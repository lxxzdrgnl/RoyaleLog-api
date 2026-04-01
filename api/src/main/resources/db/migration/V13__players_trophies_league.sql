-- V13: players_to_crawl 에 트로피/리그 정보 컬럼 추가
--
-- current_trophies : 최근 수집된 트로피 (ladder/pvp 배틀 기준)
-- league_number    : 최근 수집된 PoL 리그 레벨 (0=브론즈 ~ 9=챔피언십)
--
-- 수집 시점: CollectBattleLogProcessor가 CR API 응답(newest-first)의
--            첫 번째 유효 배틀에서 추출 → CollectBattleLogWriter가 저장
-- COALESCE 업데이트: 한쪽 모드만 플레이한 유저도 이전 값 보존

ALTER TABLE players_to_crawl
    ADD COLUMN IF NOT EXISTS current_trophies INT,
    ADD COLUMN IF NOT EXISTS league_number    INT;
