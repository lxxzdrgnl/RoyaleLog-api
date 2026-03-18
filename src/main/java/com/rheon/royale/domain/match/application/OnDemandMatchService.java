package com.rheon.royale.domain.match.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rheon.royale.batch.analyzer.DeckAnalyzerProcessor;
import com.rheon.royale.batch.analyzer.dto.AnalyzedBattle;
import com.rheon.royale.domain.entity.BattleLogRaw;
import com.rheon.royale.domain.entity.BattleLogRawId;
import com.rheon.royale.domain.match.dto.BattleEntry;
import com.rheon.royale.domain.match.dto.CardInfo;
import com.rheon.royale.domain.match.dto.ParticipantDetail;
import com.rheon.royale.global.util.BattleHashUtils;
import com.rheon.royale.infrastructure.external.clashroyale.ClashRoyaleClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;

/**
 * 일반 유저 온디맨드 전적 조회
 *
 * [설계 원칙]
 *   - battle_log_raw / match_features 에 쓰지 않는다.
 *     → 야간 Analyzer Job 이 랭커 데이터만 집계하도록 오염 방지
 *   - Clash API → 인메모리 분석 → Redis 캐시(5분) 만 사용
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OnDemandMatchService {

    private final ClashRoyaleClient clashRoyaleClient;
    private final DeckAnalyzerProcessor deckAnalyzerProcessor;
    private final ObjectMapper objectMapper;

    @Cacheable(value = "playerBattleLog", key = "'matches:' + #normalizedTag")
    public List<BattleEntry> fetchAndAnalyze(String normalizedTag) {
        log.info("[OnDemand] {} — DB 미수집, Clash API 실시간 분석", normalizedTag);

        String rawJson = clashRoyaleClient.getBattleLog(normalizedTag);
        List<BattleEntry> result = new ArrayList<>();

        try {
            JsonNode battles = objectMapper.readTree(rawJson);

            for (JsonNode battle : battles) {
                String battleTime = battle.path("battleTime").asText(null);
                if (battleTime == null) continue;

                JsonNode teamNode     = battle.path("team");
                JsonNode opponentNode = battle.path("opponent");
                if (teamNode.isEmpty() || opponentNode.isEmpty()) continue;

                JsonNode teamPlayer     = teamNode.get(0);
                JsonNode opponentPlayer = opponentNode.get(0);

                String playerTag   = teamPlayer.path("tag").asText();
                String opponentTag = opponentPlayer.path("tag").asText();
                String battleId    = BattleHashUtils.battleId(playerTag, opponentTag, battleTime);
                var    createdAt   = BattleHashUtils.parseBattleTime(battleTime);
                String battleType  = battle.path("type").asText(null);
                String gameMode    = battle.path("gameMode").path("name").asText(null);

                BattleLogRaw raw = BattleLogRaw.builder()
                        .id(new BattleLogRawId(battleId, createdAt))
                        .playerTag(normalizedTag)
                        .battleType(battleType)
                        .rawJson(battle.toString())
                        .build();

                AnalyzedBattle analyzed = deckAnalyzerProcessor.process(raw);

                ParticipantDetail team = toParticipant(
                        teamPlayer,
                        analyzed != null ? analyzed.deckHash() : null,
                        analyzed != null ? analyzed.avgLevel() : null,
                        analyzed != null ? analyzed.evolutionCount() : 0);

                ParticipantDetail opponent = toParticipant(
                        opponentPlayer,
                        analyzed != null ? analyzed.opponentHash() : null,
                        null, 0);

                result.add(new BattleEntry(battleId, battleType, gameMode, createdAt, team, opponent));
            }
        } catch (Exception e) {
            log.warn("[OnDemand] {} 분석 중 오류: {}", normalizedTag, e.getMessage());
        }

        log.info("[OnDemand] {} — {}건 분석 완료", normalizedTag, result.size());
        return result;
    }

    public ParticipantDetail toParticipant(JsonNode player, String deckHash,
                                            BigDecimal avgElixir, int evolutionCount) {
        String tag  = player.path("tag").asText(null);
        String name = player.path("name").asText(null);
        String clan = player.path("clan").path("name").asText(null);
        int    crowns             = player.path("crowns").asInt(0);
        int    startingTrophies   = player.path("startingTrophies").asInt(0);
        int    trophyChange       = player.path("trophyChange").asInt(0);
        int    kingTowerHp        = player.path("kingTowerHitPoints").asInt(0);
        double elixirLeaked       = player.path("elixirLeaked").asDouble(0);

        List<CardInfo> cards  = parseCards(player.path("cards"));
        CardInfo towerCard    = parseTowerCard(player.path("supportCards"));

        // avgElixir: DeckAnalyzerProcessor 결과 없을 때 카드 elixirCost로 직접 계산
        BigDecimal elixir = avgElixir;
        if (elixir == null && !cards.isEmpty()) {
            OptionalDouble avg = cards.stream()
                    .filter(c -> c.elixirCost() > 0)
                    .mapToInt(CardInfo::elixirCost)
                    .average();
            elixir = avg.isPresent()
                    ? BigDecimal.valueOf(avg.getAsDouble()).setScale(1, RoundingMode.HALF_UP)
                    : null;
        }

        // cycleElixir: 가장 엘릭서 비용이 낮은 4장 합계
        int cycleSum = cards.stream()
                .filter(c -> c.elixirCost() > 0)
                .sorted(Comparator.comparingInt(CardInfo::elixirCost))
                .limit(4)
                .mapToInt(CardInfo::elixirCost)
                .sum();
        BigDecimal cycle = cycleSum > 0
                ? BigDecimal.valueOf(cycleSum).setScale(1, RoundingMode.HALF_UP)
                : null;

        return new ParticipantDetail(
                tag, name, clan, crowns,
                startingTrophies, trophyChange,
                kingTowerHp, elixirLeaked,
                cards, towerCard,
                deckHash, elixir, cycle, evolutionCount);
    }

    private List<CardInfo> parseCards(JsonNode cardsNode) {
        List<CardInfo> cards = new ArrayList<>();
        for (JsonNode card : cardsNode) {
            cards.add(new CardInfo(
                    card.path("id").asLong(),
                    card.path("name").asText(null),
                    card.path("iconUrls").path("medium").asText(null),
                    card.path("level").asInt(0),
                    card.path("evolutionLevel").asInt(0),
                    card.path("elixirCost").asInt(0)
            ));
        }
        return cards;
    }

    private CardInfo parseTowerCard(JsonNode supportCards) {
        if (supportCards.isEmpty()) return null;
        JsonNode card = supportCards.get(0);
        return new CardInfo(
                card.path("id").asLong(),
                card.path("name").asText(null),
                card.path("iconUrls").path("medium").asText(null),
                card.path("level").asInt(0),
                0, 0
        );
    }
}
