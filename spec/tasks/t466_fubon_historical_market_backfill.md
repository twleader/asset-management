# [t466] 富邦台股 10 年日 K 與可得期間 1 分 K 歷史回補

**對應 Requirements:** Requirement 170
**前置任務:** t408、t425、t461
**Liquibase changesets:** v1.138.0-fubon-historical-market-backfill-receipts-and-average-contract; v1.139.0-fubon-historical-minute-error-log-catalog

## 背景

Production 的 Fubon 歷史日 K、盤中 1 分 K fact tables 初始為 0 rows。目前 post-close 日 K 排程只查最近 365 天，1 分 K 排程只查當日；兩者都不會補過去歷史。查證富邦官方 market-data contract 後，日 K 可查掛牌以來資料且每次日期窗必須短於一年，歷史 1 分 K 自 2023-05-23 起可查。官方日 K 回應會 echo `timeframe=D` 及 `sort=asc`，既有 strict parser 卻拒絕這兩個已知欄位；minute `average` 是自開盤累計均價，現有 validator/database constraint 錯誤要求它落在該分鐘 OHLC 範圍。

富邦 `intraday.volumes` 只提供當日 price-level snapshot；現有同步只將快照存入具 TTL Redis key，沒有歷史 endpoint 或資料表。因此只回補有官方歷史 endpoint 且可按歷史 fact contract 保存的日 K／1 分 K；累計盤中 quote-volume 與分價量 snapshot 必須明確標記 unsupported，不得透過當日 endpoint 偽造過去 snapshots 或由 K 線推演。

## 要做什麼

- [x] 466.1 在 `spec/requirements.md`、`spec/design.md` 加入 Requirement 170 的完整歷史範圍、資料 contract、current-only 限制及失敗/驗收條件；依 spec-review skill 跑 `bash scripts/spec-check.sh` 並取得獨立唯讀 spec-auditor 審查。spec-check 的外部文件 BLOCK 請見完成報告。
- [x] 466.2 修復 Python daily historical parser：精確容許 optional `timeframe=D`、`sort=asc` echo 並驗值，其他未知欄位與既有 strict validations 繼續拒絕；新增負面／正面 parser tests。
- [x] 466.3 新增 token-protected `/internal/market-data/historical-intraday-candles/read`，只接收 symbol/from/to 並固定官方 historical minute API 的 `timeframe="1"`。驗證 identity、query window、日台北 09:00–13:30（含 13:30）、唯一遞增時間、decimal、原始 volume、session cumulative average；拒絕超過31曆日或 2 MiB/`31*271` rows 的單一 response，不得部分成功；為此 endpoint 的錯誤日誌新增 immutable operation catalog identity。
- [x] 466.4 新增單次 CommandLineRunner，需顯式 command 和 `FUBON_HISTORICAL_BACKFILL_ENABLED=true` 雙 gate；正常 server／schedule 不可觸發。只讀 `FubonRadarScope.current(30)` 並凍結 sorted code snapshot；以既有日曆 gate 設定 10 年 inclusive interval `toDate.minusYears(10)..toDate`，日 K 日期窗差≤364天，minute 日期窗最多31個曆日且起點不早於 2023-05-23。每 window 前再驗歷史專用 gate/scope，每次只發一個 request，使用原全域 gateway/history quotas，遇429/stopRun/fatal error即停。
- [x] 466.5 建立 campaign manifest 與 per-window attempt receipts（每次重試新增，STARTED 僅可轉一次終態，終態不可變）；以 PostgreSQL session advisory lock 保證同一 campaign 同時僅一個 invocation，lock busy 時零 vendor request；支援同 campaign resume，略過 COMPLETE/NO_DATA，只有明確 retry-failed 可重試 FAILED，單次 invocation 每 window 最多一個 attempt。記錄 row counts、實際 observedAt、 sanitized error code、conflicts 及 scope changes；不儲存 raw SDK body、credentials、owner/account data。
- [x] 466.6 以既有 immutable daily fact store 加原有 guarded projection、既有 intraday history transaction writer 寫入；相同 facts unchanged，不覆寫/刪除來源衝突。Migration v1.138.0 修正 minute `average` constraint，只解除平均價相對單分鐘 OHLC 的限制，並新增 receipt schema；保留其他價格/來源/單位/unique constraints，重新產生 `db/schema.sql`。
- [x] 466.7 Campaign 固定將 cumulative intraday quote volume 與 price-volume distribution 標示 `UNSUPPORTED`，不查當日 volume endpoint、不製造歷史 snapshot；報告 1 分 K 原始 minute volume 為獨立 dataset。最終報表須含 campaign ID、初始／最終 row counts、symbol 數、日期 range、最早／最晚 fact date、inserted/unchanged/conflict/failed counts、unsupported datasets。
- [x] 466.8 測試 daily optional echo allowlist；歷史 minute strict parser、13:30、平均值超 OHLC、units；31日/365日 chunk 間界；calendar/scope/symbol guards；resume/no duplicate requests/retry gating；同 campaign concurrent lock 與缺值/重複 CLI command；429 stop；idempotent/immutability/conflict；receipt sanitization；零交易方法呼叫。執行 Python 與 Java 對應 suites、Liquibase/schema drift、spec-check、diff-check；spec-check 外部文件 BLOCK 請見完成報告。
- [x] 466.9 依 run-stack skill 在主工作樹設定基準不變且不輸出 secrets 的條件下，以一次性 container command 驗證實際歷史回補並安全檢查 rows/receipts；不得改 `.env` 或與其他部署工作併行。Campaign 與 stack 驗收完成。Feature commit `acb111d65ce1c95fd568ff3fd2996b762cb0e759` 已推至 `origin/codex/fubon-historical-backfill` 並 readback 同 SHA；main 以 `--no-ff` 合併為 `777505b99fe5ad63eb0de93b94f7ebaa30cc679c`，`origin/main` readback 同 SHA。

