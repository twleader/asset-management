import { marketToday } from './displayQuote.js'

/**
 * Trading Radar 的五個自足 Panel 載入器。它只管理 request 生命週期和 wire
 * contract；不組合金融判斷，也不把不同 panel 的 generatedAt 混為同一個時點。
 */
export const TRADING_RADAR_PANELS = Object.freeze([
  'tw-market', 'us-market', 'tw-stocks', 'us-stocks', 'public-information'
])

const STOCK_PANELS = new Set(['tw-stocks', 'us-stocks'])

export function createTradingRadarPanelLoader({ request, stateFactory = value => value, onData = () => {} }) {
  let disposed = false
  const states = stateFactory(Object.fromEntries(TRADING_RADAR_PANELS.map(panel => [panel, {
    data: null, loading: false, error: null, empty: false, generation: 0, controller: null
  }])))

  const current = (state, generation) => !disposed && state.generation === generation
  const abort = state => { state.controller?.abort(); state.controller = null }

  async function load(panel, { preserve = false } = {}) {
    const state = states[panel]
    if (!state || disposed) return null
    abort(state)
    const generation = ++state.generation
    if (!preserve) { state.data = null; state.error = null; state.empty = false }
    state.loading = true
    const controller = new AbortController()
    state.controller = controller
    try {
      const envelope = await request(panel, { signal: controller.signal })
      if (!current(state, generation)) return null
      validatePanelEnvelope(panel, envelope)
      onData(panel, envelope, { generation })
      state.data = envelope
      state.error = null
      state.empty = panelEmpty(panel, envelope)
      return envelope
    } catch (error) {
      if (!current(state, generation) || isAbort(error)) return null
      state.error = error
      state.empty = false
      return null
    } finally {
      if (current(state, generation)) {
        state.loading = false
        state.controller = null
      }
    }
  }

  function loadAll({ preserve = true } = {}) {
    return Promise.allSettled(TRADING_RADAR_PANELS.map(panel => load(panel, { preserve })))
  }

  function dispose() {
    disposed = true
    Object.values(states).forEach(state => {
      state.generation += 1
      abort(state)
      state.loading = false
    })
  }

  return { states, load, loadAll, dispose, get disposed() { return disposed } }
}

export function validatePanelEnvelope(panel, envelope) {
  const data = envelope?.data
  if (!TRADING_RADAR_PANELS.includes(panel) || envelope?.panel !== panel
    || !nonBlank(envelope?.ruleVersion) || !nonBlank(envelope?.actionPolicyVersion)
    || !validTimestamp(envelope?.generatedAt) || !data || typeof data !== 'object' || Array.isArray(data)) {
    throw new Error('區塊資料不完整，請重新載入')
  }
  if (panel === 'tw-market' || panel === 'us-market') {
    if (!validMarket(data.market)) throw new Error('大盤資料不完整，請重新載入')
  } else if (STOCK_PANELS.has(panel)) {
    if (!validMarket(data.market) || !Array.isArray(data.stocks)
      || !Number.isInteger(data.skippedNonTwStocks) || data.skippedNonTwStocks < 0) {
      throw new Error('個股資料不完整，請重新載入')
    }
    const market = panel === 'tw-stocks' ? '台股' : '美股'
    const codes = new Set()
    for (const row of data.stocks) {
      if (!nonBlank(row?.stockCode) || !samePair(row, market, row.stockCode) || codes.has(row.stockCode)) {
        throw new Error('個股資料身份不相符，請重新載入')
      }
      codes.add(row.stockCode)
    }
  } else if (!Array.isArray(data.publicInformation)) {
    throw new Error('公開資訊資料不完整，請重新載入')
  }
  return envelope
}

