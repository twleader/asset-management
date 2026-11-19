/** 富邦排程開關（Requirement 218／Task 612）的畫面純函式。 */

/** [{scheduleKey, enabled}] → { key: enabled }；非陣列或缺 key 的列忽略。 */
export function toSwitchMap(rows) {
  const map = {}
  if (!Array.isArray(rows)) return map
  for (const row of rows) {
    if (row && typeof row.scheduleKey === 'string' && row.scheduleKey) {
      map[row.scheduleKey] = row.enabled === true
    }
  }
  return map
}

/** 該列是否有可操作的開關：有 scheduleKey，且後端已回傳該 key 的狀態。 */
export function hasSwitch(row, switchMap) {
  return Boolean(row && row.scheduleKey && Object.prototype.hasOwnProperty.call(switchMap || {}, row.scheduleKey))
}
