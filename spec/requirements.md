# Requirements

## Introduction

資產管理系統（Asset Management System）是一套個人財務資產追蹤與分析平台，協助使用者整合銀行存款、股票、基金等多元資產，提供即時市場資訊、歷史趨勢分析與損益報告，讓個人財務狀況一目了然。

## Requirements

### Requirement 1: 資產快照管理

**User Story:** 作為使用者，我希望能建立與管理特定日期的資產快照，以便追蹤我在不同時間點的淨資產狀況。

**Acceptance Criteria:**

- [ ] 使用者可以建立新的資產快照，並指定快照日期
- [ ] 系統自動計算該快照的總資產（存款 + 基金 + 股票）
- [ ] 使用者可以查看所有快照列表，依日期降序排列
- [ ] 使用者可以查看特定快照的明細，包含銀行存款、股票持倉、基金持倉
- [ ] 使用者可以編輯或刪除現有快照
- [ ] 同一日期僅允許一筆快照，若已存在則提示衝突
- [ ] 快照建立時可記錄當日美元匯率，用於美股換算
- [ ] 新增快照時，選擇日期後系統自動查詢並填入該日歷史匯率；日期改變時匯率隨之更新
- [ ] 編輯模式（管理資產）與新增模式行為一致：股票表格「股價/漲跌(%)」欄套用 per-market 基準日規則 —— 基準日 = 該市場時區的今日 → 顯示即時價＋漲跌，每 2 分鐘輪詢更新；其他日期 → 顯示基準日歷史收盤、不顯示漲跌。設計理念：使用者多在當日盤後建檔／微調快照，看得到當日漲跌幫助判讀；非今日的舊快照沒有 live cache，自然 fallback 為歷史收盤
- [ ] 管理資產頁上方資產彙整列（總資產 / 存款 / 台股現值 / 美股現值 / 共同基金現值 / 預估年配息）必須**內部自洽**：「總資產」一律 = 存款 + 台股 + 美股 + 基金（顯示值的直接加總），不得讀 stored `totalAssets` 後與分項並列；否則 stored 與分項來源不同步時 bar 上四項加起來會不等於總資產
- [ ] 「歷年資產管理」表格最新一筆 snapshotDate == 今日時，台股 / 美股 / 資產總計 / 增加金額 / 增幅 / 投資比例必須以 live-assets 計算結果覆蓋 stored 值，與 Dashboard 即時資產區塊顯示同一個總資產數字

### Requirement 2: 銀行存款追蹤

**User Story:** 作為使用者，我希望能記錄多個銀行帳戶的存款資訊，以便掌握各銀行的資金分配。

**Acceptance Criteria:**

- [ ] 銀行清單由系統設定動態管理，不寫死於程式碼（詳見 Requirement 11）
- [ ] 每筆存款需記錄：所屬銀行、帳戶類型、金額（台幣或美元原始金額）、台幣換算金額
- [ ] 存款類型由系統設定動態管理，不寫死於程式碼（詳見 Requirement 12）
- [ ] 系統彙總各銀行存款並計算占總資產比例
- [ ] 可視化呈現各銀行存款分佈（圓餅圖、長條圖）
- [ ] 每筆台幣／美元存款可記錄「年利率」（百分比，1.5 表示 1.5%）；在途款項不適用此欄位
- [ ] 系統依「金額 × 年利率 / 100」自動算出該筆「預估年利息」（USD 透過 `amount`（已換算 TWD）一致以台幣等值呈現），不另存於 DB
- [ ] 每筆存款的預估年利息加總後，併入快照層級的 `estimatedAnnualDividend`，與股票／基金的預估配息一起彙總
- [ ] 存款明細頁台幣 tab 底部彙總列新增「預估年利息」一欄
- [ ] Dashboard「預估年配息」卡片的殖利率分母同步納入存款本金：`殖利率 = estimatedAnnualDividend / (totalStockValue + totalFundValue + totalDeposit)`。理由：分子已含存款預估年利息，分母也須含存款本金口徑才一致（否則殖利率被高估）

### Requirement 3: 股票持倉管理

**User Story:** 作為使用者，我希望能記錄台股與美股的持倉資訊，並取得即時市價計算損益。

**Acceptance Criteria:**

- [ ] 市場類型由系統設定動態管理，不寫死於程式碼（詳見 Requirement 12）
- [ ] 每筆持倉記錄：股票代號、股票名稱、股數、成本均價、股利率
- [ ] 系統從市場 API 取得即時股價，計算當前市值
- [ ] 系統計算每筆持倉的未實現損益（現值 - 成本）
- [ ] 美股持倉以台幣換算顯示（依快照匯率）
- [ ] 券商清單由系統設定動態管理，不寫死於程式碼（詳見 Requirement 11）
- [ ] 顯示股票所屬券商
- [ ] 自動查詢並快取每支股票的現金股利率
- [ ] 每筆持倉可記錄交易類型（買/賣）與交易日期
- [ ] 美股持倉若有記錄交易日期，系統自動查詢並套用該日歷史匯率計算台幣成本；無交易日期則沿用快照匯率

### Requirement 4: 基金持倉管理

**User Story:** 作為使用者，我希望能記錄信託基金的投資狀況，以掌握基金的獲利情形。

**Acceptance Criteria:**

- [ ] 每筆基金記錄：基金名稱、投入成本、當前市值
- [ ] 系統計算基金的未實現損益
- [ ] 基金資料可在快照中新增、編輯、刪除

### Requirement 5: Excel 批次匯出

**User Story:** 作為使用者，我希望能將資料匯出成 Excel，作為備份與後續離線分析之用。

**Acceptance Criteria:**

- [x] 匯出成 `.xlsx`
- [x] 每個快照產生一張 sheet，名稱格式 YYYYMMDD
- [x] 每個 sheet 內依序顯示：頂部摘要（日期/匯率/總資產）、銀行存款區塊、基金區塊、股票區塊
- [x] 已實現損益單獨一張 sheet（資產名稱／代號／交易日期／股數／賣出均價／收帳金額／投資成本／損益／報酬率／市場／幣別／券商／匯率／年度）
- [x] 提供 `GET /api/snapshots/export`（完整匯出）與 `GET /api/realized-gains/export`（僅損益）
- [x] 前端在「資產快照」、「歷年資產」、「已實現損益」頁面提供「匯出 Excel」按鈕

> ⚠️ Excel 批次匯入功能已停用（按鈕改為匯出），原 ExcelImportService 保留但 controller 端點移除。

### Requirement 6: 已實現損益追蹤

**User Story:** 作為使用者，我希望能記錄每筆股票買賣的已實現損益，以分析年度投資績效。

**Acceptance Criteria:**

- [ ] 記錄每筆交易：股票代號、名稱、市場、損益金額、年度
- [ ] 依年度彙總損益並計算獲利率
- [ ] 列表可依年度篩選
- [ ] 支援手動新增、編輯、刪除交易記錄
- [ ] 支援匯出 Excel（見 Requirement 5；批次匯入功能已停用）

### Requirement 7: 市場資料整合

**User Story:** 作為使用者，我希望系統能自動取得市場資料，以提供即時的資產估值。

**Acceptance Criteria:**

