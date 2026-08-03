# [t289] 大盤指數日線匯出（Excel／JSON）加「成交股數」「成交金額」兩欄

**對應 Requirements:** Requirement 45（股市大盤指數日線 Excel／JSON 匯出與排程自動匯出；本任務把匯出欄位由九欄擴為十一欄，加入成交量相關欄位）
**前置任務:** Task 285（週線MA5，建立「附加在最末」的擴欄原則）、Task 286（月/季/年線，延續原則）、Task 288（成交量落地 DB：`twse_index_daily_history.trade_volume`／`trade_value`、`us_index_daily_history.volume`，已 merge 進 main）
**Liquibase changeset:** 無（Task 288 已建好欄位，本任務純讀取，不新增／修改任何 DB schema）

## 背景

`GdpTwseView.vue` 的日線圖已經有成交量柱狀子圖（Task 288），但「匯出 Excel」按鈕與排程自動匯出產出的 xlsx／json 檔案**沒有成交量**——目前欄位固定九欄：日期／開盤／最高／最低／收盤／週線MA5／月線MA20／季線MA60／年線MA240（`ExcelExportService.indexDailySheet`，`backend/src/main/java/com/steven/assets/service/ExcelExportService.java:547-574`）。

Task 288 完成時明文把這件事排除在外（`spec/tasks/t288_index_daily_volume_chart.md:418`：「不得改動 Excel／JSON 匯出的欄位…擴欄另案處理」），因為 Task 288 的範圍是「畫圖」，不是「匯出」。使用者現在要求把匯出檔案也補上成交量，這就是「另案」。

**現況已具備的條件（不需要新資料源、新查詢、新 DB 欄位）：**
- `TwseIndexDailyHistory`（`backend/src/main/java/com/steven/assets/model/TwseIndexDailyHistory.java:50-55`）已有 `tradeVolume`（`Long`，成交股數）與 `tradeValue`（`BigDecimal(20,0)`，成交金額）兩個欄位，皆 nullable。
- `UsIndexDailyHistory`（`backend/src/main/java/com/steven/assets/model/UsIndexDailyHistory.java:50-51`）已有 `volume`（`Long`，成交量），nullable；海外指數沒有成交金額欄（Yahoo 無此欄，與日線圖成交量子圖的 `turnovers` 對海外指數恆回全 `null` 陣列同一事實）。
- `indexDailySheet` 呼叫的 `findIndexDaily`（`ExcelExportService.java:613-624`）查詢回傳的是**完整 entity**（`twseIndexHistRepo.findByTradingDateBetweenOrderByTradingDateAsc` / `usIndexHistRepo.findByIndexCodeAndTradingDateBetweenOrderByTradingDateAsc`），已經查得到這兩個量欄，只是私有 record `IndexDailyRow`（`:577`）目前沒有把它們帶出來（**查了但沒選進 DTO**，不是沒查到）。
- **本次匯出邏輯是「大盤指數匯出」專用、獨立的私有方法**（已查證：`indexDailySheet`／`indexMaAt`／`findIndexDaily`／`IndexDailyRow` 這組私有輔助只被 `indexDailyDoc` 呼叫，未被資產總覽、已實現損益、交易紀錄、油價金價、匯率、交易日曆、交易雷達等其他 8 個匯出頁引用）。**這次擴欄不會波及其他匯出頁**，只共用欄位無關的底層渲染元件 `ExcelDocRenderer`／`JsonDocRenderer`／`DualFormatExportWriter`。

## 要做什麼

- [x] **289.1 `ExportDoc.Format` 新增 `NUM0`**（`backend/src/main/java/com/steven/assets/service/export/ExportDoc.java:144`）：
  ```java
  public enum Format { TEXT, MONEY, NUM2, NUM4, NUM6, NUM0, DATE, TIMESTAMP, BOOL_ZH, LIST_LINES }
  ```
  成交股數／成交金額恆為整數（`trade_value` 為 `NUMERIC(20,0)` 無小數位），既有 `MONEY`／`NUM2`（`#,##0.00`）／`NUM4`／`NUM6` 都帶固定小數位，套用會印出恆為 0 的小數位、謊稱精度（與 Task 285/286 選 `NUM2` 而非 `NUM4` 給均線欄同一理由，見 `spec/requirements.md` Requirement 45 該條 AC）。`NUM0` 格式字串為 `#,##0`（千分位、無小數）。

