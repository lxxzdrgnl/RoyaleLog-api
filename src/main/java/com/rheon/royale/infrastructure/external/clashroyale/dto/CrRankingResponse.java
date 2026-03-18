package com.rheon.royale.infrastructure.external.clashroyale.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CrRankingResponse(
        List<CrRankingPlayer> items,
        CrPaging paging
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CrPaging(CrCursors cursors) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CrCursors(String after) {}

    public boolean hasNextPage() {
        return paging != null
                && paging.cursors() != null
                && paging.cursors().after() != null;
    }

    public String nextCursor() {
        return paging.cursors().after();
    }
}
