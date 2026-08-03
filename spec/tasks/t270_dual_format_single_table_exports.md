# [t270] 五個單表匯出改走 `ExportDoc` 雙格式（已實現損益／匯率／指數／油價金價／交易紀錄）

**對應 Requirements:** Requirement 55（十個自動匯出點的檔案一律同時產出 `.json` 與 `.xlsx` 兩份，主檔名完全相同、只差副檔名；兩份必須來自同一次資料查詢；既有 Excel 的內容不得減損，判準是「逐列逐格 ＋ 儲存格樣式」）
**前置任務:** t269（`ExportDoc` block list 模型、`ExcelDocRenderer`／`JsonDocRenderer`、`DualFormatExportWriter` 已就緒且測試綠）
**後續任務:** t271（資產總覽／交易雷達／交易日曆）、t272（警示觸發／爬蟲公開資訊）
**Liquibase changeset:** 無（本需求全程零 schema 變更）

## 背景

十個自動匯出點中，這五個的 Excel 結構最單純——**單一工作表、單一表頭列、其下全是資料列**，沒有 section
標題、沒有鍵值表頭區、沒有空行。先把它們接上 t269 的地基，用最小的爆炸半徑驗證整套模型可行，再處理結構
複雜的資產總覽與交易雷達（t271）。

五個匯出點的現況（實測自程式碼，實作時仍以程式碼為準）：

| 排程服務 | 主檔名 | 工作表名 | 表頭 |
|---|---|---|---|
| `RealizedGainExportScheduleService` | `已實現損益_{ownerId}_{yyyyMMdd}` | `已實現損益` | 資產名稱／代號／交易日期／股數／賣出均價／收帳金額／投資成本／損益／報酬率／市場／幣別／券商／匯率／年度（14 欄） |
| `ExchangeRateExportScheduleService` | `台幣兌美元_{ownerId}_{yyyyMMdd}` | `台幣兌美元`（＝`ExcelExportService.exchangeRateLabel(currency)`） | 日期／即期買入／即期賣出／中間價（4 欄） |
| `IndexExportScheduleService` | `{indexLabel(market)}_{ownerId}_{yyyyMMdd}` | `createSafeSheetName(indexLabel(market))` | 日期／開盤／最高／最低／收盤（5 欄）〔**Task 285 起為 6 欄**（末尾加「週線MA5」）、**Task 286 起為 9 欄**（再加月線MA20／季線MA60／年線MA240）〕 |
| `CommodityExportScheduleService` | `油價金價_{ownerId}_{yyyyMMdd}` | `油價金價` | 日期／WTI原油(USD/桶)／布蘭特原油(USD/桶)／黃金(USD/盎司)（4 欄） |
| `AssetTransactionExportScheduleService` | `交易紀錄_{ownerId}{_排程名}_{yyyyMMdd}` | `交易紀錄` | **17** 欄固定順序（見 `assetTransactionsSheet()`；併入 main 時 Task 268 於 index 9、10 插入「手續費」「證交稅」，兩欄皆 `Format.MONEY`） |

**「既有 Excel 內容不得減損」是本任務最大的風險**，判準是**逐列逐格 ＋ 儲存格樣式**（不是只比對表頭文字
與列數）。這五份已經在使用者目錄裡累積了歷史檔案，欄位或樣式漂移會讓新舊檔無法並排比對。而且五份的
產生方法**同時被手動下載端點使用**（`exportRealizedGains`／`exportAssetTransactions`／
`exportCommodityPrices`／`exportExchangeRates`／`exportIndexDaily`），手動下載的產物同樣不得改變。

## 要做什麼

### 270.1 `ExcelExportService`：五個 writer 改為 doc builder

- [x] 270.1 把五支 `writeXxxSheet(Workbook, Styles, …)` 改寫成回傳 `ExportDoc.Sheet` 的方法，只回傳資料、不碰 POI：
  - `writeRealizedGainsSheet(wb, st)` → `realizedGainsSheet()`
  - `writeExchangeRateSheet(wb, st, currency, start, end)` → `exchangeRateSheet(currency, start, end)`
  - `writeIndexDailySheet(wb, st, market, start, end)` → `indexDailySheet(market, start, end)`
  - `writeCommoditySheet(wb, st, start, end)` → `commoditySheet(start, end)`
  - `writeAssetTransactionsSheet(wb, st)` → `assetTransactionsSheet()`
