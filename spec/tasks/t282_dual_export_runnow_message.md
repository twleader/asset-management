# [t282] 九個匯出頁的手動匯出結果提示改為報出 JSON 與 Excel 兩個落點

**對應 Requirements:** Requirement 55（所有自動匯出的檔案一律同時產出 JSON 與 Excel 兩份，主檔名相同）
**前置任務:** t269／t270／t271／t272（雙格式匯出已落地，後端兩份都產、回應也已帶兩個路徑欄位）
**Liquibase changeset:** 無（純前端，零 schema 變更）

## 背景

**使用者回報（2026-08-02）：「我按了匯出，應該匯出 json 和 excel，結果只匯出 excel。」**

實測結果是**檔案兩份都在，UI 說謊**。使用者在「GDP／台股大盤」頁按下「立即匯出到目錄」後：

```
$ ls -lat /Users/steven/Project/SRPP/data/input | head -3
-rw-r--r--  1 steven  staff  38556  Aug  2 16:35 台股大盤_1_20260802.json
-rw-r--r--  1 steven  staff  13038  Aug  2 16:35 台股大盤_1_20260802.xlsx
```

畫面上的「上次上傳」也顯示 `xlsx 成功：… / json 成功：…` 兩份都上了 Google Drive。但當下跳出的
toast 只有一句 `已匯出到：/home/steven/Project/SRPP/data/input/台股大盤_1_20260802.xlsx`——
使用者因此以為 JSON 沒產出。

**錯誤行為**：九個有手動觸發入口（「立即匯出」，交易日曆叫「匯出到目錄」）的匯出頁，
成功提示只讀 response 的**一個**路徑欄位。

**正確行為**：一律讀**兩個**路徑欄位並都列出；只成功一份時改用黃色警告並明講缺的是哪一種格式。

**為什麼九頁全錯**：後端 t269–t272 把兩份檔、兩個路徑欄位、合併狀態字串都做好了
（`DualFormatExportWriter` 回 `DualResult(jsonFile, xlsxFile, localStatus, gdriveStatus, …)`，
各服務的 `RunNowResponse` 也已帶 `jsonPath`／`jsonSizeBytes`／`jsonGdrivePath`），前端卻只改了
**設定卡的檔名說明文字**，沒改**按鈕的結果提示**。`t270.3`／`t271.5`／`t272.3` 三處都白紙黑字寫著
「run-now 成功提示改為同時顯示兩個落點（讀新增的 `jsonPath`）」的同義要求並打了 `[x]`，
`t272.3.1` 則自稱「全前端最後一次巡檢」卻用了抓不到它的判準——**那四個勾都是假的**。
唯一做對的是**爬蟲資訊查詢頁**，而那一頁的四分支是 **t280** 落地的
（`CrawlerDataView.vue:534-562` 的 `showManualResult`；t272 落地時該頁根本還沒有手動觸發入口）。

> **這件事被漏掉的結構原因，本任務要一併堵住**：該約束只寫在 design.md 與任務檔，
> `requirements.md` 的 Requirement 55 只有「檔名說明文字須同步」那一條。實作者改完文案就把整個
> 前端項目視為完成，而**沒有任何 requirement 層級的驗收條件會因此變紅**。故本任務除了修程式，
> 還要（a）在 Requirement 55 補上這條驗收條件（已於本次 spec 變更完成）、
> （b）把 t270.3／t271.5／t272.3／t272.3.1 那四個假的 `[x]` 改回未完成並註明由本任務承接。

## 要做什麼

### 282.1 新增共用模組 `frontend/src/utils/dualExportMessage.js`

