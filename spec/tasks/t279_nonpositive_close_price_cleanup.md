# [t279] 清除 stock_price_history 台股 175 列非正收盤，並在 fetch／收盤校正／寫入／Redis／DB 五處擋住復發

**對應 Requirements:** Requirement 62（台股「當日無整股成交價」不得以收盤價 0 寫進 `stock_price_history`，既有髒列刪除、不回補、不標記）
**前置任務:** 無
**Liquibase changeset:** `v1.85.0-drop-nonpositive-close.sql`

> **編號提醒（本專案一年內有 12 次 commit 專門在做編號避讓）：** 建檔當下實測 main 與運行中 DB 的 `databasechangelog` 皆停在 `v1.84.0-asset-transaction-fee-tax`，另有多個 worktree 同樣停在 v1.84.0。**動工前重跑一次 `bash scripts/spec-check.sh`**；若 `v1.85.0` 已被別的分支取用，改用下一個未用號並同步改本檔與 `db.changelog-master.yaml`。

## 背景

### 現在的錯誤行為

`stock_price_history` 台股有 **175 列 `close_price = 0.0000`**（該欄 `NOT NULL`，故無 NULL 列）。這些列被 MA、乖離率、波動度、KD 等計算當成真實收盤價，**單一列即造成該日乖離率 `= (0 − MA60)/MA60 = −100%`**，並把 MA60 往下拉、把滾動波動度灌大，產生不存在的買賣訊號。

實測分布（`docker exec asset-postgres psql -U assets -d assets`）：

```sql
SELECT stock_code, count(*) FROM stock_price_history
WHERE market='台股' AND close_price<=0 GROUP BY stock_code ORDER BY 2 DESC;
```

| 代號 | 列數 | 期間 |
|---|---|---|
| 006208 | 87 | 2016-08-03 ～ 2018-04-12 |
| 7556 | 67 | 2019-11-27 ～ 2024-10-04 |
| 00865B | 6 | 2020-04-07 ～ 2022-02-08 |
| 3036 | 5 | 2017-04-06 ～ 2025-07-15 |
| 2327 | 4 | 2018-04-27 ～ 2021-06-30 |
| 3037 / 9933 / 00695B / 00719B / 2303 / 2317 | 各 1 | — |
| **合計** | **175** | |

### 成因（已查證，非推測）

**交易所對「當日無整股成交」一律不發布 OHLC（回 `--`），FinMind 將 `--` 序列化為 `0.0`，而我方只擋 `null`、沒擋 `0`。**

1. `PriceFetchClient.fetchTwHistoricalRange`（`external-materials-service/src/main/java/com/steven/assets/externalmaterials/client/PriceFetchClient.java:484`）只有 `if (close == null) continue;`。FinMind 回的 `close: 0.0` 非 null，於是進入 `HistoricalBar`，再由 `HistoricalBackfillService.backfillTwStock`（`HistoricalBackfillService.java:157`）呼叫 `store.upsertHistory(...)` 寫入。
2. 實測 FinMind `TaiwanStockPrice` 006208 於 2016-08-03 回：
   `{"date":"2016-08-03","Trading_Volume":0,"Trading_money":0,"open":0.0,"max":0.0,"min":0.0,"close":0.0,"Trading_turnover":0}`，而 08-02 收 39.6、08-04 收 39.22 皆正常。
3. 實測 **TWSE 官方**同檔同日（`https://www.twse.com.tw/rwd/zh/afterTrading/STOCK_DAY?date=20160803&stockNo=006208&response=json`，UA 須為 `Mozilla/5.0`）：
   `['105/08/03','0','0','--','--','--','--',' 0.00','0','']`。
   **來源本身沒有價，不是抓取失敗、也不是 FinMind 的缺陷。**

### 「零價」不等於「零成交」——本任務最容易做錯的一點

```sql
SELECT count(*) FILTER (WHERE close_price<=0 AND volume=0)  AS zero_both,      -- 實測 127
       count(*) FILTER (WHERE close_price<=0 AND volume<>0) AS zero_price_vol, -- 實測 48
       count(*) FILTER (WHERE close_price>0  AND volume=0)  AS priced_zero_vol -- 實測 9
FROM stock_price_history WHERE market='台股';
```

