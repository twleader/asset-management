# Tech Steering — 資產管理系統

> **用途：** 本文件由 AWS KIRO SDD 在每次對話中載入，提供「技術棧、版本、慣例、開發指令」的長期 context。`design.md` 描述具體架構決策，本文件描述穩定的技術選型與工作流。

---

## 1. 系統組成（五個 service + 兩個 datastore）

| 元件 | 技術 | Port | 對外 | 角色 |
|------|------|------|------|------|
| **Frontend** | Vue 3 + Vite 5 + Element Plus + Nginx | 80 | ✅ | SPA；五條 external-only API 明確回 404 |
| **API Gateway** | Nginx Alpine（non-root） | 9090 | ✅（僅 `127.0.0.1`） | Docker 外部五條 exact GET 唯一入口 |
| **BFF** | Spring Boot 3.4.4 + Spring Cloud Gateway | 8080 | ❌ 僅內網 | 前端應用 API 入口；一頁面一 controller |
| **Business Services（backend）** | Spring Boot 3.4.4 + Spring Data JPA | 8080 | ❌ 僅內網 | 領域邏輯、JPA 持久化 |
| **External Materials Service** | Spring Boot 3.4.4 + WebFlux | 8080 | ❌ 僅內網 | 抓股價／NAV／配息／匯率，寫 Redis & DB |
| **PostgreSQL** | postgres:16-alpine | 5432 | ✅（debug） | 主資料庫 |
| **Redis** | redis:7-alpine（AOF + LRU 256MB） | 6379 | ❌ | Live 行情 cache + Pub/Sub channel |

**容器化：** 全套 `docker-compose.yml` 一鍵啟動，網路 `asset-network`，volume `asset-postgres-data` / `asset-redis-data`。

**對外連線方向：**
```
Browser ──► nginx:80 (Frontend) ──► bff:8080 ──► business-services:8080 ──► postgres:5432
                                                                       └─► redis:6379  ◄── external-materials-service
                                                                                       (TWSE / FinMind / NASDAQ / FundClear / IMF)
Host tools ──► 127.0.0.1:9090 (API Gateway) ─┬──► bff:8080
Tailscale ──► HTTPS :9090（僅五條 exact path）──┘──► external-materials-service:8080
```

---

## 2. Backend 技術棧

### 2.1 執行環境

- **Java：21**（pom 設定 `<java.version>21</java.version>`；CLAUDE.md 提到「Java 25」為未來計畫，目前實際為 21）
- **Spring Boot：3.4.4**（JDK 21 相容）
- **Maven：3.9.11**（路徑 `/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn`）

### 2.2 主要依賴（business-services + bff + external-materials-service 通用）

| 依賴 | 用途 |
|------|------|
| `spring-boot-starter-web` | MVC controller |
| `spring-boot-starter-data-jpa` | ORM |
| `spring-boot-starter-validation` | DTO 驗證 |
| `spring-boot-starter-data-redis` | Lettuce 連線、Pub/Sub |
| `spring-boot-starter-webflux` | WebClient（外部 API）、SSE |
| `spring-cloud-starter-gateway`（僅 BFF） | 路由 |
| `postgresql` | JDBC driver（runtime） |
| `liquibase-core` | DB schema migration |
| `poi-ooxml` 5.3.0 | Excel `.xlsx` 匯出 |
| `lombok` 1.18.38 | `@Data` / `@Builder` 等 |

### 2.3 DB 慣例

