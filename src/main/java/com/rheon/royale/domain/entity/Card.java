package com.rheon.royale.domain.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.PostgreSQLEnumJdbcType;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "cards")
public class Card {

    /** "{api_id}_{card_type}" e.g. "26000000_NORMAL", "26000000_EVOLUTION" */
    @Id
    @Column(name = "card_key", length = 50)
    private String cardKey;

    @Column(name = "api_id", nullable = false)
    private Long apiId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "card_type", nullable = false)
    private CardType cardType;

    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "rarity")
    private CardRarity rarity;

    @Column(name = "elixir_cost")
    private Integer elixirCost;

    @Column(name = "max_level")
    private Integer maxLevel;

    @Column(name = "max_evo_level")
    private Integer maxEvoLevel;

    @Column(name = "icon_url")
    private String iconUrl;

    /** /cards items → true, supportItems → false */
    @Column(name = "is_deck_card", nullable = false)
    private boolean isDeckCard;

    @Column(name = "synced_at", nullable = false)
    private LocalDateTime syncedAt;

    @Builder
    public Card(String cardKey, Long apiId, String name, CardType cardType,
                CardRarity rarity, Integer elixirCost, Integer maxLevel,
                Integer maxEvoLevel, String iconUrl, boolean isDeckCard,
                LocalDateTime syncedAt) {
        this.cardKey = cardKey;
        this.apiId = apiId;
        this.name = name;
        this.cardType = cardType;
        this.rarity = rarity;
        this.elixirCost = elixirCost;
        this.maxLevel = maxLevel;
        this.maxEvoLevel = maxEvoLevel;
        this.iconUrl = iconUrl;
        this.isDeckCard = isDeckCard;
        this.syncedAt = syncedAt;
    }
}
