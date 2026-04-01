# Collector Bracket-Aware Sampling & Early Stop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** players_to_crawl에 bracket 컬럼을 추가하고, salted hash 기반 브라켓별 균등 샘플링 + 목표 건수 달성 시 즉시 배치 종료를 구현한다.

**Architecture:** `players_to_crawl.bracket` 컬럼을 materialized 값으로 관리해 CASE 로직을 DB에서 제거. `BracketAwarePlayerReader`가 커서를 스트리밍하며 PoL 브라켓 full 체크 + 전체 브라켓 달성 시 early stop을 담당. `BracketBattleCounter`는 배틀 단위 카운팅의 source of truth로 유지.

**Tech Stack:** Spring Batch 5, JdbcCursorItemReader, SynchronizedItemStreamReader, PostgreSQL hashtext(), Flyway, JPA (validate 모드)

---

## 파일 구조

| 파일 | 변경 | 역할 |
|------|------|------|
| `api/src/main/resources/db/migration/V15__players_bracket.sql` | 신규 | bracket 컬럼 + 인덱스 + 백필 |
| `core/.../entity/PlayerToCrawl.java` | 수정 | bracket 필드 추가 |
| `core/.../repository/PlayerToCrawlRepository.java` | 수정 | bracket 포함 UPSERT/UPDATE, `findDistinctActiveBrackets()` 추가 |
| `batch/.../collector/BracketBattleCounter.java` | 수정 | `isAllBracketsFull()` 추가 |
| `batch/.../collector/BracketAwarePlayerReader.java` | 신규 | early stop + PoL pre-check wrapper |
| `batch/.../collector/CollectorJobConfig.java` | 수정 | 기존 Reader 빈 제거, BracketAwarePlayerReader 연결, job param 추가 |
| `batch/.../collector/CollectBattleLogProcessor.java` | 수정 | RETENTION_DAYS/PRUNING_DAYS 8일 |
| `batch/.../collector/SyncRankingTasklet.java` | 수정 | upsertRanked에 bracket 전달 |
| `batch/.../collector/CollectBattleLogWriter.java` | 수정 | UPSERT_OPPONENT_SQL에 bracket 추가 |
| `batch/src/main/resources/application.yml` | 수정 | 설정값 변경 |
| `batch/.../api/BatchController.java` | 수정 | batchSeq, hashK 파라미터 추가 |

---

## Task 1: Flyway V15 — bracket 컬럼 추가

**Files:**
- Create: `api/src/main/resources/db/migration/V15__players_bracket.sql`

- [ ] **Step 1: V15 마이그레이션 파일 작성**

```sql
-- V15__players_bracket.sql
ALTER TABLE players_to_crawl ADD COLUMN bracket VARCHAR(20);

CREATE INDEX idx_ptc_bracket_active
    ON players_to_crawl (bracket)
    WHERE is_active = true;

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

- [ ] **Step 2: :api 모듈 빌드로 Flyway 마이그레이션 실행**

```bash
cd /home/rheon/Desktop/Study/projects/Royale\ log/RoyaleLog-api
./gradlew :api:bootRun --args='--spring.batch.job.enabled=false' &
sleep 15 && kill %1
```

또는 직접 DB 확인:
```bash
docker exec royalelog-postgres psql -U royale -d royalelog -c "\d players_to_crawl" | grep bracket
docker exec royalelog-postgres psql -U royale -d royalelog -c "SELECT count(*) FROM players_to_crawl WHERE bracket IS NOT NULL;"
```

Expected: `bracket` 컬럼 존재, 백필된 행 수 > 0

- [ ] **Step 3: Commit**

```bash
git add api/src/main/resources/db/migration/V15__players_bracket.sql
git commit -m "feat: add bracket column to players_to_crawl (V15 migration)

- bracket VARCHAR(20) 컬럼 추가
- idx_ptc_bracket_active 인덱스 (is_active=true 필터)
- 기존 rows 백필 (current_trophies/league_number → arena_NN/pol_N)

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 2: PlayerToCrawl Entity — bracket 필드 추가

