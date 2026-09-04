# [t390] 富邦 ETF 成分股本地讀取與來源日期

**對應 Requirements:** Requirement123；**前置任務:** t389正規化資料表/adapter；**Liquibase:** 無新changeset。

## 背景與核實來源

官方[ETF Holdings契約](https://www.fbs.com.tw/TradeAPI/en/docs/market-data/http-api/ownership/etf-holdings/)已記載`data[].date/components`，本次按正式schema實作，不保留永遠回空的佔位parser。官方schema確認與部署真實回應核對分開記錄。當有效憑證/可驗證唯讀權限不可用時，不繞过安全限制；資料未同步時API必須明示，不得捏造holding或asOfDate。

## 要做什麼

- [x] **390.1 獨立parser。** `FubonEtfHoldingsParser`只解析Task389版本1正規化JSON，不直接依赖任意SDKraw。驗schemaVersion、stockCode、sourceDate、holding欄位與decimal邊界；回傳sourceDate與既有`EtfHolding`列表（可新增內部Parsed record，但公開`EtfHoldingsResult/EtfHolding`形狀不變）。null/blank/malformed/未知版本/錯code等回明確不可用，不拋出未捕捉例外；合法空data的normalized無股票成分狀態須與格式錯誤區分。
- [x] **390.2 台股pure-read。** `MarketDataService.getEtfHoldings(code,"台股")`僅讀本地Repository；查無顯示「尚無同步資料」、success=false顯示消毒失敗原因、badpayload顯示格式不可用、合法空集合顯示無股票成分資料。成功的source=Fubon，asOfDate=payload來源sourceDate，不是fetched_at/現在日期。不在request path呼叫adapter或external，不為空集合額外取價。
- [x] **390.3 保留美股及內部呼叫端。** 美股原有HTTP/快取/錯誤處理完全不變。只移除`getMoneyDjEtfHoldings/parseMoneyDjHoldings`，保留external `HistoricalBackfillService.addTwEtfConstituents()` 使用的in-process台股Yahoo/FinMind路徑與內部endpoint，避免破壞既有透視歷史回補；不能把HTTP切換擴張成刪除所有台股內部查詢。
- [x] **390.4 API盤點一致。** `FubonApiInfoBffController`的ownership.etf_holdings connected=true、endpoint為POST /internal/market-data/etf-holdings、consumer說明08:50/15:30、雷達ETF限定及設定啟用。response說明為正規化JSON與來源日（不是任意raw）。52項=9已串接/43未串接，行情分類20項中3已串接。同步t386 Java常數區塊、R121/design與tests；不改前端routes、icons、permissions或新增操作按鈕。
- [x] **390.5 回歸與部署。** parser fixtures直接測來源日早於抓取日仍顯示正確asOfDate、多日選擇、合法空data、wrongcode/schema/numeric、台股所有不可用狀態與美股不變；external歷史回補仍可用。官方fixtures只是contract tests，不可宣稱真查詢。實機若可安全取得0050 response，記採樣time/sourceDate與實際欄位，核對adapter→DB→business/BFF；否則明列具體環境限制與已完成的離線/DB證據，不改成猜測值。

## 驗證

Temurin Java21執行backend/bff/external-materials-service `mvn test`，禁止`2>/dev/null || true`。完整測試與架構審查後執行run-stack：從本feature worktree重建受影響服務、等待healthy、核對image SHA，實際查看台股ETF與美股ETF回應及盤點頁/排程列表。frontend從http://localhost走BFF，9090只有既有13條exact契約；不使用localhost:8080。合併推送後從main重建再讀回；兩session未全部完成前不刪除原Claude branches。

## 完成報告

2026-08-30：parser、台股 pure-read 切換、美股／external 歷史回補相容性與盤點同步已完成，獨立 arch 審查目前未解 critical／major／minor 均為 0。

- **官方 schema 已確認**：依官方 v2.2.9 ETF Holdings 的 `symbol`、`data[].date/components` 建立正規化契約；測試涵蓋最新來源日、非法最新資料不回退、合法空集合、錯誤代碼／版本／decimal，以及来源日早於抓取日。
- **真人 provider 查詢未驗證**：目前缺 API key 唯讀權限證據，runtime 維持 Fubon disabled 與 ETF 同步 disabled，未登入或呼叫真人 ETF SDK。這是部署環境限制，不以 fixtures 或空資料推定 provider 已成功。
- **DB/API 實際讀回**：台股 0050 查無同步資料，回 `supported=false`、`holdings=[]`、`asOfDate=null`；正式 ETF snapshot 表 0 筆。美股 SPY 同一執行中 business API 回 `supported=true`、source 為 `Yahoo Finance (前 10 大)`、10 筆 holdings，原有讀取行為保留。
- **實際 UI**：重建後透過 `http://localhost` 的既有登入頁驗證，富邦證 API 顯示 **52 項、9 已串接、43 未串接**；ETF 列的內部 endpoint、正規化回應與 08:50／15:30 說明正確。排程頁顯示 **58＝24＋34**。未更動前端 routes、icons、permissions 或新增操作按鈕。
- **測試／部署**：完整 Python 232、backend 1,627、BFF 235、external 657 均通過且 Java 無略過；feature Fubon／business／external／BFF 重建、recreate、healthy、image SHA／Compose provenance 均已核對。schema 92 表重產一致，13 條既有 OpenAPI parity 通過。

本機驗收證據與美股讀回保存在 `/tmp/asset-takeover-20260830/etf/`；完成報告不將一次非預期 rescan 的驗收事故隱藏為「全程只有唯讀」，事故與其已觀察副作用已另行揭露。此處只記合併前驗收；main 合併推送後必須再由 main 重建讀回。兩個 session 全部完成前，原 Claude branches 及未提交檔案仍保留。

**2026-09-04 補充（環境限制已部分解除）**：第 26、27 行「真人 provider 查詢未驗證」已被後續事實部分推翻。當日直接呼叫 `fubon-broker-service` 的 `POST /internal/market-data/etf-holdings`（查 `0050`）回 `status: SUCCESS`，取得完整真實成分股清單，確認台股 ETF 成分股查詢具備可用唯讀權限，非僅缺憑證證據。同一帳號的**即時報價**當日仍 100% 失敗（`TRIAL_QUOTE_REJECTED`／`SDK_CALL_SATURATED`），故本補充僅限於 ETF 成分股這一項查詢，不代表帳號已全面轉正式權限。`.env.example` 的 `FUBON_ETF_HOLDINGS_SYNC_ENABLED` 已改為預設 `true`；`fubon_etf_holdings_snapshot` 表在本次補充撰寫當下仍為 0 筆，是因排程固定時間（台北時間每日 08:50／15:30）尚未在旗標開啟後的容器實例上觸發過，非權限問題。
