package com.rheon.royale.domain.card.api;

import com.rheon.royale.domain.card.application.CardService;
import com.rheon.royale.domain.card.dto.CardMetaResponse;
import com.rheon.royale.domain.card.dto.CardRankingResponse;
import com.rheon.royale.domain.card.dto.DeckStatsResponse;
import com.rheon.royale.global.error.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

@Tag(name = "Card", description = "카드 메타 및 덱/카드 순위")
@RestController
@RequestMapping("/api/v1/cards")
@RequiredArgsConstructor
public class CardController {

    private static final Set<Integer> ALLOWED_DAYS = Set.of(1, 3, 7);

    private final CardService cardService;

    @Operation(summary = "덱 티어표", description = "meta_score 기준 덱 순위. PoL: leagueNumber(1~7), PvP: minTrophies+maxTrophies 아레나 범위")
    @GetMapping("/tier")
    public ApiResponse<List<DeckStatsResponse>> getTierList(
            @RequestParam(defaultValue = "pathOfLegend") String battleType,
            @RequestParam(defaultValue = "7") int days,
            @Parameter(description = "PoL 리그 번호 (1~7)")
            @RequestParam(required = false) Integer leagueNumber,
            @Parameter(description = "PvP 아레나 최소 트로피")
            @RequestParam(required = false) Integer minTrophies,
            @Parameter(description = "PvP 아레나 최대 트로피")
            @RequestParam(required = false) Integer maxTrophies) {
        int validDays = ALLOWED_DAYS.contains(days) ? days : 7;
        return ApiResponse.ok(cardService.getTierList(battleType, validDays, leagueNumber, minTrophies, maxTrophies));
    }

    @Operation(summary = "카드 순위", description = "meta_score 기준 카드 순위. PoL: leagueNumber(1~7), PvP: minTrophies+maxTrophies")
    @GetMapping("/ranking")
    public ApiResponse<List<CardRankingResponse>> getCardRanking(
            @RequestParam(defaultValue = "pathOfLegend") String battleType,
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(required = false) Integer leagueNumber,
            @RequestParam(required = false) Integer minTrophies,
            @RequestParam(required = false) Integer maxTrophies) {
        int validDays = ALLOWED_DAYS.contains(days) ? days : 7;
        return ApiResponse.ok(cardService.getCardRanking(battleType, validDays, leagueNumber, minTrophies, maxTrophies));
    }

    @Operation(summary = "카드 메타", description = "카드 id→name 매핑 (24시간 캐시)")
    @GetMapping
    public ApiResponse<List<CardMetaResponse>> getCardMeta() {
        return ApiResponse.ok(cardService.getCardMeta());
    }
}
