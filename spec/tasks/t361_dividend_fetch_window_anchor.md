# [t361] FinMind 配息抓取視窗停止以 `date` 欄代理除權息日——修正「最近約 6 天內除權息事件靜默漏抓」

**對應 Requirements:** Requirement 97（FinMind 配息抓取請求不得再送 `end_date`；區間上界一律改由 client 端以事件自身的除息／除權日過濾，消除隨每日滑動的約 6 天漏抓盲區）
**前置任務:** t357（配息四個日期各自獨立落地與顯示，已完成並 merge 進 main，commit `b0d038b9`）
**Liquibase changeset:** 無（不改資料庫 schema；本任務只改抓取視窗與既有資料回補）

> **編號說明**：Requirement 96／Task 360 已由另一支 worktree（`trading-radar-logic-check`，檔名 `t360_radar_j_polarity_fix.md`）佔用，依專案慣例讓號給已建檔者，本任務編為 361／Requirement 97。

---

## 背景

### 現在的錯誤行為

`stock_dividend_history` 中 `2885`（元大金）2026 年的純配股事件（`stock_dividend = 0.400000`）仍帶著 t357 修正前的錯誤欄位值：

```
year | cash_dividend | stock_dividend | ex_dividend_date | ex_rights_date
2026 |      0.000000 |       2.626900 |                  | 2026-08-18
2026 |      1.800000 |       0.000000 | 2026-07-21       |
2026 |      0.000000 |       0.400000 | 2026-08-18       |          ← 錯：這是除權日，卻放在除息日欄
```

t357 的歷史回補跑遍全部納管標的、零失敗，卻獨獨沒修到這一列。原因要分兩段講，兩段都必須成立才會有這個結果（運行中 DB 的 `source` 欄實測）：

```
 id  | cash | stock | ex_dividend_date | ex_rights_date | source
 977 |  1.8 |  0.00 | 2026-07-21       |                | FinMind[TaiwanStockDividend+TaiwanStockDividendResult]
1004 |  0.0 |  0.40 | 2026-08-18       |                | TWSE_TWT48U_ALL+TPEX_EXRIGHT_PREPOST
1212 |  0.0 |  2.63 |                  | 2026-08-18     | FinMind[TaiwanStockDividend+TaiwanStockDividendResult]
```

1. **錯誤那列（id=1004）不是「沒落地」，而是由 upcoming 官方行事曆路徑落地的**——`source` 為 `TaiwanOfficialDividendCalendarClient` 的 provider 字串，帶著該 client 修正前「不論事件型別一律塞 `exDividendDate`」的映射（該映射已於 t357 修好）。
2. **FinMind 盲區讓 t357 的回補產不出對照列**：`stock_dividend_history` 的兩個 ex 日期欄是 t357 定義的 INSERT-only identity 欄，要修掉 id=1004 只能靠「抓到同一事件的正確版本、產生新列、再由收斂機制擇一保留」。但盲區讓 FinMind 主表那筆（配股 0.4、除權 2026-08-18）從未進入落地路徑，回補因而沒有對照對象——id=1004 就這樣原封不動留到今天。

所以本任務要修的是第 2 段（讓正確版本抓得到），並確保收斂機制會挑對列（見 361.2e）。

### 根因：FinMind 的 `date` 欄不是除權息日，而抓取請求的上界建在它上面

兩條路徑對**同一個 FinMind dataset** 拿到不同的列：

| 路徑 | 請求參數 | `2885` 2026 配股 0.4 那列 |
|---|---|---|
| `MarketDataFetchService.getTwDividendHistory`（唯讀顯示） | 只帶 `start_date` | **有** |
| `DividendFetchClient.finmindData`（歷史落地主路徑） | `start_date` **＋ `end_date=today`** | **無** |

`TaiwanStockDividend` 的 `date` 欄**不是除權息日**，而是晚於它的基準／發放相關日期；FinMind 的區間過濾是**在 `date` 欄上做 server-side 過濾**。實測（2026-08-23，`data_id=2885`）落後固定為 6 天：

| 事件 | `date` | `CashExDividendTradingDate` | `StockExDividendTradingDate` |
|---|---|---|---|
| 2026 配股 0.4 | **2026-08-24** | （空） | 2026-08-18 |
| 2026 現金 1.8 | 2026-07-27 | 2026-07-21 | （空） |
| 2025 配股 0.3 | 2025-08-18 | （空） | 2025-08-12 |
| 2025 現金 1.55 | 2025-07-07 | 2025-07-01 | （空） |

因此 `end_date=today` 形成一個**約 6 天寬、隨每日滑動的盲區**：除權息日落在「今天往前推約 6 天」之內的事件，其 `date` 仍在未來，整列在 FinMind 伺服器端就被剔除。`2026-08-18` 距查詢當日 5 天，正好落在盲區裡；同一檔其他年度的同型事件早已離開盲區，所以全部正常。

**這是既有的抓取視窗缺陷，不是 t357 拆欄邏輯造成的**——t357 的回補只是讓它現形。

可直接重現（唯讀，不需改任何程式）：

```bash
docker exec asset-external-materials-service sh -lc 'for E in "" "&end_date=2026-08-23"; do echo "=== end_date:[$E] ==="; curl -fsS "https://api.finmindtrade.com/api/v4/data?dataset=TaiwanStockDividend&data_id=2885&start_date=2025-01-01$E" | tr "," "\n" | grep -c "\"date\""; done'
```

不帶 `end_date` 回 4 列（含 `date=2026-08-24` 那列），帶 `end_date=2026-08-23` 只回 3 列。

`TaiwanStockDividendResult`（fallback 表）**不受影響**，因為該表的 `date` 就是除權息日（`2026-08-18`）。這解釋了為什麼 snapshot 裡只剩 fallback 表那一筆而主表那筆消失。

