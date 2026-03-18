package com.rheon.royale.infrastructure.external.clashroyale.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CrBadge(
        String name,
        Integer level,
        Integer maxLevel,
        Integer progress,
        Integer target,
        BadgeIconUrls iconUrls
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BadgeIconUrls(String large) {}
}
