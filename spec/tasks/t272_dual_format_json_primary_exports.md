# [t272] 兩份 JSON-primary 匯出加上 Excel（警示觸發、爬蟲公開資訊）＋ 排程列表頁文案收尾

**對應 Requirements:** Requirement 55（十個自動匯出點的檔案一律同時產出 `.json` 與 `.xlsx` 兩份，主檔名完全相同、只差副檔名；既有兩份 JSON 的內容與檔名一律不得變動，只新增一份 `.xlsx`；`SchedulePublicBffController.JOBS` 的九條說明文字必須同步改寫）
**前置任務:** t269（block list 模型、兩個 renderer、`DualFormatExportWriter` 已就緒）。t270／t271 非必要前置，但**排程列表頁文案收尾（272.4）必須等它們做完**才不會寫出提前生效的假說明。
**後續任務:** 無（本任務完成即 Requirement 55 全部落地）
**Liquibase changeset:** 無（本需求全程零 schema 變更）

## 背景

十個自動匯出點中，這兩個的方向與其他八個**相反**：它們本來就只有 JSON，要補的是 Excel。

| 匯出點 | 位置 | 主檔名 | 產生時機 |
|---|---|---|---|
| 警示觸發 | backend `StockAlertTriggerExportService` | `alert_triggers_{ownerId}` | **事件驅動**：警示條件觸發當下（Redis 訂閱者執行緒上同步寫本機檔） |
| 爬蟲公開資訊 | **`external-materials-service`** `NewsPoller` | `public_info_{yyyy-MM-dd}` | 每輪爬取結束後 |

**兩份既有 JSON 都是對外契約，內容與檔名一律不得變動：**

- `public_info_{yyyy-MM-dd}.json` 是 **SRPP 退休規劃專案**的輸入。既有規格明文寫「檔名不開放設定——
  SRPP 依此檔名取用」。
- `alert_triggers_{ownerId}.json` 是 Requirement 54 對下游程式的契約（3 天滾動視窗、每次觸發全量重寫）。

本任務**只新增一份 `.xlsx`**，既有 JSON 的欄位、結構、產生時機全部照舊；新增的 Excel **由既有 payload
轉出**，不得為了做 Excel 而回頭改動 JSON 的形狀。

**警示觸發那一份有一個必須守住的約束：** 它的寫檔發生在 **Redis 訂閱者執行緒**上
（`PriceStreamService.onPriceUpdate` → `StockAlertService.checkAlertsFor` 是同步呼叫），同一條執行緒還負責
SSE 廣播與交易雷達評估。多產一份 Excel 會增加該執行緒的工作量，且 **POI 產檔比 JSON 序列化慢一個數量級**。

## 要做什麼

### 272.1 警示觸發加 Excel（backend）

- [x] 272.1 **既有 JSON 的輸出 byte 不變，但需要一次最小重構。** 現況是
      `private byte[] buildJson(Long ownerUserId, LocalDateTime since, List<StockAlertTrigger> triggers)`
      （`:292` 起）內部組一個 `ObjectNode root`（`:293-302`）後直接序列化成 `byte[]`。
      要讓 xlsx 吃到同一份 payload，必須把它拆成兩步：
      `ObjectNode buildPayload(Long ownerUserId, LocalDateTime since, List<StockAlertTrigger> triggers)`
      ＋ 既有的序列化。**組 payload 的每一行內容不動**，只把序列化那幾行搬出去。
      **不要寫「一行不動」**——那與「新增一支吃 payload 的方法」自相矛盾；正確的約束是
      **「JSON 輸出 byte 必須逐 byte 相同」**（272.5.b 守門）。
- [x] 272.1.1 新增 `ExportDoc alertTriggersDoc(ObjectNode payload)`。
      **參數型別是 `com.fasterxml.jackson.databind.node.ObjectNode`，不是 `Map<String,Object>`**——
      既有 payload 本來就是 `ObjectNode`（`:293 objectMapper.createObjectNode()`），
      硬轉成 Map 會多一層轉換並丟失型別。
      Sheet 名 `警示觸發`，`Table` 的 `omitNullCells = false`（新建的表，統一走 BLANK 格），blocks 為：
  - `KvRow`：檔層級欄位 `ownerUserId`／`windowDays`／`since`／`exportedAt`／`triggerCount`
    （實測自 `:294-300`，逐一到程式碼確認）。
  - `Blank`。
  - 單一 `Table("觸發明細", SECTION_13, headers, showHeader=true, labelFirstColumn=false, …)`：
    rows 來自 `payload.get("triggers")` 陣列。
  - `Sheet.autoSizeColumns = headers.size()`。
