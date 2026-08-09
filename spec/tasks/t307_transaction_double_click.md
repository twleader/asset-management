# [t307] 交易紀錄明細列雙擊開啟股票分析圖

**對應 Requirements:** Requirement 49（資產交易紀錄，明細列雙擊開啟股票分析圖 AC）
**前置任務:** 無
**Liquibase changeset:** 無

## 背景

「交易紀錄」頁（`frontend/src/views/TransactionView.vue`）的「全部交易紀錄明細」表格，目前沒有任何 row click / row dblclick 事件綁定（`<el-table>` 標籤第 44 行只有 `:data`／`size`／`stripe`／`class` 四個屬性）。使用者要求：雙擊表格中的股票列，也要比照「已實現損益」頁（`frontend/src/views/RealizedGainView.vue`）跳出共用的股票分析彈窗 `StockAnalysisDialog`（`frontend/src/components/StockAnalysisDialog.vue`），方便直接查看該股票的歷史走勢、當日分時與技術指標。

`StockAnalysisDialog` 目前已被 Dashboard／SnapshotForm／WatchStock／StockAlert／RealizedGain／TradingRadar 六個 view 共用（`spec/design.md` 第 101 行；本次併入後更新為七個），本任務讓交易紀錄成為第七個消費者，做法逐字比照 `RealizedGainView.vue` 既有實作，**不修改** `StockAnalysisDialog.vue` 本身、**不新增**任何 API 或 BFF route（唯一要動的後端相關檔案是 `StockAnalysisBffRoutes.java` 的 class Javadoc 計數文字，見 307.5，純註解、非路由邏輯）。

與已實現損益頁不同的是：交易紀錄的每一列不保證都是股票——`資產類型` 欄位值為 `股票` 或 `基金`（`TransactionView.vue` 第 416 行 `const ASSET_TYPES = ['股票', '基金']`）。且即使是股票列，`資產代號`（`assetCode`）與`市場`（`market`）在後端 entity `backend/src/main/java/com/steven/assets/model/AssetTransaction.java` 都**未加 `nullable=false`**，可能為 `null`（歷史資料或使用者未填，`spec/requirements.md` Requirement 49 既有 AC 也明文「市場（股票用；基金可空）」）。雙擊時必須先判斷「資產類型＝股票 且 代號／市場皆有值」再開窗，否則基金列、或欄位缺值的股票列雙擊會用 `undefined`／`null` 開出空白或報錯的分析圖。

## 要做什麼

- [x] 307.1 匯入共用元件：在 `frontend/src/views/TransactionView.vue` 的 `<script setup>` 區塊，於既有最後一個 import（第 413 行 `import { todayLocal } from '@/utils/localDate'`）之後新增一行：
  ```js
  import StockAnalysisDialog from '@/components/StockAnalysisDialog.vue'
  ```

- [x] 307.2 新增彈窗狀態與雙擊 handler：在 `<script setup>` 區塊的 `handleDelete` 函式（現行第 717–721 行）之後，新增：
  ```js
  // ===== 雙擊開啟股票走勢分析（僅股票列，Task 307） =====
  const analysisVisible = ref(false)
  const analysisStock = ref(null)
  function onRowDblClick(row) {
    if (row?.assetType !== '股票' || !row?.assetCode || !row?.market) return
    analysisStock.value = { stockCode: row.assetCode, stockName: row.assetName, market: row.market }
    analysisVisible.value = true
  }
  ```
  guard 條件必須同時檢查 `assetType==='股票'`、`assetCode`、`market` 三者。`RealizedGainView.vue` 對應的 `onRowDblClick`（第 726–730 行：`if (!row?.assetCode || !row?.market) return`）因整頁本來就只有股票，沒有 `assetType` 判斷；本頁**必須**多這一項，這是與已實現損益頁既有寫法唯一的差異點，不得省略。

- [x] 307.3 表格綁定雙擊事件：把 `<el-table>` 標籤（現行第 44 行）：
  ```vue
  <el-table v-else :data="filteredRecords" size="small" stripe class="tx-table">
  ```
  改為：
  ```vue
  <el-table v-else :data="filteredRecords" size="small" stripe class="tx-table"
    @row-dblclick="onRowDblClick">
  ```
  比照 `RealizedGainView.vue` 第 56–57 行 `<el-table :data="filteredRecords" size="small" stripe class="gain-table" @row-dblclick="onRowDblClick">` 的既有換行寫法。

