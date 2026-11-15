/**
 * 订单工程的本地配置入口：读取所属工程的 .env，再启动 Docker Compose 或 Java。
 * .env 不是可执行脚本；用 Node 内置 parseEnv 解析，不执行其中的命令，也不打印密码。
 * 本脚本只负责传递配置，不构建 JAR、不等待依赖就绪、不修改数据库已有密码。
 */
import { spawn } from 'node:child_process'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { parseEnv } from 'node:util'

const root = fileURLToPath(new URL('../', import.meta.url))
const envPath = path.join(root, 'projects/order-lab/.env')
const task = process.argv[2]
const composeTasks = { up: ['up', '-d'], ps: ['ps', '-a'], stop: ['stop'] }

/**
 * 返回子进程环境；文件中的 ORDER_* 优先于终端同名变量，方便只编辑文件。
 * 仅接受订单配置，不允许此文件覆盖 PATH、JAVA_HOME 等通用进程设置。
 * 必需密码缺失时在启动服务前报错；错误只包含变量名，不包含变量值。
 */
function readEnvironment() {
  let content
  try {
    content = readFileSync(envPath, 'utf8')
  } catch {
    throw new Error('无法读取 projects/order-lab/.env，请复制同目录 .env.example 并填写密码。')
  }
  const values = parseEnv(content.replace(/^\uFEFF/, ''))
  const environment = { ...process.env }
  for (const [key, value] of Object.entries(values)) {
    if (key.startsWith('ORDER_')) environment[key] = value
  }
  const required = ['ORDER_DB_PASSWORD', 'ORDER_REDIS_PASSWORD', 'ORDER_MQ_PASSWORD']
  if (task !== 'start') required.push('ORDER_DB_ROOT_PASSWORD')
  for (const key of required) {
    // 要求密码明确写在文件里，避免遗漏时悄悄采用终端中遗留的旧密码。
    if (!values[key]?.trim()) throw new Error(`请在 projects/order-lab/.env 填写 ${key}。`)
  }
  return environment
}

/**
 * 使用参数数组直接启动进程，密码只经环境传递，不拼进 shell 命令。
 * stdio: inherit 让 Java 日志与交互继续显示在当前终端；退出码传回调用方。
 * Java 是直接子进程，Ctrl+C/SIGTERM 转交给它；结束前等待其退出。
 */
function run(command, args, environment) {
  const child = spawn(command, args, { cwd: root, env: environment, stdio: 'inherit' })
  for (const signal of ['SIGINT', 'SIGTERM']) {
    process.on(signal, () => {
      if (child.exitCode === null && !child.killed) child.kill(signal)
    })
  }
  child.once('error', () => {
    console.error('无法启动命令，请检查 Docker 或 JDK 是否已安装并可用。')
    process.exitCode = 1
  })
  child.once('exit', (code, signal) => {
    process.exitCode = code ?? (signal === 'SIGINT' ? 130 : 1)
  })
}

try {
  if (task !== 'start' && !Object.hasOwn(composeTasks, task)) {
    throw new Error('用法：node tooling/order-lab.mjs <up|ps|stop|start>')
  }
  const environment = readEnvironment()
  if (task === 'start') {
    const java = environment.JAVA_HOME
      ? path.join(environment.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
      : 'java'
    run(java, ['-jar', 'projects/order-lab/target/order-lab-0.1.0-SNAPSHOT.jar'], environment)
  } else {
    run(
      'docker',
      ['compose', '--env-file', envPath, '-f', 'projects/order-lab/compose.yml', ...composeTasks[task]],
      environment,
    )
  }
} catch (error) {
  console.error(error.message)
  process.exitCode = 1
}
