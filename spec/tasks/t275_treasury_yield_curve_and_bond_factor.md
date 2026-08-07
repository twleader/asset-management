# [t275] 美債殖利率曲線落地與債券 ETF 的利率因子——停止用股票指標評債券

**對應 Requirements:** Requirement 58（美債殖利率曲線落地與債券 ETF 的利率因子——四個天期的殖利率落地 `treasury_yield_daily`，並接為 `BOND` 專屬評分因子）
**前置任務:** t273（規則回測框架——利率因子的形式、方向與權重必須引用其量測輸出，不得憑「利率漲債券跌」的通則直接寫死）
**Liquibase changeset:** `v1.85.0-treasury-yield-daily.sql`（**預留版號**）

> **⚠ 複驗的判準是「該版號檔是否已存在」，不是「它是不是最大值」。** 本路線圖預留了 `v1.85.0`（本任務）與 `v1.87.0`（t266 基本面三表）；t277 的 `v1.86.0` 短期通知 changeset 已隨 t291 取代 t277 而取消。t266 是 t278 的前置、可能先落地，故 `ls backend/src/main/resources/db/changelog/changes/ | sort -V | tail` 可能回 `v1.87.0`——**那不代表本任務的 `v1.85.0` 被佔用**。實作前直接檢查 `v1.85.0-*.sql` 是否存在；若已被其他分支佔用，重新走 SDD 避讓，不得覆寫。

## 背景

### 缺口

以 `federal_funds`／`policy_rate`／`treasury`／`TNX`／`yieldCurve`／`FOMC` 六個識別字掃 `backend/src/main`、`external-materials-service/src/main`、`db` 三處（2026-08-01 實測，`grep -ranil`），**零命中**——唯一的 `treasury` 命中是 `backend/src/main/java/com/steven/assets/service/AssetClassifier.java` 的一句中文註解（「年期帶在標的名稱（如「元大美債20年」…「iShares 0-3 Month Treasury」）」），屬字串比對規則、非資料路徑。

債券 ETF 的價格主要由市場利率決定，而 `TW_RULES_V9` 的 14 個因子中沒有任何一個與利率有關。使用者持有 00679B／00697B／00719B／00865B 等債券 ETF，系統目前**等同於用股票的均線與 KD 在評債券**。

此缺口與 t274 修的乖離尺度失效**互相加乘**：這幾檔債券 ETF 既拿不到利率資訊，其極端態保護又從未啟動過（實測 00697B 的 `|bias|>20%` 觸發率為 0.00%）。

### 資料已確認可得（2026-08-01 實測）

| Yahoo 代碼 | 天期 | 實測筆數 | 日期範圍 | 最新值 | `meta.exchangeTimezoneName` |
|---|---|---|---|---|---|
| `^IRX` | 13 週 | 2514 | 2016-08-01 ~ 2026-07-31 | 3.682 | `America/Chicago` |
| `^FVX` | 5 年 | 2514 | 同上 | 4.460 | `America/Chicago` |
| `^TNX` | 10 年 | 2514 | 同上 | 4.745 | `America/Chicago` |
| `^TYX` | 30 年 | 2514 | 同上 | 5.275 | `America/Chicago` |

`^TNX` 的 `close` null 比例實測約 **0.04%**（美股假日對齊造成）。

**管道已存在**，不必新建：`external-materials-service/src/main/java/com/steven/assets/externalmaterials/client/MacroDataFetchClient.java` 的 `fetchUsIndexDaily(String indexCode)` 已處理——(1) `^` 需 URL-encode 為 `%5E`（raw `^` 在部分環境被 Yahoo 視為無效）；(2) 以 curl 子程序抓取，避開 Yahoo 對 Java HTTP/2 fingerprint 的封鎖；(3) 依 `meta.exchangeTimezoneName` 轉交易日（非寫死 NY）；(4) `range=10y&interval=1d`。

