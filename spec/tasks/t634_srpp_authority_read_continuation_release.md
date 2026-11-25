# [t634] 同步午報規則讀取續作修復的權威規則包

**對應 Requirements:** Requirement 187（SRPP不可變日報計算包的規則包身分與同輸入一致性）
**前置任務:** 無
**Liquibase changeset:** 無

## 背景

2026-11-25 Codex午報的日曆、Swagger、runtime與政策均PASS，主體68批只讀9批，即在實際task_started後約138秒以AUTHORITY_PACKET_READ_INCOMPLETE結束；failed_checks為空、沒有原始工具錯誤，L0及草稿均未開始。這是無合法停止證據提前結束，無法從公開紀錄判定模型內在理由。SRPP修復讀取續作提示與起跑計時來源，資產端只同步其authority身分，不改任何金融計算、API或投資規則。

## 要做什麼

- [x] 634.1 SRPP既有daily_timing.py增加--authority-progress，用實際時鐘與原started_at、當前包expected_batches及連續零起算batches_read回報下一批。缺值、洞、重複、bool批號、非法時間拒絕；不接受--now、不自動寫已讀、不推定startup、不讀金融來源。狀態僅SELF_REPORTED_READ_PROGRESS_ONLY，model_read_proven=false、gate_authorization=NOT_EVALUATED；35分鐘取數截止與60分鐘停止不變。主體未讀完且無原停止／實際失敗即續讀；已讀完仍不能授權下游。起跑時間用已提供harness metadata，否則讀規格前第一次實際host clock，不在日曆前增查DB／rollout。
- [x] 634.2 資產端只以原generator同步SRPP最終manifest、model原bytes、正式policy document、insert-only registration SQL與兩份golden；不改Java calculator、公式manifest、投資規則、路由／Swagger、來源與排程，不刷新來源、不修改歷史registry。所有golden金融輸入輸出及calculator hashes必須與main相同（僅bundle身分可變）。
- [x] 634.3 獨立Codex CLI spec審查通過後才產生資產resource；執行authority gate、generator測試與相關Java回歸。保留原午報INCOMPLETE收據，不補跑日報、不建草稿、不寄信、不下單。既有其他worktree未提交工作原樣保留。
- [x] 634.4 兩專案feature commit、main --no-ff merge、push及精確remote-ref讀回後，執行受追蹤SQL transaction新增或重用同值row，完整比對policy document與formula manifest並核對舊rows不變。從乾淨資產main及正式設定重建/recreate business-services，保留其餘容器和設定，讀回JAR authority原bytes。SRPP主機依Swagger實測日曆、TWSE與quotes三個唯讀GET。自然排程實際遵循續作及完成草稿仍待合法交易日時窗驗收，靜態測試不保證模型行為。

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

本次修復、前置驗證及正式發布已完成，原午報INCOMPLETE收據保留，未補跑報告、建立草稿、寄信或下單。流程修復只補上續讀提示與起跑計時契約，不證明模型自然排程已遵循或日報已交付。

SRPP main為`98ad7851594e23fdf8fcdbe2972620b99b3f9f55`，已推送並精確讀回，實際runtime安全ff至同版；原242個SRPP與91個資產primary無關檔案bytes保持。資產執行產物來源main為`3a08d15975761e44d304b231c4340871d8ca8da5`，feature為`6f875c03aebcbb325c5051c7a67642fccc2044f4`，兩repo均採feature commit與main no-ff merge/push。此完成段為發布後純文件回填，後續文件main與image來源commit不同，但原執行／authority產物保持，不為文件再次重建。

最終bundle為`2d86981102ff814d9bc150058c7e707e52486bc1ccaa6a090a7683ad8d604770`；model、正式policy document、formula manifest及Swagger bytes未變。111／15 golden cases僅bundle身分不同，金融輸入輸出與calculator hashes完全相同。SRPP timing14/runtime35/rule_packs11/rule_query11共71測試、資產authority4、Ruby6+9、Java84全通過；final spec-check BLOCK0/CHECK0，獨立本機Codex spec第2輪與arch首輪PASS，0 critical/major/minor。

首輪spec唯一Major是測試命令未綁SRPP_RELEASE_WORKTREE，已修正。原要求由Claude修正，但實際API餘額不足、既有subscription達weekly limit；使用者隨後明確授權「授權給 codex 修正」，由Codex修該一行並經合法獨立第2輪複審通過。原錯誤、先前還原且discard的無效review均保留，未冒稱Claude完成。

由已合併受追蹤SQL作insert-only transaction，registry79→80，新row四欄全文精確等generator，舊79rows保持，未UPDATE／DELETE歷史。僅business-services從乾淨main及正式設定build並force-recreate，新container為`6e6817c1f073e8e4e9ccdecde7c54cdee85c57b9b4a50cfe492c8283a9b43701`，image為`sha256:d74e2b2cbc375dbaf1f937b16d1684e5a830d480288a89d39d71f6c45a527e0e`且healthy。JAR內manifest/model/Swagger/SQL/formal policy五份bytes全等來源main；47個明示env、完整53個Env pairs及4mount相同，9個無關容器ID保持。raw effective config首次差異經原證據確認為Docker生成hostname與Env順序，未重建或改設定，診斷錯誤亦保留。

2026-11-25 21:26:02 +08:00，SRPP實機`.venv/bin/python`執行skill verify_9090.py，日曆365列AVAILABLE、TWSE日線21列、台股報價40列且40正價，三GET均200及內容PASS。這僅READ_ONLY_CONNECTIVITY_ONLY；正式report-calculations及自然排程草稿交付維持NOT_YET_EXECUTED，沒有在晚間冒驗收。

完整本輪證據位於SRPP `reports/noon-read-release-20261125/`與主機`/tmp/srpp-noon-read-release-20261125/`。本段不填入自身提交SHA，避免為文件重複發布。
