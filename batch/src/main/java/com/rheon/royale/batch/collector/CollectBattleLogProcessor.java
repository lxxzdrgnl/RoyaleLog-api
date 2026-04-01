package com.rheon.royale.batch.collector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rheon.royale.batch.collector.dto.DiscoveredOpponent;
import com.rheon.royale.batch.collector.dto.PlayerBattleLogs;
import com.rheon.royale.domain.entity.BattleLogRaw;
import com.rheon.royale.domain.entity.BattleLogRawId;
import com.rheon.royale.domain.entity.PlayerToCrawl;
import com.rheon.royale.domain.repository.PlayerToCrawlRepository;
import com.rheon.royale.global.error.BusinessException;
import com.rheon.royale.global.util.BattleHashUtils;
import com.rheon.royale.global.util.BattleJsonPruner;
import com.rheon.royale.global.util.JsonNodeUtils;
import com.rheon.royale.infrastructure.external.clashroyale.ClashRoyaleClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
@Transactional
public class CollectBattleLogProcessor implements ItemProcessor<PlayerToCrawl, PlayerBattleLogs> {

    private static final int RETENTION_DAYS = 8; // 8일 이상 된 배틀은 저장하지 않음
    private static final int PRUNING_DAYS = 8;   // 8일 이내 전적 없으면 잠수 → 비활성화

    private final ClashRoyaleClient clashRoyaleClient;
    private final ObjectMapper objectMapper;
    private final PlayerToCrawlRepository playerToCrawlRepository;
    private final BracketBattleCounter bracketBattleCounter;

    @Override
    public PlayerBattleLogs process(PlayerToCrawl player) throws Exception {
        String rawJson;
        try {
            long t0 = System.currentTimeMillis();
            rawJson = clashRoyaleClient.getBattleLog(player.getPlayerTag());
            log.debug("[Processor] {} API latency={}ms", player.getPlayerTag(), System.currentTimeMillis() - t0);
        } catch (BusinessException e) {
            // 404, 400: 존재하지 않는 유저 → skip (null 반환)
            // 429/5xx: ClashRoyaleClient retrySpec이 3회 재시도 후 던짐 → skip
            log.warn("[Processor] {}: API 호출 실패 ({}) → skip", player.getPlayerTag(), e.getErrorCode());
            return null;
        } catch (Exception e) {
            log.warn("[Processor] {}: 예상치 못한 오류 → skip. {}", player.getPlayerTag(), e.getMessage());
            return null;
        }
        JsonNode battles = objectMapper.readTree(rawJson);

        List<BattleLogRaw> polBattles = new ArrayList<>();
        Map<String, DiscoveredOpponent> discoveredOpponents = new HashMap<>();

        // 플레이어 본인의 최신 트로피/리그
        // CR API는 최신 배틀을 먼저 반환하므로 첫 번째 유효값 = 현재 상태에 가장 가까운 값
        Integer currentTrophies = null;
        Integer currentLeague   = null;
        // 30일 이내 배틀이 존재하는지 (브라켓 cap과 진짜 잠수를 구분하기 위함)
        boolean hasRecentBattles = false;

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

            JsonNode teamPlayer     = team.get(0);
            JsonNode opponentPlayer = opponent.get(0);

            // 플레이어 본인 최신 트로피/리그 추출 (첫 유효값만 사용)
            // startingTrophies: team[0] 하위, leagueNumber: 배틀 루트 레벨
            if (currentTrophies == null) currentTrophies = JsonNodeUtils.getIntOrNull(teamPlayer, "startingTrophies");
            if (currentLeague == null)   currentLeague   = JsonNodeUtils.getIntOrNull(battle, "leagueNumber");

            String playerTag   = teamPlayer.path("tag").asText();
            String opponentTag = opponentPlayer.path("tag").asText();

            // 상대방 발견 → BFS 풀 확장용 누적 (tag → DiscoveredOpponent, 중복 자동 제거)
            if (opponentTag != null && !opponentTag.isBlank()) {
                String  opponentName   = opponentPlayer.path("name").asText(null);
                Integer opponentTrophy = JsonNodeUtils.getIntOrNull(opponentPlayer, "startingTrophies");
                // leagueNumber는 배틀 루트 레벨 — 양쪽 플레이어 동일
                Integer opponentLeague = JsonNodeUtils.getIntOrNull(battle, "leagueNumber");

                discoveredOpponents.put(opponentTag,
                        new DiscoveredOpponent(opponentName, opponentTrophy, opponentLeague));
            }

            String battleId = BattleHashUtils.battleId(playerTag, opponentTag, battleTime);
            var createdAt = BattleHashUtils.parseBattleTime(battleTime);

            // 30일 이상 된 배틀(잠수 유저 등) → 파티션 없음 + 의미 없는 데이터이므로 skip
            if (createdAt.isBefore(LocalDateTime.now().minusDays(RETENTION_DAYS))) {
                log.debug("[Processor] {}: 오래된 배틀 skip ({})", player.getPlayerTag(), battleTime);
                continue;
            }
            hasRecentBattles = true;  // 30일 이내 배틀 존재 확인

            String battleType = battle.path("type").asText("unknown");

            // 브라켓별 수집 한도 초과 시 skip (오늘 배치 런 기준)
            Integer trophiesForBracket = JsonNodeUtils.getIntOrNull(teamPlayer, "startingTrophies");
            if (trophiesForBracket == null) trophiesForBracket = currentTrophies;
            Integer leagueForBracket = JsonNodeUtils.getIntOrNull(battle, "leagueNumber");

            String bracket = BracketBattleCounter.toBracket(battleType, leagueForBracket, trophiesForBracket);
            if (!bracketBattleCounter.tryIncrement(bracket)) {
                log.debug("[Processor] {}: 브라켓 {} 한도 초과 → skip", player.getPlayerTag(), bracket);
                continue;
            }

            BattleLogRaw row = BattleLogRaw.builder()
                    .id(new BattleLogRawId(battleId, createdAt))
                    .playerTag(player.getPlayerTag())
                    .battleType(battleType)
                    .rawJson(BattleJsonPruner.prune((ObjectNode) battle.deepCopy()))
                    .build();

            polBattles.add(row);
        }

        String playerBracket = BracketBattleCounter.toBracket(
                currentLeague != null ? "pathOfLegend" : "PvP",
                currentLeague,
                currentTrophies);

        if (polBattles.isEmpty()) {
            if (!hasRecentBattles) {
                // 8일 이내 배틀 자체가 없음 → 진짜 잠수 유저 → 비활성화
                playerToCrawlRepository.deactivate(player.getPlayerTag());
                log.debug("[Processor] {}: 8일 이내 전적 없음 → is_active=false (pruned)", player.getPlayerTag());
            } else {
                // 배틀은 있지만 브라켓 한도 초과로 전부 skip → 활성 유저, 비활성화 금지
                // last_crawled_at 갱신으로 다음 배치에서 후순위 처리
                playerToCrawlRepository.updateAfterCrawl(player.getPlayerTag(), currentTrophies, currentLeague, playerBracket);
                log.debug("[Processor] {}: 브라켓 한도 초과로 전부 skip → last_crawled_at 갱신만", player.getPlayerTag());
            }
            return null;
        }

        log.debug("[Processor] {}: 배틀 {}건, 상대방 {}명 발견, trophies={}, league={}, bracket={}",
                player.getPlayerTag(), polBattles.size(), discoveredOpponents.size(),
                currentTrophies, currentLeague, playerBracket);
        return new PlayerBattleLogs(player, polBattles, discoveredOpponents, currentTrophies, currentLeague, playerBracket);
    }

}
