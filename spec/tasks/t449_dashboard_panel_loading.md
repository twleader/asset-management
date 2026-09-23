# [t449] 儀表板 Panel 平行完整載入與失敗隔離

**對應 Requirements:** Requirement 9 修訂（儀表板 Panel 完整回應、平行載入與失敗隔離）
**前置任務:** t424（價格查詢去重）
**Liquibase changeset:** 無

## 背景

目前 DashboardView 首次 await 單一 summary（history/detail/close 等），之後 lazy 補分類；mount 另 POST 配息補算再重載 summary。所有 Panel 共用可變資料、30 秒 axios timeout，catch 常只 log，快照切換與離頁缺少完整取消防護。internet 慢速使此風險明顯；尚未證實使用者瀏覽器中斷的實際 transport 根因。

## 要做什麼

- [x] 449.1 Backend 的 GET /api/snapshots/history 新增 optional snapshotId。保留無參數既有功能，AssetService 以同一 history builder 接完整 roots 或 owner-guard 指定＋前一筆 roots（日期升冪，最多 2）。Repository 先限縮，不得全量 history 算完才 filter。選中列分類/總額/較上次與全量一致，前一列比較基底的 increase 可 null。無效/跨 owner id 使用既有錯誤，不回其他 owner latest。無 DB schema 變更。測試 selected parity、首筆、missing、跨 owner。
- [x] 449.2 同一 DashboardBffController 新增 GET /snapshots（原 summaries list）及 prefix /panels 的 kpis/{id}、allocation/{id}?tab、trend、deposits/{id}、stock-values/{id}、holdings/{id}、funds/{id}。Controller 僅委派新 Service；新 DTO record envelope 固定 {panel,snapshotId,data,warnings}，panel literal 固定 kpis|allocation|trend|deposits|stock-values|holdings|funds（allocation 四 tab 均相同），trend snapshotId=null，其他固定 URL id，warnings=[] 無警告。data 明確如下：kpis/category allocation={snapshot,history,mergedStocks,stockPrices,liveAssets}；history 是 backend bounded response；assetClass allocation={snapshot,history,classifiedHoldings}；twStock allocation={snapshot,twLookthrough}；usStock allocation={snapshot,usLookthrough}；trend={history} 完整歷史先套既有 LiveAssetsOverlay；deposits={snapshot含deposits}；stock-values/holdings={snapshot,mergedStocks,stockPrices,marketStatus,liveAssets}；funds={snapshot含funds}。非對應 child collections 不投影。allocation tab 限 category/assetClass/twStock/usStock，未知400。empty arrays 真空，不能把 upstream error 包成零。
- [x] 449.3 新 Service 沿用 SnapshotEnricher、LiveAssetsOverlay、台美 lookthrough 原算法與逐股 updatedAt；抽共用 lookthrough Service 給舊 controller 和新 Panel，不能 Service 呼叫 Controller。保持舊 summary/snapshot/realtime/holdings-classified/lookthrough/enrich/stock-order endpoints 原契約。新 panel 必要資料以 Mono.zip 平行取得，整體20秒預算含 detail/close/ETF；timeout 504、temporary upstream502、401/403/404原樣，可選 live-assets/market-status失敗保留 frozen＋warnings。所有 I/O 只經既有 tenant-aware business client，不加 DB/provider/broker 呼叫或跨 request cache，使用 BigDecimal 金額。legacy fallback 不外溢到新 panel 必要 detail/history。
- [x] 449.4 Frontend api wrapper 新增上述 method，維持30秒預算且 skipErrorToast 交 Panel 處理，AbortSignal 透傳。Dashboard 先 metadata 固定 id，再 Promise.allSettled 平行啟動七個Panel，各完成即 render，不等其他。每個 panel 自己持有完整payload，不能依賴另一 panel 的 refs 或 Pinia currentSnapshot/history。既有 ECharts/formatting/歷史凍結/per-market live/配息規則保持；不新增前端商業算法。分類 tooltip 不二次載入，allocation 換tab重取該tab整份payload。完整trend不裁切、點擊/下拉同一切換機制；保留stock拖曳排序、分析dialog、fund panel。
- [x] 449.5 每panel loading/error/retry/empty；相同id refresh失敗保留完整舊圖並標示，換id/tab清除舊圖並loading，日期不冒用。generation＋AbortController確保舊success/catch/finally不污染新選擇，retry僅該panel。mount移除enrich POST與第二次summary，價格SSE僅增量、不作首屏依賴；背景poll single-flight。unmount cancel所有request與SSE reconnect/status/order timer。測試 deferred promises 真正驗 parallel start、獨立complete、isolatedfailure/retry、A→B late success/error/finally、unmount、empty-vs-error；不要只grep implementation。
- [ ] 449.6 Java21 focused與必要回歸、frontend原測試＋新loader測試/build；spec-check zero BLOCK、獨立spec/architecture稽核。依run-stack以正確main .env驗一致（不輸出secrets），從feature rebuild/recreate backend/bff/frontend驗新endpoint與瀏覽器，再依commit-merge-push兩段no-ff merge/push，最後從main rebuild驗部署來源。不要改frontend/package.json（其他worktree有尚未提交變更）；新node測試直接node --test執行。不得券商下單或改使用者資產資料。

