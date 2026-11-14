import test from 'node:test'
import assert from 'node:assert/strict'

import {
  formatMoneyText,
  formatPreciseDecimal,
  formatPreciseMoneyText,
  inspectWeightTotal,
  parseMoneyText,
  validateMoneyText,
  validatePensionOverridePairs,
  validateShortStrategyValues
} from './srppSettingsCenterFormatting.js'

test('金額欄位接受有逗點／無逗點輸入並保留兩位小數數值', () => {
  assert.deepEqual(parseMoneyText('1,234,567.80'), { valid: true, complete: true, value: 1234567.8 })
  assert.deepEqual(parseMoneyText('1234567.80'), { valid: true, complete: true, value: 1234567.8 })
  assert.equal(formatMoneyText(100000), '100,000')
  assert.equal(formatMoneyText('10000000.00', { minimumFractionDigits: 2 }), '10,000,000.00')
  assert.equal(formatMoneyText(12.5), '12.5')
  assert.equal(formatPreciseMoneyText('1234.567890'), '1,234.567890')
})

test('nullable清空與部分輸入不會偽裝成零或破壞既有編輯值', () => {
  assert.deepEqual(parseMoneyText(''), { valid: true, complete: true, value: null })
  assert.deepEqual(parseMoneyText('.'), { valid: true, complete: false, value: null })
  assert.deepEqual(parseMoneyText('1.'), { valid: true, complete: true, value: 1 })
  assert.equal(parseMoneyText('1,23').valid, false)
  assert.equal(parseMoneyText('1.234').valid, false)
  assert.equal(parseMoneyText('1,,000').valid, false)
  assert.equal(validateMoneyText('', { nullable: false }), false)
  assert.equal(validateMoneyText('', { nullable: true }), true)
  assert.equal(validateMoneyText('1.2', { integer: true }), false)
  assert.equal(validateMoneyText('1,000.00', { min: 0, max: 500 }), false)
})

test('配置合計依完整catalog keys與1e-9容許值檢查，不改原精度', () => {
  const weights = { TW_STOCK: 0.164, US_STOCK: 0.236, BOND: 0.3, CASH: 0.3 }
  const keys = Object.keys(weights)
  const valid = inspectWeightTotal(weights, keys)
  assert.equal(valid.status, 'valid')
  assert.equal(valid.totalPercent, 100)
  assert.equal(formatPreciseDecimal(16.400000000000002), '16.4')
  assert.equal(weights.TW_STOCK, 0.164)
})

test('配置合計即時指出99%／101%，缺值與非法值不能顯示為有效', () => {
  const keys = ['A', 'B']
  assert.equal(inspectWeightTotal({ A: 0.4, B: 0.59 }, keys).status, 'under')
  assert.equal(inspectWeightTotal({ A: 0.4, B: 0.61 }, keys).status, 'over')
  assert.equal(inspectWeightTotal({ A: 0.4, B: null }, keys).status, 'incomplete')
  assert.equal(inspectWeightTotal({ A: 0.4 }, keys).status, 'incomplete')
  assert.equal(inspectWeightTotal({ A: 0.4, B: 0.6, EXTRA: 0 }, keys).status, 'invalid')
  assert.equal(inspectWeightTotal({ A: 0.4, B: NaN }, keys).status, 'invalid')
  assert.equal(inspectWeightTotal({ A: 0.4, B: 0.6000000005 }, keys).status, 'valid')
  assert.equal(inspectWeightTotal({ A: 0.4, B: 0.600000002 }, keys).status, 'over')
})

test('短線策略只接受原白名單標的、欄位界限與組合上限', () => {
  const fields = [
    { id: 'shortEquitySymbols', type: 'symbols', allowedSymbols: ['0050', '00878'] },
    { id: 'shortEquityPositionCap', type: 'number', min: 0, max: 1 },
    { id: 'shortEquitySleeveCap', type: 'number', min: 0, max: 1 },
    { id: 'usTacticalBatchCap', type: 'number', min: 0, max: 1 },
    { id: 'usTacticalCompanyCap', type: 'number', min: 0, max: 1 },
    { id: 'usTacticalSleeveCap', type: 'number', min: 0, max: 1 },
    { id: 'shortBondMaxHoldingSessions', type: 'integer', min: 1, max: 260 }
  ]
  const validValues = {
    shortEquitySymbols: ['00878', '0050'], shortEquityPositionCap: 0.01, shortEquitySleeveCap: 0.02,
    usTacticalBatchCap: 0.01, usTacticalCompanyCap: 0.02, usTacticalSleeveCap: 0.03,
    shortBondMaxHoldingSessions: 10
  }
  assert.deepEqual(validateShortStrategyValues(validValues, fields), [])
  assert.ok(validateShortStrategyValues({ ...validValues, shortEquitySymbols: ['9999'] }, fields)
    .some(error => error.fieldId === 'shortEquitySymbols'))
  assert.ok(validateShortStrategyValues({ ...validValues, shortEquityPositionCap: 0.03 }, fields)
    .some(error => error.fieldId === 'shortEquityPositionCap'))
  assert.ok(validateShortStrategyValues({ ...validValues, usTacticalCompanyCap: 0.005 }, fields)
    .some(error => error.fieldId === 'usTacticalBatchCap'))
  assert.ok(validateShortStrategyValues({ ...validValues, shortBondMaxHoldingSessions: 2.5 }, fields)
    .some(error => error.fieldId === 'shortBondMaxHoldingSessions'))
})

test('勞保／勞退 nullable override 成對；零金額仍是已填值', () => {
  const empty = { laborPensionReceiptDate: null, laborPensionReceiptAmount: null, laborInsuranceStartDate: null, laborInsuranceMonthlyAmount: null }
  assert.deepEqual(validatePensionOverridePairs(empty), [])
  assert.match(validatePensionOverridePairs({ ...empty, laborPensionReceiptAmount: 0 })[0], /勞退/)
  assert.deepEqual(validatePensionOverridePairs({ ...empty, laborPensionReceiptDate: '2030-01-02', laborPensionReceiptAmount: 0 }), [])
  assert.match(validatePensionOverridePairs({ ...empty, laborInsuranceMonthlyAmount: 12000 })[0], /勞保/)
  assert.deepEqual(validatePensionOverridePairs({ ...empty, laborInsuranceStartDate: '2030-01-02', laborInsuranceMonthlyAmount: 12000 }), [])
})
