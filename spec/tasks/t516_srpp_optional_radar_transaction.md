# [t516] 隔離可選雷達讀取以恢復背景報告發布

**對應 Requirements:** Requirement 187（背景不可變報告計算包與純讀 GET）
**前置任務:** t489（已存在完整報告 producer 與 immutable publisher）
**Liquibase changeset:** 無

## 背景

2026-10-28 09:05 背景 report producer 多次對三個 active owner 全部報 UnexpectedRollbackException，9090 report-calculations 返回 503 CONTEXT_NOT_READY。producer 以 owner/date transaction lock 包住 capture；兩個 capture adapter 對政策符號與完整 universe 讀取 explicit-owner 雷達。TradingRadarService.getStockDetailForOwner 使用 REQUIRED readOnly，當標的不在該 owner 正持股／watchlist 即拋 IllegalArgumentException；adapter catch 原本應保存 null／UNAVAILABLE，卻已把 shared transaction 標 rollback-only，最後全部發布回滾。

## 要做什麼

- [x] 516.1 唯一生產碼變更將 TradingRadarService.getStockDetailForOwner 的交易傳播改為 REQUIRES_NEW、保留 readOnly=true。兩 capture adapter 現有 null／UNAVAILABLE 缺值語意不變；其他雷達方法與瀏覽器查詢不變，不造完整來源、不解除 BLOCKED，不改政策／Swagger／authority／registry／規則包 hash。
- [x] 516.2 保留 scheduler owner/date advisory transaction lock、先鎖後 reservation 捕捉、兩 consumer 同交易 atomic publish、失敗回滾及 immutable/idempotent publisher；不得把 capture 移出交易、清除 rollback-only 或放寬全域 L0／政策失敗。
- [x] 516.3 新增真正 Spring transaction proxy＋隔離 PostgreSQL 回歸測試；actual explicit-owner 缺失目標例外被 catch 後 outer commit 成功，inner readOnly 且 outer 恢復；外層全域例外仍回滾。測試不可連正式 DB 植入假金融資料。回歸既有 owner 隔離、producer 日曆／slot 與 publisher 並行／immutable suites。
- [ ] 516.4 使用 run-stack feature 驗收，只 rebuild/recreate business-services，main .env 及正式 secrets 掛載需先比對且不輸出秘密；驗收後 commit→no-ff merge→push，從乾淨 main 再 rebuild/recreate，同時核對 image/jar provenance 與 remote main SHA。BFF 依規範保持運行並驗 upstream 恢復，只有有界驗證失敗且 log 特定連線拒絕才可 fallback restart。
- [x] 516.5 先讀交易日曆 200 及今日 twTrading=true，僅台北今日 09:05≤now<13:30，以原正式 policyBundleSha256=be939f392f8a684fa48d525e4d0d39cbbef2005d9cb99357a69b4aa410f4f433、swaggerSha256=40819f617271e21b33efbca040134c3024e4915d1d34c735fa3b6a2489d45afe 唯讀 GET 今日當前 slot。背景正常 tick 發布後讀相同 calculationRunId 逐位元／hash/identity 核對；兩 consumer 可共享 base 但需各別包與 consumer identity，部分資料缺口保持 PARTIAL／UNAVAILABLE／NOT_EVALUATED／BLOCKED。不可人工觸發 report／observer、刷新市場、寄信或交易。逾 legal window 保留 NOT_YET_EXECUTED，不拿 health 代替業務成功。

