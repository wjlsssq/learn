package io.github.wjlsssq.learn.order;

import io.github.wjlsssq.learn.order.application.*;
import io.github.wjlsssq.learn.order.domain.Models.*;
import io.github.wjlsssq.learn.order.persistence.OrderMapper;
import io.github.wjlsssq.learn.web.error.BusinessException;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 真 MySQL/Redis/MQ 集成验收，默认跳过，必须显式设置 ORDER_INTEGRATION_TESTS=true。
 * 每次用独立商品与买家，禁止连接生产库；不清理流水，便于学习审计与重放。
 * 这些用例不使用 mock 代替数据库并发约束。代理仅编写，不执行。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "ORDER_INTEGRATION_TESTS", matches = "true")
class OrderReliabilityTest {
    @Autowired OrderFacade facade;
    @Autowired OrderTransactions transactions;
    @Autowired PointsTransactions points;
    @Autowired OrderMapper mapper;
    @Autowired JdbcTemplate jdbc;
    private long product;
    private String buyer;

    @BeforeEach
    void prepareIsolatedProduct() {
        // 测试商品也会显示到前端，限定在 JavaScript 安全整数范围内，避免 JSON 数字精度丢失。
        product = Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 1000000000000L) + 1000;
        buyer = "test-" + UUID.randomUUID();
        jdbc.update("INSERT INTO product(id,name,price_cents,available) VALUES(?,?,1000,5)",
            product, "integration-" + buyer);
    }

    @Test
    void duplicateRequestsReserveOnlyOnce() throws Exception {
        CreateOrder request = new CreateOrder(buyer, UUID.randomUUID().toString(), product, 1);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            tasks.add(new Callable<String>() {
                public String call() { return facade.create(request).id(); }
            });
        }
        List<String> ids = run(tasks);
        assertEquals(1, new HashSet<>(ids).size());
        assertEquals(4, mapper.product(product).available());
        assertEquals(1, mapper.product(product).reserved());
    }

    @Test
    void concurrentOrdersCannotOversell() throws Exception {
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            final String key = UUID.randomUUID().toString();
            tasks.add(new Callable<String>() {
                public String call() {
                    try { return facade.create(new CreateOrder(buyer, key, product, 1)).id(); }
                    catch (BusinessException expected) {
                        assertEquals("ORDER_STOCK_SHORTAGE", expected.errorCode().code());
                        return "SOLD_OUT";
                    }
                }
            });
        }
        List<String> ids = run(tasks);
        assertEquals(7, Collections.frequency(ids, "SOLD_OUT"));
        assertEquals(0, mapper.product(product).available());
        assertEquals(5, mapper.product(product).reserved());
        assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order WHERE buyer_id=?", Integer.class, buyer));
    }

    @Test
    void repeatedPaymentAndDeliveryAwardOnlyOnce() {
        Order order = facade.create(new CreateOrder(buyer, UUID.randomUUID().toString(), product, 1));
        transactions.pay(order.id());
        transactions.pay(order.id());
        String eventId = jdbc.queryForObject("SELECT id FROM outbox_event WHERE order_id=?", String.class, order.id());
        PaidEvent event = new PaidEvent(1, eventId, order.id());
        points.award(event);
        points.award(event);
        assertEquals(10L, jdbc.queryForObject("SELECT points FROM points_account WHERE buyer_id=?", Long.class, buyer));
        assertEquals(1, mapper.product(product).sold());
        assertEquals(0, mapper.product(product).reserved());
    }

    @Test
    void paymentAndCancellationCannotBothWinEvenWithoutRedisLock() throws Exception {
        final Order order = facade.create(new CreateOrder(buyer, UUID.randomUUID().toString(), product, 1));
        List<Callable<String>> tasks = new ArrayList<>();
        tasks.add(new Callable<String>() {
            public String call() {
                try { return transactions.pay(order.id()).status(); }
                catch (BusinessException expected) { return expected.errorCode().code(); }
            }
        });
        tasks.add(new Callable<String>() {
            public String call() {
                try { return transactions.cancel(order.id(), false).status(); }
                catch (BusinessException expected) { return expected.errorCode().code(); }
            }
        });
        run(tasks);
        Order finalOrder = mapper.find(order.id());
        Product stock = mapper.product(product);
        assertEquals(0, stock.reserved());
        if ("PAID".equals(finalOrder.status())) {
            assertEquals(1, stock.sold()); assertEquals(4, stock.available());
        } else {
            assertEquals("CANCELLED", finalOrder.status());
            assertEquals(0, stock.sold()); assertEquals(5, stock.available());
        }
    }

    /** 测试也限制队列和等待时间；finally 关闭线程池，避免验收后留下工作线程。 */
    private List<String> run(List<Callable<String>> tasks) throws Exception {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(4, 4, 10, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(20), new ThreadPoolExecutor.AbortPolicy());
        try {
            List<Future<String>> futures = executor.invokeAll(tasks, 30, TimeUnit.SECONDS);
            List<String> results = new ArrayList<>();
            for (Future<String> future : futures) results.add(future.get());
            return results;
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
