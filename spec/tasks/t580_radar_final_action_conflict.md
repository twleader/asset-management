# [t580] 交易雷達持有期分歧改依最終動作判斷

**對應 Requirements:** Requirement 93（三軌分歧只揭露、不合成）、Requirement 117（候選與實際動作三軌同等可讀）
**前置任務:** t356、t382、t487、t575、t576、t577、t579
**Liquibase changeset:** 無

## 背景

目前 V26 的 TradingRadarService 對列表與完整明細輸出安全閘門處理後的 action／swingAction／shortAction，horizonConflict 卻沿用 DecisionCore.result 的原始規則結果。當原始三軌皆 TRIAL_BUY、horizonConflict=false，且 SHORT 分鐘證據缺漏將 shortAction 降為 HOLD，而 MEDIUM／SWING 仍 TRIAL_BUY 時，回應錯誤地表示沒有分歧。反向情境也會發生：原始分歧經閘門降級為同組後，旗標仍可能保留 true。正確行為是依同一回應最後輸出的三軌動作分組計算。

## 要做什麼

- [x] 580.1 共用規則引擎現有的純動作分組與分歧判斷，不新增外部依賴。分組為 BUY_CANDIDATE／ADD_CANDIDATE／TRIAL_BUY；HOLD／WATCH／HOLD_CAUTION／WAIT；REDUCE_CANDIDATE／EXIT_CANDIDATE／AVOID；NO_TRADE／null 四組。任兩軌分組不同即 true，全部同組即 false。缺失 swing 保持 null 的既有無法判定組，不複製另一軌、不推導動作。原始引擎、baseline 與 candidate 使用同一分組，行為保持不變。
- [x] 580.2 TradingRadarService 的 toListStock 與 toFullDecision 都以 officialProjection(...).gated() 的 mediumAction／swingAction／shortAction 算 horizonConflict，確保 evidence、SHORT 分鐘確認及官方 SMA20 閘門全部執行後才判斷。不從 core.result 的原始旗標或 candidate 欄位取代。沿用既有投影，使 current-read、單檔評估的 summary／stock、新建 full snapshot／export 同源；不回寫歷史 immutable snapshot。失敗列保持既有 NO_TRADE／null 與 false。
- [x] 580.3 以既有 TradingRadarRuleEngineTest 測分組與 null／NO_TRADE 邊界；以既有 TradingRadarPanelServiceTest 的真實 TradingRadarService 呼叫（可用 spy engine 提供固定原始候選，行情／repo 用固定 persisted fixture 與 mock ports）驗證 list／full／單檔摘要明細。測原始一致但 SHORT gate 關閉後分歧、原始分歧但 evidence gate 收斂後不分歧、官方 SMA20 gate 後仍依最終動作、各軌同組不同動作不分歧、無變化與失敗列。斷言三軌原始／candidate／final action、分數與 gate 診斷未被本修正改寫，不可只測純 helper 或 mock DTO 來替代投影接線驗證。
- [x] 580.4 本任務僅修復既有派生布林欄與其同筆回應動作不一致，不改買賣策略、權重、門檻、公式、資料來源、候選／最終 action、理由／風險內容或通知 transition；沿用 TW_RULES_V26 與 EVIDENCE_GATE_V2。不得新增／修改 API path、method、query、DTO schema、YAML／Swagger／policy／SRPP authority 或 registry，亦不改 DB、Redis key、排程、前端或券商能力。零下單、零寄信、零重跑報告。
- [x] 580.5 通過獨立 Codex spec／arch 審查、機械檢查與限定回歸後，以 run-stack 僅重建／recreate business-services，保持 BFF 運作並驗證恢復；確認 actual API 的每筆 horizonConflict 都符合回應最終三軌分組。僅以固定測試資料重現盤中 SHORT gate，禁止為驗收向正式 DB／Redis 注入假行情。當天無自然分歧案例時如實記錄，不能把所有 false 稱為盤中反例已實測。依 commit-merge-push 以 feature 中文短 commit、main no-ff merge、push 並核對 remote；合併後從乾淨 main 及其原設定重建必要服務使共用 stack 收斂。

## 驗證

```bash
bash scripts/spec-check.sh main
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true -Dtest='TradingRadarRuleEngineTest,TradingRadarPanelServiceTest,TradingRadarEvidenceGateTest,RadarOfficialSma20DecisionTest,RadarIntradayCandlePolicyTest,TradingRadarServiceOwnerScopeTest' test
ruby scripts/render-9090-openapi-docs.rb --check --asset-only
```

