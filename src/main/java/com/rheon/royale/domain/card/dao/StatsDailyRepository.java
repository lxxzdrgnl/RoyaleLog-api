package com.rheon.royale.domain.card.dao;

import com.rheon.royale.domain.card.dto.DeckStatsResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class StatsDailyRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * stats_current VIEW에서 덱 통계 집계
     *
     * - HAVING SUM(use_count) >= minGames: 표본 부족 덱 제외 (신뢰도 보장)
     * - win_rate: win_count/use_count * 100 (%), NULLIF 로 0 나누기 방지
     * - ORDER BY win_rate DESC: 승률 높은 덱 먼저
     */
    public List<DeckStatsResponse> findTierList(String battleType, int days, int minGames, int limit) {
        return jdbcTemplate.query("""
                SELECT deck_hash,
                       battle_type,
                       SUM(win_count)::int                                       AS win_count,
                       SUM(use_count)::int                                       AS use_count,
                       ROUND(SUM(win_count)::numeric
                             / NULLIF(SUM(use_count), 0) * 100, 1)              AS win_rate
                FROM stats_current
                WHERE battle_type = ?
                  AND stat_date >= CURRENT_DATE - (? * INTERVAL '1 day')
                GROUP BY deck_hash, battle_type
                HAVING SUM(use_count) >= ?
                ORDER BY win_rate DESC NULLS LAST, SUM(use_count) DESC
                LIMIT ?
                """,
                (rs, rowNum) -> new DeckStatsResponse(
                        rs.getString("deck_hash"),
                        rs.getString("battle_type"),
                        rs.getInt("win_count"),
                        rs.getInt("use_count"),
                        rs.getBigDecimal("win_rate")
                ),
                battleType, days, minGames, limit);
    }
}
