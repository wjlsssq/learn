package io.github.wjlsssq.learn.web.error;

import org.springframework.http.HttpStatus;

/** A stable, client-facing error identifier with its HTTP semantics. */
public interface ErrorCode {

    String code();

    HttpStatus status();

    String defaultMessage();
}
