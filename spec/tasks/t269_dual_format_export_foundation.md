# [t269] 雙格式匯出的地基：`ExportDoc` 中介模型、兩個 renderer、雙檔落地元件

**對應 Requirements:** Requirement 55（十個自動匯出點的檔案一律同時產出 `.json` 與 `.xlsx` 兩份，主檔名完全相同、只差副檔名；兩份必須來自同一次資料查詢；既有 Excel 的內容不得減損，判準是「逐列逐格 ＋ 儲存格樣式」）
**前置任務:** 無
**後續任務:** t270（五個單表匯出接上）、t271（資產總覽／交易雷達／交易日曆）、t272（警示觸發／爬蟲公開資訊加 Excel）
**Liquibase changeset:** 無（本需求全程零 schema 變更）

## 背景

系統目前有十個自動匯出點，格式是歷史累積出來的：七個只有 `.xlsx`、兩個只有 `.json`、交易日曆讓使用者
在 json／excel 之間**二選一**。使用者要求全部同時產出兩種格式、主檔名相同。

**為什麼需要中介模型，而不是「產完 xlsx 再轉成 json」。** 七份 Excel 的產生方式是
`ExcelExportService`（997 行）與 `TradingRadarExportService`（279 行）**直接對 POI 逐格
`cell(row, i, value, style)`**，沒有任何中介資料結構。若改用「把 workbook 讀回來轉 JSON」：

- 只能得到「工作表→列→格」的**位置式** JSON，下游得自己數第幾格，欄位一改就靜默錯位；
- Excel 的顯示格式（`#,##0.00`）會把 `BigDecimal` 精度截掉，JSON 拿到的是**顯示值不是資料值**。

故採「查詢結果先落成 `ExportDoc` → 兩個 renderer 各自產出」。

**本任務只交付地基，不接任何既有匯出點**——做完之後系統行為與現在完全一致（既有七份 Excel、兩份 JSON、
交易日曆二選一都不變），這是正確狀態（先例：t242 同樣是「地基先行、功能未啟用」）。
本任務的獨立驗收判準是：模型與兩個 renderer 的單元測試綠、既有全部測試無回歸。

> **模型形狀是設計評審後修正過的。** 初版把 `Sheet` 定成固定的 `note`／`meta`／`tables` 三欄位，實測有
> **四處既有版面表達不出來**（下方 269.1 逐條列出）。現在改成「一張 sheet ＝ 一串 block」，render 順序
> ＝ list 順序，空行本身也是一種 block。**不要退回三欄位版本**——那會讓「既有 Excel 內容不得減損」跳票。

## 要做什麼

### 269.1 資料模型 `ExportDoc`

- [x] 269.1 新增 `backend/src/main/java/com/steven/assets/service/export/ExportDoc.java`。

**先讀懂為什麼是 block list。** 以下四處既有版面，用固定的 note／meta／tables 三欄位表達不出來
（行號皆為 `backend/src/main/java/com/steven/assets/service/ExcelExportService.java`）：

| 既有版面 | 固定三欄位為何表達不出 |
|---|---|
| 「當前即時資產」的 `基準快照日期／美元匯率／即時總資產` 是**同一列六格**（`:667-673`，label,value,label,value,label,value） | 拆成三個 `Kv` 會變三列；做成 3 欄 `Table` 會變「表頭列＋資料列」兩列 |
| 「即時彙總」四列（`summaryRow`，`:190-195`）**沒有表頭列**，且第 0 欄標籤走 `st.head`、第 1 欄值走 `st.money` | `Table` 必寫表頭列，且資料列無法指定某一欄用 head 樣式 |
| 早退分支（`:651-663`）輸出**三列**：`當前即時資產`(section) ／ `匯出時間`＋值 ／ `尚無資產快照`(**無樣式**)，並 autoSize **六欄** | `Sheet` 只有一個 `note` 名額，裝得下標題就裝不下「尚無資產快照」；且無 tables 時算不出 autoSize 欄數 |
| 「當前即時資產」的 `匯出時間` 列與下一列之間**沒有空行**（`:654-673` 中間無 `r++`），空行在那之後才出現（`:675`） | 「meta 非空就空一列」的通則在這張分頁會多插一列，使其後全部位移 |

