# [t555] 交易日收盤六小時後保存每日台股與美股庫存

**對應 Requirements:** Requirement 94（年度配息的歷史持股依據；擴充每日庫存保存）
**前置任務:** t548、t552（已在main；本任務不修改股利來源）
**Liquibase changeset:** v1.162.0-daily-inventory-history.sql

**後續修訂：** Task556將本任務原「永久保留／一律禁止DELETE」限縮為最多66個曆月；未過期仍不可變，只有過期daily兩表允許原子清理。Task571再改為42個曆月（3.5年）。歷史驗收紀錄保留當時結果。

## 背景

最新asset_snapshot每日會移動日期且持股可被同步改寫；目前歷史配息只能由較舊快照加ledger推估。需要每日獨立市場庫存以供日後回查，不能把現在持股回填為以前的實際庫存。

## 要做什麼


- 每個有資產快照的 owner，其台股與美股在各自交易日一般交易收盤後 **6 小時**，保存一份不可變的系統庫存歷史（Task556修訂：最多保留66個曆月；Task571再修訂為42個曆月）。台股 Asia/Taipei 13:30→19:30；美股 America/New_York 正常16:00→22:00、提前13:00→19:00，沿既有 UsReportCalendar 的 session.close／EARLY_CLOSE 與2026–2028明確涵蓋範圍，不另造提前收盤規則。區域時區自動處理DST；美股歸美國交易日期，不歸台灣次日。
- 新增每分鐘tick（0 * * * * *，Asia/Taipei），依權威交易日曆與各市場當日close+6h到當地午夜前的窗口執行；到點前、休市、未知日曆／US覆蓋外日期不得存檔，不以星期猜測。延遲／重啟可在同一市場當日剩餘窗口補存，實際captured_at如實保留；跨當地午夜不以目前庫存補造過去日期。無歷史回填、無任意手動觸發端點、保留期限及過期刪除依Task556修訂。
- 保存來源是系統既有最新asset_snapshot與stock_holding，並非新增券商查詢，也不聲稱券商剛同步／收盤成交庫存。來源快照日期不得晚於當下台北日期；保存source_snapshot_id/date及captured_at、scheduled_at、session_close_at。保留owner/market/trading_date獨立header與每筆原始明細：source_holding_id、stock_code、broker_id(nullable)、shares numeric(15,5)、investment_cost numeric(20,2)、currency varchar(3) nullable、transaction_exchange_rate numeric(10,4) nullable；不存即時行情payload、衍生損益或整包JSON。系統無更細券商帳號欄位，不捏造帳號。此為具名歷史快照正規化例外：原資料後續可能改寫，必須保留當時股數與成本。
- 每個owner各自transaction，第一個DB操作經AssetSnapshotMutationLock鎖該owner latest root，再讀該owner持股。每次取得root鎖後重新讀Clock.instant，重新驗證仍在該market交易日期的close+6h至午夜窗口，captured_at必須用此真時間；等待鎖跨午夜時不寫header。鎖後重新查該owner latest ID，若等待期間新latest已提交而ID不同，退出本次交易，交下次tick重判，不把已過時root當latest。測試必覆蓋等待鎖跨午夜及等待期間建立新latest兩個反例。header/所有明細原子提交；header唯一(owner_user_id,market,trading_date)、明細唯一(header_id,source_holding_id)，防多實例／重啟重複。成功header與明細資料庫trigger拒絕UPDATE/DELETE；FK不得因來源快照/持股被改刪而令歷史消失，source ids是歷史識別值。無來源快照、未來快照或股數/成本格式無效則不產生成功header並記明錯誤；某owner失敗不阻擋其他owner或另一市場。來源存在而該市場無明細，保存EMPTY header以區分已確認的系統零庫存與未保存；其餘SAVED。各市場只存自身列。
- 年度配息既有純讀repository納入每日歷史symbols與完整market-scoped基準（含EMPTY），同日優先每日歷史、不同日選截止日前最新適用市場基準，之後才套用嚴格較晚交易日的ledger；台股基準不代表美股零庫存。既有asset_snapshot仍為全市場fallback，不重複加總兩份來源、不修改既有配息算式、原幣隔離／資料不足語意或UI回應欄位。歷史sold-out股票即使目前庫存刪除仍可由每日歷史進分析範圍。來源仍為系統庫存推估，不提升為實際入帳。
- 同步SchedulePublicBffController.JOBS與排程清單測試，列明每分鐘檢查、台股19:30／美股NY22:00（提前19:00），不可顯示成每分鐘新增快照。既有SnapshotDateRollScheduler與latest估值不變。無券商寫入／交易、無新9090/SRPP API、無SRPP政策/排程變更。DB備份沿既有一般金融資料保留，不新增排除。

