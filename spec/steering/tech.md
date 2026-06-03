# Tech Steering — 資產管理系統

> **用途：** 本文件由 AWS KIRO SDD 在每次對話中載入，提供「技術棧、版本、慣例、開發指令」的長期 context。`design.md` 描述具體架構決策，本文件描述穩定的技術選型與工作流。

---

## 1. 系統組成（四個 service + 兩個 datastore）

| 元件 | 技術 | Port | 對外 | 角色 |
|------|------|------|------|------|
| **Frontend** | Vue 3 + Vite 5 + Element Plus + Nginx | 80 | ✅ | SPA，所有 `/api/*` proxy 至 BFF |
| **BFF** | Spring Boot 3.4.4 + Spring Cloud Gateway | 8080 | ✅ | API Gateway，前端唯一入口；一頁面一 controller |
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
- **市場時區感知時間：** `lastTriggeredAt` 等以「該股市場本地 wall-time」儲存（TW = `Asia/Taipei`、US = `America/New_York`），不用 JVM 預設時區。

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
| **Google Drive（rclone gdrive-crypt）** | DB 備份目的地 | rclone config | 設定檔以 read-only volume 掛入 container |

---

## 5. 關鍵架構決策（穩定不變的部分）

> 這些是「不會輕易改動」的決策；具體實作細節請見 `design.md`。

### 5.1 微服務切分

- **business-services 不打外部行情 API。** 全部委派給 `external-materials-service` 寫 Redis / DB，business-services 只讀。
- **BFF 為前端唯一入口。** 前端不直接打 business-services；所有 `/api/*` 經 BFF 路由。
- **一個前端頁面對應一個 BFF controller（或 route）。** 即使是純 passthrough 也要有自己的 route（如 BankSettings）。

### 5.2 Live 行情走 Redis + SSE

- **Cache：** `price:{market}:{code}`（JSON，TTL 600s），由 `external-materials-service` 每 2 分鐘 cron 寫入。
- **Pub/Sub：** Redis channel `price-update`；business-services 透過 `RedisMessageListenerContainer` 訂閱 → fan-out 到 `Sinks.Many<String>` → SSE endpoint `/api/market-data/prices/stream`。
- **前端：** `EventSource('/api/bff/market-data/stream')`，初始 GET 一次後改走 SSE，不再 polling。
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

- nginx `/api/bff/market-data/stream` 必須設 `proxy_buffering off`，否則 SSE 被卡住。

---

## 6. 開發工作流（強制 SDD）

### 6.1 SDD 順序（pre-commit hook 強制）

```
1. spec/requirements.md  →  User Story + Acceptance Criteria
2. spec/design.md        →  架構 / 資料模型 / API 設計
3. spec/tasks.md         →  對應 Task 標記完成
4. 實作程式碼
```

**Pre-commit hook：** `scripts/git-hooks/pre-commit` 檢查 staged 變更是否觸及 controller / model / dto / views / router / db changelog / bff，若有則強制同 commit 必須含 `spec/` 變更。

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
| `RCLONE_CONFIG` | `/etc/rclone/rclone.conf`（read-only volume 從 host 掛入） |

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
- **備份目的地：** Google Drive（rclone `gdrive-crypt:` 加密 remote）。
- **不規劃：** Kubernetes、雲端託管、多區域、CI/CD pipeline（目前手動部署）。
