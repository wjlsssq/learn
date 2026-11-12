<script setup lang="ts">
/**
 * 工程内容页：位于工作台右侧主内容区域，展示当前工程已经登记和实际探测得到的信息。
 *
 * 学习阅读顺序：
 * 1. 父组件 App.vue 根据地址中的工程标识找到 ProjectDefinition。
 * 2. 父组件通过 props 把工程登记信息、运行状态和复制结果传入本组件。
 * 3. 本组件只展示数据；检测、复制和返回操作通过 emit 通知父组件处理。
 *
 * props 可以理解为父组件传给子组件的只读参数；emit 是子组件向父组件发送的操作通知。
 * 这样的单向数据流能让列表页和工程页共用同一份真实运行状态，避免维护两份互相矛盾的数据。
 * 本页不发起请求、不启动本地进程，也不会在进入页面时把服务状态默认改为在线。
 */
import { computed } from 'vue'
import AppIcon from './AppIcon.vue'
import type { ProjectDefinition } from '../projects'
import '../project-workspace.css'

/**
 * 运行状态的类型约定，与 App.vue 维护的数据结构一致。
 * interface 只约束 TypeScript 编写阶段的数据形状，不会自动向服务器查询数据。
 */
interface ProjectRuntime {
  /** unknown 表示尚未检测；其余状态必须由父组件的实际探测流程更新。 */
  state: 'unknown' | 'checking' | 'online' | 'offline'
  /** 最近一次探测完成的显示时间；未检测或正在首次检测时可以不存在。 */
  checkedAt?: string
  /** 服务真实返回的运行信息；未获取成功时不显示推测的版本或能力。 */
  info?: {
    name: string
    version: string
    javaVersion: string
    capabilities: string[]
  }
  /** 父组件整理的探测说明或失败信息，本组件按文本显示，不把它当作 HTML 执行。 */
  message?: string
}

/**
 * defineProps 声明组件的输入。
 * project 继续使用现有登记类型，避免把工程信息复制到这个页面后造成后续更新遗漏。
 * runtime 和 copyFeedback 都由父组件维护，本组件不直接修改这些传入值。
 */
const props = defineProps<{
  project: ProjectDefinition
  runtime: ProjectRuntime
  copyFeedback: string
}>()

/**
 * defineEmits 声明允许向父组件发送的事件，以及每种事件携带的参数。
 * [] 表示该事件没有额外参数；[value: string] 表示复制事件会携带完整命令文本。
 * 父组件分别负责页面导航、调用健康检查和操作剪贴板，本组件不重复实现这些副作用。
 */
const emit = defineEmits<{
  back: []
  check: []
  copy: [value: string]
}>()

/** 将程序使用的状态值翻译成页面文案，保证未检测和离线是两个不同的含义。 */
const runtimeLabels: Record<ProjectRuntime['state'], string> = {
  unknown: '未检测',
  checking: '检查中',
  online: '在线',
  offline: '离线',
}

/**
 * computed 是根据响应式输入自动重新计算的只读值。
 * 当父组件完成探测并替换 runtime 时，状态说明会自动更新，无需手动修改页面文字。
 * 这些文案描述最近一次检查结果，不承诺服务此后始终在线。
 */
const runtimeExplanation = computed(() => {
  switch (props.runtime.state) {
    case 'checking':
      return '正在检查工程的实际运行状态，请稍候。'
    case 'online':
      return '最近一次健康检查通过，可以访问工程提供的入口。'
    case 'offline':
      return '最近一次探测未连接到服务，请结合启动终端和探测说明排查。'
    default:
      return '尚未检测运行状态。按需启动工程后，可手动发起检查。'
  }
})

/** 顶部快捷入口复用登记数组的第一项；没有登记入口时不渲染空链接。 */
const primaryEntry = computed(() => props.project.entries[0])

/** 这里仅展示已有的工程分类，不据此推断未来工程必须使用哪种语言。 */
const categoryLabel = computed(() =>
  props.project.category === 'foundation' ? '基础工程' : '学习工程',
)
</script>

