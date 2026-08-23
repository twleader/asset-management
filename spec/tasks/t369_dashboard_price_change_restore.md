# [t369] 儀表板「股價/漲跌(%)」欄位在 `priceChange` 缺值時整包消失——改為股價與漲跌%各自獨立降級

**對應 Requirements:** Requirement 105（`getRealtimePrice` 對 live 報價採「全有或全無」判斷，導致 `priceChange` 缺值時連本來有效的 `price` 一起被丟棄，儀表板股票持股表格漲跌%整欄消失、股價數字被誤判為非 live 而顯示灰色）
**前置任務:** 無
**Liquibase changeset:** 無（純前端顯示邏輯修正，不涉及 DB／API 契約）

## 背景

使用者回報：儀表板（`DashboardView.vue`）「股票持股」卡片的「股價/漲跌(%)」欄位只顯示灰色股價數字，沒有漲跌 ▲/▼ 與百分比（尤其台股／美股／英股皆休市時最容易重現）。

**現在的錯誤行為**：`getRealtimePrice(row)`（`frontend/src/views/DashboardView.vue` 第 567～581 行）對報價物件 `p` 採「全有或全無」判斷：

```js
function getRealtimePrice(row) {
  if (!shouldApplyLive(row.market)) return null
  const key = `${row.market}_${row.stockCode}`
  const p = stockPrices.value[key]
  if (!p || p.price == null) return null
  if (!isAcceptedTodayQuote(p, marketToday(row.market))) return null
  if (p.priceChange == null) return null   // ← 這一行是根因
  return {
    price: Number(p.price),
    priceChange: Number(p.priceChange),
    changePercent: p.changePercent != null ? Number(p.changePercent) : null
  }
}
```

只要 `p.price` 有值但 `p.priceChange` 為 `null`，整個函式就 `return null`，連帶把有效的即時 `price` 一起丟棄。`p.priceChange` 為 `null` 是後端既有的、合理的保守行為（不是 bug）：
- `external-materials-service` 的 `PriceCacheWriter.buildPayload()`（`external-materials-service/src/main/java/com/steven/assets/externalmaterials/service/PriceCacheWriter.java` 第 257～288 行）在上游 provider 給不出 `previousClose`／`change` 時，`changeOrNull`／`changePctOrNull` 回 `null`。
- 台股非開盤時段，`backend` 的 `PriceQueryService.historyToLive()`（`backend/src/main/java/com/steven/assets/service/PriceQueryService.java` 第 356～366 行）查不到「前一交易日」收盤價時，`change` 同樣為 `null`。

`getRealtimePrice` 回 `null` 之後：
- `getPriceCell(row)`（第 603～618 行）落到快照後備分支，用 `row.stockPrice` 顯示股價（所以股價本身仍看得到）。
- `priceNumberColor(row)`（第 620～623 行）因 `getRealtimePrice(row)` 為 `null` 且 `isBaselineToday(row.market)` 為 `true`，判定顯示灰色（`#94a3b8`）——即使股價其實是即時的。
- 模板既有 `v-if="getPriceCell(row).priceChange != null"`（第 259 行）本來就會在 `priceChange` 為 `null` 時優雅地不渲染漲跌 ▲/▼ 與百分比，但因為上游整包被丟棄，這個分支被繞過的方式是「股價也跟著從 live 掉回快照」，不是「股價正常顯示、只有漲跌欄位單獨降級」。

**正確行為**：`price` 有值時一律回傳含 `price` 的物件；`priceChange`／`changePercent` 個別允許為 `null`。股價顯示為 live（深色），漲跌%欄位因既有的 `v-if="priceChange != null"` 判斷自然留空，不顯示 `NaN`。

## 要做什麼

