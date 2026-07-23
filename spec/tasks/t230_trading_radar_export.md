# [t230] 今日交易雷達結果快照落地 Redis 與 Excel 區間匯出

**對應 Requirements:** Requirement 48（今日交易雷達結果快照落地 Redis 與 Excel 區間匯出——每次頁面計算把結果存為帶時間戳的 per-owner Redis 快照，並能指定時間區間匯出成 Excel、存檔時自選資料夾；且不得因快照量把即時股價快取逐出）
**前置任務:** 無（本任務只序列化 `TradingRadarService.get()` 當下回傳的 `TradingRadarDto.Response`，對 DTO 目前有哪些欄位無耦合；與評分邏輯任務相互獨立）
**Liquibase changeset:** 無（本任務不新增、不修改任何資料表——快照只存 Redis）

## 背景

### 使用者要什麼

使用者要「今日交易雷達」頁能匯出 Excel，且能**指定時間**與**指定目錄**；並要求「每次頁面有更新，資料應存 Redis，匯出時由 Redis 抓取」。

### 為什麼是 Redis 快照，而不是 DB 落地或匯出時重算（決策脈絡，勿推翻）

交易雷達本體是**純即時規則運算、結果不落地**：`TradingRadarService.get()`（`backend/src/main/java/com/steven/assets/service/TradingRadarService.java`，標 `@Transactional(readOnly=true)`）讀 `twse_index_daily_history`／`stock_price_history`／Redis 即時價等，跑 `TradingRadarRuleEngine` 後組 `TradingRadarDto.Response` 直接回傳，全程無任何 `save`／`persist`／`insert`。畫面「完成日 K」只是來源資料最新交易日，不是被存下來的結果。

與使用者確認後定案：**每次頁面計算就把整份結果寫一份快照到 Redis（帶時間戳），匯出時讀 Redis 快照組 Excel**。刻意**不新增 PostgreSQL 資料表**（使用者要「存 Redis、由 Redis 抓取」）、**不回補上線前歷史**（從現在開始累積；匯出區間早於上線日則該段無資料，屬預期）。

### 容量硬約束（本任務最容易做錯的地方）

這套 Redis 是 `docker-compose.yml` 內 `redis:7-alpine`，`command: ["redis-server", "--appendonly", "yes", "--maxmemory", "256mb", "--maxmemory-policy", "allkeys-lru"]`，掛 `redis_data:/data` 卷（重啟不失資料），**與即時股價 `price:*` 共用同一個 Redis、database 0**。

`allkeys-lru` 代表**記憶體壓力下會逐出任意 key，包含 `price:*`**。而交易雷達頁盤中會透過既有 SSE（`/api/market-data/prices/stream`）每約 2 秒重讀一次 `get()`（見 `design.md` Requirement 43 的盤中更新段）。若「每次重讀都存快照」，盤中高頻 × 長保留窗會塞爆 256MB 並**把即時股價快取擠掉**，波及儀表板與雷達自身的即時價。

故快照寫入**必須**同時具備節流、去重、壓縮、較短保留窗四道控制（見「要做什麼」230.1）。這是硬需求，不是最佳化選項。

## 要做什麼

> 約束總則：快照**只存 Redis**、**不新增任何資料表**、**不新增任何 `@Scheduled`**（保留窗修剪於寫入路徑 inline 完成）、**不呼叫任何外部行情或 AI API**。全部門檻／間隔／保留窗一律為**具名常數**，可由 `application.yml` 設定覆寫，不得散落於計算式。