- **`ddl-auto: none` + Liquibase 全管控。** 任何 schema 變更必須走 changelog；不靠 Hibernate 自動建表。
- **存 enum 一律用 `@Enumerated(EnumType.STRING)`**（事實上多數已改為 `String` + 動態主檔表）。
- **金額：`BigDecimal`**，scale 視欄位而定（價格 4、單位數 6、台幣金額 2）。
- **日期：`LocalDate`**（不存時間）；時間戳：`Instant`。
- **市場時區感知時間：** `lastTriggeredAt` / `triggeredAt` 以「該股市場本地 wall-time」儲存（TW = `Asia/Taipei`、US = `America/New_York`、UK = `Europe/London`），一律經 `MarketZones` 顯式指定，不依賴 JVM 預設時區。
- **JVM 預設時區 = `Asia/Taipei`（Requirement 53 / Task 252）：** 三個 JVM 容器由 compose 的 `TZ` 環境變數設定，PostgreSQL 由啟動參數設定。其語意是「沒有顯式指定 ZoneId 的呼叫落在哪裡」——切換前為 UTC，是全系統唯一沒有人想要的時區。除上述兩個市場牆鐘欄位外，所有 `timestamp without time zone` 欄位一律存台北牆鐘；涉及「交易日／今日」的判定一律走 `MarketZones.today(market)`，禁用裸 `LocalDate.now()`。

### 2.4 啟動指令（本機開發）

```bash
# 後端（business-services，port 8080）
cd backend
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn spring-boot:run

# 也適用於 bff / external-materials-service（各自 cd 進去執行）
```

---

## 3. Frontend 技術棧

### 3.1 執行環境

- **Node.js：v22.21.0**（透過 nvm 安裝於 `/Users/steven/.nvm/versions/node/v22.21.0/`）
- **Vite：5**（dev port 5173）

### 3.2 主要依賴

| 依賴 | 用途 |
|------|------|
| `vue` ^3.5 + `vue-router` ^4.4 | SPA 框架 |
| `pinia` ^2.2 | 全域狀態 |
| `element-plus` ^2.8 + `@element-plus/icons-vue` | UI 元件庫 |
| `echarts` ^5.5 + `vue-echarts` ^7 | 圖表 |
| `axios` ^1.7 | API 呼叫 |
| `dayjs` ^1.11 | 日期處理 |
| `numeral` ^2.0.6 | 數字格式化 |
| `sortablejs` ^1.15 | 拖曳排序 |
| `unplugin-auto-import` ^0.18 | 自動匯入 `ref` / `computed` / `watch`（無需手動 `import`） |
| `unplugin-vue-components` ^0.27 | Element Plus 元件自動匯入 |

### 3.3 啟動指令

```bash
cd frontend
/Users/steven/.nvm/versions/node/v22.21.0/bin/node \
  /Users/steven/.nvm/versions/node/v22.21.0/bin/npm run dev
# 或直接執行 vite：
/Users/steven/.nvm/versions/node/v22.21.0/bin/node ./node_modules/.bin/vite
```

---

## 4. 外部資料來源（External APIs）

由 `external-materials-service` 集中呼叫，business-services 不直接打外部 API。

| 來源 | 用途 | 認證 | 備註 |
|------|------|------|------|
| **TWSE mis API** | 台股盤中 live | 無 | `o`/`z` 欄位；`z=-` 一律 skip write（不寫 `o`/`y` 推估值），保留上輪真實 cache；Redis 空時由 `PriceQueryService` fallback `stock_price_history` 昨收 |
| **TWSE FMTQIK** | 台股大盤月報 + 假日表 | 無 | 用於 GDP-TWSE 圖、交易日曆 |
| **TWSE BWIBBU** | 台股股利率 | 無 | 取代 Yahoo Finance（已停用） |
| **FinMind** | 台股盤後收盤、TaiwanStockDividend、TaiwanETFHoldings | `FINMIND_TOKEN`（Bearer） | 未設 token 仍可匿名（限流） |
| **NASDAQ `/info` + `/historical`** | 美股 live + open（2026/04 起 `/info` 不再回 open） | 無 | 須加 User-Agent |
| **NASDAQ `/dividends`** | 美股股利歷史 | 無 | |
| **FundClear nav-profit / fund-info** | 信託基金 NAV + 配息（offshore / onshore 兩組 endpoint） | 無 | DTO 欄位名分流（`organizeCode` vs `orgId`） |
| **MoneyDJ** | FundClear 失敗時的 fallback | 無 | |
| **央行 / 台銀** | USD/TWD、ZAR/TWD 匯率 | 無 | 沿用 FinMind 為主來源 |
| **IMF DataMapper** | 台灣 / 韓國人均 GDP（`NGDPDPC`、`NGDP_RPCH`） | 無 | GDP-TWSE 圖 |
| **Google Drive（rclone `gdrive-crypt`，crypt 加密）** | DB 備份目的地 | `~/.config/rclone/rclone.conf` 的 `[gdrive-crypt]` | read-only volume 掛入 **business-services**；檔名與內容皆加密 |
| **Google Drive（rclone `GDriveOutput`，`scope=drive`、未加密）** | 匯出檔案輸出目的地（**附加副本**，本機照寫不變） | **同一份** `~/.config/rclone/rclone.conf` 的 `[GDriveOutput]` | read-only 掛入 **business-services ＋ external-materials-service**；per-process `RCLONE_CONFIG` 指向 `/tmp` 可寫副本（token 續期需寫回）。Requirement 50 / Task 245 |

