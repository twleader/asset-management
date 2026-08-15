# [t333] 交易雷達證據缺口複驗與剩餘缺口登記（2026-08-15 實測基準；各子項動工時另開任務檔）

**對應 Requirements:** Requirement 43（今日交易雷達——不呼叫 AI API 的規則式買賣決策輔助）、
Requirement 64（交易雷達納入美股個股評分，畫面改為「台股」「美股」兩個分頁）、
Requirement 65（交易雷達 V13——證據完整度、資料時效與樣本外校準）、
Requirement 34（ETF 淨值與折溢價每日入庫留存）、Requirement 46（台股個股基本面資料每日抓取與歷史落地）
**前置任務:** 無（本檔只登記，不含任何實作）
**Liquibase changeset:** 無

## 背景

`spec/tasks/t324_radar_evidence_coverage_backlog.md` 於 2026-08-13 登記了一次完整的證據覆蓋量測。
本檔做兩件事：**(1) 複驗 t324 登記的五項缺口在 2026-08-15 的現況**（哪些已被後續任務補上、哪些還在），
**(2) 登記本次盤點新發現、但本輪刻意不動工的缺口**，讓每一項日後都能各自開一支自足任務檔動工，
而不必重跑一次全面量測。

**本檔自身不含任何實作。** `spec/tasks/README.md` 鐵則 1「一檔一任務＝一個可獨立驗收的交付」
在此的交付即為這份可複驗的登記。本輪實際動工的兩項已抽成獨立任務檔，不在本檔範圍：

- `spec/tasks/t334_us_valuation_history_derivation.md`（美股歷史估值序列推導落地）
- `spec/tasks/t332_index_daily_freshness_alignment.md`（海外指數日線新鮮度判準與交易雷達同源）

## 量測基準（2026-08-15 13:53 Asia/Taipei，實機唯讀）

以 business-services 容器內 `GET /api/trading-radar`（`X-User-Id: 1`）當下結果為準：共 **32 檔**
（台股 21、美股 11），`ruleVersion=TW_RULES_V12`、`actionPolicyVersion=EVIDENCE_GATE_V1`。
逐一統計 `evidence.evidenceGroups[*].components[*].applicability`（**下表為全部 component，未省略**）：

| group | component | AVAILABLE | STALE | MISSING | N/A |
|---|---|---|---|---|---|
| PUBLIC_EVENT | dividend_event | **32** | 0 | 0 | 0 |
| PUBLIC_EVENT | company_public_info | 9 | 0 | 23 | 0 |
| PUBLIC_EVENT | industry_public_info | 1 | 0 | 31 | 0 |
| MARKET_LIQUIDITY | market_volume_turnover | **32** | 0 | 0 | 0 |
| MARKET_LIQUIDITY | regime | **32** | 0 | 0 | 0 |
| VALUATION | pe／pb／dividend_yield（各） | 4 | 0 | **7** | 21 |
| FINANCIAL_OPERATING | eps | 6 | 0 | **5** | 0 |
| FINANCIAL_OPERATING | roe | 5 | 0 | **6** | 0 |
| FINANCIAL_OPERATING | revenue | 3 | 0 | 1 | 7 |
| FINANCIAL_OPERATING | industry | 2 | 0 | 2 | 7 |
| ASSET_SPECIFIC | etf_premium | 17 | 0 | 0 | — |
| ASSET_SPECIFIC | fx | 5 | 0 | 0 | — |
| ASSET_SPECIFIC | bond_rate | 6 | 0 | 0 | — |
| PRICE_TECHNICAL | accepted_price／ma5／kd_j_wr | 32 | 0 | 0 | 0 |
| PRICE_TECHNICAL | macd_rsi_bias | 31 | 0 | 1 | 0 |
| PRICE_TECHNICAL | volume_ratio | 31 | 0 | 1 | 0 |
| PRICE_TECHNICAL | ma20_60_240 | 30 | 0 | 2 | 0 |

逐檔缺漏與中期信心度（`mediumEvidenceConfidence`，只列非 100 者）：

