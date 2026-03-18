package com.rheon.royale.domain.card.application;

import com.rheon.royale.domain.card.dao.StatsDailyRepository;
import com.rheon.royale.domain.card.dto.DeckStatsResponse;
import com.rheon.royale.global.error.BusinessException;
import com.rheon.royale.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
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
     * 캐시 전략:
     *   - TTL 1시간: stats 집계는 일 1회 배치 갱신 → 빈번 조회 불필요
     *   - key = battleType:days → 파라미터 조합별 독립 캐시
     */
    @Cacheable(value = "tierList", key = "#battleType + ':' + #days")
    public List<DeckStatsResponse> getTierList(String battleType, int days) {
        List<DeckStatsResponse> result = statsDailyRepository.findTierList(
                battleType, days, DEFAULT_MIN_GAMES, DEFAULT_LIMIT);

        if (result.isEmpty()) {
            throw new BusinessException(ErrorCode.STATS_NOT_FOUND);
        }
        return result;
    }
}
