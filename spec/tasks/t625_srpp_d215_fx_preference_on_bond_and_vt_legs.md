# [t625] D-215 美元匯率偏好接到 D-130／00865B 債券腿與 VT 建倉腿

**對應 Requirements:** Requirement 222（D-215 美元匯率偏好接到 D-130 策略債券腿、國泰緊急 00865B 腿及 VT 建倉腿：匯率偏高且無自身跌深例外時該腿等待、證據不足時具名缺證，並逐腿揭露匯率與例外事實）
**前置任務:** t623、t624（四支 D-234 任務在同一分支依 t623 → t624 → t625 → t626 順序實作，見 625.20；VT 腿部分依賴 t623 的腿，債券腿部分的計算本身不依賴 t623）
**Liquibase changeset:** 無

## 背景

SRPP 政策 `execution_policy.usd_asset_fx_preference_d215`（D-215，政策 2.75.0 起嵌入 `backend/src/main/resources/srpp/decision/model_inputs.json`）規定：

- `purchase_scope`：所有新增美元計價 BUY／ADD（VOO／VT／SGOV）都要評估；另外「以台幣交易、來源已核實為 BOND_ETF」的買進也算美元資產，**明確包含 D-130 策略債券與國泰緊急 00865B**。台幣股票 ETF（0050／00881 等）不因美元成分納入。
- `d130_precedence`：D-215 明確優先於舊 D-130「其他條件不能把合格一張減成零」的限制，但只限這項匯率偏好。匯率偏高、而該債券 ETF 沒有已核實的「>10% 跌深例外」時，原本合格的策略或緊急債券買進可以 `WAIT_PREFERRED`。額度、目標、資金、來源及其他原始限制都不變。
- SRPP 最終報告檢核（`report_evidence_guard.py:usd_asset_fx_issue`）：`WAIT_PREFERRED` 及適用但來源 UNKNOWN 的腿，不得同時呈現正數買進；API 正數只是上限。

asset-management 已有這條公式：`backend/src/main/java/com/steven/assets/service/UsdFxPurchaseContext.java`。目前只接到兩處：交易雷達，以及 `SrppReportMediumDipCalculator` 的 D-222 選檔（只 VOO；`SrppReportExtraCaptureAdapter` 只對 VOO 附 `usdFxPurchase`）。

**現在的錯誤行為**：`BOND_D130_D195` 的四條腿（00865B／00719B／00697B 為 `STRATEGIC_FUBON`，00865B 另有 `EMERGENCY_CATHAY`）只檢查 `FX`（來源）與 `FX_CAP`（D-130 的 USD/TWD > 33 停買）。所以 USD/TWD 落在 32.8 < S ≤ 33，或兩年區間位置 ≥ 0.90 時，仍會輸出 `ACTIONABLE_FOR_REPORT` 正數張數，SRPP 只能在本機重算後自行降為等待。t623 新增的 VT 建倉腿也一樣沒有這項評估。

**正確行為**：每條債券腿及 VT 腿都以同一個 `UsdFxPurchaseContext` 評估。

| 評估結果 | 腿的處理 |
| --- | --- |
| HIGH 且沒有核實例外 | 腿為 BLOCKED，具名 `D215_FX_WAIT_PREFERRED`；原容量上限保留在收據 |
| UNKNOWN 且沒有核實例外 | 腿為 BLOCKED，具名 `D215_FX_EVIDENCE_UNKNOWN` |
| NORMAL，或核實例外 | 腿繼續由其他原閘門決定 |

結果必須與 SRPP 參考實作 `scripts/usd_asset_fx_preference.py` 的 `evaluate` 逐欄一致（D-234 批次規格 §0 等價定義、§4）。

**推翻的做法**：D-234 批次規格 §4 原寫「WAIT_PREFERRED 時 leg 量不改（仍為容量上限），但 leg 狀態不得是 ACTIONABLE」。這與現行 wire 契約衝突。三處都寫明非 ACTIONABLE 腿的 lots／units 只能是 0 或 null，limitPrice／amount 必須是 null：

- 後端 `SrppReportEnvelopeValidator.walkLegs`
- BFF `SrppReportResponseValidator`
- SRPP `verify_srpp_report_calculations.py:leg_check`（`BLOCKED_LEG_HAS_NUMBERS`）

本任務**不新增 leg status enum**，改為：腿 BLOCKED、wire 數量 0、價格與金額 null，原容量上限寫入 `D215_FX` 收據的 `d215WaitCeilingLots`／`d215WaitCeilingUnits`／`d215WaitCeilingAmount`。這樣與 SRPP guard「WAIT 不得呈現正數買進」一致，也不必改四處的 status enum。

## 要做什麼

### A. 政策綁定與等價修正（UsdFxPurchaseContext 與參考實作的差異；全部 must-fix）

