# [t336] 收斂 IXIC 均線 MA5／20／60／240 的兩條算術路徑——交易雷達美股組改用 BigDecimal 精確路徑

**對應 Requirements:** Requirement 77（IXIC 的四條均線在「股市大盤查詢」頁與交易雷達美股分頁必須是同一個數字，把後者的 double 累加改為 BigDecimal 精確路徑）
**前置任務:** 無（Task 335 已 landed 於 main，本任務不依賴其未落地的部分）
**Liquibase changeset:** 無（純計算路徑修正，不動 schema、不動資料）

---

## 背景

### 現在的錯誤行為

那斯達克綜合指數（IXIC）的均線 MA5／20／60／240 在本專案有**三份顯示用實作**（另有第四份供回測用，見 336.3 與 336.6 的表格），全部讀同一張表 `us_index_daily_history`（`index_code='IXIC'`）、同樣只用已落地日線收盤、都不併即時價。**其中 (a) 與 (c) 是 BigDecimal 精確路徑、(b) 是 double 累加**，因此 (b) 與另外兩份**偶爾會差 0.01**：

- **(a) BFF 那一份**（`bff/src/main/java/com/steven/assets/bff/gdptwse/MarketIndexChartService.java` 的 `movingAverage(List<BigDecimal> values, int window)`）：BigDecimal 精確滾動和，最後 `sum.divide(BigDecimal.valueOf(window), 2, RoundingMode.HALF_UP)`。服務「股市大盤查詢」頁的圖表，涵蓋 9 個市場（TWSE／DJI／SPX／IXIC／SOX／FTSE／DAX／KOSPI／N225）的整段序列。
- **(b) backend 那一份**（`backend/src/main/java/com/steven/assets/service/TechnicalIndicatorService.java` 的 `nasdaqSimpleMa(List<UsIndexDailyHistory> desc, int days)`，約 `:669`）：**double 累加**（由最新往回加），最後 `BigDecimal.valueOf(sum / days).setScale(2, RoundingMode.HALF_UP)`。服務 `computeAllForNasdaq()` → `TradingRadarService.buildUsMarket()` → 交易雷達頂部大盤卡片的美股分頁。**本任務要改的就是這一支。**
- **(c) backend 匯出那一份**（`backend/src/main/java/com/steven/assets/service/ExcelExportService.java` 的 `indexMaAt(List<IndexDailyRow> asc, int i, int window)`，約 `:616`）：BigDecimal 精確加總、只在最後 `divide(window, 2, HALF_UP)` 捨入一次。服務「股市大盤查詢」頁匯出的「週線MA5／月線MA20／季線MA60／年線MA240」四欄。**它確實會產出 IXIC 均線**——`findIndexDaily(market, ...)` 只有 `"TWSE".equalsIgnoreCase(market)` 一條分支、其餘一律走 `us_index_daily_history`，而 `MacroHistoryService.DAILY_INDEX_CODES` 含 `IXIC`。因它本就精確，收斂後與 (a)(b) 同值，**本次不動**。

現行 (b) 的完整逐字實作為：

```java
private static BigDecimal nasdaqSimpleMa(List<UsIndexDailyHistory> desc, int days) {
    if (desc.size() < days) return null;
    double sum = 0;
    for (int i = 0; i < days; i++) sum += desc.get(i).getClosePoint().doubleValue();
    return BigDecimal.valueOf(sum / days).setScale(2, RoundingMode.HALF_UP);
}
```

### 正確行為

三份顯示用實作對同一組收盤價、同一視窗，必須產出**逐位相同**的值。

### 重現條件

當某視窗收盤價的**精確和除以視窗長度**恰好落在 `x.xx5`（第三位小數為 5 且其後全 0）時，double 累加的末位誤差會讓和變成略小於精確值，`HALF_UP` 因此向下捨入，而精確路徑向上捨入，兩者差 0.01。運行中 DB 的 IXIC 全量日線（2559 筆，2016-06-10 ~ 2026-08-14）實測命中兩天：

| 日期 | 視窗 | 精確和 | 精確商 | (a) 精確路徑 | (b) double 路徑 |
|---|---|---|---|---|---|
| 2018-07-17 | MA20 | `153353.5000` | `7667.675` | `7667.68` | double 和 `153353.49999999997` → `7667.67` |
| 2025-12-22 | MA20 | `465875.5000` | `23293.775` | `23293.78` | double 和 `465875.4999999999` → `23293.77` |

### 為什麼既有的免罪理由都不適用

`spec/steering/structure.md` §3.2「BFF 設計鐵則」第 4 條是「同義欄位 → 同一支 business service API」，其下已有一組具名例外（台股大盤均線的三份實作，編號 (1)(2)(3)）。**那組的兩個免罪理由對 IXIC 都不成立**：

