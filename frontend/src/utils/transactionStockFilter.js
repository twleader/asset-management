/**
 * 交易紀錄頁「股票」下拉篩選（Requirement 166 / Task 457）。純函式模組，不依賴 Vue，
 * 只操作傳入的 plain array 值，供元件 computed／watch 呼叫，也供 node:test 直接 import 測試。
 */

/**
 * 依「年度卡片」＋「市場 tab」兩層篩選展開 summaries 的紀錄列。邏輯逐字沿用原本內嵌在
 * TransactionView `filteredRecords` computed 裡的行為，只是搬到這支純函式。
 */
export function filterByYearAndMarket(summaries, selectedYear, marketFilter) {
  const byYear = selectedYear === null
    ? summaries.flatMap(s => s.records || [])
    : (summaries.find(x => x.year === selectedYear)?.records || [])
  return marketFilter ? byYear.filter(r => r.market === marketFilter) : byYear
}

/** 股票下拉選項的 key：同一股票代號在不同市場視為不同選項。 */
export function stockOptionKey(assetCode, market) {
  return `${assetCode}__${market || ''}`
}

/** 依當前「年度＋市場」範圍內的紀錄列，收集股票類型的下拉選項（依中文排序）。 */
export function buildStockOptions(records) {
  const map = new Map()
  for (const r of records) {
    if (r.assetType !== '股票' || !r.assetCode) continue
    const key = stockOptionKey(r.assetCode, r.market)
    if (!map.has(key)) map.set(key, { value: key, label: `${r.assetName}（${r.assetCode}）` })
  }
  return Array.from(map.values()).sort((a, b) => a.label.localeCompare(b.label, 'zh-Hant'))
}

/**
 * 疊加股票篩選。空陣列＝全部（股票＋基金皆顯示）；非空時，基金列（assetType !== '股票'）
 * 恆通過、不受此篩選影響，股票列只保留 key 命中已選集合者。
 */
export function filterByStock(records, stockFilter) {
  if (!stockFilter.length) return records
  const selected = new Set(stockFilter)
  return records.filter(r =>
    r.assetType !== '股票' || selected.has(stockOptionKey(r.assetCode, r.market))
  )
}

/**
 * 當 selectedYear／marketFilter 改變（或 CRUD 後 summaries 重新載入）導致 stockOptions
 * 重新計算時，移除已選但已不在新選項清單內的 key；仍有效的已選項要保留，不整批清空。
 * 沒有任何項目失效時回傳原陣列參照，讓呼叫端可用參照相等判斷是否需要寫回。
 */
export function pruneInvalidStockFilter(stockFilter, options) {
  const validKeys = new Set(options.map(o => o.value))
  const next = stockFilter.filter(v => validKeys.has(v))
  return next.length === stockFilter.length ? stockFilter : next
}