- [x] 282.1 建立 `frontend/src/utils/dualExportMessage.js`，匯出單一函式：

  ```js
  showDualExportResult({ jsonPath, xlsxPath, gdriveStatus, prefix })
  ```

  **九頁一律呼叫它，不得逐頁各寫一份 template literal。** 理由與後端把落檔收斂進
  `DualFormatExportWriter` 相同：九份各寫一次，措辭與判準必然分歧，而「只成功一份」正是最少被
  觸發、最容易在複製貼上中漏掉的那一支。撰寫風格比照同目錄既有的 `gdriveSelfCheck.js`
  （具名 export、`import { ElMessage } from 'element-plus'`、檔頭 Javadoc 式中文註解寫明「為什麼」）。

  **行為規格（五分支，順序即判斷順序：先判本機落點、再判 Drive）：**

  | # | 判準 | 型別 | 訊息內容 |
  |---|---|---|---|
  | 1 | `jsonPath` 與 `xlsxPath` **皆為空** | `warning` | 「本輪未產出任何檔案，請檢查輸出資料夾權限與磁碟空間」 |
  | 2 | 恰有一個為空 | `warning` | 列出成功那一份的完整落點，並**明講**缺的是 JSON 還是 Excel |
  | 3 | 兩份都有；`gdriveStatus` 非「兩半皆成功」且含「失敗」／「略過」／「跳過」 | `warning` | 「本機兩份已寫出，但 Google Drive 同步未全部成功：<gdriveStatus>」 |
  | 4 | 兩份都有；`gdriveStatus` 非「兩半皆成功」的其餘情形 | `info` | 「本機兩份已寫出；Google Drive：<gdriveStatus>」 |
  | 5 | 其餘（`gdriveStatus` 為空＝Drive 未啟用，或兩半皆成功） | `success` | 「已匯出 <jsonPath> 與 <xlsxPath>」；`gdriveStatus` 非空時後綴「；Google Drive：<gdriveStatus>」 |

  **六條硬約束：**

  1. **輸入是兩個具名落點，不是整包 response。** 九頁的欄位名**不一致**：第 1～8 項的
     `path`＝xlsx、`jsonPath`＝json；第 9 項警示觸發相反，`path`＝**json**（`alert_triggers_*.json`
     是該頁的對外契約）、`xlsxPath`＝xlsx。**模組內不得硬編任何 response 欄位名**，由呼叫端各自對映。
     在模組內讀 `r.path` 會讓警示觸發頁把兩份**說反**，而說反是**靜默**的——兩個檔案都在，只有文字錯。
  2. **Drive 的主判準是「兩半是否皆為成功」，不得寫成列舉關鍵字的黑名單。** 單邊狀態有**五種**取值
     （`backend/.../service/GdriveOutputSupport.java:283/287/292/295/301`）：

     ```
     成功：<remote:path>（<n> bytes）
     逾時（<n> 秒）：Drive 端可能已完成，請於下一輪確認
     暫時未上傳：Drive API 達每分鐘查詢上限，下一輪排程會自動重試
     失敗：<訊息>
     跳過：<原因>
     ```

     另有兩份未皆成功時由 `DualFormatExportWriter.java:101` 直接寫入的
     `略過：本機未兩份皆成功，不上傳`（是「**略**過」不是「跳過」，兩個字都要涵蓋）。
     **只判「含失敗或跳過」會讓「逾時」與「暫時未上傳」落進綠色 `success`**——那正是本任務
     要修的同一類 UI 說謊，只是換成 Drive 那一層。可直接使用的判準：

     ```js
     const bothOk = /^xlsx 成功：/.test(gd) && gd.includes('／json 成功：')
     ```

     `bothOk` 為真 → 分支 5；為假再以關鍵字區分分支 3（含 `失敗`／`略過`／`跳過`＝確定沒上去）
     與分支 4（其餘＝結果未定）。
  3. **判準是「包含」不是「開頭」，而且不得簡化成 `gd.includes('成功')`。**
     合併格式為 `xlsx <狀態>／json <狀態>`，三個產生點一致
     （`DualFormatExportWriter.java:98-99`、`StockAlertTriggerExportService.java:553`、
     `TradingCalendarExportScheduleService.java:269-270`——**警示觸發頁雖然 json 才是主格式，
     合併字串仍是 `xlsx …／json …`**）。「xlsx 上傳失敗、json 成功」時整串以 `xlsx ` 起頭而非
     「失敗」，`startsWith('失敗')` 判不到；而 `includes('成功')` 在同一情境為真，
     **會把最常見的那種部分失敗顯示成綠色成功**。
     **截斷不會破壞這個判準**：兩半各自從**尾端**截斷至 250 字元後才合併
     （`DualFormatExportWriter.java:40-41`／`:170-172`），`成功：` 前綴必然存活；
     反之任何以尾端特徵（例如 `bytes）`）判斷的寫法會在長路徑時失效。
  4. **任何分支都不得把 `null`／`undefined` 印進訊息。** 判空一律用
     「trim 後為空字串」，涵蓋 `null`、`undefined`、`''` 三種。
  5. **分支 1 走 `warning`，不是 `error`。** 呼叫端能走到這裡代表 HTTP 已成功回應；
     真正的例外由各頁既有的 `catch` 區塊處理，此處只負責「跑完了但什麼都沒產出」這一格。
  6. **`prefix` 為可選前綴字串**（預設 `''`），供呼叫端接既有的額外資訊（警示觸發頁的
     `r.message` 等）；非空時以 `；` 與後段相接。**不得**把這些資訊搬進模組——那是各頁自己的語意。

