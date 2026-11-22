# [t624] FUNDING_CAPACITY 新增不扣預留的 D-152 全行合併買後剩餘額度 pooledPostBuyHeadroom

**對應 Requirements:** Requirement 221（FUNDING_CAPACITY 不扣預留的 D-152 全行合併買後剩餘額度：每幣別輸出 `pooledPostBuyHeadroom`，使 SRPP 只需比較「本輪擬採納買進合計 ≤ headroom」即與本機 `pooled_funding` 的 PASS 等價，預留／官方掛單來源缺時不再整段回退本機）
**前置任務:** t623（同分支依 t623 → t624 → t625 → t626 順序實作，見 624.9；本任務計算本身不依賴 t623）
**Liquibase changeset:** 無

## 背景

SRPP（`/Users/steven/Project/SRPP`）每輪日報對任何買進都要做 D-152「全部銀行已交割存款合併」買後底線檢查（本機 `scripts/trade_blocker_policy.py` 的 `pooled_funding`，basis `ALL_BANK_SETTLED_DEPOSITS_D152`）。9090 的 `FUNDING_CAPACITY` 模組已逐幣別彙總全部銀行（`settledBalance`、`payable`、`excludedReceivable`、`termBalance`、`termFloor`、`totalFloor` 一律輸出），但唯一可直接比較的額度 `deployableCapacity` 要扣「全部策略預留＋官方全幣別掛單」，任一來源缺時為 null（`ALL_STRATEGY_REPORT_RESERVATIONS_UNAVAILABLE`／`OFFICIAL_ALL_CURRENCY_PENDING_ORDERS_UNAVAILABLE`），且 `CURRENCY_POOL` 收據同時變 `UNAVAILABLE`——SRPP verifier 規定 FUNDING_CAPACITY 每個 fact 只有在 `CURRENCY_POOL` 收據為 PASS/BLOCK 時才可採納，所以連不受預留影響的 `settledBalance` 也一併不能用，SRPP 只好整段回退本機公式。

D-152 本身不扣預留（預留由 SRPP 把同輪各腿與費用加總成 `cashRequired` 處理），所以本任務新增一個**不扣預留**、**只依 assets＋policy** 的 fact 與其獨立收據，與本機參考實作逐案等價。這是 D-234「SRPP 固定公式移入 9090」批次的第 2 項；不推翻任何既有欄位語意，`deployableCapacity`／`CURRENCY_POOL` 原樣保留。

### 已定案的幣別歸屬（Steven 2026-11-22，與 t623／t626 一致）

- **VT 建倉腿以 TWD 計**：t623 的 VT 腿（`SATELLITE_BUILD_D234`）走國泰台幣複委託，它的 `cashRequired` 是 `FX_CONVERSION` 收據的 `twdAmount + twdFee`，計入 **TWD** 的 `pooledPostBuyHeadroom`，不計入 USD。
- **D-182 美元定期定額改台幣扣款**：VT（5 日）、VOO（15、25 日）各 US$350 三筆指示，在全部銀行美元存款合併不足以支應某筆時改由國泰台幣扣款；台幣等值（USD 金額 × 同輪已核實銀行即期賣出匯率＋手續費）在其扣款日成為 **TWD 資金承諾**，同樣必須守住 D-152 台幣買後底線（定存 800,000／總存款 1,550,000，通膨指數化）。這筆承諾由 consumer 計入 TWD `cashRequired`（Task 626 的 `fundingCommitments.TWD`），本 fact 的定義不變（不扣預留、不扣承諾）。SRPP 設定：`model_inputs.json` → `execution_policy_overrides.global_equity_usd_dca_d182.funding`。

### 參考實作（原文照錄，SRPP `scripts/trade_blocker_policy.py`，以 Decimal 計算）

