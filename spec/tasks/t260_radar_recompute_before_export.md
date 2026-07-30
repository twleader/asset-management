# [t260] 交易雷達排程產檔前一律先回補行情並重算（修今早 Drive 缺檔）

**對應 Requirements:** Requirement 48（今日交易雷達結果快照落地 Redis 與 Excel 區間匯出——含「追加（Task 231）排程自動匯出到指定伺服器目錄」與本任務新增的「追加（Task 260）產檔前一律由背景重算」）＋ Requirement 43 修訂（今日交易雷達；本任務把 Task 249 已推翻的「排程路徑不得觸發外部**行情**抓取」適用範圍**擴大到排程產檔路徑**）
**前置任務:** 無（t231 已上線提供排程匯出、t249 已上線提供 `PriceQueryService.refreshTradingRadarPrices()`、t244／t245 已上線提供 Drive 上傳；本任務只改「產檔前先做什麼」）
**Liquibase changeset:** 無（不動資料庫 schema）

## 背景

### 現在的錯誤行為

使用者 2026-07-30 報「今早交易雷達檔案沒有匯出至 google drive」。

該 owner（`owner_user_id = 1`）在 `trading_radar_export_time` 設有兩個時間點：**09:10** 與 **11:45**，兩者皆 `enabled`。

`asset-business-services` log：

```
2026-07-30T09:10:00.031+08:00  INFO ... TradingRadarExportScheduleService : 交易雷達排程匯出略過（當日無快照）owner=1 9:10
2026-07-30T11:45:03.230+08:00  INFO ... TradingRadarExportScheduleService : 交易雷達排程匯出成功 owner=1 11:45 → /home/steven/Project/SRPP/data/input/交易雷達_1_20260730.xlsx
```

**09:10 那一輪確實執行了，是「當日無快照」而被依設計略過**，不是排程沒跑、也不是 Drive／rclone 故障（同一上午其他七支排程匯出全部成功、零失敗；log 覆蓋期間唯一的 rclone 失敗是 **2026-07-29** 21:52 的 `alert_triggers_1.json` md5 校驗，發生在事故前一日且與本功能無關）。

Redis `trading-radar:snap:idx:1` 當日（`ZRANGEBYSCORE` 2026-07-30 00:00～23:59）只有三筆：**11:43:11、11:50:04、11:56:02**。09:10 當下今日區間為空。保留窗 90 天／per-owner 上限 5000 筆，7/21 的舊快照都還在，故**不是被修剪或逐出，是根本沒產生**。

Google Drive 檔案時間戳直接佐證：

| 檔案 | Drive `createdTime`（換算台北） | 對應哪一輪 |
|---|---|---|
| `交易雷達_1_20260729.xlsx` | **09:10:02** | 前一日 09:10 那一輪有上傳 |
| `交易雷達_1_20260730.xlsx` | **11:44:38** | 當日 09:10 缺席，11:45 才上傳 |

`trading_radar_export_setting` 當時的狀態列（`docker exec asset-postgres psql -U assets -d assets`）：

```
last_run_at        | 2026-07-30 11:45:00.049726
last_run_status    | 成功：/home/steven/Project/SRPP/data/input/交易雷達_1_20260730.xlsx
gdrive_enabled     | t
gdrive_subpath     | 投資理財/資產管理
gdrive_last_run_at | 2026-07-30 11:45:03.228095
gdrive_last_status | 成功：GDriveOutput:投資理財/資產管理/交易雷達_1_20260730.xlsx（10166 bytes）
```

### 根因

快照的**唯一產生點**是 `backend/src/main/java/com/steven/assets/service/TradingRadarService.java` 的 `get()` 內這一段（現況原文）：

```java
// Requirement 48：每次頁面計算把結果存為 per-owner Redis 快照供匯出（fail-soft、僅登入的 HTTP 請求）。
try {
    if (RequestContextHolder.getRequestAttributes() != null && currentUserContext.hasUser()) {
        snapshotStore.save(currentUserContext.getEffectiveUserId(), response);
    }
} catch (Exception e) {
    log.warn("交易雷達快照寫入失敗（不影響頁面）：{}", e.toString());
}
```

即**只有登入使用者開頁／刷新時才產生快照**，背景不產生。而 `TradingRadarExportScheduleService.writeDailyExport(...)` 查無快照就回 `null`、不寫檔，`syncGdrive(ownerId, null, "當日無交易雷達快照")` 完全不上傳。

**這不是第一次，10 天內至少三次，其中兩次整日缺檔。** 對 Redis 索引與輸出目錄逐日交叉比對（台股交易日以 `twse_index_daily_history` 當日有無日 K 判定）：

| 日期 | 台股 | 當日快照 | `交易雷達_1_*.xlsx` |
|---|---|---|---|
| 2026-07-21（二） | 交易日 | 3 筆（首筆 00:20） | 有檔 |
| 2026-07-22（三） | 交易日 | 3 筆（首筆 01:31） | 有檔 |
| 2026-07-23（四） | 交易日 | **0 筆** | **整日缺檔** |
| 2026-07-24（五） | 交易日 | 3 筆（首筆 00:08） | 有檔 |
| 2026-07-25（六） | 休市 | 1 筆（03:42） | 有檔（新規則下休市日不再產出） |
| 2026-07-26（日） | 休市 | 0 筆 | 無檔 |
| 2026-07-27（一） | 交易日 | 首筆 **13:45** | **整日缺檔**（09:10／11:45 兩輪皆空手） |
| 2026-07-28（二） | 交易日 | 4 筆（首筆 03:05） | 有檔 |
| 2026-07-29（三） | 交易日 | 11 筆（首筆 01:35） | 有檔 |
| 2026-07-30（四） | 交易日 | 首筆 11:43 | 遲到 2.5 小時 |

實查指令（可重跑）：`docker exec asset-redis redis-cli ZCOUNT trading-radar:snap:idx:1 <當日00:00毫秒> <當日23:59毫秒>`；
`docker exec asset-business-services sh -c 'ls -1 /home/steven/Project/SRPP/data/input/ | grep 雷達'`（實際只有 0721／0722／0724／0725／0728／0729／0730 七支，**缺 0723 與 0727**）。

**所以這是「排程隱性依賴使用者當天先開過頁」的設計反覆失效，不是孤例。** 之所以之前沒被發現：多數日子使用者習慣半夜看美股時開過雷達頁，凌晨那筆讓 09:10 有東西可倒。**不得**以「往前數日第一筆快照都落在凌晨、故一向有快照可倒」為由把它當單次意外——7/23（整日 0 筆）與 7/27（首筆 13:45）已否證該說法。

**證據時效**：Redis 當日筆數與「當日第一筆＝11:43:11」為 2026-07-30 調查當下的時點快照，雷達頁開啟時會持續累積，事後無法回溯覆核（載重事實「09:10 當下為 0 筆」已由該時刻的 log 行獨立佐證）。DB 狀態列、Drive createdTime、輸出目錄檔名可隨時重查。

### 正確行為

排程**每次產檔前**都要先回補台股即時行情、再由背景客觀重算一次雷達判斷，然後產檔——不需使用者先開過頁。台股休市日不產檔。

使用者 2026-07-30 明示：「交易雷達應該每日產檔前，根據資料庫資料、搜尋可得資料，客觀判斷出是否該交易，**每次產檔前都要做**」。追問資料來源範圍後，使用者選擇「**回補台股即時行情後再算**」（零 LLM、零新聞爬蟲），並選擇「**休市日不產檔**」。

