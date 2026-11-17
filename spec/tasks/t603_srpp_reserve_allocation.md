# [t603] D-222實質保留金額與配置權威

**對應 Requirements:** Requirement 210（退休配置權威同步）
**前置任務:** t591
**Liquibase changeset:** 無

## 背景

現行17%固定現金與V1固定目標無法呈現385萬元實質保留金額；需同步已核准D222並防止基準被誤用為正式目標。

## 要做什麼

使用者於2026-11-17核准取消固定17%現金目標，保留2026年8月實質生活定存150萬元、活存75萬元、緊急定存80萬元及獨立國泰00865B80萬元。原17%比較額度超過385萬元之餘額由VOO／0050／00881視市場分配；不足由VOO／0050／00881視市場調節，無同輪市場證據支持的具體分攤時不得猜比例或輸出已完成配置。緊急ETF與策略00865B2.5%不重複計入。00919目標2.5%、009826目標2.16666667%；四高股息目標13.33333333%、上限17%；009826每日3張、每週6張，月14萬／21萬不變。熊市僅可動非緊急債券ETF及生活定存150萬元，債券ETF交易後至少總資產3%且緊急ETF不計；保留生活活存75萬元。事故／重大疾病可動緊急資金，或生涯末年且無其他資金時可用；事故醫療每筆最晚12曆月回補，不重設前筆期限。

正式registry採V2文件保留數值基準targets並標示BASELINE_NOT_EXECUTABLE及完整reserveAllocation；模型bytes與bundle仍精確綁定。V1歷史文件繼續可讀。完整報告ALLOCATION_GAP依同輪資產與交易日換算金額及動態目標；缺分攤回MARKET_ALLOCATION_REQUIRED，不能回退固定17%。緊急ETF單列且與策略部位分開；缺獨立帳本時current value保持未知。既有僅支援固定目標的legacy allocation模組在V2下顯示未知，不發布誤導固定配置。涉及VOO／0050／00881目標的交易計算缺有證據的動態目標時保守阻擋；其餘未變更金融gate保留。runtime匯出同步至srpp-simulator且依正式來源產生catalog/provenance，禁止手抄。無新路由、DTO、資料表、行情I/O、下單、寄信或排程。

- [x] 603.1 獨立spec審查並記錄gate。
- [x] 603.2 新增純函式動態配置、V2文件驗證與Ruby/Python/Java一致投影，無效契約拒絕。
- [x] 603.3 完整報告動態facts、緊急ETF分帳與未核准不足額阻擋；舊固定模組不宣稱已完成配置。
- [x] 603.4 同步來源衍生artifact、simulator runtime及相關goldens，source-bound formula identity依原generator更新，不改歷史V1/V2公式。
- [x] 603.5 離線測試、獨立arch審查，交release協調者。

#### D222閉合投影契約

V2根欄位恰為schema、targets、targetSemantics、reserveAllocation；schema恰SRPP_POLICY_DOCUMENT_V2，targetSemantics恰BASELINE_NOT_EXECUTABLE。targets沿V1 ASSET_KEY_V1及canonical Decimal字串、0..1、合計<=1規則；它只作基準，非已完成配置。無D222 enabled時仍產生原V1兩欄位文件。

reserveAllocation恰為下列欄位，全部從execution_policy.reserve_allocation_d222同名投影，不能漏欄或增加欄：decision（字串D-222）；enabled（boolean true）；inflation_base_date（ISO日期2026-08-31）；annual_inflation_rate（canonical Decimal字串0.0225）；living_term_deposit_twd_2026_real（1500000）；living_demand_deposit_twd_2026_real（750000）；emergency_term_deposit_twd_2026_real（800000）；emergency_etf_twd_2026_real（800000）；emergency_etf_symbol（字串00865B）；reference_cash_weight（0.17）；excess_symbols（依序字串陣列VOO、0050、00881，不重複）；excess_requires_explicit_allocation（boolean true）；shortfall_symbols（依序字串陣列VOO、0050、00881，不重複）；shortfall_requires_explicit_allocation（boolean true）。括號中數字一律canonical Decimal字串，拒null/非字串/非canonical/負數；金額須正、年率>=0且<1、比較比重>0且<1。模型可有說明欄位，但投影僅選這14欄；V2文件未知欄位、缺欄、型別錯、日期非canonical或符號錯一律拒絕。三語言用相同合成model輸入比較整份canonical投影（UTF-8排序keys、無空白；檔案最後一個LF），不是只比hash。

