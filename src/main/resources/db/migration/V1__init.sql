-- =============================================================
-- V1: Phase 1 초기 스키마 (Collector Job 대상 테이블)
-- =============================================================

-- -------------------------------------------------------------
-- 0. ENUM 타입 정의
-- -------------------------------------------------------------
DO $$ BEGIN
    CREATE TYPE card_type_enum AS ENUM ('NORMAL', 'EVOLUTION', 'HERO');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
    CREATE TYPE card_rarity_enum AS ENUM ('COMMON', 'RARE', 'EPIC', 'LEGENDARY', 'CHAMPION');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- -------------------------------------------------------------
-- 1. 카드 메타 정보
--    card_key: '{api_id}_{card_type}' 형태 (예: 26000000_NORMAL)
--    card_type: NORMAL / EVOLUTION / HERO
--    → 동일 api_id라도 타입별로 별개 행으로 저장
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS cards (
    card_key        VARCHAR(50)      PRIMARY KEY,       -- '{api_id}_{card_type}'
    api_id          BIGINT           NOT NULL,
    name            VARCHAR(100)     NOT NULL,
    card_type       card_type_enum   NOT NULL,
    rarity          card_rarity_enum,
    elixir_cost     INT,
    max_level       INT,
    max_evo_level   INT,                                -- 외형(황금 카드 등) 판별용
    icon_url        TEXT,
    is_deck_card    BOOLEAN      NOT NULL DEFAULT TRUE,   -- 8장 덱에 포함 가능한 카드
    is_tower        BOOLEAN      NOT NULL DEFAULT FALSE,  -- 타워 카드 (supportCards, id>=159000000)
    synced_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_cards_api_id_type
    ON cards (api_id, card_type);

-- -------------------------------------------------------------
-- 2. 수집 대상 플레이어 목록
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS players_to_crawl (
    player_tag      VARCHAR(20)  PRIMARY KEY,
    name            VARCHAR(100),
    current_rank    INT,
    last_crawled_at TIMESTAMP,
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_players_active
    ON players_to_crawl (is_active, last_crawled_at);

-- -------------------------------------------------------------
-- 3. 배틀 로그 원천 데이터 (월별 파티셔닝)
--    PK에 created_at 포함 필수 (PostgreSQL 파티셔닝 제약)
--    파티션 테이블 자체는 PartitionManagerTasklet이 생성/삭제
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS battle_log_raw (
    battle_id   VARCHAR(32)  NOT NULL,
    player_tag  VARCHAR(20)  NOT NULL,
    battle_type VARCHAR(50),
    raw_json    JSONB        NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (battle_id, created_at)
) PARTITION BY RANGE (created_at);

-- 메인 조회 인덱스: 특정 플레이어의 최신 배틀 타입별 조회
CREATE INDEX IF NOT EXISTS idx_battle_log_main
    ON battle_log_raw (player_tag, battle_type, created_at DESC);

-- 초기 파티션: 앱 최초 기동 시점 (2026년 3월, 4월)
-- 이후 파티션은 PartitionManagerTasklet이 자동 관리
CREATE TABLE IF NOT EXISTS battle_log_raw_2026_03
    PARTITION OF battle_log_raw
    FOR VALUES FROM ('2026-03-01') TO ('2026-04-01');

CREATE TABLE IF NOT EXISTS battle_log_raw_2026_04
    PARTITION OF battle_log_raw
    FOR VALUES FROM ('2026-04-01') TO ('2026-05-01');
