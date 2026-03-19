-- ============================================================
-- V6: 유저 등급제(priority) + Pruning용 인덱스
-- ============================================================

-- priority 컬럼: P1(Active) / P2(Normal) / P3(Dormant)
-- 기본값 2: BFS로 발견된 상대방은 Normal로 시작
ALTER TABLE players_to_crawl
    ADD COLUMN IF NOT EXISTS priority SMALLINT NOT NULL DEFAULT 2;

-- Pruning 쿼리: WHERE last_crawled_at < NOW() - INTERVAL '30 days'
-- 100만 유저 환경에서 인덱스 없으면 전체 테이블 스캔 → 서비스 멈춤
CREATE INDEX IF NOT EXISTS idx_players_to_crawl_last_crawled_at
    ON players_to_crawl (last_crawled_at ASC NULLS FIRST);

-- 배치 Reader 정렬: ORDER BY priority ASC, last_crawled_at ASC NULLS FIRST
-- priority + last_crawled_at 복합 인덱스로 커버
CREATE INDEX IF NOT EXISTS idx_players_to_crawl_priority_crawled
    ON players_to_crawl (priority ASC, last_crawled_at ASC NULLS FIRST)
    WHERE is_active = true;

-- PriorityUpdateTasklet에서 등급 갱신 시 사용하는 서브쿼리 인덱스
-- SELECT player_tag FROM battle_log_raw WHERE created_at >= NOW() - INTERVAL '3 days'
-- → (player_tag, created_at) 인덱스는 V1에서 이미 생성됨 (idx_battle_log_raw_player)
