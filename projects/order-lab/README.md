# 订单可靠性实验室

状态：已实现待用户验证。代理未启动服务，未运行构建、类型检查、Lint 或测试。**这是按生产可靠性原则构建的学习工程，不是已经完成生产验收的交易系统。**

从门户点击“订单可靠性实验室”进入整页操作区，保留侧栏和面包屑。没有账号体系，买家标识为学习输入，不是真实身份；“支付”明确是模拟支付，不发生扣款。

## 学什么、怎么读

Docker 初学者可先看 [Docker 学习：从订单工程理解容器](docs/DOCKER.md)，了解本机 Java 如何连接容器中的 MySQL、Redis 和 RabbitMQ，以及端口、变量、数据卷和生命周期的作用。门户文档区也提供“Docker 学习”入口。

先读 [问答过滤与学习路线](docs/LEARNING.md)，在门户中也可以进入“Redis 与 MQ 学习”文档；再读 [架构与接口](docs/DESIGN.md)、[故障验收手册](docs/ACCEPTANCE.md)。本页提供可直接操作的环境与学习步骤。

实际业务链路：

```text
HTTP 下单 → Redis Lua 限流 → MyBatis / MySQL 事务
  ├─ buyer + requestKey 唯一约束
  ├─ 条件 UPDATE：可售 → 预留
  └─ PENDING 订单 + 审计记录
模拟支付 → Redisson 订单锁 → 独立事务 Bean
  ├─ 数据库订单行锁 + 状态/期限条件
  ├─ 预留 → 已售
  └─ PAID + Outbox 同事务提交
后台扫描 → 有界线程池 → 领取数据库租约 → RabbitMQ 持久消息
  └─ Confirm ACK 且无 Return → SENT；否则指数退避或 FAILED
积分消费者 → 数据库事务：消费记录 + 唯一积分流水 + 余额 → 提交 → ACK
失败 → 三次有限重试 → DLX → DLQ → 修复后人工重放原事件
未支付到期 → 数据库扫描 → PENDING 转 CANCELLED + 释放预留
```

## 目录与隔离

- 后端：本目录的 pom.xml、src/main/java 和 src/main/resources。
- 前端：web/OrderWorkbench.vue、web/order-workbench.css；作为本工程的界面模块接入门户构建，业务状态不放进门户。
- 资源：独立 order_lab 数据库、order-lab: Redis 前缀、order-lab RabbitMQ vhost、order-lab.* 队列。
- SQL：原生 MyBatis XML，无 MyBatis-Plus、无 Lambda 查询包装器。
- 公共复用：Java web-support；前端只导入 shared/frontend/http 的公开入口。
- 基础工程仍可单独启动，不需要本工程的中间件。

## 准备和启动（由你执行）

需要 JDK 17、现有仓库的 Node/pnpm、Docker Compose。使用现有 Maven Wrapper，数据库迁移由 Flyway 在启动时执行。首次启动必须能下载 Maven 依赖和 Docker 镜像。

在仓库根目录打开 PowerShell。首次使用时，把 `projects/order-lab/.env.example` 复制为同目录的 `.env`（已有 `.env` 时不要覆盖），在编辑器中填写四个密码：

```powershell
notepad projects/order-lab/.env
```

文件内容示例（替换占位文字，不要留空）：

```dotenv
ORDER_DB_PASSWORD='换成你的本地数据库密码'
ORDER_DB_ROOT_PASSWORD='换成不同的本地管理员密码'
ORDER_REDIS_PASSWORD='换成你的本地Redis密码'
ORDER_MQ_PASSWORD='换成你的本地MQ密码'
ORDER_LAB_ENABLED=true
```

使用单行、单引号包裹的密码，密码内不要包含单引号；不使用 `${变量}` 引用。保存为 UTF-8，文件名为 `.env`，不是 `.env.txt`。然后执行：

```powershell
pnpm order:up
pnpm order:ps
```

等待 MySQL 为 healthy，并确认 Redis 和 RabbitMQ 正常运行后，构建并启动 Java：

```powershell
.\mvnw.cmd -pl projects/order-lab -am package -DskipTests
pnpm order:start
```

