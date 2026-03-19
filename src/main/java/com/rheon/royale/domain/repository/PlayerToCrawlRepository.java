package com.rheon.royale.domain.repository;

import com.rheon.royale.domain.entity.PlayerToCrawl;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlayerToCrawlRepository extends JpaRepository<PlayerToCrawl, String> {

    /** 현재 랭킹 플레이어 UPSERT — is_active=true 로 활성화 */
    @Modifying
    @Query(value = """
            INSERT INTO players_to_crawl (player_tag, name, current_rank, is_active, updated_at)
            VALUES (:tag, :name, :rank, TRUE, NOW())
            ON CONFLICT (player_tag) DO UPDATE SET
                name         = EXCLUDED.name,
                current_rank = EXCLUDED.current_rank,
                is_active    = TRUE,
                updated_at   = NOW()
            """, nativeQuery = true)
    void upsertRanked(@Param("tag") String tag,
                      @Param("name") String name,
                      @Param("rank") int rank);

    /** 배틀 로그 수집 완료 시 last_crawled_at 갱신 */
    @Modifying
    @Query("UPDATE PlayerToCrawl p SET p.lastCrawledAt = CURRENT_TIMESTAMP WHERE p.playerTag = :tag")
    void updateLastCrawledAt(@Param("tag") String tag);

    /** 7일 이내 전적 없음(잠수) → 비활성화 (Pruning) */
    @Modifying
    @Query("UPDATE PlayerToCrawl p SET p.isActive = false, p.updatedAt = CURRENT_TIMESTAMP WHERE p.playerTag = :tag")
    void deactivate(@Param("tag") String tag);
}
