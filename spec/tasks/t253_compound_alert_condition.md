# [t253] 複合條件警示：多條件同時成立才觸發（單層 AND 群組）

**對應 Requirements:** Requirement 16（到價警示：使用者設定股票價格門檻／均線／KD 等條件，系統定期檢查並記錄最近一次觸發資訊，於股票觀察頁彙總顯示）
**前置任務:** 無
**Liquibase changeset:** `v1.78.0-stock-alert-group.sql`

## 背景

現行每一列 `stock_alert` 就是一個獨立條件（`alert_type` + `ma_period` + `threshold`），`StockAlertService.evaluate()` 逐列判定、逐列記 24h cooldown、逐列 enqueue 寄信。所以同一檔股票掛 6 個條件時語意天然是 **OR**：任一達標就寄一封信。

實例（運行中 DB，`0050` 元大台灣50 目前有 6 列）：`低於季線 10%`、`低於年線`、`K 值高於 96`、`K 值低於 15`、`D 值高於 95`、`D 值低於 15`。使用者要的是「**低於季線 10% 且 K 值低於 15**」這種同時成立才算數的買點訊號，現在做不到。

本任務新增「複合條件」：一個 `stock_alert_group` 綁 2～5 條件，群組內條件全部在同一次評估中成立才觸發一次。

**已拍板的範圍限制（不得自行擴大）：**

1. **只做單層 AND。** 不做 OR、不做巢狀運算式（`(A 且 B) 或 C`）。要 OR 就照現況拆成多筆獨立條件，各自觸發、各自寄信。因此**不設 `logic_op` 欄位**——只有一種運算子時，該欄位是恆定值，違反本專案「不存可計算得出的衍生值」原則；日後真要支援 OR 再加 nullable 欄位。
2. **複合條件不做盤中補抓。** 既有單一條件在 `lastTriggeredAt IS NULL` 時會走 `findRecentIntradayTrigger` 抓 Yahoo 5 分 K 回溯最近 3 個交易日、精確定位觸發時點；**群組不走這條路徑**。要支援得把 `matchInIntradayBars` 從「找第一個滿足單一條件的 bar」重寫成「對每根 bar 算出 price/MA20/MA60/MA240/K/D 全套指標再套 AND」，成本遠高於效益；漏掉的僅是「盤中短暫同時成立又立刻脫離」的尖峰。

## 現況事實（實作前提，皆已於本次確認）

### DB（運行中 `asset-postgres`）

`\d stock_alert`：

| 欄位 | 型別 | Nullable | Default |
|------|------|----------|---------|
| id | bigint | not null | `nextval('stock_alert_id_seq')` |
| stock_code | varchar(20) | not null | — |
| market | varchar(20) | not null | — |
| alert_type | varchar(50) | not null | — |
| threshold | numeric(10,4) | not null | — |
| active | boolean | not null | true |
| last_triggered_at | timestamp | null | — |
| created_at | timestamp | not null | now() |
| updated_at | timestamp | not null | now() |
| last_triggered_price | numeric(16,4) | null | — |
| last_triggered_kd_value | numeric(10,4) | null | — |
| last_triggered_ma_value | numeric(16,4) | null | — |
| last_triggered_d_value | numeric(10,4) | null | — |
| display_order | integer | not null | 0 |
| ma_period | integer | null | — |
| owner_user_id | bigint | not null | — |

索引 `stock_alert_pkey`、`idx_stock_alert_active(active)`、`idx_stock_alert_code_market(stock_code, market)`、`idx_stock_alert_owner(owner_user_id)`；FK `fk_stock_alert_owner → app_user(id)`；被 `stock_alert_recipient.fk_sar_alert` 與 `stock_alert_trigger.stock_alert_trigger_alert_id_fkey` 以 `ON DELETE CASCADE` 參照。**沒有 `group_id` 欄位**（本任務新增）。

`\d stock_alert_recipient`：`id bigint identity PK`、`alert_id bigint not null`、`recipient_id bigint not null`；`uq_stock_alert_recipient UNIQUE (alert_id, recipient_id)`；FK `fk_sar_alert → stock_alert(id) ON DELETE CASCADE`、`fk_sar_recipient → notification_recipient(id) ON DELETE CASCADE`。

`stock_alert_trigger`：`alert_id` 目前為 **NOT NULL**，FK → `stock_alert(id) ON DELETE CASCADE`；另有 `stock_code`、`market`、`triggered_at`、`price`、`monthly_ma`、`quarterly_ma`、`annual_ma`、`k_value`、`d_value`、`created_at`。**沒有 `group_id` 欄位**（本任務新增）。

運行中 DB `SELECT id FROM databasechangelog ORDER BY orderexecuted DESC LIMIT 5` 為 `v1.77.0-notification-recipient-calendar`、`v1.76.0-gdrive-output-all-export-pages`、`v1.75.0-crawler-gdrive-output`、`v1.74.0-asset-transaction-price-scale`、`v1.73.0-asset-transaction-export-schedule`，與 main 的 `db.changelog-master.yaml` 尾端一致 → **`v1.78.0` 為未被占用的下一個版號**。

### 既有程式（皆在 `backend/src/main/java/com/steven/assets/`）

- `model/StockAlert.java`：`@Entity @Table(name="stock_alert")`、`@Filter(name="ownerFilter", condition="owner_user_id = :ownerId")`、Lombok `@Data @NoArgsConstructor @AllArgsConstructor @Builder`。`@PreUpdate void onUpdate()` 維護 `updatedAt`。
- `repository/StockAlertRepository.java`：`findAllByOrderByDisplayOrderAsc()`、`findByActiveTrue()`、`findByStockCodeAndMarket(String, String)`、`@Query` 的 `findDistinctStockCodeMarket()`（`SELECT a.stockCode, a.market, MIN(a.displayOrder) as ord FROM StockAlert a GROUP BY a.stockCode, a.market ORDER BY ord ASC`，回 `List<Object[]>`）、`findMinDisplayOrderFor(code, market)`。
- `service/StockAlertService.java`（889 行）關鍵方法：
  - `checkAlerts()`：`alertRepo.findByActiveTrue()` → 逐一 `evaluate`。
  - `checkAlertsFor(stockCode, market)`：`findByActiveTrue()` 後在記憶體 filter 同代號同市場 → 逐一 `evaluate`。由 Redis pub/sub price-update 訊息觸發。
  - `evaluate(StockAlert)`：取 `priceQuery.getLive` → 24h cooldown（`lastTriggeredAt.isAfter(now().minusHours(24))` 則 return）→ **一段 `switch (alert.getAlertType())` 判定 `triggered`**（`MA_ABOVE_PCT` / `MA_BELOW_PCT` → `checkMaDeviation(alert, price, maPeriod, above)`；`KD_ABOVE` / `KD_BELOW` / `KD_D_ABOVE` / `KD_D_BELOW` → `checkKdValue(alert, useD, above)`；`PRICE_ABOVE` / `PRICE_BELOW` → 直接比 `threshold`；`default → false`）→ 交易日閘門 `marketDataService.isTradingDay(market, ZonedDateTime.now(marketZone).toLocalDate())` → 命中則寫 `lastTriggered*` 五欄 + `alertRepo.save` + `recordTrigger(alert, triggeredAt, price)` → 否則（且 `lastTriggeredAt == null`）落到 `findRecentIntradayTrigger(alert, 3)` 補抓分支。整支包在 try/catch，失敗只 `log.warn`。
  - `recordTrigger(alert, triggeredAt, price)`：`indicatorService.computeAll(code, market)` → `triggerRepo.save(StockAlertTrigger.builder().alertId(...)…)` → `notificationDispatcher.enqueue(alert, triggeredAt, price, ind.monthlyMa(), ind.quarterlyMa(), ind.annualMa(), ind.k(), ind.d())`，兩段各自包 try/catch。
  - `computeTriggeredAt(alert)`：交易日且在 `[openTime, closeTime]` 內 → `ZonedDateTime.now(marketZone).toLocalDateTime()`；否則退回 `historyRepo.findMaxTradingDate(code, market).atTime(close)`。
  - `public static String buildLabel(StockAlert a)` / `buildLabel(StockAlert a, TechnicalIndicatorService.FullIndicators ind)`：以 `switch (alertType)` 產出「高於季線 5%（315）」「K 值低於 20」「股價高於 260」等文案；`MA_*_PCT` 且 threshold≠0 時經 `maTriggerPriceSuffix` 附換算觸發價。`private static BigDecimal pickMaForAlert(type, maPeriod, ind)`（20→monthlyMa、60→quarterlyMa、240→annualMa、其他→quarterlyMa）。
  - `assertNoDuplicate(code, market, alertType, maPeriod, threshold, excludeId, stockName)`：掃 `findByStockCodeAndMarket`，`(alertType, maPeriod, threshold)` 三者皆同即丟 `IllegalArgumentException("已存在相同的警示條件（%s %s），未重複新增")`（`GlobalExceptionHandler` 轉 400 ProblemDetail）。threshold 用 `compareTo`、maPeriod 用 `Objects.equals`。
  - `assertNameMatchesCode(code, market, userName)`：外部 canonical name 守門；`0000`+台股跳過、`0000`+美股/英股拒絕。
  - `replaceRecipients(alertId, recipientIds)`：`recipientLinkRepo.deleteByAlertId` → 去重 → **先以 owner-filtered 的 `recipientRepo.findByIdIn(distinct)` 取得合法白名單，只寫入交集**（防止把他人 email 掛成自己警示的收件人，Requirement 30）。
  - `public static final int FRESHNESS_TRADING_DAYS = 2`；`recentTradingDayCutoff(market, n)` 取 `historyRepo.findDistinctTradingDatesByMarket(market, PageRequest.of(0,n))` 最早一天午夜。
  - `toResponse(a)`：組 `StockAlertDto.Response`，`lastTriggered*` 五欄經 freshness cutoff 過濾（過期回 null），`conditionLabel` 由 `buildLabel(a, maIndicatorsForLabel(a))` 產生。
  - `private boolean checkMaDeviation(alert, currentPrice, days, above)` 與 `private boolean checkKdValue(alert, useD, above)`：**各自查 `historyRepo.findRecentN` 並在命中時把 MA / K / D 回寫進傳入的 `alert` 物件**（副作用，`evaluate` 依賴它凍結指標值）。
