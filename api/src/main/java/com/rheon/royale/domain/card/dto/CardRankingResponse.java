package com.rheon.royale.domain.card.dto;

import java.math.BigDecimal;

public record CardRankingResponse(
        long       cardId,    // api_id
        int        winCount,
        int        useCount,
        BigDecimal winRate,
        BigDecimal score      // Bayesian score
) {}
