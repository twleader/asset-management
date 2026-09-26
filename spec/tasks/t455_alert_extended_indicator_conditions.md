# [t455] 警示條件新增 RSI5、BIAS10、52 週位置、W%R9 與 K>D／K<D

**對應 Requirements:** Requirement 164
**前置任務:** 無（Task 253 複合條件、Task 261／262 延伸指標已 landed）
**Liquibase changeset:** 無（沿用 `stock_alert.alert_type` VARCHAR(50)，不新增欄位）

## 背景

警示條件目前只有價位、MA 偏離與 KD 門檻。`TechnicalIndicatorService.computeAll()` 的 `ExtendedIndicators` 早已算出 RSI5／BIAS10／W%R9，走勢圖也有；52 週位置在交易雷達有同義概念。本任務把這些指標開放為警示條件。

## 要做什麼

- [x] 455.1 **後端判定** `backend/.../service/StockAlertService.java`：`VALID_ALERT_TYPES` 加入 10 個新類型；`matches()` 新增分支：RSI5／BIAS10／WR9／K>D／K<D 讀 `indicatorService.computeAll(code, market)`（內部已自動分派 `0000`，不另判斷）；POS52W 以新純函式 `static Double week52Position(double currentPrice, List<double[]> highLowAsc)`（每筆 `{high, low}`，缺值由呼叫端以收盤代入；筆數 < 240 或 高=低 回 null）計算，取最近 240 根完成日 K（不含今日列，今日由現價代表）：一般股票讀 `stock_price_history`，`0000` 讀 `twse_index_daily_history`（新注入 `TwseIndexDailyHistoryRepository`）。同一次評估中同一檔股票的 `computeAll` 與高低序列以 lazy 快取共用、最多各查一次。任何所需指標為 null → false。`findRecentIntradayTrigger` 對新類型直接回 `Optional.empty()`。觸發時沿用既有 MA／K／D 凍結流程（新指標觸發值不留存，刻意取捨）。`StockAlert.java` 的類型 javadoc 同步。
- [x] 455.2 **門檻驗證**：新增單條件驗證靜態函式，單一條件 create/update（目前無任何類型驗證）與 `validateConditions` 共用；對所有類型檢查白名單、threshold 必填、maPeriod 規則，新類型另檢查：RSI5／POS52W／WR9 threshold ∈ [0,100]；BIAS10 不限；`KD_K_GT_D`／`KD_K_LT_D` threshold 必須 = 0；新類型不得帶 maPeriod。錯誤回 400（`IllegalArgumentException`）。
- [x] 455.3 **label**：`buildLabel` 新增 10 個分支，文案見 Requirement 164。
- [x] 455.4 **前端** `frontend/src/views/StockAlertView.vue`：單一與複合兩處條件類型下拉加入 RSI5、BIAS10（10 日乖離）、52 週位置、W%R9（預設門檻 80，BIAS10 預設 5，可負）；KD 值指標選項加入「K 大於 D」「K 小於 D」（隱藏方向與門檻、送 0）。同步修改 `buildAlertType`、`parseAlertType`（`KD_K_GT_D`／`KD_K_LT_D` 須先於 `KD_D`／`KD_` 分支判斷）、條件類型切換預設值重設、複合條件 `conditionPreview`（與後端 label 逐字一致）。
- [x] 455.5 **測試**：`StockAlertGroupValidationTest` 補新類型正反例；新增 52 週位置純函式與 `buildLabel` 新類型的單元測試（不啟 Spring）。
- [x] 455.6 **驗收**：`mvn test` 相關測試綠燈、前端 build 通過；`/run-stack` 重建 business-services 與 frontend 後，於容器內建立一筆 RSI5 與一筆 K>D 條件確認 200 與 label 正確，並刪除測試資料。

## 完成報告

- 後端：`StockAlertService` 新增 10 個類型、共用單條件驗證 `validateCondition`、`EvalCache` lazy 快取、`week52Position` 純函式（`0000` 讀 `TwseIndexDailyHistoryRepository`）、label 與盤中補抓排除；`mvn test -Dtest='StockAlert*'` 66 個測試全綠（新增 `StockAlertExtendedIndicatorTest`）。
- 前端：`StockAlertView.vue` 單一／複合表單加入四項指標與 K>D／K<D，`buildAlertType`／`parseAlertType`／預覽同步。
- 架構查核：0 critical／0 major；1 minor（52 週位置與雷達口徑差異）已於 Requirement 164 註明為刻意取捨。
- 部署驗收（2026-09-26）：`--no-cache` 重建 business-services、frontend 並 recreate，均 healthy；容器內建立 `RSI5_ABOVE 97.5`、`KD_K_GT_D 0` 皆 200，label 為「RSI5 高於 97.5」「K 值大於 D 值」；`KD_K_LT_D` 門檻 5 回 400；前端 bundle 含新文案；測試資料已刪除。
