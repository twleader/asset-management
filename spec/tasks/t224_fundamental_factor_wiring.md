# [t224] 交易雷達基本面組接線與啟用

> ## ⛔ 本任務已由 [t267](t267_fundamental_factor_wiring.md) 取代，不得再依本檔實作
>
> 本檔從未實作，且其權重接法綁在 t223 的五組分組正規化框架上。**四個子因子的定義、PE 的兩種 `null` 語意與優先序、EPS 負基期處理等內容仍然有效**，已完整移入 t267，僅把權重接法改為扁平。

**對應 Requirements:** Requirement 43 修訂（今日交易雷達——不呼叫 AI API 的台股規則式買賣決策輔助；本任務把已定義好權重位置的基本面組實際接上資料，使個股評分納入公司體質）
**前置任務:** t222（台股基本面資料每日抓取與歷史落地——提供 `stock_valuation_daily`／`stock_financial_quarter`／`stock_monthly_revenue` 三張表與 ETF 判定入口）、t223（分數飽和修正、長期趨勢組與動作門檻分層——提供分組加權正規化框架與基本面組的權重位置）
**Liquibase changeset:** 無（本任務不改資料庫結構）

---

> ## ⚠ 本任務尚未通過 spec 審查，不得進入實作
>
> `/spec-review` 三輪分數 5 → 5 → 7（門檻 8）。本任務自身的內容問題較少，但它**繼承前置任務 t223 的未解問題**（該檔頂部列有四個 Major），且 t223 的前置 t225 有兩個 Critical。t223／t225 修正並通過審查前，本任務不得開工。
>
> 本任務自身待確認的一項：224.5 引用的 ETF 判定入口，其具體類別與簽名由 t222 定義為 `backend/src/main/java/com/steven/assets/service/SecurityTypeResolver.java` 的 `String resolve(String code, String market)`。開工前請確認 t222 已實作且簽名一致。

---

## 背景

### 這個任務為什麼被單獨切出來

t223 已建立分組加權正規化框架，並把基本面組的組權重（`0.20`）與四個子因子的組內權重都定義好，但四個子因子一律回 `null`，由權重重分配吸收。本任務把它們實際接上 t222 落地的資料。

分成兩支任務的原因是**資料累積需要時間**。t222 的資料來源（TWSE／TPEx 開放 API）只提供「當期單一快照」，`?date=` 參數會被伺服器忽略，且歷史回補的唯一來源 MOPS 全站禁止程式化存取（`robots.txt` 為 `User-Agent: * / Disallow: /`）。故歷史只能自 t222 上線起自行累積：

| 子因子 | 需要的歷史 | t222 上線後可用 |
|---|---|---|
| 月營收 YoY | 3 個月（來源直給 YoY，不需去年基期） | 3 個月 |
| ROE | 4 季 | 約 1 年 |
| PE 自身歷史分位 | 250 交易日 | 約 1 年 |
| EPS 年增率 | 8 季（近四季 vs 前四季） | 約 **2 年** |

**執行本任務前須先確認資料量已達門檻**（見驗證段步驟 1）。資料未達門檻時實作仍可進行，但該子因子會持續回 `null`，屬預期行為。

### 本任務不改變的事

t223 建立的一切維持不變：五組權重、組內權重、缺值重分配規則、動作門檻三層分層、買進硬閘門、`RULE_VERSION`（仍為 `TW_RULES_V5`——本任務不改規則定義，只是讓既有的基本面組從「恆 `null`」變成「有值」）。

## 要做什麼

