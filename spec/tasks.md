# Tasks

## Implementation Tasks

以下任務依功能模組分組，每個任務標注所對應的 Requirements，並列出具體實作步驟。任務之間若有依賴關係則以「前置任務」標注。

### 本檔的組織方式

> **新任務不要寫進這個檔。** 每個新任務建立一支獨立的自足任務檔 `spec/tasks/tNNN_<slug>.md`，
> 內容須完整到「只讀那一支檔就能實作」，不必回頭翻 `requirements.md` / `design.md` 或其他任務檔。
> 格式與自足規則見 [spec/tasks/README.md](tasks/README.md)。

Task 1–200 為歷史紀錄，已凍結並切分至歸檔檔，內容不再修改：

| 歸檔檔 | 收錄 |
|---|---|
| [tasks-001-050.md](tasks/archive/tasks-001-050.md) | Task 1–50（另含後補編號的 181、182） |
| [tasks-051-100.md](tasks/archive/tasks-051-100.md) | Task 51–100（含 60a–60h 系列） |
| [tasks-101-150.md](tasks/archive/tasks-101-150.md) | Task 101–150 |
| [tasks-151-200.md](tasks/archive/tasks-151-200.md) | Task 151–200（181、182 除外） |

Task 201 起仍在本檔下方，是最後一批以單體檔記錄的任務；收尾後不再新增。

> **編號沿革（讀索引時會撞到的三個怪點）：**
> - **Task 11 不存在** —— 從未建立，非遺漏。
> - **Task 181、182 物理位置在 001–050 歸檔檔**：兩者原誤編為 Task 39/40，Task 183.6 重編號但未搬動位置。
> - **Task 14b、60a–60h** 是後補的子編號；**Task 219 排在 220 之後**寫入。索引一律依編號排序，與檔案內順序不同屬正常。
> - **Task 320 曾被兩個 worktree 同時佔用**：`t320_radar_live_etf_premium_column.md`（已於 `f8014302` landed，保留 320）與 `t320_public_trading_radar_stock_detail_loopback_api.md`（另一分支進行中，依「已 landed 的保留原號」原則需自行改號）。Task 321 於撞號當下刻意跳號避讓，故 321 早於未 landed 的那支 320 建立。

---

## 任務索引（Task 1–228、264–267、269–292、297–309、311–342、344–362）

