# [t252] 系統時區基準統一為台北：容器 TZ ＋ 4 處市場時區修正 ＋ 17 欄歷史資料校正

**對應 Requirements:** Requirement 53（系統時區基準統一為台北——畫面上每個時間都是台北時間、警示冷卻期是真的 24 小時，全系統「現在幾點」只有一個答案）
**前置任務:** 無
**Liquibase changeset:** `v1.79.0-naive-timestamp-to-taipei.sql`

## 背景

三個 JVM 容器（business-services／external-materials-service／bff）的 `TZ` 從未設定，base image `eclipse-temurin:21-jre-alpine` 無 `/etc/timezone`，JVM 預設時區為 **UTC**。於是所有**未帶 ZoneId** 的 `LocalDateTime.now()` / `LocalDate.now()` 產生 UTC 牆鐘，寫進 `timestamp without time zone`（不帶時區資訊），前端原字串顯示 → 使用者看到的時間**恆少 8 小時**。

而「需要非台北時區」的地方**早已 100% 顯式化**（46 個帶 `zone` 的 `@Scheduled`、`MarketZones`、`MarketClock`、7 支匯出排程各自的 `TW_ZONE`）。所以 JVM 預設時區的唯一作用，就是決定那些沒寫 zone 的呼叫落在哪裡——而 UTC 是全系統唯一沒有任何人想要的時區。

**本任務要修的不只是顯示。** 有一個正在漏發警示的 bug：`StockAlertService` 的 24 小時冷卻，左側 `lastTriggeredAt` 是**市場牆鐘**、右側是 **JVM 牆鐘**，兩個不同時鐘直接比較，實際冷卻長度 = `24h ± 市場 offset`：

| 市場 | 實際冷卻 | 後果 |
|---|---|---|
| 台股 | **32 小時** | 早上觸發後，**隔天整個交易日（13:30 收盤前）都還在冷卻**，靜默漏發 |
| 英股 | 25 小時 | 隔天開盤後約 1 小時才解除，影響小 |
| 美股 | 20 小時 | 提早解除，仍跨夜，影響小 |

DB 硬證據（`stock_alert` 同一 transaction 寫入的兩欄）：

```
 id | code | market | last_triggered_at   | updated_at          | 差
 65 | AMZN | 美股   | 07-28 12:00:02.360 | 07-28 16:00:02.364 | -4.0000009 h   ← 紐約 vs UTC
 21 | VOO  | 美股   | 07-28 09:40:05.604 | 07-28 13:40:05.606 | -4.0000007 h
```

**只改 TZ 不改這行會讓情況更糟**：台股被修好（32h→24h），但美股惡化成 12h、英股 17h（台北牆鐘比紐約快 12h、比倫敦快 7h）→ 同一封警示信一天內重複寄兩次。

## 現況事實（皆已實測，實作前提）

**容器與 DB**

```
$ date                                          → Wed Jul 29 02:39:54 CST 2026   (host, UTC+8)
$ docker exec asset-business-services date      → Tue Jul 28 18:39:55 UTC 2026
$ docker exec asset-business-services sh -c 'echo TZ=$TZ; cat /etc/timezone'
                                                → TZ=（空）  cat: /etc/timezone: No such file
$ docker exec asset-postgres psql -U assets -d assets -c "SHOW timezone"  → UTC
$ grep -n -i "TZ\b\|timezone" docker-compose.yml → 零命中
```

**PostgreSQL 的 timezone 已被 initdb 寫進資料目錄，環境變數贏不過它**：

```
$ docker exec asset-postgres sh -c 'grep -n "^timezone\|^log_timezone" /var/lib/postgresql/data/postgresql.conf'
608:log_timezone = UTC
722:timezone = UTC        ← source = configuration file
```
→ 必須用 `command: ["postgres","-c","timezone=Asia/Taipei","-c","log_timezone=Asia/Taipei"]`，**不可只設 `TZ` 環境變數**。

