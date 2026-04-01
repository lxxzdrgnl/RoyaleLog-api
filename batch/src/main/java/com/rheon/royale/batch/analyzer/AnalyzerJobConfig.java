package com.rheon.royale.batch.analyzer;

import com.rheon.royale.batch.analyzer.dto.AnalyzedBattle;
import com.rheon.royale.domain.entity.BattleLogRaw;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import com.rheon.royale.domain.entity.BattleLogRawId;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.batch.item.support.SynchronizedItemStreamReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.LocalDateTime;

@Configuration
@RequiredArgsConstructor
public class AnalyzerJobConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final DataSource dataSource;
    private final DeckAnalyzerProcessor deckAnalyzerProcessor;
    private final DeckAnalyzerWriter deckAnalyzerWriter;
    private final StatsOverwriteTasklet statsOverwriteTasklet;
    private final AnalyzerMetaService analyzerMetaService;
    private final AnalyzerProgressListener analyzerProgressListener;
    private final com.rheon.royale.batch.collector.PartitionManagerTasklet partitionManagerTasklet;

    /**
     * Analyzer Job 3-Step 구성
     *
     * Step 0: 파티션 자동 생성 (match_features 포함)
     * Step 1: deck_dictionary + match_features 적재
     * Step 2: stats_decks_daily overwrite (Rename Swap)
     */
    @Bean
    public Job deckAnalyzerJob() {
        return new JobBuilder("deckAnalyzerJob", jobRepository)
                .start(analyzerPartitionStep())
                .next(deckAnalyzerStep())
                .next(statsOverwriteStep())
                .build();
    }

    @Bean
    public Step analyzerPartitionStep() {
        return new StepBuilder("analyzerPartitionStep", jobRepository)
                .tasklet(partitionManagerTasklet, transactionManager)
                .build();
    }

    @Bean
    public Step deckAnalyzerStep() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(4);
        executor.setThreadNamePrefix("analyzer-");
        executor.initialize();

        return new StepBuilder("deckAnalyzerStep", jobRepository)
                .<BattleLogRaw, AnalyzedBattle>chunk(500, transactionManager)
                .reader(synchronizedBattleLogReader())
                .processor(deckAnalyzerProcessor)
                .writer(deckAnalyzerWriter)
                .taskExecutor(executor)
                .faultTolerant()
                .skipLimit(100)
                .skip(Exception.class)
                .listener(analyzerProgressListener)
                .build();
    }

    @Bean
    public Step statsOverwriteStep() {
        return new StepBuilder("statsOverwriteStep", jobRepository)
                .tasklet(statsOverwriteTasklet, transactionManager)
                .build();
    }

    @Bean
    public SynchronizedItemStreamReader<BattleLogRaw> synchronizedBattleLogReader() {
        SynchronizedItemStreamReader<BattleLogRaw> reader = new SynchronizedItemStreamReader<>();
        reader.setDelegate(battleLogRawReader());
        return reader;
    }

    /**
     * JdbcCursorItemReader — JPA 대신 JDBC 직접 사용
     *
     * JpaCursorItemReader 문제: Hibernate 1차 캐시에 엔티티 누적 → 1500만건 OOM
     * JdbcCursorItemReader: ResultSet 스트리밍 → 메모리 일정하게 유지 (fetchSize 단위 버퍼)
     *
     * analyzer_version < current_version 조건
     *   - 0 < 1 → 미처리, 1 < 2 → 이전 버전 재처리
     */
    @Bean
    public JdbcCursorItemReader<BattleLogRaw> battleLogRawReader() {
        int currentVersion = analyzerMetaService.currentVersion();
        return new JdbcCursorItemReaderBuilder<BattleLogRaw>()
                .name("battleLogRawReader")
                .dataSource(dataSource)
                .fetchSize(500)
                .sql("""
                        SELECT battle_id, created_at, player_tag, battle_type, raw_json, analyzer_version
                        FROM battle_log_raw
                        WHERE analyzer_version < %d
                          AND created_at >= CURRENT_DATE - 8
                        ORDER BY created_at ASC, battle_id ASC
                        """.formatted(currentVersion))
                .rowMapper((rs, rowNum) -> BattleLogRaw.builder()
                        .id(new BattleLogRawId(rs.getString("battle_id"),
                                rs.getObject("created_at", LocalDateTime.class)))
                        .playerTag(rs.getString("player_tag"))
                        .battleType(rs.getString("battle_type"))
                        .rawJson(rs.getString("raw_json"))
                        .build()
                )
                .build();
    }
}
