# [t360] 修正交易雷達 J 值因子的極性——評分鏈誤用畫面慣例 `J9 = 3D − 2K`

**對應 Requirements:** Requirement 96（交易雷達的 J 值因子須照其自述的「低檔有利於承接、高檔不鼓勵追價」計分，不得因誤用畫面慣例而在股價上漲時加分、下跌時扣分）
**前置任務:** 無（修正 Task 291 與 Task 356.6b 引入的既有缺陷；不依賴任何未完成工作）
**Liquibase changeset:** 無（不改資料庫）

## 背景

### 現在的錯誤行為

交易雷達的 J 位置分量**動能項極性是反的**：股票正在上漲（`K > D`）時它**加**分，正在下跌（`K < D`）時它**扣**分。這與該分量自述的用途完全相反。

程式碼位置共兩處（`backend/src/main/java/com/steven/assets/service/TradingRadarRuleEngine.java`）：

```java
// 日線，kdJContribution()，:2395-2396
BigDecimal j = input.extendedIndicators() == null ? null : input.extendedIndicators().j9();
Double jPosition = j == null ? null : clampUnit((50.0 - j.doubleValue()) / 50.0);

// 週線，weeklyMomentumContribution()（函式起點 :1836），:1848-1849
Double jPosition = weekly.j9() == null
        ? null : clampUnit((50.0 - weekly.j9().doubleValue()) / 50.0);
```

`clampUnit((50 − value)/50)` 的語意是「**低檔為正、高檔為負**」。問題出在餵進去的 `j9`：

本專案的 `j9` **刻意**採用畫面慣例 `J9 = 3D − 2K`（`TechnicalIndicatorService.java:110` 註解逐字：「j9 = 3D − 2K、k3d2 = 3K − 2D（同一對 K/D 的兩種鏡像慣例，畫面兩者都要顯示）」），而 `spec/design.md:2031` 早已記載該慣例「**與坊間常見的 `J = 3K − 2D` 相反**」。把方向相反的值餵進「低檔為正」的位置式，得到的就是方向相反的貢獻。

### 正確行為與代數證明

令 `avg = (K + D)/2`、`m = K − D`（`m` 即 KD 動能項）：

```
3D − 2K = avg − 2.5m        ← 本專案 j9（畫面慣例）
3K − 2D = avg + 2.5m        ← 坊間標準 J

現行 jPosition = (50 − j9)/50 = (50 − avg)/50 + m/20    ← 動能越強分數越高（追漲）✗
正確 jPosition = (50 − J)/50  = (50 − avg)/50 − m/20    ← 動能越強分數越低（不追價）✓
```

兩者恆差 `m/10`。**位置項 `(50 − avg)/50` 在兩式中相同且方向本來就正確，錯的只有動能項。**

> ⚠️ **`(j9 − 50)/50` 不是修法。** 那會連位置項一起反轉（`−(50−avg)/50 − m/20`，高檔反而加分），錯得更徹底。標準 J 之所以正確，是因為 `3K − 2D = avg + 2.5m` 同時保有正確的位置項與正確的動能項。

### 與規格的直接衝突

`spec/tasks/t291_trading_radar_profit_horizon.md:59` 逐字宣告：

> KD／J：`K>D => +1`、`K<D => -1`；位置各為 `clamp((50-value)/50)`，**使低檔有利於承接、高檔不鼓勵追價**。

Requirement 43／59 的核心紀律則是「純獲利、不追高殺低」。現行實作與兩者皆相反。

### 可重現的量化證據

2026-08-23 對執行中的 stack 呼叫 `GET /api/trading-radar/current`，取 35 檔 `kValue`／`dValue`／`extendedIndicators.j9`／`extendedIndicators.k3d2` 皆非 null 的標的，逐檔計算兩種慣例下的 `jPosition`：

| 指標 | 值 |
|---|---|
| J 位置平均絕對偏差 | **1.05**（該分量值域滿格僅 `[-1, +1]`，即偏差逾半個滿格） |
| `K > D`（12 檔，正在漲） | 現行版平均比標準版 **高 +0.70** |
| `K < D`（23 檔，正在跌） | 現行版平均比標準版 **低 −1.23** |

逐檔極端例（皆為實測值。表列 K／D 為 1 位小數，代回 `3D − 2K` 與表列 `j9` 的實測落差皆 ≤ `0.1`，理論上界為 `3×0.05 + 2×0.05 = 0.25`）：

