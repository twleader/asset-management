# [t222] 台股個股基本面資料每日抓取與歷史落地（TWSE／TPEx 開放 API）

> ## ⛔ 本任務已由 [t266](t266_stock_fundamental_ingestion.md) 取代，不得再依本檔實作
>
> 本檔從未實作。其後續 t224 綁在 t223 的五組分組正規化框架上，而該框架已由 Task 264 改採扁平權重取代。資料來源限制（TWSE／TPEx 只給當期快照、MOPS 禁爬故歷史無法回補）的分析仍然有效，已移入 t266。

**對應 Requirements:** Requirement 46（台股個股基本面資料每日抓取與歷史落地——因官方開放資料只給當期快照、不提供歷史查詢，系統須自上線第一天起自行累積時間序列，供交易雷達評分反映公司長期體質）
**前置任務:** 無
**Liquibase changeset:** `v1.68.0-stock-fundamentals.sql`

---

> ## ⚠ 本任務尚未通過 spec 審查，不得進入實作
>
> `/spec-review` 最近一輪 6/10（門檻 8）。以下問題**已查證屬實**，其中兩項已在本檔修正、其餘尚未：
>
> **已修正：** 驗證段的 `mvn -q test -pl backend` 在本專案無法執行（三個模組各自獨立、**沒有 root pom**，實跑回 `Could not find the selected project in the reactor`）——已全數改為 `mvn -q -f backend/pom.xml test`，並一併修正 `spec/tasks/README.md:78` 的模板（該模板是這個錯誤的源頭，會持續汙染後續任務檔）。business-services 與 external-materials-service **未啟用 actuator**（打 `/actuator/health` 分別回 500／404，只有 bff 可用）——健康檢查已改用實際存在的端點。
>
> **Critical 未修：`security_type` 免設定頁的例外聲明不成立。** 222.12 只拿 `asset_class`（多值域）當對照來論證「值域固定兩者、由規則自動判定、僅為逃生口」，但同一個 `Stock` entity 上的 **`stock_style` 與 `security_type` 結構完全同構**——`stock_style` 也恰好只有兩個值（`GROWTH`／`INCOME`）、也由規則自動判定（殖利率門檻 0.04），而專案給它的是**完整五件套**：`StockStyle` seed 表 entity、`InstitutionController.java:215/220/226/233` 的 `GET/POST/PUT/PATCH /api/settings/stock-styles`、`:173` 的 `PUT /api/settings/securities/stock-style` 逐檔 override、以及前端設定頁。`bond_term` 同構（`:187/192/198/205` ＋ `:179`）。**只挑最不利於自己的先例來比較，屬選擇性引用。** 修法二擇一：(a) 比照 `stock_style` 補齊 seed 表＋`/api/settings/security-types`＋逐檔 override 端點＋前端頁；(b) 若仍主張例外，必須正面處理 `stock_style`／`bond_term` 這兩個反例並說明實質差異。
>
> **Major 未修（六項）：**
> 1. **222.11 的「資料驅動」未指名資料來源。** backend 對 `etf_nav_history` **零命中**（`grep -rln "etf_nav_history\|EtfNavHistory" backend/src/main/java/` 無結果），唯一入口是 `PriceQueryService.java:105` 的 `getEtfNav()`，讀 Redis `price:etfnav:{market}:{code}` 且其 Javadoc 明寫「刻意不 fallback 到 DB」，實測 TTL 約 3.6 天。走 Redis → 抓取中斷逾 TTL 後 ETF 靜默降級為 STOCK 並被套上基本面因子（正是 222.3 花整節要防的靜默錯誤）；走 DB → 需新增 backend 的 `EtfNavHistoryRepository`，而本檔未列此工作項。須寫死來源與降級行為。
> 2. **222.9 沒在「要做什麼」寫死端點路徑**，驗證段卻自行加註「依實作結果調整」，違反本專案任務檔鐵則「約束寫在要做什麼裡」與「自足」。應直接寫死 `@PostMapping("/internal/fundamental/sync")`、無參數、回 `Map<String,Object>`，並註明與逐檔的 `/internal/dividend/sync`（`@RequestParam code, market`）不同。
> 3. **驗證步驟 13 無鑑別力**：`SecurityTypeResolver` 在本任務中不被任何路徑呼叫（接線在 t224），故 `/api/trading-radar` 的輸出在實作前後完全相同。應改為 resolver 的單元測試斷言或 `SELECT code, security_type FROM stock WHERE code IN (...)`。
> 4. **222.1 的 DDL 區塊刻意不完整**：`stock_valuation_daily` 的 `updated_at` 只在區塊外用散文補救，且 222.2／222.3／222.12 要求的三處 `COMMENT ON COLUMN` 一條都沒寫進 SQL。自足任務檔的 DDL 應可整段照抄。
> 5. **`design.md` 的 `AssetClassifier` 計數仍是舊的錯值**（`22+15`／`60+`），與本檔已修正的 `21+15`／`51` 打架。
> 6. **m1「比照 `upsertHistory`」指向較弱的範本**：`StockSourceQuery.java:377` 是 SELECT-then-UPDATE/INSERT，而同檔 `upsertNews`（:515）已用 `INSERT ... ON CONFLICT ... DO UPDATE`。三張新表都有 UNIQUE 約束，應比照後者。
>
> **Minor 未修：** 222.10 未給 `ScheduledJobDto` 的欄位值（該 record 有 7 個參數，且 DB 驅動排程的 `schedule`／`cron` 應填動態說明而非寫死時間）、`SchedulePublicBffController.java:52` 的「37 筆」註解已漂移（實測 43 筆）未要求一併更新；`crawler_key='fundamental'` 沒有設定 UI（`CrawlerScheduleController` 的 GET/PUT 都 `defaultValue = news-poller`、前端零命中），且會使 `CrawlerSchedule.java:12/33` 的「目前僅 news-poller」Javadoc 變成錯述；222.4 未定義 client 的回傳型別與 DTO 欄位。

