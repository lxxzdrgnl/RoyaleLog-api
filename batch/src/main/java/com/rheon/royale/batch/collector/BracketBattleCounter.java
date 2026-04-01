package com.rheon.royale.batch.collector;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CollectBattleLogStep 에서 브라켓별 수집 건수를 제한하는 카운터.
 *
 * - 배치 런마다 beforeStep() 에서 초기화 (이전 런과 독립)
 * - AsyncItemProcessor 50 스레드 환경 → ConcurrentHashMap + AtomicInteger
 * - tryIncrement(): 한도 미만이면 +1 후 true, 초과면 false (skip 신호)
 */
@Slf4j
@Component
public class BracketBattleCounter implements StepExecutionListener {

    @Value("${collector.bracket.max-trophy-low:50000}")
    private int maxTrophyLow;   // arena_01 ~ arena_10

    @Value("${collector.bracket.max-trophy:100000}")
    private int maxTrophy;      // arena_11 ~ arena_28

    @Value("${collector.bracket.max-pol:100000}")
    private int maxPol;

    @Value("${collector.bracket.max-special:300000}")
    private int maxSpecial;

    private final ConcurrentHashMap<String, AtomicInteger> counts = new ConcurrentHashMap<>();

    @Override
    public void beforeStep(StepExecution stepExecution) {
        counts.clear();
        log.info("[BracketCounter] 초기화 — trophy-low {}건, trophy {}건, PoL {}건, special {}건",
                maxTrophyLow, maxTrophy, maxPol, maxSpecial);
    }

    private int limitOf(String bracket) {
        if (bracket.startsWith("pol_"))    return maxPol;
        if ("special".equals(bracket))    return maxSpecial;
        if ("trophy_unknown".equals(bracket)) return 0;
        // arena_01 ~ arena_10: 5만, arena_11 ~ arena_28: 10만
        try {
            int arenaNum = Integer.parseInt(bracket.substring(6)); // "arena_01" → 1
            return arenaNum <= 10 ? maxTrophyLow : maxTrophy;
        } catch (NumberFormatException e) {
            return maxTrophy;
        }
    }

    /**
     * 플레이어 단위 사전 차단: API 호출 전에 브라켓이 이미 가득 찼는지 확인.
     * trophy_unknown은 항상 skip 대상이므로 true 반환.
     *
     * @return true  → 이 브라켓은 이미 한도 도달, 해당 플레이어 API 호출 불필요
     *         false → 아직 수집 여지 있음
     */
    public boolean isBracketFull(String bracket) {
        if ("trophy_unknown".equals(bracket)) return true;
        int limit = limitOf(bracket);
        AtomicInteger counter = counts.get(bracket);
        return counter != null && counter.get() >= limit;
    }

    /**
     * early-stop 판단: knownBrackets 전부 한도에 도달했는지 확인.
     * 50 스레드 환경에서 매 read()마다 호출 — ConcurrentHashMap 조회만 하므로 ~수백 ns.
     */
    public boolean isAllBracketsFull(Set<String> knownBrackets) {
        return !knownBrackets.isEmpty() && knownBrackets.stream().allMatch(this::isBracketFull);
    }

    /**
     * @return true  → 이 배틀은 수집 가능 (카운터 +1 완료)
     *         false → 브라켓 한도 초과, skip
     */
    public boolean tryIncrement(String bracket) {
        int limit = limitOf(bracket);
        AtomicInteger counter = counts.computeIfAbsent(bracket, k -> new AtomicInteger(0));

        // CAS 루프: 한도 미만일 때만 +1
        int current;
        do {
            current = counter.get();
            if (current >= limit) return false;
        } while (!counter.compareAndSet(current, current + 1));

        return true;
    }

    // 트로피 기반이지만 일반 ladder(PvP)와 분리해서 집계할 경쟁 모드
    private static final java.util.Set<String> SPECIAL_MODES = java.util.Set.of(
            "trail", "riverRacePvP", "riverRaceDuel", "riverRaceDuelColosseum",
            "boatBattle", "tournament", "PvE"
    );

    // 분석 의미 없는 비경쟁 모드 → trophy_unknown으로 처리 (cap 소진 방지)
    private static final java.util.Set<String> NON_COMPETITIVE = java.util.Set.of(
            "friendly", "clanMate", "unknown"
    );

    /** 브라켓 분류: pathOfLegend → pol_N, 특수경쟁 → special, PvP → arena_NN, 비경쟁 → trophy_unknown */
    public static String toBracket(String battleType, Integer leagueNumber, Integer startingTrophies) {
        if ("pathOfLegend".equals(battleType)) {
            return "pol_" + (leagueNumber != null ? leagueNumber : "unknown");
        }
        if (NON_COMPETITIVE.contains(battleType)) {
            return "trophy_unknown";
        }
        if (SPECIAL_MODES.contains(battleType)) {
            return "special";
        }
        if (startingTrophies == null || startingTrophies <= 0) return "trophy_unknown";
        if (startingTrophies <   300) return "arena_01";
        if (startingTrophies <   600) return "arena_02";
        if (startingTrophies <  1000) return "arena_03";
        if (startingTrophies <  1300) return "arena_04";
        if (startingTrophies <  1600) return "arena_05";
        if (startingTrophies <  2000) return "arena_06";
        if (startingTrophies <  2300) return "arena_07";
        if (startingTrophies <  2600) return "arena_08";
        if (startingTrophies <  3000) return "arena_09";
        if (startingTrophies <  3400) return "arena_10";
        if (startingTrophies <  3800) return "arena_11";
        if (startingTrophies <  4200) return "arena_12";
        if (startingTrophies <  4600) return "arena_13";
        if (startingTrophies <  5000) return "arena_14";
        if (startingTrophies <  5500) return "arena_15";
        if (startingTrophies <  6000) return "arena_16";
        if (startingTrophies <  6500) return "arena_17";
        if (startingTrophies <  7000) return "arena_18";
        if (startingTrophies <  7500) return "arena_19";
        if (startingTrophies <  8000) return "arena_20";
        if (startingTrophies <  8500) return "arena_21";
        if (startingTrophies <  9000) return "arena_22";
        if (startingTrophies <  9500) return "arena_23";
        if (startingTrophies < 10000) return "arena_24";
        if (startingTrophies < 10500) return "arena_25";
        if (startingTrophies < 11000) return "arena_26";
        if (startingTrophies < 11500) return "arena_27";
        return "arena_28";
    }
}
