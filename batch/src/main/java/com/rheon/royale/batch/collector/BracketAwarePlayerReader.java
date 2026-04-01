package com.rheon.royale.batch.collector;

import com.rheon.royale.domain.entity.PlayerToCrawl;
import com.rheon.royale.domain.repository.PlayerToCrawlRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * JdbcCursorItemReader 래퍼.
 *
 * - open(): DB에서 활성 브라켓 목록 조회 → knownBrackets
 * - read(): cursor 스트리밍 중 두 가지 early-stop 조건 적용
 *   1. isAllBracketsFull(knownBrackets) → 모든 브라켓 달성 → null (Step 종료)
 *   2. PoL 플레이어의 pol_N 브라켓이 가득 찼으면 → skip (API 호출 절약)
 *      트로피 플레이어는 통과 → Processor에서 배틀별 브라켓 분류
 *      (한 플레이어가 PvP + trail 등 복수 브라켓에 기여 가능)
 *
 * SynchronizedItemStreamReader로 감싸서 AsyncItemProcessor 50 스레드 안전성 보장.
 */
@Slf4j
@Component
@Scope("step")   // @StepScope 대신 @Scope("step") 사용 — @Component와 함께 쓸 때 더 안전
public class BracketAwarePlayerReader implements ItemStreamReader<PlayerToCrawl> {

    private final JdbcCursorItemReader<PlayerToCrawl> delegate;
    private final BracketBattleCounter bracketBattleCounter;
    private final PlayerToCrawlRepository playerToCrawlRepository;

    private Set<String> knownBrackets = new HashSet<>();

    public BracketAwarePlayerReader(
            BracketBattleCounter bracketBattleCounter,
            PlayerToCrawlRepository playerToCrawlRepository,
            @Value("#{jobParameters['startTime']}") String startTime,
            @Value("#{jobParameters['batchSeq'] ?: '0'}") String batchSeq,
            @Value("#{jobParameters['hashK'] ?: '10'}") String hashKStr,
            @Value("${collector.max-players-per-bracket:20000}") int maxPlayersPerBracket,
            DataSource dataSource) {

        this.bracketBattleCounter = bracketBattleCounter;
        this.playerToCrawlRepository = playerToCrawlRepository;

        LocalDateTime jobStartTime = LocalDateTime.parse(startTime);
        int hashK = Integer.parseInt(hashKStr);

        this.delegate = buildDelegate(dataSource, startTime, batchSeq, hashK, jobStartTime, maxPlayersPerBracket);
    }

    private JdbcCursorItemReader<PlayerToCrawl> buildDelegate(
            DataSource dataSource,
            String startTime, String batchSeq, int hashK,
            LocalDateTime jobStartTime, int maxPlayersPerBracket) {

        return new JdbcCursorItemReaderBuilder<PlayerToCrawl>()
                .name("bracketAwarePlayerReader")
                .dataSource(dataSource)
                .sql("""
                        WITH sampled AS (
                            SELECT player_tag, name, current_rank, last_crawled_at,
                                   is_active, priority, current_trophies, league_number, bracket, updated_at,
                                   hashtext(player_tag) AS h1
                            FROM players_to_crawl
                            WHERE is_active = true
                              AND updated_at < ?
                              AND bracket IS NOT NULL
                              AND bracket <> 'unknown'
                              AND (? = 1 OR mod(abs(hashtext(player_tag || ? || ?)), ?) = 0)
                        ),
                        ranked AS (
                            SELECT *, ROW_NUMBER() OVER (
                                PARTITION BY bracket
                                ORDER BY h1
                            ) AS rn
                            FROM sampled
                        )
                        SELECT player_tag, name, current_rank, last_crawled_at,
                               is_active, priority, current_trophies, league_number, bracket, updated_at
                        FROM ranked
                        WHERE rn <= ?
                        ORDER BY rn, bracket
                        """)
                .preparedStatementSetter(ps -> {
                    ps.setTimestamp(1, Timestamp.valueOf(jobStartTime));
                    ps.setInt(2, hashK);               // K=1 분기 (K=1이면 mod 조건 bypass)
                    ps.setString(3, startTime);        // mod 조건 h2 salt
                    ps.setString(4, batchSeq);         // mod 조건 h2 salt 보조
                    ps.setInt(5, hashK);               // mod 제수
                    ps.setInt(6, maxPlayersPerBracket);
                })
                .rowMapper((rs, rowNum) -> {
                    Timestamp lastCrawledAt = rs.getTimestamp("last_crawled_at");
                    return PlayerToCrawl.builder()
                            .playerTag(rs.getString("player_tag"))
                            .name(rs.getString("name"))
                            .currentRank(rs.getObject("current_rank", Integer.class))
                            .lastCrawledAt(lastCrawledAt != null ? lastCrawledAt.toLocalDateTime() : null)
                            .isActive(rs.getBoolean("is_active"))
                            .priority(rs.getInt("priority"))
                            .currentTrophies(rs.getObject("current_trophies", Integer.class))
                            .leagueNumber(rs.getObject("league_number", Integer.class))
                            .bracket(rs.getString("bracket"))
                            .updatedAt(rs.getTimestamp("updated_at").toLocalDateTime())
                            .build();
                })
                .build();
    }

    @Override
    public void open(ExecutionContext executionContext) {
        delegate.open(executionContext);
        knownBrackets = new HashSet<>(playerToCrawlRepository.findDistinctActiveBrackets());
        knownBrackets.remove("unknown");
        knownBrackets.remove(null);
        log.info("[BracketAwareReader] 활성 브라켓 {}개: {}", knownBrackets.size(), knownBrackets);
    }

    @Override
    public PlayerToCrawl read() throws Exception {
        while (true) {
            PlayerToCrawl player = delegate.read();
            if (player == null) {
                log.info("[BracketAwareReader] cursor 소진 → Step 종료");
                return null;
            }

            // early stop: 모든 브라켓 달성
            if (bracketBattleCounter.isAllBracketsFull(knownBrackets)) {
                log.info("[BracketAwareReader] 모든 브라켓 목표 달성 → early stop");
                return null;
            }

            // PoL 플레이어만 pre-check (플레이어 브라켓 = 배틀 브라켓 1:1)
            String bracket = player.getBracket();
            if (bracket != null && bracket.startsWith("pol_")
                    && bracketBattleCounter.isBracketFull(bracket)) {
                continue;
            }

            return player;
        }
    }

    @Override
    public void update(ExecutionContext executionContext) {
        delegate.update(executionContext);
    }

    @Override
    public void close() {
        delegate.close();
    }
}