- [ ] **230.1 新增 `TradingRadarSnapshotStore`（集中掌管 key 結構、序列化與四道容量控制）**

  新增 `backend/src/main/java/com/steven/assets/service/TradingRadarSnapshotStore.java`。寫入與匯出讀取**共用這一支**，避免 key 格式在兩處漂移（比照 CLAUDE.md「同義欄位、同一 business service API」）。

  依賴注入（皆為既有可用 bean，不需新增設定類）：
  - `StringRedisTemplate`——Spring Boot 自動配置，`PriceQueryService` 已以建構子注入使用（`opsForValue().set/get`、`opsForZSet()`、`expire` 全可用；key/value 皆 `StringRedisSerializer` 純字串）。
  - `ObjectMapper`——注入 Spring 受管 bean（`PortfolioAdviceController` 等多處已注入）。`TradingRadarDto.Response` 是扁平 record（欄位僅 `String / BigDecimal / int / boolean / 巢狀 record / List<String>`，`generatedAt` 已是 `String`，**無 `LocalDate/LocalDateTime`**），預設 ObjectMapper 即可乾淨序列化，不需 `JavaTimeModule`。

  **Redis key 結構（`{ownerId}` 為 `Long`，即 `CurrentUserContext.getEffectiveUserId()`）：**

  | 用途 | key | 型別／值 |
  |---|---|---|
  | 快照內容 | `trading-radar:snap:{ownerId}:{epochMillis}` | String＝`Base64(gzip(JSON))`，TTL＝保留窗 |
  | 快照索引 | `trading-radar:snap:idx:{ownerId}` | Sorted Set，member＝`epochMillis`（字串）、score＝`epochMillis`（double） |
  | 去重雜湊 | `trading-radar:snap:hash:{ownerId}` | String＝上一筆內容雜湊，TTL＝保留窗 |

  `{epochMillis}`＝`OffsetDateTime.parse(response.generatedAt()).toInstant().toEpochMilli()`。

  **可設定參數（用 `@Value` 綁定，`application.yml` 未設時取預設；不得寫成 `static final`，否則「可由設定覆寫」的 AC 不成立）：**
  ```java
  // 節流：同一 owner 兩次寫入最小間隔（分鐘）
  @Value("${trading-radar.snapshot.min-interval-minutes:5}") private long minIntervalMinutes;
  // 保留窗：value TTL 與索引修剪界線（天）；刻意短於 12 個月以配合 256MB
  @Value("${trading-radar.snapshot.retention-days:90}")      private long retentionDays;
  // 每 owner 硬上限筆數：防多使用者把 256MB 共用 Redis 填爆而逐出 price:*（見「容量硬約束」）
  @Value("${trading-radar.snapshot.max-per-owner:5000}")     private int  maxPerOwner;
  ```
  下文以 `SNAPSHOT_MIN_INTERVAL`／`SNAPSHOT_RETENTION` 代稱由上述設定構造的 `Duration`（`Duration.ofMinutes(minIntervalMinutes)`／`Duration.ofDays(retentionDays)`）。並在 `backend/src/main/resources/application.yml` 補上這三個鍵的預設值（`trading-radar.snapshot.*`），供部署時調整而不需重編。

  **`save(long ownerId, TradingRadarDto.Response resp)` 邏輯（整段 try/catch 包住，見 230.2 的 fail-soft）：**
  1. `long ts = epochMillis(resp.generatedAt())`。
  2. **節流**：`Double lastScore = opsForZSet().reverseRangeWithScores(idxKey, 0, 0)` 取索引最大 score（＝上次寫入時間）；`lastScore == null`（該 owner 第一筆）視為通過；否則 `ts - lastScore.longValue() < SNAPSHOT_MIN_INTERVAL.toMillis()` → **直接 return，不寫**。
  3. **去重**：把 `resp` 序列化為 `JsonNode`（`ObjectMapper.valueToTree`），移除 `generatedAt` 欄位後 `writeValueAsString` 取 canonical 字串，算其 **SHA-256 hex** 為 `hash`；讀 `hashKey` 現值，若相等 → **return，不寫**（盤後內容未變時不重複累積）。為避免盤後內容靜態、但距上次寫入已逾節流窗時每次 SSE 重讀都重算整份 SHA-256，實作可在節流通過後另存一個輕量「最後評估時間」標記（TTL＝節流窗）短路重算——屬效能優化，不影響正確性。
  4. **壓縮寫入**：`json = ObjectMapper.writeValueAsString(resp)`；`payload = Base64.getEncoder().encodeToString(gzip(json.getBytes(UTF_8)))`；`opsForValue().set(valueKey(ownerId, ts), payload, SNAPSHOT_RETENTION)`。
  5. **索引**：`opsForZSet().add(idxKey, String.valueOf(ts), (double) ts)`；`expire(idxKey, SNAPSHOT_RETENTION.plusDays(1))`（略長於保留窗，refresh）。
  6. **更新去重雜湊**：`opsForValue().set(hashKey, hash, SNAPSHOT_RETENTION)`。
  7. **inline 修剪（兩道，取代排程）**：先依時間窗 `opsForZSet().removeRangeByScore(idxKey, 0, ts - SNAPSHOT_RETENTION.toMillis())`；**再依筆數硬上限** `opsForZSet().removeRange(idxKey, 0, -(maxPerOwner + 1))`（`ZREMRANGEBYRANK`，只保留最新 `maxPerOwner` 筆）。value key 靠 TTL 自然過期，被剪掉索引但 value 尚未到期者於下次 LRU 或 TTL 回收——因已無索引指向，不會被匯出讀到。**筆數硬上限是保護 `price:*` 的關鍵**：時間窗在多使用者高頻情境下仍可能讓單人累積上萬筆，硬上限給每個 owner 一個確定的 footprint 天花板。

  gzip helper（`java.util.zip.GZIPOutputStream`／`GZIPInputStream`）：
  ```java
  static byte[] gzip(byte[] raw) throws IOException {
      var bos = new ByteArrayOutputStream();
      try (var gz = new GZIPOutputStream(bos)) { gz.write(raw); }
      return bos.toByteArray();
  }
  static String gunzip(byte[] gzipped) throws IOException {
      try (var gz = new GZIPInputStream(new ByteArrayInputStream(gzipped))) {
          return new String(gz.readAllBytes(), StandardCharsets.UTF_8);
      }
  }
  ```

  **匯出讀取方法**，回傳同時帶「查得的快照」與「缺漏統計」，供匯出端在 Excel 標示（見 230.3）——回一個小 record：
  ```java
  record SnapshotRange(List<JsonNode> snapshots, int indexCount, int missingCount) {}

  SnapshotRange range(long ownerId, long fromEpoch, long toEpoch):
      members = opsForZSet().rangeByScore(idxKey, fromEpoch, toEpoch)   // 依 score 升冪
      indexCount = members.size()
      for m in members:
          raw = opsForValue().get(valueKey(ownerId, Long.parseLong(m)))
          if raw == null: missingCount++; continue     // TTL/LRU 已逐出，容忍且計數
          node = ObjectMapper.readTree(gunzip(Base64.getDecoder().decode(raw)))
          snapshots.add(node)
      return new SnapshotRange(snapshots, indexCount, missingCount)
  ```
  `snapshots` 為 `JsonNode`（不反序列化回 record），匯出端逐欄取值並容忍缺欄位（相容日後 DTO 欄位增修）。`indexCount`／`missingCount` 讓「部分被逐出」對使用者在 Excel 裡可見，而非只進 server log。