- [x] 555.1 新增DailyInventoryCalendarPort、adapter、scheduler、capture service、歷史repository抽象與JDBC adapter，Clock可注入測試；除既有MarketDataService日曆proxy以外不新增外部IO。
- [x] 555.2 新增daily_inventory_snapshot與daily_inventory_position。header：id bigint identity PK，owner_user_id bigint NOT NULL FK app_user、market varchar(20) NOT NULL CHECK台股/美股、trading_date date NOT NULL、session_close_at/scheduled_at/captured_at timestamptz NOT NULL、source_snapshot_id bigint NOT NULL（不FK）、source_snapshot_date date NOT NULL、status varchar(10) NOT NULL CHECK SAVED/EMPTY；唯一(owner_user_id,market,trading_date)，CHECK scheduled_at=session_close_at+6h、captured_at>=scheduled_at。明細id bigint identity PK、snapshot_id bigint NOT NULL FK header、source_holding_id bigint NOT NULL（不FK）、stock_code varchar(20) NOT NULL、broker_id bigint nullable FK broker、shares numeric(15,5) NOT NULL CHECK>=0、investment_cost numeric(20,2) NOT NULL CHECK>=0、currency varchar(3) nullable、transaction_exchange_rate numeric(10,4) nullable；唯一(snapshot_id,source_holding_id)。兩表不可UPDATE/DELETE trigger；migration可安全重跑，附DB regression測試與schema重產。
- [x] 555.3 AnnualDividendData.Snapshot additive nullable market並保留舊constructor；calculator依security.market選基準、同日daily勝出；repository把daily symbols納scope，EMPTY也載入，所有私有查詢帶owner predicate。保持annual DTO/BFF/UI契約。
- [x] 555.4 同步SchedulePublicBffController.JOBS與測試、獨立spec review、diff-scoped arch review及feature run-stack驗收已完成；Git落地與main驗收結果見最終交付回報。

## 驗證

已建立DailyInventoryCalendarTest、DailyInventoryCaptureServiceTest、JdbcDailyInventoryHistoryRepositoryTest，並擴充AnnualDividendCalculatorTest、JdbcAnnualDividendReadRepositoryTest、SchedulePublicBffControllerTest；AnnualDividendServiceTest一併回歸。Backend相關測試36個、BFF排程清單測試28個全部通過，無失敗、錯誤或跳過。真PostgreSQL 16 Testcontainers覆蓋migration重跑、header/明細原子rollback、並行唯一鍵與不可變trigger；其餘涵蓋到點前/邊界/午夜後、TW假日/未知、US DST與提前收盤、重啟/重跑/跨owner隔離、EMPTY與無來源、股數小數/成本幣別、等待鎖跨午夜與來源替換，以及TW基準不能清空US、sold-out symbol與當日daily優先。

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest='*DailyInventory*,AnnualDividendCalculatorTest,JdbcAnnualDividendReadRepositoryTest' test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml -Dtest=SchedulePublicBffControllerTest test
# run-stack：使用正式.env一致的目錄重建並recreate business-services與bff，檢查health、migration與scheduler存在。
# 正常到點後唯讀查header/positions，非窗口不可手動偽造日期或補寫過往。
# schema重產：依db/schema.sql檔頭，先pg_dump至暫存檔，再保留原檔頭合成正式schema。
bash scripts/tests/schema-sql-drift-test.sh
```

## 完成報告

2026-11-06 feature驗收完成；Git落地與main驗收結果見最終交付回報：

- spec-review第二輪無critical/major/minor；diff-scoped arch review無發現，gate由主agent記錄。
- 僅重建及recreate business-services、bff。正式.env從main單向同步，容器有效環境與business三個bind mounts均核對main一致，未列印秘密值。
- business-services與bff均healthy；保留原BFF等待business恢復後，9090 market-index唯讀GET取得合法JSON，再recreate含新排程清單的BFF。
- Liquibase v1.162.0已EXECUTED；兩張daily_inventory表與兩個immutable triggers存在。schema依真DB pg_dump重產，現119張表，schema-sql-drift-test逐位元比對通過。
- 實際運行jar含DailyInventoryScheduler與相關類；從該jar讀出@Service及tick的@Scheduled(cron="0 * * * * *", zone="Asia/Taipei")。服務正常啟動，未以人工呼叫執行scheduler。
- 從實際BFF jar讀取既有controller靜態清單，確認82筆且每日台美股庫存歷史保存恰1筆。匿名schedule-list請求維持401；未繞過登入，未做登入後瀏覽器UI測試。
- 驗收時台北2026-11-06 02:05／紐約2026-11-05 13:05均未達保存窗口，兩張表0筆；沒有用當前庫存補造歷史。正式排程首次到點的實際保存尚未發生，不能以部署驗收冒稱已保存。
- Backend 36與BFF 28個測試通過；無寄信、報告重跑、券商交易或新9090/SRPP契約變更。
