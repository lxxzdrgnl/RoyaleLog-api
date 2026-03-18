package com.rheon.royale.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class RestClientConfig {

    /**
     * ML 서버(FastAPI) 전용 WebClient.
     * base-url, timeout은 MlServerProperties에서 주입받아 MlServerClient에서 설정한다.
     */
    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }
}