| Task | 標題 | 位置 |
|---|---|---|
| 1 | 專案基礎建設 | [001–050](tasks/archive/tasks-001-050.md) |
| 2 | 資料模型與 Repository | [001–050](tasks/archive/tasks-001-050.md) |
| 3 | 資產快照 API | [001–050](tasks/archive/tasks-001-050.md) |
| 4 | Excel 批次匯入（已停用） | [001–050](tasks/archive/tasks-001-050.md) |
| 5 | 已實現損益模組 | [001–050](tasks/archive/tasks-001-050.md) |
| 6 | 市場資料整合 | [001–050](tasks/archive/tasks-001-050.md) |
| 7 | 資產歷史趨勢 | [001–050](tasks/archive/tasks-001-050.md) |
| 8 | 前端 API 整合層 | [001–050](tasks/archive/tasks-001-050.md) |
| 9 | 前端頁面實作 | [001–050](tasks/archive/tasks-001-050.md) |
| 10 | 測試與品質保證 | [001–050](tasks/archive/tasks-001-050.md) |
| 12 | 銀行與券商設定管理 | [001–050](tasks/archive/tasks-001-050.md) |
| 13 | 存款類型與市場類型設定管理 | [001–050](tasks/archive/tasks-001-050.md) |
| 14 | 即時股價排程與資產估算 | [001–050](tasks/archive/tasks-001-050.md) |
| 14b | 股票交易類型與日期 | [001–050](tasks/archive/tasks-001-050.md) |
| 15 | 微服務架構重構（MVC → BFF + Business Services） | [001–050](tasks/archive/tasks-001-050.md) |
| 16 | 文件與部署 | [001–050](tasks/archive/tasks-001-050.md) |
| 17 | Excel 批次匯出 | [001–050](tasks/archive/tasks-001-050.md) |
| 18 | 股票走勢圖 Popup 延伸（ETF 持股明細 + 股利歷史） | [001–050](tasks/archive/tasks-001-050.md) |
| 19 | 觀察股票清單 | [001–050](tasks/archive/tasks-001-050.md) |
| 20 | 資料庫備份／還原（UI 介面） | [001–050](tasks/archive/tasks-001-050.md) |
| 21 | 到價警示（Stock Alerts） | [001–050](tasks/archive/tasks-001-050.md) |
| 22 | 待轉入資金類型設定管理（TransitFundType） | [001–050](tasks/archive/tasks-001-050.md) |
| 23 | 資料庫正規化 — 移除 legacy 字串欄位 | [001–050](tasks/archive/tasks-001-050.md) |
| 24 | 資料庫正規化 — 移除 RealizedGain 衍生欄位 | [001–050](tasks/archive/tasks-001-050.md) |
| 25 | 歷史回補範圍擴大至 stock 主檔 | [001–050](tasks/archive/tasks-001-050.md) |
| 26 | 全面正規化 — 移除冗餘 / 衍生欄位 | [001–050](tasks/archive/tasks-001-050.md) |
| 27 | 新增觀察股票時即時抓價 | [001–050](tasks/archive/tasks-001-050.md) |
| 28 | 啟動補抓主檔缺報價的股票 | [001–050](tasks/archive/tasks-001-050.md) |
| 29 | 儀表板「股票持股」股價依基準日顯示 | [001–050](tasks/archive/tasks-001-050.md) |
| 30 | 修復基準日切換後圖表未跟動 | [001–050](tasks/archive/tasks-001-050.md) |
| 31 | 股利歷史新增「除息日昨收價」欄位 | [001–050](tasks/archive/tasks-001-050.md) |
| 32 | 股利歷史新增年度小計列與現金殖利率欄 | [001–050](tasks/archive/tasks-001-050.md) |
| 33 | FinMind API token 支援（修正股利歷史 402） | [001–050](tasks/archive/tasks-001-050.md) |
| 34 | SnapshotForm「台幣存款總計」併入在途款項 | [001–050](tasks/archive/tasks-001-050.md) |
| 35 | Dashboard 美元存款併入 TRANSIT_USD | [001–050](tasks/archive/tasks-001-050.md) |
| 36 | 即時股價排程拆成「開盤抓即時 + 收盤後單次紀錄」 | [001–050](tasks/archive/tasks-001-050.md) |
| 37 | 即時股價移除 Yahoo Finance fallback + Yahoo crumb negative cache | [001–050](tasks/archive/tasks-001-050.md) |
| 38 | 台股收盤價紀錄改用 FinMind | [001–050](tasks/archive/tasks-001-050.md) |
| 39 | 股價基準日規則改 per-market（修正美股盤中誤顯示前一交易日收盤） | [001–050](tasks/archive/tasks-001-050.md) |
| 40 | SnapshotForm BFF 配息率批次抓取避免 30s timeout | [001–050](tasks/archive/tasks-001-050.md) |
| 41 | dividend-rate 加 backend in-memory cache (TTL 1h) | [001–050](tasks/archive/tasks-001-050.md) |
| 42 | Dashboard 前端不再重做 basedate 判斷，一律信任 BFF flag | [001–050](tasks/archive/tasks-001-050.md) |
| 43 | 到價警示觸發時間改用 LocalDateTime.now()（不再寫成收盤時間） | [001–050](tasks/archive/tasks-001-050.md) |
| 44 | 警示頁面 MA / KD 顯示「觸發時」值（不再混入當前值） | [001–050](tasks/archive/tasks-001-050.md) |
| 45 | 管理資產（SnapshotForm 編輯模式）股價/漲跌與新增模式一致 | [001–050](tasks/archive/tasks-001-050.md) |
| 46 | 美股漲跌幅錯誤 — `previous_close` 改用歷史表權威值 | [001–050](tasks/archive/tasks-001-050.md) |
| 47 | 抓股價拆出獨立微服務（external-materials-service + Redis live cache） | [001–050](tasks/archive/tasks-001-050.md) |
| 48 | Live 股價即時推送（Redis pub/sub + SSE，取代前端 polling） | [001–050](tasks/archive/tasks-001-050.md) |
| 49 | 股利歷史快取至 DB（每日同步，UI 直讀 DB） | [001–050](tasks/archive/tasks-001-050.md) |
| 50 | 盤中 high / low 自行聚合（修 NASDAQ ETF 無 dayrange） | [001–050](tasks/archive/tasks-001-050.md) |
| 51 | 儀表板新增「信託基金」長條圖 | [051–100](tasks/archive/tasks-051-100.md) |
| 52 | 備份開關 + 紀錄改存 DB | [051–100](tasks/archive/tasks-051-100.md) |
| 53 | GDP + 台股大盤年度走勢頁 | [051–100](tasks/archive/tasks-051-100.md) |
| 54 | 信託基金最新淨值自動估值 | [051–100](tasks/archive/tasks-051-100.md) |
| 55 | 信託基金主檔設定頁 + SnapshotForm 欄位合併 | [051–100](tasks/archive/tasks-051-100.md) |
| 56 | 信託基金預估年配息 | [051–100](tasks/archive/tasks-051-100.md) |
| 57 | 信託基金歷史 NAV / 配息 / FX 回補 + 基準日估值 | [051–100](tasks/archive/tasks-051-100.md) |
| 58 | 走勢圖「股價」線只取實際成交價 | [051–100](tasks/archive/tasks-051-100.md) |
| 59 | Dashboard KPI「資產總計」與「歷年資產管理」對齊 | [051–100](tasks/archive/tasks-051-100.md) |
| 60 | 修復 SSE 推送讓「股價」欄滲入非基準日的 live 價（Task 29 回歸） | [051–100](tasks/archive/tasks-051-100.md) |
| 60a | 移除 business-services 重複的每日股價收盤排程 | [051–100](tasks/archive/tasks-051-100.md) |
| 60b | 將股票歷史回補 + 啟動 10 年回補 + FX FinMind 回補搬到 external-materials-service | [051–100](tasks/archive/tasks-051-100.md) |
| 60c | 匯率排程（BOT 5 分鐘 + FinMind 17:00）搬到 external-materials-service | [051–100](tasks/archive/tasks-051-100.md) |
| 60d | 警示盤中 5 分鐘 K 線抓取搬到 external-materials-service | [051–100](tasks/archive/tasks-051-100.md) |
| 60e | MarketDataService 配息率 / ETF / 股利歷史 / TWSE 假日 / 股票名稱搬到 external-materials-service | [051–100](tasks/archive/tasks-051-100.md) |
| 60f | MacroHistoryService IMF / TWSE FMTQIK 搬到 external-materials-service | [051–100](tasks/archive/tasks-051-100.md) |
| 60g | 警示頁文案校正（沒有基準日，盤中每 2 分鐘隨股價更新檢查） | [051–100](tasks/archive/tasks-051-100.md) |
| 60h | 修台股大盤日線抓取（舊 URL 失效 + 加每日排程） | [051–100](tasks/archive/tasks-051-100.md) |
| 61 | 觀察清單由 stock_alert 衍生（廢止 watch_stock 表 + 大盤 0000 KD） | [051–100](tasks/archive/tasks-051-100.md) |
| 62 | 觀察清單欄位調整（移除買進/賣出、新增警示條件欄） | [051–100](tasks/archive/tasks-051-100.md) |
| 63 | 保留設定儲存時立即套用 retention | [051–100](tasks/archive/tasks-051-100.md) |
| 64 | 美股觀察清單「開盤」欄補上今日 open（NASDAQ historical endpoint） | [051–100](tasks/archive/tasks-051-100.md) |
| 65 | 代繳帳戶記錄管理（Requirement 22） | [051–100](tasks/archive/tasks-051-100.md) |
| 66 | SnapshotForm 台股 broker 列修改股數時自動重算持股成本 | [051–100](tasks/archive/tasks-051-100.md) |
| 67 | SnapshotForm 編輯模式下使用者改動後 KPI 改用 live 計算 | [051–100](tasks/archive/tasks-051-100.md) |
| 68 | MarketDataService.getDividendRate 無配息資料時用 stock 主檔補 stockName | [051–100](tasks/archive/tasks-051-100.md) |
| 69 | 美股殖利率新增 Yahoo Finance fallback（覆蓋 NYSE / NYSEARCA） | [051–100](tasks/archive/tasks-051-100.md) |
| 70 | Backup rotation 改以 DB `backup_record` 為單一事實來源 | [051–100](tasks/archive/tasks-051-100.md) |
| 71 | BackupRestoreView 儲存保留設定後 reload 備份列表 | [051–100](tasks/archive/tasks-051-100.md) |
| 72 | 均線警示通用化（新增月線 MA20、改為 `MA_*_PCT` + `ma_period`） | [051–100](tasks/archive/tasks-051-100.md) |
| 73 | 觀察清單拿掉「操作」欄（移除觀察改走警示條件頁） | [051–100](tasks/archive/tasks-051-100.md) |
| 74 | 新增警示 dialog 支援「股名 → 代號」反向自動帶入 | [051–100](tasks/archive/tasks-051-100.md) |
| 75 | `lookup-name` 對「美股 + 0000」加守門（防 Yahoo fuzzy match 污染主檔） | [051–100](tasks/archive/tasks-051-100.md) |
| 76 | 已實現損益明細列雙擊開啟股票分析圖 | [051–100](tasks/archive/tasks-051-100.md) |
| 77 | 存款新增「年利率／預估利息」欄位並併入預估年配息 | [051–100](tasks/archive/tasks-051-100.md) |
| 78 | 警示存檔守門放寬：本地 stock 主檔也算合法 canonical | [051–100](tasks/archive/tasks-051-100.md) |
| 79 | 修復盤中股價跳回昨收（TWSE `z='-'` 改採 skip write） | [051–100](tasks/archive/tasks-051-100.md) |
| 80 | cold-start fallback — `z='-'` 補上「今日開盤價」候選 | [051–100](tasks/archive/tasks-051-100.md) |
| 81 | 回退 Task 80 cold-start — Redis 只能由真實 z tick 推進 | [051–100](tasks/archive/tasks-051-100.md) |
| 82 | Redis 股價 TTL 拉長至 24h（修「股價退回昨收」的退化） | [051–100](tasks/archive/tasks-051-100.md) |
| 83 | 警示觸發 Email 通知 | [051–100](tasks/archive/tasks-051-100.md) |
| 84 | HistoricalBackfillService 禁止寫入「今日列」（避免 Yahoo intraday bar 汙染 DB 收盤） | [051–100](tasks/archive/tasks-051-100.md) |
| 85 | 新增「英股」市場類型（CSPX 等 LSE UCITS ETF） | [051–100](tasks/archive/tasks-051-100.md) |
| 86 | Dashboard 資產配置面板加「台股個股穿透前 10 大」tab | [051–100](tasks/archive/tasks-051-100.md) |
| 87 | 股票走勢圖期間按鈕加「當日」（最後一交易日分時 5m K 線） | [051–100](tasks/archive/tasks-051-100.md) |
| 88 | 「當日」走勢兩階段資料源（盤中 polling 累積 + 盤後外部源覆寫） | [051–100](tasks/archive/tasks-051-100.md) |
| 89 | 走勢圖「成本均價」改用 BFF avgCostOriginal（修跨頁買入均價不一致） | [051–100](tasks/archive/tasks-051-100.md) |
| 90 | 警示觸發 Email 改列月線／季線／年線三條均線 | [051–100](tasks/archive/tasks-051-100.md) |
| 91 | 觀察清單「補發」按鈕（各市場最後交易日觸發事件，單封 email 重寄） | [051–100](tasks/archive/tasks-051-100.md) |
| 92 | 警示 / 補發 email 同一股票多條件合併成一筆 | [051–100](tasks/archive/tasks-051-100.md) |
| 93 | 警示 / 補發 email 內嵌「股票分析走勢圖」PNG | [051–100](tasks/archive/tasks-051-100.md) |
| 94 | email 走勢圖升級為 Price+MA / KD 雙 pane、數值入圖、文字精簡 | [051–100](tasks/archive/tasks-051-100.md) |
| 95 | GDP+大盤頁日線圖支援「台股 / 美股四大指數」切換 | [051–100](tasks/archive/tasks-051-100.md) |
| 96 | 指數日線圖新增「當日」分時走勢（盤中即時 / 盤後最後交易日） | [051–100](tasks/archive/tasks-051-100.md) |
| 97 | 移除「台灣人均 GDP vs 台股大盤年末收盤」卡 + 清後端死碼 | [051–100](tasks/archive/tasks-051-100.md) |
| 98 | 債券 ETF / 收益分配型 ETF 股利歷史 fallback（TaiwanStockDividendResult） | [051–100](tasks/archive/tasks-051-100.md) |
| 99 | 美股非 NASDAQ ETF 股利歷史 fallback（Yahoo chart events=div） | [051–100](tasks/archive/tasks-051-100.md) |
| 100 | 警示 email 寄送時段閘門（盤外市場不寄） | [051–100](tasks/archive/tasks-051-100.md) |
| 101 | StockPriceService 開盤判斷委派 MarketZones（去重） | [101–150](tasks/archive/tasks-101-150.md) |
| 102 | 指數「當日」走勢圖顯示昨日收盤 / 漲跌 / 漲跌% | [101–150](tasks/archive/tasks-101-150.md) |
| 103 | Dashboard 資產配置面板加「美股個股穿透前 10 大」tab | [101–150](tasks/archive/tasks-101-150.md) |
| 104 | 股市分析指數下拉新增海外四指數（英國 / 德國 / 韓國 / 日本） | [101–150](tasks/archive/tasks-101-150.md) |
| 105 | 海外指數日線自動回補排程（修當日走勢「昨收」過時 → 漲跌% 失真） | [101–150](tasks/archive/tasks-101-150.md) |
| 106 | 「警示」顯示窗由近 3 個交易日收斂為「最後交易日＋前一日」 | [101–150](tasks/archive/tasks-101-150.md) |
| 107 | 國定假日休市仍抓價／觸發警示的全面修正（含 Juneteenth） | [101–150](tasks/archive/tasks-101-150.md) |
| 108 | Dashboard 台股總值偏差修正（現值與「股價」欄不同源） | [101–150](tasks/archive/tasks-101-150.md) |
| 109 | Dashboard 資產總計與「歷年資產管理」不一致（休市時繞過 liveAssets） | [101–150](tasks/archive/tasks-101-150.md) |
| 110 | 管理資產（SnapshotForm）總資產進頁閃動修正（先舊值、幾秒後才更新） | [101–150](tasks/archive/tasks-101-150.md) |
| 111 | 同一快照「資產總計」跨頁不一致根因（休市刷新把 FinMind 收盤蓋成 last-tick） | [101–150](tasks/archive/tasks-101-150.md) |
| 112 | 外幣基金台幣現值改用「即期買入」匯率，與銀行對帳單一致 | [101–150](tasks/archive/tasks-101-150.md) |
| 113 | 現金／債券／股票 資產類別三分類（Requirement 25） | [101–150](tasks/archive/tasks-101-150.md) |
| 114 | 股票「成長型／收益型」風格細分（Requirement 26） | [101–150](tasks/archive/tasks-101-150.md) |
| 115 | 債券短/中/長期細分 + 雙層圓餅同色系配色（Requirement 27） | [101–150](tasks/archive/tasks-101-150.md) |
| 116 | 高收益/收益債基金分類修正 + 基金 asset_class 人工微調（Requirement 25） | [101–150](tasks/archive/tasks-101-150.md) |
| 117 | 基金 stock_style／bond_term 逐檔 override（成長/收益型、短/中/長期）（Requirement 26/27） | [101–150](tasks/archive/tasks-101-150.md) |
| 118 | 修正美股 USD 計價成本未換匯 → total_stock_cost 低估（Requirement 3 bug fix） | [101–150](tasks/archive/tasks-101-150.md) |
| 119 | 修正上櫃台股（債券 ETF 00xxxB）「當日」分時恆空（Requirement 13 bug fix） | [101–150](tasks/archive/tasks-101-150.md) |
| 120 | 修正美股「開盤」欄恆空（NASDAQ /historical 同日區間 400）（Requirement 14 bug fix） | [101–150](tasks/archive/tasks-101-150.md) |
| 121 | 修正「最新一筆為過去日期」時被較新交易日收盤汙染（LiveAssetsOverlay per-market 閘門）（Requirement 1/9 bug fix；Req 7 Redis 收盤語意為依賴） | [101–150](tasks/archive/tasks-101-150.md) |
| 122 | 統一「過去日期股票現值」跨頁口徑為 stored（buildMergedStocks 重算加 per-market 基準日閘門）（Requirement 9 bug fix） | [101–150](tasks/archive/tasks-101-150.md) |
| 123 | 指數日線圖標出可視區間最高 / 最低點（Requirement 18） | [101–150](tasks/archive/tasks-101-150.md) |
| 124 | 股票分析走勢圖標出可視區間最高 / 最低點（Requirement 13） | [101–150](tasks/archive/tasks-101-150.md) |
| 125 | 每條警示挑選通知收件人（Requirement 23） | [101–150](tasks/archive/tasks-101-150.md) |
| 126 | 新增追蹤標的時即時觸發 10 年歷史回補（Requirement 7 bug fix） | [101–150](tasks/archive/tasks-101-150.md) |
| 127 | 警示 email 走勢圖標出最高 / 最低點（Requirement 23） | [101–150](tasks/archive/tasks-101-150.md) |
| 128 | 「補發」按鈕只補發當前市場 tab（Requirement 23） | [101–150](tasks/archive/tasks-101-150.md) |
| 129 | ETF 透視 top10 成份股歷史回補（Requirement 9 / Requirement 7） | [101–150](tasks/archive/tasks-101-150.md) |
| 130 | 警示防重複條件（Requirement 23） | [101–150](tasks/archive/tasks-101-150.md) |
| 131 | 多租戶資料層 — AppUser + owner 欄位 + Liquibase 遷移（Requirement 28） | [101–150](tasks/archive/tasks-101-150.md) |
| 132 | business-services 身分情境與 owner 過濾（Requirement 28） | [101–150](tasks/archive/tasks-101-150.md) |
| 133 | BFF reactive 安全層 + Gmail OAuth2（Requirement 28） | [101–150](tasks/archive/tasks-101-150.md) |
| 134 | 部署設定 — Nginx / vite / compose / env（Requirement 28） | [101–150](tasks/archive/tasks-101-150.md) |
| 135 | 前端登入、選單與使用者管理（Requirement 28） | [101–150](tasks/archive/tasks-101-150.md) |
| 136 | 股票分析對話框無歷史時 lazy 回補（Requirement 9 / Requirement 7 bug fix） | [101–150](tasks/archive/tasks-101-150.md) |
| 137 | BFF 身分情境收斂與代看 cookie 修正（Requirement 28） | [101–150](tasks/archive/tasks-101-150.md) |
| 138 | 觀察清單「警示」欄補上月線、年線（Requirement 14） | [101–150](tasks/archive/tasks-101-150.md) |
| 139 | 「台日韓人均 GDP 比較」圖新增日本（Requirement 18） | [101–150](tasks/archive/tasks-101-150.md) |
| 140 | 「台日韓人均 GDP 比較」圖區間由近 30 年改為近 40 年（Requirement 18） | [101–150](tasks/archive/tasks-101-150.md) |
| 141 | 匯率走勢圖區間由 5 年擴為 10 年、新增「3年」「5年」按鈕（Requirement 10） | [101–150](tasks/archive/tasks-101-150.md) |
| 142 | 匯率當日（T-0）缺失修復 — 台銀 WAF 失效改 Yahoo 中間價備援（Requirement 10） | [101–150](tasks/archive/tasks-101-150.md) |
| 143 | 資安弱點修補 — Stored XSS／設定授權／行情參數注入／個資入版控（Requirement 29） | [101–150](tasks/archive/tasks-101-150.md) |
| 144 | 延後低風險資安項修補 — 收件人跨租戶綁定／Cookie Secure 條件化／DB port 綁定／DGBAS TLS 驗證／匯入 owner-scoped 刪除（Requirement 30） | [101–150](tasks/archive/tasks-101-150.md) |
| 145 | 收件人寄信讀取端跨租戶 defense-in-depth（Requirement 30 延伸） | [101–150](tasks/archive/tasks-101-150.md) |
| 146 | MA 百分比警示條件於「警示條件」欄附換算觸發價 | [101–150](tasks/archive/tasks-101-150.md) |
| 147 | Dashboard 持股表「股價/漲跌(%)」欄 — 收盤/週末亦顯示當日漲跌 + 台股漲紅跌綠配色 | [101–150](tasks/archive/tasks-101-150.md) |
| 148 | spec×code 一致性維護 — Entity 位數/nullable 對齊實際 DB + schema 基準線澄清 | [101–150](tasks/archive/tasks-101-150.md) |
| 149 | 今日股市分析 — 每交易日 08:30 由 Claude Opus 4.8 判斷當天台股走向（Requirement 31） | [101–150](tasks/archive/tasks-101-150.md) |
| 150 | `/gdp-twse` 頁面選單／標題「股市分析」更名為「股市大盤查詢」 | [101–150](tasks/archive/tasks-101-150.md) |
| 151 | 今日股市分析結果每日自動 Email 寄送（沿用通知收件人，per-recipient 訂閱選擇）（Requirement 31） | [151–200](tasks/archive/tasks-151-200.md) |
| 152 | 資產配置建議——依個人條件與持有資產由 AI 給個人化配置建議（Requirement 32） | [151–200](tasks/archive/tasks-151-200.md) |
| 153 | 修正「當日」分時走勢盤中誤判「無當日分時資料」（bug fix） | [151–200](tasks/archive/tasks-151-200.md) |
| 154 | 修正英股「當日」分時走勢跨兩天串接（bug fix） | [151–200](tasks/archive/tasks-151-200.md) |
| 155 | 修正股票分析「當日」走勢被均線壓成平線（bug fix） | [151–200](tasks/archive/tasks-151-200.md) |
| 156 | 修正台股 / 英股「當日」分時缺收盤前尾段（Yahoo 延遲截斷）（bug fix） | [151–200](tasks/archive/tasks-151-200.md) |
| 157 | 股票分析「當日」X 軸固定延伸到收盤時間（非現在時間）（Requirement 13） | [151–200](tasks/archive/tasks-151-200.md) |
| 158 | 股票分析「當日」顯示昨收與今日漲跌（Requirement 13） | [151–200](tasks/archive/tasks-151-200.md) |
| 159 | 警示 email 每檔股票多嵌一張「當日分時走勢圖」（Requirement 23） | [151–200](tasks/archive/tasks-151-200.md) |
| 160 | 台股颱風假 / 臨時休市自動偵測（DGPA 停班公告 → 交易日曆一體休市） | [151–200](tasks/archive/tasks-151-200.md) |
| 161 | 颱風假「當日」分時圖顯示昨收平盤線 — 資料層一體休市（偵測落後補清）（bug fix） | [151–200](tasks/archive/tasks-151-200.md) |
| 162 | 颱風假不寄「今日股市分析」每日 email — 收尾寄信前重驗交易日（縱深守門） | [151–200](tasks/archive/tasks-151-200.md) |
| 163 | 花錢前先做權威即時颱風假偵測 — 避免颱風假仍白花 LLM 費用送出分析批次 | [151–200](tasks/archive/tasks-151-200.md) |
| 164 | 理財條件強化 — 生日/退休全日期、勞保勞退退休後現金流、通膨大筆花費（餵入 AI） | [151–200](tasks/archive/tasks-151-200.md) |
| 165 | 退休配置建議深化 — 退休現金流試算、建議金額化、餵入更完整持有明細（Requirement 32） | [151–200](tasks/archive/tasks-151-200.md) |
| 166 | 移除多餘的「投資年限」欄位（Requirement 32） | [151–200](tasks/archive/tasks-151-200.md) |
| 167 | 退休後拆長照前／長照後兩階段、生活費由月改年（Requirement 32） | [151–200](tasks/archive/tasks-151-200.md) |
| 168 | 退休前收入以年薪計算（年薪 − 年支出 = 每年淨投入）（Requirement 32） | [151–200](tasks/archive/tasks-151-200.md) |
| 169 | 績效比較 — 最多三檔我的股票與五大指數同圖比報酬率 | [151–200](tasks/archive/tasks-151-200.md) |
| 170 | 績效比較 — 含息（total return）報酬 ＋ 純價格/含息切換 | [151–200](tasks/archive/tasks-151-200.md) |
| 171 | 歷年資產 Excel 匯出增強（當前彙總表）＋ per-user 每日排程自動匯出 | [151–200](tasks/archive/tasks-151-200.md) |
| 172 | Dashboard KPI「股票現值／債券現值」兩卡改用圓餅圖同邏輯的資產類別歸類值（Requirement 9 / 25） | [151–200](tasks/archive/tasks-151-200.md) |
| 173 | 退休現金流試算——勞保年金依《勞工保險條例》§65-4 CPI 累計±5% 調整（Requirement 32 bug fix） | [151–200](tasks/archive/tasks-151-200.md) |
| 174 | 每日自動釘定「最新快照」日期為當日並重算資產（Requirement 35） | [151–200](tasks/archive/tasks-151-200.md) |
| 175 | 全專案稽核——修正 4 項規範違反（分層 / DTO record / 一頁一 BFF） | [151–200](tasks/archive/tasks-151-200.md) |
| 176 | 左側選單新增「公開資訊」分組 ＋ 排程列表頁（Requirement 36） | [151–200](tasks/archive/tasks-151-200.md) |
| 177 | 排程時間調整（分析 07:30→08:30、爬蟲 06:00→08:00）＋公開資訊每次輸出 JSON 供 SRPP（Requirement 31） | [151–200](tasks/archive/tasks-151-200.md) |
| 178 | 公開資訊只保留 stock 主檔個股＋總體新聞，其餘個股濾除（Requirement 31） | [151–200](tasks/archive/tasks-151-200.md) |
| 179 | 移除「新聞搜尋（web_search）」，新聞固定讀本地 `news_headline`＋顯示時點對齊 08:30（Requirement 31） | [151–200](tasks/archive/tasks-151-200.md) |
| 180 | 公開資訊爬蟲新增「台幣兌美元匯率」＋「美股主要指數收盤」快照、新聞擴增政治/國際來源、明確排除中港澳（Requirement 31） | [151–200](tasks/archive/tasks-151-200.md) |
| 181 | Dashboard 美股持股 bar 改用股票代號（原誤編為 Task 39，與下方「股價基準日規則改 per-market」重號，重編為 181） | [001–050](tasks/archive/tasks-001-050.md) |
| 182 | Dashboard 持股 bar 依損益上色（原誤編為 Task 40，與下方「SnapshotForm BFF 配息率批次抓取」重號，重編為 182） | [001–050](tasks/archive/tasks-001-050.md) |
| 183 | 規格與程式碼一致性對齊（spec-code consistency 稽核修正） | [151–200](tasks/archive/tasks-151-200.md) |
| 184 | 公開資訊爬蟲早上那輪由 08:00 改 08:20（Requirement 31） | [151–200](tasks/archive/tasks-151-200.md) |
| 185 | 公開資訊爬蟲新增「韓國股市」快照（KOSPI 大盤＋三星電子＋SK 海力士）（Requirement 31） | [151–200](tasks/archive/tasks-151-200.md) |
| 186 | 今日股市分析排程由 08:30 改 08:45（Requirement 31） | [151–200](tasks/archive/tasks-151-200.md) |
| 187 | 台股臨時休市偵測時窗由 05:00–08:45 縮短為 05:00–07:00（Requirement 7） | [151–200](tasks/archive/tasks-151-200.md) |
| 188 | 公開資訊爬蟲中午時間由 12:00 改 11:30（Requirement 31） | [151–200](tasks/archive/tasks-151-200.md) |
| 189 | 交易日曆匯出到指定路徑（JSON／Excel） | [151–200](tasks/archive/tasks-151-200.md) |
| 190 | 交易日曆匯出「每日排程自動匯出」（指定時間） | [151–200](tasks/archive/tasks-151-200.md) |
| 191 | 可設定多個「分析寄送時間」，每時段各重跑一次分析並各寄一封（限台股交易日）（Requirement 31） | [151–200](tasks/archive/tasks-151-200.md) |
| 192 | 「公開資訊」新增「爬蟲資訊查詢」頁（依日期查爬回資料 ＋ 頁面設定多個爬蟲執行時間） | [151–200](tasks/archive/tasks-151-200.md) |
| 193 | 公開資訊爬蟲增列「韓股盤中」快照，讓分析看得到當日開盤動向（Requirement 31） | [151–200](tasks/archive/tasks-151-200.md) |
| 194 | 修正英股即時報價開盤價恆為 null（`getYahooLsePrice` 誤用 `meta.regularMarketOpen`）（Requirement 7、Requirement 24） | [151–200](tasks/archive/tasks-151-200.md) |
| 195 | 修正排程列表與實作漂移（分析寫死 08:45、交易日曆匯出漏列）（Requirement 36） | [151–200](tasks/archive/tasks-151-200.md) |
| 196 | 已實現損益 Excel 匯出到指定目錄與每日排程自動匯出（Requirement 39） | [151–200](tasks/archive/tasks-151-200.md) |
| 197 | 規格 ↔ 程式碼一致性稽核與修正（文件層 ＋ 低風險死碼清理） | [151–200](tasks/archive/tasks-151-200.md) |
| 198 | 爬蟲新聞來源增列美國財經網站（CNBC / Nasdaq）（Requirement 31） | [151–200](tasks/archive/tasks-151-200.md) |
| 199 | 台灣中文新聞編輯收錄政策過濾（`EditorialNewsFilter`）（Requirement 31） | [151–200](tasks/archive/tasks-151-200.md) |
| 200 | 「股票（即時）」匯出增列即時報價與技術指標欄（Requirement 34） | [151–200](tasks/archive/tasks-151-200.md) |
| 201 | 修正 Entity 與 DB 的靜默漂移，並清理 Codex skill 副本的代換錯誤 | 本檔 ↓ |
| 202 | 公開資訊「油價金價」十年歷史曲線與 Excel 匯出（Requirement 40） | 本檔 ↓ |
| 203 | 油價金價 Excel 排程自動匯出到指定目錄（Requirement 41） | 本檔 ↓ |
| 204 | 台幣兌美元匯率 Excel 匯出與排程自動匯出到指定目錄（Requirement 42） | 本檔 ↓ |
| 205 | 未識別身分的錯誤語意修正（business-services 回 401 而非 500）（Requirement 28） | 本檔 ↓ |
| 206 | 資產總覽活頁簿第二張起每檔持股一張「過去一年股價」分頁（Requirement 34） | 本檔 ↓ |
| 207 | 公開資訊新增「台股均線突破」快照（大盤／0050／00881 對季線・年線）（Requirement 31） | 本檔 ↓ |
| 208 | 修正 business-services 容器重建後 BFF 沿用舊 IP 的 stale DNS（整站 500） | 本檔 ↓ |
| 209 | 今日交易雷達——不呼叫 AI API 的台股規則式買賣決策輔助（Requirement 43） | 本檔 ↓ |
| 210 | 今日交易雷達——逆勢抄底獨立狀態（Requirement 43 / TW_RULES_V2） | 本檔 ↓ |
| 211 | 每檔交易雷達狀態 Email 通知（Requirement 44） | 本檔 ↓ |
| 212 | 爬蟲產生檔案的輸出路徑可於頁面設定（Requirement 38） | 本檔 ↓ |
| 213 | 交易雷達避免配息型債券 ETF 誤判出場（Requirement 43 / TW_RULES_V3） | 本檔 ↓ |
| 214 | 資產總覽匯出增列 ETF 淨值與折溢價欄（Requirement 34） | 本檔 ↓ |
| 215 | ETF 淨值與折溢價每日入庫留存（Requirement 34） | 本檔 ↓ |
| 216 | 股市大盤指數日線 Excel 匯出（開/高/低/收）與排程自動匯出到指定目錄（Requirement 45） | 本檔 ↓ |
| 217 | 交易雷達判斷邏輯強化（`TW_RULES_V4`，Requirement 43 修訂） | 本檔 ↓ |
| 218 | 交易雷達通知抖動抑制（Requirement 44 修訂） | 本檔 ↓ |
| 219 | ETF 折溢價納入交易雷達（下一增量，尚未動工） | 本檔 ↓ |
| 220 | 通知評估與派送併入單一 2 秒節拍（Requirement 44 修訂） | 本檔 ↓ |
| 221 | 濾除「影響不了全球經濟／股市」的雜訊新聞（Requirement 31 / EditorialNewsFilter） | 本檔 ↓ |
| 222 | 台股個股基本面資料每日抓取與歷史落地（Requirement 46）**⛔ 已由 t266 取代，不得依本檔實作** | [tasks/t222_stock_fundamental_ingestion.md](tasks/t222_stock_fundamental_ingestion.md) |
| 223 | 交易雷達分數飽和修正、長期趨勢組與動作門檻分層（`TW_RULES_V5`，Requirement 43 修訂）**⛔ 已由 t264 取代（問題診斷仍有效、解法改為二維動作映射），不得依本檔實作** | [tasks/t223_long_term_scoring_v5.md](tasks/t223_long_term_scoring_v5.md) |
| 224 | 交易雷達基本面組接線與啟用（Requirement 43 修訂）**⛔ 已由 t267 取代（子因子定義仍有效、權重接法改為扁平），不得依本檔實作** | [tasks/t224_fundamental_factor_wiring.md](tasks/t224_fundamental_factor_wiring.md) |
| 225 | 還原權息序列支援股票分割，並排除非正收盤列（Requirement 43 修訂）**⛔ 已由 t265 取代（不擴大取數視窗），不得依本檔實作** | [tasks/t225_split_adjusted_series.md](tasks/t225_split_adjusted_series.md) |
| 226 | 新增「分批試單」動作，解決長線佳＋短線超賣無法產生買進建議的結構互斥（Requirement 43 修訂；前置 t223）**⚠ 尚未送審** | [tasks/t226_trial_buy_oversold_path.md](tasks/t226_trial_buy_oversold_path.md) |
| 227 | 匯率曝險與換匯估值納入交易雷達評分（Requirement 47；前置 t223）**⚠ 尚未送審** | [tasks/t227_fx_exposure_factor.md](tasks/t227_fx_exposure_factor.md) |
| 228 | 大盤盤中即時判斷（`TW_RULES_V6`，Requirement 43 修訂） | [t228_taiex_intraday_market_signal.md](tasks/t228_taiex_intraday_market_signal.md) |
| 264 | 交易雷達改為「趨勢品質 × 進場時機」二維決策：均值回歸、高檔轉弱出場、極端超賣不殺低、ETF 折溢價（`TW_RULES_V9`，Requirement 43 修訂；前置 t265）**已通過對抗式審查（3 輪）並完成實作** | [tasks/t264_radar_mean_reversion_and_top_exit.md](tasks/t264_radar_mean_reversion_and_top_exit.md) |
| 265 | 還原序列補齊股票分割，並新增週線 MA5（Requirement 43；**t264 的前置任務**，取代 t225）**已通過對抗式審查（3 輪）並完成實作** | [tasks/t265_split_adjusted_series.md](tasks/t265_split_adjusted_series.md) |
| 266 | 台股個股基本面資料每日抓取與歷史落地 **⛔ 已由 t292 取代** | [tasks/t266_stock_fundamental_ingestion.md](tasks/t266_stock_fundamental_ingestion.md) |
| 267 | 個股基本面因子接進交易雷達 **⛔ 已由 t292 取代** | [tasks/t267_fundamental_factor_wiring.md](tasks/t267_fundamental_factor_wiring.md) |
| 269 | 雙格式匯出的地基：`ExportDoc` 中介模型、`ExcelDocRenderer`／`JsonDocRenderer` 兩個 renderer、`DualFormatExportWriter` 雙檔落地（Requirement 55）**已通過對抗式審查（3 輪）並完成實作** | [tasks/t269_dual_format_export_foundation.md](tasks/t269_dual_format_export_foundation.md) |
| 270 | 五個單表匯出改走 `ExportDoc` 雙格式：已實現損益／匯率／指數／油價金價／交易紀錄（Requirement 55；前置 t269）**已完成實作** | [tasks/t270_dual_format_single_table_exports.md](tasks/t270_dual_format_single_table_exports.md) |
| 271 | 資產總覽與交易雷達改走 `ExportDoc` 雙格式，交易日曆改為一律兩份（Requirement 55；前置 t269）**已完成實作** | [tasks/t271_dual_format_multi_block_exports.md](tasks/t271_dual_format_multi_block_exports.md) |
| 272 | 兩份 JSON-primary 匯出加上 Excel（警示觸發、爬蟲公開資訊）＋ 排程列表頁文案收尾（Requirement 55；前置 t270／t271）**已完成實作** | [tasks/t272_dual_format_json_primary_exports.md](tasks/t272_dual_format_json_primary_exports.md) |
| 273 | 交易雷達規則回測框架：量測各規則述詞的前瞻報酬分布 vs 同標的同期間基準（Requirement 56；只建工具、不改行為，供 t291／t274／t275 使用）**已完成實作** | [tasks/t273_radar_rule_backtest_framework.md](tasks/t273_radar_rule_backtest_framework.md) |
| 274 | 季線乖離改以標的自身波動正規化，修正固定百分比門檻的結構性失效（Requirement 57；前置 t273）**⚠ 尚未送審** | [tasks/t274_volatility_normalized_thresholds.md](tasks/t274_volatility_normalized_thresholds.md) |
| 275 | 美債殖利率曲線落地 `treasury_yield_daily` 與債券 ETF 的利率因子（Requirement 58；前置 t273）**⚠ 尚未送審** | [tasks/t275_treasury_yield_curve_and_bond_factor.md](tasks/t275_treasury_yield_curve_and_bond_factor.md) |
| 276 | 把已落地但未使用的技術指標與成交量納入評分（Requirement 59）**⛔ 已由 t291 取代，不得依本檔實作** | [tasks/t276_unused_indicators_and_volume.md](tasks/t276_unused_indicators_and_volume.md) |
| 277 | 交易雷達的短線與中長線雙軌建議（Requirement 60）**⛔ 已由 t291 取代，不得依本檔實作** | [tasks/t277_dual_horizon_recommendations.md](tasks/t277_dual_horizon_recommendations.md) |
| 278 | 台股歷史估值回補，推翻 Requirement 46 的「預設不實作」（Requirement 61；前置 t266） **⚠ 尚未送審** | [tasks/t278_twse_valuation_history_backfill.md](tasks/t278_twse_valuation_history_backfill.md) |
| 279 | 清除 `stock_price_history` 台股 175 列非正收盤，並在 fetch／收盤校正／寫入／Redis／DB 五處擋住復發（Requirement 62）**已通過對抗式審查（3 輪）** | [tasks/t279_nonpositive_close_price_cleanup.md](tasks/t279_nonpositive_close_price_cleanup.md) |
| 280 | 爬蟲資訊查詢頁補上手動匯出：「立即匯出」（只重產檔案）＋「立即抓取並匯出」（完整跑一輪），兩者都產 JSON ＋ Excel 兩份（Requirement 63） | [tasks/t280_crawler_manual_export.md](tasks/t280_crawler_manual_export.md) |
| 281 | 交易雷達匯出補上週線 MA5 與走勢圖指標選單的 14 個值（大盤總覽 20→35 欄、個股決策 35→50 欄；Requirement 43／48）**已通過對抗式審查（4 輪）** | [tasks/t281_radar_export_indicators.md](tasks/t281_radar_export_indicators.md) |
| 282 | 九個匯出頁的手動匯出結果提示改為報出 JSON 與 Excel 兩個落點（t270.3／t271.5／t272.3／t272.3.1 漏做，使用者回報「只匯出 excel」；Requirement 55） | [tasks/t282_dual_export_runnow_message.md](tasks/t282_dual_export_runnow_message.md) |
| 283 | 交易雷達頁首「匯出 Excel」在下載之外，同時落一份 JSON ＋ Excel 到伺服器輸出目錄（Requirement 48／55） | [tasks/t283_radar_manual_export_dual_format.md](tasks/t283_radar_manual_export_dual_format.md) |
| 284 | 交易雷達個股收合列補顯示週線 MA5，並守住 Excel／JSON 匯出既有 MA5 欄（Requirement 43／48） | [tasks/t284_trading_radar_ma5_summary.md](tasks/t284_trading_radar_ma5_summary.md) |
| 285 | 股市大盤查詢頁日線圖加畫週線 MA5（四線→五線），匯出（xlsx／json）加第六欄「週線MA5」並回看 30 日避免前 4 列空白（Requirement 18／45） | [tasks/t285_index_daily_weekly_ma5.md](tasks/t285_index_daily_weekly_ma5.md) |
| 286 | 大盤指數日線匯出補齊月線MA20／季線MA60／年線MA240（6 欄→9 欄），回看視窗由 30 日放大為 400 日以支撐 MA240 的 239 個交易日（Requirement 45） | [tasks/t286_index_export_all_four_ma.md](tasks/t286_index_export_all_four_ma.md) |
| 287 | 股市大盤排程匯出支援多時間點與多指數 checkbox（Requirement 45） | [tasks/t287_index_export_multi_time_market.md](tasks/t287_index_export_multi_time_market.md) |
| 288 | 股市大盤查詢頁的日線圖下方新增每日成交量柱狀子圖（台股走 TWSE FMTQIK 成交金額／股數、海外走 Yahoo `volume`；Requirement 18） | [tasks/t288_index_daily_volume_chart.md](tasks/t288_index_daily_volume_chart.md) |
| 289 | 大盤指數日線匯出（Excel／JSON）加「成交股數」「成交金額」兩欄，九欄擴為十一欄（Requirement 45） | [tasks/t289_index_export_volume.md](tasks/t289_index_export_volume.md) |
| 290 | 台股官方收盤逐檔對帳與盤後顯示防呆（Requirement 7） | [tasks/t290_tw_official_close_reconciliation.md](tasks/t290_tw_official_close_reconciliation.md) |
| 291 | 今日交易雷達改為一週至六個月的獲利機會雙軌評分，納入完整技術指標、量能、美股科技、台美公開資訊與債券 ETF 匯率（Requirement 43／59／60） | [tasks/t291_trading_radar_profit_horizon.md](tasks/t291_trading_radar_profit_horizon.md) |
| 292 | 今日交易雷達個股納入財報／基本面與全市場產業營收發展（`TW_RULES_V11`，取代 t266／t267） | [tasks/t292_radar_fundamental_industry.md](tasks/t292_radar_fundamental_industry.md) |
| 297 | 交易雷達美股 live K 併入判定改用標的市場時區（`TW_RULES_V12` 波，Requirement 43） | [tasks/t297_radar_us_live_row_market_zone.md](tasks/t297_radar_us_live_row_market_zone.md) |
| 298 | 規則引擎因子同源修正（BIAS／W%R／OSC）與 `TW_RULES_V12` 升版（Requirement 43） | [tasks/t298_radar_factor_dedup_osc_v12.md](tasks/t298_radar_factor_dedup_osc_v12.md) |
| 299 | 極端時機門檻增加季線乖離自身分位替代路徑（`TW_RULES_V12` 波，Requirement 43） | [tasks/t299_radar_bias_percentile_extremes.md](tasks/t299_radar_bias_percentile_extremes.md) |
| 300 | 近似 ROE 標度放緩（斜率 5 → 10；`TW_RULES_V12` 波，Requirement 43／46） | [tasks/t300_fundamental_roe_scale.md](tasks/t300_fundamental_roe_scale.md) |
| 301 | 交易雷達通知冷卻：同一 setting 同一狀態 60 分鐘內不重複寄送（Requirement 44） | [tasks/t301_radar_notification_cooldown.md](tasks/t301_radar_notification_cooldown.md) |
| 302 | 通知評估效能：每輪每市場只組一次大盤、市場脈絡 bounded 查詢、通知路徑不抓新聞（Requirement 43／44） | [tasks/t302_radar_notification_market_once.md](tasks/t302_radar_notification_market_once.md) |
| 303 | 匯率分位 request 內 memoize（Requirement 43／47） | [tasks/t303_radar_fx_memoize.md](tasks/t303_radar_fx_memoize.md) |
| 304 | 指數 KD 遞迴整併：taiexKd／nasdaqKd 刪除、統一走 kdSeriesAsc 單趟（Requirement 43） | [tasks/t304_indicator_kd_recursion_unify.md](tasks/t304_indicator_kd_recursion_unify.md) |
| 305 | 雙軌因子貢獻一次計算（輸出逐位不變）＋整波部署驗證（Requirement 43） | [tasks/t305_radar_factor_compute_once.md](tasks/t305_radar_factor_compute_once.md) |
| 306 | 台幣兌美元匯率新增兆豐銀行備援層（台銀 → 兆豐 → Yahoo → FinMind，Requirement 10） | [tasks/t306_exchange_rate_mega_bank_fallback.md](tasks/t306_exchange_rate_mega_bank_fallback.md) |
| 307 | 交易雷達估值合成、資產輪廓、市場別公開資訊與版本化配息事件（Requirement 65） | [tasks/t307_radar_valuation_profile_events.md](tasks/t307_radar_valuation_profile_events.md) |
| 308 | 交易雷達可成交台美回測、時間外推校準與 `TW_RULES_V13` 發布判定（Requirement 56／65） | [tasks/t308_radar_walk_forward_v13_calibration.md](tasks/t308_radar_walk_forward_v13_calibration.md) |
| 309 | 交易雷達 decision-time 資料時效、證據信心與買進完整性閘門（Requirement 65） | [tasks/t309_radar_evidence_integrity_confidence.md](tasks/t309_radar_evidence_integrity_confidence.md) |
| 311 | 交易紀錄明細列雙擊開啟股票分析圖（比照已實現損益／今日交易雷達既有模式，Requirement 49） | [tasks/t311_transaction_double_click.md](tasks/t311_transaction_double_click.md) |
| 312 | external-materials-service 最新報價 host 對外唯讀查詢 API（`/api/quotes`，Requirement 66） | [tasks/t312_external_materials_public_quote_api.md](tasks/t312_external_materials_public_quote_api.md) |
| 313 | `market:status` Redis key 描述訂正：改記載現況（即時運算、無快取，Requirement 7） | [tasks/t313_market_status_no_redis_cache.md](tasks/t313_market_status_no_redis_cache.md) |
| 314 | 交易雷達 V13 joint-fold 無洩漏與回測成本邊界閉環（Requirement 65） | [tasks/t314_radar_v13_joint_fold_integrity.md](tasks/t314_radar_v13_joint_fold_integrity.md) |
| 315 | 交易雷達 PE／PB／殖利率逐分量 provenance UI 與匯出閉環（Requirement 65） | [tasks/t315_radar_valuation_provenance_surfaces.md](tasks/t315_radar_valuation_provenance_surfaces.md) |
| 316 | 交易雷達 Treasury freshness、完整回歸與真實 holdout 結案（Requirement 65） | [tasks/t316_radar_v13_validation_closure.md](tasks/t316_radar_v13_validation_closure.md) |
| 317 | 股市大盤查詢 Docker 可呼叫單一唯讀圖表 API（9 市場 × 8 期間，Requirement 67） | [tasks/t317_public_market_index_api.md](tasks/t317_public_market_index_api.md) |
| 318 | 交易雷達 Treasury 未來曲線 fail-closed（Requirement 65） | [tasks/t318_treasury_future_curve_guard.md](tasks/t318_treasury_future_curve_guard.md) |
| 319 | 收盤 provenance 白名單只界定 verified，不得當技術序列納入判準（台股全數「今日不交易」修正，Requirement 65） | [tasks/t319_radar_indicator_series_provenance_scope.md](tasks/t319_radar_indicator_series_provenance_scope.md) |
| 320 | 交易雷達新增「即時折溢價」欄，表格／JSON／Excel 三處揭露，與既有完成日折溢價並存（Requirement 43） | [tasks/t320_radar_live_etf_premium_column.md](tasks/t320_radar_live_etf_premium_column.md) |
| 321 | 濾除「中國＋他國國名」的個人刑案雜訊：`EditorialNewsFilter` 規則③收緊 `GEO_REGION` disjunct（Requirement 31） | [tasks/t321_china_branch_personal_crime.md](tasks/t321_china_branch_personal_crime.md) |
| 322 | 修正股利歷史全數 500 與 append-only evidence JDBC `Instant`／`TIMESTAMPTZ` 管線失效（Requirement 13／65） | [tasks/t322_dividend_history_instant_binding_500.md](tasks/t322_dividend_history_instant_binding_500.md) |
| 323 | 交易雷達美股大盤量能接線：`buildUsMarket()` 補上既有 IXIC 量能，停止線上與回測分岔（Requirement 64） | [tasks/t323_radar_us_market_volume_wiring.md](tasks/t323_radar_us_market_volume_wiring.md) |
| 324 | 交易雷達證據覆蓋缺口盤點（實測待辦；各子項動工時另開任務檔） | [tasks/t324_radar_evidence_coverage_backlog.md](tasks/t324_radar_evidence_coverage_backlog.md) |
| 325 | 最新全資產唯讀 API 接入 Nginx 9090（Requirement 68） | [tasks/t325_external_api_gateway_9090.md](tasks/t325_external_api_gateway_9090.md) |
| 326 | 最新資產每日匯出支援多個執行時間（Requirement 69） | [tasks/t326_latest_asset_export_multi_schedule.md](tasks/t326_latest_asset_export_multi_schedule.md) |
| 327 | USD/TWD 交易時段每 2 秒即期＋近一年歷史唯讀 API（port 9090，Requirement 70） | [tasks/t327_usd_twd_local_api_9090.md](tasks/t327_usd_twd_local_api_9090.md) |
| 328 | Nginx 9090 統一外部 API 與 Tailscale 私網 HTTPS（Requirement 66／67） | [tasks/t328_nginx_tailscale_api_gateway_9090.md](tasks/t328_nginx_tailscale_api_gateway_9090.md) |
| 329 | 「爬蟲資訊查詢」頁公開觸發重新搜尋 API（Nginx 9090 第六條路由，Requirement 71） | [tasks/t329_crawler_public_rescan_api.md](tasks/t329_crawler_public_rescan_api.md) |
| 330 | 油價金價每日匯出支援多個執行時間（Requirement 72） | [tasks/t330_commodity_export_multi_schedule.md](tasks/t330_commodity_export_multi_schedule.md) |
| 331 | 已實現損益每日匯出支援多個執行時間（Requirement 73） | [tasks/t331_realized_gain_export_multi_schedule.md](tasks/t331_realized_gain_export_multi_schedule.md) |
| 332 | 海外指數日線的「新鮮度」判準與交易雷達同源（Requirement 75） | [tasks/t332_index_daily_freshness_alignment.md](tasks/t332_index_daily_freshness_alignment.md) |
| 333 | 交易雷達證據缺口複驗與剩餘缺口登記（2026-08-15 基準；只登記、不實作） | [tasks/t333_radar_gap_backlog_2026_08_15.md](tasks/t333_radar_gap_backlog_2026_08_15.md) |
| 334 | 美股歷史估值序列由已入庫官方財報推導落地（`SEC_DERIVED` provider，Requirement 74） | [tasks/t334_us_valuation_history_derivation.md](tasks/t334_us_valuation_history_derivation.md) |
| 335 | 交易雷達大盤風險卡片改為「台股」「美股」兩個分頁（Requirement 76） | [tasks/t335_radar_us_market_card_tabs.md](tasks/t335_radar_us_market_card_tabs.md) |
| 336 | 收斂 IXIC 均線 MA5／20／60／240 的兩條算術路徑——交易雷達美股組改用 BigDecimal 精確路徑（Requirement 77） | [tasks/t336_ixic_ma_arithmetic_convergence.md](tasks/t336_ixic_ma_arithmetic_convergence.md) |
| 337 | 今日股市分析改由本機規則引擎產生，LLM 成本歸零且可隨時切回（Requirement 78） | [tasks/t337_local_market_analysis_engine.md](tasks/t337_local_market_analysis_engine.md) |
| 338 | 今日股市分析與資產配置建議的 Docker 外部唯讀 API（Nginx 9090 第七、八條路由，Requirement 79） | [tasks/t338_market_analysis_advice_public_api.md](tasks/t338_market_analysis_advice_public_api.md) |
| 339 | 資產配置建議三態引擎切換：完全本機／部分打 API／現行全 LLM（Requirement 80） | [tasks/t339_portfolio_advice_engine_switch.md](tasks/t339_portfolio_advice_engine_switch.md) |
| 340 | 油價金價交易時段每分鐘即時報價、收盤後 5 分鐘取回收盤價校正（Requirement 81；規劃階段原為 335，因與其他尚未落地的 worktree 撞號主動避讓至 337、再至 340，335／336／337／338／339 皆已被佔用） | [tasks/t340_commodity_intraday_live_quote.md](tasks/t340_commodity_intraday_live_quote.md) |
| 341 | 資產配置建議「股票」「信託基金」子類別細分：成長型／收益型／短中長期債（Requirement 82） | [tasks/t341_portfolio_advice_subclass_breakdown.md](tasks/t341_portfolio_advice_subclass_breakdown.md) |
| 342 | 交易雷達美股大盤接上量價環境因子，跨市場改為「不適用」語意，`RULE_VERSION` 升 `TW_RULES_V14`（Requirement 83；規劃階段原為 339／R80，再避讓至 341／R82，因與其他尚未落地的 worktree 撞號主動避讓） | [tasks/t342_radar_us_market_volume_regime_wiring.md](tasks/t342_radar_us_market_volume_regime_wiring.md) |
| 344 | 資產配置建議的標的層級再平衡：本機／hybrid 檔位把加減碼金額落到具體銀行／基金／個股（Requirement 84；規劃階段原為 342／R83，因 main 已佔用而避讓；Task 343 讓給另一個在途 worktree） | [tasks/t344_portfolio_advice_holding_level_rebalance.md](tasks/t344_portfolio_advice_holding_level_rebalance.md) |
| 345 | 股利歷史重複事件去重：同除息日同金額不得因發放日 metadata 分裂成兩列，年度小計與還原權息不再灌水（Requirement 13／65 bug fix） | [tasks/t345_dividend_history_duplicate_event_dedup.md](tasks/t345_dividend_history_duplicate_event_dedup.md) |
| 346 | 台股集中／櫃買市場指數與上市櫃個股兩分鐘 Redis 報價契約（Requirement 85） | [tasks/t346_tpex_market_index.md](tasks/t346_tpex_market_index.md) |
| 347 | 今日交易雷達 9090 公開 API 與 OpenAPI 3 全覆蓋硬規則（Requirement 86） | [tasks/t347_trading_radar_public_api_openapi.md](tasks/t347_trading_radar_public_api_openapi.md) |
| 348 | 股票分析 popup 新增台股行情五檔頁籤（Requirement 87；Task 347 已由在途 worktree 占用） | [tasks/t348_stock_analysis_quote_depth.md](tasks/t348_stock_analysis_quote_depth.md) |
| 349 | 台股 ETF 官方折溢價改為每 2 分鐘更新，並以 nullable 欄位併入 Docker 外部 quote API（Requirement 88；Task 348 由其他在途 worktree 保留） | [tasks/t349_etf_premium_quotes_2min.md](tasks/t349_etf_premium_quotes_2min.md) |
| 350 | 台股 Redis 最新價日期時間單調守門、MIS 批次重試與每輪可觀測統計（Requirement 89） | [tasks/t350_tw_quote_cache_freshness.md](tasks/t350_tw_quote_cache_freshness.md) |
| 351 | 交易日曆三年查詢窗口與今年明年雙年度匯出（Requirement 37） | [tasks/t351_trading_calendar_year_window.md](tasks/t351_trading_calendar_year_window.md) |
| 352 | Docker Linux 富邦證券庫存同步至管理者最新資產快照（Requirement 90） | [tasks/t352_fubon_linux_inventory_sync.md](tasks/t352_fubon_linux_inventory_sync.md) |
| 353 | 富邦台股逐檔 intraday quote 作為可切換的 LIVE provider（Requirement 91） | [tasks/t353_fubon_tw_marketdata_provider.md](tasks/t353_fubon_tw_marketdata_provider.md) |
| 354 | TWSE 未公布時以 DGPA 完整行事曆暫行推導台股交易日（Requirement 37） | [tasks/t354_dgpa_calendar_fallback.md](tasks/t354_dgpa_calendar_fallback.md) |
| 355 | 股票分析價格圖新增日 K／週 K（Requirement 92） | [tasks/t355_stock_analysis_daily_weekly_candles.md](tasks/t355_stock_analysis_daily_weekly_candles.md) |
| 356 | 今日交易雷達拆為「一周」「1周~1月」「1月~6月」三軌，並把日K 棒與週K 重新計算的指標納入評分（Requirement 93） | [tasks/t356_radar_three_horizon_weekly_k.md](tasks/t356_radar_three_horizon_weekly_k.md) |
| 357 | 配息事件的四個日期各自獨立落地與顯示：除息／除權／發放股息／發放股權，格式 `yyyy-MM-dd`（Requirement 94） | [tasks/t357_dividend_four_dates.md](tasks/t357_dividend_four_dates.md) |
| 358 | 今日股市分析頁面改為分類分點呈現：技術面／量能面／美股連動／籌碼面各自獨立、台股與美股分開區塊（Requirement 95） | [tasks/t358_market_analysis_structured_sections.md](tasks/t358_market_analysis_structured_sections.md) |
| 359 | 股票分析彈窗「持股明細」改接既有 ETF 成分股 API、以圓餅圖呈現（比照 Dashboard 台股個股穿透）（Requirement 13） | [tasks/t359_etf_holdings_chart.md](tasks/t359_etf_holdings_chart.md) |
| 360 | 修正交易雷達 J 值因子的極性——評分鏈誤用畫面慣例 `J9 = 3D − 2K`，使「跌深承接」變成「追漲加分」；改用標準 `3K − 2D` 並升版 `TW_RULES_V16`（Requirement 96） | [tasks/t360_radar_j_polarity_fix.md](tasks/t360_radar_j_polarity_fix.md) |
| 361 | FinMind 配息抓取視窗停止以 `date` 欄代理除權息日：請求不送 `end_date`，上界改由 client 端以 `anchorDate` 過濾，消除約 6 天寬的滑動漏抓盲區（Requirement 97） | [tasks/t361_dividend_fetch_window_anchor.md](tasks/t361_dividend_fetch_window_anchor.md) |
| 362 | 交易雷達 evidence 面板明示零權重資料源「不進評分」——九項市場數值特徵與美債殖利率，涵蓋前端／匯出／公開 OpenAPI 契約；不移除任何數值、不升版（Requirement 98） | [tasks/t362_radar_zero_weight_disclosure.md](tasks/t362_radar_zero_weight_disclosure.md) |

