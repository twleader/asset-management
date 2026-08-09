# [t301] 交易雷達通知冷卻：同一 setting 同一狀態 60 分鐘內不重複寄送

**對應 Requirements:** Requirement 44（交易雷達逐檔狀態 Email 通知）之 `TW_RULES_V12` 波修訂第 8 條
**前置任務:** 無（同波部署；t302 之後會再動同一 service 檔，實作順序 t301 → t302）
**Liquibase changeset:** `v1.91.0-trading-radar-notification-cooldown.sql`

## 背景

`TradingRadarNotificationService` 以 2 秒節拍評估價格事件、`TradingRadarNotificationTransition` 在「動作／逆勢狀態**轉入**訂閱清單中的狀態」時寄信，同一狀態持續不重寄。**現在的錯誤行為**：盤中分數在動作門檻（75／55／40／25）附近震盪時，動作反覆「離開→重進」同一狀態，每次重進都算新轉入 → 單一交易日對同一檔可寄出數十封重複信件。t298 的 OSC 幅度正規化消除最大抖動源，但門檻邊界的翻面仍可能發生；引擎必須維持純函數（不能做分數遲滯），洗版抑制由通知層承擔。

**正確行為**：同一 `setting` 的同一 `state_code`（`ACTION` 與 `COUNTER_TREND` 各自獨立）在**冷卻窗 60 分鐘**內不重複寄送；冷卻**只擋 email**，`last_action`／`last_counter_trend_state` 基準照常更新（Requirement 44 其餘語意不變）。冷卻時長 **60 分鐘為判斷性取值、無量測依據**。

持久化選 DB 欄位而非 in-memory：重啟後冷卻不歸零，且語意落在資料所屬的 `trading_radar_notification_state`（訂閱狀態）列上。

## 要做什麼

- [x] 301.1 新增 `backend/src/main/resources/db/changelog/changes/v1.91.0-trading-radar-notification-cooldown.sql`：

  ```sql
  --liquibase formatted sql

  --changeset steven:v1.91.0-trading-radar-notification-cooldown
  -- Requirement 44（Task 301）：通知冷卻。同一 setting 同一 state_code 於冷卻窗內不重複寄送；
  -- 只擋 email，不影響 last_action 基準。TIMESTAMPTZ + Hibernate Instant（時區中立寫入）。
  ALTER TABLE trading_radar_notification_state
      ADD COLUMN IF NOT EXISTS last_notified_at TIMESTAMPTZ NULL;
  ```

  並在 `backend/src/main/resources/db/changelog/db.changelog-master.yaml` 尾端（`v1.90.0` include 之後）追加同格式 include。**建檔前先確認 `v1.91.0` 未被其他分支占用**（`ls backend/src/main/resources/db/changelog/changes/ | grep v1.91` 應無輸出；運行中 DB 再以 `docker exec asset-postgres psql -U assets -d assets -c "SELECT id FROM databasechangelog ORDER BY orderexecuted DESC LIMIT 5"` 比對）。冪等：`IF NOT EXISTS` 已保證重跑 no-op。
- [x] 301.2 `backend/src/main/java/com/steven/assets/model/TradingRadarNotificationState.java` 新增欄位：

  ```java
  @Column(name = "last_notified_at")
  private java.time.Instant lastNotifiedAt;
  ```

  型別必須是 `Instant`（Hibernate 走 UTC calendar、時區中立），**不得**用 `LocalDateTime`。
- [x] 301.3 `TradingRadarNotificationStateRepository` 新增 `List<TradingRadarNotificationState> findBySettingId(Long settingId);`（既有 `findStateCodes` 查詢保留不動，供其他呼叫端）。
- [x] 301.4 `TradingRadarNotificationService`：
  - 冷卻常數 `static final Duration NOTIFY_COOLDOWN = Duration.ofMinutes(60);`（javadoc 註明判斷性取值）。
  - 純函式判斷（供測試）：`static boolean cooldownActive(Instant lastNotifiedAt, Instant now)` → `lastNotifiedAt != null && Duration.between(lastNotifiedAt, now).compareTo(NOTIFY_COOLDOWN) < 0`。
  - `evaluateSettingSafely`：`transition.evaluate(...)` 判定 `actionEntered`／`counterTrendEntered` 之後（基準更新與 `settingRepo.save` **照舊、不受冷卻影響**），對每個 entered 的狀態：以 `findBySettingId` 取得對應 `state_type`＋`state_code` 列（`ACTION` 用 `decision.action()`、`COUNTER_TREND` 用 `decision.counterTrendState()`），`cooldownActive(row.lastNotifiedAt, now)` 為 true → 該狀態本輪**不進通知**；為 false → 設 `row.lastNotifiedAt = now` 並 save，狀態進通知。兩狀態同輪觸發時**逐狀態獨立**判冷卻；全部被抑制 → 不呼叫 `dispatcher.enqueue`。`now` 取評估當下 `Instant.now()`（服務層，非引擎）。
  - 對應列不存在（理論上不可能——transition 已要求 entered 狀態 ∈ 訂閱集合，而訂閱集合即該表）→ 視同無冷卻、照常通知並 log warn，不得拋錯中斷整輪。
