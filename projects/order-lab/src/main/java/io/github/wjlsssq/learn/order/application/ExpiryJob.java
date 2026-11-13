package io.github.wjlsssq.learn.order.application;

import io.github.wjlsssq.learn.order.persistence.OrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 用数据库到期索引恢复关单任务，不依赖不可靠的 Redis 过期通知。
 * 多实例可以扫描到同一订单，但状态条件和行锁只允许一次取消、一次库存释放。
 */
@Component
public class ExpiryJob {
    private static final Logger log = LoggerFactory.getLogger(ExpiryJob.class);
    private final OrderMapper mapper;
    private final OrderTransactions transactions;
    public ExpiryJob(OrderMapper mapper, OrderTransactions transactions) { this.mapper = mapper; this.transactions = transactions; }
    @Scheduled(fixedDelay = 5000)
    public void expire() {
        for (String id : mapper.expired()) {
            try { transactions.cancel(id, true); }
            catch (RuntimeException failure) { log.error("到期关单失败 orderId={}", id, failure); }
        }
    }
}
