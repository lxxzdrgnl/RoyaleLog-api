package com.rheon.royale.batch.collector.dto;

import com.rheon.royale.domain.entity.BattleLogRaw;
import com.rheon.royale.domain.entity.PlayerToCrawl;

import java.util.List;
import java.util.Map;

/**
 * Processor → Writer 전달 단위
 * 플레이어 1명 + 해당 플레이어의 pathOfLegend 배틀 목록
 * + 배틀에서 발견한 상대방 목록 (BFS 풀 확장용)
 */
public record PlayerBattleLogs(
        PlayerToCrawl player,
        List<BattleLogRaw> battles,
        Map<String, String> discoveredOpponents  // tag → name (중복 제거 자동)
) {}