- [x] 270.1.1 五份都是**單一 `Table` block**（`name = null`、`showHeader = true`、`labelFirstColumn = false`）。
      **`omitNullCells` 兩種值都會用到，必須逐份判定**：
      油價金價（`:385-387`）／匯率（`:444-446`）／指數（`:516-518`）三份既有是
      `if (x != null) cell(...)`＝**值為 null 時根本不建那一格** → `omitNullCells = true`；
      已實現損益與交易紀錄逐格都有呼叫 `cell(...)`（null 時建 BLANK 格）→ `omitNullCells = false`。
      **兩者在 POI 讀回時可分辨**（`getCell(i)` 為 null vs BLANK、`getLastCellNum()` 不同），判錯就是版面變動。
      其餘參數：
      `Sheet.autoSizeColumns` 依既有各方法結尾的迴圈次數填（已實現損益／交易紀錄以 `headers.length` 為準，
      油價金價與匯率為 **4**、指數為 **5**——**逐一到程式碼確認，不要照抄本行**）。
      **五份都不需要 `showHeader=false`**（那是 t271「即時彙總」才有的需求）。
- [x] 270.1.2 對應的 `buildXxxWorkbook()` 改為 `ExportDoc xxxDoc(…)`。公開方法（`exportRealizedGains`／
      `exportRealizedGainsForOwner`／`exportAssetTransactions`／`exportAssetTransactionsForOwner`／
      `exportCommodityPrices`／`exportExchangeRates`／`exportIndexDaily`）**維持既有簽章與回傳 `byte[]`**，
      內部改為 `excelDocRenderer.render(xxxDoc(...))`。**另外**各加一支 `xxxDoc(...)` 的 public 版本，
      供排程服務取得同一份 doc 去 render JSON。
- [x] 270.1.3 **`@Transactional(readOnly = true)` 與 `enableFilter("ownerFilter")` 的位置一律不變。**
      `exportRealizedGainsForOwner`／`exportAssetTransactionsForOwner` 目前是先 `enableFilter` 再呼叫
      builder，兩者必須在**同一個 session／交易**內。新的 public `xxxDoc(...)` 同樣標
      `@Transactional(readOnly = true)`，且**排程服務必須只呼叫一次**取得 doc、再 render 兩種格式——
      呼叫兩次等於查兩次資料，違反「兩份必須來自同一次資料查詢」。
- [x] 270.1.4 **`buildWorkbook()`（手動「完整匯出」`exportFull`／`exportFullForOwner`）必須跟著改，
      但產物不得變。** 它的活頁簿是「當前彙總 ＋ 每快照一張 sheet ＋ 已實現損益」，其中已實現損益那張
      與排程那份是同一支 writer。改法：原本呼叫 `writeRealizedGainsSheet(wb, st)` 的地方改為
      `excelDocRenderer.writeSheet(wb, realizedGainsSheet())`（**注意 `writeSheet` 不吃 `Styles` 參數**，
      renderer 自己依該 workbook 建立並快取）。**不得**讓完整匯出留一份自己的已實現損益 writer。
- [x] 270.1.5 **`ExcelExportService.Styles` 不刪、也不會變成死碼**：`writeCurrentSummarySheet`／
      `writeSnapshotSheet`／`writeStockPriceHistorySheets` 等未接 doc 的分頁仍在用它。
      兩份 Styles（它與 `ExcelDocRenderer.Styles`）的 `head`／`money`／`num2`／`num4`／`num6` 定義
      必須維持逐字相同，任一方改動須同步。