- `dto/StockAlertDto.java`：`Request(String stockCode, String stockName, String market, String alertType, Integer maPeriod, BigDecimal threshold, Boolean active, List<Long> recipientIds)`（compact constructor 把 null `active` 補成 true）；`@Builder Response(Long id, String stockCode, String stockName, String market, String alertType, Integer maPeriod, BigDecimal threshold, Boolean active, List<Long> recipientIds, LocalDateTime lastTriggeredAt, BigDecimal lastTriggeredPrice, BigDecimal lastTriggeredMaValue, BigDecimal lastTriggeredKdValue, BigDecimal lastTriggeredDValue, LocalDateTime createdAt, String conditionLabel)`。
- `controller/StockAlertController.java`：`@RequestMapping("/api/stock-alerts")`，已有 `GET /`、`GET /recipients`、`POST /`、`PUT /{id}`、`DELETE /{id}`、`PATCH /{id}/active`、`PUT /reorder`（body `List<Long>`）、`POST /check`、`GET /lookup-name`、`GET /lookup-code`。類上有 `@Validated`，常數 `CODE_PATTERN = "^[A-Za-z0-9.\\-]{1,12}$"`、`MARKET_PATTERN = "^[\\p{L}0-9]{1,10}$"`。
- `service/WatchStockService.java`：`findAll()` 由 `alertRepo.findDistinctStockCodeMarket()` 衍生觀察清單；`toResponse(code, market)`（一般股）與 `toIndexResponse(code, market)`（`0000` 台股大盤）**兩個分支各自**呼叫 `buildConditions(alerts, cutoff, ind)` 與計算 `lastAlert`（`alerts.stream().filter(lastTriggeredAt != null).filter(不早於 cutoff).max(comparing(lastTriggeredAt))`）。`private static List<WatchStockDto.Condition> buildConditions(List<StockAlert>, LocalDateTime cutoff, FullIndicators ind)` 依 `displayOrder` 排序後逐條 map 成 `new WatchStockDto.Condition(buildLabel(a, ind), active, triggered)`。
- `dto/WatchStockDto.java`：`record Condition(String label, Boolean active, Boolean triggered)`；`record Key(String stockCode, String market)`；`@Builder Response(…, List<Condition> conditions, LocalDateTime lastTriggeredAt, BigDecimal lastTriggeredPrice, String lastTriggeredAlertType, BigDecimal monthlyMa, quarterlyMa, annualMa, @JsonProperty("kValue") kValue, @JsonProperty("dValue") dValue)`。
- `service/AlertNotificationDispatcher.java`：`@Scheduled(fixedDelay=60_000L, initialDelay=60_000L) flush()`。`private record PendingTrigger(Long alertId, String stockCode, String market, String stockName, String conditionLabel, LocalDateTime triggeredAt, BigDecimal price, BigDecimal monthlyMa, BigDecimal quarterlyMa, BigDecimal annualMa, BigDecimal kValue, BigDecimal dValue)`。`enqueue(...)` 以 `StockAlertService.buildLabel(alert)` 填 `conditionLabel`。`private record RecipientBatch(AlertRecipientTarget target, List<PendingTrigger> triggers)`；`groupByRecipient(List<PendingTrigger>)` 回 `LinkedHashMap<Long recipientId, RecipientBatch>`，內部以 `recipientLinkRepo.findActiveTargetsByAlertId(alertId)` 取收件人、並以 `Map<Long alertId, List<AlertRecipientTarget>>` 快取。`buildDigest(batch, chartCache, withCalendarNotice)` 回 `DigestMail(String html, Map<String,byte[]> inlineImages, int stockCount, List<String> calendarLines)`，內部以 `groupByStock(batch)`（key = `stockCode + " " + market`（分隔符是半形空白，不是豎線））合併同檔多條件、labels 去重後串接。`resendLastTradingDay(market)` 從 `stock_alert_trigger` 撈列 → `toPending(t)` → 共用 `groupByRecipient` / `buildDigest`。
  > **查這支檔的呼叫端一律用 `grep -ran`（帶 `-a`）**：`AlertNotificationDispatcher.java` 會被 `file(1)` 判為 `data`，不加 `-a` 的 `grep -r` 會**靜默跳過整個檔案**。
- `repository/StockAlertRecipientRepository.java`：`findActiveTargetsByAlertId(Long)` 的 JPQL 為
  `SELECT new com.steven.assets.repository.projection.AlertRecipientTarget(r.id, r.email, r.addToCalendar) FROM StockAlertRecipient s, com.steven.assets.model.NotificationRecipient r, com.steven.assets.model.StockAlert a WHERE s.alertId = :alertId AND s.recipientId = r.id AND a.id = s.alertId AND r.ownerUserId = a.ownerUserId AND r.active = true`
  —— 其中 `r.ownerUserId = a.ownerUserId` 是 Task 145 為「背景 cron 無 request context → `ownerFilter` 不啟用」補的縱深防護。另有 `deleteByAlertId(Long)`、`findRecipientIdsByAlertId(Long)`。
- `repository/projection/AlertRecipientTarget.java`：`record AlertRecipientTarget(Long id, String email, Boolean addToCalendar)`。
- `security/TenantFilterAspect.java:45-51`：`@Before("execution(* com.steven.assets.repository..*(..))")` 內
  `if (RequestContextHolder.getRequestAttributes() == null) { return; // 背景執行緒：不啟用，維持掃全體 }`
  → `@Scheduled` 中呼叫任何 repository 方法都**不套 `ownerFilter`**。
- `security/TenantGuard`：`requireCurrentUserId()`、`assertOwned(Long ownerUserId)`。

### 前端

- `frontend/src/views/StockAlertView.vue`（518 行）：`el-table :data="currentAlerts" row-key="id"`，欄位為 拖拽把手 / 股號股名 / 警示條件（`row.conditionLabel`）/ 建立時間 / 狀態（`el-switch` → `toggleActive`）/ 觸發時間股價均線KD / 操作（編輯、刪除）。以 `marketTab` 分台股／美股／英股三個 `el-tab-pane`，資料存 `twAlerts` / `usAlerts` / `ukAlerts` 三個 ref。Sortable 拖曳 `onEnd` 呼叫 `bffApi.stockAlert.reorder([...tw, ...us, ...uk].map(a => a.id))`。新增/編輯 dialog 的 `form` 為單一條件（`conditionGroup` ∈ `PRICE` / `MA_20` / `MA_60` / `MA_240` / `KD`、`direction` ∈ `ABOVE` / `BELOW`、`kdIndicator` ∈ `K` / `D`、`threshold`、`priceThreshold`、`recipientIds`、`active`），`buildAlertType()` 組出 `alertType`，`parseAlertType(alertType, threshold, maPeriod)` 反解回 form。`MA_GROUPS = { MA_20: 20, MA_60: 60, MA_240: 240 }`、`MA_NAMES = { 20:'月線', 60:'季線', 240:'年線' }`。`defineExpose({ openNewDialog(initMarket) })` 供父層 `StockMonitorView` 呼叫。存檔錯誤以 `ElMessageBox.alert` 呈現（`create`/`update` 已帶 `skipErrorToast`）。
- `frontend/src/views/WatchStockView.vue`（297 行）：「警示條件」欄逐條渲染 `row.conditions`（`label` / `active` / `triggered`）。
- `frontend/src/api/index.js:372-384`：`stockAlert` 命名空間有 `getAll` / `getRecipients` / `reorder` / `lookupName` / `lookupCode` / `create` / `update` / `toggleActive` / `delete`，全部打 `/bff/stock-alert...`。
- BFF `bff/src/main/java/com/steven/assets/bff/stockalert/StockAlertBffRoutes.java`：`/api/bff/stock-alert/**` → rewrite `"/api/bff/stock-alert(?<seg>/?.*)"` → `"/api/stock-alerts${seg}"`，**已是萬用 passthrough，新端點不需改 BFF**。

## 要做什麼

### 資料層

