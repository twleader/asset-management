# [t587] SRPP短線策略草稿與目前進出場觀察

**對應 Requirements:** Requirement 208（SRPP設定草稿、短線觀察與情境來源隔離）
**前置任務:** t586
**Liquibase changeset:** 無

## 背景

使用者確認需要短線策略設定與當前適合觀察的標的／買進賣出時機，原SRPP模擬中心尚無此入口。月頻退休情境不能產生當日進出場訊號；既有同owner交易雷達已具SHORT最終方向與理由，須保留其證據及限制。Task586新增大型V2結果也需要明確歷史摘要傳輸界線。

## 要做什麼

使用者明確確認同時需要可調短線策略與依最新雷達顯示標的／買賣時機。本增量擴充尚未發布的catalog V2至76欄，退休情境引擎仍V2。新增16欄均editable=true、simulationSupported=false、formalSupported=false、retirementImpact=NOT_MODELED；設定會保存於既有owner草稿，但不計入月頻退休回報、不修改正式雷達，也不因保存草稿成為正式採用。畫面必須在設定與目前清單旁直接揭露「草稿參數尚未套用正式雷達；短線報酬未納入退休試算」。本輪不承諾尚無獨立證據的短線成功率／價格／股數。

六個台股股票欄以 `/execution_policy/short_term_opportunity_d171` 為prefix；六個台股債券欄以 `/execution_policy/bond_short_term_opportunity_d185` 為prefix：

| 股票id／債券id | type / bounds | prefix後pointer |
|---|---|---|
| shortEquitySymbols／shortBondSymbols | symbols，來源白名單subset，0..來源數量 | /symbols |
| shortEquityReboundPct／shortBondReboundPct | number，0..100百分點，至多4小數 | /minimum_rebound_pct |
| shortEquityNetRewardRisk／shortBondNetRewardRisk | number，0.1..100，至多4小數 | /minimum_net_reward_risk |
| shortEquitySleeveCap／shortBondSleeveCap | number，fraction 0..1 | /limits/sleeve |
| shortEquityPositionCap／shortBondPositionCap | number，fraction 0..1 | /limits/per_position |
| shortEquityMaxHoldingSessions／shortBondMaxHoldingSessions | integer，1..260交易日 | /limits/max_holding_sessions |

symbols只接受不重複的原始非空string，均屬該field以同版model原symbols設定的allowedSymbols；保持使用者順序、空陣列合法，不允許自行加新股票或市場。兩組positionCap各不高於sleeveCap。default source維持原陣列與原值（股票7檔、債券3檔），不是跨帳號持股清單。四個美股欄prefix `/execution_policy/us_megacap_tactical_policy_d133`：usTacticalNetUpsidePct number 0..100百分點、至多4小數，pointer `/minimum_profit_space_d185/minimum_pct`；usTacticalBatchCap／usTacticalCompanyCap／usTacticalSleeveCap fraction 0..1，pointer各為 `/order_check_d186/batch_cap`、`/order_check_d186/company_cap`、`/order_check_d186/tactical_total_cap`，batch<=company<=sleeve。全部source=model，未知key/type/nonfinite/bool與其他既有閉合檢查不變。既有模型與payroll JSON bytes不變；由原export與manifest/authority發布工具同步。新增symbols renderer用catalog白名單，原60欄契約不變。

新增owner登入後純讀business GET `/api/portfolio-advice/srpp/short-opportunities` 與 `/api/portfolio-advice/srpp/short-opportunities/stock?stockCode=&market=`；本頁BFF exact同suffix在 `/api/bff/portfolio-advice/srpp`。Controller僅委派專用service。service在任何雷達查詢前確認CurrentUserContext有owner；BFF沿既有tenant傳遞。不新增DB／9090／Swagger／外部行情或refresh端點。只重用TradingRadarService.getList()與既有純讀getStockEvaluation().stock()相同owner純讀來源，禁止get()/export快照、refresh、排程、producer、worker、AI、券商或來源寫入。按刷新鈕只重讀GET。

清單可選成對draftId(UUID)+revision正整數；未帶沿正式值，只有一個400；帶入須owner一致、revision一致、base/model/catalog hash與version皆當下相符，異owner404、stale409，不fallback。草稿只有symbols subset用於縮小觀察集合，其他數值只顯示正式與草稿差異，固定thresholdsAppliedToRadar=false、configurationStatus=FORMAL或DRAFT_PARAMETER_PREVIEW_ONLY，不能假稱已按自訂RR/反彈/容量重新計算。保留原shortAction作「雷達短線觀察」，不等於D173/D185/US的ledger、資金、對帳、深度、基本面及全部SRPP採用閘門通過。原FormalSupported=false更動仍擋正式發布，不新增旁路。

