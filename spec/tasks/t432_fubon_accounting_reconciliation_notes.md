# [t432] 富邦成交、在途款與已實現損益共同 owner 對帳，以及在途成交備註

**對應 Requirements:** Requirement 150（富邦已成交資料以一個明確帳號對帳至交易紀錄、台北富邦銀行在途款與已實現損益；在途備註顯示當日標的與張數）
**前置任務:** t385、t394、t395、t405
**Liquibase changeset:** `v1.129.0-bank-deposit-source.sql`；在既有 `bank_deposit.notes VARCHAR(200)` 外，新增不可為空的 `source` provenance（`MANUAL`／`FUBON_SYNC`）。兩個既有 transit **type code** 是 `買股待付款`、`賣股待收款`，`TRANSIT_TWD` 是 `BankDeposit.currency`。完成後必重產 `db/schema.sql` 並跑 drift test。

## 背景

富邦庫存同步與帳務同步是不同唯讀 API：庫存成功不代表 `filled_history`、`query_settlement` 或 `realized_gains_and_loses` 已可讀。既有交易同步使用 configured admin，而在途／已實現損益使用 `FUBON_SYNC_OWNER_EMAIL` 的 dedicated owner policy；這會造成同一帳戶的三種財務投影 owner 不一致，或後兩者在 owner 未設定時安全停止。既有在途 writer 只寫 aggregate amount，沒有維護 `bank_deposit.notes`，使用者無法看出今日買賣的標的和張數。

本任務讓三條既有富邦帳務管線採同一個顯式 owner policy。它不將三種不同來源混為同一事實：交易紀錄、在途金額和已實現損益仍各自在自己的 broker source 成功後才進入本機 writer。富邦證券的交易銀行由使用者指定為台北富邦銀行，既有 database code 是 `fubon`。元大證券→元大銀行（`yuanta`）、國泰證券→國泰世華銀行（`cathay`）僅記錄為未來整合 mapping，本 task 不得讀取或寫入任何元大／國泰資料。

## 要做什麼

- [ ] **432.1 統一 dedicated owner gate 與 outcome。** `FubonTradeSyncService` 與 `FubonTradeWriter` 改用既有 `FubonSyncOwnerPort`，不再直接依 `UserAdminService.configuredAdmin()` 選 owner。feature/config/calendar gate 順序不變；完成本地 config/calendar gate 後，先呼叫 `preflight()`，未放行時回 sanitized dedicated-owner outcome，且不得讀 broker、broker repository、stock master、交易 repository 或 writer。明確新增 `FubonTradeOutcome.SYNC_OWNER_NOT_CONFIGURED`：port denial 為 `SYNC_OWNER_NOT_CONFIGURED` 時，`TradeSyncResult.outcome`、`reason` 和 `FubonTradeOutcomeCounters` 都用該值；其餘 denial 用既有 `NO_OWNER`。writer 的第一個 DB action 必為透過 `FubonSyncOwnerPort.lockAndRevalidate(expectedOwnerId)`（production implementation 為 `FubonSyncOwnerPolicy`）取得的 owner row lock；只要設定空白／非法、帳號不存在、非 ACTIVE ADMIN、configured-admin drift 或 race，整批零 ledger mutation 並採同一 outcome 映射。settlement／realized 既有 owner policy、snapshot lock 順序與 fresh revalidation 不得回歸。

- [ ] **432.2 保持三個 broker source 的獨立、唯讀寫入語意。** `filled_history` 成功且既有 strict batch contract 通過時才冪等 insert `asset_transaction`，固定 `source='FUBON_SYNC'`，不得 update/delete manual 或既有 sync rows；`query_settlement('3d')` 成功、explicit binding、freshness 和 future aggregate contract 通過時才更新在途 aggregate；`realized_gains_and_loses` 成功且既有 `Stock/Sell`／idempotency contract 通過時才新增 realized gain。任一路 503、timeout、schema/identity failure、unknown calendar 或 owner failure，該路零寫入、不得用其他兩路或庫存推導缺失資料。不得新增 endpoint、BFF、public/9090/Tailscale route 或 scheduler。

