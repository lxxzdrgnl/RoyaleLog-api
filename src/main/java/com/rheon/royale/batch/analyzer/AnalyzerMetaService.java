package com.rheon.royale.batch.analyzer;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * analyzer_meta 테이블에서 current_version을 동적으로 조회
 *
 * 왜 하드코딩하지 않나:
 *   - 분석 로직 변경 시 코드 재배포 없이 DB UPDATE 한 줄로 재처리 유발 가능
 *   - current_version 업 → analyzer_version < current_version 인 배틀 자동 재처리 대상 편입
 */
@Service
@RequiredArgsConstructor
public class AnalyzerMetaService {

    private final JdbcTemplate jdbcTemplate;

    public int currentVersion() {
        Integer version = jdbcTemplate.queryForObject(
                "SELECT current_version FROM analyzer_meta WHERE id = 1",
                Integer.class
        );
        if (version == null) {
            throw new IllegalStateException("analyzer_meta row not found — V2 migration 실행 여부 확인");
        }
        return version;
    }
}
