# [t247] Google Drive 輸出的可用性自檢與 config 掛目錄（讓「明天早上才發現全掛」變成「啟用當下就看得到」）

**對應 Requirements:** Requirement 52（Google Drive 輸出的可用性自檢：在啟用當下與服務啟動時主動探出三種已知的「Drive 不可用」原因並寫進 log／狀態欄，同時把 rclone config 從單檔掛載改為目錄掛載以消除 dangling inode）
**前置任務:** 無（Requirement 50 / Task 241、245 與 Requirement 51 / Task 242–244 均已實作完成並上 main）
**Liquibase changeset:** 無（本任務不改 schema、不加欄位）

## 背景

2026-07-27 21:31 上線 Drive 輸出功能後的第一個完整排程日（2026-07-28），九張設定表**各有至少一列**
`gdrive_enabled=true`、`gdrive_subpath=投資理財/資產管理`（`exchange_rate_export_schedule` 另有一列屬第二位
使用者、為 false），**本機檔案九份全部照常產生，Drive 端一份都沒上去**。使用者是隔天早上發現檔案沒上雲端
才回頭查的。事後從 `gdrive_last_status` 與容器 log 追出**三個彼此獨立**的原因：

**原因 1 — GCP 專案未啟用 Drive API。** rclone 回：

```
CRITICAL: Failed to create file system for destination "GDriveOutput:投資理財/資產管理/":
couldn't find root directory ID: googleapi: Error 403: Google Drive API has not been used in
project 1098468643583 before or it is disabled.
```

**DB 備份完全不受影響**——備份走 `[GoogleDriver]`，同一晚 23:00:56 的 `Uploaded to gdrive-crypt:backups/daily/`
照常成功。這個不對稱正是誤判來源：「備份好好的，所以 Drive 沒問題」。
**成因**：事故當下 `[GDriveOutput]` 帶自訂 `client_id`／`client_secret`（實測存在；`rclone config reconnect`
印出 `Make sure your Redirect URL is set to … in your custom config`，該訊息只在使用自訂 client 時出現），
自訂 client 把配額與 API 啟用狀態綁到該 client 所屬的 GCP 專案。
**現況**：2026-07-28 15:53 重新授權後這兩個鍵已從該 section 移除（config 1319 → 1219 bytes），改走 rclone
內建公用 client，403 消失。**新取捨是配額共享**——實測當天 16:03–16:12 補跑八頁時三頁回 `rateLimitExceeded`，
間隔 60–90 秒重試才成功。日後若為配額改回自訂 client，**必須同時滿足三個條件**：該 GCP 專案**啟用 Drive API**（否則重現原因 1）、
OAuth 同意畫面**已發布**（停在「測試」狀態的 refresh token **7 天即失效**，會以原因 2 的形式重現）、
授權時選對 Google 帳號。

**原因 2 — `[GDriveOutput]` 的 OAuth token 沒有 `refresh_token`。** 實測該 section 的 token JSON 只有
`access_token`／`expires_in`／`expiry`／`token_type` 四個鍵，對照 `[GoogleDriver]` 多一個 `refresh_token`。
後果是 access_token 一過期（實測約 1 小時）就回：

```
couldn't find root directory ID: Get "https://www.googleapis.com/drive/v3/files/root?...":
token expired and there's no refresh token - manually refresh with "rclone config reconnect GDriveOutput:"
```

**這一項最陰險**：重新授權後的一小時內，任何探測、任何上傳都會成功，看起來完全正常。實測當天
13:39:29 有一次上傳成功、13:42 手動跑 `rclone lsd` 也通，而 token 的 `expiry` 是 `2026-07-28T14:37:15+08:00`——
15:36 再跑同一個 run-now 就回上面那段錯誤。**任何「跑一次 rclone 看通不通」的檢查，在那一小時內都會給出
假的綠燈。**
**取不到 refresh_token 的成因**：Google 對「該 client 已被此帳號授權過」的重複授權不重發 refresh token——
實測 `rclone config reconnect` 在 3 秒內就 `Got code`（瀏覽器跳過同意畫面）那次拿不到；出現同意畫面的
那次才拿得到。

**原因 3 — host 端改過 config 之後，容器內的掛載點成為 dangling inode。** `rclone config` 寫設定是
「寫新檔 ＋ rename」原子替換，而 `docker-compose.yml` 是以**單一檔案**掛入
（`${HOME}/.config/rclone/rclone.conf:/etc/rclone/rclone.conf:ro`），替換後容器內舊 inode 的 link count 歸零：

```
$ docker exec asset-business-services stat -c "%n links=%h" /etc/rclone/rclone.conf
/etc/rclone/rclone.conf links=0
$ docker exec asset-business-services cat /etc/rclone/rclone.conf
（讀不到，ENOENT）
```

**這不是「使用者偶爾手動改設定」的罕見情況**——rclone **每次續期 OAuth token 都會重寫 config**，實測當天
15:30／15:53／16:02 三次原子替換，其中 16:02 那次發生在容器 recreate 之後 **1 分鐘內**，掛載當場又 dangling。
後果是**「使用者去 host 重新授權」對執行中的容器完全無效**。這條陷阱早已寫在 `spec/steering/tech.md` §4
（「host 改過 rclone config 之後必須 `--force-recreate` 容器」），但純文件約束擋不住它再次發生。

