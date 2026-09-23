# [t450] 管理資產表單各 Panel 平行完整載入

**對應 Requirements:** Requirement 1 修訂（管理資產表單各 Panel 平行完整載入）
**前置任務:** t448、t449
**Liquibase changeset:** 無

## 背景

目前管理資產編輯頁 /snapshots/:id/edit 先等lookups+funds，再等detail，再prices，再realtime及逐股補值。四段網路等待與大表格重繪均被全頁v-loading遮罩鎖住。首次funds還沒確定snapshotDate即查最新主檔。實測本機各API僅15–53ms，但prices→realtime有約1秒主執行緒間隔；網路延遲會逐段疊加。需讓basic/deposits/stocks/funds各自取得完整資料再顯示，保存全表PUT的完整性與canonical讀回保護，不可因效能修正改帳。

## 要做什麼

- [x] 450.1 新增SnapshotFormPanelService與immutable record envelope `{panel,snapshotId,data,warnings}`，SnapshotFormBffController新增GET /api/bff/snapshot-form/panels/{basic|deposits|stocks|funds}/{id}四個delegate。保留舊get/list/create/update/lookups/funds/prices/realtime/exchange-rate/refresh契約，controller不新增業務聚合。
- [x] 450.2 各data.snapshot為原detail root移除不相關children，驗id/date/必要child-list型別。basic只root；deposits含deposits+banks/depositTypes/transitFundTypes（active）；stocks含stocks+brokers（active）+mergedStocks+stockPrices+marketStatus；funds含funds+banks（active）+fundMasters。必要設定與detail能平行就Mono.zip；fundMasters依detail.snapshotDate查/api/funds?date=，不得未確定date先查最新。保留原始DepositResponse全部id/updateMode/processingDate欄位。
- [x] 450.3 stocks沿用SnapshotEnricher.enrichInvestmentCostOriginal/buildMergedStocks、fetchSnapshotCloseData(detail,true)與相同per-market判斷，但新Service用完整displayRows處理frozen市場（避免現有mergePerMarketPrices重建frozen row遺失metadata）；live display價格仍只取既有/api/market-data/prices，status同原來源，逐股updatedAt/status/tradingDate/source保留；當日live含null pending優先、缺live可用displayRows，歷史只用displayRows實際交易日，缺close禁用今日live且以CLOSE_PRICES_UNAVAILABLE警告保留frozen fallback。保留per-market basedate、CLOSE_PENDING與已有temporary close fallback；auth/404不得fallback。名稱/配息率先用snapshot，首次不作dividend-rate逐股補抓/backfill/refresh。既有手動查價及兩分鐘更新契約維持。既有美股缺交易日匯率補值移至stocks Panel：有transactionDate但缺transactionExchangeRate者按相異日去重、最多4路並行純讀USD on-date，回transactionRates date→正值map。各讀5秒選配/20秒總預算，暫時失敗或無有效值省略key並warning TRANSACTION_RATES_UNAVAILABLE、沿用有效快照匯率fallback，只有transactionRates對確切on-date GET的404按缺值fallback，401/403與detail/lookups/close/required effectiveUSD的404仍relay。raw/version不變；前端group後沿原onUsTransactionDateChange同步順序補值/重算，已有值不查不改。
- [x] 450.4 所有I/O走tenant-aware business client，不加schema/public endpoint/vendor/broker或cache寫入。20秒整體預算涵蓋所有相依鏈，timeout504、暫時失敗502，401/403/404原body/status透傳。display prices/status可選5秒預算，warning PRICES_UNAVAILABLE/MARKET_STATUS_UNAVAILABLE，status缺失顯示未知；每份data.effectiveUsdExchangeRate由同一helper取raw正匯率，legacy缺值/非正值則同日純讀on-date；失敗回error不完成Panel，禁止假值。snapshot原匯率保留，deposit originalAmount缺值時仍用rawRate||1反推，不能改用補值改帳。必要rows/lookup失敗不能當空[]。
- [x] 450.5 frontend api/index.js新增四methods含AbortSignal/skipErrorToast。只替換既有快照edit bootstrap，新建模式、PUT/POST/複製/排序/分析dialog不變。四Panel由Promise.allSettled同時發出、各自成功立即填入自己的完整rows/options。基金初始化只設定master map，不重算原存基金金額。stocks使用完整stockPrices+mergedStocks+transactionRates一次初始化，不再mount呼叫legacy detail/lookups/funds/prices/realtime或逐股補查。移除edit全頁mask，使用每區loading/error/retry/empty顯示；可lazy掛載未開啟tab但不改form arrays與submit輸出。
- [x] 450.6 每份data.snapshotVersion是原始完整business detail在enrich/投影前的穩定SHA-256（map key遞迴排序、數值標準化、children IDs/順序保留）；全部required panels同route id、snapshotDate、snapshotVersion、effectiveUsdExchangeRate一致成功前，el-form與各save按鈕/submit皆禁止編輯存檔，loadedFormKey不能提前設定；summary亦不能顯示未齊全的零總額。局部retry不能清掉其他成功panel；若版本/日期/有效匯率不一致，保持鎖定並提示完整reload，此為局部重試原則例外。完整reload/route切換/unmount abort所有請求與poll、gen保護success/catch/finally，停止舊dirtywatch。PUT成功後loadFormData四panel canonical讀回，任何required失敗會reject並由既有submit保留canonicalReloadRequired鎖定，全部retry成功才解除；不能再PUT舊id。
- [x] 450.7 背景prices/realtime並行single-flight，更新quote/status而非重載草稿；date變更期間禁save，late fx/fund/quote與row讀取只能對應同route/date/code與仍存在的row。unmount不得late開啟timer；保留收盘pending不可升級、使用者編輯後原有金額計算、Task448 source-owned處理日與canonical語意。不做真實資產寫入作驗證。
- [ ] 450.8 新增有deferred promises的行為測試：四請求同步起跑、慢stocks不擋deposits、隔離失敗/retry、全部ready存檔閘門、A→B晚到成功/錯誤/finally、date/fund誤更新、unmount、PUT後canonical失敗鎖定；basic最慢且rawFX/originalAmount皆null的legacy mapping、同id不同版本/child IDs混合不得解鎖。BFF以可控client/virtual time驗payload、owner relay、missing/403/404、timeout/cancellation、optional downgrade、date NAV、歷史假日實際交易日、缺close但有今日live不能倒灌、fingerprint key/number穩定性與children變更辨識、零逐股backfill；交易日匯率去重、USD100交易FX30/快照FX31成本與submit匯率parity、temporary/該選配on-date 404的fallback警示、必要匯率404與401/403仍error、零browser逐列補查。既有前端59tests/BFF suite要過；不要改package/lock（其他worktree重疊），新增node測試直接執行。獨立spec與arch審查，Docker feature驗收→no-ff main/push→main重建；以獨立瀏覽器tab驗慢網/單區阻擋/局部retry/數值parity，不修改使用者原表單或實際資產。

