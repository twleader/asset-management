# [t550] 發布00919高檔D值與K轉弱減碼保護

**對應 Requirements:** Requirements 187／193（不可變報告計算與精確authority發布）
**前置任務:** t549（D208完整發布）；SRPP D209來源獨立審查／凍結
**Liquibase changeset:** 無

## 背景

使用者在D208已完整發布後進一步確認：00919一般減碼應在日D仍位於80以上的高檔區且K已確認轉弱時進行，K可以已跌破80，不要把K>=80誤加為條件。最近完成日日D及同輪即時日D皆採>=80inclusive。原D208四KD>50、其他低檔反證及D174原擇時條件仍保留。資產端提供台股timingPass事實，必須與新來源同輸入一致，不只換hash。

## 要做什麼

- [ ] 550.1 唯一來源 `/Users/steven/.codex/worktrees/00919-high-zone-turn/SRPP` 經獨立審查及policy/runtime檢核後的D-209／政策v2.58.0／模型v3.22.0最終原bytes。先等root給凍結hash才同步，不沿用D208舊9bea或中間hash。新增core_sell_guard_d174.high_zone_reversal_d209：decision=D-209、enabled=true、symbols=[00919]、market=台股、minimum_d_inclusive=80、k_may_be_below_80=true、requires_existing_d174_confirmation=true，scope／validation為非空描述；原low_point_sell_protection_d208物件不改。
- [ ] 550.2 Java台股coreTiming依原價格觀察／完成KD驗證→D208→D209→原D174確認順序執行。最近完成日原始日D及即時精確雷達dValue均須有效0..100且>=80；K可<80但原四KD>50、RSI／WR／BIAS／年位置及雷達低檔／超賣／反彈反證仍擋。完成K78D82／即時K75D81且原條件全通過應PASS，任一D79.99阻擋、80恰好允許。原K連降、最新K<D／D下降、下降日在高點後、即時K<D／即時K>=D的回復不賣、回落>=1.5%、方向及其他必要條件不放寬。D209_HIGH_ZONE_SELL_BLOCKED為有效阻擋false；新增設定／證據缺口D209_HIGH_ZONE_EVIDENCE_UNVERIFIED沿既有局部Gap→module missing／UNAVAILABLE，無該筆PASS／timingPass fact；原早期資料錯誤保留原Gap。不增client緊急繞過旗標，不動其他代號、US、買進及原具名必要退出獨立路徑，不保證高點或績效。
- [ ] 550.3 Java保留watch四欄穩定hash、當前完整policyhash、只移除high_zone_reversal_d209的先前D208fullhash、再移除low_point_sell_protection_d208的pre-D208fullhash；拒絕任意hash及保留D209卻去掉D208的未發布組合。保留舊觀察起日／高水位／consumer隔離，不改寫原state。published CORE_SELL_GUARD.scope精確單值canonical D174陣列，不增D209 scope；新語意以新bundle／modelhash與既有receipt shape內D209 reason綁定。保留原Pythonverifier，不放寬SCOPES／adoptable_fact。
- [ ] 550.4 原Pythongenerator新增完成D／即時D的79.99／80／80.01邊界及K<80高檔正例、即時K>=D／完成日K回升／原轉弱不成立及缺值反例；Java逐值parity／合理Gap不可執行語意覆蓋，嚴格checker案例數跟著原generator精確輸出更新，不改expected洗過。保留舊57case，逐項解釋其中00919因新D門檻改變的expected，不宣稱舊57金融輸出全不變；原其他22case及15longshort退出金融輸出、targets、formula manifest與Swagger保持。延續Java實際publicmodule→原Pythonadoptable_fact的3項代表欄位正反契約測試，明示合成binding不是完整包或金融採用；原consumer對額外scope、未驗證依賴仍拒絕。先spec-check及作者以外的spec審查記錄後才寫backend，實作後獨立arch審完整diff、修復後跑focusedJava／authority／Ruby／generator／package及JAR bytes檢核。
- [ ] 550.5 root確認來源凍結後逐位元同步model／manifest，以原targets projection核正式document，以原Ruby stdout產追蹤insert-only SQL及原Python產同源golden。ASSET_MGMT_SRPP_V2與report族SRPP_REPORT_CALCULATIONS_V1不變，無API／schema／Swagger／producer／排程變動。雙repo各feature commit→main no-ff merge→push／remote-ref讀回後才執行已合併SQL；新hash只新增row並核四欄全文、原32列保持，不UPDATE／DELETE。登錄後立即從SRPP host以新bundle與原Swagger唯讀GET業務API，保留409／503等原回應，不稱為成功採用。來源再變須撤銷舊衍生驗收重產。
- [ ] 550.6 從乾淨asset main正式設定僅business-services build及force-recreate，採t549已接受與現行srpp-policy-release步驟的正式鏈，不冒稱Task504歷史順序。部署前唯讀重核startup相關analysis／index／到時匯出／備份／快照／在途款無需補跑、配息補抓停用及無PROCESSING／活動正式報告；避開既有工作，不改設定。保留其他8個容器與BFF，health最多120秒／safeGET最多30秒，不例行重啟BFF。核對新image／container、5份JAR authority＋changed class與離線產物、原env／mount／network設定及精確新hash業務API；只保存env雜湊／相等判定，不存秘密。原背景自然tick照常，不人工producer／報告／Gmail／刷新或交易。正式採用須原合法入口／完整required_reads／直接L0／verifier，維護診斷不得取代。

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

待來源凍結、獨立審查與實際發布收據完成後回填；未執行項目不提前宣稱完成。
