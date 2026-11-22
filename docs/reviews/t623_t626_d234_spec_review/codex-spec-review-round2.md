VERDICT: CHANGES_REQUESTED

## 判定
critical: 0   major: 3   minor: 5

## Findings
### [Major] t623 的 009826／VT 建倉腿沒有任何時間守門，14:00 後的 AFTER_CLOSE_INITIALIZATION 包可能含 ACTIONABLE 腿，整包會被 envelope validator 拒絕
位置：spec/tasks/t623_srpp_d234_satellite_build_legs.md 623.7、623.8、623.13（無 after-close 條款）；backend/src/main/java/com/steven/assets/service/srpp/report/SrppReportEnvelopeValidator.java:33-46；SrppReportProducerScheduler.java:63、76-77、85-92
證據：
- `SrppReportProducerScheduler.produce` 在台北 14:00 之後走 `afterClose`，slot 固定 "12:00"，並以 `withAfterCloseContext` 寫入 `publication-context` 來源，再交給 `publisher.publish`。
- `SrppReportEnvelopeValidator.validatePublicationContext` 對每個 module 的 `data` 呼叫 `requireNonExecutable`：`require(!"ACTIONABLE_FOR_REPORT".equals(status))`，且任何 `lots／units／limitPrice／amount` 非零即丟例外。發布與 GET 都驗。design.md:13319 也寫明「拒絕任何 actionable status 或正 lots／units／limitPrice／amount…保持原計算結果，不事後清零／移除腿」。
- 現有各腿靠報價與五檔時效自然變成 BLOCKED（`DailyDecisionCalculator.quoteQualified`、`bookQualified` 的分鐘上限）。`rg publication-context` 顯示 SrppReportModuleCalculator 完全不讀這個來源。
- t623 的兩個移植公式沒有時間檢核：009826 只比對 `quote.tradingDate == trading_date` 與價格為正，VT 的報價日期本來就是前一個美股完成日、與台北時間無關。14:00 後只要 fills、fx、total assets 都合格，就會算出正數並成為 ACTIONABLE（`market:美股:VT` 與 `fx` 仍可為 VERIFIED）。`grep -n "AFTER_CLOSE\|publication-context" spec/tasks/t62[3-6]*.md` 無任何結果，623.22 的測試清單也沒有這一項。
- 結果是 UPSTREAM_INVALID，收盤後初始化包（最多重試 3 次）無法發布，連帶其他十一個模組一起失敗。
修法：在 t623 新增一條（建議放 623.13 之後）：`input.sources` 含 `publication-context`（mode=AFTER_CLOSE_INITIALIZATION）時，兩條腿一律走 0 量 BLOCKED，blockingReasons 加具名碼（例如 `AFTER_CLOSE_INITIALIZATION_NON_EXECUTABLE`），facts 維持純計算結果。623.22 增加測試：用 `withAfterCloseContext` 後的 Frozen 計算，確認結果能通過 `SrppReportEnvelopeValidator.validate`。同步更新 Requirement 220 AC 與 design 220。

