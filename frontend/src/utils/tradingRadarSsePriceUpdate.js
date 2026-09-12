import { marketToday, mergeSseQuote } from './displayQuote.js'

/**
 * Requirement 148's complete Trading Radar price-update allow-list.
 *
 * This is deliberately a row-level mutation helper: it performs no HTTP, no rule
 * calculation, no root replacement, and no payload/object spread.  Its boolean
 * result is useful to callers that need to distinguish an ignored event from a
 * quote patch without deriving any additional UI state.
 */
export function applyTradingRadarSsePriceUpdate(stocks, payload, today = marketToday(payload?.market)) {
  if (!Array.isArray(stocks) || !['台股', '美股'].includes(payload?.market)
    || !payload.stockCode || payload.stockCode === '0000') return false

  const row = stocks.find(candidate =>
    candidate.market === payload.market && String(candidate.stockCode) === String(payload.stockCode))
  if (!row) return false

  // Canonical changePercent wins. changePct is only the legacy fallback when the
  // canonical field is absent, not merely falsy or null.
  const changeValue = Object.prototype.hasOwnProperty.call(payload, 'changePercent')
    ? payload.changePercent
    : payload.changePct
  if (!isFiniteWireNumber(payload.price) || !isFiniteWireNumber(changeValue)) return false
  const price = Number(payload.price)
  const changePercent = Number(changeValue)
  if (price <= 0) return false

  const gatedQuote = mergeSseQuote(row, {
    market: payload.market,
    quoteStatus: payload.quoteStatus,
    tradingDate: payload.tradingDate
  }, today)
  if (gatedQuote === row) return false

  // The authoritative table permits these named fields only. updatedAt may move
  // only after the atomic price/change tuple has passed the quote-status gate.
  row.price = price
  row.changePercent = changePercent
  row.quoteStatus = gatedQuote.quoteStatus
  if (payload.updatedAt != null) row.priceUpdatedAt = payload.updatedAt
  return true
}

/** JSON numbers and explicit decimal strings are accepted; null/blank/non-numeric values are not zero. */
function isFiniteWireNumber(value) {
  if (typeof value === 'number') return Number.isFinite(value)
  return typeof value === 'string' && value.trim() !== '' && Number.isFinite(Number(value))
}
