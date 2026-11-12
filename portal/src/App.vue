<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { createHttpClient, HttpClientError } from '@learn/http'
import AppIcon from './components/AppIcon.vue'
import { documents, parseDocument } from './documents'
import { foundationBaseURL, projects, selectProjects } from './projects'
import type { ProjectCategory, ProjectDefinition, ProjectSort } from './projects'

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

const client = createHttpClient({ baseURL: foundationBaseURL, timeoutMs: 4_000 })
const query = ref('')
const category = ref<ProjectCategory>('all')
const sort = ref<ProjectSort>('recent')
const route = ref(window.location.hash || '#projects')
const mobileNavOpen = ref(false)
const searchInput = ref<HTMLInputElement>()
const drawer = ref<HTMLDialogElement>()
const selectedProject = ref<ProjectDefinition | null>(null)
const runtime = ref<Record<string, RuntimeRecord>>({})
const controllers = new Map<string, AbortController>()
const copyFeedback = ref('')
let copyTimer: ReturnType<typeof setTimeout> | undefined

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
const currentDocument = computed(() =>
  documents.find((document) => route.value === `#docs/${document.id}`),
)
const documentBlocks = computed(() =>
  currentDocument.value ? parseDocument(currentDocument.value.content) : [],
)
const categoryTitle = computed(
  () => ({ all: '全部工程', foundation: '基础工程', learning: '学习工程' })[category.value],
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
const selectedRuntime = computed(() =>
  selectedProject.value ? getRuntime(selectedProject.value.id) : { state: 'unknown' as const },
)
const stateLabels: Record<RuntimeState, string> = {
  unknown: '未检测',
  checking: '检查中',
  online: '在线',
  offline: '离线',
}

function getRuntime(id: string): RuntimeRecord {
  return runtime.value[id] ?? { state: 'unknown' }
}

function navigateProjects(nextCategory: ProjectCategory = 'all') {
  category.value = nextCategory
  window.location.hash = 'projects'
  mobileNavOpen.value = false
}

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

async function checkAll() {
  await Promise.all(projects.map(checkProject))
}

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

function closeDrawer() {
  drawer.value?.close()
  selectedProject.value = null
}
function backdropClick(event: MouseEvent) {
  if (event.target === drawer.value) closeDrawer()
}

watch(selectedProject, async (project) => {
  if (project) {
    await nextTick()
    drawer.value?.showModal()
  }
})

function hashChanged() {
  route.value = window.location.hash || '#projects'
  mobileNavOpen.value = false
}
function keyboardShortcut(event: KeyboardEvent) {
  if (event.key === 'Escape') mobileNavOpen.value = false
  const target = event.target as HTMLElement | null
  if (
    event.key !== '/' ||
    event.ctrlKey ||
    event.metaKey ||
    event.altKey ||
    target?.isContentEditable ||
    /^(INPUT|TEXTAREA|SELECT)$/.test(target?.tagName ?? '') ||
    drawer.value?.open
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
  window.removeEventListener('hashchange', hashChanged)
  window.removeEventListener('keydown', keyboardShortcut)
  controllers.forEach((controller) => controller.abort())
  clearTimeout(copyTimer)
})
</script>

<template>
  <a class="skip-link" href="#main-content">跳到主要内容</a>
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
          :class="{ active: !currentDocument && category === 'all' }"
          @click="navigateProjects('all')"
          ><AppIcon name="grid" /><span>全部工程</span
          ><span class="nav-count">{{ projects.length }}</span></a
        >
        <a
          href="#projects"
          :class="{ active: !currentDocument && category === 'foundation' }"
          @click="navigateProjects('foundation')"
          ><AppIcon name="layers" /><span>基础工程</span
          ><span class="nav-count">{{
            projects.filter((project) => project.category === 'foundation').length
          }}</span></a
        >
        <a
          href="#projects"
          :class="{ active: !currentDocument && category === 'learning' }"
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
        <div class="breadcrumbs">
          <button
            class="icon-button mobile-menu"
            :aria-expanded="mobileNavOpen"
            aria-label="打开工作台导航"
            @click="mobileNavOpen = !mobileNavOpen"
          >
            <AppIcon name="menu" />
          </button>
          <AppIcon class="breadcrumb-home" name="folder" :size="17" /><span>工作空间</span
          ><span class="breadcrumb-divider">/</span
          ><strong>{{ currentDocument?.title ?? categoryTitle }}</strong>
        </div>
        <a class="topbar-docs" href="#docs/development"
          ><AppIcon name="book" :size="17" /><span>开发指南</span
          ><AppIcon name="external" :size="13"
        /></a>
      </header>

      <main id="main-content" class="main-content" tabindex="-1">
        <template v-if="!currentDocument">
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
              <button
                v-for="project in visibleProjects"
                :key="project.id"
                class="project-card"
                :class="{ 'project-row': view === 'list' }"
                :aria-label="`查看${project.name}的工程详情`"
                @click="selectedProject = project"
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
              </button>

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

        <template v-else>
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
      </main>
    </div>
  </div>

  <dialog
    ref="drawer"
    class="project-drawer"
    aria-labelledby="drawer-title"
    @click="backdropClick"
    @close="selectedProject = null"
  >
    <div v-if="selectedProject" class="drawer-content">
      <header class="drawer-top">
        <span><AppIcon name="folder" :size="17" />工程详情</span
        ><button class="icon-button" aria-label="关闭工程详情" autofocus @click="closeDrawer">
          <AppIcon name="close" />
        </button>
      </header>
      <div class="drawer-main">
        <span class="drawer-project-icon"><AppIcon name="code" :size="28" /></span>
        <p class="eyebrow">FOUNDATION PROJECT</p>
        <h2 id="drawer-title">{{ selectedProject.name }}</h2>
        <p class="drawer-description">{{ selectedProject.description }}</p>
        <div class="project-tags">
          <span v-for="tag in selectedProject.tags" :key="tag">{{ tag }}</span>
        </div>
        <div class="runtime-panel">
          <div>
            <span class="runtime-badge" :class="selectedRuntime.state"
              ><span class="status-dot"></span>{{ stateLabels[selectedRuntime.state] }}</span
            ><span v-if="selectedRuntime.checkedAt" class="checked-at"
              >检测于 {{ selectedRuntime.checkedAt }}</span
            >
          </div>
          <button
            class="button button-quiet"
            :disabled="selectedRuntime.state === 'checking'"
            @click="checkProject(selectedProject)"
          >
            <AppIcon
              name="refresh"
              :size="15"
              :class="{ spinning: selectedRuntime.state === 'checking' }"
            />检测
          </button>
          <p v-if="selectedRuntime.message" class="runtime-message">
            {{ selectedRuntime.message }}
          </p>
          <dl v-if="selectedRuntime.info" class="service-info">
            <div>
              <dt>服务</dt>
              <dd>{{ selectedRuntime.info.name }}</dd>
            </div>
            <div>
              <dt>版本</dt>
              <dd>{{ selectedRuntime.info.version }}</dd>
            </div>
            <div>
              <dt>Java</dt>
              <dd>{{ selectedRuntime.info.javaVersion }}</dd>
            </div>
          </dl>
        </div>
        <div class="detail-section">
          <h3>工程入口<small>服务启动后可访问</small></h3>
          <div class="service-entries">
            <a
              v-for="entry in selectedProject.entries"
              :key="entry.url"
              class="button button-secondary"
              :href="entry.url"
              target="_blank"
              rel="noopener noreferrer"
              ><AppIcon :name="entry.label === '健康检查' ? 'activity' : 'external'" :size="15" />{{
                entry.label
              }}<AppIcon name="external" :size="12"
            /></a>
          </div>
        </div>
        <div class="detail-section">
          <h3>工程位置</h3>
          <code class="source-path">{{ selectedProject.sourcePath }}</code>
        </div>
        <div class="detail-section">
          <h3>基础能力</h3>
          <div class="capability-list">
            <div v-for="capability in selectedProject.capabilities" :key="capability.title">
              <span class="capability-check"><AppIcon name="check" :size="13" /></span>
              <p>
                <strong>{{ capability.title }}</strong><span>{{ capability.description }}</span>
              </p>
            </div>
          </div>
        </div>
        <div class="detail-section">
          <h3>启动与验证<small>在仓库根目录执行</small></h3>
          <div
            v-for="command in selectedProject.commands"
            :key="command.label"
            class="command-block"
          >
            <div>
              <span>{{ command.label }}</span><button
                class="icon-button"
                :aria-label="`复制${command.label}命令`"
                @click="copyCommand(command.value)"
              >
                <AppIcon name="copy" :size="15" />
              </button>
            </div>
            <pre><code>{{ command.value }}</code></pre>
          </div>
          <p class="copy-feedback" role="status">{{ copyFeedback }}</p>
        </div>
        <div class="detail-section">
          <h3>学习目标</h3>
          <ul class="learning-goals">
            <li v-for="goal in selectedProject.learningGoals" :key="goal">{{ goal }}</li>
          </ul>
        </div>
      </div>
      <footer class="drawer-footer">
        <a
          class="button button-primary"
          :href="`#docs/${selectedProject.documentId}`"
          @click="closeDrawer"
          ><AppIcon name="book" :size="17" />阅读开发指南<AppIcon name="arrow" :size="16" /></a
        ><button class="button button-secondary" @click="closeDrawer">关闭</button>
      </footer>
    </div>
  </dialog>
</template>