- [x] 272.1.2 **headers 必須由 payload 動態推導，不得在 Excel 側硬編一份欄位清單。**
      理由：**日後 Requirement 54 加欄位時，硬編的那一份會靜默少一欄**，而 JSON 有、Excel 沒有，
      兩份檔就不一致了。推導方式：取 `triggers` 陣列**所有元素 key 的聯集**、以第一個元素的順序為基準、
      新出現的 key 附在後面。
      （**理由僅此一條**。初版寫的「某些欄位在指標資料不足時可能整個不存在」是假前提——
      既有 `putDecimal(...)`（`:358`）在值為 null 時仍會寫入 key，欄位不會消失。聯集邏輯本身保留為防禦，
      但不要用一個不成立的理由去支撐它。）
- [x] 272.1.3 `triggers` 為空陣列時，仍須產出**含表頭的合法 xlsx**（比照 Requirement 54 對空 JSON 的既有
      規定：使用者按 run-now 的目的是驗證落點，空檔案同樣達成該目的）。**不得**回 404 或靜默不產檔。
      空陣列時 headers 推導不出來 → 此時以 `buildPayload` 內實際寫入 trigger 物件的 key 順序為準
      （在程式碼裡有單一來源可讀，不必硬編兩份）。
- [x] 272.1.4 落檔改用 `DualFormatExportWriter`，`baseName = "alert_triggers_" + ownerUserId`
      （與現況逐字元相同）。既有的 private 落檔實作（tmp ＋ atomic move，約 `:376`）刪除。
      **`FILENAME_PATTERN` 常數（`"alert_triggers_{使用者ID}.json"`，public、供前端顯示）必須改成明示兩份**——
      漏改會讓設定頁顯示的檔名與實際落檔不符。
- [x] 272.1.4.1 **兩支 render 各自 try/catch，失敗的那一份傳 `null`**（t269 269.4.1.1 已允許）。
      **這一條在本匯出點特別要緊**：`alert_triggers_{ownerId}.json` 是 Requirement 54 的對外契約檔，
      新加的 xlsx（POI 產檔）若擲例外而沒被接住，會連既有 JSON 一起弄丟——那是本任務明文禁止的。
- [x] 272.1.4.2 **`lastRunStatus` 改寫入 `DualResult.localStatus()`。** 既有字串
      （`StockAlertTriggerExportService.java:256-257`）只描述一份檔，原樣保留的話 xlsx 失敗時狀態欄
      仍顯示「成功：…alert_triggers_1.json」，使用者看不出另一份掛了——Requirement 55 的
      「狀態欄如實反映部分成功」在此就沒有落腳點。若要保留既有的 bytes／觸發筆數資訊，
      **一律在傳給共用元件之前拼好，不得自行拼接後才截斷**（截斷由共用元件負責，順序不可顛倒）。
- [x] 272.1.5 **`exportForTrigger` 的三段獨立 try/catch 語意不得改變**：匯出失敗絕不讓
      `stock_alert_trigger` 的 INSERT 回滾、絕不中斷 email enqueue、絕不讓 `evaluate`／`evaluateGroup`
      擲出而中止整輪檢查。加了 Excel 之後**多一個可能失敗的來源**，`DualFormatExportWriter` 本身已保證
      「一份失敗不影響另一份、且不擲例外」，但呼叫端仍須維持既有的包覆。
