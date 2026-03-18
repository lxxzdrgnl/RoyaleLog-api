package com.rheon.royale.domain.match.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 단건 배틀 조회 결과
 * - match_features 미분석 배틀은 deckHash/result/avgLevel null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BattleLogEntry(
        String battleId,
        String battleType,
        LocalDateTime battleTime,
        LocalDate battleDate,
        String deckHash,
        String opponentHash,
        Integer result,        // 1=win, 0=loss, null=미분석
        BigDecimal avgLevel,
        Integer evolutionCount
) {}
