# [t362] 交易雷達 evidence 面板明示零權重資料源「不進評分」——九項市場數值特徵與美債殖利率

**對應 Requirements:** Requirement 98（交易雷達展開列同時呈現參與評分的因子與不參與評分的觀測值，兩者無任何區隔，使用者會誤以為商品價格、三大法人與美債殖利率已計入分數；須在畫面、匯出與公開 OpenAPI 契約明示，且不得移除任何既有數值）
**前置任務:** 無
**Liquibase changeset:** 無（不改資料庫）

> **編號說明**：本工作建檔時 `Requirement 97／Task 361`（FinMind 配息抓取視窗錨定日）正由平行 worktree
> `great-lehmann-336be2` 進行中，故依專案慣例讓號為 98／362。該工作已於 `c1f22070` merge 進 main，
> 本任務檔隨後合入，**編號連續、無缺號**。

## 背景

### 現在的問題

「今日交易雷達」展開任一標的，證據區同時列出兩類東西，而畫面沒有任何區隔：

- **會影響分數的**：均線、KD、MACD、RSI、乖離、量比、基本面、估值、盤勢 regime……
- **不會影響分數的**：市場數值特徵面板的九項數值，以及（債券標的）美債殖利率情境的數值

使用者合理地把它們當成同一回事。面板現有標題是「市場數值特徵（只呈現，不在前端重算）」——
這句只交代了「不在**前端**算」，完全沒交代「不進**後端評分**」，正是落差所在。

**這不是 bug，是既有設計。** V13 candidate 路徑尚未 promote，這些資料源只作 disclosure。
`spec/requirements.md` 的 Requirement 96「不在本次範圍」段已逐字預告：「不處理『商品價格／公債殖利率／
三大法人在 production 恆為零權重』（V13 candidate 路徑未 promote，屬既有設計，**其揭露落差另案處理**）」。
本任務就是那個另案。

### 零權重的機制（兩條互不相干的路徑，皆可逐行查證）

**路徑一：九項 typed market feature 整段不執行。**

`backend/src/main/java/com/steven/assets/service/TradingRadarRuleEngine.java:2022` 的區塊被包在：

```java
if (candidate != null && context != null) {
    // ... 取 shortMarketFeatureContribution()／mediumMarketFeatureContribution()
    // ... acc.add(featureWeight, featureContribution)
}
```

production 的唯一呼叫端是 `TradingRadarService.java:966` 的 `ruleEngine.evaluateStock(...)`，
而 `evaluateStock(StockInput)`（`TradingRadarRuleEngine.java:1378-1379`）的本體就是
`return evaluateStockInternal(input, null, null);` —— `candidate` 與 `context` 恆為 `null`，
整段從不進入。`BacktestService.java:849`／`:1233`／`:1234` 另有三個 `evaluateStock` 呼叫端，同樣走
`candidate == null` 的 baseline 多載，故「唯一」限定在交易雷達路徑。真正吃 candidate 的
`evaluateCandidate`，在 `backend/src/main/java` 底下的唯一呼叫端是 `BacktestService.java:2294-2295`；
**`evaluatePromoted` 除 `TradingRadarRuleEngine.java:1444` 的多載自我委派外，在 `backend/src/main/java`
底下沒有任何呼叫端**——無 service／controller 消費者，只在測試中被外部呼叫；promotion 機制尚未接進
production（實測 `grep -ran`，命中的五筆為 `:1417`／`:1491` javadoc、`:1439`／`:1455` 兩個多載宣告，
以及 `:1444` 的自我委派）。

涉及的九個 code（`TradingRadarMarketFeatureResolver.CODES`）：

```
IXIC_RET5, SOX_RET5, SPX_RET5, DJI_RET5, INDEX_VOLUME_RATIO20,
TW_INSTITUTIONAL_NET_TURNOVER, WTI_RET5, BRENT_RET5, GOLD_RET5
```

`TradingRadarMarketFeatureResolver.java:105-106` 的 Javadoc 自述：
"This is deliberately a candidate-only observation: the legacy V12 engine never calls it."

**路徑二：美債殖利率的權重 baseline 就是字面 `0.0`。**

`TradingRadarRuleEngine.java:2076-2080`：

```java
double treasuryWeight = candidateWeight(candidate,
        shortTerm ? RuleParameters.CandidateWeight.SHORT_TREASURY
                : RuleParameters.CandidateWeight.MEDIUM_TREASURY,
        0.0);
acc.add(treasuryWeight, treasuryContribution);
```

`candidateWeight(...)`（`:2220-2229`）第一行即 `if (candidate == null) return baseline;`（`:2222`）。
而 `Accumulator.add(double, Double)`（`:2318-2322`）為：

```java
void add(double weight, Double contribution) {
    if (contribution == null) return;
    sumW += weight;      // += 0
    sumWC += weight * contribution;   // += 0
}
```

`score()` 為 `50 + 50 × (sumWC / sumW)`，故 weight 為 `0` 時**連分母都不動**，影響嚴格為零，
不是「權重很小」。

### 三段語意不同，文案不得合寫成一句（本任務最容易做錯的地方）

