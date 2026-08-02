# [t271] 資產總覽與交易雷達改走 `ExportDoc` 雙格式，交易日曆改為一律兩份

**對應 Requirements:** Requirement 55（十個自動匯出點的檔案一律同時產出 `.json` 與 `.xlsx` 兩份，主檔名完全相同、只差副檔名；兩份必須來自同一次資料查詢；既有 Excel 的內容不得減損，判準是「逐列逐格 ＋ 儲存格樣式」；交易日曆的 json／excel 二選一設定停用）
**前置任務:** t269（block list 地基就緒）、t270（五個單表匯出已接上，模型已被實戰驗證）
**後續任務:** t272（警示觸發／爬蟲公開資訊加 Excel）
**Liquibase changeset:** 無（`trading_calendar_export_schedule.format` 欄位**保留不刪**，本需求全程零 schema 變更）

## 背景

這三個匯出點是十個裡結構最複雜的，故獨立成一支任務：

| 匯出點 | 結構 | 難點 |
|---|---|---|
| 資產總覽（`ExportScheduleService`） | 「當前即時資產」分頁（**多區塊**）＋ **N 個「每檔持股過去一年股價」分頁** | 分頁數不固定；股票表 21 欄；吃 Redis 即時價；版面有同列多鍵值、無表頭彙總列、早退分支三列 |
| 交易雷達（`TradingRadarExportScheduleService`） | 三分頁：快照索引（含一列**條件樣式**的提示文字）／大盤總覽／個股決策 | 資料來源是 Redis 裡的 `JsonNode`；四支取值輔助把型別壓成字串；section 是 **12pt** 不是 13pt |
| 交易日曆（`TradingCalendarExportScheduleService`） | 單分頁，但**目前讓使用者在 json／excel 之間二選一** | 既有 JSON 是對外契約、形狀不得變；要拆掉一整條 format 設定鏈（前端 radio → BFF → DTO → service → DB 欄位） |

**「兩份必須來自同一次資料查詢」在這裡不是理論問題。** 資產總覽吃 `StockPriceService.getLiveAssets()`
（Redis 即時價）、交易雷達吃 Redis 快照，兩次查詢之間價格會變。若 JSON 與 Excel 各查一次，使用者會拿到
兩份數字對不起來的檔案，而且無從得知哪一份才是對的。

## 要做什麼

### 271.1 資產總覽（`ExcelExportService.buildLiveWorkbook`）

- [x] 271.1 `writeLiveAssetsSheet(wb, st, live, latest)` 改寫成 `ExportDoc.Sheet liveAssetsSheet(live, latest)`，
      `writeStockPriceHistorySheets(wb, st, latest)` 改寫成 `List<ExportDoc.Sheet> stockPriceHistorySheets(latest)`，
      `buildLiveWorkbook()` 改為 `ExportDoc liveAssetsDoc()`。公開方法 `exportLiveAssets()`／
      `exportLiveAssetsForOwner(Long)` **維持既有簽章與回傳 `byte[]`**，內部改為
      `excelDocRenderer.render(liveAssetsDoc())`；**另外**各加一支回傳 `ExportDoc` 的 public 版本供排程使用。

- [x] 271.1.1 **「當前即時資產」分頁的 block list（逐格對應既有版面，行號為 `ExcelExportService.java`）。**
      實作前**必讀 `:646-810` 全段**再動手；下表是對應關係，不是版面定義的替代品。

  | 既有列（行號） | 既有寫法 | block |
  |---|---|---|
  | r0（`:651-652`） | `cell(title, 0, "當前即時資產", st.section)` | `Line("當前即時資產", SECTION_13)` |
  | r1（`:654-656`） | `cell(exp,0,"匯出時間",st.head)`＋`cell(exp,1,值,null)` | `KvRow([Kv("匯出時間", LocalDateTime, TIMESTAMP)])` |
  | r2（`:667-673`） | **同一列六格**：`cell(h,0,"基準快照日期",st.head)`／`cell(h,1,ISO 日期,null)`／`cell(h,2,"美元匯率",st.head)`／`cell(h,3,rate,st.num4)`／`cell(h,4,"即時總資產",st.head)`／`cell(h,5,total,st.money)` | `KvRow([Kv("基準快照日期",LocalDate,DATE), Kv("美元匯率",BigDecimal,NUM4), Kv("即時總資產",BigDecimal,MONEY)])` |
  | r3（`:675`） | `r++; // 空行` | `Blank()` |
  | r4 起（`:678-683`） | `cell(secSum,0,"即時彙總",st.section)` ＋ 四次 `summaryRow`（`:190-195`：`cell(row,0,label,st.head)`、`cell(row,1,value,st.money)`，**無表頭列**） | `Table(name="即時彙總", nameStyle=SECTION_13, headers=["項目","金額"], showHeader=false, labelFirstColumn=true, columnFormats=[TEXT,MONEY], rows=4 列)` |
  | （`:685`） | `r++` | `Blank()` |
  | （`:688-705`） | `cell(dh,0,"銀行存款",st.section)` ＋ 表頭列 ＋ 資料列 | `Table("銀行存款", SECTION_13, 6 欄, showHeader=true, labelFirstColumn=false, …)` |
  | （`:707`） | `r++` | `Blank()` |
  | （`:710-723`） | `cell(fh,0,"基金",st.section)` ＋ 表頭列 ＋ 資料列 | `Table("基金", SECTION_13, 4 欄, showHeader=true, …)` |
  | （`:725`） | `r++` | `Blank()` |
  | （`:733-…`） | `cell(sh,0,"股票（即時）",st.section)` ＋ 21 欄表頭 ＋ 資料列 | `Table("股票（即時）", SECTION_13, 21 欄, showHeader=true, …)` |
  | 結尾（`:809`） | `for (int i = 0; i < 21; i++) autoSizeColumn(i)` | `Sheet.autoSizeColumns = 21` |

  **本分頁的四個 `Table` 一律 `omitNullCells = false`**：既有這幾區逐格都有呼叫 `cell(...)`
  （null 時建 BLANK 格），與油價金價／匯率／指數那三份「值為 null 就不呼叫 `cell()`」不同。
  另外 `:699`／`:719`／`:764`／`:785` 四處既有刻意寫空字串 `""` 而非 null，見 271.1.5。

  **`showHeader=false` 與 `labelFirstColumn=true` 這兩個旗標是為「即時彙總」而存在的**（t269 269.1 已定義）：
  該區既有**沒有**表頭列、且第 0 欄標籤走 `st.head`。用一般的 `Table` 會多出一列表頭、且標籤欄少了 head 樣式。
  **`KvRow` 則是為 r1／r2 而存在**：`Kv` 逐個成列會把 r2 一列拆成三列，`Table` 會把它變成兩列，兩者都是版面變動。

