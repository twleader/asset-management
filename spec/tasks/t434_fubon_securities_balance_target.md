# [t434] 富邦交割銀行餘額改寫既有台北富邦銀行證券戶

**對應 Requirements:** Requirement 152（富邦 `bank_remain.balance` 僅更新最新快照既有的台北富邦銀行證券戶／TWD，並原子重算總額）
**前置任務:** t429（歷史活存 target 規則由本任務覆寫）；沿用既有唯讀 adapter、configured active admin、最新快照鎖與 aggregate calculator。
**Liquibase changeset:** 無；不得新增或修改 table、column、index、migration 或 `db/schema.sql`。

## 背景

畫面中的既有「台北富邦銀行／證券戶／TWD」餘額未更新，因 writer 把已驗證的富邦 `bank_remain.balance` 對應至不同的「活存」列。這是本機 target mapping 錯誤，不是前端刷新問題；既有 adapter 的 Result/data、指定帳號身分、TWD、非負整數、日期與新鮮度驗證已可通過，且本任務不放寬它們。

## 要做什麼

- [ ] **434.1 精確 target 與 fail-closed 規則。** `FubonBankBalanceWriter` 第一個 DB operation 仍為 configured active admin 最新 snapshot lock，鎖後重驗 owner、broker `fubon`、bank `fubon` 與 deposit type `證券戶` 都 active。候選集限同一 locked managed deposits collection 中全部 `bank.code=fubon`、`depositType=證券戶` 列，且不先用 currency 篩選；0 列回 `TARGET_MISSING` 且不 insert，多列回 `AMBIGUOUS_TARGET`，唯一列但非 `TWD` 也回 `AMBIGUOUS_TARGET`。只有唯一既有 TWD 列可更新；不得新增、刪除、轉移、轉幣、改 type 或更新 `活存`、其他銀行或其他列。
- [ ] **434.2 金額與原子性。** 僅把既有完整驗證的 `balance` 以 HALF_UP scale 2 寫入該 managed target amount；`availableBalance` 不落地、不作 fallback。0 仍寫入且不刪列；保留 `originalAmount`、`notes`、`annualInterestRate`。以既有 `SnapshotAggregateCalculator.recalculate` 重算 aggregate、驗 column precision 並在同一 `REQUIRES_NEW` transaction `saveAndFlush`；commit 失敗不得回 SUCCESS。
- [ ] **434.3 範圍與資訊頁。** 不改 broker reader、Result/data、identity、currency、integer 或 freshness validation；不改 feature flag、cron、內部 endpoint、BFF route、schema 或 migration，且不新增 manual sync。`FubonApiInfoBffController` 與 `SchedulePublicBffController.JOBS` 的既有 `sdk.accounting.bank_remain`／銀行餘額文案要明確說明既有台北富邦銀行證券戶／TWD、0 可寫、缺列／重複／非 TWD fail closed。排程 cron 與 Asia/Taipei zone 維持既有四個 slot。
- [ ] **434.4 測試。** 更新 writer 的 PostgreSQL tests，驗唯一證券戶更新、0、metadata preservation、活存零 mutation、缺 target、重複 target、唯一非 TWD target、aggregate 同 transaction 與 rollback；更新兩份 BFF 靜態資訊文案測試，並保留／更新 scheduler cron、Taipei zone 的回歸測試。不得在測試或驗收呼叫真實 SDK、下單、改單、撤單、匯款、圈存、轉帳或任何券商寫入。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=FubonAccountingPostgresTest,FubonBankBalanceSyncSchedulerTest,FubonBankBalanceSyncServiceTest test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml -Dtest=FubonApiInfoBffControllerTest,SchedulePublicBffControllerTest test
```

本次使用者明確限制不重建 Docker、不中斷既有服務、不呼叫 manual sync 或富邦 SDK；因此本交付只以單元與隔離 PostgreSQL regression tests 驗證，未宣稱完成 runtime 驗收或合併。

## 完成報告

（實作者完成後回填實際變更、測試輸出與任何偏差。）