| 標的 | 市場 | 中期信心 | 缺漏 component |
|---|---|---|---|
| NVDA | 美股 | 56 | pe／pb／dividend_yield／eps／roe |
| TSM | 美股 | 56 | pe／pb／dividend_yield／eps／roe |
| AVGO | 美股 | 67 | pe／pb／dividend_yield／roe |
| AMZN／MSFT／GOOGL／COIN | 美股 | 78 | pe／pb／dividend_yield |
| 2881 | 台股 | 78 | eps／roe／revenue／industry |
| 2885 | 台股 | 82 | eps／roe／industry |
| 2891 | 台股 | 84 | eps／roe |
| 009816 | 台股 | 83 | ma20_60_240 |
| 009826 | 台股 | 65 | macd_rsi_bias／volume_ratio／ma20_60_240 |

對應 DB 覆蓋（**append-only 觀測表的列數會隨每次抓取增加，覆蓋應以相異 (代號, 期別) 為準**；
下列列數僅為該時點快照，複驗時數字變大屬正常）：

- `stock_valuation_daily`：台股 **4 檔 34,007 列**（2021-08-09～2026-08-14）、
  美股 **7 檔 56 列**（2026-08-09～2026-08-15）
- `stock_financial_quarter`：台股 2330 78 列／20 季（2021Q3–2026Q2）、2881／2885／2891 各 152 列／19 季
  （2021Q3–**2026Q1**）；美股 AMZN／AVGO／COIN／GOOGL 各 10 季、MSFT 12 季、NVDA 9 季（皆起 2024Q1），
  **TSM 為 0 列**
- `stock_price_history`：台股 52 檔 106,330 列、美股 17 檔 40,593 列、英股 4 檔 8,031 列
  （皆 2016-08-15～2026-08-14）
- `etf_nav_history`：台股 **17 檔 332 列**、美股 **4 檔 84 列**，皆起 2026-07-17
- `stock_dividend_snapshot`：**330 列**（t324 登記時為 0）
- `stock_dividend_history`：台股 38 檔 633 列、美股 11 檔 488 列
- `us_index_daily_history`：IXIC／SPX／DJI／SOX／SP500TR `max(trading_date)`＝2026-08-14；
  N225／KOSPI／FTSE／DAX＝2026-08-13

## 要做什麼

### 一、t324 五項缺口的複驗結果

- [ ] **333.0（已複驗，已解決） `dividend_event` 由 32/32 MISSING 轉為 32/32 AVAILABLE。**
      t324.0 登記的根因（`java.time.Instant` 直接綁進 `JdbcTemplate` 參數，寫入端與讀取端各一處）
      由另一 worktree 的 `t322_dividend_history_instant_binding_500.md` 修復，已 landed 於 main
      （commit `1ebd83ce`「修復股利歷史時間型別綁定」，merge `bc394d9b`）。
      本次實測 `stock_dividend_snapshot` 已有 **330 列**、`dividend_event` 32/32 AVAILABLE。**本項結案。**

- [ ] **333.1（已複驗，本輪動工） 美股個股估值歷史 7 檔 100% MISSING → 由 t334 承接。**
      t324.1 登記的缺口現況未變（美股 `stock_valuation_daily` 仍只有 7 檔 56 列、起 2026-08-09）。
      本輪採 t324.1 的路線 **(a)「由既有資料推導」**，成果為
      `spec/tasks/t334_us_valuation_history_derivation.md`（Requirement 74）。
      t324.1 當時記載路線 (a)「只覆蓋 6 檔、起點只到 2024Q1」——**起點限制的成因已查明並在 t334 處理**：
      `StockFundamentalFetchClient.SEC_LOOKBACK_YEARS = 3` 把 `companyfacts` 的取數視窗砍在近三年，
      不是來源沒有更早的資料。**「只覆蓋 6 檔」則確認為硬限制**（TSM 見 333.2）。