| 對象 | `score` | `action` | 依據 |
|---|---|---|---|
| 九項 market feature | 無 | **無** | `marketFeatures` 在 production 只被**攜帶**不被**消費**：`TradingRadarEvidenceConfidenceResolver.java:542` 原樣塞進 `Evidence` record、`TradingRadarDto.java:501-509` 映射給前端、`TradingRadarExportService.java:689` 攤平進匯出。唯一會**讀值計分**的是 `TradingRadarRuleEngine.java:2032` 的 candidate 分支與 `BacktestService.java:3092`／`:3097`。六個 evidence group builder（`TradingRadarEvidenceConfidenceResolver.java:497-504`，例如 `:503` 註冊 `assetGroup(...)`、`:504` 註冊 `publicEventGroup(...)`）與 `TradingRadarEvidenceGate` 全檔皆無讀取端 |
| 美債殖利率**數值** | 無 | 無 | 上述路徑二 |
| 美債殖利率**可得性** | 無 | **有** | curve batch 是否完整 → `TradingRadarEvidenceConfidenceResolver.java:775` 的 `assetGroup(...)` 於 `:815` 建立 `c.add(component("bond_rate", status, 1, asOf, provider, reason))` → `ASSET_SPECIFIC` group 覆蓋率未達 `.70` 時關閉債券的買進閘門 |
| 美債殖利率**風險單位** | 無 | **有，方向固定** | production 的 `TradingRadarService.rateObservation(...)`（`:1246-1250`）一律回 `RateObservation.contextOnly(...)`，`riskUnit` 恆 `null` → risk 群 `asset_rate` 恆非 `AVAILABLE` → `TradingRadarEvidenceGate.riskEvidenceOpen()`（`:209`）對 `profile.bond()` 恆回 `false` → **債券的 REDUCE／EXIT 恆降級為候選揭露** |

**故對美債殖利率寫「不影響決策」或「僅供參考」是錯的**——數值不計分，但資料齊備與否確實動到閘門。
寫成前者會讓使用者在債券買進訊號被擋下時，找不到任何可對應的畫面說明。

### 第二種必須避開的誤導

**不得寫成「大盤數據不影響評分」。** 九項中的 `IXIC_RET5`／`SOX_RET5` 所代表的資訊，**另有一條路徑
確實進了 baseline**——`TradingRadarRuleEngine.java:2016` 的 `acc.add(marketWeight, factors.market())`
把大盤 regime 因子計入三軌分數，其權重 `SW_MARKET`／`SWG_MARKET`／`MW_MARKET` 皆非零。
正確表述是「**本面板的這些數值**不進評分，大盤趨勢另由盤勢因子計入」。

### 為什麼是「標示」不是「移除」

三條理由，任一單獨成立即足以否決移除：

1. Requirement 65 已明訂缺值須「明示『未納入』與原因」，其精神是揭露而非隱藏。
2. 這些 provenance 欄位（provider／as-of／availableAt／availabilityBasis／缺漏原因／來源連結）
   是未來 V13 promotion 的可稽核證據，移除等於自斷後路。
3. `docs/openapi/docker-external-api.yaml:1382` 已把 `marketFeatures` 與 `treasuryRateContext`
   列入 `RadarEvidence` 的 `required`，前端移除會造成「公開契約有、畫面沒有」的新落差。

## 要做什麼

### 362.1 前端：市場數值特徵面板明示不進評分

`frontend/src/views/TradingRadarView.vue:565`，現況為：

```html
<div class="fundamental-title">市場數值特徵（只呈現，不在前端重算）</div>
```

改為明示「這些數值目前不進評分」，並在同一區塊補上「大盤趨勢另由盤勢因子計入」的說明
（可放在標題下方新增一行 `<small>` 或 `<div class="muted">`，沿用該檔既有 class 慣例，不新增全域樣式）。

**必須一併交代 `status` 欄可能出現的 `DISCLOSURE_ONLY`，否則會製造新的誤解。**
`TradingRadarMarketFeatureResolver.java:395-400` 的 `indexReturn()` **在成功路徑**對 `IXIC`／`SOX` 設
`regimeDuplicate = true`，其 `duplicateOf` 為 `"MARKET_REGIME"`、`status` 為
`CandidateMarketFeature.DISCLOSURE_ONLY`。

> **但這是條件行為，文案不得寫死。** `:365-368`（terminal session 未由日曆確認）、`:379-380`
> （不足 6 個完成日）、`:382-386`（缺指定 terminal session）三條缺值早退路徑走的是
> `CandidateMarketFeature.unavailable(...)`，`duplicateOf` 為 `null`、`status` 為 `MISSING`。
> 故**不得寫成「`IXIC_RET5`／`SOX_RET5` 固定顯示 `DISCLOSURE_ONLY`」**——缺值日它們與其他碼一樣是
> `MISSING`，那樣寫會與畫面不符。

面板逐項顯示 `feature.status`（`:568`），在 production 只有三種值：`DISCLOSURE_ONLY`、`AVAILABLE`、
`MISSING`。**`NOT_APPLICABLE` 不必為它設計文案**——`CandidateMarketFeature.notApplicable(...)` 在
`backend/src/main/java` 只有定義（`CandidateMarketFeature.java:49`）、沒有呼叫端，只有測試會建；
`applicabilityForCode(...)` 回傳的 `NOT_APPLICABLE` 是 `profileApplicability` 欄位值，而面板未顯示該欄
（`:568-569` 只顯示 key／value／status／provider／asOfDate／availableAt／availabilityBasis／missingReason）。若文案只寫「本面板數值不進評分」而不解釋 `DISCLOSURE_ONLY` 的意義，使用者會反向
推論成「只有那一項無效、其他有效」——正是本任務要消除的誤解。文案須做到兩件事：
(1) 明確**九項在 production 全部不進評分**；
(2) 解釋 `DISCLOSURE_ONLY` **這個狀態**的意義——candidate／回測路徑的**去重**標記（該碼資訊已由盤勢
因子代表），**不是** production 有效性的區別。

