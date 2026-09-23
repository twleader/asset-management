import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { nextTick, reactive, watchEffect } from 'vue'
import { createDashboardPanelLoader } from './dashboardPanelLoader.js'

function deferred() {
  let resolve
  let reject
  const promise = new Promise((ok, fail) => { resolve = ok; reject = fail })
  return { promise, resolve, reject }
}

test('七個面板同步起跑且各自完成就提交', async () => {
  const pending = new Map()
  const loader = createDashboardPanelLoader({
    panelNames: ['kpis', 'allocation', 'trend', 'deposits', 'stock-values', 'holdings', 'funds'],
    request(panel) { const d = deferred(); pending.set(panel, d); return d.promise }
  })
  const all = loader.select(10, { allocation: { tab: 'category' } })
  assert.deepEqual([...pending.keys()].sort(), ['allocation', 'deposits', 'funds', 'holdings', 'kpis', 'stock-values', 'trend'])
  pending.get('trend').resolve({ panel: 'trend', data: { history: [{ id: 1 }] } })
  await Promise.resolve()
  assert.equal(loader.states.trend.data.panel, 'trend')
  assert.equal(loader.states.kpis.data, null)
  for (const [panel, d] of pending) if (panel !== 'trend') d.resolve({ panel, data: { snapshot: { id: 10 } } })
  await all
})

test('單一面板失敗不污染其他面板，retry 只重發該面板', async () => {
  const calls = []
  const pending = []
  const loader = createDashboardPanelLoader({
    panelNames: ['kpis', 'trend'],
    request(panel) { const d = deferred(); calls.push(panel); pending.push(d); return d.promise }
  })
  const initial = loader.select(1)
  pending[0].reject(new Error('kpi failed'))
  pending[1].resolve({ panel: 'trend', data: { history: [{ id: 1 }] } })
  await initial
  assert.match(loader.states.kpis.error.message, /kpi failed/)
  assert.equal(loader.states.trend.data.panel, 'trend')
  const retry = loader.retry('kpis')
  assert.deepEqual(calls, ['kpis', 'trend', 'kpis'])
  pending[2].resolve({ panel: 'kpis', data: { snapshot: { id: 1 } } })
  await retry
  assert.equal(loader.states.kpis.error, null)
})

test('A 切 B 後，A 的晚到 success、error、finally 都不覆寫 B', async () => {
  const pending = []
  const loader = createDashboardPanelLoader({
    panelNames: ['kpis'],
    request(_panel, request) { const d = deferred(); pending.push({ ...d, request }); return d.promise }
  })
  const a = loader.select(1)
  const b = loader.select(2)
  pending[1].resolve({ panel: 'kpis', snapshotId: 2, data: { snapshot: { id: 2 } } })
  await b
  pending[0].reject(new Error('A late'))
  await a
  assert.equal(loader.states.kpis.data.snapshotId, 2)
  assert.equal(loader.states.kpis.error, null)
  assert.equal(loader.states.kpis.loading, false)
})

test('dispose 取消請求，empty 與 error 維持不同狀態', async () => {
  const pending = []
  const loader = createDashboardPanelLoader({
    panelNames: ['funds'],
    request(_panel, request) { const d = deferred(); pending.push({ ...d, request }); return d.promise },
    isEmpty: (_panel, payload) => Array.isArray(payload?.data?.snapshot?.funds) && payload.data.snapshot.funds.length === 0
  })
  const empty = loader.select(1)
  pending[0].resolve({ panel: 'funds', data: { snapshot: { funds: [] } } })
  await empty
  assert.equal(loader.states.funds.empty, true)
  const active = loader.retry('funds')
  loader.dispose()
  assert.equal(pending[1].request.signal.aborted, true)
  pending[1].reject(Object.assign(new Error('cancelled'), { code: 'ERR_CANCELED' }))
  await active
  assert.equal(loader.states.funds.error, null)
})

