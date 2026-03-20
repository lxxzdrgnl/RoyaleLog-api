# RoyaleLog-api

![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.5-6DB33F?style=flat-square&logo=springboot&logoColor=white)
![Java](https://img.shields.io/badge/Java-21-007396?style=flat-square&logo=openjdk&logoColor=white)
![Gradle](https://img.shields.io/badge/Gradle-8.x-02303A?style=flat-square&logo=gradle&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-4169E1?style=flat-square&logo=postgresql&logoColor=white)
![Redis](https://img.shields.io/badge/Redis-7.0-DC382D?style=flat-square&logo=redis&logoColor=white)
![Spring Batch](https://img.shields.io/badge/Spring%20Batch-Scheduler-6DB33F?style=flat-square&logo=spring&logoColor=white)
![MLflow](https://img.shields.io/badge/MLflow-Model%20Registry-0194E2?style=flat-square&logo=mlflow&logoColor=white)
![Prometheus](https://img.shields.io/badge/Prometheus-Metrics-E6522C?style=flat-square&logo=prometheus&logoColor=white)
![Docker](https://img.shields.io/badge/Docker-K3s-2496ED?style=flat-square&logo=docker&logoColor=white)
![Status](https://img.shields.io/badge/Status-Phase%201-yellow?style=flat-square)

---

Spring Boot 기반 메인 API 서버. 클라이언트 단일 진입점(BFF) 역할을 하며, 배틀 로그 수집(Spring Batch), 통계 조회, AI 승률 예측 위임을 담당한다.

### 데이터 수집 전략: On-Demand + Batch 하이브리드

```
[배치 (Airflow 트리거 — 매일 새벽)]
  PoL 상위 1000명 수집 → 배틀에서 만난 상대방(opponent)을 players_to_crawl에 자동 추가
  → 매 배치마다 풀이 BFS 방식으로 확장 (1일차 1천 → 2일차 1만 → 3일차 5만)

[온디맨드 (유저 검색 시 즉시)]
  └─ DB에 수집된 랭커  → battle_log_raw 기반 즉시 응답
  └─ DB에 없는 일반 유저 → CR API 실시간 호출 → 인메모리 분석 → 응답
  └─ 비활성 유저 (is_active=false) → 실시간 호출 + 재활성화

[향후 (Phase 2) — 유저 등급제]
  P1 (Active, 7일 이내)  → 매일 수집
  P2 (Normal, 8~30일)   → 3~7일 주기
  P3 (Dormant, 30일+)   → is_active=false, 검색 시 On-Demand 재활성화
```

---

## 기술 스택

| 분류 | 기술 |
|------|------|
| Language | Java 21 (LTS) |
| Framework | Spring Boot 3.2.5 |
| ORM | Spring Data JPA + PostgreSQL |
| Cache | Spring Data Redis |
| Batch | Spring Batch |
| Monitoring | Actuator + Micrometer(Prometheus) |
| Build | Gradle |

---
## 실행 방법

### 로컬 (Gradle)

> **사전 조건**: PostgreSQL 16, Redis 7 가 로컬에서 실행 중이어야 합니다.

```bash
# 1. 환경변수 파일 준비
cp .env.example .env
# .env 열어서 DB_PASSWORD, CLASH_API_TOKEN 채우기

# 2. 빌드
./gradlew build -x test

# 3. 실행 (local 프로파일 — ddl-auto: create-drop)
./gradlew bootRun
```

### Docker Compose (권장)

```bash
# 1. 환경변수 파일 준비
cp .env.example .env
# .env 열어서 DB_PASSWORD, CLASH_API_TOKEN 채우기

# 2. 빌드 + 전체 스택 실행 (PostgreSQL + Redis + API)
docker compose up --build

# 백그라운드 실행
docker compose up --build -d

# 로그 확인
docker compose logs -f api

# 종료
docker compose down
```

---

## 환경변수

`.env.example` 을 복사해 `.env` 로 사용합니다. `.env` 는 절대 커밋하지 않습니다.

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `DB_HOST` | `localhost` | PostgreSQL 호스트 |
| `DB_PORT` | `5432` | PostgreSQL 포트 |
| `DB_NAME` | `royalelog` | 데이터베이스명 |
| `DB_USER` | `royale` | DB 유저 |
| `DB_PASSWORD` | — | **필수** DB 비밀번호 |
| `REDIS_HOST` | `localhost` | Redis 호스트 |
| `REDIS_PORT` | `6379` | Redis 포트 |
| `CLASH_API_TOKEN` | — | **필수** 슈퍼셀 Developer API 토큰 |
| `ML_SERVER_URL` | `http://royalelog-worker:8000` | FastAPI Worker 주소 |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | CORS 허용 도메인 |

---

## 배포 주소

| 항목 | URL |
|------|-----|
| Base URL | `http://localhost:8080/api/v1` |
| Swagger UI | `http://localhost:8080/swagger-ui/index.html` |
| Health Check | `http://localhost:8080/health` |
| Prometheus Metrics | `http://localhost:8080/actuator/prometheus` |

---

## 패키지 구조

```
src/main/java/com/rheon/royale/
├── RoyaleLogApiApplication.java
│
├── global/                             # 공통 설정 (Security, Exception, Util, Config)
│   ├── config/
│   │   ├── RedisConfig.java            # Redis 연결 및 캐시 설정
│   │   ├── RestClientConfig.java       # WebClient / RestTemplate 빈 설정
│   │   └── SwaggerConfig.java          # Springdoc OpenAPI 설정
│   ├── error/
│   │   ├── GlobalExceptionHandler.java # @RestControllerAdvice
│   │   ├── BusinessException.java      # 도메인 예외 베이스
│   │   ├── ErrorCode.java              # 에러 코드 enum
│   │   └── ApiResponse.java            # 공통 응답 DTO { success, data, message }
│   └── util/
│       └── TagUtils.java               # 클래시로얄 태그 인코딩 (#ABC → %23ABC)
│
├── domain/                             # 핵심 비즈니스 로직
│   ├── card/                           # 카드 티어, 시너지
│   │   ├── api/
│   │   │   └── CardController.java     # GET /api/v1/cards/tier
│   │   ├── application/
│   │   │   └── CardService.java
│   │   ├── dao/
│   │   │   └── StatsDailyRepository.java
│   │   └── dto/
│   │       ├── CardTierResponse.java
│   │       └── CardMetaResponse.java
│   │
│   ├── match/                          # 매치 로그 수집 및 조회
│   │   ├── api/
│   │   │   └── MatchController.java    # GET /api/v1/matches/{playerTag}
│   │   ├── application/
│   │   │   └── MatchService.java
│   │   ├── dao/
│   │   │   └── BattleLogRawRepository.java
│   │   └── dto/
│   │       ├── MatchResponse.java
│   │       └── BattleLogResponse.java
│   │
│   └── prediction/                     # ML 모델 추론(Inference)
│       ├── api/
│       │   └── PredictionController.java  # GET /api/v1/predict/matchup
│       ├── application/
│       │   └── PredictionService.java     # mlserver 클라이언트 호출 + Fallback
│       └── dto/
│           ├── MatchupRequest.java
│           └── PredictionResponse.java    # source: "model" | "stats_fallback"
│
└── infrastructure/                     # 외부 시스템 연동
    └── external/
        ├── clashroyale/                # 공식 Clash Royale API 클라이언트
        │   ├── ClashRoyaleClient.java  # API 호출 (Throttling/Retry 포함)
        │   ├── ClashRoyaleProperties.java
        │   └── dto/                   # 슈퍼셀 API 응답 매핑 DTO
        │       ├── CrBattleResponse.java
        │       └── CrPlayerResponse.java
        └── mlserver/                  # FastAPI 서빙 서버 클라이언트
            ├── MlServerClient.java    # WebClient 기반 추론 요청
            ├── MlServerProperties.java
            └── dto/
                ├── MlPredictRequest.java
                └── MlPredictResponse.java
```

> **Spring Batch** (`BattleLogJob`, `ClashApiItemReader`, `BattleLogProcessor`, `BattleLogWriter`)는
> `infrastructure/external/clashroyale/` 클라이언트를 사용하는 별도 `batch/` 패키지로 분리 예정.

---

## API 명세

### 플레이어 전적 조회
```
GET /api/v1/matches/{playerTag}
```
- `#`은 `%23`으로 인코딩 (예: `%23ABC123`)
- DB에 없는 유저: CR API 실시간 호출 + 인메모리 분석 후 반환
- Redis TTL: **5분** (외부 API 어뷰징 방지)

### 닉네임으로 플레이어 검색
```
GET /api/v1/matches/search?name=닉네임
```
- `players_to_crawl` 기반: PoL 랭커 + BFS로 발견된 상대방 포함
- **최소 2글자** 필수 (1글자 검색은 DDoS 수준 DB 부하 유발)
- pg_trgm GIN 인덱스로 `ILIKE '%name%'` 고속 처리
- 최대 20건, Redis 캐싱 없음 (실시간)

### 덱 티어표 조회
```
GET /api/v1/cards/tier?battleType=pathOfLegend&days=7
```
- `days` 허용값: **1 / 3 / 7 / 30** (그 외는 7로 처리, 기본값: 7)
- 기간별 Redis TTL: 1일→10분, 3일→30분, 7일/30일→1시간

### AI 승률 예측 (Phase 2)
```
GET /api/v1/predict/matchup?myDeck=card1,card2,...&opponentDeck=card1,card2,...
```
- FastAPI 장애 시 `source: "stats_fallback"` 으로 통계 기반 승률 반환 (Fallback)

---

## DB 스키마

Flyway V1~V5 마이그레이션 기준. ELT 4단 분리 아키텍처.

```
Collector Job  →  battle_log_raw (raw_json JSONB 몰빵)
                        ↓
Analyzer Job   →  deck_dictionary / match_features / stats_decks_daily
                        ↓
              stats_decks_daily_current (Rename Swap → API serving)
```

### V1: 수집 레이어

| 테이블 | 역할 |
|--------|------|
| `battle_log_raw` | 원본 배틀 JSON (월별 파티션, 30일 보관) |
| `players_to_crawl` | 수집 대상: PoL 상위 1000명 + BFS로 발견된 상대방 |
| `cards` | 카드 메타 (이름, is_tower) |

### V2: Analyzer 레이어

| 테이블 | 역할 |
|--------|------|
| `analyzer_meta` | 버전 관리 싱글톤 (`CHECK (id = 1)`) |
| `deck_dictionary` | base_deck_hash → card_ids **BIGINT[]** (GIN 인덱스) |
| `match_features` | ML Feature (월별 파티션) |
| `stats_decks_daily` | 덱별 일일 집계 (날짜별 파티션) |
| `stats_decks_daily_current` | API Serving 포인터 테이블 (Rename Swap 타겟) |

### V3~V8: 확장

| 마이그레이션 | 내용 |
|-------------|------|
| V3 | 타워 카드 시드 4종 (`is_tower=true`) |
| V4 | `pg_trgm` 확장 + `players_to_crawl.name` GIN 인덱스 (닉네임 검색 고속화) |
| V5 | `batch_skip_log` DLQ 테이블 (배치 Skip 침묵 실패 방지) |
| V6 | `players_to_crawl.priority` 컬럼 (P1/P2/P3 등급제) |
| V7 | priority SMALLINT → INTEGER (Hibernate 타입 매핑 정합성) |
| V8 | 계층적 덱 해시: `refined_deck_hash` 추가, `card_ids TEXT→BIGINT[]` |

#### 덱 해시 계층 구조 (V8 기준)

```
base_deck_hash  = MD5(sorted card_ids)           ← 카드 구성만 (진화 무관)
                                                    deck_dictionary 키 / 유사덱 그룹핑 기준

refined_deck_hash = MD5(sorted "id:evoLevel")    ← 진화/히어로 포함 정밀 식별
                                                    stats_decks_daily 집계 기준 / ML 입력
```

예: 기사(evoLevel=0) vs 진화기사(evoLevel=1)
- base: 같은 해시 → "호그 덱 전체" 통계 가능
- refined: 다른 해시 → "진화기사 포함 여부"가 별도 승률 행으로 분리

#### 주요 설계 결정

```sql
-- battle_log_raw: 파티셔닝 키 + 분석 추적 컬럼
PRIMARY KEY (battle_id, created_at)       -- partition-aware dedup
INDEX (created_at, analyzer_version)      -- 조회 패턴 기준 (정렬 먼저, 필터 나중)

-- match_features: canonical dedup 키 + 두 해시
PRIMARY KEY (battle_id, battle_date)      -- PostgreSQL partitioned table PK 요건
deck_hash VARCHAR(32)                     -- base hash (카드 ID만)
refined_deck_hash VARCHAR(32)             -- refined hash (진화/히어로 포함)

-- deck_dictionary: BIGINT[] + GIN 인덱스 (V8에서 TEXT → BIGINT[] 변경)
card_ids BIGINT[] NOT NULL
INDEX USING GIN (card_ids)               -- @> (포함), && (교집합) 연산자 고속 지원
-- 사용 예: WHERE card_ids @> ARRAY[26000000]::bigint[] (특정 카드 포함 덱)

-- stats_decks_daily_current: Rename Swap (VIEW가 아닌 포인터 테이블)
-- VIEW는 swap 중 dirty read 가능 → RENAME = 카탈로그 레벨 원자 연산
-- deck_hash = refined_deck_hash (집계 기준)
-- base_deck_hash = 유사덱 그룹핑용 별도 컬럼
```

---

## Spring Batch 파이프라인

```
[Airflow Trigger — 수집 주기는 유저 등급에 따라 차등 (Phase 2 적용 예정)]
  현재: 매일 새벽 1회 (전체 is_active 유저)
  예정: P1(Active) → 1일 주기 / P2(Normal) → 3~7일 주기 / P3(Dormant) → 제외

CollectorJob (4 Steps)
  ├── Step 0: PartitionManagerTasklet
  │           - 당월 + 익월 파티션 생성 (IF NOT EXISTS)
  │           - 3개월 전 파티션 DROP
  │
  ├── Step 1: SeasonIdTasklet
  │           - GET /locations/global/seasons → currentSeasonId → JobExecutionContext
  │
  ├── Step 2: SyncRankingTasklet
  │           - GET /pathoflegend/{seasonId}/rankings → players_to_crawl UPSERT
  │
  └── Step 3: CollectBattleLogStep (Chunk 200)
              Reader    : players_to_crawl (is_active=true, updatedAt < jobStartTime)
              Processor : ClashRoyaleClient 호출, 모든 battleType 수집, battle_id 생성
                          ※ opponentTag + opponentName 추출 (BFS 확장)
                          ※ 7일 이내 전적 없음 → is_active=false (Pruning)
              Writer    : battle_log_raw INSERT (ON CONFLICT DO NOTHING)
                          + discoveredOpponents → players_to_crawl UPSERT (batchUpdate, tag 정렬)
              AsyncItemProcessor(50 threads) + Guava RateLimiter(120 req/s)
              SkipListener: batch_skip_log DLQ 기록

AnalyzerJob (Collector 완료 후 실행)
  ├── Step 1: DeckAnalyzerStep (Chunk 500, 4 threads)
  │           Reader    : JdbcCursorItemReader — battle_log_raw WHERE analyzer_version < current
  │                       ORDER BY created_at ASC, battle_id ASC (기존 인덱스 활용)
  │           Processor : raw_json 파싱 → deck_hash(MD5) 생성 (base + refined)
  │           Writer    : batchUpdate 3종 (deck_dictionary, match_features, markProcessed)
  │                       SET LOCAL synchronous_commit=off (WAL 동기화 비활성화)
  │
  └── Step 2: StatsOverwriteStep (Tasklet)
              최근 7일 match_features 재집계 → stats_new 생성
              인덱스 생성 (RENAME 전) → Rename Swap → stats_decks_daily_current
```

---

## Redis 캐싱 전략

```java
// 티어표: 기간별 TTL 차등
@Caching(cacheable = {
    @Cacheable(value = "tierList_1",  key = "#battleType", condition = "#days == 1"),   // 10분
    @Cacheable(value = "tierList_3",  key = "#battleType", condition = "#days == 3"),   // 30분
    @Cacheable(value = "tierList_7",  key = "#battleType", condition = "#days == 7"),   // 1시간
    @Cacheable(value = "tierList_30", key = "#battleType", condition = "#days == 30")   // 1시간
})

// 유저 전적: 5분
@Cacheable(value = "playerBattleLog", key = "#tag")
```

---

## Fallback 전략

```
PredictionService.getMatchupWinRate()
    │
    ├── [정상] MlServerClient → FastAPI /predict 호출 → ML 추론 결과 반환
    │         source = "model"
    │
    └── [장애] CircuitBreaker 발동 or Timeout(50ms 초과)
              → StatsDailyRepository에서 덱 해시 기반 통계 승률 조회
              → source = "stats_fallback"
```

---


## 성능 · 보안 고려사항

### 캐싱 (Redis)
| 캐시 키 | TTL | 이유 |
|---------|-----|------|
| `tierList` | 1시간 | 일일 집계 기반, 변동 낮음 |
| `playerBattleLog` | 5분 | 외부 API 어뷰징 방지 |

### 요청 크기 제한
- 최대 요청 크기: `10MB` (`spring.servlet.multipart.max-request-size`)
- 멀티파트 파일: `10MB`

### CORS
- `cors.allowed-origins` 프로퍼티로 도메인 화이트리스트 관리
- 로컬: `localhost:5173`, `localhost:3000` 허용
- 운영: `.env`의 `CORS_ALLOWED_ORIGINS`로 주입

### 에러 응답 포맷
모든 오류는 아래 통일된 JSON으로 반환됩니다.

```json
{
  "timestamp": "2026-03-18T12:00:00Z",
  "path": "/api/v1/players/%23ABC123",
  "status": 404,
  "code": "M001",
  "message": "플레이어를 찾을 수 없습니다.",
  "details": null
}
```

Validation 실패 시 `details` 에 필드별 오류가 포함됩니다.

```json
{
  "timestamp": "2026-03-18T12:00:00Z",
  "path": "/api/v1/players/search",
  "status": 400,
  "code": "C001",
  "message": "유효하지 않은 입력값입니다.",
  "details": {
    "tag": "태그 형식이 올바르지 않습니다.",
    "size": "최대 50까지 입력 가능합니다."
  }
}

---

## 배치 수집 성능

### Collector Job 멀티스레드 파이프라인

```
Reader (single-thread, SynchronizedItemStreamReader + jobStartTime 필터)
  → AsyncItemProcessor (50 threads) ─── CR API 호출 병렬화
  → AsyncItemWriter → CollectBattleLogWriter (single-thread write)
```

| 항목 | 측정값 |
|------|--------|
| CR API 평균 latency | ~390ms |
| Rate Limit | 120 req/s (실측 burst ~95, warmup 2s로 초반 억제) |
| Thread Pool | 50 (`120 × 0.39s ≈ 47` + 여유 버퍼) |
| Chunk / PageSize | 200 / 200 |
| 실 처리량 | **~57 플레이어/초** |
| 이전 (synchronized throttle, chunk=10) | ~5 플레이어/초 |
| 개선 | **11배 향상** |

### Rate Limiting — Guava RateLimiter

기존 `synchronized + Thread.sleep()` 방식은 락을 보유한 채 sleep → 50개 async 스레드 전원 직렬 대기 (사실상 싱글스레드).

```java
// 수정 후: Guava RateLimiter
private RateLimiter rateLimiter = RateLimiter.create(120.0, 2, TimeUnit.SECONDS);
// acquire() — 슬롯 계산만 lock, sleep은 각 스레드가 독립 수행
```

`warmupPeriod=2s`로 초반 burst를 억제해 429 방지.

### Thread / Chunk 설계 공식

```
필요 thread 수 ≈ rateLimit(req/s) × API 응답시간(s)
예: 120 req/s × 0.39s ≈ 47 → 50 threads (여유 버퍼)

chunk 크기 > thread 수: 모든 스레드가 쉬지 않고 처리
→ chunk=200, pageSize=200: DB commit 횟수 ↓, write 병목 해소
```

스레드 수가 더 많아도 RateLimiter가 병목이므로 CPU 낭비 없음.

### BFS 무한 루프 방지 — jobStartTime 필터

배치 중 발견된 상대방이 `players_to_crawl`에 추가되면 Reader가 이를 다시 읽어 무한 확장될 수 있다. Reader 쿼리에 `updatedAt < jobStartTime` 조건을 추가해, 배치 시작 이후 추가된 신규 유저는 현재 배치에서 제외한다.

```sql
WHERE p.isActive = true
AND (p.lastCrawledAt IS NOT NULL OR p.updatedAt < :jobStartTime)
ORDER BY p.lastCrawledAt ASC NULLS FIRST, p.currentRank ASC
```

### Analyzer Job 성능 최적화

| 항목 | 이전 | 이후 |
|------|------|------|
| Writer 방식 | 건건이 UPSERT (500건 → 1,500번 DB 왕복) | `batchUpdate` (3번 왕복) |
| WAL 동기화 | synchronous_commit=on | `SET LOCAL synchronous_commit=off` |
| 인덱스 메모리 | 기본값 | `maintenance_work_mem=1GB` |
| Reader | `JpaPagingItemReader` (OFFSET 기반, OOM) | `JdbcCursorItemReader` (스트리밍) |
| 실 처리량 | ~233건/초 | **~1,650건/초 (7배 향상)** |

**JdbcCursorItemReader 선택 이유:**
- `JpaPagingItemReader`: OFFSET 기반 → Writer가 `analyzer_version` 업데이트 시 결과셋이 밀려 데이터 절반 스킵 (Paging Skip 버그)
- `JpaCursorItemReader`: Hibernate 1차 캐시에 1,500만건 누적 → OOM
- `JdbcCursorItemReader`: ResultSet 스트리밍 → 메모리 일정 (fetchSize 단위 버퍼만 유지)

### GIN Deadlock 방지 (PostgreSQL)

`players_to_crawl.name`의 `gin_trgm_ops` 인덱스 — 대량 UPSERT 시 GIN pending list(기본 4MB) 초과 → mid-batch cleanup → deadlock.

```sql
ALTER DATABASE royalelog SET gin_pending_list_limit = 134217728;  -- 128MB
-- + IS DISTINCT FROM: 값이 같으면 GIN 인덱스 갱신 생략
```

---

## 성능 개선 요약 (수정 전 → 후)

| 항목 | 수정 전 | 수정 후 | 개선 |
|------|---------|---------|------|
| **Collector 처리량** | ~5 플레이어/초 | **~57 플레이어/초** | **11배** |
| Rate Limit 방식 | `synchronized + Thread.sleep()` (락 보유 중 sleep → 사실상 싱글스레드) | Guava `RateLimiter.acquire()` (락 외부 sleep, 스레드 독립) | — |
| Rate Limit 한도 | 30 req/s | 120 req/s | 4배 |
| Thread Pool | 20 threads (병렬 효과 없음) | 50 threads | — |
| Chunk / PageSize | 10 / 10 | 200 / 200 | DB commit 20배 감소 |
| **Analyzer Writer 방식** | for-loop UPSERT (chunk 500 → 1,500번 DB 왕복) | `batchUpdate` (3번 왕복) | **7배** |
| **Analyzer 처리량** | ~233건/초 | **~1,650건/초** | **7배** |
| WAL 동기화 | synchronous_commit=on (매 commit flush 대기) | `SET LOCAL synchronous_commit=off` (비동기 WAL) | — |
| Reader 방식 | JpaPagingItemReader (OFFSET → Paging Skip 버그, 데이터 50% 누락) | JdbcCursorItemReader (커서 스트리밍, OOM 없음) | 버그 제거 |
| 커서 오픈 시간 | ORDER BY battle_id (파티션 전체 정렬 → 3분+) | ORDER BY created_at, battle_id (기존 인덱스 활용 → 수초) | — |
| StatsOverwrite 인덱스 | RENAME 후 인덱스 생성 (swap 중 Full Scan) | RENAME 전 인덱스 완성 (항상 인덱스 있는 테이블 serving) | API 응답 안정 |
| 통계 집계 범위 | match_features 전체 (30일) | 최근 7일 필터 (파티션 프루닝) | I/O 대폭 감소 |

---

## 기술적 도전과 해결

### 1. 배틀 ID가 없다 — MD5로 멱등성 확보

슈퍼셀 API는 배틀 고유 ID를 제공하지 않는다. 상위권 유저끼리 매칭되므로 양쪽 플레이어 모두 수집 대상이 되어 동일 배틀이 중복 저장될 수 있다.

```java
// 두 태그를 정렬 후 조합 → 수집 방향에 무관하게 항상 동일 ID
String sortedTags = Stream.of(playerTag, opponentTag).sorted().reduce("", String::concat);
String battleId = md5(sortedTags + battleTime);  // ms precision 포함
```

`ON CONFLICT DO NOTHING`과 함께 완벽한 멱등성 보장. BFS 확장 이후 같은 배틀이 양쪽에서 수집될 확률이 높아졌지만 추가 처리 없이 자동 중복 제거.

---

### 2. 파티션 자동 관리 — Self-Managing Pipeline

월별 파티션이 없는 날짜에 INSERT하면 즉시 오류. Flyway로 관리하면 매달 수동 개입이 필요하다.

`PartitionManagerTasklet`을 Collector Job Step 0으로 배치:
- 당월 + 익월 파티션 `CREATE TABLE IF NOT EXISTS`
- 90일 이전 파티션 `DROP TABLE` → Dead Tuple 없는 무부하 삭제
- 외부 오케스트레이터 없이 배치가 스스로 인프라를 관리

---

### 3. Analyzer 건건이 UPSERT 병목 — batchUpdate로 7배 향상

| 방식 | chunk 500기준 DB 왕복 | 처리속도 |
|------|----------------------|---------|
| for loop UPSERT | 500 × 3 = **1,500번** | 233건/초 |
| `batchUpdate` | **3번** | **1,650건/초** |

Writer 내 3개 작업(deck_dictionary, match_features, markProcessed)을 모두 `jdbcTemplate.batchUpdate()`로 일괄 전송. `SET LOCAL synchronous_commit=off` + `maintenance_work_mem=1GB`로 추가 가속.

---

### 4. JpaPagingItemReader Paging Skip 버그 → JdbcCursorItemReader

`JpaPagingItemReader`는 OFFSET 기반 페이지네이션. Writer가 `analyzer_version`을 업데이트하면 결과셋이 변경되어 다음 페이지가 밀림 → 전체 데이터 절반 스킵.

`JpaCursorItemReader`로 교체 시도 → Hibernate 1차 캐시에 1,500만건 누적 → OOM.

최종 선택: **`JdbcCursorItemReader`** — DB 커서를 한 번 열고 ResultSet을 `fetchSize` 단위로 스트리밍. OFFSET 없음 + 메모리 일정 유지.

---

### 5. 덱 해시 계층 설계 — base + refined

진화기사와 일반기사는 같은 카드 ID. 단일 해시로 묶으면 승률이 희석된다.

| 해시 | 생성 방식 | 용도 |
|------|-----------|------|
| `base_deck_hash` | MD5(sorted card_ids) | 유사덱 그룹핑, deck_dictionary 키 |
| `refined_deck_hash` | MD5(sorted "id:evoLevel") | 진화/히어로 포함 정밀 승률 집계, ML 입력 |

`deck_dictionary.card_ids`는 `BIGINT[]` + GIN 인덱스 → `@>` (포함), `&&` (교집합) 연산자로 "특정 카드 포함 덱" 검색을 서브ms로 처리.

---

### 6. stats_decks_daily_current — VIEW가 아닌 포인터 테이블

VIEW는 underlying table을 참조해 swap 중 dirty read 가능. RENAME은 카탈로그 레벨 원자 연산이다.

```sql
BEGIN;
ALTER TABLE stats_decks_daily_current RENAME TO stats_old;  -- 격리
ALTER TABLE stats_new RENAME TO stats_decks_daily_current;  -- 서빙
COMMIT;
DROP TABLE stats_old;  -- COMMIT 이후 안전 삭제
```

API는 항상 완전한 스냅샷만 조회. 집계 중에도 응답 단절 없음.

---

### 7. GIN 인덱스 데드락 — IS DISTINCT FROM으로 불필요한 갱신 차단

`players_to_crawl.name`의 `gin_trgm_ops` GIN 인덱스 — 대량 UPSERT 시 pending list(4MB) 초과 → mid-batch GIN cleanup → 다른 트랜잭션 락 충돌 → 데드락.

```sql
ON CONFLICT (player_tag) DO UPDATE
    SET name = EXCLUDED.name
WHERE players_to_crawl.name IS DISTINCT FROM EXCLUDED.name  -- 같으면 GIN 갱신 안 함
```

`gin_pending_list_limit=128MB`로 추가 완화.

---

### 8. BFS 무한 루프 방지 — jobStartTime 스냅샷

배치 중 발견된 상대방이 `players_to_crawl`에 추가되면 Reader가 이를 읽어 무한 확장. `jobStartTime`을 파라미터로 주입해 배치 시작 이후 추가된 유저를 현재 배치에서 제외한다.

```sql
WHERE p.isActive = true
  AND (p.lastCrawledAt IS NOT NULL OR p.updatedAt < :jobStartTime)
```

---

### 핵심: Virtual Thread (가상 스레드)

이 프로젝트의 병목은 **I/O bound 작업**입니다.
- Spring Batch: 1,000명 × 슈퍼셀 API 호출 (HTTP + 대기)
- Redis 캐시 조회 / PostgreSQL 쿼리

| 항목 | JDK 17 (Platform Thread) | JDK 21 (Virtual Thread) |
|------|--------------------------|--------------------------|
| 스레드 생성 비용 | 높음 (OS 스레드 1:1 매핑) | 매우 낮음 (JVM 내부 스케줄링) |
| I/O blocking 시 | OS 스레드 점유 → 낭비 | carrier thread 반납 → 재사용 |
| 대량 동시 요청 | Thread Pool 튜닝 필수 | 수만 개 생성 가능, 자동 확장 |
| 배치 1,000 req | 스레드 부족 → 큐잉 발생 | 요청마다 Virtual Thread 할당 |

```
JDK 17: "스레드는 비싸다 → 아껴 써라" (Thread Pool 크기 제한)
JDK 21: "스레드는 싸다 → 많이 써라" (Virtual Thread 무제한에 가깝게)
```

Spring Boot에서는 설정 한 줄로 활성화됩니다.

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

### 추가 이유

| 기능 | 설명 |
|------|------|
| **Pattern Matching** (switch) | 타입 확인 + 캐스팅을 한 번에 → 조건 분기 코드 간결화 |
| **Structured Concurrency** | 비동기 작업 그룹화 — 한 작업 실패 시 전체 취소, 흐름 추적 용이 |
| **최신 LTS** | JDK 17 다음 LTS, 장기적으로 표준이 될 버전 |

> **Gradle 데몬**은 `~/.gradle/gradle.properties`의 `org.gradle.java.home`으로 Corretto 17을 사용합니다.
> 소스 컴파일 타깃은 `build.gradle`의 `toolchain { languageVersion = 21 }` 설정으로 분리되어 있습니다.
> 이 경로는 로컬 머신 종속이므로 프로젝트 `gradle.properties`에 포함하지 않습니다 (CI/CD 환경 오염 방지).


---

## 티어리스트 & 카드 순위 — 통계 방법론

### 랭킹 기법 비교

| 기법 | 원리 | CR 적합성 | 비고 |
|---|---|---|---|
| 단순 winRate | win/use | ❌ | 소표본 편향 심각 (10판 10승 = 1위) |
| Wilson Score | 95% CI 하한 | △ | 0% 방향으로 당김 → CR winRate 분포와 불일치 |
| Laplace Smoothing | (win+1)/(use+2) | △ | 단순하지만 통계적 근거 약함 |
| **Bayesian Average** | 전체 평균으로 수렴 | ✅ | CR 매치메이킹 특성에 최적 |

### 왜 Wilson이 아닌 Bayesian인가?

Wilson Score는 소표본을 **0% 방향**으로 당긴다. 그런데 CR 매치메이킹은 비슷한 실력끼리 매칭시키므로 모든 덱의 winRate가 ~50% 근방에 클러스터링된다. winRate 0%인 덱은 실질적으로 존재하지 않으므로 Wilson의 하향 보정 방향이 왜곡을 만든다.

Bayesian Average는 소표본을 **전체 평균(~50%)으로 수렴**시킨다.

```
score = (C × 0.5 + win_count) / (C + use_count) × 100
C = 500  (application.yml stats.bayes-prior-count)
```

| 상황 | 단순 winRate | Bayesian Score |
|---|---|---|
| 20판 80% 신규 덱 | 80.0% (1위 오염) | 51.4% (신중하게 평가) |
| 300판 60% 덱 | 60.0% | 54.4% |
| 5,000판 58% 메타 덱 | 58.0% | 57.8% (신뢰 반영) |

C=500이 넘으면 실제 winRate에 수렴하므로, 충분히 검증된 덱은 그대로 반영된다.

### 카드 순위 SQL 구조

카드 순위는 `deck_dictionary.card_ids`를 unnest해 덱 단위 통계를 카드 단위로 분해한다. **반드시 unnest를 subquery로 먼저 실행한 뒤 tower 카드 JOIN**해야 한다. 직접 JOIN하면 덱당 8개 카드가 cross-product로 팽창해 use_count가 8배 부풀어오른다.

```sql
SELECT card_id, SUM(win_count), SUM(use_count), ...
FROM (
    SELECT unnest(dd.card_ids) AS card_id, s.win_count, s.use_count
    FROM stats_decks_daily_current s
    JOIN deck_dictionary dd ON s.deck_hash = dd.deck_hash
    ...
) sub
JOIN cards c ON c.api_id = sub.card_id AND c.is_tower = false
GROUP BY card_id
```

---

## 모듈 구조 (Gradle Multi-Project)

### 왜 단일 모듈이 아닌 3-submodule 구조인가?

Phase 1까지는 단일 JAR으로 api와 batch를 함께 실행했다. K3s 배포 단계에서 두 가지 문제가 생겼다.

**문제 1 — 독립 배포 불가**
api 코드 1줄 수정해도 batch까지 재배포 → Batch Job이 재시작되면 진행 중이던 수집이 중단된다.

**문제 2 — cross-layer 의존성**
`OnDemandMatchService`(api 레이어)가 `DeckAnalyzerWriter`(batch 레이어)를 직접 `@Autowired`했다.
멀티모듈로 분리하면 이 참조가 컴파일 에러가 되므로 구조적으로 막아야 했다.

### 분리 결과

```
:core   ← 공유 라이브러리 (plain JAR, no main)
          Entity, Repository, ClashRoyaleClient, AnalyzerPersistenceService 등

:api    ← REST API 서버 (port 8080)
          implementation project(':core')
          Flyway 적용, Redis, Swagger
          bootJar → royalelog-api.jar

:batch  ← Batch Job 서버 (port 8081)
          implementation project(':core')
          Spring Batch, HTTP 트리거 엔드포인트
          bootJar → royalelog-batch.jar
```

**의존 규칙**: `:api ⇎ :batch` — 두 모듈은 서로 임포트하지 않는다. DB를 통해서만 통신.

### cross-layer 의존성 해결 — `AnalyzerPersistenceService`

`DeckAnalyzerWriter`의 SQL 로직(deck_dictionary UPSERT, match_features UPSERT, battle_log_raw 갱신)을 `:core`에 `AnalyzerPersistenceService`로 추출했다.

```
Before:
  OnDemandMatchService (api) ──→ DeckAnalyzerWriter (batch)   ← 순환 위험

After:
  OnDemandMatchService (api)  ──→ AnalyzerPersistenceService (core)
  DeckAnalyzerWriter   (batch) ──→ AnalyzerPersistenceService (core)
```

`DeckAnalyzerWriter`는 배치 전용 최적화(`SET LOCAL synchronous_commit = off`)만 추가하고 나머지는 위임한다.

### Flyway는 `:api`에만

두 서비스가 동시에 Flyway를 실행하면 `flyway_schema_history` lock 충돌이 발생한다.
`:api`가 먼저 스키마를 최신화하고, `:batch`는 `flyway.enabled=false`로 시작한다.
K3s에서 api → batch `initContainer` 순서로 배포 순서를 보장한다.

### `java-library` 플러그인 (`core/build.gradle`)

`:core`에서 JPA, WebFlux 등을 `api` scope으로 선언하면 `:api`, `:batch`가 `:core`만 의존해도 transitive dependency를 직접 임포트할 수 있다. `java` 플러그인의 `implementation`은 transitive를 외부에 노출하지 않으므로 불가능하다.

```groovy
// core/build.gradle
apply plugin: 'java-library'
dependencies {
    api 'org.springframework.boot:spring-boot-starter-data-jpa'   // transitive 노출
    api 'org.springframework.boot:spring-boot-starter-webflux'
    api 'org.springframework.boot:spring-boot-starter-jdbc'
}
```