> **User-Agent 必須為短字串 `Mozilla/5.0`**。長 Chrome UA 會被 Yahoo 的 WAF 回 429。既有 client 已如此，不得改動。

## 要做什麼

### 275.1 `US_INDEX_YAHOO` 的容器型別必須改（會編譯失敗的硬約束）

- [ ] 275.1 `MacroDataFetchClient` 的 `US_INDEX_YAHOO` 現以 `Map.of(...)` 建構且**已有 9 對**：

  ```java
  private static final Map<String, String> US_INDEX_YAHOO = Map.of(
          "DJI", "^DJI", "SPX", "^GSPC", "SP500TR", "^SP500TR", "IXIC", "^IXIC",
          "SOX", "^SOX", "FTSE", "^FTSE", "DAX", "^GDAXI", "KOSPI", "^KS11", "N225", "^N225");
  ```

  `java.util.Map.of` 的多載上限為 **10 對**，該 Map 現有 9 對。

  **本任務採 (b)：另開 `TREASURY_YAHOO` Map ＋ 新增 `fetchTreasuryYieldDaily(String tenor)`。** 理由：
  1. **語意分離**——指數點數與殖利率百分比是兩種量綱，下游落地表也不同（見 275.2）；
  2. **兩張表各 ≤ 10 對，`Map.of` 完全不必改動**；
  3. 不會讓 `spec/design.md` 中 `/internal/macro/us-index` 的 `code ∈ {9 個}` 值域敘述靜默失準。

  > **⚠ 初版寫的「無論選哪個，`Map.of` 的 10 對上限都必須處理」是錯的——採 (b) 時那是假約束。** 只有在改為併入單一 Map（13 對）時才必然編譯失敗、才必須改 `Map.ofEntries`，屆時還須同步更新 design.md 的 `code` 值域敘述。**本任務已定案採 (b)，不要再走 (a)。**

### 275.2 新表 `treasury_yield_daily`

- [ ] 275.2 Liquibase changeset `v1.85.0-treasury-yield-daily.sql` 建表：

  | 欄位 | 型別 | 說明 |
  |---|---|---|
  | `tenor` | `varchar(8)` NOT NULL | 值域 `M3`／`Y5`／`Y10`／`Y30`，對應 `^IRX`／`^FVX`／`^TNX`／`^TYX` |
  | `trading_date` | `date` NOT NULL | 交易日（依 `meta.exchangeTimezoneName` 轉換後） |
  | `yield_percent` | `numeric(10,4)` NOT NULL | **百分比**：`4.7450` 代表 4.745% |
  | `updated_at` | `timestamp` NOT NULL DEFAULT now() | 稽核用，不參與計算 |

  - 業務唯一鍵 `(tenor, trading_date)`。
  - **全域公開行情資料**，比照 `stock_price_history` **不帶 `owner_user_id`、不套 `@Filter`**。
  - changeset 內所有 `CREATE`／`ADD` 一律冪等（`IF NOT EXISTS`）——避免日後版號避讓改名時 Liquibase 視為新 changeset 重跑而失敗。
  - **changeset 的 `--comment` 一旦寫定就不要再改**：Liquibase 的 checksum **包含註解**，改註解會造成 `ValidationFailed` 而讓 business-services 進入 crash loop。編號避讓用的 `sed` 必須排除 `db/changelog/`。

- [ ] **changeset 必須註冊進 `db.changelog-master.yaml` 的檔尾**：該 master **全部是顯式 `- include:`**（實測 `grep -c includeAll` ＝ **0**），最後一筆現為 `v1.84.0-asset-transaction-fee-tax.sql`。**漏註冊時 Liquibase 不報錯、服務照常啟動**，直到第一次寫入該表才炸。寫法：

  ```yaml
  - include:
      file: db/changelog/changes/vX.Y.Z-<slug>.sql
      relativeToChangelogFile: false
  ```

  順序須排在現有最後一筆之後。