- [ ] 253.1 新增 `backend/src/main/resources/db/changelog/changes/v1.78.0-stock-alert-group.sql`，檔首 `--liquibase formatted sql`，內含**兩個 changeset**（拆開的理由見下方 253.1b）。第一個為 `--changeset steven:v1.78.0-stock-alert-group`，內容如下。**每一句都必須冪等**（本專案多 worktree 共用同一套運行中 DB，changeset 可能已被別的分支套用；非冪等即 `already exists` → business crash loop 整站掛，Task 207 的教訓）：

      ```sql
      CREATE TABLE IF NOT EXISTS stock_alert_group (
          id                      BIGSERIAL PRIMARY KEY,
          owner_user_id           BIGINT       NOT NULL REFERENCES app_user(id),
          stock_code              VARCHAR(20)  NOT NULL,
          market                  VARCHAR(20)  NOT NULL,
          active                  BOOLEAN      NOT NULL DEFAULT TRUE,
          display_order           INTEGER      NOT NULL DEFAULT 0,
          last_triggered_at       TIMESTAMP,
          last_triggered_price    NUMERIC(16,4),
          last_triggered_ma_value NUMERIC(16,4),
          last_triggered_kd_value NUMERIC(10,4),
          last_triggered_d_value  NUMERIC(10,4),
          created_at              TIMESTAMP    NOT NULL DEFAULT now(),
          updated_at              TIMESTAMP    NOT NULL DEFAULT now()
      );
      CREATE INDEX IF NOT EXISTS idx_sag_active      ON stock_alert_group(active);
      CREATE INDEX IF NOT EXISTS idx_sag_code_market ON stock_alert_group(stock_code, market);
      CREATE INDEX IF NOT EXISTS idx_sag_owner       ON stock_alert_group(owner_user_id);

      CREATE TABLE IF NOT EXISTS stock_alert_group_recipient (
          id           BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
          group_id     BIGINT NOT NULL REFERENCES stock_alert_group(id) ON DELETE CASCADE,
          recipient_id BIGINT NOT NULL REFERENCES notification_recipient(id) ON DELETE CASCADE,
          CONSTRAINT uq_stock_alert_group_recipient UNIQUE (group_id, recipient_id)
      );
      CREATE INDEX IF NOT EXISTS idx_sagr_group     ON stock_alert_group_recipient(group_id);
      CREATE INDEX IF NOT EXISTS idx_sagr_recipient ON stock_alert_group_recipient(recipient_id);

      ALTER TABLE stock_alert ADD COLUMN IF NOT EXISTS group_id BIGINT;
      CREATE INDEX IF NOT EXISTS idx_stock_alert_group ON stock_alert(group_id);

      ALTER TABLE stock_alert_trigger ADD COLUMN IF NOT EXISTS group_id BIGINT;
      CREATE INDEX IF NOT EXISTS idx_sat_group ON stock_alert_trigger(group_id);
      ALTER TABLE stock_alert_trigger ALTER COLUMN alert_id DROP NOT NULL;
      ```

- [ ] 253.1b 三個具名 FK / CHECK 沒有 `IF NOT EXISTS` 語法，一律用 `DO $$` 包 `pg_constraint` 存在性判斷。這一段**必須獨立成第二個 changeset 並帶 `splitStatements:false`**：

      ```
      --changeset steven:v1.78.0-stock-alert-group-constraints splitStatements:false
      ```

      **少了 `splitStatements:false` 會讓 Liquibase 把 `DO $$ … END $$;` 從中間切開，migration 失敗、business-services 進 crash loop 整站掛。** 原因：Liquibase 4.29.2（`backend/pom.xml` 宣告版本）的 `StringUtil.processMultiLineSQL` 以 `BEGIN` / `END` **token 計數**判斷是否身處區塊內，`END IF;` 的 `END` 會把計數減回 0，緊接的 `;` 就被當成語句結尾切開 → dollar-quote 未閉合 → Postgres syntax error。本 repo 既有僅有的兩支含 `DO $$` 的 changeset（`v1.71.0-configurable-admin-email.sql` 與 `v1.34.1-drop-legacy-global-uniques.sql`）**都**帶這個旗標，無一例外。

      ```sql
      DO $$ BEGIN
          IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_stock_alert_group') THEN
              ALTER TABLE stock_alert ADD CONSTRAINT fk_stock_alert_group
                  FOREIGN KEY (group_id) REFERENCES stock_alert_group(id) ON DELETE CASCADE;
          END IF;
          IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_sat_group') THEN
              ALTER TABLE stock_alert_trigger ADD CONSTRAINT fk_sat_group
                  FOREIGN KEY (group_id) REFERENCES stock_alert_group(id) ON DELETE CASCADE;
          END IF;
          IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_sat_alert_xor_group') THEN
              ALTER TABLE stock_alert_trigger ADD CONSTRAINT ck_sat_alert_xor_group
                  CHECK ((alert_id IS NOT NULL) <> (group_id IS NOT NULL));
          END IF;
      END $$;
      ```

      `ALTER COLUMN alert_id DROP NOT NULL` 本身冪等（重複執行對已是 nullable 的欄位無作用）。
      **`stock_alert.group_id` 刻意不設 DEFAULT** —— 既有列（撰寫本任務時運行中 DB 為 76 列）全部保持 NULL ＝ 獨立單一條件，行為不變，**不做任何資料遷移**。
      於 `db.changelog-master.yaml` **尾端**追加（block 樣式，與檔內既有 90 個 include 一致，**不要**寫成 flow 單行）：
      ```yaml
        - include:
            file: db/changelog/changes/v1.78.0-stock-alert-group.sql
            relativeToChangelogFile: false
      ```

- [ ] 253.2 `model/StockAlert.java` 新增 `@Column(name = "group_id") private Long groupId;`（nullable，**不映射 JPA 關聯**，沿用本專案「以 Long id 顯式關聯」慣例，同 `StockAlertTrigger.alertId`）。javadoc 註明：`null` ＝ 獨立單一條件（行為與 Task 253 前完全相同）；非空 ＝ AND 群組成員，**不得自行觸發**，`active` 恆為 true、`last_triggered_*` 五欄不寫。

- [ ] 253.3 新增 `model/StockAlertGroup.java`：`@Entity @Table(name = "stock_alert_group")`、`@Filter(name = "ownerFilter", condition = "owner_user_id = :ownerId")`、`@Data @NoArgsConstructor @AllArgsConstructor @Builder`。欄位對應 253.1 的表（`Long id` / `Long ownerUserId` / `String stockCode` / `String market` / `Boolean active`（`@Builder.Default = true`）/ `Integer displayOrder`（`@Builder.Default = 0`）/ `LocalDateTime lastTriggeredAt` / `BigDecimal lastTriggeredPrice` / `lastTriggeredMaValue` / `lastTriggeredKdValue` / `lastTriggeredDValue` / `createdAt`（`@Column(nullable=false, updatable=false) @Builder.Default = LocalDateTime.now()`）/ `updatedAt`（`@Builder.Default = LocalDateTime.now()`）），並比照 `StockAlert` 加 `@PreUpdate void onUpdate() { updatedAt = LocalDateTime.now(); }`。

- [ ] 253.4 新增 `model/StockAlertGroupRecipient.java`：`@Entity @Table(name = "stock_alert_group_recipient")`，欄位 `Long id` / `Long groupId` / `Long recipientId`，`@Data @NoArgsConstructor @AllArgsConstructor @Builder`。**不掛 `@Filter`**（比照既有 `StockAlertRecipient`，join 表無 owner 欄位；租戶保護在寫入端以白名單交集完成，見 253.9）。

- [ ] 253.5 `model/StockAlertTrigger.java`：
      - 新增 `@Column(name = "group_id") private Long groupId;`
      - **把既有的 `@Column(name = "alert_id", nullable = false) private Long alertId;` 改為 `@Column(name = "alert_id") private Long alertId;`** —— 253.1 已把 DB 端改成 nullable，entity 若還宣告 `nullable = false`，Hibernate 可能在 flush 前先擋下 `alertId = null` 的群組觸發列（是否真的擋，取決於 `hibernate.check_nullability`，而本專案 `application.yml` 從未顯式設定該鍵、其預設又受 classpath 上的 Bean Validation 影響）。**不要把「群組觸發寫不寫得進 trigger 表」押在一個沒人設定過的內部旗標上。**
      - `alertId` 的 javadoc 補上「Task 253 起可為 null（群組觸發時）；`alert_id` 與 `group_id` 恰好一個非空，DB 以 `ck_sat_alert_xor_group` 保證」。

- [ ] 253.6 Repository：
      - `StockAlertRepository` 新增 `List<StockAlert> findByActiveTrueAndGroupIdIsNull();` 與 `List<StockAlert> findByGroupIdOrderByDisplayOrderAsc(Long groupId);` 與 `void deleteByGroupId(Long groupId);`
      - 新增 `repository/StockAlertGroupRepository.java`：`extends JpaRepository<StockAlertGroup, Long>`，方法 `List<StockAlertGroup> findAllByOrderByDisplayOrderAsc()`、`List<StockAlertGroup> findByActiveTrue()`、`List<StockAlertGroup> findByStockCodeAndMarket(String, String)`。
      - 新增 `repository/StockAlertGroupRecipientRepository.java`：`extends JpaRepository<StockAlertGroupRecipient, Long>`，方法 `void deleteByGroupId(Long)`、`void deleteByRecipientId(Long)`、`@Query("SELECT s.recipientId FROM StockAlertGroupRecipient s WHERE s.groupId = :groupId") List<Long> findRecipientIdsByGroupId(@Param("groupId") Long groupId)`，以及供 dispatcher 用的投影查詢（**JPQL 與既有 `findActiveTargetsByAlertId` 逐字對齊，只把 `StockAlert a` 換成 `StockAlertGroup g`**）：
        ```java
        @Query("SELECT new com.steven.assets.repository.projection.AlertRecipientTarget(r.id, r.email, r.addToCalendar) " +
               "FROM StockAlertGroupRecipient s, com.steven.assets.model.NotificationRecipient r, " +
               "com.steven.assets.model.StockAlertGroup g " +
               "WHERE s.groupId = :groupId AND s.recipientId = r.id AND g.id = s.groupId " +
               "AND r.ownerUserId = g.ownerUserId AND r.active = true")
        List<AlertRecipientTarget> findActiveTargetsByGroupId(@Param("groupId") Long groupId);
        ```
        **`r.ownerUserId = g.ownerUserId` 與 `r.active = true` 兩個條件不得省略**：`AlertNotificationDispatcher.flush()` 是 `@Scheduled`、無 HTTP request context，`TenantFilterAspect` 明文放行不套 `ownerFilter`（`if (RequestContextHolder.getRequestAttributes() == null) return;`），少了 owner 條件就會把別的租戶的 email 掛進本群組的收件人。這正是 Task 145 為同一支 dispatcher 修過的洞，不可從新入口重新打開。
        `SELECT new` 後**必須寫完整套件路徑**（Hibernate 6.6.11 / Boot 3.4.4 的 constructor expression 限制）。

