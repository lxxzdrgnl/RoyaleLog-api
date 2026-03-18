-- ============================================================
-- V3: 타워 카드 시드 데이터
--
-- 이유: /cards API에 타워 카드(supportCards) 미포함
--   → CardSyncJob으로 수집 불가 → DB Seed로 관리
--
-- 발견 방법: 배틀 로그 player.supportCards 필드에서 수집
-- 확인 시점: 2026-03-18 (PoL 상위 10명 배틀 로그 샘플링)
--
-- 타워 카드 규칙:
--   is_deck_card = FALSE (8장 덱에 포함 불가)
--   is_tower     = TRUE
--   card_type    = NORMAL
--   elixir_cost  = NULL (시전 비용 없음)
--   max_evo_level = NULL
--
-- 신규 타워 카드 추가 시:
--   배틀 로그 supportCards에서 id/name/rarity/maxLevel 확인 후 INSERT 추가
-- ============================================================

INSERT INTO cards (card_key, api_id, name, card_type, rarity, elixir_cost,
                   max_level, max_evo_level, icon_url, is_deck_card, is_tower, synced_at)
VALUES
    -- Tower Princess (id=159000000, rarity=COMMON, maxLevel=16)
    ('159000000_NORMAL', 159000000, 'Tower Princess', 'NORMAL', 'COMMON',
     NULL, 16, NULL, NULL, FALSE, TRUE, NOW()),

    -- Cannoneer (id=159000001, rarity=EPIC, maxLevel=11)
    ('159000001_NORMAL', 159000001, 'Cannoneer', 'NORMAL', 'EPIC',
     NULL, 11, NULL, NULL, FALSE, TRUE, NOW()),

    -- Dagger Duchess (id=159000002, rarity=LEGENDARY, maxLevel=8)
    ('159000002_NORMAL', 159000002, 'Dagger Duchess', 'NORMAL', 'LEGENDARY',
     NULL, 8, NULL, NULL, FALSE, TRUE, NOW()),

    -- Royal Chef (id=159000004, rarity=LEGENDARY, maxLevel=8)
    -- id=159000003 미확인: 샘플 미발견 또는 미출시 카드
    ('159000004_NORMAL', 159000004, 'Royal Chef', 'NORMAL', 'LEGENDARY',
     NULL, 8, NULL, NULL, FALSE, TRUE, NOW())

ON CONFLICT (card_key) DO UPDATE SET
    name      = EXCLUDED.name,
    max_level = EXCLUDED.max_level,
    is_tower  = TRUE,
    synced_at = EXCLUDED.synced_at;
