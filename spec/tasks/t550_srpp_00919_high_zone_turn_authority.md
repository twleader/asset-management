# [t550] 發布00919高檔D值與K轉弱減碼保護

**對應 Requirements:** Requirements 187／193（不可變報告計算與精確authority發布）
**前置任務:** t549（D208完整發布）；SRPP D209來源獨立審查／凍結
**Liquibase changeset:** 無

## 背景

使用者在D208已完整發布後進一步確認：00919一般減碼應在日D仍位於80以上的高檔區且K已確認轉弱時進行，K可以已跌破80，不要把K>=80誤加為條件。最近完成日日D及同輪即時日D皆採>=80inclusive。原D208四KD>50、其他低檔反證及D174原擇時條件仍保留。資產端提供台股timingPass事實，必須與新來源同輸入一致，不只換hash。

## 要做什麼

- [x] 550.1 唯一來源 `/Users/steven/.codex/worktrees/00919-high-zone-turn/SRPP` 經獨立審查及policy/runtime檢核後的D-209／政策v2.58.0／模型v3.22.0最終原bytes。先等root給凍結hash才同步，不沿用D208舊9bea或中間hash。新增core_sell_guard_d174.high_zone_reversal_d209：decision=D-209、enabled=true、symbols=[00919]、market=台股、minimum_d_inclusive=80、k_may_be_below_80=true、requires_existing_d174_confirmation=true，scope／validation為非空描述；原low_point_sell_protection_d208物件不改。
- [x] 550.2 Java台股coreTiming依原價格觀察／完成KD驗證→D208→D209→原D174確認順序執行。最近完成日原始日D及即時精確雷達dValue均須有效0..100且>=80；K可<80但原四KD>50、RSI／WR／BIAS／年位置及雷達低檔／超賣／反彈反證仍擋。完成K78D82／即時K75D81且原條件全通過應PASS，任一D79.99阻擋、80恰好允許。原K連降、最新K<D／D下降、下降日在高點後、即時K<D／即時K>=D的回復不賣、回落>=1.5%、方向及其他必要條件不放寬。D209_HIGH_ZONE_SELL_BLOCKED為有效阻擋false；新增設定／證據缺口D209_HIGH_ZONE_EVIDENCE_UNVERIFIED沿既有局部Gap→module missing／UNAVAILABLE，無該筆PASS／timingPass fact；原早期資料錯誤保留原Gap。不增client緊急繞過旗標，不動其他代號、US、買進及原具名必要退出獨立路徑，不保證高點或績效。
- [x] 550.3 Java保留watch四欄穩定hash、當前完整policyhash、只移除high_zone_reversal_d209的先前D208fullhash、再移除low_point_sell_protection_d208的pre-D208fullhash；拒絕任意hash及保留D209卻去掉D208的未發布組合。保留舊觀察起日／高水位／consumer隔離，不改寫原state。published CORE_SELL_GUARD.scope精確單值canonical D174陣列，不增D209 scope；新語意以新bundle／modelhash與既有receipt shape內D209 reason綁定。保留原Pythonverifier，不放寬SCOPES／adoptable_fact。
- [x] 550.4 原Pythongenerator新增完成D／即時D的79.99／80／80.01邊界及K<80高檔正例、即時K>=D／完成日K回升／原轉弱不成立及缺值反例；Java逐值parity／合理Gap不可執行語意覆蓋，嚴格checker案例數跟著原generator精確輸出更新，不改expected洗過。保留舊57case，逐項解釋其中00919因新D門檻改變的expected，不宣稱舊57金融輸出全不變；原其他22case及15longshort退出金融輸出、targets、formula manifest與Swagger保持。延續Java實際publicmodule→原Pythonadoptable_fact的3項代表欄位正反契約測試，明示合成binding不是完整包或金融採用；原consumer對額外scope、未驗證依賴仍拒絕。先spec-check及作者以外的spec審查記錄後才寫backend，實作後獨立arch審完整diff、修復後跑focusedJava／authority／Ruby／generator／package及JAR bytes檢核。
- [x] 550.5 root確認來源凍結後逐位元同步model／manifest，以原targets projection核正式document，以原Ruby stdout產追蹤insert-only SQL及原Python產同源golden。ASSET_MGMT_SRPP_V2與report族SRPP_REPORT_CALCULATIONS_V1不變，無API／schema／Swagger／producer／排程變動。雙repo各feature commit→main no-ff merge→push／remote-ref讀回後才執行已合併SQL；新hash只新增row並核四欄全文、原32列保持，不UPDATE／DELETE。登錄後立即從SRPP host以新bundle與原Swagger唯讀GET業務API，保留409／503等原回應，不稱為成功採用。來源再變須撤銷舊衍生驗收重產。
- [x] 550.6 從乾淨asset main正式設定僅business-services build及force-recreate，採t549已接受與現行srpp-policy-release步驟的正式鏈，不冒稱Task504歷史順序。部署前唯讀重核startup相關analysis／index／到時匯出／備份／快照／在途款無需補跑、配息補抓停用及無PROCESSING／活動正式報告；避開既有工作，不改設定。保留其他8個容器與BFF，health最多120秒／safeGET最多30秒，不例行重啟BFF。核對新image／container、5份JAR authority＋changed class與離線產物、原env／mount／network設定及精確新hash業務API；只保存env雜湊／相等判定，不存秘密。原背景自然tick照常，不人工producer／報告／Gmail／刷新或交易。正式採用須原合法入口／完整required_reads／直接L0／verifier，維護診斷不得取代。

