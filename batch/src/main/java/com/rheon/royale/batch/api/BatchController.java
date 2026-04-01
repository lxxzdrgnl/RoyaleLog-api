package com.rheon.royale.batch.api;

import com.rheon.royale.global.error.ApiResponse;
import com.rheon.royale.global.error.BusinessException;
import com.rheon.royale.global.error.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.step.tasklet.TaskletStep;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 배치 Job 수동 트리거 API
 *
 * [실행 정책]
 *   RUNNING   → JobExecutionAlreadyRunningException → 409 (JobLauncher가 직접 차단)
 *   FAILED    → 재실행 허용 (Airflow retry 가능)
 *   COMPLETED → 기본 거부 / force=true 시 runId 추가해 새 JobInstance 생성
 *
 * [파라미터 설계]
 *   - date: idempotent key (Airflow: ?date={{ ds }})
 *   - runId: force 재실행 시에만 추가 (System.currentTimeMillis())
 *   → timestamp를 기본 파라미터로 쓰면 매번 새 JobInstance 생성 → Airflow retry 중복 실행 버그
 *
 * [Airflow HttpOperator]
 *   endpoint="/api/v1/batch/collector?date={{ ds }}"   // 정상 실행
 *   endpoint="/api/v1/batch/collector?date={{ ds }}&force=true"  // 강제 재실행
 *
 * [RUNNING 중복 방지]
 *   사전 체크(pre-check) 제거 — race condition 있음
 *   실제 제어는 JobLauncher.run() 내부의 JobExecutionAlreadyRunningException에 위임
 *   → GlobalExceptionHandler가 409로 변환
 */
@Slf4j
@Tag(name = "Batch", description = "배치 Job 수동 트리거 (Airflow 연동 / 개발 테스트용)")
@RestController
@RequestMapping("/api/v1/batch")
@RequiredArgsConstructor
public class BatchController {

    private final JobLauncher jobLauncher;
    private final JobExplorer jobExplorer;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    private final com.rheon.royale.batch.collector.BracketBattleCounter bracketBattleCounter;

    @Qualifier("battleLogCollectorJob") private final Job battleLogCollectorJob;
    @Qualifier("deckAnalyzerJob")       private final Job deckAnalyzerJob;
    @Qualifier("cardSyncJob")           private final Job cardSyncJob;

    // ── 모니터링 ─────────────────────────────────────────────────────────

    @Operation(summary = "배치 실시간 모니터링 — 실행 중인 Job 자동 감지, 없으면 최신 완료 Job 반환")
    @GetMapping("/monitor")
    public ApiResponse<MonitorResponse> monitor() {
        // 1. STARTED 상태인 Job 찾기 (collector → analyzer → card-sync 순)
        for (String jobName : java.util.List.of("battleLogCollectorJob", "deckAnalyzerJob", "cardSyncJob")) {
            var running = jobExplorer.findRunningJobExecutions(jobName);
            if (!running.isEmpty()) {
                var exec = running.iterator().next();
                return ApiResponse.ok(buildMonitorResponse(jobName, exec));
            }
        }

        // 2. 실행 중인 게 없으면 가장 최근 완료된 Job 반환
        for (String jobName : java.util.List.of("battleLogCollectorJob", "deckAnalyzerJob", "cardSyncJob")) {
            var instances = jobExplorer.getJobInstances(jobName, 0, 1);
            if (!instances.isEmpty()) {
                var executions = jobExplorer.getJobExecutions(instances.get(0));
                if (!executions.isEmpty()) {
                    return ApiResponse.ok(buildMonitorResponse(jobName, executions.get(0)));
                }
            }
        }
        return ApiResponse.ok(null);
    }