- [ ] 275.2.1 **不得併入既有 `us_index_daily_history`**。該表的欄位是 `open_point`／`high_point`／`low_point`／`close_point`（皆 `NUMERIC(14,4)`），語意為「指數點數」；殖利率是百分比，量綱不同。混存會讓下游無從分辨一個 `4.745` 是點數還是百分率。另本表只需收盤殖利率、不需 OHLC。

### 275.3 抓取與落地

- [ ] 275.3.1 **首次執行落地全部 10 年歷史**（實測各 2514 筆，四個 tenor 合計約 10,056 筆）；之後每日增量。寫入採 **upsert**，同一批重跑須冪等。

- [ ] 275.3.2 **`close` 為 null 的日子一律略過不寫**，**不得以前一日的值回填**。回填會製造出「利率連續數日不動」的假事實，而利率因子正是要偵測其變動。缺日由下游以「取最近一個有值的交易日」處理。

- [ ] 275.3.3 **落地路徑沿用既有的兩段式**（ext-materials 抓 → backend proxy → upsert），比照 `us_index_daily_history` 的既有實作：
  - ext-materials：`InternalPriceController`（`external-materials-service/src/main/java/com/steven/assets/externalmaterials/controller/InternalPriceController.java`，該服務唯一的 controller）新增 `GET /internal/macro/treasury-yield?tenor=`，回 `List<TreasuryYieldPoint>`。
  - backend：`MacroHistoryService`（`backend/src/main/java/com/steven/assets/service/MacroHistoryService.java`）新增 `refreshTreasuryYieldDaily(String tenor)`，比照既有 `refreshUsIndexDaily(String code)` 的形狀（經 `priceServiceClient` proxy → `saveAll` upsert → log `upserted=N (from~to)` → 回 `Map`）。

- [ ] 275.3.4 **手動觸發端點**：backend 新增 `POST /internal/macro/treasury-yield/refresh?tenor=`（四個 tenor 各跑一次，或不帶參數時全跑）供部署後驗證與失敗補救。

  > **⚠ 服務歸屬**：`/internal/dividend/sync` 所在的 `InternalPriceController` 位於 **external-materials-service**，**不是** business 端的既有慣例（business 端只有 `UserAdminController` 的 `/internal/users`）。路由風格可比照，但該類別不在本服務。**授權立場須二選一寫死**：納入 `AdminGateInterceptor` 的 `addPathPatterns`，或刻意只靠「容器不對外映射 8080」。
  >
  > **本端點須同步登錄進 `spec/design.md` 的 business-services `/internal` 契約表**，否則該表會與實作漂移。

- [ ] 275.3.5 **排程採固定 cron，置於 business-services，比照 `IndexDailyRefreshScheduler`（刻意不走 `crawler_schedule`）**：

  ```java
  @Scheduled(cron = "0 0 7 * * TUE-SAT", zone = "Asia/Taipei")
  ```

  與既有 `backend/src/main/java/com/steven/assets/service/IndexDailyRefreshScheduler.java:46` 完全同一形狀。美股收盤在台北時間隔日凌晨，週二至週六早上取前一交易日資料是同一個理由。changeset **不 seed `crawler_schedule`**。

  > **⚠ 為什麼刻意不用 `crawler_schedule` 的 DB 可設定機制**（初版寫的作法，已推翻）：
  >
  > 1. **該機制的類別只存在於 external-materials-service。** 實測 `grep -rln "CrawlerScheduleQuery" backend/src external-materials-service/src` → 只命中 ext 的 4 個檔，`backend/src` **零命中**。排程若落 business 則類別不存在；若落 ext 則 ext 需反向呼叫 business 做 upsert，**違反 `spec/steering/structure.md` 的「❌ external-materials-service 反向呼叫 business-services」**。
  > 2. **新增的 `crawler_key` 沒有任何設定 UI。** `CrawlerDataBffController` 硬寫 `NEWS_POLLER`，前端零命中。排程列表頁會依慣例標「動態：依『爬蟲資訊查詢』頁設定」，但那頁改不到——形成**假說明**。t222 已把同一問題記為「已知未修」，本任務不重蹈。
  > 3. **business 端同類資料（macro／指數日線回補）的既有先例就是固定 cron。** 本項與 `us_index_daily_history` 是同一個資料家族。
  >
  > 「DB 可設定」在沒有設定 UI 的情況下不帶來實質可設定性，只帶來一個不存在的承諾。**若日後要改為可設定，應連同設定 UI 一併做並另立任務。**
  >
  > （參考：`crawler_schedule` 現況實測只有 3 列、`crawler_key` 皆為 `news-poller`、08:20／11:30／20:30、`enabled = t`。）

