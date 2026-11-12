package io.github.wjlsssq.learn.foundation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.wjlsssq.learn.web.error.BusinessException;
import io.github.wjlsssq.learn.web.error.ErrorCode;
import io.github.wjlsssq.learn.web.request.RequestIdFilter;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the assembled application through a real embedded HTTP server. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FoundationHttpTest.TestEndpointsConfiguration.class)
class FoundationHttpTest {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void exposesOnlyAvailableFoundationCapabilities() throws Exception {
        HttpResponse<String> response = send("GET", "/api/foundation/info", null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode info = json(response);
        assertThat(info.path("name").asText()).isEqualTo("Learn Foundation Service");
        assertThat(info.path("version").asText()).isEqualTo("0.1.0-SNAPSHOT");
        assertThat(info.path("javaVersion").asText()).isNotBlank();
        assertThat(info.path("capabilities").toString()).isEqualTo("[\"web\",\"validation\",\"request-correlation\"]");
        assertThat(info.has("data")).isFalse();
        assertThat(requestId(response)).matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    }

    @Test
    void healthDoesNotRequireExternalInfrastructure() throws Exception {
        HttpResponse<String> response = send("GET", "/actuator/health", null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).path("status").asText()).isEqualTo("UP");
        assertThat(json(response).has("components")).isFalse();
        assertThat(send("GET", "/actuator/env", null, null).statusCode()).isEqualTo(404);
    }

    @Test
    void preservesNotFoundStatusAndCorrelatesItsError() throws Exception {
        HttpResponse<String> response = send("GET", "/api/does-not-exist", null, "learn-request-01");
        assertError(response, 404, "NOT_FOUND", "/api/does-not-exist");
        assertThat(requestId(response)).isEqualTo("learn-request-01");
    }

    @Test
    void replacesUnsafeAndOverlongRequestIds() throws Exception {
        HttpResponse<String> unsafe = send("GET", "/api/does-not-exist", null, "unsafe request id");
        assertError(unsafe, 404, "NOT_FOUND", "/api/does-not-exist");
        assertThat(requestId(unsafe)).doesNotContain(" ");

        HttpResponse<String> overlong = send("GET", "/api/does-not-exist", null, "a".repeat(65));
        assertThat(requestId(overlong)).hasSizeLessThanOrEqualTo(64);
        assertThat(requestId(overlong)).isNotEqualTo(requestId(unsafe));
    }

    @Test
    void mapsExpectedBusinessFailureToItsRealHttpStatus() throws Exception {
        HttpResponse<String> response = send("GET", "/__test/conflict", null, null);
        assertError(response, 409, "TEST_CONFLICT", "/__test/conflict");
        assertThat(json(response).path("message").asText()).isEqualTo("测试资源状态冲突");
    }

    @Test
    void reportsFieldValidationWithoutEchoingRejectedValues() throws Exception {
        HttpResponse<String> response = send("POST", "/__test/validate", "{\"title\":\"\",\"count\":0}", null);
        assertError(response, 400, "VALIDATION_FAILED", "/__test/validate");
        JsonNode violations = json(response).path("violations");
        assertThat(violations.size()).isEqualTo(2);
        assertThat(violations.get(0).path("field").asText()).isEqualTo("count");
        assertThat(violations.get(1).path("field").asText()).isEqualTo("title");
        assertThat(response.body()).doesNotContain("rejectedValue", "validatedValue");
    }

    @Test
    void malformedJsonAndMissingParametersRemainBadRequests() throws Exception {
        HttpResponse<String> malformed = send("POST", "/__test/validate", "{private-parser-input", null);
        assertError(malformed, 400, "BAD_REQUEST", "/__test/validate");
        assertThat(malformed.body()).doesNotContain("private-parser-input", "JsonParseException");

        HttpResponse<String> missing = send("GET", "/__test/required", null, null);
        assertError(missing, 400, "BAD_REQUEST", "/__test/required");
    }

