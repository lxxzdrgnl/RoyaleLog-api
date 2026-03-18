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
GET /api/v1/players/{tag}

Response:
{
  "success": true,
  "data": {
    "tag": "#ABC123",
    "name": "플레이어명",
    "trophies": 8500,
    "battleLogs": [ ... ]
  }
}
```
- Redis TTL: **5분** (외부 API 어뷰징 방지)

### 덱 메타/티어표 조회
```
GET /api/v1/stats/tier?season=2024-01

Response:
{
  "success": true,
  "data": [
    { "deckHash": "xxxx", "winRate": 0.58, "useRate": 0.12, "tier": "S" },
    ...
  ]
}
```
- Redis TTL: **1시간** (stats_daily 기반, 변동성 낮음)

### AI 승률 예측
```
GET /api/v1/predict/matchup?myDeck=card1,card2,...&opponentDeck=card1,card2,...

Response:
{
  "success": true,
  "data": {
    "winRate": 0.63,
    "confidence": 0.85,
    "source": "model"   // "model" | "stats_fallback"
  }
}
```
- FastAPI 장애 시 `source: "stats_fallback"` 으로 통계 기반 승률 반환 (Fallback)

---

## DB 스키마

Flyway V1 + V2 마이그레이션 기준. ELT 4단 분리 아키텍처.

```
Collector Job  →  battle_log_raw (raw_json JSONB 몰빵)
                        ↓
Analyzer Job   →  deck_dictionary / match_features / stats_decks_daily
```

### V1: 수집 레이어

| 테이블 | 역할 |
|--------|------|
| `battle_log_raw` | 원본 배틀 JSON (월별 파티션, 90일 보관) |
| `players_to_crawl` | 수집 대상 PoL 상위 1000명 |
| `cards` | 카드 메타 (이름, 희귀도, 타워여부) |

### V2: Analyzer 레이어

| 테이블 | 역할 |
|--------|------|
| `analyzer_meta` | 버전 관리 싱글톤 (`CHECK (id = 1)`) |
| `deck_dictionary` | deck_hash → card_ids TEXT 정규화 |
| `match_features` | ML Feature (월별 파티션, 90일 보관) |
| `stats_decks_daily` | 덱별 일일 집계 역사 보관 (파티션) |
| `stats_decks_daily_current` | API Serving 포인터 테이블 (Rename Swap 타겟) |
| `stats_current` | VIEW → `stats_decks_daily_current` |

#### 주요 설계 결정

```sql
-- battle_log_raw: 파티셔닝 키 + 분석 추적 컬럼
PRIMARY KEY (battle_id, created_at)       -- partition-aware dedup
INDEX (created_at, analyzer_version)      -- 조회 패턴 기준 (정렬 먼저, 필터 나중)

-- match_features: canonical dedup 키
PRIMARY KEY (battle_id, battle_date)      -- PostgreSQL partitioned table PK 요건
ON CONFLICT (battle_id, battle_date)      -- 앱 단에서 반드시 두 컬럼 모두 명시

-- deck_dictionary: card_ids 저장 방식
card_ids TEXT NOT NULL                    -- "id1-id2-id3" (숫자 오름차순, "-" 구분)
-- BIGINT[] 미사용: B-tree 인덱스 불가, equality 느림, 정렬 보장 추적 어려움

-- stats_decks_daily_current: Rename Swap (VIEW가 아닌 포인터 테이블)
-- VIEW는 swap 중 dirty read 가능 → RENAME = 카탈로그 레벨 원자 연산
```

---

## Spring Batch 파이프라인

```
[Airflow Trigger — 매일 새벽]
     │
     ▼
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
              Reader    : players_to_crawl (JdbcPagingItemReader)
              Processor : ClashRoyaleClient 호출, pathOfLegend 필터, battle_id 생성
              Writer    : battle_log_raw INSERT (ON CONFLICT DO NOTHING)
              FaultTolerant: Retry(3) + ExponentialBackOff / Skip(50)

[Airflow Trigger — Collector 완료 후]
     │
     ▼
AnalyzerJob (2 Steps)
  ├── Step 1: DeckAnalyzerStep (Chunk 50)
  │           Reader    : BattleLogRaw WHERE analyzerVersion < currentVersion (DB 조회)
  │           Processor : raw_json 파싱 → deck_hash(MD5) 생성 → AnalyzedBattle
  │           Writer    : deck_dictionary UPSERT + match_features UPSERT
  │                       + battle_log_raw.analyzer_version 갱신
  │
  └── Step 2: StatsOverwriteStep (Tasklet)
              match_features 전체 집계 → stats_new 생성
              Rename Swap: stats_decks_daily_current → stats_old → stats_new → current
              DROP stats_old
```

---

## Redis 캐싱 전략

```java
// 티어표: 1시간
@Cacheable(value = "tierList", key = "#season")

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