### 282.2 九頁逐一改用共用模組

行號為實測值（`ce409536`），實作前重新 `grep -n` 確認位移。**每一頁都必須實際改，不得只改一部分。**

- [x] 282.2.1 `frontend/src/views/AssetHistoryView.vue:346`（資產總覽）
      `handleRunNow`：`ElMessage.success(\`已匯出到：${r.path}\`)`
      → `showDualExportResult({ jsonPath: r.jsonPath, xlsxPath: r.path, gdriveStatus: r.gdriveStatus })`
- [x] 282.2.2 `frontend/src/views/RealizedGainView.vue:433`（已實現損益）同上對映
- [x] 282.2.3 `frontend/src/views/ExchangeRateView.vue:550`（台幣兌美元）同上對映
- [x] 282.2.4 `frontend/src/views/GdpTwseView.vue:567`（大盤指數，**使用者回報的那一頁**）同上對映
- [x] 282.2.5 `frontend/src/views/CommodityPriceView.vue:590`（油價金價）同上對映
- [x] 282.2.6 `frontend/src/views/TransactionView.vue:831`（交易紀錄）同上對映。
      **本頁是多排程**（`handleRunNow(idx)` 逐筆排程各自 run-now），四個欄位一樣來自該筆的回應，
      不需要額外處理。
- [x] 282.2.7 `frontend/src/views/TradingRadarView.vue:1102-1106`（交易雷達）。現況**逐字**為：

      ```js
      if (res?.path) {
        ElMessage.success(`已寫入 ${res.path}（${Math.round((res.size || 0) / 1024)} KB）`)
      } else {
        ElMessage.warning(res?.message || '當日尚無快照，未產檔')
      }
      ```

      **既有的「當日尚無快照」分支必須保留**（那是業務語意上的「沒東西可匯出」，與
      「產檔失敗」不同），但**判準必須改**：現況的鑑別條件是 `res?.path`，`message` 只是 fallback，
      因此兩份 render 都失敗時（`TradingRadarExportScheduleService.java:410-421` 兩個 try/catch
      各自吞例外、`:424` 照樣呼叫 `dualWriter.write`）會得到
      `path=null, jsonPath=null, message="匯出完成"`（`:243`），畫面跳出的是**一則黃色警告、
      內容卻寫著「匯出完成」**——既沒說產檔壞了，也沒給落點。
      正確判準是比對訊息內容：`res?.message?.includes('當日尚無快照')`
      （`NO_SNAPSHOT_STATUS = "當日尚無快照，未產檔"`，見 `:68`；`:234` 另有加後綴的
      `NO_SNAPSHOT_STATUS + "（重算失敗且當日無既有快照）"`，`includes` 兩者都命中——
      **不要改成 `===`，那會弄丟這個降級細節**）沿用既有 warning，
      其餘一律交給 `showDualExportResult({ jsonPath: res.jsonPath, xlsxPath: res.path, gdriveStatus: res.gdriveStatus })`。
      **KB 數字可以不再顯示**——兩份大小差很多（實測 json 約為 xlsx 的 3～5 倍），
      只印一個會誤導，兩個都印訊息太長；落點本身才是 run-now 要驗證的東西。
