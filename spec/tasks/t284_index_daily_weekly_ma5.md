# [t284] 股市大盤查詢頁的日線圖與匯出加上「週線（MA5）」

**對應 Requirements:** Requirement 18（股市大盤查詢：指數日線／當日分時 ＋ 台日韓人均 GDP 比較）、Requirement 45（股市大盤指數日線 Excel 匯出與排程自動匯出）
**前置任務:** 無（t270 的 `ExportDoc` 雙格式基礎已在 main）
**Liquibase changeset:** 無（不動 DB）

## 背景

「股市大盤查詢」頁（`/gdp-twse`，`frontend/src/views/GdpTwseView.vue`）最上方那張日線圖目前畫四條線：
收盤、月線 MA20、季線 MA60、年線 MA240。使用者要求**再加一條週線（MA5）**，且**匯出的檔案裡也要有週線價**。

現況兩處都沒有 MA5：

- 圖表：BFF `GET /api/bff/gdp-twse/index-daily` 只回 `dates`／`closes`／`ma20`／`ma60`／`ma240`。
- 匯出：`.xlsx`／`.json` 兩份都只有 **日期／開盤／最高／最低／收盤** 五欄，一欄計算欄都沒有。

「週線」在本專案既有語彙中一律指 **MA5＝台股慣例的 5 個交易日簡單移動平均**（非日曆週、非週 K）——
`TechnicalIndicatorService` 的 `ma5`、交易雷達匯出的「週線MA5」欄、股票分析走勢圖的「週線MA5」線都是這個定義。
本任務沿用，不另立第二種週線。

## 要做什麼

### A. BFF：`index-daily` 回傳加 `ma5`

- [x] 284.1 `bff/src/main/java/com/steven/assets/bff/gdptwse/GdpTwseBffController.java`
  的 `getIndexDaily(...)` 在 `body.put("ma20", movingAverage(closes, 20));` **之前**加一行
  `body.put("ma5", movingAverage(closes, 5));`。
  - **必須複用同一支 `movingAverage(List<BigDecimal>, int window)` private method**，不得另寫一份 5 日版本：
    該方法為 BigDecimal 滾動加總、`divide(window, 2, HALF_UP)`、視窗未滿填 `null`。四條 MA 只差 window。
  - 台股（`/api/twse-daily-index`）與海外指數（`/api/us-daily-index`）走的是同一段 `map(rows -> ...)`，
    故兩市場自動同格式，**不得**為某一市場加分支。
  - 回傳陣列長度與 `dates`／`closes` 相同；前 4 個交易日為 `null`。
  - **同步改該方法的 javadoc**：`GdpTwseBffController.java:150` 現寫「指數日線（近 N 年）+ MA20 / MA60 / MA240」，
    要補成 `+ MA5 / MA20 / MA60 / MA240`——漏改就是新的錯誤斷言。

### B. 前端：日線圖第五條線

檔案：`frontend/src/views/GdpTwseView.vue`

- [x] 284.2 新增 `const dailyMa5 = ref([])`，在 `fetchDailyData()` 內 `dailyMa5.value = (res.ma5 ?? []).map(num)`
      （比照既有 `dailyMa20`／`dailyMa60`／`dailyMa240` 的**兩處**：宣告在 `:263-265`、
      `fetchDailyData()` 賦值在 `:359-361`。第三處 `:706-708` 屬 284.4）。
- [x] 284.3 `cardTitle` 的日線分支由
      `` `${marketLabel.value}每日收盤（近 10 年，含月線/季線/年線）` `` 改為
      `` `${marketLabel.value}每日收盤（近 10 年，含週線/月線/季線/年線）` ``。
      「當日」分支（`${marketLabel.value}當日走勢…`）**不動**。
