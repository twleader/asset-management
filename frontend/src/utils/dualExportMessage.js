import { ElMessage } from 'element-plus'

/**
 * 顯示「手動匯出」的結果（Requirement 55 / Task 282）。
 *
 * 九個匯出頁一律呼叫這一支，不各寫一份 template literal——理由與後端把落檔收斂進
 * `DualFormatExportWriter` 完全相同：九份各寫一次，措辭與判準必然分歧，而「只成功一份」
 * 正是最少被觸發、最容易在複製貼上中漏掉的那一支。
 *
 * 本函式存在的直接原因是 2026-08-02 的使用者回報：「應該匯出 json 和 excel，結果只匯出 excel」。
 * 檔案其實兩份都在，是九頁的成功提示都只讀了 response 的**一個**路徑欄位（t270.3／t271.5／
 * t272.3 都寫了要顯示兩個落點，但都沒實作）。UI 說謊的成本等同功能真的壞掉，而且更糟：
 * 真的壞掉使用者會來報修，說謊只會讓人失去對這個功能的信任。
 *
 * **輸入是兩個具名落點，不是整包 response。** 九頁的欄位名不一致：第 1～8 頁 `path`＝xlsx、
 * `jsonPath`＝json；警示觸發頁相反（`path`＝json，`alert_triggers_*.json` 是該頁的對外契約）。
 * 在這裡硬編欄位名會讓那一頁把兩份**說反**，而說反是靜默的——兩個檔案都在，只有文字錯。
 *
 * 五分支，順序即判斷順序（先判本機落點、再判 Drive）：
 *  1. 兩個落點皆空 → warning「未產出任何檔案」
 *  2. 恰一個為空   → warning，列出成功那一份並明講缺哪一種格式
 *  3. Drive 確定沒上去（失敗／略過／跳過）→ warning ＋狀態字串
 *  4. Drive 結果未定（逾時／暫時未上傳）  → info    ＋狀態字串
 *  5. 其餘 → success，兩個落點都列出；Drive 已啟用時一併附上狀態
 *
 * 分支 3／4 刻意分開：`失敗`／`跳過`／`略過` 是確定沒上去（要人介入）；`逾時` 的後端措辭明寫
 * 「Drive 端可能已完成，請於下一輪確認」、`暫時未上傳` 明寫「下一輪排程會自動重試」——
 * 塗成黃色警告等於要使用者為一件系統會自己處理的事採取行動。
 *
 * 分支 1 走 warning 而非 error：能走到這裡代表 HTTP 已成功回應，真正的例外由各頁自己的
 * catch 處理；這裡只負責「跑完了但什麼都沒產出」那一格。
 *
 * @param {object}  r
 * @param {string?} r.jsonPath     `.json` 那一份的落點（該份失敗時為 null）
 * @param {string?} r.xlsxPath     `.xlsx` 那一份的落點（同上）
 * @param {string?} r.gdriveStatus 後端合併好的 Drive 狀態；Drive 未啟用時為 null
 * @param {string?} r.prefix       呼叫端自己的前綴資訊（警示觸發頁的 r.message），預設無
 */
export function showDualExportResult({ jsonPath, xlsxPath, gdriveStatus, prefix = '' } = {}) {
  // 判空一律走 trim 後為空字串，一次涵蓋 null／undefined／''——任何分支都不得把 null 印進訊息
  const json = String(jsonPath ?? '').trim()
  const xlsx = String(xlsxPath ?? '').trim()
  const gd = String(gdriveStatus ?? '').trim()
  const head = String(prefix ?? '').trim() ? `${String(prefix).trim()}；` : ''

  if (!json && !xlsx) {
    return show('warning', `${head}本輪未產出任何檔案，請檢查輸出資料夾權限與磁碟空間`)
  }
  if (!json || !xlsx) {
    return show('warning',
      `${head}已匯出 ${json || xlsx}，但 ${json ? 'Excel' : 'JSON'} 那一份本輪未產出`)
  }

  // 本機兩份都在，才輪到 Drive。判準是**白名單**：兩半都要以「成功：」起頭。
  // 後端單邊狀態有五種（成功／逾時／暫時未上傳／失敗／跳過，另有兩份未皆成功時的「略過」），
  // 寫成「含失敗或跳過」的黑名單會讓逾時與暫時未上傳落進綠色成功——那就是同一類 UI 說謊；
  // 簡化成「整串含成功兩字」更糟，「xlsx 失敗、json 成功」會被判成完全成功。
  // 兩半各自從**尾端**截斷至 250 字元後才合併，故「成功：」前綴必然存活，前綴判準安全。
  const bothOk = /^xlsx 成功：/.test(gd) && gd.includes('／json 成功：')
  if (gd && !bothOk) {
    return gd.includes('失敗') || gd.includes('略過') || gd.includes('跳過')
      ? show('warning', `${head}本機兩份已寫出，但 Google Drive 同步未全部成功：${gd}`)
      : show('info', `${head}本機兩份已寫出；Google Drive：${gd}`)
  }

  // Drive 成功時也附上狀態：警示觸發頁原本的 ElMessage.info 是全庫唯一會顯示 Drive 落點的地方，
  // 收斂到這裡後若成功分支不附，那一頁的 Drive 落點就在畫面上消失了。
  return show('success', `${head}已匯出 ${json} 與 ${xlsx}${gd ? `；Google Drive：${gd}` : ''}`)
}

/** 訊息含兩個絕對路徑（中文檔名 ＋ 深層子路徑），預設 3 秒讀不完，故延長並開放手動關閉。 */
function show(type, message) {
  ElMessage({ type, message, duration: 8000, showClose: true })
}