- [ ] 275.3.6 **新增的 `@Scheduled` 須同步登錄至「公開資訊 → 排程列表」，並逐處更新五個硬編筆數**（`SchedulePublicBffController` 的靜態 `JOBS` 清單）。該清單為手動維護，漏登即造成該頁與實際排程漂移——這是本專案排程漂移的固定成因（Task 195／196／197／228 已反覆修正同一類）。

  **實測現況（2026-08-01，`bff/src/main/java/com/steven/assets/bff/schedulelist/SchedulePublicBffController.java`）**：

  | 位置 | 現值 | 本任務後 |
  |---|---|---|
  | `:13` javadoc 分組數 | business **17**／external **29** | business **18**／external 29 |
  | `:16` 計數慣例說明 | external 29 | 不變 |
  | `:52` 「全系統排程清單（46 筆）」 | **46** | **47** |
  | `:54` business 分組註解 | `// ===== business-services（17）=====` | **18** |
  | `:107` external 分組註解 | `// ===== external-materials-service（29）=====` | 不變 |
  | `spec/design.md` 的「共 **46 筆** ＝ business 17 ＋ external 29」 | 46／17／29 | **47／18**／29 |

  > 本任務的 `@Scheduled` 落在 **business**（見 275.3.5），故只動 business 側與總數。**數字一律以 `grep -c "new ScheduledJobDto(" bff/src/main/java/com/steven/assets/bff/schedulelist/SchedulePublicBffController.java` 實查後填入**（實測現值 46，與宣稱一致），不得硬抄本檔。

### 275.4 利率因子接入評分

- [ ] 275.4.1 新增的利率因子**只對 `InstrumentType.BOND` 生效**。`EQUITY` 時該因子回 `null`，權重由 `TradingRadarRuleEngine.Accumulator` 按既有機制重分配給其餘因子。

  > **不得以 0 充當中性值**。`Accumulator.add(weight, contribution)` 在 `contribution == null` 時直接 return（不累加 `sumW`），故 `null` 會讓權重自動按比例分攤；傳 `0` 則會把股票拉向 50 分。這是既有機制，照用即可，但必須用對。

- [ ] 275.4.2 **因子的形式、方向與權重必須由 t273 的量測決定，不得憑「利率漲債券跌」的通則直接寫死。** 待決定的項目：用殖利率的**水準**、**變動**、還是**曲線斜率**（如 `Y10 − M3`）；用哪一個 tenor；正向或反向；權重多少。須在程式碼註解與完成報告列出所依據的量測數字。