- [ ] 625.1 新增純 helper `backend/src/main/java/com/steven/assets/service/srpp/report/SrppReportD215FxPreference.java`。不做 Spring、DB 或 vendor I/O，輸入只有凍結 JSON、Policy 及 `UsdFxPurchaseContext`。
  - 先核對 `model.execution_policy.usd_asset_fx_preference_d215` 的精確值：
    - `decision="D-215"`、`enabled=true`、`window_calendar_years=2`
    - `range_position_threshold` 以 Decimal 比較等於 `0.9`；`absolute_rate_strictly_above` 等於 `32.8`
    - `stock_exception_drawdown_fraction` 等於 `0.3`；`etf_exception_drawdown_fraction` 等於 `0.1`；`etf_exception_strictly_greater=true`
    - `twd_traded_bond_etfs_count_as_usd_assets=true`；`rate_basis="BANK_SPOT_SELLING"`
  - 任一不符：每條在範圍內的腿的 `D215_FX` 收據為 `UNAVAILABLE`／`D215_FX_POLICY_UNAVAILABLE`，腿 BLOCKED。
  - 理由：Java 把 32.8／90／70／90 寫死為常數，參考實作則從政策讀值。綁定可以讓政策數值一改就 fail-closed，不會靜默分歧。
  - 舊 bundle 完全沒有這個鍵時（D-215 之前），不輸出 `D215_FX` 收據，維持原輸出 byte-identical。
- [ ] 625.2 只接受 LIVE。報告路徑只接受 `data.quality()=="LIVE"`。`HISTORICAL_PROXY` 及其他品質一律視為 UNKNOWN，並且不能支持例外。
  - 理由：參考實作要求 `fx_quote.quote_status=="LIVE"`；Java 的 `preference()`／`priceException()` 也接受 `HISTORICAL_PROXY`，這是 must-fix 差異。
  - 只在本 helper 內收緊，不修改 `UsdFxPurchaseContext` 對交易雷達／回測的既有行為。
  - 另外，`DecisionInputCaptureAdapter.fxQualified` 接受 `INDICATIVE`，所以 `FX` 收據可能 PASS，但 `UsdFxPurchaseContext.live` 會拒絕 INDICATIVE。這種情況必須是 D215_FX UNKNOWN，不能沿用 `FX` PASS。
- [ ] 625.3 台幣未列名標的 fail-closed。腿的代號若不在 `verified_instruments` 中（`PolicyInstruments` 查無 `台股:<code>`），報告路徑回 UNKNOWN（`D215_TYPE_AUTHORITY_UNVERIFIED`），不回 NOT_APPLICABLE。
  - 理由：參考實作對台幣資產缺分類權威時回 `FX_EVIDENCE_UNKNOWN`，Java 的 `applies()` 則回 false（NOT_APPLICABLE）。
  - 現行三檔 00865B／00719B／00697B 都已列為 `BOND_ETF`／`TWD`，hash `5ea009791bd09360dbab44661ce26dba4b8a765c2c5277ff680846bc00b5d6f0` 與嵌入 manifest 的 `research/ETF_UNIVERSE.md` 相符；VT 列為 `EQUITY_ETF`／`USD`，同一 hash。
  - 若 `PolicyInstruments.VALUES` 因 hash 不符而為空 map，四條債券腿與 VT 腿全部 UNKNOWN。
- [ ] 625.4 來源文件白名單。`verified_instruments[].source_document` 只允許 `research/ETF_UNIVERSE.md`、`docs/NAMED_DEEP_DRAWDOWN.md`（對齊參考實作 `D215_TYPE_POLICY_SOURCE_INVALID`）。這兩份來源原文未嵌入 JAR，只做 manifest hash 綁定，是既有已接受的鏈。
  - 在 helper 內核對；不符時回 UNKNOWN。
  - 不改 `UsdFxPurchaseContext.PolicyInstruments` 對雷達的行為。
- [ ] 625.5 例外價格事實要綁定市場完成日。建立 `RadarCompletedPriceDrawdown.Context` 時，`priceOnlyDaily` 最新列的 `tradingDate` 必須等於該市場 body 的 `completedSessions` 最後一日，且早於 `sessionDate`。參考實作的 `asset_completed_as_of` 由日曆核對，並有 `FX_EXCEPTION_COMPLETED_SESSION_LOOKAHEAD`；Java 的 `evaluate` 只取最新列。
  - 不符時例外為 false（`D215_EXCEPTION_PRICE_ASOF_MISMATCH`），preference 照常評估。
  - 所有列 `trusted=true`。價基：台股為 `TW_SPLIT_STOCK_PRICE`，美股為 `STORED_PRICE_ONLY`。
  - P 必須精確等於本腿同輪已驗 quote 價：債券腿為 `Symbol.quote().price()`，VT 為 t623 腿的參考價來源 quote。
