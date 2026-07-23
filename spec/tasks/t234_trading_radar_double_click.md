# [t234] 今日交易雷達雙擊開啟股票分析圖

**對應 Requirements:** Requirement 43（今日交易雷達個股決策表互動）
**前置任務:** t230 / t231 / t232
**Liquibase changeset:** 無

## 背景

今日交易雷達已提供規則結果、Excel 匯出與排程輸出，但個股列不能像儀表板、股票觀察與警示頁一樣雙擊開啟完整股票分析圖。使用者需要從雷達結果直接查看該股票的歷史走勢、當日分時與技術指標，同時不得破壞既有通知設定單擊與匯出排程區塊。

## 要做什麼

- [x] 234.1 `TradingRadarView.vue` 的個股表格監聽 Element Plus `row-dblclick`。
- [x] 234.2 雙擊時只傳入該列 `{ stockCode, stockName, market }`，開啟跨頁共用 `StockAnalysisDialog`。
- [x] 234.3 沿用既有 stock-analysis BFF、歷史回補、當日分時與技術指標，不新增雷達專屬 API 或圖表。
- [x] 234.4 保留通知設定按鈕 `@click.stop` 的單擊行為，並保留交易雷達匯出時間、輸出目錄與立即匯出功能。

## 驗證

```bash
npm --prefix frontend run build
bash scripts/spec-check.sh
git diff --check
```

重建並 recreate frontend 後，確認首頁 HTTP 200、新版 `TradingRadarView` 與 `StockAnalysisDialog` chunk 均存在；登入今日交易雷達後，雙擊任一個股列應彈出股票分析圖。

## 完成報告

已將 `StockAnalysisDialog` 接到交易雷達資料列雙擊事件，並在同步最新版 main 時保留完整 Excel 匯出與排程設定區塊。frontend production build、容器重建置與 HTTP/BFF 健康檢查成功。