- [ ] **333.2（新登記，本輪不動工） TSM 的估值與財報在 `companyfacts` 完全取不到。**
      `stock_financial_quarter` 中 TSM 為 **0 列**：外國發行人（foreign private issuer）以 **20-F**
      申報，而 `StockFundamentalFetchClient.extractFacts(...)` 只收 `form ∈ {10-Q, 10-K}`，
      且 `companyfacts` 對 20-F 申報者的 `us-gaap` 節點覆蓋本就不同。連帶使 TSM 的
      `pe`／`pb`／`dividend_yield`／`eps`／`roe` **五個 component 全數 MISSING**，中期信心度 56（與 NVDA 並列最低）。
  - 動工者要先查證的是**來源層面**，不是程式層面：TSM 的 CIK 在 `companyfacts` 底下實際有哪些
    concept／form（可能是 `ifrs-full` 而非 `us-gaap`），以及該資料的期別語意是否與現行
    `buildPeriodMap`／`selectCumulative` 的假設相容。**不得**在未查證前就放寬 form 白名單——
    20-F 是**年報**，把它當季報餵進累計還原邏輯會算出錯誤但不拋例外的單季值。
  - 若 `companyfacts` 確實取不到，替代來源須各自查證授權與 robots.txt，且**抓取一律寫在
    `external-materials-service`**（`business-services` 不得直連任何外部估值來源）。
  - 在此之前，TSM 五項維持 MISSING 是**誠實揭露**，不得為了讓數字好看而以同業推估或當期快照填補。

- [ ] **333.3（新登記，本輪不動工） AMZN／COIN 的「不配息」與「查無資料」無法區分。**
      `stock_dividend_history` 對 AMZN 與 COIN **零列**。t334 的推導規則是「D 之前查無任何除息紀錄
      → `dividend_yield_pct` 寫 `NULL`」，故這兩檔的 `dividend_yield` component 會維持 MISSING。
      這是刻意的保守做法：本專案既有結論是「查無配息不是 bug，也不得捏造入庫」
      （累積型台股 ETF 006205／00642／00646／00865B 本就不配息的既有案例）。
  - **代價要說清楚**：這兩檔的 `VALUATION` group 只會有 `pe`／`pb` 兩項 AVAILABLE，
    group coverage 為 2/3 ≈ 67%，**低於 Requirement 65 evidence gate 要求的 70%**，
    故中期 BUY／ADD／TRIAL_BUY 仍會被閘門擋下（只到 WATCH／HOLD）。
    這比現況（三項全 MISSING、coverage 0%）嚴格來說是改善，但**不足以解鎖買進動作**。
  - 要真正解決，須有一個可查證的「該公司當期不發放現金股利」事實來源，才能把
    `dividend_yield` 判成 `NOT_APPLICABLE`（N/A 退出 coverage 分母）而非 MISSING。
    現行 `stock_dividend_fetch_observation`／`stock_dividend_snapshot` 這一組表就是為了記錄
    「某次抓取確實成功且 scope 涵蓋某區間但查無事件」而存在（t322 修好後已有 330 列），
    動工者應先確認該表對美股標的的實際 scope 語意，再決定能否據以判定 N/A。
    **不得**用「表裡沒有列」直接推論「公司不配息」——那正是 t322 修好之前造成 32/32 誤判的同一種錯誤。

- [ ] **333.4（新登記，本輪不動工） 會計年度偏移的美股公司被台股季報截止日誤判為 MISSING。**
      `expectedFinancialPeriodIndex(LocalDate)` 的 Javadoc 逐字寫著
      「依**台灣**季報法定常用截止日推得『現在至少應看到哪一季』：Q1 5/15、Q2 8/14、Q3 11/14、
      年報次年 3/31」，判定方式為 `最新季 periodIndex < expectedFinancialPeriodIndex(decisionDate)`
      即視為「最新季漏抓」。
  - **這個方法在全樹有兩份複本，動工者要改的是 backend 那一份**：
    - `backend/.../service/FundamentalAnalysisService.java:585`——呼叫鏈為
      `:577 financialFresh` → `:389 epsFactor`／`:404 roeFactor`，**決定雷達 `eps`／`roe` 的 applicability，
      也就是本項描述的現象的直接成因**。該處 Javadoc 逐字要求「必須與 external-materials-service 的
      fallback deadline 契約同步」。
    - `external-materials-service/.../service/FundamentalObservationStore.java:220`——呼叫點 `:177
      hasFinancialCoverage`，只決定「還要不要再向外抓」，**不影響雷達顯示**。
    - **任何判準變更必須同步兩份**，否則會出現「外部服務不再重抓、雷達仍 MISSING」或反之。
  - 該判準對**美股**一體適用，但美股公司的會計年度未必對齊日曆年：
  - 實測 2026-08-15（台股 Q2 截止日 8/14 剛過，故期望值為 2026Q2）：
    MSFT 最新為 **2026Q4**（會計年度 6 月底結束）→ 通過；
    NVDA 最新為 **2026Q1**（會計年度 1 月底結束，其下一季要到 8 月下旬才申報）→ **判為 MISSING**；
    AVGO 最新為 **2026Q2**（會計年度 11 月底結束）→ 期別通過，但因 `equity_parent` 全為 null 而
    ROE 仍 MISSING（見 333.5）。
  - **這是假性 MISSING**：NVDA 的財報並沒有漏抓，只是它的 Q2 還沒到申報時點。
  - **要正解需要現在沒有的欄位**：`stock_financial_quarter` 只存 `fiscal_year`／`fiscal_quarter`，
    **不存期間結束日**，故無法從 DB 推得該公司的會計年度起訖，也就無法算出「這家公司現在至少
    應該看到哪一季」。動工時須先決定是否新增期間結束日欄位（需 Liquibase changeset 與寫入端改動），
    或改以「距最新一季的 `source_available_at` 已超過 N 天」這類與會計年度無關的判準。
  - **不得**只是把美股的期望季別往回退一季了事——那會讓「真的漏抓一季」也一併被放過，
    等於用降低偵測力換取好看的數字。

