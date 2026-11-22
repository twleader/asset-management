VERDICT: PASS

## 判定
critical: 0   major: 0   minor: 5

## Findings
### [Minor] Requirement 223 的 coverage outcome 敘述把 WAIT／VETO 歸入「其餘為 COMPLETE」，與 design.md 與 t626 626.16 的 NOT_EVALUATED 不一致
位置：spec/requirements.md:7330；對照 spec/design.md:14344、spec/tasks/t626_srpp_order_plan_v2_recompute.md:159（626.16 優先序第 1 項）
證據：
- requirements.md:7330 原文為「`coverage` 依逐腿 outcome（未判斷或缺 base 單位為 NOT_EVALUATED、因缺證歸 0 為 UNAVAILABLE、其餘為 COMPLETE）統計」。WAIT／VETO 是已做出的判斷，照字面落入「其餘為 COMPLETE」。
- design.md:14344 寫「NOT_EVALUATED（WAIT／VETO／NOT_DECIDED、REDUCE_BASE_UNIT_UNAVAILABLE）」；626.16 第 1 項也是 `verdict ∈ {WAIT, VETO, NOT_DECIDED}` 或 `REDUCE_BASE_UNIT_UNAVAILABLE` 為 NOT_EVALUATED。
- v1 現況也把 WAIT／VETO 算 NOT_EVALUATED：`SrppProjectionService.java:60` 起（預設 `Coverage.NOT_EVALUATED`，只有 SELL＋ADOPT 才 COMPLETE），BFF `PublicSrppProjectionService.java:233-238`（WAIT／VETO／NOT_DECIDED 回 `LegOutcome.NOT_EVALUATED`）。
出處：內部一致性（requirements、design、tasks 三處對同一規則的敘述）。
修法：requirements.md:7330 改為「WAIT／VETO／NOT_DECIDED 或缺 base 單位為 NOT_EVALUATED、final 為 0 且因缺證為 UNAVAILABLE、其餘為 COMPLETE」，與 626.16 逐字對齊。

### [Minor] 626.5 收盤後段落寫「每條 BUY 腿 …（outcome COMPLETE）」，與 626.16 的 outcome 優先序和 626.7 的 WAIT／VETO 規則衝突
位置：spec/tasks/t626_srpp_order_plan_v2_recompute.md:83（626.5 收盤後初始化包段）；對照 :88（626.7）、:159（626.16）
證據：
- 626.5 寫「每條 BUY 腿 final 為 0 並加 `AFTER_CLOSE_INITIALIZATION_NON_EXECUTABLE`（outcome `COMPLETE`）」。
- 626.7：WAIT／VETO 為 0、加 decision reason、不做任何讀取；626.16 第 1 優先：verdict 為 WAIT／VETO／NOT_DECIDED 的腿是 NOT_EVALUATED，先符合者為準。因此收盤後包中 verdict 為 WAIT／VETO 的 BUY 腿依 626.16 是 NOT_EVALUATED，不是 COMPLETE。
- 626.5 也沒說 WAIT／VETO／NOT_DECIDED 的 BUY 腿是否仍加上收盤後碼。實作者照 626.5 字面把所有收盤後 BUY 腿記為 COMPLETE，伺服器的 coverage 計數就與 BFF `validateLegV2` 依 626.16 重算的結果不同，BFF 會回 502。測試只寫了 ADOPT 的情境，抓不到。
出處：內部一致性（同一份 t626 的 626.5 與 626.16）。
修法：626.5 該句改為「ADOPT／REDUCE 的 BUY 腿 final 為 0 並加此碼（outcome 依 626.16 判定，為 COMPLETE）；WAIT／VETO／NOT_DECIDED 的 BUY 腿維持 626.7 的 0 與各自原因，outcome 為 NOT_EVALUATED，並明寫是否加收盤後碼」；626.5 的測試補一個 WAIT 決策案，斷言 coverage 計數與 BFF 重算一致。

