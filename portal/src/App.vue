<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { createHttpClient, HttpClientError } from '@learn/http'
import AppIcon from './components/AppIcon.vue'
import ProjectWorkspace from './components/ProjectWorkspace.vue'
import { documents, parseDocument } from './documents'
import { parseWorkspaceHash, projectHash } from './navigation'
import { foundationBaseURL, projects, selectProjects } from './projects'
import type { ProjectCategory, ProjectDefinition, ProjectSort } from './projects'

// App 保留左侧菜单和顶部导航，只根据 Hash 地址替换 main 内的列表、工程页或文档。
// 工程内容独立为 ProjectWorkspace，导航与探测仍由本组件管理，避免页面切换丢失状态。

/** 运行状态只来源于用户手动探测；刚打开页面时为 unknown，不推断服务已经运行。 */
type RuntimeState = 'unknown' | 'checking' | 'online' | 'offline'
interface FoundationInfo {
  name: string
  version: string
  javaVersion: string
  capabilities: string[]
}
interface RuntimeRecord {
  state: RuntimeState
  checkedAt?: string
  info?: FoundationInfo
  message?: string
}

// HTTP 客户端负责传输机制；这里配置基础服务的地址及 4 秒超时，不负责启动进程。
const client = createHttpClient({ baseURL: foundationBaseURL, timeoutMs: 4_000 })
const query = ref('')
const category = ref<ProjectCategory>('all')
const sort = ref<ProjectSort>('recent')
const route = ref(window.location.hash || '#projects')
const navigation = computed(() => parseWorkspaceHash(route.value))
const mobileNavOpen = ref(false)
const searchInput = ref<HTMLInputElement>()
const mainContent = ref<HTMLElement>()
// 以工程 ID 保存探测记录，因此返回列表或进入工程页时能够复用同一份真实结果。
const runtime = ref<Record<string, RuntimeRecord>>({})
const controllers = new Map<string, AbortController>()
const copyFeedback = ref('')
let copyTimer: ReturnType<typeof setTimeout> | undefined

/** 恢复用户上次选择的展示方式；浏览器禁用存储时退回网格，不影响页面可用性。 */
function storedView(): 'grid' | 'list' {
  try {
    return localStorage.getItem('learn.portal.view') === 'list' ? 'list' : 'grid'
  } catch {
    return 'grid'
  }
}
const view = ref(storedView())
watch(view, (value) => {
  try {
    localStorage.setItem('learn.portal.view', value)
  } catch {
    /* Private browsing can disable storage. */
  }
})

const visibleProjects = computed(() =>
  selectProjects(projects, query.value, category.value, sort.value),
)
// computed 会在 URL 改变时重新查询。工程页不使用弹窗状态，刷新当前地址仍能定位工程。
const currentProject = computed(() => {
  const currentRoute = navigation.value
  return currentRoute.kind === 'project'
    ? projects.find((project) => project.id === currentRoute.projectId)
    : undefined
})
const currentDocument = computed(() => {
  const currentRoute = navigation.value
  return currentRoute.kind === 'document'
    ? documents.find((document) => document.id === currentRoute.documentId)
    : undefined
})
const documentBlocks = computed(() =>
  currentDocument.value ? parseDocument(currentDocument.value.content) : [],
)
const categoryTitle = computed(
  () => ({ all: '全部工程', foundation: '基础工程', learning: '学习工程' })[category.value],
)
// 在工程页中高亮工程所属分类，列表筛选条件仍保留，便于按原分类返回。
const activeCategory = computed(() => currentProject.value?.category ?? category.value)
const missingPageTitle = computed(() =>
  navigation.value.kind === 'project' ? '没有找到这个工程' : '没有找到这个页面',
)
const onlineCount = computed(
  () => Object.values(runtime.value).filter((item) => item.state === 'online').length,
)
const checking = computed(() =>
  Object.values(runtime.value).some((item) => item.state === 'checking'),
)
const checkedCount = computed(
  () => Object.values(runtime.value).filter((item) => item.checkedAt).length,
)
const stateLabels: Record<RuntimeState, string> = {
  unknown: '未检测',
  checking: '检查中',
  online: '在线',
  offline: '离线',
}

