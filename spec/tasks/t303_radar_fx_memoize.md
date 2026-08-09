# [t303] 匯率分位 request 內 memoize（同幣別只解析一次）

**對應 Requirements:** Requirement 43／47 之 `TW_RULES_V12` 波修訂第 10 條
**前置任務:** t302（同檔 `TradingRadarService`，實作順序 t302 → t303）
**Liquibase changeset:** 無

## 背景

`TradingRadarService.buildStock()` 對每一檔底層幣別非 TWD 的標的呼叫 `marketContextService.resolveFx(currency, decisionInstant)`——每次查 5 年匯率（約 1,250 列）並重算分位。同一次 `assemble()` 內所有美股標的的幣別都是 USD、`decisionInstant` 相同，結果**完全相同**卻逐檔重查重算。持有／觀察 20 檔美股 = 20 次相同的 5 年查詢。

**正確行為**：單次 `assemble()`／`evaluateForNotification()` 內，同幣別的 `FxContext` 只解析一次。**不引入跨 request 快取**（匯率日內會更新、且 request 間 `decisionInstant` 不同，跨 request 快取需要失效策略，成本效益不成立）。行為逐位不變，純效能重構，不升版。

## 要做什麼

- [x] 303.1 `backend/src/main/java/com/steven/assets/service/TradingRadarService.java`：
  - `buildStock(Target, MarketRegime, boolean, Instant)` 增加參數 `Map<String, TradingRadarMarketContextService.FxContext> fxCache`。方法內原
    `TradingRadarMarketContextService.FxContext fx = TWD.equals(currency) ? FxContext.EMPTY : marketContextService.resolveFx(currency, decisionInstant);`
    改為
    `TradingRadarMarketContextService.FxContext fx = TWD.equals(currency) ? TradingRadarMarketContextService.FxContext.EMPTY : fxCache.computeIfAbsent(currency, c -> marketContextService.resolveFx(c, decisionInstant));`
    （`resolveFx` 失敗回 `FxContext.EMPTY` 而非 null，`computeIfAbsent` 安全；`currency` 已由 `underlyingCurrencyOf` 正規化為大寫 trim。）
  - `assemble(Long ownerId)`：decisions stream 之前建 `Map<String, TradingRadarMarketContextService.FxContext> fxCache = new java.util.concurrent.ConcurrentHashMap<>();`（stream 目前是循序，但用 ConcurrentHashMap 防未來並行化踩雷），`buildStock` 呼叫處傳入。
  - `evaluateForNotification(..., MarketSnapshot)`（t302 的四參數版）傳 `new java.util.HashMap<>()`（單檔評估，cache 命中率 0，僅滿足簽章）。
- [x] 303.2 不動 `resolveFx`／`resolveFxFromRows`／`fxTargetDate` 本體；不動 `FxContext` 形狀；不改任何文案。
- [x] 303.3 測試（`TradingRadarUsStockEngineTest` 同檔新增）：兩檔美股標的（同 USD）跑一次 `assemble` 路徑（走 `get()` 或直接以 mock 觸發 `assemble`），`verify(marketContextService, times(1)).resolveFx(eq("USD"), any())`；另一檔台股標的不觸發 `resolveFx`（`TWD` 短路，既有行為回歸）。

## 驗證

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test -Dtest='TradingRadarUsStockEngineTest' -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

部署驗證併入 t305 完成後的整波驗證（見 t305）。

## 完成報告

## t303：匯率分位 request 內 memoize（同幣別只解析一次）

### 實際改動

**`backend/src/main/java/com/steven/assets/service/TradingRadarService.java`**（4 處，皆依任務檔 303.1 逐字對應）：

1. `buildStock(...)` 私有方法簽章新增第 5 參數 `Map<String, TradingRadarMarketContextService.FxContext> fxCache`。
2. 方法內原本直接呼叫 `marketContextService.resolveFx(currency, decisionInstant)` 改為
   `fxCache.computeIfAbsent(currency, c -> marketContextService.resolveFx(c, decisionInstant))`；
   `TWD.equals(currency)` 短路分支（回 `FxContext.EMPTY`）不變，不進 cache。
