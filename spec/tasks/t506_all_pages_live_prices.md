# [t506] 全頁共享即時行情 SSE

**對應 Requirements:** Requirement 197（全頁即時行情一致更新）
**前置任務:** t505（富邦推播先DB後Redis已落main）
**Liquibase changeset:** 無

## 背景

main2031dc34只有Dashboard與TradingRadar個股接SSE。WatchStock只載入，SnapshotForm兩分鐘poll，分析視窗分時與book靜態，大盤卡片未更新。這次補完整顯示鏈，不改策略或價格producer。

## 要做什麼

- [x] API client集中建立共享訂閱；同一 SPA 共用一條已登入的 `/api/market-data/prices/stream` SSE；Dashboard、TradingRadar、WatchStock、SnapshotForm 與所有來源頁開啟的 StockAnalysisDialog 訂閱同一服務。最後使用者離開即清除連線與timer；隱藏分頁暫停讀取與回復可見補讀。EventSource 自動重連時補抓 pure-read BFF 最新行情；永久 CLOSED 不無限重建，401不重試風暴。
- [x] 事件及HTTP回應需有market/code/date/status與更新時間檢查，舊事件不能倒退較新行情、昨日LIVE不能覆蓋今天；首屏HTTP晚到不得覆蓋已收SSE。CLOSE_PENDING/VERIFIED_CLOSE沿既有官方收盤保護；unknown/null不轉零。LIVE完整報價tuple允許明確存在的changePercent與priceChange為null（來源缺比較基準），仍更新正數price並原子清空比較欄，不保留舊漲跌、不推算或轉零；缺少比較欄或NaN拒絕。此為Requirement197對既有Requirement148 radar報價比較欄有限更新規則的具名修訂，策略凍結邊界不變。
- [x] 總覽沿既有snapshot-date gating更新報價並bounded合併刷新BFF即時估值；雷達個股及0000大盤只改具名price/changePercent/source/status/time/tradingDate，不重算或刷新策略、指標、score、regime、理由、歷史generatedAt。重連純行情補讀不得呼叫可能persist雷達snapshot的完整雷達讀取。
- [x] 觀察清單即時更新price/priceChange/changePercent及有權威事件值的OHLC/volume/source/status/time/date；警示條件、觸發snapshot及技術指標維持原資料。快照表單只對市場當日current/new模式更新具名行情，沿withoutDirtyChange保護手工欄位與dirty狀態；歷史日期不受live覆蓋。事件補讀不得呼叫會backfill/refresh/vendor I/O的prices或歷史接口；使用既有pure-read realtime。
- [x] 共用分析視窗只在開啟且符合標的時響應。當日分時以事件後至少300ms、最多每秒一次、不重疊且保留最後pending的canonical session純GET完整替換，避免PUBLISH早於ticks落地與自行造volume/comparison tuple。可在最後事件後再作一次bounded尾端補讀；generation擋下關窗/換股舊response。五檔僅在五檔tab開啟時用同樣coalesced、最少2秒間距純GET既有canonical book；price事件不冒充book資料、不拼不同來源。歷史K線、技術指標與凍結交易列不得因price事件重算或覆寫。
- [x] 允許在既有登入 intraday-ticks 鏈新增選填 readOnly=true（預設false，既有行為不變），一路透傳至 IntradaySessionQueryService，true時完全略過reference self-heal，仍讀既有reference cache並由同一business resolver產生完整session；事件刷新必帶true。允許WatchStock/TradingRadar各頁BFF新增登入pure-read realtime代理同一business價格cache API；不讀其他頁BFF、不觸發計算。只做這些具名內部契約，不新增行情provider/broker I/O、Redis/DB寫入或公開API；保持BFF/auth/nginx SSE無buffer及timeout。離線測試覆蓋共享、cleanup、重連、日期及亂序、HTTP/SSE競態、各頁實際handler、表單不dirty與歷史保護、dialog尾端補讀/換股/非重疊；Docker重建recreatefrontend及任何實改服務、唯讀smoke。非交易日不注入正式DB/Redis，盤中live驗收如實記未執行。

