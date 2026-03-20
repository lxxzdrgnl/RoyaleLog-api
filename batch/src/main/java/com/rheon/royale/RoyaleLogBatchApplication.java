package com.rheon.royale;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RoyaleLogBatchApplication {
    public static void main(String[] args) {
        SpringApplication.run(RoyaleLogBatchApplication.class, args);
    }
}