**Files:**
- Modify: `core/src/main/java/com/rheon/royale/domain/entity/PlayerToCrawl.java`

- [ ] **Step 1: bracket 필드 및 Builder 파라미터 추가**

`leagueNumber` 필드 뒤에 추가:

```java
/** 플레이어 브라켓 (arena_01~28 / pol_0~9 / unknown / null=미분류).
 *  BracketBattleCounter.toBracket() 기준. 쓰기 경로에서 항상 계산 후 저장. */
@Column(name = "bracket", length = 20)
private String bracket;
```

Builder 생성자도 업데이트:

```java
@Builder
public PlayerToCrawl(String playerTag, String name, Integer currentRank,
                     LocalDateTime lastCrawledAt, boolean isActive, int priority,
                     Integer currentTrophies, Integer leagueNumber,
                     String bracket,
                     LocalDateTime updatedAt) {
    this.playerTag = playerTag;
    this.name = name;
    this.currentRank = currentRank;
    this.lastCrawledAt = lastCrawledAt;
    this.isActive = isActive;
    this.priority = priority;
    this.currentTrophies = currentTrophies;
    this.leagueNumber = leagueNumber;
    this.bracket = bracket;
    this.updatedAt = updatedAt;
}
```

- [ ] **Step 2: :core 빌드 확인**

```bash
./gradlew :core:build -x test 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add core/src/main/java/com/rheon/royale/domain/entity/PlayerToCrawl.java
git commit -m "feat: add bracket field to PlayerToCrawl entity

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 3: PlayerToCrawlRepository — bracket 포함 쿼리 + findDistinctActiveBrackets

**Files:**
- Modify: `core/src/main/java/com/rheon/royale/domain/repository/PlayerToCrawlRepository.java`

- [ ] **Step 1: upsertRanked에 bracket 추가**

현재 `upsertRanked`를 다음으로 교체:

```java
/** 현재 랭킹 플레이어 UPSERT — is_active=true 로 활성화, league_number + bracket 갱신
 *  PoL API는 trophies를 주지 않으므로 current_trophies 는 건드리지 않음 */
@Modifying
@Query(value = """
        INSERT INTO players_to_crawl (player_tag, name, current_rank, league_number, bracket, is_active, updated_at)
        VALUES (:tag, :name, :rank, :leagueNumber, :bracket, TRUE, NOW())
        ON CONFLICT (player_tag) DO UPDATE SET
            name          = EXCLUDED.name,
            current_rank  = EXCLUDED.current_rank,
            league_number = COALESCE(EXCLUDED.league_number, players_to_crawl.league_number),
            bracket       = COALESCE(EXCLUDED.bracket, players_to_crawl.bracket),
            is_active     = TRUE,
            updated_at    = NOW()
        """, nativeQuery = true)
void upsertRanked(@Param("tag") String tag,
                  @Param("name") String name,
                  @Param("rank") int rank,
                  @Param("leagueNumber") Integer leagueNumber,
                  @Param("bracket") String bracket);
```

- [ ] **Step 2: updateAfterCrawl에 bracket 추가**

현재 `updateAfterCrawl`을 다음으로 교체:

```java
/** 배틀 수집 완료 시 last_crawled_at + 트로피/리그/브라켓 갱신
 *  COALESCE: null이면 기존 값 보존 (ladder ↔ PoL 전환 유저 대응) */
@Modifying
@Query(value = """
        UPDATE players_to_crawl SET
            last_crawled_at  = NOW(),
            current_trophies = COALESCE(:trophies, current_trophies),
            league_number    = COALESCE(:league, league_number),
            bracket          = COALESCE(:bracket, bracket),
            updated_at       = NOW()
        WHERE player_tag = :tag
        """, nativeQuery = true)
void updateAfterCrawl(@Param("tag") String tag,
                      @Param("trophies") Integer trophies,
                      @Param("league") Integer league,
                      @Param("bracket") String bracket);