3. `assemble(Long ownerId)`：在 decisions stream 之前新建
   `Map<String, TradingRadarMarketContextService.FxContext> fxCache = new java.util.concurrent.ConcurrentHashMap<>();`
   （單次 request 內所有標的共用同一份），`buildStock` 呼叫處補上此參數，並加了一行行內註解說明動機（同 20 檔美股原本各自重查 5 年匯率的背景，及為何用 ConcurrentHashMap 而非 HashMap）。
4. `evaluateForNotification(String, String, boolean, MarketSnapshot)`（t302 新增的四參數版）呼叫 `buildStock` 時傳入 `new java.util.HashMap<>()`——單檔評估不需要跨檔共用，僅為滿足新簽章。

未動 `resolveFx`／`resolveFxFromRows`／`fxTargetDate` 本體，未動 `FxContext` 形狀，未改任何文案（303.2 要求），也未修改 `TradingRadarMarketContextService.java`——已用 `git diff` 確認該檔案的既有變更（來自 t302）不含 FxContext/resolveFx/fxTargetDate 相關字串異動。

**`backend/src/test/java/com/steven/assets/service/TradingRadarUsStockEngineTest.java`**：新增一個測試（分節標題 `(j) 同一 assemble() 內同幣別 FX 只解析一次（Task 303）`），純附加在檔尾、未動任何既有測試：

```java
@Test
void assemble內兩檔同幣別美股只呼叫一次resolveFx且台股短路不觸發() {
    stubBaseline();
    when(alertRepo.findDistinctStockCodeMarket()).thenReturn(List.<Object[]>of(
            new Object[]{"AAPL", "美股"},
            new Object[]{"MSFT", "美股"},
            new Object[]{"2330", "台股"}));

    TradingRadarDto.Response resp = newService().get();

    assertEquals(3, resp.stocks().size());
    verify(marketContextService, org.mockito.Mockito.times(1)).resolveFx(eq("USD"), any());
    verify(marketContextService, never()).resolveFx(eq("TWD"), any());
}
```

兩檔美股（AAPL、MSFT，`stockRepo` 未 stub → 依 Mockito 預設回傳空 `Optional`，`underlyingCurrencyOf` 依 market="美股" 預設得 "USD"）同走一次 `get()`→`assemble(null)`，驗證 `resolveFx(eq("USD"), any())` 恰好呼叫一次（cache 命中省下第二次）；台股（2330）驗證從未以 `"TWD"` 呼叫 `resolveFx`（`TWD.equals(currency)` 短路，既有行為回歸，未經 cache 分支）。`verify(times(...))` 沿用同檔既有的 `org.mockito.Mockito.times(...)` 完整限定寫法（該檔未 static-import `times`，與既有第 (d) 節 `evaluateMarket` 驗證同慣例），未新增 import。

### 驗證輸出摘要

```
mvn test -Dtest='TradingRadarUsStockEngineTest' -DextraArgLine=-Dnet.bytebuddy.experimental=true
→ Tests run: 13, Failures: 0, Errors: 0, Skipped: 0（原 12 個既有測試 + 本次新增 1 個，全綠）

mvn test -DextraArgLine=-Dnet.bytebuddy.experimental=true（整套 backend）
→ exit code 0；彙總 74 份 surefire 報告（含 @Nested 內部類各自的報告檔）：
  Tests run: 623, Failures: 0, Errors: 0, Skipped: 0
```

整套測試全綠。有注意到 log 尾端出現一段 `RcloneTimeoutException: timeout` 堆疊，經確認來自既有測試 `GdriveOutputSupportTest.逾時的措辭與確定性失敗分開`（該測試本就故意觸發逾時例外驗證錯誤訊息措辭），非本次改動引入、亦計入上述全綠統計，非異常。

### 與原計畫的偏差

無偏差。任務檔 303.1 給的「原始碼片段」與「新片段」與工作區當下實際內容（含前置任務 t302 已落地的四參數 `evaluateForNotification`／`MarketSnapshot`／`buildMarketSnapshot`）完全吻合，逐字套用即可，未發生任務檔行號或片段與現況不符需要調整識別字定位的情況。

### 更新過期望值的測試清單

無。本任務為純效能重構（stream 內同幣別 memoize），行為逐位不變，未修改任何既有測試的斷言或期望值——僅新增一個測試方法驗證新增的快取行為（`resolveFx` 呼叫次數），其餘 12 個既有測試與其餘 61 個既有測試類完全未觸碰。
