# [t562] 國安基金政策訊號解讀與精確authority發布

**對應 Requirements:** Requirement 187（唯讀SRPP報告計算之正式政策來源與相同輸入驗證）
**前置任務:** t561
**Liquibase changeset:** 無

## 背景

使用者明確要求完整發布國安基金解讀修正：政府正式公告／授權啟動安定市場任務，即構成重要政策支持及將視情況擇機護盤的訊號，不需要先公開實際成交或金額。未有公開可驗證成交證據時，實際進場時點、成交與金額仍未知，不能把授權寫成已成交；不固定分數、不保證底部、不推定選舉因果。這次只改SRPP政策證據解讀文字，金融門檻與計算完全保留。

## 要做什麼

- [x] 562.1 先spec-check及作者以外的唯讀spec審查，記錄通過後才同步backend。來源只用root凍結的 `/private/tmp/srpp-nsf-interpretation-20261107`。D210／policy2.59.0／model3.22.0維持；model_inputs、原calculator、formula manifest、formal targets及Swagger bytes必須不變，不新增分數常數或交易權限／硬閘門例外。
- [x] 562.2 同步凍結 `assumptions/policy_manifest.json` 原始bytes至backend決策resource，model同值就不改。原Ruby stdout產受追蹤d201-registration.sql，原Python重產report-same-input-golden與report-long-short-exit-golden；除bundle identity外全文／金融expected／calculator hashes維持，不手改expected、不更動generator。formal policy document原targets投影同值保持。作者以外的arch審查完整diff，不新增Java／API／DTO／DB／排程。
- [x] 562.3 authority gate、兩份golden checks、Ruby registry、authority／long-short generator負向回歸、focused Java與backend package通過；保存本輪新的source／artifact hashes及實際輸出，不能沿用上輪容器或收據作新proof。
- [x] 562.4 使用者明示順序為離線驗收→雙repo各feature commit／main no-ff merge／push及精確remote讀回→已合併SQL insert-only registry。精確核新hash的policy_bundle_sha256／formula_version／policy_document／formula_manifest四欄全文，所有舊rows保持；不UPDATE／DELETE歷史資料。
- [x] 562.5 由委派run-stack從乾淨正式asset main與原.env／mount／network設定，只build／force-recreate business-services；先核實真Compose project與活動報告／啟動工作，保留其他容器與BFF、原設定及有界health／safeGET流程。驗運行JAR五份authority與合併來源全文一致、計算器class保留，從SRPP host以新hash作唯讀業務GET並保存實際HTTP／原回應；不在時窗外冒稱成功採用，正式日報採用仍須合法入口及完整required_reads／同owner L0／verifier。無人工producer、刷新、報告、Gmail或交易。

## 驗證

```bash
bash scripts/spec-check.sh
python3 -B scripts/check-srpp-report-authority.py --srpp-root /private/tmp/srpp-nsf-interpretation-20261107 --check
python3 -B scripts/generate-srpp-report-golden.py --srpp-root /private/tmp/srpp-nsf-interpretation-20261107 --check
python3 -B scripts/generate-srpp-long-short-exit-golden.py --srpp-root /private/tmp/srpp-nsf-interpretation-20261107 --check
ruby scripts/tests/srpp-policy-register-test.rb
ruby scripts/tests/srpp-report-policy-register-test.rb
SRPP_RELEASE_WORKTREE=/private/tmp/srpp-nsf-interpretation-20261107 python3 -B scripts/tests/check-srpp-report-authority-test.py
SRPP_RELEASE_WORKTREE=/private/tmp/srpp-nsf-interpretation-20261107 python3 -B scripts/tests/generate-srpp-long-short-exit-golden-test.py
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=SrppReportModuleCalculatorTest,DecisionPolicyAuthorityTest,DailyDecisionCalculatorTest,SrppReportShortUsCalculatorTest,UsCoreSellCalculatorTest test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DskipTests package
git diff --check
```

雙repo落地及registry後，僅從正式main使用 `docker compose -p asset-management build business-services` 與 `docker compose -p asset-management up -d --no-deps --force-recreate business-services`；真project先由現行container labels確認。無DB schema變動，不重產schema.sql。收據放 `/Users/steven/Project/SRPP/data/output/diagnostics/2026-11-07-nsf-interpretation-release/`，不存秘密原文。

## 完成報告

2026-11-07（Asia/Taipei）完成本輪政策解讀文字澄清、雙repo落地、insert-only登錄與正式部署。正式公告／授權啟動安定任務已可認列為重要政策支持及將伺機護盤的訊號，不要求公開成交明細才承認訊號；實際操作數字仍按證據分開記錄為已知／未知。沒有固定分數、底部保證、選舉因果、新交易權限或硬閘門例外。**正式日報採用仍為 NOT_YET_EXECUTED；本輪業務GET為非交易日診斷，api_accepted=false。**

