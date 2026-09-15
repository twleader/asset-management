# [t429] 富邦交割銀行餘額改寫台北富邦台幣活存

**對應 Requirements:** Requirement 128（富邦 `bank_remain` 的合格餘額只覆寫最新快照既有的台北富邦銀行台幣活存，並原子重算總額）
**前置任務:** 無；沿用既有唯讀 adapter、configured active admin、最新快照鎖與聚合計算器。
**Liquibase changeset:** 無；不得新增或修改 table、column、index、migration 或 `db/schema.sql`。

> **現行契約覆蓋聲明（2026-09-15）：** 此任務的活存 target 規則已由 Requirement 152／Task 434 覆寫。現行實作只可覆寫既有台北富邦銀行／證券戶／TWD；不存在、重複或非 TWD 時必須 fail closed，不得新增、轉移、轉幣或改寫活存／任何其他存款列。下方 429.1–429.4 與完成報告僅保留當時規格與交付證據，不可作為後續實作指示。

## 背景

目前同步把富邦 `bank_remain.balance` 寫入 `證券戶`，但使用者明確確認該 SDK 回傳要對應的是畫面中既有的「台北富邦銀行／台幣活存」。舊映射會留下過期活存金額，並嘗試建立不代表使用者帳戶的 `證券戶` 列；這是錯誤資料，不是前端顯示問題。

富邦 adapter 已在同次 accounting critical section 驗證 `Result.is_success`、回傳 branch/account 與選定帳號精確相同、TWD、非負 `balance`／`availableBalance`，再輸出不含 raw identity 的 normalized DTO。此任務不改變 SDK 呼叫、帳號選擇、fingerprint、排程、feature flag、internal route 或券商端唯讀邊界；只修正 Java writer 對本地既有存款列的映射。

## 要做什麼

- [ ] **429.1 精確目標與 fail-closed 規則。** `FubonBankBalanceWriter` 仍在第一個 DB operation 鎖定 configured active admin 的最新 snapshot，並在鎖後 recheck owner、broker `fubon`、bank `fubon` 與存款類型 `活存` 都 active。候選集是同一 locked managed `deposits` collection 的全部 `bank.code=fubon`、`depositType=活存` 列，不先依 currency 過濾；0 候選回新增 typed outcome `TARGET_MISSING`，不建立 `BankDeposit`；多列回既有 `AMBIGUOUS_TARGET`；恰一列但 currency 非 `TWD` 也回 `AMBIGUOUS_TARGET`；恰一個 TWD 候選才可更新。不得選擇、建立、更新或刪除 `證券戶`，不得猜測其他銀行或幣別，也不得將美元列改成台幣。

- [ ] **429.2 金額與原子性。** 只將已驗證的 `balance` 以 HALF_UP scale 2 寫到同一個 managed 台幣活存 instance；`availableBalance` 不落地、不作 fallback。零值仍寫入且不刪列。既有 `notes`、`annualInterestRate`、`originalAmount` 均保持原值。呼叫唯一 `SnapshotAggregateCalculator.recalculate(snapshot)`、驗證所有 monetary aggregate 仍在 `NUMERIC(20,2)` 範圍並 `saveAndFlush`；child、`totalDeposit`、`totalAssets`、`estimatedAnnualDividend` 必須在同一 REQUIRES_NEW transaction commit，任何例外或 freshness failure 全數 rollback。SUCCESS 只可在 commit 後回傳。

- [ ] **429.3 Outcome 與既有邊界。** `FubonBankBalanceOutcome`、counter、sanitized manual response 與 focused tests 必須識別 `TARGET_MISSING`，不可把「缺少使用者既有活存」偽稱為 `BANK_MISSING`。bank 或 `活存` catalog inactive／不存在仍為既有 `BANK_MISSING`；broker inactive 為 `BROKER_MISSING`。不新增 BFF、frontend、9090、Tailscale、公開 API 或設定頁，但必須更新既有 `FubonApiInfoBffController` 與 `SchedulePublicBffController.JOBS` 的銀行餘額 target 文案及各自 BFF test，明確顯示「既有台北富邦銀行台幣活存」；不得新增 SDK 寫入、下單、轉帳或任何券商副作用。

- [ ] **429.4 回歸與真人資料邊界。** 更新真 PostgreSQL／Spring proxy 測試 fixture，使它以既有台北富邦 `活存` 作合法 target，證明該列 id、notes、rate、originalAmount、其他 deposits/funds/stocks 完整保留且總額正確。精確覆蓋候選集的缺列零 insert、重複、唯一非 TWD、合法 TWD，以及 `證券戶` 不被碰、inactive `活存`、零值、overflow、rollback、dry-run 零寫入與 full PUT concurrency；同步調整 unit test 的 typed outcome。`FubonApiInfoBffControllerTest` 與 `SchedulePublicBffControllerTest` 必驗既有兩筆資訊文案的 target 同步。所有 automated test 使用 fake adapter，不含真人帳號、餘額、token 或秘密。

## 驗證

```bash
bash scripts/spec-check.sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=FubonAccountingPostgresTest,FubonBankBalanceSyncServiceTest test
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml -Dtest=FubonApiInfoBffControllerTest,SchedulePublicBffControllerTest test
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
```

完成合併後，依 run-stack 從乾淨且已對齊的 main worktree rebuild/recreate **only** `business-services` 與 `bff`，確認健康與既有安全 GET。使用者本次已要求校正自己的餘額時，才可經既有內部權杖的 `dryRun=false` 手動同步執行一次唯讀 broker query；回應必為 SUCCESS，並以資料庫唯讀查詢確認 configured owner 最新快照的唯一 `fubon`／`活存`／`TWD` 列與回傳 `updatedAmount` 相同、aggregate 已一起變動。不得印出 raw account、token 或 SDK payload。

## 完成報告

（實作者做完後回填：實際改了哪些檔、focused／full test、Docker runtime 與授權的資料庫讀回證據，以及任何偏差。）