```python
POOLED_BASIS = "ALL_BANK_SETTLED_DEPOSITS_D152"
def pooled_funding(assets, trading_date, currency, cash_required, model=None):
    validate_assets(assets)
    if taipei_timestamp(assets.get("generatedAt")).date().isoformat() != trading_date:
        raise ValueError("FUNDING_SAME_ROUND_ASSETS_REQUIRED")
    if currency not in {"TWD", "USD"}:
        raise ValueError("FUNDING_CURRENCY_UNSUPPORTED")
    debit = number(cash_required)
    if debit < 0:
        raise ValueError("FUNDING_DEBIT_NEGATIVE")
    policy = model["execution_policy"]["stock_purchase_funding_policy_d150"]
    total, term, payables = Decimal(0), Decimal(0), Decimal(0)
    for row in assets["snapshot"]["deposits"]:
        if row["currency"] not in {currency, "TRANSIT_" + currency}:
            continue
        amount = number(row["amount"] if currency == "TWD" else row["originalAmount"])
        if row["currency"].startswith("TRANSIT_"):
            payables += min(amount, Decimal(0))
            continue
        total += amount
        if row["depositType"] in {"定存", "美元定存"}:
            term += amount
    after = total + payables - debit
    term_after = min(term, max(after, Decimal(0)))
    index = Decimal(str(reference_price_level(dt.date.fromisoformat(trading_date))))
    term_floor = number(policy["twd_term_deposit_floor_twd"]) * index if currency == "TWD" else Decimal(0)
    total_floor = number(policy["twd_total_deposit_floor_twd"]) * index if currency == "TWD" else Decimal(0)
    passed = after >= total_floor and term_after >= term_floor
    # status = "PASS" if passed else "BLOCKED"
```

- `reference_price_level(date, annual_rate=.0225) = (1 + annual_rate) ** ((date - 2026-08-31).days / 365.25)`（SRPP `scripts/budget_accounting.py`，`BASE_DATE = 2026-08-31`）。`Decimal(str(float))` 取 Python 最短 repr。
- 現行底線（SRPP `assumptions/model_inputs.json` → `execution_policy.stock_purchase_funding_policy_d150`）：`twd_term_deposit_floor_twd = 800000`、`twd_total_deposit_floor_twd = 1550000`（2026-08 實質台幣，依經過日數指數化）。USD 兩底線固定 0。
- `taipei_timestamp`：有 offset 依 offset 換算，無 offset 視為 Asia/Taipei；比較的是台北日期。

### 等價代數（已由作者以參考實作暴力驗證 4041 組、0 不符）

令 `S = Σ 非在途列`、`P = Σ min(在途列, 0)`（≤ 0）、`T = Σ 定存列`、`F_t = termFloor`、`F_a = totalFloor`、`d = cashRequired ≥ 0`、`after = S + P − d`。

- `term_after ≥ F_t` ⇔ `T ≥ F_t` 且 `max(after,0) ≥ F_t`。
  - `F_t > 0`（TWD）：`max(after,0) ≥ F_t` ⇔ `after ≥ F_t`。
  - `F_t = 0`（USD）：`max(after,0) ≥ 0` 恆真，只剩 `T ≥ 0`。
- 故 PASS ⇔ `T ≥ F_t` 且 `after ≥ max(F_a, F_t)` ⇔ `T ≥ F_t` 且 `d ≤ S + P − max(F_a, F_t)`。
- 定義 `pooledPostBuyHeadroom = S + P − max(F_a, F_t)`（`T ≥ F_t` 時；可為負），否則 null（原因 `POOLED_TERM_BELOW_FLOOR`）。則對任何 `d ≥ 0`：`d ≤ headroom` ⇔ `pooled_funding(...).status == "PASS"`；headroom 為 null 時任何 d（含 0）都 BLOCKED；headroom < 0 時任何 d（含 0）都 BLOCKED。
- `term_after = min(term, max(after,0))` 規則（使用者可在任一銀行解約定存、盡量保留定存本金）已被上式完整吸收，不需在 Java 另做 term_after。
- 現行底線 `F_a(1,550,000) > F_t(800,000)`，`max` 恆取 totalFloor；仍必須寫成 `max(F_a, F_t)`，底線日後調整才不失真（golden 以合成 model 涵蓋 `F_t > F_a`）。
- **符號對齊**：Python `payables` 是負數總和；Java 既有 `payable` fact 存的是負在途金額的絕對值（正數）。因此 `headroom = settled − payable(abs) − max(F_a, F_t)` 與 Python `settled + payables − max(...)` 相同。正數在途（未交割收款）兩邊都排除（Java 記在 `excludedReceivable`）。

