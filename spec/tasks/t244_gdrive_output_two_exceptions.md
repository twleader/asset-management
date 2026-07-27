# [t244] Drive 輸出：交易雷達與交易日曆兩個結構例外

**對應 Requirements:** Requirement 51（八個匯出頁的檔案除本機外可同步一份到 Google Drive；本機照寫不變）
**前置任務:** t242（共用元件 `GdriveOutputSupport`、`RcloneClient.copyTo`、changeset `v1.76.0` 的八張表欄位、八個 entity 欄位）— **必須先完成**。t243（六個結構相近的頁面）**建議先完成**，因為本任務的權限判定、狀態欄語意、best-effort 失敗處理都與它相同，先做完六頁再處理例外可以直接沿用同一組決策。
**Liquibase changeset:** 無（欄位已由 t242 的 `v1.76.0` 建好）

## 背景

Requirement 51 要把「本機照寫＋Drive 附加副本」推廣到八個匯出頁。t242 做完地基、t243 做完六個結構相近的頁面。剩下這兩頁**結構與其餘六頁不同，套同一個修改樣板會出錯**，故單獨成一支任務：

| 頁面 | Requirement | 設定表 | 為什麼是例外 |
|---|---|---|---|
| 交易雷達 | 48 | `trading_radar_export_setting` | 設定 DTO 是**裸單欄** `SettingRequest(String outputSubpath)`，controller 與 service 簽章都只傳一個字串；執行時間點存於**另一張表** `trading_radar_export_time`，**一天可能匯出多次** |
| 交易日曆 | 37 | `trading_calendar_export_schedule` | 排程 service **自己不寫檔**（委派 `TradingCalendarExportService`）；**沒有 run-now**（手動匯出是 `POST /run`，`subpath` 由 query param 帶入而非讀設定列）；UI 的輸出資料夾在**匯出對話框內**、與手動匯出共用同一欄位 |

**語意（硬約束，與 t241／t242／t243 一致）：本機一律照寫，Drive 只是附加副本，不提供「只寫 Drive」的選項。**

**權限與失敗處理完全沿用 t243 的決策**（以下為完整複寫，不需回頭翻 t243）：
- Drive 開關**只有主要管理者本人能啟用**：`PUT` 時非主要管理者要啟用 → **403**，且必須 `throw new AdminRequiredException()`（`GlobalExceptionHandler` 已映 403）。**不得用 `IllegalArgumentException`**（映 400）、**不得在 service 用 `ResponseStatusException`**（實測會被 `Exception` 兜底成 **500**）。
- **權限判定一律呼叫 `GdriveOutputSupport.isDriveAllowedFor(ownerUserId)`（t242.1.6），不得自行查 `AppUserRepository`**：它內部走 `findById → email → isConfiguredAdmin`（比對 `ADMIN_EMAIL`，全庫唯一一人），**查不到使用者一律 fail-closed 回 false**。不得用 `role == ADMIN` 或 `CurrentUserContext.isAdmin()`——`role` 可有多列 ADMIN（實測 `app_user` 現有兩名使用者），第二位若被升為 ADMIN 其報表照樣進到 remote 擁有者的 Drive。**代看（impersonation）情境**：business 端拿不到登入者身分，故以 `isDriveAllowedFor(effectiveUserId)` 天然 fail-closed 為準，殘餘風險與理由見 t242.1.6。
- **背景排程須逐列再驗一次**（背景無 `CurrentUserContext`，`PUT` 的檢查在此不適用）：呼叫**同一個** `isDriveAllowedFor(s.getOwnerUserId())`，不通過則跳過上傳並寫狀態欄說明原因。可行性已確認：`AppUser` 未套 `@Filter`，`TenantFilterAspect` 在無 request 情境時直接 return，故 `findById(ownerId)` 不會被 fail-closed 成空。
- **上傳失敗為 best-effort**：只記 `log.error` 與 `gdrive_last_status`，絕不 rollback 本機檔、絕不把既有 `last_run_status` 標記為失敗（本機那份確實成功了）、絕不擲例外影響其他 owner 的列。
- **凡「已啟用但未實際上傳」也必須寫狀態欄**（跳過原因），否則狀態會停在上一次的成功、顯示過期的好消息。
- **狀態欄寫入自身的失敗也要吞掉**。不實作 retry queue。
- 驗證規則一律委派 `GdriveOutputSupport`（開頭 `/` → 400、`..` 逐段比對、`a..b` 不誤擋、拒收 `:`、啟用時必填、關閉不清空既有值、`get()` 不因既有不合法值擲例外）。

