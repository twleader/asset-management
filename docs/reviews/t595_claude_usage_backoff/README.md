# Claude 費用查詢限流處理驗收

Task 595；2026-11-16（Asia/Taipei）。修正usage HTTP429被顯示為一般費用未知，以及Java選擇驗證／interpret、host parent與worker多層探測造成的重複查詢。

明確載入工具清單才可探測全部工具；Java與host parent只驗已顯示的目錄快照，worker仍對所選工具執行必要的fresh費用/模型檢查。目錄及metadata成功快取不是推論授權。Claude限流不影響Codex或本機模型；429會顯示專屬安全文案。

負向冷卻狀態跨process／host重啟保存，尊重Retry-After，依指數退避到期後才允許下一次probe重試。等待期間不重複外呼，模型推論不自動重試，未知或付費狀態仍拒絕。全部原訂閱、安全與不額外付費閘門保留。

[規格機械檢查](spec-check.log) · [首輪規格審查](spec-review-round1.md) · [修正後規格審查](spec-review.md)。首輪指出Java兩處GET遺漏，已納入契約；第二輪PASS，保留1項期限歸屬用語Minor：12秒為Java→host client期限，BFF為20秒，本次metadata實際總deadline仍為7.5秒。

Python橋接109項、backend70項、frontend193項測試通過，frontend build成功；主代理獨立重跑Python109項及frontend193項。涵蓋真loopback HTTP429→零外呼冷卻→200恢復、跨process與restart單飛/冷卻、Retry-After秒/date/長期限、登入輪替、fresh不讀正快取，以及Java/host過TTL執行路徑不探測未選工具。

[測試與來源雜湊](tests.json)。[架構審查](arch-review.md) PASS（無finding）。[Feature部署收據](feature-runtime.json)已記錄實際後端/前端重建、正式TLS/9090與舊run hash/receipt回歸；production assets以合成BFF資料驗證兩CLI同時可選，以及Claude限流時只有Claude禁選/Codex仍可選。

Feature驗收當時，真Claude usage首次實測仍HTTP429，供應商要求等至台北2026-11-16 01:04:29.517630；該次feature歷史收據明示WAITING_PROVIDER_RETRY_AFTER，沒有模型推論、不刪除或縮短期限。主代理另以已部署來源及真負向state測三次fresh probe，確認均具名拒絕且network/native calls=0，原state不變。該次尚未到供應商重試期限，不能把合成fixture當實機恢復。後續main部署與期限後的正向恢復／單次合成解讀結果，以私有/tmp/t595-acceptance/main-runtime.json記錄；此feature收據不代表後續即時狀態。不保存token、account、credential fingerprint或完整模型文字；最終main收據存私有驗收目錄。
