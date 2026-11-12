package io.github.wjlsssq.learn.foundation;

import io.github.wjlsssq.learn.web.config.WebSupportConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(WebSupportConfiguration.class)
public class FoundationApplication {

    public static void main(String[] args) {
        SpringApplication.run(FoundationApplication.class, args);
    }
}