- [ ] **333.5（新登記，本輪不動工） AVGO 的 `equity_parent` 全為 null、`net_income_parent` 幾乎全 null，
      連帶使 ROE MISSING、PB 算不出、且 t334 的基準閘門對它 fail closed。**
      實測 `stock_financial_quarter` 中 AVGO 10 季**每一季的 `equity_parent` 皆為 null**，
      `net_income_parent` **只有 2024Q4 一季非 null**（`5,895,000,000`，且它是第 4 舊的一季、
      **不是最新季**——這正是「由新到舊掃描、掃到最新季就 fail closed」對 AVGO 成立的原因）；
      同表的 MSFT 12 季、NVDA 9 季、AMZN 10 季、GOOGL 10 季兩欄皆有值。
      `FundamentalAnalysisService.roeFactor` 要求最新一季 `equity()` 非 null 且為正，故 AVGO 的 `roe` 恆為 MISSING。
      連帶影響 t334：PB 需要 `equity_parent`（算不出）、每股基準閘門需要推導股數
      （＝淨利 ÷ EPS，幾乎每季都算不出）→ 依 fail-closed 規則 AVGO 多數季度不可用，
      **故 AVGO 很可能不在 t334 的實際覆蓋範圍內**。本項一旦解決，AVGO 的 `roe`、PB 與估值歷史會一併打開。
  - 高度可能的成因（**動工者須實際查證，不得照抄本推測**）：
    `parseCompanyFacts(...)` 只抓 `StockholdersEquity` 這一個 concept，而部分申報者使用
    `StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest`
    或其他 XBRL concept 名稱。查證方式是直接取該公司的 `companyfacts` JSON，
    列出 `facts.us-gaap` 底下所有含 `Equity` 的 concept 與其 `units.USD` 筆數。
  - 若確認是 concept 名稱問題，修法是**在既有 EPS 的 fallback 體例上再加一層**
    （`EarningsPerShareDiluted` 缺則退回 `EarningsPerShareBasic` 已是現成範本），
    而**不是**改動 `selectInstant`／`buildPeriodMap` 的期間判定邏輯。
  - 加 fallback concept 時須注意語意差異：含非控制權益的權益總額**大於**母公司權益，
    直接混用會讓 ROE 系統性偏低。若採用，須在該列留下可辨識的來源標記，
    或明確接受並在 spec 記載這個偏差方向。

