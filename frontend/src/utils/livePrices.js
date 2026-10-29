import { marketToday, mergeSseQuote } from './displayQuote.js'

const identity = q => `${q?.market}\u0000${q?.stockCode}`
const own = (o, key) => Object.prototype.hasOwnProperty.call(o, key)
function timestamp(value) {
  if (typeof value !== 'string') return null
  const match = value.match(/T\d{2}:\d{2}:\d{2}(?:\.(\d{1,9}))?(Z|[+-]\d{2}:\d{2})?$/)
  if (!match) return null
  // Cache local timestamps are emitted in Taipei, regardless of the viewing browser's zone.
  const millis = Date.parse(match[2] ? value : `${value}+08:00`)
  if (!Number.isFinite(millis)) return null
  return BigInt(millis) * 1000000n + BigInt((match[1] ?? '').padEnd(9, '0').slice(3))
}
function time(q) {
  const values = [timestamp(q?.updatedAt), timestamp(q?.priceUpdatedAt)].filter(v => v != null)
  return values.length ? values.reduce((a, b) => a > b ? a : b) : null
}
const validNumber = value => (typeof value === 'number' || (typeof value === 'string' && value.trim() !== '')) && Number.isFinite(Number(value))
const quoteDate = q => q?.tradingDate ?? q?.asOfDate
export function mergeLiveQuote(current, incoming, today = marketToday(incoming?.market)) {
  const date = quoteDate(incoming), currentDate = quoteDate(current)
  const status = incoming?.quoteStatus
  if (current?.market && current?.stockCode && identity(current) !== identity(incoming)) return current
  if (!incoming || !['台股', '美股', '英股'].includes(incoming.market) || !incoming.stockCode
    || !/^\d{4}-\d{2}-\d{2}$/.test(date ?? '')
    || !['LIVE', 'VERIFIED_CLOSE', 'CLOSE_PENDING', 'PREVIOUS_CLOSE'].includes(status)) return current
  const change = own(incoming, 'changePercent') ? incoming.changePercent : incoming.changePct
  if (incoming.price != null && (!validNumber(incoming.price) || Number(incoming.price) <= 0)) return current
  if (change != null && !validNumber(change)) return current
  if (status === 'LIVE' && (date !== today || time(incoming) == null
    || !validNumber(incoming.price) || Number(incoming.price) <= 0
    || !(own(incoming, 'changePercent') || own(incoming, 'changePct')) || !own(incoming, 'priceChange')
    || !(change === null || validNumber(change))
    || !(incoming.priceChange === null || validNumber(incoming.priceChange)))) return current
  if (status === 'VERIFIED_CLOSE' && (!validNumber(incoming.price) || Number(incoming.price) <= 0)) return current
  if (currentDate && date < currentDate) return current
  const sameDate = currentDate === date
  const newSession = currentDate && date > currentDate
  // Missing session dates never unlock an official close fence.
  if (!newSession && ['CLOSE_PENDING', 'VERIFIED_CLOSE'].includes(current?.quoteStatus)
    && !(status === 'VERIFIED_CLOSE' && date === today)) return current
  if (!newSession && time(current) != null && (time(incoming) == null || time(incoming) < time(current))) return current
  const normalized = { ...incoming, tradingDate: date, ...(own(incoming, 'changePct') && !own(incoming, 'changePercent') ? { changePercent: incoming.changePct } : {}) }
  if (status === 'LIVE' && (change === null || incoming.priceChange === null)) {
    normalized.changePercent = null
    normalized.priceChange = null
  }
  const gated = mergeSseQuote(sameDate ? current : null, normalized, today)
  if (gated !== normalized) return current
  return { ...current, ...normalized }
}

