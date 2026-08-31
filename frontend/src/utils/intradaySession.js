const LABELS = {
  PREVIOUS_CLOSE: '昨收', EX_RIGHTS_REFERENCE: '除權息參考價',
  SESSION_REFERENCE: '參考價', UNAVAILABLE: '比較基準'
}

const EMPTY = Object.freeze({
  tradingDate: null, ticks: [], sessionReferencePrice: null, sessionReferenceDate: null,
  sessionReferenceSource: null, comparisonPrice: null, comparisonKind: 'UNAVAILABLE',
  comparisonSource: null, lastPrice: null, change: null, changePercent: null
})

/**
 * Treat the comparison fields as one signed tuple. A partial/mismatched server response must not
 * produce a plausible but false quote in the popup: all comparison/change fields fail closed.
 */
export function normalizeIntradaySession(payload) {
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)) return { ...EMPTY }

  const tradingDate = validDate(payload.tradingDate) ? payload.tradingDate : null
  const ticks = Array.isArray(payload.ticks) ? payload.ticks : []
  const lastPrice = positiveOrNull(payload.lastPrice)
  const reference = {
    price: positiveOrNull(payload.sessionReferencePrice),
    date: validDate(payload.sessionReferenceDate) ? payload.sessionReferenceDate : null,
    source: nonBlankString(payload.sessionReferenceSource)
  }
  const candidate = {
    comparisonPrice: positiveOrNull(payload.comparisonPrice),
    comparisonKind: nonBlankString(payload.comparisonKind),
    comparisonSource: nonBlankString(payload.comparisonSource),
    change: numberOrNull(payload.change),
    changePercent: numberOrNull(payload.changePercent)
  }

  if (!validComparisonTuple(candidate, lastPrice, tradingDate, reference, payload)) {
    return { ...EMPTY, tradingDate, ticks }
  }

  return { tradingDate, ticks, sessionReferencePrice: reference.price,
    sessionReferenceDate: reference.date, sessionReferenceSource: reference.source,
    ...candidate, lastPrice }
}

export function comparisonLabel(kind) { return LABELS[kind] || LABELS.UNAVAILABLE }

export function intradayLatestDate(session, chartDates) {
  if (session?.ticks?.length && validDate(session.tradingDate)) return session.tradingDate
  return Array.isArray(chartDates) && chartDates.length ? chartDates[chartDates.length - 1] : null
}

function validComparisonTuple(candidate, lastPrice, tradingDate, reference, payload) {
  const noComparison = candidate.comparisonPrice == null
    && candidate.change == null && candidate.changePercent == null
    && (candidate.comparisonKind == null || candidate.comparisonKind === 'UNAVAILABLE')
    && candidate.comparisonSource == null
  const rawReferenceProvided = payload.sessionReferencePrice != null
    || payload.sessionReferenceDate != null || payload.sessionReferenceSource != null
  if (noComparison) return !rawReferenceProvided

  if (!validDate(tradingDate) || !(candidate.comparisonPrice > 0) || !LABELS[candidate.comparisonKind]
      || candidate.comparisonKind === 'UNAVAILABLE'
      || !validSourceForKind(candidate.comparisonSource, candidate.comparisonKind)) return false

  if (!(lastPrice > 0) || candidate.change == null || candidate.changePercent == null) return false
  if (candidate.comparisonSource === 'TWSE_MIS_Y') {
    if (!(reference.price > 0) || !validDate(reference.date)
        || reference.date !== tradingDate || reference.source !== 'TWSE_MIS_Y'
        || !closeEnough(reference.price, candidate.comparisonPrice)) return false
  } else if (rawReferenceProvided) {
    // A history-derived comparison must not carry a misleading or malformed TWSE reference.
    return false
  }

  const expectedChange = lastPrice - candidate.comparisonPrice
  const expectedPercent = expectedChange / candidate.comparisonPrice * 100
  return closeEnough(expectedChange, candidate.change) && closeEnough(expectedPercent, candidate.changePercent)
}

function validSourceForKind(source, kind) {
  if (source === 'TWSE_MIS_Y') {
    return kind === 'PREVIOUS_CLOSE' || kind === 'EX_RIGHTS_REFERENCE' || kind === 'SESSION_REFERENCE'
  }
  return kind === 'PREVIOUS_CLOSE'
    && (source === 'STOCK_PRICE_HISTORY' || source === 'TWSE_INDEX_DAILY_HISTORY')
}

function closeEnough(expected, actual) {
  return Math.abs(expected - actual) <= Math.max(0.00001, Math.abs(expected) * 0.000001)
}

function validDate(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const parsed = new Date(`${value}T00:00:00.000Z`)
  return !Number.isNaN(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value
}
function nonBlankString(value) {
  return typeof value === 'string' && value.trim() ? value : null
}
function positiveOrNull(value) { const n = numberOrNull(value); return n != null && n > 0 ? n : null }
function numberOrNull(value) {
  if (value == null || value === '') return null
  const n = Number(value)
  return Number.isFinite(n) ? n : null
}
