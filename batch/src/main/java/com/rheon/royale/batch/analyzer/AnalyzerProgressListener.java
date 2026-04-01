package com.rheon.royale.batch.analyzer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Analyzer Step 시작 시 총 대상 건수를 카운트해서 ExecutionContext에 저장.
 * 모니터 API에서 "현재 처리 / 총 대상" 진행률 표시에 사용.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalyzerProgressListener implements StepExecutionListener {

    private final JdbcTemplate jdbcTemplate;
    private final AnalyzerMetaService analyzerMetaService;

    @Override
    public void beforeStep(StepExecution stepExecution) {
        int version = analyzerMetaService.currentVersion();
        Integer total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM battle_log_raw WHERE analyzer_version < ? AND created_at >= CURRENT_DATE - 8",
                Integer.class, version);
        if (total == null) total = 0;

        stepExecution.getExecutionContext().putInt("totalTarget", total);
        log.info("[AnalyzerProgress] 분석 대상: {}건 (version < {})", total, version);
    }

    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        long read = stepExecution.getReadCount();
        long write = stepExecution.getWriteCount();
        log.info("[AnalyzerProgress] 완료 — read: {}, write: {}", read, write);
        return null;
    }
}
