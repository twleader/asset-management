# SRPP 共用計算結果 API — 資產管理系統實作交接規格

版本：1.0.0-proposal.1｜日期：2026-09-23｜狀態：**提案，尚未實作、部署或接入 SRPP**

## 1. 交付範圍與使用者指定邊界

本規格供「資產管理系統」新增 API。固定公式與資料彙整由程式完成，Claude／Codex 未來接入後讀取同一份有版本、可重播的計算結果。

**本階段不修改 SRPP。** 程式、規則、規則包、排程、模型設定及 `docs/9090 Port API Swagger.md` 都維持原樣。等 API 實作驗收完成，再另做 SRPP 接入。未接入前，新 API 的失敗或缺資料不影響現行日報。

資產管理的文件產生器可能連帶覆寫 SRPP Swagger。依使用者本次明確指示，實作期間只更新資產管理自己的 OpenAPI／Swagger，待交付的 SRPP 鏡像另存於專案外；**不要執行會覆寫 SRPP 的產生步驟**。接入階段再同步。

本規格不新增投資政策，不授權交易，不宣稱全政策編譯等價，不提供可下單的最終張數或限價。

## 2. 基準與交付檔案

| 項目 | 查閱基準 |
|---|---|
| SRPP commit | `46bba5750f27b6676ac15069fd4f4008bd6da7f9` |
| SRPP 規則包 | `842ba22f07a4061eb7e3216579ff84e3e3f983aed0d3181185614958edc2d43f` |
| 執行索引 | D-178／schema 3，非完整政策編譯器 |
| 資產管理 main | `06e06d8b`；實作前須重讀當下版本 |
| 現有外部契約 | OpenAPI 3.1.0／文件版本 1.13.0 |

- `openapi.yaml`／`openapi.json`：內容相同，所有 schema reference 均在檔內，可獨立匯入。
- `examples/`：合成完整、部分可用、過期及錯誤範例；不是使用者持倉或交易建議。
- `ACCEPTANCE.md`：實作及接入前驗收清單。
- `validate_contract.py`／`requirements-validation.txt`：文件與範例驗證器。
- `validation.json`：本次契約驗證結果，不代表 API 已上線。
- `SRPP_UNCHANGED.json`：SRPP 與現有排程未更動的交付證據。

## 3. 新增一條精確唯讀路由

`GET /api/public/srpp/daily-context`

沿用 9090 loopback gateway，部署時同步加入 Tailscale 同名 exact path。不是公網 API；沿用現有 BFF 及 owner selector。

| Query | 必填 | 意義 |
|---|---|---|
| tradingDate | 是 | ISO 日期，本輪交易日 |
| slot | 是 | 只允許 `09:05`／`11:40`，不以舊任務 ID 推測 |
| policyBundleSha256 | 是 | 呼叫端期望的已登錄規則包 |
| email | 否 | 沿用 assets/latest；省略為 configured-admin，指定則只接受 ACTIVE 帳號 |
| view | 否 | `summary`（預設）／`evidence` |
| packageId | 否 | 固定不可變套件；evidence 必填 |
| sourceId | 否 | 只在 evidence 使用且必填 |

禁止 GET body、未知／重複 query、任意來源 URL、檔案路徑、SQL、公式、分數或 caller 自報 PASS 收據。

- **最新摘要**：不帶 packageId，只接受台北今天，取同 owner/date/slot/policy 的最新 CURRENT 套件。未到該時段回 503，不能冒用較早時段。
- **固定摘要**：帶 packageId，各項 identity 必須一致。可讀保留中的歷史套件，但過期只能回放，不供當輪使用。
- **凍結來源**：evidence 回傳指定套件當時的單一 JSON 來源字串及 SHA-256；讀取不補抓資料。
- 其他 owner 的 package 與不存在相同，回 404；UUID 本身不授權讀取。重播也須確認 owner 仍 ACTIVE。
- 建議保留最近 7 個日曆日，可設定延長。清理只由背景維護進行。

以下只是尚未部署的請求格式：

```http
GET /api/public/srpp/daily-context?tradingDate=2026-09-24&slot=09%3A05&policyBundleSha256=<64位雜湊>
GET /api/public/srpp/daily-context?tradingDate=2026-09-24&slot=09%3A05&policyBundleSha256=<同上>&view=evidence&packageId=<UUID>&sourceId=assets
```

## 4. 架構、產生與更新

