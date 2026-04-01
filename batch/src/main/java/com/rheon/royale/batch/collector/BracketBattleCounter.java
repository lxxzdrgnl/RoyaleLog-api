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

    @Override
    public org.springframework.batch.core.ExitStatus afterStep(StepExecution stepExecution) {
        // 브라켓별 최종 카운터를 ExecutionContext에 저장 → Spring Batch가 DB에 영속화
        // History API에서 꺼내서 "아레나별 수집 건수" 표시 가능
        var ctx = stepExecution.getExecutionContext();
        counts.forEach((bracket, counter) -> ctx.putInt("bracket." + bracket, counter.get()));

        int total = counts.values().stream().mapToInt(AtomicInteger::get).sum();
        log.info("[BracketCounter] 최종 — 총 {}건, 브라켓 {}개", total, counts.size());
        return null; // null = 기본 ExitStatus 유지
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

    /** 브라켓별 현재 카운트 + 한도 스냅샷 (모니터링 API용) */
    public java.util.Map<String, int[]> snapshot() {
        java.util.Map<String, int[]> result = new java.util.TreeMap<>();
        counts.forEach((bracket, counter) ->
                result.put(bracket, new int[]{ counter.get(), limitOf(bracket) }));
        return result;
    }

    /** BracketClassifier 위임 — 분류 로직 단일 소스 유지 */
    public static String toBracket(String battleType, Integer leagueNumber, Integer startingTrophies) {
        return com.rheon.royale.global.util.BracketClassifier.toBracket(battleType, leagueNumber, startingTrophies);
    }
}