### 凍結執行契約

富邦台股歷史日 K 與 1 分 K 可稽核回補

#### 一次性執行邊界與 campaign

將回補實作為 external-materials-service 的專用 CommandLineRunner／ApplicationRunner，使用正常 Spring DI、既有 `FubonMarketDataPort`、`FubonHistoricalDailyCandleStore`、`FubonMarketDataHistoryStore` 與 `StockSourceQuery`，不提供任何 HTTP route，也不改 production schedules。新 `FUBON_HISTORICAL_BACKFILL_ENABLED=false` 是單次執行的第二道明示 gate；一般 service 啟動及 scheduler 呼叫皆不得進入回補。Docker 一次性工作需顯式傳入 runner command 和此 flag，設定 web application type 為 none，完成後以非零退出碼表示失敗／部分結果；不得寫入或提交 `.env`、secret、Redis、交易 API，也不與正式服務的 deployment/recreate 並行。Dockerfile 的 `--spring.profiles.active=postgres` 是允許的既有啟動參數，其他 profile 一律拒絕。歷史 candles SDK 的官方 404（無資料）須映射成 `NO_DATA`，不能誤列為上游故障；診斷寫入 `api_error_log.occurred_at` 時須將 `Instant` 轉為 JDBC `Timestamp`，以符合 PostgreSQL JDBC 綁定型別。Runner 只接受建立 campaign 或以既有 campaign ID resume；日期範圍固定由 latest completed Taiwan session 回推十個 calendar years 得出，不可由 caller 指定；另接受 `--retry-failed`，不接受代碼或 SDK request 欄位。專用入口在缺少 command 時須以非零退出；一般服務啟動缺少 command 則保持無操作。

Campaign start 透過既有 access/calendar/clock 抽象建立歷史專用 gate，驗證 `FUBON_ENABLED`、SDK READY、Asia/Taipei 已完成交易日及已知 trading calendar；不可直接呼叫以今日必須開市為條件的 `FubonMarketRunGate.reason`；上界為該日曆回傳且嚴格早於今日的 latest completed Taiwan session，不是 `LocalDate.now()`，下界為上界減 10 calendar years，含頭含尾。再讀 `FubonRadarScope.current(30)`，驗證回傳的排序、去重結果有 1–30 個合法代碼，凍結在 immutable campaign scope manifest，並計算 SHA-256 scope hash。既有 `StockSourceQuery` 對 radar 採集合語意，來源重複列於該查詢邊界去重，不是無效 scope。campaign 的 `toDate`、`fromDate`、symbol 清單一旦建立不可編輯；Resume 只能操作相同 campaign。每 window 開始前重驗歷史專用 access/completed-date gate 與代碼仍在當下 `current(30)`；移出者寫 `SCOPE_CHANGED` 而不 request。Scope 新增的代碼屬新 campaign，不追加入既有 campaign。