```text
既有 DB／canonical 行情快取／已驗證政策登錄
  → 背景 producer：凍結來源 → 共用公式 → 驗證 → 原子發布
  → owner＋交易日＋時段＋規則包的最新 package pointer
  → 9090 → BFF → Service → Repository 唯讀取得
  → 未來 SRPP adapter 核對 → 必要即時資料檢查 → 報告
```

1. 市場資料仍由既有 external-materials producer 負責。新 GET 不得觸發券商、Yahoo、抓取、refresh、通知、匯出或 writer。
2. 計算放業務 Service，Controller 只處理 HTTP；BFF 不直接查 DB。公式不用 LLM。
3. 先確認台股權威交易日曆；非交易日／未知不產生當輪套件，不讀下游資料。
4. 個人 DB 資產一致讀取；行情／技術使用 immutable capture 或 canonical revision。單一 DB transaction 不代表 Redis 與 DB 已原子一致。
5. 保存來源 revision、dataAsOf、body hash；發布前核對 revision vector。來源變更就取消該次發布、背景重算，不能混新資產與舊衍生值。
6. 資產 identity 與八項對帳通過即可發布部分可用套件，不等待所有稅務／技術來源。
7. 最新 pointer 原子指向完整 immutable package。Redis 若使用，必須核對 DB 發布 revision。
8. generatedAt 不會讓舊來源變新。GET 不觸發計算、不延長有效期，冷啟動沒有結果回 503。
9. 各模組可按依賴重用。formula manifest 明列實際讀取的欄位投影、排序、版本；不能排除價格、股數、資料時點、幣別、給付日或公司行為等有效輸入。

| 變更 | 必須失效／重算 |
|---|---|
| 快照、持股、存款、待付款、成交更新 | 對帳及所有相依配置／收益／資金值 |
| 估值價格、匯率 revision 更新 | live 資產及使用該估值的模組 |
| 完成日 K 線或公司行為修正 | 受影響技術、還原價及相關分類 |
| 股利估計、利率、ETF 分配組成 | 受影響收益與稅務 |
| 日期、日曆、規則／公式版本 | 對應日期及版本套件 |

建議既有資料事件＋背景排程保底，合併同一修訂的重算需求。不能前晚算一次就當整天有效，也不能把 HTTP 查詢時間當來源更新時間。

## 5. 回應與數值契約

摘要包含 `context`（不可變）、`contextContentSha256` 與 `freshness`（本次查核）。固定五個模組必須都出現：
`assets`、`allocation`、`cashIncome`、`funding`、`completedTechnicals`。

| status | 意義 |
|---|---|
| COMPLETE | 模組所列必備值有可驗證依據；不是交易許可 |
| PARTIAL | 有可用值及具名缺口；data 非 null |
| UNAVAILABLE | data=null，原因必填 |
| context.coverage | 全部模組 COMPLETE 才是 COMPLETE，否則 PARTIAL |

數值以 canonical Decimal 字串傳輸，例如 `"1600000"`、`"0.05"`、`"-31004"`。禁止 boolean、NaN、Infinity、指數、千分位、前導零、負零或不必要尾端零。計算精度／捨入沿用已登錄公式，不因 API 傳輸先轉 double。一般權重是 0–1 比例，差距可負，KD 為 0–100 點。

Metric 必帶 value、unit、quality、reasonCodes、sourceIds：

- EXACT：相對凍結來源的確定值。
- ESTIMATE：包含估計，例如一年配息。
- UPPER_BOUND／LOWER_BOUND：明確上／下界；健保費上界不能讓稅後所得也冒稱上界。
- UNAVAILABLE：value=null，具名原因；不得以零代替未知。
- COMPLETE 可含 ESTIMATE／界限值，但不能含 UNAVAILABLE、缺列或未映射。

新 API 日期時間必含時區。原始 evidence 保留原格式，offset-less 來源的時區轉換依 formula manifest；不能猜成 UTC。

## 6. 五個計算模組

### 6.1 assets：資產對帳與存款分組

來源使用與 `/api/assets/latest` 相同的 domain service／資料切面，不建立另一套資產帳本。

參考：SRPP `scripts/asset_reconciliation.py::validate_assets/reconcile`、資產管理 `SnapshotAggregateCalculator`。

snapshot/live ID、日期、holding ID 集合、市場、代號與股數須一致。股數精確相等，不使用金額容差。八項 checks 固定：

`snapshot_deposits, live_deposits, snapshot_funds, live_funds, snapshot_stocks, live_stocks, snapshot_total, live_total`。

容差沿用 `max(0.01, max(abs(detail), abs(reported)) × 0.0001)`；不得另放寬。

