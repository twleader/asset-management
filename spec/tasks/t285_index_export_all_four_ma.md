# [t285] 大盤指數日線匯出補齊月線／季線／年線（6 欄 → 9 欄）

**對應 Requirements:** Requirement 45（股市大盤指數日線 Excel／JSON 匯出與排程自動匯出）
**前置任務:** t284（同一支匯出剛加上第六欄「週線MA5」與回看機制，尚未併入 main，與本任務同分支）
**Liquibase changeset:** 無（不動 DB）

## 背景

t284 讓「股市大盤查詢」頁（`/gdp-twse`）的匯出檔多了第六欄「週線MA5」。使用者看到產出後回覆：
**「月線、季線、年線的價位也要匯出」**——圖表上本來就有那三條線，檔案裡卻只有週線。

現況（t284 完成後、本任務開始前）：

- 匯出（xlsx／json，手動與排程共用同一支 `ExcelExportService.indexDailyDoc`）：
  **日期／開盤／最高／最低／收盤／週線MA5** 六欄。
- 圖表：BFF `GET /api/bff/gdp-twse/index-daily` **早就回 `ma5`／`ma20`／`ma60`／`ma240` 四條**，
  頁面也早就畫四條均線。**所以本任務只補匯出端，BFF 與前端圖表一行都不用改。**
- 匯出端的 MA 實作在 `ExcelExportService`：`ma5At(asc, i)` ＋ 常數 `MA5_WINDOW`(5)、`MA5_LOOKBACK_DAYS`(30)。
- **「週線MA5：唯一的計算欄」這個 spec 標題字面不變**（`spec/design.md` Requirement 45）——它是
  `ExcelExportService`／`GdpTwseBffController` 等既有 javadoc 與 `spec/steering/structure.md` §3.2
  交叉引用的錨點文字，本任務只擴充該節內容，不改標題。

**回看視窗是本任務唯一有技術風險的地方**：30 個日曆天只夠 MA5 的 4 個交易日，
補上 MA240 之後需要當日之前的 **239 個交易日**，不放大回看的話，匯出區間前面一整年的
月線／季線／年線都會是空的——正是 t284 當初為了 MA5 才引入回看要避免的那個 bug。

## 要做什麼

### A. 後端：匯出加三欄（`backend/src/main/java/com/steven/assets/service/ExcelExportService.java`）

- [x] 285.1 `indexDailySheet` 的表頭由
      `List.of("日期", "開盤", "最高", "最低", "收盤", "週線MA5")` 改為
      `List.of("日期", "開盤", "最高", "最低", "收盤", "週線MA5", "月線MA20", "季線MA60", "年線MA240")`。
  - **三個新欄一律附加在最末、依視窗由短到長**（MA5→MA20→MA60→MA240，與圖表 legend 同序）。
    既有欄索引 0–5 不得位移：`DualFormatSingleTableExportTest` 有寫死的欄索引斷言
    （`getCell(1)`／`getCell(3)` 為 null、`getCell(4)` ＝ 23200.00、`getCell(5)` ＝ 週線MA5），
    附加在最末才能用 **identity 映射**做「插欄前後既有欄逐格未變」的比對。
  - **欄名沿用專案既有語彙**：`週線MA5`／`月線MA20`／`季線MA60`／`年線MA240`
    （與交易雷達匯出、走勢圖 legend 同名，不得自創「MA20」「20日均線」等第二套寫法）。
- [x] 285.2 `formats` 對應加三個 `ExportDoc.Format.NUM2`（四條均線全部 NUM2，**不是 NUM4**）：
      MA 定義上只有 2 位小數（`divide(window, 2, HALF_UP)`），NUM4 會多印兩個恆為 0 的位數而謊稱精度。
      `ExportDoc.Table` 建構子會驗 `columnFormats.size() == headers.size()`，漏加會直接擲 `IllegalArgumentException`。
