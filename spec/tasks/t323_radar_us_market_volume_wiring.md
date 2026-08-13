# [t323] 交易雷達美股大盤量能接線：`buildUsMarket()` 補上既有 IXIC 量能，停止線上與回測分岔

**對應 Requirements:** Requirement 64（交易雷達納入美股個股評分，畫面改為「台股」「美股」兩個分頁）、
Requirement 65（交易雷達 V13——證據完整度、資料時效與樣本外校準：每個 evidence component 都必須誠實
反映 applicability，缺漏不得當中性值）
**前置任務:** 無
**Liquibase changeset:** 無（純接線，不動 schema、不新增資料來源）

## 背景

### 現在的錯誤行為

交易雷達美股分頁的每一檔個股，`evidence.evidenceGroups.MARKET_LIQUIDITY` 中的
`market_volume_turnover` 恆為 `MISSING`，`missingReason` 為
「大盤量能 context 缺漏（適用指數不得視為 N/A）」。實測（2026-08-13 00:41 Asia/Taipei，business-services
容器內 `GET /api/trading-radar`，`X-User-Id: 1`）：美股 11 檔全部 MISSING、台股 21 檔為 STALE。

**這不是資料缺口，是接線缺口。** 三層事實與「美股沒有大盤量能來源」的直覺相反：

1. **資料已在庫**：`us_index_daily_history` 有 IXIC 共 2556 列、其中 2519 列有 `volume`，
   最新交易日 2026-08-11（2026 年內 `volume IS NULL` 的列數為 0）。例：
   `2026-08-11 → 7026093000`、`2026-08-10 → 7675200000`、`2026-08-07 → 8183970000`。
2. **計算已存在**：`backend/src/main/java/com/steven/assets/service/TradingRadarMarketContextService.java`
   的 `resolveUsV13(Instant, List<UsIndexDailyHistory>)` 已經過濾 `IXIC`、套 `usCompletion()` 完成日邊界，
   並以 `RATIO_LOOKBACK` 根前值算出 `volumeRatio`；公開入口
   `resolveMarketFromRows(String market, Instant, List<TwseIndexDailyHistory>, List<UsIndexDailyHistory>)`
   已有 `if ("美股".equals(market)) return resolveUsV13(...)` 分支。該分支的 production 呼叫端目前**只有 `BacktestService`**（另有單元測試直接呼叫）。
3. **線上雷達沒接上**：`backend/src/main/java/com/steven/assets/service/TradingRadarService.java`
   的 `buildUsMarket(...)` 在建 `TradingRadarDto.MarketSummary` 時，`extendedIndicators` 之後的
   8 個引數一律硬寫 `null, null, null, null, null, null, null, false`。對照
   `backend/src/main/java/com/steven/assets/dto/TradingRadarDto.java:555-562` 的欄位順序，前三個正是
   `marketVolumeRatio`／`marketTurnoverRatio`／`marketVolumeAsOfDate`。
   同檔台股端 `buildMarket(...)` 則是傳 `context.marketVolumeRatio()`／`context.marketTurnoverRatio()`／
   `context.marketAsOfDate()`。

而 `buildStock(...)` 組 evidence 時是從 `marketSummary` 反讀這三個欄位：

```java
new TradingRadarEvidenceConfidenceResolver.MarketContext(
        parseLocalDate(marketSummary.marketVolumeAsOfDate()),
        marketSummary.marketVolumeRatio(), marketSummary.marketTurnoverRatio(),
        "MARKET_CONTEXT",
        true);
```

因此美股列的 `contextValueAvailable`（`TradingRadarEvidenceConfidenceResolver` 中
`finite(volumeRatio) || finite(turnoverRatio)`）恆為 false，`market_volume_turnover` 落到 `MISSING`。

### 正確行為

美股列的 `MarketSummary` 必須帶入既有 IXIC 量能，使 `market_volume_turnover` 依實際資料時效落在
`AVAILABLE`／`STALE`，而不是恆 `MISSING`。

### 為什麼不能改成「再落地一份美股大盤量能時間序列」

