# [t441] 八頁匯出摘要卡與可取消編輯對話框

**對應 Requirements:** Requirement 159（八頁匯出摘要卡與可取消編輯對話框）
**前置任務:** 無
**Liquibase changeset:** 無

## 背景

舊8/24版卡dialog未落main，但其single-time設計已過期。當前八頁仍可直接在卡編輯，應依當前各頁設定contract建立draft，保留多time與所有其他功能。

## 要做什麼

- TransactionView、StockAlertView、AssetHistoryView、TradingRadarView、ExchangeRateView、CommodityPriceView、RealizedGainView、CrawlerDataView八頁現有匯出卡只呈現已保存摘要與編輯入口；所有原先可寫設定移至dialog，保留目前各頁多times／台美市場／enabled／輸出路徑／Drive及所有其餘功能，不套舊單時間版實作。
- 每次open從最新canonical已載入setting深複製draft（times子陣列不共享）；draft增刪時點／toggle／browse路徑不寫canonical、不自動呼叫update。Cancel、X、ESC、遮罩關閉都丟棄draft、零write；browse若只讀沿用原contract。save既有endpoint/body/normalize/validate，只busy=false時允許一次請求；busy期間禁重複save、close、runNow與移除時點，避免不一致。
- save成功以server canonical response替換摘要並關閉；失敗dialog保持draft與錯誤、不關閉／不重讀覆蓋草稿，不假裝保存成功；再次開啟用新canonical。runNow與現有狀態刷新沿用原行為且不隱式保存draft，頁面其他交易／CRUD／圖表／SSE／查詢不變；不新增API、DB、排程或券商動作。

- [x] 441.1 只修改frontend/src/views/TransactionView.vue、StockAlertView.vue、AssetHistoryView.vue、TradingRadarView.vue、ExchangeRateView.vue、CommodityPriceView.vue、RealizedGainView.vue、CrawlerDataView.vue及必要共享UI helper。前端實作subagent固定gpt-5.6-terra/high，其他code不擴張。摘要保留enabled、全部已保存time/market、local/Drive destination、last result與既有warning；僅摘要不放可write控制。
- [x] 441.2 保留每頁目前資料DTO/原BFF endpoint/body、times array與hour/minute/每time enabled/server id，不退成single hour。Transaction台美設定分開deep clone不相互污染，ExchangeRate採目前多daily time。保存draft沿用既有排序/dedupe/最大times/路徑validate和readonly server欄處理，不發明新business規則。
- [x] 441.3 dialog只用draft，browse返回只寫draft；open從canonical深複製，Cancel/X/ESC/backdrop均無write。save單次busy，close/save/runNow/browse/增刪/toggle衝突操作鎖住；以server response取代canonical。失敗保持所有draft/error，下一次save可重試，不自動reload。runNow保持原saved setting動作，dialog dirty時不隱式save（禁runNow或明確只用saved設定），現有手動run結果不被draft帶動。
- [x] 441.4 不改股票/交易/已實現CRUD、圖表/查詢、SSE及其按鈕。保留表單label、keyboard與dialog可達操作、loading/error訊息，不加endpoint/cache/db/backend。

## 驗證

```bash
bash scripts/spec-check.sh
cd frontend
/Users/steven/.nvm/versions/node/v22.21.0/bin/npm run build
```

以本機component或現有contract runner fake API測8頁open改draft-cancel zero PUT、close/ESC/backdrop、times nested隔離、兩market隔離、save一次click雙點zero duplicate、save成功server normalization、failed draft保留、browse不write、runNow不暗中保存、既有warning與其他CRUD。測試設定均fixture，不觸正式setting update。Docker重建frontend，authenticated browser只讀摘要/開dialog/cancel，禁止保存實機設定或runNow；無現有authorized browser session記錄待證，不以public9090代替。


驗收不呼叫券商、不下單、不觸發外部通知，不讀出secret。新增單元／整合測試為待實作，不以不存在的具名測試類作已完成證據。實作後依project規定作獨立diff-scoped架構審查、Docker image rebuild/container recreate（從main同步環境或先比對.env，不輸出值）與只讀功能驗收；不以container healthy取代資料語意。最後自動focused commit/no-ff merge/push，main部署與版本一致。

## 完成報告

八頁已完成已儲存摘要與深複製草稿對話框；多時間點、各頁原有設定與 BFF 保留。前端57項測試／Vite build 通過；實際 Docker 提供的八頁隔離瀏覽器 fixture 皆通過取消／X／ESC／遮罩零寫、新增時間後取消隔離、失敗保留草稿、busy 關閉／重複儲存守門，以及 server canonical 回應替換。八頁 pageerror=0；所有 API 與寫入均 fixture 攔截，這不是正式登入資料或正式設定寫入證據。

本批後端最新256組測試報告共2214項、失敗0／錯誤0；BFF全套67組316項、失敗0／錯誤0。獨立架構最終覆核0 critical／0 major／0 minor；機械 spec／OpenAPI／兩鏡像／102表 schema drift 已通過。feature business／frontend／BFF 均已重建並重新部署驗收；分組提交、no-ff main 合併推送與 main 最終重建於本批收尾執行，不由測試替代部署。
