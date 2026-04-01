# Collector Job — Bracket-Aware Sampling & Early Stop 설계

**날짜**: 2026-04-01
**범위**: `players_to_crawl` 스키마, `CollectorJobConfig`, `BracketAwarePlayerReader` (신규),
`BracketBattleCounter`, `CollectBattleLogProcessor`, 모든 쓰기 경로

---

## 목표

- 브라켓별 목표 건수 달성 시 즉시 배치 종료 (불필요한 API 호출 제거)
- `RANDOM()` 제거 → salted hash 기반 균등 샘플링 (정렬 비용 제거, 배치마다 다른 샘플)
- 수집 기간 30일 → 8일 (최근 활동 유저 중심)
- arena_01~10: 5만 건, arena_11~28 / PoL / special: 기존 유지

---

## 브라켓별 수집 상한

| 브라켓 | 상한 | 설정키 |
|--------|------|--------|
| arena_01 ~ arena_10 | 50,000건 | `collector.bracket.max-trophy-low` |
| arena_11 ~ arena_28 | 100,000건 | `collector.bracket.max-trophy` |
| pol_0 ~ pol_9 | 100,000건 | `collector.bracket.max-pol` |
| special (trail, riverRace 등) | 300,000건 | `collector.bracket.max-special` |
| trophy_unknown / friendly 등 | 0 (수집 안 함) | — |

---

## 핵심 설계 결정: `bracket` 컬럼 materialization

### 기존 접근의 문제

CASE 분기 로직이 세 곳에 중복:
1. Reader SQL (`WITH base AS (... CASE ...)`)
2. `knownBrackets` 쿼리 (early-stop용)
3. `BracketBattleCounter.toBracket()`

경계값이 바뀌면 세 곳을 모두 수정해야 하고, 불일치 시 early-stop이 잘못 동작함.

### 해결: `players_to_crawl.bracket` 컬럼 추가

- **쓰기 경로**: INSERT/UPDATE 시 `BracketBattleCounter.toBracket()`으로 계산 후 저장
- **읽기 경로**: `SELECT bracket FROM players_to_crawl` — CASE 없음
- **경계값 변경 시**: `BracketBattleCounter.toBracket()` 수정 + `UPDATE players_to_crawl SET bracket = ...` 한 번 실행
  - 어차피 경계값 변경 = `match_features`/`stats_decks_daily` 전체 재처리 필요 → 추가 비용 미미

### Flyway V15

```sql
ALTER TABLE players_to_crawl ADD COLUMN bracket VARCHAR(20);

CREATE INDEX idx_ptc_bracket_active
    ON players_to_crawl (bracket)
    WHERE is_active = true;

-- 기존 rows 백필
UPDATE players_to_crawl SET bracket =
    CASE
        WHEN league_number IS NOT NULL        THEN 'pol_' || league_number
        WHEN current_trophies IS NULL
          OR current_trophies <= 0            THEN 'unknown'
        WHEN current_trophies <   300         THEN 'arena_01'
        WHEN current_trophies <   600         THEN 'arena_02'
        WHEN current_trophies <  1000         THEN 'arena_03'
        WHEN current_trophies <  1300         THEN 'arena_04'
        WHEN current_trophies <  1600         THEN 'arena_05'
        WHEN current_trophies <  2000         THEN 'arena_06'
        WHEN current_trophies <  2300         THEN 'arena_07'
        WHEN current_trophies <  2600         THEN 'arena_08'
        WHEN current_trophies <  3000         THEN 'arena_09'
        WHEN current_trophies <  3400         THEN 'arena_10'
        WHEN current_trophies <  3800         THEN 'arena_11'
        WHEN current_trophies <  4200         THEN 'arena_12'
        WHEN current_trophies <  4600         THEN 'arena_13'
        WHEN current_trophies <  5000         THEN 'arena_14'
        WHEN current_trophies <  5500         THEN 'arena_15'
        WHEN current_trophies <  6000         THEN 'arena_16'
        WHEN current_trophies <  6500         THEN 'arena_17'
        WHEN current_trophies <  7000         THEN 'arena_18'
        WHEN current_trophies <  7500         THEN 'arena_19'
        WHEN current_trophies <  8000         THEN 'arena_20'
        WHEN current_trophies <  8500         THEN 'arena_21'
        WHEN current_trophies <  9000         THEN 'arena_22'
        WHEN current_trophies <  9500         THEN 'arena_23'
        WHEN current_trophies < 10000         THEN 'arena_24'
        WHEN current_trophies < 10500         THEN 'arena_25'
        WHEN current_trophies < 11000         THEN 'arena_26'
        WHEN current_trophies < 11500         THEN 'arena_27'
        ELSE                                      'arena_28'
    END;
```