- [x] 307.4 掛載彈窗元件：在 `<template>` 區塊中，於輸出資料夾選擇器 `<el-dialog v-model="dirPicker.visible" ...>` 的結尾 `</el-dialog>`（現行第 264 行）之後、新增/編輯用的 `<!-- 新增/編輯 dialog -->` 註解（現行第 266 行）之前，插入一個空行加：
  ```vue
  <StockAnalysisDialog v-model="analysisVisible" :stock="analysisStock" />
  ```
  比照 `RealizedGainView.vue` 第 250–254 行同樣夾在兩個 `<el-dialog>` 之間的既有位置與寫法（`v-model` 綁 `analysisVisible`、`:stock` 綁 `analysisStock`，與 307.2 新增的兩個 ref 同名）。

- [x] 307.5 同步更新 BFF 端計數 Javadoc：`bff/src/main/java/com/steven/assets/bff/stockanalysis/StockAnalysisBffRoutes.java` 第 9–21 行的 class Javadoc 目前寫「六個 view」且未列 Transaction（第 12–16 行現行內容）：
  ```java
  /**
   * StockAnalysisDialog 共用對話框專屬 BFF route。
   *
   * 此元件被 Dashboard / SnapshotForm / WatchStock / StockAlert / RealizedGain / TradingRadar
   * 六個 view 同時使用（RealizedGain 為損益明細列雙擊、TradingRadar 為個股決策表列雙擊，Task 234）。
   * 為符合 CLAUDE.md「同義欄位、同一 business service API」原則 — 六個 view 顯示
   * 同一支股票的歷史價、配息歷史、ETF 持股都應該走同一個入口 — 將其拆為獨立 BFF route，
   * 而非由六個父 view 的 BFF 各自重複代理。
   *
   * 走勢圖本身的資料不在這裡：它要把股價與技術指標兩支上游 join 起來（aggregation），
   * 由 {@link StockAnalysisChartBffController} 的 /api/bff/stock-analysis/chart-series 提供（Task 261）。
   * 本檔的五條 route 皆為精確路徑、不含萬用，不會攔截到該 controller。
   */
  ```
  改為（三處「六」→「七」＋補列 Transaction 與 Task 307）：
  ```java
  /**
   * StockAnalysisDialog 共用對話框專屬 BFF route。
   *
   * 此元件被 Dashboard / SnapshotForm / WatchStock / StockAlert / RealizedGain / TradingRadar / Transaction
   * 七個 view 同時使用（RealizedGain 為損益明細列雙擊、TradingRadar 為個股決策表列雙擊 Task 234、
   * Transaction 為交易紀錄明細列雙擊 Task 307）。
   * 為符合 CLAUDE.md「同義欄位、同一 business service API」原則 — 七個 view 顯示
   * 同一支股票的歷史價、配息歷史、ETF 持股都應該走同一個入口 — 將其拆為獨立 BFF route，
   * 而非由七個父 view 的 BFF 各自重複代理。
   *
   * 走勢圖本身的資料不在這裡：它要把股價與技術指標兩支上游 join 起來（aggregation），
   * 由 {@link StockAnalysisChartBffController} 的 /api/bff/stock-analysis/chart-series 提供（Task 261）。
   * 本檔的五條 route 皆為精確路徑、不含萬用，不會攔截到該 controller。
   */
  ```
  這是純文字計數更新（class Javadoc），不修改本檔任何路由邏輯、方法簽名或 Bean 定義。若省略這項，`design.md` 已更新的「七個 view」會與這支類別註解的「六個 view」立即產生新的計數漂移（design.md 早前已因同類疏漏被退回兩輪，不得再犯）。

- [x] 307.6 不得變動的邊界：
  - 不修改 `frontend/src/components/StockAnalysisDialog.vue`、不修改任何 `/api/bff/stock-analysis/*` BFF route 的路由邏輯或其後端實作、不新增 business 端點（**唯一例外**：307.5 指定的 `StockAnalysisBffRoutes.java` class Javadoc 計數文字）。
  - 不影響「操作」欄（現行第 115–124 行）既有的編輯／刪除按鈕行為，不加 `.stop` 攔截雙擊（比照 `RealizedGainView.vue` 操作欄現況——該欄同樣未攔截雙擊，維持全站一致行為，不在本任務單獨修正）。
  - 不影響列的既有單擊行為（年度彙總卡點選、市場 tab 切換皆維持原樣）。
  - 基金列（`assetType==='基金'`）雙擊必須完全無反應（不開空白彈窗、不噴 console error）。
  - 不因本任務調整 `<el-table>` 既有欄位、排序或篩選邏輯。

## 驗證