## 要做什麼

### 244.1 交易雷達：DTO／controller／service 簽章改造

- [x] 244.1 現況（實測）是一路裸傳單一字串，加 Drive 欄位必須**四處一起改**，漏一處就會讓設定靜默存不進去：

  ```java
  // 1) dto/TradingRadarExportDto.java:20  —— 現況
  public record SettingRequest(String outputSubpath) {}

  // 2) controller/TradingRadarController.java:100-103  —— 現況：拆出單一欄位再傳
  @PutMapping("/export-schedule/setting")
  public TradingRadarExportDto.SettingResponse saveExportSetting(
          @RequestBody TradingRadarExportDto.SettingRequest request) {
      return exportScheduleService.saveSetting(request == null ? null : request.outputSubpath());
  }

  // 3) service/TradingRadarExportScheduleService.java:154  —— 現況
  public TradingRadarExportDto.SettingResponse saveSetting(String outputSubpath) {

  // 4) frontend/src/api/index.js:271  —— 現況
  saveExportSetting: (outputSubpath) => api.put('/bff/trading-radar/export-schedule/setting', { outputSubpath }),
  ```

- [x] 244.1.1 改法：`SettingRequest` 加 `gdriveEnabled`（**包裝型別 `Boolean`**）與 `gdriveSubpath`；controller 改為**傳整個 request** 而非拆單一欄位；`saveSetting` 簽章改收 DTO；前端 helper 改收物件（`(payload) => api.put(..., payload)`）。
- [x] 244.1.2 **`gdriveEnabled` 必須是包裝型別 `Boolean`**：要能分辨「明確送 false」與「整個欄位沒送」。後者（舊版前端、或只想改本機路徑的呼叫端）**不應把已開啟的 Drive 開關靜默關掉**。null 視為「不變更」；`gdriveSubpath` 為 null 時同樣保留既有值。**這正是 t241 在爬蟲頁踩過的坑**（`saveExportPath` 原本只送單一字串，前端存了卻沒生效），本頁的裸字串結構是同一個形狀，特別容易再犯。
- [x] 244.1.3 `SettingResponse` 加 `gdriveEnabled`（boolean）、`gdriveSubpath`、`gdriveRemote`（衍生顯示值不入庫）、`gdriveLastRunAt`（格式化字串，比照既有 `lastRunAt`）、`gdriveLastStatus`。維持 `record` 不可變。

### 244.2 交易雷達：上傳整合與多時間點語意

- [x] 244.2 寫檔結構（實測）：`writeDailyExport(long ownerId, LocalDate today)`（`:267`）內部呼叫 `writeAtomically(currentSubpath(ownerId), filename, data)`（`:277`）；`writeDailyExport` 有**兩個呼叫點**——`:172` 與 `:238`（分別對應排程與 run-now 路徑，實作時逐一確認哪個是哪個）。
- [x] 244.2.1 **上傳插在 `writeDailyExport` 的呼叫點之後，不要動 `writeAtomically()`**——後者是純寫檔工具，沒有設定列情境，拿不到 `gdrive_subpath`。呼叫點才有設定列可讀。
- [x] 244.2.2 **順序不可顛倒**：本機檔寫成功才上傳；本機失敗時完全不上傳（絕不上傳前一次的舊檔）。
- [x] 244.2.3 run-now（`POST /api/trading-radar/export-schedule/run-now`，實測存在於 `TradingRadarController:107`）**也必須上傳並回報落點**——run-now 的用途就是驗證落點正確。
- [x] 244.2.4 **本頁一天可能上傳多次**：執行時間點存於另一張表 `trading_radar_export_time`（一列一時間點），與其餘七頁「每日單一時間」不同。故 `gdrive_last_run_at`／`gdrive_last_status` 是**「最後一次」語意**，不是「今天那一次」。前端文案不要寫成「今日上傳結果」。
- [x] 244.2.5 檔名沿用既有規則（含 owner id），Drive 上與本機同名同內容；同一天多輪覆寫同一檔案（與本機行為一致）。
- [x] 244.2.6 **`writeDailyExport` 當日無快照時回 `null` 且不寫檔**（既有行為）。上傳必須寫成 `if (file != null)`；`null` 時依「已啟用但未上傳也要寫狀態欄」把 `gdrive_last_status` 寫成跳過原因（例「跳過：當日無交易雷達快照」）。
- [x] 244.2.7 **逾時與確定性失敗的措辭分開**（承 t242.2.5.1）：逾時寫「逾時（45 秒）：Drive 端可能已完成，請於下一輪確認」，只有 rclone 非零退出才寫「失敗：<rclone 錯誤>」。實測有過「狀態欄記逾時失敗、Drive 上檔案卻完整且與本機逐 byte 相同」的假失敗。

