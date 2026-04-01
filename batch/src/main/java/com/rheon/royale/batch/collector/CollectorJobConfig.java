package com.rheon.royale.batch.collector;

import com.rheon.royale.batch.collector.dto.PlayerBattleLogs;
import com.rheon.royale.domain.entity.PlayerToCrawl;
import com.rheon.royale.domain.repository.PlayerToCrawlRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.integration.async.AsyncItemProcessor;
import org.springframework.batch.integration.async.AsyncItemWriter;
import org.springframework.batch.item.support.SynchronizedItemStreamReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.concurrent.Future;

@Configuration
@RequiredArgsConstructor
public class CollectorJobConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    private final SeasonIdTasklet seasonIdTasklet;
    private final PartitionManagerTasklet partitionManagerTasklet;
    private final SyncRankingTasklet syncRankingTasklet;
    private final CollectBattleLogProcessor collectBattleLogProcessor;
    private final CollectBattleLogWriter collectBattleLogWriter;
    private final CollectBattleLogSkipListener collectBattleLogSkipListener;
    private final BracketBattleCounter bracketBattleCounter;
    private final PlayerToCrawlRepository playerToCrawlRepository;

    @Bean
    public Job battleLogCollectorJob(Step collectBattleLogStep) {
        return new JobBuilder("battleLogCollectorJob", jobRepository)
                .start(seasonIdStep())
                .next(partitionManagerStep())
                .next(syncRankingStep())
                .next(dropIndexStep())
                .next(collectBattleLogStep)
                .next(createIndexStep())
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

    @Bean
    public Step dropIndexStep() {
        return new StepBuilder("dropIndexStep", jobRepository)
                .tasklet(new IndexManagementTasklet(jdbcTemplate, IndexManagementTasklet.Mode.DROP), transactionManager)
                .build();
    }

    @Bean
    public Step createIndexStep() {
        return new StepBuilder("createIndexStep", jobRepository)
                .tasklet(new IndexManagementTasklet(jdbcTemplate, IndexManagementTasklet.Mode.CREATE), transactionManager)
                .build();
    }

    /**
     * AsyncItemProcessor (50 threads): API 호출 병렬화
     * AsyncItemWriter: Future 결과 모아서 단일 스레드 Writer 위임 → DB 직렬 쓰기, 데드락 없음
     *
     * chunk 500: DB commit 횟수 줄여 write 병목 완화
     * synchronous_commit=off: WAL 동기화 끄기 → write 2~3배 가속 (배치 데이터 재수집 가능하므로 허용)
     */
    @Bean
    public Step collectBattleLogStep(
            SynchronizedItemStreamReader<PlayerToCrawl> synchronizedPlayerReader) {
        return new StepBuilder("collectBattleLogStep", jobRepository)
                .<PlayerToCrawl, Future<PlayerBattleLogs>>chunk(500, transactionManager)
                .reader(synchronizedPlayerReader)
                .processor(asyncProcessor())
                .writer(asyncWriter())
                .listener(bracketBattleCounter)
                .build();
    }

    @Bean
    @StepScope
    public SynchronizedItemStreamReader<PlayerToCrawl> synchronizedPlayerReader(
            @Value("#{jobParameters['startTime']}") String startTime,
            @Value("#{jobParameters['batchSeq'] ?: '0'}") String batchSeq,
            @Value("#{jobParameters['hashK'] ?: '10'}") String hashKStr,
            @Value("${collector.max-players-per-bracket:20000}") int maxPlayersPerBracket) {

        BracketAwarePlayerReader bracketReader = new BracketAwarePlayerReader(
                bracketBattleCounter, playerToCrawlRepository,
                startTime, batchSeq, hashKStr, maxPlayersPerBracket, dataSource);

        SynchronizedItemStreamReader<PlayerToCrawl> reader = new SynchronizedItemStreamReader<>();
        reader.setDelegate(bracketReader);
        return reader;
    }

    @Bean
    public AsyncItemProcessor<PlayerToCrawl, PlayerBattleLogs> asyncProcessor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(50);
        executor.setMaxPoolSize(50);
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
}
