# [t342] 交易雷達美股大盤接上量價環境因子，跨市場改為「不適用」語意，`RULE_VERSION` 升 `TW_RULES_V14`

**對應 Requirements:** Requirement 82（交易雷達美股大盤接上量價環境因子——消除「畫面顯示量比 0.79、風險提醒卻說資料不足」的自相矛盾）
**前置任務:** 無（t294 的 `buildUsMarket()`、t323 的 IXIC 量能解析、t335 的美股大盤分頁、t336 的 BigDecimal 均線皆已 landed 於 `main`）
**Liquibase changeset:** 無（不入庫；升版所需的 `trading_radar_notification_setting.rule_version` 欄**現況已存在**——`db/schema.sql` 記為 `rule_version character varying(30)`，落地前請以運行中 DB 複驗：`docker exec asset-postgres psql -U assets -d assets -c '\d trading_radar_notification_setting'`）

> **⚠ 編號撞號警告（落地前必讀）。** 本任務**已經避讓過一次**：規劃階段原取 Task 339 ／ Requirement 80，spec 寫完後發現兩者都被 `local-analysis-replace-api-e5fa7c` 佔用，且該批（t337–t340、R78–R81）隨即 merge 進 `origin/main`，故改為現在的 **Task 342 ／ Requirement 82**。
>
> 定稿當下 `origin/main` 為 `5f1ec11f`：t337–t340 與 R78–R81 **皆已 landed**，不再是待避讓對象。
>
> **⚠ Task 342 目前仍與 `trading-radar-no-trades-a47b9f` 的 `t341_portfolio_advice_subclass_breakdown.md` 撞號**（兩邊都未 landed，取號時間相近）。**Requirement 82 未撞**（全機唯一）。本任務**不再繼續改號**——本專案並行度高到每隔數分鐘就有新 worktree 取號，追著改是無止境的；依 CLAUDE.md 的既有規則處理即可：**誰先 landed 誰保留原號，後到者改自己那邊**。故 merge 前務必用下面的指令複查 `origin/main`，若對方已先 landed t341 就改本任務的號（只動 spec 檔，程式碼不受影響）。
>
> `scripts/spec-check.sh` **抓不到跨 worktree 撞號**——它只比對 `origin/main`。故**動手前與 merge 前各跑一次**：
>
> ```bash
> git fetch origin && bash scripts/spec-check.sh
> ls /Users/steven/Project/asset-management/.claude/worktrees/*/spec/tasks/t34*.md
> grep -h -oE '^### Requirement 8[0-9]:' /Users/steven/Project/asset-management/.claude/worktrees/*/spec/requirements.md | sort -u
> ```
>
> **glob 必須涵蓋本任務實際取用的號段（`t34*`）**——用 `t33*.md` 結構上就抓不到 t340／t341。避讓規則見 CLAUDE.md：**已 landed 的保留原號、改自己這邊、不 force push**。程式碼不依賴編號，改號只動 spec 檔。

---

## 背景

### 現在的錯誤行為

使用者在 `/trading-radar` 的「美股大盤風險」分頁看到**同一張卡片自相矛盾**：

- metric 區顯示「**大盤完成日量比 0.79 倍**（2026-08-14）」
- 下方風險提醒卻寫著「**大盤完成日成交量或成交金額資料不足**，本日不採計量價環境分數。」

第二則風險提醒「前一個已完成的美股科技共同交易日資料不足，本日不採計跨市場分數。」同樣是假的——那個因子對美股**本來就不適用**（見下），不是資料缺失。

### 成因

`TradingRadarService.buildUsMarket()` 組 `MarketInput` 時（現行 `:678` 起），三個欄位一律傳 `null`，程式碼註解（`:684-690`）逐字為：

```java
// completedChangePercent／marketVolumeRatio／marketTurnoverRatio
// 一律維持 null（Task 323.2）：這三欄在 TradingRadarRuleEngine 內會進 regime
// 分數（averageAvailable(...) → score ±8／±10／±3），把上面解出的 usContext
// 灌進來會直接翻動美股個股的 regime 與買進閘門。本任務只補 MarketSummary。
null,
null,
null,
```

而 `TradingRadarRuleEngine.evaluateMarket()` 的量價區塊（現行 `:798-815`）：

```java
Double marketActivity = averageAvailable(
        decimal(input.marketVolumeRatio()), decimal(input.marketTurnoverRatio()));
if (marketActivity == null || input.completedChangePercent() == null) {
    risks.add("大盤完成日成交量或成交金額資料不足，本日不採計量價環境分數。 ");
} else if (input.completedChangePercent().signum() > 0 && marketActivity >= 1.1) {
    score += 8;  reasons.add("大盤完成日上漲且量能／成交金額同步放大，需求獲得確認。 ");
} else if (input.completedChangePercent().signum() < 0 && marketActivity >= 1.1) {
    score -= 10; risks.add("大盤完成日下跌且量能／成交金額放大，市場賣壓升高。 ");
} else if (input.completedChangePercent().signum() > 0 && marketActivity <= 0.8) {
    score -= 3;  risks.add("大盤完成日上漲但量能不足，漲勢確認偏弱。 ");
} else if (input.completedChangePercent().signum() < 0 && marketActivity <= 0.8) {
    score += 3;  reasons.add("大盤完成日下跌但量能收斂，賣壓未擴大。 ");
}

if (!input.usTechAvailable() || input.usTechCompositePercent() == null) {
    risks.add("前一個已完成的美股科技共同交易日資料不足，本日不採計跨市場分數。 ");
} else { /* 加減分分支 */ }
```