### 244.3 交易雷達：前端

- [x] 244.3 `frontend/src/views/TradingRadarView.vue` 的排程設定區塊，在既有「輸出資料夾」之下新增「同步 Google Drive」`el-switch` ＋ Drive 資料夾欄位（readonly ＋「選擇」按鈕）＋「上次上傳」唯讀顯示（文案用「上次上傳」而非「今日上傳」，承 244.2.4）。
- [x] 244.3.1 `frontend/src/api/index.js` 的 trading-radar 區塊：`saveExportSetting` 改收物件（承 244.1.1），並新增 `browseGdriveExportDir: (subpath = '') => api.get('/bff/trading-radar/export/browse-gdrive', { params: { subpath }, skipErrorToast: true })`（`skipErrorToast` 是同檔既有 `browseExportDir` 的慣例，讓錯誤顯示在 dialog 內、不與全域 toast 打架）。
- [x] 244.3.2 資料夾選擇器沿用 t241 的雙模式寫法（範本見 `frontend/src/views/CrawlerDataView.vue`：`dirPicker` 帶 `mode: 'local' | 'gdrive'`、`dirPickerTitle` 依 mode 切標題、`openDirPicker(mode)`、樹的 load 依 mode 選 API）。Drive 回傳形狀與本機相同，不需第二套渲染邏輯。
- [x] 244.3.3 **dialog 的「目前選擇」預覽，兩種 mode 分隔符不同**：Drive 基底是 `remote:`（已含冒號，**直接接**子路徑），本機基底是 `/home/steven`（需 `/` 分隔）。混用會顯示成 `GDriveOutput:/投資理財`——多一個斜線、不是 rclone 格式。（t241 犯過，由使用者截圖發現。）
- [x] 244.3.4 Drive 樹載入失敗顯示後端訊息，**不得顯示成空樹**（空樹會被誤讀為「Drive 裡沒有資料夾」）。開關關閉時欄位停用但**保留已填值**。開關開啟而資料夾為空時前端擋下儲存並提示。
- [x] 244.3.5 **非主要管理者不顯示 Drive 開關與欄位**：本 view 目前**沒有引入 auth store**（實測 `isAdmin`／`useAuthStore` 在此頁 grep 為 0），需新增 `import { useAuthStore } from '@/stores/authStore'`，條件用 **`auth.isConfiguredAdmin`**（t242.5.2 新增的 getter）。**不得用 `auth.isAdmin`**——那是 `role === 'ADMIN'` 判準，與後端 403 的判準不一致，會出現「畫面顯示得了、按儲存卻 403」。真正的閘門在後端，前端只是不顯示。

### 244.4 交易日曆：設定 DTO、寫入側與上傳插入點

- [x] 244.4.0 **本頁的設定 DTO 不在 t243 的六個清單內，且 record 名與其餘七頁不同。** 實測：`TradingCalendarExportDto` 的 record 名為 **`ScheduleSettingRequest`／`ScheduleSettingResponse`**（其餘七頁是 `SettingRequest`／`SettingResponse`）——**不要照抄 t243 的類名**。沒有這一步，244.6.4 要前端送 `gdriveEnabled`／`gdriveSubpath`、244.4.1 又假設排程列上讀得到這兩欄，中間是斷的；而 Spring 預設關閉 `FAIL_ON_UNKNOWN_PROPERTIES`，**未開欄位的 payload 會被 Jackson 靜默丟棄**，症狀就是「前端存了卻沒生效」。
  - `ScheduleSettingRequest` 加 `Boolean gdriveEnabled`（**包裝型別，null＝不變更**）＋ `String gdriveSubpath`
  - `ScheduleSettingResponse` 加 `gdriveEnabled`／`gdriveSubpath`／`gdriveRemote`／`gdriveLastRunAt`／`gdriveLastStatus`
  - `TradingCalendarExportScheduleService.updateForCurrentUser(:72)` 委派 `GdriveOutputSupport` 驗證，並在啟用時做 `isDriveAllowedFor` → 不通過擲 `AdminRequiredException`（403）
  - `toResponse(:167)` 回填新欄位