```java
package com.steven.assets.service.export;

public record ExportDoc(String title, List<Sheet> sheets) {

    /** 一張工作表。autoSizeColumns＝結束時要 autoSize 的欄數（既有各分頁不同，不可由 headers 推導）。 */
    public record Sheet(String name, List<Block> blocks, int autoSizeColumns) {}

    public sealed interface Block permits Line, KvRow, Table, Blank {}

    /** 單格文字列（標題列、提示列）。 */
    public record Line(String text, LineStyle style) implements Block {}

    /** 同一列的 label／value 交錯：label 走 head 樣式、value 走各自 Format。 */
    public record KvRow(List<Kv> cells) implements Block {}

    /**
     * 表格。name 非 null 時前置一列 section 標題（樣式為 nameStyle）。
     * showHeader=false 時不寫表頭列（JSON 仍以 headers 當 key）。
     * labelFirstColumn=true 時，資料列第 0 欄走 head 樣式（summaryRow 的排版）。
     * omitNullCells=true 時，資料列中值為 null 的格「完全不建」（對應既有 `if (x != null) cell(...)` 的分頁）；
     * false 時建立 BLANK 格（對應既有 `cell(row, i, null, style)` 的分頁）。兩者讀回時可分辨，不可混用。
     */
    public record Table(String name, LineStyle nameStyle,
                        List<String> headers, boolean showHeader, boolean labelFirstColumn,
                        boolean omitNullCells,
                        List<Format> columnFormats, List<List<Object>> rows) implements Block {}

    /** 空行：只遞增列索引、**不建立 Row**（與既有裸 `r++` 等價）。 */
    public record Blank() implements Block {}

    public record Kv(String key, Object value, Format format) {}

    /** 文字列／表格標題列的樣式。PLAIN＝不套任何 CellStyle。 */
    public enum LineStyle { PLAIN, HEAD, SECTION_13, SECTION_12, WARN }

    /** 只影響 Excel 儲存格；JSON renderer 一律忽略（BOOL_ZH／LIST_LINES 亦然）。 */
    public enum Format { TEXT, MONEY, NUM2, NUM4, NUM6, DATE, TIMESTAMP, BOOL_ZH, LIST_LINES }
}
```

- [x] 269.1.1 **值一律以原始型別放進 `rows`／`Kv.value`**（`BigDecimal`／`LocalDate`／`LocalDateTime`／
      `String`／`Boolean`／`Integer`／`List<String>`／`null`），**不得在建構期就轉成字串**。理由：轉成字串
      之後 JSON 就永遠拿不回型別與原精度，這是「JSON 的數值一律是 JSON number、保留資料庫原精度」的唯一防線。
- [x] 269.1.2 **`Format` 只給 Excel 用。`JsonDocRenderer` 不得讀取 `Format`／`LineStyle` 的任何值**——
      這是「顯示格式不得滲進 JSON」在型別層級的保證。`BOOL_ZH`（Excel 寫「是」／「否」）與
      `LIST_LINES`（Excel 以 `\n` 串接）尤其如此：JSON 該拿到 `true`／`false` 與字串陣列。
- [x] 269.1.3 **建構期驗證**（在 `Table` 的 compact constructor）：
  - 每一列 `rows.get(i).size() == headers.size()`，不符即擲 `IllegalArgumentException`，訊息含列索引與兩個長度。
  - `columnFormats` 為 null 時視為全 `TEXT`；非 null 時長度必須等於 `headers.size()`。
  - **`headers` 內不得有重複欄名**，有就擲 `IllegalArgumentException`。理由：JSON renderer 把 headers 與
    row zip 成 map，重複 key 後者會覆蓋前者、靜默吃掉一欄，而 Excel 那份仍有兩欄——兩份檔就不一致了。
- [x] 269.1.4 list 欄位做防禦性複製，但 **`rows` 的內層 list 不得用 `List.copyOf`**——它不接受 null 元素，
      而資料列裡 null 是合法值。改用 `Collections.unmodifiableList(new ArrayList<>(row))`。
      **這一條必須照做**：用 `List.copyOf(row)` 會在任何一格為 null 時 `NullPointerException`，
      而「沒有值就是 null」正是本需求明文要求的行為。

### 269.2 `ExcelDocRenderer`

- [x] 269.2 新增 `service/export/ExcelDocRenderer.java`（`@Component`），API 兩支：
  - `byte[] render(ExportDoc doc)` — 產一整份 xlsx。
  - `void writeSheet(Workbook wb, ExportDoc.Sheet sheet)` — 把單一 sheet 寫進呼叫端已開好的 workbook。
    **`Styles` 由 renderer 內部依該 workbook 建立並快取，呼叫端不傳**（`Styles` 維持 private，
    不對外曝露）。這一支不可省略：`ExcelExportService.buildWorkbook()`（手動「完整匯出」）的活頁簿裡，
    「已實現損益」分頁與排程匯出的那一份是**同一支** `writeRealizedGainsSheet`；t270 把它改走 `ExportDoc`
    之後，完整匯出必須能把 doc 產的 sheet 嵌進自己的混合活頁簿，否則同一張分頁會分裂成兩份實作。
