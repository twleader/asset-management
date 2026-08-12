# [t320] 交易雷達新增「即時折溢價」欄（表格＋JSON＋Excel，與既有完成日折溢價並存）

**對應 Requirements:** Requirement 43（今日交易雷達——不呼叫 AI API 的台股規則式買賣決策輔助）追加段「Task 320 交易雷達即時折溢價欄」；相依既有規格 Requirement 34（資產 Excel 匯出的 ETF 淨值／折溢價欄，Task 214／215／259）
**前置任務:** 無（既有 Redis `price:etfnav:{market}:{code}` 與 `ExcelExportService.premiumDiscountPct()` 皆已在 production 運作）
**Liquibase changeset:** 無（即時值只讀 Redis、不入庫；長期留存已由 `etf_nav_history`／`etf_nav_observation` 負責）

---

## 背景

使用者要求：「交易雷達表格多一個折溢價欄位，盤中股價變動時也要跟著更新，輸出的 JSON、Excel 也要有這個值。」

**現況為什麼看起來「抓不到」。** 交易雷達已經有一個 `StockDecision.etfPremiumPct`，但它是 **dated observation**：
`TradingRadarService.etfPremiumObservation()`（`backend/src/main/java/com/steven/assets/service/TradingRadarService.java:1284`）
只認**已完成交易日**的 observation，盤中刻意排除當日（該方法 `:1292`–`:1294` 現有註解為英文，大意為：
收盤前今日 NAV 不是 completed-session observation、不得進 premium veto）。理由是這個值會進
`buyGate` 硬否決（溢價 `>= 3%`）與 `OVERBOUGHT` 判定，用未完成 session 的 NAV 會破壞 point-in-time 正確性。

它還只在主表格的**收合列**裡（`frontend/src/views/TradingRadarView.vue:214`），主表格沒有折溢價欄，
且美股 ETF 恆為 `null`（`TradingRadarService.java:1291` 對 `US_MARKET` 直接回 unavailable，Task 294.5 的
刻意設計）。所以使用者在表格上「看不到折溢價」。

**正確行為。** 新增一個**獨立**的即時折溢價欄位，與該列現價同一個 tick，純揭露、不進任何規則；既有
`etfPremiumPct`／`etfPremiumPercentile` 的取值路徑、語意與規則接線一個位元都不動。兩者在盤中本來就會不同，
畫面上必須能分辨。

**這個決定不推翻任何先前做法**：Task 309 的 dated 路徑照舊，Task 294.5 的「美股不進 factor」照舊——
本任務新增的欄位本來就不是 factor，故美股有值不違反該決定。

---

## 要做什麼

### 資料取得與共用計算

- [x] 320.1 **抽出單一共用實作，不得複製第二份分流。** 「台股直取權威值／美股以該列現價反推／缺值回 null」
  這組分流**已存在**於 `backend/src/main/java/com/steven/assets/service/ExcelExportService.java:969`
  的 `static BigDecimal premiumDiscountPct(PriceQueryService.EtfNav nav, BigDecimal livePrice)`。
  把它抽為單一共用實作（建議新檔 `backend/src/main/java/com/steven/assets/service/EtfLivePremiumCalculator.java`，
  純靜態、無 Spring 依賴、可脫離 context 單元測試），供資產總覽 Excel 與交易雷達兩處呼叫。
  `ExcelExportService` 改為委派它，**既有輸出必須逐格不變**。
  **不得**在 `TradingRadarService` 另寫一份同義計算——同義欄位在兩處各自演化，會產生
  「一個頁面對、另一個頁面錯 0.07 個百分點」的靜默分岔。

- [x] 320.2 **共用實作的分流優先序與三條紅線（原文照抄自 Requirement 34／Task 214／Task 259，違反會產生「平盤日正常、大跌日離譜」的靜默錯誤）：**
  0. **先照抄現行的判斷順序，不得改寫成「市場二分」**。`ExcelExportService:970`–`:976` 的實際優先序是
     「`nav == null` 回 null → **`nav.premiumDiscountPct()` 有值即原樣回傳（不看 market）** → 台股回 null →
     其餘以現價反推」。下面三條紅線是這個順序在兩個市場的**結果**描述，不是實作結構：
     今天美股 payload 恆無 `premiumDiscountPct` 故兩種寫法等價，但若照字面寫成
     `if (市場 == 台股) 直取 else 反推`，就偏離了現行語意，違反 320.1「既有輸出逐格不變」。
  1. **台股直接取權威值、一律不重算**：取 Redis payload 的 `premiumDiscountPct`（＝證交所 `all_etf.txt` 已算好的
     `g` 欄）。**不得自行以 (市價 − 淨值)/淨值 重算**——淨值欄在股票型 ETF 被四捨五入至小數 2 位，重算誤差達
     0.07 個百分點，與各站顯示對不起來卻又不夠離譜到會被發現。**亦不得使用「前一交易日淨值」欄計算**——
     該欄對全部檔位皆為 T-1，實測台股重挫日會把 0050 的真實 +1.2% 溢價算成 −5.8% 折價。
     台股該欄留白時回 `null`，**不反推**（Task 259 鐵則）。
  2. **美股以該列自己的現價反推**：Yahoo 不提供折溢價欄，Redis `price:etfnav:美股:{code}` 恆無
     `premiumDiscountPct`。以該列畫面上顯示的現價與 `nav` 計算 `(price − nav) / nav × 100`，
     `divide(nav, 2, RoundingMode.HALF_UP)`（沿用 `ExcelExportService` 現行精度）。
     **不得改用同回應的 `previousClose`**（那是 T-1，會把 VOO 真實 +0.003% 溢價放大成 +1.02%）。
  3. 現價或淨值任一為 `null`、或淨值為 0 時回 `null`。個股（Redis 查無 `price:etfnav:*` key）恆為 `null`，
     **不補 0、不補「N/A」、不做 ETF 白名單**（既有 `isEtf()` 白名單誤把 AVGO 當 ETF、又漏掉 SGOV，刻意不複用）。

