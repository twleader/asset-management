/**
 * 將字串中的 HTML 特殊字元轉義，供「會被當成 raw HTML 渲染」的地方使用
 * （例如 ECharts tooltip 的 formatter 回傳字串）。
 *
 * 資安（Requirement 29）：持股股名／代號等為使用者可自由輸入的自由文字，
 * 若未轉義即插入 tooltip HTML，會造成 stored XSS（管理者代看該使用者時於 admin session 觸發，
 * 可提權呼叫 admin API）。所有把外部字串拼進 HTML 的地方一律先經本函式。
 *
 * @param {*} value 任意值（null/undefined → 空字串；其餘 toString 後轉義）
 * @returns {string} 已轉義、可安全內嵌於 HTML 的字串
 */
export function escapeHtml(value) {
  if (value == null) return ''
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}
