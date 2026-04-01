package com.rheon.royale.batch.collector;

import com.rheon.royale.batch.collector.dto.DiscoveredOpponent;
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

    // 발견된 상대방 UPSERT: 트로피/리그/브라켓도 함께 저장
    // COALESCE: 이미 존재하는 유저라면 기존 값을 덮어쓰지 않음 (null 무시)
    private static final String UPSERT_OPPONENT_SQL = """
            INSERT INTO players_to_crawl (player_tag, name, is_active, current_trophies, league_number, bracket, updated_at)
            VALUES (?, ?, true, ?, ?, ?, NOW())
            ON CONFLICT (player_tag) DO UPDATE
                SET name             = EXCLUDED.name,
                    current_trophies = COALESCE(EXCLUDED.current_trophies, players_to_crawl.current_trophies),
                    league_number    = COALESCE(EXCLUDED.league_number,    players_to_crawl.league_number),
                    bracket          = COALESCE(EXCLUDED.bracket,          players_to_crawl.bracket),
                    updated_at       = NOW()
            WHERE players_to_crawl.name             IS DISTINCT FROM EXCLUDED.name
               OR players_to_crawl.current_trophies IS DISTINCT FROM EXCLUDED.current_trophies
               OR players_to_crawl.league_number    IS DISTINCT FROM EXCLUDED.league_number
            """;

    @Override
    @Transactional
    public void write(Chunk<? extends PlayerBattleLogs> chunk) {
        List<Object[]> battleArgs = new ArrayList<>();
        Map<String, DiscoveredOpponent> chunkOpponents = new HashMap<>();  // 청크 전체 상대방 합산

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
            // last_crawled_at + 트로피/리그/브라켓 갱신
            playerToCrawlRepository.updateAfterCrawl(
                    item.player().getPlayerTag(),
                    item.currentTrophies(),
                    item.leagueNumber(),
                    item.bracket());
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
                    .map(e -> {
                        DiscoveredOpponent opp = e.getValue();
                        // 플레이어 브라켓: leagueNumber 있으면 pol_N, 없으면 trophies 기반
                        String bracket = BracketBattleCounter.toBracket(
                                opp.leagueNumber() != null ? "pathOfLegend" : "PvP",
                                opp.leagueNumber(),
                                opp.trophies());
                        return new Object[]{
                                e.getKey(),
                                opp.name(),
                                opp.trophies(),
                                opp.leagueNumber(),
                                bracket
                        };
                    })
                    .toList();

            // 50개씩 나눠서 UPSERT — 대량 GIN 업데이트 분산
            int batchSize = 50;
            for (int i = 0; i < opponentArgs.size(); i += batchSize) {
                List<Object[]> sub = opponentArgs.subList(i, Math.min(i + batchSize, opponentArgs.size()));
                jdbcTemplate.batchUpdate(UPSERT_OPPONENT_SQL, sub, sub.size(),
                        (ps, args) -> {
                            ps.setString(1, (String) args[0]);
                            ps.setString(2, (String) args[1]);
                            // trophies / league / bracket: null 가능
                            if (args[2] != null) ps.setInt(3, (Integer) args[2]);
                            else                 ps.setNull(3, java.sql.Types.INTEGER);
                            if (args[3] != null) ps.setInt(4, (Integer) args[3]);
                            else                 ps.setNull(4, java.sql.Types.INTEGER);
                            if (args[4] != null) ps.setString(5, (String) args[4]);
                            else                 ps.setNull(5, java.sql.Types.VARCHAR);
                        });
            }

            log.debug("[Writer] players_to_crawl 상대방 UPSERT {}명", chunkOpponents.size());
        }
    }
}
