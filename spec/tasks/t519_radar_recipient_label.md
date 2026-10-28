# [t519] 今日交易雷達通知收件人顯示名稱

**對應 Requirements:** Requirement 44（交易雷達逐檔通知對象顯示 email 與選填名稱）
**前置任務:** t509、t518
**Liquibase changeset:** 無

## 背景

股票警示已顯示 `email [名稱]`，使用者要求今日交易雷達也同步。雷達使用獨立的 RecipientOption，目前只有 id、email、active，因此需補上既有收件人名稱映射，不能只修改畫面。

## 要做什麼

- [x] 519.1 `TradingRadarNotificationDto.RecipientOption` 維持 immutable record，末尾追加 nullable `String name`；`TradingRadarNotificationSettingService.toResponse` 以既有 owner-scoped `NotificationRecipientRepository.findAllByOrderByCreatedAtAsc()` 的實體 `getName()` 填入，不新增查詢、不猜測或回填名稱。GET 與 PUT 共用此 Response 映射。Request／DB／寄送身分不變。
- [x] 519.2 `TradingRadarView.vue` 通知設定的 Email 收件人勾選標籤顯示 `email [名稱]`（email 後一個空格、半形方括號）；name 缺少／null／空字串／純空白時只顯示 email，不顯示空括號或占位。採 Vue 文字插值、不可 v-html；長 email／100 字名稱須換行、不得超出對話框，保留停用提示。key/value、recipientIds、狀態選取、啟停及儲存流程完全沿用。
- [x] 519.3 沿用既有本頁 `/api/bff/trading-radar/notifications/{stockCode}?market=台股`，不新增路由、不跨頁取名、不修改其他頁面或公開 9090 API、通知派送／基準／排程、日曆或資料庫。
- [x] 519.4 新增聚焦 service unit test 驗證既有 owner-scoped read 所回傳的具名與無名稱收件人，以及 id/email/active 均保留，get 與 save 共用映射可由程式查證；不啟動 Spring context 或資料庫。以實際 served frontend 的隔離瀏覽器 fixtures 驗證名稱／缺名／停用／HTML 特殊字元／長名稱換行、勾選仍為 id。不得儲存真實設定或寄信。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest='TradingRadarNotification*Test' test
docker compose -p asset-management build business-services frontend
docker compose -p asset-management up -d --no-deps --force-recreate business-services
docker compose -p asset-management up -d --no-deps --force-recreate frontend
curl -fsSI http://localhost/
docker inspect --format '{{.State.Status}}' asset-frontend
```

建置前比對 main 與 feature 的部署設定，秘密值不可輸出；business-services 健康後確認 BFF safe GET 已恢復，BFF 不例行重啟。從實際 served app 開啟通知設定，以隔離 fixtures 驗證具名、null、缺欄位、空字串、純空白、停用、HTML 特殊字元及100字長名稱，保留原勾選 id。merge 後從乾淨 main 再 build/recreate 並重驗，不使用 feature 舊映像宣稱正式完成。

## 完成報告

- 變更：RecipientOption 末尾追加 nullable name、既有 toResponse 直接映射 getName；TradingRadarView 安全插值顯示 email [名稱]、缺名 fallback 與長標籤換行。Request、id 選取、通知基準與寄送流程、DB及公開路由均未修改。
- 新增 pure Mockito service test，涵蓋具名／null、id/email/active 與既有 recipientIds。Java25 的四套通知測試共23項，0 failures/errors/skipped；spec-check BLOCK0/CHECK0，diff-check通過。獨立 spec 及架構審查皆 critical0/major0/minor0，已記錄通過。
- feature 已重建/recreate business-services（healthy）與 frontend（HTTP200），BFF保持原容器並在backend健康後safe market-index回200；其餘7容器未變，部署設定與main一致。runtime JAR驗證 DTO name及service getName映射，served frontend JS/CSS驗證名稱標籤與換行。
- 實際執行中的 owner-scoped read-only notification GET 與同owner持久化資料逐欄比對：5位收件人名稱及id/email/active完全相同；無資料寫入。
- 隔離Chromium驗證實際served頁面8種情境：具名、null、缺欄位、空字串、純空白、停用、HTML特殊字元與100字名稱；名稱顯示／換行、既有選取及mock儲存recipientIds正確，pageErrors0、real API writes0。未儲存真實通知設定或寄信。
- 無功能範圍偏差。
