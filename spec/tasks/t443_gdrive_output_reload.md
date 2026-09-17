# [t443] GDriveOutput 補上 source-fingerprint 熱重載，修正 invalid_client 自檢誤判

**對應 Requirements:** Requirement 160（GDriveOutput 匯出比照 DB 備份補上 source-fingerprint 熱重載，修正 invalid_client 自檢誤判；明確推翻 Requirement 52「本需求對 GDriveOutput 刻意不實作 config 熱重載」一條，以及該 Requirement 末條「不得把這個 backup-specific 例外擴大到 GDriveOutput」一句）
**前置任務:** 無（讀取但不修改 Task 388 已完成的 `ProcessBackupRemoteClient`，作為既有已驗證機制的參考實作）
**Liquibase changeset:** 無（不改 DB schema、不改任何欄位定義）

## 背景

2026-09-17 實測：九張匯出設定表（`export_schedule_setting` 等）與爬蟲頁的 Google Drive 同步，從 **2026-08-30** 之後全數停止上傳，直到當天才被發現（18 天）。本機 xlsx／json 全程正常產生；`docker logs` 與 `gdrive_last_status` 一致記錄：

```
couldn't fetch token: invalid_client: if you're using your own client id/secret, make sure they're properly set up following the docs
```

成因：`~/.config/rclone/rclone.conf` 的 `[GDriveOutput]` section 的 `client_id`，與 app 登入／Blog 發布共用同一組 `GOOGLE_CLIENT_ID`（docker-compose.yml 對 business-services 既有設計）；但 `client_secret` 已與 `.env` 現行值不同步（以 SHA-256 雜湊比對兩邊：長度相同、雜湊不同）。把 `[GDriveOutput]` 的 `client_secret` 改回與 `.env` 一致、`docker compose -p asset-management up -d --force-recreate business-services external-materials-service` 後，兩服務**未經任何互動式重新登入**即恢復上傳——證實原本的 OAuth refresh token 從未失效，純粹是 secret 漂移擋住了 token 交換，且**現有 client_id/secret 一旦正確，兩容器只要重啟就會自動恢復**。

問題在於：`backend` 的 `ProcessRcloneClient` 與 `external-materials-service` 的 `ProcessGdriveUploader` 目前都是「啟動時把唯讀掛入的 `/etc/rclone/rclone.conf` 複製一份到可寫的 `/tmp/rclone-output.conf`，執行期永遠不重讀來源」（`@PostConstruct` 設一次 `configReady`，之後不再檢查）。這代表：即使 host 端的 `rclone.conf` 已經修好，**執行中的容器也不會自動生效**，必須有人記得手動 `--force-recreate` 才會撿到新設定——而 `GdriveSelfCheck` 的自檢（Requirement 52）刻意設計成「一律只寫 WARN log、不阻止啟動、不做任何主動通知」，沒有人會主動被提醒去重建容器，才會斷線 18 天都沒人發現。

`business-services` 內部另一個 client——`ProcessBackupRemoteClient`（DB 備份，使用 `[GoogleDriver]`／`[gdrive-crypt]`，`/tmp/rclone.conf`）——早在 Task 388（2026-08-08 的另一次類似事故）就已經修好這個問題：以 `lastLoadedSourceFingerprint`（SHA-256）判斷 source 是否變動，變動就原子安裝新的 writable snapshot，host 端修好設定後**下一次操作就自動生效，不需要 recreate**。

**Requirement 52 當初刻意不把這個機制套用到 GDriveOutput**（理由：「熱重載需要 watch／輪詢與併發保護，收益只有省下一次 recreate」），Task 388 完成後更在 Requirement 52 末條明文禁止「不得把這個 backup-specific 例外擴大到 GDriveOutput」。**本任務的背景事故直接推翻了這個判斷**：Task 388 已經把機制做成熟、可直接複用（成本大降），而「收益只是省一次 recreate」的估計被證明大錯特錯（自檢只寫 log、無主動通知，真實收益是避免數週級的靜默斷線）。本任務因此明確推翻 Requirement 52 的上述兩處條款，把同一類機制（**不是**照抄 Task 388 的完整實作，見下方範圍說明）套用到 `ProcessRcloneClient` 與 `ProcessGdriveUploader`。

**另一個獨立問題（同一批診斷中發現，本任務一併修正）**：`invalid_client` 這個錯誤目前落在 `ProcessRcloneClient.exec()` 與 `ProcessGdriveUploader.exec()` 的「其他失敗」通用分支，訊息只是原樣附上 rclone stderr；`external-materials-service` 的 `GdriveSelfCheck.probe()` 更會在任何失敗後一律附加固定提示「常見成因：該 OAuth client 所屬的 GCP 專案未啟用 Drive API…、remote 名稱設錯、或授權已被撤銷」——這份清單裡沒有「client_secret 與其他共用服務不同步」這個已經真實發生過的成因，會誤導排查方向。

