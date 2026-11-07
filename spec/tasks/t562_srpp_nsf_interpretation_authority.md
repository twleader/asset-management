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
- [ ] 562.4 使用者明示順序為離線驗收→雙repo各feature commit／main no-ff merge／push及精確remote讀回→已合併SQL insert-only registry。精確核新hash的policy_bundle_sha256／formula_version／policy_document／formula_manifest四欄全文，所有舊rows保持；不UPDATE／DELETE歷史資料。
- [ ] 562.5 由委派run-stack從乾淨正式asset main與原.env／mount／network設定，只build／force-recreate business-services；先核實真Compose project與活動報告／啟動工作，保留其他容器與BFF、原設定及有界health／safeGET流程。驗運行JAR五份authority與合併來源全文一致、計算器class保留，從SRPP host以新hash作唯讀業務GET並保存實際HTTP／原回應；不在時窗外冒稱成功採用，正式日報採用仍須合法入口及完整required_reads／同owner L0／verifier。無人工producer、刷新、報告、Gmail或交易。

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

首次提交前離線檢查點：D210／policy2.59.0／model3.22.0不變，新bundle `2da98a8706843024ab25f01aa3b8145d64620898d00a1690779b8ff0dcfbd708`。僅同步manifest、原Ruby產生SQL與兩份原Python生成goldens的bundle身分；model、calculator、formal targets、formula manifest及Swagger全文維持。獨立spec與arch審查均critical0／major0／minor0，spec-check為BLOCK0／CHECK0。54項Java、13項Ruby（307 assertions）、4項authority負向、5項long-short來源回歸與backend package通過；73／15goldens除bundle外全文相同，原金融expected與calculator hashes未變。SRPP另通過80項shared／runtime／political回歸及各consumer／runtime／rules檢查。

same-input generator第一次因新worktree缺ignored交易日曆而TRADING_CALENDAR_UNVERIFIED失敗，原錯誤保留；依發布skill複製完整primary原始日曆並記SHA與離線用途後，原generator單次重跑通過。未改來源／金融規則以解除失敗，未刷新市場資料。SRPP已先完成feature `860c59ec886b42c4cc8fafd07bba0c4ff5e0bd1c`、main／remote `e18572ad5c7b374f4bf0b9de69f35f4e7a50c7ec`及實際日報工作區同步；本文件首次提交時asset Git／registry／正式部署與業務讀回仍待依序執行，未預宣稱完成或正式日報採用。

本輪收據統一在 `/Users/steven/Project/SRPP/data/output/diagnostics/2026-11-07-nsf-interpretation-release/`；目前包含 `asset-offline-validation.json`、`asset-independent-reviews.json`、`asset-spec-check.log`、`asset-generator-initial-failure.json`與`offline-calendar-copy.json`。後續正式鏈由本輪新收據驗證，不沿用上輪runtime作證。本段為完成報告檢查點回填，沒有修訂功能契約或backend resources。
