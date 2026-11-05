# [t549] 發布00919低檔減碼保護與台股擇時authority

**對應 Requirements:** Requirements 187／193（不可變報告計算及精確規則包發布）
**前置任務:** t547；SRPP D-208來源完成獨立審查與測試
**Liquibase changeset:** 無

## 背景

2026-11-05早盤Claude建議賣00919兩張，原因為既有高檔觀察後完成日KD下降及回落達標；當時完成日K32.31、D48.24，約09:09即時K33.85、D43.46。使用者明確要求修改規則，避免低檔追跌減碼。SRPP將加入00919專用保守保護；資產端台股coreTiming亦提供擇時PASS，因此必須同步執行，不能只有新hash。

## 要做什麼

- [x] 549.1 唯一來源為 `/Users/steven/.codex/worktrees/00919-low-sell-guard/SRPP` 通過獨立審查及policy/runtime檢查的最終D-208原始bytes。00919台股一般D174減碼／退出，最近完成日原始日K、D和同輪精確雷達kValue、dValue四值都要是0..100有效有限數且嚴格>50；任一<=50、缺值／invalid／來源日期不可驗證時該筆不通過。既有低檔／超賣／反彈反向仍阻擋。四值均>50只解除新增保護，不自動通過原高檔觀察、轉弱、回落、價格、雷達、配置及其他硬閘門。保護非保證最低點或報酬最佳；不改買進、其他代號、美股及既有具名必要退出獨立路徑，不新增可由client旗標繞過的緊急例外。
- [x] 549.2 由實作subagent在獨立spec審查後，對台股 `SrppReportModuleCalculator.coreTiming`／呼叫端加入同源policy gate。Java/Python對同一四值、低檔conflict及缺值取得等價reason與timingPass；不改published API／schema／模組集合或新增SELL legs。觀察狀態的arming fingerprint與執行gate分離，保留consumer／原高水位歷史，US calculator完全不改。直接端點與投影不得以新bundle承接舊PASS繞過保護。
- [x] 549.3 凍結後讀回完整bundle、model／manifest／Swagger hashes；逐位元同步backend decision model／manifest，以原canonical targets projection核對formal document，以原Ruby完整stdout重產insert-only SQL，不UPDATE／DELETE歷史row。顯式指定同一SRPP root，以原Pythongenerator重產same-input及long-short-exit golden；新增00919案例由來源原工具產生expected，不手改金融數字。其他案例未涉及的金融輸出、targets、formula manifest及Swagger維持；本輪必須變動的calculator source hashes逐項說明。
- [x] 549.4 meaningful回歸含四值逐項=50／<50／>50、缺漏／非數字、既有低檔／反彈反向、原D174其他條件仍阻擋、其他代號與US維持，以及歷史觀察保存。補Java/Python同輸入golden並跑既有authority／registry generator regression及聚焦Java測試；離線package建立可重現artifact。spec-check BLOCK0、diff-check與另一subagent依完整diff做arch審查；不把歷史離線回放當今日即時資格。
- [x] 549.5 正式鏈依使用者既有完整發布授權及本輪 `srpp-policy-release` 技能發布順序步驟3至5：兩repo各自commit／no-ff merge／push及remote-ref readback後才SQL，再由乾淨main部署。這明示取代Task504任務舊段feature先serve／main重建後才SQL的不同順序，並非Task504原文。此前只做離線build及測試，不替換正式容器；雙repo落地後自main受追蹤SQL單次insert，精確讀回policy_bundle_sha256／formula_version／policy_document／formula_manifest全文及既有rows不變。登錄後立即以新bundle+原Swagger從SRPP host唯讀GET report-calculations，保留真實HTTP/body，409／503不能當已接受。
- [x] 549.6 從乾淨asset main及正式.env／mount僅build及force-recreate必要business-services。部署前唯讀核對原startup當日analysis／index／到時匯出／備份已完成、配息補抓停用、PROCESSING=0及snapshot／到期在途款不需補跑；避免已啟用工作分鐘，不改設定／排程。保留其他容器/BFF，health最多120秒、safeGET最多30秒，不例行重啟BFF。讀回JAR model／manifest／Swagger／registration SQL／formal document與已合併bytes，重核新hash業務API。來源、部署、registry、HTTP各保留證明範圍；正式日報採用須原合法入口／required_reads／直接L0／verifier，其他只記診斷或NOT_YET_EXECUTED。不觸發報告、observer、producer、refresh、寄信或交易。


