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

    @Qualifier("battleLogCollectorJob") private final Job battleLogCollectorJob;
    @Qualifier("deckAnalyzerJob")       private final Job deckAnalyzerJob;
    @Qualifier("cardSyncJob")           private final Job cardSyncJob;

    // ── 실행 ─────────────────────────────────────────────────────────────

    @Operation(summary = "배틀 로그 수집 Job 실행",
               description = "Airflow: ?date={{ ds }} | 재처리: &force=true")
    @PostMapping("/collector")
    public ApiResponse<JobResult> runCollector(
            @Parameter(description = "실행 날짜 (yyyy-MM-dd)")
            @RequestParam(required = false) String date,
            @Parameter(description = "COMPLETED 상태 강제 재실행")
            @RequestParam(defaultValue = "false") boolean force) throws Exception {
        return ApiResponse.ok(launch("battleLogCollectorJob", battleLogCollectorJob, date, force));
    }

    @Operation(summary = "Analyzer Job 실행 (deck_dictionary / match_features / stats 집계)",
               description = "Airflow: ?date={{ ds }} | 재처리: &force=true")
    @PostMapping("/analyzer")
    public ApiResponse<JobResult> runAnalyzer(
            @RequestParam(required = false) String date,
            @RequestParam(defaultValue = "false") boolean force) throws Exception {
        return ApiResponse.ok(launch("deckAnalyzerJob", deckAnalyzerJob, date, force));
    }

    @Operation(summary = "카드 메타 동기화 Job 실행 (주 1회)",
               description = "재처리: &force=true")
    @PostMapping("/card-sync")
    public ApiResponse<JobResult> runCardSync(
            @RequestParam(required = false) String date,
            @RequestParam(defaultValue = "false") boolean force) throws Exception {
        return ApiResponse.ok(launch("cardSyncJob", cardSyncJob, date, force));
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

    private JobResult launch(String jobName, Job job, String date, boolean force) throws Exception {
        String resolvedDate = (date != null && !date.isBlank()) ? date : LocalDate.now().toString();

        // force라도 RUNNING이면 차단 — "재실행 허용"이지 "동시 실행 허용"이 아님
        boolean running = !jobExplorer.findRunningJobExecutions(jobName).isEmpty();
        if (running) {
            throw new org.springframework.batch.core.repository.JobExecutionAlreadyRunningException(
                    jobName + " is already running for date=" + resolvedDate);
        }

        JobParametersBuilder builder = new JobParametersBuilder()
                .addString("date", resolvedDate)
                .addString("startTime", LocalDateTime.now().toString());

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
            JobParams jobParameters,         // date 포함 — Airflow/로그 없이도 어느 날짜 작업인지 확인
            String startTime, String endTime,
            String exitCode, String exitMessage,
            List<StepSummary> steps
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

            return new ExecutionDetail(
                    e.getId(),
                    e.getStatus().name(),
                    new JobParams(
                            e.getJobParameters().getString("date"),
                            e.getJobParameters().getLong("runId")  // force 재실행 시에만 존재
                    ),
                    e.getStartTime() != null ? e.getStartTime().toString() : null,
                    e.getEndTime()   != null ? e.getEndTime().toString()   : null,
                    e.getExitStatus().getExitCode(),
                    e.getExitStatus().getExitDescription(),
                    steps
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