| 標的 | K | D | KD 均值 | 現行 `j9` → jPos | 標準 `J` → jPos | 判讀 |
|---|---|---|---|---|---|---|
| `00881` | 37.4 | 58.2 | 47.8（中性偏低） | 99.9 → **−1.00** | −4.2 → **+1.00** | 吃滿超買懲罰，實則動能轉弱 |
| `NVDA` | 23.7 | 45.5 | 34.6（明確超賣） | 89.1 → **−0.78** | −19.9 → **+1.00** | 深度超賣卻被當超買扣分 |
| `00882` | 62.0 | 44.5 | 53.3（中性偏高） | 9.4 → **+0.81** | 97.0 → **−0.94** | 強勢追漲卻拿到承接滿分 |

**換算成分數（一周軌）：**

| 來源 | 偏差 |
|---|---|
| 日線 KD／J 因子單獨 | **±3.00 分**（`SW_KD_J = 0.12`〔`:89`〕、jPosition 佔群組 1/4、`score = 50 + 50σ`） |
| 週線動能因子追加 | **±0.33 分**（`SW_WEEKLY_MOMENTUM = 0.03`〔`:106`〕、週線 jPosition 佔 1/9）；週MACD／週RSI 皆缺值時佔 1/3 → **±1.00 分**（`50 × 0.03 × 2.00 ÷ 3`） |
| **合計上界** | **≥ ±3.33 分**（缺值情境可達 `±4.00`） |

> `Accumulator.score()`（`:2313-2317`）採缺值權重重分配（`sigma = sumWC / sumW`），`sumW < 1.0` 時單因子影響力等比放大，實際偏差可再高於上表——上表隱含「全部因子皆可得」，而多數標的的基本面因子實際缺值。

### 成因：同一個疏漏發生過兩次

`j9 = 3D − 2K` 由 Task 261／262 為對齊券商畫面反推確立（`spec/design.md:2031` 記載實測值 K9=40.36、D9=32.74 → J9=17.50、K3D2=55.60），當時**只供顯示**；〈還原權息技術序列〉一節第 4 點（Task 281）更明文「只供匯出檔揭露，**不進 `StockInput`、不影響 `action`／`score`**」。

- **日線**那一處由 **Task 291** 拉進評分鏈（`t291:44`／`:59`）。
- **週線**那一處由 **Task 356.6b** 引入（`t356:142` 逐字：「再與 `clamp((50 - avg(k,d)) / 50)` 及 `clamp((50 - j9) / 50)` 取平均（使低檔有利於承接、高檔不鼓勵追價）」）。

**兩份任務檔對 `k3d2`／`鏡像`／`3K`／`3D` 都是零命中**（實測 grep）——兩次都在複製「J9 位置」這個看似自明的寫法時沒有回頭檢視慣例方向。

對照組就在同一支函式裡：W%R 分量上方（`:2397`）留有極性說明——

```java
// 本系統 W%R 值域為 0（高檔）至 100（低檔），故低檔為正貢獻，與被併入前的 wrContribution 同式。
```

證明作者對 W%R 逐一確認過方向，唯獨 J 值沒有。

---

## 要做什麼

### 360.1 新增 `standardJPosition()` 並在兩處改用

- [x] **360.1a** 在 `TradingRadarRuleEngine` 新增 `package-private static` 純函數（同 package 的 `TradingRadarRuleEngineTest` 可直測；`clampUnit` 為同類別 `private static double`，`:2320`）：

  ```java
  /**
   * 標準 J（{@code 3K − 2D}）的位置分量：低檔為正、高檔為負。
   *
   * <p><b>刻意不讀 {@code j9}。</b>本專案的 {@code j9 = 3D − 2K} 是為了與使用者券商畫面相符
   * 而採的顯示慣例，方向與坊間標準 {@code J = 3K − 2D} 相反。令 {@code avg = (K+D)/2}、
   * {@code m = K − D}，則 {@code 3D − 2K = avg − 2.5m}、{@code 3K − 2D = avg + 2.5m}，
   * 代入本式的 {@code (50 − value)/50}（語意為「低檔為正」）分別得到
   * {@code (50−avg)/50 + m/20}（動能越強分數越高＝追漲）與 {@code (50−avg)/50 − m/20}
   * （動能越強分數越低＝不追價）。後者才符合 Requirement 43／59「不追高殺低」與
   * {@code t291:59} 自述的「低檔有利於承接、高檔不鼓勵追價」。Task 291（日線）與
   * Task 356.6b（週線）都直接接入 {@code j9} 而未檢視慣例方向，由 Requirement 96／Task 360 修正。</p>
   *
   * <p>改寫成 {@code (j9 − 50)/50} <b>不是</b>修法：那會連位置項一起反轉，變成高檔加分。</p>
   *
   * <p><b>為什麼由 {@code k}／{@code d} 現算而不讀 {@code k3d2}：因為 {@link WeeklyInput}
   * 沒有 {@code k3d2} 欄位</b>，為此新增 record 欄位會踩到本專案已知的「相容建構式吃掉新欄位
   * → production 少傳引數、新欄位靜默 null、既有測試全綠」陷阱。<b>理由不是精度，精度其實相反</b>：
   * {@code k}／{@code d}／{@code j9}／{@code k3d2} 是在 {@code TechnicalIndicatorService:435}
   * 的同一行一起 {@code scale2(...)} 捨入到 2 位小數的，故由 2 位 {@code k}／{@code d} 現算
   * {@code 3k − 2d} 的誤差上界為 {@code 0.025}，直接讀 {@code k3d2} 只有 {@code 0.005}——
   * 讀 {@code k3d2} 反而較精確，只是兩者代入 {@code /50} 後分別為 {@code 5e-4} 與 {@code 1e-4}，
   * 皆可忽略。</p>
   */
  static Double standardJPosition(BigDecimal k, BigDecimal d) {
      if (k == null || d == null) return null;
      double j = 3.0 * k.doubleValue() - 2.0 * d.doubleValue();
      return clampUnit((50.0 - j) / 50.0);
  }
  ```

  > 全樹 grep `standardJPosition` 目前零命中，無同名衝突。

