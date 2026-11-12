package io.github.wjlsssq.learn.web.error;

import io.github.wjlsssq.learn.web.request.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

@RestControllerAdvice
public final class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final Comparator<ApiError.Violation> VIOLATION_ORDER = Comparator
            .comparing(ApiError.Violation::field)
            .thenComparing(ApiError.Violation::message);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Object> handleBusinessException(BusinessException exception,
                                                        HttpServletRequest request) {
        ErrorCode errorCode = exception.errorCode();
        return response(errorCode.code(), exception.getMessage(), errorCode.status(),
                new HttpHeaders(), request, List.of());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException exception,
                                                          HttpServletRequest request) {
        List<ApiError.Violation> violations = exception.getConstraintViolations().stream()
                .map(violation -> new ApiError.Violation(violation.getPropertyPath().toString(),
                        violation.getMessage()))
                .sorted(VIOLATION_ORDER)
                .toList();
        return response(CommonErrorCode.VALIDATION_FAILED, new HttpHeaders(), request, violations);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpectedException(Exception exception,
                                                           HttpServletRequest request) {
        LOG.error("Unexpected request failure; requestId={}", RequestIdFilter.requestId(request), exception);
        return response(CommonErrorCode.INTERNAL_ERROR, new HttpHeaders(), request, List.of());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
                                                                 HttpHeaders headers,
                                                                 HttpStatusCode status,
                                                                 WebRequest request) {
        List<ApiError.Violation> violations = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiError.Violation(error.getField(),
                        error.getDefaultMessage() == null ? "参数不符合要求" : error.getDefaultMessage()))
                .sorted(VIOLATION_ORDER)
                .toList();
        return response(CommonErrorCode.VALIDATION_FAILED, headers, servletRequest(request), violations);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
                                                             HttpHeaders headers, HttpStatusCode status,
                                                             WebRequest request) {
        if (request instanceof ServletWebRequest servletWebRequest
                && servletWebRequest.getResponse() != null
                && servletWebRequest.getResponse().isCommitted()) {
            return null;
        }
        if (status.is5xxServerError()) {
            LOG.error("Framework request failure; requestId={}",
                    RequestIdFilter.requestId(servletRequest(request)), exception);
        }
        CommonErrorCode errorCode = CommonErrorCode.fromStatus(status);
        return response(errorCode.code(), errorCode.defaultMessage(), status,
                headers, servletRequest(request), List.of());
    }

    private ResponseEntity<Object> response(CommonErrorCode errorCode, HttpHeaders headers,
                                            HttpServletRequest request,
                                            List<ApiError.Violation> violations) {
        return response(errorCode.code(), errorCode.defaultMessage(), errorCode.status(),
                headers, request, violations);
    }

    private ResponseEntity<Object> response(String code, String message, HttpStatusCode status,
                                            HttpHeaders originalHeaders, HttpServletRequest request,
                                            List<ApiError.Violation> violations) {
        String requestId = RequestIdFilter.requestId(request);
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(originalHeaders);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(RequestIdFilter.HEADER_NAME, requestId);
        ApiError error = new ApiError(code, message, requestId, Instant.now(), request.getRequestURI(), violations);
        return new ResponseEntity<>(error, headers, status);
    }

    private HttpServletRequest servletRequest(WebRequest request) {
        return ((ServletWebRequest) request).getRequest();
    }
}