## 驗證

```bash
bash scripts/spec-check.sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test
npm --prefix frontend test
node --test frontend/src/utils/snapshotFormPanelLoader.test.js frontend/src/utils/snapshotFormViewLifecycle.test.js
npm --prefix frontend run build
```

Docker依run-stack先確認main .env parity（不輸出機密）與正確secrets mount，只重建修改的bff/frontend，部署後以登入browser測四panel實際JSON及各區完成時間。公開HTTPS此機量測與可控慢網模擬分別記錄，不能宣稱實際遠端internet問題已重現。編輯readonly驗證只讀取，不click save/新增/刪除/匯入；提交/canonical用fixture驗證。

## 完成報告

實作與 feature stack 驗收完成（2026-09-23）：

- SnapshotFormBffController 同頁提供 basic/deposits/stocks/funds 四支完整 Panel；Service 使用 tenant-aware business client、20秒總預算與5秒選配預算。所有 Panel 具穩定完整快照版本與有效 FX，歷史價格保留實際交易日，基金按快照日期查主檔；首次載入沒有 refresh/backfill 或 browser 逐股／逐交易日補查。
- 前端四請求平行，各區完整到齊再掛載；單區錯誤局部重試，所有版本一致才可編輯與存檔。8個未開啟分頁改 lazy，保留完整表單資料。route/date/unmount、輪詢、手動查詢與存檔後 canonical reload 都有取消及過期回應防護。新建、AUTO 欄位及既有計算語意保留。
- Java21 全 BFF 70 suites、417 tests，failure/error/skip 均為0。前端既有59＋新增20個 loader/實際SFC執行測試通過，production build成功；既有Vite大chunk提示仍存在。測試實際執行Vue setup/render，涵蓋4請求、局部失敗、混合版本/FX、route/date晚到、row/loading owner、缺交易FX、canonical失敗/新ID及新建POST（全部mock，無真實寫入）。
- 獨立架構審查 critical/major/minor 0；另外辨識出的無效交易日FX沿用舊值、取消row查詢殘留loading兩項回歸已修正，再由獨立審查者確認並執行3項focused tests，未解決finding 0。
- feature Docker僅重建並recreate BFF/frontend；main .env同步且有效OAuth/TLS/ingest設定一致，Fubon secrets mount維持main，business/Fubon未重建。BFF healthy、actuator UP、frontend HTTP200、9090 quotes有效JSON。
- 公開HTTPS以既有帳戶重新登入的獨立tab驗收：最新快照四Panel在2.3ms內同時啟動，各60–88ms全部HTTP200；沒有legacy bootstrap calls。四區同snapshotVersion/FX、warnings空；初始DOM元素5224→2759，table8→3，未開啟tab不掛載。
- 同條件400ms延遲、125000bytes/s（1Mbps）下載/上傳，舊序列資料載入約2533ms，新四Panel426/459/533/481ms，從第一請求到全部完成約534ms。這是受控模擬與此機公開HTTPS量測，不等同重現使用者遠端internet中斷。
- 最新及2025-12-31歷史快照的deposits/stocks/funds逐筆JSON與修改前完全一致，UI總資產、各類現值、配息與FX一致。歷史四Panel29–45ms，舊流程單prices曾4403ms；歷史quote日期均2025-12-31，沒有倒灌當日live。歷史初始table8→3。
- 僅阻擋stocks Panel：其他三區ready且明細正常，所有存檔disabled、summary隱藏；解除後按該區重試，只多一個stocks GET並恢復完整表單。切換美股/在途分頁零額外API，更新方式與處理日欄位保留。所有網路阻擋/降速已解除，原使用者分頁未操作，無實際資產寫入。
- 450.8的測試與feature驗收已完成；no-ff merge/push及main重建的最終commit/image身分由本次收尾交付訊息記錄。