```

- [ ] **Step 3: findDistinctActiveBrackets 추가**

```java
/** BracketAwarePlayerReader.open()에서 early-stop 기준 브라켓 목록 조회 */
@Query(value = """
        SELECT DISTINCT bracket
        FROM players_to_crawl
        WHERE is_active = true AND bracket IS NOT NULL
        """, nativeQuery = true)
List<String> findDistinctActiveBrackets();
```

import 추가: `import java.util.List;`

- [ ] **Step 4: :core 빌드 확인**

```bash
./gradlew :core:build -x test 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/com/rheon/royale/domain/repository/PlayerToCrawlRepository.java
git commit -m "feat: add bracket to PlayerToCrawlRepository write paths

- upsertRanked: bracket 파라미터 추가
- updateAfterCrawl: bracket 파라미터 추가 (COALESCE 보존)
- findDistinctActiveBrackets(): early-stop용 브라켓 목록 조회

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 4: SyncRankingTasklet — upsertRanked 호출 시 bracket 전달

**Files:**
- Modify: `batch/src/main/java/com/rheon/royale/batch/collector/SyncRankingTasklet.java`

- [ ] **Step 1: BracketBattleCounter 주입 + bracket 계산 후 upsertRanked 호출**

클래스에 `BracketBattleCounter` 의존성 추가 (Lombok `@RequiredArgsConstructor` 사용 중이므로 필드만 추가):

```java
private final ClashRoyaleClient clashRoyaleClient;
private final PlayerToCrawlRepository playerToCrawlRepository;
private final BracketBattleCounter bracketBattleCounter;  // 추가
```

`execute()` 내 루프 수정:

```java
for (CrRankingPlayer player : allPlayers) {
    // PoL 랭커는 leagueNumber가 항상 존재 → bracket = pol_N
    String bracket = BracketBattleCounter.toBracket(
            "pathOfLegend", player.leagueNumber(), null);
    playerToCrawlRepository.upsertRanked(
            player.tag(), player.name(), player.rank(),
            player.leagueNumber(), bracket);
}
```

- [ ] **Step 2: :batch 빌드 확인**

```bash
./gradlew :batch:build -x test 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add batch/src/main/java/com/rheon/royale/batch/collector/SyncRankingTasklet.java
git commit -m "feat: pass bracket to upsertRanked in SyncRankingTasklet

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 5: CollectBattleLogWriter — UPSERT_OPPONENT_SQL에 bracket 추가

**Files:**
- Modify: `batch/src/main/java/com/rheon/royale/batch/collector/CollectBattleLogWriter.java`

- [ ] **Step 1: UPSERT_OPPONENT_SQL에 bracket 컬럼 추가**

`UPSERT_OPPONENT_SQL` 상수 교체:

```java
private static final String UPSERT_OPPONENT_SQL = """
        INSERT INTO players_to_crawl (player_tag, name, is_active, current_trophies, league_number, bracket, updated_at)
        VALUES (?, ?, true, ?, ?, ?, NOW())
        ON CONFLICT (player_tag) DO UPDATE
            SET name             = EXCLUDED.name,
                current_trophies = COALESCE(EXCLUDED.current_trophies, players_to_crawl.current_trophies),
                league_number    = COALESCE(EXCLUDED.league_number,    players_to_crawl.league_number),
                bracket          = COALESCE(EXCLUDED.bracket,          players_to_crawl.bracket),
                updated_at       = NOW()
        WHERE players_to_crawl.name             IS DISTINCT FROM EXCLUDED.name
           OR players_to_crawl.current_trophies IS DISTINCT FROM EXCLUDED.current_trophies
           OR players_to_crawl.league_number    IS DISTINCT FROM EXCLUDED.league_number
        """;