**引擎只有「有資料 vs 沒資料」兩態**，把「不適用」硬塞進「沒資料」那一態。

同時 `buildUsMarket()` 早就把量比算出來並填進了 `MarketSummary`（那正是畫面上 0.79 的來源），取自 `marketContextService.resolveMarketFromRows(US_MARKET, decisionInstant, List.of(), rows)` 回傳的 `usContext`。**資料一直都在，只是沒接進評分。**

### 正確行為

- 量能與完成日漲跌幅真正參與美股 regime 計算，「資料不足」那則提醒消失
- 跨市場那則改為**沉默**（不適用就不講），但台股「資料真的缺」時的正當提醒原封保留

### 這個決定推翻了什麼

推翻 **Task 323.2** 逐字的「⚠ **`MarketInput` 的量能兩欄也必須維持 `null`，只改 `MarketSummary`**……填了會直接翻動 11 檔美股的 regime 分數與買進閘門」，以及 **Task 294.2** 的「後六個參數全部傳 null／false」。

**兩處排除當初都是正確的範圍控制**（t323 的射程只到 DTO），不是「資料取不到」也不是「不該採計」。但 Task 335 把 `usMarket` 送上畫面後，這個「算了但不用」的中間狀態變成使用者直接看得到的矛盾。使用者已明確要求「真的納入評分」，並在被告知代價（分數會變、需升版、連帶影響美股個股買進閘門）後選擇接受。

**不推翻跨市場三欄的排除。** Requirement 64 的「美股 regime 不得重複套用『前一美股科技交易日』加減分」**維持完全有效**——美股個股與 NASDAQ 同一交易時段，IXIC 自身的 MA／KD 已直接反映該資訊，再疊加等同對同一份資訊算兩次分。本任務只要求引擎不要把「不適用」謊報成「資料不足」。

---

## 要做什麼

### 後端規則引擎（`backend/src/main/java/com/steven/assets/service/TradingRadarRuleEngine.java`）

- [x] **342.1 `MarketInput` 新增跨市場適用性旗標。** 現行 record 為 12 個 component（`:240-253`），在最後新增第 13 個：**`boolean crossMarketApplicable`**（`spec/design.md` 已以此名記載，請照用；語意是「跨市場因子是否適用於本次評估」，**不是**「資料有沒有」）。

  現行的 5 參數相容建構式（`:255` 起，javadoc 逐字「V10 前的呼叫形狀；量能與美股資料缺值時不加減分。」）**必須填 `true`**——讓「漏改」的後果是多一則正當提醒，而不是靜默吞掉一則真提醒。若還有其他相容建構式，一律同樣填 `true`。

- [x] **342.2 跨市場區塊改為三段。** 現行 `:816-817` 的兩段判斷改為：

  ```
  if (!input.crossMarketApplicable())                                    → 不加任何 reasons／risks（沉默）
  else if (!input.usTechAvailable() || input.usTechCompositePercent() == null)
                                                                          → 維持現行「資料不足」risk，一字不改
  else                                                                    → 現行加減分分支完全不動
  ```

  **中間那段是台股的正當提醒，絕對不能一起關掉。** 台股確實會遇到「美股科技資料真的抓不到」（`TradingRadarMarketContextService` 對美股科技共同完成日有 5 個日曆日的上限，超過即整組 unavailable）。

- [x] **342.3 量價文案不得再列舉不存在的欄位。** 三處都要改：

  1. `:801` 的「大盤完成日成交量或成交金額資料不足，本日不採計量價環境分數。 」→ 改為**不列舉欄位**的中性句（例如「大盤完成日量價資料不足，本日不採計量價環境分數。 」）。理由：美股結構性沒有成交金額，列舉它等於暗示「本來該有卻沒有」。
  2. `:804` 的「大盤完成日上漲且量能／成交金額同步放大，需求獲得確認。 」
  3. `:807` 的「大盤完成日下跌且量能／成交金額放大，市場賣壓升高。 」

  後兩句的「量能／成交金額」改由**純函數 helper** 依「本次 `marketActivity` 實際由哪幾個 ratio 構成」內插標籤：兩者皆非 null →「量能與成交金額」；僅量比 →「量能」；僅金額比 →「成交金額」。**必須是輸入的純函數**，不得新增第二個旗標欄位、不得讀取任何外部狀態（引擎的「同一輸入永遠同一輸出」契約是回測可重現性的基礎）。

  > **這會改變台股既有的可見文案**：`marketTurnoverRatio` 在台股也可能為 null（`TradingRadarMarketContextService.ratio()` 要求至少 10 筆正值樣本），那些日子的文案會從「量能／成交金額同步放大」變成「量能同步放大」。**這是修正而非退步**（原本就在講一個不存在的值），須在完成報告記錄。

  **文案安全性已預先查證：**

  ```bash
  grep -ranE "本日不採計|量能／成交金額|賣壓未擴大|漲勢確認偏弱|成交量或成交金額" backend/src/test frontend/src
  ```

  → **零命中**。`backend/src/test` 與 `frontend/src` 都沒有任何測試在斷言這幾句文案，改文案不會弄紅測試。golden 檔亦已解壓驗過：`backend/src/test/resources/golden/radar*.xlsx` 的 `sharedStrings.xml` 只含欄名「大盤成交金額比」「量能完成日」，**不含任何理由／風險句**，故不動任何 golden 檔。

  ⚠ **`-E` 不可省。** BRE（`grep -ran` 不帶 `-E`）下的 `|` 是**字面字元**，整串會被當成一個含 `|` 的字串去比對，必然得到假的「零命中」——實測會只命中本行指令自己。動手前請用**上面帶 `-E` 的完整指令**複跑一次確認。

