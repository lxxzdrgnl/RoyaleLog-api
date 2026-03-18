package com.rheon.royale.infrastructure.external.clashroyale.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CrCardListResponse(
        List<CrCardItem> items,
        List<CrCardItem> supportItems
) {}
