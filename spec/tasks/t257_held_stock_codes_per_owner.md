# [t257] 持股抓價清單改為涵蓋每位 owner 的最新快照（修元大金 2885 價格永不更新）

**對應 Requirements:** Requirement 7（市場資料整合——自動取得市場資料以提供即時的資產估值；本任務新增其中「持股抓價清單必須涵蓋每一位 owner 的最新快照」一條驗收項）＋ Requirement 43 修訂（今日交易雷達手動重新整理——本任務**推翻**其中「不得修改 `collectHeldStockCodes` 本身」一句，該句已於 requirements.md 加刪除線並註記）
**前置任務:** 無（t249 已上線並新增了 `collectTwRadarCodes`，本任務把同一個修法套用到 `collectHeldStockCodes`，並推翻 t249 當時「不得修改該方法」的判斷）
**Liquibase changeset:** 無（不動資料庫 schema）

## 背景

### 現在的錯誤行為

使用者在資產快照表單看到 **元大金（2885）顯示 `$63.50 ▲$0.00 (0.00%)`**，但 2026-07-29 的實際收盤是 **62.2**。`$63.50` 是 **2026-07-28** 的收盤價。

根因在 `external-materials-service/src/main/java/com/steven/assets/externalmaterials/service/StockSourceQuery.java` 的 `collectHeldStockCodes(...)`：

```java
Long latestSnapshotId = jdbc.query(
        "SELECT id FROM asset_snapshot ORDER BY snapshot_date DESC LIMIT 1",
        rs -> rs.next() ? rs.getLong(1) : null);
```

**全庫只取一筆快照。** 本系統是多租戶（Requirement 28 Gmail OAuth2 登入與多租戶資料隔離），每位 owner 各有自己的 `asset_snapshot`；且 Requirement 35「每日自動釘定最新快照日期為當日」會把各 owner 的最新快照日期都釘成今天，於是**多位 owner 的最新快照必然同日**，`ORDER BY snapshot_date DESC LIMIT 1` 的 tie-break 由 Postgres 任意決定。

2026-07-29 實測（運行中 DB）：

| snapshot id | owner_user_id | snapshot_date | 該快照的台股持股列數 |
|---|---|---|---|
| 15 | 1 | 2026-07-30 | 35 |
| 18 | 2 | 2026-07-30 | 2 |

Postgres 挑中 **id 18**（owner 2）。於是 `collectHeldStockCodes` 得到的台股集合 ＝「snapshot 18 的 2 檔」∪「`stock_alert` 的台股觀察清單」＝ **18 檔**，owner 1 的持股全部落選。去重後只有 **`2885` 一檔**是「只在 owner 1 持股、又不在觀察清單」——它就是唯一的受害者。

### 連鎖故障（三段，皆已由日誌與 DB 實測佐證）

```text
PricePoller.scheduledTwIntradayUpdate（cron "0 0/2 9-13 * * MON-FRI" Asia/Taipei）跳過 2885
  log 逐輪為「更新台股即時價格 (18 檔)」
  → Redis price:台股:2885 停在前一交易日 FinMind 校正過的值（TTL 24h 內仍活著）
  → 13:32 ClosePersister.dumpTwCloseFromRedis 依 Redis SET price:index:台股（34 檔，仍含 2885）
      把該舊值以 tradingDate=2026-07-29 寫進 stock_price_history
      log：「台股 Redis dump 完成：34 檔」
  → 16:00 ClosePersister.verifyTwCloseWithFinMind 同樣用 collectHeldStockCodes（18 檔）
      2885 不在內 → 假收盤未被校正
      log：「FinMind 校正台股收盤完成：成功覆寫 18 檔，缺漏 0 檔」
  → TTL 到期後 price:台股:2885 整個消失（現況即如此）
  → 前端 $63.50 ▲$0.00 (0.00%)
      price 取自 stock_price_history 的假收盤；
      priceChange／changePercent 因 Redis 無 entry 而為 0
```

DB 佐證（`docker exec asset-postgres psql -U assets -d assets`）：

```
 stock_code | trading_date | close_price | open_price | high_price | low_price |  volume
------------+--------------+-------------+------------+------------+-----------+----------
 2885       | 2026-07-29   |     63.5000 |    64.6000 |    64.7000 |   62.6000 | 36045128
 2885       | 2026-07-28   |     63.5000 |    64.6000 |    64.7000 |   62.6000 | 36045128
```

