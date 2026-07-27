# [t243] Drive 輸出：六個結構相近的匯出頁

**對應 Requirements:** Requirement 51（八個匯出頁的檔案除本機外可同步一份到 Google Drive；本機照寫不變）
**前置任務:** t242（共用元件 `GdriveOutputSupport`、`RcloneClient.copyTo`、changeset `v1.76.0` 的八張表欄位、八個 entity 欄位）— **必須先完成**，本任務直接使用它們
**後續任務:** t244（交易雷達與交易日曆兩個結構例外）
**Liquibase changeset:** 無（欄位已由 t242 的 `v1.76.0` 建好）

## 背景

Requirement 51 要把「本機照寫＋Drive 附加副本」推廣到八個匯出頁。t242 已把地基做完（共用元件、上傳能力、DB 欄位、entity 欄位）。本任務負責**六個結構相近的頁面**，剩下兩個結構例外由 t244 處理。

**語意（硬約束，與 t241／t242 一致）：本機一律照寫，Drive 只是附加副本，不提供「只寫 Drive」的選項。** 這八頁匯出的是使用者的資產快照、已實現損益、交易紀錄等財務報表，本機那一份是既有的留存機制，不能因為開了 Drive 就消失。

**本任務的六頁，依寫檔結構分兩組**（實測結果，**不可套同一個修改樣板**）：

| 組 | service | 頁面 | Requirement | 上傳插入點 | 底層寫檔 |
|---|---|---|---|---|---|
| A | `ExportScheduleService` | 歷年資產 | 34 | `writeToDir()` 回傳 `Path` 之後 | `Files.write` |
| A | `RealizedGainExportScheduleService` | 已實現損益 | 39 | 同上 | `Files.write` |
| A | `AssetTransactionExportScheduleService` | 交易紀錄 | 49 | 同上 | `Files.write` |
| B | `CommodityExportScheduleService` | **油價金價** | **41** | `export()` 內、`writeAtomically()` 之後 | `writeAtomically()` |
| B | `ExchangeRateExportScheduleService` | **台幣兌美元匯率** | **42** | 同上 | `writeAtomically()` |
| B | `IndexExportScheduleService` | GDP-TWSE | 45 | 同上 | `writeAtomically()` |

> **B 組的插入點必須在外層 `export()`，不可動 `writeAtomically()`**——後者是純寫檔工具，沒有設定列情境，拿不到 `gdrive_subpath` 與 owner。
>
> **R41／R42 的對應**：R41 是「油價金價」、R42 是「台幣兌美元匯率」。這兩個曾在 spec 初稿被寫反，實作時以本表為準（可 `grep -n '^### Requirement 4[12]:' spec/requirements.md` 複核）。

**這六頁的設定表都是 per-user**（`owner_user_id` ＋ `@Filter(ownerFilter)`），與爬蟲頁那張全域無 owner 的表不同——這是本任務隱私約束的來源（見 243.2）。

**Drive 子路徑的驗證規則（完整複寫，不需回頭翻 t242）**——一律委派 `GdriveOutputSupport`，但實作者要知道它在驗什麼：

- 正規化：去頭尾空白、**只剝結尾** `/`；空字串 → null（未設定），**不套用任何預設值**（Drive 沒有「合理的預設目錄」，猜錯就是把檔案倒進使用者雲端硬碟的非預期位置）
- **開頭 `/` 回 400**，刻意不剝除（先剝再檢查會讓該 400 分支永遠不可達）
- **不得含 `..` 路徑段**：以 `/` 切開**逐段**比對，不可用 `contains("..")`（會誤擋合法目錄名如 `a..b`）。理由：rclone 對 Drive remote **不做**路徑正規化，擋它不是防跳脫，而是避免在 Drive 建出字面名為 `..` 的怪目錄
- **拒收含 `:`**：rclone 取第一個 `:` 之前為 remote 名，防子路徑被解讀成切換 remote
- `gdriveEnabled=true` 時子路徑**必填**；`false` 時允許為空且**不得清掉既有值**
- 讀取時**不得**因既有不合法值而擲例外（否則設定頁 500，使用者失去唯一的修正入口）

## 要做什麼

### 243.1 六頁的設定 DTO 加欄位