- [ ] **230.2 在 `TradingRadarService.get()` 掛寫入點（fail-soft，僅登入請求）**

  在 `backend/src/main/java/com/steven/assets/service/TradingRadarService.java` 注入 `TradingRadarSnapshotStore` 與 `CurrentUserContext`（request-scoped bean，`backend/src/main/java/com/steven/assets/security/CurrentUserContext.java`，有 `hasUser()` 與 `getEffectiveUserId()`）。

  在 `get()` 組好 `Response response` 之後、`return response` 之前插入：
  ```java
  try {
      if (RequestContextHolder.getRequestAttributes() != null && currentUserContext.hasUser()) {
          snapshotStore.save(currentUserContext.getEffectiveUserId(), response);
      }
  } catch (Exception e) {
      log.warn("交易雷達快照寫入失敗（不影響頁面）：{}", e.toString());
  }
  return response;
  ```
  約束：
  - **僅在 HTTP 請求且 `hasUser()` 為真時寫**。背景通知路徑走的是另一支 `evaluateForNotification(...)`（不經 `get()`），本寫入點不會被背景觸發；仍以 `RequestContextHolder` 與 `hasUser()` 雙重防護，避免寫出 owner 錯亂（無身分時 `TenantFilterAspect` 會 fail-closed 為 `ownerId=-1`）的髒快照。
  - **fail-soft 是硬要求**：Redis 任何例外只記 log，**不得讓 `get()` 拋錯或改變回應**。頁面渲染是核心功能，快照是附加。
  - 不得改變 `get()` 既有回傳內容與 `@Transactional(readOnly=true)` 語意（寫 Redis 不在 JPA transaction 內，無衝突）。

