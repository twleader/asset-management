# [t585] SRPP 設定與雙模式模擬及受控正式發布

**對應 Requirements:** Requirement 208（SRPP 退休／配置／買賣風險參數草稿、模擬與版本發布）
**前置任務:** t584
**Liquibase changeset:** 待全worktree確認後使用下一未占用版號，預計 v1.166.0

## 背景
資產配置頁目前是獨立本機模板／AI與逐年固定報酬試算，未接SRPP完整模型。使用者要求在此調整SRPP（包括正式買賣與風險門檻）、保留無AI／有AI兩模式，測試部署並兩專案commit/no-ff merge/push。SRPP正式authority目前由JAR內精確model/manifest及insert-only registry綁定，存DB不能直接生效。

## 要做什麼
- [ ] 585.1 SRPP維護有限typed catalog、current extraction/validation、run-scoped canonical simulation adapter與host release apply CLI；資產端同步精確原始碼／catalog及provenance。類型number/integer/date/weights，值需有限、白名單、bounds、跨欄一致；配置只接受現有標的、非負合計1。涵退休日期、規劃年齡、退休至75／75至80／長照支出、長照年齡、通膨、大筆花費、配置、股票floor/cap、債券回撤／溢價暫停／每日每週額度及KD買賣門檻。每field明示simulationSupported/formalSupported與NOT_MODELED限制，不支援就明示，不默默忽略。
- [ ] 585.2 新增business service/controller/DTO/repositories與冪等DB增量：srpp_settings_draft(UUID id、owner FK、name≤100、revision bigint CAS、base/catalog/config hashes、values_json、changes_json text not null、timestamps)；srpp_settings_run(UUID、owner/draft FK、revision/mode/status、config/input/result hashes、frozen request/result JSON、ai狀態/文字、錯誤碼、timestamps)；srpp_settings_release(UUID、owner/draft/run FK、unique request hash、immutable payload、created time)。歷史JSON為稽核snapshot具名正規化例外，現行來源仍是SRPP bundle。所有by-id查詢都帶owner，背景顯式傳owner不讀request context。同步schema.sql。
- [ ] 585.3 本頁BFF前綴`/api/bff/portfolio-advice/srpp`，business前綴`/api/portfolio-advice/srpp`。GET root回catalog/current/drafts/simulations/releases/canPublish/aiAvailable；POST drafts `{name,baseBundleSha256,values}`；PUT drafts/{id} `{revision,name,values}`，回id/revision/configSha256等；POST drafts/{id}/simulate `{revision,mode:LOCAL|AI,seed,simulations}`；GET simulations/{id}查status/deterministicResult/aiStatus/errorCode；POST drafts/{id}/publish `{revision,simulationId}`（ADMIN雙層）；GET releases/{id}/payload下載不可變請求。未知值400/422、stale409、非owner404、無身份401、非ADMIN403，有界queue滿429。seed非負32位、paths100..100000預設1000，timeout≤180秒，重啟中斷不可永久PROCESSING。
- [ ] 585.4 request執行緒以LatestAssetsService.getLatestForOwner凍結本owner snapshot+liveAssets/capturedAt，worker只吃frozen JSON。純Python服務只限Compose內網無host port，以版本化SRPP_SCENARIO_CASHFLOW_V1計算，重用SRPP既有分齡spending／現金流／報酬等純函式，明示未包含legacy完整逐檔過渡、完整稅務與日頻市場閘，不冒稱formal monthly_engine結果，隔離每run，禁止formal_run.py、vendor/AI/broker I/O與canonical output寫入。baseline/scenario同input/seed，hash雙端一致、拒絕NaN/Infinity/duplicate keys。缺必要資料FAILED/UNAVAILABLE；不以舊檔或其他owner填補。結果顯示成功率／分位路徑／資金缺口與實質幣值基準；日頻規則退休模型不涵蓋時NOT_MODELED，另有規則差異預覽。
- [ ] 585.5 LOCAL不建client/查key/發AI；AI同一deterministic result，僅用既有Anthropic設定/SDK產文字，缺key或失敗保留數值且aiStatus明示，禁止AI改規則/計算/發布。六項risk類別／上限與交易禁止協定唯讀；總分審慎／防禦門檻以新增V2 model-driven rubric採用，V1原樣保留，backend capture/evaluator及SRPP verifier使用相同V2 digest，新公式不透過JSON/code執行。
- [ ] 585.6 前端在AssetAllocationAdviceView提供SRPP中心與原功能入口，載入current版本，分組typed editor，草稿命名/保存/載入、dirty與revision、防stale run、LOCAL/AI選擇、paths/seed、模擬進度、基準/情境結果與限制/差異、ADMIN發布與payload下載、版本歷史。Vue不計算金融值或重建正式差異；API走本頁BFF。儲存／模擬／發布獨立，PENDING_RELEASE明示尚未正式採用。
- [ ] 585.7 publish須當下成功run及同revision/config/base/catalog，request payload封閉version1 `{schemaVersion,requestSha256,baseBundleSha256,modelInputsSha256,catalogSha256,configSha256,values,simulation:{id,resultSha256}}`，hash排除自身欄，以共同canonical JSON計算；重複同payload冪等。不支援formal欄位改動拒絕。host CLI只收此payload與核對過managed feature roots，產白名單source/aliases變更，不執行任意shell、不給business Docker/Git權限。正式發布沿原規則驗證、runtime/manifest重建、authority/golden/insert-only registry、雙repo no-ff/push、部署驗收。任何閘門沒過保留待發布。
- [ ] 585.8 ACTIVE由classpath `srpp/settings/release-receipt.json`與已存request/config、目前DecisionPolicyAuthority bundle/model hash、從目前authority抽取的全部values與原payload.values canonical相同（原configHash仍以request baseline bundle/catalogVersion重驗，不用新bundle重算）、registry exact row、SrppReportAuthority.matches全吻合推導，無client ACTIVE ACK。歷史後續被取代顯示SUPERSEDED。此輪保留既有正式投資數值，以現值草稿／隔離fixture驗收，不改排程/報告/寄信/券商，不新增9090路由；因riskMode說明改為依版本化門檻，更新Swagger並走renderer/sync/authority完整發布鏈。
- [ ] 585.9 測試覆蓋own/other-owner、ADMIN雙層、CAS、unknown/type/range/cross-field、invalid hashes、舊run或base drift拒發、LOCAL無key零AI、AI failure不改數字、queue/timeout/重啟、receipt偽造/registry不匹配不ACTIVE、canonical模型同input與formal compiler aliases、前端build。獨立spec與diff-scoped arch審查。兩專案feature commit/no-ff merge/push精確remote回讀，必要服務run-stack image rebuild+recreate，操作新API/畫面、schema.sql同步。正式SRPP報告採用受時窗限制，未執行明記NOT_YET_EXECUTED。