- [ ] **333.6（複驗後改列，本輪不動工） 台股金控 2881／2885／2891 的 `eps`／`roe` 有兩個獨立成因，
      解決其一不會讓另一項恢復。**
  - **成因 (a)：Q2 財報尚未入庫。** 三檔的 `stock_financial_quarter` 最新皆為 **2026Q1**
    （2330 已到 2026Q2），而 `expectedFinancialPeriodIndex` 在 2026-08-15 期望 2026Q2，故 `eps` MISSING。
  - **成因 (b)：`equity_parent` 全部為 null，`roe` 因此恆為 MISSING。** 實測逐 provider：
    `2881`／`2885`／`2891` 各 152 列（provider `FINMIND`）**`equity_parent` 非 null 者 0 列**；
    對照 `2330` 的 FINMIND 77 列全部有值、EXCHANGE 1 列有值。
    `FundamentalAnalysisService.roeFactor`（`backend/.../FundamentalAnalysisService.java:402-405`）為
    `if (recent.size() < 4 || !financialFresh(...) || recent.get(0).equity() == null
    || recent.get(0).equity().signum() <= 0) return null;`——**即使 2026Q2 到齊，這三檔的 `roe` 仍會是 MISSING**。
  - 成因 (b) 與 333.5（AVGO 的 `equity_parent` 全 null）**是同一類問題**：來源端的權益欄位取不到。
    差別在 333.5 是 SEC XBRL concept 名稱，本項是 FinMind 金控財報路徑的欄位對應
    （台股金控走 `_fh` 分表，欄位命名與一般業不同——`spec/design.md` 已載明「上市與上櫃 key 命名不一致，
    須建 key 映射表，不得假設同名」，金控分表同理）。動工者須實查 FinMind／TWSE 金控分表實際回傳的
    權益欄位名稱與我方的對應。
  - **成因 (a) 動工前必須先確認這是不是來源端問題**：台灣一般公司 Q2 合併財報申報期限為 8/14，
    但**金融保險業與金控適用不同期限**。若來源端此刻尚未發布，我方就沒有漏抓，
    正確處置是等待而非改抓取邏輯——這與 333.4 是同一類問題（期望值與該類公司的實際申報期限不符），
    差別在 333.4 的成因是會計年度偏移、本項的成因是**行業別申報期限差異**。
  - 查證方式：直接取 TWSE openapi 的金控損益表分表（`t187ap06_L_fh`）確認當期資料年季；
    t324.3 已有同型的查證體例（當時對 `t187ap05_L` 月營收查證後確認為來源端未發布、非我方漏抓）。
  - 若確認來源已發布而我方仍缺，才是抓取端問題，屆時再開任務檔。

- [ ] **333.7（複驗後維持登記，本輪不動工） 台股 ETF 折溢價分位仍 0/17，仍不得回補。**
      `etf_premium` 本身 17/17 AVAILABLE，但 `TradingRadarService.ETF_PREMIUM_MIN_SAMPLES = 60`
      而 `etf_nav_history` 台股 17 檔僅 332 列（每檔約 19.5 筆），故 `etfPremiumPercentile` 全為 null。
      t264 規劃時已逐字預告「分位因子（權重 `0.06`）在上線後約 3 個月內對每一檔 ETF 恆為 `null`，
      由權重重分配吸收」——**現況與規劃一致，不是 bug**。
  - **台股不得以反推回補**（t259 紅線）：台股淨值欄在股票型 ETF 四捨五入至小數 2 位，
    自行以 (市價−淨值)/淨值 反推誤差達 0.07 個百分點、與證交所公告值對不上，故台股折溢價
    **只接受來源直接公告**（`pct_origin=OFFICIAL`）。既有調查亦確認官方不提供歷史淨值、
    MoneyDJ 的 robots.txt 禁止 AI 取用、SITCA 雖有歷史淨值但**無折溢價欄**。
    只能等每日累積至 60 筆（多數檔約 2026-10 中旬；起算日較晚的 `009816`／`009826` 須再延後 1–2 個月）。
    **想「補歷史淨值再自己算」等同繞過 t259 紅線，必須先推翻該 AC 並記錄理由，不得默默實作。**
  - **美股 ETF 不是「補資料就能生效」**：`TradingRadarService.etfPremiumObservation(...)` 開頭即對
    美股短路回 unavailable，依據是 Requirement 64 的 AC「美股 ETF 個股本次仍以一般 EQUITY 規則評分，
    折溢價因子恆為不適用」。美股 NAV 其實已在每日累積（4 檔 84 列），
    但在推翻該 AC 與移除短路之前，**補再多歷史 NAV 都不會改變任何輸出**。動工前必須先處理規格層面。