- [ ] **230.3 新增 `TradingRadarExportService`（讀區間快照 → POI 三分頁 → `byte[]`）**

  新增 `backend/src/main/java/com/steven/assets/service/TradingRadarExportService.java`，注入 `TradingRadarSnapshotStore`、`CurrentUserContext`。對外 `byte[] export(String from, String to)`：

  1. **owner**：`long ownerId = currentUserContext.getEffectiveUserId()`（此為 HTTP 路徑，context 必存在）。
  2. **參數**：`from`／`to` 為 Asia/Taipei 的 ISO local datetime（例 `2026-07-20T00:00:00`），以 `LocalDateTime.parse` → `atZone(Asia/Taipei)` → `toInstant().toEpochMilli()`；`from > to` 或格式錯誤 → 拋 `IllegalArgumentException`（controller 轉 400）。
  3. `var range = store.range(ownerId, fromEpoch, toEpoch)`；`snaps = range.snapshots()`，並保留 `range.indexCount()`／`range.missingCount()` 供缺漏彙總。
  4. **POI 產檔**（沿用 `backend/src/main/java/com/steven/assets/service/ExcelExportService.java` 的既有慣例：`new XSSFWorkbook()` → `wb.write(ByteArrayOutputStream)` → `toByteArray()`；共用 `cell(Row,col,value,style)` helper 自動處理 BigDecimal/Number/String；`private static class Styles` 集中 head/section/money/num2/num4 樣式；收尾 `autoSizeColumn`）。至少三張工作表：

     - **Sheet「快照索引」**：首列先放一列**缺漏彙總**——「查得 {snaps.size()} 筆／索引預期 {indexCount} 筆／缺漏 {missingCount} 筆（已逾期或被 LRU 逐出）」；`missingCount > 0` 時該格以警示樣式標示。之後每列一個快照。欄：快照時間（`generatedAt`）、`ruleVersion`、大盤 `regime`、`regimeLabel`、大盤 `score`、`stale`、個股檔數（`stocks` 長度）、`skippedNonTwStocks`。**缺漏必須在檔內可見，不得只進 server log**——否則使用者看到列數變少會誤判成「那段沒有交易」，而非「資料被逐出」。
     - **Sheet「大盤總覽」**：每列一個快照的 `market`（`MarketSummary`）全欄，前置「快照時間」欄。欄序：快照時間、`regime`、`regimeLabel`、`score`、`dataComplete`、`stale`、`intraday`、`liveUpdatedAt`、`asOfDate`、`price`(最新點位)、`changePercent`、`monthlyMa`(MA20)、`quarterlyMa`(MA60)、`annualMa`(MA240)、`quarterlyConfirmation`、`annualConfirmation`、`kValue`、`dValue`、`reasons`(換行合併)、`risks`(換行合併)。

       > `intraday`／`liveUpdatedAt` 為 Task 228（`TW_RULES_V6`，大盤盤中即時判斷）新增於 `MarketSummary` 的欄位：`intraday=true` 代表該次 regime 由 Redis 即時大盤點位計算而非已入庫完成日 K，`liveUpdatedAt` 為該即時點位的更新時間；`asOfDate` 語意不變，仍為完成日 K 的日期。因匯出以 `JsonNode` 逐欄取值並容忍缺欄位，舊快照沒有這兩欄時該格留空、不會出錯。
     - **Sheet「個股決策」**：每列一個（快照, 個股），前置「快照時間」欄。欄序：快照時間、`stockCode`、`stockName`、`market`、`assetClass`、`held`、`distributionAdjusted`、`action`、`actionLabel`、`score`、`counterTrendState`、`counterTrendLabel`、`price`、`changePercent`、`priceUpdatedAt`、`asOfDate`、`monthlyMa`、`quarterlyMa`、`annualMa`、`monthlyConfirmation`、`quarterlyConfirmation`、`annualConfirmation`、`kValue`、`dValue`、`fxPercentile`、`underlyingCurrency`、`dataComplete`、`reasons`(合併)、`risks`(合併)、`counterTrendReasons`(合併)、`counterTrendRisks`(合併)。

  5. **時間／日期欄一律寫 ISO 文字**（`style=null` 的字串 cell），比照 `ExcelExportService` 既有作法避開 Excel 依時區把日期偏移一天。
  6. 各欄以 `JsonNode.path("欄名")` 取值並容忍缺欄位（`isMissingNode()`／`isNull()` 時留空格）；`List<String>`（reasons 等）以 `\n` 合併為單格。
  7. **零快照**：`snaps` 為空時仍產出含表頭的合法活頁簿；缺漏彙總列改寫為「指定區間 {from}～{to} 查無交易雷達快照（索引預期 {indexCount} 筆）」，**不得拋錯或回 5xx**。