/** 單股 endpoint 的根、指定股票及三軌動作／標籤／分數必須完全同步。 */
export function validateStockEvaluation(market, stockCode, envelope) {
  const summary = envelope?.summary
  const stock = envelope?.stock
  if (!plainObject(envelope) || !validMarket(envelope.market) || !plainObject(summary) || !plainObject(stock)
    || !nonBlank(envelope.ruleVersion) || !nonBlank(envelope.actionPolicyVersion) || !validTimestamp(envelope.generatedAt)
    || !samePair(summary, market, stockCode) || !samePair(stock, market, stockCode)) {
    throw new Error('單股評估資料不完整或不相符，請重新載入')
  }
  for (const field of ['shortAction', 'shortActionLabel', 'shortScore', 'swingAction', 'swingActionLabel', 'swingScore', 'action', 'actionLabel', 'score']) {
    if (!(field in summary) || !(field in stock) || summary[field] !== stock[field]
      || !(field.toLowerCase().endsWith('score')
        ? summary[field] === null || Number.isInteger(summary[field])
        : summary[field] === null || nonBlank(summary[field]))) {
      throw new Error('單股評估三軌資料不一致，未套用更新')
    }
  }
  return envelope
}

/** 只投影 compact ListStock 欄位，絕不把完整 StockDecision 合併回 table row。 */
export function applyStockEvaluationTuple(rows, market, stockCode, envelope) {
  validateStockEvaluation(market, stockCode, envelope)
  const index = rows.findIndex(row => samePair(row, market, stockCode))
  if (index < 0) throw new Error('股票已不在目前清單，未套用舊回應')
  const current = rows[index]
  const detail = preserveNewerSseQuote(current, envelope)
  const nextSummary = { ...detail.summary }
  const nextRows = rows.slice()
  nextRows[index] = { ...current, ...nextSummary, evaluationGeneratedAt: envelope.generatedAt, evaluationMarket: envelope.market,
    evaluationRuleVersion: envelope.ruleVersion, evaluationActionPolicyVersion: envelope.actionPolicyVersion }
  return { rows: nextRows, detail }
}

export function panelEmpty(panel, envelope) {
  return STOCK_PANELS.has(panel) ? envelope.data.stocks.length === 0
    : panel === 'public-information' ? envelope.data.publicInformation.length === 0 : false
}

function samePair(value, market, stockCode) {
  return value?.market === market && String(value?.stockCode) === String(stockCode)
}
// Evaluation 是較早的分析 snapshot 時，不能覆蓋在它完成後已由 SSE 接受的行情。
// 僅重套 SSE allow-list，generatedAt 和分析／三軌欄位永遠不由 SSE 改動。
function preserveNewerSseQuote(current, envelope) {
  const evaluationQuoteAt = asMillis(envelope.summary?.priceUpdatedAt)
  const currentQuoteAt = asMillis(current?.priceUpdatedAt)
  // 新評估的等待／已驗證收盤屬權威狀態；缺時間也不能當成較舊盤中行情。
  if (envelope.summary.quoteStatus !== 'LIVE' || !['LIVE', 'VERIFIED_CLOSE'].includes(current?.quoteStatus)
    || currentQuoteAt == null || evaluationQuoteAt == null || currentQuoteAt <= evaluationQuoteAt
    || marketToday(current.market, new Date(currentQuoteAt)) !== marketToday(current.market, new Date(evaluationQuoteAt))) return envelope
  const fields = ['price', 'changePercent', 'quoteStatus', 'priceUpdatedAt']
  const summary = { ...envelope.summary }
  const stock = { ...envelope.stock }
  for (const field of fields) {
    if (Object.prototype.hasOwnProperty.call(current, field)) {
      summary[field] = current[field]
      stock[field] = current[field]
    }
  }
  return { ...envelope, summary, stock }
}
function asMillis(value) {
  if (!value) return null
  const time = Date.parse(value)
  return Number.isFinite(time) ? time : null
}
function validMarket(value) {
  return plainObject(value) && nonBlank(value.regime) && nonBlank(value.regimeLabel)
    && (value.score === null || Number.isInteger(value.score))
}
function plainObject(value) { return !!value && typeof value === 'object' && !Array.isArray(value) }
function validTimestamp(value) { return nonBlank(value) && Number.isFinite(Date.parse(value)) }
function nonBlank(value) { return typeof value === 'string' && value.trim() !== '' }
function isAbort(error) { return error?.name === 'AbortError' || error?.code === 'ERR_CANCELED' }
