# [t266] 台股個股基本面資料每日抓取與歷史落地

**對應 Requirements:** **Requirement 46**（台股個股基本面資料每日抓取與歷史落地——本任務為其唯一實作任務，接手自被取代的 t222）；Requirement 43 修訂（今日交易雷達——個股評分須納入公司體質；本任務只建立資料管道，不改評分）
**前置任務:** 無
**後續任務:** t267（把本任務落地的資料接成評分因子）、t278（回補 `stock_valuation_daily` 的歷史 PE／PB／殖利率）
**Liquibase changeset:** `v1.87.0-stock-fundamental.sql`（三張新表 ＋ `crawler_schedule` 的 `fundamental` 冪等 seed ＋ `stock.security_type` 欄位；版號須於實作前重新確認未被其他 worktree 佔用）

> **⚠ 版號已避讓（2026-08-01）：本檔原記載的 `v1.83.0-stock-fundamental.sql` 已撞號。** `v1.83.0-radar-notification-rule-version.sql`（Task 264）與 `v1.84.0-asset-transaction-fee-tax.sql`（Task 268）皆已在 main 上，`v1.85.0` 由 t275（`treasury_yield_daily`）預留、`v1.86.0` 由 t277（雙軌通知欄位）預留，故本任務改用 `v1.87.0`。**實作前仍須以 `ls backend/src/main/resources/db/changelog/changes/ | sort -V | tail` 重新確認**——本專案有 30+ 個 worktree 並行推進 main，版號會被其他分支先佔走。
>
> **避讓時的硬約束：改 changeset 檔名或內容（含 `--comment` 註解）都會改變 Liquibase 的 checksum。** 註解也計入 checksum，改註解會造成 `ValidationFailed` 而讓 business-services 進入 crash loop。做編號避讓的 `sed` 必須排除 `db/changelog/`。

> **本任務取代 `spec/tasks/t222_stock_fundamental_ingestion.md`**（該檔從未實作，且其後續 t224 綁在 t223 的五組框架上；本任務改為銜接 t264 的扁平權重）。

## 背景

### 使用者要求

「如果是個股，一定要參考基本面。」

### 現況：基本面資料在本系統中完全不存在

全庫查證：`stock_valuation_daily`／`stock_financial_quarter`／`stock_monthly_revenue` 在 `backend/src`、`external-materials-service/src` **零命中**。交易雷達的因子 100% 為技術面與匯率（規劃當下的 V8 為 11 個因子；t264 落地後為 14 個，新增的季線乖離、52 週位置、ETF 折溢價同樣是價格／估值資訊），**沒有任何公司體質資訊**。

其直接後果（t223 已診斷）：個股的低分**無法區分「基本面崩壞應出場」與「短期錯殺應加碼」**，而這兩者的正確動作相反。t264 以「52 週位置 ＋ 年線結構」作為長期結構的代理來緩解，但那仍是價格資訊——價格已反映的崩壞，基本面往往更早顯示。

### 必須先講清楚的資料限制（不是推託，是硬事實）

- **來源只有當期快照。** TWSE／TPEx 開放 API 的基本面資料集只提供「今天」這一筆，`?date=` 參數會被伺服器忽略。
- **歷史無法回補。** 唯一的歷史來源 MOPS 全站 `robots.txt` 為 `User-Agent: * / Disallow: /`，禁止程式化存取。本專案既有紀律不繞過 robots.txt（比照 MoneyDJ 的既有決定）。
- **故歷史只能自本任務上線起自行累積：**

  | 子因子 | 需要的歷史 | 上線後可用時間 |
  |---|---|---|
  | 月營收 YoY | 3 個月（來源直給 YoY，不需去年基期） | **3 個月** |
  | ROE | 4 季 | 約 **1 年** |
  | PE 自身歷史分位 | 250 交易日 | 約 **1 年** |
  | EPS 年增率 | 8 季（近四季 vs 前四季） | 約 **2 年** |

  **這個時程必須向使用者揭露，不得讓畫面看起來像已納入完整基本面判斷。** 本任務上線後前 3 個月，四個子因子全部為 `null`。