- [x] 272.1.6 **Drive 上傳的兩條分支都要改成上傳兩份。** 既有 `writeExport(ownerUserId, debounceDriveUpload)`
      （`:223`）有兩條路（`:264-267`）：
  - `debounceDriveUpload = true`（觸發路徑）→ 走 `ScheduledExecutorService` 的 **60 秒合併去抖**
    （`:413-428`）。**必須維持非同步 ＋ 去抖，不得改成寫檔當下同步上傳**（rclone 上傳逾時上限 45 秒，
    同步做會讓整條即時價管線停擺）。
  - `debounceDriveUpload = false`（**run-now**）→ 走 `uploadNow(...)` **立即上傳**（`:267`）。
    **這一條初版漏了**：run-now 同樣要依序上傳 xlsx 與 json 兩份，並把兩份結果合併進回應。
  實作方式：呼叫 `DualFormatExportWriter.write(..., gdriveEnabled=false, ...)` 只寫本機兩份，
  Drive 上傳仍由既有的兩條分支各自負責、**各自改為上傳兩份**。
  **被合併去抖跳過的那幾次，`gdrive_last_run_at` 與 `gdrive_last_status` 兩欄值一律保持不變**
  （Requirement 54 既有規定，有既有測試守門）。
- [x] 272.1.6.1 **本路徑繞過 `DualFormatExportWriter` 的 Drive 段，故狀態字串的合併與截斷必須自己做。**
      兩條分支各上傳兩份之後，一律沿用 t269 269.4.6 的字串契約
      `"xlsx " + <xlsx status> + "／json " + <json status>`，**並在寫入 `gdriveLastStatus` 前截斷至
      512 字元**（優先保留成功／失敗與副檔名標記）。
      漏做的話，Requirement 55 的兩條驗收條件——「僅一份上傳成功時必須分辨得出是哪一份」與
      「狀態欄截斷」——在本匯出點就沒有任何實作承接，而超長字串會讓 JPA save 擲 `DataException`、
      把一次本機其實已成功的匯出記成失敗。
- [x] 272.1.7 **per-owner 寫檔序列化不得弄丟**：兩條觸發路徑可能由不同執行緒同時進入。既有的 per-owner
      鎖必須繼續涵蓋**兩份檔**的寫入（鎖的範圍是整個 `DualFormatExportWriter.write` 呼叫，不是只鎖 JSON 那一份）。
- [x] 272.1.8 **本匯出點的既有欄位維持指向 `.json`，新增的是 xlsx 欄位**——與其餘八個匯出點相反，
      不要照抄它們。理由：`alert_triggers_*.json` 是本匯出點的對外契約，既有 `ExportResult` 的
      path／size 欄位現在指的就是那份 JSON；改成指向 xlsx 會靜默改變既有回應語意。
      故：既有欄位不動（仍為 `.json`），**另加 `xlsxPath`／`xlsxSizeBytes`／`xlsxGdrivePath`**。
      **兩個 DTO 都要加，不是只加 service 內部那個**：
  - service 內部的 `ExportResult`；
  - **前端實際讀的 `StockAlertExportDto.RunNowResponse`**，以及 `StockAlertController` 內
    **每一個** 建構它的 return 點都要填上新欄位。
    只改 `ExportResult` 的話，272.3 要求的「run-now 提示顯示兩個落點」沒有任何管道把 xlsx 落點送到前端。
  （**第 10 項爬蟲公開資訊沒有 run-now DTO**——該頁只顯示設定的落點字串，故無此工作項。）

### 272.2 爬蟲公開資訊加 Excel（external-materials-service）

- [x] 272.2 **在 `external-materials-service` 內做，不共用 backend 的元件。** 三個 Maven 專案
      （`backend`／`bff`／`external-materials-service`）無父 pom、不共用程式碼，Requirement 50 已就
      「不為兩處數十行程式碼建共用 module」定案。故 ext 端自帶一份極小的轉換，**不得**把
      `ExportDoc`／`ExcelDocRenderer`／`DualFormatExportWriter` 搬成共用 module 或複製整套過去。
- [x] 272.2.1 `external-materials-service/pom.xml` 新增依賴 `org.apache.poi:poi-ooxml`，
      **版本寫死 `5.3.0`**——與 `backend/pom.xml:77-79` 一致。
      （實測 backend 是**明確寫版號**、非由 Spring Boot BOM 管理，故此處也必須寫版號；
      實作前再確認一次 backend 的版號，以它為準。）
