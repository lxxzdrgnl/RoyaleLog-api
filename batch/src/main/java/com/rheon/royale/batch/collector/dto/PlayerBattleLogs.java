package com.rheon.royale.batch.collector.dto;

import com.rheon.royale.domain.entity.BattleLogRaw;
import com.rheon.royale.domain.entity.PlayerToCrawl;

import java.util.List;
import java.util.Map;

/**
 * Processor → Writer 전달 단위
 * 플레이어 1명 + 해당 플레이어의 배틀 목록
 * + 배틀에서 발견한 상대방 목록 (BFS 풀 확장용)
 * + 플레이어의 최신 트로피/리그/브라켓 (CR API newest-first 첫 유효값)
 */
public record PlayerBattleLogs(
        PlayerToCrawl player,
        List<BattleLogRaw> battles,
        Map<String, DiscoveredOpponent> discoveredOpponents, // tag → opponent 정보
        Integer currentTrophies,   // 플레이어 본인 최신 트로피 (ladder 기준)
        Integer leagueNumber,      // 플레이어 본인 최신 PoL 리그 레벨
        String bracket             // 플레이어 브라켓 (updateAfterCrawl 전달용)
) {}
