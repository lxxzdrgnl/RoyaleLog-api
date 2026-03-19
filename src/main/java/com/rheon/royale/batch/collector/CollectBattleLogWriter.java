package com.rheon.royale.batch.collector;

import com.rheon.royale.batch.collector.dto.PlayerBattleLogs;
import com.rheon.royale.domain.entity.BattleLogRaw;
import com.rheon.royale.domain.repository.PlayerToCrawlRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class CollectBattleLogWriter implements ItemWriter<PlayerBattleLogs> {

    private final JdbcTemplate jdbcTemplate;
    private final PlayerToCrawlRepository playerToCrawlRepository;

    private static final String INSERT_SQL = """
            INSERT INTO battle_log_raw (battle_id, player_tag, battle_type, raw_json, created_at)
            VALUES (?, ?, ?, CAST(? AS jsonb), ?)
            ON CONFLICT (battle_id, created_at) DO NOTHING
            """;

    private static final String UPSERT_OPPONENT_SQL = """
            INSERT INTO players_to_crawl (player_tag, name, is_active, updated_at)
            VALUES (?, ?, true, NOW())
            ON CONFLICT (player_tag) DO UPDATE
                SET name       = COALESCE(EXCLUDED.name, players_to_crawl.name),
                    is_active  = true,
                    updated_at = NOW()
            """;

    @Override
    @Transactional
    public void write(Chunk<? extends PlayerBattleLogs> chunk) {
        List<Object[]> battleArgs = new ArrayList<>();
        Map<String, String> chunkOpponents = new HashMap<>();  // 청크 전체 상대방 합산

        for (PlayerBattleLogs item : chunk) {
            for (BattleLogRaw battle : item.battles()) {
                battleArgs.add(new Object[]{
                        battle.getId().getBattleId(),
                        battle.getPlayerTag(),
                        battle.getBattleType(),
                        battle.getRawJson(),
                        Timestamp.valueOf(battle.getId().getCreatedAt())
                });
            }
            // 청크 전체 상대방 합산 (같은 청크 내 중복 제거)
            chunkOpponents.putAll(item.discoveredOpponents());
            // last_crawled_at 갱신
            playerToCrawlRepository.updateLastCrawledAt(item.player().getPlayerTag());
        }

        if (!battleArgs.isEmpty()) {
            int[][] result = jdbcTemplate.batchUpdate(INSERT_SQL, battleArgs, 500,
                    (ps, args) -> {
                        ps.setString(1, (String) args[0]);
                        ps.setString(2, (String) args[1]);
                        ps.setString(3, (String) args[2]);
                        ps.setString(4, (String) args[3]);
                        ps.setTimestamp(5, (Timestamp) args[4]);
                    });

            int total = 0;
            for (int[] batch : result) {
                for (int r : batch) {
                    if (r >= 0) total += r;
                }
            }
            log.debug("[Writer] battle_log_raw INSERT {}건 (신규)", total);
        }

        // 상대방 UPSERT — batchUpdate 1회로 묶어서 커넥션 절약
        // playerTag 오름차순 정렬: ON CONFLICT DO UPDATE 데드락 방지 (트랜잭션 간 락 획득 순서 일관화)
        if (!chunkOpponents.isEmpty()) {
            List<Object[]> opponentArgs = chunkOpponents.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(e -> new Object[]{e.getKey(), e.getValue()})
                    .toList();

            jdbcTemplate.batchUpdate(UPSERT_OPPONENT_SQL, opponentArgs, opponentArgs.size(),
                    (ps, args) -> {
                        ps.setString(1, (String) args[0]);
                        ps.setString(2, (String) args[1]);
                    });

            log.debug("[Writer] players_to_crawl 상대방 UPSERT {}명", chunkOpponents.size());
        }
    }
}