## 要做什麼

### 266.1 三張表（全域公開資料，無 `owner_user_id`、不套 Hibernate `@Filter`）

- [ ] 建表（欄位皆 nullable，`NULL` 代表無資料或虧損，**絕不會是 `0` 代表缺值**）：

  ```text
  stock_valuation_daily     UK(stock_code, market, trading_date)
                            pe_ratio / pb_ratio / dividend_yield_pct  numeric(12,4)

  stock_financial_quarter   UK(stock_code, market, fiscal_year, fiscal_quarter)
                            eps numeric(12,4)
                            net_income_parent / equity_parent  bigint（單位：千元）

  stock_monthly_revenue     UK(stock_code, market, revenue_year, revenue_month)
                            revenue bigint（千元） / revenue_yoy_pct numeric(12,4)
  ```

  三表各有 `updated_at TIMESTAMP NOT NULL DEFAULT now()`（稽核用，不參與計算）與 `(stock_code, market)` ＋期別的複合索引。changeset 須寫成冪等（`CREATE TABLE / INDEX IF NOT EXISTS`），沿用本專案既有慣例。
- [ ] **`market` 欄位值為 `'台股'`**（繁體中文），與 `stock_price_history`／`stock` 一致（該欄實際值域只有 `台股`／`美股`／`英股`）。**不是 `'上市'`／`'上櫃'`。**
  這是本任務最容易靜默失敗的一點：寫錯會讓 t267 的基本面因子**永遠是 `null` 而不報任何錯**。驗證步驟須以實際 SQL 查詢確認 `market` 的 distinct 值。
- [ ] **新增 `stock.security_type` 欄位**（Requirement 46 AC「ETF 判定採資料驅動，跟隨專案既有決策方向」），值域 `STOCK`／`ETF`，nullable，供 ETF 判定的第一段使用（順序見 t267 的 267.3）。
  **必須同步新增 entity 欄位 `Stock.securityType`（`@Column(name = "security_type")`）**——本專案 `ddl-auto: none`，只加 DB 欄位而 entity 沒有對應欄位時，t267 的判定第 (1) 段讀不到值、會**靜默退到第 (2) 段**。既有先例 `stock.underlying_currency` ＋ `Stock.underlyingCurrency` 即為兩者一起加。
  **本欄刻意不建 `/api/settings/*` 與前端設定頁**（Requirement 46 AC「`security_type` 為技術判定 override，刻意不建設定頁（例外聲明）」）。
  **⚠ 這條例外先前被判 Critical 且未解，本任務必須正面回應，不得只重述。** `spec/tasks.md` 記載 t222 的三個 Critical 之一為：「`security_type` 免設定頁的例外聲明被同一 entity 上結構同構的 `stock_style` 反證（後者有完整設定表＋seed＋端點＋前端頁）」。反證屬實——`stock` 同一張表上的另兩個 nullable override 欄都有完整五件套：`stock_style`（`v1.29.0-stock-style.sql` 建設定表＋seed、`DataInitializer.seedStockStyles()`、CRUD DTO）與 `asset_class`（`AssetClassSettingsBffRoutes` → `/api/settings/asset-classes`、`AssetClassSettingsView.vue`）。
  **本任務的回應（採「反駁」而非「補齊」）**：`stock_style` 與 `asset_class` 是**使用者可自訂的業務分類**——前者帶可調的 `dividend_threshold` 參數、後者決定資產配置的呈現分組，值域與判定規則本來就屬於使用者的業務決策，故必須有設定頁。`security_type` 不同：其值域（`STOCK`／`ETF`）由**市場事實**固定，使用者無從也不應「設定」某檔股票是不是 ETF；它存在的唯一目的是在資料驅動判定（`etf_nav_history` 有無列）誤判時提供逃生門，屬**技術修正**而非業務設定。為它建設定頁反而會讓使用者以為可以自由改變標的性質。
  **但下列兩點為強制，不得省略**：(1) 此例外與修正路徑（DBA 手動 `UPDATE`）須記載於**欄位註解與 changeset `--comment`**；(2) 完成時須於 `spec/tasks.md` 該 Critical 條目旁標註「已由 t266 回應」，否則下一次稽核仍會撿出同一條。