- **127 列**是完全無成交（`volume=0`、筆數 0）。含極低流動性標的整日無人交易，也含**個股當日暫停交易**——如 2317 鴻海 2025-07-30。**該日並非休市**：實測 `MI_INDEX?date=20250730&type=ALLBUT0999` 同日 2330 台積電正常成交（收 1,155.00），2317 那列是 `['2317','鴻海','0','0','0','--','--','--','--']`。
- **48 列有成交量卻仍無 OHLC**（006208 31、7556 15、00865B 2）。實測 TWSE 006208 2017-03-28：`['106/03/28','113','4,859','--','--','--','--',' 0.00','3','']`——**成交 113 股、金額 4,859、筆數 3，OHLC 仍是 `--`**；2017-06-12 為 `['1,240','56,827','--','--','--','--',' 0.00','7','']`。這是「當日只有零股／盤後成交、無整股成交價」。
- 另有 **9 列**是「有正常價格但 `volume=0`」，屬**合法資料，不得刪除**。

> **因此判準只有 `close_price <= 0`。** 任何拿 `volume` 當「這列是不是髒資料」代理的實作或測試都是錯的：用 `volume=0` 會漏掉 48 列並誤刪 9 列合法列。

### 為什麼只有台股

美股／英股走 Yahoo chart API，無成交日**根本不給該筆 timestamp**，既有的 `close.isNull() || close.isMissingNode()` 已足夠。實測台股 106,249 列中 175 列非正、**美股 40,587 列與英股 8,002 列皆為 0 列**。故本任務只動台股路徑。

### 第二條入口（尚未造成髒列，一併關掉）

`StockSourceQuery.upsertHistory`（`external-materials-service/src/main/java/com/steven/assets/externalmaterials/service/StockSourceQuery.java:463`）對 close 套用 `nz()`（同檔 `:590`，`v == null ? BigDecimal.ZERO : v`），把「沒有價」轉成「價 0」寫進 `NOT NULL` 欄位。現有 175 列不是由它產生（`ClosePersister.dumpRedisToDb` 於 `ClosePersister.java:359` 已先 `if (price == null) continue;`），但這是同一反模式的第二個入口。

### 正確行為

該日沒有整股收盤價，**就不該有這一列**。台股非交易日（週末、國定假日）在本表本來就沒有列，所有消費端早已按「缺列」處理；刪除後這些日子與國定假日在資料層完全同形，**不需要任何消費端配合改動**。

### 為什麼是刪除，不是回補或標記（此決定不得在實作時被推翻）

1. **回補在定義上不可能**：交易所沒有該 `(代號, 日期)` 的收盤價，真值不存在。
2. **不得 carry-forward 前一日收盤**：專案既有紀律是「抓不到就維持上一個 tick、禁止回寫充數」。寫入前一日收盤會憑空製造一天報酬率 0，**系統性低估波動度**（正是本任務要修的東西），並讓 MA 被非成交價稀釋。
3. **不得以 `成交金額 ÷ 成交股數` 反推**（2017-03-28 得 43.00、2017-06-12 得 45.83）：那是**零股成交均價**，與本欄語意（整股收盤價）是不同的價格序列，混入等於同一欄存兩種意義的值；且本表未存成交金額，要用還得重抓。
4. **標記無效**：把 0 留在 `close_price` 另加旗標，等於要求每個現有與未來的消費端都記得看旗標——實際上只有 `DistributionAdjustedPriceService.detectSplits()` 記得，其餘全部不記得，這正是現狀。

### 相關的既有註解需要更新

`backend/src/main/java/com/steven/assets/service/DistributionAdjustedPriceService.java:135` 的註解寫「非正收盤一律跳過：**實測台股有 176 筆**，否則會除以零或產生誤判。」——數字與實測 175 不符，且清理後為 0 列。

## 要做什麼