### 條件評估（本任務的核心，回歸風險最高）

- [ ] 253.7 `StockAlertService`：把 `evaluate` 內判定 `triggered` 的那段 `switch (alert.getAlertType())` **原封不動**抽成
      `private boolean matches(StockAlert alert, double currentPrice)`，`evaluate` 改為呼叫它。
      **不得順手「優化」內部實作**（例如把 `checkMaDeviation` / `checkKdValue` 各自查 history 改成共用一次 `computeAll`）：那兩支方法會在命中時把 MA / K / D **回寫進傳入的 `alert` 物件**，`evaluate` 依賴這個副作用凍結指標值；且 `checkMaDeviation` 的 MA 口徑（`historyRepo.findRecentN` + `withTodayIfMissing` 後取簡單平均）與 `TechnicalIndicatorService.computeAll()` 未必逐位一致，換來源等於偷改既有單一條件的觸發門檻。抽方法的唯一目的是讓群組與獨立條件**共用同一份判定邏輯**、口徑零分歧。

- [ ] 253.8 `StockAlertService` 新增 `private void evaluateGroup(StockAlertGroup group)`，整支包 try/catch（失敗只 `log.warn("Error evaluating alert group {}: {}", …)`，比照 `evaluate`）：
      1. `priceQuery.getLive(group.getStockCode(), group.getMarket())`；空或 `price() == null` → return。
      2. 24h cooldown：`group.getLastTriggeredAt() != null && group.getLastTriggeredAt().isAfter(LocalDateTime.now().minusHours(24))` → return。**cooldown 只看群組，成員不各自 cooldown。**
      3. `List<StockAlert> members = alertRepo.findByGroupIdOrderByDisplayOrderAsc(group.getId())`；
         **`members.size() < 2` → return**（`allMatch` 對空集合恆真，不擋等於無條件觸發）。
      4. `boolean triggered = members.stream().allMatch(m -> matches(m, currentPrice))`——**用同一個 `currentPrice`**。
         注意 `allMatch` 短路：第一個 false 就停，未被評估的成員不會被 `checkMaDeviation` / `checkKdValue` 回寫指標值。這無妨，因為群組的 `last_triggered_*` 一律改由第 6 步的 `computeAll` 填，不依賴成員副作用。
         **但被評估到的成員會被回寫，且該回寫必須不落到 DB。** `checkMaDeviation`（命中時 `alert.setLastTriggeredMaValue`）與 `checkKdValue`（命中時 `setLastTriggeredKdValue` / `setLastTriggeredDValue`）直接改傳入的 entity；`findByGroupIdOrderByDisplayOrderAsc` 回來的成員是 managed 狀態，而 `POST /api/stock-alerts/check` 是 HTTP 路徑、OSIV 預設開啟（`spring.jpa.open-in-view` 全樹未設定），第 6 步的 `groupRepo.save(group)` 會在 commit 時 flush **整個** persistence context，把髒掉的成員一併 UPDATE 出去 —— 直接違反「成員 `last_triggered_*` 一律不寫」。
         **作法：取出成員後立即 detach**（注入 `EntityManager`，對每個成員呼叫 `em.detach(m)`；或改用不含 setter 的投影後另行建構暫時物件）。Redis pub/sub 的背景路徑因每次 repository 呼叫各開各的 EntityManager 而不受影響，但**不得以此為由略過 detach** —— 手動「立即檢查」按鈕走的正是 HTTP 路徑。
      5. 交易日閘門：`marketDataService.isTradingDay(group.getMarket(), ZonedDateTime.now(MarketZones.resolve(group.getMarket())).toLocalDate())`；非交易日則**直接 return**（不落到任何補抓分支——複合條件不做盤中補抓）。
      6. 命中時：`LocalDateTime triggeredAt = computeTriggeredAt(...)`（既有方法只用到 `alert.getMarket()` 與 `getStockCode()`；**改成接 `(String market, String stockCode)` 兩個參數的多載**，`evaluate` 改呼叫多載傳 `alert.getMarket(), alert.getStockCode()`，不要複製一份），寫 `group.setLastTriggeredAt/Price`；`indicatorService.computeAll` 取 `FullIndicators` 後寫 `lastTriggeredMaValue`（取**群組內第一個 `MA_*_PCT` 成員**的 `maPeriod` 對應均線，無 MA 成員則不寫）、`lastTriggeredKdValue`、`lastTriggeredDValue`（取指標失敗只 `log.warn`，不阻斷）；`groupRepo.save(group)`。
      7. `recordGroupTrigger(group, members, triggeredAt, price)`：寫**一筆** `StockAlertTrigger`（`alertId = null`、`groupId = group.getId()`、`stockCode` / `market` / `triggeredAt` / `price` / 五個指標欄比照既有 `recordTrigger` 無條件填寫），再 `notificationDispatcher.enqueueGroup(group, buildGroupLabel(members, ind), triggeredAt, price, ind.monthlyMa(), ind.quarterlyMa(), ind.annualMa(), ind.k(), ind.d())`。兩段各自包 try/catch（比照既有 `recordTrigger`）。
         **寫一筆、不是每個成員各寫一筆**：寫 N 筆會讓補發路徑（`resendLastTradingDay`）把同一次 AND 觸發還原成 N 條獨立條件，文案與 live 寄出的合併 label 分歧。

- [ ] 253.9 `StockAlertService` 新增 `public static String buildGroupLabel(List<StockAlert> members, TechnicalIndicatorService.FullIndicators ind)`：把成員（已依 `displayOrder` 升冪）各自的 `buildLabel(m, ind)` 串接。
      **分隔符的字面值固定為 `" 且 "`（半形空白 + 且 + 半形空白），即 `String.join(" 且 ", labels)`。** 這個字面值本檔到處都要一致 —— `buildLabel` 產出的單條 label 兩端都不帶空白（`"低於季線 10%"` / `"低於季線 10%（92.09）"` / `"K 值低於 15"`），寫成不帶空白的 `"且"` 會得到「…10%（92.09）且K 值低於 15」這種黏在一起的字串，而 253.23 的測試斷言與 253.16／驗證段的 curl 斷言只要各自假設不同寫法就必然有一邊失敗。
      合併結果範例（`ind` 非 null）：`低於季線 10%（92.09） 且 K 值低於 15`；`ind = null` 時：`低於季線 10% 且 K 值低於 15`。
      `ind` 可為 null（退回不含觸發價的純文字，比照既有 `buildLabel(a, null)`）。成員為空 list 時回空字串，不丟例外。
      警示頁、觀察頁、email digest、補發四條路徑**全部**用這一支，不得各自串接。

- [ ] 253.10 `StockAlertService.checkAlerts()` 改為：
      ```java
      List<StockAlert> actives = alertRepo.findByActiveTrueAndGroupIdIsNull();
      List<StockAlertGroup> groups = groupRepo.findByActiveTrue();
      if (actives.isEmpty() && groups.isEmpty()) return;
      log.info("檢查 {} 個到價警示、{} 個複合條件群組", actives.size(), groups.size());
      actives.forEach(this::evaluate);
      groups.forEach(this::evaluateGroup);
      ```
      `checkAlertsFor(stockCode, market)` 同步改：獨立條件由 `findByActiveTrue()` 改為 `findByActiveTrueAndGroupIdIsNull()`（再 filter 同代號同市場），並加上 `groupRepo.findByActiveTrue()` filter 同代號同市場後逐一 `evaluateGroup`。
      > **這一項是整個任務最高的回歸風險點。** `findByActiveTrue()` 沒改成 `findByActiveTrueAndGroupIdIsNull()` 的話，群組成員（`active` 恆為 true）會各自被 `evaluate` 獨立觸發並各自寄信 —— AND 靜默退化成 OR，功能看起來「有做」但完全沒生效，且沒有任何錯誤訊息。**兩支方法都要改，`checkAlertsFor` 很容易漏。**

### 群組 CRUD

- [ ] 253.11 `dto/StockAlertDto.java` 擴充：
      - 新增 `public record ConditionItem(String alertType, Integer maPeriod, BigDecimal threshold, String label) {}`（`label` 僅 Response 回填，Request 送來時忽略）。
      - 新增 `public record GroupRequest(String stockCode, String stockName, String market, List<ConditionItem> conditions, Boolean active, List<Long> recipientIds) {}`，compact constructor 把 null `active` 補成 true（比照既有 `Request`）。
      - `Response` 新增兩欄：`String kind`（`"SINGLE"` / `"GROUP"`）與 `List<ConditionItem> conditions`（`GROUP` 才有值，`SINGLE` 為 null）。**放在 record 參數列尾端 `conditionLabel` 之後**，避免既有 `Response.builder()` 呼叫端受影響。