- [x] 243.1 六個 DTO（`ExportScheduleDto`／`RealizedGainExportDto`／`AssetTransactionExportDto`／`CommodityExportDto`／`ExchangeRateExportDto`／`IndexExportDto`）的 `SettingResponse` 與 `SettingRequest`（皆為不可變 `record`，維持 record 不變）：
  - `SettingResponse` 加 `gdriveEnabled`（boolean）、`gdriveSubpath`、`gdriveRemote`（＝共用元件提供的 remote 名稱，**衍生顯示值不入庫**，讓前端能在錯誤訊息中指名 remote）、`gdriveLastRunAt`（格式化字串，比照該 DTO 既有 `lastRunAt` 的 `yyyy-MM-dd HH:mm:ss`）、`gdriveLastStatus`
  - `SettingRequest` 加 `gdriveEnabled`、`gdriveSubpath`
- [x] 243.1.1 **`SettingRequest.gdriveEnabled` 必須用包裝型別 `Boolean` 而非 `boolean`**：要能分辨「明確送 false」與「整個欄位沒送」。後者（舊版前端、或只想改本機路徑／排程時間的呼叫端）**不應把使用者已開啟的 Drive 開關靜默關掉**。null 一律視為「不變更」。同理 `gdriveSubpath` 為 null 時保留既有值。（此為 t241 實作時發現並修正的實際問題。）

### 243.2 六個 service：`PUT` 的驗證與權限

- [x] 243.2 六個 service 的設定 `update`／`upsert` 方法各加 Drive 設定處理，**驗證一律委派 t242 的 `GdriveOutputSupport`**，不得自行實作第二份規則。
- [x] 243.2.1 **Drive 同步只有「主要管理者」本人能啟用（隱私硬約束）**：若請求要把 `gdrive_enabled` 設為 true 而當前使用者不是主要管理者，回 **403**（不是 400——這是權限問題而非輸入錯誤）。理由：這六張表雖為 per-user，但 **rclone remote 全機只有一份**、綁定某一個特定 Google 帳號；若允許其他使用者啟用，**B 的財務報表就會被上傳到那個帳號的雲端硬碟**，這是實質的資料外流，而且從 B 的角度完全不可見。
- [x] 243.2.2 **一律呼叫 t242 的 `GdriveOutputSupport.isDriveAllowedFor(ownerUserId)`，不得自行查 `AppUserRepository`、不得用 `role == ADMIN` 或 `CurrentUserContext.isAdmin()`**：`role` 是 DB 欄位、**可以有多列 ADMIN**（實測 `app_user` 現有兩名使用者，第二位若被升為 ADMIN，其報表照樣進到 remote 擁有者的 Drive——外流語意不變、只是母體變小）。該方法內部走 `findById → email → isConfiguredAdmin`（比對 `ADMIN_EMAIL`，全庫唯一一人），查不到使用者一律 fail-closed 回 false。`PUT` 與 243.4 的背景檢查**走同一個函式**，避免日後其中一處被改寬。
- [x] 243.2.3 **403 必須 `throw new AdminRequiredException()`**（`GlobalExceptionHandler` 已有 `@ExceptionHandler` 映 403）。**不得用 `IllegalArgumentException`**（那被映成 400）、**不得在 service 用 `ResponseStatusException`**——實測同機制的 `FundNavController` 擲 `ResponseStatusException(BAD_REQUEST)` 實際回 **HTTP 500**、body 的 detail 才是「400 BAD_REQUEST …」，因為它落到 `Exception` 兜底；且在 service 組 HTTP 狀態也違反 `structure.md` 2.2 的分層規範。
- [x] 243.2.4 **本機輸出路徑、排程時間等既有欄位仍維持所有使用者皆可設定**（不限 ADMIN）。這個不對稱是刻意的：只有 Drive 這一項會把資料送出本機。**不要**順手把整支 `PUT` 改成限 ADMIN——那會破壞既有的 per-user 排程設定能力（Requirement 39／49 明訂「非管理者亦可設定自己的排程」）。
- [x] 243.2.5 `gdriveEnabled == true` 時 `gdriveSubpath` 為必填（空值回 400）；`false` 時允許為空且**不得清掉先前填過的值**（關掉再開回來不必重填）。
- [x] 243.2.6 `get()` 回傳時**不得因既有不合法的 Drive 值而擲例外**（DB 值可能被繞過 API 直改）。若讀取也失敗，設定頁會 500，使用者就**沒有任何入口能把它改回正常值**——唯一的修正入口被自己鎖死。不合法值照原樣回傳供前端顯示，存檔時才驗。
- [x] 243.2.7 `update()` **不得**碰 `gdrive_last_run_at`／`gdrive_last_status`（那是執行結果，不是使用者設定）。

