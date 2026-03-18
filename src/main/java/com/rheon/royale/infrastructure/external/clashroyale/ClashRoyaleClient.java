package com.rheon.royale.infrastructure.external.clashroyale;

import com.rheon.royale.global.error.BusinessException;
import com.rheon.royale.global.error.ErrorCode;
import com.rheon.royale.infrastructure.external.clashroyale.dto.CrCardListResponse;
import com.rheon.royale.infrastructure.external.clashroyale.dto.CrPlayerProfile;
import com.rheon.royale.infrastructure.external.clashroyale.dto.CrRankingResponse;
import com.rheon.royale.infrastructure.external.clashroyale.dto.CrSeasonListResponse;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class ClashRoyaleClient {

    private final WebClient.Builder webClientBuilder;
    private final ClashRoyaleProperties props;

    private WebClient webClient;
    private long intervalMs;          // 요청 간 최소 간격 (ms)
    private long lastCallTime = 0L;

    @PostConstruct
    public void init() {
        this.webClient = webClientBuilder
                .baseUrl(props.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + props.getToken())
                .build();
        this.intervalMs = 1000L / props.getRateLimit();
    }

    // ----------------------------------------------------------------
    // Public API
    // ----------------------------------------------------------------

    /**
     * GET /cards
     * 전체 카드 메타 정보 조회 (items + supportItems)
     */
    public CrCardListResponse getCards() {
        throttle();
        log.debug("GET /cards");
        return webClient.get()
                .uri("/cards")
                .retrieve()
                .onStatus(HttpStatusCode::is5xxServerError,
                        r -> Mono.error(new BusinessException(ErrorCode.CLASH_API_ERROR)))
                .bodyToMono(CrCardListResponse.class)
                .retryWhen(retrySpec())
                .block();
    }

    /**
     * GET /players/{playerTag}
     * 플레이어 프로필 전체 (트로피, 카드 컬렉션, 전투 통계 등)
     */
    public CrPlayerProfile getPlayerProfile(String playerTag) {
        throttle();
        log.debug("GET /players/{}", playerTag);
        return webClient.get()
                .uri("/players/{tag}", playerTag)
                .retrieve()
                .onStatus(s -> s == HttpStatus.NOT_FOUND,
                        r -> Mono.error(new BusinessException(ErrorCode.PLAYER_NOT_FOUND)))
                .onStatus(s -> s.value() == 429,
                        r -> Mono.error(new BusinessException(ErrorCode.CLASH_API_RATE_LIMIT)))
                .onStatus(HttpStatusCode::is5xxServerError,
                        r -> Mono.error(new BusinessException(ErrorCode.CLASH_API_ERROR)))
                .bodyToMono(CrPlayerProfile.class)
                .retryWhen(retrySpec())
                .block();
    }

    /**
     * GET /players/{playerTag}/battlelog
     * 플레이어 최근 배틀 로그 (raw JSON 그대로 반환 → battle_log_raw.raw_json)
     */
    public String getBattleLog(String playerTag) {
        throttle();
        log.debug("GET /players/{}/battlelog", playerTag);
        return webClient.get()
                .uri("/players/{tag}/battlelog", playerTag)
                .retrieve()
                .onStatus(s -> s == HttpStatus.NOT_FOUND,
                        r -> Mono.error(new BusinessException(ErrorCode.PLAYER_NOT_FOUND)))
                .onStatus(s -> s.value() == 429,
                        r -> Mono.error(new BusinessException(ErrorCode.CLASH_API_RATE_LIMIT)))
                .onStatus(HttpStatusCode::is5xxServerError,
                        r -> Mono.error(new BusinessException(ErrorCode.CLASH_API_ERROR)))
                .bodyToMono(String.class)
                .retryWhen(retrySpec())
                .block();
    }

    /**
     * GET /locations/global/pathoflegend/{seasonId}/rankings/players
     * PoL 랭킹 조회 (커서 기반 페이지네이션)
     *
     * @param seasonId 시즌 ID (SeasonIdTasklet에서 주입)
     * @param after    다음 페이지 커서 (첫 페이지는 null)
     */
    public CrRankingResponse getPolRankings(String seasonId, String after) {
        throttle();
        log.debug("GET /locations/global/pathoflegend/{}/rankings/players (after={})", seasonId, after);
        return webClient.get()
                .uri(uriBuilder -> {
                    var builder = uriBuilder
                            .path("/locations/global/pathoflegend/{seasonId}/rankings/players")
                            .queryParam("limit", 50);
                    if (after != null) {
                        builder.queryParam("after", after);
                    }
                    return builder.build(seasonId);
                })
                .retrieve()
                .onStatus(s -> s.value() == 429,
                        r -> Mono.error(new BusinessException(ErrorCode.CLASH_API_RATE_LIMIT)))
                .onStatus(HttpStatusCode::is5xxServerError,
                        r -> Mono.error(new BusinessException(ErrorCode.CLASH_API_ERROR)))
                .bodyToMono(CrRankingResponse.class)
                .retryWhen(retrySpec())
                .block();
    }

    /**
     * GET /players/{playerTag}/masteries
     * 플레이어 카드별 마스터리 (도전과제 진척도) 조회
     */
    public String getMasteries(String playerTag) {
        throttle();
        log.debug("GET /players/{}/masteries", playerTag);
        return webClient.get()
                .uri("/players/{tag}/masteries", playerTag)
                .retrieve()
                .onStatus(s -> s == HttpStatus.NOT_FOUND,
                        r -> Mono.error(new BusinessException(ErrorCode.PLAYER_NOT_FOUND)))
                .onStatus(s -> s.value() == 429,
                        r -> Mono.error(new BusinessException(ErrorCode.CLASH_API_RATE_LIMIT)))
                .onStatus(HttpStatusCode::is5xxServerError,
                        r -> Mono.error(new BusinessException(ErrorCode.CLASH_API_ERROR)))
                .bodyToMono(String.class)
                .retryWhen(retrySpec())
                .block();
    }

    /**
     * GET /locations/global/seasons
     * 전체 시즌 목록 조회 — 마지막 아이템이 현재 활성 시즌
     * (seasonsV2는 null 반환 버그 있음 → 사용 금지)
     */
    public CrSeasonListResponse getSeasons() {
        throttle();
        log.debug("GET /locations/global/seasons");
        return webClient.get()
                .uri("/locations/global/seasons")
                .retrieve()
                .onStatus(HttpStatusCode::is5xxServerError,
                        r -> Mono.error(new BusinessException(ErrorCode.CLASH_API_ERROR)))
                .bodyToMono(CrSeasonListResponse.class)
                .retryWhen(retrySpec())
                .block();
    }

    // ----------------------------------------------------------------
    // Internal
    // ----------------------------------------------------------------

    /**
     * 레이트 리밋: 초당 최대 rateLimit 건 (기본 10 req/s = 100ms 간격)
     * Spring Batch 단일 스레드 Step 기준 — synchronized 로 충분
     */
    private synchronized void throttle() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastCallTime;
        if (elapsed < intervalMs) {
            try {
                Thread.sleep(intervalMs - elapsed);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
            }
        }
        lastCallTime = System.currentTimeMillis();
    }

    /**
     * Retry 정책: 429 / 5xx 에 한해 최대 retryMax 회, ExponentialBackOff
     * 404 / 400 은 재시도 없이 예외 전파
     */
    private Retry retrySpec() {
        return Retry.backoff(props.getRetryMax(), Duration.ofSeconds(1))
                .maxBackoff(Duration.ofSeconds(10))
                .filter(e -> e instanceof BusinessException be &&
                        (be.getErrorCode() == ErrorCode.CLASH_API_RATE_LIMIT
                                || be.getErrorCode() == ErrorCode.CLASH_API_ERROR))
                .doBeforeRetry(signal -> log.warn(
                        "Clash API 재시도 {}/{}  원인: {}",
                        signal.totalRetries() + 1,
                        props.getRetryMax(),
                        signal.failure().getMessage()));
    }
}
