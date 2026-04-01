package com.rheon.royale.domain.card.dao;

import com.rheon.royale.domain.card.dto.CardMetaResponse;
import com.rheon.royale.domain.card.dto.CardRankingResponse;
import com.rheon.royale.domain.card.dto.DeckStatsResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class StatsDailyRepository {

    private final JdbcTemplate jdbcTemplate;

    @Value("${stats.bayes-prior-count:500}")
    private int bayesPriorCount;

    /**
     * 덱 티어표 — meta_score = bayes_wr + 5 × usage_bonus
     *
     * meta_score = bayes_wr + 5.0 × LN(1+use_count) / LN(1+max_use_count)
     *   - bayes_wr = (C×0.5 + win) / (C + use) × 100  → 절대 성능 기준
     *   - usage_bonus = 로그 정규화 픽률 (최대 +5점)  → 메타 검증 가산점
     *   - 감점 없음: 인기 없는 덱도 승률 좋으면 상위권 유지 (장인픽 존중)
     *   - 장인 왜곡 방지: 로그 스케일이므로 1000판 vs 100판 차이 완만하게 반영
     *
     * leagueNumber: null이면 전체, not null이면 해당 리그만 필터
     */
    public List<DeckStatsResponse> findTierList(String battleType, int days, int minGames, int limit,
                                                Integer leagueNumber, Integer minTrophies, Integer maxTrophies) {
        String leagueClause   = leagueNumber  != null ? "  AND s.league_number = ?\n" : "";
        String trophiesClause = (minTrophies  != null && maxTrophies != null)
                ? "  AND s.starting_trophies BETWEEN ? AND ?\n" : "";
        String sql = """
                WITH base AS (
                    SELECT s.deck_hash,
                           s.base_deck_hash,
                           s.battle_type,
                           SUM(s.win_count)::int                                              AS win_count,
                           SUM(s.use_count)::int                                              AS use_count,
                           ROUND(SUM(s.win_count)::numeric
                                 / NULLIF(SUM(s.use_count), 0) * 100, 1)                     AS win_rate,
                           (? * 0.5 + SUM(s.win_count))
                               / (? + SUM(s.use_count)::numeric) * 100                       AS bayes_wr,
                           MAX(s.card_ids)                                                    AS card_ids,
                           MAX(s.card_evo_levels)                                            AS card_evo_levels
                    FROM stats_decks_daily_current s
                    WHERE s.battle_type = ?
                      AND s.stat_date >= CURRENT_DATE - (? * INTERVAL '1 day')
                """ + leagueClause + trophiesClause + """
                    GROUP BY s.deck_hash, s.base_deck_hash, s.battle_type
                    HAVING SUM(s.use_count) >= ?
                )
                SELECT deck_hash,
                       base_deck_hash,
                       battle_type,
                       win_count,
                       use_count,
                       win_rate,
                       ROUND(
                           bayes_wr
                           + 5.0 * LN(1.0 + use_count)
                               / NULLIF(MAX(LN(1.0 + use_count)) OVER (), 0)
                       , 1) AS score,
                       card_ids,
                       card_evo_levels
                FROM base
                ORDER BY score DESC NULLS LAST
                LIMIT ?
                """;

        List<Object> paramList = new java.util.ArrayList<>();
        paramList.add(bayesPriorCount);
        paramList.add(bayesPriorCount);
        paramList.add(battleType);
        paramList.add(days);
        if (leagueNumber  != null) paramList.add(leagueNumber);
        if (minTrophies   != null && maxTrophies != null) { paramList.add(minTrophies); paramList.add(maxTrophies); }
        paramList.add(minGames);
        paramList.add(limit);
        Object[] params = paramList.toArray();

        return jdbcTemplate.query(sql,
                (rs, rowNum) -> {
                    java.sql.Array arr = rs.getArray("card_ids");
                    long[] cardIds = arr != null
                            ? Arrays.stream((Long[]) arr.getArray()).mapToLong(Long::longValue).toArray()
                            : new long[0];
                    int[] cardEvoLevels = null;
                    java.sql.Array evoArr = rs.getArray("card_evo_levels");
                    if (evoArr != null) {
                        Object rawObj = evoArr.getArray();
                        if (rawObj instanceof Short[] shorts) {
                            cardEvoLevels = Arrays.stream(shorts).mapToInt(Short::intValue).toArray();
                        } else if (rawObj instanceof Object[] objs) {
                            cardEvoLevels = Arrays.stream(objs).mapToInt(o -> ((Number) o).intValue()).toArray();
                        }
                        System.out.println("[DEBUG] evoArr type=" + rawObj.getClass().getName() + " val=" + java.util.Arrays.toString(cardEvoLevels));
                    } else {
                        System.out.println("[DEBUG] evoArr is NULL");
                    }
                    return new DeckStatsResponse(
                            rs.getString("deck_hash"),
                            rs.getString("base_deck_hash"),
                            rs.getString("battle_type"),
                            rs.getInt("win_count"),
                            rs.getInt("use_count"),
                            rs.getBigDecimal("win_rate"),
                            rs.getBigDecimal("score"),
                            cardIds,
                            cardEvoLevels
                    );
                },
                params);
    }

    /**
     * 카드 순위 — unnest 서브쿼리 패턴 + meta_score
     *
     * unnest를 서브쿼리로 분리해야 JOIN cards가 각 카드 행에 1:1 매핑
     * → use_count 중복 집계(8x overcount) 방지
     * is_tower = false 필터: 타워 카드 제외
     * score = bayes_wr + 5.0 × LN(1+use) / LN(1+max_use)
     */
    public List<CardRankingResponse> findCardRanking(String battleType, int days,
                                                      Integer leagueNumber, Integer minTrophies, Integer maxTrophies) {
        String leagueClause   = leagueNumber != null ? "      AND s.league_number = ?\n" : "";
        String trophiesClause = (minTrophies != null && maxTrophies != null)
                ? "      AND s.starting_trophies BETWEEN ? AND ?\n" : "";
        String sql = """
                WITH card_base AS (
                    SELECT sub.card_id,
                           SUM(sub.win_count)::int                                              AS win_count,
                           SUM(sub.use_count)::int                                              AS use_count,
                           ROUND(SUM(sub.win_count)::numeric
                                 / NULLIF(SUM(sub.use_count), 0) * 100, 1)                     AS win_rate,
                           (? * 0.5 + SUM(sub.win_count))
                               / (? + SUM(sub.use_count)::numeric) * 100                       AS bayes_wr
                    FROM (
                        SELECT unnest(s.card_ids) AS card_id,
                               s.win_count,
                               s.use_count
                        FROM stats_decks_daily_current s
                        WHERE s.battle_type = ?
                          AND s.stat_date >= CURRENT_DATE - (? * INTERVAL '1 day')
                """ + leagueClause + trophiesClause + """
                    ) sub
                    JOIN cards c ON c.api_id = sub.card_id AND c.is_tower = false
                    GROUP BY sub.card_id
                    HAVING SUM(sub.use_count) >= 20
                )
                SELECT card_id,
                       win_count,
                       use_count,
                       win_rate,
                       ROUND(
                           bayes_wr
                           + 5.0 * LN(1.0 + use_count)
                               / NULLIF(MAX(LN(1.0 + use_count)) OVER (), 0)
                       , 1) AS score
                FROM card_base
                ORDER BY score DESC NULLS LAST
                LIMIT 100
                """;

        List<Object> paramList = new java.util.ArrayList<>();
        paramList.add(bayesPriorCount);
        paramList.add(bayesPriorCount);
        paramList.add(battleType);
        paramList.add(days);
        if (leagueNumber != null) paramList.add(leagueNumber);
        if (minTrophies != null && maxTrophies != null) { paramList.add(minTrophies); paramList.add(maxTrophies); }
        Object[] params = paramList.toArray();

        return jdbcTemplate.query(sql,
                (rs, rowNum) -> new CardRankingResponse(
                        rs.getLong("card_id"),
                        rs.getInt("win_count"),
                        rs.getInt("use_count"),
                        rs.getBigDecimal("win_rate"),
                        rs.getBigDecimal("score")
                ),
                params);
    }

    /**
     * 카드 메타 — id→name 매핑용 (24시간 캐시)
     * - is_deck_card=true: 덱 카드 (NORMAL/HERO)
     * - is_tower=true: 타워 지원 카드 (Tower Princess, Cannoneer 등) — deck_dictionary에 포함될 수 있으므로 함께 반환
     */
    public List<CardMetaResponse> findCardMeta() {
        return jdbcTemplate.query("""
                SELECT api_id AS id, name, elixir_cost, rarity, icon_url, is_tower, card_type
                FROM cards
                WHERE (is_deck_card = true AND card_type IN ('NORMAL', 'HERO'))
                   OR is_tower = true
                ORDER BY api_id
                """,
                (rs, rowNum) -> new CardMetaResponse(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getInt("elixir_cost"),
                        rs.getString("rarity"),
                        rs.getString("icon_url"),
                        rs.getBoolean("is_tower"),
                        rs.getString("card_type")
                ));
    }
}