> **註：Task 229–263、268、293–296 以各自任務檔為準。**

> **Task 222–225 已全部被 t264／t265／t292 取代（見上表），保留僅作為決策記錄，一律不得依其實作。**
>
> 原狀態：全部尚未通過 `/spec-review` 閘門。
>
> 審查歷程：全範圍三輪 5 → 5 → 7，t222 單獨一輪 6（門檻 8）。各任務檔上方的「待解問題」區塊記錄了已查證屬實的未解問題。
>
> - **Task 222**（6/10）：三個 Critical——驗證段的 `mvn -pl` 指令在本專案無法執行（無 root pom，三個模組各自獨立）；business-services 未啟用 actuator 故健康檢查回 500；`security_type` 免設定頁的例外聲明被同一 entity 上結構同構的 `stock_style` 反證（後者有完整設定表＋seed＋端點＋前端頁）。另有「資料驅動 ETF 判定」未指名資料來源等 6 個 Major。
> - **Task 223／224／225**（7/10）：t225 兩個 Critical——`DistributionAdjustedPriceService.adjust()` 的 early-return 會使無除權息紀錄的標的（如 2327，全庫兩筆真分割之一）永遠偵測不到分割；驗證段查詢了不存在的 JSON 欄位而使迴歸判準恆為通過。t223 有四個 Major（85 門檻推導錯誤、over-fetch 未指定、`complete()` 閘門、與逆勢抄底軌重疊未處理）。
>
> **上表 Task 223／226／227 的「未通過／尚未送審」標記與 `TradingRadarRuleEngine.java` 現況不一致**（該檔 `RULE_VERSION` 實際已是 `TW_RULES_V8`、Task 264 將升為 `TW_RULES_V9`，且 `Action.TRIAL_BUY`、`fxPercentile` 等皆已存在於程式碼）——這是 Task 228 合併 `origin/main` 時發現的既有 spec／程式碼漂移。**Task 223 的部分已由本次的取代關係處理（223 → t264）**；226／227 的標記漂移仍未處理。