### [Major] t623 的每日建倉腿會被既有 report-reservations 永久保留；VT 腿以 USD 入帳卻實際扣 TWD；t626 的 H_c 公式因而被吃光
位置：spec/tasks/t623 623.14（只處理同包內 sharedFunds）；t626 626.11（`max(fundingCommitments, packageReservedFunds)`）；backend/.../report/SrppReportReservationCapture.java:36-52；backend/.../repository/SrppReportRepository.java:47-50；SrppReportModuleCalculator.java:63-67（funding）、99（topup）、151（reservationClass）
證據：
- `readReportSource` 走 `visitUnlinkedReportReservations`，SQL 是 `WHERE owner_user_id=? AND (trading_date<? OR (trading_date=? AND report_slot<?))`，也就是這位 owner 所有更早的包。每一條 `ACTIONABLE_FOR_REPORT` 腿都被轉成 `status=ACTIVE` 的 reservation row，欄位原樣複製 symbol、market、strategy、action、lots、units、amount、currency、fees；`linkedOrderId`、`linkedFillId` 永遠寫 null（:49）。
- 測試 `SrppReportRepositoryPostgresTest.allModuleReservationsDeduplicateConsumersAndRemainAcrossPolicyAndWeekChanges`（:141-147）把這個行為鎖成設計：兩週後仍保留。類別註解也寫「Saved recommendations remain reserved until an exact canonical leg/order/fill link exists」，而 repo 內沒有任何程式設定 link。
- `funding()`（:65）對每條 ACTIVE BUY row 做 `reserved.merge(currency, amount+fee)`，`deployableCapacity` 因此逐日縮小；`topup()`（:99）也把這些 row 算進 reservedEquity 與 reservedFunds。
- t623 每個交易日、兩個時段都可能產生正數腿（009826 日 3 張、VT 日 5 股），而 D-234 設定明寫 `pace_consumed_only_by_confirmed_fills`、未成交提案不消耗額度。reservation 機制會與這個語意相反地累積，spec 完全沒有提到。
- VT row 的 `currency` 是 USD、`amount` 與 `fees` 是 USD（fees "3"），但腿實際由國泰台幣複委託扣 TWD。`funding()` 會扣到 USD 池，TWD 池沒有被扣；`reportBondInput`、`SrppReportMediumDipCalculator.commitments` 也依 row.currency 換算。`reservationClass`（:151）的策略後備清單不含 `SATELLITE_BUILD_D143／D234`，`resolvedStockClasses` 又只涵蓋目前持股（DecisionInputCaptureAdapter.java:56），VT 若尚無持股就丟 `RESERVATION_EXACT_CLASSIFICATION_UNAVAILABLE`，使 topup 的 reservationVerified 變 false。
- t626 的 `H_c = pooledPostBuyHeadroom − max(fundingCommitments, reservedFunds)` 取 package 的 `reservedFunds` fact；若上述 TWD reservation 持續累積，H_TWD 會變負，v2 所有 TWD BUY 都變 0。
修法：在 t623 新增一條，明定新策略在 `readReportSource` 的處理方式，並寫入 Requirement 220 與 design。可選：(a) 排除 `strategy ∈ {SATELLITE_BUILD_D143, SATELLITE_BUILD_D234}`，因為節奏只算確認成交、這兩腿只是上限；(b) 納入但限制為當日、並把 VT row 轉成 TWD（amount=twdAmount、fees=twdFee、currency=TWD）。無論選哪個，要補 SrppReportReservationCaptureTest 與 SrppReportRepositoryPostgresTest 的案例（多日後 reservedFunds 不累積、VT 不扣 USD 池、classification 缺時不使 topup 的 reservationVerified 變 false），並在 626.11 說明 `reservedFunds` 與這兩腿的關係。

### [Major] t626 626.9 的 BOND 補價 frame 沒有重算 `market:台股:<symbol>` 的 validation，與 t625 的 D215_FX PASS 前提衝突，使「報價缺失型」補價永遠失敗
位置：spec/tasks/t626 626.9（BOND_D130_D195 子項）對比 626.9 的 009826 子項與 t625 625.11 PASS 前提（t625:102）
證據：
- 626.9 的 009826 子項明寫「刷新 frame 的 sourceVector["market:台股:009826"].validation 以 assess(刷新後 body, tradingDate, refreshAt) 重算」。BOND 子項只說換 `quote／book／nav`，其餘一律沿用 package 凍結值，沒有同樣的重算。
- t625 在 `bond()` 內加 D215_FX，PASS 前提是 `fx`、`policy`、`market:台股:<symbol>` 在 sourceVector 全為 VERIFIED，否則 UNAVAILABLE `SOURCE_NOT_VERIFIED`，腿轉 BLOCKED 0。626.9 的補價正是重跑 `bond()`。
- `SrppReportExtraCaptureAdapter.assess`（:84-91）在 `quote.stockCode` 不符或 price 非正時回 `validation=UNAVAILABLE`。package 時 quote 為 null（Redis 未命中、身分不符）正是 D-230 要補價的典型情形，此時 `market:台股:<symbol>` 在 package 內就是 UNAVAILABLE；補價換入新報價後若不重算，D215_FX 仍 UNAVAILABLE，重跑腿為 0，落入「重跑腿仍為 0」分支。
- 這使 t626 對「報價缺失」型債券腿的補價名存實亡；只剩「報價過期但仍存在」的情形能補。
修法：626.9 的 BOND 子項補一句，與 009826 子項相同：對刷新 frame 內每個換入新報價的 `market:台股:<code>`，以 `assess` 重算 validation 並寫入該 frame 的 sourceVector；`fx`、`policy` 沿用凍結值。測試補一案：package 時 market 來源 UNAVAILABLE（quote 為 null）、刷新後 VERIFIED，補價腿能通過 D215_FX。

