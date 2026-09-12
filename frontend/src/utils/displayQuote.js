const MARKET_ZONES = {
  台股: 'Asia/Taipei',
  美股: 'America/New_York',
  英股: 'Europe/London'
}

export function marketToday(market, now = new Date()) {
  const zone = MARKET_ZONES[market] || MARKET_ZONES.台股
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: zone,
    year: 'numeric', month: '2-digit', day: '2-digit'
  }).formatToParts(now)
  const value = Object.fromEntries(parts.map(part => [part.type, part.value]))
  return `${value.year}-${value.month}-${value.day}`
}

export function isClosePending(quote) {
  return quote?.quoteStatus === 'CLOSE_PENDING'
}

export function isAcceptedTodayQuote(quote, today) {
  if (!quote || !today || quote.tradingDate !== today) return false
  return quote.quoteStatus === 'LIVE' || quote.quoteStatus === 'VERIFIED_CLOSE'
}

/**
 * SSE 只能推進正常 live 狀態。畫面一旦由 API 判為等待官方收盤或已驗證收盤，
 * 舊 poller 的盤中 payload 不得再把它覆蓋回最後成交價。
 */
export function mergeSseQuote(current, incoming, today = marketToday(incoming?.market)) {
  // The stream contract must carry an explicit status.  Do not turn an incomplete
  // event into LIVE: callers use this same gate before changing any displayed quote.
  if (!incoming || incoming.quoteStatus == null) return current
  if (current?.quoteStatus === 'CLOSE_PENDING' || current?.quoteStatus === 'VERIFIED_CLOSE') {
    return incoming.quoteStatus === 'VERIFIED_CLOSE' && incoming.tradingDate === today
      ? incoming
      : current
  }
  if (incoming.quoteStatus === 'LIVE' && incoming.tradingDate !== today) return current
  return incoming
}

export function applyEnrichedQuote(row, quote) {
  if (!row || !quote) return row
  row.quoteStatus = quote.quoteStatus ?? null
  row.priceSource = quote.source ?? null
  if (isClosePending(quote)) {
    row.latestPrice = null
    row.priceChange = null
    row.priceChangePct = null
    return row
  }
  row.latestPrice = quote.price != null ? Number(quote.price) : null
  row.priceChange = quote.priceChange != null ? Number(quote.priceChange) : null
  row.priceChangePct = quote.changePercent != null ? Number(quote.changePercent) : null
  return row
}

export function quoteStatusLabel(quote) {
  return isClosePending(quote) ? '收盤價待補' : ''
}
