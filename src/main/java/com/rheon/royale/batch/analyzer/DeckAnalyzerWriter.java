package com.rheon.royale.batch.analyzer;

import com.rheon.royale.batch.analyzer.dto.AnalyzedBattle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeckAnalyzerWriter implements ItemWriter<AnalyzedBattle> {

    private final JdbcTemplate jdbcTemplate;
    private final AnalyzerMetaService analyzerMetaService;

    @Override
    @Transactional
    public void write(Chunk<? extends AnalyzedBattle> chunk) throws Exception {
        int currentVersion = analyzerMetaService.currentVersion();
        for (AnalyzedBattle battle : chunk) {
            upsertDeckDictionary(battle.deckHash(), battle.cardIds());
            upsertDeckDictionary(battle.opponentHash(), battle.opponentCardIds());
            insertMatchFeature(battle);
            markProcessed(battle.battleId(), battle.createdAt(), currentVersion);
        }
        log.debug("[DeckAnalyzerWriter] {}건 처리", chunk.size());
    }

    /**
     * deck_dictionary: ON CONFLICT DO UPDATE (mutable)
     *   - DO NOTHING은 로직 변경 후 재처리 시 card_ids 갱신 불가 → 위험
     *   - DO UPDATE: 새 card_ids 로 덮어쓰기 → 분석 로직 변경에 유연
     *   - Race Condition 방어: DB unique constraint (PRIMARY KEY) 가 책임짐
     *   - card_ids TEXT: "id1-id2-id3" (숫자 오름차순, DeckHashUtils 직렬화 규칙과 동일)
     *     BIGINT[] 미사용 이유: B-tree 인덱스 불가, equality 느림, 정렬 보장 추적 어려움
     */
    private void upsertDeckDictionary(String deckHash, Long[] cardIds) {
        if (deckHash == null || cardIds == null) return;
        jdbcTemplate.update("""
                INSERT INTO deck_dictionary (deck_hash, card_ids)
                VALUES (?, ?)
                ON CONFLICT (deck_hash)
                DO UPDATE SET card_ids = EXCLUDED.card_ids
                """,
                deckHash,
                toCardIdsText(cardIds));
    }

    /** card_ids 배열 → TEXT 직렬화 (숫자 오름차순, "-" 구분자) */
    private static String toCardIdsText(Long[] cardIds) {
        return Arrays.stream(cardIds)
                .sorted(Long::compareTo)
                .map(String::valueOf)
                .collect(Collectors.joining("-"));
    }

    /**
     * match_features: ON CONFLICT DO UPDATE (conditional merge)
     *   - DO NOTHING = "dedup" → late arrival 데이터 유실 위험
     *   - DO UPDATE WHERE updated_at < EXCLUDED.updated_at:
     *     최신 데이터만 덮어쓰기 → 재처리 폭풍 방어 (불필요한 WAL 쓰기 방지)
     *   - canonical battle_id: MD5(sorted tags + battleTime with ms precision)
     *     → 양쪽 플레이어 크롤 시 동일 ID → 한 게임 = 한 row 보장
     */
    private void insertMatchFeature(AnalyzedBattle battle) {
        jdbcTemplate.update("""
                INSERT INTO match_features
                    (battle_id, deck_hash, opponent_hash, battle_type,
                     battle_date, avg_level, evolution_count, result, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW())
                ON CONFLICT (battle_id, battle_date)
                DO UPDATE SET
                    deck_hash       = EXCLUDED.deck_hash,
                    opponent_hash   = EXCLUDED.opponent_hash,
                    avg_level       = EXCLUDED.avg_level,
                    evolution_count = EXCLUDED.evolution_count,
                    result          = EXCLUDED.result,
                    updated_at      = NOW()
                WHERE match_features.updated_at < EXCLUDED.updated_at
                """,
                battle.battleId(),
                battle.deckHash(),
                battle.opponentHash(),
                battle.battleType(),
                Date.valueOf(battle.battleDate()),
                battle.avgLevel(),
                battle.evolutionCount(),
                battle.result());
    }

    /** partition-aware UPDATE: created_at 포함 → 파티션 프루닝 */
    private void markProcessed(String battleId, java.time.LocalDateTime createdAt, int version) {
        jdbcTemplate.update("""
                UPDATE battle_log_raw
                SET analyzer_processed_at = NOW(),
                    analyzer_version      = ?
                WHERE battle_id = ? AND created_at = ?
                """,
                version,
                battleId,
                Timestamp.valueOf(createdAt));
    }
}
