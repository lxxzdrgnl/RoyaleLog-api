package com.rheon.royale.batch.collector;

import com.rheon.royale.batch.collector.dto.PlayerBattleLogs;
import com.rheon.royale.domain.entity.PlayerToCrawl;
import com.rheon.royale.global.error.BusinessException;
import com.rheon.royale.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.database.JpaPagingItemReader;
import org.springframework.batch.item.database.builder.JpaPagingItemReaderBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import jakarta.persistence.EntityManagerFactory;
import java.util.Map;

@Configuration
@RequiredArgsConstructor
public class CollectorJobConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final EntityManagerFactory entityManagerFactory;

    private final SeasonIdTasklet seasonIdTasklet;
    private final PartitionManagerTasklet partitionManagerTasklet;
    private final SyncRankingTasklet syncRankingTasklet;
    private final CollectBattleLogProcessor collectBattleLogProcessor;
    private final CollectBattleLogWriter collectBattleLogWriter;

    @Bean
    public Job battleLogCollectorJob() {
        return new JobBuilder("battleLogCollectorJob", jobRepository)
                .start(seasonIdStep())
                .next(partitionManagerStep())
                .next(syncRankingStep())
                .next(collectBattleLogStep())
                .build();
    }

    @Bean
    public Step seasonIdStep() {
        return new StepBuilder("seasonIdStep", jobRepository)
                .tasklet(seasonIdTasklet, transactionManager)
                .build();
    }

    @Bean
    public Step partitionManagerStep() {
        return new StepBuilder("partitionManagerStep", jobRepository)
                .tasklet(partitionManagerTasklet, transactionManager)
                .build();
    }

    @Bean
    public Step syncRankingStep() {
        return new StepBuilder("syncRankingStep", jobRepository)
                .tasklet(syncRankingTasklet, transactionManager)
                .build();
    }

    /**
     * Chunk(10): 10명씩 읽고 → API 호출 → DB 저장 → 커밋
     * FaultTolerant:
     *   - Retry(3): 429, 5xx (BusinessException RATE_LIMIT / API_ERROR)
     *   - Skip(50): 404, 400 (PLAYER_NOT_FOUND 등)
     */
    @Bean
    public Step collectBattleLogStep() {
        return new StepBuilder("collectBattleLogStep", jobRepository)
                .<PlayerToCrawl, PlayerBattleLogs>chunk(10, transactionManager)
                .reader(playerToCrawlReader())
                .processor(collectBattleLogProcessor)
                .writer(collectBattleLogWriter)
                .faultTolerant()
                .retryLimit(3)
                .retry(BusinessException.class)  // RATE_LIMIT / API_ERROR → ClashRoyaleClient retrySpec 처리
                .skipLimit(50)
                .skip(BusinessException.class)   // PLAYER_NOT_FOUND(404) 등 개별 실패는 Skip
                .build();
    }

    @Bean
    public JpaPagingItemReader<PlayerToCrawl> playerToCrawlReader() {
        return new JpaPagingItemReaderBuilder<PlayerToCrawl>()
                .name("playerToCrawlReader")
                .entityManagerFactory(entityManagerFactory)
                .pageSize(10)
                .queryString("""
                        SELECT p FROM PlayerToCrawl p
                        WHERE p.isActive = true
                        ORDER BY p.lastCrawledAt ASC NULLS FIRST, p.currentRank ASC
                        """)
                .parameterValues(Map.of())
                .build();
    }
}
