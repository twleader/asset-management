# [t292] 今日交易雷達納入個股基本面與產業發展（`TW_RULES_V11`）

**對應 Requirements:** Requirement 43、46、59、60
**前置任務:** t291（短期／中期雙軌與完整技術面）
**取代任務:** t266、t267（資料管線與評分接線一併在本任務落地）
**Liquibase changeset:** `v1.90.0-stock-fundamental-industry.sql`

## 背景與邊界

使用者於 2026-08-08 追加：「如果是個股，財報、基本面要特別注意，產業的發展也是重點。」現況是
`stock_valuation_daily`／`stock_financial_quarter`／`stock_monthly_revenue` 在 active code 與實際 DB 都不存在，
因此不得只改畫面文案宣稱已納入。`public_info_<日期>.json/.xlsx` 的上游事實來源是 PostgreSQL
`news_headline`，可先提供個股財報、營運與產業新聞證據，但其 schema 沒有可信 EPS／ROE／PE 數值欄位，
不得把新聞文字硬解析成分數。數值缺項再依使用者指定順序從交易所、Yahoo、玩股網、FinMind 補齊；官方
TWSE／TPEx OpenAPI 可提供當期估值、財務報表、月營收與產業別，歷史財報與月營收只能自本任務上線後累積。
交易雷達請求仍只讀本地 PostgreSQL／Redis，不得在 GET、手動 refresh、SSE 重算或通知鏈直接呼叫外部來源、
crawler 或 AI／LLM。

本任務只對**台股個股**套基本面與產業因子；ETF（股票／債券）四項基本面與產業因子皆為 `null`，由既有
`Accumulator` 重分配權重。標的範圍仍是使用者持股與觀察清單；crawler 可為計算全市場產業彙總讀整批資料，
但個股明細只落使用者範圍內的台股代號。評分不讀成本、資金、配置、理財目標或風險承受度。

## 292.1 `public_info_*` 優先與結構化資料落地

- [ ] 新增以下全域公開資料（無 `owner_user_id`、不套 owner filter），所有缺值維持 `NULL`，不得補 0：

  ```text
  stock_valuation_daily
    id bigint PK; INDEX(stock_code, market, trading_date, observed_at)
    pe_ratio / pb_ratio / dividend_yield_pct numeric(12,4), pe_loss_flag boolean
    provider varchar(20), source_urls jsonb, source_available_at timestamptz,
    availability_basis varchar(20), observed_at timestamptz

  stock_financial_quarter
    id bigint PK; INDEX(stock_code, market, fiscal_year, fiscal_quarter, observed_at)
    eps numeric(12,4), net_income_parent / equity_parent bigint
    provider varchar(20), source_urls jsonb, source_available_at timestamptz,
    availability_basis varchar(20), observed_at timestamptz

  stock_monthly_revenue
    id bigint PK; INDEX(stock_code, market, revenue_year, revenue_month, observed_at)
    industry_name varchar(100), revenue / prior_year_revenue bigint,
    revenue_yoy_pct numeric(12,4), provider varchar(20), source_urls jsonb,
    source_available_at timestamptz, availability_basis varchar(20), observed_at timestamptz

  industry_monthly_revenue
    id bigint PK; INDEX(industry_name, revenue_year, revenue_month, observed_at)
    revenue / prior_year_revenue numeric(24,0), revenue_yoy_pct numeric(12,4),
    company_count integer, provider varchar(20), source_urls jsonb,
    source_available_at timestamptz, availability_basis varchar(20), observed_at timestamptz
  ```

  `market` 一律為 `台股`。`revenue_yoy_pct` 與產業彙總是刻意 denormalization：來源直接提供個股去年同期，
  產業列則由同輪全市場 `sum(current)/sum(prior)-1` 算出，保留當時來源快照以供 as-of 回放；changeset 與
  欄位註解須明載例外。官方 `source_available_at` 取 `出表日期`／`Date` 對應的完成時點；fallback 無發布時間時
  只可保存第一次抓到的 `observed_at` 並標 `availability_basis=OBSERVED`，不得冒充官方公布日。
  每筆 observation 只含同一 provider family 的欄位；`source_urls` 保存該筆所有實際端點（例如同一份財報的損益表
  與資產負債表兩支 URL），`source_available_at` 取其最晚可用時點。禁止把 EXCHANGE 的 EPS 與 FINMIND 的權益
  寫成同一筆。API 的逐來源揭露單位是 EPS、近似 ROE、營收、PE、產業五個衍生因子；每個因子只能由單一
  observation/provider 算出。
  四表都是**不可回寫過去的 observation history**：同業務期間可有多個 provider 與修正版；解析時先限制
  `source_available_at <= decisionInstant AND observed_at <= decisionInstant`，再逐衍生因子選 provider rank 最小、
  同 rank 取 `observed_at` 最新者；EPS／ROE／三月營收等多期因子所需的全部 observation 必須屬同一 provider family。
  重跑若與該 provider 最新一筆所有欄位相同則不新增；值變動才 append 新版，絕不覆寫舊列或
  保留舊 availability，否則修正版會被回測看成早已存在。