### bracket 계산 책임

`BracketBattleCounter.toBracket(battleType, leagueNumber, startingTrophies)`가 유일한 source of truth.

단, `players_to_crawl.bracket`은 플레이어 프로필 기반 (trophies/league → arena/pol). 배틀 브라켓과 구분:

| 구분 | 기준 | 저장 위치 |
|------|------|-----------|
| 플레이어 브라켓 | `current_trophies` / `league_number` | `players_to_crawl.bracket` |
| 배틀 브라켓 | 배틀의 `battleType` + `startingTrophies` | BracketBattleCounter (메모리) |

---

## 쓰기 경로 변경 (bracket 갱신)

모든 UPSERT/UPDATE에서 `bracket` 컬럼을 함께 저장.

### PlayerToCrawlRepository

**upsertRanked()** — SyncRankingTasklet 호출:
```sql
INSERT INTO players_to_crawl (player_tag, name, current_rank, league_number, bracket, ...)
VALUES (?, ?, ?, ?, ?, ...)
ON CONFLICT (player_tag) DO UPDATE SET
    league_number = EXCLUDED.league_number,
    bracket       = EXCLUDED.bracket,
    updated_at    = NOW()
```

**upsertOpponent()** — CollectBattleLogWriter 호출:
```sql
INSERT INTO players_to_crawl (player_tag, name, current_trophies, league_number, bracket, ...)
VALUES (?, ?, ?, ?, ?, ...)
ON CONFLICT (player_tag) DO UPDATE SET
    current_trophies = COALESCE(EXCLUDED.current_trophies, players_to_crawl.current_trophies),
    league_number    = COALESCE(EXCLUDED.league_number,    players_to_crawl.league_number),
    bracket          = COALESCE(EXCLUDED.bracket,          players_to_crawl.bracket),
    updated_at       = NOW()
```

**updateAfterCrawl()** — 배틀 수집 완료 후 호출:
```sql
UPDATE players_to_crawl SET
    last_crawled_at  = NOW(),
    current_trophies = COALESCE(:trophies, current_trophies),
    league_number    = COALESCE(:league, league_number),
    bracket          = COALESCE(:bracket, bracket),
    updated_at       = NOW()
WHERE player_tag = :tag
```

bracket 파라미터는 Java 측에서 `BracketBattleCounter.toBracket()`으로 계산 후 전달.
trophies/league가 모두 null이면 bracket도 null → COALESCE로 기존 값 유지.

---

## Reader 설계 (bracket 컬럼 활용)

CASE 블록 제거. `bracket` 컬럼 직접 읽기.

### SQL

```sql
WITH sampled AS (
    SELECT player_tag, name, current_rank, last_crawled_at,
           is_active, priority, current_trophies, league_number, bracket, updated_at,
           hashtext(player_tag)           AS h1,
           hashtext(player_tag || ? || ?) AS h2    -- param 1: startTime, 2: batchSeq
    FROM players_to_crawl
    WHERE is_active = true
      AND updated_at < ?                           -- param 3: jobStartTime
      AND bracket IS NOT NULL
      AND bracket <> 'unknown'
      AND (? = 1 OR mod(abs(hashtext(player_tag || ? || ?)), ?) = 0)
      --   ^K=1    ^startTime  ^batchSeq                     ^K
),
ranked AS (
    SELECT *, ROW_NUMBER() OVER (
        PARTITION BY bracket
        ORDER BY h1
    ) AS rn
    FROM sampled
)
SELECT player_tag, name, current_rank, last_crawled_at,
       is_active, priority, current_trophies, league_number, bracket, updated_at
FROM ranked
WHERE rn <= ?                                      -- param 7: maxPlayersPerBracket
ORDER BY rn, bracket
```

**참고**: h2는 WHERE 조건에서 직접 계산 (CTE 분리 시 옵티마이저가 push-down 못 할 수 있음).
성능 테스트 후 필요하면 `base` CTE로 분리.

### PreparedStatement 파라미터 순서

| # | 값 | 설명 |
|---|-----|------|
| 1 | `startTime` (String) | h2 salt |
| 2 | `batchSeq` (String) | h2 salt 보조 |
| 3 | `jobStartTime` (Timestamp) | 스냅샷 기준 |
| 4 | `hashK` (int) | K=1 분기 조건 |
| 5 | `startTime` (String) | h2 재사용 (mod 조건) |
| 6 | `batchSeq` (String) | h2 재사용 (mod 조건) |
| 7 | `hashK` (int) | mod 제수 |
| 8 | `maxPlayersPerBracket` (int) | 브라켓당 처리 상한 |

