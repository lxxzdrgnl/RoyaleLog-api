package com.rheon.royale.domain.card.application;

import com.rheon.royale.domain.card.dao.StatsDailyRepository;
import com.rheon.royale.domain.card.dto.CardMetaResponse;
import com.rheon.royale.domain.card.dto.CardRankingResponse;
import com.rheon.royale.domain.card.dto.DeckStatsResponse;
import com.rheon.royale.global.error.BusinessException;
import com.rheon.royale.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CardService {

    static final int DEFAULT_MIN_GAMES = 20;
    static final int DEFAULT_LIMIT     = 200;

    private final StatsDailyRepository statsDailyRepository;

    /**
     * 덱 티어표 — meta_score 정렬
     * PoL: leagueNumber(1~7) 필터
     * PvP: minTrophies/maxTrophies 아레나 범위 필터
     * days=1 → 2일 윈도우 (자정 직후 빈 날짜 방지)
     */
    @Caching(cacheable = {
            @Cacheable(value = "tierList_1", key = "#battleType + '_' + (#leagueNumber ?: '') + '_' + (#minTrophies ?: '')", condition = "#days == 1"),
            @Cacheable(value = "tierList_3", key = "#battleType + '_' + (#leagueNumber ?: '') + '_' + (#minTrophies ?: '')", condition = "#days == 3"),
            @Cacheable(value = "tierList_7", key = "#battleType + '_' + (#leagueNumber ?: '') + '_' + (#minTrophies ?: '')", condition = "#days == 7")
    })
    public List<DeckStatsResponse> getTierList(String battleType, int days,
                                               Integer leagueNumber, Integer minTrophies, Integer maxTrophies) {
        int effectiveDays = (days == 1) ? 2 : days;
        List<DeckStatsResponse> result = statsDailyRepository.findTierList(
                battleType, effectiveDays, DEFAULT_MIN_GAMES, DEFAULT_LIMIT,
                leagueNumber, minTrophies, maxTrophies);
        if (result.isEmpty()) {
            throw new BusinessException(ErrorCode.STATS_NOT_FOUND);
        }
        return result;
    }

    /**
     * 카드 순위 — meta_score 정렬
     */
    @Caching(cacheable = {
            @Cacheable(value = "cardRanking_1", key = "#battleType + '_' + (#leagueNumber ?: '') + '_' + (#minTrophies ?: '')", condition = "#days == 1"),
            @Cacheable(value = "cardRanking_3", key = "#battleType + '_' + (#leagueNumber ?: '') + '_' + (#minTrophies ?: '')", condition = "#days == 3"),
            @Cacheable(value = "cardRanking_7", key = "#battleType + '_' + (#leagueNumber ?: '') + '_' + (#minTrophies ?: '')", condition = "#days == 7")
    })
    public List<CardRankingResponse> getCardRanking(String battleType, int days,
                                                    Integer leagueNumber, Integer minTrophies, Integer maxTrophies) {
        int effectiveDays = (days == 1) ? 2 : days;
        List<CardRankingResponse> result = statsDailyRepository.findCardRanking(
                battleType, effectiveDays, leagueNumber, minTrophies, maxTrophies);
        if (result.isEmpty()) {
            throw new BusinessException(ErrorCode.STATS_NOT_FOUND);
        }
        return result;
    }

    @Cacheable(value = "cards")
    public List<CardMetaResponse> getCardMeta() {
        return statsDailyRepository.findCardMeta();
    }
}