- [x] 272.2.2 新增 `external-materials-service/.../service/PublicInfoXlsxWriter.java`：
      吃 `NewsPoller.exportPublicInfoJson` 已組好的 `Map<String,Object> payload`，產出 `byte[]`。
      單一工作表 `公開資訊`：
  - 頂端 metadata 鍵值列：`generatedAt`／`trigger`／`tradingDayCutoff`／`count`
    （實測自既有 `exportPublicInfoJson`；實作前逐一到程式碼確認）。
  - 空一列。
  - `items` 表：表頭為 `NewsRow` 的欄位名。**headers 由 items 動態推導（所有元素 key 的聯集，
    以第一個元素的順序為基準）**，理由同 272.1.2——ext 端日後加欄位時 Excel 不得靜默少一欄。
  - `items` 為空時仍產出含 metadata 的合法檔。
- [x] 272.2.3 `NewsPoller.exportPublicInfoJson` 的既有 JSON 產生邏輯**一行不動**（含 `payload` 的組法、
      `objectMapper.writerWithDefaultPrettyPrinter()`、tmp ＋ `ATOMIC_MOVE`、失敗 graceful）。
      新增的 xlsx **沿用同一份 `payload` 物件**（不得重查 `source.loadTodayPublicInfoForExport(...)`），
      落點為 `dir.resolve("public_info_" + today + ".xlsx")`，同樣走「同目錄 tmp ＋ `ATOMIC_MOVE`」。
      （**這裡「一行不動」是成立的**——`payload` 本來就是方法內的區域變數 `Map<String,Object>`，
      xlsx 直接吃它即可，不像警示觸發那份需要拆方法。）
- [x] 272.2.4 **順序守門：先 JSON、後 xlsx；JSON 失敗時 xlsx 不寫。** 既有 `writtenFile` 變數的語意就是
      「本機 JSON 寫成功才設值、null＝不上傳舊檔」。**這是 Requirement 55 明列的具名例外**——
      其餘九個匯出點都是「兩份互不影響」，唯獨這裡本機 JSON 是 SRPP 的權威來源。
      反向（xlsx 產檔或寫檔失敗）只記 `log.warn`，**絕不影響 JSON 那一份**，也絕不讓爬取流程中斷。
- [x] 272.2.5 **Drive 同步**：兩份都寫成功後上傳兩份到同一個 Drive 子路徑；
      **JSON 寫成功但 xlsx 失敗時，仍上傳 JSON 那一份**（JSON 是 SRPP 的契約，不能因為 Excel 壞掉就不同步）。
      狀態字串須能分辨哪一份成功。
- [x] 272.2.6 ext 端的 rclone 上傳沿用該 module 既有的 client 與逾時設定（**不得**新增第二套 rclone 呼叫路徑）。

### 272.3 前端文案

- [x] 272.3 兩個頁面的設定卡檔名說明改為明示兩份，run-now 提示顯示兩個落點：
  - `frontend/src/views/StockAlertView.vue`（警示觸發匯出卡）
  - `frontend/src/views/CrawlerDataView.vue`（爬蟲輸出路徑卡）
  **實作前先 grep 找出實際位置**：
  ```bash
  grep -rn 'alert_triggers\|public_info\|\.json' frontend/src/views/StockAlertView.vue frontend/src/views/CrawlerDataView.vue
  ```
- [x] 272.3.1 **全前端最後一次巡檢**（t270／t271 已各自改完自己那幾頁，本項只是收尾確認）：
  ```bash
  grep -ran '\.xlsx\|\.json' frontend/src/views/*.vue
  ```
  逐條檢視輸出，凡是描述「排程／自動匯出會產生什麼檔」的文案都必須已改成兩份。

### 272.4 排程列表頁說明文字收尾（**必須等 t270／t271 都完成**）

