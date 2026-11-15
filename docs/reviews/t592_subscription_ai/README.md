# 訂閱／本機 AI 文字解讀驗收

Task 592；2026-11-15（Asia/Taipei）。資產配置建議頁的 SRPP 情境模擬可選執行工具、model、模型支援的 effort。工具清單由 host registry 管理，可擴充；首批有 Codex CLI、Claude CLI、Ollama 與 LM Studio adapter。無 effort 能力者傳 null、畫面顯示不適用。

## 費用與資料邊界

本功能移除直接 Anthropic SDK 解讀路徑，CLI只使用已驗證的個人訂閱登入。本機服務只允許固定 loopback 本機庫存模型；不接受任意網頁指令或遠端推論URL。Codex可用額外credits／unlimited、Claude啟用extra usage或費用證據未知時停止，沒有自動換工具、model或付費API fallback。帳戶設定無法被客戶端原子鎖定，每次推論前立即重查；使用者若在其他工作階段修改供應商費用設定，不能宣稱客戶端預檢能消除該競態。

選擇與執行收據獨立於deterministic input/config/result hashes；CLI未回報實際模型時明示REQUESTED_ONLY，本機provider回報才標PROVIDER_REPORTED。AI失敗仍保留數值。

## 測試與實機證據

- Backend 62項、BFF 4項、host bridge 38項、frontend 191項通過；正式前端build成功。
- 獨立spec第二輪PASS（0 critical/major/minor），arch第二輪PASS（0 critical/major、1 minor）。minor是兩份新DTO收據時間沿既有ISO字串transport；仍以Instant.parse嚴格驗證，型別慣例改善留待後續，未宣稱已改Instant。
- 真實CLI合成數值測試：Codex gpt-6-luna／low與Claude claude-sonnet-5-5／low皆成功，使用SUBSCRIPTION；詳細無全文收據見cli-runtime.json。
- 真業務API→host bridge→Ollama gemma4:31b成功，LOCAL billing、effort null，實際回報模型一致。LOCAL與AI整份情境結果內容相同；各自RFC8785 result hash重新計算相符。不同capture的inputSha256不同屬正常身分差異。
- v1.167.0已在實機套用；完整pg_dump重產db/schema.sql，schema drift PASS（123表）。
- 三服務image rebuild/recreate、環境對main設定、正式TLS信任、safe9090讀取與未登入BFF 401皆已實測。真人Google OIDC完整登入未執行；BFF契約由測試驗證，瀏覽器以合成登入與資料驗實際Docker前端產物，不將fixture誤稱真金融API。
- 第一輪實機測試發現的Spring constructor注入、Codex已知fail-closed啟動診斷分類與375px option裁切均已修正並補驗，沒有以放寬付費或工具安全邊界解決。

[Feature部署及業務收據](feature-runtime.json)；[CLI收據](cli-runtime.json)；[規格審查](spec-review.md)；[架構審查](arch-review.md)。畫面驗證收據與截圖由browser-runtime.json及PNG提供。375px展開既有側欄時內容欄寬僅41px，屬既有全站窄版限制；本輪只驗新option文字未裁切，沒有宣稱全站mobile可用，收合側欄後互動標NOT_EXECUTED。

## 使用與維運

進入資產配置建議→SRPP設定中心→情境模擬，建立或載入已儲存草稿，選擇「呼叫AI解讀」，再選工具、模型與思考程度。工具不可用時可展開原因並重新載入清單；不會自動選用其他工具。

[安裝、工具擴充與費用檢查操作說明](../../operations/srpp-local-ai.md)。合併後應將LaunchAgent來源改指main，從乾淨main重建受影響容器並回讀已保存的相同run與新工具清單；不必重複產生模型推論來證明同一程式版本。
