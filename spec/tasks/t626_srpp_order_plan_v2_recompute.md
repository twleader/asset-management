# [t626] SRPP order-plan v2：LLM 判斷後由程式補報價重算、扣共用容量並產出最終張數與限價

**對應 Requirements:** Requirement 223（order-plan v2：LLM 判斷後由程式補報價重算、扣共用容量並產出最終張數與限價；仍只為報告建議，不下單）
**前置任務:** t623（GLOBAL_EQUITY_PACE 的 009826／VT 建倉 BUY leg）、t624（FUNDING_CAPACITY 每幣別 `pooledPostBuyHeadroom`）、t625（D215_FX 收據）；四支在同一分支依 t623 → t624 → t625 → t626 順序實作（見 626.21）
**Liquibase changeset:** 無（v2 與 v1 相同，不持久化任何 plan；planId 是內容位址）

## 背景

### 現況（v1，Task 497／599）

`GET /api/public/srpp/order-plan` 由 `backend/src/main/java/com/steven/assets/service/srpp/report/SrppProjectionService.java` 的 `order` → `plan` → `projectLeg` → `quoteProof` 實作，`model_inputs.json` 稱其版本為 `SRPP_ORDER_PLAN_PROJECTION_V1`。它是 immutable FINAL package 的純唯讀投影，類別註解寫明「no writer, vendor, broker or producer dependency」：

- 合格 leg 只有 module `data.legs` 中 `action` 為 BUY／SELL 者（NONE 排除但仍佔 index）；`legId = moduleId + "." + data.legs 零起算 index`。
- `derivedRisk` 固定 `{mode:DEFENSIVE,status:UNAVAILABLE,reasonCodes:[RISK_EVIDENCE_UNAVAILABLE],receiptRefs:[]}`，所以**每一筆 BUY 的 final 只能是 0／"0"**；SELL＋ADOPT 才複製 base；REDUCE 只檢查單位；其他回 `FROZEN_QUOTE_EVIDENCE_UNAVAILABLE`。
- 沒有任何行情讀取、容量計算、遞補或限價重算。

結果：SRPP 兩軌在 LLM 判斷後仍以本機 `scripts/trade_blocker_policy.py`（D-230 補價、`pooled_funding` D-152）與 `scripts/execution_policy.py`（張數、限價）重算。D-234 決定把這段固定公式移入 9090。

### 推翻什麼

v1 的「不接收風險模式、BUY 一律 0、無 IO」限制，**只對新版 v2 推翻**；v1 原樣保留為具名回退，直到 SRPP 另立任務改採 v2。

### Steven 已決定（2026-11-22，最終決定，不是待決問題）

v2 接受 SRPP consumer 本機確定性分類器算出的風險模式（`NORMAL`／`CAUTIOUS`／`DEFENSIVE`）、其收據 sha256 與 `totalScore`／`assessedCount` 輸入，伺服器以機械規則套用到各 leg；v2 移除 v1 固定的 `DEFENSIVE/UNAVAILABLE`。未知模式 400；模式與 `execution_policy.risk_score_decision_policy` 門檻不一致 400。

### Steven 對本任務八項設計問題的最終答覆（2026-11-22，全部定案，無待決問題）

1. `riskMaxCategoryScore`：**必填**，讓伺服器完整重播分類器（626.2、626.3）。
2. `fundingCommitments`：consumer 宣告；伺服器取 `max(宣告值, package reservedFunds（有值時）)`（626.11）。
3. package 階段**只**因共用資金缺口歸 0 的腿，v2 重新判定（626.11 最後一點）。
4. `runStartedAt ≤ slotStart + 60 分鐘`：接受（626.4）。
5. 降量腿的手續費**不按比例**：逐腿重跑該腿原計算器自己的費用規則（台股券商費率公式，含原計算器有套用的最低手續費；VT 每筆固定 US$3，以同一即期賣出匯率換算）（626.11）。
6. `REFRESH_NOT_SELECTED`：接受（626.9）。
7. 00865B 緊急債券補價：改 Java，使緊急債券腿在**唯一**阻擋原因是報價缺口時輸出具體報價缺口原因碼，而非一律 `EMERGENCY_TARGET_CAPACITY_OR_EVIDENCE`；真正的容量／證據原因照舊；加測試（626.20）。SRPP 規則文字保留 00865B（`REFRESH_LOT_CAPS` 的 `EMERGENCY_CATHAY` 不變）。**前提（審查後明定）**：正式包目前沒有已驗證的子帳來源（`DecisionInputCaptureAdapter.java:80` 硬編 `subaccounts=UNAVAILABLE/SUBACCOUNT_UNVERIFIED`，`SrppReportFubonOverlay` 又把 00865B 的 pending 覆寫為 `SUBACCOUNT_UNVERIFIED`），所以在子帳來源被驗證前，00865B 的 STRATEGIC_FUBON 與 EMERGENCY_CATHAY 兩腿恆含 `SUBACCOUNT_UNVERIFIED`，**不是**補價候選；另外，緊急子帳市值 `emergencyValue` 在正式資料上也恆為 null（`DecisionInputCaptureAdapter.java:130` 建 `Input` 時最後一個參數傳 `null`，`SrppReportFubonOverlay.java:29` 沿用 `old.emergencyValue()`，repo 內無其他來源），而 `DailyDecisionCalculator.java:68` 在 `emergencyValue==null` 時 `gap=null`，626.20 唯一分支（`gap != null && gap > 0`）因此也不觸發。所以本項生效需**子帳來源與 `emergencyValue` 兩者皆有已驗證來源**；本項 Java 變更在正式資料上暫不生效，只以合成 verified-subaccount 且 `emergencyValue` 非 null 的 `Input` 測試證明，完成報告須如實註明。本任務不另立子帳來源或 `emergencyValue` 來源任務。
8. 以 9090 自有 `RiskEvidenceEvaluator` 取代 consumer 宣告的風險模式：**不在本任務範圍**（未來另立任務）。

另定案：009826 補價身分以 t623 為準——t623 把 009826 建倉腿放在 `GLOBAL_EQUITY_PACE`（不是 `STRATEGIC_BUY_GATES`），本任務補價的就是這條 t623 腿（626.5、626.9）。

依據（SRPP 正式規則原文摘要，已複寫）：六項風險「任一已評項 3 分、總分 8 分以上或已評少於 4 項進入防禦；5 至 7 分審慎只揭露。**防禦只擋增加股票風險的買進，不延後必要減碼，也不單獨擋 D-130 購債。**」SRPP 參考實作 `risk_evidence_evaluator.classify_risk_mode(scores, evaluated_count, policy)`：

```python
if any(score >= 3 for score in scores) or total >= 8 or evaluated_count < 4: return "DEFENSIVE"
return "CAUTIOUS" if total >= 5 else "NORMAL"
```

現行 bundle 數值：`defensive_category_score=3`、`defensive_total_score=8`、`minimum_evaluated_categories=4`、`cautious_total_score=5`、`rubric_schema=SRPP_RISK_RUBRIC_V2`。實作一律從**已登錄 bundle 的 model_inputs** 讀這四個值及 `risk_score_categories`（類別數）與 `risk_score_category_maxima`，不得寫死。

## 要做什麼

### A. 路由、版本與請求（同一路徑，以 query 選版）

- [ ] 626.1 **不新增路徑。** 仍為唯一 `GET /api/public/srpp/order-plan`（內部 `/internal/public-srpp/order-plan`，`InternalSrppProjectionController.order`）。新增封閉 query key `projectionVersion`：
  - 缺省 → 完全等同現行 v1（parser、回應位元組、schemaVersion `"1"`、所有測試不變）。v1 仍拒絕下列 v2 專用 key（出現即 400），避免半套 v2。
  - `projectionVersion=SRPP_ORDER_PLAN_PROJECTION_V2` → v2。任何其他值 400。
  - 選 query 不選新路徑的理由：路徑 exact allowlist（Nginx、BFF security、route catalog、Tailscale Serve、frontend deny matrix、OpenAPI route-count 合約）全部不必變動；financial route 數、統一 Swagger union 數不變。
