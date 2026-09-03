# [t412] Dashboard「資產配置分佈」台股/美股個股穿透的降級與揭露不足註記從未顯示（Requirement 9 bug fix）

**對應 Requirements:** Requirement 9（儀表板總覽 —「資產配置分佈」面板 tab 2「台股個股」／tab 3「美股個股」的 ETF 穿透降級註記與美股前 10 大揭露註記）
**前置任務:** 無（沿用 Task 86／t389／t390 已完成的穿透與資料同步邏輯，本任務只補前端顯示）
**Liquibase changeset:** 無

## 背景

使用者回報「資產配置分佈」圖表切到「台股個股」tab 時，元大台灣50、富邦台50等 ETF 是以整檔 ETF
當作一個圓餅切片顯示，沒有被拆解成底下的成分股。

調查後確認：**穿透演算法、BFF 聚合邏輯、資料同步排程本身都已完整實作且已驗收合併**
（`bff/src/main/java/com/steven/assets/bff/dashboard/DashboardBffController.java` 第 259–369 行
`buildLookthrough`；`backend` 側 `FubonEtfHoldingsSyncScheduler` 等）。目前環境下「整檔 ETF 當一個
切片」的直接原因是本機 `FUBON_ETF_HOLDINGS_SYNC_ENABLED` 尚未開啟、`fubon_etf_holdings_snapshot`
表無資料，屬環境/帳號啟用問題，不在本任務範圍（不改動穿透演算法本身）。

**本任務要修的是調查中額外發現、與此現象同一類的既有 bug**：Requirement 9 已明文規定 ETF 穿透
「查無成分股資料」時要「在面板底部顯示降級註記」、美股 ETF 因 Yahoo 只揭露前 10 大時要「面板底部
固定顯示註記『美股 ETF 僅揭露前 10 大成份股，其餘已計入『其它』』」，但這兩個註記在前端**從未被
接進畫面**：

- `frontend/src/views/DashboardView.vue` 第 935 行已定義
  `const twLookthroughDegraded = computed(() => twLookthrough.value?.degradedEtfs || [])`，
  但 `twLookthroughDegraded` 這個變數名稱在整份檔案中只出現這一行定義，template 完全沒有引用它。
- 第 1004 行已定義
  `const usLookthroughEtfCount = computed(() => Number(usLookthrough.value?.lookthroughEtfCount || 0))`，
  同樣只有定義、template 沒有引用。
- 第 1633–1635 行已經有對應的 CSS class 可直接沿用：
  ```css
  .lookthrough-degraded-note {
    margin-top: 6px; font-size: 11px; color: #94a3b8; text-align: center;
  }
  ```

也就是說：即使之後 `FUBON_ETF_HOLDINGS_SYNC_ENABLED` 開啟、資料同步正常，只要仍有個別 ETF 穿透失敗
（例如新上市 ETF 尚未同步到），或美股 tab 有 ETF 被穿透，使用者在畫面上依然**完全看不到任何提示**，
只會誤以為「這張圖表本來就長這樣」——這正是本次調查中觀察到的實際落差，必須修正。

**正確行為**（Requirement 9 原文）：

- tab 2「台股個股」：「ETF 成分股查詢完全失敗（查無任何成分股）時，該 ETF 才退回以自身名稱合併
  計算（避免漏算總金額），並在面板底部顯示降級註記」
- tab 3「美股個股」：「有 ETF 被成功穿透（`lookthroughEtfCount > 0`）時，面板底部固定顯示註記
  『美股 ETF 僅揭露前 10 大成份股，其餘已計入『其它』』，避免使用者對偏大的『其它』佔比困惑；美股
  部位若全為直接持股（無 ETF）則不顯示此註記」

## 要做什麼

只修 `frontend/src/views/DashboardView.vue` 的 template，不動任何 computed 邏輯、不動 BFF、
不動穿透演算法。