- [x] 284.4 `dailyChartOption` 內：
  - 加 `const ma5Data = intraday ? xData.map(() => lastOf(dailyMa5.value)) : dailyMa5.value`
    （與既有三條**同一寫法**：當日模式取日線最新 MA5 值鋪成水平參考線）。
  - `legend.data` 由 `['收盤', '月線 (MA20)', '季線 (MA60)', '年線 (MA240)']`
    改為 `['收盤', '週線 (MA5)', '月線 (MA20)', '季線 (MA60)', '年線 (MA240)']`（週線插在收盤之後、月線之前，
    順序＝視窗由短到長）。
  - `legend.formatter` 內的 `map` 加一筆 `'週線 (MA5)': ma5Data`——**漏了這筆 legend 會顯示 `-`**。
  - `series` 陣列加一條，緊接在「收盤」之後：
    ```js
    {
      name: '週線 (MA5)',
      type: 'line',
      data: ma5Data,
      showSymbol: false,
      smooth: !intraday,
      lineStyle: { width: 1.5, color: '#8b5cf6', type: intraday ? 'dashed' : 'solid' },
      itemStyle: { color: '#8b5cf6' }
    }
    ```
    - **線色 `#8b5cf6`（紫）**：既有四者為收盤 `#1f2937`／MA20 `#f59e0b`／MA60 `#10b981`／MA240 `#3b82f6`，
      **一律不得更動**；紫色與四者皆可辨。
  - **最高／最低 markPoint 仍只掛在「收盤」那條 series 上**，不因多一條均線而改變；
    `maxMinMarkPoints(closeData, ...)` 的輸入不得改成 MA5。
- [x] 284.5 匯出對話框「輸出內容」說明文字
      `欄位為 <b>日期／開盤／最高／最低／收盤</b>，依日期遞增；當日該欄無資料則留空。`
      改為 `欄位為 <b>日期／開盤／最高／最低／收盤／週線MA5</b>，…`（週線那句補「週線MA5 為 5 個交易日收盤均價」）。
- [x] 284.6 排程卡下方 `.schedule-hint` 的
      `（主檔名相同、只差副檔名；欄位為日期／開盤／最高／最低／收盤，內容同上方「匯出 Excel」）`
      同步補上「／週線MA5」——**這兩處文案漏改就等於文件對使用者說謊**，且是本專案反覆出現的漂移型錯誤。

### C. 後端：匯出加第六欄「週線MA5」

檔案：`backend/src/main/java/com/steven/assets/service/ExcelExportService.java`（`indexDailySheet` 與 `findIndexDaily`）

- [x] 284.7 表頭由 `List.of("日期", "開盤", "最高", "最低", "收盤")`
      改為 `List.of("日期", "開盤", "最高", "最低", "收盤", "週線MA5")`——**新欄一律附加在最末**。
      既有五欄的欄索引 0–4 不得位移：`DualFormatSingleTableExportTest` 有寫死的欄索引斷言
      （`getCell(1)`／`getCell(3)` 為 null、`getCell(4)` ＝ 23200.00）。
      **插在中間並非做不到**——t281 就是把「週線MA5」插在雷達的 `MA20` 之前，再維護一組欄索引位移函式
      （`c -> c < 11 ? c : (c < 18 ? c + 1 : c + 15)`）對改動前的 golden 比對——但那是本次不必要的成本：
      附加在最末，284.14 的比對用 identity 映射即可，既有斷言的欄索引也一格都不用改。
      **同步改 javadoc（四處，漏一處就留下一句假話）**：`ExcelExportService.java:511`「單張工作表、
      日期／開高低收**五欄**」、`:527`「日期／開盤／最高／最低／收盤**五欄**」、
      `:529`「**四個價格欄**直接讀 DB 既有 OHLC 欄位」，以及 **`MacroHistoryController.java:122`**
      「指數日線區間匯出成單一 .xlsx（日期／開盤／最高／最低／收盤**五欄**）」——
      全部更新為「六欄／第六欄為計算欄（週線MA5）」。
      （查法：`grep -ran "五欄\|開盤／最高／最低／收盤" backend/src/main/java`，本次會失效的正是這四處。）