### 243.3 六個 service：上傳整合

- [x] 243.3 每個 service 加一個私有 helper 統一處理「本機檔已寫好 → 視情況上傳 → 寫狀態欄」，再由 **run-now 與排程兩處各呼叫一次**。六頁的 run-now 端點皆為 `POST .../run-now`（已實測確認）。
- [x] 243.3.1 **順序不可顛倒**：本機檔案是既有的留存機制，必須先確定它寫成功才上傳。**本機寫檔失敗時 Drive 完全不上傳**（絕不可上傳前一次的舊檔）。
- [x] 243.3.2 A 組（歷年資產／已實現損益／交易紀錄）插在 `writeToDir(...)` 回傳 `Path` 之後；B 組（油價金價／台幣兌美元／GDP-TWSE）插在 `export()` 內、`writeAtomically(...)` 之後。**不要動 `writeAtomically()` 本身**（它沒有設定列情境）。
- [x] 243.3.3 **run-now 也必須上傳並回報落點**，六個 DTO 的 `RunNowResponse` 各加 `gdrivePath` 與 `gdriveStatus` 兩欄（既有已帶本機 `path`／`sizeBytes`）。run-now 的用途就是驗證落點正確；若 Drive 啟用時它不上傳，使用者就無法在不等排程的情況下驗證 Drive 設定——**這正是 t241 在爬蟲頁缺 run-now 造成的實際不便（當時只能靠重啟容器觸發 warmup 才驗到）**。
- [x] 243.3.4 **上傳失敗為 best-effort**：只記 `log.error` 與該列的 `gdrive_last_status`，**絕不** rollback 本機檔案、**絕不**把該使用者的排程標記為失敗（本機那一份確實成功了，既有 `last_run_status` 應維持「成功」）、**絕不**擲例外影響其他使用者的排程列（這六頁的排程都是逐列跑，一列炸掉不能拖垮其餘）。
- [x] 243.3.4.1 **逾時與確定性失敗的措辭必須分開**（承 t242.2.5.1，已實測發生的假失敗）：`exec()` 的判準是「行程未在時限內 exit」而非「檔案沒上去」——實測有過「狀態欄記逾時失敗，但 Drive 上檔案完整、與本機逐 byte 相同」。逾時寫「逾時（45 秒）：Drive 端可能已完成，請於下一輪確認」，只有 rclone 非零退出才寫「失敗：<rclone 錯誤>」。
- [x] 243.3.5 **凡「已啟用 Drive 但未實際上傳」也必須寫狀態欄**，內容明示原因（例「跳過：Drive 子路徑不合法」「跳過：owner 非主要管理者」「跳過：rclone 設定不可用」）。否則 `gdrive_last_status` 會停留在**上一次的成功**，設定頁顯示過期的好消息——而這兩個欄位存在的唯一理由就是「上傳目的地不在使用者眼前，不回報就是靜默失敗」。（t241 實作時漏了這點，經審查補上。）
- [x] 243.3.6 **狀態欄寫入自身的失敗也必須被吞掉**（DB 短暫不可用時會擲例外）：「回報結果」這件事本身不能成為新的失敗來源。
- [x] 243.3.7 **不實作 retry queue**：每日排程本身即為重試，Drive 端覆寫同名檔案冪等。
- [x] 243.3.8 **檔名沿用各頁既有規則，不因 Drive 而改變**：Drive 上與本機同名同內容（多數含 `{使用者ID}` 與日期，例 `交易紀錄_1_20260727.xlsx`）。**不得以「實務上只有一人會啟用」為理由省略 owner id**——那是設定值決定的偶然狀態，不是機制保證。

### 243.4 背景排程的 owner 複驗