- [x] **289.2 `ExcelDocRenderer` 加對應樣式**（`backend/src/main/java/com/steven/assets/service/export/ExcelDocRenderer.java`）：
  - `Styles` 內部類別（`:193-241`）新增欄位 `final CellStyle num0;`，建構子內加：
    ```java
    num0 = wb.createCellStyle();
    num0.setDataFormat(fmt.getFormat("#,##0"));
    ```
  - `styleFor`（`:158-166`）加一個 case：`case NUM0 -> st.num0;`
  - `Styles` 類別上方的 javadoc（`:182`）目前寫「八種樣式」，`num0` 是第九個 `CellStyle` 欄位，同步改為「九種樣式」，避免留下錯誤計數。
  - **不需要**改 `backend/.../ExcelExportService.java:1029` 那個獨立的 `Styles` 內部類別——那支只服務 `writeCurrentSummarySheet`／`writeSnapshotSheet` 兩個完全不同的渲染路徑（不經 `ExportDoc.Format`），與 `indexDailySheet` 無關，不要動它。
  - `JsonDocRenderer` **不用改**：它明文（`:28`）不讀 `Format`，只讀 header／rows，新 enum 值不影響 JSON 輸出。

- [x] **289.3 `IndexDailyRow` 加兩欄**（`ExcelExportService.java:577`）：
  ```java
  private record IndexDailyRow(java.time.LocalDate date, BigDecimal open, BigDecimal high,
                               BigDecimal low, BigDecimal close, Long volume, BigDecimal turnover) {}
  ```

- [x] **289.4 `findIndexDaily` 帶出量欄**（`ExcelExportService.java:613-624`）：
  ```java
  private List<IndexDailyRow> findIndexDaily(String market, java.time.LocalDate start, java.time.LocalDate end) {
      if ("TWSE".equalsIgnoreCase(market)) {
          return twseIndexHistRepo.findByTradingDateBetweenOrderByTradingDateAsc(start, end).stream()
                  .map(t -> new IndexDailyRow(t.getTradingDate(), t.getOpenPoint(), t.getHighPoint(),
                          t.getLowPoint(), t.getClosePoint(), t.getTradeVolume(), t.getTradeValue()))
                  .toList();
      }
      return usIndexHistRepo.findByIndexCodeAndTradingDateBetweenOrderByTradingDateAsc(market, start, end).stream()
              .map(u -> new IndexDailyRow(u.getTradingDate(), u.getOpenPoint(), u.getHighPoint(),
                      u.getLowPoint(), u.getClosePoint(), u.getVolume(), null))
              .toList();
  }
  ```
  海外分支的 `turnover` 固定傳 `null`（不是 `BigDecimal.ZERO`）——沒有這個欄位就是沒有，不得以 0 充數。

- [x] **289.5 `indexDailySheet` 加兩欄，附加在最末**（`ExcelExportService.java:547-574`）：
  ```java
  private ExportDoc.Sheet indexDailySheet(String market,
                                          java.time.LocalDate start, java.time.LocalDate end) {
      List<String> headers = List.of("日期", "開盤", "最高", "最低", "收盤",
              "週線MA5", "月線MA20", "季線MA60", "年線MA240", "成交股數", "成交金額");
      List<ExportDoc.Format> formats = List.of(ExportDoc.Format.DATE, ExportDoc.Format.NUM4,
              ExportDoc.Format.NUM4, ExportDoc.Format.NUM4, ExportDoc.Format.NUM4,
              ExportDoc.Format.NUM2, ExportDoc.Format.NUM2, ExportDoc.Format.NUM2, ExportDoc.Format.NUM2,
              ExportDoc.Format.NUM0, ExportDoc.Format.NUM0);

      List<IndexDailyRow> all = findIndexDaily(market, start.minusDays(MA_LOOKBACK_DAYS), end);
      List<List<Object>> rows = new java.util.ArrayList<>();
      for (int i = 0; i < all.size(); i++) {
          IndexDailyRow d = all.get(i);
          if (d.date().isBefore(start)) continue;
          rows.add(java.util.Arrays.asList(d.date(), d.open(), d.high(), d.low(), d.close(),
                  indexMaAt(all, i, 5), indexMaAt(all, i, 20), indexMaAt(all, i, 60), indexMaAt(all, i, 240),
                  d.volume(), d.turnover()));
      }
      return new ExportDoc.Sheet(
              org.apache.poi.ss.util.WorkbookUtil.createSafeSheetName(indexLabel(market)),
              List.of(new ExportDoc.Table(null, null, headers, true, false, true, formats, rows)),
              headers.size());
  }
  ```
  **既有九欄的欄索引 0–8 不得位移**——新兩欄一律排在 `年線MA240` 之後。`omitNullCells=true`（第三個 boolean 參數，維持既有 `true`）讓 `turnover` 為 `null` 時該格根本不建（不是建了填 BLANK），與既有 open/high/low 的 null 處理一致。