- [x] 320.3 **在 `TradingRadarService` 逐檔組裝內取值，且折溢價必須沿用該列已組好的 `displayPrice`、不得為它再取一次「價」。**
  **先釐清「同一 tick」的正確意思**：現價與淨值本來就是兩支方法讀兩個不同的 Redis key
  （`PriceQueryService:187` 的 `price:{market}:{code}` vs `:213` 的 `price:etfnav:{market}:{code}`），
  `LivePrice` record 裡沒有 nav 欄位，**不存在「一次讀到價又讀到淨值」的路徑**。要守的是
  「**價只取一次**」——折溢價一律沿用同一列已組好的 `displayPrice`；讀淨值必然是第二次 Redis 讀取，
  那不在禁止之列。
  接點在 `TradingRadarService.java:776` 的 `BigDecimal displayPrice = acceptedPrice.value();` 之後、
  `StockDecision` 建構之前：以 `priceQueryService.getEtfNav(code, market)` 取一次 `EtfNav`，
  連同 `displayPrice` 餵給 320.1 的共用實作，得 `etfPremiumLivePct`；`etfPremiumLiveNavAsOf` 為該
  `EtfNav.navAsOf()` 原樣字串（台股 `yyyyMMdd HH:mm:ss`、美股 `yyyy-MM-dd`），無值為 `null`。
  **不得**為折溢價另打一次 `priceQueryService` 取價——分兩次取數會讓「現價」與「折溢價」落在不同 tick 上，
  使用者拿畫面數字驗算 `(price−nav)/nav` 會兜不攏（這正是美股改在消費端計算的同一個理由）。
  `getEtfNav` 查無時回 `Optional.empty()` 為正常情形（個股），兩個新欄位皆為 `null`，不記 warn 洗版。
  **呼叫次數的正確描述**：production 走 strict 分支（`etfNavObservationRepository != null`）時，逐檔迴圈內每檔
  新增**一次** `getEtfNav`；但 `TradingRadarService.java:1315` 在相容 fallback 分支
  （`etfNavObservationRepository == null`，只在部分單元測試成立）另有一次既有呼叫，
  故**測試不得斷言全域 `verify(times(1)).getEtfNav(...)`**——那在 fallback 路徑下會是兩次。

### DTO 與規則隔離

- [x] 320.4 **`TradingRadarDto.StockDecision` 新增兩個 component，一律附加在 record 最末。**
  `backend/src/main/java/com/steven/assets/dto/TradingRadarDto.java`，新增
  `BigDecimal etfPremiumLivePct`（百分比數值，`1.2` 表示溢價 1.2%、負值為折價）與
  `String etfPremiumLiveNavAsOf`。**附加在 record 的真正最末**（現行末項為 `List<String> actionGateReasons`，
  同檔 `:659`；record 共 60 個 component，加完為 62），不得插在既有 `etfPremiumPct`／`etfPremiumPercentile`
  之後或任何中間位置——中間插入會讓既有 positional 呼叫端一起位移。既有 component 的順序、名稱、型別一律不動。
  **三個 positional 呼叫端必須同步在末尾各補兩個值**（漏做會編譯失敗，但先列出以免實作者誤以為只有一處）：
  - `TradingRadarService.java:927` 的 canonical constructor（**主組裝路徑**，`:942` 傳的就是 `displayPrice`）——
    這一處補的是 320.3 算出的**兩個真值**，不是 `null`。
  - `TradingRadarDto.java:662`–`:685` 的**次要建構子**（javadoc `:661` 寫「Task 291 前的欄位形狀，供既有測試建構資料」，
    **它是次要建構子、不是靜態工廠**），其 `this(...)` 委派（`:675`–`:684`）末尾需再補兩個 `null`。
  - `TradingRadarService.java:1455` 的 `incompleteStock()`，走 canonical constructor，
    末尾（`:1492` 的 `null, null, ..., List.of()`）需再補兩個 `null`。