### 這個修法推翻了什麼

**(a) `spec/requirements.md` Requirement 48 追加（Task 231）的這條 AC**（原文已加刪除線保留為決策記錄，本檔照抄以免實作者誤以為仍有效）：

> **當日零快照時不得寫出空檔**：快照**只在使用者開啟／刷新雷達頁的 HTTP 路徑產生**（背景不產生快照），故使用者當天若在排程時間點前從未開過雷達頁，該 owner 當日區間會**查無快照**。此時**不得寫檔**（避免每天在目錄留下只有表頭的無用檔案，並保留前一版檔案不被覆蓋），改為只把 `last_run_status` 記為「當日尚無快照，未產檔」，且**仍設當日 guard**（避免每 poll 重試整天）。此行為須於前端設定卡明示，讓使用者理解「排程是把你當天看過的雷達倒出來，不是排程自己去算」——與爬蟲「排程自己去抓」的語意不同。

`spec/design.md` 內同義的「**當日零快照時不寫檔**」段落亦已加刪除線。

**(b) Task 249 對推翻範圍的限縮句**（`spec/requirements.md` Requirement 43 修訂內，原文）：

> **推翻的只有「外部行情抓取」這一項，且只限使用者明確按下按鈕的那一條路徑。**

本任務把該推翻的**適用範圍擴大到排程產檔路徑**。該句**不要刪除**（它是 Task 249 當時的決策記錄）。

**仍然完全不變的禁令（不得順手放寬）**：不得注入或呼叫 `MarketAnalysisService`、Anthropic、OpenAI 或任何 LLM／AI client；不得觸發公開資訊（新聞）爬蟲；不新增任何資料表。使用者選的資料來源**明確不含新聞／網路搜尋**。

### 明確不在本任務範圍內的事

- **不回補歷史缺檔**：2026-07-30 那支檔已於 11:45 上傳成功（內容是 11:43 之後的雷達狀態，不是 09:10 的）。09:10 那一列的 `last_run_date` 已是 `2026-07-30`，當日不會補跑。本任務保證的是**下一個交易日起** 09:10 不再依賴人工開頁。
- **不改手動匯出**（前端「匯出 Excel」對話框、`GET /api/trading-radar/export`）。
- **不改 Drive 上傳邏輯**（`GdriveOutputSupport`、`ProcessRcloneClient`）。
- **不改其餘八個匯出頁**的排程。
- **不改 `TradingRadarSnapshotStore` 既有的 `save()` 語意**（HTTP 路徑仍要節流＋去重，那是保護共用 Redis 的機制）。

## 要做什麼

### 260.1 `StockAlertRepository` 新增 owner-scoped 觀察清單查詢

- [ ] 260.1 在 `backend/src/main/java/com/steven/assets/repository/StockAlertRepository.java` 新增：

  ```java
  /**
   * 觀察清單衍生 view 的 owner-scoped 版本（Task 260）：背景產檔重算用。
   * 背景無 request context → {@code TenantFilterAspect} 不啟用 {@code @Filter(ownerFilter)}，
   * 故 owner 條件必須顯式寫在查詢裡；不得改用無 owner 的
   * {@link #findDistinctStockCodeMarket()}（背景會撈到全部租戶的觀察清單）。
   * 結果欄位與排序與無 owner 版本完全一致：[stockCode, market, minDisplayOrder]，依 minDisplayOrder 升冪。
   */
  @Query("SELECT a.stockCode, a.market, MIN(a.displayOrder) as ord " +
          "FROM StockAlert a WHERE a.ownerUserId = :ownerUserId " +
          "GROUP BY a.stockCode, a.market ORDER BY ord ASC")
  List<Object[]> findDistinctStockCodeMarketByOwnerUserId(Long ownerUserId);
  ```

  - `StockAlert` 的欄位名確認為 `ownerUserId`（`backend/.../model/StockAlert.java:31`，型別 `Long`）。
  - **結果形狀必須與 `findDistinctStockCodeMarket()` 完全相同**（`List<Object[]>`、三欄、同排序）——呼叫端 `addTarget(targets, skipped, (String) row[0], (String) row[1], false)` 只取前兩欄，形狀漂移會在 runtime 才炸。
  - 既有的 `findDistinctStockCodeMarket()` **一行都不改**（HTTP 路徑仍在用）。

### 260.2 `TradingRadarService` 新增顯式 owner 的重算入口

- [ ] 260.2 修改 `backend/src/main/java/com/steven/assets/service/TradingRadarService.java`：把 `get()` 的組裝主體抽成
  `private TradingRadarDto.Response assemble(Long ownerId)`，`ownerId == null` 代表「走 request-scoped `ownerFilter`」（HTTP 路徑），非 null 代表「顯式 owner 查詢」（背景路徑）。

  - `get()` 改為 `assemble(null)` ＋**原封不動保留**既有的快照寫入守門段（`RequestContextHolder != null && currentUserContext.hasUser()` → `snapshotStore.save(...)`）與 fail-soft try/catch。**HTTP 路徑的行為必須逐位元不變。**
  - `loadLatestHoldings` 改收 `Long ownerId`：`ownerId == null` 用既有 `snapshotRepo.findLatestWithStocks()`，否則用**既有的** `snapshotRepo.findLatestWithStocksByOwnerUserId(ownerId)`（該方法已存在於 `AssetSnapshotRepository:47`，背景通知已在用，`LEFT JOIN FETCH s.stocks` 故無 `LazyInitializationException` 之虞）。
  - `loadWatchList` 改收 `Long ownerId`：`ownerId == null` 用既有 `alertRepo.findDistinctStockCodeMarket()`，否則用 260.1 新增的 `findDistinctStockCodeMarketByOwnerUserId(ownerId)`。
  - `buildMarket()`、`buildStock(...)`、`addTarget(...)`、`prepareTechnicalData(...)` 及其餘私有方法**一行都不改**——大盤與個股組裝 owner-agnostic（讀 `twse_index_daily_history`／`stock_price_history`／Redis `price:*`，皆為跨租戶共用的市場資料）。
  - `decisions` 的過濾與排序（`TW_MARKET.equals(t.market()) && !TAIEX_CODE.equals(t.code())`、`Comparator.comparing(Target::code)`）與 `Response` 的五個建構參數**維持原樣**，不得改動 `TradingRadarDto.Response` 的欄位——那會破壞 Task 230 已落地的 Redis 快照序列化與 Requirement 48 的區間匯出。

- [ ] 260.2.1 新增公開方法：

  ```java
  /**
   * 背景產檔前的重算（Task 260）：顯式 owner、不觸碰 request-scoped 的 CurrentUserContext，
   * 重算後 append 一筆快照供當日匯出。
   */
  @Transactional(readOnly = true)
  public TradingRadarDto.Response recomputeAndStoreForOwner(long ownerId) {
      TradingRadarDto.Response response = assemble(ownerId);
      snapshotStore.saveRecomputed(ownerId, response);
      return response;
  }
  ```

  - **不得**在此方法內讀 `currentUserContext`（背景無 request context）。
  - 例外不在此吞掉——由呼叫端 `TradingRadarExportScheduleService` 決定降級（見 260.4）。

### 260.3 `TradingRadarSnapshotStore` 新增略過節流與去重的寫入入口

