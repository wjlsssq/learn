# Docker 学习：从订单工程理解容器

本文面向第一次接触 Docker 的读者，以本工程的 `projects/order-lab/compose.yml` 为例。建议先理解运行结构，再逐项读配置，最后自行做观察实验。

本文依据 2026-11-15 的仓库配置及文中链接的官方文档编写。命令供你在仓库根目录的 PowerShell 中执行；代理未执行 Docker、启动、构建或测试，实际结果由你验证。

## 1. Docker 在这个项目里解决什么问题

订单工程需要 MySQL 保存订单与库存、Redis 执行限流和协调锁、RabbitMQ 传递支付事件。手动安装这三个服务，需要分别处理版本、配置、启动、端口和数据目录。Docker 把服务运行所需的文件组织成镜像，再按配置创建容器；Compose 把三个服务的运行要求写在同一份文件里，便于重复建立学习环境。

当前结构如下，箭头上的端口是宿主机访问端口：

```text
Windows 宿主机
  浏览器 → Vue/Vite 门户 :5173
                │ /order-api 开发代理
                ▼
          Java 订单后端 :18081（本机 java 进程）
                ├─ 127.0.0.1:13306 → MySQL 容器 :3306
                ├─ 127.0.0.1:16379 → Redis 容器 :6379
                └─ 127.0.0.1:15672 → RabbitMQ 容器 :5672
  浏览器 → 127.0.0.1:15673 → RabbitMQ 管理页面 :15672

Docker 管理的命名卷
  order-mysql → MySQL /var/lib/mysql
  order-redis → Redis /data
  order-rabbit → RabbitMQ /var/lib/rabbitmq
```

当前 Compose 只有三个中间件，没有 Java 或 Vue 服务，也没有用 Dockerfile 构建业务镜像。执行 `docker compose up -d` 不会启动门户、编译 Java 或启动订单后端；这些步骤仍按[工程手册](../README.md)分别进行。

Docker 管运行环境，业务正确性由代码和中间件协议保证。例如，容器化本身不能防止超卖、重复积分或消息丢失，相关保障来自数据库事务、约束、Outbox 和消费幂等。

## 2. 先认识六个概念

| 概念 | 含义 | 本工程的例子 |
| --- | --- | --- |
| 镜像 Image | 创建容器用的文件与启动元数据模板 | `mysql:8.4` |
| 容器 Container | 由镜像创建的隔离运行实例，包含进程与可写层 | 正在运行的 mysql 服务实例 |
| 镜像仓库 Registry | 分发镜像的服务 | 默认从 Docker Hub 获取这里的镜像 |
| Docker Engine | 接收请求、管理容器等资源的后台服务 | Docker Desktop 提供的引擎 |
| Docker CLI | 发送管理命令的客户端 | PowerShell 里的 `docker` 命令 |
| Compose | 用 YAML 声明和管理一组服务的工具 | `docker compose -f projects/order-lab/compose.yml up -d` |

Compose 文件是“这组服务应该怎样运行”的说明书，不是镜像，也不是正在执行的容器。同一镜像可以创建多个容器，各自拥有不同的端口、变量和挂载。

### 容器为何比完整虚拟机轻