- [x] 320.5 **既有 dated 欄位一個位元都不動。** `etfPremiumPct`／`etfPremiumPercentile` 的取值路徑
  （`etfPremiumObservation()`／`etfPremiumPercentile()`／`TradingRadarPremiumResolver`／
  `EtfNavObservationRepository`）、`RadarEvidence` 的 `premiumAsOfDate`／`premiumSource`／`premiumStale`
  三個 provenance 欄位，全部維持現狀。
  **嚴禁把即時值寫回 `etfPremiumPct` 或讓它進入任何 veto**——那會把未完成 session 的 NAV 帶進決策，
  且盤中每 5 分鐘就讓同一決策日的 veto 結果漂移一次。

- [x] 320.5a **必須改寫一條會誤擋本任務的既有測試斷言（不改就只有兩條錯路可走）。**
  `backend/src/test/java/com/steven/assets/service/TradingRadarUsStockEngineTest.java:572` 的
  `美股ETF折溢價因子仍為null即使既有資料來源都查得到值()`，其 `:591` 目前是
  `verify(priceQueryService, never()).getEtfNav(anyString(), eq("美股"))`。
  本任務對美股**必須**呼叫 `getEtfNav`（`EtfNav` 沒有第二個取得路徑，而美股即時折溢價是明訂的驗收條件），
  故這條 `never()` 必然變紅。**它失敗時的訊息會偽裝成架構違規**（測試名稱寫的是 Task 294.5「美股折溢價恆為 null」），
  實作者若照字面反應，只會走上兩條都錯的路：(a) 為了讓測試綠而不對美股取 NAV → 靜默砍掉本任務一半的功能；
  (b) 直接刪掉該行 → 拆掉 Task 294.5 真正要守的護欄。
  **正解是把它收窄成「不進 factor 路徑」**：改為斷言該美股決策的 `etfPremiumPct()` 與 `etfPremiumPercentile()`
  仍為 `null`（＝ `etfPremiumObservation()` 對 `US_MARKET` 仍在 `TradingRadarService.java:1291` early-return），
  而**不再**斷言 `getEtfNav` 未被呼叫。同檔 `:592` 的 `findRecentPremiumPct` 的 `never()` 屬 dated 路徑護欄，
  **維持不動**。此為本任務唯一被授權**放寬**的既有測試斷言；其餘既有測試一律不得為了讓本任務通過而放寬。
  （320.12a 的 `STOCK_HEADERS_V11` 末尾補兩欄不在此限——那是同步既有欄名清單這個「事實」，
  補完後 `containsExactlyElementsOf` 的嚴格度不變，不是放寬。）

- [x] 320.6 **不進評分、不升版。** 兩個新欄位不得進 `TradingRadarRuleEngine.StockInput`／`MarketInput`，
  不得影響 `action`／`score`／`regime`／`buyGate`／`kdHeat`／`timingState`／`reasons`／`risks`。
  `RULE_VERSION` 維持 `TW_RULES_V12`，**不升版**（判準同 Task 281 的先例：規則集本身未變——不新增／不修改
  任何因子、權重或動作門檻，同一份輸入產生逐位相同的決策輸出）。
  不得因本任務觸發 Requirement 44 的通知 baseline 重建。

- [x] 320.7 **舊快照相容。** 雷達結果快照（Requirement 48，Redis）新增兩欄後雜湊必然改變；舊快照缺這兩欄
  必須可讀——反序列化為 `null`，不得拋例外、不得因缺欄整筆丟棄（比照既有「舊快照缺欄可讀」慣例）。

### 前端

- [x] 320.8 **主表格新增一欄，位置在「現價／漲跌」之後、「MA5／20／60／240」之前。**
  `frontend/src/views/TradingRadarView.vue`，接點在 `:564`（`<el-table-column label="現價／漲跌" ...>`）
  與 `:573`（`label="MA5／20／60／240"`）之間。欄名 **`折溢價(即時)`**，`min-width="120"`、`align="right"`。
  值為 `row.etfPremiumLivePct`，沿用既有 `fmtPct` 顯示；`null`（個股與查無者）顯示 `—`，**不補 0**。
  次要文字（`font-size:12px`、灰）顯示 `row.etfPremiumLiveNavAsOf`，無值時該行不顯示。

- [x] 320.9 **收合列既有折溢價項改標題以區分語意。** 同檔 `:214`–`:219` 的收合列項目保留不動（值仍為
  `etfPremiumPct` 與 `etfPremiumPercentile`、含「分位資料累積中（需滿 60 個交易日）」文案），
  但 `<span>折溢價</span>` 改為 `<span>折溢價(完成日)</span>`。
  理由：同一畫面出現兩個同名但不同語意的數字，盤中兩者本來就會不同，使用者必須能分辨——
  收合列那個是進 veto 的完成日值、新表格欄是盤中即時值。