    private MonitorResponse buildMonitorResponse(String jobName, org.springframework.batch.core.JobExecution exec) {
        var steps = exec.getStepExecutions().stream()
                .map(s -> {
                    Integer total = null;
                    if (s.getExecutionContext().containsKey("totalTarget")) {
                        total = s.getExecutionContext().getInt("totalTarget");
                    }
                    return new StepProgress(s.getStepName(), s.getStatus().name(),
                            (int) s.getReadCount(), (int) s.getWriteCount(), (int) s.getSkipCount(), total);
                })
                .toList();

        var counters = "battleLogCollectorJob".equals(jobName)
                ? bracketBattleCounter.snapshot().entrySet().stream()
                    .map(e -> new BracketCount(e.getKey(), e.getValue()[0], e.getValue()[1]))
                    .toList()
                : java.util.List.<BracketCount>of();

        return new MonitorResponse(
                jobName, exec.getId(), exec.getStatus().name(),
                exec.getStartTime() != null ? exec.getStartTime().toString() : null,
                exec.getEndTime() != null ? exec.getEndTime().toString() : null,
                steps, counters);
    }

    public record MonitorResponse(String jobName, Long executionId, String status, String startTime, String endTime,
                                  java.util.List<StepProgress> steps, java.util.List<BracketCount> brackets) {}
    public record StepProgress(String name, String status, int read, int write, int skip, Integer totalTarget) {}
    public record BracketCount(String bracket, int current, int limit) {}

    // ── DB 현황 ──────────────────────────────────────────────────────────

    @Operation(summary = "DB 현황 — 테이블 용량, 유저/배틀 통계, 디스크")
    @GetMapping("/db-stats")
    public ApiResponse<DbStats> dbStats() {
        return ApiResponse.ok(buildDbStats());
    }

    private DbStats buildDbStats() {
        // 테이블 용량
        var tables = jdbcTemplate.query("""
                SELECT tablename, pg_total_relation_size(schemaname||'.'||tablename) as bytes
                FROM pg_tables WHERE schemaname='public'
                ORDER BY bytes DESC LIMIT 15
                """, (rs, i) -> new TableSize(rs.getString("tablename"), rs.getLong("bytes")));

        // 브라켓별 유저 수
        var brackets = jdbcTemplate.query("""
                SELECT bracket, count(*) as cnt
                FROM players_to_crawl
                WHERE is_active = true AND bracket IS NOT NULL
                GROUP BY bracket ORDER BY bracket
                """, (rs, i) -> new BracketUserCount(rs.getString("bracket"), rs.getInt("cnt")));

        // 배틀 타입별 건수
        var battleTypes = jdbcTemplate.query("""
                SELECT battle_type, count(*) as cnt
                FROM battle_log_raw
                GROUP BY battle_type ORDER BY cnt DESC
                """, (rs, i) -> new BattleTypeCount(rs.getString("battle_type"), rs.getLong("cnt")));

        // 총 유저/배틀
        Integer totalPlayers = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM players_to_crawl WHERE is_active = true", Integer.class);
        Long totalBattles = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM battle_log_raw", Long.class);

        // 디스크
        Long diskFree = jdbcTemplate.queryForObject(
                "SELECT pg_database_size(current_database())", Long.class);

        return new DbStats(
                totalPlayers != null ? totalPlayers : 0,
                totalBattles != null ? totalBattles : 0,
                diskFree != null ? diskFree : 0,
                tables, brackets, battleTypes);
    }

    public record DbStats(int totalPlayers, long totalBattles, long dbSizeBytes,
                          java.util.List<TableSize> tables,
                          java.util.List<BracketUserCount> brackets,
                          java.util.List<BattleTypeCount> battleTypes) {}
    public record TableSize(String name, long bytes) {}
    public record BracketUserCount(String bracket, int count) {}
    public record BattleTypeCount(String type, long count) {}

    // ── 실행 ─────────────────────────────────────────────────────────────