- [ ] **224.1 讀取資料來源**

  三張表的欄位（皆 nullable，`NULL` 代表無資料或虧損，**絕不會是 `0` 代表缺值**）：

  ```text
  stock_valuation_daily      UK(stock_code, market, trading_date)
                             pe_ratio / pb_ratio / dividend_yield_pct   numeric(12,4)

  stock_financial_quarter    UK(stock_code, market, fiscal_year, fiscal_quarter)
                             eps numeric(12,4)
                             net_income_parent / equity_parent  bigint（單位：千元）

  stock_monthly_revenue      UK(stock_code, market, revenue_year, revenue_month)
                             revenue bigint（千元） / revenue_yoy_pct numeric(12,4)
  ```

  三張表皆為全域公開行情資料，**無 `owner_user_id`、不套 Hibernate `@Filter`**，讀取時不需要也不得加 owner 條件。三張表另有 `updated_at TIMESTAMP NOT NULL DEFAULT now()`（稽核用，不參與計算）。

  查詢一律走 `(stock_code, market)` ＋期別排序取最近 N 期（三張表皆有對應的複合索引）。

  **`market` 的值為 `'台股'`**（繁體中文），與 `stock_price_history`／`stock` 表一致（該欄實際值域只有 `台股`／`美股`／`英股` 三種）。**不是** `'上市'`／`'上櫃'`。若查詢回零筆，第一個要檢查的就是 t222 是否把 `market` 寫成了上市／上櫃——那會讓基本面因子永遠是 `null` 而不報任何錯。

- [ ] **224.2 四個子因子的定義**

  | 子因子 | 定義 | 標準化 | 回 `null` 的條件 |
  |---|---|---|---|
  | EPS 年增率 | 最近四季 `eps` 合計 vs 前四季合計的增率 | `≥+20%` → `+1`、`≤−20%` → `−1`、線性內插 | 不足 8 季；**或前四季合計 `≤ 0`** |
  | ROE | 最近四季 `net_income_parent` 合計 ÷ 最新一期 `equity_parent` | `≥15%` → `+1`、`≤5%` → `−1`、線性內插 | 不足四季；或 `equity_parent ≤ 0` |
  | 月營收 YoY | 最近 3 個月 `revenue_yoy_pct` 的算術平均 | `≥+15%` → `+1`、`≤−15%` → `−1`、線性內插 | 不足 3 個月 |
  | PE 自身歷史分位 | 現行 `pe_ratio` 在該股自身歷史 `pe_ratio` 分布中的百分位 | `≤20 百分位` → `+1`、`≥80 百分位` → `−1`、線性內插 | 見 224.3 |

  **EPS 年增率的負基期**：前四季合計 `≤ 0`（去年虧損）時無法計算有意義的增率，**必須回 `null`，不得回 `+1`**——由虧轉盈的增率在數學上是正無窮或負值，兩者都會給出錯誤訊號。

  組內權重（沿用 t223 已定義的值，不得更改）：EPS 年增率 `0.30`、ROE `0.30`、月營收 YoY `0.20`、PE 自身歷史分位 `0.20`。

- [ ] **224.3 PE 子因子的兩種 `null` 語意與優先序（最容易做錯的一項）**

  `stock_valuation_daily` 中有兩種「沒有 PE」，**行為完全相反**：

  | 情況 | 意義 | 標準化貢獻 |
  |---|---|---|
  | **當日有列，但 `pe_ratio IS NULL`** | 公司**虧損**（無正 EPS 可算本益比） | **`−1`** |
  | 當日根本沒有列 | 當天沒抓到 | `null`（跳過） |

  t222 已保證虧損公司仍會寫入一列（`pe_ratio` 為 `NULL`，其餘欄位照常），故兩者可區分。實測來源 1079 筆中有 247 筆屬前者。

  **優先序（必須明確實作）**：虧損（有列且 `pe_ratio IS NULL`）一律計 `−1`，**與歷史樣本量無關**；只有「非虧損但歷史樣本 < 250 交易日」才回 `null`。

  這個優先序不是理論問題：t222 上線首年歷史樣本恆不足 250 個交易日，故對每一家虧損公司，「虧損」與「歷史不足」兩個條件**必然同時成立**。沒有優先序規定則行為未定義，且很可能實作成「歷史不足 → 直接 `null`」而讓虧損公司免於扣分。**必須有交集測試**。

