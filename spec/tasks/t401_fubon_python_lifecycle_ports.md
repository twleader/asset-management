# [t401] 富邦 Python 唯讀介面、SDK 容量與串流生命週期

> 階段性收尾：本檔尚未實作；第一輪3項 major已依建議修正文案，仍待第二輪獨立spec審查，不得視為已通過。接續前請讀 [交接紀錄](../../docs/handoffs/fubon-refactor-2026-08-30.md)。

**對應 Requirements:** Requirement 90（隔離的唯讀 SDK 與同次帳務 capture）、91／106／115（有限報價與雷達 LIVE）、116／132（各自獨立的 indices／stock stream）、120／123／128–131（既有查詢契約）、133（收盤後來源技術指標）
**前置任務:** t399／t400 已落地；本 task 不重做其成交原子性或官方 raw 日期修復。
**Liquibase changeset:** 無

## 背景

基線 main `6aa219719c4d20d3ef405ba4b5cad75e1316df15` 的 Python service 直接 import `SdkGateway` 與它的 private helper。quote 同時允許20個 logical worker，但 gateway 的4個 native slots只等待0.1秒就失敗；真正有空間排隊的查詢會被假飽和拒絕。外層 wait_for 取消不會停止已開始的 to_thread，quote 沒有把 deadline／cancel 傳入 session lock，晚拿到鎖仍可能外呼。quota在等待worker之前預占，等待中收到429仍有晚呼叫。

indices啟動會同步驗symbol、阻塞ASGI；stop後把仍存活的worker當成已結束，restart清共用stop event可能復活舊run。callback／finally共用latest、queue、connection，可能干擾新run。股票已有epoch/scope/lease，但真正SDK起點仍需再驗run與cancel。raw quote 的 `isTrial in (False,None)` 會把0/0.0當成false。

## 要做什麼

- [ ] **401.1 範圍與安全。** 只修改 `fubon-broker-service/src/fubon_broker_service/` 相關既有模組及必要中立新模組／對應 tests，不改Java、Vue、schema、Compose、.env或secrets。使用者已允許在此隔離codex分支修改重疊檔案，但不得修改、stash、reset或刪除其他worktree。SDK只可查詢與市場訂閱，不import／包裝／呼叫任何下單、改撤單、圈存、匯款或券商資金寫入。不開真人連線、不變更FUBON_ENABLED=false及既有feature flags。富邦官方為帳務來源，帳務目標限tw.leader@gmail.com；本task不決定financial mapping、不新增DB writer，不把目前394／395預檢視為已完成。

- [ ] **401.2 窄 Protocol 與安全 capture。** 以中立 `ports.py` 定義PortfolioReader、TradeReader、BankBalanceReader、SettlementReader、RealizedGainReader、QuoteReader、EtfHoldingsReader、DividendReader、TechnicalIndicatorReader，每個consumer只看自己實際使用的方法；IndexStreamGateway／StockPushGateway／RuntimeState分開，不能建立mega SDK介面。SdkGateway為外層實作，只有app組裝concrete。SelectedAccount、AccountingRead、AccountingPair、StockPushConnection移到中立captures；account／branch／token／raw payload一律repr=False，SdkCallError移中立消毒例外，raw_field／enum_text／payload-size／config-digest helper移純utility/config。service、stream及normalizer不得再import concrete sdk_gateway或其private helper。capture仍在同一accounting lock內取得selected account、同批response和token；Task406兩種來源報表另須在取得response的同一capture臨界區固定不可變UTC微秒觀測時點，normalizer不得在鎖外用完成時間取代；raw身分／來源日先驗後HMAC、ISO wire、數量／emptyConfirmed／None／signed money等語意全保留。不得順手把raw SDK物件輸出、落地或寫log。

- [ ] **401.3 同一 absolute deadline。** query入口建立不可延長的monotonic deadline與cancel token，透過各Protocol帶至gateway。沒有更短既有aggregate的read入口固定30秒（caller更短取min），ETF整批30秒，未完成各列回既有消毒failure，保留其他成功列；已有更短aggregate者維持更短值；read/login/init每個native call最多5秒、cleanup最多2秒，quote endpoint30秒、每個shared quote flight5秒，technical每symbol三組合計25秒。所有等待session/accounting lock、quota、slot及auth retry都計入原期限；取得等待資源後與每個SDK真正起呼點再驗，不能用retry重置。shutdown先關閉新工作admission：零新login/read/subscribe，僅允許cleanup。已開始native不能強殺；caller timeout/cancel後仍占4-slot容量，只有native worker真正結束的finally才釋放，晚結果不可重新喚醒已取消工作。保留帳務rolling5calls/sec與單一session/reconnect mutex；accounting/history/quote配額都只在actual native dispatch計數，含auth retry每次業務方法，不在等待slot前reserve，不把退出timeout當作成功清理。

