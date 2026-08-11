# [t318] 交易雷達 Treasury 未來曲線 fail-closed

**對應 Requirements:** Requirement 65（交易雷達只能使用 decision-time 可得且時效可證明的資料）
**前置任務:** 無
**Liquibase changeset:** 無

## 背景

`TreasuryYieldService.resolveRateContext(Instant,String)` 先用美東 16:00 判斷最新 completed US session，再由 `sessionLag(LocalDate,LocalDate)` 計算曲線落後的交易日數。現行 `sessionLag` 對 `curveDate` 不早於 expected session 的所有情況都回傳 0，因此受污染、錯誤 revision 或測試資料若帶有未來 `curveDate`，會被誤認為最新資料。

正確行為是 fail closed：未來曲線必須是 `STALE/FUTURE_CURVE_DATE`，並保留原始 batch、provider、四 tenor source manifest 與 available-at provenance，不能改成「查無資料」。現行 `MarketDataService.isTradingDayKnown("美股",date)` 對非 null 日期使用本地美股交易日曆並固定回 `Optional.of(boolean)`，沒有 production-reachable 的 UNKNOWN 來源；日曆不可用／例外轉成 `UNKNOWN_CALENDAR` 的 adapter 改造不在本任務冒進實作，留待後續獨立任務。本任務不改 Treasury provider selection、利率 beta、V12 分數／參數、通知基準或 `TradingRadarRuleEngine.RULE_VERSION`。

## 要做什麼

- [ ] **318.1 明確區分 completed session 與 future curve。** 保留 Spring production constructor 對 `MarketDataService` 的必要注入；`resolveRateContext` 必須先以 `America/New_York` 及 16:00 判斷 decision 當時最新 completed session。收盤前從前一日往回找、收盤後可包含當日；`Optional.of(false)` 代表已知休市並繼續往前。不得移除 production calendar 注入或改用 compatibility constructor 的 weekday fallback。
- [ ] **318.2 未來曲線不得以 lag 0 冒充 fresh。** 當 `batch.curveDate() > expectedCompletedSession` 時，`RateContext.staleReason` 必須非空且含穩定代碼 `FUTURE_CURVE_DATE`。`RateContext` 仍須原樣保留 `batchId`、`provider`、四 tenor `sourceManifest`、`availableAt`、`availabilityBasis`、`fetchedAt`、`curveDate` 與選定 tenor/value；不得丟棄或改寫 batch provenance。
- [ ] **318.3 既有 completed-session lag 邊界不得回退。** 已知休市不增加 lag；恰好 3 個 completed sessions 仍 fresh，第 4 個 completed session才 stale。現行防禦性 null／empty calendar 行為可保留，但本任務不得用只靠 mock 可達的 UNKNOWN 分支宣稱 production 日曆不可用情境已完成。
- [ ] **318.4 production V12 行為逐位保持不變。** 本修正只改 Treasury freshness 與 evidence 判定。既有 evidence resolver 看到非空 `staleReason` 時仍把 bond-rate component 視為 STALE 並保留 reason/provenance；但現行 production `TradingRadarService` 對 V12 action 是 pass-through，並未套用 offline/V13 的 `TradingRadarEvidenceGate`，本任務不得把 stale evidence 說成 production action 已向 WATCH/HOLD 降級。`TradingRadarRuleEngine.RULE_VERSION`、V12 opportunity score/action、`RuleParameters.V12_DEFAULT` 與通知 rule-version baseline 必須維持 `TW_RULES_V12`，也不得藉本任務啟用任何 V13 candidate 或 beta。若要讓 production V12 action 受 evidence gate 約束，必須另立含 action contract、通知一致性與回歸驗證的任務。
- [ ] **318.5 回歸測試。** 擴充 `TreasuryYieldServiceTest`，至少直接驗證：收盤前／後 expected session、週末與連續 `Optional.of(false)` 休市、長假後最新曲線仍 fresh、恰 3 與第 4 個 completed sessions、future curve，以及 future stale 情況仍保留完整 provenance。測試須使用注入 mock `MarketDataService` 的 package constructor，不得只驗 weekday compatibility constructor。另鎖定 `TradingRadarRuleEngine.RULE_VERSION=TW_RULES_V12`，並執行完整 backend regression。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true -Dtest=TreasuryYieldServiceTest test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true test

docker inspect asset-frontend --format '{{index .Config.Labels "com.docker.compose.project"}}'
docker compose -p asset-management build business-services
docker compose -p asset-management up -d --no-deps --force-recreate business-services
docker compose -p asset-management restart bff
docker compose -p asset-management ps business-services bff
curl -fsS http://localhost:8080/actuator/health
curl -fsS http://localhost/
# 以本機既有 ACTIVE owner、且不輸出其值，從 business container 呼叫真實 GET /api/trading-radar read path。
# 記錄 BFF restart 的 UTC 時點，確認其後 log 無 Connection refused 或 500 Server Error。
```

## 完成報告

（回填 future/3-vs-4-session 測試結果、provenance 保留證據、完整 backend 測試數、business image/container/真實 read path 與 BFF restart 後 log 結果，以及 production 仍為 V12 的證據；另明列 UNKNOWN production adapter 尚未完成。）
