# [t295] 交易雷達「我的台股決策」改為「台股」「美股」兩個分頁

**對應 Requirements:** Requirement 64（交易雷達納入美股個股評分，畫面改為「台股」「美股」兩個分頁）
**前置任務:** t294（後端須先能回傳 `market="美股"` 的 `StockDecision` 列，否則本任務的美股分頁會是空的；本任務的畫面改動本身不依賴後端資料是否已含美股列，可並行開發，但驗收須等 t294 落地）
**Liquibase changeset:** 無（純前端）

## 背景

`frontend/src/views/TradingRadarView.vue` 目前「我的台股決策」卡片（`L114-432`）用單一 `<el-table :data="stocks">` 呈現全部標的，`stocks` 為 `computed(() => radar.value.stocks || [])`（`TradingRadarView.vue:788`）。卡片標頭現有文案（`TradingRadarView.vue:118-121`）：

```html
<span class="section-title">我的台股決策</span>
<span class="stock-count">{{ stocks.length }} 檔</span>
...
<span v-if="radar.skippedNonTwStocks" class="as-of">第一版未評分美／英股 {{ radar.skippedNonTwStocks }} 檔</span>
```

t294 落地後，後端 `GET /api/bff/trading-radar` 回傳的 `stocks` 陣列會同時含 `market="台股"` 與 `market="美股"` 的列（`skippedNonTwStocks` 只再統計英股）。本任務把單一表格改為「台股」「美股」兩個分頁，比照本專案既有的 `WatchStockView.vue`（`frontend/src/views/WatchStockView.vue:14-39`）分頁模式：`el-tabs` + `v-model` 綁定的 `marketTab` ref，每個 `el-tab-pane` 只放自訂 `label`（含市場圖示與檔數 `el-tag`），**表格本體只有一份、放在 `el-tabs` 外面，靠 computed 依 `marketTab` 過濾**——不得為兩個市場各寫一份表格 markup（欄位、`el-table-column`、展開列全部只維護一份，避免日後改欄位要改兩處而漂移）。

`WatchStockView.vue` 額外用了 `TaiwanMap`／`UsFlag` 兩個圖示元件（`frontend/src/components/` 底下，實際路徑以 `grep -rn "TaiwanMap\|UsFlag" frontend/src` 確認匯入路徑），可直接沿用同一組元件，不必另找圖示素材。

## 要做什麼

- [x] 295.1 **新增 `marketTab` 響應式狀態**：`const marketTab = ref('台股')`（比照 `WatchStockView.vue:157`），置於現有 `<script setup>` 區塊的響應式狀態群組附近。
- [x] 295.2 **新增依市場過濾的 computed 清單**：新增 `const twStocks = computed(() => stocks.value.filter(s => s.market === '台股'))` 與 `const usStocks = computed(() => stocks.value.filter(s => s.market === '美股'))`，以及 `const currentStocks = computed(() => marketTab.value === '美股' ? usStocks.value : twStocks.value)`（比照 `WatchStockView.vue:163-164` 的 `usList`／`ukList` 分支寫法，但本頁只有兩個分頁不必寫第三分支）。既有 `stocks` computed（`TradingRadarView.vue:788`）**不變**，作為兩個新 computed 的上游。
- [x] 295.3 **卡片標頭文案調整**：
  - 檔數提示（`TradingRadarView.vue:119`）改為顯示當前分頁的檔數：`<span class="stock-count">{{ currentStocks.length }} 檔</span>`。
  - `第一版未評分美／英股 N 檔` 提示（`TradingRadarView.vue:121`）需改字樣為只提英股（美股已評分）：`第一版未評分英股 {{ radar.skippedNonTwStocks }} 檔`；若 `radar.skippedNonTwStocks` 這個欄位在 t294 之後語意已改為「只統計英股」（依 t294 的 `addTarget` 白名單擴大，`skippedNonTw` 集合本就只再收英股等非台股非美股的市場），則本行文案與既有欄位無需改動綁定來源，只改顯示字串本身。
