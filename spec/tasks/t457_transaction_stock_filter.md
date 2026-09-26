# [t457] 交易紀錄明細表新增「股票」篩選下拉（可複選）

**對應 Requirements:** Requirement 166（交易紀錄明細表新增「股票」篩選下拉，可複選，預設全部）
**前置任務:** 無
**Liquibase changeset:** 無（純前端功能，不涉資料庫）

## 背景

`frontend/src/views/TransactionView.vue` 的交易紀錄明細表目前只能用「年度卡片」與「市場 tab」
（`selectedYear`／`marketFilter`）兩層篩選，篩到最細仍是「某年度＋某市場」的整批列表。使用者
若只想看某一檔或某幾檔股票的買賣紀錄，得自己在整份明細裡用眼睛找。本任務在既有兩層篩選之上，
再加一層「股票」多選下拉，純前端計算、不新增任何 API。

## 要做什麼

- [ ] 457.1 **新增 `stockFilter` 狀態**：在 `<script setup>` 新增 `const stockFilter = ref([])`（陣列，元素為 `${assetCode}__${market}` 字串 key），初始為空陣列＝「全部」。
- [ ] 457.2 **新增純函式模組 `frontend/src/utils/transactionStockFilter.js`**（比照既有 `frontend/src/utils/exportSettingDraft.js` 的模式：不依賴 Vue、只操作傳入的 plain array/ref 值，供元件的 computed／watch 呼叫，也供 457.8 的 `node:test` 直接 import 測試）：
  ```js
  export function filterByYearAndMarket(summaries, selectedYear, marketFilter) {
    const byYear = selectedYear === null
      ? summaries.flatMap(s => s.records || [])
      : (summaries.find(x => x.year === selectedYear)?.records || [])
    return marketFilter ? byYear.filter(r => r.market === marketFilter) : byYear
  }

  export function stockOptionKey(assetCode, market) {
    return `${assetCode}__${market || ''}`
  }

  export function buildStockOptions(records) {
    const map = new Map()
    for (const r of records) {
      if (r.assetType !== '股票' || !r.assetCode) continue
      const key = stockOptionKey(r.assetCode, r.market)
      if (!map.has(key)) map.set(key, { value: key, label: `${r.assetName}（${r.assetCode}）` })
    }
    return Array.from(map.values()).sort((a, b) => a.label.localeCompare(b.label, 'zh-Hant'))
  }

  export function filterByStock(records, stockFilter) {
    if (!stockFilter.length) return records
    const selected = new Set(stockFilter)
    return records.filter(r =>
      r.assetType !== '股票' || selected.has(stockOptionKey(r.assetCode, r.market))
    )
  }

  export function pruneInvalidStockFilter(stockFilter, options) {
    const validKeys = new Set(options.map(o => o.value))
    const next = stockFilter.filter(v => validKeys.has(v))
    return next.length === stockFilter.length ? stockFilter : next
  }
  ```
  `frontend/src/views/TransactionView.vue` 的 `<script setup>` 從此模組 `import` `filterByYearAndMarket`／`buildStockOptions`／`filterByStock`／`pruneInvalidStockFilter` 這四個函式，取代原本內嵌在 `filteredRecords` computed 裡的邏輯（`stockOptionKey` 為模組內部輔助函式，供另兩個函式呼叫，元件不直接使用，也不需為它另立測試 bullet）。
- [ ] 457.3 **元件內改用中繼 computed**：
  ```js
  const marketYearRecords = computed(() =>
    filterByYearAndMarket(summaries.value, selectedYear.value, marketFilter.value))
  const stockOptions = computed(() => buildStockOptions(marketYearRecords.value))
  ```
  `marketYearRecords` 取代既有 `filteredRecords` computed 原本的「依 `selectedYear`／`marketFilter` 過濾」邏輯（邏輯逐字沿用，只是搬到 457.2 的純函式裡）。
- [ ] 457.4 **`filteredRecords` 改為疊加股票篩選**：
  ```js
  const filteredRecords = computed(() => filterByStock(marketYearRecords.value, stockFilter.value))
  ```
  空陣列＝全部（股票＋基金皆顯示，即現行行為）；非空時，基金列（`assetType !== '股票'`）恆通過、不受此篩選影響；股票列只保留 key 命中已選集合者。
- [ ] 457.5 **自動清除失效選取**：新增
  ```js
  watch(stockOptions, (opts) => {
    const next = pruneInvalidStockFilter(stockFilter.value, opts)
    if (next !== stockFilter.value) stockFilter.value = next
  })
  ```
  當 `selectedYear` 或 `marketFilter` 改變（或 CRUD 後 `summaries` 重新載入）導致 `stockOptions` 重新計算時，任何已選但已不在新選項清單內的 key 會被移除；若移除後變空陣列，`filteredRecords` 即回到未篩選狀態。不得整批清空（只移除確實失效的項目，仍有效的已選項要保留）。