- [x] 320.10 **不新增自動 polling；但要知道頁面本來就有 SSE，並處理它造成的 2 秒不一致視窗。**
  雷達現行行為**不是**單純的「進頁載入 ＋ 手動重新整理」，實際有三條更新路徑：
  (1) 進頁載入；(2) 使用者按「重新整理」；(3) **常駐 `EventSource('/api/market-data/prices/stream')`**
  （`frontend/src/views/TradingRadarView.vue:1083`）的 `price-update` 事件即時覆寫 `row.price`（`:1057`），
  並觸發 `scheduleRecalculation()`（`:1072`）在 `RECALCULATE_DELAY_MS = 2000`（`:953`）後背景重打
  BFF 全量重算（`:1036` 的 `await load(false, true)`）。
  **後果**：SSE 覆寫只帶報價、不帶淨值，故在該事件到達後的約 2 秒內，畫面上的「現價」已跳動、
  而「折溢價(即時)」仍是上一輪 API 的值——正是本任務三條紅線宣稱要防的「使用者拿畫面數字驗算
  `(price−nav)/nav` 兜不攏」。**處置**：接受此暫時不一致（2 秒內由背景 `load()` 自癒），
  但 `applyPriceUpdate` **不得**用新報價在前端自行重算折溢價（前端重算會踩台股那條「不得由淨值反推」的紅線）。
  **不得**為本任務新增 `setInterval` 輪詢或第二條 SSE 訂閱——(3) 已經存在，再加只會重複。
  後端側的「同一 tick」保證仍然成立且不受此影響：`TradingRadarService.java:942` 傳給 DTO 的
  `price` 就是 `displayPrice`，與 320.3 取折溢價所用的是同一個值。

### 匯出（JSON／Excel 同一份 header）

- [x] 320.11 **附加在整張表的真正最末，既有欄索引不得位移。**
  `backend/src/main/java/com/steven/assets/service/TradingRadarExportService.java` 的 `stockSheet()`（`:211`–`:314`）。
  **先釐清一個容易搞錯的事實**：`"折溢價%"` **不是**這張表的末欄——它位於 `headers` 索引 **53**，整張表共 **172** 欄，
  其後尚有 118 欄（`FUNDAMENTAL_HEADERS` 29 ＋ 支持訊號／風險 6 ＋ `EVIDENCE_HEADERS` 12 ＋
  `DETAIL_EVIDENCE_HEADERS` 53 ＋ `VALUATION_COMPONENT_HEADERS` 18），真正的末三欄是
  `殖利率可得時間／殖利率資料日期／殖利率缺漏原因`。**因此絕對不可以插在 `:221`–`:222` 的「折溢價%」之後**——
  那會把其後 118 欄全部往後推兩格，其中正好包含本任務明文要求維持不動的
  「折溢價時點／折溢價來源／折溢價stale」（`:413`／`:497`–`:498`）。
  **正確插入點是整張表的尾端**：
  - `headers`：在 `:228` 的 `headers.addAll(VALUATION_COMPONENT_HEADERS)` **之後**再 `addAll(List.of("即時折溢價%", "即時淨值時間"))`。
  - `rows`：在 `:273` 的 `row.addAll(valuationComponentCells(fundamental, evidence))` **之後**再
    `row.addAll(Arrays.asList(num(d, "etfPremiumLivePct"), txt(d, "etfPremiumLiveNavAsOf")))`。
  - `formats`：在 `:310` 的 `formats.addAll(VALUATION_COMPONENT_FORMATS)` **之後**補
    `ExportDoc.Format.NUM2`（即時折溢價%）與 `ExportDoc.Format.TEXT`（即時淨值時間）。
  既有末欄「折溢價%」的欄名、值（仍取自 `etfPremiumPct`）與欄索引 53 皆不得改動。
  附加在真正最末的理由同 Task 285／286：既有 golden 逐格比對與下游取值皆以欄索引定位。
  **mid-sheet 插入並非做不到**——Task 281 就是插在中間（其 `EXT_HEADERS` 落在個股決策表索引 31–44）——
  但那要另外維護欄索引位移映射，是本次不必要的成本，**本任務一律走尾端附加、不建立新的位移映射**。
  （注意別找錯對照：`TradingRadarDualFormatTest.java:529` 那條「Task 281：插欄前後既有欄逐格未變」
  的映射比對**只涵蓋「快照索引」分頁**、用的是 t316 的 `mapPreActionPolicyIndex`；
  **「個股決策」這張表目前沒有任何既存位移映射可沿用**。）
- [x] 320.12 **`headers`／`formats`／`rows` 是三份清單，必須同步且同位置修改。**
  程式碼在 `:277` 自己就寫著「headers／formats／rows 三者長度與順序必須一致；`ExportDoc` 會在建構時 fail fast」。
  三者都在 `stockSheet()` 內：`headers`（`:214`–`:228`）／`rows`（`:236`–`:273`）／`formats`（`:278`–`:310`）。
  **`ExportDoc` 的 fail-fast 只驗長度、不驗位置**——若三份清單都在中段同步插入，長度依然一致、fail-fast 不會觸發，
  但欄索引已整體位移 118 欄，缺陷會靜默通過。故不得倚賴 fail-fast 當作沒位移的證據，必須由下方驗證段 (f) 的測試釘住索引。
  JSON 與 Excel 由同一份 header 清單產生（`JsonDocRenderer` 只讀 header／rows，不讀 `Format`），
  **不得為兩種格式各拼一次欄**。