```

- [ ] **Step 2: opponentArgs 매핑에 bracket 추가**

`DiscoveredOpponent` record에 bracket 계산 추가. `write()` 내 `opponentArgs` 빌드 부분 수정:

```java
List<Object[]> opponentArgs = chunkOpponents.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(e -> {
            DiscoveredOpponent opp = e.getValue();
            // 플레이어 브라켓: leagueNumber 있으면 pol_N, 없으면 trophies 기반
            String bracket = BracketBattleCounter.toBracket(
                    opp.leagueNumber() != null ? "pathOfLegend" : "PvP",
                    opp.leagueNumber(),
                    opp.trophies());
            return new Object[]{
                    e.getKey(),
                    opp.name(),
                    opp.trophies(),
                    opp.leagueNumber(),
                    bracket
            };
        })
        .toList();
```

- [ ] **Step 3: batchUpdate setter에 bracket(param 5) 추가**

```java
jdbcTemplate.batchUpdate(UPSERT_OPPONENT_SQL, sub, sub.size(),
        (ps, args) -> {
            ps.setString(1, (String) args[0]);
            ps.setString(2, (String) args[1]);
            if (args[2] != null) ps.setInt(3, (Integer) args[2]);
            else                 ps.setNull(3, java.sql.Types.INTEGER);
            if (args[3] != null) ps.setInt(4, (Integer) args[3]);
            else                 ps.setNull(4, java.sql.Types.INTEGER);
            if (args[4] != null) ps.setString(5, (String) args[4]);
            else                 ps.setNull(5, java.sql.Types.VARCHAR);
        });
```

- [ ] **Step 4: updateAfterCrawl 호출에 bracket 추가**

`write()` 내:

```java
playerToCrawlRepository.updateAfterCrawl(
        item.player().getPlayerTag(),
        item.currentTrophies(),
        item.leagueNumber(),
        item.bracket());   // 추가
```

`PlayerBattleLogs` record에 `bracket()` accessor가 없으면 Task 4 전에 추가 필요.
`PlayerBattleLogs.java`를 확인 후 없으면 `String bracket` 필드를 추가하고,
`CollectBattleLogProcessor`에서 bracket을 계산해 전달한다 (Task 6에서 처리).

- [ ] **Step 5: :batch 빌드 확인**

```bash
./gradlew :batch:build -x test 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add batch/src/main/java/com/rheon/royale/batch/collector/CollectBattleLogWriter.java
git commit -m "feat: add bracket to opponent UPSERT and updateAfterCrawl in Writer

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 6: CollectBattleLogProcessor — RETENTION/PRUNING 8일 + bracket 계산

**Files:**
- Modify: `batch/src/main/java/com/rheon/royale/batch/collector/CollectBattleLogProcessor.java`
- Modify: `batch/src/main/java/com/rheon/royale/batch/collector/dto/PlayerBattleLogs.java`

- [ ] **Step 1: PlayerBattleLogs에 bracket 필드 추가**

`PlayerBattleLogs.java`를 확인 후 bracket 필드가 없으면 record에 추가:

```java
// 기존 필드들 유지하고 bracket 추가
public record PlayerBattleLogs(
        PlayerToCrawl player,
        List<BattleLogRaw> battles,
        Map<String, DiscoveredOpponent> discoveredOpponents,
        Integer currentTrophies,
        Integer leagueNumber,
        String bracket          // 추가: 플레이어 브라켓
) {}
```

- [ ] **Step 2: Processor에서 RETENTION_DAYS/PRUNING_DAYS 변경 + bracket 계산**

상수 변경:
```java
private static final int RETENTION_DAYS = 8;  // 30 → 8
private static final int PRUNING_DAYS    = 8;  //  7 → 8
```

`process()` 메서드에서 return 문 수정. `currentTrophies`와 `currentLeague` 추출 후 bracket 계산 추가 (leagueNumber 있으면 `"pathOfLegend"`, 없으면 `"PvP"` 기준):

```java
// return 직전
String playerBracket = BracketBattleCounter.toBracket(
        currentLeague != null ? "pathOfLegend" : "PvP",
        currentLeague,
        currentTrophies);

return new PlayerBattleLogs(player, polBattles, discoveredOpponents,
        currentTrophies, currentLeague, playerBracket);
```

