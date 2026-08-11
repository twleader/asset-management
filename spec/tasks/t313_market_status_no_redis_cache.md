# [t313] market:status Redis key 描述訂正：改記載現況（即時運算、無快取）

**對應 Requirements:** Requirement 7（市場資料整合）
**前置任務:** 無
**Liquibase changeset:** 無（純文件修正，不動程式碼、不動資料庫）

## 背景

`spec/requirements.md` Requirement 7 的一條 Acceptance Criteria、`spec/design.md` 的 Redis key schema 表格與其下方
fail-safe 說明、`spec/steering/structure.md` 的 Redis key schema 表格，三份文件、四個位置皆記載同一件事：市場開
收盤狀態由 `external-materials-service` 的 `MarketClock` 每分鐘寫入 Redis key `market:status`（JSON
`{ twMarketOpen, usMarketOpen, twTime, usTime }`，TTL 90 秒），`business-services` 各 BFF 統一從這裡讀取。這個描
述可追溯到已凍結歸檔的 `spec/tasks/archive/tasks-001-050.md:1211`（Task 47.3：「新增 `PriceCacheWriter`：...
`market:status` key TTL 90s」，checkbox 標記 `[x]` 已完成）；同檔 47.7 也記載 `/market-status` 端點原規劃改打
`PriceQueryService`（Redis-backed 讀取層）。

**查證結果：這個 key 從未被實作，四處描述皆為失實**：

1. `git log --oneline --all -S'"market:status"' -- '*.java'` 對全 repo 歷史所有 `.java` 檔案搜尋此字面字串，
   零筆結果——這個 Redis key 從未出現在任何一次 commit 的 Java 原始碼中。另外拿掉引號與副檔名限制、改搜
   裸字串 `git log --oneline --all -S'market:status'`，命中三個歷史 commit（`51235799`／`ac41e329`／
   `9c28ae03`），`git show --stat` 逐一核對皆未動任何 `.java`／實作檔案（`9c28ae03` 另涉及 SDD 流程文件與
   腳本，如 `CLAUDE.md`／`scripts/spec-check.sh`／`scripts/git-hooks/`，但同樣非 `.java`）。
2. `external-materials-service/src/main/java/com/steven/assets/externalmaterials/service/MarketClock.java`
   全文 87 行，是純函式類別（`isTwMarketOpen()`／`isUsMarketOpen()`／`isUkMarketOpen()`／
   `isTwMarketJustClosed()` 等），完全沒有 `RedisTemplate` 相依、沒有 `@Scheduled` 標註，不可能有「每分鐘寫入
   Redis」的行為。它在該服務內部有多個呼叫端：`PricePoller`（第 48-92 行注入為 `clock` 欄位，開盤才呼叫
   `updatePrices`，否則 `syncClosedFromDb`）、`TaiexIndexPoller`／`EtfNavPoller`（各自抓價前的開盤閘門）、
   `IntradayTickRefresher`（開機 self-heal 閘門）、`TwRadarRefreshService`（雷達評估閘門）、
   `TradingDateResolver`（判斷 live session）、`InternalPriceController`（`/internal/health` 對內網揭露
   `twMarketOpen`/`usMarketOpen`，及 `/intraday-ticks` 的日期路由判斷）；`ClosePersister`／
   `HistoricalBackfillService`／`PriceCacheWriter` 等 dump/backfill 服務則只借用它的
   `TW_ZONE`／`US_ZONE`／`LON_ZONE` 三個時區常數。無論哪種用法，全部是記憶體內布林判斷或時區常數，
   `MarketClock` 從未對外寫任何 Redis key。
3. 實際的市場開盤狀態由 `backend/src/main/java/com/steven/assets/service/StockPriceService.java` 的
   `getMarketStatus()`（第 85-94 行）每次 API 呼叫（`MarketDataController` `GET /api/market-data/market-status`，
   `MarketDataController.java` 第 102-105 行）即時運算：呼叫 `StockPriceService` 自己的 `isTwMarketOpen()`／
   `isUsMarketOpen()`／`isUkMarketOpen()`（第 45-55 行），轉呼叫 `MarketDataService.isMarketOpenNow(market)`
   （`MarketDataService.java` 第 390-394 行：`MarketZones.isMarketOpen(market)` && `isTradingDay`），底層時區
   /時段常數是 backend 自有的 `backend/src/main/java/com/steven/assets/util/MarketZones.java`。這與
   `external-materials-service` 的 `MarketClock` 是**兩套獨立實作**（不同 Maven module、不同 deployable，架
   構上不可能互相依賴），不是同一份邏輯透過 Redis 串接的兩個入口，完全不經過 Redis。`getMarketStatus()` 回
   傳的 Map 還包含 `ukMarketOpen`／`ukTime`（英股）欄位——這兩欄已在 design.md「Requirement 24 擴充：英股
   市場類型」段落記載，但舊版 `market:status` 表格列與其 fail-safe 敘述完全沒提到英股，是另一處被本次訂
   正一併補上的落差。
