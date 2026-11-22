VERDICT: CHANGES_REQUESTED

## 判定
critical: 0   major: 4   minor: 6

## Findings
### [Major] t626 決定 7（00865B 緊急腿補價候選）在正式報告路徑上不可達：`SUBACCOUNT_UNVERIFIED` 永遠與報價缺口並存
位置：spec/tasks/t626_srpp_order_plan_v2_recompute.md:35、:75、:158-:165（626.20）、:192（情境 22）；spec/requirements.md Requirement 223「國泰緊急 00865B 腿在報價缺口為唯一原因時改輸出具體報價缺口碼」；spec/design.md Requirement 223 同句
證據：
- `backend/src/main/java/com/steven/assets/service/srpp/decision/DecisionInputCaptureAdapter.java:80` 硬編 `Evidence subaccounts = new Evidence("UNAVAILABLE","SUBACCOUNT_UNVERIFIED")`；`:113` 00865B 的 pending 恆為 `SUBACCOUNT_UNVERIFIED`；`:114` 00865B 的 POSITION 就是 `subaccounts`。報告路徑的 `SrppReportFubonOverlay.java:21` 又把 00865B 的 pending 強制覆寫為 `SUBACCOUNT_UNVERIFIED`；`SrppReportFubonCapturePort.java:15` 的 `subaccounts()` 同樣恆 UNAVAILABLE。`grep -ran subaccounts backend/src/main` 找不到任何會產生 PASS 的路徑。
- `DailyDecisionCalculator.java:24-26` 對每個 symbol 加 POSITION／PENDING 收據、對 00865B 另加 SUBACCOUNTS 收據，三者 block=true，UNAVAILABLE 的原因都進入原因集合；`:66` 緊急段重用 00865B 的全部收據（只排除 TARGET、D-130_CAPACITY、D-195_SELECTION）。結果：00865B 的 STRATEGIC_FUBON 與 EMERGENCY_CATHAY 兩腿的 blockingReasons 恆含 `SUBACCOUNT_UNVERIFIED`，不可能是 QUOTE_REFRESH_GAPS 的子集。
- t626:75 與 626.20 的前提「改 Java 後，報價缺口是唯一原因時此腿才會只帶報價缺口碼」在現行來源下永遠不成立；單元測試可以合成 verified subaccounts，正式 package 不行。目前實際可成為候選的債券腿只有 00719B／00697B 的 STRATEGIC_FUBON。
修法：在 626.5、626.20 與兩份 Requirement／design 增列前提：「正式包在 subaccounts 來源（`DecisionInputCaptureAdapter.java:80`）被驗證前，00865B 兩腿恆含 `SUBACCOUNT_UNVERIFIED`，不是補價候選」；情境 22 與 626.20 測試明寫以合成 verified subaccounts／pending 的 `Input` 建構，並在完成報告註明正式資料上尚未生效。若 Steven 的決定 7 原意是正式包要能補價，需另立 subaccount 來源任務，並在本任務標為前置。

### [Major] t626 626.20 情況 B 不可達，且情況 A 測試與情境 22 使用 `DailyDecisionCalculator` 從不產生的 `LIVE_QUOTE_STALE`
位置：spec/tasks/t626_srpp_order_plan_v2_recompute.md:159-:164、:192
證據：
- `DailyDecisionCalculator.java:41` 的 `D-195` 收據在 `!q.suitable()` 時為 BLOCK、reason=`q.reason()`（含 `PREMIUM_OR_PRICE_UNVERIFIED`，來自 `:82`），且該收據不在 `:66` 的排除集合，必進原因集合。因此「原因集合為空、gap>0、但 eq 不合格」不可能發生（`:69` 的 `eq.suitable()` 其實是多餘條件）。情況 B 描述的 `PREMIUM_OR_PRICE_UNVERIFIED` 實際落在情況 A（原因集合非空且全為報價缺口碼）。
- `grep -ran LIVE_QUOTE_STALE backend/src/main/java/com/steven/assets/service/srpp/decision` 零結果；唯一出處是 `SrppReportModuleCalculator.java:406`（`quoteFreshness`，非債券模組）。債券腿報價缺口實際只有 `LIVE_PRICE_UNVERIFIED`（`DailyDecisionCalculator.java:37`）、`BEST_FIVE_UNVERIFIED`（`:38`）、`PREMIUM_OR_PRICE_UNVERIFIED`（`:82`）；`FUBON_*` 對 00865B 又被 overlay 覆寫成 `SUBACCOUNT_UNVERIFIED`。所以 626.20 的測試「`LIVE_QUOTE_STALE` 為唯一原因 → 腿 reasons 恰為 `[LIVE_QUOTE_STALE]`」與情境 22「唯一原因 `LIVE_QUOTE_STALE`」無法由 `DailyDecisionCalculator` 產生。
修法：刪除情況 B（或改成情況 A 的一個測資），情況 A 的測資與情境 22 改用實際會出現的碼（`LIVE_PRICE_UNVERIFIED`／`BEST_FIVE_UNVERIFIED`／`PREMIUM_OR_PRICE_UNVERIFIED`）；把「原因集合只由 `QUOTE_REFRESH_GAPS` 中 DailyDecisionCalculator 會輸出的三碼構成」寫進 626.20。

