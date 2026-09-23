import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parse, compileScript, compileTemplate } from '@vue/compiler-sfc'
import { nextTick, reactive, ref, computed } from 'vue'
import { applyTradingRadarSsePriceUpdate } from './tradingRadarSsePriceUpdate.js'
import { marketToday } from './displayQuote.js'
import { applyStockEvaluationTuple, createTradingRadarPanelLoader, TRADING_RADAR_PANELS } from './tradingRadarPanelLoader.js'
import { createTradingRadarRefreshJob } from './tradingRadarRefreshJob.js'

const deferred = () => { let resolve, reject; const promise = new Promise((ok, fail) => { resolve = ok; reject = fail }); return { promise, resolve, reject } }
const market = () => ({ score: 50, regime: 'NEUTRAL', regimeLabel: '中性' })
const stock = (marketName = '台股', code = '2330', value = 71) => ({
  market: marketName, stockCode: code, stockName: '測試', shortAction: 'HOLD', shortActionLabel: '續抱', shortScore: value - 2,
  swingAction: 'HOLD', swingActionLabel: '續抱', swingScore: value - 1, action: 'HOLD', actionLabel: '續抱', score: value,
  price: 100, changePercent: 1, quoteStatus: 'LIVE', priceUpdatedAt: '2026-09-23T10:00:00+08:00'
})
function envelope(panel, rows = [stock(panel === 'us-stocks' ? '美股' : '台股')]) {
  const data = panel.endsWith('market') ? { market: market() }
    : panel.endsWith('stocks') ? { market: market(), stocks: rows, skippedNonTwStocks: 0 }
      : { publicInformation: [] }
  return { panel, ruleVersion: 'TW_RULES_V20', actionPolicyVersion: 'EVIDENCE_GATE_V1', generatedAt: '2026-09-23T10:00:00+08:00', data }
}

test('五路同步起跑；慢區與失敗區不阻擋已完成區，retry 只請求該區', async () => {
  const calls = [], pending = new Map()
  const loader = createTradingRadarPanelLoader({ stateFactory: reactive, request(panel) { calls.push(panel); const d = deferred(); pending.set(panel, d); return d.promise } })
  const all = loader.loadAll()
  assert.deepEqual([...calls].sort(), [...TRADING_RADAR_PANELS].sort())
  pending.get('tw-market').resolve(envelope('tw-market'))
  pending.get('us-market').resolve(envelope('us-market'))
  pending.get('tw-stocks').resolve(envelope('tw-stocks'))
  pending.get('public-information').reject(Error('news unavailable'))
  await Promise.resolve(); await nextTick()
  assert.equal(loader.states['tw-stocks'].data.data.stocks[0].stockCode, '2330')
  assert.match(loader.states['public-information'].error.message, /news unavailable/)
  pending.get('us-stocks').resolve(envelope('us-stocks'))
  await all
  const retry = loader.load('public-information', { preserve: true })
  assert.deepEqual(calls.slice(-1), ['public-information'])
  pending.get('public-information').resolve(envelope('public-information'))
  await retry
  assert.equal(loader.states['tw-stocks'].data.data.stocks[0].stockCode, '2330')
  assert.equal(loader.states['public-information'].empty, true)
})

test('generation、abort 和 finally 都不能讓舊 panel 污染新 retry', async () => {
  const pending = []
  const loader = createTradingRadarPanelLoader({ request(_panel, { signal }) { const d = deferred(); pending.push({ signal, ...d }); return d.promise } })
  const first = loader.load('tw-stocks')
  const second = loader.load('tw-stocks')
  assert.equal(pending[0].signal.aborted, true)
  pending[1].resolve(envelope('tw-stocks', [stock('台股', '2330', 88)]))
  await second
  pending[0].resolve(envelope('tw-stocks', [stock('台股', '2330', 1)]))
  await first
  assert.equal(loader.states['tw-stocks'].data.data.stocks[0].score, 88)
  assert.equal(loader.states['tw-stocks'].loading, false)
  const disposed = loader.load('us-stocks')
  loader.dispose(); pending[2].resolve(envelope('us-stocks'))
  await disposed
  assert.equal(loader.states['us-stocks'].data, null)
})

