package com.rheon.royale.infrastructure.external.clashroyale;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "clash.api")
public class ClashRoyaleProperties {

    @NotBlank
    private String baseUrl;

    @NotBlank
    private String token;

    @Min(1)
    private int rateLimit = 10;   // 초당 최대 요청 수

    @Min(1)
    private int retryMax = 3;     // 429/5xx 재시도 횟수
}