**約束：**
- 面板的 `v-for` 內容（`feature.key` / `feature.value` / `feature.status` / provider / as-of /
  availableAt / availabilityBasis / missingReason，位於 `:567-570`）**一個都不得移除或隱藏**。
- 不得改動 `marketFeatureEntries(row)`（`:1480-1485`）的邏輯——它只是把 `evidence.marketFeatures`
  的 map 攤平排序，不做任何判斷。
- 文案不得出現「預測」「將會」「機率」「能降低風險」等字眼（Requirement 56 的全域約束）。

### 362.2 前端：美債殖利率情境依三段語意加註

`frontend/src/views/TradingRadarView.vue:533-544` 的 `<div v-if="row.evidence.treasuryRateContext" class="confirm-item">`
區塊，`:534` 的 `<span>美債殖利率情境</span>` 之下須加註說明，內容須同時交代兩件事：

- **殖利率數值不計入評分**；
- **這筆曲線資料是否齊備，會影響債券標的的證據閘門**。

**約束：**
- `:535-542` 既有的 tenor／值／曲線日／provider／batch／完整／落後日數／可得時間／可得基礎／抓取時間／
  時效說明／來源連結**全部保留**，一行都不得刪。
- 不得在前端推導利率狀態或門檻——`:1469-1472` 的既有註解已明訂「只查找後端 `evidenceGroups` 已解析的
  component；不在畫面推導利率狀態或門檻」，本次不得破例。

### 362.3 前端：個股決策卡片標題改繫結 `marketTab`

`frontend/src/views/TradingRadarView.vue:163`：

```html
<span class="section-title">我的台股決策</span>
```

「台股」是硬編字串，切到「美股」分頁時檔數（`currentStocks` 長度）已正確變成美股檔數，
標題卻仍寫「我的台股決策」。改為插值 `marketTab`（`:1177` 的 `const marketTab = ref('台股')`，
值為 `'台股'`／`'美股'`）。

**對照組（兩者都已正確跟隨分頁，本次不動）：** `:27` 的 `{{ marketCardTab }}大盤風險`、
`:810` 的 `` `目前沒有可分析的${marketTab}標的` ``。

**注意兩個 ref 不可混用**：`marketCardTab`（`:1169`）是**大盤卡片**的分頁，`marketTab`（`:1177`）
才是**個股表格**的分頁；`:1167-1168` 的既有註解明訂兩者刻意獨立。此處必須用 `marketTab`。

### 362.4 匯出：`MARKET_FEATURE` 行加標示

`backend/src/main/java/com/steven/assets/service/TradingRadarExportService.java:696` 起的

```java
lines.add("MARKET_FEATURE " + name + " status=" + ... + " source=" + ...);
```

須在該行帶上不進評分的標示（例如在 `status=` 之前或整行末尾附加固定標記字串）。
這些行落在「證據閘門原因」欄（`:485` 的 header，對應 `:291` 的 `evidenceDisclosureLines(d, evidence)`，
格式為 `ExportDoc.Format.LIST_LINES`）。

**約束（硬性）：**
- **欄名字串、欄位數量與欄序一律不得變動。** `:501-503` 的既有 Javadoc 明載這些欄位
  「刻意放在既有欄位之後，讓舊版欄位索引與既有檔案相容」；新增或改名欄位會破壞既有匯出檔的索引相容性。
- 標示只能加在**儲存格內容**，不得改 `DETAIL_EVIDENCE_HEADERS`／`VALUATION_COMPONENT_HEADERS`／
  `LIVE_PREMIUM_HEADERS` 任一清單。
- 匯出端不得重算任何分數或門檻（`:655-660` 既有 Javadoc：「no score or gate is recomputed by the export layer」）。

### 362.5 匯出：treasury 揭露行（**不得只寫 Javadoc**）

**先講不能怎麼做**：把說明只寫進 Javadoc 是不夠的——Javadoc 對匯出檔的消費者不可見，那一處揭露等於沒落地。

**要做的**：在 362.4 同一支 `evidenceDisclosureLines()`（`TradingRadarExportService.java:661-717`；
`:707-715` 是既有的 `DIVIDEND_EVENT` 區塊，`:716` 才是 `return`）追加一行固定格式的 treasury 揭露，
**插在 `:715` 之後、`:716` 的 `return` 之前**，例如：

```
TREASURY_RATE 殖利率數值不計入評分；曲線資料是否齊備影響證據閘門
```

它與 `MARKET_FEATURE`／`EVIDENCE_COMPONENT` 行落在**同一個既有的**「證據閘門原因」`LIST_LINES` 欄
（header 於 `:485`），**零 header 變更**，完全不觸碰 362.4 的硬約束。該函式已有
`EVIDENCE_COMPONENT ASSET_SPECIFIC/bond_rate status=… reason=…` 的既有先例，格式比照即可。

**此行必須有輸出條件，不得每列無條件寫**；而且**判別式要用 `bond_rate` component，不是 `treasuryRateContext`**。
兩個看似自然的寫法都會踩雷：