- [x] 271.1.2 **早退分支（`:658-663`）輸出三列，不是一列。** 既有：
      `Line("當前即時資產", SECTION_13)`（r0，`:652`）→ `KvRow([Kv("匯出時間", …, TIMESTAMP)])`（r1，`:655-656`）
      → `cell(none, 0, "尚無資產快照", null)`（r2，**無樣式**）→ `for (int i = 0; i < 6; i++) autoSizeColumn(i)`（`:661`）→ return。
      故 doc 為：`blocks = [Line("當前即時資產", SECTION_13), KvRow([…匯出時間…]), Line("尚無資產快照", PLAIN)]`、
      `autoSizeColumns = 6`、**無 tables**。
      **「尚無資產快照」那一列必須是 `LineStyle.PLAIN`**（既有樣式參數是 `null`）——寫成 `SECTION_13`
      會把它變成粗體 13pt。**不得**退化成只寫一列，也不得擲例外。
- [x] 271.1.3 **兩個 per-`(code|market)` 快取都必須保留**：`indicatorCache`（技術指標）與
      `navCache`（ETF 淨值，`:761`）。同一支股票被多個券商持有時只算／只讀一次；弄丟的話
      `TechnicalIndicatorService.computeAll()` 與 `priceQueryService` 會被呼叫 N 倍，是實質效能回歸。
      **`navCache` 必須維持 `containsKey` 判定而非改成 `computeIfAbsent`**（`:799-803` 的既有寫法）——
      `computeIfAbsent` 不快取 null，查無淨值的個股會逐列重讀 Redis。
- [x] 271.1.3.1 **`writeStockPriceHistorySheets` 的三段既有邏輯必須整段保留**（`:840` 起）：
      (1) `s == null` 直接回空 list；(2) `code == null || code.isBlank()` 跳過；
      (3) `LinkedHashSet<String> seen` 以 `code + "|" + market` 去重（**同檔多券商只出一張分頁**），
      並保留總表由上而下的順序。這是**持股層級**的去重，與 271.1.4 的**分頁名**去重是兩件事，兩者都要。
- [x] 271.1.3.2 **每張個股股價分頁的 doc 形狀**：單一 `Table`（`name = null`、`showHeader = true`、
      `labelFirstColumn = false`、**`omitNullCells = false`**），headers 為
      `日期`／`開盤價`／`最高價`／`最低價`／`收盤價`／`成交量`，`Sheet.autoSizeColumns = 6`
      （既有 `ExcelExportService.java:864`）。
      **`omitNullCells` 必須是 `false`**：該分頁六欄逐格都是無條件 `cell(...)`，null 時建 BLANK 格。
      **不要被既有 Javadoc 的「開高低與成交量在 DB 可空…該格留白不補值」誤導**——「留白」在這裡
      指的是 BLANK 格，不是「不建格」，與油價金價／匯率／指數那三份不同。
- [x] 271.1.4 **「每檔持股過去一年股價」分頁的去重規則**：既有 `uniqueStockSheetName(wb, code, market)`
      （`:871-882`）依賴 `Workbook` 查名。改成 doc 之後沒有 workbook 可問 → 改為自備一個
      **大小寫不敏感**的 `Set<String>`（`new TreeSet<>(String.CASE_INSENSITIVE_ORDER)`，對齊 POI
      `getSheet()` 的 `equalsIgnoreCase` 語意）。既有規則逐條照抄：
      1. `base = WorkbookUtil.createSafeSheetName(code)`，未被占用即用它；
      2. 否則 `withMarket = WorkbookUtil.createSafeSheetName(code + "_" + market)`，未被占用即用它；
      3. 否則 `WorkbookUtil.createSafeSheetName(withMarket + "_" + n)`，n 從 2 遞增直到未被占用。
      **該 Set 必須先放入 doc 內已存在的所有分頁名**（含「當前即時資產」），否則個股分頁可能與它撞名，
      POI 在 `createSheet` 會擲例外、整份匯出失敗。
