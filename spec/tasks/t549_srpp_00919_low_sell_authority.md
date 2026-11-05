# [t549] 發布00919低檔減碼保護與台股擇時authority

**對應 Requirements:** Requirements 187／193（不可變報告計算及精確規則包發布）
**前置任務:** t547；SRPP D-208來源完成獨立審查與測試
**Liquibase changeset:** 無

## 背景

2026-11-05早盤Claude建議賣00919兩張，原因為既有高檔觀察後完成日KD下降及回落達標；當時完成日K32.31、D48.24，約09:09即時K33.85、D43.46。使用者明確要求修改規則，避免低檔追跌減碼。SRPP將加入00919專用保守保護；資產端台股coreTiming亦提供擇時PASS，因此必須同步執行，不能只有新hash。

## 要做什麼

- [ ] 549.1 唯一來源為 `/Users/steven/.codex/worktrees/00919-low-sell-guard/SRPP` 通過獨立審查及policy/runtime檢查的最終D-208原始bytes。00919台股一般D174減碼／退出，最近完成日原始日K、D和同輪精確雷達kValue、dValue四值都要是0..100有效有限數且嚴格>50；任一<=50、缺值／invalid／來源日期不可驗證時該筆不通過。既有低檔／超賣／反彈反向仍阻擋。四值均>50只解除新增保護，不自動通過原高檔觀察、轉弱、回落、價格、雷達、配置及其他硬閘門。保護非保證最低點或報酬最佳；不改買進、其他代號、美股及既有具名必要退出獨立路徑，不新增可由client旗標繞過的緊急例外。
- [ ] 549.2 由實作subagent在獨立spec審查後，對台股 `SrppReportModuleCalculator.coreTiming`／呼叫端加入同源policy gate。Java/Python對同一四值、低檔conflict及缺值取得等價reason與timingPass；不改published API／schema／模組集合或新增SELL legs。觀察狀態的arming fingerprint與執行gate分離，保留consumer／原高水位歷史，US calculator完全不改。直接端點與投影不得以新bundle承接舊PASS繞過保護。
- [ ] 549.3 凍結後讀回完整bundle、model／manifest／Swagger hashes；逐位元同步backend decision model／manifest，以原canonical targets projection核對formal document，以原Ruby完整stdout重產insert-only SQL，不UPDATE／DELETE歷史row。顯式指定同一SRPP root，以原Pythongenerator重產same-input及long-short-exit golden；新增00919案例由來源原工具產生expected，不手改金融數字。其他案例未涉及的金融輸出、targets、formula manifest及Swagger維持；本輪必須變動的calculator source hashes逐項說明。
- [ ] 549.4 meaningful回歸含四值逐項=50／<50／>50、缺漏／非數字、既有低檔／反彈反向、原D174其他條件仍阻擋、其他代號與US維持，以及歷史觀察保存。補Java/Python同輸入golden並跑既有authority／registry generator regression及聚焦Java測試；離線package建立可重現artifact。spec-check BLOCK0、diff-check與另一subagent依完整diff做arch審查；不把歷史離線回放當今日即時資格。
- [ ] 549.5 正式鏈依使用者既有完整發布授權及本輪 `srpp-policy-release` 技能發布順序步驟3至5：兩repo各自commit／no-ff merge／push及remote-ref readback後才SQL，再由乾淨main部署。這明示取代Task504任務舊段feature先serve／main重建後才SQL的不同順序，並非Task504原文。此前只做離線build及測試，不替換正式容器；雙repo落地後自main受追蹤SQL單次insert，精確讀回policy_bundle_sha256／formula_version／policy_document／formula_manifest全文及既有rows不變。登錄後立即以新bundle+原Swagger從SRPP host唯讀GET report-calculations，保留真實HTTP/body，409／503不能當已接受。
- [ ] 549.6 從乾淨asset main及正式.env／mount僅build及force-recreate必要business-services。部署前唯讀核對原startup當日analysis／index／到時匯出／備份已完成、配息補抓停用、PROCESSING=0及snapshot／到期在途款不需補跑；避免已啟用工作分鐘，不改設定／排程。保留其他容器/BFF，health最多120秒、safeGET最多30秒，不例行重啟BFF。讀回JAR model／manifest／Swagger／registration SQL／formal document與已合併bytes，重核新hash業務API。來源、部署、registry、HTTP各保留證明範圍；正式日報採用須原合法入口／required_reads／直接L0／verifier，其他只記診斷或NOT_YET_EXECUTED。不觸發報告、observer、producer、refresh、寄信或交易。


