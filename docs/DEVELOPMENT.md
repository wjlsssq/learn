# 开发指南

## 环境准备

| 工具    | 基线                  | 说明                                                           |
| ------- | --------------------- | -------------------------------------------------------------- |
| Git     | 支持 HTTPS 远端的版本 | 本仓库远端为 `https://github.com/wjlsssq/learn.git`            |
| Node.js | 22.12+ 的兼容版本     | 首次本地实现环境为 22.18；前端运行要求以根 `package.json` 为准 |
| pnpm    | 11.25.0               | 按根 `packageManager` 字段保持一致                             |
| JDK     | 17                    | `JAVA_HOME` 指向完整 JDK                                       |
| Maven   | Wrapper 固定 3.9.16   | 使用 `mvnw` / `mvnw.cmd`，无需依赖系统 Maven                   |

Windows PowerShell、Linux 和 macOS 均从仓库根目录运行命令。首次安装前端依赖、执行 Maven Wrapper 和解析 Java 依赖需要联网。代理、凭据等机器配置保留在本地，不写入源码。

检查环境：

```sh
git --version
node --version
pnpm --version
java -version
```

## 启动门户

```sh
pnpm install --frozen-lockfile
pnpm dev
```

默认访问 <http://127.0.0.1:5173>。基础服务尚未启动时，工程登记信息与文档仍可浏览。页面初始不假定服务在线，手动检查后才显示探测结果。

门户使用 Vite 开发代理：`/foundation-api/*` 转发到 `http://127.0.0.1:18080/*`。修改服务地址或端口时同步修改门户代理与工程登记信息，并更新文档。

## 启动基础服务

另开终端，在仓库根目录执行：

```sh
pnpm dev:backend
```

脚本调用 Maven Wrapper 构建 `foundation-service` 及它的本地依赖，然后运行其可执行 JAR。开发启动构建跳过测试以缩短启动时间，交付前仍须执行完整验证。

可以直接访问：

- <http://127.0.0.1:18080/api/foundation/info>
- <http://127.0.0.1:18080/actuator/health>

服务信息返回 `name`、`version`、`javaVersion` 和 `capabilities`。健康接口正常响应示例为 `{"status":"UP"}`。响应包含 `X-Request-Id`，用于关联日志与错误信息。

手动构建和启动的等价 Windows 命令：

```powershell
.\mvnw.cmd --batch-mode -pl projects/foundation-service -am package -DskipTests
java -jar projects/foundation-service/target/foundation-service-0.1.0-SNAPSHOT.jar
```

Linux / macOS 构建命令：

```sh
./mvnw --batch-mode -pl projects/foundation-service -am package -DskipTests
java -jar projects/foundation-service/target/foundation-service-0.1.0-SNAPSHOT.jar
```

在各自终端按 `Ctrl+C` 结束当前开发进程。Windows 后端开发脚本会清理其启动的进程树，不保证优雅关闭。结束验证时关闭自己启动的进程，保留用户原有进程。

## 检查与构建

```sh
pnpm check
pnpm check:backend
```

`pnpm check` 依次执行格式检查、ESLint、TypeScript 检查、测试和前端生产构建。`pnpm check:backend` 调用 Maven Wrapper 执行完整 Java `verify`。每次修改按影响范围运行检查，公共模块改动还需验证其实际消费者。

其他常用命令：

| 命令                                    | 用途                            |
| --------------------------------------- | ------------------------------- |
| `pnpm format`                           | 格式化前端工具链管理的文件      |
| `pnpm build`                            | 生成门户静态产物 `portal/dist/` |
| `pnpm build:backend`                    | 构建 Java 工程                  |
| `pnpm --filter @learn/portal typecheck` | 仅检查门户类型                  |
| `pnpm --filter @learn/portal test`      | 运行门户测试                    |
| `pnpm --filter @learn/http test`        | 运行共享 HTTP 客户端测试        |
| `pnpm --filter @learn/portal preview`   | 预览静态生产产物，默认端口 4173 |

生产预览不等同于 Vite 开发服务。静态产物中的后端请求需要运行环境配置反向代理，未配置代理时不能据此判断基础服务本身存在故障。

Java 验证也可以直接执行：

```powershell
.\mvnw.cmd --batch-mode verify
```

Linux / macOS 使用 `./mvnw --batch-mode verify`。测试与构建报告位于各 Java 模块的 `target/` 中，不提交到 Git。

## 配置和依赖变更

直接依赖使用明确版本；变更前确认 Node、JDK、构建工具和相关插件的兼容性。前端依赖变更同步更新 `pnpm-lock.yaml`，干净环境采用 `--frozen-lockfile` 安装。

配置模板只包含安全示例。实际密钥、服务访问凭据和本地覆盖配置通过环境或忽略文件管理；浏览器可读取的配置不能承载私密凭据。

基础服务当前无数据库或中间件依赖。新增能力遵循 [PROJECT_ONBOARDING.md](PROJECT_ONBOARDING.md)，不将某个工程的依赖自动扩散到全部工程。

## 常见问题

| 现象                   | 检查步骤                                                                        |
| ---------------------- | ------------------------------------------------------------------------------- |
| 系统 Maven 太旧        | 使用仓库 Wrapper；通过 `mvnw.cmd --version` 或 `./mvnw --version` 核对          |
| Java 版本不符          | 核对 `java -version` 和 `JAVA_HOME`，确认 Wrapper 和终端使用同一 JDK            |
| 首次构建下载失败       | 检查网络与本地代理设置；修复后重试，不提交机器专用凭据或绕过失败项              |
| 门户显示未检测         | 点击检查服务状态，状态不会在首次渲染时被硬编码为在线                            |
| 门户探测失败           | 检查 18080 服务、直接健康接口和 Vite 代理；探测失败可能来自服务、网络或代理配置 |
| 5173 / 18080 被占用    | 确认占用进程归属；使用其他端口时同步更新相关配置与文档                          |
| 仅静态预览无法探测后端 | 确认运行环境是否提供 `/foundation-api` 反向代理                                 |
| 修改公共库后消费者失败 | 检查接口兼容性与消费者配置，补充对应回归验证                                    |

## 开发收尾

1. 检查差异与依赖边界，确认只修改本次范围。
2. 完成相关验证，记录实际命令和结果。
3. 更新 [PROGRESS.md](PROGRESS.md)；影响运行、架构或接入步骤时同步对应文档。
4. 检查暂存内容，提交并推送 `origin main`，核对远端结果。

GitHub Actions 对推送和拉取请求执行前端与 Java 检查。工作流文件已配置不代表远端检查已经成功，实际结果另记进度文档。
