# [t557] 修復交易雷達股利區間的 PostgreSQL 日期參數型別

**對應 Requirements:** Requirements 94／99（股利事件及交易雷達還原權息證據）
**前置任務:** t552
**Liquibase changeset:** 無

## 背景

2026-11-06 早上雷達列表／單股回 502；後端 `StockDividendHistoryRepository.findAdjustmentEvents` 的 YEAR(:fromDate)／YEAR(:toDate) 產生 extract(year from ?)；PostgreSQL 對 unknown 日期無法選 overload，SQLState42725。單純 HQL 解析成功無法驗證此錯誤。

## 要做什麼

- [x] 557.1 保持四參數介面，YEAR 的輸入明示 CAST 為 LocalDate（SQL DATE）。保留股票、市場精確篩選與 ACTIVE；canonicalDate 優先否則 LEAST(exDividendDate,exRightsDate) 的日期閉區間；只有三日期全 null 才依起訖年份閉區間。排序仍 LEAST ASC、id ASC。不加回現金／配股非零條件，不更改任何資料、規則、API 契約、排程或交易功能。
- [x] 557.2 更新既有 HQL 解析檢查，新增隔離真 PostgreSQL 回歸：透過 production Spring Data repository 執行，包含跨年與起訖邊界、canonical 優先／錨定日 fallback、純除權、無日期年度列、區間外／CANCELLED／其他股票市場排除；同交易後續 SELECT 成功。測試不連正式資料庫、不啟動應用排程。
- [x] 557.3 獨立規格與架構審查後，只重建 business-services 並 recreate；保留 BFF 運行，實測現有 9090 today 與台／美股 stock GET。不得手動觸發寄信、報告、通知或券商交易。

## 驗證

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home mvn -q -f backend/pom.xml '-Dtest=StockDividendHistory*Test' test
bash scripts/spec-check.sh
docker compose -p asset-management build business-services
docker compose -p asset-management up -d --no-deps --force-recreate business-services
curl -fsS --max-time 30 http://127.0.0.1:9090/api/public/trading-radar/today
curl -fsS --max-time 30 'http://127.0.0.1:9090/api/public/trading-radar/stock?stockCode=006208&market=%E5%8F%B0%E8%82%A1'
curl -fsS --max-time 30 'http://127.0.0.1:9090/api/public/trading-radar/stock?stockCode=VOO&market=%E7%BE%8E%E8%82%A1'
```

Compose 僅由確認設定與正式 main 一致的隔離驗收 worktree 執行；已 merge 後從乾淨 main 部署，等待 healthy 後驗證具體 JSON，不以健康代替功能。

## 完成報告

- 修正 `StockDividendHistoryRepository` 的 YEAR 入參型別並校正舊註解；既有 HQL 測試同步，新真 PostgreSQL 測試透過 production Spring Data repository 執行，隔離重現原 SQLState42725，再確認修後跨年／起訖邊界／canonical 優先／純除權／無日期年度列／股票市場與狀態隔離及同交易後續查詢。
- Java25 執行 `StockDividendHistory*Test`：5 tests，0 failures／errors／skipped；真 PostgreSQL16.14。spec-check 零 BLOCK／CHECK、diff-check 乾淨；獨立 spec／arch 審查均無 findings。
- 2026-11-06 09:31:26 Asia/Taipei，business-services image rebuild + recreate 後 healthy，image `sha256:88355fe19609928002cd4d5238519a97957323c16e93ef18f1c1a4c823abbbe7`。9090 market-index 200；today 200 共36檔（2.31秒）；006208台股 stock 200（2.17秒）；VOO美股 stock 200（1.95秒），selector／JSON正確。新服務日誌42725／25P02均0筆，47鍵effective設定及mount與main一致；BFF保持運行未重啟。
- 無資料遷移、API契約或投資規則變更；未手動觸發通知、報告或券商交易。部署及Git收尾由主流程接續完成。
