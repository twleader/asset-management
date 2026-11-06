# [t561] 台股股票短線反彈門檻改為 3% 與精確 authority 發布

**對應 Requirements:** Requirement 187（唯讀 SRPP 報告計算之正式政策與相同輸入驗證）
**前置任務:** t558
**Liquibase changeset:** 無

## 背景

使用者 2026-11-07 要求台股股票短線相對支撐反彈確認從 8% 改成 3%。這是已發生的反彈幅度，不是預測獲利；仍須原均線／KD／淨風報比／資金等條件。資產端 embedded model、manifest、goldens 與 registry 需同步同一份 SRPP 凍結來源，避免日報使用兩組門檻。

## 要做什麼

- [x] 561.1 完成 spec-check 與作者以外的唯讀 spec 審查，記錄 spec-review-pass 後才寫 backend。SRPP feature 為 `/private/tmp/srpp-rebound-3pct-20261107`；待 root 凍結 hash 後只複製該 feature 的 `assumptions/model_inputs.json`／`assumptions/policy_manifest.json` 精確 bytes 至 backend 同名 decision resources。只允許 equity `execution_policy.short_term_opportunity_d171.minimum_rebound_pct`=3.0；債券反彈 5.0、美股戰術淨獲利 15.0、其餘金融門檻與配置維持。
- [x] 561.2 既有 Java `SrppReportModuleCalculator.shortEntryReasons` 必須讀正式 model 的 BigDecimal 門檻，不寫第二套常數。僅對 ledger.strategy=SHORT_TERM_D173，以未捨入十進位原始 price*100 與 support*(100+minimum_rebound_pct) 交叉乘積比較，含等於；保留顯示四位小數與債券原 comparator，不新增 schema／輸出欄位。新增有意義的 Java 邊界回歸：支撐100／現價102.99999（顯示3%但原值未達標）等待、小於 3%、等於 3%、3%至8%之間；原月線／五日線／K>D／月線 ABOVE、淨風報比1.5、雷達方向反例仍阻擋，並核 bond 與 US 原值。只允許反彈條件通過，不聲稱具備可執行單。
- [x] 561.3 以原 authority checker 的 targets projection 建正式 policy document（若同值則保留），原 Ruby stdout 產受追蹤 insert-only SQL；以原 Python generators 重產適用 goldens。formula manifest、formula family、Swagger 完整 bytes 不變。凍結後來源再改必須重產全部衍生物與驗證。實作後作者以外的唯讀 arch 審查完整 diff，修復本輪問題。
- [x] 561.4 本輪採現行已接受 Task 549／550／558 的 authority-maintenance 發布鏈（離線 parity／test／package→雙repo落地→registry→正式main build／recreate），不建立另一套stack或改正式設定。authority／golden checks、Ruby registry tests、focused Java tests 與 backend package 通過後回報 root。雙 repo feature commit→main no-ff merge→push及精確remote讀回前不執行SQL、不正式部署。發布後 insert-only registry讀回 policy_bundle_sha256／formula_version／policy_document／formula_manifest四欄全文，既有rows全文維持。
- [x] 561.5 從乾淨 asset main 與原正式 .env／mount／network 設定只build／force-recreate business-services；先核真Compose project與活動報告／startup工作，保留其他容器及BFF，按原有界health與safeGET驗證。讀回JAR model／manifest／Swagger／SQL／formal document與凍結來源bytes，從SRPP host以新hash呼叫唯讀9090業務GET並保存原狀態；時窗外正式採用NOT_YET_EXECUTED。無人工producer、刷新、報告、Gmail或交易。

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

2026-11-07（Asia/Taipei）完成本輪規則更新、雙專案落地、insert-only登錄與正式部署。股票短線的「相對支撐已反彈至少8%」改為「至少3%（含等於）」，其餘進場閘門及債券5%／美股淨空間15%維持。**正式日報採用仍為 NOT_YET_EXECUTED，當次業務API讀回不是成功採用。**