- [x] 244.4 結構（實測）：`TradingCalendarExportScheduleService` 自己**完全不寫檔**（該檔 `Files.` 命中 0 次），排程於 `:143` 委派 `exportService.exportToDir(today.getYear(), s.getFormat(), s.getOutputSubpath())`；實際寫檔在 `TradingCalendarExportService.exportToDir(int year, String format, String subpath)`（`:79`）內的 `writeAtomically`。
- [x] 244.4.1 **排程路徑的上傳插在 `TradingCalendarExportScheduleService:143` 的委派呼叫之後**（那裡有設定列 `s`，能取 `gdriveEnabled`／`gdriveSubpath` 與 `ownerUserId`）。`exportToDir` 回傳的 `RunResponse` 帶本機落點，用它組上傳來源。**不要**把上傳塞進 `TradingCalendarExportService.exportToDir`——見 244.5。
- [x] 244.4.2 **順序不可顛倒**；本機寫檔失敗時不上傳。

### 244.5 交易日曆：手動匯出路徑（本頁沒有 run-now）

- [x] 244.5 **本頁沒有 `run-now`。** 其手動匯出是（實測）：
  ```java
  // controller/TradingCalendarExportController.java:34
  @PostMapping("/run")
  public TradingCalendarExportDto.RunResponse run(
          @RequestParam(required = false) Integer year,
          @RequestParam(defaultValue = "json") String format,
          @RequestParam(required = false, defaultValue = "") String subpath) {
      return service.exportToDir(targetYear, format, subpath);
  }
  ```
  三個後果：（a）端點是 `/run` 不是 `/run-now`；（b）**`subpath` 由 HTTP query param 給、不讀設定列**，所以本機落點可能與排程設定的目錄不同；（c）`exportToDir(int, String, String)` **完全沒有 owner 情境**，要拿 `gdrive_subpath` 必須另外去讀該使用者的 `TradingCalendarExportSchedule` 列。

- [x] 244.5.1 **裁定：手動匯出路徑也要上傳 Drive，且實作點在排程 service，不在 controller。** 新增 `TradingCalendarExportScheduleService.runManualForCurrentUser(Integer year, String format, String subpath)`：內部委派 `exportService.exportToDir(...)`，再讀**當前使用者的設定列**取 `gdriveEnabled`／`gdriveSubpath` 後上傳。controller 的 `/run` 改為 `return scheduleService.runManualForCurrentUser(year, format, subpath);` **維持純委派**。
  - **不得把讀設定列與上傳寫在 controller**：`structure.md` 2.2 明訂 Controller「不寫業務邏輯、不直接讀 repository」。而且乾淨解法就在眼前——`TradingCalendarExportController` 已經同時注入 `service` 與 `scheduleService`，`/run` 目前也已是純委派。
  - 理由（為何手動路徑也要上傳）：run-now／手動匯出的用途就是驗證落點正確，若它不上傳，使用者只能等排程才知道 Drive 設定對不對——這正是 t241 在爬蟲頁缺 run-now 造成的實際不便。
- [x] 244.5.2 **不要把上傳邏輯放進 `TradingCalendarExportService.exportToDir`**：該方法被排程與手動兩條路徑共用，且簽章沒有 owner；在裡面查「當前使用者」在背景排程情境下沒有 request context，會拿到錯的人或 null。**兩條路徑各自在自己的層級處理上傳**，共用的只有 `GdriveOutputSupport`。
- [x] 244.5.3 **`subpath` 來源不一致要在程式註解中記載**：手動匯出的本機目錄來自 query param、Drive 目錄來自設定列，兩者語意刻意不同（前者是「這次匯出到哪」，後者是「Drive 同步的固定目的地」）。不要為了「一致」而讓 Drive 也吃 query param——那會讓使用者每次手動匯出都可能把檔案倒進 Drive 的不同位置。

