package com.rheon.royale.infrastructure.external.clashroyale.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CrPlayerProfile(
        String tag,
        String name,
        Integer expLevel,
        Integer trophies,
        Integer bestTrophies,
        Integer wins,
        Integer losses,
        Integer battleCount,
        Integer threeCrownWins,
        Integer starPoints,
        Integer warDayWins,
        Integer clanCardsCollected,
        Integer totalDonations,
        Integer challengeMaxWins,
        Integer challengeCardsWon,
        Integer tournamentCardsWon,
        Integer tournamentBattleCount,
        Arena arena,
        Clan clan,
        LeagueStatistics leagueStatistics,
        Integer legacyTrophyRoadHighScore,
        List<CrPlayerCard> cards,
        List<CrPlayerCard> currentDeck,
        List<CrPlayerCard> supportCards,
        CrPlayerCard currentFavouriteCard,
        List<CrBadge> badges
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Arena(Integer id, String name) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Clan(String tag, String name, Integer badgeId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LeagueStatistics(
            SeasonResult currentSeason,
            SeasonResult previousSeason,
            SeasonResult bestSeason
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SeasonResult(Integer trophies, Integer bestTrophies, Integer rank) {}
}
