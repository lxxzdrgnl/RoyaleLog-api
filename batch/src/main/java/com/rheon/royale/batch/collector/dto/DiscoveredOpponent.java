package com.rheon.royale.batch.collector.dto;

/**
 * BFS로 발견된 상대방 정보.
 * 배틀 JSON의 opponent[0] 에서 추출하므로
 * 해당 배틀 시점의 트로피/리그가 담긴다.
 */
public record DiscoveredOpponent(
        String  name,
        Integer trophies,    // startingTrophies (ladder 배틀), PoL 유저는 null
        Integer leagueNumber // PoL 리그 레벨 (0~9), ladder 유저는 null
) {}