    @Test
    void preservesMethodAndContentTypeHttpSemantics() throws Exception {
        HttpResponse<String> method = send("POST", "/api/foundation/info", "{}", null);
        assertError(method, 405, "METHOD_NOT_ALLOWED", "/api/foundation/info");
        assertThat(method.headers().firstValue("Allow").orElseThrow()).contains("GET");

        HttpRequest request = request("/__test/validate")
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("not-json"))
                .build();
        HttpResponse<String> media = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertError(media, 415, "UNSUPPORTED_MEDIA_TYPE", "/__test/validate");
    }

    @Test
    void unsupportedAcceptStillReturnsASafeJsonError() throws Exception {
        HttpRequest request = request("/api/foundation/info").header("Accept", "image/png").GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertError(response, 406, "NOT_ACCEPTABLE", "/api/foundation/info");
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
    }

    @Test
    void responseStatusExceptionsPreserveTheirStatusAndHideInternalReasons() throws Exception {
        HttpResponse<String> response = send("GET", "/__test/status", null, null);
        assertError(response, 410, "REQUEST_REJECTED", "/__test/status");
        assertThat(response.body()).doesNotContain("private-status-reason");
    }

    @Test
    void containerErrorDispatchPreservesTheOriginalPathAndRequestId() throws Exception {
        HttpResponse<String> response = send("GET", "/__test/servlet-error", null, "container-error-request");
        assertError(response, 502, "INTERNAL_ERROR", "/__test/servlet-error");
        assertThat(requestId(response)).isEqualTo("container-error-request");
        assertThat(response.body()).doesNotContain("private-container-reason");
    }

    @Test
    void unexpectedFailureIsLoggedButNeverExposesImplementationDetails() throws Exception {
        HttpResponse<String> response = send("GET", "/__test/unexpected", null, null);
        assertError(response, 500, "INTERNAL_ERROR", "/__test/unexpected");
        assertThat(response.body()).doesNotContain("private-database-password", "IllegalStateException", "stackTrace");
    }

    @Test
    void requestCorrelationIsIsolatedAcrossConcurrentRequests() {
        List<CompletableFuture<Void>> requests = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            String id = "parallel-" + index;
            HttpRequest request = request("/__test/correlation").header(RequestIdFilter.HEADER_NAME, id).GET().build();
            requests.add(HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .thenAccept(response -> {
                        assertThat(response.statusCode()).isEqualTo(200);
                        assertThat(requestId(response)).isEqualTo(id);
                        assertThat(response.body()).isEqualTo("{\"traceId\":\"" + id + "\"}");
                    }));
        }
        CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).join();
    }

    @Test
    void binaryResponsesAreNotWrappedInJson() throws Exception {
        HttpResponse<String> response = send("GET", "/__test/download", null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/octet-stream");
        assertThat(response.body()).isEqualTo("foundation-download");
    }

    private HttpResponse<String> send(String method, String path, String body, String requestId) throws Exception {
        HttpRequest.Builder builder = request(path);
        if (requestId != null) {
            builder.header(RequestIdFilter.HEADER_NAME, requestId);
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10));
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return objectMapper.readTree(response.body());
    }

    private String requestId(HttpResponse<?> response) {
        return response.headers().firstValue(RequestIdFilter.HEADER_NAME).orElseThrow();
    }

    private void assertError(HttpResponse<String> response, int status, String code, String path) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        JsonNode error = json(response);
        assertThat(error.path("code").asText()).isEqualTo(code);
        assertThat(error.path("message").asText()).isNotBlank();
        assertThat(error.path("traceId").asText()).isEqualTo(requestId(response));
        assertThat(error.path("path").asText()).isEqualTo(path);
        assertThat(Instant.parse(error.path("timestamp").asText())).isNotNull();
    }

    /** Test-only routes are never compiled into the shipped application. */
    @TestConfiguration(proxyBeanMethods = false)
    static class TestEndpointsConfiguration {
        @Bean
        TestController testController() {
            return new TestController();
        }
    }

    @RestController
    static class TestController {
        @GetMapping("/__test/conflict")
        void conflict() {
            throw new BusinessException(TestErrorCode.TEST_CONFLICT);
        }

        @PostMapping("/__test/validate")
        Map<String, String> validate(@Valid @RequestBody ValidationPayload payload) {
            return Map.of("title", payload.title());
        }

        @GetMapping("/__test/required")
        Map<String, String> required(@RequestParam String value) {
            return Map.of("value", value);
        }

        @GetMapping("/__test/unexpected")
        void unexpected() {
            throw new IllegalStateException("private-database-password");
        }

        @GetMapping("/__test/status")
        void status() {
            throw new ResponseStatusException(HttpStatus.GONE, "private-status-reason");
        }

        @GetMapping("/__test/servlet-error")
        void servletError(HttpServletResponse response) throws IOException {
            response.sendError(502, "private-container-reason");
        }

        @GetMapping("/__test/correlation")
        Map<String, String> correlation() {
            return Map.of("traceId", MDC.get(RequestIdFilter.MDC_KEY));
        }

        @GetMapping(value = "/__test/download", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        byte[] download() {
            return "foundation-download".getBytes(StandardCharsets.UTF_8);
        }
    }

    record ValidationPayload(@NotBlank String title, @Min(1) int count) {
    }

    enum TestErrorCode implements ErrorCode {
        TEST_CONFLICT;

        @Override
        public String code() {
            return name();
        }

        @Override
        public HttpStatus status() {
            return HttpStatus.CONFLICT;
        }

        @Override
        public String defaultMessage() {
            return "测试资源状态冲突";
        }
    }
}
