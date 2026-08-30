# [t389] 富邦 ETF 成分股查詢、正規化與排程落地

**對應 Requirements:** Requirement123；**前置任務:** 既有Fubon唯讀adapter與台灣交易日查詢；**Liquibase:** 沿用v1.120.0-fubon-etf-holdings-snapshot.sql，不再新增migration。

## 背景

接手前程式已留下排程與資料表，但任意SDK raw JSON跨adapter、Service直接SQL、整批失敗保留舊成功等缺口尚未驗收。這次以[富邦官方ETF Holdings文件](https://www.fbs.com.tw/TradeAPI/en/docs/market-data/http-api/ownership/etf-holdings/)（v2.2.9，2026-08-30查證）修正。官方已有schema，不再宣稱「只能靠真實帳號才知道欄位」。真實部署查詢另記證據，不得拿官方範例當真人回應。

## 要做什麼

- [x] **389.1 SDK窄wrapper。** `SdkGateway.read_etf_holdings(symbol)`僅呼叫現有 `marketdata.rest_client.stock.ownership.etf_holdings(symbol=...)`。沿用session、bounded call、一次auth重試及rate-limit消毒，不碰下單，不新增accounting lock互斥。
- [x] **389.2 正規化而非raw透傳。** `EtfHoldingsService`在Python記憶體內驗證provider頂層symbol與請求一致、data為list、date為ISO且不晚於Asia/Taipei今日，選最新有效來源日，重複同日failure。最新日components結構錯誤failure，不回退舊日掩飾。合法空data是無股票成分，不造sourceDate。對component僅取symbol/name/weight/quantity；代號名稱weight必要，無效單筆略過；shares optional。weight/quantity拒絕bool/NaN/Infinity/負值，weight<=100；跨服務全部canonical decimal字串。非空components全無效則failure。忽略所有未知欄位，尤其account/secret不可跨服務。
- [x] **389.3 嚴格route。** `POST /internal/market-data/etf-holdings`只有 `{codes: list[str]}`，1–50、不重複、有效台股ETF格式（含00981A）、排除0000、空白、bool、額外欄位。維持既有auth/三態/exception redaction/timeout。回 `{batchId,holdings:[{stockCode,status,reason,rawResponseJson}],counters}`；success的rawResponseJson只裝版本1正規化JSON：`{schemaVersion:1,stockCode,sourceDate,holdings:[{stockCode,stockName,weight,shares}]}`；此歷史欄名不表示允許SDKraw。failure payload=null且固定reason。並行上限4，不能讓raw exception洩漏。
- [x] **389.4 Java immutable records + client。** `FubonBrokerClient.readEtfHoldings(List<String>)`及既有`FubonHttpClient`處理token、503、timeout與invalid JSON；在使用結果前驗證批次結果代碼只屬於請求、無重複、status有效、payload版本/code一致。額外code不保存，缺失/無效請求code落failure。
- [x] **389.5 Repository與DB。** 已有`FubonEtfHoldingsSnapshot/Repository`、v1.120.0 include與表，沿用same-key最近結果覆寫。raw_response_json JSONB只存正規化物件語意，不保證文字一致。雷達SQL放Repository（可使用既有StockHoldingRepository native query），不在Service注入JdbcTemplate。集合等於各owner最新快照台股持股 ∪ 台股警示；排除0000後套既有isEtf，不放大為全市場。
- [x] **389.6 排程與失敗隔離。** 兩個cron `0 50 8 * * MON-FRI`/`0 30 15 * * MON-FRI`，zone Asia/Taipei，共用single-flight。依序flagfalse零呼叫、READY、known-open（empty/false/例外均no-op）、repository查code、空集合no-op、≤50分批。每requested code都保存本輪success/failure；整批HTTP例外/失敗不能continue而保留舊成功。每檔獨立repository transaction或writer，DB失敗固定log且其他檔繼續，不使用外層rollback-only交易吞exception。fetched_at與updated_at為本輪Clock instant，sourceDate保留payload來源日。
- [x] **389.7 設定與盤點。** `.env.example`和compose傳`FUBON_ETF_HOLDINGS_SYNC_ENABLED=false`，不擅改其他flags。排程列表登錄business第24項/總58項；t386背景7條adapter route。secret根目錄明確使用已核實部署來源，不用空worktree目錄。HTTP route不掛9090或host port。
- [x] **389.8 有意義的回歸。** Python測SDK auth retry/rate limit、未知payload欄位不透傳、symbol/date/decimal錯誤、合法空data與部分無效component、latest day、route輸入與auth邊界。Java測flag/calendar/empty pool零外呼、SQL集合、>50batch、extra/duplicate/missing/mismatched code、整批失敗清舊payload、單筆DB例外不阻其他筆，必要的PostgreSQL JSONB讀回。不能把Testcontainers環境錯誤說成測試通過。

## 驗證

使用Temurin Java21；`python -m pytest`、各受影響module `mvn test`保留非零exit。`bash scripts/spec-check.sh`、`bash scripts/tests/schema-sql-drift-test.sh`必須查證。獨立arch-auditor後依run-stack建置/重建Fubon、business、BFF與有改動的external服務，核對image與secret mount；每次upstream重建均restart/recreate BFF。以DB讀回、7條route auth測試、實際排程列表驗收。使用者目前兩session各自commit-merge-push，必須先驗收再獨立no-ff合併與推送。

## 完成報告

2026-08-30：實作、獨立 spec／arch 審查與 feature stack 驗收完成。SDK wrapper、版本 1 allowlist 正規化、嚴格輸入、每檔獨立 transaction、失敗清舊資料、來源日保留及雷達 ETF 範圍均已實作；每批 4 檔，以限制 SDK 並行與 HTTP 回應大小，仍符合每批最多 50 檔的契約。全域 counters 維持既有 exact 13 keys，ETF 失敗沿用 `QUOTE_FAILED`，逐筆原因保留 ETF 專用值；ETF client 使用專屬 8 MiB 上限，其他既有 client 維持原限制。

- 測試：Python **232 passed**；backend **1,627**、BFF **235**、external-materials-service **657**，全部 failures/errors/skipped 為 0。包含真實 PostgreSQL 的雷達集合／JSONB／transaction 隔離測試、真實 HTTP 大回應與超限測試，以及 Redis 相容性回歸。Java 使用 Temurin 21；Docker Testcontainers 以 `-DextraArgLine=-Dapi.version=1.44` 相容目前 daemon，未跳過 DB 測試。
- 機械查核：`spec-check.sh` BLOCK=0、CHECK=1；唯一 CHECK 是已人工核對的 changelog 文字參照，並非 schema 權威。OpenAPI 13 條路由 parity 與 `schema-sql-drift-test.sh` 均通過；92 張資料表的重產 schema 逐位元相同。
- 部署：feature worktree 的 Fubon、business、external、BFF 四個 image 已重建與 container recreate，全部 healthy，Compose labels 與 image SHA 已核對。實際登入的排程頁顯示 **58＝24 business＋34 external**，ETF 列正確為交易日 **08:50／15:30**。
- 環境限制：尚未取得目前真人 API key 為唯讀權限的可驗證證據，所以本次維持 runtime `FUBON_ENABLED=false`、ETF 同步旗標 false，不更寫 `.env` 或登入真人 SDK。掛載使用已核實的 main secrets 目錄；檔案 presence/readability 已驗證，但這不代表上游授權已通過。disabled adapter 的合法 ETF 請求回 503／DISABLED；各 route 的 token 與啟用 gate 由受控測試覆蓋。不得將這些結果稱為真人 provider 成功。
- DB/API 讀回：正式 `fubon_etf_holdings_snapshot` 為 **0 筆**；0050 台股 API 為 `supported=false`、空 holdings、`asOfDate=null`、訊息「尚無同步資料」，沒有灌入範例或捏造日期。

完整測試及 feature deployment 證據位於本機 `/tmp/asset-takeover-20260830/etf/`。驗收時曾發生一次非預期公開資訊 rescan，已向使用者揭露並留存 `incident-readback.md`，後續改為明確白名單唯讀驗收；未呼叫券商寫入方法。此完成報告記錄的是合併前結果，合併推送後的 main rebuild／readback 另行執行並記錄，不在此預先宣稱已完成。原 Claude 未提交內容保持原樣。
