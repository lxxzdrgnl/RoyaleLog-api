package com.rheon.royale.global.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
     * API battleTime 문자열 → LocalDateTime 변환 (UTC 기준)
     * "20260101T000000.000Z" → LocalDateTime (UTC, JVM timezone 무관)
     *
     * ⚠ .toLocalDateTime() 금지: ZonedDateTime timezone 정보 버림 → JVM timezone 오염 가능
     *   withZoneSameInstant(UTC).toLocalDateTime() 으로 UTC 명시 보장
     */
    public static LocalDateTime parseBattleTime(String battleTime) {
        return ZonedDateTime.parse(battleTime, BATTLE_TIME_FMT)
                .withZoneSameInstant(ZoneOffset.UTC)
                .toLocalDateTime();
    }

    /**
     * battle_date 추출 — UTC 고정
     *
     * battle_id = MD5(tags + battleTime) → battle_date는 battleTime에서 결정론적으로 파생
     * 앱/배치 전체에서 이 메서드만 사용 → timezone mismatch로 인한 partition 오배치 방지
     * (같은 battle_id가 다른 battle_date partition으로 들어가면 PK dedup 실패)
     */
    public static LocalDate battleDate(String battleTime) {
        return ZonedDateTime.parse(battleTime, BATTLE_TIME_FMT)
                .withZoneSameInstant(ZoneOffset.UTC)
                .toLocalDate();
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