- [ ] **333.8a（複驗，維持登記） 324.3 產業／營收覆蓋——`industry` 由 1/32 升至 2/11 個股，仍待來源端。**
      t324.3 登記時 `industryRevenueYoyPct` 只有 2330 有值。本次實測 `industry` 為
      **AVAILABLE 2／NOT_APPLICABLE 7（美股）／MISSING 2**、`revenue` 為 AVAILABLE 3／N/A 7／MISSING 1，
      即台股 4 檔個股中已有 2 檔具產業年增值。仍 MISSING 的 2881／2885 與 `revenue` MISSING 的 2881，
      成因與 t324.3 當時查證一致——`stock_monthly_revenue` 最新期別停在 `202606`（2330 為 `202607`），
      `FundamentalAnalysisService.latestRevenueBasis(...)` 只接受「`industryName` 非空且期別新鮮」的列。
      t324.3 已於 2026-08-13 查證 `https://openapi.twse.com.tw/v1/opendata/t187ap05_L` 當期為 `11507`、
      金融保險業 16 檔中 **2881／2885／2891 皆 0 筆**，屬來源端未發布、非我方漏抓。
      **動工前請重新查證同一端點**；若屆時三檔已出現而我方仍缺，才是抓取端問題。

- [ ] **333.12（t334 落地後新登記，本輪不動工） COIN 因虧損季截斷可用區段而未被 t334 覆蓋。**
      規劃時預期 COIN 可被覆蓋，實際落空。2026-08-15 實測：COIN 的 `stock_financial_quarter` 有
      **36 季、`net_income_parent` 與 `equity_parent` 皆完整**（不同於 AVGO 的欄位缺漏），
      但區間內存在單季 EPS／淨利為負的虧損季，依 t334 每股基準閘門的 fail-closed 條款 (iii)
      「單季 EPS 或單季淨利不為正 → 基準不可驗證 → 等同變動點」截斷可用區段，剩餘不足 250 筆，
      故 `deriveAll()` 回報「COIN：無可用推導區段」、三個 component 維持 MISSING、中期信心度停在 78。
  - **這是規則正確運作的結果，不是缺陷。** 該條款存在的理由是：推導股數＝單季淨利 ÷ 單季 EPS，
    EPS 為負時推導股數為負，而「比值 ≥ 1.8」對負數**永遠為 false**，閘門會對整個虧損區段靜默放行
    （fail **open**）——那正是要防的情形。
  - **要覆蓋 COIN，需要的是能在虧損季仍可靠判定每股基準的方法**，而不是放寬條款 (iii)。
    最直接的路徑同 333.11：取得可查證的分割事件與比例來源，屆時基準判定不再依賴推導股數的連續性，
    虧損季就不必再 fail closed。**在那之前不得為了覆蓋 COIN 而放寬閘門。**

- [ ] **333.8（新登記，無須動工，僅供複驗時對照） 三類 MISSING 屬正常語意，不是缺口。**
  - `company_public_info` 23 檔、`industry_public_info` 31 檔 MISSING：`missingReason` 為
    「無個股公開事件」「無產業公開事件」，即當期查無符合事件；來源管線正常
    （`news_headline` 於 t324 量測時為 9,416 列、最新 2026-08-13）。
  - `009816`（`ma20_60_240` 缺）與 `009826`（`macd_rsi_bias`／`volume_ratio`／`ma20_60_240` 缺）：
    兩檔為新上市 ETF，日 K 根數不足以算出長天期均線與指標，**隨時間自然補齊**，
    無任何可加速的手段。t324 量測時 009826 僅 8 根、009816 僅 124 根。
  - `revenue`／`industry` 對 7 檔美股為 `NOT_APPLICABLE`：美股上市公司無等同台股 MOPS 的月營收
    公開揭露義務，`design.md` 已明訂「美股基本面的 coverage 上限為 3，非台股的 4」，屬既定設計。