## 驗證

```bash
bash scripts/spec-check.sh
cd frontend && npm test && npm run build
```

若修改BFF，執行對應Maven tests。離線fixture必執行實際頁handler或編譯script，而非只regex比對。run-stack由subagent執行：未merge時feature設定先與main一致比對且不印secret，TLS/secret掛載指向正式既有來源；docker compose -p asset-management build frontend（及有修改服務），up -d --no-deps --force-recreate同服務。唯讀確認HTTP200、BFF health及未登入stream401，不能注入live Redis/DB。arch-auditor只審完整本次diff。驗收通過由subagent執行commit-merge-push，之後由乾淨main重建收斂並記remote與image identity。

## 完成報告

完成於2026-10-24（台北非交易日）。

- shared livePrices 核心集中API adapter建立單一EventSource，具備有限1000筆tuple cache、source reference fence、onopen/可見性補讀與consumer清理；quote comparator以BigInt保留9位時間精度，HTTP/SSE與初始panel晚回不倒退，官方收盤/跨日受控。
- Dashboard、TradingRadar個股與TW0000大盤、WatchStock、SnapshotForm及七個來源頁共用分析視窗已接共享通知。估值由既有BFF bounded補讀；雷達score/action/regime/技術指標維持原值，IXIC完成日大盤無live producer不挪用其他symbol；歷史快照/交易不受覆寫。
- 合法LIVE缺比較基準時仍接受正價，明確null comparison原子清空；缺欄、undefined、NaN拒絕，不造零。快照只市場當日行情，withoutDirtyChange保護手工欄及dirty；背景輪詢改pure-read realtime。
- Dialog分時事件延遲300ms並一秒節流、最後事件尾端再讀；五檔最短兩秒獨立canonical GET；AbortSignal/generation阻擋關窗、換股及隱藏舊response。intraday readOnly=true全鏈透傳且reference cache miss零MIS heal/寫入；原初始開窗false語意保留。Watch/Radar各頁exact GET realtime同源business cache，無provider I/O或金融契約變更。
- 新舊獨立spec審查皆critical0/major0/minor0，最終功能spec gate6b4b1489ea2c；完整diff及explicit-null增補arch各0findings，實作gate712a3942f009。spec-check BLOCK0/CHECK0，117表與live schema逐位元一致、9090/Swagger parity通過；金融與SRPP產物diff為零。
- 前端標準npm test131/131（含19項新fake EventSource/timers及真SFC AST→VM handler、Vue reactive dirty測試），production build通過；backend12/ext16/BFF14個相關測試通過，涵蓋readOnly零provider、全鏈query、exact route/auth與timestamp。parent另獨立跑新19/19且核對功能diff。
- run-stack先確認feature .env與main一致且既有正式TLS/token唯讀掛載，build/recreate frontend、BFF、business-services、external-materials-service。三JVM healthy/frontend HTTP200/BFF UP；兩新realtime與SSE未登入401，9090 quotes陣列及market-index JSON200。effective config與main逐鍵一致，jar class與測試target bytes一致、frontend產物含新SSE/readOnly/頁路徑，四image pins收尾重查一致。Fubon broker容器身分與啟動時間不變，未額外重啟。
- 日曆唯讀確認2026-10-24 twTrading=false/usTrading=false；實際盤中Redis更新→登入瀏覽器SSE→畫面重繪驗收為NOT_YET_EXECUTED。沒有注入正式DB/Redis假行情、開gate、交易或寄信。之後依commit-merge-push落main，再從乾淨main重建相同四服務收斂；main部署結果由release回報。

偏差：為避免既有intraday GET bounded MIS reference self-heal被事件頻率放大，新增預設false的readOnly選填內部參數；不改既有公開金融API或初始開窗語意。五檔仍是canonical snapshot重新讀取，並未新增book SSE或保證每筆price對應新book。