### 244.6 交易日曆：前端（UI 位置與其餘七頁不同）

- [x] 244.6 **本頁的輸出資料夾不在頁面卡片上，而在匯出對話框內、與手動匯出共用同一欄位**（實測 `frontend/src/views/TradingCalendarView.vue`）：
  ```
  :140   <!-- 匯出對話框（Requirement 37）：年度 / 格式 / 輸出資料夾 -->
  :152        <el-form-item label="輸出資料夾">
  :170        <!-- 每日排程自動匯出（Task 185）：共用上方格式／資料夾 -->
  :171        <el-divider content-position="left">每日排程自動匯出</el-divider>
  ```
  其餘七頁都是頁面上的獨立 `el-card`。故「在排程設定卡的輸出資料夾之下新增 Drive 開關」在本頁沒有對應容器。

- [x] 244.6.1 **裁定：Drive 開關掛在「每日排程自動匯出」`el-divider` 之下**（`:171` 起的區塊），不放在上方共用的「輸出資料夾」旁邊。理由：Drive 子路徑存在**排程設定列**上（承 244.5.3），語意屬於排程；放在上方共用區會讓使用者以為它跟著 query param 的那個資料夾一起變。
- [x] 244.6.2 手動匯出仍會上傳（承 244.5.1），故該區塊的說明文案要寫清楚「**手動匯出也會同步**」，避免使用者以為只有排程會上傳。
- [x] 244.6.3 Drive 資料夾的「選擇」按鈕會在**已開啟的匯出對話框內**再開一個選擇器 dialog。兩個 `el-dialog` 是**平行的兄弟節點**（不是真的巢狀），而既有的本機資料夾選擇器已經在同樣情境下正常疊加，故**不需要 `append-to-body` 或 z-index 特別處理**。仍須在瀏覽器實際點一次確認沒有被蓋住或無法點擊。
- [x] 244.6.4 `frontend/src/api/index.js` 的 trading-calendar 區塊新增 `browseGdriveExportDir: (subpath = '') => api.get('/bff/trading-calendar/export/browse-gdrive', { params: { subpath }, skipErrorToast: true })`；並確認該頁儲存排程設定的函式**送出完整 payload**（含 `gdriveEnabled`／`gdriveSubpath`）。
- [x] 244.6.5 選擇器沿用雙模式寫法、預覽分隔符處理、載入失敗顯示訊息、開關關閉保留值——與 244.3.2／244.3.3／244.3.4 相同要求。
- [x] 244.6.6 **加「上次上傳」唯讀顯示**（`gdriveLastRunAt` ＋ `gdriveLastStatus`，無值顯示「—」）。R51 要求每頁都要有，本頁原本漏列。
- [x] 244.6.7 **非主要管理者不顯示 Drive 開關與欄位**：本 view 同樣沒有引入 auth store，需新增 `import { useAuthStore } from '@/stores/authStore'`，條件用 `auth.isConfiguredAdmin`（**不得用 `auth.isAdmin`**，理由同 244.3.5）。

### 244.7 BFF：兩支 `browse-gdrive` passthrough

- [x] 244.7 兩個 BFF controller 各新增（依「一頁一 BFF」）：
  - `TradingRadarBffController`：既有 `/export/browse` → 新增 `/export/browse-gdrive`
  - `TradingCalendarBffController`：既有 `/export/browse` → 新增 `/export/browse-gdrive`
- [x] 244.7.1 **兩支都指向同一支** business `GET /api/export-schedule/browse-gdrive`（t241 建立、t242 已把邏輯遷入共用元件，端點路徑不變）。**交易日曆頁尤其注意**：它的**本機**目錄列舉走自己那份 `GET /api/trading-calendar-export/browse`（既有分裂，**本任務不修**），但 **Drive 側不得再開第二份**——不可在 `TradingCalendarExportService` 加 Drive 列舉（CLAUDE.md「不同頁面顯示同樣意義的值須呼叫同一支 business service API」）。
- [x] 244.7.2 **不做 `onErrorReturn` 降級**：remote 未設定／授權失效時要讓可讀錯誤浮到前端 dialog。
- [x] 244.7.3 檢查兩支 BFF 的設定 `PUT` 是否有 DTO 鏡像類別：`Map<String,Object>` 直通則新欄位自動穿透；**強型別 DTO 必須同步加欄位**，漏一個就會讓 Drive 設定在 BFF 層被靜默吃掉。

