package com.rheon.royale.batch.analyzer.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Processor → Writer 전달 단위
 *
 * deck_hash / opponentHash 모두 포함:
 *   - deck_dictionary 에 양쪽 덱 upsert
 *   - match_features 에 상대 덱 정보도 기록 → ML 매치업 학습 가능
 */
public record AnalyzedBattle(
        String battleId,
        LocalDateTime createdAt,      // partition-aware UPDATE 에 필요
        LocalDate battleDate,
        String battleType,
        String deckHash,              // 내 덱 (team[0])
        Long[] cardIds,               // 정렬된 9개
        String opponentHash,          // 상대 덱 (opponent[0])
        Long[] opponentCardIds,       // 정렬된 9개
        int result,                   // 1=win, 0=loss
        BigDecimal avgLevel,
        int evolutionCount
) {}
