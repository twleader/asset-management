# Claude 精簡內嵌傳輸與未完成附錄規則的規則包發布

日期：2026-11-04（Asia/Taipei）；Task 545。

## 問題與修正

2026-11-04 Claude 09:05 與 12:00 兩輪都沒有附錄。Claude 的 Gmail 工具必須把整份 HTML 內嵌在單次 create_draft，09:05 核心 101KB 約6分鐘才寫入，12:00 核心 59KB 被判「放不下」只建純文字草稿；兩輪都在第60分鐘上限前（剩33／41分鐘）收工，也沒有建立「未完成」附錄草稿。

- `render_daily_report.py` 新增 `--transport auto|full|compact`，預設 auto：Claude 輸出精簡 HTML，Codex 維持完整樣式。精簡版全部文字節點、表格結構與純文字逐字相同；省略各 td 行內樣式，th 保留背景與深色字但去掉 border／padding，表格以 border／cellpadding 屬性呈現；同一個 `verify_email_html.mjs` 檢核。本機渲染實測：09:05 核心 101,465→38,077、12:00 核心 59,186→29,188 位元組。
- 共用規格補上傳輸規則（寫入 Gmail 的內容與渲染檔逐字相同、不得僅憑「放不下」只建純文字）、「未完成」附錄為必交付項，以及 Gmail 轉址的處理說明。

## 凍結身分與 Git 落地

- POLICY_BUNDLE_SHA256：`a157b7676e8efdd4f42c65aec4e8cfe03470f9942c4de85404d6c094d3eaa6c7`
- Manifest bytes SHA256：`41a724cf88184a7d2fe6f8d286b509f4021c4ed95de0abb77b84256ccbb36ea5`
- Model bytes SHA256：`b414138ce25e9d9824964aaad4dbeb7bd3a70c4be8ced728c89f5b47034d69db`（維持）
- Swagger SHA256：`a7ce2c890c437199334b98fb914626ac955d98fbe729ec6c9a498564d52550b4`（維持1.27.0）
- SRPP feature `2662c0570d66cc73d61af823268b043b0711e4b3`，main／origin `f09a5812ee1431e50744d47a4a1b65adefd0b8e1`。
- Asset feature `3b947c3da10f3bf5173fdb3c67b3b2e8051d4c51`，執行資源 main／origin `e2862fb68ba82a29f8976a78b46b3cfa57f1a6a3`。

兩 repo 均 no-ff 合併、推送並精確讀回。登錄前因規格文字修正重建過一次 bundle（`cd0ce66e…`），該版從未登錄。

## 前置驗證

spec-check BLOCK0／CHECK0；兩輪獨立審查；authority checker、兩份 golden（22＋15案例，僅 bundle metadata 差異）、Swagger check、Ruby 13項、release regression 4項及聚焦 Java 測試均通過。

## 正式登錄與部署

20:59:08 單次 insert-only transaction，registry 29→30，舊 row 全文不變，新 row 四欄精確相同；登錄後立即查 9090 回 409（未採用）。21:04 從乾淨 asset main 僅重建／force-recreate business-services，healthy；其餘8容器、effective env 與 mounts 保持；執行 JAR 的 manifest／model／Swagger／registration SQL／policy document 與合併來源逐位元相同。重啟前以唯讀 SQL 逐項證明啟動入口 no-op，啟動日誌無寄信或補抓。

## 9090 讀回

SRPP 主機連線檢查全 200。新 hash 計算包於 21:07:33 由原排程自然發布；兩 consumer 各一次精確 hash GET 為 HTTP 200，契約與身分驗證通過，coverage 7 PARTIAL＋5 UNAVAILABLE、0 COMPLETE，adopted_modules={}，僅屬 INTEGRITY_ACCEPTED_DIAGNOSTIC_ONLY。

## 限制

晚間維護，不補做報告、不建立或修改草稿、不寄信、不交易、不手動觸發 producer。正式當輪採用仍為 NOT_YET_EXECUTED；Claude 遠端 routine 保存指令未更新，維持 REMOTE_SCHEDULE_UNVERIFIED。私人 HTTP 原文與證據不提交公開 repo。
