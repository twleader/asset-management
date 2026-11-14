# [t588] 高股息核心補足兩日止穩與固定wave分批

**對應 Requirements:** Requirement 209（高股息核心補足兩個完成交易日止穩、分批預算與防重）
**前置任務:** t584、t585、t587
**Liquibase changeset:** 無

## 背景

使用者要求把0056、00713、00878的新補足方案正式發布，初步止穩採創低次日起連續兩個已完成台股交易日不破本輪新低，平低接受，不要求5日線。單靠距52週高點回撤不能判斷止穩；原每日小額補足可能在同一跌勢跨週持續買入。既有生產topup-state／國泰帳戶及pending等canonical來源未齊全，新規則不製造這些證據。

## 要做什麼

- [ ] 588.1 新規則只管0056、00713、00878自主核心補足，00919與券商定期定額／短線不變。沿用D198配息調整後20完成日價位、D189非追高與原風險／資金／配置／來源硬閘；不採回撤5/10/15/20%直接買進。
- [ ] 588.2 新增pure SrppDividendStabilization.evaluate(fact,state,config)，無IO/交易/DB寫入。neutral fact為stock_code、market=台股、trading_date、completed_session、completed_sessions與rows[{date,low,close}]一一對齊，cash_events[{date,amount}]、current_price/current_low與radar{monthly_confirmation,k,d}。state-independent市場observation取D198同一前20完成TW sessions，從窗口首日重播running low，不建立wave/stage；輸出d218ObservedNoNewLowSessions。缺state仍保留此市場fact，wave-bound stabilized/anchor/budget等未知，0上限。來源為既有canonical市場body的trusted daily、completedSessions、dividends與精確radar；保留raw split-adjusted low/close與anchor_low_raw，以同輪cash_events扣每筆day後至trading_date已除息金額，同一價基、anchor日raw low必須等於immutable anchor_low_raw；缺完整配息來源不可用空清單假PASS。兩個相鄰完成session最低價不破running low即止穩，創低日不計、平低算、再破重算；當輪盤中資料只能擋破底、不能計天數。缺日/漏列/亂序/重複/缺low/close/非有限正價/價基未知均UNKNOWN、0額。FIRST成交／pending須含該首批實際來源的observation_anchor_date、observation_anchor_low_raw、evidence_ref（不可變來源引用），不能以新running low取代已固定保護線。
- [ ] 588.3 state從既有topup-state.stabilization逐檔正式owner/date/policy-bound canonical來源提供：wave_id、anchor_date、anchor_low_raw、budget_twd、price_basis=TW_SPLIT_STOCK_PRICE、coverage_start_date、coverage_end_date、history_complete、frozen、wave_history_complete=true、approval_evidence_ref（所有wave必填的不可變核准來源）、prior_wave_closed_evidence（有證據的首wave為null，後wave需不可變結束來源）及confirmed_fills/pending_orders。每筆{id,wave_id,date,amount_twd,kind,stage}，amount含fee、kind=STABILIZATION/DCA/CORE_TOPUP、僅STABILIZATION有FIRST/RETEST/FOLLOW。完整history從anchor至本輪，負數、未來日、跨wave、重複ID拒絕；缺state不能空帳，固定wave不得由rolling window／週界／consumer／hash變更重生。新wave需明示前wave結束與核准證據，本calculator不建立wave。
- [ ] 588.4 FIRST最多固定補足預算10%，同wave一批；真FIRST成交日後先有完成日low高於固定anchor，再有更後完成日low較前完成日低，從first日後全程守anchor且最新確認日嚴格晚於回測日、最新close>前close才能RETEST額外20%（累計30%）。任一active pending先阻擋新增；部分成交即占用FIRST/RETEST，未用批額不可週期重提。真RETEST後FOLLOW需兩日止穩仍成立及精確radar monthlyConfirmation ABOVE且K>D，可用至累計100%，沿原日3／週5張；無MA5/3%反彈要求。尚無FIRST真成交或active pending時新低只更新同wave觀察低點並重算兩日，不改waveId/預算；FIRST真成交/掛單後，沿該FIRST原始來源錨點固定保護線，完成low/current low/current price跌破凍結後批，不自動新wave。DCA／一般核心買進及pending扣同wave總預算。
- [ ] 588.5 正式DIVIDEND_TOPUP實際調用gate，先保存技術facts再評state；output含decision/status/reason/stabilized/completed_no_new_low_sessions/anchor_low/latest_completed_date/stage/stock_code/wave_id/anchor_date/used_twd/cumulative_fraction/budget_ceiling_twd且trade_authorization=false/places_orders=false。sizing逐張含fee比較新上限與原target/group/equity/funding/depth/quota取最小。生產缺國泰與pending來源仍具名BLOCKED，不冒稱自然正數採用、不用本機解封有效API BLOCKED。
- [ ] 588.6 新增純函式邊界與正式module整合測試，覆蓋平低、創低重算、假日跨日、漏日／盤中／來源缺失、除息、破anchor、首批部分成交、pending、跨週防重、回測缺失／成立、FOLLOW未確認／成立、費用預算邊界；兩端同neutral fixtures；新增市場完整但state缺失時仍有20完成日observation且無wave/預算的正式module fixture，以及回測日即使收高仍等待、後續完成日才可確認的時序fixture。既有00919及歷史無D218 policy維持原行為。
- [ ] 588.7 FIRST/RETEST本身不要求MA5、MA20或KD轉強；保留原非反向雷達。現有WAIT無typedcause能證明只等技術面時，具名RADAR_WAIT_CAUSE_NOT_VERIFIABLE等待，不靠reasons/risks自然語言分類，不宣稱完成技術WAIT豁免。
- [ ] 588.8 新增ASSET_MGMT_SRPP_V3/SRPP_FORMULA_MANIFEST_V3與可重播generator，記D218 scope及Java pure rule/module/sizing與SRPP pure腳本原bytes的SHA-256；保留V1/V2常數/fixture完整bytes及歷史registry接受。current registration改用V3、checker/golden同步，V3沿V2 nullabledeposit語意、daily-context scheduler可用V3但不改時間/IO/副作用。新V3測試含舊版本fixture/JCS仍相同與registry可讀、V3 null rate分支、scheduler支援，舊row不可UPDATE/DELETE。
- [ ] 588.9 同步SRPP D218/v2.66.0原始model/manifest與現有generators產authority/golden/policy document/insert-only SQL，無DB/API/route/排程/通知變更。必要擴充SrppReportFactDividendTopup的closed name enum為d218ObservedNoNewLowSessions(COUNT)、d218Stabilized(BOOLEAN)、d218AnchorLow(TWD)、d218UsedBudget(TWD)、d218CumulativeFraction(RATIO)、d218BudgetCeiling(TWD)，shape仍原封閉fact；同步YAML、SRPP consumer schema與三份Swagger，受控renderer→identity sync→各自check鏈，記最後bundle。測試與正式spec/arch審查通過後由root統籌兩專案feature commit、main no-ff merge/push/remote readback，然後registry、乾淨main原.env build/recreate business-services、bff、srpp-simulator、JAR bytes及精確hash業務API回讀。正式日報合法時窗尚未執行記NOT_YET_EXECUTED，無broker write或寄信。

