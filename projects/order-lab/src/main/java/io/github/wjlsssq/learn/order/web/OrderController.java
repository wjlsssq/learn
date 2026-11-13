package io.github.wjlsssq.learn.order.web;

import io.github.wjlsssq.learn.order.application.*;
import io.github.wjlsssq.learn.order.domain.Models.*;
import io.github.wjlsssq.learn.order.domain.OrderError;
import io.github.wjlsssq.learn.order.persistence.OrderMapper;
import io.github.wjlsssq.learn.web.error.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * HTTP 适配层只做输入校验、实验开关与服务调用，不在 Controller 拼接 SQL 或开启线程事务。
 * 无账户系统，buyerId 是模拟主体。服务默认绑定回环地址；该接口不能直接暴露公网。
 * 真实支付需独立支付适配器、验签和支付流水，本工程仅提供明确标注的模拟支付。
 */
@RestController
@RequestMapping("/api/orders")
@Validated
public class OrderController {
    private final OrderFacade orders;
    private final OrderMapper mapper;
    private final RateLimiter limiter;
    private final OutboxTransactions outbox;
    private final boolean labEnabled;
    public OrderController(OrderFacade orders, OrderMapper mapper, RateLimiter limiter,
            OutboxTransactions outbox, @Value("${order.lab-enabled}") boolean labEnabled) {
        this.orders = orders; this.mapper = mapper; this.limiter = limiter;
        this.outbox = outbox; this.labEnabled = labEnabled;
    }

    @GetMapping("/products")
    public List<Product> products() { return mapper.products(); }

    /** 查询窗口最多五十条，页面明确标记窗口；不会无界加载全部订单。 */
    @GetMapping
    public List<Order> recent() { return mapper.recent(); }

    /** 超时以后可以用原意图查询，不要求调用者已经收到服务端生成的订单号。 */
    @GetMapping("/request")
    public Order byRequest(
            @RequestParam @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String buyer,
            @RequestParam @Pattern(regexp = "[A-Za-z0-9_-]{8,64}") String key) {
        Order order = mapper.byRequest(buyer, key);
        if (order == null) throw new BusinessException(OrderError.NOT_FOUND);
        return order;
    }

    @GetMapping("/{id}")
    public Order find(@PathVariable @Pattern(regexp = "[a-f0-9-]{36}") String id) {
        Order order = mapper.find(id);
        if (order == null) throw new BusinessException(OrderError.NOT_FOUND);
        return order;
    }

    @PostMapping
    public Order create(@Valid @RequestBody CreateOrder request) {
        limiter.check(request.buyerId());
        return orders.create(request);
    }

    @PostMapping("/{id}/simulate-payment")
    public Order pay(@PathVariable @Pattern(regexp = "[a-f0-9-]{36}") String id) {
        requireLab();
        return orders.transition(id, true);
    }

    @PostMapping("/{id}/cancel")
    public Order cancel(@PathVariable @Pattern(regexp = "[a-f0-9-]{36}") String id) {
        return orders.transition(id, false);
    }

    @GetMapping("/{id}/history")
    public List<Map<String, Object>> history(@PathVariable @Pattern(regexp = "[a-f0-9-]{36}") String id) {
        return mapper.historyFor(id);
    }

    @GetMapping("/operations")
    public Map<String, Object> operations() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("events", mapper.events());
        result.put("eventCounts", mapper.eventCounts());
        result.put("accounts", mapper.accounts());
        result.put("labEnabled", labEnabled);
        return result;
    }

    @PostMapping("/events/{id}/replay")
    public Map<String, String> replay(@PathVariable @Pattern(regexp = "[a-f0-9-]{36}") String id) {
        requireLab();
        outbox.replay(id);
        return Map.of("status", "PENDING");
    }

    private void requireLab() {
        if (!labEnabled) throw new BusinessException(OrderError.LAB_DISABLED);
    }
}