### [Major] t623（及 t625）要求正數腿 PASS 收據引用來源，但未規定來源非 VERIFIED 時降級；BFF 會拒絕整包
位置：spec/tasks/t623_srpp_d234_satellite_build_legs.md:34-:35（623.3／623.4）、:75（623.9）、:84（623.12）、:104-:105（623.17）；spec/tasks/t625_srpp_d215_fx_preference_on_bond_and_vt_legs.md:88-:90（625.10）、:141（625.16）
證據：
- 623.9 規定 `usd-twd-month-mids` 為 UNAVAILABLE 時「視為減半」，仍可算出正數上限；623.4 規定該來源的 `validation` 為 `UNAVAILABLE`；623.12／623.17 又規定 `SATELLITE_BUILD` 收據 refs「精確等於」含 `usd-twd-month-mids` 與 `market:<市場>:<代號>` 的集合，status 為 PASS。
- `bff/.../SrppReportResponseValidator.java:223`：ACTIONABLE 腿的每個非 d130Optional 收據，其 inputRefs 必須全在 `verifiedSources`，否則 `fail()`（整包 502）。因此 mids 來源 UNAVAILABLE（或 `market:台股:009826` 因 `SrppReportExtraCaptureAdapter.java` 的 dividends 置 null 而使 `assess()` 判 UNAVAILABLE）時，一條正數且 PASS 的腿會讓整份 report-calculations 被 BFF 拒絕。
- 既有計算器的做法相反：`SrppReportModuleCalculator.java:98`、`:119` 在 PASS 前先檢查 `sourceVector` 的 `validation`。t625 的 `D215_FX`（refs 含 `market:台股:<symbol>`、`fx`）有同樣暴露面，625.10 的 NORMAL→PASS 沒有此前提。
修法：在 623.12 與 625.11 加入「收據 PASS 的必要條件是其 inputRefs 在 `sourceVector` 的 `validation` 全為 VERIFIED；否則收據 UNAVAILABLE（reason 如 `SOURCE_NOT_VERIFIED:<ref>`）、腿 0 量 BLOCKED」；並明定 mids 為 UNAVAILABLE 時究竟是 BLOCKED，或腿 refs 改為不含 mids（但 623.17 的精確 refs 集合要同步改）。加測試：mids UNAVAILABLE 且其餘條件成立時，腿不得為 ACTIONABLE。

### [Major] t625 驗證區塊先重建並重啟容器，與同檔 625.19 的發布順序及 t623／t626 的重建時點衝突
位置：spec/tasks/t625_srpp_d215_fx_preference_on_bond_and_vt_legs.md:236-:237 對 :157-:161（625.19）；spec/tasks/t626_srpp_order_plan_v2_recompute.md:215；spec/tasks/t623_srpp_d234_satellite_build_legs.md「正式發布」段
證據：
- 625.19 明寫「發布順序：SRPP verifier 接受 D215_FX → 規則包重建 → 9090 登錄 → business-services／bff 重建。順序相反時，ACTIONABLE 債券腿會讓 SRPP 判 UNKNOWN_RULE」。但 t625 驗證區塊（:236-:237）在 `check-srpp-report-authority.py` 之後直接執行 `docker compose -p asset-management build business-services bff` 與 `up -d --no-deps --force-recreate`，位於「正式發布沿用 Task 504 鏈」之前，且是從實作 worktree 而非 main 重建。
- t623 的重建只出現在「正式發布（release 責任人）」且在 insert-only registry 之後；t626:215 要求「合併後依 run-stack 從乾淨 main 重建」；t624 驗證區塊完全不含 docker。四任務之中只有 t625 把重建放進驗收指令。
修法：刪除 t625 驗證區塊的 `docker compose ...` 與 `curl` 三行，併入「正式發布」段並註明「僅在登錄 insert-only registry row 之後、從乾淨 main 執行」。