> **單一 config 檔的已知取捨（使用者明示的決定）**：`~/.config/rclone/rclone.conf` 同時含
> `[GoogleDriver]`（`drive.file`，備份底層）／`[gdrive-crypt]`（crypt 層，含解密密碼）／
> `[GDriveOutput]`（`scope=drive`，輸出用），且**同一份掛入 business 與 ext 兩個容器**。
> 代價是 `external-materials-service`（全 stack 唯一對外打第三方者：TWSE／NASDAQ／FinMind／新聞爬蟲）
> 也讀得到備份的 OAuth refresh token 與 crypt 解密密碼。原設計為分離兩份以避免此擴權；
> 改為共用是為省下第二份檔案的維護與搬機成本，且實測兩個 remote 為**同一個 Google 帳號**
> （`rclone about` 的 Total／Used 一致），分離的實際收益本就有限。
>
> **若要復原隔離**：host 上仍保留 `~/.config/rclone/rclone-gdrive-output.conf`（只含 `[GDriveOutput]`），
> 把 compose 兩處掛載改回它、`RCLONE_CONFIG` 改回 `/etc/rclone/rclone-output.conf`
> （**Task 247 後掛載粒度已是目錄，復原成單檔掛載會重新引入下述 dangling inode**），
> 並把兩支 client 的 `CONFIG_SOURCE` 改回該路徑即可。
>
> **掛的是目錄不是單檔（Requirement 52 / Task 247）**：compose 兩處為 `${HOME}/.config/rclone:/etc/rclone:ro`。
> `rclone config` 寫設定是「寫新檔＋rename」，**單檔掛載下替換後容器內舊 inode 會 dangling**——
> 2026-07-28 實測兩容器皆 `stat` 看得到（`links=0`）而 `cat` 回 `ENOENT`，症狀是「設定明明在卻說找不到」，
> 且讓「去 host 重新授權」對執行中的容器完全無效。**這是常態不是例外**：rclone 每次續期 token 都重寫 config，
> 當天 15:30／15:53／16:02 三次，最後那次在容器 recreate 後 1 分鐘內就讓掛載又 dangling。
> 掛目錄後新檔即時可見；但**若 `~/.config/rclone` 目錄本身被替換**（`mv` 重建、還原備份、換機）仍會 dangling。
>
> **仍須 `--force-recreate` 才會用到新設定**：兩支 client 在啟動時複製到 `/tmp` 後執行期不重讀
> （刻意不做熱重載，理由是範圍控制而非 token 新舊）。驗證掛載不可只用 `ls`，要實際讀取。
>
> **`[GDriveOutput]` 的 token 必須含 `refresh_token`**：缺了的話 access_token 一過期（實測約 1 小時）即回
> `token expired and there's no refresh token`，且**無法自動續期**——2026-07-28 的實際事故。
> 陰險之處是重新授權後的一小時內一切正常，故自檢 L2 直接檢查該鍵是否存在，不倚賴連線探測。
>
> **修法只有這一個實測有效**（當天裸 reconnect 連兩次失敗、換新 client id 也無效，加上這個參數一次成功）：
> ```
> rclone config reconnect GDriveOutput: --drive-auth-url "https://accounts.google.com/o/oauth2/auth?prompt=consent"
> ```
> **為什麼裸 `reconnect` 無效**：Google 對已授權過的組合跳過同意畫面、只發 access token；
> 判斷指標是授權過程**有沒有停下來讓你按「繼續／允許」**——3 秒內 `Got code` 就是沒拿到。
> **為什麼換一組新 client id 也無效**：Google 的授權記錄綁的是「**應用程式**」（＝OAuth 同意畫面），
> 同一個 GCP 專案下的所有 client 共用同一個同意畫面，換 client 仍被視為同一個已授權的應用程式。
> 另一條路是到 https://myaccount.google.com/permissions 撤銷該**應用程式**再授權，但當天在該頁找不到對應項目。
> **撤銷是否生效的判斷法**：撤銷會立即 revoke 已發出的 token，故 `rclone lsd "GDriveOutput:<存在的目錄>"`
> 會由成功轉失敗；**但 access_token 自然過期後症狀相同**，此判斷法只在 token 未到 `expiry` 前有效。
>
> **兩個 remote 的 OAuth client 來源不同，這是誤判來源**：2026-07-28 事故當下 `[GDriveOutput]` 帶自訂
> `client_id`／`client_secret`，請求算到自有 GCP 專案，該專案未啟用 Drive API →
> `Error 403: Google Drive API has not been used in project 1098468643583`；而 `[GoogleDriver]`（備份）
> 走 rclone 內建公用 client，同一晚照常成功——於是「備份好好的，所以 Drive 沒問題」。
>
> **最終組態（2026-07-28 19:35 起）**：`[GDriveOutput]` ＝ **自訂 client**（GCP 專案 1098468643583，
> **Drive API 已啟用**）＋ `scope=drive` ＋ **含 `refresh_token`**；`[GoogleDriver]` 維持內建 client。
> 中途曾短暫改走內建 client（15:53–19:19）以繞開 403，但**內建 client 配額全球共享**——實測補跑八頁時
> 三頁回 `rateLimitExceeded`，間隔 60–90 秒才成功；改回自訂 client 後專屬配額生效，補跑不再撞。
> **改用自訂 client 必須同時滿足四件事**：該 GCP 專案啟用 Drive API、OAuth 同意畫面**已發布**
> （停在「測試」的 refresh token 7 天即失效）、授權時**帶 `prompt=consent`** 以取得 refresh_token
> （見下條，這是最難的一關）、選對 Google 帳號。**四項於 2026-07-28 均已實測滿足**
> （同意畫面為 `In production`）。發布狀態的欄位位置：新版 **Google Auth Platform → Audience →
> Publishing status**（改版後已搬離 Overview 頁，舊文件寫的「OAuth 同意畫面」頁已不存在）。
>
> **`configReady` 是啟動時判定一次、失敗永不重試的旗標**：一旦啟動當下讀不到 config，該容器
> **整個生命週期**的 Drive 同步都被跳過。實測 2026-07-28 16:02:25 ext 啟動、16:02:26 讀 config 失敗、
> 16:02:29 host 檔被 rclone 改寫——business 逃過、ext 靜默失效。
> **症狀會偽裝成「Drive 還在收檔案」**（xlsx 由 business 上傳照常出現），只有爬蟲 JSON 停更。
> 故**改 host config 與 recreate 容器不可同時進行**：改 config → 驗 `refresh_token` → 等 host 檔穩定
> → 再 recreate business 與 ext → restart bff。