- [x] **289.6 四處使用者可見文案同步**（同 Task 285／286 慣例，遺漏會讓頁面說明與實際欄位漂移；Task 285／286 都各自改過這四處，不是三處）：
  - `frontend/src/views/GdpTwseView.vue:211-213`（匯出對話框「輸出內容」說明）：`<b>日期／開盤／最高／最低／收盤／週線MA5／月線MA20／季線MA60／年線MA240</b>` 改為 `<b>日期／開盤／最高／最低／收盤／週線MA5／月線MA20／季線MA60／年線MA240／成交股數／成交金額</b>`；後面那句「四條均線＝...」之後補一句「成交股數／成交金額直接取自資料庫既有欄位（不重算）；海外指數無成交金額資料，該欄留空」。
  - `frontend/src/views/GdpTwseView.vue:154`（排程卡 `.schedule-hint`）：「欄位為日期／開盤／最高／最低／收盤／週線MA5／月線MA20／季線MA60／年線MA240，」改為「欄位為日期／開盤／最高／最低／收盤／週線MA5／月線MA20／季線MA60／年線MA240／成交股數／成交金額，」。
  - `bff/src/main/java/com/steven/assets/bff/schedulelist/SchedulePublicBffController.java:86`（「大盤指數匯出」JOBS `description`）：目前文字含「欄位為開高低收＋四條均線」，改為「欄位為開高低收＋四條均線＋成交股數／成交金額」。**注意**：本行前不久（本次 session）才把「命中後各產出」改成「命中後同時產出」以配合 `SchedulePublicBffControllerTest.含格式字樣的說明都已改寫`——這次只改欄位清單那一小段，不要動到「同時產出 JSON 與 Excel 兩份」那句，否則會重新踩同一顆測試地雷。
  - `backend/src/main/java/com/steven/assets/controller/MacroHistoryController.java:121-123`（`exportIndexDaily` javadoc）：目前寫「指數日線區間匯出成單一 .xlsx（日期／開盤／最高／最低／收盤／週線MA5／月線MA20／季線MA60／年線MA240**九欄**；第六～九欄為 Task 285／286 新增的計算欄）」，改為十一欄並補一句第十～十一欄為 Task 289 新增（直接取欄位，非計算欄）。