- [x] 269.2.1 `Styles` 內部類別共**八種**（下表逐列數，不要少算——`money` 與 `num2` 格式字串相同但**必須是
      兩個獨立的 `CellStyle` 實例**，最容易被誤合併掉）。**不是「兩支既有服務的聯集」——那個前提是錯的**：兩支的
      `section` 字級不同（13pt vs 12pt），取聯集會靜默改掉其中一支。實測定義如下，逐一照抄：

  | 欄位 | 實測定義 | 出處 |
  |---|---|---|
  | `head` | 粗體 ＋ `HorizontalAlignment.CENTER` | `ExcelExportService.java:970-974`（`TradingRadarExportService.java:257-261` 相同） |
  | `section13` | 粗體 ＋ `setFontHeightInPoints((short) 13)` | `ExcelExportService.java:976-980` |
  | `section12` | 粗體 ＋ `setFontHeightInPoints((short) 12)` | `TradingRadarExportService.java:263-267` |
  | `warn` | 粗體 ＋ `setColor(IndexedColors.RED.getIndex())` | `TradingRadarExportService.java:269-273` |
  | `money` | `dataFormat("#,##0.00")` | `ExcelExportService.java:982-983` |
  | `num2` | `dataFormat("#,##0.00")`（**與 money 同格式字串**，Task 200 為漲跌／漲跌幅／月季年線另開的獨立 `CellStyle` 實例；**不可合併成同一個物件**，以免日後改 money 波及） | `ExcelExportService.java:988-990` |
  | `num4` | `dataFormat("#,##0.0000")` | `ExcelExportService.java:985-986` |
  | `num6` | `dataFormat("#,##0.000000")` | `ExcelExportService.java:992-994` |

  `LineStyle` → 樣式對應：`PLAIN`→不套（傳 null）、`HEAD`→`head`、`SECTION_13`→`section13`、
  `SECTION_12`→`section12`、`WARN`→`warn`。
- [x] 269.2.2 **逐 block 依序寫，renderer 不插入任何 doc 沒指定的列**（空行一律由 `Blank` block 表達）：
  - `Line` → 一列，第 0 格寫 `text`，樣式依 `LineStyle`。
  - `KvRow` → 一列，第 `2i` 格寫第 i 個 `Kv` 的 key（`head` 樣式）、第 `2i+1` 格寫 value（依 `Kv.format`）。
  - `Table` → 若 `name != null` 先寫一列 section 標題（樣式 `nameStyle`）；若 `showHeader` 再寫表頭列
    （每格 `head`）；再逐列寫資料（`labelFirstColumn=true` 時第 0 欄走 `head`，其餘依 `columnFormats`）。
    **`omitNullCells=true` 時，值為 null 的格連 `createCell` 都不做**（見 269.2.3）。
  - `Blank` → **只 `r++`，不 `createRow`**。既有四處空行都是裸 `r++`（`ExcelExportService.java:675`／
    `:685`／`:707`／`:725`），POI 不會為它們寫出 `<row>` 元素；若改成 `createRow(r++)`，讀回時
    `sheet.getRow(該索引)` 會由 `null` 變成非 null、檔案 bytes 也會變。這是可觀測的版面變動。
  - 寫完所有 block 後 `for (int i = 0; i < sheet.autoSizeColumns(); i++) autoSizeColumn(i)`。
- [x] 269.2.3 **`null` 值有兩種既有行為，必須都支援、不可統一。** 既有 `cell()`
      （`ExcelExportService.java:950-957`，`TradingRadarExportService.java:238-245` 語意相同）是：
      ```java
      Cell c = row.createCell(col);
      if (value == null) return;          // ← 建了格、沒寫值、也沒套 style
      …
      if (style != null) c.setCellStyle(style);
      ```
  - **有呼叫 `cell(row, i, null, style)` 的分頁** → `createCell` 之後不 `setCellValue`、
    **且不 `setCellStyle`**（既有在 `return` 就離開了，style 那一行根本沒跑到）→ 產生
    **存在的 BLANK 格、且無樣式**。這是 `omitNullCells=false` 的行為。
  - **值為 null 時根本不呼叫 `cell()` 的分頁** → 該格**完全不存在**。實測有三處：
    油價金價（`:385-387` `if (v[i] != null) cell(...)`）、匯率（`:444-446`）、指數（`:516-518`）。
    這是 `omitNullCells=true` 的行為。
  - **兩者在 POI 讀回時可分辨**（`row.getCell(i)` 為 `null` vs `BLANK`、`getLastCellNum()` 不同），
    把它們統一成任一種都是可觀測的版面變動。**不要為了「模型乾淨」而合併。**
- [x] 269.2.3.1 **`omitNullCells` 只影響 Excel，`JsonDocRenderer` 一律忽略它**——JSON 兩種情形都輸出
      `null`（該 key 存在且為 JSON null）。