### 現行 Java 與參考實作的差異（本任務必須修正於新計算，不得重用有差異的既有值）

1. **定存判定不同（must-fix）。** `DecisionInputCaptureAdapter` 第 93–96 行算 `DecisionFacts.Input.termDeposits()`：只取 `currency == "TWD"`，`depositType` **包含** `"TERM"` 或 `"定存"` 且 `amount > 0`。參考實作是 `depositType` **精確等於** `定存` 或 `美元定存`，且 USD 也算（取 `originalAmount`）。例如 `定存單`、`質借定存`、`TERM_DEPOSIT` 在 Java 會算、Python 不算。`FUNDING_CAPACITY` 既有 `termBalance` fact 直接取 `f.facts().termDeposits()`，所以 **headroom 不得用 `termBalance`／`f.facts().termDeposits()`**，必須以精確集合自行加總。既有 `termBalance` 與 `DecisionFacts.termDeposits` 本任務不改（其他模組與 DailyDecisionCalculator 依賴），差異以收據 values 的 `pooledTermBalance` 揭露。
2. **在途判定不同。** `SrppReportModuleCalculator.transit(d)` 同時認 `currency` 開頭 `TRANSIT_` **或** `depositType` 開頭 `TRANSIT_`；參考實作只看 `currency`（`DecisionInputCaptureAdapter` 第 150 行註解亦明載 depositType 只是顯示用類別，不得拿來判斷 transit）。新計算只看 `currency`。共用的 `transit()` 也被 ASSET_RECONCILIATION 使用，本任務不改它。
3. **幣別覆蓋不同。** 既有 funding 迴圈只對「有出現的幣別」輸出 fact；參考實作對沒有任何列的 TWD 也會算出 `0 − 底線`（負值），USD 算出 0。新 fact 必須 TWD、USD 兩筆都固定輸出。
4. **同輪檢查。** 參考實作要求 `assets.generatedAt` 的台北日期等於 tradingDate；既有 funding 沒有這道檢查。新計算必須加，不符時 headroom 為 null（`UNAVAILABLE`）。
5. 數值精度已核對一致：Java `DailyDecisionCalculator.inflation` 為 `BigDecimal.valueOf(StrictMath.pow(1.0225, days/365.25))`，JDK 25 的 `Double.toString` 與 Python repr 同為最短 repr；作者實測 2026-11-16 `1.0047017700289897`、2026-11-22 `1.0050690687927297`、2027-03-01 `1.0111489213082812` 兩邊相同。但參考實作的 `reference_price_level` 用預設參數 `.0225`，不讀 `annual_inflation_rate`；Java 讀 `execution_policy.annual_inflation_rate`（現值 0.0225）與 `twd_policy_amount_basis_d150.base_date`（現值 2026-08-31）。兩值相同時等價；golden 以精確 Decimal 相等比對，任一邊日後漂移會被測試抓到。

### 相關但不在本任務的矛盾（只記錄）