- [ ] **224.4 PE 低分位的方向性限制（防止價值陷阱）**

  「PE 處於自身歷史低分位 → `+1`」預設「便宜＝機會」。但**長期衰退股的 PE 會持續停在自身歷史低位而永久取得正分**，恰與本次評分改造要解決的「區分基本面崩壞 vs 短期錯殺」相衝突。

  故加條件：**當 EPS 年增率子因子為 `−1`（即年增率 `≤ −20%`）時，PE 分位子因子一律回 `null`，不得給正分。**

  此交互須有專屬測試。

- [ ] **224.5 ETF 不套用基本面組**

  ETF 在 TWSE／TPEx 基本面資料集是「整筆不存在」而非空值（實測 `BWIBBU_ALL` 1079 筆中 `00` 開頭 0 筆），故 ETF 的四個子因子恆為 `null`，觸發 t223 已實作的權重重分配（四組變為 `0.275/0.225/0.375/0.125`）。

  ETF 判定**必須使用 t222 建立的判定入口**，其順序為：(1) `stock.security_type` override；(2) 資料驅動——該標的在 ETF 淨值資料中有紀錄即為 ETF；(3) fallback——台股 `00` 開頭。

  **不得使用 `US_ETF_WHITELIST` 白名單**（`MarketDataService.java:378` 與 `MarketDataFetchService.java:371` 各有一份完全相同的複本，皆把個股 `AVGO` 誤列為 ETF、皆漏 `SGOV`；專案既有決策已三度明文記載刻意不複用它）。若誤用，博通（在 `stock_price_history` 有 2515 筆完整歷史的實際持股）會被靜默排除於基本面評分之外——分數看似正常，不會有任何錯誤訊息。

  注意 `SGOV` 既是 ETF 也在 `AssetClassifier.US_BOND_ETFS` 中被歸為 `BOND`。兩個判定正交：`BOND` 決定是否套用台股大盤閘門，`ETF` 決定是否套用基本面組。同一標的兩者皆為真是合法狀態，不得假設互斥。

- [ ] **224.6 分數跳變的處理**

  基本面組由「全 `null`」轉為「有值」後，實際生效權重由 `0.275/0.225/0.375/0.125` 變回 `0.22/0.18/0.30/0.10/0.20`，**同一標的的分數會跳變**。

  處理方式與 t223 升版時相同：**上線後首輪評估一律只建通知基準不寄信**，避免 `last_action` 的舊基準與新動作比對而觸發大量假通知。

  各子因子陸續達到資料門檻時（月營收 3 個月、ROE 與 PE 約 1 年、EPS 約 2 年）也會造成較小幅度的跳變。

  **注意：通知去抖機制目前並不存在。** 它屬於 Task 218（交易雷達通知抖動抑制），實查該任務**尚未實作**——`trading_radar_notification_setting` 表只有 `last_action`／`last_counter_trend_state`，沒有 Task 218.1 要求的 `pending_action`／`pending_action_count` 等欄位，`databasechangelog` 也查無 `v1.67%` 的紀錄。

  故本任務**不得假設去抖機制會吸收這些跳變**。處理方式二選一，並在完成報告載明採用哪一種：

  - Task 218 屆時已完成 → 依賴其去抖機制，本任務只需處理首輪基準重建。
  - Task 218 屆時仍未完成 → **每次有子因子首度達到門檻時，一律重建通知基準**（該輪只建基準不寄信），避免分數跳變被誤判為狀態轉入而寄出假通知。

- [ ] **224.7 生效時程的揭露**

  前端可解釋性文案須揭露基本面組的實際狀態，不得讓使用者誤以為分數已含完整基本面判斷。至少區分三種情況：

  - 該組完全無資料（權重已重分配）
  - 該組部分子因子生效（顯示哪幾項生效、哪幾項仍在累積）
  - 該組四項全部生效

  例：「基本面：月營收 YoY 已生效；ROE／PE 分位／EPS 年增率資料累積中（分別需 4 季／250 交易日／8 季）」。

  **前端要改的檔案**：`frontend/src/views/TradingRadarView.vue`（基本面組的三態文案與子因子明細）。若需新增 API 欄位對應，改 `frontend/src/api/index.js`。

  **BFF 不需改動**：`bff/src/main/java/com/steven/assets/bff/tradingradar/TradingRadarBffRoutes.java` 是純 Spring Cloud Gateway rewrite passthrough，沒有 DTO 定義，新增的 JSON 欄位會自動穿透。