### [Minor] t626 驗證區塊的報告 golden 仍指向 SRPP main；golden 與 bundle 綁定，release 鏈也漏列重產 golden
位置：spec/tasks/t626 驗證區塊（`python3 scripts/generate-srpp-report-golden.py --srpp-root /Users/steven/Project/SRPP --check`）；spec/tasks/t623 623.19
證據：
- `scripts/generate-srpp-report-golden.py:279` 把 `policyBundleSha256` 與 `originalCalculatorHashes`（含 `execution_policy.py`）寫進 golden。`check-srpp-report-authority.py:168-176` 的 `verify_golden` 以同一個 `--srpp-root` 呼叫它，並要求 golden 的 bundle 等於該 root 的 bundle。
- main 與 D-234 worktree 的 `execution_policy.py`、`model_inputs.json` 位元組不同（`sha256sum` 實測不同）。因此同一份已追蹤 golden 不可能同時通過 main root 與 D-234 root 的 `--check`。
- 第 1 輪處理紀錄說已把 t625／t626 的 root 全改為 D-234 worktree，但 t626 這一行沒改。
- 對照 t622（spec/tasks/t622…:22-25）：每次 bundle 變動都要重產 report-same-input、long-short-exit、state-topup 等 golden；t623 623.19 的發布鏈沒有這一步，`check-srpp-report-authority.py --check` 在 release 時會因 `GOLDEN_BUNDLE_MISMATCH` 或 generator 比對失敗。
修法：t626 該行 root 改為 D-234 worktree（或註明只在 D-234 合併後執行）；623.19 發布鏈在 formula V3 之前加入「以 D-234 root 重產 report-same-input、long-short-exit、state-topup golden 並 `--check`」。

### [Minor] 626.20／決定 7 的「正式資料上尚未生效」前提不完整：`emergencyValue` 在正式資料也恆為 null
位置：spec/tasks/t626 決定 7、626.5 前提、626.20 前提與完成報告條款；requirements.md Requirement 223；design.md Requirement 223
證據：
- `DecisionInputCaptureAdapter.java:130` 建 `Input` 時最後一個參數 `emergencyValue` 傳 `null`；`SrppReportFubonOverlay.java:29` 沿用 `old.emergencyValue()`；`rg emergencyValue` 全 repo 沒有其他來源。
- `DailyDecisionCalculator.java:68`：`gap = in.emergencyValue()==null ? null : …`，而 626.20 的唯一分支條件是 `gap != null && gap > 0`。
- 所以即使子帳來源日後被驗證，這個分支在正式資料上仍不觸發，直到 `emergencyValue` 也有來源。spec 只把前提寫成子帳來源，完成報告也要求寫「待子帳來源驗證」。
修法：把前提改為「子帳來源與緊急子帳市值（`emergencyValue`）皆須有來源」；626.20 測試的合成 `Input` 要明寫帶非 null 的 `emergencyValue`；完成報告條款同步。

### [Minor] 626.16 的 v2 腿欄位 `riskAffected` 沒有定義，v2 `coverage` 也沒有統計規則
位置：spec/tasks/t626 626.16；對照 bff/.../PublicSrppProjectionService.java:206-236（v1 `validateLeg`／LegOutcome）
證據：
- `riskAffected` 只在 626.16 的鍵清單出現一次（`grep -rn riskAffected spec` 僅此一處），型別與語意（只指 DEFENSIVE 實際歸 0？還是所有股票 BUY？）在 626.8 沒有定義。回應 schema 是封閉鍵集，BFF 要逐鍵驗證。
- v1 的 `coverage` 由逐腿 outcome（COMPLETE／UNAVAILABLE／NOT_EVALUATED）統計，BFF 也據此重驗；v2 新增補價、降量、時效等結果，626.16 仍列 `coverage`，但沒有說 v2 的 outcome 如何分類。
修法：626.8 定義 `riskAffected`（布林，建議：此腿屬增加股票風險的買進即為 true，與是否被歸 0 無關，或另述）；626.16 補 v2 outcome 與 coverage 的統計規則，並列入 BFF 閉合驗證與測試。

### [Minor] 623.20 的 BELOW_MIN_ORDER_SHARES golden 作法只改 min_shares_per_order 無法造出該案
位置：spec/tasks/t623 623.20 的 VT 案例清單
證據：`global_build_vt`（SRPP execution_policy.py:826-833）在 pace_ok 時一定有 `room["day"] = max_shares_per_day − day_shares`。現值 `max_shares_per_day=5`，所以 room 最多 5，在 `min_shares_per_order=5.5` 時先記 `DAILY_SHARE_LIMIT`（5 < 5.5），不會走到 `BELOW_MIN_ORDER_SHARES`。pace_ok 為 false 時會有 `PACE_STATE_UNVERIFIED`，同樣不可能。SRPP 的 `test_global_build_vt.py` 也沒有這個案例。要造出該案，副本 policy 需同時調高 `max_shares_per_day` 與 `max_shares_per_week`。
修法：改寫該案說明：專用 policy 副本須同時設定 `min_shares_per_order=5.5` 與大於 6 的日／週上限，並讓最小 room 落在 [5.5, 6)；case 內保存完整所用 policy。

