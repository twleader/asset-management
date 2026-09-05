# [t370] 台股十秒富邦優先行情與最新快照持久化

> **現況覆寫（Requirement 138／Task 414）：** 本檔關於 static `INVENTORY_SYNC_CAPACITY_CONFLICT` 與「先關閉 LIVE 才能啟用 inventory」的文字已失效。LIVE、inventory、trade 與 bank reader 可同時啟用，實際資源由共享五個 native slots、quota 與不重置 deadline 仲裁；不得依本 task 恢復靜態互斥。

**對應 Requirements:** Requirement 106（台股盤中每 10 秒 Fubon → TWSE MIS → Yahoo，且 Redis/DB 僅新蓋舊）
**前置任務:** t353
**Liquibase changeset:** v1.113.0-stock-intraday-quote.sql

## 背景

目前台股盤中 producer 每兩分鐘執行，`FUBON_ENABLED=false` 時只使用 TWSE MIS；`FUBON_ENABLED=true` 時改成只使用富邦，富邦失敗不會 fallback。富邦 adapter 已能讀取並驗證 actual trade、昨收、開高低、買賣一檔、成交量、名稱與來源時間，但成功 quote 會快取 30 秒，而且這些完整欄位目前只寫 Redis；DB 只在部分成功時補 stock 主檔名稱。

使用者要求台股盤中改為每十秒依序嘗試「富邦證券 API → TWSE MIS → Yahoo」，並要求 Redis 和 DB 都必須按來源行情時間嚴格比較，只有較新的資料可覆寫較舊資料；富邦 API 回傳的所有已驗證行情欄位也要保存。

`FUBON_ENABLED=true` 是 adapter 主開關，不是庫存同步授權；tracked `.env.example` 固定設定 `FUBON_ENABLED=true`、`FUBON_TW_LIVE_QUOTES_ENABLED=true`、`FUBON_INVENTORY_SYNC_ENABLED=false`。只有前兩者皆 true 時 external 才可打富邦台股 LIVE；inventory false 時 backend scheduler 與手動庫存 sync 必須在呼叫 adapter 前 typed `INVENTORY_SYNC_DISABLED`、零 SDK／DB side effect。Requirement 138／Task 414 取代舊有的 consumer 靜態互斥：兩個 flag 同為 true 時 inventory 可進入既有唯讀 adapter，與 LIVE 共用五個 native slots、quota 與不重置 deadline 仲裁，不得要求關閉 LIVE。富邦目前公開的市場資料契約僅涵蓋台灣證券市場，沒有可驗證的美股 actual-trade endpoint 與來源 timestamp；因此本任務不得對美股發出富邦 request，也不把美股既有 2 分鐘排程改為 10 秒。將來若官方正式提供美股端點，必須另立 requirement，先完成 market adapter、時間契約、速率／容量及 integration test，才可啟用美股十秒更新。

`stock_price_history` 是正式日線／收盤的權威資料：台股今日列只可由盤後 TWSE／TPEx 官方對帳或 FinMind 缺檔 fallback 寫入。它沒有來源行情 timestamp，絕對不可用來保存每十秒盤中 quote，否則盤中成交會覆蓋正式收盤語意。因此本任務新增一張「每檔最新盤中 snapshot」表，與既有 tick LIST 及日收盤歷史分開。

本任務只做唯讀行情查詢與保存，嚴禁新增或呼叫任何下單、改單、刪單、轉帳或資金操作。

## 要做什麼

- [ ] **370.1 單一十秒台股入口與避免重疊。** 將 `PricePoller.scheduledTwIntradayUpdate` 改為 Spring 六欄 cron `*/10 * 9-13 * * MON-FRI`、zone `Asia/Taipei`；已存在的 known-open/holiday gate 仍是唯一是否可呼叫任何外部台股行情的決定。排程、`refreshAll`、warmup 與既有 internal refresh 都必須走同一個 `TwLiveQuoteDispatcher`。Dispatcher 建立 process-local in-flight gate；前一輪未結束時回明確 skipped 結果且零外部呼叫、零 Redis/DB/tick/day-HL mutation。`0000` 仍永遠排除。美股、英股、台股大盤、ETF NAV、收盤、分時收尾的 cron 與行為一律不改。

