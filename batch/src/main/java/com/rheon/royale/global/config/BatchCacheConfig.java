package com.rheon.royale.global.config;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Batch 모듈용 CacheManager — 캐시 쓰기/읽기 없이 eviction(clear)만 수행
 * API 서버와 동일한 Redis 인스턴스를 바라보므로 clear()가 API 캐시를 무효화한다.
 */
@Configuration
@EnableCaching
public class BatchCacheConfig {

    private static final List<String> EVICTABLE_CACHES = List.of(
            "tierList_1", "tierList_3", "tierList_7",
            "cardRanking_1", "cardRanking_3", "cardRanking_7",
            "cards"
    );

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .disableCachingNullValues();

        Map<String, RedisCacheConfiguration> configs = EVICTABLE_CACHES.stream()
                .collect(Collectors.toMap(name -> name, name -> base.entryTtl(Duration.ofHours(1))));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(base)
                .withInitialCacheConfigurations(configs)
                .build();
    }
}