- **BLOCK 被迫以 UNAVAILABLE 表示。** D-234 規格要求定存低於底線時 headroom 為 null。但 Swagger／BFF 規定 null 值 quality 必為 `UNAVAILABLE`，且 BFF validator（`SrppReportResponseValidator` 第 142 行）禁止 `COMPLETE` 模組含 UNAVAILABLE fact，故此時模組會成為 `PARTIAL`。這是已驗證的阻擋，不是缺資料；consumer 必須讀本任務的 `D152_POOLED:<幣別>` 收據（`BLOCK`＋`POOLED_TERM_BELOW_FLOOR`）判定，不得當作來源缺而回退本機。
- **SRPP verifier 目前不認得新 fact。** `verify_srpp_report_calculations.py` 的 `fact_binding` 對 FUNDING_CAPACITY 只接受既有名稱，收據規則只認 `CURRENCY_POOL`（refs = assets、report-reservations、topup-state、policy）。新 fact 要被採納，SRPP 需另加綁定：`pooledPostBuyHeadroom` 為幣別欄位、unit = subject、rule = `D152_POOLED:<subject>`、receipt refs = {assets, policy}。屬 SRPP 端後續工作。

## 要做什麼

所有金額為 `BigDecimal`，不得用 double 做加減或比較；輸出以既有 `dec()` 正規化（無尾零、無科學記號、0 為 `"0"`）。不新增路由、不新增 DB 欄位、不下單、不寫入任何狀態；輸出仍只是報告計算。

- [ ] 624.1 新增純計算類別 `backend/src/main/java/com/steven/assets/service/srpp/report/SrppPooledFunding.java`（package-private 或 public final，無 Spring 相依、無 I/O）：
  ```java
  record Result(String currency, String status, String reason,
                BigDecimal settled, BigDecimal knownPayables, BigDecimal term,
                BigDecimal termFloor, BigDecimal totalFloor, BigDecimal headroom) {}
  static Result evaluate(JsonNode assets, LocalDate tradingDate, String currency,
                         BigDecimal termFloorBase, BigDecimal totalFloorBase, BigDecimal inflationFactor)
  ```
  規則（照參考實作，不得增減）：
  - `currency` 只允許 `TWD`、`USD`，其他丟 `IllegalArgumentException("FUNDING_CURRENCY_UNSUPPORTED")`。
  - 同輪：`assets.generatedAt` 解析為時間點（有 offset 依 offset；無 offset 視為 `Asia/Taipei`），換算 `Asia/Taipei` 日期；不等於 `tradingDate`，或欄位缺／無法解析 → `status=UNAVAILABLE`、`reason=POOLED_ASSETS_NOT_SAME_ROUND`，所有數值 null。台北日界線：`2026-11-15T16:30:00Z` 屬 2026-11-16；`2026-11-15T15:59:59Z` 屬 2026-11-15。
  - 逐列 `assets.snapshot.deposits[]`：只取 `currency` 精確等於 `<幣別>` 或 `TRANSIT_<幣別>` 的列。金額：TWD 取 `amount`，USD 取 `originalAmount`（含 `TRANSIT_USD`）。任一取用列金額缺、null 或非數字 → `UNAVAILABLE`、`reason=POOLED_DEPOSIT_AMOUNT_UNAVAILABLE:<deposit id>`，數值 null。
  - 在途只看 `currency` 是否以 `TRANSIT_` 開頭（**不看 depositType**）。在途列：`knownPayables += min(amount, 0)`（負數或 0，正數在途排除）。非在途列：`settled += amount`；`depositType` 精確等於 `定存` 或 `美元定存` 時 `term += amount`（**精確字串比較，不用 contains，不限正數**）。
  - 底線：TWD `termFloor = termFloorBase × inflationFactor`、`totalFloor = totalFloorBase × inflationFactor`（BigDecimal 精確乘，不捨入）；USD 兩者為 `0`。
  - `term < termFloor` → `status=BLOCK`、`reason=POOLED_TERM_BELOW_FLOOR`、`headroom=null`，其餘數值照填。
  - 否則 `headroom = settled + knownPayables − max(totalFloor, termFloor)`；`headroom ≥ 0` → `status=PASS`、`reason=ALL_BANK_SETTLED_DEPOSITS_D152`；`headroom < 0` → `status=BLOCK`、`reason=POOLED_TOTAL_BELOW_FLOOR`（headroom 照填負值）。
  - 沒有任何該幣別列時 settled／knownPayables／term 為 0，照上式計算（TWD 得負 headroom、USD 得 0）。