### 244.8 排程列表頁說明同步

- [x] 244.8 本任務**未新增任何 `@Scheduled`**，故「公開資訊 → 排程列表」（`bff/.../schedulelist/SchedulePublicBffController` 的 `JOBS`）**不需新增項目**；但這兩筆的 description 須補上「輸出含 Google Drive 同步（若已啟用）」。
- [x] 244.8.1 `JOBS` 是無鍵的 `List<ScheduledJobDto>`，**不能用資料表名或 service 名 grep**。本任務兩筆靠 group／name 字串定位：「**交易雷達匯出**」與「**交易日曆**」。（t241 踩過這個坑：`grep news-poller` 在該檔命中 0 次。）

### 244.9 測試（與實作同屬本任務交付）

- [x] 244.9 在 `backend/src/test/java/com/steven/assets/service/` 新增測試（**測試檔命名由實作者定**）。以下是本任務**特有**的關鍵迴歸（權限與 best-effort 的通用迴歸已由 t243 涵蓋，但這兩支 service 仍須各自驗證一次）：
  - **交易雷達的 `SettingRequest` 改造未破壞既有行為**：只送 `outputSubpath`（不送 Drive 欄位）時，本機路徑正常更新且**既有的 Drive 設定不被清掉**（244.1.2）
  - **交易雷達一天多次匯出**：`trading_radar_export_time` 有兩個時間點時，兩次都會上傳，`gdrive_last_status` 為最後一次（244.2.4）
  - **交易日曆手動匯出（`POST /run`）會上傳，且 Drive 目錄取自設定列而非 query param 的 `subpath`**（244.5.1／244.5.3）——這是本任務最容易做錯的一項
  - **交易日曆排程路徑會上傳**，且本機檔仍照寫
  - 非主要管理者啟用 → 403；背景排程遇非主要管理者 owner → 跳過上傳並寫狀態欄（兩支 service 各一）
  - 上傳失敗不影響本機：rclone 擲例外時本機檔仍存在、既有 `last_run_status` 為成功、`gdrive_last_status` 為失敗、不向外擲例外
- [x] 244.9.1 rclone 呼叫一律以 `RcloneClient` 介面替身注入，**不實際連網**。
- [x] 244.9.2 **既有行為回歸**：交易雷達的多時間點排程 guard、交易日曆的 `format`（json／excel）分支與年度參數、兩頁的 owner 隔離，均須確認未因本次變更而改變。
- [x] 244.9.3 Mockito 於本專案需 `-DargLine="-Dnet.bytebuddy.experimental=true"`（直接 `-D` 無效，surefire 會 fork）。

## 驗證

