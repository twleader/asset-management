# [t444] 修正外部資料服務八項整合測試回歸

**對應 Requirements:** Requirements 115、146、153、155（persisted LIVE、股票主檔錨定與行情名稱隔離）；Task 444 回歸補充。
**前置任務:** 無
**Liquibase changeset:** 無

## 背景

main d68cea2a 的外部服務全量有八項失敗。兩項真實Redis測試發現paired-cache Lua以reserved keyword until作local變數，script編譯失敗而Repository回FAILED；三項配息測試未建stock，current radar查詢從stock錨定故fail closed；兩項五檔與一項台股LIVE名稱斷言仍期待source名稱，與現行stock.name權威隔離不符。

## 要做什麼

- [x] 444.1 同步將 external-materials-service 與 backend 的 fubon-technical-v2-pair-write.lua 各兩處local until及引用改為合法名稱，保留 freshness deadline、generation CAS、fact-vector比較與bound context fence。不得catch後假裝WRITTEN。
- [x] 444.2 配息PG fixture新增canonical stock schema（由db/schema.sql擷取CREATE TABLE與既有constraints；stock無sequence/default，不新增；原dividend表擷取維持），seed0050與2330台股master。保留0000排除、scope與partial/dry-run/failure/official-source/單股transaction隔離assertions，不改production scope繞過stock。
- [x] 444.3 五檔兩項與Taiwan LIVE一項名稱預期改為既有master值，另顯式assert master name未被incoming覆寫。完整保留equal/older零寫、fetched time／TTL、同revision並發header/levels、DBcanonical-to-Redis修復與DBfailed零Redis。
- [x] 444.4 先跑四class共15項真實PG/Redis測試，接著external全量與spec-check、arch-review；不skip失敗test、不弱化既有安全assertion。純read runtime驗收且只重建external-materials-service與business-services。
- [x] 444.5 不新增API/DB欄位/migration/排程，零新增vendor/broker I/O、零credential/正式DB mutation、零下單；部署沿用main.env，BFF保持運作並驗證quotes恢復。

- [x] 444.6 清理測試用 Spring cached PostgreSQL context：新增 backend test-only TestExecutionListener 與 test resources META-INF/spring.factories 註冊（既有key如存在必須保留其他listeners）。afterTestClass 僅在 testContext.hasApplicationContext()、測試class有@Testcontainers、effective spring.jpa.hibernate.ddl-auto為create-drop且datasource URL為jdbc:postgresql:時markApplicationContextDirty(CURRENT_LEVEL)，先close context再JUnit container teardown；其餘context不載入也不清除。禁止修改碰撞中的FubonTradeWriterPostgresTest或其他九支測試，不加長Surefire timeout、不silence錯誤、不動正式runtime。以首個FubonSnapshotLockPostgresTest真PG測試與backend全量證明context close發生且不再有JVM shutdown等待已停PG的30秒timeout；需新listener離線單元測試驗證排除無context／非container／非PG／非create-drop，以及成功情境CURRENT_LEVEL只一次。

## 驗證

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml -DextraArgLine=-Dapi.version=1.44 -Dtest=FubonDividendEvidencePgIntegrationTest,FubonTechnicalV2CacheRaceRedisIntegrationTest,QuoteDetailSnapshotPersistenceIntegrationTest,TwLivePersistencePgRedisIntegrationTest test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml -DextraArgLine=-Dapi.version=1.44 test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DextraArgLine=-Dapi.version=1.44 test
bash scripts/spec-check.sh
```

run-stack重建與force-recreate external-materials-service與business-services，等待healthy後9090 GET /api/quotes需為JSON array；唯讀核對 deployed JAR 的Lua使用合法新變數並匹配本次hash；兩份Lua逐位元一致，後端Lua parity測試必須通過，並跑backend全量測試。合併前feature build、no-ff merge/push後main rebuild；不得為驗收觸發manual sync/vendor query。

## 完成報告

2026-09-17 完成。

- 原八個失敗全部修正：兩份paired-cache Lua只改保留字變數；配息補canonical stock fixture；五檔／LIVE名稱依主檔期待並明確查回主檔未變。四class原15項真PG／Redis測試全部通過。
- 外部服務全量134 suites／772 tests，零failures/errors/skipped，Maven exit0。
- 後端全量最終259 suites／2233 tests，零failures/errors/skipped，Maven exit0；新test-only cleanup listener五項gate單元測試與真PG定向共18項通過。九個fixture的Hikari shutdown於class結束完成，最後全量ERROR訊息0、kill self fork JVM0，原30秒SchemaDropper等待已停PG的teardown缺陷修正。
- 清理支援只在test Java/resources註冊；沒有改九支fixture（含另一worktree未提交的FubonTradeWriterPostgresTest）、沒有增加Surefire timeout，正式runtime不載入。
- 獨立spec三輪與最終arch查證均無未解決findings；test數量18的spec minor曾被auditor誤包含@Testcontainers，經@Test字邊界查證實際15並撤回。git diff --check通過。
- feature部署僅business-services/external-materials-service，沿用main.env；兩者healthy，BFF未重啟。9090quotes回JSON array、market-index回JSON；兩個deployed JAR與兩份原始Lua SHA-256皆43f0cbf1e30123b91b00bc4e4afcf6a8167e6694d9ec5119ef7f6311e87716ed，image與tag一致。
- runtime驗收僅safe GET與讀取JAR；不新增broker/vendor呼叫，不manual sync／POST，不改正式DB、環境旗標或憑證。部署保留原有已授權排程。