- [ ] 626.2 **v2 封閉 query**（每 key 恰一次、UTF-8 percent-decode 恰一次、無 body、前後空白即 400；v1 的 `email`、`consumer`、`tradingDate`、`reportSlot`、`policyBundleSha256`、`swaggerSha256`、`calculationRunId`、`asOf`、`decisions` 規則完全沿用 Task 497／599，包含 decisions CSV 文法：`legId~ADOPT`、`legId~REDUCE~<正數>~<reason>`、`legId~WAIT~<reason>`、`legId~VETO~<reason>`，reason 只限 `EVENT_RISK`、`RISK_MODE`、`MARKET_CONTEXT`、`PRIORITY_ELSEWHERE`、`EVIDENCE_CONFLICT`、`OTHER`，1–25 筆、無重複、依 leg 順序）。v2 另**必填**：

  | key | 型別／文法 | 驗證 |
  |---|---|---|
  | `projectionVersion` | `SRPP_ORDER_PLAN_PROJECTION_V2` | 精確字串 |
  | `riskMode` | `NORMAL`／`CAUTIOUS`／`DEFENSIVE` | 其他（含小寫、中文「正常」）400 |
  | `riskTotalScore` | `0|[1-9][0-9]{0,2}` 整數 | `0 ≤ x ≤ Σ risk_score_category_maxima` |
  | `riskAssessedCount` | `0|[1-9][0-9]?` 整數 | `0 ≤ x ≤ risk_score_categories 數量` |
  | `riskMaxCategoryScore` | 同上整數 | `0 ≤ x ≤ max(risk_score_category_maxima)` 且 `x ≤ riskTotalScore` |
  | `riskReceiptSha256` | `[a-f0-9]{64}` | 只綁定，不解讀 |
  | `runStartedAt` | RFC3339 instant | 見 626.4 |
  | `fundingCommitments` | `TWD~<dec>,USD~<dec>`，兩幣別各恰一次、順序固定 TWD 再 USD；`<dec>` 為 `0` 或 canonical 正 decimal（最多 18 位整數、8 位小數、無尾零） | 見 626.11 |

  `riskMaxCategoryScore` 為必填（Steven 2026-11-22 定案），讓伺服器能完整重現 `classify_risk_mode`（單項 ≥3 即防禦）；沒有「僅兩值」的替代版本。
- [ ] 626.3 **風險模式一致性（400 `INVALID_REQUEST`）。** 以 bundle 門檻 `C=defensive_category_score, D=defensive_total_score, M=minimum_evaluated_categories, K=cautious_total_score` 計算 `expected = (riskMaxCategoryScore ≥ C || riskTotalScore ≥ D || riskAssessedCount < M) ? DEFENSIVE : (riskTotalScore ≥ K ? CAUTIOUS : NORMAL)`；`riskMode != expected` 即 400。bundle 缺任一門檻或型別錯誤 → 409 `POLICY_UNSUPPORTED`（不猜預設值）。邊界（現行數值）：total 7／assessed 4／max 2 → CAUTIOUS；total 8 → DEFENSIVE；assessed 3 → DEFENSIVE；max 3／total 3 → DEFENSIVE；total 4／assessed 6／max 2 → NORMAL；total 5 → CAUTIOUS。
- [ ] 626.4 **時間與 35 分鐘截止。** 驗 `slotStart ≤ runStartedAt ≤ asOf ≤ now`，其中 `slotStart` 為台北 `tradingDate` 的 09:05 或 12:00；`runStartedAt ≤ slotStart + 60 分鐘`（D-162 附錄硬上限，Steven 2026-11-22 定案；超出 400）；`asOf ≥ package inputSnapshot.capturedAt`（v1 原規則）。`deadline = runStartedAt + 35 分鐘`（SRPP：「第35分鐘後不得再查詢或重新取價」，自實際起跑時間起算）。伺服器在 `now > deadline` 時**不做任何行情讀取**：需補價的腿維持 0 並加 `QUERY_DEADLINE_PASSED`；需最終時效檢核但凍結報價已不合格的腿 0 並加 `LIVE_QUOTE_STALE`。任何讀取的 `capturedAt` 必須 `≤ deadline`，否則該筆讀取視為失敗。
- [ ] 626.5 **合格 leg（v2 擴充）。** v1 合格集合（BUY／SELL）之外，加入「**D-230 補價候選**」：`lots` 為 0、`status=BLOCKED`、身分屬下列可補價身分（取自 SRPP `REFRESH_LOT_CAPS`／`REFRESH_BUILD_MODULES`），且 `blockingReasons` 非空並**全部**屬該身分的報價缺口集合：
  - `(BOND_D130_D195, strategy=STRATEGIC_FUBON)`，`action=NONE`（`DailyDecisionCalculator.candidate` 慣例），原因 ⊆ `QUOTE_REFRESH_GAPS = {LIVE_PRICE_UNVERIFIED, LIVE_QUOTE_STALE, BEST_FIVE_UNVERIFIED, FUBON_OFFICIAL_CAPTURE_FAILED, FUBON_SOURCE_NOT_CAPTURED, PREMIUM_OR_PRICE_UNVERIFIED}`，上限 `bond_price_selection_d195.strategic_max_lots_per_day`
  - `(BOND_D130_D195, strategy=EMERGENCY_CATHAY)`（00865B），`action=NONE`，原因 ⊆ `QUOTE_REFRESH_GAPS`，上限 `bond_price_selection_d195.emergency_max_lots_per_day`。626.20 改 Java 後，報價缺口是唯一原因時此腿才會只帶報價缺口碼（現行一律附 `EMERGENCY_TARGET_CAPACITY_OR_EVIDENCE`，永遠不是候選）。
    - **前提（正式資料上目前不成立）**：`DailyDecisionCalculator.calculate` 對 00865B 另加 `SUBACCOUNTS` 收據，且 `POSITION`／`PENDING` 對 00865B 也取子帳證據，三者 block=true；緊急段重用 00865B 全部收據（只排除 `TARGET`、`D-130_CAPACITY`、`D-195_SELECTION`）。在子帳來源（`DecisionInputCaptureAdapter.java:80` 的 `subaccounts`，及 `SrppReportFubonOverlay` 對 00865B pending 的覆寫）被驗證前，00865B 的 STRATEGIC_FUBON 與 EMERGENCY_CATHAY 兩腿 `blockingReasons` 恆含 `SUBACCOUNT_UNVERIFIED`，不是 `QUOTE_REFRESH_GAPS` 的子集，因此**都不是補價候選**；即使子帳日後被驗證，`emergencyValue` 仍恆為 null（`DecisionInputCaptureAdapter.java:130`），緊急腿也不會只帶報價缺口碼，須兩者皆有來源才可能成為候選。現行正式包實際可成為債券補價候選的只有 00719B／00697B 的 STRATEGIC_FUBON。實作仍按本項規則判定（不得對 00865B 特判放行），測試以合成的 verified-subaccount／pending 且 `emergencyValue` 非 null 的 `Input` 建構。
    - 原因集合 ⊆ `QUOTE_REFRESH_GAPS` 的判定照 SRPP 六碼常數；但 `DailyDecisionCalculator` 在債券腿上實際只會產生其中三個報價缺口碼：`LIVE_PRICE_UNVERIFIED`（`QUOTE` 收據）、`BEST_FIVE_UNVERIFIED`（`BOOK` 收據）、`PREMIUM_OR_PRICE_UNVERIFIED`（`quality` → `D-195` 收據）。`LIVE_QUOTE_STALE` 只出自 `SrppReportModuleCalculator.quoteFreshness`（非債券模組）；`FUBON_*` 對 00865B 會被 overlay 覆寫為 `SUBACCOUNT_UNVERIFIED`。
  - `(GLOBAL_EQUITY_PACE, strategy=SATELLITE_BUILD_D143, symbol=009826)`——**t623 產生的 009826 建倉腿**（t623 的 0 量腿 `action=BUY`、`status=BLOCKED`），原因**恰為** `["QUOTE_UNVERIFIED"]`。理由：t623 逐字移植 `global_build_009826`，其唯一的報價缺口碼是 `QUOTE_UNVERIFIED`（報價身分／交易日不符或參考價非正），不使用 D-230 字彙；`PACE_STATE_UNVERIFIED`、`TOTAL_ASSETS_UNVERIFIED`、`HOLDING_UNVERIFIED` 及各額度碼都不是報價缺口，混入即非候選。上限 `floor(min(remainingDayLots, remainingWeekLots))`（t623 package facts，與報價無關）；`ceilingLots` 在報價缺口時必為 0，**不得**拿來當上限，月額度與停止比重由重跑 t623 計算器以新報價決定。
    - **已知差異（與 SRPP 參考實作）**：SRPP `trade_blocker_policy.QUOTE_REFRESH_GAPS` 只含六個 D-230 碼，不含 `QUOTE_UNVERIFIED`；`bind_api_leg` 對 009826 要求 `all(quote_refreshable(z))`，所以 t623 的 `["QUOTE_UNVERIFIED"]` 零腿在 SRPP 判為 ZERO，而非 QUOTE_REFRESH。本項把它列為候選是 Java 規則（D-234 決定的 009826 補價身分），不以 `quote_refreshable` 比對；SRPP 端需另立待辦（見 626.19）。
  
  其他 module（DIVIDEND_TOPUP 依 D-218 明文不可補價、STRATEGIC_BUY_GATES 的 D215／D226、SHORT_TERM_AND_US_TACTICAL、US_TACTICAL、GLOBAL_EQUITY_PACE 的 VT 腿 `SATELLITE_BUILD_D234`）即使原因全是報價缺口也**不是**候選。SRPP `REFRESH_BUILD_MODULES` 另列 `STRATEGIC_BUY_GATES` 的 009826 是舊位置；9090 的 009826 建倉腿只存在於 `GLOBAL_EQUITY_PACE`（t623），所以只有上面這個身分是候選。候選的 `legId` 規則不變；它們的 action 在輸出記為 `BUY`、`baseLots=0`，並帶 `refreshCandidate:true`。LLM 必須對候選明確給 decision（漏列 = `NOT_DECIDED`／`LLM_DECISION_MISSING`，不補價）。混有其他原因者不是候選，維持 v1 排除。

  **收盤後初始化包（審查第 2 輪新增，與 t623 623.13a 一致）**：package `input.sources["publication-context"].mode == "AFTER_CLOSE_INITIALIZATION"` 時，該包每條腿已被 `SrppReportEnvelopeValidator.requireNonExecutable` 保證為 0／非 ACTIONABLE。v2 對此包**沒有**補價候選、**不做** 626.11 的 `redecided` 重判（pre-capacity 重跑可能取回正數原量，必須禁止）、**零行情讀取**；verdict 為 ADOPT／REDUCE 的 BUY 腿 final 為 0 並加 `AFTER_CLOSE_INITIALIZATION_NON_EXECUTABLE`（outcome 依 626.16 判定，為 `COMPLETE`）；verdict 為 WAIT／VETO／NOT_DECIDED 的 BUY 腿維持 626.7 的 0 與各自原因，**另加**同一收盤後碼，outcome 依 626.16 為 `NOT_EVALUATED`；SELL 腿依 626.7 投影（base 已為 0）。626.4 的時間鏈本身也使此包 `now > deadline`（`capturedAt ≥ 14:00`、`runStartedAt ≤ 13:00`），但本規則不依賴時間推導，直接以 publication-context 判定。測試：以 `withAfterCloseContext` 產生的 FINAL package（含一條因 `CURRENT_REPORT_SHARED_FUNDS_EXHAUSTED` 歸 0、pre-capacity 原量為正的腿與 t623 兩條 BLOCKED 腿）送含 ADOPT 的 v2 GET → 所有 BUY final 0、帶上述碼、`redecided:false`、`getLiveBatch` 0 次呼叫；另一案同包中一條 BUY 腿為 WAIT，斷言其 outcome 為 NOT_EVALUATED、帶收盤後碼，且回應 `coverage` 計數與 BFF `validateLegV2` 依 626.16 重算的結果相同。