- [ ] **224.8 ROE 計算口徑的失真揭露**

  以 `最近四季淨利合計 ÷ 最新一期期末權益` 計算——來源**未提供平均權益**，故分母是期末權益而非期間平均；且單季數值年化會放大旺季效應。

  理由文案須標示為「近四季合計 ÷ 最新期末權益」的近似值，**不得呈現為經審計的正式 ROE**。

  TWSE／TPEx 皆未提供任何現成 ROE 欄位（兩站 swagger 搜尋「報酬率」皆無命中），此為已確認事實，不需再尋找來源。

- [ ] **224.9 不得儲存任何聚合結果**

  「近四季 EPS 合計」「營收成長率均值」「PE 歷史分位」「ROE」等一律**即時計算，不得寫回資料庫**。本專案規範禁止儲存可從其他欄位計算得出的衍生值。

  若效能有疑慮，先量測再決定；快取須是記憶體內且可失效的，不得新增資料表欄位。

- [ ] **224.10 零 AI API 約束不變**

  基本面因子一律由本地 PostgreSQL 的結構化數值計算。**不得**引入 LLM 判讀財報文字、**不得**於請求鏈觸發外部抓取。基本面資料由 t222 的排程預先落地，交易雷達只讀 DB。

- [ ] **224.11 測試**

  測試與實作同屬本任務，不得延後。至少覆蓋：

  - **PE 兩種 `null` 的優先序**：有列且 `pe_ratio IS NULL`（虧損）→ `−1`；當日無列 → `null`；**兩條件同時成立（虧損且歷史不足）→ `−1`**（交集測試，這是上線首年的常態）。
  - **PE 與 EPS 的交互**：EPS 年增率 `= −1` 時，PE 分位回 `null`（即使 PE 處於低分位）。
  - **EPS 負基期**：前四季合計 `≤ 0` 回 `null`，不得回 `+1`。
  - **ROE 分母保護**：`equity_parent ≤ 0` 回 `null`，不得拋例外。
  - **資料不足**：各子因子在各自門檻之下回 `null`；四項全 `null` 時觸發權重重分配且實際生效權重總和為 `1`。
  - **ETF 路徑**：ETF 的四個子因子全 `null`；`AVGO` 判定為 `STOCK`（**不得**因白名單誤判為 ETF 而跳過基本面）；`SGOV` 判定為 `ETF` 且同時可為 `BOND`。
  - **金融股**：2881／2885／2891（走 `_fh` 產業別端點）能正確取得 `eps`／`net_income_parent`／`equity_parent` 並算出 ROE。
  - **不變量未被破壞**：接上基本面組後，全部子因子皆 `+1`／皆 `−1`／疊加 `RISK_ON`／`RISK_OFF` 的極端輸入，分數仍落在 `[0, 100]` 且不觸發 clamp WARN。

  本專案在 Java 25 下跑 Mockito 需要 `-DargLine="-Dnet.bytebuddy.experimental=true"`（直接傳 `-D` 無效，surefire 會 fork 新 JVM）。

## 驗證

> **環境事實（已實測，照抄即可執行）**：容器名為 `asset-postgres`／`asset-bff`／`asset-business-services`／`asset-external-materials-service`（**不是** `asset-management-*-1`）。DB 的 user 與 database 皆為 `assets`。**只有 `asset-bff` 對 host 發佈 8080**；business-services 只在容器網路內聽 8080，**沒有 host port**。BFF 的 `/api/bff/**` 需登入，從 host 直接 curl 會回 **401**；免 OAuth 的做法是進 business 容器帶 `X-User-Id`／`X-User-Role`／`X-User-Status` header 打 business 端點（已實測回 200）。