## 要做什麼

**範圍控制（先講清楚不做什麼，避免抄過頭）：** `RcloneClient`／`GdriveUploader` 介面現況「只有列目錄與上傳，不實作任何刪除路徑」的自我約束不變。因此 Task 388 裡「raw-root 身分守門」（防止 reconnect 到錯帳號後，把空清單誤判成『可以刪』或『可以在此帳號建新樹』）、「rotate/sync 逐筆 durable commit」、「刪除相關的 rollback 保護」**全部不適用、不要實作**——GDriveOutput 唯一的寫入操作是 `copyto` 覆寫一個具名目的檔，錯帳號時最壞後果只是把同一份檔案傳到另一個帳號的對應路徑，不會誤刪或誤判任何既有資料。同理，**不要**引入 Task 388 那把「涵蓋整個 rclone 子行程生命期」的重量級 `ReentrantLock`——那是為了保護備份的 list/delete 狀態機，GDriveOutput 不需要。

- [x] **443.1 `ProcessRcloneClient`（`backend/src/main/java/com/steven/assets/service/ProcessRcloneClient.java`）加入 source-fingerprint 熱重載**：
  - 新增 `private volatile String lastLoadedSourceFingerprint;`（初始 `null`）。`CONFIG_SOURCE`（`/etc/rclone/rclone.conf`）與 `CONFIG_WRITABLE`（`/tmp/rclone-output.conf`）兩個常數不變。
  - 新增 package-private 方法 `boolean reloadIfSourceChanged(Path source, Path writable)`（讓測試能傳入暫存路徑；生產路徑一律呼叫 `reloadIfSourceChanged(CONFIG_SOURCE, CONFIG_WRITABLE)`）：讀取 `source` 全部 bytes（讀失敗，含 `NoSuchFileException`，回傳 `false`、**不**清空既有 `lastLoadedSourceFingerprint`／`configReady`，讓既有 writable snapshot 繼續被使用——這與現況「一旦啟動當下讀不到就整個生命週期跳過」不同，是本任務刻意要修正的行為）；算 SHA-256 十六進位字串；與 `lastLoadedSourceFingerprint` 相同就回傳 `false`（不做任何檔案動作）；不同才在 `writable` 所在目錄用 `Files.createTempFile` 建立同檔案系統下的唯一 temp、寫入同一份 bytes、`Files.setPosixFilePermissions` 設 `rw-------`、以 `ATOMIC_MOVE + REPLACE_EXISTING` 安裝為 `writable`；成功才更新 `lastLoadedSourceFingerprint` 並將 `configReady` 設為 `true`；temp 檔無論成功失敗都必須清理（`finally` + `deleteIfExists`，清理失敗吞掉不拋出）；atomic move 失敗（含 `AtomicMoveNotSupportedException`）時舊 `lastLoadedSourceFingerprint` 與現有 `writable` 內容都不得變動，回傳 `false`。**禁止**比較 `writable` 的內容／mtime／inode 來決定要不要 reload。
  - 新增 `private void ensureConfigCurrent()`：呼叫 `reloadIfSourceChanged(CONFIG_SOURCE, CONFIG_WRITABLE)`（回傳值本身不需要用到，只是觸發嘗試），接著若 `!configReady` 才拋現有的 `RcloneUnavailableException("Google Drive 尚未設定：找不到 " + CONFIG_SOURCE + "。請確認 rclone 設定檔已掛入。")`（訊息拿掉「並重新部署」字樣——本任務之後已不再需要重新部署）。
  - `listDirs`／`copyTo` 開頭的 `requireConfig()` 呼叫全部改成呼叫 `ensureConfigCurrent()`；移除舊的 `requireConfig()` 方法與其「啟動時判定一次」的單純旗標檢查（`configReady` 欄位保留，語意改為「目前是否有可用的 writable snapshot」，由 `reloadIfSourceChanged` 維護，不再由 `@PostConstruct` 單獨管理）。`configReady` 欄位正上方現有 Javadoc「`config 來源是否就緒；啟動時判定一次，缺檔不阻止服務啟動（見 {@link #initConfig()}）。`」須同步改寫為反映新語意（例如「config 來源目前是否就緒；由 `reloadIfSourceChanged` 於每次呼叫時維護，非啟動時判定一次」），避免欄位宣告正上方的註解與新行為矛盾。
  - `initConfig()`（`@PostConstruct`）**保留**，但改為呼叫 `reloadIfSourceChanged(CONFIG_SOURCE, CONFIG_WRITABLE)` 取代目前手動的 `Files.copy` 邏輯，藉此在啟動當下就有一份 writable snapshot（沿用現有「來源不存在只 warn、不擲例外」語意）；原本 `IOException` 導致 `log.error` 的分支改為捕捉 `reloadIfSourceChanged` 內部已吞掉的失敗（該方法本身不擲例外，故 `initConfig()` 不需要再 try/catch IOException——若讀取失敗，`reloadIfSourceChanged` 已回傳 `false` 且 `configReady` 維持 `false`）。
  - 併發：`listDirs`／`copyTo` 可能被不同排程的執行緒同時呼叫。`reloadIfSourceChanged` 的「讀 source → 比較 fingerprint → 寫 temp → atomic move → 更新 fingerprint」這段用 `synchronized` 保護（`synchronized (this)` 或專屬 lock object 皆可），確保不會有兩個執行緒同時進行 install 導致 `lastLoadedSourceFingerprint` 更新順序錯亂；**不need** 把這把鎖延伸到涵蓋 `exec()` 的 rclone 子行程執行（那段本身透過各自獨立的 temp 輸出檔已經是執行緒安全的）。