**tzdata 在三個 image 內都齊全**（`ls /usr/share/zoneinfo/Asia/Taipei` 皆存在），故只需 compose 加 `TZ`，**不必改 Dockerfile、不必加 `-Duser.timezone`**。

**排程（用 `grep -ran "^[[:space:]]*@Scheduled"` 精算，不是 grep 整行）**

```
真正的 @Scheduled 標註：49    帶 zone：46    沒帶 zone：3
沒帶 zone 的三個全是 fixedDelay/fixedDelayString（Spring 對它們根本不讀 zone 屬性）：
  AlertNotificationDispatcher.java:88   @Scheduled(fixedDelay = 60_000L, initialDelay = 60_000L)
  TradingRadarNotificationService.java:63  @Scheduled(fixedDelay = 2_000L, initialDelay = 2_000L)
  MarketAnalysisScheduler.java:71       @Scheduled(fixedDelayString = "90000", ...)
```
→ **改 TZ 後沒有任何一支排程的觸發時刻會位移**（Spring 的 `zone` 覆蓋 JVM 預設）。運行中日誌實證：`ClosePersister` 英股 `0 32 16` zone=Europe/London 於 `2026-07-28T15:32:00.039Z` 觸發（＝倫敦 16:32 BST）。
→ **本任務不新增、不修改任何 `@Scheduled`，故不需同步 `SchedulePublicBffController.JOBS`。**

**DB 的 49 個 naive 欄位分四種時鐘**（分類以寫入端程式碼為準，並以實值交叉驗證：把每欄 `max()` 減去 `now() AT TIME ZONE 'UTC'`，台北那組落在 +5～+8h、UTC 那組落在 0～−1h）

```
 trading_radar_export_setting.last_run_at    2026-07-29 09:10:00   +7.15h  ← 台北牆鐘
 export_schedule_setting.last_run_at         2026-07-29 08:00:04   +5.99h  ← 台北牆鐘
 backup_record.created_at                    2026-07-29 07:00:00   +4.99h  ← 台北牆鐘
 trading_radar_notification_setting.updated_at 2026-07-29 01:54:02  −0.11h  ← UTC
 fund_nav.fetched_at                         2026-07-29 01:00:00   −1.01h  ← UTC
 stock_alert.updated_at                      2026-07-28 19:12:58   −6.80h  ← UTC
 notification_recipient.updated_at           2026-07-28 18:31:46   −7.48h  ← UTC
 stock_alert_trigger.created_at              2026-07-28 16:00:02  −10.01h  ← UTC
 stock_alert_trigger.triggered_at            2026-07-28 12:00:02  −14.01h  ← 市場牆鐘（美股 ET）
```
同一張 `stock_alert_trigger` 的 `created_at`(UTC 16:00) 與 `triggered_at`(ET 12:00) 差 4 小時，就是兩種時鐘並存的直接證據。

**寫入端（已逐檔 grep 確認）**

- 裸 `LocalDateTime.now()` 在 **entity 層**只出現在這 5 支（`@PrePersist`/`@PreUpdate`）：
  `AppUser.java:75,82`、`StockAlert.java:84,88,92`、`StockAlertTrigger.java:68`、`NotificationRecipient.java:71,78`、`TradingRadarNotificationSetting.java:69,76`
- **service 層**唯一一處裸寫：`MarketAnalysisService.java:286` `s.setUpdatedAt(LocalDateTime.now())`
- 7 張匯出排程表的 `updated_at` **不是** entity `@PreUpdate`，而是 service 層 `LocalDateTime.now(TW_ZONE)`（`ExportScheduleService.java:110`、`ExchangeRateExportScheduleService.java:130`、`IndexExportScheduleService.java:129`、`CommodityExportScheduleService.java:126`、`TradingCalendarExportScheduleService.java:102`、`RealizedGainExportScheduleService.java:110`、`AssetTransactionExportScheduleService.java:110`），各自宣告 `private static final ZoneId TW_ZONE = ZoneId.of("Asia/Taipei")` → **已是台北牆鐘，絕對不可動**
- `BackupService.java:108` 用 `DISPLAY_ZONE`（= Asia/Taipei）→ 不可動