- [ ] **股價一律是成交價、且每筆 tick 只能由真實成交推進**：系統內所有顯示的股價（盤中、收盤、走勢圖、Dashboard、管理資產、即時資產估算等所有功能）必須是真實成交過的價格，不得使用買賣中價、買價、賣價、開盤推估或任何衍生估算值。**規則僅兩條**：(1) **每天開盤，如果抓不到最新值 → 顯示昨日收盤價**（Redis 為空時 `PriceQueryService.getLive` 自然 fallback 至 `stock_price_history` 最近一筆收盤）；(2) **每次 cron tick，如果抓不到值（TWSE `z='-'` 或外部 API 查無）→ 不要更新 Redis**（`getStockPrice` 回 `Optional.empty()`、`PricePoller` skip write，保留上一輪成功 poll 寫入的真實 z）。**禁止** 用 `y`（昨日收盤）或 `o`（今日開盤）回寫 Redis — 不誠實地讓使用者誤以為這是「最新成交價」。走勢圖今日格 `getStockHistory` 仍在 `LivePrice.source` 含括號（如 `(history)`）時略過，確保拼入今日格的一定是該日真實成交價
- [ ] 從 NASDAQ API 取得美股即時股價；台股股利率改由 FinMind / TWSE BWIBBU 取得（Yahoo Finance 已停用）
- [ ] 自動偵測股票市場（先嘗試 .TW，失敗則嘗試 .TWO）
- [ ] 股價資料快取以減少外部 API 呼叫次數
- [ ] 從央行或第三方 API 取得美元/台幣歷史匯率
- [ ] 交易日曆顯示台股與美股的開收盤狀態
- [ ] 台股（週一～五 09:00～13:30 Asia/Taipei）與美股（週一～五 09:30～16:00 America/New_York，含 EST/EDT 夏令切換）採**獨立 cron 排程**，各自每 2 分鐘更新一次股價快取；非該市場交易時段不空轉、不混用單一 fixedRate
- [ ] 收盤時（台股 13:30、美股 16:00）將當日收盤價寫入 `stock_price_history`
- [ ] **`stock_price_history` 的「今日列」（市場時區當日）只能由 `ClosePersister` 在收盤後路徑寫入**（13:32 TW Redis dump / 16:02 ET Redis dump / 16:00 TW FinMind verify / 18:00 ET FinMind verify / `selfHealMissedClose` 已守門過收盤時點）。`HistoricalBackfillService` 任何路徑（`startupBackfill`、`/internal/backfill/stock`、`/internal/backfill/all`、`SnapshotFormBffController.triggerBackfillThenRefetch` 觸發等）**禁止寫入該市場當日 row**：即使外部 API（Yahoo Finance `interval=1d`）在盤中也會回傳一根「今日 partial bar」（open/high/low + 此刻 last trade 當 close），會被前端誤當作收盤、與 Redis 即時 tick 脫鉤。實作上由 `HistoricalBackfillService` 在 for-loop 跳過 `bar.tradingDate() == LocalDate.now(market TZ)` 的 bar
- [ ] FinMind 盤後校正（台股 16:00、美股 18:00）覆寫 `stock_price_history` 時，必須同步覆寫 Redis `price:{market}:{code}` cache，並 PUBLISH `price-update` 給 SSE 訂閱者。動機：盤中最後一輪 cron 通常落在收盤前 2 分鐘（13:28 / 15:58），抓到的是 last tick 而非集合競價收盤；若只更新 DB，Dashboard / SnapshotForm 在 `basedate == 今日` 時讀 Redis 仍看到 last tick，與「歷年資產管理」（讀 DB）顯示的收盤對不起來
- [ ] 歷史收盤價保存至少 10 年（透過 FinMind / TWSE 回補；Yahoo Finance 已停用）
- [ ] 抓價子系統獨立為 `external-materials-service` 微服務（獨立 image / container），盤中 2 分鐘 cron 將 live 價寫入 Redis（key `price:{market}:{code}`，TTL 24 小時 — 確保流動性低的 ETF / 個股 z='-' 連續多輪後該值仍持續活著，不會 TTL 過期被退回昨收），盤後將收盤價寫入 `stock_price_history`
- [ ] `business-services` 不再直接呼叫外部行情 API；live 股價一律先讀 Redis、miss 則 fallback 至 `stock_price_history` 最近一筆收盤；歷史收盤價直接讀 DB
- [ ] 市場開收盤狀態由 `external-materials-service` 維護並寫入 Redis（key `market:status`），各 BFF 透過 `business-services` 統一讀取
- [ ] `POST /api/market-data/prices/refresh` 改由 `business-services` 內部呼叫 `external-materials-service` 的觸發端點，價格刷新後再從 Redis 回讀
- [ ] 股利歷史（FinMind TaiwanStockDividend）每日由 cron 同步寫入 `stock_dividend_history`；`/api/market-data/dividends` 端點直接讀 DB，不再每次開啟對話框都打 FinMind
- [ ] 凡列入 `stock` 主檔的股票（含曾持有、觀察清單、設有警示）皆自動納入 10 年歷史收盤價回補與每日排程更新範圍；新增觀察股票時即同步寫入 `stock` 主檔
- [ ] 提供 `GET /api/market-data/live-assets` 端點：以最新快照持倉 × 當前快取股價，即時計算總資產估值

### Requirement 8: 資產歷史趨勢分析

**User Story:** 作為使用者，我希望能查看資產隨時間的變化趨勢，以評估整體財務成長。

**Acceptance Criteria:**

- [ ] 以折線圖呈現總資產歷史變化
- [ ] 計算相鄰期間的資產變化量與變化率
- [ ] 顯示投資資產占總資產的比例趨勢
- [ ] 支援依時間範圍篩選歷史資料

### Requirement 9: 儀表板總覽

**User Story:** 作為使用者，我希望在進入系統後能立即看到最新的資產總覽，以快速掌握財務狀況。

**Acceptance Criteria:**

- [ ] 顯示最新快照的總資產、存款、投資金額等 KPI 卡片
- [ ] 圓餅圖呈現資產類別分配，分 5 區：台幣存款、美元存款、台股、美股、信託基金。當 hover 右側趨勢圖某時間點時，圓餅圖即時切換顯示該時間點的分配；未 hover 時顯示最新／選中快照。卡片副標題同步顯示對應日期。值為 0 的區段自動隱藏。資料來源：
  - 最新／選中快照：存款拆分依前端 `bankSummary`（`BankDeposit.currency` USD vs TWD/null，TRANSIT_TWD/TRANSIT_USD 各歸對應幣別），股票拆分依 `liveLatest.totalTwStockValue / totalUsStockValue`
  - 歷史時間點：`AssetHistoryResponse` 預先帶 `totalTwdDeposit / totalUsdDeposit / totalTwStockValue / totalUsStockValue / totalFundValue`，由後端 `getAssetHistory()` 聚合（amount 已是台幣等值）
- [ ] 顯示台股與美股的持倉市值
- [ ] 「歷年資產管理」(`/asset-history`) 表格與兩張圖（總資產趨勢、堆疊長條）的「存款」欄位拆成「台幣存款」、「美元存款」兩欄／兩條線／兩堆疊段；資料來源 `AssetHistoryResponse.totalTwdDeposit / totalUsdDeposit`
- [ ] 資產歷史趨勢線圖簡化為 3 條線：總資產、存款、投資（投資 = `totalFundValue + totalStockValue`）；不再分顯示基金 / 台股 / 美股（細分留給資產配置圓餅圖）
- [ ] 點擊指定股票時，能顯示該股價走勢，包含月線、季線、年線
- [ ] 顯示預估年化股利收入
- [ ] 每個 KPI 卡片顯示相對上一期的變化量
- [ ] 顯示「即時資產估算」區塊：以最新股價計算當前總資產，交易時間內每 2 分鐘自動刷新，收盤後顯示收盤估值
- [ ] 股票持股表格的「股價/漲跌(%)」欄需依右上角基準日（所選快照的 snapshotDate）**per-market** 判斷：
  - 台股：`基準日 == LocalDate.now(Asia/Taipei)` → 顯示即時股價＋漲跌金額＋漲跌%（格式：`▲$X.XX (Y.YY%)`），每 2 分鐘自動更新，資料來源與「觀察股票」一致
  - 美股：`基準日 == LocalDate.now(America/New_York)` → 顯示即時股價＋漲跌金額＋漲跌%（EST/EDT 由 JVM `ZoneId` 自動處理）。設計目的：使用者多在 TW 收盤後建快照，TW 過午夜後若以 TW 今日比對會把美股盤中（TW 凌晨）誤判為非交易時間
  - 其他情形：顯示該快照保存的收盤價（`snapshotStockHolding.stockPrice`），不顯示漲跌金額/百分比、不參與輪詢更新
