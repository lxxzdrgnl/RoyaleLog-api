package com.rheon.royale.global.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * 덱 해시 유틸리티
 *
 * 핵심 원칙: "deck identity" vs "deck state" 분리
 *   - deck_hash: card_id 기반 (level/evolution 미포함) → 같은 덱 구성이면 항상 동일한 해시
 *   - deck state (has_evolution, avg_level 등): 별도 컬럼에 저장
 *
 * 이유: level 포함 시 같은 덱이 플레이어마다 다른 hash → 통계 분산, 의미 없는 데이터
 */
public final class DeckHashUtils {

    private DeckHashUtils() {}

    /**
     * 덱 해시 생성
     * MD5( 정렬된(8 deck card_ids + 1 tower card_id) )
     *
     * @param deckCardIds  8장의 덱 카드 api_id 목록
     * @param towerCardId  타워 카드 api_id (supportCards[0])
     */
    /**
     * 구분자: "-" (숫자 ID 사이 "-" 사용 → 공백/언더스코어보다 가독성 높고 ambiguity 없음)
     * 정렬: Long.compareTo() — 숫자 오름차순 (lexicographic 정렬과 다름, 예: 9 < 11)
     * 결과 예: "26000000-26000001-26000048-..."
     */
    public static String deckHash(List<Long> deckCardIds, Long towerCardId) {
        List<Long> all = new ArrayList<>(deckCardIds);
        if (towerCardId != null) {
            all.add(towerCardId);
        }
        // Long::compareTo → 숫자 오름차순 (not lexicographic)
        String key = all.stream()
                .sorted(Long::compareTo)
                .map(String::valueOf)
                .reduce("", (a, b) -> a.isEmpty() ? b : a + "-" + b);
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
