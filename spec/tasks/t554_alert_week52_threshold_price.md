# [t554] 警示條件顯示 52 週百分比對應價位

**對應 Requirements:** Requirement 164（延伸指標警示條件）
**前置任務:** 無
**Liquibase changeset:** 無

## 背景

警示清單的「52 週位置低於 50%」只有比例，使用者無法直接得知對應價位。延伸既有後端條件文字，不需前端重新計算。

## 要做什麼

- [x] 554.1 單一與群組 POS52W_ABOVE／POS52W_BELOW label 追加價位，支援0～100任意 threshold。區間共用既有 loadCompletedHighLowAsc：最近240根未還原完成日K、排除市場今日及之後；一般股票 stock_price_history、大盤台股0000為 twse_index_daily_history，高低缺值沿用收盤補位。再與 PriceQueryService.getLive 現價共同取高低，與原 week52Position 相同。不得改觸發計算或資料來源。
- [x] 554.2 純函式以 BigDecimal 算 low+(high-low)*threshold/100，四捨五入2位去尾零。例「52 週位置低於 50%（25000 點）」；台股0000加「 點」，股票沿用MA括號價格格式。歷史不足240、現價缺失／非正／非有限、等高低或讀取失敗顯示「（價位資料不足）」，不填0。
- [x] 554.3 群組只載入一次現價／高低，conditionLabel與conditions[].label共用上下文；非POS52W零新增行情讀取。既有static buildLabel／buildGroupLabel無上下文時保留百分比文字；不擅自改歷史通知、觸發匯出與觀察頁契約。BFF與Vue沿用既有字串render，DTO／API／DB／9090／SRPP不改、不下單。
- [x] 554.4 測試涵蓋50%、非50%及0/100、現價創新高低、一般股與大盤格式、歷史不足／等高低／現價無效、單條與複合response mapping共用查詢、例外降級及非POS52W無額外讀取。執行相關既有警示測試。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=StockAlertGroupLabelTest,StockAlertExtendedIndicatorTest test
git diff --check
```

依 run-stack 比對 main 有效環境與掛載後，只 rebuild/recreate business-services，等待 healthy，驗 gateway safe GET 與容器內既有警示清單唯讀JSON中的單條／群組條件字串。不得建立測試警示、假登入或手動觸發通知。若無登入session，明列UI未驗證；runtime API字串與前端既有render可提供實際呈現證據。驗收後feature commit、no-ff merge、push；已合併後若需部署只能由乾淨main執行。

## 完成報告

2026-11-06 完成。

- 只修改 `StockAlertService` 清單 mapping／range與label helper，及既有 `StockAlertGroupLabelTest`；未修改前端、BFF、DTO、DB、SRPP、觸發判定或歷史通知／匯出。
- spec-check：BLOCK 0／CHECK 0。獨立規格與架構審查皆 critical 0／major 0／minor 0，已記錄各自gate。4類警示單元測試共52項通過，含50/25/0/100、尾零與精度、現價創高低、歷史不足／無效現價、一般股與指數、單條／群組共用查詢與例外降級；`git diff --check` 通過。
- run-stack以繼承模型備援（派工工具無指定Terra可選），只重建並recreate business-services。feature設定從main同步，47個effective環境鍵與3個mount皆一致；business healthy、BFF UP、gateway safe GET回JSON，BFF未重啟。
- 實際截圖同條0000群組回傳「52 週位置低於 50%（38182.45 點） 且 D 值低於 20 且 K 值大於 D 值」。唯讀獨立驗算240完成K高49968.92／低26395.98、現價41574.72，50%為38182.45；現有5個群組／5個52週成員價位皆正確且合併與成員一致。目前資料無獨立52週列，該路徑已由單元測試覆蓋。
- 驗收image為 `sha256:db4081d9` 開頭，前後一致，完整值與去識別證據位於 `/tmp/t554-runtime-evidence.json`。登入後UI為 `UNVERIFIED_NO_SESSION`；前端原有render直接顯示新字串，未建立測試警示或假登入、未觸發通知。
- 收尾以feature commit／main no-ff merge及push交付。