- [ ] 260.3 修改 `backend/src/main/java/com/steven/assets/service/TradingRadarSnapshotStore.java`：把 `save(long ownerId, TradingRadarDto.Response resp)` 的主體抽成
  `private void write(long ownerId, TradingRadarDto.Response resp, boolean enforceThrottleAndDedupe)`，並新增：

  ```java
  /**
   * 背景重算產檔專用（Task 260）：略過節流與去重，其餘（gzip＋Base64、value TTL＝保留窗、
   * 索引 ZSet、兩道 inline 修剪）與 {@link #save} 完全相同。
   *
   * <p><b>為什麼必須略過去重</b>：背景補產若被「與上一筆內容相同即不寫」擋掉（休市日／連假時
   * 內容可能與前一筆逐位元相同），當日就仍然無快照、仍然不產檔——修法自我失效。節流同理。
   * footprint 影響可忽略：每 owner 每時間點每日一筆，對 per-owner 5000 筆硬上限而言是雜訊。
   */
  public void saveRecomputed(long ownerId, TradingRadarDto.Response resp) {
      write(ownerId, resp, false);
  }
  ```

  - `save(...)` 改為 `write(ownerId, resp, true)`，**外部行為逐位元不變**（HTTP 路徑仍節流＋去重）。
  - `enforceThrottleAndDedupe == false` 時**只跳過**「(1) 節流」與「(2) 去重」那兩段 early-return；**去重雜湊 `trading-radar:snap:hash:{ownerId}` 仍要照寫**。

    **理由（別把因果寫反）**：去重的判斷是「新內容雜湊 == 已存雜湊 ⇒ 擋下」（現況 `TradingRadarSnapshotStore.java:94-98`）。所以**寫入新雜湊**的效果是「下一次同內容的 HTTP 寫入會被擋」，**不寫**的效果是「去重基準停在更舊的內容」。兩者都不會弄壞使用者的快照，但寫入才能維持「雜湊 ＝ 最後一次實際寫入的內容」這個可推理的不變式。
    **代價已知並接受**（Requirement 48 追加已列為 AC）：背景那筆同樣進索引 ZSet（節流讀索引最大 score）、同樣更新雜湊，故使用者若在排程時間點後 5 分鐘內開頁，該次可能不再產生新快照；內容相同時亦被去重擋下。這是 Task 260 之前不會發生的**可觀察**行為變化，不影響匯出內容（背景那筆已在當日區間內）。故 260.2／260.3 所稱「HTTP 路徑行為不變」指的是**程式碼路徑與 `save()` 的判斷邏輯**不變，**不是**「使用者開頁一定產生快照」。
  - 「(3) 壓縮寫入」「(4) 索引 + 去重雜湊」「(5) inline 兩道修剪」三段**完全共用同一份程式碼**，不得為背景另寫一套。
  - 整段 fail-soft 的 try/catch（`catch (Exception e) { log.warn("交易雷達快照寫入失敗 owner={}：{}", ...) }`）維持在 `write(...)` 內——即 `saveRecomputed` 亦不上拋。**故呼叫端不能靠例外判斷是否寫入成功**，260.4 的降級一律以「重讀當日區間是否仍為空」為準。

### 260.4 `TradingRadarExportScheduleService`：產檔前重算 ＋ 休市日不產檔 ＋ 一輪一次回補

- [ ] 260.4 修改 `backend/src/main/java/com/steven/assets/service/TradingRadarExportScheduleService.java`。

  新增建構子注入（沿用既有 constructor injection 風格，該類目前是手寫建構子非 `@RequiredArgsConstructor`）：
  `TradingRadarService radarService`、`PriceQueryService priceQueryService`、`MarketDataService marketDataService`。

- [ ] 260.4.1 新增狀態常數（比照既有 `NO_SNAPSHOT_STATUS`，`static final`、package-private 供測試斷言）：

  ```java
  /** 非台股交易日時的狀態文字（休市日不產檔，Task 260）。 */
  static final String NON_TRADING_DAY_STATUS = "非台股交易日，未產檔";
  ```

  既有的 `NO_SNAPSHOT_STATUS = "當日尚無快照，未產檔"` **保留不動**——它仍是「重算失敗且當日確實零快照」時的狀態。

- [ ] 260.4.2 改寫 `private void runDueExports()`。現況原文為：

  ```java
  private void runDueExports() {
      LocalDate today = LocalDate.now(TW_ZONE);
      LocalTime now = LocalTime.now(TW_ZONE);
      for (TradingRadarExportTime t : timeRepo.findAll()) {
          if (!Boolean.TRUE.equals(t.getEnabled())) continue;
          if (today.equals(t.getLastRunDate())) continue;
          if (!now.isBefore(LocalTime.of(t.getRunHour(), t.getRunMinute()))) {
              runScheduled(t, today);
          }
      }
  }
  ```

  改為**先收集到點清單、再一次回補、再逐 owner 重算並產檔**：

  - 先以完全相同的三個條件（`enabled`／`today != lastRunDate`／`now >= LocalTime.of(runHour, runMinute)`）收集 due 清單。**判斷條件一個都不能改**：`now >= 時分`（非「分鐘精確相等」）＋ per-時間點的 `lastRunDate` guard 是 Task 231 刻意的設計，改成相等會在排程執行緒被長工作卡住跨分鐘時整日靜默漏跑。
  - due 為空 → 直接 return（**不得呼叫回補**，否則每分鐘都在打 external）。
  - due 非空且 `!marketDataService.isTradingDay("台股", today)` → 對 due 清單每一列：`recordStatus(ownerId, NON_TRADING_DAY_STATUS)`、**不重算、不回補、不產檔、不呼叫 `syncGdrive`**、`log.info`，並**仍設當日 guard**（`setLastRunDate(today)`／`setUpdatedAt`／`timeRepo.save(t)`），然後 return。
  - due 非空且是交易日 → **先回補一次**（見 260.4.3），再對 due 清單每一列依序：先 `recomputeQuietly(ownerId)`（見 260.4.4），再呼叫**既有的** `runScheduled(t, today)`。
  - `runScheduled(...)` 內部（產檔 → `recordStatus` → `syncGdrive` → finally 設 guard）**一行都不改**。

- [ ] 260.4.3 新增一輪一次的行情回補：

  ```java
  /**
   * 產檔前回補台股即時行情（Task 260）。<b>一輪只回補一次</b>——回補清單是全庫台股標的
   * （跨租戶共用的市場資料），逐 owner 各打一次只是重複打同一份清單。
   *
   * <p><b>不得沿用 TradingRadarRefreshService.refreshAndGet()</b>：其冷卻鍵取自 request-scoped 的
   * CurrentUserContext，背景會落到 "anonymous"，且會與使用者按下「重新整理」互相燒掉冷卻。
   *
   * <p>回補失敗／逾時／BUSY 一律只記 log 後繼續——外部服務不可用不得使當日缺檔，
   * 那是用一個新的失敗模式換掉舊的。開機自癒（ApplicationReadyEvent）時 external 可能尚未就緒，
   * 這條降級路徑即為該情境所需。
   */
  private void refreshPricesQuietly() { ... }
  ```

  - 呼叫 `priceQueryService.refreshTradingRadarPrices()`（回 `JsonNode`；逾時由 Reactor `block(Duration)` 擲 `IllegalStateException`）。
  - `catch (Exception e)` 全吞、只 `log.warn`。**不得**讓任何回補例外中斷後續產檔。
  - 回應內的 `busy`／`skippedPendingClose` 旗標只寫 log，**不改變後續流程**（照樣重算產檔）。

