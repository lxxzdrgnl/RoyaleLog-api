-- V12: match_features에 card_ids, card_evo_levels 추가
--
-- 목적:
--   deck_dictionary / refined_deck_dictionary 제거
--   → match_features 자체에 카드 정보 포함
--   → stats_decks_daily_current에 card_ids 직접 집계 가능
--   → API에서 deck_dictionary JOIN 불필요

ALTER TABLE match_features ADD COLUMN IF NOT EXISTS card_ids      BIGINT[];
ALTER TABLE match_features ADD COLUMN IF NOT EXISTS card_evo_levels SMALLINT[];