### [Minor] 626.11 靜默捨棄 D-234 §5.3 的「配置群組上限」，未記為已知差異
位置：spec/tasks/t626 626.11「配置群組與日／週節奏」段；SRPP docs/SRPP_CALCULATION_MIGRATION_D234_SPEC.md §5 步驟 3
證據：D-234 批次規格 §5.3 寫「依順序扣減 pooledPostBuyHeadroom、配置群組上限與日／週節奏」。t626 寫成「不新增跨 module 群組上限」，也沒有說明為何可省（例如各腿原計算器已套群組上限、降量與補價只會縮小量；補價的 009826 腿則不經 `topup()` 的群組檢查）。
修法：在 626.11 補一句理由並列入「已知差異」與 SRPP 待辦；若補價的 009826 腿需要群組檢查，要明寫。

## 已查無發現
逐項驗證第 1 輪 10 項處理，全部屬實、修正位置存在，第 1 輪未有誤判：
1. 00865B 補價前提：DecisionInputCaptureAdapter.java:80（subaccounts 硬編 UNAVAILABLE）、SrppReportFubonOverlay.java:21 與 SrppReportFubonCapturePort.java:15 已核對；前提文字出現在 t626 決定 7（:35）、626.5（:76）、626.20（:162、:168）、情境 22（:196）、完成報告（:224）、requirements.md:7327、design.md:14340。補充見上方 emergencyValue 的 Minor。
2. 情況 B 刪除與三碼：`grep LIVE_QUOTE_STALE backend/.../srpp/decision` 無結果，只出現在 SrppReportModuleCalculator.quoteFreshness（:406）；DailyDecisionCalculator 的三碼在 :37、:38、:82 已核對；`add()` 對 UNAVAILABLE 會把 reason 加入 eb，但 `eb.first()` 本來就在 eb 內，所以不會多一個碼，分支邏輯成立。
3. PASS 前提：BFF `SrppReportResponseValidator.java:223`（非 d130Optional 收據 inputRefs 必須全在 verifiedSources）、:120（COMPLETE 模組 inputRefs 全 VERIFIED）、:142（COMPLETE 不許 UNAVAILABLE fact）已核對；t623:87、t625:92／102／149／223 的條文存在。但 BOND 補價路徑的 market 重算漏掉，見 Major 3。
4. t625 驗證區塊的 docker 三行已移到「正式發布」段（本輪材料已見）。
5. root 矛盾：t625、t626 其他兩行已改 D-234 worktree；t626 報告 golden 那行仍用 main，見 Minor 1。
6. QUOTE_UNVERIFIED 已知差異：SRPP trade_blocker_policy.py:42-43、:199-203、:279-280 已核對（`quote_refreshable` 只認六碼、`bind_api_leg` 對 009826 另走 refreshable_identity）。
7. redecided／baseLots：SrppReportModuleCalculator.sharedFunds（:74-90）會把 lots 歸 0、清空價量，blockingReasons 恰為單一 reason，所以「恰為」判定可行；`calculateShared`（:27-32）可拆為三段。
8. t623 623.3：PublicTransactionHistoryService.java:84-88、BFF UsdTwdPublicService.validateRates:252、validateHistory:114、live:198 已核對；`isApprovedCompleteBook`（PublicQuoteMarketDataService.java:705）的條件另含 stockName 非空、fetchedAt 非空、stockCode 格式，623.9 的列舉沒列出但寫「同等條件」，不另報。
9. helper 可見度：`DailyDecisionCalculator.cost` 為 private static（:134）、`SrppReportModuleCalculator.normalize` 為 public（:34）已核對；`DecisionInputCaptureAdapter` 的 `book`／`normalize` 行號未逐行核對。
10. reasonCodes 維持自由字串：OpenAPI SrppOrderPlanLeg.reasonCodes 為 `items: type: string`。

