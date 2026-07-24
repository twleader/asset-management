# [t227] 匯率曝險與換匯估值納入交易雷達評分

**對應 Requirements:** Requirement 47（匯率曝險與換匯估值納入交易雷達評分——讓系統不再把「美元升值推高的台幣報價」誤判為標的本身的多頭，並揭露某檔標的的漲勢有多少來自匯率）
**前置任務:** t223（分數飽和修正、長期趨勢組與動作門檻分層——本任務新增的匯率環境組是 `TW_RULES_V5` 分組加權正規化的第六組，沒有那個框架就沒有可插入的位置；且在飽和未修正前，任何新增扣分都會被 clamp 吃掉而完全無效）
**Liquibase changeset:** `v1.69.0-stock-underlying-currency.sql`

---

> ## ✅ 已實作並上線（`TW_RULES_V7`，commit `40f6458b`）
>
> 實作與本計畫有數處**刻意偏差**，已同步回寫 `requirements.md` Req 47（1252／1265／1266 條）與 `design.md`「Requirement 47」段。細節見文末「完成報告」，摘要：
>
> - 評分框架**採扁平因子權重，非本檔原述的巢狀六組**（扁平權重數學結果相同且少一層間接）；匯率因子權重為 **`0.05`**（`TradingRadarRuleEngine.W_FX`），非計畫中的 `0.08`。
> - 匯率分位回看期為 **五年**（`TradingRadarService.FX_LOOKBACK_YEARS = 5`），非計畫中的三年。
> - 買進閘門對 `fxPercentile ≥ 90` 設**否決**（`FX_EXPENSIVE_PCT`，與 Req 47〔1266〕一致）。
> - UI 揭露只落地 (a) `fxPercentile` 徽章＋五年期換匯貴賤 verdict tooltip；**(b) 台幣／底層變動對照（`台幣 +10.34%／底層 +2.05%`）未實作**（227.7 僅部分完成）。

## 背景

### 使用者的問題

「債券 ETF 的評分，似乎也沒有參考匯率，在美元很高時，給 100 分？美元很貴，應該要扣一點分數。」

實測證實這個質疑成立，而且比字面上更嚴重。

### 實測證據

**評分鏈路完全沒有匯率輸入。** `TradingRadarService`（461 行）、`TradingRadarRuleEngine`（374 行）、`TradingRadarDto`、`AssetClassifier`、`DistributionAdjustedPriceService` 五個檔案 grep `exchange|fx|rate|匯率|currency|幣別|usd|twd` 零實質命中（唯一命中是 `previousD`、`dividendRate`、`generatedAt` 等假陽性）。規則引擎的 `StockInput` record 全部 13 個欄位——`held / price / changePercent / completedChangePercent / indicators(ma20,ma60,ma240,k,d) / previousK / previousD / ma20Confirmation / ma60Confirmation / ma240Confirmation / instrumentType / marketRegime / marketStale`——**沒有任何幣別或匯率欄位**。這不是「權重太低」，是輸入端根本沒有這個維度。

**台幣計價的美債 ETF，其報價由匯率主導。** 近一年（2025-07-19 起）日收盤與 USD/TWD 中價的相關係數：

| 標的 | 存續期 | 水準相關 | 日報酬相關 |
|---|---|---|---|
| **00719B 元大美債1-3** | 1–3 年 | **0.9737** | **+0.6763**（R²=0.457） |
| 00697B 元大美債7-10 | 7–10 年 | 0.8449 | +0.3057 |
| 00679B 元大美債20年 | 20 年 | 0.5864 | +0.0845 |
| 0050（對照組） | — | 0.7175 | **−0.5339** |

相關性隨存續期**單調遞減**，符合「短債幾乎沒有利率風險，所以只剩匯率」的金融結構——這個模式交叉驗證了它不是統計巧合。0050 的水準相關 0.72 是共同趨勢造成的偽相關，日報酬一看即為負。

剝除匯率後（`implied = 台幣收盤 / 當日中價`）：**00719B 過去一年台幣價變動 10.34%，底層美元價值僅變動 2.05%。**

