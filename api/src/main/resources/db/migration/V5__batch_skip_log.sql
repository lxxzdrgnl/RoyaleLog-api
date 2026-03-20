-- DLQ: 배치 Skip 발생 건 기록
-- SkipListener가 DataIntegrityViolationException 등 침묵 실패를 여기에 적재
-- 모니터링: SELECT * FROM batch_skip_log ORDER BY skipped_at DESC;
CREATE TABLE IF NOT EXISTS batch_skip_log (
    id          BIGSERIAL PRIMARY KEY,
    item_key    TEXT        NOT NULL,   -- player_tag 또는 기타 식별자
    phase       VARCHAR(10) NOT NULL,   -- READ / PROCESS / WRITE
    reason      TEXT        NOT NULL,
    skipped_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_batch_skip_log_skipped_at ON batch_skip_log (skipped_at DESC);
