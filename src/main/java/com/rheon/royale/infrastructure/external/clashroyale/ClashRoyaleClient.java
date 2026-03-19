package com.rheon.royale.infrastructure.external.clashroyale;

import com.google.common.util.concurrent.RateLimiter;
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
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class ClashRoyaleClient {

    private final WebClient.Builder webClientBuilder;
    private final ClashRoyaleProperties props;

    private WebClient webClient;
    /**
     * [아키텍처 제약] Rate Limiting — Guava RateLimiter
     *
     * Guava RateLimiter는 내부적으로 lock 점유 시간을 최소화한 토큰 버킷 방식.
     * acquire()가 필요한 만큼만 sleep하고 락을 즉시 반환 → 20개 async 스레드가
     * 진정한 병렬로 동작 (각자 다른 시간에 슬롯 예약, 락 경합 최소화).
     *
     * 현재: 단일 파드 환경이므로 로컬 RateLimiter로 충분
     * 미래: K3s Scale-out 시 Redis Bucket4j로 교체 필요
     */
    private RateLimiter rateLimiter;

    @PostConstruct
    public void init() {
        this.webClient = webClientBuilder
                .baseUrl(props.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + props.getToken())
                .build();
        // warmupPeriod=2s: 초반 burst 억제 → 슬로우 스타트 후 정상 rate 유지 (429 방지)
        this.rateLimiter = RateLimiter.create(props.getRateLimit(), 2, TimeUnit.SECONDS);
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
     * 레이트 리밋: 초당 최대 rateLimit 건 (rate-limit: 30 → 33ms 간격)
     *
     * Guava RateLimiter.acquire()는 토큰을 가져올 때까지 정확히 필요한 시간만 대기.
     * lock 점유 최소화 → 20개 async 스레드가 각자 다른 시간에 슬롯을 예약하고
     * 병렬로 API를 호출 → 진정한 멀티스레드 처리.
     */
    private void throttle() {
        rateLimiter.acquire();
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