- [ ] 切換基準日後，KPI 卡片、資產配置圓餅圖、銀行存款圖、持股圖與股票持股表格都需跟著基準日（所選快照）變動；不可被 2 分鐘的價格輪詢覆蓋回「最新快照」
- [ ] 資產歷史趨勢圖需以基準日為終點：只顯示 `snapshotDate <= 基準日` 的歷史點；KPI「較上次」變化率亦以此篩選後的前一筆比較
- [ ] 股票持股表格下方顯示該市場小計：目前總值、投資成本、損益（含 %）、預估配息、持股數，與管理資產（SnapshotForm）的市場小計欄位一致
- [ ] 基準日 == 今日（市場時區）時，2 分鐘輪詢回傳的 live price 需即時驅動持股表的「現值 / 損益 / 預估配息」、KPI「資產總計／股票現值／預估年配息」、以及下方市場小計重算（依 `shares × livePrice ×（美股）匯率`），不可只更新股價欄位而值欄位停留在快照儲存值。**不得加上「市場開盤」額外閘門**：該市場收盤後 Redis 仍保留當日最後一筆成交價（前一交易日收盤），KPI 必須使用該值，才能與「歷年資產管理」今日列（透過 `/api/market-data/live-assets`）顯示相同總資產數字
- [ ] Dashboard 的 `/api/bff/dashboard/realtime` 每次輪詢需先 trigger 後端 `/api/market-data/prices/refresh` 主動向 Yahoo 拉最新行情，再回傳；不可僅讀取 cache（與 SnapshotForm `/realtime` 一致，避免後端 cron 漏跑時前端看到舊值）
- [ ] 顯示「信託基金」橫向長條圖（與「持股明細」並列），y 軸基金名稱、x 軸現值，依現值升冪排序，bar 顏色依損益正負（賺綠、賠紅），下方顯示總值 / 成本 / 損益（含 %）小計；資料來源 `latestSnapshotDetail.funds`，無 BFF 額外彙總（基金值已存於快照，不隨輪詢跳動）；無基金資料時卡片仍顯示，內容為「尚無基金資料」
- [ ] KPI 卡片列加入「信託基金」一張（共 5 張：資產總計 / 存款總計 / 信託基金 / 股票現值 / 預估年配息），改用 flex 平均分配寬度。`sub` 顯示「損益 ${...}」並依正負上色（綠/紅）
- [ ] 「資產配置分佈」面板提供 tab 切換：
  - tab 1「資產類別」：維持原 6 區圓餅圖（台幣/美元存款、台股、美股、英股、信託基金）
  - tab 2「台股個股」：顯示台股部位穿透 ETF 後的前 10 大個股 + 1 個「其它」segment 圓餅圖
  - 穿透規則：台股 ETF（`stockCode` 以 `00` 開頭）依成分股權重**正規化**分配整筆 `currentValue`（`cv × weight / Σweight`），使每檔 ETF 完全穿透成個股、不殘留「ETF 自身」slice（成分股來源常只揭露前 N 大未滿 100%，正規化後未揭露尾段按已揭露分布等比攤入）；直接持股不拆解；同檔個股一律合併（不同 ETF 持有的同檔 + 直接持有的同檔）。成分股來源 MoneyDJ 只揭露股名無代號，故以**股名**為聚合鍵（圓餅圖亦以股名顯示）
  - ETF 成分股查詢**完全失敗**（查無任何成分股）時，該 ETF 才退回以自身名稱合併計算（避免漏算總金額），並在面板底部顯示降級註記
  - 圓餅圖環標籤/圖例只顯示股名 + 佔比（`{b}\n{d}%`）；hover tooltip 顯示「股票代號 股名」+ 金額 + 佔比
  - 成分股代號補齊：MoneyDJ 只給股名，ext-materials 以記憶體「股名→代號」字典（TWSE `STOCK_DAY_ALL` 上市 + TPEX 上櫃，24h cache）補上代號；**不**寫入 `stock` 主檔（該表是 `StockSourceQuery.collectAllStockCodes` 抓價排程清單，灌入全市場會造成排程爆量）
  - 切到 tab 2 才向 BFF lazy fetch（避免拖慢 `/summary` 首屏）；同一 snapshot 切回再切回時用前端 cache，不重打
  - 切換基準日（snapshot）時 tab 2 重新 fetch 對應 snapshot 的穿透結果
  - hover 右側趨勢圖某時間點時，tab 2 圓餅圖亦同步切換顯示該時間點對應 snapshot 的穿透結果（與 tab 1「資產類別」一致的 hover 連動行為）；卡片副標題日期同步切換；未 hover 時回到所選快照。hover 對應的 snapshot 命中前端 cache 時即時切換、未命中則 lazy fetch（race 防護：fetch 回來時若目前的 effective snapshot 已不是該筆，不覆寫畫面）

### Requirement 10: 匯率歷史查詢

**User Story:** 作為使用者，我希望能查看美元/台幣的歷史匯率走勢，以了解匯率對資產的影響。

**Acceptance Criteria:**

- [ ] 以折線圖呈現 USD/TWD 歷史匯率（含買入、中間、賣出價）
- [ ] 支援時間範圍選擇
- [ ] 系統定期或按需取得最新匯率資料並儲存歷史紀錄
- [ ] 支援手動補齊指定日期起的歷史匯率（`POST /api/market-data/exchange-rate/backfill-history`）
- [ ] 歷史匯率至少保存 10 年

---

### Requirement 11: 銀行與券商設定管理

**User Story:** 作為使用者，我希望能在系統介面中自由新增、編輯、停用銀行與券商，而不需要修改程式碼或重新部署，以因應未來金融機構的異動。

**Acceptance Criteria:**

- [x] 銀行與券商清單儲存於資料庫，不以 Enum 寫死
- [x] 使用者可新增銀行，欄位包含：名稱（顯示用）、識別代碼（唯一）、Excel 匯入關鍵字（可多組）
- [x] 使用者可新增券商，欄位包含：名稱（顯示用）、識別代碼（唯一）、Excel 匯入關鍵字（可多組）
- [x] 使用者可編輯現有銀行與券商的名稱、關鍵字
- [x] 使用者可停用銀行或券商（軟刪除），停用後不出現於新增存款/股票的下拉選單，但歷史資料仍正常顯示
- [x] Excel 匯入時依資料庫中設定的關鍵字動態比對銀行與券商，無需修改程式碼即可支援新機構
- [x] 系統提供預設初始資料（Seed Data）：富邦、國泰、台新、LINE Bank、永豐、元大、華南等常見銀行，以及富邦、國泰、元大、華南等常見券商

---

### Requirement 12: 存款類型與市場類型設定管理

**User Story:** 作為使用者，我希望存款類型（活存、定存等）與市場類型（台股、美股等）也能由資料庫管理，不寫死於程式碼，以便未來靈活調整分類。

**Acceptance Criteria:**

- [x] 存款類型與市場類型各自有獨立的資料表，不以 Enum 寫死
- [x] 每個類型有識別代碼（code，同時作為存入資料庫的值）、顯示名稱、顯示排序、啟用狀態
- [x] 使用者可新增、編輯、停用存款類型與市場類型
- [x] 停用後不出現於新增表單的下拉選單，但歷史資料仍正常顯示
- [x] 系統提供預設 Seed Data：6 種存款類型（活存/定存/美元活存/美元定存/證券戶/信用卡待付款）、3 種市場類型（台股/美股/英股）
- [x] 前端新增快照的存款類型下拉選單動態從 API 載入，不寫死


---

### Requirement 13: 股票走勢圖延伸資訊（ETF 持股明細與股利歷史）

**User Story:** 作為使用者，我希望在雙擊開啟的股票走勢圖 popup 裡，除了看到 K 線與技術指標，還能快速檢視「ETF 成分持股」以及「最近 10 年的股利發放」，以便做配置與殖利率評估。

**Acceptance Criteria:**

- [ ] Popup 以頁籤（Tabs）呈現：走勢圖（預設）、持股明細（僅 ETF）、股利歷史
- [ ] 走勢圖期間按鈕第一個項目為「當日」，顯示最後一個交易日的盤中分時走勢。月線 MA20 / 季線 MA60 / 年線 MA240 與 K / D 仍要顯示，但 intraday tick 數無法支撐日線級指標重新計算 → 改以「該股最近一個交易日收盤後算出的日線最新值」畫成水平參考線（與其他期間同口徑，避免使用者在切回日線期間時看到不同 MA 值）
- [ ] 「當日」分時資料採兩階段：(1) **盤中**由 `external-materials-service` 既有 `PricePoller` 每 2 分鐘輪詢時，將真實成交 tick（時間 + 成交價）`RPUSH` 到 Redis LIST `price:ticks:{market}:{code}:{tradingDate}`，TTL 36h。**寫入守門條件**：(a) `source` 不含括號（排除 `(history)` / `(前收)` / `(買賣中價)` 等非實際成交值，與 `HistoricalDataService.getStockHistory` 對今日格條件一致）；(b) `markClosed=false`（排除 cache warming 對盤外時段抓到的「最新可得值」回灌污染分時序列）。前端切到「當日」即時讀此 LIST 看到當下累積結果
- [ ] **盤後**由 `IntradayTickRefresher` cron（台股 13:35 TW、美股 16:05 ET、英股 16:35 LON，較 `ClosePersister` dump 晚 3 分鐘）抓外部完整當日資料 `DEL` + `RPUSH` 覆寫該日 LIST，補回盤中 polling 因 `z='-'` 跳號漏掉的時間點。資料源優先序：
  - 台股：FinMind `TaiwanStockKBar` 5 分鐘 K 線（需 sponsor token；`finmind.token` 未設定時 FinMind 回 400 → fallback）
  - 台股 fallback / 美股 / 英股：Yahoo `chart?interval=5m&range=1d`（既有 `PriceFetchClient.fetchIntraday5m`）