### 同型缺陷也在 upcoming 45 日 scope

`DividendFetchClient.fetchFinMindBounded` 對 `TaiwanStockDividend` 送 `end_date=to`，`to` 為 `decisionDate+45d`。除權息日落在窗尾約 6 天內的事件同樣會因 `date > to` 被伺服器端剔除。該路徑的 `parseFinMindDividendRows` **已在 client 端以 `anchorDate` 過濾 `[from, to]`**，代表正確的過濾欄位一直都在 client 端，server-side 的 `date` 上界是多餘且有害的一層。此路徑的產出用於判定 upcoming scope 是否 COMPLETE，漏抓會被誤讀為「該區間無事件」。

### 正確行為

FinMind 請求不再送 `end_date`；區間上界一律在 client 端以事件自身的除息／除權日過濾。下界 `start_date` 維持不變——`date` 恆**晚於**除權息日，所以下界只會多收一列（由 client 端過濾剔除），不會造成漏抓。

### 為什麼不用「把 `end_date` 往後加緩衝」

實測落後固定為 6 天（除權息基準日＝除權息日＋5 個營業日的慣例），看似加個 30 或 90 天的緩衝即可。**不採用**：任何有限緩衝都只是對「`date` 與除權息日的最大落差」的猜測，無法證明上界，而 client 端本來就握有真正該過濾的欄位。緩衝會把一個可證明正確的過濾換成一個猜測，且失效時症狀同樣是靜默漏抓。

---

## 要做什麼

改動涵蓋三處：

1. `external-materials-service/.../client/DividendFetchClient.java`（＋其測試）——抓取視窗本身（361.1、361.2）。
2. `backend/.../service/DividendCurrentStateProjectionService.java`（＋其測試）——current-state 收斂偏好（361.2e）。**只修抓取不會修好既有的錯誤列**，理由見 361.2e。
3. `docker-compose.yml`——補一行環境變數 passthrough（361.4a-0），否則回補會靜默 no-op。

**不改 DB schema、不新增 Liquibase changeset、不新增排程、不新增對外端點、不新增 9090 路由、不新增第二條抓取路徑、不新增第二套回補機制。**

### 361.1 移除 FinMind 請求的 `end_date`

- [ ] **361.1a** `finmindData(String dataset, String stockCode, int years, LocalDate today)`（歷史落地共用呼叫）組出的 URL **移除 `&end_date=` 參數**，只保留 `dataset`／`data_id`／`start_date`。
      **同時把 `start_date` 的算法由 `today.minusYears(years)` 改為 `today.minusYears(Math.max(1, years))`**：`finmindEndpoint(...)` 產生的稽核揭露字串由呼叫端傳入的是 `today.minusYears(Math.max(1, years))`（見 `fetchTw` 內兩處 `finmindEndpoint(...)` 呼叫），因此 `years <= 0` 時**現況的揭露字串與實際送出的 `start_date` 就已經不一致**。本任務既然要求兩者逐字一致（361.1c），這一半不能漏。改後 `fetchTw`／`fetchTwDividendResult` 內用來過濾的 `from` 與實際 `start_date` 也才是同一個值。
- [ ] **361.1b** `fetchFinMindBounded(String dataset, String stockCode, LocalDate from, LocalDate to)`（upcoming scope 共用呼叫）組出的 URL 同樣**移除 `&end_date=`**，只保留 `start_date=from`。移除後 `to` 在該方法內將**完全不再被使用**（`to` 目前只出現在 URL 字串；回傳的 `BoundedFinMindDataset` 不含任何 endpoint 揭露，揭露字串是呼叫端 `fetchFinMindUpcomingScope` 另外用 `finmindEndpoint(...)` 組的）。**保留簽章不動**，並在 javadoc 註明 `to` 為預留參數、僅維持呼叫端與既有測試現狀；不要為此連動修改呼叫端。
- [ ] **361.1c** `finmindEndpoint(...)` 產生的稽核揭露字串，其 **`start_date` 與 `end_date` 兩者都必須與實際送出的 URL 逐字一致**。移除 `end_date` 後若揭露字串仍宣稱送了 `end_date`，就是稽核揭露造假。`start_date` 的一致性見 361.1a。此一致性須有測試（斷言方式：由同一組參數分別取得實際 URL 與揭露字串，比對 query 參數集合相同）。

### 361.2 補上 client 端的區間上界（移除 server-side 上界後的必要配套）

> **這一段不能省。** 移除 `end_date` 之後，FinMind 會回傳「已公告但尚未除權息」的未來事件。歷史 snapshot 的語意是**已發生事件**，未來事件由既有 upcoming scope 機制負責；不補 client 端上界就會把未來事件混進歷史落地路徑。

- [ ] **361.2a** `fetchTw(String stockCode, int years, LocalDate today)`（主表）在組出 `DividendEvent` 前，以
      `anchor = anchorDate(parseDateIso(cashExDate), parseDateIso(stockExDate))`（即除息日與除權日中**較早且非 null** 者，此 helper 已存在於本檔）
      過濾：只有 `anchor != null && !anchor.isBefore(from) && !anchor.isAfter(today)` 的事件才進入結果，其中 `from = today.minusYears(Math.max(1, years))`。
      **`anchor == null` 的列一律不進入結果**（無法判定發生時點的事件不得落地為歷史）。既有那條「`anchor` 為 null 時退回 `parseYear(cashExDate/stockExDate)`、再退回 `item.date` 推導年度」的兩層 fallback，**在本任務中隨之移除**——它推導出的 `year` 已不足以構成落地資格，留著只會讓沒有可解析除權息日的列以未定位的形式進 snapshot。移除後 `year` 一律取 `anchor.getYear()`。