**正確行為：** 上述任一種情況下，在**使用者啟用 Drive 的當下**與**每次服務啟動時**就明白指出是哪一種、
以及該怎麼修；且原因 3 從部署層根治，不再倚賴人記得下 `--force-recreate`。**上傳行為本身完全不改**——
Requirement 50／51 的「本機一律照寫、Drive 只是附加副本、上傳失敗 best-effort」語意一個字都不動。

## 要做什麼

### 247.1 compose：rclone config 改為掛目錄（根治原因 3）

- [ ] 247.1.1 `docker-compose.yml` **第 91 行**（business-services）與**第 158 行**（external-materials-service）
      兩處掛載，由 `- ${HOME}/.config/rclone/rclone.conf:/etc/rclone/rclone.conf:ro`
      改為 `- ${HOME}/.config/rclone:/etc/rclone:ro`。
- [ ] 247.1.2 **`RCLONE_CONFIG` 環境變數的值不變**（business 第 62 行、ext 第 142 行，均為
      `/etc/rclone/rclone.conf`），兩支 client 的 `CONFIG_SOURCE` 常數也**不變**
      （`backend/src/main/java/com/steven/assets/service/ProcessRcloneClient.java:41`、
      `external-materials-service/src/main/java/com/steven/assets/externalmaterials/service/ProcessGdriveUploader.java:45`，
      皆為 `Path.of("/etc/rclone/rclone.conf")`）。目錄掛載下該路徑每次都經目錄查找解析，host 端原子替換後
      容器內即可讀到新檔。**不要**順手把常數改成別的路徑——那會讓改動範圍擴散到兩個服務的程式碼而毫無收益。
- [ ] 247.1.3 兩處掛載旁的註解須更新，寫明三件事：（a）掛的是目錄不是單檔，因為 `rclone config` 原子替換會讓
      單檔掛載的 inode dangling（2026-07-28 實測 `links=0` 且 `cat` 回 ENOENT）；（b）**這是常態**——rclone
      每次續期 token 都重寫 config，當天三次，最後一次在容器 recreate 後 1 分鐘內就再度 dangling；
      （c）**限縮**：只消除單檔替換造成的 dangling，若 `~/.config/rclone` **目錄本身**被替換（`mv` 重建、
      還原備份、換機搬設定），掛載仍指向舊目錄 inode，屆時仍須 recreate 容器。
- [ ] 247.1.4 **已知副作用須在註解記載**：該目錄下其他檔案會一併以唯讀進入容器。實測 host 上
      `~/.config/rclone/` 除 `rclone.conf` 外另有 `rclone-gdrive-output.conf`（只含 `[GDriveOutput]` 的舊隔離版本，
      刻意保留供復原用）與 `rclone.conf.bak-before-merge`。**這不擴大權限面**——單一 config 檔的共用取捨
      （Requirement 50 末條）已使兩容器都讀得到備份憑證，多掛入的兩檔是同一組憑證的舊副本。

### 247.2 business：新建獨立自檢元件（**不要塞進 `ProcessRcloneClient`**）

**先讀這一段再動手，否則會撞上循環依賴。** `ProcessRcloneClient`（`backend/.../service/ProcessRcloneClient.java`）
**沒有** remote 名稱：其建構子（`:108`）只吃 `ObjectMapper`，remote 是每次呼叫時傳進來的參數
（`:142` `listDirs(String remote, …)`、`:170` `copyTo(String remote, …)`）。而 backend 全樹**唯一**的
`GDRIVE_OUTPUT_REMOTE` 注入點是 `GdriveOutputSupport`（`backend/.../service/GdriveOutputSupport.java:39-45`，
其 javadoc 明寫「**全 backend 唯一的注入點**（Task 242.1.4）」）。因此：

- 在 `ProcessRcloneClient` 加第二個 `@Value("${GDRIVE_OUTPUT_REMOTE:…}")` → **直接推翻 Task 242.1.4**；
- 改成讓 `ProcessRcloneClient` 注入 `GdriveOutputSupport` → `GdriveOutputSupport` 的建構子吃 `RcloneClient`
  （唯一實作就是 `ProcessRcloneClient`）→ **建構子循環依賴**。Spring Boot 2.6+ 預設禁止循環參照
  （全樹 grep `allow-circular-references` 零命中），結果是 `BeanCurrentlyInCreationException`、
  business-services 起不來——正是本需求禁止的結果。

