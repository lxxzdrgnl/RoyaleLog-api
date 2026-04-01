package com.rheon.royale.domain.card.dto;

import java.math.BigDecimal;

public record DeckStatsResponse(
        String     deckHash,
        String     baseDeckHash,    // 기본 8장 덱 해시 (진화/히어로 무시) — 프론트 그룹핑 기준
        String     battleType,
        int        winCount,
        int        useCount,
        BigDecimal winRate,         // 실제 승률 (표시용, 0.0~100.0 %)
        BigDecimal score,           // Bayesian score (정렬 기준)
        long[]     cardIds,         // api_id 배열 (숫자 오름차순 정렬)
        int[]      cardEvoLevels    // 카드별 진화 레벨 (cardIds와 동일 순서), null = 순수 기본덱
) {}
