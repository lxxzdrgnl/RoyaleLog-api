package com.rheon.royale.global.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("RoyaleLog API")
                        .description("Clash Royale 전적 검색 및 AI 승률 예측 서비스")
                        .version("v1"))
                .servers(List.of(
                        new Server().url("/").description("Current")
                ));
    }
}
