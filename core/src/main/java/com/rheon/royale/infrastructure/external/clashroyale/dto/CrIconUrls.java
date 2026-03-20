package com.rheon.royale.infrastructure.external.clashroyale.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CrIconUrls(
        String medium,
        String heroMedium,
        String evolutionMedium
) {}