1. **例外 (3) 的理由是「語意不同（含 live）」** —— 但 `computeAllForNasdaq()` **刻意不併即時價**（IXIC 無對應 Redis 即時報價來源），既有測試 `backend/src/test/java/com/steven/assets/service/TechnicalIndicatorNasdaqTest.java` 有一條 `序列充足時MA240與KD皆正常算出且完全不查詢即時價` 明文鎖住這件事。(a)(b)(c) 都只用已落地日線收盤，本應同值。
2. **例外 (1)(2) 的理由是「算術上逐位相同」** —— 該論證的前提是台股收盤為 `numeric(12,2)`、和除以 5 恆為第三位小數為偶數而碰不到 HALF_UP 邊界。但 `us_index_daily_history.close_point` 是 **`numeric(14,4)`**，而且**這四位小數不是名目**：運行中 DB 實測 2559 筆 IXIC 日線的有效小數位分布為 0 位 20 筆、1 位 20 筆、2 位 271 筆、**4 位 2248 筆（87.8%）**。前提不成立。

### 為什麼 Task 335 沒有順手做

(a)(b) 這對分歧的實作都早於 Task 335（`nasdaqSimpleMa` 為 Task 294.1、`movingAverage` 為既有），Task 335 只是讓 (b) 的輸出**首次上畫面**，使「同義值在兩頁顯示」的條件成立。收斂必然要改 `computeAllForNasdaq()` 的輸出，而該輸出經 `indicators(ind)` 進 `MarketInput` → 直接影響美股 regime 與買進閘門；Task 335 的 Requirement 76 明文以「純揭露、`action`／`score`／`regime` 逐位不變」作為不升 `RULE_VERSION`（Task 281 先例）的前提，順手改會使該前提失效。

---

## 要做什麼

### 336.1 spec（本任務已完成的部分，實作者不必重做，但必須讀懂約束）

- [x] `spec/requirements.md` 新增 Requirement 77。
- [x] `spec/design.md` 新增「IXIC 均線兩條算術路徑的收斂（Requirement 77，Task 336）」段落。
- [x] `spec/tasks/t336_ixic_ma_arithmetic_convergence.md`（本檔）。
- [x] `spec/tasks.md` 索引新增一列、標題編號範圍改為 311–336。
- [x] `spec/tasks/README.md` 的索引範圍字樣同步改為 311–336。
- [x] `spec/steering/structure.md` §3.2 鐵則 4「具名例外之二」由「待收斂」改寫為「已收斂」（並改列**三份**顯示用實作、加記回測那份 double 殘餘）。
- [x] `CLAUDE.md` 與 `spec/steering/structure.md` 的「N 個 Requirements」計數由 76 改為 77（`scripts/spec-check.sh` 的 B7 會擋計數漂移）。

> ⚠️ **時序約束（硬性）**：`structure.md` 是長期 steering context，上面那段現在已經寫成「已收斂」，
> 但在 336.2 落地前程式仍是 double。故**本次 spec 變更不得單獨先行 commit**，必須與 336.2 的實作
> 在**同一個 commit** 落地。若本任務被擱置或放棄，須先把 `structure.md` 那段還原為「待收斂」再收工——
> 否則後續讀者與 `arch-auditor` 會得到「IXIC 均線已收斂」的錯誤結論。

### 336.2 改 `nasdaqSimpleMa` 為 BigDecimal 精確路徑

- [ ] 把 `backend/src/main/java/com/steven/assets/service/TechnicalIndicatorService.java` 的 `nasdaqSimpleMa` 改為：

```java
/**
 * IXIC 均線：BigDecimal 精確和後 divide(days, 2, HALF_UP)（Task 336）。
 *
 * <p><b>刻意與同類別的 {@link #simpleMa}／{@link #taiexSimpleMa}／{@link #maAt} 三支 double
 * 累加版不同，不是漏改。</b>IXIC 的同一個顯示值全站有三份實作——本支、BFF 的
 * {@code MarketIndexChartService.movingAverage(...)}、以及匯出的
 * {@code ExcelExportService.indexMaAt(...)}（同讀 {@code us_index_daily_history} 的 IXIC
 * 已落地日線收盤、都不併即時價），另外兩份本就是精確路徑。double 累加會在精確商恰為
 * {@code x.xx5} 時與精確路徑差 0.01——實測 2016-06-10~2026-08-14 的 2559 筆 IXIC 日線中，
 * MA20 有 2 日（2018-07-17、2025-12-22）命中。改為精確路徑後三份等值<b>由構造保證</b>
 * （同一批收盤、同一視窗、同樣 divide(w, 2, HALF_UP)；BigDecimal 加法可結合，故累加方向
 * 不影響結果），不需抽樣論證。</p>
 *
 * <p>另外三支 helper 服務台股大盤與個股、屬 structure.md §3.2 鐵則 4 具名例外 (3)（含 live）
 * 的範圍，其收斂前提是先決定「MA 要不要併 live」，屬獨立任務；本次不得一併改動。連帶地
 * {@code BacktestService.buildUsMarketRegimes()} 走的 {@link #simpleMa} 仍是 double，故本支
 * 改動後 live 與回測的 IXIC 均線由 bit-identical 變為存在 0.01 上限的分歧（歷史重放 0 日
 * 翻動 regime），該殘餘為刻意保留、已登記於 structure.md。</p>
 */
private static BigDecimal nasdaqSimpleMa(List<UsIndexDailyHistory> desc, int days) {
    if (desc.size() < days) return null;
    BigDecimal sum = BigDecimal.ZERO;
    for (int i = 0; i < days; i++) sum = sum.add(desc.get(i).getClosePoint());
    return sum.divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP);
}
```