- [x] **443.2 `ProcessGdriveUploader`（`external-materials-service/src/main/java/com/steven/assets/externalmaterials/service/ProcessGdriveUploader.java`）加入相同機制**：比照 443.1 的邏輯（`reloadIfSourceChanged`／`ensureConfigCurrent`／`synchronized` 安裝），套用到本類別自己的 `CONFIG_SOURCE`（`/etc/rclone/rclone.conf`）／`CONFIG_WRITABLE`（`/tmp/rclone-output.conf`）／`configReady` 欄位。`upload()`／`probe()` 開頭原本的 `if (!configReady) throw new IllegalStateException(...)` 改為先呼叫 `ensureConfigCurrent()`（可沿用相同私有方法名稱），仍在 `!configReady` 時拋 `IllegalStateException("rclone 設定不可用（" + CONFIG_SOURCE + " 不存在）")`（訊息不變）。
  - **`isAvailable()` 必須改為也觸發 reload 嘗試，這是本任務的必要條件、不是可選項**：`isAvailable()` 目前是純 getter（直接回傳 `configReady`），但它是 `NewsPoller.syncToGdrive()`（第 572-573 行 `else if (!gdriveUploader.isAvailable())` 早退跳過）與本檔 `runStartupCheck()`（第 152-166 行 `if (!uploader.isAvailable())` 早退 return）在呼叫 `upload()`／`probe()` **之前**的唯一前置閘門；若 `isAvailable()` 本身不觸發 reload，這兩個呼叫端會在到達 `upload()`／`probe()` 之前就被舊的 `configReady=false` 擋下，`ensureConfigCurrent()` 因此永遠不會被執行到——「source 從未成功載入過、後續變成可讀」這個情境對 `external-materials-service` 端會**完全沒有修正到**（`backend` 端的 `RcloneClient` 介面沒有 `isAvailable()` 這種前置閘門，故不受影響，`copyTo`／`listDirs` 每次都會執行到 443.1 的 `ensureConfigCurrent()`）。修法：`isAvailable()` 內部先呼叫 `reloadIfSourceChanged(CONFIG_SOURCE, CONFIG_WRITABLE)`（回傳值不需要用到，只是觸發嘗試，與 `ensureConfigCurrent()` 內部呼叫方式相同），再回傳目前的 `configReady`。這會讓 `isAvailable()` 從「純被動 getter」變成「每次呼叫都嘗試 reload」，成本與 443.1／443.2 其他路徑一致（一次檔案讀取＋SHA-256，內容未變時不做任何安裝動作），可接受。
  - 本服務與 `backend` 的 reload 狀態（`lastLoadedSourceFingerprint`、`configReady`）完全獨立，不共用任何欄位或鎖。`configReady` 欄位正上方現有 Javadoc「`config 來源是否就緒；啟動時判定一次，缺檔不阻止服務啟動。`」比照 443.1 同樣的理由同步改寫。

- [x] **443.3 `ProcessRcloneClient.exec()` 新增 `invalid_client` 分類**：新增 `private static final String INVALID_CLIENT = "invalid_client";`。在既有 `if (err.contains(NO_SECTION))` 判斷之後、`isRateLimited(err)` 判斷之前，新增：
  ```java
  if (err.toLowerCase(Locale.ROOT).contains(INVALID_CLIENT)) {
      throw new RcloneUnavailableException(
          "Google Drive remote「" + remote + "」的 OAuth client_id/secret 組合已被 Google 拒絕"
          + "（invalid_client）。此 client 與 app 登入／Blog 發布共用同一組 "
          + "GOOGLE_CLIENT_ID/GOOGLE_CLIENT_SECRET，常見成因是曾在別處（如 .env）輪替過 "
          + "client_secret 但未同步更新 rclone 設定。請確認兩邊 client_secret 一致；修正後系統會"
          + "自動偵測設定變更並重新載入，不需要重建容器。若該 OAuth client 已在 GCP Console 被刪除"
          + "或停用，需重新建立並更新 client_id/secret 後執行 rclone config reconnect "
          + remote + ":。");
  }
  ```
  需要在檔案頂部新增 `import java.util.Locale;`（若尚未存在）。訊息字面必須包含這五個關鍵詞供測試比對：`invalid_client`、`GOOGLE_CLIENT_ID`（或 `GOOGLE_CLIENT_SECRET`）、`client_secret`、`自動`、`reconnect`。