---

## 尚未歸檔的任務（Task 201 起）

### Task 201：修正 Entity 與 DB 的靜默漂移，並清理 Codex skill 副本的代換錯誤

背景：Task 200.x 把 schema-only 鏡像 `db/schema.sql` 納入版控後，過去因「權威 dump 不在版控、無法複驗」而被擱置的 entity 位數爭議首次可自 repo 查證，故一併收尾。同時修正 `.agents/`（Codex 版 skill 副本）機械式 `Claude`→`Codex` 代換造成的錯誤路徑／名稱。

- [x] 201.1 **`StockPriceHistory.java` 對齊實際 DB**：`openPrice`／`highPrice`／`lowPrice`／`closePrice` 的 `precision` 由 `20` 改回 `15`；`volume` 的 `@Column(nullable = false)` 改為 `@Column`（DB 為 `bigint` 可空）。依據 `db/schema.sql`：`close_price numeric(15,4) NOT NULL`、`open/high/low_price numeric(15,4)` 可空、`volume bigint` 可空。此為 Task 148.1 反向操作的收尾（148.1 誤照永不執行的 `v1.0.0-initial-schema.sql` 修改）。`ddl-auto: none` 下**零 runtime 行為變更**，價值在於未來若啟用 `validate` 不會誤 fail。
- [x] 201.2 **`.agents/` 代換錯誤修正**（共 5 處、4 類；以 `.agents/` 與 `.claude/skills/` 的 tree-diff 窮舉確認範圍，僅 5 個檔案有差異）：
        `commit-merge-push/SKILL.md:3,37` 分支前綴 `Codex/*` → `codex/*`（實際分支為小寫，如 `codex/spec-code-document-alignment`）；
        `commit-merge-push/SKILL.md:56` `.Codex/skills/**` → `.agents/skills/**`（Codex 版 skill 的實際位置）；
        `run-stack/SKILL.md:27` `.Codex/worktrees/` → `.claude/worktrees/`（實際 worktree 目錄，與使用哪個 agent 無關，該路徑原本不存在）；
        `ag/skills/TKT.{1.init,2.review,3.fix}/SKILL.md` 的 `Co-Authored-By: Codex Sonnet...` → `Co-Authored-By: Codex ...`（Sonnet 是 Claude 的模型名，「Codex Sonnet」不存在）。
- [x] 201.3 **spec 同步**：`design.md` 的 schema 基準線段落，`StockPriceHistory` 由「已知靜默漂移、待後續任務處理」改記為「已對齊（Task 201）」；`tasks.md` Task 148.1 註記標明已由本任務收尾。
- [x] 201.4 **驗證**：`mvn compile` 通過；`.agents/` 殘留掃描 `\.Codex|Codex/|Codex Sonnet` 命中數為 0；entity 四個 OHLC 位數與 `volume` nullable 與 `db/schema.sql` 逐欄比對一致。
- [ ] 201.5 commit ＋ 兩段式 merge。

---

### Task 202：公開資訊「油價金價」十年歷史曲線與 Excel 匯出（Requirement 40）

**需求對應：** Requirement 40「公開資訊『油價金價』十年歷史曲線與 Excel 匯出」。

**背景：** 使用者要求在「公開資訊」下新增「油價金價」頁，收集最近 10 年每日油價、金價畫成曲線圖，並可指定時間區間與存檔目錄匯出成單一檔案。標的採國際盤美元計價（WTI `CL=F`／Brent `BZ=F`／COMEX 黃金 `GC=F`），因中油零售油價與台銀黃金存摺的公開歷史不足十年。匯出目錄採瀏覽器 `showSaveFilePicker`（本專案首次使用 File System Access API），與 R34/37/39「後端寫進容器目錄」的排程留存模型分工不同。

- [x] 202.1 **spec**：`requirements.md` 新增 Requirement 40；`design.md` 新增「Requirement 40（Task 202）」設計段（來源表／資料模型／分層／匯出／雙 Y 軸圖表／端點／檔案清單）；`tasks.md` 本任務。
- [x] 202.2 **DB changeset**：`v1.60.0-commodity-price-history.sql` 建 `commodity_price_history`（`commodity_code`／`price_date`／`close_price`，`uq_commodity_price_code_date` UNIQUE ＋ `idx_commodity_price_code_date`），冪等寫法（`CREATE TABLE IF NOT EXISTS`／`IF NOT EXISTS` 索引）；`db.changelog-master.yaml` 尾端註冊。
- [x] 202.3 **`CommodityFetchClient`**：curl 子程序 ＋ 短 UA 抓 Yahoo chart；`fetchRange(code, start, end)` 回 `List<CommodityBar(date, close)>`；`null` close 略過；例外吞掉回空 List 並 `log.warn`。
- [x] 202.4 **落庫與回補**：`StockSourceQuery` 增 `upsertCommodityPrice`（select-then-update/insert）／`findMaxCommodityDate`／`findMinCommodityDate`；`HistoricalBackfillService` 增 `backfillCommodity`（增量）／`backfillCommodityFrom`（強制），`startupBackfill` 追加三標的補滿十年。
- [x] 202.5 **`CommodityPricePoller`**：`@Scheduled(cron="0 30 6 * * MON-SAT", zone="Asia/Taipei")` 每日增量補前一交易日收盤（紐約收盤＝台北隔日凌晨）；`commodity.enabled` flag；單一標的失敗不影響其他。
- [x] 202.6 **ext internal 端點**：`InternalPriceController` 增 `POST /internal/backfill/commodity` 與 `/commodity-from`。
- [x] 202.7 **backend model／repository**：`CommodityPriceHistory` entity（無 owner 欄位、全域公開）＋ repository（區間查詢／`findMaxPriceDate`／`deleteByCommodityCodeAndPriceDateBefore`）。
- [x] 202.8 **backend service／controller**：`HistoricalDataService` proxy 兩支 ext 端點（`.block()` ＋ try/catch 降級）並於 `purgeOldHistory` 追加十年清理；`MarketDataController` 增 `GET /api/market-data/commodity`、`POST /commodity/refresh`、`GET /commodity/export`（`start`/`end` 驗證，`start > end` 回 400）。
- [x] 202.9 **Excel 匯出**：`ExcelExportService.exportCommodityPrices(start, end)` ＋ `writeCommoditySheet`，`TreeMap` 三序列 outer join、缺值留空、日期文字格式、價格 `num4`。
- [x] 202.10 **BFF**：新增 `bff/.../commodityprice/CommodityPriceBffController`（`GET /` 先 refresh 再回十年三序列、`POST /refresh`、`GET /export` passthrough byte[]）；外部失敗降級空序列。
- [x] 202.11 **前端**：`api/index.js` 增 `commodityPrice` 命名空間；`CommodityPriceView.vue`（三 KPI 卡／雙 Y 軸 ECharts 折線／區間快切／匯出對話框含日期區間 ＋ `showSaveFilePicker` 另存、不支援則退回 anchor 下載）；`router/index.js` 與 `App.vue`「公開資訊」選單加入口。
- [x] 202.12 **排程列表登錄**：`SchedulePublicBffController.JOBS` 補「油價金價 每日回補」項目。
- [x] 202.13 **建置與部署驗證**：`--no-cache` 重 build business／ext／bff／frontend 並 `--force-recreate`（四者皆 healthy）。Liquibase `v1.60.0-commodity-price-history` EXECUTED、表與 `uq_commodity_price_code_date`／`idx_commodity_price_code_date` 到位。`POST /api/market-data/commodity/refresh` 回補 `{WTI:2513, BRENT:2515, GOLD:2513}`，DB 三標的皆 `2016-07-18 ~ 2026-07-17`（滿十年）。`GET /api/market-data/commodity?start=&end=` 值與 Yahoo 原始 API 逐日核對一致（2026-07-17：WTI 82.49／Brent 88.1／GOLD 4012.7）；`start > end` 回 400。匯出端點產 `油價金價_20260701_20260717.xlsx`（Content-Disposition UTF-8 中文檔名正確）：單張工作表四欄、三序列依日期 outer join 對齊、7/3–7/5（美國國慶＋週末）整列缺無誤補。前端 chunk `CommodityPriceView-*.js` 已入 nginx 且含 `showSaveFilePicker`、`index-*.js` 含 `bff/commodity-price`。**頁面曲線與另存對話框待使用者於瀏覽器登入後目視確認**（BFF 端點需 Google OAuth session，無法免登入驗證）。
- [ ] 202.14 commit ＋ 兩段式 merge。

---

### Task 203：油價金價 Excel 排程自動匯出到指定目錄（Requirement 41）

**需求對應：** Requirement 41「油價金價 Excel 排程自動匯出到指定目錄」。

**背景：** 使用者於 Task 202 完成後追加要求「這個匯出，我要能排程匯到指定目錄」。Task 202 的匯出為瀏覽器 `showSaveFilePicker` 一次性另存；本任務補上 R34／37／39 那套「後端寫進容器目錄＋`el-tree` 目錄選擇器＋每日排程」模型，兩者並存。

**關鍵差異（勿照抄 R39）：** 油金價為全域公開行情（`commodity_price_history` 無 `owner_user_id`、無 `@Filter`），背景 cron 產檔**不需**手動 `enableFilter`，直接呼叫 `exportCommodityPrices(start, end)` 即可——排程設定 per-user，但資料本身全域（同 R37 交易日曆）。另新增「匯出範圍」`range_months`，使排程產出隨時間滾動。

- [x] 203.1 **spec**：`requirements.md` 新增 Requirement 41；`design.md` 新增「Requirement 41（Task 203）」設計段（與既有三套排程的定位對照／資料模型／滾動範圍／寫檔安全／排程機制／端點／檔案清單）；`tasks.md` 本任務。
- [x] 203.2 **DB changeset**：`v1.61.0-commodity-export-schedule.sql` 建 `commodity_export_schedule`（owner UNIQUE ＋ 時分／range_months CHECK），冪等寫法；`db.changelog-master.yaml` 註冊。
- [x] 203.3 **backend model／repository／dto**：`CommodityExportSchedule` entity（`@Filter(ownerFilter)`）＋ repository ＋ `CommodityExportDto`。
- [x] 203.4 **`CommodityExportScheduleService`**：設定 CRUD、`resolveDir` 路徑驗證（拒 `..` 與絕對路徑）、`writeAtomically`（tmp ＋ ATOMIC_MOVE）、滾動區間計算、每分鐘 tick（`now >= 時分` ＋ 當日 guard ＋ `AtomicBoolean` 防重入）、`ApplicationReadyEvent` 自癒、run-now（不動 guard）。
- [x] 203.5 **`CommodityExportController`**：`GET/PUT /api/commodity-export/schedule`、`POST /api/commodity-export/run-now`。目錄列舉沿用既有 `/api/export-schedule/browse`，不新增第四份實作。
- [x] 203.6 **BFF**：`CommodityPriceBffController` 增 schedule GET/PUT、run-now、browse 四支 passthrough。
- [x] 203.7 **前端**：`api/index.js` `commodityPrice` 增 4 支；`CommodityPriceView.vue` 增「排程自動匯出」設定卡（開關／每日時間／匯出範圍／資料夾樹選擇器／立即匯出／上次執行結果）。
- [x] 203.8 **排程列表登錄**：`SchedulePublicBffController.JOBS` 補「油價金價匯出 每日匯出排程檢查」。
- [x] 203.9 **建置與部署驗證**：`--no-cache` 重 build business／bff／frontend 並 `--force-recreate`（皆 healthy）。Liquibase `v1.61.0-commodity-export-schedule` EXECUTED、表與 `uq_commodity_export_schedule_owner` ＋ 三個 CHECK 到位。驗證項目：**設定 CRUD** — GET 無設定回預設 `{enabled:false, runHour:8, outputSubpath:"input", rangeMonths:null, baseDir:"/home/steven"}`，PUT 正確 upsert；**輸入驗證** — `outputSubpath:"../../etc"` 回 400、`rangeMonths:999` 回 400；**run-now 實際落檔** — 回 `{path:"/home/steven/input/oilgold/油價金價_1_20260718.xlsx", sizeBytes:5569}`，主機端 `/Users/steven/input/oilgold/` 確實出現同大小檔案（volume 對映正確）、無 `.tmp` 殘留；**滾動區間正確** — `rangeMonths:3` 產出 62 筆、`2026-04-20 ~ 2026-07-17`（4/18–19 為週末故首筆落 4/20），表頭四欄與手動匯出一致；**背景排程實際觸發** — 設 07:30 且當日未跑，08:20:00 UTC 的 tick 自動補跑成功（log：`油價金價排程匯出成功 owner=1 → …`），`last_run_date=2026-07-18` guard 已設、`last_run_status` 記錄成功路徑；**前端** — `CommodityPriceView-rf12F3CP.js` 含 `rangeMonths`、`index-C3oONiK9.js` 含三支 `commodity-price/export/*` 路徑；**排程列表** — 運行中 BFF jar 的 `SchedulePublicBffController.class` 含「油價金價」兩筆登錄（回補＋匯出）。驗證後已將測試排程 `enabled` 設回 `false`，避免未經使用者要求的每日自動產檔。**設定卡 UI 與目錄樹選擇器待使用者於瀏覽器登入後目視確認**。
- [x] 203.10 **對抗式審查修正（`el-select` 綁 null 顯示失真）**：五視角並行審查（排程正確性／租戶隔離／路徑安全／API 契約／前端行為）共 8 個發現，經每個發現 2 名獨立懷疑者對抗式驗證後 6 個被駁回、2 個成立——且為同一缺陷由 api-consistency 與 frontend-behavior 兩視角各自獨立發現：`rangeMonths` 的「全部十年」選項以 `null` 為值，而 element-plus 2.13.6 的 `DEFAULT_EMPTY_VALUES` 含 `null`，致 `hasModelValue=false`、欄位渲染灰色 placeholder 而非「全部十年」，使用者無法分辨「已選全部十年」與「尚未選擇」（值本身正確，純顯示層失真）。**修正**：前端改以哨兵值 `120`（月）表示全部十年——`end.minusMonths(120)` 與 `minusYears(10)` 等價、CHECK 允許 `1..120`，語意零變動，且不依賴 `:empty-values` 這類版本相依 prop；`loadSchedule`／`saveSchedule` 將後端 `null` 映射為 `120`，後端保留 `null` 分支相容未儲存過的舊列。同步更新 `requirements.md` 與 `design.md`。**驗證**：重 build frontend，`rangeMonths=120` 之 run-now 產出 2515 筆、`2016-07-18 ~ 2026-07-17`（76,474 bytes），與十年完全等價（近 3 個月為 62 筆／5,569 bytes）。
- [x] 203.11 commit ＋ 兩段式 merge（`3edc7577`）。

---

### Task 204：台幣兌美元匯率 Excel 匯出與排程自動匯出到指定目錄（Requirement 42）

**需求對應：** Requirement 42「台幣兌美元匯率 Excel 匯出與排程自動匯出到指定目錄」。

**背景：** 使用者於 Task 203 完成後追加要求「這個功能也要可以指定時間匯出到指定目錄」，指的是「台幣兌美元」頁。該頁原本只有區間按鈕與「回補資料」，完全沒有匯出能力，故本任務一次補齊 R40＋R41 兩層：手動指定區間另存 ＋ 每日排程寫入指定目錄。