- [x] 270.1.6 **欄位、順序、表頭文字、儲存格格式、null／空字串的選擇逐一照抄，不得「順手改善」。**
      具體要保留的既有行為（實作時逐條到程式碼確認；下列是已知最容易在重構時弄丟的）：
  - 日期一律 `ISO.format(...)` 寫成**文字**（doc 裡放 `LocalDate` ＋ `Format.DATE` 即可，
    `ExcelDocRenderer` 會寫成 ISO 字串 cell）。既有註解寫明理由是「避免 Excel 依開啟端時區重新詮釋
    date cell 而偏移一天」。
  - 油價金價／匯率／指數三份：某格無值時**留空、不補前值、不捏造**。
    **既有寫法是 `if (x != null) cell(...)`——值為 null 時 `cell()` 根本沒被呼叫，那一格完全不存在**，
    **不是** BLANK 格。在 doc 裡放 `null` ＋ `omitNullCells = true` 才等價。
    （把它當成 BLANK 格是本任務初版的錯誤：實測同一列在 POI 讀回時
    `getLastCellNum()` 會由 3 變 4、`getCell(1)` 由 `null` 變 `BLANK`。）
  - **既有有兩處刻意寫空字串 `""` 而非 null，必須照抄成 `""`**：
    `ExcelExportService.java:929`（已實現損益「交易日期」為 null 時）與
    `:314`（交易紀錄「交易日期」為 null 時）。改成 null 會讓儲存格由「空字串格」變成「BLANK 格」，
    是可觀測的版面變動。**其餘欄位請同樣逐格核對既有的 null／空字串選擇。**
  - 指數分頁名走 `WorkbookUtil.createSafeSheetName(indexLabel(market))`，不可改成 `indexLabel(market)`。
  - 交易紀錄的「台幣成交金額」是即時算出的衍生值（`currency=USD` 且 `exchangeRate` 非 null 時
    ＝`amount × exchangeRate`，否則＝`amount`），**不入庫**——照抄計算式，不要改精度或捨入。

### 270.2 五支排程服務改用 `DualFormatExportWriter`

- [x] 270.2 五支服務的落檔改為呼叫 `DualFormatExportWriter.write(...)`，`baseName` **不含副檔名**：

  | 服務 | `baseName` |
  |---|---|
  | `RealizedGainExportScheduleService` | `"已實現損益_" + ownerId + "_" + LocalDate.now(TW_ZONE).format(FILE_DATE)` |
  | `ExchangeRateExportScheduleService` | `ExcelExportService.exchangeRateLabel(CURRENCY) + "_" + ownerId + "_" + end.format(FILE_DATE)` |
  | `IndexExportScheduleService` | `ExcelExportService.indexLabel(market) + "_" + ownerId + "_" + end.format(FILE_DATE)` |
  | `CommodityExportScheduleService` | `"油價金價_" + ownerId + "_" + end.format(FILE_DATE)` |
  | `AssetTransactionExportScheduleService` | `"交易紀錄_" + ownerId + suffix + "_" + date`（`suffix` ＝排程名非空白時 `"_" + name.trim()`，否則 `""`） |

  **這五個字串必須與現況逐字元相同**（拿掉 `.xlsx` 之後）。改檔名等於讓使用者目錄裡多出一組新名字的檔，
  舊檔變成永遠不再更新的孤兒。
- [x] 270.2.1 **一次匯出只查一次資料**：`ExportDoc doc = excelExportService.xxxDoc(...)` 取得後，
      `byte[] xlsx = excelDocRenderer.render(doc); byte[] json = jsonDocRenderer.render(doc);`
      **不得**寫成兩次 `xxxDoc(...)`。這一條有測試守門（270.4.b）。
      **兩支 render 各自 try/catch，失敗的那一份傳 `null` 給 `DualFormatExportWriter`**
      （t269 269.4.1.1 已允許 null）——這是「一份 render 失敗不得讓另一份不寫」那條驗收條件的落實方式。
- [x] 270.2.2 該服務內**所有**產檔入口（背景排程 `runScheduled`、`run-now`，以及任何其他入口）
      全部改用同一支 `DualFormatExportWriter`。只改背景排程會讓 run-now 只產一份，使用者用 run-now
      驗證落點時看到的與實際排程結果不一致。實作前先 grep 該服務內所有落檔呼叫端確認數量。