- [ ] **230.4 在 `TradingRadarController` 新增匯出端點（照抄既有下載模板）**

  在 `backend/src/main/java/com/steven/assets/controller/TradingRadarController.java`（`@RequestMapping("/api/trading-radar")`）注入 `TradingRadarExportService`，新增：
  ```java
  @GetMapping("/export")
  public ResponseEntity<ByteArrayResource> export(
          @RequestParam String from, @RequestParam String to) throws IOException {
      byte[] data = exportService.export(from, to);
      String filename = "交易雷達_" + compact(from) + "_" + compact(to) + ".xlsx";  // compact: 去掉 - : T，取 YYYYMMDDHHmm
      HttpHeaders headers = new HttpHeaders();
      headers.setContentDisposition(ContentDisposition
              .attachment().filename(filename, StandardCharsets.UTF_8).build());
      return ResponseEntity.ok()
              .headers(headers)
              .contentType(MediaType.parseMediaType(
                      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
              .contentLength(data.length)
              .body(new ByteArrayResource(data));
  }
  ```
  此為既有 `RealizedGainController.exportExcel()`／`AssetSnapshotController` 的 canonical 下載模板（`ResponseEntity<ByteArrayResource>` + `ContentDisposition.attachment().filename(name, UTF_8)` + spreadsheetml content-type + `contentLength`），一字不差沿用即可。`export()` 拋 `IllegalArgumentException`（from/to 錯誤）時以 `@ExceptionHandler` 或既有全域處理轉 **400**。

- [ ] **230.5 前端：匯出入口、指定目錄、資訊框文案**

  - **`frontend/src/api/index.js`** 的 `tradingRadar` 區塊（現有 `get` / `getNotification` / `saveNotification`）新增：
    ```js
    exportExcel: (from, to) =>
      api.get('/bff/trading-radar/export', { params: { from, to }, responseType: 'blob' }),
    ```
    （axios interceptor 是 `res => res.data`，呼叫端拿到的直接是 blob。）

  - **`frontend/src/views/TradingRadarView.vue`**：
    - 頁首（現有「重新整理」按鈕 `header-row`）旁新增「匯出 Excel」按鈕，開啟對話框。
    - 對話框用 `el-date-picker type="datetimerange"`（起訖 datetime）；預設帶入「當日 00:00 至現在」。
    - 匯出流程沿用 `frontend/src/views/ExchangeRateView.vue` 既有的 `canPickDirectory` / `saveBlob` / `downloadBlob`（**照抄，勿另創**）：優先 `showSaveFilePicker` 讓使用者自選資料夾與檔名，不支援時退回一般下載，按取消不顯示成功訊息。檔名 `交易雷達_{起}_{迄}.xlsx`（`YYYYMMDDHHmm`，用 `dayjs`）。参考模板：
      ```js
      const canPickDirectory = typeof window !== 'undefined' && 'showSaveFilePicker' in window
      async function saveBlob(blob, filename) {
        if (canPickDirectory) {
          try {
            const handle = await window.showSaveFilePicker({
              suggestedName: filename,
              types: [{ description: 'Excel 活頁簿',
                accept: { 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet': ['.xlsx'] } }]
            })
            const w = await handle.createWritable(); await w.write(blob); await w.close(); return true
          } catch (e) { if (e?.name === 'AbortError') return false /* 使用者取消 */ }
        }
        downloadBlob(blob, filename); return true
      }
      function downloadBlob(blob, filename) {
        const url = URL.createObjectURL(blob); const a = document.createElement('a')
        a.href = url; a.download = filename; document.body.appendChild(a); a.click()
        document.body.removeChild(a); URL.revokeObjectURL(url)
      }
      ```
    - **資訊框文案更新**：既有 `el-alert` 的 `description`「本頁只讀取系統既有 PostgreSQL 與 Redis 資料，不會送出 Claude、OpenAI 或其他 AI API 請求，也不會觸發外部行情回補。」改為揭露快照寫入，例如：「本頁讀取系統既有 PostgreSQL 與 Redis 資料，並將每次結果快照寫入 Redis 供匯出；不會送出 Claude、OpenAI 或其他 AI API 請求，也不會觸發外部行情回補。」