那會做出第三條管線：`BacktestService` 走 `resolveMarketFromRows("美股", …)`、線上雷達走新管線，
同一語意的值在兩處各自演化——正是本專案「同義欄位、同一 business service API」要防的事。
`TradingRadarMarketContextService` 第 250-255 行的既有 javadoc 也已明訂
「台股與美股不可共用同一個 marketAsOf／量能來源：美股只使用 IXIC 的完成日與成交量，台股則使用 TAIEX；
不存在可用來源時回傳 `MarketContext#EMPTY` 欄位，而不是把另一市場的數字代入」。

## 要做什麼

- [ ] **323.1 `buildUsMarket()` 改用既有 context 解析，不得自行重算。** 在 `TradingRadarService.buildUsMarket(...)`
      內，以該方法**已經讀進來的** `List<UsIndexDailyHistory> rows`（同方法內既有變數，來自
      `usIndexDailyHistoryRepo`）呼叫既有的
      `marketContextService.resolveMarketFromRows(US_MARKET, decisionInstant, List.of(), rows)`
      （`US_MARKET` 為 `TradingRadarService` 既有常數，不要寫字面值），取得 context 後填入 `MarketSummary`：
  - `marketVolumeRatio` ← `context == null ? null : context.marketVolumeRatio()`
  - `marketVolumeAsOfDate` ← `context == null || context.marketAsOfDate() == null ? null : context.marketAsOfDate().toString()`
  - `marketTurnoverRatio` ← **維持 `null`**：`us_index_daily_history` 沒有成交值／週轉率欄位，
    `resolveUsV13` 也明確傳 `null`。**不得以成交量除以任何數字偽造週轉率。**
    `TradingRadarEvidenceConfidenceResolver` 的 `contextValueAvailable` 是
    `finite(volumeRatio) || finite(turnoverRatio)` 的 OR，只有量能比即可成立。
  - ⚠ **系統裡有兩個同名不同型的 `MarketContext`，不得混用**：
    `resolveMarketFromRows` 回傳的是 **`TradingRadarMarketContextService.MarketContext`**，
    其 accessor 為 `marketAsOfDate()`／`completedMarketChangePercent()`／`marketVolumeRatio()`／
    `marketTurnoverRatio()`；而 `buildStock` 內組 evidence 用的是
    **`TradingRadarEvidenceConfidenceResolver.MarketContext`**，accessor 為
    `asOfDate()`／`volumeRatio()`／`turnoverRatio()`／`provider()`／`liquidityApplicable()`。
    本任務只碰前者。**不得為了迎合任何文件而改 import 成另一支型別。**
  - **null 防護是硬性要求**：既有單元測試把 `TradingRadarMarketContextService` 宣告為 `@Mock`，
    未 stub 的方法回 `null`；若直接寫 `context.marketVolumeRatio()` 會 NPE，
    而該 NPE 會被 `buildUsMarket` 既有的 `catch (Exception e)` 吞成 `incompleteMarket(...)`
    ——**不報錯、只讓美股整組變 `DATA_INCOMPLETE`**，是最難察覺的失敗模式。
  - **不得**在 `TradingRadarService` 內另寫一份 ratio 計算；**不得**改動 `resolveUsV13` 既有邏輯與
    `usCompletion()` 完成日邊界（那是 V13 的前視偏誤防線）。