- [x] **360.1b** 日線 `kdJContribution()`：刪除 `:2395-2396` 兩行，改為 `Double jPosition = standardJPosition(k, d);`。`k`／`d` 是該函式 `:2382-2383` 已存在的區域變數，不需新增取值。
- [x] **360.1c** 週線 `weeklyMomentumContribution()`（函式起點 `:1836`）：把 `:1848-1849` 改為 `Double jPosition = standardJPosition(weekly.k(), weekly.d());`。
- [x] **360.1d** 確認 `j9()` 在整個規則引擎內的消費點只剩零處（改前為 `:1848`／`:1849`／`:2395` 三行＝兩處）。大盤評分路徑本就不吃 `j9`，不需改動。

### 360.2 顯示路徑一律不動

- [x] **360.2a** 以下全部維持**逐位不變**：`TechnicalIndicatorService` 的 `j9 = 3D − 2K` 與 `k3d2 = 3K − 2D` 計算；`TechnicalIndicatorService.ExtendedIndicators`／`TradingRadarDto.ExtendedIndicators`／`TradingRadarRuleEngine.WeeklyInput.j9`／`TradingRadarDto.WeeklyIndicators.j9` 的欄位與值；走勢圖 KD 子圖的 J9 線、legend 與 Y 軸自適應規則；`frontend/src/views/TradingRadarView.vue:108`／`:282`／`:339` 的 J 值顯示；匯出檔的**欄位順序**與 J9／K3D2 兩欄的**值**。
- [x] **360.2b** ⚠️ **匯出檔的個股 `score`／`action`／`actionLabel` 與 `ruleVersion` 會變動，這是預期**（`TradingRadarExportService.java:258` 輸出個股 `action`／`actionLabel`／`score`）。**大盤 `score`（`:128`／`:160`）不會變**——`evaluateMarket()`（`TradingRadarRuleEngine.java:1147`）的週線分量只比較 `weekly.k()` 與 `weekly.d()` 的大小（`:1282-1289`），全程不吃 `j9`，故該兩處只有 `ruleVersion` 欄隨升版變動。**不得**在任何文件或 commit 訊息宣稱「匯出檔逐位不變」。

### 360.3 `RULE_VERSION` 升版 `TW_RULES_V15` → `TW_RULES_V16`

升版理由：`score`／`action` 對實測 35 檔全部實質變動，符合既有判準「使用者可觀察行為有實質變化」（〈三軌持有期與真正的週K／日K 棒〉一節的升版判準段）。三個不升版先例**一個都不適用**——Task 249 是「輸出完全相同」（本次不符）、Task 281 是「純揭露、不進 `StockInput`」（本次確實進評分）、Task 336 是「`score`／`regime` 逐位不變的顯示值捨入修正」（本次改的正是 `score` 本身）。

**production／前端／契約 七處**（實測 `grep -ran "TW_RULES_V15"` 排除 spec／node_modules／target 後，非測試命中恰為此七行，一處不多一處不少）：