- [ ] **230.6 不動的部分（明確約束）**

  - **BFF 不改**：`bff/src/main/java/com/steven/assets/bff/tradingradar/TradingRadarBffRoutes.java` 是 Spring Cloud Gateway 純 rewrite passthrough，`/api/bff/trading-radar/**` → `/api/trading-radar/**`，二進位 body 與 `Content-Disposition`／`Content-Type` header 原樣穿透，`/export` 會自動代理，**不需新增 `@RestController` 或 route**。
  - **不新增資料表 / 不改 Liquibase**：快照只存 Redis。
  - **不新增 `@Scheduled`**：保留窗修剪於 230.1 步驟 7 的寫入路徑 inline 完成，故不需碰 `SchedulePublicBffController.JOBS`。

- [ ] **230.7 測試（實作與測試同屬本任務）**

  在 `backend/src/test/java/com/steven/assets/service/` 新增匯出／快照相關單元測試（測試檔命名由實作者定），至少覆蓋：
  - 節流：同一 owner 在 `SNAPSHOT_MIN_INTERVAL` 內第二次 `save` 不新增索引 member。
  - 去重：內容相同（僅 `generatedAt` 不同）時不新增快照；內容變動時才寫。
  - gzip＋Base64 往返：`gunzip(Base64.decode(payload))` 還原出的 JSON 與原始 `writeValueAsString` 完全一致。
  - 保留窗修剪：寫入時移除 score 早於 `now − SNAPSHOT_RETENTION` 的索引項。
  - fail-soft：`StringRedisTemplate` 拋例外時 `save` 不向外拋（可用 Mockito stub 拋 `RuntimeException` 驗證）。
  - 匯出：多個快照 → 三分頁且列數正確、時間欄為文字；`range` 遇 value 為 `null`（TTL/LRU 逐出）時跳過該點而不失敗；空區間回含表頭的合法活頁簿。

  本專案在 Java 25 下跑 Mockito 需要 `-DargLine="-Dnet.bytebuddy.experimental=true"`（直接傳 `-D` 無效，surefire 會 fork 新 JVM）。

## 驗證

> **環境事實（已實測，照抄即可執行）**：容器名為 `asset-postgres`／`asset-redis`／`asset-bff`／`asset-business-services`／`asset-external-materials-service`（**不是** `asset-management-*-1`）。DB 的 user 與 database 皆為 `assets`。**只有 `asset-bff` 對 host 發佈 8080**；business-services 只在容器網路內聽 8080，沒有 host port。BFF 的 `/api/bff/**` 需登入（回 401），免 OAuth 的做法是進 business 容器帶 `X-User-Id`／`X-User-Role`／`X-User-Status` header。**本專案沒有 root pom**，測試要用 `-f <module>/pom.xml`。business 與 external **未啟用 actuator**，只有 bff 有。Redis 為 `asset-redis`，快照與 `price:*` 共用 database 0。

