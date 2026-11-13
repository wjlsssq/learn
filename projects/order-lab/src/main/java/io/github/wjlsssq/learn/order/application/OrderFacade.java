package io.github.wjlsssq.learn.order.application;

import io.github.wjlsssq.learn.order.domain.Models.*;
import io.github.wjlsssq.learn.order.domain.OrderError;
import io.github.wjlsssq.learn.order.persistence.OrderMapper;
import io.github.wjlsssq.learn.web.error.BusinessException;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 接口与事务之间的协调层。锁由请求线程获取和释放，不把已持锁的工作转交异步线程。
 * Redis 锁降低同订单竞争，但无法抵御长暂停/主从切换，所以数据库行锁与状态条件才是最终防线。
 */
@Service
public class OrderFacade {
    private static final Logger log = LoggerFactory.getLogger(OrderFacade.class);
    private final OrderTransactions transactions;
    private final OrderMapper mapper;
    private final RedissonClient redisson;
    public OrderFacade(OrderTransactions transactions, OrderMapper mapper, RedissonClient redisson) {
        this.transactions = transactions; this.mapper = mapper; this.redisson = redisson;
    }

    /** 唯一键冲突在事务代理外捕获，保证失败事务已回滚，再查询已提交的原始结果。 */
    public Order create(CreateOrder request) {
        try {
            return transactions.create(request);
        } catch (DuplicateKeyException conflict) {
            Order previous = mapper.byRequest(request.buyerId(), request.requestKey());
            if (previous == null) throw conflict;
            return transactions.verify(previous, request);
        }
    }

    /** 不指定 leaseTime 的 tryLock 使用看门狗；最多等待 300ms，避免请求无限占用线程。 */
    public Order transition(String id, boolean payment) {
        RLock lock = redisson.getLock("order-lab:lock:order:" + id);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(300, TimeUnit.MILLISECONDS);
            if (!acquired) throw new BusinessException(OrderError.BUSY);
            if (payment) return transactions.pay(id);
            return transactions.cancel(id, false);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new BusinessException(OrderError.BUSY);
        } finally {
            if (acquired) {
                try {
                    // 过期后可能已经换持有者，不能用 DEL 强制释放；Redisson 内部原子校验所有者。
                    if (lock.isHeldByCurrentThread()) lock.unlock();
                } catch (RuntimeException unlockFailure) {
                    // 解锁失败不能把已提交的业务伪装成回滚；TTL 最终释放，同时保留日志供排查。
                    log.warn("释放订单锁失败 orderId={}", id, unlockFailure);
                }
            }
        }
    }
}