- [ ] 260.4.4 新增重算的降級包裝：

  ```java
  /**
   * 產檔前重算（Task 260）。失敗只記 log——重算是「盡力讓檔更新」，不是產檔的新前提；
   * 後續 writeDailyExport 會回退用當日既有快照產檔，當日確實零快照才不寫檔。
   */
  private void recomputeQuietly(long ownerId) { ... }
  ```

  - 呼叫 `radarService.recomputeAndStoreForOwner(ownerId)`，`catch (Exception e)` 只 `log.warn`。
  - **不得**因單一 owner 重算失敗而跳過同輪其他 owner（沿用 Task 231「單一使用者失敗不影響其他使用者」）。

- [ ] 260.4.5 `writeDailyExport(...)`、`syncGdrive(...)`、`recordStatus(...)`、`resolveDir(...)`、`writeAtomically(...)`、CRUD（`listTimes`／`replaceTimes`／`getSetting`／`saveSetting`）的**函式主體一行都不改**。特別是：

  - `writeDailyExport` 的「當日查無快照 → 回 `null` 不寫檔」**保留**——它現在是「重算也失敗且當日零快照」的降級終點，不再是常態路徑。

- [ ] 260.4.5.1 **`runNow()` 須新增重算**（使用者明示「**每次產檔前都要做**」，run-now 也產檔）：在 `writeDailyExport(...)` 之前先 `refreshPricesQuietly()` ＋ `recomputeQuietly(ownerId)`，其餘（不動當日 guard、回傳落點與大小、`syncGdrive` 回報）**維持不變**。

  - **`runNow()` 不加交易日限制**：它是使用者明確觸發、用途就是驗證落點，**休市日必須仍可用並照樣重算**，否則週末無法驗證部署（本任務的實質驗收即依賴這條，見「驗證」步驟 8）。休市日不產檔只約束**排程**路徑。
  - 既有的使用者可見字串 `NO_SNAPSHOT_STATUS + "（請先開啟一次交易雷達頁產生快照）"`（現況 `TradingRadarExportScheduleService.java:199`）**必須改寫**——run-now 現在會自己重算，叫使用者去開頁已是錯誤指示。改為說明「重算失敗且當日無既有快照」之類的實際原因。

- [ ] 260.4.5.2 **下列 javadoc／註解在本任務後會變成假的，必須一併改**（函式主體不動，但文件不得留下已推翻的斷言——這正是本專案 Task 197 稽核出的 64 處不一致的成因）：

  - `TradingRadarExportScheduleService.java:60-61` `NO_SNAPSHOT_STATUS` 的 javadoc：「當日查無快照時的狀態文字（**快照只在使用者開頁的 HTTP 路徑產生，背景不產生**）。」→ 改為「背景重算失敗且當日確實零快照時的降級終點」。
  - `TradingRadarExportScheduleService.java:294-298` `writeDailyExport` 的 javadoc：同樣寫著「**快照只在使用者開啟／刷新雷達頁的 HTTP 路徑產生**——背景不產生」→ 同上修正。
  - 類別層級 javadoc（`:38-53`）若有同義敘述一併修正。

- [ ] 260.4.6 **不新增、不修改任何 `@Scheduled` 方法**（重算掛在既有 `tick()`／`selfHealOnStartup()` → `runDueExports()` 內），故 `SchedulePublicBffController` 的 `JOBS` **不新增項目**。**但既有那一筆的說明文字必須改，見 260.7。**

### 260.7 排程列表頁的說明文字同步（不新增項目，只改 description）

- [ ] 260.7 修改 `bff/src/main/java/com/steven/assets/bff/schedulelist/SchedulePublicBffController.java` 的 `JOBS` 內「交易雷達匯出」那一筆（現況第 100–102 行）。現況原文：

  ```java
  new ScheduledJobDto(BUSINESS, "交易雷達匯出", "每日匯出排程檢查",
          "每分鐘檢查各使用者設定的多個交易雷達匯出時間點，命中執行時間即把當日 Redis 快照產出 Excel 到指定目錄；當日尚無快照則略過不產檔（Requirement 48）；輸出含 Google Drive 同步（若已啟用）",
          "動態：依「今日交易雷達」頁設定的多個時間點", "0 * * * * *", TPE),
  ```

  第 4 個參數即 `description`（`ScheduledJobDto.java:21`，排程列表頁對使用者顯示的白話說明）。其中「**當日尚無快照則略過不產檔**」在本任務後為假，須改寫為揭露：命中執行時間即**先回補台股即時行情、由背景重算一次雷達並寫入快照**，再把當日快照產出 Excel 到指定目錄；**台股休市日不產檔**。

  - **只改第 4 個參數這個字串**；`BUSINESS`／名稱／`"每日匯出排程檢查"`／觸發描述／cron `"0 * * * * *"`／`TPE` 全部不動。
  - **保留**結尾的「輸出含 Google Drive 同步（若已啟用）」——那是 Requirement 51 已落地的事實。
  - 保留 `（Requirement 48）` 的出處標註。
  - **不得順手改動 `JOBS` 內其他任何一筆**。

  > **為什麼「沒新增 `@Scheduled` 也要改這裡」**：`spec/requirements.md` Requirement 51 有完全同型的先例明文——該需求同樣不新增 `@Scheduled`，卻要求「**八個對應排程的 description 須補**『輸出含 Google Drive 同步（若已啟用）』，避免該頁與實際行為漂移」，上述現況原文結尾那句就是該 AC 被執行的痕跡。「不新增 `@Scheduled` ⇒ `JOBS` 不必動」這個推論在本專案**已被否決過一次**；`spec/tasks/README.md` 的自足性自查也把漏掉排程登錄同步列為「排程列表頁漂移的固定成因」。

### 260.5 前端文案同步（兩處）

- [ ] 260.5 修改 `frontend/src/views/TradingRadarView.vue`。**只改文案字串，不改任何邏輯、不改 `api/index.js`。**

- [ ] 260.5.1 排程卡說明（現況第 290 行附近，`description` 屬性原文）：

  > `設定後即時生效（免重啟），下一分鐘起依新時間執行。清空全部時間點＝不再自動匯出。匯出內容為「當日已產生的雷達快照」；若當天還沒開過本頁，該次排程會略過不產檔。`

  改為揭露新行為：每次排程產檔前會先回補台股行情並重新計算一次雷達，**不需先開本頁**；匯出內容為當日累積的全部快照（含排程自己算出的那幾筆）；**台股休市日不產檔**。

- [ ] 260.5.2 頁首資訊框（現況第 20 行附近，`description` 屬性原文）：

  > `判斷全由本地規則產生，不會送出 Claude、OpenAI 或其他 AI API 請求。按下「重新整理」會先回補一次台股行情再重算；頁面自動更新與其餘操作只讀取既有 PostgreSQL 與 Redis 資料。每次結果快照會寫入 Redis 供匯出。`

  其中「頁面自動更新與其餘操作只讀取既有 PostgreSQL 與 Redis 資料」在排程路徑已不成立，須補述**排程產檔亦會觸發一次台股行情回補**。**必須保留**「判斷全由本地規則產生，不會送出 Claude、OpenAI 或其他 AI API 請求」這句——該禁令本任務完全不動。

### 260.6 單元測試

