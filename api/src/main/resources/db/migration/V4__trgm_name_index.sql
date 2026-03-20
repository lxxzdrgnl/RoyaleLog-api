-- pg_trgm: 닉네임 양방향 부분 검색 고속화 (ILIKE '%name%')
-- BFS 풀 확장으로 유저 수 급증 시에도 인덱스 스캔으로 0.01초 이내 응답 보장
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_players_to_crawl_name_trgm
    ON players_to_crawl USING GIN (name gin_trgm_ops);