- [x] 369.1 **`getRealtimePrice(row)` 拿掉整包丟棄邏輯**（`frontend/src/views/DashboardView.vue`）：移除 `if (p.priceChange == null) return null` 這一行判斷式；`priceChange`／`changePercent` 個別依原值是否為 `null` 決定回傳值是否為 `null`（沿用既有 `changePercent != null` 寫法，`priceChange` 比照同樣寫法）。函式其餘判斷（`shouldApplyLive`、`p.price == null`、`isAcceptedTodayQuote`）不動。
- [x] 369.2 **`priceNumberColor(row)` 不需改程式碼，但必須驗證邏輯正確**：其判斷式 `if (getRealtimePrice(row)) return '#1e293b'` 本身不動——369.1 修正後，`getRealtimePrice(row)` 對「有 live 股價、缺漲跌」的情境會回傳 truthy 物件，此函式自然會判定為深色，不需額外程式碼變更。任務驗收時須實測確認這一點（見「驗證」段）。
- [x] 369.3 **不得更動的既有邏輯**：模板第 259～262 行的 `v-if="getPriceCell(row).priceChange != null"` 判斷式、`getPriceCell(row)`（第 603～618 行）的快照後備分支與 `belongsToRow` 判斷、`changeColor`／`changeArrow`（第 593～600 行）一律不動。
- [x] 369.4 **Tooltip 一致性檢查**：`DashboardView.vue` 第 1280～1290 行附近（`shouldApplyLive` 判斷後呼叫 `getRealtimePrice(row)` 取即時原幣股價供 tooltip）呼叫的是同一個函式，369.1 修正後自然一致，不需額外程式碼改動；驗收時需確認表格欄位與 tooltip 顯示的股價一致（皆為深色 live 值）。
- [x] 369.5 **不改後端／BFF**：`PriceCacheWriter.buildPayload()`、`PriceQueryService.historyToLive()` 缺 `previousClose`／前一交易日收盤價時繼續回傳 `priceChange: null`，不在本任務範圍內改動；不新增／修改任何 DTO 欄位或 API 契約。
- [x] 369.6 **前端既有測試**：若 `frontend/src/utils/*.test.js` 或其他既有測試涵蓋 `displayQuote.js`（`isAcceptedTodayQuote`／`marketToday`／`mergeSseQuote`）等相關工具函式，跑一次確認未受影響（本任務未改動這些工具函式，預期全綠）；`DashboardView.vue` 目前無既有 view 元件測試前例，不新增。

## 修正後複查：AC7～AC9（第二個、實際造成使用者回報畫面的根因）

實機以 `docker exec asset-business-services curl http://localhost:8080/api/market-data/prices` 查證，休市時報價快取的 `priceChange`／`changePercent` 其實都有值（例：`0050` 的 `quoteStatus: "PREVIOUS_CLOSE"`, `priceChange: 0.85`, `changePercent: 0.818882`）。369.1 修的「`priceChange` 為 null 整包丟棄」情境根本沒發生：`isAcceptedTodayQuote()`（`frontend/src/utils/displayQuote.js`）只接受 `quoteStatus === 'LIVE'` 或 `'VERIFIED_CLOSE'`，`PREVIOUS_CLOSE` 在 `getRealtimePrice()` 一開始就被拒絕，369.1 的分支根本沒機會執行。

真正吃掉漲跌%的是 `getPriceCell(row)`（`frontend/src/views/DashboardView.vue` 第 603～618 行）快照後備分支的 `belongsToRow` 判斷：

```js
const belongsToRow = p && p.tradingDate === latest.value?.snapshotDate
```

`p.tradingDate` 是報價快取記錄的「該市場最後交易日」（如週一查詢是上週五）；`latest.value?.snapshotDate` 對最新快照而言是**今天的日曆日期**（休市當天仍會產生今日快照）。休市時這兩個日期恆不相等，`belongsToRow` 恆為 `false`，`priceChange`／`changePercent` 因此被強制設為 `null`，即使 `p` 裡明明有值——這與同函式上方既有中文註解「收盤/週末**亦可顯示**」直接矛盾。

- [x] 369.7 **修正 `getPriceCell(row)` 的 `belongsToRow` 判斷**（`frontend/src/views/DashboardView.vue` 第 611 行附近）：改為判斷「目前是否正在檢視最新快照」，不比對日期字串：
  ```js
  const isLatestSnapshotView = selectedSnapshotId.value == null || selectedSnapshotId.value === store.latestSnapshot?.id
  const belongsToRow = p && isLatestSnapshotView
  ```
  `selectedSnapshotId`（同檔案第 417 行既有 `ref`）與 `store.latestSnapshot`（`frontend/src/stores/assetStore.js:13` 既有 getter，`snapshots[0] ?? null`）皆為既有欄位，不需新增狀態、不需 import。