- [x] 271.1.5 **既有寫空字串 `""` 而非 null 的四處必須照抄**（`:699`／`:719`／`:764`／`:785`：
      銀行／基金的銀行名、股票的券商名、股票的交易日期，皆為 `x != null ? … : ""`）。
      改成 null 會讓儲存格由「空字串格」變成「BLANK 格」，是可觀測的版面變動。
      **其餘欄位逐格核對既有的 null／空字串選擇。**
- [x] 271.1.6 `ExportScheduleService` 的落檔改用 `DualFormatExportWriter`，
      `baseName = "資產總覽_" + ownerId + "_" + LocalDate.now(TW_ZONE).format(FILE_DATE)`（與現況逐字元相同）。
      背景排程 `runScheduled` 與 `runNowForCurrentUser` **兩條路徑都要改**。
      既有的 `private Path writeToDir(...)`（直接 `Files.write`，無 tmp）刪除。
      run-now 回應 DTO 加 `jsonPath`／`jsonSizeBytes`／`jsonGdrivePath`（既有 `path`／`sizeBytes`／
      `gdrivePath` 語意不變、填 xlsx 那一份）。
- [x] 271.1.7 **一次匯出只查一次 `getLiveAssets()`**：取一次 `ExportDoc` 再 render 兩次。
      **這是本任務最重要的一條**，有測試守門（271.4.b）。
      **兩支 render 各自 try/catch，失敗的那一份傳 `null` 給 `DualFormatExportWriter`**
      （t269 269.4.1.1 已允許 null）——交易雷達與交易日曆兩個匯出點同此規則。

### 271.2 交易雷達（`TradingRadarExportService`）

- [x] 271.2 `build(range, fromLabel, toLabel)` 改為 `ExportDoc radarDoc(range, fromLabel, toLabel)`；
      `writeIndexSheet`／`writeMarketSheet`／`writeStockSheet` 三支改為回傳 `ExportDoc.Sheet`。
      `export(String, String)` 與 `exportForOwner(long, long, long)` **維持既有簽章與回傳 `byte[]`**，
      內部改為 render；**另外**各加一支回傳 `ExportDoc` 的版本。
- [x] 271.2.1 **三個分頁的結構不同，只有「快照索引」有標題列。** 實測：
  - **快照索引**（`:93-122`）：r0 是一列提示文字（條件樣式，見 271.2.2）、r1 才是表頭列、其後為資料列，
    結尾 `autosize(sheet, headers.length)`（8 欄）。
  - **大盤總覽**（`:124-160`）：**沒有標題列**，**r0 就是表頭列**（20 欄），其後資料列。
  - **個股決策**（`:161-212`）：**沒有標題列**，**r0 就是表頭列**（35 欄），其後資料列。
    （原為 31 欄；併入 main 時 Task 264 在「資料完整」後插入「時機」「季線乖離%」「52週位置」
    「折溢價%」四欄，原 index 27–30 的四個陣列欄後移為 31–34。）
  **後兩張不得新增任何列**（含 section 標題列與 `Blank`）——多一列就是版面變動。
  它們的 doc 是單一 `Table`（`name = null`、`showHeader = true`、`labelFirstColumn = false`）。
  **三張分頁一律 `omitNullCells = false`**：該服務的 `cell()` 逐格無條件呼叫，null 時建 BLANK 格。
- [x] 271.2.1.1 **該服務的 `section` 是 12pt，不是 13pt**（`TradingRadarExportService.java:263-267`：
      `secFont.setFontHeightInPoints((short) 12)`；`ExcelExportService.java:976-980` 才是 13pt）。
      唯一用到它的地方是快照索引 r0 的非 warn 分支 → `LineStyle.SECTION_12`。
      **把兩者當同一種 section 合併，會靜默改掉交易雷達的字級，而只比對文字的回歸測試抓不到。**
- [x] 271.2.2 「快照索引」分頁頂端那一列是**條件樣式**的（`:97-102`）：
      `range.missingCount() > 0` 時走 `st.warn`、否則走 `st.section`（12pt）。
      表達為 `Line(text, missingCount > 0 ? WARN : SECTION_12)`。**兩種文案都要照抄**
      （有快照／無快照兩種措辭，見既有 `writeIndexSheet`）。
