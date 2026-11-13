package io.github.wjlsssq.learn.order.domain;

import io.github.wjlsssq.learn.web.error.ErrorCode;
import org.springframework.http.HttpStatus;

/** 安全返回给调用者的业务错误；服务异常的堆栈由公共 Web 模块记录，不泄露到页面。 */
public enum OrderError implements ErrorCode {
    NOT_FOUND(HttpStatus.NOT_FOUND, "订单或商品不存在"),
    STOCK_SHORTAGE(HttpStatus.CONFLICT, "库存不足"),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "同一幂等键不能提交不同的商品或数量"),
    INVALID_STATE(HttpStatus.CONFLICT, "当前订单状态或支付期限不允许此操作"),
    BUSY(HttpStatus.SERVICE_UNAVAILABLE, "资源繁忙，请稍后使用原幂等键重试"),
    RATE_LIMIT(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后重试"),
    LAB_DISABLED(HttpStatus.FORBIDDEN, "本地实验操作尚未开启");

    private final HttpStatus status;
    private final String message;
    OrderError(HttpStatus status, String message) { this.status = status; this.message = message; }
    public String code() { return "ORDER_" + name(); }
    public HttpStatus status() { return status; }
    public String defaultMessage() { return message; }
}