**沿用與差異：** 結構全面比照 R41（同為全域公開行情 ＋ per-user 排程設定，背景 cron 不需 `enableFilter`）。三個差異：(1) 單一序列，不需 R41 的跨標的 outer join；(2) 中間價為 `@Transient` 計算值（`mid_rate` 已於 v1.9.4 移除欄位），只能走 entity getter，不可回到 SQL 選取／排序；(3) 排程表不設 `currency` 欄，服務層固定 `USD`——本頁為單一幣別頁，加欄等於為不存在的多幣別頁預留未使用欄位。

- [ ] 204.1 **spec**：`requirements.md` 新增 Requirement 42；`design.md` 新增「Requirement 42（Task 204）」設計段（五套排程定位對照／midRate 取得方式／幣別維度取捨／資料模型／端點／檔案清單）；`tasks.md` 本任務。
- [ ] 204.2 **DB changeset**：`v1.62.0-exchange-rate-export-schedule.sql` 建 `exchange_rate_export_schedule`（owner UNIQUE ＋ 時分／range_months CHECK），冪等寫法；`db.changelog-master.yaml` 註冊。
- [ ] 204.3 **backend model／repository／dto**：`ExchangeRateExportSchedule` entity（`@Filter(ownerFilter)`）＋ repository ＋ `ExchangeRateExportDto`。
- [ ] 204.4 **`ExcelExportService.exportExchangeRates`**：新增 `writeExchangeRateSheet`（日期／即期買入／即期賣出／中間價；日期寫文字避免時區偏移；null 留空；midRate 走 `@Transient` getter）。
- [ ] 204.5 **`MarketDataController`**：新增 `GET /api/market-data/exchange-rate/export`（UTF-8 檔名、`ByteArrayResource`、區間預設近十年並驗證 start ≤ end）。
- [ ] 204.6 **`ExchangeRateExportScheduleService` ＋ `ExchangeRateExportController`**：設定 CRUD、`resolveDir` 路徑驗證、`writeAtomically`、滾動區間、每分鐘 tick ＋ 當日 guard ＋ `AtomicBoolean` 防重入、`ApplicationReadyEvent` 自癒、run-now（不動 guard）；端點 `GET/PUT /api/exchange-rate-export/schedule`、`POST /api/exchange-rate-export/run-now`。目錄列舉沿用既有 `/api/export-schedule/browse`。
- [ ] 204.7 **BFF**：`ExchangeRateBffController` 增 export 下載 ＋ schedule GET/PUT ＋ run-now ＋ browse 五支 passthrough。
- [ ] 204.8 **前端**：`api/index.js` `exchangeRate` 增 5 支；`ExchangeRateView.vue` 增「匯出 Excel」按鈕＋區間匯出對話框＋「排程自動匯出」設定卡＋資料夾樹選擇器。
- [ ] 204.9 **排程列表登錄**：`SchedulePublicBffController.JOBS` 補「台幣兌美元匯出 每日匯出排程檢查」。
- [x] 204.9b **`db/schema.sql` 基準線鏡像重產**：依該檔標頭指令重新 `pg_dump --schema-only` 並補回標頭。順帶修掉一項既有落後——Task 203 的 `commodity_export_schedule` 當時未同步進鏡像，本次一併補上。重產後 diff 為**純新增 128 行、零刪除**，內容僅 `commodity_export_schedule` 與 `exchange_rate_export_schedule` 兩張表及其 sequence／PK／UNIQUE／CHECK，證實無其他 schema 漂移混入。
- [x] 204.9c **對抗式驗證修正（幣別標籤硬編碼）**：六面向並行端到端驗證（CRUD／run-now 落檔／Excel 內容／BFF 路由／租戶隔離／部署真實性，共 77 個檢查點）提出 4 個 FAIL，經每個 FAIL 三名獨立視角對抗式複驗後全數被駁回。但其中「檔名與工作表名寫死『台幣兌美元』」一項**經我覆核後判定駁回理由不成立**——複驗者以「前端恆送 USD 故不影響」為由駁回，然該理由描述的是當下呼叫者而非 API 契約，且已實測 `currency=ZAR` 可重現：回 200、內容確為 ZAR 資料（22 列與 DB 相符）、檔名與工作表名卻標為「台幣兌美元」。**修正**：新增單一來源 `ExcelExportService.exchangeRateLabel(currency)`，由工作表名／手動匯出檔名／排程檔名三處共用。另兩項為既有跨服務行為（無身分回 500 而非 401，五支同類服務一致，已另立追蹤）與驗證環境自身干擾（並行 agent 寫同一張表），均非本次缺陷。
- [ ] 204.10 **建置與部署驗證**：`--no-cache` 重 build business／bff／frontend 並 `--force-recreate`；驗證 Liquibase EXECUTED、設定 CRUD、路徑跳脫與 rangeMonths 越界擋為 400、run-now 實際落檔（含主機端可見）、滾動區間筆數正確、手動匯出內容與排程一致。
- [ ] 204.11 commit ＋ 兩段式 merge。

---

### Task 205：未識別身分的錯誤語意修正（business-services 回 401 而非 500）（Requirement 28）

**需求對應：** Requirement 28 新增 AC「business-services 未識別身分回 401（錯誤語意契約）」。本任務即 Task 204.9c 驗證時記錄「無身分回 500 而非 401，五支同類服務一致，已另立追蹤」的後續。

**問題：** 五支排程設定 service 的 `requireOwnerId()` 與 `PortfolioAdviceService` 的兩處身分檢查，在 `CurrentUserContext` 無使用者時拋 `IllegalStateException`。`GlobalExceptionHandler` 無對應 handler，落入 `@ExceptionHandler(Exception.class)` 的 500 兜底，使「未帶身分」被回報成伺服器內部錯誤。

**嚴重度：minor（錯誤語意問題，非資安漏洞）。** business-services 未對主機開埠，公開邊界（nginx:80／bff:8080）對未登入與偽造 header 一律正確回 401，且 `ProblemDetail` 錯誤回應無 stacktrace、無資料外洩。

**設計取捨：** 既有 `security/` 下只有 `TenantAccessException`（→404）與 `AdminRequiredException`（→403），**無**可沿用的未認證例外，故新增 `UnauthenticatedException` 補齊第三種語意，三者成套且互斥（未識別／權限不足／非本人所有）。保留帶訊息建構子，讓各呼叫端維持原本的情境描述（「…無法存取排程設定」／「…無法儲存理財條件」／「…無法產生資產配置建議」），避免修正錯誤碼的同時劣化錯誤訊息。

- [x] 205.1 **spec**：`requirements.md` Requirement 28 新增 AC；`design.md`「認證與多租戶」段新增身分例外 → HTTP 狀態對映表；`tasks.md` 本任務。
- [x] 205.2 **新增 `security/UnauthenticatedException.java`**：`RuntimeException` 子類，無參建構子預設訊息「未識別使用者」，另備 `String message` 建構子。
- [x] 205.3 **`GlobalExceptionHandler`**：新增 `@ExceptionHandler(UnauthenticatedException.class)` → `HttpStatus.UNAUTHORIZED`。置於既有 `TenantAccessException`／`AdminRequiredException` handler 之後，維持身分三例外相鄰易讀。
- [x] 205.4 **五支排程 service 的 `requireOwnerId()`**：`ExportScheduleService`（R34）／`RealizedGainExportScheduleService`（R39）／`TradingCalendarExportScheduleService`（R37）／`CommodityExportScheduleService`（R41）／`ExchangeRateExportScheduleService`（R42）改拋 `UnauthenticatedException`，訊息不變。
- [x] 205.5 **`PortfolioAdviceService`**（R33）：`saveProfile()`／`generate()` 兩處 `requireCurrentUserId()` 回 `null` 的分支改拋 `UnauthenticatedException`，訊息不變。
- [x] 205.6 **建置與部署驗證**：`--no-cache` 重 build business 並 `--force-recreate`；於 business 容器內 curl 不帶 `X-User-*` 打五支 schedule 端點應回 **401**（原 500），帶正常 header 的既有行為（讀寫排程設定）不回歸。
- [ ] 205.7 commit ＋ 兩段式 merge。

---

### Task 206：資產總覽活頁簿第二張起每檔持股一張「過去一年股價」分頁（Requirement 34）

**需求對應：** Requirement 34 新增 AC「每檔持股各一張『過去一年股價』分頁」。

**背景：** 使用者要求 `資產總覽_{使用者ID}_{YYYYMMDD}.xlsx` 第一張 sheet 維持全部資產總表，第二張起每一支持股一張 sheet、sheet 名稱為股票代號，內容為該股過去一年的股價。此檔由 run-now 與每日排程共用（`buildLiveWorkbook`），且會落到 SRPP 退休規劃專案的輸入目錄，故分頁採「表頭＋逐日一列」的機器可讀表格（不加標題列），與既有油價金價／匯率分頁一致。

**設計要點：** 資料源固定 `stock_price_history`（與技術指標同源，匯出零外部行情呼叫）；持股清單重用第一張分頁的同一個 `AssetSnapshot`，`(code, market)` 去重；查無資料仍出空表頭分頁；分頁名以 `WorkbookUtil.createSafeSheetName` 收斂，撞名補市場／數字後綴。

- [x] 206.1 **spec**：`requirements.md` Requirement 34 增 AC；`design.md` Requirement 34 章新增「資產總覽活頁簿改為『總表 ＋ 每檔持股一張過去一年股價分頁』」設計段；`tasks.md` 本任務。
- [x] 206.2 **`ExcelExportService` 注入 `StockPriceHistoryRepository`**：與既有 `commodityHistRepo`／`rateHistRepo` 同慣例（建構子注入），不新增 repository 方法（沿用既有 `findByStockCodeAndMarketAndTradingDateBetweenOrderByTradingDateAsc` 派生查詢）。
- [x] 206.3 **`buildLiveWorkbook()` 追加股價分頁**：`writeLiveAssetsSheet` 之後呼叫 `writeStockPriceHistorySheets(wb, st, latest)`；`latest == null` 時不產生分頁（沿用既有「尚無資產快照」行為）。
- [x] 206.4 **`writeStockPriceHistorySheets` / `writeStockPriceHistorySheet` / `uniqueStockSheetName`**：去重（`LinkedHashSet<code|market>`，保留總表順序）、區間 `today.minusYears(1) ~ today`（Asia/Taipei）、欄序 `日期／開盤價／最高價／最低價／收盤價／成交量`、日期寫文字、OHLC 用 `num4`、空值留白、空資料出表頭分頁、分頁名撞名補 `_市場`／數字後綴。
- [x] 206.5 **前端說明文字同步**：`AssetHistoryView.vue` 排程設定卡說明補「第二張起每檔持股一張過去一年股價分頁（sheet 名＝股票代號）」，讓 UI 描述與實際產檔內容一致。
- [x] 206.6 **建置與部署驗證**：`--no-cache` 重 build business-services（運行 jar 內 `ExcelExportService.class` 確認含 `writeStockPriceHistorySheets`／`uniqueStockSheetName`，非 stale image）＋ `--force-recreate`（healthy）；frontend 亦 `--no-cache` 重 build（bundle `AssetHistoryView-B5cuz12j.js` 含新說明文字）。以 owner 1（X-User header）觸發 run-now 產出 `資產總覽_1_20260718.xlsx`（210,463 bytes），驗證：**22 張分頁＝1 張「當前即時資產」＋21 張股價分頁**，分頁名依序 `0050／VOO／006208／VT／00881／GOOGL／SGOV／009804／0056／00919／00878…` 無重複；持股去重正確（DB 最新快照 43 筆持股 → 21 個 distinct `(code, market)`，與 21 張分頁相符）；表頭 `日期／開盤價／最高價／最低價／收盤價／成交量`；列數合理（`0050`／`009804` 各 243 列＝台股一年交易日、`VOO` 252 列＝美股）；日期區間 `2025-07-18`～`2026-07-17`（今日尚無收盤，正確）；收盤價與 DB 逐筆一致（`0050` 2025-07-18 = 51.45／量 107,920,505、2026-07-17 = 100.15／量 520,545,951，與 `stock_price_history` 完全相同）；無非預期空分頁。
- [ ] 206.7 commit ＋ 兩段式 merge。

---

### Task 207：公開資訊新增「台股均線突破」快照（大盤／0050／00881 對季線・年線）（Requirement 31）

**需求對應：** Requirement 31 新增 AC「公開資訊必含台股均線突破偵測」。

**來源：** 本功能實作原存在於過時分支 `claude/morning-premarket-fetch-schedule-2f9046`（該分支原編 Task 183，與 main 既有 Task 183「規格與程式碼一致性對齊」撞號，比照 Task 202/203 之編號避讓改編為 207）。該分支其餘內容（盤前爬蟲 08:20、韓股快照、分析改 08:40）**已被 main 的 Task 184／185／186／191／192／193 獨立且更完整地實作**，且其寫死 cron 正是 Task 195 刻意根除的漂移類型，故**整支不 merge**，僅本功能以人工移植方式撿回。

**移植與修正：** 原實作經四視角對抗式審查（正確性／健壯性／整合風險／慣例符合度），核心 SMA 與穿越判定正確，但有兩個 blocker 與三個 major 須先修（見下）。

- [x] 207.1 **spec**：`requirements.md` R31 新增 AC；`design.md` 新增「台股均線突破快照」設計段 ＋ `news_headline` 的 source／category 列舉、`PublicInfoStockFilter` 保留清單、`MarketAnalysisService` 注入群三處補 `ma-cross`；`tasks.md` 本任務。
- [x] 207.2 **B1 相依移植**：`StockSourceQuery` 補 `ClosePoint` record ＋ `loadRecentStockCloses()`／`loadRecentTaiexCloses()`（main 原無此三者，只搬 client 會編譯失敗）。多列查詢用 void 區塊 lambda（`RowCallbackHandler`），避免 expression lambda 被解析成 `ResultSetExtractor`。
- [x] 207.3 **`MaCrossSnapshotClient` 移植**：偵測大盤／0050／00881 對 MA60／MA240 的漲破跌破，命中才產列；逐標的獨立 try/catch。
- [x] 207.4 **M1 分割污染防呆（視窗層）**：原實作只比對最後兩根（擋分割當日），分割次日即失效而均線仍被舊權值污染 → 新增 `windowHasGap()`，均線取樣視窗內任一相鄰兩日變動 >15% 即略過該均線。
- [x] 207.5 **M2 去重鍵事件化**：原實作 source／url／category 三者皆固定 → `dedupe_key` 恆定 → `ma-cross` 永遠一列、後來事件整列覆寫先前的。改為**每則事件各一列**、`url` 帶 `#代號-ma期數-資料日` fragment；同輪重跑仍冪等。
- [x] 207.6 **M3 匯出 cutoff 例外**：`loadTodayPublicInfoForExport` 對 `category='ma-cross'` 豁免 `published_at >= cutoff`（本 category 的 `publishedAt` 刻意取事件資料日，而大盤指數表落後屬常態），避免該列被 SRPP JSON 靜默濾掉、或同日不同輪次時有時無。
- [x] 207.7 **B2 掛載點**：只在 main 現行 `NewsPoller` 加兩行（欄位注入 ＋ `rows.addAll(maCrossClient.fetchAll())` 接在 `krIntradayClient` 之後）。**不可**沿用分支的 `NewsPoller`——該版注入 main 已移除的 `KrMarketSnapshotClient`（編不過），且缺 Task 192 的 DB 驅動排程，覆蓋等於回退可設定爬蟲時段與韓股盤中快照。
- [x] 207.8 **M4 文件**：class javadoc 與實作對齊（原 javadoc 仍寫 `publishedAt=Instant.now()`，實作早已改為事件資料日）。
- [x] 207.9 **單元測試**：新增 `MaCrossSnapshotClientTest`（10 例）——漲破／跌破／同側不觸發、`n=period` 與 `n=period+1` 臨界、15% 門檻兩側（14%／16%）、`publishedAt` 取資料日、null／零收盤 graceful、單一標的失敗不影響整輪，以及 **M1 與 M2 的回歸測試**。M1 該例已實測「停用 `windowHasGap` 則失敗（噴出『0050 收 48.09 漲破季線(MA60 47.80)』假訊號）、啟用則通過」，確認具鑑別力而非空測。
- [x] 207.10 **建置與部署驗證**：`--no-cache` 重 build external-materials-service 並 `--force-recreate`；驗證爬蟲輪次正常、無突破時不產列、`ma-cross` 列可進 SRPP JSON 與分析 prompt。
- [ ] 207.11 commit ＋ 兩段式 merge。

---

### Task 208：修正 business-services 容器重建後 BFF 沿用舊 IP 的 stale DNS（整站 500）

**背景（實地事故）：** Task 206 部署時 `--force-recreate business-services`，該容器 IP 由 `172.19.0.4` 換成 `172.19.0.7`；
BFF 之後持續對舊 IP 連線得 `Connection refused`，使用者點「歷年資產」等頁面一律 500。business-services 日誌**全乾淨**、
容器內直打端點正常，錯誤只出現在 `docker logs asset-bff`——極易被誤診成功能壞掉。重啟 bff 後恢復。

**根因（bytecode ＋ 原始 DNS 封包實測）：** reactor-netty 預設走 netty 非同步 DNS resolver（非 JDK `InetAddress`），
其 `cacheMaxTimeToLive` 預設 `Integer.MAX_VALUE` 秒＝照抄回應 TTL；Docker 內建 DNS 對 container name 回 TTL **600 秒**
（封包 TTL 欄位 `0x00000258`），故舊 IP 最久被記住 10 分鐘。`getent hosts` 正常是因為那走 glibc/NSS，與 netty 快取無關。

**設計取捨：** 不採「每次重建 business 就人工 restart bff」的純流程規範作為唯一解——重建 business-services 是本專案最常見的
部署動作，漏一次即整站 500 且症狀誤導性極強；改為程式修補（一支新檔 ＋ WebClientConfig 三處小改），流程面則另在
run-stack skill 補上保險。同時捨棄兩條看似可行的路：`-Dnetworkaddress.cache.ttl`（對 reactor-netty 完全無效，且它是
security property，直接 `-D` 讀不到——雙重靜默無效）、改用 JDK `DefaultAddressResolverGroup`（會讓 DNS 查詢變 blocking
卡在 event loop，為修快取而動 I/O 模型不划算）。

- [x] 208.1 **spec**：`design.md` Infrastructure 章新增「BFF 上游 DNS 解析策略（Task 208）」；`tasks.md` 本任務。
- [x] 208.2 **新增 `bff/config/DnsCacheConfig`**：`MAX_TTL = 30s`（與 JDK `InetAddress` 慣用值一致；秒級設定會過度依賴
      embedded DNS 可用性）；`applyDnsCacheLimit(HttpClient, usage)` 設 `cacheMinTimeToLive(0)` ＋ `cacheMaxTimeToLive(30s)`
      並印 `[dns-cache]` 日誌；`HttpClientCustomizer` bean 套用於 gateway。**negative TTL 刻意不設**——netty 預設 0s＝不快取
      失敗，設 1s 反而把重建瞬間的 NXDOMAIN 黏住。
- [x] 208.3 **`WebClientConfig` 套同一設定**：`businessServicesClient` 明確 `.clientConnector(new ReactorClientHttpConnector(...))`；
      client 由注入的 `ReactorResourceFactory`（`org.springframework.http.client`，Boot 3.4.4 實際使用的那支，非 deprecated 的
      `...client.reactive`）建立以共用連線池／event loop，`ObjectProvider` 取不到時退回 `HttpClient.create()` 不讓 BFF 起不來。
      註解明示「已脫離 Boot connector 組裝管線，日後加 ssl bundle／mapper 需同步此處」。
- [x] 208.4 **run-stack skill 保險**：`.claude/skills/run-stack/SKILL.md` 與 `.agents/` 副本新增「recreate business/ext 後
      一併 restart bff」段落，含誤診特徵（business log 乾淨、錯只在 bff log、`getent` 正常）與「bff 容器無 curl、
      除 actuator 外全需登入 session 故無法用未認證 curl 自證」的提醒。