其餘維度已查：
- 可驗證性：t623–t626 引用的類別與方法逐一存在並與簽章相符（UsdFxPurchaseContext 的 forAsset、live、PolicyInstruments、RadarCompletedPriceDrawdown.Context、SrppReportModuleCalculator.quoteFreshness、SrppReportPublisher 的 Frozen 重建、PriceQueryService.getLiveBatch、getEtfNav、MarketDataService.getQuoteDetail）。驗證區塊的指令與旗標存在：render-9090-openapi-docs.rb 的 `--srpp-root`、`--asset-only`、`--check`；sync-srpp-swagger-identity.py；check-srpp-report-authority.py；generate-srpp-formula-v3.py；十一個測試類名皆存在。
- SRPP 參考實作逐步比對：`global_build_009826` 與 `global_build_vt`（reasons 順序、`usd_rate_ok`、room 鍵序、VT 不加一股的 post_buy_weight、BELOW_MIN 順序）與 623.7、623.8 一致；`global_build_api_inputs` 與 623.9 一致；`pooled_funding` 的代數與 624 的等價推導成立；通膨指數三個日期的數值（1.0047017700289897、1.0050690687927297、1.0111489213082812）以 Python 重算相同；`classify_risk_mode` 與 626.3 邊界一致；`risk_score_decision_policy` 四值與 `risk_score_category_maxima`、`risk_score_categories` 在內嵌 model_inputs.json 存在。
- 架構鐵則：沒有 Controller 直注入 Repository、新 DTO 為 record 的描述；沒有新路由（order-plan 以 query 選版）；沒有前端新頁；IO 邊界只讀快取（`getLiveBatch` 為 Redis MGET、`getEtfNav` 為 Redis get、`getQuoteDetail` 只認持久化 producer 快照）。
- 資料來源與 schema：`db/schema.sql` 的 asset_transaction（shares numeric(15,5)、exchange_rate numeric(10,4)、amount NOT NULL）與 exchange_rate_history 欄位與 623.3 相符；spec-check 為 BLOCK 0／CHECK 0。市場來源的 universe 含 009826、VT（detailed_target_allocation_2027）。
- 內部一致性：requirements、design、tasks 三處對 22／17／15 案例數、策略名、收據集合、`SATELLITE_BUILD` refs 規則一致；OpenAPI 的 `SrppReportLeg`、`SrppReportReceipt`、`SrppReportDataGlobalEquityPace.legs` 允許新腿與新收據值（units 的 pattern、currency 為自由字串、receipt values 的 unit 允許 BOOLEAN／RATIO／PERCENT／LOTS／SHARES／ISO 三碼）。v1 order-plan 對美股腿本來就支援 market `US`，新增 VT 腿不會讓 v1 失效。
- 各驗證指令的 formula V3 測試（srpp-formula-v3-test.py）會從現行 fixture 宣告的 source 建樹，新增 satelliteBuild 條目不致使該測試失效。

## 自我挑戰
- Major 1：最大不確定在 14:00 後 Redis 是否還保有當日 009826 報價，因此 009826 腿可能不為正；但 VT 的報價身分只取決於美股日曆，不隨台北時間改變，且 spec 沒有任何 after-close 條款，design.md:13319 又明寫不允許事後清零，所以保留。如果 VT 的 `market` 來源在 after-close 一律 UNAVAILABLE，就只是少報嚴重度，不是誤報。
- Major 2：最強的證據是 `visitUnlinkedReportReservations` 的 SQL 與 Postgres 測試名稱；我沒有在正式資料庫上實測累積量。若維護者實際另有「成交後清除」機制，我沒有在 repo 找到（`linkedOrderId` 只在 putNull、讀取端出現）。保留，修法列了兩種選擇，讓作者判定。
- Major 3：fail-closed，不會做錯方向，只是補價失效；因 626.9 的 009826 子項已明寫重算而 BOND 子項沒有，屬同一文件的不一致，故列 Major。若作者認為債券腿 market 來源在報價缺失時本來就不會補價，應在 626.9 明寫，那這條可降為 Minor。
- 已撤下或不報的項目：(a) 「US_TACTICAL 不是 module 名稱」：`reservationClass` 的策略清單含 `US_TACTICAL`，不算憑空名稱，撤下。(b) formula V3 manifest 沒涵蓋 `SrppPooledFunding.java`、`SrppReportD215FxPreference.java`：現有 manifest 同樣沒涵蓋 `UsdFxPurchaseContext`、`DailyDecisionCalculator`，屬既有慣例，撤下。(c) OpenAPI 版本只在 t626 升 minor：未發現測試要求逐任務升版，撤下。(d) `render-9090-openapi-docs.rb` 拒絕 primary checkout：spec 的「合併後改用 /Users/steven/Project/SRPP」只用於 formula V3 與 authority check，這兩者不呼叫 render／sync，撤下。(e) `riskTotalScore ≤ assessed×max` 的交叉約束未寫：伺服器已用 `riskMode` 一致性擋掉大部分不合理組合，且屬防禦性，不報。
- Minor 4 到 8 都保留：證據是具體行號或代碼；Minor 8（群組上限）屬決策紀錄完整性，若 Steven 已口頭同意可不修。
