package com.rheon.royale.domain.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "players_to_crawl")
public class PlayerToCrawl {

    @Id
    @Column(name = "player_tag", length = 20)
    private String playerTag;

    @Column(name = "name", length = 100)
    private String name;

    @Column(name = "current_rank")
    private Integer currentRank;

    @Column(name = "last_crawled_at")
    private LocalDateTime lastCrawledAt;

    @Column(name = "is_active", nullable = false)
    private boolean isActive;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    public PlayerToCrawl(String playerTag, String name, Integer currentRank,
                         LocalDateTime lastCrawledAt, boolean isActive,
                         LocalDateTime updatedAt) {
        this.playerTag = playerTag;
        this.name = name;
        this.currentRank = currentRank;
        this.lastCrawledAt = lastCrawledAt;
        this.isActive = isActive;
        this.updatedAt = updatedAt;
    }
}