### B. 處理順序（逐 leg，依 package module 順序、再依 index；順序固定、不得重排）

- [ ] 626.6 **步驟 0：載入。** 沿用 v1 `load(...)`（日曆、owner、policy registry、Swagger、immutable identity 全部同 v1）。另以 `SrppJcs.parseStrict(inputBundleJcs)` 取得 `facts`，用 `ObjectMapper.convertValue(input.path("facts"), DecisionFacts.Input.class)` 重建 `DecisionInputCapturePort.Frozen(facts, input, sourceVector, inputSnapshot.assetSnapshotId, inputSnapshot.assetGeneratedAt)`（與 `SrppReportPublisher.publish` 現行重建方式相同）。`Policy` 由 `SrppReportAuthority`／`SrppPolicyRegistryService` 對同一 `policyBundleSha256` 取得；不一致 409。
- [ ] 626.7 **步驟 1：判斷套用（verdict）。** 每 leg 的「判斷後量」`q`：
  - WAIT／VETO → 0，加 decision reason；**不做任何讀取**。
  - ADOPT → base 量（補價候選為「步驟 3 的補價結果」）。
  - REDUCE → `min(base, amount)`。`amount` 的單位是該 leg 的 base 單位（lots 腿為張、units 腿為股）；非整數 → 0 並加 `REDUCE_AMOUNT_NOT_WHOLE_UNIT`（不 400，保留 v1 文法）；`baseLots` 與 `baseUnits` 同時為 null 或同時非 null → 沿 v1 `REDUCE_BASE_UNIT_UNAVAILABLE`、NOT_EVALUATED。補價候選的 REDUCE 對補價後量取 min。
  - SELL：v2 與 v1 相同只投影 frozen base（ADOPT 複製、REDUCE 取 min、WAIT／VETO 0），**不讀行情、不扣容量、不受風險模式影響**（防禦不延後必要減碼）。
- [ ] 626.8 **步驟 2：風險模式（機械套用）。** 「增加股票風險的買進」= `action` 為 BUY（含補價候選）且**標的不是債券型**；債券型定義逐字沿用 SRPP `trade_blocker_policy.assess` 的工具分類：台股代號以 `B` 結尾（如 00679B、00697B、00719B、00751B、00865B）或 `SGOV`。依 module／strategy 列舉（實作以標的分類判斷，並以下表做一致性斷言；module 與分類衝突 → 502 `UPSTREAM_INVALID`）：

  | 受影響（股票買進） | 不受影響 |
  |---|---|
  | DIVIDEND_TOPUP（四檔高股息 D-198／D-218） | BOND_D130_D195 的 STRATEGIC_FUBON、EMERGENCY_CATHAY（D-130 購債） |
  | GLOBAL_EQUITY_PACE 的 009826、VT 建倉（t623） | SHORT_TERM_AND_US_TACTICAL 的 BOND_SHORT_TERM_D185（債券 ETF） |
  | STRATEGIC_BUY_GATES 的 NAMED_DEEP_DRAWDOWN_D215、NAMED_CASH_BUY_D226 | 所有 SELL（CORE_SELL_GUARD、NAMED_RADAR_ROTATION_D226 賣方、TIMEBOX 等） |
  | SHORT_TERM_AND_US_TACTICAL 的 SHORT_TERM_D173、US_TACTICAL 買進 | |

  每模式效果：
  - `DEFENSIVE`：受影響腿 `q=0`，加 `RISK_MODE_DEFENSIVE`；補價候選若受影響**不讀行情**。不受影響腿不變。
  - `CAUTIOUS`：量不變；受影響且 `q>0` 的腿加揭露碼 `RISK_MODE_CAUTIOUS_DISCLOSED`。
  - `NORMAL`：無作用。

  **`riskAffected` 定義（審查第 2 輪新增）**：每腿輸出布林 `riskAffected`，`true` 當且僅當該腿是上表「受影響（股票買進）」的腿，即 `action=BUY`（含補價候選）且標的不是債券型（本項的工具分類）；與風險模式是哪一種、該腿是否因 DEFENSIVE 或其他原因被歸 0 **無關**（NORMAL 下的股票 BUY 也是 `true`）。SELL 與債券型 BUY 恆為 `false`。不是 boolean → 回應無效。BFF 重驗：SELL ⇒ `false`；代號以 `B` 結尾或 `SGOV` ⇒ `false`；其餘 BUY ⇒ `true`；`riskMode.mode=DEFENSIVE` 且 `riskAffected=true` ⇒ final 為 0 且 `reasonCodes` 含 `RISK_MODE_DEFENSIVE`；`CAUTIOUS` 且 `riskAffected=true` 且 final>0 ⇒ 含 `RISK_MODE_CAUTIOUS_DISCLOSED`；`riskAffected=false` ⇒ 不得含這兩碼。

  以 9090 自有 `RiskEvidenceEvaluator` 取代 consumer 宣告模式不在本任務範圍（Steven 2026-11-22 定案，未來另立任務）。

  輸出 root `riskMode`（取代 v1 `derivedRisk`）：`{mode, status:"CONSUMER_DECLARED", totalScore, assessedCount, maxCategoryScore, receiptSha256, rubricSchema:<bundle rubric_schema>, thresholds:{defensiveCategoryScore, defensiveTotalScore, minimumEvaluatedCategories, cautiousTotalScore}}`。v2 回應**不得**出現 `derivedRisk` 或 `RISK_EVIDENCE_UNAVAILABLE`。伺服器只驗一致性與綁定，不宣稱已驗六項來源；D-216 台海硬閘、事件 WAIT／VETO 仍由 LLM 以 decision 表達。