- [x] 320.12a **同步既有測試裡寫死的 172 個字面欄名清單（不同步就一定紅，且與 320.5a 不衝突）。**
  `backend/src/test/java/com/steven/assets/service/export/TradingRadarDualFormatTest.java` 的
  `STOCK_HEADERS_V11`（`:205`–`:242`）是 **172 個字面欄名**的硬清單，`:309` 以
  `containsExactlyElementsOf(STOCK_HEADERS_V11)` 逐欄比對、描述字串為 `.as("個股決策 172 欄")`。
  尾端加兩欄後必紅。故須：(1) 在 `STOCK_HEADERS_V11` **末尾**補 `"即時折溢價%"`、`"即時淨值時間"`；
  (2) 把 `.as("個股決策 172 欄")` 改為 `174 欄`；
  (3) **修正同一支測試方法內三條「由 `size()` 往回推」的錨點算式**（`:310`–`:312`）——
  `valuationStart = STOCK_HEADERS_V11.size() - VALUATION_COMPONENT_HEADERS_EXPECTED.size()`、
  `detailStart = valuationStart - DETAIL_HEADERS_EXPECTED.size()`、`evidenceStart = detailStart - 12`，
  三者餵給 `:313`／`:318`／`:320` 的 `subList(...)` 比對。尾端多兩欄後這三條全部錯位、**三條一起紅**，
  而且失敗訊息讀起來就是「欄位整體位移了」——正好偽裝成本任務最想避免的災難，
  實作者很可能因此誤判成「尾端附加行不通」而轉去做 mid-sheet 插入或弱化 `containsExactlyElementsOf`，
  兩者都是錯的。正解是讓錨點扣掉本次新增的欄數再反推，例如：

```java
int liveCols = 2;  // Task 320 尾端附加
int valuationStart = STOCK_HEADERS_V11.size() - liveCols - VALUATION_COMPONENT_HEADERS_EXPECTED.size();
int detailStart = valuationStart - DETAIL_HEADERS_EXPECTED.size();
int evidenceStart = detailStart - 12;
// 既有三條 subList 比對維持不變，另加一條釘住新欄就在最末：
assertThat(STOCK_HEADERS_V11.subList(STOCK_HEADERS_V11.size() - liveCols, STOCK_HEADERS_V11.size()))
        .containsExactly("即時折溢價%", "即時淨值時間");
```

  **同一支測試裡不受影響、不要順手改的部分**（已逐條確認）：`:269` 的 `subList(31,45)`（前錨）、
  `:322` 的去重數量斷言（新欄名不重複）、`:377`／`:379`／`:402`–`:422`／`:508`–`:525` 的 `indexOf(...)`、
  `:491`–`:498` 寫死的索引 23／31／37／44、`:529`–`:550` 的 `assertSameMapped`（只比對「快照索引」分頁）。
  **這是同步既有事實，不是放寬斷言**，與 320.5a 的「唯一被授權改動的既有測試斷言」不衝突——
  該清單補在末尾後，`containsExactlyElementsOf` 仍逐欄嚴格比對，正好就是驗證段 (f) 要的零位移證明。
  先例：Task 285 的任務檔也明文列了「`DualFormatSingleTableExportTest` 有寫死的欄索引斷言」需同步。

---

## 驗證

### 單元測試（新增／擴充，與實作同一支任務）

至少覆蓋下列七項；台股／美股各自的 fixture 直接構造 `PriceQueryService.EtfNav`，不需 Spring context：

- (a) **台股原樣取用權威值**：`EtfNav(market="台股", nav=105.57, premiumDiscountPct=-0.35)` 配
  `livePrice=110.00`，斷言回 **`-0.35`**（權威值原樣），並斷言它**不等於**以該列現價重算的
  `(110.00−105.57)/105.57×100 = +4.20`（`HALF_UP` 至 2 位）——這一項就是釘住「取的是權威值而非重算值」。
  **fixture 必須讓重算值與權威值明顯不同才有鑑別力**：例如拿 `livePrice=105.20` 就不行，
  `(105.20−105.57)/105.57×100 = −0.350478…` 四捨五入後恰好也是 `-0.35`，兩者相等、斷言恆紅。
- (b) **台股折溢價欄留白時回 `null`**（不反推）：`EtfNav(nav=105.57, premiumDiscountPct=null)` 配有效現價 → `null`。
- (c) **美股以該列現價反推**：`EtfNav(market="美股", nav=710.53, premiumDiscountPct=null)` 配 `livePrice=712.00`
  → `(712.00−710.53)/710.53×100` 四捨五入至 2 位；並斷言與「用 T-1 收盤價反推」的結果不同（釘住不得用 `previousClose`）。