- [x] 269.2.4 值的型別對應：
  - `BigDecimal` → `setCellValue(bd.doubleValue())`；其他 `Number` → `setCellValue(n.doubleValue())`
    （**與既有 `cell()` 完全一致**，不得改成 `setCellValue(BigDecimal)` 之類的別種寫法）。
  - `LocalDate`（`Format.DATE`）→ **寫成 ISO-8601 字串 cell，不寫成 Excel date cell**。
  - `LocalDateTime`（`Format.TIMESTAMP`）→ 寫成 `yyyy-MM-dd HH:mm:ss` 字串 cell。
  - `Boolean`（`Format.BOOL_ZH`）→ 字串 `"是"`／`"否"`；`Boolean` 搭其他 Format → `toString()`。
  - `List<String>`（`Format.LIST_LINES`）→ 以 `\n` 串接成單一字串 cell。
  - **`BOOL_ZH` 與 `LIST_LINES` 的 `null` 值一律 `setCellValue("")`（空字串格），
    不落入 269.2.3 的通用 null 規則**——既有 `TradingRadarExportService.bool()`／`list()`
    在缺值時回的就是 `""`（`:223-236`），寫成 BLANK 格是版面變動。JSON 側仍輸出 `null`。
  - 其餘 → `setCellValue(value.toString())`。
  - **日期一律寫成文字是既有的刻意決定，不可改成 date cell**：既有註解寫明「避免 Excel 依開啟端時區
    重新詮釋 date cell 而偏移一天」（`writeCommoditySheet`／`writeExchangeRateSheet`／`writeIndexDailySheet` 三處皆有）。
- [x] 269.2.5 **`TEXT`／`DATE`／`TIMESTAMP`／`BOOL_ZH`／`LIST_LINES` 五種 Format 一律不套 `CellStyle`**
      （傳 null），只有 `MONEY`／`NUM2`／`NUM4`／`NUM6` 才套。既有這些格都是 `cell(row, i, v, null)`；
      套一個 style 會讓儲存格帶上格式，與既有檔案不同。

### 269.3 `JsonDocRenderer`

- [x] 269.3 新增 `service/export/JsonDocRenderer.java`（`@Component`，注入 Spring 既有的 `ObjectMapper`），
      API：`byte[] render(ExportDoc doc)`。走同一串 block 分桶：`Line` → `lines[]`、`KvRow` 的每個 `Kv`
      併進 `meta{}`、`Table` → `tables[]`、`Blank` → 忽略。輸出結構：

```json
{
  "title": "資產總覽",
  "generatedAt": "2026-08-01T08:00:00+08:00",
  "sheets": [{
    "name": "當前即時資產",
    "lines": ["當前即時資產"],
    "meta": { "匯出時間": "2026-08-01 08:00:00", "基準快照日期": "2026-08-01", "美元匯率": 32.1054 },
    "tables": [
      { "name": "銀行存款",
        "rows": [{ "銀行": "國泰世華", "存款類型": "定存", "幣別": "TWD",
                   "原幣金額": 1000000, "台幣金額": 1000000, "備註": null }] }
    ]
  }]
}
```

- [x] 269.3.1 `rows` 一律是**物件陣列**（headers 與該列 zip 成 `LinkedHashMap`，保序），
      `showHeader=false` 的 Table **JSON 仍以 headers 當 key**。
      **不得**輸出 `[["國泰世華","定存",…]]` 這種依位置解析的陣列。
- [x] 269.3.2 `generatedAt` 為 `ZonedDateTime.now(ZoneId.of("Asia/Taipei"))` 的 ISO-8601 字串（含 `+08:00`）。
- [x] 269.3.3 型別規則（本任務的核心價值，逐條測）：
  - `BigDecimal` → JSON **number**，**保留原精度**，不得經過任何 `setScale`／`String.format`／千分位。
  - `LocalDate` → `"yyyy-MM-dd"`；`LocalDateTime` → `"yyyy-MM-ddTHH:mm:ss"`（**ISO local，不加位移**——
    它本來就沒有時區資訊，加位移等於憑空捏造）。**只有 `generatedAt` 這種本身帶時區的值才輸出 `+08:00`**
    （269.3.2）。requirements.md 的 AC 與本條一致，range 內若出現第三種寫法以本條為準。
    **注意上方 JSON 範例中的 `匯出時間` 值是示意，實際輸出為 ISO local 的 `T` 分隔格式，不是空白分隔**
    ——空白分隔是 Excel 那一側 `Format.TIMESTAMP` 的呈現。
  - `null` → JSON `null`。**不得**以 `0`、`"-"`、`""` 充數。
  - `Boolean` → JSON boolean（**不是**字串「是」／「否」，那是 `Format.BOOL_ZH` 的 Excel 呈現）。
  - `List<String>` → JSON 字串陣列（**不是** `\n` 串接後的單一字串）。
- [x] 269.3.4 以 `writerWithDefaultPrettyPrinter()` 輸出、UTF-8 bytes。中文**不得**被 escape 成 `\uXXXX`
      （Jackson 預設即不 escape，但要有測試守門——既有 `public_info_*.json` 是可讀中文，新的七份不該不一致）。