---

## 背景

### 要解決的問題

交易雷達（`/trading-radar` 頁面）目前的評分因子 100% 是技術面：價格相對 MA20／MA60／MA240 的位置、三條均線的兩日確認、KD9、大盤 regime、單日漲跌幅。完全沒有任何公司體質資訊（EPS、營收成長、ROE、本益比、殖利率皆無），資料庫也沒有任何基本面欄位。

後果是個股的低分無法區分兩種相反情境：「基本面崩壞，應出場」與「短期錯殺，應加碼」。本任務負責建立資料基礎；評分邏輯本身由 t223 處理。

### 為什麼這件事有時間壓力（最重要的一點）

TWSE／TPEx 開放 API **只回傳「當期單一快照」，且 `?date=` 參數會被伺服器直接忽略**——實測對 `t187ap06_L_ci` 傳 `?date=11412`，回傳的仍是 `11506` 期別的資料。

歷史月營收的唯一來源是公開資訊觀測站 MOPS，而 `https://mopsov.twse.com.tw/robots.txt` 內容為：

```
User-Agent: *
Disallow: /

User-Agent: bingbot
Allow: /mops/web
```

即**全站禁止程式化存取，僅開放 bingbot**。因此沒有合規的歷史回補管道。

推論：**時間序列只能從本任務上線那天起自行累積，每延後一天上線就永久缺一天且無法補回。** 這是本任務優先於評分邏輯（t223）實作的唯一理由——爬蟲可以先上線默默存資料，評分公式之後再迭代。

### 已實測確認的事實（不需重新查證）

以下皆為 2026-07-19 實際打端點取得的結果：

- TWSE 開放 API `https://openapi.twse.com.tw/` 共 143 支端點，**無需 API key、無需註冊**，連續 8 次請求無節流跡象，`robots.txt` 回 404。
- `BWIBBU_ALL` 回 1079 筆，`t187ap06_L_ci` 回 1043 筆，`t187ap05_L` 回 1082 筆——**皆為整批單次回傳全市場**。
- TPEx 開放 API `https://www.tpex.org.tw/openapi/` 共 225 支端點，同樣免 key。
- **ETF 在全部基本面資料集是「整筆不存在」而非空值**：`BWIBBU_ALL`／`t187ap05_L`／`t187ap06_L_ci` 中 `00` 開頭的筆數皆為 0。
- **TWSE 與 TPEx 皆未提供任何現成 ROE 欄位**（兩站 swagger 搜尋「報酬率」皆無命中）。不需再尋找 ROE 來源。
- `BWIBBU_ALL` 的 1079 筆中有 **247 筆 `PEratio` 為空字串**，代表該公司虧損（無正 EPS 可計算本益比），不是資料缺漏。

`BWIBBU_ALL` 的真實回傳樣本：

```json
{"Date":"1150717","Code":"1102","Name":"亞泥","PEratio":"11.16","DividendYield":"6.92","PBratio":"0.67"}
```

注意 `Date` 為**民國年格式**（`1150717` = 2026-07-17），落地前須轉為西元 `date`。

### 合規界線（不得逾越）

TWSE／TPEx 使用條款禁止以爬蟲程式擷取本站資料，但**明文豁免**「已授權『政府資料開放平臺』提供公眾使用之本網站資料」。`/openapi/` 正是該授權通道，政府資料開放授權條款允許程式化取用與衍生著作，義務為標示出處。

因此：

- ✅ **允許**：`https://openapi.twse.com.tw/v1/...`、`https://www.tpex.org.tw/openapi/...`
- ❌ **禁止**：抓取 MOPS（`mops.twse.com.tw`／`mopsov.twse.com.tw`，全站 `Disallow: /`）
- ❌ **禁止**：抓取 TWSE／TPEx 的 HTML 頁面（不在開放資料豁免範圍）
- ❌ **本任務不實作歷史回補**：TWSE 舊版 `rwd/zh/afterTrading/BWIBBU_d?date=YYYYMMDD` 雖可回溯至 2005-09-02，但非官方指定的開放資料通道，屬合規灰色地帶。若日後要做須另立 Requirement 並記錄合規評估結論。
- ❌ **不得**引入 FinMind 作為本任務的資料來源。其免費層強制逐檔查詢（不帶 `data_id` 回 400），全市場掃一輪需 7 小時以上，且免責條款明列「不得將即時資料直接呈現於 web、App 等對外介面」「不包含對外再散布」。既有價格路徑使用 FinMind 屬既成事實，本任務不擴大其使用範圍。

TWSE／TPEx openapi 本身即為 MOPS 資料的官方 JSON 轉發（TPEx 端點前綴直接叫 `mopsfin_`），所以繞過 MOPS 不會損失資料。

## 要做什麼