- [ ] 625.6 兩年歷史的順序一致性。若凍結 FX 歷史列中，有 `isSpotBasisVerified()` 為 true 的 USD 列落在 `(completedAsOfDate, windowEnd]`，區間視為 UNKNOWN（`FX_TWO_YEAR_HISTORY_ORDER_INVALID`）。
  - 理由：參考實作對晚於完成日的列直接拒絕，Java 的 `windowRows` 只靜默過濾。
  - legacy 未核實列仍只排除、計數，不觸發本項。
- [ ] 625.7 以下差異只記錄、不修。Java 比參考實作嚴格，且等價輸入下結果相同；golden 輸入要讓它們成立：
  - Java 要求每列 `buyRate < sellRate`，並以 `HistoryProof.rowsSha256` 綁定列。
  - Java 以 FinMind 原生查詢 URL 區間證明涵蓋；參考實作改用 `droppedDates`／`coverage=COMPLETE` 宣告。
  - Feb-29：Java `minusYears(2)` 與參考 `years_before` 都落在 2/28。
  - 區間邊界：兩邊都是 `(S−L)×100 >= (H−L)×90` 的交叉乘法，等價。
  - ETF 例外：兩邊都是 `P×100 < H×90`，嚴格；STOCK 例外：兩邊都是 `P×100 <= H×70`。
  - 52 週窗口：兩邊都是 `[D−52週, D]`，邊界列 ≤ D−52週；同高取最新日。

### B. 擷取（SrppReportExtraCaptureAdapter）

- [ ] 625.8 把產出 `priceOnlyDaily`／`priceOnlyBasis` 的代號集合，從 `Set.of("VOO","0050","00881")` 擴為 VOO、0050、00881、VT、00865B、00719B、00697B。
  - 債券三檔在 reuse 路徑（`bond_price_selection_d195.strategic_symbols`）中，`rows` 是空清單，且凍結 daily 只有 100 天。因此這三檔要另讀一次 canonical `history.findByStockCodeAndMarketAndTradingDateBetweenOrderByTradingDateAsc(code,"台股",date.minusDays(730),date.minusDays(1))`。這次讀取只用於 price-only 例外基礎。
  - 不改 reuse 的 `daily`／`quote`／`book`／`radar`／`dividends` 內容，不呼叫 vendor，不刷新。
- [ ] 625.9 對上述 VT 與三檔債券的 market body，用與 VOO 相同的方式附上 `usdFxPurchase`：`UsdFxPurchaseContext.forAsset(code, m, "台股".equals(m)?"TWD":"USD", UsdFxPurchaseContext.live(rate, at, completedTw, fxRows, null))`。
  - `rate` 取自 `frozenSources.path("fx")`；`fxRows` 每輪只查一次 `fxHistory.findByCurrencyAndRateDateBetweenOrderByRateDateAsc("USD",date.minusYears(2),date.minusDays(1))`，由五個代號共用。
  - 例外：`fxHistory==null` 時寫 `{"evaluated":false}`；其他失敗寫 `{"evaluated":false,"reason":"D215_FX_CONTEXT_UNAVAILABLE"}`。
  - VOO 既有欄位與原因碼 `D222_FX_SELECTION_CONTEXT_UNAVAILABLE` 不變。

### C. 債券腿（BOND_D130_D195）

- [ ] 625.10 `SrppReportModuleCalculator.bond(...)` 改為接收 `model` 與 `extra`（呼叫點 `out.add(bond(frozen,policy))` 同步修改）。
  - 本任務**不改** `DailyDecisionCalculator.calculate` 本身、`DailyDecisionCalculator.VERSION` 及每日決策端點（`/api/srpp/daily-decision` 系列）。（之後的 Task 626 會另改緊急債券腿在「報價缺口是唯一原因」時的原因碼，`VERSION` 仍不變；本任務的 D-215 後置閘門不依賴該原因碼。）D-215 是 bond() 組腿後的後置閘門。
  - 每個 Candidate（含 BLOCKED、0 張）都加一筆 `D215_FX` 收據，calculatorVersion 用 `SRPP_REPORT_CALCULATIONS_V1`，scope 為 null。
  - `inputRefs` 固定為 `["fx","policy","market:台股:<symbol>"]`。
- [ ] 625.11 收據狀態與 reason 依參考實作 `status` 一一對應，不得自創其他值；唯一例外是下方「來源未驗證」前提的 `SOURCE_NOT_VERIFIED:<ref>`（屬 wire 層降級，不是參考實作 status，golden 不比對）。

