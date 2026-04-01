-- V14: Add per-card level array and opponent deck columns to match_features
-- These columns are required by the ML worker for LightGBM matchup model training.
-- Adding to parent table propagates to all partitions automatically.

-- Per-card level array (same order as card_ids, values 0-14)
ALTER TABLE match_features ADD COLUMN IF NOT EXISTS card_levels              SMALLINT[];

-- Opponent deck card info
ALTER TABLE match_features ADD COLUMN IF NOT EXISTS opponent_card_ids        BIGINT[];
ALTER TABLE match_features ADD COLUMN IF NOT EXISTS opponent_card_levels     SMALLINT[];
ALTER TABLE match_features ADD COLUMN IF NOT EXISTS opponent_card_evo_levels SMALLINT[];
