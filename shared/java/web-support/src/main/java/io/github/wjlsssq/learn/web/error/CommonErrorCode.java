package io.github.wjlsssq.learn.web.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

public enum CommonErrorCode implements ErrorCode {
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "请求参数不正确"),
    REQUEST_REJECTED(HttpStatus.BAD_REQUEST, "请求未被接受"),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "请求参数校验失败"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "请求的资源不存在"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "不支持该请求方法"),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "无法提供请求的响应格式"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "不支持该请求内容格式"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "服务暂时无法处理请求"),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "服务暂时不可用");

    private final HttpStatus status;
    private final String message;

    CommonErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String defaultMessage() {
        return message;
    }

    public static CommonErrorCode fromStatus(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> BAD_REQUEST;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406 -> NOT_ACCEPTABLE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 503 -> SERVICE_UNAVAILABLE;
            default -> status.is5xxServerError() ? INTERNAL_ERROR : REQUEST_REJECTED;
        };
    }
}
