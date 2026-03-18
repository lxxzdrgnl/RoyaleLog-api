package com.rheon.royale.batch.analyzer;

import com.rheon.royale.batch.analyzer.dto.AnalyzedBattle;
import com.rheon.royale.domain.entity.BattleLogRaw;
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
public class AnalyzerJobConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final EntityManagerFactory entityManagerFactory;
    private final DeckAnalyzerProcessor deckAnalyzerProcessor;
    private final DeckAnalyzerWriter deckAnalyzerWriter;
    private final StatsOverwriteTasklet statsOverwriteTasklet;
    private final AnalyzerMetaService analyzerMetaService;

    /**
     * Analyzer Job 2-Step 구성
     *
     * Step 1: deck_dictionary + match_features 적재
     *   - analyzer_version < CURRENT_VERSION 인 배틀만 처리
     *   - 중간 실패 후 재실행 시 이미 처리된 배틀은 자동 Skip
     *
     * Step 2: stats_decks_daily overwrite
     *   - TRUNCATE + INSERT in transaction → deterministic
     *   - Dirty Read 방어: 트랜잭션 COMMIT 전까지 유저는 기존 통계 조회
     */
    @Bean
    public Job deckAnalyzerJob() {
        return new JobBuilder("deckAnalyzerJob", jobRepository)
                .start(deckAnalyzerStep())
                .next(statsOverwriteStep())
                .build();
    }

    @Bean
    public Step deckAnalyzerStep() {
        return new StepBuilder("deckAnalyzerStep", jobRepository)
                .<BattleLogRaw, AnalyzedBattle>chunk(50, transactionManager)
                .reader(battleLogRawReader())
                .processor(deckAnalyzerProcessor)
                .writer(deckAnalyzerWriter)
                .faultTolerant()
                .skipLimit(100)
                .skip(Exception.class)
                .build();
    }

    @Bean
    public Step statsOverwriteStep() {
        return new StepBuilder("statsOverwriteStep", jobRepository)
                .tasklet(statsOverwriteTasklet, transactionManager)
                .build();
    }

    /**
     * analyzer_version < current_version 조건 (DB에서 동적 조회)
     *   - 0 < 1 → 미처리 (최초 실행)
     *   - 1 < 2 → 이전 버전 처리 배틀 재처리 (로직 변경 시 analyzer_meta UPDATE 한 줄로 해결)
     *   - 현재 버전으로 처리된 배틀은 읽지 않음
     */
    @Bean
    public JpaPagingItemReader<BattleLogRaw> battleLogRawReader() {
        return new JpaPagingItemReaderBuilder<BattleLogRaw>()
                .name("battleLogRawReader")
                .entityManagerFactory(entityManagerFactory)
                .pageSize(50)
                .queryString("""
                        SELECT b FROM BattleLogRaw b
                        WHERE b.analyzerVersion < :currentVersion
                        ORDER BY b.id.createdAt ASC
                        """)
                .parameterValues(Map.of("currentVersion", analyzerMetaService.currentVersion()))
                .build();
    }
}
