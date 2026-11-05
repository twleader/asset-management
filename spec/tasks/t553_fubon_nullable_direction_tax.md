# [t553] 保留未知方向稅額並恢復未來交割同步

**對應 Requirements:** Requirement 129（富邦未來交割款投影）
**前置任務:** 無
**Liquibase changeset:** 無

## 背景

2026-11-05 真 SDK 回傳 source date11/04、settlement11/06、buy_value10020、buy_fee20、buy_settlement-10040、buy_tax=null、sell_value/fee/settlement=0、sell_tax=null、total_bs_value10020、total_fee20、total_tax0、total_settlement_amount-10040。另一列11/05→11/09為buy_value43120、buy_fee61、buy_settlement-43181、taxes及sell全0、total_bs_value43120、total_fee61、total_tax0、total_settlement_amount-43181。第一列 partial-null 使整批拒絕，第二列漏入帳，已精確恢復第二列但程式根因待修。

## 要做什麼

- [x] 553.1 Python `SettlementService` 只允許原存在的 buy_tax/sell_tax key 為 explicit None，正規化 buyTax/sellTax 保留 null，不猜0、不推算。缺key、其他十個金額null、錯型別／浮點／科學記號／overflow仍整批拒絕。非null方向稅額仍 signed exact integer precision≤20。全null placeholder 與先排除到期列規則保留；帳戶/row identity、日期、TWD、direction sign及兩方向等於來源total、duplicate檢核不放寬。
- [x] 553.2 Java immutable `FubonDtos.SettlementDay` 兩方向稅額key required但值nullable，strict codec區分缺key與null；`FubonAccountingContract` 對required十金額和optional兩稅額分開驗，placeholder仍所有null。傳輸null不能轉0或浮點。writer仍只投影來源buySettlement/sellSettlement，不能加減稅額、推日期或修改其他金額／時段／flags／source-report／公共金融Swagger／SRPP契約。
- [x] 553.3 Python單元與app route、Java真HTTP codec與PostgreSQL writer回歸：兩future日期、tax null/0/nonzero、單欄null、missing各欄、malformed tax、required amount null、wrong account/date/currency/sign/total、duplicate仍拒絕；完整batch可合計-53221，原兩筆source-owned列與notes不被覆寫、重跑無重複，其他存款與ledger無改。測試用隔離fake fixtures且FUBON_ENABLED=false，不將fixture灌正式DB；更新內部adapter文件說明nullable稅額契約。
- [x] 553.4 實機只重建/recreate business-services、fubon-broker-service，先核對main權威.env及secrets effective配置且不輸出秘密。合法dry-run後用既有settlement-sync正式同步兩次，查真SDK完整batch應付53221、兩處理日列金額/來源/notes及其餘資料不變；只唯讀券商，不下單／移撥。驗收與獨立arch review後兩段式commit/noff main/push，再由乾淨main重建必要服務並回讀。

## 驗證

```bash
bash scripts/spec-check.sh
PYTHONPATH=fubon-broker-service/src python3 -m pytest fubon-broker-service/tests
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest='*FubonAccounting*,*FubonSettlement*,*FubonPayable*' test
docker compose -p asset-management build business-services fubon-broker-service
docker compose -p asset-management up -d --no-deps --force-recreate business-services fubon-broker-service
```

實機依run-stack完成.env/secrets/健康/BFF安全GET查核，再用受保護的既有 `/internal/brokers/fubon/settlement-sync?dryRun=true` 和 `dryRun=false`（兩次）查核真实來源及幂等；token只從掛載檔讀且不輸出。最後純讀9090 `/api/assets/latest` 及正式DB，原有手動存款/ledger和兩個日期source款逐欄比對。

## 完成報告

- 已實作Python explicit-null兩方向稅額保留、Java required-key immutable DTO與分離驗證；其餘十金額、placeholder、identity/date/sign/total/duplicate檢核不變。writer、公開API、金融Swagger、SRPP、排程與DB schema未變。
- Python全suite 1001項通過（Docker禁網路、FUBON_ENABLED=false、原共享fixtures唯讀掛載）；Java聚焦accounting/settlement/payable 217項通過，0 failure/error/skipped，包含28項真PostgreSQL writer測試。首輪新增測試的缺header與detached測試entity ID取值已修，原fixture掛載缺漏亦已補齊；正式碼沒有以放寬防線消除紅燈。
- 獨立spec與arch審查均0 findings；spec-check BLOCK0/CHECK0、公開文件mirror及117表schema drift通過。spec／arch gates已記錄。
- Feature已依run-stack只重建並recreate business-services/fubon-broker-service，模型依工具無gpt-5.6-terra之既定fallback繼承主agent。main→feature部署設定一致，實際env/mounts逐項吻合，其他7容器ID/image未動，BFF不中斷且safeGET200，host8080/8082無監聽。
- 2026-11-05真來源dry-run DRY_RUN與正式同步SUCCESS兩次，全部rowCount2、payable-53221.00、receivable0。id16761為11/06 -10040、id16807為11/09 -43181，兩筆source/notes/日期/金額未變，所有deposit與ledger逐欄不變，API顯示AUTO與總待付71519。未用假SDK資料灌正式DB，沒有券商寫入。
- 安全證據 `/tmp/t553-feature-acceptance.json` 權限0600；feature images business1948d3a8548f、fubone83f95218386。收尾依既定skill將已驗收功能commit、no-ff main merge及push，再從乾淨main重建同兩服務，最終SHA／映像與回讀由發布結果記錄（此報告在該收尾前寫入）。
