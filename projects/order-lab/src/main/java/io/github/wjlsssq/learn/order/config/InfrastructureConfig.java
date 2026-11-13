package io.github.wjlsssq.learn.order.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 明确管理进程内资源。Redisson 原生客户端不替换 Spring Redis 自动配置，
 * Spring Redis 执行限流 Lua，Redisson 负责分布式锁，两者使用相同的 Redis 实例。
 */
@Configuration
public class InfrastructureConfig {
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redisson(
            @Value("${spring.data.redis.host}") String host,
            @Value("${spring.data.redis.port}") int port,
            @Value("${spring.data.redis.password}") String password) {
        Config config = new Config();
        config.setLockWatchdogTimeout(30000);
        config.useSingleServer().setAddress("redis://" + host + ":" + port)
            .setPassword(password).setConnectionMinimumIdleSize(2).setConnectionPoolSize(12)
            .setConnectTimeout(2000).setTimeout(2000).setRetryAttempts(1);
        return Redisson.create(config);
    }

    /** 发送线程有界：2 个核心、4 个最大、16 个排队；拒绝时发送意图仍留在数据库。 */
    @Bean
    public ThreadPoolTaskExecutor outboxExecutor() {
        return executor("outbox-", 2, 4, 16);
    }

    /** 实验使用独立线程池，不能把阻塞实验放进公共 ForkJoinPool 或抢占消息发送线程。 */
    @Bean
    public ThreadPoolTaskExecutor labExecutor() {
        return executor("order-lab-", 2, 4, 8);
    }

    private ThreadPoolTaskExecutor executor(String prefix, int core, int maximum, int queue) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(prefix);
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(maximum);
        executor.setQueueCapacity(queue);
        executor.setKeepAliveSeconds(60);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        return executor;
    }
}