> **① 不得用 `evidence.path("treasuryRateContext").isMissingNode()`。**
> 全樹沒有任何 Jackson null-exclusion 設定（`grep -ran "setSerializationInclusion\|JsonInclude\|NON_NULL"
> backend/src/main/java/` 零命中，`backend/src/main/resources/` 也無 jackson 設定），故序列化為預設
> `ALWAYS`：`RadarEvidence` 的 `treasuryRateContext` 欄（`TradingRadarDto.java:353`）**每一列都存在**，
> 非債券時值為 `null`，而 `NullNode.isMissingNode()` 回 `false`。`docs/openapi/docker-external-api.yaml:1382`
> 也把它列在 `RadarEvidence.required`、`:1421-1424` 型別為 `anyOf [TreasuryRateContext, 'null']`，
> 公開契約本身就保證這個 key 恆在。照此寫法，362.8(d) 的「非債券列不含該行」必然紅燈。
>
> **② 也不得用「`treasuryRateContext` 非 null ⟺ 債券標的」。**
> 債券但 curve 不可得時它同樣是 `null`：`TradingRadarService.resolveTreasuryRateObservation(...)`
> 有四條回 `RateObservation.missing(...)` 的路徑——`:1209-1211`（strict bond profile 不完整）、
> `:1218-1220`（strict bond term 缺漏）、`:1225-1226`（決策時點前無完整 Treasury curve batch）、
> `:1229-1230`（解析失敗），其 `context()` 皆為 `null`（`:1156` 再把它塞進 DTO）。
> 而「curve 不齊備 → `ASSET_SPECIFIC` 覆蓋率掉到 `.70` 以下 → 買進閘門關閉」**正是本任務最需要向
> 使用者解釋的那個情境**（`TradingRadarEvidenceGate.java:227-230`），用這個條件會讓這些列一行揭露都沒有。

**正確判別式**：該列 `ASSET_SPECIFIC` group 是否有 `bond_rate` component。它對**所有** bond profile 都會建立
——`TradingRadarEvidenceConfidenceResolver.java:797` 的 `if (p.bond())` 之下 `:815` 無條件
`c.add(component("bond_rate", status, 1, asOf, provider, reason))`，只有 `applicability` 隨 curve 可得性在
`AVAILABLE`／`STALE`／`MISSING` 之間變動。`evidenceDisclosureLines(decision, evidence)`（`:661`）拿得到同一個
`evidence` 節點，其 `evidenceGroups.ASSET_SPECIFIC.components[]` 就在裡面（該函式 `:664-692` 本來就在走訪
這棵樹）；前端 `TradingRadarView.vue:1470-1471` 的 `bondRateEvidence(row)` 已有同一條 lookup 的既有先例。

> **附帶更正**：`TradingRadarExportService.java:296` 的 `evidence.path("treasuryRateContext")` **不是** bond
> 判別式，它只是把該節點當容器餵給 `num(treasuryRateContext, "batchId")` 等子欄位讀取器；非 missing
> 不等於有值。

**附加（非唯一手段）**：`DETAIL_EVIDENCE_HEADERS` 中「利率批次ID」起至「利率來源Manifest」那 12 欄
（實際落在 `:518-520`；`:517` 的「利率證據狀態／利率證據來源／利率缺漏原因」三欄不在此範圍）
的說明另寫在該常數上方的 Javadoc，供讀 code 的人查證。不得為此新增欄位。

### 362.6 OpenAPI：兩個 schema 加 `description`

`docs/openapi/docker-external-api.yaml`：

- `:1500` 的 `MarketFeatureEvidence` — 加 `description`，說明為揭露欄位、不進 production 評分。
- `:1531` 的 `TreasuryRateContext` — 加 `description`，依上述三段語意分述（數值不計分／可得性影響閘門）。
- `:4` 的 `info.version` 由 `1.1.0` 升版。

**為什麼不能只改 Vue：** `GET /api/public/trading-radar/today` 是 Requirement 86 第九條
gateway／Tailscale 路由，其消費者不經過前端畫面；只改 Vue 等於外部消費者完全收不到這個標示。

**約束：**
- **不得新增、移除或改名任何 property，也不得改動 `required` 清單。** `description` 是 JSON Schema
  關鍵字而非 property，與既有 `additionalProperties: false` 不衝突，
  `backend/src/test/java/com/steven/assets/dto/TradingRadarOpenApiSchemaContractTest.java` 的
  雙向比對不受影響。
- 不得改動任何 path、HTTP method、參數或 9090／Tailscale 路由設定。

### 362.7 不得改動任何評分行為，`RULE_VERSION` 維持 `TW_RULES_V16`

三軌 `score`／`action`／`candidateAction`／`reasons`／`risks` 對所有標的**逐位不變**。
`TradingRadarRuleEngine` 的權重常數、門檻、因子組成、動作映射，以及 `TradingRadarService` 的組裝結果一律不動。

**七處版號 hardcode 與既有版號測試斷言一處都不要碰**（實測 `grep -ran "TW_RULES_V16"`：
`TradingRadarRuleEngine.java:61`、
`TradingRadarDto.java:9`、`TradingRadarService.java:752` 註解、`TradingRadarView.vue:28`／`:1142`／`:821`、
`docs/openapi/docker-external-api.yaml:579`）。

**既有三條不升版先例一條都不適用，本任務新增第四條判準。**

