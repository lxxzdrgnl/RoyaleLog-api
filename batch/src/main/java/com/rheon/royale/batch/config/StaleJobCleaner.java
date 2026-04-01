package com.rheon.royale.batch.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 서버 시작 시 STARTED/STARTING 상태로 남은 고아 Job Execution을 FAILED로 정리.
 *
 * 원인: JVM 강제 종료(kill -9, OOM, 서버 재시작) 시 Spring Batch가
 *       정상 종료 훅을 실행하지 못해 DB에 STARTED 상태가 영구적으로 남음.
 * 영향: 다음 실행 시 "already running" 오류로 Job 실행 불가.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StaleJobCleaner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        int updated = jdbcTemplate.update("""
                UPDATE batch_job_execution
                SET status    = 'FAILED',
                    exit_code = 'FAILED',
                    end_time  = NOW(),
                    exit_message = 'Marked FAILED by StaleJobCleaner on server restart'
                WHERE status IN ('STARTED', 'STARTING')
                """);

        if (updated > 0) {
            log.warn("[StaleJobCleaner] 고아 실행 {}건을 FAILED로 정리", updated);
        }
    }
}
