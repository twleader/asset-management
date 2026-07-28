import { ElMessage } from 'element-plus'

/**
 * 顯示設定儲存回應中夾帶的 Google Drive 自檢警告（Task 247.3.5）。
 *
 * `gdriveSelfCheckWarning` 是**非持久化**的當次結果：後端只在這次請求把 Drive 同步由
 * 關翻成開時才做自檢（讀得到 rclone config、token 有沒有 refresh_token），一切正常回 null。
 * 它存在的理由是 2026-07-28 那次事故——九頁的檔案本機全數照產、Drive 端一份都沒上去，
 * 使用者隔天早上才發現；自檢就是要把「明天早上才知道」提前到「按下儲存的當下」。
 *
 * 三個刻意的行為：
 *  - null／空字串**完全不顯示**。每次存檔都跳一則「檢查通過」是純噪音，久了連真的警告也會被略過。
 *  - 一律 warning 而非 error。設定本身已經存進去了（自檢失敗不擋儲存，否則使用者連修正的入口都被鎖死），
 *    本機檔案也照常產生，壞掉的只有 Drive 這份附加副本。
 *  - **不碰頁面上的 gdriveLastRunAt／gdriveLastStatus**。那兩欄的語意是「上次上傳」的結果，
 *    把自檢結果寫進去會蓋掉昨晚真正成功的落點與大小，還會顯示成一個根本沒上傳過的時刻（Task 247.3.4）。
 *
 * 停留時間拉長到 10 秒並開放手動關閉：警告內含要照抄的 rclone 修復指令（例如帶
 * `prompt=consent` 的 reconnect），預設 3 秒讀不完更別說複製。
 *
 * @param {string|null|undefined} warning 後端回應的 gdriveSelfCheckWarning 欄位
 */
export function showGdriveSelfCheckWarning(warning) {
  const text = (warning || '').trim()
  if (!text) return
  ElMessage({ type: 'warning', message: text, duration: 10000, showClose: true })
}
