package com.rheon.royale.domain.player.api;

import com.rheon.royale.domain.player.application.PlayerService;
import com.rheon.royale.global.error.ApiResponse;
import com.rheon.royale.infrastructure.external.clashroyale.dto.CrPlayerProfile;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "Player", description = "플레이어 프로필 조회")
@RestController
@RequestMapping("/api/v1/players")
@RequiredArgsConstructor
public class PlayerController {

    private final PlayerService playerService;

    @Operation(
            summary = "플레이어 프로필 조회",
            description = "태그로 플레이어 정보 조회. # 포함/미포함 모두 가능. 예: #PGR9PPG, PGR9PPG"
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "잘못된 태그 형식"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "플레이어 없음")
    })
    @GetMapping("/{tag}")
    public ApiResponse<CrPlayerProfile> getProfile(
            @Parameter(description = "플레이어 태그 (#PGR9PPG 또는 PGR9PPG)")
            @PathVariable String tag) {
        return ApiResponse.ok(playerService.getProfile(tag));
    }

    @Operation(
            summary = "플레이어 배틀 로그 실시간 조회",
            description = "Clash Royale API 직접 호출 — DB 수집 여부와 무관하게 모든 플레이어 조회 가능"
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "플레이어 없음")
    })
    @GetMapping("/{tag}/battles")
    public ApiResponse<List<Map<String, Object>>> getBattleLog(
            @Parameter(description = "플레이어 태그 (#PGR9PPG 또는 PGR9PPG)")
            @PathVariable String tag,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "25") int limit) {
        List<Map<String, Object>> all = playerService.getBattleLog(tag);
        if (offset >= all.size()) return ApiResponse.ok(List.of());
        return ApiResponse.ok(all.subList(offset, Math.min(offset + Math.min(limit, 100), all.size())));
    }

    @Operation(summary = "플레이어 마스터리 조회", description = "카드별 도전과제 진척도")
    @GetMapping("/{tag}/masteries")
    public ApiResponse<List<Map<String, Object>>> getMasteries(
            @Parameter(description = "플레이어 태그")
            @PathVariable String tag) {
        return ApiResponse.ok(playerService.getMasteries(tag));
    }
}
