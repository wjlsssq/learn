package io.github.wjlsssq.learn.order.persistence;

import io.github.wjlsssq.learn.order.domain.Models.*;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/**
 * 持久化端口：所有 SQL 放入同名 XML，参数使用 #{} 绑定，禁止拼接客户端 SQL。
 * 返回 int 的写操作必须检查影响行数；返回 null 的查询由应用服务转换为明确业务错误。
 * @Param 固定 XML 使用的参数名，不依赖编译器对多参数方法的命名。
 */
public interface OrderMapper {
    List<Product> products();
    Product product(long id);
    Product lockProduct(long id);
    Order find(String id);
    Order lockOrder(String id);
    Order byRequest(@Param("buyer") String buyer, @Param("key") String key);
    List<Order> recent();
    int insertOrder(@Param("id") String id, @Param("request") CreateOrder request, @Param("amount") long amount);
    int reserve(@Param("id") long id, @Param("quantity") int quantity);
    int sell(@Param("id") long id, @Param("quantity") int quantity);
    int release(@Param("id") long id, @Param("quantity") int quantity);
    int pay(String id);
    int cancel(@Param("id") String id, @Param("expiredOnly") boolean expiredOnly);
    List<String> expired();
    void history(@Param("id") String id, @Param("action") String action,
                 @Param("trace") String trace, @Param("thread") String thread);
    List<Map<String, Object>> historyFor(String id);
    void insertEvent(@Param("id") String id, @Param("orderId") String orderId);
    Event claimCandidate();
    int claim(@Param("id") String id, @Param("token") String token);
    Event event(String id);
    int sent(@Param("id") String id, @Param("token") String token);
    int failedAttempt(@Param("id") String id, @Param("token") String token, @Param("error") String error);
    int expireExhausted();
    int replay(String id);
    List<Event> events();
    String consumed(String eventId);
    void recordConsumption(@Param("eventId") String eventId, @Param("orderId") String orderId);
    Long awarded(String orderId);
    void insertPoints(@Param("order") Order order, @Param("points") long points);
    void addPoints(@Param("buyer") String buyer, @Param("points") long points);
    List<Map<String, Object>> accounts();
    List<Map<String, Object>> eventCounts();
}