- [x] 271.2.3 **四支取值輔助（`:213-236`）不得照用不變。** 實測定義：
      ```java
      txt(n, f)  → (missing || null) ? ""        : v.asText();
      num(n, f)  → v.isNumber()      ? v.decimalValue() : null;
      bool(n, f) → (missing || null) ? ""        : (v.asBoolean() ? "是" : "否");
      list(n, f) → !v.isArray()      ? ""        : String.join("\n", …);
      ```
      `txt`／`bool`／`list` 把缺值壓成 `""`、boolean 壓成「是」／「否」、陣列壓成 `\n` 串接字串——
      **那是 Excel 的顯示決定**。照用不變的話，交易雷達那份 JSON 會出現 `""` 與 `"是"`，
      **直接違反本需求「缺值就是 null、boolean 就是 JSON boolean」的驗收條件**。故：
  - doc 裡一律放**語意值**：缺值 → `null`；boolean → `Boolean`；number → `BigDecimal`（`num` 照用）；
    陣列 → `List<String>`。
  - 呈現交給 `Format`：boolean 欄用 `Format.BOOL_ZH`（Excel 寫「是」／「否」、JSON 寫 `true`／`false`）、
    陣列欄用 `Format.LIST_LINES`（Excel 以 `\n` 串接、JSON 寫字串陣列）。
  - **`list()` 是第四支、本任務初版漏列**：用於「支持訊號」「風險提醒」兩欄，實作時不要漏。
  - **`txt()` 缺值回 `""` 的既有 Excel 行為要保留**——doc 放 `null`、`Format.TEXT` 時
    `ExcelDocRenderer` 產生的是 BLANK 格而非空字串格，兩者可分辨。
    故這些欄位在 doc 裡放 `""`（不是 null）以保 Excel 零回歸，JSON 那側因此也是 `""`。
    **本項為具名例外，已登錄在 Requirement 55**：交易雷達三分頁中原本由 `txt()` 取值的字串欄，
    JSON 缺值輸出 `""` 而非 `null`。
  - **`bool()`／`list()` 兩類則改放語意值**（`Boolean`／`List<String>`／`null`），
    它們的 Excel 呈現由 `Format.BOOL_ZH`／`LIST_LINES` 負責。
    **注意這兩類的缺值也必須是空字串格**：t269 269.2.4 已明訂
    「`BOOL_ZH`／`LIST_LINES` 的 `null` 值一律 `setCellValue("")`」，不落入通用的 null→BLANK 規則。
    這兩類**不需要**具名例外，因為 JSON 側輸出 `null`、Excel 側維持空字串格，兩邊都與既有一致。
- [x] 271.2.4 **零快照時仍須產出含表頭的合法檔、不得回 5xx**（Requirement 48 既有規定）。
      `export(String, String)` 內 `ownerId == null` 時走空 `SnapshotRange` 的既有分支**必須保留**——
      既有註解寫明：直接委派 `exportForOwner` 會在 `Long → long` 拆箱時 NPE 而回 500。
- [x] 271.2.5 `TradingRadarExportScheduleService` 的落檔改用 `DualFormatExportWriter`，
      `baseName = "交易雷達_" + ownerId + "_" + today.format(FILE_DATE)`（與現況逐字元相同）。
      **該服務每人可設多個執行時間，產檔路徑不只一條**——實作前先
      `grep -ran 'writeDailyExport' backend/src/main/java` 找出**所有**呼叫端，逐一改用同一支元件。
- [x] 271.2.6 該服務的類別 Javadoc 寫「檔名 `交易雷達_{ownerId}_{yyyyMMdd}.xlsx`」，以及約 `:511` 有一個
      `"交易雷達_" + ownerId + "_{YYYYMMDD}.xlsx"` 的**回給前端顯示的檔名樣板**（`filenamePattern`）。
      兩處都要改成明示兩份。**漏改第二處會讓設定頁顯示的檔名與實際落檔不符。**
- [x] 271.2.7 **`TradingRadarExportService` 的 private `Styles`／`cell()`／`autosize()` 在本節做完後
      會變成零使用者的死碼，一併刪除。** 該服務只有三張分頁，271.2 把三張全部改走 `ExportDoc` 之後就沒人用了。
      （對照：`ExcelExportService.Styles` **不刪**——`writeCurrentSummarySheet`／`writeSnapshotSheet`／
      `summaryRow` 仍在用。兩份的下場不同，不要一概而論。）
      驗收：`grep -c 'private static class Styles' backend/src/main/java/com/steven/assets/service/TradingRadarExportService.java` 應為 0。
- [x] 271.2.8 **`TradingRadarExportDto.RunNowResponse` 加 `jsonPath`／`jsonSizeBytes`／`jsonGdrivePath`**
      三個欄位（既有欄位語意不變、填 xlsx 那一份）；`runNow()` 的**每一個** return 點都要填。
      BFF 為 `Map<String,Object>` passthrough、無需改 BFF DTO（實作前先確認）。

### 271.3 交易日曆：拆掉 format 二選一

- [x] 271.3 `TradingCalendarExportService.exportToDir(int year, String format, String subpath)`
      → `exportToDir(int year, String subpath)`，一律產兩份。
      常數 `FORMAT_JSON`／`FORMAT_EXCEL`、方法 `requireValidFormat(...)`／`normalizeFormat(...)` **移除**。
- [x] 271.3.1 **本匯出點是十個裡唯一不接 `ExportDoc` 的。** 既有 `buildJson(year, days)` 的 JSON 結構是
      對外契約、形狀一律不得改變——它**不走** `JsonDocRenderer`，維持既有實作與既有輸出（含 `count`／
      `holidays` 以 `TreeMap` 排序等細節）；Excel 那份維持既有 `buildExcel(year, days)`。
      理由：它本來就已經有兩個 builder、且兩者共吃同一份 `buildDays(year)` 的結果，改接中介模型只會
      改變既有輸出、沒有任何好處。**落檔仍改用 `DualFormatExportWriter`**（取得 tmp＋atomic move
      與統一的狀態字串）。