Campaign 固定兩個 vendor fact datasets：`DAILY_CANDLE`、`INTRADAY_CANDLE_1M`。日 K 從 campaign `fromDate` 起按最多 365 個包含曆日（start 至 end 日期差最多 364 日）分窗，以官方 API `<1 year` 限制留有界內餘裕。1m 從 `max(fromDate, 2023-05-23)` 起按最多 31 個包含曆日分窗。按 symbol、dataset、window start 升冪序列執行，每個 request 完成並 commit receipt 後才開始下一個；不並行，且成功窗口間至少間隔 1,250 ms，使此 runner 的速率不高於 48 次／分鐘，為 broker 共用的 60 次／分鐘歷史額度保留空間。歷史沒有上市日期清單時，不推估掛牌日；官方無資料回應記 `NO_DATA`，其他錯誤不得當作空窗。結束報告的實際範圍以可寫成功的來源日期為準。

`fubon_historical_backfill_campaign` 將不可變 identity/scope manifest（UUID、要求的起訖日期、latest-completed 日期、sorted symbol JSON、scope SHA-256、建立時間）與可更新 lifecycle/summary 欄位（完成時間、整體狀態、結束摘要）分開儲存；只有後者可隨 campaign 執行更新，manifest 欄位不可編輯。`fubon_historical_backfill_window_attempt` 以 identity/campaign/dataset/symbol/window/attempt number 儲存 request attempt（STARTED 僅可轉一次終態，終態不可更新或刪除）：開始／完成時間、status、observedAt、provider row count、inserted、unchanged、conflict、sanitized error code；不存 raw body、credential、token、account data 或原始 exception message。先寫 `STARTED` attempt，再做單一 SDK request 及 fact persistence，只有完整 response 及所有 facts 正常 commit 才改寫 attempt 終態；若程序中止，仍留 `STARTED` 並由下次 resume 明確標為 `FAILED/INTERRUPTED` 後新建下一 attempt。這些 attempt 不更新或刪除既有歷史 facts。

Resume 先載入並校驗 campaign manifest 和 scope，再略過具有 `COMPLETE` 或 `NO_DATA` 的窗口；`STARTED` 只標記 interrupted，不重複假設完成。`FAILED` 僅在顯式 retry-failed option 下可產生新 attempt，且每個 invocation 每 window 最多一個 attempt。`CONFLICT`、`SCOPE_CHANGED`、429／stopRun、gate/SDK failure、契約 invalid 或 persistence fatal 皆停止本 invocation，留下可續跑狀態；不可快速重送或由 Java 隱藏重試。API history 60/min 與 shared actual-start 60/min 都由原 gateway 控制；單 worker確保不製造併發 request。

#### Gateway normalization 與 persistence

Python adapter 的 daily parser 對現有 root allowlist 增加精確的官方 echo 驗證：`timeframe` 和 `sort` 為 optional，前者出現只接受 `D`、後者出現只接受 `asc`；仍拒絕其他未知 root/row 欄位、錯誤資料型別及 query echo。現有 internal daily route 及 SDK method 不變，錯誤 response 不得落入 Java persistence。

新增 token-protected `POST /internal/market-data/historical-intraday-candles/read`。body schema 精確 `{symbol, from, to}`，台股 symbol 採既有 strict validator，日期須有效且 `from <= to`、包含日期窗不超過 31 日、`to` 不晚於 latest completed date；API method 固定為官方 `stock.historical.candles`、exchange 由既有 stock master/API contract 決定、timeframe 固定字串 `"1"`，gateway global/history limiter 每次實際 SDK start 照常計數。此 route 只支援 equity；不提供 caller 可控 method、exchange、timeframe、minute start/end time、sort 或原始 SDK kwargs。

