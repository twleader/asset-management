# [t548] 年度配息統計與除息日前持股估算

**對應 Requirements:** Requirement 94（現金除息事件與歷史持股年度統計）
**前置任務:** 無
**Liquibase changeset:** 無

## 背景

資產管理選單尚無已公告逐次配息的年度統計。既有快照年化預估不是逐事件可領股息；只拿最新持股會漏已賣出股票並把除息後買進誤算成可領。現有 stock_dividend_history 有 ex_dividend_date/cash_payment_date/cash_dividend/ex_rights_date/stock_dividend/event_status/event_key/source，無 record_date 或股利幣別；asset_snapshot/stock_holding 是時點存量，asset_transaction 是獨立股票/基金流水，不能宣稱交易完整或券商核定資格。

## 要做什麼

- [x] 548.1 新增資產管理選單「年度配息統計」、SPA `/annual-dividends` 與 AnnualDividendsView.vue。使用年度、市場與重新載入，顯示各幣別歷史/未來/合計、無法計算數與資料缺口、逐次表（股票、除息日、發放日、股數、每股現金股利、稅前預估、持股依據/基準快照日/交易數/原因/來源）。空白日期顯示未提供，零值與未知分開。無已公告事件不複製往年股利；空資料/錯誤/loading 分開，切年度需防競態。前端只render/format，不算金融/聚合/篩選。
- [x] 548.2 frontend/src/api/index.js 定義本頁 API；獨立 bff/annualdividends/ controller 或route `GET /api/bff/annual-dividends/summary` 只轉送 `GET /api/annual-dividends`，沿現有 tenant-aware WebClient。year optional，缺省台北當年，範圍1900–2100；market optional/空為全部，非空驗既有 market_type.code，不接受 owner/email，不公開9090。前端不得跨頁 BFF、直接business或fetch。
- [x] 548.3 business controller僅委派 service；service用TenantGuard.requireCurrentUserId，私人Repository查詢均顯式owner條件。建立可不啟Spring/DB測試的純calculator及中立immutable輸入records、輸出DTO records，不讓內層依賴HTTP/JPA。從owner截至台北今日快照持過與股票ledger有過之market/code聯集查事件，不漏已賣股、不含基金；多券商合併，股票名稱可從stock或交易assetName補，無名稱回code。
- [x] 548.4 查已保存ACTIVE股利；選年依exDividendDate.year（不是盈餘year）。同market/code/exDate現金金額一致去重；不同值或一筆缺金額為衝突/未知，不能擅選或相加。年度彙總exDate=null只作該year缺口，不與事件加算；無現金的純配股排除，但現金資訊缺失需報缺口。無股利股票以gaps列出。GET只讀，不用findFromDb或projection/sync，不更動原snapshot.estimatedAnnualDividend/日報/API。
- [x] 548.5 截止日＝歷史(exDate≤今日)之exDate-1日；未來則今日。取截止前/同日最新owner完整snapshot為baseline，該股shares跨券商sum；若snapshot不存在此股可作0，但baseline不存在不能假設0。加買/減賣 `(baselineDate, cutoff]` 之assetType=股票/同market/code流水，shares是股（非張）、保留零股/小數，快照同日/除息日交易不重加。按交易日彙總淨量，任何日末負股數、負/缺流水股數、未知方向、缺snapshot股數時shares/estimatedCash=null+原因。不能以未來snapshot回填，不能只靠ledger從0起算。
- [x] 548.6 所有計算皆估算：歷史標「歷史持股推估」，未來標「維持目前持股預估」，顯示baseline/ledger依據；揭露流水不保證完整、公司行動（配股/拆併股）未自動校正，不自行新增股數。不把除息日稱record date或推定已入帳。台股TWD/美股USD；其他market輸出currency/金額null+原因，不依成本currency推斷。稅前cash=shares×cashDividend，BigDecimal逐事件HALF_UP至scale2再分幣別加總，不扣稅/補充保費/匯費/匯率。有未知項目標可計算項目小計。
- [x] 548.7 回傳 selectedYear/asOfDate/availableYears（截至今日owner timeline年、當年與股利除息年倒序）/markets（設定表）/currencyTotals(currency,historicalEstimate,futureEstimate,total)/rows/gaps/unavailableCount；row含code/name/market/currency/exDividendDate/cashPaymentDate/cashDividend/shares/estimatedCash/basisSnapshotDate/transactionCount/holdingBasis/reason/source。結果碼為閉合計算狀態，不新增業務分類enum/持久化衍生欄位/DB表。穩定依除息日/market/code排序。
- [x] 548.8 meaningful 單測涵蓋多券商、基準日前後買賣/除息日買賣排除/快照同日不重加、已賣出股、未來預估、無baseline/負庫存/缺數量/無股利/重複與衝突事件、原幣隔離與零股精度、year與exDate年份不同、取消/年度彙總/未知日期、tenant隔離；BFF測路徑參數/錯誤傳播。部署僅重建business-services/bff/frontend，保留設定與secret mounts，不動broker/外抓/報告/寄信。先驗收再commit/no-ff merge/push，合併後如main前進影響artifact需從乾淨main再次部署。