- [ ] 保留既有的 `if (desc.size() < days) return null;` 短路，語意不變（視窗不足回 null，不擲例外）。
- [ ] `RoundingMode` 與 `BigDecimal` 的 import 該檔已有，不需新增。
- [ ] **同步修正 `computeAllForNasdaq()` 的既有 javadoc（約 `:611`）**（本項由本任務的 `arch-auditor` 稽核 minor finding 追加）。該處現行逐字為「MA 核心比照 `{@link #computeAllForTaiex()}` 的既有寫法（`{@link #nasdaqSimpleMa}`）」，而 `computeAllForTaiex()` 走的 `taiexSimpleMa` 仍是 double——本次改動後這句話在**算術**這一層已不成立。須把「比照」的範圍限縮為**呼叫形狀**並明寫算術自本任務起刻意不同，例如：「MA 核心比照 `computeAllForTaiex()` 的**呼叫形狀**（專屬 desc-list helper、不走 `computeFromSeries`）；**算術路徑自 Task 336 起刻意不同**（本支為 BigDecimal 精確、`taiexSimpleMa` 仍為 double），見 `nasdaqSimpleMa` 的方法註解。」**只改註解文字，不得順手改任何程式行為**。留著一句已失效的理由，正是本任務要消滅的那種失誤（Task 335 的 arch finding 成因即是「沿用了一個不適用的免罪理由」）。

### 336.3 明確不得做的事（爆炸半徑控制）

- [ ] **不得改動同類別另外三支同族 helper**：`simpleMa(List<StockPriceHistory>, int)`（約 `:384`，個股，服務 `computeFromSeries()`）、`taiexSimpleMa(List<TwseIndexDailyHistory>, int)`（約 `:597`，台股大盤 `0000`）、`maAt(List<StockPriceHistory>, int, int)`（約 `:376`，走勢圖整段序列）。它們服務台股大盤與個股，動了會直接翻動台股大盤 regime 與**全部**個股的 `action`／`score`。
- [ ] **不得破壞 `maAt` 的既有 javadoc 契約**：該處逐字寫著「累加方向必須是『新→舊』，與 `simpleMa`／`taiexSimpleMa` 一致」。其列舉的對齊對象**不含** `nasdaqSimpleMa`，故本次改動不觸及該契約；但也不得順手把 `nasdaqSimpleMa` 加進那份列舉。
- [ ] **不得改 `TradingRadarRuleEngine.RULE_VERSION`**，維持 `TW_RULES_V12`（理由見下方「升版判準」）。
- [ ] **不得改 BFF 的 `movingAverage`**：它是收斂目標本身，且服務另外 8 個市場。
- [ ] **不得改 `TradingRadarRuleEngine.confirm(...)` 內部的 `average(values, start, count)`**：那是 scale-8 的兩收盤日確認內部比較值、不對外顯示，與本次的 2 位小數顯示值語意不同。
- [ ] **不得改 `ExcelExportService.indexMaAt`**——但**理由不是「它不產出 IXIC」**（那是錯的）。它**確實會產出 IXIC 均線**：`findIndexDaily(market, start, end)`（約 `:625`）只有 `"TWSE".equalsIgnoreCase(market)` 一條分支，其餘一律走 `usIndexHistRepo.findByIndexCodeAndTradingDateBetweenOrderByTradingDateAsc(market, ...)`，而 `MacroHistoryService.DAILY_INDEX_CODES` 含 `IXIC`，`GET /api/index-daily/export?market=IXIC` 會輸出「週線MA5／月線MA20／季線MA60／年線MA240」四欄。不改它的真正理由是**它本來就是精確路徑**（`ExcelExportService` 約 `:616-623`：BigDecimal 精確加總、只在最後 `divide(window, 2, HALF_UP)` 捨入一次，javadoc 已明文與 BFF 那份對齊），收斂後三者同值。
- [ ] **不得改 `BacktestService.buildUsMarketRegimes()`（約 `:2911`）與它所依賴的 `simpleMa`**：該方法同樣以 IXIC 日線算 MA、餵進**同一支** `ruleEngine.evaluateMarket()`，但走 `RadarInputAssembler.assemble(...)` → `TechnicalIndicatorService.computeFromSeries(...)` → `simpleMa`（double 累加）。改 `simpleMa` 會翻動全部個股的 `action`／`score`。故本次落地後會存在一個**已知且刻意保留**的狀態：live 雷達的美股 regime 走精確路徑、回測的美股 regime 仍走 double 路徑。**殘餘分歧已量化，且方向必須寫清楚**：改動**前**，live 的 `nasdaqSimpleMa` 與回測的 `simpleMa` 同為 double、同為 desc（新→舊）、同一批未調整收盤（`DistributionAdjustedPriceService` 對無配息事件的指數原樣回傳），兩者 **bit-identical**；改動**後** live 走精確、回測仍走 double，出現上限 0.01 的分歧。因兩條路徑的累加方向相同，下方 336.4 的重放結果直接適用——`price.compareTo(ma)` 正負號翻動 0 日，故 2319 個可重放交易日中回測與 live 的美股 `regime` **0 日不同**。**這是本次改動新造出來的殘餘，不是原本就有的舊帳**；完成報告必須據實寫出，不得寫成「已收斂」「保留既有狀態」或略過不提。