- [ ] 626.9 **步驟 3：D-230 補價（只對 `q` 可能 >0 的補價候選）。** 對同一 module 的整個標的 universe 一次讀新行情（見 626.13），組成「刷新 frame」：`Frozen` 內 `facts.capturedAt` 與 `input.capturedAt` 改為 `refreshAt`（本次讀取完成時刻，≤ now、≤ deadline、同台北日），該 module universe 每檔的 `quote:<code>`／`book:<code>`（DecisionFacts `Symbol.quote`／`Symbol.book`／premium）與 `market:台股:<code>` 的 `quote`／`nav`／`book` 換成新讀值；**其餘來源（assets、ledger、reservations、radar、daily、dividends、classifications、topup-state、global-pace、transactions）一律沿用 package 凍結值**（D-200）。再以**原計算器**重跑該 module，取同 index、同 symbol、同 strategy 的輸出腿：
  - 重跑腿 ACTIONABLE 且 lots>0 → 補價量 `r = min(重跑 lots, 身分上限 − 同 sleeve 其他腿 final 合計)`（SRPP `sleeve_shares ≤ refresh_lot_cap×1000`），限價／金額／手續費取重跑腿值（D-144 深度限價、D-195 價位選債、D-175 時效全由原計算器決定）。
  - 重跑腿仍為 0：維持 0，reasonCodes 為重跑腿 `blockingReasons`，並加 `D230_FRESH_QUOTE_REQUIRED`。
  - 重跑後同 module 選中另一檔：本腿 0 加 `REFRESH_NOT_SELECTED`（Steven 2026-11-22 定案）；**不遞補**另一檔（v1 零遞補原則不變）。
  - 任何讀取失敗／逾時／身分不符：該腿 0，保留原 package 原因並加 `D230_FRESH_QUOTE_REQUIRED`。
  
  可隔離重跑的 Java 計算器（實作者需把下列 `private static` 改為 package-private，或新增 `static ObjectNode SrppReportModuleCalculator.recalculateModuleForRefresh(String moduleId, Frozen f, Policy p, JsonNode model, JsonNode extra)` 分派器，**只**支援下列兩個 moduleId，其他擲 `IllegalArgumentException("REFRESH_MODULE_UNSUPPORTED")`）：
  - `BOND_D130_D195`：`SrppReportModuleCalculator.bond(Frozen, Policy, JsonNode model, JsonNode extra)`（t625 625.10 之後的簽章；刷新時 `model`、`extra` 取自 package 重建的 frame）→ `reportBondInput(Frozen, Policy)` → 純函式 `DailyDecisionCalculator.calculate(Policy, Input)`（`quoteQualified`、`bookQualified`、`quality`、`capacity`、`limit` 皆讀 `Input.capturedAt` 與 `Symbol.quote/book`）；Fubon 官方報價覆蓋沿用 `SrppReportFubonOverlay` 對刷新 frame 套用。**sourceVector 重算（審查第 2 輪新增，與下方 009826 子項相同）**：對刷新 frame 內每個換入新報價的 `market:台股:<code>`，以 `SrppReportExtraCaptureAdapter.assess(刷新後 body, tradingDate, refreshAt)` 重算 `validation`（及同步的 `coverage`、`sha256`、`revision`、`capturedAt=refreshAt`）並寫入該 frame 的 `sourceVector`；`fx`、`policy` 及其他來源的 sourceVector 項沿用 package 凍結值。理由：t625 在 `bond()` 內加的 `D215_FX` 收據 PASS 前提是 `fx`、`policy`、`market:台股:<symbol>` 全為 VERIFIED；package 時 quote 為 null（Redis 未命中、身分不符）正是 D-230 補價的典型情形，此時 `assess` 回 `UNAVAILABLE`，若不重算，補價後 `D215_FX` 仍為 `SOURCE_NOT_VERIFIED`、重跑腿恆為 0，「報價缺失型」補價形同無效。刷新 body 的 `assess` 結果仍非 VERIFIED 時照常 fail-closed（重跑腿為 0，走「重跑腿仍為 0」分支）。新 quote／book 映射必須重用 `DecisionInputCaptureAdapter` 現有映射（把其 `private static Book book(QuoteDetailDto.Response,String)` 與 line 103 的 `Quote` 建構抽成 **public static** helper，放在 `service.srpp.decision` package，供 `service.srpp.report` package 的 `SrppReportModuleCalculator`／`SrppProjectionService` 呼叫；package-private 無法跨 package。行為不變），不得另寫第二套。
  - `GLOBAL_EQUITY_PACE`（只 009826）：對刷新 frame 呼叫 t623 的同一路徑——`toPythonShape(frozen)`（009826 `quote`／`askLevels` 取自刷新後 `market:台股:009826` 的 `quote`／`book`，五檔須通過 t623 的 `isApprovedCompleteBook` 同等條件，`capturedAt=refreshAt`）→ `SrppSatelliteBuildCalculator.apiInputs009826` → `SrppSatelliteBuildCalculator.build009826`，再以 t623 `pace(...)` 內同一個 009826 腿組裝程式（`SATELLITE_BUILD_D143`、`富邦證券`、`CORE_D143`、`fees=amount×feeRate` 進位到分）產生重跑腿。`satellite-build-fills`、`usd-twd-month-mids`、`assets`、`fx` 一律沿用 package 凍結值。刷新 frame 的 `sourceVector["market:台股:009826"].validation` 以 `SrppReportExtraCaptureAdapter.assess(刷新後 body, tradingDate, refreshAt)` 重算；t623 623.12 的「PASS 收據 inputRefs 全為 VERIFIED」前提照樣套用於重跑腿，不成立時重跑腿為 0（走本項「重跑腿仍為 0」分支）。VT 腿不重跑。分派器的 GLOBAL_EQUITY_PACE 分支只回傳 009826 腿。
  
  **不可**用於補價但可供步驟 4 最終時效檢核引用的既有方法：`SrppReportModuleCalculator.quoteFreshness(JsonNode body, Frozen f, JsonNode model, boolean finalStage)`、`topup(...)`／`SrppReportTopupSizing.calculate(JsonNode, boolean)`（D-218 不可補價）、`strategic(...)`＋`SrppReportMediumDipCalculator.append`／`appendNamedCashPlan`、`SrppReportShortUsCalculator.calculate`。
  
  **package 發布路徑不得改變位元組**：任何抽取／可見度調整都要以既有 `SrppReportModuleCalculatorTest` 與 `generate-srpp-report-golden.py --check` 證明 `calculateShared` 輸出不變。
