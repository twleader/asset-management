# [t533] 所有均線警示條件補上對應價格

**對應 Requirements:** Requirement 16（到價警示條件的均線價格顯示）及 Requirement 14（觀察清單共用條件文字）
**前置任務:** 無
**Liquibase changeset:** 無

## 背景

目前警示條件的非 0% 均線條件會顯示換算價格，但沒有百分比的「低於季線」「低於月線」沒有價格。原因有兩層：共用 label helper 的 threshold=0 分支沒有價格後綴，警示清單的單條／群組 mapping 也跳過這類條件的指標查詢。本次使所有均線條件一致呈現，取代原先刻意省略 0% 價格的契約。

## 要做什麼

- [x] 533.1 `StockAlertService.buildLabel` 的 `MA_ABOVE_PCT`／`MA_BELOW_PCT` 在 threshold=0 時也附目前均線價格，保留「高於／低於月線／季線／年線」文字、不加入 `0%`。0% 直接使用對應 MA20／MA60／MA240；非 0% 維持均線 × (1 ± pct/100) 與既有格式。價格四捨五入至小數 2 位、去除尾零，以全形括號呈現。
- [x] 533.2 單條 `maIndicatorsForLabel` 及群組 `groupIndicatorsForLabel` 對任一 MA 條件（包括 threshold=0）取得 `TechnicalIndicatorService.computeAll()`；群組合併 `conditionLabel` 與每個 `conditions[].label` 共用一次結果。不對全為非 MA 的條件／群組新增指標查詢。指標失敗或對應均線 null／0 時退回純文字，清單仍可載入。
- [x] 533.3 `WatchStockService` 保持以已算好的 `FullIndicators` 呼叫同一 `buildLabel`／`buildGroupLabel`，不加額外查詢。格式化僅在 business service 共用 helper，BFF 與前端沿用現有字串顯示；非 MA label、實際觸發判斷、通知流程、API 路由、DTO 與 DB schema 均不改動。
- [x] 533.4 更新既有 `StockAlertGroupLabelTest` 中「0% 不附價格」的舊斷言，驗證高於／低於 × 月／季／年線的 0% 顯示、精度／尾零、null／0 均線 fallback 及非 0% 回歸。在同一測試檔補上 response mapping 案例，經公開 `findAll()` 驗證單條與僅含 0% MA 的群組確實取得指標且回傳正確 `conditionLabel`／`conditions[].label`、群組只查一次；驗證非 MA 不查、指標例外仍回純文字。

## 驗證

從本任務 worktree 執行（僅部署變更的 business-services）。建置前先依 main 的有效環境及實際掛載產生 `/tmp/t533-deploy-override.json`，比對確認與 main 一致後才執行以下本次實際驗收指令；若暫存 override 不存在，須重新準備並比對，不可省略第二個 `-f` 參數：

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=StockAlertGroupLabelTest,StockAlertGroupMatchTest,StockAlertPurposeTest,StockAlertExtendedIndicatorTest test
docker inspect asset-frontend --format '{{index .Config.Labels "com.docker.compose.project"}}'
test -f /tmp/t533-deploy-override.json
docker compose -p asset-management -f docker-compose.yml -f /tmp/t533-deploy-override.json build business-services
docker compose -p asset-management -f docker-compose.yml -f /tmp/t533-deploy-override.json up -d --no-deps --force-recreate business-services
docker exec asset-business-services curl -fsS http://127.0.0.1:8080/api/snapshots
curl -fsS 'http://127.0.0.1:9090/api/public/market-index?market=TWSE&range=1m'
```

建置前比對本 worktree 與乾淨 main 的部署設定，只回報相同／不同，不輸出秘密值；保留正式 TLS／secrets 掛載，按照 run-stack 流程確認容器 effective config。重建後等 business-services healthy，再驗既有 BFF safe GET 恢復，不例行重啟 BFF。唯讀檢查執行中 jar 確含本次 label 與指標選取變更，並以現有單條／群組資料驗證 label 價格及觀察清單一致性；若無登入瀏覽器 session，只明列 UI 未驗證，不建立假登入或寫入測試警示、不觸發通知。驗收後以兩段式 feature commit／no-ff merge 推至 main；若此後需部署，從乾淨 main 重建。

## 完成報告

2026-11-02 完成實作與驗收：

- `StockAlertService.java` 僅修改兩個 MA 指標載入 guard、兩個 0% label 分支與對應註解；既有 helper 共用價格後綴，未改觸發判斷。`StockAlertGroupLabelTest.java` 更新舊契約並加入六種週期／方向及公開 `findAll()` mapping 回歸。
- 先以舊碼重現價格缺漏紅燈，再修正為指定四類共 42 個測試通過；`git diff --check` 通過。規格及架構兩次獨立唯讀審查均 critical／major／minor = 0（實作前規格審查與實作後架構查證）。
- run-stack 使用 inherited 主代理模型備援。從本 feature 重建並 recreate 僅 business-services；Docker healthy、BFF actuator `UP`、gateway safe GET 首次即恢復，不重啟 BFF。46 個有效環境鍵及 3 個掛載均與 main 一致；main → feature 複製缺少的 `.env`，以 `/tmp` 專用 Compose override 保留 main 的 shared-secret 實際掛載，不改受控設定或 main。host 8080／8082 無 listener。
- 執行中唯讀 API 驗證 36 個 0% MA 條件（34 群組成員、2 獨立）全帶正確價格，涵蓋月／季／年線，與 24 筆觀察清單文字一致。0050 實例：「低於季線（108.13）」「低於月線（110.46）」。執行中 class 與測試編譯產物逐位元一致，驗收前後 image 固定 `sha256:ec9f944e4467962c056bc3ff429028a6e4dedfe07d0a067e66aefed5a3aefc7f`。
- 去識別驗收證據暫存 `/tmp/asset-t533-runtime-evidence.json`。登入後 UI 為 `UNVERIFIED_NO_SESSION`；未建立測試警示或手動觸發通知。
- 驗收指令校正：business 未提供 `/actuator/health`，改用現有 Compose probe `/api/snapshots`（200 JSON array）；BFF 的 actuator 已確認 `UP`。原錯誤 endpoint 的回應為 500／No static resource，未將它宣稱為健康。
