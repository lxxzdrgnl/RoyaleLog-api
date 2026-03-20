package com.rheon.royale.domain.match.application;

import com.rheon.royale.domain.entity.PlayerToCrawl;
import com.rheon.royale.domain.match.dao.MatchRepository;
import com.rheon.royale.domain.match.dto.BattleEntry;
import com.rheon.royale.domain.match.dto.PlayerBattlesResponse;
import com.rheon.royale.domain.match.dto.PlayerSearchResult;
import com.rheon.royale.domain.repository.PlayerToCrawlRepository;
import com.rheon.royale.global.util.TagUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class MatchService {

    static final int DEFAULT_LIMIT = 25;

    private final MatchRepository matchRepository;
    private final OnDemandMatchService onDemandMatchService;
    private final PlayerToCrawlRepository playerToCrawlRepository;

    /**
     * 플레이어 태그로 최근 배틀 조회 — 스마트 라우팅
     *
     * [라우팅 전략]
     *   1. players_to_crawl에 없음 (신규 유저)    → On-Demand + DB 저장 + 풀 편입
     *   2. players_to_crawl에 있지만 is_active=false (휴면 유저) → On-Demand + 재활성화
     *   3. is_active=true 이지만 battle_log_raw 데이터 없음 (첫 배치 전) → On-Demand
     *   4. is_active=true + DB 데이터 있음 (활성 랭커/수집 유저) → DB 응답
     */
    public PlayerBattlesResponse getBattles(String tag, int offset, int limit) {
        String normalizedTag = TagUtils.normalize(tag);
        int safeLimit = Math.min(limit, DEFAULT_LIMIT);

        Optional<PlayerToCrawl> playerOpt = playerToCrawlRepository.findById(normalizedTag);

        boolean needsOnDemand = playerOpt.isEmpty() || !playerOpt.get().isActive();

        if (needsOnDemand) {
            log.info("[MatchService] {} — {} → On-Demand 실시간 조회",
                    normalizedTag, playerOpt.isEmpty() ? "신규 유저" : "휴면 유저(재활성화)");
            return toSlicedResponse(normalizedTag,
                    onDemandMatchService.fetchAndAnalyze(normalizedTag), offset, safeLimit);
        }

        // 활성 유저이지만 battle_log_raw에 데이터가 아직 없는 경우 (BFS로 방금 추가된 유저)
        List<BattleEntry> battles = matchRepository.findByPlayerTag(normalizedTag, safeLimit, offset);
        if (battles.isEmpty()) {
            log.info("[MatchService] {} — players_to_crawl 등록됐지만 배치 미수집 → On-Demand", normalizedTag);
            return toSlicedResponse(normalizedTag,
                    onDemandMatchService.fetchAndAnalyze(normalizedTag), offset, safeLimit);
        }

        return new PlayerBattlesResponse(normalizedTag, battles);
    }

    /**
     * 닉네임으로 플레이어 검색 (최대 20건)
     * - players_to_crawl 기반 (한 번이라도 배틀에서 만난 유저 포함)
     * - pg_trgm GIN 인덱스로 ILIKE 양방향 검색 고속 처리
     */
    public List<PlayerSearchResult> searchByName(String name) {
        return matchRepository.searchByName(name);
    }

    private PlayerBattlesResponse toSlicedResponse(String tag, List<BattleEntry> all, int offset, int limit) {
        List<BattleEntry> sliced = offset < all.size()
                ? all.subList(offset, Math.min(offset + limit, all.size()))
                : List.of();
        return new PlayerBattlesResponse(tag, sliced);
    }
}
