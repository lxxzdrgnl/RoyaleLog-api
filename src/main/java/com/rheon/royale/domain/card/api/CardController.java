package com.rheon.royale.domain.card.api;

import com.rheon.royale.domain.card.application.CardService;
import com.rheon.royale.domain.card.dto.DeckStatsResponse;
import com.rheon.royale.global.error.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

@Tag(name = "Card", description = "카드 메타 및 덱 티어표")
@RestController
@RequestMapping("/api/v1/cards")
@RequiredArgsConstructor
public class CardController {

    private static final Set<Integer> ALLOWED_DAYS = Set.of(1, 3, 7, 30);

    private final CardService cardService;

    @Operation(
            summary = "덱 티어표 조회",
            description = """
                    지정 기간 내 덱별 승률/사용률을 내림차순으로 반환합니다.

                    - 표본 **10건 미만** 덱은 제외됩니다 (신뢰도 보장)
                    - `winRate`: 0.0 ~ 100.0 (%)
                    - `days` 허용값: 1 / 3 / 7 / 30 (그 외는 7로 처리)
                    - 기간별 Redis TTL: 1일→10분, 3일→30분, 7일/30일→1시간
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "해당 조건의 통계 데이터 없음",
                    content = @Content(examples = @ExampleObject(
                            value = """
                            {"success":false,"message":"해당 시즌의 통계 데이터가 없습니다."}
                            """)))
    })
    @GetMapping("/tier")
    public ApiResponse<List<DeckStatsResponse>> getTierList(
            @Parameter(description = "배틀 타입", example = "pathOfLegend")
            @RequestParam(defaultValue = "pathOfLegend") String battleType,

            @Parameter(description = "집계 기간 (일, 허용값: 1/3/7/30)", example = "7")
            @RequestParam(defaultValue = "7") int days) {
        int validDays = ALLOWED_DAYS.contains(days) ? days : 7;
        return ApiResponse.ok(cardService.getTierList(battleType, validDays));
    }
}