/** 没有探测记录时返回默认状态，列表卡片和工程页遵循同一个状态来源。 */
function getRuntime(id: string): RuntimeRecord {
  return runtime.value[id] ?? { state: 'unknown' }
}

/** 返回工程列表并选择分类；同步更新响应式地址，方便键盘快捷键等待下一次 DOM 更新。 */
function navigateProjects(nextCategory: ProjectCategory = 'all') {
  category.value = nextCategory
  route.value = '#projects'
  window.location.hash = 'projects'
  mobileNavOpen.value = false
}

/** 返回入口保留当前列表分类；在新标签页直接进入工程时，默认返回全部工程。 */
function returnToProjects() {
  navigateProjects(category.value)
  window.scrollTo({ top: 0, behavior: 'auto' })
}

/** 跳过导航直接聚焦主区，不改 Hash，避免无障碍跳转被误认为一次页面导航。 */
function focusMainContent() {
  mainContent.value?.focus({ preventScroll: true })
}

/** 在用户点击检测时重新读取当前工程，避免快速切换地址后仍使用旧页面的引用。 */
async function checkCurrentProject() {
  const project = currentProject.value
  if (project) await checkProject(project)
}

/**
 * 按用户操作探测服务：先读取健康状态，再补充版本等信息。
 * 健康接口成功但信息接口失败时仍显示在线，并单独提示信息读取问题。
 * AbortController 用于页面销毁时取消请求，避免旧请求再写回已销毁的组件。
 */
async function checkProject(project: ProjectDefinition) {
  if (getRuntime(project.id).state === 'checking') return
  const controller = new AbortController()
  controllers.set(project.id, controller)
  runtime.value[project.id] = { state: 'checking' }
  try {
    const health = await client.getJson<{ status: string }>('/actuator/health', {
      signal: controller.signal,
    })
    if (health.status !== 'UP') throw new Error('健康检查尚未就绪')
    let info: FoundationInfo | undefined
    let message: string | undefined
    try {
      const response = await client.getJson<FoundationInfo>('/api/foundation/info', {
        signal: controller.signal,
      })
      if (typeof response.name !== 'string' || !Array.isArray(response.capabilities))
        throw new Error('服务信息格式不符合约定')
      info = response
    } catch (error) {
      if (controller.signal.aborted) return
      message =
        error instanceof Error
          ? `服务在线，基础信息读取失败：${error.message}`
          : '服务在线，基础信息暂不可用'
    }
    if (!controller.signal.aborted)
      runtime.value[project.id] = {
        state: 'online',
        checkedAt: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
        info,
        message,
      }
  } catch (error) {
    if (controller.signal.aborted) return
    runtime.value[project.id] = {
      state: 'offline',
      checkedAt: new Date().toLocaleTimeString('zh-CN', { hour12: false }),
      message:
        error instanceof HttpClientError ? error.message : '健康检查尚未就绪，请确认服务已经启动。',
    }
  } finally {
    controllers.delete(project.id)
  }
}

/** 列表上的批量检测复用单工程检测；它只发送 HTTP 请求，不启动任何服务。 */
async function checkAll() {
  await Promise.all(projects.map(checkProject))
}

/** 只复制命令文本；用户在终端自行执行，浏览器无权限代为启动项目。 */
async function copyCommand(value: string) {
  clearTimeout(copyTimer)
  try {
    await navigator.clipboard.writeText(value)
    copyFeedback.value = '命令已复制'
  } catch {
    copyFeedback.value = '暂时无法复制，请选中命令手动复制'
  }
  copyTimer = setTimeout(() => {
    copyFeedback.value = ''
  }, 3_000)
}

/**
 * 原生链接、浏览器前进和后退都会触发 hashchange。
 * Vue 在 nextTick 后才完成模板替换，因此工程页的聚焦和滚动放在 DOM 更新之后。
 */