| 參考實作 status | 收據 status | 收據 reason | 腿 |
| --- | --- | --- | --- |
| `NO_FX_PREFERENCE_VETO`（fx_risk NORMAL） | PASS | `D215_NO_FX_PREFERENCE_VETO` | 不變 |
| `FX_PREFERENCE_EXCEPTION_REVIEW`（HIGH 或 UNKNOWN，但核實例外成立） | PASS | `D215_FX_PREFERENCE_EXCEPTION_REVIEW` | 不變（例外只解除本偏好，不解除其他閘門） |
| `WAIT_PREFERRED`（HIGH，無例外） | BLOCK | `D215_FX_WAIT_PREFERRED` | 見 625.12 |
| `FX_EVIDENCE_UNKNOWN` | UNAVAILABLE | `D215_FX_EVIDENCE_UNKNOWN` | 見 625.12；並 `m.missing("D215_FX_EVIDENCE_UNKNOWN:<symbol>")` |
| `NOT_APPLICABLE` | 不可能出現（四腿皆 BOND_ETF）；若出現，視為 UNKNOWN | — | — |

  - **PASS 前提（審查後新增，同 t623 623.12）**：上表兩個 PASS 列，只有在本收據 `inputRefs`（`fx`、`policy`、`market:台股:<symbol>`；VT 為 `market:美股:VT`）於 `sourceVector` 的 `validation` **全為 VERIFIED** 時才可輸出 PASS；任一不是 → 收據 `UNAVAILABLE`、`reason = SOURCE_NOT_VERIFIED:<ref>`（多個依 refs 排序以 `,` 連接），並 `m.missing("SOURCE_NOT_VERIFIED:<ref>:<symbol>")`，腿依 625.12 轉換（BLOCKED、0、ceiling 寫入 values）。理由：BFF `SrppReportResponseValidator` 對 ACTIONABLE 腿每個收據的 inputRefs 必須全在 `verifiedSources`，否則整份 report-calculations 502；`market:台股:<symbol>` 可能因 `SrppReportExtraCaptureAdapter` 把非財務股利置 null 等原因被 `assess()` 判為 UNAVAILABLE。WAIT_PREFERRED／UNKNOWN 列本來就不是 PASS，照上表輸出，不改 reason。

- [ ] 625.12 腿轉換。只在原 Candidate 為 `ACTIONABLE_FOR_REPORT`，且 D215_FX 不是 PASS 時執行：
  - `status=BLOCKED`、`action=NONE`、`lots=0`，`limitPrice`／`amount`／`fees` 為 null。
  - `blockingReasons` 加入收據 reason（既有原因保留，以 sorted set 去重）。
  - 原張數、原限價×1000×張數金額，分別寫入同收據 values 的 `d215WaitCeilingLots`（LOTS）與 `d215WaitCeilingAmount`（TWD）。
  - 原 BLOCKED 腿不寫 ceiling values。
  - **不重流**：D-195 排名、被選檔，以及 strategic 已在 `DailyDecisionCalculator` 內扣用的共享資金都不改；不改選另一檔，也不把釋出的資金給 emergency。
  - 轉換必須在 `sharedFunds(out)` 之前完成，讓 WAIT 腿不參與共享資金。
  - 既有 `FX_CAP`（>33 停買）照原樣保留；兩者同時成立時兩個原因並列。
- [ ] 625.13 收據 values 逐項輸出（subject 皆為腿 symbol；值使用 canonical decimal；未知時 quality=UNAVAILABLE、value=null、reason 為具名原因）。

| name | unit | 內容 |
| --- | --- | --- |
| `fxSpotSelling` | `TWD` | 同輪 LIVE 銀行即期賣出 S |
| `fxRangeLow` | `TWD` | 兩曆年 BANK_SPOT_SELLING 最低 L；未核實時 UNAVAILABLE |
| `fxRangeHigh` | `TWD` | 兩曆年 BANK_SPOT_SELLING 最高 H；未核實時 UNAVAILABLE |
| `fxRangePosition` | `RATIO` | 僅供顯示，`(S−L)/(H−L)` 用 `MathContext(28, HALF_EVEN)`，等同 Python Decimal 預設 context；不夾到 0–1；決策只用交叉乘法 |
| `fxAbsoluteHigh` | `BOOLEAN` | 1 代表 S>32.8，0 代表否 |
| `fxHigh` | `BOOLEAN` | 1=HIGH、0=NORMAL；UNKNOWN 時 UNAVAILABLE（`D215_FX_RANGE_UNKNOWN`） |
| `assetHigh52w` | 台股 `TWD`／VT `USD` | 自身 52 週完成高點 |
| `assetDrawdown52w` | `PERCENT` | `RadarCompletedPriceDrawdown.Context.drawdownPercent()` |
| `cheapAssetException` | `BOOLEAN` | 例外是否成立；fx NORMAL 時參考實作不評例外，填 UNAVAILABLE（`D215_EXCEPTION_NOT_EVALUATED_FX_NORMAL`） |

  - fxPreference 不作數值 fact（BOOLEAN／RATIO 都無法表達四值），由收據 reason 表達。
  - 所有 name 都只出現在 `D215_FX` 收據的 values，**不加入** `BOND_D130_D195` 模組 `data.facts`。現行 OpenAPI 該模組 `facts` 為 `maxItems: 0`，BFF `FACT_NAMES` 對 BOND 也是 `Set.of()`，兩者保持不變。
  - 這沿用 D-218（`d218BudgetCeiling`）與 D-227（`d227DValue`）的通用 receipts.values 契約。

