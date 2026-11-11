# [t571] 每日庫存保留期由66個曆月改為42個曆月（3.5年）

**對應 Requirements:** Requirement 94（年度配息以除息日前一日持股推算；每日台美股庫存是其歷史持股依據，本任務只改保留期限）
**前置任務:** t555（每日庫存保存）、t556（66個曆月保留與過期清理，已在main）
**Liquibase changeset:** v1.164.0-daily-inventory-retention-42-months.sql

## 背景

使用者2026-11-11確認：每日紀錄的目的是精確推算配息（除息日前一日實際持股），只需保留3.5年，超過的刪除。股票部分已由Task555每日保存台股／美股各自交易日庫存（`daily_inventory_snapshot`＋`daily_inventory_position`），Task556定義保留期為66個曆月（5.5年）。本任務推翻Task556的66個月，改為**42個曆月**；其餘保存、清理與guard機制全部沿用。使用者已明確決定：不新增基金／現金每日紀錄、不刪除手動建立的年度 `asset_snapshot`（2020年起的紀錄全部保留）。

現行機制（不修改的部分，實作者須知道它們為何會自動套用新期限）：

- DB函式 `daily_inventory_retention_cutoff(p_market TEXT, p_at TIMESTAMPTZ) RETURNS DATE`（v1.163.0建立）：null→ERRCODE 23514；`台股`取 `p_at AT TIME ZONE 'Asia/Taipei'` 的當地日期、`美股`取 `America/New_York` 當地日期，其他market→23514；回傳 `(local_today - INTERVAL '66 months')::DATE`。
- trigger `guard_daily_inventory_retention`（兩表 BEFORE INSERT/UPDATE/DELETE）、`JdbcDailyInventoryHistoryRepository.deleteExpired()`（REQUIRES_NEW、以DB `statement_timestamp()` 選取並鎖定已過期header固定id集合，先刪position再刪header）、`DailyInventoryScheduler.tick()` 每分鐘先清理再保存，以及 `JdbcAnnualDividendReadRepository` 兩條daily讀取SQL，**全都在執行時呼叫上述函式**。因此只要替換函式本體，全系統即改用42個月。

## 要做什麼

