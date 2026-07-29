/**
 * 本地日期工具（Requirement 53 / Task 252）。
 *
 * **不可用 `new Date().toISOString().slice(0, 10)` 取「今天」** —— `toISOString()` 是 UTC，
 * 在台北 00:00–08:00 之間會得到「昨天」。這會造成兩個問題：
 *   1. 表單預設日期（新增交易／已實現損益／新增快照）在清晨變成前一天
 *   2. 後端 `SnapshotFormBffController` 的 `isToday` 是拿**後端**的今日跟本字串比對；
 *      後端時區改為 Asia/Taipei 後，前端若仍送 UTC 日期，兩邊在清晨就會分家 →
 *      判定不是「今天」→ 不刷即時匯率 → 美英股部位用舊 USD 匯率換算
 *
 * `sv-SE` locale 的日期格式即為 `yyyy-MM-dd`，且 `toLocaleDateString` 走的是瀏覽器本地時區。
 */

/** 本地時區的今天，格式 `yyyy-MM-dd`。 */
export function todayLocal() {
  return new Date().toLocaleDateString('sv-SE')
}

/** 把 Date 物件（或既有字串）正規化成本地時區的 `yyyy-MM-dd`。 */
export function toLocalDateString(d) {
  if (!d) return ''
  return d instanceof Date ? d.toLocaleDateString('sv-SE') : String(d).slice(0, 10)
}