- [ ] **361.2b** `fetchTwDividendResult(String stockCode, int years, LocalDate today)`（fallback 表）以該表的 `date` 欄（即除權息日）過濾：只有 `from <= date <= today` 的列才進入結果，`from` 同 361.2a。日期解析用本檔既有的 `parseDateIso`，不可解析者跳過該列（維持既有 `parseYear` 回 null 即 `continue` 的寬鬆語意，**不得升級為整批拒收**——該路徑歷來就是 lenient，這一點不因本任務改變）。
      **不要照著「此表完全沒有 client 端過濾」的直覺做**：所有台股／美股歷史觀測的收尾方法 `result(...)` 已有一層 client 端上界，但它過濾的鍵是 `exDividendDate`，對 `exDividendDate == null` 的列（t357 之後純配股事件正是這種）一律直接放行。也就是現金列本來就被擋過一次，純配股列才是真正沒有上界的那一半。這層既有過濾要一併收斂，見 361.2b-2。
- [ ] **361.2b-2** 把 `result(String source, List<DividendEvent> events, FetchStatus explicit, int years, LocalDate to, String errorReason, List<String> sourceUrls)` 內既有的 `bounded` 過濾，其**鍵由 `event.exDividendDate()` 改為 `anchorDate(event)`**（本檔已有接受 `DividendEvent` 的 `anchorDate` 多載，供 `taiwanEventKey`／`eventDateForSort` 使用）。
      維持既有的放行語意不變：`event == null`、`anchorDate` 為 null、日期不可解析者一律放行，只把「有可解析日期且晚於 `to`」的列擋掉。
      **留著兩套語意不同的上界是本缺陷的復發溫床**：下一個人讀到 `result(...)` 用 `exDividendDate` 過濾，很容易照著推論而再次寫出對純配股事件無效的邊界。此改動須有測試（純配股事件、`exRightsDate` 晚於 `to` → 被擋；早於 `to` → 放行）。
      **兩層過濾刻意重複，不得為了消除重複而任擇其一移除**，兩者的 null 語意刻意相反：
      - `fetchTw`／`fetchTwDividendResult` 那層是**落地資格判準**——`anchor == null` 的列**拒收**（無法判定發生時點的事件不得落地為歷史）。
      - `result(...)` 那層是**跨路徑的最後防線**（美股 `fetchUs` 也走它，且美股事件的 `exRightsDate` 恆為 null）——`anchorDate` 為 null 一律**放行**，只擋「有可解析日期且晚於上界」的列。
      若只留 `result(...)` 那層，`anchor == null` 的列就會進歷史 snapshot，直接違反 361.2a 的立意。
- [ ] **361.2c** `parseFinMindDividendRows` 與 `parseFinMindResultRows`（upcoming scope 用）**維持不變**：兩者既有的 `[from, to]` client 端過濾在移除 server-side 上界後即為正確且完整的過濾。不得為了「配合」本次改動而調整它們的過濾條件。
- [ ] **361.2d** **明確接受並釘住 fail-closed 範圍的擴大。** `parseFinMindDividendRows`／`parseFinMindResultRows` 對 malformed 列的處理是 `return ParseEvents.invalid(...)`（整批拒收、observation 標為 `valid=false`）。移除 `end_date` 後回應會多出窗外的列，這些列**同樣參與 malformed 判定**——無法「先判斷窗內外再判 malformed」，因為 `anchorDate` 不可解析正是 malformed 的定義。此擴大是刻意接受的保守取捨（漏判 incomplete 比誤判 complete 危險），**不得為此放寬 fail-closed 語意**，並須以測試釘住：一列窗外的 malformed 資料仍會讓整批 invalid。

- [ ] **361.2e** **修正 current-state 的收斂偏好（`backend/.../service/DividendCurrentStateProjectionService.java`）——這一項不做，361.4 的回補與本任務的驗收就不會成立。**
      查證到的機制：`stock_dividend_history` 的 `ex_dividend_date`／`ex_rights_date` 是 Task 357 定義的 **identity 欄、只在 INSERT 時決定**——`JdbcDividendCurrentStateRepository` 全部四處 `UPDATE stock_dividend_history` 都不寫這兩欄，且三段 match 查詢（`findByEventKey`／`findByFullValue`／`findByDateAndAmount`）都以 `ex_dividend_date IS NOT DISTINCT FROM ? AND ex_rights_date IS NOT DISTINCT FROM ?` 比對。
      因此修好抓取之後送進來的 `(ex_dividend_date=NULL, ex_rights_date=2026-08-18)` **不會** match 到既有那列錯誤的 `(2026-08-18, NULL)`，而是新 INSERT 一列 → 同一真實事件出現兩列 ACTIVE。
      接著 `collapseDuplicateActiveEvents` 會把兩列歸進同一 `relaxedIdentity(anchorDate, cash, stock)` 群組（兩列的 anchorDate 皆為 `2026-08-18`、金額皆為 `(0, 0.4)`）並收斂成一列——但現行 `KEEPER_PREFERENCE` 為
      `有 cashPaymentDate → 有 yieldPct → id 最小`，兩列皆無發放日與 yield 時退回「取最舊 id」，**保留的正是那列錯誤的、把新的正確列 CANCELLED**。
      要做的修正：在 `KEEPER_PREFERENCE` **最前面**加一層判準——**優先保留「日期欄占位與金額拆分自洽」的列**：
      - 純配股事件（`cashDividend` 視 null 為 0 後 `== 0` 且 `stockDividend > 0`）：不可能有除息日，`ex_dividend_date` 非空即為**不自洽**。
      - 純現金事件（`stockDividend` 視 null 為 0 後 `== 0` 且 `cashDividend > 0`）：不可能有除權日，`ex_rights_date` 非空即為**不自洽**。
      - **其餘一律視為自洽**（同時配息又配股的事件本來兩個日期就都該有值；兩邊金額皆為 0 的列不在此判準範圍），由既有三層判準決定。
      此判準只由列自身推得、不需外部證據，故可安全地放在最前面。金額比對一律 **null 視 0**，與 `relaxedIdentity` 及 `uk_dividend_event` 索引的 `COALESCE(...,0)` 口徑一致。
      **收斂發生在「下一次」`projectOne`，不是同一次。** `collapseDuplicateActiveEvents` 位於 `projectOne` 方法**開頭**（第一行實質敘述），而新列是在同一方法後段的 `upsertActiveEvent`／`upsertHistoricalEvent` 才寫入；因此第一次投影結束時**必然是新舊兩列並存**，要等第二次投影才會收斂。錯誤列的 anchor（`2026-08-18`）已是過去，也不會被 `findActiveFutureEvents`（條件 `LEAST(ex_dividend_date, ex_rights_date) > decisionDate`）的 cancel pass 順手處理。**驗收與回補都必須投影兩次**，見驗證段第 4b／4c 步與 361.4c。此為既有設計（收斂是每次讀取的 self-heal），本任務不改動它。
      **不得改動 identity 欄的 INSERT-only 語意、不得新增 UPDATE ex 日期欄的路徑、不得改動 `relaxedIdentity` 的分組鍵**——那些是 Task 357／345 刻意的設計，本任務只換 keeper 的挑選順序。

