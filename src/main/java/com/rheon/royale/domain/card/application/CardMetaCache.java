package com.rheon.royale.domain.card.application;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * cards 테이블 인메모리 캐시 — 배치 pruning 후 name/iconUrl 조회용
 *
 * 사용처:
 *   - OnDemandMatchService.parseCards(): batch 수집 데이터(pruned JSON)에서 name/iconUrl null 시 fallback
 *
 * 갱신: CardSyncJob 완료 후 refresh() 호출 권장 (현재는 재시작으로 대체)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CardMetaCache {

    private final JdbcTemplate jdbcTemplate;

    private volatile Map<Long, CardMeta> cache = Map.of();

    @PostConstruct
    public void init() {
        refresh();
    }

    public void refresh() {
        Map<Long, CardMeta> loaded = jdbcTemplate.query(
                "SELECT api_id, name FROM cards",
                (rs, n) -> new CardMeta(
                        rs.getLong("api_id"),
                        rs.getString("name")
                )
        ).stream().collect(Collectors.toMap(CardMeta::apiId, m -> m, (a, b) -> a));

        this.cache = loaded;
        log.info("[CardMetaCache] {}개 카드 로드 완료", loaded.size());
    }

    public CardMeta get(long apiId) {
        return cache.get(apiId);
    }

    public record CardMeta(long apiId, String name) {}
}
