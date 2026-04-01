package com.rheon.royale.batch.analyzer;

import com.rheon.royale.batch.analyzer.dto.AnalyzedBattle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeckAnalyzerWriter implements ItemWriter<AnalyzedBattle> {

    private final JdbcTemplate jdbcTemplate;
    private final AnalyzerMetaService analyzerMetaService;
    private final AnalyzerPersistenceService analyzerPersistenceService;

    @Override
    @Transactional
    public void write(Chunk<? extends AnalyzedBattle> chunk) throws Exception {
        int currentVersion = analyzerMetaService.currentVersion();
        var items = chunk.getItems();

        // WAL 동기화 비활성화 — 배치 전용 최적화
        jdbcTemplate.execute("SET LOCAL synchronous_commit = off");

        analyzerPersistenceService.batchInsertMatchFeatures(items);
        analyzerPersistenceService.batchMarkProcessed(items, currentVersion);

        log.debug("[DeckAnalyzerWriter] {}건 처리", items.size());
    }
}