### 336.4 升版判準：不升 `RULE_VERSION`，但兩條既有先例都不適用，須新增第三條

- [ ] `TradingRadarRuleEngine.RULE_VERSION` 維持 `"TW_RULES_V12"`。前端 `TradingRadarView.vue` 兩處 hardcode fallback（約 `:28` 與 `:978`）亦不動。
- [ ] **不得逕行援引既有兩條先例中的任何一條。** `spec/design.md` 那份清單已兩度以嚴格解讀使用（Task 335 就是以「response 結構多一個 component、快照 content hash 會變」判定自己不符 Task 249），本次若改用寬鬆解讀套同一條，正是該處「互斥、不得混用」所要防的事。逐條核對：
  - **Task 281 不適用**：它成立於「新增欄位純揭露——不進 `StockInput`／`MarketInput`」，但本次改的 MA 確實經 `TradingRadarService.buildUsMarket()` 的 `indicators(ind)` 進入 `MarketInput`，再經 `TradingRadarRuleEngine.evaluateMarket()` 的 `priceVsMa(price, ma20, 8)`／`(ma60, 12)`／`(ma240, 15)` 進分數。
  - **Task 249 的字面條件也不滿足**：`buildUsMarket()` 把 `ind.weeklyMa()`／`monthlyMa()`／`quarterlyMa()`／`annualMa()` 直接寫進 `MarketSummary` 回傳前端（約 `:722-725`），2018-07-17 與 2025-12-22 兩天 `usMarket.monthlyMa` 會由 `7667.67`／`23293.77` 變為 `7667.68`／`23293.78`，故「同一份輸入前後產生完全相同的輸出」在**顯示值**這一層不成立。
- [ ] **須在 `spec/design.md` 的先例清單（該處原文為「不升版的先例有兩個」，Task 336 起改為三個）新增第三條**，逐字為：「**規則引擎輸出（`action`／`score`／`regime`／`reasons`／`risks`）逐位不變，且變動的僅是顯示值本身的捨入缺陷修正——該修正使既有實作彼此趨於一致、不引入任何新值**」。不寫進去的話，下一個任務會再次踩進同一個模糊地帶。（此項屬 336.1 的 spec 工作，已完成，實作者只需確認該段存在、不得改動它。）
- [ ] 不升版的立論以運行中 DB 的實資料逐日重放驗證，不是只憑論證。Task 249 那條先例的原文理由逐字為「升版只會製造假的不可比性訊號並觸發 Requirement 44 的通知基準全面重建」——本次的**規則引擎輸出**正是逐位不變：

| 量測項 | 結果 |
|---|---|
| 資料範圍 | IXIC 2559 筆日線，2016-06-10 ~ 2026-08-14 |
| MA 值不同的交易日 | MA20 **2 日**／2540 可評估日（0.08%）；MA5 0／2555、MA60 0／2500、MA240 0／2320 |
| `price.compareTo(ma)` 正負號翻動 | MA20／MA60／MA240 各 **0 日** |
| `score` 差異 | 2319 個可重放交易日中 **0 日** |
| `regime` 翻動 | **0 日** |
| 邊界餘裕 `min｜收盤 − MA(精確)｜` | MA20 `0.1502`（2016-08-30）、MA60 `0.2901`（2017-07-03）、MA240 `1.6999`（2019-02-13） |

  重放可完整覆蓋美股分支的計分，因為 `buildUsMarket()` 對 `MarketInput` 的 `completedChangePercent`／`marketVolumeRatio`／`marketTurnoverRatio` 與跨市場四欄一律傳 `null`／`false`，那兩組在 `evaluateMarket()` 內走的都是「資料不足、不採計分數」分支。【**Requirement 83／Task 342 起不再成立**：量能兩欄已於該任務接進 production 與回測兩側，跨市場再多一個 applicability component。此結論只涵蓋 MA 路徑、**不再涵蓋量價欄**；後續重放或重跑 V13 校準須以升版後的實際欄位重新界定範圍】計分**前**另有 `complete(input)` 前置檢查：`price`／`changePercent`／`ma20`／`ma60`／`ma240`／`k`／`d`／`ma60Confirmation`／`ma240Confirmation` 任一為 null（或兩個 confirmation 為 `UNAVAILABLE`）即直接回 `MarketResult(null, DATA_INCOMPLETE, ...)`、`score` 為 null，不進下列計分。通過該檢查後，美股大盤分數＝`50 + priceVsMa(ma20,8) + priceVsMa(ma60,12) + priceVsMa(ma240,15) + confirmationScore(c60,5) + confirmationScore(c240,5) + (K>D ? +5 : −5) + (changePercent ≤ −3 ? −10 : 0)`，clamp 至 [0,100]，`≥65 RISK_ON`／`≥40 NEUTRAL`／否則 `RISK_OFF`。注意 `changePercent ≥ 3` **只加 risk 文案、不加分**（Task 264 起刻意移除該加分），`priceVsMa` 為三態（大於 `+w`／小於 `−w`／相等 `0`）。