- [x] 295.4 **在 `<el-table>` 之前插入 `el-tabs`**：比照 `WatchStockView.vue:14-39` 的結構，插入
  ```html
  <el-tabs v-model="marketTab" style="margin-bottom:12px">
    <el-tab-pane name="台股">
      <template #label>
        <span style="display:inline-flex;align-items:center;gap:6px">
          <TaiwanMap :size="18" />
          台股 <el-tag size="small" style="margin-left:2px">{{ twStocks.length }}</el-tag>
        </span>
      </template>
    </el-tab-pane>
    <el-tab-pane name="美股">
      <template #label>
        <span style="display:inline-flex;align-items:center;gap:6px">
          <UsFlag :size="22" />
          美股 <el-tag size="small" style="margin-left:2px">{{ usStocks.length }}</el-tag>
        </span>
      </template>
    </el-tab-pane>
  </el-tabs>
  ```
  緊接在 `</template>`（`#header` 結束，`TradingRadarView.vue:123` 附近）之後、`<el-table v-if="stocks.length" :data="stocks" ...>`（`TradingRadarView.vue:125-127`）之前。若 `TaiwanMap`／`UsFlag` 元件尚未在本檔 `<script setup>` 匯入，需比照 `WatchStockView.vue` 的既有 import 陳述式新增匯入。
- [x] 295.5 **表格資料來源改綁 `currentStocks`**：`<el-table v-if="stocks.length" :data="stocks" ...>` 改為 `<el-table v-if="currentStocks.length" :data="currentStocks" ...>`（`TradingRadarView.vue:126-127`）。**表格內其餘所有 `<el-table-column>`、展開列 `template #default="{ row }"` 內容（`TradingRadarView.vue:133` 起至表格結束）一律不動，唯一例外見 295.5b**——欄位定義本就讀 `row.*`，換一份過濾後的資料來源不影響欄位邏輯。
- [x] 295.5b **基本面 coverage 分母須依市場動態顯示，不得沿用台股寫死的 `/4`**：`TradingRadarView.vue:182` 與 `:338` 兩處目前都寫死 `基本面 {{ row.fundamental.coverage ?? 0 }}/4`。t293／Requirement 64 已定案美股 `coverage` 上限為 3（不含月營收／產業因子），若這兩處分母不隨市場調整，美股即使三項基本面因子全部到齊（`coverage=3`，實質已達美股可得上限）畫面仍顯示「3/4」，會誤導使用者以為缺一項。**這兩處是 295.5「一律不動」範圍的明確例外**，改為 `{{ row.fundamental.coverage ?? 0 }}/{{ row.market === '美股' ? 3 : 4 }}`（或等效寫法，兩處都要改，不得只改其中一處）。
- [x] 295.6 **空狀態文案依分頁調整**：現行 `v-if="stocks.length"` 為假時想必有對應的 `v-else` 空狀態提示（用 `grep -n "v-else" frontend/src/views/TradingRadarView.vue` 附近確認現況），若有固定文案（如「尚無持股或觀察標的」），需改為依 `marketTab` 顯示對應市場的空狀態文案，避免使用者在美股分頁只有台股標的時看到誤導文案；若原本沒有 `v-else` 分支，本項可略過但需記錄於完成報告。
- [x] 295.7 **雙擊、通知等既有互動不得因換分頁而遺失狀態**：`@row-dblclick="onStockDblClick"`（`TradingRadarView.vue:131`）等既有事件處理沿用不動；`row-key="stockCode"` 若在台股與美股出現相同代碼字串的邊界情況（機率極低但需確認），須確認不會造成 Element Plus 表格 key 衝突——若有疑慮可將 `row-key` 改為 `row => \`${row.market}_${row.stockCode}\`` 比照 `WatchStockView.vue:42` 的既有寫法，兩個市場分頁各自渲染時天然不會同時出現同一 key，但為求穩健建議直接採用複合 key。
- [x] 295.8 **Excel 匯出表頭文案同步更新**：`TradingRadarExportService.java:119` 的大盤總覽工作表表頭字串 `"略過非台股檔數"` 語意與前端 295.3 的「第一版未評分美／英股」文案對應同一個 `skippedNonTwStocks` 欄位；t294 落地後該欄位只再統計英股，需將此表頭字串同步改為 `"略過非台股非美股檔數"` 或等效文字，避免匯出檔案與畫面文案語意不一致。
- [x] 295.9 **兌現 t294.2 交棒的「美股大盤資訊如何呈現」決定**：t294.2 把「`buildUsMarket()` 算出的美股組 regime 是否需要在畫面新增對應卡片」明確交棒給本任務決定，本項即是那個決定：**本次刻意不新增獨立的「美股大盤」卡片**（避免 `TradingRadarDto`／`MarketSummary` 需要為此新增巢狀欄位、擴大本次前端改動範圍），改為在美股分頁的 `el-tabs` 下方（或表格上方，比照現有版面）新增一行固定提示文字，例如「美股個股的大盤情境採 NASDAQ 綜合指數（IXIC）自身技術面判斷，非台股加權指數」，讓使用者知道美股分頁的買賣建議與畫面頂部既有的「台股大盤風險」卡片（`TradingRadarView.vue:23-27`，該卡片維持只顯示台股 TAIEX regime、不受本次影響）依據的是不同的大盤情境，避免誤以為兩者同源。若日後要做獨立的美股大盤卡片，須另立任務並在 `TradingRadarDto.MarketSummary` 新增對應欄位（本次不做）。
- [x] 295.10 **不得引入第二套匯出／通知邏輯**：Excel／JSON 匯出按鈕與既有通知設定入口（若在本卡片內）維持依全體 `stocks`（含台股與美股）運作，**不得**因為加了分頁而誤改為只匯出當前分頁——匯出範圍是否要跟隨分頁不在本任務範圍內，維持現行「匯出全部」行為，若使用者未來要「只匯出當前分頁」需另立任務。

