package com.rheon.royale.batch.collector;

import com.rheon.royale.global.error.BusinessException;
import com.rheon.royale.global.error.ErrorCode;
import com.rheon.royale.infrastructure.external.clashroyale.ClashRoyaleClient;
import com.rheon.royale.infrastructure.external.clashroyale.dto.CrSeasonListResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SeasonIdTasklet implements Tasklet {

    public static final String KEY_SEASON_ID = "currentSeasonId";

    private final ClashRoyaleClient clashRoyaleClient;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        CrSeasonListResponse response = clashRoyaleClient.getSeasons();
        String seasonId = response.currentSeasonId();

        if (seasonId == null || seasonId.isBlank()) {
            throw new BusinessException(ErrorCode.CLASH_API_ERROR);
        }

        ExecutionContext jobCtx = chunkContext.getStepContext()
                .getStepExecution()
                .getJobExecution()
                .getExecutionContext();
        jobCtx.putString(KEY_SEASON_ID, seasonId);

        log.info("[CollectorJob] 현재 시즌 ID: {}", seasonId);
        return RepeatStatus.FINISHED;
    }
}