- [x] 271.3.2 `baseName = "交易日曆_" + year`（與現況逐字元相同）。
- [x] 271.3.2.1 **交易日曆的 Drive 上傳必須自己合併與截斷，比照 t272 的 272.1.6.1。**
      `exportToDir(year, subpath)` 的簽章不含 owner 與 Drive 參數，**結構上走不到 `DualFormatExportWriter`
      的 Drive 段**，故本匯出點與警示觸發是同型情況：`TradingCalendarExportScheduleService` 的
      `syncGdrive` 改為對同一個 `gdriveSubpath` 上傳**兩份**（各呼叫一次 `GdriveOutputSupport.syncQuietly`），
      兩個 `SyncResult.status()` 依 t269 269.4.6 的字串契約合併成
      `"xlsx " + <xlsx status> + "／json " + <json status>`，**寫入 `gdriveLastStatus` 前截斷至 512 字元**。
      漏做的話，Requirement 55 的「Drive 兩份都要上傳」「僅一份成功須可分辨」「狀態欄截斷」三條驗收條件
      在本匯出點都沒有實作承接。`exportToDir` 須回傳兩個 `Path` 供上傳端使用。
- [x] 271.3.3 **`getTwHolidays(year)` 在改動前就已被呼叫兩次**（`:113` 於 `buildDays`、`:148` 於 `buildJson`
      組 `holidays` 區塊），`getUsHolidays`／`getUkHolidays` 亦然。這是既有行為，本需求**不得**去「優化」掉
      ——`buildJson` 是受保護的對外契約。單次查詢探針因此不可寫 `times(1)`（見 271.4.b）。
- [x] 271.3.4 `TradingCalendarExportScheduleService`：`s.getFormat()` 的讀取全部移除；
      `updateForCurrentUser` 不再寫 `s.setFormat(...)`；`toResponse` 不再回 `format`。
      **DB 欄位 `trading_calendar_export_schedule.format` 保留不刪**（避免不可逆的 drop column）。
      **該欄是 `NOT NULL` ＋ CHECK 約束（實作前以 `docker exec asset-postgres psql -U assets -d assets -c '\d trading_calendar_export_schedule'` 確認）**，
      故 entity 的 `format` 欄位**連同其預設值一併保留**、只加 `@Deprecated` 與一行註解說明
      「Requirement 55 起一律雙格式，本欄保留僅為避免 drop column，程式一律不讀寫」。
      **不得**改成會寫入 null 的形式（例如移除 entity 欄位或設成 `insertable=false`），那會在
      insert 新列時違反 NOT NULL。既有列殘留的 `json`／`excel` 值不影響行為——不要寫 migration 去清空它。
- [x] 271.3.5 DTO `TradingCalendarExportDto` 的 `format` 欄位：request 側移除（送來也忽略）、response 側移除、
      `RunResponse.format` 移除；改加 `jsonPath`／`jsonSizeBytes`／`jsonGdrivePath`。
- [x] 271.3.6 **BFF**：移除 `TradingCalendarBffController.export` 的 `@RequestParam format` 與
      `.queryParam("format", ...)`，並更新該方法的 Javadoc。
      （其餘匯出頁的 BFF 多為 `Map<String,Object>` passthrough，新欄位自動透傳、無需改 DTO；
      交易日曆是唯一有具名 `format` 參數的，故是唯一要動 BFF 的。實作前先確認。）
- [x] 271.3.7 controller `TradingCalendarExportController` 的手動匯出端點若吃 `format` query 參數，
      **移除該參數**（帶了也忽略）。手動匯出同樣一律產兩份。
- [x] 271.3.8 前端 `frontend/src/views/TradingCalendarView.vue`：
  - 移除 `<el-radio-group v-model="exportDialog.format">` 整段與 `exportDialog.format` 狀態、
    `if (s.format) exportDialog.format = s.format` 的回填、送出 payload 裡的 `format` 欄位。
  - 檔名說明文字（兩處，皆為 `交易日曆_{{ year }}.{{ format === 'excel' ? 'xlsx' : 'json' }}`）
    改為明示「同時產生 `交易日曆_{年}.json` 與 `交易日曆_{年}.xlsx` 兩份」。
    **實作前先 grep 確認行號**（本檔會因前面的改動位移）：
    ```bash
    grep -n 'exportDialog.format\|交易日曆_' frontend/src/views/TradingCalendarView.vue
    ```
  - `bffApi.tradingCalendar.exportToDir(year, format, subpath)` 的呼叫改為 `(year, subpath)`，
    `frontend/src/api` 對應的函式簽章一併改。

### 271.5 前端（本任務負責三個頁面，不留給收尾任務）

- [ ] 271.5 三個匯出點各自的前端文案都由本任務改（271.3.8 已涵蓋交易日曆頁，這裡補另外兩頁）：

  > **本項只完成了一半，勾是假的（2026-08-02 實測）。** 三頁的檔名說明文字確實都改成「兩份」了；
  > 但下方最後一行「兩頁的 run-now 成功提示改為同時顯示兩個落點（讀新增的 `jsonPath`）」
  > **完全沒做**——資產總覽仍是 `已匯出到：${r.path}`、交易雷達仍是 `已寫入 ${res.path}`、
  > 交易日曆的 toast 與 `export-status` 也都只列一份。**文案那一半不需要重做**，
  > 未完成的只有結果提示，由 **t282** 承接。

  - **`frontend/src/views/AssetHistoryView.vue`**（資產總覽）：`:133` 的
    `<code>資產總覽_{使用者ID}_YYYYMMDD.xlsx</code>` 改為明示同時產生 `.json` 與 `.xlsx` 兩份、主檔名相同。
    （**`:301` 的 `a.download = 資產管理_….xlsx` 不要動**——那是瀏覽器端「完整匯出」的下載檔名，
    屬手動下載、不在本需求範圍。）
  - **`frontend/src/views/TradingRadarView.vue`**（交易雷達）：`:338` 的落點顯示與
    `filenamePattern` 的 fallback 字串 `'交易雷達_{使用者ID}_{日期}.xlsx'`、以及 `:339` 的
    「檔名固定為 …xlsx」兩處，改為明示兩份。
    （**`:916`／`:940` 的區間手動匯出下載檔名不要動**——那是手動下載。）
  - 兩頁的 run-now 成功提示改為同時顯示兩個落點（讀新增的 `jsonPath`）。
  **實作前先 grep 確認行號**（會因前面的改動位移）：
  ```bash
  grep -n 'xlsx' frontend/src/views/AssetHistoryView.vue frontend/src/views/TradingRadarView.vue
  ```

