package com.rheon.royale.infrastructure.external.clashroyale.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CrSeasonListResponse(
        List<CrSeason> items
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CrSeason(String id) {}

    /**
     * 마지막 아이템이 현재 활성 시즌
     */
    public String currentSeasonId() {
        if (items == null || items.isEmpty()) {
            throw new IllegalStateException("Season list is empty");
        }
        return items.getLast().id();
    }
}
