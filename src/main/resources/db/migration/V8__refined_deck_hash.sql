-- ============================================================
-- V8: 계층적 덱 해시 (Hierarchical Hashing) 도입
--
-- 설계 결정:
--   base_deck_hash  = MD5(sorted card_ids)          -- 카드 구성만 (진화 무관)
--   refined_deck_hash = MD5(sorted "id:evoLevel")   -- 진화/히어로 포함 정밀 식별
--
-- 왜 두 해시가 필요한가:
--   단일 해시로는 "진화 기사 포함 호그 덱"과 "일반 기사 포함 호그 덱"이 같은 통계로 합산됨
--   → 진화 카드가 포함된 덱의 승률이 희석되어 분석 의미 소실
--   refined로 집계하되 base로 그룹핑하면 두 뷰 모두 가능
--
-- deck_dictionary.card_ids TEXT → BIGINT[]:
--   TEXT("id1-id2-id3")는 GIN 인덱스 미지원 → 카드 포함 검색 전테이블 스캔 필요
--   BIGINT[] + GIN: WHERE card_ids @> ARRAY[26000000]::bigint[] 인덱스 타격 가능
--   나중에 유사덱(Jaccard) 쿼리: WHERE card_ids && other_deck_ids
-- ============================================================

-- 1. match_features: refined hash 컬럼 추가
ALTER TABLE match_features
    ADD COLUMN IF NOT EXISTS refined_deck_hash     VARCHAR(32),
    ADD COLUMN IF NOT EXISTS refined_opponent_hash VARCHAR(32);

CREATE INDEX IF NOT EXISTS idx_match_features_refined
    ON match_features (refined_deck_hash, battle_type, battle_date DESC);

-- 2. deck_dictionary: card_ids TEXT → BIGINT[]
--    기존 데이터 마이그레이션 포함
ALTER TABLE deck_dictionary ADD COLUMN IF NOT EXISTS card_ids_arr BIGINT[];

-- 기존 TEXT("26000000-26000001-...") → BIGINT[] 변환
UPDATE deck_dictionary
SET card_ids_arr = (
    SELECT ARRAY(
        SELECT unnest(string_to_array(card_ids, '-'))::BIGINT
    )
)
WHERE card_ids IS NOT NULL AND card_ids_arr IS NULL;

ALTER TABLE deck_dictionary DROP COLUMN IF EXISTS card_ids;
ALTER TABLE deck_dictionary RENAME COLUMN card_ids_arr TO card_ids;
ALTER TABLE deck_dictionary ALTER COLUMN card_ids SET NOT NULL;

-- GIN 인덱스: @> (포함), && (교집합) 연산자 고속 처리
CREATE INDEX IF NOT EXISTS idx_deck_dict_card_ids_gin
    ON deck_dictionary USING GIN (card_ids);

-- 3. stats_decks_daily: base_deck_hash 컬럼 추가
--    (stats_decks_daily_current는 StatsOverwriteTasklet Rename Swap으로 재생성됨)
ALTER TABLE stats_decks_daily
    ADD COLUMN IF NOT EXISTS base_deck_hash VARCHAR(32);

-- 기존 데이터: deck_hash가 base였으므로 그대로 복사
UPDATE stats_decks_daily
SET base_deck_hash = deck_hash
WHERE base_deck_hash IS NULL;