- [ ] **323.2 其餘五個引數本次不動，且必須在程式碼註解寫明理由。** `nasdaqChangePercent`／
      `soxChangePercent`／`usTechCompositePercent`／`usTechAsOfDate`／`usTechAvailable` 五欄維持
      現行的 `null…false`。理由：「美股科技共同完成日」因子的設計語意是**台股列的領先訊號**
      （台股 14:00 決策時看前一個已完成的美股 session），把它填進**美股列自己使用的 `MarketSummary`**
      是自我指涉，屬於另一個需要獨立驗收的決定，不在本任務射程。**不得順手一起填。**
      **本任務不改變任何前端顯示欄位**：`TradingRadarDto.Response` 只有一個 `market` 欄位、
      裝的是台股 summary，美股的 `MarketSummary` 從未序列化給前端（前端只有一張大盤卡、
      美股分頁僅有一則說明用 `el-alert`）。唯一可見變化在 evidence 與信心度。
  - ⚠ **`MarketInput` 的量能兩欄也必須維持 `null`，只改 `MarketSummary`。**
    `buildUsMarket` 內 `evaluateMarket(new MarketInput(...))` 目前傳 `marketVolumeRatio=null`、
    `marketTurnoverRatio=null`、`completedMarketChangePercent=null`；台股端則是把同一份 context
    灌進去，**實作者鏡像照做是最自然的錯誤**。這兩欄在 `TradingRadarRuleEngine` 內是**進分數的**
    （`averageAvailable(marketVolumeRatio, marketTurnoverRatio)` → `score += 8`／`-= 10`／`-= 3`／`+= 3`），
    填了會直接翻動 11 檔美股的 regime 分數與買進閘門，也會讓 323.5「只變信心度」的前提失效。
    既有測試只斷言 `nasdaqChangePercent`／`soxChangePercent`／`usTechCompositePercent`／`usTechAvailable`
    四欄，**攔不到這個誤填**，故 323.3 必須補一條斷言。
- [ ] **323.3 單元測試（backend）＋ 同步既有測試的 stub。**
      觀測入口用 public 的 `buildMarketSnapshot(US_MARKET).summary()`（`buildUsMarket` 是 private；
      `assembleAt(Instant)` 雖可指定 instant，但其 `Response.market` 只帶台股 summary）。
      ⚠ **`buildMarketSnapshot(String)` 第一行即 `Instant now = Instant.now();`，沒有吃 instant 的 overload**，
      故測試不能「指定 decisionInstant」，只能用**相對於現在**的 fixture 日期表達時窗
      （過去日期＝已完成、未來日期＝晚於完成邊界）。
      **既有測試的 stub 要補，但不得放進共用 `stubBaseline()`**——
      `TradingRadarUsStockEngineTest` 有一條既有迴歸
      `美股完成收盤與marketSummary同日但量能context缺值時PRICE_MARKET仍開閘`，其前提就是「量能 context 缺值」；
      若在共用 stub 統一回一個有值的 context，該迴歸會名存實亡且無人察覺。
      該案例必須維持 `MarketContext.EMPTY`（或以 per-test stub 覆寫），補完後重讀案例名稱確認語意仍成立。
      四支既有測試皆把 `TradingRadarMarketContextService` 宣告為 `@Mock`：
      `TradingRadarUsStockEngineTest`（另已 stub `resolveMarket`／`resolveFx`）、
      `TradingRadarServiceOwnerScopeTest`、`TradingRadarIndicatorSeriesProvenanceTest`、
      `TradingRadarMarketFreshnessTest`（後三支目前只 stub 了 `resolve(...)`）。
      新增案例至少五個，皆針對美股大盤組裝：
  - (a) **線上與回測不分岔的守門斷言（不可省略）**：必須用**真實**的
        `TradingRadarMarketContextService`（建構時注入 mock repository），**不得**用 `@Mock` 的 context service
        ——拿 stub 跟自己比會恆真、證明不了任何事（既有 `TradingRadarNotificationMarketBatchTest`
        即採「真實 service ＋ mock repo」的作法）。斷言 `MarketSummary.marketVolumeRatio()` 非 null，
        且與直接呼叫 `resolveMarketFromRows(US_MARKET, 同一 instant, List.of(), 同一 rows)`
        的 `marketVolumeRatio()` **完全相等**。
        ⚠ **fixture 必須有足夠樣本**：`ratio()` 要求 `prior.size() >= RATIO_MIN_SAMPLES`（＝10），
        取前 20 個**正 volume 日**的中位數為分母，故至少要 1 筆最新 ＋ 10 筆正 volume 前值。
        現成的 `TradingRadarUsStockEngineTest.usUpRowsAt(...)` 建了 241 列但**從未呼叫 `setVolume`**，
        直接沿用會得到 `volumeRatio == null`；可比照
        `TradingRadarMarketContextServiceTest` 既有案例（20 列 volume=100 ＋ 1 列 400 → ratio 4.0000）自建 fixture。
  - (b) `marketVolumeAsOfDate` 等於該 context 的 `marketAsOfDate()` 字串。
  - (c) IXIC 列為空、或全部晚於完成邊界（fixture 用未來日期）時，三欄皆為 null，且**不得拋例外**
        （`buildUsMarket` 既有 try/catch 的隔離語意不變：美股組裝失敗不得拖垮台股組）。
  - (d) `marketTurnoverRatio` 恆為 null。
  - (e) **`MarketInput` 守門**：捕捉美股那組 `MarketInput`（`ArgumentCaptor`），斷言
        `marketVolumeRatio`／`marketTurnoverRatio`／`completedMarketChangePercent` **仍為 null**。