- [x] **360.3a** `backend/.../service/TradingRadarRuleEngine.java:49` — 常數本體，並於其 Javadoc 新增 V16 沿革段落（一句話說明 J 極性修正）
- [x] **360.3b** `backend/.../dto/TradingRadarDto.java:9` — Javadoc 內版號
- [x] **360.3c** `backend/.../service/TradingRadarService.java:752` — 註解內版號
- [x] **360.3d** `frontend/src/views/TradingRadarView.vue:28` — 顯示 fallback
- [x] **360.3e** `frontend/src/views/TradingRadarView.vue:1125` — `ref` 初始值
- [x] **360.3f** `frontend/src/views/TradingRadarView.vue:804` — 免責文字改述為「`TW_RULES_V15` 與 `TW_RULES_V16` 為不同規則版本，兩者的分數不可直接比較」
- [x] **360.3g** `docs/openapi/docker-external-api.yaml:579` — synthetic example 的 `ruleVersion`
  > ⚠️ **這一處沒有任何自動化紅燈。** `t356:240` 已記載它是全樹唯一不屬 Java／Vue／測試的版號 hardcode，而既有 OpenAPI 測試**只驗 example 存不存在、不驗內容**（實測 `TradingRadarOpenApiSchemaContractTest` 內 `example` 零命中）。漏改不會有任何測試失敗。

**測試檔版號斷言 九行**：

- [x] **360.3h** `TradingRadarRuleEngineTest.java:533`
- [x] **360.3i** `BacktestServiceTest.java:806`（`@DisplayName`）、`:808`、`:821`、`:837`
- [x] **360.3j** `TreasuryYieldServiceTest.java:178`（註解）、`:189`
- [x] **360.3k** `TradingRadarControllerCurrentTest.java:27`
- [x] **360.3l** `TradingRadarV13ActionPolicyTest.java:112`（註解）

- [x] **360.3m** ⚠️ **若用 `sed` 批次取代版號，必須排除 changelog 目錄 `backend/src/main/resources/db/changelog/`**（注意實際路徑在 `backend/src/main/resources/` 底下，不是 repo 根目錄的 `db/`——根目錄只有 `db/schema.sql`）。該目錄的 changeset 內容被 Liquibase 納入 checksum，**連 `--comment` 註解行都算**，改動會導致 `ValidationFailed` → business crash loop。實測全 changelog 樹唯一含 `TW_RULES` 的是 `backend/src/main/resources/db/changelog/changes/v1.83.0-radar-notification-rule-version.sql:4` 的 `--comment` 行（內含 `TW_RULES_V9`），**不會**被 V15→V16 的取代波及，但仍須排除以免誤傷。

### 360.4 通知基準重建為刻意行為

- [x] **360.4a** `TradingRadarNotificationService`（`:127`／`:132`／`:154`）在 `setting.getRuleVersion()` 與現行 `RULE_VERSION` 不符時視同未初始化、重建基準且首輪不寄信（Requirement 44）。升版後首次排程即走此路徑，屬**預期且正確**。**不得**寫任何 V15→V16 的基準沿用特例。

### 360.5 不得順手調整任何權重或門檻

- [x] **360.5a** `SW_KD_J = 0.12`（`:89`）／`SWG_KD_J = 0.08`（`:115`）／`MW_KD_J = 0.05`（`:140`）／`SW_WEEKLY_MOMENTUM = 0.03`（`:106`）、`KD_OVERHEAT_K`、`KD_BAND_MIN_PERCENT = 2.0`（`:247`）、`averageAvailable` 的分量組成——**全部維持現值**。
  > 本任務修的是**極性錯誤**（可由代數證明對錯），不是**權重校準**（需 Requirement 56 回測量測支持；`t333` 333.10 明文禁止未經量測就調門檻）。兩者混在同一次變更裡，升版後的分數變化將無法歸因。

### 360.6 可得性與缺值行為不變

- [x] **360.6a** 確認**不需要**任何缺值補償：`j9` 與 `k`／`d` 同出 `kdSeriesAsc` 的同一個 `KdPoint`（`TechnicalIndicatorService.java:435`），暖機不足時三者同時為 `null`；且 `ExtendedIndicators` 在 production 恆非 `null`（`FullIndicators.EMPTY` 與 `extendedOf()` 回傳的都是 `ExtendedIndicators.EMPTY` 哨兵，唯二呼叫端 `TradingRadarService.java:990`／`BacktestService.java:872`、`:3028` 皆走 `assembler.extendedIndicators(ind.extended())`）。故改用 `k`／`d` 後可得性**完全等價**——**不是**涵蓋率提升，不得如此描述。
- [x] **360.6b** 窄幅 KD（`kdBandWidthPercent < 2%`）整組缺值的既有規則不受影響：它在更外層（`:1643` 的 `narrowBand ? null : kdJContribution(...)`）優先生效，且**本就只守日線那一組**，週線動能不在其保護範圍內（Task 356 既有設計，非本次變更、不在本次修正範圍）。