兩日 O/H/L/C **與 volume** 完全相同。Yahoo（`2885.TW`, `interval=1d`）對照：07-28 為 O64.6 H64.7 L62.6 C63.5（與 DB 07-28 相符），07-29 為 O64.4 H64.7 L60.7、`regularMarketPrice=62.2`。同輪共 **13 檔**的 07-29 與 07-28 完全相同：`006205`／`00642`／`00646`／`00695B`／`00929`／`1616`／`2002`／`2412`／`2606`／`2885`／`7556`／`9933`／`IB01`。

`2885` 在 `stock` 主檔內，所以開機一次性的 `PricePoller.warmCacheOnStartup`（用的是 `collectAllStockCodes`，log「背景補抓 cold cache：台股 34 檔」）涵蓋它——這是 Redis 曾經有該 key 的來源。**缺的是持續刷新與收盤校正，不是首次寫入。**

### 這個修法推翻了什麼

`spec/design.md` 在 Requirement 43 / Task 249 的設計段落原本明文寫著（原句已於 design.md 加刪除線保留為決策記錄，本檔照抄以免實作者誤以為仍然有效）：

> **`collectHeldStockCodes` 本身不得修改**：它服務每 2 分鐘的 `scheduledTwIntradayUpdate` 與 `refreshAll()`，放大範圍會改變背景排程的外部請求量。

`StockSourceQuery.collectTwRadarCodes` 的 javadoc 也有同義的一段（「**刻意不改 `collectHeldStockCodes` 本身**……屬另一個決定」）。

**該判斷經量測後不成立。** 改為 per-owner 後的實測涵蓋數：

| 市場 | 現況 | 改為 per-owner 後 |
|---|---|---|
| 台股 | 18 | **19**（只多 `2885`） |
| 美股 | 9 | 9 |
| 英股 | 3 | 3 |

原因是 `stock_holding` 同一檔股票在同一快照內可有多列（依券商／帳戶分列），owner 1 的 35 列台股持股去重後與觀察清單高度重疊。外部請求量幾乎不變，而不改的代價是落選 owner 的持股完全沒有行情。

### 明確不在本任務範圍內的事

以下三項是同一次診斷發現的、但**不屬於**本任務，不得順手一起改（各自需要自己的 spec 與任務）：

1. **`ClosePersister.dumpRedisToDb` 不檢查 payload 的 `tradingDate` / `closed`**，會把任何 Redis 現值當成當日收盤。同輪另有 `1301`（寫成 55.00、Redis `updatedAt` 為 07-29 10:40、`closed:false`）／`2409`（10:55）／`2882`（11:30）被寫入盤中 tick 當收盤。此缺口 design.md 的 Task 249 段落已記載。
2. **回補既已寫錯的歷史列**（07-29 那 13 筆重複列 ＋ 3 筆盤中值）。
3. **`collectAllStockCodes` 內同樣的 `ORDER BY snapshot_date DESC LIMIT 1`**。它另外 union 了 `stock` 主檔，實測已涵蓋 34 檔（含 2885），本次故障不經由它，暫不動。

使用者已知悉且明示只做本任務範圍。**副作用要講清楚：本任務不會讓畫面上既有的 `$63.50` 立刻變成 `62.2`**——它保證的是 2026-07-30 起 `2885` 進入每 2 分鐘輪詢與 16:00 FinMind 校正，當日之後的價格正確；`stock_price_history` 既有的 07-29 錯誤列仍在，快照日尚無收盤時前端 fallback 到該列就仍會顯示 63.50。既寫錯的歷史列由另一支任務回補。

## 要做什麼

### 257.1 `collectHeldStockCodes` 改為 per-owner 最新快照

