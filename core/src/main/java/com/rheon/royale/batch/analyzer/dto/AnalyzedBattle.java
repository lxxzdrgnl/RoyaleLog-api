package com.rheon.royale.batch.analyzer.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Processor → Writer 전달 단위
 *
 * 해시 2종 설계:
 *   - base  deck_hash : 카드 ID만 (진화 무관) → deck_dictionary 키 / 유사덱 그룹핑 기준
 *   - refined deck_hash : "id:evoLevel" 페어 → 진화/히어로 포함 정밀 식별 → stats 집계 기준
 *
 * deck_hash / opponentHash 모두 포함:
 *   - deck_dictionary 에 양쪽 덱 upsert (base hash 기준)
 *   - match_features 에 상대 덱 정보도 기록 → ML 매치업 학습 가능
 */
public record AnalyzedBattle(
        String battleId,
        LocalDateTime createdAt,          // partition-aware UPDATE 에 필요
        LocalDate battleDate,
        String battleType,
        String deckHash,                  // 내 덱 base hash (카드 ID만)
        String refinedDeckHash,           // 내 덱 refined hash (진화/히어로 포함)
        Long[] cardIds,                   // 정렬된 9개 (숫자 오름차순)
        String opponentHash,              // 상대 덱 base hash
        String refinedOpponentHash,       // 상대 덱 refined hash
        Long[] opponentCardIds,           // 정렬된 9개
        int result,                       // 1=win, 0=loss
        BigDecimal avgLevel,
        int evolutionCount,
        Integer leagueNumber,             // pathOfLegend 전용 (0~9), 나머지 null
        Integer startingTrophies,         // PvP 트로피 모드 전용, 나머지 null
        Short[] evoLevels,                // 내 덱 카드별 진화 레벨 (cardIds와 동일 순서)
        Short[] opponentEvoLevels,        // 상대 덱 카드별 진화 레벨 (opponentCardIds와 동일 순서)
        Short[] cardLevels,               // 내 덱 카드별 레벨 (cardIds와 동일 순서, 0-14)
        Short[] opponentCardLevels        // 상대 덱 카드별 레벨 (opponentCardIds와 동일 순서, 0-14)
) {}
