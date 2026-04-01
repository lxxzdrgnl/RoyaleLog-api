package com.rheon.royale.global.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * battle_log_raw 저장 전 불필요 필드 제거.
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
public final class BattleJsonPruner {

    private BattleJsonPruner() {}

    private static final List<String> BATTLE_REMOVE = List.of(
            "deckSelection", "isHostedMatch", "isLadderTournament");

    private static final List<String> CARD_REMOVE = List.of(
            "name", "iconUrls", "rarity", "maxLevel", "maxEvolutionLevel", "starLevel");

    /** 배틀 JSON 경량화 후 문자열 반환. 원본 ObjectNode를 직접 변경(mutate)한다. */
    public static String prune(ObjectNode battle) {
        battle.remove(BATTLE_REMOVE);

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

    private static void pruneCards(JsonNode cardsNode) {
        for (JsonNode cardNode : cardsNode) {
            ObjectNode card = (ObjectNode) cardNode;
            CARD_REMOVE.forEach(card::remove);
        }
    }
}