- [ ] 260.6 修改 `backend/src/test/java/com/steven/assets/service/TradingRadarExportScheduleServiceTest.java`。

  現有慣例（照抄同檔既有寫法）：Mockito `mock(...)` 建構受測類、JUnit 5 `@Test`、AssertJ `assertThat`、**測試方法名用繁體中文**、以 `givenSnapshots(owner)` 預先塞快照、`@TempDir` 當輸出基底。新增的三個 mock（`TradingRadarService`／`PriceQueryService`／`MarketDataService`）須在 `setup()` 內建立，且**預設 `when(marketDataService.isTradingDay(anyString(), any(LocalDate.class))).thenReturn(true)`**（**不可**寫成 `isTradingDay("台股", any())`——Mockito 用了 matcher 就所有參數都要是 matcher，字面值與 `any()` 混用會在錄製時擲 `InvalidUseOfMatchersException`；照抄同目錄 `TradingRadarMarketFreshnessTest.java:94` 的既有寫法）——否則既有 4 條 due 非空的排程測試（`同日多個時間點各自跑一次_guard必須per時間點`／`當日零快照時不寫檔但仍設guard並記狀態`／`單一owner失敗不影響其他owner`／`設定列不存在時recordStatus會建立一列`）會全部因休市日分支而失敗。其餘 6 條不受影響，但理由分兩種：3 條（`今日已跑過的時間點不重跑`／`停用的時間點不跑`／`尚未到執行時間不跑`）仍呼叫 `tick()`，只是 due 清單為空而提前 return；另 3 條（`輸出子路徑跳脫基底目錄被擋`／`重複時分回IllegalArgument而非撞UNIQUE`／`不合法時分回IllegalArgument`）走的是 HTTP CRUD 路徑（`saveSetting`／`replaceTimes`），根本不經過 `runDueExports()`，與本次改動無關。

- [ ] 260.6.1 **既有測試 `當日零快照時不寫檔但仍設guard並記狀態` 須改寫為新行為的回歸錨點**：當日零快照時，排程**仍會產檔**（因為先重算了）。改法：對 `radarService.recomputeAndStoreForOwner(1L)` 用 `doAnswer(inv -> { givenSnapshots(1L); return null; })`，讓「背景重算」在 mock 層面等價於既有的 `givenSnapshots` 塞快照手法，再斷言檔案存在且 `last_run_status` 以「成功：」開頭。**不得只是刪掉該測試**——它是本任務推翻的那條 AC 的唯一守門。

- [ ] 260.6.2 **當日已有快照時仍重算並 append**：預先塞一筆當日快照，跑 `tick()`，`verify(radarService).recomputeAndStoreForOwner(1L)`。這條釘住「不是只在查無快照時才補算」——只補空窗的話早上產出的檔會是使用者凌晨的判斷。
- [ ] 260.6.3 **回補在重算之前、且一輪只一次**：兩位 owner 同一分鐘到點時，`verify(priceQueryService, times(1)).refreshTradingRadarPrices()`，並以 `InOrder` 斷言回補早於第一次 `recomputeAndStoreForOwner`。
- [ ] 260.6.4 **due 為空時完全不呼叫回補**：無任何時間點到點時 `verify(priceQueryService, never()).refreshTradingRadarPrices()`（否則每分鐘都在打 external）。
- [ ] 260.6.5 **回補失敗仍產檔**：`when(priceQueryService.refreshTradingRadarPrices()).thenThrow(new IllegalStateException("Timeout on blocking read"))`，斷言仍呼叫重算、仍產出檔案。
- [ ] 260.6.6 **休市日不產檔且仍設 guard 且不回補、不重算**：`isTradingDay` 回 false 時，斷言無檔案產生、`last_run_status` 等於 `NON_TRADING_DAY_STATUS`、`last_run_date` 已設為今日、`verify(priceQueryService, never()).refreshTradingRadarPrices()`、`verify(radarService, never()).recomputeAndStoreForOwner(anyLong())`。
- [ ] 260.6.7 **重算擲例外時回退用當日既有快照產檔**：`recomputeAndStoreForOwner` `thenThrow`，但當日已有快照 → 斷言仍產出檔案且狀態為「成功：」開頭。
- [ ] 260.6.8 **重算擲例外且當日零快照 → 維持既有不寫檔行為**：斷言無檔案、狀態等於 `NO_SNAPSHOT_STATUS`、且**未呼叫 Drive 上傳**（`syncGdrive` 傳 null 不上傳的既有語意不得回歸）。
- [ ] 260.6.9 **單一 owner 重算失敗不影響其他 owner**：owner 1 的 `recomputeAndStoreForOwner` `thenThrow`、owner 2 正常，斷言 owner 2 仍產檔。

- [ ] 260.6.10 在**既有**的 `backend/src/test/java/com/steven/assets/service/TradingRadarSnapshotStoreTest.java` 內新增下列測試方法（**不新建測試檔**——一個受測類一支測試檔，且該檔已有 Redis mock 與 Response fixture 可沿用）：
  - [ ] 260.6.10a **`saveRecomputed` 不被去重擋下**：先 `save` 一筆，再以**內容相同**（僅 `generatedAt` 不同）的 Response 呼叫 `saveRecomputed`，斷言索引 ZSet 確實多一筆。這條直接對應 260.3 的自我失效風險。
  - [ ] 260.6.10b **`saveRecomputed` 不被節流擋下**：節流窗（5 分鐘）內連續 `save` 再 `saveRecomputed`，斷言後者仍寫入。
  - [ ] 260.6.10c **`saveRecomputed` 仍更新去重雜湊**：`saveRecomputed` 後，以**同內容**呼叫 `save`，斷言該次被去重擋下（即雜湊確實被更新過）。
  - [ ] 260.6.10d **`save` 的節流與去重行為未被本次重構改壞**（回歸錨點）。

- [ ] 260.6.11 新增 `backend/src/test/java/com/steven/assets/service/TradingRadarServiceOwnerScopeTest.java`，斷言**背景重算走 owner-scoped 查詢**：呼叫 `recomputeAndStoreForOwner(7L)` 後
  `verify(snapshotRepo).findLatestWithStocksByOwnerUserId(7L)`、`verify(alertRepo).findDistinctStockCodeMarketByOwnerUserId(7L)`，且
  `verify(snapshotRepo, never()).findLatestWithStocks()`、`verify(alertRepo, never()).findDistinctStockCodeMarket()`、
  `verify(currentUserContext, never()).getEffectiveUserId()`，並 `verify(snapshotStore).saveRecomputed(eq(7L), any())`。
  **這是本任務唯一防止跨租戶污染的測試**——無 owner 版本在背景會撈到全部租戶。

  建構方式照抄同目錄既有的 `TradingRadarMarketFreshnessTest`：`@ExtendWith(MockitoExtension.class)`、`ruleEngine` 用**真實**
  `new TradingRadarRuleEngine()`（純函式無副作用），其餘 14 個建構子依賴全 `@Mock`
  （`indicatorService`／`adjustedPriceService`／`assetClassifier`／`twseRepo`／`priceHistoryRepo`／`dividendHistoryRepo`／
  `priceQueryService`／`snapshotRepo`／`alertRepo`／`stockRepo`／`marketDataService`／`exchangeRateRepo`／`snapshotStore`／
  `currentUserContext`），holdings／watchlist 一律回空以隔離出 owner 路徑本身。測試方法名用繁體中文。

  - [ ] 260.6.11a 另加一條 **HTTP 路徑未被改壞**的回歸錨點：直接呼叫 `get()`（測試無 request context，
    故快照寫入守門不會觸發）並斷言走的是無 owner 版本——`verify(snapshotRepo).findLatestWithStocks()`、
    `verify(alertRepo).findDistinctStockCodeMarket()`、`verify(snapshotStore, never()).saveRecomputed(anyLong(), any())`。
    少了這條，`assemble(null)` 的分支若寫反（HTTP 誤走 owner-scoped 查詢並以 null 當 owner）會 runtime 才炸。

