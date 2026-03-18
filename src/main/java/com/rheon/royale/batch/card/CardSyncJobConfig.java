package com.rheon.royale.batch.card;

import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@RequiredArgsConstructor
public class CardSyncJobConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final CardSyncTasklet cardSyncTasklet;

    @Bean
    public Job cardSyncJob() {
        return new JobBuilder("cardSyncJob", jobRepository)
                .start(cardSyncStep())
                .build();
    }

    @Bean
    public Step cardSyncStep() {
        return new StepBuilder("cardSyncStep", jobRepository)
                .tasklet(cardSyncTasklet, transactionManager)
                .build();
    }
}
