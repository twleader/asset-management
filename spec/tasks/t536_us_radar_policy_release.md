# [t536] 發布美股全部雷達分析規則包身分

**對應 Requirements:** Requirement 193（現行SRPP規則包authority發布鏈）
**前置任務:** t535
**Liquibase changeset:** 無

## 背景

使用者要求美股報告分析持股加本輪全部美股雷達，並要求新hash同步資產管理系統，兩專案均commit、merge、push。來源已以官方工具驗證bundle `17d105ecae3c357b6c19f52a479f70e6f9a81740034969df0b343752cdaad58f`；D-205／2.54.0／3.22.0保持，model bytes與金融公式未變。

## 要做什麼

- [ ] 536.1 來源 `/Users/steven/Project/SRPP` 官方policy/runtime檢核通過後，逐位元同步model與manifest；不手填hash、不變model、targets、Swagger、API或公式。
- [ ] 536.2 用既有Ruby registration及Python golden generator重產追蹤產物；主golden與獨立 `report-long-short-exit-golden.json` 均只允許bundle身分變更；後者以原 `generate-srpp-long-short-exit-golden.py` 重產，所有cases／expected不變，並盤點其他獨立golden的bundle欄位，固定hash回歸斷言同步，正式policy document維持原bytes。所有內嵌資源、SQL、golden經offline authority gate核對。
- [ ] 536.3 獨立spec review後實作，Ruby registry、Python authority gate及受影響Java parity／authority測試通過；不得借舊hash假通過。
- [ ] 536.4 使用main正式設定於隔離feature只build/recreate business-services，healthy後驗BFF safe GET與JAR精確bytes；保留其他容器。兩repo均commit、no-ff merge、push並讀回remote refs，再從乾淨main重建同服務。
- [ ] 536.5 生成SQL只允許INSERT ON CONFLICT DO NOTHING；雙repo落地後transaction執行並exact row readback，所有歷史rows保留。只讀官方calendar，僅在twTrading=true當日09:05<=now<13:30且JAR／registry已核對才可金融GET驗收；時窗外記NOT_YET_EXECUTED，不POST產生報告、不重跑排程、不寄信、不交易。

## 驗證

```bash
python3 /Users/steven/Project/SRPP/scripts/policy_bundle.py check --purpose daily --consumer Codex
python3 scripts/generate-srpp-report-golden.py --srpp-root /Users/steven/Project/SRPP --check
python3 scripts/generate-srpp-long-short-exit-golden.py --srpp-root /Users/steven/Project/SRPP --check
python3 scripts/check-srpp-report-authority.py --srpp-root /Users/steven/Project/SRPP --check
ruby scripts/tests/srpp-report-policy-register-test.rb
ruby scripts/tests/srpp-policy-register-test.rb
python3 scripts/tests/check-srpp-report-authority-test.py
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=SrppPolicyRegistryServiceTest,SrppReportShortUsCalculatorTest,SrppReportModuleCalculatorTest,SrppReportBuildIdentityTest test
bash scripts/spec-check.sh
git diff --check
docker compose -p asset-management build business-services
docker compose -p asset-management up -d --no-deps --force-recreate business-services
```

## 完成報告

- 來源bundle `17d105ecae3c357b6c19f52a479f70e6f9a81740034969df0b343752cdaad58f` 與官方policy/runtime check通過。內嵌manifest逐位元同步；model與正式policy document原bytes不變。
- 原generator更新主golden22例與完整持有期golden15例，JSON除頂層policyBundleSha256外完全相同；SQL除新bundle外與前版一致且保持insert-only。沒有Java／公式／Swagger／API變更。
- Java五類38 tests、Python authority4與獨立generator5 tests、Ruby report5／261 assertions及registry8／46 assertions全數通過；兩份golden --check、offline authority gate與diff-check PASS。獨立spec與架構審查最終均0 findings。
- 測試收據在 `/private/tmp/asset-radar-tests/`；部署、Git與registry精確收據統一保存 `/private/tmp/asset-radar-release/`。金融runtime驗收受合法交易時窗限制，尚未執行不得宣稱已採用。
