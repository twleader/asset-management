/**
 * 已實現損益頁的交易日期排序（Requirement 167 / Task 458）。
 * 只處理已載入的顯示列，回傳新陣列且不改寫 BFF 回傳的原始資料。
 */

/**
 * 以 ISO `YYYY-MM-DD` 交易日期排序；同日一律維持輸入列順序。
 *
 * @param {Array<{tradeDate: string}>} records
 * @param {'ascending' | 'descending' | null} order
 * @returns {Array<{tradeDate: string}>}
 */
export function sortRealizedGainRecords(records, order) {
  const indexed = records.map((record, index) => ({ record, index }))
  if (order !== 'ascending' && order !== 'descending') return indexed.map(item => item.record)

  const direction = order === 'ascending' ? 1 : -1
  return indexed
    .sort((left, right) => {
      const dateComparison = left.record.tradeDate.localeCompare(right.record.tradeDate)
      return dateComparison === 0 ? left.index - right.index : direction * dateComparison
    })
    .map(item => item.record)
}

/**
 * Element Plus 點擊已啟用的排序 caret 時會回報 null；本頁只允許兩個排序方向，
 * 因此保留目前方向並供 table.sort() 同步指示器。
 */
export function resolveRealizedGainDateSortOrder(currentOrder, receivedOrder) {
  if (receivedOrder === 'ascending' || receivedOrder === 'descending') return receivedOrder
  return currentOrder === 'ascending' || currentOrder === 'descending' ? currentOrder : 'ascending'
}