```bash
# 1. 後端與 BFF 建置測試
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test \
  -DargLine="-Dnet.bytebuddy.experimental=true"
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml package -DskipTests

# 2. 從 worktree build 前先把主 repo 的 .env 複製進來（env_file 相對 compose 檔解析）
cp /Users/steven/Project/asset-management/.env .

# 3. JVM service 一律 --no-cache（cached build 會產出 stale jar）
docker compose -p asset-management build --no-cache business-services bff
docker compose -p asset-management build --no-cache frontend
docker compose -p asset-management up -d --no-deps --force-recreate business-services bff frontend

# 4. recreate business 後必須 restart bff（換 IP、Docker DNS TTL 600s 內不自癒）
docker compose -p asset-management restart bff

# ⚠ 以下步驟會改寫「生產中」的設定。先存檔現值，跑完務必還原。
docker exec asset-postgres psql -U assets -d assets -c \
  "SELECT owner_user_id, output_subpath, gdrive_enabled, gdrive_subpath FROM trading_radar_export_setting; \
   SELECT owner_user_id, format, output_subpath, gdrive_enabled, gdrive_subpath FROM trading_calendar_export_schedule"

# 5. 交易雷達：只送 outputSubpath（不送 Drive 欄位）不得清掉既有 Drive 設定（244.1.2 迴歸）
#    body 的 outputSubpath 請照抄上面存下的現值，不要寫死成 input
docker exec asset-business-services sh -c 'curl -s -X PUT \
  -H "X-User-Id: 1" -H "X-User-Role: ADMIN" -H "X-User-Status: ACTIVE" \
  -H "Content-Type: application/json" -d "{\"outputSubpath\":\"input\"}" \
  "http://localhost:8080/api/trading-radar/export-schedule/setting"'
docker exec asset-postgres psql -U assets -d assets -c \
  "SELECT gdrive_enabled, gdrive_subpath FROM trading_radar_export_setting"   # 應保留原值

# 6. 交易日曆手動匯出：Drive 目錄取自設定列、不受 query param subpath 影響（244.5.3 迴歸）
docker exec asset-business-services sh -c 'curl -s -X POST \
  -H "X-User-Id: 1" -H "X-User-Role: ADMIN" -H "X-User-Status: ACTIVE" \
  "http://localhost:8080/api/trading-calendar-export/run?year=2026&format=json&subpath=Downloads"'
# 本機應落在 Downloads，Drive 應落在設定列的 gdrive_subpath（兩者刻意不同）
rclone lsl --config ~/.config/rclone/rclone.conf "GDriveOutput:投資理財/資產管理"
ls -l ~/Downloads/交易日曆_2026.* || echo '本機未落檔（失敗）'   # 檔名是中文：交易日曆_{year}.json/.xlsx

# 7. 狀態欄
docker exec asset-postgres psql -U assets -d assets -c \
  "SELECT 'radar' AS t, gdrive_last_run_at, gdrive_last_status FROM trading_radar_export_setting
   UNION ALL
   SELECT 'calendar', gdrive_last_run_at, gdrive_last_status FROM trading_calendar_export_schedule"

# 8. **還原生產設定**（用第 0 步存下的現值）
#     兩張表各發一次 PUT，body 照抄原值、不含 gdrive 欄位（null＝不變更，故 Drive 設定會保留）

# 9. 八張表全部有四個 gdrive 欄位（t242 已建，此處為整體回歸）
for t in export_schedule_setting realized_gain_export_schedule asset_transaction_export_schedule \
         commodity_export_schedule exchange_rate_export_schedule index_export_schedule \
         trading_radar_export_setting trading_calendar_export_schedule; do
  printf "%-38s " "$t"
  docker exec asset-postgres psql -U assets -d assets -c "\d $t" | grep -c gdrive_
done
```

**必須在瀏覽器實際操作確認（不可只憑 API 回應宣稱完成）：**

1. **交易雷達**：排程設定區出現「同步 Google Drive」開關、Drive 資料夾欄位、「上次上傳」（文案不是「今日上傳」）。選擇器能展開 Drive 樹、預覽為 `GDriveOutput:資產管理`（**無多餘斜線**）、儲存成功。
2. **交易雷達**：只改本機資料夾後儲存，確認 Drive 開關與資料夾**沒有被清掉**。
3. **交易日曆**：打開匯出對話框 → 「每日排程自動匯出」區塊下出現 Drive 開關與資料夾欄位 → 點「選擇」→ **確認選擇器 dialog 正常顯示在最上層、可點擊**（244.6.3，嵌套 dialog 的 z-index 風險）。
4. **交易日曆**：對話框內手動匯出一次 → 確認本機落在對話框指定的資料夾、Drive 落在設定列的目錄（兩者刻意不同）、「上次上傳」更新。
5. 兩頁各按一次立即／手動匯出 → 確認本機檔照樣產生、Drive 出現同名同大小檔案。
6. 以非主要管理者身分 → 兩頁的 Drive 開關與欄位**不顯示**，本機路徑與排程時間仍可設定。
7. 故意把 `GDRIVE_OUTPUT_REMOTE` 設成不存在的名稱並 recreate business → 本機仍照寫、既有「上次執行」仍為成功、只有「上次上傳」記失敗；設定頁仍能載入與儲存（不得 500）。

## 完成報告

**實際改了哪些檔**

*交易雷達*（四處必須一起改，漏一處就靜默存不進去）：