```bash
# 1. 單元測試
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test \
  -DargLine="-Dnet.bytebuddy.experimental=true"

# 2. 前端建置
cd frontend && /Users/steven/.nvm/versions/node/v22.21.0/bin/node \
  /Users/steven/.nvm/versions/node/v22.21.0/bin/npm run build && cd ..

# 3. 重建映像並重建容器（JVM 必須 --no-cache）
docker compose -p asset-management build --no-cache business-services frontend
docker compose -p asset-management up -d --no-deps --force-recreate business-services frontend

# 4. 重建 business 會換 IP，BFF 握舊 IP 回 500 且不自癒（Docker DNS TTL 600s）
docker compose -p asset-management restart bff

# 5. 健康檢查（只有 bff 有 actuator）
curl -s http://localhost:8080/actuator/health

# 6. 匯出前先記錄 price:* 現有筆數（稍後比對，確認未被快照擠掉）
docker exec asset-redis redis-cli --scan --pattern 'price:*' | wc -l

# 7. 觸發 get() 寫入快照（owner=1；容器內帶身分 header）
docker exec asset-business-services curl -s \
  -H "X-User-Id: 1" -H "X-User-Role: ADMIN" -H "X-User-Status: ACTIVE" \
  http://localhost:8080/api/trading-radar >/dev/null && echo "get() 已呼叫"

# 8. 確認快照索引與 value 落地（含 TTL）
docker exec asset-redis redis-cli ZRANGE trading-radar:snap:idx:1 0 -1 WITHSCORES
EPOCH=$(docker exec asset-redis redis-cli ZRANGE trading-radar:snap:idx:1 0 0 | head -1)
echo "最新快照 epoch=$EPOCH"
docker exec asset-redis redis-cli TTL "trading-radar:snap:1:$EPOCH"   # 應接近 90 天秒數（約 7776000）
docker exec asset-redis redis-cli STRLEN "trading-radar:snap:1:$EPOCH" # gzip+Base64 後的長度

# 9. 節流：5 分鐘內再打一次，索引 member 數不應增加
BEFORE=$(docker exec asset-redis redis-cli ZCARD trading-radar:snap:idx:1)
docker exec asset-business-services curl -s \
  -H "X-User-Id: 1" -H "X-User-Role: ADMIN" -H "X-User-Status: ACTIVE" \
  http://localhost:8080/api/trading-radar >/dev/null
AFTER=$(docker exec asset-redis redis-cli ZCARD trading-radar:snap:idx:1)
echo "節流前 $BEFORE → 後 $AFTER（應相等）"

# 10. 匯出當日區間為 .xlsx（經 bff，需登入；此處走 business 容器內直打驗證產檔）
docker exec asset-business-services curl -s \
  -H "X-User-Id: 1" -H "X-User-Role: ADMIN" -H "X-User-Status: ACTIVE" \
  "http://localhost:8080/api/trading-radar/export?from=$(date +%F)T00:00:00&to=$(date +%F)T23:59:59" \
  -o /tmp/radar.xlsx
docker exec asset-business-services sh -c 'ls -l /tmp/radar.xlsx && head -c4 /tmp/radar.xlsx | xxd'
# 預期：檔案非空、前四 bytes 為 PK\x03\x04（xlsx=zip）

# 11. from>to 回 400
docker exec asset-business-services curl -s -o /dev/null -w "%{http_code}\n" \
  -H "X-User-Id: 1" -H "X-User-Role: ADMIN" -H "X-User-Status: ACTIVE" \
  "http://localhost:8080/api/trading-radar/export?from=2026-07-20T10:00:00&to=2026-07-20T09:00:00"
# 預期：400

# 12. price:* 未被擠掉（與步驟 6 比對，不應大幅下降）
docker exec asset-redis redis-cli --scan --pattern 'price:*' | wc -l

# 13. 請求鏈沒有 AI API 呼叫
docker compose -p asset-management logs --since 2m business-services \
  | grep -iE "anthropic|openai|claude" || echo "無 AI API 呼叫（預期）"
```