- [ ] 275.4.3 **存續期間（duration）缺口須明白記載為已知限制，不得以猜測值填補。**

  把殖利率變動換算成價格變動的係數是存續期間，而本系統**沒有任何欄位承載 duration 或 YTM**。實測（2026-08-01）元大投信 `www.yuantaetfs.com/robots.txt` 為 `User-agent: * / Allow: /`（發行 00679B／00719B／0050／0056）、復華投信 `www.fhtrust.com.tw` 僅擋 `GPTBot`——**兩家投信官網都未禁止一般存取**，但尚未找到穩定的產品頁路徑（試過的 URL 皆 404）。

  本任務**不要求**取得 duration，改以「該 ETF 自身價格對殖利率變動的**歷史敏感度**」由本專案資料迴歸得出（`stock_price_history` 的還原序列 × `treasury_yield_daily`，兩者皆為自有資料），這在數學上正是經驗存續期間。

  > **嚴禁寫死任何猜測的 duration 值**——那會讓一個錯誤係數以精確數字的外觀進入評分，且不會有任何報錯。

  **迴歸規格（已先行實測，實作須複驗，但下列各項不得省略）：**

  - [ ] **(1) 時間對齊：用「該台股交易日之前最後一個美債交易日」的殖利率變動**，不得用同一個日曆日期。美債收盤在台北時間之後，同日對齊會取到尚未反映的資訊。
  - [ ] **(2) 被解釋變數為還原權息／還原分割後的台股日報酬**（與評分序列同源）。
  - [ ] **(3) 須同時輸出「單變量」與「控制 USD/TWD 日變動」兩組係數**供比對，完成報告兩者都要列。**`ΔFX` 必須取該台股交易日「當日」的匯率變動**（與已 lag 的 `ΔY` 同期）——若也取前一日，`corr(ΔY, ΔFX)` 只有 **−0.004**，等於根本沒控制（正確對齊為 **0.2216**）。

  **先行實測結果（`^TNX` × 各債券 ETF，2016-08-01 起）：**

  | 標的 | 單變量斜率（%／殖利率 +1pp） | **控 FX 後利率 β** | FX β | n |
  |---|---|---|---|---|
  | 00679B（美債20年） | −10.06% | **−10.94%** | 0.867 | 2322 |
  | 00751B（AAA至A公司債） | −7.97% | **−8.45%** | 0.470 | 1902 |
  | 00695B（美債7-10） | −4.91% | **−5.79%** | 0.860 | 2228 |
  | 00697B（投資級公司債） | −4.75% | **−5.59%** | 0.822 | 2219 |
  | 00719B（美債1-3） | −0.14% | **−1.01%** | 0.863 | 2062 |
  | 00865B（US短期公債） | +0.93% | **−0.01%** | 0.837 | 1617 |

  - [ ] **(4) ⚠ 利率因子不得無條件套用於所有 `BOND`。** 控制 FX 後 00719B 為 `−1.01%`、00865B 為 `−0.01%`（≈0），而 00679B 為 `−10.94%`——**相差一個數量級以上**。短天期債券本就幾乎沒有利率價格風險，與長天期共用係數等於**餵雜訊**，正是本節開頭要防的「錯誤係數以精確數字的外觀進入評分」。

    > ⚠ **不得再宣稱「00865B 是正號」**——單變量的 `+0.93%` 是 FX 混淆造成的假象，控制 FX 後為 `−0.01%`。

    **實作須逐檔檢核擬合結果，僅在係數符號為負且相關性達門檻時啟用該檔的利率因子**；不通過者該因子回 `null`（由 `Accumulator` 重分配權重，**不得填 0**）。門檻值由 t273 的框架量測後決定。

  - [ ] **(5) 利率與匯率在日頻上並非正交（`corr ≈ 0.22`），但 275.4.4「不得合併或互相取代」的規定維持不變——理由改為「兩者量的是不同東西」。** 控制 FX 後利率 β 會外擴 8–15%（短天期更大：00719B `−0.14%` → `−1.01%`）。FX β ≈ `0.47–0.87`，完成報告須一併列出。

    > ⚠ 初版寫的「經實測正交」是錯的，成因是當時的 `ΔFX` 取了前一日、與 `ΔY` 幾乎不相關（corr `−0.004`），等於沒有控制。

  - [ ] **(6) 測試**：以「已知 lag 與已知斜率」的構造序列斷言估出的係數落在容差內；另斷言時間對齊確實取「嚴格早於台股交易日的最後一個美債交易日」。

