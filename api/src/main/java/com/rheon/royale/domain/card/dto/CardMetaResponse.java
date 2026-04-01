package com.rheon.royale.domain.card.dto;

public record CardMetaResponse(
        long    id,          // api_id
        String  name,
        int     elixirCost,
        String  rarity,
        String  iconUrl,
        boolean isTower,
        String  cardType     // NORMAL | HERO | EVOLUTION
) {}