- [x] **342.4 `RULE_VERSION` 升為 `TW_RULES_V14`。** `:28` 的常數本體改字串，並改寫其上方描述 V12 的 javadoc。

  **必須跳過 13。** `RuleParameters.V13_VERSION = "TW_RULES_V13"` 是**離線 calibration／candidate 參數集**的版本字串——`TradingRadarRuleEngine.evaluateCandidate()` 明文 `if (!RuleParameters.V13_VERSION.equals(parameters.ruleVersion())) throw ... "candidate evaluation 必須使用 TW_RULES_V13 參數"`。Task 336 已預先記載「若日後真要升版必須跳到 `TW_RULES_V14`」。

  **不得新增 `V14_VERSION` 常數、不得改 `RuleParameters.v12Default()` 的名稱或回傳值。** `RuleParameters.ruleVersion` 在 production 的全部消費端都是 `V13_VERSION.equals(...)` 形式的「這是不是 candidate」二值判別式；本次沒有改動任何個股參數值，parameter set 未變。加 V14 會擴散到十餘個呼叫點，換來零語意收益，還會破壞「V13 = candidate」這個乾淨的判別式。

  **同步點必須現場 grep 取得：** 跑 `grep -ran "TW_RULES_V12" backend/src frontend/src`（**`-a` 不可省**——本專案 `grep -r` 會靜默跳過被 `file(1)` 判為 data 的 `.java` 檔）。**不得沿用 `spec/requirements.md` 他處記載的「五處」或「5 檔（7 處）」——那些數字已過期。** 逐一分類處置：
  - `TradingRadarRuleEngine.java` 常數本體與 javadoc、`dto/TradingRadarDto.java` javadoc、`frontend/src/views/TradingRadarView.vue` 的顯示 fallback 與 `radar` ref 初始值（兩處）、`TradingRadarRuleEngineTest` 的版本斷言（**連測試方法名一併改**）、`BacktestServiceTest` 的 `@DisplayName` 與版本斷言 → **全部要改**
  - `RuleParameters` 內的 V12／V13 字串 → **不得改**（candidate 命名空間）
  - `TradingRadarCalibrationSelectorTest` 驗的是 `RuleParameters.v12Default().ruleVersion()` → **不得改**
  - Liquibase changelog 的 `--comment` → **絕不可改**（checksum 含註解，改了會 `ValidationFailed` 並讓 business-services 進入 crash loop）。**注意實際路徑是 `backend/src/main/resources/db/changelog/`——本 repo 沒有 top-level 的 `db/changelog/`**，用 rooted glob 排除 `db/changelog/**` 會排到一個不存在的路徑而讓真正的檔案暴露在替換範圍內。本次的 grep（`TW_RULES_V12`）實測**不會命中** changelog（該目錄內只有 `v1.83.0-radar-notification-rule-version.sql` 提到 `TW_RULES_V9`），所以這條是給日後做跨版本全域替換的人看的。
  - **`TradingRadarV13ActionPolicyTest` → 要改，且會紅，但 `grep -ran "TW_RULES_V12"` 完全抓不到它。** 該檔有一條斷言 `assertThat(TradingRadarRuleEngine.RULE_VERSION).isEqualTo(RuleParameters.V12_VERSION)`——比對的是**常數**而非字面值，不會出現在字面值 grep 的結果裡。升 V14 後必然失敗，而 342.4 同時明令「`RuleParameters` 內的字串不得改」，實作者最直覺的兩種修法（改 `V12_VERSION`、把 `RULE_VERSION` 改回 V12）**都被本任務禁止**——正解是改那條斷言本身，例如改為斷言 `RULE_VERSION` **不等於** `RuleParameters.V12_VERSION` 也不等於 `V13_VERSION`（順帶把「production 不得竊用 candidate 標籤」變成機器強制）。該測試方法名內嵌 `V12`，**一併改**。
  - `TreasuryYieldServiceTest` → **要改，且會紅**。該檔現有一條測試同時斷言 `RULE_VERSION` 等於 `"TW_RULES_V12"`、且 `RuleParameters.v12Default().ruleVersion()` **等於** `RULE_VERSION`；升 V14 後第二句必然失敗。處置見 342.10 第 6 項，**測試方法名一併改**（現行名字含「逐位維持不變」，升版後語意相反）。
  - `InternalBacktestControllerV13Test`（5 處，是單檔命中最多的）→ **不會紅、改不改皆可**：全部是餵給 mock 的 fixture 字面值與對應的 `jsonPath` 斷言，未引用 `TradingRadarRuleEngine.RULE_VERSION`。**但不得因為它綠燈就認定版號已同步**——它本來就與 production 版號無關。
  - 其餘命中逐條判斷「這是 production 版號還是 candidate 標籤」再決定

  ⚠ **字面值 grep 只是第一道，並不完整。** 本專案早有前例：Task 298 的同步點記錄逐字寫著「原 5 檔 7 處，全數處理，**另補 2 處 grep 抓不到的方法名**」。除了上面那條指令，**必須另跑**：

  ```bash
  grep -ranE "V12_VERSION|v12Default" backend/src/main backend/src/test
  ```

  取得以**常數比對**（而非字面值）耦合到 V12 的位置（實測 25 處），逐一判斷哪些是「production 版號」哪些是「candidate／baseline 參數集」。**`TradingRadarV13ActionPolicyTest` 是唯一一支字面值 grep 抓不到的會紅測試**（它比對常數）；`TreasuryYieldServiceTest` 兩道 grep 都會出現（一處字面值、一處常數），不要誤以為它只在第二道。

