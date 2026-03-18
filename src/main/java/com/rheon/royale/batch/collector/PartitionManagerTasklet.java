package com.rheon.royale.batch.collector;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 월별 파티션 자동 관리 Tasklet
 *
 * 관리 대상:
 *   - battle_log_raw   (Raw 레이어)
 *   - match_features   (ML Feature 레이어)
 *
 * 전략:
 *   - 당월 + 익월 파티션 생성 (IF NOT EXISTS)
 *   - 90일 이전(3개월 전) 파티션 DROP
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PartitionManagerTasklet implements Tasklet {

    private static final List<String> PARTITIONED_TABLES = List.of(
            "battle_log_raw",
            "match_features",
            "stats_decks_daily"
    );

    private final JdbcTemplate jdbcTemplate;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        LocalDate today = LocalDate.now();

        for (String table : PARTITIONED_TABLES) {
            createPartitionIfAbsent(table, today);
            createPartitionIfAbsent(table, today.plusMonths(1));
            dropOldPartitionIfExists(table, today.minusMonths(3));
        }

        return RepeatStatus.FINISHED;
    }

    private void createPartitionIfAbsent(String table, LocalDate month) {
        String partitionName = partitionName(table, month);
        LocalDate start = month.withDayOfMonth(1);
        LocalDate end = start.plusMonths(1);

        jdbcTemplate.execute(String.format("""
                CREATE TABLE IF NOT EXISTS %s PARTITION OF %s
                FOR VALUES FROM ('%s') TO ('%s')
                """,
                partitionName,
                table,
                start.format(DateTimeFormatter.ISO_LOCAL_DATE),
                end.format(DateTimeFormatter.ISO_LOCAL_DATE)));

        log.info("[PartitionManager] 파티션 확인/생성: {}", partitionName);
    }

    private void dropOldPartitionIfExists(String table, LocalDate month) {
        String partitionName = partitionName(table, month);
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + partitionName);
        log.info("[PartitionManager] 오래된 파티션 DROP: {}", partitionName);
    }

    private String partitionName(String table, LocalDate month) {
        return String.format("%s_%d_%02d", table, month.getYear(), month.getMonthValue());
    }
}