- [x] 270.2.3 **`RealizedGainExportScheduleService` 目前是直接 `Files.write`（無 tmp＋atomic move），
      改用本元件後自動獲得 tmp＋atomic move**——這是本需求明列的一併補上項，不是額外範圍。
- [x] 270.2.4 **`AssetTransactionExportScheduleService.writeToDir` 的路徑逃脫重驗不得弄丟。**
      該方法（`:328-331`）現有一段：
      ```java
      if (!file.normalize().startsWith(dir)) {
          throw new IllegalArgumentException("排程名稱不合法，拒絕寫入：" + name);
      }
      ```
      理由是「排程名會進檔名，而 DB 值可能被繞過 API 以 psql 直改」。t269 已把這道防線搬進
      `DualFormatExportWriter`（269.4.2）；**本任務刪 `writeToDir` 前必須確認 t269 那一條真的做了**，
      否則就是在重構中靜默弄丟一道安全檢查。
- [x] 270.2.5 `lastRunStatus` 改寫入 `DualResult.localStatus()`（已由元件截斷至 500 字元內）；
      Drive 狀態欄改寫入 `DualResult.gdriveStatus()`（已截斷至 512 字元內，未啟用時為 null）。
      **`gdriveStatus` 為 null 時一律不碰 `gdriveLastRunAt`／`gdriveLastStatus` 兩欄**（沿用既有語意：
      那兩欄是「上次上傳」的結果，未啟用時寫任何東西進去都會覆蓋掉前一次真正成功的落點）。
      **實作前先以 `\d <該服務的設定表>` 確認這兩欄的 `varchar` 長度**，若比 500／512 更短，
      須在 t269 的 `DualFormatExportWriter` 把上限改成最短者。
- [x] 270.2.6 各服務原本的 `private Path writeToDir(...)`／`export(...)` 內的 tmp＋move 程式碼**刪除**，
      不得留著不用。留著會是第二份落檔實作，日後必然分歧。
      **但 `resolveDir(...)` 不可一起刪掉**：共用元件只驗 `baseName`（單一檔名），
      **目錄跳脫（使用者設定的 `outputSubpath` 不得跑出 `EXPORT_OUTPUT_DIR` 基底）仍由呼叫端的
      `resolveDir` 負責**。兩道防線管的是不同東西，順手刪掉 `resolveDir` 會弄丟路徑安全模型的另一半。
- [x] 270.2.7 run-now 的回應 DTO（`path`／`sizeBytes`／`gdrivePath`）**維持既有欄位與語意不變**
      （`path` 與 `sizeBytes` 填 xlsx 那一份、`gdrivePath` 填 xlsx 的 Drive 落點）。**額外**加
      `jsonPath`／`jsonSizeBytes`／`jsonGdrivePath` 三個欄位。理由：既有前端讀 `path`／`sizeBytes`，
      改語意會讓五個頁面同時壞掉。
      **BFF 端這幾支是 `Map<String,Object>` passthrough，新欄位自動透傳，無需改 BFF DTO**——
      實作前先確認該頁的 BFF route 是不是 passthrough；若不是，才需要改。

### 270.3 前端（本任務自己負責，不留給收尾任務）

