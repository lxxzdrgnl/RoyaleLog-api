package com.rheon.royale.global.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // Common (C)
    INVALID_INPUT_VALUE(HttpStatus.BAD_REQUEST, "C001", "유효하지 않은 입력값입니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "C002", "서버 내부 오류가 발생했습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "C003", "지원하지 않는 HTTP 메서드입니다."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "C004", "지원하지 않는 미디어 타입입니다."),
    RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "C005", "요청 한도를 초과했습니다. 잠시 후 다시 시도해주세요."),

    // Auth (A)
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "A001", "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "A002", "접근 권한이 없습니다."),

    // Player / Match (M)
    PLAYER_NOT_FOUND(HttpStatus.NOT_FOUND, "M001", "플레이어를 찾을 수 없습니다."),
    INVALID_PLAYER_TAG(HttpStatus.BAD_REQUEST, "M002", "올바르지 않은 플레이어 태그 형식입니다."),
    DUPLICATE_PLAYER(HttpStatus.CONFLICT, "M003", "이미 등록된 플레이어입니다."),

    // Card / Stats (S)
    STATS_NOT_FOUND(HttpStatus.NOT_FOUND, "S001", "해당 시즌의 통계 데이터가 없습니다."),
    CARD_NOT_FOUND(HttpStatus.NOT_FOUND, "S002", "카드 정보를 찾을 수 없습니다."),

    // Prediction (P)
    PREDICTION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "P001", "예측 서버에 연결할 수 없습니다."),

    // External API (E)
    CLASH_API_ERROR(HttpStatus.BAD_GATEWAY, "E001", "Clash Royale API 호출 중 오류가 발생했습니다."),
    CLASH_API_RATE_LIMIT(HttpStatus.TOO_MANY_REQUESTS, "E002", "Clash Royale API 요청 한도를 초과했습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
