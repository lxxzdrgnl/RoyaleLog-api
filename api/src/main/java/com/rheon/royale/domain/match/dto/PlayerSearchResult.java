package com.rheon.royale.domain.match.dto;

/**
 * 닉네임 검색 결과 — players_to_crawl 기반
 *
 * @param currentRank PoL 상위 랭킹 플레이어는 순위 포함, 상대방 발견으로 추가된 유저는 null
 */
public record PlayerSearchResult(
        String playerTag,
        String name,
        Integer currentRank
) {}