- [x] 272.4 改寫 `bff/src/main/java/com/steven/assets/bff/schedulelist/SchedulePublicBffController.java`
      的 `JOBS` 清單中**九條** `description`。**不新增也不移除項目**（本需求不動任何 `@Scheduled` 的 cron），
      但這九條說明在本需求落地後會全部變成假的：
  **九條的新措辭必須統一含 `同時產出 JSON 與 Excel 兩份` 這個確切子字串**（驗收判準與 272.5.2 的
  `allMatch` 斷言都依賴它；措辭不統一會讓兩者同時失效）：
  - `:71`／`:77`／`:80`／`:83`／`:101`／`:104` 寫「產出 Excel（到指定目錄）」
    → 「…命中執行時間即**同時產出 JSON 與 Excel 兩份**（主檔名相同）到指定目錄…」
  - `:74` 寫「即**以其格式（JSON／Excel）**產出當前年度交易日曆」
    → 「…命中執行時間即**同時產出 JSON 與 Excel 兩份**（主檔名相同）的當前年度交易日曆…」
      （**不要只刪子句**，要留一句含新措辭的完整敘述，否則 `allMatch` 斷言會漏掉它。）
  - `:86` 原文是「產出**所選指數的開高低收 Excel** 到指定目錄」——**不含「產出 Excel」四字**，
    字面搜尋會漏掉。→ 「…即**同時產出 JSON 與 Excel 兩份**（主檔名相同，欄位為開高低收）到指定目錄…」
  - `:188` 寫「每輪輸出公開資訊 **JSON** 至本機設定資料夾」——**原文根本不含 `Excel` 二字**。
    → 「每輪輸出公開資訊，**同時產出 JSON 與 Excel 兩份**（主檔名相同）至本機設定資料夾…」
  **行號會因前面的改動位移，實作前先定位**：
  ```bash
  grep -nE "Excel|輸出公開資訊 JSON" bff/src/main/java/com/steven/assets/bff/schedulelist/SchedulePublicBffController.java
  ```
  （改動前為 **9** 行。）
- [x] 272.4.1 **「不動 `@Scheduled` ⇒ `JOBS` 不必動」這個推論在本專案已被否決過一次**
      （Requirement 48／Task 260：交易雷達那一筆的 `description` 在改了匯出行為後變成假的，必須改寫）。
      本需求初版又寫了一次同樣的錯誤推論，已修正。**不要再退回去。**

### 272.5 測試

- [x] 272.5 警示觸發（backend，擴充既有 `StockAlertTriggerExportTest`）：
  - **(a) 主檔名一致性**：兩個落點去掉副檔名後 `assertEquals`。
  - **(b) 既有 JSON 逐 byte 零回歸（`exportedAt` 為具名例外）**：`buildPayload` 內的
    `exportedAt`（`:299` `LocalDateTime.now(TW_ZONE)`）是牆鐘值，逐 byte 比對必然失敗。
    比對前先把該欄位以固定字串取代（或改為「除 `exportedAt` 外逐欄位相等，`exportedAt` 只驗存在
    ＋符合 ISO local 正則」）。
    **不要為此引入 `Clock` 注入**——backend 全樹沒有 `Clock`、也沒有 `mockStatic` 的既有用法，
    那是跨十個匯出點的獨立重構，超出本需求範圍。
    其餘欄位固定 owner／`since`／觸發資料後，比對 `buildPayload(...)` 序列化後的輸出與改動前相同。
    **基準走 golden file，與 t270／t271 同一流程**（只把舊版 `.java` 撈出來是沒用的，單元測試無法
    編譯執行舊版 service）：
    ```bash
    git worktree add /tmp/r55-before origin/main
    ```
    在該 worktree 以**固定的 owner／since／trigger fixture** 產出
    `backend/src/test/resources/golden/alert_triggers_before.json` 並提交，新測試讀它比對。
    這是 272.1「最小重構」的唯一守門。
  - **(c) headers 動態推導**：構造兩筆 trigger，第二筆比第一筆多一個 key，斷言 Excel 表頭含該欄
    且第一列該格為空（272.1.2 的探針；只取第一個元素 keys 的壞實作會少一欄）。
  - **(d) 空觸發**：`triggers` 為空陣列時，兩份都產出、xlsx 含表頭、未擲例外。
  - **(e) 匯出失敗不影響觸發，且不得弄丟既有 JSON**：注入會讓 Excel 產檔失敗的情形（例如 POI 擲例外），
    斷言 (1) `alert_triggers_{ownerId}.json` **仍完整寫出**且內容與正常情況相同；
    (2) `lastRunStatus` 同時含成功與 `xlsx render 失敗` 字樣；
    (3) `stock_alert_trigger` 仍寫入、email 仍 enqueue、`evaluate` 未擲出。
    **(1)(2) 是 272.1.4.1／272.1.4.2 的探針**——少了它們，xlsx 產檔失敗會靜默連既有契約檔一起弄丟。
  - **(f) Drive 去抖未回歸（觸發路徑）**：60 秒內連續 N 次觸發只上傳一輪（每輪兩份＝`copyTo` 兩次）；
    **尾端補跑**仍成立（兩次觸發相隔 5 秒、之後不再觸發，間隔到期後必須再發生一輪）；
    被合併的那幾次 `gdrive_last_run_at`／`gdrive_last_status` **值保持不變**。
  - **(g) Drive 立即上傳（run-now 路徑）**：`runNow(...)` 呼叫 `copyTo` **兩次**（xlsx ＋ json），
    且**不經過**去抖 executor（272.1.6 漏補分支的探針）。
  - **(h) per-owner 序列化＝兩份出自同一輪**：兩條執行緒以**不同筆數**的 triggers 同時觸發同一 owner，
    讀回兩份檔後斷言 **json 的 `triggerCount` 等於 xlsx「觸發明細」表的資料列數**，且該值是那兩個
    筆數之一（不是混合）。
    **不要**只斷言「兩份檔都完整、可被解析」——那在**完全沒有鎖**的實作下也一定成立
    （每份檔各自是 tmp＋`ATOMIC_MOVE`，本來就不會半截），是假綠燈。
  - **(i) 既有測試一併更新**：`StockAlertTriggerExportTest` 內對 `syncQuietly` 的次數斷言
    （實測全檔有 14 處 `syncQuietly` 引用）會因「每輪上傳兩份」而變紅。
    **這是預期變更不是回歸**，次數一律 ×2。
    但**「本機寫檔失敗 → 完全不上傳」那幾條的 `never()` 不受影響**（失敗發生在解析輸出目錄時，
    在呼叫共用元件之前）。實作前先跑一次全測試取得精確清單，逐條判斷是 ×2 還是不動。
