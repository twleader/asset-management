/** 單一手動更新工作的輪詢；期限涵蓋 POST、GET 與等待，不會自行重送 POST。 */
export function createTradingRadarRefreshJob({ start, status, onCompleted = async () => {}, now = () => Date.now(),
  setTimer = setTimeout, clearTimer = clearTimeout, interval = 1000, maxInterval = 2000, deadlineMs = 60000 }) {
  let disposed = false
  let active = null
  let cancelActive = null

  function run() {
    if (disposed) return Promise.resolve(null)
    if (active) return active
    active = execute().finally(() => { active = null; cancelActive = null })
    return active
  }

  async function execute() {
    const deadline = now() + deadlineMs
    const stopped = Symbol('stopped')
    let stopReason = null
    let controller = null
    let delayTimer = null
    let deadlineTimer = null
    let signalStop
    const cancellation = new Promise(resolve => { signalStop = resolve })
    const stop = reason => {
      if (stopReason) return
      stopReason = reason
      controller?.abort()
      signalStop(stopped)
    }
    const timeoutResult = () => disposed ? null : { timeout: true, completed: false }
    const checkpoint = () => {
      if (disposed) stop('disposed')
      else if (now() >= deadline) stop('timeout')
      return stopReason != null
    }
    const request = async invoke => {
      if (checkpoint()) return stopped
      controller = new AbortController()
      const signal = controller.signal
      // Promise.race also releases the UI when an adapter fails to settle after abort.
      const response = await Promise.race([Promise.resolve().then(() => invoke({ signal })), cancellation])
      controller = null
      return checkpoint() ? stopped : response
    }
    cancelActive = () => stop('disposed')
    deadlineTimer = setTimer(() => stop('timeout'), deadlineMs)
    try {
      const created = await request(start)
      if (created === stopped) return timeoutResult()
      if (!created?.jobId) throw new Error('未取得行情更新工作編號，保留原資料。')
      let delay = interval
      while (!checkpoint()) {
        try {
          const job = await request(options => status(created.jobId, options))
          if (job === stopped) return timeoutResult()
          if (job?.status === 'COMPLETED') {
            // 工作已在期限內完成；後續五區各自受 Panel 的請求期限保護。
            clearTimer(deadlineTimer)
            deadlineTimer = null
            const result = await Promise.race([onCompleted(job), cancellation])
            return result === stopped || disposed ? null : result
          }
          if (job?.status === 'FAILED') return { job, completed: false }
        } catch (error) {
          if (checkpoint()) return timeoutResult()
          if (isAbort(error) || terminal(error)) throw error
        }
        const wait = new Promise(resolve => {
          delayTimer = setTimer(resolve, Math.min(delay, maxInterval, Math.max(0, deadline - now())))
        })
        if (await Promise.race([wait, cancellation]) === stopped) return timeoutResult()
        delayTimer = null
        delay = Math.min(maxInterval, delay * 2)
      }
      return timeoutResult()
    } catch (error) {
      if (checkpoint()) return timeoutResult()
      throw error
    } finally {
      if (deadlineTimer != null) clearTimer(deadlineTimer)
      if (delayTimer != null) clearTimer(delayTimer)
      controller?.abort()
    }
  }

  function dispose() { disposed = true; cancelActive?.() }
  return { run, dispose, get active() { return active != null } }
}

function terminal(error) { return [400, 401, 403, 404].includes(error?.response?.status ?? error?.status) }
function isAbort(error) { return error?.name === 'AbortError' || error?.code === 'ERR_CANCELED' }
