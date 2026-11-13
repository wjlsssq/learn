package io.github.wjlsssq.learn.order;

import io.github.wjlsssq.learn.web.config.WebSupportConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 订单工程独立启动入口。组件扫描限定当前工程，不扫描其他学习工程的业务代码。
 * MapperScan 将 MyBatis 接口交给框架创建代理；XML 中的 SQL 才是实际数据库操作。
 * EnableScheduling 开启消息补投与超时关单，二者都从数据库恢复工作，不依赖内存队列保活。
 */
@SpringBootApplication
@EnableScheduling
@MapperScan("io.github.wjlsssq.learn.order.persistence")
@Import(WebSupportConfiguration.class)
public class OrderApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }
}
