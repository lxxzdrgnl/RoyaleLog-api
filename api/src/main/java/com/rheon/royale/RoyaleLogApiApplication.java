package com.rheon.royale;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Slf4j
@SpringBootApplication
@EnableCaching
@EnableJpaAuditing
@ConfigurationPropertiesScan
public class RoyaleLogApiApplication {

    private final Environment env;

    public RoyaleLogApiApplication(Environment env) {
        this.env = env;
    }

    public static void main(String[] args) {
        SpringApplication.run(RoyaleLogApiApplication.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        String port = env.getProperty("server.port", "8080");
        log.info("""

                ╔══════════════════════════════════════════════════╗
                ║           RoyaleLog API  —  Started              ║
                ╠══════════════════════════════════════════════════╣
                ║  Profile  : {}
                ║  Swagger  : http://localhost:{}/swagger-ui/index.html
                ║  Actuator : http://localhost:{}/actuator/health
                ╚══════════════════════════════════════════════════╝""",
                String.join(", ", env.getActiveProfiles()),
                port, port);
    }
}
