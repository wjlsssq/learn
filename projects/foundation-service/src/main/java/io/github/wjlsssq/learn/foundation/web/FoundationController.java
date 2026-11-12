package io.github.wjlsssq.learn.foundation.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Describes the foundation that is actually available, without advertising planned integrations. */
@RestController
@RequestMapping("/api/foundation")
public final class FoundationController {

    private final String version;

    public FoundationController(@Value("${app.version}") String version) {
        this.version = version;
    }

    @GetMapping("/info")
    public FoundationInfo info() {
        return new FoundationInfo("Learn Foundation Service", version, System.getProperty("java.version"),
                List.of("web", "validation", "request-correlation"));
    }

    public record FoundationInfo(String name, String version, String javaVersion, List<String> capabilities) {
    }
}