---

## 5. 關鍵架構決策（穩定不變的部分）

> 這些是「不會輕易改動」的決策；具體實作細節請見 `design.md`。

### 5.1 微服務切分

- **business-services 不打外部行情 API。** 全部委派給 `external-materials-service` 寫 Redis / DB，business-services 只讀。
- **BFF 為前端唯一入口。** 前端不直接打 business-services；所有 `/api/*` 經 BFF 路由。
- **Docker 外部 API 只經 9090 exact allowlist。** BFF 與 external service 不發布 host port；
  Tailscale 只掛 quotes 兩條、market-index、assets/latest 與公開 USD/TWD 匯率，不使用 root／`/api/` proxy 或 Funnel。
- **一個前端頁面對應一個 BFF controller（或 route）。** 即使是純 passthrough 也要有自己的 route（如 BankSettings）。

### 5.2 Live 行情走 Redis + SSE

- **Cache：** `price:{market}:{code}`（JSON，TTL 24h — 確保「今日撈到過真實 z 後就持續活著直到被覆寫」），由 `external-materials-service` 每 2 分鐘 cron 寫入。
- **Pub/Sub：** Redis channel `price-update`；business-services 透過 `RedisMessageListenerContainer` 訂閱 → fan-out 到 `Sinks.Many<String>` → SSE endpoint `/api/market-data/prices/stream`。
- **前端：** `EventSource('/api/market-data/prices/stream')`，初始 GET 一次後改走 SSE，不再 polling。
- **Fallback：** Redis miss → `stock_price_history` 最近一筆收盤。