test('single stock tuple 一次驗證後才更新 compact summary；拒絕不一致並保留較新 SSE', () => {
  const current = stock('台股', '2330', 70)
  current.price = 110; current.priceUpdatedAt = '2026-09-23T10:05:00+08:00'
  const summary = stock('台股', '2330', 81)
  summary.price = 99
  const full = { ...summary, evidence: { full: true } }
  const tuple = { ruleVersion: 'TW_RULES_V20', actionPolicyVersion: 'EVIDENCE_GATE_V1', generatedAt: '2026-09-23T10:01:00+08:00', market: market(), summary, stock: full }
  const applied = applyStockEvaluationTuple([current, stock('台股', '2308')], '台股', '2330', tuple)
  assert.equal(applied.rows[0].score, 81)
  assert.equal(applied.rows[0].price, 110)
  assert.equal(applied.detail.stock.price, 110)
  assert.equal('evidence' in applied.rows[0], false)
  assert.equal(applied.detail.stock.evidence.full, true)
  assert.throws(() => applyStockEvaluationTuple([current], '台股', '2330', { ...tuple, stock: { ...full, swingScore: 0 } }), /不一致/)
  assert.equal(current.score, 70)
})

test('TradingRadarView 是可編譯 SFC，且使用五路 panel、tuple endpoint 和 dispose fence', () => {
  const source = readFileSync(new URL('../views/TradingRadarView.vue', import.meta.url), 'utf8')
  const descriptor = parse(source).descriptor
  const script = compileScript(descriptor, { id: 'trading-radar-panel-test' })
  const template = compileTemplate({ source: descriptor.template.content, filename: 'TradingRadarView.vue', id: 'trading-radar-panel-test', compilerOptions: { bindingMetadata: script.bindings } })
  assert.deepEqual(template.errors, [])
  for (const fragment of ['loadPanels()', 'stockEvaluation(row.market, row.stockCode', 'applyStockEvaluationTuple', 'panelLoader.dispose()', 'refreshJob.dispose()', 'cancelMarketDetails(marketName)']) {
    assert.ok(source.includes(fragment), `missing lifecycle fragment: ${fragment}`)
  }
})

function viewHarness() {
  const source = readFileSync(new URL('../views/TradingRadarView.vue', import.meta.url), 'utf8')
  const script = compileScript(parse(source).descriptor, { id: 'trading-radar-lifecycle-test' })
  const compiled = script.content.replace(/import[\s\S]*?from [^\n]+\n/g, '').replace('export default', 'const component =')
  const calls = [], mounted = [], unmounted = [], streams = [], pending = new Map(), details = []
  const panelApi = Object.fromEntries(TRADING_RADAR_PANELS.map(panel => [
    ({ 'tw-market': 'twMarketPanel', 'us-market': 'usMarketPanel', 'tw-stocks': 'twStocksPanel', 'us-stocks': 'usStocksPanel', 'public-information': 'publicInformationPanel' })[panel],
    ({ signal }) => { calls.push(panel); const d = deferred(); pending.set(panel, { ...d, signal }); return d.promise }
  ]))
  class EventSource {
    static CLOSED = 2
    constructor(url) { this.url = url; this.listeners = {}; streams.push(this) }
    addEventListener(name, callback) { this.listeners[name] = callback }
    close() { this.closed = true }
  }
  const bffApi = { tradingRadar: {
    ...panelApi, list: () => { throw Error('legacy list must stay unused') },
    stockEvaluation: (market, stockCode, { signal }) => { const d = deferred(); details.push({ ...d, market, stockCode, signal }); return d.promise },
    getExportTimes: async () => [], getExportSetting: async () => ({}), getBlogStatus: async () => ({}),
    startRefreshJob: async () => ({ jobId: 'id' }), getRefreshJob: async () => ({ status: 'FAILED' })
  } }
  const noop = () => {}
  const deps = {
    ref, reactive, computed, EventSource,
    onMounted: hook => mounted.push(hook), onUnmounted: hook => unmounted.push(hook),
    useRouter: () => ({ push: noop, replace: noop }), useRoute: () => ({ query: {} }), useAuthStore: () => ({ isConfiguredAdmin: false }),
    bffApi, cloneExportSetting: value => ({ ...value }), replaceExportSetting: Object.assign, showGdriveSelfCheckWarning: noop,
    showDualExportResult: noop, isClosePending: () => false, applyTradingRadarSsePriceUpdate,
    applyStockEvaluationTuple, createTradingRadarPanelLoader, createTradingRadarRefreshJob,
    projectValuationEvidence: () => [], HOLDING_PERIOD_OPTIONS: [], HOLDING_PERIOD_PROMPT: '', HOLDING_PERIOD_STATES: { NONE: null },
    completedCandleDisclosure: () => '', presentAllHorizonDecisions: () => [], presentHorizonDecision: () => null,
    Bell: {}, Download: {}, Plus: {}, Promotion: {}, Refresh: {}, TaiwanMap: {}, UsFlag: {}, StockAnalysisDialog: {},
    ElMessage: Object.assign(noop, { warning: noop, success: noop, error: noop }), ElMessageBox: { confirm: async () => {} },
    dayjs: () => ({ startOf: () => ({ format: () => '' }), format: () => '' })
  }
  const component = new Function(...Object.keys(deps), compiled + '\nreturn component')(...Object.values(deps))
  const view = component.setup({}, { expose: noop })
  return { view, calls, pending, details, streams, mount: () => mounted.forEach(hook => hook()), unmount: () => unmounted.forEach(hook => hook()) }
}
const flush = async () => { for (let i = 0; i < 8; i++) await Promise.resolve(); await nextTick() }
function evaluation(marketName = '台股', code = '2330', score = 88) {
  const summary = { ...stock(marketName, code, score), fundamental: { applicable: true, coverage: 3 }, weeklyIndicators: { k: 50 } }
  return { ruleVersion: 'TW_RULES_V20', actionPolicyVersion: 'EVIDENCE_GATE_V1', generatedAt: '2026-09-23T10:01:00+08:00', market: market(), summary,
    stock: { ...summary, fundamental: { ...summary.fundamental, reasons: ['完整證據'] }, weeklyIndicators: { k: 50, ma20: 123 }, evidence: { complete: true } } }
}
async function loadedView() {
  const h = viewHarness(); h.mount()
  for (const panel of TRADING_RADAR_PANELS) h.pending.get(panel).resolve(envelope(panel))
  await flush()
  return h
}

