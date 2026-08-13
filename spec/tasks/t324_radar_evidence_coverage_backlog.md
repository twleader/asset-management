# [t324] 交易雷達證據覆蓋缺口盤點登記（實測基準；各子項動工時另開任務檔）

**對應 Requirements:** Requirement 43（今日交易雷達——不呼叫 AI API 的台股規則式買賣決策輔助）、
Requirement 34（ETF 淨值與折溢價每日入庫留存）、Requirement 46（**台股**個股基本面資料每日抓取與歷史落地）、
Requirement 64（交易雷達納入美股個股評分，畫面改為「台股」「美股」兩個分頁）、
Requirement 65（交易雷達 V13——證據完整度、資料時效與樣本外校準）
**前置任務:** 無
**Liquibase changeset:** 無（本任務只登記量測結果，不動 schema、不動程式碼）

## 背景

使用者要求盤點「交易雷達該改善而未改善的項目」。本檔的**單一交付是「登記」**：把一次完整的實機量測
與其成因查證固定下來，讓後續每個缺口都能各自開一支自足任務檔動工，而不必重跑一次全面量測。
**本檔自身不含任何實作**；`spec/tasks/README.md` 鐵則 1「一檔一任務＝一個可獨立驗收的交付」在此的
交付即為這份可複驗的登記，各子項一律標示「動工時另開任務檔」。

本次盤點中**唯一可立即實作**的缺口（美股大盤量能未接線）已抽成獨立任務
`spec/tasks/t323_radar_us_market_volume_wiring.md`，不在本檔範圍。

### 量測基準（2026-08-13 00:41 Asia/Taipei，實機唯讀）

以 business-services 容器內 `GET /api/trading-radar`（`X-User-Id: 1`）當下結果為準：共 **32 檔**
（台股 21、美股 11），`ruleVersion=TW_RULES_V12`、`actionPolicyVersion=EVIDENCE_GATE_V1`。
逐一統計 `evidence.evidenceGroups[*].components[*].applicability`（**下表為全部 component，未省略**）：

| group | component | AVAILABLE | STALE | MISSING | N/A |
|---|---|---|---|---|---|
| PUBLIC_EVENT | dividend_event | 0 | 0 | **32** | 0 |
| PUBLIC_EVENT | industry_public_info | 1 | 0 | 31 | 0 |
| PUBLIC_EVENT | company_public_info | 9 | 0 | 23 | 0 |
| MARKET_LIQUIDITY | market_volume_turnover | 0 | 21 | **11** | 0 |
| MARKET_LIQUIDITY | regime | 11 | 21 | 0 | 0 |
| VALUATION | pe／pb／dividend_yield（各） | 4 | 0 | **7** | 21 |
| FINANCIAL_OPERATING | roe | 6 | 0 | 5 | 0 |
| FINANCIAL_OPERATING | eps | 7 | 0 | 4 | 0 |
| FINANCIAL_OPERATING | revenue／industry（各） | 1 | 0 | 3 | 7 |
| ASSET_SPECIFIC | etf_premium | 17 | 0 | 0 | — |
| ASSET_SPECIFIC | fx | 5 | 0 | 0 | — |
| ASSET_SPECIFIC | bond_rate | 6 | 0 | 0 | — |
| PRICE_TECHNICAL | 六項 | 30–32 | 0 | 0–2 | 0 |

> **`company_public_info`／`industry_public_info` 的 MISSING 不是缺口、無待辦。** 其 `missingReason` 為
> 「無個股公開事件」「無產業公開事件」，即當期查無符合事件；來源管線正常（`news_headline` 9416 列、
> 最新 2026-08-13）。列在表上只為完整揭露。
>
> **`market_volume_turnover` 的 11 檔 MISSING 已由 t323 承接**（美股列未接上既有 IXIC 量能）；
> 台股 21 檔的 STALE 是量測當下為盤前的既有誠實語意，非缺陷。

對應 DB 覆蓋（**append-only 觀測表的列數會隨每次抓取增加，覆蓋應以相異 (代號, 期別) 為準**；
下列列數僅為該時點快照，複驗時數字變大屬正常）：