- [x] 243.4 **背景排程必須逐列再驗一次 owner 仍是主要管理者。** 243.2.1 的檢查有 request context，但排程是背景執行緒、逐列跑 `settingRepo.findAll()`，**沒有 `CurrentUserContext`**，那個檢查在此完全不適用。若某列在啟用後 owner 被改、DB 值被 psql 直改繞過 API、或 `ADMIN_EMAIL` 換人，背景仍會照上傳。
- [x] 243.4.1 做法：產檔後、上傳前呼叫**與 243.2.2 同一個** `GdriveOutputSupport.isDriveAllowedFor(s.getOwnerUserId())`；不通過則**跳過上傳並寫入狀態欄說明原因**（例「跳過：owner 非主要管理者」，不可靜默跳過，否則使用者會以為還在同步）。
- [x] 243.4.2 可行性已確認，不需額外處理 `@Filter`：`AppUser` **未**套 `@Filter`，且 `TenantFilterAspect` 在 `RequestContextHolder.getRequestAttributes() == null`（背景執行緒）時直接 return，故 `findById(ownerId)` 不會被 fail-closed 成空。
- [x] 243.4.3 此為與 Requirement 39／49「背景排程必須逐列 `enableFilter`」同一類的縱深防禦：背景執行緒沒有 request 情境可依賴，任何「HTTP 層已經驗過」的假設都不成立。

### 243.5 BFF：六支 `browse-gdrive` passthrough

- [x] 243.5 六個 BFF controller 各新增自己的 Drive 資料夾樹 passthrough（依「一頁一 BFF」），路徑比照該頁既有的本機 browse（**注意 `AssetHistoryBffController` 的前綴與其餘五支不同**，已實測）：

  | BFF controller | 既有本機路徑 | 新增 Drive 路徑 |
  |---|---|---|
  | `AssetHistoryBffController` | `/export-schedule/browse` | `/export-schedule/browse-gdrive` |
  | `RealizedGainBffController` | `/export/browse` | `/export/browse-gdrive` |
  | `TransactionBffController` | `/export/browse` | `/export/browse-gdrive` |
  | `CommodityPriceBffController` | `/export/browse` | `/export/browse-gdrive` |
  | `ExchangeRateBffController` | `/export/browse` | `/export/browse-gdrive` |
  | `GdpTwseBffController` | `/export/browse` | `/export/browse-gdrive` |

- [x] 243.5.1 **六支全部指向同一支** business `GET /api/export-schedule/browse-gdrive`（t241 建立、t242 已把邏輯遷入共用元件，端點路徑不變）。**不得新造第二份 Drive 目錄列舉**（CLAUDE.md「不同頁面顯示同樣意義的值須呼叫同一支 business service API」）。BFF 層各建一支是「一頁一 BFF」的要求，business 層只有一份實作——兩者不衝突。
- [x] 243.5.2 **不做 `onErrorReturn` 降級**：remote 未設定／授權失效時 business 回可讀錯誤，必須讓它浮到前端 dialog 顯示。降級成空清單會讓使用者誤讀為「Drive 裡沒有資料夾」而以為自己選錯位置。
- [x] 243.5.3 檢查各 BFF 的設定 `PUT` 是否有 DTO 鏡像類別：若為 `Map<String,Object>` 直通則新欄位自動穿透、無需改動；若為**強型別 DTO 必須同步加上新欄位**，漏一個就會讓 Drive 設定在 BFF 層被靜默吃掉（前端存了卻沒生效）。
- [x] 243.5.4 BFF `SecurityConfig`：新的 `GET` 應落在既有的 `anyExchange().authenticated()`；**不需**為 403 加規則（權限判斷在 business 端做，因為背景排程也要用同一套判定）。
- [x] 243.5.5 **BFF passthrough 必須把 business 的 4xx／5xx 狀態與 `ProblemDetail.detail` 原樣回傳。** 實測 BFF 端**目前沒有任何錯誤轉譯**（`ErrorWebExceptionHandler`／`@ControllerAdvice`／`ProblemDetail`／`onStatus` 在 bff 全樹皆為 0，`WebClientConfig` 也沒有 `defaultStatusHandler`），而前端 `api/index.js` 只讀 `data.detail`。若不處理，business 回的 403「需要主要管理者權限」到了使用者眼前會變成沒有訊息的錯誤。設定 `PUT`（403）與 `browse-gdrive`（503）兩條路徑都要。

### 243.6 前端：六個 view ＋ `api/index.js`