- **不得援引 Task 281**（「**新增欄位**純揭露」）——本次一個欄位都沒新增（362.4／362.6 明文禁止）。
- **不適用 Task 249**（「同一份輸入前後產生完全相同的輸出」）——匯出檔「證據閘門原因」欄的儲存格文字會變。
- **不適用 Task 336**（顯示值捨入缺陷修正）——本次不是捨入問題。

比照 Task 336 當初的作法，須在 `spec/design.md` 的先例權威清單（Task 263 段落）新增**第四條**並逐字寫明：
「規則引擎輸出（`action`／`score`／`regime`／`reasons`／`risks`）與 API payload 的每一個欄位值逐位不變，
且不新增／移除／改名任何欄位；變動的僅是**說明性文字**——畫面文案、匯出檔中專供揭露用的儲存格文字，
以及 OpenAPI schema 的 `description`」。**該條已隨本任務寫入 design.md，實作時只需確認未被回退。**

**不得把判準收窄成「使用者可觀察的評分行為有實質變化」**——`spec/requirements.md` 的 Requirement 43
追加（Task 281）AC 逐字禁止把原判準「使用者可觀察**行為**有實質變化」收窄後拿去當後續任務的先例。

**反面說明**：若有人主張「畫面文字變了也是使用者可觀察」而升版，會產生兩個分數完全相同的版號，
反而破壞 `TradingRadarView.vue:821` 那句「`TW_RULES_V15` 與 `TW_RULES_V16` 為不同規則版本，
兩者的分數不可直接比較」的意義。

### 362.8 測試（與實作同一次交付）

文案本身沒有邏輯可測，真正需要釘住的是**文案所宣稱的事實**——否則 V13 promote 之後文案會靜默變成謊言。

**(a) 零 delta 時 market feature 與 treasury 對分數零影響（核心防漂移點）。**
落點 `backend/src/test/java/com/steven/assets/service/TradingRadarV13ActionPolicyTest.java`
（該檔已有 `candidate(Map)`、`context(...)` 與 `CandidateContext` 的建構樣板，直接沿用）。

> **先講不能怎麼寫。** **不得**斷言
> `evaluateCandidate(input, emptyDeltaCandidate, ctx)` 的分數 `==` `evaluateStock(input)` 的分數。
> `candidate != null` 時引擎另有兩處與本次**完全無關**的分歧，該等式必紅：
> (i) `TradingRadarRuleEngine.java:2003-2007` 的 `if (candidate != null && candidate.normalizedBiasEnabled())`
> 會把 `factors.bias()` 換成 `normalizedBiasCandidate(...)`，observation 不可得時回 `null`（`:2119-2124`），
> 該因子整個退出 `Accumulator`、`sumW` 改變；而該測試檔的 `candidate(Map)` helper 走的是
> `RuleParameters.v13Candidate(...)` 而非 `v13DisabledCandidate(...)`，故 `normalizedBiasEnabled()` 為 true。
> (ii) `:2181-2185` 的 `sigmaUnavailable` 對 `V13_VERSION` 參數集恆判定，`context.normalizedBiasSigmaRatio()`
> 為 `null` 時觸發 `V13_SIGMA_PROFILE_GATE` 並改 action（該檔既有測試已斷言此 risk 存在）。

**正確設計是 candidate-vs-candidate 的權重隔離**：固定同一個 `candidateWeightDeltas` 為**空 map** 的
candidate，只改 `CandidateContext` 裡的 market feature aggregate（例如 `+1.0` 與 `-1.0` 兩個飽和極值），
斷言三軌 `score` **逐位相同**；treasury contribution 同法各做一組。兩邊都走 candidate 路徑、參數集相同，
上述兩處分歧被消掉，測到的就只有權重本身。

若有人把 `TradingRadarRuleEngine.java:2030` 的 `candidateWeight(candidate, featureKey, 0.0)`
或 `:2079` 的 treasury baseline 從字面 `0.0` 改成非零，此測試必紅。

**落點樣板要挑對，`context(String, String)` 不夠用。** 該 helper 沒有 market feature 參數；要「只改
market feature aggregate、固定其餘」必須用同檔的 `featureContext(Evidence)`／`featureContext(Evidence, AssetProfile)`
helper（它會呼叫 `marketFeatures.aggregateContribution(profile)` 並把 `shortTerm()`／`mediumTerm()` 餵進
`CandidateContext` 的最後兩欄），或直接用 `CandidateContext` 的完整建構式。`±1.0` 的飽和極值合法——
`AggregatedContribution` 的緊湊建構式限定 `-1..1`（`TradingRadarMarketFeatureResolver.java:176-179`）。

**對照組要指對測試，別高估既有覆蓋：**
- **treasury**：既有的 `candidateApiRerunsFullEngineWithImmutableMarketAndTreasuryWeights()` 確有
  「非零 delta 時分數變高」的斷言（`treasuryWeighted` 用 `SHORT_TREASURY`／`MEDIUM_TREASURY`＋`context("1","1")`），
  可直接當正向對照，不必重寫。
