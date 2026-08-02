# [t278] 台股歷史估值回補——推翻「預設不實作」的決定

**對應 Requirements:** Requirement 61（台股歷史估值回補——重新評估合規依據後推翻 Requirement 46 的「預設不實作」，使本益比分位子因子不必等待一年累積）
**前置任務:** t266（建立 `stock_valuation_daily` 表與每日抓取管道；本任務只回補歷史，不建表）
**Liquibase changeset:** 無（表由 t266 的 `v1.87.0-stock-fundamental.sql` 建立）

## 背景

### 本任務推翻的是什麼

`spec/requirements.md` 的 Requirement 46 有一條 Acceptance Criteria（逐字標題「**歷史回補為獨立決策，預設不實作**」），其理由為：

> 這兩個端點**並非官方指定的開放資料通道**，不在使用條款的開放資料豁免範圍內，屬合規灰色地帶。故本 Requirement **預設不實作歷史回補**；PE 分位子因子在樣本不足期間回 `null` 並由權重重分配吸收。**若日後決定實作，須另立 Requirement 並明確記錄合規評估結論**。

該條同時要求「若日後決定實作，須另立 Requirement」——Requirement 61 即為該條指定的另立 Requirement，本任務為其實作。

### 重新評估的依據（2026-08-01 實測）

**robots.txt 全文**（`https://www.twse.com.tw/robots.txt`）：

```
# robots.txt for https://www.twse.com.tw/

User-agent: Googlebot
Allow: /

User-agent: Googlebot-News
Allow: /

User-agent: OAI-SearchBot
Allow: /

User-agent: GPTBot
Allow: /

User-agent: *
Disallow: /epaper/
Disallow: /FTSE/
Allow: /
```

**`/rwd/zh/afterTrading/` 明確落在 `User-agent: *` 的 `Allow: /` 之下**，被禁的只有 `/epaper/` 與 `/FTSE/` 兩個路徑。

**端點實測**（`https://www.twse.com.tw/rwd/zh/afterTrading/BWIBBU_d?date=20200102&selectType=ALL&response=json`）：

- HTTP 200、`stat: "OK"`、`date: "20200102"`、**941 筆**
- `fields`：`["證券代號", "證券名稱", "收盤價", "殖利率(%)", "股利年度", "本益比", "股價淨值比", "財報年/季"]`
- 首兩筆：`["1101", "台泥", "44.10", "9.09", 107, "10.86", "1.36", "108/3"]`、`["1102", "亞泥", "48.90", "5.73", 107, "10.77", "1.15", "108/3"]`

故「技術上取不到」與「robots.txt 禁止」兩項均不成立。

### 本任務不推翻 MOPS 禁令

實測 `https://mopsov.twse.com.tw/robots.txt` 為：

```
User-Agent: *
Disallow: /

User-Agent: bingbot
Allow: /mops/web
```

Requirement 46 對 MOPS 的記載**正確且維持不變**。**EPS 年增率（需 8 季）與 ROE（需 4 季）確實只能自上線起累積**，本任務不改變此事實，也**不得**以任何方式繞過 MOPS 的禁令。

本任務只回補 **PE／PB／殖利率**三項。

## 要做什麼

### 278.0 服務歸屬：抓取必須落在 `external-materials-service`（架構鐵則）

- [ ] 278.0 本任務為**兩段式**落地，不得把抓取寫進 business-services。

  `spec/steering/structure.md` 的架構鐵則明訂：

  > - ❌ business-services 直接打外部行情 / NAV / 配息 API（**一律經 external-materials**）
  > - **抓價邏輯不寄宿在 business-services。** 外部 API 限流／失敗不影響主系統。

  **現況查證（2026-08-01）**：`grep -ran "twse.com.tw" backend/src/main` 只命中兩處——`v1.21.0-twse-daily-history.sql:5` 的一句來源註解，與 `MarketAnalysisService.java:948` 的新聞網域白名單字串。**business-services 目前對 TWSE 零個實際抓取，本任務不得成為第一個破例。** 另 `grep -ran "RequestMapping(\"/internal"` 顯示 business 只有 `/internal/users`（`UserAdminController`），而 `/internal` 的抓取端點全在 `external-materials-service` 的 `InternalPriceController`（該服務唯一的 controller）。

  | 服務 | 職責 |
  |---|---|
  | **external-materials-service** | 新增 client 類別（置於該服務的 `client/` 之下）實際 curl TWSE、套用短 UA、節流；於既有 `InternalPriceController`（`/internal`）新增**單日快照**端點 `GET /internal/valuation/twse-daily?date=`，回該日全上市個股的 PE／PB／殖利率 |
  | **business-services** | 新增**編排**端點 `POST /internal/valuation/backfill`：逐日 proxy 呼叫上述端點，取回後 upsert `stock_valuation_daily`；負責續跑點判定與範圍控制。**不得自行發任何指向 `twse.com.tw` 的 HTTP** |

  比照的既有模式是 `us_index_daily_history`：ext-materials 的 `/internal/macro/us-index?code=` 負責抓，business 的 `MacroHistoryService.refreshUsIndexDaily(code)` 經 `priceServiceClient` proxy → `saveAll` upsert → log `upserted=N (from~to)` → 回 `Map`。**照這個形狀做即可。**