- [ ] 雷達先以 `PublicInfoEvidenceResolver` 查 `news_headline`（亦即 `public_info_*` 匯出來源），近 120 日內依完整
  股票代號／名稱與產業名稱選最多 5 筆個股、5 筆產業證據。匹配只決定相關性，不作情緒打分；代號必須有字元
  邊界，名稱／產業不得作任意子字串模糊匹配。查無只表示沒有公開資訊證據，不得變成負分。
- [ ] `external-materials-service` 新增整批 client／poller，第一順位使用官方端點：
  - TWSE：`BWIBBU_ALL`、`t187ap05_L`、`t187ap06_L_{ci,fh,ins,bd,mim,basi}`、
    `t187ap07_L_{ci,fh,ins,bd,mim,basi}`。
  - TPEx：`tpex_mainboard_peratio_analysis`、`mopsfin_t187ap05_O`、
    `mopsfin_t187ap06_O_{ci,fh,ins,bd,mim,basi}`、`mopsfin_t187ap07_O_{ci,fh,ins,bd,mim,basi}`。
  上市／上櫃中英文 key 必須由具名候選欄位解析；損益與資產負債表以 code/year/quarter merge，缺任一側仍保留
  可用欄位。金融股不得因只抓 `_ci` 而整段消失。HTTP 設 connect 10 秒、request 30 秒、最多一次退避重試；
  逐來源 fail-soft，不得逐檔輪詢。
- [ ] 目標個股某衍生因子在官方整批來源仍缺時，依 `EXCHANGE(1) -> YAHOO(2) -> WANTGOO(3) -> FINMIND(4)` 逐因子補值；
  `PUBLIC_INFO` 是 rank 0 的公開資訊證據而非無結構數值。Yahoo／玩股網／FinMind 只查官方缺項的持股／觀察代號，
  不得全市場掃描；玩股網若只有新聞資料，該數值保持缺漏並繼續 FinMind。每個 provider adapter 回傳來源 URL、
  period、availability、實際 `source_urls` 與欄位 map；缺 publication time 採第一次觀測時間。不得跨 provider 拼同一個 EPS/ROE 計算，
  低順位非 null 不得覆寫高順位非 null，全數失敗維持 null。既有 MOPS robots 禁抓邊界不變。
- [ ] 個股明細落地前以 `StockSourceQuery.collectTwRadarCodes()` 過濾；產業彙總使用同輪 TWSE＋TPEx 月營收全市場
  有效列，避免把使用者清單誤當產業母體。虧損公司估值列的 `pe_ratio` 可為 null，但整列仍須落地；當日無列才是
  未抓到。只有來源明確以空 PE 表示虧損時才寫 `pe_loss_flag=true`；provider 不支援、欄位缺漏或全 fallback 失敗時
  `pe_loss_flag=null`，不得因同列 PB／殖利率存在就推定虧損。append-on-change 與同值重跑 no-op 必須可測。
- [ ] 排程沿用 `crawler_schedule`，`crawler_key='fundamental'` 預設 15:30；每分鐘 tick 讀 DB 時點，DB 例外才
  fallback。`ApplicationReadyEvent` 僅在當日尚無任一成功來源時補跑，`AtomicBoolean` 防重入；提供
  `POST /internal/fundamentals/refresh` 手動入口。BFF 排程清單新增一筆並同步總數／分組數註解。