- [ ] 279.1 **建立 changeset `backend/src/main/resources/db/changelog/changes/v1.85.0-drop-nonpositive-close.sql`**，內容依序為：
      1. `DELETE FROM stock_price_history WHERE market = '台股' AND close_price <= 0;`
      2. `ALTER TABLE stock_price_history ADD CONSTRAINT ck_sph_close_price_positive CHECK (close_price > 0);`

      **順序不可顛倒**（先加約束會因既有 175 列而失敗）。**刪除條件必須是 `close_price <= 0`**，不得加入 `volume` 條件、不得列舉代號。市場別寫死 `'台股'`。約束本身涵蓋全部市場（實測美股／英股無非正列，不會擋到既有合法資料）。
      **冪等性**：DELETE 天然冪等；ADD CONSTRAINT 重跑會報 `already exists`，須以 `DO $$ ... IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='ck_sph_close_price_positive') THEN ... END IF; ... $$;` 包起來。
      > **⚠ 用了 `DO $$` 就必須加 `splitStatements:false`，漏掉會 crash loop。** Liquibase formatted SQL 預設 `splitStatements=true`、以 `;` 斷句，會把 DO block 內的 `THEN ...;`／`END IF;` 切成語法不完整的片段而執行失敗 → business-services 啟動時 Liquibase 中斷 → crash loop。本 repo 含 DO block 的 changeset 共 4 檔（`v1.34.1-drop-legacy-global-uniques.sql:9`／`:29`、`v1.71.0-configurable-admin-email.sql:3`、`v1.78.0-stock-alert-group.sql:52`、`v1.79.0-naive-timestamp-to-taipei.sql:3`），**無一例外**都帶此旗標；`v1.78.0-stock-alert-group.sql:57` 甚至已把這條規則寫成註解。

      檔頭格式：第一行 `--liquibase formatted sql`、接 **`--changeset steven:v1.85.0-drop-nonpositive-close splitStatements:false`**、再接 `--comment <說明>`。（`splitStatements` 是 changeset 級旗標，故 DELETE 與 DO block 會以單一 statement 送出，PgJDBC 可行；若偏好比照 v1.34.1 拆成兩個 changeset 也可以，但**帶 DO block 的那個一定要有旗標**。）
- [ ] 279.2 **在 `backend/src/main/resources/db/changelog/db.changelog-master.yaml` 末端追加 include**，格式與既有項目一致（`file: db/changelog/changes/v1.85.0-drop-nonpositive-close.sql`、`relativeToChangelogFile: false`）。
- [ ] 279.3 **fetch 端擋掉非正收盤**：`PriceFetchClient.fetchTwHistoricalRange`（`:484`）把 `if (close == null) continue;` 改為同時擋非正值（`close.signum() <= 0`）。被跳過的列須留下可觀測紀錄（`log.debug` 或 `log.warn`，含代號與日期），**不得靜默丟棄**。**同批的正常列必須全部保留**——只跳過該列，不得因為出現一列 0 就丟棄整批或提前 return。
      **不得改動**同檔案內美股（`fetchUsHistoricalRange`）、英股、韓股的解析分支，以及 `:152`（`getUsClosingPriceFromFinMind`，美股）／`:793`（`fetchTwKBar5m`，產物是 Redis 分時 tick、不進本表）的 `close == null` 判斷。
- [ ] 279.3a **同一個 FinMind 缺口的第二個入口也要擋：`parseTwClosingRow`（`PriceFetchClient.java:201`）**。該處同樣只有 `if (close == null) return Optional.empty();`，讀的是**同一個** FinMind `TaiwanStockPrice` 資料集、同樣會拿到 `--` 序列化成的 `0.0`。改為 `close == null || close.signum() <= 0` 時回 `Optional.empty()`。
      > **為什麼這一處不能只靠 DB 三道防線擋。** 呼叫鏈是 `ClosePersister.verifyTwCloseWithFinMind`（`@Scheduled` 16:00 TW）→ `ClosePersister.java:221 upsertHistory(...)`（會被 279.4 的寫入端擋下）**→ `ClosePersister.java:226 cacheWriter.writeVerifiedClose(pr)`**。`PriceCacheWriter.writeVerifiedClose`（`PriceCacheWriter.java:148` 起）**沒有任何價格正值檢查**，直接 `payload.put("price", ...)`、`redis.opsForValue().set(...)` 並 `convertAndSend("price-update", ...)`。也就是說 DB 被擋住了，**Redis live cache 仍會被寫入 0**，Dashboard／SnapshotForm 會顯示股價 0——這牴觸專案既有紀律「即時價只能從 Redis 取／抓不到就維持上一個 tick，禁止回寫充數」。擋在 `:201` 可讓該檔當日直接算 `miss`，Redis 維持前一個值。
