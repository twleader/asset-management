/**
 * 匯出設定編輯一律在草稿上進行。這裡刻意不保留 API 或頁面狀態，讓各頁仍能沿用原本
 * 的 endpoint、驗證與 server response 轉換；唯一共用的契約是草稿和已儲存設定不共用
 * 巢狀陣列或物件。
 */
export function cloneExportSetting(value) {
  // API setting DTO 僅能是 JSON 欄位。JSON clone 同時把 Vue proxy 正規化為 plain data，
  // 因此 nested times 不會與 canonical 共用，也不會因 structuredClone(proxy) 拋錯。
  return JSON.parse(JSON.stringify(value))
}

/**
 * 將已儲存設定替換為伺服器回傳的 canonical response。直接覆寫而非 reload，避免成功
 * response 與下一次背景刷新之間把使用者剛保存的正規化結果丟掉。
 */
export function replaceExportSetting(target, source) {
  for (const key of Object.keys(target)) delete target[key]
  Object.assign(target, cloneExportSetting(source))
}

/**
 * 將一次保存序列化。呼叫端保留 draft，只有 API 成功後才以 response 更新 canonical；
 * 失敗時由呼叫端顯示錯誤，草稿不會被此函式碰觸。
 */
export async function persistExportSettingDraft({ busy, save, payload, apply }) {
  if (busy.value) return { skipped: true }
  busy.value = true
  try {
    const response = await save(cloneExportSetting(payload()))
    apply(response)
    return { response }
  } finally {
    busy.value = false
  }
}