- [x] **443.4 `ProcessGdriveUploader.exec()` 比照新增 `invalid_client` 分類**：沿用該類別既有「無專屬例外階層、直接拋 `RuntimeException`」的風格，在既有 `if (err.contains(NO_SECTION))` 判斷之後新增：
  ```java
  if (err.toLowerCase(java.util.Locale.ROOT).contains("invalid_client")) {
      throw new RuntimeException(
          "Drive remote「" + remote + "」的 OAuth client_id/secret 組合已被 Google 拒絕"
          + "（invalid_client）。此 client 與 app 登入／Blog 發布共用同一組 "
          + "GOOGLE_CLIENT_ID/GOOGLE_CLIENT_SECRET，常見成因是曾在別處（如 .env）輪替過 "
          + "client_secret 但未同步更新 rclone 設定。請確認兩邊 client_secret 一致；修正後系統會"
          + "自動偵測設定變更並重新載入，不需要重建容器。若該 OAuth client 已在 GCP Console 被刪除"
          + "或停用，需重新建立並更新 client_id/secret 後執行 rclone config reconnect "
          + remote + ":。");
  }
  ```
  與 443.3 相同的五個關鍵詞比對要求。

- [x] **443.5 `external-materials-service` 的 `GdriveSelfCheck.probe()`（`external-materials-service/src/main/java/com/steven/assets/externalmaterials/service/GdriveSelfCheck.java` 第 302–312 行）更新硬編提示文字**：現有字串：
  ```
  "Drive 輸出自檢 L3 失敗：對 remote「{}」的唯讀探測（rclone lsd）不成功——{}。"
  + "常見成因：該 OAuth client 所屬的 GCP 專案未啟用 Drive API（錯誤訊息內含啟用連結，"
  + "照著開即可）、remote 名稱設錯、或授權已被撤銷。"
  + "注意 DB 備份走的是另一個 remote，備份正常不代表本 remote 正常。"
  ```
  改為在「或授權已被撤銷」之後、「注意 DB 備份走的是另一個 remote」之前，插入第四個成因：「、或此 client 的 client_secret 與其他共用服務（如 app 登入）不同步」。只改這一處字串串接，不改變 log 觸發條件（仍是任何 `RuntimeException`）、log 層級（仍是 `WARN`）、既有三個成因的文字、或方法簽章。

