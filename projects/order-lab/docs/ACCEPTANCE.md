# 验收与故障演练（由用户执行）

状态：全部待执行。不要连接其他工程的资源或生产库。每轮记录提交 SHA、环境版本、输入、SQL结果、日志和恢复耗时；通过后反馈到仓库 PROGRESS。

## 自动集成验收

准备好 README 的环境并启动中间件，不需要另启动 Java；测试会启动自己的 Spring 上下文，使用真实 MySQL/Redis/MQ。

```powershell
$env:ORDER_INTEGRATION_TESTS = 'true'
.\mvnw.cmd -pl projects/order-lab -am test
```

默认不设置该变量时测试跳过。四个用例覆盖：相同意图并发只预留一次、库存5面对12个请求不超卖、重复支付/消费只积分一次、绕过Redis锁仍无法让支付取消同时成功。测试创建独立的 integration-* 商品和 test-* 买家，不清空原业务表，便于查看审计。每次执行会保留数据；不要把种子商品库存当成测试断言。

前端检查（可选，由用户执行）：仓库 pnpm typecheck、pnpm lint、pnpm test、pnpm build。代理没有执行这些命令。

## 手工验收矩阵

| 场景 | 步骤 | 应观察到 |
| --- | --- | --- |
| 幂等提交 | 同买家/同键提交两次，再改数量重试 | 同订单；改参数 409；仅一次预留 |
| 并发售罄 | 在独立学习库选择低库存商品，以不同键并发下单 | available 不为负，成功数量不超过初始可售 |
| 支付取消竞争 | 两终端同时 POST 同订单的支付和取消 | 最终仅一种状态，库存对应一个结果 |
| 支付到期 | 等 15 分钟；或只在隔离库把一笔 PENDING 的 expires_at 调至过去 | 支付 409，扫描取消并释放库存 |
| 重复 MQ | 支付后积分落库，再点“重放原事件” | 消费记录/积分流水不重复，余额不再次增长 |
| MQ 暂停 | Compose stop rabbitmq，支付一单，再 start rabbitmq | Outbox 保留，恢复后补投；若已 FAILED，修复后重放 |
| Confirm 后宕机 | 在 mapper.sent 前打断点并停止 Java，然后重启 | 租约到期后补投，积分仍只一次 |
| 消费提交后 ACK 前宕机 | 在 basicAck 打断点并停止 Java，再重启 | RabbitMQ 重投，数据库幂等生效 |
| 发送路由错误 | 暂时解除主队列 binding；支付后检查事件；恢复 binding | Return 导致不标 SENT，恢复后补投 |
| 消费坏消息 | 管理页面向主交换机发布不符合契约的小 JSON | 三次重试后进入 order-lab.dead，不无限 requeue |
| 重试耗尽 | MQ 长时间不可用，观察 attempts=10 | FAILED 可观察，后续修复和手工重放 |
| 锁到期 | 仅在实验分支临时用显式短 leaseTime 并在事务前暂停，另一进程操作同单 | Java旧线程继续；数据库状态机仍拒绝非法迁移；结束后恢复代码 |
| 线程池饱和 | 并发实验发多组请求，结合断点或低速数据库制造短时积压 | REJECTED 明确；未接收任务不伪装成功 |
| 资源恢复 | 停/启中间件与应用，不删除卷 | 已提交订单、事件和积分仍存在 |
| 门户回归 | 进入工程、后退、刷新直达地址、窄屏、基础工程启动 | 整页内容、正确面包屑、原工程可独立工作 |

DLQ 中的消息不是完成证明。修复有效业务事件的失败原因后，从 Outbox 重放原事件；确认积分/业务记录以后再按运维审批处理 DLQ 留存。没有对应可信 Outbox 的畸形消息不得伪造订单“补偿”。

## 数据库只读核对

```sql
-- 以下第一条应无结果：任何负库存都属于严重问题。
SELECT * FROM product WHERE available < 0 OR reserved < 0 OR sold < 0;
-- 每商品预留应等于待支付订单数量，已售应等于支付订单数量。
SELECT p.id,p.reserved,p.sold,
 COALESCE(SUM(CASE WHEN o.status='PENDING' THEN o.quantity ELSE 0 END),0) expected_reserved,
 COALESCE(SUM(CASE WHEN o.status='PAID' THEN o.quantity ELSE 0 END),0) expected_sold
FROM product p LEFT JOIN purchase_order o ON o.product_id=p.id
GROUP BY p.id,p.reserved,p.sold;
-- 支付订单与 Outbox 一一对应；所有已支付但长期无积分订单需要排查。
SELECT o.id,e.status,e.attempts,l.points
FROM purchase_order o LEFT JOIN outbox_event e ON e.order_id=o.id
LEFT JOIN points_ledger l ON l.order_id=o.id WHERE o.status='PAID';
SELECT status,COUNT(*),MIN(created_at) FROM outbox_event GROUP BY status;
SELECT buyer_id,SUM(points) FROM points_ledger GROUP BY buyer_id;
SELECT buyer_id,points FROM points_account;
```

多条查询不是同一时间快照；并发运行时短暂观察差异需要在一致性快照或停止写入后复核，不能直接判断为超卖。

## 尚不能声称通过的项目

运行兼容性、吞吐量、P99 延迟、长 GC、主从切换、灾备恢复、队列满载、依赖漏洞检查、真实支付与退款均未完成验收。单机 Docker 的成功启动也不能证明多节点生产可用性。