- `stock_valuation_daily`：20676 列／**11 檔**（台股 2330／2881／2885／2891 自 2021-08-09；美股 7 檔僅 2026-08-09 起 4 天）
- `etf_nav_history`：台股 **17 檔 298 列**、美股 **4 檔 76 列**，起始 2026-07-17。**分佈不均**：
  多數 ETF 各 19 筆，`009816` 僅 9 筆（自 2026-07-31）、`009826` 僅 4 筆（自 2026-08-07）
- `stock_financial_quarter` 384 列／10 檔；`stock_monthly_revenue` 1009 列／4 檔
  （其中 `2881` 有 355 列但只有 59 個相異期別）；`industry_monthly_revenue` 122 列／35 產業
- `stock_dividend_snapshot`／`stock_dividend_snapshot_event`／`stock_dividend_fetch_observation`／
  `stock_dividend_fetch_attempt`：**四張皆 0 列**
- `stock_dividend_history`（現況投影表，另一條寫入路徑）：1005 列／49 檔，最後除息日 2026-09-04
  —— 故**還原權息序列不受影響**

## 要做什麼

- [x] **324.0（已登記） `dividend_event` 全滅——由另一 worktree 的任務承接，本檔只負責回補後複驗。**
      32/32 檔 MISSING，`missingReason`「沒有 decision-time 前且 scope 覆蓋未來 45 日的完整配息 snapshot」。
      根因是 `java.time.Instant` 被直接綁進 `JdbcTemplate` 參數（PostgreSQL driver 不支援，
      Spring 轉譯成 `BadSqlGrammarException`），且**有兩個獨立位置**：
  - **寫入端**（external-materials-service）：`DividendSnapshotStore` 的 `appendEvents(...)`、
    fetch-observation insert 與 `recordAttempt(...)` 三處綁 `Instant`；該方法標 `@Transactional`，
    故整筆 rollback ⇒ 上述**四張**表恆為空。
  - **讀取端**（backend）：`repository/JdbcDividendEventEvidenceRepository` 的 `loadObservations(...)`
    把 `Instant latestDecision` 直接綁進 `jdbc.query(...)`，且以 `catch (Exception) { return List.of(); }`
    靜默吞掉 ⇒ `resolveBatch()` 恆回空 ⇒ `Resolution.MISSING`。
    **只修寫入端不會讓 `dividend_event` 變 AVAILABLE。**
  - 該修復由另一 worktree（`hidden-key-toggle-47285e`）的任務檔
    `t322_dividend_history_instant_binding_500.md` 承接（該檔原編 321，因 main 已 landed
    `t321_china_branch_personal_crime.md` 而改號為 322）。**本檔與 t323 皆不得碰該修復涉及的程式碼。**
  - **複驗歸屬 t322，不歸本檔**（本檔的完成不得綁在別的 worktree 的交付上）：請 t322 的完成報告
    涵蓋以下複驗——確認 `stock_dividend_snapshot` 非空、
    `stock_dividend_fetch_observation.complete=true` 且 `scope_from ≤ decisionDate`、
    `scope_to ≥ decisionDate + 45 日`；再取一次 `GET /api/trading-radar`，斷言 `dividend_event`
    的 AVAILABLE 檔數 > 0，且有未來除息事件的個股其 `nextDistributionDate`／`nextDistributionStatus` 非 null。
    **診斷順序**：若表已非空但 `dividend_event` 仍 0 檔 AVAILABLE，先確認讀取端
    `JdbcDividendEventEvidenceRepository` 的綁定已修；確認後才輪到 fetch client 的 scope 取數視窗。

