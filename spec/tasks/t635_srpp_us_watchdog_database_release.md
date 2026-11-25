# [t635] 發布美股守門員資料庫入口與部署parity修復

**對應 Requirements:** Requirement 187（SRPP不可變日報計算包規則身分與同輸入一致性）
**前置任務:** 無
**Liquibase changeset:** 無

## 背景

2026-11-25盤前readiness確認，既有Codex守門員22-00-2完整prompt仍指定sqlite/codex-dev.db，實際當前排程及近期run在sqlite/codex.db；舊DB僅保留10月及09:45舊名稱，可能使守門員找不到當日精確22-00執行。維護只修此DB入口，不改季節/日曆gate、targetSlot至watchSlot半開window、卡住判定、一次精確收斂、模型、時段、投資規則或金融硬閘，也不補跑報告。

## 要做什麼

- [x] 635.1 SRPP建立既有22-00-2完整受版控prompt正本，只替換DB入口一段，其餘條文逐字保留。獨立us_watchdog_entry.py提供--database-schema，CODEX_HOME未設/空白用家目錄.codex，優先sqlite/codex.db，只有路徑不存在才fallback codex-dev.db。存在但空/錯schema/無資料/無法讀或broken symlink／directory都不換舊DB；readonly URI及query_only，僅PRAGMA automations與automation_runs實際schema，不假設started_at、不讀run正文、不寫DB／檔／排程、不授權停止。
- [x] 635.2 --check-deployed只核US守門員，完整正本prompt須同時等於automation TOML與選定當前DB的既有22-00-2 row，並核精確id/name、ACTIVE、原rrule/project。只改TOML或只改DB皆PENDING_NATIVE_UPDATE。該檢查不依賴TW入口parity、不接金融L0、不證明run存活／守門已執行或金融採用。model/effort及其他設定另以實際before/after metadata比對保留，不改主排程22-00。昨夜來源獨立性與讀取覆蓋缺口仍屬未驗證，不改成PASS。
- [ ] 635.3 正常部署只經App可用更新入口改既有22-00-2的prompt、保留其他設定；工具不可用則保存可貼上的正文與PENDING_NATIVE_UPDATE，不直接寫SQLite、不改TOML冒稱DB同步、不繞過App安全限制。這項native缺口不阻擋已授權其餘bundle發布，但最後須分開回報。發布前核時及主排程活躍證據，避免22:35真報告起跑後改源混版；若接近合法起跑且有活躍報告，暫停runtime改源與依賴部署，不補報或停止正常報告。
- [x] 635.4 資產端只同步最終SRPP manifest/model原bytes、由原generator產生formal policy、insert-only SQL與兩份golden；model/Swagger/formula與金融Java程式不改。另用SRPP原export_settings_runtime.py固定24來源同步srpp-simulator/runtime並--check；舊鏡像落後，5來源差異為3既有金融script、model及manifest，另runtime-manifest重產。前4檔與本輪前已核准SRPP main逐bytes相同，本輪不新增/改寫金融規則；不可誤稱相對舊sim僅metadata或程式不變。111/15 golden除bundle外全部JSON及calculator hashes須保持。沿用既有ignored日曆原bytes僅離線測試，禁止refresh來源。
- [ ] 635.5 獨立本機Codex spec/arch審查及相關測試後，兩repo feature commit、main no-ff merge/push/readback；merged tracked SQL insert-only精確新row四欄及舊rows不變；從乾淨asset main及正式設定只build/recreate必要business-services與srpp-simulator；驗business JAR五份authority原bytes及sim固定24來源/manifest，worker catalog.baseBundleSha256須等最終authority且既有設定中心純讀無SIMULATOR_POLICY_MISMATCH，不觸發任何模擬或為驗收改權限。保留business47設定env／完整53Env pairs、4mount，sim實際before/after全部設定/mount及8無關容器保持。SRPP實機只三個維護GET，區分維護PASS與正式US報告採用。使用者本session已授權Codex修正，Claude角色依賴按該授權處理，獨立CLI審查仍必須。

## 驗證

```bash
python3 scripts/check-srpp-report-authority.py --srpp-root /Users/steven/.codex/worktrees/us-watchdog-db-20261125/SRPP --check
SRPP_RELEASE_WORKTREE=/Users/steven/.codex/worktrees/us-watchdog-db-20261125/SRPP python3 -B scripts/tests/check-srpp-report-authority-test.py
ruby scripts/tests/srpp-report-policy-register-test.rb
ruby scripts/tests/srpp-policy-register-test.rb
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=SrppPolicyDocumentTest,SrppPolicyRegistryServiceTest,SrppReportModuleCalculatorTest test
```

SRPP新guard13測試覆蓋primary優先、只不存在fallback、錯schema／broken link／directory不fallback、實際schema不輸出run正文、只TOML或只DB不算部署、exact identity及readonly。新增工具同步inventory；既有event_rebound.py先前未登錄只補inventory一行及README名稱，不改其金融code。擴跑test_data_tools曾有5項因隔離feature缺既有ignored財務輸出fixtures及舊inventory缺漏失敗，原log保留；本輪只跑相關inventory及guard測試，不取新資料或補算財務輸出，不宣稱無關全套通過。

正式部署必在兩repo落地與registry驗證後，從asset main執行docker compose -p asset-management build business-services srpp-simulator及up -d --no-deps --force-recreate business-services srpp-simulator；等待兩容器healthy，驗JAR與sim固定鏡像/catalog及既有設定中心純讀。沒有App native更新證據時，守門員實際prompt保持PENDING_NATIVE_UPDATE，不以規則包部署替代。