### [Minor] t625／t626 驗證指令的 SRPP root 與「同一 D-234 worktree root」定案互相矛盾
位置：spec/tasks/t625_srpp_d215_fx_preference_on_bond_and_vt_legs.md:234 對 :162-:163（625.20）；spec/tasks/t626_srpp_order_plan_v2_recompute.md:211 對 :166（626.21）
證據：625.20／626.21 規定重產 manifest 與 golden 要用 D-234 worktree root，而 :234／:211 以 `--srpp-root /Users/steven/Project/SRPP`（main checkout）跑 `check-srpp-report-authority.py`。該腳本 `scripts/check-srpp-report-authority.py:216-217` 以同一 root 執行 `generate-srpp-formula-v3.py --check`，而 manifest 條目含 `SRPP/scripts/execution_policy.py` 的雜湊（`scripts/generate-srpp-formula-v3.py` 的 `named_hashes`，t623 另加 `satelliteBuild`）。實測 `shasum -a 256`：main 的 `execution_policy.py` 為 66a86a97…，worktree 的為 16fb30de…（main 尚無 `global_build_vt`），照寫法執行必得 `FORMULA_V3_SOURCE_IDENTITY` 失敗。
修法：t625:234、t626:211 改用與同區塊 formula 指令相同的 worktree 絕對路徑；並註明 `check-srpp-report-authority.py` 另需 `EMBEDDED_MODEL` 與 SRPP 正本位元組相同，只能在 623.19 的 sync 之後通過，實作期間不列為必過項。

### [Minor] t626 626.5／626.19／Requirement 223：SRPP `QUOTE_REFRESH_GAPS` 不含 `QUOTE_UNVERIFIED`，「與參考實作逐欄比對」的已知差異與 SRPP 待辦漏列
位置：spec/tasks/t626_srpp_order_plan_v2_recompute.md:76（626.5 第三項）、:154（626.19）、:180（情境 5）；spec/requirements.md Requirement 223 末句
證據：SRPP `scripts/trade_blocker_policy.py:42-43` 的 `QUOTE_REFRESH_GAPS` 是六個 D-230 碼，不含 t623 的 `QUOTE_UNVERIFIED`；`bind_api_leg`（同檔 `:279-:284`）對 009826 要求 `all(quote_refreshable(z))`，故 t623 的 `["QUOTE_UNVERIFIED"]` 零腿在 SRPP 為 ZERO 而非 QUOTE_REFRESH。626.5 刻意用不同集合（正確），但 Requirement 223 只宣告容量降量為已知差異，626.19 的 SRPP 待辦也未列此項；golden 規範「只呼叫 SRPP 原始純函式」，情境 5 的「候選」無法由 `quote_refreshable` 導出。
修法：Requirement 223 與 626.19 增列第二項已知差異與 SRPP 待辦（`QUOTE_REFRESH_GAPS` 或 `bind_api_leg` 需對 `GLOBAL_EQUITY_PACE` 的 009826 納入 `QUOTE_UNVERIFIED`）；情境 5 註明候選判定為 Java 規則、不以 `quote_refreshable` 比對。

### [Minor] t626 626.11／626.12／626.16：被共用資金擋下而重判的腿，`baseLots` 對外語意未定
位置：spec/tasks/t626_srpp_order_plan_v2_recompute.md:131（626.11 末項）、:132（626.12）、:148（626.16）
證據：626.11 規定這類腿「取回原量與限價作 base」，但 package 腿此時 lots 已歸 0（`SrppReportModuleCalculator.sharedFunds`，`:74` 起，lots 設 0、價量清空）；626.16 的 `baseLots/baseUnits` 沿 v1 為「package frozen base」，626.12 要求 `final ≤ base`，違反即 500。補價候選有明文例外（baseLots=0＋`refreshCandidate:true`，626.5），重判腿沒有，實作者可能對外輸出 0 而 final>0，或誤觸 500；626.19 的 SRPP 重播也會算出 final>base。
修法：明定重判腿對外 `baseLots/baseUnits` 取回的原量（或比照補價候選加 `refreshCandidate` 類旗標與例外），並把 626.12 的不變量改成對照同一個 base。

