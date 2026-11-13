<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { createHttpClient, HttpClientError } from '@learn/http'
import './order-workbench.css'

interface Product { id: number; name: string; priceCents: number; available: number; reserved: number; sold: number }
interface Order { id: string; buyerId: string; requestKey: string; productId: number; quantity: number; amountCents: number; status: string; expiresAt: string }
interface Event { id: string; orderId: string; status: string; attempts: number; lastError: string | null }
interface Operations { events: Event[]; accounts: { buyerId: string; points: number }[]; eventCounts: { status: string; total: number }[]; labEnabled: boolean }
interface History { action: string; traceId: string | null; threadName: string; createdAt: string }
interface LabResult { requestKey: string; outcome: string; orderId: string | null; thread: string }
const client = createHttpClient({ baseURL: '/order-api', timeoutMs: 12000 })
const products = ref<Product[]>([])
const orders = ref<Order[]>([])
const operations = ref<Operations>({ events: [], accounts: [], eventCounts: [], labEnabled: false })
const history = ref<History[]>([])
const results = ref<LabResult[]>([])
const selected = ref('')
const buyer = ref('learner-001')
const productId = ref(1)
const quantity = ref(1)
const requestKey = ref(crypto.randomUUID())
const busy = ref(false)
const message = ref('')
const error = ref('')
const checkedAt = ref('')
const count = ref(12)
const sameKey = ref(true)

function money(cents: number) { return (cents / 100).toFixed(2) }
function status(value: string) {
  const labels: Record<string, string> = { PENDING: '待处理', PAID: '已支付', CANCELLED: '已取消', SENDING: '发送中', SENT: 'Broker 已确认', FAILED: '待人工处理' }
  return labels[value] || value
}
function explain(failure: unknown) {
  if (failure instanceof HttpClientError) return failure.message + (failure.traceId ? ' · 请求标识 ' + failure.traceId : '')
  return failure instanceof Error ? failure.message : '请求失败'
}
async function refresh() {
  const snapshot = await Promise.all([
    client.getJson<Product[]>('/api/orders/products'),
    client.getJson<Order[]>('/api/orders'),
    client.getJson<Operations>('/api/orders/operations'),
  ])
  products.value = snapshot[0]
  orders.value = snapshot[1]
  operations.value = snapshot[2]
  checkedAt.value = new Date().toLocaleTimeString()
}
async function execute(action: () => Promise<void>) {
  if (busy.value) return
  busy.value = true
  message.value = ''
  error.value = ''
  try { await action() } catch (failure) { error.value = explain(failure) }
  finally { busy.value = false }
}
async function reload() { await execute(refresh) }
async function createOrder() {
  await execute(async () => {
    const order = await client.getJson<Order>('/api/orders', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ buyerId: buyer.value, productId: productId.value, quantity: quantity.value, requestKey: requestKey.value }),
    })
    message.value = '已确认订单 ' + order.id + '；原幂等键已保留，可再次提交观察去重。'
    await refresh()
  })
}
async function recoverOrder() {
  await execute(async () => {
    const query = new URLSearchParams({ buyer: buyer.value, key: requestKey.value })
    const order = await client.getJson<Order>('/api/orders/request?' + query)
    message.value = '此请求已提交：' + order.id + ' · ' + status(order.status)
    await refresh()
  })
}
async function transition(order: Order, payment: boolean) {
  await execute(async () => {
    await client.getJson<Order>('/api/orders/' + order.id + (payment ? '/simulate-payment' : '/cancel'), { method: 'POST' })
    message.value = payment ? '模拟支付已提交，积分由 MQ 异步处理，请稍后刷新。' : '取消已提交，库存已释放。'
    await refresh()
  })
}
async function showHistory(id: string) {
  await execute(async () => {
    history.value = await client.getJson<History[]>('/api/orders/' + id + '/history')
    selected.value = id
  })
}
async function replay(id: string) {
  await execute(async () => {
    await client.getJson('/api/orders/events/' + id + '/replay', { method: 'POST' })
    message.value = '已记录重放请求，仍使用原事件 ID。'
    await refresh()
  })
}
async function runBatch() {
  await execute(async () => {
    results.value = await client.getJson<LabResult[]>('/api/orders/lab/concurrent', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, timeoutMs: 25000,
      body: JSON.stringify({ buyerId: buyer.value, productId: productId.value, count: count.value, sameRequestKey: sameKey.value }),
    })
    await refresh()
  })
}
function newIntent() { requestKey.value = crypto.randomUUID(); message.value = '已建立新的下单意图。' }
onMounted(reload)
</script>