function hashChanged() {
  route.value = window.location.hash || '#projects'
  mobileNavOpen.value = false
  clearTimeout(copyTimer)
  copyFeedback.value = ''
  void nextTick(() => {
    window.scrollTo({ top: 0, behavior: 'auto' })
    if (navigation.value.kind === 'project') focusMainContent()
  })
}
/** / 快捷键返回列表并定位搜索框；输入控件内的 / 作为普通字符保留。 */
function keyboardShortcut(event: KeyboardEvent) {
  if (event.key === 'Escape') mobileNavOpen.value = false
  const target = event.target as HTMLElement | null
  if (
    event.key !== '/' ||
    event.ctrlKey ||
    event.metaKey ||
    event.altKey ||
    target?.isContentEditable ||
    /^(INPUT|TEXTAREA|SELECT)$/.test(target?.tagName ?? '')
  )
    return
  event.preventDefault()
  navigateProjects(category.value)
  void nextTick(() => searchInput.value?.focus())
}

onMounted(() => {
  window.addEventListener('hashchange', hashChanged)
  window.addEventListener('keydown', keyboardShortcut)
})
onBeforeUnmount(() => {
  // 移除全局事件、终止请求和清理提示计时器，避免热更新或卸载后留下旧的副作用。
  window.removeEventListener('hashchange', hashChanged)
  window.removeEventListener('keydown', keyboardShortcut)
  controllers.forEach((controller) => controller.abort())
  clearTimeout(copyTimer)
})
</script>

