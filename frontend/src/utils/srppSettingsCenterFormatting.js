const MONEY_TEXT = /^([+-]?)(?:(\d{1,3}(?:,\d{3})+|\d+)?)(?:\.(\d{0,2}))?$/

/** 將金額顯示成千分位字串；不改呼叫端保存的 number 或 canonical money 字串。 */
export function formatMoneyText(value, { minimumFractionDigits = 0 } = {}) {
  if (value == null || value === '') return ''
  const raw = typeof value === 'number' ? (Number.isFinite(value) ? String(value) : '') : String(value).trim()
  if (!raw) return ''

  const match = raw.match(/^([+-]?)(\d+)(?:\.(\d+))?$/)
  if (!match) {
    const numeric = Number(raw)
    if (!Number.isFinite(numeric)) return ''
    return formatMoneyText(numeric.toFixed(2), { minimumFractionDigits })
  }

  const [, sign, whole, fraction = ''] = match
  const groupedWhole = whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  let visibleFraction = fraction.slice(0, 2).replace(/0+$/, '')
  if (minimumFractionDigits > visibleFraction.length) {
    visibleFraction = visibleFraction.padEnd(Math.min(2, minimumFractionDigits), '0')
  }
  return `${sign}${groupedWhole}${visibleFraction ? `.${visibleFraction}` : ''}`
}

/** 顯示行情／價格字串時保留來源的小數位數，只加入整數千分位。 */
export function formatPreciseMoneyText(value) {
  if (value == null || value === '') return '—'
  const raw = typeof value === 'number' ? (Number.isFinite(value) ? String(value) : '') : String(value).trim()
  const match = raw.match(/^([+-]?)(\d+)(?:\.(\d+))?$/)
  if (!match) return '—'
  const [, sign, whole, fraction = ''] = match
  return `${sign}${whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',')}${fraction ? `.${fraction}` : ''}`
}

/**
 * 解析金額欄位。允許正確的千分位與最多兩位小數；暫時的空字串、單獨小數點等
 * 編輯狀態不會覆寫既有數值，讓游標輸入保持穩定。
 */
export function parseMoneyText(input) {
  const raw = String(input ?? '').trim()
  if (!raw) return { valid: true, complete: true, value: null }
  if (!MONEY_TEXT.test(raw)) return { valid: false, complete: false, value: null }

  const ungrouped = raw.replaceAll(',', '')
  const hasDigit = /\d/.test(ungrouped)
  if (!hasDigit || ungrouped === '+' || ungrouped === '-') {
    return { valid: true, complete: false, value: null }
  }
  const numericText = ungrouped.endsWith('.') ? ungrouped.slice(0, -1) : ungrouped
  const value = Number(numericText)
  return Number.isFinite(value)
    ? { valid: true, complete: true, value }
    : { valid: false, complete: false, value: null }
}

export function validateMoneyText(input, { min = null, max = null, integer = false, nullable = false } = {}) {
  const parsed = parseMoneyText(input)
  if (!parsed.valid || !parsed.complete) return false
  if (parsed.value == null) return nullable
  if (integer && !Number.isInteger(parsed.value)) return false
  if (min != null && parsed.value < min) return false
  if (max != null && parsed.value > max) return false
  return true
}

/** 對目前載入的配置欄位做純顯示合計；不會修改或正規化輸入物件。 */
export function inspectWeightTotal(weights, assetKeys, tolerance = 1e-9) {
  if (!Array.isArray(assetKeys) || !assetKeys.length || !weights || typeof weights !== 'object' || Array.isArray(weights)) {
    return { status: 'incomplete', sum: null, totalPercent: null, differencePoints: null }
  }

  const expected = new Set(assetKeys)
  const keys = Object.keys(weights)
  if (keys.some(key => !expected.has(key))) {
    return { status: 'invalid', reason: 'extra-key', sum: null, totalPercent: null, differencePoints: null }
  }
  if (assetKeys.some(key => !Object.hasOwn(weights, key) || weights[key] == null || weights[key] === '')) {
    return { status: 'incomplete', sum: null, totalPercent: null, differencePoints: null }
  }
  if (keys.length !== assetKeys.length || assetKeys.some(key => typeof weights[key] !== 'number' || !Number.isFinite(weights[key]) || weights[key] < 0 || weights[key] > 1)) {
    return { status: 'invalid', reason: 'range-or-type', sum: null, totalPercent: null, differencePoints: null }
  }

  const sum = assetKeys.reduce((total, key) => total + weights[key], 0)
  if (Math.abs(sum - 1) <= tolerance) {
    return { status: 'valid', sum, totalPercent: 100, differencePoints: 0 }
  }
  return {
    status: sum < 1 ? 'under' : 'over',
    sum,
    totalPercent: sum * 100,
    differencePoints: Math.abs(1 - sum) * 100
  }
}