- [x] **289.7 測試**：
  - `backend/src/test/java/com/steven/assets/service/export/DualFormatSingleTableExportTest.java` 已有以 `service.indexDailyDoc("TWSE", start, end)` 取 `/sheets/0/tables/0/rows` 的既有測試（`:317`、`:449`、`:503` 附近）——新增或擴充斷言，確認：
    - (a) 新增列的長度為 11（既有斷言若寫死欄數需同步更新）；
    - (b) `rows` 最末兩個值對應 header 的「成交股數」「成交金額」，且與 DB 該日 `trade_volume`／`trade_value` 一致（用測試資料建構時一併塞入這兩欄）；
    - (c) 海外指數（例如 `service.indexDailyDoc("DJI", ...)`）該日 `trade_value` 對應欄位固定為 `null`——`stubAll()`（`DualFormatSingleTableExportTest.java:219-228`）目前只 stub 了 `twseIndexHistRepo`，沒有 stub `usIndexHistRepo`，未 stub 時 Mockito 對 `List` 回傳型別預設回空集合。本項測試需另外新增 `UsIndexDailyHistory` fixture（比照 `twseIndex()` 的寫法，`:207-217`）並 `when(usIndexHistRepo.findByIndexCodeAndTradingDateBetweenOrderByTradingDateAsc(...)).thenReturn(...)`；
    - (d) 既有欄索引 0–8（日期至年線MA240）不得因新增兩欄而位移——沿用既有 identity 映射比對手法（Task 285/286 的 golden 比對慣例，見下一項）。
  - JSON 那一份：擴充或新增一個對 `jsonDocRenderer.render(indexDailyDoc(...))` 的斷言，確認 JSON 的 `headers`／每列陣列長度同樣是 11，且鍵名（若 JSON 結構帶欄名）為「成交股數」「成交金額」。
  - `backend/src/test/java/com/steven/assets/service/export/ExportDocRendererTest.java`（既有檔）：新增一個對 `NUM0` 格式的斷言，確認 `#,##0` 格式字串被正確套用、且與 `NUM2`／`MONEY` 是不同的 `CellStyle` 實例（該強調實際寫在 `ExcelDocRenderer.java:186-187` 的 javadoc，非測試檔本身；`NUM0` 同理需要獨立實例）。
  - **`ZeroRegression.大盤指數日線()` 這條既有測試會因欄數變 11 而確定性失敗，必須連同 golden 基準一起處理**（沿用 Task 286 的 286.10 節同一套流程，不得跳過）：
    1. 複製現有 `backend/src/test/resources/golden/index_twse.xlsx` 為 `index_twse_pre_t289.xlsx`（＝ Task 286 版基準，9 欄），供本任務的 identity 映射測試比對「插欄前 9 欄逐格未變」。
    2. 寫一支拋棄式產生腳本／臨時測試呼叫 `service.exportIndexDaily("TWSE", D1, D2)`（**不是** `indexDailyDoc`——後者只回傳 `ExportDoc` 資料物件，不是可寫檔的 workbook bytes；`exportIndexDaily` 內部才會呼叫 `excelDocRenderer.render(indexDailyDoc(...))` 產出實際 xlsx，比照 t286 286.10 節的既有寫法）產出新版 workbook，另存回 `backend/src/test/resources/golden/index_twse.xlsx`（覆蓋，11 欄版）。**這條測試走的是 mock（`twseIndexHistRepo` stub，非真實 DB）**：核對新增的成交股數／成交金額兩欄與 `twseIndex()` fixture（`DualFormatSingleTableExportTest.java:207-217`）現況的值一致即可——該 fixture **目前未設定** `tradeVolume`／`tradeValue`（`@Data` 預設維持 `null`），故重產出的 golden 這兩欄在資料列應皆留空（僅表頭列有「成交股數」「成交金額」字樣）。**不得順手改 `twseIndex()` fixture 去塞非 null 的量值**（比照 Task 286 286.10 節的同一條警示）——golden 的唯一差異就是表頭列由 9 格變 11 格、資料列的量欄仍是空格；要驗證「量欄有值」的路徑，改用 289.7(b) 另外新增的獨立斷言（可沿用或擴充下方 `sevenDays()`／`longSeries()` 一類已有量值的既有 fixture，不要動 `twseIndex()`）。
    3. `DualFormatSingleTableExportTest.java` 的 `assertSameWorkbook(golden("index_twse"), ...)` 斷言改用新的 11 欄 golden；`index_twse_pre_t285.xlsx`／`index_twse_pre_t286.xlsx` 兩份既有基準原樣保留不動（供各自任務的 identity 映射測試繼續使用）。
    4. 不得為了讓測試通過而放寬或刪除既有的 `getLastCellNum()` 欄數斷言——欄數斷言本身要從 9 改成 11，不是移除。**明確列出兩處**：`插欄前後既有五欄未變()`（`DualFormatSingleTableExportTest.java:402`）與 `插欄前後既有六欄未變()`（`:421`）各有一行 `assertThat(s.getRow(0).getLastCellNum()).isEqualTo((short) 9);`，兩處都要改成 `(short) 11`；緊鄰該行的中文註解（解釋「表格總欄數已因 t286 變動」那句）也要同步改成提及 t289。
    5. **新增第三條 identity 映射測試**（沿用既有 `@Nested class WeeklyMa5`，`DualFormatSingleTableExportTest.java:378` 起，比照前兩條「插欄前後既有 N 欄未變」的寫法）：對 `index_twse_pre_t289.xlsx`（第 1 步新增的基準，9 欄）做 `assertSameMapped(pre, actual, c -> c)`，斷言表頭第 9、10 格（0-based，即「成交股數」「成交金額」）字串值正確、`getLastCellNum()` 為 11。**這條不能省略**：既有兩條分別只驗到欄索引 0–4 與 0–5，沒有任何測試會用 identity 映射驗證欄索引 6–8（月線MA20／季線MA60／年線MA240）在本次插入兩欄後有沒有位移——`ZeroRegression.大盤指數日線()` 拿「本次重產的輸出」當 golden，屬循環驗證，抓不到插入邏輯本身的位移 bug。

