# [t399] 富邦成交整批交易與 configured-admin 快照讀寫修復

**對應 Requirements:** Requirement 90（富邦庫存唯讀查詢與指定管理者快照局部同步）、Requirement 120（成交紀錄只新增且冪等）、Requirement 128（交割銀行餘額更新指定管理者最新快照）
**前置任務:** t352、t385、t393 已交付的功能切片
**Liquibase changeset:** 無

## 背景

本任務基於 main `5197d42aab14a1889d42fc60454b85437a8b30c4`，是使用者要求全面檢查富邦程式後可獨立處理的修復單元，不新增券商能力。既有需求已要求單一交易、冪等新增、configured-admin owner 及 snapshot row lock；本任務恢復這些既定行為，不修改功能開關、排程或對外契約。

`FubonTradeSyncService.processTrades()` 目前逐筆呼叫 repository `save()`，每筆可能各自提交，並將所有 `DataIntegrityViolationException` 視為重複。若後段出現非重複的 DB 錯誤，前段可能已永久寫入；註解以 PostgreSQL unique violation 會中止交易為由偏離整批原子性，但既有 partial unique index 可用精確 `ON CONFLICT ... DO NOTHING` 處理重複而不丟棄整批交易。

補查確認 `FubonHttpClient.readFilledTrades()` 目前只有反序列化，service 也只零散檢查 side／amount，尚未完整驗證 normalized batch。故「整批先驗證、再寫入」也必須補齊既有 adapter 契約在 Java 的防禦檢查，不能把 Java record 型別視為欄位語意已全部成立。

`AssetSnapshot` 的 tenant filter 在具有 HTTP request、但沒有一般使用者 tenant headers 的內部同步情境，會把一般 JPQL／derived snapshot 查詢限制到未登入 owner。富邦流程雖已由伺服器解析 configured-admin，銀行 writer 的 latest lock 與庫存 preflight 仍用這些查詢，可能找不到正確快照。現有 bank PostgreSQL 測試未同時涵蓋 request context、Spring tenant aspect 與真 repository；這是靜態控制流發現，尚非本任務已完成的測試證據。

## 要做什麼

- [x] **399.1 邊界與編輯範圍。** 僅處理 backend 的成交 writer、富邦庫存／銀行快照讀取及相關 repository／lock adapter／測試。所有券商互動只用 fake `FubonBrokerClient`，不登入、不呼叫真人券商，不啟用 `FUBON_ENABLED` 或任何新功能開關。不修改外部行情來源、`MarketDataService`、BFF、前端、排程、schema 或一般使用者 CRUD 契約。Task 394／395 仍未完成、維持來源未核實的 no-write 預檢，不建立交割或已實現損益 writer。其他 worktree 有未提交變更的同路徑不得編輯；新 dependency 檔案也必須先查重疊。

- [x] **399.2 成交使用真正的短交易 writer。** SDK 查詢、補齊後的完整 DTO 驗證及名稱解析一律放在 DB 寫入交易外；以獨立 Spring bean／proxy 的短 `@Transactional` writer 一次寫入整批已準備資料，禁止同物件 self-invocation 假交易。writer 依賴 repository 介面；repository 對每筆使用精確 owner／filled-no conflict target 的 insert-only SQL：`ON CONFLICT (owner_user_id, broker_filled_no) WHERE broker_filled_no IS NOT NULL DO NOTHING`。update count 1 才計新增、0 才計既存；不可 `DO UPDATE`、不可不限定 conflict target，也不可 catch 所有 integrity error 後當成 duplicate。任一非重複 insert／flush／commit 失敗，整批 rollback；`SUCCESS` 與其 counter 只能在交易 proxy 成功返回、commit 完成後記錄。保留既有 `ROLLED_BACK` 錯誤處理與消毒結果，不能在 DB rollback 後回傳正新增數。