```bash
npm --prefix frontend run build
bash scripts/spec-check.sh
git diff --check
grep -n "七個" bff/src/main/java/com/steven/assets/bff/stockanalysis/StockAnalysisBffRoutes.java
```
最後一行的 grep 必須有輸出（確認 307.5 的 Javadoc 更新已套用，不再殘留「六個」）。

重建並 recreate frontend 容器後（本專案無 dev server，見 `.claude/skills/run-stack`）：
```bash
docker compose -p asset-management build --no-cache frontend
docker compose -p asset-management up -d --no-deps --force-recreate frontend
```
確認首頁 HTTP 200、新版 `TransactionView` 與 `StockAnalysisDialog` chunk 均存在於重建後的 frontend image。瀏覽器打開「交易紀錄」頁「全部」分頁，逐項確認：
1. 雙擊任一「資產類型＝股票」且代號/市場皆有值的列（例如 00697B、00679B、VT），應跳出 `StockAnalysisDialog` 且彈窗內股票代號／名稱與該列一致。
2. 雙擊任一「資產類型＝基金」的列，不應有任何彈窗跳出、瀏覽器 console 無錯誤。
3. 單擊「操作」欄的編輯／刪除按鈕仍正常運作（分別打開編輯表單、跳出刪除確認），未被雙擊事件影響。

## 完成報告

**實際修改的檔案**（`git status --short`）：
- `M frontend/src/views/TransactionView.vue` — 307.1（新增 import）、307.2（新增 `analysisVisible`／`analysisStock` 兩個 ref 與 `onRowDblClick` handler，含 `assetType==='股票'` 防呆）、307.3（`<el-table>` 加 `@row-dblclick`）、307.4（掛載 `<StockAnalysisDialog>`）
- `M bff/src/main/java/com/steven/assets/bff/stockanalysis/StockAnalysisBffRoutes.java` — 307.5（class Javadoc 三處「六個」→「七個」，補列 Transaction／Task 307，純註解）

`spec/requirements.md`、`spec/design.md`、`spec/tasks.md`、`CLAUDE.md`、`spec/tasks/t307_transaction_double_click.md`（本檔）為先前 spec 撰寫階段（本任務檔建立與 /spec-review 通過）已完成的既有變更，非本次實作步驟所產生，此處不重複列入「本次改動」。

**與原計畫的偏差**：無。任務檔描述的行號、程式碼片段（307.1–307.5）與實作當下的檔案現況完全吻合，逐字套用，未做任何調整。307.6 邊界逐項確認未被觸碰：`StockAnalysisDialog.vue`、BFF 路由邏輯／方法簽名／Bean 定義、操作欄編輯/刪除按鈕（未加 `.stop`）、既有單擊行為、`<el-table>` 既有欄位/排序/篩選邏輯，皆未變動（見下方 diff 核對）。

**額外處理（非 spec 變更，純本機驗證環境問題）**：此 worktree 的 `frontend/node_modules` 原本不存在（worktree 慣例不含 node_modules），`npm --prefix frontend run build` 一開始報 `vite: command not found`。確認 worktree 與主 repo 的 `frontend/package-lock.json`／`package.json` 內容逐位元組相同後，直接從主 repo `cp -R` 複製 `node_modules` 過來（未跑 `npm install`，避免網路安裝的不確定性）；`node_modules` 本就是 `.gitignore` 排除項，不影響 git 狀態、也不算入本任務的程式碼變更。

**驗證指令輸出摘要**：
1. `npm --prefix frontend run build` → 成功，exit code 0。輸出的 chunk 清單包含 `dist/assets/TransactionView-B9VuRpER.js`（28.41 kB）與 `dist/assets/StockAnalysisDialog-B56RAL9-.js`（21.83 kB），確認新的 import 關係已正確打包；僅有既有的「chunk 大小 >500kB」警告（與本次變更無關的既有現象），無 error。
2. `bash scripts/spec-check.sh` → `BLOCK: 0   CHECK: 0`（變更檔數 7，含未追蹤新檔），機械檢查通過。
3. `git diff --check` → 無輸出，exit code 0，無空白字元錯誤。
4. `grep -n "七個" bff/src/main/java/com/steven/assets/bff/stockanalysis/StockAnalysisBffRoutes.java` → 3 行命中（第 13、15、17 行），且複查 `grep -n "六個"` 已無殘留匹配，確認 Javadoc 更新已完整套用。

**尚未執行**：任務檔「驗證」段落中 docker 重建（`docker compose ... build --no-cache frontend` 與 `up -d --no-deps --force-recreate frontend`）及瀏覽器手動 3 項確認，依上游指示屬下一階段（`/run-stack`）工作範圍，本次未執行。