- [x] 208.5 **建置與部署驗證**：`--no-cache` 重 build bff ＋ `--force-recreate`（healthy）；啟動日誌確認
      `[dns-cache] webclient` 與 `[dns-cache] gateway` **各一行**（兩條路徑都套到）。
      **換 IP 對照實測**（不需任何帳號登入）：以運行中 `asset-bff` jar 內的同版本依賴（reactor-netty 1.2.4 ＋
      netty 4.1.119）編出探針 `DnsProbe`，於 `asset-network` 上同時跑兩個 `HttpClient.newConnection()`（不走連線池，
      故每次都必須重新解析）——一個全預設、一個套本任務設定——每 5 秒印實際連到的 remote address；中途以
      「暫時容器占住舊 IP」強制 `business-services` 由 `172.19.0.8` 換到 `172.19.0.9`。結果：
      | 時間 | 預設（修補前行為） | 本任務設定（maxTtl=30s） |
      |---|---|---|
      | 13:34:38 | ← 換 IP，舊 IP 被占位容器占住 | |
      | 13:34:49 | 仍 `Connection refused: 172.19.0.8` | **已跟上 `172.19.0.9`（11 秒）** |
      | 13:35:59 | **80 秒後仍卡在舊 IP** | 持續正常 |
      即修補前後為「最久 600 秒」對「≤30 秒」的量級差異，機制與設定值皆獲實證。測試容器（探針／占位）事後移除，
      stack 全數 healthy、`asset-bff` 自啟動起 `Connection refused`／`500` 計數為 0。
      註：BFF 除 `/actuator/health|info` 外全需登入 session，故未以瀏覽器代登入驗證（登入屬使用者本人操作）；
      上述探針即為不觸及帳號的等價驗證。
- [ ] 208.6 commit ＋ 兩段式 merge。

---

### Task 209：今日交易雷達——不呼叫 AI API 的台股規則式買賣決策輔助（Requirement 43）

**需求對應：** 使用者要求在左側「股市綜合分析」下加入可看台股大盤與指定個股買賣建議的功能，並明確要求不要 call AI API。第一版以「最新持股 ∪ 股票觀察」作為指定標的，純讀既有 PostgreSQL／Redis，不新增外部資料呼叫、不自動下單。

- [ ] 209.1 **spec**：新增 Requirement 43；`design.md` 補請求鏈、owner 隔離、DTO、兩收盤日確認、`TW_RULES_V1`、API／前端與驗證設計；`tasks.md` 本任務；`CLAUDE.md` Requirement 數同步為 43。
- [ ] 209.2 **規則引擎與 DTO**：新增 `TradingRadarRuleEngine`（純函式、版本 `TW_RULES_V1`）與 `TradingRadarDto`；實作大盤／個股評分、clamp、兩日確認、動作映射、`RISK_OFF` 禁買與 incomplete veto。
- [ ] 209.3 **business service/API**：新增 `TradingRadarService`／`TradingRadarController`；標的取 owner-scoped 最新持股 ∪ 觀察台股，指標共用 `TechnicalIndicatorService`，報價只讀 `PriceQueryService`，大盤／個股逐檔 graceful，不注入 AI／爬蟲服務。
- [ ] 209.4 **一頁一 BFF**：新增 `TradingRadarBffRoutes`，rewrite `/api/bff/trading-radar` → `/api/trading-radar`；一般已登入使用者可 GET。
- [ ] 209.5 **前端**：新增 `TradingRadarView.vue`、`bffApi.tradingRadar`、router `/trading-radar`，並在「股市綜合分析」中加入「今日交易雷達」（位於今日股市分析與股票觀察之間）；完成大盤風險卡、個股決策表、理由／風險展開、空清單與資料不足狀態。
- [ ] 209.6 **單元測試**：新增 `TradingRadarRuleEngineTest`，覆蓋兩日確認四態、分數 clamp、買進門檻、大盤 `RISK_OFF` veto、held／未持有映射與 incomplete `NO_TRADE`。
- [ ] 209.7 **建置與部署驗證**：backend tests/package、BFF package、frontend build；重建/recreate business、BFF、frontend 並確認 healthy；驗證實際 payload、選單與畫面，且 `/api/trading-radar` 請求不產生 AI API 呼叫。
- [x] 209.8 **盤中 SSE 自動更新**：沿用 `/api/market-data/prices/stream`；台股事件先即時更新現價／漲跌幅／行情時間，再以 2 秒 debounce 背景重讀完整雷達以重算 MA／KD／分數／建議；避免重疊重算，離頁關閉 SSE 與 timers，斷線自動重連，不觸發行情 refresh／AI API；完成前端 build、chunk 與執行環境驗證。
  - 驗證：frontend production build 成功；執行中 `TradingRadarView` chunk 含 SSE path／`price-update`；`/trading-radar`＝200、BFF health＝UP、雷達 API 回 19 檔完整 payload、BFF 部署後連線／500 錯誤＝0。
- [ ] 209.9 commit ＋兩段式 merge（僅在使用者明確授權 commit／push 後執行）。

---

### Task 210：今日交易雷達——逆勢抄底獨立狀態（Requirement 43 / TW_RULES_V2）

**需求對應：** 使用者要求在原本保守趨勢型分數／主建議之外，額外增加一套逆勢抄底狀態；跌深仍走弱先標「超跌觀察」，出現低檔黃金交叉且停止續跌才升級「逆勢試單候選」，不得把逆勢訊號偽裝成原規則買進建議。

- [x] 210.1 **spec／版本契約**：Requirement 43 與 design 補 `TW_RULES_V2`、兩軌狀態、精確門檻、前一期 KD 權威來源、DTO／前端／風險文案與驗證；V1 score/action 行為保持不變。
- [x] 210.2 **KD 與純規則引擎**：`TechnicalIndicatorService.FullIndicators` 增 `previousK/previousD`；`TradingRadarRuleEngine` 增 `CounterTrendState/Result`，實作 `NONE`／`OVERSOLD_WATCH`／`TRIAL_CANDIDATE`，不得覆寫 score/action，`RISK_OFF` 加逆勢風險提示。
- [x] 210.3 **DTO／service**：`StockDecision` 回傳 counter-trend state／label／reasons／risks；完整與 incomplete mapping 一致，無 DB migration、無外部行情或 AI 呼叫。
- [x] 210.4 **前端**：交易雷達新增獨立「逆勢抄底」欄與展開理由／風險；SSE 背景重算後同步更新新狀態；原「規則建議」、分數與手動重新整理保留。
- [x] 210.5 **測試與部署**：補 009804 型超跌觀察、真／假黃金交叉、仍續跌、年線失守與 incomplete 測試；backend target test/package、BFF package、frontend build；重建 business/BFF/frontend，確認 V2 payload、頁面 chunk 與健康狀態。
  - 驗證：規則／通知純函式測試通過；三層 production build 成功；live `TW_RULES_V2` payload 中 009804 維持 score=17、action=`EXIT_CANDIDATE`，另回 `OVERSOLD_WATCH`；business/BFF healthy、頁面 200、bundle 含逆勢欄與 SSE。
- [ ] 210.6 commit ＋兩段式 merge（僅在使用者明確授權 commit／push 後執行）。

---

### Task 211：每檔交易雷達狀態 Email 通知（Requirement 44）

**需求對應：** 使用者要求每檔股票後方提供按鈕，可選擇哪些交易雷達狀態出現時寄 Email，並指定收件人；通知須在背景價格事件運作，不依賴頁面開啟，且不得重複轟炸或跨租戶寄信。

- [x] 211.1 **spec／契約**：新增 Requirement 44；design 定義狀態選項、逐檔 dialog、正規化三表、owner 隔離、首次 baseline、狀態轉入語意、價格事件合併、per-recipient digest、API/BFF 與驗證；`CLAUDE.md` Requirement 數同步為 44。
- [x] 211.2 **schema／entity／repository**：新增 v1.63 Liquibase；實作 setting/state/recipient join entities 與 owner-safe repositories，含 unique/FK/index 與 active email 同 owner 查詢。
- [x] 211.3 **設定 API**：實作 DTO、owner-scoped GET/PUT、狀態 code 驗證、recipient 白名單與 bulk replace；擴充 TradingRadarController／BFF／frontend API。
- [x] 211.4 **背景偵測與 Email**：PriceStreamService 接入 2 秒合併評估；0000 更新全評估，個股只評該檔；explicit owner latest snapshot 判斷 held；共用 V2 決策、baseline／transition 去重與 per-recipient HTML digest，錯誤 fail-soft。
- [x] 211.5 **前端**：每列最右新增「通知設定」按鈕與逐檔 dialog；分組勾選主狀態／逆勢狀態、收件人、active，顯示首次 baseline／不重寄說明與無收件人引導。
- [x] 211.6 **測試與部署**：覆蓋狀態轉入去重、再進入、未選／inactive、收件人 owner 防護與 held mapping；backend target test/package、BFF package、frontend build；重建 business/BFF/frontend，確認 migration、V2 payload、notification API、bundle、health 與 logs。
  - 驗證：通知 transition／held mapping 與雷達規則測試合計 15/15；三層 production build 成功。v1.63 changeset EXECUTED、三表存在；通知 GET 回 10 個主狀態／2 個逆勢狀態；兩位使用者只見各自收件人，跨 owner recipient PUT 回 400 且 setting 留存 0 筆；頁面 200、business/BFF healthy、bundle 含逐檔按鈕／dialog／notification endpoint，近 5 分鐘無 error／500／connection refused。
- [ ] 211.7 commit ＋兩段式 merge（僅在使用者明確授權 commit／push 後執行）。

---

### Task 212：爬蟲產生檔案的輸出路徑可於頁面設定（Requirement 38）

**需求對應：** Requirement 38「爬蟲資訊查詢頁」新增之「輸出檔案路徑設定」相關 AC。

**背景：** 使用者於 Task 192（爬蟲執行時間可設定）完成後追加要求「爬蟲除了可以設時間，還要可以設定產生檔案的路徑」。
此處「產生的檔案」＝ `NewsPoller` 每輪寫給 SRPP 退休規劃專案的公開資訊 JSON（`public_info_<日期>.json`，Task 177），
原本目的地寫死在 `news-scraper.export-dir`（容器 `/srpp-input`）＋ `docker-compose.yml` 的 volume，改路徑必須改檔重新部署。

**關鍵設計決定（掛載基底對齊）：** 本專案已有一套成熟的「輸出目錄設定」模型（Requirement 34／39／41／42）——DB 只存
**相對子路徑**、實際目錄由容器基底 `EXPORT_OUTPUT_DIR`（`/home/steven`←host `/Users/steven`）resolve、前端用 `el-tree`
懶載入資料夾樹挑選、目錄列舉共用 `GET /api/export-schedule/browse`。但那套的寫入方與列舉方都在 **business-services**，
而爬蟲寫檔在 **external-materials-service**，後者原本只掛了 `/srpp-input` 一個窄目錄 → 前端樹上選得到的資料夾，爬蟲
根本寫不到。故本任務**替 ext 補上與 business 相同的 host 家目錄掛載與基底環境變數**，讓「同義的輸出資料夾」在兩個
服務指向同一個實體目錄，才有資格沿用同一支 `browse` API（CLAUDE.md「同義欄位、同一 business service API」）。
代價是爬蟲容器取得整個家目錄的寫入權限（原僅一個子目錄），此權衡已與使用者確認後採行。

**刻意不做：** （1）不開放設定**檔名**——SRPP 依 `public_info_<日期>.json` 取用，改名等於單方面破壞下游契約；
（2）不開放**絕對路徑**——等於把容器內檔案系統位置寫進 DB、跨環境不可攜且繞過基底防護；（3）不把路徑併進
`crawler_schedule`——該表一列一時間點，併入會讓同一事實隨列數重複儲存，且刪時間點會連帶弄丟路徑。

- [x] 212.1 **spec**：`requirements.md` Requirement 38 標題／User Story／背景納入輸出路徑，新增 6 條 AC（路徑設定／路徑模型與掛載對齊／預設值不變行為／資料夾選擇器沿用同一 API／動態生效／權限）；`design.md` 更新 `CrawlerDataBffController` 端點、ERD 實體清單、Requirement 38 設計段（新增「動態輸出檔案路徑」四個子項）、Task 177 SRPP JSON 段之基底描述、`crawler_export_setting` 資料表段與 API 端點區塊；`tasks.md` 本任務。
- [x] 212.2 **DB changeset**：`v1.64.0-crawler-export-path.sql` 建 `crawler_export_setting`（`crawler_key` UNIQUE ＋ `output_subpath NOT NULL`），冪等寫法，seed `('news-poller','Project/SRPP/data/input')`＝原 `/srpp-input` 的同一 host 目錄；`db.changelog-master.yaml` 註冊。**編號避讓**：原編 v1.63.0 並已在開發 DB 執行，部署時發現另一個 worktree 已先占用 v1.63.0（`v1.63.0-index-export-schedule`，14:05 EXECUTED，尚未進 main）→ 改號至 v1.64.0 避免兩支 `v1.63.0-*` 同時進 main。changeset id 隨檔名改變會被 Liquibase 當成新 migration 重跑，故建表／seed 皆為冪等寫法（舊 id 於開發 DB 留下一筆孤兒 `databasechangelog` 紀錄，無功能影響）。
- [x] 212.3 **backend model／repository／dto／service／controller**：`CrawlerExportSetting` entity（全域、無 `@Filter`）＋ repository ＋ `CrawlerExportPathDto`；`CrawlerExportPathService`（讀取無列時回 seed 預設、`normalizeSubpath`、`resolveDir` 擋 `..`／絕對路徑、回傳 `baseDir`＋`absolutePath` 供前端顯示）；`CrawlerExportPathController`（`GET/PUT /api/crawler-export-path`，`PUT` 以 `CurrentUserContext.isAdmin()` 縱深防禦）。
      **讀寫的驗證嚴格度刻意不同**：`PUT` 擋下不合法子路徑（→ 400），但 `GET` 組 `absolutePath` 時遇不合法值只回 `null`、不擲例外——DB 內仍可能存在繞過 API 的值（psql 直改、跨環境備份還原、日後改動 `EXPORT_OUTPUT_DIR` 基底），若讀取也失敗會使設定頁 500，反而讓使用者**沒有入口把它改回正常值**（唯一修正管道被自己鎖死）。前端此時顯示「—」、仍可重選並儲存。
- [x] 212.4 **ext `CrawlerExportPathQuery` ＋ `NewsPoller` 改讀 DB**：新增 `JdbcTemplate` 純讀元件（比照 `CrawlerScheduleQuery`）；`NewsPoller.exportPublicInfoJson()` 每輪讀現值並於寫檔前**再驗一次**跳脫，DB 例外／空值 fallback 常數 `Project/SRPP/data/input`；移除 `news-scraper.export-dir`，改注入 `EXPORT_OUTPUT_DIR`（預設 `/home/steven`）為基底。
- [x] 212.5 **docker-compose**：ext 新增 `EXPORT_OUTPUT_DIR: /home/steven` 與 volume `${EXPORT_OUTPUT_DIR_HOST:-/Users/steven}:/home/steven`；移除 `/srpp-input` 掛載（其預設目的地已被家目錄涵蓋，host 落點不變）。
- [x] 212.6 **BFF**：`CrawlerDataBffController` 增 `GET/PUT /api/bff/crawler-data/export-path` 與 `GET /api/bff/crawler-data/export-path/browse`（passthrough 既有 `/api/export-schedule/browse`）；`SecurityConfig` 將 `PUT /api/bff/crawler-data/export-path` 列入 ADMIN。
- [x] 212.7 **前端**：`api/index.js` `crawlerData` 增 3 支；`CrawlerDataView.vue` 新增「爬蟲輸出檔案設定」卡（唯讀輸入框＋「選擇」開資料夾樹對話框＋新增子資料夾＋儲存，非 ADMIN 唯讀）並顯示完整落點路徑與檔名規則。
- [x] 212.8 **建置與部署驗證**：`--no-cache` 重 build business／bff／frontend／ext 並 `--force-recreate`（六個容器皆 healthy，含 recreate 上游後 restart bff，`asset-bff` 近 5 分鐘 `Connection refused`／`500` 計數為 **0**）。實測結果：
      - **Liquibase**：`v1.64.0-crawler-export-path` EXECUTED；因改號重跑而與舊 id `v1.63.0-crawler-export-path` 併存兩筆紀錄，冪等寫法使重跑為 no-op、business 正常啟動（非 crash loop）。seed 值 `news-poller | Project/SRPP/data/input`。
      - **契約**：`GET` 回 `{"crawlerKey":"news-poller","outputSubpath":"Project/SRPP/data/input","baseDir":"/home/steven","absolutePath":"/home/steven/Project/SRPP/data/input","updatedAt":"2026-07-18 22:26:40"}`；空字串 `PUT` 被正規化回預設子路徑。
      - **路徑安全**：`{"outputSubpath":"../../etc"}` → **400**；`{"outputSubpath":"/etc/passwd"}` → **400**（`detail` 為「輸出子路徑不可跳脫基底目錄，且須為相對路徑」）；非 ADMIN（`X-User-Role: USER`）`PUT` → **403**。
      - **掛載對齊**：`docker inspect asset-external-materials-service` 之 Mounts 為 `/Users/steven → /home/steven`（與 business 同一份），`/srpp-input` 已消失。
      - **端到端改路徑實測**：預設值下 warmup 寫出 `/home/steven/Project/SRPP/data/input/public_info_2026-07-18.json`（309 筆，host 端 108,350 bytes，**與改動前同一個 host 落點**）→ `PUT` 改為 `input/crawler-test` 後重跑，寫出 `/home/steven/input/crawler-test/public_info_2026-07-18.json`（host `/Users/steven/input/crawler-test/` **由程式自動建立**、同樣 108,350 bytes）→ 改回預設後再跑，落點回到 SRPP 目錄。三輪皆無 `.tmp` 殘留（原子 rename 仍正常）。測試目錄事後刪除。
      - **前端／BFF**：`CrawlerDataView-D5i0daWR.js` 含「爬蟲輸出檔案設定」卡與資料夾選擇器；`index-Bv829Q4m.js` 含 `crawler-data/export-path`；未登入打 `GET /api/bff/crawler-data/export-path` 與 `.../export-path/browse` 皆回 **401**（路由已註冊、非 404）。
      - **待使用者目視確認**：設定卡版面與 `el-tree` 資料夾選擇器需於瀏覽器登入後確認（登入屬使用者本人操作）。
- [x] 212.9 commit ＋ 兩段式 merge（feature `2e40749f` ＋ 編號避讓 `b8513e4c`；main merge `7675d2ef`）。**編號避讓兩次**：本任務原編 Task 209，部署後才發現交易雷達那條線已占用 209／210／**211**（`a1a0dec8` 先 merge 進 main），故一路避讓至 212；同批改掉 17 個檔的引用，含 `Task 192、209`／`Task 177／209` 這類不以 `Task ` 開頭的複合寫法（首次批次取代漏抓、複查時補齊）。純註解／文件變更，三個 JVM 模組重新編譯通過，運行中容器行為不變故未重新部署。merge 衝突兩處皆為「雙方各自在檔尾新增」：`db.changelog-master.yaml`（保留 v1.63.0-trading-radar-notification ＋ v1.64.0-crawler-export-path 兩個 include，依版號排序）與本檔（保留對方 209/210/211 ＋ 本任務 212）；合併後複驗 Task 208–212 各一次無重複、`api/index.js` 兩組 API 並存。

---

### Task 213：交易雷達避免配息型債券 ETF 誤判出場（Requirement 43 / TW_RULES_V3）

**需求對應：** 使用者發現 00751B 當日上漲且 KD 轉強，交易雷達仍以 0 分標為「出場候選」。實際稽核確認兩個獨立根因：技術指標直接使用未還原權息 OHLC，使 2026-06-22 現金配息 0.43 元及 240 日內多次配息把 MA60／MA240 機械墊高；同時 `stock.asset_class=BOND` 雖已是系統事實來源，雷達仍把所有台股一律套用 TAIEX `RISK_OFF -15` 與股票買進閘門。

