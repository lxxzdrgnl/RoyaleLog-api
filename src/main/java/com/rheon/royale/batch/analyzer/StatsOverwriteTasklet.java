package com.rheon.royale.batch.analyzer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

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

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        // 1. 전체 재집계 → stats_new
        jdbcTemplate.execute("DROP TABLE IF EXISTS stats_new");
        jdbcTemplate.execute("""
                CREATE TABLE stats_new AS
                SELECT
                    battle_date  AS stat_date,
                    deck_hash,
                    battle_type,
                    SUM(result)  AS win_count,
                    COUNT(*)     AS use_count
                FROM match_features
                GROUP BY battle_date, deck_hash, battle_type
                """);

        int count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stats_new", Integer.class);
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
            jdbcTemplate.execute("DROP TABLE IF EXISTS stats_old");
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