- [x] 282.2.8 `frontend/src/views/TradingCalendarView.vue`（交易日曆）**兩處都要改**：
      - `:625` `doExport` 的 toast → `showDualExportResult({ jsonPath: r.jsonPath, xlsxPath: r.path, gdriveStatus: r.gdriveStatus })`
      - `:159-162` template 的 `export-status` 區塊，現況**逐字**為（**注意每一個 binding 都有
        `exportDialog.` 前綴**——全檔沒有獨立的 `lastResult` binding，照抄成 `lastResult.jsonPath`
        會在 render 時對 `undefined` 取屬性）：

        ```html
        <div v-if="exportDialog.lastResult" class="export-status">
          ✅ 已匯出：<code>{{ exportDialog.lastResult.path }}</code>
          （{{ exportDialog.lastResult.totalDays }} 天，{{ (exportDialog.lastResult.sizeBytes / 1024).toFixed(1) }} KB）
        </div>
        ```

        → **兩個 `<code>` 都列出**（`exportDialog.lastResult.path` 與
        `exportDialog.lastResult.jsonPath`）。這一列**留在畫面上**、比一閃即逝的 toast 更常被當成事實，
        只列一份等於把同一個錯誤釘在畫面上。`exportDialog.lastResult.jsonPath` 為空時該 `<code>`
        不顯示，不得印出 `undefined`。
        **`sizeBytes` 那個 KB 一併移除**，理由同 282.2.7：`sizeBytes` 只有 xlsx 那一份，
        改完會變成「兩個落點、一個大小」，比不顯示更誤導。`totalDays` 保留（它與格式無關）。
- [x] 282.2.9 `frontend/src/views/StockAlertView.vue:688`（警示觸發）
      現況 `ElMessage.success(\`${r.message}：${r.path}（${r.size} bytes）\`)`，
      下一行另有 `if (r.gdriveStatus) ElMessage.info(...)`。
      → `showDualExportResult({ jsonPath: r.path, xlsxPath: r.xlsxPath, gdriveStatus: r.gdriveStatus, prefix: r.message })`
      **本頁的 `path` 是 json、`xlsxPath` 才是 xlsx，對映與其餘八頁相反，不可照抄。**
      既有那句 `ElMessage.info(\`Google Drive：${r.gdriveStatus}\`)`（`:689`）**移除**——
      分開彈第二則 toast 會與共用模組那一則疊在一起互相遮蔽。
      **移除的前提是分支 3／4／5 三者都照規格實作**：該行是全庫唯一會把「逾時」「暫時未上傳」
      以及 **Drive 成功時的落點**顯示出來的地方（其餘八頁本來就沒有）。
      分支 3／4 承接非成功狀態、分支 5 末句的「`gdriveStatus` 非空時一併附上」承接成功狀態；
      少做任何一個，該頁就會有一類 Drive 資訊憑空消失——那是既有語意的退化，違反 282.3 訂下的界線。

