package com.rheon.royale.batch.collector;

import com.rheon.royale.batch.collector.dto.PlayerBattleLogs;
import com.rheon.royale.domain.entity.PlayerToCrawl;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.integration.async.AsyncItemProcessor;
import org.springframework.batch.integration.async.AsyncItemWriter;
import org.springframework.batch.item.database.JpaPagingItemReader;
import org.springframework.batch.item.database.builder.JpaPagingItemReaderBuilder;
import org.springframework.batch.item.support.SynchronizedItemStreamReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import jakarta.persistence.EntityManagerFactory;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.Future;

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
    private final CollectBattleLogSkipListener collectBattleLogSkipListener;

    @Bean
    public Job battleLogCollectorJob(Step collectBattleLogStep) {
        return new JobBuilder("battleLogCollectorJob", jobRepository)
                .start(seasonIdStep())
                .next(partitionManagerStep())
                .next(syncRankingStep())
                .next(collectBattleLogStep)
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
     * AsyncItemProcessor (20 threads): API 호출 병렬화
     * AsyncItemWriter: Future 결과 모아서 단일 스레드 Writer 위임 → DB 직렬 쓰기, 데드락 없음
     * FaultTolerant 제거: Spring Batch AsyncItemProcessor + FaultTolerant 조합 미지원
     *   → 예외 처리는 CollectBattleLogProcessor 내부에서 직접 핸들링
     *   → 404/5xx: Processor catch → null 반환 (Spring Batch 자동 skip)
     *   → 429/5xx retry: ClashRoyaleClient 내부 retrySpec 처리
     */
    @Bean
    public Step collectBattleLogStep(SynchronizedItemStreamReader<PlayerToCrawl> synchronizedPlayerReader) {
        return new StepBuilder("collectBattleLogStep", jobRepository)
                .<PlayerToCrawl, Future<PlayerBattleLogs>>chunk(100, transactionManager)
                .reader(synchronizedPlayerReader)
                .processor(asyncProcessor())
                .writer(asyncWriter())
                .build();
    }

    @Bean
    public AsyncItemProcessor<PlayerToCrawl, PlayerBattleLogs> asyncProcessor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(35);
        executor.setMaxPoolSize(35);
        executor.setThreadNamePrefix("collector-");
        executor.initialize();

        AsyncItemProcessor<PlayerToCrawl, PlayerBattleLogs> processor = new AsyncItemProcessor<>();
        processor.setDelegate(collectBattleLogProcessor);
        processor.setTaskExecutor(executor);
        return processor;
    }

    @Bean
    public AsyncItemWriter<PlayerBattleLogs> asyncWriter() {
        AsyncItemWriter<PlayerBattleLogs> writer = new AsyncItemWriter<>();
        writer.setDelegate(collectBattleLogWriter);
        return writer;
    }

    @Bean
    @StepScope
    public SynchronizedItemStreamReader<PlayerToCrawl> synchronizedPlayerReader(
            @Value("#{jobParameters['startTime']}") String startTime) {
        SynchronizedItemStreamReader<PlayerToCrawl> reader = new SynchronizedItemStreamReader<>();
        reader.setDelegate(playerToCrawlReader(startTime));
        return reader;
    }

    @Bean
    @StepScope
    public JpaPagingItemReader<PlayerToCrawl> playerToCrawlReader(
            @Value("#{jobParameters['startTime']}") String startTime) {
        LocalDateTime jobStartTime = LocalDateTime.parse(startTime);
        return new JpaPagingItemReaderBuilder<PlayerToCrawl>()
                .name("playerToCrawlReader")
                .entityManagerFactory(entityManagerFactory)
                .pageSize(100)
                .saveState(false)  // AsyncItemProcessor 환경에서 상태 저장 비활성화
                .queryString("""
                        SELECT p FROM PlayerToCrawl p
                        WHERE p.isActive = true
                        AND (p.lastCrawledAt IS NOT NULL OR p.updatedAt < :jobStartTime)
                        ORDER BY p.lastCrawledAt ASC NULLS FIRST, p.currentRank ASC
                        """)
                .parameterValues(Map.of("jobStartTime", jobStartTime))
                .build();
    }
}