- [ ] **Cold start**：使用者切到「當日」時，BFF endpoint 看 Redis LIST 為空，同步觸發一次 `IntradayTickRefresher.refreshOne` 抓外部資料寫入後再回傳；之後切換即從 Redis 命中
- [ ] 非 ETF 個股不顯示「持股明細」頁籤（台股 00 開頭者視為 ETF；美股採白名單：VOO / VT / AVGO 等已有持倉的 ETF）
- [ ] 持股明細頁籤顯示 ETF 成分股清單（名稱 / 持股比例 / 股數）。台股 ETF 資料來源優先順序：(1) **MoneyDJ** 持股明細頁 `Basic0007a.xdjhtm?etfid={code}.TW`（完整成分股，非僅前 10；只揭露股名無代號）；(2) Yahoo `quoteSummary?modules=topHoldings`（前 10 大）為 fallback；(3) FinMind `TaiwanETFHoldings` dataset 已於 2026 年被 FinMind 移除（回 422），僅向下相容。美股 ETF 走 Yahoo；不支援則顯示「尚未支援該市場」。結果含 12h in-memory cache（成分股每日至多變動一次，避免重複外呼）
- [ ] MoneyDJ / Yahoo 一律以 `curl` 子程序抓取（非 Java HttpClient）：站方 WAF 會依 TLS/HTTP 指紋辨識 Java HttpClient 並回 `429 Too Many Requests`（同容器、同 IP 的 curl 卻正常）。Yahoo crumb 流程：`curl -c <cookie> https://fc.yahoo.com`（回 404 但 Set-Cookie: A1/A3）→ `curl -b <cookie> /v1/test/getcrumb` 取 crumb → `curl -b <cookie> quoteSummary?...&crumb=`
- [ ] 股利歷史頁籤顯示最近 10 年股利：年度 / 每股現金股利 / 每股股票股利 / 現金殖利率 / 除息日昨收價 / 除息日 / 發放日 / 填息天數
- [ ] 「除息日昨收價」=該檔股票在除息日前一個交易日的收盤價（取自 `StockPriceHistory`），無資料時顯示「—」
- [ ] 「現金殖利率」每筆事件 = 現金股利 / 除息日昨收價 × 100%（無昨收價時顯示「—」）
- [ ] 表格除了顯示每次除息事件外，還需在每年事件之上插入該年度小計列（年度／合計現金股利／合計股票股利／年度殖利率），年度小計列以較深背景與粗體區隔；年度殖利率以「該年合計現金股利 / 該年最近一次除息事件的昨收價」計算
- [ ] 台股股利資料來源 FinMind `TaiwanStockDividend`；美股股利資料來源 NASDAQ `/api/quote/{code}/dividends`
- [ ] 後端呼叫 FinMind 時若環境變數 `FINMIND_TOKEN` 有值，需在 HTTP request header 帶 `Authorization: Bearer <token>`，避免匿名額度耗盡時被回 402 Payment Required；未設定時保持匿名呼叫向下相容
- [ ] 資料查無或來源失敗時顯示友善提示，不拋例外

---

### Requirement 14: 觀察股票清單（由警示條件衍生）

**User Story:** 作為使用者，我希望「觀察清單」即「警示條件」中出現過的去重股票清單，這樣在警示條件設了多筆 0050 條件時，觀察清單就會自動有一筆 0050；不必同時維護兩份名單。

**Acceptance Criteria:**

- [ ] 左側導覽選單「股票觀察」項目，路徑 `/stocks`（內含「觀察清單」、「警示條件」兩個頁籤；舊路徑 `/watch-stocks`、`/stock-alerts` 自動 redirect 並帶 `tab` query）
- [ ] 頁面提供「台股」、「美股」兩個頁籤，依市場分流顯示
- [ ] **觀察清單不另存資料表**：`watch_stock` 表廢止；觀察清單一律由 `stock_alert` 群組去重衍生 = `SELECT stockCode, market, MIN(displayOrder) FROM stock_alert GROUP BY stockCode, market`。同一檔股票即使有多筆條件也僅顯示一列
- [ ] 每列顯示欄位：股名/股號、股價、漲跌、漲跌幅(%)、開盤、昨收、最高、最低、成交量(張)、警示條件（該股票所有 alert 條件 label 清單）、警示（最近一次觸發資訊：觸發時間/股價/均線/KD）
- [ ] 「警示條件」欄列出該 (stockCode, market) 在 `stock_alert` 中所有條件（依 displayOrder 升冪），每條顯示其 condition label（如「高於季線 5%」「K 值低於 20」）；停用條件以淺色 + 「(停用)」標註；**已觸發**（該條 alert 的 `lastTriggeredAt` 在最近 3 個交易日內）的條件以紅字顯示，與「警示」欄的觸發判定共用同一份 cutoff
- [ ] 「警示」欄取自該股票所有 alert 中最近一筆 `lastTriggeredAt`，呈現觸發時間、觸發價、均線值、KD 值（最近 3 個交易日內才顯示，過期不顯示）
- [ ] 報價來源 `StockPrice` 擴充欄位：buyPrice（買進）、sellPrice（賣出）、openPrice（開盤）、previousClose（昨收）、highPrice（最高）、lowPrice（最低）、volume（成交量，台股為張）
- [ ] 觀察清單中的股票同樣納入排程的股價更新（與持股一併更新）
- [ ] **新增觀察的入口 = 新增警示條件**：觀察清單頁的「新增」按鈕直接開啟警示條件新增表單；至少必須設一筆條件，建立成功後該股票自動出現在觀察清單
- [ ] **移除觀察的入口 = 警示條件頁刪除**：觀察清單頁**不提供**列級的刪除操作（無「操作」欄）；要把某股票從觀察清單移除，只能在「警示條件」頁刪除該 (stockCode, market) 所有 alert，列才會消失。此設計反映「觀察清單即 stock_alert 衍生 view」單一資料源語意，避免在兩個頁面提供等價但措辭不同的刪除入口
- [ ] **觀察清單拖曳排序 = 移動該股票所有 alert**：拖曳一列時將該股票所有 alert 的 `displayOrder` 整組重排到新位置（保持條件之間的相對順序）；警示條件頁的拖曳維持單筆 alert 級行為
- [ ] 盤中即時 `highPrice` / `lowPrice` 由 `external-materials-service` 自行聚合：每輪 cron 觀察到的成交價與當日已記錄的高/低做 max/min，存於 Redis（key `price:dayhl:{market}:{code}:{tradingDate}`，TTL 36 小時）。寫入 `price:{market}:{code}` 時，若外部 API 有提供 high/low 則取「外部值與聚合值的 max(high)/min(low)」；若外部 API 未提供（如 NASDAQ 對 ETF 的 `keyStats` 為 null），則直接採用聚合值。盤後 `dumpRedisToDb` 沿用同一份 Redis JSON 寫入 `stock_price_history`
- [ ] 美股 `openPrice` 來源：NASDAQ `/info` endpoint 自 2026/04 起不再回傳 `OpenPrice`，`PriceFetchClient.getNasdaqPrice` 額外打 NASDAQ `/historical` endpoint（`fromdate = todate = 美東今日`）抓今日 `open` 欄位；若 NASDAQ 尚未開盤、historical 無今日列、或查詢失敗，`openPrice = null`，下游 `WatchStockService` 維持 `stock_price_history` fallback 行為。台股維持 TWSE mis API 的 `o` 欄位
- [ ] **觀察清單支援代號 `0000`（市場 = 台股）= 台股大盤（TAIEX）**（含 KD）：
  - 加入觀察 = 設一筆 0000 的 alert（與其他股票流程一致）
  - `lookup-name` 端點看到 `code=0000&market=台股` 直接回 `{"stockName":"台股大盤"}`，不打外部 API、不寫入 stock 主檔
  - `StockAlertService.create` 對 `0000` 不寫入 `stock` 主檔（避免被排程當作真股票抓價）；報價/技術指標一律從 `twse_index_daily_history` 取
  - 觀察清單列：`price` / `previousClose` 取 `twse_index_daily_history` 最新與次新；`openPrice` / `highPrice` / `lowPrice` 從同表 OHLC 欄位填；`buyPrice` / `sellPrice` / `volume` 為 null（大盤無買賣盤口、無成交量定義）
  - 季線（MA60）、年線（MA240）、KD 皆從 `twse_index_daily_history` 計算（與一般股票同算法），`TechnicalIndicatorService` 對 `0000` 改讀此表代替 `stock_price_history`
  - 警示彙總（`lastTriggered*`）對 `0000` 比照其他股票顯示

---

### Requirement 15: 資料庫備份／還原（UI 介面 + 自動排程）

**User Story:** 作為使用者，我希望系統能依交易日曆自動於每個交易日收盤後與每周日定時備份資料庫到 Google Drive，並能在「系統設定」選單下手動觸發備份／挑選歷史備份還原，以確保資料連續性與災難復原能力。

**Acceptance Criteria:**

- [ ] 左側導覽選單「系統設定」子選單下新增「備份/還原 資料」項目，路徑 `/settings/backup-restore`
- [ ] 頁面分成兩個區塊：「立即備份」與「還原資料」
- [ ] 「立即備份」按鈕觸發 `POST /api/backups`，後端執行 `pg_dump` 並上傳加密檔到 `gdrive-crypt:backups/manual/`
- [ ] **自動備份排程（後端 `@Scheduled`，時區 `Asia/Taipei`）：**
  - **台股交易日備份**：每個台股交易日收盤（13:30）後 2 小時，於 15:30 啟動，檔案上傳至 `daily/`，檔名 `asset_daily_tw_YYYYMMDD_HHmmss.dump`。台股是否為交易日依 TWSE Open API 假日表（`MarketDataService.getTwHolidays`）為準。
  - **美股交易日備份**：每個美股交易日收盤（美東 16:00）後 2 小時，對應台北時間 06:00～07:00（依夏令／標準時切換），統一於台北時間 07:00 啟動（隔日 TUE–SAT），檔案上傳至 `daily/`，檔名 `asset_daily_us_YYYYMMDD_HHmmss.dump`。美股是否為交易日依 NYSE 假日規則（`MarketDataService.getUsHolidays`）為準。
  - **每周備份**：每周日台北時間 05:00 啟動，檔案上傳至 `weekly/`，檔名 `asset_weekly_YYYYMMDD_HHmmss.dump`，不檢查交易日。
