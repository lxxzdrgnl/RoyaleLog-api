-- V7: priority 컬럼 SMALLINT → INTEGER 변경
-- Hibernate JPA 타입 매핑(int → INTEGER) 과 일치시키기 위해 타입 변환
ALTER TABLE players_to_crawl
    ALTER COLUMN priority TYPE INTEGER USING priority::INTEGER;
