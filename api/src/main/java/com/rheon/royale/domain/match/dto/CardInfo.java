package com.rheon.royale.domain.match.dto;

public record CardInfo(
        Long id,
        String name,
        String iconUrl,
        int level,
        int evolutionLevel,  // 0이면 일반 카드
        int elixirCost       // 사이클 계산용
) {}
