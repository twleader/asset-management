# [t462] 資產配置建議部分載入失敗時保留已載入內容

**對應 Requirements:** Requirement 32（資產配置建議的條件、資產資料、建議與試算頁面）
**前置任務:** 無
**Liquibase changeset:** 無

## 背景

資產配置建議頁由 BFF 一次平行讀取六個 business 區塊：`latest`、`history`、`profile`、`settings`、`currentAllocation`、`projection`。目前 BFF 將個別錯誤轉成空物件或空陣列，但沒有回報哪些區塊失敗；前端每次成功拿到聚合回應後，會把所有空 fallback 當成新資料，覆蓋使用者已看到的理財條件、建議、歷史、資產配置、試算或引擎設定。產生建議期間每五秒的靜默輪詢也會觸發同一覆蓋。另下游 HTTP 200 卻沒有 response body 時，`bodyToMono` 會以空完成結束，不會被目前的 `onErrorReturn` 捕捉，可能令整個聚合請求失敗。

正確行為是隔離每一區塊的讀取失敗：成功的區塊更新，失敗的區塊保留前一次成功內容，且頁面能在非靜默載入時提示部分資料未更新。合法空資料仍須照常呈現，不能被誤認為失敗。

## 要做什麼

- [x] **462.1 BFF 聚合契約**：維持六個 business GET 平行執行。每個區塊遇到下游 HTTP／傳輸／解碼錯誤，或 HTTP 200 但無 response body 時，該區塊獨立使用既有 fallback（`latest`、`profile`、`settings`、`currentAllocation`、`projection` 為 `{}`；`history` 為 `[]`），聚合端點仍回 HTTP 200。根層新增永遠存在的 `fetchErrors` 字串陣列，固定順序及允許值為 `latest`、`history`、`profile`、`settings`、`currentAllocation`、`projection`；只列本次失敗區塊，全部成功時為 `[]`。不得將下游例外訊息、錯誤 response body 或個資細節放入 `fetchErrors` 或失敗區塊的 fallback；六個成功區塊仍依現有租戶隔離規則照常回傳使用者可存取的資料。HTTP 200 且有合法 JSON 空結果（例如 `latest.status = NONE`、空歷史陣列、`projection.available = false`）視為成功，不列入 `fetchErrors`。既有回應欄位、`latest.rebalanceGroups` 與其排序／分組行為保持不變。
- [x] **462.2 前端逐區保留**：`AssetAllocationAdviceView.vue` 只依 `fetchErrors` 決定失敗區塊，不以 fallback 的空物件或空陣列猜測錯誤。`profile` 失敗時保留表單、理財目標／風險／報酬選項；`settings` 失敗時保留設定物件及已選引擎／模型／思考深度／搜尋次數；`latest`、`history`、`currentAllocation`、`projection` 各自失敗時保留該區前一次成功內容。其餘成功區照常更新。第一次載入時沒有舊資料，就維持各 state 初始值；不得以 fallback 清空表單或已載入的資料。
- [x] **462.3 使用者提示與輪詢**：`load(false)` 收到一個以上 `fetchErrors` 時，最多顯示一次固定警告「部分資料更新失敗，已保留原本內容」。`load(true)` 的五秒背景輪詢遇到部分失敗時不顯示 toast／alert，避免持續打擾。沒有部分錯誤時不顯示此警告。不得在畫面提示下游原始錯誤文字。
- [x] **462.4 BFF 契約測試**：在 `bff/src/test/java/com/steven/assets/bff/portfolioadvice/` 新增 `PortfolioAdviceBffController` 的單元測試，使用可控制的 WebClient/exchange stub，至少驗證：(a) 六區成功時 HTTP 200、`fetchErrors=[]` 且資料完整；(b) 任一區 HTTP 錯誤時只有該識別值進 `fetchErrors`、該區回既有 fallback、其他五區仍保留；(c) 下游空 body 被視為該區失敗而非整體 500；(d) 六區全失敗時仍回 HTTP 200、包含六個識別值且有 `latest.rebalanceGroups`；(e) 合法空 JSON／空歷史仍是成功。測試不得連接真實 business service 或 broker。
- [x] **462.5 不擴大範圍**：不改 business API、DTO、資料庫、排程、OpenAPI 或寫入流程；不新增資料持久化或改變 owner-scoped 身分傳遞。保留 BFF 既有平行讀取。

## 驗證

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -f bff/pom.xml '-Dtest=*PortfolioAdvice*' test
npm --prefix frontend run build
bash scripts/spec-check.sh
```

以 `run-stack` 流程從目前 main 同源的 feature worktree rebuild/recreate `bff` 與 `asset-frontend`，確認容器健康、前端 HTTP 200，並以登入後唯讀請求確認 `GET /api/bff/portfolio-advice` 回 HTTP 200 且根層有 `fetchErrors`。用測試 stub 驗證故障組合，不刻意關閉共用環境的 business 容器。不得呼叫任何券商或下單 API。

## 完成報告

實作變更：`PortfolioAdviceBffController` 六區平行聚合新增有序 `fetchErrors`；錯誤與空 body 個別降級，保留既有 `rebalanceGroups`。新增 BFF controller 契約測試；`AssetAllocationAdviceView.vue` 按區保留舊資料並只在非靜默載入提示；新增引擎公告 helper、Node 契約測試並納入前端 test script。

驗證：BFF focused Maven tests 24 passed；`npm --prefix frontend test` 86 passed；Vite build passed；`bash scripts/spec-check.sh` BLOCK 0／CHECK 0；`git diff --check` passed。Feature stack rebuild/recreate `bff` 與 `frontend` 成功：BFF `healthy`／actuator `UP`，前端容器 running 且首頁 HTTP 200。映像：`asset-management-bff:latest` `sha256:adb94d39b405c28b2a1ac61af2eb2844164f24627a7a8a73c1d16586de8803af`；`asset-management-frontend:latest` `sha256:b79bfeda257a46b485436e4dea130ae04e2a2f81bc3a41535d75ff275eb4f497`。

限制：唯讀未登入 `GET /api/bff/portfolio-advice` 回 401，當時沒有可用瀏覽器登入上下文，故未能以 runtime 驗證個人聚合 JSON；錯誤組合由 WebClient stub 測試覆蓋。未呼叫券商／下單、`generate` 或引擎設定寫入；其他服務容器 ID 與 Fubon flags 保持不變。
