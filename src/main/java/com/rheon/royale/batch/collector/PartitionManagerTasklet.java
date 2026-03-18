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

/**
 * 월별 파티션 자동 관리 Tasklet
 * - 당월 + 익월 파티션 생성 (IF NOT EXISTS)
 * - 90일 이전(3개월 전) 파티션 DROP
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PartitionManagerTasklet implements Tasklet {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        LocalDate today = LocalDate.now();

        createPartitionIfAbsent(today);
        createPartitionIfAbsent(today.plusMonths(1));
        dropOldPartitionIfExists(today.minusMonths(3));

        return RepeatStatus.FINISHED;
    }

    private void createPartitionIfAbsent(LocalDate month) {
        String partitionName = partitionName(month);
        LocalDate start = month.withDayOfMonth(1);
        LocalDate end = start.plusMonths(1);

        String sql = String.format("""
                CREATE TABLE IF NOT EXISTS %s PARTITION OF battle_log_raw
                FOR VALUES FROM ('%s') TO ('%s')
                """,
                partitionName,
                start.format(DateTimeFormatter.ISO_LOCAL_DATE),
                end.format(DateTimeFormatter.ISO_LOCAL_DATE));

        jdbcTemplate.execute(sql);
        log.info("[PartitionManager] 파티션 확인/생성: {}", partitionName);
    }

    private void dropOldPartitionIfExists(LocalDate month) {
        String partitionName = partitionName(month);
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + partitionName);
        log.info("[PartitionManager] 오래된 파티션 DROP (없으면 무시): {}", partitionName);
    }

    private String partitionName(LocalDate month) {
        return String.format("battle_log_raw_%d_%02d", month.getYear(), month.getMonthValue());
    }
}