成功輸出版本化 envelope：`schemaVersion=1`、symbol、market=`台股`、provider=`FUBON_SDK`、queryFrom/queryTo、observedAt、exchange、sourceMarket、`timeframe="1"`、status=`AVAILABLE|NO_DATA`、candles。每列 exact fields `candleAt,open,high,low,close,average,volume`；時間為有 offset 的 ISO instant，unique、strictly increasing、Asia/Taipei日期落在 query window、當日時間 09:00 至 13:30 整分，不能晚於 observedAt；合法窗口可跨交易日，Java 用台北日期作各 row `sourceDate`。response 最大 2 MiB 且最多 `31*271` rows；大小超限不作部分解析或寫入，gateway 回可分類 `RESPONSE_TOO_LARGE`，目前 attempt 記為 FAILED 並停止；本任務固定窗口、不自動拆分或建立 parent/child windows。若實際遇超限需先修復明確上限／窗口契約並測試，不得靜默改 campaign manifest。所有 decimals 為 canonical positive string 並符合既有 DB precision，volume 是官方 minute field 的原始整數 unit 原樣傳遞。

日 K Java client 僅接受既有 daily envelope 與 validated echo；日 K response 的 unit contract 沿用 daily store／current SDK adapter，依官方 daily unit 保存 shares。1m Java client 另新增專屬 `HistoricalIntradayCandlesRead`，不冒用只代表單日的 `IntradayCandlesRead`，嚴格核對 envelope、時間、排序、identity、query window、row/body 上限及每列欄位。minute `volume` 與 `average` 不換算：volume 保持既有 minute schema 的 vendor raw unit；`average` 為官方 session-cumulative average，只須為正數，不受單 bar OHLC 邊界限制。現有 `fubon_intraday_candle` 唯一鍵與 content hash 保留；每列由既有 history store 的 REQUIRES_NEW transaction 寫入。相同 hash 為 unchanged；同 key 異 hash 為 `CONFLICT_NO_SOURCE_REVISION`，不更新或刪除既有 row。Daily 經 immutable facts store，只有 fact 已完成寫入才進行既有 guarded stock-price projection。

Migration v1.138.0 新增兩張 receipt 表及索引/FK/checks，並將 `ck_fubon_intraday_candle_prices` 拆成 OHLC 有效性及 `average > 0`，移除 `average >= low AND average <= high`。保留 schema 中市場/provider/timeframe、正數價格、minute、source-day、volume、payload hash 與事實 PK 所有其他條件；既存 rows 不回填改值、不 delete。執行既有 db export／schema drift 檢查，`db/schema.sql` 必須精確反映 migration。

#### 不提供歷史的 current-only 資料與結果

`intraday.volumes` 目前是 current-day price-level snapshot，存在具 TTL Redis cache，官方未提供可查過去日期的 snapshot endpoint，且沒有持久來源。Campaign 在 manifest/終結報表中對 `CUMULATIVE_INTRADAY_QUOTE_VOLUME` 及 `INTRADAY_PRICE_VOLUME_DISTRIBUTION` 固定產生 `UNSUPPORTED` coverage 註記及原因，不能為歷史日期呼叫 current-day route，不能用 1m OHLC bar 推演或聲稱重建這類 vendor snapshot，也不新增假的 receipt fact row。1m candle 的 bar volume 是獨立原始 fact；不得誤稱為 cumulative quote-volume 或 price-volume distribution。

執行後以 DB query 產出按 dataset、symbol 的既有事實 row count、最早／最晚 source date，以及本 campaign attempts 的 COMPLETE／NO_DATA／FAILED／CONFLICT／SCOPE_CHANGED 數，另列 unsupported current-only datasets、實際 latest completed date 與 campaign UUID。報告需比對執行前在 runbook 記錄的全表／campaign symbol counts；各 logical window 以最新 attempt 終態判定是否解決；未解決錯誤、未完成 windows 或 conflicts 使 campaign outcome 為 `PARTIAL`，CLI exit 非 0。只有每個可用 window 的最新 attempt 終態為 COMPLETE/NO_DATA、無未解決 scope/error/conflict 且 unsupported 範圍明列時才回成功；歷次 FAILED/INTERRUPTED 保留累積稽核統計，後續成功不改寫舊 attempt，但舊失敗不再阻擋 campaign 完成。CONFLICT/SCOPE_CHANGED 永久阻擋該 campaign，不得 retry；遇既有 FAILED 而未指定 retry-failed 則立即以非零退出，不略過後續執行。