以date-base_date的實際日數/365.25與(1+年率)冪次算通膨因子。定存target=(1500000+800000)*factor；活存target=750000*factor；緊急00865B獨立target=800000*factor。delta=total*0.17-3850000*factor。delta!=0時若缺同輪市場證據支持的具體分攤，三檔target為未知並回MARKET_ALLOCATION_REQUIRED，其餘照算，不新增Steven逐輪同意門檻。純函式可以接市場allocation map（帶符號金額）驗算，僅VOO/0050/00881、正差只可非負且合計等於delta、負差只可非正且每檔減額不超基準並合計等於delta，零差不需方案；目前producer無此已凍結來源，正式報告不得憑空建立。

擴充generate-srpp-formula-v3.py來源清單，新增SrppReserveAllocation及任何本輪改動且參與保留額/分帳/熊市資金的Java實作（SrppPolicyDocument、SrppModuleCalculator、SrppReportShortUsCalculator、SrppReportStateCalculator若變動）；V3產物由原generator重產，V1/V2公式不動。離線暫存副本只改helper bytes時--check必須失敗，驗完回復副本，不改正式來源假裝驗收。

D222不足不回退normal_equity_target固定56.53%；牛市日頻核心保留缺動態target則未知，避免誤賣。熊市regime以已驗subaccount的緊急ETF市值從全債剔除；無該證據不算正容量。ladderEvent非緊急總債須等於同輪剔除後總額、eligibleBonds不得超過該值、availableCash不得超過非緊急生活定存可動用額，否則拒絕；不容用緊急ETF補3%底線。不從75萬元生活活存取熊市買股資金。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=SrppPolicyDocumentTest,SrppReportModuleCalculatorTest test
python3 scripts/check-srpp-report-authority.py --srpp-root /Users/steven/.codex/worktrees/d222-reserves/SRPP --check
python3 scripts/generate-srpp-formula-v3.py --srpp-root /Users/steven/.codex/worktrees/d222-reserves/SRPP --check
```

測試涵蓋17%恰等/超出/不足、完整配置總和、通膨、緊急ETF不重計、不足未分攤未知、格式錯誤拒絕、V1歷史相容及既有report回歸。最終兩專案落地／insert-only registry／乾淨main build+recreate business-services與srpp-simulator／精確hash業務API回讀由release協調者順序完成，本階段不部署、不commit或寫DB。

## 完成報告

實作準備及離線驗收完成，交由 release operator 執行兩專案 Git 落地、insert-only registry、正式重建與 API 回讀；本報告不宣稱這些後續發布動作已執行。

- 最終來源：D-222，policy 2.70.0／model 3.25.0；bundle `47ffc4cae823f790c5e8811a0ee3b8b9df44b4b89eb6f690adeca4978151140c`。先前候選 1d717f／424677／e32c795 全部撤銷、未發布。
- 規則與實作：V2正式文件15欄閉合保留額契約、三語言同 bytes；實質385萬元分帳與非緊急ETF3%底線；動態三檔目標及INITIAL／LIVING獨立選檔；缺原文FX／方向容量與生活子帳時維持具名未知；TW／US核心生產入口不拿未知target放行，短線既有SELL保持獨立。一般基金不能充ETF底線；INITIAL不可行配置早期拒絕，LIVING資金流不受初始target solver誤擋。
- 產物：model／manifest原bytes、canonical正式文件與SQL、111／15／22三組既有golden、18例原Python／Java選檔同輸入golden、24檔simulator runtime均同步；FormulaV3 `09b35862b9c48fcb742ed9302b864a234fdbf7b486444a98dd7c1178121f0b64`，V1／V2公式未動。
- 驗證：Java九類實際94 tests、0 failures、0 errors；Python／Ruby文件3測及Ruby既有6測264 assertions通過；authority／formula／四組golden／runtime --check及機械檢查通過。隔離副本helper-only drift確實拒絕；configured SRPP Python可匯入runtime dependency closure。SRPP 77測與完整policy/runtime檢查由主代理獨立驗證通過。
- 獨立審查：spec三輪，最後PASS；其後使用者追加條件與語義修復明確未另作spec複審，由三輪獨立arch覆蓋。arch首輪1 critical／2 major均修；第二輪PASS後修復主代理與實作代理發現的LIVING／INITIAL耦合，第三輪最終PASS、0 findings。實際作者及修正者均為Codex，無假稱Claude修正。
- 能力界線：local selector只驗原文hash一致性，不等於原始資料認證或正式API容量接受，所有交易授權仍false。既有正式producer尚缺完整FX／方向容量與獨立生活子帳，相關模組維持UNAVAILABLE，不能宣稱新規則已被自然時段日報完整金融採用。舊不支援D222的模型／試算入口具名拒絕，不沿用固定17%。
- 正式部署服務：business-services、bff（formula resource）、srpp-simulator（runtime）；未改Swagger、端點、資料表、行情I/O、交易權限或排程，未寄信或下單。

審查與驗收原始收據位於 `/tmp/d222-asset-review/`，最終arch報告為 `codex-arch-review-round3.md`；Git落地／部署後的正式驗收由release協調者另附。


共同合成canonical範例為spec/fixtures/srpp_d222_reserve_document_example.json：它的三個targets只為跨語言投影測試，不能當實際配置。測試將reserveAllocation數字字串還原成模型numeric輸入，同名投影後必須精確回復整份範例UTF-8 bytes。

本輪使用者最後更正：餘額也可以轉VOO／0050／00881，視市場狀況而定，取代先前餘額只轉VOO。正負差皆不得猜比例；有同輪市場證據支持之方案才產生三檔確定target，不需另向使用者逐輪請示。V2閉合reserveAllocation欄位由13變14：刪excess_to_symbol，新增excess_symbols與excess_requires_explicit_allocation。

#### 第三輪後使用者追加的市場選擇條件（未再規格複審，納入獨立實作審查）

生活費與VOO／0050／00881調節，依匯率及每檔自身配息調整52週最高／最低區間位置，先通過既有FX與該方向買賣gate，再於合格者買最低位置／賣最高位置。三檔範圍資料均須同輪可驗；缺任何一檔不排名。同分不猜，selected容量不足只保留殘額，不換次佳不自動分攤。所有既有硬gate保留。V2 reserveAllocation在前14欄外新增market_selector，閉合為15欄；market_selector子欄恰symbols=[VOO,0050,00881]、position_basis=ADJUSTED_52_WEEK_RANGE、buy_selection=LOWEST_POSITION、sell_selection=HIGHEST_POSITION、requires_all_three_verified=true、tie_action=AMBIGUOUS_TIE、no_capacity_reflow=true、fx_policy=execution_policy.usd_asset_fx_preference_d215，數值不新增。

純selector輸入三筆candidate，每筆source={source_ref,sha256,payload:{stock_code,trading_date,price,low52,high52,adjustment_basis:DISTRIBUTION_ADJUSTED_52_WEEK}}，price/low/high正數且low<=price<=high、high>low；gate_receipts含FX及本次BUY或SELL之同形wrapper，payload含stock_code/trading_date/scope/status(VERIFIED_PASS或VERIFIED_BLOCKED)/非空source_evidence(原文wrapper陣列)。source_ref非空，sha256對排序keys／無空白／UTF8非ASCII不escape的JSON原文驗算；日期/代號/方向一致。capacity_twd須有限非負。原文hash一致性不是認證，輸出固定source_consistency_only=true、api_capacity_acceptance=false、transaction_authorized=false；無原文FX/gate不得偽造boolean通過。position=(price-low)/(high-low)，同分容差1e-12；只選合格極值。status限ALLOCATION_REVIEW／PARTIAL_CAPACITY_WAIT／AMBIGUOUS_TIE／UNVERIFIED／NO_ELIGIBLE_CANDIDATE／NO_ADJUSTMENT。選定額取delta絕對值與該檔容量，SELL不超其基準額，BUY台股不超原13%／7.53%普通cap。

Java完整報告可沿現有凍結market extra之daily highPrice/lowPrice、dividends與calendar純算調整區間facts；不新增I/O或對外wire。現有producer未提供D215完整FX偏好與方向capacity receipt時，選擇仍未知，facts/receipts明示UNAVAILABLE。不可把price已verified等同FX偏好通過；本機selector只為方案檢視，不繞過正式API阻擋或將source_consistency_only當正式容量接受。

D222 selector並分INITIAL_ALLOCATION與LIVING_RESERVE_REBALANCE兩模式。INITIAL才比較17%與385萬目標；LIVING須獨立生活子帳living_reserve wrapper，其payload恰提供valuation_date、ledger_scope=LIVING_RESERVE_EXCLUDING_EMERGENCY、非空唯一reconciled_deposit_ids、非負numeric living_term_twd/living_demand_twd/settled_unrestricted_available_twd。hash與原文同前且日期同輪；缺時D222_LIVING_SUBLEDGER_UNAVAILABLE，不以銀行總存款推定。LIVING delta=兩生活子帳現值合計-indexed225萬元；正差買進額另受已交割可動available限制，負差選最高者賣出補生活。這是現況資金流、不改長期target；只有INITIAL完整分配可產target_projection。兩者仍source consistency only、無API容量或交易接受；正式producer目前無獨立生活子帳，須具名揭露。此語義澄清未再spec複審，由獨立arch查證。
