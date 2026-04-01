package com.rheon.royale.global.util;

import java.util.Set;

/**
 * 배틀/플레이어를 브라켓으로 분류하는 유틸리티.
 * :core 모듈에 배치해 :api, :batch 모두 사용 가능.
 */
public final class BracketClassifier {

    private BracketClassifier() {}

    // 트로피 기반이지만 일반 ladder(PvP)와 분리해서 집계할 경쟁 모드
    private static final Set<String> SPECIAL_MODES = Set.of(
            "trail", "riverRacePvP", "riverRaceDuel", "riverRaceDuelColosseum",
            "boatBattle", "tournament", "PvE"
    );

    // 분석 의미 없는 비경쟁 모드 → trophy_unknown으로 처리 (cap 소진 방지)
    private static final Set<String> NON_COMPETITIVE = Set.of(
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