- [x] 213.1 **spec／版本契約**：Requirement 43 與 design 升版 `TW_RULES_V3`，定義還原權息 OHLC 因子、MA／KD／兩日確認同價基、有效資產類別、BOND／STOCK 大盤規則差異、DTO／前端揭露與驗證；不新增資料表、不呼叫外部行情或 AI API。
- [x] 213.2 **還原權息純計算**：新增 `DistributionAdjustedPriceService`，以區間內 `stock_dividend_history` 現金／股票配息事件調整 OHLC 並縮放至最新價不變；無有效事件原值返回。`StockDividendHistoryRepository` 新增區間純讀 query。
- [x] 213.3 **同一技術價基整合**：`TechnicalIndicatorService` 抽出 `computeFromSeries` 共用核心；`TradingRadarService` 對完成日 K 與可選今日 live K 一次還原後，同一序列計算 MA／KD、三條兩日確認及規則內部單日漲跌（±5% 扣分／停止續跌），禁止混用原始／還原價；DTO 行情漲跌仍保留原始市場值。
- [x] 213.4 **資產類別感知規則**：`TradingRadarService` 以 `stock.asset_class` override＋`AssetClassifier` 取得有效類別；`TradingRadarRuleEngine` V3 增 `InstrumentType`，BOND 不套台股大盤加減分、`RISK_OFF` 買進閘門／逆勢風險文案及 `DATA_INCOMPLETE` veto，EQUITY 維持 V2。
- [x] 213.5 **DTO／前端可解釋性**：`StockDecision` 新增 `assetClass`／`distributionAdjusted`；標的名稱下顯示「債券」／「還原權息」標記，展開 reasons 明示債券未套股票大盤規則。
- [x] 213.6 **回歸測試與建置**：新增 00751B 型除息序列、最新價不變、無事件 no-op、股票股利因子、BOND／EQUITY RISK_OFF 差異、大盤 incomplete 差異測試；執行 backend target tests/package、BFF package、frontend build。
  - 驗證：針對性規則／還原權息／通知測試 20/20；Java 21 全後端測試 60/60 並 package 成功；BFF package、frontend production build 成功。Java 25 首輪完整測試因既有 Mockito inline mock 不支援該 JVM 而 29 errors，切回專案指定 Java 21 後全數通過，非本次回歸。
- [x] 213.7 **部署與實機驗證**：重建／recreate business-services 與 frontend，依 run-stack 慣例 restart BFF；確認服務 healthy、`TW_RULES_V3` payload、00751B 不再為錯誤 `EXIT_CANDIDATE`，畫面顯示「債券／還原權息」，且 logs 無 500／連線錯誤。
  - 驗證：business-services／BFF healthy、frontend HTTP 200、BFF actuator UP；frontend 運行 chunk `TradingRadarView-CJB-ZrKW.js` 含 `TW_RULES_V3`。owner 1 真實 payload：市場仍 `RISK_OFF score=35`；00751B 回 `assetClass=BOND`、`distributionAdjusted=true`、`score=79`、`action=HOLD/續抱`、MA20／60／240=`31.70／31.39／31.04`、KD=`22.47／19.83`，reasons 同時揭露還原權息與債券不套大盤規則。重建後 BFF `Connection refused|500 Server Error=0`、business `ERROR|TradingRadar 組裝失敗=0`。
- [x] 213.8 **commit ＋兩段式 merge**：feature commit `42693e95` 已推送；保留 main Task 214／215 後以 `--no-ff` 合併並推送 main。

---

### Task 214：資產總覽匯出增列 ETF 淨值與折溢價欄（Requirement 34）

**需求對應：** Requirement 34 新增 AC「『股票（即時）』增列 ETF 淨值與折溢價欄」。

**背景：** 使用者要求匯出的資產總覽每支股票都要有折溢價。折溢價＝(市價−淨值)/淨值，**只有 ETF 有**（個股無基金淨值），
故個股列留白而非填 0。資料源經實測查證：台股用證交所 MIS 全市場 ETF 彙整檔（一次 request 涵蓋上市＋上櫃，
使用者 13 檔台股 ETF 100% 命中，且證交所已算好折溢價）；美股用 Yahoo `navPrice`（與 Vanguard／iShares 官方淨值實測完全吻合）。

**關鍵設計決策：**
1. **不做 ETF 白名單判定**——台股看代號是否在證交所 ETF 名冊、美股看 Yahoo 是否回 `navPrice`，資料本身即答案。
   既有 `isEtf()` 三份複本皆誤含個股 AVGO、皆漏使用者持有的 SGOV，複用會第一天就錯列。
2. **折溢價不自行重算**——台股直接取證交所已算好的欄位（淨值欄四捨五入至 2 位，重算誤差 0.07 個百分點）；
   且**禁用 T-1 淨值欄**（實測台股重挫日會把 0050 的 +1.2% 溢價算成 −5.8% 折價）。美股**禁用 `previousClose`**
   配 `navPrice`（會把 VOO 真實 +0.003% 溢價放大成 +1.02%）。
3. **獨立 Redis key**（`price:etfnav:{market}:{code}`）而非擴充既有 price payload——後者有三個寫入者會互相覆蓋，
   且會波及 ClosePersister 收盤回填與 SSE 契約。
4. **TTL 96h ＋ 輸出「淨值時間」欄**——淨值一天一組值且只在交易時段抓，24h 會讓週末後首份匯出空白；
   但撐久了就必須揭示資料時點，否則使用者無從分辨今日值與殘留值。

- [x] 214.1 **spec**：`requirements.md` Requirement 34 增 AC；`design.md` Requirement 34 章新增「ETF 淨值與折溢價欄」設計段；`tasks.md` 本任務。
- [x] 214.2 **ext 抓取（台股）**：新增 `client/EtfNavFetchClient`，GET `all_etf.txt` 解析 `{"a1":[...]}`（跳過無 `msgArray` 的空物件、
      數值過 `parseDecimal` 去千分位逗號），取代號／iNAV／證交所折溢價／資料時點，回 `Map<代號, EtfNav>`；失敗回空 Map 不拋出。
- [x] 214.3 **ext 抓取（美股）**：`MarketDataFetchService.getUsEtfNav(symbol)` 沿用既有 `getYahooCrumb()`／`yahooApiGet()`（短 UA），
      取 `summaryDetail.navPrice`，`regularMarketTime` 轉紐約日期為資料時點；**折溢價刻意留 null 交由匯出端算**（見 214.6）；
      無 `navPrice`（個股）回 null；401/429 清空 crumb 快取比照既有慣例。
- [x] 214.4 **ext 寫入與排程**：新增 `service/EtfNavCacheWriter`（`price:etfnav:{market}:{code}`、TTL 96h、失敗只 log）
      與 `service/EtfNavPoller`（Task 214 原始台股排程為 `0 2/5 9-13 * * MON-FRI`；**Task 349 起已由 `0 1/2 9-13 * * MON-FRI` 每 2 分鐘契約取代**；交易時段 guard、美股 `0 30 18 * * MON-FRI` NYC、
      開機 warmup 不阻塞、`refreshAll()` 供手動觸發）；`application.yml` 加 `etf-nav.enabled`；
      `InternalPriceController` 加 `POST /internal/etf-nav/refresh`。
- [x] 214.5 **business 讀取**：`PriceQueryService` 新增 `EtfNav` record 與 `getEtfNav(code, market)`（讀 Redis、
      **不 fallback DB**、查無回 `Optional.empty()`）。
- [x] 214.6 **Excel 欄位**：`ExcelExportService.writeLiveAssetsSheet` 增欄 18 淨值(`num4`)／19 折溢價(%)(`num2`)／20 淨值時間；
      折溢價經 `premiumDiscountPct(nav, livePrice)`：**來源有權威值（台股證交所）就用，沒有（美股）就以該列自己的即時價計算**，
      確保列內自洽——首版讓 ext 端用 Yahoo 自己的市價預算，實測 VOO 出現「即時價 683.935／淨值 683.15／折溢價 0.00」
      互相矛盾的列（自行驗算為 +0.11%），故改為現行做法；
      逐 `code|market` 以 `containsKey` 快取（**不可用 `computeIfAbsent`**——它不快取 null，個股會逐列重讀 Redis）；
      `autoSizeColumn` 上界 18→21。注入 `PriceQueryService`。
- [x] 214.7 **排程列表登記**：`SchedulePublicBffController.JOBS` 補台股／美股兩筆（否則排程列表與實作漂移，比照 Task 195 教訓）。
- [x] 214.8 **建置與部署驗證**：`--no-cache` 重 build external-materials-service 與 business-services（兩個 JVM service，
      cached build 易出 stale jar）並 `--force-recreate`；**recreate 後一併 restart bff**（Task 208 的 DNS 陷阱）；
      打 `POST /internal/etf-nav/refresh` 後驗 Redis 有 13 檔台股＋3 檔美股 ETF、個股無 key；
      run-now 產檔驗證 ETF 列有淨值／折溢價／淨值時間、個股列三欄留白，且折溢價與證交所頁面數值一致。
      **實測結果**：warmup 即抓到台股 350 檔名冊、持股 19 檔中 15 檔為 ETF 寫入 Redis，美股 11 檔中 4 檔取得淨值；
      個股（2330／2881／GOOGL）確認無 key（資料驅動判定生效）。產檔後逐列驗算，16 檔 ETF（13 台股＋3 美股）
      折溢價欄與「(本列即時價−本列淨值)/淨值」完全吻合、0 列不一致，5 檔個股三欄留白：
      0050 100.15/98.96=+1.20、00713 60.25/60.65=−0.66、00882 14.56/14.75=−1.29、
      VOO 683.935/683.15=+0.11、SGOV 100.575/100.57098=+0.01。
      註：本任務編號原為 209，因另一 worktree 的「今日交易雷達」先佔用 209（spec 已寫入 main 工作樹），
      依既有慣例避讓改為 210；改編號只動註解與文件字串，不影響已驗證的執行結果，故未重跑部署。
- [ ] 214.9 commit ＋ 兩段式 merge。

---

### Task 215：ETF 淨值與折溢價每日入庫留存（Requirement 34）

**需求對應：** Requirement 34 新增 AC「ETF 淨值與折溢價每日入庫留存」。

**背景：** Task 214 讓匯出檔有了折溢價，但資料只存在 Redis（TTL 96h），過幾天就沒了。使用者要求「每天都抓得到，請記入資料庫」——
目的是累積歷史，日後能回看某檔 ETF 的折溢價常態區間（平常溢價 0.5%、現在 2% 就是買貴了）。

**正規化取捨（重要）：**
- **不存市價**：同一事實已在 `stock_price_history.close_price`，跨表重複違反 CLAUDE.md 的完整正規化。
- **折溢價原樣保存不是存衍生值**：台股該值為證交所發布的權威數字，且其淨值欄在股票型 ETF 已四捨五入至 2 位，
  由淨值反推誤差達 0.07 個百分點、與證交所公告對不起來。屬刻意 denormalization（比照 `asset_snapshot` 匯總欄位例外），
  changeset comment 與 design.md 皆已載明理由。

- [x] 215.1 **spec**：`requirements.md` Requirement 34 增 AC；`design.md` 新增「每日入庫留存（Task 215）」小節；`tasks.md` 本任務。
- [x] 215.2 **Liquibase `v1.65.0-etf-nav-history.sql`**（原編 `v1.63.0`，因與 `v1.63.0-trading-radar-notification.sql` 撞號而改版號避讓；`spec/design.md` 已同步更正，本行 2026-07-30 隨 Task 259 一併修正）：建 `etf_nav_history`（`stock_code`／`market`／`nav_date`／`nav`／
      `premium_discount_pct`／`source`，`uq_etf_nav_code_market_date` UNIQUE ＋ `idx_etf_nav_code_date`），
      冪等寫法（`CREATE TABLE / INDEX IF NOT EXISTS`）；`db.changelog-master.yaml` 尾端註冊。
- [x] 215.3 **`StockSourceQuery.upsertEtfNav()`**：比照既有 `upsertCommodityPrice` 的 select-then-update/insert 形狀（JdbcTemplate，無 JPA entity）。
- [x] 215.4b **美股入庫折溢價**：`resolvePct()`——來源有權威值（台股證交所）就用，沒有（美股）則以 `stock_price_history` 中
      **與淨值同一交易日**的收盤價計算（新增 `StockSourceQuery.findCloseOn()`）；查無同日收盤價回 null 不湊數，
      下一輪抓取會再補。刻意不用即時價（歷史列的值會隨抓取時點漂移）或前一日收盤（實測會把 VOO 真實 +0.003% 放大成 +1.02%）。
      **與匯出欄位語意有別**：Excel 用「該列當下即時價」答「現在買貴了沒」，本表用「該交易日收盤價」答「當日收盤折溢價」。
- [x] 215.4 **`EtfNavPoller` 雙寫**：`refreshTw()`／`refreshUs()` 於寫 Redis 後呼叫 `persist()` upsert 入庫；
      新增 `parseNavDate()` 解析來源資料日（台股 `yyyyMMdd HH:mm:ss`、美股 `yyyy-MM-dd`），**解析不出來就不入庫**
      （不以 `now()` 代入，否則跨日／休市補抓會把資料掛到錯誤日期並被 UNIQUE 固化成假資料）；入庫失敗只記 log。
- [x] 215.5 **收盤後補抓**：新增 `scheduledTwCloseUpdate()`（`0 30 17 * * MON-FRI` TPE ＋ `isTradingDay` guard），
      讓當日最後一次寫入落在收盤後，DB 內該日值具「收盤折溢價」語意；美股沿用既有 18:30 ET 那輪。
- [x] 215.6 **排程列表登記**：`SchedulePublicBffController.JOBS` 補台股收盤後補抓一筆。
- [x] 215.7 **建置與部署驗證**：`--no-cache` 重 build business-services（Liquibase 於其啟動時執行）與 external-materials-service 並
      `--force-recreate` ＋ restart bff；驗 `etf_nav_history` 表已建立、手動觸發後 16 檔 ETF 各一列且數值與 Redis 一致、
      個股無列、重複觸發不新增列（upsert 冪等）。
      **實測結果**：Liquibase 於 business 啟動時 `Run: 1` 執行 `v1.65.0-etf-nav-history`（原編 `v1.63.0`，已避讓）成功建表；
      觸發後入庫 **19 列**（15 台股＋4 美股，含觀察清單的 00719B／00850／QQQ），個股零列；
      重複觸發後仍為 19 列（upsert 冪等）。台股折溢價沿用證交所值（0050 +1.20／00713 −0.66／00882 −1.29）；
      **美股折溢價經同日收盤價驗算逐檔吻合**：VOO 683.935/683.15=0.1149、VT 154.94/154.73=0.1357、
      SGOV 100.575/100.5710=0.0040、QQQ 696.61/695.54=0.1538，`count(premium_discount_pct)` = 19 = 總列數（無缺值）。
      註：首版入庫時美股折溢價為 NULL（Task 214 已把該值改由匯出端計算），本任務補上 `resolvePct()`
      以同一交易日收盤價計算後修正。
- [ ] 215.8 commit ＋ 兩段式 merge。

---

### Task 216：股市大盤指數日線 Excel 匯出（開/高/低/收）與排程自動匯出到指定目錄（Requirement 45）

**背景：** 「股市大盤查詢」頁（`GdpTwseView`）只能看圖，無法取檔。R40/41（油價金價）、R42（匯率）已建立
「手動匯出（選區間＋另存路徑）＋ 每日排程匯出（選時間＋選目錄）」的成熟模式，本任務照該模式補齊本頁，
匯出欄位為使用者指定的**每日開盤／最高／最低／收盤**。兩張日線表本來就存 OHLC（`v1.22.0` 已補 TWSE），
故**不需要任何行情表 DDL 或補抓**——實測 10 個指數 24,588 列 OHLC 全滿。

- [x] 216.1 **spec**：`requirements.md` Requirement 45；`design.md` 對應章節（含與 R42「不設 currency 欄」相反的
      `market` 欄取捨理由）；`tasks.md` 本任務。
- [x] 216.2 **DB**：`v1.66.0-index-export-schedule.sql` 建 `index_export_schedule`（per-owner UNIQUE、時分／`market`／
      子路徑／`range_months`／當日 guard 三欄）；註冊進 `db.changelog-master.yaml`。**寫成冪等**
      （`CREATE TABLE IF NOT EXISTS`）：日後版號避讓改 changeset id 會被 Liquibase 視為新 changeset 重跑，
      非冪等即 `already exists` → business crash loop 整站掛（Task 207 的教訓）。`market` 刻意不設 CHECK——
      合法清單單一來源在 Java 端 `OVERSEAS_INDEX_CODES`，寫進 DDL 會變第二份而漂移。
- [x] 216.3 **產檔**：`ExcelExportService` 新增 `indexLabel(market)`（標籤單一來源，供工作表名／兩種檔名共用）、
      `exportIndexDaily(market, start, end)`、`writeIndexDailySheet`（日期／開盤／最高／最低／收盤五欄，
      日期寫 ISO 文字避免時區偏移，null 留空不補前值）。`TWSE` 走 twse 表、其餘走 us 表，正規化成同一組欄位。
- [x] 216.4 **business 端點**：`MacroHistoryController` 新增 `GET /api/index-daily/export`（白名單
      `OVERSEAS_INDEX_CODES ∪ {TWSE}`，**不含 `SP500TR`**——那是績效比較頁的含息指數，不在本頁下拉）；
      新增 `IndexExportController`（`/api/index-export`：schedule GET/PUT、run-now）＋ `IndexExportDto`。
- [x] 216.5 **排程服務**：`IndexExportScheduleService` 比照 `CommodityExportScheduleService`——每分鐘 poll
      ＋ `now >= 設定時分` ＋ `last_run_date` 當日 guard ＋ `ApplicationReadyEvent` 自癒 ＋ `AtomicBoolean` 防重入；
      路徑 resolve 後 `startsWith(base)` 驗證拒跳脫；`.tmp` ＋ atomic move 寫檔；單一使用者失敗不影響他人。
      背景產檔**不需** `enableFilter`（行情全域），但設定表本身 owner-scoped。
- [x] 216.6 **BFF**：`GdpTwseBffController` 新增 export／schedule GET,PUT／run-now／browse 五支 passthrough；
      browse 沿用既有 `/api/export-schedule/browse`（不新增第六份目錄列舉實作）。
      `SchedulePublicBffController.JOBS` 補「大盤指數匯出」項目（否則排程列表頁與實際排程漂移）。
- [x] 216.7 **前端**：`api/index.js` `gdpTwse` 新增 5 支；`GdpTwseView.vue` 工具列加「匯出 Excel」（開對話框選區間，
      預設帶入目前圖表區間，`showSaveFilePicker` 另存、不支援退回下載）＋「排程自動匯出」設定卡
      （啟用／執行時間／指數／匯出範圍／輸出資料夾＋`el-tree` 選擇器＋立即匯出）。
      「當日」分時模式停用匯出按鈕（分時為 transient 資料，非日線 OHLC）。
- [x] 216.8 **建置與端到端驗證**：JVM 服務一律 `--no-cache` 重 build（cached build 會出 stale jar）；
      recreate business 後**一併 restart bff**（Task 208：換 IP 後 BFF 握舊 IP 整站 500）；
      以容器內 `curl` 帶 `X-User-*` header 驗證匯出內容與 DB 相符（含 OHLC 欄位值逐列比對）、
      租戶隔離、白名單擋未知代碼、路徑跳脫被拒，並實跑一次排程確認落檔。
      **實測結果**：TWSE 2026-07-01~07-17 匯出 12 列，開高低收四欄與 `twse_index_daily_history` 逐列相符；
      N225 排程（近 3 個月）落檔 `/Users/steven/input/index/日經225_1_20260718.xlsx` 共 60 列，與 DB 同區間筆數一致；
      `EVIL`／`SP500TR` 皆回 400；`../../etc` 子路徑回 400；user2 讀到的是自己的預設值（未受 user1 設定影響）；
      新 BFF 路由回 401（＝已註冊、由登入把關，非 404）；`asset-bff` 近期 `Connection refused`／500 計數為 0。
      驗證用的排程設定事後已還原為停用（避免留下使用者未設定過的每日排程）。
      **前端畫面（匯出按鈕／對話框／排程卡）需由使用者於瀏覽器確認**——本頁走 Google 登入，代登入屬使用者本人操作。
- [ ] 216.9 commit ＋ 兩段式 merge。

---

## Task 217：交易雷達判斷邏輯強化（`TW_RULES_V4`，Requirement 43 修訂）

背景：多視角查核發現三項會實際給錯訊號的缺陷（見 spec/design.md「Requirement 43／44 修訂」）。
本 Task 只處理主規則側，通知側在 Task 218。

