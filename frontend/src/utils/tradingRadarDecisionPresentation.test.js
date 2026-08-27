import assert from 'node:assert/strict'
import test from 'node:test'
import {
  HOLDING_PERIOD_PROMPT,
  HOLDING_PERIOD_STATES,
  completedCandleDisclosure,
  presentAllHorizonDecisions,
  presentHorizonDecision
} from './tradingRadarDecisionPresentation.js'

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
