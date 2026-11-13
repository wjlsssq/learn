package io.github.wjlsssq.learn.order.application;

import io.github.wjlsssq.learn.order.domain.Models.*;
import io.github.wjlsssq.learn.order.domain.OrderError;
import io.github.wjlsssq.learn.order.persistence.OrderMapper;
import io.github.wjlsssq.learn.web.error.BusinessException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 订单事务边界。独立 Bean 让外层门面经过 Spring 代理调用，避免同类自调用导致事务失效。
 * 这里不访问 Redis/MQ，不等待异步任务；事务只覆盖短暂的 MySQL 读写。
 * 每条库存变化都与订单状态、审计日志一起提交或一起回滚。
 */
@Service
public class OrderTransactions {
    private final OrderMapper mapper;
    public OrderTransactions(OrderMapper mapper) { this.mapper = mapper; }

    /** 同一 buyer+requestKey 对应一次意图。唯一索引覆盖并发穿透，失败将回滚库存。 */
    @Transactional(timeout = 10, rollbackFor = Exception.class)
    public Order create(CreateOrder request) {
        // 先拿商品排他锁，再读幂等记录和插入带外键的订单。
        // 避免多个事务先通过外键获得商品共享锁，再同时升级为库存写锁造成死锁。
        // 锁等待结束后才开始普通快照查询，同商品前一笔提交的幂等记录此时已可见。
        Product product = mapper.lockProduct(request.productId());
        if (product == null) throw new BusinessException(OrderError.NOT_FOUND);
        Order previous = mapper.byRequest(request.buyerId(), request.requestKey());
        if (previous != null) return verify(previous, request);
        String id = UUID.randomUUID().toString();
        long amount = Math.multiplyExact(product.priceCents(), request.quantity());
        mapper.insertOrder(id, request, amount);
        if (mapper.reserve(product.id(), request.quantity()) != 1) {
            throw new BusinessException(OrderError.STOCK_SHORTAGE);
        }
        history(id, "CREATED");
        return mapper.find(id);
    }

    /** 重试必须比较业务参数，不能把同一键的新订单错误地视作旧订单成功。 */
    public Order verify(Order previous, CreateOrder request) {
        if (previous.productId() != request.productId() || previous.quantity() != request.quantity()) {
            throw new BusinessException(OrderError.IDEMPOTENCY_CONFLICT);
        }
        return previous;
    }

    /** 模拟支付，不调用真实支付机构；期限由数据库时钟判断，避免应用节点时钟差导致超期支付。 */
    @Transactional(timeout = 10, rollbackFor = Exception.class)
    public Order pay(String id) {
        Order order = requiredLocked(id);
        if ("PAID".equals(order.status())) return order;
        if (mapper.pay(id) != 1) throw new BusinessException(OrderError.INVALID_STATE);
        requireStockChange(mapper.sell(order.productId(), order.quantity()));
        mapper.insertEvent(UUID.randomUUID().toString(), id);
        history(id, "PAID");
        return mapper.find(id);
    }

    /** 取消仅从 PENDING 迁移；重复取消不二次释放库存，支付后的订单不能被取消。 */
    @Transactional(timeout = 10, rollbackFor = Exception.class)
    public Order cancel(String id, boolean expiredOnly) {
        Order order = requiredLocked(id);
        if (expiredOnly && !"PENDING".equals(order.status())) return order;
        if ("CANCELLED".equals(order.status())) return order;
        if (mapper.cancel(id, expiredOnly) != 1) {
            if (expiredOnly) return order;
            throw new BusinessException(OrderError.INVALID_STATE);
        }
        requireStockChange(mapper.release(order.productId(), order.quantity()));
        history(id, expiredOnly ? "EXPIRED" : "CANCELLED");
        return mapper.find(id);
    }

    private Order requiredLocked(String id) {
        Order order = mapper.lockOrder(id);
        if (order == null) throw new BusinessException(OrderError.NOT_FOUND);
        return order;
    }
    private void requireStockChange(int rows) {
        if (rows != 1) throw new IllegalStateException("库存预留数据不一致，事务已回滚");
    }
    private void history(String id, String action) {
        mapper.history(id, action, MDC.get("traceId"), Thread.currentThread().getName());
    }
}