**要改的四處程式碼的當前行號**（已於 merge `origin/main`（含 t249／t250）之後重新定位）

```
StockAlertService.java:272   alert.getLastTriggeredAt().isAfter(LocalDateTime.now().minusHours(24))
StockAlertService.java:613   LocalDate today = java.time.LocalDate.now();          （withTodayIfMissing）
TechnicalIndicatorService.java:65    LocalDate today = LocalDate.now();            （個股）
TechnicalIndicatorService.java:149   LocalDate today = LocalDate.now();            （台股大盤）
PriceQueryService.java:250   LocalDateTime.now(ZoneId.systemDefault()).toString()
```

`StockAlertService.java:359`（`LocalDate.now().minusYears(2), LocalDate.now()`）與 `:828`（`minusDays(30)` 清理 cutoff）**不改**：前者是查詢區間界、差一天不改變結果；後者切換後 cutoff 與 `stock_alert_trigger.created_at`（本任務會 +8h）**同步**變台北，保留期仍精準 30 天。

## 要做什麼

> **三段必須同一個 commit、同一次 build、同一次 `up -d`。** 拆開部署會產生半套狀態：只改 TZ 不改冷卻 → 美股警示 12 小時就重複寄信；只跑 migration 不改 TZ → 所有時間顯示變成**超前** 8 小時。

### 第 1 段：容器設定

- [ ] 252.1 `docker-compose.yml`：`business-services`、`external-materials-service`、`bff` 三個 service 的 `environment` 各加一行 `TZ: ${APP_TZ:-Asia/Taipei}`。用 `${APP_TZ:-...}` 形式是為了保留「覆寫環境變數即可回退」的能力。
- [ ] 252.2 `docker-compose.yml`：`postgres` service 加
      `command: ["postgres","-c","timezone=Asia/Taipei","-c","log_timezone=Asia/Taipei"]`。
      **不可改用 `TZ` 環境變數**——實測 `postgresql.conf` 已被 initdb 寫死 `timezone = UTC`（`source=configuration file`），環境變數贏不過它。
- [ ] 252.3 `docker-compose.yml`：`frontend` 加 `TZ: Asia/Taipei`（僅 nginx access log 可讀性）。**`redis` 不設**（無時間語意）。

### 第 2 段：程式碼修正（不改就會被 TZ 切換弄壞，或修不好）

- [ ] 252.4 `backend/.../util/MarketZones.java` 新增兩個 static helper，作為所有「市場今日／市場現在」的唯一入口（避免下面三處各寫一遍）：
      ```java
      /** 該市場時區的今日。涉及交易日判定一律用本方法，禁用裸 LocalDate.now()。 */
      public static LocalDate today(String market)        { return LocalDate.now(resolve(market)); }
      /** 該市場時區的現在（牆鐘）。 */
      public static LocalDateTime nowLocal(String market) { return LocalDateTime.now(resolve(market)); }
      ```
- [ ] 252.5 `StockAlertService.java:272` —— **本任務最重要的一行**。冷卻判定右側改為同一市場的牆鐘：
      ```java
      // 改前：右側是 JVM 牆鐘，左側 lastTriggeredAt 是市場牆鐘（computeTriggeredAt 寫入）
      alert.getLastTriggeredAt().isAfter(LocalDateTime.now().minusHours(24))
      // 改後
      alert.getLastTriggeredAt().isAfter(MarketZones.nowLocal(alert.getMarket()).minusHours(24))
      ```
      註解須寫明「兩側必須同為該股市場的牆鐘；此處若用 JVM 牆鐘，冷卻長度會變成 24h ± 市場 offset」。