- [x] 217.1 **大盤新鮮度閘門**：`TradingRadarService.buildMarket()` 以既有交易日曆解析「當前台股交易日」
      （非交易日取最近一個交易日），與最新完成日 K 比對得出 `stale`；`MarketState`／`MarketSummary`
      增加 `stale` 欄位。**不得**新建第二份假日清單，也不得為此補一個大盤即時抓價來源
      （Requirement 43 明訂零外部行情抓取；且 `0000/台股` 本就被 `StockSourceQuery` 排除）。
      > **事實更正（Task 249 逐行查證）：** 該排除**只套在 `stock_alert` 那半段**（`WHERE NOT (stock_code = '0000' AND market = '台股')`）；
      > `SELECT stock_code, market FROM stock_holding WHERE snapshot_id = ?` 那半段沒有任何排除。呼叫端不得把它當成保證，須自行 `remove("0000")`。
- [x] 217.2 **規則引擎套用 stale**：`StockInput` 增加 `marketStale`；`evaluateStock` 於 stale 時不給
      `RISK_ON` +8；`actionFor` 的 `buyGate` 於 stale 時一律 false。`RISK_OFF` 的 −15 與 veto 不受影響
      （只收緊不放寬）。`InstrumentType.BOND` 本就不套大盤閘門，行為不變。
- [x] 217.3 **逆勢「停止續跌」改用完成日 K**：`StockInput` 增加 `completedChangePercent`
      （最近一根完成日 K 相對前一根，取自**還原後**序列以與其他逆勢條件同價基）；
      `evaluateCounterTrend` 的 `stabilized` 改讀此值。盤中即時漲跌幅仍供顯示與單日漲跌扣分使用。
- [ ] 217.4 **降級旗標**：`MarketSummary`／`StockDecision` 增加 `degraded`，僅 `catch` 路徑為 true；
      與既有「資料量不足」的 `dataComplete=false` 語意分離，供 Task 218 判斷是否跳過通知。
- [ ] 217.5 **還原權息長窗偏誤揭露**：`StockDecision` 增加 `distributionAdjustedYieldPct`
      ＝`(1 − 最舊列 scale) × 100`（純衍生值、不入庫）；`DistributionAdjustedPriceService.Adjustment`
      一併回傳該值。**不得**因此取消還原（會讓除息日回到假跌破）。
- [x] 217.6 **RULE_VERSION 升 V4**：`TradingRadarRuleEngine.RULE_VERSION`、前端 fallback 字串、
      spec 內所有版本字樣一併更新，不得殘留 V3。
- [~] 217.7 **前端**：大盤卡 stale 警示**已加但未建置驗證**（此 worktree 無 node_modules）；
      `degraded` 文案與還原權息 tooltip 待 217.4／217.5 完成後再補。原述：大盤卡於 stale 時明示「大盤為前一交易日資料，今日買進訊號暫停」；
      `degraded` 與「資料不足」用不同文案；「還原權息」tag 加 tooltip 說明長窗均線含配息累積。
- [x] 217.8 **測試**：已補 stale 不給加分／關閉買進閘門、stale 時 RISK_OFF 仍 veto、債券不受 stale 影響、
      逆勢 `stabilized` 改讀完成日漲跌幅（盤中翻紅不升級／完成日止跌才升級）。
      backend 全套 64 測試通過。**注意**：驗證 stale 分差時發現 `strongStock` 原始分 121 被 clamp 吃掉 8 分差，
      改用未觸頂輸入才測得出——此即既有「分數飽和使 regime 調整項失效」問題的實證，待後續 Task 處理。
      原述：`TradingRadarRuleEngineTest` 補 stale 不給加分／關閉買進閘門、stale 時 RISK_OFF 仍 veto、
      逆勢 `stabilized` 改基準後盤中不翻轉；`DistributionAdjustedPriceServiceTest` 補累積還原幅度計算。

## Task 218：交易雷達通知抖動抑制（Requirement 44 修訂）

- [ ] 218.1 **Liquibase `v1.67.0`**：`trading_radar_notification_setting` 增加
      `pending_action`／`pending_action_count`／`pending_counter_trend`／`pending_counter_trend_count`、
      `last_notified_at`、`daily_notify_date`／`daily_notify_count`。寫成冪等
      （`ADD COLUMN IF NOT EXISTS`），避免日後版號避讓改 changeset id 被 Liquibase 視為新 migration 重跑。
- [ ] 218.2 **持穩去抖**：`TradingRadarNotificationTransition` 由單次比對改為持穩計數（N=3）；
      未達 N 次只記候選、不更新 baseline、不寄信。門檻集中為具名常數。
- [ ] 218.3 **每日上限與冷卻**：同 `(setting, 狀態)` 每交易日 1 封、同 setting 每日 4 封、冷卻 30 分；
      計數持久化（容器重建不得重置）；每日界線以台北時區交易日為準。
- [ ] 218.4 **降級輪次完全跳過**：`degraded=true` 時不寄信且**不更新** baseline，
      避免「暫時失敗→寫入 NO_TRADE 基準→恢復後誤判轉入」的假訊號對。
- [ ] 218.5 **交易時段閘門**：`queueEvaluation` 先查既有交易日曆，非交易日／非交易時段直接 return；
      只加在雷達通知入口，不影響既有到價警示與 SSE。
- [ ] 218.6 **前端**：通知 dialog 提示相鄰狀態較常觸發，並顯示目前每日上限與冷卻值。
- [ ] 218.7 **測試**：未達持穩不寄、達持穩寄一次、候選中途改變計數重來、同日同狀態第二次不寄、
      冷卻期內不寄、degraded 不寄且不改 baseline、非交易時段不入列；跨日重置以注入固定時鐘驗證。
- [ ] 218.8 **建置與端到端驗證**：JVM 服務 `--no-cache` 重 build；recreate business 後一併 restart bff；
      容器內 `curl` 帶 `X-User-*` header 驗證。
- [ ] 218.9 commit ＋ 兩段式 merge（**待使用者明確指示後才執行**）。

## Task 220：通知評估與派送併入單一 2 秒節拍（Requirement 44 修訂）

- [x] 220.1 `TradingRadarNotificationDispatcher.flush()` 移除 `@Scheduled(fixedDelay = 10s)`，
      改由 `TradingRadarNotificationService` 驅動；移除該類的 `Scheduled` import。
- [x] 220.2 新增非交易性的 `runCycle()` 掛 `@Scheduled(fixedDelay = 2s)`：
      先經自身 proxy（`ObjectProvider<TradingRadarNotificationService>`）呼叫 `@Transactional` 的
      `flushEvaluations()`，**commit 後**再呼叫 `dispatcher.flush()`。兩段各自 try/catch。
      派送不得置於交易內（SMTP I/O 撐長交易；回滾後重寄）。
- [x] 220.3 backend 編譯與全套 64 測試通過。
- [ ] 220.4 **啟動期驗證（未完成）**：自身注入若解析失敗只會在 Spring context 啟動時炸，
      現有測試不涵蓋（專案無 `@SpringBootTest` context-load 測試）。需 `--no-cache` 重建 business
      映像 + recreate + health 確認；因會覆寫全機共用的 `asset-management-*:latest`
      並把運行中的 stack 換成未 merge 的程式碼，**待使用者指示後才執行**。
- [ ] 220.5 修正既有文件對評估頻率的誤述：2 秒為排空節拍，真正評估頻率由 `PricePoller`
      台股 cron `0 0/2 9-13`（每 2 分鐘）決定；Task 218 的去抖 N=3 因此約為 6 分鐘而非 6 秒。（已於
      requirements.md／design.md 更正）

## Task 219：ETF 折溢價納入交易雷達（219.2／219.3 已拆分並完成，219.1／219.4 尚未動工）

> **2026-07-30 動工前盤點更正**：本節原文的兩個前提有誤，已於下方標明並更正，供日後接續 219.1／219.4 時參考，
> 不要重複踩同樣的假設。

- [x] ~~219.2 `etf_nav_history` 增加 `source` 欄位~~——**前提錯誤**：`source` 欄位建表時就存在，
      且已被「淨值提供站台」（`TWSE`／`Yahoo Finance`）占用，與本項想表達的「折溢價怎麼算出來」是不同軸、
      不可合用一欄。已拆出為 **[t259](tasks/t259_etf_premium_origin_no_reverse_calc.md)**，新增獨立欄位
      `pct_origin`（`OFFICIAL`／`RECONSTRUCTED`／`NULL`），並改為對接 TWSE 而非原文設想的 SITCA
      （219.1 的 SITCA 回補尚未動工，故現階段沒有 SITCA 重建值需要標記；美股 Yahoo 反推是唯一既有的
      RECONSTRUCTED 來源）。
- [x] ~~219.3 修既有實作縫~~——已隨 219.2 一併於 **t259** 完成：`EtfNavPoller`（改名 `resolvePremium`）與
      `ExcelExportService.premiumDiscountPct()` 皆已依 market 分流，台股 `g` 欄留白不再反推。
- [ ] 219.1 歷史淨值來源結論（保留原文，未被本次盤點推翻；見 memory `reference_etf_nav_history_sources`）：證交所／櫃買**無** NAV 歷史；MoneyDJ 技術可行
      但 robots.txt 明文 `Disallow: /ETF/X/xdjbcd/` 且聲明禁止 LLM／AI 用途並封鎖 ClaudeBot，**不得**排進
      正式排程；SITCA（投信投顧公會）可行且合規，實測一年 240 個交易日連續無缺口、可回溯至 2015、
      上市與上櫃同一支查詢涵蓋。SITCA 歷史淨值回補（ASP.NET WebForms，需先 GET 取
      `__VIEWSTATE`／`__EVENTVALIDATION` 再 POST；一天一次查詢、COMID 留空回全市場約 4425 筆；
      一年約 240 次，須節流）**尚未動工**。實作前须知：
      本 repo 對 `__VIEWSTATE` 型 ASP.NET WebForms、以及 `application/x-www-form-urlencoded` POST
      **皆無先例**；既有「10 年回補」都是一次 range 查詢，逐日 240 次的既有先例只有 TWSE 報酬指數
      回補一支，節流與 single-flight 防護要另外設計。t259 已把 `pct_origin='RECONSTRUCTED'` 的位置
      空出來給未來的 SITCA 重建值使用，但 219.1 動工前應先確認 SITCA 淨值精度與 0.07pp 反推誤差是否
      成立——`spec/tasks.md`／`spec/requirements.md` 內現有的「反推誤差 0.07pp」記載講的是**證交所**
      股票型 ETF 淨值四捨五入至 2 位所致，與 SITCA 無關。**SITCA 本身的精度已另有查證**：memory
      `reference_etf_nav_history_sources` 記載 SITCA 股票型 ETF 淨值同樣僅小數 2 位（債券型 4 位）、
      反推誤差同為約 0.07pp（與證交所巧合同一精度），但**該記錄不在 repo 追蹤範圍內**，動工時應以此為
      查證起點覆核，而非視為全新未知、也不應誤以為兩個數字互不相干的巧合各自成立而重新驗證。
- [ ] 219.4 雷達第三軌「折溢價狀態」（**尚未動工**，且動工前應先評估效益）：不改分數、不改 action，
      但溢價顯著高於該檔自身常態時關閉買進閘門；樣本不足時降級為分類別絕對門檻並於前端揭露。
      **原文「比照 stale 的處理方式」是錯的範本**——`marketStale` 其實會改分數（`RISK_ON` 加分被抽掉），
      並非純閘門；真正「不改分數、不改 action 映射、只關閉買進閘門並於收合列揭露」的既有前例是
      `kdHeat`（Task 232），動工時應照抄它的落地形狀（enum 三態 → `StockResult` → `StockDecision` DTO →
      前端收合列 tag，且刻意不進匯出、不進通知）。**效益務必先評估**：買進閘門只在 `score ≥ 75` 時才有
      任何作用，且十年 103,040 個交易日的實測顯示 `TRIAL_BUY`（抄底路徑）與買進閘門**共存 0 次**——
      即這個第三軌完全不影響 `TRIAL_BUY`；另外現有折溢價資料**台股**每檔僅 10 個交易日、sd 介於
      0.14–0.47pp，**美股**僅 9 個交易日、sd 介於 0.01–1.13pp（波動遠大於台股、樣本亦更不足，兩市場
      不可套同一組門檻），皆不足以定義「自身常態」，需等 219.1 的 SITCA 回補補齊歷史後才可能算出有意義的門檻。

---

### Task 221：濾除「影響不了全球經濟／股市」的雜訊新聞（Requirement 31 / EditorialNewsFilter）

**需求對應：** Requirement 31 之「台灣中文新聞編輯收錄政策」新增 AC（Task 199 政策的收緊）。

**背景：** 使用者於 2026-07-19 回報兩則實際爬進 `news_headline` 的雜訊，**成因完全不同**，故需兩條規則：

1. 「防囚犯越獄！以色列修法 鱷魚可部署監獄周邊」— 命中 `GEO_REGION`(以色列)＋`GEO_TRIGGER`(部署)，被規則④判 `KEEP:market-geopolitics`。
2. 「68歲退休翁嫌定期定額賺太慢！看到半導體股狂飆就衝了 下場曝光」— 標題必帶財經詞，在規則①即被 `KEEP:finance` 攔下。使用者原話：「這種新聞看似和股市有關，實則是都市傳說，對於判斷股市走向沒有意義」。

**關鍵設計決定：**

- **第 2 類需要一條凌駕 `FINANCE` 的規則**，這是 Task 199 建立 cascade 以來的第一次。`FINANCE` 原本是所有否決規則的唯一豁免，一命中就沒有任何後續規則有機會發言；而此文類的標題**必然**帶財經詞，不放在 `FINANCE` 之前就永遠攔不到。因風險最高，觸發條件設計得極窄：需**同時**滿足「`N歲`」與（身分稱謂｜釣魚詞）。
- **`SOCIAL_ODDITY` 放在 `FINANCE` 之後、其他所有規則之前**（非 `LIFESTYLE` 的位置之後）。放在規則④之後則輪不到發言（鱷魚那則在規則④就被 KEEP）。
- **刻意保留 `GEO_TRIGGER` 的「部署」**。前一版實作曾移除它，經對抗式稽核實測會誤殺「南韓同意部署薩德系統」「伊朗在荷姆茲海峽部署新型快艇」「以色列在加薩邊界部署重兵」等真正影響市場者；且語料中「部署」有效樣本僅 1 則（n=1 推不出誤判率）。該版本已整份廢棄重做。
- **不收裸的「退休」**，只收 `退休翁`／`退休師`／`退休族` 等複合詞——裸詞會誤殺「勞工65歲退休可請領月退金」這類退休金政策。

**刻意排除的候選詞（經語料實測會誤殺，勿再加入）：** 判刑／監禁／起訴／收押／交保／法院／檢方／刑事、領照／換發（台灣媒體主流用法是**建照／使照領照量**＝營建股房市領先指標）、車禍（「馬斯克自駕計程車連環車禍遭監理機關調查」）、推擠（法案闖關標準寫法）、罹難／翻覆／空難、走私（「防中國走私輝達晶片」）。

- [x] 221.1 **spec**：`requirements.md` Requirement 31 新增 AC（兩條規則、零誤殺驗證數據、刻意排除詞清單）；`design.md` cascade 段插入 ⓪ 與 ①b 兩條；`tasks.md` 本任務。
- [x] 221.2 **`EditorialNewsFilter` 新增 `ANECDOTE`**：`AGE` regex `[0-9]+歲` ＋ `ANECDOTE_ROLE`（翁／婦／大叔／阿公／阿嬤／少年／女孩／人妻／寶媽／童／高管／新貴／退休翁／退休師…）＋ `ANECDOTE_BAIT`（曝光／後悔／崩潰／心酸／驚見／翻身／賺翻／狂賺／親吐／心路／秘訣／逆襲／大公開…）；`isAnecdote()` 需兩者皆命中；置於 cascade 最前。
- [x] 221.3 **`EditorialNewsFilter` 新增 `SOCIAL_ODDITY`**：越獄／囚犯／監獄／獄方／獄警／典獄／鱷魚／蟒蛇／動物園／追晉／褒揚令；置於 `FINANCE` 之後、`LIFESTYLE` 之前。`GEO_TRIGGER` 不動。
- [x] 221.4 **全語料校準**：以 2005 則真實 ltn／udn 標題（`news_headline` 匯出）跑新舊版逐則對照。**KEEP→DROP 翻轉 15 則**（12 則理財軼事、2 則軍人榮典、1 則鱷魚），逐則檢視皆為應濾除者；**零反向翻轉**。含「N歲」者 43 則中命中 34 則，全語料命中總數亦為 34 → 不含「N歲」的標題完全不受影響，爆炸半徑為零。分佈：`finance` 1287→1275、`polity-strong` 105→103、`market-geopolitics` 64→63、`non-finance-general` 245→224（原本就 DROP、僅換 reason）、新增 `anecdote` 34／`social-oddity` 3。
- [x] 221.5 **單元測試**：`EditorialNewsFilterTest` 新增 `Anecdote`（5 案）與 `SocialOddity`（4 案）兩個巢狀類，含兩則使用者回報案例判 DROP，以及必保回歸錨點（青安3.0／巴菲特／張忠謀／薩德／荷姆茲／領照／車禍／推擠／交保）判 KEEP。
- [ ] 221.6 **建置與部署驗證**：`--no-cache` 重建 `external-materials-service` 並 recreate，確認下一輪爬取不再收錄該兩類（**待使用者指示；依共用 stack 規則需從 main 的 worktree 重建**）。
- [ ] 221.7 commit ＋ 兩段式 merge。

### Task 246: 今日股市分析批次收尾 poller 於 virtual thread 下不執行 — 改平台執行緒排程器 ＋ client 逾時（Requirement 31 bug fix）

對應 Requirements: 31

> **編號說明**：本工作原於 2026-07-18 於分支 `claude/happy-williams-a4c86c` 以 **Task 152** 記錄，
> 但該編號在 main 上已被「資產配置建議（Requirement 32）」占用並歸檔於
> `spec/tasks/archive/tasks-151-200.md`。兩者為完全不同的工作，故本次併入 main 時避讓為 246。

**背景**：實機發現手動分析批次在 Anthropic 端送出後約 7 分鐘即 `succeeded`（results 端點秒回、結果完整），但 `daily_market_analysis` 一直停在 `PROCESSING`、未落 OK 也未寄信。診斷：`spring.threads.virtual.enabled=true` 下，Boot 為 `@Scheduled` 配置 virtual-thread `SimpleAsyncTaskScheduler`，唯一的 `fixedDelay` 任務 `MarketAnalysisScheduler.pollBatches`（收尾 poller）不週期執行——thread dump 顯示排程 clock 執行緒存活但收尾從未觸發、日誌全無 finalize/例外，重啟仍複現；其餘 7 個 `@Scheduled` 皆 cron、症狀不明顯。若放任，批次連 12h 逾時判 FAILED 都不會觸發。

- [x] 246.1 spec：`requirements.md` Requirement 31 加「收尾 poller 於 virtual thread 組態下可靠執行」驗收項；`design.md` Requirement 31 設計段加「排程執行緒（`SchedulingConfig`）」說明；本 Task。
- [x] 246.2 後端：新增 `config/SchedulingConfig`——定義名為 `taskScheduler` 的 `ThreadPoolTaskScheduler`（pool=3）bean，使 Boot virtual 排程器退讓（`@ConditionalOnMissingBean(TaskScheduler.class)`），`@Scheduled` 改跑平台執行緒（解 fixedDelay 不重排 ＋ SDK on VT 阻塞 ＋ 單執行緒餓死）。Web 層仍維持 virtual threads。
- [x] 246.3 後端加固：`MarketAnalysisService.client()` 建 `AnthropicOkHttpClient` 時加 `.timeout(Duration.ofSeconds(90))`，避免單次 retrieve/results 呼叫長時間阻塞排程執行緒。
- [ ] 246.4 `--no-cache` 重 build business-services、recreate；驗證重啟後 poller 於平台執行緒週期執行、既有排程未回退（**待使用者指示；依共用 stack 規則需從 main 的 worktree 重建**）。
