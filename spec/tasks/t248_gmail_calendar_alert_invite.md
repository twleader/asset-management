# [t248] 警示通知 digest 對 Gmail 收件人夾帶 Google 日曆邀請（可逐一勾選）

**對應 Requirements:** Requirement 23（股票警示條件觸發時自動寄 email 給該警示選定且啟用的收件人，同輪多筆合併成單封 digest）
**前置任務:** 無
**Liquibase changeset:** `v1.77.0-notification-recipient-calendar.sql`

## 背景

現行警示通知只有 email 一條路：`AlertNotificationDispatcher.flush()` 每 60 秒 drain 一次 in-memory queue，以收件人為單位分組後，逐位寄出一封 HTML digest（內嵌年圖 + 當日分時圖）。email 的即時性受限於「使用者何時打開信箱」，手機上往往沒有推播。

本任務讓收件人可逐一開啟「加入 Google 日曆」：開啟後，寄給該收件人的警示 digest 額外夾帶一份 iCalendar（RFC 5545）邀請，Gmail / Google 日曆收到即自動建立事件，靠日曆本身的提醒推播到手機，達成近即時通知（延遲 ≈ 1~2 分鐘）。

**為什麼是 ics 邀請而不是 Google Calendar API**：收件人是別人的信箱（家人 / 副信箱），本系統拿不到他們的 OAuth 授權，Calendar API 無從代其建立事件；服務帳戶無自身日曆，個人 Gmail 也無法做 domain-wide delegation。夾帶 `METHOD:REQUEST` 的 ics 是唯一「不需收件人授權、又能自動落進日曆」的路徑，且完全走既有 SMTP，不新增憑證或對外相依。**既有 rclone 的 Google OAuth token（`/etc/rclone/rclone.conf` 的 `[GoogleDriver]` / `[GDriveOutput]`）scope 是 drive，與 calendar 無關，不可挪用。**

**已知前提（程式無法控制，只能在設定頁提示）**：收件人的 Google 日曆需維持「自動將邀請加入我的日曆」設定（Google 預設為「是」）；若設為「僅在我回覆時」，收件人得在信中手動接受一次才會進日曆。

**順帶修掉的既有缺陷**：dispatcher 目前以 **email 字串**為分組 key，而 `notification_recipient` 的唯一鍵是複合 `(owner_user_id, email)`——兩個租戶各自登記同一個 email 時，兩邊的觸發會被合併成**同一封信**寄出（跨租戶內容混寄）。本任務因為需要收件人的 `id` 與 `add_to_calendar`，順勢把分組單位改為 `recipientId`，同時關掉這條路徑（詳見 248.10）。

## 現況事實（實作前提，皆已於本次確認）

**DB（運行中 `asset-postgres`，`\d notification_recipient`）**：

| 欄位 | 型別 | Nullable | Default |
|------|------|----------|---------|
| id | bigint | not null | identity |
| email | varchar(255) | not null | — |
| active | boolean | not null | true |
| created_at | timestamp | not null | CURRENT_TIMESTAMP |
| updated_at | timestamp | not null | CURRENT_TIMESTAMP |
| owner_user_id | bigint | not null | — |
| receive_market_analysis | boolean | not null | true |

UNIQUE `uq_recipient_owner_email (owner_user_id, email)`；FK `fk_notification_recipient_owner → app_user(id)`；被 `stock_alert_recipient` 與 `trading_radar_notification_recipient` 以 `ON DELETE CASCADE` 參照。**沒有 `add_to_calendar` 欄位**（本任務新增）。

運行中 DB 的 `databasechangelog` 最新五筆為 `v1.76.0-gdrive-output-all-export-pages`、`v1.75.0-crawler-gdrive-output`、`v1.74.0-asset-transaction-price-scale`、`v1.73.0-asset-transaction-export-schedule`、`v1.72.0-asset-transaction`，與 main 的 `db.changelog-master.yaml` 尾端一致 → `v1.77.0` 為未被占用的下一個版號。

**既有程式（皆在 `backend/src/main/java/com/steven/assets/`）**：