- [ ] 412.1 **台股 tab（`allocationTab === 'twStock'`）補上降級註記**：目前 template 第 56–66 行為：
  ```html
  <div v-else-if="allocationTab === 'twStock'">
    <div v-if="twLookthroughLoading" style="height:400px;display:flex;align-items:center;justify-content:center;color:#94a3b8">
      <el-icon class="is-loading" style="margin-right:6px"><Loading /></el-icon>
      載入台股個股穿透中…
    </div>
    <div v-else-if="!twLookthroughHasData" style="height:400px;display:flex;align-items:center;justify-content:center;color:#94a3b8">
      此快照無台股部位
    </div>
    <v-chart v-else :option="twStockPieOption" style="height: 400px; cursor: pointer" autoresize
      @click="p => onLookthroughPieClick(p, '台股')" />
  </div>
  ```
  在 `<v-chart v-else .../>` 之後、同一個 `v-else-if="allocationTab === 'twStock'"` 的 `<div>` 內，
  新增一段只在圖表實際渲染（`twLookthroughHasData` 為真）且 `twLookthroughDegraded.length > 0` 時
  顯示的註記：
  ```html
  <div v-if="twLookthroughHasData && twLookthroughDegraded.length > 0" class="lookthrough-degraded-note">
    {{ twLookthroughDegraded.length }} 檔 ETF 因故未展開成分股，已以整檔金額計入：{{ twLookthroughDegraded.map(d => d.code).join('、') }}
  </div>
  ```
  - 用 `d.code`（`TwStockLookthroughDto.DegradedEtf.code`，型別 `String`）逐檔列出代號，不逐檔顯示
    `d.message`（避免文字過長把面板撐高；`message` 欄位保留給之後要做 tooltip/detail 才用）。
  - 判斷條件必須帶 `twLookthroughHasData`，否則「此快照無台股部位」與「載入中」兩種狀態下
    `twLookthroughDegraded` 若殘留舊快照的值會誤顯示。

- [ ] 412.2 **美股 tab（`allocationTab === 'usStock'`）補上前 10 大揭露註記**：目前 template
  第 67–77 行結構與台股鏡像（`usLookthroughLoading` / `!usLookthroughHasData` / `v-chart` 三態）。
  在該區塊的 `<v-chart v-else .../>` 之後新增：
  ```html
  <div v-if="usLookthroughHasData && usLookthroughEtfCount > 0" class="lookthrough-degraded-note">
    美股 ETF 僅揭露前 10 大成份股，其餘已計入『其它』
  </div>
  ```
  - 固定文字，不帶變數，須與 Requirement 9 原文逐字一致：內層「其它」用**單書名號** `『』`
    （Unicode `U+300E`/`U+300F`），**不是**雙書名號 `「」`（`U+300C`/`U+300D`），也不是英文引號。
    複製貼上時務必核對括號字元，不要憑印象手打。
  - `usLookthroughEtfCount === 0`（美股部位全為直接持股、無 ETF）時不得顯示，沿用既有
    `usLookthroughEtfCount` computed（第 1004 行）判斷，不要新增別的旗標。

- [ ] 412.3 **不得改動的範圍**：不修改 `twLookthroughDegraded`／`usLookthroughEtfCount`／
  `twStockPieOption`／`usStockPieOption`／`loadTwStockLookthrough`／BFF／穿透演算法／
  `.env` 的 `FUBON_ETF_HOLDINGS_SYNC_ENABLED`（該項為另一件事，由使用者另行處理帳號權限升級後
  自行決定開關時機，不在本任務內以程式碼綁定）。本任務純粹是「把已經算好的既有 computed 值接進
  template」，範圍刻意收斂到只動 `<template>` 區塊。

## 驗證

前端無獨立單元測試涵蓋此 template 區塊，以實跑 stack 驗證：

```bash
docker compose -p asset-management build --no-cache frontend
docker compose -p asset-management up -d --no-deps --force-recreate frontend
curl -s -o /dev/null -w '%{http_code}\n' http://localhost/
```

實跑驗證重點（用瀏覽器打開 Dashboard 頁面，切到「資產配置分佈」面板）：

1. 切到「台股個股」tab：若目前環境 `fubon_etf_holdings_snapshot` 為空表（本次調查已確認為 0 筆），
   所有台股 ETF 都會走降級分支，此時面板底部應顯示「N 檔 ETF 因故未展開成分股，已以整檔金額計入：
   <代號清單>」。**注意**：`DashboardBffController.buildLookthrough` 對降級 ETF 仍會與其他個股
   一起依市值排序取前 10（見該檔第 337–354 行），若某檔降級 ETF 市值排名落到前 10 名之外會被併入
   「其它」聚合值，畫面上不會出現對應的獨立切片——因此 N 與「畫面上看起來像整檔 ETF 的切片數」不
   必然相等，只需確認註記本身正確顯示、且清單代號與 BFF 回傳的 `degradedEtfs` 相符即可，不必逐一
   對應到可見切片。
2. 切到「美股個股」tab：若所選快照有美股 ETF 持股（如 VOO/QQQ/SPY 等），面板底部應顯示「美股 ETF
   僅揭露前 10 大成份股，其餘已計入『其它』」；若快照的美股部位全為個股、無 ETF，則不應顯示任何
   註記。
3. 切到「資產類別」「現金/債券/股票」兩個 tab：兩個新增的 `<div class="lookthrough-degraded-note">`
   都不應出現（`v-if` 只綁定在 `twStock`／`usStock` 各自的 `v-else-if` 分支內）。
4. 瀏覽器 console 不應出現新的 Vue warning（例如引用到不存在的變數名稱）。

## 完成報告

（實作者做完後回填：實際改了哪些檔、驗證輸出、與原計畫的偏差及原因。）