- [ ] 260.6.12 回到 `TradingRadarExportScheduleServiceTest`，新增 **`runNow()` 亦重算**的測試（260.4.5.1 的回歸錨點）：呼叫 `runNow()` 後
  `verify(priceQueryService).refreshTradingRadarPrices()`、`verify(radarService).recomputeAndStoreForOwner(1L)`，且沿用既有的 `givenCurrentUser(1)` 手法設定 owner。另加一條**休市日不受限**：`isTradingDay` 回 false 時 `runNow()` 仍呼叫上述兩者、仍可能產檔（不得因休市日分支而被擋）。

## 驗證

> **`scripts/spec-check.sh` 的預期 BLOCK（實作前）：** 該腳本的 B5 檢查對 `added_lines 'spec/**'` 全文抓取符合
> 「大寫開頭＋`Test` 結尾」樣式的識別字、`sort -u` 去重後逐一比對是否存在對應 `.java`（同一識別字在檔案裡出現
> 幾次都只算一筆，不會因為多拼一次而多產生 BLOCK）。本檔 260.6.11 宣告要**新建** `TradingRadarServiceOwnerScopeTest`，
> 故在實作完成前必然命中一次：
>
> ```
> BLOCK │ spec 提到測試類 TradingRadarServiceOwnerScopeTest，但全樹找不到 …… 宣稱的驗證不存在
> ```
>
> B5 的用意是抓「拿不存在的測試當既成證據」（Task 160 前例），無法區分「將要新建」。**不要為了消除它而
> 先建一支空測試檔**——那等於 code 先於審查，違反 SDD 順序，也把閘門變成形式。實作完成後此 BLOCK 應自動消失；
> **若實作後仍在，代表 260.6.11 沒做**，屆時它就是真陽性。

```bash
# 1) 建置 + 單元測試（本機 JVM 為 Java 25、pom 的 java.version 21 只是編譯目標，
#    故 Mockito 需 byte-buddy experimental。絕不可用 -DargLine —— 那會覆蓋掉
#    pom 既有的 -Duser.timezone=Asia/Taipei，導致 181 個測試 error 且錯誤訊息偽裝成 byte-buddy 問題）
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true

# 2) 只跑本任務相關測試
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -f backend/pom.xml test \
  -Dtest='TradingRadarExportScheduleServiceTest+TradingRadarSnapshotStore*Test+TradingRadarService*Test' \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true

# 3) 前端建置（只改了文案字串，仍須確認 vite build 通過）
/Users/steven/.nvm/versions/node/v22.21.0/bin/node \
  /Users/steven/.nvm/versions/node/v22.21.0/bin/npm --prefix frontend run build

# 4) 重建並 recreate（JVM service 必須 --no-cache，否則 layer cache 會出 stale jar；
#    從 worktree 跑 compose 前先把主 repo 的 .env 複製進來，env_file 相對 compose 檔解析）
cp /Users/steven/Project/asset-management/.env .
docker compose -p asset-management build --no-cache business-services
docker compose -p asset-management up -d --no-deps --force-recreate business-services
docker compose -p asset-management build --no-cache frontend
docker compose -p asset-management up -d --no-deps --force-recreate frontend

# 5) recreate business 會換 IP → BFF 握舊 IP 會回 500 且 ≥3 分鐘不自癒（Docker DNS TTL 600s），必須一起 restart
docker compose -p asset-management restart bff

# 6) 確認容器健康
docker ps --filter name=asset- --format '{{.Names}}\t{{.Status}}'
curl -s http://localhost:8080/actuator/health
```

**跑起來真的有這個功能**（光看測試綠燈不算數）：

```bash
# 7) 驗證部署的不是 stale jar（本專案有 cached build 出 stale jar 的前例）
#    注意：class 檔內的中文常量是 UTF-8 多位元組序列，`strings`（預設 ASCII 模式）會靜默濾掉，
#    誤判為「不存在」——必須用 grep -a 直接在原始位元組上比對，不能先過濾 strings。
docker exec asset-business-services sh -c \
  'unzip -p /app/app.jar BOOT-INF/classes/com/steven/assets/service/TradingRadarExportScheduleService.class | grep -ac "非台股交易日"'
#   期望 ≥ 1（實測部署後為 2）

# 8) 休市日（週末）可驗證的是「重算 + 產檔」本身——透過 run-now（260.4.5.1 已讓它也重算），
#    不受休市日限制。清掉判斷用的當日索引筆數基準值。
BEFORE=$(docker exec asset-redis redis-cli ZCOUNT trading-radar:snap:idx:1 \
  "$(TZ=Asia/Taipei date -j -f '%Y-%m-%d %H:%M:%S' "$(TZ=Asia/Taipei date +%Y-%m-%d) 00:00:00" +%s)000" \
  "$(TZ=Asia/Taipei date +%s)000")
echo "重算前當日索引筆數: $BEFORE"

# 9) 觸發 run-now（POST，無 body）。這條在休市日（週末）也可執行，因為 run-now 不受交易日限制。
docker run --rm --network asset-network curlimages/curl:latest -s -X POST \
  'http://asset-business-services:8080/api/trading-radar/export-schedule/run-now' \
  -H 'X-User-Id: 1' -H 'X-User-Role: USER' -H 'X-User-Status: ACTIVE'

# 10) 索引筆數必須比步驟 8 多一筆（背景重算 append 的那一筆），且 log 出現回補與重算的痕跡
docker exec asset-redis redis-cli ZCOUNT trading-radar:snap:idx:1 \
  "$(TZ=Asia/Taipei date -j -f '%Y-%m-%d %H:%M:%S' "$(TZ=Asia/Taipei date +%Y-%m-%d) 00:00:00" +%s)000" \
  "$(TZ=Asia/Taipei date +%s)000"
docker logs asset-business-services --since 2m 2>&1 | grep -E '交易雷達排程匯出|行情回補'

# 11) 排程路徑（tick／selfHealOnStartup）須另外在交易日驗證：以容器內請求重設一個時間點為
#     「下一分鐘」（POST /export-schedule/times，body 為 {"times":[...]}，時分不得補前導零，
#     Jackson 預設拒絕 ALLOW_NUMERIC_LEADING_ZEROS）
#     ⚠️ 這會覆寫既有的 09:10／11:45 設定，驗證完要在步驟 13 改回來
H=$(TZ=Asia/Taipei date -v+2M +%-H); M=$(TZ=Asia/Taipei date -v+2M +%-M)
docker run --rm --network asset-network curlimages/curl:latest -s -X PUT \
  'http://asset-business-services:8080/api/trading-radar/export-schedule/times' \
  -H 'Content-Type: application/json' \
  -H 'X-User-Id: 1' -H 'X-User-Role: USER' -H 'X-User-Status: ACTIVE' \
  -d "{\"times\":[{\"runHour\":${H},\"runMinute\":${M},\"enabled\":true}]}"

# 12) 等到點後看 log：休市日應為「非台股交易日」（不回補、不重算）；交易日應看到
#     回補 → 重算 → 產檔成功，且 gdrive_last_status 為成功
docker logs asset-business-services --since 5m 2>&1 | grep -E '交易雷達排程匯出|非台股交易日|行情回補'
docker exec asset-postgres psql -U assets -d assets -x -c \
  "SELECT last_run_at, last_run_status, gdrive_last_run_at, gdrive_last_status
     FROM trading_radar_export_setting WHERE owner_user_id = 1;"

# 13) 驗證完把時間點改回 09:10 與 11:45（注意：還原後這兩列的 last_run_date 為空，
#     若還原時已過這兩個時分，會各立即補跑一次——屬預期，非缺陷；不想發生就在 09:10 前執行本驗證）
docker run --rm --network asset-network curlimages/curl:latest -s -X PUT \
  'http://asset-business-services:8080/api/trading-radar/export-schedule/times' \
  -H 'Content-Type: application/json' \
  -H 'X-User-Id: 1' -H 'X-User-Role: USER' -H 'X-User-Status: ACTIVE' \
  -d '{"times":[{"runHour":9,"runMinute":10,"enabled":true},{"runHour":11,"runMinute":45,"enabled":true}]}'
```