- [x] 285.3 把 `ma5At(List<IndexDailyRow> asc, int i)` **一般化**為
      `indexMaAt(List<IndexDailyRow> asc, int i, int window)`，四條均線共用同一支、只差視窗長度。
  - **方法名刻意不用 `maAt`**——`TechnicalIndicatorService` 已有一支同名 `maAt(asc, i, days)`
    （股票路徑、`double` 累加、可能併 live），同名同形會讓 `grep -ran "maAt" backend` 混淆兩種
    語意不同的實作，也會讓 `spec/design.md` 裡「走勢圖的『週線MA5』線（`indicators/series` → `maAt`）」
    這句失去指涉對象。
  - **不得**為某一條均線另寫一份（BFF 那邊四條線也是共用一支 `movingAverage(closes, window)`，
    本次要維持同一個結構）。
  - 演算法與精度**不變**：`BigDecimal` 精確加總、只在最後 `divide(window, 2, RoundingMode.HALF_UP)`
    捨入一次；`i < window - 1` 回 `null`。
    ⚠️ **不得改用 `double` 累加**——double 加法不可結合，會在捨入邊界翻面，讓圖與檔案偶爾差 0.01。
  - 常數 `MA5_WINDOW`(5) 改為四個視窗常數或直接以字面量 5/20/60/240 呼叫，擇一即可，
    但**四條必須走同一支方法**。
- [x] 285.4 **回看常數由 30 放大為 400**：`MA5_LOOKBACK_DAYS`(30) → `MA_LOOKBACK_DAYS`(400)，
      查詢起點 `start.minusDays(400)`（`indexDailySheet` 內既有的那一處，兩張來源表共用）。
  - **400 的推導寫進 javadoc**：最長視窗 MA240 需要當日之前的 **239 個交易日**；400 個日曆天約含
    400÷7×5 ≈ 285 個平日，台股每年約 240～242 個交易日（年約 19～21 天非週末休市），折算約再扣
    21～26 天休市日 ≈ **261～266 個交易日**，對 239 仍有約 **22～27 個交易日**餘裕
    （理論下限約 239×365/242 ≈ 361 個日曆天，400 尚有約 11% headroom；即使跨兩次農曆年的最壞情況，
    交易日仍約 259～260，≥ 239）。
  - **回看不足只會缺值、不會算錯**：湊不滿視窗一律 `null`（`omitNullCells` ＝該格根本不建，非 BLANK 格），
    不補前值、不以不足視窗的平均充數。故此常數選保守即可。
- [x] 285.5 **同步更新 javadoc**（漏一處就留下一句假話）：
      `ExcelExportService` 的 `exportIndexDaily`／`indexDailySheet` 兩段（「六欄」「第六欄為計算欄」→
      九欄／第六～九欄）、`ma5At` 上方那句「第六欄『週線MA5』是本分頁唯一的計算欄」（改為
      「第六～九欄是本分頁唯一不直接取自 DB 欄位的四欄」），以及 `MacroHistoryController` 的
      `/api/index-daily/export` 那段（六欄→九欄）。
      查法：`grep -ran "六欄\|週線 \?MA5\|MA5_" backend/src/main/java`——
      **`ExcelExportService.java` 另有兩處「六欄」與本任務無關（快照早退分支的 autoSize、股價分頁的
      omitNullCells 註解），不得誤改**；用 `grep -n` 確認每個命中的上下文屬於 `indexDailySheet`
      再動手。**不需要**改 `GdpTwseBffController.java` 或其測試——兩處對 Requirement 45 這節的
      交叉引用是錨點文字「週線MA5：唯一的計算欄」，標題未變故引用仍有效（見背景段）。
- [x] 285.6 **不得**改動 `exportIndexDaily`／`indexDailyDoc` 的簽章與 `IndexExportScheduleService` 的呼叫方式：
      排程與手動匯出走同一支 `indexDailyDoc(market, start, end)`，兩條途徑自動同時拿到新欄。
      JSON 那一份由同一份 headers 產生三個新 key，**不需要**也**不得**另寫 JSON 分支。

### B. 文案（三處，全是使用者可見）

- [x] 285.7 `frontend/src/views/GdpTwseView.vue` 匯出對話框「輸出內容」：
      `日期／開盤／最高／最低／收盤／週線MA5` → 補上 `／月線MA20／季線MA60／年線MA240`，
      並把下一句的「週線MA5＝…」改寫為涵蓋四條均線的說法。