- [ ] **`revenue_yoy_pct` 是刻意保留的衍生值**（Requirement 46 AC「`revenue_yoy_pct` 為刻意保留的 denormalization（例外說明）」）：它形式上可由前後年同月營收推得，但來源直接提供且本系統無去年同期基期，**屬刻意 denormalization**。此例外**須於欄位註解與 changeset `--comment` 明文記載**，否則違反 CLAUDE.md「禁止存入可從其他欄位計算得出的衍生值」。**同理禁止新增 `roe` 之類的衍生欄位**——ROE 於 t267 即時計算。

### 266.2 抓取（`external-materials-service`，比照既有 poller 慣例）

- [ ] 新增 `StockFundamentalPoller`。business-services **不得直連外部來源**（既有鐵則）。
- [ ] **⚠ 執行時點必須讀 DB，不得硬編字面 cron 時間**（Requirement 46 AC「排程、自癒與手動觸發（沿用既有慣例）」 明訂，被取代的 t222 亦有整段反向規定）。沿用既有的 `crawler_schedule` 機制：每分鐘 ticker ＋ 比對 `CrawlerScheduleQuery.enabledTimes("fundamental")`（`external-materials-service/.../CrawlerScheduleQuery.java:22-25`），參考實作為 `NewsPoller.java:130,149`。表缺列或 DB 例外時 fallback 至預設時點並記 WARN。
  changeset 須冪等 seed 一筆 `crawler_key = 'fundamental'`（`INSERT ... WHERE NOT EXISTS`，建議 15:30，格式參考現有三筆 `news-poller`）。**不要寫成「硬編 cron 字面時間」並宣稱那是本專案慣例——那正是 t222 明文禁止的。**
- [ ] **新增的 `@Scheduled` 須登錄排程清單**：`bff/src/main/java/com/steven/assets/bff/schedulelist/SchedulePublicBffController.java:53` 的 `JOBS`（Requirement 46 AC「排程列表頁需登錄」）。
- [ ] **開機自癒與手動觸發**（Requirement 46 AC「排程、自癒與手動觸發（沿用既有慣例）」）：`@EventListener(ApplicationReadyEvent.class)` 於啟動時補跑當日尚未執行者，`AtomicBoolean` 防重入，並提供 `/internal/*` 手動觸發端點。
- [ ] 來源為 TWSE／TPEx 開放 API 的估值（本益比／股價淨值比／殖利率）、財報（EPS／淨利／權益）、月營收三個資料集，上市與上櫃**兩個來源都要抓**（只抓上市會讓上櫃標的永久缺值）。
- [ ] **⚠ 財報須抓齊全部產業別分表**（Requirement 46 AC「產業別分表必須完整涵蓋」）：`_ci`／`_fh`／`_ins`／`_bd`／`_mim`／`_basi`。**只讀 `_ci` 會整段漏掉金融股**（如 2881、2885、2891——皆為使用者實際持股），且漏掉時三張表照樣有資料、不會報錯。
- [ ] **虧損公司仍須寫入一列**：`pe_ratio IS NULL` 且該日有列 = 虧損（無正 EPS 可算本益比）；「該日根本沒有列」才是未抓到。**兩者必須可區分**——t267 對前者計 `−1`、對後者回 `null`，行為完全相反。t222 實測來源 1079 筆中有 **247 筆**屬前者。
- [ ] **ETF 在來源中是整筆不存在**（t222 實測 `BWIBBU_ALL` 1079 筆中 `00` 開頭 0 筆），屬預期，不得視為抓取失敗。
  **但「整筆不存在」不得被下游當成 ETF 的判準**——同一個訊號在上一條的虧損／未抓到規則裡已經代表「未抓到」，兩者行為相反。ETF 判定一律走 t267 的 267.3 三段順序。