- [x] 284.8 `formats` 對應加第六個 `ExportDoc.Format.NUM2`（**不是 NUM4**）：
      MA5 定義上只有 2 位小數（`divide(5, 2, HALF_UP)`），用 NUM4 會多印兩個恆為 0 的位數、
      謊稱精度。交易雷達匯出的「週線MA5」欄同樣是 NUM2（既有先例）。
      `ExportDoc.Table` 建構子會驗 `columnFormats.size() == headers.size()`，漏加會直接擲 `IllegalArgumentException`。
- [x] 284.9 **MA5 計算**：以區間內（含回看列）的 `close` 依日期遞增計算「該日含當日往前 5 個交易日的簡單移動平均」，
      **BigDecimal 精確加總後 `divide(5, 2, RoundingMode.HALF_UP)`**，前 4 列（視窗未滿）為 `null`。
  - **精度與算法必須與 BFF `GdpTwseBffController.movingAverage(closes, 5)` 逐位相同**
    （同為 BigDecimal 精確加總、無中間捨入、`divide(window, 2, HALF_UP)`）——同一指數同一日期，
    圖上看到的週線值與檔案裡的「週線MA5」必須一模一樣。
    ⚠️ **不得改用 `double` 累加**（`TechnicalIndicatorService.simpleMa` 走的是 double／那是股票路徑的既有實作），
    double 加法不可結合，會在 `x.xx5` 邊界翻面，讓圖與檔案偶爾差 0.01。
  - **不得**呼叫 `TechnicalIndicatorService`。先把兩件容易寫錯的事講清楚：該服務的大盤分支
    `computeAllForTaiex()` 讀的**也是** `twse_index_daily_history`（所以「資料表不同」不是理由），
    而且它**有**逐日序列 API（`indicatorSeries(...)` 回 `List<IndicatorPoint>`，`IndicatorPoint.ma5` 逐日都有，
    經 `GET /api/market-data/indicators/series` 對外）——所以「只回單點」也不是理由。真正的三條理由是：
    (a) **覆蓋範圍不夠**：`TechnicalIndicatorService.isTaiex(code, market)` 只認 `"0000"`＋`"台股"`，
        本頁另外 8 個海外指數（DJI/SPX/IXIC/SOX/FTSE/DAX/KOSPI/N225）在該服務**沒有任何分支**，
        走 `stockSeriesAsc` 只會去查 `stock_price_history` 而得到空序列；
    (b) 它會併入 Redis 今日盤中即時點位，本匯出一律只用已落地的日線收盤；
    (c) 它的 `simpleMa`／`taiexSimpleMa`／`maAt` 都是 `double` 累加，與 284.9 要求的 BigDecimal 精度不同
        （對 `numeric(14,4)` 的海外指數在 `.xx500` 邊界可能差 0.01）。
- [x] 284.10 **回看視窗**：`findIndexDaily(market, start, end)` 的查詢起點改為 `start.minusDays(30)`，
      算完 MA5 後**只輸出 `date >= start` 的列**，回看列不得出現在檔案中。
  - 理由：只用區間內收盤時，檔案前 4 列的週線必為空——使用者會讀成 bug。
  - 30 個日曆天足以涵蓋含農曆年連假在內的 4 個交易日（連假最長約 9 天 ＋ 前後週末）。
  - **兩張來源表都要**：`TWSE` 走 `twseIndexHistRepo.findByTradingDateBetweenOrderByTradingDateAsc`、
    其餘走 `usIndexHistRepo.findByIndexCodeAndTradingDateBetweenOrderByTradingDateAsc`，
    兩條路徑都要吃到回看起點（把回看寫在共用的呼叫端，不要只改其中一條）。
  - DB 本身湊不滿 5 個交易日時（該指數歷史最前端）該格留 `null`，**不補前值、不以不足視窗的平均充數**
    ——同既有 open/high/low 的處理。`omitNullCells=true` 語意不變：該格**根本不建**（非 BLANK 格）。
