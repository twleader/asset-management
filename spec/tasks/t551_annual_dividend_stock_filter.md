# [t551] 年度配息的年度／市場／股票篩選

**對應 Requirements:** Requirement 94（逐事件現金配息統計與多條件過濾）
**前置任務:** t548
**Liquibase changeset:** 無

## 背景

既有年度配息頁已有年度與設定主檔市場選單，但無股票過濾。使用者要求分年度、台股／美股並能過濾股票。只在前端隱藏列會使原幣合計仍顯示全集而誤導，故沿原本本頁API在business做篩選與共用彙總，不改配息算式。

## 要做什麼

- [x] 551.1 在AnnualDividendsView.vue給既有年度/市場明確文字標籤，沿market_type.code作value/displayName作label（可選全部、台股/美股及其他既有主檔，不硬編新分類）。新增可搜尋代號/名稱、多選、可清除的股票選單，label含code/name/market。清除股票為全部。股票條件與年度/市場取交集，表列、資料缺口、待確認數與原幣摘要全部一致。
- [x] 551.2 擴充GET /api/bff/annual-dividends/summary與GET /api/annual-dividends，只有optional stock可重複，其他僅year/market單值，未知query、owner/email/duplicate year/market仍400。stock值必為下述stockOptions.key，空白或不在當前owner／年度／市場scope的值400，重複同值只算一次；未帶stock為全集，保持舊回應計算結果。BFF轉送所有stock值與原本狀態/tenant，不能只取第一值。frontend API要序列化為stock=a&stock=b而非stock[]=a。
- [x] 551.3 在原Summary additive新增immutable stockOptions:[{key,code,name,market}]、selectedStockKeys:string[]。key精確為Base64 URL encoder without padding對UTF8 market與code分別編碼，以ASCII '.'連接（Base64url不含'.'，不跨市場撞key）；UI將key視opaque。stockOptions來源是owner既有Facts算完年度與市場、尚未套stock的rows與gaps內股票聯集，按market/code去重排序，含已賣出/缺股利資料股票，不因股票已選而縮小選項。selectedStockKeys為驗證去重後key排序清單，未帶回空陣列。key不是租戶授權，所有私人讀取沿現有顯式owner。
- [x] 551.4 不新增外呼/資料表/派生值保存或9090/新BFF路徑。Controller仍只驗形狀/委派，Service使用既有TenantGuard與read repository讀一次，純calculator抽共用彙總入口，讓原calculate與filter結果共用金額和unavailable彙總。股票集合對rows/gaps篩選，再按原幣/歷史未來加總逐事件既有scale2金額，不重算/複製股數資格/四捨五入算法。零/未知/衝突/純配股與無事件既有語意維持；空摘要保持空，不憑空補0。
- [x] 551.5 前端只render API結果與格式化，選項搜尋可在el-select純顯示層完成，不能前端過濾rows/gaps或重算小計。切年度/市場時清空selectedStockKeys，再查無stock的summary並更新選項；選/清股票時重新查API。原取消＋序號保護涵蓋三條件，舊回應不可覆寫當前條件，error/empty/loading分開。保留選單在交易紀錄與資產配置建議間、原估算說明及responsive尺寸。
- [x] 551.6 擴充既有calculator/service/controller/BFF測試：單股、多股/重複key、清除恢復全體、年度市場交集與原幣金額/缺口/待確認計數、跨市場同代號、缺資料股票仍可選、選項不因選股縮小、invalid/空stock/不在scope或他owner key400且無外呼、BFF全部值保留；原21測試仍通過。瀏覽器以隔離fixture驗搜尋/多選/清除/年度市場清股/競態/請求stock重複形式、桌面/窄視窗，正式API內部tenant唯讀用實際資料與全集對帳。
- [x] 551.7 使用run-stack rebuild/recreate business-services/bff/frontend，先比對main權威env/掛載，只改feature build contexts；檢查healthy、API實際rows/gaps/原幣小計、未登入401與9090私人新路徑仍404。其他服務保持，無外抓/producer/報告/寄信/券商動作。獨立diff-scoped架構審查與spec-check完成後，依commit-merge-push feature commit→main --no-ff→push/readback；若main前進改到部署來源需以乾淨main再驗部署。

## 驗證

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest='*AnnualDividend*' test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml -Dtest='*AnnualDividend*' test
cd frontend && npm run build
```

執行run-stack完整流程：同正式env/mount、僅受影響服務image rebuild/container recreate，具體GET核對篩選結果與summary、無登入/非法參數邊界；fixture瀏覽器不得聲稱正式session驗收。私人原始持股資料、credentials、環境值、QA腳本與收據只在本機臨時位置，不進Git。執行bash scripts/spec-check.sh與git diff --check；驗收通過再commit/merge/push。

## 完成報告

完成：既有年度/市場新增明確標籤，股票可搜尋代號/名稱、多選與清除；股票選項以市場/代號分別編碼，不混淆跨市場同代號。原summary新增stockOptions與selectedStockKeys，BFF保留全部stock值；Service在原owner資料單次讀取後驗scope並過濾，rows/gaps與原幣摘要重用共用純彙總。原資格、事件計算及四捨五入函式保持，無schema/9090/SRPP/seed變更。

驗證：backend25+BFF4，共29tests（原21+新增8）全部通過，0 failures/errors/skipped，含實際PostgreSQL租戶範圍與專屬key拒絕、跨市場同ticker、gap-only、重複多選、清除與完整選項。frontend build與實際Axios repeated-stock序列化通過。獨立spec審查與diff-scoped架構審查（含CSS增量）均無findings，spec-check零BLOCK/CHECK，git diff --check通過。

run-stack以main權威環境/正式掛載、僅feature build contexts重建三服務；2026-11-05新取全集為80rows/7gaps/12待確認/39options。10個實際資料案例由全集獨立篩選rows/gaps與Python Decimal對帳，差異0；單股/多股/重複/清除/2026台美及2025年度交集/gap-only/無事件年度均檢查。9非法query400，另三個owner各自scope檢查通過；未登入/偽header401、9090私人路徑404，既有安全GET正常。business/BFF healthy，其餘六容器不動，effective env與bind mounts一致，最後imagepins再驗一致。私人raw資料/收據只保留本機臨時位置。

實際部署bundle以隔離fixture瀏覽器完成10組操作檢查：年度缺省、名稱搜尋、同碼跨市場、重複stock query、多選選項保持、clear恢復、年度市場清股、回應競態、error/empty、432px篩選尺寸/高度；全部通過，page errors 0。不聲稱已使用正式登入session操作。

收尾視覺驗收發現新增欄位wrapper後，舊窄視窗flex-basis變成輸入高度；主agent依低模型驗收失敗收尾例外修正純CSS，獨立增量覆核無findings，僅frontend重新部署後重驗通過。功能契約無偏差。
