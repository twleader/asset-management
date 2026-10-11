const ISO_DATE = /^(\d{4})-(\d{2})-(\d{2})$/

function dateParts(value) {
  const match = typeof value === 'string' ? value.match(ISO_DATE) : null
  if (!match) return null
  const [, yearText, monthText, dayText] = match
  const year = Number(yearText)
  const month = Number(monthText)
  const day = Number(dayText)
  const date = new Date(Date.UTC(year, month - 1, day))
  if (date.getUTCFullYear() !== year || date.getUTCMonth() !== month - 1 || date.getUTCDate() !== day) return null
  return { year, month, day }
}

export function isIsoDate(value) {
  return dateParts(value) !== null
}

/** 以台北時區取得今天，不受瀏覽器設定時區影響。 */
export function todayTaipeiISO(now = new Date()) {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Taipei',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit'
  }).formatToParts(now)
  const values = Object.fromEntries(parts.map(part => [part.type, part.value]))
  return `${values.year}-${values.month}-${values.day}`
}

/** 日期控件的本地曆日運算；不把 Date 當作時間戳送到 API。 */
export function addIsoDateDays(value, days) {
  const parts = dateParts(value)
  if (!parts || !Number.isInteger(days)) return ''
  const date = new Date(Date.UTC(parts.year, parts.month - 1, parts.day + days))
  return [date.getUTCFullYear(), String(date.getUTCMonth() + 1).padStart(2, '0'), String(date.getUTCDate()).padStart(2, '0')].join('-')
}

export function fallbackAiFlowEarliestDate(today = todayTaipeiISO()) {
  return addIsoDateDays(today, -89)
}

export function isDateOutsideWindow(value, earliestDate, latestDate) {
  if (!isIsoDate(value)) return true
  return value < earliestDate || value > latestDate
}

export function displayUnknown(value) {
  return value == null || value === '' ? '未記錄' : String(value)
}

export function displayDurationMillis(value) {
  if (value == null) return '未記錄'
  const minutes = Math.floor(value / 60000)
  const seconds = Math.floor((value % 60000) / 1000)
  const milliseconds = value % 1000
  return `${minutes} 分 ${seconds} 秒 ${milliseconds} 毫秒`
}

/** 只把 BFF 已計算的 graph 欄位映射到 ECharts graph series。 */
export function mapAiFlowGraphData(detail) {
  const nodes = Array.isArray(detail?.nodes) ? detail.nodes.map(node => ({
    id: node.eventId,
    name: node.label,
    graphLabel: [displayUnknown(node.label), displayUnknown(node.statusLabel)].join('\n'),
    x: node.x,
    y: node.y,
    itemStyle: { color: node.color },
    statusLabel: node.statusLabel,
    categoryLabel: node.categoryLabel,
    timeLabel: node.timeLabel,
    durationMillis: node.durationMillis,
    tooltipText: [
      displayUnknown(node.label),
      displayUnknown(node.statusLabel),
      displayUnknown(node.categoryLabel),
      displayUnknown(node.timeLabel),
      displayDurationMillis(node.durationMillis),
      displayUnknown(node.summary)
    ].join('\n'),
    rawNode: node
  })) : []

  const edges = Array.isArray(detail?.edges) ? detail.edges.map(edge => ({
    id: edge.id,
    source: edge.source,
    target: edge.target,
    relation: edge.relation,
    label: edge.label,
    curveness: edge.curveness,
    tooltipText: edge.label
  })) : []

  return { nodes, edges }
}

export function createAiFlowViewState(filters = {}) {
  return {
    filters: { date: filters.date || '', consumer: filters.consumer || 'Codex', period: filters.period || 'ALL' },
    listLoading: false,
    listError: '',
    runs: [],
    earliestDate: '',
    detailLoading: false,
    detailError: '',
    detail: null,
    selectedRunId: '',
    selectedNodeId: ''
  }
}

function isCanceled(error) {
  return error?.name === 'AbortError' || error?.code === 'ERR_CANCELED'
}

/** 以 generation fence 保證已失效的清單或明細回應永遠不會回填畫面。 */
export function createAiFlowViewLoader({ listRuns, getRun, errorMessage = error => error?.message || '查詢失敗，請稍後再試。' }) {
  let listGeneration = 0
  let detailGeneration = 0
  let listController = null
  let detailController = null

  function clearDetail(state) {
    detailGeneration += 1
    detailController?.abort()
    detailController = null
    state.detail = null
    state.detailError = ''
    state.detailLoading = false
    state.selectedRunId = ''
    state.selectedNodeId = ''
  }

  function clear(state) {
    listGeneration += 1
    listController?.abort()
    listController = null
    clearDetail(state)
    state.listLoading = false
    state.listError = ''
    state.runs = []
    state.earliestDate = ''
  }

  async function query(state, filters) {
    listGeneration += 1
    const generation = listGeneration
    listController?.abort()
    listController = new AbortController()
    clearDetail(state)
    state.filters = { date: filters.date, consumer: filters.consumer, period: filters.period }
    state.listError = ''
    state.runs = []
    state.earliestDate = ''
    state.listLoading = true

    try {
      const result = await listRuns({ ...state.filters, signal: listController.signal })
      if (generation !== listGeneration) return false
      state.runs = Array.isArray(result?.runs) ? result.runs : []
      state.earliestDate = result?.earliestDate || ''
      return true
    } catch (error) {
      if (generation !== listGeneration || isCanceled(error)) return false
      state.runs = []
      state.listError = errorMessage(error)
      return false
    } finally {
      if (generation === listGeneration) state.listLoading = false
    }
  }

  async function loadDetail(state, run, filters = state.filters) {
    detailGeneration += 1
    const generation = detailGeneration
    const parentGeneration = listGeneration
    detailController?.abort()
    detailController = new AbortController()
    state.detail = null
    state.detailError = ''
    state.detailLoading = true
    state.selectedRunId = run.runId
    state.selectedNodeId = ''

    try {
      const result = await getRun(run.runId, {
        date: filters.date,
        consumer: filters.consumer,
        signal: detailController.signal
      })
      if (generation !== detailGeneration || parentGeneration !== listGeneration || state.selectedRunId !== run.runId) return false
      state.detail = result
      return true
    } catch (error) {
      if (generation !== detailGeneration || parentGeneration !== listGeneration || isCanceled(error)) return false
      state.detailError = errorMessage(error)
      return false
    } finally {
      if (generation === detailGeneration && parentGeneration === listGeneration) state.detailLoading = false
    }
  }

  function selectNode(state, eventId) {
    state.selectedNodeId = state.selectedNodeId === eventId ? '' : eventId
  }

  return { query, loadDetail, clearDetail, selectNode, clear }
}
