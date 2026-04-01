-- ============================================================
-- V11: refined_deck_dictionary — 진화/히어로 카드 레벨 매핑
--
-- 목적:
--   tier list에서 "진화 대포, 히어로 얼골 포함 순환 호그" 처럼
--   덱 내 각 카드의 진화 레벨을 표시하기 위한 보조 테이블
--
-- 설계:
--   - deck_hash (refined) → card_evo_levels SMALLINT[] (deck_dictionary.card_ids와 동일 정렬 순서)
--   - base variant (진화 없음)는 저장하지 않음 (refined == base일 때 INSERT 스킵)
--   - ON CONFLICT DO NOTHING: 멱등 INSERT
--   - 배치 재처리 시 중복 없이 안전하게 upsert
-- ============================================================

CREATE TABLE IF NOT EXISTS refined_deck_dictionary (
    deck_hash       VARCHAR(32) PRIMARY KEY,  -- refined deck hash (진화/히어로 포함)
    base_deck_hash  VARCHAR(32) NOT NULL,      -- base deck hash (deck_dictionary.deck_hash)
    card_evo_levels SMALLINT[]  NOT NULL,      -- deck_dictionary.card_ids와 같은 정렬 기준 (숫자 오름차순)
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- base_deck_hash 기준 조회 인덱스: "이 기본 덱의 진화 조합 목록" 쿼리
CREATE INDEX IF NOT EXISTS idx_rdd_base_deck_hash
    ON refined_deck_dictionary (base_deck_hash);
