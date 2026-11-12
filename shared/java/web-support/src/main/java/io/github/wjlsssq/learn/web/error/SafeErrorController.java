package io.github.wjlsssq.learn.web.error;

import io.github.wjlsssq.learn.web.request.RequestIdFilter;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** Covers container error dispatches, which do not pass through MVC exception advice. */
@RestController
public final class SafeErrorController implements ErrorController {

    @RequestMapping("${server.error.path:${error.path:/error}}")
    public ResponseEntity<ApiError> error(HttpServletRequest request) {
        Object suppliedStatus = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int code = suppliedStatus instanceof Integer value && value >= 400 && value <= 599 ? value : 500;
        HttpStatusCode status = HttpStatusCode.valueOf(code);
        CommonErrorCode errorCode = CommonErrorCode.fromStatus(status);
        Object originalPath = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        String path = originalPath instanceof String value ? value : request.getRequestURI();
        String requestId = RequestIdFilter.requestId(request);
        ApiError body = new ApiError(errorCode.code(), errorCode.defaultMessage(), requestId,
                Instant.now(), path, List.of());
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .header(RequestIdFilter.HEADER_NAME, requestId)
                .body(body);
    }
}
