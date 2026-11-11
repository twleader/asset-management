# [t556] 每日台美股庫存最多保留66個曆月

**對應 Requirements:** Requirement 94（年度配息歷史持股；每日庫存保留期限）
**前置任務:** t555（每日台美股庫存已發布）
**Liquibase changeset:** v1.163.0-daily-inventory-retention.sql

**後續修訂：** Task571將66個曆月改為42個曆月（3.5年），只替換cutoff函式interval與排程文案；歷史驗收紀錄保留當時結果。

## 背景

使用者將每日庫存保留策略從原先永久改為「最多存5.5年」。目前daily兩表trigger完全禁止DELETE，必須使用新的incremental migration限縮不可變規則，只放行已過期資料；不修改已發布migration。

## 要做什麼


- [x] 本修訂依使用者「最多存5.5年」指示，僅針對daily_inventory_snapshot與daily_inventory_position，取代Task555永久保留與一律拒絕DELETE。5.5年明確定義為66個曆月：按market「台股」Asia/Taipei、「美股」America/New_York各自當地今日計算cutoff=localToday.minusMonths(66)，trading_date>=cutoff保留，<cutoff過期；邊界當日包含。月末／閏年依曆法月底截短，不以365*5.5天或美台共用日期代替。
- [x] 沿既有DailyInventoryScheduler每分鐘tick，先獨立呼叫retention清理再做市場日曆與close+6h保存判斷；清理不依賴交易日、有效日曆、到點窗口或owner目前是否還有來源快照，包含休市、午夜後與重啟首tick。清理失敗記錄明確錯誤但不阻擋合法新日庫存保存；下tick重試。SAVED與EMPTY header皆依同一cutoff到期。
- [x] 一次REQUIRES_NEW交易先鎖住當下判定已過期的header（按id固定順序），再刪除其positions、再刪除同組header；任何失敗整組rollback，不能留下半份有效資料或清到未過期。清理只命中這兩表，不刪舊asset_snapshot、stock_holding、asset_transaction、股利、SRPP或其他資料。未過期仍拒絕UPDATE/DELETE，所有UPDATE無論是否過期都拒絕；不新增任意日期清理／手動清理endpoint。
- [x] 新增incremental migration v1.163.0-daily-inventory-retention.sql，不修改已發布v1.162.0。DB新增daily_inventory_retention_cutoff(market text, at timestamptz)純計算函式，按上述兩市場明確時區回傳date；未知market或null fail closed。DB row trigger對header INSERT與child INSERT必用statement_timestamp()計算真實市場cutoff，拒絕過期header重灌與對過期header補child；header DELETE只准當時已過期者，child DELETE只准parent已過期者，UPDATE一律拒絕。不可透過session變數／客戶端日期繞過；guard使用真DB時鐘，純函式可傳測試時間不等於guard可被覆寫。
- [x] 清理adapter於同一交易先取得DB statement_timestamp()作本輪選取截止時間，鎖定已過期header後只刪其固定id集合，不使用caller傳入cutoff。即使跨午夜，較舊輪次cutoff只可能少清不會刪未過期資料；trigger每個statement仍以當時真DB時間覆核。DB與JVM時鐘差異不放寬guard。
- [x] 年度配息純讀的daily symbol scope與daily基準兩條SQL，皆加trading_date>=daily_inventory_retention_cutoff(market,statement_timestamp())，即使清理落後也不讀／使用過期每日庫存。呼叫端asOfDate仍為原查詢上界，不能用來延長保留期限。保留原舊asset_snapshot與ledger fallback；已清理的每日資料缺席不是EMPTY、不當零，無其他基準維持資料不足。DTO/BFF/UI及配息算式不改，既有stock master/ledger名稱查詢不改。
- [x] 修改既有排程文案為「最多保留5.5年（66個曆月），過期每分鐘清理」，保留台股19:30、美股NY22:00／提前19:00保存時機，JOBS仍82筆、不新增@Scheduled工作或新頁面。資料庫備份政策不變，備份中的舊副本不聲稱已被此線上清理同步移除；現行schema的一般INSERT/COPY會拒絕過期資料，但完整schema+data復原不宣稱由guard阻擋（復原可能重建舊schema/trigger）。復原後須確認現行migration已套用，再由首tick清除過期；現行annual讀路徑立即排除過期。此任務不新增restore/backup改造或備份檔清理功能。