- [x] 257.1 修改 `external-materials-service/src/main/java/com/steven/assets/externalmaterials/service/StockSourceQuery.java` 的 `collectHeldStockCodes(Set<String> twCodes, Set<String> usCodes, Set<String> ukCodes)`：

  **刪掉**先查 `latestSnapshotId` 再 `WHERE snapshot_id = ?` 的兩段式查詢，**改為單一查詢**：

  ```sql
  SELECT stock_code, market FROM stock_holding
   WHERE snapshot_id IN (SELECT DISTINCT ON (owner_user_id) id FROM asset_snapshot
                          ORDER BY owner_user_id, snapshot_date DESC, id DESC)
  ```

  - **`ORDER BY` 三個欄位一個都不能少**：`owner_user_id` 是 `DISTINCT ON` 的必要前綴；`snapshot_date DESC` 取最新；`id DESC` 是同 owner 同日多筆時的決定性 tie-break（少了它就退回本 bug 的「任意 tie-break」）。
  - 與 `collectTwRadarCodes`（同檔案，Task 249）用的是**同一個子查詢**，兩者口徑必須一致。
  - **不改變方法簽章、不改變回傳語意**：仍是把結果依 `market` 欄位分流填進呼叫端傳入的三個 `Set`，仍沿用同檔案的 `classify(code, market, twCodes, usCodes, ukCodes)` 私有方法。
  - **`stock_alert` 那一段完全不動**（含 `WHERE NOT (stock_code = '0000' AND market = '台股')` 的大盤排除、以及「watch_stock 表已廢止（v1.22）」的註解）。
  - **JdbcTemplate 多列查詢的 callback 必須寫成 void 區塊** `(java.sql.ResultSet rs) -> { classify(...); }`，**不可寫成 expression lambda**——後者會被 Java 解析成 `ResultSetExtractor` 多載，`rs` 未定位，runtime 才炸。既有兩段程式碼已是正確寫法，改寫時不要退化。
  - 因為不再需要 `latestSnapshotId`，順帶確認沒有留下未使用的區域變數或 import。

- [x] 257.1.1 更新該方法的 javadoc：原文為「取最新快照所有持股代號（盤中 / 盤後皆用同一份）。」，改為說明「**每位 owner 各自**最新快照的持股 ∪ `stock_alert` 觀察清單」，並註明 Task 257 推翻了 Task 249 當時「不得修改本方法」的判斷、附上量測結果（台股 18→19、美股 9→9、英股 3→3）。

- [x] 257.1.2 更新 `collectTwRadarCodes` 的 javadoc：其中「**刻意不改 `collectHeldStockCodes` 本身**：它同時服務每 2 分鐘的 `PricePoller.scheduledTwIntradayUpdate` 與 `refreshAll()`，放大其範圍會改變背景排程對外部 API 的請求量，屬另一個決定。」一段**不要刪除**（它是當時的決策記錄），在其後加註「（Task 257 已推翻：實測改為 per-owner 後台股僅 18→19 檔，請求量幾無變化；兩者現已同口徑。）」。
- [ ] 257.1.3 **（尚未落實，見完成報告偏差 4）** 同一份 javadoc 開頭「為什麼不重用 `collectHeldStockCodes`」那段也要加註「（Task 257 後 `collectHeldStockCodes` 已改為同一個 `DISTINCT ON` 子查詢，本段描述的是 Task 249 當時的狀態）」，否則同一份 javadoc 前後矛盾。**另一處同樣過期的斷言在 `TwRadarRefreshService.java:89-91`**：註解寫「不可改回 `collectHeldStockCodes`：它全庫只取一筆最新快照且同日 tie-break 任意」——前半在 Task 257 後已為假。結論（不可改回）仍成立，但理由句須改為「它不做 SQL 層 `market='台股'` 過濾、也不無條件 `remove("0000")`」。兩處要一起修。

### 257.2 不得順帶更動的東西

- [x] 257.2 `collectAllStockCodes` 的 `ORDER BY snapshot_date DESC LIMIT 1` **維持原狀**（見「明確不在本任務範圍內的事」第 3 項）。
- [x] 257.2.1 `ClosePersister`、`PricePoller`、`IntradayTickRefresher`、`EtfNavPoller` 這 14 個呼叫端**一行都不改**——它們透過修好的收集器自動擴大涵蓋範圍，這正是本修法的重點。
- [x] 257.2.2 **不新增、不修改任何 `@Scheduled` 方法**，故 `SchedulePublicBffController.JOBS` 排程清單不需同步（本任務不造成該頁漂移）。
- [x] 257.2.3 不動資料庫 schema、不新增 Liquibase changeset。
- [x] 257.2.4 不動前端。

### 257.3 單元測試