- [ ] 247.2.1 **新建 `GdriveSelfCheck`**（`backend/src/main/java/com/steven/assets/service/`，名稱可自訂）
      作為**葉節點元件**：只注入 `RcloneClient`、`JdbcTemplate`、`ObjectMapper`，
      **絕不注入 `GdriveOutputSupport`**。remote 名稱一律**由呼叫端傳參**（如 `check(String remote, …)`），
      沿用 `ProcessRcloneClient` 既有模式（其建構子不吃 remote，`:142` `listDirs(String remote, …)`／
      `:170` `copyTo(String remote, …)` 都由呼叫端傳入）。
      **這條是硬約束，違反即起不來**：247.3 要求 `GdriveOutputSupport.resolveUpdate` 呼叫本元件，
      若本元件反向注入 `GdriveOutputSupport` 就形成 `GdriveOutputSupport ⇄ GdriveSelfCheck` 建構子循環，
      Spring Boot 2.6+ 預設禁止（全樹 grep `allow-circular-references` 零命中）→
      `BeanCurrentlyInCreationException` → business-services 起不來。
      **自檢邏輯全部放這裡，`ProcessRcloneClient` 的既有程式碼不動**（除 247.2.6 指定的那一句字串）。
      最終相依圖（三者無環，且 Task 242.1.4「remote 唯一注入點」不破例）：
      ```
      GdriveSelfCheck        → {RcloneClient, JdbcTemplate, ObjectMapper}        （葉節點，remote 傳參）
      GdriveOutputSupport    → {RcloneClient, AppUserRepository, UserAdminService, GdriveSelfCheck}
      GdriveSelfCheckStarter → {GdriveOutputSupport, GdriveSelfCheck}            （掛 ApplicationReadyEvent）
      ```
- [ ] 247.2.2 **啟動自檢掛在新的薄元件 `GdriveSelfCheckStarter`**，以
      `@EventListener(ApplicationReadyEvent.class)`（或 `ApplicationRunner`）觸發，
      由它注入 `GdriveOutputSupport` 取 `remoteName()`（`:53`）後傳給 `GdriveSelfCheck`。
      **不用 `@PostConstruct`**：它可能早於 Liquibase 套用 migration，而本自檢要查八張表。
      **監聽器內須立刻把工作丟到獨立 daemon 執行緒。理由不是 `depends_on: service_healthy`**——
      `ApplicationReadyEvent` 發布時 web server 已在 listen、healthcheck 已可回應。
      真正的理由是：該事件的 listener 跑在**主執行緒**上、彼此**沒有順序保證**，阻塞 20 秒會延後
      `SpringApplication.run()` 收尾與同事件的其他 listener；若自檢排在 availability listener 之前，
      readiness 轉為 `ACCEPTING_TRAFFIC` 一樣會被拖 20 秒。
- [ ] 247.2.3 **前置條件：先查「全庫是否有任一列啟用 Drive」，否則整個啟動自檢跳過。**
      Requirement 50／51 明訂「既有部署升級後行為與現況一致、不要求 rclone remote 存在」，沒人啟用時
      不得每次啟動都噴 WARN。business 端要查的**八張表**：
      `export_schedule_setting`／`trading_calendar_export_schedule`／`index_export_schedule`／
      `exchange_rate_export_schedule`／`trading_radar_export_setting`／`commodity_export_schedule`／
      `realized_gain_export_schedule`／`asset_transaction_export_schedule`。**一次查詢完成**，例：
      ```sql
      SELECT EXISTS (
        SELECT 1 FROM export_schedule_setting            WHERE gdrive_enabled
        UNION ALL SELECT 1 FROM trading_calendar_export_schedule  WHERE gdrive_enabled
        UNION ALL SELECT 1 FROM index_export_schedule             WHERE gdrive_enabled
        UNION ALL SELECT 1 FROM exchange_rate_export_schedule     WHERE gdrive_enabled
        UNION ALL SELECT 1 FROM trading_radar_export_setting      WHERE gdrive_enabled
        UNION ALL SELECT 1 FROM commodity_export_schedule         WHERE gdrive_enabled
        UNION ALL SELECT 1 FROM realized_gain_export_schedule     WHERE gdrive_enabled
        UNION ALL SELECT 1 FROM asset_transaction_export_schedule WHERE gdrive_enabled)
      ```
      **必須走 `JdbcTemplate` 而非 JPA repository**：這八張表都掛 `@Filter(ownerFilter)`，而這裡問的是
      「全庫有沒有人啟用」而非「我的設定」；背景執行緒也沒有 request context 可依賴。
      JdbcTemplate 天然繞過 Hibernate filter，語意明確。
      **多列查詢的 callback 用雙參數 `(rs, rowNum) -> ...`**，不可寫成單參數 `(rs) -> ...`——後者會被解析成
      `ResultSetExtractor`，Spring 只呼叫一次且 `rs` 未 `next()` 定位，runtime 才炸（本專案既有踩坑，
      見 `CrawlerExportPathQuery.gdriveConfig` 於 `:44-47` 的註解）。
- [ ] 247.2.4 **整段自檢（含前置查詢）包在單一 catch-all 內，任何例外只寫 WARN 後 return。**
      這不是防禦性冗餘：八張表在全新安裝的首次啟動時可能尚未建立（`BadSqlGrammarException`），而既有同類
      元件 `CrawlerExportPathQuery` 的既定契約就是「表缺／DB 例外時由呼叫端 fallback」（見該檔 javadoc `:15`）；
      自檢若讓例外逸出，會把一個純觀測功能變成啟動失敗。
