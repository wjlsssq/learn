package io.github.wjlsssq.learn.order.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.wjlsssq.learn.order.application.OutboxTransactions;
import io.github.wjlsssq.learn.order.config.MessagingConfig;
import io.github.wjlsssq.learn.order.domain.Models.*;
import io.github.wjlsssq.learn.order.persistence.OrderMapper;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Outbox 的工作者：先在工作线程中领取租约，再发送持久消息并等待 Confirm + Return。
 * 线程池丢失的是“扫描机会”，不是订单事件；进程崩溃后租约超时，另一个工作者会重新领取。
 * Confirm 成功但写 SENT 前宕机会重复发送，所以事件 ID 稳定且消费端必须幂等。
 */
@Component
public class OutboxDispatcher {
    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private final OutboxTransactions transactions;
    private final OrderMapper mapper;
    private final RabbitTemplate rabbit;
    private final ObjectMapper json;
    private final ThreadPoolTaskExecutor executor;
    public OutboxDispatcher(OutboxTransactions transactions, OrderMapper mapper, RabbitTemplate rabbit,
            ObjectMapper json, @Qualifier("outboxExecutor") ThreadPoolTaskExecutor executor) {
        this.transactions = transactions; this.mapper = mapper; this.rabbit = rabbit;
        this.json = json; this.executor = executor;
    }

    /** 最多提交四个领取机会；拒绝任务只记录背压，下轮扫描继续，不丢数据库里的发送意图。 */
    @Scheduled(fixedDelay = 2000)
    public void dispatch() {
        mapper.expireExhausted();
        for (int i = 0; i < 4; i++) {
            try {
                executor.execute(new DispatchTask());
            } catch (RejectedExecutionException rejected) {
                log.warn("Outbox 线程池已满，留待下轮扫描");
                break;
            }
        }
    }

    private final class DispatchTask implements Runnable {
        @Override
        public void run() {
            Event event = null;
            try {
                event = transactions.claim();
                if (event == null) return;
                MDC.put("traceId", event.id());
                MessageProperties properties = new MessageProperties();
                properties.setMessageId(event.id());
                properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
                properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                PaidEvent body = new PaidEvent(1, event.id(), event.orderId());
                Message message = new Message(json.writeValueAsBytes(body), properties);
                CorrelationData correlation = new CorrelationData(event.id() + ":" + event.leaseToken());
                rabbit.send(MessagingConfig.EXCHANGE, MessagingConfig.ROUTING_KEY, message, correlation);
                CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
                // Return 在对应 Confirm 完成之前填入；ACK 但无路由绝不能标记为发送成功。
                if (!confirm.isAck() || correlation.getReturned() != null) {
                    throw new IllegalStateException("Broker 拒绝或消息无匹配路由");
                }
                mapper.sent(event.id(), event.leaseToken());
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                log.error("消息发送失败 eventId={}", event == null ? "unclaimed" : event.id(), failure);
                if (event != null) {
                    try {
                        mapper.failedAttempt(event.id(), event.leaseToken(), "发送未确认，请查看关联日志");
                    } catch (RuntimeException databaseFailure) {
                        // 数据库也不可用时保持租约，恢复后自动回收，不能只在内存保存重试。
                        log.error("记录发送失败状态失败，等待租约恢复", databaseFailure);
                    }
                }
            } finally {
                MDC.remove("traceId");
            }
        }
    }
}