- [ ] 270.3 **本任務只改下列五頁，逐一列名以免與 t271 重工**（實測位置；行號會位移，實作前重 grep）：

  > **本項只完成了一半，勾是假的（2026-08-02 實測）。** 設定卡的**檔名說明文字**確實五頁都改成
  > 「兩份」了；但下方要求的「run-now 成功提示改為同時顯示兩個落點（讀新增的 `jsonPath`）」
  > **五頁一頁都沒做**，五頁的成功提示至今仍是 `已匯出到：${r.path}`（只有 xlsx）。
  > **文案那一半不需要重做**，未完成的只有結果提示，由 **t282** 承接。


  | 頁面 | 排程檔名說明 |
  |---|---|
  | `frontend/src/views/RealizedGainView.vue` | `:209` |
  | `frontend/src/views/ExchangeRateView.vue` | `:149` |
  | `frontend/src/views/GdpTwseView.vue` | `:137` |
  | `frontend/src/views/CommodityPriceView.vue` | `:143` |
  | `frontend/src/views/TransactionView.vue` | `:208-209`（兩句：無名稱與有名稱的排程各一） |

  改為明示「會同時產生 `.json` 與 `.xlsx` 兩份、主檔名相同」；run-now 成功提示改為同時顯示兩個落點
  （讀新增的 `jsonPath`）。
  **`a.download = …xlsx` 與 `filename = …xlsx`（`RealizedGainView:651`／`ExchangeRateView:445`／
  `CommodityPriceView:496`／`GdpTwseView:422`／`TransactionView:683` 等）一律不要動**——那是使用者
  按鈕觸發的手動下載檔名，不在本需求範圍。
  **`AssetHistoryView.vue` 與 `TradingRadarView.vue` 屬 t271（271.5），本任務不得動它們。**
  **文案必須跟著本任務走，不得延後到 t272 統一處理**——延後會讓中間狀態的 UI 對使用者說謊。
      ```bash
      grep -n 'xlsx' frontend/src/views/RealizedGainView.vue frontend/src/views/ExchangeRateView.vue frontend/src/views/GdpTwseView.vue frontend/src/views/CommodityPriceView.vue frontend/src/views/TransactionView.vue
      ```

### 270.4 測試