- [ ] **401.4 真 dispatch 的quote admission。** 每次輸入1..100codes及既有格式不變；single-flight key固定 `(purpose, stockCode)`，跨request的排隊＋執行中 admitted keys最多100，logical workers最多20，native未結束最多4。同key新增waiter不重占key；第101個新key以逐檔消毒failure `BATCH_CAPACITY_REJECTED` 回覆，不丟棄已開始工作的會計記錄。每次實際quote SDK呼叫前，在同一dispatch判定內確認slot、rolling240/min、429 circuit、deadline、cancel；auth retry每次也計額度。不能先在event loop占quota再無界等slot；等待期間別的call回429，尚未開始者不得外呼。429維持至少60秒、有效Retry-After上限600秒，僅真429觸發其既有退避，不把普通schema失敗誤判限流。同flight每個waiter獨立取消；還有有效waiter時繼續共享，最後waiter離開阻止未dispatch SDK。caller已收到timeout/cancel但native仍活著時，slot及該admitted key都保留至worker真結束；新request不得另起同key並行native。未dispatch的取消排程須確認不會晚起呼才可移除key。INVENTORY-only成功cache30秒、LIVE不走此cache，wire counters維持原13key集合，不新增process欄位到market response。

- [ ] **401.5 每個stream run自行擁有資源。** indices與stock各自持有run generation、不可clear復活的cancel、worker、connection、subscription、latest／queue。start/stop交換與callback admission要有明確線性化點；舊callback、late connect／ACK或finally只能清自己的run，不得開始subscribe／fan-out或清新run狀態。indices symbol verification與connect放離ASGI event loop且啟動single-flight，connect最多5秒、cleanup最多2秒；timeout後仍活著的worker持續計容量，晚connect只能清理。stock保留最多300symbols、獨立Normal aggregates client、5秒ACK、scope version、queue64、120秒lease及差集subscribe/unsubscribe；indices和stock不得互相關閉／重用connection。所有SDK subscribe起點再驗deadline/cancel/generation，shutdown後不得晚訂閱。stop前已開始native依真實結束處理，不能因join timeout把worker設為已死亡後另起無界worker。

- [ ] **401.6 統一有限的輸入解碼，不改路由安全。** app只組裝與HTTP轉換，純 `request_contract.py` 接已選定route的資料；全數既有body在累積前以65,536 raw bytes封頂，解UTF-8後拒絕duplicate key、trailing JSON、unknown field、missing required、非exact型別與coercion。只接受下表既有shape，query參數一律不新增；無body路徑要求真正空body（不是 `{}` 或null），不接受帳戶或任意URL selector。沿原config／token／reason/outcome規則，不改任何method、redirect、alias或新增host/public入口。不得為測此項對runtime進行internal alias／方法矩陣／安全探測；用靜態路由核對與純parser fixture。

| 既有方法／路徑 | 輸入 |
|---|---|
| GET /internal/health、GET /internal/config | 無query、無body；health仍可於disabled回UP |
| POST /internal/portfolio/read | exact `{dryRun:true}`，真正boolean，false不允許 |
| POST /internal/market-data/tw-quotes | exact `{codes:[string],purpose:"LIVE"或"INVENTORY"}`，1..100codes |
| POST /internal/market-data/etf-holdings | exact `{codes:[string]}`，1..50個合法ETF code、不得重複 |
| POST /internal/trades/read | exact `{startDate,endDate}` ISO字串、起迄有序且相差至多7日 |
| POST /internal/bank-balance/read、/internal/settlement/read、/internal/realized-gains/read | 無query、無body，固定selected account |
| GET /internal/market-data/taiex-index/stream | 無query、無body；獨立indices gate |
| POST /internal/market-data/dividends/read | exact `{symbols,from,to}`，1..2000codes、ISO日期；固定queryDate−320天至+45天 |
| POST /internal/market-data/technical-indicators/read | exact `{symbol,from,to}`，ISO日期；固定queryDate−120天至queryDate |
| POST /internal/market-data/stock-push/subscriptions | exact `{symbols:[string]}`；可空以取消，最多300 |
| GET /internal/market-data/stock-push/stream | 無query、無body；獨立stock gate |