/** Quote-only allow-list. Strategies, alert snapshots and indicators never move here. */
export function patchLiveRow(row, quote, kind = 'watch') {
  if (!row || identity(row) !== identity(quote)) return false
  const change = own(quote, 'changePercent') ? quote.changePercent : quote.changePct
  if (kind === 'radar' && quote.quoteStatus !== 'CLOSE_PENDING'
    && (!validNumber(quote.price) || Number(quote.price) <= 0
      || (quote.quoteStatus === 'PREVIOUS_CLOSE' && !validNumber(change)))) return false
  const next = mergeLiveQuote(row, quote)
  if (next === row) return false
  if (kind === 'radar' && next.priceChange === null && own(row, 'priceChange')) row.priceChange = null
  const mapping = kind === 'form'
    ? { price: 'latestPrice', priceChange: 'priceChange', changePercent: 'priceChangePct', source: 'priceSource', quoteStatus: 'quoteStatus', tradingDate: 'tradingDate', updatedAt: 'priceUpdatedAt' }
    : { price: 'price', priceChange: 'priceChange', changePercent: 'changePercent', source: 'priceSource', quoteStatus: 'quoteStatus', tradingDate: 'tradingDate', updatedAt: 'priceUpdatedAt', openPrice: 'openPrice', highPrice: 'highPrice', lowPrice: 'lowPrice', volume: 'volume', previousClose: 'previousClose', closed: 'closed' }
  if (kind === 'radar') {
    for (const key of ['openPrice', 'highPrice', 'lowPrice', 'volume', 'previousClose', 'closed', 'priceChange']) delete mapping[key]
  }
  for (const [from, to] of Object.entries(mapping)) {
    if (from === 'previousClose' && String(row.stockCode) === '0000') continue
    if (own(quote, from) || (from === 'changePercent' && own(quote, 'changePct'))) row[to] = next[from]
  }
  return true
}

/** At most one in-flight read, bounded throttle and one trailing read after the final event. */
export function createTrailingRefresh(read, { delay = 0, interval = 1000, tail = false, now = Date.now, setTimer = setTimeout, clearTimer = clearTimeout } = {}) {
  let timer = null, pending = false, running = false, disposed = false, last = -Infinity, tailDue = false, revision = 0
  function arm() {
    if (disposed || running || timer != null || !pending) return
    timer = setTimer(run, Math.max(delay, interval - (now() - last)))
  }
  async function run() {
    timer = null
    if (disposed || !pending) return
    pending = false; running = true; last = now()
    const needsTail = tailDue, readRevision = revision; tailDue = false
    try { await read() } catch { /* Background reads preserve the last complete snapshot. */ }
    finally {
      running = false
      if (needsTail && !disposed && readRevision === revision) pending = true
      arm()
    }
  }
  return {
    request() { if (disposed) return; pending = true; tailDue = tail; arm() },
    cancel() { revision++; if (timer != null) clearTimer(timer); timer = null; pending = false; tailDue = false },
    dispose() { this.cancel(); disposed = true }
  }
}

/** Browser-independent transport core; EventSource construction belongs to the API adapter. */
export function createLivePriceStream({ connect, document: doc, maxQuotes = 1000, marketToday: todayForMarket = marketToday }) {
  const listeners = new Set(), latest = new Map()
  let source = null, closed = false
  const visible = () => !doc?.hidden
  function notify(method, value) { for (const listener of [...listeners]) { try { listener[method]?.(value) } catch (e) { console.warn('即時行情處理失敗', e) } } }
  function stop() { source?.close(); source = null }
  function start() {
    if (source || closed || !listeners.size || !visible()) return
    source = connect()
    const connection = source
    source.addEventListener('price-update', event => {
      if (source !== connection || !visible()) return
      let quote
      try { quote = JSON.parse(event.data) } catch { return }
      const key = identity(quote), current = latest.get(key), next = mergeLiveQuote(current, quote, todayForMarket(quote?.market))
      if (!next || next === current) return
      latest.delete(key); latest.set(key, next)
      if (latest.size > maxQuotes) latest.delete(latest.keys().next().value)
      notify('onQuote', next)
    })
    source.onopen = () => { if (source === connection && visible()) notify('onCatchup') }
    source.onerror = () => {
      if (source === connection && source?.readyState === 2) { closed = true; stop() }
    }
  }
  function visibility() {
    if (!visible()) { stop(); notify('onPause') }
    else { start(); notify('onCatchup') }
  }
  return {
    subscribe(listener) {
      const alreadyOpen = source?.readyState === 1
      const first = listeners.size === 0
      listeners.add(listener)
      if (first) { closed = false; doc?.addEventListener('visibilitychange', visibility) }
      start()
      if (alreadyOpen && visible()) listener.onCatchup?.()
      return () => {
        listeners.delete(listener)
        listener.onPause?.()
        if (!listeners.size) { stop(); latest.clear(); doc?.removeEventListener('visibilitychange', visibility) }
      }
    },
    overlay(quote) {
      const key = identity(quote), cached = latest.get(key), today = todayForMarket(quote?.market)
      const next = mergeLiveQuote(cached, quote, today)
      if (next && next !== cached) {
        latest.delete(key); latest.set(key, next)
        if (latest.size > maxQuotes) latest.delete(latest.keys().next().value)
      }
      return latest.has(key) ? (mergeLiveQuote(quote, latest.get(key), today) ?? quote) : quote
    },
    quotes() { return [...latest.values()] },
    visible
  }
}