### 269.4 雙檔落地元件 `DualFormatExportWriter`

- [x] 269.4 新增 `service/export/DualFormatExportWriter.java`（`@Component`，注入 `GdriveOutputSupport`）：

```java
public record DualResult(Path jsonFile,          // 該份 render 或寫檔失敗時為 null
                         Path xlsxFile,          // 同上
                         String localStatus,      // 已截斷至 500 字元內
                         String gdriveStatus,     // 已截斷至 512 字元內；未啟用 Drive 時為 null
                         String xlsxGdrivePath,   // Drive 落點，供 run-now 回報；未上傳為 null
                         String jsonGdrivePath) {}

DualResult write(Long ownerUserId, Path dir, String baseName,
                 byte[] jsonBytes, byte[] xlsxBytes,
                 boolean gdriveEnabled, String gdriveSubpath) throws IOException;
```

- [x] 269.4.1 **`baseName` 不含副檔名**——這是「主檔名完全相同」的結構性保證：呼叫端**沒有機會**讓兩份
      檔名分岔。落點為 `dir.resolve(baseName + ".json")` 與 `dir.resolve(baseName + ".xlsx")`。
      **不得**在方法簽章上開放兩個檔名參數。
- [x] 269.4.1.1 **`jsonBytes`／`xlsxBytes` 允許為 `null`，代表「那一份 render 失敗」。** 本元件收到 null
      時跳過該份、照寫另一份，並在 `localStatus` 記 `json render 失敗`（或 `xlsx render 失敗`）。
      **這一條是 Requirement 55「JSON render 失敗不得中斷 Excel 的寫入」那條驗收條件的唯一落腳點**——
      render 發生在呼叫端、在本元件之前，若簽章不允許 null，render 擲例外時根本走不到這裡，那條 AC
      就沒有任何實作承接。呼叫端（t270／t271／t272）的兩支 render 各自 try/catch，失敗的那一份傳 null。
      **兩份都是 null 時**：不寫任何檔、`localStatus` 記兩份都失敗、**不擲例外**。
