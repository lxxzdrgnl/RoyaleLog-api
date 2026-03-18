package com.rheon.royale.batch.analyzer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rheon.royale.batch.analyzer.dto.AnalyzedBattle;
import com.rheon.royale.domain.entity.BattleLogRaw;
import com.rheon.royale.global.util.DeckHashUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeckAnalyzerProcessor implements ItemProcessor<BattleLogRaw, AnalyzedBattle> {

    private final ObjectMapper objectMapper;

    @Override
    public AnalyzedBattle process(BattleLogRaw battle) throws Exception {
        JsonNode root = objectMapper.readTree(battle.getRawJson());

        JsonNode team = root.path("team");
        JsonNode opponent = root.path("opponent");

        if (team.isEmpty() || opponent.isEmpty()) {
            log.warn("[Analyzer] team/opponent 없음: {}", battle.getId().getBattleId());
            return null;
        }

        DeckInfo teamInfo = parseDeck(team.get(0));
        DeckInfo opponentInfo = parseDeck(opponent.get(0));

        if (teamInfo == null || opponentInfo == null) {
            log.warn("[Analyzer] 덱 파싱 실패: {}", battle.getId().getBattleId());
            return null;
        }

        int teamCrowns = team.get(0).path("crowns").asInt(0);
        int opponentCrowns = opponent.get(0).path("crowns").asInt(0);
        int result = teamCrowns > opponentCrowns ? 1 : 0;

        // createdAt = BattleHashUtils.parseBattleTime(battleTime) → UTC LocalDateTime
        // .toLocalDate() = UTC 날짜 → battle_date 파티션 키와 동일 기준 보장
        // ⚠ 이 값이 달라지면 같은 battle_id가 다른 파티션에 중복 삽입됨 (dedup 실패)
        return new AnalyzedBattle(
                battle.getId().getBattleId(),
                battle.getId().getCreatedAt(),
                battle.getId().getCreatedAt().toLocalDate(),
                battle.getBattleType(),
                teamInfo.deckHash(),
                teamInfo.cardIds(),
                opponentInfo.deckHash(),
                opponentInfo.cardIds(),
                result,
                teamInfo.avgLevel(),
                teamInfo.evolutionCount()
        );
    }

    private DeckInfo parseDeck(JsonNode player) {
        JsonNode cardsNode = player.path("cards");
        if (cardsNode.isEmpty()) return null;

        List<Long> deckCardIds = new ArrayList<>();
        int totalLevel = 0;
        int evolutionCount = 0;

        for (JsonNode card : cardsNode) {
            deckCardIds.add(card.path("id").asLong());
            totalLevel += card.path("level").asInt(1);
            if (card.path("evolutionLevel").asInt(0) > 0) evolutionCount++;
        }

        if (deckCardIds.size() < 8) return null;

        JsonNode supportCards = player.path("supportCards");
        Long towerCardId = supportCards.isEmpty() ? null : supportCards.get(0).path("id").asLong();

        String deckHash = DeckHashUtils.deckHash(deckCardIds, towerCardId);
        Long[] sortedCardIds = DeckHashUtils.sortedCardIds(deckCardIds, towerCardId);
        BigDecimal avgLevel = BigDecimal.valueOf(totalLevel)
                .divide(BigDecimal.valueOf(deckCardIds.size()), 2, RoundingMode.HALF_UP);

        return new DeckInfo(deckHash, sortedCardIds, avgLevel, evolutionCount);
    }

    private record DeckInfo(String deckHash, Long[] cardIds, BigDecimal avgLevel, int evolutionCount) {}
}