**而此刻美元正貴。** 2026-07-17 的 USD/TWD 中價 32.23，處於一年期百分位 **99.2**、全歷史百分位 **91.8**，且其自身為完美多頭排列（32.23 > MA20 32.00 > MA60 31.66 > MA240 31.29）。

> **結論：系統給 00719B 滿分的理由「最新價位於月／季／年線之上、已連續兩個收盤日站上」，跟 USD/TWD 自己的均線排列是同一件事。它在美元逼近一年新高的時點，把「美元多頭排列」翻譯成了「債券 ETF 買進候選」。** 這正是使用者擔心的高價換匯。

### 資料基礎已具備

`exchange_rate_history` 現有 4,261 列，其中 **USD 2,488 列涵蓋 2016-07-19 至 2026-07-19 整整十年**。表結構：`id / currency / rate_date / buy_rate numeric(10,4) / sell_rate numeric(10,4)`，UNIQUE `(currency, rate_date)`。

抓取管線在 `external-materials-service` 的 `ExchangeRatePoller`：盤中 `@Scheduled(cron = "0 0/5 9-15 * * MON-FRI", zone = "Asia/Taipei")`、收盤後 `0 0 17 * * MON-FRI`；台銀牌告（`BotFxFetchClient`）為主，被 Akamai WAF 擋時 USD fallback 到 `YahooFxFetchClient`（`buy = sell = mid`），隔日 17:00 由 FinMind（`ExchangeRateFetchClient`）以真實買賣價覆寫同一列。

**本任務只消費既有資料，不新增任何抓取。**

### 結構性障礙：系統無法表達「匯率曝險」

`stock` 表的實際欄位只有 `code / market / name / asset_class / stock_style / bond_term`，**沒有 currency 欄位**。三檔美債 ETF 的 `market` 皆為 `台股`、`asset_class` 皆為空（`BOND` 是 `AssetClassifier` 執行期推導出來的）。

所以系統無法區分「以台幣交易且持有台幣資產」與「以台幣交易但持有美元資產」——這是本任務必須先解決的前提。

## 要做什麼

- [x] **227.1 建立 Liquibase changeset `v1.69.0-stock-underlying-currency.sql`**

  檔案置於 `backend/src/main/resources/db/changelog/changes/v1.69.0-stock-underlying-currency.sql`，並在 `backend/src/main/resources/db/changelog/db.changelog-master.yaml` 末尾以既有格式追加：

  ```yaml
    - include:
        file: db/changelog/changes/v1.69.0-stock-underlying-currency.sql
        relativeToChangelogFile: false
  ```

  **版號說明：DB 已套用的最新 changeset 為 `v1.66.0-index-export-schedule`；`v1.67.0` 已由通知去抖任務預定、`v1.68.0` 已由基本面資料任務預定（兩者檔案皆尚未建立），故本任務用 `v1.69.0`。不得改用 v1.67／v1.68。**

  **全部語句必須冪等**（`ADD COLUMN IF NOT EXISTS`、`UPDATE ... WHERE` 條件式）。理由：本專案曾因版號避讓而改 changeset id，Liquibase 會把改名後的 changeset 視為新 migration 重跑，非冪等語句會失敗並造成服務 crash loop。

  ```sql
  --liquibase formatted sql

  --changeset steven:v1.69.0-stock-underlying-currency
  --comment Requirement 47（Task 227）：<寫明設計決策與理由，比照既有 changeset 的詳盡 comment 慣例>

  ALTER TABLE stock ADD COLUMN IF NOT EXISTS underlying_currency VARCHAR(10);

  COMMENT ON COLUMN stock.underlying_currency IS
    '底層資產幣別（TWD/USD/GBP…）。null 時依 market 推斷：美股→USD、英股→GBP、台股→TWD。'
    '台幣計價但持有外幣資產的 ETF（如 00679B/00697B/00719B）必須顯式標為 USD，'
    '否則匯率環境組會誤判為無曝險。誤判修正路徑為 DBA 手動 UPDATE。';

  -- 顯式標記已知的台幣計價美元資產 ETF（冪等）
  UPDATE stock SET underlying_currency = 'USD'
   WHERE market = '台股' AND code IN ('00679B','00697B','00719B')
     AND (underlying_currency IS NULL OR underlying_currency <> 'USD');
  ```

  **同時須在 JPA entity 補上對應欄位**——`backend/src/main/java/com/steven/assets/model/Stock.java`。該類別是 Lombok `@Data` entity（**不是 record**，entity 用 `@Data` 是本專案既有慣例），使用 `@IdClass(StockId.class)` 複合主鍵，既有三個 override 欄位都帶固定格式 Javadoc，新欄位比照：

  ```java
  /**
   * 底層資產幣別（Requirement 47）：TWD / USD / GBP…
   * 非空時決定匯率曝險；null 時依 market 推斷（美股→USD、英股→GBP、台股→TWD）。
   * 台幣計價但持有外幣資產的 ETF 必須顯式標記，不得靠名稱字串比對判斷。
   */
  @Column(name = "underlying_currency", length = 10)
  private String underlyingCurrency;
  ```