- [x] 556.1 擴充DailyInventoryHistoryRepository.deleteExpired()與JdbcDailyInventoryHistoryRepository，REQUIRES_NEW原子鎖定過期header固定id集合，先刪child再刪header。DailyInventoryScheduler每分鐘先清再依原calendar保存；兩階段錯誤隔離，首tick可清過期。
- [x] 556.2 新增daily_inventory_retention_cutoff(text,timestamptz)與guard triggers，unknown/null拒絕；清理索引以(market,trading_date,id)建立。不新增表/欄位，不改原history時間/份數/來源契約。trigger使用statement_timestamp()強制guard，不讀session override。保留原UPDATE禁令；DELETE只准過期；header/child INSERT拒絕過期。
- [x] 556.3 annual repository兩處daily讀取共用DB cutoff函式，SCOPE只納有效daily symbols，EMPTY與已清理缺資料分開；legacy fallback與ledger不動。
- [x] 556.4 同步Task555文件後續修訂指標、排程文案與測試、獨立spec/arch審查、feature run-stack與schema drift驗收已完成；Git落地與main驗收結果見最終交付回報。

## 驗證

已擴充DailyInventoryCaptureServiceTest、JdbcAnnualDividendReadRepositoryTest、SchedulePublicBffControllerTest，並新增JdbcDailyInventoryRetentionTest；既有JdbcDailyInventoryHistoryRepositoryTest、AnnualDividendCalculatorTest、AnnualDividendServiceTest與DailyInventoryCalendarTest一併回歸。Backend 42個、BFF 28個測試全部通過，零失敗／錯誤／跳過。新增保留期限真PostgreSQL 16測試4/4通過，包含最後同transaction修改session時區與fake2099變數不能放行未過期DELETE的強化重跑。真PostgreSQL測試以舊migration先建立已過期fixture，再套新migration，不能為測試關閉正式guard。涵蓋66月邊界包含、前一日到期、月末/閏年、同一UTC瞬間台美日期不同、未到期UPDATE/DELETE拒絕、過期positions/header/EMPTY原子清理、第二步失敗rollback、過期header/child重灌拒絕、清理不受休市/日曆錯誤阻擋、首tick清理、查詢排除尚未物理清掉的過期daily、legacy fallback不當零。

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml '-Dtest=*DailyInventory*,JdbcAnnualDividendReadRepositoryTest,AnnualDividendCalculatorTest,AnnualDividendServiceTest' test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml -Dtest=SchedulePublicBffControllerTest test
# run-stack：重建/recreate business-services與bff，驗證新migration/guard、排程82筆與health。
# 依db/schema.sql檔頭先dump到暫存檔再保留檔頭重產；119表不新增表。
bash scripts/tests/schema-sql-drift-test.sh
```

## 完成報告

2026-11-06 feature驗收完成；Git落地與main驗收結果見最終交付回報：

- spec第二輪與diff-scoped arch review皆無critical/major/minor；Backend 42與BFF 28個測試通過，未重跑已通過測試。
- 正式.env由main單向同步至feature，僅重建及recreate business-services、bff；有效env與既有bind mounts核對main一致，兩服務healthy，保留原BFF等待business復原後安全market-index GET取得合法JSON。
- v1.163.0-daily-inventory-retention已EXECUTED。純SELECT確認市場cutoff：台北當地2026-11-06→2021-05-06、紐約當地2026-11-05→2021-05-05；函式使用66曆月及明確市場時區，guard使用statement_timestamp()且無session override。
- 兩表trigger均涵蓋INSERT/UPDATE/DELETE並指向guard_daily_inventory_retention；索引idx_daily_inventory_retention為(market,trading_date,id)。schema依真DB pg_dump重產，仍119張表，drift逐位元比對通過。
- 運行jar含@Service DailyInventoryScheduler與既有每分鐘cron；服務已跨過正常分鐘tick且未見retention失敗訊息，未人工呼叫清理或保存。從實際BFF jar讀出82筆排程，該項目文案含最多保留5.5年（66個曆月）、過期每分鐘清理，數量未增加。
- 驗收時台北02:24／紐約13:24，未到保存時間，兩張daily表仍0筆。未對正式DB建立過期fixture或偽造歷史；實際有資料刪除／不可變guard行為以真PostgreSQL 16隔離測試驗證，不冒稱已刪正式歷史。
- 未做登入後UI測試、未暴露新端點、未重跑報告／寄信／券商交易；備份中既有副本不受此線上清理同步刪除。