- [x] **289.8 不得做的事**：
  - 不得新增 DB 欄位或 Liquibase changeset——Task 288 已建好 `trade_volume`／`trade_value`／`volume`，本任務純讀取。
  - 不得改動 `IndexExportScheduleService`（排程／立即匯出的呼叫邏輯不變，仍是同一個 `excelExportService.indexDailyDoc(market, start, end)`，欄位擴充對呼叫端透明）。
  - 不得改動海外指數的成交金額為「用某種方式估算」——沒有就是 `null`，不得用成交股數×收盤價之類的方式推算出一個假的「成交金額」（那是衍生值、且該推算法在真實市場不成立，會誤導使用者）。
  - 不得把 `NUM0` 拿去替換既有 `MONEY`／`NUM2` 等欄位的樣式——只用於本次新增的兩欄。
  - 不得動 `GdpTwseBffController.buildIndexDailyBody`（日線圖用的 BFF 端點）——那是給圖表用的資料，契約與精度已由 Task 288 定案，與本次的匯出檔案是兩條獨立路徑。**這不需要援引 MA5 那組具名例外**（`spec/steering/structure.md` §3.2 第 4 條，明文只涵蓋 MA5/20/60/240 三份實作、不得引用來新增第四份）：成交股數／成交金額在兩處都只是單純讀取 DB 欄位（backend 端 `entity.getTradeValue()`、BFF 端讀 REST 回應裡本來就不存在該鍵時 `Map.get` 天然回 `null`），沒有任何計算邏輯、數學上不存在分岔可能，性質等同開/高/低/收四個價格欄本來就是兩處各自讀取同一張表、從未被視為需要收斂的重複實作。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true test
