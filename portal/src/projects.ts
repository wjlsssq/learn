export interface ProjectDefinition {
  id: string
  name: string
  description: string
  category: 'foundation' | 'learning'
  tags: readonly string[]
  sourcePath: string
  updatedAt: string
  documentId: string
  entries: { label: string; url: string }[]
  commands: { label: string; value: string }[]
  learningGoals: string[]
  capabilities: { title: string; description: string }[]
}

export const foundationBaseURL = (
  import.meta.env.VITE_FOUNDATION_BASE_URL || '/foundation-api'
).replace(/\/$/, '')

/** Register implemented projects only; planned capabilities belong in PROGRESS.md. */
export const projects: readonly ProjectDefinition[] = [
  {
    id: 'foundation-service',
    name: '基础工程',
    description: '从可运行的工程底座开始，建立独立、清晰、可持续扩展的学习空间。',
    category: 'foundation',
    tags: ['Java 17', 'Spring Boot', 'HTTP'],
    sourcePath: 'projects/foundation-service',
    updatedAt: '2026-11-12',
    documentId: 'development',
    entries: [
      { label: '服务信息', url: `${foundationBaseURL}/api/foundation/info` },
      { label: '健康检查', url: `${foundationBaseURL}/actuator/health` },
    ],
    commands: [
      { label: '启动后端', value: 'pnpm dev:backend' },
      { label: '后端验证', value: 'pnpm check:backend' },
    ],
    learningGoals: [
      '理解公共模块与独立工程的职责边界',
      '验证参数校验、异常处理与请求关联链路',
      '建立可复现的开发、验证和文档维护流程',
    ],
    capabilities: [
      { title: '基础 Web 能力', description: '统一错误结构，保留真实 HTTP 状态。' },
      { title: '参数校验', description: '明确输入约束与字段级错误信息。' },
      { title: '请求关联', description: '通过 X-Request-Id 关联响应与日志。' },
      { title: '健康检查', description: '手动检查实际运行状态，按需启动服务。' },
    ],
  },
]

export type ProjectCategory = 'all' | ProjectDefinition['category']
export type ProjectSort = 'recent' | 'name'

export function selectProjects(
  source: readonly ProjectDefinition[],
  query: string,
  category: ProjectCategory,
  sort: ProjectSort,
): ProjectDefinition[] {
  const normalizedQuery = query.trim().toLocaleLowerCase()
  return source
    .filter((project) => {
      const matchesCategory = category === 'all' || project.category === category
      const searchable = [project.name, project.description, project.id, ...project.tags]
        .join(' ')
        .toLocaleLowerCase()
      return matchesCategory && searchable.includes(normalizedQuery)
    })
    .sort((left, right) =>
      sort === 'name'
        ? left.name.localeCompare(right.name, 'zh-CN')
        : right.updatedAt.localeCompare(left.updatedAt) ||
          left.name.localeCompare(right.name, 'zh-CN'),
    )
}
