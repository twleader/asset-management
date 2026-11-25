# [t634] 同步午報規則讀取續作修復的權威規則包

**對應 Requirements:** Requirement 187（SRPP不可變日報計算包的規則包身分與同輸入一致性）
**前置任務:** 無
**Liquibase changeset:** 無

## 背景

2026-11-25 Codex午報的日曆、Swagger、runtime與政策均PASS，主體68批只讀9批，即在實際task_started後約138秒以AUTHORITY_PACKET_READ_INCOMPLETE結束；failed_checks為空、沒有原始工具錯誤，L0及草稿均未開始。這是無合法停止證據提前結束，無法從公開紀錄判定模型內在理由。SRPP修復讀取續作提示與起跑計時來源，資產端只同步其authority身分，不改任何金融計算、API或投資規則。

## 要做什麼

- [ ] 634.1 SRPP既有daily_timing.py增加--authority-progress，用實際時鐘與原started_at、當前包expected_batches及連續零起算batches_read回報下一批。缺值、洞、重複、bool批號、非法時間拒絕；不接受--now、不自動寫已讀、不推定startup、不讀金融來源。狀態僅SELF_REPORTED_READ_PROGRESS_ONLY，model_read_proven=false、gate_authorization=NOT_EVALUATED；35分鐘取數截止與60分鐘停止不變。主體未讀完且無原停止／實際失敗即續讀；已讀完仍不能授權下游。起跑時間用已提供harness metadata，否則讀規格前第一次實際host clock，不在日曆前增查DB／rollout。
- [ ] 634.2 資產端只以原generator同步SRPP最終manifest、model原bytes、正式policy document、insert-only registration SQL與兩份golden；不改Java calculator、公式manifest、投資規則、路由／Swagger、來源與排程，不刷新來源、不修改歷史registry。所有golden金融輸入輸出及calculator hashes必須與main相同（僅bundle身分可變）。
- [ ] 634.3 獨立Codex CLI spec審查通過後才產生資產resource；執行authority gate、generator測試與相關Java回歸。保留原午報INCOMPLETE收據，不補跑日報、不建草稿、不寄信、不下單。既有其他worktree未提交工作原樣保留。
- [ ] 634.4 兩專案feature commit、main --no-ff merge、push及精確remote-ref讀回後，執行受追蹤SQL transaction新增或重用同值row，完整比對policy document與formula manifest並核對舊rows不變。從乾淨資產main及正式設定重建/recreate business-services，保留其餘容器和設定，讀回JAR authority原bytes。SRPP主機依Swagger實測日曆、TWSE與quotes三個唯讀GET。自然排程實際遵循續作及完成草稿仍待合法交易日時窗驗收，靜態測試不保證模型行為。

## 驗證

```bash
python3 scripts/check-srpp-report-authority.py --srpp-root /Users/steven/.codex/worktrees/noon-read-continuation-20261125/SRPP --check
SRPP_RELEASE_WORKTREE=/Users/steven/.codex/worktrees/noon-read-continuation-20261125/SRPP python3 -B scripts/tests/check-srpp-report-authority-test.py
ruby scripts/tests/srpp-report-policy-register-test.rb
ruby scripts/tests/srpp-policy-register-test.rb
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=SrppPolicyDocumentTest,SrppPolicyRegistryServiceTest,SrppReportModuleCalculatorTest test
```

正式部署由run-stack在兩專案落地與registry驗證後執行：docker compose -p asset-management build business-services，接著up -d --no-deps --force-recreate business-services，等待healthy，完整核對JAR內manifest/model/Swagger/SQL原文及新image/container。時窗外只作維護三GET，不呼叫report-calculations冒稱正式採用。

## 完成報告

尚未正式落地／部署，收據將由發布操作者回報。流程修復不證明自然排程已遵循或日報已交付。
