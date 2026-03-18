package com.rheon.royale.global.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.stream.Stream;

public final class BattleHashUtils {

    /** Clash Royale API battleTime 포맷: "20260101T000000.000Z" */
    private static final DateTimeFormatter BATTLE_TIME_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss.SSSX");

    private BattleHashUtils() {}

    /**
     * 배틀 고유 ID 생성
     * MD5( sort(playerTag, opponentTag) + battleTime )
     * 양 플레이어 어느 쪽 입장에서 저장해도 동일한 ID 보장 → 중복 방지
     */
    public static String battleId(String playerTag, String opponentTag, String battleTime) {
        String sortedTags = Stream.of(playerTag, opponentTag)
                .sorted()
                .reduce("", String::concat);
        return md5(sortedTags + battleTime);
    }

    /**
     * API battleTime 문자열 → LocalDateTime 변환
     * "20260101T000000.000Z" → LocalDateTime (UTC)
     */
    public static LocalDateTime parseBattleTime(String battleTime) {
        return ZonedDateTime.parse(battleTime, BATTLE_TIME_FMT).toLocalDateTime();
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