### [Minor] 623.3(2) 的 `usd-twd-month-mids.rows` 沒有規定數值為 canonical 字串，直接用 BigDecimal 節點會讓 JCS 雜湊拋例外
位置：spec/tasks/t623_srpp_d234_satellite_build_legs.md:34（623.3 第 2 項）；對照 :30（第 1 項已明寫 canonical decimal 字串）
證據：
- 第 1 項（fills）明寫「數值以 canonical decimal 字串或 null 表示」。第 2 項只寫 `rows:[{date, buyRate, sellRate, midRate}]` 與 `midRate=(buyRate+sellRate)/2，setScale(4, HALF_UP)`，沒指定型別。
- `SrppJcs.canonicalize`（backend/src/main/java/com/steven/assets/srpp/SrppJcs.java:113-130 的 `integerText`）對非整數 BigDecimal 拋 `JCS_NON_INTEGER_NOT_ALLOWED`。`SrppReportEnvelopeValidator.safeJson` 對浮點節點拋 `IllegalArgumentException`。623.4 以 `SrppJcs.hash(body)` 寫入 sourceVector，所以 `ObjectNode.put("midRate", BigDecimal)` 會使 `produce` 在啟用 D-234 的 bundle 下每輪失敗（只記 warn，該 owner 沒有任何包）。
- 623.9 與 `SrppReportModuleCalculator` 的既有慣例用 `normalize`／`dec()` 轉成字串，實作者容易照做。但本任務的 spec 沒寫，且 623.22 的擷取測試清單也沒要求整包通過 JCS／envelope 驗證。
出處：可查證性（對既有 JCS／envelope 限制的隱含前提）。
修法：623.3(2) 補「buyRate／sellRate／midRate 皆為 canonical decimal 字串（沿 `SrppReportModuleCalculator.dec()`），不得放 BigDecimal 或 double 節點」；623.22 擷取測試加一案：兩個來源經 `withSatelliteBuildSources` 寫入後，`SrppJcs.hash`、`SrppReportEnvelopeValidator.validate` 都通過。

### [Minor] 623.7(4)／623.8(4) 沒有寫明 `pace_ok` 一開始為 false 時不走訪 records，evidence 的 day／week／month 值因此為 0
位置：spec/tasks/t623_srpp_d234_satellite_build_legs.md:49（623.7 第 4 步）、:58（623.8 第 4 步）；SRPP /Users/steven/Project/SRPP/.claude/worktrees/asset-management-logic-review-d1cbbc/scripts/execution_policy.py:695、:820
證據：
- 參考實作兩處都是 `for r in (records if pace_ok else []):`。起始 `pace_ok` 由 `isoDate(start)／isoDate(end)／start <= min(month_start, week_start)／end >= trading_date／records 是陣列` 決定，為 false 時完全不累加。輸出 evidence 的 `day_lots`／`week_lots`／`month_amount_twd`（VT 為 `day_shares`／`week_shares`）因此恆為 0.0。
- 623.7(4) 的寫法是「`pace_ok = …`。逐筆：非物件 → …」，沒有這個前置條件。照字面逐筆累加的 Java，在「selection 起日太晚但 records 內有同代號買進」時，evidence 會是正值而參考實作為 0。這種差異會被 golden 的 evidence 欄位比對抓到，但 623.20 的案例描述只寫「selection 起日太晚；amount null」，沒說該案要帶有效買進 records。
出處：可查證性（對參考實作的逐步移植描述）。
修法：623.7(4)、623.8(4) 各補一句「`pace_ok` 初始為 false 時不走訪 records（evidence 的各累計值為 0）」；623.20 的 PACE_STATE_UNVERIFIED 案例註明 records 內須有有效的同代號買進列，使此規則被斷言。

### [Minor] 626.9 的 BOND 重跑呼叫鏈仍寫 `bond(Frozen, Policy)`，與 t625 625.10 已改的簽章不同
位置：spec/tasks/t626_srpp_order_plan_v2_recompute.md:119；對照 spec/tasks/t625_srpp_d215_fx_preference_on_bond_and_vt_legs.md:88（625.10）
證據：
- t625 625.10：`SrppReportModuleCalculator.bond(...)` 改為接收 `model` 與 `extra`，呼叫點 `out.add(bond(frozen,policy))` 同步修改。t626 的前置任務含 t625，且四支同分支依序實作。
- 626.9 BOND 項仍寫「`SrppReportModuleCalculator.bond(Frozen, Policy)` → `reportBondInput(Frozen, Policy)` → …」，是 t625 之前的簽章。現行程式碼確為 `private static ObjectNode bond(Frozen f, Policy p)`（SrppReportModuleCalculator.java:71）。
- `recalculateModuleForRefresh(String moduleId, Frozen f, Policy p, JsonNode model, JsonNode extra)` 本來就帶 model／extra，所以不會做錯方向，但實作者要自行推斷 BOND 分支該傳哪個簽章，也容易漏掉 D215_FX 需要的 model 與 extra。
出處：內部一致性（t625 與 t626 對同一方法的描述）。
修法：626.9 該行改為「`bond(Frozen, Policy, JsonNode model, JsonNode extra)`（t625 625.10 之後的簽章）→ `reportBondInput(Frozen, Policy)`」，並註明刷新時 model 與 extra 取自 package 重建的 frame。