### [Minor] t623 623.3 兩處敘述與既有程式不符
位置：spec/tasks/t623_srpp_d234_satellite_build_legs.md:32（623.3，exchangeRate 段）、:34（mids 有效性）
證據：
- 「`exchangeRate` 為 null（或 ≤0）的 USD 列，`amountTwd` 依上述規則會退回 USD `amount`」：`PublicTransactionHistoryService.java:84-88` 只在 `getExchangeRate() != null` 時相乘，只有 null 才退回 `amount`；≤0 會得 0 或負數，仍被 `finitePositive(amountTwd)` 擋下，結論不變但敘述錯。
- mids 有效性只列「buy/sell 為 null、≤0 或日期重複」，並宣稱對應 BFF 整支回應 502；實際 `UsdTwdPublicService.validateRates`（`:252-:256`）另拒絕 `buy > sell`，且驗整個兩年窗與 live 回應，而本任務只檢當月列。壞資料出現在當月以外時，Python 端 502→rates=[]→減半，Java 端不減半，與「逐欄等價」不符。
修法：更正第一處敘述為「僅 null 退回 amount；≤0 得 0 或負值」；mids 有效性補上 `buy > sell`，並二選一：同樣檢查兩年窗，或在 623.9 寫明此為已知差異並加 golden 斷言。

### [Minor] t626 626.9／626.11／626.14：helper「package-visible」跨 package 不足
位置：spec/tasks/t626_srpp_order_plan_v2_recompute.md:112（626.9 `book`／`Quote` 抽取）、:124（626.11 費用 helper）、:143（626.14 `normalize`）
證據：`DecisionInputCaptureAdapter.book`（`DecisionInputCaptureAdapter.java:154`）是 `private static`，`normalize`（`:162`）與 `Quote` 建構（`:103`）是 package-private，`DailyDecisionCalculator.cost`（`:134`）是 `private static`，而呼叫端（`SrppReportModuleCalculator`、`SrppProjectionService`）在 `service.srpp.report` package，來源在 `service.srpp.decision` package。`package-visible` 無法跨 package；626.14 還指名 `DecisionInputCaptureAdapter.normalize`，但同 package 已有 public 的 `SrppReportModuleCalculator.normalize`。
修法：把 626.9／626.11 改成「抽成 public static helper（放在 decision package，供 report package 呼叫）」，626.14 改用 `SrppReportModuleCalculator.normalize` 或明指新 public helper。

### [Minor] t626 626.17 reason code「封閉清單」與「另透傳原 blockingReasons」自相矛盾
位置：spec/tasks/t626_srpp_order_plan_v2_recompute.md:149（626.17）、:148（626.16 `reasonCodes`）
證據：626.17 稱「新 reason codes（封閉清單，加入 OpenAPI enum）」，同句又「另透傳 package／重跑腿的原 `blockingReasons`」；v1 `SrppOrderPlanLeg.reasonCodes` 在 `docs/openapi/docker-external-api.yaml:17023` 是自由字串（`items: type: string`），透傳碼（如 `LIVE_PRICE_UNVERIFIED`）無法同時被一個封閉 enum 涵蓋。另 626.16 的 `funding` 子物件寫「未知為 null 並附 reasonCodes」，但鍵清單 `{startHeadroom, fundingUsed, fundingHeadroomAfter}` 不含 `reasonCodes`。
修法：改成「v2 `reasonCodes` 維持自由字串；本任務新增碼只在說明文字列舉」，或把透傳碼改為統一包成 `PACKAGE_REASON:<code>` 並進 enum pattern；並在 `funding` 鍵清單補上 `reasonCodes`。

