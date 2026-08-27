export const HOLDING_PERIOD_STATES = Object.freeze({
  NONE: null,
  SHORT: 'SHORT',
  SWING: 'SWING',
  MEDIUM: 'MEDIUM'
})

export const HOLDING_PERIOD_OPTIONS = Object.freeze([
  { value: HOLDING_PERIOD_STATES.SHORT, label: '一周' },
  { value: HOLDING_PERIOD_STATES.SWING, label: '1周~1月' },
  { value: HOLDING_PERIOD_STATES.MEDIUM, label: '1月~6月' }
])

const HORIZON_PRESENTATION = Object.freeze({
  SHORT: Object.freeze({
    horizon: HOLDING_PERIOD_STATES.SHORT,
    label: '一周',
    candidateField: 'shortCandidateAction',
    actionField: 'shortAction'
  }),
  SWING: Object.freeze({
    horizon: HOLDING_PERIOD_STATES.SWING,
    label: '1周~1月',
    candidateField: 'swingCandidateAction',
    actionField: 'swingAction'
  }),
  MEDIUM: Object.freeze({
    horizon: HOLDING_PERIOD_STATES.MEDIUM,
    label: '1月~6月',
    candidateField: 'candidateAction',
    actionField: 'action'
  })
})

export const HOLDING_PERIOD_PROMPT = '請先依資金安排選擇持有期；系統不會替你合成單一建議。'

function normalizedHorizon(horizon) {
  return Object.prototype.hasOwnProperty.call(HORIZON_PRESENTATION, horizon)
    ? horizon
    : HOLDING_PERIOD_STATES.NONE
}

/**
 * 僅投影後端已給出的三軌欄位。缺值保留 null，絕不可借用其他軌的候選或實際動作。
 */
export function presentHorizonDecision(row, horizon, selectedHorizon = HOLDING_PERIOD_STATES.NONE) {
  const normalized = normalizedHorizon(horizon)
  if (normalized === HOLDING_PERIOD_STATES.NONE) return null

  const definition = HORIZON_PRESENTATION[normalized]
  return {
    ...definition,
    candidateAction: row?.[definition.candidateField] ?? null,
    action: row?.[definition.actionField] ?? null,
    selected: normalized === normalizedHorizon(selectedHorizon)
  }
}

/** 三軌永遠完整回傳；selected 只提供視覺聚焦，不過濾或排序。 */
export function presentAllHorizonDecisions(row, selectedHorizon = HOLDING_PERIOD_STATES.NONE) {
  return HOLDING_PERIOD_OPTIONS.map(option => presentHorizonDecision(row, option.value, selectedHorizon))
}

/** 完成日 K 只能來自 dailyCandle，不能把 accepted live quote date 說成完成日 K。 */
export function completedCandleDisclosure(row) {
  const asOfDate = row?.dailyCandle?.asOfDate
  return asOfDate
    ? `止跌與避免追價使用完成日 K（${asOfDate}）判定；盤中股價／漲跌僅供展示，不重算訊號。`
    : '止跌與避免追價的完成日 K 資料不足；盤中股價／漲跌僅供展示，不重算訊號。'
}
