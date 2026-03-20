package com.rheon.royale.global.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;

import java.time.Instant;
import java.util.Map;

@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    private final Instant timestamp;
    private final String path;
    private final int status;
    private final String code;
    private final String message;
    private final Map<String, String> details;

    private ErrorResponse(String path, ErrorCode errorCode, Map<String, String> details) {
        this.timestamp = Instant.now();
        this.path = path;
        this.status = errorCode.getStatus().value();
        this.code = errorCode.getCode();
        this.message = errorCode.getMessage();
        this.details = details;
    }

    public static ErrorResponse of(String path, ErrorCode errorCode) {
        return new ErrorResponse(path, errorCode, null);
    }

    public static ErrorResponse of(String path, ErrorCode errorCode, Map<String, String> details) {
        return new ErrorResponse(path, errorCode, details);
    }
}