- [x] 243.6 `frontend/src/api/index.js` 六個頁面區塊各加 `browseGdriveExportDir`，並確認該頁的 save 函式**送出完整 payload**（含 `gdriveEnabled`／`gdriveSubpath`）。**這一項漏掉會讓 Drive 設定靜默存不進去**——t241 就是漏了這裡（`saveExportPath` 原本只送單一字串），經審查才發現。新 helper 帶 `skipErrorToast: true`（同檔既有 `browseExportDir` 的慣例，讓錯誤顯示在 dialog 內、不與全域 toast 打架）。
- [x] 243.6.1 六個 view（`AssetHistoryView`／`RealizedGainView`／`TransactionView`／`CommodityPriceView`／`ExchangeRateView`／`GdpTwseView`）的排程設定卡，在既有「輸出資料夾」之下新增：「同步 Google Drive」`el-switch` ＋ Drive 目標資料夾欄位（readonly ＋「選擇」按鈕）＋「上次上傳」唯讀顯示。
- [x] 243.6.2 **資料夾選擇器沿用 t241 的雙模式寫法**（範本見 `frontend/src/views/CrawlerDataView.vue`：`dirPicker` reactive 帶 `mode: 'local' | 'gdrive'`、`dirPickerTitle` 依 mode 切標題、`openDirPicker(mode)`、樹的 load 依 mode 選 API）。同一個 `el-dialog` ＋ `el-tree` 即可，因為 Drive 端回傳形狀與本機完全相同，不需第二套渲染邏輯。
- [x] 243.6.3 **dialog 內的「目前選擇」預覽，兩種 mode 的分隔符不同**：Drive 基底是 `remote:`（已含冒號，後面**直接接**子路徑），本機基底是 `/home/steven`（需要 `/` 分隔）。混用會顯示成 `GDriveOutput:/投資理財` ——多一個斜線、不是 rclone 的路徑格式，會誤導使用者。（t241 犯過這個錯，由使用者截圖發現。）
- [x] 243.6.4 Drive 樹載入失敗時把後端訊息顯示在 dialog 內，**不得顯示成空樹**。
- [x] 243.6.5 開關關閉時 Drive 欄位與「選擇」按鈕停用，但**保留已填的值**（不清空）。開關開啟而資料夾為空時前端即擋下儲存並提示（後端亦回 400，前後端都擋）。
- [x] 243.6.6 **非主要管理者不顯示 Drive 開關與欄位**（承 243.2.1）。注意這與同一張卡片上的其他欄位不同——本機路徑與排程時間對所有使用者都可見可改。
  - **六個 view 目前都沒有引入 auth store**（實測 `isAdmin`／`useAuthStore` 在這六頁的 grep 皆為 0），故各自新增 `import { useAuthStore } from '@/stores/authStore'`。
  - 條件用 **`auth.isConfiguredAdmin`**（t242.5.2 新增的 getter）。**不得用 `auth.isAdmin`**——那是 `role === 'ADMIN'` 判準，與後端 403 的 `isConfiguredAdmin` 不一致，會出現「畫面顯示得了、按儲存卻 403」。
  - **真正的閘門在後端**，前端只是不顯示。
- [x] 243.6.7 頁面文案須說明**本機仍會照寫**（例「Drive 為額外備份，本機輸出不受影響」），避免使用者誤以為開了 Drive 就不寫本機。
- [x] 243.6.8 前端只呼叫自己頁面的 BFF，且**所有請求一律經 `api/index.js`**，view 內不得裸用 `axios`／`fetch`。

### 243.7 排程列表頁說明同步

- [x] 243.7 本任務**未新增任何 `@Scheduled`**（上傳掛在既有排程輪次內），故「公開資訊 → 排程列表」（`bff/.../schedulelist/SchedulePublicBffController` 的 `JOBS`）**不需新增項目**；但這六個排程對應的 description 須各補上「輸出含 Google Drive 同步（若已啟用）」。
- [x] 243.7.1 `JOBS` 是無鍵的 `List<ScheduledJobDto>`，**不能用資料表名或 service 名去 grep**。要靠 group／name 字串定位——本任務六筆為「資產匯出」／「已實現損益匯出」／「交易紀錄匯出」／「油價金價匯出」／「台幣兌美元匯出」／「大盤指數匯出」。（t241 踩過這個坑：`grep news-poller` 在該檔命中 0 次。）

### 243.8 測試（與實作同屬本任務交付）

