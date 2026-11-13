package io.github.wjlsssq.learn.order.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import io.github.wjlsssq.learn.order.application.PointsTransactions;
import io.github.wjlsssq.learn.order.config.MessagingConfig;
import io.github.wjlsssq.learn.order.domain.Models.PaidEvent;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * ACK 必须发生在独立事务 Bean 正常返回以后，此时数据库提交已完成。
 * 异常不在此处吞掉：容器进行三次有限重试，最终 NACK(requeue=false) 转往 DLQ。
 * ACK 网络丢失可能再次投递，业务事务内的幂等约束防止重复积分。
 * Channel 只在当前监听线程使用，不转交 CompletableFuture 或线程池。
 */
@Component
public class PointsConsumer {
    private final PointsTransactions points;
    private final ObjectMapper json;
    public PointsConsumer(PointsTransactions points, ObjectMapper json) { this.points = points; this.json = json; }
    @RabbitListener(queues = MessagingConfig.QUEUE)
    public void receive(Message message, Channel channel) throws Exception {
        if (message.getBody().length > 4096) throw new IllegalArgumentException("消息超过契约大小");
        PaidEvent event = json.readValue(message.getBody(), PaidEvent.class);
        points.award(event);
        channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
    }
}