- **market feature**：同一支測試裡的 `marketWeighted` 用的是 `SHORT_MARKET`／`MEDIUM_MARKET`——那是**盤勢
  regime 權重**，不是本次要釘的 `SHORT_MARKET_FEATURE`／`MEDIUM_MARKET_FEATURE`（`RuleParameters.java:126-133`
  是四個不同的 enum 常數）。market feature 這一組的既有測試是
  `candidateMarketFeatureContributionChangesScoreButMissingFeaturesDoNot()`，但它斷言的是
  「`reasons` 含 `V13_MARKET_FEATURE_CONTRIBUTION`」與「缺值時分數等同無權重」，**沒有任何「分數變高」的
  斷言**。故若要 market feature 也有一正一反的完整覆蓋，**正向那條需一併補寫**（給非零
  `*_MARKET_FEATURE` delta，斷言分數確實變高），不能宣稱既有測試已覆蓋。

**(b) production 未 promote 時走 baseline。**

> **先講不能怎麼寫。** **不得**寫成「斷言 `RuleParameters.v12Default().candidateWeightDeltas()` 為空，
> **故**四個 key 走 baseline」——這是 non-sequitur。`v12Default()` 這個物件根本不會被傳進
> `candidateWeight()`：`TradingRadarRuleEngine.java:1469-1471` 在未 promote 時直接
> `return evaluateStock(input);`，走的是 `evaluateStockInternal(input, null, null)`。
> production 走 baseline 的真正原因是 `:2222` 的 `if (candidate == null) return baseline;`，
> 與 deltas 空不空無關。（`TradingRadarService.java:1016`／`:1214` 的 `v12Default()` 是傳給
> `resolveBondYieldBeta`／`resolveTreasuryRateObservation`，與權重無關。）

**正確落點是既有的 `TradingRadarV13ActionPolicyTest`**，具體兩支：
- `unpromotedPolicyAndEmptyRegistryRetainExactBaselineResult()`——未 promote 的 policy ＋
  `TradingRadarV13PromotionRegistry.empty()` 時，`evaluatePromoted(...)` 逐位等於 baseline。
- `trackScopedRuntimeWithTwoFallbackKeysIsBitIdenticalToV12()`——兩把 fallback key 時逐位等於
  `engine.evaluateStock(input)`。

> **不得指名 `TradingRadarV13PromotionRegistryTest`。** 實測該檔只斷言 `registry.isPromoted()`／
> `resolve()`／`decision()`，全檔沒有 import 規則引擎、沒有任何 `evaluateStock`／`evaluatePromoted`
> 呼叫（`grep -cn "evaluatePromoted\|evaluateStock\|RuleEngine"` 回 0）。指名它會讓完成報告引用一個
> 不存在的守門。

本條**引用既有測試即可，不必新寫**，但完成報告須誠實載明其守備範圍：`evaluatePromoted` 除
`TradingRadarRuleEngine.java:1444` 的多載自我委派外沒有任何呼叫端（無 service／controller 消費者），
故這兩支守的是**引擎契約**（promotion 機制真的接線時不會靜默改變 baseline），**不是**現行 production
路徑——後者由 `TradingRadarService.java:966` 直接呼叫 `evaluateStock(StockInput)` 這個單參數多載來保證。

**(c) treasury 可得性確實影響 evidence。** 斷言債券 profile 在 curve context 完整與缺漏兩種情形下，
`ASSET_SPECIFIC` 的 `bond_rate` component applicability 不同——這條是為了證明背景表格第三列不是臆測，
而非只是複述。落點 `TradingRadarTreasuryRateObservationTest` 或 `TradingRadarEvidenceGateTest`（兩者皆存在）。

**(d) 匯出標示。** 落點是 `backend/src/test/java/com/steven/assets/service/export/TradingRadarDualFormatTest.java`
——**不是** `TradingRadarExportServiceTest`。理由是前者已備妥所需 fixture：

- `snapshotNodeWithTreasury()` 已有 `evidence.treasuryRateContext`，同檔另有 `evidence.evidenceGroups`
  fixture，可在其 `ASSET_SPECIFIC` 補一個 `bond_rate` component → **債券列**；
- `snapshotNode()` **沒有** `evidence` 節點 → 天生的**非債券列對照組**；
- `STOCK_HEADERS_V11`（`:207` 起，於 `:332` 以 `containsExactlyElementsOf` 斷言）已逐字釘住含
  「證據閘門原因」的完整 header 順序，**欄序守門不必重寫**。

只需為 `marketFeatures` 補一個 code。斷言：`MARKET_FEATURE` 行含新標示；有 `bond_rate` 的列的
「證據閘門原因」欄含 `TREASURY_RATE` 行；沒有的列**不**含該行（釘住 362.5 的輸出條件）；欄位數與欄序
逐位不變。

> **golden workbook 不受影響**：其來源 fixture `snapshotNode()` 無 `evidence` 節點，不會產生
> `MARKET_FEATURE` 行，故該檔既有的 golden 逐格比對不會因本次文字變更而紅。

> **前端沒有元件測試框架，據實寫明、不得假裝有守門。** `frontend/package.json:7` 的 `test` script 是
> `node --test` 跑 `src/utils/` 底下五支純函數測試（`displayQuote`／`valuationEvidence`／
> `gdpTwseMarketCatalog`／`tradingCalendarYearWindow`／`stockAnalysisDialog.contract`），
> 全樹沒有任何 `.vue` 元件測試（無 vitest、無 jsdom）。故 362.1／362.2／362.3 的三處前端改動
> **只能靠下方實機驗證守門**，完成報告不得宣稱有前端自動化測試覆蓋。

## 驗證

`/run-stack` 以 `--no-cache` 重建並 recreate `business-services`／`frontend`
（JVM service 的 cached build 可能不含變更，本專案已有 stale jar 事故前例；重建後須 restart BFF）。
登入後於「今日交易雷達」頁確認：

