# Foundation Service · 基础服务

提供工作台当前基础能力的真实运行信息和健康检查，消费 `shared/java/web-support`，用于验证 Java 工程独立构建、启动和接入公共 Web 规范的链路。

技术基线为 JDK 17、Spring Boot 3.5.16。当前无需数据库、缓存、MQ、模型服务或其他外部资源；服务也不负责门户工程清单和本地进程管理。

## 目录与职责

```text
pom.xml                 声明本服务依赖与可执行 JAR 构建
src/main/java/          启动入口和基础信息接口
src/main/resources/    本服务运行配置
src/test/              基础服务验证
```

`FoundationApplication` 显式导入 `io.github.wjlsssq.learn.web.config.WebSupportConfiguration`。异常映射、参数校验处理和请求关联由公共模块提供；服务自身维护应用入口和接口契约。

## 构建与启动

以下命令均在仓库根目录执行。推荐使用统一脚本：

```sh
pnpm dev:backend
```

它先构建本服务与所需公共模块，再运行可执行 JAR。需要 Node.js / pnpm、JDK 17 和首次下载 Maven 依赖的网络；不要求安装系统 Maven。

也可以直接使用 JDK 与 Maven Wrapper。Windows PowerShell：

```powershell
.\mvnw.cmd --batch-mode -pl projects/foundation-service -am package -DskipTests
java -jar projects/foundation-service/target/foundation-service-0.1.0-SNAPSHOT.jar
```

Linux / macOS：

```sh
./mvnw --batch-mode -pl projects/foundation-service -am package -DskipTests
java -jar projects/foundation-service/target/foundation-service-0.1.0-SNAPSHOT.jar
```

默认监听 `127.0.0.1:18080`。在运行终端按 `Ctrl+C` 结束当前开发进程。Windows 开发脚本会清理其启动的进程树，不保证优雅关闭。

## 配置

| 配置             | 默认值      | 用途     |
| ---------------- | ----------- | -------- |
| `SERVER_ADDRESS` | `127.0.0.1` | 监听地址 |
| `SERVER_PORT`    | `18080`     | 监听端口 |

端口或地址变更后，同步检查门户 Vite 代理、工程启动说明和探测配置。版本字段由 Maven 项目版本写入配置。

Actuator 仅暴露健康接口，默认不展示内部健康详情。日志中 `traceId` 与响应头 `X-Request-Id` 关联。

## 接口

### `GET /api/foundation/info`

返回实际服务信息，正常 HTTP 状态为 200：

```json
{
  "name": "Learn Foundation Service",
  "version": "0.1.0-SNAPSHOT",
  "javaVersion": "17.0.12",
  "capabilities": ["web", "validation", "request-correlation"]
}
```

`javaVersion` 随运行环境变化；`version` 随项目版本变化。能力列表说明已接入的基础机制，不代表已有业务模块或中间件。

### `GET /actuator/health`

服务健康时返回 HTTP 200：

```json
{ "status": "UP" }
```

### 请求关联与错误

请求可以携带符合安全格式的 `X-Request-Id`；缺失或格式不符时生成新标识。响应头、错误体的 `traceId` 和日志使用相同标识。

错误保留真实 HTTP 状态，使用 `{ code, message, traceId, timestamp, path, violations? }` 契约。未知异常不向响应暴露内部原因。完整约定见[架构文档](../../docs/ARCHITECTURE.md)。

## 验证

在仓库根目录执行：

```sh
pnpm check:backend
```

或使用 `.\mvnw.cmd --batch-mode verify`（Windows）、`./mvnw --batch-mode verify`（Linux / macOS）。完整验证同时检查公共 Web 模块和本服务；开发启动时跳过的测试在此执行。

实际运行验证访问：

- <http://127.0.0.1:18080/api/foundation/info>
- <http://127.0.0.1:18080/actuator/health>

相关测试与报告位于各模块 `src/test/` 和 `target/`。具体通过情况统一记录在仓库[当前进度](../../docs/PROGRESS.md)，本文件只维护运行与接口说明。

后续功能按[工程接入指南](../../docs/PROJECT_ONBOARDING.md)评估扩展或新建边界。涉及业务、数据库或中间件时，先明确学习目标、资源归属和验证方式。