## 292.2 基本面與產業 as-of 計算

- [ ] 新增 `FundamentalAnalysisService`，production 與 backtest 都傳顯式 `decisionInstant`。資料列的
  `source_available_at <= decisionInstant` 才可用；官方估值日按台股 14:00、官方財報／營收／產業按出表日 18:00，
  fallback 則按首次觀測 instant。未來列即使已在 DB 亦不得讀，回測不得讀 `updated_at` 來猜可用日。
- [ ] 財報來源數字為年度累計值。先把同一會計年度 Q1 視為單季，Q2/Q3/Q4 各減前一季，才可組 TTM；不得直接
  把四筆累計數相加。任一必要前季缺漏時該 standalone quarter 為 null。
- [ ] 四個基本面子因子沿用 t267 的方向，但以修正後單季序列計算：

  | 子因子 | 定義 | `[-1,+1]` | null／特殊語意 |
  |---|---|---|---|
  | EPS 年增率 | 最近四個單季 EPS 合計 vs 前四個單季 | `+20%=>+1`、`-20%=>-1`、線性 | 不足 8 單季或前四季合計 `<=0` 為 null |
  | 近似 ROE | 最近四個單季母公司淨利合計 ÷ 最新期末母公司權益 | `15%=>+1`、`5%=>-1`、線性 | 不足 4 單季或權益 `<=0` 為 null；文案明示不是正式 ROE |
  | 月營收 YoY | 最近 3 個月來源 YoY 算術平均 | `+15%=>+1`、`-15%=>-1`、線性 | 不足 3 個月為 null |
  | PE 自身分位 | 最新有效 PE 在自身有效歷史的百分位 | `<=20=>+1`、`>=80=>-1`、線性 | 只有 `pe_loss_flag=true` 才是虧損並直接 `-1`；flag null／無列＝null；非虧損樣本 <250＝null；EPS 因子 `-1` 時 PE 正貢獻改 null |

- [ ] 產業發展因子取該股最新可用月營收列的 `industry_name`，再取同年月全市場產業彙總；公司數至少 3、去年同期
  營收須正。貢獻 `clamp(industryRevenueYoyPct/15)`。不得由新聞標題關鍵字推導產業方向，也不得以個股自身營收
  取代產業母體。API 揭露產業名稱、YoY、公司數與資料年月。
- [ ] ETF 判定順序：(1) `etf_nav_history` 有任一列；(2) 台股代號 `00` 開頭。不得新增硬編 `security_type`
  業務分類規避專案的設定資料鐵則；個股基本面整筆缺失不得被當成 ETF。ETF 的基本面／產業欄均 null，
  `fundamentalApplicable=false`。

## 292.3 `TW_RULES_V11` 雙軌權重與動作閘門

- [ ] `RULE_VERSION` 與前端 fallback 升為 `TW_RULES_V11`。既有十四因子重配後，短期／中期各自精確合計 1：

  | 因子 | 短期 | 中期 |
  |---|---:|---:|
  | MA5 | 0.05 | 0.02 |
  | MA20 | 0.06 | 0.05 |
  | MA60 | 0.04 | 0.08 |
  | MA240 | 0.02 | 0.07 |
  | KD/J | 0.10 | 0.04 |
  | MACD | 0.08 | 0.05 |
  | RSI | 0.06 | 0.04 |
  | W%R | 0.04 | 0.03 |
  | BIAS | 0.07 | 0.08 |
  | 個股相對量 | 0.08 | 0.05 |
  | 市場環境 | 0.09 | 0.06 |
  | 完成日漲跌 | 0.04 | 0.02 |
  | FX | 0.04 | 0.03 |
  | ETF 折溢價 | 0.03 | 0.02 |
  | EPS 年增率 | 0.03 | 0.07 |
  | 近似 ROE | 0.03 | 0.07 |
  | 月營收 YoY | 0.04 | 0.05 |
  | PE 自身分位 | 0.02 | 0.05 |
  | 產業營收 YoY | 0.08 | 0.12 |
  | **合計** | **1.00** | **1.00** |

  optional null 一律由同一 `Accumulator` 重分配，不得填 0。個股中期基本面＋產業占 36%，高於短期 20%，符合
  財報／產業對 1–6 月獲利機會更重要的時間尺度；ETF 因不適用而把權重分配回技術面與市場面。