Linux 容器通过 namespace 隔离进程看到的进程、网络、挂载等视图，通过 cgroup 管理资源。容器中的程序仍是进程，通常共享承载它们的 Linux 内核，不会为每个容器启动一整套独立内核。镜像层可复用，每个容器再叠加自己的可写层。[Docker 架构说明](https://docs.docker.com/get-started/docker-overview/)

本项目使用 Linux 中间件镜像。在 Windows 上使用 Docker Desktop 的 WSL 2 后端时，它们运行在 WSL 2 提供的 Linux 环境中，共享那里的 Linux 内核；并非直接共享 Windows 内核。也不要把容器当成具有独立内核的完整虚拟机。[Windows WSL 2 后端](https://docs.docker.com/desktop/features/wsl/)

当前 Compose 没有声明 CPU 或内存上限。隔离不等于已经限制资源，实际还受 Docker Desktop 和宿主机资源配置影响。

## 3. 一条 up 命令背后发生什么

```powershell
docker compose -f projects/order-lab/compose.yml up -d
```

`-f` 指定本工程配置，`up` 根据声明创建或更新并启动服务，`-d` 让容器在后台运行，命令返回后服务继续运行。可以这样理解处理过程：

1. Compose 读取 YAML，并解析密码等变量。必需变量缺失时在这一阶段报错。
2. 根据镜像拉取策略使用本地镜像或下载所需镜像。已有缓存不代表每次都会获取最新补丁。
3. 根据项目名建立或复用网络和命名卷。
4. 为服务创建或复用容器，设置端口、环境变量、卷和启动命令。
5. 引擎启动容器进程。MySQL 首次初始化还需要时间，命令返回不代表业务依赖都已就绪。

重复执行通常会复用未发生配置变化的容器；配置变化可能导致重建。不要依赖容器可写层保存重要数据。[服务配置参考](https://docs.docker.com/reference/compose-file/services/)

## 4. 逐项阅读本工程 Compose

### 4.1 项目名与服务名

```yaml
name: learn-order-lab
services:
  mysql:
    image: mysql:8.4
```

`learn-order-lab` 是 Compose 项目名；`mysql` 是服务名。默认情况下，网络和卷等资源带项目名前缀，例如 `learn-order-lab_default`、`learn-order-lab_order-mysql`，容器名称也由 Compose 生成。如果显式使用 `-p` 等方式覆盖项目名，实际资源名会变化，可能看到一套新的空数据卷。

`image` 选择现成镜像；`mysql:8.4` 中冒号后是标签。当前另外两项是 `redis:7.4` 和 `rabbitmq:4.1-management`，后者包含管理插件。标签可以更新，不是永久固定的内容；需要严格复现时应在核验后记录或固定镜像摘要。这里解释当前配置，不修改版本。

### 4.2 端口：从哪里进入，到哪里去

```yaml
ports: ["127.0.0.1:13306:3306"]
```

从左到右是“宿主机绑定地址 : 宿主机端口 : 容器端口”。所以本机 Java 连接 `127.0.0.1:13306`，Docker 的端口发布机制将连接转到 MySQL 容器的 `3306`。绑定 `127.0.0.1` 的意图是仅供本机访问，而非向局域网所有地址发布。

| 服务或协议 | 宿主机入口 | 容器内端口 | 谁使用 |
| --- | --- | --- | --- |
| MySQL | `127.0.0.1:13306` | `3306` | Java 数据源、数据库客户端 |
| Redis | `127.0.0.1:16379` | `6379` | Spring Redis、Redisson |
| RabbitMQ AMQP | `127.0.0.1:15672` | `5672` | Java 消息连接 |
| RabbitMQ 管理 HTTP | `127.0.0.1:15673` | `15672` | 浏览器 |

本项目特意使用了不同的宿主机端口。不要凭 RabbitMQ 的常见默认值猜协议：浏览器管理页在 `http://127.0.0.1:15673`；Java 的 `ORDER_MQ_PORT` 默认是 `15672`，因为它映射到容器 AMQP 端口 `5672`。

对应配置在 `src/main/resources/application.yml`。修改映射的宿主机端口后，也要同步 Java 环境变量或配置；只改一边会连接失败。

### 4.3 网络：为什么 Java 不连接 mysql:3306

Compose 会给服务建立默认网络，同一网络内可以通过服务名发现彼此。例如在该网络里的另一个容器可以连接 `mysql:3306`，使用的是容器端口，通常不需要绕行宿主机发布端口。[Compose 网络说明](https://docs.docker.com/compose/how-tos/networking/)

但当前 Java 在 Windows 宿主机上，不在这个容器网络中，所以使用 `127.0.0.1:13306`。容器里的 `localhost` 指该容器自己的网络环境，不能用它表示其他容器。

若以后将 Java 也加入同一 Compose 网络，数据库地址应改为 `mysql:3306`，Redis 改为 `redis:6379`，MQ 改为 `rabbitmq:5672`；这是未来改造示例，当前仓库没有这项实现。

### 4.4 环境变量：有三个不同的接收者

```yaml
environment:
  MYSQL_DATABASE: order_lab
  MYSQL_USER: order_app
  MYSQL_PASSWORD: ${ORDER_DB_PASSWORD:?设置数据库密码}
```

这里存在三层配置：

1. 本工程的 `tooling/order-lab.mjs` 读取 `projects/order-lab/.env`，将 `ORDER_DB_PASSWORD` 等配置放入子进程环境，无需手写 `$env:`。
2. Compose 将解析结果作为容器中的 `MYSQL_PASSWORD` 交给 MySQL 镜像。它不会因为变量存在于终端就把所有变量自动传进容器。
3. 使用 `pnpm order:start` 启动的 Java 同样从脚本继承 `ORDER_DB_PASSWORD`，Spring 再用它配置数据源密码。

`${变量:?提示}` 表示变量必须已设置且非空，否则停止解析并显示提示，防止无意以空密码启动。[Compose 变量插值](https://docs.docker.com/compose/how-tos/environment-variables/variable-interpolation/)

`.env.example` 只是模板，不会因文件名包含 env 就自动生效。也可以显式使用 `--env-file`：

```powershell
# 仅作为替代读取方式；先自行创建本地 .env 并填入密码。
docker compose --env-file projects/order-lab/.env -f projects/order-lab/compose.yml up -d
```

直接使用 Compose 的 `--env-file` 不会给之后单独执行的 `java -jar` 自动设置环境，且 Compose 默认优先使用终端同名变量。本工程推荐 `pnpm order:up` 和 `pnpm order:start`：脚本使用 Node 内置 `parseEnv` 读取文件，明确让文件中的 ORDER_* 优先于终端同名值，再传给各自子进程；并检查必需密码非空。`.env` 已被仓库忽略，不提交真实密码。[Node dotenv 解析说明](https://nodejs.org/api/environment_variables.html#dotenv)

为统一 Node 和 Compose 的读取结果，配置值使用单行、单引号包裹的字面量，密码不含单引号，不使用 `${其他变量}` 引用。脚本不修改当前 PowerShell 的环境；Java 应通过 `pnpm order:start` 启动。

### 4.5 为什么 Redis 命令里写两个美元符号

```yaml
command: ["/bin/sh", "-c", "exec redis-server --appendonly yes --requirepass \"$$ORDER_REDIS_PASSWORD\""]
```

`$$` 告诉 Compose 保留一个字面量 `$`，不要在 Compose 阶段展开这个变量。随后容器中的 `/bin/sh -c` 才将 `$ORDER_REDIS_PASSWORD` 展开为容器环境里的值。这里显式调用 shell，因为 Compose 的 `command` 不会自动提供 shell 变量展开。[插值转义规则](https://docs.docker.com/reference/compose-file/interpolation/)

`exec` 让 Redis 替换这层 shell，便于接收停止信号；`--requirepass` 设置认证，`--appendonly yes` 启用 AOF。AOF 是 Redis 的持久化机制，卷负责承载文件；仅有卷而没有数据写盘机制，不能保存内存中的所有状态。环境变量与命令参数也不是专用密钥保险箱，不要对外分享包含凭据的检查输出。

### 4.6 数据卷：为什么容器重建后订单还能保留

```yaml
volumes: ["order-mysql:/var/lib/mysql"]
```

左边是 Docker 管理的命名卷，右边是容器内挂载路径。MySQL 向该目录写入时，数据进入卷，而不是只留在容器可写层。容器可以删除并重建，再挂上同一卷读取原数据。卷与容器的生命周期分开管理。[数据卷原理](https://docs.docker.com/engine/storage/volumes/)

本工程三个卷分别保存数据库文件、Redis 持久化文件和 RabbitMQ 节点数据。Docker Desktop 的 Linux 卷由其 Linux 环境管理，不是本仓库中的普通文件夹。不要手工修改数据库的底层文件，也不要将卷等同于备份：同一台机器或卷损坏仍可能丢失数据。

需要区分三种东西：镜像层保存程序模板，容器可写层保存该容器的临时变化，命名卷保存希望跨容器重建保留的数据。另一种挂载方式 bind mount 将宿主机指定路径挂进去，适合需要直接编辑的文件；当前 Compose 用的是命名卷。

### 4.7 首次初始化：数据库是谁建的，业务表又是谁建的

MySQL 镜像在空数据目录首次初始化时，根据 `MYSQL_DATABASE`、`MYSQL_USER` 和密码配置建立数据库与账号；已有数据目录不会因重新设置这些初始化变量就重新建库或改密。[MySQL 官方镜像说明](https://hub.docker.com/_/mysql)

订单业务表由 Java 启动时的 Flyway 执行 `src/main/resources/db/migration/V1__order_schema.sql` 创建。Compose 中没有挂载 MySQL 初始化 SQL 目录，也没有替 Java 执行这份迁移。

RabbitMQ 的默认用户和 vhost 用于初始化 Broker；Java 的 `MessagingConfig` 再声明交换机、队列和绑定。已有 RabbitMQ 数据卷中的用户密码也不能靠修改默认初始化变量直接重置。[RabbitMQ 官方镜像说明](https://hub.docker.com/_/rabbitmq)

所以出现“我换了环境变量却认证失败”时，先核对旧卷中的真实账号配置，按服务自己的方式改密。不要为了认证问题直接删除学习数据。

### 4.8 健康检查：running 与 healthy 有什么区别

本工程只为 MySQL 配置了健康检查：

```yaml
healthcheck:
  test: ["CMD-SHELL", "MYSQL_PWD=$$MYSQL_ROOT_PASSWORD mysqladmin ping -h localhost -u root --silent"]
  interval: 5s
  timeout: 3s
  retries: 30
```

它在 MySQL 容器内执行探测，每 5 秒一次，每次超时 3 秒，连续失败达到阈值会标为不健康。这里的 localhost 正好指 MySQL 所在容器。`mysqladmin ping` 主要说明服务进程能响应，不能证明业务账号权限、Flyway 迁移或完整下单链路可用。

当前文件没有 `depends_on`，也没有 Redis/MQ 健康检查；Java 更是在 Compose 外启动。普通 `up -d` 不会替外部 Java 等待依赖就绪。即使以后将 Java 容器化，也需明确就绪条件；单纯的启动顺序不保证服务准备完成。[启动顺序与就绪条件](https://docs.docker.com/compose/how-tos/startup-order/)

健康检查失败不会自动修好数据库，也不自动重启容器。当前还没有配置 `restart` 策略，不应假定退出后一定会自动恢复。

## 5. 按步骤观察一次完整生命周期

以下操作由你自行执行，需先启动 Docker Desktop 并选择 Linux 容器模式。这里不安装软件、不修改你的 Docker 设置。

### 第一步：确认客户端和引擎

```powershell
docker version
docker compose version
```

`docker version` 应能读取 Client 与 Server。只有客户端信息或提示无法连接引擎时，先处理 Docker Desktop、上下文或后端启动问题。

### 第二步：准备环境，再启动中间件

首次将 `projects/order-lab/.env.example` 复制为同目录 `.env`，已有文件不要覆盖。用编辑器填写密码，保存为 UTF-8；实际文件名不能是 `.env.txt`：

```dotenv
ORDER_DB_PASSWORD='替换为你的数据库密码'
ORDER_DB_ROOT_PASSWORD='替换为不同的管理员密码'
ORDER_REDIS_PASSWORD='替换为你的Redis密码'
ORDER_MQ_PASSWORD='替换为你的MQ密码'
ORDER_LAB_ENABLED=true
```

在仓库根目录执行：

```powershell
pnpm order:up
pnpm order:ps
```

它们每次读取 `.env`，修改文件后重新执行即可。实验开关 `ORDER_LAB_ENABLED` 供 Java 使用，不是 Docker 启动开关。直接用 Compose 检查配置时可加 `config --quiet`，普通 `config` 输出可能包含密码。

观察三个服务是否持续运行、MySQL 是否变为 healthy，再结合日志确认 Redis/MQ 就绪，随后按[工程手册](../README.md)启动 Java 和门户。

### 第三步：查看日志与容器内部状态

```powershell
docker compose --env-file projects/order-lab/.env -f projects/order-lab/compose.yml logs --tail 80 mysql redis rabbitmq
docker compose --env-file projects/order-lab/.env -f projects/order-lab/compose.yml exec redis redis-cli --askpass PING
docker compose --env-file projects/order-lab/.env -f projects/order-lab/compose.yml exec mysql mysql -u order_app -p order_lab
```

`logs` 查看服务标准输出与错误；`exec` 在已有运行容器里执行额外命令，不会重新创建服务。Redis 交互输入密码后预期返回 `PONG`；MySQL 交互输入应用账号密码后，可执行只读查询：

```sql
SELECT DATABASE();
SHOW TABLES;
SELECT id, name, available, reserved, sold FROM product;
```

业务表与种子商品要等 Java/Flyway 成功启动后才存在；首次只启动 MySQL 时没有业务表是正常现象。在 MySQL 客户端输入 `exit` 退出。

### 第四步：停止与恢复，理解数据留在哪里

先用 Ctrl+C 停止你自己启动的 Java，再停止中间件：

```powershell
pnpm order:stop
pnpm order:ps
pnpm order:up
```

预期停止后容器仍存在，恢复后同一卷的数据还在。可以先在界面创建一笔订单并记录 ID，恢复中间件与 Java 后再查询它，验证持久化。

若只是想移除容器和默认网络，同时保留命名卷，可以在停止相关学习进程后使用：

```powershell
docker compose --env-file projects/order-lab/.env -f projects/order-lab/compose.yml down
```

普通 `down` 默认保留本工程的命名卷；再 `up -d` 可以重新挂载。[down 命令说明](https://docs.docker.com/reference/cli/docker/compose/down/)

| 操作 | 对容器的作用 | 对本工程命名卷的作用 |
| --- | --- | --- |
| `stop` | 停止，保留容器 | 保留 |
| `start` | 启动已存在的容器 | 保留 |
| `restart` | 重启现有容器，不重新应用全部配置 | 保留 |
| `up -d` | 按声明创建、复用或重建并启动 | 通常复用同名卷 |
| `down` | 停止并移除容器及默认网络 | 默认保留 |
| `down -v` | 移除容器，并请求移除声明的命名卷等 | 会删除本工程数据 |

`down -v` 只用于明确决定清空实验数据的场景，会丢失订单、库存变化、积分、消息及相关持久化状态。本文不把它作为普通重试步骤，也不提供全局清理命令。

## 6. 常见问题，沿哪条链路排查

| 现象 | 原理与检查方向 |
| --- | --- |
| Cannot connect to Docker daemon | CLI 无法访问引擎，先看 Docker Desktop 是否运行、Docker 上下文是否正确 |
| 缺少 ORDER_* 变量 | Compose 在插值阶段就失败，核对当前终端或显式 env 文件 |
| 镜像下载失败 | 属于网络、代理、仓库访问或标签问题，不是 Java 代码错误 |
| 端口被占用 | 宿主机发布端口冲突，先查占用归属；改映射后同步客户端配置 |
| Java 报连接拒绝 | 看容器是否退出、端口映射及依赖是否就绪，再核对 Java 地址 |
| Java 认证失败 | 核对 Java 与容器使用的密码，并考虑旧卷初始化后的账号状态 |
| 容器 running 但 API 不可用 | 继续查中间件就绪、Java 日志、Flyway 和业务配置；Docker 进程状态覆盖不了整条链路 |
| 浏览器打不开 MQ 页面 | 本工程管理页用 15673；宿主机 15672 是 AMQP 入口 |
| 重建后像是数据没了 | 核对项目名、Docker 上下文和实际挂载卷，是否连接了另一套资源 |

观察卷与映射可用以下只读命令。带项目名前缀的卷名以前文默认项目名为前提：

```powershell
docker context show
docker compose --env-file projects/order-lab/.env -f projects/order-lab/compose.yml port mysql 3306
docker volume inspect learn-order-lab_order-mysql
```

## 7. Docker 和业务可靠性分别负责什么

Docker 负责创建进程环境、连接网络、挂载存储和管理生命周期。MySQL 自身负责事务与数据库文件恢复，Redis 自身负责 AOF，RabbitMQ 自身负责消息存储与协议；代码负责正确使用它们。

本工程只有单节点 MySQL、Redis、RabbitMQ。命名卷不会自动产生副本，单节点 Quorum 队列也不会自动变成多节点高可用。业务后端退出后，Docker 内的三个依赖仍可能继续运行；反过来，依赖停止时本机 Java 进程也可能仍在，只是请求失败。

建议学习验收分两层：先确认你能解释镜像、容器、网络与卷，再按[故障验收手册](ACCEPTANCE.md)验证停机恢复、Outbox 补投及幂等积分。本篇不声称这些运行实验已经通过。

## 8. 自测问题与继续阅读

读完后尝试不用查文档回答：

1. 为什么 `up -d` 后门户仍没启动？因为门户和 Java 不在当前 Compose 服务中。
2. 为什么本机 Java 使用 13306，而同网络容器使用 mysql:3306？前者走宿主机端口映射，后者走容器网络与服务名。
3. 为什么删除容器不一定删除订单？订单文件保存在独立命名卷里。
4. 为什么重启容器不一定更新密码？初始化变量、已有数据和当前运行配置是不同层次。
5. 为什么有数据卷还需要 Outbox 和幂等？持久化文件不能自动处理跨系统提交与重复投递。

建议源码阅读顺序：`compose.yml` → `.env.example` → `application.yml` → Flyway SQL → `MessagingConfig.java`。再回到订单事务与消息消费，区分基础设施配置和业务可靠性。

官方资料已在各章节按主题链接，可继续阅读 Docker 架构、Compose 网络、变量插值、服务配置、数据卷和启动条件。它们说明工具通用行为；本工程的具体端口、资源名和运行边界以仓库配置为准。
