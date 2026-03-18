package com.rheon.royale.domain.repository;

import com.rheon.royale.domain.entity.BattleLogRaw;
import com.rheon.royale.domain.entity.BattleLogRawId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BattleLogRawRepository extends JpaRepository<BattleLogRaw, BattleLogRawId> {
    // 배치 INSERT는 CollectBattleLogWriter 에서 JdbcTemplate 직접 사용 (ON CONFLICT DO NOTHING)
}
