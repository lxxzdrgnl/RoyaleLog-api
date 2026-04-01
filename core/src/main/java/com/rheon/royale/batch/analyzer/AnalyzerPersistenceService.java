package com.rheon.royale.batch.analyzer;

import com.rheon.royale.batch.analyzer.dto.AnalyzedBattle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.util.List;

/**
 * Shared analyzer persistence — lives in :core so both :api (OnDemandMatchService)
 * and :batch (DeckAnalyzerWriter) can use it without a cross-module dependency.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyzerPersistenceService {

    private final JdbcTemplate jdbcTemplate;

    /**
     * On-Demand full persist: match_features → mark processed.
     * @Transactional here because OnDemandMatchService has no outer transaction.
     */
    @Transactional
    public void persistOnDemand(List<AnalyzedBattle> items) {
        if (items.isEmpty()) return;
        int version = currentAnalyzerVersion();
        batchInsertMatchFeatures(items);
        batchMarkProcessed(items, version);
        log.debug("[AnalyzerPersistenceService] On-Demand {}건 저장", items.size());
    }

    /** ON CONFLICT guard: only update when incoming record is newer */
    public void batchInsertMatchFeatures(List<? extends AnalyzedBattle> items) {
        jdbcTemplate.batchUpdate("""
                INSERT INTO match_features
                    (battle_id, deck_hash, refined_deck_hash,
                     opponent_hash, refined_opponent_hash,
                     battle_type, battle_date, avg_level, evolution_count, result,
                     league_number, starting_trophies,
                     card_ids, card_evo_levels, card_levels,
                     opponent_card_ids, opponent_card_levels, opponent_card_evo_levels,
                     updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
                ON CONFLICT (battle_id, battle_date)
                DO UPDATE SET
                    deck_hash                = EXCLUDED.deck_hash,
                    refined_deck_hash        = EXCLUDED.refined_deck_hash,
                    opponent_hash            = EXCLUDED.opponent_hash,
                    refined_opponent_hash    = EXCLUDED.refined_opponent_hash,
                    avg_level                = EXCLUDED.avg_level,
                    evolution_count          = EXCLUDED.evolution_count,
                    result                   = EXCLUDED.result,
                    league_number            = EXCLUDED.league_number,
                    starting_trophies        = EXCLUDED.starting_trophies,
                    card_ids                 = EXCLUDED.card_ids,
                    card_evo_levels          = EXCLUDED.card_evo_levels,
                    card_levels              = EXCLUDED.card_levels,
                    opponent_card_ids        = EXCLUDED.opponent_card_ids,
                    opponent_card_levels     = EXCLUDED.opponent_card_levels,
                    opponent_card_evo_levels = EXCLUDED.opponent_card_evo_levels,
                    updated_at               = NOW()
                WHERE match_features.updated_at < EXCLUDED.updated_at
                """,
                items, items.size(),
                (ps, b) -> {
                    ps.setString(1, b.battleId());
                    ps.setString(2, b.deckHash());
                    ps.setString(3, b.refinedDeckHash());
                    ps.setString(4, b.opponentHash());
                    ps.setString(5, b.refinedOpponentHash());
                    ps.setString(6, b.battleType());
                    ps.setDate(7, Date.valueOf(b.battleDate()));
                    ps.setBigDecimal(8, b.avgLevel());
                    ps.setInt(9, b.evolutionCount());
                    ps.setInt(10, b.result());
                    if (b.leagueNumber() != null) ps.setInt(11, b.leagueNumber());
                    else ps.setNull(11, java.sql.Types.INTEGER);
                    if (b.startingTrophies() != null) ps.setInt(12, b.startingTrophies());
                    else ps.setNull(12, java.sql.Types.INTEGER);
                    // card_ids (13)
                    if (b.cardIds() != null)
                        ps.setArray(13, ps.getConnection().createArrayOf("bigint", b.cardIds()));
                    else ps.setNull(13, java.sql.Types.ARRAY);
                    // card_evo_levels (14)
                    if (b.evoLevels() != null)
                        ps.setArray(14, ps.getConnection().createArrayOf("smallint", b.evoLevels()));
                    else ps.setNull(14, java.sql.Types.ARRAY);
                    // card_levels (15) — per-card level array
                    if (b.cardLevels() != null)
                        ps.setArray(15, ps.getConnection().createArrayOf("smallint", b.cardLevels()));
                    else ps.setNull(15, java.sql.Types.ARRAY);
                    // opponent_card_ids (16)
                    if (b.opponentCardIds() != null)
                        ps.setArray(16, ps.getConnection().createArrayOf("bigint", b.opponentCardIds()));
                    else ps.setNull(16, java.sql.Types.ARRAY);
                    // opponent_card_levels (17)
                    if (b.opponentCardLevels() != null)
                        ps.setArray(17, ps.getConnection().createArrayOf("smallint", b.opponentCardLevels()));
                    else ps.setNull(17, java.sql.Types.ARRAY);
                    // opponent_card_evo_levels (18)
                    if (b.opponentEvoLevels() != null)
                        ps.setArray(18, ps.getConnection().createArrayOf("smallint", b.opponentEvoLevels()));
                    else ps.setNull(18, java.sql.Types.ARRAY);
                });
    }

    /** Marks battle_log_raw rows processed — partition-aware via created_at */
    public void batchMarkProcessed(List<? extends AnalyzedBattle> items, int version) {
        jdbcTemplate.batchUpdate("""
                UPDATE battle_log_raw
                SET analyzer_processed_at = NOW(), analyzer_version = ?
                WHERE battle_id = ? AND created_at = ?
                """,
                items, items.size(),
                (ps, b) -> {
                    ps.setInt(1, version);
                    ps.setString(2, b.battleId());
                    ps.setTimestamp(3, Timestamp.valueOf(b.createdAt()));
                });
    }

    private int currentAnalyzerVersion() {
        Integer v = jdbcTemplate.queryForObject(
                "SELECT current_version FROM analyzer_meta WHERE id = 1", Integer.class);
        if (v == null) throw new IllegalStateException("analyzer_meta row not found");
        return v;
    }
}