### D. VT 建倉腿（GLOBAL_EQUITY_PACE，依 t623）

- [ ] 625.14 對 t623 產生的 VT BUY 腿（`module=GLOBAL_EQUITY_PACE`、`strategy=SATELLITE_BUILD_D234`、`market=美股`、`currency=USD`、`lots=null`、`units="N"`、`broker=國泰證券`、`subaccount=CORE_D234`）套用同一 helper：
  - `inputRefs=["fx","policy","market:美股:VT"]`。
  - 收據與轉換規則與 625.11–625.13 相同，但 ceiling 寫入 `d215WaitCeilingUnits`（SHARES）及 `d215WaitCeilingAmount`（USD），轉換後 `units="0"`。**例外**：VT 腿轉 BLOCKED 時 `action` 維持 `BUY`（t623 0 量腿與 `sharedFunds` 的既有慣例；債券腿改 `NONE` 是 `DailyDecisionCalculator.candidate` 的慣例），t623 的 `FX_CONVERSION` 收據原樣保留。
  - 轉換在 `sharedFunds(out)` 之前完成（同 625.12），所以 WAIT 的 VT 腿不扣 TWD 資金池。
  - VT ACTIONABLE 收據集合由 t623 的 `{SATELLITE_BUILD, FX_CONVERSION, SHARED_CURRENCY_CAPACITY}` 變為 `{SATELLITE_BUILD, FX_CONVERSION, D215_FX, SHARED_CURRENCY_CAPACITY}`（bundle 含 D-215 政策時）；BFF `SrppReportResponseValidator` 與 business `SrppReportEnvelopeValidator` 的 t623 VT 檢查同步把 `D215_FX` 加進集合，其他不動。
  - VT 為 `EQUITY_ETF`，例外是 `P×100 < H×90`，P 為 t623 腿的同輪參考價。
  - 只加 `D215_FX` 一筆，不改 t623 其他 gate。
  - **收盤後初始化包（審查第 2 輪確認，不另加條款）**：t623 623.13a 已讓 `publication-context` `mode=AFTER_CLOSE_INITIALIZATION` 的包中 VT 腿一律 BLOCKED 0；債券腿由 `DailyDecisionCalculator` 的報價與五檔時效自然 BLOCKED。本任務的 D215_FX 只會把 ACTIONABLE 腿降為 BLOCKED（625.12 只在原腿 ACTIONABLE 時轉換），不會把 BLOCKED 腿升為 ACTIONABLE，也不寫正數 `lots／units／limitPrice／amount`（ceiling 只寫入收據 values 的 `d215WaitCeiling*` facts，不是 `requireNonExecutable` 檢查的鍵），所以不需新的 after-close 規則。收盤後包中 VT 腿已是 BLOCKED，故不寫 ceiling values，`blockingReasons` 仍以 `AFTER_CLOSE_INITIALIZATION_NON_EXECUTABLE` 為首。
- [ ] 625.15 `STRATEGIC_BUY_GATES` 的 0050／00881（NAMED_DEEP_DRAWDOWN_D215／NAMED_CASH_BUY_D226）是台幣股票 ETF，`purchase_scope` 明定不納入，不加 `D215_FX`。`SHORT_TERM_AND_US_TACTICAL` 的 `BOND_SHORT_TERM_D185` 也屬 SRPP 的 `TWD_BOND_SHORT_TERM` scope，但不在本任務範圍，另開任務；此處維持 SRPP 本機評估。

### E. Wire、OpenAPI、validator、文件

- [ ] 625.16 BFF `bff/src/main/java/com/steven/assets/bff/publicsrpp/SrppReportResponseValidator.java`：
  - ACTIONABLE 的 `STRATEGIC_FUBON`／`EMERGENCY_CATHAY` 腿，在 bundle 含 D-215 政策時，`required` gate 加 `D215_FX`，且它必須 PASS。
  - `D215_FX` 的 refs 精確等於 `{fx, policy, market:台股:<symbol>}`。
  - BLOCKED 腿的 `D215_FX` 可以是 BLOCK 或 UNAVAILABLE。
  - values 的 name 只允許 625.13 列出的名稱，以及 `d215WaitCeilingLots`／`d215WaitCeilingUnits`／`d215WaitCeilingAmount`；unit 依表核對。
  - VT 腿同樣處理（t623 之後）。
  - 加入正向、WAIT、UNKNOWN、`SOURCE_NOT_VERIFIED`（BLOCKED 腿可帶此 reason；ACTIONABLE 腿的 D215_FX refs 含未驗證來源必拒）、非法 refs、非法 name 測試。