### 後端接線（`backend/src/main/java/com/steven/assets/service/TradingRadarService.java`）

- [x] **342.5 `buildUsMarket()` 接上量能兩欄。** 現行 `:684-690` 的三個 `null` 改為：

  ```java
  usContext == null ? null : usContext.completedMarketChangePercent(),
  usContext == null ? null : usContext.marketVolumeRatio(),
  null,   // marketTurnoverRatio：us_index_daily_history 無成交值／週轉率欄，不得偽造
  ```

  並在該建構式最後補 `false`（`crossMarketApplicable`）。原有註解改寫為說明新狀態，**保留「不得偽造週轉率」那條約束的文字**。

  **`completedChangePercent` 必須用 `usContext` 這一份，不得用同方法內既有的區域變數 `changePercent`。** 後者算自 `changePercent(closes.get(0), closes.get(1))`，其 `closes` 來自 `findTopNByIndexCodeOrderByTradingDateDesc(IXIC_CODE, 241)`——**沒有任何完成日過濾**（Task 335 已在同一方法的註解逐字記載：「`findTopN...(IXIC_CODE, 241)` 沒有任何完成日過濾，Yahoo `range=10y&interval=1d` 在盤中會回傳當日的部分 bar」）；而 `usContext` 那一份在 `resolveMarketFromRows` 內套了美東 16:00 的完成日邊界。兩者在盤中會落在**不同 as-of 日**，使「漲跌方向」與「量比」跨日拼接，直接把 342.3 那四個計分分支的正負號判錯。

  **`usContext == null` 的三元防護不可省。** 理由與同方法既有註解相同：單元測試把 `marketContextService` 宣告為 `@Mock`，未 stub 的方法回 `null`，直接解參考產生的 NPE 會被 `buildUsMarket()` 的 catch 吞成 `incompleteMarket()`——不報錯卻讓美股整組變 `DATA_INCOMPLETE`。

- [x] **342.6 `buildMarket()`（台股，現行 `:583` 的 `MarketInput` 建構）補 `true`。** 台股的跨市場因子確實適用，行為必須逐位不變。

### 後端回測（`backend/src/main/java/com/steven/assets/service/BacktestService.java`）

- [x] **342.7 兩個建構點都要補，美股那個還要補量比。**

  - **`:4017`（台股回測）**：補 `true`。
  - **`:2931`（美股回測，`buildUsMarketRegimes`）**：補 `false`（`crossMarketApplicable`），**並把量比從 `null` 改為 `RadarInputAssembler` 既有的 `volumeRatio`**。

  > **為什麼美股回測非補不可（本任務最容易被漏掉的一項）：** 該處**現在就已經**與 production 不一致——回測的第 6 個引數（`completedChangePercent`）已填了 `change`，而 production 傳 `null`；只是被引擎 `marketActivity == null` 的短路吃掉整個區塊，所以至今沒有可觀察後果。**production 一旦補上量比，這個分支就會在線上生效、在回測不生效，而且沒有任何測試會抓到**——這正是本專案踩過的坑：`spec/tasks/t323_radar_us_market_volume_wiring.md` 的標題逐字就是「交易雷達美股大盤量能接線：`buildUsMarket()` 補上既有 IXIC 量能，**停止線上與回測分岔**」（對應 Requirement 64／65）；Task 299.5 亦明文規定「回測與 production 共用同一 assembler 輸出，**不得**在 `BacktestService` 另算一份」。

  `RadarInputAssembler` 的 `volumeRatio` 與 `TradingRadarMarketContextService.ratio()` 演算法逐項相同（前 20 個正成交量日的中位數為分母、要求至少 10 筆樣本、scale 4 HALF_UP），且 `buildUsMarketRegimes` 已把 `us_index_daily_history` 的 `volume` 映射進轉型後的列，資料就位。

  > **代價（必須知情）：** 這會改變既有 V13 walk-forward／holdout 的美股 regime 序列，Task 308／310／314／316 已記錄的美股校準結果失效，未來要 promote V13 時須重跑。使用者已知情並選擇承擔。**本任務不負責重跑校準**（見 342.11 排除項）。

- [x] **342.8 順手修三處會過期的版本字面值。** 用 `grep -ranE "V12 fallback|TW_RULES_V12" backend/src/main` 取得（該指令實測回 **7 行**，其中屬本項射程的是**三處**，其餘四行分別由 342.4 的「全部要改」與「不得改」兩類覆蓋）：`BacktestService` 的「…production runtime 仍明確維持 `TW_RULES_V12`。」與「…維持 V12 fallback。」，以及 `BacktestDto` 中同一句話的 javadoc 版（「…production runtime 仍由 V12 fallback 保護。」）。**只有第一處含字面值 `TW_RULES_V12`，另兩處寫的是「V12 fallback」**——342.4 的字面值 grep 抓不到它們，這正是本項要用上面那條 `-E` 指令的原因。三處都改為引用 `TradingRadarRuleEngine.RULE_VERSION` 或改寫為不含版號的「baseline fallback」措辭。

### 測試