- [ ] 279.3b **Redis live cache 一併加正值守門（涵蓋台／美／英三個市場）**：`PriceCacheWriter.writeVerifiedClose`（`PriceCacheWriter.java:148` 起）對非正或 null 的 `result.price()` **一律不寫 Redis、不 `convertAndSend("price-update", …)`**，並記 log。
      > **為什麼要多這一道、而不是只擋 `:201`。** 美股的收盤校正走的是 `getUsClosingPriceFromFinMind`（`PriceFetchClient.java:135`，`:152` 同樣只擋 `null`），**不是** Yahoo；英股走 `verifyUkCloseWithYahoo`。兩者最後都會呼叫 `writeVerifiedClose`。也就是說「Redis 被寫 0」的機制對美股／英股同樣成立，只是實測尚未發生（美股 40,587 列、英股 8,002 列非正皆為 0）。**在此處守門一次即涵蓋三個市場**，故 279.3 明文不動 `:152` 的解析分支。
- [ ] 279.4 **寫入端擋掉非正收盤（縱深防禦）**：`StockSourceQuery.upsertHistory`（`:463`）在方法開頭判斷 `close == null || close.signum() <= 0` 時**直接不寫入**（既不 INSERT 也不 UPDATE）並記 log，**不得擲例外**（`ClosePersister.dumpRedisToDb` 對每個 code 逐一 try/catch，擲出去會被吃掉、只留一行看不出原因的 warn）。同時**移除 close 上的 `nz()` 呼叫**——「沒有價」不得再被轉成「價 0」；`nz()` 若無其他使用者則一併移除，有的話保留。
      **回傳值改為 `boolean`**（`true` = 有寫入／更新，`false` = 被拒），使呼叫端能分辨。**共 8 個呼叫點**，逐一確認都已處理：`HistoricalBackfillService.java:157`（`backfillTwStock`）／`:241`（`repairRange`）／`:275`（`backfillUsStock`）／`:302`（`backfillUkStock`）、`ClosePersister.java:221`（verify-tw）／`:272`（verify-us）／`:325`（verify-uk）／`:365`（`dumpRedisToDb`）。
      > **這 8 個必須逐一清點，不能靠編譯器。** `void → boolean` 對忽略回傳值的呼叫端**不會產生編譯錯誤**，漏改一處 build 依然會過。
- [ ] 279.5 **回補筆數不得把被拒的列算成已寫入**：以下 5 個計數器改為只在 `upsertHistory` 回 `true` 時累加——`HistoricalBackfillService.java:159`（`backfillTwStock` 的 `count++`）、`:243`（**`repairRange` 的 `wrote++`**，其 `rows += wrote` 在 `:245`）、`:277`（`backfillUsStock`）、`:304`（`backfillUkStock`），以及 `ClosePersister.java:369`（`dumpRedisToDb` 的 `n++`）。
      > **`:243` 的 `wrote++` 特別容易漏**——它的變數名與其他三支不同（`wrote` 而非 `count`），用 `count++` 搜尋會跳過它。`HistoricalBackfillService` 內另有 `:330`（`upsertRates`，走 `upsertExchangeRate`）／`:356`（`upsertCommodities`，走 `upsertCommodityPrice`）兩個 `count++`，**不走 `upsertHistory`，不得改動**。
      **`ClosePersister` 的三個 `ok++`（`:227`／`:277`／`:332`）一併納入**：它們在 `upsertHistory` 回 `false` 時仍會累加，log 會印出「FinMind 校正台股收盤完成：成功覆寫 N 檔」這種與事實不符的數字。改為只在實際寫入時累加。
      > **這三個 `ok` 同時是 `verify*` 方法的回傳值，有控制流消費端，不只是 log。** `ClosePersister.java:206 public int verifyTwCloseWithFinMind()` → `:235 return ok;`，消費端是同檔 `selfHealMissedClose` 的 `:131`（`if (finmindOk == 0) { … dumpTwCloseFromRedis(); }`）、`:149`（美股）、`:167`（英股）。收緊後，「來源有回值但全被寫入端擋下」會從「不 fallback」變成「fallback 去 dump Redis」。**此為預期行為**（新語意是「實際寫入 DB 的檔數」，比原本更正確），且實務機率極低（台股有 279.3a 先擋、美英實測 0 列非正）——但實作時要知道自己動到的是控制流，不是只有 log 文字。