- [ ] 457.6 **UI**：在既有 `<el-tabs v-model="marketFilter" class="market-tabs">...</el-tabs>` 區塊之後、`<el-empty>`／`<el-table>` 之前，新增：
  ```html
  <div class="stock-filter-row" v-if="stockOptions.length">
    <span class="stock-filter-label">股票：</span>
    <el-select
      v-model="stockFilter"
      multiple
      collapse-tags
      collapse-tags-tooltip
      clearable
      placeholder="全部"
      style="min-width:260px"
      size="small"
    >
      <el-option v-for="opt in stockOptions" :key="opt.value" :value="opt.value" :label="opt.label" />
    </el-select>
  </div>
  ```
  `v-if="stockOptions.length"`：目前「年度＋市場」範圍內沒有任何股票列時（例如市場 tab 只有基金交易），不渲染此下拉。對應樣式：
  ```css
  .stock-filter-row { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
  .stock-filter-label { font-size: 13px; color: #475569; white-space: nowrap; }
  ```
- [ ] 457.7 **不影響匯出與其他既有行為**：「匯出 Excel」（`handleExport`）與「排程自動匯出」沿用既有呼叫（`bffApi.transaction.exportExcel()` / `bffApi.transaction.runExportNow()` 等），不得讀取或傳遞 `stockFilter`——維持既有「涵蓋所有年度、不受頁面篩選影響」的行為。`onRowDblClick` 開啟 `StockAnalysisDialog` 的邏輯不變（篩選只影響 `el-table` 顯示哪些列，不影響雙擊行為本身）。`stockFilter` 為純頁面暫存狀態，不寫入 localStorage／不隨路由query／重新整理頁面後歸零（沿用 `selectedYear`／`marketFilter` 現行的同一種暫存慣例，不另立第二套）。
- [ ] 457.8 **測試**：新增 `frontend/src/utils/transactionStockFilter.test.js`，比照既有 `frontend/src/utils/exportSettingDraft.test.js` 的寫法（`import test from 'node:test'` + `node:assert/strict`，直接 `import` 457.2 的四個純函式，不掛載 `.vue` 元件、不使用 `@vue/test-utils`——本專案 `frontend` 未安裝該套件），以純資料覆蓋：
  - `filterByYearAndMarket`／`buildStockOptions`／`filterByStock`／`pruneInvalidStockFilter` 各自的基本情境（比照 457.2 給的實作）。
  - 預設 `stockFilter=[]` 時，`filterByStock(records, [])` 回傳與輸入完全相同的陣列內容（股票＋基金列皆在）。
  - 輸入含兩檔股票（不同 `assetCode`）與一筆基金紀錄時，`filterByStock(records, [key1])` 只剩該股票列＋基金列。
  - 同時帶兩個 key 時為聯集（兩檔股票列＋基金列皆在，另一檔未選的股票列不在）。
  - `pruneInvalidStockFilter(['A__台股', 'B__台股'], [{value:'A__台股', ...}])` 回傳 `['A__台股']`（移除失效項、保留有效項，僅測試字串陣列，不驗證任何 UI 渲染）。
  - `buildStockOptions([])` 回傳空陣列。
  - **`frontend/package.json` 的 `"test"` script 字串必須加上 `src/utils/transactionStockFilter.test.js`**，否則 `npm --prefix frontend run test` 不會執行到這支新測試（本專案 `node --test` 呼叫方式是逐檔列名，沒有 glob 自動探索，且不支援用命令列參數篩選單一檔案）。

## 驗證

```bash
node --test frontend/src/utils/transactionStockFilter.test.js
npm --prefix frontend run test
npm --prefix frontend run build
docker compose -p asset-management build --no-cache frontend
docker compose -p asset-management up -d --no-deps --force-recreate frontend
curl -s -o /dev/null -w "%{http_code}\n" http://localhost/
```

以瀏覽器（或本專案既有的 Docker 對外網域）登入後開啟「交易紀錄」頁，實測：切換年度／市場 tab 觀察下拉選項與已選狀態的連動；勾選單一股票與多檔股票觀察明細表結果；勾選後確認基金列仍照常顯示；確認「匯出 Excel」下載內容不受目前下拉選取影響（仍為全部年度全部列）。全程唯讀操作，不新增／修改／刪除任何交易紀錄。

## 完成報告

**實際改了哪些檔：**

- 新增 `frontend/src/utils/transactionStockFilter.js`：純函式模組，逐字依 457.2 給出的實作，
  匯出 `filterByYearAndMarket`／`stockOptionKey`／`buildStockOptions`／`filterByStock`／
  `pruneInvalidStockFilter`。