- `model/NotificationRecipient.java`：`@Entity`、`@Table(name="notification_recipient", uniqueConstraints=@UniqueConstraint(name="uq_recipient_owner_email", columnNames={"owner_user_id","email"}))`、`@Filter(name="ownerFilter", condition="owner_user_id = :ownerId")`、Lombok `@Data @NoArgsConstructor @AllArgsConstructor @Builder`。既有欄位含 `Boolean active`（`@Builder.Default = true`）與 `Boolean receiveMarketAnalysis`（`@Column(name="receive_market_analysis", nullable=false)`、`@Builder.Default = true`）。`@PrePersist` / `@PreUpdate` 維護 `createdAt` / `updatedAt`。
- `dto/NotificationRecipientDto.java`：`Response(Long id, String email, Boolean active, Boolean receiveMarketAnalysis, LocalDateTime createdAt, LocalDateTime updatedAt)`、`CreateRequest(@NotBlank @Email String email, Boolean active)`、`UpdateRequest(@NotBlank @Email String email)`。
- `service/NotificationRecipientService.java`：`normalize(email)` = `trim().toLowerCase()`；`create` / `update` / `toggleActive` / `toggleMarketAnalysis` / `delete`，每支寫入前以 `tenantGuard.assertOwned(r.getOwnerUserId())` 縱深保護；`toResponse` 組 `Response`。
- `controller/NotificationRecipientController.java`：`@RequestMapping("/api/notification-recipients")`，已有 `GET /`、`POST /`、`PUT /{id}`、`PATCH /{id}/active`、`PATCH /{id}/market-analysis`、`DELETE /{id}`。
- `service/EmailService.java`：`sendHtml(List<String> recipients, String subject, String html, Map<String,byte[]> inlineImages)` — 內部 `mailSender.createMimeMessage()` + `new MimeMessageHelper(msg, true, "UTF-8")`（＝`MULTIPART_MODE_MIXED_RELATED`：root 為 `multipart/mixed`、其內含 `multipart/related`），`helper.setText(html, true)` 後 `helper.addInline(cid, new ByteArrayResource(bytes), "image/png")`；全程 try/catch，失敗只 `log.warn`。**`private String resolveFrom()`（`EmailService.java:109`，其依據的兩個 `@Value` 欄位 `:29`／`:32` 也是 private）** = `notification.email.from`（環境變數 `NOTIFICATION_FROM`）非空則用之，否則 `spring.mail.username`（`MAIL_USERNAME`）。`isEnabled()` = `MAIL_USERNAME` 非空白。**另有兩處呼叫 `sendHtml`：`MarketAnalysisEmailDispatcher:65` 與 `TradingRadarNotificationDispatcher:78`——不得改動其行為。**
- `repository/StockAlertRecipientRepository.java`：`findActiveEmailsByAlertId(Long)` 的 JPQL 原文為
  `SELECT r.email FROM StockAlertRecipient s, com.steven.assets.model.NotificationRecipient r, com.steven.assets.model.StockAlert a WHERE s.alertId = :alertId AND s.recipientId = r.id AND a.id = s.alertId AND r.ownerUserId = a.ownerUserId AND r.active = true`
  —— **只投影 `r.email`，收件人身分（id / owner）在投影當下就丟失**。其 javadoc 說明該 `r.ownerUserId = a.ownerUserId` 條件是 Task 145 為「背景 cron 無 request context → `ownerFilter` 不啟用」補的 defense-in-depth。
- `security/TenantFilterAspect.java:45-51`：`@Before("execution(* com.steven.assets.repository..*(..))")` 內
  `if (RequestContextHolder.getRequestAttributes() == null) { return; // 背景執行緒：不啟用，維持掃全體 }`
  —— 故 `@Scheduled` 中呼叫任何 repository 方法都**不套 `ownerFilter`**，會掃全租戶。
- `service/AlertNotificationDispatcher.java`：`@Scheduled(fixedDelay=60_000L, initialDelay=60_000L) flush()`；流程為 `drain()` → `withinSendWindow(market)` 過濾 → `groupByRecipient(sendable)`（回 `Map<String email, List<PendingTrigger>>`，內部以 `recipientLinkRepo.findActiveEmailsByAlertId(alertId)` 取 email、`emailCache` 以 alertId 快取）→ 逐位 `buildDigest(...)` 得 `DigestMail(String html, Map<String,byte[]> inlineImages, int stockCount)` → `subject = String.format("[資產管理] 股票警示觸發 %d 筆", mail.stockCount())` → `emailService.sendHtml(List.of(email), subject, mail.html(), mail.inlineImages())`。另有 `resendLastTradingDay(String market)`（手動補發，主旨為 `[資產管理] 股票警示補發 %d 筆`），同樣走 `groupByRecipient` + `buildDigest`。私有 record `PendingTrigger(Long alertId, String stockCode, String market, String stockName, String conditionLabel, LocalDateTime triggeredAt, BigDecimal price, BigDecimal monthlyMa, BigDecimal quarterlyMa, BigDecimal annualMa, BigDecimal kValue, BigDecimal dValue)`。
- `repository/NotificationRecipientRepository.java`：既有 `findAllByOrderByCreatedAtAsc()`、`findByEmail(String)`、`findByIdIn(Collection<Long>)`。

**前端**：

