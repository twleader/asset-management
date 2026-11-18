# [t605] D-223 現金買進與金融股退出獨立方案權威同步

**對應 Requirements:** Requirement 210（退休配置權威同步）的具名現金買進方案
**前置任務:** t603、t604
**Liquibase changeset:** 無

## 背景

使用者持有現金，指定00881低於38元可先買，不必等2881賣出；2881賣價更正為高於152元且保留原賣出條件，2885本方案先保留。舊規則只有一般現金先買與核心賣出，未列本次價格與跨日總預算；不能只有文案更新卻讓既有API繼續忽略新條件。

## 要做什麼

- [ ] 605.1 獨立Codex規格審查及記錄gate。
- [ ] 605.2 精確同步SRPP execution_policy.named_cash_rotation_d223：固定plan_id D223_00881_CASH_2881_EXIT_20261118；00881原始台幣合格成交價嚴格<38元，以已交割合格未專用現金分批買，單一方案累計400000元含買進費用與有效掛單占用。不得因日期、consumer、報告或bundle變更重置，不把建議當成交，預算未知不得當0。2881嚴格>152且原coreTiming全部通過；兩腿獨立，買方不等賣方，未交割賣款不能作現金。2885排除本次替代賣方，其他獨立必要風險授權保留。
- [ ] 605.3 原有CORE_SELL_GUARD對2881增加價格必要條件，不能改寫原已驗BLOCKED為PASS；具名買方/rotation用途若完整跨日budget ledger、有效掛單、資金專款、動態配置容量未提供，保留具名UNKNOWN/UNAVAILABLE，不虛構正容量。報告仍揭露價格是否觸發、總預算400000元、已用/剩餘未知與原因，不能吞掉方案；資產API使用既有receipt reason/missing，不增加wire。00881一般BUY入口不得繞過本plan，明確歸屬的其他獨立策略才按原規則；其餘標的與獨立策略不擴張套用。不得新增每批20萬、交易頻率或到價自動交易。
- [ ] 605.4 同步正式model/manifest、既有V2配置文件（仍只表targets/reserves）、insert-only SQL、goldens及simulator runtime。公式來源清單包含新helper與實際變動計算器，原generator重產V3，V1/V2歷史公式不變。新增純函式與邊界回歸測試，不新增API、schema、DTO、資料表、背景producer、vendor I/O、刷新、寄信或券商呼叫。
- [ ] 605.5 獨立arch審查、targeted測試、authority gate與runtime來源一致後交發布協調者。兩專案feature commit/main no-ff merge/push核對後insert-only registry，立即exact hash 9090診斷讀回，再由乾淨main重建/recreate必要服務並重驗JAR與業務回應；時窗外不宣稱正式日報採用，不手動啟動producer、日報或交易。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=SrppReportModuleCalculatorTest,SrppPolicyDocumentTest test
python3 scripts/check-srpp-report-authority.py --srpp-root /Users/steven/.codex/worktrees/d223-cash-rotation/SRPP --check
python3 scripts/generate-srpp-formula-v3.py --srpp-root /Users/steven/.codex/worktrees/d223-cash-rotation/SRPP --check
```

新增helper測試預定涵蓋00881的37.99/38/38.01、2881的151.99/152/152.01、原賣出BLOCKED不解封、缺ledger保守未知、不同日期不重設、2885排除及其他標的不改。純模型原文同步不新增對外wire欄位；完整SRPP budget helper由SRPP原測試驗證，不在資產側臆造producer協定。部署驗收由release協調者使用run-stack僅必要服務rebuild/recreate，registry與JAR權威bytes精確核對，HTTP200不等於交易已授權。

## 完成報告

待實作與驗收後回填。
