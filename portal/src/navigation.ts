/**
 * 门户的页面地址约定。
 * Hash 是网址中 # 后面的部分：改变它只会切换当前前端页面，不需要服务器新增路由。
 * 把页面类型写成联合类型，可以明确区分工程列表、单个工程、文档与不存在的地址。
 */
export type WorkspaceRoute =
  | { kind: 'projects' }
  | { kind: 'project'; projectId: string }
  | { kind: 'document'; documentId: string }
  | { kind: 'not-found' }

/**
 * 解析地址栏 Hash，让刷新、直接访问及浏览器前进/后退使用同一套页面选择规则。
 * 空 Hash 视为工程列表；路径结构或 URL 编码不合法时返回 not-found，避免解析异常使页面空白。
 * 此处只解析地址，具体工程是否已登记，由调用方查询 projects 数组决定。
 */
export function parseWorkspaceHash(hash: string): WorkspaceRoute {
  if (!hash || hash === '#projects') return { kind: 'projects' }

  const match = /^#(projects|docs)\/([^/]+)$/.exec(hash)
  if (!match) return { kind: 'not-found' }

  try {
    const id = decodeURIComponent(match[2]!)
    return match[1] === 'projects'
      ? { kind: 'project', projectId: id }
      : { kind: 'document', documentId: id }
  } catch {
    // 例如 % 后缺少合法编码时，decodeURIComponent 会抛异常；将其作为无效地址处理即可。
    return { kind: 'not-found' }
  }
}

/** 为已登记的工程生成页面地址，编码标识以免特殊字符意外变成路径分隔符。 */
export function projectHash(projectId: string): string {
  return `#projects/${encodeURIComponent(projectId)}`
}