- [ ] 247.2.5 **L1：來源 config 實際讀得到內容。**
      判準是**實際讀取**（如 `Files.readAllBytes` 或讀首個 byte），**不是 `Files.exists()`**——
      dangling inode 下 `exists()` 走 stat 仍回 true，只有實際讀取才 `ENOENT`。
      **L1 的價值被一個既有設計放大，實作時要理解**：`configReady`
      （`ProcessRcloneClient.java:106`／`ProcessGdriveUploader.java:85`）是**啟動時判定一次的旗標、
      失敗後永不重試**（全檔只在 `initConfig()` 內設為 true，無任何重試路徑）——一旦啟動當下讀不到 config，
      該容器**整個生命週期**的 Drive 同步都會被跳過。實測 2026-07-28 16:02 就發生過：
      16:02:25 ext 啟動 → 16:02:26 讀 config 得 `NoSuchFileException` → 16:02:29 host 檔正好被
      `rclone config` 改寫，結果 business 逃過而 **ext 靜默失效**；**症狀會偽裝成「Drive 好像還在收檔案」**
      （各匯出頁的 xlsx 由 business 上傳、照常出現），只有爬蟲 JSON 停止更新。
      失敗時 WARN，訊息須含 config 路徑並提示「host 改過 rclone 設定後需
      `docker compose -p asset-management up -d --force-recreate <service>`」，
      **並明寫「本容器本次生命週期的 Drive 同步將全部跳過」**——這句才是使用者真正需要知道的後果。
      **因為自檢是獨立元件、獨立於 `initConfig()` 的 try/catch，L1 在複製失敗與早退兩條路徑上都會執行到**——
      這正是不把自檢寫進 `ProcessRcloneClient.initConfig()` 的第三個理由：寫在那裡的話，dangling 時
      `Files.copy`（`:127`）會擲例外直接跳到 `:137` 的 catch，L1 在它唯一要涵蓋的情境下反而變成死碼。
- [ ] 247.2.6 **`ProcessRcloneClient.initConfig()` 既有的 `log.error`（`:137`）維持不變。**
      本任務不改它。自檢**新增**的訊息一律 WARN（「功能不可用」而非「系統故障」，且本機輸出完全正常）；
      同一次失敗會同時出現既有的一則 ERROR 與自檢的一則 WARN，這是可接受的——前者是初始化事實，
      後者才帶修法。
- [ ] 247.2.6.1 **唯一授權改動 `ProcessRcloneClient` 的地方：速率限制分支的使用者可見字串**
      （`ProcessRcloneClient.java:223-225`）。現行文案是
      「…此為 rclone 內建共用憑證的已知限制，**根治方式是為 rclone 設定專屬的 OAuth client_id**。」
      ——而自訂 client_id 正是本次 403 的成因（見背景原因 1），且使用者現在正處於共享配額狀態
      （實測三次 `rateLimitExceeded`），這句會直接寫進 `gdrive_last_status` 被使用者看到、照做就重演事故。
      改為補上必要前提：「…根治方式是為 rclone 設定專屬的 OAuth client_id；**設定後必須同時到該 GCP 專案
      啟用 Drive API，否則會改為回 403 而完全無法上傳**。」**純字串、無邏輯變更。**
- [ ] 247.2.7 **L2：解析 `[<remote>]` section 的 token JSON，檢查 `refresh_token` 存在且非空。**
      這是唯一能在「access_token 尚未過期」期間就抓出原因 2 的辦法——實際探測在那個時間窗內必然通過。
      解析對象是**已複製到 `/tmp/rclone-output.conf` 的副本**（來源可能 dangling）。
      remote 名稱**由呼叫端傳入**（見 247.2.1），**不得寫死 `GDriveOutput`**、也不得在本元件注入取得。
      實作要點：rclone config 為 INI 格式，`token = {"access_token":"…","expiry":"…"}` 為單行 JSON 字串；
      找到該 section 下的 `token` 值後以注入的 `ObjectMapper` 解析。
      **五種結果，全部不得擲例外**：含非空 `refresh_token` → 靜默通過；缺或為空 → WARN；
      token 值非合法 JSON → WARN（無法判定）；**config 副本不存在** → WARN（無法判定，L1 失敗的下游狀態）；
      **檔內無該 section** → WARN（無法判定，remote 名稱錯或尚未建立）。
      WARN 訊息須明寫「`[<remote>]` 的 token 缺 refresh_token，access_token 過期後將無法自動續期」，
      附修法 `rclone config reconnect <remote>:`，並提示「**若授權過程數秒內就完成（沒出現同意畫面），
      Google 不會重發 refresh token**，須先到 https://myaccount.google.com/permissions 撤銷該應用授權再重試」。
- [ ] 247.2.8 **L3：實跑一次唯讀探測 `rclone lsd <remote>:`。**
      涵蓋只有真的連線才知道的狀況：Drive API 未啟用（403）、remote 名稱打錯、授權已撤銷。
      走 `RcloneClient.listDirs(remote, "")` 即可（既有介面，唯讀，測試可替身注入）。
      **須攔下 `exec(...)` 會擲的全部四種例外**：`RcloneClient.RcloneUnavailableException`／
      `RcloneTimeoutException`／`RcloneRateLimitedException`／裸 `RuntimeException`。
      失敗時 WARN 並**盡可能原樣附上 rclone 的 stderr**（403 訊息本身就含啟用 Drive API 的 console 連結，
      是使用者最需要的那一行）。**注意既有 `exec(...)` 在兩條分支會把 stderr 換成罐頭訊息**
      （`ProcessRcloneClient.java:216-226`：`didn't find section in config file` → `RcloneUnavailableException`、
      速率限制 → `RcloneRateLimitedException`），該兩型附其可讀訊息即可，不必強求原始 stderr。
      403 不含速率限制特徵字（`RATE_LIMIT_MARKERS`，`:98-101`），會落到帶 stderr 的那條，主要情境無虞。