- [x] 285.8 同檔 `.schedule-hint`：`欄位為日期／開盤／最高／最低／收盤／週線MA5` 同步補三欄。
- [x] 285.9 `bff/.../schedulelist/SchedulePublicBffController.java` 的「大盤指數匯出」那筆：
      `欄位為開高低收＋週線MA5` → `欄位為開高低收＋四條均線`。**不新增 `JOBS` 項目**（沒有新排程）；
      該檔既有測試 `SchedulePublicBffControllerTest` 會驗 `hasSize(46)` 與 description 的三個子字串
      （`Excel`／`同時產出 JSON 與 Excel 兩份`／`以其格式（JSON／Excel）`），本次改的是後段
      「欄位為開高低收＋…」，不在被驗的子字串內，故兩者皆不受影響。

### C. 不做什麼（範圍界線）

- **BFF 主程式邏輯一行都不改**：`index-daily` 早已回四條 MA。
- **前端圖表一行都不改**：五條線是 t284 就做好的，本次只動兩處文案。
- 不動 DB、不新增 changeset、不新增端點、不動排程。
- 不動交易雷達／走勢圖／觀察清單的任何 MA（那些走 `TechnicalIndicatorService`，
  是另一條資料流、且會併入 Redis 今日盤中即時點位——與本頁圖表／匯出的「一律不含 live」語意不同）。
  台股大盤的均線目前有**三份**實作（BFF `movingAverage`、本次擴充的 `ExcelExportService.indexMaAt`、
  `TechnicalIndicatorService`），例外已記在 `spec/steering/structure.md` §3.2 第 4 條，
  **本任務不得被引用來新增第四份實作**。

### D. 測試（`backend/src/test/java/com/steven/assets/service/export/DualFormatSingleTableExportTest.java`）

285.11–285.13 的新測試**全部沿用既有 `@Nested class WeeklyMa5`**（不另立巢狀類別）；
其 `@DisplayName("Task 284：週線MA5 欄")` 改為 `@DisplayName("Task 284／285：大盤指數日線的四條均線欄")`，
反映該類別現在同時涵蓋四條均線。

- [x] 285.10 **golden 基準重產（順序不可顛倒）**：
  1. 先把現行 `backend/src/test/resources/golden/index_twse.xlsx`（＝ t284 產出的 6 欄版）
     **複製**為 `index_twse_pre_t285.xlsx`（兩個檔名都要存在，**不是 `git mv`**），並 `git add`。
  2. 實作完 285.1–285.5 後才重產 `index_twse.xlsx`：暫時在該測試類別加一支拋棄式測試，
     **stub 與 fixture 必須與該檔現行的完全同一份**（`stubAll()` ＋ `twseIndex()`），寫出後刪掉：
     ```java
     java.nio.file.Files.write(java.nio.file.Path.of("src/test/resources/golden/index_twse.xlsx"),
             service.exportIndexDaily("TWSE", D1, D2));
     ```
     （工作目錄為 `backend/`。）
  3. **不得順手改 `twseIndex()` fixture**（2 列，湊不滿任何視窗）——重產後四個 MA 格皆不存在，
     golden 的唯一差異是**表頭列由 6 格變 9 格**。
  4. `index_twse_pre_t284.xlsx`（5 欄版）**保留不刪**：它是 t284 的比對基準，仍被既有測試使用。
- [x] 285.11 **改既有測試 ＋ 新增一條**，兩條並存（`WeeklyMa5` 這個 nested class 目前只有一條
      「插欄前後既有五欄未變」，t285 前後總共要有兩條插欄回歸測試，各自守住一次插欄）：
  1. **改**既有「插欄前後既有五欄未變」：它對 `index_twse_pre_t284` 的 `assertSameMapped(pre, actual, c -> c)`
     維持不動（迴圈只跑到 `pre.getLastCellNum()`＝6，九欄後仍會通過）；**但**該測試另有一行
     `assertThat(s.getRow(0).getLastCellNum()).isEqualTo((short) 6);`——這行假設 sheet 只有 6 欄，
     九欄後必紅，**改成 `(short) 9`**。
  2. **新增**「插欄前後既有六欄未變」：對 `index_twse_pre_t285` 做 identity 映射（`c -> c`），
     並斷言表頭第 6/7/8 格（0-based）為 `月線MA20`／`季線MA60`／`年線MA240`、`getLastCellNum()` 為 9。