D-208凍結契約：設定位於 `execution_policy.core_sell_guard_d174.low_point_sell_protection_d208`，decision=`D-208`、enabled=true、symbols=[00919]、market=台股、minimum_kd_exclusive=50；其他低檔數值為rsi5_at_or_below=30、wr9_at_or_above=80、bias10_at_or_below=-8、week52_position_at_or_below=0.15。blocked_radar_states固定為BUY_CANDIDATE／ADD_CANDIDATE／LOW／LOW_WATCH／OVERSOLD／OVERSOLD_WATCH／REBOUND_WATCH／ENTRY_WATCH。任一數值低檔條件、股票本身timingState／counterTrendState／action／candidateAction命中集合，或timingLabel／counterTrendLabel／reasons出現「低檔」「超賣」「反彈觀察」均封鎖；不是搜尋全市場敘述。KD／RSI／WR域0..100、position域0..1、BIAS有限，數值缺漏／型別不合法皆不可驗證。Java使用source同欄位，有效低檔值回reason=`D208_LOW_POINT_SELL_BLOCKED`及timingPass=false。D208新增設定／附加技術證據缺口沿既有局部Gap(reason=`D208_LOW_POINT_EVIDENCE_UNVERIFIED`)處理；原KD／數值／來源驗證失敗保留原具名Gap。`calculateCore` catchGap只記module missing／UNAVAILABLE，該檔不輸出timingPass fact或PASS receipt，不能宣稱已回false JSON。這與Python timing_pass=false在「不得執行」語意等價，golden分別驗有效值的timingPass／reason parity及缺值的Java Gap／module無PASS、Pythonfalse，兩者不是JSON逐值相等。不新增envelope欄位。四值中任一=50仍封鎖，值全部>50尚需其他低檔及原D174條件。

保留觀察fingerprint：Python已有四欄decision／scope／arming／non_distributing_symbols穩定指紋；Java台股原本驗整份policy的歷史相容雜湊，加入執行欄位不能讓舊state全部失效。Java只接受同四欄穩定指紋、當前完整policy精確指紋，以及去除這個新執行欄位後的原完整policy精確指紋；不得接受任意字串或只憑symbol放行。測試保留舊高水位與scope／arming遭竄改仍拒絕；不改寫來源state。

公式身分：registry的ASSET_MGMT_SRPP_V2 manifest僅描述assets／allocation／cashIncome／funding／completedTechnicals，沒有D174 scope，本輪不改該五項公式。報告十二模組共同的SRPP_REPORT_CALCULATIONS_V1為既有計算族／wire契約身分，保持以免擴大到無關scope及schema；D208實際語意變更由新bundle、完整modelInputsSha256／policyManifestSha256、CORE_SELL_GUARD既有canonical scope內的具名D208 receipt reason及derived同輸入golden綁定，不冒稱00919金融輸出不變。


00919 scope相容性修復：`CORE_SELL_GUARD.scope` 必須嚴格維持唯一值陣列 `["D174_CONSUMER_ISOLATED_HIGH_WATER_AND_KD"]`，不得因00919而追加D208標記或放寬原SRPP consumer。來源 `verify_srpp_report_calculations.py` 的 `SCOPES`、`adoptable_fact` 與package scope_ok皆採完整陣列相等；追加第二值會使同模組既有事實無法採用。D208語意已由同一9bea48401bf05c360b05fe83272a72a247680fd228e47eaaee059cb2865b5603 bundle／model bytes、57casegolden及D208 receipt reason綁定，不需要新scope。本次只移除資產端額外scope append並修正Java exact-list斷言；不改SRPP verifier、model、manifest、SQL、57casegolden或金融門檻。

