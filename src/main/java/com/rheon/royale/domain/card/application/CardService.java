package com.rheon.royale.domain.card.application;

import com.rheon.royale.domain.card.dao.StatsDailyRepository;
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

    static final int DEFAULT_DAYS    = 30;
    static final int DEFAULT_MIN_GAMES = 10;  // 표본 부족 덱 제외
    static final int DEFAULT_LIMIT   = 50;

    private final StatsDailyRepository statsDailyRepository;

    /**
     * 덱 티어표 조회
     *
     * 캐시 전략 (기간별 변동성에 따라 TTL 차등):
     *   - 1일 → 10분 (당일 데이터, 배치 직후 빠르게 갱신)
     *   - 3일 → 30분
     *   - 7일 → 1시간 (기본값, 주간 집계)
     *   - 30일 → 1시간 (월간 집계, 거의 변동 없음)
     */
    @Caching(cacheable = {
            @Cacheable(value = "tierList_1",  key = "#battleType", condition = "#days == 1"),
            @Cacheable(value = "tierList_3",  key = "#battleType", condition = "#days == 3"),
            @Cacheable(value = "tierList_7",  key = "#battleType", condition = "#days == 7"),
            @Cacheable(value = "tierList_30", key = "#battleType", condition = "#days == 30")
    })
    public List<DeckStatsResponse> getTierList(String battleType, int days) {
        List<DeckStatsResponse> result = statsDailyRepository.findTierList(
                battleType, days, DEFAULT_MIN_GAMES, DEFAULT_LIMIT);

        if (result.isEmpty()) {
            throw new BusinessException(ErrorCode.STATS_NOT_FOUND);
        }
        return result;
    }
}