- [x] 284.11 `ExportDoc.Sheet(..., headers.size())` 的 `autoSizeColumns` 自動跟著變 6（既有寫法已是 `headers.size()`，
      確認不要改成寫死常數）。
- [x] 284.12 **不得**改動 `exportIndexDaily`／`indexDailyDoc` 的簽章與 `IndexExportScheduleService` 的呼叫方式：
      排程與手動匯出走同一支 `indexDailyDoc(market, start, end)`，兩條途徑自動同時拿到新欄
      （CLAUDE.md「同義欄位、同一 business service API」）。JSON 那一份由同一份 headers 產生
      key `"週線MA5"`，**不需要**也**不得**另寫 JSON 分支。

### D. 測試

檔案：`backend/src/test/java/com/steven/assets/service/export/DualFormatSingleTableExportTest.java`

- [x] 284.13 **golden 基準重產（順序不可顛倒）**：
  1. 先把現行 `backend/src/test/resources/golden/index_twse.xlsx` **複製**為 `index_twse_pre_t284.xlsx`
     （兩個檔名都要存在，**不是 `git mv`**），並 `git add` 之。
  2. 實作完 284.7–284.11 後才重產 `index_twse.xlsx`。repo 內沒有 golden 產生器，作法是暫時在該測試類別加一支
     拋棄式測試，**stub 與 fixture 必須與該檔現行的完全同一份**（`stubAll()` ＋ `twseIndex()`），寫出後刪掉：
     ```java
     java.nio.file.Files.write(java.nio.file.Path.of("src/test/resources/golden/index_twse.xlsx"),
             service.exportIndexDaily("TWSE", D1, D2));
     ```
     （工作目錄為 `backend/`。）
  3. **不得順手改 `twseIndex()` fixture**（現行為 D1=2026-07-30 全欄有值、D2=2026-07-31 只有收盤 23200.00，
     共 2 列）——改了會讓 284.14 的「既有欄未變」比對失去意義。2 列不足 5 筆，故重產後
     **MA5 那格不存在**（`omitNullCells`），golden 的唯一差異是**表頭列多一格「週線MA5」**。
- [x] 284.14 新增測試「插欄後既有五欄逐格未變」：以 `index_twse_pre_t284` 為期望值，
      對 `service.exportIndexDaily("TWSE", D1, D2)` 的產出做**欄索引 identity 映射**逐格比對
      （值、型別、`dataFormat`、粗體、字級），並斷言新欄只出現在表頭列。
      ⚠️ **只重產 golden 不做這條比對是不夠的**——那樣「新增了欄」與「順手改壞既有欄」無法分辨。
      ⚠️ **不可沿用同檔的 `assertSameWorkbook`**：它會斷言 `getLastCellNum()` 相等，而表頭列是 5 vs 6，必紅。
      可比照 `TradingRadarDualFormatTest.assertSameMapped`（該檔 `:333`，`private static`，
      跨測試類別用不到，**需複製一份**到 `DualFormatSingleTableExportTest`），映射函式傳 `c -> c`。
- [x] 284.15 新增測試「MA5 值正確且與 BFF 同定義」：另備一組**獨立 fixture**（不與 golden 比對）
      至少 7 個交易日的 TWSE 收盤序列，斷言：
  ⚠️ **本條 fixture 的日期一律 `>= 傳入的 start`**（模擬「DB 歷史最前端湊不滿 5 筆」的情境）。
  若把前幾筆放在 `start` 之前，284.10 的回看過濾會把它們濾掉、第一列就有值，本條的「前 4 列為 null」必紅。
  **它與 284.16 的「含 `start` 之前列」fixture 是兩組不同資料，不得共用。**
  - 前 4 列該格**不存在**（`getCell(5) == null`）、第 5 列起有值；
  - 第 5 列的值 ＝ 前 5 個收盤的算術平均四捨五入到 2 位（在測試裡以 `BigDecimal` 手算期望值，
    **不得**呼叫被測程式自己算期望值）；
  - JSON 那一份（`JsonDocRenderer.render(service.indexDailyDoc(...))`）同列的 `"週線MA5"`
    為相同數值、前 4 列為 `null`。