- [ ] 275.4.4 **不得改動既有的匯率因子**。債券 ETF 的台幣報價 ≈ 底層外幣價 × 匯率，此曝險已由 Requirement 47 的匯率分位因子（`W_FX = 0.05`）承接（實測 00719B 與 USD/TWD 近一年相關係數 0.9737、剝除匯率後底層僅動 2.05% 而台幣價動 10.34%）。利率因子量的是**底層資產本身**，兩者正交。**不得**以「都是債券的環境因子」為由合併或互相取代。

- [ ] 275.4.5 **權重重配**：新增因子後全部權重須重配並維持合計 `1.00`（由 `WEIGHT_SUM` 常數供測試斷言釘住，不得靠人工加總）。既有測試的期望分數會隨之改變——**須依新權重重算後更新期望值，並在完成報告列出新舊對照；嚴禁為了讓舊測試通過而回頭改權重。**

### 275.5 `RULE_VERSION` 升版

- [ ] 275.5 `RULE_VERSION` 升版一級。**同步點共五處**，以 `grep -ran "<現行版本字串>"` 取得（`-a` 不可省略：本專案有 `.java` 檔被 `file(1)` 誤判為 data，普通 `grep -r` 會整檔跳過）：

  | # | 檔案 | 位置 |
  |---|---|---|
  | 1 | `backend/src/main/java/com/steven/assets/service/TradingRadarRuleEngine.java` | `RULE_VERSION` 常數本體 |
  | 2 | `frontend/src/views/TradingRadarView.vue` | `radar` ref 的初始值 |
  | 3 | `frontend/src/views/TradingRadarView.vue` | 顯示 fallback |
  | 4 | `backend/src/main/java/com/steven/assets/dto/TradingRadarDto.java` | class Javadoc |
  | 5 | `backend/src/test/java/com/steven/assets/service/TradingRadarRuleEngineTest.java` | 版本斷言，**連同其測試方法名一併改** |

  > **⚠ grep 的命中數多於同步點數。** 以 `TW_RULES_V9` 實測為例，`grep -ran` 回 **7 個命中、分布在 5 個檔案**，但只有上表 5 處該改。另外兩個是 `TradingRadarRuleEngine.java` 權重表上方的說明註解（歷史敘述，改不改皆可），以及 **`backend/src/main/resources/db/changelog/changes/v1.83.0-radar-notification-rule-version.sql` 的 `--comment`——絕對不可改**：Liquibase 的 checksum **包含註解**，改了會 `ValidationFailed`、讓 business-services 進入 crash loop。**做批次取代的 `sed` 必須排除 `db/changelog/`。**

  > **實作順序警告**：`RULE_VERSION` 的字面值**取決於落地順序，本檔刻意不預設**。同時可能各佔用一級版號的有 t267、t274 與本任務；t276／t277 已由 t291 取代。設 `V(n)` 為實作當下的實際值，本任務升為 `V(n+1)`。**實作前必須先 `grep -ran "RULE_VERSION" backend/src` 讀取當下實際值，不得依本檔或任何 spec 的假設。**

- [ ] 275.5.1 **通知基準會自動重建**：`trading_radar_notification_setting.rule_version` 與現行 `RULE_VERSION` 不符時視同未初始化（機制由 Task 264 的 `v1.83.0-radar-notification-rule-version.sql` 建立），升級後首輪評估一律只建基準不寄信。須實際確認生效，否則升級首輪會對每筆訂閱狂發假通知。

### 275.6 前端文案

- [ ] 275.6 `spec/design.md` 現載「**前端不得宣稱雷達已納入利率或央行資訊**」。本任務落地後，**「利率」的部分解除**——前端可揭露債券標的已納入美債殖利率因子。但**「央行」的部分維持不變**：台灣央行政策利率仍無確認的官方 API（`data.gov.tw` 的路徑未查得、FRED 連線未驗證），本任務不納入，前端**不得**宣稱已納入央行或政策利率資訊。

## 驗證

### 單元測試