### 5.3 排程

| Cron | 時區 | 內容 | 所在 service |
|------|------|------|--------------|
| 每 2 分鐘（TW 09:00–13:30 週一～五） | Asia/Taipei | 台股 live → Redis | external-materials |
| 每 2 分鐘（US 09:30–16:00 週一～五） | America/New_York | 美股 live → Redis（含 EST/EDT 切換） | external-materials |
| 13:35 收盤後 | Asia/Taipei | 台股收盤寫 `stock_price_history` | external-materials |
| 16:05 收盤後 | America/New_York | 美股收盤寫 `stock_price_history` | external-materials |
| 每日 09:00 | Asia/Taipei | 抓 `fund_master` 啟用基金 NAV、配息 | external-materials |
| 每日 | — | 警示觸發歷史輪替（保留 30 天） | business-services |
| `0 30 15 * * MON-FRI` | Asia/Taipei | 台股交易日 → `daily/asset_daily_tw_*.dump` | business-services |
| `0 0 7 * * TUE-SAT` | Asia/Taipei | 美股交易日 → `daily/asset_daily_us_*.dump` | business-services |
| `0 0 5 * * SUN` | Asia/Taipei | 每周備份 → `weekly/asset_weekly_*.dump` | business-services |

### 5.4 備份／還原

- **工具：** `pg_dump` / `pg_restore` + `rclone gdrive-crypt`，皆透過 `ProcessBuilder` 呼叫。
- **保留代數（DB `backup_setting` 表單列）：** daily 50 / weekly 5 / manual 5（自救點不計入）。
- **儲存設定當下立即套用 retention**，不等下一次排程。
- **還原流程：** 自動先建 `asset_auto-pre-restore_*.dump` 自救點 → 跑 pg_restore → 前端遮罩 → HikariCP 自動重連。

### 5.5 SSE / Stream 注意事項

- nginx `/api/market-data/prices/stream` 必須設 `proxy_buffering off`，否則 SSE 被卡住。

---

## 6. 開發工作流（強制 SDD）

### 6.1 SDD 順序（第 4 步為實作前閘門）

```
1. spec/requirements.md     →  User Story + Acceptance Criteria
2. spec/design.md           →  架構 / 資料模型 / API 設計
3. spec/tasks/tNNN_*.md     →  建立自足任務檔（規範見 spec/tasks/README.md）
4. spec 對抗式審查          →  /spec-review（產出 findings，不打分數；critical／major 修完即可開工）
5. 實作程式碼
```

第 3 步：Task 201 起的新任務一律建立獨立自足任務檔 `spec/tasks/tNNN_<slug>.md`；`spec/tasks.md` 已降為索引，Task 1–200 凍結於 `spec/tasks/archive/`。

第 4 步：審查必須由**另一支 subagent** 執行（作者自審等於沒審），並先跑 `scripts/spec-check.sh` 取機械證據——編號撞號／重號、Liquibase changeset 版號碰撞與冪等性、spec 宣稱的測試類是否存在、文件計數漂移。

**Commit-msg hook：** `scripts/git-hooks/commit-msg` 檢查 staged 變更是否觸及 controller / model / dto / views / router / db changelog / bff，若有則強制同 commit 必須含 `spec/` 變更。