新增 `scripts/tests/srpp-core-scope-consumer-test.py` 作跨語言field-adoption契約回歸：Java現有public calculateCore合成測試可在明示 `-Dsrpp.core.scope.exportDir=<暫存目錄>` 時輸出其真實模組JSON；平常不寫檔。Python測試以必要的 `SRPP_RELEASE_WORKTREE` 與 `SRPP_CORE_SCOPE_FIXTURE` 顯式指定唯讀來源與此輸出，核對verifier bytes等於來源manifest列管hash後，直接呼叫原verifier的SCOPES／adoptable_fact。以明示合成VERIFIED sourceVector和持股binding證明canonical scope的代表highWater與timingPass=0事實可採用；額外scope及來源缺失維持拒絕。這只是離線欄位契約測試，不宣稱完整API envelope／L0／正式採用驗收。原57case金融輸出、00919低檔false與其餘標的／US不變，重新跑focused Java、authority與package並由另一agent審完整diff；重新落地asset main後才部署，不再新增registry row或修改已登錄內容。

## 驗證

```bash
bash scripts/spec-check.sh
python3 -B scripts/check-srpp-report-authority.py --srpp-root /Users/steven/.codex/worktrees/00919-low-sell-guard/SRPP --check
python3 -B scripts/generate-srpp-report-golden.py --srpp-root /Users/steven/.codex/worktrees/00919-low-sell-guard/SRPP --check
python3 -B scripts/generate-srpp-long-short-exit-golden.py --srpp-root /Users/steven/.codex/worktrees/00919-low-sell-guard/SRPP --check
ruby scripts/tests/srpp-policy-register-test.rb
ruby scripts/tests/srpp-report-policy-register-test.rb
SRPP_RELEASE_WORKTREE=/Users/steven/.codex/worktrees/00919-low-sell-guard/SRPP python3 -B scripts/tests/check-srpp-report-authority-test.py
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=SrppReportModuleCalculatorTest,DecisionPolicyAuthorityTest,DailyDecisionCalculatorTest,SrppReportShortUsCalculatorTest -Dsrpp.core.scope.exportDir=/private/tmp/00919-low-sell-authority-20261105/scope-r3 test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DskipTests package
ruby scripts/render-9090-openapi-docs.rb --check --srpp-root /Users/steven/.codex/worktrees/00919-low-sell-guard/SRPP
python3 -B scripts/sync-srpp-swagger-identity.py --check --srpp-root /Users/steven/.codex/worktrees/00919-low-sell-guard/SRPP
SRPP_RELEASE_WORKTREE=/Users/steven/.codex/worktrees/00919-low-sell-guard/SRPP SRPP_CORE_SCOPE_FIXTURE=/private/tmp/00919-low-sell-authority-20261105/scope-r3/core-scope-consumer.json python3 -B scripts/tests/srpp-core-scope-consumer-test.py
git diff --check
```

正式serve驗收在雙repo落地及registry後，由run-stack從main執行 `docker compose -p asset-management build business-services` 與 `docker compose -p asset-management up -d --no-deps --force-recreate business-services`；先驗真實Compose project是asset-management及main正式設定，不從feature另建stack。JAR全文、registry四欄、精確新hash HTTP及原設定／其他容器逐項保留。

## 完成報告

2026-11-05（Asia/Taipei），截至10:58:25的實際收據，00919 D-208低檔減碼保護已完成跨專案落地、insert-only登錄、正式服務部署及兩consumer精確hash業務API讀回。這是執行保護的發布驗收，不保證辨識最低點或提高報酬；**正式日報採用仍為 NOT_YET_EXECUTED**。

