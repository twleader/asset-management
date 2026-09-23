import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import * as vue from 'vue'
import { parse, compileScript, compileTemplate } from '@vue/compiler-sfc'
import { createSnapshotFormPanelLoader, panelContext } from './snapshotFormPanelLoader.js'
import { createSnapshotFormReadScope } from './snapshotFormReadScope.js'
import * as quotes from './displayQuote.js'
import * as dates from './localDate.js'

// Execute the real SFC setup and compiled render with Vue's actual reactivity.
// Only browser/UI adapters and network boundaries are replaced; no application logic is copied.
const descriptor = parse(readFileSync(new URL('../views/SnapshotFormView.vue', import.meta.url), 'utf8')).descriptor
const script = compileScript(descriptor, { id: 'snapshot-form-test' })
const compiled = script.content.replace(/^import[^\n]*\n/gm, '').replace('export default', 'const component =')
const template = compileTemplate({ source: descriptor.template.content, filename: 'SnapshotFormView.vue',
  id: 'snapshot-form-test', compilerOptions: { bindingMetadata: script.bindings } })
assert.deepEqual(template.errors, [])
const aliases = [...template.code.matchAll(/(\w+) as (_\w+)/g)].map(match => [match[2], vue[match[1]]])
const render = new Function(...aliases.map(([name]) => name), template.code
  .replace(/^import[^\n]*\n/gm, '').replace('export function render', 'function render') + '\nreturn render')(...aliases.map(([, value]) => value))
const panels = ['basic', 'deposits', 'stocks', 'funds']
const deferred = () => {
  let resolve, reject
  const promise = new Promise((ok, fail) => { resolve = ok; reject = fail })
  return { promise, resolve, reject }
}
const flush = async () => { for (let i = 0; i < 15; i++) await vue.nextTick() }
const stock = { stockCode: 'TEST', stockName: 'Saved stock', market: '美股', shares: 2, currency: 'USD',
  investmentCost: 100, originalCurrencyValue: 50, currentValue: 4000, dividendRate: 0.02,
  transactionDate: '2026-09-01', transactionExchangeRate: null }
function envelope(panel, id = '7', changes = {}) {
  const { snapshot = {}, ...data } = changes
  return { panel, snapshotId: Number(id), warnings: [], data: {
    snapshot: { id: Number(id), snapshotDate: '2026-09-23', usdExchangeRate: 31.2, notes: '',
      ...(panel === 'basic' ? {} : { [panel]: [] }), ...snapshot },
    snapshotVersion: 'v1', effectiveUsdExchangeRate: 31.2, banks: [], brokers: [], depositTypes: [],
    transitFundTypes: [], fundMasters: [], mergedStocks: [], stockPrices: [], marketStatus: {}, transactionRates: {}, ...data
  } }
}
function harness(t, { id = '7', handlers = {}, panelData = {} } = {}) {
  const route = vue.reactive({ params: id == null ? {} : { id } })
  const hooks = [], timers = new Map(), calls = [], messages = [], navigations = []
  let timerSeq = 0
  const api = {}
  const defaults = { ...Object.fromEntries(panels.map(panel => [`${panel}Panel`, target => envelope(panel, target, panelData[panel])])),
    getLookups: () => ({ banks: [], brokers: [], depositTypes: [], transitFundTypes: [] }), getFunds: () => [],
    exchangeRate: () => ({ midRate: 31.2 }), prices: () => [], realtime: () => ({ marketStatus: {} }),
    update: () => ({}), create: () => ({ id: 8 }), listSnapshots: () => [], get: () => ({}) }
  for (const [name, fallback] of Object.entries(defaults)) api[name] = (...args) => {
    calls.push({ name, args })
    return Promise.resolve().then(() => (handlers[name] ?? fallback)(...args))
  }
  const store = vue.reactive({ currentSnapshot: null, fetchSnapshots: async () => handlers.fetchSnapshots?.() })
  const deps = { ...vue, ...quotes, ...dates, createSnapshotFormPanelLoader, calculatePanelContext: panelContext,
    createSnapshotFormReadScope, useRoute: () => route, useRouter: () => ({ replace: path => navigations.push(path) }),
    useAssetStore: () => store, bffApi: { snapshotForm: api },
    ElMessage: Object.fromEntries(['error', 'warning', 'success'].map(kind => [kind, message => messages.push({ kind, message })])),
    ElMessageBox: { confirm: async () => {} }, Sortable: { create: () => ({ destroy() {}, option() {} }) },
    ArrowLeft: {}, Plus: {}, Delete: {}, Operation: {}, InfoFilled: {}, TaiwanMap: {}, UsFlag: {}, StockAnalysisDialog: {},
    SnapshotFormPanelState: {}, onMounted() {}, onUnmounted: hook => hooks.push(hook),
    setInterval: callback => { timers.set(++timerSeq, callback); return timerSeq }, clearInterval: key => timers.delete(key) }
  const component = new Function(...Object.keys(deps), compiled + '\nreturn component')(...Object.values(deps))
  const scope = vue.effectScope()
  let view
  scope.run(() => { view = component.setup({}, { expose() {} }) })
  view.formRef.value = { validate: async () => true }
  let closed = false
  const close = () => { if (closed) return; closed = true; hooks.forEach(hook => hook()); scope.stop() }
  t.after(close)
  return { view, route, api, handlers, calls, messages, timers, store, navigations, close,
    render() {
      const oldWarn = console.warn
      console.warn = () => {} // resolveComponent needs a mounted DOM instance; render expressions still execute.
      try { return render(vue.proxyRefs(view), [], {}, vue.proxyRefs(view), {}, {}) } finally { console.warn = oldWarn }
    } }
}