## 已查無發現
- 可查證性（逐一對照實際程式，皆存在且行為與 spec 描述相符）：`SrppReportModuleCalculator` 的 `GLOBAL_EQUITY_PACE` 區段、`paceAdvance`／`paceCapacity`、`CONFIRMED_FILL_PACE_STATE_EVOLUTION`、`calculateShared` 呼叫序、`sharedFunds`、`namedCashRotationFinalGates`、`funding`、`bond`、`reportBondInput`、`transit`／`term`／`original`、`M.fact` 預設 `FIELD_SOURCE_UNVERIFIED`、`quoteFreshness`；`SrppReportExtraCaptureAdapter` 的 `global-pace` UNAVAILABLE 清單（:81）、`Set.of("VOO","0050","00881")` 與只對 VOO 附 `usdFxPurchase`、reuse 路徑 `rows=List.of()`；`SrppReportProducerScheduler.produce`／`withReportReservations`／`finishCapture`；`SrppReportPublisher.publish` 以 `convertValue(original.path("facts"), DecisionFacts.Input.class)` 重建 Frozen；`SrppReportFingerprint`（無需改）；`PublicTransactionHistoryService.toRecord` 的 amountTwd 規則；`AssetTransactionRepository` native query 先例與 `existsByOwnerUserIdAndBrokerFilledNo` 註解；`ExchangeRateHistoryRepository.findByCurrencyAndRateDateBetweenOrderByRateDateAsc`；`UsdTwdPublicService.mid`（HALF_UP 4 位）；`UsdFxPurchaseContext.forAsset／live／PolicyInstruments`、`HistoryProof.rowsSha256`、`windowRows`；`RadarCompletedPriceDrawdown.Context.validPriceFact`；`DailyDecisionCalculator.inflation`（`StrictMath.pow`）、`candidate`、`limit`；`DecisionInputCaptureAdapter` 的 termDeposits（:93-:96）、`Quote` 建構（:103）、`fxQualified` 接受 INDICATIVE（:145）、transit 註解（:149-:150）；BFF `SrppReportResponseValidator` 的 `FACT_NAMES`（:22 `FUNDING_CAPACITY`）、COMPLETE 模組禁 UNAVAILABLE fact（:142）、`leg()` 的台股限制與 `boundStrategy`、單位集合；`PublicQuoteMarketDataService.isApprovedCompleteBook`（條件與 623.9 列表一致，僅時鐘改為 capturedAt）；`SrppProjectionService.order/plan/projectLeg/quoteProof`、`SrppProjectionRequest`、`InternalSrppProjectionController`；`PriceQueryService.getLiveBatch／getEtfNav`、`MarketDataService.getQuoteDetail`／`approvedQuoteDetailSnapshot`；`SrppReportFubonCapturePort`／`PersistedSrppReportFubonCapture`；OpenAPI 版本 1.28.4、`SrppReportLeg.market` 文字、`SrppReportFactGlobalEquityPace`／`SrppReportFactFundingCapacity` 存在；`scripts/` 下 render／sync／formula／authority／policy-register 腳本與 `--asset-only`／`--check`／`--srpp-root` 旗標、`srpp-report-policy-register.rb` 第 97 行 V3 檢查；各既有測試類別（`SrppReportModuleCalculatorTest`、`SrppReportProducerSchedulerTest`、`SrppReportShortUsCalculatorTest`、`SrppReportMediumDipCalculatorTest`、`SrppOrderPlanServiceTest`、`SrppOrderPlanProjectionServiceProxyTest`、`UsdFxPurchaseContextTest`、`DailyDecisionCalculatorTest`、`SrppReportResponseValidatorTest`、`SrppProjectionQueryTest`、`SrppReportRepositoryPostgresTest`）皆存在；SRPP 側 `execution_policy.py` 的 `global_build_009826`／`global_build_vt`／兩個 api_inputs、`trade_blocker_policy.pooled_funding`／`QUOTE_REFRESH_GAPS`／`REFRESH_LOT_CAPS`／`candidate_debit`／`_commitments`、`usd_asset_fx_preference.evaluate`、`risk_evidence_evaluator.classify_risk_mode`、`budget_accounting.reference_price_level`、`D139-2026-08-30` fixture 與 `test_global_build_vt.py` 的測試名稱均存在。
- 公式等價（逐步對照 SRPP 原始碼）：009826／VT 的期間、報價身分、基準價、節奏掃描（含 `pace_ok` 的 continue 語意與 `usd_rate_ok`）、匯率減半、room 與阻擋碼順序、`BELOW_MIN_ORDER_SHARES` 的迴圈順序、輸出 evidence 鍵；`pooled_funding` 代數（PASS ⇔ term ≥ termFloor 且 d ≤ headroom，含 USD 底線 0 與 headroom<0 邊界）；D-215 17 個案例的預期狀態逐一對照 `evaluate()` 分支皆正確（含 `FX_TWO_YEAR_RANGE_ZERO`、2/29 起點、INDICATIVE、未列名台幣債、例外 ETF 嚴格大於、個股含等於）。
- 內部一致性：requirements.md／design.md／四份 task 的任務與需求編號（220-223／623-626）、golden 案例數（15／17／22 情境逐列齊全）、t623→t624→t625→t626 的 VT 腿收據集合演進、`SATELLITE_BUILD_D143/D234` 命名、refs 集合、D-182 TWD 承諾在 t623／t624／t626 的口徑一致。
- 架構鐵則：新計算皆為純類別或 service 內 @Component（`SrppSatelliteBuildCapture` 只讀 DB，顯式 owner native query），無 Controller 直讀 Repository、無新頁面或 BFF 路由、無 `@Data`（`SrppPooledFunding.Result` 為 record）；無新增 route，order-plan 以 query 選版且 route 數不變；BFF 僅驗證不計算。
- 資料來源／schema：`db/schema.sql` 的 `asset_transaction`（`shares numeric(15,5)`、`amount numeric(20,2) NOT NULL`、`exchange_rate numeric(10,4)`、`asset_code`／`market`／`currency` 可 null）與 `exchange_rate_history`（`buy_rate`／`sell_rate` 可 null）與 spec 斷言相符，未使用 `db/changelog/**`；未新增 Liquibase；未把 DB 可設定的排程時點寫死（09:05／12:00 為既有 `slotFor` 程式碼常數與 SRPP 規則）。
- spec-check：BLOCK 0、CHECK 0，未重查。

