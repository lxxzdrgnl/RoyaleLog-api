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

    /**
     * 수집 우선순위
     *   1 = P1 Active  (7일 이내 활동, 3시간 주기 수집)
     *   2 = P2 Normal  (8~30일 활동, 일 1회 수집) — 기본값
     *   3 = P3 Dormant (30일+ 미활동, is_active=false 직전 단계)
     */
    @Column(name = "priority", nullable = false)
    private int priority = 2;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    public PlayerToCrawl(String playerTag, String name, Integer currentRank,
                         LocalDateTime lastCrawledAt, boolean isActive, int priority,
                         LocalDateTime updatedAt) {
        this.playerTag = playerTag;
        this.name = name;
        this.currentRank = currentRank;
        this.lastCrawledAt = lastCrawledAt;
        this.isActive = isActive;
        this.priority = priority;
        this.updatedAt = updatedAt;
    }
}