test('real edit SFC renders during independent panel loading and sends exactly four requests', async t => {
  const requests = Object.fromEntries(panels.map(panel => [panel, deferred()]))
  const h = harness(t, { handlers: Object.fromEntries(panels.map(panel => [`${panel}Panel`, () => requests[panel].promise])) })
  assert.doesNotThrow(() => h.render())
  const load = h.view.loadFormData()
  assert.deepEqual(h.calls.map(call => call.name), panels.map(panel => `${panel}Panel`))
  requests.deposits.resolve(envelope('deposits', '7', { snapshot: { deposits: [{ id: 1, currency: 'TWD', amount: 25 }] } }))
  await flush()
  assert.equal(h.view.form.deposits[0].amount, 25)
  assert.equal(h.view.formBlocked.value, true)
  assert.equal(h.view.panelsReady.value, false)
  assert.doesNotThrow(() => h.render())
  panels.filter(panel => panel !== 'deposits').forEach(panel => requests[panel].resolve(envelope(panel)))
  await load
  assert.equal(h.view.formBlocked.value, false)
  assert.equal(h.calls.length, 4)
  assert.equal(h.timers.size, 1)
})

test('initial mapping preserves saved funds/raw legacy deposits and supplements only US transaction FX', async t => {
  const legacy = { id: 42, currency: 'USD', amount: 100, originalAmount: null }
  const us = { ...stock }, tw = { ...stock, market: '台股', currency: 'TWD', stockCode: '2330' }
  const h = harness(t, { panelData: {
    basic: { snapshot: { usdExchangeRate: null } },
    deposits: { snapshot: { usdExchangeRate: null, deposits: [legacy] } },
    stocks: { snapshot: { usdExchangeRate: null, stocks: [us, tw] }, transactionRates: { '2026-09-01': 30 } },
    funds: { snapshot: { usdExchangeRate: null, funds: [{ fundCode: 'F1', units: 10, currentValue: 123, estimatedDividend: null }] },
      fundMasters: [{ fundCode: 'F1', twdPerUnit: 99, dividendPerUnit: 5 }] }
  } })
  await h.view.loadFormData()
  assert.equal(h.view.form.deposits[0].amount, 100)
  assert.equal(h.view.form.usdExchangeRate, 31.2)
  assert.equal(h.view.form.funds[0].currentValue, 123)
  assert.equal(h.view.form.funds[0].estimatedDividend, null)
  assert.equal(h.view.form.stocks[0].brokerRows[0].transactionExchangeRate, 30)
  assert.equal(h.view.stockCost(h.view.form.stocks[0]), 3000)
  assert.equal(h.view.form.stocks[1].brokerRows[0].transactionExchangeRate, null)
  await h.view.submit()
  const payload = h.calls.find(call => call.name === 'update').args[1]
  assert.equal(payload.usdExchangeRate, 31.2)
  assert.equal(payload.deposits[0].originalAmount, 100)
  assert.equal(payload.stocks[0].transactionExchangeRate, 30)
  assert.equal(payload.funds[0].currentValue, 123)
})

