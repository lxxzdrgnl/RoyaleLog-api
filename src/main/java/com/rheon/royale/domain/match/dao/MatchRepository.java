package com.rheon.royale.domain.match.dao;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rheon.royale.batch.analyzer.DeckAnalyzerProcessor;
import com.rheon.royale.batch.analyzer.dto.AnalyzedBattle;
import com.rheon.royale.domain.entity.BattleLogRaw;
import com.rheon.royale.domain.entity.BattleLogRawId;
import com.rheon.royale.domain.match.application.OnDemandMatchService;
import com.rheon.royale.domain.match.dto.BattleEntry;
import com.rheon.royale.domain.match.dto.PlayerSearchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Repository
@RequiredArgsConstructor
public class MatchRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final DeckAnalyzerProcessor deckAnalyzerProcessor;
    private final OnDemandMatchService onDemandMatchService;

    /**
     * 랭커 전적 조회 — battle_log_raw.raw_json 기반 파싱
     * OnDemandMatchService.toParticipant() 재사용 → 응답 형태 통일
     */
    public List<BattleEntry> findByPlayerTag(String playerTag, int limit, int offset) {
        List<RawRow> rows = jdbcTemplate.query("""
                SELECT battle_id, battle_type, created_at, raw_json
                FROM battle_log_raw
                WHERE player_tag = ?
                ORDER BY created_at DESC
                LIMIT ? OFFSET ?
                """,
                (rs, rowNum) -> new RawRow(
                        rs.getString("battle_id"),
                        rs.getString("battle_type"),
                        rs.getTimestamp("created_at"),
                        rs.getString("raw_json")
                ),
                playerTag, limit, offset);

        List<BattleEntry> entries = new ArrayList<>();
        for (RawRow row : rows) {
            try {
                JsonNode battle = objectMapper.readTree(row.rawJson());
                var createdAt = row.createdAt().toLocalDateTime();

                BattleLogRaw raw = BattleLogRaw.builder()
                        .id(new BattleLogRawId(row.battleId(), createdAt))
                        .playerTag(playerTag)
                        .battleType(row.battleType())
                        .rawJson(row.rawJson())
                        .build();

                AnalyzedBattle analyzed = deckAnalyzerProcessor.process(raw);

                JsonNode teamPlayer     = battle.path("team").get(0);
                JsonNode opponentPlayer = battle.path("opponent").get(0);

                String gameMode = battle.path("gameMode").path("name").asText(null);
                entries.add(new BattleEntry(
                        row.battleId(),
                        row.battleType(),
                        gameMode,
                        createdAt,
                        onDemandMatchService.toParticipant(teamPlayer,
                                analyzed != null ? analyzed.deckHash() : null,
                                analyzed != null ? analyzed.avgLevel() : null,
                                analyzed != null ? analyzed.evolutionCount() : 0),
                        onDemandMatchService.toParticipant(opponentPlayer,
                                analyzed != null ? analyzed.opponentHash() : null,
                                null, 0)
                ));
            } catch (Exception e) {
                log.warn("[MatchRepository] {} 파싱 실패: {}", row.battleId(), e.getMessage());
            }
        }
        return entries;
    }

    /**
     * 닉네임 검색 — pg_trgm GIN 인덱스 활용 (ILIKE '%name%' 양방향)
     * currentRank IS NULL → 상대방 발견으로 추가된 유저 (PoL 랭커 아님)
     */
    public List<PlayerSearchResult> searchByName(String name) {
        return jdbcTemplate.query("""
                SELECT player_tag, name, current_rank
                FROM players_to_crawl
                WHERE name ILIKE ?
                ORDER BY current_rank ASC NULLS LAST
                LIMIT 20
                """,
                (rs, rowNum) -> new PlayerSearchResult(
                        rs.getString("player_tag"),
                        rs.getString("name"),
                        (Integer) rs.getObject("current_rank")
                ),
                "%" + name + "%");
    }

    public boolean existsByPlayerTag(String playerTag) {
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM battle_log_raw WHERE player_tag = ? LIMIT 1",
                Integer.class, playerTag);
        return cnt != null && cnt > 0;
    }

    private record RawRow(String battleId, String battleType, Timestamp createdAt, String rawJson) {}
}
