package com.rheon.royale.batch.collector;

import com.rheon.royale.domain.repository.PlayerToCrawlRepository;
import com.rheon.royale.infrastructure.external.clashroyale.ClashRoyaleClient;
import com.rheon.royale.infrastructure.external.clashroyale.dto.CrRankingPlayer;
import com.rheon.royale.infrastructure.external.clashroyale.dto.CrRankingResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class SyncRankingTasklet implements Tasklet {

    private final ClashRoyaleClient clashRoyaleClient;
    private final PlayerToCrawlRepository playerToCrawlRepository;

    @Override
    @Transactional
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        String seasonId = chunkContext.getStepContext()
                .getStepExecution()
                .getJobExecution()
                .getExecutionContext()
                .getString(SeasonIdTasklet.KEY_SEASON_ID);

        // 1. 전체 랭킹 수집 (커서 페이지네이션)
        List<CrRankingPlayer> allPlayers = fetchAllRankedPlayers(seasonId);
        log.info("[SyncRanking] 수집된 랭커 수: {}", allPlayers.size());

        // 2. 현재 랭킹 플레이어 UPSERT (current_rank, name 갱신 + is_active=true 보장)
        //    deactivateAll() 제거: 전체 수집 대상이 BFS로 확장되므로 랭킹 탈락자도 수집 유지
        for (CrRankingPlayer player : allPlayers) {
            playerToCrawlRepository.upsertRanked(player.tag(), player.name(), player.rank());
        }

        log.info("[SyncRanking] players_to_crawl 동기화 완료: {}명", allPlayers.size());
        contribution.incrementWriteCount(allPlayers.size());
        return RepeatStatus.FINISHED;
    }

    private List<CrRankingPlayer> fetchAllRankedPlayers(String seasonId) {
        List<CrRankingPlayer> result = new ArrayList<>();
        String cursor = null;

        do {
            CrRankingResponse page = clashRoyaleClient.getPolRankings(seasonId, cursor);
            if (page.items() != null) {
                result.addAll(page.items());
            }
            cursor = page.hasNextPage() ? page.nextCursor() : null;
        } while (cursor != null);

        return result;
    }
}
