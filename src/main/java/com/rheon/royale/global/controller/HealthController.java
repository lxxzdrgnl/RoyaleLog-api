package com.rheon.royale.global.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
public class HealthController {

    @Value("${spring.application.version:unknown}")
    private String version;

    @Value("${spring.application.build-time:unknown}")
    private String buildTime;

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