- [ ] 253.12 `StockAlertService` 新增群組 CRUD（全部 `@Transactional`，寫入前一律 `tenantGuard.assertOwned(group.getOwnerUserId())`；`create` 用 `tenantGuard.requireCurrentUserId()` 填 owner）：
      - `createGroup(GroupRequest)`：驗證（見 253.13）→ `assertNameMatchesCode(code, market, stockName)`（沿用既有守門，`0000`+台股跳過）→ 非 `0000`+台股時 `stockMasterService.upsert(code, market, stockName)` → 存 group（`displayOrder` = 現有獨立 alert 與群組兩者 `displayOrder` 最大值 + 1）→ 逐條建立成員 `StockAlert`（`groupId` = 新群組 id、`ownerUserId` 同群組、`active = true`、`lastTriggered*` 全部留 null）→ `replaceGroupRecipients(groupId, recipientIds)`。
        **成員的 `displayOrder` 必須從「群組自己的 `displayOrder`」起算**（第 i 個成員 = `group.displayOrder + i`），**不得從 0 或從陣列索引起算**。理由：觀察清單的去重查詢是
        `SELECT a.stockCode, a.market, MIN(a.displayOrder) FROM StockAlert a GROUP BY a.stockCode, a.market ORDER BY ord ASC`，
        成員仍是 `stock_alert` 的列、必然被算進 `MIN()`。若成員拿到 0 / 1，該股票的 `MIN` 會被壓到全域最小值，**使用者只是建了一個複合條件，整份觀察清單順序卻被重排、該股票跳到最前面**（實測運行中 DB：`0050` 目前 `ord=4`、全表 `display_order` 範圍 0–80，成員取 0、1 就會把 `0050` 頂到第一）。而群組本身在警示頁又被指派 `max+1` 排在最後，兩頁順序直接互相矛盾。
      - `updateGroup(Long id, GroupRequest)`：**成員整組覆寫**（`alertRepo.deleteByGroupId(id)` 後重建）；`recipientIds` 非 null 才覆寫 join（null ＝ 本次未更動，比照既有 `update`）。整組覆寫會清掉成員 id，但成員 id 對外不可見（前端只看群組 id），且成員本就不承載觸發狀態，無資料遺失。
      - `deleteGroup(Long id)`：`groupRecipientRepo.deleteByGroupId(id)` → `alertRepo.deleteByGroupId(id)` → `groupRepo.delete(group)`（DB 亦有 `ON DELETE CASCADE` 雙保險）。
      - `toggleGroupActive(Long id)`：翻轉 `active` 並存檔。
      - `replaceGroupRecipients(Long groupId, List<Long> recipientIds)`：**逐字比照既有 `replaceRecipients` 的租戶白名單作法** —— `groupRecipientRepo.deleteByGroupId` → `LinkedHashSet` 去重並移除 null → `recipientRepo.findByIdIn(distinct)`（`NotificationRecipient` 掛 `@Filter`，HTTP 請求下必被 `ownerFilter` 限縮成只回當前租戶）取合法白名單 → **只寫入交集**，他人的 `recipientId` 略過不綁定。少了這一步就能把他人 email 掛成自己群組的收件人（Requirement 30）。
      - `createGroup` 的 recipientIds 為 null 時，比照既有 `create` 預設全選（`notificationRecipientService.findAll()` 的所有 id）；空 list 代表不寄給任何人。
- [ ] 253.13 群組驗證（放在 `createGroup` / `updateGroup` 前端，違反一律丟 `IllegalArgumentException` → 400 ProblemDetail）：
      - `conditions` 為 null 或 `size() < 2` → 「複合條件至少需要 2 個條件」
      - `conditions.size() > 5` → 「複合條件最多 5 個條件」
      - 同群組內兩個條件的 `(alertType, maPeriod, threshold)` 完全相同 → 「複合條件內有重複的條件」（threshold 用 `compareTo` 比、maPeriod 用 `Objects.equals`，比照既有 `assertNoDuplicate`）
      - 已存在另一個群組，其 `(stockCode, market)` 相同且**條件集合完全相同**（不計順序）→ 「已存在相同的複合條件警示，未重複新增」（update 時排除自身）
      - 每個條件的 `alertType` 必須是既有八種之一（`PRICE_ABOVE` / `PRICE_BELOW` / `MA_ABOVE_PCT` / `MA_BELOW_PCT` / `KD_ABOVE` / `KD_BELOW` / `KD_D_ABOVE` / `KD_D_BELOW`），且 `MA_*_PCT` 必須有非 null `maPeriod`、其餘型別 `maPeriod` 必須為 null → 否則「條件類型 X 不合法」。**不擋跨型別組合**（例「股價高於 300 且 股價低於 200」這種恆偽組合是使用者的自由，系統不揣測意圖）。
      - **不需要**呼叫既有 `assertNoDuplicate`（那支是比對獨立條件；群組與獨立條件內容相同不算重複，語意本就不同）。
- [ ] 253.13b **反方向也要處理：既有 `assertNoDuplicate` 必須排除群組成員。** 現行實作是
      `for (StockAlert a : alertRepo.findByStockCodeAndMarket(code, market))`，**完全沒有 `groupId` 過濾**。Task 253 之後成員就是同 `stock_code` / `market` 的 `stock_alert` 列，必然被掃到 → 使用者對 0050 建了群組「低於季線 10% 且 K 值低於 15」之後，再新增獨立條件「0050 低於季線 10%」會被回 400「已存在相同的警示條件（元大台灣50 低於季線 10%），未重複新增」，**而畫面上根本找不到那筆「已存在」的條件**（253.14 已把成員從 `findAll()` 濾掉），使用者陷入無法自行排除的死路。
      作法：迴圈第一行加 `if (a.getGroupId() != null) continue;`。這與 253.13 的「群組不需呼叫 `assertNoDuplicate`」是同一個設計決定的兩面，兩面都要做。
- [ ] 253.14 `StockAlertService.findAll()` 改回**混合清單**：獨立條件（`groupId IS NULL`）以既有 `toResponse` 組成 `kind="SINGLE"`；群組以新的 `toGroupResponse(group)` 組成 `kind="GROUP"`（`id` = 群組 id、`alertType` / `maPeriod` / `threshold` 為 null、`conditionLabel` = `buildGroupLabel(members, ind)`、`conditions` = 成員的 `ConditionItem`（含各自 `label`）、`recipientIds` = `findRecipientIdsByGroupId`、`lastTriggered*` 五欄經**同一份** freshness cutoff 過濾）；兩者合併後**依 `displayOrder` 升冪**排序回傳。
      `toGroupResponse` 取指標的規則比照既有 `maIndicatorsForLabel`：群組內**有任一 `MA_*_PCT` 且 threshold≠0 的成員**才呼叫 `indicatorService.computeAll`，否則傳 null 省查詢；取指標失敗吞例外回 null（label 退回不含價格），不影響清單載入。
      **`findAll()` 目前的實作是 `alertRepo.findAllByOrderByDisplayOrderAsc().stream().map(this::toResponse).toList()`，改動後獨立條件那半必須加上 `groupId == null` 過濾** —— 否則群組成員會同時以 `SINGLE` 列出現，畫面上一個群組看起來像「一列群組 + N 列散條件」。
- [ ] 253.15 `StockAlertService.reorder` 改為接 `List<StockAlertDto.OrderItem>`（新 record `OrderItem(String kind, Long id)`）：依序把 `SINGLE` 的 `StockAlert.displayOrder` 與 `GROUP` 的 `StockAlertGroup.displayOrder` 指派為索引值，兩者共用同一個排序空間；寫入前各自 `tenantGuard.assertOwned`。
      **群組成員的 `displayOrder` 不參與此重排**（它只決定群組內條件的串接順序）。
      > 既有實作有個 bug 會一併帶進來：`a.setDisplayOrder(orderedIds.indexOf(id))` 在 id 重複時取第一個索引。改寫時直接用迴圈變數 `i`，不要沿用 `indexOf`。
- [ ] 253.16 `StockAlertController` 新增（沿用類上既有 `@Validated`）：
      - `POST /groups` → `createGroup`
      - `PUT /groups/{id}` → `updateGroup`
      - `DELETE /groups/{id}` → `deleteGroup`（回 `ResponseEntity.noContent()`）
      - `PATCH /groups/{id}/active` → `toggleGroupActive`
      並把既有 `PUT /reorder` 的 body 型別由 `List<Long>` 改為 `List<StockAlertDto.OrderItem>`。
      **BFF 不需改動**（`/api/bff/stock-alert/**` 已是萬用 passthrough，rewrite 成 `/api/stock-alerts/**`）。

### 通知

- [ ] 253.17 `AlertNotificationDispatcher`：
      - `PendingTrigger` record **新增 nullable `Long groupId`**（放在既有 `alertId` 之後）。既有 `enqueue(...)` 一律填 null。
      - 新增 `public void enqueueGroup(StockAlertGroup group, String conditionLabel, LocalDateTime triggeredAt, BigDecimal price, BigDecimal monthlyMa, BigDecimal quarterlyMa, BigDecimal annualMa, BigDecimal kValue, BigDecimal dValue)`：比照既有 `enqueue` 以 `resolveStockName(code, market)` 取股名，`alertId` 填 null、`groupId` 填 `group.getId()`、`conditionLabel` 用傳入的合併 label；整支包 try/catch 只 `log.warn`。
      - `groupByRecipient`：依 `t.groupId != null` 決定呼叫 `groupRecipientRepo.findActiveTargetsByGroupId(t.groupId)` 或既有 `recipientLinkRepo.findActiveTargetsByAlertId(t.alertId)`。
        **收件人快取的 key 必須能區分兩者** —— 既有快取是 `Map<Long alertId, List<AlertRecipientTarget>>`，直接沿用會讓 `alert id = 5` 與 `group id = 5` 互相污染收件人清單（兩表各自 BIGSERIAL，值必然重疊）。改成 `Map<String, List<AlertRecipientTarget>>`，key 為 `"A" + alertId` / `"G" + groupId`。
      - 新增 `private final StockAlertGroupRecipientRepository groupRecipientRepo;`（`@RequiredArgsConstructor` 自動建構）。既有欄位不得重複宣告。
      - `buildDigest` / `groupByStock` / `calendarLine` **完全不改**：群組觸發與同檔的獨立條件觸發本就該併進同一個股票區塊，labels 去重串接的行為正確。