`order:up`、`order:ps`、`order:stop` 和 `order:start` 调用 `tooling/order-lab.mjs`，每次读取同一份 `.env`，文件中的 `ORDER_*` 优先于终端同名变量。Docker 和 Java 分别从脚本继承配置，无需手写 `$env:`。`order:start` 只运行已构建 JAR，不自动构建或启动 Docker。Linux/macOS 把 Maven 命令换成 `./mvnw`；这些 pnpm 命令不变。直接执行 `java -jar` 仍不会自动读取 `.env`。

修改 `.env` 后，对中间件重新执行 `pnpm order:up`，对 Java 先 Ctrl+C 再 `pnpm order:start`。密码变更还需同步服务中的真实账号，旧卷不会自动改密。`.env` 已被 Git 忽略，实际密码不提交。

新增了 @learn/order-lab-web 工作区包。先在仓库根目录执行 pnpm install --frozen-lockfile 建立新包链接（锁文件复用现有 Vue/TypeScript 版本，没有新增第三方前端版本），再运行 pnpm dev，打开 http://127.0.0.1:5173/#projects/order-lab。代理没有执行依赖安装。

| 资源 | 本机端口 | 说明 |
| --- | --- | --- |
| 门户 | 5173 | /order-api 代理到订单后端 |
| 订单 API | 18081 | 独立于基础服务 18080 |
| MySQL | 13306 | 库 order_lab，用户 order_app |
| Redis | 16379 | 有密码，AOF 开启 |
| RabbitMQ AMQP | 15672 | 容器内实际 5672 |
| RabbitMQ 管理页面 | 15673 | http://127.0.0.1:15673，用户 order_app |

ORDER_DB_URL、ORDER_DB_USER、ORDER_REDIS_HOST/PORT、ORDER_MQ_HOST/PORT/USER、ORDER_PORT 可覆盖地址；修改后端端口时同步门户 Vite 代理。本地镜像采用 mysql:8.4、redis:7.4、rabbitmq:4.1-management 系列标签，会接收补丁更新；**严格重现及上线前需核验版本、安全公告并固定镜像摘要**。Java 新增直接依赖固定为 MyBatis Starter 3.0.5、Redisson 3.52.0，其余由仓库 Spring Boot BOM 管理，兼容性尚待用户构建验证。

停止 Java 用 Ctrl+C。停止中间件并保留数据：

```powershell
pnpm order:stop
```

不要为了重试启动而删除卷；已有卷中的数据库/MQ密码不会因更改环境变量自动重置。

## 第一次学习操作

1. 刷新数据，查看两种种子商品的可售/预留/已售库存。
2. 提交一件商品，观察 PENDING 与预留库存；再次提交相同幂等键，应返回同一订单。
3. 更改数量但不换键，应得到 409；“新建下单意图”才代表另一笔订单。
4. 点击模拟支付，观察 PAID、Outbox SENT、积分账户；消息异步处理，稍后手动刷新。
5. 对 SENT 事件重放，再刷新：投递次数可变化，但积分只能增加一次。
6. 新建订单后取消，应归还预留库存；另一笔不支付的订单在 15 分钟后由扫描任务关闭。
7. 打开并发实验，先复用同一键，再换为不同键。结果会显示真实线程名、拒绝及业务冲突。
8. 请求超时不能当成失败回滚，用“按键核对结果”查询；404 也可能是原事务尚未结束，稍后用原键重试。

界面仅展示最近 50 条订单、消息、前 50 个账户，是有界的学习观察窗口，不是完整运营后台。订单历史最多 100 条；更长期的数据分析使用数据库只读查询。SQL 时间为 UTC；界面明确显示，避免与本机时区混淆。

## 运行与验收边界

[验收手册](docs/ACCEPTANCE.md)包含真实集成用例、停机恢复、支付取消竞争、锁失效、路由错误与 DLQ 检查。集成测试默认跳过；需显式 ORDER_INTEGRATION_TESTS=true 才会连接真实资源。

生产上线还缺少：真实支付渠道及验签、退款和支付对账、身份与授权、TLS/密钥管理、数据库和 MQ 多节点高可用、备份恢复演练、监控告警接入、容量与压测、故障演练、依赖安全审查。无账号和单机学习要求与直接公网生产部署不兼容；不能用“代码有锁、有 MQ”替代这些验收。