- [ ] **432.3 台北富邦在途 target provenance 與備註。** 新增 Liquibase `v1.129.0-bank-deposit-source.sql` 與 master include：`bank_deposit.source VARCHAR(20) NOT NULL DEFAULT 'MANUAL'`，CHECK 僅允許 `MANUAL`／`FUBON_SYNC`，既有列都保留／回填 `MANUAL`；同步更新 `BankDeposit`、`@Builder.Default`／field default、non-null mapping、schema snapshot 與 schema drift test。任何手動 create、完整 PUT 或 Excel import 都必由實體 default 實際插入 `MANUAL`（不能倚賴 DB default；DTO 也不得收發 source），只有 writer 顯式建立 `FUBON_SYNC`。`FubonSettlementWriter` 對既有 `bank.code='fubon'`（台北富邦銀行）、`currency='TRANSIT_TWD'`、`買股待付款`／`賣股待收款` target 保持既有 amount、sign、aggregate recalculation 和 nonzero-only lifecycle。target 不存在才建立 `source='FUBON_SYNC'`；唯一既有 target 只有 `source='FUBON_SYNC'` 可更新，`MANUAL` 或未知 source 必回新增的 `FubonSettlementOutcome.UNMANAGED_TARGET`、零 amount/notes mutation，兩筆以上或 currency 不對仍 `AMBIGUOUS_TARGET`。寫入／更新 source-owned target 時，detail repository method **必須**是 `nativeQuery=true` 的 parameterized SQL，以 `owner_user_id=expectedOwnerId`、`source='FUBON_SYNC'`、`trade_date=observation.queryDate` 和精確 `transaction_type` 為所有 predicate，依 `asset_code, asset_name, id` 排序；不得走 derived／JPQL query 或 HTTP tenant filter，不可看 manual rows、不可 I/O 到 broker。

- [ ] **432.3a 保留 source-managed 列。** `AssetService.updateSnapshot()` 在清空／重建 deposits 前，必保存同一 snapshot 的既有 `FUBON_SYNC` 富邦 `TRANSIT_TWD` 兩個 transit targets（bank `fubon`、types `買股待付款`／`賣股待收款`）及其 amount、notes、source。碰撞 identity 固定是 `(bankId, depositType)`：payload 命中該 identity 時，不論 payload currency 是 `TRANSIT_TWD`、`TWD`、`USD` 或 null，均必從重建輸入剔除，不得覆寫、刪除、重建或建立第二列；這也讓不含 source 的管理資產 UI round-trip 安全。保留後與其他 manual rows 一起進既有 aggregate recalculation／response；不擴充 DTO 或使用者來源選擇。`ExcelImportService` 的同日期覆蓋匯入若有此 protected row，必用同一 locked update/preservation flow，絕對不得 delete-and-recreate；鎖定／owner gate 不成立時 fail closed 且原 snapshot 不變。僅此精確 Fubon source-owned scope 受到保留，其他手動完整 PUT 和無 protected target 的既有 Excel 覆蓋語意不變。

- [ ] **432.3b 在途款項的更新方式顯示。** `DepositResponse` 增加 readonly `updateMode`，不是 raw `source`：`FUBON_SYNC → AUTO`，`MANUAL → MANUAL`；無來源 legacy row 已由 migration 回填為 `MANUAL`。`DepositRequest`／Excel import model／任何寫入 payload 不得新增這個欄位，也不得接受 source。`SnapshotFormView` 的兩個在途表各加「更新方式」column，使用 disabled `el-select` 固定顯示「手動」與「自動」options；front-end form mapping 與新增在途列預設 `MANUAL`。`AUTO` row 的銀行、類型、金額、備註、drag handle、刪除皆 disabled/hidden，防線不可只依 UI，後端 432.3a 的 preservation 維持權威。這是既有 snapshot detail response 的 additive UI display field，不能新增 route、feature flag、broker I/O 或使用者切換來源的功能。

- [ ] **432.4 備註格式與界限。** `買股待付款` 必只讀 `transaction_type='買'` 並使用 `買入` 格式；`賣股待收款` 必只讀 `transaction_type='賣'` 並使用 `賣出` 格式。以 `assetCode`、`assetName`、id 固定排序，合併相同標的的 shares。整張（shares 可被 1000 整除）顯示 `買入 名稱（代號）N 張` 或 `賣出 名稱（代號）N 張`；零股顯示精確股數與十進位張數，不能四捨五入成整張。資料列前綴必明示「富邦證券當日已同步成交：」，使備註不假稱此 note 與 SDK settlement aggregate 的每一個日列一對一對應。沒有同日成功同步交易時，使用「富邦證券交割款；當日無已同步成交明細」；當完整內容超過 200 characters 時，以顯示已同步標的數量的明確摘要取代，不能切斷中文字、不能列出未完整的清單、不能修改手動 transaction note。amount 不變但 note 改變時仍須保存 note；除了這兩個 `FUBON_SYNC` 富邦 transit target 外不得改寫任何 `BankDeposit`。