## 已查無發現
- 第 2 輪 8 項處理逐項對照程式碼，全部屬實，修法落在 spec 上：
  - 收盤後守門（623.13a、623.17、623.22、626.5、t625 625.14）：`SrppReportProducerScheduler.produce`（14:00 後 slot 固定 12:00，`withAfterCloseContext` 寫 publication-context）、`SrppReportEnvelopeValidator.validatePublicationContext`／`requireNonExecutable` 的鍵名檢查（只查 lots／units／limitPrice／amount，與 facts 的 name／value 結構、t625 的 `d215WaitCeiling*` values 不衝突）、BFF `leg()` 對 BLOCKED 腿只要求非空 blockingReasons。`pace()` 取得的 Frozen 確實含 publication-context（`publisher.publish(scope, completed, …)`）。626.5 的時間鏈推導（capturedAt ≥ 14:00、runStartedAt ≤ 13:00 使 now 必晚於 deadline）成立。
  - 跨包預留排除（623.14a、626.11）：`SrppReportReservationCapture.readReportSource` 的 `legIndex=index++` 在 ACTIONABLE 判斷之前，所以 `continue` 不改其他腿的 `reservationId`；`reservationClass` 的策略後備清單確實不含兩個建倉策略；`visitUnlinkedReportReservations` 的 SQL 範圍、`funding()` 的 `reserved.merge(currency, amount+fee)` 與 Postgres 測試名稱都存在。`SrppReportReservationCapture` 不在 `generate-srpp-formula-v3.py` 與 `spec/fixtures/srpp_formula_manifest_v3.json`（grep 為 0）。
  - 626.9 BOND frame 重算：`SrppReportExtraCaptureAdapter.assess(JsonNode, LocalDate, Instant)` 為 public static；refresh 後以合併後的 market body 呼叫即可通過，與 626.14 的三欄位 capture body 是兩個物件（見自我挑戰）。
  - golden root：t626、t623、t624、t625 的驗證區塊都指向 D-234 worktree；623.19 已含三支 golden 重產與 `--check`。三支 generator 與 `render-9090-openapi-docs.rb`、`sync-srpp-swagger-identity.py` 存在且旗標相符。`state-topup` generator 前的 `SRPP_RELEASE_WORKTREE` 與 t622 的既有寫法一致（無害）。
  - emergencyValue：`DecisionInputCaptureAdapter.java:130` 傳 null、`SrppReportFubonOverlay.java:29` 沿用、`DailyDecisionCalculator.java:68` 在 null 時 gap=null、`:69` 的分支條件已核對；626 決定 7、626.5、626.20、情境 22、requirements、design 的前提一致。
  - riskAffected／coverage：v1 outcome 規則（SrppProjectionService:60-79、BFF :212-241）與 626.16 除 requirements 措辭外一致，見 Finding 1。
  - BELOW_MIN 作法：SRPP `global_build_vt` 在 blocks 為空時才依 room 鍵序記碼；min=5.5、日／週上限 10／30、month room 5.8 時 `ceiling=floor(5.8)=5 < 5.5` 走到 `BELOW_MIN_ORDER_SHARES`，作法可行。
  - 群組上限理由：`sharedFunds` 在 `calculateShared` 最後，redecided 的 base 取自 sharedFunds 之前，已含 topup 的群組／股票上限；已知差異與 626.19 待辦已列。