test('Vue reactive state 會在 panel 完成時通知畫面', async () => {
  const pending = deferred()
  const loader = createDashboardPanelLoader({
    panelNames: ['trend'],
    stateFactory: reactive,
    request: () => pending.promise
  })
  let rendered = 'loading'
  watchEffect(() => { rendered = loader.states.trend.data?.panel ?? 'loading' })
  const run = loader.select(1)
  pending.resolve({ panel: 'trend', data: { history: [{ id: 1 }] } })
  await run
  await nextTick()
  assert.equal(rendered, 'trend')
})

test('同一快照背景更新與重試失敗時保留完整資料，切換 tab 後不保留舊圖', async () => {
  let fail = false
  const data = { panel: 'allocation', data: { snapshot: { id: 1 } } }
  const loader = createDashboardPanelLoader({
    panelNames: ['allocation'],
    request: async () => { if (fail) throw new Error('offline'); return data }
  })
  await loader.select(1, { allocation: { tab: 'category' } })
  fail = true
  await loader.select(1, { allocation: { tab: 'category' } })
  assert.equal(loader.states.allocation.data, data)
  assert.match(loader.states.allocation.refreshError.message, /offline/)
  await loader.retry('allocation')
  assert.equal(loader.states.allocation.data, data)
  await loader.run('allocation', { context: { tab: 'assetClass' } })
  assert.equal(loader.states.allocation.data, null)
  assert.match(loader.states.allocation.error.message, /offline/)
})

test('背景即時資料只能更新同一世代已完成的 panel，切換與 dispose 後不得寫入', async () => {
  const loader = createDashboardPanelLoader({
    panelNames: ['holdings'],
    request: async (_panel, { snapshotId }) => ({ snapshotId, data: { liveAssets: { value: 1 }, marketStatus: { twMarketOpen: false } } })
  })
  await loader.select(1)
  const generation = loader.states.holdings.generation
  assert.equal(loader.update('holdings', generation, payload => ({ ...payload, data: {
    liveAssets: { value: 2 }, marketStatus: { twMarketOpen: true }
  } })), true)
  assert.equal(loader.states.holdings.data.data.liveAssets.value, 2)
  assert.equal(loader.states.holdings.data.data.marketStatus.twMarketOpen, true)
  await loader.select(2)
  assert.equal(loader.update('holdings', generation, () => { throw new Error('stale write') }), false)
  const current = loader.states.holdings.generation
  loader.dispose()
  assert.equal(loader.update('holdings', current, () => { throw new Error('disposed write') }), false)
})

test('A 的晚到成功不會覆寫已完成的 B 或離頁後資料', async () => {
  const pending = []
  const loader = createDashboardPanelLoader({
    panelNames: ['allocation'],
    request() { const d = deferred(); pending.push(d); return d.promise }
  })
  const a = loader.select(1)
  const b = loader.select(2)
  pending[1].resolve({ snapshotId: 2 })
  await b
  pending[0].resolve({ snapshotId: 1 })
  await a
  assert.equal(loader.states.allocation.data.snapshotId, 2)
  const c = loader.select(3)
  loader.dispose()
  pending[2].resolve({ snapshotId: 3 })
  await c
  assert.equal(loader.states.allocation.data, null)
})

test('所有 Dashboard ECharts option 都關閉初次與更新動畫', async () => {
  const source = await readFile(new URL('../views/DashboardView.vue', import.meta.url), 'utf8')
  for (const option of ['pieOption', 'assetClassPieOption', 'twStockPieOption', 'usStockPieOption', 'trendOption', 'bankOption', 'fundBarOption', 'stockBarOption']) {
    const start = source.indexOf(`const ${option} = computed`)
    assert.notEqual(start, -1, `${option} 必須存在`)
    const section = source.slice(start, source.indexOf('\nconst ', start + 1))
    assert.match(section, /animation:\s*false/, `${option} 必須停用 ECharts 動畫`)
  }
})
