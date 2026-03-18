package com.rheon.royale.batch.collector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class CollectBattleLogProcessor implements ItemProcessor<PlayerToCrawl, PlayerBattleLogs> {

    private static final String BATTLE_TYPE_POL = "pathOfLegend";

    private final ClashRoyaleClient clashRoyaleClient;
    private final ObjectMapper objectMapper;

    @Override
    public PlayerBattleLogs process(PlayerToCrawl player) throws Exception {
        String rawJson = clashRoyaleClient.getBattleLog(player.getPlayerTag());
        JsonNode battles = objectMapper.readTree(rawJson);

        List<BattleLogRaw> polBattles = new ArrayList<>();

        for (JsonNode battle : battles) {
            String type = battle.path("type").asText();
            if (!BATTLE_TYPE_POL.equals(type)) {
                continue;
            }

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

            String battleId = BattleHashUtils.battleId(playerTag, opponentTag, battleTime);
            var createdAt = BattleHashUtils.parseBattleTime(battleTime);

            BattleLogRaw row = BattleLogRaw.builder()
                    .id(new BattleLogRawId(battleId, createdAt))
                    .playerTag(player.getPlayerTag())
                    .battleType(BATTLE_TYPE_POL)
                    .rawJson(battle.toString())
                    .build();

            polBattles.add(row);
        }

        log.debug("[Processor] {}: PoL 배틀 {}건", player.getPlayerTag(), polBattles.size());
        return new PlayerBattleLogs(player, polBattles);
    }
}