- [x] 272.5.1 爬蟲公開資訊（ext service）：擴充既有 `NewsPollerGdriveSyncTest`，並在
      `external-materials-service/src/test/java/com/steven/assets/externalmaterials/service/`
      新建一支 xlsx 轉換的測試檔（類名＝受測類名加 `Test` 後綴）。
      **本任務刻意不在 spec 裡寫死該新類的識別字**，理由同 t269：`scripts/spec-check.sh` 的 B5 會把
      「spec 提到但全樹不存在的 `Test` 結尾類名」判為 BLOCK，全新元件必然先有 spec 後有測試檔。
      下列斷言仍為必須交付項：
  - **(i) 主檔名一致性** ＋ **(j) 既有 JSON 零回歸**：基準同樣走 golden file——在
    `/tmp/r55-before` 的 `origin/main` worktree 以**固定 payload** 產出
    `external-materials-service/src/test/resources/golden/public_info_before.json` 並提交，新測試讀它比對。
    `generatedAt` 為牆鐘值，比對前以固定字串取代或只驗格式（Requirement 55 具名例外三，理由同 (b)）。
  - **(k) 單次查詢**：`verify(source, times(1)).loadTodayPublicInfoForExport(any(), any())`
    ——兩份共用同一份 payload 的探針。
  - **(l) xlsx 失敗不影響 JSON**：注入 POI 產檔失敗，斷言 JSON 仍寫出、仍上傳 Drive、爬取流程未中斷、只記 warn。
  - **(m) JSON 失敗時 xlsx 不寫**（272.2.4 順序守門的探針）。
  - **(n) items 為空**時仍產出含 metadata 的合法 xlsx。
  - **(o) Drive**：兩份都成功時上傳兩份；JSON 成功、xlsx 失敗時**仍上傳 JSON 那一份**，狀態字串能分辨。
    rclone 一律以介面替身注入，**不實際連網**。
- [x] 272.5.2 排程列表頁測試，落點 `bff/src/test/java/com/steven/assets/bff/schedulelist/`。
      `JOBS` 是 private static，**透過該 controller 的公開查詢方法取得清單**（`:202` 回傳 `JOBS`），
      不要用反射。斷言兩條：
  - 項目數為 **46**（實測值，且與 `:54` 起各段註解的分組計數一致）——本需求不新增也不移除項目。
  - **沒有任何一條 `description` 同時「含 `Excel` 卻不含新文案」**：
    即 `jobs.stream().filter(j -> j.description().contains("Excel")).allMatch(j -> j.description().contains("同時產出 JSON 與 Excel 兩份"))`。
    **不要用「不含『產出 Excel』字樣」當判準**——`:86` 的原文是「產出所選指數的開高低收 Excel」，
    那種字面比對會漏掉它，是可證實的假綠燈。