- [ ] 625.17 後端 `SrppReportEnvelopeValidator` 不需改：非 ACTIONABLE 腿為 0／null，已涵蓋。加一則回歸測試，證明 WAIT 腿帶 ceiling values 時 envelope 仍合法。
- [ ] 625.18 OpenAPI 來源 `docs/openapi/docker-external-api.yaml`：
  - 在 `SrppReportLeg.receipts` 說明中「狹義Task488既有BOND optional…」一句後，補上 D-215 說明：
    - 債券四腿與 VT 腿逐腿有 `D215_FX`，refs 為 fx/policy/market。
    - PASS 只限 `D215_NO_FX_PREFERENCE_VETO`／`D215_FX_PREFERENCE_EXCEPTION_REVIEW`。
    - `D215_FX_WAIT_PREFERRED`(BLOCK)／`D215_FX_EVIDENCE_UNKNOWN`(UNAVAILABLE) 時腿為 BLOCKED 0，原上限在 `d215WaitCeiling*`。
    - 列出 values 名稱與 unit。
  - status enum 與 facts maxItems 都不變。
  - 以 `ruby scripts/render-9090-openapi-docs.rb` 重產 `docs/openapi/9090-api-swagger.md` 與 `backend/src/main/resources/srpp/9090-api-swagger.md`，再跑 `python3 scripts/sync-srpp-swagger-identity.py --asset-only`。Swagger 文字一變，就會進入 Task 504 發布鏈。
- [ ] 625.19 SRPP 端配套（屬 SRPP repo，由發布流程實作，本任務在完成報告列出確認）：
  - `scripts/verify_srpp_report_calculations.py:leg_check` 對 bond ACTIONABLE 腿呼叫 `verify_srpp_daily_decision.expected_refs(rule)`。未知 rule 會 `UNKNOWN_RULE`，所以必須先讓 `D215_FX` 對應 `{"fx","policy","market:台股:"+symbol}`，並要求 ACTIONABLE 時為 PASS。
  - verifier 要以 `usd_asset_fx_preference.evaluate` 重播 values。
  - `docs/API_CALCULATION_RULES.md` 把 D-215 債券／VT 改為 API 優先、本機具名回退。
  - **發布順序**：SRPP verifier 接受 `D215_FX` → 規則包重建 → 9090 登錄 → business-services／bff 重建。順序相反時，ACTIONABLE 債券腿會讓 SRPP 判 `UNKNOWN_RULE`。
- [ ] 625.20 Policy authority。跑 `python3 scripts/check-srpp-report-authority.py --srpp-root <SRPP> --check`。公式指紋涵蓋 `SrppReportModuleCalculator`／`SrppReportExtraCaptureAdapter`，本任務改了兩者位元組，必須以 `scripts/generate-srpp-formula-v3.py` 重產，並以 insert-only registry SQL 登錄；不改既有 row。不新增路由、資料表或 Liquibase。
  - **D-234 四任務共用的重產順序（定案）**：t623／t624／t625／t626 都會改 `SrppReportModuleCalculator.java` 位元組，四支任務在**同一分支**依 t623 → t624 → t625 → t626 順序實作；本任務接在 t624 之後，結束時以同一個 SRPP root（`/Users/steven/Project/SRPP/.claude/worktrees/asset-management-logic-review-d1cbbc`；SRPP D-234 合併到 main 後改用 `/Users/steven/Project/SRPP`）重產一次公式 V3 manifest（fixture、`SrppFormulaCatalog` 生成區塊、BFF mirror）與本任務 golden，並以 `--check` 證明；不得沿用 t624 的 manifest digest。

### F. Golden parity（≥10，實際 17 組）

- [ ] 625.21 新增 `scripts/generate-srpp-d215-fx-golden.py`，結構比照 `scripts/generate-srpp-reserve-golden.py`：
  - 參數 `--srpp-root`（必填）、`--check`。
  - 以 `importlib` 載入 `<srpp-root>/scripts/usd_asset_fx_preference.py`，先把 `<srpp-root>/scripts` 加進 `sys.path`，因為它 import `deep_drawdown`。
  - 從 `<srpp-root>/assumptions/model_inputs.json` 取出 policy 並一起寫入。
  - 輸出 `backend/src/test/resources/srpp/d215-fx-preference-golden.json`：`schema="D215_FX_PREFERENCE_GOLDEN_V1"`，以及 `policy` 和 `cases[{name,payload,expected}]`；`expected` 是 `evaluate(payload, policy)` 的完整回傳。
  - `--check` 漂移時以 `D215_FX_GOLDEN_DRIFT` 結束；通過時印 `D215_FX_GOLDEN_PASS:<n>`。
  - 不加 `fx_source_api`，所以不走 projection。
  - payload 固定欄位：`checked_at=2026-11-23T09:05:00+08:00`；`fx_quote` 為 `rate_date=2026-11-23`、`as_of=2026-11-23T09:04:58+08:00`；`fx_completed_as_of=2026-11-20`。
  - `fx_history.dailyRates` 降冪列出，每列另含 `buyRate=rate−0.1`（參考實作忽略），讓 Java 端可以重建。
  - asset 帶 `action=BUY`、`in_existing_universe=true`；例外案例另帶 `asset_price_fact`（52 週 `completedPriceHistory`）、`asset_completed_as_of=2026-11-20`、`session_date=2026-11-23`、`price_verified_current_round=true`。
