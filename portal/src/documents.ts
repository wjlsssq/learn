import overview from '../../README.md?raw'
import architecture from '../../docs/ARCHITECTURE.md?raw'
import development from '../../docs/DEVELOPMENT.md?raw'
import progress from '../../docs/PROGRESS.md?raw'
import onboarding from '../../docs/PROJECT_ONBOARDING.md?raw'
import orderLab from '../../projects/order-lab/README.md?raw'
import orderLearning from '../../projects/order-lab/docs/LEARNING.md?raw'

export interface WorkspaceDocument {
  id: string
  title: string
  description: string
  path: string
  content: string
}

export const documents: readonly WorkspaceDocument[] = [
  {
    id: 'order-lab',
    title: '订单工程手册',
    description: '订单链路、环境准备与故障验收。',
    path: 'projects/order-lab/README.md',
    content: orderLab,
  },
  {
    id: 'order-learning',
    title: 'Redis 与 MQ 学习',
    description: '问答过滤、易错点与源码阅读路径。',
    path: 'projects/order-lab/docs/LEARNING.md',
    content: orderLearning,
  },
  {
    id: 'overview',
    title: '项目说明',
    description: '工作台的目标、边界与快速开始。',
    path: 'README.md',
    content: overview,
  },
  {
    id: 'architecture',
    title: '架构约定',
    description: '工程边界、依赖方向与公共能力。',
    path: 'docs/ARCHITECTURE.md',
    content: architecture,
  },
  {
    id: 'development',
    title: '开发指南',
    description: '准备环境，启动工程，完成验证。',
    path: 'docs/DEVELOPMENT.md',
    content: development,
  },
  {
    id: 'progress',
    title: '当前进度',
    description: '已完成、待验证，以及下一步。',
    path: 'docs/PROGRESS.md',
    content: progress,
  },
  {
    id: 'onboarding',
    title: '工程接入',
    description: '根据学习目标，接入新的工程。',
    path: 'docs/PROJECT_ONBOARDING.md',
    content: onboarding,
  },
]

export type DocumentBlock =
  | { kind: 'heading'; level: number; text: string }
  | { kind: 'code'; language: string; text: string }
  | { kind: 'text'; text: string }

/** A deliberately small, safe renderer: all document content remains Vue text nodes. */
export function parseDocument(content: string): DocumentBlock[] {
  const blocks: DocumentBlock[] = []
  const lines = content.replace(/\r\n/g, '\n').split('\n')
  let text: string[] = []
  let code: string[] | null = null
  let language = ''
  const flushText = () => {
    if (text.length) blocks.push({ kind: 'text', text: text.join('\n') })
    text = []
  }
  for (const line of lines) {
    if (line.startsWith('```')) {
      if (code !== null) {
        blocks.push({ kind: 'code', language, text: code.join('\n') })
        code = null
      } else {
        flushText()
        code = []
        language = line.slice(3).trim()
      }
    } else if (code !== null) {
      code.push(line)
    } else {
      const heading = /^(#{1,6})\s+(.+)$/.exec(line)
      if (heading) {
        flushText()
        blocks.push({
          kind: 'heading',
          level: Math.min(heading[1]!.length + 1, 6),
          text: heading[2]!,
        })
      } else if (line.trim() === '') {
        flushText()
      } else {
        text.push(line)
      }
    }
  }
  if (code !== null) blocks.push({ kind: 'code', language, text: code.join('\n') })
  flushText()
  return blocks
}