test('failed required panel blocks save; retry preserves successful row identity', async t => {
  const h = harness(t, { handlers: { stocksPanel: () => { throw Error('stocks unavailable') } },
    panelData: { deposits: { snapshot: { deposits: [{ id: 42, currency: 'TWD', amount: 25 }] } } } })
  await assert.rejects(h.view.loadFormData(), /必要區塊/)
  const row = h.view.form.deposits[0]
  await h.view.submit()
  assert.equal(h.calls.some(call => call.name === 'update'), false)
  delete h.handlers.stocksPanel
  await h.view.retryPanel('stocks')
  assert.equal(h.view.form.deposits[0], row)
  assert.equal(h.view.formBlocked.value, false)
  assert.deepEqual(h.calls.map(call => call.name), [...panels.map(panel => `${panel}Panel`), 'stocksPanel'])
})

test('mixed version or effective FX never enables editing or submit', async t => {
  for (const change of [{ snapshotVersion: 'v2' }, { effectiveUsdExchangeRate: 33 }]) {
    const h = harness(t, { panelData: { funds: change } })
    await assert.rejects(h.view.loadFormData(), /版本不一致/)
    assert.equal(h.view.panelContext.value.mismatch, true)
    await h.view.submit()
    assert.equal(h.calls.length, 4)
  }
})

test('route changes and unmount fence late success, failure, finally and timers', async t => {
  const pending = []
  const h = harness(t, { handlers: Object.fromEntries(panels.map(panel => [`${panel}Panel`, id => {
    const d = deferred(); pending.push({ panel, id, ...d }); return d.promise
  }])) })
  const first = h.view.loadFormData()
  await flush()
  h.route.params.id = '8'
  await flush()
  pending.filter(p => p.id === '7').forEach((p, i) => i ? p.resolve(envelope(p.panel, p.id)) : p.reject(Error('old failure')))
  await first
  assert.equal(h.view.loading.value, true)
  assert.equal(h.view.loadedFormKey.value, null)
  assert.equal(h.timers.size, 0)
  pending.filter(p => p.id === '8').forEach(p => p.resolve(envelope(p.panel, p.id)))
  await flush()
  assert.equal(h.view.loadedFormKey.value, '8')
  assert.equal(h.view.loading.value, false)
  assert.equal(h.timers.size, 1)
  const last = h.view.loadFormData()
  await flush()
  h.close()
  pending.slice(-4).forEach(p => p.resolve(envelope(p.panel, p.id)))
  await last
  assert.equal(h.timers.size, 0)
})