- amount 已是台幣，不再乘匯率；待付款保留負號。
- nullable bankId／bankName 不得讓該列消失；不支援的產品／幣別要具名失敗。
- identity／對帳不符不得發布新套件，舊套件只能 STALE。
- 原始 assets payload 必須可重播。摘要不取代原始證據。

### 6.2 allocation：總曝險與目標差距

固定 `TOTAL_EXPOSURE_REFERENCE`，只提供描述性差距：

```text
denominatorTwd = 同套件 liveTotalAssets（必須 > 0）
currentWeight = exposureTwd / denominatorTwd
gapWeight = targetWeight - currentWeight
gapValueTwd = targetWeight × denominatorTwd - exposureTwd
```

目標精度依已登錄 `model_inputs.json.detailed_target_allocation_2027`；CSV 用於說明／核對，不用截短小數覆蓋 JSON。

版本化 mapping 涵蓋股票／基金／存款。輸出持有資產與政策目標的 assetKey 聯集；未持有但有目標者為零曝險。未映射的 target／gap 為 UNAVAILABLE，不能猜零目標；跨券商相同市場代號合併並保留來源 holding IDs，不合併不同市場。

`shortTermSleeveSeparated=false` 固定揭露未套私有短線帳本。D-173 核心／短線拆分、群組上限、曝險及可買容量仍由原 SRPP 流程處理；總曝險差距不能當核心可買張數。

### 6.3 cashIncome：來源收益、可支配現金與稅後估計

來源為 snapshot 股票／基金的 estimatedDividend、存款 estimatedAnnualInterest；加總對帳 snapshot.estimatedAnnualDividend。

| API 欄位 | SRPP current_cash_income 的 current 輸出 |
|---|---|
| stockAndEtfDistributions | stock_and_etf_distributions_twd |
| fundDistributions | fund_distributions_twd |
| depositInterest | deposit_interest_twd |
| sourceAccruedAnnualIncome | source_accrued_annual_income_twd |
| permanentTermInterestReinvested | permanent_term_deposit_interest_reinvested_twd |
| spendableAnnualGross | gross_annual_cash_income_twd |
| taiwanIncomeTaxOrRefund | estimated_taiwan_dividend_income_tax_twd |
| usWithholding | estimated_us_withholding_twd |
| additionalBasicTax | estimated_overseas_amt_twd |
| supplementaryNhi | estimated_supplementary_premium_twd |
| afterAllTaxAnnualCashIncome | after_all_tax_cash_income_twd |

```text
sourceAccruedAnnualIncome = stocks + funds + deposit interest
spendableAnnualGross = sourceAccruedAnnualIncome - permanentTermInterestReinvested
afterAllTax = spendableAnnualGross - Taiwan tax/refund - US withholding - additional basic tax - NHI
```

保留可能的負數退稅。null 是缺漏，已知部分只能是具名 subtotal，不可宣稱完整年度合計。

參考 `scripts/current_cash_income.py::calculate`、共同 helper 及 `verify_output`。不得以固定稅率取代原計算；健保沿用同受益人、同發行人、同次給付合併，不按券商各自套門檻。

**不得假設 DB 已具備所有依據。** ETF 分配組成、給付事件、稅務假設、十年歷史／代理期別、個別定存給付日可能不完整。永久定存續存利息排除及健保上界要保留原方法／品質。

首階段可只驗證基礎加總，其他欄位為 null＋`NET_CALCULATION_NOT_VERIFIED`，cashIncome=PARTIAL。不能填零求 COMPLETE。

現有 calculate 還包含退休目標投影等依賴；若分離目前現金流子集，先以固定完整輸入比對原輸出再部署。本 API 不跑正式蒙地卡羅，不宣稱退休成功率，不要求日報等待十年資料重新抓取。

### 6.4 funding：買前資金基礎

參考 current_cash_income 的 tactical_funding_capacity 子集及已登錄 D-150/D-152 參數。

| 欄位 | 定義 |
|---|---|
| snapshotAllCurrencyDepositTwdEquivalent | 所有幣別存款的台幣等值，僅供對帳 |
| twdTermDeposits | 依原 _is_twd_term_deposit 分類 |
| twdTotalIncludingNegativeTransit | currency=TWD amount＋負值 TRANSIT_TWD |
| excludedPositiveTransit | 尚未當作已交割資金的正值台幣在途款 |
| usdDepositsTwdEquivalent | USD 存款的台幣等值，不能當台幣購股餘額 |
| permanentTermFloorNominal | 既有按日通膨定存底線 |
| totalTwdDepositFloorNominal | 既有按日通膨台幣總存款底線 |
| headroomAboveTotalFloor | max(台幣總存款－總存款底線, 0) |
| termFloorMet | 台幣定存是否達同日底線，非全部資金閘門 |