- [ ] **殘餘風險必須據實寫，不得寫成「不可能」**：翻面在結構上並非不可能。`close_point` 為 4 位小數而 MA 為 2 位小數，若某日收盤恰好落在兩個候選 MA **之間**（例如 `7667.6750`），精確路徑判 BELOW、double 路徑判 ABOVE，`priceVsMa` 的差值是 **2×權重**（MA240 為 30 分），足以翻動 regime 與買進閘門。本任務的立論是「十年實測 0 次且邊界餘裕 15 倍」這個**經驗事實**，不是「數學上不可能」。程式註解與 spec 都不得寫成後者。
- [ ] **若日後真要升版必須跳到 `TW_RULES_V14`**：`RuleParameters.V13_VERSION = "TW_RULES_V13"` 已是**離線 calibration／candidate 參數集**的版本字串（`TradingRadarRuleEngine.evaluateCandidate()` 明文 `if (!RuleParameters.V13_VERSION.equals(parameters.ruleVersion())) throw ... "candidate evaluation 必須使用 TW_RULES_V13 參數"`）。本次不升版，此項只是留下紀錄，**不需要也不得在本次改任何版本字串**。

### 336.5 測試

- [ ] 在既有的 `backend/src/test/java/com/steven/assets/service/TechnicalIndicatorNasdaqTest.java`（現有 5 個 `@Test`，`@ExtendWith(MockitoExtension.class)`，以 mock 的 `usIndexDailyHistoryRepo.findTopNByIndexCodeOrderByTradingDateDesc("IXIC", 240)` 餵資料）新增**兩條**測試：

  - [ ] **(a) 邊界迴歸案例——必須逐字釘住這 20 筆真實收盤，依「新→舊」順序餵入**（mock 的 `findTopNByIndexCodeOrderByTradingDateDesc` 本就回 desc，與 production 一致）：

    ```
    7855.1201, 7805.7202, 7825.9800, 7823.9199, 7716.6099, 7759.2002, 7756.2002,
    7688.3901, 7586.4302, 7502.6699, 7567.6899, 7510.2998, 7503.6802, 7445.0801,
    7561.6299, 7532.0098, 7692.8198, 7712.9502, 7781.5098, 7725.5898
    ```

    （即 `us_index_daily_history` 中 `index_code='IXIC' AND trading_date <= '2018-07-17'` 的最近 20 筆，精確和 `153353.5000`、精確商 `7667.675`。）斷言 `computeAllForNasdaq().monthlyMa()` 為 `7667.68` 而**非** `7667.67`。斷言請用 `assertEquals(0, expected.compareTo(actual))` 或 `assertThat(actual).isEqualByComparingTo(...)`，**不要用 `equals`**——`BigDecimal.equals` 連 scale 都比，`7667.68` 與 `7667.6800` 會判不相等而產生假紅燈。
  - [ ] **不得自行構造「精確和恰為 153353.5000」的等值序列。** 紅燈的必要條件**不是**「精確和等於 153353.5」，而是「**這 20 個具體值、以新→舊順序做 double 累加後嚴格小於精確和**」。實測對照（本任務已跑過）：

    | 序列 | double 累加和 | double 路徑 | 精確路徑 | 結果 |
    |---|---|---|---|---|
    | 真實 20 筆（新→舊） | `153353.49999999997` | `7667.67` | `7667.68` | **紅燈（唯一可用）** |
    | 同一批反轉（舊→新） | `153353.50000000003` | `7667.68` | `7667.68` | 綠燈 |
    | `[7667.675] × 20` | `153353.5` | `7667.68` | `7667.68` | 綠燈 |
    | `7667.00 × 19 + 7680.50` | `153353.5` | `7667.68` | `7667.68` | 綠燈 |

    連順序都會翻面。照「構造等值序列」寫會得到一條恆綠、什麼都沒鎖住的測試。
  - [ ] **此案例必須先在未改動的程式上跑出紅燈**才算數。做法：先只加測試不改 `nasdaqSimpleMa`，跑一次確認 fail（預期收到 `7667.67`），再改實作跑成綠燈。**沒驗過紅燈的迴歸測試等於沒鎖住行為**，完成報告要寫出這一次紅燈的實際輸出。
  - [ ] **(b) 四視窗與精確路徑等值的性質測試**：對一組足夠長（≥240 筆）的收盤價序列，在測試內另寫一份獨立的精確參考實作（`sum.divide(BigDecimal.valueOf(w), 2, RoundingMode.HALF_UP)`），逐一比對 `weeklyMa()`（MA5）／`monthlyMa()`（MA20）／`quarterlyMa()`（MA60）／`annualMa()`（MA240）四個視窗。**四個都要驗**，不得只驗 MA20。注意 `FullIndicators` 的欄位名與視窗的對應是 `weeklyMa=MA5`／`monthlyMa=MA20`／`quarterlyMa=MA60`／`annualMa=MA240`。

