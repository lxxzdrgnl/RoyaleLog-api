package com.rheon.royale.batch.collector;

import com.rheon.royale.domain.entity.PlayerToCrawl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.SkipListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Skip 발생 시 DLQ 테이블에 기록 + 로그 경보
 *
 * 대상:
 *   - DataIntegrityViolationException: 파티션 없음 등 DB 제약 위반 → "COMPLETED 성공!" 침묵 방지
 *   - BusinessException(skip 대상): API 404/400 등 일시적 실패
 *
 * DLQ 테이블: batch_skip_log (V5 Flyway로 생성)
 *   - 슬랙/이메일 알림을 붙일 경우 이 클래스에 추가
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CollectBattleLogSkipListener implements SkipListener<PlayerToCrawl, Object> {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void onSkipInRead(Throwable t) {
        log.warn("[SkipListener] Reader skip: {}", t.getMessage());
    }

    @Override
    public void onSkipInProcess(PlayerToCrawl player, Throwable t) {
        String reason = t.getClass().getSimpleName() + ": " + t.getMessage();
        log.warn("[SkipListener] Processor skip — player={}, reason={}", player.getPlayerTag(), reason);
        insertDlq(player.getPlayerTag(), "PROCESS", reason);
    }

    @Override
    public void onSkipInWrite(Object item, Throwable t) {
        boolean isDbConstraint = t instanceof DataIntegrityViolationException;
        String reason = t.getClass().getSimpleName() + ": " + t.getMessage();

        if (isDbConstraint) {
            // 파티션 없음 등 구조적 오류 → ERROR 레벨로 더 강하게 경보
            log.error("[SkipListener] Writer skip (DB 제약 위반 — 파티션 미생성 가능성!) reason={}", reason);
        } else {
            log.warn("[SkipListener] Writer skip: {}", reason);
        }
        insertDlq(item.toString(), "WRITE", reason);
    }

    private void insertDlq(String itemKey, String phase, String reason) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO batch_skip_log (item_key, phase, reason, skipped_at)
                    VALUES (?, ?, ?, NOW())
                    """, itemKey, phase, reason);
        } catch (Exception e) {
            // DLQ 저장 실패가 본 로직에 영향을 주지 않도록 예외 흡수
            log.error("[SkipListener] DLQ 저장 실패: {}", e.getMessage());
        }
    }
}