- [x] 270.4 五個匯出點各一組，擴充對應的既有測試類（`AssetTransactionExportScheduleServiceTest`／
      `AssetTransactionExcelExportTest`／`ExportScheduleGdriveTest`／`TradingExportGdriveTest` 等已存在；
      查不到對應類就新建）：
  - **(a) 主檔名一致性**：取 `DualResult` 的兩個落點，去掉副檔名後 `assertEquals`。五個匯出點各一條。
  - **(b) 單次查詢**：`verify(excelExportService, times(1)).xxxDoc(...)` 斷言一次匯出只取一次 doc。
    **探針對準本需求新引入的那一層（`xxxDoc`），不要對準既有的 repository 方法**——既有方法的呼叫次數
    可能本來就不是 1，寫死 `times(1)` 會逼實作者去改既有邏輯。五個匯出點各一條。
    **同一條測試必須併加** `verify(excelExportService, never()).exportRealizedGains…()`
    （列出該匯出點所有回 `byte[]` 的既有公開方法），或以 `verifyNoMoreInteractions(excelExportService)` 收尾。
    只驗 `xxxDoc` 被叫一次**擋不住**「xlsx 走既有的 `byte[]` 方法、json 另呼一次 `xxxDoc()`」這種
    查兩次資料卻全綠的寫法——mock 看不到 service 的內部自呼叫。
  - **(c) 既有 Excel 零回歸**：以 POI 讀回產出的 bytes，逐份**逐列逐格**斷言：分頁名、列索引、每一格的值、
    每一格 `CellStyle` 的粗體／字級／`dataFormat` 字串、`autoSizeColumn` 的欄數。
    **基準取得方式＝golden file，不是「在測試裡跑舊版程式碼」。** 只把舊版 `.java` 撈出來是**沒用的**——
    單元測試無法編譯執行一支舊版 service（它有整串相依）。正確流程：
    ```bash
    git worktree add /tmp/r55-before origin/main
    ```
    在該 worktree 跑一支拋棄式測試，把改動前 `exportRealizedGains()`／`exportAssetTransactions()`／
    `exportCommodityPrices(...)`／`exportExchangeRates(...)`／`exportIndexDaily(...)` 的 bytes 各寫成
    `backend/src/test/resources/golden/<name>_before.xlsx` 並提交；新測試以 POI 讀 golden 檔逐格比對。
    （**用 `origin/main` 而非 `main`**：本專案多個 worktree 共用同一個 local `main` ref，它可能被別的
    session 推進或根本沒 checkout。）不要從改動後的程式碼抄期望值——那樣測試只會確認「程式碼等於它自己」。
  - **(c1) 牆鐘時間戳**：實測**本任務這五份分頁一格牆鐘都沒有**
    （`ExcelExportService` 全檔只有兩處：`:152-153` 屬 `writeCurrentSummarySheet`（完整匯出，本次不接 doc）、
    `:655-656` 屬 `writeLiveAssetsSheet`（t271 範圍）），故本條對 t270 是防禦性條款：
    若實作後發現有牆鐘格，比對時以「該格存在 ＋ 型別 ＋ 樣式 ＋ 符合 `yyyy-MM-dd HH:mm:ss` 正則」
    取代值相等（Requirement 55 已登錄的具名例外三）。
    **不要為此在本任務引入 `Clock` 注入**——那是跨十個匯出點的獨立重構，超出本需求範圍。
  - **(c2) 產 golden file 時必須固定輸入**：六個資料來源
    （`gainRepo`／`assetTxRepo`／`commodityHistRepo`／`rateHistRepo`／`twseIndexHistRepo`／`usIndexHistRepo`）
    一律以**同一份 fixture** mock，新測試沿用同一份。新舊兩支測試 fixture 不同時 golden 對不起來，
    整條零回歸比對就變成噪音。
  - **(d) 空字串 vs BLANK**：已實現損益「交易日期」與交易紀錄「交易日期」為 null 時，該格為
    **空字串格**（`getCellType() == STRING && getStringCellValue().isEmpty()`），
    **不是** BLANK 格（270.1.6 的探針；改成 null 會在此紅）。
  - **(e) 留白欄位為「無格」**：油價金價／匯率／指數三份的無值格為 **`row.getCell(i) == null`**，
    且該列 `getLastCellNum()` 與改動前相同（`omitNullCells = true` 的探針）。
    **不是 BLANK 格**——寫成 BLANK 的實作會在此紅，且會與 (c) 的逐格基準比對互相打架。
    JSON 那一側該欄仍為 `null`（不是缺 key）。
  - **(f) JSON 型別**：數值欄為 JSON number 非字串、無值欄為 JSON null 非 `0`／`""`、
    `BigDecimal` 精度未被截掉（例：匯率 `32.1054` 輸出為 `32.1054` 而非 `32.11`）。
  - **(g) 完整匯出零回歸**：`exportFull()` 產出的活頁簿仍含「當前彙總」＋各快照分頁＋「已實現損益」，
    且已實現損益分頁**逐列逐格**與 `exportRealizedGains()` 的那一份相同（270.1.4 的探針）。
  - **(h) Drive 兩份**：以 `RcloneClient` 介面替身注入（**不實際連網**），斷言一次排程匯出呼叫 `copyTo`
    兩次、目標子路徑相同、檔名分別為 `.json` 與 `.xlsx`；狀態欄符合 t269 269.4.6 的字串契約
    （`xlsx …／json …`）。
  - **(i) 既有測試一併更新——範圍只有交易紀錄那一支。** 實測（`grep -ranl` 於
    `backend/src/test/java`）：本任務五個匯出點中，**只有交易紀錄有既有測試類**
    （`AssetTransactionExportScheduleServiceTest`、`AssetTransactionExcelExportTest`）；
    **已實現損益／匯率／指數／油價金價四個匯出點目前沒有任何既有測試類**，(a)～(h) 對這四點是**新建**。
    兩支的變紅原因**不同，處置也相反**：
    - `AssetTransactionExportScheduleServiceTest`：對 `lastRunStatus` 前綴的斷言會變紅，
      **這是預期變更**，改成新格式（`xlsx …／json …`）。
    - `AssetTransactionExcelExportTest`：它用 `@InjectMocks` 建 `ExcelExportService`，
      本任務為該 service 新增 `ExcelDocRenderer` 依賴後會被注入 `null` 而 NPE。
      **修法是注入一個真正的 `ExcelDocRenderer` 實例（不是 mock）**——
      **它的分頁名／17 欄表頭／列數／儲存格值等斷言一字都不得改**：那是本任務唯一的既有逐格守門，
      （原為 15 欄；併入 main 時 Task 268 在「台幣成交金額」後插入「手續費」「證交稅」兩欄，
      該測試的 `EXPECTED_HEADERS` 與 `getCell(9)`／`getCell(10)` 斷言由 main 側帶入，同樣一字不改。）
      改掉等於把守門拆了。**那些斷言若在注入修好之後仍然紅，就是真回歸，要改的是實作不是測試。**
    **`ExportScheduleGdriveTest` 與 `TradingExportGdriveTest` 屬 t271 範圍**
    （前者測 `ExportScheduleService`＝資產總覽，後者 mock 的是 `TradingRadarExportService`／
    `TradingCalendarExportService`），**本任務不得動它們**。實作前先跑一次全測試確認受影響清單。
  - **(j) owner 隔離未回歸**：`exportXxxForOwner(ownerId)` 產出的兩份檔都只含該 owner 的資料
    （交易紀錄與已實現損益兩份 owner-scoped 的匯出各一條）。
  - **(k) 路徑逃脫**：交易紀錄排程名含 `/` 或 `..` 時擲 `IllegalArgumentException`、目錄內不產生任何檔
    （270.2.4 的探針）。

