package com.rheon.royale.infrastructure.external.clashroyale.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CrRankingPlayer(
        String tag,
        String name,
        Integer rank,
        Integer expLevel,
        Integer eloRating,
        Integer leagueNumber  // PoL 리그 레벨 (0=브론즈 ~ 9=챔피언십)
) {}