### 278.1 合規結論寫進程式碼

- [ ] 278.1 回補用的 client 類別 Javadoc 須載明：資料來源、上述 robots.txt 實測全文與判讀、TWSE 使用條款的評估結論、以及顯名出處標示。

  此為 Requirement 46 既有要求「程式碼須於 client 類別 Javadoc 標示資料來源與授權依據」的延伸。**寫在 spec 而沒寫進程式碼不算完成**——日後維護者讀的是程式碼。**該 Javadoc 隨 client 類別落在 `external-materials-service`**（見 278.0）。

### 278.2 執行方式：低頻單次，不排程

- [ ] 278.2.1 透過 `/internal` 手動端點觸發（比照既有 `/internal/dividend/sync` 慣例），**不得**新增 `@Scheduled`。故「公開資訊 → 排程列表」（`SchedulePublicBffController.JOBS`）**不需新增項目**。

- [ ] 278.2.2 **每個請求之間 sleep ≥ 1000ms**（寫死秒數，不得只寫「須有節流」——填 50ms 也算通過就失去意義）。節流間隔須以**可注入的計時器替身**實作，使驗證 (h) 能直接斷言該值。回補是一次性作業，沒有速度需求；把伺服器打爆才是真正會失去存取權的方式。

- [ ] 278.2.3 **User-Agent 一律使用短字串 `Mozilla/5.0`**。長 Chrome UA 會被 WAF 擋（本專案在 Yahoo 端已踩過同一個坑）。

- [ ] 278.2.4 **失敗須可從中斷處續跑**，不得每次都從頭重來。回補涵蓋數千個請求日，中途失敗是常態。續跑點的判定建議以「`stock_valuation_daily` 中已存在資料的最大／最小 `trading_date`」推導，不另建狀態表。

### 278.3 parser 依 `fields` 動態對應（會靜默寫錯資料的硬約束）

- [ ] 278.3 **parser 須依回應中的 `fields` 陣列動態對應欄位，不得依索引位置硬編。**

  `BWIBBU_d` 的回傳欄位在 2017／2018 年間變更過。**四個日期的實測結果（2026-08-01）**：

  | 日期 | 欄數 | `fields` | 首筆（1101 台泥） |
  |---|---|---|---|
  | 2015-01-05 | 5 | `證券代號`／`證券名稱`／**`本益比`**／`殖利率(%)`／`股價淨值比` | `['1101','台泥','14.88','5.35','1.42']` |
  | 2017-01-03 | 5 | 同上 | `['1101','台泥','20.56','3.78','1.23']` |
  | 2018-01-02 | 8 | `證券代號`／`證券名稱`／**`收盤價`**／`殖利率(%)`／`股利年度`／`本益比`／`股價淨值比`／`財報年/季` | `['1101','台泥','36.55','3.97',105,'19.97','1.20','106/3']` |
  | 2020-01-02 | 8 | 同上 | `['1101','台泥','44.10','9.09',107,'10.86','1.36','108/3']` |

  變更點落在 2017-01-03 與 2018-01-02 之間。

  > **依位置硬編的具體後果**：`index 2` 在 5 欄時代是 `本益比`、在 8 欄時代是 `收盤價`，故跨越變更點會**把收盤價寫進本益比欄**——2020-01-02 的 1101 會記成「本益比 44.10」，真值是 10.86。錯誤資料會以完全正常的外觀入庫，且不會報錯。
  >
  > **更危險的是 `殖利率(%)` 恰好在兩個版本都位於 `index 3`。** 只抽驗殖利率會通過，讓錯誤看起來像沒發生——所以驗收時**必須抽驗本益比本身**（見驗證段）。
  >
  > 另注意 8 欄版的 `股利年度`（`index 4`）是**未加引號的整數**（`107`），其餘欄位都是字串。這個型別差異可作為欄位對位的交叉印證。

