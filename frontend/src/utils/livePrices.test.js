import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import { parse, compileScript, compileTemplate } from 'vue/compiler-sfc'
import { reactive, watch } from 'vue'
import { createLivePriceStream, createTrailingRefresh, mergeLiveQuote, patchLiveRow } from './livePrices.js'
import { marketToday, applyEnrichedQuote, isClosePending } from './displayQuote.js'
import { normalizeIntradaySession } from './intradaySession.js'
import { dashboardLiveIdentityMatches, isDashboardLivePriceEligible, isDashboardSnapshotRollover } from './dashboardLivePriceEligibility.js'

const today = marketToday('台股')
const yesterday = new Date(Date.parse(`${today}T00:00:00Z`) - 86400000).toISOString().slice(0, 10)
const quote = (overrides = {}) => ({ market: '台股', stockCode: '2330', tradingDate: today,
  quoteStatus: 'LIVE', price: 110, priceChange: 10, changePercent: 10,
  updatedAt: `${today}T10:00:02`, source: 'FUBON', ...overrides })
const older = () => quote({ price: 100, updatedAt: `${today}T10:00:01` })
const flush = async () => { for (let i = 0; i < 12; i++) await Promise.resolve() }
function deferred() { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
class Timers {
  time = 0; id = 0; tasks = new Map()
  options = { now: () => this.time, setTimer: (fn, delay) => { const id = ++this.id; this.tasks.set(id, { fn, at: this.time + delay }); return id }, clearTimer: id => this.tasks.delete(id) }
  async tick(ms) {
    const target = this.time + ms
    for (;;) {
      const next = [...this.tasks].filter(([, task]) => task.at <= target).sort((a, b) => a[1].at - b[1].at)[0]
      if (!next) break
      this.time = next[1].at; this.tasks.delete(next[0]); next[1].fn(); await flush()
    }
    this.time = target; await flush()
  }
}
function fixture({ todayForMarket } = {}) {
  const sources = [], events = new Set()
  const document = { hidden: false, addEventListener: (_, fn) => events.add(fn), removeEventListener: (_, fn) => events.delete(fn) }
  const stream = createLivePriceStream({ document, maxQuotes: 3, ...(todayForMarket ? { marketToday: todayForMarket } : {}), connect() {
    const handlers = {}
    const source = { readyState: 0, closeCount: 0, addEventListener: (name, cb) => { handlers[name] = cb }, close() { this.closeCount++; this.readyState = 2 }, open() { this.readyState = 1; this.onopen?.() }, emit(value) { handlers['price-update']({ data: JSON.stringify(value) }) }, fail() { this.readyState = 2; this.onerror?.() } }
    sources.push(source); return source
  } })
  return { stream, sources, events, hide(hidden) { document.hidden = hidden; for (const fn of events) fn() } }
}

// Parse the real SFC script and execute its actual declarations, rather than a copied handler.
function page(file, names, globals = {}, extra = '') {
  const { descriptor } = parse(readFileSync(new URL(file, import.meta.url), 'utf8'))
  const source = descriptor.scriptSetup.content
  const ast = compileScript(descriptor, { id: 'live-price-fixture' }).scriptSetupAst
  const snippets = ast.filter(node => (node.type === 'FunctionDeclaration' && names.includes(node.id.name))
    || (node.type === 'VariableDeclaration' && node.declarations.some(d => names.includes(d.id.name)))).map(node => source.slice(node.start, node.end))
  assert.equal(snippets.length, names.length, `all real declarations found: ${file}`)
  const context = vm.createContext({ console, AbortController, patchLiveRow, mergeLiveQuote, marketToday, isClosePending,
    applyEnrichedQuote, normalizeIntradaySession, ...globals })
  vm.runInContext(`${extra}\n${snippets.join('\n')}`, context)
  return context
}

test('one shared authenticated source, new consumer catchup, reconnect, visibility and final cleanup', () => {
  const f = fixture(); let a = 0, b = 0, opened = 0, paused = 0
  const stopA = f.stream.subscribe({ onQuote: () => a++, onCatchup: () => opened++, onPause: () => paused++ })
  f.sources[0].open()
  const stopB = f.stream.subscribe({ onQuote: () => b++, onCatchup: () => opened++ })
  assert.equal(opened, 2); assert.equal(f.sources.length, 1)
  f.sources[0].emit(quote()); assert.equal(a, 1); assert.equal(b, 1)
  f.sources[0].open(); assert.equal(opened, 4)
  f.hide(true); assert.equal(paused, 1); assert.equal(f.sources[0].closeCount, 1)
  f.hide(false); assert.equal(f.sources.length, 2)
  stopA(); assert.equal(f.sources[1].closeCount, 0)
  stopB(); assert.equal(f.sources[1].closeCount, 1); assert.equal(f.events.size, 0); assert.equal(f.stream.quotes().length, 0)
})
test('permanent CLOSED never rebuilds automatically or on visibility/new active consumer', () => {
  const f = fixture(); const stop = f.stream.subscribe({}); f.sources[0].fail()
  f.hide(true); f.hide(false); const stop2 = f.stream.subscribe({})
  assert.equal(f.sources.length, 1); stop(); stop2()
})
test('closed statuses, market dates, identity, finite tuples, out-of-order SSE and HTTP overlay never regress', () => {
  const current = quote()
  for (const bad of [quote({ quoteStatus: '???' }), quote({ quoteStatus: null }), quote({ price: null }), quote({ price: 'nan' }), quote({ changePercent: 'NaN' }), quote({ price: 0 }), quote({ updatedAt: null }), quote({ tradingDate: yesterday }), older()]) {
    assert.equal(mergeLiveQuote(current, bad), current)
  }
  for (const status of ['CLOSE_PENDING', 'VERIFIED_CLOSE']) {
    const closed = { ...current, quoteStatus: status }; assert.equal(mergeLiveQuote(closed, quote()), closed)
    const noDate = { market: '台股', stockCode: '2330', quoteStatus: status }
    assert.equal(mergeLiveQuote(noDate, quote()), noDate)
    assert.equal(mergeLiveQuote({ ...closed, tradingDate: yesterday }, quote()).price, 110)
    const asOf = { ...closed, tradingDate: undefined, asOfDate: today }; assert.equal(mergeLiveQuote(asOf, quote()), asOf)
  }
  const f = fixture(); const stop = f.stream.subscribe({}); f.sources[0].emit(current)
  assert.equal(f.stream.overlay(older()).price, 110)
  const http = quote({ price: 120, updatedAt: `${today}T10:00:04` })
  f.stream.overlay(http)
  f.sources[0].emit(quote({ price: 115, updatedAt: `${today}T10:00:03` }))
  assert.equal(f.stream.overlay(older()).price, 120)
  for (let i = 0; i < 8; i++) f.sources[0].emit(quote({ stockCode: String(i) }))
  assert.equal(f.stream.quotes().length, 3); stop()
})
test('throttle does not starve under continuous ticks and final tick gets a bounded trailing read', async () => {
  const clock = new Timers(), reads = []
  const scheduler = createTrailingRefresh(() => reads.push(clock.time), { ...clock.options, delay: 300, interval: 1000, tail: true })
  for (let i = 0; i < 40; i++) { scheduler.request(); await clock.tick(50) }
  assert.ok(reads.length >= 2)
  await clock.tick(4000)
  assert.equal(clock.tasks.size, 0)
  for (let i = 1; i < reads.length; i++) assert.ok(reads[i] - reads[i - 1] >= 1000)
  assert.ok(reads.at(-1) > 2000)
  scheduler.dispose()
})
test('in-flight reads never overlap, pending survives, cancel invalidates trailing work', async () => {
  const clock = new Timers(), gate = deferred(); let reads = 0
  const scheduler = createTrailingRefresh(() => { reads++; return reads === 1 ? gate.promise : null }, { ...clock.options, delay: 300, interval: 1000, tail: true })
  scheduler.request(); await clock.tick(300); scheduler.request(); await clock.tick(4000); assert.equal(reads, 1)
  scheduler.cancel(); gate.resolve(); await flush(); assert.equal(clock.tasks.size, 0)
  scheduler.request(); await clock.tick(300); assert.equal(reads, 2)
  scheduler.dispose(); await clock.tick(5000); assert.equal(reads, 2)
})

test('Dashboard live eligibility uses selected/latest identity and Taipei-or-market dates through DST', () => {
  const crossMidnight = new Date('2026-10-29T16:16:00Z')
  const snapshot = { id: 15, snapshotDate: '2026-10-30' }
  const live = { snapshotId: 15, snapshotDate: '2026-10-30' }
  assert.equal(isDashboardLivePriceEligible(snapshot, live, 15, 15, '美股', crossMidnight), true)
  assert.equal(isDashboardLivePriceEligible(snapshot, live, 15, 15, '台股', crossMidnight), true)
  assert.equal(isDashboardLivePriceEligible(snapshot, live, 15, 15, '英股', crossMidnight), true)
  assert.equal(isDashboardLivePriceEligible(snapshot, live, 14, 15, '美股', crossMidnight), false)
  assert.equal(isDashboardLivePriceEligible({ ...snapshot, id: 14 }, live, 15, 15, '美股', crossMidnight), false)
  assert.equal(isDashboardLivePriceEligible(snapshot, { ...live, snapshotId: null }, 15, 15, '美股', crossMidnight), false)
  assert.equal(isDashboardLivePriceEligible(snapshot, { ...live, snapshotDate: '2026-10-29' }, 15, 15, '美股', crossMidnight), false)
  assert.equal(isDashboardLivePriceEligible({ ...snapshot, snapshotDate: '2026-10-31' }, { ...live, snapshotDate: '2026-10-31' }, 15, 15, '美股', crossMidnight), false)
  assert.equal(isDashboardLivePriceEligible(snapshot, live, 15, 15, 'unknown', crossMidnight), false)
  assert.equal(dashboardLiveIdentityMatches(snapshot, live, 15, 15), true)

  const winter = new Date('2026-01-15T16:16:00Z')
  const winterUs = { id: 15, snapshotDate: '2026-01-15' }
  const winterLive = { snapshotId: 15, snapshotDate: '2026-01-15' }
  assert.equal(isDashboardLivePriceEligible(winterUs, winterLive, 15, 15, '美股', winter), true)
  assert.equal(isDashboardLivePriceEligible(winterUs, winterLive, 15, 15, '英股', winter), true)
  assert.equal(isDashboardLivePriceEligible(winterUs, winterLive, 15, 15, '台股', winter), false)

  const summer = new Date('2026-07-15T16:16:00Z')
  const summerUs = { id: 15, snapshotDate: '2026-07-15' }
  const summerLive = { snapshotId: 15, snapshotDate: '2026-07-15' }
  assert.equal(isDashboardLivePriceEligible(summerUs, summerLive, 15, 15, '美股', summer), true)
  assert.equal(isDashboardLivePriceEligible(summerUs, summerLive, 15, 15, '英股', summer), true)
  assert.equal(isDashboardLivePriceEligible(summerUs, summerLive, 15, 15, '台股', summer), false)
})

test('Dashboard rollover only accepts a same-ID forward move to Taipei today', () => {
  const now = new Date('2026-10-29T16:16:00Z')
  const previous = { id: 15, snapshotDate: '2026-10-29' }
  const today = { snapshotId: 15, snapshotDate: '2026-10-30' }
  assert.equal(isDashboardSnapshotRollover(previous, today, 15, 15, now), true)
  assert.equal(isDashboardSnapshotRollover({ ...previous, snapshotDate: '2026-10-31' }, today, 15, 15, now), false)
  assert.equal(isDashboardSnapshotRollover({ ...previous, id: 14 }, today, 15, 15, now), false)
  assert.equal(isDashboardSnapshotRollover(previous, { ...today, snapshotId: 14 }, 15, 15, now), false)
  assert.equal(isDashboardSnapshotRollover(previous, today, 14, 15, now), false)
  assert.equal(isDashboardSnapshotRollover({ ...previous, snapshotDate: '2026-10-30' }, today, 15, 15, now), false)
})

function usLiveQuote(stockCode, price, updatedAt, overrides = {}) {
  return { market: '美股', stockCode, tradingDate: '2026-10-29', quoteStatus: 'LIVE', price,
    priceChange: 1, changePercent: 0.2, updatedAt, source: 'NASDAQ', ...overrides }
}

test('Dashboard real SFC handler uses each panel identity, displays first US tuples, and accepts later SSE', () => {
  const f = fixture(); const stop = f.stream.subscribe({})
  const snapshot = { id: 15, snapshotDate: '2026-10-30' }
  const liveAssets = { snapshotId: 15, snapshotDate: '2026-10-30' }
  const panels = Object.fromEntries(['kpis', 'allocation', 'stock-values', 'holdings'].map(panel => [panel, {
    snapshot, liveAssets: panel === 'holdings' ? { snapshotId: 14, snapshotDate: '2026-10-30' } : liveAssets
  }]))
  const rows = {
    GOOGL: usLiveQuote('GOOGL', 285.17, '2026-10-30T00:16:00+08:00'),
    VOO: usLiveQuote('VOO', 706.935, '2026-10-30T00:16:00+08:00'),
    VT: usLiveQuote('VT', 157.885, '2026-10-30T00:16:00+08:00')
  }
  const maps = Object.fromEntries(Object.keys(panels).map(panel => [panel, Object.fromEntries(
    Object.entries(rows).map(([symbol, row]) => [`美股_${symbol}`, { ...row }])
  )]))
  const crossNow = new Date('2026-10-29T16:16:00Z')
  const eligible = (...args) => isDashboardLivePriceEligible(...args, crossNow)
  let refreshes = 0
  const ctx = page('../views/DashboardView.vue', ['applyLivePrice', 'canOverlayStreamQuote', 'priceMap', 'getRealtimePrice'], {
    livePrices: maps, livePriceStream: f.stream, valuationRefresh: { request: () => refreshes++ },
    panelData: panel => panels[panel], selectedSnapshotId: { value: 15 }, snapshots: { value: [snapshot] },
    shouldApplyLive: (market, base, assets) => eligible(base, assets, 15, 15, market),
    isDashboardLivePriceEligible: eligible,
    marketToday: market => market === '美股' ? '2026-10-29' : '2026-10-30',
    isAcceptedTodayQuote: (quote, day) => quote.tradingDate === day && ['LIVE', 'VERIFIED_CLOSE'].includes(quote.quoteStatus),
    mergeLiveQuote: (current, incoming) => mergeLiveQuote(current, incoming, '2026-10-29'),
    refreshPanelForLive: () => assert.fail('no projection retry expected for a valid tuple')
  })

  for (const [symbol, price] of [['GOOGL', 285.17], ['VOO', 706.935], ['VT', 157.885]]) {
    const quoteForPanel = ctx.getRealtimePrice({ market: '美股', stockCode: symbol }, maps.kpis, snapshot, liveAssets)
    assert.equal(quoteForPanel.price, price)
    assert.equal(quoteForPanel.priceChange, 1)
  }
  const first = usLiveQuote('GOOGL', 285.25, '2026-10-30T00:18:00+08:00')
  ctx.applyLivePrice(first)
  for (const panel of ['kpis', 'allocation', 'stock-values']) assert.equal(maps[panel]['美股_GOOGL'].price, 285.25)
  assert.equal(maps.holdings['美股_GOOGL'].price, 285.17)
  assert.equal(ctx.getRealtimePrice({ market: '美股', stockCode: 'GOOGL' }, maps.holdings, snapshot, panels.holdings.liveAssets), null)

  panels.holdings.liveAssets = liveAssets
  ctx.applyLivePrice(usLiveQuote('GOOGL', 285.31, '2026-10-30T00:19:00+08:00'))
  assert.equal(maps.holdings['美股_GOOGL'].price, 285.31)
  ctx.applyLivePrice(usLiveQuote('GOOGL', 1, '2026-10-30T00:17:00+08:00'))
  for (const map of Object.values(maps)) assert.equal(map['美股_GOOGL'].price, 285.31)
  assert.equal(ctx.priceMap([rows.GOOGL])['美股_GOOGL'].price, 285.17)
  assert.equal(refreshes, 2)
  stop()
})

test('Dashboard real priceMap cannot poison shared stream with a future frozen row before true US BFF and SSE tuples', () => {
  const f = fixture({ todayForMarket: market => market === '美股' ? '2026-10-29' : '2026-10-30' })
  const stop = f.stream.subscribe({})
  const ctx = page('../views/DashboardView.vue', ['canOverlayStreamQuote', 'priceMap'], {
    livePriceStream: f.stream, marketToday: market => market === '美股' ? '2026-10-29' : '2026-10-30'
  })
  const frozenFuture = usLiveQuote('GOOGL', 324.81, '2026-10-30T00:16:00+08:00', { tradingDate: '2026-10-30' })
  assert.equal(ctx.priceMap([frozenFuture])['美股_GOOGL'].price, 324.81)
  assert.equal(f.stream.quotes().length, 0)

  const bffTuple = usLiveQuote('GOOGL', 285.17, '2026-10-30T00:16:00+08:00')
  assert.equal(ctx.priceMap([bffTuple])['美股_GOOGL'].price, 285.17)
  assert.equal(f.stream.quotes()[0].tradingDate, '2026-10-29')
  f.sources[0].emit(usLiveQuote('GOOGL', 285.25, '2026-10-30T00:18:00+08:00'))
  assert.equal(f.stream.quotes()[0].price, 285.25)
  assert.equal(ctx.priceMap([bffTuple])['美股_GOOGL'].price, 285.25)
  assert.equal(ctx.canOverlayStreamQuote({ ...bffTuple, quoteStatus: 'PREVIOUS_CLOSE' }), false)
  assert.equal(ctx.canOverlayStreamQuote({ ...bffTuple, quoteStatus: null }), false)
  assert.equal(ctx.canOverlayStreamQuote({ ...bffTuple, quoteStatus: 'CLOSE_PENDING', price: null }), true)
  stop()
})

test('Dashboard real overlay receives panel-local snapshot/liveAssets and stays frozen for historical or invalid IDs', () => {
  const now = new Date('2026-10-29T16:16:00Z')
  const snapshot = { id: 15, snapshotDate: '2026-10-30', totalDeposit: 5, totalFundValue: 0 }
  const live = { snapshotId: 15, snapshotDate: '2026-10-30', liveTotalAssets: 300,
    stocks: [{ market: '美股', stockCode: 'GOOGL', liveValue: 285.17 }] }
  const quoteMap = { '美股_GOOGL': usLiveQuote('GOOGL', 285.17, '2026-10-30T00:16:00+08:00') }
  const eligible = (...args) => isDashboardLivePriceEligible(...args, now)
  const ctx = page('../views/DashboardView.vue', [
    'shouldApplyLive', 'getLiveValueFromAssets', 'getRealtimePrice', 'overlayLivePrice', 'withPanelLiveOverlay'
  ], {
    selectedSnapshotId: { value: 15 }, snapshots: { value: [snapshot] },
    isDashboardLivePriceEligible: eligible, dashboardLiveIdentityMatches: (...args) => dashboardLiveIdentityMatches(...args),
    marketToday: market => market === '美股' ? '2026-10-29' : '2026-10-30',
    isAcceptedTodayQuote: (quote, day) => quote.tradingDate === day && ['LIVE', 'VERIFIED_CLOSE'].includes(quote.quoteStatus)
  })
  const stock = { market: '美股', stockCode: 'GOOGL', shares: 1, currentValue: 100, investmentCost: 80,
    dividendRate: 0, estimatedDividend: 0, stockPrice: 100 }
  const overlay = ctx.overlayLivePrice(stock, snapshot, live, quoteMap)
  assert.equal(overlay.currentValue, 285.17)
  assert.equal(overlay.stockPrice, 285.17)
  const kpi = ctx.withPanelLiveOverlay(snapshot, [stock], live, quoteMap)
  assert.equal(kpi.totalUsStockValue, 285.17)
  assert.equal(kpi.totalAssets, 300)
  const historical = ctx.overlayLivePrice(stock, { ...snapshot, snapshotDate: '2026-10-28' }, live, quoteMap)
  assert.equal(historical.currentValue, 100)
  const wrongId = ctx.overlayLivePrice(stock, snapshot, { ...live, snapshotId: 14 }, quoteMap)
  assert.equal(wrongId.currentValue, 100)
})

test('Dashboard holdings price cell keeps price/change tuple together when live-assets is unavailable', () => {
  const snapshot = { id: 15, snapshotDate: '2026-10-30' }
  const assets = { snapshotId: 15, snapshotDate: '2026-10-30' }
  const quoteMap = { '美股_GOOGL': usLiveQuote('GOOGL', 285.17, '2026-10-30T00:16:00+08:00') }
  const eligible = (...args) => isDashboardLivePriceEligible(...args, new Date('2026-10-29T16:16:00Z'))
  const holdingPayload = { value: { snapshot, liveAssets: null } }
  const ctx = page('../views/DashboardView.vue', ['shouldApplyLive', 'getRealtimePrice', 'getPriceCell'], {
    selectedSnapshotId: { value: 15 }, snapshots: { value: [snapshot] }, holdingPayload,
    livePrices: { holdings: quoteMap }, isDashboardLivePriceEligible: eligible,
    marketToday: market => market === '美股' ? '2026-10-29' : '2026-10-30',
    isAcceptedTodayQuote: (quote, day) => quote.tradingDate === day && ['LIVE', 'VERIFIED_CLOSE'].includes(quote.quoteStatus),
    isRowClosePending: () => false
  })
  const row = { market: '美股', stockCode: 'GOOGL', stockPrice: 324.81 }
  const frozen = ctx.getPriceCell(row)
  assert.equal(frozen.price, 324.81)
  assert.equal(frozen.priceChange, null)
  assert.equal(frozen.changePercent, null)
  holdingPayload.value.liveAssets = assets
  const live = ctx.getPriceCell(row)
  assert.equal(live.price, 285.17)
  assert.equal(live.priceChange, 1)
  assert.equal(live.changePercent, 0.2)
})

test('Dashboard rollover SFC reload is bounded and late metadata cannot jump away from historical selection', async () => {
  const original = { id: 15, snapshotDate: '2026-10-29' }, refreshed = { id: 15, snapshotDate: '2026-10-30' }
  const gate = deferred(), calls = [], selections = []
  const snapshots = { value: [original] }, selectedSnapshotId = { value: 15 }
  const ctx = page('../views/DashboardView.vue', ['refreshLatestSnapshotForRollover', 'onSnapshotChange'], {
    isMounted: true, snapshots, selectedSnapshotId, allocationTab: { value: 'category' },
    bffApi: { dashboard: { snapshots: options => { calls.push(options); return gate.promise } } },
    panelLoader: { select: id => { selections.push(id); return Promise.resolve() } }
  }, 'let rolloverController = null, rolloverAttemptedScope = null, statusController = null, orderSaveTimer = null;')
  const first = ctx.refreshLatestSnapshotForRollover(15, '2026-10-30')
  ctx.refreshLatestSnapshotForRollover(15, '2026-10-30')
  assert.equal(calls.length, 1)
  ctx.onSnapshotChange(14)
  gate.resolve([refreshed, { id: 14, snapshotDate: '2026-10-29' }])
  await first
  assert.deepEqual(snapshots.value, [original])
  assert.deepEqual(selections, [14])
  assert.equal(calls[0].signal.aborted, true)
})

test('Dashboard realtime restores unavailable live projection once and leaves look-through allocation tab untouched', async () => {
  const snapshot = { id: 15, snapshotDate: '2026-10-30' }
  const live = { snapshotId: 15, snapshotDate: '2026-10-30' }
  const tuple = usLiveQuote('GOOGL', 285.17, '2026-10-30T00:16:00+08:00')
  const panels = {
    kpis: { data: { data: { snapshot, liveAssets: null, stockPrices: [{ ...tuple }] }, warnings: [] }, generation: 2, loading: false, context: {} },
    allocation: { data: { data: { snapshot, classifiedHoldings: [{ name: '科技 ETF' }], twLookthrough: null, usLookthrough: {} }, warnings: [] }, generation: 4, loading: false, context: { tab: 'usStock' } },
    'stock-values': { data: null, generation: 0, loading: false, context: {} },
    holdings: { data: null, generation: 0, loading: false, context: {} },
    trend: { data: null, generation: 0, loading: false, context: {} }
  }
  const livePrices = { kpis: { '美股_GOOGL': { ...tuple, tradingDate: '2026-10-30' } }, allocation: {}, 'stock-values': {}, holdings: {} }
  const refreshes = [], fixedNow = new Date('2026-10-29T16:16:00Z')
  const eligible = (...args) => isDashboardLivePriceEligible(...args, fixedNow)
  const ctx = page('../views/DashboardView.vue', ['refreshMarketStatus', 'refreshPanelForLive'], {
    isMounted: true, statusInFlight: false, selectedSnapshotId: { value: 15 }, snapshots: { value: [snapshot] }, latest: { value: snapshot },
    panels, livePrices, livePriceStream: { visible: () => true, overlay: quote => quote },
    bffApi: { dashboard: { realtime: async () => ({ liveAssets: live, stockPrices: [tuple], marketStatus: {} }) } },
    panelLoader: {
      update(panel, generation, transform) {
        if (!panels[panel]?.data || panels[panel].generation !== generation) return false
        panels[panel].data = transform(panels[panel].data); return true
      },
      run(panel, options) { refreshes.push({ panel, options }); return Promise.resolve(null) }
    },
    dashboardLiveIdentityMatches: (...args) => dashboardLiveIdentityMatches(...args),
    isDashboardLivePriceEligible: eligible,
    isDashboardSnapshotRollover: () => false,
    panelData: panel => panels[panel]?.data?.data ?? null,
    refreshLatestSnapshotForRollover: () => assert.fail('same-date projection recovery is not a snapshot rollover'),
    livePanelRefreshScope: new Map(),
    mergeLiveQuote: (current, incoming) => mergeLiveQuote(current, incoming, '2026-10-29'),
    marketToday: market => market === '美股' ? '2026-10-29' : '2026-10-30',
    isAcceptedTodayQuote: (quote, day) => quote.tradingDate === day && ['LIVE', 'VERIFIED_CLOSE'].includes(quote.quoteStatus)
  }, 'let statusController = null;')
  await ctx.refreshMarketStatus()
  assert.deepEqual(refreshes.map(item => item.panel), ['kpis'])
  assert.equal(panels.kpis.data.data.liveAssets, null)
  assert.equal(panels.allocation.data.data.usLookthrough instanceof Object, true)
  assert.deepEqual(panels.allocation.context, { tab: 'usStock' })

  const preserved = []
  panels.allocation.context = { tab: 'usStock' }
  const refreshContext = page('../views/DashboardView.vue', ['refreshPanelForLive'], {
    isMounted: true, selectedSnapshotId: { value: 15 }, snapshots: { value: [snapshot] }, panels,
    livePanelRefreshScope: new Map(), marketToday: () => '2026-10-30',
    panelLoader: { run: (panel, options) => preserved.push({ panel, options }) }
  })
  refreshContext.refreshPanelForLive('allocation', 15, '2026-10-30')
  assert.equal(preserved[0].options.context.tab, 'usStock')
})

test('Dashboard frozen latest close keeps its own changes without using unrelated live comparisons', () => {
  const snapshot = { id: 15, snapshotDate: '2026-10-30' }
  const quote = { market: '美股', stockCode: 'GOOGL', tradingDate: '2026-10-30',
    quoteStatus: 'VERIFIED_CLOSE', price: 285.17, priceChange: -39.72, changePercent: -12.22 }
  const prices = { '美股_GOOGL': quote }
  const selection = { value: 15 }
  const ctx = page('../views/DashboardView.vue', ['getPriceCell'], {
    selectedSnapshotId: selection, snapshots: { value: [snapshot] },
    holdingPayload: { value: { snapshot, liveAssets: null } }, livePrices: { holdings: prices },
    isRowClosePending: () => false, getRealtimePrice: () => null
  })
  const row = { market: '美股', stockCode: 'GOOGL', stockPrice: 285.17 }
  assert.equal(ctx.getPriceCell(row).priceChange, -39.72)
  assert.equal(ctx.getPriceCell(row).changePercent, -12.22)
  quote.quoteStatus = 'PREVIOUS_CLOSE'
  quote.tradingDate = '2026-10-29' // Weekend/holiday can use the preceding actual close.
  assert.equal(ctx.getPriceCell(row).priceChange, -39.72)
  quote.price = 324.81
  assert.equal(ctx.getPriceCell(row).priceChange, null)
  quote.price = 285.17
  quote.tradingDate = '2026-10-31'
  assert.equal(ctx.getPriceCell(row).priceChange, null)
  quote.tradingDate = '2026-10-30'
  quote.quoteStatus = 'LIVE'
  assert.equal(ctx.getPriceCell(row).priceChange, null)
  quote.quoteStatus = 'VERIFIED_CLOSE'
  selection.value = 14
  assert.equal(ctx.getPriceCell(row).priceChange, null)
})

test('Dashboard CLOSE_PENDING remains visible while eligible live-assets are unavailable', () => {
  const snapshot = { id: 15, snapshotDate: marketToday('台股') }
  const quote = { market: '美股', stockCode: 'GOOGL', tradingDate: snapshot.snapshotDate,
    quoteStatus: 'CLOSE_PENDING', price: null }
  const ctx = page('../views/DashboardView.vue', ['isBaselineToday', 'getQuoteForRow', 'isRowClosePending'], {
    selectedSnapshotId: { value: 15 }, snapshots: { value: [snapshot] }
  })
  assert.equal(ctx.isRowClosePending({ market: '美股', stockCode: 'GOOGL' }, { '美股_GOOGL': quote }, snapshot, null), true)
})
test('Radar real handler updates exact stock, detail and TW0000 quote while decision evidence stays frozen', () => {
  const row = { ...older(), score: 90, regime: 'BULL', action: 'WAIT', reasons: ['frozen'], indicators: { k: 10 } }
  const market = { ...older(), stockCode: '0000', score: 80, regime: 'BULL', asOfDate: yesterday, liveUpdatedAt: `${today}T10:00:01` }
  const usMarket = { price: 1, asOfDate: yesterday, score: 60 }
  const detail = { ...row }
  const ctx = page('../views/TradingRadarView.vue', ['applyPriceUpdate', 'detailKey'], { radar: { value: { stocks: [row], market, usMarket } }, detailStates: { '台股\u00002330': { tuple: { stock: detail } } } })
  ctx.applyPriceUpdate(quote()); assert.equal(row.price, 110); assert.equal(detail.price, 110)
  ctx.applyPriceUpdate(quote({ stockCode: '0000', price: 20000 })); assert.equal(market.price, 20000)
  ctx.applyPriceUpdate(quote({ market: '美股', stockCode: 'VT', price: 5 })); assert.equal(usMarket.price, 1)
  ctx.applyPriceUpdate(older()); assert.equal(row.price, 110)
  assert.equal(row.score, 90); assert.equal(row.action, 'WAIT'); assert.equal(row.indicators.k, 10); assert.equal(market.score, 80); assert.equal(market.asOfDate, yesterday)
})
test('WatchStock real handler only patches quotes, keeps alert/technical snapshots and index comparison intact', () => {
  const row = { ...older(), lastTriggeredPrice: 88, kValue: 20, conditions: [{ triggered: false }], monthlyMa: 90 }
  const index = { ...older(), stockCode: '0000', previousClose: 19999 }
  const ctx = page('../views/WatchStockView.vue', ['applyLivePrice'], { list: { value: [row, index] } })
  ctx.applyLivePrice(quote({ openPrice: 100, highPrice: 115, volume: null, lastTriggeredPrice: 999, monthlyMa: 999 }))
  assert.equal(row.price, 110); assert.equal(row.highPrice, 115); assert.equal(row.volume, null); assert.equal(row.monthlyMa, 90); assert.equal(row.lastTriggeredPrice, 88); assert.equal(row.conditions[0].triggered, false)
  ctx.applyLivePrice(quote({ stockCode: '0000', previousClose: 1 })); assert.equal(index.previousClose, 19999)
})
test('SnapshotForm actual handlers preserve dirty/manual fields, gate scope/date and reject late enriched HTTP', () => {
  const f = fixture(); const stop = f.stream.subscribe({})
  const row = { ...older(), latestPrice: 100, shares: 50, investmentCost: 1000, dividendRate: 0.04, stockName: '台積電', brokerRows: [{ shares: 10 }] }
  const form = reactive({ snapshotDate: today, stocks: [row] }), loadedFormKey = { value: 'new' }, formBlocked = { value: false }, readScope = { disposed: false }
  const ctx = page('../views/SnapshotFormView.vue', ['applyLivePrice', 'applyEnrichedPrices', 'withoutDirtyChange'], { form, loadedFormKey, routeKey: () => 'new', formBlocked, readScope, livePriceStream: f.stream, watch }, 'let programmaticChange = false; let userEdited = false;')
  vm.runInContext("watch(() => form.stocks, () => { if (!programmaticChange) userEdited = true }, { deep: true, flush: 'sync' })", ctx)
  ctx.applyLivePrice(quote()); assert.equal(row.latestPrice, 110); assert.equal(vm.runInContext('userEdited', ctx), false); assert.equal(vm.runInContext('programmaticChange', ctx), false)
  ctx.applyEnrichedPrices([older()], [row], false); assert.equal(row.latestPrice, 110)
  form.snapshotDate = yesterday; ctx.applyLivePrice(quote({ price: 120 })); assert.equal(row.latestPrice, 110)
  form.snapshotDate = today; loadedFormKey.value = 'other'; ctx.applyLivePrice(quote({ price: 120 })); assert.equal(row.latestPrice, 110)
  loadedFormKey.value = 'new'; formBlocked.value = true; ctx.applyLivePrice(quote({ price: 120 })); assert.equal(row.latestPrice, 110)
  assert.equal(row.shares, 50); assert.equal(row.investmentCost, 1000); assert.equal(row.dividendRate, 0.04); assert.equal(row.brokerRows[0].shares, 10); stop()
})

function dialogFixture(clock, api) {
  const props = { modelValue: true, stock: { market: '台股', stockCode: '2330' } }
  const state = { props, activeTab: { value: 'chart' }, isIntraday: { value: true }, intradayLoading: { value: false }, intradaySession: { value: null }, intradayTicks: { value: [] }, quoteRequestToken: { value: 0 }, quoteDetailLoading: { value: false }, quoteDetail: { value: null }, livePriceStream: { visible: () => true }, bffApi: { stockAnalysis: api }, createTrailingRefresh: (read, options) => createTrailingRefresh(read, { ...options, ...clock.options }) }
  const ctx = page('../components/StockAnalysisDialog.vue', ['liveIdentity', 'liveCurrent', 'fetchIntraday', 'fetchQuoteDetail', 'requestLiveRead', 'pauseLiveReads', 'intradayRefresh', 'bookRefresh'], state,
    'let liveGeneration = 0, intradayRequest = null, bookRequest = null, intradayController = null, bookController = null;')
  return { ctx, ...state }
}
const session = price => ({ tradingDate: today, ticks: [{ price, time: '10:00:02', volume: 1 }], lastPrice: price })
test('Dialog actual session handler delays >=300ms, tails final tick, uses canonical readOnly GET and no overlap', async () => {
  const clock = new Timers(), calls = []
  const d = dialogFixture(clock, { getIntradayTicks: async (...args) => { calls.push({ at: clock.time, args }); return session(calls.length === 1 ? 100 : 110) } })
  d.ctx.requestLiveRead(); await clock.tick(299); assert.equal(calls.length, 0)
  await clock.tick(1); assert.equal(calls.length, 1); assert.equal(calls[0].args[3].readOnly, true)
  assert.equal(d.intradaySession.value.comparisonPrice, null) // incomplete comparison tuple stays unavailable, never derived
  assert.equal(d.intradayTicks.value[0].price, 100)
  await clock.tick(1000); assert.equal(calls.length, 2); assert.equal(d.intradayTicks.value[0].price, 110)
  await clock.tick(5000); assert.equal(calls.length, 2)
  d.isIntraday.value = false; d.ctx.requestLiveRead(); await clock.tick(5000); assert.equal(calls.length, 2)
})
test('Dialog actual generation/abort fences switch stock and close; initial read keeps reference default', async () => {
  const clock = new Timers(), gate = deferred(), calls = []
  const d = dialogFixture(clock, { getIntradayTicks: (...args) => { calls.push(args); return gate.promise } })
  const request = d.ctx.fetchIntraday(); await flush(); assert.equal(calls[0][3].readOnly, false)
  d.ctx.pauseLiveReads(); assert.equal(calls[0][3].signal.aborted, true)
  d.props.stock = { stockCode: '2317', market: '台股' }
  gate.resolve(session(999)); await request; assert.equal(d.intradaySession.value, null)
  d.props.modelValue = false; await d.ctx.fetchIntraday(true); assert.equal(calls.length, 1)
})
test('Dialog book real handler only reads open book tab, minimum two seconds and no price synthesis', async () => {
  const clock = new Timers(), calls = []
  const d = dialogFixture(clock, { getIntradayTicks: async () => session(100), getQuoteDetail: async () => { calls.push(clock.time); return { available: true, canonicalRevision: calls.length, levels: [{ bidPrice: 1 }] } } })
  d.isIntraday.value = false; d.ctx.requestLiveRead(); await clock.tick(3000); assert.equal(calls.length, 0)
  d.activeTab.value = 'quote-detail'; d.ctx.requestLiveRead(); await clock.tick(300); assert.equal(calls.length, 1)
  for (let i = 0; i < 20; i++) { d.ctx.requestLiveRead(); await clock.tick(100) }
  await clock.tick(5000); for (let i = 1; i < calls.length; i++) assert.ok(calls[i] - calls[i - 1] >= 2000)
  assert.equal(d.quoteDetail.value.levels[0].bidPrice, 1)
  d.activeTab.value = 'chart'; const before = calls.length; d.ctx.requestLiveRead(); await clock.tick(5000); assert.equal(calls.length, before)
})
test('quote detail compiled template keeps refresh feedback behind the available-snapshot boundary', () => {
  const file = new URL('../components/StockAnalysisDialog.vue', import.meta.url)
  const { descriptor } = parse(readFileSync(file, 'utf8'))
  const fragment = descriptor.template.content.match(/<el-tab-pane(?=[^>]*\bname="quote-detail")[\s\S]*?<\/el-tab-pane>/)?.[0]
  assert.ok(fragment, 'real quote-detail tab template exists')
  const { code, errors } = compileTemplate({ source: fragment, filename: file.pathname, id: 'quote-detail-refresh-contract', compilerOptions: { mode: 'function' } })
  assert.deepEqual(errors, [])
  assert.equal(code.split('_ctx.quoteDetailLoading && !_ctx.quoteDetail?.available').length - 1, 2,
    'button spinner and loading branch both require no available snapshot')
})
test('Dialog quote detail real handler deduplicates canonical reads and ignores late failures after stock switch or close', async () => {
  const gate = deferred(), calls = []
  const d = dialogFixture(new Timers(), { getQuoteDetail: (...args) => { calls.push(args); return gate.promise } })
  const next = { available: true, canonicalRevision: 2, source: 'FUBON_BOOKS', levels: Array.from({ length: 5 }, (_, i) => ({ level: i + 1, bidPrice: 90 - i, bidVolumeLots: i + 1 })) }
  d.quoteDetail.value = { available: true, canonicalRevision: 1, source: 'YAHOO_TW', levels: [{ level: 1, bidPrice: 80 }] }
  const first = d.ctx.fetchQuoteDetail(), duplicate = d.ctx.fetchQuoteDetail()
  assert.equal(d.quoteDetailLoading.value, true)
  await flush()
  assert.equal(calls.length, 1, 'one in-flight five-level request is shared')
  assert.equal(calls[0][0], '2330'); assert.equal(calls[0][1], '台股'); assert.ok(calls[0][2].signal instanceof AbortSignal)
  gate.resolve(next); await Promise.all([first, duplicate])
  assert.strictEqual(d.quoteDetail.value, next, 'the complete canonical snapshot replaces the prior snapshot')
  assert.equal(d.quoteDetail.value.levels.length, 5); assert.equal(d.quoteDetailLoading.value, false)

  for (const abandon of [
    dialog => { dialog.props.stock = { market: '台股', stockCode: '2317' } },
    dialog => { dialog.props.modelValue = false }
  ]) {
    const late = deferred(), stale = dialogFixture(new Timers(), { getQuoteDetail: () => late.promise })
    const request = stale.ctx.fetchQuoteDetail(); await flush()
    stale.ctx.pauseLiveReads(); stale.quoteDetail.value = null; abandon(stale)
    late.reject(new Error('late request failure')); await request
    assert.equal(stale.quoteDetail.value, null, 'late rejection cannot restore an unavailable result')
    assert.equal(stale.quoteDetailLoading.value, false)
  }
})


test('nanosecond source ordering and offset normalization retain exact newest quote', () => {
  const current = quote({ updatedAt: `${today}T10:00:02.123456000` })
  assert.equal(mergeLiveQuote(current, quote({ updatedAt: `${today}T10:00:02.123455000`, price: 1 })), current)
  assert.equal(mergeLiveQuote(current, quote({ updatedAt: `${today}T02:00:02.123455000Z`, price: 1 })), current)
  assert.equal(mergeLiveQuote(current, quote({ updatedAt: `${today}T10:00:02.123457000`, price: 120 })).price, 120)
})
test('queued events from disposed/hidden transport cannot enter a new connection', () => {
  const f = fixture(); let delivered = 0, caught = 0
  const stop = f.stream.subscribe({ onQuote: () => delivered++, onCatchup: () => caught++ })
  f.sources[0].open(); f.hide(true); f.hide(false)
  const before = caught
  f.sources[0].emit(quote()); f.sources[0].open()
  assert.equal(delivered, 0); assert.equal(caught, before)
  f.sources[1].emit(quote()); assert.equal(delivered, 1); stop()
})
test('Radar real HTTP pending and verified close preserve unknown comparison without zero', () => {
  const row = { ...older() }
  const ctx = page('../views/TradingRadarView.vue', ['applyPriceUpdate', 'detailKey'], { radar: { value: { stocks: [row] } }, detailStates: {} })
  ctx.applyPriceUpdate(quote({ quoteStatus: 'CLOSE_PENDING', price: null, changePercent: null }))
  assert.equal(row.quoteStatus, 'CLOSE_PENDING'); assert.equal(row.price, null); assert.equal(row.changePercent, null)
  ctx.applyPriceUpdate(quote({ quoteStatus: 'VERIFIED_CLOSE', price: 115, changePercent: null, updatedAt: `${today}T14:30:00` }))
  assert.equal(row.price, 115); assert.equal(row.changePercent, null)
  ctx.applyPriceUpdate(quote({ price: 999, updatedAt: `${today}T14:31:00` })); assert.equal(row.price, 115)
})
test('actual WatchStock catchup is fenced across hidden-visible generation and disposal', async () => {
  const clock = new Timers(), gate = deferred(), row = older()
  const ctx = page('../views/WatchStockView.vue', ['applyLivePrice', 'catchupPrices'], {
    list: { value: [row] }, livePriceStream: { visible: () => true, overlay: q => q },
    bffApi: { watchStock: { realtime: () => gate.promise } },
    createTrailingRefresh: (read, options) => createTrailingRefresh(read, { ...options, ...clock.options })
  }, 'let liveEpoch = 0, watchDisposed = false;')
  vm.runInContext('catchupPrices.request()', ctx); await clock.tick(0)
  vm.runInContext('liveEpoch++; catchupPrices.cancel()', ctx)
  gate.resolve([quote()]); await flush(); assert.equal(row.price, 100)
})
test('Dialog actual in-flight event reads remain singular and abort on hidden/switch', async () => {
  const clock = new Timers(), gate = deferred(), calls = []
  const d = dialogFixture(clock, { getIntradayTicks: (...args) => { calls.push(args); return gate.promise } })
  d.ctx.requestLiveRead(); await clock.tick(300)
  for (let i = 0; i < 20; i++) { d.ctx.requestLiveRead(); await clock.tick(100) }
  assert.equal(calls.length, 1)
  d.ctx.pauseLiveReads(); assert.equal(calls[0][3].signal.aborted, true)
  d.props.modelValue = false; gate.resolve(session(999)); await flush()
  assert.equal(d.intradaySession.value, null); assert.equal(clock.tasks.size, 0)
})


test('explicit null LIVE comparisons update authoritative price and atomically clear the comparison pair', () => {
  for (const fields of [ { changePercent: null, priceChange: 10 }, { changePercent: 10, priceChange: null },
    { changePercent: null, changePct: 999, priceChange: null } ]) {
    const next = mergeLiveQuote(older(), quote({ price: 120, ...fields }))
    assert.equal(next.price, 120); assert.equal(next.priceChange, null); assert.equal(next.changePercent, null)
  }
  const legacy = quote({ price: 120, changePct: null, priceChange: 10 }); delete legacy.changePercent
  assert.equal(mergeLiveQuote(older(), legacy).changePercent, null)
  for (const field of ['changePercent', 'priceChange']) {
    const missing = quote(); delete missing[field]
    const current = older(); assert.equal(mergeLiveQuote(current, missing), current)
    for (const bad of [undefined, 'NaN', NaN, Infinity, '']) {
      assert.equal(mergeLiveQuote(current, quote({ [field]: bad })), current)
    }
  }
})
test('all real page handlers accept explicit null comparisons, reject missing/NaN, and preserve strategy/manual evidence', () => {
  const f = fixture(); const stop = f.stream.subscribe({})
  const dashboardPrices = { holdings: { '台股_2330': older() }, kpis: { '台股_2330': older() } }
  const dashboardSnapshot = { id: 15, snapshotDate: today }
  const dashboardLiveAssets = { snapshotId: 15, snapshotDate: today }
  const dashboardPanels = { holdings: { snapshot: dashboardSnapshot, liveAssets: dashboardLiveAssets }, kpis: { snapshot: dashboardSnapshot, liveAssets: dashboardLiveAssets } }
  const dashboard = page('../views/DashboardView.vue', ['applyLivePrice'], {
    livePrices: dashboardPrices, valuationRefresh: { request() {} },
    selectedSnapshotId: { value: 15 }, snapshots: { value: [dashboardSnapshot] },
    panelData: panel => dashboardPanels[panel], isDashboardLivePriceEligible,
    refreshPanelForLive: () => assert.fail('fresh same-day tuple should merge directly')
  })
  const watchRow = { ...older(), monthlyMa: 90, lastTriggeredPrice: 80 }
  const watchPage = page('../views/WatchStockView.vue', ['applyLivePrice'], { list: { value: [watchRow] } })
  const radarRow = { ...older(), score: 99, action: 'WAIT' }
  const radarPage = page('../views/TradingRadarView.vue', ['applyPriceUpdate', 'detailKey'], { radar: { value: { stocks: [radarRow] } }, detailStates: {} })
  const formRow = { ...older(), latestPrice: 100, investmentCost: 1234 }
  const form = { snapshotDate: today, stocks: [formRow] }
  const formPage = page('../views/SnapshotFormView.vue', ['applyLivePrice', 'withoutDirtyChange'], {
    form, loadedFormKey: { value: 'new' }, routeKey: () => 'new', formBlocked: { value: false }, readScope: { disposed: false }
  }, 'let programmaticChange = false;')
  const handlers = [q => dashboard.applyLivePrice(q), q => watchPage.applyLivePrice(q), q => radarPage.applyPriceUpdate(q), q => formPage.applyLivePrice(q)]
  const live = quote({ price: 120, changePercent: null, priceChange: 10 })
  handlers.forEach(handler => handler(live))
  for (const row of [dashboardPrices.holdings['台股_2330'], dashboardPrices.kpis['台股_2330'], watchRow, radarRow]) {
    assert.equal(row.price, 120); assert.equal(row.changePercent, null); assert.equal(row.priceChange, null)
  }
  assert.equal(formRow.latestPrice, 120); assert.equal(formRow.priceChangePct, null); assert.equal(formRow.priceChange, null)
  const missing = quote({ price: 999, updatedAt: `${today}T10:00:04` }); delete missing.priceChange
  const missingPct = { ...missing, priceChange: 1 }; delete missingPct.changePercent
  for (const bad of [missing, missingPct, quote({ price: 999, priceChange: 'NaN', updatedAt: `${today}T10:00:04` }), quote({ price: 999, changePercent: 'NaN', updatedAt: `${today}T10:00:04` })]) handlers.forEach(handler => handler(bad))
  assert.equal(watchRow.price, 120); assert.equal(radarRow.price, 120); assert.equal(formRow.latestPrice, 120); assert.equal(dashboardPrices.holdings['台股_2330'].price, 120)
  assert.equal(watchRow.monthlyMa, 90); assert.equal(watchRow.lastTriggeredPrice, 80); assert.equal(radarRow.score, 99); assert.equal(radarRow.action, 'WAIT'); assert.equal(formRow.investmentCost, 1234)
  stop()
})
