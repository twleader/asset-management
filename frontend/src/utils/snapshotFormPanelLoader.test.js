import test from 'node:test'
import assert from 'node:assert/strict'
import { computed, nextTick, reactive } from 'vue'
import { createSnapshotFormPanelLoader, panelContext } from './snapshotFormPanelLoader.js'

const panels = ['basic', 'deposits', 'stocks', 'funds']
const deferred = () => {
  let resolve, reject
  const promise = new Promise((ok, fail) => { resolve = ok; reject = fail })
  return { promise, resolve, reject }
}
const envelope = (panel, id = 7, { version = 'v1', date = '2026-09-23', fx = 31.2, rows = [] } = {}) => ({
  panel, snapshotId: id, data: { snapshot: { id, snapshotDate: date, [panel]: panel === 'basic' ? undefined : rows }, snapshotVersion: version, effectiveUsdExchangeRate: fx, banks: [], brokers: [], depositTypes: [], transitFundTypes: [], fundMasters: [], mergedStocks: [], stockPrices: [], marketStatus: {}, transactionRates: {} }
})

test('四個 Panel 同步起跑，慢股票不阻擋存款，reactive ready 直到全數完成', async () => {
  const pending = new Map()
  const loader = createSnapshotFormPanelLoader({
    stateFactory: reactive,
    request(panel) { const d = deferred(); pending.set(panel, d); return d.promise }
  })
  const readiness = computed(() => panelContext(loader.states).ready)
  const all = loader.load(7)
  assert.deepEqual([...pending.keys()].sort(), [...panels].sort())
  pending.get('deposits').resolve(envelope('deposits'))
  await Promise.resolve()
  assert.equal(loader.states.deposits.data.panel, 'deposits')
  assert.equal(readiness.value, false)
  for (const panel of panels.filter(p => p !== 'deposits')) pending.get(panel).resolve(envelope(panel))
  await all
  await nextTick()
  assert.equal(readiness.value, true)
})

test('單區錯誤只重試該區，其他完整 rows 不被清除', async () => {
  const calls = []
  const pending = []
  const loader = createSnapshotFormPanelLoader({
    request(panel) { const d = deferred(); calls.push(panel); pending.push(d); return d.promise }
  })
  const first = loader.load(7)
  pending[0].reject(new Error('basic unavailable'))
  panels.slice(1).forEach((panel, index) => pending[index + 1].resolve(envelope(panel)))
  await first
  const deposits = loader.states.deposits.data
  assert.match(loader.states.basic.error.message, /unavailable/)
  const retry = loader.retry('basic')
  assert.deepEqual(calls, [...panels, 'basic'])
  pending[4].resolve(envelope('basic'))
  await retry
  assert.equal(loader.states.deposits.data, deposits)
  assert.equal(loader.states.basic.error, null)
})

test('同 id 混入不同 version、date、FX 或 child IDs 的 envelope 不能解鎖', async () => {
  const loader = createSnapshotFormPanelLoader({ request: async panel => envelope(panel, 7, { version: panel === 'funds' ? 'v2' : 'v1', rows: [{ id: panel }] }) })
  await loader.load(7)
  assert.deepEqual(panelContext(loader.states), { ready: false, mismatch: true, routeId: '7', snapshotDate: '2026-09-23', snapshotVersion: 'v1', effectiveUsdExchangeRate: 31.2 })
  const dateMismatch = reactive(Object.fromEntries(panels.map(panel => [panel, { data: envelope(panel, 7, { date: panel === 'stocks' ? '2026-09-24' : '2026-09-23' }), loading: false, error: null }])))
  assert.equal(panelContext(dateMismatch).mismatch, true)
})

test('A 到 B 時 A 的 success、error、finally 都不覆寫 B', async () => {
  const pending = []
  const loader = createSnapshotFormPanelLoader({ request(panel, id) { const d = deferred(); pending.push({ panel, id, ...d }); return d.promise } })
  const a = loader.load('A')
  const b = loader.load('B')
  pending.filter(p => p.id === 'B').forEach(p => p.resolve(envelope(p.panel, 'B')))
  await b
  pending.filter(p => p.id === 'A').forEach((p, index) => index % 2 ? p.reject(new Error('late A')) : p.resolve(envelope(p.panel, 'A')))
  await a
  for (const panel of panels) {
    assert.equal(loader.states[panel].data.snapshotId, 'B')
    assert.equal(loader.states[panel].error, null)
    assert.equal(loader.states[panel].loading, false)
  }
})

test('dispose abort 請求，晚到 finally 不可重新標記 loading 或寫資料', async () => {
  const pending = []
  const loader = createSnapshotFormPanelLoader({ request(_panel, _id, { signal }) { const d = deferred(); pending.push({ signal, ...d }); return d.promise } })
  const run = loader.load(7)
  loader.dispose()
  assert.equal(pending.every(p => p.signal.aborted), true)
  pending.forEach(p => p.resolve(envelope('basic')))
  await run
  assert.equal(panels.every(panel => loader.states[panel].data === null && loader.states[panel].loading === false), true)
})

test('legacy raw FX、fund stored values 和交易 FX 的前端映射契約留給 view', async () => {
  // 保護重要資料流的靜態邊界：有效 FX 不得參與 legacy deposit 反推，基金初始不得重算。
  const source = await import('node:fs/promises').then(fs => fs.readFile(new URL('../views/SnapshotFormView.vue', import.meta.url), 'utf8'))
  assert.match(source, /mapDepositFromApi\(d, Number\(snapshot\.usdExchangeRate\) \|\| 1\)/)
  assert.match(source, /_preserveSnapshotValue: true/)
  assert.match(source, /if \(!br\.transactionExchangeRate && Number\(rate\) > 0\) applyUsTransactionRate/)
})