## 驗證

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

（**bff 那一行不可省略**：272.5.2 的排程列表頁測試在 bff 專案裡，t269–t271 的驗證段都沒跑過 bff 測試，
漏了這行那支測試永遠不會被執行。）

確認 ext 端沒有把 backend 的元件複製過去（應輸出 0）：

```bash
grep -ran "ExportDoc\|ExcelDocRenderer\|DualFormatExportWriter" external-materials-service/src | wc -l
```

確認排程列表頁文案已改（改動前為 **9**，改完後應為 **0**，已實測）：

```bash
grep -E 'Excel|輸出公開資訊 JSON' bff/src/main/java/com/steven/assets/bff/schedulelist/SchedulePublicBffController.java | grep -vc '同時產出 JSON 與 Excel 兩份'
```

> **判準為什麼是這個形狀。** 直覺會想「把舊文案 grep 到 0」，但**那永遠做不到**——新措辭本身就含
> 「產出 JSON 與 Excel」，任何鎖舊文案的樣式（含 `產出.*Excel`）改完後都還是會命中新文案（已實測）。
> 而且那種判準與 272.5.2 的 `allMatch` 斷言**互斥**：要 `allMatch` 成立，每一條就一定含「產出…Excel」。
> 故改用「含格式字樣的那幾條，是否全部帶上新措辭」——與 272.5.2 同義，兩者不會打架。
> 前提是九條的新措辭統一含 `同時產出 JSON 與 Excel 兩份` 這個確切子字串（見 272.4）。

建置與部署（四個服務都動到）：

```bash
docker compose -p asset-management build --no-cache business-services external-materials-service bff frontend
```

```bash
docker compose -p asset-management up -d --no-deps --force-recreate business-services external-materials-service bff frontend
```

端到端實測——警示觸發 run-now：

```bash
docker exec asset-business-services curl -s -X POST -H 'X-User-Id: 1' -H 'X-User-Role: ADMIN' -H 'X-User-Status: ACTIVE' http://localhost:8080/api/stock-alerts/export-setting/run-now
```

```bash
docker exec asset-business-services sh -c 'ls -l /home/steven/input/ | grep alert_triggers'
```

端到端實測——爬蟲輸出（觸發一輪爬取後查落檔）：

```bash
docker exec asset-external-materials-service sh -c 'ls -l /home/steven/input/ | grep public_info'
```

**Requirement 55 全數落地的最終檢查**——只看「本次部署後才產生的檔」，成對即通過：

```bash
docker exec asset-business-services sh -c 'cd /home/steven/input && n=0; for f in $(find . -maxdepth 1 -name "*.xlsx" -mmin -60); do n=$((n+1)); b="${f%.xlsx}"; [ -f "$b.json" ] || echo "缺 json: $b"; done; for f in $(find . -maxdepth 1 -name "*.json" -mmin -60); do b="${f%.json}"; [ -f "$b.xlsx" ] || echo "缺 xlsx: $b"; done; echo "檢查完畢（近一小時 xlsx $n 份）"'
```

> **為什麼要用 `-mmin -60` 而不是 `-newermt`：** business-services 容器是 Alpine／BusyBox，
> 它的 `find` **不支援 GNU 的 `-newermt`**——實跑會直接印 usage 並失敗，而 `echo 檢查完畢` 照樣執行，
> 看起來像通過。`-mmin` 是 BusyBox 支援的。
>
> **為什麼要有時間過濾：** 該目錄裡有本需求上線**之前**產生的單一格式舊檔
> （例如昨天的 `資產總覽_1_20260731.xlsx` 沒有對應的 `.json`）。不加時間過濾會把它們全部誤報成缺檔，
> 讓這個檢查恆為紅、失去意義。系統**一律不刪使用者的舊檔**（既有規定），故舊檔會一直在。
>
> **為什麼要印出份數：** 否則「近一小時完全沒有任何檔案」與「全部成對」的輸出長得一模一樣。

## 完成報告

**狀態：已完成。Requirement 55 全部落地——十個自動匯出點皆同時產出 `.json` 與 `.xlsx`。**