- [ ] **323.4 預期結果分兩個時窗，完成報告必須記錄實際落在哪一個。**
      `TradingRadarEvidenceConfidenceResolver` 的三條出口是：

      ```java
      Applicability contextStatus = !contextApplicable ? Applicability.NOT_APPLICABLE
              : contextExactSession
              && !in.marketStale() && contextValueAvailable ? Applicability.AVAILABLE
              : (in.marketStale() ? Applicability.STALE : Applicability.MISSING);
      ```

      即「有值但非要求的完成 session」在 `marketStale=false` 時映到 **MISSING**（不是 STALE）。故：
  - **(a) `expectedMarketCompletedSession` 與 context `marketAsOfDate()` 相同且 `marketStale=false`
    ⇒ `AVAILABLE`。這涵蓋兩種情形：美股 ET 收盤（16:00）前決策，以及已收盤且當日 IXIC 已入庫。
    以收盤前為例**：`expectedMarketCompletedSession` 為前一個完成 session
    （量測時點 2026-08-13 00:41 Asia/Taipei ＝ ET 12:41，對應完成 session 2026-08-11），
    與 `resolveUsV13` 產出的 `asOfDate`（IXIC 最新 2026-08-11）相同，且此時 `marketStale=false`
    （實測 11 檔 `regime` 皆 AVAILABLE，而 `regimeAvailable` 的必要條件含 `!marketStale`）
    ⇒ 接線後應為 **AVAILABLE**。
  - **(b) 美股已收盤但當日 IXIC 尚未入庫**：`marketStale=true` ⇒ 為 **STALE**。
  - 完成報告須記錄四項：量測時點的 ET local time、`expectedMarketCompletedSession`、
    context `asOfDate`、`marketStale`。**不得只寫「已修好」而不附這四項。**

- [ ] **323.5 必須揭露信心度變動，並確認動作是否被翻動。** `market_volume_turnover` 權重 `.30`，
      `weightedCoverage` 只把 `AVAILABLE` 計入分子；`MARKET_LIQUIDITY` 在信心度中短期權重 `.30`、
      中期 `.20`。接線後美股 11 檔的 MARKET coverage 由 0.70 升至 1.00，預估
      `shortEvidenceConfidence` 由 89→100（多檔）、`mediumEvidenceConfidence` 由 71→78、
      60→67、49→56（依檔而異）。`TradingRadarEvidenceGate` 的預設 confidence 門檻為 `.70`，
      故落在 60–69 的中期候選在其他日的資料狀態下**可能因此跨過門檻而改變 `action`／`shortAction`**。
      完成報告必須列出 11 檔美股接線前後的 `shortEvidenceConfidence`／`mediumEvidenceConfidence`，
      以及 `action`／`shortAction` 是否變動；有變動須逐檔說明並確認符合預期，**不得默默通過**。

## 驗證

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true test