- [ ] 278.3.1 遇到 `fields` 中找不到預期欄位名時，該日**整批略過並記 WARN**，不得寫入部分欄位。

- [ ] 278.3.2 **不得與 t266 的每日抓取共用同一個 parser。** 兩者來源端點的欄位命名與 schema 不同（每日抓取走 `openapi.twse.com.tw` 的 `BWIBBU_ALL`，本任務走 `www.twse.com.tw/rwd/zh/afterTrading/BWIBBU_d`），硬共用會讓其中一邊在對方改版時靜默壞掉。

### 278.4 落地目標與空值語意（沿用 Requirement 46 既有規定，逐字複寫）

- [ ] 278.4 寫入 t266 建立的 `stock_valuation_daily`：

  | 欄位 | 型別 | 說明 |
  |---|---|---|
  | `stock_code` | — | 業務唯一鍵之一 |
  | `market` | — | 業務唯一鍵之一，**一律填 `'台股'`** |
  | `trading_date` | `date` | 業務唯一鍵之一 |
  | `pe_ratio` | `numeric(12,4)` **nullable** | 本益比 |
  | `pb_ratio` | `numeric(12,4)` **nullable** | 股價淨值比 |
  | `dividend_yield_pct` | `numeric(12,4)` **nullable** | 殖利率(%) |

- [ ] 278.4.1 **`market` 一律填 `'台股'`，不得填 `'上市'`／`'上櫃'`。** 該欄是跨表 join key（與 `stock_price_history`／`stock` 對齊，實際值域只有 `台股`／`美股`／`英股` 三種），填錯會使下游 `WHERE market='台股'` **靜默回零筆**、基本面因子永遠為 `null` 而不報錯。

- [ ] 278.4.2 **缺值標記是 `'-'`（不是空字串），一律寫入 `NULL`，嚴禁以 `0` 代替。**

  > **⚠ 兩個來源的缺值標記不同——這是「兩個 parser 不得共用」的第二個理由。** 實測 `BWIBBU_d?date=20200102` 的 941 筆中，`本益比` 欄有 **191 筆為 `'-'`、0 筆為空字串**；而 Requirement 46 記載每日來源 `BWIBBU_ALL` 用的是**空字串**（1079 筆中 247 筆）。**回補 parser 必須把 `'-'` 與空字串兩者都映射為 `NULL`**，只處理空字串會讓全部 191 筆虧損公司走進數值解析而拋例外或落地成錯值。

  `'-'` 代表該公司虧損（無正 EPS 可計算本益比），**不是**資料缺漏。`pe_ratio = 0` 會在下游被解讀為「本益比極低＝極便宜」而把虧損公司評為最優。

- [ ] 278.4.3 **虧損公司仍須寫入一列**（`pe_ratio` 為 `NULL`，其餘欄位照常），不得因「沒有 PE」而整列略過。理由：下游規定「有列且 `pe_ratio IS NULL`＝虧損，計為 `−1`」與「當日無列＝未抓到，回 `null`」是兩種不同語意且**行為相反**；若虧損公司整列不寫，下游無從區分，虧損會被誤判為資料缺失而免於扣分。

- [ ] 278.4.4 寫入採 **upsert**，與 t266 每日抓取路徑重疊的日期須冪等。

### 278.5 回補範圍與其代價

- [ ] 278.5 回補的起始日期須可指定（端點參數）。

  **量級評估**：全市場逐日回補十年 ≈ 2400 個請求日 × 每日約 900–1000 筆 ≈ **240 萬列**。實測 2020-01-02 回 941 筆。

- [ ] 278.5.1 須先評估並在完成報告記載這個量級對 `stock_valuation_daily` 的儲存與查詢影響。

- [ ] 278.5.2 **若決定縮小範圍**（例如只回補使用者實際持有與觀察的標的、或只回補近五年），須明白記載縮小的範圍與理由，**不得靜默截斷**。