calculationDate 沿用 reference calculator 的快照日期，必須揭露。與本輪交易日不同時，標 PARTIAL／`CALCULATION_DATE_MISMATCH`，不採作本輪底線；不能暗改 today 求通過。

headroom 只是買前差額。買後底線、成交去重、額度、費用、換股準備金、短線帳本、幣別及解約條件仍須本輪檢查。`tradingAuthorized=false` 永遠固定。

### 6.5 completedTechnicals：完成交易日技術事實

- 台股持股與政策台股目標聯集；缺資料標的也留 UNAVAILABLE 列。
- requiredCompletedSession 由權威日曆判定，不以日期減一。
- MA5／20／60、最近三個完成日 K/D、最近二十個還原收盤與其最高點；必附公式版本、調整基礎及公司行為證據完整性。
- 還原價邏輯對齊 `execution_policy.adjusted_completed_closes`。KD 保留已驗證基礎，不能混 raw KD 與另一套 adjusted KD。
- MA60 的來源 evidence 必須包含足夠歷史，不能只以二十天資料宣稱可重播。
- 日數不足、除權息／分割未確認、重複／未來／亂序 bar、來源缺口應局部降級，不補值、不移用週 K。
- highOf20AdjustedCloses 是 D-170 原料，與 D-174 armed high-water state 不同。
- **不輸出 CORE_SELL_READY，不共用或寫入 Claude／Codex watch state。** D-174 仍依當輪 bid、盤中雷達、完成日轉弱、公司行為窗口與各自 state 判斷。

## 7. 政策與公式登錄

```text
policyBundleSha256
 → calculationPolicySha256
 → formulaSetSha256 / formulaVersion
 → calculation IDs、輸入欄位投影、mapping、品質規則、驗收 fixture hashes
```

Query hash 只是期望版本；伺服器必須查已驗證 registry，不能原樣 echo 冒充支援。未知回 409 POLICY_UNSUPPORTED。

calculationPolicySha256 對實際參數文件做 JCS SHA-256；formulaSetSha256 對公式/build artifact 版本 manifest 做 JCS SHA-256。manifest 納入 POLICY evidence。

只改敘述的新 bundle 可以經離線驗證後登錄到相同公式；GET 不自動接受新 hash。D-178 停用的 daily_decision_engine 不得當生產引擎；scope 固定 LISTED_CALCULATIONS_ONLY。

## 8. 不可變來源與雜湊