- [ ] **222.1 建立 Liquibase changeset `v1.68.0-stock-fundamentals.sql`**

  檔案置於 `backend/src/main/resources/db/changelog/changes/v1.68.0-stock-fundamentals.sql`，並在 `backend/src/main/resources/db/changelog/db.changelog-master.yaml` 末尾以既有格式追加：

  ```yaml
    - include:
        file: db/changelog/changes/v1.68.0-stock-fundamentals.sql
        relativeToChangelogFile: false
  ```

  **版號說明：目前 DB 已套用的最新 changeset 是 `v1.66.0-index-export-schedule`；`v1.67.0` 已由尚未實作的通知去抖任務預定（檔案尚未建立），故本任務刻意跳到 `v1.68.0` 避免撞號。不得改用 `v1.67.0`。**

  **全部語句必須寫成冪等**（`CREATE TABLE IF NOT EXISTS`、`ADD COLUMN IF NOT EXISTS`）。理由：本專案曾因版號避讓而改 changeset id，Liquibase 會將改名後的 changeset 視為新 migration 重跑，非冪等語句會在「表已存在」時失敗並造成服務 crash loop。

  檔案開頭格式（比照既有 changeset）：

  ```sql
  --liquibase formatted sql

  --changeset steven:v1.68.0-stock-fundamentals
  --comment Requirement 46（Task 222）：<在此寫明設計決策與理由，比照既有 changeset 的詳盡 comment 慣例>
  ```

  建立三張表與一個欄位：

  ```sql
  CREATE TABLE IF NOT EXISTS stock_valuation_daily (
      id                  BIGSERIAL PRIMARY KEY,
      stock_code          VARCHAR(20)   NOT NULL,
      market              VARCHAR(20)   NOT NULL,
      trading_date        DATE          NOT NULL,
      pe_ratio            NUMERIC(12,4),
      pb_ratio            NUMERIC(12,4),
      dividend_yield_pct  NUMERIC(12,4),
      CONSTRAINT uq_stock_valuation_daily UNIQUE (stock_code, market, trading_date)
  );

  CREATE TABLE IF NOT EXISTS stock_financial_quarter (
      id                  BIGSERIAL PRIMARY KEY,
      stock_code          VARCHAR(20)   NOT NULL,
      market              VARCHAR(20)   NOT NULL,
      fiscal_year         INT           NOT NULL,
      fiscal_quarter      INT           NOT NULL,
      eps                 NUMERIC(12,4),
      net_income_parent   BIGINT,
      equity_parent       BIGINT,
      updated_at          TIMESTAMP     NOT NULL DEFAULT now(),
      CONSTRAINT uq_stock_financial_quarter UNIQUE (stock_code, market, fiscal_year, fiscal_quarter),
      CONSTRAINT ck_stock_financial_quarter_q CHECK (fiscal_quarter BETWEEN 1 AND 4)
  );

  CREATE TABLE IF NOT EXISTS stock_monthly_revenue (
      id                  BIGSERIAL PRIMARY KEY,
      stock_code          VARCHAR(20)   NOT NULL,
      market              VARCHAR(20)   NOT NULL,
      revenue_year        INT           NOT NULL,
      revenue_month       INT           NOT NULL,
      revenue             BIGINT,
      revenue_yoy_pct     NUMERIC(12,4),
      updated_at          TIMESTAMP     NOT NULL DEFAULT now(),
      CONSTRAINT uq_stock_monthly_revenue UNIQUE (stock_code, market, revenue_year, revenue_month),
      CONSTRAINT ck_stock_monthly_revenue_m CHECK (revenue_month BETWEEN 1 AND 12)
  );

  ALTER TABLE stock ADD COLUMN IF NOT EXISTS security_type VARCHAR(20);
  ```

  `stock_valuation_daily` 亦須加 `updated_at TIMESTAMP NOT NULL DEFAULT now()`（上方 DDL 已省略，實作時補上，三張表一致）。**三張表都不設 `fetched_date`**——`stock_valuation_daily` 已有 `trading_date`，另兩張的期別欄本身即標示資料歸屬期；「何時抓到」由 `updated_at` 承擔，且有 `DEFAULT now()` 故 upsert 不需顯式帶值。

  另建查詢索引（評分端會以 `(stock_code, market)` ＋期別排序取最近 N 期）：

  ```sql
  CREATE INDEX IF NOT EXISTS idx_svd_code_date  ON stock_valuation_daily  (stock_code, market, trading_date DESC);
  CREATE INDEX IF NOT EXISTS idx_sfq_code_period ON stock_financial_quarter (stock_code, market, fiscal_year DESC, fiscal_quarter DESC);
  CREATE INDEX IF NOT EXISTS idx_smr_code_period ON stock_monthly_revenue  (stock_code, market, revenue_year DESC, revenue_month DESC);
  ```

  **三張表皆為全域公開行情資料，比照現有 `stock_price_history`：不得加 `owner_user_id`、不得套 Hibernate `@Filter(ownerFilter)`。** 現有 `stock_price_history` 的實際欄位為 `id / close_price / high_price / low_price / market / open_price / stock_code / trading_date / volume`，無 owner 欄位，可據此對照。

  **`market` 欄位一律填 `'台股'`（繁體中文），不得填 `'上市'`／`'上櫃'`／`'TWSE'`／`'TPEx'`。** 實查現有 `stock_price_history.market` 的值域只有三種：`台股`（106,156 列）／`美股`（40,578）／`英股`（7,972）；`stock` 表的複合主鍵也是 `(code, market)` 且用同一組值。

  這是**跨表 join key**，填錯的後果是靜默的：下游評分以 `WHERE market='台股'` 查詢，若本任務寫入 `'上市'`，查詢會回零筆而讓基本面因子永遠是 `null`——不報錯、不告警，只是功能默默不生效。

  上市／上櫃的區別**不入 `market` 欄**。本任務不需要保留該區別（下游不使用）；若日後需要，另加 `listing_board VARCHAR(10)` 欄，不要挪用 `market`。

  現有 `stock` 表的實際欄位為 `code / market / name / asset_class / stock_style / bond_term`（`code` 與 `market` 為複合主鍵），本任務只新增 `security_type` 一欄。

  **同時須在 JPA entity 補上對應欄位**——`backend/src/main/java/com/steven/assets/model/Stock.java`。該類別是 Lombok `@Data` entity（**不是 record**，entity 用 `@Data` 是本專案既有慣例），使用 `@IdClass(StockId.class)` 的複合主鍵。既有三個 override 欄位都帶固定格式的 Javadoc，新欄位比照：

  ```java
  /**
   * 證券型態 override（Requirement 46）：STOCK / ETF。
   * 非空時覆蓋資料驅動判定；null 時依「ETF 淨值資料是否存在 → 台股 00 開頭」順序自動判定。
   * 誤判修正路徑為 DBA 手動 UPDATE，刻意不建設定頁（理由見 222.12）。
   */
  @Column(name = "security_type", length = 20)
  private String securityType;
  ```