- [ ] 278.5.3 **上櫃與 ETF 明確不在範圍，且必須在文案上與「查無」「虧損」區分開來。**

  `BWIBBU_d` 只涵蓋上市。回補後仍然沒有資料的有兩類，理由各不相同：

  | 類別 | 為什麼沒有 | 下游該怎麼表現 |
  |---|---|---|
  | ETF | 來源整筆不存在（Requirement 46 已載明，本任務實測複驗：20 檔 ETF 於 1081 筆中 0 命中） | PE 分位回 `null`，權重重分配 |
  | 上櫃個股（實例：7556 意德士） | `BWIBBU_d` 不涵蓋上櫃；**TPEx 舊 PHP 端點的合規判斷未被重新評估**（實測 `https://www.tpex.org.tw/robots.txt` 回 **HTTP 302**，連 robots.txt 都取不到），Requirement 46 對該端點的原結論維持有效 | 同上 |

  故：
  - **Requirement 46 的「樣本不足回 `null` 並由權重重分配吸收」那條退路不得移除**——它是這兩類標的的長期依靠，不隨本任務而作廢。
  - **不得**讓使用者以為「所有台股都有本益比分位」。查無資料、虧損（`pe_ratio IS NULL` 但有列）、該市場不在回補範圍——**三者語意不同，不得混為一談**。
  - 若日後要補上櫃，須另行評估 TPEx 的合規性並另立 Requirement，**不得**逕自沿用本任務對 TWSE 的結論。

  > **建議的縮小方式**：以標的縮小而非以時間縮小。PE 分位需要「該標的自身的歷史分布」，時間縮短會直接削弱分位的意義。
  >
  > **但「49 檔」不是正確的估算基數。** `stock_price_history` 中 `market='台股'` 且非 `0000` 者確為 49 檔（2026-08-01 實測），然而：
  >
  > | 類別 | 檔數 | 來源是否回傳 |
  > |---|---|---|
  > | ETF（0050／0056／006205／006208／00642／00646／00679B／00695B／00697B／00713／00719B／00751B／00850／00865B／00878／00881／00882／00919／00929／009804） | 20 | **否**——實測 `BWIBBU_d?date=20260731` 的 1081 筆中 0 命中，與 Requirement 46「ETF 在來源整筆不存在」一致 |
  > | 上櫃個股（7556 意德士） | 1 | **否**——`BWIBBU_d` 只涵蓋上市 |
  > | 上市個股 | **28** | 是（已抽驗 2330、3711 皆在來源中） |
  >
  > 故實際可落地者為 **28 檔、約 6.7 萬列**，不是 49 檔／12 萬列。
  >
  > **來源是整批回傳，縮小標的只省儲存、不省請求數。**

### 278.6 回補後的下游驗證

- [ ] 278.6 回補完成後，PE 分位子因子因樣本不足而回 `null` 的前提不再成立。須確認該因子的樣本門檻邏輯會**正確地因為歷史資料存在而啟用**，並以**實資料**驗證（而非僅靠單元測試）。

  > 若 t267（把基本面接成評分因子）尚未實作，本項改為確認「資料已就緒，t267 實作時無須再等待累積」，並在完成報告記載回補後的實際樣本數。

### 278.7 不得改動每日抓取路徑

- [ ] 278.7 t266 的每日抓取（`BWIBBU_ALL` 等 openapi 端點）維持不變。回補是另一條獨立路徑。

## 驗證

### 單元測試

- [ ] **(a) parser 依 `fields` 動態對應而非索引硬編（最重要的一條）**：以兩組**欄位順序不同**的構造 JSON 斷言解析結果相同。可直接用實測的 8 欄順序（`證券代號`／`證券名稱`／`收盤價`／`殖利率(%)`／`股利年度`／`本益比`／`股價淨值比`／`財報年/季`）與一組刻意打亂的順序對照——若實作依索引硬編，此測試必失敗。
- [ ] **(b) `fields` 缺欄位時整批略過並記 WARN**，不得寫入部分欄位。
- [ ] **(c) 空字串落地為 `NULL` 而非 `0`。**
- [ ] **(d) 虧損公司（`本益比` 為空字串）仍寫入一列**，且其 `pb_ratio`／`dividend_yield_pct` 照常有值。
- [ ] **(e) upsert 冪等**：同一日回補兩次，表筆數不變。
- [ ] **(f) `market` 欄位為 `'台股'`**（不是 `'上市'`／`'上櫃'`）。
- [ ] **(g) 中斷後可從續跑點接續**：以部分資料已存在的狀態呼叫，斷言不重抓已完成的日期。
- [ ] **(h) 節流**：斷言連續請求之間有間隔（以注入的計時器替身驗證，不實際連網）。

