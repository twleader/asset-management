# [t560] 交易紀錄共用資產類別選單與分類設定

**對應 Requirements:** Requirements 25（共用資產類別歸類）／49（owner-scoped交易紀錄）
**前置任務:** 無
**Liquibase changeset:** 無

## 背景

使用者要求交易新增/編輯「資產類型」改「資產類別」，選項應為自動（規則）與既有DB債券／股票。原asset_type股票／基金實際是商品種類；基金可屬債券或股票，券商/成本等依此判斷，不可將BOND覆寫asset_type。global分類authority已存在stock.asset_class與fund_class_override.asset_class，設定寫入限ADMIN。市場只有台股/美股/英股，無可靠市場欄可推斷新基金，故名稱旁保留小型「基金交易」checkbox取代原商品下拉，不新增第二個大型下拉。

## 要做什麼

- [x] 560.1 新增交易專用分類service，effective分類完全委派原AssetClassifier，讀寫共用stock.asset_class(code+market)、fund_class_override.asset_class(完整fund_name)。寫使用既有InstitutionService.setSecurityAssetClass，與ledger同一DB REQUIRED transaction，不加分類DB欄、不建新主檔、不改schema/9090/Swagger/SRPP/券商/匯出/金額公式。list採批次讀authority，避免逐筆N+1。
- [x] 560.2 authenticated CreateAssetTransactionRequest末尾加選填assetClassChange物件（兼容舊constructor/client）：省略/null不改分類；物件assetClass=null/空白清override；非空只接受DB存在非CASH code（含inactive，完全沿用分類頁authority，不新增active限制或改settings行為），非自創enum。response加assetClassOverride/effectiveAssetClass/assetClassAssignable衍生欄。create/update先驗當前owner或by-id歸屬，再驗CurrentUserContext.isAdmin才可分類更動；USER分類修改403、非法category/identity400、外人交易404，全部零mutation。ledger或classification flush/commit失敗兩者rollback；改identity只改新identity，delete交易不改global設定。
- [x] 560.3 新增純讀GET /api/asset-transactions/classification，以assetType(股票/基金)、assetCode、market、assetName讀override/effective/assignable，不外呼/建檔。股票missing code/market/本地master及台股0000不可分類指定/清除（400）；其自動或無categoryChange的手動交易仍可儲存。基金非空、<=150字名稱為valid identity，透過既有fund override authority建立/清除，不猜fund_code。普通USER可讀但不可改。category既有inactive值依分類頁規則可顯示及指定；legacyCASH只可保留顯示，不可由新選單指定CASH。
- [x] 560.4 TransactionBffController list聚合另取/api/settings/asset-classes作categories；增加本頁/classification代理，不跨用settings頁BFF。create/update/classification完整穿透400/403/404與ProblemDetail，禁止改成成功或500。UI只走本頁BFF；不改public DTO或對外契約。
- [x] 560.5 TransactionView原位置換label資產類別，下拉自動（規則）＋DB categories既有排序且排CASH，名稱旁基金交易checkbox維持assetType股票/基金。列表原資產類型欄用effective類別及DB名稱；前端不推算分類。選單草稿baseline/dirty與instrument identity綁定，身份變更重新查分類且清前identity未存選擇，request序號拒舊回應。lookup載入錯誤/未完成不能提交分類變動；無valid股票target仍可儲存自動交易但指定選項disabled並清楚說明。普通USER下拉唯讀，仍可存自己交易；未變選擇省略change，避免普通USER被拒及意外清override。取消不寫分類；提示與資產類別歸類共用並套同標的。自動新交易無target省略change，明確changed分類無target不得悄悄忽略。
- [x] 560.6 測試：股票債券ETF與股票型/債券型基金沿原rule/override、跨頁authority一致；DB新增category及inactive非CASH可用、legacyCASH保留但新指定CASH拒、auto清除且保留style/term、未知stock與無code自動可存但明確指定400、基金名稱identity/長度、USER偽造403與原樣可存、外人404先於global寫、classification/ledger雙向flush/commit rollback、舊client省略保留override、response不改assetType及金額。BFF categories與錯誤穿透，前端draft取消/身份切換/舊回應/基金入口/disabled/dirty測試與build。run-stack重建recreate business-services/bff/frontend並實際驗證新增/編輯與settings一致，實帳戶不建立假交易；可用既有記錄開啟/取消及安全讀取驗收。測試mutation使用隔離DB/fixture。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest='*AssetTransaction*,*TransactionAssetClass*' test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml -Dtest='*TransactionBff*' test
cd frontend
npm test
npm run build
```

run-stack從權威設定重建business-services/bff/frontend，確認健康與登入頁面/BFF安全GET可用。以隔離測試證明原子儲存與權限；實機只讀分類及開dialog取消，不新增假交易/更改投資資料。獨立arch審查後commit-merge-push收尾。

## 完成報告

- 完成交易專用分類service、選填assetClassChange與衍生分類response、batch authority讀取，以及同頁classification GET／BFF categories與400/403/404穿透。dialog原位置改為資產類別，基金交易checkbox保留原assetType；分類草稿與identity綁定、取消不寫、過期回應丟棄。新股票缺master可自動記錄，分類變更仍受valid target及ADMIN guard保護。global authority與ledger同一transaction，不新增分類欄，不改public DTO／Swagger／SRPP／券商／schema／金額或匯出。
- Backend聚焦5 suites共51項、BFF 2項全過，0 failure/error/skipped。真PostgreSQL4項驗證實際InstitutionService共用fund authority、同標的兩筆交易即時分類、舊client/USER原樣儲存、403/400/外人拒絕、auto清除保留style/term、deferred commit拒ledger時global override與ledger全部rollback，以及update分類拒絕後原ledger名稱恢復。紀錄：`/tmp/transaction-category-backend-tests-r2.log`、`/tmp/transaction-category-postgres-final.log`、`/tmp/transaction-category-bff-tests.log`。
- Frontend完整npm test共172項：171通過、1項既有baseline失敗。唯一失敗為openApiContract.test.js仍預期金融26／union27，而目前main已有金融27／union28；在未修改main獨立重跑同檔同樣失敗，未更改無關公共契約測試。新draft5項全過，含先選BOND後晚name lookup不可洗掉草稿、原缺master在name lookup完成後可重讀分類。npm run build成功，僅既有chunk大小提醒。紀錄：`/tmp/transaction-category-frontend-tests-r2.log`、`/tmp/transaction-category-main-baseline.log`、`/tmp/transaction-category-draft-tests-r2.log`、`/tmp/transaction-category-frontend-build-r2.log`。
- 獨立spec R2 critical0／major0／minor0，機械BLOCK0／CHECK0；arch首輪指出晚name lookup洗掉選擇，已限同股票identity、原target不可指定且無pending/error/dirty時才refresh，兩項回歸通過。獨立arch複核0／0／0，gate hash前綴0719d18814fa仍有效。最終spec-check BLOCK0／CHECK0、git diff --check通過，紀錄`/tmp/transaction-category-final-check.log`。
- run-stack已重建recreate business-services／bff／frontend，三服務有效env與mount符合main，安全GET正常。已部署frontend bundle的browser驗收採identity fixture與live唯讀business GET資料，**未使用真登入session**；兩頁選項皆依DB順序為自動（規則）／債券／股票，編輯選分類取消與新增基金checkbox取消均零writes，pageErrors空，主agent檢視截圖確認布局正常。此驗收證明已部署bundle互動與唯讀來源接合，不聲稱完成真登入或正式資料寫入驗收。完整ledger與stock/fund分類hash前後不變；證據`/tmp/transaction-category-ui/result.json`及`/tmp/transaction-category-data-unchanged.json`。
- 驗收後依commit-merge-push執行feature commit/push、main no-ff merge/push及remote ref回讀；最終SHA於發布回報提供。本段只回填已發生的完成事實，不再改功能契約或程式。
