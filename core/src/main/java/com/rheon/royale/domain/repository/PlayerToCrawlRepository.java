package com.rheon.royale.domain.repository;

import com.rheon.royale.domain.entity.PlayerToCrawl;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PlayerToCrawlRepository extends JpaRepository<PlayerToCrawl, String> {

    /** 현재 랭킹 플레이어 UPSERT — is_active=true 로 활성화, league_number + bracket 갱신
     *  PoL API는 trophies를 주지 않으므로 current_trophies 는 건드리지 않음 */
    @Modifying
    @Query(value = """
            INSERT INTO players_to_crawl (player_tag, name, current_rank, league_number, bracket, is_active, updated_at)
            VALUES (:tag, :name, :rank, :leagueNumber, :bracket, TRUE, NOW())
            ON CONFLICT (player_tag) DO UPDATE SET
                name          = EXCLUDED.name,
                current_rank  = EXCLUDED.current_rank,
                league_number = COALESCE(EXCLUDED.league_number, players_to_crawl.league_number),
                bracket       = COALESCE(EXCLUDED.bracket, players_to_crawl.bracket),
                is_active     = TRUE,
                updated_at    = NOW()
            """, nativeQuery = true)
    void upsertRanked(@Param("tag") String tag,
                      @Param("name") String name,
                      @Param("rank") int rank,
                      @Param("leagueNumber") Integer leagueNumber,
                      @Param("bracket") String bracket);

    /** 배틀 수집 완료 시 last_crawled_at + 트로피/리그/브라켓 갱신
     *  COALESCE: null이면 기존 값 보존 (ladder ↔ PoL 전환 유저 대응) */
    @Modifying
    @Query(value = """
            UPDATE players_to_crawl SET
                last_crawled_at  = NOW(),
                current_trophies = COALESCE(:trophies, current_trophies),
                league_number    = COALESCE(:league, league_number),
                bracket          = COALESCE(:bracket, bracket),
                updated_at       = NOW()
            WHERE player_tag = :tag
            """, nativeQuery = true)
    void updateAfterCrawl(@Param("tag") String tag,
                          @Param("trophies") Integer trophies,
                          @Param("league") Integer league,
                          @Param("bracket") String bracket);

    /** BracketAwarePlayerReader.open()에서 early-stop 기준 브라켓 목록 조회 */
    @Query(value = """
            SELECT DISTINCT bracket
            FROM players_to_crawl
            WHERE is_active = true AND bracket IS NOT NULL
            """, nativeQuery = true)
    List<String> findDistinctActiveBrackets();

    /** 7일 이내 전적 없음(잠수) → 비활성화 (Pruning) */
    @Modifying
    @Query("UPDATE PlayerToCrawl p SET p.isActive = false, p.updatedAt = CURRENT_TIMESTAMP WHERE p.playerTag = :tag")
    void deactivate(@Param("tag") String tag);
}