- [x] 282.3 `frontend/src/views/CrawlerDataView.vue` 的 `showManualResult`（`:534-562`）
      **本次不動，而且它既有的黑名單判準是對的，不得「順手修正」成本任務的白名單。**

      理由一，它多帶爬蟲頁獨有的語意：「輸出／入庫筆數」與 `status !== 'OK'` 的五種狀態
      （`BUSY`／`RUNNING`／`DISABLED`／`FAILED`／`ERROR`）。**強行併進共用模組會把爬蟲頁的
      狀態機拖進來**，讓其餘八頁扛一堆用不到的分支。

      理由二（**這一條容易被誤判成 bug，務必看完再動手**）：`:557` 的
      `gd.includes('失敗') || gd.includes('跳過')` 看起來就是 282.1 硬約束 2 判定為錯的黑名單，
      但**在該頁它是窮盡的**。爬蟲頁的 Drive 同步走 `external-materials-service` 自己的實作
      （`NewsPoller.java`），狀態語彙只有三種：`成功：`（`:551`／`:563`）、`跳過：`（`:533`／`:535`／
      `:540`／`:557`）、`失敗：`（`:567`／`:579`）——**逾時在該 module 被 catch 成
      `失敗：rclone …逾時（45 秒）`**（`ProcessGdriveUploader.java:189` 擲 `RuntimeException`），
      根本不存在「逾時」與「暫時未上傳」兩個前綴。那兩種是 backend `GdriveOutputSupport` 才有的，
      而這九頁走的正是 backend。Requirement 63 對爬蟲頁要求黑名單、Requirement 55 對這九頁禁止黑名單，
      兩者**沒有衝突**，差別純粹在兩邊的狀態語彙不同。

      改動範圍以「不讓任何一頁的既有語意退化」為界。

### 282.4 回填四個假的 `[x]`

- [x] 282.4 **（已於本任務的 spec 變更當下完成，實作階段不必再動 spec）** 把下列**四**處假的 `[x]`
      改回 `[ ]`，並在該項下方以引言區塊註記「文案已完成、run-now 結果提示未完成，由 t282 承接」：
      - `spec/tasks/t270_dual_format_single_table_exports.md` 的 `270.3`（五頁）
      - `spec/tasks/t271_dual_format_multi_block_exports.md` 的 `271.5`（三頁）
      - `spec/tasks/t272_dual_format_json_primary_exports.md` 的 `272.3`（`StockAlertView`；
        該項的爬蟲頁那一半實際是 t280 落地的，本任務落地時爬蟲頁還沒有手動觸發入口）
      - `spec/tasks/t272_dual_format_json_primary_exports.md` 的 `272.3.1`（自稱「全前端最後一次巡檢」，
        但它的 grep 判準只掃 `.xlsx`／`.json` 字面，而結果提示印的是 `${r.path}` 這種變數，
        **判準在設計上就抓不到**）
      **檔名說明文字那一半四處都確實做到了，註記一律寫明「文案已完成、結果提示未完成」**，
      不要把整項說成沒做——那會讓後續讀者以為文案也要重做。

### 282.5 明確不做的事

- [x] 282.5 **不引入前端測試框架。** `frontend/package.json` 目前沒有任何 test 相依與 script，
      全庫零前端測試。為這支 30 行的工具函式引入 vitest ＋ jsdom ＋ 對 `element-plus` 的
      `ElMessage` 做 mock，是一個獨立的基礎設施決定，不該夾帶在一個 bug fix 裡。
      本任務的驗證改走「grep 判準 ＋ 實機點擊」（見驗證段）。
- [x] 282.6 **不動任何後端／BFF 程式碼。** 九支 run-now 端點的回應**已經**帶著兩個路徑欄位
      （實測：`IndexExportDto`／`ExportScheduleDto`／`RealizedGainExportDto`／`CommodityExportDto`／
      `ExchangeRateExportDto`／`AssetTransactionExportDto`／`TradingRadarExportDto`／
      `TradingCalendarExportDto` 皆有 `jsonPath`，`StockAlertExportDto` 有 `xlsxPath`），
      九支 BFF 路由**皆為 `Map<String,Object>` passthrough 或 Gateway rewrite**
      （交易雷達走 `TradingRadarBffRoutes` 的 `rewritePath`），新欄位本來就會透傳。
      **本任務若動到 `backend/**` 或 `bff/**`，就是走錯方向了。**