- [x] 257.3 新增 `external-materials-service/src/test/java/com/steven/assets/externalmaterials/service/StockSourceQueryHeldCodesTest.java`。

  現有測試慣例（照抄同目錄的 `CrawlerExportPathQueryTest`）：以 `mock(JdbcTemplate.class)` 建構受測類、JUnit 5 `@Test`、AssertJ `assertThat`、**測試方法名用繁體中文**。

  至少涵蓋：

  - [x] 257.3.1 **SQL 契約**（as-built 拆成三個測試方法，以 `doAnswer` 攔下 `jdbc.query(String, RowCallbackHandler)` 收集 SQL 字串與 handler，非 `ArgumentCaptor`）：
    - [x] 257.3.1a 持股查詢含 `DISTINCT ON (owner_user_id)`。
    - [x] 257.3.1b tie-break 斷言為**精確字串** `ORDER BY owner_user_id, snapshot_date DESC, id DESC`——格式被釘死是刻意的：沒有它，未來有人把 `id DESC` 拿掉不會被任何測試發現。
    - [x] 257.3.1c **不含**舊寫法 `ORDER BY snapshot_date DESC LIMIT 1`，且 `jdbc.query(String, ResultSetExtractor)` 這個多載**一次都不得被呼叫**（`verify(jdbc, never())`）——用意是釘住兩段式 `latestSnapshotId` 查詢已完全移除。**代價須知**：若日後該方法確有需要新增其他單值查詢（例如加一道 owner 數量的 sanity log），這條會以「舊寫法殘留」的名義誤報失敗；屆時應改為只斷言 SQL 字串不含舊寫法。
  - [x] 257.3.2 **市場分流**：餵入 `(2885, 台股)`、`(VOO, 美股)`、`(VWRA, 英股)` 三列，斷言三個 `Set` 各收到正確的一筆、且互不混入。
  - [x] 257.3.3 **`stock_alert` 仍被聯集且大盤仍被排除**：斷言 `stock_alert` 那條查詢字串仍含 `stock_alert` 與 `0000` 的排除條件，並驗證 alert 來源的代號會併入結果 Set。
  - [x] 257.3.4 **無快照時不擲例外**：持股查詢回空結果時，方法正常返回、`stock_alert` 那段仍照跑（本次改寫移除了 `latestSnapshotId != null` 的守門，等價行為必須由測試釘住）。
  - [x] 257.3.5 **回歸錨點：雷達收集器未被波及**：另驗 `collectTwRadarCodes` 仍發出自己的 `DISTINCT ON (owner_user_id)` 持股查詢並仍排除大盤 `0000`，確保本次改動沒有把 Task 249 的雷達收集器一併改壞。（as-built 已有此測試，原 257.3 未宣稱，補記於此。）

  以 Mockito 模擬 `jdbc.query(String, RowCallbackHandler)` 時，用 `doAnswer` 取出 callback 並餵入 `mock(ResultSet.class)` 驅動 `classify`（`TwRadarRefreshServiceTest` 已有 `doAnswer` 的用法可參考）。

## 驗證

```bash
# 1) 建置 + 單元測試（本機 JVM 為 Java 25、pom 的 java.version 21 只是編譯目標，
#    故 Mockito 仍需 byte-buddy experimental；與 t258 同一口徑）
#    絕不可用 -DargLine —— 那會覆蓋掉 pom 既有的 -Duser.timezone=Asia/Taipei
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml test \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true

# 2) 只跑本任務新增的測試
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -f external-materials-service/pom.xml \
  test -Dtest=StockSourceQueryHeldCodesTest \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true

# 3) 重建並 recreate（JVM service 必須 --no-cache，否則 layer cache 會出 stale jar）
#    從 worktree 跑 compose 前先把主 repo 的 .env 複製進來（env_file 相對 compose 檔解析）
cp /Users/steven/Project/asset-management/.env .
docker compose -p asset-management build --no-cache external-materials-service
docker compose -p asset-management up -d --no-deps --force-recreate external-materials-service

# 4) 確認容器健康
docker ps --filter name=asset-external-materials-service --format '{{.Names}} {{.Status}}'
```

**跑起來真的有這個功能**（下列為本任務的實質驗收，光看測試綠燈不算數）：

