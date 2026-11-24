# [t630] 美股交易雷達個股通知

**對應 Requirements:** Requirement 44（逐檔交易雷達狀態 Email 通知）
**前置任務:** t294、t301、t302
**Liquibase changeset:** 無

## 背景

美股個股已顯示通知按鈕，前端與 BFF 已傳市場。設定 service 拒絕美股，背景通知佇列也只接受台股，即使已有美股決策與 price-update 事件。

## 要做什麼

- [ ] 630.1 既有登入後通知 GET／PUT 接受台股、美股，維持 owner／收件人歸屬、拒絕其他市場與台股大盤。沿用設定與狀態資料表。
- [ ] 630.2 `PriceStreamService` 只將明示 `LIVE` 且 `closed=false` 的美股個股事件送給通知佇列，沿用既有 Redis 事件、SSE 與到價警示。過濾美股無有效 LIVE 決策或降級的評估，不更新基準、不派送。沿用現有美股大盤 snapshot 與個股決策入口，不拉新行情、不啟動新排程。
- [ ] 630.3 批次依市場、代號或台股 0000 查 active 設定，0000 只展開台股，不能蓋掉美股事件。同輪每個市場組一次快照、每個 setting 評估一次。沿用首次基準、後續狀態轉入與 60 分鐘冷卻。
- [ ] 630.4 Email 每筆顯示決策的市場；沿用每位收件人每輪合併。維持中期與逆勢通知語意，不變更規則、通知 API 形狀、DB 或券商整合。

## 驗證

- [ ] 以既有 `TradingRadarNotificationSettingServiceTest`、`TradingRadarNotificationServiceTest`、`TradingRadarNotificationMarketBatchTest` 擴充驗證：美股 GET／PUT、無效市場、個股事件入列、非 LIVE 事件過濾、0000 與美股同輪、每市場一份快照、首次／後續／冷卻及 Email 市場標籤。以替身驗證寄信，不手動寄測試信。
- [ ] 執行 `/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -f backend/pom.xml '-Dtest=TradingRadarNotification*' test`，依既有部署流程重建並重建啟動 business-services，驗證服務健康與既有台／美股雷達讀取。

## 上線與回退

驗收後合併至 main 並推送。回退以反轉該次程式變更並重建 business-services 執行，保留已儲存設定。