1. `TradingRadarExportDto.SettingRequest` — `(String outputSubpath)` → `(String outputSubpath, Boolean gdriveEnabled, String gdriveSubpath)`；`SettingResponse` ＋5 欄；`RunNowResponse` ＋`gdrivePath`／`gdriveStatus`
2. `TradingRadarController.saveExportSetting` — 由 `request.outputSubpath()` 拆欄改為**傳整個 request**
3. `TradingRadarExportScheduleService.saveSetting(String)` → `saveSetting(SettingRequest)`；`runNow` 三個分支與 `runScheduled` 三個分支各接 `syncGdrive(ownerId, file, skipReason)`；新增私有 `syncGdrive`（自行 upsert 設定列並 save，比照既有 `recordStatus` 的形狀，**save 失敗被吞掉**）
4. `frontend/src/api/index.js` 的 `saveExportSetting` — 由裸字串改收整包 payload

*交易日曆*：

- `TradingCalendarExportDto` — `ScheduleSettingRequest`／`ScheduleSettingResponse`（**注意本頁 record 名是 `ScheduleSetting*` 而非 `Setting*`**）各加 Drive 欄位；`RunResponse` ＋`gdrivePath`／`gdriveStatus`
- `TradingCalendarExportScheduleService` — `updateForCurrentUser` 走 `resolveUpdate`；新增 **`runManualForCurrentUser(year, format, subpath)`**；`runScheduled` 成功／失敗兩分支各接 `syncGdrive(s, ...)`；新增 `syncGdrive`（只改記憶體欄位）＋`saveQuietly`
- `TradingCalendarExportController.run` — 改為 `scheduleService.runManualForCurrentUser(...)`，**維持純委派**（未在 controller 讀 repository）
- 前端 `TradingRadarView.vue`／`TradingCalendarView.vue`：Drive 開關、資料夾欄位、「上次上傳」（雷達的文案為「上次上傳」而非「今日上傳」）、雙模式選擇器、`useAuthStore`
- BFF 兩支 `browse-gdrive`；`SchedulePublicBffController` 兩筆 description

**測試輸出**：`TradingExportGdriveTest`（12 項）全綠，涵蓋本任務特有的四條迴歸：

- **雷達：只送 `outputSubpath`（Drive 兩欄為 null）→ 本機路徑更新、`gdriveEnabled`／`gdriveSubpath` 原封不動**（244.1.2）
- **雷達：一天兩個時間點 → `copyTo` 被呼叫 2 次，狀態欄為最後一次**（244.2.4）；當日無快照 → 不上傳、狀態寫「跳過…快照」（244.2.6）
- **日曆：手動匯出本機落在 query param 的 `Downloads`，而 `copyTo` 收到的是設定列的 `投資理財/資產管理`**（244.5.1／244.5.3，本任務最容易做錯的一項）
- 日曆：排程路徑會上傳；本機失敗時不上傳但寫「跳過」；兩頁各自的非主要管理者 403

全庫合計 **Tests run: 205, Failures: 0, Errors: 0**。

**與原計畫的偏差**

1. **`TradingCalendarExportController` 的年度預設值計算移入 service**：原本 controller 內 `year != null ? year : LocalDate.now().getYear()`（無時區），移入 `runManualForCurrentUser` 後改用既有的 `TW_ZONE`。這是純委派化的必要副產物，且跨年夜的行為更正確。
2. **日曆的手動匯出在「設定列不存在」時不上傳也不建列**（任務檔未明訂）：只手動匯出、從未設過排程的使用者沒有 Drive 目的地可讀，建列會憑空產生一筆使用者沒設定過的排程列。已有測試涵蓋。
3. 其餘偏差（B 組插入點、`BusinessErrorAdvice`、`AdminRequiredException` 訊息 constructor、`resolveUpdate` 的權限觸發時機）與 t243 相同，說明見 t243 完成報告。

**尚待使用者實機確認**：兩頁的 Drive 落檔證據、`POST /run` 的兩處落點對照輸出、以及 **244.6.3 的嵌套 dialog 實機點擊**（選擇器 dialog 是否正常顯示在匯出對話框之上）——後者只能在瀏覽器確認，程式面已依判斷不加 `append-to-body`（兩個 `el-dialog` 為平行兄弟節點，既有本機選擇器已在同樣情境正常疊加）。
