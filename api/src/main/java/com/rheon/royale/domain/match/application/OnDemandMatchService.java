package com.rheon.royale.domain.match.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rheon.royale.batch.analyzer.DeckAnalyzerProcessor;
import com.rheon.royale.batch.analyzer.AnalyzerPersistenceService;
import com.rheon.royale.batch.analyzer.dto.AnalyzedBattle;
import com.rheon.royale.domain.card.application.CardMetaCache;
import com.rheon.royale.domain.entity.BattleLogRaw;
import com.rheon.royale.domain.entity.BattleLogRawId;
import com.rheon.royale.domain.match.dto.BattleEntry;
import com.rheon.royale.domain.match.dto.CardInfo;
import com.rheon.royale.domain.match.dto.ParticipantDetail;
import com.rheon.royale.global.util.BattleHashUtils;
import com.rheon.royale.global.util.BattleJsonPruner;
import com.rheon.royale.global.util.BracketClassifier;
import com.rheon.royale.global.util.JsonNodeUtils;
import com.rheon.royale.infrastructure.external.clashroyale.ClashRoyaleClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;

/**
 * 온디맨드 전적 조회 — DB 미수집 유저 또는 일반 유저 검색 시
 *
 * [설계 원칙]
 *   - Clash API → 인메모리 분석 → battle_log_raw 저장 → Redis 캐시(5분)
 *   - battle_log_raw에 저장함으로써 해당 유저를 다음 배치 수집 대상으로 자동 편입
 *   - players_to_crawl UPSERT: 검색된 유저를 BFS 풀에 즉시 추가
 *
 * [이전 설계와의 차이]
 *   - 이전: "랭커 데이터 오염 방지"를 위해 저장 안 함 (PoL 1000명 고정 시대)
 *   - 현재: BFS 확장 전략으로 수집 대상이 전체 유저로 확대됨 → 저장이 맞음
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OnDemandMatchService {

    private static final int RETENTION_DAYS = 8;

    private static final String INSERT_BATTLE_SQL = com.rheon.royale.global.util.BatchSqlConstants.INSERT_BATTLE_SQL;
    private static final String UPSERT_PLAYER_SQL = com.rheon.royale.global.util.BatchSqlConstants.UPSERT_PLAYER_SQL;

    private final ClashRoyaleClient clashRoyaleClient;
    private final DeckAnalyzerProcessor deckAnalyzerProcessor;
    private final AnalyzerPersistenceService analyzerPersistenceService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final CardMetaCache cardMetaCache;

    @Cacheable(value = "playerBattleLog", key = "'matches:' + #normalizedTag")
    public List<BattleEntry> fetchAndAnalyze(String normalizedTag) {
        log.info("[OnDemand] {} — Clash API 실시간 조회 시작", normalizedTag);

        String rawJson = clashRoyaleClient.getBattleLog(normalizedTag);
        List<BattleEntry> result = new ArrayList<>();
        List<Object[]> battleArgs = new ArrayList<>();
        List<AnalyzedBattle> analyzedList = new ArrayList<>();
        String playerName = null;
        Integer currentTrophies = null;
        Integer currentLeague   = null;

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

                // 첫 배틀에서 이름 + 트로피/리그 추출 (CR API newest-first)
                if (playerName == null) {
                    playerName = teamPlayer.path("name").asText(null);
                }
                if (currentTrophies == null) currentTrophies = JsonNodeUtils.getIntOrNull(teamPlayer, "startingTrophies");
                if (currentLeague == null)   currentLeague   = JsonNodeUtils.getIntOrNull(battle, "leagueNumber");

                // 30일 이상 된 배틀은 파티션 없음 → 저장 및 분석 skip
                if (createdAt.isBefore(LocalDateTime.now().minusDays(RETENTION_DAYS))) {
                    continue;
                }

                // battle_log_raw 저장 대상 누적 — batch와 동일하게 pruned JSON 저장
                String prunedJson = BattleJsonPruner.prune((ObjectNode) battle.deepCopy());
                battleArgs.add(new Object[]{
                        battleId, normalizedTag, battleType, prunedJson,
                        Timestamp.valueOf(createdAt)
                });

                BattleLogRaw raw = BattleLogRaw.builder()
                        .id(new BattleLogRawId(battleId, createdAt))
                        .playerTag(normalizedTag)
                        .battleType(battleType)
                        .rawJson(battle.toString())
                        .build();

                AnalyzedBattle analyzed = deckAnalyzerProcessor.process(raw);

                if (analyzed != null) {
                    analyzedList.add(analyzed);
                }

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

        // battle_log_raw 저장 + players_to_crawl 등록 (다음 배치부터 자동 수집 대상)
        // ON CONFLICT DO NOTHING → 중복 배틀 안전하게 무시
        if (!battleArgs.isEmpty()) {
            jdbcTemplate.batchUpdate(INSERT_BATTLE_SQL, battleArgs, battleArgs.size(),
                    (ps, args) -> {
                        ps.setString(1, (String) args[0]);
                        ps.setString(2, (String) args[1]);
                        ps.setString(3, (String) args[2]);
                        ps.setString(4, (String) args[3]);
                        ps.setTimestamp(5, (Timestamp) args[4]);
                    });

            String bracket = BracketClassifier.toBracket(
                    currentLeague != null ? "pathOfLegend" : "PvP",
                    currentLeague, currentTrophies);
            jdbcTemplate.update(UPSERT_PLAYER_SQL, normalizedTag, playerName, currentTrophies, currentLeague, bracket);
            log.info("[OnDemand] {} ({}) — {}건 저장, players_to_crawl 편입",
                    normalizedTag, playerName, battleArgs.size());
        }

        // deck_dictionary + match_features 즉시 저장 (중복 시 ON CONFLICT DO NOTHING / updated_at 조건 무시)
        // → 배치 미실행 상태에서도 stats 집계에 반영 가능
        if (!analyzedList.isEmpty()) {
            analyzerPersistenceService.persistOnDemand(analyzedList);
        }

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
            long id = card.path("id").asLong();
            String name = card.path("name").asText(null);

            // batch pruned JSON에서 name이 없으면 cards 테이블에서 보완 (iconUrl은 프론트 내부 에셋 처리)
            if (name == null) {
                CardMetaCache.CardMeta meta = cardMetaCache.get(id);
                if (meta != null) name = meta.name();
            }

            cards.add(new CardInfo(id, name, null,
                    card.path("level").asInt(0),
                    card.path("evolutionLevel").asInt(0),
                    card.path("elixirCost").asInt(0)));
        }
        return cards;
    }

    private CardInfo parseTowerCard(JsonNode supportCards) {
        if (supportCards.isEmpty()) return null;
        JsonNode card = supportCards.get(0);
        long id = card.path("id").asLong();
        String name = card.path("name").asText(null);

        if (name == null) {
            CardMetaCache.CardMeta meta = cardMetaCache.get(id);
            if (meta != null) name = meta.name();
        }

        return new CardInfo(id, name, null, card.path("level").asInt(0), 0, 0);
    }

}