<template>
  <a class="skip-link" href="#main-content" @click.prevent="focusMainContent">跳到主要内容</a>
  <div class="workspace">
    <button
      v-if="mobileNavOpen"
      class="nav-backdrop"
      aria-label="关闭导航"
      @click="mobileNavOpen = false"
    ></button>
    <aside class="sidebar" :class="{ 'is-open': mobileNavOpen }" aria-label="工作台导航">
      <a class="brand" href="#projects" @click="navigateProjects()">
        <span class="brand-mark"><AppIcon name="folder" :size="24" /></span>
        <span
          ><strong>Learn<span class="brand-dot">.</span></strong
          ><small>个人工程学习工作台</small></span
        >
      </a>
      <div class="workspace-label">
        <span class="workspace-avatar">W</span
        ><span>我的工作空间<small>Personal workspace</small></span
        ><span class="workspace-dot"></span>
      </div>

      <p class="nav-label">工作空间</p>
      <nav class="project-nav" aria-label="工程分类">
        <a
          href="#projects"
          :class="{ active: !currentDocument && activeCategory === 'all' }"
          @click="navigateProjects('all')"
          ><AppIcon name="grid" /><span>全部工程</span
          ><span class="nav-count">{{ projects.length }}</span></a
        >
        <a
          href="#projects"
          :class="{ active: !currentDocument && activeCategory === 'foundation' }"
          @click="navigateProjects('foundation')"
          ><AppIcon name="layers" /><span>基础工程</span
          ><span class="nav-count">{{
            projects.filter((project) => project.category === 'foundation').length
          }}</span></a
        >
        <a
          href="#projects"
          :class="{ active: !currentDocument && activeCategory === 'learning' }"
          @click="navigateProjects('learning')"
          ><AppIcon name="compass" /><span>学习工程</span
          ><span class="nav-count">{{
            projects.filter((project) => project.category === 'learning').length
          }}</span></a
        >
      </nav>

      <p class="nav-label docs-label">文档与记录</p>
      <nav class="document-nav" aria-label="文档导航">
        <a
          v-for="document in documents"
          :key="document.id"
          :href="`#docs/${document.id}`"
          :class="{ active: currentDocument?.id === document.id }"
          ><AppIcon
            :name="
              document.id === 'progress'
                ? 'activity'
                : document.id === 'architecture'
                  ? 'layers'
                  : 'file'
            "
            :size="19"
          /><span>{{ document.title }}</span></a
        >
      </nav>

      <div class="sidebar-bottom">
        <div class="learning-note">
          <span class="note-symbol">✦</span>
          <p>一步一步，积累成体系。<small>让每次学习，都有迹可循。</small></p>
        </div>
        <a
          class="repository-link"
          href="https://github.com/wjlsssq/learn"
          target="_blank"
          rel="noopener noreferrer"
          ><AppIcon name="github" :size="18" /><span>源代码仓库</span
          ><AppIcon name="external" :size="14"
        /></a>
        <span class="workspace-version">WORKSPACE <span>v0.1.0</span></span>
      </div>
    </aside>

    <div class="workspace-body">
      <header class="topbar">
        <nav class="breadcrumbs" aria-label="面包屑">
          <button
            class="icon-button mobile-menu"
            :aria-expanded="mobileNavOpen"
            aria-label="打开工作台导航"
            @click="mobileNavOpen = !mobileNavOpen"
          >
            <AppIcon name="menu" />
          </button>
          <!-- 面包屑属于固定页面壳；进入工程后增加末级名称，前两级仍可返回列表。 -->
          <AppIcon class="breadcrumb-home" name="folder" :size="17" />
          <a href="#projects" @click="navigateProjects()">工作空间</a>
          <span class="breadcrumb-divider" aria-hidden="true">/</span>
          <template v-if="currentProject">
            <a href="#projects" @click="navigateProjects(category)">{{ categoryTitle }}</a>
            <span class="breadcrumb-divider" aria-hidden="true">/</span>
            <strong aria-current="page">{{ currentProject.name }}</strong>
          </template>
          <strong v-else aria-current="page">{{
            currentDocument?.title ??
            (navigation.kind === 'projects' ? categoryTitle : missingPageTitle)
          }}</strong>
        </nav>
        <a class="topbar-docs" href="#docs/development"
          ><AppIcon name="book" :size="17" /><span>开发指南</span
          ><AppIcon name="external" :size="13"
        /></a>
      </header>

      <main
        id="main-content"
        ref="mainContent"
        class="main-content"
        :class="{ 'project-main': navigation.kind === 'project' }"
        tabindex="-1"
      >
        <!-- 工程页占据整个主内容区；首页统计、搜索和卡片不会继续叠在工程内容上方。 -->
        <ProjectWorkspace
          v-if="currentProject"
          :project="currentProject"
          :runtime="getRuntime(currentProject.id)"
          :copy-feedback="copyFeedback"
          @back="returnToProjects"
          @check="checkCurrentProject"
          @copy="copyCommand"
        />

        <template v-else-if="navigation.kind === 'projects'">
          <section class="page-heading">
            <div>
              <p class="eyebrow">YOUR LEARNING, ORGANIZED</p>
              <h1>我的工程空间<span class="title-dot">.</span></h1>
              <p class="page-description">把每一段学习，变成可以继续生长的工程。</p>
            </div>
            <a href="#docs/onboarding" class="button button-primary"
              ><AppIcon name="book" :size="17" />工程接入指南<AppIcon name="arrow" :size="17"
            /></a>
          </section>

          <section class="overview-strip" aria-label="工作空间概况">
            <div class="overview-item">
              <span class="overview-icon"><AppIcon name="folder" :size="21" /></span>
              <div>
                <span class="overview-title">已接入工程</span
                ><strong
                  >{{ projects.length.toString().padStart(2, '0')
                  }}<small>个独立工程</small></strong
                >
              </div>
            </div>
            <div class="overview-item">
              <span class="overview-icon green"><AppIcon name="activity" :size="21" /></span>
              <div>
                <span class="overview-title">在线服务</span
                ><strong
                  >{{ onlineCount.toString().padStart(2, '0')
                  }}<small>{{
                    checkedCount ? `${checkedCount} 个已检测` : '尚未检测运行状态'
                  }}</small></strong
                >
              </div>
            </div>
            <div class="overview-item overview-principle">
              <span class="overview-icon violet"><AppIcon name="layers" :size="21" /></span>
              <div>
                <span class="overview-title">当前建设重点</span
                ><strong class="principle-text">基础底座<small>清晰边界 · 按需扩展</small></strong>
              </div>
            </div>
          </section>

          <section class="project-section" aria-labelledby="project-section-title">
            <div class="section-heading">
              <div class="section-title-wrap">
                <h2 id="project-section-title">{{ categoryTitle }}</h2>
                <span class="section-count">{{ visibleProjects.length }}</span>
              </div>
              <button
                class="button button-quiet status-refresh"
                :disabled="checking"
                @click="checkAll"
              >
                <AppIcon name="refresh" :size="16" :class="{ spinning: checking }" />{{
                  checking ? '检查中…' : '检测运行状态'
                }}
              </button>
            </div>
            <div class="project-toolbar">
              <label class="search-field"
                ><AppIcon name="search" :size="19" /><input
                  ref="searchInput"
                  v-model="query"
                  placeholder="搜索工程名称、技术栈…"
                  aria-label="搜索工程名称或技术栈"
                /><button
                  v-if="query"
                  class="clear-search"
                  aria-label="清空搜索"
                  @click="query = ''"
                >
                  <AppIcon name="close" :size="14" /></button
                ><kbd v-else>/</kbd></label
              >
              <div class="toolbar-right">
                <label class="sort-field"
                  ><span class="visually-hidden">工程排序</span
                  ><select v-model="sort">
                    <option value="recent">最近更新</option>
                    <option value="name">名称排序</option></select
                  ><AppIcon name="chevronDown" :size="14" /></label
                ><span class="toolbar-divider"></span>
                <div class="view-switch" role="group" aria-label="工程展示方式">
                  <button
                    :class="{ selected: view === 'grid' }"
                    :aria-pressed="view === 'grid'"
                    aria-label="网格视图"
                    @click="view = 'grid'"
                  >
                    <AppIcon name="grid" :size="17" /></button
                  ><button
                    :class="{ selected: view === 'list' }"
                    :aria-pressed="view === 'list'"
                    aria-label="列表视图"
                    @click="view = 'list'"
                  >
                    <AppIcon name="list" :size="19" />
                  </button>
                </div>
              </div>
            </div>

            <div
              v-if="visibleProjects.length"
              class="project-grid"
              :class="{ 'list-view': view === 'list' }"
            >
              <a
                v-for="project in visibleProjects"
                :key="project.id"
                class="project-card"
                :class="{ 'project-row': view === 'list' }"
                :aria-label="`进入${project.name}`"
                :href="projectHash(project.id)"
              >
                <span class="card-top"
                  ><span class="project-folder" aria-hidden="true"
                    ><svg viewBox="0 0 80 66" fill="none">
                      <path
                        d="M6 14a7 7 0 0 1 7-7h18l8 9h29a7 7 0 0 1 7 7v33H6V14Z"
                        fill="#9CB4FF"
                      />
                      <path
                        d="M5 24a7 7 0 0 1 7-7h56a7 7 0 0 1 7 7v32a7 7 0 0 1-7 7H12a7 7 0 0 1-7-7V24Z"
                        fill="url(#folder-fill)"
                      />
                      <path
                        d="m34 34-7 7 7 7M47 34l7 7-7 7"
                        stroke="white"
                        stroke-width="3"
                        stroke-linecap="round"
                        stroke-linejoin="round"
                      />
                      <defs>
                        <linearGradient
                          id="folder-fill"
                          x1="40"
                          y1="17"
                          x2="40"
                          y2="63"
                          gradientUnits="userSpaceOnUse"
                        >
                          <stop stop-color="#648AFF" />
                          <stop offset="1" stop-color="#4168E8" />
                        </linearGradient>
                      </defs></svg></span
                  ><span class="runtime-badge" :class="getRuntime(project.id).state"
                    ><span class="status-dot"></span
                    >{{ stateLabels[getRuntime(project.id).state] }}</span
                  ></span
                >
                <span class="card-body"
                  ><span class="card-title"
                    >{{ project.name }}<AppIcon name="arrow" :size="17" /></span
                  ><span class="project-id">{{ project.id }}</span
                  ><span class="card-description">{{ project.description }}</span
                  ><span class="project-tags"
                    ><span v-for="tag in project.tags" :key="tag">{{ tag }}</span></span
                  ></span
                >
                <span class="card-footer"
                  ><span><AppIcon name="folder" :size="14" />独立工程</span
                  ><span>{{ project.updatedAt.replaceAll('-', '.') }}</span></span
                >
              </a>

              <div v-if="view === 'grid' && !query && category === 'all'" class="next-project-note">
                <span class="next-project-symbol"><AppIcon name="compass" :size="25" /></span
                ><strong>下一段学习，从这里开始</strong>
                <p>有了新的学习目标，再接入新的能力。<br />每个工程，都保留自己的清晰边界。</p>
                <a href="#docs/onboarding">了解工程接入规则<AppIcon name="arrow" :size="15" /></a>
              </div>
            </div>
            <div v-else class="empty-state">
              <span class="empty-icon"
                ><AppIcon :name="query ? 'search' : 'folder'" :size="34"
              /></span>
              <h3>{{ query ? '没有找到匹配的工程' : '这片空间，等待新的学习' }}</h3>
              <p>
                {{
                  query
                    ? '试试其他工程名称或技术关键词。'
                    : '新的学习工程会在实际建设后出现在这里。'
                }}
              </p>
              <button v-if="query" class="button button-secondary" @click="query = ''">
                清空搜索</button
              ><a v-else class="button button-secondary" href="#docs/onboarding"
                >查看接入指南<AppIcon name="arrow" :size="15"
              /></a>
            </div>
          </section>

          <section class="workspace-guide" aria-label="工程使用说明">
            <span class="guide-symbol"><AppIcon name="terminal" :size="20" /></span>
            <div>
              <strong>环境按需启动，学习保持轻量。</strong>
              <p>先在终端启动当前工程，再检测运行状态。工作台负责组织入口与文档。</p>
            </div>
            <a href="#docs/development">查看开发指南<AppIcon name="arrow" :size="16" /></a>
          </section>
          <footer class="page-footer">
            <span><span class="footer-dot"></span>独立工程，持续积累。</span
            ><a href="#docs/progress">查看当前进度<AppIcon name="arrow" :size="13" /></a>
          </footer>
        </template>

        <template v-else-if="currentDocument">
          <section class="page-heading document-heading">
            <div>
              <p class="eyebrow">WORKSPACE DOCUMENTATION</p>
              <h1>{{ currentDocument.title }}<span class="title-dot">.</span></h1>
              <p class="page-description">{{ currentDocument.description }}</p>
            </div>
            <a class="button button-secondary" href="#projects"
              ><AppIcon name="folder" :size="17" />返回工程空间</a
            >
          </section>
          <section class="document-panel" :aria-label="currentDocument.title">
            <div class="document-meta">
              <AppIcon name="file" :size="16" /><code>{{ currentDocument.path }}</code
              ><span>与仓库源文件保持一致</span>
            </div>
            <article class="document-content">
              <template v-for="(block, index) in documentBlocks" :key="index"
                ><component :is="`h${block.level}`" v-if="block.kind === 'heading'">{{
                  block.text
                }}</component>
                <div v-else-if="block.kind === 'code'" class="document-code">
                  <span v-if="block.language" class="code-language">{{ block.language }}</span>
                  <pre><code>{{ block.text }}</code></pre>
                </div>
                <p v-else class="document-text">{{ block.text }}</p></template
              >
            </article>
          </section>
        </template>

        <!-- 未登记的工程或错误地址必须有明确反馈，避免被误当作列表或空白页面。 -->
        <section v-else class="empty-state" aria-labelledby="missing-page-title">
          <span class="empty-icon"><AppIcon name="folder" :size="34" /></span>
          <h1 id="missing-page-title">{{ missingPageTitle }}</h1>
          <p>地址可能已变更，或对应内容尚未接入工作台。</p>
          <a class="button button-secondary" href="#projects" @click="navigateProjects()">
            返回工程列表
          </a>
        </section>
      </main>
    </div>
  </div>
</template>