- [ ] **保留代數（自動輪替，依資料夾分別計算；可由使用者於 UI 調整，預設值如下）：**
  - `daily/`：預設 **50** 份（`asset_daily_*` 才計入；超過自動刪除最舊）
  - `weekly/`：預設 **5** 份
  - `manual/`：預設 **5** 份手動備份（`asset_manual_*`，自救點 `asset_auto-pre-restore_*` 不計入）
  - 設定儲存於 `backup_setting` 單列資料表，欄位 `manual_retention` / `daily_retention` / `weekly_retention`（INT，1～999）
  - 端點：`GET /api/backups/settings`、`PUT /api/backups/settings`
  - 前端「備份/還原 資料」頁新增「保留設定」區塊，三個 number input + 儲存按鈕；儲存成功顯示 `ElMessage.success`
  - **儲存設定當下立即套用 retention**：使用者下調某類型保留代數時，`PUT /api/backups/settings` 在更新 `backup_setting` 後立即對 `manual/`、`daily/`、`weekly/` 三個資料夾各跑一次 `rotateFolder`，把超出新上限的最舊備份刪除（含 `backup_record` row 與 Google Drive 上加密檔），不需等到下一次排程備份。輪替過程中任何單一資料夾失敗只記 log、不影響設定儲存成功與其他資料夾繼續輪替
- [ ] 手動備份檔案名稱格式 `asset_manual_YYYYMMDD_HHMMSS.dump`
- [ ] 「還原資料」區塊以 `el-table` 列出 Google Drive 上**所有**備份（含 manual / daily / weekly / monthly 四個資料夾），欄位：來源資料夾、檔名、備份時間、檔案大小
- [ ] 列表預設依備份時間「新→舊」排序
- [ ] 每列提供「還原」按鈕，點擊後跳出二次確認對話框，需在輸入框輸入「確認還原」字樣才能執行
- [ ] 還原前自動建立「自救點」備份（`asset_auto-pre-restore_YYYYMMDD_HHMMSS.dump`，存於 `manual/`，不計入 5 份保留上限）
- [ ] 還原期間 UI 顯示遮罩「還原中…請勿關閉視窗」，禁止其他操作
- [ ] 還原完成後自動重新載入頁面（HikariCP 會自動重連 PostgreSQL）
- [ ] 備份／還原失敗時顯示錯誤訊息（含後端 stderr 訊息摘要），不直接拋 500
- [ ] 後端需透過 `ProcessBuilder` 呼叫 `pg_dump` / `pg_restore` / `rclone`，相關工具透過 Dockerfile 安裝、rclone 設定檔以 read-only volume 從 host 掛入
- [ ] 「保留設定」新增「啟用備份」開關（`backup_setting.backup_enabled`，預設 true）。關閉時：手動備份按鈕停用，所有 `@Scheduled` 排程在進入備份流程前先檢查並 skip。還原流程不受開關影響（自救點仍會建立）
- [ ] 備份紀錄改存於 DB（`backup_record` 表，欄位：folder, filename, size_bytes, modified_at, auto_pre_restore, created_at），每次備份成功後寫入一筆；rotateFolder 刪除舊檔時連帶刪除對應 row。還原資料列表 (`GET /api/backups`) 一律從 DB 讀取，不再每次連 Google Drive，UI 開啟即時顯示。新增「從 Google Drive 同步」按鈕呼叫 `POST /api/backups/sync`：列出 rclone 並 upsert / 清除 DB 與 GDrive 不一致的紀錄，供首次部署或外部刪檔後手動對齊

---

### Requirement 16: 到價警示（Stock Alerts）

**User Story:** 作為使用者，我希望能設定股票的到價警示（價格門檻、均線、KD 等），系統定期檢查並記錄最近一次觸發資訊，於股票觀察頁面彙總顯示。

**Acceptance Criteria:**

- [x] 警示資料儲存於獨立資料表 `stock_alert`，欄位含股票代號、名稱、市場、條件、啟用狀態、顯示排序、最近觸發時間/股價/均線/KD
- [x] 警示頁面整併進股票觀察頁（路徑 `/stocks?tab=alerts`，舊路徑 `/stock-alerts` 自動 redirect）
- [x] 提供新增、編輯、刪除、啟用/停用、拖曳排序、手動觸發檢查
- [x] 觀察股票列表（Requirement 14）顯示對應股票最近一次觸發資訊
- [x] 提供 `GET /api/stock-alerts/lookup-name` 由代號自動帶名稱（觀察股票與警示新增表單共用）；對 `code=0000` 有市場守門：`台股` → 直接回「台股大盤」（TAIEX 特例），`美股` → 直接回空字串（美股無此代號，避免 Yahoo fuzzy match 回隨機公司並被自動寫入 `stock` 主檔）
- [x] **建立／更新警示時，後端守門：代號↔股名必須一致**。`StockAlertService.create` / `update` 在 `stockMasterRepo.upsert` 之前，呼叫 `historicalDataService.fetchTwStockName / fetchUsStockName(code)` 取得 canonical name（權威來源為 ext-materials-service `/internal/stock-name` → FinMind / Yahoo），若 canonical 非空且與使用者送來的 `stockName` 不一致（trim + 大小寫不敏感），**再對照本地 `stock` 主檔的名稱（亦視為合法 canonical）**；兩者皆不相符才回 `400`，並提示「代號 X 與股名「Y」不符，外部來源為「Z」」，阻止錯誤資料寫入 `stock` 主檔（過去症狀：使用者誤把 `2500` 標為「台積電」，於觀察清單顯示「2500 台積電」但所有報價欄位皆為 `—`，因 2500 非台積電也無 live／history 資料）。「本地主檔也算 canonical」的設計目的：lookupName 是「本地主檔優先 → 外部 fallback」，若外部來源在不同時間回不同字串（例如 Yahoo `shortName` 「NVIDIA Corporation」vs 過去寫入主檔的 「NVIDIA Corporation Common Stock」），會造成 UI 自動帶名後再送出時被守門擋下；既然 UI 帶出的值來自主檔，主檔本身就應算合法 canonical。canonical 為空（外部 API 失敗或查無）則信任使用者，避免外部服務異常時阻擋合法建立。`0000` + `台股` 跳過守門（大盤特例）；`0000` + `美股` 直接拒絕（美股無此代號）
- [ ] 提供 `GET /api/stock-alerts/lookup-code?name=&market=` 反向查找：由股名查代號（只查本地 `stock` 主檔，精確匹配；`「台股大盤」+ 台股 → 0000` 特例），查無時前端提示「請改輸入股票代號」。警示新增表單股名欄位 blur 時自動觸發，方便使用者直接打股名（如「富邦金」）建立警示
- [ ] **`stock_alert` 是觀察清單的唯一資料來源**：新增、編輯、刪除任一 alert 後，觀察清單需同步反映（出現新股票、移除最後一筆條件被刪的股票、displayOrder 變更後排序更新）
- [ ] 支援 `stockCode = 0000`（台股大盤）的 alert：可設 PRICE / MA / KD 各類型；報價與技術指標來源改為 `twse_index_daily_history`（含 OHLC）；不寫入 `stock` 主檔
- [ ] **均線警示通用化**：`stock_alert` 新增 `ma_period` 欄位（INT，可空），均線類警示 `alertType` 統一為 `MA_ABOVE_PCT` / `MA_BELOW_PCT` 並以 `ma_period` 表示天數，新增警示時可選 20（月線）/ 60（季線）/ 240（年線）；舊資料 `QUARTERLY_MA_*` → `MA_*_PCT` + `ma_period=60`，`ANNUAL_MA_*` → `MA_*_PCT` + `ma_period=240`（同 commit liquibase 遷移），未來再加任何均線週期不需改 enum-like 字串
- [ ] 每次警示觸發都記錄一筆歷史於 `stock_alert_trigger`，欄位含 `alert_id`、`triggered_at`、`price`、`monthly_ma` (MA20)、`quarterly_ma` (MA60)、`annual_ma` (MA240)、`k_value`、`d_value`、`created_at`
- [ ] 觸發紀錄只保留 30 天：每日排程刪除 `created_at < NOW() - 30 days` 的舊紀錄
- [ ] 觸發紀錄表透過 `alert_id` FK 級聯刪除（警示本體被刪時，歷史一併清除）
- [ ] 觸發時間（`lastTriggeredAt` / `stock_alert_trigger.triggered_at`）一律以「該股市場的本地 wall time」儲存（台股 = Asia/Taipei、美股 = America/New_York）。`computeTriggeredAt` 在交易時段內 fallback 不再用 JVM 預設時區的 `LocalDateTime.now()`，改用 `ZonedDateTime.now(marketZone).toLocalDateTime()`，與 Yahoo intraday bar、`tradingDate.atTime(close)` 對齊。前端 `WatchStockView` / `StockAlertView` 顯示時加市場時區後綴（`TW` / `NY`），避免使用者把美股的「13:30」誤讀為台北時間