- [x] 571.1 新增 `backend/src/main/resources/db/changelog/changes/v1.164.0-daily-inventory-retention-42-months.sql`：Liquibase formatted sql，`--changeset steven:v1.164.0-daily-inventory-retention-42-months splitStatements:false`，`--comment` 說明Task571把66個曆月改為42個曆月。內容只有一段 `CREATE OR REPLACE FUNCTION daily_inventory_retention_cutoff(p_market TEXT,p_at TIMESTAMPTZ) RETURNS DATE LANGUAGE plpgsql STABLE`，與v1.163.0函式逐字相同，唯一差異是 `INTERVAL '66 months'` → `INTERVAL '42 months'`（簽章、null／未知market以ERRCODE 23514拒絕、兩市場時區全保留）。不得修改已發布的v1.162.0／v1.163.0檔（含註解，checksum會變）。在 `db.changelog-master.yaml` 的v1.163.0 include之後加入同格式include（`relativeToChangelogFile: false`）。檔案可安全重跑。
- [x] 571.2 Java production程式不改：guard trigger、清理adapter、scheduler與年度配息SQL都不動。42至66個月之間的資料自套用起即過期，由下一tick既有原子清理移除、trigger拒絕重灌；正式資料最早為2026-11-05，套用時不會刪到任何正式資料。不刪 `asset_snapshot`、`stock_holding`、`asset_transaction`、股利或其他任何表資料；不改保存時點、份數、來源或年度配息算式。
- [x] 571.3 `bff/src/main/java/com/steven/assets/bff/schedulelist/SchedulePublicBffController.java` 中「每日台美股庫存歷史保存」項目描述的 `最多保留5.5年（66個曆月）` 改為 `最多保留3.5年（42個曆月）`，其他文字不變；JOBS仍82筆，不新增@Scheduled、API、頁面、9090路由。同步 `SchedulePublicBffControllerTest` 的斷言（原 `"5.5年", "66個曆月"` 改為 `"3.5年", "42個曆月"`），並另斷言 `doesNotContain("5.5年", "66個曆月")`，防止追加而非替換。
- [x] 571.4 更新 `backend/src/test/java/com/steven/assets/repository/JdbcDailyInventoryRetentionTest.java`（真PostgreSQL 16 Testcontainers）：`retention()` 改為依序套用v1.163.0與v1.164.0（真實上線順序）；`cutoff()` 改 `minusMonths(42)`；測試名稱 `sixtySix...` 改為表達42個月；期望值改以42個月重算——台股 `2026-08-31T04:00:00Z`→`2023-02-28`、台股 `2025-08-31T04:00:00Z`→`2022-02-28`、需另加一個閏年月底案例：台股 `2027-08-31T04:00:00Z`→`2024-02-29`；台股 `2026-11-06T02:00:00Z`→`2023-05-06`、美股同一瞬間→`2023-05-05`；未知market／null仍拒絕。新增一個案例：以舊migration（只到v1.163.0）存入trading_date介於42與66個月之間的header＋position（在66月規則下未過期），套用v1.164.0後 `deleteExpired()` 能原子清除它、邊界當日（cutoff當天）保留，且trigger拒絕再插入該區間日期。其餘既有測試（rollback、guard、首tick等）在新期限下全部維持通過。
- [x] 571.5 `backend/src/test/java/com/steven/assets/repository/JdbcAnnualDividendReadRepositoryTest.java`：維持其現有簡化表結構（不套v1.162.0），在既有讀入v1.163.0之後再讀入 `v1.164.0-daily-inventory-retention-42-months.sql`。過期fixture改為 `((local_today - INTERVAL '42 months')::date - 1)`；另加一筆trading_date為 `local_today - INTERVAL '50 months'`（介於42與66個月之間）的header＋position，此筆須與既有過期fixture一樣在讀入v1.163.0之前插入（guard會拒絕已過期日期的INSERT），斷言年度配息daily讀取排除它——此筆在只有v1.163.0時不會被排除，用以證明v1.164.0確實生效。維持「查詢排除尚未物理清除的過期daily、legacy fallback不當零」語意。
- [x] 571.6 收尾：依 `db/schema.sql` 檔頭「重新產生」段指令從運行DB重產該檔（預期只有函式本體 `66 months`→`42 months` 差異，表數不變），並跑 `bash scripts/tests/schema-sql-drift-test.sh` 通過。完成報告回填本檔。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true '-Dtest=*DailyInventory*,JdbcAnnualDividendReadRepositoryTest,AnnualDividendCalculatorTest,AnnualDividendServiceTest' test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml -Dtest=SchedulePublicBffControllerTest test
# run-stack：從與main一致的.env重建並recreate business-services與bff（recreate business後restart bff），
# 確認databasechangelog有v1.164.0 EXECUTED，SELECT daily_inventory_retention_cutoff('台股',now()) 等於台北今日減42個月，
# 每日庫存兩表筆數不因套用而減少（正式資料皆在期限內），BFF排程清單文案為3.5年（42個曆月）且仍82筆。
bash scripts/tests/schema-sql-drift-test.sh
```

## 完成報告

2026-11-11 feature驗收完成：

- spec兩輪獨立審查（第一輪1 major／3 minor、第二輪2 minor）全數修正；diff-scoped arch review無critical／major（唯一minor為schema重產，已完成）。
- Backend 43個（含JdbcDailyInventoryRetentionTest 5個真PostgreSQL 16）、BFF SchedulePublicBffControllerTest 28個全部通過，零失敗。新案例證明42～66個月資料在只有v1.163.0時保留、套v1.164.0後被原子清除且讀取排除，cutoff當天保留。
- business-services與bff以--no-cache重建並recreate，.env與main一致；v1.164.0 EXECUTED，台股／美股cutoff皆為2023-05-11；每日庫存筆數套用前後相同（台股SAVED 12、美股SAVED 8／EMPTY 4），正式資料無任何刪除。運行中bff jar文案為「3.5年（42個曆月）」，JOBS仍82筆。
- db/schema.sql依檔頭重產，差異僅函式interval一行，drift test逐位元通過，仍119張表。