### 361.3 測試（與實作同一支任務，不得延後）

測試放 `external-materials-service/src/test/java/com/steven/assets/externalmaterials/client/`，沿用該目錄既有的測試風格與 stub 手法（先讀 `DividendFetchCoverageTest.java`／`MarketDividendUpcomingScopeClientTest.java` 確認既有如何頂替 HTTP，照做，不要另起爐灶）。

> **`fetchTw`／`fetchTwDividendResult`／`fetchFinMindBounded` 都是 private**，測試不能直接呼叫。公開入口是 `fetchObservations(String stockCode, String market, int years)`（歷史路徑）與 `fetchProviderUpcomingScope(...)`（upcoming 路徑）；`DividendFetchClient` 另有 package-private 建構式收 `Clock` 與 `HttpClient`，既有測試即以 `mock(HttpClient)` ＋ `AtomicReference` 捕捉送出的 request 來斷言 URL。下列各項一律經公開入口進入、以捕捉到的 request URI 做 URL 斷言。

- [ ] **361.3a** **盲區回歸測試，必須以固定 `Clock` 與構造回應撰寫，不得依賴當日真實日期。**
      `DividendFetchClient` 已注入 `Clock`；測試以固定時鐘設 `today = 2026-08-23`，餵一份含以下列的 `TaiwanStockDividend` 回應：
      `{"date":"2026-08-24", "StockEarningsDistribution":0.4, "StockStatutorySurplus":0.0, "StockExDividendTradingDate":"2026-08-18", "CashEarningsDistribution":0.0, "CashStatutorySurplus":0.0, "CashExDividendTradingDate":"", "CashDividendPaymentDate":"", "StockDividendPaymentDate":""}`
      斷言：(1) 送出的 URL **不含** `end_date`；(2) 結果含一個 `stockDividend = 0.4000`、`exRightsDate = "2026-08-18"`、`exDividendDate = null` 的事件。
      **用真實「今天」寫的測試會在 6 天後自動變綠而失去意義**，故此項強制固定時鐘。
- [ ] **361.3b** 未來事件不得進入歷史落地：同一固定時鐘下，餵一列 `StockExDividendTradingDate = "2026-09-15"`（`today` 之後）的資料，斷言 `fetchTw` 的結果**不含**該事件。
- [ ] **361.3c** 下界維持有效：餵一列除權息日早於 `today.minusYears(years)` 的資料，斷言不進入結果。
- [ ] **361.3d** fallback 表上下界：對 `TaiwanStockDividendResult` 餵 `date` 分別在窗內、`today` 之後、`from` 之前的三列，斷言只有窗內那列進入結果。
- [ ] **361.3e** upcoming scope 不回歸：`fetchFinMindBounded` 送出的 URL 不含 `end_date`，且窗外（`date > to` 但除權息日 `<= to`）的列**會**被納入、除權息日 `> to` 的列**不會**被納入。
- [ ] **361.3f** 361.2d 的 fail-closed 釘死：窗外一列 malformed（除息日與除權日皆不可解析）仍讓 `parseFinMindDividendRows` 回 invalid。
- [ ] **361.3f-2** `anchor == null` 的列不得出現在 `fetchTw` 結果：餵一列除息日與除權日皆為空字串（或皆不可解析）的 `TaiwanStockDividend` 資料，斷言 `fetchTw` 的結果不含該事件（對應 361.2a 移除兩層 year fallback 之後的落地資格判準）。
- [ ] **361.3g** 既有測試全綠：`DividendFetchCoverageTest`、`DividendFetchResultTest`、`MarketDividendUpcomingScopeClientTest`、`DividendSnapshotAppendOnlyTest`、`DividendPersisterAppendOnlyTest`、`DividendEventKeyMigrationTest`、`DividendDatesTest` 皆不得因本次改動而紅燈。
      **已查證：目前沒有任何測試斷言「請求含 `end_date`」**（`grep -ran "end_date" external-materials-service/src/test backend/src/test` 的命中全部是 DB 欄位名 `ex_dividend_date`），不必為此另行搜尋。