- [ ] 279.5a **既有 `HistoricalRepairRangeTest` 必須補 mock stub（回傳型別變更的必然連帶）**：`external-materials-service/src/test/java/com/steven/assets/externalmaterials/service/HistoricalRepairRangeTest.java` 中 `store` 是 Mockito mock，`boolean` 的預設回傳是 `false`；計數改為「回 `true` 才累加」之後，該檔 `summary.rowsOverwritten()` 斷言中會有 **4 條變紅**：`:71`／`:93`／`:156`（皆 `isEqualTo(1)`）與 `:128`（`isEqualTo(2)`）。**`:112` 的 `isZero()` 不受影響**（該案例在 `:109` 是 `verify(store, never()).upsertHistory(...)`，本來就不呼叫），但一併列出以免被順手放寬。
      **修法是為 `store.upsertHistory(...)` 補上 `thenReturn(true)` stub**，**不得**改為放寬或刪除 `rowsOverwritten` 斷言——那正好會廢掉 279.5 要建立的保證。
      > `store` 由 `mock(StockSourceQuery.class)` 手動建立（`:43`）、**未掛 `MockitoExtension`**，故沒有 strict stubs，加一個共用的 `thenReturn(true)` 不會噴 `UnnecessaryStubbingException`，不需要逐案例加。
- [ ] 279.6 **更新全部宣稱「176 筆」的落點（共 2 處程式碼）**：`backend/src/main/java/com/steven/assets/service/DistributionAdjustedPriceService.java:135` 的註解「非正收盤一律跳過：實測台股有 176 筆…」，以及 `backend/src/test/java/com/steven/assets/service/DistributionAdjustedPriceServiceTest.java:173` 的 Javadoc「（實測台股有 176 筆）」。兩處都改為陳述現況——實測原為 175 列、已於 t279 全數刪除、`stock_price_history.close_price` 已有 `CHECK (close_price > 0)` 約束，此處保留跳過判斷作為除以零的縱深防禦。**該處的跳過邏輯本身保留、不得移除。**
      （`spec/requirements.md:1214` 與 `spec/design.md:4716` 內同樣寫「176 筆」的兩處，已於建立本任務時一併更正，實作者不需再動。）
      > **`spec/tasks/` 底下的 `t265:67`、`t223:312`、`t225:25/128/193` 也寫著 176，一律不動——這是刻意的，不是遺漏。** 依 `spec/tasks/README.md` 鐵則 3，任務檔「凍結在派工當下的事實」，回頭改寫等於竄改決策記錄。（t223／t225 另已在 `spec/tasks.md:265/267` 標記「⛔ 已由 t264／t265 取代，不得依本檔實作」；t265 則已標「已通過對抗式審查（3 輪）並完成實作」，其 `:67` 那條是 `- [x]` 完成紀錄、不是待辦。）**下一輪稽核若再掃到這幾處，請以本段為準判為刻意保留。**