- [x] **227.2 幣別判定：顯式欄位優先，不得用名稱比對**

  判定順序：(1) `stock.underlying_currency` 非空時直接採用；(2) null 時依 `market` 推斷——`美股` → `USD`、`英股` → `GBP`、`台股` → `TWD`。

  **嚴禁以名稱字串判斷**（如「名稱含『美債』二字」）。該做法在標的更名、新增或名稱格式變動時會**靜默失效**——匯率組會突然變成 `null`，分數跳動但不報任何錯。

  現有 `stock` 表中 `market` 的實際值域只有三種：`台股`／`美股`／`英股`（實測 `stock_price_history` 分別為 106,156／40,578／7,972 列）。

- [x] **227.3 新增「匯率環境」因子** ⚠ *實作偏差：落地為扁平因子權重 `W_FX = 0.05`，非本節原述的巢狀六組與 `0.08`（見頂部狀態橫幅與 `requirements.md` 1252 條）。以下六組表為原始計畫，保留備查。*

  作為 t223 建立的分組加權正規化的第六組。六組權重同步調整為：

  | 組 | 權重 |
  |---|---|
  | 短期動能 | `0.20` |
  | 中期趨勢 | `0.17` |
  | 長期趨勢 | `0.28` |
  | 基本面 | `0.18` |
  | 大盤環境 | `0.09` |
  | **匯率環境** | **`0.08`** |
  | 合計 | `1.00` |

  匯率環境組為單一子因子，組內權重 `1.00`：

  ```text
  組分數 = clamp(−(fxPercentile − 50) / 50, −1, +1)
  ```

  `fxPercentile` 為該標的底層幣別對台幣的中價（`(buy_rate + sell_rate) / 2`）在**過去三年**分布中的百分位。

  對照值：分位 `99` → `−0.98`（極貴）、`73` → `−0.46`（偏貴）、`50` → `0`、`10` → `+0.80`（便宜）。

  **`underlying_currency = TWD` 的標的該組回 `null`**（無匯率曝險），觸發 t223 已實作的權重重分配——**台股標的不得因匯率被加減分**。

- [x] **227.4 回看期** ⚠ *實作偏差：落地為五年（`FX_LOOKBACK_YEARS = 5`），非本節原述的三年（見頂部狀態橫幅與 `requirements.md` 1265 條）。下表為原始計畫，保留備查。*

  實測同一天（2026-07-17、USD/TWD 32.23）的百分位在不同回看期差異極大：

  | 回看期 | 百分位 | 換算組分數 |
  |---|---|---|
  | 一年 | **99.2** | `−0.98` |
  | **三年** | **73.2** | **`−0.46`** |
  | 全歷史 | 91.8 | `−0.84` |

  取**三年**。理由：一年過短，易被單一波段主導而使因子在趨勢行情中長期釘在極值（分位 99 意味著幾乎每天都給滿額扣分，失去區辨力）；全歷史涵蓋不同匯率制度時期，代表性存疑。

  回看期為**具名常數**，調整時須同步更新 Requirement 47 與本任務檔的記載。

