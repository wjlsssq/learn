package io.github.wjlsssq.learn.order.application;

import io.github.wjlsssq.learn.order.domain.Models.Event;
import io.github.wjlsssq.learn.order.domain.OrderError;
import io.github.wjlsssq.learn.order.persistence.OrderMapper;
import io.github.wjlsssq.learn.web.error.BusinessException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 消息领取只占用一个短数据库事务；绝不持有数据库行锁等待 MQ 网络确认。 */
@Service
public class OutboxTransactions {
    private final OrderMapper mapper;
    public OutboxTransactions(OrderMapper mapper) { this.mapper = mapper; }

    @Transactional(timeout = 5, rollbackFor = Exception.class)
    public Event claim() {
        Event candidate = mapper.claimCandidate();
        if (candidate == null) return null;
        String token = UUID.randomUUID().toString();
        mapper.claim(candidate.id(), token);
        return mapper.event(candidate.id());
    }

    /** 修复消息失败原因后重放，沿用原事件标识并记录操作。不是无限自动重试开关。 */
    @Transactional(timeout = 5, rollbackFor = Exception.class)
    public void replay(String id) {
        Event event = mapper.event(id);
        if (event == null) throw new BusinessException(OrderError.NOT_FOUND);
        if (mapper.replay(id) != 1) throw new BusinessException(OrderError.INVALID_STATE);
        mapper.history(event.orderId(), "EVENT_REPLAY", MDC.get("traceId"), Thread.currentThread().getName());
    }
}