- [ ] **370.2 將台股 provider 改成逐檔 fallback chain。** 移除「啟用/停用後由 Spring 選其中一個 provider」的二選一語意，改成一個可測試的台股 LIVE coordinator：

  1. `FUBON_ENABLED=true` 且 `FUBON_TW_LIVE_QUOTES_ENABLED=true`：先用 `FubonNormalizedQuoteClient` 以 strict `purpose=LIVE` 詢問富邦 normalized endpoint。
  2. 對每一個未產生可接受新 observation 的 code，使用 `PriceFetchClient.fetchTwBatch` 的 TWSE MIS。
  3. 對仍未產生可接受新 observation 的 code，最後使用新的 Yahoo 台股 LIVE reader。

  先將輸入去重、正規化並依 code 穩定字典序排序；不得沿用 dispatcher 的 100 檔靜默截斷。富邦對單一 code 的失敗、misconfigured、服務不可用、429 circuit、timeout、payload reject、無成交或 DB 回 `STALE_OR_EQUAL` 都必須讓該 code 進下一來源；富邦整批失敗時全數進 MIS。MIS 在無成交、payload reject、HTTP failure 或 DB 回 `STALE_OR_EQUAL` 時才進 Yahoo。DB `FAILED` 是本輪 local persistence failure：該 code 不得寫 Redis，也不再用下游來源掩蓋失敗。`FUBON_ENABLED=false` 或 `FUBON_TW_LIVE_QUOTES_ENABLED=false` 時不得建 token、不讀 token file、不打富邦端點，直接走 MIS → Yahoo。每個 fallback 只能針對尚未 DB APPLIED 的 code；Fubon 已 DB APPLIED 的 code 禁止再打 MIS/Yahoo，即使後續 Redis 同步暫時失敗。

- [ ] **370.3 三個來源的 actual-trade/time contract。**

  - **Fubon：** 只接受現有 Python adapter 已正規化、`source=FUBON_INTRADAY`、`quoteStatus=LIVE`、`closed=false` 的 actual trade；`updatedAt` 是富邦 `lastTrade` 或 `closePrice` 的 UTC 來源時間，不得改為 HTTP receipt time。
  - **TWSE MIS：** 沿用 Task 350 的既定 actual-trade 契約：只接受唯一 requested code 的正數 `z` 與 strict `d=yyyyMMdd`、`t=HH:mm:ss`，以 `LocalDateTime(d,t)` 在 `Asia/Taipei` 組成唯一 `PriceResult.freshnessInstant`。`tlong` 不是成交時間；它選填，存在時只驗 strict epoch milliseconds 及轉台北日期等於 `d`，壞／缺只加 source-time anomaly，不能要求它等於 `t`、不能讓合法 `d/t/z` 失效，也不能推進 freshness。
  - **Yahoo：** 新增獨立台股 LIVE method，先 `.TW`、沒有合格結果才 `.TWO`；只接受當日正數 regular-market/latest-bar actual price，以及回應中的 epoch source time。時間缺漏、不是台北當日、超過本機 UTC 時鐘 30 秒、價格非正，或只有昨收／開盤／買賣價而沒有 actual trade 時都回 empty。Yahoo 不可用本機 receipt time 補 freshness，且只作第三層 fallback。

  所有來源都不得以 `previousClose`、`openPrice`、`buyPrice`、`sellPrice`、中間價、試撮價或派生值當作 `price`。