## 驗證

```bash
docker compose -p asset-management build --no-cache frontend
docker compose -p asset-management up -d --no-deps --force-recreate frontend
curl -s http://localhost:5173 -o /dev/null -w '%{http_code}\n'
```

以及瀏覽器實測：開啟 `/trading-radar`，確認：
1. 「我的台股決策」卡片下方出現「台股」「美股」兩個分頁，各自標籤旁的數字與該市場實際檔數相符。
2. 切換分頁後表格內容確實只顯示對應市場的標的，欄位／排序／展開列／雙擊互動與現行台股表格完全一致。
3. 卡片標頭「N 檔」數字隨分頁切換而改變。
4. 若目前持股或觀察清單無美股標的，美股分頁應顯示恰當的空狀態而非報錯或顯示台股資料。
5. 頁面主控台（`read_console_messages`／瀏覽器開發者工具）無新增錯誤或警告。
6. 美股分頁任一標的的「基本面」欄位與展開列，分母顯示為 `/3` 而非台股既有的 `/4`。

## 完成報告

**實作日期：** 2026-08-09

### 實際改動檔案

**`frontend`（生產程式碼）**

- `frontend/src/views/TradingRadarView.vue`
  - `<script setup>`：新增 `marketTab`／`twStocks`／`usStocks`／`currentStocks` 四個響應式狀態（緊接在既有 `stocks` computed 之後），新增 `TaiwanMap`／`UsFlag` 兩個既有元件的 import（`@/components/TaiwanMap.vue`／`@/components/UsFlag.vue`，沿用 `WatchStockView.vue` 既有匯入路徑，非新建元件）。
  - `<template>`：「我的台股決策」卡頭檔數提示改綁 `currentStocks.length`；`skippedNonTwStocks` 提示文案改為「第一版未評分英股 N 檔」（僅改顯示字串，未改綁定來源，因為 t294 的 `addTarget` 白名單擴大後 `skippedNonTw` 本就只再收英股等非台股非美股市場）；`</template>`（`#header`）之後、`<el-table>` 之前插入 `el-tabs`（台股／美股兩個 `el-tab-pane`，各自 `label` 含 `TaiwanMap`／`UsFlag` 圖示與 `el-tag` 檔數），以及一則 295.9 要求的固定提示 `el-alert`（`v-if="marketTab === '美股'"`，說明美股大盤情境採 NASDAQ 綜合指數自身技術面）；`<el-table>` 的 `v-if`／`:data` 改綁 `currentStocks`，`row-key` 改為複合鍵 `row => \`${row.market}_${row.stockCode}\``；表格內其餘 `<el-table-column>`／展開列內容完全不動，僅有的兩處例外是基本面 coverage 分母（展開列一處＋主表格「基本面／產業」欄一處）由寫死的 `/4` 改為 `` /{{ row.market === '美股' ? 3 : 4 }} ``；`el-empty` 的空狀態文案改為 `` `目前沒有可分析的${marketTab}標的` ``（動態內插目前分頁名稱）。
  - `<style scoped>`：新增 `.us-market-note { margin-bottom: 14px; }`，比照同檔既有 `.stale-alert`／`.sched-note` 等 alert 間距慣例。

