# [t437] 台股主檔阻擋使用者名稱覆寫

**對應 Requirements:** Requirement 155（台股股票主檔不得由使用者輸入覆寫權威名稱）
**前置任務:** t435；保留既有台股 trusted resolver 與所有唯讀券商邊界。
**Liquibase changeset:** `v1.133.0-tw-stock-master-user-input-authority.sql`；只修正既有 `00850` 名稱，結構不變。

## 背景

受控唯讀資料庫讀回的 39 檔台股中，僅 `00850` 名稱是
`YUANTA SECURITIES INV TRUST CO`，正確為「元大臺灣ESG永續」。現有
`StockRepository.upsert` 對 `(code, market)` conflict 無條件更新名稱，而
`AssetService`、`StockAlertService` 四組建立／更新路徑都把 request 的 `stockName`
送入這條共用寫入鏈；主檔沒有來源歷史，故不能捏造是哪次輸入造成此列。本任務修正無條件覆寫這個可證實根因，並精確修正既有資料。

## 要做什麼

- [ ] **437.1 收斂主檔寫入與名稱 authority。** 將 `StockMasterService` 的 public contract 分為 trusted Taiwan resolution 與手動 payload policy。台股 `resolveName(code, 台股)` 不得以 local master hit short-circuit，固定依序使用既有唯讀富邦台股 quote、官方 TWSE、官方 TPEx、Yahoo `{code}.TW` 後 `{code}.TWO`；每一層採用前都須是 exact requested identity 且名稱 trim 後非空、非 requested code，否則該層 unavailable並繼續 fallback。富邦 unavailable/invalid 才查 TWSE，TWSE 無 exact row或 transport/schema/identity unavailable 才查 TPEx，TWSE/TPEx 都 unavailable/invalid 才查 Yahoo。FinMind 不得用作此名稱 authority。`.TW` 任一 transport/schema/identity/name unavailable 或 exact mismatch 都必查 `.TWO`。每個 Yahoo response ticker 必精確等於本次所請 ticker且可映射回 requested code/market，任一 identity/name mismatch 不得採用，所有層全失敗零 mutation。此名稱 resolver 的 suffix fallback 不改既有 order-book Yahoo worker「僅 404 才 `.TWO`」契約。只有此 resolver 的有效名稱可經受限 trusted path 寫入／更新台股主檔；台股手動 payload 不得外呼 resolver。
- [ ] **437.2 修正四個台股手動 caller 並封住 mutable escape hatch。** `AssetService` 的 create/update snapshot 與 `StockAlertService` 的 alert/group create/update，在 market=台股 時不得呼叫任何主檔寫入或外部名稱 resolver；台股 alert/group 只以已有 local master 做名稱 validation，缺主檔則保留 request。非台股維持既有手動 policy。`StockRepository` 改為 read-only interface、不再繼承 `JpaRepository.save` 或曝露 raw conflict upsert；可寫 adapter 為 package-private `StockMasterPersistence`，僅 `StockMasterService` 可用。新增 source-scan/architecture test 釘住其他 production caller 不得注入 writer、呼叫 `save`／`upsert`或直寫 stock SQL。Fubon/Yahoo quote、五檔、庫存與交易 ingress 均不得因此新增主檔寫入。
- [ ] **437.3 精確資料修正。** 新增並註冊 `v1.133.0-tw-stock-master-user-input-authority.sql`，只在 `code='00850' AND market='台股' AND name IS DISTINCT FROM '元大臺灣ESG永續'` 時更新 name；不得 insert、改其他欄位或其他列。`v1.130.0` 的 006208 correction 不得改動。結構沒有變更，但依專案慣例仍重產 `db/schema.sql` 並驗 drift。
- [ ] **437.4 測試與實機驗證。** 測試富邦成功、富邦 invalid→TWSE、TWSE invalid/miss→TPEx、TWSE/TPEx 失敗→Yahoo `.TW`、`.TW` unavailable→`.TWO`、ticker mismatch拒絕、FinMind 未被呼叫、所有層全失敗零寫入，並釘住每次只呼叫允許的後續 source；existing wrong local + Fubon success 必更新為富邦名稱。再以既有台股 `00850`／`元大臺灣ESG永續` fixture 證明 snapshot、alert、group 四個 caller各自送入英文錯名後主檔不變；缺主檔台股手動 payload 不寫 master 且 zero external interaction；trusted resolver 可新建與更名；美股／英股手動名稱仍可更新；architecture scan 證明 mutable master adapter 不被其他 production service 使用。migration test 必證明只更正 00850 且可重跑。跑 Maven backend/external-materials tests、schema drift test；run-stack rebuild/recreate `business-services` 與 `external-materials-service`，health/BFF safe GET recovery 後以唯讀 PostgreSQL 查 `00850`。runtime 不得主動觸發 resolver／富邦 SDK、manual sync、internal broker POST、下單、改單、撤單、匯款、轉帳或變更 `.env`／secrets。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml test
bash scripts/tests/schema-sql-drift-test.sh
```

## 完成報告

（實作者完成後回填實際變更、驗證輸出與任何偏差。）