### 360.7 測試（與實作同一次交付）

除 360.7d 另有指定外，追加在既有檔 `backend/src/test/java/com/steven/assets/service/TradingRadarRuleEngineTest.java`（`standardJPosition` 為 `package-private static`，同 package 可直測）：

- [x] **360.7a 純函數基本性質**：`standardJPosition(null, d)`／`standardJPosition(k, null)` 皆回 `null`；`K == D` 時等於 `clampUnit((50 − K)/50)`（動能項為零）；clamp 飽和（`K=100, D=0` → `J=300` → `−1.0`；`K=0, D=100` → `J=−200` → `+1.0`）。
- [x] **360.7b 極性回歸測試（本任務核心防迴歸點）**：`standardJPosition(BigDecimal.valueOf(23.7), BigDecimal.valueOf(45.5))`（`NVDA` 實測，深度超賣、K<D）必須為**正**；`standardJPosition(BigDecimal.valueOf(62.0), BigDecimal.valueOf(44.5))`（`00882` 實測，動能強勢、K>D）必須為**負**。
  > **必須實際驗證這個測試會抓到迴歸**：暫時把式子改回 `3D − 2K`（或改成 `(j9 − 50)/50`）跑一次確認紅燈，再改回來。只寫測試不驗證它會失敗，等於沒有防護。
- [x] **360.7c 代數等價斷言**：對 (40,45)、(55,50)、(48,52) 三組未飽和的 `K`／`D`，斷言 `standardJPosition(k, d)` 等於 `(50 − (k+d)/2)/50 − (k − d)/20`（容差 `1e-9`；預期值分別為 `0.4`、`−0.3`、`0.2`）。
- [x] **360.7d 兩條路徑皆已改（放在 `TradingRadarThreeHorizonEngineTest.java`，不是 `TradingRadarRuleEngineTest`）**：`kdJContribution()`（`:2381`）與 `weeklyMomentumContribution()`（`:1836`）**都是 `private` 實例方法，同 package 的測試也叫不到**，且分量值不外露（公開路徑只吐 `score`／`action`）。**不得為此放寬其可見性**——本次唯一允許新增的可測試進入點是 `standardJPosition`。故本項以**分數方向**驗證，沿用該檔既有的 builder（`weekly(int)`〔`:619`〕→ `WeeklyBuilder`〔`:624`，`k`／`d` setter 在 `:649-650`〕與 `StockBuilder`〔`:677`，`k`／`d` setter 在 `:704-705`〕；`WeeklyInput` 有 16 個 component，逐測試手寫極易錯位，該檔註解已載明此為建 builder 的理由，而 `TradingRadarRuleEngineTest` 內 `WeeklyInput` 零命中、沒有可用 builder）。

  > ⚠️ **不得用「K>D vs K<D」的反向比較，那個斷言在正確實作下也必然失敗。** `jPosition` 與 `direction` 是**同一個 `averageAvailable` 的兄弟分量**（日線 `:2402`、週線 `:1850`），而 `direction = signum(K−D)`（`:2386`／`:1842`）。取對稱的 60/40 與 40/60：兩者 `avg` 皆為 50 → `position = 0`；`K=60,D=40` 得 `direction=+1`、`standardJPosition = clampUnit((50−100)/50) = −1.0`，分量和 `0`；`K=40,D=60` 得 `direction=−1`、`standardJPosition = clampUnit((50−0)/50) = +1.0`，分量和亦為 `0`——**逐位相等**。一般性地，令 `p = clampUnit((50−avg)/50)`、`m = K−D > 0`，則 `Δ(K<D − K>D) = −2 + [clamp(p+m/20) − clamp(p−m/20)] ≤ −2 + 2 = 0`，**恆 ≤ 0**，永遠不可能嚴格大於。且 `k`／`d` 對 `shortScore` 只有這一條通道（`evaluateHorizon` 的 `score = acc.score()`〔`:2074`〕只吃 `factors.*`；`kdHeat`／`timing` 只寫 `reasons`／`risks` 與 `action`，`:1621` 註解逐字寫明「`kdHeat` 目前不影響任何共用貢獻值或文案」）。

  改用下列**兩條**斷言，兩條都必須在漏改或改錯方向時變紅：

  - **(d-1) 同向、不同動能幅度**（固定 `direction` 與 `position`，只變動能量級）：
    - 日線：`StockBuilder` 一組 `k("52").d("48")`、一組 `k("70").d("30")`，其餘輸入完全相同。兩組 `avg` 皆為 50 → `position = 0`、`direction = +1`。正確實作下分量和分別為 `1 + 0 + clampUnit((50−60)/50) = 0.80` 與 `1 + 0 + clampUnit((50−150)/50) = 0.00`，故斷言 **`k=70,d=30` 的 `shortScore` 低於 `k=52,d=48`**。
      > 若誤寫回 `3D − 2K`：兩組分別得 `1 + 0 + clampUnit((50−40)/50) = 1.20` 與 `1 + 0 + clampUnit((50−(−50))/50) = 2.00`，**方向相反**，測試必紅。
    - 週線：`weekly(60).k("52").d("48")` vs `weekly(60).k("70").d("30")`，日線 `k`／`d` 固定不變，同法斷言 `shortScore` 較低。這一條是「週線有沒有被漏改」的唯一防護。
  - **(d-2) 「不再讀 `j9`」的直接證明**：固定 `k`／`d` 與其餘所有輸入，只改 `weekly(60).j9(...)`（以及日線 `StockBuilder` 的 `extended(...)` 內 `j9`）為兩個差異極大的值，斷言 `shortScore`／`swingScore`／`score` **三軌皆逐位不變**。修正前此斷言必紅，修正後必綠——它直接證明 `j9` 已退出評分鏈。

  > 兩者組成不同，供理解權重用：日線 J 是 `averageAvailable(direction, position, jPosition, wrPosition)`（`:2402`）**四**分量之一；週線 J 是 `averageAvailable(direction, position, jPosition)`（`:1850`）**三**分量之一，該 `kd` 再與週MACD／週RSI 取平均（`:1867` 的 `averageAvailable(kd, macd, rsi)`），故週線 J 實佔該因子 1/9。

