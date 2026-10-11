import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

import {
  addIsoDateDays,
  createAiFlowViewLoader,
  createAiFlowViewState,
  displayDurationMillis,
  displayUnknown,
  fallbackAiFlowEarliestDate,
  isDateOutsideWindow,
  mapAiFlowGraphData,
  todayTaipeiISO
} from './aiFlowView.js'

function deferred() {
  let resolve
  let reject
  const promise = new Promise((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

test('日期窗用台北日，90 天含今天；未知耗時保留為未記錄', () => {
  const now = new Date('2026-10-10T18:30:00.000Z')
  const today = todayTaipeiISO(now)
  assert.equal(today, '2026-10-11')
  assert.equal(fallbackAiFlowEarliestDate(today), '2026-07-14')
  assert.equal(addIsoDateDays('2026-10-11', -89), '2026-07-14')
  assert.equal(isDateOutsideWindow('2026-07-13', '2026-07-14', today), true)
  assert.equal(isDateOutsideWindow('2026-07-14', '2026-07-14', today), false)
  assert.equal(isDateOutsideWindow('2026-10-12', '2026-07-14', today), true)
  assert.equal(displayUnknown(null), '未記錄')
  assert.equal(displayUnknown(''), '未記錄')
  assert.equal(displayUnknown(0), '0')
  assert.equal(displayDurationMillis(null), '未記錄')
  assert.equal(displayDurationMillis(0), '0 分 0 秒 0 毫秒')
  assert.equal(displayDurationMillis(999), '0 分 0 秒 999 毫秒')
  assert.equal(displayDurationMillis(60000), '1 分 0 秒 0 毫秒')
  assert.equal(displayDurationMillis(128191), '2 分 8 秒 191 毫秒')
  assert.equal(displayDurationMillis(258301), '4 分 18 秒 301 毫秒')
})

test('時段選項保留全部並依協定順序呈現，US 會原樣送進專屬 BFF 查詢', async () => {
  const view = readFileSync(new URL('../views/AiFlowView.vue', import.meta.url), 'utf8')
  const periodSelect = view.match(/<el-select v-model="period"[\s\S]*?<\/el-select>/)?.[0]
  assert.ok(periodSelect)
  const options = [...periodSelect.matchAll(/<el-option label="([^"]+)" value="([^"]+)"\s*\/>/g)]
    .map(([, label, value]) => ({ label, value }))
  assert.deepEqual(options, [
    { label: '全部時段', value: 'ALL' },
    { label: '台股早盤', value: 'MORNING' },
    { label: '台股午盤', value: 'NOON' },
    { label: '美股', value: 'US' }
  ])
  assert.match(view, /const period = ref\('ALL'\)/)
  assert.match(view, /bffApi\.aiFlow\.runs\(\{ date, consumer: selectedConsumer, period: selectedPeriod, signal \}\)/)

  const requests = []
  const loader = createAiFlowViewLoader({
    listRuns: async request => {
      requests.push(request)
      return { runs: [{ runId: 'us-run' }] }
    },
    getRun: async () => ({})
  })
  const state = createAiFlowViewState()
  await loader.query(state, { date: '2026-10-08', consumer: 'Codex', period: 'US' })
  assert.equal(requests[0].period, 'US')
  assert.deepEqual(state.runs.map(run => run.runId), ['us-run'])
})

test('filter generation 清空舊資料並拒絕過期清單回應', async () => {
  const pending = []
  const loader = createAiFlowViewLoader({
    listRuns: request => {
      const item = deferred()
      pending.push({ ...item, request })
      return item.promise
    },
    getRun: async () => ({})
  })
  const state = createAiFlowViewState({ date: '2026-10-08' })
  state.runs = [{ runId: 'old' }]
  state.detail = { run: { runId: 'old' } }
  state.selectedRunId = 'old'

  const first = loader.query(state, { date: '2026-10-08', consumer: 'Codex', period: 'ALL' })
  assert.deepEqual(state.runs, [])
  assert.equal(state.detail, null)
  assert.equal(state.selectedRunId, '')
  const firstSignal = pending[0].request.signal

  const second = loader.query(state, { date: '2026-10-09', consumer: 'Claude', period: 'US' })
  assert.equal(firstSignal.aborted, true)
  assert.equal(pending[1].request.period, 'US')
  assert.deepEqual(state.runs, [])
  assert.equal(state.detail, null)

  pending[0].resolve({ date: '2026-10-08', earliestDate: '2026-07-12', runs: [{ runId: 'stale' }] })
  await first
  assert.deepEqual(state.runs, [])
  assert.equal(state.listLoading, true)

  pending[1].resolve({ date: '2026-10-09', earliestDate: '2026-07-13', runs: [{ runId: 'current' }] })
  await second
  assert.deepEqual(state.runs.map(run => run.runId), ['current'])
  assert.equal(state.earliestDate, '2026-07-13')
  assert.equal(state.listLoading, false)
})

test('查詢錯誤不保留上一筆資料，明細舊回應不能跨 filter 回填', async () => {
  const detailRequest = deferred()
  let listCalls = 0
  let detailArgs
  const loader = createAiFlowViewLoader({
    listRuns: async () => {
      listCalls += 1
      if (listCalls === 1) return { runs: [{ runId: 'run-1' }] }
      if (listCalls === 2) return { runs: [{ runId: 'run-2' }] }
      throw new Error('讀取失敗')
    },
    getRun: (runId, options) => {
      detailArgs = { runId, ...options }
      return detailRequest.promise
    },
    errorMessage: error => '查詢錯誤：' + error.message
  })
  const state = createAiFlowViewState()
  await loader.query(state, { date: '2026-10-08', consumer: 'Codex', period: 'ALL' })
  const detailPromise = loader.loadDetail(state, state.runs[0], state.filters)
  assert.equal(detailArgs.runId, 'run-1')
  assert.equal(detailArgs.date, '2026-10-08')
  assert.equal(detailArgs.consumer, 'Codex')
  await loader.query(state, { date: '2026-10-09', consumer: 'Claude', period: 'MORNING' })
  detailRequest.resolve({ run: { runId: 'run-1' }, nodes: [], edges: [], slowSteps: [] })
  await detailPromise
  assert.equal(state.detail, null)
  assert.equal(state.selectedRunId, '')
  assert.deepEqual(state.runs.map(run => run.runId), ['run-2'])

  state.runs = [{ runId: 'stale-error' }]
  const succeeded = await loader.query(state, { date: '2026-10-10', consumer: 'Codex', period: 'ALL' })
  assert.equal(succeeded, false)
  assert.deepEqual(state.runs, [])
  assert.equal(state.listError, '查詢錯誤：讀取失敗')
  assert.equal(state.listLoading, false)
})

test('ECharts 只映射 BFF layout、中文狀態、顏色與關係標籤', () => {
  const hostileText = '<img src=x onerror=alert(1)>'
  const detail = {
    nodes: [{
      eventId: 'event-1',
      label: hostileText,
      status: 'UNKNOWN',
      statusLabel: '未確認',
      categoryLabel: '工具',
      timeLabel: '09:05:03',
      durationMillis: null,
      x: 17,
      y: 29,
      color: '#123456',
      summary: '<script>不執行</script>'
    }],
    edges: [{ id: 'edge-1', source: 'event-1', target: 'event-2', relation: 'NEXT_OBSERVED', label: '接續紀錄', curveness: 0.25 }]
  }
  const mapped = mapAiFlowGraphData(detail)
  assert.equal(mapped.nodes[0].id, 'event-1')
  assert.equal(mapped.nodes[0].name, hostileText)
  assert.equal(mapped.nodes[0].x, 17)
  assert.equal(mapped.nodes[0].y, 29)
  assert.equal(mapped.nodes[0].itemStyle.color, '#123456')
  assert.equal(mapped.nodes[0].durationMillis, null)
  assert.match(mapped.nodes[0].tooltipText, /<img src=x onerror=alert\(1\)>/)
  assert.equal(mapped.edges[0].source, 'event-1')
  assert.equal(mapped.edges[0].target, 'event-2')
  assert.equal(mapped.edges[0].relation, 'NEXT_OBSERVED')
  assert.equal(mapped.edges[0].label, '接續紀錄')

  const view = readFileSync(new URL('../views/AiFlowView.vue', import.meta.url), 'utf8')
  assert.doesNotMatch(view, /v-html\s*=/)
  assert.match(view, /renderMode:\s*'richText'/)
  assert.match(view, /edgeSymbol:\s*\['none',\s*'arrow'\]/)
  assert.match(view, /slotLabel/)
  assert.match(view, /reportedOutcome/)
  assert.match(view, /verifiedReportOutcome/)
  assert.match(view, /traceCoverage/)
  assert.match(view, /state\.detail\.slowSteps/)
})

test('路由與選單限 ADMIN，view 經自己的 AI Flow BFF namespace', () => {
  const app = readFileSync(new URL('../App.vue', import.meta.url), 'utf8')
  const router = readFileSync(new URL('../router/index.js', import.meta.url), 'utf8')
  const api = readFileSync(new URL('../api/index.js', import.meta.url), 'utf8')
  const view = readFileSync(new URL('../views/AiFlowView.vue', import.meta.url), 'utf8')

  assert.match(app, /path: '\/ai-flow', title: 'AI 判斷流程'[^\n]*requiresAdmin: true/)
  assert.match(router, /path: '\/ai-flow'[\s\S]*?meta: \{ title: 'AI 判斷流程'[^}]*requiresAdmin: true/)
  assert.match(api, /aiFlow:\s*\{[\s\S]*?api\.get\('\/bff\/ai-flow\/runs'/)
  assert.match(api, /api\.get\('\/bff\/ai-flow\/runs\/'/)
  assert.match(view, /bffApi\.aiFlow\.runs/)
  assert.match(view, /bffApi\.aiFlow\.detail/)
  assert.doesNotMatch(view, /\/api\/(?:ai-flow|srpp)/)
})