**`backend`（生產程式碼，僅 295.8 指定範圍）**

- `backend/src/main/java/com/steven/assets/service/TradingRadarExportService.java:119`：Excel 匯出「快照索引」工作表表頭字串 `"略過非台股檔數"` → `"略過非台股非美股檔數"`。已 grep 確認全樹無任何測試斷言這個表頭字串本身（既有測試只斷言 `skippedNonTwStocks` 的數值，不斷言表頭文字），故無需同步改動其他檔案。

**匯出／通知邏輯（295.10）：** 未修改。`openExport`／`onExport`／`openNotification`／`saveNotification` 等既有函式全部沿用不動，匯出仍以後端 Redis 快照為準（不依賴前端 `stocks`/`currentStocks`），維持「匯出全部」行為，未因加分頁而誤縮小匯出範圍。

### 驗證輸出

- **Vue 語法／編譯驗證**：本 worktree 未安裝 `frontend/node_modules`（`package-lock.json` 與主 repo `frontend/` 完全一致，`diff` 無差異），臨時建立 `node_modules` 符號連結指向主 repo 的 `frontend/node_modules`（唯讀使用、驗證後已移除），執行：
  ```
  node ./node_modules/.bin/vite build
  ```
  結果：`✓ 2400 modules transformed`、`✓ built in 4.17s`，`TradingRadarView-*.js`／`.css` 產出正常，無編譯錯誤或警告（僅既有的 chunk size 提醒，與本次改動無關）。建置完成後已 `rm -rf dist` 並移除符號連結 `node_modules`，未留下任何建置產物或依賴目錄於本 worktree。
- 未跑 `docker compose build/up` 與瀏覽器實測（依指示留給後續統一部署驗證；t293／t294 的後端亦同步留給該次驗證一併確認）。

### 架構符規查證

已派 `arch-auditor`（diff-scoped，僅餵本次變更的兩個檔案 diff：`TradingRadarView.vue`＋`TradingRadarExportService.java:119`，明確排除同 worktree 中另外兩支 agent 平行完成的 t293／t294 後端變更）。判定 **critical/major/minor 均為 0**，已用 `bash .claude/hooks/arch-review-pass.sh` 記錄通過。查證重點含：表格本體僅一份未複製 markup、未新增/繞過 BFF 呼叫、`'台股'`／`'美股'` 字串為既有 `WatchStockView.vue` 慣例延伸非新寫死業務分類、`TaiwanMap`／`UsFlag` 為既有元件重用、複合 `row-key` 合理性、後端文案變更無斷鏈呼叫端。

### 與原計畫的偏差及原因