- [ ] 625.22 必備案例（名稱固定）：

| # | name | 情境 | 參考實作預期 |
| --- | --- | --- | --- |
| 1 | `bond_00865B_absolute_equal_32_8_normal` | S=32.8、L=30、H=33.5 | 位置 0.8 → NO_FX_PREFERENCE_VETO |
| 2 | `bond_00719B_absolute_above_32_8_range_missing` | S=32.81、無歷史 | WAIT_PREFERRED、fx_risk HIGH |
| 3 | `bond_00697B_position_exact_0_90_high` | L=30、H=33、S=32.7 | WAIT_PREFERRED |
| 4 | `bond_00865B_position_below_0_90_normal` | S=32.69 | NO_FX_PREFERENCE_VETO |
| 5 | `bond_zero_range_unknown` | L=H=31、S=31 | FX_EVIDENCE_UNKNOWN、range_status UNKNOWN |
| 6 | `bond_missing_history_unknown` | S=32.0、無 fx_history | FX_EVIDENCE_UNKNOWN |
| 7 | `bond_etf_drawdown_exact_10_no_exception` | HIGH、P=90、H=100 | WAIT_PREFERRED、cheap_exception_verified=false |
| 8 | `bond_etf_drawdown_above_10_exception` | HIGH、P=89.99、H=100 | FX_PREFERENCE_EXCEPTION_REVIEW |
| 9 | `stock_goog_drawdown_exact_30_exception` | USD STOCK、HIGH、P=70、H=100 | FX_PREFERENCE_EXCEPTION_REVIEW |
| 10 | `stock_goog_drawdown_29_99_wait` | P=70.01 | WAIT_PREFERRED |
| 11 | `vt_range_unknown_with_verified_exception` | S=32.5、無歷史、P=88、H=100 | FX_PREFERENCE_EXCEPTION_REVIEW、fx_risk UNKNOWN |
| 12 | `vt_normal` | VT 一般情況 | NO_FX_PREFERENCE_VETO |
| 13 | `quote_indicative_unknown` | quote_status=INDICATIVE，且有例外 | FX_EVIDENCE_UNKNOWN |
| 14 | `twd_unlisted_bond_claim_unknown` | 台股 `00937B` 未列名 | FX_EVIDENCE_UNKNOWN（驗 625.3） |
| 15 | `twd_equity_etf_0050_not_applicable` | 0050 | NOT_APPLICABLE |
| 16 | `history_window_start_mismatch_unknown` | windowStart 錯一天、S=32.0 | FX_EVIDENCE_UNKNOWN |
| 17 | `leap_day_window_start` | `rate_date=2028-02-29`、windowStart=2026-02-28 | 依 S 判定，起點 2/28 |

- [ ] 625.23 在既有 `backend/src/test/java/com/steven/assets/service/srpp/report/SrppReportModuleCalculatorTest.java` 新增 `@ParameterizedTest` 方法 `d215FxGoldenParity`（不另建測試類；`spec-check` 會擋尚不存在的測試類名），讀取 golden，把每個 payload 轉成 Java 輸入：
  - `fx_quote` → `UsdTwdLiveRateService.LiveRate`：source BANK_OF_TAIWAN、`liveUpdateStatus=ACTIVE`、quoteStatus 照搬、sellRate=rate、buyRate=rate−0.1、polledAt=as_of。
  - `dailyRates` → `ExchangeRateHistory` 列：沿用 `UsdFxPurchaseContextTest` 以 `ExchangeRateSourceCapture.memberSha` 建 spot-verified 列的作法。
  - 明確 `HistoryProof`：`rowsSha256=UsdFxPurchaseContext.rowsSha256(selected)`，window 與 completed 依 payload；payload 刻意不一致的案例，照 payload 給錯的值。
  - `asset_price_fact.completedPriceHistory` → `StockPriceHistory` 降冪列，再經 `RadarCompletedPriceDrawdown.evaluate`。
  - 經 `SrppReportD215FxPreference` 得出結果後，逐欄比對 `status`、`fx_risk`、`range_status`、`range_low`、`range_high`、`range_position`、`absolute_rate_high`、`rate`、`cheap_exception_verified`（參考實作無此鍵時，Java 必須是 UNAVAILABLE）、`classification_status`、`scope`。
  - 數值用 `BigDecimal.compareTo==0`；`range_position` 以 28 位有效數字比較。
  - `reasons` 字彙兩邊不同，不比對，但參考實作有 reason 時，Java 必須有非空具名原因。