## 完成報告

本輪總狀態為RELEASE_DEPLOYED_WITH_VERIFICATION_GAPS，來源、兩repo與registry已發布，必要兩服務已各一次build/recreate，保留native prompt與舊HostConfig兩項驗證缺口；635.3及635.5不勾成完全完成。沒有補報、金融來源refresh、POST模擬/驗證、草稿、寄信或交易。

最終bundle為`d86ee3266a191027fd61004130f1e99bbe6ccbb34b6bd88274cbc4668bdee554`，259來源比原256多正本ENTRY/helper/test三檔，三個實際file SHA精確等manifest保護值。SRPP model/runtime contract/Swagger原bytes未變，helper不加入金融required_reads或L0。原00a255測試前身分已撤銷；最終guard13測試+inventory1 PASS，原擴跑財務fixture5 errors與inventory1 failure保留，不宣稱全15通過。

SRPP feature為`27b91e8529e6df6f47ac8620a62d1e7ff4105c74`、main為`f92e95cca89dd2957d516567c61b9a1812624c67`；asset執行feature為`d02965924263053affa8c766bf9b1b6525191cc9`、執行main/image source為`ab2f0fc83e1931accc7be988eefd4b747185c1a6`。兩repo feature/main已push及ls-remote精確讀回、main no-ff且乾淨；21:52:39在已確認21:35季節no-op completed後安全ff實際SRPP runtime，未碰22:35正式報告。321個SRPP無關檔及91個asset primary檔bytes保持，3個本輪reports輸出明示task-owned排除。此完成報告為純文件收尾，後續文件main與image來源不同，原17份執行/authority來源及JAR/sim鏡像bytes保持，不為文件再次重建。

首輪spec Major指出既有simulator baseBundle身分相依，已由原export固定24來源+runtime-manifest同步。相對舊sim有5來源差異：3金融script、model與manifest；前4金融來源逐bytes等本輪前已核准SRPP main，本輪未新增金融規則，但不能說相對舊sim僅metadata。兩backend settings mirrors原bytes相同。24來源exact與export --check PASS，worker verify_runtime+descriptor純驗76fields PASS，未simulate/validate。authority4、Ruby6+9、Java108（含settings相關24）全PASS；111/15 golden除bundle外全JSON金融輸入輸出及calculator hashes相同。final spec-check BLOCK0/CHECK0，獨立本機Codex spec第2輪與arch首輪PASS，0 critical/major/minor；角色依賴依使用者同session授權Codex處理，未再呼叫已知受限Claude。

由merged tracked SQL insert-only transaction登錄，registry80→81，新row四欄全文等generator、舊80rows不變。兩服務從乾淨main及正式設定單次build/force-recreate且healthy：business container `de78ecd40a98577e06ad3d55cd38c7047366731d63fd0fd031907de02e1254d8`、image `sha256:6acfa162527b1653bacbdf119f485a7cbd5495616e28bcd8e51eae33bf00eed1`；sim container `794a0d8120bec741d63a7d7ebcc7aaf1b5daa36ccbfa72b88150aafa200e3322`、image `sha256:209e90a544082fa757a427b93137f605e9eaf072aad90557e3e43c0bf7675f16`。JAR五份authority及sim /app固定25份原文逐bytes等main，sim health/catalog兩GET200，bundle/model/catalog hash相符及76fields。

配置驗證分開回報：完整53/8 Env pairs、47/2 Compose keys、4/0mount及8個其他容器保持，business完整config PASS；sim目前明示安全/網路/資源配置精確符合未變正式main Compose。但sim舊HostConfig聚合hash與新值不同，before只存聚合hash，無原文/逐欄baseline，歷史指定收據亦查不到，有限診斷仍未定位，不宣稱全設定保持，不泛排除HostConfig或重build掩蓋；deployment驗收為PARTIAL_CONFIG_BASELINE_UNRESOLVED。

2026-11-25 21:55:45，root以既有已登入外部Chrome正常開設定中心，畫面76fields已載入、正式2.76.0，formal與draft基準bundle全文皆新d86，無SIMULATOR_POLICY_MISMATCH/503畫面。只展開版本識別、未儲存/模擬/發布，不讀token/cookie/header；這是UI實證，沒有擷取HTTP狀態或API原文。21:59:58，SRPP實機.venv skill三GET全部200及內容PASS（calendar365 AVAILABLE/TWSE21/quote40且40正價）；僅READ_ONLY_CONNECTIVITY_ONLY。

Native更新仍PENDING_NATIVE_UPDATE：本session沒有automation update工具且原生Codex介面存取受安全限制，未直接寫SQLite/TOML、未繞過限制。root已請使用者在原生App替換既有22-00-2全文；尚未收到已儲存確認。最新readonly checker顯示TOML與current DB仍舊prompt a91e1a...，expected為fe4226...，model/effort/rrule/status及其他設定before/after不變；helper存在與規則包部署不等於實際prompt已修復生效。原21:35/22:35與22:05/23:05、±300秒、原半開window及金融硬閘保留。

完整證據位於SRPP `reports/us-watchdog-release-20261125/`及主機`/tmp/srpp-us-watchdog-release-20261125/`；正式23:05守門採用、22:35美股報告流程/來源獨立性及交付仍NOT_YET_EXECUTED，不把昨夜缺證改稱PASS。本段不填自身提交SHA，避免純文件重複發布。