- [ ] 624.2 `DailyDecisionCalculator` 新增重載 `public static BigDecimal inflation(BigDecimal annualRate, LocalDate baseDate, LocalDate date)`，原 `inflation(Policy,LocalDate)` 改為委派它；公式與 `BigDecimal.valueOf(StrictMath.pow(ONE.add(rate).doubleValue(), DAYS.between(base,date)/365.25))` 位元一致，不得改用 `Math.pow` 或 BigDecimal 冪次。
- [ ] 624.3 `SrppReportModuleCalculator.funding(...)`：在既有迴圈之後，對 `TWD`、`USD` 各呼叫一次 `SrppPooledFunding.evaluate(a, f.facts().date(), c, p.termFloor(), p.totalFloor(), DailyDecisionCalculator.inflation(p, f.facts().date()))`，並：
  - 輸出 fact `pooledPostBuyHeadroom`，`subject=<幣別>`、`unit=<幣別>`。有值 → `quality=EXACT`、`reason=null`。null → `quality=UNAVAILABLE`、`reason` = Result.reason（`POOLED_TERM_BELOW_FLOOR`／`POOLED_ASSETS_NOT_SAME_ROUND`／`POOLED_DEPOSIT_AMOUNT_UNAVAILABLE:<id>`），並加入模組 `missing` 為 `<reason>:<幣別>`（BFF 不允許 COMPLETE 模組含 UNAVAILABLE fact）。不得沿用 `m.fact()` 預設的 `FIELD_SOURCE_UNVERIFIED` 原因；需在 `M` 加一個可指定 reason 的 fact 方法，或在 funding 內直接組 ObjectNode。
  - 輸出收據 `ruleId = "D152_POOLED:<幣別>"`、`status` = Result.status（PASS／BLOCK／UNAVAILABLE）、`reason` = Result.reason、`inputRefs = ["assets","policy"]`（**不含** report-reservations、topup-state），`values` 依序為 `pooledSettledBalance`、`pooledKnownPayables`（≤0，Python 符號）、`pooledTermBalance`、`termFloor`、`totalFloor`、`pooledPostBuyHeadroom`，全部 `subject=<幣別>`、`unit=<幣別>`，null 值者 `quality=UNAVAILABLE` 且 reason 非空。
  - 新收據與 fact **不得**受 `valid`（預留）或 `allPending`（掛單）影響；預留／掛單來源缺時仍照算。
  - 既有 fact（settledBalance、payable、excludedReceivable、termBalance、inflationFactor、termFloor、totalFloor、reservedFunds、reservedFees、deployableCapacity）、`CURRENCY_POOL` 收據、模組 scope `CURRENCY_POOL_ALL_STRATEGY_RESERVATIONS_PENDING_AND_PROTECTED_CAPACITY` 與 sources 清單原樣不動。