### 271.4 測試

- [x] 271.4 三個匯出點各一組：
  - **(a) 主檔名一致性**：兩個落點去掉副檔名後 `assertEquals`。三個匯出點各一條。
  - **(b) 單次查詢**：探針對準**本需求新引入的那一層**——
    `verify(excelExportService, times(1)).liveAssetsDoc()`（資產總覽）、
    `verify(radarExportService, times(1)).radarDoc(...)`（交易雷達）。
    **交易日曆不寫 `times(1)`**：`getTwHolidays` 改動前就是 **2 次**（271.3.3），
    改斷言 `verify(marketDataService, times(2)).getTwHolidays(anyInt())` 並在測試方法名或註解寫明
    「2 次是既有行為（`buildDays` 與 `buildJson` 各一次），本需求不得讓它變成 3 次以上」。
    **寫成 `times(1)` 會逼實作者去改 `buildJson`——而那正是本任務明文保護的對外契約。**
    （`getUsHolidays`／`getUkHolidays` 同為 2 次，一併比照。）
    資產總覽與交易雷達那兩條**必須併加** `verify(excelExportService, never()).exportLiveAssets…()`
    （列出該匯出點所有回 `byte[]` 的既有公開方法）或以 `verifyNoMoreInteractions(...)` 收尾——
    只驗 `xxxDoc` 被叫一次擋不住「xlsx 走既有的 `byte[]` 方法、json 另呼一次 doc」這種查兩次資料
    卻全綠的寫法（mock 看不到 service 的內部自呼叫）。
  - **(c) 既有 Excel 零回歸**：以 POI 讀回，**逐列逐格**比對——分頁名、分頁順序、列索引、每格的值、
    每格 `CellStyle` 的粗體／字級／`dataFormat`、空白列位置、`autoSizeColumn` 欄數。
    **基準取得方式＝golden file，不是「在測試裡跑舊版程式碼」。** 只把舊版 `.java` 撈出來是**沒用的**——
    單元測試無法編譯執行一支舊版 service（它有整串相依）。正確流程：
    ```bash
    git worktree add /tmp/r55-before origin/main
    ```
    在該 worktree 跑一支拋棄式測試，把改動前 `exportLiveAssets()` 與
    `TradingRadarExportService.build(...)` 的 bytes 寫成
    `backend/src/test/resources/golden/live_assets_before.xlsx`／`radar_before.xlsx` 並提交；
    新測試以 POI 讀 golden 檔逐格比對。
    （**用 `origin/main` 而非 `main`**：本專案多個 worktree 共用同一個 local `main` ref，可能被別的
    session 推進或沒 checkout。）
    **「當前即時資產」與「快照索引」兩張分頁必須做完整逐列逐格比對**，不可只抽驗。
    產 golden file 時**必須固定輸入**：`StockPriceService.getLiveAssets()`／`AssetSnapshotRepository`／
    `TradingRadarSnapshotStore` 一律以 mock 餵固定資料，否則基準不可重現。
  - **(c1) 牆鐘時間戳是具名例外**：`匯出時間`（`ExcelExportService.java:656`）這類
    `LocalDateTime.now(TW_ZONE)` 的格，逐格比對時以「該格存在 ＋ 型別 ＋ 樣式 ＋ 符合
    `yyyy-MM-dd HH:mm:ss` 正則」取代值相等，其餘欄位仍逐格比對值。
    理由：backend 全樹沒有 `Clock` 注入、也沒有 `mockStatic` 的既有用法，「逐格完全相同」在這幾份上
    寫不出來。**不要為此在本任務引入 `Clock` 注入**——那是跨十個匯出點的獨立重構，超出本需求範圍。
  - **(d) section 字級**：「快照索引」r0 的 `getCellStyle().getFont().getFontHeightInPoints() == 12`；
    「當前即時資產」r0 為 `13`（271.2.1 的探針；統一成一種 section 的錯誤實作會在此紅）。
  - **(e) 資產總覽早退分支**：`live == null` 時兩份都產出且未擲例外；Excel **前三列逐格**為
    `當前即時資產`(粗體 13pt)／`匯出時間`+值／`尚無資產快照`(**無樣式**)，`autoSizeColumn` 涵蓋 6 欄；
    JSON 的 `sheets[0].tables` 為空陣列、`lines` 含兩則（271.1.2 的探針）。
  - **(f) 即時彙總無表頭 ＋ 標籤欄樣式**：Excel 中「即時彙總」section 標題列的下一列**直接是資料列**
    （不是表頭列）、且該列第 0 欄 `getCellStyle()` 等於 head 樣式；JSON 中該 table 的 rows 仍以
    `項目`／`金額` 為 key（271.1.1 的探針）。
  - **(g) 同列多鍵值**：Excel 中 r2 為**單一列六格**（`基準快照日期`／值／`美元匯率`／值／`即時總資產`／值），
    `row.getLastCellNum() == 6`；JSON 中三者併進同一個 `meta` 物件（271.1.1 的探針；
    用 `Kv` 逐列或用 `Table` 的錯誤實作都會在此紅）。
  - **(h) 交易雷達 JSON 型別**：boolean 欄在 Excel 為「是」／「否」、在 JSON 為 `true`／`false`；
    陣列欄（支持訊號／風險提醒）在 Excel 為 `\n` 串接字串、在 JSON 為字串陣列（271.2.3 的探針）。
  - **(i) 交易雷達零快照**：空 `SnapshotRange` 時兩份都產出且含表頭；`export(from, to)` 在
    `ownerId == null` 時**不擲 NPE**（271.2.4 的探針）。
  - **(j) 分頁名去重**：構造「同代號不同市場」「同代號僅大小寫不同」兩種持股，
    斷言分頁名互不重複、且不與「當前即時資產」撞名（271.1.4 的探針）。
  - **(j1) 持股層級去重與跳過**：同代號同市場、分屬**兩家券商**的兩筆持股，只產出**一張**分頁；
    `stockCode` 為 null 或空白的持股被跳過、不產生分頁；`latest == null` 時回空 list
    （271.1.3.1 的探針——只做分頁名去重的實作會產出兩張名字不同的重複分頁，(j) 抓不到）。
  - **(k) 兩個快取**：同一支股票被兩個券商持有時，`technicalIndicatorService.computeAll()` 與
    `priceQueryService.getEtfNav()` 對該 `(code|market)` **各只被呼叫一次**；
    另加一條：查無淨值（回 null）的個股在多列情況下 `getEtfNav()` 仍**只被呼叫一次**
    （`containsKey` vs `computeIfAbsent` 的探針，271.1.3）。
  - **(l) 交易日曆 JSON 零回歸**：固定 `year` 與 mock 的假日資料，比對改動前後的 `buildJson` 輸出。
    **`generatedAt` 是牆鐘值**（`TradingCalendarExportService.java:154`），屬 Requirement 55 已登錄的
    具名例外三——**該 key 只驗存在且符合格式正則，其餘欄位（`year`／`count`／`holidays`／逐日陣列等）
    逐字比對**。寫成「整份逐 byte 相同」會永遠紅。
  - **(m) format 欄位停用**：設定列 `format='excel'` 時仍產出 `.json` 與 `.xlsx` 兩份
    （殘留值不影響行為的探針）；且**新建一列時 `format` 欄仍寫入合法值**（271.3.4 的 NOT NULL 探針）。
  - **(n) 既有測試一併更新**：`TradingExportGdriveTest`／`ExportScheduleGdriveTest`／
    `TradingRadarExportScheduleServiceTest`／`TradingRadarExportServiceTest` 內既有對 `copyTo` 次數、
    狀態字串前綴、以及 `requireValidFormat` 的 stub，會因為本任務而全部紅。
    **這些不是回歸，是預期變更**，須逐一改成新的次數（每匯出點 ×2）與新的字串契約（`xlsx …／json …`），
    並移除已不存在的 `requireValidFormat` stub。實作前先跑一次全測試取得受影響清單。

