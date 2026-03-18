package com.rheon.royale.batch.card;

import com.rheon.royale.domain.repository.CardRepository;
import com.rheon.royale.infrastructure.external.clashroyale.ClashRoyaleClient;
import com.rheon.royale.infrastructure.external.clashroyale.dto.CrCardItem;
import com.rheon.royale.infrastructure.external.clashroyale.dto.CrCardListResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class CardSyncTasklet implements Tasklet {

    private final ClashRoyaleClient clashRoyaleClient;
    private final CardRepository cardRepository;

    @Override
    @Transactional
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        CrCardListResponse response = clashRoyaleClient.getCards();

        int synced = 0;
        if (response.items() != null) {
            for (CrCardItem card : response.items()) {
                synced += upsertDeckCard(card);
            }
        }
        if (response.supportItems() != null) {
            for (CrCardItem card : response.supportItems()) {
                upsertSupportCard(card);
                synced++;
            }
        }

        log.info("[CardSyncJob] 카드 동기화 완료: {}건", synced);
        contribution.incrementWriteCount(synced);
        return RepeatStatus.FINISHED;
    }

    /**
     * 덱 카드 (items)
     * - 챔피언(heroMedium 있음): HERO 1개
     * - 진화 가능 카드(maxEvolutionLevel > 0): NORMAL + EVOLUTION 2개
     * - 일반 카드: NORMAL 1개
     */
    private int upsertDeckCard(CrCardItem card) {
        boolean isHero = card.iconUrls() != null && card.iconUrls().heroMedium() != null;
        boolean hasEvolution = card.maxEvolutionLevel() != null && card.maxEvolutionLevel() > 0;

        if (isHero) {
            String iconUrl = card.iconUrls().heroMedium();
            cardRepository.upsert(
                    card.id() + "_HERO", card.id(), card.name(), "HERO",
                    toRarityStr(card.rarity()), card.elixirCost(),
                    card.maxLevel(), null, iconUrl, true
            );
            return 1;
        }

        String normalIcon = card.iconUrls() != null ? card.iconUrls().medium() : null;
        cardRepository.upsert(
                card.id() + "_NORMAL", card.id(), card.name(), "NORMAL",
                toRarityStr(card.rarity()), card.elixirCost(),
                card.maxLevel(), card.maxEvolutionLevel(), normalIcon, true
        );

        if (hasEvolution) {
            String evoIcon = card.iconUrls() != null ? card.iconUrls().evolutionMedium() : null;
            cardRepository.upsert(
                    card.id() + "_EVOLUTION", card.id(), card.name() + " (Evolution)", "EVOLUTION",
                    toRarityStr(card.rarity()), card.elixirCost(),
                    card.maxLevel(), card.maxEvolutionLevel(), evoIcon, true
            );
            return 2;
        }

        return 1;
    }

    /** 서포트 카드 (supportItems) — 타워 카드 등, is_deck_card=false */
    private void upsertSupportCard(CrCardItem card) {
        String iconUrl = card.iconUrls() != null ? card.iconUrls().medium() : null;
        cardRepository.upsert(
                card.id() + "_NORMAL", card.id(), card.name(), "NORMAL",
                toRarityStr(card.rarity()), card.elixirCost(),
                card.maxLevel(), null, iconUrl, false
        );
    }

    private String toRarityStr(String rarity) {
        if (rarity == null) return null;
        return rarity.toUpperCase().replace(" ", "_");
    }
}