- [ ] **333.11（新登記，本輪不動工） 以 rebase 延長美股推導估值的可用歷史。**
      t334 的每股基準閘門採 fail-closed：`stock_price_history` 美股收盤**已還原分割**，而 SEC
      `companyfacts` 的舊期別是**申報當下基準**，故只落地「最新一個基準變動點之後」的區段。
      代價是可用歷史短於資料實際深度——例如 NVDA 最近一次分割為 2024-06-10（10:1），
      其推導序列最早只能自該點之後起算。
  - 要延長，須把舊期別 **rebase 到今日基準**：由新到舊掃描推導股數序列，遇到 ≥1.8 倍跳階即累積一個
    還原因子，把更舊季度的每股值乘上該因子。這在數學上直接，但**風險在於「跳階不一定是分割」**
    （大額增資、併購換股也會讓股數跳），錯判會把真實的股本變動當成分割抹平，產生看似合理卻錯誤的歷史。
    動工者須先建立可查證的分割事件來源（而非只靠跳階推測），再實作 rebase。
  - **在有可查證的分割事件來源之前，維持 fail-closed 是正確選擇**，不得為了延長歷史而改用純推測的還原。
  - 同一份 rebase 能力也可解 t334 對股利去重的「取最小值」啟發式——有了明確的分割事件與比例，
    就能直接判定哪一列是已還原基準，不必依賴「正向分割下較小者即已還原」這個推論。

### 二、本檔不做的事

- [ ] **333.9 不得在本檔內實作任何一項。** 每一項動工時各自開 `spec/tasks/tNNN_<slug>.md`，
      並先跑 `bash scripts/spec-check.sh` 查編號撞號（本專案多個 worktree 並行推進 main，
      Task 編號、Requirement 編號與 Liquibase 版號會同時撞）。
- [ ] **333.10 不得為了讓覆蓋率數字好看而放寬任何門檻。** `PE_MIN_SAMPLES=250`、
      `ETF_PREMIUM_MIN_SAMPLES=60`、Requirement 65 evidence gate 的 70% 覆蓋與 70 分信心，
      皆屬未經回測量測的判斷性取值；要調整必須先以 Requirement 56 的回測工具量測、
      比照 requirements.md 既有的「無量測依據門檻清單」體例登記結果，
      且**不得在任何註解或 UI 文案宣稱調整後的門檻有實證支持**。

## 驗證

本檔的驗證＝「上表每個數字都能以下列指令複驗，且每個子項都有可獨立動工的自足描述」。
複驗一律以實機唯讀取得（本機既有使用者為 `X-User-Id: 1`）：

```bash
docker compose -p asset-management exec -T business-services curl -fsS -H 'X-User-Id: 1' -H 'X-User-Role: ADMIN' -H 'X-User-Status: ACTIVE' http://localhost:8080/api/trading-radar
```

```bash
docker compose -p asset-management exec -T postgres sh -c "psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -At -F'|' -c \"SELECT market, count(DISTINCT stock_code), count(*), min(trading_date), max(trading_date) FROM stock_valuation_daily GROUP BY 1;\""
```

```bash
docker compose -p asset-management exec -T postgres sh -c "psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -At -F'|' -c \"SELECT stock_code, market, count(*), min(fiscal_year||'Q'||fiscal_quarter), max(fiscal_year||'Q'||fiscal_quarter), count(*) FILTER (WHERE equity_parent IS NOT NULL) FROM stock_financial_quarter GROUP BY 1,2 ORDER BY 2,1;\""
```

```bash
docker compose -p asset-management exec -T postgres sh -c "psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -At -F'|' -c \"SELECT market, count(DISTINCT stock_code), count(*), min(nav_date), max(nav_date) FROM etf_nav_history GROUP BY 1; SELECT count(*) FROM stock_dividend_snapshot;\""
```

```bash
curl -sA 'Mozilla/5.0' 'https://openapi.twse.com.tw/v1/opendata/t187ap06_L_fh' | head -c 400
```

## 完成報告

**本檔的完成判準是「登記內容經 spec-review 採納」，不綁任何子項的交付。**

- 2026-08-15：完成複驗與新缺口登記。t324 五項（324.0–324.4）中，324.0（`dividend_event`）已由 t322
  解決並複驗結案；324.1（美股估值歷史）本輪由 t334 動工；324.4（海外指數日線 self-heal 判準）
  本輪由 t332 動工；324.2（ETF 折溢價）複驗於 333.7、324.3（產業／營收覆蓋）複驗於 333.8a，兩者維持登記。
  其餘項目（333.2–333.6、333.8a、333.11）登記為待處理，動工時各自另開任務檔。
