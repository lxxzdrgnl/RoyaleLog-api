# RoyaleLog-api

![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.5-6DB33F?style=flat-square&logo=springboot&logoColor=white)
![Java](https://img.shields.io/badge/Java-17-007396?style=flat-square&logo=openjdk&logoColor=white)
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
| Language | Java 17 |
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

### battle_log_raw
```sql
CREATE TABLE battle_log_raw (
    id              BIGSERIAL PRIMARY KEY,
    battle_id       VARCHAR(64) UNIQUE NOT NULL,   -- Idempotent 보장
    player_tag      VARCHAR(32) NOT NULL,
    opponent_tag    VARCHAR(32),
    player_deck     JSONB NOT NULL,
    opponent_deck   JSONB,
    result          VARCHAR(8),                    -- 'win' | 'loss' | 'draw'
    battle_time     TIMESTAMP NOT NULL,
    raw_data        JSONB,
    created_at      TIMESTAMP DEFAULT now()
);
```

### stats_daily
```sql
CREATE TABLE stats_daily (
    id              BIGSERIAL PRIMARY KEY,
    stat_date       DATE NOT NULL,
    deck_hash       VARCHAR(64) NOT NULL,
    win_count       INT DEFAULT 0,
    use_count       INT DEFAULT 0,
    win_rate        NUMERIC(5,4),
    UNIQUE (stat_date, deck_hash)                  -- Airflow 재실행 멱등성
);
```

### features_training
```sql
CREATE TABLE features_training (
    id              BIGSERIAL PRIMARY KEY,
    snapshot_date   DATE NOT NULL,
    player_deck_vec INTEGER[8],                    -- One-hot 인코딩
    opponent_deck_vec INTEGER[8],
    result          SMALLINT,                      -- 1=win, 0=loss
    created_at      TIMESTAMP DEFAULT now()
);
```

---

## Spring Batch 파이프라인

```
[Airflow Trigger]
     │
     ▼
BattleLogJob
  └── Step 1: fetchAndStoreRawLogs
        ├── Reader  : ClashApiItemReader
        │             - 슈퍼셀 API 호출 (상위 랭커 태그 순회)
        │             - Rate Limit: 초당 10회 Throttling
        │             - Retry: 3회 (429/5xx 한정)
        ├── Processor: BattleLogProcessor
        │             - 응답 JSON → BattleLogRaw 엔티티 변환
        └── Writer  : BattleLogWriter
                      - battle_log_raw INSERT (ON CONFLICT DO NOTHING)
                      - Chunk size: 100
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

## 구현 순서 (Phase 1 → Phase 2)

### Phase 1 — 통계 기반 서비스 (MVP)

- [ ] **Step 1**: 프로젝트 기본 세팅
  - [ ] `com.rheon.royale` 패키지 구조 생성
  - [ ] `application.yml` 작성 (PostgreSQL, Redis, Actuator 설정)
  - [ ] `global/error/ApiResponse`, `ErrorCode`, `GlobalExceptionHandler` 구현
  - [ ] `global/config/RedisConfig`, `RestClientConfig` 구현

- [ ] **Step 2**: DB 엔티티 & 레포지토리
  - [ ] `BattleLogRaw`, `StatsDaily`, `FeaturesTraining` 엔티티 구현
  - [ ] `BattleLogRawRepository`, `StatsDailyRepository` JPA 인터페이스 작성

- [ ] **Step 3**: 외부 API 클라이언트
  - [ ] `infrastructure/external/clashroyale/ClashRoyaleClient` 구현
    - [ ] Rate Limit Throttling (초당 10회)
    - [ ] Retry 3회 (429/5xx 한정)
  - [ ] `ClashRoyaleProperties` (`@ConfigurationProperties`)

- [ ] **Step 4**: Spring Batch 파이프라인
  - [ ] `ClashApiItemReader` — `ClashRoyaleClient` 호출
  - [ ] `BattleLogProcessor` — 응답 DTO → `BattleLogRaw` 변환
  - [ ] `BattleLogWriter` — Idempotent Insert (ON CONFLICT DO NOTHING)
  - [ ] `BattleLogJobConfig` — Job/Step 조립 (Chunk size: 100)

- [ ] **Step 5**: 통계 집계 쿼리
  - [ ] `stats_daily` 집계 SQL (Native Query)
  - [ ] Airflow 트리거용 집계 SQL 스크립트 준비

- [ ] **Step 6**: REST API 구현
  - [ ] `domain/match/api/MatchController` — 전적 조회 (Redis TTL 5분)
  - [ ] `domain/card/api/CardController` — 티어표 조회 (Redis TTL 1시간)

### Phase 2 — AI 예측 도입

- [ ] **Step 7**: ML 서버 클라이언트 + 예측 API
  - [ ] `infrastructure/external/mlserver/MlServerClient` — WebClient 기반
  - [ ] `MlServerProperties` (`@ConfigurationProperties`)
  - [ ] `domain/prediction/application/PredictionService` — 호출 + Fallback 처리
  - [ ] `domain/prediction/api/PredictionController` 구현

- [ ] **Step 8**: 운영 안정화
  - [ ] Prometheus 메트릭 커스텀 등록 (P95 응답 시간)
  - [ ] K3s Deployment YAML + Resource Limit 설정
  - [ ] GitHub Actions CI/CD 파이프라인

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