- [x] **360.7e 版號**：`assertEquals("TW_RULES_V16", TradingRadarRuleEngine.RULE_VERSION)`。
- [x] **360.7f 顯示路徑未被污染**：既有的 `j9 = 3D − 2K` 回歸斷言須逐字通過——`TechnicalIndicatorSeriesAlignmentTest.java:429-430` 與 `:544-545` 的 `assertThat(e.j9()...).isCloseTo(3 * d - 2 * k, within(0.03))`／`e.k3d2() ≈ 3 * k - 2 * d`，以及 `TechnicalIndicatorMa10AppendTest.java:77` 的 `assertThat(ind.extended().j9()).isEqualByComparingTo("72.98")`。
  > ⚠️ 〈走勢圖技術指標序列〉一節的 Yahoo 逐位比對表 的 2330 九值 Yahoo 逐位比對（K9 42.65／D9 32.92／J9 13.45 等）是**文件層的一次性人工核對紀錄，沒有對應的自動化測試類**（`grep -ran "42.65\|13.45\|32.92" backend/src/test` 零命中；檔名以 `TechnicalIndicator` 開頭的專屬測試檔只有 `TechnicalIndicatorSeriesAlignmentTest`／`TechnicalIndicatorMa10AppendTest`／`TechnicalIndicatorIndexKdCrossCheckTest`／`TechnicalIndicatorNasdaqTest`／`TechnicalIndicatorTaiexLiveBlendTest` 五支，沒有任何一支涵蓋那九個值）。不得把它當成既有測試來援引。
- [x] **360.7g 既有測試全綠**：`TradingRadarThreeHorizonEngineTest`、`RadarInputAssemblerWeeklyTest`、`BacktestServiceTest`、`TradingRadarDualFormatTest`、`TradingRadarOpenApiSchemaContractTest` 等除版號斷言外不得有其他失敗。若有分數相關的硬編碼期望值因本次修正而變動，**須逐一確認變動方向符合預期**（K<D 的標的分數上升、K>D 的下降）後才更新期望值，**不得盲目改成實際輸出**。
  > ⚠️ **既有測試常以相容建構式建 `StockInput`、`extendedIndicators` 為 `null`**（例 `TradingRadarRuleEngineTest.java:253` 的 `trialBuyStock()` 只傳 20 個引數，命中 Task 291 前的建構形狀，`weeklyMa`／`extendedIndicators`／`volumeRatio` 三者皆填 `null`）。這類案例現行 `jPosition` 與 `wrPosition` 皆為 `null`、`kdJContribution` 只有 2 個可用分量；改用 `k`／`d` 後變 3 個，其分數變動源自「`jPosition` 由缺值變可得（分量數 2→3）」而**非**極性翻轉，**不適用上述方向準則**，須逐案以手算 `averageAvailable` 核對。（360.6a 的「可得性完全等價」限定 production 組裝路徑，對這類手寫測試語料不成立。）