### G. 回歸測試

- [ ] 625.24 在 `SrppReportModuleCalculatorTest` 加入以下案例：
  - 四條債券腿各自有 D215_FX，refs 精確。
  - HIGH：被選策略腿與 emergency 腿都 BLOCKED 0，ceiling values 等於原 lots／amount；不重流到其他檔；不扣 sharedFunds。
  - UNKNOWN：模組 missing，且帶 `D215_FX_EVIDENCE_UNKNOWN:<symbol>`。
  - NORMAL：原 ACTIONABLE 與張數不變。
  - 例外成立時：同時有 FX_CAP BLOCK 的腿仍 BLOCKED。
  - 政策值改 0.91：回 `D215_FX_POLICY_UNAVAILABLE`。
  - 舊 bundle 無政策鍵：輸出 byte-identical。
  - `PolicyInstruments` 為空：全部 UNKNOWN。
  - HISTORICAL_PROXY：UNKNOWN。
  - 每日決策端點輸出不變。
  - 來源未驗證：參考實作為 `NO_FX_PREFERENCE_VETO`，但 `market:台股:<symbol>`（或 `fx`）的 `sourceVector.validation` 為 UNAVAILABLE → `D215_FX` 為 UNAVAILABLE／`SOURCE_NOT_VERIFIED:<ref>`、原 ACTIONABLE 腿 BLOCKED 0 並帶 ceiling values、module missing 含該碼，且整份輸出通過 BFF validator（不得出現 ACTIONABLE 腿引用未驗證來源）；VT 腿以 `market:美股:VT` 同測一案。
  - 收盤後初始化：以 `SrppReportProducerScheduler.withAfterCloseContext` 處理後的 Frozen、D215_FX 結果為 `NO_FX_PREFERENCE_VETO` 的輸入計算整包，VT 腿維持 t623 623.13a 的 BLOCKED 0（無 ceiling values）、債券腿無 ACTIONABLE，整包通過 `SrppReportEnvelopeValidator.validate`。
- [ ] 625.25 不新增 API、vendor I/O、券商寫入、寄信或下單。不在合法時窗外啟動 producer 或補跑報告。

## 驗證

```bash
cd /private/tmp/asset-d234-calc-migration
bash scripts/spec-check.sh
python3 scripts/generate-srpp-d215-fx-golden.py --srpp-root /Users/steven/Project/SRPP/.claude/worktrees/asset-management-logic-review-d1cbbc --check
python3 scripts/generate-srpp-formula-v3.py --srpp-root /Users/steven/Project/SRPP/.claude/worktrees/asset-management-logic-review-d1cbbc --check
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=UsdFxPurchaseContextTest,SrppReportModuleCalculatorTest,SrppReportMediumDipCalculatorTest,DailyDecisionCalculatorTest test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml -Dtest=SrppReportResponseValidatorTest test
ruby scripts/render-9090-openapi-docs.rb --asset-only --check
python3 scripts/sync-srpp-swagger-identity.py --asset-only --check
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
# 下行另需 EMBEDDED_MODEL 與 SRPP 正本位元組相同，只能在 t623 623.19 的 Swagger identity sync 之後通過；實作期間不列為必過項
python3 scripts/check-srpp-report-authority.py --srpp-root /Users/steven/Project/SRPP/.claude/worktrees/asset-management-logic-review-d1cbbc --check
```

驗證區塊不含任何 docker 重建或重啟；重建只在下方「正式發布」段執行。

SRPP 端（625.19 合併後）：

```bash
cd /Users/steven/Project/SRPP && python3 -m pytest -q scripts/test_data_tools.py -k "d215 or report_calculations"
```

正式發布（release 責任人）沿用 Task 504 鏈與 625.19 順序：SRPP verifier 接受 `D215_FX` → 規則包重建 → `check-srpp-report-authority.py --check` → `ruby scripts/srpp-report-policy-register.rb` 產 insert-only registry SQL 並登錄 row → 重建。**僅在 insert-only registry row 登錄之後、從乾淨 main 執行**：

```bash
docker compose -p asset-management build business-services bff
docker compose -p asset-management up -d --no-deps --force-recreate business-services bff
curl -s http://localhost:8080/actuator/health
```

之後核對兩端 main refs、registry row、JAR 內嵌 bytes，並從 SRPP 實測 9090 report-calculations 200，確認債券腿帶 `D215_FX`。不補跑日報、不寄信、不下單。

## 完成報告

待實作及正式驗收。