- [ ] **361.3h** 361.2e 的 keeper 判準測試（放 `backend/src/test/java/com/steven/assets/service/`，沿用該目錄既有的 `DividendCurrentStateProjectionService` 測試手法）：
      (1) 同一 `relaxedIdentity` 的兩列純配股（`cash=0, stock=0.4`，anchorDate 同為 `2026-08-18`），一列 `(ex_dividend_date=2026-08-18, ex_rights_date=NULL)`、另一列 `(NULL, 2026-08-18)`，且**錯誤那列的 id 較小**（重現實際情境）→ 斷言保留的是 `(NULL, 2026-08-18)` 那列、錯誤列被 CANCELLED。
      (2) 對稱案例：純現金（`cash=1.8, stock=0`）兩列，錯誤列為 `(NULL, 2026-07-21)` → 斷言保留 `(2026-07-21, NULL)`。
      (3) 兩列皆自洽時，既有三層判準（發放日 → yieldPct → 最小 id）行為不變。
      (4) 同時配息又配股的事件（`cash>0 且 stock>0`）不受新判準影響。

### 361.4 全庫回補（AC7）

> 盲區是每日滑動的：過去每一次同步都可能漏掉當時剛除權息的事件，受影響的不只有 `2885`。

- [ ] **361.4a-0**（**前置，不做這步下一步必定 no-op**）在 `docker-compose.yml` 的 `business-services` → `environment` 補一行 `DIVIDEND_BACKFILL_STATE_DIR` passthrough，緊接既有的 `DIVIDEND_BACKFILL_ENABLED`／`_BATCH_SIZE`／`_BATCH_PAUSE_MILLIS` 三行之後。
      **理由（該處既有註解已寫明）**：`docker compose up` 只展開 compose 檔裡出現過的變數、`up` 不吃 `-e`；`_STATE_DIR` 不在 passthrough 名單裡，於 shell 設了也傳不進容器，`application.yml` 會落回預設的 t357 續跑帳。
      預設值請用 `${DIVIDEND_BACKFILL_STATE_DIR:-/home/steven/dividend-backfill-t357}`（直接寫死 t357 既有預設），**不要用空字串預設**——空字串會被 Spring 當成「明確指定為空路徑」而覆蓋掉 `application.yml` 的巢狀預設，常態啟動的 state-dir 會變成空路徑。
      **路徑刻意寫死**：程式端的預設是 `${EXPORT_OUTPUT_DIR:/home/steven}/dividend-backfill-t357`，而 compose 的 `${VAR:-default}` 無法在 default 裡再套用容器內才解析的 `EXPORT_OUTPUT_DIR`。目前 compose 的 `EXPORT_OUTPUT_DIR: /home/steven`，兩者解析結果逐字相同；**日後若改動 `EXPORT_OUTPUT_DIR`，這一行必須同步**，在該行加註解說明這件事。
- [ ] **361.4a** 以既有的 `DividendBackfillService`／`DividendBackfillStarter`／`DividendBackfillLedger`／`DividendBackfillTargetRepository`（`backend/src/main/java/com/steven/assets/service/`，Task 357 建立，`app.dividend-backfill.enabled` 預設 `false`、無排程無端點）執行全庫回補，**不得新增第二套回補機制**。
      **同目錄另有一支 `DividendHistoricalBackfillRunner`（＋`DividendHistoricalBackfillRunnerTest`）**：那是 Task 357 較早一輪留下的重疊實作，**沒有任何觸發路徑**（無 starter／排程／端點），Task 357 的完成報告已建議另案收斂。本任務**不使用它、不修改它、也不刪除它**（刪除會讓 `scripts/spec-check.sh` 因既有 spec 文字提到該測試類而 BLOCK）。
      觸發方式：以環境變數 `DIVIDEND_BACKFILL_ENABLED=true` 與 **`DIVIDEND_BACKFILL_STATE_DIR=/home/steven/dividend-backfill-t361`** recreate `business-services`；完成後拿掉旗標再 recreate 一次回到常態。
      **`STATE_DIR` 必須換成 t361 的新目錄**：`DividendBackfillService` 以 `ledger.completedKeys()` 算 pending，預設值是 t357 的續跑帳，沿用會讓整批標的都被判定「已跑過」而跳過，回補靜默變成 no-op。
- [ ] **361.4a-1** 觸發後**先證明環境變數真的生效再看結果**：
      ```bash
      docker exec asset-business-services env | grep DIVIDEND_BACKFILL
      docker logs asset-business-services 2>&1 | grep -i 'dividend.*backfill' | tail -30
      ```
      log 中實際處理的標的數不得為 0；若為 0 即代表 361.4a-0 沒生效或 state-dir 沒換到，**不得當成「本來就沒有要補的」**。
- [ ] **361.4b** 回補**前**先存一份 baseline、回補後以**逐字相同的指令**取 after 再 diff。指令見驗證段第 7 步（baseline）與第 9 步（after ＋ diff），兩處必須是同一句 SQL；**不要另外自擬一句**，欄位或 ORDER BY 只要差一點，diff 就會噴出整片假差異。
- [ ] **361.4c** **回補必須跑兩輪**（見驗證段第 7～9 步）：`projectOne` 的收斂在 upsert 之前執行，第一輪結束時新舊兩列並存，第二輪才會收斂。第二輪用不同的 state-dir（pending 會是 0，但每檔仍會先跑一次 `collapseDuplicateActiveEvents`）。
      在完成報告中列出**實際被補回或修正的列數與逐列明細**（代號／年度／金額／前後的 `ex_dividend_date`／`ex_rights_date`），**以第 7 步 baseline 與第 10 步 after 的 SQL diff 為準**，不要用 `report.tsv` 的 `corrections` 欄（其取樣時點在同一次投影之內，會低估）。
      若結果為 0 列變動亦須據實記錄。另請一併回答「為何只有 `2885` 那列有變」——可用下列唯讀 SQL 量化，實測全庫 ACTIVE 中符合「純配股卻有 `ex_dividend_date`」或「純現金卻有 `ex_rights_date`」的**恰好 1 列**（即 `2885` 的 `id=1004`）：
      ```bash
      docker exec asset-postgres sh -lc "psql -U \$POSTGRES_USER -d \$POSTGRES_DB -c \"SELECT id, stock_code, year, cash_dividend, stock_dividend, ex_dividend_date, ex_rights_date, source FROM stock_dividend_history WHERE event_status='ACTIVE' AND ((COALESCE(cash_dividend,0)=0 AND COALESCE(stock_dividend,0)>0 AND ex_dividend_date IS NOT NULL) OR (COALESCE(stock_dividend,0)=0 AND COALESCE(cash_dividend,0)>0 AND ex_rights_date IS NOT NULL));\""
      ```
      **不得以「已回補完成」帶過。**

