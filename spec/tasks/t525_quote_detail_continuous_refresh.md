# [t525] 行情五檔無成交推播仍持續更新

**對應 Requirements:** Requirement 197（共用分析視窗即時行情與五檔補讀）
**前置任務:** t506、t524
**Liquibase changeset:** 無

## 背景

使用者回報 2330 五檔消除閃爍後資料停住。12:54 的唯讀 API 取樣顯示 sourceTime 12:53:39→12:54:03、volumeLots 17125→17160；DB／Redis canonical revision 47873 對應 sourceTime 12:54:38。上游會更新，但前端 bookRefresh 只有 price SSE/catchup/tab/manual 事件觸發、沒有自主 timer，不能保證 book 變動會觸發前端補讀。

## 要做什麼

- [x] 525.1 為 StockAnalysisDialog 加唯一兩秒 timer，啟用條件為 modelValue=true、document 可見、activeTab=quote-detail、stock.market=台股、stockCode 非空且非0000。即使 EventSource未open、無事件或永久CLOSED也持續工作；不要新增SSE或依賴股價變動。
- [x] 525.2 timer 和 SSE/catchup 沿原 bookRefresh delay300ms／interval2000ms／tail=true，同一bookRequest去重；自動GET至少兩秒間隔、單一in-flight，慢回應不可積壓風暴。手動重整保持同一fetchQuoteDetail／bookRequest去重，既有手動時序不擴張。只有pure-read bffApi.stockAnalysis.getQuoteDetail，不新增API/provider/DB/Redis寫入/交易/策略運算。
- [x] 525.3 idempotent啟用與停止timer，切tab/close/stock/hidden/unmount清理，visible/進入tab恢復；每次callback與讀取確認適用狀態，舊response維持AbortController／quoteRequestToken／liveGeneration／market+code守門。保持Task524兩個loading條件及穩定label/level keys，完整snapshot替換，null/unavailable/catch照舊。
- [x] 525.4 在現有livePrices.test.js沿真SFC AST fixture＋假timer測試無SSE至少三輪請求與新snapshot採用、定時與SSE合併／慢回應非重疊、重複啟用只一timer、hidden/tab/stock/close/unmount取消及恢復。真瀏覽器用實際元件函式與template＋可控回應驗證連續三輪數值/時間更新與同一DOM；不僅直接注入reactive值。真實2330 API/DB/Redis唯讀取樣；無登入session則如實列限制。

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

至少在無SSE且book來源仍有新snapshot時觀察三輪自動GET與可見新值；隱藏/離開五檔後timer不持續GET，恢復不補積壓；慢回應同時最多一請求。Browser harness必須執行實際元件refresh函式，不能只直接改state宣稱更新鏈成功。不要注入正式DB/Redis或人造price-update。

## 完成報告

- `StockAnalysisDialog.vue` 新增 `quoteDetailRefreshEligible`、`syncBookRefreshTimer`／`stopBookRefreshTimer` 與唯一兩秒 interval；初始化不依賴 SSE open，tab／subscription／catchup 啟用、pause／hidden／close／換股／unmount 清理。仍走原 `bookRefresh`／`bookRequest`，未改 API、canonical writer、source、策略與 Task524 DOM 條件。
- `livePrices.test.js` 延伸假 interval 控制及真 SFC AST fixture：無 SSE 連續三輪、重複啟用唯一 timer、慢回應單一 in-flight／coalescing、狀態切換停止／恢復。`npm test` 165/165、單檔 33/33、`npm run build`、diff check 通過；spec-check BLOCK 0／CHECK 0，獨立架構審查無 finding。
- `/tmp/quote525-browser.cjs` 在真 Chrome 執行整份實際 SFC script、真 Vue watch／mounted／unmounted、實際 createTrailingRefresh 與 quote template。舊程式無 SSE 時等不到第三輪；新程式零 SSE 的三輪 GET 約 508.6／2509.9／4508.8ms，來源時間與數值推進且摘要／五檔 rows 保持同一 DOM，hidden／resume／tab／換股／close／reopen／unmount 均已實際驅動並通過。log：`/tmp/quote525-browser-final.log`。
- 同一瀏覽器 harness 的 `--live` 模式將既有 getQuoteDetail client 入口橋接到公開 pure-read `/api/quotes/one` 的真 2330 canonical snapshot，零 SSE 三輪自動 GET 約 544.5／2544.2／4545.8ms，sourceTime 12:58:15→12:58:45、volumeLots 17381→17422、price 2375→2380，既有 DOM 保持，證明來源有新版本時 timer 能自主讀入。log：`/tmp/quote525-live-browser.log`。兩秒 GET 不保證來源每兩秒改變。
- Feature frontend 已 image rebuild＋container recreate；實供 `StockAnalysisDialog-DfqM8jaJ.js` SHA-256 `6977c7035ce37eae210d4b4bbd389a74c17bb1b75f2103d81f4c1c13891e07af` 與驗證的本機 dist 一致，index／chunk HTTP200，env／唯讀 TLS 同 main，其他容器未變。receipt：`/tmp/quote525-feature-runtime.json`。
- 限制：有真 2330 persisted 資料與元件自動讀取／DOM 證據，未取得使用者登入 session，因此正式已登入前端→BFF 的完整 UI 驗收仍 `UNVERIFIED_NO_SESSION`，不以公開 API 橋接或 HTTP200冒稱認證流程通過。驗收後依 Git 收尾流程提交／no-ff merge／push，再從 clean main 重建並回讀；最終 SHA 與 runtime 結果由本次聊天交付。