- 修改 `frontend/src/views/TransactionView.vue`：
  - `<script setup>` 從 `@/utils/transactionStockFilter` import 四個函式（`stockOptionKey` 為模組內部輔助函式，不 import）。
  - 新增 `const stockFilter = ref([])`。
  - 新增 `marketYearRecords`／`stockOptions` computed，`filteredRecords` 改為
    `computed(() => filterByStock(marketYearRecords.value, stockFilter.value))`，取代原本內嵌
    「依 `selectedYear`／`marketFilter` 過濾」的邏輯（邏輯逐字搬進純函式模組）。
  - 新增 `watch(stockOptions, ...)` 自動移除失效選取（`pruneInvalidStockFilter`）。
  - 在既有 `<el-tabs v-model="marketFilter" class="market-tabs">` 之後、`<el-empty>` 之前新增
    `.stock-filter-row` 下拉 UI（`v-if="stockOptions.length"`），並新增對應 `.stock-filter-row`／
    `.stock-filter-label` 樣式。
  - `handleExport`／排程自動匯出／`onRowDblClick` 皆未改動，未讀取或傳遞 `stockFilter`。
- 新增 `frontend/src/utils/transactionStockFilter.test.js`：`node:test` + `node:assert/strict`，
  14 個測試，覆蓋 457.8 列出的所有情境（含 `filterByStock([]) ` 回傳與輸入完全相同陣列、單一 key、
  雙 key 聯集、`pruneInvalidStockFilter` 移除失效項保留有效項、`buildStockOptions([])` 回空陣列等）。
- 修改 `frontend/package.json`：`"test"` script 字串在既有 14 個檔名之後追加
  `src/utils/transactionStockFilter.test.js`，其餘既有檔名順序與內容未變動。

**驗證輸出：**

```
$ node --test frontend/src/utils/transactionStockFilter.test.js
# tests 14
# pass 14
# fail 0

$ npm --prefix frontend run test
# tests 73
# pass 73
# fail 0
（含新測試檔的 14 筆；grep 確認 "test" script 指令列有帶到 src/utils/transactionStockFilter.test.js）

$ npm --prefix frontend run build
✓ built in 8.90s
（含 dist/assets/TransactionView-*.js 產出）
```

**與原計畫的偏差及原因：**

- 本 worktree 初始沒有 `frontend/node_modules`（per-worktree、不隨 git 共用），導致
  `npm --prefix frontend run test` 一開始有 3 個既有測試檔（`exportSettingDraft.test.js`／
  `tradingCalendarYearWindow.test.js`／`tradingRadarDecisionPresentation.test.js`）因
  `Cannot find package 'vue'` 而失敗——這是環境問題，與本次改動無關。執行 `npm --prefix frontend ci`
  補齊依賴後重跑，全部 73 個測試（含新增 14 個）皆綠燈。這一步不在任務檔「## 驗證」清單內，但屬於
  讓既有驗證指令能在本 worktree實際跑起來的必要前置動作，故記錄於此。
- 其餘實作、UI 與樣式均逐字比照任務檔 457.1～457.8 給出的程式碼，無其他偏差。
- 未執行任務檔「## 驗證」節後段的 `docker compose build/up` 與瀏覽器手動驗證、亦未執行
  `git commit`／merge／push——依外層協調流程指示，這些收尾與部署驗證步驟由外層另外處理。

**部署驗收（由外層 `/run-stack` 子任務完成）：**

- 本 worktree 的 `.env` 原本不存在；比對 `asset-management-main/.env` 後複製同步（權威來源以 main
  為準，且與無後綴的 `/Users/steven/Project/asset-management/.env` 比對後發現後者才是過期版）。
- `docker compose -p asset-management build --no-cache frontend` 成功（15.58s，產出
  `TransactionView-BjDvRIJM.js`）；`up -d --no-deps --force-recreate frontend` 完成，image sha256
  由 build 前基準線變為新映像，其餘 8 個容器維持 healthy、未受影響。
- `curl -sI http://localhost/` → `200 OK`；`docker exec asset-frontend grep -l "stock-filter-row"
  /usr/share/nginx/html/assets/*.js` 命中新 bundle，確認新版程式碼確實在跑。
- 瀏覽器登入後的實際 UI 互動驗證（下拉是否顯示、預設「全部」、勾選後明細表結果）**未完成**：
  此沙盒環境的瀏覽器工具導覽到 `http://localhost/` 後，Google OAuth 回 `400 redirect_uri_mismatch`
  （該環境的存取路徑與已註冊的 redirect_uri 網域不符，屬環境限制而非本次程式碼變更所致）。依安全規範
  不會代為輸入帳密完成登入。此層驗證需使用者自行登入 `http://localhost/` 開啟「交易紀錄」頁確認。
