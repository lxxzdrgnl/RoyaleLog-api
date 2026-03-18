package com.rheon.royale.infrastructure.external.clashroyale.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CrRankingPlayer(
        String tag,
        String name,
        Integer rank,
        Integer expLevel,
        Integer eloRating
) {}