- [ ] 252.6 `StockAlertService.java:613`（`withTodayIfMissing`）：`java.time.LocalDate.now()` → `MarketZones.today(market)`。
- [ ] 252.7 `TechnicalIndicatorService.java:65`：`LocalDate.now()` → `MarketZones.today(market)`；
      `:149`（台股大盤，無 market 參數）→ `LocalDate.now(MarketZones.TW_ZONE)`。
      **為什麼非改不可**：這兩處拿 `today` 去比對 Redis／DB 的 `tradingDate`。現況是「湊巧對」——三個市場的交易時段在 UTC 日期上都與其交易日重合；改成台北後，**美股在台北 00:00–04:00（＝ET 12:00–16:00，含收盤前最關鍵的 4 小時）會算出 `today = D+1`** 而 Redis 的 `tradingDate = D`，兩個 `equals` 同時不成立 → 今日即時價完全不併入序列 → MA20/60/240 與 KD 全部用舊序列算，警示判定與畫面指標同時失真。
- [ ] 252.8 `PriceQueryService.java:250`：`LocalDateTime.now(ZoneId.systemDefault())` → `LocalDateTime.now(MarketZones.TW_ZONE)`。切換後 `systemDefault()` 本來就等於台北，但這是顯示用時間戳，**明示優於隱式**，也讓這行不再跟著 JVM 漂。

- [x] 252.8b **BFF 的第 5 處（對抗式審查補抓）**：`SnapshotFormBffController.java:106`
      `boolean isToday = date.equals(LocalDate.now().toString())` → `LocalDate.now(TW_ZONE)`（檔內新增該常數）。
      右側 `date` 是**前端送來的**，前端原本用 `new Date().toISOString().slice(0,10)`（UTC 日期）；
      兩邊碰巧都是 UTC 才一致，後端一改台北就會在台北 00:00–08:00 分家 → 判定不是「今天」→ 不刷即時匯率
      → 美英股部位用舊 USD 匯率換算。**必須與 252.8c 同批改**，只改一邊只是換個方向錯。
- [x] 252.8c **前端「今天」的產生方式**（推翻原 AC 的「前端零改動」）：新增
      `frontend/src/utils/localDate.js`（`todayLocal()` / `toLocalDateString()`，用
      `toLocaleDateString('sv-SE')` 取本地時區的 `yyyy-MM-dd`），並改三處使用者可見的預設日期：
      `TransactionView.vue:534`、`RealizedGainView.vue:661`、`SnapshotFormView.vue:2571`，
      以及 `SnapshotFormView.vue:1796` 的 Date→字串轉換。
      **顯示端仍是零改動**——後端改吐台北牆鐘後既有的字串切割與 `dayjs` 自動變正確；
      **不得**在前端新增任何 +8 補償，那會造成雙重補償。
- [x] 252.8d **兩個市場牆鐘欄位加 entity 註解**（requirements／design 都要求，原任務檔漏列）：
      `StockAlert.lastTriggeredAt` 與 `StockAlertTrigger.triggeredAt` 的 javadoc 須寫明
      「存該股市場的牆鐘、是台北牆鐘規則的唯二例外、不可納入任何時區校正 migration」，
      並在前者註明「冷卻判定必須用 `MarketZones.nowLocal(market)`」。
- [x] 252.8e `PriceQueryService.java:256` 的 `public LocalDate today() { return LocalDate.now(); }`
      零呼叫端（`grep -ran` 確認）且違反本任務新立的規則，直接刪除。

### 第 3 段：一次性歷史資料校正

