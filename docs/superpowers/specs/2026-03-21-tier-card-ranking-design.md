# 티어리스트 & 카드 순위 서비스 설계

**날짜**: 2026-03-21
**범위**: RoyaleLog-api (`:api`, `:batch`) + RoyaleLog-front

---

## 1. 목표 및 범위

### 구현 대상
- **티어리스트 강화**: `GET /api/v1/cards/tier` 응답에 `cardIds`, `score` 추가
- **카드 순위**: `GET /api/v1/cards/ranking` 신규
- **카드 메타 API**: `GET /api/v1/cards` 신규 (id→name 매핑용, 24hr 캐시)
- **캐시 전략**: 배치 완료 시 CacheManager 명시적 eviction
- **프론트엔드**: `/tier` 라우트, 덱 순위·카드 순위 뷰

### 제외 (추후 ML 모델 도입 시)
- 추천덱 (Phase 2)

---

## 2. 통계 방법론 — Bayesian Average

### 선택 근거
CR 매치메이킹 특성상 전체 winRate가 ~50%에 수렴한다. Wilson Score는 0% 방향으로 소표본을 당기는데 CR에서 winRate 0%인 덱은 존재하지 않으므로 왜곡이 발생한다. Bayesian Average는 **전체 평균(~50%)으로 수렴**시켜 CR 메타에 더 적합하다.

### 공식

```
score = (C × prior + win_count) / (C + use_count) × 100

C     = 500   (application.yml stats.bayes-prior-count 로 주입, @Value)
prior = 0.5   (CR 매치메이킹 특성)
```

`C`는 `@Value("${stats.bayes-prior-count:500}")`로 `StatsDailyRepository`에 주입한다. SQL 문자열은 빌드 시점에 포맷팅되므로 YAML 수정 → 재시작으로 튜닝 가능.

### 샘플 비교 (C=500)

| 덱 | use_count | winRate | score |
|---|---|---|---|
| 소표본 | 20판 | 80% | 51.4% |
| 중간 | 300판 | 60% | 54.4% |
| 메타 | 5,000판 | 58% | 57.8% |

### 최소 표본 필터
`HAVING SUM(use_count) >= 20` — Bayesian 보정과 병행 사용.

---

## 3. DB 스키마 참고

### cards 테이블
```
card_key   VARCHAR(50) PK  -- "{api_id}_{card_type}" e.g. "26000000_NORMAL"
api_id     BIGINT NOT NULL  -- CR API 카드 ID (deck_dictionary.card_ids 와 매핑)
name       VARCHAR(100)
elixir_cost INT
rarity     card_rarity_enum
icon_url   TEXT
is_tower   BOOLEAN
is_deck_card BOOLEAN
```

`deck_dictionary.card_ids BIGINT[]`는 `api_id` 값 배열이다. 따라서 카드 JOIN 시 반드시 `c.api_id = ANY(dd.card_ids)` 를 사용한다.

### stats_current VIEW
`stats_current`는 `stats_decks_daily_current`의 5개 컬럼 추상화 뷰다.
카드 ID가 필요한 쿼리는 `deck_dictionary` JOIN이 필요하므로 **`stats_decks_daily_current` 직접 조회**한다. `stats_current`는 기존 단순 집계 쿼리에만 사용한다.

---

## 4. 백엔드 설계

### 4-1. 티어리스트 강화 (`GET /api/v1/cards/tier`)

**SQL** — `stats_decks_daily_current` + `deck_dictionary` JOIN:

```sql
SELECT s.deck_hash,
       s.battle_type,
       SUM(s.win_count)::int                                              AS win_count,
       SUM(s.use_count)::int                                              AS use_count,
       ROUND(SUM(s.win_count)::numeric
             / NULLIF(SUM(s.use_count), 0) * 100, 1)                    AS win_rate,
       ROUND((:c * 0.5 + SUM(s.win_count))
             / (:c + SUM(s.use_count)::numeric) * 100, 1)               AS score,
       MAX(dd.card_ids)                                                   AS card_ids
FROM stats_decks_daily_current s
JOIN deck_dictionary dd ON s.deck_hash = dd.deck_hash
WHERE s.battle_type = ?
  AND s.stat_date >= CURRENT_DATE - (? * INTERVAL '1 day')
GROUP BY s.deck_hash, s.battle_type
HAVING SUM(s.use_count) >= 20
ORDER BY score DESC NULLS LAST
LIMIT ?
```