## 驗證

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

確認 format 設定鏈已拆乾淨（前兩個應輸出 0，第三個應仍看得到 DB 欄位＝刻意保留）：

```bash
grep -ran "FORMAT_JSON\|FORMAT_EXCEL\|requireValidFormat" backend/src/main/java bff/src/main/java frontend/src | wc -l
```

```bash
grep -n "exportDialog.format" frontend/src/views/TradingCalendarView.vue | wc -l
```

```bash
docker exec asset-postgres psql -U assets -d assets -c "\d trading_calendar_export_schedule" | grep format
```

建置與部署（前後端都動到）：

```bash
docker compose -p asset-management build --no-cache business-services frontend
```

```bash
docker compose -p asset-management up -d --no-deps --force-recreate business-services frontend && docker compose -p asset-management restart bff
```

端到端實測：

```bash
docker exec asset-business-services curl -s -X POST -H 'X-User-Id: 1' -H 'X-User-Role: ADMIN' -H 'X-User-Status: ACTIVE' http://localhost:8080/api/export-schedule/run-now
```

```bash
docker exec asset-business-services sh -c 'ls -l /home/steven/input/ | grep -E "資產總覽|交易日曆|交易雷達"'
```

（最後一行：每個主檔名都必須看到 `.json` 與 `.xlsx` 各一份。）

## 完成報告

**狀態：已完成——但 271.5 的「run-now 成功提示顯示兩個落點」實際未實作（2026-08-02 發現，由 t282 承接）；
檔名說明文案那一半確實完成。** 本報告下方的檔案表只涵蓋文案那一半。

