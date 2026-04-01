package com.rheon.royale.batch.collector;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * battle_log_raw 인덱스 관리 — bulk INSERT 성능 최적화.
 *
 * DROP 모드: collectBattleLogStep 직전 실행 → 인덱스 제거로 INSERT 가속
 * CREATE 모드: collectBattleLogStep 직후 실행 → 인덱스 재생성 (CONCURRENTLY)
 *
 * 파티션 테이블이라 부모 인덱스 DROP 시 자식 인덱스도 자동 제거.
 * 재생성 시 부모에 CREATE INDEX → 기존 파티션에 자동 전파.
 */
@Slf4j
@RequiredArgsConstructor
public class IndexManagementTasklet implements Tasklet {

    private final JdbcTemplate jdbcTemplate;
    private final Mode mode;

    public enum Mode { DROP, CREATE }

    private static final List<String> DROP_SQLS = List.of(
            "DROP INDEX IF EXISTS idx_battle_log_main",
            "DROP INDEX IF EXISTS idx_battle_log_pending"
    );

    private static final List<String> CREATE_SQLS = List.of(
            "CREATE INDEX IF NOT EXISTS idx_battle_log_main ON battle_log_raw (player_tag, battle_type, created_at DESC)",
            "CREATE INDEX IF NOT EXISTS idx_battle_log_pending ON battle_log_raw (created_at, battle_id) WHERE analyzer_version < 2"
    );

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        List<String> sqls = (mode == Mode.DROP) ? DROP_SQLS : CREATE_SQLS;
        for (String sql : sqls) {
            long t0 = System.currentTimeMillis();
            jdbcTemplate.execute(sql);
            log.info("[IndexManager] {} ({}ms)", sql.substring(0, Math.min(60, sql.length())),
                    System.currentTimeMillis() - t0);
        }
        return RepeatStatus.FINISHED;
    }
}