- [x] 284.16 新增測試「回看列不得出現在檔案中」：stub repo 回傳含 `start` 之前日期的列，
      斷言產出的第一列日期 ＝ `start`（不是回看起點），且該列 MA5 **有值**
      （證明回看有生效、不是把回看列直接印出來）。
      ⚠️ 現行 stub 是 `findByTradingDateBetweenOrderByTradingDateAsc(any(), any())`，
      回看與否從回傳值看不出來——本條須改用 `ArgumentCaptor` 或具體日期 matcher 斷言
      **實際傳入 repo 的起點 ＝ `start.minusDays(30)`**。
- [x] 284.17 **BFF 側把 MA 定義釘進測試**（本次唯一的跨模組防漂移代償，見下方「架構決策」）：
  - `GdpTwseBffController.movingAverage` 由 `private` 改為 **package-private**（加註
    `/** package-private：供同 package 的單元測試釘住 MA 定義，勿改回 private */`）。
  - 在 `bff/src/test/java/com/steven/assets/bff/gdptwse/` 下**新增一支 JUnit 5 單元測試類**
    （類名依專案慣例＝受測類別名 ＋ `…Test` 後綴，與受測類別同 package 才叫得到 package-private 方法；
    本檔刻意不寫死類名——`scripts/spec-check.sh` 的 B5 會把「spec 提到但全樹不存在的測試類」判為 BLOCK，
    而實作前它本來就還不存在），
    對 `movingAverage(closes, 5)` 以**測試內手算的 BigDecimal 期望值**斷言三件事：
    (1) 前 4 筆為 `null`；(2) 第 5 筆起 ＝ 該視窗 5 筆的精確平均 `setScale(2, HALF_UP)`；
    (3) 回傳長度 ＝ 輸入長度。至少含一組會觸發 HALF_UP 進位的收盤值（例如末位湊出 `x.xx5`），
    否則測不到精度那一條。
  - ⚠️ **不是要在 BFF 重驗 backend 的值**（跨 Maven 專案做不到），而是讓「視窗／null／2 位 HALF_UP」
    這三個約束在兩邊各自有一條會紅的測試。
  - ⚠️ `movingAverage` 是 **instance method**（`GdpTwseBffController` 為 `@RequiredArgsConstructor`、
    唯一欄位是 `WebClient businessServicesClient`）。測試取實例的寫法是 `new GdpTwseBffController(null)`
    ——該方法完全不碰 `businessServicesClient`，傳 `null` 安全；**不可照抄 `SchedulePublicBffControllerTest`
    的無參數建構子寫法**（那支 controller 沒有欄位），會編譯不過。或一併把 `movingAverage` 改成
    `static` package-private，測試就不必建實例。
  - bff 模組已有 `spring-boot-starter-test`（JUnit 5 ＋ AssertJ），surefire 會自動撿到；
    `gdptwse` 測試子目錄尚不存在，新建即可。bff 測試不用 Mockito，故驗證段的 bff 測試指令
    **不需要**帶 `-DextraArgLine=-Dnet.bytebuddy.experimental=true`。

### 架構決策（實作者必讀，不要「順手統一」）

本任務**刻意**讓 MA5 在 BFF（圖表）與 business（匯出）各算一次。這是有意識借下的債，理由與被放棄的選項：

- business 雖有逐日 MA5 的既有實作（`TechnicalIndicatorService.indicatorSeries` → `IndicatorPoint.ma5`），
  但 `isTaiex` 只認 `0000`＋`台股`，本頁另外 8 個海外指數在該服務完全沒有分支，**不是現成解**。