- [ ] 626.10 **步驟 4：最終報價時效（D-175／D-176，非補價腿）。** 對 `q>0` 的非補價 BUY 腿：先以 package 凍結的 `market:<市場>:<code>` body、frame 時間 = `asOf`，呼叫 `quoteFreshness(body, frame, model, true)`（final stage）。通過 → 不讀行情，限價／金額／手續費沿用 package leg（不重算，等同 SRPP「已合格者不重抓」）。不通過且 `now ≤ deadline` → 讀該檔新報價一次，以刷新 body 重跑 `quoteFreshness(..., true)`；通過則 limit **仍沿用 package 值**（數量與限價不因新價放大），並附 refreshCapture；仍不通過或讀取失敗 → 0 加 `LIVE_QUOTE_STALE`。美股腿（VT、VOO D-215、US_TACTICAL）沿用其原計算器的美股報價時效規則，不套台股 D-175；若原 module 無美股 final 規則，則只做 package 收據透傳，不讀行情。
- [ ] 626.11 **步驟 5：共用容量重算（依序扣減）。** 每幣別起始容量 `H_c = pooledPostBuyHeadroom_c − max(fundingCommitments_c, packageReservedFunds_c)`：
  - `pooledPostBuyHeadroom_c` 取 package `FUNDING_CAPACITY` 的 fact（name=`pooledPostBuyHeadroom`、subject=幣別、quality=EXACT，t624）。fact 缺或 null → 該幣別 `H_c` 未知，所有該幣別 BUY 腿 0：若 fact reason 為 `POOLED_TERM_BELOW_FLOOR`（t624 同時輸出收據 `D152_POOLED:<幣別>` BLOCK、module `missing` 含 `POOLED_TERM_BELOW_FLOOR:<幣別>`）加 `POOLED_TERM_BELOW_FLOOR`，其他（`POOLED_ASSETS_NOT_SAME_ROUND`、`POOLED_DEPOSIT_AMOUNT_UNAVAILABLE:<id>` 或 fact 缺）加 `POOLED_HEADROOM_UNAVAILABLE`。負值 headroom（`POOLED_TOTAL_BELOW_FLOOR`）是已知值，照常計算（任何正成本都放不進）。
  - `packageReservedFunds_c` 取 `reservedFunds` fact（EXACT 非 null 時；null 視為 0，因 consumer 宣告值已涵蓋）。`fundingCommitments` 由 consumer 宣告（Steven 2026-11-22 定案），對應 SRPP `trade_blocker_policy._commitments`（同輪既有掛單／預留含費），兩者取大者，絕不相加以免重複扣。
  - **`reservedFunds` 與 t623 兩條建倉腿的關係（審查第 2 輪新增）**：t623 623.14a 讓 `SrppReportReservationCapture.readReportSource` 排除 `strategy ∈ {SATELLITE_BUILD_D143, SATELLITE_BUILD_D234}`，所以 package 的 `reservedFunds`（及 `funding()`、`topup()` 讀的 report-reservations rows）**不再包含**先前任何包的 009826／VT 建倉腿：D-234／D-143 節奏只由已確認成交消耗，未成交提案不消耗任何額度（與 SRPP 本機計算器從不保留先前 009826／VT 建議一致），因此 `H_TWD` 不會被逐日累積的未成交建倉腿吃光，VT 也不會出現在 USD 池。本包自己的 009826／VT 腿不是 reservation，而是本步驟依序扣減的對象（不重複扣）；已實際成交的 009826／VT 由 t623 `satellite-build-fills` 進入節奏、由 assets 存款餘額反映在 `pooledPostBuyHeadroom`。
  - **D-182 定期定額台幣扣款承諾（Steven 2026-11-22 決定，SRPP `model_inputs.json` → `execution_policy_overrides.global_equity_usd_dca_d182.funding`）**：VT（5 日）、VOO（15、25 日）三筆各 US$350 的美元定期定額，在 owner 全部銀行美元存款合併不足以支應某筆時改由國泰以台幣扣款；其台幣等值（USD 金額 × 同輪已核實銀行即期賣出匯率＋手續費）在扣款日是 TWD 資金承諾，且必須守住 D-152 台幣買後底線。因此扣款日恰為 `tradingDate` 的此類台幣扣款**必須包含在 consumer 宣告的 `fundingCommitments.TWD` 內**（仍以美元扣款者不計入 TWD）；伺服器不自行推導這筆承諾，只依上式取 `max`。OpenAPI 參數說明同步寫明。
  - 依序處理 `q>0` 的 BUY 腿（含補價後的候選）：`cost(n) = amount(n) + fees(n)`，`amount(n) = n × 單位股數 × limitPrice`。
  - **`fees(n)` 不按比例（Steven 2026-11-22 定案）**：對縮量後的 `amount(n)` 重跑**該腿原計算器自己的費用規則**，不得用 `fees_q × n ÷ q`。實作必須把各原計算器的費用運算抽成 static helper 後呼叫（行為與位元組不變），不得另寫第二套。`DailyDecisionCalculator.cost`（`private static`，位於 `service.srpp.decision`）須改為 **public static** helper 留在 decision package；`SrppReportTopupSizing`、`SrppReportMediumDipCalculator`、`SrppReportShortUsCalculator` 與呼叫端同在 `service.srpp.report`，可用 package-private：
    - 台股腿（`BOND_D130_D195` 兩策略、`DIVIDEND_TOPUP`、`STRATEGIC_BUY_GATES` D215／D226、t623 `SATELLITE_BUILD_D143`）：與原計算器相同的台股券商費率公式 `amount(n) × policy.feeRate()`，`setScale(2, CEILING)`（`SrppReportModuleCalculator.bond`／`DailyDecisionCalculator.cost`／`SrppReportTopupSizing`／`SrppReportMediumDipCalculator` 現行寫法）；原計算器有套最低手續費者照套（例如 `SHORT_TERM_AND_US_TACTICAL` 的 `SHORT_TERM_D173` 依 `SrppReportShortUsCalculator` 的 `max(minimum_fee_twd, amount × 費率)`，現值 `minimum_fee_twd=20`）。
    - VT 腿（`SATELLITE_BUILD_D234`）：每筆委託固定 US$3，不隨股數縮放；幣別記 **TWD**（國泰台幣複委託，SRPP `GLOBAL_BUILD_VT` debit currency=TWD），`cost(n) = n × limitPriceUsd × spotSellingRate + 3 × spotSellingRate`，`spotSellingRate` 取 t623 `FX_CONVERSION` 收據同一值（n=q 時恰等於 `twdAmount + twdFee`）。
    - 任何可進入本步驟的 BUY strategy 若找不到對應費用 helper，以單元測試列舉 package 可能出現的全部 BUY strategy 斷言皆已對應（不得執行期猜費率）。
  - 取最大整數 `n ∈ [0,q]` 使 `cost(n) ≤ remaining_c`；VT `n < minOrderShares`（t623 fact，現值 5）→ 0。`n < q` 加 `SHARED_CAPACITY_REDUCED`。`remaining_c -= cost(n)`；輸出 leg `fees = fees(n)`（VT 為 USD `"3"`，n=0 時 null）。
  - 縮量時限價保持 q 的限價（深度遞增下 q 的限價 ≥ n 的限價，仍保守可成交），`finalAmount = n × 單位 × limitPrice`。
  - **配置群組與日／週節奏**：只執行原計算器已發布的上限（同 sleeve final 合計 ≤ 該 sleeve 原計算器日／週上限：債券 sleeve 用 626.5 上限、009826 用 t623 `remainingDayLots`／`remainingWeekLots`、VT 用 `remainingDayShares`／`remainingWeekShares`、DIVIDEND_TOPUP 用 package facts `remainingDailyLots`／`remainingWeeklyLots`）。不新增跨 module 群組上限。超出同樣降量並加 `SHARED_CAPACITY_REDUCED`。
    - **為何不另扣 D-234 §5.3 的「配置群組上限」（審查第 2 輪補理由）**：D-234 批次規格 §5 步驟 3 寫「依順序扣減 pooledPostBuyHeadroom、配置群組上限與日／週節奏」。v2 不重做群組上限，因為 (1) 每條 package 腿的原計算器已套用各自的群組／目標上限（例如 `topup()` 的 `aggregate_hard_cap`、`normal_equity_cap`、`DailyDecisionCalculator` 的債券目標與 D-130 容量），v2 只會判斷後縮量、降量或歸 0，不會放大任何腿，所以不可能因 v2 而超出原計算器已核過的群組上限；(2) 補價重跑的 009826 腿不經 `topup()` 的群組檢查，它的上限來自 t623 計算器本身（`stop_at_weight` 停止比重與日／週／月節奏），重跑時照樣套用；(3) `redecided:true` 腿取回的 base 是原計算器在 `sharedFunds` 之前已套過群組上限的量。跨 module 的配置群組合計上限（例如多條股票買進合計後的股票比重）仍由 SRPP consumer 在採納時作為配置硬閘門。
    - **已知差異**：v2 不實作 D-234 §5.3 的跨 module 配置群組上限扣減；同一 package 內多條股票 BUY 腿各自已過原計算器上限，但合計後的群組比重不在 v2 檢查。列入 626.19 SRPP 待辦：consumer 採納 v2 結果前，以本機群組上限檢查 v2 final 合計，不得以 v2 回應取代該硬閘門。
  - **package 階段被共用資金擋下的腿（Steven 2026-11-22 定案：v2 重新判定）**：`blockingReasons` 恰為 `{OFFICIAL_SHARED_PENDING_OR_RESERVATION_SOURCE_UNAVAILABLE}` 或 `{CURRENT_REPORT_SHARED_FUNDS_EXHAUSTED}`（`SrppReportModuleCalculator.sharedFunds` 產生、已把 lots 歸 0 並清空 limitPrice／amount／fees）者，v2 以凍結輸入重跑「`calculateShared` 中 `sharedFunds(out)` 之前的同一 module 輸出」取回原量與限價作 base，再進本步驟（SRPP 把這類 generic funding gap 視為可由 D-152 合併檢查重新判定）。這類「重判腿」對外 `baseLots`／`baseUnits` 輸出**取回的原量**（不是 package 腿已歸 0 的 lots），並帶 `redecided:true`；其他腿 `redecided:false`。626.12 的 `final ≤ base` 對照的就是這個取回的 base；SRPP 重播（626.19）亦以同一 base 比對。為此 `calculateShared` 必須拆成 `calculateSharedBeforeCapacity`＋`sharedFunds`＋`namedCashRotationFinalGates` 三段，組合後輸出位元組不變。`D215_CURRENT_REPORT_CROSS_PATH_DUPLICATE_BUY` 不可重新判定。
- [ ] 626.12 **步驟 6：不變量（任一違反 → 500 `INTERNAL_ERROR`，不得回 200）。** 每腿 `final ≤ base`，base 即對外輸出的 `baseLots`／`baseUnits`（`redecided:true` 腿為 626.11 取回的原量；補價候選 base=0，例外改為 `final ≤ 重跑 lots ≤ 身分上限`）；SELL final 只能是 base／REDUCE 結果／0；`finalAmount = final × 單位 × finalLimitPrice`；每幣別 `fundingUsed_c = Σ cost`、`fundingHeadroomAfter_c = H_c − fundingUsed_c ≥ 0`；`executable=false`、`tradeAuthorization=false`、`placesOrders=false`、`executionScope=REPORT_RECOMMENDATION_ONLY`。

### C. 行情讀取的 IO 邊界與封存

- [ ] 626.13 **只讀快取、零寫入。** v2 只可用下列既有唯讀方法，全部在既有 `@Transactional(readOnly=true)` 內：
  - `PriceQueryService.getLiveBatch(Set<PriceKey>, Map<PriceKey,List<StockPriceHistory>>)`（Redis 快取讀；history 參數傳 package 凍結 daily 對應列或空 map，以現行簽章為準）
  - `PriceQueryService.getEtfNav(code, market)`（折溢價 `premium_discount` 閘，對 `FUBON_OFFICIAL_CAPTURE_FAILED`／`PREMIUM_OR_PRICE_UNVERIFIED` 必讀）
  - `MarketDataService.getQuoteDetail(code, "台股")`（只回 `approvedQuoteDetailSnapshot` 通過的已持久化 producer 快照，非請求時 vendor 呼叫）
  - `SrppReportFubonCapturePort` 最新已持久化官方擷取（唯讀，對 `FUBON_*` 原因）
  
  **禁止**：`triggerRefresh`、`refreshTradingRadarPrices`、任何 vendor／crawler refresh、券商呼叫、`FubonSrppSourceScheduler`、producer 觸發、DB／Redis 寫入、email、POST。讀取次數上限：每次請求每檔最多 1 次（D-232 的重試由 consumer 以重送整個 GET 表達，伺服器不內部重試），全請求總檔數 ≤ 25。類別註解改為：「v1: no IO; v2: read-only cached market reads at call time, no writer, vendor refresh, broker or producer trigger.」
