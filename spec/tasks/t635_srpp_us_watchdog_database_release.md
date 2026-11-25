# [t635] 發布美股守門員資料庫入口與部署parity修復

**對應 Requirements:** Requirement 187（SRPP不可變日報計算包規則身分與同輸入一致性）
**前置任務:** 無
**Liquibase changeset:** 無

## 背景

2026-11-25盤前readiness確認，既有Codex守門員22-00-2完整prompt仍指定sqlite/codex-dev.db，實際當前排程及近期run在sqlite/codex.db；舊DB僅保留10月及09:45舊名稱，可能使守門員找不到當日精確22-00執行。維護只修此DB入口，不改季節/日曆gate、targetSlot至watchSlot半開window、卡住判定、一次精確收斂、模型、時段、投資規則或金融硬閘，也不補跑報告。

## 要做什麼

- [ ] 635.1 SRPP建立既有22-00-2完整受版控prompt正本，只替換DB入口一段，其餘條文逐字保留。獨立us_watchdog_entry.py提供--database-schema，CODEX_HOME未設/空白用家目錄.codex，優先sqlite/codex.db，只有路徑不存在才fallback codex-dev.db。存在但空/錯schema/無資料/無法讀或broken symlink／directory都不換舊DB；readonly URI及query_only，僅PRAGMA automations與automation_runs實際schema，不假設started_at、不讀run正文、不寫DB／檔／排程、不授權停止。
- [ ] 635.2 --check-deployed只核US守門員，完整正本prompt須同時等於automation TOML與選定當前DB的既有22-00-2 row，並核精確id/name、ACTIVE、原rrule/project。只改TOML或只改DB皆PENDING_NATIVE_UPDATE。該檢查不依賴TW入口parity、不接金融L0、不證明run存活／守門已執行或金融採用。model/effort及其他設定另以實際before/after metadata比對保留，不改主排程22-00。昨夜來源獨立性與讀取覆蓋缺口仍屬未驗證，不改成PASS。
- [ ] 635.3 正常部署只經App可用更新入口改既有22-00-2的prompt、保留其他設定；工具不可用則保存可貼上的正文與PENDING_NATIVE_UPDATE，不直接寫SQLite、不改TOML冒稱DB同步、不繞過App安全限制。這項native缺口不阻擋已授權其餘bundle發布，但最後須分開回報。發布前核時及主排程活躍證據，避免22:35真報告起跑後改源混版；若接近合法起跑且有活躍報告，暫停runtime改源與依賴部署，不補報或停止正常報告。
- [ ] 635.4 資產端只同步最終SRPP manifest/model原bytes、由原generator產生formal policy、insert-only SQL與兩份golden；model/Swagger/formula與金融Java程式不改。另用SRPP原export_settings_runtime.py固定24來源同步srpp-simulator/runtime並--check；舊鏡像落後，5來源差異為3既有金融script、model及manifest，另runtime-manifest重產。前4檔與本輪前已核准SRPP main逐bytes相同，本輪不新增/改寫金融規則；不可誤稱相對舊sim僅metadata或程式不變。111/15 golden除bundle外全部JSON及calculator hashes須保持。沿用既有ignored日曆原bytes僅離線測試，禁止refresh來源。
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

準備中，未宣稱native prompt已更新、守門已執行或美股報告已交付。