- [x] **399.2a 完整 normalized 批次驗證早於任何寫入與空批次成功判定。** 沿用 Python `TradeReadService` 已輸出的契約，不自行核實或補造 raw 身分：batchId 非空且符合 `[A-Za-z0-9_-]{1,64}`；accountFingerprint 為既有 HMAC 的 24 位小寫十六進位字串（僅驗形狀，不能宣稱這等同 Java 再驗 raw 帳號）；startDate／endDate 非 null、與該次 request 精確相同且合法有序、差值不超過 7 日，當日排程仍 start=end=捕捉的台北 queryDate。trades 必為非 null list，emptyConfirmed 必精確等於 list 是否為空；每列非 null，stockCode 符合 `[0-9A-Z]{2,10}` 且非 `0000`，side 精確 Buy／Sell，filledQty exact integer 為 `1..9,999,999,999`，filledPrice／filledAvgPrice 正值 canonical decimal，precision≤20、scale 0..10，filledDate 非 null 且在本次 request 範圍，filledNo 為長度 1..50 的非空字串，filledTime 為非空字串。filledTime 尚無更細格式契約，不可臆造格式或推算日期；同批與已保存 filledNo 的既有冪等語意不變。任一列無效，整批 `TRADE_FAILED`／零 writer，不因名稱未解析或資料已存在而略過驗證。client 使用只限成交回應的嚴格 codec：必填欄、未知欄、重複 key、尾隨 JSON、null／缺少 primitive、數值轉字串、浮點或文字轉整數、文字／數值轉 boolean 均拒絕；日期只接受有效 ISO 字串，不接受 array。可重用現有 exact shares／date deserializer 與嚴格 mapper 組裝，但不得改動其他 broker read 的 codec／wire 行為。service 對 typed batch 再驗上述語意，不能只依賴 HTTP client；DTO 仍不可變 record，複製 list 防後續變動。不將 fingerprint／逐筆成交資料輸出到回應或 log。

- [x] **399.3 保留原成交資料語意並尊重儲存精度。** owner 只來自 ACTIVE configured-admin，broker 必須為 active `code=fubon`；writer 開始寫入前重驗同一 owner 與 broker，呼叫者不能指定其他人。仍只新增未記錄的成交，已存在的資料即使經使用者手動編輯也不得覆寫。名稱仍由 `StockMasterService.resolveNameLocalOnly(code, "台股")` 解析；未解析時只跳過該筆，不以代號代替名稱。Buy／Sell 映射「買／賣」，assetType「股票」、market「台股」、currency「TWD」、channel「富邦證券」、source `FUBON_SYNC`；`fee`／`transactionTax`／`exchangeRate`／`notes` 皆為 null。以原始 price×shares 計算 amount 後才 HALF_UP 至 2 位，amount precision 不超過 20。`db/schema.sql` 現有 shares 為 NUMERIC(15,5)、price 為 NUMERIC(17,6)、amount 為 NUMERIC(20,2)，不新增欄位或放寬 precision；必須在任一寫入前拒絕不能以相同數值精確存入 shares／price 的整批，不得依 DB 隱性捨入改變來源成交均價。已有 partial unique index `ux_asset_transaction_owner_broker_filled_no`。空確認、dry run、未解析名稱與既存數的既有 outcome／回應 shape 不變。

- [x] **399.4 富邦專用 explicit-owner snapshot 查詢。** 建立限縮於已驗證 configured-admin 同步流程的 repository read／lock 方法，SQL 明確含 owner 條件，latest 仍依 snapshot_date 降冪取一筆，lock 必須取得同一張 `asset_snapshot` 的 PostgreSQL row lock。不得關閉／放寬全域 tenant filter，不改一般 `findLatest`、`findById`、user CRUD 或共享 generic lock 方法的授權語意，不提供 public endpoint 或讓 request header／參數選擇 owner。可在既有 mutation-lock service 新增具名的富邦用方法，與一般 CRUD 仍鎖同一 row；不得建立互不相通的 process lock 取代 DB lock。只把富邦 inventory preflight、inventory writer、bank writer 的適當呼叫端切換到此入口；一般畫面用的 ownership 判定保持既有 tenant 行為。

- [x] **399.5 保留鎖內重驗與部分更新。** snapshot writer 交易的第一個 DB 動作仍是 owner-latest row lock；owner/config/broker/bank/children 的任何重驗不得提前至鎖前。鎖後必須重驗當前 ACTIVE configured-admin 的 id 與 snapshot.owner_user_id 一致，維持庫存「最新且台北今日」及銀行餘額既有新鮮度限制。銀行仍只更新 active 台北富邦銀行的 TWD 證券戶一列；重複／幣別不符拒絕、不動其他銀行；庫存仍只替換富邦台股 scope，保留其他 children。managed collection、唯一 aggregate calculator、amount／totals NUMERIC(20,2) 驗證、失敗整批 rollback、beforeCommit 時間重驗及 afterCommit backfill 均不得退化。來自其他 owner 的 tenant header 不得改變 configured-admin 目標；一般使用者 repository 查詢仍依自己的 tenant 隔離。