- [x] 369.8 **既有行為不得回歸壞掉**：選了**歷史**快照（`selectedSnapshotId` 不等於最新快照 id）時，`isLatestSnapshotView` 為 `false`，`getPriceCell` 仍只顯示凍結收盤價、不帶漲跌%——這是既有設計（避免把「目前」報價快取的漲跌誤植到歷史列），369.7 不得改變這一段行為。
- [x] 369.9 **`priceNumberColor(row)` 與 `getRealtimePrice(row)` 不受 369.7 影響**：休市時 `isAcceptedTodayQuote` 仍拒絕 `PREVIOUS_CLOSE`，`getRealtimePrice` 仍回傳 `null`，股價數字依既有邏輯仍顯示灰色（`#94a3b8`）——369.7 只讓漲跌%不再被錯誤地一併清空，不改變顏色判斷。

## 驗證

```bash
docker compose -p asset-management build --no-cache frontend
docker compose -p asset-management up -d --no-deps --force-recreate frontend
```

實機驗證（瀏覽器開啟儀表板頁面）：
1. 找一列 `stockPrices` 中 `price` 有值、`priceChange` 為 `null` 的股票（休市時段常見；若當下抓不到這種資料，改用瀏覽器 DevTools 手動在 Vue devtools 或 console 對 `stockPrices` 注入一筆 `{ price: 100, priceChange: null, changePercent: null }`），確認：
   - 「股價/漲跌(%)」欄股價數字顯示為**深色**（`#1e293b`），不是灰色。
   - 漲跌%欄位**留空**（不顯示 ▲/▼、不顯示 `NaN`、不顯示空括號 `()`）。
   - 該列 tooltip（滑鼠移到股價數字上）顯示的股價與表格欄位一致，皆為 live 值，不是舊快照收盤價。
2. 找一列 `priceChange` 正常有值的股票，確認漲跌 ▲/▼ 與百分比顯示與修改前一致（回歸不壞）。
3. 找一列非基準日（`isBaselineToday` 為否，例如選了歷史快照日期）的股票，確認股價/漲跌仍走既有快照後備分支，顯示不受本次修改影響。

若本地環境目前抓不到任何休市或 `priceChange=null` 的真實資料可供驗證，可改用 `curl -s http://localhost:8080/api/market-data/prices` 確認實際回應中是否存在 `priceChange: null` 的項目；若完全沒有，於 Vue devtools 手動注入以覆蓋此分支，並在完成報告中註明使用哪種方式驗證。

## 完成報告

實際改了 `frontend/src/views/DashboardView.vue` 兩處：

1. `getRealtimePrice(row)`（369.1）：移除 `if (p.priceChange == null) return null`，`priceChange` 改為 `p.priceChange != null ? Number(p.priceChange) : null`。
2. `getPriceCell(row)`（369.7，第二輪修正）：`belongsToRow` 的判準從 `p.tradingDate === latest.value?.snapshotDate` 改為 `isLatestSnapshotView = selectedSnapshotId.value == null || selectedSnapshotId.value === store.latestSnapshot?.id`。

驗證：`vite build` 兩輪皆成功；已用 `docker exec asset-business-services curl http://localhost:8080/api/market-data/prices` 與 `.../api/assets/latest` 實機比對確認根因（休市時 `quoteStatus=PREVIOUS_CLOSE` 的報價 `priceChange` 實際有值，但 `p.tradingDate`「上一交易日」與最新快照 `snapshotDate`「今日日曆日」恆不相等，導致漲跌%被 `belongsToRow` 誤判清空）。已跑 `arch-auditor` 兩輪查證，皆無 finding。

與原計畫的偏差：369.1（AC1-6）修正的「priceChange 缺值整包丟棄」情境經實機資料查證，實際上休市時 `priceChange` 本身有值（`quoteStatus=PREVIOUS_CLOSE` 被 `isAcceptedTodayQuote` 擋在更前面），369.1 對使用者回報的畫面沒有影響；真正根因是 369.7 修的 `belongsToRow` 日期比對。369.1 仍是合理的獨立改善（修正「有 live 報價但缺漲跌」這一情境），予以保留。因使用者第一輪驗收「沒修好」而追加 369.7-9 並經第二輪 spec-review／arch-audit 通過。

因使用者明確指示「做完要 /commit-merge-push 不需要 /run-stack」，本次收尾未重跑 `/run-stack` 實機視覺驗證，改以上述 API 層級查證取代。