```bash
# 4a) 啟動後應看到 warm cache（34 檔）與 self-heal；重點在下一步的盤中輪詢檔數
docker logs asset-external-materials-service --since 5m 2>&1 | grep -E 'cold cache|啟動自癒'

# 4b) 台股開盤時段（週一～五 09:00–13:30 Asia/Taipei）觀察輪詢檔數：
#     修好前是「更新台股即時價格 (18 檔)」，修好後必須是「(19 檔)」
docker logs asset-external-materials-service --since 10m 2>&1 | grep '更新台股即時價格'

# 4c) 盤中或收盤後，Redis 必須出現 2885 的即時價 key（修好前這行回 0）
docker exec asset-redis redis-cli EXISTS 'price:台股:2885'
docker exec asset-redis redis-cli GET 'price:台股:2885'

# 4d) 16:00 FinMind 校正應由 18 檔變 19 檔
docker logs asset-external-materials-service --since 24h 2>&1 | grep 'FinMind 校正台股收盤完成'

# 4e) 校正後 DB 當日列不得再等於前一交易日（此查詢應「不再」列出 2885）
docker exec asset-postgres psql -U assets -d assets -c \
"SELECT a.stock_code FROM stock_price_history a \
   JOIN stock_price_history b ON a.stock_code=b.stock_code \
  WHERE a.trading_date=(SELECT max(trading_date) FROM stock_price_history WHERE market='台股') \
    AND b.trading_date=(SELECT max(trading_date) FROM stock_price_history WHERE market='台股' \
                          AND trading_date < (SELECT max(trading_date) FROM stock_price_history WHERE market='台股')) \
    AND a.close_price=b.close_price AND a.volume IS NOT DISTINCT FROM b.volume \
  ORDER BY 1;"

# 4f) business 端即時價清單應含 2885（修好前回 22 檔且不含）
docker run --rm --network asset-network curlimages/curl:latest -s \
  'http://asset-business-services:8080/api/market-data/prices' \
  -H 'X-User-Id: 1' -H 'X-User-Role: USER' -H 'X-User-Status: ACTIVE' \
  | tr ',' '\n' | grep -c '2885'
```

> **驗證時機的提醒**：4b／4c／4d 需要台股開盤或當日收盤排程跑過才有輸出。若在盤外部署，可先確認 4a 正常、單元測試綠燈，再於下一個交易日回頭核 4b–4f。**不要為了讓 4c 有值而手動 `redis-cli SET`** —— 那正是 Requirement 7 明令禁止的「回寫充數」。

## 完成報告

**狀態：已實作、已部署、已合併 main。** 實作 commit `b975ae75`（與 Task 258 同一 commit），merge commit `3bec71af`。
本段於 2026-07-30 補寫 spec 時回填（原 commit 未回填完成報告，故本檔 13 個 checkbox 當時全未勾選；Task 257＋258 合計 53 個）。

**實際改的檔（2 個）**

| 檔 | 改動 |
|---|---|
| `external-materials-service/.../service/StockSourceQuery.java` | `collectHeldStockCodes` 的兩段式查詢（先 `latestSnapshotId` 再 `WHERE snapshot_id = ?`）改為單一查詢 `WHERE snapshot_id IN (SELECT DISTINCT ON (owner_user_id) id FROM asset_snapshot ORDER BY owner_user_id, snapshot_date DESC, id DESC)`；同步改寫該方法 javadoc；`collectTwRadarCodes` javadoc 加註「Task 257 已推翻」。`collectAllStockCodes` 未動（257.2） |
| `external-materials-service/src/test/.../StockSourceQueryHeldCodesTest.java` | 新增，7 個 `@Test`（繁中方法名）：持股查詢必須取每位owner各自的最新快照／快照tiebreak必須具決定性／不得再出現全庫只取一筆快照的舊寫法／三個市場各自分流且互不混入／觀察清單仍被聯集進來且大盤仍被排除／完全沒有快照時不擲例外且觀察清單仍照跑／雷達收集器仍維持自己的每owner查詢與大盤排除 |

**驗證輸出**（2026-07-30 05:12 CST，皆唯讀查證）