- [ ] 624.4 OpenAPI 來源 `docs/openapi/docker-external-api.yaml` 的 `components.schemas.SrppReportFactFundingCapacity.properties.name`：`enum` 末尾加 `pooledPostBuyHeadroom`；`description` 的允許值清單同步加上，並補一句「pooledPostBuyHeadroom：D-152 全行合併買後剩餘額度，不扣預留與掛單，逐幣別；cashRequired ≤ 此值即等同本機 pooled_funding PASS；定存低於底線時為 null（POOLED_TERM_BELOW_FLOOR），判定以收據 D152_POOLED:<幣別> 為準；VT 建倉腿（國泰台幣複委託）與改台幣扣款的 D-182 定期定額計入 TWD」。不改其他 schema。之後執行 `ruby scripts/render-9090-openapi-docs.rb --asset-only` 重產 `docs/openapi/9090-api-swagger.md` 與 `backend/src/main/resources/srpp/9090-api-swagger.md`（兩檔為產物，禁止手改）。
- [ ] 624.5 BFF `bff/src/main/java/com/steven/assets/bff/publicsrpp/SrppReportResponseValidator.java` 第 22 行 `FACT_NAMES` 的 `FUNDING_CAPACITY` 集合加 `pooledPostBuyHeadroom`。不放寬其他檢查。
- [ ] 624.6 golden 產生器 `scripts/generate-srpp-pooled-headroom-golden.py`（仿 `scripts/generate-srpp-reserve-golden.py`）：`--srpp-root <絕對路徑>`（必填）、`--check`（不寫檔，與已追蹤檔不同即 `SystemExit("POOLED_HEADROOM_GOLDEN_DRIFT")`）；`sys.dont_write_bytecode = True`；以 `importlib` 從 `<srpp-root>/scripts` 載入 `trade_blocker_policy`、`budget_accounting`（需把該目錄放進 `sys.path`，因參考實作內部 import `asset_reconciliation`、`execution_policy`）；讀 `<srpp-root>/assumptions/model_inputs.json` 的 `stock_purchase_funding_policy_d150`。輸出 `backend/src/test/resources/srpp/pooled-post-buy-headroom-golden.json`，`schema = "D152_POOLED_HEADROOM_GOLDEN_V1"`，`json.dumps(..., ensure_ascii=False, sort_keys=True, indent=2) + "\n"`，Decimal 一律輸出字串。成功印 `POOLED_HEADROOM_GOLDEN_PASS:<案例數>`。
  - 資產：以 SRPP `data/history/D139-2026-08-30/latest_assets_snapshot.json` 為底，整段換掉 `snapshot.deposits`（複製第一列取得必要欄位，改 `id`、`currency`、`depositType`、`depositDisplayName`、`amount`、`originalAmount`），並以 Decimal 差額同步調整 `snapshot.totalDeposit`、`liveAssets.totalDeposit`、`snapshot.totalAssets`、`liveAssets.liveTotalAssets`，使 `validate_assets` 通過；`generatedAt` 依案例設定。
  - 每案每幣別 headroom 由產生器依 624.1 公式自算，**然後必須以參考實作證明**：對探針 `cashRequired ∈ {0, 1, headroom, headroom+0.0001, max(headroom−0.0001,0), 3000000}`（headroom 為 null 或負時只取 0、1、3000000）逐一呼叫 `pooled_funding(assets, tradingDate, currency, str(probe), model)`，`status == "PASS"` 必須等於 `headroom is not None and probe <= headroom`，任一不符 `SystemExit("POOLED_HEADROOM_EQUIVALENCE_FAILED:<case>:<currency>:<probe>")`。同輪不符案例需確認參考實作丟 `FUNDING_SAME_ROUND_ASSETS_REQUIRED`。
  - 每案輸出：`name`、`tradingDate`、`generatedAt`、`floors`（termBase、totalBase）、`inflationFactor`（`str(reference_price_level(...))`）、`assets`（完整）、`expected.<幣別>`（status、reason、settled、knownPayables、term、termFloor、totalFloor、headroom）、`probes[]`（currency、cashRequired、referenceStatus、post_total、post_term）。
  - 至少下列 15 案（皆須產出）：
    1. `fixture_baseline`：D139 原始存款不換、tradingDate 2026-11-16（含負在途 −124,200 等與 USD 美元定存）。
    2. `base_date_total_floor_exact`：2026-08-31（指數 1），定存 900,000＋活存使 `S+P = 1,550,000` → headroom 0；探針 0 PASS、0.0001 BLOCKED。
    3. `term_exactly_at_floor`：2026-08-31，定存恰 800,000 → 非 null。
    4. `term_below_floor_by_one`：2026-08-31，定存 799,999、活存 5,000,000 → null、`POOLED_TERM_BELOW_FLOOR`，任何探針 BLOCKED。
    5. `negative_transit_reduces_headroom`：TRANSIT_TWD −300,000 與 −1,793。
    6. `positive_transit_excluded`：TRANSIT_TWD +50,000，headroom 不增加。
    7. `negative_headroom_total_shortage`：`S+P` 低於 totalFloor、定存達標 → 負 headroom、BLOCK `POOLED_TOTAL_BELOW_FLOOR`、探針 0 BLOCKED。
    8. `usd_original_amount_zero_floor`：USD 列 amount（台幣等值）與 originalAmount 不同、TRANSIT_USD originalAmount −10 → headroom = Σ原幣 − 10。
    9. `usd_no_rows`：無任何 USD 列 → USD headroom 0（探針 0 PASS、1 BLOCKED）；TWD 照算。
    10. `twd_no_rows`：無任何 TWD 列 → TWD term 0 < 底線 → null。
    11. `live_index_2026_11_16`：非 1 指數的精確底線（`800000 × 1.0047017700289897`）。
    12. `synthetic_term_floor_exceeds_total_floor`：合成 model（termBase 2,000,000、totalBase 1,000,000），證明 `max(totalFloor, termFloor)` 取 termFloor，且 term_after 規則被吸收。
    13. `non_exact_term_type_ignored`：TWD `定存單` 2,000,000＋`定存` 700,000 → 參考實作 term 700,000 → null（現行 Java termDeposits 會算成 2,700,000，此案鎖住 must-fix）。
    14. `deposit_type_prefix_is_display_only`：`currency=TWD`、`depositType=TRANSIT_DISPLAY`、amount 100,000 → 算入 settled（現行 Java `transit()` 會排除，此案鎖住差異）。
    15. `stale_assets_taipei_boundary`：tradingDate 2026-11-16，generatedAt `2026-11-15T15:59:59Z` → UNAVAILABLE；另一子案 `2026-11-15T16:30:00Z` → 同輪照算。
