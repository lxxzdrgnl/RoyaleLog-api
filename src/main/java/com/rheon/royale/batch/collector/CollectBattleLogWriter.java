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
import java.util.List;

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

    @Override
    @Transactional
    public void write(Chunk<? extends PlayerBattleLogs> chunk) {
        List<Object[]> batchArgs = new ArrayList<>();

        for (PlayerBattleLogs item : chunk) {
            for (BattleLogRaw battle : item.battles()) {
                batchArgs.add(new Object[]{
                        battle.getId().getBattleId(),
                        battle.getPlayerTag(),
                        battle.getBattleType(),
                        battle.getRawJson(),
                        Timestamp.valueOf(battle.getId().getCreatedAt())
                });
            }
            // last_crawled_at 갱신
            playerToCrawlRepository.updateLastCrawledAt(item.player().getPlayerTag());
        }

        if (!batchArgs.isEmpty()) {
            int[][] result = jdbcTemplate.batchUpdate(INSERT_SQL, batchArgs, 500,
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
    }
}
