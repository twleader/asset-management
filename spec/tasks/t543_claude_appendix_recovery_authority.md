# [t543] 發布 Claude 附錄續作與 Gmail 數字檢核修復的規則包

**對應 Requirements:** Requirements 187／193（SRPP 唯讀報告計算與精確 authority 身分發布）
**前置任務:** SRPP 附錄續作與郵件檢核修正經測試並凍結來源
**Liquibase changeset:** 無

## 背景

2026-11-04 Claude 09:05 在09:29建立核心，回讀時因 Gmail 為127.0.0.1:9090加入google.com/url的ust數字，造成35／36項通過；09:32即以時間預算收工，實際距原60分鐘上限仍有33分鐘。附錄未建立。修正由SRPP負責已知轉址包裝的窄正規化與實際時鐘續作指示，保留真正數字差異拒絕、核心凍結、原日曆／L0／金融閘門和35／40／60分鐘。

## 要做什麼

- [ ] 543.1 唯一source root為 `/private/tmp/srpp-claude-appendix-recovery-20261104`。等來源負責者明示凍結最終bytes與測試通過才同步；記錄source commit、bundle、manifest/model/Swagger hashes，不預填暫存hash。來源只修consumer格式檢核、計時續作及兩個既有Claude入口；另補齊既有staged檢查漏列的精確 `assumptions/srpp_us_report_calculations_schema.json`，不放寬來源選擇或驗證。不改金融規則、模型、公式、targets、API、producer與權限。
- [ ] 543.2 完整複製最終manifest到 `backend/src/main/resources/srpp/decision/policy_manifest.json`。以原Python工具重產主report及long-short-exit兩份golden，以原Ruby工具重產 `backend/src/main/resources/srpp/report/d201-registration.sql`。model、formal policy document、formula manifest及所有golden cases／calculator hashes不變，golden只允許bundle metadata差異。Python使用-B防止SRPP bytecode寫入。
- [ ] 543.3 原authority checker、Ruby registry測試、release gate regression及聚焦Java authority／calculator測試均通過。來源乾淨提交後只做Swagger renderer／identity --check，不執行無關mutation。spec機械檢查與獨立審查在寫runtime resources之前完成。
- [ ] 543.4 依本輪使用者明確選用的完整發布流程，先兩repo feature commit、main no-ff merge、push與remote refs精確讀回，才單次執行已合併受追蹤insert-only SQL。登錄前後驗原rows全文不變，新row bundle、formula version、policy document及formula manifest四欄精確相同。相同hash既存且相同可沿用；矛盾即停止，不更新／刪除歷史。
- [ ] 543.5 只由乾淨asset main及其正式.env rebuild並force-recreate business-services。確認新image/container healthy、正式effective env／mount一致，其他容器ID/image不變；執行JAR manifest/model/Swagger/registration SQL逐位元等於已合併來源。前置測試與離線build不替換正式服務，正式serve驗收留在兩repo落地與登錄後。
- [ ] 543.6 從SRPP實際主機Python執行唯讀9090連線檢查，另保存精確新bundle之report-calculations HTTP原文與獨立契約／身分驗證；403／409／503或缺包不能稱正式採用。時窗外或無當輪完整證據只記診斷與NOT_YET_EXECUTED，不觸發producer、重跑／補做早盤、建立或更改草稿、寄信或交易。私人HTTP／資產原文不提交公開repo，只提交去識別發布摘要。

## 驗證

```bash
bash scripts/spec-check.sh
python3 -B scripts/check-srpp-report-authority.py --srpp-root /private/tmp/srpp-claude-appendix-recovery-20261104 --check
python3 -B scripts/generate-srpp-long-short-exit-golden.py --srpp-root /private/tmp/srpp-claude-appendix-recovery-20261104 --check
ruby scripts/tests/srpp-policy-register-test.rb
ruby scripts/tests/srpp-report-policy-register-test.rb
SRPP_RELEASE_WORKTREE=/private/tmp/srpp-claude-appendix-recovery-20261104 python3 -B scripts/tests/check-srpp-report-authority-test.py
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=SrppReportModuleCalculatorTest,DecisionPolicyAuthorityTest,DailyDecisionCalculatorTest,SrppReportShortUsCalculatorTest test
git diff --check
```

正式serve驗收包含僅business-services build/recreate、registry四欄及JAR全文比對、其他容器與設定保持、SRPP主機真HTTP；不以healthy或日曆代替計算包採用。來源凍結後另驗兩份golden除bundle外完全相同。既有測試名稱如與當前版本不符，先核對實際類名並修正，不略過。

## 完成報告

待實作及實際發布後回填。