- `frontend/src/views/NotificationSettingsView.vue`：`el-table` 欄位為 Email / 狀態 / 建立時間 / 操作；`bffApi.notificationSettings.*` 取資料；新增 / 編輯 dialog 只有 email 一欄。
- `frontend/src/api/index.js:465-471`：`notificationSettings` 命名空間有 `getRecipients` / `createRecipient` / `updateRecipient` / `toggleActive` / `deleteRecipient`，全部打 `/bff/notification-settings/recipients...`。
- BFF `bff/src/main/java/com/steven/assets/bff/notificationsettings/NotificationSettingsBffRoutes.java`：`/api/bff/notification-settings/recipients/**` → rewrite → `/api/notification-recipients/**`，**已是 `/**` 萬用 passthrough，新端點不需改 BFF**。

## 要做什麼

### 資料層

- [x] 248.1 新增 `backend/src/main/resources/db/changelog/changes/v1.77.0-notification-recipient-calendar.sql`：
      `--liquibase formatted sql` + `--changeset steven:v1.77.0-notification-recipient-calendar`，內容為
      `ALTER TABLE notification_recipient ADD COLUMN IF NOT EXISTS add_to_calendar BOOLEAN NOT NULL DEFAULT FALSE;`。
      **必須冪等**（`IF NOT EXISTS`）——本專案多 worktree 共用一套運行中 DB，changeset 可能在別的分支已被套用。
      於 `db.changelog-master.yaml` **尾端**追加下列三行（block 樣式，與檔內既有 89 個 include 一致；**不要**寫成 flow 樣式的單行）：
      ```yaml
        - include:
            file: db/changelog/changes/v1.77.0-notification-recipient-calendar.sql
            relativeToChangelogFile: false
      ```
      **預設 FALSE**（不同於 `receive_market_analysis` 的 TRUE）：日曆事件會實際寫進別人的日曆，屬於明示同意才開的行為，不對既有收件人自動開啟。
- [x] 248.2 `NotificationRecipient` entity 新增
      `@Column(name="add_to_calendar", nullable=false) @Builder.Default private Boolean addToCalendar = false;`

### 後端 API 與網域限制

- [x] 248.3 `NotificationRecipientDto.Response` 新增 `Boolean addToCalendar`（放在 `receiveMarketAnalysis` 之後、`createdAt` 之前），`NotificationRecipientService.toResponse` 一併回填。
      `CreateRequest` / `UpdateRequest` **不加**此欄位——一律用專屬的 PATCH 切換（比照既有 `active` / `market-analysis` 的作法）。
- [x] 248.4 `NotificationRecipientService` 新增 `public static boolean isGmail(String email)`：email 為 null / 空白回 false；取最後一個 `@` 之後的字串（已 `normalize` 成小寫），等於 `gmail.com` 或 `googlemail.com` 才回 true。
- [x] 248.5 `NotificationRecipientService` 新增 `@Transactional toggleCalendar(Long id)`：`repo.findById` → `tenantGuard.assertOwned(r.getOwnerUserId())` → **若目前為 false 且 `!isGmail(r.getEmail())` 則丟 `IllegalArgumentException("僅 Gmail 收件人可加入 Google 日曆")`**（既有 `GlobalExceptionHandler` 會轉 400 ProblemDetail）→ 否則翻轉 `addToCalendar` 並存檔回 `Response`。
      關掉（true → false）不受網域限制，避免資料改壞後無法關閉。
- [x] 248.6 `NotificationRecipientService.update`：email 改成非 Gmail 時，**同一次寫入把 `addToCalendar` 設為 false**（在既有的 `r.setEmail(email)` 之後、`repo.save` 之前判斷），不留「非 Gmail 卻已開啟」的殘留狀態。
- [x] 248.7 `NotificationRecipientController` 新增 `@PatchMapping("/{id}/calendar") public NotificationRecipientDto.Response toggleCalendar(@PathVariable Long id)` → 委派 service。
      **BFF 不需改動**（既有 `/api/bff/notification-settings/recipients/**` 為萬用 passthrough）。

### iCalendar 產生