---

### Requirement 17: 待轉入資金類型設定管理

**User Story:** 作為使用者，我希望「待轉入資金類型」也能由資料庫管理，能在系統介面中新增、編輯、停用，不寫死於程式碼。

**Acceptance Criteria:**

- [x] 待轉入資金類型有獨立資料表，不以 Enum 寫死
- [x] 每個類型有識別代碼（code，唯一）、顯示名稱、顯示排序、啟用狀態
- [x] 使用者可新增、編輯、停用，停用後不出現於下拉選單但歷史資料仍正常顯示
- [x] 系統提供預設 Seed Data（`DataInitializer`）
- [x] 前端新增 `/settings/transit-fund-types` 設定頁面

---

### Requirement 18: 台灣人均 GDP 與台股大盤年度走勢比較

**User Story:** 作為使用者，我希望能在同一張圖上比較近 30 年「台灣人均 GDP」與「台股大盤 12/31 收盤點位」的年度變化，以理解總體經濟成長與股市表現的關聯。

**Acceptance Criteria:**

- [ ] 左側選單新增「GDP + 台股大盤」項目，路徑 `/gdp-twse`
- [ ] 頁面以雙 Y 軸折線圖呈現：左 Y 軸為人均 GDP（USD），右 Y 軸為台股大盤年末收盤點位
- [ ] 預設顯示近 30 年（含當年度若已有資料）
- [ ] 兩種年度資料各自存於資料庫獨立資料表（`taiwan_gdp_per_capita_history`、`twse_index_year_end_history`），由 Liquibase changelog seed
- [ ] 後端提供獨立資源端點 `/api/taiwan-gdp` 與 `/api/twse-year-end-index`
- [ ] BFF 端點 `/api/bff/gdp-twse`，前端只呼叫 BFF
- [ ] 滑鼠移到任一年份時，tooltip 同時顯示該年人均 GDP 與大盤收盤點位
- [ ] 「回補 GDP」按鈕回補台灣人均 GDP 與實質成長率，採**主計總處（DGBAS）優先、IMF 備援**：以 DGBAS NA8101A1A（國民所得統計常用資料-年，1951 起官方實際值）為主，DGBAS 缺的年份（未來預測年 2026+、或抓取失敗）才用 IMF DataMapper（`NGDPDPC/TWN` 人均 GDP、`NGDP_RPCH/TWN` 實質成長率）補；合併後 upsert 至 `taiwan_gdp_per_capita_history`（DB 存單一最終值，不分來源欄位）
- [ ] 「回補大盤」按鈕呼叫 TWSE FMTQIK 月報，逐年抓 12 月最後一筆收盤 upsert 至 DB
- [ ] BFF 在組裝 X 軸年份時過濾 `> 當年`（IMF 含未來預測，不顯示）
- [ ] 當年（尚未到 12/31）若 `twse_index_year_end_history` 無紀錄，BFF 以「最後一個交易日大盤收盤」（取自 `twse_index_daily_history` 最新一筆）回填當年值；前端在該點用空心圓區隔、tooltip 標註「截至 YYYY-MM-DD」
- [ ] 同頁下方加第二張「台韓人均 GDP 比較」圖：左 Y 軸為 TW/KR 人均 GDP（折線），右 Y 軸為各自年增率（柱狀）
- [ ] 韓國資料同樣由 IMF DataMapper API（`NGDPDPC/KOR`）回補，存於 `korea_gdp_per_capita_history`（DGBAS 無韓國資料，故韓國維持純 IMF）
- [ ] 「回補資料」按鈕同步觸發 TWN + KOR 兩國 GDP 回補
- [ ] 經濟成長率取**實質 GDP 成長率**（台灣優先 DGBAS NA8101A1A「經濟成長率(%)」、缺則 IMF `NGDP_RPCH`；韓國 IMF `NGDP_RPCH`），不再由前後端用人均 GDP（USD）相減推算（因含匯率波動會失真）；存於 `*_gdp_per_capita_history.real_gdp_growth_rate`。兩張圖的成長率柱狀皆讀此欄位（同一資料源）
- [ ] 同頁「最上方」加第三張卡：台股大盤（TAIEX）每日收盤近 10 年走勢圖
  - 顯示每日收盤點位（`close_point`）+ 月線（MA20）+ 季線（MA60）+ 年線（MA240）四條曲線
  - 區間切換按鈕：1 個月 / 3 個月 / 半年 / 1 年 / 2 年 / 5 年（透過 dataZoom 對齊 X 軸末端，前段 240 個交易日仍保留以利 MA240 完整顯示）
  - 後端日線資料表 `twse_index_daily_history`（`trading_date` PK, `open_point`, `high_point`, `low_point`, `close_point` 各 NUMERIC(12,2)），由 Liquibase changelog 建立（不 seed 歷史值）
  - business service 新增 `GET /api/twse-daily-index?from=YYYY-MM-DD&to=YYYY-MM-DD` 與 `POST /api/twse-daily-index/refresh?years=10`，後者逐月呼叫 TWSE FMTQIK 月報抓全部交易日 OHLC（`OpeningIndex` / `HighestIndex` / `LowestIndex` / `ClosingIndex`）upsert 至 DB
  - 大盤 OHLC 同時供 Requirement 14（觀察清單 0000 KD 計算）使用，不另建表
  - BFF 新增 `GET /api/bff/gdp-twse/twse-daily?years=10`：載入近 N 年日線並計算 MA20/60/240 後一次回傳；前端切換區間僅用 dataZoom 不再打 API
  - BFF 新增 `POST /api/bff/gdp-twse/refresh-twse-daily?years=10`：proxy 至 business `/api/twse-daily-index/refresh`，回補可能要 1~2 分鐘
  - 「回補資料」按鈕同步觸發 TWN GDP + KOR GDP + 大盤年末 + 大盤日線四項回補

---

### Requirement 19: 信託基金最新淨值自動估值

**User Story:** 作為使用者，我希望系統能自動抓取信託基金的最新淨值與匯率，自動算出台幣現值，免去每次建快照都要手動填現值的麻煩。

**Acceptance Criteria:**

- [ ] 新增「信託基金主檔」資料表（`fund_master`），記錄基金代號、名稱、計價幣別、銷售銀行、是否啟用
- [ ] 主檔由 Liquibase changelog seed 7 支基金：6 支華南境外基金（`02A8`、`02B9`、`01C2`、`1680`、`24B2`、`1616`）+ 1 支元大境內基金（`93100953A` 元大日本龍頭企業台幣 A 類）
- [ ] `fund_master` 含 `site`（`offshore` / `onshore`）+ FundClear 三段代碼（`fundclear_org_code` / `fundclear_fund_code` / `fundclear_class_code`），用於 NAV 抓取
- [ ] 抓取分流：`site=offshore` 打 `/api/offshore/nav-profit/query-history`（DTO `organizeCode`/`fundCode`/`fundClassCode`），`site=onshore` 打 `/api/onshore/nav-profit/query-history`（DTO `orgId`/`fundNo`/`fundClassCode`）
- [ ] TWD 計價基金（如元大日本龍頭台幣類型）跳過 FX 換算，直接 `units × nav` 即為台幣現值
- [ ] 新增「基金淨值快取」資料表（`fund_nav`），存放最新淨值與淨值日，按 `fund_code + nav_date` 唯一
- [ ] `FundHolding` 新增 `units`（總單位數）欄位，**保留** `currentValue` 欄位作為 snapshot 凍結值
- [ ] `units` 為 nullable：填了 → 系統用 `units × NAV × FX` 自動算出 `currentValue` 寫入 snapshot；未填 → 維持手填 `currentValue`（向後相容舊資料）
- [ ] `currency` 由 `fund_master.currency` 提供，FundHolding 不重複儲存
- [ ] external-materials-service 每日 cron（09:00 Asia/Taipei）抓取 `fund_master` 全部啟用基金的最新淨值，寫入 `fund_nav`
- [ ] 抓取來源：FundClear（境外基金資訊觀測站）為主、MoneyDJ 為 fallback；先用 `java.net.http.HttpClient` 純 API 抓
- [ ] 抓取失敗時保留上次 NAV，前端顯示「資料過時 X 天」警示
- [ ] 匯率服務擴充支援 ZAR/TWD（基金 `1680`、`24B2` 計價為南非幣），沿用既有 `ExchangeRateHistory` 表 + FinMind/台銀來源
- [ ] SnapshotForm 信託基金區塊：`units` 欄位手填、現值欄位改為唯讀（顯示自動計算結果），提供「刷新最新淨值」按鈕觸發 external-materials-service 立即抓取
- [ ] 對外端點：`POST /api/fund-nav/refresh`（business-services proxy 至 external `POST /internal/fund-nav/refresh`）
- [ ] 既有 FundHolding 資料（`units` NULL）保留原 `currentValue`，不破壞歷史 snapshot 一致性
- [ ] 新增「信託基金主檔設定」頁面 `/settings/funds`，提供 fund_master CRUD（同 bank / broker 設定頁風格）；可編輯 fundName / bank / currency / site / FundClear 三段代碼 / active；fundCode 為 PK 只在新增時可填，編輯不可改
- [ ] SnapshotForm 信託基金區塊「基金代號」「基金名稱」兩欄合併為單一 dropdown，顯示 `「{code}　{name}」`；選擇後 fundName 寫入隱藏欄位以保留現有 schema 與舊 snapshot 顯示相容性
- [ ] DataInitializer.seedFundMasters 為 idempotent — 只在 fund_code 不存在時插入，避免覆蓋使用者於設定頁修改的資料

