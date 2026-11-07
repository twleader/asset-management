# [t563] 觀察頁警示條件補上52週門檻價位

**對應 Requirements:** Requirement 164（延伸指標警示條件與門檻價位顯示）
**前置任務:** t554
**Liquibase changeset:** 無

## 背景

警示清單已有52週門檻價位，但觀察頁目前仍以不帶區間的static label組裝複合條件，像「低於季線5%（669.11）且D值低於15且K值大於D值且低於月線5%（666.52）且52週位置低於60%」最後只有比例。本任務具名延伸原未涵蓋觀察頁的顯示範圍，補上相同口徑價位。

## 要做什麼

- [x] 563.1 一般股票與台股0000觀察列的獨立／複合POS52W_ABOVE、POS52W_BELOW條件，既有conditions[].label附上門檻對應價位，支援0～100任意threshold。共用既有Week52Range與label helper，群組以「 且 」串接、排序及active／triggered語意不變；保留不帶range的static label行為供歷史通知／匯出使用。
- [x] 563.2 復用既有completed高低載入，最近240根未還原完成日K，排除各市場今日與未來；一般股票stock_price_history、台股0000為twse_index_daily_history，高低缺值以收盤代入。以PriceQueryService.getLive現價共同取高低，不用觀察頁收盤後備展示價。每股票同次response含POS52W才額外讀一次現價及一次高低歷史，所有單條與群組共享；非POS52W零新增讀取。不得反向注入StockAlertService形成循環依賴。
- [x] 563.3 價位以BigDecimal計算low+(high-low)*threshold/100，四捨五入2位去尾零，全形括號附於比例後；大盤加「 點」。不足240根、現價缺失／非正／非有限、等高低或讀取失敗顯示「（價位資料不足）」，不填0。只改目前觀察清單文字，不改警報觸發、通知、歷史匯出、MA／KD、報價、DTO／BFF／API schema、DB、前端、9090、SRPP或交易。
- [x] 563.4 測試涵蓋實際60%複合條件、獨立條件、一般股／大盤、同股票多群組共用一次查詢、無POS52W零額外查詢、歷史不足／現價缺失／讀取例外；跑既有52週價位與觀察大盤測試確認無回歸。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=WatchStockTaiexIntradayTest,StockAlertGroupLabelTest,StockAlertExtendedIndicatorTest test
git diff --check
```

依run-stack先比對main有效環境／mount，僅rebuild及force-recreate business-services，等待healthy、驗gateway safe GET與實際觀察列唯讀JSON含60%價位。不得新增測試警示、假登入或手動觸發通知；沒有已登入session就明列UI未驗證並驗前端原render與API文字。驗收後feature commit、main no-ff merge及push。

## 完成報告

2026-11-07 完成。

- 僅修改 StockAlertService、WatchStockService 與既有 WatchStockTaiexIntradayTest；一般股／大盤的觀察條件共用既有52週區間及格式。原觸發instance wrapper及不帶range的歷史文案維持原行為，未改前端、BFF、DTO、DB、9090或SRPP。
- 規格機械檢查 BLOCK 0／CHECK 0；獨立spec審查 critical 0／major 0／minor 1（錯誤依賴敘述已修正），獨立架構審查 critical 0／major 0／minor 0，兩個gate皆已記錄。此段僅回填驗收狀態，未修改規格契約。
- 指定三類單元測試49項全過：觀察大盤15、警示群組文案28、延伸指標6；新增6項觀察測試涵60%完整AND、單條／多群組共用、非52週零額外讀取、大盤、資料不足與例外。git diff --check 通過。
- run-stack採繼承模型備援（派工工具無指定gpt-5.6-terra可選），只rebuild／force-recreate business-services；47個effective環境鍵與3個bind mount皆與main一致。容器healthy、BFF UP且未重啟、gateway安全GET回JSON。
- 實際VOO觀察列回傳「低於季線 5%（669.11） 且 D 值低於 15 且 K 值大於 D 值 且 低於月線 5%（666.52） 且 52 週位置低於 60%（662.38）」。12個52週觀察條件皆與同次警示清單文案逐字相同，大盤保留「 點」。
- 驗收image SHA-256為 d0972f2202991f415020107813cfb7d7d6cbe2f590b81e56d0ee620adac8a62a，驗證前後一致；去識別證據 /tmp/t563-runtime-evidence.json。無登入session故UI未直接驗證，前端沿既有label render。未新增測試警示、假登入、通知或交易。
- 驗收後依feature commit、main no-ff merge及push收尾。
