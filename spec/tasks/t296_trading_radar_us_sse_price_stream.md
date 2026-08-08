# [t296] 交易雷達 SSE 盤中即時報價比照台股邏輯擴大到美股

**對應 Requirements:** Requirement 64（交易雷達納入美股個股評分，畫面改為「台股」「美股」兩個分頁；本任務為其新增一條涵蓋 SSE 盤中即時更新的 Acceptance Criteria，見 296.1）
**前置任務:** t293（美股基本面擷取）／t294（交易雷達美股評分引擎，`TradingRadarService.assemble()` 須已產出 `market="美股"` 的 `StockDecision` 列，否則本任務的美股分支永遠比對不到任何列）／t295（台股／美股分頁 UI，`usStocks`／`currentStocks`／複合 row-key 須已存在）——三者皆已 commit 並 merge 進 main（`2542e900`）。
**Liquibase changeset:** 無（純前端，不涉資料庫、不涉後端）

## 背景

`frontend/src/views/TradingRadarView.vue` 的 `applyPriceUpdate(payload)` 函式（第 903-928 行）目前完整內容：

```js
function applyPriceUpdate(payload) {
  if (payload?.market !== '台股' || !payload.stockCode) return

  let matched = false
  const nextStocks = (radar.value.stocks || []).map(row => {
    if (row.market !== '台股' || String(row.stockCode) !== String(payload.stockCode)) return row
    matched = true
    const incoming = {
      ...row,
      ...payload,
      quoteStatus: payload.quoteStatus ?? 'LIVE',
      changePercent: payload.changePercent ?? payload.changePct ?? row.changePercent,
      priceUpdatedAt: payload.updatedAt ?? row.priceUpdatedAt
    }
    const merged = mergeSseQuote(row, incoming, marketToday('台股'))
    if (merged === row) return row
    return {
      ...row,
      ...merged
    }
  })

  if (matched) radar.value = { ...radar.value, stocks: nextStocks }
  if (!matched && String(payload.stockCode) !== '0000') return
  scheduleRecalculation()
}
```

呼叫點 `openPriceStream()`（第 930-955 行）訂閱 `EventSource('/api/market-data/prices/stream')`，收到 `price-update` 事件就丟給 `applyPriceUpdate` 解析。

**現在的錯誤行為：** 這支 SSE 端點本身完全不分市場——`external-materials-service` 的 `PricePoller.scheduledUsIntradayUpdate()`（`@Scheduled(cron = "0 0/2 9-16 * * MON-FRI", zone = "America/New_York")`）在美股盤中每 2 分鐘抓 NASDAQ 即時報價，經 `PriceCacheWriter.write()` 寫入 Redis `price:美股:{code}`（TTL 24h）並 `redis.convertAndSend("price-update", json)`；`backend` 的 `RedisSubscriberConfig` 訂閱該 channel 轉給 `PriceStreamService`，再由 `MarketDataController.streamPrices()` 的 `/api/market-data/prices/stream` 原封不動轉發給所有已連線的前端（含開著交易雷達美股分頁的瀏覽器）——**整條管線對市場沒有任何過濾**。但 `applyPriceUpdate` 開頭 `payload?.market !== '台股'` 與內部 `row.market !== '台股'` 兩處寫死，會把美股的 `price-update` 事件直接丟棄。t295 完成報告（本檔目錄下 `t295_trading_radar_market_tabs.md` 第 118 行，「與原計畫的偏差及原因」第 7 點）已明確記載這個既有限制，並標註為「已另外提出一個獨立的後續追蹤建議，未在本次變更」——本任務就是那個後續追蹤。

**正確行為：** 美股分頁（`usStocks`／`currentStocks`，`TradingRadarView.vue:821-824`）的個股在盤中收到對應的 `price-update` 事件時，應與台股分頁享有完全相同的即時體驗：立即更新該列現價／漲跌幅／行情時間，再以既有 debounce 機制背景重讀 `GET /api/bff/trading-radar` 重算 MA／KD／分數／建議。

**額外發現的連帶缺陷（非本任務新增，但修復範圍內必須一併處理）：** `mergeSseQuote(row, incoming, marketToday('台股'))` 呼叫時第三個參數寫死 `'台股'`。`mergeSseQuote` 定義於 `frontend/src/utils/displayQuote.js:30`：

```js
export function mergeSseQuote(current, incoming, today = marketToday(incoming?.market)) {
```

此函式本身已有 `today = marketToday(incoming?.market)` 的正確預設值；呼叫端手動傳入 `marketToday('台股')` 等於覆寫掉這個依市場自動選時區的預設邏輯。`marketToday`（同檔第 7-15 行）依 `MARKET_ZONES = { 台股: 'Asia/Taipei', 美股: 'America/New_York', 英股: 'Europe/London' }`（同檔第 1-5 行）換算「該市場的今天日期」，若繼續寫死 `'台股'`，即使加了美股白名單，美股報價的「是否為今日有效行情」（`mergeSseQuote` 內比對 `incoming.tradingDate !== today`）仍會誤用台灣時區換算，可能在美股交易日與台灣日期不同步的時段（例如美股夏令時間、跨日邊界）誤判為過期行情而拒絕更新。