## 驗證

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest='*SrppReport*,*TradingRadarServiceOwnerScope*,*OptionalRadarTransaction*' test
ruby scripts/render-9090-openapi-docs.rb --asset-only --check
bash scripts/spec-check.sh
git diff --check
```

Docker 由 run-stack subagent 執行受影響 business-services build/up --no-deps --force-recreate，並逐欄讀取真實 9090 package。不變更 scheduler cron 或 public route，故不需變動排程登錄表／Tailscale／OpenAPI。

## 完成報告

2026-10-28 完成程式、獨立規格／架構審查與 feature 真交易日驗收。Git landing 及從乾淨 main 重建的結果由本次最終回報與 Git history 核對；516.4 的收尾不以此段文字預先冒稱完成。

- 生產碼唯一語意變更為 TradingRadarService explicit-owner 方法 readOnly=true／REQUIRES_NEW，兩個 callers 均是既有 SRPP capture adapters，公開雷達方法不變。保留 scheduler 的 owner/date transaction lock、reservation 順序與 publisher 原子性、既有未知／BLOCKED 狀態；未改政策、authority、registry、Swagger、API schema 或路由。正式 bundle 仍 be939f392f8a684fa48d525e4d0d39cbbef2005d9cb99357a69b4aa410f4f433，Swagger 仍 40819f617271e21b33efbca040134c3024e4915d1d34c735fa3b6a2489d45afe。
- 新增 SrppOptionalRadarTransactionPostgresTest 的三項真 Spring proxy＋隔離 PostgreSQL 測試：同 actual 缺標路徑在 legacy REQUIRED 下回滾零包；production annotation 下 inner 真正唯讀、不同 transaction/backend、outer 恢復且 advisory fence 仍 held，兩 consumer 包 commit 並逐位元讀回 radar=null／UNAVAILABLE；outer 全域例外仍回滾 base 與兩包。三項均通過，無跳過。
- focused suite 15 suites、91 tests、0 failures／errors、1 skipped（90 實跑通過）。唯一 skip 是原有 opt-in existingReadOnlyCanonicalMarketFrameFitsActualEscapedWireLimit，因未設 srpp.report.size-probe；沒有把它當成已測。spec-check BLOCK=0／CHECK=1（remote main commit 時間36小時；已 fetch，無撞號），Swagger asset-only parity 與 git diff --check PASS。獨立 spec 與完整 diff 架構審查均無 critical／major／minor findings。
- feature 以 main 的部署設定與同正式 secret mount 完成僅 business-services rebuild／recreate，46 個有效環境鍵與三個 mounts 一致，不輸出秘密。BFF 原容器保留，upstream healthy 且 safe GET 200 恢復。新 image sha256:04d3d4ff362fe11c1cd26feb23b872c705e7bb2251d71cdd8c4f2edf19b125d9；jar SHA256 1fed88abb87a66840fca22857485a64f0af01a31458ab6291dbdbd14049964f9，與 wire build identity 一致，實際 class 交易註記符合修改。
- 背景正常 tick 自行發布，不人工觸發報告。11:17:09 feature GET 200 FINAL；11:17:26 獨立消費端驗證器驗今日真實日曆 200／AVAILABLE／twTrading=true、assets L0 PASS、report 200，runId=b82a7d6f-3e73-4639-a642-7bce9b359afd；帶同 calculationRunId 再 GET 200 並驗 raw SHA256 13b7d4b8606651616ad7b5e75ef52bd8fa80cb367a0696fcc35e9f309e7b0264 完全相同，兩次完整 verifier PASS。十二模組仍 PARTIAL=7、UNAVAILABLE=5、COMPLETE=0；API 恢復不代表金融資料全完備、原早盤草稿完成或交易授權。
- 部署副作用如實記錄：既有 MarketAnalysisScheduler startup self-heal 因今日尚無成功分析，11:14:29 補分析後對兩位既有訂閱收件人寄出非 SRPP 股市分析通知；既有 IndexDailyRefresh startup self-heal 自動更新 TPEX 2453 筆。未手動呼叫寄信／市場刷新或下單。查到今日 daily_market_analysis 已保存 OK 與 email_sent_at=11:14:36.667805+08；十個 index 的 latest dates 全通過既有 staleness 閘門，後續 main recreate 前必須重讀 gate 並在啟動後核對 skip logs，不能宣稱此部署完全零寄信／零刷新。
- 曾因歷史 reservation 驗證耗時暫時 503；JVM 真診斷為 JCS CPU 計算，PG blocking pids 為空，沒有新增 transaction deadlock。未刪除歷史資料、放寬驗證或為效能擴大本次範圍。