| 檔案 | 改動 |
|---|---|
| `ExcelExportService.java` | `writeLiveAssetsSheet` → `liveAssetsSheet()`；`writeStockPriceHistorySheets` → `stockPriceHistorySheets()`；`buildLiveWorkbook` → `liveAssetsDoc()`／`liveAssetsDocForOwner()`；`uniqueStockSheetName` 改吃大小寫不敏感 Set |
| `TradingRadarExportService.java` | 三分頁 → `indexSheet`／`marketSheet`／`stockSheet`；`build` → `radarDoc`；新增 `boolVal`／`listVal`；**刪除 `Styles`／`cell()`／`autosize()`／`bool()`／`list()` 與 11 個未用 import** |
| `TradingCalendarExportService.java` | `exportToDir(year, format, subpath)` → `exportToDir(year, subpath)`，一律兩份；`FORMAT_*`／`requireValidFormat`／`normalizeFormat`／`writeAtomically` 刪除；**`buildJson` 一行未改** |
| `ExportScheduleService`／`TradingRadarExportScheduleService`／`TradingCalendarExportScheduleService` | 接 `DualFormatExportWriter`；自有落檔實作刪除；交易日曆另加 `syncGdriveBoth`（自行合併與截斷） |
| `TradingCalendarExportSchedule.java` | `format` 欄位加 `@Deprecated`，**預設值保留**（DB 為 NOT NULL ＋ CHECK） |
| DTO ×3、BFF ×1、前端 ×3 ＋ `api/index.js` | 新欄位、移除 `format` 參數、文案改為明示兩份 |
| 新測試 ×3 ＋ `GoldenWorkbooks` ＋ 4 份 golden | `LiveAssetsDualFormatTest`(9)／`TradingRadarDualFormatTest`(8)／`TradingCalendarDualFormatTest`(6) |

**驗證輸出：**
- `mvn -f backend/pom.xml test` → **Tests: 457, Failures: 0, Errors: 0**
- `vite build` 通過；bff compile 通過
- `FORMAT_JSON|FORMAT_EXCEL|requireValidFormat|normalizeFormat` 全樹 **0**；四支服務落檔死碼 **0**
- t272 範圍（`StockAlertTrigger*`／`NewsPoller`／`StockAlertView`／`CrawlerDataView`／`SchedulePublicBff`）**改動 0 次**；新增 `@Scheduled` **0**；新增 changeset **0**
- `arch-auditor` 查證：0 critical、2 major、2 minor，均已修

**與原計畫的偏差及原因：**
1. **`arch-auditor` 抓到兩個真 bug（均已修）：**
   - 交易日曆排程**自組** `lastRunStatus`（`"成功：" + path + "／" + jsonPath`）。兩份都寫不出來時
     共用元件不擲例外、回 null path，於是記成假的「成功：null／null」；且無截斷，兩個路徑串起來可達
     ~576 字元而溢位 `varchar(500)`。改為由 `exportToDir` 帶回共用元件算好的 `localStatus`，
     並補一條測試（目標路徑被同名目錄占住 → 斷言不得以「成功：」開頭）。
   - `jsonGdrivePath` 宣告了卻**永遠不填**——`syncGdriveBoth` 拿到 json 落點卻只回 xlsx 那一份。
     改為回傳 `BothUploaded(status, xlsxPath, jsonPath)`。
2. **先前的刪除腳本在寫檔前 crash，`bool()`／`list()`／`cell()` 其實沒被刪掉**（`Styles`／`autosize` 是
   第二支腳本刪的）。`arch-auditor` 抓到後補刪，連同 11 個未用 import。
3. **兩處 `startsWith("跳過：")` 被無成因地放寬成 `contains("跳過")`**——該路徑走
   `GdriveOutputSupport.skipped()`，字串仍以「跳過：」開頭，原斷言不會紅。已還原。
4. **`indexLabel("TWSE")` 是「台股大盤」**、`RcloneClient.copyTo` 是 4 參數、`buildJson` 的欄位是
   `tradingDayCount` 而非 `count`——測試 fixture 與斷言均依實測更正。
5. **golden fixture 必須與產生器逐字相同**：交易雷達的測試一度多加了 `reasons`／`risks` 兩欄而與 golden
   對不起來，改為另建一份 `snapshotNodeWithArrays()` 供「陣列兩種呈現」測試使用。
6. **`arch-auditor` 指出 golden 的 provenance 無法從產物本身證明**（只有 POI 的 creator metadata）。
   結構佐證成立：`live_assets.xlsx` 的 0-based 第 3／9／14／19 列**不存在 `<row>` 元素**、第 2 列有 6 個 `<c>`
   ——正是「空行不建 Row」與「同列六格」的指紋，新實作若做錯就對不上。
7. **併入 main 後個股決策由 31 欄變 35 欄。** merge `origin/main`（71bf6a29）時，Task 264 在
   「資料完整」（index 26）之後插入「時機」（TEXT／`timingLabel`）、「季線乖離%」（NUM2／`ma60BiasPercent`）、
   「52週位置」（NUM2／`week52Position`）、「折溢價%」（NUM2／`etfPremiumPct`），原 index 27–30 的四個
   `LIST_LINES` 欄後移為 31–34。**大盤總覽（20 欄）與快照索引（8 欄）未變。**
   **踩到的坑：`columnFormats` 那一串落在 git 衝突標記之外**，三方合併靜默保留了 31 欄版；
   只改衝突區內的 headers／rows 會讓 `ExportDoc.Table` 的 compact constructor 在 runtime 擲
   `IllegalArgumentException`（長度不符）。headers／formats／rows **三處必須同時改且順序一致**。
   `golden/radar.xlsx` 與 `golden/radar_empty.xlsx` 已用合併後的 origin/main 重產（個股決策表頭列 35 格已驗）。
   `TradingRadarDualFormatTest` 對「逆勢條件」的硬編 `getCell(29)` 隨之改為 `getCell(33)`。
