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

const UI_CHOICE_PREFIX = 'ui-choice:'
const CHOICE_HUB_X = 920
const CHOICE_OPTION_X = 1180
const CHOICE_OPTION_GAP = 80
const CHOICE_GROUP_GAP = 120
const GRAPH_BOTTOM_PADDING = 160
const TOOL_NAME = /^[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}$/
const PUBLIC_CHOICE_SUMMARY_FIELDS = ['title', 'selectedOptionId', 'options']
const PUBLIC_CHOICE_OPTION_SUMMARY_FIELDS = ['optionId', 'label', 'toolName', 'selected']
const PRIVATE_CHOICE_MARKER = /(?:private[ \t\r\n\f\x0B_-]*reasoning|chain[ \t\r\n\f\x0B._-]*of[ \t\r\n\f\x0B._-]*thought|(?:^|[,{[ \t\r\n\f\x0B"'])?(?:reasoning|analysis|thinking|thoughts?)[ \t\r\n\f\x0B]*["']?[ \t\r\n\f\x0B]*[:=：]|<[ \t\r\n\f\x0B]*\/?[ \t\r\n\f\x0B]*(?:analysis|think|thinking|reasoning)[ \t\r\n\f\x0B]*>|(?:BEGIN|END)[ \t\r\n\f\x0B._-]+(?:PRIVATE|CHAIN[ \t\r\n\f\x0B._-]*OF[ \t\r\n\f\x0B._-]*THOUGHT|REASONING)|私人推理|內部思考|思考鏈)/i
const UNSAFE_PUBLIC_CHOICE_TEXT = /(?:https?:\/\/|[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}|\bauthorization\b|\bbearer\b|\bapi[_ -]?key\b|\bpassword\b|\bsecret\b|\btoken\b|\$\(|```|<\s*script|持股|帳號|[0-9][,0-9]*(?:元|美元|股|張)|(?:NT\s*\$|US\s*\$|\b(?:USD|TWD|NTD|EUR|JPY)\b|[$€£¥])\s*[-+]?\s*\d|\d[\d,]*(?:\.\d+)?\s*\b(?:USD|TWD|NTD|EUR|JPY)\b|\b(?:parameters?|arguments?|input)\b\s*["']?\s*[:=：]|\b(?:raw[_ -]*(?:input|command|shell|response|api|mime|reasoning)|command|cmd|shell)\b\s*["']?\s*[:=：]|(?:<[ \t\r\n\f\x0B]*\/?[ \t\r\n\f\x0B]*(?:analysis|think|thinking|reasoning)[ \t\r\n\f\x0B]*>|(?:^|\n)[ \t\r\n\f\x0B]*(?:analysis|thinking|reasoning|private[ \t\r\n\f\x0B_-]*(?:analysis|reasoning)|chain[ \t\r\n\f\x0B._-]*of[ \t\r\n\f\x0B._-]*thought|hidden[ \t\r\n\f\x0B_-]*reasoning)[ \t\r\n\f\x0B]*[:=]|\b(?:BEGIN|END)[ \t\r\n\f\x0B._-]+(?:PRIVATE|CHAIN[ \t\r\n\f\x0B._-]*OF[ \t\r\n\f\x0B._-]*THOUGHT|REASONING)|(?:私人推理|內部思考|思考鏈)[ \t\r\n\f\x0B]*[:=：])|(?:^|\n)\s*(?:curl|wget|bash|zsh|sh|pwsh|powershell|python(?:3)?|node|cat|echo|printf)\s+\S|(?:\/Users\/|\/private\/|\/tmp\/|~\/|[A-Z]:\\)|\d{6,})/i
const SENSITIVE_FIELD_SEMANTIC = /(?:\b(?:price|amount|balance|holdings?|account|acct|position|quantity|qty|cost|shares?|lots?|portfolio|cash|market_value|total_value)\b\s*["']?\s*[:=：]|\b(?:price|amount|balance|holdings?|account|acct|position|quantity|qty|cost|shares?|lots?|portfolio|cash|market_value|total_value)\b\s*[-+]?\s*\d|(?:成本|金額|餘額|持倉|部位|股數|張數|單價|帳戶|帳號)\s*["']?\s*[:=：]?\s*\d|\b(?:NT\$|USD|TWD)\s*\d)/i

function finiteCoordinate(value, fallback) {
  const coordinate = Number(value)
  return Number.isFinite(coordinate) ? coordinate : fallback
}

function hasLoneSurrogate(value) {
  for (let index = 0; index < value.length; index += 1) {
    const codeUnit = value.charCodeAt(index)
    if (codeUnit >= 0xD800 && codeUnit <= 0xDBFF) {
      const next = value.charCodeAt(index + 1)
      // `charCodeAt` returns NaN past the end; express the valid-pair case
      // positively so an unpaired final high surrogate cannot pass through.
      if (!(next >= 0xDC00 && next <= 0xDFFF)) return true
      index += 1
    } else if (codeUnit >= 0xDC00 && codeUnit <= 0xDFFF) {
      return true
    }
  }
  return false
}

function hasExactOwnFields(value, allowedFields) {
  if (!value || typeof value !== 'object' || Array.isArray(value) || Object.getOwnPropertySymbols(value).length !== 0) return false
  const fields = Object.getOwnPropertyNames(value)
  return fields.length === allowedFields.length && fields.every(field => allowedFields.includes(field))
}

/** Summary text is public display metadata, never the Task 501 parameter exception. */
function isSafePublicChoiceText(value) {
  if (typeof value !== 'string' || value.trim() === '' || hasLoneSurrogate(value) || /[\u0000-\u001F\u007F]/.test(value)) return false
  if ([...value].length < 1 || [...value].length > 100) return false
  return !PRIVATE_CHOICE_MARKER.test(value) && !UNSAFE_PUBLIC_CHOICE_TEXT.test(value) && !SENSITIVE_FIELD_SEMANTIC.test(value)
}

/**
 * Graph data must never retain a parameter-bearing field.  Keep only the
 * public Node read-model rather than spreading an untrusted future field into
 * ECharts or its tooltip data.
 */
function publicRawNode(node) {
  const operation = node.operation && typeof node.operation === 'object' && !Array.isArray(node.operation)
    ? {
        method: typeof node.operation.method === 'string' ? node.operation.method : null,
        path: typeof node.operation.path === 'string' ? node.operation.path : null
      }
    : null
  const evidenceRefs = Array.isArray(node.evidenceRefs)
    ? node.evidenceRefs.map(evidence => ({
        role: typeof evidence?.role === 'string' ? evidence.role : null,
        id: typeof evidence?.id === 'string' ? evidence.id : null,
        hash: typeof evidence?.hash === 'string' ? evidence.hash : null
      }))
    : []
  const parentEventIds = Array.isArray(node.parentEventIds)
    ? node.parentEventIds.filter(parentEventId => typeof parentEventId === 'string')
    : []
  return {
    eventId: node.eventId,
    sequence: node.sequence,
    stepId: node.stepId,
    eventKind: node.eventKind,
    status: node.status,
    occurredAt: node.occurredAt,
    title: node.title,
    summary: node.summary,
    operation,
    reasonCode: node.reasonCode,
    attemptNo: node.attemptNo,
    parentEventIds,
    relation: node.relation,
    evidenceRefs,
    reportedOutcome: node.reportedOutcome,
    statusLabel: node.statusLabel,
    label: node.label,
    categoryLabel: node.categoryLabel,
    timeLabel: node.timeLabel,
    durationMillis: node.durationMillis,
    x: node.x,
    y: node.y,
    color: node.color
  }
}

/** The BFF owns validation; this defensive gate makes a hostile detail safe to render. */
function choiceSummaryForGraph(node) {
  const summary = node?.choiceSetSummary
  if (node?.eventKind !== 'START' || !hasExactOwnFields(summary, PUBLIC_CHOICE_SUMMARY_FIELDS)) return null
  if (!isSafePublicChoiceText(summary.title) || typeof summary.selectedOptionId !== 'string' || !TOOL_NAME.test(summary.selectedOptionId) || !Array.isArray(summary.options)) return null
  if (summary.options.length < 2 || summary.options.length > 8) return null

  const optionIds = new Set()
  const options = []
  let selectedCount = 0
  for (const option of summary.options) {
    if (!hasExactOwnFields(option, PUBLIC_CHOICE_OPTION_SUMMARY_FIELDS)) return null
    if (typeof option.optionId !== 'string' || !TOOL_NAME.test(option.optionId) || optionIds.has(option.optionId)) return null
    if (!isSafePublicChoiceText(option.label) || typeof option.toolName !== 'string' || !TOOL_NAME.test(option.toolName) || typeof option.selected !== 'boolean') return null
    const selected = option.optionId === summary.selectedOptionId
    if (option.selected !== selected) return null
    if (selected) selectedCount += 1
    optionIds.add(option.optionId)
    options.push({
      optionId: option.optionId,
      label: option.label,
      toolName: option.toolName,
      selected
    })
  }
  if (selectedCount !== 1) return null
  return { title: summary.title, options }
}

function choiceHubId(eventId) {
  return `${UI_CHOICE_PREFIX}${eventId}:hub`
}

function choiceOptionId(eventId, optionId) {
  return `${UI_CHOICE_PREFIX}${eventId}:option:${optionId}`
}

function choiceSyntheticNode({ id, parentEventId, name, graphLabel, x, y, color, tooltipText, kind }) {
  return {
    id,
    name,
    graphLabel,
    x,
    y,
    itemStyle: { color },
    statusLabel: '',
    categoryLabel: '公開候選分支',
    timeLabel: '',
    durationMillis: null,
    tooltipText,
    isSynthetic: true,
    syntheticKind: kind,
    parentEventId,
    // UI-only marker; never resembles a persisted event or carries parameters.
    rawNode: { eventId: parentEventId, uiOnly: true }
  }
}

/**
 * 只把 BFF 已計算的 graph 欄位映射到 ECharts graph series。
 * 公開候選僅是 UI synthetic nodes：它們不會寫回 detail、timeline 或 API identity。
 */
export function mapAiFlowGraphData(detail) {
  const entries = Array.isArray(detail?.nodes) ? detail.nodes.map((node, index) => {
    const safeNode = node && typeof node === 'object' ? node : {}
    const mapped = {
      id: safeNode.eventId,
      name: safeNode.label,
      graphLabel: [displayUnknown(safeNode.label), displayUnknown(safeNode.statusLabel)].join('\n'),
      x: finiteCoordinate(safeNode.x, (index % 4) * 220),
      y: finiteCoordinate(safeNode.y, index * 100),
      itemStyle: { color: safeNode.color },
      statusLabel: safeNode.statusLabel,
      categoryLabel: safeNode.categoryLabel,
      timeLabel: safeNode.timeLabel,
      durationMillis: safeNode.durationMillis,
      tooltipText: [
        displayUnknown(safeNode.label),
        displayUnknown(safeNode.statusLabel),
        displayUnknown(safeNode.categoryLabel),
        displayUnknown(safeNode.timeLabel),
        displayDurationMillis(safeNode.durationMillis),
        displayUnknown(safeNode.summary)
      ].join('\n'),
      rawNode: publicRawNode(safeNode)
    }
    return { mapped, choiceSummary: choiceSummaryForGraph(safeNode) }
  }) : []

  const groups = entries
    .filter(entry => entry.choiceSummary && typeof entry.mapped.id === 'string' && entry.mapped.id !== '')
    .sort((left, right) => left.mapped.y - right.mapped.y || left.mapped.id.localeCompare(right.mapped.id))

  let cursor = entries.reduce((maximum, entry) => Math.max(maximum, entry.mapped.y), 0) + CHOICE_GROUP_GAP
  const choiceGroups = new Map()
  const syntheticNodes = []
  const syntheticEdges = []

  for (const entry of groups) {
    const eventId = entry.mapped.id
    const summary = entry.choiceSummary
    const hubId = choiceHubId(eventId)
    const hubY = cursor + (summary.options.length - 1) * (CHOICE_OPTION_GAP / 2)
    choiceGroups.set(eventId, { hubId })
    syntheticNodes.push(choiceSyntheticNode({
      id: hubId,
      parentEventId: eventId,
      name: summary.title,
      graphLabel: `公開候選\n${summary.title}`,
      x: CHOICE_HUB_X,
      y: hubY,
      color: '#cbd5e1',
      tooltipText: `公開候選\n${summary.title}`,
      kind: 'choice-hub'
    }))

    summary.options.forEach((option, index) => {
      const optionNodeId = choiceOptionId(eventId, option.optionId)
      const selected = option.selected === true
      syntheticNodes.push(choiceSyntheticNode({
        id: optionNodeId,
        parentEventId: eventId,
        name: option.label,
        graphLabel: `${selected ? '採用' : '未選擇'}\n${option.label}`,
        x: CHOICE_OPTION_X,
        y: cursor + index * CHOICE_OPTION_GAP,
        color: selected ? '#bbf7d0' : '#e2e8f0',
        tooltipText: [selected ? '採用' : '未選擇', option.label, option.toolName].join('\n'),
        kind: 'choice-option'
      }))
      syntheticEdges.push({
        id: `${UI_CHOICE_PREFIX}${eventId}:edge:hub:${option.optionId}`,
        source: hubId,
        target: optionNodeId,
        relation: 'PUBLIC_CHOICE_OPTION',
        label: selected ? '候選' : '未選擇',
        curveness: 0,
        tooltipText: selected ? '候選' : '未選擇'
      })
      if (selected) {
        syntheticEdges.push({
          id: `${UI_CHOICE_PREFIX}${eventId}:edge:adopted:${option.optionId}`,
          source: optionNodeId,
          target: eventId,
          relation: 'PUBLIC_CHOICE_ADOPTED',
          label: '採用',
          curveness: 0,
          tooltipText: '採用'
        })
      }
    })
    cursor += summary.options.length * CHOICE_OPTION_GAP + CHOICE_GROUP_GAP
  }

  const edges = (Array.isArray(detail?.edges) ? detail.edges : []).map(edge => {
    const safeEdge = edge && typeof edge === 'object' ? edge : {}
    return {
      id: safeEdge.id,
      source: safeEdge.source,
      target: choiceGroups.get(safeEdge.target)?.hubId || safeEdge.target,
      relation: safeEdge.relation,
      label: safeEdge.label,
      curveness: safeEdge.curveness,
      tooltipText: safeEdge.label
    }
  })
  const nodes = [...entries.map(entry => entry.mapped), ...syntheticNodes]
  const maxY = nodes.reduce((maximum, node) => Math.max(maximum, finiteCoordinate(node.y, 0)), 0)

  return { nodes, edges: [...edges, ...syntheticEdges], graphHeight: Math.max(700, maxY + GRAPH_BOTTOM_PADDING) }
}

/** A synthetic graph node always selects its real persisted START event. */
export function resolveAiFlowGraphNodeSelection(graphData, nodeId) {
  const node = graphData?.nodes?.find(candidate => candidate.id === nodeId)
  if (node?.isSynthetic) return { eventId: node.parentEventId || '', fromSynthetic: true }
  return { eventId: typeof nodeId === 'string' && !nodeId.startsWith(UI_CHOICE_PREFIX) ? nodeId : '', fromSynthetic: false }
}

export function resolveAiFlowGraphNodeId(graphData, nodeId) {
  return resolveAiFlowGraphNodeSelection(graphData, nodeId).eventId
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
    selectedNodeId: '',
    selectedToolCall: null,
    selectedToolCallLoading: false,
    selectedToolCallError: '',
    selectedChoiceSet: null,
    selectedChoiceSetLoading: false,
    selectedChoiceSetError: ''
  }
}

function isCanceled(error) {
  return error?.name === 'AbortError' || error?.code === 'ERR_CANCELED'
}

/** 以各自的 generation fence 保證已失效的清單、明細或節點按需資料永不回填。 */
export function createAiFlowViewLoader({ listRuns, getRun, getToolCall, getChoiceSet, errorMessage = error => error?.message || '查詢失敗，請稍後再試。' }) {
  let listGeneration = 0
  let detailGeneration = 0
  let toolCallGeneration = 0
  let choiceSetGeneration = 0
  let listController = null
  let detailController = null
  let toolCallController = null
  let choiceSetController = null

  function clearSelectedToolCall(state) {
    toolCallGeneration += 1
    toolCallController?.abort()
    toolCallController = null
    state.selectedToolCall = null
    state.selectedToolCallLoading = false
    state.selectedToolCallError = ''
  }

  function clearSelectedChoiceSet(state) {
    choiceSetGeneration += 1
    choiceSetController?.abort()
    choiceSetController = null
    state.selectedChoiceSet = null
    state.selectedChoiceSetLoading = false
    state.selectedChoiceSetError = ''
  }

  function clearSelectedNode(state) {
    state.selectedNodeId = ''
    clearSelectedToolCall(state)
    clearSelectedChoiceSet(state)
  }

  function clearDetail(state) {
    detailGeneration += 1
    detailController?.abort()
    detailController = null
    clearSelectedToolCall(state)
    clearSelectedChoiceSet(state)
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
    clearSelectedToolCall(state)
    clearSelectedChoiceSet(state)

    try {
      const result = await getRun(run.runId, {
        date: filters.date,
        consumer: filters.consumer,
        signal: detailController.signal
      })
      if (generation !== detailGeneration || parentGeneration !== listGeneration || state.selectedRunId !== run.runId) return false
      state.detail = result && Array.isArray(result.nodes)
        ? {
            ...result,
            nodes: result.nodes.map(node => {
              const { toolCall: _toolCall, choiceSet: _choiceSet, ...publicNode } = node || {}
              return { ...publicNode, toolCall: null }
            })
          }
        : result
      return true
    } catch (error) {
      if (generation !== detailGeneration || parentGeneration !== listGeneration || isCanceled(error)) return false
      state.detailError = errorMessage(error)
      return false
    } finally {
      if (generation === detailGeneration && parentGeneration === listGeneration) state.detailLoading = false
    }
  }

  function loadSelectedToolCall(state, eventId, filters, parentListGeneration, parentDetailGeneration, runId) {
    if (typeof getToolCall !== 'function') return Promise.resolve(false)
    const generation = toolCallGeneration
    const controller = new AbortController()
    toolCallController = controller
    state.selectedToolCallLoading = true

    return getToolCall(runId, eventId, {
      date: filters.date,
      consumer: filters.consumer,
      signal: controller.signal
    }).then(result => {
      if (generation !== toolCallGeneration || parentListGeneration !== listGeneration ||
        parentDetailGeneration !== detailGeneration || state.selectedRunId !== runId || state.selectedNodeId !== eventId) return false
      state.selectedToolCall = result?.toolCall ?? null
      return true
    }).catch(error => {
      if (generation !== toolCallGeneration || parentListGeneration !== listGeneration ||
        parentDetailGeneration !== detailGeneration || isCanceled(error)) return false
      state.selectedToolCallError = errorMessage(error)
      return false
    }).finally(() => {
      if (generation === toolCallGeneration && parentListGeneration === listGeneration &&
        parentDetailGeneration === detailGeneration) state.selectedToolCallLoading = false
    })
  }

  function loadSelectedChoiceSet(state, eventId, filters, parentListGeneration, parentDetailGeneration, runId) {
    if (typeof getChoiceSet !== 'function') return Promise.resolve(false)
    const generation = choiceSetGeneration
    const controller = new AbortController()
    choiceSetController = controller
    state.selectedChoiceSetLoading = true

    return getChoiceSet(runId, eventId, {
      date: filters.date,
      consumer: filters.consumer,
      signal: controller.signal
    }).then(result => {
      if (generation !== choiceSetGeneration || parentListGeneration !== listGeneration ||
        parentDetailGeneration !== detailGeneration || state.selectedRunId !== runId || state.selectedNodeId !== eventId) return false
      state.selectedChoiceSet = result?.choiceSet ?? null
      return true
    }).catch(error => {
      if (generation !== choiceSetGeneration || parentListGeneration !== listGeneration ||
        parentDetailGeneration !== detailGeneration || isCanceled(error)) return false
      state.selectedChoiceSetError = errorMessage(error)
      return false
    }).finally(() => {
      if (generation === choiceSetGeneration && parentListGeneration === listGeneration &&
        parentDetailGeneration === detailGeneration) state.selectedChoiceSetLoading = false
    })
  }

  async function selectNode(state, eventId, filters = state.filters, { forceSelect = false } = {}) {
    if (!eventId) {
      clearSelectedNode(state)
      return false
    }
    if (state.selectedNodeId === eventId && forceSelect) return true
    if (state.selectedNodeId === eventId) {
      clearSelectedNode(state)
      return false
    }

    clearSelectedToolCall(state)
    clearSelectedChoiceSet(state)
    state.selectedNodeId = eventId
    if (!state.selectedRunId) return false

    const parentListGeneration = listGeneration
    const parentDetailGeneration = detailGeneration
    const runId = state.selectedRunId
    const outcomes = await Promise.all([
      loadSelectedToolCall(state, eventId, filters, parentListGeneration, parentDetailGeneration, runId),
      loadSelectedChoiceSet(state, eventId, filters, parentListGeneration, parentDetailGeneration, runId)
    ])
    return outcomes.some(Boolean)
  }

  return { query, loadDetail, clearDetail, selectNode, clearSelectedNode, clear }
}
