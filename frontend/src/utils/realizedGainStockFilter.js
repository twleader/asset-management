/**
 * 已實現損益頁「股票」下拉篩選（Requirement 168 / Task 459）。只處理已載入的
 * 記錄列；RealizedGainResponse 沒有 assetType，因此以非空白 assetCode 判斷是否可選。
 */

function normalizedAssetCode(assetCode) {
  return String(assetCode ?? '').trim()
}

/** 同一代號在不同市場是不同的下拉選項。 */
export function realizedGainStockOptionKey(assetCode, market) {
  return `${normalizedAssetCode(assetCode)}__${market || ''}`
}

/** 只收集目前年度／市場範圍內有代號的記錄，依繁中名稱排序。 */
export function buildRealizedGainStockOptions(records) {
  const options = new Map()
  for (const record of records) {
    const assetCode = normalizedAssetCode(record.assetCode)
    if (!assetCode) continue

    const value = realizedGainStockOptionKey(assetCode, record.market)
    if (!options.has(value)) {
      options.set(value, {
        value,
        label: `${record.assetName || ''}（${assetCode}）`
      })
    }
  }
  return [...options.values()].sort((a, b) => a.label.localeCompare(b.label, 'zh-Hant'))
}

/**
 * 空選取顯示全部；有選取時只過濾有代號的列。沒有代號的舊資料無從歸類，始終保留顯示。
 */
export function filterRealizedGainByStock(records, stockFilter) {
  if (!stockFilter?.length) return records

  const selected = new Set(stockFilter)
  return records.filter(record => {
    if (!normalizedAssetCode(record.assetCode)) return true
    return selected.has(realizedGainStockOptionKey(record.assetCode, record.market))
  })
}

/** 範圍或資料更新時只清除失效選項，保留仍在目前範圍內的選取。 */
export function pruneInvalidRealizedGainStockFilter(stockFilter, options) {
  const validKeys = new Set(options.map(option => option.value))
  const next = stockFilter.filter(value => validKeys.has(value))
  return next.length === stockFilter.length ? stockFilter : next
}