- [ ] 279.7 **不新增排程、前端頁面或 BFF 端點**。本任務沒有使用者可見的操作面，故 `SchedulePublicBffController.JOBS`（排程列表頁）**不新增也不移除項目**，其既有 description 亦不受本任務影響（本任務不改任何 `@Scheduled` 的 cron）。
- [ ] 279.8 **測試**（放在本任務，不另開；**類名由實作者自訂**——本檔刻意不指定尚不存在的類名，`scripts/spec-check.sh` 的 B5 會把 spec 裡查無實檔的測試類名判為 BLOCK）：
      > **⚠ 先讀這段再動手寫測試——本模組的測試基礎設施比你以為的少。** `external-materials-service/pom.xml` 的測試依賴**只有 `spring-boot-starter-test`**：沒有 H2、沒有 Testcontainers、沒有 MockWebServer／WireMock。而 `PriceFetchClient` 的 `httpClient` 是在建構子（`PriceFetchClient.java:42-51`）內 `HttpClient.newBuilder()` 自建，**沒有注入點也沒有 setter**。所以「發一個假 HTTP 回應給 `fetchTwHistoricalRange`」與「真的寫一列進 DB 再查回來」這兩種寫法**都做不到**。**不得為此引入任何新測試依賴。**

      - **fetch 端（`fetchTwHistoricalRange`）**：比照同檔既有前例 `parseTwClosingRow`（`PriceFetchClient.java:192`，被抽成 package-private `static` 才得以被 `PriceFetchClientClosingDateTest` 直接呼叫）——把 `:481-493` 那段 FinMind 歷史列的解析／過濾迴圈抽成同樣可直接呼叫的 package-private `static` 方法（例如 `parseTwHistoricalRows(JsonNode data, String candidate)`），再對它做表格測試。斷言（a）`close` 為 0 的那列不在結果中；（b）同批正常列**全部保留**（不得整批丟棄或提前 return）。
      - **台股收盤校正入口（`parseTwClosingRow`，279.3a）**：**測試檔已存在**——`external-materials-service/src/test/java/com/steven/assets/externalmaterials/client/PriceFetchClientClosingDateTest.java`，既有 4 個案例的 `close` 全為 `63.1`。補一個 `close: 0.0`（與一個負值）回 `Optional.empty()` 的案例即可，約三行。
      - **寫入端（`upsertHistory`）**：以 `mock(JdbcTemplate.class)` 測（同本模組既有做法，見 `StockSourceQueryHeldCodesTest.java:37`）。以 `close` 為 `0`、負值、`null` 各呼叫一次，斷言（a）回傳 `false`；（b）`verify(jdbc, never()).update(...)`——既不 INSERT 也不 UPDATE；（c）方法未擲例外（`ClosePersister.dumpRedisToDb` 逐檔 try/catch，擲例外會被吃掉、看不出原因）。
      - **Redis 守門（`writeVerifiedClose`，279.3b）**：以 mock 的 `StringRedisTemplate` 斷言非正 price 時**不呼叫** `opsForValue().set(...)`、**不呼叫** `convertAndSend(...)`。
      - **兩種髒列型態都要有樣本**：測試資料須同時涵蓋 `volume=0` 與 `volume>0` 的非正收盤列，證明判準是 `close_price` 而非 `volume`。
      - **changeset 冪等與 CHECK 約束生效不寫成單元測試**（本模組無 DB 測試基礎設施），改由「驗證」段的實機指令驗收，見該段的重跑 changeset 與 `INSERT ... close_price=0` 兩項。

## 驗證

**動工前先確認編號未被搶走：**

```bash
bash scripts/spec-check.sh
```

**建置與測試（Mockito on Java 25 必須用 `-DextraArgLine`，不得用 `-DargLine`——後者會覆蓋掉時區設定）：**

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

**部署（本專案沒有 dev server，「改好」＝ image rebuild ＋ container recreate；JVM 服務一律 `--no-cache`，cached build 會產出不含本次變更的 stale jar）：**

```bash
cp /Users/steven/Project/asset-management-main/.env .
```

```bash
docker compose -p asset-management build --no-cache business-services external-materials-service
```

```bash
docker compose -p asset-management up -d --no-deps --force-recreate business-services external-materials-service
```

> 重建 business-services 會換 IP，BFF 會握著舊 IP 回 500 且約 3 分鐘不自癒（Docker DNS TTL），故一併 restart：

```bash
docker compose -p asset-management restart bff
```

**驗收查詢——髒列已清空（應回 `0`）：**

```bash
docker exec asset-postgres psql -U assets -d assets -tAc "SELECT count(*) FROM stock_price_history WHERE close_price<=0;"
```

**合法資料未被誤刪。判準必須是相對的，不得寫死絕對列數**——台股每個交易日新增約 43 列，撰稿當下（2026-08-02）的 `總列 106,249`／`非正 175`／`priced_zero_vol 9` 到動工日必然已經漂移，照抄絕對值比對會誤判成「刪除沒生效」。**部署前**先記下基準：

```bash
docker exec asset-postgres psql -U assets -d assets -c "SELECT count(*) AS total_before, count(*) FILTER (WHERE close_price<=0) AS dirty_before, count(*) FILTER (WHERE close_price>0 AND volume=0) AS priced_zero_vol_before FROM stock_price_history WHERE market='台股';"
```