> **執行測試的注意事項（本專案既有陷阱）**：Mockito 需 `-DextraArgLine=-Dnet.bytebuddy.experimental=true`；**不得**用 `-DargLine`（會覆蓋時區設定導致大量無關 error，錯誤訊息會偽裝成 byte-buddy 問題）。

---

## 驗證

- [x] **建置**：`cd backend && /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -DextraArgLine=-Dnet.bytebuddy.experimental=true test` 全綠。
- [x] **實機**：跑 `/run-stack`，以 `--no-cache` 重建並 recreate `business-services` 與 `frontend`（JVM service 的 cached compose build 可能不含變更，本專案已有 stale jar 事故前例；重建 business 後須一併 restart `bff`，否則 BFF 握著舊 IP 會回 500 且 ≥3 分鐘不自癒）。
- [x] **畫面 (a)**：版號 tag 顯示 `TW_RULES_V16`。
- [x] **畫面 (b)**：展開列的 `J9` 顯示值與變更前一致（未被評分修正污染）。
- [x] **畫面 (c)**：`NVDA`（日線 K=23.7、D=45.5，深度超賣）**一周軌**分數應較變更前提高；`00882`（日線 K=62.0、D=44.5，動能強勢）應降低。預期限縮在一周軌，因為 `SW_KD_J = 0.12` 為三軌中最大、最不易被抵銷。
  > **若某軌未如預期變動，先排除兩個合理原因再判定為異常**：(i) 該標的的週K `k`／`d` 方向與日線相反時，週線動能因子的 J 分量會往反方向動（量級較小但存在）；(ii) `score` 經 `Math.round` 取整（`:2316`），不足 0.5 分的變化會被吃掉。
- [x] **公開端點**：`curl -s http://127.0.0.1:9090/api/public/trading-radar/today | head -c 400` 仍正常回應且 `ruleVersion` 為 `TW_RULES_V16`（Requirement 86 第九條公開路由未受影響）。

---

## 不在本次範圍

- 不修改 `j9`／`k3d2` 的計算與任何顯示路徑。
- 不調整任何權重、門檻或因子組成。
- **不處理**「商品價格／公債殖利率／三大法人在 production 恆為零權重」——`TradingRadarService:966` 走 `evaluateStock()`（baseline，`candidate == null`），market feature 整段被 `TradingRadarRuleEngine:2011` 的 `if (candidate != null && context != null)` 包住、treasury 權重為 `candidateWeight(candidate, ..., 0.0)` 而 `candidateWeight(null, key, baseline)` 直接回 `baseline`。此為 V13 candidate 路徑尚未 promote 的既有設計，其**揭露落差**（畫面 evidence 面板列出這些數字，但它們對 `score`／`action` 影響恆為零）另案處理。
- **不處理** `MW_PE` 權重表標籤寫「PE 自身分位」（`TradingRadarRuleEngine:214`）但實為 PE／PB／殖利率三者算術平均（`FundamentalAnalysisService:498-501`）的命名落差，另案。
- **不處理** `db/schema.sql` 與實際 schema 的漂移，另案。
- 不新增 `@Scheduled`；不變更任何 API 路徑或 9090／Tailscale 路由；不改資料庫。

---

## 完成報告

**完成日期：** 2026-08-23

### 實際改動

| 檔案 | 改動 |
|---|---|
| `backend/.../service/TradingRadarRuleEngine.java` | `:21-33` V16 沿革段（舊 V15 段降為 `<p>V15（歷史）…`）；`:61` `RULE_VERSION = "TW_RULES_V16"`；`:1860` 週線改呼叫 `standardJPosition(weekly.k(), weekly.d())`；`:2335-2363` 新增 `standardJPosition` 純函數（緊接 `clampUnit` 之後）；`:2435` 日線改呼叫 `standardJPosition(k, d)` |
| `backend/.../dto/TradingRadarDto.java:9` | Javadoc 版號 |
| `backend/.../service/TradingRadarService.java:752` | 註解版號 |
| `docs/openapi/docker-external-api.yaml:579` | synthetic example 的 `ruleVersion` |
| `frontend/src/views/TradingRadarView.vue` | `:28` fallback、`:804` 免責文字、`:1125` `ref` 初始值 |
| 六支測試檔 | 新增 6 個測試（360.7a–d）＋ 九行版號斷言 ＋ 8 處既有期望值更新 |

`grep "j9()"` 在規則引擎已為**零命中**（360.1d 達成）。權重常數零改動（`SW_KD_J`／`SWG_KD_J`／`MW_KD_J`／`SW_WEEKLY_MOMENTUM` 經 diff 確認未出現）。`db/changelog/` 未觸及。

