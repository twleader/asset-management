# [t445] 台股交易雷達以最近完成交易日作收盤基準

**對應 Requirements:** Requirement 43 修訂（收盤前以上一交易日完成收盤搭配當日行情）
**前置任務:** 無
**Liquibase changeset:** 無

## 背景

台股大盤 stale 現在要求當日完成收盤或當日 live，午夜過後到開盤前上一交易日的正常收盤會被標為過期。使用者要求收盤基準改為上一交易日，盤中以當日行情判斷。

## 要做什麼

- [x] 445.1 修改 TradingRadarService.buildMarket，使用明確 decisionInstant、Asia/Taipei、RadarObservationResolver.decisionSessionsStrict 與權威日曆：交易日收盤前目標上一交易日，收盤後當日，休市最近完成交易日。列表日曆用 isTwTradingDayCachedOnly；明細／通知用 isTwTradingDayKnown。未知不能猜週末或刷新列表日曆。
- [x] 445.2 日K／週K歷史截於 targetCompletedSession 與現有 marketAsOfDate（兩者較早），最新必要列日期必須等於 targetCompletedSession 且收盤為正；缺列、落後、未來列不能由當日 live 豁免。開盤前／休市只需有效完成收盤；盤中必須有效上一交易日收盤與當日 eligible live；盤後由當日有效完成收盤判定。live 日期須 decision 當地當日、價格正，拒絕 closed 與收盤 fallback。intraday 僅 OPEN 被採用當日 live。保留來源／時間戳，不新增秒級延遲門檻，不宣稱已驗證秒級行情即時性。
- [x] 445.3 保留美股流程、指標公式與權重、資料來源、API／DTO／DB／排程，不能新增 vendor I/O 或券商動作。列表／明細／通知共用大盤邏輯，版本由 V19 升 TW_RULES_V20，ACTION_POLICY_VERSION 保持 EVIDENCE_GATE_V1。通知版本變更首輪沿用只建 baseline、不寄 transition email。
- [x] 445.4 V20 同步 FubonRadarCompatibilityManifest.DECISION_INPUT_VERSION、TradingRadarDto 有效說明、OpenAPI 有效描述／範例及 generated Markdown mirrors（使用 scripts/render-9090-openapi-docs.rb）。只改版本標籤，manifest semantic fixture hashes／proof／APPROVALS 不變；同步受影響 active version tests（TradingRadarRuleEngineTest、TradingRadarNotificationServiceTest、BacktestServiceTest、TreasuryYieldServiceTest、RadarTechnicalResolverFubonOverlayTest、TradingRadarOpenApiSchemaContractTest 及相關版本斷言）。舊歷史任務版本保留，但 Requirement 156 當前版本由本修訂 V20 覆寫；前端 fallback 此次不動。
- [x] 445.5 固定時間測試：凌晨／開盤前、有效當日盤中 live、缺失／前日／closed／收盤 fallback live、盤後、週末／連假、缺完成列、未來列、未知日曆、列表 cache-only。執行相關美股與通知版本回歸。完成獨立規格與架構審查、Docker 服務驗證；驗收完成，commit/merge/push 接續執行。

## 驗證

```bash
bash scripts/spec-check.sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q -f backend/pom.xml -Dtest=TradingRadarMarketFreshnessTest,TradingRadarMarketWeeklyWiringTest,TradingRadarUsMarketVolumeWiringTest,TradingRadarNotificationTransitionTest test
docker compose -p asset-management build business-services
docker compose -p asset-management up -d --no-deps --force-recreate business-services
curl -fsS 'http://127.0.0.1:9090/api/public/trading-radar/today?email=tw.leader%40gmail.com'
```

服務需 healthy、雷達具體回傳與新 image provenance 驗證；BFF 保留運行，等上游 healthy 後重試正常復原。

## 完成報告

已完成 TradingRadarService 台股大盤完成日日期基準與歷史截斷；收盤前上一交易日、盤後當日、休市最近完成日。OPEN 必須上一完成收盤與當日正值 LIVE/nonclosed 同時完整，unknown fail closed；列表 cache-only。Rule／business manifest 版本 V20、DTO 有效說明及 OpenAPI／兩份生成 mirrors 同步，未變指標公式、美股流程、producer、前端、DB或API形狀。

獨立規格第2輪與架構/最終增量審查均 critical 0／major 0／minor 0；機械檢查 BLOCK 0／CHECK 0。固定時間 freshness 13 cases、影響回歸 199 tests、額外 fixture 10 tests 均通過。Temurin21 完整 backend 259 classes／2239 tests，failures 0／errors 0／skipped 0，Maven exit 0；git diff --check、OpenAPI mirrors --check通過。完整測試log /tmp/radar-prev-full-pass.log。

Feature business-services 映像 rebuild/recreate 後 healthy，parent fresh 9090 today 已驗證 TW_RULES_V20、38標的、market stale=false、asOfDate=2026-09-17、quoteStatus=PREVIOUS_CLOSE、intraday=false，修正原 9/18 凌晨 stale=true 重現。BFF維持運行。最終 main rebuild/provenance 由landing後run-stack再驗證，不以feature結果宣稱已完成main部署。

範圍限制：未新增秒級延遲門檻，本次不驗證行情秒級即時性；TechnicalIndicatorService與display resolver保留原獨立讀取路徑，完成日截斷僅本次大盤歷史rows。額外既有測試只補權威日曆fixture與剔除未完成列後足量歷史樣本，所有原斷言保留。
