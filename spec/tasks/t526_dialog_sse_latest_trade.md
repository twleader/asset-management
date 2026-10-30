# [t526] 分析視窗直接呈現 SSE 最新成交

**對應 Requirements:** Requirement 197（所有即時行情頁共用 SSE 並直接更新）
**前置任務:** t506、t524、t525
**Liquibase changeset:** 無

## 背景

使用者明確指出成交價應富邦即時寫 Redis 後，透過 SSE 更新需要的頁面。共用 dialog 的 onQuote 目前只要求補抓五檔，quoteSummary 成交仍取 Yahoo canonical book price，會比 SSE 晚。13:04–13:05 的15秒唯讀取樣收到9筆2330 FUBON_WS_AGGREGATES price-update，price都2375，updatedAt持續前進；成交量大不保證每次價不同，不能造假跳價。

## 要做什麼

- [x] 526.1 StockAnalysisDialog 加獨立最新成交 state 與固定 UI 區塊，在 onQuote 直接採用同一 shared livePriceStream 的 market+code matched tuple，使用既有 mergeLiveQuote 處理當日、亂序、完整比較欄/null、CLOSE_PENDING／PREVIOUS_CLOSE／VERIFIED_CLOSE fence。直接顯示 provider price／priceChange／changePercent、source／updatedAt，不用快照昨收推算漲跌，不宣稱 aggregates是完整逐筆成交。
- [x] 526.2 初次訂閱/catchup 可重播 livePriceStream.quotes() 現有cache，仍驗open／visible／身分／日期／status；無合法當日即時資料明示等待、close狀態依真值顯示。換股/close/pause清state，恢復再播合法cache。不新增SSE/API/provider/HTTP入口或修改shared stream。
- [x] 526.3 最新成交區與五檔快照分開持有state與來源時間，book不可用時最新成交仍可更新；五檔summary原成交改標「快照成交」，header清楚標為五檔快照。quoteDetail、levels與內外盤完整snapshot不變，不混source欄位、不被新即時區寫入。慢book response不可覆蓋新SSE，保留T524穩定DOM與T525兩秒timer、非重疊/abort/generation/token，不碰其他頁策略與歷史。來源無offset時按台北解析；新source時間與不變價都如實顯示，不做假動畫。
- [x] 526.4 授權限縮展示例外：僅獨立最新成交區可讀既有SSE及cache；canonical五檔/公開API仍完整單一snapshot，不可接上估值、警示、策略或交易。不改Redis writer、provider、資料庫、BFF/API或Swagger。
- [x] 526.5 用現有livePrices.test.js真SFC handler測試onQuote與cache/catchup接線、同價時間推進、變價、亂序/錯股/昨日/null/close fence、book晚到不蓋新成交、pause換股與原booktimer。真Vue browser驗證兩區來源/時間、SSE即時顯示與相同DOM；唯讀真business SSE／Redis事件透過真shared transport送入真SFC，不注入Redis或人工事件到正式服務；同價不得聲稱變價。核對Dashboard/TradingRadar/WatchStock/SnapshotForm既有直接SSE更新；正式登入session無證據保留UNVERIFIED_NO_SESSION。

## 驗證

```bash
bash scripts/spec-check.sh
cd frontend
npm test
npm run build
cd ..
docker compose -p asset-management build frontend
docker compose -p asset-management up -d --no-deps --force-recreate frontend
curl -fsSI http://localhost/
```

以完整實際SFC+真Vue lifecycle/template及真createLivePriceStream接收事件，驗證不發book GET也能直接改最新成交，book晚到維持自身snapshot且不覆寫最新成交；相同value事件更新來源時間，DOM不重建。唯讀取樣真富邦→Redis→business SSE，價格確實變時才記變價證據；無登入session不假稱正式BFF瀏覽器鏈完整通過。

## 完成報告

- 共用 StockAnalysisDialog 的 `latestTrade` state 經 `applyLatestTrade`／`replayLatestTrade` 直接消費同一 livePriceStream onQuote／cache，使用 mergeLiveQuote 驗身份／日期／奈秒順序／close fence／explicit-null。原 quoteDetail 仍整份 canonical snapshot，book晚到不影響最新成交，T525 interval不變。
- 固定最新成交區含來源／毫秒時間與 provider 漲跌，原 snapshot 成交改標「快照成交」。無offset時間按台北解析，紐約瀏覽器測試亦正確；同價事件只推進來源時間，不造價差。當日僅LIVE／VERIFIED_CLOSE可顯示價／漲跌／幅度；root驗收發現pending帶非null值會露出未確認價後，依低模型驗收失敗接手規則收斂該guard並增加回歸，CLOSE_PENDING三數值均為「—」、狀態與時間保留真值。獨立架構複核無finding。
- 產品碼僅 StockAnalysisDialog.vue，測試僅 livePrices.test.js；`npm test` 167/167、build、diff check 通過（最終logs `/tmp/quote526-tests-final.log`、`/tmp/quote526-build-final.log`）。Dashboard／TradingRadar／WatchStock／SnapshotForm既有direct apply handler與七來源view共用dialog均已核對；無其他頁、BFF、API、writer或金融契約變更。
- 真Chrome harness `/tmp/quote526-browser.cjs` 執行完整真SFC／Vue lifecycle／template與真createLivePriceStream/mergeLiveQuote module：舊程式收到合法SSE後無最新成交DOM；新程式book pending時SSE先改成交、同價sourceTime前進、變價同DOM、晚book隔離、亂序/錯股/昨日/null、hidden/cache/換股、close fence與pending非null保護均通過。log `/tmp/quote526-browser-after.log`，來源時間在America/New_York瀏覽器仍以台北顯示；UI亦已人工看圖確認來源分區。
- 真實鏈證據：`/tmp/quote526-live-events.json` 是既有Redis price-update唯讀取樣（2330九筆同價2375且時間前進）；`/tmp/quote526-business-sse.json` 為business既有SSE唯讀取樣。browser `--live` 直接將真business SSE的原樣price-update轉交真shared transport與真SFC，收到2330 price2370、source FUBON_WS_AGGREGATES，時間13:12:02.827225→13:12:03.078221，畫面顯示2,370.00與毫秒時間，慢book fixture自身仍2300未污染最新成交；log `/tmp/quote526-browser-live.log`。此案例兩筆同價，不宣稱觀測到當場變價；變價行為另由受控事件回歸驗證。未向正式Redis注入事件。
- Feature frontend已Docker rebuild/recreate，實供StockAnalysisDialog-D0hz5YM7.js SHA-256 `c3a6e3f92df388220d2ab83f0b0b0e53ce536e25d05d2fbddc345ad82663efc5` 與最終local dist一致，index/chunk HTTP200且bytes相同、env/TLS同main且RO、其他容器不變。receipt `/tmp/quote526-feature-runtime.json`。
- 正式已登入前端→BFF session無證據，保持 `UNVERIFIED_NO_SESSION`；實際Redis→business SSE與browser transport橋接驗證不冒充已登入UI整鏈。驗收後進行feature commit／no-ff merge／push，再由clean main重建前端並回讀，最終SHA與runtime結果由聊天交付。
