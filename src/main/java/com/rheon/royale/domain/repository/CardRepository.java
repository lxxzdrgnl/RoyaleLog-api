package com.rheon.royale.domain.repository;

import com.rheon.royale.domain.entity.Card;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CardRepository extends JpaRepository<Card, String> {

    @Modifying
    @Query(value = """
            INSERT INTO cards (card_key, api_id, name, card_type, rarity, elixir_cost,
                               max_level, max_evo_level, icon_url, is_deck_card, synced_at)
            VALUES (:cardKey, :apiId, :name, CAST(:cardType AS card_type_enum),
                    CAST(:rarity AS card_rarity_enum), :elixirCost, :maxLevel,
                    :maxEvoLevel, :iconUrl, :isDeckCard, NOW())
            ON CONFLICT (card_key) DO UPDATE SET
                name        = EXCLUDED.name,
                elixir_cost = EXCLUDED.elixir_cost,
                max_level   = EXCLUDED.max_level,
                max_evo_level = EXCLUDED.max_evo_level,
                icon_url    = EXCLUDED.icon_url,
                synced_at   = EXCLUDED.synced_at
            """, nativeQuery = true)
    void upsert(@Param("cardKey") String cardKey,
                @Param("apiId") long apiId,
                @Param("name") String name,
                @Param("cardType") String cardType,
                @Param("rarity") String rarity,
                @Param("elixirCost") Integer elixirCost,
                @Param("maxLevel") Integer maxLevel,
                @Param("maxEvoLevel") Integer maxEvoLevel,
                @Param("iconUrl") String iconUrl,
                @Param("isDeckCard") boolean isDeckCard);
}