- [x] **227.5 資料品質防護**

  `exchange_rate_history` 中存在 `buy_rate = sell_rate` 的列——那是台銀被 WAF 擋下時的 Yahoo fallback 值（實測 2026-07-18／19 兩筆皆為 `32.3650`），性質與有真實買賣價差的列不同（對照 2026-07-17 為 `31.8950`／`32.5650`）。

  要求：

  - 分位計算以**完成日**資料為準，排除或標記 fallback 列。
  - **取不到當日匯率時該子因子回 `null`，不得以最近一筆硬代**。匯率在假日不變動，硬代會使分位在連假期間失真。
  - 週末與國定假日沿用既有交易日曆判定，不得自建假日表。

- [x] **227.6 技術面仍以台幣報價計算（刻意的取捨，須記載於程式碼註解）**

  MA／KD／兩日確認等趨勢指標**維持使用台幣報價**，**不改用剝除匯率後的 implied 序列**。

  理由：使用者的實際部位與損益是台幣計價的，剝離後的訊號雖然更純粹地反映債券本身，卻不對應他真正承受的價格波動。匯率的影響改由匯率環境組與 UI 揭露處理。

  **此取捨須明文寫入 `TradingRadarService` 的相關註解**：本任務已知技術面訊號對高匯率相關標的（如 00719B，相關係數 0.97）有相當部分來自匯率，選擇以「揭露＋獨立因子」而非「改變價基」處理。日後若要改為剝離價基，須另立 Requirement 並評估對歷史分數可比性的影響。

- [~] **227.7 UI 必須揭露匯率貢獻** ⚠ *部分完成：(a) `fxPercentile` 徽章＋五年期換匯貴賤 verdict tooltip 已上線（`TradingRadarView.vue` `fxTooltip`／`fxTagType`）；**(b) 台幣／底層變動對照未實作**。*

  對有匯率曝險的標的（`underlying_currency <> TWD`），畫面須顯示：

  1. 目前 `fxPercentile` 與其回看期（例：「USD/TWD 位於三年期第 73 百分位」）。
  2. 該標的近一年台幣報價變動 vs **剝除匯率後的底層變動**。實測 00719B 應顯示「台幣 +10.34%／底層 +2.05%」這類對照。

  第 2 項是本任務對使用者最直接的價值——讓他一眼看出漲勢來自哪裡。

  前端要改的檔案：`frontend/src/views/TradingRadarView.vue`；若需新增 API 欄位對應改 `frontend/src/api/index.js`。**BFF 不需改動**——`bff/src/main/java/com/steven/assets/bff/tradingradar/TradingRadarBffRoutes.java` 是純 Spring Cloud Gateway rewrite passthrough，沒有 DTO 定義，新增 JSON 欄位會自動穿透。

- [x] **227.8 不得儲存衍生值、不得新增抓取、零 AI API**

  `fxPercentile` 與 implied 序列一律**即時計算，不得寫回資料庫**（可從 `exchange_rate_history` 與 `stock_price_history` 算出，儲存違反本專案正規化規範）。

  不得於請求鏈觸發外部抓取（匯率由既有 `ExchangeRatePoller` 預先落地），不得引入任何 LLM。

- [x] **227.9 測試**

  測試與實作同屬本任務，不得延後。至少覆蓋：

  - `underlying_currency = TWD`（或 null 且 market=台股）時匯率組回 `null`，且權重重分配後六組實際生效權重總和為 `1`。
  - `USD` 標的：分位 `99` → 約 `−0.98`、`50` → `0`、`10` → `+0.80`（三個 case）。
  - 顯式欄位優先於 market 推斷（`market=台股` 但 `underlying_currency=USD` 時匯率組生效）。
  - 回看期不足三年時的行為。
  - 當日無匯率資料時回 `null` 而非沿用前值。
  - `buy_rate = sell_rate` 的 fallback 列被正確排除或標記。
  - **迴歸**：0050 等純台股標的的分數在本任務前後**完全不變**。

  本專案在 Java 25 下跑 Mockito 需要 `-DargLine="-Dnet.bytebuddy.experimental=true"`（直接傳 `-D` 無效，surefire 會 fork 新 JVM）。