- [ ] 588.10 BFF報告validator同步6個closed fact name與typedunit/範圍；new D218 model三檔正數leg的closed gate set增加D218_STABILIZATION，PASS收據的唯一同symbol/TWD/EXACT正budget與原完整topup refs必須綁定，leg.amount+fees不得超上限；舊model無D218仍原gate集合。BFF daily-context/orchestrated對V3version/digest/manifest精確驗證，由同一generator同步BFFresource，V1/V2原wire不變。用既有受控export同步srpp-simulator新baseBundle/runtime/catalog/provenance，不改模型公式或新增設定。正式部署三個實際變更服務business-services、bff、srpp-simulator。

## 驗證

```bash
bash scripts/spec-check.sh main
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true -Dtest='SrppDividendStabilizationTest,SrppReportStateTopupCalculatorTest,SrppReportModuleCalculatorTest,SrppFormulaCatalogTest,SrppPolicyRegistryServiceTest,SrppModuleCalculatorV2Test,SrppModuleCalculatorV3Test,SrppDailyContextProducerSchedulerTest,D218FormulaAuthorityTest' test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml -Dtest='SrppReportResponseValidatorTest,SrppDailyContextResponseValidatorTest,SrppOrchestratedResponseValidatorTest' test
python3 scripts/generate-srpp-formula-v3.py --srpp-root /Users/steven/.codex/worktrees/dividend-two-session/SRPP --check
python3 scripts/check-srpp-report-authority.py --srpp-root /Users/steven/.codex/worktrees/dividend-two-session/SRPP --check
ruby scripts/render-9090-openapi-docs.rb --check --asset-only
```

雙repo已落地與registry驗證後，從乾淨正式main原.env執行docker compose -p asset-management build business-services bff srpp-simulator及docker compose -p asset-management up -d --no-deps --force-recreate business-services bff srpp-simulator。比對新image/container、healthy、JAR原始authority全文與精確bundle業務API；無關服務不動。

## 完成報告

已完成pure D218、正式DIVIDEND_TOPUP接入、含費stage上限、獨立市場observation與必要BFF closed契約/V3相容。離線驗證：74個不重複backend測試通過（最初V3測試fixture序列化2項錯誤已修正並重跑通過），BFF三類44項通過，Java/Python共享neutral golden 26個case逐欄完全一致。新D218測試涵market來源完整/state缺失仍有觀察事實、FIRST無MA5、RETEST與確認分日、partial FIRST後RETEST只額外20%、除息、pending/跨週防重、破低及含費整張精確邊界。

測試證據：/tmp/d218-java-tests.log、/tmp/d218-fee-boundary-tests.log、/tmp/d218-bff-final-tests.log及當輪Surefire reports；原錯誤log保留，不把初輪失敗改稱成功。完整來源freeze後仍須重產authority與受控Swagger、V3generator check及最終同版回歸。正式Git/registry/三服務部署與精確API回讀尚待root依序完成，其最終收據獨立保存；不提前勾選發布完成。生產canonical wave/國泰account/pending等來源未齊仍BLOCKED，fixture正數不代表自然日報已採用。