(cd frontend && /Users/steven/.nvm/versions/node/v22.21.0/bin/npm run build)
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true test
cp /Users/steven/Project/asset-management/.env .
docker compose -p asset-management build --no-cache business-services bff frontend
docker compose -p asset-management up -d --no-deps --force-recreate business-services bff frontend
docker compose -p asset-management restart bff
curl -fsS http://localhost:8080/actuator/health
```

實資料驗收（容器內以模擬租戶 header 呼叫，免走 OAuth）：

```bash
docker exec asset-business-services sh -c "curl -s -H 'X-User-Id: 1' -H 'X-User-Role: ADMIN' -H 'X-User-Status: ACTIVE' 'http://localhost:8080/api/index-daily/export?market=TWSE&start=2026-07-01&end=2026-08-03'" -o /tmp/twse_export_check.xlsx
```
（若上述端點路徑與實際 `MacroHistoryController.exportIndexDaily` 的實際掛載路徑不同，實作時以 `grep -n "exportIndexDaily" backend/src/main/java/com/steven/assets/controller/MacroHistoryController.java` 確認正確路徑後替換）——下載後用 `unzip -p` 或 Python `openpyxl` 檢查工作表欄數為 11、最後兩欄表頭為「成交股數」「成交金額」，且至少一列的成交股數非空、其值與資料庫該日 `trade_volume` 相符（`docker exec asset-postgres psql -U assets -d assets -c "SELECT trading_date, trade_volume, trade_value FROM twse_index_daily_history WHERE trading_date='2026-08-03';"`）。

## 完成報告

**實際修改檔案**：
- `backend/.../service/export/ExportDoc.java`：`Format` enum 加 `NUM0`
- `backend/.../service/export/ExcelDocRenderer.java`：`Styles` 加 `num0`（`#,##0`）、`styleFor` 加對應 case、javadoc「八種樣式」改「九種樣式」
- `backend/.../service/ExcelExportService.java`：`IndexDailyRow` 加 `volume`／`turnover`；`findIndexDaily` 兩分支帶出量欄；`indexDailySheet` 表頭/formats/rows 各加成交股數／成交金額；兩處 javadoc（`exportIndexDaily`、`indexDailySheet` 上方）欄位清單同步更新為十一欄
- `backend/.../controller/MacroHistoryController.java`：僅 javadoc，九欄→十一欄
- `frontend/src/views/GdpTwseView.vue`：匯出對話框說明（:211-214）、排程卡 `.schedule-hint`（:154）欄位清單同步
- `bff/.../schedulelist/SchedulePublicBffController.java`：「大盤指數匯出」JOBS description 欄位清單同步
- `backend/src/test/java/.../export/DualFormatSingleTableExportTest.java`：`WeeklyMa5` 巢狀類別新增 3 條測試（插欄後既有九欄未變、成交股數/成交金額值與 JSON、海外指數成交金額固定 null）＋ 2 個 fixture helper；既有兩條 `getLastCellNum()==9` 斷言改 11
- `backend/src/test/java/.../export/ExportDocRendererTest.java`：新增 `NUM0格式獨立於其他數值格` 測試
- `backend/src/test/resources/golden/index_twse.xlsx`：重產為 11 欄版
- `backend/src/test/resources/golden/index_twse_pre_t289.xlsx`：新增（＝重產前的 9 欄版）
- `spec/requirements.md`、`spec/design.md`、`spec/tasks.md`：對應規格與索引更新

**未改動**：`GdpTwseBffController.java`（依 289.8 規劃，日線圖 BFF 端點與匯出是兩條獨立路徑，不需援引任何具名例外）、`IndexExportScheduleService.java`（呼叫端透明）、`ExcelExportService.java:1029` 的獨立 `Styles`（服務不同渲染路徑）。

**單元測試輸出**：
```
backend: mvn test → BUILD SUCCESS（exit 0），含新增的 4 條測試
bff:     mvn test → BUILD SUCCESS（exit 0），SchedulePublicBffControllerTest 三條皆過
frontend: npm run build → 成功
```

**容器驗證輸出**：`docker compose -p asset-management build --no-cache business-services bff frontend` 全部 Built；`up -d --no-deps --force-recreate` 後三容器皆 healthy，`docker logs asset-bff` 無 error/refused。

**實際 Excel 欄值比對**（容器內以 `X-User-Id: 1` 模擬租戶呼叫 `GET /api/index-daily/export?market=TWSE&start=2026-08-01&end=2026-08-03`，下載後用 Python `zipfile` 直接解析 xlsx XML）：
- `sharedStrings.xml` 表頭：`日期／開盤／最高／最低／收盤／週線MA5／月線MA20／季線MA60／年線MA240／成交股數／成交金額`（11 項，逐字相符）
- 2026-08-03 那一列：`成交股數=11427047935`、`成交金額=885506043091`，與 `SELECT trade_volume, trade_value FROM twse_index_daily_history WHERE trading_date='2026-08-03'` 的 DB 實際值逐位相同。

**與原計畫的偏差**：無實質偏差。三輪 spec 對抗式審查修正的項目（golden 重產步驟改用 `exportIndexDaily` 而非 `indexDailyDoc`、補上 `index_twse_pre_t289` identity 映射測試、`usIndexHistRepo` fixture 補齊、`MacroHistoryController.java` javadoc 列入四處文案同步、`NUM0` 相關 javadoc 計數更新）皆已在實作前併入任務檔並落實，實作階段未再發現新的落差。
