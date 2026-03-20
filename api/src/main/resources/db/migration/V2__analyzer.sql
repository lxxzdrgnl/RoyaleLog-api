-- ============================================================
-- V2: Analyzer Job 테이블
-- 아키텍처: Raw → Dimension → Feature(ML) → Fact(집계)
-- ============================================================

-- 0. analyzer_meta — 버전 관리 (하드코딩 금지)
--    current_version: DeckAnalyzerWriter가 DB에서 읽어 사용
--    버전 업 → UPDATE analyzer_meta SET current_version = N
--    → 이전 버전 처리된 배틀 자동 재처리 대상 편입
--    CHECK (id = 1): DB constraint로 싱글톤 강제
--      논리 singleton ≠ DB singleton → constraint 없으면 다중 인스턴스 경쟁 시 깨짐
-- ============================================================
CREATE TABLE IF NOT EXISTS analyzer_meta (
    id              INT       PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    current_version INT       NOT NULL    DEFAULT 1,
    updated_at      TIMESTAMP NOT NULL    DEFAULT CURRENT_TIMESTAMP
);

-- 싱글톤 row: id=1 고정, ON CONFLICT DO NOTHING = 멱등 초기화
INSERT INTO analyzer_meta (id, current_version)
VALUES (1, 1)
ON CONFLICT (id) DO NOTHING;

-- 1. battle_log_raw Analyzer 추적 컬럼
ALTER TABLE battle_log_raw
    ADD COLUMN IF NOT EXISTS analyzer_processed_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS analyzer_version      INT NOT NULL DEFAULT 0;

-- 인덱스: 조회 패턴 기준 설계
--   WHERE analyzer_version < N ORDER BY created_at LIMIT K
--   → 정렬 기준(created_at) 먼저, 필터(analyzer_version) 나중
--   → (analyzer_version, created_at) 순서면 range 조건 후 filesort 발생
CREATE INDEX IF NOT EXISTS idx_battle_log_unprocessed
    ON battle_log_raw (created_at, analyzer_version);

-- ============================================================
-- 2. deck_dictionary — Dimension 테이블 (mutable)
--    card_ids: TEXT (숫자 오름차순 정렬 후 "-" 구분자로 직렬화)
--      ex) "26000000-26000001-26000002-57000006"
--      BIGINT[]를 쓰지 않는 이유:
--        - B-tree 인덱스 불가
--        - equality 비교 느림
--        - 정렬 보장 실패 시 hash mismatch 추적 어려움
--    ON CONFLICT DO UPDATE: 로직 변경 시 card_ids 갱신 가능
-- ============================================================
CREATE TABLE IF NOT EXISTS deck_dictionary (
    deck_hash   VARCHAR(32) PRIMARY KEY,
    card_ids    TEXT        NOT NULL,
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 3. match_features — ML Feature 테이블
--    partition: 월별 (battle_log_raw 와 동일 전략, 90일 보관)
--    canonical battle_id: MD5(sorted tags + battleTime with ms precision)
--      → 양쪽 플레이어 크롤 시 동일 ID → 한 게임 = 한 row 보장
--
--    ⚠ PK (battle_id, battle_date) 설계 이유:
--      PostgreSQL partitioned table은 PK에 partition key 포함 필수
--      이론적으로 같은 battle_id + 다른 battle_date 중복 가능하나
--      battle_id = MD5(tags + battleTime) → battle_date는 battleTime에서 결정론적 파생
--      → 앱 단에서 battle_date 일관성 보장, ON CONFLICT (battle_id, battle_date) 사용 필수
-- ============================================================
CREATE TABLE IF NOT EXISTS match_features (
    battle_id       VARCHAR(32)  NOT NULL,
    deck_hash       VARCHAR(32)  NOT NULL,
    opponent_hash   VARCHAR(32),
    battle_type     VARCHAR(50)  NOT NULL,
    battle_date     DATE         NOT NULL,
    avg_level       NUMERIC(4,2),
    evolution_count SMALLINT     NOT NULL DEFAULT 0,
    result          SMALLINT     NOT NULL,   -- 1=win, 0=loss
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (battle_id, battle_date)
) PARTITION BY RANGE (battle_date);

CREATE TABLE IF NOT EXISTS match_features_2026_03 PARTITION OF match_features
    FOR VALUES FROM ('2026-03-01') TO ('2026-04-01');
CREATE TABLE IF NOT EXISTS match_features_2026_04 PARTITION OF match_features
    FOR VALUES FROM ('2026-04-01') TO ('2026-05-01');

CREATE INDEX IF NOT EXISTS idx_match_features_deck
    ON match_features (deck_hash, battle_type, battle_date DESC);

-- ============================================================
-- 4. stats_decks_daily — 역사 보관용 Fact 테이블 (월별 파티션)
--    용도: 장기 통계 분석 / Phase 2 ML Feature 입력
--    API 직접 조회 금지: serving은 stats_decks_daily_current 경유
-- ============================================================
CREATE TABLE IF NOT EXISTS stats_decks_daily (
    stat_date    DATE        NOT NULL,
    deck_hash    VARCHAR(32) NOT NULL,
    battle_type  VARCHAR(50) NOT NULL,
    win_count    INT         NOT NULL DEFAULT 0,
    use_count    INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (stat_date, deck_hash, battle_type)
) PARTITION BY RANGE (stat_date);

CREATE TABLE IF NOT EXISTS stats_decks_daily_2026_03 PARTITION OF stats_decks_daily
    FOR VALUES FROM ('2026-03-01') TO ('2026-04-01');
CREATE TABLE IF NOT EXISTS stats_decks_daily_2026_04 PARTITION OF stats_decks_daily
    FOR VALUES FROM ('2026-04-01') TO ('2026-05-01');

CREATE INDEX IF NOT EXISTS idx_stats_daily_deck
    ON stats_decks_daily (deck_hash, battle_type, stat_date DESC);

-- ============================================================
-- 5. stats_decks_daily_current — API Serving 포인터 테이블
--    왜 VIEW가 아닌 테이블인가:
--      VIEW는 underlying table 그대로 참조 → swap 중 dirty read 발생 가능
--      테이블 RENAME은 catalog 레벨 원자 연산 → API는 항상 완전한 스냅샷만 조회
--    Swap 절차 (StatsOverwriteTasklet):
--      1. stats_new 에 전체 재집계
--      2. stats_decks_daily_current RENAME TO stats_old
--      3. stats_new RENAME TO stats_decks_daily_current
--      4. DROP stats_old
-- ============================================================
CREATE TABLE IF NOT EXISTS stats_decks_daily_current (
    stat_date    DATE        NOT NULL,
    deck_hash    VARCHAR(32) NOT NULL,
    battle_type  VARCHAR(50) NOT NULL,
    win_count    INT         NOT NULL DEFAULT 0,
    use_count    INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (stat_date, deck_hash, battle_type)
);

CREATE INDEX IF NOT EXISTS idx_stats_current_deck
    ON stats_decks_daily_current (deck_hash, battle_type, stat_date DESC);

-- ============================================================
-- 6. stats_current — API 조회 진입점 (포인터 추상화)
--    API는 이 뷰만 참조: stats_decks_daily_current 구조 변경 시 뷰만 수정
-- ============================================================
CREATE OR REPLACE VIEW stats_current AS
SELECT stat_date,
       deck_hash,
       battle_type,
       win_count,
       use_count
FROM stats_decks_daily_current;