- [x] **443.5a 修正 `external-materials-service` 的 `GdriveSelfCheck.java` 裡另外五處仍斷言「仍須重建容器」的硬編文字**：這五處在 443.1／443.2 落地後會與 443.3／443.4 新增的 `invalid_client` 訊息（「不需要重建容器」）自相矛盾，必須一併修正，否則使用者會在同一個子系統裡同時看到兩種相反的說法：
  1. **`RECONNECT_HINT` 常數（第 66-77 行）**：把結尾子句「`+ "在 host 重新授權後仍須重建容器才會生效："` `+ "docker compose -p asset-management up -d --force-recreate " + "business-services external-materials-service";`」改為「`+ "在 host 重新授權後，下一次上傳或自檢操作即會自動偵測設定變更並重新載入，不需要重建容器。";`」。其上方程式碼註解「L2 解析的是啟動時複製到 /tmp 的副本，而本 client 啟動時複製一次、執行期不重讀來源（刻意不做熱重載，Task 247.5.1）。不講這句，使用者在 host 重新授權後會看到警告一字不變，以為修法沒效而繞回頭。」須同步改為說明「本 client 現在會在下一次操作時自動重新載入（Task 443），故此提示不再需要特別強調『仍須重建容器』」，避免程式碼註解與新行為矛盾。
  2. **`RECREATE_HINT` 常數（第 85-87 行）**：目前為「`"host 改過 rclone 設定後需 docker compose -p asset-management up -d --force-recreate business-services external-materials-service"`」，這個常數在 443.1／443.2 之後只在一種情況仍然成立：`~/.config/rclone` 目錄本身被整個替換（`mv` 後重建、還原備份、換機搬設定，見 `spec/design.md` 既有段落），此時掛載仍指向舊目錄 inode，reload 邏輯讀到的其實還是舊內容、fingerprint 不會變，才真的需要 recreate。改為：「`"若確認 host 端 rclone 設定的『內容』已經修好，下一次操作會自動重新載入、不需要重建容器；只有在 ~/.config/rclone 這個掛載目錄本身被整個替換（而非目錄內檔案內容變更）時，才需要 docker compose -p asset-management up -d --force-recreate business-services external-materials-service"`」。
  3. **`runStartupCheck()` 的 `if (!uploader.isAvailable())` 分支（第 152-166 行）**：整段註解與 WARN 訊息目前描述的是「啟動當下讀不到、之後 host 修好了、這個容器整個生命週期都不會同步」這個 443.1／443.2 之後已被修正掉的情境（見 443.2 對 `isAvailable()` 的修法）。註解與訊息都須重寫，反映「`isAvailable()` 現在每次呼叫都會嘗試 reload，本輪讀不到只代表這一次呼叫的當下讀不到，下一次呼叫（下一輪 warmup 或排程）會自動重試」，不再使用「本容器本次生命週期的 Drive 同步將全部跳過」這種永久性措辭；訊息裡的 `RECREATE_HINT` 引用維持（改用上述第 2 點修正後的新文字）。
  4. **`checkConfigReadable()` 私有方法（第 190-222 行）的 Javadoc 與 catch 區塊訊息**：這支方法是 L1（純本地檔案讀取，供 `localWarnings()` 在啟用當下與啟動時共用），與上面三處是不同的程式碼路徑，但斷言同一種已被推翻的行為，必須一併修正：
     - Javadoc 第 197-200 行：「`這一層的價值來自一個既有設計：{@code configReady} 是<b>啟動時判定一次、失敗後永不重試</b>的旗標，讀不到 config 的那一刻起，這個容器<b>整個生命週期</b>的 Drive 同步都會被跳過。實測 2026-07-28 16:02 就發生過：ext 啟動時剛好撞上 host 改寫 config，business 逃過而 ext 靜默失效——症狀還會偽裝成「Drive 好像還在收檔案」（各匯出頁的 xlsx 由 business 上傳、照常出現），只有爬蟲 JSON 停止更新。`」改為說明「Task 443 之後 `isAvailable()`／`ensureConfigCurrent()` 每次呼叫都會重新嘗試讀取 source，讀不到只代表『這一刻』讀不到，不再是『整個生命週期』；保留 2026-07-28 事故作為歷史脈絡即可，但不得再用現在式斷言『會被跳過』」。
     - catch 區塊訊息第 216-220 行：「`"Drive 輸出自檢 L1 失敗：讀不到 rclone 設定 " + configSource + "（" + e.getClass().getSimpleName() + "）。掛載點可能已 dangling——host 端 rclone 每次續期 OAuth token 都會原子替換這個檔案，單檔掛載的舊 inode 會就此失效（stat 仍成功、實際讀取才 ENOENT）。" + "本容器本次生命週期的 Drive 同步將全部跳過（config 只在啟動時判定一次、失敗後不重試）。" + RECREATE_HINT;`」改為移除「本容器本次生命週期的 Drive 同步將全部跳過（config 只在啟動時判定一次、失敗後不重試）」這句，換成「本次檢查當下讀不到；下一次上傳或自檢操作會自動重試。」＋沿用（443.5a 第 2 點修正後的）`RECREATE_HINT`。
  5. **`localWarnings()` 方法（第 173-188 行）的 Javadoc**：第 176-177 行「兩層各自回報而非「L1 失敗就不做 L2」：兩者的修法完全不同（一個是重建容器、一個是重新授權），合成一句會讓使用者只看到其中一半。」改為中性描述，例如「兩者的修法完全不同（一個是等下一次操作自動重新載入、極端情況才需重建容器；一個是重新授權），合成一句會讓使用者只看到其中一半」。純 Javadoc 文字調整，不影響 `localWarnings()` 本身「L1／L2 各自獨立回報」的邏輯。