- **抓價清單檔數（4b 的 DB 等價驗證，盤外可查）**：對 `asset-postgres` 跑 per-owner 子查詢 ∪ `stock_alert` → 台股 **19**、美股 **9**、英股 **3**；同一 SQL 換回舊寫法 → 台股 **18**、美股 9、英股 3；`EXCEPT` 差集**只有一列 `2885`**。與本任務宣稱的 18→19／9→9／3→3 完全一致。
- **快照分佈（佐證 tie-break 就是本 bug 情境）**：`(18, owner 2, 2026-07-30)`、`(15, owner 1, 2026-07-30)`、`(9, owner 1, 2026-06-22)`、`(4, owner 1, 2025-12-31)`——兩位 owner 最新快照確實同日，舊寫法的任意 tie-break 會選中 id 18（owner 2，僅 2 筆台股持股）。
- **4c Redis 已有 2885**（修好前 `EXISTS` 回 0）：`EXISTS price:台股:2885` → `1`；`GET` → `price=62.2000`、`previousClose=63.5000`、`priceChange=-1.3000`、`changePercent=-2.047244`、`tradingDate=2026-07-29`、`source=DB-close`、`closed=true`。即前端不再顯示 `$63.50 ▲$0.00 (0.00%)`。
- **部署非 stale jar**（本專案有 cached build 出 stale jar 的前例，故實查）：容器 `asset-external-materials-service` 的 image `sha256:b413014b1157`，image built `2026-07-29T20:47:01Z`（＝台北 07-30 04:47:01，commit 後 92 秒）、container created 04:47:10、`RestartCount=0`；compose label `working_dir=/Users/steven/Project/asset-management-main`（＝從 main 的 worktree 重建，符合共用 stack 規則）。容器內 `unzip -p` + `strings` 確認 `StockSourceQuery.class` 含新的 `DISTINCT ON (owner_user_id)` 常量。**注意舊字串 `SELECT id FROM asset_snapshot ORDER BY snapshot_date DESC LIMIT 1` 仍在 jar 內，這是正確的**——它屬於 `collectAllStockCodes` 與 `collectLatestSnapshotHoldingsWithValue`，依 257.2 刻意保留。**不可**拿「舊字串消失」當回歸判準，那會誘使後人去改 `collectAllStockCodes`、正好違反 257.2。

- **4b 盤中輪詢檔數已驗證**：2026-07-30 台股盤中，`docker logs asset-external-materials-service | grep '更新台股即時價格'` 於 11:54～12:04 每一輪皆為 **`更新台股即時價格 (19 檔)`**（修好前為 18 檔）。本任務的實質驗收至此完成。

**與原計畫的偏差**

1. **257.3.1 as-built 拆成三個測試方法**，且手法是 `doAnswer` 攔下 `jdbc.query(String, RowCallbackHandler)` 收集 SQL，而非任務檔原寫的 `ArgumentCaptor<String>`；另加了一條比 spec 更強的斷言 `verify(jdbc, never()).query(String, ResultSetExtractor)`。已據實改寫 257.3.1 為 a／b／c 三小項並註明該強斷言的代價。
2. **多了一個 spec 未宣稱的測試**（雷達收集器回歸錨點），已補記為 257.3.5。
3. 驗證段步驟 1／2 原註「Mockito on Java 21：本模組不需 byte-buddy experimental 旗標」與 t258 同段落矛盾且與本機實況不符（本機 JVM 為 Java 25），已更正為 `-DextraArgLine=-Dnet.bytebuddy.experimental=true` 並註明不可用 `-DargLine`。
4. **257.1.3 尚未落實，故維持未勾。** 補寫 spec 時發現 `collectTwRadarCodes` javadoc **開頭**那段「為什麼不重用 `collectHeldStockCodes`」仍逐字寫著「後者取的是 `SELECT id FROM asset_snapshot ORDER BY snapshot_date DESC LIMIT 1`——全庫只取一筆」，沒有任何 Task 257 caveat（`grep -ran 'Task 249 當時的狀態'` 零命中）；`b975ae75` 加註的只有第二段（257.1.2）。同一份 javadoc 因此前後矛盾。**另 `TwRadarRefreshService.java:89-91` 的註解也有同一句過期斷言**（「它全庫只取一筆最新快照且同日 tie-break 任意」），兩處要一起修。這需要一次純註解修正，屬 `external-materials-service/**` 寫入、受 SDD 閘門管，故不在本次「純 spec」變更內處理。
5. 完成報告中的 Redis payload 與日誌行數為**時點快照**（2026-07-30 05:12 CST）。之後盤中 tick 已覆寫 `price:台股:2885`（`source` 轉為 `TWSE`）、日誌亦已增長，該兩項無法回溯覆核；DB 與 image 相關的數字則可隨時重查。