- [ ] 253.17b `NotificationRecipientService.delete(id)` 目前有
      `alertRecipientRepo.deleteByRecipientId(id);   // Task 125：連帶刪除其在警示 join 表的列（DB 亦有 ON DELETE CASCADE 雙保險）`，
      **同一行下方補 `groupRecipientRepo.deleteByRecipientId(id);`**（需注入新 repository）。DB 的 `fk_sagr_recipient … ON DELETE CASCADE` 本就會兜底、功能不會壞，但既有寫法是「service 顯式刪 ＋ DB CASCADE 雙保險」，只做一半會在日後 CASCADE 被調整時留下孤兒列。
- [ ] 253.18 `AlertNotificationDispatcher.resendLastTradingDay` 的 `toPending(StockAlertTrigger t)`：`t.getGroupId() != null` 時，`groupId` 帶入、`conditionLabel` 由 `groupRepo.findById(groupId)` + `alertRepo.findByGroupIdOrderByDisplayOrderAsc(groupId)` 經 `StockAlertService.buildGroupLabel(members, null)` 還原；群組已刪除的孤兒觸發沿用既有的「以『警示觸發』當 fallback label，不靜默丟棄」行為。`alertId` 非空時走既有路徑不變。

### 觀察清單

- [ ] 253.19 `WatchStockService`：
      - `buildConditions(alerts, cutoff, ind)` 改為 `buildConditions(alerts, groups, cutoff, ind)`：獨立條件（`groupId == null`）維持逐條一個 `Condition`；**群組合併成一條** `Condition`（`label` = `StockAlertService.buildGroupLabel(成員, ind)`、`active` = 群組的 `active`、`triggered` = 群組 `lastTriggeredAt` 落在 cutoff 內）。合併後的排序：獨立條件用自身 `displayOrder`、群組用群組的 `displayOrder`，混合後升冪。
      - 「警示」欄的最近觸發彙總（既有 `lastAlert`：`alerts.stream().filter(lastTriggeredAt != null).filter(不早於 cutoff).max(comparing(lastTriggeredAt))`）**須一併納入該股票所有群組的 `lastTriggeredAt` 取 max**；命中群組時 `lastTriggeredAlertType` 填 `"GROUP"`。
      - `toResponse`（一般股）與 `toIndexResponse`（`0000` 台股大盤）**兩個分支都要改** —— 它們各自複製了一份 `buildConditions` 呼叫與 `lastAlert` 計算，只改一邊會讓大盤的複合條件在觀察頁散開顯示。
      - `findAll()` 用的 `alertRepo.findDistinctStockCodeMarket()` **不需改**：群組成員仍是 `stock_alert` 的列（`stock_code` / `market` 皆有值），GROUP BY 自動涵蓋。但**若日後允許建立「只有群組、沒有任何成員」的空群組則會漏**——本任務以 `conditions.size() >= 2` 的驗證杜絕空群組，不另處理。
- [ ] 253.19b **`WatchStockService.reorder` 必須跳過群組成員，並同步重排群組。** 現行實作是
      ```java
      List<StockAlert> alerts = alertRepo.findByStockCodeAndMarket(key.stockCode().trim().toUpperCase(), key.market());
      alerts.sort(Comparator.comparingInt(a -> a.getDisplayOrder() != null ? a.getDisplayOrder() : 0));
      for (StockAlert a : alerts) { a.setDisplayOrder(cursor++); alertRepo.save(a); }
      ```
      **沒有 `groupId` 過濾。** 253.12 賦予成員 `displayOrder` 新語意（決定群組內條件的串接順序、且必須從群組的 `displayOrder` 起算），253.15 聲明「成員不參與重排」——但那句只約束 `StockAlertService.reorder`，觀察頁的拖曳走的是這一支完全不同的方法。不改的話：使用者在觀察頁拖一次，(a) 群組內條件順序被 `cursor++` 洗成任意值，合併 label 的條件順序跟著變；(b) `stock_alert_group.display_order` 完全沒被同步，警示頁的混合排序與剛拖出來的觀察清單順序脫節。
      作法：內圈 `for` 加 `if (a.getGroupId() != null) continue;`（成員不吃 cursor），並在同一輪對該 `(stockCode, market)` 的 `StockAlertGroup` 指派 `cursor++`；群組拿到 cursor 值後，**該群組所有成員的 `displayOrder` 一併重寫為 `群組新值 + 成員索引`**，維持 253.12 的不變式。

### 前端

- [ ] 253.20 `frontend/src/api/index.js` 的 `stockAlert` 命名空間新增（皆帶 `skipErrorToast: true`，比照既有 `create` / `update`，讓存檔錯誤走 dialog 而非頂部 toast）：
      ```js
      createGroup: (data) => api.post('/bff/stock-alert/groups', data, { skipErrorToast: true }),
      updateGroup: (id, data) => api.put(`/bff/stock-alert/groups/${id}`, data, { skipErrorToast: true }),
      toggleGroupActive: (id) => api.patch(`/bff/stock-alert/groups/${id}/active`),
      deleteGroup: (id) => api.delete(`/bff/stock-alert/groups/${id}`),
      ```
      並把既有 `reorder: (ids) => api.put('/bff/stock-alert/reorder', ids)` 的參數改為 `orderItems`（`[{kind, id}]`）。
- [ ] 253.21 `StockAlertView.vue`：
      - `el-table` 的 `row-key` 由 `"id"` 改為 `` (row) => `${row.kind}-${row.id}` `` —— **群組 id 與獨立條件 id 分屬不同表、值會相撞**，沿用 `row-key="id"` 會讓 Element Plus 與 Sortable 的列識別錯亂。
      - 「警示條件」欄：`kind === 'GROUP'` 時在 label 前加一個 `el-tag size="small"` 標示「複合」，並把各條件分行顯示（`row.conditions.map(c => c.label)`，行間以「且」串接或逐行前綴），讓使用者一眼看出是 AND；`SINGLE` 維持現狀顯示 `row.conditionLabel`。
      - 編輯 / 刪除 / 啟停按鈕依 `row.kind` 分派到 `updateGroup` / `deleteGroup` / `toggleGroupActive` 或既有的單一條件端點。
      - 拖曳 `onEnd` 的 `reorder` payload 改送 `[...tw, ...us, ...uk].map(a => ({ kind: a.kind, id: a.id }))`。
      - 「新增警示」按鈕旁新增「新增複合條件」按鈕，開啟**複合條件 dialog**：股票欄位（市場 / 代號 / 股名，沿用既有 `fetchStockName` / `fetchStockCode`）+ 可增刪的條件列（每列一組「條件類型 / 方向 / 指標 / 門檻」，重用既有 `conditionGroup` / `direction` / `kdIndicator` / `threshold` / `priceThreshold` 的欄位組合與 `buildAlertType()` 邏輯，抽成可重複渲染的區塊）+ 收件人多選（重用既有 `recipients` / `recipientAllChecked` / `recipientIndeterminate`）+ 啟用開關。
      - 條件列數限制 2～5：少於 2 時「儲存」禁用並提示「複合條件至少需要 2 個條件」；已達 5 列時「新增條件」按鈕禁用。
      - dialog 頂部加一行說明：「所有條件在同一次檢查中**同時成立**才觸發（AND）。任一條件成立就要通知的話，請改用個別的『新增警示』。複合條件不做盤中回溯補抓，只在每 2 分鐘的即時檢查判定。」
      - 編輯群組時以 `row.conditions` 逐條反解回表單（重用既有 `parseAlertType`，改成接受一個 form 物件參數而非只寫全域 `form`）。
      - 存檔錯誤沿用既有 `ElMessageBox.alert(apiErrorMessage(e, '儲存失敗'), '無法儲存警示', …)`。
      - 儲存成功後 `emit('alert-saved')`（父層 `StockMonitorView` 據此重載觀察清單）。
- [ ] 253.22 `WatchStockView.vue`：`row.conditions` 中屬於群組的那條（後端已合併成單一 `Condition`），在 label 前加「複合」小標籤，其餘渲染邏輯不變。

### 測試

- [ ] 253.23 新增 `backend/src/test/java/com/steven/assets/service/StockAlertGroupLabelTest.java`（**純 JUnit 5，不需 Mockito**）驗證 `StockAlertService.buildGroupLabel`：
      - 兩個條件 → 以 `" 且 "` 串接，順序依傳入 list 順序。**斷言整個字串逐字相等**（不是 `contains`）：`MA_BELOW_PCT`/60/10 + `KD_BELOW`/15、`ind = null` → 恰好等於 `"低於季線 10% 且 K 值低於 15"`。逐字相等這點很重要——用 `contains("且")` 會讓分隔符寫成 `"且"`（無空白）也通過，而驗證段的 curl 斷言假設的是有空白的版本
      - 三個條件 → 兩個 `" 且 "`
      - 單一條件 → 無分隔符（回傳值等於該條件自己的 label）
      - 空 list → 回空字串（不丟例外）
      - 帶 `FullIndicators` 時，`MA_*_PCT` 且 threshold≠0 的成員 label 附換算觸發價「（…）」，其餘成員不受影響
