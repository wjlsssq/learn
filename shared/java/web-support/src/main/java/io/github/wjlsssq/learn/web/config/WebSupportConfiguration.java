package io.github.wjlsssq.learn.web.config;

import io.github.wjlsssq.learn.web.error.GlobalExceptionHandler;
import io.github.wjlsssq.learn.web.error.SafeErrorController;
import io.github.wjlsssq.learn.web.request.RequestIdFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;

/** Applications explicitly import this configuration to opt in to the shared Web conventions. */
@Configuration(proxyBeanMethods = false)
@Import({GlobalExceptionHandler.class, SafeErrorController.class})
public class WebSupportConfiguration {

    @Bean
    public FilterRegistrationBean<RequestIdFilter> requestIdFilterRegistration() {
        FilterRegistrationBean<RequestIdFilter> registration = new FilterRegistrationBean<>(new RequestIdFilter());
        registration.setName("learnRequestIdFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setAsyncSupported(true);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        return registration;
    }
}