功能權限整合：DataInitializer.seedAppFeatures 以既有 insert-if-missing 慣例加入 `/annual-dividends`（資產管理／年度配息統計，enabledForUser=true）；保留其他功能設定，不改既有角色權限。新路由沿現有登入/功能可見性守門。

選單固定放在「交易紀錄」與「資產配置建議」之間（使用者指定）；權限主檔排序亦須一致。

## 驗證

以下新增測試是本任務待建立，非既有測試宣稱。

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest='*AnnualDividend*' test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml -Dtest='*AnnualDividend*' test
cd frontend && npm run build
```

使用run-stack完整流程，先對照main權威.env（僅回報一致性不輸出secret），只重建及recreate受影響服務；確認Docker healthy、帶既有合法登入/tenant上下文的summary安全GET回應與私人SQL結果一致；未登入不能讀私人資料。前端驗證新chunk、選單、年度/市場、錯誤/空狀態。不建開發server、不增加公開9090白名單。完成獨立diff-scoped架構審查，spec-check零BLOCK後依commit-merge-push提交、no-ff合併main並推送/遠端讀回。

## 完成報告

完成後端中立資料模型、純計算器、顯式 owner 的 JDBC read repository、Service／Controller、immutable DTO、本頁 BFF，以及 Vue 頁面／API wrapper／路由／指定選單位置。功能主檔新增並僅搬移兩個已知舊預設排序，保留 visibility 與客製值；無 schema／9090／SRPP 變更。

驗證：backend 18＋BFF 3，共 21 tests 全通過、0 skipped；PostgreSQL Testcontainers 實際驗證租戶隔離與 SQL。frontend build 成功。兩輪獨立 spec 審查完成，最終無 findings；獨立 diff-scoped 架構審查與 CSS 增量覆核皆無 findings；spec-check 零 BLOCK／CHECK。

run-stack 從 feature source 建置三個受影響服務，使用 main 權威部署設定與原正式掛載；expanded config 僅 build context 不同。business／BFF healthy，frontend SPA 200。2026 年 80 筆實際事件及原幣小計由獨立 DB／Python Decimal 核對，差異 0；另三個 active owner 各自對帳通過。6 種非法 query 400，無 tenant／未登入／偽造 caller headers 401，9090 新私人路徑 404；既有安全 GET 正常。部署環境與掛載一致，其他六個容器未改。私人原始結果僅留本機，不納入 Git。

對實際部署 bundle 以隔離瀏覽器攔截測試資料檢查：指定選單位置、初次後端年度、原幣小數／未知值、市場 code、年度回應競態、錯誤清除舊結果、空狀態、窄視窗收合側欄後篩選欄位。全部通過、page errors 0；這是 fixture 畫面驗證，不聲稱以正式登入 session 點擊。窄視窗驗收後補上純 CSS 尺寸上限，已獨立重建 frontend 與覆核。

計畫偏差：無功能契約變更。純配股判定在中立輸入加 exRightsDate，僅供既有除權資料識別，不新增輸出欄位。所有金額仍為已保存快照與獨立流水的稅前推估，未確認歷史流水完整或實際入帳。