- [ ] 626.14 **封存刷新報價。** 每次讀取產生 `refreshCapture`：`body` = 與 package `market:<市場>:<code>` 相同欄位子集 `{market, symbol, volumeUnit, quote, nav, book}`（以 public 的 `SrppReportModuleCalculator.normalize` 正規化數字為 canonical decimal 字串；不使用 decision package 的 package-private `DecisionInputCaptureAdapter.normalize`）；`sha256 = SrppJcs.sha256Hex(SrppJcs.canonicalize(body))`；`captureId = "refresh:" + sourceId + ":" + sha256`；`capturedAt = 讀取完成時刻`。root `refreshCaptures[]` 依 `sourceId` 排序列出 `{captureId, sourceId, capturedAt, sha256, body}`（完整 body，供 SRPP 重播）；每腿 `refreshCaptureRefs[]` 列其使用的 `{kind:"REFRESH_QUOTE", captureId, sha256, capturedAt}`。
- [ ] 626.15 **planId 與冪等。** `planId = sha256(JCS({projectionVersion, identity, base, asOf, runStartedAt, normalizedDecisions, riskMode, fundingCommitments, refreshCaptures:[{sourceId, sha256, capturedAt}]}))`。同輸入且快取未變 → 相同 planId 與 contentSha256；快取變動 → 不同 planId（預期行為；consumer 採用其中一份並記錄 planId）。不持久化、無 Liquibase。GET 仍無 writer 依賴；行情讀取失敗只影響該腿，不轉成 5xx。

### D. 回應（schemaVersion "2"）

- [ ] 626.16 root 恰為 `{schemaVersion:"2", projectionVersion:"SRPP_ORDER_PLAN_PROJECTION_V2", planId, identity, base, asOf, runStartedAt, queryDeadline, normalizedDecisions, riskMode, fundingCommitments:{TWD,USD}, funding:{TWD:{startHeadroom, fundingUsed, fundingHeadroomAfter, reasonCodes}, USD:{...同鍵}}（`reasonCodes` 恆存在、為自由字串陣列；已知時 `[]`，未知時三數值為 null 並列具名原因）, refreshCaptures, legs, coverage, executionScope:"REPORT_RECOMMENDATION_ONLY", tradeAuthorization:false, placesOrders:false, receiptRefs, contentSha256}`。每 leg 恰為 `{legId, market, symbol, action, strategy, refreshCandidate, redecided, verdict, riskAffected, baseLots, baseUnits, finalLots, finalUnits, finalLimitPrice, finalAmount, fees, currency, reasonCodes, quoteCapture, refreshCaptureRefs, freshness, settlement:null, executable:false}`；`quoteCapture` 沿 v1 `quoteProof` 規則；`freshness` 為 `{status, stage:"final", ageMinutes, maxAgeMinutes, source:"PACKAGE"|"REFRESH"}` 或 null。數值為 canonical decimal 字串，lots 為整數，units 為整數字串。金額一律不超過 base 計算器上限。
  - **v2 逐腿 outcome 與 `coverage`（審查第 2 輪新增）**：`coverage` 鍵與 v1 相同 `{requestedCount, completeCount, partialCount, unavailableCount, notEvaluatedCount}`，`requestedCount = legs.length`，其餘四個計數之和等於 `requestedCount`，`partialCount` 恆為 0。每腿 outcome 依下列優先序判定（先符合者為準）：
    1. `NOT_EVALUATED`：`verdict ∈ {WAIT, VETO, NOT_DECIDED}`（不做任何計算或讀取；`NOT_DECIDED` 必含 `LLM_DECISION_MISSING`），或 `reasonCodes` 含 `REDUCE_BASE_UNIT_UNAVAILABLE`。
    2. `UNAVAILABLE`：final 為 0 且 `reasonCodes` 含任一證據缺口碼 `{D230_FRESH_QUOTE_REQUIRED, QUERY_DEADLINE_PASSED, LIVE_QUOTE_STALE, POOLED_HEADROOM_UNAVAILABLE}`（讀取失敗、逾時、時效不合格或容量未知；final 因缺證而為 0）。
    3. `COMPLETE`：其餘所有腿，即 final 已由機械規則完整決定，含 final>0、`RISK_MODE_DEFENSIVE` 歸 0、`POOLED_TERM_BELOW_FLOOR`（已驗證阻擋，不是缺證）、`SHARED_CAPACITY_REDUCED` 降量或歸 0、`REDUCE_AMOUNT_NOT_WHOLE_UNIT`、`REFRESH_NOT_SELECTED`、`AFTER_CLOSE_INITIALIZATION_NON_EXECUTABLE`、SELL 投影。
    伺服器依此計數；BFF `PublicSrppProjectionService` 的 v2 驗證以同一規則從每腿 `verdict`、`reasonCodes`、final 重算 outcome（v2 專用的 `validateLegV2`，不改 v1 `validateLeg`），計數不符、四項和不等於 `requestedCount`、`partialCount≠0` → 502 零寫入。測試：BFF 對三種 outcome 各一腿的正例；hostile upstream 把 `UNAVAILABLE` 腿計入 `completeCount`、或 `requestedCount` 與 legs 數不符 → 502；backend 斷言 22 個 golden 情境的 coverage 計數。
- [ ] 626.17 v2 leg 與 `funding` 的 `reasonCodes` 維持 v1 `SrppOrderPlanLeg.reasonCodes` 的**自由字串**陣列（`items: type: string`、uniqueItems），**不**加 enum，因為還要原樣透傳 package／重跑腿的 `blockingReasons`（例如 `LIVE_PRICE_UNVERIFIED`）。本任務新增的碼只在 OpenAPI description 文字列舉：`RISK_MODE_DEFENSIVE`、`RISK_MODE_CAUTIOUS_DISCLOSED`、`D230_FRESH_QUOTE_REQUIRED`、`REFRESH_NOT_SELECTED`、`QUERY_DEADLINE_PASSED`、`LIVE_QUOTE_STALE`、`SHARED_CAPACITY_REDUCED`、`POOLED_HEADROOM_UNAVAILABLE`、`POOLED_TERM_BELOW_FLOOR`、`REDUCE_AMOUNT_NOT_WHOLE_UNIT`、`AFTER_CLOSE_INITIALIZATION_NON_EXECUTABLE`（626.5 收盤後初始化包；與 t623 623.13a 同碼）。

### E. 合約與發布面

- [ ] 626.18 OpenAPI `docs/openapi/docker-external-api.yaml` 的 `/api/public/srpp/order-plan`：新增 8 個 v2 參數（`required:false`，description 寫明 v2 必填；`fundingCommitments` 說明寫明 TWD 須含扣款日為交易日、已改台幣扣款的 D-182 定期定額台幣等值）、回應 schema 改 `oneOf: [SrppOrderPlanResponse (v1), SrppOrderPlanResponseV2]`、description 不再寫「固定 DEFENSIVE、不做行情刷新」而改為兩版各自邊界；金融 OpenAPI 版本 minor bump（現 1.28.4 → 1.29.0），route 數不變。同步 `backend/src/main/resources/srpp/9090-api-swagger.md` 與 `docs/openapi/9090-api-swagger.md`（renderer `ruby scripts/render-9090-openapi-docs.rb`）、`PublishedSwaggerIdentity`、BFF `SrppProjectionQuery` 與 `PublicSrppProjectionService` 回應驗證（v2 root／leg 鍵閉合、false flags、不變量重驗、未知 → 502 零寫入）。
- [ ] 626.19 **SRPP consumer 影響（本任務不改 SRPP repo，只在完成報告列出）**：`model_inputs.json` 的 `published_read_candidates["/api/public/srpp/order-plan"].projection_version` 需另立 SRPP 任務改 V2；`scripts/verify_srpp_projection_api.py` 現 `check_order_plan` 斷言 `schemaVersion=="1"` 與固定 DEFENSIVE，必須新增 v2 分支（root／leg 閉合、riskMode 與本地 classifier 收據一致、refreshCaptures sha 重算、final ≤ base、容量算術重播），`--prepare-decisions` 需加 `riskMode` 收據、`runStartedAt`、`fundingCommitments` 並把補價候選納入合格 leg；容量算術重播對 `redecided:true` 腿以回應的 `baseLots`／`baseUnits`（取回的原量）為 base。另一項 SRPP 待辦：`trade_blocker_policy.QUOTE_REFRESH_GAPS` 或 `bind_api_leg` 須對 `GLOBAL_EQUITY_PACE` 的 009826 建倉腿納入 `QUOTE_UNVERIFIED`（626.5 已知差異），否則 SRPP 會把 v2 補價後的 009826 腿判為與本機 ZERO 不一致。第三項 SRPP 待辦：v2 不扣 D-234 §5.3 的跨 module 配置群組上限（626.11 已知差異），SRPP consumer 採納 v2 final 前仍須以本機配置群組硬閘門檢查合計。v1 呼叫在 SRPP 改版前完全不受影響。