```java
.preparedStatementSetter(ps -> {
    ps.setString(1, startTime);
    ps.setString(2, batchSeq);
    ps.setTimestamp(3, Timestamp.valueOf(jobStartTime));
    ps.setInt(4, hashK);                  // K=1 분기
    ps.setString(5, startTime);           // mod 조건용 h2 재계산
    ps.setString(6, batchSeq);
    ps.setInt(7, hashK);                  // mod 제수
    ps.setInt(8, maxPlayersPerBracket);
})
```

### 설계 근거

- CASE 블록 완전 제거 → bracket 컬럼 인덱스(`idx_ptc_bracket_active`) 활용
- `RANDOM()` 제거: hash filter O(N), 정렬 없음
- `hashtext(player_tag || salt || batchSeq)`: 배치마다 다른 샘플 → 전체 유저 커버
- `ORDER BY h1`: bias 없음, K=1 fallback 대응
- `ORDER BY rn, bracket`: 브라켓 간 라운드로빈 처리

---

## BracketAwarePlayerReader (신규 클래스)

`JdbcCursorItemReader<PlayerToCrawl>`를 delegate로 감싸는 `ItemStreamReader` wrapper.
`SynchronizedItemStreamReader` 안에 들어감.

**`@StepScope` 필수**: job parameter(`startTime`, `batchSeq`, `hashK`) 주입 필요.

**기존 빈 제거**: `CollectorJobConfig`의 `randomPlayerReader()`, `synchronizedPlayerReader()` 빈 삭제.
두 빈 공존 시 `NoUniqueBeanDefinitionException` 발생.

### open()

```java
void open(ExecutionContext ctx) {
    delegate.open(ctx);
    // bracket 컬럼에서 직접 조회 — CASE 없음
    knownBrackets = playerToCrawlRepository.findDistinctActiveBrackets()
        .stream()
        .filter(b -> b != null && !b.equals("unknown"))
        .collect(toSet());
}
```

`findDistinctActiveBrackets()`:
```sql
SELECT DISTINCT bracket
FROM players_to_crawl
WHERE is_active = true AND bracket IS NOT NULL
```
반환 타입: `List<String>`

### read()

```java
public PlayerToCrawl read() {
    while (true) {
        PlayerToCrawl player = delegate.read();
        if (player == null) return null;                           // cursor 소진

        if (bracketBattleCounter.isAllBracketsFull(knownBrackets)) return null; // early stop

        // PoL만 pre-check (플레이어 브라켓 = 배틀 브라켓 1:1)
        // 트로피 플레이어는 통과 → Processor에서 배틀별 분류
        // 이유: PvP(arena_N) 배틀과 special(trail 등) 배틀을 동시에 가질 수 있음
        String bracket = player.getBracket();
        if (bracket != null && bracket.startsWith("pol_")
                && bracketBattleCounter.isBracketFull(bracket)) {
            continue;
        }

        return player;
    }
}
```

---

## BracketBattleCounter 변경

### 추가: isAllBracketsFull()

```java
public boolean isAllBracketsFull(Set<String> knownBrackets) {
    return knownBrackets.stream().allMatch(this::isBracketFull);
}
```

### 기존 변경 (이미 적용됨)

- `maxTrophyLow` 필드 추가 (`@Value("${collector.bracket.max-trophy-low:50000}")`)
- `limitOf()` — arena_01~10은 `maxTrophyLow`, arena_11~28은 `maxTrophy`
- `isBracketFull()` 추가

---

## Processor 변경

```java
private static final int RETENTION_DAYS = 8;  // 30 → 8
private static final int PRUNING_DAYS    = 8;  //  7 → 8 (RETENTION_DAYS와 일치)
```

---

## PlayerToCrawl Entity 변경

```java
@Column(name = "bracket", length = 20)
private String bracket;
```

---

## 설정값 변경

```yaml
collector:
  max-players-per-bracket: 20000   # 기존 per-bracket-size(1000) 대체
  bracket:
    max-trophy-low: 50000          # arena_01~10 (신규)
    max-trophy: 100000             # arena_11~28
    max-pol: 100000
    max-special: 300000
```

---

## Job Parameters

| 파라미터 | 용도 | 기본값 |
|----------|------|--------|
| `startTime` | jobStartTime + hash salt 겸용 | 필수 |
| `batchSeq` | 같은 날 재실행 시 다른 샘플 | `"0"` |
| `hashK` | 샘플링 비율 (10=10%, 1=전체) | `"10"` |