## 驗證

> **環境事實（已實測，照抄即可執行）**：容器名為 `asset-postgres`／`asset-bff`／`asset-business-services`／`asset-external-materials-service`（**不是** `asset-management-*-1`）。DB 的 user 與 database 皆為 `assets`。**只有 `asset-bff` 對 host 發佈 8080**；business-services 只在容器網路內聽 8080，沒有 host port。BFF 的 `/api/bff/**` 需登入（回 401），免 OAuth 的做法是進 business 容器帶 `X-User-Id`／`X-User-Role`／`X-User-Status` header。**本專案沒有 root pom**，測試要用 `-f <module>/pom.xml`。business 與 external **未啟用 actuator**，只有 bff 有。

```bash
# 1. 單元測試
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test \
  -DargLine="-Dnet.bytebuddy.experimental=true"

# 2. 前端建置
cd frontend && /Users/steven/.nvm/versions/node/v22.21.0/bin/node \
  /Users/steven/.nvm/versions/node/v22.21.0/bin/npm run build && cd ..

# 3. 確認匯率資料齊備（USD 應有十年、約 2488 列）
docker exec -i asset-postgres psql -U assets -d assets -c \
  "SELECT currency, COUNT(*), MIN(rate_date), MAX(rate_date)
   FROM exchange_rate_history GROUP BY currency ORDER BY 2 DESC;"

# 4. 確認目前的 USD/TWD 三年分位（實作前後應一致）
docker exec -i asset-postgres psql -U assets -d assets -c \
  "WITH fx AS (SELECT rate_date, (buy_rate+sell_rate)/2 mid FROM exchange_rate_history WHERE currency='USD'),
        cur AS (SELECT mid FROM fx WHERE rate_date='2026-07-17')
   SELECT (SELECT mid FROM cur) AS usdtwd,
     ROUND(100.0*(SELECT COUNT(*) FROM fx WHERE rate_date>='2023-07-17' AND mid<=(SELECT mid FROM cur))
           /(SELECT COUNT(*) FROM fx WHERE rate_date>='2023-07-17'),1) AS pct_3y;"
# 預期：usdtwd=32.23、pct_3y≈73.2

# 5. 重建映像並重建容器（JVM 必須 --no-cache）
docker compose -p asset-management build --no-cache business-services frontend
docker compose -p asset-management up -d --no-deps --force-recreate business-services frontend

# 6. 重建 business 會換 IP，BFF 握舊 IP 回 500 且不自癒（Docker DNS TTL 600s）
docker compose -p asset-management restart bff

# 7. 健康檢查（只有 bff 有 actuator）
curl -s http://localhost:8080/actuator/health

# 8. migration 已套用
docker exec -i asset-postgres psql -U assets -d assets -c \
  "SELECT id, dateexecuted FROM databasechangelog WHERE id LIKE 'v1.69.0%';"

# 9. 幣別標記正確落地
docker exec -i asset-postgres psql -U assets -d assets -c \
  "SELECT code, name, market, underlying_currency FROM stock
   WHERE code IN ('00679B','00697B','00719B','0050') ORDER BY code;"
# 預期：三檔美債 ETF 為 USD，0050 為 NULL（依 market 推斷為 TWD）

# 10. 匯率組確實生效，且台股標的不受影響
docker exec asset-business-services curl -s \
  -H "X-User-Id: 1" -H "X-User-Role: ADMIN" -H "X-User-Status: ACTIVE" \
  http://localhost:8080/api/trading-radar \
  | python3 -c "
import sys,json
d=json.load(sys.stdin)
for s in d.get('stocks',[]):
    if s.get('stockCode') in ('00679B','00697B','00719B','0050'):
        print(s.get('stockCode'), 'score=',s.get('score'), 'action=',s.get('action'),
              'fxPct=',s.get('fxPercentile'), 'fxScore=',s.get('fxGroupScore'))"
# 預期：三檔美債 ETF 有 fxPercentile（約 73）與負的 fxGroupScore；0050 兩者皆為 None/null

# 11. 請求鏈沒有 AI API 呼叫
docker compose -p asset-management logs --since 2m business-services \
  | grep -iE "anthropic|openai|claude" || echo "無 AI API 呼叫（預期）"
```