- (d) **個股回 `null`**：`nav == null` 或 `EtfNav` 為 `null`（Redis 查無）→ `null`；淨值為 0 → `null`。
- (e) **反射釘子**：掃 `TradingRadarRuleEngine.StockInput` 與 `MarketInput` 的 record component 名，
  斷言不含 `etfPremiumLive` 字樣（大小寫不敏感），且 `etfPremiumPct` 的既有接線不變。
- (f) **匯出欄零位移**：斷言個股決策摘要表 (i) `"折溢價%"` 的欄索引仍為 **53**；(ii) 新兩欄
  `"即時折溢價%"`、`"即時淨值時間"` 出現在**整張表最末**（即現行末欄 `"殖利率缺漏原因"` 之後），
  總欄數由 **172** 增為 **174**；(iii) 既有 `"折溢價時點"／"折溢價來源"／"折溢價stale"` 三欄索引不變；
  (iv) `headers`／`formats`／`rows` 三者長度一致。**不得只斷言長度一致**——`ExportDoc` 的 fail-fast
  已經在驗長度，它擋不住中段插入造成的整體位移，本項的重點是索引。
  實作上這一項主要由 320.12a 同步 `STOCK_HEADERS_V11`（末尾補兩欄、`.as()` 描述 172→174）後，
  由 `TradingRadarDualFormatTest:309` 的 `containsExactlyElementsOf` 逐欄比對自動釘住；
  該檔 `:529` 的 Task 281 映射比對只涵蓋「快照索引」分頁、本任務不會使它變紅，須維持綠燈且**不新增位移映射**。
- (g) **`ExcelExportService` 零回歸**：抽出共用實作後，既有資產總覽折溢價輸出逐格不變
  （沿用既有 `ExcelExportPremiumOriginTest` 並擴充）。
- (h) **舊快照缺兩欄可讀（釘住 320.7）**：`TradingRadarDualFormatTest` 既有的舊快照相容測試
  （`:477`–`:526`，`@DisplayName("Task 281：舊快照沒有 extendedIndicators／weeklyMa 時…不擲例外")`）
  **不會**因本次尾端附加而自動變紅（它用前錨索引與 `indexOf`），故 320.7 目前是一條沒有測試釘子的約束，
  必須補：以缺 `etfPremiumLivePct`／`etfPremiumLiveNavAsOf` 的舊快照 golden 匯出，斷言「個股決策」末兩欄
  在 Excel 為 `CellType.BLANK`、在 JSON 為 `null`，且匯出過程不擲例外。

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

> 用 `-DextraArgLine`，**不要用 `-DargLine`**——後者自 Task 252 起會覆蓋掉時區設定，造成 181 個測試 error
> 且錯誤訊息偽裝成 byte-buddy 問題。

### 建置與部署（本專案沒有 dev server，「改好」＝ image rebuild ＋ container recreate）

```bash
cp /Users/steven/Project/asset-management/.env .
```

```bash
docker compose -p asset-management build --no-cache business-services frontend
```

```bash
docker compose -p asset-management up -d --no-deps --force-recreate business-services frontend
```

```bash
docker compose -p asset-management restart bff
```

> 三點必須照做：(1) worktree 內跑 compose 前先把主 repo 的 `.env` 複製進來（`env_file` 相對 compose 檔解析，
> `--env-file` 救不了）；(2) JVM service 一律 `--no-cache`，cached build 會產出不含本次變更的 stale jar；
> (3) recreate `business-services` 會換 IP，BFF 握舊 IP 會回 500 且 ≥3 分鐘不自癒（Docker DNS TTL 600s），
> 故必須 restart `bff`。`-p asset-management` 不可省，否則會建到沒人用的 tag。

### 實機驗證（在 business 容器內帶 X-User-* header，免走 Google 登入）

```bash
docker exec asset-business-services sh -c 'curl -s -H "X-User-Id: 1" -H "X-User-Role: ADMIN" -H "X-User-Status: ACTIVE" "http://localhost:8080/api/trading-radar"' | python3 -c "import json,sys; d=json.load(sys.stdin); [print(r['stockCode'], r['market'], r.get('etfPremiumLivePct'), r.get('etfPremiumLiveNavAsOf'), r.get('etfPremiumPct')) for r in d['stocks']]"
```

須同時滿足：台股 ETF（0050／0056／00878／006208 等）`etfPremiumLivePct` 有值且與 Redis
`price:etfnav:台股:{code}` 的 `premiumDiscountPct` **逐位相同**；美股 ETF（VOO／QQQ／VT／SGOV）
`etfPremiumLivePct` 有值（現行 `etfPremiumPct` 對美股恆為 `null`，這是預期的語意差異）；
個股（2330／NVDA／2891）兩個新欄位皆為 `null`。

```bash
docker exec asset-redis redis-cli GET "price:etfnav:台股:0050"
```