4. design.md 原本在表格下方的配套敘述「`PriceQueryService` 視 Redis miss 為『未開盤』」同樣失實：
   `PriceQueryService` 實際的 Redis-miss fallback（`withStatus`／`VERIFIED_CLOSE` 判斷邏輯）是退回
   `stock_price_history` 取最近收盤、狀態來自那筆歷史紀錄自帶的 closed 語意，與「`market:status` 有沒有
   miss」完全無關——這整個 fail-safe 機制本身也不存在，一併訂正。

**為什麼判斷「現況優於原規劃」、不反過來補實作**：

- **呼叫頻次與效能**：`getMarketStatus` 有三個前端呼叫端，全部經各自 BFF proxy 至同一
  `/api/market-data/market-status`（符合「同義欄位、同一 business service API」規範）：
  `frontend/src/views/DashboardView.vue`（約第 439 行）`setInterval` 60 秒一次，程式註解原文即「marketStatus
  仍用低頻 polling（每分鐘）— 純時區判斷，不需要即時推〔原註解結尾缺字，非本次訂正引入〕」；
  `frontend/src/views/TradingCalendarView.vue`
  （約第 357-359 行）同為 60 秒一次；`frontend/src/views/SnapshotFormView.vue`（約第 2456-2459 行）為 120
  秒一次。三者加總仍是低頻。運算以記憶體內時區比對為主；台股假日表另有
  `MarketDataService.twHolidayCurrentYearCache`（10 分鐘 TTL，`MarketDataService.java` 第 176-177 行）記憶體
  快取，僅該快取到期時才透過 `priceServiceClient`（`WebClient`）對 `external-materials-service` 的
  `/internal/tw-holidays` 發一次真實網路請求（`MarketDataService.java` 第 234-244 行；美股／英股假日判斷為
  純函式，無此問題）——此頻率遠低於任何前端輪詢週期，且比原規劃的 90 秒 Redis TTL 更粗略／更省，不需要
  額外的 Redis 前擋——加這層快取反而多一道 JSON 序列化/反序列化與過期視窗要維護，划不來。
- **正規化原則衝突**：市場開盤語意其實已經以另一種形式進了 Redis——`PricePoller` 用 `MarketClock` 的布林
  值決定要不要更新該檔股價，最終體現在 `price:{market}:{code}` 的 `closed` 欄位（design.md「Redis key
  schema」表 `price:{market}:{code}` 列）。若真的補上集中式 `market:status` key，會是同一事實的第二份副
  本，違反 CLAUDE.md「相同的資料只能存一份」的正規化原則。

結論：現況（`business-services` 端即時運算、不經 Redis）優於 Task 47.3 原規劃，**不補實作**，僅訂正文件描述
以符合現況。

## 要做什麼

- [x] 313.1 `spec/requirements.md` Requirement 7：移除「市場開收盤狀態由 `external-materials-service` 維護並
      寫入 Redis（key `market:status`），各 BFF 透過 `business-services` 統一讀取」這條失實 AC，改記載：市場
      開收盤狀態由 `business-services` 的 `MarketDataService.isMarketOpenNow`（`MarketZones` 時區/時段 + 交
      易日曆含國定假日）於 API 呼叫時即時運算；`external-materials-service` 不維護此值、Redis 不快取（純記
      憶體運算，成本低於快取本身）；各 BFF 仍透過 `business-services` 的 `StockPriceService.getMarketStatus`
      統一讀取。
- [x] 313.2 `spec/design.md`「Redis key schema」表格：移除 `market:status` 列（原緊接在
      `price:dayhl:{market}:{code}:{tradingDate}` 列之後）。
- [x] 313.3 `spec/design.md` 表格下方原引用 `market:status` TTL 與 `PriceQueryService` miss 語意的 fail-safe
      說明段落，改寫為訂正說明：陳述查證方法（`git log -S`、`MarketClock.java` 內容、`getMarketStatus()`
      呼叫鏈）、實際行為（`MarketDataService.isMarketOpenNow` + `MarketZones` 即時運算，與
      `external-materials-service` 的 `MarketClock` 是獨立兩套實作）、不補實作的理由（效能無虞、與
      `price:{market}:{code}.closed` 重複違反正規化原則），並標註本次訂正對應 Task 313、原始規劃出自已凍
      結的 Task 47.3。
- [x] 313.4 `spec/steering/structure.md`「4.3 Redis Key Schema」表格：移除 `market:status` 列。
- [x] 313.5 **不修改** `spec/tasks/archive/tasks-001-050.md`——該檔已凍結，Task 47.3 的 `[x]` 記錄維持原樣，
      作為「當初規劃如此」的歷史事實，不回頭改寫歷史；訂正後的現況以本任務檔與
      requirements.md／design.md／structure.md 現行內容為準。
- [x] 313.6 不新增、不修改任何 `.java`／`.vue`／`.js` 程式碼，不新增 Liquibase changeset，不改變任何執行期
      行為——本任務純屬文件訂正，`getMarketStatus()` 的即時運算實作維持原樣不動。

## 驗證

