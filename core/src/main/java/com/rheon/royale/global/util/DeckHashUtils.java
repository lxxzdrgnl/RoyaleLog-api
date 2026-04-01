package com.rheon.royale.global.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 덱 해시 유틸리티
 *
 * 해시 2종 설계:
 *   - base_deck_hash  = MD5(sorted card_ids; 구분자)
 *     카드 구성만 (레벨/진화 무관) → deck_dictionary 키 / 유사덱 그룹핑 기준
 *   - refined_deck_hash = MD5(sorted "id:evoLevel"; 구분자)
 *     진화/히어로 포함 정밀 식별 → stats 집계 기준 / ML 입력
 *
 * 구분자 통일: ";" — 두 해시 모두 동일
 * 정렬 기준: 항상 숫자 오름차순 (Long::compareTo) — 문자열 정렬 사용 금지
 *   이유: "159000000" < "26000000" (문자열 기준) ≠ 159000000 > 26000000 (숫자 기준)
 *   타워 카드(~159000000)와 일반 카드(~26000000) 혼재 시 해시 불일치 버그 발생
 */
public final class DeckHashUtils {

    private DeckHashUtils() {}

    /**
     * Base 덱 해시 — 카드 ID만 (진화/레벨 무관)
     * MD5( 숫자 오름차순 정렬된 "id1;id2;...;idN" )
     *
     * @param deckCardIds  8장의 덱 카드 api_id 목록
     * @param towerCardId  타워 카드 api_id (supportCards[0], null 허용)
     */
    public static String deckHash(List<Long> deckCardIds, Long towerCardId) {
        List<Long> all = new ArrayList<>(deckCardIds);
        if (towerCardId != null) all.add(towerCardId);
        String key = all.stream()
                .sorted(Long::compareTo)          // 숫자 오름차순 (not lexicographic)
                .map(String::valueOf)
                .collect(Collectors.joining(";"));
        return md5(key);
    }

    /**
     * Refined 덱 해시 — 진화/히어로 인식 정밀 해시
     * MD5( 숫자 기준 정렬된 "id1:evo1;id2:evo2;...;towerid:0" )
     *
     * 왜 offset(+10_000_000) 방식이 아닌가:
     *   - 숫자 오버플로우 / 다른 카드 ID와 충돌 가능성
     *   - "id:evoLevel" 문자열 페어가 의미상 명확하고 충돌 불가
     *
     * 왜 문자열 정렬이 아닌 숫자 정렬인가:
     *   - "159000000:0" < "26000000:0" (문자열 기준) → 타워 카드 포함 시 정렬 역전 버그
     *   - ID 파트를 Long으로 파싱하여 숫자 기준으로 정렬
     *
     * @param refinedPairs  각 카드의 "id:evoLevel" 문자열 목록 (8장)
     * @param towerCardId   타워 카드 api_id (null 허용)
     */
    public static String refinedDeckHash(List<String> refinedPairs, Long towerCardId) {
        List<String> all = new ArrayList<>(refinedPairs);
        if (towerCardId != null) all.add(towerCardId + ":0");
        String key = all.stream()
                .sorted(Comparator.comparingLong(
                        (String s) -> Long.parseLong(s.split(":")[0])))  // 숫자 기준 정렬
                .collect(Collectors.joining(";"));
        return md5(key);
    }

    /**
     * Long[] 편의 메서드
     */
    public static Long[] sortedCardIds(List<Long> deckCardIds, Long towerCardId) {
        List<Long> all = new ArrayList<>(deckCardIds);
        if (towerCardId != null) all.add(towerCardId);
        return all.stream().sorted(Long::compareTo).toArray(Long[]::new);
    }

    /**
     * 카드 ID 정렬 순서(숫자 오름차순)와 동일한 순서로 정렬된 카드 레벨 배열
     * match_features.card_levels / opponent_card_levels 저장 시 사용
     *
     * @param deckCardIds  8장 카드 ID 목록
     * @param towerCardId  타워 카드 ID (null 허용, level=1 고정)
     * @param levelsList   각 덱 카드의 레벨 (deckCardIds와 동일 순서)
     */
    public static Short[] sortedCardLevels(List<Long> deckCardIds, Long towerCardId,
                                           List<Integer> levelsList) {
        int n = deckCardIds.size();
        List<long[]> pairs = new ArrayList<>(n + 1);
        for (int i = 0; i < n; i++) {
            pairs.add(new long[]{ deckCardIds.get(i), levelsList.get(i) });
        }
        if (towerCardId != null) pairs.add(new long[]{ towerCardId, 1L });
        return pairs.stream()
                .sorted(Comparator.comparingLong(a -> a[0]))
                .map(a -> (short) a[1])
                .toArray(Short[]::new);
    }

    /**
     * 카드 ID 정렬 순서(숫자 오름차순)와 동일한 순서로 정렬된 evo level 배열
     * refined_deck_dictionary.card_evo_levels 저장 시 사용
     *
     * @param deckCardIds   8장 카드 ID 목록
     * @param towerCardId   타워 카드 ID (null 허용, evo=0 고정)
     * @param evoLevelsList 각 덱 카드의 진화 레벨 (deckCardIds와 동일 순서)
     */
    public static Short[] sortedEvoLevels(List<Long> deckCardIds, Long towerCardId,
                                          List<Integer> evoLevelsList) {
        int n = deckCardIds.size();
        List<long[]> pairs = new ArrayList<>(n + 1);
        for (int i = 0; i < n; i++) {
            pairs.add(new long[]{ deckCardIds.get(i), evoLevelsList.get(i) });
        }
        if (towerCardId != null) pairs.add(new long[]{ towerCardId, 0L });
        return pairs.stream()
                .sorted(Comparator.comparingLong(a -> a[0]))
                .map(a -> (short) a[1])
                .toArray(Short[]::new);
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 not available", e);
        }
    }
}