> hook 與第 4 步是**兩個不同的閘門**。hook 只驗「`spec/` 有沒有被碰」，改個錯字就過關，且觸發清單漏了 `service/`、`repository/`、`external-materials-service/**`（Task 195 的排程漂移就漏過去了）。內容正確性一律由 `/spec-review` 負責。

**首次安裝（每個 clone / worktree）：**
```bash
git config core.hooksPath scripts/git-hooks
```

**例外：** 純樣式 / typo / import 整理 → commit 訊息加 `[skip-spec]`。

### 6.2 不允許的 shortcut

- ❌ `git commit --no-verify`（除非緊急且事後補 spec）
- ❌ Enum 寫死業務分類（銀行、券商、存款類型、市場類型）
- ❌ 跨資料表冗餘儲存同一事實（除歷史快照凍結值）
- ❌ 前端直接打 business-services（必須走 BFF）
- ❌ 同義欄位走不同 service API（必須收斂到同一支）

### 6.3 環境變數（`.env`）

| 變數 | 用途 |
|------|------|
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | DB 連線 |
| `DB_HOST` / `DB_PORT` | 由 compose 注入（postgres / 5432） |
| `REDIS_HOST` / `REDIS_PORT` | 由 compose 注入（redis / 6379） |
| `FINMIND_TOKEN` | FinMind Bearer token（選填，未設則匿名） |
| `BUSINESS_SERVICES_URL` | BFF 路由目標（compose 設 `http://business-services:8080`） |
| `RCLONE_CONFIG` | business 與 ext 皆為 `/etc/rclone/rclone.conf`（host `~/.config/rclone` **目錄**唯讀掛入 `/etc/rclone`，值指向其中的 `rclone.conf`；掛目錄而非單檔的理由見 §4 的 Task 247 條）。程式啟動時各自複製到 `/tmp` 可寫副本（`BackupService` → `/tmp/rclone.conf`、Drive 輸出 → `/tmp/rclone-output.conf`；rclone 續期 OAuth token 需寫回，實測 token 幾乎每次呼叫都已過期），實際呼叫時以 per-process 覆寫指定 |
| `GDRIVE_OUTPUT_REMOTE` | Drive 輸出用的 remote 名稱（預設 `GDriveOutput`）；需先由使用者以 `rclone config create GDriveOutput drive scope=drive` 建立（**`scope=drive` 是必要的**——`drive.file` 只看得到 rclone 自己建的檔案，列不出使用者手動建的目錄）。**現行為自訂 OAuth client**，授權時務必帶 `--drive-auth-url "…?prompt=consent"`（否則拿不到 refresh_token，見 §4）。Requirement 50 / Task 245、Requirement 52 / Task 247 |

---

## 7. 測試與品質

- **後端單元測試：** `spring-boot-starter-test`（JUnit 5 + Mockito）；目前覆蓋率非強制門檻，重要 service 應有測試。
- **前端：** 暫無自動化測試框架；以手動 + Element Plus 元件穩定性為主。
- **Lint / Format：** 後端依 IDE 預設；前端使用 Vue 預設規則，無 ESLint 強制。
- **建置驗證：** `mvn package` 須通過；前端 `vite build` 須通過後才可 commit。

---

## 8. 部署模式

- **目標環境：** 個人 Mac / NAS / 自架 Linux server，本機 Docker。
- **入口：** `docker compose up -d`（讀 `.env`）。
- **資料持久化：** named volumes `asset-postgres-data`、`asset-redis-data`。
- **備份目的地：** Google Drive（rclone `gdrive-crypt:` 加密 remote）。與匯出輸出用的 `GDriveOutput:` 是**兩個不同的 remote，但共用同一份 config 檔**（Requirement 50）——前者加密、只有 `BackupService` 使用；後者未加密、business ＋ ext 共用。兩者實測為同一個 Google 帳號。共用一檔的已知代價（ext 亦可讀備份憑證）與復原方式見 §4 的取捨說明。
- **不規劃：** Kubernetes、雲端託管、多區域、CI/CD pipeline（目前手動部署）。
