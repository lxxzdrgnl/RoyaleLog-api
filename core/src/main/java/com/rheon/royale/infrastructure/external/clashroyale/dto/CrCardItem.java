package com.rheon.royale.infrastructure.external.clashroyale.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CrCardItem(
        Long id,
        String name,
        Integer maxLevel,
        Integer maxEvolutionLevel,
        Integer elixirCost,
        CrIconUrls iconUrls,
        String rarity
) {}
