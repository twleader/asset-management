/**
 * SnapshotForm edit panels 的可測載入生命週期。
 * request 必須回傳 BFF envelope；stateFactory 可傳 Vue reactive，讓 view 直接渲染。
 */
export function createSnapshotFormPanelLoader({ request, stateFactory = value => value, onData = () => {}, onSettled = () => {} }) {
  const panels = ['basic', 'deposits', 'stocks', 'funds']
  let disposed = false
  let routeId = null
  let reloadGeneration = 0
  const states = stateFactory(Object.fromEntries(panels.map(panel => [panel, {
    data: null, loading: false, error: null, empty: false, generation: 0, controller: null
  }])))

  const abort = state => { state.controller?.abort(); state.controller = null }
  const current = (state, generation, target) => !disposed && reloadGeneration === target && state.generation === generation
  const clear = state => { state.data = null; state.error = null; state.empty = false }

  async function run(panel, { preserve = false, target = reloadGeneration } = {}) {
    const state = states[panel]
    if (!state || disposed) return null
    abort(state)
    state.generation += 1
    const generation = state.generation
    if (!preserve) clear(state)
    state.loading = true
    const controller = new AbortController()
    state.controller = controller
    try {
      const envelope = await request(panel, routeId, { signal: controller.signal })
      if (!current(state, generation, target)) return null
      validateEnvelope(panel, routeId, envelope)
      onData(panel, envelope, { target, generation })
      state.data = envelope
      state.error = null
      state.empty = isPanelEmpty(panel, envelope)
      return envelope
    } catch (error) {
      if (!current(state, generation, target) || isAbort(error)) return null
      state.error = error
      state.empty = false
      return null
    } finally {
      if (current(state, generation, target)) {
        state.loading = false
        state.controller = null
        onSettled(panel, { target, generation })
      }
    }
  }

  function load(id) {
    routeId = String(id)
    reloadGeneration += 1
    const target = reloadGeneration
    for (const state of Object.values(states)) { abort(state); clear(state) }
    return Promise.allSettled(panels.map(panel => run(panel, { target })))
  }

  function retry(panel) { return run(panel, { preserve: true }) }
  function dispose() {
    disposed = true
    reloadGeneration += 1
    Object.values(states).forEach(state => { state.generation += 1; abort(state); state.loading = false })
  }

  return { panels, states, load, retry, dispose, get routeId() { return routeId }, get generation() { return reloadGeneration } }
}

export function panelContext(envelopes) {
  const panels = ['basic', 'deposits', 'stocks', 'funds']
  const ready = panels.every(panel => envelopes[panel]?.data && !envelopes[panel]?.loading && !envelopes[panel]?.error)
  if (!ready) return { ready: false, mismatch: false }
  const contexts = panels.map(panel => {
    const envelope = envelopes[panel].data
    return [String(envelope.snapshotId), String(envelope.data?.snapshot?.snapshotDate ?? ''), envelope.data?.snapshotVersion,
      Number(envelope.data?.effectiveUsdExchangeRate)]
  })
  const first = contexts[0]
  const valid = first[1] && first[2] && Number.isFinite(first[3]) && first[3] > 0
  const matches = valid && contexts.every(c => c[0] === first[0] && c[1] === first[1] && c[2] === first[2] && c[3] === first[3])
  return { ready: matches, mismatch: !matches, routeId: first[0], snapshotDate: first[1], snapshotVersion: first[2], effectiveUsdExchangeRate: first[3] }
}

function validateEnvelope(panel, id, envelope) {
  const data = envelope?.data
  const snapshot = data?.snapshot
  if (!envelope || envelope.panel !== panel || String(envelope.snapshotId) !== String(id)
      || !snapshot || String(snapshot.id) !== String(id)
      || !/^\d{4}-\d{2}-\d{2}$/.test(snapshot.snapshotDate ?? '')
      || typeof data.snapshotVersion !== 'string' || !data.snapshotVersion
      || !Number.isFinite(Number(data.effectiveUsdExchangeRate)) || Number(data.effectiveUsdExchangeRate) <= 0) {
    throw new Error('區塊資料不完整，請重新載入')
  }
  const required = { basic: [], deposits: ['banks', 'depositTypes', 'transitFundTypes'],
    stocks: ['brokers', 'mergedStocks', 'stockPrices'], funds: ['banks', 'fundMasters'] }
  const arrays = required[panel].map(key => data[key])
  if (panel !== 'basic') arrays.push(snapshot[panel])
  if (arrays.some(rows => !Array.isArray(rows) || rows.some(row => !row || typeof row !== 'object' || Array.isArray(row)))) {
    throw new Error('區塊明細不完整，請重新載入')
  }
  if (panel === 'stocks' && [data.marketStatus, data.transactionRates].some(value => !value || typeof value !== 'object' || Array.isArray(value))) {
    throw new Error('股票資料不完整，請重新載入')
  }
}

function isPanelEmpty(panel, envelope) {
  const snapshot = envelope?.data?.snapshot
  const key = panel === 'basic' ? null : panel
  return key ? Array.isArray(snapshot?.[key]) && snapshot[key].length === 0 : false
}

function isAbort(error) { return error?.name === 'AbortError' || error?.code === 'ERR_CANCELED' }
