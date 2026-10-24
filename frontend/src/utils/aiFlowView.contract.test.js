import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

import {
  addIsoDateDays,
  createAiFlowViewLoader,
  createAiFlowViewState,
  displayDurationMillis,
  displayUnknown,
  fallbackAiFlowEarliestDate,
  isDateOutsideWindow,
  mapAiFlowGraphData,
  resolveAiFlowGraphNodeId,
  resolveAiFlowGraphNodeSelection,
  resolveAiFlowToolStartId,
  todayTaipeiISO
} from './aiFlowView.js'

function deferred() {
  let resolve
  let reject
  const promise = new Promise((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

test('日期窗用台北日，90 天含今天；未知耗時保留為未記錄', () => {
  const now = new Date('2026-10-10T18:30:00.000Z')
  const today = todayTaipeiISO(now)
  assert.equal(today, '2026-10-11')
  assert.equal(fallbackAiFlowEarliestDate(today), '2026-07-14')
  assert.equal(addIsoDateDays('2026-10-11', -89), '2026-07-14')
  assert.equal(isDateOutsideWindow('2026-07-13', '2026-07-14', today), true)
  assert.equal(isDateOutsideWindow('2026-07-14', '2026-07-14', today), false)
  assert.equal(isDateOutsideWindow('2026-10-12', '2026-07-14', today), true)
  assert.equal(displayUnknown(null), '未記錄')
  assert.equal(displayUnknown(''), '未記錄')
  assert.equal(displayUnknown(0), '0')
  assert.equal(displayDurationMillis(null), '未記錄')
  assert.equal(displayDurationMillis(0), '0 分 0 秒 0 毫秒')
  assert.equal(displayDurationMillis(999), '0 分 0 秒 999 毫秒')
  assert.equal(displayDurationMillis(60000), '1 分 0 秒 0 毫秒')
  assert.equal(displayDurationMillis(128191), '2 分 8 秒 191 毫秒')
  assert.equal(displayDurationMillis(258301), '4 分 18 秒 301 毫秒')
})

test('時段選項保留全部並依協定順序呈現，US 會原樣送進專屬 BFF 查詢', async () => {
  const view = readFileSync(new URL('../views/AiFlowView.vue', import.meta.url), 'utf8')
  const periodSelect = view.match(/<el-select v-model="period"[\s\S]*?<\/el-select>/)?.[0]
  assert.ok(periodSelect)
  const options = [...periodSelect.matchAll(/<el-option label="([^"]+)" value="([^"]+)"\s*\/>/g)]
    .map(([, label, value]) => ({ label, value }))
  assert.deepEqual(options, [
    { label: '全部時段', value: 'ALL' },
    { label: '台股早盤', value: 'MORNING' },
    { label: '台股午盤', value: 'NOON' },
    { label: '美股', value: 'US' }
  ])
  assert.match(view, /const period = ref\('ALL'\)/)
  assert.match(view, /bffApi\.aiFlow\.runs\(\{ date, consumer: selectedConsumer, period: selectedPeriod, signal \}\)/)

  const requests = []
  const loader = createAiFlowViewLoader({
    listRuns: async request => {
      requests.push(request)
      return { runs: [{ runId: 'us-run' }] }
    },
    getRun: async () => ({})
  })
  const state = createAiFlowViewState()
  await loader.query(state, { date: '2026-10-08', consumer: 'Codex', period: 'US' })
  assert.equal(requests[0].period, 'US')
  assert.deepEqual(state.runs.map(run => run.runId), ['us-run'])
})

test('filter generation 清空舊資料並拒絕過期清單回應', async () => {
  const pending = []
  const loader = createAiFlowViewLoader({
    listRuns: request => {
      const item = deferred()
      pending.push({ ...item, request })
      return item.promise
    },
    getRun: async () => ({})
  })
  const state = createAiFlowViewState({ date: '2026-10-08' })
  state.runs = [{ runId: 'old' }]
  state.detail = { run: { runId: 'old' } }
  state.selectedRunId = 'old'

  const first = loader.query(state, { date: '2026-10-08', consumer: 'Codex', period: 'ALL' })
  assert.deepEqual(state.runs, [])
  assert.equal(state.detail, null)
  assert.equal(state.selectedRunId, '')
  const firstSignal = pending[0].request.signal

  const second = loader.query(state, { date: '2026-10-09', consumer: 'Claude', period: 'US' })
  assert.equal(firstSignal.aborted, true)
  assert.equal(pending[1].request.period, 'US')
  assert.deepEqual(state.runs, [])
  assert.equal(state.detail, null)

  pending[0].resolve({ date: '2026-10-08', earliestDate: '2026-07-12', runs: [{ runId: 'stale' }] })
  await first
  assert.deepEqual(state.runs, [])
  assert.equal(state.listLoading, true)

  pending[1].resolve({ date: '2026-10-09', earliestDate: '2026-07-13', runs: [{ runId: 'current' }] })
  await second
  assert.deepEqual(state.runs.map(run => run.runId), ['current'])
  assert.equal(state.earliestDate, '2026-07-13')
  assert.equal(state.listLoading, false)
})

test('查詢錯誤不保留上一筆資料，明細舊回應不能跨 filter 回填', async () => {
  const detailRequest = deferred()
  let listCalls = 0
  let detailArgs
  const loader = createAiFlowViewLoader({
    listRuns: async () => {
      listCalls += 1
      if (listCalls === 1) return { runs: [{ runId: 'run-1' }] }
      if (listCalls === 2) return { runs: [{ runId: 'run-2' }] }
      throw new Error('讀取失敗')
    },
    getRun: (runId, options) => {
      detailArgs = { runId, ...options }
      return detailRequest.promise
    },
    errorMessage: error => '查詢錯誤：' + error.message
  })
  const state = createAiFlowViewState()
  await loader.query(state, { date: '2026-10-08', consumer: 'Codex', period: 'ALL' })
  const detailPromise = loader.loadDetail(state, state.runs[0], state.filters)
  assert.equal(detailArgs.runId, 'run-1')
  assert.equal(detailArgs.date, '2026-10-08')
  assert.equal(detailArgs.consumer, 'Codex')
  await loader.query(state, { date: '2026-10-09', consumer: 'Claude', period: 'MORNING' })
  detailRequest.resolve({ run: { runId: 'run-1' }, nodes: [], edges: [], slowSteps: [] })
  await detailPromise
  assert.equal(state.detail, null)
  assert.equal(state.selectedRunId, '')
  assert.deepEqual(state.runs.map(run => run.runId), ['run-2'])

  state.runs = [{ runId: 'stale-error' }]
  const succeeded = await loader.query(state, { date: '2026-10-10', consumer: 'Codex', period: 'ALL' })
  assert.equal(succeeded, false)
  assert.deepEqual(state.runs, [])
  assert.equal(state.listError, '查詢錯誤：讀取失敗')
  assert.equal(state.listLoading, false)
})

test('工具參數只在節點選取後讀取，清除、切換 run/filter 後舊回應不回填，null 不算錯誤', async () => {
  const toolRequests = []
  const loader = createAiFlowViewLoader({
    listRuns: async ({ date }) => ({ runs: [{ runId: date === '2026-10-09' ? 'run-2' : 'run-1' }] }),
    getRun: async runId => ({
      run: { runId },
      nodes: [{
        eventId: 'node-1',
        toolCall: { name: 'exec', parameters: 'MUST_NOT_ENTER_DETAIL' },
        choiceSet: { options: [{ parameters: 'MUST_NOT_ENTER_DETAIL' }] }
      }],
      edges: [],
      slowSteps: []
    }),
    getToolCall: (runId, eventId, options) => {
      const request = deferred()
      toolRequests.push({ runId, eventId, ...options, ...request })
      return request.promise
    }
  })
  const state = createAiFlowViewState()
  await loader.query(state, { date: '2026-10-08', consumer: 'Codex', period: 'ALL' })
  await loader.loadDetail(state, state.runs[0], state.filters)

  assert.equal(state.detail.nodes[0].toolCall, null)
  assert.equal(Object.hasOwn(state.detail.nodes[0], 'choiceSet'), false)
  assert.equal(toolRequests.length, 0)

  const firstSelection = loader.selectNode(state, 'node-1', state.filters)
  assert.equal(toolRequests.length, 1)
  assert.equal(toolRequests[0].runId, 'run-1')
  assert.equal(toolRequests[0].date, '2026-10-08')
  assert.equal(toolRequests[0].consumer, 'Codex')
  assert.equal(state.selectedToolCallLoading, true)
  const toolCall = { name: 'exec', innerNames: [], parameters: 'SELECTED_ONLY_CANARY', parametersChars: 20, truncated: false, redactedCount: 0 }
  toolRequests[0].resolve({ runId: 'run-1', eventId: 'node-1', toolCall })
  await firstSelection
  assert.deepEqual(state.selectedToolCall, toolCall)
  assert.equal(state.selectedToolCallError, '')

  const clearedSelection = loader.selectNode(state, 'node-2', state.filters)
  const clearedSignal = toolRequests[1].signal
  loader.clearSelectedNode(state)
  assert.equal(clearedSignal.aborted, true)
  toolRequests[1].resolve({ toolCall })
  await clearedSelection
  assert.equal(state.selectedNodeId, '')
  assert.equal(state.selectedToolCall, null)

  const switchedRunSelection = loader.selectNode(state, 'node-3', state.filters)
  const switchedRunSignal = toolRequests[2].signal
  await loader.loadDetail(state, { runId: 'run-2' }, state.filters)
  assert.equal(switchedRunSignal.aborted, true)
  toolRequests[2].resolve({ toolCall })
  await switchedRunSelection
  assert.equal(state.selectedRunId, 'run-2')
  assert.equal(state.selectedToolCall, null)

  const filteredSelection = loader.selectNode(state, 'node-4', state.filters)
  const filteredSignal = toolRequests[3].signal
  await loader.query(state, { date: '2026-10-09', consumer: 'Claude', period: 'US' })
  assert.equal(filteredSignal.aborted, true)
  toolRequests[3].resolve({ toolCall })
  await filteredSelection
  assert.equal(state.selectedNodeId, '')
  assert.equal(state.selectedToolCall, null)

  await loader.loadDetail(state, state.runs[0], state.filters)
  const nullSelection = loader.selectNode(state, 'legacy-node', state.filters)
  toolRequests[4].resolve({ runId: 'run-2', eventId: 'legacy-node', toolCall: null })
  assert.equal(await nullSelection, true)
  assert.equal(state.selectedToolCall, null)
  assert.equal(state.selectedToolCallLoading, false)
  assert.equal(state.selectedToolCallError, '')

  const closedSelection = loader.selectNode(state, 'node-before-close', state.filters)
  const closedSignal = toolRequests[5].signal
  loader.clearDetail(state)
  assert.equal(closedSignal.aborted, true)
  toolRequests[5].resolve({ toolCall })
  await closedSelection
  assert.equal(state.selectedRunId, '')
  assert.equal(state.selectedToolCall, null)
})

test('ECharts 只映射 BFF layout、中文狀態、顏色與關係標籤', () => {
  const hostileText = '<img src=x onerror=alert(1)>'
  const detail = {
    nodes: [{
      eventId: 'event-1',
      label: hostileText,
      status: 'UNKNOWN',
      statusLabel: '未確認',
      categoryLabel: '工具',
      timeLabel: '09:05:03',
      durationMillis: null,
      x: 17,
      y: 29,
      color: '#123456',
      summary: '<script>不執行</script>',
      toolCall: { name: 'exec', parameters: 'TOOL_PARAMETER_CANARY' }
    }],
    edges: [{ id: 'edge-1', source: 'event-1', target: 'event-2', relation: 'NEXT_OBSERVED', label: '接續紀錄', curveness: 0.25 }]
  }
  const mapped = mapAiFlowGraphData(detail)
  assert.equal(mapped.nodes[0].id, 'event-1')
  assert.equal(mapped.nodes[0].name, hostileText)
  assert.equal(mapped.nodes[0].x, 17)
  assert.equal(mapped.nodes[0].y, 29)
  assert.equal(mapped.nodes[0].itemStyle.color, '#123456')
  assert.equal(mapped.nodes[0].durationMillis, null)
  assert.equal(Object.hasOwn(mapped.nodes[0].rawNode, 'toolCall'), false)
  assert.equal(Object.hasOwn(mapped.nodes[0], 'toolCall'), false)
  assert.doesNotMatch(JSON.stringify(mapped), /TOOL_PARAMETER_CANARY/)
  assert.match(mapped.nodes[0].tooltipText, /<img src=x onerror=alert\(1\)>/)
  assert.equal(mapped.edges[0].source, 'event-1')
  assert.equal(mapped.edges[0].target, 'event-2')
  assert.equal(mapped.edges[0].relation, 'NEXT_OBSERVED')
  assert.equal(mapped.edges[0].label, '接續紀錄')

  const view = readFileSync(new URL('../views/AiFlowView.vue', import.meta.url), 'utf8')
  assert.doesNotMatch(view, /v-html\s*=/)
  assert.match(view, /renderMode:\s*'richText'/)
  assert.match(view, /edgeSymbol:\s*\['none',\s*'arrow'\]/)
  assert.match(view, /slotLabel/)
  assert.match(view, /reportedOutcome/)
  assert.match(view, /verifiedReportOutcome/)
  assert.match(view, /traceCoverage/)
  assert.match(view, /state\.detail\.slowSteps/)
})

test('公開候選只形成 UI 分支、改接 inbound edge，並維持固定不重疊 layout', () => {
  const parameterCanary = 'CHOICE_PARAMETERS_MUST_NOT_ENTER_GRAPH'
  const node = (eventId, eventKind, x, y, choiceSetSummary) => ({
    eventId,
    eventKind,
    label: `節點 ${eventId}`,
    statusLabel: '成功',
    categoryLabel: '工具',
    timeLabel: '09:05:03',
    durationMillis: 20,
    x,
    y,
    color: '#123456',
    summary: '公開摘要',
    toolCall: { parameters: parameterCanary },
    choiceSet: { parameters: parameterCanary },
    choiceSetSummary
  })
  const summary = (title, selectedOptionId, options) => ({ title, selectedOptionId, options })
  const option = (optionId, label, toolName, selected) => ({ optionId, label, toolName, selected })
  const mapped = mapAiFlowGraphData({
    nodes: [
      node('parent', 'OBSERVATION', 0, 50),
      node('start-a', 'START', 220, 100, summary('第一組公開候選', 'a-adopt', [
        option('a-reject', '等候資料', 'wait_tool', false),
        option('a-adopt', '讀取資料', 'read_tool', true)
      ])),
      node('start-b', 'START', 440, 200, summary('第二組公開候選', 'b-adopt', [
        option('b-adopt', '採用工具', 'tool_b', true),
        option('b-reject', '略過工具', 'tool_c', false)
      ])),
      node('not-start', 'END', 660, 300, summary('不得顯示', 'no', [
        option('no', '不應出現', 'tool_no', true),
        option('no-alt', '不應出現二', 'tool_no_alt', false)
      ]))
    ],
    edges: [
      { id: 'in-a', source: 'parent', target: 'start-a', relation: 'NEXT', label: '接續紀錄', curveness: 0 },
      { id: 'in-b', source: 'start-a', target: 'start-b', relation: 'NEXT', label: '接續紀錄', curveness: 0 }
    ]
  })

  const hubA = 'ui-choice:start-a:hub'
  const hubB = 'ui-choice:start-b:hub'
  const rejectedA = 'ui-choice:start-a:option:a-reject'
  const adoptedA = 'ui-choice:start-a:option:a-adopt'
  const adoptedB = 'ui-choice:start-b:option:b-adopt'
  const rejectedB = 'ui-choice:start-b:option:b-reject'
  assert.equal(mapped.nodes.length, 10)
  assert.equal(mapped.edges.find(edge => edge.id === 'in-a').target, hubA)
  assert.equal(mapped.edges.find(edge => edge.id === 'in-b').target, hubB)
  assert.deepEqual(
    mapped.edges.find(edge => edge.id === 'ui-choice:start-a:edge:adopted:a-adopt'),
    {
      id: 'ui-choice:start-a:edge:adopted:a-adopt',
      source: adoptedA,
      target: 'start-a',
      relation: 'PUBLIC_CHOICE_ADOPTED',
      label: '採用',
      curveness: 0,
      tooltipText: '採用'
    }
  )
  assert.equal(mapped.edges.find(edge => edge.target === rejectedA).label, '未選擇')
  assert.equal(mapped.edges.find(edge => edge.target === rejectedB).label, '未選擇')
  assert.equal(mapped.edges.some(edge => edge.source === rejectedA), false)
  assert.equal(mapped.edges.some(edge => edge.source === rejectedB), false)

  const byId = Object.fromEntries(mapped.nodes.map(graphNode => [graphNode.id, graphNode]))
  assert.deepEqual([byId[hubA].x, byId[hubA].y], [920, 460])
  assert.deepEqual([byId[rejectedA].x, byId[rejectedA].y], [1180, 420])
  assert.deepEqual([byId[adoptedA].x, byId[adoptedA].y], [1180, 500])
  assert.deepEqual([byId[hubB].x, byId[hubB].y], [920, 740])
  assert.deepEqual([byId[adoptedB].x, byId[adoptedB].y], [1180, 700])
  assert.deepEqual([byId[rejectedB].x, byId[rejectedB].y], [1180, 780])
  assert.equal(mapped.nodes.some(graphNode => graphNode.id.startsWith('ui-choice:not-start:')), false)
  assert.equal(mapped.graphHeight, 940)
  assert.ok(mapped.nodes.every(graphNode => Number.isFinite(graphNode.x) && Number.isFinite(graphNode.y)))
  assert.equal(new Set(mapped.nodes.map(graphNode => `${graphNode.x}:${graphNode.y}`)).size, mapped.nodes.length)
  assert.equal(resolveAiFlowGraphNodeId(mapped, hubA), 'start-a')
  assert.equal(resolveAiFlowGraphNodeId(mapped, rejectedB), 'start-b')
  assert.equal(resolveAiFlowGraphNodeId(mapped, 'start-a'), 'start-a')
  assert.equal(resolveAiFlowGraphNodeId(mapped, 'ui-choice:unknown'), '')
  assert.doesNotMatch(JSON.stringify(mapped), /CHOICE_PARAMETERS_MUST_NOT_ENTER_GRAPH/)
  assert.doesNotMatch(JSON.stringify(mapped), /choiceSetSummary/)
  assert.doesNotMatch(JSON.stringify(mapped), /toolCall/)
})

test('不安全或不閉合的公開候選摘要整組省略，且絕不進入圖與 tooltip', () => {
  const parameterCanary = 'CHOICE_PARAMETERS_MUST_NOT_ENTER_GRAPH'
  const makeSummary = (title = '公開候選', label = '讀取資料') => ({
    title,
    selectedOptionId: 'adopt',
    options: [
      { optionId: 'adopt', label, toolName: 'read_tool', selected: true },
      { optionId: 'wait', label: '等候資料', toolName: 'wait_tool', selected: false }
    ]
  })
  const mapFor = choiceSetSummary => mapAiFlowGraphData({
    nodes: [{
      eventId: 'actual-start',
      eventKind: 'START',
      label: '實際工具節點',
      statusLabel: '成功',
      categoryLabel: '工具',
      timeLabel: '09:05:03',
      durationMillis: 10,
      x: 220,
      y: 100,
      color: '#123456',
      summary: '公開摘要',
      choiceSetSummary
    }],
    edges: []
  })
  const assertOmitted = (choiceSetSummary, forbiddenText) => {
    const mapped = mapFor(choiceSetSummary)
    assert.equal(mapped.nodes.length, 1)
    assert.equal(mapped.nodes.some(node => node.id.startsWith('ui-choice:')), false)
    if (forbiddenText) assert.doesNotMatch(JSON.stringify(mapped), new RegExp(forbiddenText.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')))
  }

  const astral100 = '🙂'.repeat(100)
  const accepted = mapFor(makeSummary(astral100, astral100))
  assert.equal(accepted.nodes.length, 4)
  assert.equal(accepted.nodes.some(node => node.id === 'ui-choice:actual-start:hub'), true)

  const rawCandidate = makeSummary()
  rawCandidate.options[0] = { ...rawCandidate.options[0], parameters: parameterCanary }
  assertOmitted(rawCandidate, parameterCanary)
  assertOmitted(makeSummary(`parameters: ${parameterCanary}`), parameterCanary)
  assertOmitted(makeSummary('private reasoning: hidden branch'), 'private reasoning')
  assertOmitted(makeSummary('公開候選', 'analysis: hidden branch'), 'analysis: hidden')
  assertOmitted(makeSummary('公開候選', 'price: 123'), 'price: 123')
  assertOmitted(makeSummary('公開候選\u0001'), '公開候選')
  assertOmitted(makeSummary('公開候選', '\uD800'), '')
  assertOmitted(makeSummary('🙂'.repeat(101)), '🙂')
  const markerVectors = JSON.parse(readFileSync(
    new URL('../../../scripts/tests/fixtures/ai_flow_choice_set_vectors.json', import.meta.url), 'utf8'
  ))
  const separatorCanaries = markerVectors.cases.filter(vector => typeof vector.marker === 'string')
  assert.deepEqual(separatorCanaries.map(vector => vector.id), [
    'private-choice-parameters-lf',
    'private-choice-parameters-tab',
    'chain-choice-parameters-lf',
    'begin-private-choice-parameters-lf'
  ])
  for (const vector of separatorCanaries) {
    assertOmitted(makeSummary(vector.marker), vector.marker)
  }
  const classifierSource = readFileSync(new URL('./aiFlowView.js', import.meta.url), 'utf8')
  assert.ok(classifierSource.includes('private[ \\t\\r\\n\\f\\x0B_-]*reasoning'))
  assert.ok(classifierSource.includes('chain[ \\t\\r\\n\\f\\x0B._-]*of[ \\t\\r\\n\\f\\x0B._-]*thought'))
  assertOmitted({ ...makeSummary(), selectedOptionId: 'not valid' }, '公開候選')
  const invalidTool = makeSummary()
  invalidTool.options[0] = { ...invalidTool.options[0], toolName: 'not valid' }
  assertOmitted(invalidTool, '公開候選')
})

test('synthetic 點擊以 fromSynthetic 強制保留已選的實際 parent，不產生 synthetic API 請求', async () => {
  const graphData = mapAiFlowGraphData({
    nodes: [{
      eventId: 'actual-start', eventKind: 'START', label: '實際工具節點', statusLabel: '成功', categoryLabel: '工具',
      timeLabel: '09:05:03', durationMillis: 10, x: 220, y: 100, color: '#123456', summary: '公開摘要',
      choiceSetSummary: {
        title: '公開候選', selectedOptionId: 'adopt', options: [
          { optionId: 'adopt', label: '讀取資料', toolName: 'read_tool', selected: true },
          { optionId: 'wait', label: '等候資料', toolName: 'wait_tool', selected: false }
        ]
      }
    }],
    edges: []
  })
  const synthetic = resolveAiFlowGraphNodeSelection(graphData, 'ui-choice:actual-start:option:wait')
  assert.deepEqual(synthetic, { eventId: 'actual-start', fromSynthetic: true })
  assert.deepEqual(resolveAiFlowGraphNodeSelection(graphData, 'actual-start'), { eventId: 'actual-start', fromSynthetic: false })

  let requests = 0
  const loader = createAiFlowViewLoader({
    listRuns: async () => ({ runs: [] }),
    getRun: async () => ({}),
    getToolCall: async () => { requests += 1; return { toolCall: null } },
    getChoiceSet: async () => { requests += 1; return { choiceSet: null } }
  })
  const state = createAiFlowViewState({ date: '2026-10-21' })
  state.selectedRunId = 'run-1'
  state.selectedNodeId = 'actual-start'
  assert.equal(await loader.selectNode(state, synthetic.eventId, state.filters, { forceSelect: synthetic.fromSynthetic }), true)
  assert.equal(state.selectedNodeId, 'actual-start')
  assert.equal(requests, 0)
  assert.equal(await loader.selectNode(state, 'actual-start', state.filters), false)
  assert.equal(state.selectedNodeId, '')
})

test('工具與公開候選 detail 同時請求，兩者各自 abort、stale fence 與錯誤狀態隔離', async () => {
  const toolRequests = []
  const choiceRequests = []
  const loader = createAiFlowViewLoader({
    listRuns: async () => ({ runs: [{ runId: 'run-1' }] }),
    getRun: async () => ({ run: { runId: 'run-1' }, nodes: [], edges: [], slowSteps: [] }),
    getToolCall: (runId, eventId, options) => {
      const request = deferred()
      toolRequests.push({ runId, eventId, ...options, ...request })
      return request.promise
    },
    getChoiceSet: (runId, eventId, options) => {
      const request = deferred()
      choiceRequests.push({ runId, eventId, ...options, ...request })
      return request.promise
    },
    errorMessage: error => `錯誤：${error.message}`
  })
  const state = createAiFlowViewState()
  await loader.query(state, { date: '2026-10-21', consumer: 'Codex', period: 'ALL' })
  await loader.loadDetail(state, state.runs[0], state.filters)

  const initial = loader.selectNode(state, 'actual-start', state.filters)
  assert.equal(toolRequests.length, 1)
  assert.equal(choiceRequests.length, 1)
  assert.equal(toolRequests[0].eventId, 'actual-start')
  assert.equal(choiceRequests[0].eventId, 'actual-start')
  assert.equal(state.selectedToolCallLoading, true)
  assert.equal(state.selectedChoiceSetLoading, true)
  const toolCall = { name: 'read_tool', innerNames: [], parameters: 'TOOL_DETAIL_ONLY', parametersChars: 16, truncated: false, redactedCount: 0 }
  const choiceSet = {
    title: '公開候選',
    selectedOptionId: 'adopt',
    options: [{ optionId: 'adopt', label: '讀取', toolName: 'read_tool', parameters: 'CHOICE_DETAIL_ONLY', parametersChars: 18, truncated: false, redactedCount: 0 }]
  }
  choiceRequests[0].resolve({ runId: 'run-1', eventId: 'actual-start', choiceSet: null })
  toolRequests[0].resolve({ runId: 'run-1', eventId: 'actual-start', toolCall })
  assert.equal(await initial, true)
  assert.equal(state.selectedToolCall, toolCall)
  assert.equal(state.selectedChoiceSet, null)
  assert.equal(state.selectedToolCallError, '')
  assert.equal(state.selectedChoiceSetError, '')

  const stale = loader.selectNode(state, 'old-start', state.filters)
  const current = loader.selectNode(state, 'current-start', state.filters)
  assert.equal(toolRequests[1].signal.aborted, true)
  assert.equal(choiceRequests[1].signal.aborted, true)
  toolRequests[1].resolve({ toolCall: { ...toolCall, parameters: 'STALE_TOOL' } })
  choiceRequests[1].resolve({ choiceSet })
  await stale
  assert.equal(state.selectedNodeId, 'current-start')
  assert.equal(state.selectedToolCall, null)
  assert.equal(state.selectedChoiceSet, null)

  toolRequests[2].resolve({ toolCall })
  choiceRequests[2].reject(new Error('公開候選讀取失敗'))
  assert.equal(await current, true)
  assert.equal(state.selectedToolCall, toolCall)
  assert.equal(state.selectedChoiceSet, null)
  assert.equal(state.selectedToolCallError, '')
  assert.equal(state.selectedChoiceSetError, '錯誤：公開候選讀取失敗')

  const closing = loader.selectNode(state, 'close-start', state.filters)
  assert.equal(toolRequests[3].signal.aborted, false)
  assert.equal(choiceRequests[3].signal.aborted, false)
  loader.clearDetail(state)
  assert.equal(toolRequests[3].signal.aborted, true)
  assert.equal(choiceRequests[3].signal.aborted, true)
  toolRequests[3].resolve({ toolCall })
  choiceRequests[3].resolve({ choiceSet })
  await closing
  assert.equal(state.selectedRunId, '')
  assert.equal(state.selectedNodeId, '')
  assert.equal(state.selectedToolCall, null)
  assert.equal(state.selectedChoiceSet, null)
})

test('長公開工具標題在節點內截斷，完整 title 仍保留於 tooltip', () => {
  const longLabel = '公開工具呼叫：exec（內含 exec_command、web__run）'
  const mapped = mapAiFlowGraphData({
    nodes: [{ eventId: 'event-long', label: longLabel, status: 'SUCCEEDED', statusLabel: '成功', categoryLabel: '工具', timeLabel: '09:05:03', durationMillis: 12, x: 1, y: 2, color: '#123456', summary: '' }],
    edges: []
  })
  assert.equal(mapped.nodes[0].tooltipText.split('\n')[0], longLabel)

  const view = readFileSync(new URL('../views/AiFlowView.vue', import.meta.url), 'utf8')
  assert.match(view, /symbolSize:\s*\[150,\s*48\]/)
  assert.match(view, /label:\s*\{[^}]*position:\s*'inside'[^}]*width:\s*136[^}]*overflow:\s*'truncate'[^}]*formatter:\s*node\.graphLabel[^}]*\}/)
})