- [ ] **370.4 共同 Redis strict-newer primitive。** 將所有台股 LIVE source（含 Fubon）統一走 `PriceCacheWriter` 的同一條 Redis Lua latest-key primitive；移除或完全停用只限 Fubon 的 provider takeover 規則，來源字串不能再影響同日 LIVE 的覆寫資格。對同 key 的決策固定為：

  1. incoming `tradingDate` 較舊 → reject。
  2. 同日已有 `VERIFIED_CLOSE` → incoming LIVE 一律 reject。
  3. 同日 LIVE／PREVIOUS_CLOSE 比較 `updatedAt`，只有 incoming 嚴格大於 existing 才接受；equal 也 reject。
  4. 較新交易日維持既有可前進行為。

  Lua reject 必須不改 latest key、index set、任一 TTL、PUBLISH、tick 或 day-HL。`PriceCacheWriter` 必須回傳足以讓 coordinator判定 Redis written／stale／failed 的結果，不能只印 log 後模糊計數；same-tuple repair 永遠以 DB returned canonical row 重新走 common writer，禁止把本輪 raw payload 直接重播。

- [ ] **370.5 tick 與日內 high/low 僅能隨 accepted Redis quote 前進。** MIS/Yahoo 的 `PriceCacheWriter.write` 在 latest-key 成功以前只能唯讀目前 `price:dayhl:{market}:{code}:{tradingDate}`；可以用它與本次成交價計算即將放入 payload 的 high/low，但不得先寫 aggregate。只有 latest-key 成功後才呼叫 `IntradayHighLowTracker.observe` 並 append `price:ticks:*`。富邦維持已驗證 source OHLC 為權威，accepted payload 原樣使用其 high/low、只 append tick、絕不觸碰 `price:dayhl:*`。所有台股 priority LIVE 的 old/equal/current-malformed/write-failed 皆不得改 day-HL 或 tick；既有 non-Taiwan producer 仍沿用 Task 350 行為。

- [ ] **370.6 新增最新盤中 snapshot schema。** 新增並註冊 migration `backend/src/main/resources/db/changelog/changes/v1.113.0-stock-intraday-quote.sql`，建立：

  ```sql
  CREATE TABLE stock_intraday_quote (
      stock_code          VARCHAR(20) NOT NULL,
      market              VARCHAR(20) NOT NULL,
      trading_date        DATE NOT NULL,
      provider_updated_at TIMESTAMPTZ NOT NULL,
      source              VARCHAR(64) NOT NULL,
      actual_price        NUMERIC(20,10) NOT NULL,
      previous_close      NUMERIC(20,10),
      open_price          NUMERIC(20,10),
      high_price          NUMERIC(20,10),
      low_price           NUMERIC(20,10),
      buy_price           NUMERIC(20,10),
      sell_price          NUMERIC(20,10),
      volume              BIGINT,
      PRIMARY KEY (stock_code, market)
  );
  ```

  加上以下 CHECK：`actual_price > 0`；六個 nullable price 欄為 null 或 `> 0`；`volume IS NULL OR volume >= 0`；`(provider_updated_at AT TIME ZONE 'Asia/Taipei')::date = trading_date`。migration 必須冪等，master YAML 註冊一次；不得給這張表加 surrogate id、`stock_name`、`price_change`、`change_percent`、raw secret payload 或任何正式收盤欄位。另修正既有 `schema-sql-drift-test.sh` 在 schema 相同成功路徑中，未以 `${schema_rel}` 包住且緊接全形文字而被 Bash 當成未定義變數的問題；重產後該腳本必須真的回 0，不能只因 shell error 被 spec-check 降成 CHECK。Liquibase 已套用 migration 後，必須在 repo root 依下列精確程序重產（不可直接先截斷 `db/schema.sql`）：

  ```bash
  docker exec asset-postgres pg_dump -U assets -d assets \
    --schema-only --no-owner --no-privileges \
    | grep -v '^\\restrict\|^\\unrestrict' > /tmp/t370-fresh-schema.sql
  HDR=$(( $(grep -nxF -- '-- PostgreSQL database dump' db/schema.sql | head -1 | cut -d: -f1) - 2 ))
  head -n "$HDR" db/schema.sql > /tmp/t370-schema-header.sql
  cat /tmp/t370-schema-header.sql /tmp/t370-fresh-schema.sql > db/schema.sql
  grep -c '^CREATE TABLE' db/schema.sql
  ```

  最後將 `db/schema.sql` 檔頭的「產生當下表數」改成上列最後輸出的數字，再執行 `bash scripts/tests/schema-sql-drift-test.sh`；migration、schema baseline 與 drift test 三者少任一項都不算完成。