test('date refresh starts despite pending polling and old results cannot overwrite the new date', async t => {
  const h = harness(t, { panelData: { stocks: { snapshot: { stocks: [{ ...stock }] } } } })
  await h.view.loadFormData()
  const oldPrice = deferred(), oldFx = deferred(), oldFunds = deferred()
  h.handlers.prices = date => date === '2026-09-23' ? oldPrice.promise : [{ market: '美股', stockCode: 'TEST', price: 70 }]
  const poll = h.view.loadAllPrices()
  await flush()
  h.handlers.exchangeRate = date => date === '2026-09-22' ? oldFx.promise : { midRate: 32 }
  h.handlers.getFunds = date => date === '2026-09-22' ? oldFunds.promise : []
  h.view.form.snapshotDate = '2026-09-22'
  await flush()
  h.view.form.snapshotDate = '2026-09-21'
  await flush()
  assert.equal(h.view.dateLoading.value, false)
  assert.equal(h.view.form.usdExchangeRate, 32)
  assert.equal(h.view.form.stocks[0].latestPrice, 70)
  oldPrice.resolve([{ market: '美股', stockCode: 'TEST', price: 999 }])
  oldFx.resolve({ midRate: 999 }); oldFunds.resolve([{ fundCode: 'OLD', twdPerUnit: 999 }])
  await poll; await flush()
  assert.equal(h.view.form.usdExchangeRate, 32)
  assert.equal(h.view.form.stocks[0].latestPrice, 70)
  assert.equal(h.view.fundMasterMap.value.OLD, undefined)
  assert.deepEqual(h.calls.filter(c => c.name === 'prices').map(c => c.args[0]), ['2026-09-23', '2026-09-22', '2026-09-21'])
})

test('date failure stays locked until successful retry; passive quotes do not dirty saved drafts', async t => {
  const h = harness(t, { panelData: { stocks: { snapshot: { stocks: [{ ...stock }] } } } })
  await h.view.loadFormData()
  h.handlers.prices = () => [{ market: '美股', stockCode: 'TEST', price: 70, dividendRate: 0.99 }]
  await h.view.loadAllPrices()
  assert.equal(h.view.userEdited.value, false)
  assert.equal(h.view.form.stocks[0].dividendRate, 0.02)
  h.handlers.exchangeRate = () => { throw Error('no FX') }
  h.view.form.snapshotDate = '2026-09-22'
  await flush()
  assert.equal(h.view.formBlocked.value, true)
  assert.match(h.view.dateError.value.message, /no FX/)
  await h.view.submit()
  assert.equal(h.calls.some(c => c.name === 'update'), false)
  delete h.handlers.exchangeRate
  await h.view.reloadDateData()
  assert.equal(h.view.formBlocked.value, false)
})

test('date switch clears cancelled manual refresh state without allowing its late quote', async t => {
  const h = harness(t, { panelData: { stocks: { snapshot: { stocks: [{ ...stock }] } } } })
  await h.view.loadFormData()
  const old = deferred()
  h.handlers.prices = date => date === '2026-09-23' ? old.promise : [{ market: '美股', stockCode: 'TEST', price: 70 }]
  const manual = h.view.refreshAllPrices()
  await flush()
  assert.equal(h.view.refreshingAll.value, true)
  h.view.form.snapshotDate = '2026-09-22'
  await flush()
  assert.equal(h.view.refreshingAll.value, false)
  assert.equal(h.view.formBlocked.value, false)
  old.resolve([{ market: '美股', stockCode: 'TEST', price: 999 }])
  await manual
  assert.equal(h.view.form.stocks[0].latestPrice, 70)
})

test('new snapshot bootstrap remains usable and validation can POST once', async t => {
  const h = harness(t, { id: null })
  await h.view.loadFormData()
  assert.equal(h.view.loadedFormKey.value, 'new')
  assert.equal(h.view.formBlocked.value, false)
  await h.view.submit()
  assert.equal(h.calls.filter(c => c.name === 'create').length, 1)
  assert.equal(h.calls.some(c => c.name.endsWith('Panel')), false)
  assert.deepEqual(h.navigations, ['/snapshots/8/edit'])
})

test('canonical partial readback rejects second PUT and retry adopts fresh IDs despite list failure', async t => {
  let saved = false
  const h = harness(t, { handlers: {
    depositsPanel: id => envelope('deposits', id, { snapshot: { deposits: [{ id: saved ? 90 : 42, currency: 'TWD', amount: 25 }] } }),
    stocksPanel: id => { if (saved) throw Error('canonical stock read failed'); return envelope('stocks', id) },
    update: () => { saved = true }, fetchSnapshots: () => { throw Error('list unavailable') }
  } })
  await h.view.loadFormData()
  await h.view.submit()
  assert.equal(h.view.canonicalReloadRequired.value, true)
  assert.equal(h.view.form.deposits[0].id, 90)
  await h.view.submit()
  assert.equal(h.calls.filter(c => c.name === 'update').length, 1)
  delete h.handlers.stocksPanel
  await h.view.retryPanel('stocks')
  assert.equal(h.view.canonicalReloadRequired.value, false)
  await h.view.submit()
  assert.equal(h.calls.filter(c => c.name === 'update')[1].args[1].deposits[0].id, 90)
  assert.equal(h.view.formBlocked.value, false)
})

