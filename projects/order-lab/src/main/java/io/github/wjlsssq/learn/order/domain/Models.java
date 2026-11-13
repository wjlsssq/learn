package io.github.wjlsssq.learn.order.domain;

import java.time.LocalDateTime;
import jakarta.validation.constraints.*;

/**
 * 本工程不可变数据快照。record 是 JDK 17 的数据载体，字段在构造后不可修改，
 * 避免多个工作线程共享一个可以随意改写的实体；状态变更必须通过显式 SQL。
 * 金额单位为分，数据库时间统一为 UTC；buyerId 只是学习用标识，不代表已认证用户。
 */
public final class Models {
    private Models() { }
    public record CreateOrder(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String buyerId,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{8,64}") String requestKey,
        @NotNull @Positive Long productId,
        @Min(1) @Max(10) int quantity) { }
    public record Product(long id, String name, long priceCents, int available, int reserved, int sold) { }
    public record Order(String id, String buyerId, String requestKey, long productId,
        int quantity, long amountCents, String status, LocalDateTime createdAt,
        LocalDateTime expiresAt, LocalDateTime updatedAt) { }
    public record Event(String id, String orderId, String status, int attempts,
        LocalDateTime nextAttemptAt, String leaseToken, LocalDateTime leaseUntil,
        String lastError, LocalDateTime createdAt) { }
    /** MQ 只传事件和订单标识；消费者回查已提交的订单，不信任消息传来的积分金额。 */
    public record PaidEvent(int version, String eventId, String orderId) { }
}