## 驗證
```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true -Dtest='SrppSettings*Test' test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml -Dtest='SrppSettings*Test' test
cd frontend && npm run build
```
SRPP新adapter/CLI測試以其自足測試指令執行；從正確main正式設定重建business-services/bff/frontend及新內部模擬服務。需要的registry SQL僅於雙repo落地後insert-only執行。部署後實際載入catalog、儲存現值草稿、執行LOCAL、確認AI模式真成功或具名未配置、建立待發布請求並驗receipt；不得為驗收產生券商動作。schema更新後重產db/schema.sql並執行`bash scripts/tests/schema-sql-drift-test.sh`。

## 完成報告
待實作、測試、部署與Git落地完成後填實證，不預宣稱完成。

風險V2補充：既有 immutable event-evidence replay維持原內容；建立新capture時，只接受精確目前DecisionPolicyAuthority bundle（且registry驗證通過），其餘409 POLICY_UNSUPPORTED，不把歷史bundle套目前V2或猜V1。這是因歷史registry document僅含targets、不含可驗的完整rubric。測試用V1入口維持歷史語意，production明確注入current V2並對回應rubricSha256驗真。

Settings 身分編碼補充（獨立審查 round1 修正）：既有 SrppJcs 只支援安全整數，原樣保留不修改；本中心新增 SettingsCanonicalJson，使用完整 RFC8785 實作 `io.github.erdtman:java-json-canonicalization:1.1`，Python固定rfc8785==0.1.4。嚴格parser拒duplicate key、trailing token、NaN/Infinity及非法Unicode。values/input/result/request以同一新canonicalizer計hash；測試含0.0225、18鍵權重、小數金額、1e-7／1e20、中文與UTF16 keys跨語言golden。configHash=H({baseBundleSha256,catalogVersion,values:全normalized值})；catalogSha256=assumptions/settings_catalog.json原始bytes hash（metadata不含default/currentValues），worker額外提供currentValues/defaultValue。發布後不能對newBundle重算original configHash；驗原request baseline身分及configHash，再比較目前authority抽取完整values與payload.values canonical相等，另驗publishedBundle/model/registry/JAR。測試包含values不變但bundle改變、非預設門檻發布、current values與receipt矛盾。
Draft存changes_json為當初baseline差異稽核snapshot（fieldId/label/before/after/formalSupported/retirementImpact）；server DTO提供changes、publishEligible、blockedReasons:[{code,message}]，前端只render，不自己推導formal差異。更新draft重驗重產changes；舊baseline仍可讀，base漂移不得新模擬／發布，需明確建立新baseline草稿。run.deterministicResult保存完整worker response：{schemaVersion,baseBundleSha256,configSha256,inputSha256,engineVersion,result:{baseline,scenario,differences,changes,warnings}}，baseline/scenario各含successProbability/95%區間/percentilePaths/retirementAssets/terminalAssets/depletionAge/modeledFields/notModeledFields/assumptions，differences由Python或Java算不由Vue算。V2 model marker固定 execution_policy.risk_score_decision_policy.rubric_schema=SRPP_RISK_RUBRIC_V2，V1四camelCase決策keys及category順序沿用，除schema不同及總分門檻可變，其餘3/4固定。