### 247.3 business：啟用當下也要自檢（**只做啟動自檢等於防不到本次事故**）

- [ ] 247.3.1 **需要兩個掛載點，不是一個。**
      （a）**八個匯出頁**：`GdriveOutputSupport.resolveUpdate(...)`（`GdriveOutputSupport.java:166`）——
      其 javadoc `:155` 明寫「八個匯出頁的 `PUT` 一律走這一支」，實測全樹該方法的呼叫點**恰為八支**匯出
      service（`ExportScheduleService`／`ExchangeRateExportScheduleService`／`IndexExportScheduleService`／
      `TradingCalendarExportScheduleService`／`CommodityExportScheduleService`／`RealizedGainExportScheduleService`／
      `AssetTransactionExportScheduleService`／`TradingRadarExportScheduleService`），改一處即八頁全有。
      （b）**爬蟲頁**：`CrawlerExportPathService.update()`——**該頁不走 `resolveUpdate`**。它雖注入
      `GdriveOutputSupport`，卻只用其零件（`normalizeSubpath`／`validateSubpath`／`remoteName`）自行合成，
      故必須另行掛一次（在既有的驗證與必填判定之後、`repo.save` 之前）。
      **漏掉 (b) 就是漏掉最該被攔下的那一次**：爬蟲頁是九列中 `updated_at` 最早的一列（2026-07-27 22:33）。
      **兩處都只在「本次請求把 enabled 從 false 翻成 true」時觸發**，true→true 不得重複觸發。
- [ ] 247.3.2 **時序理由必須理解，不可簡化掉這一項**：R50／R51 部署 recreate 當下九張表的 `gdrive_enabled`
      全為 false（`NOT NULL DEFAULT false`、Requirement 50／51 明訂 seed 不啟用）→ 啟動自檢的前置條件判定
      「全庫無人啟用」→ 整個自檢跳過。使用者接著在 UI 打開開關，**不需要也不會 recreate 容器**。
      實測 `updated_at`：`crawler_export_setting` 2026-07-27 22:33、`export_schedule_setting` 2026-07-28 12:09、
      `trading_radar_export_setting` 12:56、`exchange_rate_export_schedule` 12:58——全部晚於 21:31 的上線 recreate。
      也就是說**只做啟動自檢的話，這次事故從頭到尾不會有任何一次自檢執行**。
- [ ] 247.3.3 **(b) 只做 L1 ＋ L2，不做 L3。**
      這兩層是純本地檔案讀取與 JSON 解析（毫秒級、無副作用、不打網路），同步執行即可。
      **L3 刻意排除在 (b) 之外**，三個理由缺一不可：
      （i）L3 走 `RcloneClient.listDirs` → `exec(cmd, remote, LIST_TIMEOUT_SEC, …)`，`LIST_TIMEOUT_SEC = 20`
      （`ProcessRcloneClient.java:51`）——同步做會讓使用者按下儲存後乾等最長 20 秒，而「探測慢」恰恰等於
      「Drive 有問題」，體感就是儲存卡死；
      （ii）九支設定 service 中 **`TradingRadarExportScheduleService.saveSetting`（`@Transactional` 於 `:162`）與
      `CrawlerExportPathService.update`（`:74`）在交易內**，同步 L3 會把 20 秒的外部行程呼叫包進交易、
      佔住 Hikari 連線（其餘七支目前無 `@Transactional`）；
      （iii）內建公用 client 的配額為全球共享（實測當天三次 `rateLimitExceeded`），而使用者啟用時正是
      連續開九個開關的時候，同步探測會加劇撞牆。
      **這不是缺口**：L3 涵蓋的 403／remote 錯誤會在該頁**第一次實際上傳**時寫進 `gdrive_last_status`
      （既有機制，本次事故正是這樣被記錄下來的），而 (a) 每次啟動也會做一次；(b) 真正不可取代的價值在
      **L2**——它是唯一能在 access_token 尚未過期的時間窗內抓出「token 缺 refresh_token」的辦法，
      而那正是本次最陰險的一項。**刻意不做非同步 L3 ＋ 回寫**：`resolveUpdate` 收不到 entity／id
      （簽章為 `(Long ownerUserId, Boolean, String, boolean, String)`、回傳只有兩欄的
      `DriveSettings` record，`:151`／`:166`），且八支呼叫端都在 `repo.save` **之前**呼叫、新列當下**還沒有 id**；
      要讓非同步結果落地就得替九支各自傳表名與主鍵回寫，那會推翻 247.3.1(a)「改一處即八頁全有」。
- [ ] 247.3.4 **結果一律不得寫入 `gdrive_last_run_at`／`gdrive_last_status`。**
      那兩欄的既有語意是**上傳結果**——Requirement 50 明訂「成功記落點路徑與檔案大小，失敗記錯誤訊息摘要，
      **由設定卡顯示「上次上傳」狀態**」，且**九個前端 view 全部**把它標成「上次上傳」直接串接顯示
      （`frontend/src/views/` 下 `上次上傳` 全樹命中 11 處，涵蓋九個頁面）。寫進去有兩個實害：
      （i）**永久覆蓋真正的上傳記錄**——使用者關掉再打開開關，昨晚成功上傳的落點與大小就沒了；
      （ii）**`gdrive_last_run_at` 會被寫成一個沒有發生任何上傳的時刻**，設定卡顯示
      「上次上傳：今天 12:09 — 自檢失敗：…」，而 12:09 根本沒上傳過。
      九支設定 service 既有的「刻意不碰 `gdriveLastRunAt`／`gdriveLastStatus`：那是執行結果，不是使用者設定」
      註解（全樹 grep 命中 9 次）**維持成立，本任務不為它開例外**。
