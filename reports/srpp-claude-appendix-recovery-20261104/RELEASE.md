# Claude 早盤附錄續作修復與規則包發布

日期：2026-11-04（Asia/Taipei）；Task 543。

## 問題與修正

Claude 09:05 在第26分47秒結束，理由為時間預算；附錄未產出，距原60分鐘上限仍有33分13秒。核心草稿讀回另因 Gmail 轉址 `ust` 追蹤參數被當成報告硬數字而失敗。

- 郵件檢核僅解開精確 `/url` 的已知 Gmail 閉合轉址格式，保留目標網址本身及其數字。未知／重複／額外參數或錯誤大小寫路徑保留原文；真正價格／數量差異仍拒絕。原歷史 MIME 的離線回歸36／36通過，改動價格負例仍拒絕；不改寫歷史結果或草稿。
- `daily_timing.py --progress` 使用真實時鐘，提供35／40／60分鐘剩餘秒數；核心已建立但檢核失敗時繼續整理附錄，保留核心失敗及原版。提示只供交付時程，不核准資料／金融／郵件動作、不放寬原停止閘門、不延長上限。
- 共用規格及兩個Claude入口明示核心失敗不連帶停止附錄，禁止在實際失敗前以預估時間／token預算收工。沒有任何已驗證草稿時仍為INCOMPLETE。
- 發布前補齊既有 staged 檢查清單漏列的精確美股consumer schema，未放寬來源選擇或驗證。

## 凍結身分與 Git 落地

- POLICY_BUNDLE_SHA256：`396a1335528b53059e603cc7a06623a65b8e7a1b44306351b98016d425e51170`
- Manifest bytes SHA256：`0737c6fc8e9e4d1771cfca1ac26b0db384b41e1d40e4bccbd8b4296e5c2a6768`
- Model bytes SHA256：`b414138ce25e9d9824964aaad4dbeb7bd3a70c4be8ced728c89f5b47034d69db`（維持）
- Swagger SHA256：`a7ce2c890c437199334b98fb914626ac955d98fbe729ec6c9a498564d52550b4`（維持1.27.0）
- SRPP feature：`1c396f793a17ed233fe869dd242c29e115b21abb`；main／origin：`60edb2dbeca2756bc445a6112bd27457a2c0a545`。
- Asset feature：`119c80d3acd666eed742b482c27c2b01f1c0f235`；執行資源main／origin：`897f38c46be5a480893705988b97dab76d748387`。

兩repo均no-ff合併、推送既有遠端並精確讀回feature/main；原先自動核准拒絕已在使用者明確確認兩儲存庫所有權與推送授權後續作。SRPP實際工作區安全ff-only同步，保留原分支、4個未追蹤根項目及1,775筆路徑／內容。最終凍結後沒有再產生其他hash，也未混入未提交工作。

## 前置驗證

獨立規格及架構審查無未解critical／major／minor；spec-check BLOCK0／CHECK0，正式PG117表schema全文一致。郵件98項、日報執行41項、Java聚焦42項、Ruby13項／307 assertions、release gate4項均通過。額外policy回歸36項中34項在整組通過，其餘2項補入原始ignored離線輸入後單項重驗通過；錯誤與補入來源hash保留，不觸發來源刷新。

資產端只更新manifest、insert-only SQL與兩份golden的bundle metadata；22＋15案例、calculator hashes、model、formal targets及formula manifest逐值／bytes不變。Swagger renderer／identity只做check且成功。Maven離線package與JAR authority bytes比對通過，與正式部署驗收分開記錄。

## 正式登錄

13:30:19單次insert-only transaction，registry28→29；原28筆含registered_at全文不變，新row的bundle／formula version／policy document／formula manifest四欄精確相同。立即從SRPP主機以新hash讀report-calculations，部署前實回409 POLICY_UNSUPPORTED，problem七欄與private,no-store契約通過；該回應不算API採用成功。

## 正式部署與實機驗證

13:31:25從乾淨asset main僅重建／force-recreate business-services，container `99cd7af539002047b4c41a5e8368cd674db39447f2aabecc22a6896f3d969786`、image `sha256:67600fb356efbe5f9f75e5e6dad91c15503ab94d3ac594ddec75497c55d493c6`，healthy；BFF UP、gateway首試恢復。其餘8容器ID/image全部保持，52項effective environment、main.env及mounts與原正式設定一致。執行JAR SHA256 `d689012bf1b171abee06951bb6b563061f787fac09060e3758d3cccd490d28b6`，model／manifest／Swagger／registration SQL／formal document五項bytes與合併main精確相同，另經獨立唯讀查核確認。

13:31:57兩consumer的新hash GET首先回503 CALENDAR_UNAVAILABLE；一般9090連線檢查則日曆200 AVAILABLE／365日、TWSE日線21筆、台股報價23筆全部通過。查明新容器的cached-only日曆尚空，而合法公開日曆GET依原功能填入本機cache；13:33:15單次有界重驗改為503 CONTEXT_NOT_READY，仍不算API接受。

其後唯讀DB查得兩consumer新hash包由原每分鐘排程於13:34:29.879819自然發布，沒有呼叫producer或重跑報告。以這項實際狀態變化為依據，各作一次唯讀GET：Claude13:35:58與Codex13:36:00均HTTP200。schema、content/input/source JCS hash鏈、model/manifest原bytes hash與owner1／consumer／日期／12:00／policy／Swagger精確身分全部通過。coverage仍7 PARTIAL＋5 UNAVAILABLE、0 COMPLETE；adopted_modules={}、trade_authorization=false，僅屬INTEGRITY_ACCEPTED_DIAGNOSTIC_ONLY。

原409及兩階段503的HTTP原文、headers、SHA與收據完整保留，沒有用成功新檔覆蓋失敗歷程。整輪只build/recreate一次、只登錄一次，未改碼或放寬gate換取200。

兩個Claude本機SKILL已從合併來源同步並逐位元讀回（保留舊版私人備份），未改cron、model、權限或觸發執行。完整runtime policy check的host_policy_status=PASS；Claude已遷移遠端的保存指令沒有新鮮裝置驗證，整體維持REMOTE_SCHEDULE_UNVERIFIED，claude_guest_execution_verified=false。不能把本機入口／共用規格更新稱為遠端副本已更新或一輪已實跑。

## 限制與證據

本輪為修復／發布維護，不補做早盤、不建立或修改草稿、不寄信、不下單、不手動觸發producer或刷新來源。HTTP診斷完整性與正式當輪計算採用分開；後者需原合法時窗、完整必讀、同owner直接L0及獨立verifier，未完成時維持NOT_YET_EXECUTED。

私人完整證據：`/private/tmp/claude-appendix-release-20261104/`。HTTP原文、資產資料及設定秘密不提交公開repo；本文件只保存去識別摘要。

本輪後續收尾提交只更新本任務完成報告與發布摘要；執行資源bytes未變，沿用已驗證image，不為文件重建服務。