## 驗證

```bash
bash scripts/spec-check.sh
python3 -B scripts/check-srpp-report-authority.py --srpp-root /Users/steven/.codex/worktrees/00919-high-zone-turn/SRPP --check
python3 -B scripts/generate-srpp-report-golden.py --srpp-root /Users/steven/.codex/worktrees/00919-high-zone-turn/SRPP --check
python3 -B scripts/generate-srpp-long-short-exit-golden.py --srpp-root /Users/steven/.codex/worktrees/00919-high-zone-turn/SRPP --check
ruby scripts/tests/srpp-policy-register-test.rb
ruby scripts/tests/srpp-report-policy-register-test.rb
SRPP_RELEASE_WORKTREE=/Users/steven/.codex/worktrees/00919-high-zone-turn/SRPP python3 -B scripts/tests/check-srpp-report-authority-test.py
SRPP_RELEASE_WORKTREE=/Users/steven/.codex/worktrees/00919-high-zone-turn/SRPP python3 -B scripts/tests/generate-srpp-long-short-exit-golden-test.py
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=SrppReportModuleCalculatorTest,DecisionPolicyAuthorityTest,DailyDecisionCalculatorTest,SrppReportShortUsCalculatorTest,UsCoreSellCalculatorTest -Dsrpp.core.scope.exportDir=/private/tmp/00919-high-zone-turn-authority-20261105/scope test
SRPP_RELEASE_WORKTREE=/Users/steven/.codex/worktrees/00919-high-zone-turn/SRPP SRPP_CORE_SCOPE_FIXTURE=/private/tmp/00919-high-zone-turn-authority-20261105/scope/core-scope-consumer.json python3 -B scripts/tests/srpp-core-scope-consumer-test.py
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DskipTests package
ruby scripts/render-9090-openapi-docs.rb --check --srpp-root /Users/steven/.codex/worktrees/00919-high-zone-turn/SRPP
python3 -B scripts/sync-srpp-swagger-identity.py --check --srpp-root /Users/steven/.codex/worktrees/00919-high-zone-turn/SRPP
git diff --check
```

正式serve只在雙repo落地及registry後從確認為main的正式目錄執行 `docker compose -p asset-management build business-services` 及 `docker compose -p asset-management up -d --no-deps --force-recreate business-services`；先驗實際Compose project及正式.env／掛載，不能從feature另建stack。DB schema未變，不重產schema.sql。

## 完成報告

2026-11-05（Asia/Taipei），截至11:42:11的實際收據，D209已完成雙專案落地、insert-only登錄、正式服務部署及Codex／Claude精確新hash業務API讀回。新增門檻只限00919一般減碼；D值高檔不是獨立賣出訊號，保留D208及原D174全部條件，不保證預測高點或績效。**正式日報採用仍為 NOT_YET_EXECUTED**。

