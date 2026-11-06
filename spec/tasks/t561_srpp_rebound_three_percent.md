# [t561] 台股股票短線反彈門檻改為 3% 與精確 authority 發布

**對應 Requirements:** Requirement 187（唯讀 SRPP 報告計算之正式政策與相同輸入驗證）
**前置任務:** t558
**Liquibase changeset:** 無

## 背景

使用者 2026-11-07 要求台股股票短線相對支撐反彈確認從 8% 改成 3%。這是已發生的反彈幅度，不是預測獲利；仍須原均線／KD／淨風報比／資金等條件。資產端 embedded model、manifest、goldens 與 registry 需同步同一份 SRPP 凍結來源，避免日報使用兩組門檻。

## 要做什麼

- [ ] 561.1 完成 spec-check 與作者以外的唯讀 spec 審查，記錄 spec-review-pass 後才寫 backend。SRPP feature 為 `/private/tmp/srpp-rebound-3pct-20261107`；待 root 凍結 hash 後只複製該 feature 的 `assumptions/model_inputs.json`／`assumptions/policy_manifest.json` 精確 bytes 至 backend 同名 decision resources。只允許 equity `execution_policy.short_term_opportunity_d171.minimum_rebound_pct`=3.0；債券反彈 5.0、美股戰術淨獲利 15.0、其餘金融門檻與配置維持。
- [ ] 561.2 既有 Java `SrppReportModuleCalculator.shortEntryReasons` 必須讀正式 model 的 BigDecimal 門檻，不寫第二套常數。僅對 ledger.strategy=SHORT_TERM_D173，以未捨入十進位原始 price*100 與 support*(100+minimum_rebound_pct) 交叉乘積比較，含等於；保留顯示四位小數與債券原 comparator，不新增 schema／輸出欄位。新增有意義的 Java 邊界回歸：支撐100／現價102.99999（顯示3%但原值未達標）等待、小於 3%、等於 3%、3%至8%之間；原月線／五日線／K>D／月線 ABOVE、淨風報比1.5、雷達方向反例仍阻擋，並核 bond 與 US 原值。只允許反彈條件通過，不聲稱具備可執行單。
- [ ] 561.3 以原 authority checker 的 targets projection 建正式 policy document（若同值則保留），原 Ruby stdout 產受追蹤 insert-only SQL；以原 Python generators 重產適用 goldens。formula manifest、formula family、Swagger 完整 bytes 不變。凍結後來源再改必須重產全部衍生物與驗證。實作後作者以外的唯讀 arch 審查完整 diff，修復本輪問題。
- [ ] 561.4 本輪採現行已接受 Task 549／550／558 的 authority-maintenance 發布鏈（離線 parity／test／package→雙repo落地→registry→正式main build／recreate），不建立另一套stack或改正式設定。authority／golden checks、Ruby registry tests、focused Java tests 與 backend package 通過後回報 root。雙 repo feature commit→main no-ff merge→push及精確remote讀回前不執行SQL、不正式部署。發布後 insert-only registry讀回 policy_bundle_sha256／formula_version／policy_document／formula_manifest四欄全文，既有rows全文維持。
- [ ] 561.5 從乾淨 asset main 與原正式 .env／mount／network 設定只build／force-recreate business-services；先核真Compose project與活動報告／startup工作，保留其他容器及BFF，按原有界health與safeGET驗證。讀回JAR model／manifest／Swagger／SQL／formal document與凍結來源bytes，從SRPP host以新hash呼叫唯讀9090業務GET並保存原狀態；時窗外正式採用NOT_YET_EXECUTED。無人工producer、刷新、報告、Gmail或交易。

## 驗證

```bash
bash scripts/spec-check.sh
python3 -B scripts/check-srpp-report-authority.py --srpp-root /private/tmp/srpp-rebound-3pct-20261107 --check
python3 -B scripts/generate-srpp-report-golden.py --srpp-root /private/tmp/srpp-rebound-3pct-20261107 --check
python3 -B scripts/generate-srpp-long-short-exit-golden.py --srpp-root /private/tmp/srpp-rebound-3pct-20261107 --check
ruby scripts/tests/srpp-policy-register-test.rb
ruby scripts/tests/srpp-report-policy-register-test.rb
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=SrppReportModuleCalculatorTest,DecisionPolicyAuthorityTest,DailyDecisionCalculatorTest,SrppReportShortUsCalculatorTest,UsCoreSellCalculatorTest test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DskipTests package
git diff --check
```

正式部署由root協調雙repo落地及registry後執行；從正式main目錄使用 `docker compose -p asset-management build business-services`、`docker compose -p asset-management up -d --no-deps --force-recreate business-services`。新container healthy後核safe GET與JAR全文；DB schema未改，不重產schema.sql。

## 完成報告

待實際驗證回填，不預宣稱部署或正式日報採用。
