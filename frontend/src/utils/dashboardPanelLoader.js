/**
 * Dashboard 面板載入生命週期。
 *
 * 此模組刻意不依賴 Vue，讓每個面板的取消、換快照與重試規則可單獨驗證。
 * 呼叫端透過 stateFactory 傳入 reactive；每個 state 只保存該面板的完整 response。
 */
export function createDashboardPanelLoader({ panelNames, request, stateFactory = value => value, isEmpty = () => false }) {
  let disposed = false
  let snapshotId = null
  const states = stateFactory(Object.fromEntries(panelNames.map(panel => [panel, {
    data: null,
    loading: false,
    error: null,
    refreshError: null,
    empty: false,
    generation: 0,
    controller: null,
    context: {}
  }])))

  const isCurrent = (state, generation) => !disposed && state.generation === generation

  function abort(state) {
    state.controller?.abort()
    state.controller = null
  }

  function clearState(state) {
    state.data = null
    state.error = null
    state.refreshError = null
    state.empty = false
  }

  async function run(panel, { context = {}, preserve = false } = {}) {
    const state = states[panel]
    if (!state || disposed) return null

    abort(state)
    state.generation += 1
    const generation = state.generation
    state.context = context
    const hadData = state.data != null
    if (!preserve) clearState(state)
    state.loading = true
    const controller = new AbortController()
    state.controller = controller

    try {
      const payload = await request(panel, {
        snapshotId,
        context,
        signal: controller.signal
      })
      if (!isCurrent(state, generation)) return null
      state.data = payload
      state.error = null
      state.refreshError = null
      state.empty = isEmpty(panel, payload)
      return payload
    } catch (error) {
      if (!isCurrent(state, generation) || isAbort(error)) return null
      if (hadData && preserve) {
        state.refreshError = error
      } else {
        state.error = error
        state.empty = false
      }
      return null
    } finally {
      if (isCurrent(state, generation)) {
        state.loading = false
        state.controller = null
      }
    }
  }

  function select(nextSnapshotId, contexts = {}) {
    const changed = snapshotId !== nextSnapshotId
    snapshotId = nextSnapshotId
    return Promise.allSettled(panelNames.map(panel => run(panel, {
      context: contexts[panel] ?? {},
      // 同一快照的背景更新維持已完成的圖；切換快照則先清空。
      preserve: !changed
    })))
  }

  function retry(panel) {
    const state = states[panel]
    return state ? run(panel, { context: state.context, preserve: state.data != null }) : Promise.resolve(null)
  }

  // 背景輪詢只能更新開始輪詢時已完成、且之後未被切換或重試的面板。
  function update(panel, generation, transform) {
    const state = states[panel]
    if (!state?.data || !isCurrent(state, generation)) return false
    state.data = transform(state.data)
    return true
  }

  function dispose() {
    disposed = true
    Object.values(states).forEach(state => {
      state.generation += 1
      abort(state)
      state.loading = false
    })
  }

  return {
    states,
    select,
    run,
    retry,
    update,
    dispose,
    get snapshotId() { return snapshotId }
  }
}

function isAbort(error) {
  return error?.name === 'AbortError' || error?.code === 'ERR_CANCELED'
}