### 361.5 spec 與文件同步

- [x] **361.5a**（規劃階段已由 spec 作者完成，實作者只需複驗內容與程式碼一致） `spec/design.md` 的「股利歷史資料來源（stock_dividend_history）」段落補上：FinMind `TaiwanStockDividend` 的 `date` 欄不是除權息日（實測落後 6 天）、FinMind 在該欄做 server-side 過濾、故本專案一律不送 `end_date`、上界改由 client 端以 `anchorDate` 過濾。連同「為何不用緩衝」的理由一起寫入——這是未來最可能被「順手加回去」的一行。
- [x] **361.5b**（規劃階段已由 spec 作者完成） 同段落記錄 Requirement 97 AC8 的既有差異：`MarketDataFetchService.getTwDividendHistory`（唯讀顯示路徑）本來就不送 `end_date`，本任務不改它；但它**也沒有 client 端上界過濾**，會顯示已公告但尚未除權息的未來事件。此差異為既有行為，**不得宣稱兩條路徑此後完全等價**。
- [x] **361.5c**（規劃階段已由 spec 作者完成） 更新 `CLAUDE.md` 的 spec 文件位置表：Requirements 計數與最新編號（由「95 個 Requirements；最新為 95」改為實際值），`spec/tasks.md` 的任務索引範圍納入 361。

---

## 驗證

```bash
# 0. 一律先進 worktree（後續所有相對路徑與 compose 的 .env 解析都以此為根）
cd /Users/steven/Project/asset-management/.claude/worktrees/great-lehmann-336be2
cp /Users/steven/Project/asset-management/.env .env
```

```bash
# 1. 單元測試（external-materials-service ＋ backend，兩個模組都改到了）
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml test \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

```bash
# 2. 重建並重啟兩支 JVM service（一律 --no-cache，見專案既有教訓：cached build 可能不含你的變更）
docker compose -p asset-management build --no-cache external-materials-service business-services
docker compose -p asset-management up -d --no-deps --force-recreate external-materials-service business-services
docker compose -p asset-management restart bff
```

> `restart bff` 不可省略：recreate 會換 container IP，BFF 握著舊 IP 會回 500 且約 3 分鐘不自癒（Docker DNS TTL 600s），症狀是 business log 乾淨、錯誤只出現在 bff log 的 Connection refused。

```bash
# 3. 確認 image 真的含本次變更（避免 stale jar）
docker exec asset-external-materials-service sh -lc 'ls -la /app/*.jar'
docker exec asset-business-services sh -lc 'ls -la /app/*.jar'
```

```bash
# 4. 驗收 AC5：重跑 2885 同步並重投影
docker exec asset-external-materials-service curl -fsS -X POST 'http://localhost:8080/internal/dividend/sync?code=2885&market=%E5%8F%B0%E8%82%A1'
```

> **第 4 步只 append `external-materials-service` 端的 snapshot evidence（`DividendPersister.syncOne` → `DividendSnapshotStore`），它完全不寫 `stock_dividend_history`。** 寫 current-state 的是 backend 的 `DividendCurrentStateProjectionService.projectOne`，必須另外觸發。**跳過下面這一步，第 5 步必然仍是舊資料，會被誤診成實作失敗。**

```bash
# 4a. 取得 admin 的 users.id（下一步要用；直接貼 <ADMIN_ID> 字面值不會報錯，
#     CurrentUserFilter 對非數字 id 是「靜默視為無身分」，會讓 4b 變成無聲的空轉）
ADMIN_ID=$(docker exec asset-postgres sh -lc "psql -U \$POSTGRES_USER -d \$POSTGRES_DB -tAc \"SELECT id FROM users WHERE email='tw.leader@gmail.com';\"" | tr -d '[:space:]')
echo "ADMIN_ID=$ADMIN_ID"
```

```bash
# 4b + 4c. 觸發 backend current-state 投影——**必須跑兩次**
docker exec asset-business-services sh -lc "curl -fsS -H 'X-User-Id: $ADMIN_ID' -H 'X-User-Role: ADMIN' -H 'X-User-Status: ACTIVE' 'http://localhost:8080/api/market-data/dividends?code=2885&market=%E5%8F%B0%E8%82%A1&years=12' > /dev/null && echo projected-1"
docker exec asset-business-services sh -lc "curl -fsS -H 'X-User-Id: $ADMIN_ID' -H 'X-User-Role: ADMIN' -H 'X-User-Status: ACTIVE' 'http://localhost:8080/api/market-data/dividends?code=2885&market=%E5%8F%B0%E8%82%A1&years=12' > /dev/null && echo projected-2"
```

> **為什麼要跑兩次。** `DividendCurrentStateProjectionService.projectOne` 的第一行實質敘述就是 `collapseDuplicateActiveEvents(...)`，而新列是在同一方法後段才 upsert 進去。所以**第一次投影結束時必然是新舊兩列並存**，第二次投影開頭的收斂才會把錯誤那列 CANCELLED。錯誤列的 anchor（`2026-08-18`）已是過去，也不會被 cancel pass（條件 `LEAST(ex_dividend_date, ex_rights_date) > decisionDate`）順手處理。
>
> 若嫌取 `ADMIN_ID` 麻煩，也可以直接跑第 7 步的全庫回補——`DividendBackfillService` 的第二段本身就會對每一檔呼叫 `projectOne`；但同樣要跑**兩輪**（理由同上），見 361.4c。

```bash
# 5. 驗收 AC5 結果：必須恰好一列，且 ex_dividend_date 為空、ex_rights_date=2026-08-18
docker exec asset-postgres sh -lc "psql -U \$POSTGRES_USER -d \$POSTGRES_DB -c \"SELECT id, cash_dividend, stock_dividend, ex_dividend_date, ex_rights_date, event_status FROM stock_dividend_history WHERE stock_code='2885' AND year=2026 AND stock_dividend = 0.400000 AND event_status='ACTIVE';\""
```

```bash
# 5b. 複驗同年其他列不受影響（現金那列、以及刻意不在本任務範圍的 2.6269 那列）
docker exec asset-postgres sh -lc "psql -U \$POSTGRES_USER -d \$POSTGRES_DB -c \"SELECT id, cash_dividend, stock_dividend, ex_dividend_date, ex_rights_date, event_status FROM stock_dividend_history WHERE stock_code='2885' AND year=2026 ORDER BY id;\""
```

```bash
# 6. 兩條路徑一致性：唯讀路徑仍看得到 stock=0.4／除權 2026-08-18 那筆
docker exec asset-external-materials-service curl -fsS 'http://localhost:8080/internal/dividend-history?code=2885&market=%E5%8F%B0%E8%82%A1&years=12'
```

```bash
# 7. 全庫回補（361.4）：先存 baseline，再開旗標 recreate
docker exec asset-postgres sh -lc "psql -U \$POSTGRES_USER -d \$POSTGRES_DB -c \"SELECT stock_code, year, cash_dividend, stock_dividend, ex_dividend_date, ex_rights_date, event_status FROM stock_dividend_history ORDER BY stock_code, year, id;\"" > /tmp/t361-baseline.txt

