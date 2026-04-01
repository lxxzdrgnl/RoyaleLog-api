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
import java.util.Map;
import java.util.stream.Stream;

/**
 * 월별 파티션 자동 관리 Tasklet
 *
 * 보존 정책:
 *   - battle_log_raw : 파티션 생성만, 삭제 없음 (영구 보관)
 *   - match_features : 8일 초과 데이터 삭제 + 빈 파티션 DROP
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PartitionManagerTasklet implements Tasklet {

    /** 파티션 생성만, 절대 DROP 하지 않음 */
    private static final List<String> CREATE_ONLY_TABLES = List.of("battle_log_raw");

    /** 8일 보존: 데이터 삭제 + 빈 파티션 DROP. 값 = 날짜 컬럼명 */
    private static final Map<String, String> RETENTION_TABLES = Map.of(
            "match_features", "battle_date"
    );

    private final JdbcTemplate jdbcTemplate;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        LocalDate today = LocalDate.now();

        // 전월 ~ 익월 파티션 생성 (모든 테이블 공통)
        Stream.concat(CREATE_ONLY_TABLES.stream(), RETENTION_TABLES.keySet().stream())
                .forEach(table -> {
                    createPartitionIfAbsent(table, today.minusMonths(1));
                    createPartitionIfAbsent(table, today);
                    createPartitionIfAbsent(table, today.plusMonths(1));
                });

        // 8일 초과 데이터 삭제 + 빈 파티션 DROP + VACUUM
        for (Map.Entry<String, String> entry : RETENTION_TABLES.entrySet()) {
            int deleted = pruneOldData(entry.getKey(), entry.getValue());
            dropEmptyPartitions(entry.getKey());
            if (deleted > 0) {
                vacuumTable(entry.getKey());
            }
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

    private int pruneOldData(String table, String dateColumn) {
        int deleted = jdbcTemplate.update(
                "DELETE FROM " + table + " WHERE " + dateColumn + " < CURRENT_DATE - 8");
        log.info("[PartitionManager] {} 8일 초과 데이터 삭제: {}건", table, deleted);
        return deleted;
    }

    /** DELETE 후 dead tuple 공간 회수. VACUUM (non-FULL)은 트랜잭션 밖에서 실행해야 함. */
    private void vacuumTable(String table) {
        jdbcTemplate.execute("VACUUM " + table);
        log.info("[PartitionManager] {} VACUUM 완료", table);
    }

    private void dropEmptyPartitions(String table) {
        LocalDate thisMonth = LocalDate.now().withDayOfMonth(1);

        // YYYY_MM 패턴 파티션만 조회 (stats_decks_daily_current 같은 비파티션 테이블 제외)
        List<String> partitions = jdbcTemplate.queryForList("""
                SELECT tablename FROM pg_tables
                WHERE schemaname = 'public'
                  AND tablename ~ ?
                """,
                String.class, "^" + table + "_[0-9]{4}_[0-9]{2}$");

        for (String partition : partitions) {
            // 당월 이후 파티션은 건너뜀 (방금 생성했을 수 있음)
            if (!isBeforeThisMonth(partition, table, thisMonth)) continue;

            Boolean isEmpty = jdbcTemplate.queryForObject(
                    "SELECT NOT EXISTS (SELECT 1 FROM \"" + partition + "\" LIMIT 1)", Boolean.class);
            if (Boolean.TRUE.equals(isEmpty)) {
                jdbcTemplate.execute("DROP TABLE \"" + partition + "\"");
                log.info("[PartitionManager] 빈 파티션 DROP: {}", partition);
            }
        }
    }

    /** 파티션명(table_YYYY_MM)에서 연월을 파싱해 당월보다 이전인지 확인 */
    private boolean isBeforeThisMonth(String partitionName, String table, LocalDate thisMonth) {
        String suffix = partitionName.substring(table.length() + 1); // "YYYY_MM"
        String[] parts = suffix.split("_");
        if (parts.length != 2) return false;
        try {
            LocalDate partitionMonth = LocalDate.of(
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    1);
            return partitionMonth.isBefore(thisMonth);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String partitionName(String table, LocalDate month) {
        return String.format("%s_%d_%02d", table, month.getYear(), month.getMonthValue());
    }
}