test('路由與選單限 ADMIN，view 經自己的 AI Flow BFF namespace', () => {
  const app = readFileSync(new URL('../App.vue', import.meta.url), 'utf8')
  const router = readFileSync(new URL('../router/index.js', import.meta.url), 'utf8')
  const api = readFileSync(new URL('../api/index.js', import.meta.url), 'utf8')
  const view = readFileSync(new URL('../views/AiFlowView.vue', import.meta.url), 'utf8')

  assert.match(app, /path: '\/ai-flow', title: 'AI 判斷流程'[^\n]*requiresAdmin: true/)
  assert.match(router, /path: '\/ai-flow'[\s\S]*?meta: \{ title: 'AI 判斷流程'[^}]*requiresAdmin: true/)
  assert.match(api, /aiFlow:\s*\{[\s\S]*?api\.get\('\/bff\/ai-flow\/runs'/)
  assert.match(api, /api\.get\('\/bff\/ai-flow\/runs\/'/)
  assert.match(view, /bffApi\.aiFlow\.runs/)
  assert.match(view, /bffApi\.aiFlow\.detail/)
  assert.match(view, /bffApi\.aiFlow\.toolCall/)
  assert.match(view, /bffApi\.aiFlow\.choiceSet/)
  assert.ok(api.includes("'/bff/ai-flow/runs/' + encodeURIComponent(runId) + '/nodes/' + encodeURIComponent(eventId) + '/tool-call'"))
  assert.ok(api.includes("'/bff/ai-flow/runs/' + encodeURIComponent(runId) + '/nodes/' + encodeURIComponent(eventId) + '/choice-set'"))
  const toolCallApi = api.slice(api.indexOf('toolCall: (runId, eventId,'), api.indexOf('\n  },', api.indexOf('toolCall: (runId, eventId,')))
  assert.match(toolCallApi, /params:\s*\{ date, consumer \}/)
  assert.match(toolCallApi, /signal,\s*skipErrorToast:\s*true/)
  const choiceSetApi = api.slice(api.indexOf('choiceSet: (runId, eventId,'), api.indexOf('\n  },', api.indexOf('choiceSet: (runId, eventId,')))
  assert.match(choiceSetApi, /params:\s*\{ date, consumer \}/)
  assert.match(choiceSetApi, /signal,\s*skipErrorToast:\s*true/)
  const utility = readFileSync(new URL('./aiFlowView.js', import.meta.url), 'utf8')
  const graphOption = view.match(/const graphOption = computed\(\(\) => \([\s\S]*?\n\}\)\)/)?.[0]
  assert.ok(graphOption)
  assert.doesNotMatch(graphOption, /toolCall|choiceSet|parameters|selectedToolCall|selectedChoiceSet/)
  assert.doesNotMatch(view, /console\.(?:log|info|warn|error|debug)\s*\([^)]*(?:toolCall|choiceSet|parameters)/s)
  assert.match(utility, /toolCallController\?\.abort\(\)/)
  assert.match(utility, /choiceSetController\?\.abort\(\)/)
  assert.match(utility, /toolCallGeneration/)
  assert.match(utility, /choiceSetGeneration/)
  assert.match(utility, /parentListGeneration !== listGeneration/)
  assert.match(utility, /parentDetailGeneration !== detailGeneration/)
  assert.match(view, /v-else-if="state\.selectedToolCall"/)
  assert.match(view, /<pre class="tool-call-parameters">/)
  assert.match(view, /<pre class="choice-set-parameters">/)
  assert.match(view, /未記錄公開候選/)
  assert.match(view, /resolveAiFlowGraphNodeSelection\(graphData\.value, params\?\.data\?\.id\)/)
  assert.match(view, /forceSelect: fromSynthetic/)
  assert.match(view, /max-height:\s*240px/)
  assert.match(view, /overflow:\s*auto/)
  assert.match(view, /white-space:\s*pre-wrap/)
  assert.match(view, /word-break:\s*break-all/)
  assert.match(view, /原始輸入超出 1,048,576 字元預掃上限；下列內容與長度只反映已處理前綴/)
  assert.match(view, /至少 100000 處/)
  assert.match(view, /已截斷：已處理前綴的遮蔽後長度/)
  assert.doesNotMatch(view, /v-html\s*=/)
  assert.doesNotMatch(view, /\/api\/(?:ai-flow|srpp)/)
})


test('工具入口沿 CALL_RESULT 找唯一同 step START；slow row END 不直接當參數 id', async () => {
  const start = { eventId: 'start', eventKind: 'START', stepId: 'call-1', operation: { method: 'TOOL', path: 'tool' } }
  const end = { eventId: 'end', eventKind: 'END', stepId: 'call-1', relation: 'CALL_RESULT', parentEventIds: ['start'], operation: start.operation }
  const detail = { nodes: [start, end], slowSteps: [{ nodeId: 'end' }] }
  assert.equal(resolveAiFlowToolStartId(detail, 'start'), 'start')
  assert.equal(resolveAiFlowToolStartId(detail, 'end'), 'start')
  assert.equal(resolveAiFlowToolStartId(detail, detail.slowSteps[0].nodeId), 'start')
  assert.equal(resolveAiFlowToolStartId({ nodes: [{ ...start, operation: { method: 'LOCAL', path: 'calendar' } }] }, 'start'), 'start')
  for (const invalidEnd of [
    { ...end, stepId: 'different' },
    { ...end, relation: 'NEXT_OBSERVED' },
    { ...end, parentEventIds: [] },
    { ...end, parentEventIds: ['missing'] },
    { ...end, parentEventIds: ['start', 'start'] },
    { ...end, parentEventIds: null },
    { ...end, stepId: undefined }
  ]) assert.equal(resolveAiFlowToolStartId({ nodes: [start, invalidEnd] }, 'end'), '')
  const second = { ...start, eventId: 'second' }
  assert.equal(resolveAiFlowToolStartId({ nodes: [start, second, { ...end, parentEventIds: ['start', 'second'] }] }, 'end'), '')
  assert.equal(resolveAiFlowToolStartId({ nodes: [start, start, end] }, 'start'), '')
  assert.equal(resolveAiFlowToolStartId({ nodes: [{ ...start, eventId: 'ui-choice:start:hub' }] }, 'ui-choice:start:hub'), '')
  assert.equal(resolveAiFlowToolStartId({ nodes: [{ ...start, stepId: 'scheduled-start', operation: { method: 'LOCAL' } }] }, 'start'), '')
  assert.equal(resolveAiFlowToolStartId(detail, 'missing'), '')
  assert.equal(resolveAiFlowToolStartId(null, 'end'), '')

  const requests = []
  const loader = createAiFlowViewLoader({
    listRuns: async () => ({ runs: [] }), getRun: async () => ({}),
    getToolCall: async (runId, eventId) => { requests.push({ runId, eventId }); return { toolCall: { name: 'exec', innerNames: ['exec_command'], parameters: 'await tools.exec_command({cmd: command})', parametersChars: 39, truncated: false, redactedCount: 0 } } }
  })
  const state = createAiFlowViewState()
  state.selectedRunId = 'run'
  state.detail = detail
  const id = resolveAiFlowToolStartId(state.detail, state.detail.slowSteps[0].nodeId)
  await loader.selectNode(state, id, state.filters, { forceSelect: true })
  assert.deepEqual(requests, [{ runId: 'run', eventId: 'start' }])
  assert.equal(state.selectedToolCall.parameters, 'await tools.exec_command({cmd: command})')
  await loader.selectNode(state, id, state.filters, { forceSelect: true })
  assert.equal(state.selectedNodeId, 'start')
  assert.equal(requests.length, 1)
  assert.equal(Object.hasOwn(detail.nodes[0], 'toolCall'), false)
})

test('工具入口顯示公開原始輸入與來源界線，保留載入、錯誤、未記錄及空輸入狀態', () => {
  const view = readFileSync(new URL('../views/AiFlowView.vue', import.meta.url), 'utf8')
  assert.equal((view.match(/>查看工具與參數<\/el-button>/g) || []).length, 2)
  assert.match(view, /showToolCall\(row\.nodeId\)/)
  assert.match(view, /showToolCall\(node\.eventId\)/)
  assert.match(view, /resolveAiFlowToolStartId\(state\.detail, eventId\)/)
  assert.match(view, /if \(!startId\) return/)
  assert.match(view, /loader\.selectNode\(state, startId, state\.filters, \{ forceSelect: true \}\)/)
  assert.match(view, /nodeDetail\.value\?\.focus/)
  assert.match(view, /scrollIntoView/)
  assert.match(view, /未記錄工具開始事件/)
  assert.match(view, /<dt>公開原始輸入<\/dt>/)
  assert.match(view, /v-else class="tool-call-state">未記錄工具參數/)
  assert.match(view, /parameters === '' \? '（無參數）'/)
  assert.match(view, /selectedToolCallLoading[\s\S]*selectedToolCallError[\s\S]*v-else-if="state\.selectedToolCall"/)
  assert.match(view, /內含工具名稱僅從文字擷取，不保證逐項已執行/)
  assert.match(view, /動態參數與逐項耗時未獨立記錄/)
  assert.match(view, /僅保留前 16384 字元/)
  assert.match(view, /只反映已處理前綴/)
  assert.match(view, /至少 100000 處/)
  assert.doesNotMatch(view, /v-html|console\.(?:log|info|debug)/)
})
