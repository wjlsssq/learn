package io.github.wjlsssq.learn.order.application;

import io.github.wjlsssq.learn.order.domain.Models.*;
import io.github.wjlsssq.learn.order.persistence.OrderMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 消费者业务事务：锁订单行后查幂等记录，写消费记录、积分流水与余额。
 * 行锁让同订单的重复消费串行化；两个唯一键分别限制事件与业务效果。
 * 不吞掉任意 DuplicateKeyException：只有查到一致的已提交记录才认为消费已完成。
 */
@Service
public class PointsTransactions {
    private final OrderMapper mapper;
    public PointsTransactions(OrderMapper mapper) { this.mapper = mapper; }

    @Transactional(timeout = 10, rollbackFor = Exception.class)
    public void award(PaidEvent event) {
        if (event.version() != 1 || event.eventId() == null || event.orderId() == null) {
            throw new IllegalArgumentException("不支持的消息契约");
        }
        Order order = mapper.lockOrder(event.orderId());
        Event original = mapper.event(event.eventId());
        if (order == null || !"PAID".equals(order.status()) || original == null
                || !original.orderId().equals(order.id())) {
            throw new IllegalStateException("事件和已支付订单不匹配");
        }
        String consumedOrder = mapper.consumed(event.eventId());
        if (consumedOrder != null) {
            if (!consumedOrder.equals(order.id())) throw new IllegalStateException("事件标识冲突");
            return;
        }
        mapper.recordConsumption(event.eventId(), order.id());
        if (mapper.awarded(order.id()) == null) {
            long points = Math.max(1, order.amountCents() / 100);
            mapper.insertPoints(order, points);
            mapper.addPoints(order.buyerId(), points);
            mapper.history(order.id(), "POINTS_AWARDED", event.eventId(), Thread.currentThread().getName());
        }
    }
}