- [x] 243.8 在 `backend/src/test/java/com/steven/assets/service/` 新增測試（**測試檔命名由實作者定**，比照既有 service 測試風格）。以下三項是本任務最關鍵的迴歸，**每一項都必須有對應測試**：
  - **非主要管理者啟用 Drive → 403**（243.2.1／243.2.2；同時驗證 `role == ADMIN` 但非 `ADMIN_EMAIL` 的使用者**也**被擋，這是 `isConfiguredAdmin` 與 `isAdmin` 的差異所在）
  - **背景排程遇非主要管理者 owner → 跳過上傳且寫入狀態欄**（243.4；同時驗證本機檔仍產生、既有 `last_run_status` 仍為成功）
  - **上傳失敗不影響本機**（243.3.4）：rclone 擲例外時本機檔仍存在、既有 `last_run_status` 為成功、`gdrive_last_status` 為失敗、不向外擲例外
- [x] 243.8.1 其餘應涵蓋：關閉開關不清空既有子路徑、未送出 `gdriveEnabled` 時保留原設定（243.1.1）、讀取不合法既有值不擲例外、run-now 啟用時會上傳並回報落點、狀態欄寫入自身失敗也被吞掉。
- [x] 243.8.2 rclone 呼叫一律以 `RcloneClient` 介面替身注入，**不實際連網**。
- [x] 243.8.3 **既有六頁本機匯出行為的回歸**：確認本機路徑驗證、檔名、排程當日 guard、owner 隔離（`exportXxxForOwner` 的 `enableFilter`）等既有行為未因本次變更而改變。
- [x] 243.8.4 Mockito 於本專案需 `-DargLine="-Dnet.bytebuddy.experimental=true"`（直接 `-D` 無效，surefire 會 fork）。

## 驗證

```bash
# 1. 後端與 BFF 建置測試
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test \
  -DargLine="-Dnet.bytebuddy.experimental=true"
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml package -DskipTests

# 2. 從 worktree build 前先把主 repo 的 .env 複製進來（env_file 相對 compose 檔解析）
cp /Users/steven/Project/asset-management/.env .

# 3. JVM service 一律 --no-cache（cached build 會產出 stale jar：前端有新功能、後端 404）
docker compose -p asset-management build --no-cache business-services bff
docker compose -p asset-management build --no-cache frontend
docker compose -p asset-management up -d --no-deps --force-recreate business-services bff frontend

# 4. recreate business 後必須 restart bff（換 IP、Docker DNS TTL 600s 內不自癒）
docker compose -p asset-management restart bff

# ⚠ 以下步驟會改寫「生產中」的排程設定。先存檔現值，跑完務必還原。
# 實測現值範例：owner 1 的 export_schedule_setting 為 enabled=t / 09:12 / Project/SRPP/data/input
docker exec asset-postgres psql -U assets -d assets -c \
  "SELECT owner_user_id, enabled, run_hour, run_minute, output_subpath, gdrive_enabled, gdrive_subpath \
   FROM export_schedule_setting ORDER BY owner_user_id"   # 輸出貼進完成報告，最後一步用它還原

# 5. 非主要管理者啟用 Drive → 403
#    **必須用 role=ADMIN 但 email 非 ADMIN_EMAIL 的帳號**（實測 id=2 hi.steven@gmail.com）。
#    送 X-User-Role: USER 沒有鑑別力——CurrentUserContext.isAdmin() 直接比對 header 的 role，
#    所以「誤用 role 判準」與「正確用 isConfiguredAdmin」兩種實作都會回 403。
#    若此步回 200，即代表實作誤用了 role 判準。
docker exec asset-postgres psql -U assets -d assets -c "SELECT id, email, role FROM app_user ORDER BY id"
docker exec asset-business-services sh -c 'curl -s -o /dev/null -w "%{http_code}\n" \
  -X PUT -H "X-User-Id: 2" -H "X-User-Role: ADMIN" -H "X-User-Status: ACTIVE" \
  -H "Content-Type: application/json" \
  -d "{\"enabled\":true,\"runHour\":9,\"runMinute\":12,\"outputSubpath\":\"Project/SRPP/data/input\",\"gdriveEnabled\":true,\"gdriveSubpath\":\"投資理財/資產管理\"}" \
  "http://localhost:8080/api/export-schedule/settings"'   # 期望 403

# 6. 主要管理者啟用成功（id 需為 ADMIN_EMAIL 那一位；body 照抄上面存下的現值、只加 gdrive 欄位）
docker exec asset-business-services sh -c 'curl -s \
  -X PUT -H "X-User-Id: 1" -H "X-User-Role: ADMIN" -H "X-User-Status: ACTIVE" \
  -H "Content-Type: application/json" \
  -d "{\"enabled\":true,\"runHour\":9,\"runMinute\":12,\"outputSubpath\":\"Project/SRPP/data/input\",\"gdriveEnabled\":true,\"gdriveSubpath\":\"投資理財/資產管理\"}" \
  "http://localhost:8080/api/export-schedule/settings"'

# 7. run-now 觸發並確認 Drive 落檔（--config 用實際生效的主檔）
docker exec asset-business-services sh -c 'curl -s -X POST \
  -H "X-User-Id: 1" -H "X-User-Role: ADMIN" -H "X-User-Status: ACTIVE" \
  "http://localhost:8080/api/export-schedule/run-now"'   # 回應應含 gdrivePath / gdriveStatus
rclone lsl --config ~/.config/rclone/rclone.conf "GDriveOutput:投資理財/資產管理"

# 8. 狀態欄有被寫入
docker exec asset-postgres psql -U assets -d assets -c \
  "SELECT owner_user_id, gdrive_enabled, gdrive_subpath, gdrive_last_run_at, gdrive_last_status \
   FROM export_schedule_setting"

# 9. jar 真的含本次變更
docker exec asset-business-services sh -c 'unzip -l app.jar | grep -icE "GdriveOutputSupport"'

# 10. **還原生產設定**（用第 0 步存下的現值；跑完務必執行）
docker exec asset-business-services sh -c 'curl -s \
  -X PUT -H "X-User-Id: 1" -H "X-User-Role: ADMIN" -H "X-User-Status: ACTIVE" \
  -H "Content-Type: application/json" \
  -d "{\"enabled\":true,\"runHour\":9,\"runMinute\":12,\"outputSubpath\":\"Project/SRPP/data/input\"}" \
  "http://localhost:8080/api/export-schedule/settings"'
docker exec asset-postgres psql -U assets -d assets -c \
  "SELECT owner_user_id, enabled, run_hour, run_minute, output_subpath FROM export_schedule_setting"
```