波動假設補充：catalog新增equityVolatility/bondVolatility兩個simulation-only fraction欄位，bounds 0..1，預設0.18/0.08為明示的規劃情境假設，非本日觀測或完整正式模型校準。共27fields（16 simulation-only、11 formal）。期望報酬以同版model可驗來源揭露；成功率/95%區間僅表示給定假設下的存續比例與Monte Carlo抽樣不確定性，不表示假設本身可靠度。UI可調波動並完整顯示限制，不使用舊個人artifact假裝新資料。

## 明確報酬與購買力規約

本引擎是簡化情境模型，不等同完整正式模型校準。
股票實質複合目標 `g = global_equity_wrapper_assumptions.core_real_compound_return_before_wrapper_overlay - target_weighted_equity_wrapper_drag`，不得用其他過期重複欄位。
股票算術規劃均值 `a_e = exp(log1p(g) + 0.5 * equityVolatility^2) - 1`；抽取原 `_arithmetic_for_geometric_target` 為 return_model 共用純 helper，monte_carlo wrapper 保留原 API 與輸出。
此 lognormal 換算只是近似波動拖累補償，不宣稱 Student-t generator 實際實現 g；波動假設變更要重算 a_e，輸出原始 g、a_e、公式／來源與這項限制。

唯一 reference inflation `refInfl = global_equity_wrapper_assumptions.forward_return_assumptions.inflation_assumption.midpoint`。
債券名目規劃收益 `y = bond_forward_assumptions.weighted_nominal_yield_net_expense_before_fx`，轉基準實質算術均值 `a_b_ref=(1+y)/(1+refInfl)-1`。
現金基準實質均值只取 `asset_assumptions.cash.real_return_at_2_25_percent_inflation`；不另造存款收益來源。
每期算術漂移 `(1+a)^fraction-1` 加相關 Student-t(df=7) 的零均值、變異數正規化 shocks，最低單期報酬 -0.95；原 draw_period_returns/covariance_factor 抽至 return_model 純 helper，monte_carlo wrapper 傳原 df 以保留歷史輸出。
相關矩陣取原正式四因子架構；現金年波動 0.005 沿原模型固定假設，FX 波動取同版 model asset_assumptions.usd_twd.volatility 並揭露其既有來源日期，FX 長期漂移 0。股票／債券波動只取明示情境欄位。

沿 return_model.security_returns 的原規約：股票實質因子先加目標配置的已知美元曝險，不再重複扣 CPI；債券／現金先將基準實質報酬乘 `(1+refInfl)^fraction` 還原名目，再除當期實際情境 CPI multiplier，FX 對債券只加一次。
情境 CPI 使用 InflationPath，每曆年單一 uniform 抽樣：正式 minimum/maximum 同減 formal midpoint，再加情境 inflationRate，lower 至少 0，upper 至少 lower；不得把 reference inflation 與情境預測 CPI 混為一個基準。
期初即時名目資產除 `reference_price_level(asOfDate,refInfl)` 轉 2026-08-31 實質元，此期初換算兩情境相同，不因修改未來通膨重寫過去物價。其後 price level 逐期依情境 CPI 複利；名目薪資／年金／勞退入帳時只除該期 price level 一次；政策標為 2026-08 實質的生活費／大額支出不重複膨脹。
創新值沿 CalendarMarketPath 依 seed/曆年/月份固定，每月同一 draw 以 log interpolation 分配月內事件，修改退休或入帳日期不重抽整條市場路徑。
固定 innovation golden 應核對漂移、t 正規化、CPI/FX 各一次；zero-volatility 測試核對同一路徑逐期精確現金流，並證明波動改變重算 a_e。

## 正式部署驗收修正（2026-11-14）

首次正式 HTTP 驗收發現：未帶使用者身分的中心請求被全域 `Exception.class` handler 攔成 500，未保留原訂 401。原因是本中心的具名例外 advice 未宣告優先序；補上 `Ordered.HIGHEST_PRECEDENCE`，僅優先處理本 controller 的 `SrppSettingsProblem`，其他未預期錯誤仍沿全域 500 處理，未更動既有 API 契約、正式規則、Swagger、authority 或 bundle 身分。

新增 `SrppSettingsAdviceWebMvcTest`，以真正 MVC application context 同時註冊本中心與全域 advice，驗證 401／403／409 的狀態、code、message，以及未預期例外仍為 500。修正前四項測試有三項失敗（原訂 401／403／409 皆得到 500），修正後四項全過；完整 settings 範圍 28 項測試全過、零失敗／錯誤／跳過。

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true -Dtest=SrppSettingsAdviceWebMvcTest test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true '-Dtest=SrppSettings*Test' test
```

修正前後紀錄：`/tmp/srpp-settings-live-advice-before.log`、`/tmp/srpp-settings-live-advice-after.log`。本修正仍須完成獨立架構複查、Git 落地與正式映像重建後，再重新執行實際 HTTP 驗收；單元／MVC 測試不能代替正式部署採用證據。