**部署後**再查一次同樣三個值，判準為：`dirty_after = 0`、`total_before − total_after = dirty_before`（**刪掉的列數恰等於髒列數，證明沒有誤刪**）、`priced_zero_vol_after = priced_zero_vol_before`（**有價但零成交量的合法列一列都沒少**）：

```bash
docker exec asset-postgres psql -U assets -d assets -c "SELECT count(*) AS total_after, count(*) FILTER (WHERE close_price<=0) AS dirty_after, count(*) FILTER (WHERE close_price>0 AND volume=0) AS priced_zero_vol_after FROM stock_price_history WHERE market='台股';"
```

**CHECK 約束存在且生效（第一條應列出約束、第二條應被 DB 拒絕並回 `ERROR ... violates check constraint`）：**

```bash
docker exec asset-postgres psql -U assets -d assets -c "\d stock_price_history" | grep -i check
```

```bash
docker exec asset-postgres psql -U assets -d assets -c "INSERT INTO stock_price_history (stock_code, market, trading_date, close_price, volume) VALUES ('TESTZERO','台股','1990-01-01',0,0);"
```

**changeset 已套用：**

```bash
docker exec asset-postgres psql -U assets -d assets -c "SELECT id FROM databasechangelog ORDER BY orderexecuted DESC LIMIT 3;"
```

**changeset 冪等（重跑不得失敗）——刪掉紀錄列讓它再跑一次，business-services 須正常啟動、不得 crash loop：**

```bash
docker exec asset-postgres psql -U assets -d assets -c "DELETE FROM databasechangelog WHERE id='v1.85.0-drop-nonpositive-close';"
```

```bash
docker compose -p asset-management restart business-services && sleep 45 && docker compose -p asset-management logs --tail=40 business-services | grep -iE "liquibase|ERROR|started"
```

> 這一步是 `DO $$ … IF NOT EXISTS …` 與 `splitStatements:false` 的實機驗證。**只在本次 changeset 尚未進 main 時可以這樣做**；已 merge 的 changeset 不得用刪紀錄的方式重跑。

**回補不會復發——手動對 006208 觸發一次歷史回補後，髒列數應仍為 `0`：**

```bash
docker exec asset-external-materials-service curl -s -X POST "http://localhost:8080/internal/repair/history?code=006208&market=%E5%8F%B0%E8%82%A1&from=2016-08-01&to=2018-05-01"
```

```bash
docker exec asset-postgres psql -U assets -d assets -tAc "SELECT count(*) FROM stock_price_history WHERE close_price<=0;"
```

> **上式的四個細節都已實測，照抄即可，不要自己改寫：** 容器名是 `asset-external-materials-service`（不是 `asset-external-materials`）；容器內 port 是 **8080**（不是 8081）；參數名是 `code`／`market`／`from`／`to`（`InternalPriceController.java:147-152` 的簽章，用 `stockCode`／`start`／`end` 會回 400）；`market=台股` **必須 percent-encode 成 `%E5%8F%B0%E8%82%A1`**，否則 Tomcat 直接回 400。目的只有一個——**證明重跑回補不會把 175 列寫回來**。

## 完成報告

**完成日期：** 2026-08-02

### 實際改動