- [x] 285.12 **保留**既有 `MA5值與JSON` 測試原樣不動（含 `sevenDays()` fixture 與三條手算斷言，
      那是跨模組同值保證的機械防線之一，見 `spec/design.md` Requirement 45）。**新增**
      `長天期均線值與JSON`：另備一組**至少 241 個交易日**的獨立 fixture（日期一律 `>= start`），斷言
  - MA20 在**資料 index 0–18（共 19 列）該格不存在、index 19 起有值**
    （對應 `getRow(1)`–`getRow(19)` 為 null、`getRow(20)` 有值）；MA60 為 index 0–58／59 起；
    MA240 為 index 0–238／239 起（`i < window - 1` 回 null，`window-1` 即該視窗的暖機列數）；
  - 至少一條長天期均線的值 ＝ 測試內以 `BigDecimal` 手算的視窗平均（**不得**呼叫被測程式自己算期望值）；
  - JSON 那一份同列的 `月線MA20`／`季線MA60`／`年線MA240` 為相同數值、暖機列為 `null`。
- [x] 285.13 **改**既有「回看列不輸出」測試：`@DisplayName` 由「查詢向前回看 30 天…」改為
      「查詢向前回看 400 天…」，斷言行 `assertThat(from.getValue())...isEqualTo(START.minusDays(30))`
      改為 `.isEqualTo(START.minusDays(400))`（`fixture withLookback()` 本身不受影響，因為
      `twseIndexHistRepo` 是 mock、回傳值與傳入的 range 參數無關）。
      **新增**「回看視窗足以支撐 MA240」測試：以 `ArgumentCaptor` 斷言**實際傳入 repo 的查詢起點
      ＝ `start.minusDays(400)`**（與上一條合併驗證亦可）；並以「回看列 239 筆 ＋ 區間內 1 筆」的
      fixture 斷言**輸出只有 1 列且該列 MA240 有值**（證明回看有生效、且回看列沒有外洩到檔案裡）。

## 驗證

```bash
# 1. 後端測試（含 golden 零回歸、四條均線值、回看視窗）
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test \
  -Dtest='DualFormatSingleTableExportTest,SingleTableScheduleServiceDualFormatTest' \
  -DfailIfNoSpecifiedTests=false -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

```bash
# 2. 全量後端測試（確認沒打壞其餘 golden）
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

```bash
# 3. BFF 測試（本次未改 BFF 主程式，但文案在該模組內，仍須通過）
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test
```

**跑起來真的有這個功能**（本專案沒有 dev server，「改好」＝ image rebuild ＋ container recreate；
JVM 服務一律 `--no-cache`。compose 服務名為 **`bff`** 不是 `bff-service`）：

```bash
cd /Users/steven/Project/asset-management && docker compose -p asset-management build --no-cache business-services bff frontend
```

```bash
cd /Users/steven/Project/asset-management && docker compose -p asset-management up -d --no-deps --force-recreate business-services bff frontend
```

⚠️ recreate business 之後要 `docker compose -p asset-management restart bff`（換 IP，BFF 握舊 IP 會回 500）。

實機驗收：

1. 在 business 容器內產檔並檢視（該端點為全域公開行情，不需帶 `X-User-*`；business 無 host port 對映）：
   `docker exec asset-business-services curl -s 'http://localhost:8080/api/index-daily/export?market=TWSE&start=2026-07-01&end=2026-08-02' -o /tmp/idx.xlsx`
   → 取出後確認表頭為 **9 欄**、且**第一列的四個均線格都有值**（回看生效）。
2. 交叉驗證：同一日期的四個均線值，須與頁面圖表 legend 的四個值一模一樣
   （legend 顯示的是最後一個交易日；以匯出檔末列比對）。