- [x] **324.1（已登記） 美股個股估值歷史（PE／PB／殖利率）——7 檔 100% MISSING（動工時另開任務檔）。**
      `FundamentalAnalysisService` 的 `PE_MIN_SAMPLES = 250`：`valuationComponent(...)` 對每個 provider
      要求至少 250 筆有效歷史，不足即 `continue`，**五個** provider
      （`PROVIDERS = List.of("EXCHANGE","SEC_EDGAR","YAHOO","WANTGOO","FINMIND")`）全數落空後回 `null`；
      `valuationComposite(...)` 隨即 `if (available.isEmpty() && rawYield == null) return null;`，
      呼叫端 `analyze(...)` 再以 `valuation == null ? null : valuation.asFactor()` 帶出——**值與分位一起消失**。
      美股估值自 2026-08-09 才落地（4 筆／檔），故 AMZN／NVDA／AVGO／MSFT／TSM／GOOGL／COIN
      七檔 `missingReason` 皆為「估值欄位／250 筆歷史不足」、VALUATION 整組 `availableWeight=0`。
      動工者須先確認來源，**不得假設「換一支 URL 就能補」**：現行美股 PE／PB 取自 Yahoo `quoteSummary`，
      **只有當期快照、無歷史**。兩條路線擇一，各自查證授權與 robots：
  - **(a) 由既有資料推導——但覆蓋有硬限制，動工前必須先接受**：`stock_financial_quarter` 由
    SEC EDGAR `companyfacts`（官方 XBRL）落地 EPS／淨利／權益，實測
    **只覆蓋 6 檔**（AMZN／NVDA／AVGO／MSFT／GOOGL／COIN，各 9–12 季）、**起點只到 2024Q1**，
    且 `equity_parent` 61 列中有 10 列為空（PB 可推導區間更短）；**`TSM` 在該表為 0 列**
    （外國發行人走 20-F，不在現行 `companyfacts` 落地範圍），故七檔中 TSM 走不通路線 (a)，
    須另指定來源或明列為本路線不覆蓋。`stock_price_history` 美股有 2016-08-12～2026-08-11
    共 40553 列／17 檔收盤，可供組出 PE（收盤 ÷ TTM EPS）與 PB（收盤 ÷ 每股淨值）。
    **此為推導值**，必須比照 `t259` 對 ETF 折溢價的既有作法誠實標記 provenance（該檔：
    「美股續行反推，但必須誠實標記為 `RECONSTRUCTED`」），且**不得與 Yahoo 當期快照混進同一
    provider 的歷史序列後一起算分位**。
  - **(b)** 接一個提供歷史估值的授權來源，落地為獨立 provider。**抓取／落地一律寫在
    `external-materials-service`**：現行 `stock_valuation_daily` 的**寫入端**是該服務的
    `FundamentalObservationStore`（由 `StockFundamentalPoller` 驅動），**抓取 client** 才是
    `MarketDataFetchService`；新 provider 兩處都要處理。`business-services` 不得直連任何外部估值來源，
    只讀 `stock_valuation_daily`。
  - **兩條路線共同的兩個必辦前置**（漏了會「資料入庫但永不生效且無任何日誌」）：
    (i) 新 provider 字串**必須同步加進 `FundamentalAnalysisService.PROVIDERS` 白名單**——
    `valuationComponent(...)`／`latestRevenueBasis(...)` 都只在該清單內迴圈並以
    `provider.equals(r.provider())` 過濾，清單外的 provider 會被靜默丟棄；
    (ii) `stock_valuation_daily` **目前沒有 provenance 欄**（欄位為 `id, stock_code, market,
    trading_date, pe_ratio, pb_ratio, dividend_yield_pct, pe_loss_flag, provider, source_urls,
    source_available_at, availability_basis, observed_at`），t259 的 `pct_origin` 作法無法照抄，
    推導值要用「新增欄位」還是「編碼進 provider 字串」須在子任務中先定案；
    (iii) 落地推導值屬**刻意的 denormalization**（PE＝收盤 ÷ TTM EPS、PB＝收盤 ÷ 每股淨值，
    兩張來源表都在庫，直接違反 CLAUDE.md「禁止存入可從其他欄位計算得出的衍生值」），
    子任務必須在 spec 明列理由（分位計算需要固定且可重現的觀測序列，逐次重算會讓歷史分位隨資料修訂漂移），
    比照 `asset_snapshot.total_*` 的既有加註體例。
  - **門檻不得順手調降**：本專案已有「無量測依據門檻清單」的既有體例——`spec/requirements.md`
    列出 OSC 全幅 `0.5%`／文案門檻 `0.1%`、乖離自身分位 `98`／`2` 與最少樣本 `120`、ROE 斜率 `10`、
    通知冷卻 `60` 分鐘，並註明「**皆無回測量測依據，為判斷性取值**；Requirement 56 的回測工具
    可於日後量測後調整，任何文案不得宣稱這些門檻能提升報酬或降低風險」。`PE_MIN_SAMPLES=250`
    同屬未經回測量測的取值，要調整必須先以 t273 的回測工具量測、比照該體例登記結果，且
    **不得在任何註解或 UI 文案宣稱調整後的門檻有實證支持**。