- [x] **342.9 既有測試的處置。**

  **會紅的：** `backend/src/test/java/com/steven/assets/service/TradingRadarUsMarketVolumeWiringTest.java` 中斷言「美股 `MarketInput` 的量能與完成日漲跌幅兩欄仍為 null」的那條測試（`assertNull(usInput.marketVolumeRatio())` 與 `assertNull(usInput.completedChangePercent())`）。

  **處置：翻轉為「守同源」，不得直接刪除**（刪掉會失去 t323 當初為何刻意不填的稽核痕跡）：
  - `marketVolumeRatio` 改為斷言**等於同一份 `MarketSummary.marketVolumeRatio()`**（證明 `MarketInput` 與 `MarketSummary` 吃同一個 context，不是各算一份）
  - `completedChangePercent` 改為斷言等於該次 `resolveMarketFromRows(...)` 的 `completedMarketChangePercent()`
  - `marketTurnoverRatio` **維持 `assertNull`**（這是「不得偽造週轉率」的守門，不得動）
  - 既有的 nasdaq／sox／usTechComposite 三條 `assertNull` **原封保留**
  - 測試方法名一併改為守同源語意

  **應該不會紅但要複查的：** `TradingRadarUsStockEngineTest`（其 baseline stub 把 `resolveMarketFromRows` 回成空 context，全欄 null）、`TradingRadarMarketFreshnessTest`、`TradingRadarRuleEngineTest`（除版本斷言外）。跑完測試依實際結果處理，**不得為了讓測試變綠而回頭改規則**。

- [x] **342.10 新增測試，至少涵蓋六項：**
  1. 美股 `MarketInput.marketVolumeRatio()` 與同一次組裝的 `MarketSummary.marketVolumeRatio()` **同值**；`completedChangePercent()` 與 context 同源（同一個 as-of 日）。防止日後被改成各算一份。
  2. 跨市場旗標為 `false` 時 `risks` **不含**跨市場那則提醒；旗標為 `true` 且 `usTechAvailable=false` 時**仍含**。這是台股方向的護欄，防止日後被順手兩邊都關掉。

     ⚠ **斷言字串必須指名跨市場那一句的專屬片段**（例如「美股科技共同交易日」），**不得用泛用詞「資料不足」**——342.3 改完之後量價那句仍含「資料不足」四字，用泛用詞會在量比缺值的 fixture 下同時命中兩則而誤紅。
  3. 台股組的跨市場旗標必須為 `true`。
  4. `marketActivity` 僅由量比構成時，產生的理由文案**不出現**「成交金額」字樣（342.3 的守門）。
  5. production 與回測的美股 `MarketInput` **在本次接線的四欄同源**（`completedChangePercent`／`marketVolumeRatio`／`marketTurnoverRatio`／`crossMarketApplicable`），防 342.7 的分岔重演。

     **四欄的比較方式必須分開寫，不得一律要求「值相等」**——`completedChangePercent` 在兩側走的是**兩支捨入不同的 helper**：production 端為 `subtract().divide(previous, 10, HALF_UP).multiply(100).setScale(4, HALF_UP)`，回測端為 `divide(previous, 8, HALF_UP).subtract(ONE).multiply(100)`（**無 `setScale`**）。收盤價非整除時兩者連 `compareTo` 都不相等，`assertEquals(BigDecimal)` 更會因 scale 不同而必失敗。故：`marketVolumeRatio`／`marketTurnoverRatio`／`crossMarketApplicable` **三欄斷言值相等**（量比兩側演算法已驗為逐項相同）；`completedChangePercent` **只斷言同一 as-of 日且 `signum()` 相同**——引擎的四個計分分支只取 `signum()`，逐位相等既做不到也非必要。**不得**為了讓斷言過就把 production 改回區域變數 `changePercent`（342.5 明令禁止），也**不得**擅自把回測改呼叫 `resolveMarketFromRows`（不在本任務授權範圍）。

     ⚠ **斷言範圍必須限縮在這四欄，不得寫成「逐欄同源」——那是做不到的，而且會與已 landed 的決定衝突。** `price`／`changePercent`／`indicators`／`ma60Confirmation`／`ma240Confirmation` 這五欄 production 與回測**本來就不同**，且是 Requirement 77／Task 336 **明文列為範圍外、刻意保留**的狀態：`spec/design.md` 的收斂範圍表把「回測的美股 as-of regime（走 double `simpleMa`）」標為**範圍外**，並寫明「落地後存在一個**已知且刻意保留**的狀態：live 雷達美股 regime 走精確路徑、回測美股 regime 仍走 double」。此外 production 的 `price`／`changePercent` 取自**未經完成日過濾**的序列，回測取自 as-of window，兩者結構上就不會相等。寫成逐欄同源會讓這條測試必然失敗，或誘使實作者去收斂一個已被明確排除的項目。
  6. 升版後 `TradingRadarRuleEngine.RULE_VERSION` 為 `"TW_RULES_V14"`，且**不等於** `RuleParameters.V13_VERSION`（把「production 不得竊用 candidate 標籤」變成機器強制）。此項可放在 `TreasuryYieldServiceTest`（該檔已有 `RuleParameters.v12Default().ruleVersion()` 與 `RULE_VERSION` 的關聯斷言，需一併改為：`RULE_VERSION` 是 `TW_RULES_V14`、`v12Default().ruleVersion()` 仍是 `TW_RULES_V12`（回測 baseline 參數集，另一個命名空間）、兩者不得等於 `V13_VERSION`）。

