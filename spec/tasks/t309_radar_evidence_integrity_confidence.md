# [t309] 交易雷達資料時效、證據信心與買進完整性閘門

**對應 Requirements:** Requirement 65（交易雷達 V13——證據完整度、資料時效與樣本外校準）
**前置任務:** t290（可信收盤 gate）、t298（V12 因子同源修正）、t292（基本面 observation）、t274（共用 sigma primitive）
**Liquibase changesets:** `v1.93.0-radar-evidence-integrity.sql`, `v1.95.0-etf-nav-observation.sql`

## 背景

V12 的技術序列只在 live `tradingDate` 等於標的市場當地日期時才併入，但 `buildStock` 的規則價仍直接取 Redis-first `getLive()`；因此 stale live 可被排除於均線序列，卻仍作為 BIAS、MA 比較與 52 週位置的分子。ETF premium 的 Redis 路徑驗證 NAV 日期，PostgreSQL fallback 只取 latest value，過期值仍可能觸發 3% 買進否決。台股債券 ETF 的 currency null 又會默認 TWD；運行中 00695B／00751B／00865B 都是美元底層卻缺 mapping。

另一個結構問題是 optional factor 缺值後全體重配：分數可算不代表證據完整。V13 必須把 opportunity、downside risk、evidence confidence 分開，並以 confidence/group gate 限制 BUY／ADD，不把 missing 當 neutral，也不把 confidence 再加進 opportunity score。

## 要做什麼