- [ ] 247.3.5 **結果改走當次回應。** 九頁的設定儲存 response DTO 各加一個**非持久化**的警告欄位
      （如 `gdriveSelfCheckWarning`，正常時為 `null`），前端在既有的「儲存成功」提示旁多顯示這一則警告。
      **不新增 DB 欄位、不新增端點、不新增頁面或元件**；九頁的 BFF passthrough 已存在，DTO 加欄位即隨
      既有路徑帶到前端。訊息沿用 `GdriveOutputSupport` 既有的截斷機制（`MAX_STATUS_LEN = 512`，`:33`）
      與 `skipped(...)`（`:245`）的措辭風格。**新增／修改的 DTO 一律維持不可變 `record`**（本專案既有規範）。
- [ ] 247.3.6 **自檢失敗不得讓儲存回非 2xx。** 使用者必須能先把設定存起來再去修授權，否則唯一的修正入口
      被自己鎖死——這是 Requirement 50 `absolutePathOrNull` 既有理由的同一條。**啟用時的自檢不受
      247.2.3 前置條件約束**：該請求本身就是「有人要啟用」的證據。

### 247.4 ext：`ProcessGdriveUploader` 同一套自檢

- [ ] 247.4.1 ext 端同樣**新建獨立自檢元件**（不塞進 `ProcessGdriveUploader`），實作 L1／L2／L3，
      掛 `ApplicationReadyEvent` ＋ 獨立 daemon 執行緒。remote 名稱**沿用既有的
      `GdriveUploader.remoteName()`**（介面已有此方法），不要在 ext 端新增第二個
      `GDRIVE_OUTPUT_REMOTE` 的 `@Value` 注入點。
- [ ] 247.4.2 **ext 端的 L3 需要先給 `GdriveUploader` 一個唯讀探測方法——這是對既有安全不變量的明示修改。**
      該介面目前**只有三個方法**（`upload`／`isAvailable`／`remoteName`），且其 javadoc 把
      「**只有「上傳」一個動作**…為把完整權限的實際使用面縮到最小，這裡**只實作 `copyto`**，
      **不實作任何刪除既有 Drive 檔案的程式路徑**」寫成安全承諾。
      故：（a）在 `GdriveUploader` 新增一支唯讀探測（如 `void probe()` 或 `boolean canReach()`），
      於 `ProcessGdriveUploader` 以 `rclone lsd <remote>:` ＋既有逾時與 `RCLONE_LIMITS` 實作；
      （b）**同步把該介面的 javadoc 改為**「兩種操作：`copyto`（寫入指定子路徑）與 `lsd`（唯讀列目錄）；
      仍不實作任何刪除既有 Drive 檔案的程式路徑」。
      **不得**在自檢元件內另寫一份 `ProcessBuilder` 跑 rclone——那會複製逾時、stderr 解析與 rate-limit
      判定三份邏輯。這一項是本任務**唯一**授權改動 `ProcessGdriveUploader`／`GdriveUploader` 的地方。
- [ ] 247.4.3 **ext 端的自檢元件需注入 `ObjectMapper`**（Spring Boot 自動配置已提供）——
      `ProcessGdriveUploader` 目前**沒有** Jackson 相依（全檔無 Jackson import、建構子只吃 remote 字串），
      這點與 business 端不同（`ProcessRcloneClient` 建構子已有 `ObjectMapper`）。
      **不要在方法內 `new ObjectMapper()`**。
- [ ] 247.4.4 **ext 端的前置條件查的是 `crawler_export_setting`**（全域一列、無 owner）：
      `SELECT EXISTS (SELECT 1 FROM crawler_export_setting WHERE gdrive_enabled)`。
      ext 對此表**原本就純讀、走 `JdbcTemplate` 不建 entity**（見 `CrawlerExportPathQuery`），
      本任務沿用該模式——**新增的查詢方法應加在 `CrawlerExportPathQuery`**，不另建第二支 query 元件。
      同樣包 catch-all（該檔 javadoc `:15` 已載明「表缺／DB 例外時由呼叫端 fallback」的既定契約）。
- [ ] 247.4.5 **ext 端不需要「啟用當下自檢」，但 business 端的爬蟲頁需要**：爬蟲設定的 `PUT` 在 business
      （`/api/crawler-export-path` → `CrawlerExportPathService.update()`），不在 ext。
      **注意該頁不走 `resolveUpdate`**（見 247.3.1(b)），必須另行掛一次，不可假設 247.3.1(a) 已涵蓋。