    @Operation(summary = "배틀 로그 수집 Job 실행",
               description = "Airflow: ?date={{ ds }} | 재처리: &force=true | K-단계: &hashK=4&batchSeq=1")
    @PostMapping("/collector")
    public ApiResponse<JobResult> runCollector(
            @Parameter(description = "실행 날짜 (yyyy-MM-dd)")
            @RequestParam(required = false) String date,
            @Parameter(description = "COMPLETED 상태 강제 재실행")
            @RequestParam(defaultValue = "false") boolean force,
            @Parameter(description = "Airflow K-단계 전략용 배치 순번 (salt 용도)")
            @RequestParam(defaultValue = "0") String batchSeq,
            @Parameter(description = "샘플링 비율 분모 (K=10 → 10% 샘플링, K=1 → 전체)")
            @RequestParam(defaultValue = "10") String hashK) throws Exception {
        return ApiResponse.ok(launch("battleLogCollectorJob", battleLogCollectorJob, date, force, batchSeq, hashK));
    }

    @Operation(summary = "Analyzer Job 실행 (deck_dictionary / match_features / stats 집계)",
               description = "Airflow: ?date={{ ds }} | 재처리: &force=true")
    @PostMapping("/analyzer")
    public ApiResponse<JobResult> runAnalyzer(
            @RequestParam(required = false) String date,
            @RequestParam(defaultValue = "false") boolean force) throws Exception {
        return ApiResponse.ok(launch("deckAnalyzerJob", deckAnalyzerJob, date, force, "0", "10"));
    }

    @Operation(summary = "카드 메타 동기화 Job 실행 (주 1회)",
               description = "재처리: &force=true")
    @PostMapping("/card-sync")
    public ApiResponse<JobResult> runCardSync(
            @RequestParam(required = false) String date,
            @RequestParam(defaultValue = "false") boolean force) throws Exception {
        return ApiResponse.ok(launch("cardSyncJob", cardSyncJob, date, force, "0", "10"));
    }

    // ── 상태 조회 ─────────────────────────────────────────────────────────

    @Operation(summary = "실행 중인 Job 조회",
               description = "jobName 미입력 시 전체. 값: collector | analyzer | card-sync")
    @GetMapping("/status/running")
    public ApiResponse<List<JobResult>> getRunning(
            @RequestParam(required = false) String jobName) {
        String resolved = toJobName(jobName);
        if (resolved != null) {
            return ApiResponse.ok(toResults(jobExplorer.findRunningJobExecutions(resolved)));
        }
        // 전체 조회
        List<JobResult> all = new java.util.ArrayList<>();
        all.addAll(toResults(jobExplorer.findRunningJobExecutions("battleLogCollectorJob")));
        all.addAll(toResults(jobExplorer.findRunningJobExecutions("deckAnalyzerJob")));
        all.addAll(toResults(jobExplorer.findRunningJobExecutions("cardSyncJob")));
        return ApiResponse.ok(all);
    }

    @Operation(summary = "Job 실행 이력 (최근 10건)")
    @GetMapping("/history/{jobName}")
    public ApiResponse<List<JobResult>> getHistory(@PathVariable String jobName) {
        return ApiResponse.ok(
                jobExplorer.getJobInstances(jobName, 0, 10).stream()
                        .flatMap(i -> jobExplorer.getJobExecutions(i).stream())
                        .map(JobResult::from)
                        .toList()
        );
    }

    @Operation(summary = "Job 실행 상세 (readCount / writeCount / skipCount / exitMessage)")
    @GetMapping("/execution/{executionId}")
    public ApiResponse<ExecutionDetail> getExecution(@PathVariable Long executionId) {
        JobExecution execution = jobExplorer.getJobExecution(executionId);
        if (execution == null) {
            throw new BusinessException(ErrorCode.JOB_EXECUTION_NOT_FOUND);
        }
        return ApiResponse.ok(ExecutionDetail.from(execution));
    }

    // ── 내부 헬퍼 ─────────────────────────────────────────────────────────

