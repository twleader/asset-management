# [t397] 個股即時推播——雷達範圍的可信成交經既有 writer 更新完整即時價快取

**對應 Requirements:** Requirement 132（訂閱交易雷達台股，將可信實際成交更新到既有 Redis quote；不新增 SQL schema、不改完整五檔 canonical revision）
**前置任務:** t396 external config-state；既有 StockSourceQuery、PriceCacheWriter、TAIEX index stream
**Liquibase changeset:** 無

## 背景

既有 `price:{market}:{code}` 是 `PriceCacheWriter.buildPayload()` 產生的**完整 quote JSON**，包含價格、來源、日期、updatedAt、quoteStatus、closed 與 optional OHLC／昨收等 metadata，並非可單獨改 price/time 的純量。全部最新 quote 寫入共用 `redis/price-cache-monotonic-write.lua` 的原子 freshness/status fence；不得另作 Redis read-modify-write 拼出假來源。

[官方 aggregates 文件](https://www.fbs.com.tw/TradeAPI/docs/market-data/websocket-api/market-data-channels/aggregates.txt) 已核實資料形狀：`lastPrice/lastSize` 包含試撮，`lastTrade.price/size/time` 才是實際成交資訊，`lastTrial` 是試撮；quote 的 lastUpdated 也不是成交發生時間。本任務選 **Normal mode 的 aggregates**，不是未核實的通用 channel。官方例子 `lastTrade.time=1685338200000000` 對應 2023-05-29 台北 13:30，採 epoch microseconds（由官方例子與同專案 indices 微秒契約交叉核實），不以位數猜秒／毫秒，不用接收時間冒充成交時間。

[官方多連線說明](https://www.fbs.com.tw/TradeAPI/docs/market-data/making-connection.txt) 支援每次 init_realtime 後保存獨立 stock WebSocket reference。本任務為個股保存專屬 client/session（額外一條，最多 300 symbols），不借 `TaiexIndexStream` 擁有的物件。個股在 indices flag 關閉時仍可啟動，指數 reconnect 不得摧毀個股訂閱。

## 要做什麼

- [x] 397.1 **feature/config/calendar gate 與雷達範圍。** 新增 `FUBON_STOCK_PUSH_ENABLED=false`，轉接 adapter 與 external-materials-service，維持其他旗標值。關閉／config 非 READY 時不連線、不打 SDK。external 的 `FubonStockPushSubscriptionManager` 每 30 秒用既有 `collectTwRadarCodes` 取得各 owner 最新台股持股∪台股 alerts，去 0000；不用全市場 stock master。不改 SQL、股票主檔、指數或既有 REST quote dispatcher。
  盤中授權沿用 `MarketClock.isTwMarketOpenKnown()` 明確 true（台北 09:00 ≤ time < 13:30 且既有 calendar known true）；盤外／calendar unknown／停用即取消全部訂閱並停止消費。13:30 後收盤價仍由既有官方 close 路徑處理，本 websocket 不產生 VERIFIED_CLOSE。代碼上限 300，超出明確 SUBSCRIPTION_LIMIT，不偷偷截斷、擴到非雷達或建立無界連線。

- [x] 397.2 **Python 自有生命週期，兩個 token-protected 入口。** `POST /internal/market-data/stock-push/subscriptions` body `{"symbols":[...]}` 為全量 desired set，校驗合法台股、唯一、排除 0000、<=300；更新時只对差集 subscribe/unsubscribe aggregates/intradayOddLot=false，不每 30 秒 reconnect。`GET /internal/market-data/stock-push/stream` 用既有 SSE/token 三態模式。每 30 秒 renewal 可重送同一 set 以續 120 秒 subscription lease，但相同 set 不重送 SDK 訂閱；lease 到期／consumer 關閉／服務 shutdown 撤銷訂閱并釋放個股 client。
  在 gateway 的 session lock 內建立並保存專屬 Normal-mode stock reference，不能覆寫既有 index-owned reference；auth/session generation 改變時失效並清空 confirmed subscriptions，新 session 連線成功後完整重訂目前 desired set，不能只等股票集合變動。reconnect 使用既有有上限退避、固定上限 queue、取消停止機制，不 busy loop。取消股票不取消 indices，indices 重連不影響股票；兩者同時失去登入 session 時各自重建正確訂閱。不得以多連線功能為由把其他 REST/SDK client 切成不同未驗證設定。

- [x] 397.3 **adapter 正規化必須證明實際成交。** 只接受 `event=data,channel=aggregates,data=object`，symbol 屬目前已確認訂閱，exchange 是 TWSE/TPEx、type 是該版本官方支援台股類型（已核實 EQUITY；未知 type 拒收），source date 嚴格 ISO。heartbeat、snapshot ack、error、其他 channel 均不是成交。`isTrial=true` 必拒絕；官方 boolean 只在事件出現時揭示，缺 isTrial 不能當 schema failure，但其他必要成交證據仍要存在，非 boolean 異常須拒絕。
  `lastTrade.price > 0`、size 為正整數、time 為 exact positive microseconds；解析成 Instant 後台北日必須等於 data.date 與接收当日，不能前日、未來日或未來 timestamp（不加寬鬆 future 容忍），且屬已授權當日盤中。可收到 13:30 前最後成交，但不得把接收時間/lastUpdated/lastTrial.time 換成 freshness。closePrice/closeTime 如有必與 lastTrade price/time 一致，否則丟棄矛盾 packet；有 lastTrial 但有效 lastTrade 的 packet 也不可選 trial 值。
  輸出 immutable SSE event 含 symbol/market/exchange/sourceDate/source=`FUBON_WS_AGGREGATES`、tradeTimeMicros、price，以及**同一 packet 已驗證**的 optional previousClose/OHLC/name；同包缺資料就 null，不從舊 Redis、REST、別來源補。OHLC若存在須有限正值、high>=low、且不矛盾於成交／開盤；有異常則拒 packet，不偽造修正。buyPrice/sellPrice 留 null；volume 只有經核實單位與既有 quote 一致才映射，否則 null，不把成交張數當股數。SSE 不含原始 SDK 全物件、帳號、完整五檔或 book revision。

- [x] 397.4 **consumer 防禦複驗，完整 quote 經既有 writer/Lua 更新。** `FubonStockPushConsumer` 只在 gate 通過時維持有界 SSE consumer；每個 event 重新檢查目前 radar membership、source、實際成交／日期／時間／正值與 optional metadata，避免取消訂閱後的隊列殘留寫入。建立 PriceResult：price=實際成交、tradingDate=來源日、freshnessInstant=來源 trade time、source=FUBON_WS_AGGREGATES、closed=false、quoteStatus=LIVE；priceChange/changePercent 如需呈現，只可由該同包 previousClose 與 price 計算，缺昨收則 null，不沿用舊值。
  由 `PriceCacheWriter` 的窄 websocket entry 驗證後委派既有 `writeTaiwanLive(result,true)`，或在相同 class 直接呼叫既有 strict-newer `executeLatestWrite`；兩者都必須共用原有 `price-cache-monotonic-write.lua`，不複製比較邏輯、不另設可旁路 writer。此路徑使用完整 packet 的 OHLC，不合併 localHighLowTracker 形成來源不實的 quote；沿用 writer 的 JSON shape、TTL、index、publish 行為，成功後才允許既有可信成交 tick side effect。不得把 websocket來源寫成 FUBON_INTRADAY 或 VERIFIED_CLOSE。
  原子 fence 的 source date、time、status/closed 必須與整份 JSON 一致：較舊或相同時間事件不改 price 或 metadata，同日 verified close 不被降級；失敗不發布新價格。不可直接 HSET/JSON.SET 部分欄位、不可讀舊 quote 後只換 price/time/source。

- [x] 397.5 **既有 book/SQL canonical 完全不動。** 不寫 quoteDetail/bidLevels/askLevels，不呼叫或修改 Requirement 114 的 PostgreSQL comparator，不寫 `fubon_tw_live_quote_response`／其 canonical revision，也不改 `fubon_taiex_index_latest`。本任務的 latest quote 與 book canonical 是不同既有存取面；不得以完整 aggregates 含五檔為理由順便加入第三 book source。所有 SDK 方法僅登入/行情/訂閱/退訂，不含委託或交易呼叫。

- [x] 397.6 **盤點與可觀測性。** JOBS 用 EXTERNAL 既有行情分類，時間「交易日盤中即時；訂閱清單每 30 秒更新」，不虛構 cron。enum/counter 區分 disabled/config/calendar、SUBSCRIPTION_LIMIT、RECONNECTING、INVALID_EVENT、REJECTED_STALE、WRITTEN、FAILED，錯誤僅 sanitized reason 不 dump 原始包。API inventory 的 stock-push httpEndpoint 為 `GET /internal/market-data/stock-push/stream`，consumer 補記 subscriptions 控制入口與 external consumer。connected 以真正可執行且已測過來源解析→writer 路徑為準，不以空 SSE heartbeats 代表完成。不新增 BFF/frontend/9090 route。

- [x] 397.7 **測試要覆蓋完整 metadata 與重連。** Python 官方格式去識別 fixtures 測試試撮、無 lastTrade、zero/negative/NaN、秒／毫秒錯單位、前日／未來、wrong symbol/channel、同 packet 矛盾、缺 optional Boolean 合法。測 indices 停用時個股能啟動、雙方個別 reconnect／unsubscribe 不干擾、session generation 重建完整 desired set、lease 到期、queue/backoff 上限。Java/真 Redis 整合測所有新值＋source/date/status/closed 一起原子更新、等時舊時拒絕、verified close 不降級、未授權／移除 radar 無寫入、不混舊昨收OHLC、不碰 SQL canonical。不能只斷言 price 有更新或只測 fake time > previous。

## 驗證

```bash
bash scripts/spec-check.sh

docker buildx build --platform linux/amd64 --target test -f fubon-broker-service/Dockerfile fubon-broker-service --load -t asset-fubon-broker-service:test
docker run --rm --platform linux/amd64 asset-fubon-broker-service:test pytest -q

/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

實作後依 run-stack rebuild/recreate adapter、external-materials-service、bff，保留既有旗標設定。用隔離 fake SSE→真 Redis 讀回整個 quote，而非只核對 price 或 health。SDK 實機權限／行情樣本未在本規格階段驗證，不宣稱已完成；有官方契約即可先完成 offline 正常與拒絕路徑，不能把 flag=false 當成功驗收。

## 完成報告

**程式、隔離測試、獨立架構與 feature Docker 驗收已完成；第二個 session 整體仍待394／395來源契約。** Python676項、external700項全數通過，包含獨立Normal client、ACK／generation、delta subscribe／unsubscribe、300檔上限、120秒租期、bounded queue、重連與最後consumer離開；37項stream／原子session／市場並行案例另連續重跑三次皆通過。fake SDK／SSE至真Redis確認整份quote的價格、同包OHLC／昨收、source/date/time/status/closed一起更新，等時／舊時／verified close保護有效；沒有SQL/book canonical寫入，不影響indices串流。SDK底層HTTP無原生timeout，外層deadline與最多四個blocking workers限制資源，但不能強制終止SDK內部執行緒；不宣稱真人連線或權限已驗。

共用實機驗收見 [Task386接手更新](t386_fubon_api_documentation_view.md)：四個服務從此feature rebuild/recreate且healthy、class/source雜湊與測試產物相符，內部disabled GET及登入後盤點／排程頁已驗。全域FUBON_ENABLED保持false，新flags保持false，正向資料回寫只在隔離PostgreSQL／Redis測試，未啟用真人券商查詢。這不構成第二個session已完成或已push。
