package io.github.wjlsssq.learn.order.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 本机拓扑：Direct Exchange 精确匹配路由键。主队列和 DLQ 都持久化并使用 Quorum。
 * at-least-once 死信转移需要 reject-publish，目标不可达时保留源消息，不能使用默认丢弃策略。
 * 生产环境应将 DLX 参数迁入 Broker policy，并部署跨节点多数派；单节点 Compose 不是 HA。
 */
@Configuration
public class MessagingConfig {
    public static final String EXCHANGE = "order-lab.events";
    public static final String QUEUE = "order-lab.points";
    public static final String ROUTING_KEY = "order.paid";
    @Bean
    public Declarables orderTopology() {
        DirectExchange events = new DirectExchange(EXCHANGE, true, false);
        DirectExchange dead = new DirectExchange("order-lab.dlx", true, false);
        Queue points = QueueBuilder.durable(QUEUE).quorum()
            .deadLetterExchange("order-lab.dlx").deadLetterRoutingKey("order.dead")
            .withArgument("x-dead-letter-strategy", "at-least-once")
            .withArgument("x-overflow", "reject-publish")
            .withArgument("x-max-length", 10000)
            .withArgument("x-delivery-limit", 20).build();
        Queue dlq = QueueBuilder.durable("order-lab.dead").quorum().build();
        return new Declarables(events, dead, points, dlq,
            BindingBuilder.bind(points).to(events).with(ROUTING_KEY),
            BindingBuilder.bind(dlq).to(dead).with("order.dead"));
    }
}
