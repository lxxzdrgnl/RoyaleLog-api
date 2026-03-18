package com.rheon.royale.domain.match.api;

import com.rheon.royale.domain.match.application.MatchService;
import com.rheon.royale.domain.match.dto.PlayerBattlesResponse;
import com.rheon.royale.global.error.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/matches")
@RequiredArgsConstructor
public class MatchController {

    private final MatchService matchService;

    /**
     * GET /api/v1/matches/{playerTag}
     *
     * 플레이어 최근 배틀 로그 조회
     * - tag: URL 경로에서 #을 %23으로 인코딩해서 전달 가능
     * - 미분석 배틀은 deckHash/result null로 반환 (LEFT JOIN)
     * - Redis TTL: 5분
     */
    @GetMapping("/{playerTag}")
    public ApiResponse<PlayerBattlesResponse> getBattles(@PathVariable String playerTag) {
        return ApiResponse.ok(matchService.getBattles(playerTag));
    }
}
