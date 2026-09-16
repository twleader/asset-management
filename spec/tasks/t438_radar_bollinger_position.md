# [t438] 完成日 K 布林延伸風險納入既有乖離因子

**對應 Requirements:** Requirement 156（完成日 K 布林上方延伸以有界扣分納入三軌既有乖離因子）
**前置任務:** 無
**Liquibase changeset:** 無

## 背景

目前 production TW_RULES_V18 用 23 個因子、三軌各自總權重 1。日 KD 已包含 WR9 與現算 standardJ=3K−2D，MACD 只取 OSC，BIAS10／20 平均而 B10−B20 只展示；再加獨立動能或均線權重容易重複。布林 %B 提供相對價格位置、BandWidth 提供價格離散尺度，但上／下軌觸及不是單獨賣／買訊。官方原則：https://www.bollingerbands.com/bollinger-band-rules 。本任務只採保守上方延伸扣分；所有窗口、衰減與扣分幅度為判斷性產品取值，沒有回測獲利依據，不宣稱準確度、報酬或風險機率提升。

## 要做什麼

- [x] 438.1 在共用 RadarInputAssembler 上新增不可變 BollingerInput，以相同完成日 K 權息還原後、降序序列的 firstCompletedIndex 起連續 20 根收盤計算。不得用 live/in-progress 列、不得跳過 null 導致跨缺口湊數，期間每筆 close 必須正值、20筆日期嚴格遞減，任何無效值／根數不足回 null。中軌 M=sum(C)/20；population σ=sqrt(sum((C−M)^2)/20)；upper=M+2σ，lower=M−2σ；BandWidthPercent=(upper−lower)/M*100；percentB=(最新完成close−lower)/(upper−lower)。BigDecimal DECIMAL128 中間運算、sqrt；最終欄位scale 8 HALF_UP，有限值且正分母；σ=0時保留 M/upper/lower、width=0，percentB=null。日期 asOfDate 是同一第一根完成 K 日期。不得與既有9日KD高低帶 bandWidthPercent 混用。布林沿用Prepared既有權息還原共同價基，不另還原一次；若進行中live触發既有分割啟發式而共同縮放完成K，absolute bands仍同完成dailyCandle的價基，比率%B/width/延伸扣分在scale8容差內保持不變。盤中列本身不得計入20根。
- [x] 438.2 BollingerInput 由 assembler 同一結果進 StockInput、production compact list/core/full/detail/notification/export 與 offline baseline 共用路徑。相容 StockInput 舊建構式明確以 null 補此欄；必須查所有完整建構點，避免新欄在相容 overload 靜默遺失。不新增資料庫／Redis資料源、不得外呼、啟用未證實富邦overlay、改寫已保存富邦原指標。
- [x] 438.3 只改既有日 BIAS 因子內部值，不增加第24因子：原值 b=meanAvailable(clamp(-BIAS10/10,−1,1),clamp(-BIAS20/20,−1,1))。有效 percentB 與 width>0 時，extension=clamp(2*(percentB−0.5),0,1)；reliability=min(1,width/2)*min(1,20/width)（width是百分點）；penalty=0.25*extension*reliability；新值=clamp(b−penalty,−1,1)。b缺值時整個因子仍缺值，布林不得補成方向；布林缺值或width=0時沿用b而不扣分。中軌及下方不加分，上軌與上方最多扣0.25；窄幅平滑衰減、極寬幅平滑衰減；不得新增直接買賣覆寫，不得把下軌/窄幅當買訊或上軌當賣訊。
- [x] 438.4 日 BIAS 三軌權重仍各0.06，23因子及三軌權重、其餘thresholds、weekly因子、market因子不變。此扣分是『布林上方延伸風險』，理由/risks文本與factor說明須透明區分原乖離與扣分。僅實際penalty>0時在每軌本地risks揭露扣分，缺資料揭露『布林完成日資料不足，本次沿用原乖離因子』；width=0揭露『布林區間無寬度，本次不採計延伸扣分』。不得放入support reasons。EvidenceGate最後仍以同軌coverage/freshness/dividend裁切，EVIDENCE_GATE_V1不變。
- [x] 438.5 RULE_VERSION 升 TW_RULES_V19；有效decisionInputVersion使用V19|現行technicalSourceVersion，不保留V18前綴。CLAUDE歷史R117/V18需標本任務覆寫而非全域替換。同步所有 production metadata、hardcode、測試及文件有效敘述；V13 candidate 參數與 evaluateCandidate 維持既有規則，不納入新扣分、不推動candidate promotion。離線 production baseline用共用assembler且asOf截斷，不lookahead；補可重放的新舊對照，包括三軌score/candidate/final-action變動數與缺布林fallback，不要求獲利改善。快取 fingerprint 必含布林所有有效輸入/日期/公式版本，舊BOUND不得命中；通知既有ruleVersion mismatch重建baseline首輪不寄信。保留三軌與action-policy版本。
- [x] 438.6 最小新增 nullable DTO 欄位 bollinger（asOfDate date、period int=20、standardDeviationMultiplier int=2、middleBand/upperBand/lowerBand/percentB/bandWidthPercent decimal nullable），資料只能投影 assembler 本次唯一結果；舊snapshot缺欄解碼null。不改既有欄名／型別／required／語意，不加public endpoint；snapshot/JSON/Excel新欄明示本機完成K來源與扣分用途。OpenAPI同步所有可達detail/full typed contract，version下一相容minor並重產兩份Swagger。前端若需變更只render，不重算或替使用者選持有期。