```bash
# 1. 先確認各子因子的資料是否已達門檻（決定哪幾項會實際生效）
docker exec -i asset-postgres psql -U assets -d assets -c \
  "SELECT '季報' AS kind, stock_code, COUNT(*) AS periods FROM stock_financial_quarter
   WHERE market='台股' GROUP BY stock_code ORDER BY periods DESC LIMIT 5;
   SELECT '月營收' AS kind, stock_code, COUNT(*) AS periods FROM stock_monthly_revenue
   WHERE market='台股' GROUP BY stock_code ORDER BY periods DESC LIMIT 5;
   SELECT '估值' AS kind, stock_code, COUNT(*) AS days FROM stock_valuation_daily
   WHERE market='台股' GROUP BY stock_code ORDER BY days DESC LIMIT 5;"
# 門檻：季報 ≥8（EPS 年增率）／≥4（ROE）、月營收 ≥3、估值 ≥250

# 2. 單元測試
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test \
  -Dtest=TradingRadarRuleEngineTest \
  -DargLine="-Dnet.bytebuddy.experimental=true"
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test \
  -DargLine="-Dnet.bytebuddy.experimental=true"

# 3. 前端建置
cd frontend && /Users/steven/.nvm/versions/node/v22.21.0/bin/node \
  /Users/steven/.nvm/versions/node/v22.21.0/bin/npm run build && cd ..

# 4. 重建映像並重建容器（JVM 必須 --no-cache，否則可能產出不含本次變更的 stale jar）
docker compose -p asset-management build --no-cache business-services frontend
docker compose -p asset-management up -d --no-deps --force-recreate business-services frontend

# 5. 重建 business 會換 IP，BFF 握舊 IP 回 500 且不自癒（Docker DNS TTL 600s）
docker compose -p asset-management restart bff

# 6. 健康檢查
curl -s http://localhost:8080/actuator/health   # 只有 bff 有 actuator

# 7. 基本面組確實生效：個股應可見基本面組分數與子因子值
docker exec asset-business-services curl -s \
  -H "X-User-Id: 1" -H "X-User-Role: ADMIN" -H "X-User-Status: ACTIVE" \
  http://localhost:8080/api/trading-radar | head -c 2000; echo

# 8. ETF 的基本面組應為 null 且權重已重分配（對照組）
docker exec asset-business-services curl -s \
  -H "X-User-Id: 1" -H "X-User-Role: ADMIN" -H "X-User-Status: ACTIVE" \
  http://localhost:8080/api/trading-radar \
  | python3 -c "import sys,json; d=json.load(sys.stdin); [print(s.get('stockCode'), s.get('score'), s.get('groupScores'), s.get('effectiveWeights')) for s in d.get('stocks',[])]"

# 9. 虧損股的 PE 子因子確實計為 −1（挑一檔 pe_ratio IS NULL 的標的核對）
docker exec -i asset-postgres psql -U assets -d assets -c \
  "SELECT stock_code, trading_date, pe_ratio FROM stock_valuation_daily
   WHERE pe_ratio IS NULL AND market='台股'
   ORDER BY trading_date DESC LIMIT 5;"

# 10. 請求鏈確認沒有 AI API 呼叫
docker compose -p asset-management logs --since 2m business-services \
  | grep -iE "anthropic|openai|claude" || echo "無 AI API 呼叫（預期）"
```

驗收判準：步驟 7 個股可見基本面組分數（若步驟 1 顯示資料未達門檻，則該組仍為 `null` 屬預期，須在完成報告載明）；步驟 8 的 ETF 基本面組為 `null` 且 `effectiveWeights` 已重分配；步驟 10 無命中。

## 完成報告

（實作者做完後回填：實際改了哪些檔、上述驗證各步驟的真實輸出、**執行當下各子因子的資料累積量與實際生效的子因子清單**、接線前後的全標的分數對照、與原計畫的偏差及原因。）