---

### Requirement 20: 信託基金預估年配息

**User Story:** 作為使用者，我希望系統能自動估算每支信託基金的預估年配息（含於 snapshot 預估年配息合計），以便了解整體資產的被動收入水準。

**Acceptance Criteria:**

- [ ] 新增 `fund_dividend_history` 資料表，欄位 `(id, fund_code, base_date, amount, currency, frequency, fetched_at)`，唯一鍵 `(fund_code, base_date)`；amount 為「每單位原幣配息」，base_date 為配息基準日 (`asiBaseDate`)
- [ ] external-materials-service 新增 `FundDividendFetchClient`：offshore 打 `POST /api/offshore/fund-info/info-dividend/query`（DTO `queryType:"1"`、`organizeCode/fundCode/fundClassCode`、`baseBeginDate/baseEndDate` 為 YYYY/MM、`asiFreqList:[]`、`_pageNum/_pageSize`），onshore 對應 `/api/onshore/fund-info/info-dividend/query-dividend`；回應 `list[].asiBaseDate / asiAmt / asiFreq` 寫入 `fund_dividend_history`
- [ ] `FundDividendPoller` 每日 cron（接 `FundNavPoller` 之後）跑一次：對 `fund_master.active=TRUE` 的基金抓近 13 個月（避免月份切換遺漏）寫入；inactive 基金跳過
- [ ] `InternalPriceController` 暴露 `POST /internal/fund-dividend/refresh` 同步觸發；business-services 加 `POST /api/fund-dividend/refresh` proxy
- [ ] backend `FundDividendService.getAnnualEstimateTwd(fundCode)`：取 `fund_dividend_history` 近 12 個月 `amount` 加總 → 「每單位年配息（原幣）」 × FX → 台幣；回 Optional.empty 表示無資料
- [ ] `FundHolding` 新增 `estimatedDividend NUMERIC(20,2)` 欄位（snapshot 凍結值；同 `StockHolding.estimatedDividend` 風格）
- [ ] `AssetService.createSnapshot` / `updateSnapshot`：若 `units` 非 null 且有 dividend 估算，自動算 `fund.estimatedDividend = units × annualPerUnitTwd`；既有 row 不動
- [ ] `AssetSnapshot.estimatedAnnualDividend` 合計 = stock dividends + fund dividends（SnapshotEnricher / recalcTotals 一併調整）
- [ ] `AssetSnapshotDto.FundResponse` 新增 `estimatedDividend`、`dividendRate`（rate = annualPerUnitTwd × units / currentValue）
- [ ] SnapshotForm 信託基金 row 增加「預估年配息」欄（read-only，hover 顯示計算來源）
- [ ] Dashboard / SnapshotDetail 既有「預估年配息」自動含基金部分（無需額外改動，靠 snapshot 層級合計）

---

### Requirement 21: 信託基金歷史 NAV 與基準日估值

**User Story:** 作為使用者，我希望系統儲存每支基金 10 年的歷史淨值，且在編輯任一 snapshot 時用該 snapshot 基準日 (snapshotDate) 對應的 NAV / FX / 配息來計算 currentValue 與 estimatedDividend，而非永遠用「最新」值。

**Acceptance Criteria:**

- [ ] external-materials-service 新增 `FundNavBackfillService`：呼叫 FundClear `nav-profit/query-history` 分段抓取 10 年歷史 NAV 寫入 `fund_nav`（每段最多一年避免單次 response 過大）；針對 `fund_master.active=TRUE` 的所有基金
- [ ] 對外端點：`POST /internal/fund-nav/backfill?years=10` 一次性觸發；business-services 暴露 `POST /api/fund-nav/backfill?years=10` proxy
- [ ] 配息歷史 (`fund_dividend_history`) 同樣回補近 10 年；新增 `FundDividendBackfillService` + 端點
- [ ] 匯率歷史擴充：`HistoricalDataService.startupBackfill` / 啟動時對 `fund_master` 出現的所有非 TWD 幣別回補 10 年（既有 `backfillExchangeRateFrom` 已支援多幣別，僅需擴充呼叫範圍至 ZAR 等）
- [ ] backend `FundNavService.getNavTwdOnDate(fundCode, basedate)`：取 `fund_nav` 該日（含之前最近一筆，closest-on-or-before）+ 該日 FX；找不到回 Optional.empty
- [ ] backend `FundDividendService.getAnnualEstimateOnDate(fundCode, basedate)`：取 `fund_dividend_history` 中 base_date 落在 `[basedate - 12 months, basedate]` 的紀錄加總 × 該日 FX
- [ ] `AssetService.createSnapshot` / `updateSnapshot`：`resolveFundCurrentValue` / `resolveFundEstimatedDividend` 改用 `basedate` （= `snapshotDate`），不再用「最新」NAV/FX；對既有 snapshot 重存等於用基準日值覆寫手填 `currentValue`（決議 A）
- [ ] BFF `/api/bff/snapshot-form/funds?date=YYYY-MM-DD`：date 參數帶入時，每筆基金 DTO 的 `latestNav` / `latestFxRate` / `twdPerUnit` / `annualDividendPerUnitTwd` 改為「基準日值」；不帶 date 時保持「最新」
- [ ] SnapshotForm `loadFundMasters()` 帶 `form.snapshotDate`；當使用者改 snapshotDate（編輯既有 snapshot 切換不會發生但新建可能改）時重新載入 fund_master 預覽
- [ ] Dashboard / SnapshotDetail 顯示 snapshot 凍結 `currentValue` / `estimatedDividend`，**無需改動**（既已是基準日值）

---

### Requirement 22: 代繳帳戶記錄管理

**User Story:** 作為使用者，我希望系統能集中記錄每筆「定期代繳項目」對應的扣款帳戶與備註（如：市話 + MOD → momo 信用卡 → 用戶號碼 Y046509、台電電費 → momo 信用卡 → 電號 16680357783），以便日後查詢扣款歸屬、核對帳單時不必每次翻找紙本資料。

**Acceptance Criteria:**

- [ ] 新增「自動代繳」主選單（與其他主功能同層，置於「系統設定」之前），路徑 `/payment-accounts`，對應 `PaymentAccountSettingsView`；舊路徑 `/settings/payment-accounts` 自動 redirect 至新路徑以維持書籤相容
- [ ] 代繳記錄欄位包含：**分類**（FK 至 `payment_category`）、**項目**（自由輸入字串，如「市話 + MOD」）、**帳戶**（自由輸入字串，可填銀行帳戶或信用卡名稱，**不**與既有 `bank` / `broker` entity 關聯）、**備註**（自由輸入字串，可放用戶號碼、電號、水號等）、顯示排序
- [ ] 分類儲存於資料庫 `payment_category` 表，不寫死 Enum；提供 `code` / `displayName` / `sortOrder` / `active` 欄位
- [ ] 同頁面上半段提供分類維護區（新增 / 編輯 / 啟用-停用），下半段為代繳記錄主表格，可依分類過濾並依 `payment_category.sortOrder` → `payment_account.sortOrder` 排序顯示
- [ ] 代繳記錄支援新增、編輯、刪除（硬刪除，不做軟停用）
- [ ] 系統提供預設 Seed Data：三個分類 `繳費` / `繳稅` / `服務`（與圖示一致）；代繳記錄本身不 seed，由使用者依實際扣款項目自行新增
- [ ] 後端 API：`/api/settings/payment-categories`（分類 CRUD + active toggle）與 `/api/payment-accounts`（記錄 CRUD）；前端透過單一 BFF `/api/bff/payment-account-settings/**` 取得
- [ ] 分類若被任一代繳記錄引用，停用後仍允許舊紀錄顯示其分類，但停用中的分類不出現於新增 / 編輯 dialog 的下拉選單
- [ ] 不與既有資產 / 銀行 / 券商表產生 FK 關聯；本功能屬獨立的「資料記錄簿」，不影響資產計算