台股股票與債券以來源symbols分組；不在目前owner雷達宇宙的configured代號回missingSymbols（只標等待資料，不臆造name/price/action）。美股primary集合只取本輪owner雷達美股列，不擅加固定科技名單或推測fallback資格。清單依shortAction區分買進觀察／減碼退出觀察／等待：BUY_CANDIDATE、ADD_CANDIDATE、TRIAL_BUY屬買進觀察，REDUCE_CANDIDATE、EXIT_CANDIDATE屬減碼退出觀察，其餘等待；未知code、缺score、market stale/dataComplete不全、缺必要來源時只能等待資料，不能以高score推導買賣。排序按組／觀察分類／source shortScore降冪／market+symbol穩定tie break，null最後。shortAction與label仍原樣保留並標狀態；不從中長線action/reasons生成SHORT方向。

新response以immutable typed records回傳，lists List.copyOf、null money保留null、decimal price用字串toPlainString保原精度。ListEnvelope至少含configurationStatus、thresholdsAppliedToRadar(false)、policyBundleSha256、modelInputsSha256、ruleVersion、actionPolicyVersion、generatedAt、markets、policies、rows、missingSymbols、limitations。markets各含market、stale、dataComplete、asOfDate、quoteStatus；policies三組含group(TW_EQUITY/TW_BOND/US)、label、formalValues、draftValues（typed SixPolicy或UsPolicy）、entryRule、exitRule、retirementImpact(NOT_MODELED)。formal/draft值只投影上述16欄；rules從同版model rule/exit_rule等文字採既有source，不自行發明新時機。rows各exact `{stockCode,stockName,market,group,held,shortAction,shortActionLabel,shortScore,observationType,status,price,quoteStatus,priceUpdatedAt,dailyCandleAsOfDate,horizonConflict}`，observationType BUY_WATCH/SELL_WATCH/WAIT，status RADAR_OBSERVATION/WAITING_DATA；price是原source字串不捏造限價，全部actionable/tradeAuthorization概念固定false（可用頂層tradeAuthorization:false表達）。

stock detail須是目前owner雷達宇宙且在相同source政策/US集合，否則404；不必先查全部詳細內容，只針對當次展開symbol/market一次getStockEvaluation，禁止逐列N+1。DetailEnvelope含ruleVersion/actionPolicyVersion/generatedAt、相同身份與status、dataComplete、source shortAction/shortActionLabel/shortScore、shortReasons/shortRisks、shortEvidenceConfidence/shortDownsideRisk/shortRiskCoverage、horizonConflict、原quote價格/狀態/時間、dailyCandle.asOfDate、原priceReference exact `{buyLower,buyUpper,sellLower,sellUpper,asOfDate,applicableSide}`（money皆保原decimal string/null）及limitations、tradeAuthorization:false。missing/null不補0；舊資料無新detail欄仍等待。不把general actionGateReasons或medium reasons當SHORT利多；若保留audit需分開明示。PriceReference只能標「完成日布林參考區間」及其自身asOfDate/applicableSide，不標可成交限價、停損或報酬目標。沒有SHORT賣出觀察時不從持股或shortScore低自行造SELL。

UI新增獨立child短線區，可調策略欄仍沿原草稿保存／dirty／CAS，symbols用source白名單複選；目前買進/賣出/等待清單顯示來源與時點，展開後顯示SHORT原因風險、參考區間及原策略進出場文字。參數預覽與正式方向清楚分開，預設不在背景重複poll/外呼；舊run不因缺short設定毀損，不把NOT_MODELED改成退休效益。對讀取失敗具名保留錯誤，不用前一owner或前一symbol代替。

純讀來源已查證：不可呼叫舊getStockDetail，該路會走可刷新calendar、DividendEventEvidencePipeline.resolve/projectOne(DB writer)、technical resolver寫cache。使用既有TradingRadarService.getStockEvaluation(String,String).stock()，其ownerTargets先驗owner與自有holding/watchlist，buildPanelMarket/compactInputs使用cached-only日曆、非null batch Entry、Dividend.resolveBatch projection-free及technical.resolveReadOnly。共用同決策核心與完整StockDecision，不複製assembler、不改舊detail行為。既有TradingRadarPanelServiceTest的oneEvaluationRunsCoreOnceAndAllCompactScalarsMatchTheSameFullDecision及verifyNoExternalOrWrites提供底層證據；本輪再驗新service只委派getList/getStockEvaluation，never getStockDetail/get()/refresh/export，冷／過期／跨年度快取缺失維持等待、不發holiday proxy/vendor/DB writer/cache writer。

### V2歷史摘要有界傳輸

