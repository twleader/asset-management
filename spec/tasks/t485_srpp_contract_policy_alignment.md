# Task 485：SRPP 決策佔位契約與既有政策對齊

**對應 Requirements:** Requirement 182／183；僅修正現況契約與未實作設計，不啟用 Task 482。

## 需求

目前 `SrppCaptureService.evaluate` 驗證五個必填字串後回 503 `CONTEXT_NOT_READY`，不產生 FINAL。OpenAPI 的封閉 object 缺 properties，卻宣告五個 required，連範例也不合法；200／201 範例誤示已實作收據。Task 482 的可交易性來源缺失規格也偏離已接受的 SRPP D-144。

## 設計

- 既有路徑不變。OpenAPI 升至 1.23.0，新增封閉 `SrppDailyDecisionRequest`：必填 ownerEmail（email）、tradingDate（date，伺服器臺北今天）、slot（09:05／11:40）、兩個小寫 hex64 hashes。未知欄位不允許。此為目前佔位契約；Task 482 的 optional ownerEmail 是未來設計。
- evaluate 僅文件化 400／409（NON_TRADING_DAY）／415／500／502（BFF upstream 失敗）／503（CONTEXT_NOT_READY 或 CALENDAR_UNAVAILABLE）；移除尚不可產生的 200／201，標示未實作。Task 482 實作時再加入成功與處理中 schema。
- 不改 Java、資料庫、路由或 owner 解讀；保留佔位 fail-closed。
- Requirement 182、design 與 t482 統一 D-144：來源缺失是 L2，只收緊第一檔限價、不減張數；既有五檔累計深度不足與清單命中仍 L1。掛單／子帳缺失維持阻擋。
- YAML 是唯一正本；重新產生 repo Markdown 與 backend classpath。部署 business-services 完成前不更新 SRPP 執行中的 Swagger 鏡像，以免 published hash 不一致；部署時三份必須一致。

## 驗收

- [ ] 獨立 spec 審查與機械檢核。
- [ ] OpenAPI 契約測試鎖定五欄型別／required／additionalProperties／slot／hash／範例合法與成功狀態不存在；renderer 一致。
- [ ] Docker business-services 重建／重建容器後 published Swagger identity 與三份文件一致，既有 event-evidence 契約保持一致；不執行交易或寄信。
- [ ] focused commit → no-ff merge → push；若部署驗收未完成則保留 feature，不宣稱已上線。