- [ ] 247.4.6 **兩支各自實作，不抽跨服務共用 module。** `backend` 與 `external-materials-service` 是兩個獨立
      Maven 專案、無父 pom；Requirement 50 已定案「不為兩處 rclone 呼叫建共用 module」（為數十行程式碼引入
      跨服務 module 會使三個服務的建置相互耦合）。本任務的自檢邏輯同屬此類，**兩邊各寫一份是刻意的**。
      注意這條**只適用於跨服務**：同一個 backend 內若有八處相同邏輯，仍必須抽共用元件
      （Requirement 51 的 `GdriveOutputSupport` 即是）。

### 247.5 不做什麼（明確排除，避免實作者擴大範圍）

- [ ] 247.5.1 **不實作 config 熱重載。** 兩支 client 都在啟動時複製一份到 `/tmp` 後執行期不重讀來源；
      掛目錄後 host 重新授權仍須 recreate 容器才生效。**理由是範圍控制**（熱重載需要 watch／輪詢與並發保護，
      收益只有省一次 recreate）。**不要寫成「重載會覆蓋較新的 token」**——那個理由撐不住：token 健康時
      rclone 會自己再 refresh、只多一次網路往返；token 缺 `refresh_token` 時從來源重載反而正是想要的行為。
- [ ] 247.5.2 **不新增 API 端點、不新增 UI、不做健康檢查頁、不新增 DB 欄位。**
      啟用時的結果寫既有 `gdrive_last_status`，啟動時的只進 `docker logs`。
- [ ] 247.5.3 **不改任何上傳行為、不改 schema、不新增 Liquibase changeset。**
- [ ] 247.5.4 **不新增 `@Scheduled`**（自檢掛 `ApplicationReadyEvent` 與 `PUT` 路徑），故「公開資訊 → 排程列表」
      （`bff/src/main/java/com/steven/assets/bff/schedulelist/SchedulePublicBffController.java` 的 `JOBS`，`:53`）
      **不需新增或修改任何項目**。
- [ ] 247.5.5 **不得把 token 內容寫進 log。** L2 只解析 token JSON 的**鍵名**判斷 `refresh_token` 是否存在，
      log 只寫存在與否；**絕不**輸出 `access_token`／`refresh_token` 的值。L1 亦不得為了診斷而 dump config
      檔內容——該檔含 `[gdrive-crypt]` 的 crypt 解密密碼。

### 247.6 測試

- [ ] 247.6.1 **L2 的 token 解析**（backend，新增一支自檢測試類，命名由實作者決定）須涵蓋**五種**輸入：
      含 `refresh_token`（通過，不 WARN）／缺 `refresh_token`（WARN）／token 值非合法 JSON（WARN，不擲例外）／
      config 檔不存在（WARN，不擲例外）／檔內無該 section（WARN，不擲例外）。
      測資直接以字串組出 INI 內容，**不得使用真實 token**。
- [ ] 247.6.2 **前置 DB 查詢擲例外時自檢靜默結束**：以替身讓 `JdbcTemplate` 擲 `BadSqlGrammarException`，
      驗證不擲出、不影響啟動、只寫一則 WARN。
- [ ] 247.6.3 **全庫無任何列啟用時整個啟動自檢跳過**：以介面替身驗證**零次** rclone 呼叫。
      這是 Requirement 50／51「既有部署不要求 rclone remote 存在」的直接回歸。
- [ ] 247.6.4 **啟用當下自檢失敗仍回 2xx、設定已存入、且狀態兩欄未被改動**：驗證儲存不因自檢失敗而變成
      4xx／5xx，該列的 `gdrive_enabled`／`gdrive_subpath` 確實寫入，警告字串出現在 response 的
      `gdriveSelfCheckWarning`，且 **`gdrive_last_run_at`／`gdrive_last_status` 維持原值不變**
      （後半段是 Requirement 50「這兩欄＝上次上傳」語意的回歸保護）。
- [ ] 247.6.5 **兩個啟用掛載點各需一組「false→true 觸發、true→true 不觸發」測試**：
      （a）八頁走 `resolveUpdate` 的那一處，（b）`CrawlerExportPathService.update()` 那一處。
      **(b) 不可省略**——它是唯一不走 `resolveUpdate` 的頁面，也是實測中最早被打開的一列。
- [ ] 247.6.6 **(b) 完全不呼叫 rclone**：以介面替身驗證**零次**呼叫。這是「(b) 只做 L1＋L2、不做 L3」的
      機械保證，也順帶擋掉「儲存因探測而變慢」的回歸。
- [ ] 247.6.7 **L1 失敗時只 warn**：來源檔讀取失敗時自檢不擲例外、服務仍可啟動。
- [ ] 247.6.8 **自檢一律不實際連網**——(a) 的 L3 走介面替身。backend 既有的
      `GdriveBrowseTest`／`ProcessRcloneClientRateLimitTest` 已是 `RcloneClient` 替身模式，可沿用。
      ext 端既有的 `NewsPollerGdriveSyncTest` 用的是 `mock(GdriveUploader.class)`（Mockito 介面 mock），
      介面新增探測方法後**自動涵蓋，只需為新方法補 stub 行為**即可，不必手寫替身。
      （Mockito 測試在本專案需 `-DargLine="-Dnet.bytebuddy.experimental=true"`，見下方驗證段。）
- [ ] 247.6.9 ext 端對應的 L2 解析測試同 247.6.1（該服務有自己的測試樹
      `external-materials-service/src/test/java/com/steven/assets/externalmaterials/service/`）。