## 要做什麼

**執行順序為強制性，不得對調：296.1（spec 修改）必須先完成並通過 `/spec-review`、以 `bash .claude/hooks/spec-review-pass.sh` 記錄通過後，才能開始 296.2 起對 `frontend/` 程式碼的修改。** 這是因為 CLAUDE.md 的 `require-spec-review` PreToolUse 閘門會在 `spec/` 變更尚未通過對抗式審查前，擋下對 `frontend/src/views/*.vue` 等實作檔的任何寫入；若先寫程式碼會直接被閘門擋下。

- [ ] 296.1 **修改 `spec/requirements.md`**：找到 Requirement 64（現行第 2493-2517 行）Acceptance Criteria 裡的這一條（逐字比對用，用於定位插入點，不修改其內容）：

  ```
  - [ ] **前端「我的台股決策」表格改為「台股」「美股」兩個分頁**：`TradingRadarView.vue` 現行單一表格改為 `el-tabs`，比照既有 `WatchStockView.vue` 的分頁模式（`marketTab` ref＋`el-tab-pane name="台股"`／`name="美股"`＋依 `market` 欄位過濾的 computed 清單）。**兩個分頁沿用完全相同的欄位、排序、互動與展開列邏輯**，不得為美股另建簡化表格；分頁標籤各自顯示檔數（沿用現行「21 檔」的顯示模式）。父層卡片標題與既有「N 檔」提示需相應改為依當前分頁計數。
  ```

  在這一條**之後**、下一條「- [ ] **通知與匯出須能區分市場別，不得混列**」**之前**，插入以下新 AC（逐字插入，不得改寫語意）：

  ```
  - [ ] **既有「盤中自動更新」SSE 邏輯（Requirement 43 既有 AC）須同樣涵蓋美股分頁的即時報價，不得只更新台股**：`price-update` 管線本身（`external-materials-service` 的 `PricePoller.scheduledUsIntradayUpdate()` 於美股盤中每 2 分鐘抓 NASDAQ 報價 → `PriceCacheWriter.write()` 寫入 `price:美股:{code}` → `redis.convertAndSend("price-update", json)`）本已對美股與台股一視同仁，`business-services` 的 `PriceStreamService`／`GET /api/market-data/prices/stream` 對 market 也未做任何過濾（見 `spec/design.md` 498-526 行「Live price push」段落）。唯一的過濾點在前端 `TradingRadarView.vue` 的 `applyPriceUpdate(payload)`：目前開頭 `if (payload?.market !== '台股' || !payload.stockCode) return`（守門）與內部逐列比對 `if (row.market !== '台股' || ...)` 兩處皆寫死只認台股，導致美股分頁（`usStocks`／`currentStocks`）的個股報價無法透過 SSE 即時反映，只能靠整頁重新載入。須改為白名單同時接受 `'台股'` 與 `'美股'`，比對邏輯沿用既有「依 market ＋ stockCode 找 `radar.value.stocks` 對應列」寫法，使美股分頁與台股分頁享有相同的「先局部更新現價 → debounce 後背景重讀 `GET /api/bff/trading-radar` 重算」行為；斷線重連、離開頁面關閉 SSE、`0000` 特殊代碼觸發大盤重算等既有規則不變。同時須修正一個既有連帶缺陷：`applyPriceUpdate` 呼叫 `mergeSseQuote(row, incoming, marketToday('台股'))` 時第三個參數寫死 `'台股'`——`mergeSseQuote`（`frontend/src/utils/displayQuote.js:30`）本身已有 `today = marketToday(incoming?.market)` 的正確預設值，呼叫端不應覆寫，改為省略第三個參數即可讓函式依 `incoming.market` 自動選對時區（`美股` → `America/New_York`），否則即使加了白名單，美股報價的「是否為今日有效行情」判斷仍會誤用台灣時區。
  ```

  改完跑 `/spec-review`（含 `scripts/spec-check.sh` 與 `spec-auditor` 對抗式查證），修完 critical／major 後用 `bash .claude/hooks/spec-review-pass.sh` 記錄通過，才能進行以下步驟。

