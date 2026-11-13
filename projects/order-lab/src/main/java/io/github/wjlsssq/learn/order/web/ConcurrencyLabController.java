package io.github.wjlsssq.learn.order.web;

import io.github.wjlsssq.learn.order.application.OrderFacade;
import io.github.wjlsssq.learn.order.application.RateLimiter;
import io.github.wjlsssq.learn.order.domain.Models.*;
import io.github.wjlsssq.learn.order.domain.OrderError;
import io.github.wjlsssq.learn.web.error.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.*;

/**
 * 有上限的本地并发实验。入口默认不存在，仅显式开启 order.lab-enabled 后注册。
 * 任务通过真实下单门面写真实学习库，不能连接生产库。这里没有持有订单锁再等待子线程。
 * 用显式 Callable 取代嵌套 Lambda，让执行线程、异常归属和事务入口清晰可读。
 */
@RestController
@RequestMapping("/api/orders/lab")
@ConditionalOnProperty(name = "order.lab-enabled", havingValue = "true")
public class ConcurrencyLabController {
    private final OrderFacade orders;
    private final RateLimiter limiter;
    private final ThreadPoolTaskExecutor executor;
    public ConcurrencyLabController(OrderFacade orders, RateLimiter limiter,
            @Qualifier("labExecutor") ThreadPoolTaskExecutor executor) {
        this.orders = orders; this.limiter = limiter; this.executor = executor;
    }
    public record Batch(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String buyerId,
        @Positive long productId, @Min(1) @Max(30) int count, boolean sameRequestKey) { }
    public record Result(String requestKey, String outcome, String orderId, String thread) { }

    /** 同键验证幂等，不同键验证库存竞争。超时只代表结果未知，不声称数据库事务已经取消。 */
    @PostMapping("/concurrent")
    public List<Result> concurrent(@Valid @RequestBody Batch batch) {
        List<Result> results = new ArrayList<>();
        List<Future<Result>> futures = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        String commonKey = UUID.randomUUID().toString();
        String traceId = MDC.get("traceId");
        for (int i = 0; i < batch.count(); i++) {
            String key = batch.sameRequestKey() ? commonKey : UUID.randomUUID().toString();
            CreateOrder request = new CreateOrder(batch.buyerId(), key, batch.productId(), 1);
            try {
                futures.add(executor.submit(new OrderTask(request, traceId)));
                keys.add(key);
            } catch (RejectedExecutionException rejected) {
                results.add(new Result(key, "REJECTED", null, Thread.currentThread().getName()));
            }
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        for (int i = 0; i < futures.size(); i++) {
            try {
                long remaining = Math.max(1, deadline - System.nanoTime());
                results.add(futures.get(i).get(remaining, TimeUnit.NANOSECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new BusinessException(OrderError.BUSY);
            } catch (TimeoutException timeout) {
                results.add(new Result(keys.get(i), "UNKNOWN_QUERY_ORDER", null, ""));
            } catch (ExecutionException failure) {
                results.add(new Result(keys.get(i), "FAILED_QUERY_BEFORE_RETRY", null, ""));
            }
        }
        return results;
    }

    private final class OrderTask implements Callable<Result> {
        private final CreateOrder request;
        private final String traceId;
        private OrderTask(CreateOrder request, String traceId) { this.request = request; this.traceId = traceId; }
        @Override
        public Result call() {
            if (traceId != null) MDC.put("traceId", traceId);
            try {
                limiter.check(request.buyerId());
                Order order = orders.create(request);
                return new Result(request.requestKey(), "OK", order.id(), Thread.currentThread().getName());
            } catch (BusinessException expected) {
                return new Result(request.requestKey(), expected.errorCode().code(), null, Thread.currentThread().getName());
            } finally {
                // 线程池会复用线程；必须清理线程局部日志字段，避免下一笔订单串用 traceId。
                MDC.remove("traceId");
            }
        }
    }
}