歷史專用 gate 允許休市日執行已完成交易日的回補，不更動既有當日排程 gate；最新完成日的解析使用日曆逐日回溯，遇未知日曆則停止，本歷史工作固定排除今日，不論今日是否已收盤。新 adapter minute route 的歷史日期上界由 Java runner 的已知完成日保證，Python 必須拒絕今日與未來日期（本次只補過往完整日），不可假稱 Python 現有 SDK 提供交易日曆。一次性 runner 使用獨立 Spring 啟動入口與明確 bean import，不能啟動一般 server component scan、@Scheduled、WebSocket、SSE consumers 或其他啟動同步；web-disabled 本身不構成排程隔離。這項隔離須有 context test 證明。

## 驗證

```bash
bash scripts/spec-check.sh
bash scripts/tests/schema-sql-drift-test.sh
git diff --check
```

執行 implementation 對應的 fubon-broker-service Python adapter/route tests、external-materials-service unit/integration tests 與 migration 驗證。生產 backfill 前記錄既有 `fubon_historical_daily_candle`、`fubon_intraday_candle` 逐 symbol/dataset counts 和 source date coverage。以一次性、web-disabled runner 建立 campaign；不透過 current-day scheduler，不修改 main `.env`。campaign 完成後重查相同 coverage、receipt outcomes、無超出固定 radar symbol scope，且 no order/account endpoint invocation；同一 campaign 無 retry-failed resume 必須零重複 SDK calls。

## 完成報告

#### 實作與 production campaign 驗收（2026-10-01）

- Campaign：`e78c95f6-8477-4f6e-856f-6354c1ab79c9`，狀態 `SUCCESS`；範圍 2016-09-30 至 2026-09-30，24 個凍結 symbols，1,224 個 logical windows，最新已完成日期 2026-09-30。
- Campaign receipts：1,080 `COMPLETE`、144 `NO_DATA`；歷次 retry 留存 46 個 `FAILED` attempts（44 `UPSTREAM_UNAVAILABLE`、1 `HISTORY_BUDGET_EXHAUSTED`、1 `INVALID_RESPONSE`），最終每個 logical window 最新狀態均為 `COMPLETE` 或 `NO_DATA`。插入 3,890,389 rows，unchanged 0、conflicts 0；`STARTED` 0。失敗歷史 attempts 未改寫，重試後成功解除阻擋。
- Baseline／final：每個受納入的 symbol 在 campaign 前都沒有這兩類 Fubon historical facts（0 rows）；campaign receipts 對應最終 rows 的 campaign inserts，故 baseline `final - inserted = 0`。Daily 最終 43,601 rows，覆蓋 2016-09-30 至 2026-09-30；1m 最終 3,846,788 rows，覆蓋 2023-05-23 至 2026-09-30。逐 symbol 明細如下：

| Symbol | 日 K rows；coverage | 1 分 K rows；coverage |
|---|---:|---:|
| 0050 | 2,433；2016-09-30–2026-09-30 | 214,833；2023-05-23–2026-09-30 |
| 0056 | 2,438；2016-09-30–2026-09-30 | 216,696；2023-05-23–2026-09-30 |
| 006208 | 2,363；2016-10-05–2026-09-30 | 208,582；2023-05-23–2026-09-30 |
| 00679B | 2,363；2017-01-17–2026-09-30 | 214,569；2023-05-23–2026-09-30 |
| 00697B | 2,261；2017-06-23–2026-09-30 | 46,188；2023-05-23–2026-09-30 |
| 00713 | 2,193；2017-09-27–2026-09-30 | 212,582；2023-05-23–2026-09-30 |
| 00719B | 2,104；2018-02-01–2026-09-30 | 108,884；2023-05-23–2026-09-30 |
| 00751B | 1,943；2018-10-03–2026-09-30 | 185,846；2023-05-23–2026-09-30 |
| 00850 | 1,727；2019-08-23–2026-09-30 | 145,909；2023-05-23–2026-09-30 |
| 00859B | 1,690；2019-10-18–2026-09-30 | 15,878；2023-05-23–2026-09-30 |
| 00865B | 1,659；2019-11-25–2026-09-30 | 56,675；2023-05-23–2026-09-30 |
| 00878 | 1,509；2020-07-20–2026-09-30 | 216,596；2023-05-23–2026-09-30 |
| 00881 | 1,409；2020-12-10–2026-09-30 | 209,397；2023-05-23–2026-09-30 |
| 00882 | 1,370；2021-02-04–2026-09-30 | 199,340；2023-05-23–2026-09-30 |
| 00919 | 955；2022-10-20–2026-09-30 | 215,859；2023-05-23–2026-09-30 |
| 009804 | 358；2025-04-16–2026-09-30 | 38,205；2025-04-16–2026-09-30 |
| 009816 | 157；2026-02-03–2026-09-30 | 41,518；2026-02-03–2026-09-30 |
| 009826 | 41；2026-08-03–2026-09-30 | 10,673；2026-08-03–2026-09-30 |
| 2308 | 2,438；2016-09-30–2026-09-30 | 214,199；2023-05-23–2026-09-30 |
| 2330 | 2,438；2016-09-30–2026-09-30 | 217,221；2023-05-23–2026-09-30 |
| 2454 | 2,438；2016-09-30–2026-09-30 | 209,218；2023-05-23–2026-09-30 |
| 2881 | 2,438；2016-09-30–2026-09-30 | 215,696；2023-05-23–2026-09-30 |
| 2885 | 2,438；2016-09-30–2026-09-30 | 215,092；2023-05-23–2026-09-30 |
| 2891 | 2,438；2016-09-30–2026-09-30 | 217,132；2023-05-23–2026-09-30 |

