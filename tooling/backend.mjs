import { spawn, spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const root = fileURLToPath(new URL('../', import.meta.url))
const task = process.argv[2]
const commands = {
  build: ['--batch-mode', '-pl', 'projects/foundation-service', '-am', 'package', '-DskipTests'],
  verify: ['--batch-mode', 'verify'],
  dev: ['--batch-mode', '-pl', 'projects/foundation-service', '-am', 'package', '-DskipTests'],
}

if (!Object.hasOwn(commands, task)) {
  console.error('Usage: node tooling/backend.mjs <dev|build|verify>')
  process.exit(2)
}

let child
let interrupted = false
for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    interrupted = true
    if (!child?.pid || child.exitCode !== null) return
    if (process.platform === 'win32') {
      // A .cmd wrapper owns a Java subprocess; stop only this process tree.
      spawnSync('taskkill', ['/pid', String(child.pid), '/T', '/F'], { stdio: 'ignore' })
    } else {
      child.kill(signal)
    }
  })
}

function run(command, args, shell = false) {
  return new Promise((resolve, reject) => {
    child = spawn(command, args, { cwd: root, stdio: 'inherit', shell })
    child.once('error', reject)
    child.once('exit', (code) => resolve(code ?? 1))
  })
}

try {
  const windows = process.platform === 'win32'
  const wrapper = windows ? '.\\mvnw.cmd' : './mvnw'
  const buildCode = await run(wrapper, commands[task], windows)
  if (buildCode !== 0 || interrupted) process.exit(buildCode || 130)

  if (task === 'dev') {
    const java = process.env.JAVA_HOME
      ? path.join(process.env.JAVA_HOME, 'bin', windows ? 'java.exe' : 'java')
      : 'java'
    process.exit(
      await run(java, [
        '-jar',
        'projects/foundation-service/target/foundation-service-0.1.0-SNAPSHOT.jar',
      ]),
    )
  }
} catch (error) {
  console.error('Backend task failed:', error.message)
  process.exit(1)
}