- [x] 252.9 新增 `backend/src/main/resources/db/changelog/changes/v1.79.0-naive-timestamp-to-taipei.sql`，
      並於 `db.changelog-master.yaml` 尾端以 block 樣式 include。

      **只校正 11 欄**（原規劃 17 欄，經對抗式審查逐欄查證寫入端後砍到 11）：

      | 寫入端型態 | 欄位 | 位移？ | 處置 |
      |---|---|---|---|
      | entity `@PrePersist`/`@PreUpdate` 裸 `LocalDateTime.now()` | `app_user`×2、`notification_recipient`×2、`stock_alert.created_at/updated_at`、`stock_alert_trigger.created_at`、`trading_radar_notification_setting`×2（9 欄） | 會 | **+8h** |
      | service 層裸寫（`MarketAnalysisService`） | `market_analysis_setting.updated_at` | 會 | **+8h** |
      | SQL `NOW()`（ext `StockSourceQuery`） | `stock_dividend_history.updated_at` | 會 | **+8h** |
      | JdbcTemplate `Timestamp.from(instant)`（ext） | `crawler_export_setting.gdrive_last_run_at`、`fund_nav.fetched_at`、`fund_dividend_history.fetched_at` | 會 | **改寫入端（252.9b），歷史不動** |
      | Hibernate `Instant`（business JPA） | `crawler_export_setting.updated_at`、`crawler_schedule.updated_at`、`market_analysis_send_time.created_at` | **不會** | **絕對不可動** |

      **判準是「寫入端怎麼繫結」，不是 Java 型別。** Hibernate 6 對 `Instant` 走
      `TimestampUtcAsJdbcTimestampJdbcType`，bind／extract 兩側都帶 UTC Calendar、時區中立；
      對這三欄 `+8` 會把目前正確的顯示**永久改壞**。而 ext 的 `Timestamp.from(instant)` 經
      `ps.setTimestamp()` 不帶 Calendar，依 JVM 預設時區換算——那是寫入端的破口，改歷史值只會把舊列一起弄錯。

      **同一欄兩個寫入端的陷阱**：`crawler_export_setting.gdrive_last_run_at` 由 business 的 Hibernate
      （`Instant`，UTC 中立）與 ext 的 JdbcTemplate（隨 JVM 時區）各寫一次。切換前兩者碰巧都產生 UTC 牆鐘
      所以一致，切換後會分家 8 小時。

      changeset 的其他約束：id **絕對不可改名**（改名＝Liquibase 視為新 migration 重跑＝資料變 +16 小時）；
      附 `--rollback` 區塊；**刻意不設日期 cutoff**（Liquibase 於 Web 層與排程啟動前執行、ext 又以
      `depends_on: service_healthy` 等待 business，執行當下全表皆為舊值；寫死 cutoff 反而會在部署延後時
      漏掉那幾天的新列）；`DO` 區塊的 guard 必須**同時檢查表與欄位存在**且與 `UPDATE` 用同一個 schema 判準
      （只擋表不擋欄位的話，遇到部分還原的庫會擲 `column does not exist` → changeset 失敗 → business 起不來）。

- [x] 252.9b **ext 的 JdbcTemplate 寫入端改為顯式 UTC 繫結**（不改的話切換後與 Hibernate 端分家）：
      `CrawlerExportPathQuery.java:99` 與 `FundNavSourceQuery.java:53,57,73,77` 的
      `Timestamp.from(now)` → `LocalDateTime.ofInstant(now, ZoneOffset.UTC)`（pgjdbc 對 `LocalDateTime`
      是牆鐘原樣寫入，時區中立，與 Hibernate 的 `Instant` 語意對齊）。

- [x] 252.9c **Redis payload 的 `updatedAt` 改為顯式台北**：
      `PriceCacheWriter.java:84,164,233` 與 `EtfNavCacheWriter.java:64` 的
      `LocalDateTime.now()` → `LocalDateTime.now(MarketClock.TW_ZONE)`。
      這個值前端直接顯示，且 `StockPriceService:128-132` 會**跨 key 取 max**——若跟著 JVM 預設時區跑，
      切換當下新舊 tick 會是兩種基準，max 恆被先覆寫的那一筆鎖住（顯示反而超前，不是單純的早 8 小時）。

### 第 4 段：防迴歸

- [ ] 252.10 `backend/pom.xml` 與 `external-materials-service/pom.xml` 加 surefire 設定，讓測試 JVM 時區與正式一致（兩份 pom 目前皆無 argLine／user.timezone 設定）：
      ```xml
      <properties>
          <extraArgLine></extraArgLine>   <!-- 供命令列疊加 -->
      </properties>
      ...
      <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-surefire-plugin</artifactId>
          <configuration>
              <argLine>-Duser.timezone=Asia/Taipei ${extraArgLine}</argLine>
          </configuration>
      </plugin>
      ```
      **必須用 `${extraArgLine}` property 疊加，不可只寫死 argLine**：surefire 的 `argLine` 只能有一個值，
      而本機 JDK 為 25 時跑 Mockito 需要 `-Dnet.bytebuddy.experimental=true`；若從命令列下
      `-DargLine=...` 會**整個覆蓋**掉時區設定（實測：這樣跑會出現 181 個
      `Mockito cannot mock this class: JdbcTemplate` 錯誤，因為 byte-buddy 參數與時區參數互相擠掉）。
      正確用法是 `mvn test -DextraArgLine=-Dnet.bytebuddy.experimental=true`。
