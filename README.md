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

## 배치 수집 성능

### Collector Job 멀티스레드 파이프라인

```
Reader (single-thread, SynchronizedItemStreamReader)
  → AsyncItemProcessor (20 threads) ─── CR API 호출 병렬화
  → AsyncItemWriter → CollectBattleLogWriter (single-thread write)
```

| 항목 | 측정값 |
|------|--------|
| CR API 평균 latency | ~390ms |
| Rate Limit (안전 마진) | 60 req/s (실측 burst ~95) |
| Thread Pool | 35 (`60 × 0.39s ≈ 24` + 여유 버퍼) |
| Chunk / PageSize | 100 / 100 |
| 실 처리량 | **~30 플레이어/초** |
| 이전 (chunk=10, single-thread throttle) | ~5 플레이어/초 |
| 개선 | **6배 향상** |

### Rate Limiting — Guava RateLimiter

기존 `synchronized + Thread.sleep()` 방식은 락을 보유한 채 sleep → 35개 async 스레드 전원 직렬 대기 (사실상 싱글스레드).

```java
// 수정 후: Guava RateLimiter
private RateLimiter rateLimiter = RateLimiter.create(60.0, 2, TimeUnit.SECONDS);
// acquire() — 슬롯 계산만 lock, sleep은 각 스레드가 독립 수행
```

`warmupPeriod=2s`로 초반 burst를 억제해 429 방지.

### Thread / Chunk 설계 공식

```
필요 thread 수 ≈ rateLimit(req/s) × API 응답시간(s)
예: 60 req/s × 0.39s ≈ 24 → 35 threads (여유 버퍼)

chunk 크기 > thread 수: 모든 스레드가 쉬지 않고 처리
→ chunk=100, pageSize=100: DB commit 횟수 ↓, write 병목 해소
```

스레드 수가 더 많아도 RateLimiter가 병목이므로 CPU 낭비 없음.

### GIN Deadlock 방지 (PostgreSQL)

`players_to_crawl.name`의 `gin_trgm_ops` 인덱스 — 대량 UPSERT 시 GIN pending list(기본 4MB) 초과 → mid-batch cleanup → deadlock.

```sql
ALTER DATABASE royalelog SET gin_pending_list_limit = 134217728;  -- 128MB
-- + IS DISTINCT FROM: 값이 같으면 GIN 인덱스 갱신 생략
```

---

## JDK 21 선택 이유

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
  └── Step 3: CollectBattleLogStep (Chunk 10)
              Reader    : players_to_crawl (is_active=true)
              Processor : ClashRoyaleClient 호출, pathOfLegend 필터, battle_id 생성
                          ※ opponentTag + opponentName 추출 (BFS 확장)
              Writer    : battle_log_raw INSERT (ON CONFLICT DO NOTHING)
                          + discoveredOpponents → players_to_crawl UPSERT (batchUpdate)
              FaultTolerant: Retry(3) + ExponentialBackOff / Skip(50)
              SkipListener: batch_skip_log DLQ 기록

AnalyzerJob (Collector 완료 후 실행)
  ├── Step 1: DeckAnalyzerStep (Chunk 50)
  │           Reader    : BattleLogRaw WHERE analyzerVersion < currentVersion
  │           Processor : raw_json 파싱 → deck_hash(MD5) 생성
  │           Writer    : deck_dictionary UPSERT + match_features UPSERT
  │
  └── Step 2: StatsOverwriteStep (Tasklet)
              stats_decks_daily 집계 → Rename Swap → stats_decks_daily_current
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
```