DIVIDEND_BACKFILL_ENABLED=true DIVIDEND_BACKFILL_STATE_DIR=/home/steven/dividend-backfill-t361 \
  docker compose -p asset-management up -d --no-deps --force-recreate business-services
```

```bash
# 8. 證明環境變數真的生效、回補真的有跑（處理檔數不得為 0），並**等它跑完**
docker exec asset-business-services env | grep DIVIDEND_BACKFILL
docker logs asset-business-services 2>&1 | grep -i 'dividend.*backfill' | tail -30
```

> 回補由 `DividendBackfillStarter` 在 `ApplicationReadyEvent` 起一條 daemon thread 執行，分批且批次間預設停 5 秒，全庫數百檔要跑一段時間。**完成訊號是 log 出現「配息四日期回補結束」那一行**；沒看到就不要進第 9 步，否則 after 拍到的是中間態。開始那行「配息四日期回補開始：目標 N 檔、已完成 M 檔、本次待跑 K 檔」的 **K 不得為 0**——K=0 代表 361.4a-0 沒生效或 state-dir 沒換到 t361，**不得當成「本來就沒有要補的」**。

```bash
# 9. 第一輪回補跑完後，**再跑第二輪**讓收斂生效（理由同第 4b/4c）
#    第二輪的 pending 會是 0，但每檔仍會先跑 collapseDuplicateActiveEvents
DIVIDEND_BACKFILL_ENABLED=true DIVIDEND_BACKFILL_STATE_DIR=/home/steven/dividend-backfill-t361-pass2 \
  docker compose -p asset-management up -d --no-deps --force-recreate business-services
# 同樣等 log 出現「配息四日期回補結束」再往下
```

```bash
# 10. 取 after 並 diff（SQL 必須與第 7 步逐字相同），然後拿掉旗標回到常態
docker exec asset-postgres sh -lc "psql -U \$POSTGRES_USER -d \$POSTGRES_DB -c \"SELECT stock_code, year, cash_dividend, stock_dividend, ex_dividend_date, ex_rights_date, event_status FROM stock_dividend_history ORDER BY stock_code, year, id;\"" > /tmp/t361-after.txt
diff /tmp/t361-baseline.txt /tmp/t361-after.txt

