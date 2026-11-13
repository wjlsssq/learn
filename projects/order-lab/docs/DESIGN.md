# 订单工程设计与接口

## 边界与取舍

这是独立的模块化单体：web 接 HTTP、application 编排事务、domain 定义业务数据、persistence 放 MyBatis 接口、messaging 收发消息、config 组装资源。MQ 消费端与订单暂时共用数据库及部署，便于观察本地事务；不伪装成拥有独立数据库的积分微服务。未来拆分时必须改消息契约、消费事实来源和幂等存储，不能直接跨服务查业务表。

单商品订单、整单支付/取消、整数分金额、15 分钟支付期限。暂不支持购物车、多 SKU 锁顺序、真实支付、退款、优惠券、地址或物流；这些能力需要独立状态及对账设计，不应添加空壳接口。

## 一致性约束

1. available + reserved + sold 对单商品守恒，且每项非负。种子数据不自动重置。
2. buyer_id + request_key 唯一；同键不同商品或数量返回 409。
3. PENDING 只能转 PAID 或 CANCELLED，不能同时成功；已支付重试返回原结果。
4. PAID 与唯一 Outbox 事件在同一 MySQL 事务提交。
5. 消费记录、唯一积分流水和积分余额同事务，ACK 晚于提交。
6. 发送租约的领取使用 FOR UPDATE SKIP LOCKED；结果更新必须匹配 lease_token。
7. 消费者不依赖 Outbox 的 SENT 才消费：消息可能早于生产者更新 SENT 到达。

所有库存变化走条件 UPDATE。数据库约束防止负数，但不能单独保证业务守恒；守恒仍靠事务与验收 SQL。发生库存预留不一致时回滚并报错，不悄悄归零。

## 故障与恢复

下单先锁商品行，再查询幂等记录并插入带商品外键的订单，避免共享外键锁升级为写锁的竞争。单商品库存写入本身需要串行约束；此设计优先正确性，不宣称热点 SKU 极限吞吐。后续多 SKU 订单必须统一商品加锁顺序，并设计死锁重试边界。

| 故障窗口 | 行为 |
| --- | --- |
| 库存不足 | 事务回滚订单，不留无库存的成功订单 |
| 响应超时但事务已提交 | 原 requestKey 查询/重试，不生成新键 |
| Redis 不可用 | 新下单限流或锁操作失败，不自动绕过；数据库事实仍存在 |
| MQ 不可用 | 支付可落库；Outbox 退避重试，最多 10 次，之后 FAILED |
| 发送后进程宕机 | 60 秒租约到期后重新领取，可能重复发送 |
| 第十次发送过程中宕机 | 扫描将过期租约转 FAILED，避免永久 SENDING |
| 确认无路由 | 不记 SENT，等待修复绑定并补投 |
| 消费事务提交后宕机 | 重投查询消费记录，不重复积分 |
| 消费反复失败 | 容器最多 3 次，之后拒绝转 DLQ；Broker delivery-limit 限制反复断线重投 |
| 到期扫描宕机 | 恢复后按数据库 expires_at 继续关单；允许延迟关闭，不允许超期支付 |

Outbox 重试间隔为 min(300, 2^attempts) 秒；数据库查询、连接池、Redis、锁等待、Confirm 均有限时。限时不等于强制终止所有底层操作，过期工作者可能仍发消息，所以不能省掉消费幂等。

## 接口

前端开发代理前缀 /order-api；下表为后端路径。返回原始 JSON，错误使用公共 ApiError（code、message、traceId 等）。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | /api/orders/products | 前 100 个商品 |
| POST | /api/orders | buyerId、requestKey、productId、quantity |
| GET | /api/orders | 最近 50 笔 |
| GET | /api/orders/request?buyer=…&key=… | 按幂等键恢复未知结果 |
| GET | /api/orders/{id} | 指定订单 |
| POST | /api/orders/{id}/simulate-payment | 模拟支付，实验开关保护 |
| POST | /api/orders/{id}/cancel | 取消未支付订单 |
| GET | /api/orders/{id}/history | 线程/请求标识/状态审计 |
| GET | /api/orders/operations | 消息、状态计数、积分账户、实验开关 |
| POST | /api/orders/events/{id}/replay | 重放 SENT/FAILED 事件，实验开关保护 |
| POST | /api/orders/lab/concurrent | buyerId、productId、count(1–30)、sameRequestKey；默认未注册 |
| GET | /actuator/health | 真实依赖健康状态，不公开详细凭据 |

400 参数非法、404 不存在、409 库存/状态/幂等冲突、429 限流、503 锁繁忙。其他未知依赖异常由公共处理器返回安全的 500，不把技术堆栈返回页面。写请求的 5xx/超时需先查结果再用原键重试。

## 可观察性与生产门槛

已提供订单审计、traceId、工作线程名、Outbox 状态/次数/错误摘要、健康接口。不是完整监控平台。需要接入外部采集并告警：FAILED 数、最老未消费事件年龄、DLQ 深度、消费延迟、库存不变量、连接池饱和、拒绝数、Redis 延迟。

本地 Compose 是单节点，持久卷不能替代备份。上线前应核验：三节点以上 Quorum 与故障域、数据库高可用和备份恢复、Redis 故障策略、TLS/ACL/密钥轮换、应用权限、边界限流、真实支付验签和对账、容量与 SLO、依赖版本及镜像摘要。事务隔离不跨 MySQL/RabbitMQ/Redis 提供全局 ACID，本工程采用至少一次传递与幂等业务效果。
