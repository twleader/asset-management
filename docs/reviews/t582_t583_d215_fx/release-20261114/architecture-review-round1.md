VERDICT: PASS

## 判定

critical: 0   major: 0   minor: 0

審查範圍：`main/HEAD=caafb547db1666d8f7efacdb2d267a42f4aa4ed2` 至工作目錄，包含本輪 tracked diff 與新增檔。已閱讀完整 `CLAUDE.md`、`spec/steering/structure.md`，查核 Task582／583 production source、migration、回補工具及相關測試證據。審查期間新增的 generated identity 產物不列入本次身分驗收。全程未修改檔案或執行寫入操作。

## Findings

無可成立的新增架構或功能邊界 finding。

## 已查無發現

以下以 `git diff --unified=0 main` 確認新增範圍，搭配 `grep -ran`、`rg -n` 查證。

| 維度 | 查證結果 |
|---|---|
| 模組界線 | diff 新增行及新 Java 檔未命中外部 HTTP client、反向 business-services 呼叫或券商交易操作。新增資料存取限 SDD 明確設計的匯率來源表與歷史表，符合 `structure.md` §8。來源 URL 字串用於驗證，未誤判為外呼。 |
| 分層職責 | Controller 無變更；Service 未新增 `ResponseEntity`／`HttpServletResponse`；Repository 僅追加 `EntityGraph`。DTO 為巢狀 record，未持有 Entity；金額維持 `BigDecimal`，日期／時間使用 `LocalDate`／`Instant`。 |
| BFF | 無新增前端頁面、跨頁 BFF 或裸 fetch。既有公開匯率服務仍從內部 API 聚合，未新增 DB 直讀、vendor refresh 或寫入。新頁面與多 panel 並行檢查不適用本輪。 |
| 正規化與 migration | history 僅新增 capture FK 與具名核准的 member receipt；metadata entity 未映射 `raw_body`。`v1.165.0` 只有一份 changeset，master 已註冊；migration 包含來源欄位成對約束與 immutable trigger。 |
| 五元來源身分 | `StockSourceQuery.java:418–420` 的 conflict／lookup 均綁定 provider、basis、rawSHA、sourceURL、capturedAt。BFF 去重保留 URL 與 fetchedAt；同 body 的不同合法查詢 context 不會僅因 hash 相同而合併。 |
| 原文與價基 | 獨立讀取原始 bytes 重算 SHA，確認為 `2b271509afc67b01784af5880a4ec474fb8e00d4584303dcc59c48840a19dce7`；495 列、495 唯一日期、全為 USD、495 筆有效 native spot，日期為 2024-11-08～2026-11-13。USD parser 不以 cash 補值。 |
| 成員收據 | `ExchangeRateHistory.java:53–55` 同時核對 authority date、正向買賣價差及重算 memberSHA；writer 先驗 native member，並回讀 stored raw。既有無證據 writer 不能覆蓋已有 capture 的列。 |
| 時鐘與回測 | `UsdTwdLiveRateService.java:50` 的 radar 入口使用 `readLiveRate(false)`，不走歷史 DB fallback。LIVE factory 檢查來源、日期、freshness 與未來時間；historical 路徑以 `knownAt` 拒絕事後回填證據。 |
| Coverage／legacy | `UsdFxPurchaseContext.java:162、181` 要求 FinMind query interval 聯集覆蓋窗口；銀行 OBSERVATION 僅供價格。可信 boundary、最新 D、成員收據及 excludedLegacyRows 分別檢查，UNKNOWN 價格不參與極值。 |
| FX／own-DD 邊界 | `UsdFxPurchaseContext.java:82、90–98` 保留 `>32.8`、區間位置 `>=90%`、STOCK `>=30%`、ETF 嚴格 `>10%`。`validPriceFact()` 綁定標的、市場、P、窗口與價基；具名免止穩集合仍由原 `threshold()` 限定。 |
| SRPP 窄例外 | `UsTacticalCalculator.java:78` 先要求 quote、candidate binding 與 canonical radar PASS；新 helper 再重放 H／P／日期證據。GOOG／GOOGL 的公司成本、批次及 pending 共用 company 判斷，價格證據仍各自綁定。 |

測試證據已讀取指定 final logs 與 Surefire reports。原 `external-final.log` 確有一項跨日 fixture 失敗；審查期間作者修正 fixture，較新的 `external-final2.log` 無測試失敗訊息，`ExchangeRateFetchClientTest` XML 為 6 項、零失敗。已讀取的 backend 關鍵測試及 BFF 11 項報告亦為零失敗；本審查未重跑測試。

## 無法查證

- 正式 v1.165 migration、實際回補、部署後 API／radar readback 尚未驗證。未將隔離資料庫 receipt 當成正式 DB 驗收。
- 正式 536 列與其中 41 列 UNKNOWN 的實際保留結果，本輪未直接查 DB；已確認程式具備保留與排除規則。
- 目前 `db/schema.sql` 為 migration 前的實際 production 快照；依本輪明示發布順序，不要求提前偽造未部署結構。實際 migration 後仍須重產並驗 drift。
- 五元身分整合重跑、最終 generated authority／registry 與合法時窗 API 採用須另外查證；本次 PASS 不代表上述驗收完成。

## 自我挑戰

- 已以 diff 排除既有 Controller／SSE 技術債，未擴成全樹清算。
- DTO 外層 class 內有 record；未把外層容器、註解中的 `@Data` 或局部浮點運算誤報為違規。
- memberSHA 是本輪明確核准的密碼學收據，未當成禁止持久化的金融衍生值。
- 撤下「schema 缺新表即漂移」及「外部測試仍失敗」兩項疑慮：前者受本輪明示部署順序約束；後者已有更新 fixture 與零失敗報告。未保留缺乏新增違規證據的推測。