- [ ] **401.7 保留來源與節流語意。** isTrial缺欄可依actual-trade pair判定，存在則只能為真正boolean false；null、0/0.0、字串和true拒絕，indices/stock價格日期／微秒、raw19欄不改。portfolio官方YYYY/MM/DD先轉date後對帳的修復不變。technical仍2個Java symbol worker，每90秒最多20symbol attempts、共用3.1秒起呼間距、每symbol KDJ→MACD→BB依序與25秒aggregate，rolling60/min shared history配額、真429停止未開始者、全輪30分鐘。原after-close/same-day/radar、sourceDate與PARTIAL都保留，不承諾vendor每次一定回全資料。官方帳務5/s與historical60/min和本系統quote240/min/90秒保守節拍分開，不宣稱官方同API每分鐘只能一次。官方依據為[帳務限制](https://www.fbs.com.tw/TradeAPI/docs/trading/trade-rate-limit/)與[行情限制](https://www.fbs.com.tw/TradeAPI/docs/market-data/rate-limit/)。ETF既有50code/4worker、只讀成分股票、source-date語意不變，單筆逾時不得讓其他成功結果消失。

- [ ] **401.8 新增有意義的隔離回歸。** 以下為**規劃新增**測試群，不是現成成功報告：20logical/4native不假飽和；第100／101key與same-key多waiter；一waiter取消而另一成功、最後waiter取消後零late dispatch；slot/lock/quota等待逾時、timeout native仍占slot和admitted key且新同key不能再dispatch、shutdown後late lock／late connect不新呼叫；accounting5/s/history60/min/quote240/min的auth retry actual-dispatch計數與等待不預占、真429阻止pending而已開始call仍完成；rolling窗口邊界和Retry-After；indices同時start只一次symbol verify、ASGI可處理health、oldrun stop/start/latecallback不影響newrun；stock token/scope/ACK/lease race；raw repr不含帳戶/secret、strict parser65,536／65,537bytes及multibyte邊界、duplicate/trailing/invalid UTF-8/unknown/coercion；isTrial的missing/false/true/null/0/0.0/string。時間與競態用fake clock/latch/barrier控制，不靠長sleep碰運氣；保留完整既有portfolio/trade/ETF/dividend/technical/redaction回歸，不以只測disabled取代正常成功路徑。

## 驗證

先完成獨立spec審查才可改程式。以下命令從本task所在feature worktree的repo root執行，Python環境為既有3.13 venv；不需真人憑證。

```bash
bash scripts/spec-check.sh
PYTHONPATH=fubon-broker-service/src /tmp/fubon-venv-t395/bin/python -m pytest -q fubon-broker-service/tests
git diff --check
FUBON_ENABLED=false DOCKER_HOST=unix:///Users/steven/.docker/run/docker.sock FUBON_SECRETS_DIR_HOST=/Users/steven/Project/asset-management-main/secrets/fubon docker compose -p asset-management --env-file /Users/steven/Project/asset-management-main/.env build fubon-broker-service
FUBON_ENABLED=false DOCKER_HOST=unix:///Users/steven/.docker/run/docker.sock FUBON_SECRETS_DIR_HOST=/Users/steven/Project/asset-management-main/secrets/fubon docker compose -p asset-management --env-file /Users/steven/Project/asset-management-main/.env up -d --no-deps --force-recreate fubon-broker-service
```

Docker命令只由協調者依run-stack操作共享stack：先核對main .env原值及hash未變，另核對容器有效FUBON_ENABLED=false／六個新flags不變；main .env目前global原值為true，所有build/recreate必顯式以process env覆寫false，不改該檔既有flag、來源worktree與canonical唯讀secret mount，再build/recreate；禁止down、刪volume或覆蓋.env。以容器health及逐檔已測Python source hash驗實際image，Linux/amd64 test-target另以network none重跑同套tests，不能重複加總為不同測試。runtime GET白名單僅首頁、既有health/config及commodity/calendar純讀；authenticated富邦目錄在本task單獨階段為52/15/37；若與406一併交付且兩份來源報表完整驗收，最終為52/17/35。排程64不變。禁止真人SDK、manual sync、subscription、rescan POST或method/alias探測。功能正常路徑只由fake SDK與隔離測試證明。

經完整diff獨立架構審查、feature runtime驗收後由協調者commit→no-ff merge→push，再從main重建驗證。原兩個session全範圍完成前保留原Claude refs。

## 完成報告

尚未實作；核准規格後由實作者回填實際檔案、測試與部署證據，checkbox保持未勾選。