## 自我挑戰
- 撤下：「`UsdFxPurchaseContext.preference()`／`priceException()` 接受 `HISTORICAL_PROXY`」在報告路徑不可達（報告路徑只經 `live(...)` 產生 LIVE 或 UNAVAILABLE），625.2 的收緊是無害的重複防護，不構成缺陷。
- 撤下：t623 背景「`global-pace` 一律寫成 UNAVAILABLE」措辭略過 `frozenSources.has(id)` 的 `continue`；但全樹（含 main）沒有任何 producer，只有測試 fixture，不影響實作者行為。
- 撤下：「`calculateShared` 拆成三段」現況其實已是 `sharedFunds` 與 `namedCashRotationFinalGates` 兩個獨立靜態呼叫，實際只需抽出 pre-capacity 一段；措辭略誇大但不會讓實作者做錯。
- 撤下：VT 腿 D-215 例外價 P 究竟取 ask1 還是 last（625.5 與 625.14 措辭不同）；我無法證明兩者在 golden 路徑上不一致，且取較高的 ask 只會更保守，不報。
- 撤下：626.8 以代號尾碼 B／SGOV 判債券型，與 D-215 政策「不得由代號推斷債券 ETF」看似衝突；實際是 SRPP 兩條不同規則（風險模式 `assess` 明文用尾碼、D-215 用 verified_instruments），spec 各自照搬，不構成缺陷。
- 保留但承認有條件：第三條 Major（來源非 VERIFIED）取決於 mids／`market:*` 來源在正式資料中真的出現 UNAVAILABLE；但 spec 已明確規定該情形仍算正數上限並強制 refs 集合，加上 BFF `:223` 是確定性規則，因此即使機率低仍屬 spec 自身矛盾，保留。
- 保留但承認有條件：第四條 Major（t625 驗證區塊含 docker 重建）若實作者本來就只在 release 時才跑該段則無實害；但同檔 625.19 已明文順序、其餘三任務都不放，屬自相矛盾，保留。
- Minor 的 SRPP root 一條：在實作前（manifest 尚未以 worktree 重產）指令不會失敗，我以 t623 之後重產 manifest 為前提；該前提是 623.18／624.9／625.20 的明文流程，故保留。