- [x] 301.5 測試：
  - 加入既有 `TradingRadarNotificationTransitionTest`（通知狀態轉入語意的既有測試檔，冷卻屬同一語意群）：(a) `cooldownActive(null, now)` = false；(b) 59 分前 = true；(c) 61 分前 = false；(d) 恰 60 分 = false（`compareTo < 0` 邊界）。
  - service 層（Mockito，比照既有測試模式 stub `settingRepo`／`stateRepo`／`snapshotRepo`／`tradingRadarService`／`transition`）：(e) 首次轉入 → enqueue 且 `lastNotifiedAt` 被寫入；(f) 冷卻窗內再轉入同狀態 → 不 enqueue，但 `setting.lastAction` 仍被更新；(g) 冷卻窗外再轉入 → enqueue。
- [x] 301.6 無新增 `@Scheduled`（沿用既有 2 秒 `runCycle`），不需同步 `SchedulePublicBffController.JOBS`。

## 驗證

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test -Dtest='TradingRadarNotification*Test' -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

部署後（整波，見 t305）確認 Liquibase 套用成功、business-services 無 crash loop：

```bash
docker logs asset-business-services 2>&1 | grep -i "liquibase\|v1.91.0" | tail -5
```

```bash
docker exec asset-postgres psql -U assets -d assets -c "\d trading_radar_notification_state" | grep last_notified_at
```

## 完成報告

## t301 交易雷達通知冷卻：完成報告

### 實際改動

1. **新增** `backend/src/main/resources/db/changelog/changes/v1.91.0-trading-radar-notification-cooldown.sql`：`ALTER TABLE trading_radar_notification_state ADD COLUMN IF NOT EXISTS last_notified_at TIMESTAMPTZ NULL;`，內容與任務檔給定的 SQL 逐字一致。建檔前先 `ls .../changes | grep v1.91` 確認未被占用（無輸出）。因不得跑 docker，任務檔建議的 `docker exec ... databasechangelog` 比對未執行——以檔案系統層級的空輸出作為未撞號的依據（這是權威來源，本 worktree 尚未部署）。
2. **`db.changelog-master.yaml`**：於 `v1.90.0` include 之後純附加 3 行新 include，未動既有任何一行（`git diff` 確認只有 `+` 無 `-`）。
3. **`TradingRadarNotificationState.java`**：新增 `lastNotifiedAt`（`Instant`，`@Column(name = "last_notified_at")`）。型別用 `import java.time.Instant;` + 裸型別，而非任務檔片段中的行內全限定 `java.time.Instant`——純風格差異，語意與欄位/型別/欄名完全相同。
4. **`TradingRadarNotificationStateRepository.java`**：新增 `List<TradingRadarNotificationState> findBySettingId(Long settingId)`（Spring Data 衍生查詢，無需 `@Query`）；既有 `findStateCodes`／`deleteBySettingId` 原樣保留。
5. **`TradingRadarNotificationService.java`**：
   - 新增 `static final Duration NOTIFY_COOLDOWN = Duration.ofMinutes(60);`（package-private，javadoc 註明判斷性取值）。
   - 新增純函式 `static boolean cooldownActive(Instant lastNotifiedAt, Instant now)`。
   - `evaluateSettingSafely`：基準更新（`setting.setLastAction` 等 + `settingRepo.save`）維持在冷卻判斷**之前**、不受影響。`result.shouldNotify()` 為 true 時才呼叫一次 `stateRepo.findBySettingId(...)` 取整份訂閱列，對 `actionEntered`／`counterTrendEntered` 各自呼叫新增的私有 helper `notifyAllowed(rows, stateType, stateCode, settingId, now)`：查無對應列 → log warn 並視同無冷卻放行（不拋錯）；查有 → `cooldownActive` 為 true 則抑制、為 false 則寫入 `lastNotifiedAt=now` 並 `stateRepo.save(row)` 後放行。兩狀態共用同一個 `now`／同一份 `rows`，但各自獨立判斷；`entered` 清單全空時不呼叫 `dispatcher.enqueue`。
   - 未新增 `@Scheduled`，沿用既有 `runCycle`；未動 `SchedulePublicBffController`。

### 測試