- [ ] **222.2 禁止儲存衍生值（正規化規範）**

  **不得新增 `roe` 欄位。** ROE 可由 `net_income_parent ÷ equity_parent` 算出，儲存衍生值違反本專案「相同的資料只能存一份、禁止存入可從其他欄位計算得出的衍生值」的架構規範。同理**不得**儲存「近四季 EPS 合計」「營收成長率均值」「PE 歷史分位」等任何聚合或衍生結果——這些一律由消費端（評分引擎）即時計算。

  三張表的 `updated_at` 不是衍生值，而是「這筆資料何時寫入／更新」的事實記錄，用於稽核與判斷資料新鮮度，保留。（**不設 `fetched_date`**——見 222.1。）

  **`revenue_yoy_pct` 是刻意保留的 denormalization 例外，不得依上述規則刪除。** 它在自建歷史累積滿 13 個月後確實可由 `revenue(Y,M) ÷ revenue(Y−1,M) − 1` 重建，形式上屬衍生值。保留的理由：本表歷史自本任務上線起才開始累積，**滿 13 個月前沒有去年同月基期**，屆時來源直給的 YoY 是無法由本表重建的獨立事實；捨棄它等同讓下游的月營收 YoY 因子首年完全不可用。本專案允許刻意 denormalization 但要求加註說明（既有例外如 `realized_gain.broker` 記錄成交當下的券商名稱）。此理由須寫入該欄位的 `COMMENT ON COLUMN` 與 changeset 的 `--comment`，避免日後維護者依「禁止衍生值」規則誤刪。

- [ ] **222.3 NULL 與 0 的語意區分（最容易做錯的一項）**

  來源回傳空字串時一律寫入 `NULL`，**嚴禁以 `0` 代替**。

  理由具體且可觸發：`BWIBBU_ALL` 實測 1079 筆中有 247 筆 `PEratio` 為空字串，代表該公司**虧損**。若寫成 `pe_ratio = 0`，下游評分會讀成「本益比極低＝極度便宜」，把虧損公司評為最優標的——錯誤不會拋例外，只會靜默產生錯誤的投資建議。

  同樣規則適用於 `eps`、`net_income_parent`、`equity_parent`、`revenue`、`revenue_yoy_pct`、`pb_ratio`、`dividend_yield_pct`。

  **虧損公司仍須寫入一列。** 當來源回傳了該股票但 `PEratio` 為空字串時，**仍要寫入 `stock_valuation_daily` 一列**（`pe_ratio` 為 `NULL`，`pb_ratio`／`dividend_yield_pct` 照常寫入），不得因「沒有 PE」而整列略過。

  理由：下游評分對這兩種情況的行為**完全相反**——「有列且 `pe_ratio IS NULL`」＝公司虧損，計為負面訊號；「當日根本沒有列」＝當天沒抓到，該因子跳過不計。若虧損公司整列不寫，下游無從區分，虧損反而會被當成資料缺失而**免於扣分**。

  此語意區分須寫入 changeset 的 `--comment` 與各欄位的 DB 註解（`COMMENT ON COLUMN`），讓日後維護者不需翻任務檔就知道。

