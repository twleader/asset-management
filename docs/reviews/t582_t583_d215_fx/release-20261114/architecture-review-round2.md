VERDICT: PASS

## 判定

critical: 0   major: 0   minor: 0

審查範圍：以 `/private/tmp/d215-d583-arch-review/materials-round1.md` 的 ARCH1 快照比對目前工作目錄，限定兩支 production service 與兩支測試的日期修正。另追查 `BacktestService` 呼叫端、四份相關 spec、原始資料及最新 Surefire XML。四個檔案 SHA 均符合指定快照，結束前再次核對未變動。全程唯讀。

## Findings

無可成立的新增架構違規或日期修正 finding。

## 已查無發現

以快照差異比對、`git diff`、`grep -ran` 及唯讀解析逐項查證：

| 檢查項目 | 證據與結果 |
|---|---|
| actualD 與時鐘截點 | `UsdFxPurchaseContext.java:160–174` 先以台北 17:00 決定 cutoff，再取已驗證 USD 成員的最大日期；要求 capture 不晚於 knownAt。TW 完成日僅作下限，actualD 落後即不可用。 |
| LIVE／歷史窗口一致 | 同檔 `:124–127、146–149` 皆以原決策時點的台北日期作 windowEnd，起點減兩曆年；completedAsOfDate 獨立保存 actualD。 |
| coverage 與銀行觀察分工 | 同檔 `:185` 仍要求 `nativeQueryCoverage(selected, end.minusYears(2), completed)`；`:204` 起僅採 FinMind query interval 證明 coverage。銀行觀察不補查詢缺口。 |
| production 三條讀取路徑 | `TradingRadarMarketContextService.java:371、398、436` 的單檔、批次、cached-only 查詢皆延伸至 clock cutoff，沿用各自一次 repository 查詢。 |
| 舊五年 MID 分數 | 同檔 `:488` 仍以 `!rateDate.isAfter(target)` 篩選，未把 TW 休市期間新增銀行列納入舊分數。新增測試 `TradingRadarMarketContextServiceTest.java:253` 同時檢查三條路徑及查詢次數。 |
| 回測原始時點 | 同檔 `:464、477、496` 將原 decisionInstant 傳給 historical factory；`BacktestService.java:4409、4415–4431` 保留 signalInstant。該呼叫端與 ARCH1 相同，未改成合成 TW target 17:00。 |
| 防前視與診斷標記 | `UsdFxPurchaseContext.java:154、165、183、191` 保留 capture 時間檢查；historical quality 仍為 `HISTORICAL_PROXY` 或不可用，未冒稱 LIVE。 |
| 模組與分層 | 兩支 production 檔以 `grep -ran` 查 HTTP client、response、SQL writer、upsert 及券商交易呼叫均無命中。新增日期邏輯留在 Service，符合 `structure.md` §2.2、§7、§8。 |
| BFF／資料模型 | 本輪 delta 無新增頁面、BFF、Controller、Repository、Entity、公開 DTO、分類或 migration；跨頁呼叫、前端聚合、panel 並行及 changeset 檢查不適用。金額仍為 BigDecimal，日期／時間仍為 LocalDate／Instant。 |
| spec 對應 | 實讀 Requirement 205 AC12、206 AC1、design 日期修正及 t582／t583，與 actualD、TW minimum、原 signalInstant 和五年分數口徑一致。未重做 spec 全案審查。 |

已直接重算 raw／calendar SHA，均與指定值一致；原始 495 列及本機日曆確實包含所列四個「TW 休市、US 開市且有原生 USD spot」日期，未由股票日曆推導銀行日曆。

最新測試證據：

- backend 指定五組 Surefire XML：**80 項，失敗／錯誤／略過皆 0**。包含新增銀行日期測試及 historical windowEnd 斷言；編譯產物與報告時間晚於目前測試來源。
- external 指定五組：29 項，零失敗／錯誤／略過。
- BFF controller／service 兩組：10 項，零失敗／錯誤／略過。

本輪僅讀既有測試結果，未執行會產生檔案的測試命令。

## 無法查證

正式 migration、495 列 production 匯入、registry、部署、120 表 schema 快照及部署後 API／radar readback 尚未驗收。隔離 apply／reapply 收據均為 PASS，第二次零新增、零修價，但不代表正式 DB 驗收，也不代表週六 current LIVE 已就緒。

## 自我挑戰

- ARCH1 原為零 finding；本輪另行核對根 agent 日期問題，未將前輪 PASS 當作日期語義正確的證據。
- 已將四檔變更與 ARCH1 快照比對，未把既有 Controller／SSE 技術債或其他未提交變更納入 findings。
- 保留的相容 historical overload 仍可建立完成日 17:00，但兩個正式回測 resolver 已改走保留原 decisionInstant 的入口，因此不構成本輪遺漏。
- 未把來源 URL 驗證字串誤判為外呼，也未將 DTO 容器、註解中的 `@Data` 或舊局部浮點百分位運算列為違規。
- 本輪未新增 schema 變更；依明示發布順序，未要求提前產生尚未部署的正式 schema 快照。