- [x] **324.2（已登記） ETF 折溢價分位——0/32 有值；台股不可回補，美股被規格明文排除（動工時另開任務檔）。**
      `etf_premium` 本身 17/17 AVAILABLE，但 `TradingRadarService.ETF_PREMIUM_MIN_SAMPLES = 60`
      而每檔僅約 19 筆（`009816` 9 筆、`009826` 4 筆），故 `etfPremiumPercentile` 全為 null。
      `t264` 規劃時已逐字預告「分位因子（權重 `0.06`）在上線後約 3 個月內對每一檔 ETF 恆為 `null`，
      由權重重分配吸收」——**現況與規劃一致，不是 bug**。
  - **台股不得以反推回補。** `t259` 已明訂：台股淨值欄在股票型 ETF 四捨五入至小數 2 位，
    自行以 (市價−淨值)/淨值 反推誤差達 0.07 個百分點、與證交所公告值對不上，故台股折溢價
    **只接受來源直接公告**（`pct_origin=OFFICIAL`）。既有調查亦確認官方不提供歷史淨值、
    MoneyDJ 的 robots.txt 禁止 AI 取用、SITCA 雖有歷史淨值但**無折溢價欄**。
    因此台股 ETF 折溢價歷史**沒有合規且精度足夠的回補路徑**，只能等每日累積至 60 筆
    （多數檔約 2026-10 中旬；`009816`／`009826` 因起算日較晚須再延後約 1–2 個月）。
    **想「補歷史淨值再自己算」等同繞過 t259 紅線，必須先推翻該 AC 並記錄理由，不得默默實作。**
  - **美股 ETF 不是「補資料就能生效」。** `TradingRadarService.etfPremiumObservation(...)` 開頭即
    `if (US_MARKET.equals(market)) return PremiumObservation.unavailable(false);  // 美股 ETF 折溢價因子明確排除（Task 294.5）`，
    其依據是 Requirement 64 的 AC「美股 ETF 個股本次仍以一般 EQUITY 規則評分，折溢價因子恆為不適用」。
    美股 NAV 其實**已在每日累積**（4 檔 76 列，其中 36 列標 `RECONSTRUCTED`），缺的只是 2026-07-17 以前的歷史；
    但在推翻上述 AC 與移除該短路之前，**補再多歷史 NAV 都不會改變任何輸出**。動工前必須先處理規格層面。

- [x] **324.3（已登記） 產業／營收覆蓋——`industry` 僅 1/32 有值；目前無事可做（動工時另開任務檔）。**
      `industryRevenueYoyPct` 只有 2330 有值（57.02）。2881／2885／2891 的 `stock_monthly_revenue`
      最新期別停在 `202606`（2330 為 `202607`），而 `FundamentalAnalysisService.latestRevenueBasis(...)`
      只接受「`industryName` 非空且期別新鮮」的列，故三檔金控的 `revenue`／`industry` 皆 MISSING。
      **已查證（2026-08-13）為來源端未發布、非我方漏抓**：
      `https://openapi.twse.com.tw/v1/opendata/t187ap05_L` 當期為資料年月 `11507`、共 1069 檔，
      金融保險業 16 檔（含 2884 玉山金、2890 永豐金），**2881／2885／2891 皆為 0 筆**。
      動工前請重新查證同一端點；若屆時三檔已出現而我方仍缺，才是抓取端問題。