- [ ] **222.4 抓取 client：`FundamentalFetchClient`**

  置於 `external-materials-service/src/main/java/com/steven/assets/externalmaterials/client/FundamentalFetchClient.java`，比照同目錄既有 client 的命名與分層慣例（client 只負責抓取＋解析，不碰 DB）。

  類別 Javadoc 須標示資料來源 URL 與授權依據（政府資料開放授權條款，義務為標示出處）。

  **採整批單次 GET，不得逐檔輪詢。** 各端點單次即回傳全市場 800–1100 筆。既有價格路徑的「逐檔查詢＋`Thread.sleep(300)`」節流模式是為 FinMind 的逐檔 API 而設，套用在整批端點上會產生上千次無謂請求。此處只需設定 connect／read timeout 與失敗退避重試。

  端點對應表：

  | 目標 | 上市（TWSE，前綴 `https://openapi.twse.com.tw`） | 上櫃（TPEx，前綴 `https://www.tpex.org.tw/openapi`） |
  |---|---|---|
  | PE／PB／殖利率 | `/v1/exchangeReport/BWIBBU_ALL` | `/tpex_mainboard_peratio_analysis` |
  | EPS、淨利 | `/v1/opendata/t187ap06_L_<產業>` | `/mopsfin_t187ap06_O_<產業>` |
  | 股東權益 | `/v1/opendata/t187ap07_L_<產業>` | `/mopsfin_t187ap07_O_<產業>` |
  | 月營收＋YoY | `/v1/opendata/t187ap05_L` | `/mopsfin_t187ap05_O` |

  實作前請先以瀏覽器或 curl 確認各端點的完整路徑與當期欄位（開放資料端點偶有調整），但**不得改用非 openapi 通道**。

- [ ] **222.5 產業別分表必須完整涵蓋（漏做會靜默漏掉金融股）**

  損益表（`t187ap06`）與資產負債表（`t187ap07`）依產業切成多支端點，後綴至少包含：`_ci`（一般業）、`_fh`（金控）、`_ins`（保險）、`_bd`（證券）、`_mim`、`_basi`。

  **只讀 `_ci` 會整段漏掉金融股。** 具體受害者：2881（富邦金）、2885（元大金）、2891（中信金）皆為使用者實際持股，且在 `stock_price_history` 各有 2439 筆完整價格歷史——它們會有價格但永遠沒有基本面，症狀是評分靜默降級而非報錯。

  各產業端點的欄位結構不同（金融業損益表科目與一般業不同），須個別對應到 `eps`／`net_income_parent`／`equity_parent`；無法對應的產業須明確記錄於程式碼註解，不得靜默略過。

- [ ] **222.6 上市與上櫃的 key 命名映射（實測不一致，不得假設同名）**

  同一批端點混用英文與中文 key，已實測確認：

  | 端點 | 代號 key | 期別 key |
  |---|---|---|
  | `mopsfin_t187ap06_O_ci`（上櫃） | `SecuritiesCompanyCode` | `Year` / `Season` |
  | `mopsfin_t187ap07_O_ci`（上櫃） | `SecuritiesCompanyCode` | `年度` / `季別` |
  | `mopsfin_t187ap05_O`（上櫃） | `公司代號` | （月營收欄位為中文） |
  | TWSE 對應端點（上市） | 中文 | 中文 |

  須建立明確的 key 映射表（常數或 enum），不得在解析邏輯中散落字串字面值。解析時若預期的 key 全部缺失，須拋出明確錯誤而非回傳空結果——靜默回空會讓排程看似成功但零筆落地。

  民國年轉西元：`Date` 欄位如 `1150717` 代表 2026-07-17；財報期別如 `Year=115, Season=1` 代表 2026 Q1。落地一律轉為西元。

- [ ] **222.7 落地：`StockSourceQuery` 新增 upsert 方法**

  於既有的 `external-materials-service/src/main/java/com/steven/assets/externalmaterials/service/StockSourceQuery.java` 新增三個 upsert 方法（該類別為本服務的 DAO 門面，使用 `JdbcTemplate` 而非 JPA，刻意避免與 backend 重複維護 entity）。

  比照該檔既有 `upsertHistory` 的寫法。**upsert 必須冪等**：同一唯一鍵重複抓取須為更新而非新增，且重跑整個排程不得產生重複列。

  **JdbcTemplate 多列查詢的既有陷阱**：若需要多列查詢，callback 要寫成 void 區塊形式 `(rs) -> { list.add(...); }`；寫成 expression lambda 會被 Java 解析成 `ResultSetExtractor` 而非 `RowMapper`，導致 `rs` 未定位，runtime 才炸。