- [ ] 基本面不直接製造賣出：避免財報公布後追殺。若可用基本面中至少兩項 `<=-0.8`，或 PE 虧損 `-1` 且另一項
  `<=-0.5`，兩軌一般 BUY／ADD／TRIAL_BUY 均關閉並揭露「基本面多項惡化」；既有極端超賣保護與多證據獲利
  了結規則不變。正基本面也不得繞過止跌、過熱、匯率、溢價或 stale 閘門。

## 292.4 API、畫面、快照、匯出與回測

- [ ] `StockDecision` 追加 `fundamentalApplicable`、`fundamentalCoverage`（0–4）、EPS YoY、近似 ROE、三月營收
  YoY、PE 分位／可信虧損旗標、財報／營收／估值資料日與**逐因子 provider/sourceUrls**，以及產業名稱／YoY／公司數／資料年月、
  個股／產業 `public_info_*` 證據。兩軌 reasons/risks 分別說明貢獻；缺值明示「資料累積中，權重已重分配」，
  fallback 已用則明示來源，不得宣稱已完整評估。
- [ ] `TradingRadarView` 個股展開列新增「基本面與產業」區塊；收合列至少顯示 `基本面 n/4` 與產業 YoY。頁首明示
  不考慮個人理財條件、財報歷史需累積、跨標的因缺值重分配而組成不同。ETF 顯示「不適用個股財報」。
- [ ] Redis snapshot／Excel／JSON round-trip 全部保留新欄；舊快照缺欄可讀。個股匯出追加基本面／產業欄，公開資訊
  分頁不拿新聞文字打分。通知仍只追蹤中期 action；V11 首輪依既有 rule-version 機制只重建 baseline、不寄信。
- [ ] 回測共用 `FundamentalAnalysisService` 的 as-of resolver；資料開始累積前的歷史 signal 自然為 null，報告須列每個
  子因子的有效天數與標的數，不得把零覆蓋解讀為因子有效或無效。

## 驗證

- [ ] `public_info_*` 同源查詢、代號邊界／完整名稱／產業精確匹配、無證據不扣分；parser 覆蓋 TWSE／TPEx、
  六種產業財報 schema、民國日期、空 PE、欄位候選；產業彙總必須用全市場而非目標集。
- [ ] provider chain 覆蓋逐因子 fallback、缺 publication time 用 first-observed、高順位保護、不得跨來源拼接；
  Yahoo／玩股網／FinMind 只收到官方缺項的目標代號，全失敗為 null。
- [ ] observation history 覆蓋同值重跑 no-op、值改變 append、修正版在原 decisionInstant 不可見；同一 observation
  不得跨 provider，合併端點必須完整保存 `source_urls` 並以最晚 availability 守門；五個衍生因子可分別揭露 provider；
  `pe_loss_flag=true/null/false` 分別代表可信虧損／無法判定／有效 PE，不得互換。
- [ ] poller 只落目標個股但產業彙總含全市場；重跑冪等；排程讀 DB；手動端點與 warmup 共用同一路徑。
- [ ] as-of 排除未完成同日與未來列；累計財報正確轉單季；EPS 負基期、PE 虧損優先、樣本不足、EPS 衰退下 PE
  正分禁止皆有直接測試。
- [ ] 個股十九因子兩軌方向與精確權重總和、ETF 五項 null 重分配、held 不改分數、基本面惡化只擋買不強迫賣。
- [ ] API／快照／Excel／JSON／Vue 可見 coverage 與產業資料，舊快照可讀，通知只看中期；active code 註解不再宣稱
  基本面不存在或不評分。
- [ ] backend、external-materials-service、BFF 全測試與 frontend build 通過；Docker no-cache 重建並 recreate
  `business-services`、`external-materials-service`、`frontend`，restart BFF；migration、health、手動抓取、實資料 radar
  payload 與 V11 回測皆驗證成功。

## 完成報告

（實作後回填實際各表列數、上市／上櫃／金融股覆蓋、四因子有效比例、產業彙總數與回測 coverage。）