> `MAX(dd.card_ids)` — `deck_hash → card_ids`는 함수적 종속 관계이므로 MAX는 안전한 no-op aggregation이다.

**DTO 변경**:

```java
public record DeckStatsResponse(
    String     deckHash,
    String     battleType,
    int        winCount,
    int        useCount,
    BigDecimal winRate,    // 실제 승률 (표시용)
    BigDecimal score,      // Bayesian score (정렬 기준)
    long[]     cardIds     // api_id 배열 (Long[] → primitive long[])
) {}
```

**Redis TTL (기존 유지)**:

| days | cache name | TTL |
|---|---|---|
| 1 | tierList_1 | 10분 |
| 3 | tierList_3 | 30분 |
| 7 | tierList_7 | 1시간 |
| 30 | tierList_30 | 1시간 |

---

### 4-2. 카드 순위 (`GET /api/v1/cards/ranking`)

**SQL** — unnest 먼저, tower 필터는 subquery 밖에서 JOIN:

```sql
SELECT sub.card_id,
       SUM(sub.win_count)::int                                            AS win_count,
       SUM(sub.use_count)::int                                            AS use_count,
       ROUND(SUM(sub.win_count)::numeric
             / NULLIF(SUM(sub.use_count), 0) * 100, 1)                  AS win_rate,
       ROUND((:c * 0.5 + SUM(sub.win_count))
             / (:c + SUM(sub.use_count)::numeric) * 100, 1)             AS score
FROM (
    SELECT unnest(dd.card_ids) AS card_id,
           s.win_count,
           s.use_count
    FROM stats_decks_daily_current s
    JOIN deck_dictionary dd ON s.deck_hash = dd.deck_hash
    WHERE s.battle_type = ?
      AND s.stat_date >= CURRENT_DATE - (? * INTERVAL '1 day')
) sub
JOIN cards c ON c.api_id = sub.card_id AND c.is_tower = false
GROUP BY sub.card_id
HAVING SUM(sub.use_count) >= 20
ORDER BY score DESC NULLS LAST
LIMIT 100
```

> unnest를 subquery로 분리해야 `JOIN cards` 가 각 카드 행에 1:1 매핑되어 use_count 중복 집계(8x overcount)를 방지한다.

**DTO**:

```java
public record CardRankingResponse(
    long       cardId,    // api_id
    int        winCount,
    int        useCount,
    BigDecimal winRate,
    BigDecimal score
) {}
```

**Redis TTL**: tier list와 동일 (days 파라미터 연동, `cardRanking_1/3/7/30` 캐시명)

---

### 4-3. 카드 메타 (`GET /api/v1/cards`)

**SQL**:
```sql
SELECT api_id AS id, name, elixir_cost, rarity, icon_url, is_tower
FROM cards
ORDER BY api_id
```

**DTO**:

```java
public record CardMetaResponse(
    long    id,          // api_id
    String  name,
    int     elixirCost,
    String  rarity,
    String  iconUrl,     // CDN URL (CardSyncTasklet이 채움)
    boolean isTower
) {}
```

**Redis TTL**: 24시간 (`cards` 캐시명)

---

### 4-4. Redis Cache Eviction (배치 완료 시)

`redisTemplate.keys()` 는 O(N) blocking → 사용 금지.
대신 `RedisConfig`에 등록된 **명시적 캐시명으로 eviction**:

**StatsOverwriteTasklet** — Rename Swap COMMIT 직후:
```java
for (String name : List.of("tierList_1","tierList_3","tierList_7","tierList_30",
                            "cardRanking_1","cardRanking_3","cardRanking_7","cardRanking_30")) {
    Cache cache = cacheManager.getCache(name);
    if (cache != null) cache.clear();
}
```

**CardSyncTasklet** — Sync 완료 직후:
```java
Cache cache = cacheManager.getCache("cards");
if (cache != null) cache.clear();
```

