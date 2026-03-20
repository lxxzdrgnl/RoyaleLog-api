-- V10: Add league_number and starting_trophies to match_features (parent → auto-propagates to all partitions)
--
-- league_number    : pathOfLegend 전용 (0~9 등급, 높을수록 상위권)
-- starting_trophies: PvP 등 트로피 기반 모드 전용 (trail 등 일부 모드는 NULL)
--
-- 부모 테이블에 추가하면 모든 파티션에 자동 전파됨

ALTER TABLE match_features ADD COLUMN IF NOT EXISTS league_number INT;
ALTER TABLE match_features ADD COLUMN IF NOT EXISTS starting_trophies INT;