- **最終來源與身分。** SRPP政策D-208／v2.57.0／月頻模型v3.22.0；bundle `9bea48401bf05c360b05fe83272a72a247680fd228e47eaaee059cb2865b5603`，model原始bytes SHA `ba24042320c18af57aef44b34b7590e8a9eb26a6d591ac5cff75f87bd21a0ffb`，manifest原始bytes SHA `7578ca9eef049bfbb5fcb2f50108575ce318e4ebb00c0459fd20b03f510d7244`。Swagger維持 `a7ce2c890c437199334b98fb914626ac955d98fbe729ec6c9a498564d52550b4`。SRPP main／remote為 `f8e085647f2dc8cd4027e21f9758561fb1380f13`；資產端正式部署的程式碼main／remote為 `6b2619901414e923d4e5221c2dc0a974407fc284`。兩專案均按feature commit、main no-ff merge、push及精確remote-ref讀回完成，SRPP實際排程工作區亦已安全同步。
- **保護與相容性。** 原觀察起日／高水位保留；00919最近完成日與當輪即時日K／D四值均須>50且沒有其他低檔反證，才可能續行原D174條件。非法container狀態值不能由Java的字串轉換變成可採用。發布前修正00919額外scope標記與原consumer不相容的問題，public模組保留唯一canonical D174 scope，D208由原receipt reason及同bundle綁定；未放寬SRPP verifier。具名必要退出獨立路徑、其他代號、US及買進不變。
- **離線驗收與獨立審查。** SRPP 168項Python與100項Node檢查通過。資產端49項Java、3項原Python consumer欄位採用契約、57個同輸入golden、13項Ruby registry測試（307 assertions）、4項authority負向回歸及5項long-short來源／唯讀generator回歸通過。原22個golden金融expected及15項長短線退出案例不變，新35項00919案例由原Python工具產生；formal targets、registry formula manifest及Swagger原bytes維持。spec機械檢查BLOCK0／CHECK0、差異檢查與作者之外的獨立spec／架構審查完成；型別與scope相容性finding均修復並重驗。3項consumer測試使用Java真實public計算結果與明示合成binding，只證明欄位契約，不冒稱完整API或金融採用。
- **正式登錄。** 10:42:55–10:42:56以已合併SQL新增同bundle唯一row，registry由31列成為32列；舊31列canonical全文維持。四欄policy_bundle_sha256／formula_version／policy_document／formula_manifest精確相符，formula為ASSET_MGMT_SRPP_V2；後續scope-only修復未重送SQL或改寫該row。登錄後立即的409及部署後背景context尚未就緒的503均保留原回應，沒有用健康檢查替代業務API驗收。
- **部署與runtime bytes。** 10:53:34開始從乾淨asset main及原正式設定只重建business-services；10:54:26新container啟動，10:54:37 healthy，BFF於10:55:37安全GET恢復且未重啟。新container `882469eb992d80c6e36f1e4ea998dcabc932ab9e6f64e7b04e2a6265ff223ced`，image `sha256:32ebfa014873cbc4481873113fea37c9c83f11407cebf3b1e655facbef91a309`；部署JAR SHA `8d54cf658001eb3665adecc69162fcbfc59257d78b82d23852efe61d158906b8`。五份authority resource逐位元符合已合併來源，實際calculator class SHA `aacc0618796731d59bc9e1e8475e9a3f6b997dd2ed7a328abaa574ab6c34ea4c`與離線class相同；2208個JAR解壓entry全部bytes相同，整份JAR hash差別屬ZIP容器metadata，沒有冒稱整包原始bytes相同。部署前startup唯讀檢核通過；原.env／有效environment／mount／network／healthcheck／restart policy／ports保持，其他8個container及image不變，未因本次operator行為補做報告、草稿、寄信、外部刷新或交易。
- **精確hash業務API。** 保留原背景排程自然發布行為，未人工觸發producer；確認自然發布後於10:58:23（Codex）及10:58:25（Claude）以同新bundle／Swagger／當日09:05身分唯讀GET，兩者HTTP200、schema及hash-chain診斷驗證PASS，各為7 PARTIAL／5 UNAVAILABLE／0 COMPLETE，tradeAuthorization=false、places_orders=false。此次未執行完整required_reads及獨立直接L0，因此 `DIAGNOSTIC_SCHEMA_AND_HASH_CHAIN_ONLY` 不等於正式報告採用、交易資格或下單授權。一般9090日曆／TWSE／報價連線亦PASS，各只證明本身範圍。

主要本機驗收收據位於 `/Users/steven/Project/SRPP/data/output/diagnostics/2026-11-05-00919-low-sell-release/`：`registry-verification.json`、`deployment-verification.json`、`postdeploy-confirmed-publication-business-readback.json`，以及 `asset/r3/` 的離線與原consumer回歸。原HTTP回應僅存本機私人診斷目錄；環境僅記錄雜湊與相等判定，不納入秘密原文。

本段與549.1–549.6勾選只回填已完成工作的實證，沒有修改功能契約、程式碼、資源或SRPP來源。依CLAUDE.md對「只是回填任務完成報告」的明文例外處理；歷次獨立審查仍各自證明原契約／程式碼，不以本段冒稱重新審過新功能。後續task-only文件commit使main HEAD前進時，部署程式碼身分仍以上述6b261990為準；runtime bytes沒有變動，不為文件收尾重算bundle或重建映像，也不預填尚未產生的文件commit SHA。
