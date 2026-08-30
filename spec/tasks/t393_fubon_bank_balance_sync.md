# [t393] 交割銀行餘額查詢排程——每日四時段更新存款與快照總額

**對應 Requirements:** Requirement 128（每天 08:00／09:30／14:00／22:00 唯讀查詢富邦交割銀行餘額，更新最新快照的台北富邦銀行證券戶；只使用既有 table／columns）
**前置任務:** 無；重用既有 Fubon adapter、configured admin、共用快照鎖與聚合計算器。
**Liquibase changeset:** 無；不得新增表、欄位、索引或變更 `db/schema.sql`。

## 背景

2026-08-30 接手審查確認原實作有 raw account 跨服務、HTTP／owner 查詢包進寫入 transaction、零餘額被拒絕、children 更新後總額未更新等缺口。本任務要修復這些缺口，不能把原完成報告的例外當成授權。

既有 schema 的 `bank_deposit.amount` 是 `NUMERIC(20,2)`，`original_amount` 是 `NUMERIC(20,4)`，`currency` 是 `VARCHAR(20)`，並非原任務誤寫的三字元欄位。`asset_snapshot` 的金額總計是 `NUMERIC(20,2)`。既有銀行 `code=fubon` 為台北富邦銀行；既有存款類型 `證券戶` 用於本任務。

