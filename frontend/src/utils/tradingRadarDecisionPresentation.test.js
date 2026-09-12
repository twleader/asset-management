import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import {
  HOLDING_PERIOD_PROMPT,
  HOLDING_PERIOD_STATES,
  completedCandleDisclosure,
  presentAllHorizonDecisions,
  presentHorizonDecision
} from './tradingRadarDecisionPresentation.js'

const tradingRadarView = readFileSync(
  new URL('../views/TradingRadarView.vue', import.meta.url),
  'utf8'
)

const completeRow = {
  shortCandidateAction: 'SHORT_CANDIDATE',
  shortAction: 'SHORT_FINAL',
  swingCandidateAction: 'SWING_CANDIDATE',
  swingAction: 'SWING_FINAL',
  candidateAction: 'MEDIUM_CANDIDATE',
  action: 'MEDIUM_FINAL',
  asOfDate: '2026-08-27',
  dailyCandle: { asOfDate: '2026-08-26' }
}

test('未選擇持有期仍保留三軌，並提供不合成提示', () => {
  const decisions = presentAllHorizonDecisions(completeRow, HOLDING_PERIOD_STATES.NONE)

  assert.equal(HOLDING_PERIOD_PROMPT, '請先依資金安排選擇持有期；系統不會替你合成單一建議。')
  assert.deepEqual(decisions.map(decision => decision.horizon), ['SHORT', 'SWING', 'MEDIUM'])
  assert.deepEqual(decisions.map(decision => decision.selected), [false, false, false])
})

for (const [state, expected] of [
  [HOLDING_PERIOD_STATES.SHORT, ['一周', 'shortCandidateAction', 'shortAction']],
  [HOLDING_PERIOD_STATES.SWING, ['1周~1月', 'swingCandidateAction', 'swingAction']],
  [HOLDING_PERIOD_STATES.MEDIUM, ['1月~6月', 'candidateAction', 'action']]
]) {
  test(`${state} 精確對應自己的候選與實際動作欄位`, () => {
    const decision = presentHorizonDecision(completeRow, state, state)

    assert.equal(decision.label, expected[0])
    assert.equal(decision.candidateField, expected[1])
    assert.equal(decision.actionField, expected[2])
    assert.equal(decision.candidateAction, completeRow[expected[1]])
    assert.equal(decision.action, completeRow[expected[2]])
    assert.equal(decision.selected, true)
  })
}

test('候選或實際動作缺值不跨軌補值，三軌仍全數呈現', () => {
  const decisions = presentAllHorizonDecisions({
    shortCandidateAction: 'SHORT_CANDIDATE',
    swingAction: 'SWING_FINAL',
    candidateAction: 'MEDIUM_CANDIDATE'
  }, HOLDING_PERIOD_STATES.SWING)

  assert.deepEqual(decisions.map(({ horizon, candidateAction, action }) => ({ horizon, candidateAction, action })), [
    { horizon: 'SHORT', candidateAction: 'SHORT_CANDIDATE', action: null },
    { horizon: 'SWING', candidateAction: null, action: 'SWING_FINAL' },
    { horizon: 'MEDIUM', candidateAction: 'MEDIUM_CANDIDATE', action: null }
  ])
  assert.deepEqual(decisions.map(decision => decision.selected), [false, true, false])
})

test('完成日 K 文案只採 dailyCandle.asOfDate，與盤中 quote 日期無關', () => {
  assert.equal(
    completedCandleDisclosure(completeRow),
    '止跌與避免追價使用完成日 K（2026-08-26）判定；盤中股價／漲跌僅供展示，不重算訊號。'
  )
  assert.equal(
    completedCandleDisclosure({ asOfDate: '2026-08-27', dailyCandle: {} }),
    '止跌與避免追價的完成日 K 資料不足；盤中股價／漲跌僅供展示，不重算訊號。'
  )
  assert.equal(
    completedCandleDisclosure({ asOfDate: '2026-08-27' }),
    '止跌與避免追價的完成日 K 資料不足；盤中股價／漲跌僅供展示，不重算訊號。'
  )
})

