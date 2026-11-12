package io.github.wjlsssq.learn.web.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

public record ApiError(
        String code,
        String message,
        String traceId,
        Instant timestamp,
        String path,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Violation> violations
) {
    public ApiError {
        violations = List.copyOf(violations);
    }

    /** Contains no rejected value, which may hold private request data. */
    public record Violation(String field, String message) {
    }
}