- [ ] 既有測試全部維持通過，其中這幾支是本次的關鍵回歸錨點，**不得為了讓它們過而修改它們的斷言**：
  - `backend/src/test/java/com/steven/assets/service/TechnicalIndicatorSeriesAlignmentTest.java`（含 `均線累加方向必須與computeAll一致否則末位會翻面`、`台股大盤0000的序列尾筆必須等於computeAll的大盤值`）——證明個股與台股大盤的累加方向契約未被波及。
  - `backend/src/test/java/com/steven/assets/service/TechnicalIndicatorNasdaqTest.java` 既有 5 條（含 `序列充足時MA240與KD皆正常算出且完全不查詢即時價`）。
  - `backend/src/test/java/com/steven/assets/service/TradingRadarUsStockEngineTest.java`。
  - `backend/src/test/java/com/steven/assets/service/export/DualFormatSingleTableExportTest.java`（`MA5值與JSON`：`:431`、`長天期均線值與JSON`：`:481`）——那是產出點 (c) `ExcelExportService.indexMaAt` 的定義錨點。本次不改 (c)，這兩條必須原樣通過。
  - `bff/src/test/java/com/steven/assets/bff/gdptwse/MarketIndexChartServiceTest.java` 與 `bff/src/test/java/com/steven/assets/bff/gdptwse/GdpTwseBffControllerMaTest.java`——證明 BFF 那份未被改動。**後者才是以純函式呼叫直接釘住 `MarketIndexChartService.movingAverage(List, int)` 定義的那一支**（前者只經 `ma5()/ma20()/ma60()/ma240()` 間接覆蓋）。

### 336.6 落地前的前提復驗

- [ ] 跑 `grep -ran "IXIC" backend/src/main/java/ bff/src/main/java/`，確認 IXIC 均線的產出點**仍然只有下表四個**。**`-a` 不可省**：本專案 `grep -r` 會把部分 `.java`（如 `AlertNotificationDispatcher`／`PerformanceComparisonService`）判為 data 而整檔靜默跳過，曾造成「零呼叫端」的誤判。**也不得改用 `grep -rn "nasdaqSimpleMa\|computeAllForNasdaq"`**——`BacktestService` 這兩個識別字都沒有，那樣 grep 在結構上就找不到它。

| # | 產出點 | 算術路徑 | 用途 | 本次 |
|---|---|---|---|---|
| (a) | `bff` `MarketIndexChartService.movingAverage` | BigDecimal 精確 | 「股市大盤查詢」頁圖表 | 不動 |
| (b) | `backend` `TechnicalIndicatorService.nasdaqSimpleMa` | **double** | 交易雷達美股分頁 | **改為精確** |
| (c) | `backend` `ExcelExportService.indexMaAt` | BigDecimal 精確 | 該頁匯出的四條均線欄 | 不動（本就精確） |
| (d) | `backend` `BacktestService.buildUsMarketRegimes()` → `simpleMa` | **double** | 回測的美股 as-of regime | 不動（範圍外，見 336.3） |

- [ ] 順帶確認 `TechnicalIndicatorService.indicatorSeries(...)`（走勢圖整段序列、用 `maAt`）**仍然沒有 IXIC 分支**：它只有 `isTaiex(stockCode, market) ? taiexSeriesAsc(...) : stockSeriesAsc(...)` 兩條，且其唯一 HTTP 入口 `MarketDataController`（約 `:214`）以 `code`／`market` 取值而 IXIC 不是股票代號。
- [ ] 若復驗發現多出第五個產出點（有人新增了 IXIC 分支），**停下來回報**，不要逕行擴大改動範圍。

---

## 驗證

```bash
# 1. 後端單元測試（Mockito on Java 25 必須用 extraArgLine，不可用 argLine——後者會覆蓋時區設定）
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -f backend/pom.xml test \
  -Dtest='TechnicalIndicatorNasdaqTest,TechnicalIndicatorSeriesAlignmentTest,TradingRadarUsStockEngineTest,DualFormatSingleTableExportTest' \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

```bash
# 2. 後端全量測試
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -f backend/pom.xml test \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

```bash
# 3. BFF 測試（證明 movingAverage 未被改動）
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -f bff/pom.xml test
```

```bash
# 4. 重建並 recreate business-services（本專案沒有 dev server，「改好」＝ image rebuild + container recreate）
#    JVM service 一律 --no-cache：cached build 可能不含你的變更（前端卻有），症狀是 gateway 404
cp /Users/steven/Project/asset-management/.env . 2>/dev/null || true
docker compose -p asset-management build --no-cache business-services
```

```bash
# 5. recreate；business 換 IP 後 BFF 會握著舊 IP 回 500 且 ≥3 分鐘不自癒（Docker DNS TTL 600s），故一併 restart bff
docker compose -p asset-management up -d --no-deps --force-recreate business-services
docker compose -p asset-management restart bff
```

```bash
# 6. 健康檢查
curl -s http://localhost:8080/actuator/health
```

```bash
# 7. 實機比對：兩頁的 IXIC 四條均線必須逐位相同
#    (i) 交易雷達美股組（business 端，以 X-User-Id header 免 OAuth 直打）
docker exec asset-business-services curl -s -H 'X-User-Id: 1' -H 'X-User-Role: ADMIN' -H 'X-User-Status: ACTIVE' \
  http://localhost:8080/api/trading-radar | python3 -c "import sys,json; m=json.load(sys.stdin)['usMarket']; print('radar  MA5/20/60/240 =', m['weeklyMa'], m['monthlyMa'], m['quarterlyMa'], m['annualMa'])"
```

```bash
# 7. (ii) 股市大盤查詢頁那一份（走 Nginx gateway 的公開唯讀路由）
curl -s 'http://127.0.0.1:9090/api/public/market-index?market=IXIC&range=1y' \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print('chart  MA5/20/60/240 =', *[ (d.get(k) or [None])[-1] for k in ('ma5','ma20','ma60','ma240') ])"
```