## 驗證

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

確認五支服務都不再有自己的落檔實作（應輸出 0）：

```bash
grep -c 'Files.write\|ATOMIC_MOVE' backend/src/main/java/com/steven/assets/service/RealizedGainExportScheduleService.java backend/src/main/java/com/steven/assets/service/ExchangeRateExportScheduleService.java backend/src/main/java/com/steven/assets/service/IndexExportScheduleService.java backend/src/main/java/com/steven/assets/service/CommodityExportScheduleService.java backend/src/main/java/com/steven/assets/service/AssetTransactionExportScheduleService.java
```

建置與部署（前後端都動到）：

```bash
docker compose -p asset-management build --no-cache business-services frontend
```

```bash
docker compose -p asset-management up -d --no-deps --force-recreate business-services frontend && docker compose -p asset-management restart bff
```

（`restart bff` 不可省略：recreate business 會換 IP，BFF 握著舊 IP 會回 500 且 Docker DNS TTL 600 秒內不自癒。
compose 的 service 名是 `bff`，容器名是 `asset-bff`。）

端到端實測（免走 Google 登入，在 business 容器內以 X-User 標頭模擬租戶）：

```bash
docker exec asset-business-services curl -s -X POST -H 'X-User-Id: 1' -H 'X-User-Role: ADMIN' -H 'X-User-Status: ACTIVE' http://localhost:8080/api/realized-gains/export/run-now
```

```bash
docker exec asset-business-services sh -c 'ls -l /home/steven/input/ | grep 已實現損益'
```

（最後一行必須看到**同一主檔名**的 `.json` 與 `.xlsx` 各一份。）

## 完成報告

**狀態：已完成——但 270.3 的「run-now 成功提示顯示兩個落點」實際未實作（2026-08-02 發現，由 t282 承接）；
檔名說明文案那一半確實完成。** 本報告下方的檔案表只涵蓋文案那一半。

| 檔案 | 改動 |
|---|---|
| `ExcelExportService.java` | 五支 `writeXxxSheet` → 回傳 `ExportDoc.Sheet` 的 builder；新增 public `realizedGainsDoc(ForOwner)`／`assetTransactionsDoc(ForOwner)`／`commodityPricesDoc`／`exchangeRatesDoc`／`indexDailyDoc`；既有 `byte[]` 方法簽章不變、改走 renderer；`buildWorkbook()` 改用 `excelDocRenderer.writeSheet(wb, realizedGainsSheet())` |
| 五支 `*ExportScheduleService.java` | 注入兩個 renderer ＋ `DualFormatExportWriter`；`writeToDir`／`writeAtomically` 刪除；新增 `writeDual`／`applyGdriveStatus`；狀態欄改寫 `DualResult` |
| 四支 `*ExportDto.java` | `RunNowResponse` 加 `jsonPath`／`jsonSizeBytes`／`jsonGdrivePath`（既有三欄語意不變、指 xlsx） |
| 五支 view | 排程檔名文案改為明示兩份 |
| `DualFormatSingleTableExportTest`（新） | 11 個：五份 Excel 逐列逐格對 golden、兩種 null 語意、JSON 型別、完整匯出未分裂 |
| `SingleTableScheduleServiceDualFormatTest`（新） | 8 個：四支排程服務的主檔名一致性／單次查詢／Drive 兩份／owner 隔離／失敗語意 |
| `src/test/resources/golden/*.xlsx`（新） | 5 份零回歸基準，由 `origin/main` 的臨時 worktree 以相同 fixture 產出 |
| 既有兩支測試 | `AssetTransactionExcelExportTest` 改注入真的 `ExcelDocRenderer`（`@Spy`），**斷言一字未改**；`AssetTransactionExportScheduleServiceTest` 建構子補三參數、探針移到 `xxxDoc` 並加 `byte[]` 方法的 `never()` |