브라켓 한도 초과로 polBattles가 비어있는 경우 `updateAfterCrawl` 호출도 bracket 전달:
```java
playerToCrawlRepository.updateAfterCrawl(
        player.getPlayerTag(), currentTrophies, currentLeague, playerBracket);
```

- [ ] **Step 3: :batch 빌드 확인**

```bash
./gradlew :batch:build -x test 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add batch/src/main/java/com/rheon/royale/batch/collector/CollectBattleLogProcessor.java \
        batch/src/main/java/com/rheon/royale/batch/collector/dto/PlayerBattleLogs.java
git commit -m "feat: RETENTION/PRUNING 8일, bracket 계산 및 PlayerBattleLogs 전달

- RETENTION_DAYS: 30 → 8
- PRUNING_DAYS: 7 → 8
- Processor에서 playerBracket 계산 후 PlayerBattleLogs에 포함

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 7: BracketBattleCounter — isAllBracketsFull 추가

**Files:**
- Modify: `batch/src/main/java/com/rheon/royale/batch/collector/BracketBattleCounter.java`

- [ ] **Step 1: isAllBracketsFull 메서드 추가**

`isBracketFull()` 메서드 바로 아래에 추가:

```java
/**
 * early-stop 판단: knownBrackets 전부 한도에 도달했는지 확인.
 * 50 스레드 환경에서 매 read()마다 호출 — ConcurrentHashMap 조회만 하므로 ~수백 ns.
 */
public boolean isAllBracketsFull(Set<String> knownBrackets) {
    return !knownBrackets.isEmpty() && knownBrackets.stream().allMatch(this::isBracketFull);
}
```

import 추가: `import java.util.Set;`

- [ ] **Step 2: beforeStep 로그에 maxTrophyLow 포함**

```java
@Override
public void beforeStep(StepExecution stepExecution) {
    counts.clear();
    log.info("[BracketCounter] 초기화 — trophy-low {}건, trophy {}건, PoL {}건, special {}건",
            maxTrophyLow, maxTrophy, maxPol, maxSpecial);
}
```

- [ ] **Step 3: :batch 빌드 확인**

```bash
./gradlew :batch:build -x test 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add batch/src/main/java/com/rheon/royale/batch/collector/BracketBattleCounter.java
git commit -m "feat: add isAllBracketsFull to BracketBattleCounter

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 8: BracketAwarePlayerReader 신규 구현

**Files:**
- Create: `batch/src/main/java/com/rheon/royale/batch/collector/BracketAwarePlayerReader.java`

- [ ] **Step 1: BracketAwarePlayerReader 작성**