- [x] **443.5b `backend` 的 `GdriveSelfCheck.java` 比照修正四處相同性質的硬編文字**：
  1. **`configBroken(Path source, String reason)` 私有方法**：目前回傳「`"讀不到 rclone 設定 " + source + "（" + reason + "）。" + "伺服器端本次生命週期的 Drive 同步將全部跳過（本機檔案照常產生）。" + "host 改過 rclone 設定後請重建容器：" + "docker compose -p asset-management up -d --force-recreate " + "business-services external-materials-service";`」。「本次生命週期的 Drive 同步將全部跳過」這句對 443.1 之後的 `ProcessRcloneClient` 不再成立（`ensureConfigCurrent()` 每次呼叫都會重試）。改為：「`"讀不到 rclone 設定 " + source + "（" + reason + "）。" + "本機檔案照常產生；下一次上傳或列目錄操作會自動重試讀取設定，讀得到時即自動恢復，不需要重建容器。" + "若持續讀不到（例如 ~/.config/rclone 這個掛載目錄本身被整個替換），才需要 " + "docker compose -p asset-management up -d --force-recreate " + "business-services external-materials-service";`」。
  2. **`reconnectHint(String remote)` 私有方法**：結尾子句「`+ "在 host 重新授權後仍須重建容器才會生效：" + "docker compose -p asset-management up -d --force-recreate " + "business-services external-materials-service";`」改為「`+ "在 host 重新授權後，下一次上傳或列目錄操作即會自動偵測設定變更並重新載入，不需要重建容器。";`」。
  3. **`checkConfigSource(Path source)` 的 Javadoc（第 194-203 行）**：「`<p><b>這一層的價值被一個既有設計放大</b>：{@code ProcessRcloneClient.configReady} 是啟動時判定一次的旗標、失敗後永不重試——一旦啟動當下讀不到 config，該容器<b>整個生命週期</b>的 Drive 同步都會被跳過，而症狀會偽裝成「Drive 好像還在收檔案」（另一個服務上傳的檔案照常出現）。故訊息必須明寫這個後果。`」比照 443.5a 第 4 點同樣的改法：改為說明 Task 443 之後每次呼叫都會重試，不再是「整個生命週期」，2026-07-28 事故作為歷史脈絡保留即可。
  4. **`reconnectHint(String remote)` 內部程式碼註解（第 343-345 行）**：「`// **這句不能省**：L2 解析的是啟動時複製到 /tmp 的副本，而兩支 client 都是啟動時複製一次、// 執行期不重讀來源（刻意不做熱重載，Task 247.5.1）。使用者在 host 重新授權後，// 執行中的容器仍在用舊快照，警告會一字不變——不講這句，他會以為修法沒效而繞回頭。`」須同步改為說明「Task 443 後兩支 client 都會在下一次操作時自動重新載入，此註解與其解釋的『仍須重建容器』文字一併作廢」，避免程式碼註解與新行為矛盾（與 443.5a 第 1 點對 ext 端 `RECONNECT_HINT` 上方註解的處理方式一致）。

- [x] **443.6 不要做的事**：不新增任何主動通知（email、UI 健康卡片、dashboard）；不改變 L1（`checkConfigSource`／`checkConfigReadable`）、L2（`checkTokenRefreshable`／`checkRefreshToken`／refresh_token 存在性）自檢的**判定邏輯與觸發時機**——**但這兩支 L1 方法內部斷言「configReady 啟動時判定一次、失敗後永不重試、整個生命週期都會跳過」的 Javadoc 與訊息文字，必須比照 443.5a 第 4 點／443.5b 第 3 點修正，不屬於本條排除範圍**（本條排除的是「什麼時候查、怎麼判斷 L1/L2 過不過」，不是「L1/L2 訊息裡對 Task 443 之後行為的文字敘述」）；不改變 `gdrive_enabled`／`gdrive_subpath`／`gdrive_last_run_at`／`gdrive_last_status` 的欄位定義、既有寫入邏輯或 `GdriveOutputSupport.truncate()` 的截斷長度；不新增 API 端點、DB 欄位或 Liquibase changeset；不改變任何 `@Scheduled` cron 或既有 best-effort 語意（Drive 失敗仍完全不得影響本機檔案寫入、不得中斷排程、不得拋出未捕捉例外）；`RcloneClient`／`GdriveUploader` 介面本身的方法簽章不變，不新增 `delete`／`move` 等方法；不修改 `ProcessBackupRemoteClient`、`[GoogleDriver]`／`[gdrive-crypt]`（DB 備份）路徑的任何程式碼——本次事故額外發現的 `GoogleDriver:` 帳號錯誤問題是另一個獨立事故，不在本任務範圍。

- [x] **443.7 測試——`backend`**：
  - 在 `backend/src/test/java/com/steven/assets/service/` 新增一個測試檔案，命名須能清楚辨識為「`ProcessRcloneClient` 的 config 重新載入行為」測試（與同目錄既有的 `ProcessRcloneClientRateLimitTest.java` 屬同一系列命名風格）：用 `@TempDir` 建立假的 source／writable 路徑，直接呼叫 443.1 新增的 package-private `reloadIfSourceChanged(Path, Path)`（同套件內測試類可直接呼叫，不需要反射）。覆蓋：首次呼叫（source 存在、writable 不存在）安裝成功並回傳 `true`；同一 source 內容再呼叫一次回傳 `false` 且 writable 內容不變（模擬 writable 被外部改寫後再呼叫，斷言 writable 保留外部改寫的內容、不被覆蓋回舊 source）；修改 source 內容後再呼叫回傳 `true` 且 writable 內容更新為新 source；source 檔案不存在時回傳 `false`、不拋例外；於安裝一次成功後刪除 source 檔，再次呼叫回傳 `false` 但不清空先前已安裝的 writable 內容（用檔案系統原生 `Files.readAllBytes(writable)` 驗證）。
  - 併發測試（可放在同一個檔案內）：兩個執行緒同時對同一組 source／writable 呼叫 `reloadIfSourceChanged`（source 內容固定），用 `CountDownLatch` 對齊起跑；結束後斷言 writable 檔案內容完整（非 partial write）、可正常讀出且等於 source 內容，兩次呼叫都不拋例外。
  - 在既有的 `ProcessRcloneClientRateLimitTest.java` 同目錄，比照該檔的既有風格（反射呼叫 private static 純字串判斷方法，不啟動真的 process），為 443.3 新抽出的 private static 判斷方法（例如 `isInvalidClient`，與 `isRateLimited` 同一種寫法）新增一個對應的測試檔案，命名須能清楚辨識為「invalid_client 分類判斷」測試：斷言含 `invalid_client`（含大小寫混合）回 `true`；`directory not found`、空字串、`null`、rate-limit 字串都回 `false`。

