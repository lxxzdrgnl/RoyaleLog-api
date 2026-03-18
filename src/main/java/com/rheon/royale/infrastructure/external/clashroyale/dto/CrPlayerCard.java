package com.rheon.royale.infrastructure.external.clashroyale.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CrPlayerCard(
        Long id,
        String name,
        Integer level,
        Integer starLevel,
        Integer evolutionLevel,
        Integer maxLevel,
        Integer maxEvolutionLevel,
        Integer count,
        String rarity,
        Integer elixirCost,
        CrIconUrls iconUrls
) {}