- **最終規則身分與Git落地。** D-209／政策v2.58.0／模型v3.22.0，bundle `930d076f49ef25d495b4a355e95754e85bd8be4dfa39b2c4490c298f3f10d707`；model bytes SHA `89e31c563f66d481ba924e414ec772da7551f4eae1e996f1cb78cd8b99e476c3`，manifest bytes SHA `4dbd9f35f09f9ce3642b73a4a3d2ce8c77d0e7a818cd57c3d852869572b920b3`，Swagger仍 `a7ce2c890c437199334b98fb914626ac955d98fbe729ec6c9a498564d52550b4`。SRPP feature `3b33ef4c767a2602b196db881cef31ce21a007f1`、main／remote `eafcf8a2705b1786be4dd8793bb9494841b8104e`；asset feature `95da36909b9f4306b1103573de6c286d17ce2064`、正式部署程式碼main／remote `3dcb1e9d31cf6202533efe5d1d4c4135588b6d6c`。兩repo均完成no-ff merge／push／精確remote-ref讀回，SRPP實際工作區已同步；未借用其他未合併來源。
- **規則與歷史相容性。** 最近完成日D及同輪即時日D皆>=80（含80），K可低於80但四KD仍須>50；完成K78D82／即時K75D81的合成正例可通過新增高區門檻，K>=D／完成K回升／回落不足等原條件仍等待。原D208設定完整保留，移除D209新鍵後完整core policy與上一版D208深度相等；穩定指紋／當前full／prior D208 full／pre-D208 full四類精確相容，未發布的缺D208卻保留D209組合拒絕，原state不被重設。API仍為唯一canonical D174 scope，無新D209 scope標記；原consumer沒有放寬。
- **測試與金融差異。** SRPP 277項回歸（177 Python／100 Node）、asset 51項Java、3項原Pythonconsumer欄位契約、73個同輸入golden、13項Ruby registry（307 assertions）、4項authority負向回歸及5項long-short來源／唯讀generator回歸通過。73案=保留57案＋16個D209新案；原57案市場／觀察輸入逐值維持，僅明確排除37筆合法前進的state.policy_sha256身分。只有5筆受保護00919原PASS依新增D門檻轉為D209 BLOCK，逐案before／after留存；不宣稱全57案expected不變。其餘22案expected、15項long-short退出案例、targets、formal document與registry formula維持。原Java public模組→原Pythonadoptable_fact測試僅用明示合成binding驗欄位契約，不冒充完整API／直接L0／正式採用。spec-check BLOCK0／CHECK0、差異檢查及作者之外的獨立spec／架構審查均完成，最後實作無剩餘finding。
- **正式登錄。** 11:36:19由已合併SQL新增唯一新bundle row，registry由32列成為33列；舊32列canonical全文維持。policy_bundle_sha256／formula_version／policy_document／formula_manifest四欄精確相符，ASSET_MGMT_SRPP_V2及report族SRPP_REPORT_CALCULATIONS_V1維持，沒有UPDATE／DELETE歷史資料。登錄後立即的409與部署後首次context未就緒503均保存原回應，不把失敗當成業務接受，也沒有人工觸發producer解封。
- **正式部署與bytes。** 11:38:04開始只recreate business-services，11:38:19 healthy；已從乾淨asset main及原正式設定完成build／recreate。新container `8502b375cb8fdd1e85686fb3d30a4e8d03eb0c9c85db5470f833426441adfc98`，image `sha256:ce3093adfad81e79fd97a4116ee6588696d3e006afada6115b321f59d80b7206`，部署JAR SHA `23f4cdac6efe5ed531c262944bc6045e838c8d48a23e9618a1fa3c693fe1e83f`。實際calculator class SHA `f9875128f643e2660c873a315718cf036460c883f1cc6bc0576fd253269725fd`與核准離線class一致；5份authority resource等於main／離線原bytes，2208個JAR解壓entry逐位元一致。部署JAR與離線整包hash因ZIP metadata不同，不宣稱整包raw bytes相同。46項有效環境設定、3個完整mount物件與network／healthcheck／restart／ports保持；第一次mount陣列比較只因順序不同失敗，保留該收據，再按Destination排序確認完整物件相同，沒有更換掛載。其他8個容器及image不變，BFF沒有例行重啟；startup唯讀檢核與安全GET各按實際結果驗證。
- **精確hash業務讀回與界線。** 保留原背景自然排程；11:41:40.87186自然發布兩consumer各一包後，於11:42:09–11:42:10（Codex）及11:42:11（Claude）以新930d完整bundle與原Swagger唯讀GET，兩者HTTP200且strict schema／content／input／source hash-chain及authority檢核PASS，各為0 COMPLETE／7 PARTIAL／5 UNAVAILABLE，tradeAuthorization=false。這次是 `DIAGNOSTIC_SCHEMA_AND_HASH_CHAIN_ONLY`；`independent_direct_l0=NOT_PERFORMED`，未執行完整正式日報required_reads，因此正式採用仍NOT_YET_EXECUTED，不授權交易。沒有人工producer、補做報告、草稿、郵件、行情刷新或下單；一般9090日曆／TWSE／報價連線PASS只證明連線本身。

本機收據位於 `/Users/steven/Project/SRPP/data/output/diagnostics/2026-11-05-00919-high-zone-turn-release/`，包含 `registry-verification.json`、`deployment-verification.json`、`postdeploy-confirmed-publication-business-readback.json`及`asset/`中的金融parity／離線測試。原HTTP回應只存本機私人診斷目錄；環境只記錄雜湊與相等判定，不納秘密原文。

本段及550.1–550.6勾選只回填已完成工作的實證，沒有修改功能契約、程式碼、resources或SRPP來源。依CLAUDE.md「只是回填任務完成報告」的明文例外處理，不把本段稱作新功能審查；先前獨立審查仍各證明原契約／程式碼。後續task-only文件提交使main HEAD前進時，已部署程式碼仍以上述3dcb1e9d為準，runtime bytes維持，不為文件收尾重算bundle／SQL、重建映像或重啟，也不預填未來文件commit SHA。