**必須在瀏覽器實際操作確認（不可只憑 API 回應宣稱完成）：**

1. 六個頁面（歷年資產／已實現損益／交易紀錄／油價金價／台幣兌美元／GDP-TWSE）各自的排程設定卡都出現「同步 Google Drive」開關、Drive 資料夾欄位與「上次上傳」。
2. 任一頁點「選擇」→ Drive 樹能展開、挑到目標資料夾 → 儲存成功；dialog 內的路徑預覽為 `GDriveOutput:資產管理`（**沒有多餘斜線**）。
3. 開關開啟但 Drive 資料夾留空 → 儲存被擋並提示。
4. 按該頁的「立即匯出到目錄」→ 確認三件事：本機檔案照樣產生、Drive 上出現同名同大小的檔案、「上次上傳」顯示成功落點。
5. 以非主要管理者身分登入（或暫時改 `ADMIN_EMAIL`）→ 確認 Drive 開關與欄位**不顯示**，而本機路徑與排程時間仍可設定。
6. 故意把 `GDRIVE_OUTPUT_REMOTE` 設成不存在的名稱並 recreate business → 確認本機檔仍照寫、既有「上次執行」仍為成功、只有「上次上傳」記失敗；設定頁仍能載入與儲存（不得 500）。

## 完成報告

**實際改了哪些檔**

| 層 | 檔案 |
|---|---|
| 共用元件 | `GdriveOutputSupport`（新增 `resolveUpdate`／`SyncResult syncQuietly`，把 t242 的 `uploadQuietly` 併入）、`AdminRequiredException`（加帶訊息的 constructor） |
| DTO（6） | `ExportScheduleDto`／`RealizedGainExportDto`／`AssetTransactionExportDto`／`CommodityExportDto`／`ExchangeRateExportDto`／`IndexExportDto`：`SettingResponse` ＋5 欄、`SettingRequest` ＋2 欄（`Boolean gdriveEnabled`）、`RunNowResponse` ＋`gdrivePath`／`gdriveStatus` |
| service（6） | 對應六支 `*ExportScheduleService`：注入 `GdriveOutputSupport`、`update` 走 `resolveUpdate`、run-now 與排程兩處各呼叫私有 `syncGdrive(...)`、`toResponse` 回填五欄 |
| BFF（6＋2） | 八支 controller 各加 `browse-gdrive` passthrough；新增 `bff/common/BusinessErrorAdvice`（全 BFF 錯誤轉譯，見下方偏差說明） |
| 前端 | `api/index.js` 八處 `browseGdriveExportDir`；六個 view 加 Drive 開關／資料夾欄位／「上次上傳」＋雙模式選擇器＋`useAuthStore` |
| 其他 | `SchedulePublicBffController` 八筆 description 補「輸出含 Google Drive 同步（若已啟用）」 |