- [x] **342.12 本批 spec 變更必須與實作同一個 commit 落地，不得單獨先行 commit。**

  本次多處已用**完成式**陳述尚未存在的狀態：`spec/requirements.md` 的 V13 發布 AC 寫「production **已於** Task 342 因美股量價接線升至 `TW_RULES_V14`」、`spec/design.md` 的美股個股支援小節寫「量能兩欄**自 Task 342 起已接上**」、`t323`／`t294` 的推翻註記寫「**已由** Requirement 83／Task 342 推翻」。這些敘述在實作落地前**全部是假的**（`TradingRadarRuleEngine.RULE_VERSION` 目前仍是 `TW_RULES_V12`）。

  本專案對這個風險已有具名前例：Requirement 77 的 AC 為了同樣理由硬性規定 `spec/steering/structure.md` 的對應段落「**不得單獨先行 commit，必須與實作同一個 commit 落地；若實作被擱置，須把該段還原**」。本項即該規則在本任務的等價約束。

  **若實作被擱置或中止**，須把上述四處完成式敘述改回條件式（或整段還原），不得讓 spec 停在「宣稱已完成但程式碼沒有」的狀態。

### 明確不在本次範圍

- [x] **342.11 以下一律不做，做了即為超出範圍：**
  - **跨市場三欄真正參與美股計分**——維持 Requirement 64 的排除，理由是重複計分。本次只改「不適用」的表達方式。
  - **`marketTurnoverRatio` 的資料來源**——`us_index_daily_history` 無成交值欄，要補須另立 Requirement 評估來源。**不得以成交量除以任何數字偽造週轉率。**
  - **重跑 V13 walk-forward 校準**——本次只揭露其失效，重跑屬 Task 308／310／314／316 的射程。
  - **前端任何邏輯改動**——文案由後端產生，前端只 render。前端僅有 `TW_RULES_V12` 兩處 hardcode 需隨 342.4 同步。
  - **台股組量價邏輯的行為變更**——僅文案措辭因 342.3 的動態標籤而變，加減分邏輯逐位不動。
  - **在卡片上新增「美股不計跨市場因子」的說明文字**——放進「風險提醒」區只是換一種方式誤導；該揭露屬 spec。
  - **`RuleParameters` 的任何改動**（見 342.4）。

---

## 驗證

```bash
# 後端測試（Mockito 需 byte-buddy 實驗旗標；務必用 -DextraArgLine，不得用 -DargLine——
# 後者會覆蓋 surefire 的時區設定，造成上百個測試 error 且錯誤訊息偽裝成 byte-buddy 問題）
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -f backend/pom.xml \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true test
```

> **不要用 `| tail` 之類的 pipe 包住 mvn** —— 拿到的 exit code 會是 tail 的、恆為 0。用 `> /tmp/x.log 2>&1; echo "exit=$?"`。
>
> **午夜前後跑全量測試須做 clean-tree 對照。** Task 335 實測到幾支匯出排程與 Google Drive 相關的測試類（`AssetTransactionExportScheduleServiceTest`、`CommodityExportScheduleServiceTest`、`RealizedGainExportScheduleServiceTest`、`ExportScheduleGdriveTest`、`TradingExportGdriveTest`）在跨午夜時會出現**每次集合都不同**的假失敗。判定方法：`git stash` 後跑一次取得 clean-tree 基準數，還原後再跑一次比對。

前端（僅版本字串同步，仍須確認編譯）：

```bash
cd /Users/steven/Project/asset-management/.claude/worktrees/trading-radar-tabs-tw-us-e8f0d3/frontend
/Users/steven/.nvm/versions/node/v22.21.0/bin/node /Users/steven/.nvm/versions/node/v22.21.0/bin/npm test
/Users/steven/.nvm/versions/node/v22.21.0/bin/node ./node_modules/.bin/vite build
```

部署（本專案沒有 dev server，「改好」＝ image rebuild + container recreate）：

```bash
# ⚠ 上一個 block 的 cd frontend 會外溢，且 compose 檔只存在於 worktree 根
cd /Users/steven/Project/asset-management/.claude/worktrees/trading-radar-tabs-tw-us-e8f0d3
cp /Users/steven/Project/asset-management/.env .          # worktree 沒有 .env；env_file 相對 compose 檔解析
docker compose -p asset-management build --no-cache business-services frontend
docker compose -p asset-management up -d --no-deps --force-recreate business-services frontend
docker compose -p asset-management restart bff            # business 換 IP，BFF 握舊 IP 會回 500 且 DNS TTL 600s 內不自癒
```

> **若 Docker registry 連線異常導致 buildkit 卡在 resolve base image**（Task 335 實測遇過，`docker pull` 本身也 hang），可加 `DOCKER_BUILDKIT=0` 用 legacy builder 繞過（本地 base image 齊全時可離線建）。但那會失去 `--no-cache` 的保證，**必須改用驗 image 內容的方式確認**：
> ```bash
> docker run --rm --entrypoint sh asset-management-business-services:latest \
>   -c 'cd /tmp && unzip -o -q /app/app.jar "BOOT-INF/classes/com/steven/assets/service/TradingRadarRuleEngine.class" && strings BOOT-INF/classes/com/steven/assets/service/TradingRadarRuleEngine.class | grep -c TW_RULES_V14'
> ```

API 層驗收：

