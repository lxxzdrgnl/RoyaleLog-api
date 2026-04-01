package com.rheon.royale.global.util;

/**
 * batch/api 모듈 공통 SQL 상수.
 * 동일 테이블 UPSERT SQL이 여러 곳에 흩어지는 것을 방지.
 */
public final class BatchSqlConstants {

    private BatchSqlConstants() {}

    /** battle_log_raw INSERT — ON CONFLICT DO NOTHING (멱등성) */
    public static final String INSERT_BATTLE_SQL = """
            INSERT INTO battle_log_raw (battle_id, player_tag, battle_type, raw_json, created_at)
            VALUES (?, ?, ?, CAST(? AS jsonb), ?)
            ON CONFLICT (battle_id, created_at) DO NOTHING
            """;

    /**
     * players_to_crawl UPSERT (BFS 상대방 발견 + 온디맨드 등록 공용).
     * - is_active = true (검색 유저 재활성화 / 신규 발견 활성화)
     * - COALESCE: null이면 기존 값 보존
     * - IS DISTINCT FROM: 값 동일 시 불필요한 UPDATE 방지 (table bloat 감소)
     *
     * 파라미터 순서: player_tag, name, current_trophies, league_number, bracket
     */
    public static final String UPSERT_PLAYER_SQL = """
            INSERT INTO players_to_crawl (player_tag, name, is_active, current_trophies, league_number, bracket, updated_at)
            VALUES (?, ?, true, ?, ?, ?, NOW())
            ON CONFLICT (player_tag) DO UPDATE
                SET name             = COALESCE(EXCLUDED.name, players_to_crawl.name),
                    is_active        = true,
                    current_trophies = COALESCE(EXCLUDED.current_trophies, players_to_crawl.current_trophies),
                    league_number    = COALESCE(EXCLUDED.league_number,    players_to_crawl.league_number),
                    bracket          = COALESCE(EXCLUDED.bracket,          players_to_crawl.bracket),
                    updated_at       = NOW()
            WHERE players_to_crawl.name             IS DISTINCT FROM COALESCE(EXCLUDED.name, players_to_crawl.name)
               OR players_to_crawl.current_trophies IS DISTINCT FROM COALESCE(EXCLUDED.current_trophies, players_to_crawl.current_trophies)
               OR players_to_crawl.league_number    IS DISTINCT FROM COALESCE(EXCLUDED.league_number, players_to_crawl.league_number)
               OR players_to_crawl.bracket          IS DISTINCT FROM COALESCE(EXCLUDED.bracket, players_to_crawl.bracket)
               OR players_to_crawl.is_active        IS DISTINCT FROM true
            """;
}