- [ ] 252.11 新增 `backend/src/test/java/com/steven/assets/util/MarketZonesTest.java`（純 JUnit 5，不需 Mockito）：
      - `today("美股")` 與 `today("台股")` 在同一瞬間可能不同日——用固定 `ZonedDateTime` 驗證換算關係，斷言美股今日 = 台北時間減 12～13 小時後的日期（不要寫成依賴執行當下時刻的脆弱斷言）。
      - `nowLocal("台股")` 與 `LocalDateTime.now(ZoneId.of("Asia/Taipei"))` 的差距在 1 秒內。
      - `today(null)` / `today("不存在的市場")` 的行為與既有 `MarketZones.resolve` 的 fallback 一致（先讀 `resolve` 的實作再寫斷言，不要假設）。

### 第 5 段：SDD 同步（已完成，列此供驗收）

- [x] 252.12 `spec/requirements.md` 新增 Requirement 53；`CLAUDE.md` 與 `spec/steering/structure.md` 的「52 個 Requirements」同步改為 53（`scripts/spec-check.sh` 的 B7 會擋計數漂移）。
- [x] 252.13 `spec/design.md` 新增「系統時區基準」章節（三條規則、四分類表、症狀 A/B、不受影響清單）。
- [x] 252.14 `spec/steering/tech.md:58` 改寫——原句「不用 JVM 預設時區」在本任務後語意改變（JVM 預設時區從此有明確定義）。

## 驗證

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -f backend/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

部署（三段必須同一次；JVM service 一律 `--no-cache`，否則 cached layer 可能不含本次變更）：

```bash
docker compose -p asset-management build --no-cache business-services external-materials-service bff
```

```bash
docker compose -p asset-management up -d --force-recreate business-services external-materials-service bff frontend postgres
```

**切換前先存證**（用來逐列比對是否正好 +8h）：

```bash
docker exec asset-postgres psql -U assets -d assets -c "SELECT id, created_at, updated_at FROM notification_recipient ORDER BY id;" > /tmp/tz-before.txt
```

切換後逐項驗收：

```bash
docker exec asset-business-services date
```

```bash
docker exec asset-postgres psql -U assets -d assets -At -c "SHOW timezone"
```

```bash
docker exec asset-postgres psql -U assets -d assets -c "SELECT id, created_at, updated_at FROM notification_recipient ORDER BY id;"
```

驗收清單：

1. `docker exec asset-business-services date` → `CST`；`external-materials-service`、`bff` 同驗
2. `SHOW timezone` → `Asia/Taipei`
3. `notification_recipient` 逐列與 `/tmp/tz-before.txt` 比對，正好 **+8h**
4. 前端「警示通知設定」與「股票警示」頁的「建立時間」與 host `date` 同日同小時
5. **「爬蟲資訊查詢」頁的「Drive 最後上傳」不得往前跳**：該欄由 ext 的 JdbcTemplate 寫入、business 的 Hibernate（Instant，UTC 中立）讀出。252.9b 已把寫入端改為顯式 UTC 繫結，兩端對齊 → 顯示應維持正確。**若往前跳 8 小時，代表 252.9b 沒生效**（例如 ext image 命中 build cache）
6. 排程觸發的**絕對時刻不變**：等下一次 `ClosePersister` 英股 cron，log 應出現在台北 23:32（＝倫敦 16:32 BST，與切換前的 `15:32Z` 是同一瞬間）
7. 「排程列表」頁的 46 筆 cron 顯示與時區欄一字不變
8. 盤中觀察一輪，前端「行情更新」時間戳翻成台北（Redis 過渡期驗收，見風險）