- [x] 269.4.2 **`baseName` 的路徑逃脫重驗在本元件內做**：不得含 `/`、`\`、`..` 或任何路徑分隔字元；
      落點求出後重驗 `file.normalize().startsWith(dir)`，為假即擲 `IllegalArgumentException`。
      **這是搬移既有防線，不是新增**——`AssetTransactionExportScheduleService.writeToDir`（`:328-331`）
      現有這段，理由是「排程名會進檔名，而 DB 值可能被繞過 API 以 psql 直改」。t270 要刪掉那支
      `writeToDir`，若不先搬過來就是靜默弄丟一道安全檢查。
- [x] 269.4.3 每一份各自「同目錄唯一 tmp ＋ `ATOMIC_MOVE`」：tmp 名為
      `baseName + "." + UUID.randomUUID() + ".tmp"`，`Files.move(tmp, file, ATOMIC_MOVE)`，
      接 `AtomicMoveNotSupportedException` 時 fallback `Files.move(tmp, file, REPLACE_EXISTING)`，
      `finally` 一律 `Files.deleteIfExists(tmp)`。理由：下游程式可能正在讀同一個檔。
- [x] 269.4.4 **一份失敗不得讓另一份不寫**：兩份各自 try/catch，**都要嘗試**。`localStatus` 如實記到
      副檔名層級，例：`xlsx 成功：/home/steven/input/資產總覽_1_20260801.xlsx（12345 bytes）／json 失敗：<原因>`。
      **本方法不得因為其中一份失敗就擲例外**（只有 `dir` 建不出來、或 269.4.2 的 `baseName` 不合法這種
      兩份都不可能成功的情形才擲）。
      **失敗的那一份，`DualResult` 的對應 `Path` 欄位回 `null`**（render 失敗與寫檔失敗都一樣）——
      呼叫端要判斷「兩份都成功」時，判準就是 `jsonFile != null && xlsxFile != null`。
      不定義這個的話，三個呼叫端沒有任何方式得知該不該上傳 Drive、run-now 該回報什麼。
- [x] 269.4.5 **跨兩個檔案的原子性做不到，明文接受**：極短暫的時間窗內可能只有一份是新的。
      **不得**實作「兩份都成功才算成功、否則刪掉已寫的那一份」——刪檔路徑是新的風險，且磁碟滿時會讓
      使用者連舊檔都失去。本元件**不含任何刪除目標檔的程式路徑**（只刪自己建的 tmp）。
- [x] 269.4.6 **Drive：兩份本機都寫成功才上傳**（順序不可顛倒，沿用既有決定：本機那一份是既有的留存機制）。
      對同一個 `gdriveSubpath` 呼叫 `GdriveOutputSupport.syncQuietly(ownerUserId, gdriveSubpath, file)`
      **兩次**（xlsx 一次、json 一次），兩個 `SyncResult.status()` 合併成一句，**必須能分辨是哪一份成功、
      哪一份失敗**。字串契約（t270／t271／t272 的斷言都依賴它，故在此寫死）：
      `"xlsx " + <xlsx 的 status> + "／json " + <json 的 status>`。
      **兩半必須各自先截斷再合併，不可合併後才從尾端截**：`GdriveOutputSupport` 已把單邊 status 截到
      512，兩半相加超過 1000，合併後截尾會把 `／json …` **整段切掉**——那正好違反本條「必須能分辨是
      哪一份」的契約。扣掉 `"xlsx "` 與 `"／json "` 共 12 字元的固定開銷後對半分。`localStatus` 同理。
      `gdriveEnabled=false` 時回傳 `gdriveStatus=null`、兩個 Drive 落點欄位皆 null（不碰狀態欄）。
- [x] 269.4.7 **截斷在本元件內做，不是呼叫端做。** `localStatus` ≤ **500** 字元、`gdriveStatus` ≤ **512** 字元。
      這兩個上限實測自運行中 DB（`docker exec asset-postgres psql -U assets -d assets -c '\d export_schedule_setting'`
      → `last_run_status varchar(500)`、`gdrive_last_status varchar(512)`）。
      **十個匯出點各有自己的設定表，實作時必須逐表確認這兩欄的長度**——若有任一表更短，以最短者為準。
      截斷策略：**優先保留「成功／失敗」與副檔名標記**，路徑過長時截尾加 `…`。
      漏截斷的後果是 JPA save 擲 `DataException`，把一次**本機其實已寫成功**的匯出記成失敗。
- [x] 269.4.8 **本任務不修改任何既有匯出服務。** `ExcelExportService`／`TradingRadarExportService`／
      九支 `*ExportScheduleService`／`NewsPoller` 全部一行不動。接線是 t270／t271／t272 的事。
      **兩份既有 `Styles` 的下場不同，不要一概而論**：
  - **`ExcelExportService.Styles` 保留、不是死碼**——`writeCurrentSummarySheet`／`writeSnapshotSheet`／
    `summaryRow` 等未接 doc 的分頁還在用它。它的 `head`／`money`／`num2`／`num4`／`num6` 必須與
    `ExcelDocRenderer.Styles` 逐字相同，任一方改動須同步——這一點寫進 `ExcelDocRenderer.Styles` 的 Javadoc。
  - **`TradingRadarExportService.Styles`（欄位只有 `head`／`section`／`warn`／`num2`，**沒有** money／num4／num6）
    在 t271 271.2 把三張分頁全部改走 `ExportDoc` 之後會變成零使用者的死碼**，連同該服務的 private
    `cell()` 與 `autosize()` 一併由 t271 刪除。**本任務不刪**（本任務不碰既有服務），但 t269 的
    Javadoc 不要寫「兩份 Styles 都保留」那種日後會變假的話。

### 269.5 單元測試

> **測試檔命名慣例（本任務兩個新測試檔共用）：** 一律新建於
> `backend/src/test/java/com/steven/assets/service/export/`，類名＝受測類名加 `Test` 後綴
> （模型與兩個 renderer 合為一支測試檔、`DualFormatExportWriter` 一支）。
> 本任務刻意**不在 spec 裡寫死這些新測試類的識別字**——`scripts/spec-check.sh` 的 B5 檢查會把
> 「spec 提到但全樹不存在的 `Test` 結尾類名」判為 BLOCK（Task 160 前例：spec 宣稱了不存在的驗證）。
> 全新元件必然先有 spec 後有測試檔，寫死類名會讓實作前的閘門永遠過不了。**這不代表可以少寫測試**：
> 下面每一條斷言都是必須交付的，實作完成後 B5 會自動看到那些檔案。

- [x] 269.5 新增「模型與 renderer」測試檔，涵蓋：
  - `Table` 列長不符 headers → 擲 `IllegalArgumentException`，訊息含列索引與兩個長度。
  - `headers` 有重複欄名 → 擲 `IllegalArgumentException`。
  - `rows` 內含 null 值 → **建構成功**（269.1.4 的探針；用 `List.copyOf` 的錯誤實作會在此 NPE）。
  - **JSON 型別**：`BigDecimal("32.1054")` → 輸出文字含 `32.1054`（**字串比對輸出的 JSON 文字**，
    不要只斷言 `readTree().decimalValue()` 相等——那對 `"32.1054"` 字串也會過）；
    `null` → 該 key 存在且 `isNull()`；`Boolean.TRUE` 搭 `BOOL_ZH` → JSON `true`（不是 `"是"`）；
    `List.of("a","b")` 搭 `LIST_LINES` → JSON 陣列（不是 `"a\nb"`）；
    `LocalDate.of(2026,8,1)` → `"2026-08-01"`；中文欄名與中文值未被 escape 成 `\uXXXX`。
  - **JSON 結構**：`rows` 為物件陣列、key 順序等於 `headers` 順序；`showHeader=false` 的 Table
    JSON 仍以 headers 當 key。
  - **Excel 逐格**：以 POI 讀回，斷言 `Line`／`KvRow`／`Table`／`Blank` 四型各自的列位置與格內容；
    **`Blank` 的那個列索引 `sheet.getRow(i) == null`**（269.2.2 的探針；用 `createRow` 的錯誤實作
    會讓它變成非 null），且後續 block 的列索引仍照常遞增。
  - **`omitNullCells` 兩種行為可分辨**：同一份資料、`omitNullCells=true` 時
    `row.getCell(i) == null` 且 `getLastCellNum()` 較小；`false` 時 `getCell(i)` 存在、
    `getCellType() == BLANK` **且 `getCellStyle()` 等於 workbook 預設 style**（269.2.3 的探針：
    既有 `cell()` 在 null 時連 style 都沒套）。JSON 兩種情形都輸出 `null`。
  - **`BOOL_ZH`／`LIST_LINES` 的 null**：Excel 為空字串格
    （`getCellType() == STRING && getStringCellValue().isEmpty()`）、JSON 為 `null`（269.2.4 的探針）。
  - **Excel 樣式**：`SECTION_13` 的格 `getCellStyle().getFont().getFontHeightInPoints() == 13`、
    `SECTION_12` 為 `12`、`WARN` 的 font color 為 `IndexedColors.RED.getIndex()`、
    `PLAIN` 的格 `getCellStyle()` 等於 workbook 預設 style（269.2.1／269.2.5 的探針）。
  - **`null` 產生 BLANK 格而非無格**：`row.getCell(i) != null && row.getCell(i).getCellType() == CellType.BLANK`
    （269.2.3 的探針；「不建格」的錯誤實作會在此紅）。
  - **`TEXT`／`DATE`／`TIMESTAMP`／`BOOL_ZH`／`LIST_LINES` 的格不套 style**（269.2.5 的探針）。
  - **`labelFirstColumn=true`**：資料列第 0 欄 `getCellStyle()` 等於 head 樣式、第 1 欄走 `columnFormats`。
  - **`autoSizeColumns`**：無 tables 的 sheet 仍能指定欄數（早退分支的探針）。
- [x] 269.5.1 新增「雙檔落地元件」測試檔（用 `@TempDir`，`GdriveOutputSupport` 以 Mockito mock），涵蓋：
  - 兩份落點的檔名去掉副檔名後 `assertEquals`（主檔名一致性的探針）。
  - 目錄內不得殘留任何 `.tmp`。
  - **`baseName` 含 `/`／`..` → 擲 `IllegalArgumentException`，且目錄內不產生任何檔**（269.4.2 的探針）。
  - **`jsonBytes = null`（render 失敗）時 xlsx 仍寫出**：斷言未擲例外、xlsx 存在、json 不存在、
    `localStatus` 同時含成功與 `json render 失敗` 字樣（269.4.1.1 的探針，也是 Requirement 55
    「一份失敗不得讓另一份不寫」那條 AC 的探針）；反向（`xlsxBytes = null`）同樣測一條；
    兩份都 null 時不寫任何檔且不擲例外。
  - **寫入階段一份失敗、另一份仍寫出**：構造 JSON 那一路的 `Files.move` 失敗——
    **在目標 json 路徑先 `Files.createDirectory(dir.resolve(baseName + ".json"))` 建一個同名目錄**。
    **不要用「唯讀檔」**：`rename(2)` 的權限檢查在**目錄**而非目標檔，唯讀的目標檔會被直接覆蓋成功，
    測不到東西。也**不要 `mockStatic(Files.class)`**——它會連 xlsx 那一路一起攔掉。
    斷言方法未擲例外、xlsx 存在、`localStatus` 同時含成功與失敗字樣。
  - **狀態欄截斷**：以**一個長但合法的 `baseName`**（約 200 字元；加副檔名後仍 < `NAME_MAX` 255）
    讓兩份都**寫成功**，此時 `localStatus` 含兩個完整路徑必然超過 500；`gdriveStatus` 則以超長
    `gdriveSubpath` ＋ 超長 mock status 撐長。
    斷言 `localStatus.length() <= 500`、`gdriveStatus.length() <= 512`，且兩者仍含「成功」字樣與副檔名標記。
    **不要用「多層深目錄把絕對路徑撐長」**：macOS 的 `PATH_MAX` 是 **1024**（不是 Linux 的 4096），
    實測會先炸在 `Files.createDirectories`（`File name too long`），測不到截斷。
  - **Drive 兩份**：`gdriveEnabled=true` 時 `syncQuietly` 被呼叫**兩次**、兩次的 `subpath` 參數相同、
    兩次的 `Path` 參數副檔名分別為 `.json` 與 `.xlsx`；`gdriveStatus` 符合 269.4.6 的字串契約
    （`xlsx …／json …`）；僅一次回成功時能分辨是哪一份；`xlsxGdrivePath`／`jsonGdrivePath` 各自填對。
  - `gdriveEnabled=false` 時 `syncQuietly` **零呼叫**，`gdriveStatus` 與兩個 Drive 落點欄位皆 null。

## 驗證

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test -Dtest='com/steven/assets/service/export/*' -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

零回歸的機械證據（本任務不改任何既有匯出，這一行**必須輸出空的**）：

```bash
git diff --stat main -- backend/src/main/java/com/steven/assets/service/ExcelExportService.java backend/src/main/java/com/steven/assets/service/TradingRadarExportService.java
```

建置與部署（本任務功能未啟用，只驗證不炸）：

```bash
docker compose -p asset-management build --no-cache business-services
```

```bash
docker compose -p asset-management up -d --no-deps --force-recreate business-services && docker compose -p asset-management restart bff && curl -s http://localhost:8080/actuator/health
```

## 完成報告

**狀態：已完成。** 新增 4 支 main ＋ 2 支測試，全部落在新 package
`backend/src/main/java/com/steven/assets/service/export/`：

| 檔案 | 內容 |
|---|---|
| `ExportDoc.java` | block list 模型（`sealed interface Block` permits `Line`／`KvRow`／`Table`／`Blank`）＋ `LineStyle`／`Format` |
| `ExcelDocRenderer.java` | doc → xlsx；八種 Styles；`render()` 與 `writeSheet(Workbook, Sheet)` 兩支 API |
| `JsonDocRenderer.java` | doc → 語意化 JSON |
| `DualFormatExportWriter.java` | 兩份 tmp＋ATOMIC_MOVE 落檔 ＋ Drive 同步 ＋ 狀態合併與截斷 ＋ `baseName` 路徑重驗 |
| `ExportDocRendererTest.java` | 23 個測試（Construction 4／JsonTypes 7／ExcelCells 12） |
| `DualFormatExportWriterTest.java` | 11 個測試 |

**驗證輸出：**
- `mvn -f backend/pom.xml test` → **Tests: 415, Failures: 0, Errors: 0**（含本任務新增的 34 個）
- `git diff --stat origin/main -- ExcelExportService.java TradingRadarExportService.java` → **空**
  （零回歸鐵證：本任務不接任何既有匯出點，兩支一行未改）
- `arch-auditor` 查證：0 critical、1 major、1 minor，均已修（見下）

**與原計畫的偏差及原因：**
1. **`ExcelDocRenderer` 不做跨呼叫的 Styles 快取。** 初版寫了 `WeakHashMap<Workbook, Styles>`，
   但它是 singleton bean、會被排程執行緒與 HTTP 執行緒同時呼叫，那是資料競爭。改為
   `render()` 內建立一份、`writeSheet()` 每次呼叫各建一份。代價是同一 workbook 多次呼叫會各建 8 個
   `CellStyle`（XSSF 上限 64k，無實害）。
2. **狀態字串改為「兩半各自先截斷再合併」。** 原規格寫「合併後截斷至 512」，但 `GdriveOutputSupport`
   已把單邊 status 截到 512，兩半相加超過 1000，合併後截尾會把 `／json …` **整段切掉**——正好違反
   「必須能分辨是哪一份」的契約。已同步改回 269.4.6 並補一條測試斷言。
3. **截斷測試不用「深目錄撐長路徑」。** macOS 的 `PATH_MAX` 是 **1024**（不是 Linux 的 4096），
   實測先炸在 `Files.createDirectories`（`File name too long`）。改用長但合法的 `baseName`（200 字元，
   加副檔名後 < `NAME_MAX` 255），兩份都寫成功、才測得到成功狀態的截斷。已同步改回 269.5.1。
4. **`DualFormatExportWriter` 的 varchar 註解修正。** 實查運行中 DB：九張設定表中八張
   `last_run_status` 為 500、`stock_alert_export_setting` 為 512、`crawler_export_setting` 無此欄。
   常數 `LOCAL_STATUS_MAX = 500` 取最短者，值正確；只有註解原本寫「十張皆同」是錯的。
5. **`spec/steering/structure.md` 補上 `service/export/`**（`arch-auditor` 的 major）——
   這是 backend `service/` 底下第一個子 package，`structure.md` §6.1 明列目錄結構變更必須更新 steering。
   （該樹在 main 上已漏列 `security/` 與 `util/`，屬既有債，本次刻意不擴大處理。）
6. **POI 5.x 移除了 `CellStyle.getFont(Workbook)`**，測試改用 `wb.getFontAt(style.getFontIndex())`。