部署前僅由 main 向驗收 feature 補齊缺少的部署設定，記憶體中比對 env 與 secrets／TLS mounts，不輸出秘密、不覆蓋 main。驗收 feature 時從該 feature build；合併後從乾淨且追平 origin/main 的 main build，compose project 先以現行 asset-frontend label 取得。本任務只涉及 business-services：

```bash
docker compose -p asset-management build business-services
docker compose -p asset-management up -d --no-deps --force-recreate business-services
```

等待 business-services healthy 後，保留 BFF 並以 9090 安全 GET 最多 30 秒重試驗證恢復。讀取 `http://127.0.0.1:9090/api/public/trading-radar/today` 與既有持股的 `http://127.0.0.1:9090/api/public/trading-radar/stock?stockCode=GOOGL&market=%E7%BE%8E%E8%82%A1`，在記憶體比對每筆 action／swingAction／shortAction 的四組分歧與 horizonConflict，僅回報筆數與布林結果，不列完整個資。前後 image SHA 固定、effective config 與 main 相同，既有 Swagger／authority bytes 不變；未觀察到盤中自然反例須保留驗收限制。

## 完成報告

- 規格獨立 Codex 第一輪 PASS（critical／major／minor 皆 0），完成規格閘門後才改實作。
- TradingRadarRuleEngine 的既有 horizonConflict 改為可共用的 public static 純函數，既有 actionGroup 僅改 static；分組內容與引擎既有輸出不變。TradingRadarService 兩個列表／完整投影改以最後 gated medium／swing／short 動作呼叫該函數。未修改 score、候選或最終 action、安全閘門、通知、API schema、Swagger、SRPP authority 或版號。
- TradingRadarRuleEngineTest 新增四組動作的完整三軌組合回歸，含 NO_TRADE 與 null。TradingRadarPanelServiceTest 新增 5 案真 service 接線測試：SHORT 缺分鐘資料導入分歧、evidence 降級收斂、官方 SMA20 最後降級收斂、同組不同動作與缺 swing、無閘門改變的買進組；既有失敗列追加 false 斷言。以固定 observation evidence 隔離品質資料，真正的 EvidenceGate、分鐘及官方 SMA20 閘門仍執行；summary／full／list／panel 都驗三軌值與旗標一致，分數、候選及原始理由／風險保留。
- 限定 Maven 160 tests、0 failures／errors／skipped；renderer --check --asset-only 與 git diff --check 通過。完整測試及各 suite 收據在本次工作階段的 /private/tmp/radar-logic-audit-20261114/t580-implementation/。
- 獨立 Codex 架構第一輪 PASS（critical／major／minor 皆 0），四支程式與測試檔 SHA-256 與送審內容完全一致；spec-check 為 BLOCK 0／CHECK 0。審查後任務檔僅回填 checkbox 與完成報告，規格語義維持原樣。
- feature run-stack 驗收完成：僅重建／recreate business-services，image `sha256:c8bdcd7988c36969b8fe3d0c99da300fd17502e47372112f9402f41a7c29af22` healthy，其他八個容器不變且 BFF 未重啟。今日雷達 36 列與 GOOGL 明細的 horizonConflict 均符合各自最終三軌動作分組；47 個 effective config keys 與三個 mount 同 main，執行中 class bytes 同 feature target，Swagger／authority bytes 維持 main 原樣，前後 image pin 穩定。自然盤中 gate 分歧案例未觀察到（NOT_OBSERVED），固定反例由 160 項測試覆蓋，不以全部 false 代替自然反例實測。收據位於 `/private/tmp/radar-current-runtime-audit-20261114/t580-feature/summary.json`。
- 正式 Git 已由 feature `7c2c1bbac0269228c54f8a511e9dd26ac3d0fc77`、main no-ff merge `80bf5b023d50928ae2edda2c439e3b1b8fb7caac` 完成並推送，remote refs 精確讀回。其後從乾淨 main 重建／recreate business-services 並通過驗收：image `sha256:4354b66c26e5e50b78642edec5c7a88b92277a3bc1428f8f66587f6badde1f89` healthy，其他八個容器不變、BFF 未重啟；36 列與 GOOGL 明細的最後動作分歧均一致，47 設定鍵／三掛載及 class bytes 通過，前後 image pin 穩定。當時沒有自然盤中 gate 分歧案例，固定反例仍以 160 項測試為證。收據：`/private/tmp/radar-current-runtime-audit-20261114/t580-main/summary.json`；後續 V27 部署保留本修正。未寄信、重跑報告或交易。
