package com.rheon.royale.domain.card.api;

import com.rheon.royale.domain.card.application.CardService;
import com.rheon.royale.domain.card.dto.DeckStatsResponse;
import com.rheon.royale.global.error.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/cards")
@RequiredArgsConstructor
public class CardController {

    private final CardService cardService;

    /**
     * GET /api/v1/cards/tier?battleType=pathOfLegend&days=30
     *
     * 덱 티어표 조회 (승률 기준 내림차순)
     * - battleType: pathOfLegend(기본값), ladder 등
     * - days: 집계 기간 (기본값 30일)
     * - 표본 10건 미만 덱 제외
     * - Redis TTL: 1시간
     */
    @GetMapping("/tier")
    public ApiResponse<List<DeckStatsResponse>> getTierList(
            @RequestParam(defaultValue = "pathOfLegend") String battleType,
            @RequestParam(defaultValue = "30") int days) {
        return ApiResponse.ok(cardService.getTierList(battleType, days));
    }
}