- [x] **443.8 測試——`external-materials-service`**：
  - 在 `external-materials-service/src/test/java/com/steven/assets/externalmaterials/service/` 新增一個測試檔案，命名須能清楚辨識為「`ProcessGdriveUploader` 的 config 重新載入行為」測試，比照 443.7 的 `reloadIfSourceChanged` 測試案例（首次安裝、內容不變不重裝、內容改變才重裝、source 消失不清空既有 writable、atomic move 語意、併發安全）。
  - 比照 443.7，將 443.4 的 `invalid_client` 判斷抽成 private static 方法並以反射測試同一組輸入案例（可放在同一個新測試檔內）。
  - 更新既有的 `external-materials-service/src/test/java/com/steven/assets/externalmaterials/service/GdriveSelfCheckTest.java`：既有涵蓋「找不到 section」「速率限制」等分類的測試斷言不得回歸；新增一則驗證 443.5 新增的第四個成因字樣（「client_secret 與其他共用服務」或等價可辨識片段）確實出現在 `probe()` 產生的 WARN 訊息裡。

- [x] **443.9 `backend` 既有測試不得回歸**：`GdriveSelfCheckTest.java`（backend）、`ExportScheduleGdriveTest.java`、`CrawlerGdriveOutputTest.java`、`TradingExportGdriveTest.java`、`GdriveOutputSupportTest.java`、`GdriveBrowseTest.java` 這些既有測試檔都間接使用 `ProcessRcloneClient`／其介面替身，443.1 改動 `configReady`／`requireConfig` 的內部實作後，這些既有測試必須全部維持通過（多半透過 `RcloneClient` 介面 mock，不直接依賴 `ProcessRcloneClient` 內部欄位，預期不受影響，但須實際跑過確認）。

## 驗證

```bash
# 1. 後端與 ext 全量單元測試
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml test

# 2. 機械 spec 閘門
bash scripts/spec-check.sh

# 3. 從 main 的 worktree 重建兩個受影響服務（比對 main 的 .env 是否與本 worktree 一致，見
#    scripts/README.md 的既有規範；不一致要從 main 的目錄執行 compose）
build_worktree=$(git rev-parse --show-toplevel)
main_worktree=$(git worktree list --porcelain | awk '/^worktree /{p=$2} /^branch refs\/heads\/main$/{print p}')
test -n "$main_worktree"
diff <(sort "$build_worktree/.env") <(sort "$main_worktree/.env") || echo "WARNING: .env 有差異，請確認後再從正確目錄 build"
cd "$main_worktree"
docker compose -p asset-management build --no-cache business-services external-materials-service
docker compose -p asset-management up -d --no-deps --force-recreate business-services external-materials-service
for svc in asset-business-services asset-external-materials-service; do
  for _ in $(seq 1 60); do
    test "$(docker inspect "$svc" --format '{{if .State.Health}}{{.State.Health.Status}}{{end}}')" = healthy && break
    sleep 2
  done
  test "$(docker inspect "$svc" --format '{{.State.Health.Status}}')" = healthy
done

# 4. 實機回歸驗證：兩服務重建後，真實的匯出／爬蟲手動觸發端點仍正常運作（含 Drive 同步）。
#    注意：「host 修好設定後，容器不需要 recreate 就能撿到新設定」這個核心行為變更，
#    刻意**不**在這裡用會動到正式 Google 憑證的方式驗證——比照 Task 388 388.10／t388 檔案末尾的既有原則
#    （「錯帳號負測試必須由離線 fake process runner 執行，不可為了驗收而改寫真實
#    ~/.config/rclone/rclone.conf...不得為測試人為破壞健康授權」），本任務同理不得暫時改壞正式的
#    client_secret 或 token 來觀察「壞掉又修好」，那樣一旦清理步驟失敗，會讓正式的 Drive 同步真的斷線。
#    reload-without-recreate 這個行為已由 443.7／443.8 的離線單元測試（@TempDir 假 source／writable
#    路徑，直接呼叫 reloadIfSourceChanged）完整、確定性地涵蓋，不需要在真實憑證上重複驗證。
docker exec asset-business-services curl -sS -X POST "http://localhost:8080/api/export-schedule/run-now" \
  -H "X-User-Id: 1" -H "X-User-Role: ADMIN" -H "X-User-Status: ACTIVE" -w "\nHTTP_STATUS:%{http_code}\n"
# 上述 run-now 回應須含 "成功"。
docker exec asset-external-materials-service curl -sS -X POST "http://localhost:8080/internal/news-poller/export-now" \
  -w "\nHTTP_STATUS:%{http_code}\n"
# 上述 export-now 為既有端點（InternalPriceController，只重產檔案＋同步 Drive、不重新抓取），
# 回應須為 HTTP 200 且 ManualRunResult 內容不含錯誤狀態。
docker logs asset-business-services --since 5m 2>&1 | grep -i "drive" || echo "（近 5 分鐘無 Drive 相關 log，屬正常）"
docker logs asset-external-materials-service --since 5m 2>&1 | grep -i "drive.*失敗\|invalid_client" && exit 1 || echo "（無 Drive 失敗或 invalid_client log，符合預期）"
```