> 第 7 步兩行印出的四個值必須逐位相同。若 `/api/public/market-index` 的回應欄位名與上面不符，改以
> `curl -s 'http://127.0.0.1:9090/api/public/market-index?market=IXIC&range=1y' | python3 -m json.tool | head -40`
> 先看實際 schema 再取末筆，**不要**改用別的資料來源湊答案。

```bash
# 8. 架構符規（本次改的是 service 層純計算，仍須跑；呼叫 arch-auditor 時必須餵本次變更的完整 diff）
git -C /Users/steven/Project/asset-management/.claude/worktrees/friendly-tu-ef2ba6 diff --stat
```

---

## 完成報告

### 實際改動（只有兩個檔）

| 檔案 | 內容 |
|---|---|
| `backend/src/main/java/com/steven/assets/service/TechnicalIndicatorService.java` | 只有 `nasdaqSimpleMa` 一支（＋其 javadoc），與 336.2 的目標程式碼逐字相同 |
| `backend/src/test/java/com/steven/assets/service/TechnicalIndicatorNasdaqTest.java` | 新增 2 個 `@Test`（336.5 的 (a) 邊界迴歸與 (c) 四視窗性質測試）＋ 3 個 helper |

禁改清單逐項復驗未被動到：`frontend/`、`bff/`、`external-materials-service/`、`ExcelExportService.java`、`BacktestService.java`、`TradingRadarRuleEngine.java`（`RULE_VERSION` 仍為 `TW_RULES_V12`）；`simpleMa`／`taiexSimpleMa`／`maAt` 三支確認仍是 double 累加，`maAt` 的 javadoc 對齊契約原樣未動、未把 `nasdaqSimpleMa` 加進其列舉。另以 `javap` 驗證編譯後 bytecode 確為 `BigDecimal.add` ＋ `BigDecimal.divide(BigDecimal,int,RoundingMode)`、無 `doubleValue`。

### 336.5(a) 的紅燈實際輸出（改實作前，`EXIT=1`）

```
[ERROR] Tests run: 7, Failures: 1, Errors: 0, Skipped: 0 -- in com.steven.assets.service.TechnicalIndicatorNasdaqTest
[ERROR] com.steven.assets.service.TechnicalIndicatorNasdaqTest.MA20在精確商恰為x_xx5時必須向上捨入而非double累加的向下捨入
org.opentest4j.AssertionFailedError: 精確和 153353.5000 / 20 = 7667.675，HALF_UP 須為 7667.68；
收到 7667.67 表示仍走 double 累加（Task 336）。實際值＝7667.67 ==> expected: <0> but was: <1>
	at TechnicalIndicatorNasdaqTest.MA20在精確商恰為x_xx5時必須向上捨入而非double累加的向下捨入(TechnicalIndicatorNasdaqTest.java:224)
```

實際值 `7667.67`，與預期一致；改實作後同一條轉綠。測試用的是任務檔那 20 筆真實收盤、新→舊順序，未構造等值序列，斷言全用 `compareTo`。

### 測試結果

| 指令 | 結果 |
|---|---|
| `TechnicalIndicatorNasdaqTest`（改實作**前**） | `Tests run: 7, Failures: 1` — 紅燈（見上） |
| `TechnicalIndicatorNasdaqTest`（改實作**後**） | `Tests run: 7, Failures: 0, Errors: 0` |
| `Nasdaq,SeriesAlignment,UsStockEngine,DualFormatSingleTable`（主 agent 獨立重跑） | `Tests run: 70, Failures: 0, Errors: 0` — BUILD SUCCESS，`EXIT=0` |
| backend 全量 | `Tests run: 1020, Failures: 0, Errors: 0` — BUILD SUCCESS |
| bff 全量 | `Tests run: 92, Failures: 0, Errors: 0`（含 `MarketIndexChartServiceTest` 8 條、`GdpTwseBffControllerMaTest` 3 條，證明 BFF `movingAverage` 未被動到） |

### 336.6 前提復驗

`grep -ran "IXIC" backend/src/main/java/ bff/src/main/java/` 得 76 筆，逐一比對後 **IXIC 均線產出點仍是表列那四個、沒有第五個**：(a) `MarketIndexChartService.movingAverage`（bff `:212`，精確、未動）、(b) `TechnicalIndicatorService.nasdaqSimpleMa`（`:688`，**已改為精確**）、(c) `ExcelExportService.indexMaAt`（`:616`，精確、未動）、(d) `BacktestService.buildUsMarketRegimes()` → `simpleMa`（`:2911`，double、範圍外未動）。其餘 IXIC 命中處經查證都不是均線產出點：`TradingRadarMarketContextService`（只做 `percentChange`）、`TradingRadarMarketFeatureResolver`（`*_RET5` 與量能 median）、`MacroHistoryService`／`IndexDailyRefreshScheduler`（回補與排程）、`MarketAnalysisService`／`ExcelExportService:500`（指數中文名）、`PerformanceComparisonBffController`／`GdpTwseBffController`（白名單與路由）。另確認 `indicatorSeries(...)` 仍只有 `isTaiex(...) ? taiexSeriesAsc : stockSeriesAsc` 兩條分支、無 IXIC 分支；`computeAllForNasdaq()` 全站唯一呼叫端仍是 `TradingRadarService.buildUsMarket()`（`:674`）。