    private JobResult launch(String jobName, Job job, String date, boolean force,
                             String batchSeq, String hashK) throws Exception {
        String resolvedDate = (date != null && !date.isBlank()) ? date : LocalDate.now().toString();

        // force라도 RUNNING이면 차단 — "재실행 허용"이지 "동시 실행 허용"이 아님
        boolean running = !jobExplorer.findRunningJobExecutions(jobName).isEmpty();
        if (running) {
            throw new org.springframework.batch.core.repository.JobExecutionAlreadyRunningException(
                    jobName + " is already running for date=" + resolvedDate);
        }

        JobParametersBuilder builder = new JobParametersBuilder()
                .addString("date", resolvedDate)
                .addString("startTime", LocalDateTime.now().toString())
                .addString("batchSeq", batchSeq)
                .addString("hashK", hashK);

        if (force) {
            // COMPLETED 재실행: runId 추가 → 새 JobInstance 생성
            builder.addLong("runId", System.currentTimeMillis());
            log.info("[Batch] FORCE launch job={} date={}", jobName, resolvedDate);
        } else {
            log.info("[Batch] launching job={} date={}", jobName, resolvedDate);
        }

        // COMPLETED(force 없음) → JobInstanceAlreadyCompleteException → GlobalExceptionHandler → 409
        JobExecution execution = jobLauncher.run(job, builder.toJobParameters());
        return JobResult.from(execution);
    }

    private List<JobResult> toResults(java.util.Set<JobExecution> executions) {
        return executions.stream().map(JobResult::from).toList();
    }

    /** "collector" → "battleLogCollectorJob" 등 alias 변환 */
    private String toJobName(String alias) {
        if (alias == null) return null;
        return switch (alias) {
            case "collector"  -> "battleLogCollectorJob";
            case "analyzer"   -> "deckAnalyzerJob";
            case "card-sync"  -> "cardSyncJob";
            default           -> alias; // 정확한 Job 이름도 허용
        };
    }

    // ── 응답 DTO ──────────────────────────────────────────────────────────

    public record JobResult(Long jobId, Long executionId, String status, String date, String startTime) {
        static JobResult from(JobExecution e) {
            return new JobResult(
                    e.getJobId(),
                    e.getId(),
                    e.getStatus().name(),
                    e.getJobParameters().getString("date"),
                    e.getStartTime() != null ? e.getStartTime().toString() : null
            );
        }
    }

    public record ExecutionDetail(
            Long executionId, String status,
            JobParams jobParameters,
            String startTime, String endTime,
            String exitCode, String exitMessage,
            List<StepSummary> steps,
            java.util.Map<String, Integer> bracketCounts  // 브라켓별 수집 건수 (Collector only)
    ) {
        static ExecutionDetail from(JobExecution e) {
            List<StepSummary> steps = e.getStepExecutions().stream()
                    .<StepSummary>map(s -> new StepSummary(
                            s.getStepName(),
                            s.getStatus().name(),
                            (int) s.getReadCount(),
                            (int) s.getWriteCount(),
                            (int) s.getSkipCount(),
                            s.getExitStatus().getExitDescription()
                    ))
                    .toList();

            // collectBattleLogStep의 ExecutionContext에서 bracket.* 추출
            var bracketCounts = new java.util.TreeMap<String, Integer>();
            e.getStepExecutions().stream()
                    .filter(s -> "collectBattleLogStep".equals(s.getStepName()))
                    .findFirst()
                    .ifPresent(s -> {
                        var ctx = s.getExecutionContext();
                        ctx.entrySet().forEach(entry -> {
                            if (entry.getKey().startsWith("bracket.")) {
                                bracketCounts.put(
                                        entry.getKey().substring(8),
                                        ((Number) entry.getValue()).intValue());
                            }
                        });
                    });

            return new ExecutionDetail(
                    e.getId(),
                    e.getStatus().name(),
                    new JobParams(
                            e.getJobParameters().getString("date"),
                            e.getJobParameters().getLong("runId")
                    ),
                    e.getStartTime() != null ? e.getStartTime().toString() : null,
                    e.getEndTime()   != null ? e.getEndTime().toString()   : null,
                    e.getExitStatus().getExitCode(),
                    e.getExitStatus().getExitDescription(),
                    steps,
                    bracketCounts.isEmpty() ? null : bracketCounts
            );
        }
    }

    public record JobParams(String date, Long runId) {}

    public record StepSummary(
            String stepName, String status,
            int readCount, int writeCount, int skipCount,
            String exitMessage
    ) {}
}
