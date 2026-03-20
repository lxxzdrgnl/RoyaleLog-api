package com.rheon.royale.domain.card.dto;

import java.math.BigDecimal;

public record DeckStatsResponse(
        String deckHash,
        String battleType,
        int winCount,
        int useCount,
        BigDecimal winRate   // 0.0 ~ 100.0 (%)
) {}
