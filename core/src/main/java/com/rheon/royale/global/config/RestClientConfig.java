package com.rheon.royale.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.ClientCodecConfigurer;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class RestClientConfig {

    private static final int MAX_BUFFER_SIZE = 10 * 1024 * 1024; // 10MB

    /**
     * 공용 WebClient.Builder.
     * 배틀로그 JSON이 256KB 기본 한도를 초과하므로 코덱 버퍼를 10MB로 설정.
     */
    @Bean
    public WebClient.Builder webClientBuilder() {
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_BUFFER_SIZE))
                .build();
        return WebClient.builder().exchangeStrategies(strategies);
    }
}