- 最便宜的單一實作路線是「新增一支 business 序列端點回 `{dates, closes, ma5, ma20, ma60, ma240}`、
  BFF 改 relay」。放棄理由是代價與收益不成比例：要動一條使用者天天在看、目前正常運作的圖表路徑，
  且 CLAUDE.md 明文把「預先計算」派給 BFF；換得的只有「少一份實作」，畫面一模一樣。
- **實作者不得自行改採該路線**（那會讓本任務的爆炸半徑從「加一條線一個欄」擴大到既有圖表資料流）。
  日後兩處若真的出現不一致，正解才是回頭做它。

### E. 不做什麼（範圍界線）

- 不動 `us_index_daily_history`／`twse_index_daily_history` 的 schema，不新增 Liquibase changeset。
- 不動「當日」分時的 API 契約（`index-intraday` 不加 MA 欄位；當日模式的 MA5 水平線由前端取日線最新值鋪成，
  與既有三條同一做法）。
- 不動股票分析走勢圖／交易雷達／觀察清單的任何 MA5（那些走 `TechnicalIndicatorService`，是另一條資料流）。
- **不新增** `SchedulePublicBffController` 的 `JOBS` 項目（沒有新排程），但**該檔的文案要改一句**：
  「大盤指數匯出／每日匯出排程檢查」那筆的說明含「…主檔名相同，**欄位為開高低收**）…」，
  須改為「欄位為開高低收＋週線MA5」——那是排程一覽頁的使用者可見文案，與 284.5／284.6 同性質，
  漏改一樣是對使用者說謊。

## 驗證

```bash
# 1. 後端單元測試（含 golden 零回歸、MA5 值、回看視窗）
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test \
  -Dtest='DualFormatSingleTableExportTest,SingleTableScheduleServiceDualFormatTest' \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true

# 2. 全量後端測試（確認沒打壞其餘 golden）
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true

# 3. BFF 測試（含 284.17 新增的 movingAverage 定義測試）＋ 打包
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml package -DskipTests

# 4. 前端建置
/Users/steven/.nvm/versions/node/v22.21.0/bin/node \
  /Users/steven/.nvm/versions/node/v22.21.0/bin/npm --prefix frontend run build
```

**跑起來真的有這個功能**（本專案沒有 dev server，「改好」＝ image rebuild ＋ container recreate；
JVM 服務一律 `--no-cache`，見 CLAUDE.md 與 `.claude/skills/run-stack`）：

⚠️ compose 服務名為 **`bff`**（`container_name: asset-bff`），**不是 `bff-service`**——寫錯會直接
`no such service` 中止。三個服務都要重建：MA5 的三段改動分別落在 business（匯出欄）、bff（`ma5` 欄位）、
frontend（第五條線）。

```bash
cd /Users/steven/Project/asset-management && docker compose -p asset-management build --no-cache business-services bff frontend
```

```bash
cd /Users/steven/Project/asset-management && docker compose -p asset-management up -d --no-deps --force-recreate business-services bff frontend
```

⚠️ **recreate business 之後要 restart bff**（換 IP，BFF 握舊 IP 會回 500 且 ≥3 分鐘不自癒）：
`docker compose -p asset-management restart bff`。

實機驗收（缺一不可）：

1. **BFF 的 `ma5` 不能用裸 curl 驗**：host 只開了 bff 的 8080 與 frontend 的 80（business／ext 沒有
   host port 對映），且 `bff/.../config/SecurityConfig.java` 對 `/api/bff/**` 一律
   `.anyExchange().authenticated()`——沒帶登入 session 的 curl 只會拿到 OAuth 轉址，不是 JSON。
   改為兩段驗證：
   - 上游資料（在 business 容器內打，因為它沒有 host port 對映；該端點是**全域公開行情**、
     表無 `owner_user_id`，故**不需要**帶 `X-User-*`）：
     `docker exec asset-business-services curl -s 'http://localhost:8080/api/twse-daily-index?from=2026-06-01&to=2026-08-02' | python3 -c "import json,sys; r=json.load(sys.stdin); print(len(r), r[-5:])"`
     → 取末 5 筆 `closePoint` 手算 MA5，作為下一步比對的期望值。
   - BFF 那一段以**登入後的瀏覽器**驗（見第 2 步 legend 顯示的「週線 (MA5)」值＝上面手算的值）。