- [x] 248.8 新增 `backend/src/main/java/com/steven/assets/service/AlertCalendarInviteBuilder.java`（`@Component`，純字串組裝，**不引入任何 iCal 第三方函式庫**）：
      `public String build(String organizerEmail, String recipientEmail, Long recipientId, String subject, List<String> lines, Instant now)`，產出如下 VCALENDAR 字串：

      ```
      BEGIN:VCALENDAR
      VERSION:2.0
      PRODID:-//asset-management//alert//ZH-TW
      CALSCALE:GREGORIAN
      METHOD:REQUEST
      BEGIN:VEVENT
      UID:alert-{recipientId}-{now.toEpochMilli()}@asset-management
      DTSTAMP:{now，UTC yyyyMMdd'T'HHmmss'Z'}
      DTSTART:{now + 2 分鐘，秒歸零（truncatedTo(MINUTES) 後 +2 分），同格式}
      DTEND:{DTSTART + 15 分鐘}
      ORGANIZER;CN=資產管理系統:mailto:{organizerEmail}
      ATTENDEE;CUTYPE=INDIVIDUAL;ROLE=REQ-PARTICIPANT;PARTSTAT=ACCEPTED;RSVP=FALSE;CN={recipientEmail}:mailto:{recipientEmail}
      SUMMARY:{subject}
      DESCRIPTION:{lines 以 \n 串接（literal 反斜線 n，非真換行）}
      STATUS:CONFIRMED
      SEQUENCE:0
      TRANSP:TRANSPARENT
      BEGIN:VALARM
      ACTION:DISPLAY
      DESCRIPTION:股票警示觸發
      TRIGGER:-PT1M
      END:VALARM
      END:VEVENT
      END:VCALENDAR
      ```

      硬性約束：
      - **換行一律 CRLF（`\r\n`）**，含最後一行結尾。
      - **RFC 5545 escape**（套用於 `SUMMARY` / `DESCRIPTION` 的值）：`\` → `\\`、`;` → `\;`、`,` → `\,`、真換行 → `\n`。順序必須先處理反斜線。
      - **75 octet folding**：每一行輸出前檢查 UTF-8 位元組長度，超過 75 就折行——續行以單一半形空白開頭。**必須依 UTF-8 位元組數計算，且不可從多位元組字元中間切斷**（`DESCRIPTION` 內是中文，一字 3 bytes；按字元數折會超規、按位元組硬切會產生亂碼）。
      - `DTSTART` 用 `now.truncatedTo(ChronoUnit.MINUTES).plus(2, MINUTES)`，`DTEND` 為其 +15 分鐘；時間一律 UTC 並以 `Z` 結尾。`now` 由呼叫端傳入（可測）。
      - **`DTSTART` 必須在未來**：Google 對「開始時間已過」的事件不推播；事件在其提醒窗內被建立時（收件人日曆預設多為「10 分鐘前」）Google 會於加入當下立即推播。+2 分鐘同時涵蓋「ics 的 `VALARM` 生效」與「被收件人日曆預設提醒覆蓋」兩種情形。
      - **UID 每封信都要不同**（含 `recipientId` 與 epochMillis）：重複 UID 會被 Google 視為既有事件的更新而覆蓋前一次觸發、且不再推播。

### 寄送掛載

- [x] 248.9 `EmailService` 新增**多載** `sendHtml(List<String> recipients, String subject, String html, Map<String,byte[]> inlineImages, String icsContent)`；
      原四參數版本改為委派新版並傳 `null`（`MarketAnalysisEmailDispatcher` 與 `TradingRadarNotificationDispatcher` 的呼叫**不得改動、行為不得改變**）。
      新版在既有 `helper.setText(html, true)` 與 `addInline(...)` **之後**，若 `icsContent` 非空白則：
      ```java
      MimeBodyPart calPart = new MimeBodyPart();
      calPart.setDataHandler(new jakarta.activation.DataHandler(
              new jakarta.mail.util.ByteArrayDataSource(
                      icsContent.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                      "text/calendar; charset=UTF-8; method=REQUEST")));
      calPart.setHeader("Content-Transfer-Encoding", "8bit");
      helper.getRootMimeMultipart().addBodyPart(calPart);
      ```
      **不得改用 `calPart.setContent(icsContent, "text/calendar; ...")`**：JavaMail 的 `META-INF/mailcap` 未註冊 `text/calendar`（只有 text/plain、text/html、text/xml、multipart/\*、message/rfc822），`setContent` 會落到 `ObjectDataContentHandler` 的 String 分支、以 `Charset.defaultCharset()` 寫出而忽略宣告的 `charset=UTF-8`，中文摘要在非 UTF-8 預設編碼的 JVM 下變亂碼。顯式設過的 `Content-Transfer-Encoding` 不會被 `MimeBodyPart.updateHeaders` 覆寫。
      這一段**必須自帶 try/catch**：夾帶失敗只 `log.warn` 並**照常寄出不含 ics 的信**，不可讓整封信寄不出去。
- [x] 248.9b `EmailService.resolveFrom()` 由 `private` 改為 **`public`**（簽章與行為不變），供 dispatcher 取得 ics 的 `ORGANIZER`。
      **不得在 dispatcher 或任何他處重寫一份 `NOTIFICATION_FROM` → `MAIL_USERNAME` 的 fallback 判斷**——ORGANIZER 必須與實際 SMTP 寄件人**逐字一致**，Google 才會自動接受 `METHOD:REQUEST`；兩份判斷一旦不同步，功能會靜默失效且無任何 log。
- [x] 248.10 **`AlertNotificationDispatcher` 的收件人分組單位由 email 改為 recipientId**（夾帶 ics 的前置，且修掉一個既有的跨租戶混寄路徑）：
      - **不可**用 `NotificationRecipientRepository.findByEmail(email)` 反查收件人拿 `id` / `addToCalendar`。理由：`notification_recipient` 唯一鍵是複合 `(owner_user_id, email)`，**不同使用者可各自使用同一 email**；而 `flush()` 是 `@Scheduled`、無 HTTP request context，`TenantFilterAspect` 明文放行不套 `ownerFilter`（見上方「現況事實」引文）→ `Optional<NotificationRecipient> findByEmail` 在同 email 多列時丟 `IncorrectResultSizeDataAccessException`，單列時也可能取到**別的租戶**那一列（其 `id` 會被寫進 ics 的 UID、其 `addToCalendar` 會決定要不要夾帶）。Task 145 已為同一支 dispatcher 修過這個洞，不可從另一個入口重新打開。
      - 作法：`StockAlertRecipientRepository` 新增投影查詢（**保留既有的 `r.ownerUserId = a.ownerUserId` 與 `r.active = true` 條件，一字不動**），回傳 `id` / `email` / `addToCalendar`：
        ```java
        @Query("SELECT new com.steven.assets.dto.AlertRecipientTarget(r.id, r.email, r.addToCalendar) " +
               "FROM StockAlertRecipient s, com.steven.assets.model.NotificationRecipient r, " +
               "com.steven.assets.model.StockAlert a " +
               "WHERE s.alertId = :alertId AND s.recipientId = r.id AND a.id = s.alertId " +
               "AND r.ownerUserId = a.ownerUserId AND r.active = true")
        List<AlertRecipientTarget> findActiveTargetsByAlertId(@Param("alertId") Long alertId);
        ```
        新增不可變 record `repository/projection/AlertRecipientTarget(Long id, String email, Boolean addToCalendar)`（**新建 `com.steven.assets.repository.projection` 子套件**；不要放 `dto/`——該套件 33 支檔案全是 controller 對外回傳的 API 型別或 service 結果物件，沒有任何 repository 投影型別住在那裡，放進去會讓後人誤以為它是 API 契約而不敢改）。
        **注意這是本專案第一次使用 JPQL constructor expression**：`grep -ran "SELECT new" backend/src/main/java` 目前零命中，既有多欄投影一律回 `List<Object[]>`（如 `StockAlertRepository.java:18-20`、`AssetSnapshotRepository.java:67-68`）。Hibernate 6.6.11（Boot 3.4.4）支援以 record 的 canonical constructor 接收，但 `SELECT new` 後**必須寫完整套件路徑**。
        **既有 `findActiveEmailsByAlertId` 應一併刪除**：經 `grep -ran` 全樹確認，其唯一呼叫端就是本任務要改的 `AlertNotificationDispatcher.java:142`（`StockAlertService` 只用 `deleteByAlertId` / `save` / `findRecipientIdsByAlertId`），新查詢上線後它即為死碼。刪除時**把該方法 javadoc 裡 Task 145 的 owner defense-in-depth 說明整段搬到新的 `findActiveTargetsByAlertId`**，不要讓那段脈絡消失。
        > **查詢呼叫端時務必用 `grep -ran`（帶 `-a`）**：`AlertNotificationDispatcher.java` 與 `PerformanceComparisonService.java` 會被 `file(1)` 判為 `data`，不加 `-a` 的 `grep -r` 會**靜默跳過整個檔案**，讓你得到「零呼叫端」的相反結論。
      - `groupByRecipient` 改回 `LinkedHashMap<Long recipientId, ...>`，值需同時帶著該收件人的 email / `addToCalendar` 與其觸發清單（自訂 private record 承載即可）；`emailCache` 改為 `Map<Long alertId, List<AlertRecipientTarget>>`。寄送時 `sendHtml(List.of(target.email()), ...)` 行為不變。
      - **行為變更（刻意，需在完成報告載明）**：同一 email 分屬不同租戶時，原本兩租戶的觸發會被合併成同一封信寄出（跨租戶內容混寄），改以 id 分組後各租戶各寄一封。同一租戶內行為完全不變。
      - `resendLastTradingDay` 同樣改用新的分組（它與 `flush` 共用 `groupByRecipient`），但**不夾帶 ics**。
      - `AlertNotificationDispatcher` 只新增**一個** `private final` 欄位：`AlertCalendarInviteBuilder calendarInviteBuilder`（`@RequiredArgsConstructor` 自動建構）。`EmailService emailService` 已在既有欄位（`AlertNotificationDispatcher.java:51`）中，**不要**重複宣告（Lombok 會產生兩個同型別建構子參數）；`NotificationRecipientService.isGmail` 為 `public static`，**不需**注入；**不要**注入 `NotificationRecipientRepository`（本任務不再需要它）。
- [x] 248.10b **`buildDigest` 一併產出日曆用的逐檔摘要**：`DigestMail` record 由
      `(String html, Map<String,byte[]> inlineImages, int stockCount)` 擴為
      `(String html, Map<String,byte[]> inlineImages, int stockCount, List<String> calendarLines)`。
      在既有的逐檔迴圈內（`AlertNotificationDispatcher.java:260-308` 那段，已算好 `latest` 與去重後的 `labels`）順手 append 一行
      `「{股名} ({代號} {市場}) — {labels 以「、」串接} 觸發價 {formatNumber(latest.price)}」`。
      **不得在 dispatcher 內重跑一次 `groupByStock` 或複製「labels 去重 + 依 `triggeredAt` 挑 latest」的邏輯**——那段邏輯只存在於 `buildDigest` 內部，複製一份日後必然與信件正文分歧，且沒有任何測試會抓到。`calendarLines` 的順序與筆數必須與信件正文的區塊順序、`stockCount` 一致。
      **同時把簽章改為** `private DigestMail buildDigest(List<PendingTrigger> batch, Map<String, Optional<byte[]>> chartCache, boolean withCalendarNotice)`，並在收尾那行（`AlertNotificationDispatcher.java:313` 的 `sb.append("<p style=\"color:#64748b\">— 資產管理系統</p></div>")`）**之前**插入：
      ```java
      if (withCalendarNotice) {
          sb.append("<p style=\"color:#94a3b8;font-size:12px\">")
            .append("本信附有 Google 日曆邀請，如不需要請告知寄件人於「警示通知設定」關閉。</p>");
      }
      ```
      這個參數是 248.10c 那行提示的**唯一合法管道**：HTML 在 `buildDigest` 內就封好了（`:313-314`），事後在 dispatcher 對 `mail.html()` 做字串 replace 會與 HTML 字面值硬耦合、改樣式即靜默失效。`resendLastTradingDay` 一律傳 `false`。
- [x] 248.10c **`flush()` 掛載 ics**：對每位收件人，`Boolean.TRUE.equals(target.addToCalendar())` 且 `NotificationRecipientService.isGmail(target.email())` 皆成立時，以 `calendarInviteBuilder.build(emailService.resolveFrom(), target.email(), target.id(), subject, mail.calendarLines(), Instant.now())` 組出 ics，改呼叫五參數版 `emailService.sendHtml(..., ics)`；否則傳 `null`（行為與現況完全相同）。組 ics 過程**必須包 try/catch**，失敗只 `log.warn` 並照常寄不含 ics 的信。
      **要不要夾帶的那個布林只算一次**，同時當作 248.10b 的 `withCalendarNotice` 傳給 `buildDigest`——有夾帶 ics 的信才會出現「本信附有 Google 日曆邀請…」那行提示（收件人未被徵詢同意、且 `RSVP=FALSE` 使其在 Gmail 卡片上沒有可拒絕的選項，需給一條明確的停止路徑）。未夾帶的信件內容不得變動。**不得以 `mail.html()` 的字串 replace 事後注入該行。**
      - **`resendLastTradingDay`（手動補發）維持四參數呼叫、不夾帶 ics**：補發是使用者主動全量重寄歷史觸發，再進日曆只會製造重複事件，且使用者人就在畫面前不需推播。
      - `@Scheduled` 清單未新增或變更任何排程 → **不需**同步 `SchedulePublicBffController.JOBS`。

### 前端

- [x] 248.11 `frontend/src/api/index.js` 的 `notificationSettings` 命名空間新增
      `toggleCalendar: (id) => api.patch(\`/bff/notification-settings/recipients/${id}/calendar\`)`。
- [x] 248.12 `NotificationSettingsView.vue`：
      - 表格在「狀態」與「建立時間」之間新增「Google 日曆」欄（width 130、置中）：`isGmail(row.email)` 為真時顯示 **`el-switch`**（綁 `row.addToCalendar`、`@change` 呼叫 `toggleCalendar`，失敗時把值還原）；非 Gmail 顯示 `—` 並以 `el-tooltip` 說明「僅 Gmail 收件人支援」。**只用 `el-switch` 一種呈現**，不要再額外加 `el-tag`。
      - 前端 `isGmail(email)` 判定與後端一致（小寫、`@` 之後為 `gmail.com` 或 `googlemail.com`）。
      - 頁面頂端 `el-alert` 說明文字補一句前提：「Gmail 收件人可另開啟『加入 Google 日曆』，警示信會夾帶日曆邀請、由日曆推播提醒；需收件人的 Google 日曆維持『自動將邀請加入日曆』設定（預設為是）。」
      - 切換成功 `ElMessage.success`，並 `await load()` 重新載入（比照既有 `toggleActive`）。

### 測試

- [x] 248.13 新增 `backend/src/test/java/com/steven/assets/service/AlertCalendarInviteBuilderTest.java`（純 JUnit 5，**不需 Mockito**），至少涵蓋：
      - 產出含 `METHOD:REQUEST`、`BEGIN:VEVENT`、`TRANSP:TRANSPARENT`、`TRIGGER:-PT1M`，且**每行以 CRLF 結尾**。
      - 固定 `now`（如 `Instant.parse("2026-07-29T03:15:40Z")`）→ `DTSTART:20260729T031700Z`（秒歸零 + 2 分）、`DTEND:20260729T033200Z`。
      - `UID` 含 recipientId 與 epochMillis；兩次不同 `now` 產出的 UID 不同。
      - escape：`SUMMARY` / `DESCRIPTION` 含 `;` `,` `\` 時輸出為 `\;` `\,` `\\`；多行摘要以 literal `\n` 串接、不產生真換行。
      - folding：長中文 `DESCRIPTION` 折行後**每行 UTF-8 位元組 ≤ 75**，續行以單一空白起始，且把 unfold（移除 `\r\n `）後的內容還原成折行前的字串（證明沒有切壞多位元組字元）。
- [x] 248.14 新增 `backend/src/test/java/com/steven/assets/service/NotificationRecipientGmailTest.java`（純 JUnit 5）驗證 `NotificationRecipientService.isGmail`：
      `a@gmail.com` / `a@googlemail.com` → true；`a@ms1.wra.gov.tw`、`a@transglobe.com.tw`、`a@gmail.com.tw`、`agmail.com`、`null`、`""` → false。
- [x] 248.15 為了讓 248.10b 的摘要行可用純 JUnit 測（不引入 Mockito——`buildDigest` 需要 `AlertChartRenderer` 等依賴），把該行的組裝抽成
      `static String calendarLine(String stockName, String stockCode, String market, Collection<String> labels, BigDecimal price)`
      （`AlertNotificationDispatcher` 內、package-private），`buildDigest` 的逐檔迴圈呼叫它。
      新增 `backend/src/test/java/com/steven/assets/service/AlertCalendarLineTest.java`（純 JUnit 5）斷言：單一 label、多 label 以「、」串接、`price = null` → `觸發價 -`、`price` 走 `stripTrailingZeros().toPlainString()`（如 `54.350` → `54.35`）。
      「順序與正文一致」由「在同一迴圈內 append」的結構保證，不另立測試。

## 驗證

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test -Dtest='AlertCalendarInviteBuilderTest,NotificationRecipientGmailTest,AlertCalendarLineTest'
```

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
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

前端變更需一併重建（普通 build 會命中 layer cache 沒重跑 vite）：

```bash
docker compose -p asset-management build --no-cache frontend && docker compose -p asset-management up -d --no-deps --force-recreate frontend
```

驗證 migration 已套用與端點行為（`{ID}` 換成實際收件人 id；X-User-* header 為免走 Google 登入的租戶模擬）：

```bash
docker exec asset-postgres psql -U assets -d assets -c "\d notification_recipient" | grep add_to_calendar
```

```bash
docker exec asset-business-services curl -s -X PATCH -H 'X-User-Id: 1' -H 'X-User-Role: ADMIN' -H 'X-User-Status: ACTIVE' http://localhost:8080/api/notification-recipients/{ID}/calendar
```

非 Gmail 收件人的同一支呼叫必須回 400 且 detail 為「僅 Gmail 收件人可加入 Google 日曆」。

**端到端（需人工，程式無法自證）**：把某 Gmail 收件人開啟開關 → 觸發一次該收件人訂閱的警示（或暫時放寬其警示條件使其於盤中觸發）→ 確認該 Gmail 信件出現日曆邀請卡片、Google 日曆於 1~2 分鐘內出現事件並推播。**此步驟依賴收件人日曆的「自動將邀請加入日曆」設定，若未推播先確認該設定。**

## 完成報告

**新增檔案**
- `backend/src/main/resources/db/changelog/changes/v1.77.0-notification-recipient-calendar.sql`
- `backend/src/main/java/com/steven/assets/repository/projection/AlertRecipientTarget.java`
- `backend/src/main/java/com/steven/assets/service/AlertCalendarInviteBuilder.java`
- `backend/src/test/java/com/steven/assets/service/AlertCalendarInviteBuilderTest.java`（11 個測試）
- `backend/src/test/java/com/steven/assets/service/NotificationRecipientGmailTest.java`（11 個測試）
- `backend/src/test/java/com/steven/assets/service/AlertCalendarLineTest.java`（4 個測試）

**異動檔案**
- `db.changelog-master.yaml`：註冊 v1.77.0（block 樣式）
- `NotificationRecipient.java`：加 `addToCalendar`（`@Builder.Default = false`）
- `NotificationRecipientDto.java`：`Response` 加 `addToCalendar`
- `NotificationRecipientService.java`：加 `isGmail`（public static）、`toggleCalendar`；`update` 改成非 Gmail 時強制歸零；`toResponse` 回填新欄位
- `NotificationRecipientController.java`：加 `PATCH /{id}/calendar`
- `StockAlertRecipientRepository.java`：`findActiveEmailsByAlertId` → `findActiveTargetsByAlertId`（投影 id/email/addToCalendar，Task 145 的 owner 條件與 javadoc 原樣保留）
- `EmailService.java`：`sendHtml` 五參數多載 + `attachCalendarInvite`（DataHandler/ByteArrayDataSource）；`resolveFrom()` 改 public
- `AlertNotificationDispatcher.java`：分組 key email → recipientId（新增 `RecipientBatch` record）；`DigestMail` 加 `calendarLines`；`buildDigest` 加 `withCalendarNotice`；新增 static `calendarLine`、`buildInvite`；補發不夾帶 ics
- `frontend/src/api/index.js`：`notificationSettings.toggleCalendar`
- `frontend/src/views/NotificationSettingsView.vue`：「Google 日曆」欄（`el-switch` / 非 Gmail 顯示「—」+ tooltip）、`isGmail`、`toggleCalendar`、頁面說明補前提

**驗證輸出**
- `mvn test -Dtest='AlertCalendarInviteBuilderTest,NotificationRecipientGmailTest,AlertCalendarLineTest'` → Tests run: 27, Failures: 0, Errors: 0
- 全量 `mvn test -DargLine="-Dnet.bytebuddy.experimental=true"` → **Tests run: 266, Failures: 0, Errors: 0**（不帶 argLine 時 171 個 Mockito/byte-buddy error 為既有環境問題，與本任務無關）
- `docker compose build --no-cache business-services frontend` → 皆 Built；jar 內確認含 `AlertCalendarInviteBuilder.class` 與 `AlertRecipientTarget.class`
- recreate 後 business 啟動日誌：`v1.77.0-notification-recipient-calendar ran successfully in 9ms`、`EmailService 啟用，寄件人=tw.leader@gmail.com`、ERROR 0 行、無 JPQL/注入例外
- `\d notification_recipient` → `add_to_calendar | boolean | not null | false`
- `PATCH /api/notification-recipients/1/calendar`（gmail）→ 200 `"addToCalendar":true`；`/2/calendar`（transglobe.com.tw）→ **400「僅 Gmail 收件人可加入 Google 日曆」**。測試後已切回 false，開關留給使用者自行決定
- 新投影查詢的等價 SQL 在真實資料上回 5 列（寄信路徑無回歸）
- frontend bundle 內含「Google 日曆」「僅 Gmail 收件人支援」「/calendar」

**與原計畫的偏差**
1. `AlertCalendarInviteBuilderTest` 原設計對原始 ics 字串斷言 `PARTSTAT=ACCEPTED;RSVP=FALSE`，實跑失敗——`ATTENDEE` 行遠超 75 octets **必然被折行**（RFC 5545 正確行為，解析端須先 unfold）。測試改為對 unfold 後的內容斷言 property 參數，CRLF 結構另立測試。**程式未改，是測試斷言的層次寫錯。**
2. 前端未能以瀏覽器實地驗畫面：`/notification-settings` 需 Google 登入，代為認證不在可做範圍。改以「打包後 bundle 含新字串」佐證，畫面確認留給使用者。

**尚未完成（需使用者操作）**
- 端到端：把某 Gmail 收件人開啟開關 → 盤中觸發一次其訂閱的警示 → 確認信件出現日曆邀請卡、Google 日曆 1~2 分鐘內建立事件並推播。此步依賴收件人日曆的「自動將邀請加入日曆」設定。
- commit / merge 進 main / 從 main 的 worktree 重建（依專案「共用 stack 誰最後 build 誰生效」規則）。