- [ ] 253.24 新增 `backend/src/test/java/com/steven/assets/service/StockAlertGroupValidationTest.java`（純 JUnit 5）驗證 253.13 的驗證規則。為此把驗證邏輯抽成**不依賴 repository 的 static 方法**
      `static void validateConditions(List<StockAlertDto.ConditionItem> conditions)`（涵蓋筆數 2～5、組內重複、`alertType` 合法性、`maPeriod` 與型別的搭配），`createGroup` / `updateGroup` 呼叫它；「與其他群組重複」那條因需查 DB 留在 service 內、不在此測試涵蓋。
      至少涵蓋：1 條 → 丟例外且訊息含「至少需要 2 個條件」；6 條 → 含「最多 5 個條件」；兩條完全相同（含 `0` vs `0.0000` 的 scale 差異須判為相同）→ 含「重複」；`MA_ABOVE_PCT` 但 `maPeriod = null` → 丟例外；`KD_ABOVE` 但 `maPeriod = 60` → 丟例外；未知 `alertType` → 丟例外；合法的 2 條 → 不丟例外。
- [ ] 253.25 新增 `backend/src/test/java/com/steven/assets/service/StockAlertGroupMatchTest.java` 驗證 AND 判定與空群組防呆。`matches` 是 private instance method 且依賴 repository，**改以驗證抽出的純函式**為主：把「群組是否觸發」的判定抽成
      `static boolean groupTriggered(List<Boolean> memberResults)`（`memberResults.size() >= 2 && memberResults.stream().allMatch(b -> b)`），`evaluateGroup` 呼叫它。
      斷言：`[true, true]` → true；`[true, false]` → false；`[false, false]` → false；`[true]`（僅 1 個成員）→ **false**；`[]` → **false**（空集合 `allMatch` 恆真的陷阱）；`[true, true, true]` → true。

