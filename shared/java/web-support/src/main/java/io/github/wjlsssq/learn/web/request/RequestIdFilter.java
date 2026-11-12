package io.github.wjlsssq.learn.web.request;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/** Correlates one HTTP request across response headers, API errors and log messages. */
public final class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Request-Id";
    public static final String MDC_KEY = "traceId";
    public static final String REQUEST_ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";

    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain filterChain) throws ServletException, IOException {
        String requestId = requestId(request);
        String previous = MDC.get(MDC_KEY);
        response.setHeader(HEADER_NAME, requestId);
        MDC.put(MDC_KEY, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (previous == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previous);
            }
        }
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    public static String requestId(HttpServletRequest request) {
        Object existing = request.getAttribute(REQUEST_ATTRIBUTE);
        if (existing instanceof String requestId) {
            return requestId;
        }
        String supplied = request.getHeader(HEADER_NAME);
        String requestId = supplied != null && SAFE_REQUEST_ID.matcher(supplied).matches()
                ? supplied : UUID.randomUUID().toString();
        request.setAttribute(REQUEST_ATTRIBUTE, requestId);
        return requestId;
    }
}