docker compose -p asset-management up -d --no-deps --force-recreate business-services
docker compose -p asset-management restart bff
```

> 第 10 步最後的 recreate **不帶旗標**，這就是關掉回補、回到常態的方式（`DIVIDEND_BACKFILL_ENABLED` 的 compose 預設為 `false`）。
>
> **`diff` 的輸出才是 361.4c 要回填的逐列明細**，不要拿 `report.tsv` 的 `corrections` 欄當明細來源：`DividendBackfillService.backfillOne` 的 before／after 夾在**同一次** `projectOne` 兩側，after 取樣時新列剛 INSERT、舊錯誤列仍 ACTIVE，因此它記到的是「多一列」而不是「日期搬家」——corrections 會低估、rowsAfter 會虛增。這是既有機制的取樣時點問題，本任務不改它，只是不採信它的數字。
>
> `/tmp/t361-baseline.txt` 與 `/tmp/t361-after.txt` 都在 **host**（重導向發生在 host shell，`docker exec` 只負責產出 stdout）。

**通過判準**：第 5 步必須**恰好回傳一列**，且該列 `ex_dividend_date` 為空、`ex_rights_date` 為 `2026-08-18`。

- **回傳兩列** ＝ 多半是**只投影了一次**（新舊兩列並存是第一次投影的正常中間態）。補跑一次第 4b/4c 的 curl 再驗；**跑滿兩次之後仍是兩列**，才是 361.2e 沒生效。
- **回傳一列但 `ex_dividend_date = 2026-08-18`** ＝ keeper 判準挑錯列，不算通過。
- **回傳零列** ＝ 抓取仍漏抓，361.1／361.2 沒生效。

`stock_dividend = 0.400000 AND event_status='ACTIVE'` 這個過濾不可省略：同年同日另有一列 `stock_dividend = 2.626900`（來自 fallback 表、語意本身有問題，刻意不在本任務範圍），不過濾就會把它誤讀成已修好。

第 5b 步中 `cash_dividend = 1.800000` 那列須維持 `ex_dividend_date = 2026-07-21`、`ex_rights_date` 為空、`event_status='ACTIVE'`；`stock_dividend = 2.626900` 那列維持原狀（本任務不處理它）。

## 完成報告

**實際改動檔案**：
- `external-materials-service/src/main/java/com/steven/assets/externalmaterials/client/DividendFetchClient.java`（抓取視窗錨點改用 LEAST(ex_dividend_date, ex_rights_date)）
- `external-materials-service/src/test/java/com/steven/assets/externalmaterials/client/DividendFetchWindowAnchorTest.java`（新增）
- `backend/src/main/java/com/steven/assets/service/DividendCurrentStateProjectionService.java`（`collapseDuplicateActiveEvents` 收斂邏輯）
- `backend/src/test/java/com/steven/assets/service/DividendCurrentStateProjectionServiceTest.java`
- `docker-compose.yml`（新增 `DIVIDEND_BACKFILL_STATE_DIR` passthrough）
- `spec/requirements.md`、`spec/design.md`、`spec/steering/structure.md`、`spec/tasks.md`、`spec/tasks/README.md`

**單元測試**：`external-materials-service`、`backend` 兩模組 `mvn test` 全綠。

**驗證段步驟 0-3**：`.env` 複製成功；`docker compose -p asset-management build --no-cache external-materials-service business-services` 建置成功；`up -d --force-recreate` 兩支 JVM service ＋ `restart bff` 全部成功，jar 時間戳確認為本次重建產物（非 stale）。

**步驟 4-4c**：POST `/internal/dividend/sync?code=2885` 回 `{"written":22}`；`ADMIN_ID` 查詢時發現任務檔字面 SQL 寫的資料表名 `users` 與實際 schema 不符，實際表名為 `app_user`，改用正確表名取得 `ADMIN_ID=1`；`/api/market-data/dividends?code=2885&years=12` 投影兩次（`projected-1`、`projected-2`）皆成功。

**步驟 5（AC5）— 恰好一列，通過**：
```
 id  | cash_dividend | stock_dividend | ex_dividend_date | ex_rights_date | event_status
1292 |      0.000000 |       0.400000 |                  | 2026-08-18     | ACTIVE
```

**步驟 5b**：同年其他列——`id=977`（現金 1.8，`ex_dividend_date=2026-07-21`、`ex_rights_date` 空、ACTIVE，未受影響）；`id=1004`（原錯誤列，已因兩次投影收斂為 `CANCELLED`）；`id=1212`（`stock_dividend=2.6269`，本任務範圍外，維持原狀 ACTIVE）；`id=1292`（本次修正後的正確列）。

**步驟 6**：`/internal/dividend-history?code=2885&years=12` 唯讀路徑回傳 2026 年 `stockDividend=0.4000, exDividendDate=null, exRightsDate="2026-08-18"`，與 current-state 一致。

**361.4／361.4c：全庫回補兩輪＋逐列明細**：
`DIVIDEND_BACKFILL_ENABLED=true` 確認生效；第一輪 log「配息四日期回補開始：目標 62 檔、本次待跑 62 檔」（K=62，非 0），第一輪與第二輪皆以「配息四日期回補結束：...成功 62 檔、失敗 0 檔、357.3b 修正 0 列」收尾（各耗時約 2 分半鐘），符合「等到結束訊號才視為完成」的要求。

`diff /tmp/t361-baseline.txt /tmp/t361-after.txt` 輸出為**空**（全庫 1295 行 baseline 與 after 逐字相同，零變動）。逐列明細：本任務全庫僅有 `stock_code='2885'` 的 `id=1004` 一列受影響，而該列已在步驟 4b/4c 的兩次手動投影中先行修正並收斂（時序早於步驟 7 的 baseline 擷取），因此 baseline 擷取當下全庫已無不自洽列，全庫回補的兩輪 diff 自然為空，不代表回補機制沒有執行（K=62 佐證其確實對 62 檔逐一跑過 `projectOne`／`collapseDuplicateActiveEvents`）。以「純配股卻有 `ex_dividend_date`」／「純現金卻有 `ex_rights_date`」為條件複查全庫，同樣回傳 0 列，與「全庫僅 2885 id=1004 一列受影響」的判斷一致。

驗收完成後已依規範拿掉 `DIVIDEND_BACKFILL_ENABLED` 旗標、`--force-recreate business-services` 回到常態並 `restart bff`，三支容器（business-services／external-materials-service／bff）皆為 healthy。

**與原計畫的偏差**：唯一偏離任務檔字面指令處是步驟 4a 用來查 `ADMIN_ID` 的資料表名——任務檔寫 `users`，實際 schema 為 `app_user`；已用正確表名取得同一個 `ADMIN_ID=1`，其餘所有 SQL／指令均逐字比照任務檔執行。