- [ ] **370.7 DB 原子 strict-newer upsert 與完整富邦 mapping。** 在 `StockSourceQuery` 或等價的 repository boundary 實作一個 transaction-bound single-observation persistence method。它對 `(stock_code, market)` 使用：

  ```sql
  INSERT ... ON CONFLICT (stock_code, market) DO UPDATE SET ...
  WHERE EXCLUDED.provider_updated_at > stock_intraday_quote.provider_updated_at
  RETURNING stock_code;
  ```

  回傳 `APPLIED(canonicalRow)`／`STALE_OR_EQUAL(canonicalRow)`／`FAILED`；equal 或 older 必須更新零欄。APPLIED 時完整寫入這些 Fubon 正規化欄位：`actualPrice, previousClose, openPrice, highPrice, lowPrice, buyPrice, sellPrice, volume, tradingDate, updatedAt, source`。MIS/Yahoo 有值的同名欄位也寫入；缺值保留 null，不能從 Redis、舊 DB row 或自行運算補值。`stockName` 不放進新表；只有 APPLIED 且名稱非空、不等於 code 時，才在同一 transaction 更新 `stock.name`，且可覆蓋舊名稱，確保較舊回應無法把名稱倒灌。`STALE_OR_EQUAL` 回傳的是已存 row，不代表 incoming raw payload 可被任何 sink 接受。

- [ ] **370.8 DB canonical first、Redis 後同步與可證明補償。** 對每個有效 observation，唯一順序固定為：(A) 在 transaction 內做 DB newer-wins upsert，取回 APPLIED 的新 row 或 STALE_OR_EQUAL 的既有 canonical row；(B) **只有**取得 canonical row 才以它走 common Redis writer；(C) 只有 Redis WRITTEN 才進 Task 370.5 的 tick/day-HL side effect。DB `FAILED` 時 B/C 一律不做，故 Redis 不得領先 DB。DB APPLIED 但 Redis `FAILED` 時，下輪遇同 timestamp 必須先得到 DB `STALE_OR_EQUAL(canonicalRow)`，再以 canonical row 補 Redis；不得用 incoming raw 的不同 source／價格／欄位覆寫。DB STALE_OR_EQUAL 時，raw source 要依 370.2 繼續 fallback；canonical row 可獨立嘗試修復缺失或較舊的 Redis，但 cache 已有 equal/較新 tuple 時仍零 mutation。若 Redis 拒絕 DB APPLIED row（例如 legacy cache 較新或 verified close），source 已算 DB accepted、不向下 fallback，留下可稽核 outcome；不得反寫或降級 cache。成功／stale／failed／repair 狀態必須可被固定 enum counter 或 structured log 稽核，不能靠 stock code 作 metric label。

