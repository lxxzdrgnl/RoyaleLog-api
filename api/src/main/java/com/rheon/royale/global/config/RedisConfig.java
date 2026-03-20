package com.rheon.royale.global.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.Map;

@Configuration
@EnableCaching
public class RedisConfig {

    @Value("${cache.ttl.tier-list:3600}")
    private long tierListTtl;

    @Value("${cache.ttl.player-battle-log:300}")
    private long playerBattleLogTtl;

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        ObjectMapper redisObjectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .activateDefaultTyping(
                        BasicPolymorphicTypeValidator.builder()
                                .allowIfBaseType(Object.class)
                                .build(),
                        ObjectMapper.DefaultTyping.EVERYTHING
                );

        GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer(redisObjectMapper);

        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(serializer))
                .disableCachingNullValues();

        Map<String, RedisCacheConfiguration> cacheConfigs = Map.of(
                "tierList",        defaultConfig.entryTtl(Duration.ofSeconds(tierListTtl)),
                "tierList_1",      defaultConfig.entryTtl(Duration.ofMinutes(10)),
                "tierList_3",      defaultConfig.entryTtl(Duration.ofMinutes(30)),
                "tierList_7",      defaultConfig.entryTtl(Duration.ofHours(1)),
                "tierList_30",     defaultConfig.entryTtl(Duration.ofHours(1)),
                "playerBattleLog", defaultConfig.entryTtl(Duration.ofSeconds(playerBattleLogTtl))
        );

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigs)
                .build();
    }
}