## 驗證

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test -Dtest='StockAlertGroupLabelTest,StockAlertGroupValidationTest,StockAlertGroupMatchTest'
```

全量測試（Mockito 在本機 JDK 需 byte-buddy experimental 旗標；**必須用 `-DextraArgLine`，不可用 `-DargLine`**——Task 252 起 `backend/pom.xml` 的 surefire `argLine` 固定為 `-Duser.timezone=Asia/Taipei ${extraArgLine}`，命令列直接下 `-DargLine=` 會整條覆蓋掉時區設定，實測會讓 181 個 Mockito 測試轉為 error）：

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -f backend/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

部署（本專案無 dev server，「改好」＝ image rebuild + container recreate；JVM service 一律 `--no-cache`，否則 cached layer 可能不含本次變更）：

```bash
docker compose -p asset-management build --no-cache business-services
```

```bash
docker compose -p asset-management up -d --no-deps --force-recreate business-services
```

```bash
docker compose -p asset-management restart bff
```

> `restart bff` 不可省：recreate business 會換 IP，BFF 握著舊 IP 會回 500 且因 Docker DNS TTL 600s 而 ≥3 分鐘不自癒；症狀是 business log 乾淨、錯誤只出現在 bff log 的 Connection refused。

前端一併重建（普通 build 會命中 layer cache 沒重跑 vite）：

```bash
docker compose -p asset-management build --no-cache frontend && docker compose -p asset-management up -d --no-deps --force-recreate frontend
```

migration 已套用：

```bash
docker exec asset-postgres psql -U assets -d assets -c "\d stock_alert_group"
```

```bash
docker exec asset-postgres psql -U assets -d assets -c "\d stock_alert" | grep group_id
```

```bash
docker exec asset-postgres psql -U assets -d assets -c "SELECT conname FROM pg_constraint WHERE conname IN ('fk_stock_alert_group','fk_sat_group','ck_sat_alert_xor_group')"
```

既有資料未被動到。**先於 migration 前記下基準值**，migration 後 `singles` 必須不變、`members` 必須為 0（建立任何群組之前）：

```bash
docker exec asset-postgres psql -U assets -d assets -c "SELECT count(*) FILTER (WHERE group_id IS NULL) AS singles, count(*) FILTER (WHERE group_id IS NOT NULL) AS members FROM stock_alert"
```

> 不要把「應該是幾列」寫死在驗證裡對答案 —— 這套 stack 是全機共用、多個 worktree 並行推進，列數隨時在變（撰寫本任務時為 76 列，但那是當下的快照，不是不變式）。要斷言的是「migration 沒動到既有列」，不是某個具體數字。

端點行為（X-User-* header 為免走 Google 登入的租戶模擬；`{RID}` 換成實際收件人 id）：

```bash
docker exec asset-business-services curl -s -X POST -H 'Content-Type: application/json' -H 'X-User-Id: 1' -H 'X-User-Role: ADMIN' -H 'X-User-Status: ACTIVE' -d '{"stockCode":"0050","market":"台股","stockName":"元大台灣50","conditions":[{"alertType":"MA_BELOW_PCT","maPeriod":60,"threshold":10},{"alertType":"KD_BELOW","threshold":15}],"recipientIds":[]}' http://localhost:8080/api/stock-alerts/groups
```

上述回應應含 `"kind":"GROUP"` 與 `"conditionLabel":"低於季線 10%（…） 且 K 值低於 15"`。接著驗證只帶 1 個條件必須回 400：

```bash
docker exec asset-business-services curl -s -o /dev/null -w '%{http_code}\n' -X POST -H 'Content-Type: application/json' -H 'X-User-Id: 1' -H 'X-User-Role: ADMIN' -H 'X-User-Status: ACTIVE' -d '{"stockCode":"0050","market":"台股","conditions":[{"alertType":"KD_BELOW","threshold":15}],"recipientIds":[]}' http://localhost:8080/api/stock-alerts/groups
```

混合清單與觀察清單：

```bash
docker exec asset-business-services curl -s -H 'X-User-Id: 1' -H 'X-User-Role: ADMIN' -H 'X-User-Status: ACTIVE' http://localhost:8080/api/stock-alerts | head -c 2000
```

```bash
docker exec asset-business-services curl -s -H 'X-User-Id: 1' -H 'X-User-Role: ADMIN' -H 'X-User-Status: ACTIVE' http://localhost:8080/api/watch-stocks | head -c 2000
```

**AND 未退化成 OR 的關鍵驗證**：手動觸發一次檢查後，確認群組成員（`group_id` 非空的 `stock_alert` 列）**沒有**任何一列被寫入 `last_triggered_at`：

```bash
docker exec asset-business-services curl -s -X POST -H 'X-User-Id: 1' -H 'X-User-Role: ADMIN' -H 'X-User-Status: ACTIVE' http://localhost:8080/api/stock-alerts/check
```

```bash
docker exec asset-postgres psql -U assets -d assets -c "SELECT count(*) FROM stock_alert WHERE group_id IS NOT NULL AND (last_triggered_at IS NOT NULL OR last_triggered_price IS NOT NULL OR last_triggered_ma_value IS NOT NULL OR last_triggered_kd_value IS NOT NULL OR last_triggered_d_value IS NOT NULL)"
```

上式必須回 `0`。**五個欄位都要查，不能只查 `last_triggered_at`** —— 只查 `at` 會漏掉 253.8 講的 OSIV 髒寫（`checkMaDeviation` / `checkKdValue` 回寫的是 `ma_value` / `kd_value` / `d_value` 三欄，`at` 那欄確實不會被寫，所以只查 `at` 會恆回 0、什麼都抓不到）。
非 0 的兩種成因：`last_triggered_at` 非空 → 253.10 沒改乾淨、成員仍走獨立評估路徑；只有 `ma/kd/d` 非空 → 253.8 的 detach 沒做。

**建一組必定成立的群組驗證真的會觸發**（例：`PRICE_ABOVE 0` + `PRICE_BELOW 999999` 兩條恆真條件），跑一次 `/check` 後：

```bash
docker exec asset-postgres psql -U assets -d assets -c "SELECT id, stock_code, last_triggered_at, last_triggered_price FROM stock_alert_group ORDER BY id DESC LIMIT 3"
```

```bash
docker exec asset-postgres psql -U assets -d assets -c "SELECT id, alert_id, group_id, stock_code, triggered_at FROM stock_alert_trigger WHERE group_id IS NOT NULL ORDER BY id DESC LIMIT 3"
```

驗證後刪除測試用群組。**注意：`/check` 只在該市場交易日才會寫入觸發**（`evaluateGroup` 有 `isTradingDay` 閘門），假日跑這段會得到 0 筆而誤判失敗。

## 完成報告

**新增檔案**
- `backend/src/main/resources/db/changelog/changes/v1.78.0-stock-alert-group.sql`（兩個 changeset，第二個帶 `splitStatements:false`）
- `backend/src/main/java/com/steven/assets/model/StockAlertGroup.java`
- `backend/src/main/java/com/steven/assets/model/StockAlertGroupRecipient.java`
- `backend/src/main/java/com/steven/assets/repository/StockAlertGroupRepository.java`
- `backend/src/main/java/com/steven/assets/repository/StockAlertGroupRecipientRepository.java`
- `backend/src/test/java/com/steven/assets/service/StockAlertGroupLabelTest.java`（6 項）
- `backend/src/test/java/com/steven/assets/service/StockAlertGroupValidationTest.java`（13 項）
- `backend/src/test/java/com/steven/assets/service/StockAlertGroupMatchTest.java`（8 項）

**異動檔案**
- `db.changelog-master.yaml`：註冊 v1.78.0（block 樣式，第 272–274 行）
- `StockAlert.java` / `StockAlertTrigger.java`：加 `groupId`；後者的 `alert_id` 拿掉 `nullable = false`
- `StockAlertRepository.java`：加 `findByActiveTrueAndGroupIdIsNull` / `findByGroupIdOrderByDisplayOrderAsc` / `deleteByGroupId`
- `StockAlertDto.java`：加 `ConditionItem` / `GroupRequest` / `OrderItem`，`Response` 加 `kind` / `conditions`
- `WatchStockDto.java`：`Condition` 加 `Boolean group`（見下方偏離 1）
- `StockAlertService.java`：`matches` 抽出、`evaluateGroup`、`buildGroupLabel`、`groupTriggered`、群組 CRUD、`validateConditions`、`assertNoDuplicateGroup`、`assertNoDuplicate` 排除成員、`findAll` 混合清單、`reorder` 改 `OrderItem`
- `StockAlertController.java`：四支 `/groups` 端點；`PUT /reorder` body 改 `List<OrderItem>`
- `AlertNotificationDispatcher.java`：`PendingTrigger` 加 `groupId`、`enqueueGroup`、收件人快取 key 改帶 `"A"`/`"G"` 前綴
- `NotificationRecipientService.java`：`delete` 連帶清 group join 表
- `WatchStockService.java`：`buildConditions` 合併群組、`lastAlert` 納入群組、`reorder` 跳過成員並同步重排群組（`toResponse` 與 `toIndexResponse` 兩分支都改）
- `frontend/src/api/index.js`：四支 group 方法、`reorder` 改送 orderItems
- `frontend/src/views/StockAlertView.vue`：`row-key` 帶 kind、GROUP 分行渲染、按鈕依 kind 分派、複合條件 dialog（2～5 列）
- `frontend/src/views/WatchStockView.vue`：群組條件加「複合」標籤

**驗證輸出**
- `mvn test -DextraArgLine=-Dnet.bytebuddy.experimental=true` → **Tests run: 310, Failures: 0, Errors: 0**（含本任務 27 項，零回歸；310 為併入 main 的 Task 252 之後的總數，併入前為 303）
- `vite build` → `✓ built in 4.35s`，零錯誤
- jar 內確認含 `StockAlertGroup.class` 等新 class 與 `v1.78.0-stock-alert-group.sql`（防 stale jar）
- 容器啟動日誌：`v1.78.0-stock-alert-group ran successfully in 37ms`、`v1.78.0-stock-alert-group-constraints ran successfully in 7ms`，零 ERROR
- schema：`stock_alert_group` 13 欄 + 3 索引、5 個具名約束（`fk_stock_alert_group` / `fk_sat_group` / `ck_sat_alert_xor_group` / `fk_sagr_group` / `fk_sagr_recipient`）皆存在、`stock_alert_trigger.alert_id` 已 nullable
- 既有資料未被動到：migration 後 `singles = 75, members = 0`
- 建立群組 → `kind:"GROUP"`、`conditionLabel:"低於季線 10%（92.07） 且 K 值低於 15"`（分隔符含前後空白，與 253.9 一致）
- 驗證規則：1 條 → 400「複合條件至少需要 2 個條件」；條件集合相同（順序顛倒亦判重複）→ 400「已存在相同的複合條件警示」；`MA_BELOW_PCT` 缺 `maPeriod` → 400「均線條件必須指定均線天數」
- **253.13b**：群組成員有 `KD_BELOW 22` 時，仍能建立同內容的獨立條件 → HTTP 200（未被誤判重複）
- **AND 語意（決定性對照實驗）**：建群組「K 值低於 22 **且** K 值高於 88」（恆偽）＋ 同值獨立條件 `KD_BELOW 22`。跑 `/check` 後，當時 K = 19.47 → **獨立條件觸發**（`stock_alert_trigger` 有 `alert_id=155`）、**群組未觸發**（`last_triggered_at` 為 null）。若 AND 退化成 OR，群組必定觸發。
- **成員未被寫觸發狀態**：`SELECT count(*) FROM stock_alert WHERE group_id IS NOT NULL AND (last_triggered_at IS NOT NULL OR last_triggered_price IS NOT NULL OR last_triggered_ma_value IS NOT NULL OR last_triggered_kd_value IS NOT NULL OR last_triggered_d_value IS NOT NULL)` → **0**（253.10 未漏改 ＋ 253.8 的 detach 生效）
- **253.12**：群組 `display_order=80` → 成員 80/81；群組 82 → 成員 82/83。觀察清單 0050 仍在第 3 位，未被頂到最前
- 混合清單：78 列 = GROUP 2 + SINGLE 76，成員未重複以 SINGLE 出現
- 觀察頁：群組合併成單一條件列並帶「複合」旗標，未散開成多條
- CASCADE：刪除群組 → 成員隨之清空、獨立條件回到 75 列、無孤兒 trigger（`alert_id IS NULL AND group_id IS NULL` → 0）
- 測試資料已全數清理

**與原計畫的偏差**
1. **`WatchStockDto.Condition` 新增 `Boolean group`（規格未列，但非有不可）**：253.19 把群組合併成單一 `Condition`、253.22 依旗標渲染「複合」標籤，但規格沒有任何一項要求在 DTO 補這個旗標，導致標籤永遠不顯示。此缺陷不產生編譯錯誤也不造成測試失敗，是逐項對照才發現的。已補欄位並於 `WatchStockService` 兩個建構點填值。
2. **253.18 未注入 `StockAlertGroupRepository`**：改以 `alertRepo.findByGroupIdOrderByDisplayOrderAsc` 取成員還原補發 label，規格原文寫的是 `groupRepo.findById` ＋ `alertRepo` 併用。行為等價（群組被刪時成員隨 CASCADE 消失 → `buildGroupLabel` 回空字串 → 轉入既有孤兒 fallback「警示觸發」），且少一個相依。
3. **`stock_alert_group_recipient` 的兩個 FK 改為具名**（`fk_sagr_group` / `fk_sagr_recipient`）：253.17b 以名稱指稱該 FK，匿名的話 Postgres 會自動命名為 `stock_alert_group_recipient_recipient_id_fkey`，與規格不一致。仍在 `CREATE TABLE IF NOT EXISTS` 內，冪等性不受影響。
4. **`evaluateGroup` 用明確迴圈而非 `allMatch`**：短路後仍為未評估的成員補 `false` 佔位，使 `memberResults` 筆數與成員數一致 —— 否則 `groupTriggered` 的「≥2 成員」守門會被短路壓成 1 筆而誤判。
5. **`StockAlertRepository.deleteByGroupId` 用 derived delete、join 表的 `deleteByGroupId` 用 bulk `@Modifying`**：兩張表的正確選擇相反。join 表有唯一鍵，`replaceGroupRecipients` 先刪後插會撞 `uq_stock_alert_group_recipient`（Hibernate action queue 預設先 insert 後 delete），故需 bulk 立即執行；`stock_alert` 無唯一鍵，反倒要避免 bulk 繞過 persistence context 造成成員殘留 managed 狀態、flush 時對已刪列發 UPDATE。

6. **併入 main 的 Task 252（系統時區統一台北）後，補修群組 cooldown 的時區 bug**：Task 252 把獨立條件的 24h cooldown 右側由 `LocalDateTime.now()` 改為 `MarketZones.nowLocal(market)`——兩側必須同為該市場牆鐘，否則冷卻長度變成 24h ± 市場 offset（台股實測 32h，早上觸發後隔天整個交易日仍在冷卻中而靜默漏發）。`evaluateGroup` 的群組 cooldown 是本任務新寫的、帶著一模一樣的 bug，**且 git 因改在不同行而自動合併成功、沒有產生衝突標記**。已一併改為 `MarketZones.nowLocal(group.getMarket()).minusHours(24)`。這類「自動合併成功但語意錯誤」的情形，只能靠 merge 後重讀對方改了什麼來發現。

7. **編號避讓時 `sed` 改到 changeset SQL 的註解 → checksum 變 → business crash loop（已修復，但值得記住）**：本任務因 main 兩度推進而三次避讓編號（249→250→251→253），最後一次用 `sed` 把 `Task 251` 全域換成 `Task 253`，**連 `v1.78.0-stock-alert-group.sql` 註解裡的那一處也換了**。Liquibase 的 checksum 涵蓋 changeset 全文（含註解），於是已部署環境啟動時報
   `v1.78.0-stock-alert-group::steven was: 9:eb4e797415c0b017da0bf3b3d4ee650d but is now: 9:ea142710c6338f41a922861579692310`
   → `ValidationFailedException` → `entityManagerFactory` 建不起來 → **crash loop 整站掛**。
   這是 [[Liquibase changeset 改名會重跑]] 的變體：不必改 changeset id，**光改註解就足以炸掉**。
   修法（本次採用）：因該 changeset 全句冪等且尚未進 main，直接
   `DELETE FROM databasechangelog WHERE id LIKE 'v1.78.0%'` 後重啟，讓它以新 checksum 重跑 —— 重跑時所有語句都是 no-op（表已存在則 `CREATE TABLE IF NOT EXISTS` 跳過、`DO $$` 內查 `pg_constraint` 判定約束已存在），日誌確認 `ran successfully in 15ms / 3ms`、零 ERROR。**順帶完成了冪等性的實地驗證**（在表已存在的狀態下重跑）。
   **給後人的規則：changeset SQL 檔一旦在任何環境跑過，就不要再碰它的內容——包含註解。編號避讓的 `sed` 要把 `db/changelog/` 排除在外。**

**尚未完成**
- commit / merge 進 main / 從 main 的 worktree 重建（依專案「共用 stack 誰最後 build 誰生效」規則）。
- 前端畫面未經瀏覽器實地操作驗證：`/stocks?tab=alerts` 需 Google 登入，代為認證不在可做範圍。已以「打包後 bundle 含新字串」與後端端到端回應佐證，畫面確認留給使用者。
