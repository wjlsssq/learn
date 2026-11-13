package io.github.wjlsssq.learn.order.application;

import io.github.wjlsssq.learn.order.domain.OrderError;
import io.github.wjlsssq.learn.web.error.BusinessException;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Lua 限流每个学习买家 10 秒内最多 30 次下单。它保护数据库，不承担库存/幂等正确性。
 * 买家由本地用户输入，因此不是安全防刷机制；上线需使用已认证主体及网关限流。
 * Redis 故障采用失败关闭，不悄悄绕过保护；HTTP 重试仍应复用原幂等键。
 */
@Component
public class RateLimiter {
    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> script;
    public RateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
        this.script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/rate-limit.lua"));
        script.setResultType(Long.class);
    }
    public void check(String buyerId) {
        Long count = redis.execute(script, List.of("order-lab:rate:" + buyerId), "10000");
        if (count == null) throw new BusinessException(OrderError.BUSY);
        if (count > 30) throw new BusinessException(OrderError.RATE_LIMIT);
    }
}