- (a) 展開任一台股標的，市場數值特徵面板的九項數值**仍在**，且面板說明已明示不進評分並交代
  大盤另由盤勢因子計入。
- (b) 展開任一債券 ETF，美債殖利率情境區塊的 tenor／值／provenance 小字**仍在**，
  且說明已分述「數值不計分／資料齊備與否影響證據閘門」。
- (c) 個股決策卡片切到「美股」分頁時標題顯示「我的美股決策」，切回「台股」顯示「我的台股決策」，
  標題與檔數一致。
- (d) 頁面版號 tag 仍為 `TW_RULES_V16`，抽查數檔的三軌分數與變更前一致。
- (e) 公開路由仍正常且版號不變：

```bash
docker compose -p asset-management exec -T business-services curl -fsS http://localhost:8080/api/public/trading-radar/today | head -c 400
```

- (f) 手動觸發一次匯出，確認「證據閘門原因」欄的 `MARKET_FEATURE` 行含新標示、債券標的的該欄含新增的
  `TREASURY_RATE` 揭露行，且欄位總數與欄序未變。

後端測試：

```bash
cd backend && /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q test -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

> **不要用 `-DargLine`**：Task 252 起它會覆蓋掉時區設定，導致大量測試 error 且錯誤訊息偽裝成
> byte-buddy 問題。也不要用 `| tail` 包住 maven 指令——那樣拿到的 exit code 是 `tail` 的，恆為 0。

## 不在本次範圍

- **不把商品／公債／法人接進 baseline 評分。** 正規路徑是 V13 candidate 的 holdout／walk-forward
  promotion，而 `spec/tasks/t316_radar_v13_validation_closure.md:194` 記載的真實 FULL_MARKET 執行結論是
  **`INSUFFICIENT`（execution timeout）**——固定 request 送出後跑到 10 分 15 秒仍無 completed report
  （container 約 2.94 GiB／7.65 GiB memory、約 2–2.6 CPU cores，未見 OOM），至今**沒有任何一次完成的
  全市場 holdout report**。故其前置條件不是「跑一次回測」，而是先讓 full-market backtest 跑得完；
  在那之前配任何權重都會違反 `spec/tasks/t333_radar_gap_backlog_2026_08_15.md:265-269` 的 `333.10`
  （未經 Requirement 56 量測不得調門檻／權重）。**刻意不為它另建任務檔**，以免留下一張長期無法動工的殭屍任務。
- 不改任何權重、門檻、因子組成、動作映射或 `RULE_VERSION`。
- 不新增或移除任何 DTO 欄位、API 路徑、9090／Tailscale 路由；不新增 `@Scheduled`；不動 `db/changelog/`。
- 不處理 `MW_PE` 標籤寫「PE 自身分位」但實為 PE／PB／殖利率三者平均的命名落差（Requirement 96 已列為另案）。

## 完成報告

**完成日期：** 2026-08-23

### 實際改動

**前端** `frontend/src/views/TradingRadarView.vue`（6 行）
- 362.1 `:565` 標題改為「市場數值特徵（僅供揭露，這些數值目前不進評分）」，其下新增一行 `.muted`
  說明：大盤趨勢另由盤勢因子計入評分、`DISCLOSURE_ONLY` 是候選／回測路徑的去重狀態而非有效性區別、
  本面板九項在正式評分中一律不生效。`v-for` 內容與 `marketFeatureEntries()` 未動。
- 362.2 `:535` 之後（`<strong>` 數值之下）新增
  `<small>殖利率數值本身不計入評分；這筆曲線資料是否齊備，會影響債券標的的證據閘門。</small>`。
  **刻意放在數值之後而非標題之後**：`.confirm-item` 是 flex column 且 `span` 與 `small` 同色同字級
  （`:1919-1920`），插在標題後會把數值擠到第三行，與同面板其他項目（如 `:528-532` 債券利率證據）
  的「標題 → 值 → 說明」模式不一致。既有 provenance 小字與來源連結一行未刪。
- 362.3 `:163` 改為 `我的{{ marketTab }}決策`。

**後端** `backend/src/main/java/com/steven/assets/service/TradingRadarExportService.java`（+52 行）
- 362.4 新增常數 `MARKET_FEATURE_DISCLOSURE`＝` scoring=NOT_SCORED（此數值不計入評分；大盤趨勢另由盤勢因子計入）`，
  接在既有 `MARKET_FEATURE … source=…` 之後。
- 362.5 新增常數 `TREASURY_RATE_DISCLOSURE`＝`TREASURY_RATE scoring=NOT_SCORED 殖利率數值不計入評分；曲線資料是否齊備影響債券標的的證據閘門`，
  在 `DIVIDEND_EVENT` 區塊之後、`return` 之前條件輸出；判別式為新增的私有方法 `hasBondRateComponent(JsonNode)`，
  走 `evidenceGroups.ASSET_SPECIFIC.components[].name == "bond_rate"`。另在 `DETAIL_EVIDENCE_HEADERS`
  上方補 Javadoc 說明利率 12 欄語意。
- **`DETAIL_EVIDENCE_HEADERS`／`VALUATION_COMPONENT_HEADERS`／`LIVE_PREMIUM_HEADERS` 三個清單零變更**，
  欄名、欄數、欄序未動。

**契約** `docs/openapi/docker-external-api.yaml`（+33／−1）
- `info.version` `1.1.0` → `1.2.0`；`MarketFeatureEvidence` 與 `TreasuryRateContext` 各加 `description`
  （後者依三段語意分述，並寫明不得標成「僅供參考」）。唯一刪除的行是舊 version；property、`required`、
  `paths` 皆未動，實測仍為 9 條 path。

**零改動確認**：`TradingRadarRuleEngine.java`／`TradingRadarService.java`／`TradingRadarDto.java`
`git diff --stat` 為空；`RULE_VERSION` 維持 `TW_RULES_V16`，七處版號 hardcode 一處未碰。
未新增／移除任何 DTO 欄位、API 路徑、`@Scheduled`；`db/changelog/` 未動。

### 測試結果

- **全量後端測試通過**：`mvn test -DextraArgLine=-Dnet.bytebuddy.experimental=true`，
  **EXIT=0，1408 tests、0 failures、0 errors、0 skipped**。
- 新增測試：`TradingRadarV13ActionPolicyTest` 三支
  （`marketFeatureAggregateExtremesCannotMoveScoreWithoutCandidateWeight`、
  `nonZeroMarketFeatureWeightDoesRaiseCandidateScore`、
  `treasuryContributionExtremesCannotMoveScoreWithoutCandidateWeight`）、
  `TradingRadarTreasuryRateObservationTest` 一支（362.8c）、
  `TradingRadarDualFormatTest` 一支（362.8d，含債券列／非債券列對照）。
  362.8(b) 依規格引用既有測試未新寫。
- **反向驗證（確認守門非虛設）**：把 `TradingRadarRuleEngine` 的 `featureWeight` 與 `treasuryWeight`
  baseline 由字面 `0.0` 暫時改為 `0.30`，`TradingRadarV13ActionPolicyTest` 由 26 passed 轉為
  **3 failures**，其中兩支正是本次新增的零影響釘樁；已 `git checkout` 還原，該檔 `git diff` 為空。
- 前端無元件測試框架，未撰寫任何 Vue 測試（`frontend/package.json` 的 test script 只跑
  `src/utils/` 底下五支 `node --test`）。

### 實機驗證

Docker Compose project `asset-management`，自本 worktree（變更未 merge）以 `--no-cache` 重建
`business-services` 與 `frontend`、`--force-recreate` 後 `restart bff`，八個容器全部 healthy。

**產物確認非 stale image**
- `docker exec asset-frontend grep` 於 `TradingRadarView-DPvJlSFC.js` 命中「僅供揭露，這些數值目前不進評分」、
  「殖利率數值本身不計入評分」與編譯後的插值 `我的"+n(a(ne))+"決策`。
- `docker exec asset-business-services unzip -p /app/app.jar … | strings` 命中
  `TREASURY_RATE scoring=NOT_SCORED`、` scoring=NOT_SCORED` 與 `hasBondRateComponent`。

**端點**
- `GET http://127.0.0.1:9090/api/public/trading-radar/today`（Requirement 86 第九條）正常回應，
  `ruleVersion = TW_RULES_V16`（未升版），38 檔標的。