```bash
# requirements.md／structure.md 應完全零命中——純移除，不需保留字面字串
grep -rn "market:status" spec/requirements.md spec/steering/structure.md
echo "exit=$?"   # 預期 exit=1（grep 找不到＝無輸出）

# design.md 預期恰命中一筆：訂正說明段落本身合理引用被移除的 key 名稱以解釋其未實作，非殘留
grep -n "market:status" spec/design.md
echo "exit=$?"   # 預期 exit=0，且只有這一行

# 確認 archive 歷史記錄原樣保留（本任務不動它，Task 47.3 的規劃記錄維持可追溯）
grep -n "market:status" spec/tasks/archive/tasks-001-050.md
# 預期仍命中原 47.3 那一行

# 確認沒有動到任何程式碼或資料庫變更
git status --short backend/ bff/ external-materials-service/ frontend/src/views frontend/src/router db/changelog
# 預期無輸出

# spec 機械前置檢查
bash scripts/spec-check.sh
```

## 完成報告

**實際改動的檔案**（均為純文件修正，無程式碼、無 DB 變更）：
- `spec/requirements.md`：Requirement 7 移除失實的 `market:status` AC，改記載即時運算現況。
- `spec/design.md`：Redis key schema 表格移除 `market:status` 列；下方 fail-safe 說明段落改寫為訂正說明（含
  查證方法、實際行為、不補實作理由）。
- `spec/steering/structure.md`：「4.3 Redis Key Schema」表格移除 `market:status` 列。
- `spec/tasks/archive/tasks-001-050.md`：未修改（依規範，已凍結）。
- 新增本檔 `spec/tasks/t313_market_status_no_redis_cache.md`。

**與原計畫的偏差**：無——本任務範圍即「訂正描述」，未曾規劃反向補實作 Redis 快取層（選項 C 已於查證階段
排除：多前端呼叫端加總後仍屬低頻 polling + 記憶體為主的運算，無效能問題支撐加快取層的成本）。

**驗證輸出**：
- `scripts/spec-check.sh`：`變更檔數: 4（含未追蹤新檔）`，`BLOCK: 0 CHECK: 0`，機械檢查通過。
- `/spec-review`：第一輪 `spec-auditor` 回報 0 critical／3 major／1 minor，全數為具體查證出的事實誤差（誤把
  「無外部 I/O」講絕對——台股假日快取到期時仍會對 `external-materials-service` 發一次網路請求；誤把
  Dashboard 講成唯一輪詢點——實際還有 `TradingCalendarView`／`SnapshotFormView` 兩個呼叫端；本檔驗證段落
  的 grep 判準與 design.md 訂正說明段落本身自相矛盾；一處行號誤差），均不影響「不補實作」的結論。已依建
  議修正 design.md 訂正說明段落、本檔「背景」與「驗證」兩段，修正後：
  - `grep -rn "market:status" spec/requirements.md spec/steering/structure.md` → `exit=1`（零命中）
  - `grep -n "market:status" spec/design.md` → `exit=0`，僅命中訂正說明段落本身一行
  - `grep -n "market:status" spec/tasks/archive/tasks-001-050.md` → 仍命中原 Task 47.3 記錄（未變動）
  - 重跑 `scripts/spec-check.sh` 結果不變（`BLOCK: 0 CHECK: 0`）

  第二輪（全新獨立 `spec-auditor`）回報 0 critical／1 major／3 minor：major 為 `MarketClock` 呼叫端範圍被低
  估（誤寫成「僅供 `PricePoller`」，實際還有 `TaiexIndexPoller`／`EtfNavPoller`／`IntradayTickRefresher`／
  `TwRadarRefreshService`／`TradingDateResolver`／`InternalPriceController` 六個呼叫端，且與 design.md 既有
  第 395／547／665 行內容自相矛盾）；3 個 minor 為 `MarketClock.java` 行數誤植（88→87）、歷史 commit 描述不
  夠精確、`ukMarketOpen` 誤稱「spec 從未記載」（design.md 的 Requirement 24 段落其實已記載）。全數修正，不
  影響「不補實作」的結論。

  第三輪（再一支全新獨立 `spec-auditor`，逐項重新從零查證、不採信前兩輪結論）回報 0 critical／0
  major／2 minor：兩個 minor 僅為輔助證據的精確度問題（本檔引用 `DashboardView.vue` 註解原文時多寫了原
  始碼本身缺漏的「播」字；`git log -S` 的重現步驟省略了「拿掉字面引號」這一步，讀起來像只需拿掉
  `-- '*.java'` 就能重現 3 筆 commit），均已修正；核心結論與所有查證過的事實（`market:status` 從未實作、
  `MarketClock` 呼叫端清單、三個前端輪詢端點與頻率、`twHolidayCurrentYearCache` 行為）皆獨立覆核為真，
  自我挑戰段落明確记錄「未發現需撤回本輪判定的部分」。

  三輪之後 0 critical／0 major，已達 `spec-review` skill 的完成判準，依 CLAUDE.md 記錄
  `bash .claude/hooks/spec-review-pass.sh` 通過。