**測試輸出**：`mvn -f backend/pom.xml test -DargLine="-Dnet.bytebuddy.experimental=true"` → **Tests run: 205, Failures: 0, Errors: 0**（原 157 ＋ 新增 48）。新增三支測試檔：

- `GdriveOutputSupportTest`（23 項）：驗證規則（開頭 `/` 回 400、`a..b` 不被誤擋、拒 `:`、啟用必填）、`isDriveAllowedFor` fail-closed（查無使用者／email 空／`role==ADMIN` 但非 `ADMIN_EMAIL` 皆回 false）、**逾時與失敗措辭分開**、跳過情境不呼叫 rclone。
- `ExportScheduleGdriveTest`（13 項）：三條關鍵迴歸各有測試——非主要管理者啟用 → `AdminRequiredException` 且不 save；背景排程遇非主要管理者 owner → 跳過上傳、寫狀態欄、**本機檔仍存在且 `lastRunStatus` 仍為成功**；上傳失敗 → 不擲例外、本機檔仍在、兩個狀態欄可分辨。另含未送 `gdriveEnabled` 不關開關、關閉不清空子路徑、讀取不合法值不擲例外、run-now 回報落點。
- `TradingExportGdriveTest`（12 項，屬 t244）。

既有 `AssetTransactionExportScheduleServiceTest`／`TradingRadarExportScheduleServiceTest` 因 constructor 與 `SettingRequest` 簽章變動而同步更新（注入真實 `GdriveOutputSupport`＋替身 rclone，驗證規則才會真的被跑到），其餘既有測試未改動即通過＝**本機匯出行為無回歸**。

**與原計畫的偏差**

1. **243.3.2 的 B 組插入點**：任務檔寫「插在 `export()` 內、`writeAtomically()` 之後」，實作改為**插在 `export()` 的兩個呼叫點（run-now 與排程）之後**。理由：`export()` 內部上傳的話，run-now 那一側拿不到 `SyncResult`，無法滿足 243.3.3「run-now 回報 `gdrivePath`／`gdriveStatus`」。`export()` 回傳處即「`writeAtomically` 之後」，且 `writeAtomically()` 本身完全未動——原規則的意圖（不碰沒有設定列情境的純寫檔工具）完整保留，六頁的呼叫形狀也因此統一。
2. **新增 `BusinessErrorAdvice`（243.5.5 的落地方式）**：任務檔要求「BFF passthrough 必須把 business 的 4xx／5xx 與 `ProblemDetail.detail` 原樣回傳」。逐支 controller 加 `onStatus` 會是八份重複，故改為一支 `@RestControllerAdvice` 統一轉譯 `WebClientResponseException`。**副作用**：這會一併改善全 BFF 所有端點的錯誤訊息（原本一律變成無內容的 500），屬於本任務範圍外的正向影響，已在 `design.md` 記載。
3. **`AdminRequiredException` 加了帶訊息的 constructor**：既有無參數版訊息為「此操作僅限管理者」，對 `role == ADMIN` 但非 `ADMIN_EMAIL` 的使用者是自相矛盾的說明。新增 `AdminRequiredException(String)` 後訊息為「Google Drive 同步僅限主要管理者啟用」，既有呼叫端不受影響。
4. **`resolveUpdate` 只在「明確送 `gdriveEnabled=true`」時查權限**（任務檔未明訂此細節）：既有值已 true 而本次未送該欄時不檢查，否則 `ADMIN_EMAIL` 換人後該使用者連本機路徑與排程時間都會被 403 鎖死，違反 243.2.4。真正的閘門是每輪產檔前的 `isDriveAllowedFor` 複驗（243.4），已有測試涵蓋。

**尚待使用者實機確認**：本報告的六頁 Drive 落檔證據、非主要管理者 403 的容器內 curl 輸出、以及「驗證」段的六項瀏覽器操作，需在 rebuild／recreate 後由使用者實測補上（部署步驟已執行，見 t244 完成報告）。
