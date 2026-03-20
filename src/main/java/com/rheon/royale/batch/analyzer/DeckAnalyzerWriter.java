package com.rheon.royale.batch.analyzer;

import com.rheon.royale.batch.analyzer.dto.AnalyzedBattle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

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
        List<? extends AnalyzedBattle> items = chunk.getItems();

        // WAL 동기화 비활성화 — 배치 전용 세션 최적화 (크래시 시 일부 누락 가능하나 재실행으로 복구)
        jdbcTemplate.execute("SET LOCAL synchronous_commit = off");

        batchUpsertDeckDictionary(items);
        batchInsertMatchFeatures(items);
        batchMarkProcessed(items, currentVersion);

        log.debug("[DeckAnalyzerWriter] {}건 처리", items.size());
    }

    /**
     * deck_dictionary: ON CONFLICT DO UPDATE (mutable)
     *   - 키: base deck_hash (카드 ID만, 진화 무관) → "같은 구성" 덱 단위 조회용
     *   - card_ids: BIGINT[] — GIN 인덱스로 특정 카드 포함 덱 O(1) 조회 가능
     *     ex) WHERE card_ids @> ARRAY[26000000]::bigint[]
     *   - 왜 이전 TEXT에서 BIGINT[]로 바꿨나:
     *     TEXT("id1-id2-id3")는 LIKE 검색 불가 + GIN 인덱스 부적합
     *     BIGINT[]는 GIN 인덱스 완벽 지원 + && (교집합) 연산자로 유사덱 쿼리 가능
     */
    /**
     * deck_dictionary batchUpdate — chunk 전체를 한 번에 전송
     * 플레이어 덱 + 상대 덱 모두 포함, null 제거
     */
    private void batchUpsertDeckDictionary(List<? extends AnalyzedBattle> items) {
        List<AnalyzedBattle> all = new ArrayList<>();
        for (AnalyzedBattle b : items) {
            if (b.deckHash() != null && b.cardIds() != null) all.add(b);
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO deck_dictionary (deck_hash, card_ids)
                VALUES (?, ?)
                ON CONFLICT (deck_hash) DO UPDATE SET card_ids = EXCLUDED.card_ids
                """,
                all,
                all.size(),
                (ps, b) -> {
                    ps.setString(1, b.deckHash());
                    ps.setArray(2, ps.getConnection().createArrayOf("bigint", b.cardIds()));
                });
    }

    /**
     * match_features batchUpdate — 건건이 UPSERT → chunk 단위 일괄 전송
     * ON CONFLICT (battle_id, battle_date): 파티션 키 포함 → 파티션 프루닝 보장
     */
    private void batchInsertMatchFeatures(List<? extends AnalyzedBattle> items) {
        jdbcTemplate.batchUpdate("""
                INSERT INTO match_features
                    (battle_id, deck_hash, refined_deck_hash,
                     opponent_hash, refined_opponent_hash,
                     battle_type, battle_date, avg_level, evolution_count, result, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
                ON CONFLICT (battle_id, battle_date)
                DO UPDATE SET
                    deck_hash             = EXCLUDED.deck_hash,
                    refined_deck_hash     = EXCLUDED.refined_deck_hash,
                    opponent_hash         = EXCLUDED.opponent_hash,
                    refined_opponent_hash = EXCLUDED.refined_opponent_hash,
                    avg_level             = EXCLUDED.avg_level,
                    evolution_count       = EXCLUDED.evolution_count,
                    result                = EXCLUDED.result,
                    updated_at            = NOW()
                WHERE match_features.updated_at < EXCLUDED.updated_at
                """,
                items,
                items.size(),
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
                });
    }

    /** partition-aware batchUpdate: created_at 포함 → 파티션 프루닝 */
    private void batchMarkProcessed(List<? extends AnalyzedBattle> items, int version) {
        jdbcTemplate.batchUpdate("""
                UPDATE battle_log_raw
                SET analyzer_processed_at = NOW(), analyzer_version = ?
                WHERE battle_id = ? AND created_at = ?
                """,
                items,
                items.size(),
                (ps, b) -> {
                    ps.setInt(1, version);
                    ps.setString(2, b.battleId());
                    ps.setTimestamp(3, Timestamp.valueOf(b.createdAt()));
                });
    }
}