test('Task408 detail 呈現 D/W profile、field provenance 與 legacy null，不在 UI 重算技術指標', () => {
  for (const requiredFragment of [
    'row.technicalResolution',
    'technicalProfileGroups(row.technicalResolution)',
    '日線（D）',
    '週線（W）',
    'formatTechnicalMap(profile.parameters)',
    'formatTechnicalMap(profile.payload)',
    'profile.sourceDate',
    'profile.observedAt',
    'row.technicalResolution.ageSeconds',
    'row.technicalResolution.captureId',
    'row.technicalResolution.binding',
    'technicalEligibilityLabel(profile.eligibility)',
    'row.technicalResolution.fieldProvenance',
    'technicalOriginLabel(field.origin)',
    'LEGACY_LOCAL_V0'
  ]) {
    assert.ok(tradingRadarView.includes(requiredFragment), `missing Task408 detail fragment: ${requiredFragment}`)
  }
  assert.ok(tradingRadarView.includes('不重新計算任何技術值'))
  assert.ok(tradingRadarView.includes('不將其視為 0 或最新富邦來源'))
})

test('Trading Radar detail 將設定頁等價分類與 strict radar profile 分開顯示，且只排除台股 0000', () => {
  for (const requiredFragment of [
    "row?.market === '台股' && row?.stockCode === '0000'",
    '資產類別設定（生效值）',
    'row.evidence.settingsClassification',
    'settingsAssetClassLabel(row.evidence.settingsClassification)',
    'settingsSubdivisionLabel(row.evidence.settingsClassification)',
    'classificationSourceLabel(row.evidence.settingsClassification.assetClassSource)',
    'classificationSourceLabel(row.evidence.settingsClassification.stockStyleSource)',
    'classificationSourceLabel(row.evidence.settingsClassification.bondTermSource)',
    'settingsClassificationOverrideSummary(row.evidence.settingsClassification)',
    '嚴格交易雷達資產輪廓',
    'radarAssetClassLabel(row.evidence.assetProfile)',
    'radarAssetSubdivisionLabel(row.evidence.assetProfile)',
    'classificationSourceLabel(row.evidence.assetProfile?.assetClassSource)',
    'assetProfileOverrideSummary(row.evidence.assetProfile)',
    '使用者指定覆寫',
    '不等同資產類別設定頁分類'
  ]) {
    assert.ok(tradingRadarView.includes(requiredFragment), `missing classification detail fragment: ${requiredFragment}`)
  }
  assert.ok(tradingRadarView.includes('不得依股票名稱、代碼或技術資料自行補推或互相替代'))
})

test('Task426 首屏只讀 list，展開才讀單檔 detail，SSE 只按 mapping patch 同列行情欄位', () => {
  for (const fragment of [
    "bffApi.tradingRadar.list()",
    "bffApi.tradingRadar.stock(row.market, row.stockCode)",
    '@expand-change="onRowExpand"',
    'PRICE_UPDATE_FIELD_MAPPING',
    "payload: 'price + changePercent|changePct'",
    'applyTradingRadarSsePriceUpdate(radar.value.stocks || [], payload)',
    'Object.assign(row, response.stock)',
    'generation === listGeneration',
    'expandedDetailKeys.has(key)'
  ]) {
    assert.ok(tradingRadarView.includes(fragment), `missing Task426 list/detail/SSE fragment: ${fragment}`)
  }
  for (const forbidden of [
    'scheduleRecalculation()',
    'recalculateRadar()',
    'radar.value = { ...radar.value, stocks: nextStocks }'
  ]) {
    assert.equal(tradingRadarView.includes(forbidden), false, `Task426 SSE must not retain ${forbidden}`)
  }
})
