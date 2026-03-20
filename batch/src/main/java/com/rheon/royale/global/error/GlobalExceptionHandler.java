package com.rheon.royale.global.error;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(JobExecutionAlreadyRunningException.class)
    public ResponseEntity<ErrorResponse> handleJobAlreadyRunning(JobExecutionAlreadyRunningException e, HttpServletRequest request) {
        log.warn("JobAlreadyRunning [{}] {}: {}", request.getMethod(), request.getRequestURI(), e.getMessage());
        return ResponseEntity.status(ErrorCode.JOB_ALREADY_RUNNING.getStatus())
                .body(ErrorResponse.of(request.getRequestURI(), ErrorCode.JOB_ALREADY_RUNNING));
    }

    @ExceptionHandler(JobInstanceAlreadyCompleteException.class)
    public ResponseEntity<ErrorResponse> handleJobAlreadyComplete(JobInstanceAlreadyCompleteException e, HttpServletRequest request) {
        log.warn("JobAlreadyComplete [{}] {}: {}", request.getMethod(), request.getRequestURI(), e.getMessage());
        return ResponseEntity.status(ErrorCode.JOB_ALREADY_RUNNING.getStatus())
                .body(ErrorResponse.of(request.getRequestURI(), ErrorCode.JOB_ALREADY_RUNNING));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException e, HttpServletRequest request) {
        log.warn("BusinessException [{}] {}: {}", request.getMethod(), request.getRequestURI(), e.getMessage());
        ErrorCode code = e.getErrorCode();
        return ResponseEntity.status(code.getStatus())
                .body(ErrorResponse.of(request.getRequestURI(), code));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<ErrorResponse> handleValidationException(BindException e, HttpServletRequest request) {
        Map<String, String> details = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(fe -> details.put(fe.getField(), fe.getDefaultMessage()));
        log.warn("ValidationException [{}] {}: {}", request.getMethod(), request.getRequestURI(), details);
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(request.getRequestURI(), ErrorCode.INVALID_INPUT_VALUE, details));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException e, HttpServletRequest request) {
        Map<String, String> details = Map.of(e.getParameterName(), "필수 파라미터가 누락되었습니다.");
        log.warn("MissingParam [{}] {}: {}", request.getMethod(), request.getRequestURI(), e.getMessage());
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(request.getRequestURI(), ErrorCode.INVALID_INPUT_VALUE, details));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
        log.warn("MethodNotAllowed [{}] {}", request.getMethod(), request.getRequestURI());
        return ResponseEntity.status(ErrorCode.METHOD_NOT_ALLOWED.getStatus())
                .body(ErrorResponse.of(request.getRequestURI(), ErrorCode.METHOD_NOT_ALLOWED));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e, HttpServletRequest request) {
        log.error("Unhandled exception [{}] {}", request.getMethod(), request.getRequestURI(), e);
        return ResponseEntity.internalServerError()
                .body(ErrorResponse.of(request.getRequestURI(), ErrorCode.INTERNAL_SERVER_ERROR));
    }
}