- [ ] 624.7 Java 測試：在既有 `backend/src/test/java/com/steven/assets/service/srpp/report/SrppReportModuleCalculatorTest.java` 新增 golden 測試方法 `pooledHeadroomMatchesOriginalPythonGolden`（不另建測試類，spec-check 會擋尚不存在的測試類名）：讀 `/srpp/pooled-post-buy-headroom-golden.json`，斷言案例數 ≥ 15；每案每幣別以 `SrppPooledFunding.evaluate` 計算，status、reason 字串相等，每個數值 `compareTo == 0`（null 對 null）；斷言 `DailyDecisionCalculator.inflation(new BigDecimal("0.0225"), 2026-08-31, tradingDate)` 與 golden `inflationFactor` `compareTo == 0`；對每個 probe 斷言 `(headroom != null && cashRequired.compareTo(headroom) <= 0) == "PASS".equals(referenceStatus)`。
- [ ] 624.8 `SrppReportModuleCalculatorTest` 新增整合案例（沿既有 `deployableCapacity` 測試的 frozen 組法）：(a) 預留與掛單來源皆缺時 `deployableCapacity` 仍為 null、`CURRENCY_POOL` 仍 UNAVAILABLE，但 `pooledPostBuyHeadroom` TWD／USD 為 EXACT 數值、`D152_POOLED:TWD` 收據 PASS 且 inputRefs 恰為 `["assets","policy"]`；(b) 加入預留後 headroom 不變；(c) 定存低於底線時 fact UNAVAILABLE＋`POOLED_TERM_BELOW_FLOOR`、收據 BLOCK、模組 missing 含 `POOLED_TERM_BELOW_FLOOR:TWD`、模組 status 非 COMPLETE。BFF `SrppReportResponseValidatorTest` 新增：含 `pooledPostBuyHeadroom` 的 FUNDING_CAPACITY 通過，未知名稱仍拒絕。
- [ ] 624.9 因 `SrppReportModuleCalculator.java` 位元組改變，執行 `python3 scripts/generate-srpp-formula-v3.py --srpp-root /Users/steven/Project/SRPP/.claude/worktrees/asset-management-logic-review-d1cbbc` 重產 `spec/fixtures/srpp_formula_manifest_v3.json`、`SrppFormulaCatalog.java` 生成區塊與 `bff/src/main/resources/srpp/formula_manifest_v3.json`。本任務不新增 calculations 條目。
  - **D-234 四任務共用的重產順序（定案）**：t623／t624／t625／t626 都會改 `SrppReportModuleCalculator.java` 位元組，四支任務在**同一分支**依 t623 → t624 → t625 → t626 順序實作；本任務接在 t623 之後（t623 已新增 `satelliteBuild` 條目），結束時以同一個 SRPP root（`/Users/steven/Project/SRPP/.claude/worktrees/asset-management-logic-review-d1cbbc`，含 D-234 `execution_policy.py`／`trade_blocker_policy.py`；SRPP D-234 合併到 main 後改用 `/Users/steven/Project/SRPP`）重產一次 manifest 與本任務 golden 並以 `--check` 證明；不得沿用 t623 的 manifest digest。
