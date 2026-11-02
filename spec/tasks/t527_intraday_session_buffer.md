# [t527] 修正大量當日分時資料超過接收上限

**對應 Requirements:** Requirement 13（股票分析當日分時走勢完整呈現）
**前置任務:** 無
**Liquibase changeset:** 無

## 背景

2026-11-02 0050 已有 5,260 筆 Redis ticks；external session 回應約 321 KB，business 回應卻為 ticks=[]。日誌顯示 `HistoricalDataHttpAdapter` 的 WebClient 單一 object 解碼超過預設 262144 bytes，`HistoricalDataService` 將例外降級為空 session。正確行為是既有真實成交能完整送達股票分析。

## 要做什麼

- [x] 527.1 在 `backend/src/main/java/com/steven/assets/integration/marketdata/HistoricalDataHttpAdapter.java` 建立僅 `intradaySession` 使用的獨立 WebClient，接收上限固定 16 MiB（16 * 1024 * 1024 bytes），相同 base URL、`/internal/intraday-ticks` GET、code/market/date/readOnly 參數與 typed DTO；一般 client／全域 codec 不變，不使用無限 buffer。
- [x] 527.2 保留完整 tick 數量、順序、time 與 BigDecimal price，以及 session reference、既有 resolver comparison tuple。首次開窗與 readOnly=true 均適用；不截斷、不抽樣、不補造。超限或 JSON 錯誤維持既有 fail-soft，不返回部分資料。
- [x] 527.3 在既有 `backend/src/test/java/com/steven/assets/service/HistoricalDataIntradaySessionHttpTest.java` 用本地 HTTP server 加入大於 2 MiB session 的雙模式回歸，驗首末／數量／精度／reference／comparison／date 與 readOnly 傳遞；超過 16 MiB 須 fail-soft；非 session 查詢仍守預設上限。
- [x] 527.4 不改 controller、DTO、BFF、frontend、資料 producer、Redis、DB、9090 或排程；沿用 cache-only tick 和既有 reference self-heal 限制，不新增外部抓取或寫入。只重建 recreate business-services，保留 BFF 運行並驗證復原。

## 驗證

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=HistoricalDataIntradaySessionHttpTest,IntradaySessionResolverTest,MarketDataIntradaySessionControllerTest test
bash scripts/spec-check.sh
docker compose -p asset-management build business-services
docker compose -p asset-management up -d --no-deps --force-recreate business-services
```

Compose 執行前比對 feature 與乾淨 main 部署設定且不輸出 secret，合併前在 feature 驗收；合併後從乾淨 main 建置。等待 business-services healthy，30 秒內透過 9090 safe market-index GET 驗 BFF 復原。容器內 GET `http://127.0.0.1:8080/api/market-data/intraday-ticks?code=0050&market=%E5%8F%B0%E8%82%A1&readOnly=true` 必須回當日非空完整 ticks；首次開窗模式亦查證。記錄首末、數量與回應 bytes，與 source readonly session 比對；活躍行情可能多出新 tick，以同次抓取的共同 prefix 驗完整性。檢查部署後無該查詢 buffer-limit warning，驗前後 image SHA 相同。若無可用登入 session，明確記錄 UI 未驗證，不偽稱已點擊。

## 完成報告

- 實作只更動 `HistoricalDataHttpAdapter`：專用 session client 設 16 MiB，其餘 client 保留原設定；HTTP 測試新增完整大序列、雙模式、超限、malformed 及隔離驗證。
- 修正前新增大回應兩個案例均因 262144 bytes 上限失敗；修正後四個測試類合計 19 tests、0 failures/errors/skipped（HTTP 9、resolver 6、controller 2、renderer 2）。`git diff --check` 乾淨。
- 獨立 spec 與架構審查均 critical/major/minor 0；機械檢查 BLOCK 0。origin/main 的舊時間 CHECK 已重新 fetch 確認為遠端本身未更新。
- feature 部署驗收：2026-11-02 台北 10:28:16 recreate business-services，10:28:32 healthy 且 BFF safe GET 復原，10:29:10 驗收完成；BFF 未重啟。0050 首次開窗及 readOnly=true 均 5,733 ticks、350,006 bytes，交易日 2026-11-02，09:02:01.261794→10:28:52.807942，與 source 5,733 ticks 的完整序列／BigDecimal 精度及 reference 相同，comparison tuple 一致；新增 buffer-limit warning 0。
- 46 個部署環境設定及所有 bind mounts 均與 main 一致；frontend HTTP 200、9090 quotes JSON array；驗前後 image SHA 相同：`sha256:a98d75766c2837aee4b25466794777bed0edad3f72c28a1816f5f69d04ac4354`。
- 無前端程式變更；登入 UI 為 `UNVERIFIED_NO_SESSION`。無 schema、排程、公開契約或實作範圍偏差。後續收尾依兩段式合併推送，並從乾淨最新 main 再建置 recreate 同一服務。