前端則於瀏覽器開啟交易雷達，確認主表格「現價／漲跌」右側出現「折溢價(即時)」欄、個股該格顯示 `—`。
驗「折溢價(完成日)」這個改名時**必須挑一檔台股 ETF**（例如 0050／00878）展開收合列——該項是
`v-if="row.etfPremiumPct != null"`，而美股與個股的 `etfPremiumPct` 本來就恆為 `null`、整項不會 render，
在那些列上找不到它是預期行為，不是改壞。

匯出驗收：觸發交易雷達匯出，確認 `.xlsx` 與 `.json` 兩份的個股決策表最末兩欄為
`即時折溢價%`／`即時淨值時間` 且台股 ETF 有值。

> **新兩欄全空時，先排除快照節流再判定功能沒生效。** `TradingRadarSnapshotStore.write()` 的順序是
> 「先節流、後去重」，間隔取自 `trading-radar.snapshot.min-interval-minutes`（預設 5 分鐘）；
> 匯出讀的是已落地的快照，若該 owner 在 5 分鐘內已被開頁或 SSE 觸發過，下一次 `get()` **不會**落新快照，
> 匯出檔就會是舊結構、新欄全空。這是本專案已經踩過並留下警語的坑
> （`spec/requirements.md` Requirement 48 該段警語原文即為此）。等過節流間隔或重啟容器後重試。

---

## 完成報告

### 實際改動的檔案

**新增（2 主 1 測共 3 檔）**

| 檔案 | 內容 |
|---|---|
| `backend/src/main/java/com/steven/assets/service/EtfLivePremiumCalculator.java` | 320.1／320.2 的單一共用實作；純靜態、無 Spring 依賴 |
| `backend/src/test/java/com/steven/assets/service/EtfLivePremiumCalculatorTest.java` | 驗證 (a)–(d) 共 8 條 |
| `backend/src/test/java/com/steven/assets/service/TradingRadarLivePremiumRuleIsolationTest.java` | 驗證 (e) 反射釘子共 3 條 |

**修改**

| 檔案 | 內容 |
|---|---|
| `backend/src/main/java/com/steven/assets/service/ExcelExportService.java` | `premiumDiscountPct()` 改為純委派共用實作（320.1） |
| `backend/src/main/java/com/steven/assets/dto/TradingRadarDto.java` | `StockDecision` 末尾附加 `etfPremiumLivePct`／`etfPremiumLiveNavAsOf`（60 → 62 component）；次要建構子的 `this(...)` 補兩個 `null`（320.4） |
| `backend/src/main/java/com/steven/assets/service/TradingRadarService.java` | `displayPrice` 之後取一次 `getEtfNav` 並算即時折溢價；canonical constructor 與 `incompleteStock()` 同步補值（320.3／320.4） |
| `backend/src/main/java/com/steven/assets/service/TradingRadarExportService.java` | `LIVE_PREMIUM_HEADERS`／`LIVE_PREMIUM_FORMATS`；headers／rows／formats 三處<b>皆附加在尾端</b>（320.11／320.12） |
| `frontend/src/views/TradingRadarView.vue` | 主表格新增「折溢價(即時)」欄；收合列改名「折溢價(完成日)」（320.8／320.9） |
| `backend/src/test/java/com/steven/assets/service/ExcelExportPremiumOriginTest.java` | 擴充驗證 (g)：委派後兩支方法對 21 組輸入逐格相同 |
| `backend/src/test/java/com/steven/assets/service/TradingRadarUsStockEngineTest.java` | 320.5a：`never().getEtfNav(..., "美股")` 收窄為「不進 factor 路徑」，另加即時折溢價的正向釘子 |
| `backend/src/test/java/com/steven/assets/service/export/TradingRadarDualFormatTest.java` | 320.12a 三處同步 ＋ 驗證 (f)(h) 新增 3 條測試 |

### 驗證輸出

```
mvn -f backend/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
Tests run: 872, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
```

本任務相關：`EtfLivePremiumCalculatorTest` 8／`TradingRadarLivePremiumRuleIsolationTest` 3／
`TradingRadarDualFormatTest` 16 → 19／`ExcelExportPremiumOriginTest` 4 → 5／
`TradingRadarUsStockEngineTest` 16（條數不變，斷言改寫）。
`frontend/src/views/TradingRadarView.vue` 另以 `@vue/compiler-sfc` 的 `parse` ＋ `compileTemplate` 驗證無誤。

### 與原計畫的偏差（2 處，皆為任務檔漏列的既有事實，非放寬斷言）

1. **320.11 的 `rows` 用 `nullableText()` 而非任務檔字面寫的 `txt()`。**
   `txt()` 對缺漏欄回的是**空字串**（`TradingRadarExportService:337`），會產生 Excel 的
   STRING 空字串格與 JSON `""`；而驗證 (h) 明訂舊快照的末兩欄必須是 `CellType.BLANK` 與 JSON `null`。
   兩者衝突，取語意正確的一邊：改用同檔既有的 `nullableText()`（`:610`，缺漏回 `null`），
   與 `VALUATION_COMPONENT` 欄對舊快照的既有處置一致。數值欄的 `num()` 本來就回 `null`，照任務檔不變。