- Current-only unsupported：`CUMULATIVE_INTRADAY_QUOTE_VOLUME`（無歷史 vendor endpoint／持久來源）、`INTRADAY_PRICE_VOLUME_DISTRIBUTION`（僅當日 Redis snapshot）。Campaign 未呼叫當日分價量 API，也未產生仿造歷史 snapshot；沒有交易或帳戶 endpoint 呼叫。
- 驗證：Python broker suite 828 passed；Java focused suites 28 passed、0 failures；Liquibase/schema drift 通過，`db/schema.sql` 與執行資料庫一致；`git diff --check` 通過。`scripts/spec-check.sh` 仍被外部 `/Users/steven/Project/SRPP/docs/9090 Port API Swagger.md` 產生檔 stale BLOCK；該文件屬另一個 SRPP workstream，本次未修改。
- Run-stack：`external-materials-service`、`fubon-broker-service`、`business-services`、BFF、API gateway healthy；9090 `/api/quotes` JSON array smoke 通過。External image `sha256:410d19d171386b9a768cc52810b503d10facd87a30af37c41920e34d83ae5e56`；broker image `sha256:7497a1eb1b811014ada83f10860d24ce6c04da92d387cf06464d21bc86d73605`。未修改 main `.env` 或秘密設定。
- Landing：Feature commit `acb111d65ce1c95fd568ff3fd2996b762cb0e759` 已推至 `origin/codex/fubon-historical-backfill`；main `--no-ff` merge commit 為 `777505b99fe5ad63eb0de93b94f7ebaa30cc679c`，`origin/main` remote readback 與本地 SHA 一致，merge graph 保留 feature commit 父線。原先 main `CLAUDE.md` 的使用者 model-rule 編輯以 named stash 暫存後已完整還原，未進入 Task466 code commit；因 merge 同時納入 Task466 已提交 checkpoint 的 Requirements 計數更新，HEAD-relative diff 的 Git object index 行改變，但使用者 patch（排除該 index 行）前後 SHA-256 均為 `71d526f3b6d06fba4b0362d1b6817da0d70620b55225ddca7e87469a8322af31`。`scripts/spec-check.sh` 的 B9 仍因外部 SRPP Swagger 產生檔 stale 而 BLOCK；B10 schema drift PASS。Task466 未修改 SRPP Task467 文件或其 worktree。

### 2026-09-28 暫停保存紀錄

使用者因用量接近上限要求停止開發與歷史回補，之後要求保存提交。此提交僅保存未完成進度，不代表功能驗收或歷史回補完成。

- 已留下規格及部分 Python adapter／route／測試變更。
- Java 回補 runner、receipt migration、既有 daily caller 日期窗同步修正尚未完成。
- 未執行歷史回補，沒有 campaign ID、資料覆蓋成果或部署驗收證據。
- 本次保存只檢查 Git 差異格式，不重啟開發、測試套件、部署或回補。
- 不合併至 main；待後續明確恢復工作、完成實作與驗收後再落地。