```bash
docker exec asset-business-services curl -s \
  -H 'X-User-Id: 1' -H 'X-User-Role: ADMIN' -H 'X-User-Status: ACTIVE' \
  http://localhost:8080/api/trading-radar | python3 -c "
import sys, json
d = json.load(sys.stdin); u = d.get('usMarket') or {}
print('ruleVersion :', d.get('ruleVersion'))
print('score       :', u.get('score'), '| regime:', u.get('regime'))
print('volumeRatio :', u.get('marketVolumeRatio'))
print('risks       :')
for r in (u.get('risks') or []): print('   -', r)
print('reasons     :')
for r in (u.get('reasons') or []): print('   -', r)
"
```

**預期輸出：**
1. `ruleVersion` 為 `TW_RULES_V14`
2. `risks` **不含**「大盤完成日…資料不足」與「前一個已完成的美股科技共同交易日資料不足」任何一則
3. `reasons` **含**一則量價相關的理由（落地當下的資料應為「大盤完成日下跌但量能收斂，賣壓未擴大。」），且該句**不出現「成交金額」字樣**
4. **`score` 仍為 100、`regime` 仍為 `RISK_ON`**

> **第 4 點是預期，不是實作失敗。** 落地當下 IXIC 完成日漲跌 `-0.28%`、量比 `0.7915`，命中「完成日下跌但量能收斂」得 `+3`；但美股 regime 原已 100 分且引擎有分數上限截斷，故 raw 103 會被截回 100。**驗收時不得以「分數沒變」判定沒生效**——要看的是那兩則假訊息有沒有消失、以及有沒有多出正確的理由句。

瀏覽器實測（需登入，由使用者確認）：開 `/trading-radar` 美股分頁，確認兩則假風險提醒消失、支持訊號多一則量價理由、版本標籤顯示 `TW_RULES_V14`；台股分頁的量價文案若因 342.3 的動態標籤而改變，確認符合預期。

首輪通知靜音的驗證：升版後第一輪背景評估只重建 baseline 不寄信（**只影響台股訂閱**——通知評估對非台股標的直接 return），第二輪起恢復。

---

## 完成報告

**實作日期：** 2026-08-16

### 實際改動檔案

**後端生產程式碼（5 檔）**

- `TradingRadarRuleEngine.java`：`MarketInput` 新增第 13 個 component `boolean crossMarketApplicable`（5 參數相容建構式填 `true`）；跨市場區塊改為三段（不適用→沉默／適用但缺資料→維持既有提醒／適用且有資料→加減分不動）；量價「資料不足」句改為不列舉欄位的中性句；兩句加減分理由改由純函數依實際可用 ratio 內插標籤；`RULE_VERSION` → `TW_RULES_V14`，javadoc 重寫（新增 V14 段、V12 降為歷史段、明寫「跳過 13」的理由）。
- `TradingRadarService.java`：`buildUsMarket()` 的三個 `null` 改為 `usContext.completedMarketChangePercent()`／`usContext.marketVolumeRatio()`／`null`（turnover 維持），並補 `crossMarketApplicable=false`；`buildMarket()`（台股）補 `true`。**`usContext == null` 的三元防護保留**。
- `BacktestService.java`：`buildUsMarketRegimes()` 補 `a.volumeRatio()` 與 `crossMarketApplicable=false`；台股回測補 `true`；`:1012` 改為串接 `RULE_VERSION`、`:1016` 改為「baseline fallback」。
- `TradingRadarDto.java`／`BacktestDto.java`：javadoc 版本字串同步。

**前端（1 檔，僅版本字串）**

- `TradingRadarView.vue`：顯示 fallback 與 `radar` ref 初始值兩處 `TW_RULES_V12` → `TW_RULES_V14`。**無其他前端改動**（文案由後端產生）。

**測試（6 檔，含 1 個新檔）**

- `TradingRadarUsMarketVolumeWiringTest`：原「量能兩欄仍為 null」翻轉為**守同源**（斷言等於同一份 context 且等於 `MarketSummary.marketVolumeRatio()`），`marketTurnoverRatio` 的 `assertNull` 與跨市場三條 `assertNull` **原封保留**，方法名一併改；另加一支驗兩則假提醒消失。
- `TradingRadarRuleEngineTest`：+4（跨市場旗標三段行為、量價理由只列舉實際分量、缺值句不再出現「成交量或成交金額」、相容建構式預設 `true` 會產生正當提醒）。
- `TradingRadarMarketInputCrossMarketFlagTest`（**新檔**）：+2（production 與回測四欄同源、台股組旗標為 `true`）。
- `TreasuryYieldServiceTest`／`TradingRadarV13ActionPolicyTest`：兩支因升版會紅的測試，皆改斷言本身而非改 `RuleParameters`，並把「production 不得竊用 candidate 標籤」變成機器強制；方法名一併改。
- `BacktestServiceTest`：版本斷言與 `@DisplayName` 同步。

### 驗證輸出

**後端測試（主 agent 獨立重跑一次，未採信 subagent 回報）**