```java
package com.rheon.royale.batch.collector;

import com.rheon.royale.domain.entity.PlayerToCrawl;
import com.rheon.royale.domain.repository.PlayerToCrawlRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * JdbcCursorItemReader 래퍼.
 *
 * - open(): DB에서 활성 브라켓 목록 조회 → knownBrackets
 * - read(): cursor 스트리밍 중 두 가지 early-stop 조건 적용
 *   1. isAllBracketsFull(knownBrackets) → 모든 브라켓 달성 → null (Step 종료)
 *   2. PoL 플레이어의 pol_N 브라켓이 가득 찼으면 → skip (API 호출 절약)
 *      트로피 플레이어는 통과 → Processor에서 배틀별 브라켓 분류
 *      (한 플레이어가 PvP + trail 등 복수 브라켓에 기여 가능)
 *
 * SynchronizedItemStreamReader로 감싸서 AsyncItemProcessor 50 스레드 안전성 보장.
 */
@Slf4j
@Component
@Scope("step")   // @StepScope 대신 @Scope("step") 사용 — @Component와 함께 쓸 때 더 안전
public class BracketAwarePlayerReader implements ItemStreamReader<PlayerToCrawl> {

    private final JdbcCursorItemReader<PlayerToCrawl> delegate;
    private final BracketBattleCounter bracketBattleCounter;
    private final PlayerToCrawlRepository playerToCrawlRepository;

    private Set<String> knownBrackets = new HashSet<>();

    @Value("${collector.max-players-per-bracket:20000}")
    private int maxPlayersPerBracket;

    public BracketAwarePlayerReader(
            BracketBattleCounter bracketBattleCounter,
            PlayerToCrawlRepository playerToCrawlRepository,
            @Value("#{jobParameters['startTime']}") String startTime,
            @Value("#{jobParameters['batchSeq'] ?: '0'}") String batchSeq,
            @Value("#{jobParameters['hashK'] ?: '10'}") String hashKStr,
            javax.sql.DataSource dataSource) {

        this.bracketBattleCounter = bracketBattleCounter;
        this.playerToCrawlRepository = playerToCrawlRepository;

        LocalDateTime jobStartTime = LocalDateTime.parse(startTime);
        int hashK = Integer.parseInt(hashKStr);

        this.delegate = buildDelegate(dataSource, startTime, batchSeq, hashK, jobStartTime);
    }

    private JdbcCursorItemReader<PlayerToCrawl> buildDelegate(
            javax.sql.DataSource dataSource,
            String startTime, String batchSeq, int hashK,
            LocalDateTime jobStartTime) {

        org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder<PlayerToCrawl> builder =
                new org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder<>();

        builder.name("bracketAwarePlayerReader")
                .dataSource(dataSource)
                .sql("""
                        WITH sampled AS (
                            SELECT player_tag, name, current_rank, last_crawled_at,
                                   is_active, priority, current_trophies, league_number, bracket, updated_at,
                                   hashtext(player_tag) AS h1
                            FROM players_to_crawl
                            WHERE is_active = true
                              AND updated_at < ?
                              AND bracket IS NOT NULL
                              AND bracket <> 'unknown'
                              AND (? = 1 OR mod(abs(hashtext(player_tag || ? || ?)), ?) = 0)
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
                        WHERE rn <= ?
                        ORDER BY rn, bracket
                        """)
                .preparedStatementSetter(ps -> {
                    ps.setTimestamp(1, Timestamp.valueOf(jobStartTime));
                    ps.setInt(2, hashK);               // K=1 분기
                    ps.setString(3, startTime);        // mod 조건 h2 salt
                    ps.setString(4, batchSeq);         // mod 조건 h2 salt 보조
                    ps.setInt(5, hashK);               // mod 제수
                    ps.setInt(6, maxPlayersPerBracket);
                })
                .rowMapper((rs, rowNum) -> {
                    Timestamp lastCrawledAt = rs.getTimestamp("last_crawled_at");
                    return PlayerToCrawl.builder()
                            .playerTag(rs.getString("player_tag"))
                            .name(rs.getString("name"))
                            .currentRank(rs.getObject("current_rank", Integer.class))
                            .lastCrawledAt(lastCrawledAt != null ? lastCrawledAt.toLocalDateTime() : null)
                            .isActive(rs.getBoolean("is_active"))
                            .priority(rs.getInt("priority"))
                            .currentTrophies(rs.getObject("current_trophies", Integer.class))
                            .leagueNumber(rs.getObject("league_number", Integer.class))
                            .bracket(rs.getString("bracket"))
                            .updatedAt(rs.getTimestamp("updated_at").toLocalDateTime())
                            .build();
                });

        return builder.build();
    }

    @Override
    public void open(ExecutionContext executionContext) {
        delegate.open(executionContext);
        knownBrackets = new HashSet<>(playerToCrawlRepository.findDistinctActiveBrackets());
        knownBrackets.remove("unknown");
        knownBrackets.remove(null);
        log.info("[BracketAwareReader] 활성 브라켓 {}개: {}", knownBrackets.size(), knownBrackets);
    }

    @Override
    public PlayerToCrawl read() {
        while (true) {
            PlayerToCrawl player = delegate.read();
            if (player == null) {
                log.info("[BracketAwareReader] cursor 소진 → Step 종료");
                return null;
            }

            // early stop: 모든 브라켓 달성
            if (bracketBattleCounter.isAllBracketsFull(knownBrackets)) {
                log.info("[BracketAwareReader] 모든 브라켓 목표 달성 → early stop");
                return null;
            }

            // PoL 플레이어만 pre-check (플레이어 브라켓 = 배틀 브라켓 1:1)
            String bracket = player.getBracket();
            if (bracket != null && bracket.startsWith("pol_")
                    && bracketBattleCounter.isBracketFull(bracket)) {
                continue;
            }

            return player;
        }
    }

    @Override
    public void update(ExecutionContext executionContext) {
        delegate.update(executionContext);
    }

    @Override
    public void close() {
        delegate.close();
    }
}
```