/** Validate the t587 draft-only controls without rewriting their source values. */
export function validateShortStrategyValues(values, fields) {
  const errors = []
  const byId = new Map((Array.isArray(fields) ? fields : []).map(field => [field.id, field]))
  const numeric = new Map()

  for (const field of byId.values()) {
    const value = values?.[field.id]
    if (field.type === 'symbols') {
      const allowed = new Set(Array.isArray(field.allowedSymbols) ? field.allowedSymbols : [])
      if (!Array.isArray(value) || value.some(symbol => typeof symbol !== 'string' || !symbol.trim() || !allowed.has(symbol)) ||
        new Set(value).size !== value.length) {
        errors.push({ fieldId: field.id, message: '標的必須是來源白名單內不重複的代號。' })
      }
      continue
    }

    if (field.type !== 'number' && field.type !== 'integer') continue
    if (typeof value !== 'number' || !Number.isFinite(value) ||
      (field.type === 'integer' && !Number.isInteger(value)) ||
      (field.min != null && value < field.min) || (field.max != null && value > field.max)) {
      errors.push({ fieldId: field.id, message: '數值必須符合目錄指定範圍。' })
      continue
    }
    numeric.set(field.id, value)
  }

  const relationships = [
    ['shortEquityPositionCap', 'shortEquitySleeveCap', '台股股票單一部位上限不得高於短線總額上限。'],
    ['shortBondPositionCap', 'shortBondSleeveCap', '台股債券單一部位上限不得高於短線總額上限。'],
    ['usTacticalBatchCap', 'usTacticalCompanyCap', '美股單批上限不得高於單公司上限。'],
    ['usTacticalCompanyCap', 'usTacticalSleeveCap', '美股單公司上限不得高於戰術總額上限。']
  ]
  for (const [lowerId, upperId, message] of relationships) {
    const lower = numeric.get(lowerId)
    const upper = numeric.get(upperId)
    if (lower != null && upper != null && lower > upper) errors.push({ fieldId: lowerId, message })
  }
  return errors
}

/** Optional manual pension inputs must be paired; numeric zero is still an entered amount. */
export function validatePensionOverridePairs(values) {
  const current = values && typeof values === 'object' ? values : {}
  const pairs = [
    ['laborPensionReceiptDate', 'laborPensionReceiptAmount', '勞退預計實收日期與金額需同時填寫，或同時留空依模型自動估算。'],
    ['laborInsuranceStartDate', 'laborInsuranceMonthlyAmount', '勞保起領日期與月額需同時填寫，或同時留空依模型自動估算。']
  ]
  return pairs.filter(([dateKey, amountKey]) => {
    const dateFilled = current[dateKey] != null && current[dateKey] !== ''
    const amountFilled = current[amountKey] != null && current[amountKey] !== ''
    return dateFilled !== amountFilled
  }).map(([, , message]) => message)
}

/** 以指定精度去除 binary floating point 顯示尾數，不回寫計算值。 */
export function formatPreciseDecimal(value, maxFractionDigits = 12) {
  if (typeof value !== 'number' || !Number.isFinite(value)) return '—'
  if (Object.is(value, -0) || value === 0) return '0'
  let text = value.toFixed(maxFractionDigits)
  text = text.replace(/(?:\.0+|(?:(\.\d*?)0+))$/, '$1')
  if (text === '-0') return '0'
  return text
}
