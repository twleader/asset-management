# [t515] 美股完成日缺列及不完整 OHLCV 背景修復

**對應 Requirements:** Requirement 7（真實歷史收盤與完成日資料）；Task 515修訂
**前置任務:** 無
**Liquibase changeset:** 無

## 背景

2026-10-26 21:45報告以asOf=2026-10-23讀9090，VOO/VT回AS_OF_BAR_MISSING。運行DB確認兩檔只到10/22；GOOGL的10/23完整，但2026-04-29 high=0/low=null使2年序列ADX及一年高低位置不可用。現有startup美股僅>7日stale或歷史不足10年才抓，daily只收ETF透視成份，US回補maxDate+1及skip-if-exists修不了舊缺欄。10/23當時producer失敗的舊容器log不可得，不能斷言其具體故障。

## 要做什麼

- [x] 515.1 HistoricalBackfillService啟動US代號每檔增量抓；每日既有18:30US集合合併持有與透視，台英不擴張；各US代號失敗不中斷其他代號，2秒節流；台股透視單檔throw須catch隔離，不得攔住US。可提取package-private同步helper方便測試，排程仍背景執行。
- [x] 515.2 backfillUsStock保留首次／向前10年start；其他情況start為maxDate+1、today-7、2年內最早不完整日的最早值；end=min(until或today,today-1)，start>end零抓取，start=end仍抓。紐約today以MarketClock.US_ZONE判定；每根來源需在請求閉區間且嚴格早於today。
- [x] 515.3 StockSourceQuery新增US最早不完整日唯讀查詢及US atomic guarded upsert。缺口為任一OHLC null/<=0、high<low、volume null/<0。來源完整定義OHLC>0、high>=max(open,close)、low<=min(open,close)、volume非null且>=0；不合格零寫入。INSERT缺列或ON CONFLICT WHERE現有仍不完整才整根替換；完整列與其來源保留，close_source對修復列寫YAHOO_HISTORICAL_REPAIR。零量單獨不是缺口。無schema異動。
- [x] 515.4 PriceFetchClient.fetchUsHistoricalRange缺volume維持null；在純解析入口先驗chart.meta.symbol精確等於請求代號、currency為USD，缺／錯身份整批回空；timestamp僅正整數且可轉long，非法逐列跳過，不把null／字串／小數轉epoch0。新增身份與時間戳JSON fixture回歸測試、台股失敗後仍抓US測試；不改台英解析。同步SchedulePublicBffController.JOBS現有18:30文案；不新增排程。
- [x] 515.5 不改9090／Swagger bytes／任何pure-read consumer／exact-asOf／技術公式；不在GET取vendor、refresh或寫DB，不生成／回退／內插bar，不刪資料、不寫Redis、不碰券商交易或寄信。
- [x] 515.6 新增US service測試涵蓋maxDate缺一日也抓、2年內舊缺欄、完整列保護、來源空／無量／非正／日期越界／today與future不寫、零量可寫、集合及逐檔失敗隔離。以isolated PostgreSQL integration test驗atomic guard（完整列、先查缺口後別writer寫完整列、修不完整列、缺列插入）。既有HistoricalRepairRangeTest與其他市場保留。測試新增類為未來產物。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml -Dtest='*Us*History*,HistoricalRepairRangeTest' test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml -Dtest=SchedulePublicBffControllerTest test
```

獨立spec審查後才實作。驗收由主流程依run-stack重建external-materials-service與bff（先比對env名稱與值但不輸出秘密），協調policy發布，不得自行改registry。查DB精確VOO/VT/GOOGL 10/23及2026-04-29；修復只經正式背景或已授權internal維護入口真來源。唯讀GET9090 `/api/public/srpp/completed-technicals?market=美股&asOf=2026-10-23&stockCodes=GOOGL,SGOV,VOO,VT`及每檔`/api/public/srpp/technical-series`，要求HTTP200、身分日期相符並回報實際COMPLETE/PARTIAL/UNAVAILABLE與hash；不可拿health當runtime資料證明。所有修改專案的commit/no-ff merge/push由上層release協調後執行，讀回remote main。

## 完成報告

已完成程式與靜態驗證，正式部署及資料讀回待release協調，不能將本段當作runtime修復收據。

- 修改HistoricalBackfillService（US啟動與每日held聯集、7日重疊與2年缺欄起點、逐檔隔離）、StockSourceQuery（atomic guarded repair）、PriceFetchClient（來源symbol/USD與整數時間戳驗證、缺量null）、既有排程列表文案。9090路由、Swagger、公式及exact-asOf均未變。
- 新增美股背景回補7例、Yahoo JSON身份／時間戳／缺量3例、真isolated PostgreSQL atomic guard1例；連同既有歷史修復6例與非正收盤6例共23例全綠。BFF排程測試25例全綠，總48例，無skip。
- spec-check為BLOCK 0／CHECK 0；git diff --check無錯。獨立spec與diff-scoped架構審查由上層agent執行，提出的台股失敗隔離與來源身份問題已修並新增回歸測試，審查閘門已記錄。
- 正式DB查證：2026-10-23 VOO/VT缺列；GOOGL該日完整但2026-04-29 high=0/low=null。報告當輪保存的Yahoo 1d在上述精確日期有完整真bar。10/23當時producer具體失敗原因因舊容器log已不在仍未知；已證明現有stale條件與skip-if-exists會阻止自動補缺。
- 此次尚未執行正式資料寫入、image重建、容器recreate、9090修復後readback、commit/merge/push；必須由release流程完成並另填收據。

## 同一修復單元的規則包發布

SRPP美股證據與D-174工具修復完成後，使用原policy_bundle／daily_runtime_contract編譯器建立新hash；投資模型與既有Swagger bytes保持不變。本任務沿Task499／504／508既有發布契約，將精確model_inputs與policy_manifest bytes同步至backend authority，以原Ruby工具重產formal policy與insert-only registration SQL，原generator重產22案例golden並通過check-srpp-report-authority。禁止修改歷史registry rows。

先由feature重建external-materials-service、business-services與bff（僅受影響服務）並驗收精確10/23技術事實；補缺僅用已授權internal backfill/stock及Yahoo真完整bar，完整既有列不覆寫。兩repo各feature commit／no-ff main merge／push／remote SHA讀回後，先從乾淨asset main再build/recreate，再執行新hash insert-only registry SQL，然後讀回jar model／manifest／Swagger與精確registry row。9090技術API及SRPP原validator須留存實際結果；report-calculations僅在Task504台股合法時窗驗收，晚間為NOT_YET_EXECUTED，不冒稱全報告通過、不重跑報告／寄信／交易。發布收據由release流程另記。