- [ ] **Step 2: :batch 빌드 확인**

```bash
./gradlew :batch:build -x test 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`. 컴파일 오류 시 `javax.sql.DataSource` → `jakarta.sql.DataSource` 또는 `javax.sql.DataSource` import 확인.

- [ ] **Step 3: Commit**

```bash
git add batch/src/main/java/com/rheon/royale/batch/collector/BracketAwarePlayerReader.java
git commit -m "feat: add BracketAwarePlayerReader with early-stop and PoL pre-check

- JdbcCursorItemReader 래퍼로 salted hash 기반 Reader SQL 내장
- isAllBracketsFull() 체크로 모든 브라켓 달성 시 early stop
- PoL 플레이어만 pre-check, 트로피 플레이어는 Processor 위임

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 9: CollectorJobConfig — Reader 빈 교체 + job parameter 추가

**Files:**
- Modify: `batch/src/main/java/com/rheon/royale/batch/collector/CollectorJobConfig.java`

- [ ] **Step 1: 기존 Reader 빈 제거**

`CollectorJobConfig`에서 다음 메서드 전체 삭제:
- `synchronizedPlayerReader()`
- `randomPlayerReader()`
- `playerToCrawlRowMapper()` (private 메서드)
- `perBracketSize` 필드 및 `@Value("${collector.per-bracket-size:1000}")` 어노테이션

- [ ] **Step 2: BracketAwarePlayerReader 주입 + collectBattleLogStep 수정**

클래스 필드에 추가:

```java
private final BracketAwarePlayerReader bracketAwarePlayerReader;
```

`collectBattleLogStep` 빈 시그니처 변경 — `SynchronizedItemStreamReader` 파라미터 제거:

```java
@Bean
public Step collectBattleLogStep() {
    SynchronizedItemStreamReader<PlayerToCrawl> synchronizedReader =
            new SynchronizedItemStreamReader<>();
    synchronizedReader.setDelegate(bracketAwarePlayerReader);

    return new StepBuilder("collectBattleLogStep", jobRepository)
            .<PlayerToCrawl, Future<PlayerBattleLogs>>chunk(200, transactionManager)
            .reader(synchronizedReader)
            .processor(asyncProcessor())
            .writer(asyncWriter())
            .listener(bracketBattleCounter)
            .build();
}
```

`battleLogCollectorJob` 빈의 `.next(collectBattleLogStep)` 파라미터도 시그니처 변경에 맞게 `.next(collectBattleLogStep())` 으로 수정.

- [ ] **Step 3: :batch 빌드 확인**

```bash
./gradlew :batch:build -x test 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add batch/src/main/java/com/rheon/royale/batch/collector/CollectorJobConfig.java
git commit -m "feat: replace randomPlayerReader with BracketAwarePlayerReader in CollectorJobConfig

- randomPlayerReader / synchronizedPlayerReader 빈 제거
- BracketAwarePlayerReader를 SynchronizedItemStreamReader로 감싸서 등록
- perBracketSize 필드 제거

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 10: application.yml + BatchController 파라미터 추가