- [ ] 296.2 **`applyPriceUpdate` 守門條件改為白名單**：第一行 `if (payload?.market !== '台股' || !payload.stockCode) return` 改為 `if (!['台股', '美股'].includes(payload?.market) || !payload.stockCode) return`。
- [ ] 296.3 **逐列比對條件同步改為依 `payload.market` 比對，不得另建第二個白名單判斷**：`if (row.market !== '台股' || String(row.stockCode) !== String(payload.stockCode)) return row` 改為 `if (row.market !== payload.market || String(row.stockCode).toUpperCase() !== String(payload.stockCode).toUpperCase()) return row`。改用 `payload.market` 而非重複寫白名單，是因為此處執行到時 `payload.market` 已經過 296.2 的白名單守門，保證只會是 `'台股'` 或 `'美股'` 其中之一，直接比對即同時涵蓋兩個市場、不必重複邏輯。`String(...).toUpperCase()` 是防禦性正規化：後端 `TradingRadarService.addTarget()`（`backend/src/main/java/com/steven/assets/service/TradingRadarService.java:665`，`String code = rawCode.trim().toUpperCase();`）組裝 `radar.value.stocks` 時已對代碼正規化為大寫，但 SSE payload 的 `stockCode` 來源（`StockSourceQuery.collectHeldStockCodes()`，`PricePoller` 呼叫端）是否同樣正規化未經確認，兩側都轉大寫可完全消除大小寫不一致導致比對失敗的風險，對台股純數字代碼（含 `00631L` 這類帶字母後綴的槓桿/反向 ETF）無副作用。
- [ ] 296.4 **移除 `mergeSseQuote` 呼叫的第三個參數**：`const merged = mergeSseQuote(row, incoming, marketToday('台股'))` 改為 `const merged = mergeSseQuote(row, incoming)`，讓函式套用自身 `today = marketToday(incoming?.market)` 的預設值，依 `incoming.market`（即 `row.market`／`payload.market`）自動選對應時區。**不得**改成 `marketToday(row.market)` 或 `marketToday(payload.market)` 手動傳入——效果雖然相同，但等於重複維護一份呼叫端邏輯，未來若 `mergeSseQuote` 的預設值邏輯調整，手動傳參的呼叫點不會跟著變、會重新出現這個 bug 類別；直接省略參數才是與函式簽章本身同步的寫法。
- [ ] 296.5 **不得修改 `openPriceStream()`、`radar` ref、`stocks`／`twStocks`／`usStocks`／`currentStocks` computed、或複合 `row-key` 綁定**：這些既有邏輯已正確支援多市場（`stocks` computed 本就不分市場、`usStocks` 已用 `market === '美股'` 過濾、row-key 已是 `` `${row.market}_${row.stockCode}` `` 複合鍵），本任務唯一要動的是 `applyPriceUpdate` 內部三處判斷（296.2-296.4），不得因為「順手」而改動其他無關程式碼。
- [ ] 296.6 **`import` 陳述式不需改動**：`import { isClosePending, marketToday, mergeSseQuote } from '@/utils/displayQuote'`（現行第 752 行）三個具名匯入本次全部沿用，`frontend/src/utils/displayQuote.js` 本身不需要任何修改（`MARKET_ZONES` 已含 `美股: 'America/New_York'`）。

## 驗證

```bash
cd frontend
/Users/steven/.nvm/versions/node/v22.21.0/bin/node ./node_modules/.bin/vite build
/Users/steven/.nvm/versions/node/v22.21.0/bin/node --test src/utils/displayQuote.test.js
cd ..
docker compose -p asset-management build --no-cache frontend
docker compose -p asset-management up -d --no-deps --force-recreate frontend
curl -s http://localhost/ -o /dev/null -w '%{http_code}\n'
```

`node --test src/utils/displayQuote.test.js` 這一步不得省略：296.4 的正確性完全依賴 `mergeSseQuote`／`marketToday` 的既有預設值行為，這支既有測試檔正好覆蓋這兩個函式，若因本次改動意外破壞其行為，這裡會先擋下來。

以及瀏覽器實測（`http://localhost/trading-radar`，切到「美股」分頁）：

1. 若目前為美股交易時段（`America/New_York` 09:30-16:00，週一至週五）且持股／觀察清單內有美股標的：開瀏覽器 devtools Network 分頁，篩選 `prices/stream`，確認能收到 `market: "美股"` 的 `price-update` 事件；同一時間畫面上對應美股列的現價／漲跌幅應立即更新，不需重新整理整頁。
2. 若非美股交易時段，改用手動模擬：先在瀏覽器 devtools 的 EventStream 分頁複製一筆現有台股 `price-update` 事件的原始 JSON 當範本，用 `docker exec -it asset-redis redis-cli PUBLISH price-update '<改成 market:"美股"、stockCode 改成畫面上美股分頁其中一檔代碼的 JSON>'` 手動送出一筆，確認美股分頁對應列即時更新、且不影響台股分頁任何一列。
3. 確認台股分頁的 SSE 行為（現價即時更新、debounce 後背景重算、斷線重連）與本次改動前完全一致，未因新增美股分支而退化。
4. 瀏覽器主控台（`read_console_messages`）無新增錯誤或警告。