## 驗證

```bash
bash scripts/spec-check.sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=TradingRadarRuleEngineTest,TradingRadarEvidenceGateTest,TradingRadarNotificationServiceTest,BacktestServiceTest test
ruby scripts/tests/docker-external-api-openapi-test.rb
```

新增布林完成日公式單元測試與雷達布林整合測試；既有 Maven 指令先跑現存測試，新增完成後另執行新增測試。

測試須證明已知20筆population公式、常數width=0、19根/null/非正/日期倒置拒絕、權息一致、改live不改20根取樣；極端live觸發既有分割啟發式時，完成K共同縮放的absolute bands可同dailyCandle變更，%B／width／延伸扣分保持比例不變（容許scale8數值誤差），不宣稱其餘live因子分數不變、窄幅衰減、極寬衰減、中軌/下軌無正加分、上軌無直接賣覆寫、原BIAS缺值不被補齊、23因子69權重不變、三軌risks與最後gate仍各自正確、production offline/shared一致且未來列不影響、V13 candidate逐位不變、舊snapshot/舊建構式null相容、cache新舊隔離、升版notification首輪無郵件。測試類存在性先查，若新增測試類須標記為待實作。

透過run-stack規定比對main與feature環境變數key/值且不洩密；用正確同步環境重建recreate business-services、必要BFF/frontend，實際唯讀 GET 9090 today/stock與authenticated BFF驗三軌、TW_RULES_V19、完成日日期/布林值。部署驗收不執行券商、下單、manual sync或外部通知；沒有效真實資料則精確記錄無法查證而不可宣稱runtime布林已採計。驗收後依專案規定commit/no-ff merge/push，main重建保持部署與main一致。

## 完成報告

已完成本機 BB20／2σ、既有 BIAS 內有界延伸扣分、V19 與快取／通知來源版本、相容 DTO、Excel 末端 9 欄，以及 BFF 嚴格 nullable 契約與 OpenAPI 1.12.0／92 schemas／兩鏡像。完成日共價基、極端 live 分割縮放比例、資料不足 fallback、V13 三軌逐位相容與舊快照皆有測試。240 個合成離線樣本／720 三軌觀測中，分數變動79／93／97、候選與最後 gated action 變動2／0／0；這不是真實行情獲利回測。Docker 指定2330台股純讀明細回200、V19，asOfDate=2026-09-16、period=20、multiplier=2，布林8欄可讀。

本批後端最新256組測試報告共2214項、失敗0／錯誤0；BFF全套67組316項、失敗0／錯誤0。獨立架構最終覆核0 critical／0 major／0 minor；機械 spec／OpenAPI／兩鏡像／102表 schema drift 已通過。feature business／frontend／BFF 均已重建並重新部署驗收；分組提交、no-ff main 合併推送與 main 最終重建於本批收尾執行，不由測試替代部署。