---

## Airflow K-단계 전략

```
1단계: hashK=10, batchSeq=0
   → 완료 후 미달 브라켓 체크

2단계: hashK=4, batchSeq=1  (미달 브라켓 있을 때만)
   → 완료 후 미달 브라켓 체크

3단계: hashK=1, batchSeq=2  (fallback)
```

### 미달 브라켓 체크 SQL

`battle_log_raw`에 `bracket` 컬럼이 없으므로 CASE 직접 적용:

```sql
SELECT
    CASE
        WHEN battle_type = 'pathOfLegend'
            THEN 'pol_' || COALESCE((raw_json ->> 'leagueNumber'), 'unknown')
        WHEN battle_type IN ('trail','riverRacePvP','riverRaceDuel',
                             'riverRaceDuelColosseum','boatBattle','tournament','PvE')
            THEN 'special'
        WHEN battle_type IN ('friendly','clanMate','unknown')
            THEN 'trophy_unknown'
        WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies') IS NULL
          OR (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int <= 0
            THEN 'trophy_unknown'
        WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int <   300 THEN 'arena_01'
        WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int <   600 THEN 'arena_02'
        WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int <  1000 THEN 'arena_03'
        WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int <  1300 THEN 'arena_04'
        WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int <  1600 THEN 'arena_05'
        WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int <  2000 THEN 'arena_06'
        WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int <  2300 THEN 'arena_07'
        WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int <  2600 THEN 'arena_08'
        WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int <  3000 THEN 'arena_09'
        WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int <  3400 THEN 'arena_10'
        WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int <  3800 THEN 'arena_11'
        WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int <  4200 THEN 'arena_12'
        WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int <  4600 THEN 'arena_13'
        WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int <  5000 THEN 'arena_14'
        WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int <  5500 THEN 'arena_15'
        WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int <  6000 THEN 'arena_16'
        WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int <  6500 THEN 'arena_17'
        WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int <  7000 THEN 'arena_18'
        WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int <  7500 THEN 'arena_19'
        WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int <  8000 THEN 'arena_20'
        WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int <  8500 THEN 'arena_21'
        WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int <  9000 THEN 'arena_22'
        WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int <  9500 THEN 'arena_23'
        WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int < 10000 THEN 'arena_24'
        WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int < 10500 THEN 'arena_25'
        WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int < 11000 THEN 'arena_26'
        WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int < 11500 THEN 'arena_27'
        ELSE 'arena_28'
    END AS bracket,
    COUNT(*) AS cnt
FROM battle_log_raw
GROUP BY 1
ORDER BY 1
```

미달 조건: `cnt < target` (arena_01~10: 50,000 / arena_11~28 + PoL: 100,000 / special: 300,000)

---

## 변경 파일 목록

| 파일 | 변경 유형 |
|------|----------|
| Flyway V15 (api 모듈) | 신규 — `bracket` 컬럼 추가 + 인덱스 + 백필 |
| `PlayerToCrawl.java` | 수정 — `bracket` 필드 추가 |
| `PlayerToCrawlRepository.java` | 수정 — `upsertRanked`, `upsertOpponent`, `updateAfterCrawl` bracket 포함, `findDistinctActiveBrackets(): List<String>` 추가 |
| `BracketBattleCounter.java` | 수정 — `isAllBracketsFull()` 추가 (나머지는 이미 적용됨) |
| `BracketAwarePlayerReader.java` | 신규, `@StepScope` |
| `CollectorJobConfig.java` | 수정 — `randomPlayerReader()` / `synchronizedPlayerReader()` 제거, `BracketAwarePlayerReader` 연결, `@Value` 키 변경, `batchSeq`/`hashK` job param 추가 |
| `CollectBattleLogProcessor.java` | 수정 — `RETENTION_DAYS` 30→8, `PRUNING_DAYS` 7→8 |
| `SyncRankingTasklet.java` | 수정 — `upsertRanked` 호출 시 bracket 계산 후 전달 |
| `CollectBattleLogWriter.java` | 수정 — `upsertOpponent` 호출 시 bracket 전달 |
| `application.yml` (batch) | 수정 — `per-bracket-size` 제거, `max-players-per-bracket: 20000`, `max-trophy-low: 50000` 추가 |
| `BatchController.java` (api) | 수정 — job launch 시 `batchSeq`(기본 `"0"`), `hashK`(기본 `"10"`) 파라미터 추가 |
