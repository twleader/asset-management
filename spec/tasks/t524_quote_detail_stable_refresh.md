# [t524] 行情五檔補讀保留固定畫面

**對應 Requirements:** Requirement 197（共用分析視窗即時行情與五檔補讀）
**前置任務:** t506
**Liquibase changeset:** 無

## 背景

StockAnalysisDialog 每次 fetchQuoteDetail 設 quoteDetailLoading=true，template 的首個 v-if 因而移除整個摘要與五檔 DOM，再在完成時重建。盤中頻繁純讀補抓因此閃爍；欄位固定，可以保留節點讓 Vue patch 更新值。

## 要做什麼

- [x] 524.1 同一股票已有 available=true snapshot 時，自動與手動補讀等待期間持續呈現摘要、內外盤與五檔 DOM；整區載入提示與按鈕 loading 僅在尚無可用 snapshot 時顯示。摘要 label 與五檔 level 仍為穩定 key，成功後完整採用 canonical snapshot，Vue 只 patch 變動文字、顏色及量條寬度，不拼接不同來源。
- [x] 524.2 初次載入、API unavailable 與查詢 catch 失敗依原分支顯示，不保留舊報價假裝更新成功；來源、時間、狀態同步。null 顯示「—」。不改既有純讀 bffApi.stockAnalysis.getQuoteDetail、bookRefresh 300ms 延遲／至少兩秒／tail、bookRequest 去重、AbortController、quoteRequestToken、liveGeneration、市場+代碼身分檢查及關窗／換股清空。不得新增 API/provider/計算/持久化/交易。
- [x] 524.3 以實際 Vue 渲染與延遲的 mock 回應驗證首次 loading、更新等待與完成的節點身分、變動與不變值、null、unavailable／失敗、換股及關窗隔離；優先沿既有工具，不為簡單修正增加產品依賴。執行相關回歸與 frontend 建置、Docker 重建重建容器、唯讀驗收，完成獨立架構審查；驗收後的 Git 收尾另於完成報告記錄。

## 驗證

```bash
bash scripts/spec-check.sh
cd frontend
npm test
npm run build
cd ..
docker compose -p asset-management build frontend
docker compose -p asset-management up -d --no-deps --force-recreate frontend
curl -fsSI http://localhost/
```

DOM 驗證須在實際編譯的 Vue 元件／template 與可控延遲回應運行，確認 loading=true 且已有資料時表格持續存在，已存在 DOM 在成功更新後仍是同一節點。無登入 session 時可用離線 browser harness；如無法驗證真盤中使用者 session，完成報告需保留此限制，不得用 HTTP 200 聲稱盤中瀏覽器驗證通過。

## 完成報告

- 產品碼僅修改 `frontend/src/components/StockAnalysisDialog.vue` 的兩個 loading 條件：已有 available snapshot 時保留畫面與按鈕穩定。既有 label／level key、完整 canonical snapshot 採用及所有請求守門未改。
- `frontend/src/utils/livePrices.test.js` 新增真 template 編譯條件與真 fetchQuoteDetail 去重／完整 snapshot／換股及關窗晚到失敗隔離回歸；`npm test` 162/162，`npm run build` 與 `git diff --check` 通過。
- 主 agent 用真 Chrome 執行 `/tmp/quote-stable-browser.cjs <此 worktree 絕對路徑>`：舊碼可重現 `refresh must keep table` 失敗，修正後首次 loading、更新等待無 spinner、摘要／value／orderbook／五檔列節點同一性、變動／不變／null 與 unavailable 全通過；另以實際 fetchQuoteDetail 驗證當輪 catch／canonical unavailable 清除前一 available snapshot。
- spec-check：BLOCK 0／CHECK 0。獨立 spec 與架構審查均 critical 0／major 0／minor 0，已記錄閘門。
- Feature Docker frontend image 已 build 並 recreate，HTTP index 與實際 StockAnalysisDialog chunk 均 200；執行中 chunk SHA-256 `78223534639b0d65cb5e3365555ee39d79ce140b14defc26c1a733ef0455afac` 與本機建置一致，frontend 有效環境與唯讀 TLS 來源皆同 main，其他服務容器不變。feature runtime receipt：`/tmp/quote-stable-feature-runtime.json`。
- 依專案流程，驗收後以 feature commit／no-ff merge／push 收尾，再從 clean main 重建 frontend 及回讀來源；merge SHA 與最終 runtime 結果由本次聊天交付。
- 限制：瀏覽器使用可控行情資料驗證實際 Vue template 與 computed，沒有使用者登入 session；真實盤中登入行情驗收維持 `UNVERIFIED_NO_SESSION`，HTTP 200 與 chunk hash 不替代此項。無 API、provider、金融規則或資料庫變更。