---

### 4-5. RedisConfig 추가 캐시명

```java
"cardRanking_1"  → TTL 10분
"cardRanking_3"  → TTL 30분
"cardRanking_7"  → TTL 1시간
"cardRanking_30" → TTL 1시간
"cards"          → TTL 24시간
```

---

### 4-6. application.yml 추가

```yaml
# api/src/main/resources/application.yml
stats:
  bayes-prior-count: 500   # Bayesian C 파라미터 (@Value로 주입, 재시작으로 튜닝)
```

---

## 5. 프론트엔드 설계

### 5-1. 카드 메타 Store (Pinia)

```typescript
// src/stores/cardMeta.ts
// 앱 로드 시 GET /api/v1/cards 1회 호출
// Map<id, CardMetaResponse> 로 메모리 캐시
// cardMetaStore.get(id) → { name, iconUrl, elixirCost, rarity }
```

`App.vue onMounted`에서 `cardMetaStore.init()` 호출.

### 5-2. API 클라이언트 (`src/api/tier.ts`)

```typescript
fetchTierList(battleType: string, days: number): Promise<DeckStatsResponse[]>
fetchCardRanking(battleType: string, days: number): Promise<CardRankingResponse[]>
fetchCardMeta(): Promise<CardMetaResponse[]>
```

### 5-3. 라우터

```typescript
{ path: '/tier', component: () => import('@/views/TierView.vue') }
```

### 5-4. TierView.vue 레이아웃

```
┌─ 헤더 ──────────────────────────────────────────┐
│  battleType: [pathOfLegend ▾]  days: [7 ▾]      │
│  탭: [덱 순위] [카드 순위]                        │
└──────────────────────────────────────────────────┘

[덱 순위 탭]
  순위 | 카드 8장 이미지 | winRate% | score | 사용수

[카드 순위 탭]
  순위 | 카드 이미지 | 이름 | 엘릭서 | winRate% | score | 사용수
```

### 5-5. 신규 컴포넌트

| 파일 | 역할 |
|---|---|
| `stores/cardMeta.ts` | Pinia store — 카드 메타 캐시 |
| `api/tier.ts` | API 클라이언트 |
| `views/TierView.vue` | 탭 뷰 (덱 순위 / 카드 순위) |
| `components/DeckRow.vue` | 덱 1행 — 카드 8장 + score + 사용수 |
| `components/CardRankRow.vue` | 카드 1행 — 이미지 + 이름 + score + 사용수 |

---

## 6. 수정 파일 목록

### `:api`
- `domain/card/dto/DeckStatsResponse.java` — `cardIds: long[]`, `score` 추가
- `domain/card/dto/CardRankingResponse.java` — 신규
- `domain/card/dto/CardMetaResponse.java` — 신규
- `domain/card/dao/StatsDailyRepository.java` — JOIN + Bayesian SQL, ranking/meta 메서드 추가
- `domain/card/application/CardService.java` — ranking, meta 메서드 추가
- `domain/card/api/CardController.java` — `/ranking`, `/` 엔드포인트 추가
- `global/config/RedisConfig.java` — cardRanking_*, cards 캐시 추가
- `src/main/resources/application.yml` — `stats.bayes-prior-count: 500`

### `:batch`
- `batch/analyzer/StatsOverwriteTasklet.java` — CacheManager eviction 추가
- `batch/card/CardSyncTasklet.java` — cards 캐시 eviction 추가

### `RoyaleLog-front`
- `src/stores/cardMeta.ts` — 신규
- `src/api/tier.ts` — 신규
- `src/views/TierView.vue` — 신규
- `src/components/DeckRow.vue` — 신규
- `src/components/CardRankRow.vue` — 신규
- `src/router/index.ts` — `/tier` 추가
- `src/App.vue` — cardMeta init 추가

---

## 7. 비기능 요건

| 항목 | 목표 |
|---|---|
| 티어리스트 / 카드 순위 응답 | P95 < 50ms (Redis hit) |
| DB Cold start | < 500ms (Redis miss 시 JOIN 쿼리) |
| 캐시 정합성 | 배치 완료 후 최초 요청 시 신규 데이터 반환 보장 |