- **301.5 (a)–(d)**：加進既有 `TradingRadarNotificationTransitionTest.java`（同語意群）：`cooldownActiveIsFalseWhenNeverNotified`、`cooldownActiveWithin59MinutesIsTrue`、`cooldownActiveAfter61MinutesIsFalse`、`cooldownActiveAtExactly60MinutesIsFalse`（恰 60 分邊界驗證 `compareTo < 0` 排除相等）。
- **301.5 (e)–(g)**：新建 `TradingRadarNotificationServiceTest.java`（Mockito，`@MockitoSettings(strictness = LENIENT)`，比照 `TradingRadarRefreshServiceTest` 風格），mock `settingRepo`／`stateRepo`／`snapshotRepo`／`tradingRadarService`／`transition`／`dispatcher`／`self`，透過公開的 `queueEvaluation` + `flushEvaluations` 驅動私有的 `evaluateSettingSafely`：
  - `首次轉入無冷卻紀錄時通知並寫入lastNotifiedAt`：`lastNotifiedAt=null` → enqueue 且該列被寫入非 null。
  - `冷卻窗內再轉入同狀態不通知但基準仍更新`：`lastNotifiedAt` 5 分鐘前 → 不 enqueue、該列 `lastNotifiedAt` 未被覆寫，但 `setting.lastAction` 仍更新且 `settingRepo.save(setting)` 有被呼叫。
  - `冷卻窗外再轉入時恢復通知`：`lastNotifiedAt` 61 分鐘前 → enqueue 且該列 `lastNotifiedAt` 被更新為晚於原值的新時間。
  - `StockDecision` 用其既有的 39 參數相容建構子（`TradingRadarDto.java` 中「供既有測試建構資料」那個）組資料，這是本任務第一次真正使用到該建構子。

**與原計畫的偏差**：
1. 型別宣告用 import 而非行內全限定 `java.time.Instant`（風格差異，見上）。
2. (e)-(g) 放進新檔 `TradingRadarNotificationServiceTest.java` 而非塞進 `TradingRadarNotificationTransitionTest.java`——任務檔原文對 (a)-(d) 明講「加入既有 TransitionTest」，對 (e)-(g) 只講「service 層（Mockito，比照既有測試模式）」，判讀為比照 `TradingRadarRefreshServiceTest` 另開一支 Mockito service 測試檔；兩檔命名皆符合驗證指令的 `TradingRadarNotification*Test` glob。
3. 301.1 建議的 `docker exec asset-postgres psql ...` 撞號比對未執行（鐵則 3 禁跑 docker），改以檔案系統層級 `ls | grep` 確認替代，理由見上。
4. 「驗證」段落中部署後的 `docker logs`／`psql \d` 兩項照任務檔本文即屬於「部署後（整波，見 t305）」，非本任務範圍，未執行。

**更新過期望值的測試清單**：無。本任務為純新增的 email 抑制邏輯，未變更任何既有的決策/動作輸出語意，所有既有測試原樣通過，未修改任何既有測試的斷言或期望值。

### 驗證輸出摘要

- 定向測試 `-Dtest='TradingRadarNotification*Test'`：`TradingRadarNotificationServiceTest` 3/3 通過；`TradingRadarNotificationTransitionTest` 9/9 通過（5 舊 + 4 新）。
- **整套 backend 測試**：`mvn -f backend/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true`，退出碼 0；彙總 73 個測試類、**619 個測試，Failures: 0，Errors: 0，Skipped: 0**，全綠。（過程中終端機曾印出 `GdriveOutputSupportTest`／`TradingCalendarDualFormatTest` 的 WARN/ERROR 級 log，經核對對應 surefire report 均為 0 failures/0 errors——那是測試刻意觸發錯誤路徑的預期輸出，非真失敗。）

### 給下一棒（t302，同一 service 檔）的注意事項

- `TradingRadarNotificationService.java` 新增了 `notifyAllowed`／`cooldownActive`／`NOTIFY_COOLDOWN` 三個成員，並改動了 `evaluateSettingSafely` 尾段（`if (result.shouldNotify())` 區塊）；t302 若也要動同一段，請以目前工作區內容為準、用識別字定位，不要按任務檔舊行號。
- `evaluateSettingSafely` 目前結構：baseline 更新（不受冷卻影響）→ `if (shouldNotify())` 內才查 `stateRepo.findBySettingId` 並逐狀態判冷卻 → `entered` 非空才 `dispatcher.enqueue`。t302（"radar_notification_market_once"，看檔名像是要讓同輪多檔時某件事只做一次）若要插入邏輯，建議放在 baseline 更新之後、冷卻判斷之前或之後視語意而定。
- `TradingRadarNotificationState` 的 `@Builder`／`@AllArgsConstructor` 已因新欄位變成 8 參數；目前除了新測試檔外，唯一其他建構點是 `TradingRadarNotificationSettingService.java` 的 `.builder()` 呼叫，未受影響（builder 對新欄位是可選的，省略即為 null）。
- `spec/tasks/t301_radar_notification_cooldown.md` 的「完成報告」區塊本身未被我寫入（依鐵則 2），內容已整理在本結構化輸出的 report 欄位，由主 agent 回填。

> 補記（arch-auditor 查證後）：`TradingRadarNotificationState.lastNotifiedAt` 的 javadoc 原寫「成功寄出」，與實際寫入時點（enqueue 放行當下、非寄達確認）語意不符，已由主 agent 改為「上次放行派送（enqueue）的時間」。行為未變，符合任務「冷卻只擋 email」的定義。