- [ ] **(a)** 四個 tenor 的 JSON 解析正確，`^` 已 encode 為 `%5E`。
- [ ] **(b)** `close` 為 null 的日子**不落地**（以含 null 的構造 JSON 斷言筆數）。
- [ ] **(c)** upsert 冪等：同一批資料寫入兩次，表筆數不變。
- [ ] **(d)** `EQUITY` 的利率因子為 `null` 且權重確實被重分配——斷言其分數與「該因子不存在」的結果相同（守住 275.4.1 不得傳 0）。
- [ ] **(e)** **兩張 Map 各自可解析**：`US_INDEX_YAHOO` 的 9 個指數（**且未被改動**，回歸斷言）與新增 `TREASURY_YAHOO` 的 4 個 tenor。
- [ ] **(f)** `WEIGHT_SUM` 仍為 `1.00`。
- [ ] **(g)** 既有的匯率因子行為未被改動（回歸）。
- [ ] **(h)** `RULE_VERSION` 已升版，測試方法名已改。

### 建置與部署

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true test
```

```bash
cp /Users/steven/Project/asset-management/.env .
```

```bash
docker compose -p asset-management build --no-cache business-services external-materials-service frontend
```

> **JVM service 一律 `--no-cache`**：cached build 可能產出不含本次變更的 stale jar（前端卻有變更），症狀是 gateway 404 或功能靜默消失。

```bash
docker compose -p asset-management up -d --no-deps --force-recreate business-services external-materials-service frontend
```

```bash
docker compose -p asset-management restart bff
```

### 實跑驗收

```bash
curl -s http://localhost:8080/actuator/health
```

> **這行檢查的是 bff，不是 business 也不是 ext-materials。** `docker ps` 顯示 `asset-bff` 對外 `0.0.0.0:8080->8080`，其餘 JVM 服務只有內部 `8080/tcp`。實測**只有 bff 有 actuator**——在 business 容器內打 `/actuator/health` 回 **500** `No static resource actuator/health.`。其餘服務是否活著改以下面的實際端點呼叫判斷。

以手動端點實跑一次四個 tenor，然後實查資料表：

```bash
docker exec asset-postgres psql -U assets -d assets -c "SELECT tenor, count(*) n, min(trading_date) mn, max(trading_date) mx, round(min(yield_percent),3) lo, round(max(yield_percent),3) hi FROM treasury_yield_daily GROUP BY tenor ORDER BY tenor;"
```

- [ ] 四個 tenor 各約 **2514 筆**（扣掉 null 的日子後略少）；
- [ ] 日期範圍約 **2016-08-01 ~ 2026-07-31**；
- [ ] **`yield_percent` 的量級須為個位數**（實測最新值 `^IRX` 3.682／`^FVX` 4.460／`^TNX` 4.745／`^TYX` 5.275）。**若出現 47.45 這種量級，代表把百分比當點數存了**——Yahoo 對 `^TNX` 回的就是 `4.745`，不需要任何換算。

### 排程列表頁驗收

- [ ] 開啟「公開資訊 → 排程列表」，確認新增的抓取排程有出現在清單中（`SchedulePublicBffController.JOBS` 已同步）。

### 通知基準重建驗收

```bash
docker exec asset-postgres psql -U assets -d assets -c "SELECT rule_version, count(*) FROM trading_radar_notification_setting GROUP BY rule_version;"
```

- [ ] 升版後首輪評估只建基準、未寄信（須實查 log 確認無該輪寄信紀錄）。

## 完成報告

（實作者做完後回填：實際改了哪些檔、驗證輸出、與原計畫的偏差及原因。**必須包含**：(1) 利率因子的形式／方向／權重所依據的 t273 量測數字；(2) 若採經驗存續期間路徑，列出各債券 ETF 迴歸出的敏感度係數與其樣本數；(3) 既有測試期望值的新舊對照表；(4) 實跑後 `treasury_yield_daily` 的四個 tenor 筆數與日期範圍。）