採 [RFC 8785 JCS](https://www.rfc-editor.org/rfc/rfc8785.html) 配合 SHA-256；金融精度用字串保留。

1. `contextContentSha256 = SHA256(UTF8(JCS(context)))`；排除 root freshness 與 hash 自身。
2. sources 按 sourceId；sourceIds 按字典序；配置按 assetKey；技術按 market/symbol；bars 按日期升冪；depositGroups 按 currency/depositType/bankId（null 最前）；ID 陣列按字串序。重複主鍵拒絕。
3. `bodySha256 = SHA256(UTF8(evidence.body))`，body 為 HTTP JSON 解碼後的來源字串，不再 parse/stringify 後算。
4. AVAILABLE source 必有可回放 body，hash 必須一致；MISSING 明列 null，不能偽造空成功資料。
5. 原 asset_reconciliation.payload_hash 使用既有 Python canonical 方法；新 hash 不能冒充它，adapter 仍依原方法另算。
6. **contextContentSha256 不等於 INPUT_SNAPSHOT_SHA256**；後者還含當輪行情、雷達、資金、品質及 core sell state。只有完整實際輸入一致才可比較 Claude／Codex。
7. package 發布後不改。來源修正產生新 package，GET 不倒寫讀取時間。

## 9. 新鮮度、錯誤與降級

CURRENT 只代表來源修訂／日期／policy 仍相符，不替代行情與五檔時效。STALE 列出 changedSourceIds；UNKNOWN 表示無法核對。

latest 只回 CURRENT；必要修訂改變回 409，無法核對回 503。pinned 可回 STALE／UNKNOWN 供稽核。

| HTTP／情境 | 未來 SRPP 行為 |
|---|---|
| 200 CURRENT＋PARTIAL | 只採已驗證且依賴一致欄位；缺項走原流程 |
| 200 STALE／UNKNOWN | 不採作本輪值 |
| 400 INVALID_REQUEST | 記錄整合錯誤，原流程；不反覆重送 |
| 404 CONTEXT_NOT_FOUND／SOURCE_EVIDENCE_NOT_FOUND | 原流程 |
| 409 POLICY_UNSUPPORTED／CONTEXT_STALE／CONTEXT_IDENTITY_MISMATCH | 原流程，不自行 rebuild 規則包 |
| 409 NON_TRADING_DAY | 回原權威日曆核對，遵守既有停止條件 |
| 503 CALENDAR_UNAVAILABLE／OWNER_UNAVAILABLE／CONTEXT_NOT_READY | 原流程依原資料與停止條件處理 |
| 500／502／504／逾時／非 JSON | 一次失敗即原流程，不等待暖機 |

應用錯誤採 [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457)。Nginx 502/504 仍可能為 HTML。exact path 非 GET=405＋Allow: GET；尾斜線／子路徑／matrix=404。

缺列、未知 enum、額外欄位、型別錯誤由 BFF typed validator 拒絕，不能 200 放行。成功與應用錯誤為 Cache-Control: private, no-store，不使用 304；服務內部快取另行管理。

## 10. 未來接入順序（本次不執行）

1. 保留 calendar → Swagger → policy/index checks。
2. adapter 預設停用，shadow 比對後啟用；單次等待預算建議 5 秒，失敗不重試等 package。
3. 核對 owner、snapshot/date/slot、版本、schema、引用、digest 與 CURRENT。
4. 初期仍保留原 assets/latest 及八項 L0 對帳。比對已規範化實際來源內容，不能只看 snapshotId 相同。
5. 原資料若更新，放棄相依舊模組、用原計算；不得拼兩個 snapshot。
6. 只替代通過獨立差分的確定性計算。缺漏局部降級，原硬閘門與時效不變。
7. 成交去重、雷達 detail、可交易性、五檔、最終行情、D-173/D-174 state、張數與限價沿用原流程。
8. 依原 record_report_inputs 產生完整指紋；核心／附錄共享當輪 facts。
9. 模板、HTML/plain、未寄出 Gmail 草稿與 MIME 讀回照舊。
10. 使用者另行要求接入後，才同步 SRPP Swagger、規則、測試、索引／雜湊及四入口。新 API 本身不是新 L0。

## 11. 分階段交付與效能

**首個可交付 API**：route、registry、來源凍結、五模組狀態；assets／allocation／基礎收益可用。尚未完成欄位明示 PARTIAL／UNAVAILABLE，不宣稱全模組完整。

**完整計算版**：funding、完成日技術與 current cash income 子集都通過相依來源檢查及 reference parity 後，才升對應 COMPLETE。

**SRPP 接入**：另一次工作，先 shadow 再啟用。比對需包含數值、精度、null、原因碼、邊界，不只比 hash 或讓新舊共用同一段預期邏輯。

效能目標是提案，尚未量測：同機 9090 熱讀 summary p95 ≤ 2 秒、單一 evidence p95 ≤ 5 秒；重複 GET 不重算。以 30／100 持股及目標量測 producer、各模組、API p50/p95、bytes、失敗及快取命中。實際日報還包含文件、研究、報表與 Gmail，不能拿 API 速度代替整輪提速證據。

## 12. 資產管理實作同步項目

遵循該專案 SDD requirement／design／task／獨立 review。此文件不是已接受的投資政策。

- Service／Repository／typed DTO、BFF owner 隔離與 strict validator。
- Producer 與 registry。若新增結果表，列明為可重建的派生快取／immutable evidence，不寫 holdings、transactions 或銀行餘額；正式記錄正規化例外。
- Gateway exact allowlist、BFF security、frontend 禁用公開路由、Tailscale exact path。
- 現有基準 13 路由；本提案新增一條 GET。按實作時基準驗證原路由＋1，不刪其他路由。
- OpenAPI、DTO fixtures、strict validator、公開 API catalog 同步，避免新欄位造成 BFF 502。
- DB migration／schema.sql 如有變更，按規範驗證。
- Docker／實際端點、owner／錯誤／路由邊界／GET 零寫入 smoke test。
- **SRPP Swagger 鏡像先另存交付；不覆寫 SRPP，不改其他 SRPP 檔案或排程。**

## 13. 標準與驗證界線

機器契約採 [OpenAPI 3.1.0](https://spec.openapis.org/oas/v3.1.0)，內容一致性採 RFC 8785，應用錯誤採 RFC 9457。本機公式是財務計算參考，本規格未從網路更新稅率或投資門檻。

文件與合成範例驗證不等於 API 已上線。Java／Python 數值等價、來源可用性、Docker／Tailscale、完整 SRPP 交付及提速均須實作後另行驗收。