新V2的單筆結果可有數百月audit與薪資明細；root center仍最多20筆simulations，但每筆只回metadata，deterministicResult/presentation為null，不於root帶20份完整結果。原DB result_json/resultSha256及GET simulations/{id}完整內容不變；center summary不重算／改result identity。frontend載入/選取run須GET單筆完整結果，含request序號/owner/draft切換防遲到回應，PROCESSING polling仍沿現有流程。root DTO與BFF不可把summary null補成空模型。這是本輪大結果必要的傳輸界線，避免root超BFF16MB；不以刪除worker audit或捨棄歷史修容量。

驗證需涵蓋16欄defaults精確來源與symbols/跨欄/invalid/type/readonly設定、全差異NOT_MODELED不改月頻數字；owner/other-owner/stale/missing集合與資料時點；列表只一次getList/detail懶載一次，無vendor/writer/refresh/export；原SHORT方向/理由未被medium/candidate覆蓋，stale/unknown等待、草稿參數不解除BLOCKED；typed nullable價格精度、布林label非訂單；root20筆metadata無完整結果且單筆GET仍原hash、舊V1相容；node UI/前端build、實際草稿保存與雷達清單/展開操作。所有金額延續逗點顯示。最終與Task586一次整體arch review／雙repoGit／registry／部署，來源新bundle仍必精確回讀，無寄信或交易。

- [ ] 587.1 完成上述16欄／76欄catalog、閉合symbols與數值、跨欄驗證及NOT_MODELED草稿契約，原模型/payroll bytes不變。
- [ ] 587.2 完成同owner純讀service、exact business/BFF兩GET與typed清單／逐檔wire，不引入外部或交易副作用。
- [ ] 587.3 完成短線設定及清單/懶載入畫面、明示正式與草稿未採用界線、完整資料與動作分軌，不偽造資格。
- [ ] 587.4 完成V2 root metadata-only／單筆原結果GET、舊V1兼容與前端遲到回應防護。
- [ ] 587.5 完成上述Python/Java/BFF/node/UI驗證、獨立spec/arch審查，與t586一次同步來源hash／authority／registry及雙repoGit與部署，正式日報採用照時間閘。

## 驗證

於SRPP feature worktree執行：

```bash
PYTHONDONTWRITEBYTECODE=1 /tmp/srpp-settings-python/bin/python scripts/test_settings_simulation.py
PYTHONDONTWRITEBYTECODE=1 /tmp/srpp-settings-python/bin/python scripts/policy_bundle.py check --purpose daily --consumer Codex
PYTHONDONTWRITEBYTECODE=1 /tmp/srpp-settings-python/bin/python scripts/export_settings_runtime.py --destination /Users/steven/.codex/worktrees/srpp-life-cashflow-fields/asset-management/srpp-simulator/runtime --check
```

於asset feature worktree執行（SrppShortTerm測試為本任務待新增，不宣稱現有）：

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true '-Dtest=SrppSettings*Test,SrppShortTerm*Test' test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml '-Dtest=SrppSettings*Test,SrppShortTerm*Test' test
npm --prefix frontend run build
```

部署沿正確main設定重建/recreate受影響worker/business/bff/frontend，操作策略草稿保存回讀、SHORT清單/逐檔GET、缺資料/跨owner/舊draft拒絕、root有界與單筆完整結果。所有驗收不得下單、寄信、手動正式報告或refresh；正式日報尚未合法自然執行則記NOT_YET_EXECUTED。

## 完成報告

2026-11-14：實作、獨立spec／arch審查及限縮範圍發布驗收完成；catalog V2共76欄。asset已部署main `b2e057067a19ad551325d6f194a4a0bfb545e22c`、SRPP main `b9eccf2e8790c0dd7af9f15ee5f777fce6424ff0`，bundle `fc31e0979d7279cc3f1237e9ea91500d8a48958ed67f9b1d66f9e420ff996bda`；測試、精確Git／registry、部署及限制見[發布收據](../../docs/reviews/t586_t587_srpp_settings/release-20261114/README.md)。已部署worker HTTP／內部草稿保存回讀／實際BFF JAR投影／HTTPS畫面通過；backend LOCAL simulate為 `NOT_EXECUTED_ZERO_VENDOR_GUARD`，Google OIDC已登入BFF HTTP E2E未執行（畫面身份合成、需重新登入）。短線草稿未套正式雷達且NOT_MODELED；正式報告GET409 NON_TRADING_DAY，`businessApiAccepted=false`、正式日報採用 `NOT_YET_EXECUTED`，無正式publish／寄信／交易／手動報告。依CLAUDE.md「SKIP_SPEC_GATE=1（例如只是回填任務完成報告）」例外僅補完成metadata，不更動上文契約或原checklist；無新語義審查週期，兩項arch minor保留理由見驗收README。