- [x] **324.4（已登記） 美股指數日線落後一盤，且 self-heal 的新鮮度判準與交易雷達不一致（動工時另開任務檔）。**
      2026-08-13 21:58 Asia/Taipei（＝ET 09:58，8/12 那盤早已收盤）實測：
      `us_index_daily_history` 的 IXIC `max(trading_date)` 仍為 **2026-08-11**，缺 8/12；
      而同一時刻 `IndexDailyRefreshScheduler` 的 startup self-heal 明確輸出
      **「self-heal：海外指數日線皆為最新，略過」**。
      該排程為 `@Scheduled(cron = "0 0 7 * * TUE-SAT", zone = "Asia/Taipei")`，
      台北 8/13 07:00 ＝ ET 8/12 19:00，**在 8/12 收盤（16:00 ET）之後**，理應已能取得 8/12 的日線。
      連帶效果：交易雷達美股列的 `marketStale` 判準是
      `latestEodDate < mostRecentCompletedUsTradingDay`，故只要指數落後一盤，
      `regime`（權重 `.70`）與 `market_volume_turnover`（權重 `.30`）就會整組轉 stale，
      美股短期信心度由 89 掉到 63（實測，見 t323 完成報告的前後比對）。
      **動工者要查的是兩件事**：(i) 8/12 的日線為何沒進來（排程當下服務是否在跑／來源是否回空／是否被
      self-heal 的判準擋掉）；(ii) self-heal 的「最新」定義為何與雷達的
      `mostRecentCompletedUsTradingDay` 不一致——兩者對「該有哪一天」的認定必須同源，
      否則 self-heal 會在真的落後時回報正常。**不得只把 self-heal 的門檻放寬**：
      那只會讓它更常宣稱正常，不會讓資料變新。

## 驗證

本檔的驗證＝「上表每個數字都能以下列指令複驗，且每個子項都有可獨立動工的自足描述」。
複驗任一數字一律以實機唯讀取得
（本機既有使用者為 `X-User-Id: 1`）：

```bash
docker compose -p asset-management exec -T business-services curl -fsS \
  -H 'X-User-Id: 1' -H 'X-User-Role: ADMIN' -H 'X-User-Status: ACTIVE' \
  http://localhost:8080/api/trading-radar

docker compose -p asset-management exec -T postgres sh -c \
  "psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -At -F'|' -c \"SELECT market, count(DISTINCT stock_code), count(*), min(nav_date), max(nav_date) FROM etf_nav_history GROUP BY 1;\""
docker compose -p asset-management exec -T postgres sh -c \
  "psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -At -F'|' -c \"SELECT count(*) FROM stock_dividend_snapshot;\""

curl -sA 'Mozilla/5.0' https://openapi.twse.com.tw/v1/opendata/t187ap05_L | head -c 400
```

> **股利同步的日誌證據不可用 `docker logs` 複驗。** 原始觀測（2026-08-12 17:00–17:03 +08，
> `股利歷史同步完成：成功 0 檔、失敗 49 檔`）所屬容器已於 2026-08-13 01:14:58Z 重建，舊日誌不可得。
> 該同步由 `external-materials-service` 的 `DividendPersister.scheduledSyncAll()` 驅動，
> cron 為 `0 0 17 * * MON-FRI`（zone `Asia/Taipei`），輸出字串為
> `股利歷史同步完成：成功 {} 檔、失敗 {} 檔`；複驗請等次一次 17:00 排程或手動觸發後再看日誌。

## 完成報告

**本檔的完成判準是「登記內容經 spec-review 採納」，不綁任何子項的交付。**

- 2026-08-13：完成量測與成因查證並登記如上；經兩輪獨立 `spec-auditor` 對抗式查證後修正
  （第一輪 5 critical／5 major、第二輪 1 critical／5 major，皆已依 findings 修正）。
  本次盤點中唯一可立即實作的缺口已抽成 `t323_radar_us_market_volume_wiring.md`。
  324.0 的複驗歸 t322（另一 worktree）承接，324.1–324.3 動工時各自另開任務檔。