- **規則身分與邊界。** D-210／policy2.59.0／model3.22.0；bundle `2683d57a182e1c0b44d81eb90a9e6c4b63c0cb6b325fc8284a1b709718b111ec`。Java僅對SHORT_TERM_D173採原始BigDecimal交叉乘積比較，2.99999%即使顯示3%仍等待，恰好3%及5%可通過反彈條件；均線、K>D、月線ABOVE、風報比及雷達反例仍阻擋。顯示精度、債券原比較、所有其他金融條件與Swagger bytes保留。
- **獨立審查與離線驗證。** 首輪spec審查找到既有Java先捨入後比較的邊界差異，修訂契約後由另一位審查者重審；第二輪spec與實作arch審查均critical0／major0／minor0。spec-check為BLOCK0／CHECK0。54項Java測試、13項Ruby（307 assertions）、4項authority負向及5項long-short來源回歸、backend package通過；SRPP另有78項回歸通過。第一輪Java失敗只因authority測試仍期待D209／2.58，已改為本輪D210／2.59後重跑通過，原失敗log保留。73項same-input及15項long-short goldens由原工具生成，除bundle身分外全文未變；既有13項full short-US案例也以新model通過。
- **Git與registry。** SRPP feature `85dddfb49c2a1283cbb48969203e0192b4c4605c`、main `5e15c603df07f3935e1b211ee144bbd54a7f5158`；asset feature `185df674ccf0c41d4fd7a60b7850f9c85986b2e7`、已部署實作main `03b4b9b74bfb6ec57a73916304159d34cdce9049`。兩repo均no-ff merge／push與精確remote讀回；SRPP實際日報工作區已同步且保留原未追蹤資料。之後由已合併SQL於01:03:14新增新bundle row，registry34→35，原34列canonical全文維持，policy_bundle_sha256／formula_version／policy_document／formula_manifest四欄精確相符；公式家族ASSET_MGMT_SRPP_V2與既有targets未變。
- **正式部署。** 依現行Task549／550／558 authority-maintenance順序，在離線驗收、雙repo落地及registry後，只從乾淨asset main與正式設定build／force-recreate business-services。新container `ee18201d1a97a4aab736fb0fd48af2bdda793dc1baca2ceea32b891931f27d4d`、image `sha256:4fdb7478b4a5f65720105a8dbf53b79168fbdbba9fbc83086c9bbe5aeba8c960`、JAR SHA `848580edbdb7fc5f906db79ba6556a1749069c4aae2aa218a3cdc3d1b7edd08c`，healthy且safe GET200。5份JAR authority及calculator class與已合併／已驗證離線bytes相同；JAR內股票3／債券5／美股15。完整環境hash、mount、network、healthcheck、restart、ports均維持，其他8個容器及image不變，BFF未重啟。另一位agent獨立重核已下載JAR全文及運行image／healthy均通過。startup唯讀預檢先前誤用max(id)見歷史snapshot，依實際repository的MAX(snapshot_date)修正後3位owner皆當日；原誤查／修正證據保留，未改資料／flags。實際啟動log顯示analysis未到08:50跳過、indices已current跳過、snapshot rolled0/skipped3/failed0。
- **9090與界線。** root從SRPP host執行既有verify_9090.py，calendar365日／TWSE21列／quotes23筆均HTTP200且有效，連線PASS。01:06:54以新bundle與原Swagger對Codex／Claude各做一次當日2026-11-07、09:05的精確hash report-calculations GET，兩者均HTTP400 INVALID_REQUEST，當時早於slot且為週六；原請求、回應與hash已保存，api_accepted=false，不以registry／JAR／一般連線冒稱正式日報採用。沒有人工producer、重跑報告、寄信、刷新或交易。

本輪本機收據統一在 `/Users/steven/Project/SRPP/data/output/diagnostics/2026-11-07-rebound-3pct-release/`，包含 `asset-offline-validation.json`、`asset-independent-reviews.json`、`asset-git-landing.json`、`registry-verification.json`、`deployment-verification.json`、`asset-independent-runtime-check.json`、`9090-connectivity.json`及`business-readback-receipt.json`。只存環境hash與相等判斷，不保存秘密原文。

本段及561.1–561.5勾選只回填已完成工作的實證，未改功能契約、backend runtime資源或SRPP來源。依CLAUDE.md「只是回填任務完成報告」明文例外處理；文件收尾另由subagent完成commit／no-ff merge／push並證明runtime paths全文未變，不因文件提交再重算bundle、SQL或重建／重啟容器。部署實作身分仍以上述03b4b9b7為準，不預填未來文件commit SHA。