```
[INFO] Tests run: 1110, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

實作前基準為 1103（Task 335 落地後為 1018，其後 t336–t340 陸續 landed），本次 +7 與新增測試數相符。執行時間非午夜前後，未觸發匯出排程那組 wall-clock 相依 flake。

**版號同步的兩道 grep**

- 字面值 `grep -ran "TW_RULES_V12"`（原 10 檔 19 處）：常數本體與 javadoc、DTO javadoc、前端兩處、`TradingRadarRuleEngineTest`（連方法名）、`BacktestServiceTest` 4 處、`TreasuryYieldServiceTest`、`BacktestService:1012` **全部已改**；`RuleParameters` 2 處、`TradingRadarCalibrationSelectorTest`、`InternalBacktestControllerV13Test` 5 處 **依規定不改**。
- 常數 `grep -ranE "V12_VERSION|v12Default"`（原 25 處）：production 16 處與 registry／calibration 測試 7 處語意未變不動；唯二要改的 `TradingRadarV13ActionPolicyTest` 與 `TreasuryYieldServiceTest` 已改。**這一道正是字面值 grep 抓不到 `TradingRadarV13ActionPolicyTest` 的補漏機制**（spec 審查第二輪的發現）。

**前端**：`npm test` 9 pass / 0 fail；`vite build` exit 0。worktree 無 `node_modules`，驗證時暫時 symlink 主 repo 同一份（`package.json` 已比對相同），建完已移除 symlink 與 `dist/`。

### 架構符規查證

已派 `arch-auditor`（diff-scoped）。判定 **critical 0 ／ major 0 ／ minor 1**，已 `bash .claude/hooks/arch-review-pass.sh` 記錄。

**唯一的 minor 已處置：** 回測與 production 的 IXIC 量比走兩份實作（`RadarInputAssembler.volumeRatio()` vs `TradingRadarMarketContextService.ratio()`），觸發 `spec/steering/structure.md` §3.2 鐵則 4「同義值的第二份實作必須具名登記」。稽核已量化確認兩支演算法逐項相同（lookback 20／最少 10 筆／中位數 `divide(2,8,HALF_UP)`／`divide(median,4,HALF_UP)`／分母排除最新日），且 IXIC 為指數不涉分割還原，無正確性風險。

稽核給的修法之一（回測改呼叫 `resolveMarketFromRows`）**被本任務 342.10 明令禁止**，故採另一條：已在 `structure.md` §3.2 新增「**具名例外之三（Task 342 登記，狀態：併存，已量化確認同值）**」完整記錄兩份實作、同值論證、以及「為何本任務沒有收斂」（本任務的射程是消除更嚴重的 production／回測分岔，收斂兩支 helper 屬獨立任務），並把結尾的「兩組例外」改寫為三組。此為 t335 對 IXIC 均線採用的同一處理標準。

### 與原計畫的偏差及原因

1. **spec 在實作前跑了三輪對抗式審查，共修 1 critical／9 major／15 minor。** 其中三條是會直接寫錯程式碼的實質缺陷：
   - **編號撞號（critical）**：原取 Task 339／Requirement 80，寫完 spec 才發現兩者都被 `local-analysis-replace-api-e5fa7c` 佔用且隨即 landed，已避讓為 341／82。避讓時 regex 誤傷過 upstream 兩處（`design.md` 中屬於別人的「Requirement 80／Task 339：資產配置建議」），已還原並經第二、三輪確認無殘留。
   - **「四欄同源」對 `completedChangePercent` 做不到（major）**：production 與回測走兩支捨入不同的 helper（`setScale(4)` vs 無 `setScale`），值不可能相等。已改為「三欄值相等 ＋ 該欄只比 `signum()` 與 as-of 日」，並明文禁止兩條錯誤的繞道。
   - **升版會弄紅一支 grep 抓不到的測試（major）**：`TradingRadarV13ActionPolicyTest` 比對的是 `RuleParameters.V12_VERSION` **常數**而非字面值。已在任務檔補該檔處置與第二道 grep。
2. **回測側改用反射呼叫 `buildUsMarketRegimes()`（唯一的實作偏離）。** 該方法為 private，唯一公開入口是整套 V13 `run()`（需鋪 stock／dividend／fundamental fixture）。採本專案既有作法（`ProcessRcloneClientRateLimitTest`／`ExcelImportServiceTest`／`TradingRadarNotificationMarketBatchTest` 等皆用反射）；方法被改名會以 `NoSuchMethodException` 大聲失敗，不會靜默失效。arch-auditor 明確表示這不構成 finding。
3. **台股既有可見文案會改變（342.3 的動態標籤帶來的預期副作用）：** `marketTurnoverRatio` 為 null 的日子（`ratio()` 要求至少 10 筆正值樣本），文案從「量能／成交金額同步放大」變成「量能同步放大」。**這是修正而非退步**——原文案在講一個當下不存在的值。
4. **升版會觸發通知基準重建，且代價落在台股。** 實測 `trading_radar_notification_setting` 現有 21 筆 `rule_version='TW_RULES_V12'`，升 V14 後會被視同未初始化而重建 baseline（不寄信、不刪訂閱狀態列、不刪收件人）。欄位為 `varchar(30)`，`TW_RULES_V14` 不會截斷。**反直覺處：通知評估對非台股標的直接 return，本次改的是美股，這個代價 100% 落在完全沒被改動的台股設定上**——這是升版的既定成本，Task 264／298 都付過。
5. **Task 342 仍與 `trading-radar-no-trades-a47b9f` 的 `t341_portfolio_advice_subclass_breakdown.md` 撞號**（兩邊都未 landed，Requirement 82 未撞）。本任務不再繼續改號——本專案並行度高到每隔數分鐘就有新 worktree 取號。依 CLAUDE.md 規則：誰先 landed 誰保留，後到者改自己那邊。**merge 前已複查 `origin/main`**。
6. **任務檔的一處不足（供日後改進）：** 驗證段的 `vite build` 假設 worktree 有 `frontend/node_modules`，但 `.gitignore` 含該目錄、不隨 worktree 建立。日後該段應補一句「worktree 需先 symlink 主 repo 的同一份或跑 `npm ci`」。