> **最終驗收須跨到下一個交易日**：確認 09:10 那一輪在使用者**未開過雷達頁**的情況下即產出
> `交易雷達_1_{YYYYMMDD}.xlsx`、`gdrive_last_status` 為成功、且 Google Drive 上該檔的 `createdTime`
> 落在台北 09:10 前後（本次事故的直接反例是 11:44:38）。
>
> **驗證時不要為了讓步驟 10／12 有值而手動 `redis-cli ZADD` 塞快照**——那會把「排程自己算出來」偽裝成成功。
>
> **一輪只回補一次僅在單次 `runDueExports()` 內成立**：`selfHealOnStartup()` 不經過 `ticking` 旗標
> （與 `tick()` 各自獨立呼叫 `runDueExports()`），故服務重啟瞬間，開機自癒與第一次 tick 理論上可能各觸發一次回補。
> 可接受——回補本身冪等、external 端已有 busy 單一併發保護（見 `refreshTradingRadarPrices` 呼叫鏈），不影響正確性。
>
> 步驟 9／11／13 的 endpoint 已核對為 `TradingRadarController`（`@RequestMapping("/api/trading-radar")`）：
> `POST /export-schedule/run-now`（`:127-129`，無 body）與 `PUT /export-schedule/times`（`:101`，
> body 為 `TradingRadarExportDto.TimesRequest`，即 `{"times": [...]}` 而非裸陣列）。

## 完成報告

**狀態：已實作、已建置、已部署、已通過實機驗證。尚未 commit／merge／push（下一步）。**

### 編號變更說明（t259 → t260）

本任務原編號為 259。實作階段完成 spec 兩輪對抗式審查、開始寫程式後，`scripts/spec-check.sh` 偵測到
**分支點後 origin/main 已獨立合併另一支完全不相關的 Task 259**（`t259_etf_premium_origin_no_reverse_calc.md`，
ETF 折溢價來源標記 `pct_origin`，commit `add59ad0`／`50e0b08d`／`115414d2`／`500c6191`）。兩者為不同工作，
故依 `spec/tasks/README.md` 鐵則 6（編號先查撞號）將本任務全面改號為 **260**：任務檔重新命名、
`spec/requirements.md`／`spec/design.md` 內所有「Task 259」引用、以及 8 支原始碼／測試檔內的
javadoc／註解「（Task 259）」全部改為「（Task 260）」（純文字置換，`grep -c 259` 逐檔驗證歸零）。
此為**機械性改號，不影響任何語意**——兩輪對抗式審查驗證的實際內容（程式邏輯、測試斷言）完全不變，
故未重跑完整審查，僅重新執行 `scripts/spec-check.sh`（歸零）並重新記錄 `spec-review-pass`。

### 實際改的檔（不含 spec/）

| 檔 | 改動 |
|---|---|
| `backend/.../repository/StockAlertRepository.java` | 新增 `findDistinctStockCodeMarketByOwnerUserId(Long)`（260.1），既有 `findDistinctStockCodeMarket()` 未動 |
| `backend/.../service/TradingRadarService.java` | `get()` 組裝主體抽成 `private assemble(Long ownerId)`；`loadLatestHoldings`／`loadWatchList` 改收 `Long ownerId` 分流 owner-scoped／無 owner 查詢；新增 `public recomputeAndStoreForOwner(long ownerId)`（260.2、260.2.1） |
| `backend/.../service/TradingRadarSnapshotStore.java` | `save()` 主體抽成 `private write(long, resp, boolean enforceThrottleAndDedupe)`；新增 `public saveRecomputed(long, resp)`（略過節流／去重檢查但仍寫去重雜湊）（260.3） |
| `backend/.../service/TradingRadarExportScheduleService.java` | 建構子新增 3 個依賴（`TradingRadarService`／`PriceQueryService`／`MarketDataService`，10 參數）；`runDueExports()` 改為先收集 due 清單→休市日分支（設 guard、記 `NON_TRADING_DAY_STATUS`、不回補不重算不上傳）→交易日則一輪回補一次→逐列重算＋既有 `runScheduled`；新增 `refreshPricesQuietly()`／`recomputeQuietly(long)`；`runNow()` 新增回補＋重算（260.4、260.4.1–260.4.6、260.4.5.1）；`NO_SNAPSHOT_STATUS`／`writeDailyExport` 兩處 javadoc 改寫（260.4.5.2） |
| `bff/.../schedulelist/SchedulePublicBffController.java` | `JOBS` 內「交易雷達匯出」一筆的 `description` 字串改寫（260.7），其餘 6 個建構參數與其餘 7 筆 `JOBS` 項目未動 |
| `frontend/src/views/TradingRadarView.vue` | 排程卡說明（第 290 行附近）與頁首資訊框（第 20 行附近）兩處文案字串改寫（260.5.1、260.5.2），AI API 免責句原文保留，`api/index.js` 未動 |
| `backend/.../test/.../TradingRadarExportScheduleServiceTest.java` | 既有 `當日零快照時不寫檔但仍設guard並記狀態` 改寫為回歸錨點；新增 260.6.2–260.6.9、260.6.12 共 10 個測試方法；新增 3 個 `@Mock`；`setup()` 補建構子引數與 `isTradingDay` 預設 stub |
| `backend/.../test/.../TradingRadarSnapshotStoreTest.java` | 新增 260.6.10a/b/c 三個測試方法（`saveRecomputed` 不被去重／節流擋下、仍更新去重雜湊）；260.6.10d 由既有兩條測試（`節流窗內不重複寫`／`內容未變時去重不重複寫`）原樣覆蓋，未新增重複測試 |
| `backend/.../test/.../TradingRadarServiceOwnerScopeTest.java`（新檔） | 260.6.11／260.6.11a：owner-scoped 重算不觸碰 `CurrentUserContext`，以及 HTTP 路徑（`get()`）未被交換成 owner-scoped 查詢的回歸錨點 |
| `backend/.../test/.../TradingExportGdriveTest.java` | **非本任務範圍、但為修復編譯所需**：既有的 `new TradingRadarExportScheduleService(...)` 呼叫因建構子從 7 參數擴為 10 參數而編譯失敗；補 3 個 `@Mock` 並在 `setup()` stub `isTradingDay` 為 `true`（否則該檔既有的兩條 `radar.tick()` 測試會被誤判成休市日而失敗）。未新增任何測試方法 |