| 檔案 | 改動 |
|---|---|
| `StockAlertTriggerExportService.java` | `buildJson` 拆為 `buildPayload()`（內容一行未改）＋ `toJsonBytes()`；新增 `alertTriggersDoc(ObjectNode)`；落檔改用 `DualFormatExportWriter`；`uploadNow` → `uploadBothNow`（兩份、狀態合併與 512 截斷自行做）；刪除 `writeAtomically` 與已無呼叫端的 `buildJson` |
| `StockAlertExportDto` / `StockAlertController` | `RunNowResponse` 加 `xlsxPath`／`xlsxSizeBytes`／`xlsxGdrivePath`；**既有欄位維持指向 `.json`** |
| ext `pom.xml` ＋ `PublicInfoXlsxWriter.java`（新） | `poi-ooxml 5.3.0`；117 行的極小轉換，**零 backend import** |
| `NewsPoller.java` | JSON 段一行未改；新增 `writePublicInfoXlsx`（順序守門）；`syncToGdrive` 改吃兩個 Path、上傳兩份 |
| `SchedulePublicBffController.java` | 九條 `description` 改寫，統一含 `同時產出 JSON 與 Excel 兩份`；項目數仍 46 |
| 前端 ×2 | `StockAlertView.vue`／`CrawlerDataView.vue` 文案 |
| 新測試 | `PublicInfoXlsxWriterTest`(4)／`SchedulePublicBffControllerTest`(3)／ext 雙格式 ×2／backend xlsx ×6 |

**驗證輸出：**
- backend **463**／bff **21**／ext **159** tests，全部 0 failures 0 errors（雙格式專屬測試共 **83** 個）
- `vite build` 通過
- ext 對 backend `service.export` 的實際 import **0**；排程列表頁未帶新措辭的條數 **0**（改動前 9）；
  JOBS 仍 **46**；死符號 **0**；新增 `@Scheduled` **0**；新增 changeset **0**
- `arch-auditor` 查證：**0 critical、0 major、0 minor**

**與原計畫的偏差及原因：**
1. **`arch-auditor` 的四項「觀察」（非規範違反，但都是真問題），已全部處理：**
   - **`buildJson` 成為死符號**——本輪刪了同樣無人用的 `writeAtomically` 卻留下它，標準不一致。已刪。
   - **去抖任務捕捉「排定當下」的 xlsx Path，null 不會自癒**（真缺陷）：若視窗內**第一次**觸發剛好
     xlsx render 失敗，後續觸發全被合併（`if (st.scheduled) return`），到期任務仍以 `null` 執行；
     若那是當天最後一次觸發就**再也補不回來**——正是 Requirement 54 javadoc 自己警告的失效模式。
     已改為**在任務內以固定檔名重新解析兩個 Path**（存在才上傳），檔名固定＋當日全量重寫故必然拿到最新的。
   - **backend 側 xlsx 零測試覆蓋**（t272.5 本來就要求）：補 6 條——兩份主檔名一致、表頭動態推導
     （逐欄對上 JSON 的 key 順序）、空觸發仍含表頭、**`TRIGGER_FIELDS` 與 `buildPayload` 欄位一致**
     （那是第二份手寫副本，日後加欄位會靜默漂移，這是唯一探針）、xlsx 失敗不弄丟 JSON、run-now 欄位指向。
   - 前端文案句首「寫成 JSON」與句中「兩份」語意相左，已改中性措辭。
2. **`PublicInfoXlsxWriter` 用 `objectMapper.convertValue(payload, Map.class)` 取欄位集合**，
   而不是反射讀 record component——因為要的正是「與 JSON 同一組欄位」：`NewsRow.tags` 標了
   `@JsonIgnore`，反射會多出這一欄。兩者共用同一個 Spring `ObjectMapper` bean，serializer 一致。
3. **既有測試的修改都是預期變更**：`StockAlertTriggerExportTest` 的 `syncQuietly` 次數一律 ×2
   （1 輪 ＝ 2 次）、`NewsPollerGdriveSyncTest` 的 `syncToGdrive` 補 `null` 當 xlsx（既有斷言一字未減）。
   `arch-auditor` 逐條複核後確認**沒有任何守門被拆掉**。