D-208凍結契約：設定位於 `execution_policy.core_sell_guard_d174.low_point_sell_protection_d208`，decision=`D-208`、enabled=true、symbols=[00919]、market=台股、minimum_kd_exclusive=50；其他低檔數值為rsi5_at_or_below=30、wr9_at_or_above=80、bias10_at_or_below=-8、week52_position_at_or_below=0.15。blocked_radar_states固定為BUY_CANDIDATE／ADD_CANDIDATE／LOW／LOW_WATCH／OVERSOLD／OVERSOLD_WATCH／REBOUND_WATCH／ENTRY_WATCH。任一數值低檔條件、股票本身timingState／counterTrendState／action／candidateAction命中集合，或timingLabel／counterTrendLabel／reasons出現「低檔」「超賣」「反彈觀察」均封鎖；不是搜尋全市場敘述。KD／RSI／WR域0..100、position域0..1、BIAS有限，數值缺漏／型別不合法皆不可驗證。Java使用source同欄位，有效低檔值回reason=`D208_LOW_POINT_SELL_BLOCKED`及timingPass=false。D208新增設定／附加技術證據缺口沿既有局部Gap(reason=`D208_LOW_POINT_EVIDENCE_UNVERIFIED`)處理；原KD／數值／來源驗證失敗保留原具名Gap。`calculateCore` catchGap只記module missing／UNAVAILABLE，該檔不輸出timingPass fact或PASS receipt，不能宣稱已回false JSON。這與Python timing_pass=false在「不得執行」語意等價，golden分別驗有效值的timingPass／reason parity及缺值的Java Gap／module無PASS、Pythonfalse，兩者不是JSON逐值相等。不新增envelope欄位。四值中任一=50仍封鎖，值全部>50尚需其他低檔及原D174條件。

保留觀察fingerprint：Python已有四欄decision／scope／arming／non_distributing_symbols穩定指紋；Java台股原本驗整份policy的歷史相容雜湊，加入執行欄位不能讓舊state全部失效。Java只接受同四欄穩定指紋、當前完整policy精確指紋，以及去除這個新執行欄位後的原完整policy精確指紋；不得接受任意字串或只憑symbol放行。測試保留舊高水位與scope／arming遭竄改仍拒絕；不改寫來源state。

公式身分：registry的ASSET_MGMT_SRPP_V2 manifest僅描述assets／allocation／cashIncome／funding／completedTechnicals，沒有D174 scope，本輪不改該五項公式。報告十二模組共同的SRPP_REPORT_CALCULATIONS_V1為既有計算族／wire契約身分，保持以免擴大到無關scope及schema；D208實際語意變更由新bundle、完整modelInputsSha256／policyManifestSha256、CORE_SELL_GUARD具名D208 scope及derived同輸入golden綁定，不冒稱00919金融輸出不變。

## 驗證

```bash
bash scripts/spec-check.sh
python3 -B scripts/check-srpp-report-authority.py --srpp-root /Users/steven/.codex/worktrees/00919-low-sell-guard/SRPP --check
python3 -B scripts/generate-srpp-report-golden.py --srpp-root /Users/steven/.codex/worktrees/00919-low-sell-guard/SRPP --check
python3 -B scripts/generate-srpp-long-short-exit-golden.py --srpp-root /Users/steven/.codex/worktrees/00919-low-sell-guard/SRPP --check
ruby scripts/tests/srpp-policy-register-test.rb
ruby scripts/tests/srpp-report-policy-register-test.rb
SRPP_RELEASE_WORKTREE=/Users/steven/.codex/worktrees/00919-low-sell-guard/SRPP python3 -B scripts/tests/check-srpp-report-authority-test.py
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=SrppReportModuleCalculatorTest,DecisionPolicyAuthorityTest,DailyDecisionCalculatorTest,SrppReportShortUsCalculatorTest test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DskipTests package
ruby scripts/render-9090-openapi-docs.rb --check --srpp-root /Users/steven/.codex/worktrees/00919-low-sell-guard/SRPP
python3 -B scripts/sync-srpp-swagger-identity.py --check --srpp-root /Users/steven/.codex/worktrees/00919-low-sell-guard/SRPP
git diff --check
```

正式serve驗收在雙repo落地及registry後，由run-stack從main執行 `docker compose -p asset-management build business-services` 與 `docker compose -p asset-management up -d --no-deps --force-recreate business-services`；先驗真實Compose project是asset-management及main正式設定，不從feature另建stack。JAR全文、registry四欄、精確新hash HTTP及原設定／其他容器逐項保留。

## 完成報告

待來源凍結、獨立審查、實作與發布驗收後填入；本段未宣稱任何正式發布或交易資格。