### 測試結果

雷達相關 11 支測試類 **226 tests，Failures 0 / Errors 0**，`mvn` exit code 0（未經 pipe，exit code 未失真）。

**360.7b 的刻意改壞驗證**：把式子改回 `3D − 2K` 後 6 個新測試中 5 個轉紅，包含
`標準J位置_極性:613 深度超賣（K=23.7 < D=45.5）的 J 位置分量必須為正，實際為 -0.782`、
`dailyStrongerMomentumLowersShortScore:519 Expecting actual: 82 to be less than: 78`。
第 6 個（d-2「只改 j9 斷言分數不變」）正確地維持綠燈——它守的是「還在不在讀 `j9`」，與極性正交。

**既有期望值變動 8 處**，全部先手算再跑，方向皆符合 360.7g 準則（K>D 下降、K<D 上升）。另修一處
`dojiWithRealRangeContributesZeroBodyDirection`：V16 後兩臂變成 70.303 / 69.744 被 `Math.round` collapse
成同值使斷言退化為恆真，語料由 `O=C=15` 改為 `O=C=12`（仍 `high > low`、仍 `close == open`），
受測性質不變。

### 實機驗證（Docker，`--no-cache` 重建 business-services 與 frontend）

- 運行中 jar 實測含 `TW_RULES_V16`（`unzip -p /app/app.jar … | strings`）。
- `GET http://127.0.0.1:9090/api/public/trading-radar/today` → HTTP 200、`ruleVersion: TW_RULES_V16`、38 檔。
- 前端 HTTP 200；`docker logs asset-bff --since 3m | grep -cE "Connection refused|500"` → `0`。

**分數方向實測（修正前後同一組標的比對，排除 1 檔因還原權息基準刷新而 K/D 重算的 `2885`）：**

| 分組 | 結果 |
|---|---|
| K<D（動能轉弱，預期上升） | **21/21 符合** |
| K>D（動能轉強，預期下降） | **11/11 符合** |
| 反向者 | **無** |

代表性個案：`NVDA`（K−D = −21.8）短分 `54 → 57`、中分 `68 → 69`；`00881`（−20.8）短分 `56 → 60`；
`00882`（+17.5）短分 `59 → 58`、中分 `66 → 65`——與本任務背景段的量化預期一致。

`J9`／`K3D2` 顯示值 34 檔逐位不變；全 38 檔 `j9 = 3D − 2K` 仍成立（誤差 > 0.05 者 0 檔），
確認顯示路徑未被污染。

### 過程中的意外

1. 首次 `docker compose build` 撞到 buildkit `lease does not exist`，`docker builder prune -af`（清出 22GB）
   ＋重拉 `eclipse-temurin:21-jre-alpine` 後重建成功。
2. `PortfolioAdviceServiceEngineTest.getSettings_exposesEngineAndItsWhitelist` 在本分支紅燈，查證後為
   **本分支落後 main 10 個 commit** 所致——main 的 `d464ee16` 已把 `assertEquals(3, availableModels().size())`
   改為斷言內容而非數量，與本任務無關，merge 後自動消失。
3. `2885` 在驗收比對中 K/D 由 `76.19/71.91` 變為 `27.09/36.19`（同 `asOfDate`、同價格）。成因是
   2026-08-18 的股票股利事件（0.4 ＋ 2.6269）使還原權息基準改變、均線與 KD 全部重算，**與本次修正無關**，
   已在方向驗收中排除該檔。

### 對抗式審查紀錄

`/spec-review` 共跑三輪，每輪派新的 `spec-auditor`，累計修正 4 個 critical 與 12 個 major。其中兩項值得記錄：

- 第 2 輪抓到修正過程中新引入的算術錯誤：`50 × 0.03 × 2.00 ÷ 3` 被寫成 `1.11`（正解 `1.00`）。
- 第 3 輪抓到原 360.7d 的測試設計**在正確實作下也必然失敗**：`jPosition` 與 `direction = signum(K−D)`
  是同一個 `averageAvailable` 的兄弟分量，對稱例值（60/40 vs 40/60）分量和逐位相等，且一般性地
  `Δ(K<D − K>D) = −2 + [clamp(p+m/20) − clamp(p−m/20)] ≤ 0` 恆成立。已改為「同向、不同動能幅度」
  ＋「只改 `j9` 斷言分數不變」兩條互補斷言。

`arch-auditor`（diff-scoped）零 findings，並獨立重算全部 8 處期望值變動確認方向正確。