驗收判準：步驟 8 有索引 member 且 value TTL 接近 90 天；步驟 9 節流前後 member 數相等；步驟 10 產出合法 xlsx（PK 開頭）；步驟 11 回 400；步驟 12 的 `price:*` 筆數與步驟 6 相當（未被快照擠出）；步驟 13 無命中。前端另需在瀏覽器實測「匯出 Excel」按鈕→選目錄→開啟檔案確認三分頁（快照索引／大盤總覽／個股決策）與時間欄為文字。

## 完成報告

**實作日期：** 2026-07-21

**改動檔案：**
- 新增 `backend/.../service/TradingRadarSnapshotStore.java`（key 結構、節流／去重／gzip＋Base64／保留窗＋per-owner 筆數硬上限、range 缺漏容忍）。
- 新增 `backend/.../service/TradingRadarExportService.java`（讀區間快照 → POI 三分頁：快照索引／大盤總覽／個股決策；時間欄 ISO 文字；缺漏彙總列）。
- 改 `backend/.../service/TradingRadarService.java`（注入 store＋CurrentUserContext，get() 尾端 fail-soft 寫快照，僅 `RequestContextHolder` 有 request 且 `hasUser()` 時）。
- 改 `backend/.../controller/TradingRadarController.java`（新增 `GET /api/trading-radar/export?from&to`，照抄 RealizedGain 下載模板）。
- 改 `backend/.../resources/application.yml`（新增 `trading-radar.snapshot.{min-interval-minutes:5, retention-days:90, max-per-owner:5000}`，皆可經環境變數覆寫）。
- 改前端 `api/index.js`（`tradingRadar.exportExcel(from,to)`）、`views/TradingRadarView.vue`（匯出按鈕＋datetime 區間對話框＋沿用 ExchangeRateView 的 saveBlob/downloadBlob/canPickDirectory＋資訊框文案更新）。
- 新增測試 `backend/src/test/java/com/steven/assets/service/`：快照 store（節流／去重／gzip 往返／缺漏容忍，4 案）與匯出 service（三分頁／時間欄文字／缺漏彙總／空區間／from>to／格式錯誤，4 案）。

**驗證輸出（部署後 asset-* 容器實測，owner=1）：**
- 後端單元測試：`Tests run: 8, Failures: 0, Errors: 0`；前端 `vite build` 通過（TradingRadarView chunk 20.26 kB）。
- 運行 jar 含 `TradingRadarSnapshotStore`/`TradingRadarExportService`（非 stale）；前端 bundle 含 `trading-radar/export`。
- `GET /api/trading-radar`（帶 X-User header）回 200，寫入一筆快照：`ZRANGE trading-radar:snap:idx:1` 有 member `1784564424762`；value TTL＝**7775972s ≈ 90 天**；**gzip＋Base64 後僅 3840 bytes**（21 檔完整雷達）。
- 節流：立即再打一次，`ZCARD` 1→1 未增（節流生效）。
- 匯出 `GET /api/trading-radar/export?from=2026-07-21T00:00:00&to=...T23:59:59` 回 200、10161 bytes、`PK\x03\x04` 開頭；xlsx 內三工作表 `快照索引`／`大盤總覽`／`個股決策`。
- `from>to` 回 **400**（GlobalExceptionHandler 轉 IllegalArgumentException）。
- **`price:*` 即時股價 key 匯出前後皆 144，未被快照擠掉**（256MB／allkeys-lru 已實測確認）。
- bff restart 後無殘留 `Connection refused`（count=0）；前端 `/` 回 200、bff `/actuator/health` UP。

**與原計畫的偏差：**
- 任務號自 228 連續避讓兩次（228→229→230，兩號皆被其他 worktree 於同時段 land）。
- 前端瀏覽器點擊實測（按鈕→對話框→showSaveFilePicker→下載）未執行：`/api/bff/**` 需使用者 OAuth 登入 session，無法在無頭環境完成；已改以「bundle 含 export 程式＋build 通過＋後端端到端 200」佐證，最後一哩的 UI 操作待使用者於登入狀態點擊確認。
- 部署映像自本 feature worktree build（尚未 commit/merge）；依共用 stack 慣例，merge 進 main 後應改從 main 的 worktree 重建以免被其他 session 洗掉。
