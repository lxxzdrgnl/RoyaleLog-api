-- =============================================================
-- battle_log_raw 용량 정리
-- 모든 브라켓 100,000건 유지, trophy_unknown 전체 삭제
-- 제외 플레이어: #PGR9PGR, #8JLRCOJLV (전량 보존)
-- =============================================================

BEGIN;

CREATE TEMP TABLE battles_to_delete AS
WITH classified AS (
    SELECT
        battle_id,
        created_at,
        CASE
            WHEN battle_type = 'pathOfLegend'
                THEN 'pol_' || COALESCE((raw_json ->> 'leagueNumber'), 'unknown')
            WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies') IS NULL THEN 'trophy_unknown'
            WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int <= 0   THEN 'trophy_unknown'
            WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int <  300 THEN 'arena_01'
            WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int <   600 THEN 'arena_02'
            WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int <  1000 THEN 'arena_03'
            WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int <  1300 THEN 'arena_04'
            WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int <  1600 THEN 'arena_05'
            WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int <  2000 THEN 'arena_06'
            WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int <  2300 THEN 'arena_07'
            WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int <  2600 THEN 'arena_08'
            WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int <  3000 THEN 'arena_09'
            WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int <  3400 THEN 'arena_10'
            WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int <  3800 THEN 'arena_11'
            WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int <  4200 THEN 'arena_12'
            WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int <  4600 THEN 'arena_13'
            WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int <  5000 THEN 'arena_14'
            WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int <  5500 THEN 'arena_15'
            WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int <  6000 THEN 'arena_16'
            WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int <  6500 THEN 'arena_17'
            WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int <  7000 THEN 'arena_18'
            WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int <  7500 THEN 'arena_19'
            WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int <  8000 THEN 'arena_20'
            WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int <  8500 THEN 'arena_21'
            WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int <  9000 THEN 'arena_22'
            WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int <  9500 THEN 'arena_23'
            WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int < 10000 THEN 'arena_24'
            WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int < 10500 THEN 'arena_25'
            WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int < 11000 THEN 'arena_26'
            WHEN (raw_json -> 'team' -> 0 ->> 'startingTrophies')::int < 11500 THEN 'arena_27'
            ELSE 'arena_28'
        END AS bracket
    FROM battle_log_raw
    WHERE player_tag NOT IN ('#PGR9PGR', '#8JLRCOJLV')
),
ranked AS (
    SELECT battle_id, created_at, bracket,
           ROW_NUMBER() OVER (PARTITION BY bracket ORDER BY created_at DESC) AS rn
    FROM classified
)
SELECT battle_id, created_at
FROM ranked
WHERE bracket = 'trophy_unknown'   -- trophy_unknown 전체 삭제
   OR rn > 100000;                 -- 그 외 브라켓 10만건 초과분 삭제

CREATE INDEX ON battles_to_delete (battle_id, created_at);

SELECT COUNT(*) AS to_delete_count FROM battles_to_delete;

DELETE FROM battle_log_raw b
USING battles_to_delete d
WHERE b.battle_id  = d.battle_id
  AND b.created_at = d.created_at;

COMMIT;

DROP TABLE IF EXISTS battles_to_delete;

SELECT COUNT(*) AS remaining FROM battle_log_raw;