### 建置與部署

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true test
```

```bash
cp /Users/steven/Project/asset-management/.env .
```

```bash
docker compose -p asset-management build --no-cache business-services external-materials-service
```

```bash
docker compose -p asset-management up -d --no-deps --force-recreate business-services external-materials-service
```

```bash
docker compose -p asset-management restart bff
```

> `restart bff` 不可省略：recreate `business-services` 會換 IP，BFF 握舊 IP 會讓每個 `/api/**` 回 500 且 3 分鐘以上不自癒（Docker DNS TTL 600s）。

### 實跑驗收

健康檢查（**注意：host 的 8080 是 bff，不是 business**——`docker ps` 顯示 `asset-bff` 對外 `0.0.0.0:8080->8080`，而 `asset-business-services` 只有內部 `8080/tcp`。實測**只有 bff 有 actuator**：business 容器內打 `/actuator/health` 回 **500** `No static resource actuator/health.`，故不要拿它當 business 的健康探針）：

```bash
curl -s http://localhost:8080/actuator/health
```

先驗 ext-materials 的單日抓取端點（**抓取落在該服務，見 278.0**）：

```bash
docker exec asset-external-materials-service curl -s "http://localhost:8080/internal/valuation/twse-daily?date=2020-01-02" | head -c 1500
```

再以**一小段日期區間**試跑 business 的編排端點（不要一次跑十年）：

```bash
docker exec asset-business-services curl -s -X POST "http://localhost:8080/internal/valuation/backfill?from=2020-01-02&to=2020-01-31"
```

實查落地結果：

```bash
docker exec asset-postgres psql -U assets -d assets -c "SELECT count(*) rows, count(DISTINCT stock_code) codes, min(trading_date) mn, max(trading_date) mx, round(100.0*count(*) FILTER (WHERE pe_ratio IS NULL)/count(*),2) pe_null_pct FROM stock_valuation_daily;"
```

- [ ] 筆數與日期範圍符合所指定的回補區間；
- [ ] **`pe_ratio` 的 null 比例應與來源同一量級**——Requirement 46 記載 `BWIBBU_ALL` 實測 1079 筆中 **247 筆為空字串（約 22.9%）**。若回補資料的 null 比例接近 0%，代表空字串被誤寫成了 `0`；若接近 100%，代表欄位對應錯了。
- [ ] **抽驗必須跨越 schema 變更點，且必須驗本益比本身**（`殖利率(%)` 在 5 欄與 8 欄版都位於 `index 3`，只驗它會讓欄位錯位靜默通過）。至少驗這兩個日期的 `1101` 台泥：

  | 日期 | 來源欄數 | 期望 `pe_ratio` | 期望 `pb_ratio` | 期望 `dividend_yield_pct` |
  |---|---|---|---|---|
  | 2017-01-03 | 5 | **20.56** | 1.23 | 3.78 |
  | 2020-01-02 | 8 | **10.86** | 1.36 | 9.09 |

  > 若 2020-01-02 的 `pe_ratio` 落地為 **44.10**（該日收盤價），即為「依索引硬編」的典型症狀。

```bash
docker exec asset-postgres psql -U assets -d assets -c "SELECT trading_date, pe_ratio, pb_ratio, dividend_yield_pct FROM stock_valuation_daily WHERE stock_code='1101' AND trading_date IN ('2017-01-03','2020-01-02') ORDER BY trading_date;"
```

## 完成報告

（實作者做完後回填：實際改了哪些檔、驗證輸出、與原計畫的偏差及原因。**必須包含**：(1) 實際回補的範圍與筆數，若有縮小須列出範圍與理由；(2) 回補後 `stock_valuation_daily` 的筆數、標的數、日期範圍與 `pe_ratio` null 比例；(3) 與來源逐位比對的抽驗結果；(4) 儲存量級對查詢效能的實測影響。）