test('row/code/transaction removal fences manual quote and FX callbacks', async t => {
  const h = harness(t, { panelData: { stocks: { snapshot: { stocks: [{ ...stock }] } } } })
  await h.view.loadFormData()
  const pendingPrice = deferred(), pendingFx = deferred()
  h.handlers.prices = () => pendingPrice.promise
  h.handlers.exchangeRate = () => pendingFx.promise
  const row = h.view.form.stocks[0], br = row.brokerRows[0]
  const price = h.view.fetchPrice(row)
  const fx = h.view.onUsTransactionDateChange(br, br.transactionDate)
  await flush()
  row.stockCode = 'CHANGED'
  row.brokerRows = []
  pendingPrice.resolve([{ market: '美股', stockCode: 'TEST', price: 999 }])
  pendingFx.resolve({ midRate: 999 })
  await Promise.all([price, fx])
  assert.equal(row.latestPrice, null)
  assert.equal(br.transactionExchangeRate, null)
})

test('missing transaction-day FX clears the preceding date rate to allow snapshot fallback', async t => {
  const h = harness(t, { panelData: { stocks: { snapshot: { stocks: [{ ...stock, transactionExchangeRate: 30 }] } } } })
  await h.view.loadFormData()
  h.handlers.exchangeRate = () => ({}) // legacy endpoint maps an absent on-date lookup to an empty object
  const br = h.view.form.stocks[0].brokerRows[0]
  br.transactionDate = '2026-09-02'
  await h.view.onUsTransactionDateChange(br, br.transactionDate)
  assert.equal(br.transactionExchangeRate, null)
  assert.equal(h.view.effectiveRate(br), 31.2)
})

test('superseded row reads clear cancelled flags while old finally leaves the new request loading', async t => {
  const h = harness(t, { panelData: { stocks: { snapshot: { stocks: [{ ...stock }] } } } })
  await h.view.loadFormData()
  const first = deferred(), second = deferred()
  let count = 0
  h.handlers.prices = () => (++count === 1 ? first.promise : second.promise)
  const row = h.view.form.stocks[0]
  const price = h.view.fetchPrice(row)
  await flush()
  const dividend = h.view.fetchDividendRate(row)
  await flush()
  assert.equal(row._fetchingPrice, false)
  assert.equal(row._fetchingDividend, true)
  first.resolve([{ market: '美股', stockCode: 'TEST', price: 999 }])
  await price
  assert.equal(row._fetchingDividend, true)
  row.stockCode = ''
  second.resolve([{ market: '美股', stockCode: 'TEST', dividendRate: 0.9 }])
  await dividend
  assert.equal(row._fetchingDividend, false)
  assert.equal(row.latestPrice, null)
  assert.equal(row.dividendRate, 0.02)
})

test('old save failure after A to B to A cannot unlock the current save', async t => {
  const old = deferred(), current = deferred()
  let writes = 0
  const h = harness(t, { handlers: { update: () => (++writes === 1 ? old.promise : current.promise) } })
  await h.view.loadFormData()
  const first = h.view.submit()
  await flush()
  h.route.params.id = '8'; await flush()
  h.route.params.id = '7'; await flush()
  const second = h.view.submit()
  await flush()
  old.reject(Error('late old save'))
  await first
  assert.equal(h.view.saving.value, true)
  assert.equal(h.messages.some(m => String(m.message).includes('late old save')), false)
  current.resolve({}); await second
  assert.equal(h.view.saving.value, false)
})
