function isCanceled(error) {
  return error?.name === 'AbortError' || error?.code === 'ERR_CANCELED'
}

/** 切換文件時先清除舊內容，再以 generation fence 丟棄已失效的回應。 */
export function createOpenApiContractLoader({ requests, parse, errorMessage }) {
  let generation = 0
  let controller = null

  async function load(kind, state) {
    generation += 1
    const currentGeneration = generation
    controller?.abort()
    controller = new AbortController()
    const currentController = controller

    state.loading = true
    state.error = ''
    state.contract = null
    state.activeOperation = ''

    try {
      const source = await requests[kind](currentController.signal)
      if (currentGeneration !== generation) return false
      state.contract = parse(source)
      return true
    } catch (error) {
      if (currentGeneration !== generation || isCanceled(error)) return false
      state.contract = null
      state.error = errorMessage(error)
      return false
    } finally {
      if (currentGeneration === generation) state.loading = false
    }
  }

  function clear(state) {
    generation += 1
    controller?.abort()
    controller = null
    state.loading = false
    state.error = ''
    state.contract = null
    state.activeOperation = ''
  }

  return { load, clear }
}