2. 瀏覽 `http://localhost/gdp-twse`（已登入）：日線圖有五條線、legend 第二項為「週線 (MA5)」並顯示最新值
   （＝第 1 步手算值）、標題為「…（近 10 年，含週線/月線/季線/年線）」；切「當日」時週線變水平虛線。
3. 按「匯出 Excel」選任一區間 → 開啟檔案確認第六欄為「週線MA5」，**第一列就有值**（不是空的），
   且該值與圖上同日期的週線相同。
4. 排程卡按「立即匯出到目錄」→ 產出的 `.xlsx` 與 `.json` 兩份都含「週線MA5」
   （`.json` 以 `python3 -m json.tool` 檢視 `sheets[0].tables[0].rows[0]` 應有 `"週線MA5"` key）。

## 完成報告

### 實際改動檔案

| 檔案 | 內容 |
|---|---|
| `bff/.../gdptwse/GdpTwseBffController.java` | `index-daily` 加 `ma5`；`movingAverage` 由 private 改 package-private ＋ 兩處 javadoc |
| `bff/.../schedulelist/SchedulePublicBffController.java` | 排程一覽文案「欄位為開高低收」→「＋週線MA5」（未新增 `JOBS`） |
| `bff/src/test/.../gdptwse/GdpTwseBffControllerMaTest.java` | 新增：釘住 MA 定義（視窗／null／2 位 HALF_UP／4 位小數輸入） |
| `backend/.../service/ExcelExportService.java` | 第六欄「週線MA5」＋ `MA5_WINDOW`／`MA5_LOOKBACK_DAYS`／`ma5At`＋回看過濾＋三處 javadoc |
| `backend/.../controller/MacroHistoryController.java` | 僅 javadoc（五欄→六欄） |
| `backend/src/test/.../export/DualFormatSingleTableExportTest.java` | 新增 `WeeklyMa5` 三條測試 ＋ `assertSameMapped` helper |
| `backend/src/test/resources/golden/index_twse.xlsx` | 重產（表頭多一格；資料列因 fixture 只有 2 筆而無 MA5 格） |
| `backend/src/test/resources/golden/index_twse_pre_t284.xlsx` | 新增：改動前基準，供 identity 映射比對 |
| `frontend/src/views/GdpTwseView.vue` | 第五條線「週線 (MA5)」＋標題＋legend＋當日水平線＋兩處文案 |

### 測試結果

- backend：`528 tests, 0 failures`（含 `WeeklyMa5` 三條、`ZeroRegression` 五條、其餘 golden 未受影響）
- bff：`24 tests, 0 failures`（新增三條）
- 前端：本 worktree 無 `node_modules`，vite build 由 docker frontend image 建置時執行，成功

### 部署與實機驗證（2026-08-02）

`docker compose -p asset-management build --no-cache business-services bff frontend` → `up -d --force-recreate`
→ `restart bff`（business 換 IP 後 BFF 需重啟）。三個映像的 Created 時間戳都是本次（分別 2 分／49 秒／34 秒前）。

1. **上游資料**（business 容器內，全域行情不需 `X-User-*`）：`/api/twse-daily-index?from=2026-07-01&to=2026-08-02`
   回 22 筆；末 5 筆收盤 43634.19／41603.36／40039.18／39933.30／43119.75 → 手算 MA5 ＝ **41665.96**。
2. **匯出檔**（同一容器內打 `/api/index-daily/export`，取回 4787 bytes 的 xlsx 解 OOXML 檢視）：
   - 表頭第六欄為 **`週線MA5`**；
   - **第一列（2026-07-01）即有值 45794.36**——回看 30 天生效，不是空白；
   - 共 23 列（表頭 ＋ 22 筆），**回看列未外洩**；
   - 末列（2026-07-31）週線MA5 ＝ **41665.96**，與第 1 步手算逐位相同。