### F. 00865B 緊急債券報價缺口原因碼（Steven 2026-11-22 決定 7）

- [ ] 626.20 改 `backend/src/main/java/com/steven/assets/service/srpp/decision/DailyDecisionCalculator.java` 的緊急債券段（`EMERGENCY_CAPACITY` 收據與 `eb` 原因集合）。現況：只要 `elots == 0` 就加 `EMERGENCY_TARGET_CAPACITY_OR_EVIDENCE`，所以即使唯一原因是報價缺口，該腿原因也永遠混有非報價碼、永遠不是 626.5 候選。改為：
  - **前提（同 626.5）**：在子帳來源（`DecisionInputCaptureAdapter.java:80`）被驗證前，正式包的 00865B 兩腿恆含 `SUBACCOUNT_UNVERIFIED`（`POSITION`／`PENDING`／`SUBACCOUNTS` 收據），`eb` 永遠混有非報價碼，所以下方「報價缺口為唯一原因」分支在正式資料上不會觸發、00865B 不是補價候選。此外 `emergencyValue` 在正式資料上恆為 null（`DecisionInputCaptureAdapter.java:130` 傳 `null`、`SrppReportFubonOverlay.java:29` 沿用），`DailyDecisionCalculator.java:68` 令 `gap=null`，下方分支條件 `gap != null && gap > 0` 也不成立；本分支要在正式資料上觸發，**子帳來源與 `emergencyValue` 兩者都必須有已驗證來源**。本項仍照做（決定 7），只以合成的 verified-subaccount `Input`（`subaccounts`、00865B `position`／`pending` 皆 PASS，且 `emergencyValue` 為非 null 並小於緊急目標使 `gap > 0`）測試證明；完成報告必須註明「正式資料上尚未生效，待子帳來源與 `emergencyValue` 來源驗證」。本任務不另立子帳來源或 `emergencyValue` 來源任務。
  - 令 `quoteGaps = QUOTE_REFRESH_GAPS`（同 626.5 六碼，於 Java 定義一個共用常數，626.5 與本項共用）。`DailyDecisionCalculator` 本身只會產生其中三碼：`LIVE_PRICE_UNVERIFIED`（`QUOTE` 收據）、`BEST_FIVE_UNVERIFIED`（`BOOK` 收據）、`PREMIUM_OR_PRICE_UNVERIFIED`（`quality` 失敗經 `D-195` 收據進入原因集合）；`LIVE_QUOTE_STALE` 不由本類別產生。
  - **唯一分支（原「情況 A」）**：重用的共享硬閘門已把原因放入 `eb`，且 `eb` 非空並全部 ⊆ `quoteGaps`（實際即上述三碼的非空子集），同時緊急目標缺口 `gap != null && gap > 0` → `EMERGENCY_CAPACITY` 收據 `status=UNAVAILABLE`、`reason` = `eb.first()`（sorted set 第一個），**不**把 `EMERGENCY_TARGET_CAPACITY_OR_EVIDENCE` 加入 `eb`。
  - 原草案的「情況 B」（`eb` 為空但 `eq` 因報價不合格）**刪除**：`D-195` 收據在 `!eq.suitable()` 時為 BLOCK、reason=`eq.reason()`，且不在緊急段的排除集合，必進 `eb`；因此 `eb` 為空時 `eq` 必合格，`PREMIUM_OR_PRICE_UNVERIFIED` 實際落在上一分支。
  - **其他所有 `elots == 0` 情況**（`gap` 為 null 或 ≤0、`eb` 含任何非報價碼、`eb` 為空但 `capacity(...)` 為 0）→ 維持現行 `BLOCK`／`EMERGENCY_TARGET_CAPACITY_OR_EVIDENCE`，真正的容量／證據原因不得被報價碼取代。
  - `elots > 0` 路徑、策略腿、`DailyDecisionCalculator.VERSION`（`SRPP_D130_D195_V1`）不變。此變更也會讓 `/api/srpp/daily-decision` 在上述分支輸出具體報價碼（同樣須先有已驗證子帳）；這是預期行為，在完成報告列出。t625 的 D-215 後置閘門不受影響。
  - 測試（加在既有 `DailyDecisionCalculatorTest`，全部以合成 verified-subaccount 且 `emergencyValue` 非 null（`gap > 0`）的 `Input` 建構）：`LIVE_PRICE_UNVERIFIED` 為唯一原因 → 腿 reasons 恰為 `[LIVE_PRICE_UNVERIFIED]`、`EMERGENCY_CAPACITY` 收據 UNAVAILABLE；`BEST_FIVE_UNVERIFIED` 為唯一原因同理；`PREMIUM_OR_PRICE_UNVERIFIED` 為唯一原因（quote 價合格但 premium 缺）同理；`{LIVE_PRICE_UNVERIFIED, PREMIUM_OR_PRICE_UNVERIFIED}` 並存 → reasons 恰為兩碼；報價缺口＋非報價碼並存仍含 `EMERGENCY_TARGET_CAPACITY_OR_EVIDENCE`；目標已滿仍為 `EMERGENCY_TARGET_CAPACITY_OR_EVIDENCE`；報價合格但容量 0 仍為原碼；`elots>0` 輸出不變；另以**現行正式 adapter 形狀**（`subaccounts=UNAVAILABLE`、`emergencyValue=null`）證明 00865B 兩腿仍含 `SUBACCOUNT_UNVERIFIED` 而非候選；再以 verified-subaccount 但 `emergencyValue=null` 的 `Input` 證明唯一分支不觸發（`gap=null`，維持 `EMERGENCY_TARGET_CAPACITY_OR_EVIDENCE`）。並在 `SrppOrderPlanServiceTest` 以合成 verified-subaccount package 證明唯一原因為報價缺口的 00865B 緊急腿成為補價候選。
  - SRPP 規則文字保留 00865B：SRPP `REFRESH_LOT_CAPS` 的 `(BOND_D130_D195, EMERGENCY_CATHAY)` 不變，本任務不改 SRPP repo。
- [ ] 626.21 **公式 V3 manifest 與 golden 重產順序（定案）**：t623／t624／t625／t626 都會改 `SrppReportModuleCalculator.java` 位元組（本任務拆 `calculateShared`、新增 `recalculateModuleForRefresh`），四支任務在**同一分支**依 t623 → t624 → t625 → t626 順序實作；本任務是最後一支，結束時以同一個 SRPP root（`/Users/steven/Project/SRPP/.claude/worktrees/asset-management-logic-review-d1cbbc`；SRPP D-234 合併到 main 後改用 `/Users/steven/Project/SRPP`）執行 `python3 scripts/generate-srpp-formula-v3.py --srpp-root <該 root>` 重產 fixture、`SrppFormulaCatalog` 生成區塊與 BFF mirror，並重產本任務 golden，皆以 `--check` 證明；不得沿用 t625 的 manifest digest。

## 驗證

### Golden／等價（必做，22 情境）

新增 `scripts/generate-srpp-order-plan-v2-golden.py`（同 `generate-srpp-report-golden.py` 機制：`--srpp-root` 絕對路徑（D-234 SRPP root，見 626.21）、`--check` 不寫檔、只呼叫 SRPP **原始**純函式，不寫第二套解釋），輸出 `backend/src/test/resources/srpp/order-plan-v2-golden.json`；在既有 `SrppOrderPlanServiceTest` 新增 v2 golden 區段逐欄比對（Decimal compareTo；整數張／股完全相等）。參考函式：`trade_blocker_policy.quote_refreshable`、`refresh_gates`、`quote_refresh_passed`、`refresh_lot_cap`、`candidate_debit`、`pooled_funding`、`_commitments`；`execution_policy.quote_freshness`、`bond_price_selection`、`global_build_009826`、`global_build_vt`；`risk_evidence_evaluator.classify_risk_mode`。

