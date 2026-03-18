package com.rheon.royale.batch.collector.dto;

import com.rheon.royale.domain.entity.BattleLogRaw;
import com.rheon.royale.domain.entity.PlayerToCrawl;

import java.util.List;

/**
 * Processor → Writer 전달 단위
 * 플레이어 1명 + 해당 플레이어의 pathOfLegend 배틀 목록
 */
public record PlayerBattleLogs(
        PlayerToCrawl player,
        List<BattleLogRaw> battles
) {}