**Files:**
- Modify: `batch/src/main/resources/application.yml`
- Modify: `batch/src/main/java/com/rheon/royale/batch/api/BatchController.java`

- [ ] **Step 1: application.yml 설정값 변경**

`collector` 섹션 교체:

```yaml
collector:
  max-players-per-bracket: 20000
  bracket:
    max-trophy-low: 50000        # arena_01~10
    max-trophy: 100000           # arena_11~28
    max-pol: 100000
    max-special: 300000
```

(`per-bracket-size: 1000` 줄 삭제)

- [ ] **Step 2: BatchController — batchSeq, hashK 파라미터 추가**

`launch()` 내 `JobParametersBuilder` 빌드 부분에 추가:

```java
JobParametersBuilder builder = new JobParametersBuilder()
        .addString("date", resolvedDate)
        .addString("startTime", LocalDateTime.now().toString())
        .addString("batchSeq", "0")    // 추가: Airflow K-단계 전략용
        .addString("hashK", "10");     // 추가: 기본 10% 샘플링
```

`runCollector` 엔드포인트에 선택적 파라미터 추가:

```java
@PostMapping("/collector")
public ApiResponse<JobResult> runCollector(
        @RequestParam(required = false) String date,
        @RequestParam(defaultValue = "false") boolean force,
        @RequestParam(defaultValue = "0") String batchSeq,
        @RequestParam(defaultValue = "10") String hashK) throws Exception {
    return ApiResponse.ok(launch("battleLogCollectorJob", battleLogCollectorJob, date, force, batchSeq, hashK));
}
```

`launch()` 시그니처 및 내부도 수정:

```java
private JobResult launch(String jobName, Job job, String date, boolean force,
                         String batchSeq, String hashK) throws Exception {
    // ... 기존 코드 ...
    JobParametersBuilder builder = new JobParametersBuilder()
            .addString("date", resolvedDate)
            .addString("startTime", LocalDateTime.now().toString())
            .addString("batchSeq", batchSeq)
            .addString("hashK", hashK);
    // ... 나머지 동일 ...
}
```

`runAnalyzer`, `runCardSync`의 `launch()` 호출도 시그니처에 맞게 `"0"`, `"10"` 기본값 전달:
```java
return ApiResponse.ok(launch("deckAnalyzerJob", deckAnalyzerJob, date, force, "0", "10"));
return ApiResponse.ok(launch("cardSyncJob", cardSyncJob, date, force, "0", "10"));
```

- [ ] **Step 3: 전체 빌드 확인**

```bash
./gradlew build -x test 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add batch/src/main/resources/application.yml \
        batch/src/main/java/com/rheon/royale/batch/api/BatchController.java
git commit -m "feat: update application.yml and BatchController for bracket sampling params

- per-bracket-size → max-players-per-bracket: 20000
- max-trophy-low: 50000 추가 (arena_01~10)
- BatchController에 batchSeq, hashK 파라미터 추가 (기본값 0, 10)

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Task 11: 통합 검증

- [ ] **Step 1: 전체 빌드 최종 확인**

```bash
./gradlew build -x test 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 2: JPA validate 확인 — bracket 컬럼이 Entity와 일치하는지**

```bash
./gradlew :api:bootRun --args='--spring.batch.job.enabled=false' &
sleep 20
# 로그에서 HibernateException 없는지 확인
kill %1
```

Expected: `Started ApiApplication` 또는 Hibernate validate 오류 없음

- [ ] **Step 3: DB 상태 확인**

```bash
docker exec royalelog-postgres psql -U royale -d royalelog -c "
SELECT bracket, count(*)
FROM players_to_crawl
WHERE is_active = true
GROUP BY bracket
ORDER BY bracket;"
```

Expected: 각 bracket에 행 존재, `unknown` 포함, null 없음

- [ ] **Step 4: Final commit**

```bash
git add -A
git status  # 변경사항 없어야 함 (모든 변경은 이미 커밋됨)
```