test('真實編譯 SFC mount 立即開 SSE 與五個 Panel，部分完成即可顯示；卸載取消剩餘請求', async () => {
  const h = viewHarness(); h.mount()
  assert.deepEqual(h.calls, [...TRADING_RADAR_PANELS])
  assert.equal(h.streams.length, 1)
  h.pending.get('tw-market').resolve(envelope('tw-market')); await flush()
  assert.ok(h.view.currentMarketPanel.value)
  assert.equal(h.view.currentStocksPanel.value, null)
  h.unmount()
  assert.equal(h.streams[0].closed, true)
  for (const panel of TRADING_RADAR_PANELS.slice(1)) {
    assert.equal(h.pending.get(panel).signal.aborted, true)
    h.pending.get(panel).resolve(envelope(panel))
  }
  await flush()
  assert.equal(h.view.currentStocks.value.length, 0)
  assert.equal(h.streams.length, 1)
})

test('SFC 展開原子更新，完整明細不被 compact 欄位遮住；重複 callback 不重入，收合重開才再算', async () => {
  const h = await loadedView(), v = h.view
  let row = v.twStocks.value[0]
  v.onRowExpand(row, [row]); v.onRowExpand(row, [row])
  assert.equal(h.details.length, 1)
  assert.equal(v.twStocks.value[0].score, 71)
  assert.equal(v.hasDetail(row), false)
  h.details[0].resolve(evaluation()); await flush()
  row = v.twStocks.value[0]
  assert.equal(row.score, 88)
  assert.equal('evidence' in row, false)
  assert.equal(row.fundamental.reasons, undefined)
  assert.deepEqual(v.detailPresentationRow(row).fundamental.reasons, ['完整證據'])
  assert.equal(v.detailPresentationRow(row).weeklyIndicators.ma20, 123)
  v.onRowExpand(row, [row]); assert.equal(h.details.length, 1)
  v.onRowExpand(row, []); v.onRowExpand(row, [row]); assert.equal(h.details.length, 2)
  h.details[1].resolve({ ...evaluation('台股', '2330', 42), stock: { ...evaluation().stock, score: 99 } }); await flush()
  assert.equal(v.twStocks.value[0].score, 88)
  assert.match(v.detailError(row), /不一致/)
  assert.equal(v.detailPresentationRow(row).score, 88)
  h.unmount()
})

