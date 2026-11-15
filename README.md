# Learn Workbench · 个人工程学习工作台

一个用于持续接入学习成果的工程仓库。通过文件夹式门户组织独立工程，复用已经稳定的基础能力；每次根据学习链路设计和接入功能。

工作台已接入 Vue 3 / TypeScript 门户、Java 17 / Spring Boot 基础服务及订单可靠性学习工程。订单工程按首条学习链路引入 MySQL、原生 MyBatis、Redis 和 RabbitMQ；Agent/Python 仍按需接入。无需登录；不包含工程生成工具。

新增的[订单可靠性实验室](projects/order-lab/README.md)包含下单、库存、模拟支付、超时取消、可靠消息和幂等积分；先看[问答过滤总结](projects/order-lab/docs/LEARNING.md)，再按工程手册启动。新工程运行验收由用户负责，不代表已通过生产验收。

## 快速开始

准备 Node.js 22.12 或更高的兼容版本、pnpm 11.25.0 和 JDK 17。设置 `JAVA_HOME` 指向 JDK 17，Maven 由仓库内的 Wrapper 提供。依赖的精确版本见 `package.json`、`pnpm-lock.yaml` 和 `pom.xml`。

```sh
git clone https://github.com/wjlsssq/learn.git
cd learn
pnpm install --frozen-lockfile
pnpm dev
```

浏览器打开 <http://127.0.0.1:5173>。门户可独立运行，提供工程浏览、搜索和仓库文档入口。点击工程文件夹后，右侧主内容区切换为完整工程页，左侧菜单和顶部面包屑保留；通过面包屑可返回工程列表。

工程页使用 `#projects/<project-id>` 地址，例如 `http://127.0.0.1:5173/#projects/foundation-service`。地址可刷新，也可通过浏览器前进、后退切换页面；不存在的工程标识会显示“找不到工程”页面。

在另一个终端、同一仓库根目录启动基础服务：

```sh
pnpm dev:backend
```

脚本先构建基础服务及其公共依赖，再启动 Java 进程。首次运行需要联网下载 Maven 和依赖。服务监听 `127.0.0.1:18080`；在门户中手动检查服务状态，页面操作不会启动本地进程。

## 日常命令

| 命令                 | 用途                                             |
| -------------------- | ------------------------------------------------ |
| `pnpm dev`           | 启动门户开发服务，默认端口 5173                  |
| `pnpm dev:backend`   | 构建并启动基础服务，默认端口 18080               |
| `pnpm check`         | 前端格式检查、静态检查、类型检查、测试和生产构建 |
| `pnpm check:backend` | 使用 Maven Wrapper 执行 Java `verify`            |
| `pnpm build`         | 构建前端生产产物                                 |
| `pnpm build:backend` | 构建 Java 工程                                   |
| `pnpm format`        | 格式化受前端工具链管理的文件                     |

运行中的前后端分别通过所在终端的 `Ctrl+C` 停止。更详细的命令、配置和排查步骤见[开发指南](docs/DEVELOPMENT.md)。

## 工程导航

```text
portal/                         Vue 工作台门户
projects/foundation-service/    独立 Spring Boot 基础服务
shared/frontend/http/           可配置 HTTP 客户端
shared/frontend/ui/             公共样式变量
shared/java/web-support/        Java Web 异常、校验与请求关联能力
tooling/                        当前工程的启动与检查脚本
docs/                           架构、开发、接入和进度文档
```

基础服务提供以下接口：

- `GET /api/foundation/info`：服务版本、Java 运行版本和当前基础能力。
- `GET /actuator/health`：服务健康状态。

开发模式下，门户通过 `/foundation-api` 代理访问该服务；生产构建是静态门户，需要自行配置反向代理才能继续进行服务探测。

## 文档入口

- [Docker 学习](projects/order-lab/docs/DOCKER.md)：结合订单工程理解镜像、容器、Compose、端口、变量和持久化。
- [当前进度](docs/PROGRESS.md)：当前状态、验证证据、阻塞和下一步，作为唯一进度记录。
- [架构约定](docs/ARCHITECTURE.md)：职责边界、依赖方向和扩展原则。
- [开发指南](docs/DEVELOPMENT.md)：环境、运行、构建和故障排查。
- [新增工程接入指南](docs/PROJECT_ONBOARDING.md)：根据学习链路新增或扩展工程。
- [架构决策](docs/decisions/0001-workspace-foundation.md)：当前组织方式及其取舍。
- [协作规则](AGENTS.md)：代码修改、文档维护、验证和提交推送要求。

后续学习时，先明确学习目标、业务场景和验收方式，再决定扩展现有工程或建立独立工程。Java、Python 和混合工程可以采用各自适合的内部结构，并遵循同一套接入与维护要求。