# 本專案沒有 dev server：改好＝image rebuild + container recreate。
# JVM 服務一律 --no-cache，避免 layer cache 留下不含本次變更的 jar。
docker compose -p asset-management build --no-cache business-services
docker compose -p asset-management up -d --no-deps --force-recreate business-services
docker compose -p asset-management restart bff   # business 換 IP 後 BFF 會握舊 IP 回 500
docker compose -p asset-management ps

# 實機驗證：美股列的量能欄與 evidence 狀態（OWNER_ID 用本機既有使用者，本次量測為 1）
docker compose -p asset-management exec -T business-services curl -fsS \
  -H 'X-User-Id: 1' -H 'X-User-Role: ADMIN' -H 'X-User-Status: ACTIVE' \
  http://localhost:8080/api/trading-radar > /tmp/radar.json
# 期望：美股列的 market_volume_turnover 不再是 MISSING
#（美股 ET 收盤前為 AVAILABLE；已收盤但當日 IXIC 未入庫時為 STALE），
# 且 missingReason 不再是「大盤量能 context 缺漏（適用指數不得視為 N/A）」。

# 323.5 的前後比對：rebuild 前先存一份 /tmp/radar-before.json，rebuild 後存 /tmp/radar-after.json，
# 逐檔列出美股的 shortEvidenceConfidence／mediumEvidenceConfidence／action／shortAction 差異。
python3 - /tmp/radar-before.json /tmp/radar-after.json <<'PY'
import json,sys
a={s['stockCode']:s for s in json.load(open(sys.argv[1]))['stocks'] if s['market']=='美股'}
b={s['stockCode']:s for s in json.load(open(sys.argv[2]))['stocks'] if s['market']=='美股'}
for c in sorted(a):
    x,y=a[c],b.get(c,{})
    print(c, x.get('shortEvidenceConfidence'),'->',y.get('shortEvidenceConfidence'),
          '|', x.get('mediumEvidenceConfidence'),'->',y.get('mediumEvidenceConfidence'),
          '|', x.get('shortAction'),'->',y.get('shortAction'),
          '|', x.get('action'),'->',y.get('action'))
PY

# 對照資料面（唯讀）
docker compose -p asset-management exec -T postgres sh -c \
  "psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -At -c \"SELECT index_code, count(*), count(volume), max(trading_date) FROM us_index_daily_history GROUP BY 1;\""