- [x] 282.7 **不動任何「手動下載」的檔名。** 各頁 `a.download = …xlsx`／`filename = …xlsx`
      是使用者按鈕觸發的瀏覽器下載，不在 Requirement 55 範圍（t270／t271 已明文保護）。

## 驗證

### (a) 舊寫法歸零

改動前九頁全部命中（`9`），改完後應為 `0`。**必須用 `-F -e` 的固定字串比對**：待搜的字串含
`${…}`，而 `{`／`}` 在 BRE 下屬實作定義行為——實測 agent 環境的 `grep`（ugrep 的 `-G` 包裝）
會把整條 pattern 吃掉而回 `0`，`/usr/bin/grep` 回 `9`。用會回 0 的寫法當判準，等於改動前就「通過」，
與本任務批評 272.3.1 的錯誤是同一個。`-F -e` 版本兩種實作都回 `9`（已實測）：

```bash
grep -rnF -e '已匯出到：${r.path}' -e '已寫入 ${res.path}' -e '${r.message}：${r.path}' frontend/src/views/ | wc -l
```

### (b) 九頁都真的接上共用模組

應列出**九**個檔案（爬蟲頁不在內，見 282.3）：

```bash
grep -rln 'showDualExportResult' frontend/src/views/ | sort
```

預期：`AssetHistoryView.vue`／`CommodityPriceView.vue`／`ExchangeRateView.vue`／`GdpTwseView.vue`／
`RealizedGainView.vue`／`StockAlertView.vue`／`TradingCalendarView.vue`／`TradingRadarView.vue`／
`TransactionView.vue`。

### (b2) 共用模組內部的判準本身

**這一條不可省略。** 本任務花了最多篇幅論證的東西是 282.1 硬約束 2／3 的 Drive 白名單判準，
但 (a)(b)(c) 全都只驗「呼叫端有沒有接上」，**沒有一條會在模組內部寫成黑名單或
`gd.includes('成功')` 時變紅**——那正好重演本任務批評 272.3.1 的錯誤（判準抓不到它要防的事）。
四條探針，前兩條是白名單存在、後兩條是錯誤寫法不存在：

```bash
grep -cF "/^xlsx 成功：/.test" frontend/src/utils/dualExportMessage.js   # 應為 1
grep -cF "／json 成功：" frontend/src/utils/dualExportMessage.js          # 應為 1
grep -cF "includes('成功')" frontend/src/utils/dualExportMessage.js       # 應為 0
grep -cF "'info'" frontend/src/utils/dualExportMessage.js                # 分支 4 的探針，應 ≥ 1
```

> 第三條的 `0` 判準連**註解**都算：解釋「為什麼不能這樣寫」時若照抄了 `includes('成功')`
> 這個字面，探針就被自己的註解破壞了（實作時實際踩到，已把該句改寫成「整串含成功兩字」）。
>
> 第四條**不寫成 `grep -c "ElMessage.info"`**：實作把五個分支的 `ElMessage({ type, message, duration, showClose })`
> 收斂成一支 `show(type, message)`（訊息含兩個絕對路徑，預設 3 秒讀不完，故統一 `duration: 8000`
> ＋ `showClose`），分支 4 的字面是 `show('info', …)` 而非 `ElMessage.info(…)`。

### (c) 兩種對映都沒有寫反

**這是本任務最容易靜默出錯的一處**（兩個檔案都在，只有文字錯），故正反兩個方向都要驗。

警示觸發頁必須是 `jsonPath: r.path`（不是 `xlsxPath: r.path`），應輸出 `1`：

```bash
grep -c 'jsonPath: r.path' frontend/src/views/StockAlertView.vue
```

其餘八頁必須相反（`path`＝xlsx）。七頁的 response 變數名為 `r`、交易雷達為 `res`：

```bash
grep -rn 'xlsxPath: r\.path' frontend/src/views/ | wc -l       # 應為 7
grep -rn 'xlsxPath: res?\.path' frontend/src/views/ | wc -l    # 應為 1
grep -rln 'jsonPath: r\.path' frontend/src/views/              # 只該列出 StockAlertView.vue
```