- [ ] **222.8 排程與自癒：`FundamentalPersister`**

  置於 `external-materials-service/src/main/java/com/steven/assets/externalmaterials/service/FundamentalPersister.java`，**以同目錄的 `DividendPersister.java` 為骨架**（低頻資料的既有範本），而非 `ClosePersister`（後者的 Redis 中繼複雜度對基本面無必要）。

  **不得經過 Redis。** Redis 在本專案專用於即時價格路徑（`price:{market}:{code}`）與盤中→收盤的中繼，基本面為低頻非即時資料，直接寫 PostgreSQL。

  須具備：

  - **執行時點讀 DB，不硬編字面時間**——沿用本專案既有的 `crawler_schedule` 樣板（`NewsPoller.java:136` 是參考實作）：

    ```java
    @Scheduled(cron = "0 * * * * *", zone = "Asia/Taipei")   // 每分鐘只當節拍
    public void tick() {
        if (!enabled) return;
        LocalTime now = LocalTime.now(TW_ZONE);
        // 比對 scheduleQuery.enabledTimes(CRAWLER_KEY) 取得的 (run_hour, run_minute) 清單
    }
    ```

    `CrawlerScheduleQuery.enabledTimes(String crawlerKey)`（`.../service/CrawlerScheduleQuery.java:22`）會執行
    `SELECT run_hour, run_minute FROM crawler_schedule WHERE crawler_key = ? AND enabled = TRUE`。

    本任務須在 `crawler_schedule` 新增 `crawler_key = 'fundamental'` 的 seed（建議台股收盤後，如 `15:30`），寫在 changeset `v1.68.0-stock-fundamentals.sql` 內並**寫成冪等**（`INSERT ... WHERE NOT EXISTS`）。表中現有 3 筆 `news-poller` 的 seed（`08:20`／`11:30`／`20:30`）可作為格式參考。

    表缺列或 DB 例外時須 fallback 到程式內的預設時間並記 WARN，不得整個排程靜默不跑。

    > **不要寫成「硬編 cron 字面時間」並宣稱那是本專案慣例。** 該服務有 32 處 `@Scheduled`，其中確有多處硬編，但 DB 可設定的排程機制**是存在的**——除 `crawler_schedule` 外，另有 `commodity_export_schedule`、`exchange_rate_export_schedule`、`export_schedule_setting`、`index_export_schedule`、`realized_gain_export_schedule`、`trading_calendar_export_schedule` 共 7 張排程設定表。新增排程應採用既有機制。

  - 抓取頻率：估值資料每日一次；財報與月營收每日檢查是否有新期別（公布日不固定，每日檢查最單純且不會漏）。三者可共用同一個到點觸發。
  - `@EventListener(ApplicationReadyEvent.class)` 開機自癒，於背景 thread 補跑當日錯過的抓取（既有慣例，`DividendPersister` 有）。
  - `AtomicBoolean` 防重入。**注意：這一項不是本服務的普遍慣例**——全服務僅 `NewsPoller` 一處使用，指定為骨架的 `DividendPersister` 並沒有。請比照 `NewsPoller` 的寫法補上，不要因為 `DividendPersister` 沒有就略過。
  - **逐端點 try/catch，單一端點失敗不中斷整批**，結束時 log 成功／失敗計數。既有範本的骨架為：

    ```java
    int ok = 0, fail = 0;
    for (...) {
        try { ...; ok++; }
        catch (Exception e) { fail++; log.warn("...: {}", e.getMessage()); }
    }
    log.info("基本面資料同步完成：成功 {} 項、失敗 {} 項", ok, fail);
    ```

  - 抓取端只處理來源實際回傳的列。ETF 在基本面資料集整筆不存在（實測 `BWIBBU_ALL` 1079 筆中 `00` 開頭 0 筆），故不會有「ETF 抓取失敗」這種情況——**不需要也不得在抓取端加 ETF 判定或例外處理**，加了反而會引入第二套判定邏輯。

- [ ] **222.9 手動觸發端點**

  於既有的 `external-materials-service/src/main/java/com/steven/assets/externalmaterials/controller/InternalPriceController.java`（`@RequestMapping("/internal")`）新增 `@PostMapping` 手動觸發端點，比照既有 `/internal/dividend/sync` 慣例。用於部署後驗證與抓取失敗補救。

- [ ] **222.10 排程列表頁同步登錄**

  新增的 `@Scheduled` 須同步登錄至 `bff/src/main/java/com/steven/assets/bff/schedulelist/SchedulePublicBffController.java` 的靜態 `JOBS` 清單。**該清單為手動維護，漏登即造成「公開資訊 → 排程列表」頁與實際排程漂移**——這是本專案排程清單漂移的固定成因。

- [ ] **222.11 ETF 判定：採資料驅動，跟隨專案既有決策方向**

  下游（評分引擎，另立任務）需要判定「這檔是不是 ETF」以決定是否套用基本面因子。本任務提供這個判定入口。

  **入口的具體位置與簽名**（不得只描述行為而不指名類別，否則下游任務無從引用）：

  ```
  backend/src/main/java/com/steven/assets/service/SecurityTypeResolver.java

  public class SecurityTypeResolver {
      /** 回傳 "STOCK" 或 "ETF"。判定順序見下。 */
      public String resolve(String code, String market);
  }
  ```

  置於 backend 而非 external-materials-service，因為使用者是 backend 的評分引擎。**抓取端（external-materials-service）不需要這個判定**——來源本就不會回傳 ETF 的基本面資料，抓取端只處理來源實際回傳的列，不存在「該不該抓 ETF」的判斷。

  **不得使用 `US_ETF_WHITELIST` 白名單。** 現況該白名單有**兩份完全相同的複本**，分屬兩個 deployable：

  ```
  backend/src/main/java/com/steven/assets/service/MarketDataService.java:378
  external-materials-service/.../service/MarketDataFetchService.java:371
  ```

  兩份都把 `AVGO`（Broadcom，個股）誤列為 ETF，也都漏了實際為 ETF 的 `SGOV`。

  **而專案既有決策已三度明文記載要棄用它**，不是待修的 bug 而是已下的判斷：

  ```
  MarketDataFetchService.java:641  「本方法即為『這檔是不是 ETF』的資料驅動判定，不需要維護 ETF 白名單
                                    （既有 isEtf() 白名單誤把個股 AVGO 列為 ETF、又漏掉使用者實際
                                    持有的 SGOV，刻意不複用）」
  EtfNavPoller.java:29             同旨
  ExcelExportService.java:795      「刻意不做 isEtf 白名單判定（既有白名單誤含個股 AVGO、又漏掉持有的 SGOV）」
  ```

  故本任務**跟隨既有方向**，判定順序為：

  1. `stock.security_type` override（`STOCK`／`ETF`，非 null 時直接採用）
  2. **資料驅動**：該標的在 ETF 淨值資料中有紀錄 → `ETF`
  3. fallback 規則：台股代號 `00` 開頭 → `ETF`；其餘 → `STOCK`

  **兩份既有白名單不在本任務的修正範圍**——它們服務於其他既有路徑，動它們是獨立議題，本任務不做方向以外的改動。但新程式碼一律不得引用它們。

  另注意 `AssetClassifier` **並非「完全沒有 ETF 概念」**（若你看到別處這樣描述，那是錯的）：它含 `HIGH_DIVIDEND_ETFS`（21 檔台股＋15 檔美股）、`US_BOND_ETFS`（51 檔，**已包含 SGOV**）、`isTwBondEtf()` 三組 ETF 專屬邏輯。準確的說法是它**輸出的分類軸是 CASH／BOND／STOCK，不含 STOCK-vs-ETF 這一軸**。本任務新增的是那條缺少的軸，不是取代 `AssetClassifier`。

  注意交互情形：`SGOV` 既是 ETF 也在 `US_BOND_ETFS` 中被歸為 `BOND`。兩個判定是正交的——`BOND` 決定是否套用台股大盤閘門（既有語意，不變），`ETF` 決定是否套用基本面因子。同一標的兩者皆為真是合法狀態，實作不得假設互斥。

