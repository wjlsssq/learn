# 从问答提炼出的学习链路

过滤原则：去掉重复的“回答正确”、选择题鼓励语、重复代码和面试口号；保留你明确困惑的机制，以及选错后需要验证的结论。不把原问答中的简化代码直接当生产实现。

## 需要优先纠正的理解

| 你的问题或选择 | 正确结论 | 在工程中如何观察 |
| --- | --- | --- |
| Redis 单线程会不会阻塞 | 长命令、大 Key 和耗时 Lua 会阻塞其他命令；阻塞式取队列通常只挂起客户端 | RateLimiter 的 Lua 只有常数次命令，不扫描库存或遍历所有键 |
| DECR 原子，为什么 GET+SET 会丢更新 | 单命令串行不保证跨命令组合原子；DECR 原子也不代表库存不能减到负数 | 库存使用 MySQL 带 available 条件的 UPDATE；Redis 计数与 TTL 用 Lua |
| GET 确认是我的锁，为什么 DEL 还能删别人的锁 | 两条命令之间可能过期，别人重建同名键；检查结果已过时 | Redisson 的原子所有者校验解锁；禁止自行 GET 后 DEL |
| 锁过期，原业务还执行吗 | 会；Redis 无权停止 Java 线程 | 即使不调用 OrderFacade 的 Redis 锁，事务行锁和状态条件仍保护支付/取消 |
| 一遍代码是不是一个线程 | 代码是逻辑，线程是执行者；同一方法可被多个线程同时执行 | 链路记录中出现 http、order-lab、Rabbit 监听线程名 |
| 可重入是不是同一类/方法就行 | 判断客户端与线程身份；普通同步调用可重入，异步线程不能借用父线程身份 | OrderFacade 在同一线程获取/释放锁；不持锁等待需要同锁的子任务 |
| CompletableFuture 默认选了固定线程池 | 默认通常是 commonPool；并行度不足时实现有后备策略，不是 newFixedThreadPool | 不用公共池执行数据库或 MQ 阻塞操作，注入独立有界线程池 |
| core=2、max=5、queue=3，第六个任务选了入队 | 前两项核心线程，后三项排队，第六项创建第三线程；已有任务未完成时成立 | 配置源码 + 并发实验；实验不保证每次相同顺序，因为真实任务会结束 |
| CallerRunsPolicy 选了抛异常 | 调用者执行（未关闭时）；AbortPolicy 才抛拒绝异常 | 使用 AbortPolicy；Outbox 保留数据库事件；实验明确返回 REJECTED |
| 线程池宕机后任务会怎样 | 内存等待任务会丢；线程池不是可靠任务存储 | Outbox 持久保存业务意图，队列只保存扫描机会 |
| MQ 事务提交后 ACK 前崩溃 | 会重投，必须业务幂等；先 ACK 则可能永久漏业务 | PointsTransactions 返回后才 ACK，同事务写消费记录/积分流水/余额 |
| DLX 路由不匹配选了自动回原队列 | 不会自动返回；默认死信转移可能丢失，可靠模式会等待目标确认 | 本工程 Quorum + at-least-once 死信策略；路由键精确一致 |
| Topic 题尚未回答 | order.*.success 匹配 pay.success、cancel.success，不匹配 pay.refund.success | 本工程只需 Direct；不为展示技术额外引入 Topic/Fanout |

## 章节一：原子性有作用范围

Lua 的原子性是执行期间没有其他命令插入，并不是脚本出错后回滚。保持脚本短小，先校验输入再写，不在生产 Redis 上用长 Lua 人为阻塞。

本工程 Redis 限流是固定窗口，存在窗口边界突发，不宣称平滑限速。它不是库存的事实来源：若 Redis 先扣库存、MySQL 随后失败，还要实现预留令牌、补偿、对账、重建等协议。本阶段直接使用数据库库存事务，不留下两套库存“迟早一致”的隐患。