### 與原計畫的偏差

1. **本檔驗證步驟 1 的 `-Dtest='A+B+C'` 分隔符號跑不動**（Surefire 3.5.2 回 `No tests matching pattern` 而 BUILD FAILURE）。已把該行改為逗號分隔並補上 `DualFormatSingleTableExportTest`。
2. **336.5(c) 的四視窗性質測試在改實作前就是綠的**，紅燈那次 7 條中只有 (a) 失敗。實作者用決定性 LCG 產生 240 筆 4 位小數收盤，事後重放確認該組資料四個視窗的 double 與精確路徑恰好都相同，故它本來就不會紅。**據實記載：(c) 是向前鎖定用的性質測試、沒有鑑別力，真正鎖住行為的是 (a)。** 刻意沒有去搜尋「剛好會紅」的 seed——那等於用構造資料製造邊界，與 336.5 反覆強調的精神相衝突。
3. **本次新造出一個殘餘分歧（非舊帳）**：live 雷達的美股 regime 走精確路徑、回測 `BacktestService.buildUsMarketRegimes()` 仍走 double，兩者由原本的 **bit-identical** 變成存在 0.01 上限的差異（歷史重放 0 日翻動 regime）。此為 336.3 明文刻意保留、已登記於 `spec/steering/structure.md` §3.2 具名例外之二。
4. **`computeAllForNasdaq()` 的既有 javadoc（`:611`）在實作 subagent 交件時未修，由 `arch-auditor` 的 minor finding 揭出，主 agent 補修。** 該處原寫「MA 核心比照 `computeAllForTaiex()` 的既有寫法」，改動後在算術層已不成立（`taiexSimpleMa` 仍是 double）。已把「比照」限縮為**呼叫形狀**並明寫算術自 Task 336 起刻意不同。**只改註解文字、未動任何程式行為**，補修後重跑 `Nasdaq,SeriesAlignment,UsStockEngine,DualFormatSingleTable` 四支共 70 tests 全綠（BUILD SUCCESS）。此項已回寫為 336.2 的一個 checklist 條目。

### 部署與驗證步驟 7（實機比對）

依「已 merge 就從 main 的 worktree 建」的規則，於 `/Users/steven/Project/asset-management-main`（已確認在 `main`、工作區乾淨、無 `MERGE_HEAD`、已追平 `origin/main` 至 `d4c4d7cb`）執行 `docker compose -p asset-management build --no-cache business-services`（`BUILD_EXIT=0`，image `c35c27e5c201`），再 `up -d --no-deps --force-recreate business-services` ＋ `restart bff`。

**兩頁的 IXIC 四條均線逐位相同：**

| 來源 | MA5 | MA20 | MA60 | MA240 |
|---|---|---|---|---|
| 交易雷達美股組（`GET /api/trading-radar` → `usMarket`） | `26634.3` | `25848.52` | `26041.57` | `23898.84` |
| 股市大盤查詢頁（`GET /api/public/market-index?market=IXIC&range=1y` 末筆） | `26634.3` | `25848.52` | `26041.57` | `23898.84` |

同時確認美股組其他欄位正常：`asOfDate=2026-08-14`、`price=26729.1641`、`quoteStatus=VERIFIED_CLOSE`（**不是**「收盤價待補」）、`regime=RISK_ON`、`score=100`、`ruleVersion=TW_RULES_V12`（未升版）。兩份的 `closes` 末筆同為 `26729.1641`，確認取的是同一批列。

`docker logs asset-bff --since 10m | grep -cE "Connection refused|500 Server Error"` → **0**；七個容器皆 `Up`／`(healthy)`；`curl -sI http://localhost/` → `HTTP/1.1 200 OK`。

> **兩點部署期間的實況記錄。** (1) 首兩次 build 卡在 base image `resolve` 逾 25 分鐘無進展，查證後為**另一個 session 同時對同一個 compose project 跑 `--no-cache` build**（全機共用一組 buildkit 與一套 image tag）；停掉自己那次、等對方結束後重建即正常，與本次變更無關。(2) 驗證期間 `postgres`／`frontend` 被其他 session 重啟過，故依規定在下結論**前**重新 `docker inspect` 一次，確認運行中 image 仍是 `c35c27e5c201`、未被洗掉，上表數據對應的就是現行映像。

### 稽核紀錄

- **spec 對抗式審查**：三輪，findings 逐輪收斂（2 critical／4 major → 0／2 → 0／1），critical 與 major 全數修完。第一輪揪出兩個事實錯誤（`ExcelExportService.indexMaAt` 其實會產出 IXIC 均線；`BacktestService.buildUsMarketRegimes()` 是第四個產出點），皆已改寫並重驗。
- **`arch-auditor`（diff-scoped）**：**0 critical、0 major、1 minor**（即上述偏差 4，已修）。該稽核另以運行中 DB 獨立重放，本檔全部量化宣稱（2559 筆／2248 筆 4 位小數／MA20 命中 2 日／符號翻動 0 日／三個邊界餘裕值）**逐項復現一致**。