- [ ] **222.12 `security_type` 刻意不建設定頁（例外聲明，須寫入欄位註解）**

  本專案規範「所有業務分類必須存入資料庫並提供 `/api/settings/*` 管理端點與前端設定頁面」。既有的 `asset_class` 具備完整五件套：

  ```
  InstitutionController.java:134-152   GET/POST/PUT/PATCH /api/settings/asset-classes
  InstitutionController.java:167       PUT /api/settings/securities/asset-class   ← 逐檔 override
  frontend/src/views/AssetClassSettingsView.vue
  bff/src/main/java/com/steven/assets/bff/assetclasssettings/
  ```

  `security_type` **刻意不比照辦理**。理由：它不是使用者可自訂的業務分類（值域固定為 `STOCK`／`ETF`，不會出現第三種），而是「這檔證券在市場上是不是 ETF」的客觀技術事實，主要由資料驅動判定得出；此欄位只是判定誤判時的逃生口，預期使用頻率極低。

  故本任務只建欄位，**不建設定表、不建 seed、不建管理端點、不建前端頁面、不建 BFF**。誤判時的修正路徑是 DBA 手動 `UPDATE stock SET security_type = 'STOCK' WHERE code = ...`，此路徑須寫入該欄位的 `COMMENT ON COLUMN`。

  **若日後發現需要頻繁人工修正，那代表資料驅動判定不可靠，屆時應優先修判定邏輯而非補設定頁。**

- [ ] **222.13 測試**

  測試與實作同屬本任務，不得延後。至少覆蓋：

  - 整批 JSON 解析：上市（中文 key）與上櫃（`SecuritiesCompanyCode`／`Year`／`Season` 與中文混用）兩套命名各一組。
  - `PEratio` 空字串落地為 `NULL` 而非 `0`。
  - **虧損公司（`PEratio` 空字串）仍寫入一列**，而非整列略過。
  - upsert 冪等性：同一唯一鍵連續寫入兩次，結果為一列且內容為後者。
  - 民國年轉西元：`1150717` → `2026-07-17`。
  - 產業別涵蓋：金融股端點（`_fh`／`_ins`）確實被納入抓取清單。
  - `SecurityTypeResolver.resolve()`：`security_type` override 優先於資料驅動；資料驅動優先於 `00` 開頭 fallback；台股 `0050` 判定為 `ETF`；`AVGO` 判定為 `STOCK`（**不得因白名單而誤判為 ETF**）；`SGOV` 判定為 `ETF` 且同時可為 `BOND`（兩判定正交，不互斥）。
  - `Stock` entity 的 `securityType` 欄位可正確讀寫（JPA 映射到 `security_type`）。
  - **`market` 欄位值域**：落地後 `SELECT DISTINCT market FROM stock_valuation_daily` 只能有 `'台股'`，不得出現 `'上市'`／`'上櫃'`。三張表各驗一次。
  - **排程時點讀 DB**：`crawler_schedule` 有 `crawler_key='fundamental'` 的 seed 時，到點才觸發；表中無該列時 fallback 到預設時間並記 WARN。

  本專案在 Java 25 下跑 Mockito 需要 `-DargLine="-Dnet.bytebuddy.experimental=true"`（直接傳 `-D` 無效，surefire 會 fork 新 JVM）。

## 驗證

> **環境事實（已實測，照抄即可執行）**：容器名為 `asset-postgres`／`asset-bff`／`asset-business-services`／`asset-external-materials-service`（**不是** `asset-management-*-1`）。DB 的 user 與 database 皆為 `assets`（**不是** `postgres`）。**只有 `asset-bff` 對 host 發佈 8080**；business-services 與 external-materials-service 只在容器網路內聽 8080，**沒有 host port**，必須用 `docker exec` 進容器打。BFF 的 `/api/bff/**` 需登入，從 host 直接 curl 會回 **401**；免 OAuth 的做法是進 business 容器帶 `X-User-Id`／`X-User-Role`／`X-User-Status` header 打 business 端點。