- `curl -sI http://localhost/` → `HTTP/1.1 200 OK`；`docker logs asset-bff --since 3m`
  的 `Connection refused|500 Server Error` 計數為 **0**。

**真實資料印證了 362.5 判別式的必要性（本次最有價值的實測）**
- `treasuryRateContext` 非 null 的標的：**6 檔**；有 `bond_rate` component 的標的：**7 檔**。
- 差的那一檔是 **`00859B`**：`bond_rate.applicability = MISSING`、
  `missingReason = "strict bond profile 不完整，Treasury 利率風險不納入"`，而其 `treasuryRateContext` 為 `null`。
- 亦即若照被審查否決的寫法（以 `treasuryRateContext` 判定），`00859B` 這種「證據不完整、閘門受影響」
  的標的**剛好一行揭露都拿不到**——正是最需要解釋的那一類。改用 `bond_rate` 後涵蓋。

**真實資料印證了 362.1 的文案約束**
- `IXIC_RET5` 與 `SOX_RET5` 實際回 `status = DISCLOSURE_ONLY`、`duplicateOf = MARKET_REGIME`；
  `SPX_RET5` 為 `AVAILABLE`。三者在 production 一律不計分——若文案不解釋這個 `status` 差異，
  使用者會反推成「只有那兩項無效、其他有效」。
- 九碼 feature 在全部 38 檔標的的 evidence 中皆完整present。

### 未完成／待使用者確認

1. **前端畫面的視覺確認未做。** `http://localhost/` 導向 Google OAuth 登入頁，執行代理不得代為登入，
   故 362.1／362.2／362.3 的**畫面呈現**（排版、換行、插值渲染）未經人眼確認。已確認的是編譯後
   bundle 確實含這三處變更。需使用者登入後於「今日交易雷達」頁確認驗證段的 (a)(b)(c)。
2. **實機匯出未觸發。** `POST /api/trading-radar/export` 有落檔與 Google Drive 上傳的副作用，
   且會覆蓋同名前一版檔案，故未自動執行。該路徑的行為由 `TradingRadarDualFormatTest` 新增的
   測試覆蓋（含債券列含 `TREASURY_RATE` 行、非債券列不含的對照斷言）。若需實機匯出證據，
   由使用者自行觸發。
