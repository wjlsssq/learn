package io.github.wjlsssq.learn.web.error;

import java.util.Objects;

/** An expected failure whose message is safe to return to an API caller. */
public final class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, Objects.requireNonNull(errorCode, "errorCode").defaultMessage());
    }

    public BusinessException(ErrorCode errorCode, String publicMessage) {
        super(Objects.requireNonNull(publicMessage, "publicMessage"));
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
        if (!errorCode.status().isError()) {
            throw new IllegalArgumentException("An error code must use a 4xx or 5xx HTTP status");
        }
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