- [ ] **370.9 富邦十秒節流、purpose cache、typed circuit 與容量。** adapter wire 的 quote request 必須新增 required enum `purpose=LIVE|INVENTORY`（缺漏／未知=400、零 SDK call）；`FubonNormalizedQuoteClient` 永遠送 LIVE，既有 `FubonInventorySyncService` 只在 live flag=false 且 inventory flag=true 時送 INVENTORY。`QuoteService` 對 LIVE 完全移除跨輪 success cache（不可讀、不可寫、不可把 inventory cached payload 回給 LIVE）；同時請求的相同 `(purpose, code)` 仍可共用 `_inflight` future。INVENTORY 保留既有每 code 30 秒 success cache，但只可在單 consumer inventory-only 模式使用。process-wide rate limit 維持 `MAX_CALLS_PER_MINUTE=240`、429 至少 60 秒 circuit、官方 429 不重試。adapter wire response 與 `FubonNormalizedQuoteClient.BatchResult` 必須保留 per-code failure reason，特別是 `RATE_LIMITED`、`RATE_LIMIT_CIRCUIT_OPEN`、`RATE_LIMIT_BUDGET_EXHAUSTED`，讓 coordinator 可設定 monotonic local `fubonSkipUntil`。known skip window 內零 Java→Fubon HTTP與零 SDK quote call、全數直接 MIS → Yahoo；冷啟／重啟時最多容許一次 adapter status probe，adapter 發現 circuit open 時本身必須零 SDK quote call，並使 coordinator 設定 skip window。Dispatcher 每 10 秒最多送 40 檔給富邦：

  - code 數 ≤40：全部先 Fubon；
  - code 數 >40：以穩定字典序維護 deterministic round-robin cursor，選 40 檔先 Fubon，未入選者同輪直接走 MIS，下一輪從上次末端繼續；
  - MIS fallback 超過其 `MAX_REQUESTED_CODES=320` 時：依原穩定順序分成最多 320 檔的多個 batch，合併回每個 code 的唯一 outcome，絕不整批拒絕或靜默截斷；
  - Fubon circuit open：在已知 local skip window 中零 Fubon HTTP／SDK calls，所有 code 直接 MIS → Yahoo。

  不得因容量而安靜截斷、固定只抓前 40、把 budget exhausted 誤標為成功，或讓 provider cache 使 10 秒排程實質停在 30 秒。41、100、321 檔都必須在一個 round 產生完整 outcome；full cursor cycle 後每個 code 至少取得一次 Fubon 機會。

- [ ] **370.10 排程 catalog 與設定邊界。** 將 `SchedulePublicBffController.JOBS` 的「台股個股即時價（盤中）」同步成「交易日 09:00–13:30 每 10 秒」、cron `*/10 * 9-13 * * MON-FRI`，描述明確寫 Fubon → TWSE MIS → Yahoo、Redis＋最新盤中 snapshot、排除 `0000`。不新增排程條目，僅改既有的一筆；總 job count 不應改變。`FUBON_ENABLED` 仍由未納版的 deployment `.env`／secret mount 控制，不能把憑證、token、帳號或密碼寫進 source、log、schema、fixture 或公開 endpoint。依使用者要求，tracked `.env.example` 必須是 `FUBON_ENABLED=true`、`FUBON_TW_LIVE_QUOTES_ENABLED=true`、`FUBON_INVENTORY_SYNC_ENABLED=false`；compose 必須將 master＋live flag 傳給 external，master＋live＋inventory flag 傳給 backend，master flag 傳給 Python adapter。enabled 但 adapter 未配置／不可用時，台股必須以 typed failure 走 MIS → Yahoo；inventory disabled 不得 call adapter、不得讀 portfolio/quote、不得寫資產，而 LIVE=true 與 inventory=true 時仍由共享 resource gate 決定，不得以 capacity-conflict 短路。美股不得讀取或使用任一富邦 flag，也不得變更既有 2 分鐘 cadence。


