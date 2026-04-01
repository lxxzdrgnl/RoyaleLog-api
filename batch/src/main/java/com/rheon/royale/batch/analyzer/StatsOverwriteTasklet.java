package com.rheon.royale.batch.analyzer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * stats_decks_daily_current 재계산 — Rename Swap 방식
 *
 * 왜 VIEW가 아닌 pointer table인가:
 *   - VIEW는 underlying table 그대로 참조 → swap 중 dirty read 발생 가능
 *   - 테이블 RENAME은 카탈로그 레벨 원자 연산 → API는 항상 완전한 스냅샷만 조회
 *
 * 왜 DELETE + INSERT를 쓰지 않나:
 *   - row-level DELETE → vacuum 필요 → dead tuple 누적
 *   - 인덱스 재구축 비용
 *   - lock 오래 잡힘 → 서비스 조회 차단
 *
 * Rename Swap 절차:
 *   1. stats_new 에 match_features 전체 재집계
 *   2. 트랜잭션:
 *      RENAME stats_decks_daily_current → stats_old  (기존 → 격리)
 *      RENAME stats_new → stats_decks_daily_current  (신규 → 서빙)
 *   3. stats_old DROP (안전하게 COMMIT 이후)
 *
 * → catalog-level atomic swap, zero dirty read
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StatsOverwriteTasklet implements Tasklet {

    private final JdbcTemplate jdbcTemplate;
    private final CacheManager cacheManager;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        // 1. 최근 8일 재집계 → stats_new
        //    deck_hash = refined_deck_hash (진화/히어로 인식 정밀 해시, 없으면 base로 fallback)
        //    card_ids, card_evo_levels: match_features에서 직접 집계 (deck_dictionary 불필요)
        // 이전 실행 실패로 남은 잔여물 정리 (멱등성 보장)
        jdbcTemplate.execute("DROP TABLE IF EXISTS stats_old CASCADE");
        jdbcTemplate.execute("DROP TABLE IF EXISTS stats_new");
        jdbcTemplate.execute("""
                CREATE TABLE stats_new AS
                SELECT
                    battle_date                                       AS stat_date,
                    COALESCE(refined_deck_hash, deck_hash)            AS deck_hash,
                    deck_hash                                         AS base_deck_hash,
                    battle_type,
                    league_number,
                    starting_trophies,
                    SUM(result)::int                                  AS win_count,
                    COUNT(*)::int                                     AS use_count,
                    (array_agg(card_ids))[1]                          AS card_ids,
                    (array_agg(card_evo_levels))[1]                   AS card_evo_levels
                FROM match_features
                WHERE battle_date >= CURRENT_DATE - 8
                GROUP BY battle_date,
                         COALESCE(refined_deck_hash, deck_hash),
                         deck_hash,
                         battle_type,
                         league_number,
                         starting_trophies
                """);

        // 인덱스 생성 (RENAME 전 → API 조회 시 Full Scan 방지)
        jdbcTemplate.execute("CREATE INDEX ON stats_new (deck_hash)");
        jdbcTemplate.execute("CREATE INDEX ON stats_new (stat_date, battle_type)");

        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stats_new", Integer.class);
        if (count == null) count = 0;
        log.info("[StatsOverwrite] 집계 완료: {}건 → swap 시작", count);

        // 2. Rename swap (트랜잭션: 카탈로그 레벨 원자 연산)
        boolean currentExists = tableExists("stats_decks_daily_current");

        jdbcTemplate.execute("BEGIN");
        try {
            if (currentExists) {
                jdbcTemplate.execute(
                        "ALTER TABLE stats_decks_daily_current RENAME TO stats_old");
            }
            jdbcTemplate.execute(
                    "ALTER TABLE stats_new RENAME TO stats_decks_daily_current");
            jdbcTemplate.execute("COMMIT");
        } catch (Exception e) {
            jdbcTemplate.execute("ROLLBACK");
            throw e;
        }

        // 3. 격리된 기존 테이블 DROP (COMMIT 이후 → 안전)
        if (currentExists) {
            jdbcTemplate.execute("DROP TABLE IF EXISTS stats_old CASCADE");
        }

        // 4. Redis 캐시 eviction — swap 완료 후 최초 요청 시 신규 데이터 반환 보장
        for (String name : List.of("tierList_1", "tierList_3", "tierList_7",
                                   "cardRanking_1", "cardRanking_3", "cardRanking_7")) {
            Cache cache = cacheManager.getCache(name);
            if (cache != null) cache.clear();
        }

        log.info("[StatsOverwrite] stats_decks_daily_current swap 완료: {}건", count);
        contribution.incrementWriteCount(count);
        return RepeatStatus.FINISHED;
    }

    private boolean tableExists(String tableName) {
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_tables WHERE tablename = ?",
                Integer.class,
                tableName
        );
        return cnt != null && cnt > 0;
    }
}