```

## 完成報告

**完成日期：** 2026-08-13

### 實際改動

| 檔案 | 改動 |
|---|---|
| `backend/.../service/TradingRadarService.java` | `buildUsMarket()` 以既有 `rows` 呼叫 `marketContextService.resolveMarketFromRows(US_MARKET, decisionInstant, List.of(), rows)`，填入 `MarketSummary` 的 `marketVolumeRatio`／`marketVolumeAsOfDate`（皆走 `context == null ? null : …`）；`marketTurnoverRatio` 與其餘五欄維持 null；`MarketInput` 三欄亦維持 null，並於引數處加註理由 |
| `backend/.../service/TradingRadarUsMarketVolumeWiringTest.java` | **新增**，6 案例（323.3 的 a–e，含 `MarketInput` 守門） |
| `TradingRadarUsStockEngineTest`／`TradingRadarServiceOwnerScopeTest`／`TradingRadarIndicatorSeriesProvenanceTest`／`TradingRadarMarketFreshnessTest` | 各補一條 `lenient()` stub，**顯式回 `MarketContext.EMPTY`**（不放進共用 `stubBaseline()`，以保住既有「量能 context 缺值仍開閘」迴歸的前提） |

`mvn -f backend/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true test` → **Tests run: 878, Failures: 0, Errors: 0, Skipped: 0**。
實作者另做兩次 mutation 驗證守門有效（把 `MarketInput` 三欄灌值 → (e) 紅；把 `marketVolumeRatio` 改回 null → (a)(b)(d) 紅），皆已還原。
`arch-auditor` diff-scoped 查證：critical 0／major 0／minor 0。

### 323.4 實機結果：落在時窗 (b)，為 `STALE`

| 項目 | 值 |
|---|---|
| 量測時點 | 2026-08-13 21:57 Asia/Taipei ＝ **ET 09:57**（美股 8/13 盤前，8/12 那盤已收） |
| `expectedMarketCompletedSession` | 2026-08-12 |
| context `marketAsOfDate` | **2026-08-11**（`us_index_daily_history` 的 IXIC 最新交易日） |
| `marketStale` | **true**（`latestEodDate 2026-08-11 < mostRecentCompletedUsTradingDay 2026-08-12`） |
| `market_volume_turnover` | 部署前 `MISSING` ×11 → 部署後 **`STALE` ×11** |

部署後該 component 實際內容：`applicability=STALE`、`asOfDate=2026-08-11`、`provider=MARKET_CONTEXT`、
`missingReason=「大盤量能 context 非要求 completed session」`——即**有值、有來源、有日期、有理由**，
取代原本的「缺漏（適用指數不得視為 N/A）」。符合任務檔時窗 (b) 的預期。

### 323.5 信心度前後比對（**下降不是本變更造成的**）

| 代號 | short | medium | shortAction | action |
|---|---|---|---|---|
| AMZN／MSFT／NVDA／COIN／GOOGL | 89→63 | 71→56 | 不變 | 不變 |
| AVGO | 89→63 | 60→44 | 不變 | 不變 |
| QQQ／VOO／VT | 89→63 | 88→60 | 不變 | 不變 |
| SGOV | 91→70 | 90→67 | 不變 | 不變 |
| TSM | 89→63 | 49→33 | `WATCH`→`WAIT` | 不變 |

**歸因（已查證，非本變更）**：兩次快照之間跨越了美股 8/12 那盤的收盤——
部署前快照 `generatedAt=2026-08-13T02:35`（＝ET 8/12 14:35，**盤中**）、部署後為 `21:57`（＝ET 8/13 09:57，**已收盤**）。
`regime` component 因此由 `AVAILABLE`×11 翻為 `STALE`×11（權重 `.70`），
短期信心度 `(.50×1.0 + .30×0)/.80 = 62.5 → 63`，與實測完全吻合。
**同一時點若跑舊程式碼，數字會一模一樣**（舊碼下 volume 是 `MISSING`、同樣 0 分子）。
本次接線的效益要在 IXIC 當日資料入庫後（時窗 (a)）才會顯現為 `AVAILABLE`。
`TSM` 的 `shortAction` 由 `WATCH` 轉 `WAIT` 同屬 `regime` 轉 stale 的連帶效果，非量能接線所致。

### 部署證據

- 從 **main 的 worktree** `/Users/steven/Project/asset-management-main`（HEAD `481fe32a`）以
  `-p asset-management` ＋ `--no-cache` 重建 business-services；運行中 image
  `sha256:c7bf7bbb…`（build 完成後 1 分鐘內啟動），Compose project `asset-management`。
- stack 全部 healthy：`http://localhost/` → `HTTP/1.1 200 OK`、`http://localhost:8080/actuator/health` → `{"status":"UP"}`。
- 過程異常（與本變更無關）：Docker daemon 連續兩次在拉 base image 時崩潰（`rpc error: EOF`），
  engine 對所有 API 回 500；完整重啟 Docker Desktop ＋ 預先 `docker pull` 兩個 base image 後第三次建置成功。
  期間非 business 的容器一度整組消失（volume `asset-postgres-data`／`asset-redis-data` 完好），
  以 `docker compose -p asset-management up -d` 重建，Liquibase 顯示 129 個 changeset 皆為 previously run、資料完整。

### 附帶發現（不在本任務範圍，已登記於 t324）

`us_index_daily_history` 的 IXIC 停在 2026-08-11，缺 8/12 那盤；而 `IndexDailyRefreshScheduler`
的 startup self-heal 在 2026-08-13 21:58 明確輸出「self-heal：海外指數日線皆為最新，略過」——
**self-heal 的新鮮度判準與交易雷達的 `mostRecentCompletedUsTradingDay` 不一致**。
這會讓美股列每天有一段時間處於 stale。詳見 t324 的 324.4。