**驗證輸出：**
- `mvn -f backend/pom.xml test` → **Tests: 434, Failures: 0, Errors: 0**
- `vite build` → 通過
- 五支服務 `grep -c 'Files.write\|ATOMIC_MOVE'` → 全 0
- t271／t272 範圍的 9 個檔案（`ExportScheduleService`／`TradingRadar*`／`TradingCalendar*`／四支 view）→ **改動 0 次**
- `arch-auditor` 查證：0 critical、2 major、3 minor，均已修

**與原計畫的偏差及原因：**
1. **`omitNullCells` 的判定與規格一致但需逐份確認**：油價金價／匯率／指數為 `true`（既有
   `if (x != null) cell(...)`＝不建格），已實現損益／交易紀錄為 `false`（既有逐格無條件寫＝BLANK 格）。
   golden file 比對含 `getLastCellNum()` 斷言，兩種語意混用會立刻紅。
2. **golden file 的可信度已被反證**：`arch-auditor` 以 `CellStyle` 索引指紋確認 golden 出自舊實作
   （舊順序 money=3／num4=4，新 renderer 為 money=5／num4=6），不可能是「程式碼等於它自己」。
3. **`indexLabel("TWSE")` 實際是「台股大盤」**（不是我原先寫測試時假設的「加權指數」），已更正。
4. **`RcloneClient.copyTo` 是 4 參數**（`remote, localFile, subpath, destFileName`），測試斷言已對齊。
5. **`year` 是 `tradeDate` 衍生的 `@Transient` getter、無 setter**，golden 產生器的 fixture 據此調整。
6. **三支服務的未使用 import、一段孤兒 javadoc、一處 `(int)` 窄化轉型**（`arch-auditor` 的 minor）已清。
7. **`writeDual`／`applyGdriveStatus` 在五支服務各一份**，未再抽共用：五個 schedule entity 無共同介面，
   抽共用需先為它們建抽象；且淨帳上重複量是**下降**的（刪 4 份 `writeAtomically` ＋ 1 份 `writeToDir`）。
8. **併入 main 後交易紀錄由 15 欄變 17 欄。** merge `origin/main`（71bf6a29）時，Task 268 在
   「台幣成交金額」（index 8）之後插入「手續費」「證交稅」，兩欄 `Format.MONEY`（對齊 main 的 `st.money`）。
   衝突解法是「**形狀取本任務的 `ExportDoc`、欄位內容取 main 的 17 欄**」——不得為了讓 golden 過而回退欄位。
   `golden/asset_transactions.xlsx` 已用**合併後的 origin/main** ＋ 與 `DualFormatSingleTableExportTest.txs()`
   逐字相同的 fixture 重產（表頭列 17 格已驗）。fixture 第一筆補上 `fee`／`transactionTax` 的值、
   第二筆維持 null，兩欄的 MONEY 格式與 BLANK 語意各驗得到一邊。
   `AssetTransactionExcelExportTest` 由 main 帶入 17 欄斷言與 `getCell(9)`／`getCell(10)` 的 BLANK 檢查，
   它與 golden 互為獨立守門：前者驗「有值」、後者驗「逐格逐樣式」。