- [x] **399.6 有意義的回歸測試。** 使用真 PostgreSQL 16 Testcontainers 與真正 Spring transaction proxy，涵蓋同批前列成功、後列非重複 constraint 失敗的完全 rollback，commit 失敗不得 SUCCESS，兩個並行 writer 對同一 owner／filled-no 只有一筆、不同 owner 不互相誤判，duplicate 不改寫手動編輯列。精度覆蓋可精確存入 price 邊界、超出 scale 且非 trailing-zero 值、amount overflow 與最後一步捨入。fake HTTP response 經真 codec 驗 malformed／missing／錯型 primitive、正確 ISO 日期及正常完整批次；service fixture 驗後列非法也零 writer、空確認不一致、錯 request 日期／fingerprint 形狀，以及已存在或未解析名稱也不可藏住非法列。snapshot 回歸必須把真 repository、tenant aspect、request context 與 configured-admin 放在同一測試中：沒有 tenant headers 的合法內部流程成功、其他 owner header 不改目標、普通使用者仍看不到他人資料；並保留與完整 snapshot update 的兩種鎖先後順序。測試不得只 mock repository／transaction 或以 H2 代替 PostgreSQL，不新增真人 token／個資。

## 驗證

先跑既有機械規格檢查及獨立唯讀審查，再修改程式。此 task 只修復上述既定需求；本輪共用規格與其餘架構工作仍有別的 worktree 修改待協調，不能宣稱全面重構已完成。

```bash
bash scripts/spec-check.sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home DOCKER_HOST=unix:///Users/steven/.docker/run/docker.sock /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test -DextraArgLine=-Dapi.version=1.44
git diff --check
bash scripts/tests/schema-sql-drift-test.sh
```

驗收由協調者依已讀取的 run-stack 流程操作共用 stack：從本 feature worktree build／recreate `business-services`，等 healthy 後 recreate `bff` 以重連 upstream，仍使用 main 的忽略版 `.env` 與 secrets，所有富邦新功能保持停用。不執行 `down`、刪 volume、實機同步 POST、crawler rescan 或批次試打。只核對 image／工作目錄 provenance、服務 health、既有明確 GET 白名單與登入後頁面。這是部署驗收，不取代 fake broker 加真 PostgreSQL 的成功／失敗路徑測試。全面重構未完成前不得刪除原兩個 Claude branch。

## 完成報告

本任務的成交與快照修復已實作並通過隔離測試、獨立程式審查及 feature Docker 驗收；這不代表其餘富邦架構重構或兩個原始 session 已全部完成。提交與 main 合併紀錄以 Git history 為準。

- 成交專用 codec 與 typed batch validator 先檢查完整批次、日期及儲存精度；名稱未解析或已存在的資料不能藏住無效列。獨立 writer 使用精確 partial unique conflict target，真 PostgreSQL 驗證後列 constraint 與 deferred commit 失敗皆整批 rollback，並行重複只新增一筆，既有手動編輯列不變。
- 富邦 snapshot 查詢以 configured-owner 明確限定，取得與一般 CRUD 相同的資料列鎖後才重驗。以真 Spring proxy、TenantFilterAspect、HTTP RequestContext 與 OSIV context 重現舊 ACTIVE owner 及舊 children 總額問題，再由窄 `FubonSyncFreshness` 介面與外層 JPA adapter 修正；沒有全域 clear、停用 OSIV 或放寬 tenant filter。
- 已載入的 deposit child 若被另一交易刪除，該次請求安全 `ROLLED_BACK`、零寫入，下一個乾淨請求可成功。新增 deposit、stock 新增／刪除皆重讀成功且總額正確；兩種完整快照更新的鎖先後順序，以及 inventory beforeCommit 跨日回滾皆已覆蓋。
- Temurin Java 21、真 PostgreSQL 16 Testcontainers 的完整 `mvn -q -f backend/pom.xml clean test -DextraArgLine=-Dapi.version=1.44`：**209 suites／1,948 tests，failure／error／skip 均為 0，Maven exit 0**。794 個 backend 檔案測前後 SHA 一致。測後 Surefire 的 30 秒 fork JVM 清理訊息在變更前完整測試亦存在，沒有更改 POM 或降低測試條件。
- 獨立程式審查完整覆核 22 檔、2,169 行 diff（含 8 個新檔），critical／major／minor 皆為 0。Docker 重建並 recreate `business-services`／`fubon-broker-service` 後重連 BFF；容器內 **1,142 個 Java class** 與測試產物逐位元相同。明確 GET 白名單通過，BFF 重連後沒有 connection-refused／500 記錄，正式頁面維持 API 52／15 已串接及排程 64 筆。
- 保留既有設定、secrets mount 與其他容器；富邦仍停用，沒有真人登入、同步 POST、schema／migration 修改或原 Claude branch 刪除。Task394／395 的來源未核實 no-write 邊界未變。其餘共用檔重構仍有其他 worktree 的同檔修改待協調。

本機證據位於 `/tmp/asset-takeover-20260830/broad-fubon-audit/` 的 `backend-implementation/implementation-after-full.json`、`backend-implementation/osiv-reproduction.json`、`scoped-test-readback.json` 與 `feature-acceptance.json`；測試本身均隨本任務版控。