## 風險

- **【過渡期】Redis 既有 payload 的 `updatedAt`** 是切換前寫入的 UTC 字串，而新 tick 會是台北牆鐘（252.9c 改為顯式 `MarketClock.TW_ZONE`）。`StockPriceService:128-132` 對持股清單**跨 key 取 max**，故過渡期只要任一檔被新 tick 覆寫，該 max 就恆由台北那筆決定——**顯示會超前而非落後**，直到全部 key 被覆寫。盤中 2 分鐘自癒；**非交易時段切換則會停留到隔天開盤**。不可用清 Redis 解決——那會失去 last tick 價格，違反既有的「抓不到就維持上一個 tick、禁用 `o`/`y` 回寫充數」規則。
- **【部署順序】必須先停 ext，再重建 business，最後才起 ext**：compose 依 `depends_on` 重建時，**舊的 ext 容器（TZ 仍為 UTC）在 business 重啟跑 migration 的期間仍在運行**。若它剛好在 UPDATE 完成後、自己被重建前寫入（`stock_dividend_history.updated_at` 走 SQL `NOW()`），那幾列會停在 UTC 值（**少**校正、非多校正），下次寫入時自癒。正確順序：
  ```
  docker compose -p asset-management stop external-materials-service
  docker compose -p asset-management up -d --force-recreate business-services   # Liquibase 在此跑
  docker compose -p asset-management up -d --force-recreate external-materials-service bff frontend
  ```
- **【不可重跑】** v1.79.0 重跑 = 資料變 +16 小時。上線前確認 `SELECT id FROM databasechangelog WHERE id LIKE 'v1.79.0%'` 為空。
- **【半套狀態】** TZ 與程式碼修正若不同時生效（例如 JVM image 命中 build cache 沒帶上程式碼），會出現「TZ 已改但冷卻邏輯沒改」→ 美股警示 12 小時重複寄信。故 `--no-cache` 重 build 後須 `unzip -p` 驗 jar 內含新 class。
- **【日誌斷層】** 切換後應用與 postgres 日誌時間戳從 UTC 變 +08:00，事故追查比對切換前後需手動換算 8 小時。repo 內無自訂 `logback*.xml`，用 Spring Boot 預設 pattern。
- **【備份還原】** v1.79.0 之前的 Google Drive 備份還原回來會是**未校正的 UTC 值**，且 Liquibase 已記錄該 changeset 為已執行、不會重跑。處置：changeset 內的 `--rollback` 區塊反過來用（把 `-` 改成 `+`）即為手動補跑的校正 SQL。
- **【跨 worktree 洗掉】** 全機唯一一套 `asset-management-*:latest`，誰最後 build 誰生效。merge 進 main 後**須從 main 的 worktree 重建**，否則改動會被別的 session 靜默洗回舊版（症狀：DB 已 +8h 但程式碼是舊的 → 顯示超前 8 小時）。
- **【子程序繼承 TZ】** business 容器內以 ProcessBuilder 呼叫的 `pg_dump` 與 `rclone` 會繼承 `TZ=Asia/Taipei`。`pg_dump` 輸出的 timestamptz 會帶 `+08` offset（還原仍正確）；`rclone lsjson` 的 ModTime 是 RFC3339 帶 offset，`BackupService.parseRcloneTime` 用 `withZoneSameInstant(DISPLAY_ZONE)` 解析——兩者查證後認定安全，但**切換後應實跑一次手動備份驗證**。

## 完成報告

**與原計畫最重大的偏差：migration 欄位清單 17 → 11。**

原規劃把 6 個 `Instant` 欄位列入 `+8h`，理由是「Hibernate 依 JVM 預設時區換算」。**這個前提是錯的**，由對抗式審查以決定性實驗推翻——用 pgjdbc 對運行中 DB 跑同一個 `Instant`，只改 JVM 的 `TZ`：

