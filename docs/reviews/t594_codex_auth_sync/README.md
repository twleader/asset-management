# Codex 訂閱登入同步驗收

Task 594；2026-11-15（Asia/Taipei）。修正原bridge永久沿用第一次登入副本、與一般Codex CLI更新後登入脫節的問題。

每個操作唯讀取得目前正常登入，建立僅含access token的暫時外部登入投影；不複製refresh token、不代為刷新或寫回一般登入、不回退舊私有副本。真正執行前的訂閱／費用證據與模型解讀使用同一份投影，避免檢查及執行期間切換帳號。執行仍限定既有個人訂閱、未啟用付費credits及可用額度；未知即停止，沒有API或付費fallback。

登入不可取得（AI_AUTH_REQUIRED）、登入失效（AI_AUTH_EXPIRED）與費用狀態不明（AI_BILLING_UNVERIFIED）分別顯示安全說明。僅更新host、backend安全碼與前端文案，不改BFF wire、資料庫、數值計算、9090或SRPP政策。

[規格審查](spec-review.md) · [機械檢查](spec-check.log) · [操作說明](../../operations/srpp-local-ai.md)

Python橋接91項、backend65項、frontend192項測試通過；主代理獨立重跑Python91項及frontend192項。涵蓋正常登入輪替、拒絕舊副本回退、不安全來源、同snapshot檢查與執行、登入/費用錯誤分流、額外付費拒絕零推論，以及外層硬逾時的程序與暫存憑證清理。

[測試與程式雜湊](tests.json) · [架構審查](arch-review.md) · [實機部署收據](feature-runtime.json)。規格及架構獨立審查均PASS，無finding。

實機host與business center均取得Codex RUNTIME 8個模型；單次合成解讀以gpt-6-luna／low設定成功（HTTP200、SUBSCRIPTION、10.70秒），CLI未回報實際模型，收據如實標REQUESTED_ONLY，不冒充供應者模型證據。原正常登入檔bytes及權限保持一致，沒有fallback。business及frontend均由feature來源重建並recreate，BFF未變；正式域名TLS、唯讀9090、既有LOCAL/AI run雜湊與歷史收據回讀一致。

瀏覽器載入實際部署production assets，透過合成登入/BFF回應驗Codex工具、model與effort可選；AUTH_REQUIRED、AUTH_EXPIRED與BILLING_UNVERIFIED三種狀態皆顯示不同安全文案、工具不可用且停用AI模式。此瀏覽器測試無真登入、推論或API寫入；真實登入解讀由前述host smoke另驗。

完成Git發布後，host來源與重建來源切回main，再驗metadata、business center、歷史hash與TLS。不保存token、帳戶資料、憑證hash或完整模型文字；最終main部署收據保存在私有驗收目錄。
