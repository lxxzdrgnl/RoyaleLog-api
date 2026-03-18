package com.rheon.royale.domain.match.dao;

import com.rheon.royale.domain.match.dto.BattleLogEntry;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class MatchRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 플레이어의 최근 배틀 조회
     *
     * battle_log_raw LEFT JOIN match_features:
     *   - LEFT JOIN: 분석 전 배틀도 반환 (result/deckHash null)
     *   - battle_date = created_at::date: partition pruning 지원
     *   - ORDER BY created_at DESC: 최신 배틀 먼저
     */
    public List<BattleLogEntry> findByPlayerTag(String playerTag, int limit) {
        return jdbcTemplate.query("""
                SELECT blr.battle_id,
                       blr.battle_type,
                       blr.created_at                   AS battle_time,
                       blr.created_at::date             AS battle_date,
                       mf.deck_hash,
                       mf.opponent_hash,
                       mf.result,
                       mf.avg_level,
                       mf.evolution_count
                FROM battle_log_raw blr
                LEFT JOIN match_features mf
                       ON mf.battle_id   = blr.battle_id
                      AND mf.battle_date = blr.created_at::date
                WHERE blr.player_tag = ?
                ORDER BY blr.created_at DESC
                LIMIT ?
                """,
                (rs, rowNum) -> new BattleLogEntry(
                        rs.getString("battle_id"),
                        rs.getString("battle_type"),
                        rs.getTimestamp("battle_time").toLocalDateTime(),
                        rs.getDate("battle_date").toLocalDate(),
                        rs.getString("deck_hash"),
                        rs.getString("opponent_hash"),
                        rs.getObject("result") != null ? rs.getInt("result") : null,
                        rs.getBigDecimal("avg_level"),
                        rs.getObject("evolution_count") != null ? rs.getInt("evolution_count") : null
                ),
                playerTag, limit);
    }

    public boolean existsByPlayerTag(String playerTag) {
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM battle_log_raw WHERE player_tag = ? LIMIT 1",
                Integer.class, playerTag);
        return cnt != null && cnt > 0;
    }
}
