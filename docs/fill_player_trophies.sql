-- =============================================================
-- players_to_crawl.current_trophies / league_number 일회성 보정
-- battle_log_raw 의 기존 데이터에서 최신 배틀 기준으로 추출
-- API 호출 없음 — 이미 수집된 raw_json 재활용
-- =============================================================

-- 대상 확인 (실행 전 검토)
SELECT COUNT(*) AS null_trophy_users
FROM players_to_crawl
WHERE current_trophies IS NULL AND league_number IS NULL AND is_active = true;

-- 보정 실행
UPDATE players_to_crawl p
SET
    current_trophies = COALESCE(
        subq.trophies,
        p.current_trophies
    ),
    league_number = COALESCE(
        subq.league_number,
        p.league_number
    ),
    updated_at = NOW()
FROM (
    SELECT DISTINCT ON (player_tag)
        player_tag,
        (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int AS trophies,
        (raw_json -> 'team' -> 0 ->> 'leagueNumber')::int      AS league_number
    FROM battle_log_raw
    ORDER BY player_tag, created_at DESC  -- 최신 배틀 기준
) subq
WHERE p.player_tag = subq.player_tag
  AND p.current_trophies IS NULL
  AND p.league_number    IS NULL;

-- 결과 확인
SELECT COUNT(*) AS still_null
FROM players_to_crawl
WHERE current_trophies IS NULL AND league_number IS NULL AND is_active = true;