- [ ] **432.5 future broker boundary。** 不新增元大或國泰券商 adapter、SDK import、HTTP、排程、flag、資料表或 writer。本 task 的唯一 broker/bank mapping 是富邦證券→台北富邦銀行 `fubon`；未來另行規格才可實作元大證券→元大銀行 `yuanta`、國泰證券→國泰世華銀行 `cathay`。不得把這些 future mapping 寫成 enum、硬編 bank id 或目前可執行的資料動作。

- [ ] **432.6 測試。** 更新 trade-service／writer tests，證明 dedicated owner 缺失、無效或 race 時在 adapter/database 前停止，且 `SYNC_OWNER_NOT_CONFIGURED` 在 outcome/reason/counter 出現，正確 owner 才可冪等新增 ledger。為 in-transit note 格式建立純 unit coverage（買／賣各自 target-direction mapping、整張、零股、同 code 合併、排序、無 source row、200 字摘要），並在既有 PostgreSQL settlement integration coverage 中驗富邦 bank/code、兩個 direction、legacy `MANUAL` target 的 `UNMANAGED_TARGET` 零 mutation、新建及既有 `FUBON_SYNC` target、manual transaction isolation、amount unchanged 但 note refresh 與 aggregate readback。另驗手動 create、完整 PUT、Excel import 均寫入 `MANUAL` 而不倚賴 DB default，以及完整 PUT 後 source-owned target 的 amount/notes/source 保留、不重複 target；以 `TRANSIT_TWD`／`TWD`／`USD`／null currency 逐一驗證同 bank/type collision 都會保留唯一 source row。既有同日 snapshot 的 Excel 覆蓋匯入也必證明 source amount/notes/source 完整保留、沒有 duplicate、其他 imported rows 仍為 `MANUAL`。service/controller contract coverage 必驗 `DepositResponse.updateMode` 只投影 `MANUAL`／`AUTO`，write payload 不能改它；frontend coverage 必驗台幣與美元在途表均顯示 disabled 下拉選單、新增 row 預設手動、auto row controls disabled。internal HTTP manual sync 在無 tenant header 時，必仍能以 native detail query 讀到指定 owner 的 `FUBON_SYNC` rows。realized gain tests 必保留 sale source 才可寫入、不得從 ledger 偽造 realized cost/profit。所有 tests 只使用 fake adapter／isolated PostgreSQL，零真實券商下單、改單、撤單、匯款或圈存。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=FubonTradeSyncServiceTest,FubonTradeWriterPostgresTest,FubonAccountingPostgresTest,FubonAccountingProjectionPostgresTest,FubonSyncOwnerPolicyTest test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
docker compose -p asset-management build business-services
docker compose -p asset-management up -d --no-deps --force-recreate business-services
```

容器 healthy 後，先讓 Liquibase 完成、重產 `db/schema.sql` 並跑 `bash scripts/tests/schema-sql-drift-test.sh`；確認 `FUBON_SYNC_OWNER_EMAIL` 是唯一既有 ACTIVE ADMIN 的明確 email。再只在三個既有 read-only broker endpoint 均成功時，以既有 internal transaction、settlement、realized-gain manual sync `dryRun=false` 做一次本機回填。逐一讀回該 owner 的 `FUBON_SYNC` transaction、台北富邦銀行 `買股待付款`／`賣股待收款` 的 amount＋notes＋`source`，以及 realized gain。任何 broker endpoint 503／timeout／identity rejection 時停止該路、保留零假資料，記錄 sanitized outcome 後不以庫存或 SQL 補造。

## 完成報告

（實作者完成後回填：實際檔案、測試輸出、Docker image／container 驗證、三個 read-only source 的真實 outcome、以及任何未能回填的外部權限或設定阻塞。）