| # | 情境 | 期望 |
|---|---|---|
| 1 | 策略債券零張腿，原因 `{LIVE_PRICE_UNVERIFIED, PREMIUM_OR_PRICE_UNVERIFIED}`，新報價合格、D-195 合適 | `quote_refreshable=True`；v2 final = `bond_price_selection` 張數且 ≤ `strategic_max_lots_per_day`；限價同 Java `DailyDecisionCalculator.limit` |
| 2 | 同 1 但混有 `TARGET_FULL_OR_UNVERIFIED` | 非候選；v1 排除、v2 仍排除 |
| 3 | 同 1 但新報價仍過期 | 0＋`D230_FRESH_QUOTE_REQUIRED`（對應 `quote_refresh_passed=False`） |
| 4 | 同 1 且 package 已選另一檔 ACTIONABLE 2 張 | sleeve 合計 ≤ 上限（SRPP `sleeve_shares` 斷言）；重跑選另一檔 → `REFRESH_NOT_SELECTED` |
| 5 | t623 `GLOBAL_EQUITY_PACE` 009826 零張腿、reasons 恰 `["QUOTE_UNVERIFIED"]`，新報價合格、`global_build_009826` 上限 3 張 | 候選（**Java 規則**，不以 `quote_refreshable` 比對；SRPP `QUOTE_REFRESH_GAPS` 不含 `QUOTE_UNVERIFIED`，為已知差異，golden 以「該零腿 row（reasons 恰為 `["QUOTE_UNVERIFIED"]`）的 `quote_refreshable(row)` 為 False」斷言此差異）；final = min(重跑張數, floor(min(remainingDayLots, remainingWeekLots)))，重跑張數以 `global_build_009826` 對新報價比對；reasons 另含 `PACE_STATE_UNVERIFIED` 時非候選 |
| 6 | DIVIDEND_TOPUP 零張腿、原因 `LIVE_QUOTE_STALE` | 非候選（D-218），0 |
| 7 | 風險 total 8／assessed 6／max 2＋`riskMode=DEFENSIVE` | 股票 BUY 0＋`RISK_MODE_DEFENSIVE`；債券 BUY、SELL 不變 |
| 8 | 風險 total 8＋`riskMode=CAUTIOUS` | 400；`classify_risk_mode` 為 DEFENSIVE |
| 9 | total 5／assessed 4／max 2＋CAUTIOUS | 量不變，`RISK_MODE_CAUTIOUS_DISCLOSED` |
| 10 | assessed 3＋NORMAL | 400 |
| 11 | 兩筆 TWD BUY 合計 ≤ headroom | 全量；`pooled_funding(cash_required=Σ+commitments).status=PASS`，`fundingHeadroomAfter = post_total − max(total_floor, term_floor)` 同值 |
| 12 | 同 11 但第二筆超出 | 第一筆全量、第二筆降到最大可容整張＋`SHARED_CAPACITY_REDUCED`，fees 為對縮量後金額重跑 `amount×feeRate` 進位到分（**非**按比例）；SRPP 參考以縮量後合計呼叫 `pooled_funding` 必為 PASS、以原合計必為 BLOCKED（**已知差異**：參考實作只判全有／全無，降量是 D-234 §5 新行為，golden 以此兩條斷言表達） |
| 13 | VT 降量後 4 股 | 0（`min_shares_per_order=5`），扣 TWD |
| 19 | 縮量的 `SHORT_TERM_D173` 腿，縮後 `amount×費率` < `minimum_fee_twd`(20) | `fees(n)=20`（原計算器最低手續費），不是按比例的較小值；成本以此判斷可容張數 |
| 20 | VT 腿 q=10 股、TWD 剩餘只容 7 股＋固定費 | n=7；`cost = 7×limitUsd×spot + 3×spot`；leg fees 仍為 USD `"3"`；n=q 時 `cost == twdAmount + twdFee`（t623 FX_CONVERSION） |
| 21 | 5 日 VT 定期定額已改台幣扣款，consumer `fundingCommitments.TWD` 含其台幣等值 | `H_TWD` 扣掉該承諾（與 package `reservedFunds` 取大者）；USD 承諾不含此筆 |
| 22 | **合成 verified-subaccount `Input`**（`subaccounts`、00865B `position`／`pending` 皆 PASS，`emergencyValue` 非 null 且 `gap > 0`）下，00865B 緊急腿唯一原因 `LIVE_PRICE_UNVERIFIED`（626.20）、新報價合格 | 候選；final ≤ `emergency_max_lots_per_day`；另一案原因混 `EMERGENCY_TARGET_CAPACITY_OR_EVIDENCE` → 非候選；再一案以現行正式 adapter 形狀（`SUBACCOUNT_UNVERIFIED`）→ 非候選 |
| 14 | `term < termFloor` | 該幣別全部 BUY 0＋`POOLED_TERM_BELOW_FLOOR`（`pooled_funding` BLOCKED） |
| 15 | package 報價於 asOf 已超過 D-175 final 10 分鐘、新報價合格 | final 不變、limit 沿用 package；`quote_freshness(stage='final')` 通過 |
| 16 | `now > runStartedAt+35m` | 零讀取（mock 驗證 0 次呼叫）；候選 `QUERY_DEADLINE_PASSED`、過期腿 `LIVE_QUOTE_STALE` |
| 17 | REDUCE amount `1.5`（lots 腿） | 0＋`REDUCE_AMOUNT_NOT_WHOLE_UNIT` |
| 18 | package 腿因 `OFFICIAL_SHARED_PENDING_OR_RESERVATION_SOURCE_UNAVAILABLE` 歸 0 | 以 pre-capacity 重跑取回原量，再扣 pooled headroom；回應 `baseLots` = 取回的原量、`redecided:true`、`final ≤ baseLots` |

另有單元測試：v1 位元組不變（既有 `SrppOrderPlanServiceTest` 全綠＋v1 拒絕 v2 key）、v2 query 每個 key 的正反例、planId 含 refresh sha（改一個 quote 欄位 planId 必變）、IO 邊界（Mockito `verifyNoInteractions` 於 refresh／broker／writer bean；`getLiveBatch` 次數 ≤ 檔數）、`SrppOrderPlanProjectionServiceProxyTest` 仍證明 CGLIB proxy、BFF hostile upstream（v2 多鍵、final > base、flags 非 false → 502；債券 BUY 或 SELL 標 `riskAffected:true`、股票 BUY 標 `false`、DEFENSIVE 下 `riskAffected:true` 腿 final>0 → 502）、`riskAffected` 正例（NORMAL 下股票 BUY 為 `true`、DEFENSIVE 歸 0 的股票 BUY 仍為 `true`、00719B BUY 與所有 SELL 為 `false`）、626.16 coverage 規則（見該項）、626.9 BOND 補價案「package 時 `market:台股:<code>` 因 quote 為 null 為 UNAVAILABLE、刷新後 `assess` 為 VERIFIED → 重跑腿 `D215_FX` PASS（t625 PASS 前提成立）並得正數補價量；刷新後仍 UNAVAILABLE → 0＋`D230_FRESH_QUOTE_REQUIRED`」、626.5 收盤後初始化包案。

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest='*SrppReport*,*OrderPlan*,*SymbolRuleFacts*,SrppReportModuleCalculatorTest,DailyDecisionCalculatorTest' test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml -Dtest='*SrppReport*,*OrderPlan*,*SymbolRuleFacts*,SrppProjectionQueryTest' test
python3 scripts/generate-srpp-report-golden.py --srpp-root /Users/steven/Project/SRPP/.claude/worktrees/asset-management-logic-review-d1cbbc --check   # golden 綁定 bundle；SRPP D-234 合併到 main 後改用 /Users/steven/Project/SRPP（見 626.21、t623 623.19）
python3 scripts/generate-srpp-order-plan-v2-golden.py --srpp-root /Users/steven/Project/SRPP/.claude/worktrees/asset-management-logic-review-d1cbbc --check
python3 scripts/generate-srpp-formula-v3.py --srpp-root /Users/steven/Project/SRPP/.claude/worktrees/asset-management-logic-review-d1cbbc --check
ruby scripts/tests/docker-external-api-openapi-test.rb
ruby scripts/render-9090-openapi-docs.rb --check
bash scripts/tests/configure-tailscale-api-gateway-test.sh
git diff --check
# 下行另需 EMBEDDED_MODEL 與 SRPP 正本位元組相同，只能在 623.19 的 Swagger identity sync 之後通過；實作期間不列為必過項
python3 scripts/check-srpp-report-authority.py --srpp-root /Users/steven/Project/SRPP/.claude/worktrees/asset-management-logic-review-d1cbbc --check
```

合併後依 run-stack 從乾淨 main 重建 business-services、bff、api-gateway；healthy 後於台股交易日 `slot ≤ now ≤ slot+35m` 以 127.0.0.1:9090 對真實 FINAL package 送一次 v2 GET（全部 WAIT 決策＝零讀取驗證）與一次含 ADOPT 的 GET，核對 planId、refreshCaptures sha、false flags；其他時段只記 `NOT_YET_EXECUTED`。不得為驗收觸發 producer、vendor refresh、券商、POST 或寄信。

## 完成報告

（實作者回填：實際改動檔案、抽出的 helper 與 `calculateShared` 位元組不變證據、golden 22 情境輸出、OpenAPI 版本與 Swagger hash、BFF 驗證、部署與 9090 讀回；列出 SRPP 端待辦（626.19）。必須註明 626.20 的 00865B 報價缺口碼變更「正式資料上尚未生效（子帳來源未驗證，00865B 兩腿恆含 `SUBACCOUNT_UNVERIFIED`；`emergencyValue` 亦恆為 null，`gap=null`）」，只有合成 verified-subaccount 且 `emergencyValue` 非 null 的測試證據。不得把 fixture、休市或拒絕探測寫成真交易日成功。）