## 完成報告

**實作日期：** 2026-08-09

### 實際改動檔案

- `spec/requirements.md`：Requirement 64 新增一條 AC（296.1），內容如任務檔背景與 296.1 所述，逐字比對錨點插入位置無誤，已通過獨立 `spec-auditor` 對抗式查證（0 critical／0 major／2 minor）。
- `spec/tasks/t296_trading_radar_us_sse_price_stream.md`：本檔。另依 spec-auditor 指出的 minor 修正一處引用歸屬錯誤（`collectHeldStockCodes()` 誤植為 `PricePoller` 所屬，已改回其實際定義類別 `StockSourceQuery`）。
- `frontend/src/views/TradingRadarView.vue`：`applyPriceUpdate(payload)` 三處修改（296.2-296.4）——守門條件改白名單 `!['台股', '美股'].includes(payload?.market)`；逐列比對改用 `row.market !== payload.market` 並對 `stockCode` 兩側加 `.toUpperCase()` 正規化；`mergeSseQuote(row, incoming, marketToday('台股'))` 移除第三個參數，改用函式自身依 `incoming.market` 推算時區的預設值。`openPriceStream()`、`radar`／`stocks`／`twStocks`／`usStocks`／`currentStocks`、row-key、`displayQuote` 的 import 皆未改動（296.5-296.6）。

### spec-review

已依 CLAUDE.md SDD 流程跑 `/spec-review`：`scripts/spec-check.sh` 於本次變更基準（`2542e900`）下 `BLOCK: 0 CHECK: 0`；派獨立 `spec-auditor` 逐維度查證，判定 0 critical／0 major／2 minor（一為上述引用歸屬錯誤已修正；另一為既有裸 `EventSource` 繞過一頁一 BFF 規範的既有技術債，`spec/design.md:524` 已記載並列入另立任務範圍，本次刻意不處理，理由見 spec-auditor 報告的自我挑戰段落）。已用 `bash .claude/hooks/spec-review-pass.sh` 記錄通過（雜湊 `d17002e71927`）後才開始 296.2 起的程式碼修改。

### 驗證輸出

- **Vue 語法／編譯驗證**：本 worktree 未安裝 `frontend/node_modules`（`package-lock.json` 與主 repo `frontend/` 完全一致，`diff` 無差異），臨時建立指向主 repo `frontend/node_modules` 的符號連結（唯讀使用，驗證後已移除），執行 `node ./node_modules/.bin/vite build`：`✓ built in 4.24s`，`TradingRadarView-*.js` 產出正常，無編譯錯誤或警告（僅既有的 chunk size 提醒，與本次改動無關）。
- **既有單元測試**：`node --test src/utils/displayQuote.test.js` 全數通過（4 pass／0 fail）——涵蓋 `marketToday` 依市場時區換算與 `mergeSseQuote` 的收盤保護邏輯，驗證 296.4 移除手動參數後套用的預設值行為未被破壞。
- 建置完成後已 `rm -rf dist` 並移除符號連結 `node_modules`，未留下任何建置產物或依賴目錄於本 worktree。
- Docker rebuild 與瀏覽器實測：留待 `/commit-merge-push` 合併進 main 之後，依本專案慣例（已 merge 須從 main 的 worktree build）於 main 執行，避免從本 feature worktree build 洗掉其他已 merge 的變更。

### 與原計畫的偏差及原因

1. **執行環境比預期更動態。** 動工前確認 t293/294/295 已 commit（`5e44a6ae`），但緊接著發現 `origin/main` 已進一步推進為 `2542e900`（t293-295 的 --no-ff merge commit）且之後還多了一筆與本任務無關的 GDP 圖表 commit；已先 `git merge origin/main`（fast-forward，僅帶入 `GdpTwseView.vue` 的無關變更）同步後才動工，避免在落後的基準上疊加。
2. **spec-review 進行中發現本 worktree 有其他並行活動**：完成 296.1 的 spec 修改、跑完 spec-auditor 查證並記錄通過之後，發現 `spec/requirements.md` 與本任務檔已被另一個對這個 worktree 有寫入權限的行程（commit `39d64891`）提前 commit——內容經核對與本次審查通過的版本完全一致（含逐字比對），僅缺 spec-auditor 之後才做的一處 minor 修正（`collectHeldStockCodes()` 歸屬類別），已在本次一併補上。這代表本 worktree 在執行期間並非獨佔，後續類似任務建議在動工前後都重新確認 `git log`／`git status`，不要假設兩次查看之間狀態不變。
3. **296.3 的 `.toUpperCase()` 正規化為任務檔原定範圍內的防禦性寫法，非事後新增**：已依任務檔 296.3 原文實作，未縮減也未擴大範圍。
