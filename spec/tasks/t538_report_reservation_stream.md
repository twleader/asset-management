# [t538] 串流完整驗證歷史保留額度來源

**對應 Requirements:** Requirement 187（不可變報告計算）
**前置任務:** t537
**Liquibase changeset:** 無

## 背景

Task537晚間背景23:39已開始，真實thread dump顯示scheduled-1在readReportSource完整驗證歷史包。owner1有959distinctbase、7344MiB，原repository回傳List<String>佔用數GBheap，造成API長時間503。必須有界讀取且完整保留所有證據，不以跳過歷史保留額度換成功。

## 要做什麼

- [ ] 538.1 僅修改SrppReportRepository兩個歷史報告reservation查詢為交易內cursor visitor(callback)。維持原SQL全部predicates、DISTINCT ON與sort，包含跨週、舊policy與全部prior包，不filter actionable、不加LIMIT／日期裁切。setFetchSize(1)，交易保證autoCommit=false，ResultSet／statement自動close。不得在stream返回transaction外才使用；無外部HTTP/vendor/broker或寫來源。
- [ ] 538.2 SrppReportReservationCapture兩處逐列callback處理，原envelope validation、hash、owner／leg驗證及reservation projection完全原樣；只累積必要的小型projection，不能收集原content字串或JsonNode列表。任何中途壞資料／callback錯誤整輪fail closed，不交部分通過結果，不清零／猜測保留額度。原決策reservation流程及金融calculator不變。
- [ ] 538.3 增加真實Postgres測試確認交易內autoCommit=false、fetchSize=1、每列callback與跨policy/週資料不漏；與原方法有效fixture的輸出逐值相同，前段正常後段壞包仍拒絕，callback失敗釋放資源。既有reservation／publisher／readonlyGET整合測試通過。只改內部讀取實作，不更動Swagger／sourcepolicy／hash（仍7a1f8ea0）。
- [ ] 538.4 獨立spec／架構review與測試後依規範feature rebuild/recreate驗收、commit/noffmerge/push，再乾淨main rebuild。只business-services，正式env／其他容器保持。不手動producertrigger或POST，等正常背景capture，精確新hash API200與兩次byte-identical replay、directL0消費端驗證及非可執行marker。晚間只診斷驗收，不代表正式金融採納。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=SrppReportRepositoryPostgresTest,SrppReportReservationCaptureTest,SrppReportProducerSchedulerTest test
git diff --check
```

## 完成報告

- Repository以交易內cursor visitor與fetchSize=1取代兩個巨大List回傳；capture只將原迴圈改為callback，所有原SQL、owner/date/slot/跨週/舊policy範圍、完整validator與projection原樣保留。沒有model、公式、API、Swagger或hash變更。
- 三類Java36tests中35實際通過，唯一略過是原有opt-in大小探測；20個真實PostgreSQL測試全部執行，證明autoCommit=false、fetchSize=1、全列訪問、callback失敗釋放statement／可重讀，以及合法前段後的實際壞DB包仍整體拒絕。獨立spec／architecture無findings，機械spec BLOCK0／CHECK0。
- 本修復前已實際取得新hash7a1f8ea0的200與固定/latest重播逐位元一致、fresh直接L0與獨立consumer verifier PASS；七PARTIAL／五UNAVAILABLE與非可執行marker如實保留。串流修復不重新觸發producer或為效能測試創造新金融包；部署後須核對既有6包ID／contenthash不變及真實200重播。
- 測試紀錄 `/private/tmp/asset-stream-tests/`；Git、部署、immutable包前後對帳與實際API收據 `/private/tmp/asset-reservation-stream-release/`。驗證證明有界讀取，不宣稱已量測新的7GB cold backfill速度。