<template>
  <section class="order-workbench" aria-labelledby="order-title">
    <header class="order-header">
      <div><p class="order-eyebrow">ORDER LAB · 订单可靠性实验室</p><h1 id="order-title">从一笔订单，读懂整条链路</h1>
        <p>预留库存 → 模拟支付 → Outbox → RabbitMQ → 幂等积分</p></div>
      <div class="order-actions"><a href="#docs/order-learning">问答学习总结</a><a href="#docs/order-lab">开发手册</a><button :disabled="busy" @click="reload">刷新数据</button></div>
    </header>
    <p class="order-notice">本地学习环境，无真实扣款。默认 15 分钟未支付自动取消。{{ checkedAt ? '数据读取于 ' + checkedAt + '，不是实时快照。' : '等待读取后端数据。' }}</p>
    <p v-if="error" class="order-error" role="alert">{{ error }}。写请求超时可能已提交，请保留原幂等键核对。</p>
    <p v-if="message" class="order-success" role="status">{{ message }}</p>

    <div class="order-top-grid">
      <section class="order-panel">
        <h2>商品与库存</h2><p>可售 / 预留 / 已售，均来自 MySQL。</p>
        <div v-for="product in products" :key="product.id" class="order-product">
          <div><strong>{{ product.name }}</strong><span>¥{{ money(product.priceCents) }}</span></div>
          <dl><div><dt>可售</dt><dd>{{ product.available }}</dd></div><div><dt>预留</dt><dd>{{ product.reserved }}</dd></div><div><dt>已售</dt><dd>{{ product.sold }}</dd></div></dl>
        </div>
        <p v-if="!products.length">暂无数据，请按手册启动本工程后端与中间件。</p>
      </section>
      <section class="order-panel">
        <h2>提交订单</h2>
        <form class="order-form" @submit.prevent="createOrder">
          <label>模拟买家标识<input v-model="buyer" required pattern="[A-Za-z0-9_-]{1,64}" :disabled="busy" /></label>
          <div class="order-form-row"><label>商品<select v-model="productId" :disabled="busy"><option v-for="product in products" :key="product.id" :value="product.id">{{ product.name }}</option></select></label>
            <label>数量<input v-model.number="quantity" type="number" min="1" max="10" required :disabled="busy" /></label></div>
          <label>本次幂等键<input :value="requestKey" readonly /></label>
          <p>网络重试保留原键；更换商品或数量前，先点击“新建下单意图”。</p>
          <div class="order-actions"><button type="submit" :disabled="busy || !products.length">提交 / 重试本单</button><button type="button" :disabled="busy" @click="recoverOrder">按键核对结果</button><button type="button" :disabled="busy" @click="newIntent">新建下单意图</button></div>
        </form>
      </section>
    </div>

    <section class="order-panel">
      <h2>最近 50 笔订单</h2><p>支付与取消只能有一个成功迁移状态；重复操作不会重复扣库存。</p>
      <div class="order-table-wrap"><table><thead><tr><th>订单 / 幂等键</th><th>买家</th><th>数量 / 金额</th><th>状态</th><th>操作</th></tr></thead>
        <tbody><tr v-for="order in orders" :key="order.id">
          <td><code>{{ order.id }}</code><small>{{ order.requestKey }}</small></td><td>{{ order.buyerId }}</td>
          <td>{{ order.quantity }} 件 / ¥{{ money(order.amountCents) }}</td><td><span class="order-badge" :data-state="order.status">{{ status(order.status) }}</span></td>
          <td><div class="order-actions"><button :disabled="busy || order.status !== 'PENDING' || !operations.labEnabled" @click="transition(order, true)">模拟支付</button><button :disabled="busy || order.status !== 'PENDING'" @click="transition(order, false)">取消</button><button :disabled="busy" @click="showHistory(order.id)">链路记录</button></div></td>
        </tr></tbody></table></div>
      <p v-if="!orders.length">尚未查询到订单。</p>
      <div v-if="selected" class="order-history"><h3>链路记录 · {{ selected }}</h3>
        <ol><li v-for="(entry, index) in history" :key="index"><strong>{{ entry.action }}</strong> · {{ entry.threadName }}<small>{{ entry.createdAt }} UTC · trace {{ entry.traceId || '后台任务' }}</small></li></ol>
      </div>
    </section>

    <div class="order-bottom-grid">
      <section class="order-panel">
        <h2>消息投递 · 最近 50 条</h2><p>SENT 仅代表 Broker 确认；是否发放积分，请查看积分和链路记录。</p>
        <div class="order-actions"><span v-for="item in operations.eventCounts" :key="item.status">{{ status(item.status) }}：{{ item.total }}</span></div>
        <article v-for="event in operations.events" :key="event.id" class="order-event">
          <code>{{ event.id }}</code><small>订单 {{ event.orderId }}</small>
          <div class="order-actions"><span>{{ status(event.status) }} · {{ event.attempts }} 次</span><button :disabled="busy || !operations.labEnabled || !['SENT', 'FAILED'].includes(event.status)" @click="replay(event.id)">重放原事件</button></div>
          <p v-if="event.lastError">{{ event.lastError }}</p>
        </article>
        <p v-if="!operations.events.length">支付后在同一事务中生成事件。</p>
      </section>
      <section class="order-panel"><h2>积分账户 · 前 50 个</h2><p>每支付 1 元奖励 1 分，最低 1 分；同订单只发放一次。</p>
        <div v-for="account in operations.accounts" :key="account.buyerId" class="order-account"><span>{{ account.buyerId }}</span><strong>{{ account.points }} 分</strong></div>
        <p v-if="!operations.accounts.length">等待支付事件被消费者处理。</p>
      </section>
    </div>

    <section class="order-panel">
      <h2>并发下单实验</h2><p>真实写入学习库，使用独立有界线程池。拒绝表示未入队，UNKNOWN 表示需要查询结果；实验结束后可取消未支付订单。</p>
      <p v-if="!operations.labEnabled">默认关闭。在后端环境中设置 ORDER_LAB_ENABLED=true 后可使用模拟支付、并发实验与事件重放。</p>
      <form v-else class="order-lab-form" @submit.prevent="runBatch"><label>请求数<input v-model.number="count" type="number" min="1" max="30" required :disabled="busy" /></label>
        <label class="order-checkbox"><input v-model="sameKey" type="checkbox" :disabled="busy" />复用同一个幂等键</label><button :disabled="busy || !products.length">执行并发下单</button></form>
      <div v-if="results.length" class="order-table-wrap"><table><thead><tr><th>请求键</th><th>结果</th><th>订单</th><th>执行线程</th></tr></thead><tbody><tr v-for="(result, index) in results" :key="index"><td>{{ result.requestKey }}</td><td>{{ result.outcome }}</td><td>{{ result.orderId || '—' }}</td><td>{{ result.thread }}</td></tr></tbody></table></div>
    </section>
  </section>
</template>