test('SFC collapse 舊回應與 finally 不污染重開；Panel 替換只取消本市場，unmount 禁止 late commit', async () => {
  const h = await loadedView(), v = h.view
  const tw = v.twStocks.value[0], us = v.usStocks.value[0]
  v.onRowExpand(tw, [tw]); assert.deepEqual(v.expandedRowKeys.value, ['台股_2330']); v.onRowExpand(tw, [])
  assert.equal(h.details[0].signal.aborted, true)
  v.onRowExpand(tw, [tw]); h.details[0].resolve(evaluation('台股', '2330', 1)); await flush()
  assert.equal(v.isDetailLoading(tw), true)
  assert.equal(v.twStocks.value[0].score, 71)
  h.details[1].resolve(evaluation()); await flush()
  v.onRowExpand(us, [us]); h.details[2].resolve(evaluation('美股')); await flush()
  v.onRowExpand(tw, []); v.onRowExpand(tw, [tw])
  const refresh = v.retryPanel('tw-stocks'); h.pending.get('tw-stocks').resolve(envelope('tw-stocks', [stock('台股', '2330', 22)])); await refresh
  assert.equal(h.details[3].signal.aborted, true)
  assert.deepEqual(v.expandedRowKeys.value, [])
  assert.equal(v.hasDetail(us), true)
  h.details[3].resolve(evaluation('台股', '2330', 2)); await flush()
  assert.equal(v.twStocks.value[0].score, 22)
  v.onRowExpand(v.twStocks.value[0], [v.twStocks.value[0]])
  h.unmount(); h.details[4].resolve(evaluation('台股', '2330', 3)); await flush()
  assert.equal(v.twStocks.value[0].score, 22)
})

test('SFC SSE 同步可見明細和清單行情，延遲評估不倒退較新行情或改變分析時間', async () => {
  const h = await loadedView(), v = h.view
  const row = v.twStocks.value[0]
  v.onRowExpand(row, [row]); h.details[0].resolve(evaluation()); await flush()
  const generatedAt = v.twStocks.value[0].evaluationGeneratedAt
  const incoming = { market: '台股', stockCode: '2330', price: 123, changePercent: 2, quoteStatus: 'LIVE', tradingDate: marketToday('台股'), updatedAt: '2026-09-23T10:05:00+08:00', score: 1 }
  v.applyPriceUpdate(incoming)
  assert.equal(v.twStocks.value[0].price, 123)
  assert.equal(v.detailPresentationRow(v.twStocks.value[0]).price, 123)
  assert.equal(v.twStocks.value[0].score, 88)
  assert.equal(v.twStocks.value[0].evaluationGeneratedAt, generatedAt)
  v.onRowExpand(row, []); v.onRowExpand(row, [row]); h.details[1].resolve(evaluation('台股', '2330', 91)); await flush()
  assert.equal(v.twStocks.value[0].score, 91)
  assert.equal(v.twStocks.value[0].price, 123)
  assert.equal(v.detailPresentationRow(v.twStocks.value[0]).price, 123)
  assert.equal(h.calls.length, 5)
  h.unmount()
})


test('單股新評估的等待／已驗證收盤、缺時間或不同交易日不可被舊 LIVE 覆蓋', () => {
  const current = { ...stock(), price: 1200, priceUpdatedAt: '2026-09-23T13:29:59+08:00' }
  for (const [quoteStatus, priceUpdatedAt] of [
    ['CLOSE_PENDING', null], ['VERIFIED_CLOSE', null], ['LIVE', null], ['LIVE', '2026-09-22T10:00:00+08:00']
  ]) {
    const next = evaluation()
    Object.assign(next.summary, { quoteStatus, priceUpdatedAt, price: quoteStatus === 'CLOSE_PENDING' ? null : 1100 })
    Object.assign(next.stock, { quoteStatus, priceUpdatedAt, price: next.summary.price })
    const result = applyStockEvaluationTuple([current], '台股', '2330', next)
    assert.equal(result.rows[0].quoteStatus, quoteStatus)
    assert.equal(result.rows[0].price, next.summary.price)
    assert.equal(result.detail.stock.price, next.stock.price)
  }
})