七頁為 `AssetHistoryView`／`RealizedGainView`／`ExchangeRateView`／`GdpTwseView`／
`CommodityPriceView`／`TransactionView`／`TradingCalendarView`，一頁為 `TradingRadarView`
（**該頁沿用既有的 `res?.` optional chaining**——它的 `showDualExportResult` 位於
`res?.message?.includes(…)` 為假的 else 分支，`res` 為 null 時仍會走到，寫成 `res.path` 會擲例外）。
**若 `jsonPath: r.path` 在 `StockAlertView` 以外的檔案出現，就是把警示觸發頁的相反對映複製出去了。**

### (d) 建置

```bash
cd frontend && /Users/steven/.nvm/versions/node/v22.21.0/bin/node ./node_modules/.bin/vite build
```

### (e) 部署到實際運行的 stack（本專案沒有 dev server，「改好」＝重建映像＋重建容器）

**前端 build 會命中 layer cache 而沒重跑 vite，必須 `--no-cache`；`-p asset-management` 不可省略**
（省略時 project 名取自目錄名，會建到沒人用的 tag，且印 `Built`、exit 0，完全看不出來）：

**第一行的 `cd` 不可省略**：(d) 已經 `cd frontend`，同一個 shell 接著跑會把 `.env` 複製到
`frontend/`，而 compose 的 `env_file` 是相對 compose 檔解析的，`--env-file` 救不了。

```bash
cd /Users/steven/Project/asset-management/.claude/worktrees/compound-condition-trigger-f55151
cp /Users/steven/Project/asset-management/.env .env
docker compose -p asset-management build --no-cache frontend
docker compose -p asset-management up -d --no-deps --force-recreate frontend
docker images --format '{{.Repository}}:{{.Tag}}\t{{.CreatedAt}}' | grep asset-management-frontend
```

### (f) 實機點擊確認（這一條才是本任務的驗收核心）

登入後開「GDP／台股大盤」頁（使用者回報的那一頁），按「立即匯出到目錄」，toast 必須**同時**出現
`.json` 與 `.xlsx` 兩個落點。接著以檔案系統交叉確認兩份都真的產出、且主檔名相同：

```bash
ls -lat /Users/steven/Project/SRPP/data/input | head -4
```

再確認訊息裡的兩個路徑與 `ls` 的兩個檔名逐字元一致（只差副檔名）。

> **已知的驗證缺口，明文記錄而不假裝有覆蓋**：(f) 在 Drive 正常時只會命中**分支 5**。要實機走到
> 分支 3（Drive 確定失敗）或分支 4（逾時／暫時未上傳），必須人為破壞使用者真正在用的 Drive 設定
> 或等待外部服務出錯——前者會動到正式設定、後者不可控，兩者都不該寫進例行驗收步驟。
> **這兩個分支改由 (b2) 的靜態探針守門**：探針保證判準是白名單、且 `ElMessage.info` 這條路徑存在。
> 分支 1（兩份都沒產出）同理，只有磁碟滿或目錄不可寫時才會出現，不做實機驗證。

## 完成報告

**狀態：已完成並部署，使用者已於實機確認（2026-08-02）。** spec 經三輪對抗式審查
（第一輪 1 critical／5 major、第二輪 3 major、第三輪 2 major，全數修完後記錄通過）。

### 實際改了哪些檔（10 個，零 backend／BFF／DB 變更）

