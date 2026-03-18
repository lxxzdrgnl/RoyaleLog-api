package com.rheon.royale.domain.match.application;

import com.rheon.royale.domain.match.dao.MatchRepository;
import com.rheon.royale.domain.match.dto.BattleEntry;
import com.rheon.royale.domain.match.dto.PlayerBattlesResponse;
import com.rheon.royale.global.util.TagUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MatchService {

    static final int DEFAULT_LIMIT = 25;

    private final MatchRepository matchRepository;
    private final OnDemandMatchService onDemandMatchService;

    /**
     * 플레이어 태그로 최근 배틀 조회
     *
     * [조회 전략]
     *   1. DB에 수집된 데이터가 있으면 (랭커) → DB + raw_json 파싱 후 반환
     *   2. DB에 없으면 (일반 유저) → Clash API 실시간 호출 + 인메모리 분석 후 반환
     *      - battle_log_raw 에 저장하지 않음 → Analyzer Job 통계 오염 방지
     *      - 결과는 Redis 5분 캐시
     */
    public PlayerBattlesResponse getBattles(String tag, int offset, int limit) {
        String normalizedTag = TagUtils.normalize(tag);
        int safeLimit = Math.min(limit, DEFAULT_LIMIT);

        if (!matchRepository.existsByPlayerTag(normalizedTag)) {
            // 온디맨드: CR API 최대 25건, offset으로 슬라이싱
            List<BattleEntry> all = onDemandMatchService.fetchAndAnalyze(normalizedTag);
            List<BattleEntry> sliced = offset < all.size()
                    ? all.subList(offset, Math.min(offset + safeLimit, all.size()))
                    : List.of();
            return new PlayerBattlesResponse(normalizedTag, sliced);
        }

        List<BattleEntry> battles = matchRepository.findByPlayerTag(normalizedTag, safeLimit, offset);
        return new PlayerBattlesResponse(normalizedTag, battles);
    }
}