## 驗證

```bash
bash scripts/spec-check.sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=AssetServiceTest test
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test
node --test frontend/src/utils/dashboardPanelLoader.test.js
npm --prefix frontend test
npm --prefix frontend run build
docker compose -p asset-management build business-services bff frontend
docker compose -p asset-management up -d --no-deps --force-recreate business-services bff frontend
```
以上新測試檔為計畫產物。Docker前先等上游healthy，驗新Panel真實JSON，瀏覽器用既有登入session驗本機及可控延遲／單Panel失敗；若無登入需明確記錄未驗範圍，不得把401當圖表驗收。量測首次各Panel完成時間與payload bytes，不能以推論宣稱使用者真實internet已驗。

## 完成報告

實作與 feature stack 驗收完成（2026-09-23）：

- Dashboard BFF 提供 snapshots metadata 與七個完整 Panel 回應；KPI/配置只查所選＋前一筆 history，trend 仍為完整歷史。舊 endpoints 保留相容行為；新 Panel 的必要來源錯誤、401/403/404、整體逾時均不會偽裝成空資料。
- 前端七 Panel 平行提交完整 payload 才掛載內容；錯誤在該 Panel 顯示並可局部重試。切換 id/tab 清除舊內容，背景失敗保留完整資料；metadata、SSE、輪詢、取消與 generation 均有生命週期防護。所有八個 ECharts options 停用繪圖動畫，Sortable 拖曳動畫不變。移除 mount 配息補算 POST 與第二次 summary。
- Java 21：backend 39 tests（含實際 PostgreSQL Testcontainers 的 owner/window 查詢）；全 BFF 363 tests，0 failure/error/skip。前端既有 59 tests＋新增 9 loader/animation tests、Vite production build 均成功。Vite 既有大 chunk 提示仍存在。
- spec-check BLOCK 0；CHECK 1 是 origin/main ref 時間較舊（本次已成功 fetch 且 remote 仍同一 commit）。獨立 spec／architecture 審查均 critical/major/minor 0；DB schema dump 一致，沒有 schema 變更。
- feature Docker 重建並替換 business-services、BFF、frontend；上游 healthy、BFF UP、frontend HTTP 200。main .env 一致，Fubon shared secrets 維持 main 既有掛載，其他服務未重建。
- 使用原登入帳號經公開 HTTPS 網址驗證。初次七 Panel 全部 HTTP 200，118–204 ms；暖機後歷史切換53–118 ms。三個額外 allocation tabs 也各以單一完整回應成功。這是此機實測，不代表使用者遠端網路延遲。
- 修改前後 2025-12-31 snapshot core、mergedStocks、deposits、funds、所選 history row 逐項完全一致；bounded history 2 筆，trend 9 筆。
- 模擬750 ms延遲、65536 bytes/s下載、CPU 4倍降速及停用快取，七 Panel 同時發出且分別於837–1269 ms完成，圓餅/曲線完整；沒有summary/enrich呼叫。這是受控模擬，未重現使用者原先真實internet中斷根因。
- 僅阻擋holdings請求時，其他六Panel與圖形正常；解除後點局部重試，network只新增一個holdings請求。1800 ms延遲下由2025快照快速切2024，舊七請求全部取消，新七請求均200，舊圖在等待期間清除、最終顯示2024資料。測試後已恢復正常網路/CPU/快取與最新快照。
- 449.6 的驗證已完成；no-ff merge/push 與從 main 重建的最終 commit／image 身分由本次交付訊息記錄。