| 檔案 | 改動 |
|---|---|
| `frontend/src/utils/dualExportMessage.js` | **新增**。`showDualExportResult({jsonPath, xlsxPath, gdriveStatus, prefix})`，五分支；內部另有 `show(type, message)` 統一 `duration: 8000` ＋ `showClose` |
| `AssetHistoryView.vue`／`RealizedGainView.vue`／`ExchangeRateView.vue`／`GdpTwseView.vue`／`CommodityPriceView.vue`／`TransactionView.vue` | 各一處：`ElMessage.success(\`已匯出到：${r.path}\`)` → `showDualExportResult({ jsonPath: r.jsonPath, xlsxPath: r.path, gdriveStatus: r.gdriveStatus })` |
| `TradingRadarView.vue` | 判準改為 `res?.message?.includes('當日尚無快照')`；else 分支交給共用模組（`res?.` optional chaining 保留）；不再顯示 KB |
| `TradingCalendarView.vue` | `doExport` 的 toast ＋ template 的 `export-status`（兩個 `<code>`、移除 KB、保留 `totalDays`） |
| `StockAlertView.vue` | 對映相反（`jsonPath: r.path`／`xlsxPath: r.xlsxPath`）＋ `prefix: r.message`；移除原本的 `ElMessage.info(gdriveStatus)` |

### 驗證輸出

```
(a) 舊寫法歸零          → 0（改動前 9）
(b) 接上共用模組的頁面  → 9 個檔案，與預期清單逐一相符
(b2) 模組內部判準       → 白名單 1、／json 成功： 1、includes('成功') 0、'info' 1
(c) 對映方向            → StockAlertView 的 jsonPath: r.path = 1
                          xlsxPath: r.path = 7、xlsxPath: res?.path = 1
                          jsonPath: r.path 只出現在 StockAlertView.vue
(d)(e) 建置與部署       → docker compose -p asset-management build --no-cache frontend 成功，
                          映像 asset-management-frontend:latest 於 2026-08-02 17:39 重建，
                          容器 recreate 後 grep 運行中 bundle：
                            /usr/share/nginx/html/assets/dualExportMessage-DSc26BIT.js 存在
                            '已匯出到：' 在 bundle 內 0 命中（確認非 stale image）
(f) 實機                → 使用者在「GDP／台股大盤」頁按下「立即匯出到目錄」，
                          回報「兩個落點都出現了」
```

五個分支另以 node 直接跑過模組原始檔（只把 `element-plus` 的 import 換成印出 `[type] message`
的 stub，其餘一字未改），九組輸入涵蓋分支 1／2a／2b／3a／3b／4a／4b／5a／5b ＋ 警示觸發頁的
相反對映，輸出型別與措辭全部符合上表。

### 與原計畫的偏差

1. **`vite build` 沒有在 worktree 本機跑**：本 worktree 沒有 `frontend/node_modules`。
   改以 `docker compose build --no-cache frontend` 完成建置（容器內 `npm ci` ＋ `vite build`，
   輸出 `✓ built in 5.07s`）——那本來就是 (e) 的必要步驟，且更接近真正部署的產物。
2. **驗證 (b2) 第四條由 `grep -c "ElMessage.info"` 改為 `grep -cF "'info'"`**：實作把五個分支的
   `ElMessage({...})` 收斂成一支 `show(type, message)`（訊息含兩個絕對路徑，預設 3 秒讀不完，
   統一 `duration: 8000` ＋ `showClose`），分支 4 的字面因此是 `show('info', …)`。
3. **驗證 (c) 第二條由 `xlsxPath: res\.path` 改為 `xlsxPath: res?\.path`**：`TradingRadarView`
   的呼叫位於 `res?.message?.includes(…)` 為假的 else 分支，`res` 為 null 時仍會走到，
   寫成 `res.path` 會擲例外，故保留既有的 optional chaining。
4. **(b2) 第三條的 `0` 判準連註解都算**：初版在模組註解裡照抄了 `includes('成功')` 這個字面來解釋
   「為什麼不能這樣寫」，探針因此回 1。已把該句改寫成「整串含成功兩字」，探針回 0。
   這一點已回填進驗證段。
5. **分支 3／4 沒有實機驗證**：要走到它們必須人為破壞使用者正在用的 Drive 設定或等外部服務出錯，
   兩者都不該寫進例行驗收。改由 (b2) 的靜態探針 ＋ node 分支測試守門，已於驗證段明文記錄此缺口。
