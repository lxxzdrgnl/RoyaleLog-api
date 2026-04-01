-- V15__players_bracket.sql
ALTER TABLE players_to_crawl ADD COLUMN IF NOT EXISTS bracket VARCHAR(20);

CREATE INDEX IF NOT EXISTS idx_ptc_bracket_active
    ON players_to_crawl (bracket)
    WHERE is_active = true;

UPDATE players_to_crawl SET bracket =
    CASE
        WHEN league_number IS NOT NULL        THEN 'pol_' || league_number
        WHEN current_trophies IS NULL
          OR current_trophies <= 0            THEN 'unknown'
        WHEN current_trophies <   300         THEN 'arena_01'
        WHEN current_trophies <   600         THEN 'arena_02'
        WHEN current_trophies <  1000         THEN 'arena_03'
        WHEN current_trophies <  1300         THEN 'arena_04'
        WHEN current_trophies <  1600         THEN 'arena_05'
        WHEN current_trophies <  2000         THEN 'arena_06'
        WHEN current_trophies <  2300         THEN 'arena_07'
        WHEN current_trophies <  2600         THEN 'arena_08'
        WHEN current_trophies <  3000         THEN 'arena_09'
        WHEN current_trophies <  3400         THEN 'arena_10'
        WHEN current_trophies <  3800         THEN 'arena_11'
        WHEN current_trophies <  4200         THEN 'arena_12'
        WHEN current_trophies <  4600         THEN 'arena_13'
        WHEN current_trophies <  5000         THEN 'arena_14'
        WHEN current_trophies <  5500         THEN 'arena_15'
        WHEN current_trophies <  6000         THEN 'arena_16'
        WHEN current_trophies <  6500         THEN 'arena_17'
        WHEN current_trophies <  7000         THEN 'arena_18'
        WHEN current_trophies <  7500         THEN 'arena_19'
        WHEN current_trophies <  8000         THEN 'arena_20'
        WHEN current_trophies <  8500         THEN 'arena_21'
        WHEN current_trophies <  9000         THEN 'arena_22'
        WHEN current_trophies <  9500         THEN 'arena_23'
        WHEN current_trophies < 10000         THEN 'arena_24'
        WHEN current_trophies < 10500         THEN 'arena_25'
        WHEN current_trophies < 11000         THEN 'arena_26'
        WHEN current_trophies < 11500         THEN 'arena_27'
        ELSE                                      'arena_28'
    END;
