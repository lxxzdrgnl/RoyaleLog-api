package com.rheon.royale.domain.match.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ParticipantDetail(
        String tag,
        String name,
        String clanName,              // 클랜 없으면 null
        int crowns,
        Integer startingTrophies,     // 경기 시작 트로피 (랭크 배지용)
        Integer trophyChange,         // 트로피 변화량 (+1, -10 등)
        Integer kingTowerHitPoints,   // 킹타워 잔여 HP (0이면 파괴됨)
        Double elixirLeaked,          // 낭비된 엘릭서
        List<CardInfo> cards,
        CardInfo towerCard,           // null이면 미확인
        String deckHash,
        BigDecimal avgElixir,
        BigDecimal cycleElixir,       // 4장 최저 엘릭서 합 (사이클 속도)
        int evolutionCount
) {}
