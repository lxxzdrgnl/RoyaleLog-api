package com.rheon.royale.domain.match.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * 전적 상세 응답 DTO
 * - 카드 이름/아이콘/레벨/진화 포함
 * - 크라운, 클랜, 덱 해시 포함
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BattleEntry(
        String battleId,
        String battleType,
        String gameMode,           // "C.H.A.O.S", "Ladder" 등
        LocalDateTime battleTime,
        ParticipantDetail team,
        ParticipantDetail opponent
) {}
