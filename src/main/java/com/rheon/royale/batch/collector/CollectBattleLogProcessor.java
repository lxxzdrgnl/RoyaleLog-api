package com.rheon.royale.batch.collector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rheon.royale.batch.collector.dto.PlayerBattleLogs;
import com.rheon.royale.domain.entity.BattleLogRaw;
import com.rheon.royale.domain.entity.BattleLogRawId;
import com.rheon.royale.domain.entity.PlayerToCrawl;
import com.rheon.royale.global.util.BattleHashUtils;
import com.rheon.royale.infrastructure.external.clashroyale.ClashRoyaleClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class CollectBattleLogProcessor implements ItemProcessor<PlayerToCrawl, PlayerBattleLogs> {

    private static final int RETENTION_DAYS = 30; // 30일 이상 된 배틀은 저장하지 않음

    private final ClashRoyaleClient clashRoyaleClient;
    private final ObjectMapper objectMapper;

    @Override
    public PlayerBattleLogs process(PlayerToCrawl player) throws Exception {
        String rawJson = clashRoyaleClient.getBattleLog(player.getPlayerTag());
        JsonNode battles = objectMapper.readTree(rawJson);

        List<BattleLogRaw> polBattles = new ArrayList<>();
        Map<String, String> discoveredOpponents = new HashMap<>();

        for (JsonNode battle : battles) {
            String battleTime = battle.path("battleTime").asText(null);
            if (battleTime == null) {
                continue;
            }

            JsonNode team = battle.path("team");
            JsonNode opponent = battle.path("opponent");
            if (team.isEmpty() || opponent.isEmpty()) {
                continue;
            }

            String playerTag = team.get(0).path("tag").asText();
            String opponentTag = opponent.get(0).path("tag").asText();
            String opponentName = opponent.get(0).path("name").asText(null);

            // 상대방 발견 → BFS 풀 확장용 누적 (tag → name, 중복 자동 제거)
            if (opponentTag != null && !opponentTag.isBlank()) {
                discoveredOpponents.put(opponentTag, opponentName);
            }

            String battleId = BattleHashUtils.battleId(playerTag, opponentTag, battleTime);
            var createdAt = BattleHashUtils.parseBattleTime(battleTime);

            // 30일 이상 된 배틀(잠수 유저 등) → 파티션 없음 + 의미 없는 데이터이므로 skip
            if (createdAt.isBefore(LocalDateTime.now().minusDays(RETENTION_DAYS))) {
                log.debug("[Processor] {}: 오래된 배틀 skip ({})", player.getPlayerTag(), battleTime);
                continue;
            }

            String battleType = battle.path("type").asText("unknown");

            BattleLogRaw row = BattleLogRaw.builder()
                    .id(new BattleLogRawId(battleId, createdAt))
                    .playerTag(player.getPlayerTag())
                    .battleType(battleType)
                    .rawJson(pruneJson((ObjectNode) battle.deepCopy()))
                    .build();

            polBattles.add(row);
        }

        if (polBattles.isEmpty()) {
            log.debug("[Processor] {}: 수집할 배틀 없음 (잠수 or 전적 없음) → skip", player.getPlayerTag());
            return null; // ItemProcessor null → Spring Batch가 자동으로 필터링
        }

        log.debug("[Processor] {}: 배틀 {}건, 상대방 {}명 발견", player.getPlayerTag(), polBattles.size(), discoveredOpponents.size());
        return new PlayerBattleLogs(player, polBattles, discoveredOpponents);
    }

    /**
     * 저장 전 불필요 필드 제거 — cards 테이블에 있거나 분석에 불필요한 정적 메타
     *
     * 제거 대상:
     *   카드: name, iconUrls, rarity, maxLevel, maxEvolutionLevel, starLevel
     *   배틀: deckSelection, isHostedMatch, isLadderTournament, globalRank
     *
     * 유지 대상 (분석 핵심):
     *   카드: id, level, evolutionLevel, elixirCost
     *   배틀: type, battleTime, arena, gameMode, leagueNumber,
     *         startingTrophies, trophyChange, crowns, elixirLeaked,
     *         kingTowerHitPoints, princessTowersHitPoints
     */
    private String pruneJson(ObjectNode battle) {
        battle.remove(List.of("deckSelection", "isHostedMatch", "isLadderTournament"));

        for (String side : List.of("team", "opponent")) {
            JsonNode sideNode = battle.path(side);
            for (JsonNode playerNode : sideNode) {
                ObjectNode p = (ObjectNode) playerNode;
                p.remove("globalRank");
                pruneCards(p.path("cards"));
                pruneCards(p.path("supportCards"));
            }
        }
        return battle.toString();
    }

    private void pruneCards(JsonNode cardsNode) {
        for (JsonNode cardNode : cardsNode) {
            ObjectNode card = (ObjectNode) cardNode;
            card.remove("name");
            card.remove("iconUrls");
            card.remove("rarity");
            card.remove("maxLevel");
            card.remove("maxEvolutionLevel");
            card.remove("starLevel");
        }
    }
}