2. **320.12a 漏列了同一支測試檔的第二個方法。**
   除了 `表頭逐字與欄數()`，`估值逐分量雙格式與欄數鎖步()` 也有同一類「由 `size()` 往回推」的錨點
   ＋ 寫死的 172：`table.headers()).hasSize(172)`、兩條 `subList(size()-18, size())`、
   以及 `getRow(n).getLastCellNum()` 兩條 `(short) 172`。與 320.12a 同一性質（同步既有的欄數事實），
   已比照修正為扣掉 `LIVE_PREMIUM_COLS` 後反推、欄數改 174。修正後
   `containsExactly(VALUATION_COMPONENT_HEADERS_EXPECTED)` 的嚴格度不變。

### 建置與部署（由主 agent 執行，2026-08-13 00:04–00:15）

已 merge 進 main（feature `f8014302` → `--no-ff` merge `60a50614` → push origin/main），
故依「已 merge 就從 main 的 worktree build」規則，在 `/Users/steven/Project/asset-management-main`
（已確認在 `main`、工作區乾淨、`--ff-only` 追平 origin/main）執行：

```
cp /Users/steven/Project/asset-management/.env .                                   # worktree 無 .env
docker compose -p asset-management build --no-cache business-services frontend      # exit 0
docker compose -p asset-management up -d --no-deps --force-recreate business-services frontend
docker compose -p asset-management restart bff                                      # recreate 換 IP，BFF 需重啟
```

image SHA 確認已更新（非 stale）：business `4b1e736c…` → `f1cedaae…`、frontend `a06a3f64…` → `1f187d81…`。
運行中產物確認含本次變更：

```
docker exec asset-business-services sh -c 'unzip -l /app/app.jar | grep -i EtfLivePremium'
  → BOOT-INF/classes/com/steven/assets/service/EtfLivePremiumCalculator.class
docker exec asset-frontend sh -c 'grep -l "折溢價(即時)" /usr/share/nginx/html/assets/*.js'
  → /usr/share/nginx/html/assets/TradingRadarView-DWhesuwA.js（另確認含「折溢價(完成日)」）
```

六個容器皆 healthy；`curl -sI http://localhost/` → 200、`/actuator/health` → UP；
`docker logs asset-bff --since 5m | grep -cE "Connection refused|500 Server Error"` → **0**。

### 實機驗證（全部通過）

**API 逐檔比對**（business 容器內帶 `X-User-*` header 打 `/api/trading-radar`，32 檔）：

| 類別 | 結果 |
|---|---|
| 台股 ETF 15 檔 | `etfPremiumLivePct` 與 Redis `price:etfnav:台股:{code}` 的 `premiumDiscountPct` **逐位相同**，零不一致（0050 −0.35／0056 −0.25／00878 −0.12／006208 −0.41／00919 −0.56／009816 −0.59 等） |
| 美股 ETF 4 檔 | 皆有值（VOO −0.09／QQQ 0.47／VT 0.36／SGOV 0.03），以該列現價反推；同列 `etfPremiumPct`（dated）仍為 `null`，符合 Task 294.5 |
| 個股 13 檔 | 2330／NVDA／AMZN／AVGO／MSFT／2891／2885／2881／GOOGL／TSM／COIN 兩欄皆 `null`，未補 0 |

**匯出（`.xlsx` 與 `.json` 兩份）**：總欄數 **172 → 174**；`折溢價%` 仍在索引 **53**（未位移）；
`折溢價時點／折溢價來源／折溢價stale` 仍在索引 **134–136**（未位移）；
末三欄為 `殖利率缺漏原因／即時折溢價%／即時淨值時間`。兩種格式的欄名、欄序、值逐項一致。
落檔於 `/Users/steven/Project/SRPP/data/input/交易雷達_1_20260813.{xlsx,json}`。

**驗證段那條快照節流警語實測有效，且真的踩到了。** 第一次匯出時新兩欄全空：
排查發現該快照 `generatedAt` 為 `00:09:29`，而 business 容器啟動於 `00:11:13`（台北時間）——
快照比容器早 1 分 44 秒，是**舊 image 的產物**（其 `StockDecision` 只有 60 個 key，新版為 62），
且部署後首次 `GET` 落在 5 分鐘節流窗內故未落新快照。等過節流窗重打一次 `GET` 後，
新快照 `00:15:05` 的欄位數為 **62**、21 檔帶即時折溢價值，重新匯出即正確。
**這不是功能失效**——若無此警語，極易誤判為「匯出沒吃到新欄」而回頭改對的程式碼。

### 未由自動化涵蓋、需人工確認的部分

前端畫面本身未經瀏覽器實地點閱（該頁需 Google OAuth session，無法以 header 模擬）。
已驗證的是：build 產物含新舊兩個文案、欄位在 `.vue` 原始碼中位於「現價／漲跌」與
「MA5／20／60／240」之間、且該欄純 render 無任何前端計算。實際版面（欄寬、與相鄰欄的
視覺平衡、次要文字的淨值時點是否過長）建議由使用者開頁確認。