**人工查核點（不可省略，需要肉眼確認）：** 執行第 4 段後，用 `docker exec asset-external-materials-service rclone lsf "GDriveOutput:投資理財/資產管理" --config /tmp/rclone-output.conf | tail -5` 確認最新檔案的日期是今天，且 `docker exec asset-postgres psql -U assets -d assets -t -c "SELECT gdrive_last_run_at, LEFT(gdrive_last_status,60) FROM export_schedule_setting WHERE id=1;"` 顯示的狀態字串包含「成功」而非「invalid_client」或「失敗」。

## 完成報告

2026-09-17 Codex 接手 Claude 因 session limit 中斷的實作並完成驗收。

- 程式變更：backend 與 external-materials-service 各自的 process client、自檢訊息；新增三支離線測試並更新兩支既有自檢測試。source-fingerprint reload、0600 原子安裝、失敗保留舊設定、初始缺檔恢復及 invalid_client 固定診斷均已查證。
- 後端全量執行成功，最終 Surefire 報表 2,228 tests／0 failures／0 errors（含最後追加的權限失敗測試）；本次兩支新增測試 14 tests 全過。
- external 全量 771 tests／8 failures／0 errors，最後追加測試後報表合計 772 tests；新增 reload 測試 14 與 self-check 13 全過。Docker 測試 JVM 使用 `-DextraArgLine=-Dapi.version=1.44`，Java 21。
- 八個 external 失敗全部在乾淨 main `fa61ea18` 的四支整合測試中重現（15 tests／8 failures／0 errors，exit 1），方法與 assertion 完全一致，屬本次變更前既有問題：dividend evidence 三項、technical cache race 兩項、quote snapshot name 兩項、Taiwan live name 一項。本次未擴大修改富邦業務邏輯；全量套件不能宣稱全綠。
- 獨立架構審查 critical／major／minor 均為 0；spec-check BLOCK 0／CHECK 0，9090 十三路 OpenAPI 與 generated Swagger 文件同步。Task 443.5a 項目數僅修正文字為五處，無變更行為契約。
- 合併前從 feature 重建並 force-recreate 僅 business-services 與 external-materials-service，使用 main 的正式環境設定；兩者 healthy，BFF 未重啟，9090 market-index 回 JSON、quotes 回 JSON array。
- 部署產物（UTC）：business image `bb271ac22fff3fca7d1913892194a925a1f9eac5f582b482cac5943b76520f04` 建立於 `2026-09-17T14:30:04Z`、container 建立於 `2026-09-17T14:31:42Z`；external image `b4a17ba33f6a310f3e102db5f7506fb1d4fdd9ee5375738fa5d5af7da1fcc850` 建立於 `2026-09-17T14:31:35Z`、container 建立於 `2026-09-17T14:31:45Z`。最後 inspect 的 image 一致性 `consistent=true`；BFF container 建立／啟動時間維持 `2026-09-16T15:14Z`，期間無 recreate/start/die events。
- 真實匯出：business run-now HTTP 200，xlsx/json Drive 狀態皆成功；external export-now HTTP 200、status OK、mode EXPORT_ONLY，兩個 Drive 檔成功，未重新抓取新聞。
- 已核實資料庫 target 為 assets/assets；`export_schedule_setting.id=1` 的 `gdrive_last_run_at=2026-09-17 22:32:50.476283`，狀態包含「xlsx 成功」。Drive 目錄列出 20260917 的 JSON/XLSX；external 近五分鐘無 Drive failure／invalid_client。
- 保持原有 Drive 目的帳戶與設定；未修改正式憑證，reload-without-recreate 以離線假路徑／假 process 證明。