阅读：resources/lua/rate-limit.lua → application/RateLimiter.java → mapper/OrderMapper.xml 的 reserve。

## 章节二：锁是协调工具，数据库约束才是业务防线

Redisson tryLock(300, MILLISECONDS) 未指定租期，启用看门狗续期。显式 leaseTime 的重载则按固定租期释放，不以看门狗无限延长。续期失败、长 GC 暂停、主从切换可能让互斥失效。

安全解锁只解决“不误删他人的锁”；不解决“旧业务继续运行”。本工程依靠 MySQL 行锁、PENDING 条件更新和库存约束。不能将这套保证推广到发短信、外部扣款等非事务副作用；外部系统需要幂等键、对账或 fencing token 协议。

可重入内部可理解为 Hash 持有者标识和计数；底层细节应以固定 Redisson 版本为准。加几次就要释放几次，不能拿普通 Java 局部变量当分布式锁。

阅读：OrderFacade → OrderTransactions。所有事务方法经独立 Bean 代理调用；没有把 @Transactional 和异步线程混在同一个大方法中。

## 章节三：线程池解决执行，不解决可靠交付

七参数：核心数、最大数、空闲时间、单位、队列、线程工厂、拒绝策略。顺序是核心 → 入队 → 扩到最大 → 拒绝；无界队列通常阻止继续扩容，还可能耗尽内存。

本工程：
- outbox-：2/4/16，负责网络发送；入队时未领取事件，排队不会耗尽事件租约。
- order-lab-：2/4/8，负责明确标识的学习实验。
- RabbitMQ：2–4 个消费线程，每个 prefetch 8；ACK 在自己的 Channel/线程执行。
- HTTP 请求线程：处理校验、短事务；不持有 Redis 锁等待线程池任务。

任务超时不代表已经停止，更不代表已经提交的数据库事务自动撤销。实验返回 UNKNOWN 时按 requestKey 核对。MDC 日志上下文在线程之间手动传递、finally 清理，事务上下文不跨线程传递。

## 章节四：消息“至少一次”，业务效果幂等

生产者 Confirm 和消费者 ACK 是两套协议。Confirm 不能证明消费者完成，也不能独自证明目标队列正确路由；mandatory + Return 必须一起检查。

Outbox 解决“提交订单后、发送 MQ 前宕机”。租约 token 解决旧发送者覆盖新发送者结果，但不能阻止旧工作者再发一份消息；因此稳定事件 ID 与消费者幂等仍不可少。

积分事务先锁订单行，再读取消费记录。不同实例重复处理同订单会在数据库串行化。唯一积分流水进一步限制同订单业务效果；不将所有唯一键异常当成成功。

死信是异常隔离区，不是成功结果。应用有限重试耗尽后拒绝入 DLQ，不无限 requeue。重放从可信 Outbox 恢复原始事件，修复后可重复发送；DLQ 中的证据不会被页面自动删除。

## 官方依据

- [Redis Lua 执行语义](https://redis.io/docs/latest/interact/programmability/eval-intro/)
- [Redisson 锁与看门狗](https://redisson.pro/docs/data-and-services/locks-and-synchronizers/)
- [JDK 17 CompletableFuture](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/CompletableFuture.html)
- [JDK 17 ThreadPoolExecutor](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/ThreadPoolExecutor.html)
- [RabbitMQ Confirm / ACK](https://www.rabbitmq.com/docs/confirms)
- [RabbitMQ 死信与可靠转移](https://www.rabbitmq.com/docs/dlx)
- [MySQL 锁定读取](https://dev.mysql.com/doc/refman/8.0/en/innodb-locking-reads.html)
- [MyBatis Starter 兼容矩阵](https://mybatis.org/spring-boot-starter/mybatis-spring-boot-autoconfigure/)

上述机制已查官方资料；实现仍需运行验收，不把文档研究当成测试通过。
