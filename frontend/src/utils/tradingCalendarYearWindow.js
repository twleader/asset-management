const MARKETS = ['tw', 'us', 'uk']

export function taipeiDateParts(now = new Date()) {
  const parts = new Intl.DateTimeFormat('en-US-u-ca-gregory-nu-latn', {
    timeZone: 'Asia/Taipei', year: 'numeric', month: 'numeric', day: 'numeric'
  }).formatToParts(now)
  const values = Object.fromEntries(parts.filter(p => p.type !== 'literal').map(p => [p.type, Number(p.value)]))
  return {
    year: values.year,
    month: values.month,
    day: values.day,
    date: `${values.year}-${String(values.month).padStart(2, '0')}-${String(values.day).padStart(2, '0')}`
  }
}

export function emptyCalendarEntry() {
  return {
    holidays: { tw: {}, us: {}, uk: {} },
    availability: { tw: 'UNAVAILABLE', us: 'UNAVAILABLE', uk: 'UNAVAILABLE' }
  }
}

export function calendarEntry(cache, year) {
  return cache?.[year] || emptyCalendarEntry()
}

export function unavailableMarketKeys(entry) {
  return MARKETS.filter(market => entry?.availability?.[market] !== 'AVAILABLE')
}

export function marketTradingFlag(entry, market, isWeekend, date) {
  if (entry?.availability?.[market] !== 'AVAILABLE') return null
  return !isWeekend && !entry?.holidays?.[market]?.[date]
}

export function calendarDayClass(day) {
  if (!day?.day) return 'empty'
  if (![day.tw, day.us, day.uk].every(value => typeof value === 'boolean')) return ''
  if (day.isWeekend) return 'weekend'
  if (day.tw && day.us && day.uk) return 'both'
  if (!day.tw && !day.us && !day.uk) return 'holiday'
  return ''
}

export function moveCalendarMonth(year, month, delta, minYear, maxYear) {
  if (!Number.isInteger(minYear) || !Number.isInteger(maxYear)) return { year, month }
  const shifted = month + delta
  const target = shifted < 1
    ? { year: year - 1, month: 12 }
    : shifted > 12
      ? { year: year + 1, month: 1 }
      : { year, month: shifted }
  return target.year < minYear || target.year > maxYear ? { year, month } : target
}

export function isMinMonth(year, month, minYear) {
  return year === minYear && month === 1
}

export function isMaxMonth(year, month, maxYear) {
  return year === maxYear && month === 12
}

export function calendarExportYears(availableYears, currentYear) {
  const years = (availableYears || []).filter(year => year === currentYear || year === currentYear + 1)
  return years.length === 2 ? years : []
}

export function hasLocalExport(result) {
  return Boolean(result?.path || result?.jsonPath)
}