- 參考實作逐步比對：`global_build_009826`／`global_build_vt`（reasons 順序、identity、`usd_rate_ok`、room 鍵序、VT 不加一股的 post_buy_weight、evidence 鍵集）、`global_build_api_inputs`／`global_build_vt_api_inputs`（held 加總、rates 組成、spot 條件）與 623.7–623.9 一致（唯一缺漏見 Finding 4）。`classify_risk_mode`、`risk_score_decision_policy` 四值、`risk_score_category_maxima`（6 類，總和 15）在內嵌 model_inputs.json 存在。SRPP `QUOTE_REFRESH_GAPS`（六碼）、`REFRESH_LOT_CAPS`、`REFRESH_BUILD_MODULES`（含 GLOBAL_EQUITY_PACE）、`bind_api_leg` 的 `quote_refreshable` 規則與 626.5 的已知差異敘述相符。
- 可查證性：t623–t626 引用的既有類別、方法與簽章逐一存在（`UsdFxPurchaseContext.forAsset`／`live`／`priceException`、`SrppReportModuleCalculator.quoteFreshness`（public static）／`normalize`（public）、`DecisionInputCaptureAdapter` 的 private `book`／package-private `normalize`、`DailyDecisionCalculator.cost`（private static :134）／`inflation(Policy, LocalDate)`、`PriceQueryService.LivePrice.sellPrice`、`UsdTwdLiveRateService.LiveRate`、`AssetTransactionRepository.existsByOwnerUserIdAndBrokerFilledNo` 的 native 先例、`PublicTransactionHistoryService.toRecord` 的 amountTwd 規則）。OpenAPI 版本 1.28.4、`/api/public/srpp/order-plan` 路徑、model_inputs 的 `strategic_max_lots_per_day=2`／`emergency_max_lots_per_day=2`／`minimum_fee_twd=20` 皆符。
- 架構鐵則：沒有 Controller 直注入 Repository 的描述；沒有新路由（order-plan 以 query 選版）、沒有新前端頁；v2 IO 只限既有唯讀快取方法；BFF 驗證只放寬 GLOBAL_EQUITY_PACE／SATELLITE_BUILD_D234／VT；新 DTO 與回應為封閉鍵集，沒有 `@Data` 或 setter 描述。
- 資料來源與 schema：`db/schema.sql` 的 `asset_transaction`（shares numeric(15,5)、amount numeric(20,2) NOT NULL、exchange_rate numeric(10,4)）與 623.3 的欄位和 canonical 字串規則相符；spec-check 重跑為 BLOCK 0／CHECK 0，且我的命令沒有改動工作樹（git status 仍只有 design.md、requirements.md 與四個 task 檔）。D-234 活動日期、基準額度、匯率門檻與 SRPP model_inputs 的 VT／009826 區塊逐值相符。
- BFF 與 business validator 的互動：GLOBAL_EQUITY_PACE 模組因 `global-pace` 恆 UNAVAILABLE 而恆為 PARTIAL（`M.done()`：missing 非空），不會觸發 COMPLETE 模組的 inputRefs 全 VERIFIED 與 UNAVAILABLE fact 檢查；加入兩條腿後也不會變成 UNAVAILABLE（`legs` 非空使 `any` 為 true），所以 BFF 的「status 為 UNAVAILABLE／NOT_EVALUATED 的模組不得有 ACTIONABLE 腿」不會誤擋新腿。
- 內部一致性：requirements、design、tasks 三處的收據集合、策略名、案例數（t623 每檔 ≥10、t624 ≥15、t625 17、t626 22 情境）、REQUIREMENT 220–223 與 Task 623–626 編號無撞號；623.13a／623.14a 以插入式編號，沒有改動既有條號。

## 自我挑戰
- 撤下「626.14 的 refresh body 只有 market／symbol／volumeUnit／quote／nav／book 三個資料欄位，`assess` 會因缺 daily／radar 而恆為 UNAVAILABLE」：626.9 寫的是把 frame 內 market body 的 quote／nav／book 換成新讀值後再 `assess`，用的是合併後的 body，不是 626.14 的封存 body，所以 UNAVAILABLE 不成立。兩處對「刷新 body」的用語確有重疊，但上下文可分辨，不列 finding。
- 撤下「0 量建倉腿的收據集合未明定」：623.13 只明定正數腿的收據集合；BFF 對 BLOCKED 腿只要求 blockingReasons 非空與 refs 存在，不論收據多寡都不會 502，沒有誤做的後果。
- 撤下「623.12 PASS 前提在收盤後包中是否降級模組收據」：623.22 的測試寫明「facts 與模組收據等於無 publication-context 時的純計算結果」，語意已被測試鎖定。
- 撤下「626.11 的 `packageReservedFunds` null 視為 0 會放行」：這是 Steven 的定案且與 consumer 宣告值取較大者的設計一致，不是 spec 缺陷。
- Finding 3（mids 型別）有一定機率是過度保守：實作者很可能直接用 `dec()`。保留的理由是兩個既有限制（JCS 拒絕非整數 BigDecimal、envelope 拒絕浮點）一旦踩到，結果是正式環境靜默不出包，而修正只要一句話。
- Finding 4（pace_ok 前置條件）會被 golden 抓到，嚴重度已降為 Minor；保留是因為 623.20 的案例描述沒保證該案帶有效 records，golden 可能抓不到。
- Finding 2 是否會造成 502 取決於實作者怎麼理解 626.5 的括號，所以只列 Minor，不升 Major。626.16 的優先序是明確規則，BFF 也有依據。
- 沒有發現 Critical 或 Major：第 2 輪的三個 Major（收盤後守門、跨包預留、BOND frame 重算）都已被具體條文與測試處理，且我對照程式碼沒有找到新的做錯方向風險。