<template>
  <!-- 本组件仅占据父布局的右侧内容区域，左侧菜单和顶部面包屑继续由 App.vue 保留。 -->
  <section class="project-workspace" aria-labelledby="project-workspace-title">
    <!-- 顶部区域：返回列表、当前工程身份和快捷入口；这里没有弹窗或抽屉关闭逻辑。 -->
    <div class="project-workspace-topline">
      <button class="project-workspace-back" type="button" @click="emit('back')">
        <AppIcon class="project-workspace-back-icon" name="arrow" :size="16" />
        返回工程列表
      </button>
      <span class="project-workspace-kind">{{ categoryLabel }}</span>
    </div>

    <header class="project-workspace-heading">
      <div class="project-workspace-identity">
        <span class="project-workspace-symbol"><AppIcon name="code" :size="30" /></span>
        <div>
          <p class="project-workspace-identifier">{{ props.project.id }}</p>
          <h1 id="project-workspace-title">{{ props.project.name }}</h1>
          <p class="project-workspace-description">{{ props.project.description }}</p>
          <div class="project-workspace-tags" aria-label="工程技术标签">
            <span v-for="tag in props.project.tags" :key="tag">{{ tag }}</span>
          </div>
        </div>
      </div>
      <div class="project-workspace-heading-actions">
        <!-- 原样使用登记的 URL，让未来独立 Vue、Python 或 Java 工程都能提供自己的入口。 -->
        <a
          v-if="primaryEntry"
          class="button button-primary"
          :href="primaryEntry.url"
          target="_blank"
          rel="noopener noreferrer"
        >
          打开{{ primaryEntry.label }}
          <AppIcon name="external" :size="15" />
        </a>
        <a class="button button-secondary" :href="`#docs/${props.project.documentId}`">
          <AppIcon name="book" :size="16" />
          开发文档
        </a>
      </div>
    </header>

    <!--
      内容区使用正常文档流中的两列布局：主要内容较宽，状态和学习信息位于辅助列。
      小屏幕通过 CSS 改为一列；没有 fixed 定位，也不会限制为原抽屉的窄宽度。
    -->
    <div class="project-workspace-columns">
      <div class="project-workspace-primary">
        <!-- 真实入口：只渲染 projects.ts 已登记的地址，不为尚未实现的业务制造假页面。 -->
        <section class="project-workspace-panel" aria-labelledby="project-entry-title">
          <header class="project-workspace-panel-heading">
            <div>
              <h2 id="project-entry-title">工程入口</h2>
              <p>访问当前工程已经提供的页面或服务接口。</p>
            </div>
            <AppIcon name="external" :size="19" />
          </header>
          <div v-if="props.project.entries.length" class="project-workspace-entries">
            <a
              v-for="entry in props.project.entries"
              :key="entry.url"
              class="project-workspace-entry"
              :href="entry.url"
              target="_blank"
              rel="noopener noreferrer"
            >
              <span class="project-workspace-entry-icon">
                <AppIcon name="external" :size="18" />
              </span>
              <span class="project-workspace-entry-text">
                <strong>{{ entry.label }}</strong>
                <code>{{ entry.url }}</code>
              </span>
              <AppIcon name="arrow" :size="16" />
            </a>
          </div>
          <p v-else class="project-workspace-empty">当前工程尚未登记可访问入口。</p>
          <p class="project-workspace-hint">入口是否可访问取决于实际服务状态，点击链接不会启动进程。</p>
        </section>

        <!-- 能力来自工程登记信息；它与下方服务实时返回的 capabilities 分别表达两类信息。 -->
        <section class="project-workspace-panel" aria-labelledby="project-capability-title">
          <header class="project-workspace-panel-heading">
            <div>
              <h2 id="project-capability-title">工程能力</h2>
              <p>当前工程已经接入的能力及其职责。</p>
            </div>
            <AppIcon name="layers" :size="19" />
          </header>
          <div v-if="props.project.capabilities.length" class="project-workspace-capabilities">
            <div
              v-for="capability in props.project.capabilities"
              :key="capability.title"
              class="project-workspace-capability"
            >
              <span><AppIcon name="check" :size="14" /></span>
              <div>
                <h3>{{ capability.title }}</h3>
                <p>{{ capability.description }}</p>
              </div>
            </div>
          </div>
          <p v-else class="project-workspace-empty">当前工程尚未登记能力说明。</p>
        </section>

        <!--
          命令仅展示和复制，不在浏览器内执行。
          点击复制时把完整原文发送给父组件；成功或失败说明也由父组件统一反馈。
        -->
        <section class="project-workspace-panel" aria-labelledby="project-command-title">
          <header class="project-workspace-panel-heading">
            <div>
              <h2 id="project-command-title">启动与验证</h2>
              <p>在仓库根目录的终端中执行对应命令。</p>
            </div>
            <AppIcon name="terminal" :size="19" />
          </header>
          <div
            v-for="command in props.project.commands"
            :key="command.label"
            class="project-workspace-command"
          >
            <div class="project-workspace-command-heading">
              <span>{{ command.label }}</span>
              <button
                class="project-workspace-copy"
                type="button"
                :aria-label="`复制${command.label}命令`"
                @click="emit('copy', command.value)"
              >
                <AppIcon name="copy" :size="15" />
                复制
              </button>
            </div>
            <pre><code>{{ command.value }}</code></pre>
          </div>
          <p v-if="!props.project.commands.length" class="project-workspace-empty">
            当前工程尚未登记启动或验证命令。
          </p>
          <!-- role=status 让读屏软件也能获知复制结果，反馈内容仍由父组件维护。 -->
          <p class="project-workspace-copy-feedback" role="status">{{ props.copyFeedback }}</p>
        </section>
      </div>

      <aside class="project-workspace-secondary" aria-label="工程状态与学习信息">
        <!-- 运行状态来自同一份父级探测结果；可选字段不存在时保持明确的空状态。 -->
        <section class="project-workspace-panel" aria-labelledby="project-runtime-title">
          <header class="project-workspace-panel-heading">
            <div><h2 id="project-runtime-title">运行状态</h2></div>
            <button
              class="project-workspace-check"
              type="button"
              :disabled="props.runtime.state === 'checking'"
              @click="emit('check')"
            >
              <AppIcon
                name="refresh"
                :size="15"
                :class="{ spinning: props.runtime.state === 'checking' }"
              />
              {{ props.runtime.state === 'checking' ? '检查中…' : '检测状态' }}
            </button>
          </header>
          <div class="project-workspace-runtime-summary" role="status">
            <span class="runtime-badge" :class="props.runtime.state">
              <span class="status-dot"></span>
              {{ runtimeLabels[props.runtime.state] }}
            </span>
            <span v-if="props.runtime.checkedAt" class="project-workspace-check-time">
              检测于 {{ props.runtime.checkedAt }}
            </span>
            <p>{{ runtimeExplanation }}</p>
          </div>
          <p v-if="props.runtime.message" class="project-workspace-runtime-message">
            {{ props.runtime.message }}
          </p>
          <template v-if="props.runtime.info">
            <dl class="project-workspace-runtime-info">
              <div>
                <dt>服务名称</dt>
                <dd>{{ props.runtime.info.name }}</dd>
              </div>
              <div>
                <dt>服务版本</dt>
                <dd>{{ props.runtime.info.version }}</dd>
              </div>
              <div>
                <dt>Java 版本</dt>
                <dd>{{ props.runtime.info.javaVersion }}</dd>
              </div>
            </dl>
            <div
              v-if="props.runtime.info.capabilities.length"
              class="project-workspace-runtime-capabilities"
            >
              <span>服务声明能力</span>
              <ul>
                <li v-for="capability in props.runtime.info.capabilities" :key="capability">
                  {{ capability }}
                </li>
              </ul>
            </div>
          </template>
          <p v-else class="project-workspace-pending-info">尚未取得服务运行信息。</p>
        </section>

        <section class="project-workspace-panel" aria-labelledby="project-source-title">
          <header class="project-workspace-panel-heading">
            <div><h2 id="project-source-title">工程位置</h2></div>
            <AppIcon name="folder" :size="19" />
          </header>
          <code class="project-workspace-source">{{ props.project.sourcePath }}</code>
          <dl class="project-workspace-metadata">
            <div>
              <dt>工程分类</dt>
              <dd>{{ categoryLabel }}</dd>
            </div>
            <div>
              <dt>登记更新</dt>
              <dd>{{ props.project.updatedAt }}</dd>
            </div>
          </dl>
        </section>

        <section class="project-workspace-panel" aria-labelledby="project-goal-title">
          <header class="project-workspace-panel-heading">
            <div><h2 id="project-goal-title">学习目标</h2></div>
            <AppIcon name="compass" :size="19" />
          </header>
          <ul v-if="props.project.learningGoals.length" class="project-workspace-goals">
            <li v-for="goal in props.project.learningGoals" :key="goal">{{ goal }}</li>
          </ul>
          <p v-else class="project-workspace-empty">当前工程尚未登记学习目标。</p>
        </section>

        <!-- 文档采用现有 hash 导航，点击后由父组件切换右侧内容，保留同一套外层布局。 -->
        <section class="project-workspace-panel" aria-labelledby="project-document-title">
          <header class="project-workspace-panel-heading">
            <div><h2 id="project-document-title">相关文档</h2></div>
            <AppIcon name="book" :size="19" />
          </header>
          <a class="project-workspace-document" :href="`#docs/${props.project.documentId}`">
            <span><AppIcon name="file" :size="17" />工程开发指南</span>
            <AppIcon name="arrow" :size="16" />
          </a>
          <p class="project-workspace-hint">运行方式、配置说明与建议验证步骤，以仓库文档为准。</p>
        </section>
      </aside>
    </div>
  </section>
</template>