- [ ] 624.10 不得做：不改 `deployableCapacity` 公式、`CURRENCY_POOL`、共用 `transit()`／`term()` helper、`DecisionInputCaptureAdapter` 的 termDeposits；不新增 Liquibase、路由或排程；不呼叫券商、不寄信、不刷新資料；不手改任何 Swagger 產物。

## 驗證

```bash
cd /private/tmp/asset-d234-calc-migration
bash scripts/spec-check.sh
python3 scripts/generate-srpp-pooled-headroom-golden.py --srpp-root /Users/steven/Project/SRPP/.claude/worktrees/asset-management-logic-review-d1cbbc --check
python3 scripts/generate-srpp-formula-v3.py --srpp-root /Users/steven/Project/SRPP/.claude/worktrees/asset-management-logic-review-d1cbbc --check
ruby scripts/render-9090-openapi-docs.rb --asset-only --check
ruby scripts/tests/docker-external-api-openapi-test.rb
python3 scripts/tests/srpp-formula-v3-test.py
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=SrppReportModuleCalculatorTest test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml -Dtest=SrppReportResponseValidatorTest test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
```

正式上線（屬 D-234 發布鏈，非本任務單獨執行）：Swagger 文字變動走 Task 504 鏈——SRPP 端以 `scripts/sync_report_consumer_schema.rb` 同步 `assumptions/srpp_report_calculations_schema.json`、`sync-srpp-swagger-identity.py` 更新 Swagger 身分 → 規則包重建 → 9090 登錄 → `docker compose -p asset-management build --no-cache business-services bff` 與 `up -d --no-deps --force-recreate business-services bff`，再從 SRPP 實測 `GET /api/public/srpp/report-calculations` 的 FUNDING_CAPACITY 含兩筆 `pooledPostBuyHeadroom` 與 `D152_POOLED:TWD/USD` 收據。後續 SRPP 工作（不在本任務）：`verify_srpp_report_calculations.py` 的 `fact_binding` 增加 `pooledPostBuyHeadroom`（幣別欄位、rule `D152_POOLED:<subject>`、refs {assets, policy}）與收據 refs 對照；consumer 改為只比較 `cashRequired ≤ headroom` 並保存收據，BLOCK＋`POOLED_TERM_BELOW_FLOOR` 視為已驗證 BLOCKED、不回退本機。

## 完成報告

（實作者做完後回填：實際改了哪些檔、驗證輸出、與原計畫的偏差及原因。）