```
TZ=UTC          Hibernate(Instant, UTC_CALENDAR) → naive 存入 02:00:00
                ext(Timestamp.from, 無 Calendar)  → naive 存入 02:00:00
TZ=Asia/Taipei  Hibernate                         → naive 存入 02:00:00   ← 一個字都沒變
                ext(Timestamp.from, 無 Calendar)  → naive 存入 10:00:00   ← 位移 +8
```

Hibernate 6 對 `Instant` 走 `TimestampUtcAsJdbcTimestampJdbcType`，`doBind`／`doExtract` 兩側都寫死 `UTC_CALENDAR`，與 JVM 時區無關。若照原清單執行，`crawler_export_setting` 那兩欄現在會永久超前 8 小時。**判準改為「寫入端怎麼繫結」，不是 Java 型別。**

**實際改動**

- 新增：`v1.79.0-naive-timestamp-to-taipei.sql`（11 欄）、`MarketZones.today()/nowLocal()`、`MarketZonesTest`、`frontend/src/utils/localDate.js`
- compose：3 個 JVM 服務 + frontend 加 `TZ: ${APP_TZ:-Asia/Taipei}`；postgres 加 `command` 設 timezone
- backend：`StockAlertService:272`（冷卻期）、`:613`、`TechnicalIndicatorService:65/:149`、`PriceQueryService:250`、刪除零呼叫端的 `PriceQueryService.today()`、2 個市場牆鐘欄位加 javadoc
- ext：`CrawlerExportPathQuery`／`FundNavSourceQuery` 的 `Timestamp.from` → `LocalDateTime.ofInstant(now, UTC)`；`PriceCacheWriter`×3／`EtfNavCacheWriter` 的 Redis `updatedAt` → 顯式 `MarketClock.TW_ZONE`
- bff：`SnapshotFormBffController:106` 的 `isToday` → `LocalDate.now(TW_ZONE)`
- frontend：3 處「今天」預設值 + 1 處 Date→字串改用 `localDate.js`
- pom ×2：surefire `-Duser.timezone=Asia/Taipei ${extraArgLine}`

**驗證輸出**

- backend `Tests run: 283, Failures: 0, Errors: 0`；ext `Tests run: 116, Failures: 0, Errors: 0`（皆在 `-Duser.timezone=Asia/Taipei` 下）
- 三個 JVM 容器 `date` → `CST`；`SHOW timezone` → `Asia/Taipei`；三服務 ERROR 各 0 行
- migration：`v1.79.0-naive-timestamp-to-taipei ran successfully in 40ms`
- **校正正確性**：`notification_recipient` 逐列 +8h（`2026-06-05 18:53` → `2026-06-06 02:53`）；`crawler_export_setting.updated_at` 維持 `2026-07-27 14:33:06` **一個字未動**
- **症狀驗收（最關鍵）**：API 回 `gdriveLastRunAt: "2026-07-29 10:41:06"` 而當下台北 `10:41:39` → 顯示正確、無 8 小時斷層，證明 252.9b 的寫入端修正生效（未生效的話 DB 會寫成台北牆鐘、顯示變 18:41）
- **排程未位移**：`PricePoller` 於 `2026-07-29T10:42:00.007+08:00` 觸發（cron `zone=Asia/Taipei`，每 2 分鐘）
- **Redis `updatedAt`** = `2026-07-29T10:42:00`（台北牆鐘，252.9c 生效）

**流程偏差（如實記錄）**：實作在對抗式審查完成前即開工（違反 CLAUDE.md 第 4 步順序），原因是使用者要求推進。所幸審查的兩個 critical 都在**部署前**被修正，未造成資料損毀；但這個順序不應成為慣例——若審查晚到 20 分鐘，17 欄的 migration 就會先跑掉。

**未完成**

- 前端畫面確認需 Google 登入，代為認證不在可做範圍——「警示通知設定」「股票警示」頁的「建立時間」請自行目視
- 尚未 commit / merge
- 冷卻期修正（252.5）需等下一次台股警示觸發才能實地驗證；預期行為是「隔天同一時段即可再次觸發」而非現在的 32 小時