- [ ] **309.1 Accepted price 單一來源。** 新增可無 Spring context 單測的 `RadarObservationResolver`（或等價純 service），輸入 completed rows、raw live、display/trusted close、market、`decisionInstant`，輸出 `AcceptedPrice(value,tradingDate,updatedAt,source,quality,liveAccepted)`。規則：live 日期可解析、等於 `decisionInstant` 在 `MarketZones.resolve(market)` 的日期、且確實會併入序列才接受；否則忽略 live，改用最新可信完成列。`TradingRadarService` 的規則價、顯示價、MA/BIAS/52 週位置與 `RadarInputAssembler` 全部使用同一 accepted value。不得向前跳過 target date 拿任意舊價冒充當日；資料不足則 `NO_TRADE`。
- [ ] **309.2 Dated ETF premium。** repository 查詢改回傳含 `navDate` 的 observation，不再只回 BigDecimal。Redis 與 DB fallback 都以 decision instant 當時已完成的 target market date 驗證；不符時 `premiumPct/premiumPercentile=null`、`premiumStale=true`，不得進 3% veto。`StockDecision` 新增 `premiumAsOfDate`、`premiumSource`、`premiumStale`。台股與美股 target date 必須按各自市場時區／完成日解讀；美股 ETF 仍依既有規則不進正式 premium factor。
- [ ] **309.3 外幣 master-data fail-closed。** `v1.93.0-radar-evidence-integrity.sql` 以冪等 UPDATE 把 `(00695B,台股)`、`(00751B,台股)`、`(00865B,台股)` 的 `underlying_currency` 補為 `USD`，不得覆寫非空且非 USD 的人工值而不告警。`underlyingCurrencyOf` 對台股 BOND 的 null 不再直接回 TWD：若 `AssetClassifier.classifyBondTerm` 或名稱規則判定為外國債，回 incomplete result；`currencyDataComplete=false` 時短／中期 BUY、ADD、TRIAL_BUY 全部降級，reasons/risks 明示缺漏。一般台幣股票／股票 ETF 仍可推斷 TWD；美股 USD、英股 GBP 行為不變。changeset 註冊 master，既有 v1.69.0 不修改。
- [ ] **309.4 雷達專用 strict AssetProfile。** 不直接使用既有 `classifyBondTerm()` 的 unknown→MID fallback，也不得把持股快照殖利率傳給 `classifyStockStyle()`。新增不可變 `AssetProfile(assetClass,assetClassSource,assetClassComplete,instrumentKind,instrumentKindSource,instrumentKindComplete,stockStyle,stockStyleSource,stockStyleComplete,bondTerm,bondTermSource,bondTermComplete,quoteCurrency,quoteCurrencySource,quoteCurrencyComplete,underlyingCurrency,underlyingCurrencySource,underlyingCurrencyComplete,currencyDataComplete,profileComplete,missingReasons)`；`instrumentKind` 值域固定 `STOCK|EQUITY_ETF|BOND_ETF|UNKNOWN`，由 production/backtest 共用同一 resolver 依序採 `asset override → code/name ETF/BOND rule → market-default STOCK`，無法唯一判定則 UNKNOWN，不得各服務自行判斷。`instrument override` 不屬目前 master-data／settings/API/UI 契約，production/backtest 一律不得傳入或依賴；若保留相容測試 overload，只能作未接線的未來擴充測試入口，不能改變正式分類。每個 profile 欄位都有自己的 source/complete 語意，不能以整體 `profileComplete=true` 掩蓋單欄 UNKNOWN。來源值域固定 `OVERRIDE|CODE_RULE|NAME_RULE|PUBLIC_VALUATION|MARKET_DEFAULT|UNKNOWN`。asset class override 優先，其次既有代碼/名稱規則；BOND term 只有 override 或名稱明確命中 SHORT/MID/LONG 才有值，否則 UNKNOWN。股票 style 依序為 override、具名高股息 ETF 規則、decision-time 公開 valuation yield 與全域 style threshold；公開 yield 缺漏時 UNKNOWN，不默認 GROWTH。禁止讀成本、持股配息率、配置或 owner 財務欄位。美股 quote/underlying market default USD；一般台股股票 quote/underlying TWD；台股外國 BOND underlying 缺漏須 incomplete。
- [ ] **309.5 Evidence groups 的 component 契約。** 新增純技術 enum/record（不是可管理業務分類）：`PRICE_TECHNICAL`、`MARKET_LIQUIDITY`、`VALUATION`、`FINANCIAL_OPERATING`、`ASSET_SPECIFIC`、`PUBLIC_EVENT`。每個 component 回 `applicability=AVAILABLE|MISSING|STALE|NOT_APPLICABLE`、weight、asOf、provider、missingReason；`sourceCount` 只計去重後的 provider identifier，不計資料列數。組內 coverage=`fresh available component weight/applicable component weight`，N/A 退出分母，missing/stale 留在分母；group `available=coverage>0`，`fresh=mandatory component fresh && coverage>=0.70`。矩陣固定如下：

  | Group | Short components | Medium components | Freshness / applicability |
  |---|---|---|---|
  | PRICE_TECHNICAL | accepted price .25；MA5 .15；KD/J/W%R .15；MACD+RSI+BIAS（全有才算）.25；volumeRatio .20 | accepted price .20；MA20/60/240 confirmations（全有）.35；KD/J/W%R .10；MACD+RSI+BIAS .20；volumeRatio .15 | accepted price 為 mandatory；各衍生值 terminal date 必須等於 accepted price date，且 price date 必須是 `DecisionMarketClock` 的 current accepted live date 或 target completed session。volume 對該市場/標的無有意義欄位時 N/A，否則缺值是 missing。 |
  | MARKET_LIQUIDITY | regime .70；market index volume/turnover context .30 | 同 short | regime mandatory，`marketStale=false` 且 context asOf 等於該市場 decision 所需的 current/last-completed session；無有意義 volume 的 index 為 N/A，不可用 0 冒充。 |
  | VALUATION | N/A | PE/PB/dividend-yield 各 1/3 | 僅非 ETF EQUITY 適用；同 provider/decision as-of，最新 valuation date 距 decision date <=10 calendar days且各 component 自身有效歷史>=250。loss flag 是可用的 PE 負證據；某欄缺漏只扣該 1/3，不能讓另一欄取得整組 0.20。 |
  | FINANCIAL_OPERATING | N/A | EPS .35；ROE .35；revenue .20；industry .10 | 僅非 ETF EQUITY；financial period 依 3/31、5/15、8/14、11/14 公開截止日契約，revenue 在每月 11 日後要求上月、之前要求前兩月。美股沒有 monthly revenue/industry 時兩者 N/A，EPS/ROE 重配為各 .50。fallback ROE 的 component available weight 再乘 .75。 |
  | ASSET_SPECIFIC | applicable items equal-weight | 同 short | 台股 ETF premium；underlying!=quote 的標的 FX；BOND rate。僅在 profile 適用才入分母。美股 USD 股票 ETF 三者全 N/A 時整組 N/A，不封鎖 VOO。適用項全部 missing/stale 才是 group missing。 |
  | PUBLIC_EVENT | disclosure only | disclosure only | 不進 confidence；market-scoped news 與 known-at distribution 只列 provenance。 |

  短期 group weights：PRICE .50、MARKET .30、ASSET .20；中期：PRICE .30、MARKET .20、VALUATION .20、FINANCIAL .20、ASSET .10。group N/A 退出分母。最終 confidence=`round(100*Σ(groupWeight*groupCoverage)/Σ(applicableGroupWeight))`，不得看多空方向。