## 驗證

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test -DargLine="-Dnet.bytebuddy.experimental=true"
```

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml test -DargLine="-Dnet.bytebuddy.experimental=true"
```

重建與重啟（本專案沒有 dev server，「改好」＝ image rebuild ＋ container recreate；JVM 服務**一律
`--no-cache`**，cached build 可能不含變更）：

```bash
docker compose -p asset-management build --no-cache business-services external-materials-service
```

```bash
docker compose -p asset-management up -d --no-deps --force-recreate business-services external-materials-service
```

recreate business 後 BFF 會握著舊 IP（Docker DNS TTL 600s，實測 ≥3 分鐘不自癒）：

```bash
docker compose -p asset-management restart bff
```

驗掛載改成目錄後**實際讀得到**（不可只用 `ls`——dangling inode 下 `ls` 照樣看得到）：

```bash
docker exec asset-business-services sh -c 'head -c 1 /etc/rclone/rclone.conf >/dev/null && grep -o "^\[.*\]" /etc/rclone/rclone.conf'
```

驗 dangling 已根治——在 host 觸發一次 config 原子替換（跑任一個會續期 token 的 rclone 指令，例如
`rclone lsd GDriveOutput:`），再跑上面那行，**仍須讀得到**；改動前的單檔掛載此時會回 ENOENT：

```bash
docker exec asset-business-services stat -c "%n links=%h" /etc/rclone/rclone.conf
```

驗啟動自檢有跑到且**沒有誤報**。注意：**三個事故原因在 host 上都已排除**（`client_id` 已移除、
`refresh_token` 已具備、掛載改目錄後 dangling 消失），依 247.2.7「含非空 refresh_token → 靜默通過」，
**L1／L2 正常情況下不會有任何輸出**——`grep` 不到不等於自檢沒生效：

```bash
docker logs asset-business-services 2>&1 | grep -i "自檢\|refresh_token\|GDriveOutput" | head -20
```

要確認自檢真的會叫，**暫時把 remote 名稱指向不存在的值重啟一次**，確認 L3 的 WARN 如期出現後再改回
（`GDRIVE_OUTPUT_REMOTE` 在 `.env` 或 compose 覆寫皆可）：

```bash
GDRIVE_OUTPUT_REMOTE=NoSuchRemote docker compose -p asset-management up -d --no-deps --force-recreate business-services
```

```bash
docker logs asset-business-services 2>&1 | grep -i "自檢\|NoSuchRemote" | head -10
```

```bash
docker compose -p asset-management up -d --no-deps --force-recreate business-services external-materials-service
```

```bash
docker logs asset-external-materials-service 2>&1 | grep -i "自檢\|refresh_token\|GDriveOutput" | head -20
```

驗「無人啟用時不噴 WARN」——**改動前先存下九張表的現值**：

```bash
docker exec asset-postgres psql -U assets -d assets -P pager=off -c "\copy (SELECT 'export_schedule_setting' t, id, gdrive_enabled FROM export_schedule_setting UNION ALL SELECT 'trading_calendar_export_schedule', id, gdrive_enabled FROM trading_calendar_export_schedule UNION ALL SELECT 'index_export_schedule', id, gdrive_enabled FROM index_export_schedule UNION ALL SELECT 'exchange_rate_export_schedule', id, gdrive_enabled FROM exchange_rate_export_schedule UNION ALL SELECT 'trading_radar_export_setting', id, gdrive_enabled FROM trading_radar_export_setting UNION ALL SELECT 'commodity_export_schedule', id, gdrive_enabled FROM commodity_export_schedule UNION ALL SELECT 'realized_gain_export_schedule', id, gdrive_enabled FROM realized_gain_export_schedule UNION ALL SELECT 'asset_transaction_export_schedule', id, gdrive_enabled FROM asset_transaction_export_schedule UNION ALL SELECT 'crawler_export_setting', id, gdrive_enabled FROM crawler_export_setting) TO '/tmp/gdrive_flags_backup.csv' CSV HEADER"
```

再確認「全部關閉」時九張表的 EXISTS 查詢確實回 false（**九張全查，只查兩張會給假綠燈**）：

```bash
docker exec asset-postgres psql -U assets -d assets -P pager=off -c "SELECT EXISTS (SELECT 1 FROM export_schedule_setting WHERE gdrive_enabled UNION ALL SELECT 1 FROM trading_calendar_export_schedule WHERE gdrive_enabled UNION ALL SELECT 1 FROM index_export_schedule WHERE gdrive_enabled UNION ALL SELECT 1 FROM exchange_rate_export_schedule WHERE gdrive_enabled UNION ALL SELECT 1 FROM trading_radar_export_setting WHERE gdrive_enabled UNION ALL SELECT 1 FROM commodity_export_schedule WHERE gdrive_enabled UNION ALL SELECT 1 FROM realized_gain_export_schedule WHERE gdrive_enabled UNION ALL SELECT 1 FROM asset_transaction_export_schedule WHERE gdrive_enabled UNION ALL SELECT 1 FROM crawler_export_setting WHERE gdrive_enabled) AS any_enabled"
```

驗證後**逐表還原並與備份 CSV 比對**，確認使用者的正式設定沒有被留在關閉狀態。

## 完成報告

（實作者做完後回填：實際改了哪些檔、驗證輸出、與原計畫的偏差及原因。）