- [ ] **上市／上櫃的 JSON key 命名不同，須建立明確映射表**（Requirement 46 AC「產業別分表必須完整涵蓋」）：`mopsfin_t187ap06_O_ci` 用 `SecuritiesCompanyCode`／`Year`／`Season`，`mopsfin_t187ap07_O_ci` 則混用 `SecuritiesCompanyCode` 與中文 `年度`／`季別`。**不得假設兩市場欄位同名**——假設錯誤時該市場整批解析為 null，不報錯。
- [ ] **一律走整批端點**（Requirement 46 AC「整批端點，不逐檔輪詢」）：不得沿用逐檔請求 ＋ `Thread.sleep(300)` 的舊模式；須設 connect／read timeout 與失敗退避重試。
- [ ] **client 類別 Javadoc 須標示資料來源與授權依據**；**不得使用 FinMind 作為主來源**（Requirement 46 既有 AC）。
- [ ] **新增的 `@Scheduled` 登錄 `JOBS` 時，須同步更新 `SchedulePublicBffController.java:52` 的「（46 筆）」總數註解與所屬分組的筆數註解**——只加一筆 `ScheduledJobDto` 而不改註解，就是本專案反覆犯的計數漂移。
- [ ] upsert 覆寫同期別（同一日／同一季／同一月重抓時後寫覆蓋前寫）。
- [ ] 抓取失敗僅 `log.warn` 不阻斷其他 poller（沿用既有失敗策略）。
- [ ] 抓取的標的範圍：以既有 `PublicInfoStockFilter`／持股與觀察清單衍生的台股代號為準，不做全市場全量抓取（避免無謂流量）。**須確認該 filter 涵蓋觀察清單**，否則觀察標的會缺基本面。

### 266.3 本任務不做什麼

- **不改任何評分邏輯、不改 `RULE_VERSION`。** 本任務結束時雷達行為**一個位元組都不變**，只是資料庫開始累積。
- **不做歷史回補**（來源不允許，見上）。
- **不碰 ETF**（來源無資料）。

## 驗證

- [ ] 排程跑過一輪後，三張表各有資料，且 `SELECT DISTINCT market` 只回 `台股`。
- [ ] 虧損公司（`pe_ratio IS NULL` 但有列）與未抓到（無列）在 DB 中可區分，各舉一個實際代號為證。
- [ ] ETF 代號在三張表中皆無列，且抓取 log 不報錯。
- [ ] 上櫃標的有資料（證明兩個來源都抓了）。
- [ ] **金融股（2881／2885／2891）在 `stock_financial_quarter` 有資料**（證明產業別分表都抓了；只抓 `_ci` 時本條必然失敗）。
- [ ] 執行時點確實讀自 `crawler_schedule`：改該表的 `run_hour`／`run_minute` 後抓取時點隨之改變（不需重啟）。
- [ ] 新增的排程出現在排程清單頁（`SchedulePublicBffController` 的 `JOBS`）。
- [ ] **`PEratio` 為空字串時落地為 `NULL` 而非 `0`**（Requirement 46 AC）——落成 `0` 會讓虧損公司在 t267 的 PE 分位中被評為最優，且不報錯。
- [ ] **整批 JSON 解析涵蓋上市／上櫃兩套 key 命名**，各舉一個實際代號為證。
- [ ] `stock.security_type` 欄位與 `Stock.securityType` entity 欄位同時存在，且以 `@Column(name = "security_type")` 對應（只加其一時本條失敗）。
- [ ] 重跑同一日不產生重複列（UK ＋ upsert 生效）。
- [ ] 交易雷達的輸出在本任務前後**完全相同**（以同一組標的的分數與動作對照表為證）。

## 完成報告

（實作後回填）
