package com.rheon.royale.domain.match.application;

import com.rheon.royale.domain.match.dao.MatchRepository;
import com.rheon.royale.domain.match.dto.BattleLogEntry;
import com.rheon.royale.domain.match.dto.PlayerBattlesResponse;
import com.rheon.royale.global.error.BusinessException;
import com.rheon.royale.global.error.ErrorCode;
import com.rheon.royale.global.util.TagUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MatchService {

    static final int DEFAULT_LIMIT = 25;

    private final MatchRepository matchRepository;

    /**
     * 플레이어 태그로 최근 배틀 조회
     *
     * 캐시 전략:
     *   - TTL 5분: 외부 API 어뷰징 방지 (유저가 짧은 시간 반복 조회)
     *   - key = 정규화된 태그 (#ABC123) → 대소문자 혼용 방지
     */
    @Cacheable(value = "playerBattleLog", key = "#tag.toUpperCase()")
    public PlayerBattlesResponse getBattles(String tag) {
        String normalizedTag = TagUtils.normalize(tag);

        if (!matchRepository.existsByPlayerTag(normalizedTag)) {
            throw new BusinessException(ErrorCode.PLAYER_NOT_FOUND);
        }

        List<BattleLogEntry> battles = matchRepository.findByPlayerTag(normalizedTag, DEFAULT_LIMIT);
        return new PlayerBattlesResponse(normalizedTag, battles);
    }
}
