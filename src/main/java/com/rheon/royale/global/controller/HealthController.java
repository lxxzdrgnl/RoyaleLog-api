package com.rheon.royale.global.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@Tag(name = "System", description = "시스템 상태")
@RestController
public class HealthController {

    @Value("${spring.application.version:unknown}")
    private String version;

    @Value("${spring.application.build-time:unknown}")
    private String buildTime;

    @Operation(summary = "헬스 체크", description = "서버 상태, 버전, 빌드 시각 반환")
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of(
                "status", "UP",
                "version", version,
                "buildTime", buildTime,
                "serverTime", Instant.now().toString()
        );
    }
}
