package com.rheon.royale.domain.match.api;

import com.rheon.royale.domain.match.application.MatchService;
import com.rheon.royale.domain.match.dto.PlayerBattlesResponse;
import com.rheon.royale.global.error.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Match", description = "플레이어 전적 조회")
@RestController
@RequestMapping("/api/v1/matches")
@RequiredArgsConstructor
public class MatchController {

    private final MatchService matchService;

    @Operation(
            summary = "플레이어 배틀 로그 조회",
            description = """
                    플레이어 태그로 최근 25건의 배틀 로그를 조회합니다.

                    - `#`은 URL에서 `%23`으로 인코딩합니다 (예: `%23ABC123`)
                    - 분석 완료 배틀: `deckHash`, `result` 포함
                    - 분석 대기 배틀: `deckHash`, `result` = null
                    - Redis TTL: **5분**
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "잘못된 태그 형식",
                    content = @Content(examples = @ExampleObject(
                            value = """
                            {"success":false,"message":"올바르지 않은 플레이어 태그 형식입니다."}
                            """))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "플레이어를 찾을 수 없음",
                    content = @Content(examples = @ExampleObject(
                            value = """
                            {"success":false,"message":"플레이어를 찾을 수 없습니다."}
                            """)))
    })
    @GetMapping("/{playerTag}")
    public ApiResponse<PlayerBattlesResponse> getBattles(
            @Parameter(description = "플레이어 태그 (예: %23ABC123 또는 ABC123)", example = "%23GU2YR209R")
            @PathVariable String playerTag,
            @Parameter(description = "건너뛸 건수 (무한스크롤용)", example = "0")
            @RequestParam(defaultValue = "0") int offset,
            @Parameter(description = "조회 건수 (최대 25)", example = "25")
            @RequestParam(defaultValue = "25") int limit) {
        return ApiResponse.ok(matchService.getBattles(playerTag, offset, limit));
    }
}