驗收判準：步驟 4 的三年分位為 73.2；步驟 9 三檔標為 `USD`；步驟 10 三檔有匯率分數且 0050 為 null；步驟 11 無命中。

**執行步驟 10 前請先保留一份實作前的輸出**，用於確認 0050 的分數完全沒變（227.9 的迴歸要求）。

## 完成報告

已實作並上線（隨 `TW_RULES_V7` 一併落地，commit `40f6458b`「…納入 KD 過熱與匯率曝險」）。

**實際改動的檔案：**
- `backend/src/main/resources/db/changelog/changes/v1.69.0-stock-underlying-currency.sql`（新增 `stock.underlying_currency`，並顯式標記 00679B／00697B／00719B 為 `USD`），已註冊於 `db.changelog-master.yaml`。
- `backend/.../model/Stock.java`：新增 `underlyingCurrency` 欄位。
- `backend/.../service/TradingRadarService.java`：`FX_LOOKBACK_YEARS = 5`、`fxPercentile()`（五年期分位、排除非完成日/fallback）、`underlyingCurrencyOf()`（顯式欄位優先、null 依 market 推斷）。
- `backend/.../service/TradingRadarRuleEngine.java`：`W_FX = 0.05`、`FX_EXPENSIVE_PCT = 90.0`、`fxContribution()`、買進閘門對 `fxPercentile ≥ 90` 否決。
- `backend/.../dto/TradingRadarDto.java`：新增 `fxPercentile`、`underlyingCurrency`。
- `backend/.../service/TradingRadarExportService.java`：Excel 第 25 欄輸出底層幣別。
- `frontend/src/views/TradingRadarView.vue`：`fxTagType()`／`fxTooltip()`，個股列顯示「幣別＋五年分位%」徽章與換匯貴賤 tooltip。

**與原計畫的偏差（重要，均為刻意）：**
1. **評分結構採扁平因子權重，非計畫的巢狀六組。** 落地的因子彼此獨立，扁平權重的數學結果與「組內先平均再組間加權」相同且少一層間接，故 `TradingRadarRuleEngine` 直接以具名常數扁平加權（合計 `1.00`）。完整權重表見 `requirements.md` 1252 條與 `design.md`「Requirement 47」段。
2. **匯率因子權重為 `0.05`，非計畫的 `0.08`。**
3. **回看期為五年（`FX_LOOKBACK_YEARS=5`），非計畫的三年。** 三年（2026-07-17 分位 73.2）未涵蓋台幣由強轉弱的完整週期；五年（分位 83.6、實測 1247 筆、區間 27.53–33.14）涵蓋完整週期且不極端。理由已寫入 `requirements.md` 1265 條。
4. **227.7 UI 僅部分落地：** (a) `fxPercentile` 徽章＋五年期換匯貴賤 verdict tooltip 已上線；(b)「台幣報價變動 vs 剝除匯率後底層變動」的量化對照（如 00719B `台幣 +10.34%／底層 +2.05%`）**尚未實作**。若要補齊，屬前端 + DTO 欄位變更，須另立後續任務走 SDD 循環。

**同步更新的 spec：** `requirements.md` Req 47（1252／1265／1266 條已標 `[x]` 並記載扁平權重、五年回看期、否決門檻）、`design.md`「Requirement 47」段（權重 `0.05`、五年回看期）。