### 驗證輸出

**建置與測試**（`mvn -f backend/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true`）：
目標 4 檔合計 **41 個測試、0 失敗、0 錯誤**；全 backend 模組合計 **357 個測試、0 失敗、0 錯誤**（無回歸）。
`bff`／`backend` 皆 `mvn compile` 成功。`spec-check.sh`：`BLOCK: 0 CHECK: 0`。

**部署**（`docker compose -p asset-management build --no-cache business-services frontend bff`
＋ `up -d --no-deps --force-recreate` 三者＋`restart bff`）：三容器皆轉為 `healthy`。
**任務檔本身遺漏了 bff 的 build／recreate 步驟**（原驗證段步驟 4 只寫了 business-services 與
frontend）——`SchedulePublicBffController.java` 確實被改動卻沒被排進部署清單，已在實作時補上，
記錄於此供任務檔後續訂正參考。

**stale jar 檢查踩到一個自己埋的陷阱**：任務檔原驗證步驟 7 用 `unzip -p ... | strings | grep -c "非台股交易日"`，
實測回 `0`（看起來像沒部署成功）。追查後確認是**`strings`（ASCII 模式）靜默濾掉了中文的多位元組 UTF-8
序列**，與部署無關；改用 `grep -ac`（跳過 `strings`、直接在原始位元組比對）後回 `2`，確認 class 檔確實含新程式碼。
已在任務檔步驟 7 訂正該指令並加註原因，避免後續驗證者被誤導。

**實機功能驗證（2026-07-30 22:46–22:50 CST）**：

1. **`run-now` 端到端**：`POST /api/trading-radar/export-schedule/run-now`（owner=1）→ 回應
   `{"path":".../交易雷達_1_20260730.xlsx","size":59529,...,"gdriveStatus":"成功：...(59529 bytes)"}`；
   Redis 當日索引筆數由 19 → 20（背景重算確實 append 了一筆新快照，非沿用舊值）；
   log 出現 `交易雷達排程產檔前行情回補完成：{"performed":true,...,"twStocks":19,...}`，證實真的呼叫了
   `PriceQueryService.refreshTradingRadarPrices()` 且拿到 19 檔台股的回補結果。
2. **實際 `@Scheduled` tick 路徑（本任務要修的真正 bug，非 run-now）**：暫時把一個時間點改設為
   2 分鐘後（`PUT /export-schedule/times`），到點後 log 執行緒為 `[scheduled-2]`（Spring `@Scheduled`
   排程執行緒，非 HTTP handler 執行緒），依序印出「行情回補完成」→「交易雷達排程匯出成功 owner=1 22:49」；
   DB `gdrive_last_status` 為「成功：...(61902 bytes)」。**這證實了本次事故的根本修復**：排程 tick 本身
   （不是使用者按下的按鈕）在無人開過雷達頁的情況下，自己完成了回補＋重算＋產檔＋上傳 Drive 全流程。
3. **還原設定時的「guard 補跑」副作用**（任務檔已預先記載為預期行為，非缺陷）：把時間點改回
   09:10／11:45 後，兩列因 `replaceTimes` 產生新 id 而 `last_run_date` 為空，下一分鐘 tick 各補跑一次，
   log 顯示兩者皆成功、`gdrive` 皆上傳成功，且**一輪只回補一次**（兩個時間點共用同一次
   「行情回補完成」log），與 260.6.3 測試斷言的行為一致——順帶是這條規則的一次額外實機印證。
4. **BFF `SchedulePublicBffController` 260.7 的即時驗證受阻**：`/api/bff/schedule-list` 需要真實
   Google OAuth session（`SecurityConfig.java` 僅 `permitAll` `/actuator/health`／`/actuator/info`），
   business-services 慣用的 `X-User-*` header 信任邊界不適用於 BFF 層，故無法比照其餘驗證免走登入。
   依安全規範不代為執行 OAuth 登入。改以**原始碼比對**替代：已逐字核對 `SchedulePublicBffController.java`
   現況，確認只有「交易雷達匯出」那一筆的第 4 個建構參數字串被改，其餘 `JOBS` 項目與該筆其餘 5 個
   參數皆未動；`bff` 模組 `mvn compile` 成功；容器重建後 `docker inspect` 確認為新 image（見上）。
5. **前端 `TradingRadarView.vue` 文案的瀏覽器可視驗證同樣受阻於 OAuth**（`http://localhost/trading-radar`
   導向 `accounts.google.com` 要求登入 `shi.chihung@gmail.com`，未代為執行）。改以：`vite build` 於
   Docker image 建置時成功（確認樣板語法無誤）＋直接讀取檔案逐字核對 260.5.1／260.5.2 要求的前後文字，
   確認 AI API 免責句原文保留、只多了規定要補的那句。

**已恢復環境**：驗證用的臨時排程時間點已改回原始的 09:10／11:45；未手動塞任何 Redis 快照或修改任何非
本任務範圍的資料列。

### 與原計畫的偏差

1. **任務編號 259 → 260**（見上「編號變更說明」）。
2. **驗證段補了 bff 的 build／recreate**——原任務檔步驟 4 遺漏，已在執行時補上，未回頭改動任務檔的
   驗證段落文字（僅在此完成報告記錄，供後續讀者知悉這是任務檔本身的既有缺口，不是實作偏差）。
3. **驗證步驟 7 的 stale-jar 指令已訂正**（`strings` → `grep -ac`），是任務檔本身的錯誤而非實作偏差，
   已直接修正該段文字（見上）。
4. **259.6.10d 未新增獨立測試方法**——任務檔本身已預期「這條可能只是既有測試的重申」，實際確認
   既有的 `節流窗內不重複寫`／`內容未變時去重不重複寫` 兩條測試完全覆蓋「`save()` 行為未被本次重構改壞」
   這個回歸錨點需求，故未新增重複測試，改為在完成報告說明。
5. **BFF 與前端的即時（瀏覽器／API）驗證由原始碼比對＋建置成功替代**——OAuth 登入牆為本專案既有邊界，
   不在本任務可繞過範圍內；已在上方逐項記載替代驗證方式與其涵蓋範圍。
6. **背景測試代理人兩度因 API 內容過濾政策中止**（`API Error: Output blocked by content filtering policy`），
   原因不明（非本任務內容觸發，其餘六支代理人讀取同一份任務檔與程式碼皆正常完成）。改由主 agent 直接
   撰寫全部 260.6 系列測試與兩個既有測試檔的建構子修復，未再嘗試委派。

### 尚待進行

- **跨到下一個交易日的最終驗收**（任務檔原文要求）：確認 09:10 那一輪在使用者當天完全未開過雷達頁的
  情況下自然到點執行，且 Google Drive 上該檔 `createdTime` 落在台北 09:10 前後——本次完成報告的驗證是
  「人為觸發後立即到點」，還不是「隔日自然運作」的獨立佐證，留待下一個交易日回頭核對。
- **休市日（週末）的排程分支** 已由單元測試（260.6.6）完整覆蓋並通過，但未在真實休市日對運行中的
  容器做一次實機驗證（今日為交易日，無法在當下製造休市日情境）；下一個週末可比照上方 `run-now` 的
  手法對排程時間點做一次真實觀察。
- commit → 兩段式 merge → push（下一步，依使用者指示執行）。