3. 頁面「匯出 Excel」對話框與排程卡說明文字都列出九個欄位。

## 完成報告

### 實際改動檔案

| 檔案 | 內容 |
|---|---|
| `backend/.../service/ExcelExportService.java` | 表頭 6→9 欄；`ma5At`→`indexMaAt(rows,i,window)` 一般化；`MA5_LOOKBACK_DAYS`(30)→`MA_LOOKBACK_DAYS`(400)；四處 javadoc 更新 |
| `backend/.../controller/MacroHistoryController.java` | 僅 javadoc（六欄→九欄） |
| `backend/src/test/.../export/DualFormatSingleTableExportTest.java` | `WeeklyMa5` 改名 `@DisplayName`；「五欄未變」的 `getLastCellNum()` 6→9；新增「六欄未變」「長天期均線值與JSON」「回看支撐MA240」三條；「回看列不輸出」30→400 |
| `backend/src/test/resources/golden/index_twse.xlsx` | 重產（9 欄） |
| `backend/src/test/resources/golden/index_twse_pre_t285.xlsx` | 新增：t284 版（6 欄）基準，供 identity 映射比對 |
| `bff/.../schedulelist/SchedulePublicBffController.java` | 僅文案（「＋週線MA5」→「＋四條均線」） |
| `frontend/src/views/GdpTwseView.vue` | 僅文案（匯出對話框、排程說明列出九個欄位） |
| `CLAUDE.md` / `spec/tasks.md` / `spec/tasks/README.md` | 任務索引計數同步（283 佔位說明） |
| `spec/steering/structure.md` | §3.2 第 4 條例外由「兩份」擴充為「三份實作」 |

**BFF 主程式與前端圖表確實一行未動**（`index-daily` 與五條線皆 t284 已完成）。

### 測試結果

- backend：`531 tests, 0 failures`（較 t284 的 528 多 3 條：六欄未變、長天期均線值與JSON、回看支撐MA240）
- bff：`24 tests, 0 failures`（本次未新增，t284 的 3 條 `GdpTwseBffControllerMaTest` 依舊綠）

### 部署與實機驗證（2026-08-03）

`docker compose -p asset-management build --no-cache business-services bff frontend` → `up -d --force-recreate`
→ `restart bff`。三個映像 Created 時間戳分別為本次的 5 分鐘／52 秒／36 秒前。

1. **上游資料手算**（business 容器內，全域行情不需 `X-User-*`）：`/api/twse-daily-index?from=2025-01-01&to=2026-08-02`
   回 381 筆；手算末筆 MA5/20/60/240 ＝ **41665.96／44114.76／44089.08／33295.78**——
   與使用者稍早提供的頁面 legend 截圖數字**逐位相同**。
2. **匯出檔**（同一容器內打 `/api/index-daily/export?market=TWSE&start=2025-01-01&end=2026-08-02`，
   29573 bytes，382 列）：
   - 表頭九欄：`日期／開盤／最高／最低／收盤／週線MA5／月線MA20／季線MA60／年線MA240`；
   - **第一列（2025-01-02）四個均線格都有值**（23116.0／23076.22／23006.06／21435.43）——
     400 天回看生效，不是空白；
   - 末列（2026-07-31）四個均線值 ＝ **41665.96／44114.76／44089.08／33295.78**，
     與第 1 步手算、與 legend 截圖三方逐位相同。
3. **架構符規查證**：`arch-auditor` 對 t284＋t285 累積 diff 的最終一輪判定 **0 critical／0 major／0 minor**
   （前一輪 t284 單獨審查的 2 major 已在 spec 修正並反映到本次實作：例外由「兩份」擴為「三份實作」、
   `indexMaAt` 改名避開與 `TechnicalIndicatorService.maAt` 的歧義）。
4. **畫面目視**：使用者已提供實際頁面截圖，五條線與 legend 四個均線數值與上述驗證一致。

### 與計畫的偏差

無功能偏差，285.1–285.13 全數照做。編號未再避讓（284 之後緊接 285，未撞號）。