---

### Requirement 23: 警示觸發 Email 通知

**User Story:** 作為使用者，我希望股票觀察清單的警示條件觸發時，系統能自動寄 email 到我的信箱，避免我必須長時間盯盤才能察覺到價。

**Acceptance Criteria:**

- [ ] 警示條件（`StockAlert`）於 `StockAlertService.evaluate()` 判定為觸發、且通過既有 24 小時冷卻機制，寫入 `StockAlertTrigger` 後，必須對所有 `active=true` 的收件人寄送通知 email
- [ ] 同一輪輪詢（盤中每 2 分鐘）或同一次手動「立即檢查」內若同時觸發多筆警示，合併成單一 digest email 寄出（每筆觸發一行），避免同時段收到多封信
- [ ] Digest 寄送節流：以 in-memory 觸發 queue + 排程 flush（每 60 秒一次）實作；queue 內為空時 skip 寄信
- [ ] Email 主旨格式：`[資產管理] 股票警示觸發 N 筆`（N = 本次合併筆數）
- [ ] Email 內容必須包含：股票名稱與代號、市場、警示條件（同前端「警示條件」欄位顯示文案）、觸發時間（市場時區 wall time）、觸發股價、相關技術指標（MA / K / D；無對應指標時欄位省略）
- [ ] 提供「通知收件人」設定頁（`/notification-settings`，主選單「系統設定」群組內），使用者可新增 / 刪除 / 啟停 email 收件人，至少支援 0 ~ N 筆收件人
- [ ] 收件人持久化於資料庫 `notification_recipient` 表（欄位：`id`、`email`、`active`、`created_at`、`updated_at`），不寫死於程式碼或設定檔；email 欄位需正規化（去除前後空白、轉小寫）並 unique 不可重複
- [ ] 後端 API：`/api/notification-recipients`（CRUD + `PATCH /api/notification-recipients/{id}/active` 切換啟停）
- [ ] BFF passthrough：`/api/bff/notification-settings/recipients/**` → `/api/notification-recipients/**`
- [ ] SMTP 走 Gmail：`smtp.gmail.com:587` STARTTLS；帳密透過環境變數 `MAIL_USERNAME` / `MAIL_PASSWORD`（Gmail App Password，**非**一般登入密碼）注入，**絕不**寫入 yaml / git；寄件人預設 = `MAIL_USERNAME`，可選擇性以 `NOTIFICATION_FROM` 覆寫
- [ ] 收件人為空、或環境變數 `MAIL_USERNAME` / `MAIL_PASSWORD` 未設定時，dispatcher 直接 skip 寄信（僅 log 警告），不可導致警示判斷流程拋例外、阻擋 `StockAlertService.evaluate()`
- [ ] 寄信失敗（SMTP 錯誤、authentication 失敗、network 異常）僅 `log.warn`，**不**重試、**不**讓觸發判斷 transaction rollback；觸發紀錄 `StockAlertTrigger` 必須無論寄信成敗都已落地


---

### Requirement 24: 英股市場類型擴充（LSE UCITS ETF）

**User Story:** 作為使用者，我希望系統能除了台股、美股之外，再支援「英股」這個市場類型，讓我能透過複委託投資 LSE 掛牌的 UCITS ETF（如 CSPX、VWRA、VUSA、EIMI、IWDA），並在所有相關頁面正確呈現。

**Acceptance Criteria:**

- [ ] `market_type` seed 包含「英股」（displayName「英國股市」、sortOrder=3），由 `DataInitializer.seedMarketTypes()` 提供
- [ ] **計價幣別沿用既有 USD 體系**：使用者購買的標的（如 CSPX.L）為 USD-denominated UCITS ETF，`StockHolding.currency` 存 `USD`，市值換算與美股同 path（`shares × price × usdExchangeRate`），不引入 GBP 匯率體系。若未來購買 GBP-denominated UCITS（如 CSP1.L），再另外擴充 currency=GBP 體系
- [ ] **即時股價走 Yahoo Finance `.L` suffix**：`external-materials-service` 的 `PriceFetchClient.getStockPrice(code, "英股")` 打 `https://query2.finance.yahoo.com/v8/finance/chart/{code}.L`，透過 `curlGetWithRetry` 子程序避開 Yahoo Java HTTP fingerprint 偵測，從 `meta.regularMarketPrice` 取 live 價、`previousClose` 算漲跌；source 標 `Yahoo`
- [ ] 倫敦時區（`Europe/London`，由 JVM `ZoneId` 自動處理 BST/GMT 切換）寫入 `MarketClock`：`isUkMarketOpen()` 為週一～五 08:00–16:30、`isUkMarketJustClosed()` 為 16:30–16:50；`PricePoller.scheduledUkIntradayUpdate` cron `0 0/2 8-16 * * MON-FRI` zone `Europe/London`
- [ ] 收盤寫 `stock_price_history`：`ClosePersister.dumpUkCloseFromRedis` 於倫敦 16:32 dump Redis 收盤值至 DB（與台股 13:32、美股 16:02 同 pattern）；`verifyUkCloseWithYahoo` 於倫敦 17:00 用 Yahoo `chart` 單日查當日 OHLC 覆寫 DB 與 Redis（英股無 FinMind 對應 dataset，校正改走 Yahoo historical）
- [ ] 歷史回補：`HistoricalBackfillService.backfillUkStock` 仿 `backfillUsStock`，呼叫 `priceFetch.fetchUkHistoricalRange(code, start, end)` 走 Yahoo `chart` API（timezone `Europe/London`）；今日 bar 一律 skip（沿用 Requirement 7「今日列獨佔給 ClosePersister」）
- [ ] `business-services` 的 `StockPriceService.getLiveAssets` 對 `market="英股"` 持倉套用 `usdExchangeRate` 換算台幣（與美股同 path）；`getMarketStatus()` 回傳新增 `ukMarketOpen` / `ukTime`；`LiveAssetsResponse` 新增 `ukMarketOpen`
- [ ] 服務層 timezone 路由共用 helper（`MarketZones.resolve(market)`）：`美股 → America/New_York`、`英股 → Europe/London`、其餘（含 `台股`、`0000`）→ `Asia/Taipei`。`TechnicalIndicatorService` / `WatchStockService` / `StockAlertService` / `HistoricalDataService` 一律改用此 helper
- [ ] `StockAlertController` 的 `lookup-name` 守門：`0000 + 英股` 直接回空字串（英股無此代號，避免 Yahoo fuzzy match 寫入 stock 主檔），與 `0000 + 美股` 同邏輯
- [ ] `StockAlertService.assertNameMatchesCode`：英股 canonical name 走 `MarketDataFetchService.fetchUkStockName(code)`（Yahoo `chart meta.shortName` for `{code}.L`），其餘規則同既有
- [ ] `MarketDataService.getDividendRate("英股", code)`：走 Yahoo `chart?events=div` 對 `{code}.L` 取 TTM 配息；`getEtfHoldings("英股", code)`：呼叫 Yahoo `quoteSummary?modules=topHoldings` 對 `{code}.L`，失敗則回「未支援」訊息；`isEtf("英股", code)` 採白名單 `[CSPX, VWRA, VUSA, EIMI, IWDA]`
- [ ] 前端各頁面同步支援英股：
  - `StockAnalysisDialog.vue`：`isEtf` 加英股白名單；`etfExternalLinks` 加 iShares 官網 (`https://www.ishares.com/uk/individual/en/products/search?keyword={code}`)
  - `DashboardView.vue`：頂部資產彙整列加「英股現值」一欄；資產配置圓餅圖加第 6 區「英股」（Requirement 9 圓餅圖規格從 5 區擴為 6 區）；KPI / 即時資產估算演算法對英股套 USD 匯率
  - `AssetHistoryView.vue`：歷年資產表格加「英股」欄；今日列以 live-assets 覆寫的邏輯涵蓋英股
  - `SnapshotDetailView.vue`：英股持股小數位顯示 5 位、currency `USD`（與美股同）
  - `SnapshotFormView.vue`：股票區塊加英股區、預設 `currency='USD'`，匯率欄位沿用美元匯率
  - `WatchStockView.vue` / `StockAlertView.vue`：tab 加「英股」、觸發時間時區後綴顯示 `LON`
  - `TradingCalendarView.vue`：顯示英股開收盤狀態（讀 `getMarketStatus()` 的 `ukMarketOpen` / `ukTime`）；LSE 銀行假日由 `MarketDataService.getUkHolidays(year)` 計算（New Year's Day / Good Friday / Easter Monday / Early May / Spring / Summer Bank Holiday / Christmas / Boxing Day，含 weekend forward observed）
  - `RealizedGainView.vue`：已實現損益的 market 篩選 / 新增表單加「英股」選項
- [ ] **不在本次範圍**：GBP 匯率體系、Excel 匯入英股欄位、iShares ETF 持股 scrape（先依靠 Yahoo `topHoldings`）