- **範圍與身分。** 維持D210／policy2.59.0／model3.22.0，新bundle `2da98a8706843024ab25f01aa3b8145d64620898d00a1690779b8ff0dcfbd708`。SRPP僅五份prose與原工具重產runtime／manifest；model、calculator、formal targets、formula manifest及Swagger全文不變。Asset僅embedded manifest、原Ruby產SQL、兩份原Python生成goldens身分與SDD文件更新；不動Java、API、DTO、DB schema、排程或設定。
- **審查與離線驗收。** 獨立spec／SRPP完整diff及arch審查均critical0／major0／minor0，spec-check為BLOCK0／CHECK0。54項Java、13項Ruby（307 assertions）、4項authority負向、5項long-short來源回歸與backend package通過；73／15goldens除bundle外全文相同，原金融expected及calculator hashes未變。SRPP另通過80項shared／runtime／political回歸與各consumer／runtime／rules。Same-input generator首次因新worktree缺ignored日曆而TRADING_CALENDAR_UNVERIFIED失敗，原錯誤保留；依發布skill複製完整primary原始日曆並記SHA與離線用途後，原generator單次重跑通過，未改規則或刷新市場資料以解封。
- **Git與registry。** SRPP feature `860c59ec886b42c4cc8fafd07bba0c4ff5e0bd1c`、main／remote `e18572ad5c7b374f4bf0b9de69f35f4e7a50c7ec`；asset實作feature `d9803a4df8454b39f2e5cc3924c3790a311e9424`、已部署實作main／remote `2177421f1e789d06fff4bdb629720fd8b53ef004`。兩repo各no-ff merge／push與精確remote讀回，SRPP實際工作區ff同步且保留原5項未追蹤資料。之後由已合併SQL於13:05:03新增新bundle row，registry35→36，舊35列canonical全文維持；新row四欄policy_bundle_sha256／formula_version／policy_document／formula_manifest精確一致，ASSET_MGMT_SRPP_V2及原targets／formula不變。
- **部署與來源驗證。** 從乾淨正式asset main與原.env只build／force-recreate business-services；新image `sha256:a4d718fa9b843b3393acaa01a1a8c53c93a6f3f78e2566ba0a0a4ef1ce3b4f55`、container `11ed08c369fe1c643c1b46b27024675b6736b92c5e0fa440350f1bda6b8be0d8`、JAR SHA `fb8936009934aed40ff662a1962dd97ac1d1df3adce05897c999dc4fdf2cd2b7`，healthy且safe TWSE GET200。JAR五份authority與main原始bytes、calculator class與本輪核准離線class一致；另一位agent亦獨立核對當輪JAR資源／class／新bundle及運行healthy。環境hash、mount、network、healthcheck、restart、ports均維持，其他8個容器及image不變，BFF未重啟。使用run-stack工具的指定Terra不可用備援，繼承主agent模型，不宣稱改用其他模型。
- **當輪啟動行為。** 以新收據確認當時台北13:xx，08:50已過，因此不沿用上輪01:xx的before-earliest斷言。既有startup會執行closure check，之後因週六非交易日不分析／不寄信；實際13:06:24 log顯示closure check false、Saturday skip，indices已current略過、snapshot rolled0／skipped3／failed0、US備份當日已有durable entry而略過。這是授權recreate的既有正常啟動路徑，沒有人工呼叫刷新、改flags或資料來取得通過。
- **SRPP實機9090。** 13:06台北由root執行既有verify_9090.py，日曆365日／TWSE21列／報價23筆均HTTP200且有效，連線PASS。以本輪精確新hash與原Swagger對Codex／Claude各做當日2026-11-07、09:05的report-calculations GET，兩者均HTTP409 NON_TRADING_DAY；完整query／raw response／hash已保存。它不等於正式交易日採用或可執行單，不以registry／JAR／健康檢查代替成功業務結果。沒有人工producer、重跑報告、寄信、手動刷新或交易。

本輪本機收據在 `/Users/steven/Project/SRPP/data/output/diagnostics/2026-11-07-nsf-interpretation-release/`，包含 `asset-offline-validation.json`、`asset-independent-reviews.json`、`asset-git-landing.json`、`registry-verification.json`、`deployment-preflight.json`、`deployment-verification.json`、`deployment-startup-observations.log`、`asset-independent-runtime-check.json`、`9090-connectivity.json`及`business-readback-receipt.json`。初次日曆失敗與完整原始copy另保留各自收據；環境只保存hash與相等判斷，不存秘密原文。

本段及562.1–562.5勾選只回填已完成實證，未修訂功能契約、backend runtime資源或SRPP來源。依CLAUDE.md「只是回填任務完成報告」明文例外處理，另委派文件commit／no-ff merge／push並記錄backend tree／runtime bytes維持；不為文件提交重建bundle、SQL或映像，不重啟容器。已部署實作身分仍以上述2177421f為準，不預填未來文件commit SHA。
