-- =============================================================
-- players_to_crawl.current_trophies 보정 (2차)
-- battle_log_raw 에서 opponent 측으로 등장한 배틀의 트로피/리그 추출
-- 대상: 1차 보정(team 기준) 이후에도 null인 유저
-- =============================================================

-- 대상 확인
SELECT COUNT(*) AS still_null
FROM players_to_crawl
WHERE current_trophies IS NULL AND league_number IS NULL AND is_active = true;

-- opponent 측에서 트로피 추출 → 업데이트
UPDATE players_to_crawl p
SET
    current_trophies = COALESCE(subq.trophies, p.current_trophies),
    league_number    = COALESCE(subq.league_number, p.league_number),
    updated_at       = NOW()
FROM (
    SELECT DISTINCT ON (opp_tag)
        raw_json -> 'opponent' -> 0 ->> 'tag' AS opp_tag,
        (raw_json -> 'opponent' -> 0 ->> 'startingTrophies')::int AS trophies,
        (raw_json -> 'opponent' -> 0 ->> 'leagueNumber')::int      AS league_number
    FROM battle_log_raw
    WHERE raw_json -> 'opponent' -> 0 ->> 'tag' IS NOT NULL
    ORDER BY opp_tag, created_at DESC
) subq
WHERE p.player_tag = subq.opp_tag
  AND p.current_trophies IS NULL
  AND p.league_number    IS NULL;

-- 결과 확인
SELECT
    COUNT(*) FILTER (WHERE current_trophies IS NOT NULL OR league_number IS NOT NULL) AS filled,
    COUNT(*) FILTER (WHERE current_trophies IS NULL AND league_number IS NULL AND is_active = true) AS still_null
FROM players_to_crawl;