3. **BFF**：`/api/bff/**` 一律 `.anyExchange().authenticated()`，裸 curl 只會拿到 OAuth 轉址，故改以部署產物驗證——
   運行中的 `asset-bff` 容器內 `GdpTwseBffController.class` 常數池含 `ma5`／`ma20`／`ma60`／`ma240`
   （注意：`strings` 預設最短 4 字元會漏掉 `ma5`，須用 `strings -n 3`，否則會誤判成「沒部署到」）。
4. **前端**：運行中的 `asset-frontend` 容器內 `assets/GdpTwseView-*.js` 同時含
   `週線 (MA5)` 與 `含週線/月線/季線/年線`。
5. **畫面目視**：`/gdp-twse` 走 Google OAuth，代理登入不在可執行範圍——五條線與 legend 值的目視確認留給使用者。

### 審查紀錄

- **`/spec-review`（實作前）**：三輪獨立 `spec-auditor`。R1 1 critical／3 major（compose 服務名寫成 `bff-service`、
  驗收裸打不存在的 8081 且未考慮 OAuth、宣稱做不到的機械判準、「無共同計算落點」與程式碼不符）；
  R2 4 major（「第一次兩份實作」不實、javadoc 漏第四處、被放棄選項的理由不成立、例外未寫進規範）；
  R3 2 major（「第一次」仍不實、任務檔殘留被推翻的舊理由）。全部修完，`scripts/spec-check.sh` 0 BLOCK。
  期間 origin/main 占用 283 → 整體避讓為 284。
- **`arch-auditor`（實作後）**：0 critical／2 major，兩條同源——「同義欄位 → 同一支 business service API」。
  它的關鍵反駁成立：規範要的是 **BFF 呼叫同一支 business API**，不是共用程式碼，故原本
  「跨 Maven 專案無法共用」的論證答非所問；且 backend 內部 `ExcelExportService` 與
  `TechnicalIndicatorService` 同模組，根本沒有技術阻礙。處置：
  1. 改正 design.md 的錯誤前提，把真正的阻礙寫成 **live 併入語意 ＋ 覆蓋率**（換過去會讓同一張圖的
     五條線出現兩種口徑）；
  2. 實測＋證明「兩路徑對台股 `numeric(12,2)` 收盤逐位相同」（50 萬組樣本 0 次不一致），
     確認差異只來自盤中 live 併入、不是精度漂移；
  3. 依 auditor 的選項 (c) 把此例外**明文寫進 `spec/steering/structure.md` §3.2**（規範文字才是判準來源），
     並註明「不得被引用來新增第三份實作」。
  **未收斂**成單一實作——那需要先決定「MA 要不要併 live」再統一 `TechnicalIndicatorService` 的算術路徑，
  會動到交易雷達／觀察清單／走勢圖與其 golden，屬獨立任務。

### 與計畫的偏差

- **無功能偏差**，284.1–284.17 全數照做。
- **編號避讓**：本任務原編 283，實作前 origin/main 推進並占用 283（交易雷達頁首匯出雙格式），整體改為 **284**。
- 284.17 的 BFF 測試類名刻意不寫進 spec（`scripts/spec-check.sh` 的 B5 會把「spec 提到但全樹不存在的測試類」
  判為 BLOCK，而實作前它本來就還不存在），實際落檔為 `GdpTwseBffControllerMaTest`。
- 284.15 的「至少含一組會觸發 HALF_UP 進位的收盤值」：2 位小數的收盤除以 5 恆為 3 位小數且末位為偶數
  （`k/500 = 2k/1000`），故 `x.xxx5` 平手在 TWSE 精度下**數學上不可能**；改以「100.028 → 100.03」的進位案例
  涵蓋，並在測試註解記下此推導。海外指數的 4 位小數精度另立一條測試。