1. **實作前被 SDD 閘門擋下，額外跑了一輪 spec-review。** 本 worktree 的 `.claude/.spec-review-state` 記錄的審查雜湊（`59daf252efac`）與動工當下的 `spec/` 實際內容雜湊（`4e9eb2bac21e`）不一致（`spec/design.md`／`requirements.md`／`steering/structure.md` 在記錄之後又被改動，另有 t293／t294 三個未追蹤任務檔），觸發 `require-spec-review.sh` PreToolUse 閘門直接擋下第一次 Edit。依 CLAUDE.md 規範，未使用 `SKIP_SPEC_GATE=1` 逃生門（該逃生門需要在 Edit 呼叫前設定行程環境變數，經實測 Bash 工具的 `export` 不會持續到後續獨立的 Bash／Edit 呼叫，技術上無法可靠使用；且用 Bash 直接寫檔繞過 Edit/Write 專屬的 hook matcher 有規避閘門精神之虞，故未採用），改為依 CLAUDE.md 記載的正規路徑走一輪完整 `/spec-review`：跑 `scripts/spec-check.sh`（0 BLOCK／0 CHECK）→ 派獨立 `spec-auditor` 查證本次 Requirement 64／t293／t294／t295 全部 spec 變更 → 抓到 2 個 major（詳見下一點）→ 修完 → `bash .claude/hooks/spec-review-pass.sh` 記錄新雜湊（`5a6a0d4a3fff`）→ 閘門解除後才開始寫 t295 的程式碼。
2. **spec-auditor 抓到的 2 個 major 已一併修正（超出 t295 原始範圍，但為解除閘門的必要前置）：**
   - `spec/tasks/t294_trading_radar_us_stock_engine.md` 的 294.1–294.8 checklist 全數維持 `- [ ]` 未勾選，但完成報告與實際程式碼（`TradingRadarService.java` 的 `US_MARKET`／`buildUsMarket`／`regimeFor`／`staleFor`／`etfPremiumPct` 短路等）皆已逐項驗證存在且行為相符，屬單純回填疏漏（與同批次 t293 的 `- [x]` 慣例不一致）。已全部改為 `- [x]`。
   - t293／t294 完成報告的 backend 測試計數互相矛盾（t293 宣稱 596、t294 宣稱 592，但兩者理應是遞增關係）。已對合併後的 working tree 重新完整跑一次 `mvn -f backend/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true test`，權威結果為 **596（BUILD SUCCESS，Failures/Errors 均為 0）**，已回填更正 t294 完成報告內的數字並附註對帳說明。
   - spec-auditor 另列 2 個 minor（`db/schema.sql` 對 `us_index_daily_history.volume`／`etf_nav_history` 已過時，與本次改動無關；`t295` 對 `WatchStockView.vue:163-164` 的行號引用有 3 行漂移）——判定不影響可實作性，依 spec-auditor 建議未動 spec，僅記錄於此供後續參考。**這兩個 minor 未修正**，其中 `db/schema.sql` 過時的問題已透過任務規劃流程另行提出追蹤。
3. **295.6 空狀態文案：確有 `v-else` 分支**（`TradingRadarView.vue` 原 434 行 `<el-empty v-else description="目前沒有可分析的台股標的">`，非任務檔背景段落所稱的臆測情境），已依分頁動態化為 `` `目前沒有可分析的${marketTab}標的` ``，非略過項目。
4. **行號小幅漂移，均以實際程式碼為準：** 任務檔引用的 `TradingRadarView.vue:119/121/126-127/182/338/788` 與 `TradingRadarExportService.java:119` 動工前重新核對全部精確命中；唯一 3 行內的漂移是任務檔引用 `WatchStockView.vue:163-164`（`usList`／`ukList` computed 實際位於 160-161 行），純參考行號、不影響本檔實作，未特別處理。
5. **row-key 採複合鍵而非維持原樣**：295.7 給了「可以改／建議改」的選擇空間，考量本頁美股分頁為新增功能、未來若代碼命名規則變動存在低機率的跨市場代碼碰撞風險，直接採用複合鍵 `` row => `${row.market}_${row.stockCode}` ``（比照 `WatchStockView.vue:42` 既有寫法），非僅止於「留意」。
6. **295.9 的提示文案未逐字沿用任務檔範例句**：任務檔給的範例是「美股個股的大盤情境採 NASDAQ 綜合指數（IXIC）自身技術面判斷，非台股加權指數」，實作採用語意相同但稍作調整的版本（標題＋說明兩段式 `el-alert`，說明段落額外點出「與上方『台股大盤風險』卡片…不同的大盤情境」），目的是更完整呼應任務檔本身「避免誤以為兩者同源」的訴求，內容決定與任務檔一致，僅文案表達方式不同。
7. **意外發現但未在本任務範圍內處理的既有限制**：`applyPriceUpdate()`（`TradingRadarView.vue`）目前開頭 `if (payload?.market !== '台股' || ...) return`，SSE 即時報價合併只認台股，美股分頁的報價目前無法透過 SSE 即時更新、只能靠整頁重新載入。這不在 t295 checklist 範圍內（checklist 未要求修改即時報價合併邏輯），已另外提出一個獨立的後續追蹤建議，未在本次變更。