```bash
# 1. 後端建置與測試
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test \
  -DargLine="-Dnet.bytebuddy.experimental=true"
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml test \
  -DargLine="-Dnet.bytebuddy.experimental=true"

# 2. 端點可用性（實作前先確認，回傳應為 200 且 body 為 JSON 陣列）
curl -s -o /dev/null -w "BWIBBU_ALL=%{http_code}\n" \
  https://openapi.twse.com.tw/v1/exchangeReport/BWIBBU_ALL
curl -s https://openapi.twse.com.tw/v1/exchangeReport/BWIBBU_ALL | head -c 300; echo

# 3. 重建映像並重建容器（本專案沒有 dev server，「改好」的定義是 image rebuild + container recreate）
#    JVM 服務必須 --no-cache，否則 cached build 可能產出不含本次變更的 stale jar
docker compose -p asset-management build --no-cache external-materials-service business-services
docker compose -p asset-management up -d --no-deps --force-recreate external-materials-service business-services

# 4. 重建 business 會換 IP，BFF 會握著舊 IP 回 500 且不會自癒（Docker DNS TTL 600s），須一併重啟
docker compose -p asset-management restart bff

# 5. 健康檢查
#    注意：只有 bff 啟用了 actuator。business-services 打 /actuator/health 會回 500
#    （"No static resource actuator/health."），external-materials-service 回 404——
#    那兩個服務要用實際存在的端點探活。
curl -s http://localhost:8080/actuator/health
docker exec asset-external-materials-service \
  curl -s -o /dev/null -w "ext=%{http_code}\n" -X POST http://localhost:8080/internal/refresh
docker exec asset-business-services curl -s -o /dev/null -w "business=%{http_code}\n" \
  -H "X-User-Id: 1" -H "X-User-Role: ADMIN" -H "X-User-Status: ACTIVE" \
  http://localhost:8080/api/trading-radar

# 6. migration 實際套用確認
docker exec -i asset-postgres psql -U assets -d assets -c \
  "SELECT id, dateexecuted FROM databasechangelog WHERE id LIKE 'v1.68.0%';"

# 7. 手動觸發一次完整抓取（端點路徑依 222.9 實作結果調整；external 無 host port，須進容器）
docker exec asset-external-materials-service \
  curl -s -o /dev/null -w "sync=%{http_code}\n" -X POST http://localhost:8080/internal/fundamental/sync

# 8. 實查三張表確認真的有資料落地（不可只確認 HTTP 200）
docker exec -i asset-postgres psql -U assets -d assets -c \
  "SELECT (SELECT COUNT(*) FROM stock_valuation_daily)   AS valuation_rows,
          (SELECT COUNT(*) FROM stock_financial_quarter) AS quarter_rows,
          (SELECT COUNT(*) FROM stock_monthly_revenue)   AS revenue_rows;"

# 8b. market 值域必須是 '台股'（填錯會讓下游查詢靜默回零筆）
docker exec -i asset-postgres psql -U assets -d assets -c \
  "SELECT DISTINCT market FROM stock_valuation_daily
   UNION SELECT DISTINCT market FROM stock_financial_quarter
   UNION SELECT DISTINCT market FROM stock_monthly_revenue;"
# 預期：只有 台股

# 9. 金融股確實有落地（驗證產業別分表沒漏，這三檔走 _fh 端點）
docker exec -i asset-postgres psql -U assets -d assets -c \
  "SELECT stock_code, fiscal_year, fiscal_quarter, eps, net_income_parent, equity_parent
   FROM stock_financial_quarter WHERE stock_code IN ('2881','2885','2891') ORDER BY stock_code;"

# 10. 虧損股：pe_ratio 是 NULL 而非 0，且該列確實存在（不是整列略過）
docker exec -i asset-postgres psql -U assets -d assets -c \
  "SELECT COUNT(*) AS null_pe_rows      FROM stock_valuation_daily WHERE pe_ratio IS NULL;
   SELECT COUNT(*) AS should_be_zero    FROM stock_valuation_daily WHERE pe_ratio = 0;"

# 11. 冪等性：再次觸發抓取，三張表筆數不得增加
docker exec asset-external-materials-service \
  curl -s -o /dev/null -X POST http://localhost:8080/internal/fundamental/sync
docker exec -i asset-postgres psql -U assets -d assets -c \
  "SELECT COUNT(*) AS valuation_rows_after FROM stock_valuation_daily;"

# 12. 排程列表頁已登錄新排程（BFF 需登入，故改在 business 容器內驗；若該清單由 BFF 靜態提供，
#     則改為直接 grep 原始碼確認）
grep -c "基本面" bff/src/main/java/com/steven/assets/bff/schedulelist/SchedulePublicBffController.java \
  || echo "未登錄，須補 SchedulePublicBffController.JOBS"

# 13. ETF 判定正確（資料驅動，不得走白名單）
docker exec asset-business-services curl -s \
  -H "X-User-Id: 1" -H "X-User-Role: ADMIN" -H "X-User-Status: ACTIVE" \
  http://localhost:8080/api/trading-radar | head -c 500; echo
```

驗收判準：步驟 8 三張表皆非 0 筆；步驟 9 三檔金融股皆有列；步驟 10 的 `null_pe_rows` > 0（證明虧損公司有寫入）且 `should_be_zero` 必須為 0；步驟 11 的筆數與步驟 8 相同。

## 完成報告

（實作者做完後回填：實際改了哪些檔、上述驗證各步驟的真實輸出、與原計畫的偏差及原因。）