[富邦官方銀行餘額契約](https://www.fbs.com.tw/TradeAPI/docs/trading/library/python/accountManagement/Balance/) 已於 2026-08-30 核實：`sdk.accounting.bank_remain(account)` 回傳 `Result.is_success/data/message`，成功 `data` 為 `BankRemain`，含字串 `branch_no/account/currency` 及整數 `balance/available_balance`，範例也有十進位整數字串。此契約沒有來源日期或銀行名稱；不得製造 `sourceDate` 或宣稱由回應辨識出交割銀行。台北富邦銀行是使用者指定的本地對應。官方 schema 已核實不等於此部署已做真人帳號驗證。

## 要做什麼

- [x] **393.1 排程與 gate。** `FubonBankBalanceSyncScheduler` 同一方法註冊四個 `@Scheduled`：`0 0 8 * * *`、`0 30 9 * * *`、`0 0 14 * * *`、`0 0 22 * * *`，時區全部 `Asia/Taipei`。每天執行，週末與休市日不略過。獨立 `AtomicBoolean inFlight` 只防單程序重入，不可冒充跨程序 DB 互斥。先檢查 `FUBON_BANK_BALANCE_SYNC_ENABLED`，再檢查 `FUBON_ENABLED`／既有 `FubonConfigState.READY`；停用或未設定時零 SDK、HTTP 與資料寫入。沿用 Python `_accounting_lock`、共用 5 calls/sec 帳務 budget、5 秒單次 timeout、僅 auth-invalid 可重登一次；不改既有 LIVE／inventory／trade 的互斥政策。`.env.example` 加新 flag 預設 `false`，Compose 的 business-services 白名單傳入；不修改既有部署的全域啟用值或秘密。

- [x] **393.2 Python 先驗來源，再產生自有 DTO。** `read_bank_balance()` 使用既有 `_accounting_call("bank_remain", selected.raw)`；在同一 accounting critical section 擷取 selected account、該次回應及 token，避免分兩次取 session 而把重登前後帳戶混用。檢查 `is_success is True`、`data` 非空且形狀正確；缺少信封、`None`、錯型別皆為 `RECONCILE_FAILED`，不得接受裸物件備援。selected stock account 類型及唯一選擇沿用既有登入規則；`data.branch_no`、`data.account` 必須存在、是非空字串且各自與 selected raw identity 精確相等。缺值不可透過 `None == None` 當匹配，也不得用 selected 值填補缺欄。`currency` 必須為 `TWD`；兩金額接受零與官方整數／整數字串，拒絕 bool、負數、非有限、指數表示及超界值；不因 available balance 為零拒絕整批。

  驗證通過才沿用 `HMAC-SHA256(internal-token, branch_no + ":" + account)` 截前 24 個 hex 的 `accountFingerprint`。raw identity、SDK object、token、回應 message、秘密路徑不得跨 adapter、記 log、寫 DB 或回 Java。`queryDate` 是呼叫前捕捉的台北日期，`observedAt` 是成功查詢的觀察時間，不是 vendor timestamp；查詢跨台北日界整批拒絕。

- [x] **393.3 固定 internal 契約。** Python exact token-protected `POST /internal/bank-balance/read` 不接受帳戶 selector 或 request body，回 normalized `{queryDate, observedAt, accountFingerprint, currency, balance, availableBalance}`；金額為 canonical decimal string。Java `FubonDtos.BankBalance` 用 `LocalDate/Instant/String` 及欄位限定的 `CanonicalFubonDecimal.NonNegativeDeserializer`；重用現有 `parseNonNegative()`，不得放寬 quote／portfolio 正值欄位的 class-level deserializer。Python／Java 都驗 fingerprint 格式、觀察時間不得在未來、`queryDate` 與 `observedAt` 台北日期一致、金額 `precision<=20, scale<=10` 及非負。不能把 queryDate 改名成 sourceDate。

  Java exact `POST /internal/brokers/fubon/bank-balance-sync?dryRun=true|false` 預設 `true`，由獨立 `FubonBankBalanceInternalTokenFilter` 保護。Controller 只委派；response 只含 `{outcome,dryRun,reason,updatedAmount}`，只有 commit 成功才回 updatedAmount，不外露 fingerprint/account。不得新增 BFF、frontend、9090、gateway 或 Tailscale route。

- [x] **393.4 外部 preflight 與 DB writer 分離。** `FubonBankBalanceSyncService.syncScheduled()/syncManual()` 是 orchestrator，以 `Propagation.NOT_SUPPORTED` 保證不繼承 caller transaction。先驗 flags/config、`configuredAdmin()` 存在且 active/admin，再做 adapter HTTP 與完整金額預驗。`dryRun=true` 也完成來源／數值驗證，但不進 writer、不拿 mutation lock、不寫 DB。

  另建 Spring bean `FubonBankBalanceWriter`，其 `@Transactional(propagation=REQUIRES_NEW)` 公開方法由 orchestrator 經 proxy 呼叫，**第一個 DB operation** 就是 `AssetSnapshotMutationLock.lockLatestForOwner(ownerId)`。不得先查 app_user、broker、bank、children，不能弱化成「第一個 snapshot 相關動作」，也不能先查 latest id 再鎖。HTTP 不得在此 transaction 發生。查無快照回 `NO_SNAPSHOT`，不建立快照、不改快照日期。鎖後重驗 snapshot owner、configured admin 仍 active/admin、`broker.code=fubon` active、`bank.code=fubon` 與存款類型可用；跨日或觀察距 commit 超過 60 秒回 `STALE_QUERY`，不沿用前日查詢。

- [x] **393.5 children 與 aggregate 原子更新。** 只從已鎖 snapshot 的 managed `deposits` collection 找同 `bank=fubon, depositType=證券戶` 的列。零列才新增，一列才更新，兩列以上回 `AMBIGUOUS_TARGET` 整筆 rollback，不能任選第一列；既有目標若是其他幣別也拒絕，不能將美元存款改成台幣。新增列必須 setSnapshot 並加入 `snapshot.getDeposits()`；更新修改同一 managed instance，不能只 repository.save 新列而讓 calculator 看不到它。

  `amount=balance.setScale(2, HALF_UP)`，捨入後再次驗 `precision<=20`（整數最多 18 位）；零照寫、不刪列。新增 `currency=TWD, originalAmount=null, annualInterestRate=null, notes=null`；更新保持既有 note/rate，保持台幣 originalAmount 為 null。availableBalance 不落地。其他存款／基金／股票完整保留。呼叫唯一 `SnapshotAggregateCalculator.recalculate(snapshot)`，檢查 monetary aggregates 在 `NUMERIC(20,2)` 內，再 `snapshotRepository.saveAndFlush(snapshot)`；children 與 totals 必須同 transaction commit 或全部 rollback。手動修改的同目標金額仍會在下一次合格同步被覆寫；不授權改別的銀行或非最新快照。

- [x] **393.6 outcome 與註冊。** 獨立 `FubonBankBalanceOutcome`／process-local counters 至少含 `DISABLED, BANK_BALANCE_SYNC_DISABLED, MISCONFIGURED, NO_OWNER, BROKER_MISSING, BANK_MISSING, NO_SNAPSHOT, AMBIGUOUS_TARGET, STALE_QUERY, BANK_BALANCE_FAILED, DRY_RUN, SUCCESS, ROLLED_BACK`。commit 失敗不可記 SUCCESS。`SchedulePublicBffController.JOBS` 的 BUSINESS／券商庫存項目顯示「每日 08:00／09:30／14:00／22:00」，cron、註冊數測試同步。API 盤點 `httpEndpoint` 固定 adapter `POST /internal/bank-balance/read`，Java 手動路徑放 consumer；有正式呼叫路徑且通過成功／失敗測試才標 connected=true，不是因旗標宣告就算接妥。

- [x] **393.7 有效驗收。** Python 覆蓋官方 Result、錯／缺 account、缺 branch、錯幣別、零值、auth 重試跨 session 一致性、raw sentinel 不外洩。Java 以真正 Spring proxy 證明 HTTP 時沒有 transaction、writer 第一 SQL 為共用 lock；PostgreSQL/Testcontainers 驗新增及更新後 clear persistence context 讀回 children 與 totalDeposit/totalAssets/estimatedAnnualDividend 一致。涵蓋全零、18 位整數界線、aggregate overflow、duplicate target、owner 不符、bank 缺席、最新快照變更、同時 full PUT、rollback、dryRun 零寫入。不可只以 Mockito 或 jar class 字串存在當成功。

## 驗證

```bash
bash scripts/spec-check.sh
docker buildx build --platform linux/amd64 --target test -f fubon-broker-service/Dockerfile fubon-broker-service --load -t asset-fubon-broker-service:test
docker run --rm --platform linux/amd64 asset-fubon-broker-service:test pytest -q
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test
```

實作後使用 run-stack 從實際驗收 worktree rebuild/recreate `fubon-broker-service business-services bff`，確認 runtime 註冊與 auth／disabled 行為，保留既有運行設定。真實帳號、餘額與 production DB 不作測試資料；用獨立 fake adapter 與 PostgreSQL 驗收，分開列明真人驗證限制。不能因 flag 預設 false 而略過成功路徑測試。

## 完成報告

**程式、隔離測試、獨立架構與 feature Docker 驗收已完成；第二個 session 整體仍待394／395來源契約。** Backend完整1,854項、BFF242項、Python676項全數通過，沒有跳過。29項真PostgreSQL／Spring proxy測試確認HTTP無transaction、writer首個DB操作為共用最新快照鎖、managed存款與所有總額同交易提交；涵蓋新增／更新／零值、18位整數、溢位、owner變更、雙向全量PUT併發、flush與commit失敗回滾。29項MVC測試確認dryRun只接受省略或單一小寫true/false；別名、重複及未知參數零service呼叫。正常資料由隔離fake adapter提供，不是實帳餘額或真券商權限驗證。

獨立審查另以隔離MockMvc重現三個新token filter的編碼路徑繞過：修前9案未經token檢查即可呼叫fake service；已讓raw／decoded／servletPath一起識別受保護範圍，再拒絕所有非raw exact別名。修後45次別名請求皆404且零service呼叫，focused47項與完整backend1,854項全數通過，沒有對正式端點送出測試POST。

共用實機驗收見 [Task386接手更新](t386_fubon_api_documentation_view.md)：四個服務從此feature rebuild/recreate且healthy、class/source雜湊與測試產物相符，內部disabled GET及登入後盤點／排程頁已驗。全域FUBON_ENABLED保持false，新flags保持false，正向資料回寫只在隔離PostgreSQL／Redis測試，未啟用真人券商查詢。這不構成第二個session已完成或已push。