- [ ] **309.6 Action confidence gate。** candidate action 仍由 opportunity/timing 產生，再只向保守方向降級：BUY／ADD／TRIAL_BUY 必須 PRICE 與 MARKET group `fresh=true` 且 confidence>=70；所有對該軌/標的適用的額外 group 也必須 coverage>=70：中期 EQUITY 的 VALUATION 與 FINANCIAL_OPERATING 各自 >=70，適用的 ASSET_SPECIFIC >=70；N/A group 退出分母。ETF/BOND 不要求個股財報，但若 ASSET group 有適用 component 卻 coverage<70 仍降級。currency incomplete 一律禁止買進。低 confidence/missing 絕不可產生 REDUCE/EXIT，降級不改 opportunity score；另回 `candidateAction/actionGateReasons`。
- [ ] **309.7 Deterministic downside risk。** 每軌回 nullable 0–100、`riskCoverage` 與 components；component risk unit 全部 clamp 0..1：timing（EXTREME_OVERBOUGHT=1、OVERBOUGHT=.5、其他=0）weight25；market（RISK_OFF=1、NEUTRAL=.25、RISK_ON=0、DATA_INCOMPLETE=missing）weight20；move/volatility weight20＝可用項 `abs(completedChangePct)/7` 與 `returnStdDev60Ratio/.03` 的 max；liquidity weight15＝volumeRatio<=.5 時1、.5<r<.8 時 `(.8-r)/.3`、>=.8 時0；asset weight10＝適用且 fresh 的 `max(clamp(max(premiumPct,0)/3), clamp((fxPercentile-50)/40), rateRiskUnit)`；event weight10＝配息/除息在 5 sessions 內1、6–20內.5、否則0。N/A 退出分母；applicable missing 不當 0，降低 `riskCoverage=availableWeight/applicableWeight`，score 只在 availableWeight>0 時為 `round(100*Σ(weight*riskUnit)/availableWeight)`，否則 null。rateRiskUnit 在 t275/t308 promotion 前 missing。此值在 t308 前只揭露、不改 action，也不是跌幅機率。
- [ ] **309.8 DTO、快照、匯出與 Vue。** `StockDecision`、Redis snapshot、JSON/Excel 增加 accepted price provenance、strict profile、兩軌 confidence/downside/riskCoverage、component/evidence groups、candidate action/gate reasons、currency completeness、premium provenance；舊快照缺欄可讀。收合列至少顯示短/中期信心，展開列顯示 component、資料日期與缺漏原因；前端不得重算。通知只追 final 中期 action，V13 最終升版前不部署。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true test
(cd frontend && /Users/steven/.nvm/versions/node/v22.21.0/bin/npm run build)
docker compose -p asset-management build business-services external-materials-service bff frontend
docker compose -p asset-management up -d --no-deps --force-recreate business-services external-materials-service bff frontend
docker compose -p asset-management ps
docker compose -p asset-management exec -T business-services wget -qO- http://localhost:8080/api/snapshots
docker compose -p asset-management exec -T external-materials-service wget -qO- http://localhost:8080/internal/health
docker compose -p asset-management exec -T business-services sh -c 'wget -qO- --header="Content-Type: application/json" --post-data="{}" http://localhost:8080/internal/backtest/rules | head -c 1000'
curl -fsS http://localhost:8080/actuator/health
curl -fsS http://localhost:80/
: "${BFF_COOKIE:?set BFF_COOKIE to a valid authenticated session cookie before API verification}"
curl -fsS -H "Cookie: ${BFF_COOKIE}" http://localhost:8080/api/bff/trading-radar
docker compose -p asset-management exec -T postgres psql -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" -c "SELECT stock_code,market,underlying_currency FROM stock WHERE market='台股' AND stock_code IN ('00695B','00751B','00865B') ORDER BY stock_code;"
```

至少直接測試：stale/invalid/future live 不作規則價；同日 live 同時作規則價與序列；DB stale premium 不觸發 veto；三檔債券 USD migration 與未知外幣債券 fail-closed；confidence 不受方向影響；missing 重配後高分仍被 action gate 降級；ETF 不被個股財報群組懲罰；舊 snapshot 相容；Excel headers/formats/rows 等長。實機須查 SQL mapping，並抽查 API 每檔 accepted price/as-of 與 confidence gate。

## 完成報告

（回填實際檔案、測試數、三檔 currency SQL、stale live/premium 構造案例、實機 confidence 分布與被降級的動作數；不得只寫「測試通過」。）