- [ ] **370.11 必要測試。**

  - Python：兩次串行 LIVE 成功讀取同 code 必須有兩次 SDK quote call；並行相同 `(LIVE, code)` 仍只一個 in-flight call；INVENTORY-only 的兩次讀取才可命中既有 30 秒 cache；LIVE 不得讀到 inventory cache；缺／未知 purpose 是 400、零 SDK call；240/min budget、429 circuit、actual-trade/時間驗證不可回歸。
  - Java client：Fubon fields 全量 mapping、strict purpose wire（LIVE client／inventory client）與 cache 隔離、TWSE strict `d+t` 才是 freshness（壞／缺 `tlong` 只記 anomaly）、Yahoo `.TW`→`.TWO` fallback 與 timestamp reject matrix，以及 per-code rate/circuit reason 的 preservation。
  - Coordinator：Fubon DB APPLIED 時零 MIS/Yahoo；Fubon individual/whole-batch/misconfigured/429/stale/equal 時依 code fallback；MIS DB APPLIED 時零 Yahoo；MIS stale/failure 才 Yahoo；DB failure 零 Redis且停止該 code；disabled 零 Fubon；41/100/321 code 的 capacity round-robin、MIS batching、full cursor cycle、local circuit skip 與 in-flight skip。
  - Feature flag 隔離：.env.example 的 master/live/inventory 三值精確為 true/true/false；master 或 live 關閉時 external 零 token／Fubon HTTP 並走 MIS → Yahoo；inventory 關閉時 scheduler 與手動 service 都回 INVENTORY_SYNC_DISABLED 且零 portfolio／quote／資產寫入；兩 consumer flag 同為 true 時 inventory 仍可進既有唯讀 adapter，LIVE 的 240/min 預算、共享五個 native slots及 deadline 仲裁不變。
  - Redis 真實 integration：跨 FUBON/TWSE/YAHOO 的 newer 成功一次，older/equal 不 SET/SADD/EXPIRE/PUBLISH/tick/day-HL，VERIFIED_CLOSE 不降級，Fubon source OHLC 不寫 day-H/L tracker，MIS/Yahoo 只在 accepted latest 後 observe；不能只 assert Java outcome。
  - PostgreSQL 真實 integration：migration 後 schema 的型別／CHECK、富邦所有可保存欄、newer wins、equal/older 各欄完全不變、stock name 僅隨 APPLIED 前進，以及 DB APPLIED/Redis failure 後同 timestamp 只以 canonical row 修復、DB equal/raw 不同不回灌、DB failure 零 Redis。不可用 mock SQL 宣稱已證明 `ON CONFLICT ... WHERE` 原子性。
  - 排程與 BFF catalog reflection：精確驗 cron／zone／友善文字，且公開 quote API 既有 19 keys、SSE、收盤流程、`stock_price_history` 不被盤中 pipeline 寫入。

## 驗證

```bash
set -euo pipefail

bash scripts/spec-check.sh

docker buildx build --platform linux/amd64 --target test \
  -f fubon-broker-service/Dockerfile fubon-broker-service \
  --load -t asset-fubon-broker-service:test
docker run --rm --platform linux/amd64 asset-fubon-broker-service:test pytest -q

/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q \
  -f external-materials-service/pom.xml test \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q \
  -f bff/pom.xml test \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true

# 套用 Liquibase 後重產 db/schema.sql，接著它必須與實際 DB 一致。
bash scripts/tests/schema-sql-drift-test.sh

# 本專案沒有 dev server；實作完成需無快取重建並 recreate 實際服務。
docker compose -p asset-management build --no-cache \
  fubon-broker-service business-services external-materials-service bff
docker compose -p asset-management up -d --no-deps --force-recreate \
  fubon-broker-service business-services external-materials-service bff
docker compose -p asset-management ps
```

實機只做唯讀驗收：確認四個服務 health、公開 quote 的既有 19 欄形狀、以及 `stock_intraday_quote` 已存在。若當下不在台股 known-open 時段，禁止繞過 `MarketClock` 造假 LIVE 寫入；以 cron reflection、fixture、真 Redis/PostgreSQL integration 驗證十秒與 strict-newer 行為。真實富邦 secrets 只可在已授權交易時段驗證，完成報告必須清楚區分 fixture／integration 與真實 Fubon LIVE 證據。

## 完成報告

（實作者完成後回填：實際變更檔、各驗證指令結果、migration/schema regeneration 證據、FUBON_ENABLED 部署狀態、真實市場時段驗證是否執行，以及任何與本任務不同的取捨。）