| 檔案 | 改動 |
|---|---|
| `db/changelog/changes/v1.85.0-drop-nonpositive-close.sql` | **新增**。先 `DELETE ... WHERE market='台股' AND close_price<=0`，再以 `DO $$ ... IF NOT EXISTS(pg_constraint) ...` 加 `ck_sph_close_price_positive`。changeset 宣告帶 `splitStatements:false` |
| `db.changelog-master.yaml` | 追加 v1.85.0 的 include |
| `PriceFetchClient.java` | `fetchTwHistoricalRange` 的解析迴圈抽成 package-private static `parseTwHistoricalRows(JsonNode, String)` 並擋 `signum() <= 0`；`parseTwClosingRow` 同樣擋非正 |
| `StockSourceQuery.java` | `upsertHistory` 回傳型別 `void → boolean`、非正／null close 拒寫並記 warn；移除 `nz()`（含定義）；補 `@Slf4j` |
| `PriceCacheWriter.java` | `writeVerifiedClose` 非正／null price 不寫 Redis、不推播 |
| `HistoricalBackfillService.java` | 4 個計數器（`:159` `count++`／`:243` `wrote++`／`:277`／`:304`）改為只在回 `true` 時累加 |
| `ClosePersister.java` | 3 個 `ok++`（台／美／英 verify）與 `dumpRedisToDb` 的 `n++` 同上 |
| `DistributionAdjustedPriceService.java` ＋ 其測試 | 「176 筆」註解改為陳述現況 |
| `HistoricalRepairRangeTest.java` | 補 `@BeforeEach` 的 `thenReturn(true)` stub（boolean 預設 false 會讓 5 條筆數斷言歸零） |
| **新增 3 支測試** | `PriceFetchClientTwHistoricalZeroCloseTest`(4)、`StockSourceQueryNonPositiveCloseTest`(6)、`PriceCacheWriterNonPositiveCloseTest`(6)；`PriceFetchClientClosingDateTest` 由 4 增為 6 |

### 與原計畫的偏差

1. **279.3a／279.3b 是審查中新增的，不在初版規劃內。** 初版只規劃 fetch／寫入／DB 三道，第 2 輪審查指出 `parseTwClosingRow` 是同一 FinMind 缺口的第二個入口，且 `ClosePersister` 在 `upsertHistory` 之後仍會呼叫 `PriceCacheWriter.writeVerifiedClose` 寫 Redis——**該路徑不經過 DB**，只擋 DB 的話前端仍會顯示股價 0。守門因此增為五處，並刻意把 Redis 那道放在 `writeVerifiedClose`（三個市場的匯流點），而非逐一改各市場解析分支。
2. **測試改用「抽 static ＋ mock JdbcTemplate」而非原本寫的「餵假 HTTP 回應／查 DB 驗列」。** 第 3 輪審查查出本模組測試依賴只有 `spring-boot-starter-test`（無 H2／Testcontainers／MockWebServer），且 `PriceFetchClient.httpClient` 在建構子自建、無注入點，原寫法做不到。已同步改寫 Requirement 62 的測試 AC。
3. **`spec/tasks/` 下 t223／t225／t265 的「176 筆」刻意不改**，依 `spec/tasks/README.md` 鐵則 3（任務檔凍結在派工當下的事實）。已在本檔與 AC 註明，供後續稽核結案。

### 驗證輸出（實機，2026-08-02）

```
單元測試      external-materials-service 177 / backend 492，皆 BUILD SUCCESS
部署          --no-cache 重建 business-services + external-materials-service，recreate，restart bff
              （jar 內確認含 parseTwHistoricalRows，非 stale）
Liquibase     v1.85.0-drop-nonpositive-close ran successfully in 26ms

刪除前        total 106249 / dirty 175 / priced_zero_vol 9
刪除後        total 106074 / dirty   0 / priced_zero_vol 9
              → 106249 − 106074 = 175 = dirty_before（恰等於髒列數，無誤刪）
              → 全表（含美英股）dirty = 0
CHECK         "ck_sph_close_price_positive" CHECK (close_price > 0::numeric)
CHECK 生效    INSERT close_price=0 → ERROR: violates check constraint
冪等          刪 databasechangelog 紀錄後 restart → ran successfully in 15ms，無 already exists、無 crash loop
回補不復發    POST /internal/repair/history?code=006208&market=%E5%8F%B0%E8%82%A1&from=2016-08-01&to=2016-09-30
              → HTTP 200，重查 dirty = 0
              → 006208 該區間正常列（08-01/02/04/05/08/09/10/12/15/16/18）全在，
                 原髒日期 08-03／08-11／08-17 未被寫回 ← 守門生效且同批正常列未被丟棄
服務          bff / business-services / external-materials-service 皆 healthy
```

> **merge 進 main 後必須從 main 的 worktree 重新 build 一次。** 全機只有一套
> `asset-management-*:latest`，誰最後 build 誰生效；只在本 worktree build 的話，
> 別的 session 一 build 就會把這版洗掉，症狀是「DB 的表與 CHECK 都在，但守門的程式碼退回舊版」。
