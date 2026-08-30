# [t402] 富邦行情窄介面、串流重啟與已提交狀態修復

> 階段性收尾：本檔尚未實作；第一輪3項 major已依建議修正文案，仍待第二輪獨立spec審查，不得視為已通過。接續前請讀 [交接紀錄](../../docs/handoffs/fubon-refactor-2026-08-30.md)。

**對應 Requirements:** Requirement 91／106／115（有限雷達行情與完整回應保存）、109／114（五檔 canonical 與 pure read）、116（大盤串流與DB先提交）、131（股利證據）、132（個股推播）、133（技術快取）
**前置任務:** 既有富邦行情、股利、技術快取已實作；本task與Python生命週期修復可獨立驗收，wire保持相容。
**Liquibase changeset:** 無

## 背景

main `6aa219719c4d20d3ef405ba4b5cad75e1316df15` 的external核心服務仍直接依賴concrete HTTP/JDBC/Redis類，且多個資料值定義在client/store內。只抽一個interface卻仍回傳concrete nested DTO不會修正依賴方向。FubonTechnicalCache也反向import client codec。

TAIEX的running/body/executor共用，old open卡住→stop→new start→old open返回時，舊worker可清掉新body或開始ingest。股票stream雖有generation，check後對共用activeBody的set/getAndSet仍可互關。TAIEX readLine無界、只有connect timeout；normalized quote先讀完String才驗2MiB。index repair的timestamp Set在Redis長期失敗時永久累積，單純改成成功clear/失敗set的AtomicReference仍有舊completion覆蓋新狀態的競態。

## 要做什麼

- [ ] **402.1 範圍與零新能力。** 修改只在external-materials-service及其測試；不改vendor來源、非富邦市場演算法、schema、排程、公共route、Docker旗標或憑證。其他worktree保留原樣。全程fake vendor與隔離DB/Redis，不真人SDK、不打manual sync/subscription/rescan POST。FUBON_ENABLED=false與各flag現況不變。市場/radar仍沿既有全域範圍，不因帳務owner限tw.leader@gmail.com而改成私人行情。

- [ ] **402.2 小介面由現有adapter實作。** 在 `service/port/` 建下表能力，DTO／pure helper放 `model/marketdata/`；可採等價內層命名，但不得巨大介面或中立port反import concrete class。現有client/store/cache直接implements，不另寫第二套SQL、Lua或無作用wrapper。業務消費者為FubonLiveResponseReadService、QuoteDetailReadService、FubonTaiexIndexIngestionService、TaiexIndexPoller、TwLiveQuoteDispatcher、PricePoller、TwFubonOrderBookRoundWriter、TwYahooOrderBookFallbackRoundWriter、FubonDividendEvidenceSyncService、FubonStockPushConsumer、FubonRadarScope、MarketCalendar。

| port | 唯一需要的能力 | 現有adapter |
|---|---|---|
| FubonNormalizedQuotePort | fetch(List codes) | FubonNormalizedQuoteClient |
| LivePriceFetchPort | fetchTwBatch、getYahooTwLivePrice、getStockPrice | PriceFetchClient |
| StockUniversePort | collectTwRadarCodes、collectHeldStockCodes、collectAllStockCodes | StockSourceQuery |
| StockMarketRepository | persistIntradayQuote、findLatestDatedClose、loadRecentTaiexCloses、upsertStockName | StockSourceQuery |
| PriceProjectionPort | write兩個原overload、writeTaiwanLive、writeTaiwanIndexLive、writeFubonStockPush、syncClosedFromDb | PriceCacheWriter |
| TaiexCanonicalRepository | persist、findForTradingDate | FubonTaiexIndexStore |
| IndexIntradaySourcePort | fetchIndexIntradayDay | MacroDataFetchClient |
| OrderBookRepository | persist、findRevision、findCanonical | IntradayOrderBookSnapshotStore |
| OrderBookCachePort | find、writeStrictNewer | QuoteDetailCache |
| YahooOrderBookSourcePort | probeTw、probeTwo | TwQuoteDetailFetchClient |
| FubonResponseRepository | persist、find | FubonLiveResponseStore |
| FubonResponseCachePort | find、writeStrictNewer | FubonLiveResponseCache |
| DividendEvidencePort | record既有append證據 | DividendSnapshotStore |
| TaiwanHolidaySourcePort | getTwHolidays、getTwHolidaysKnown、peekTwHolidaysKnown | MarketDataFetchService |

- [ ] **402.3 中立資料值與純函式。** 搬出PriceFetchClient的PriceResult／TwQuoteBatchSummary及原constructor/withTiming/withSource；TwQuoteDetailFetchClient的QuoteDetailResult／OrderBookLevel／YahooProbeOutcome；FubonNormalizedQuoteClient的BatchStatus／BatchResult／ValidatedEnvelope；StockSourceQuery的DatedClose／ClosePoint／IntradayQuote／IntradayPersistenceResult；TaiexStore的Candidate／CanonicalIndex／PersistResult／FindResult及status；book/response store與cache的canonical/result/lookup；DividendFetchClient的DividendEvent／DividendFetchResult／FetchStatus、DividendSnapshotStore.PersistResult、MacroDataFetchClient.DayQuote、PriceCacheWriter outcome。原field名、型別、nullable、順序及建構子語意保持，一種值只有一份，candidate與committed canonical仍可區分。ProviderTimedPriceObservation與FubonMarketData不得漏改。supportedIdentity／revision text／book-validity抽中立pure contract，不讓core呼concrete static。只把external bridge的Quote.tradingDate改LocalDate，ISO wire不變；raw19及opaque normalized JSON不遷移。deprecated FubonTwLiveQuoteProvider／ExistingTwLiveQuoteProvider不是production bean，不能重新接入；僅機械型別適配，legacy writeProviderTimed若保留用自己的fixture port，不能塞進production介面。

- [ ] **402.4 技術快取的codec隔離。** 既有FubonMarketDataPort／FubonTechnicalCachePort已足夠，不新增等價IO介面。保留Attempt／Group／Document／Read／Write與manifest為中立值；FubonTechnicalCache不得再import client.FubonMarketJson。JSON encode/decode、canonical-hash與persisted-format validation移outer Redis codec，或移到真正不依client的共用純codec；不為純函式另造interface。v1 key／JSON bytes／hash／參數manifest／sourceDate fence／7日expiry／15分鐘failure診斷精確相容，不能重編碼造成等日異值或刷新TTL。

- [ ] **402.5 每個SSE run獨立擁有。** TAIEX與stock各用Run保存generation、cancelled、state、opening future、body、worker/executor；client只保留current reference。start/stop交換與ingestion admission採同鎖或等效機制，stop完成後不開始舊run的ingest。late open只有仍為current且未cancel才可註冊；否則關那一份response。舊finally只清自己的fields，不可對shared activeBody getAndSet後誤關新run。stop先使自己的run失效，再關其body／取消pending／interrupt其worker；新run不得clear舊cancel。stop前已admit的有限DB transaction依原commit/rollback，不假稱可事後殺掉。stock／index完全獨立。StockPushSubscriptionManager新增terminal shutdown gate，排隊refresh取得同鎖後仍先驗；shutdown後零subscription/start，一般空scope clear不等於terminal。flag false／invalid symbol時仍早於token read／HTTP；token失效不產生retry storm。

- [ ] **402.6 真正有限的transport。** normalized quote使用production bounded subscriber或等效reader，在累積／轉String前限制2MiB raw response bytes，含chunked/無Content-Length；超限立即cancel/close。30秒總request deadline涵蓋header和slowbody、connect5秒、不跟redirect；保留RATE_LIMITED/SERVICE_UNAVAILABLE/INVALID_RESPONSE區分，無envelope即零Fubon writer。兩SSE可共用純UTF-8 reader但不能共用Run：每line及每frame各16KiB raw bytes，包含LF/CRLF delimiter、comment、未知欄位；完整空行結束frame，不能用整段stream累計大小擋正常多frame。非法UTF-8、超限或framing error關自己的body；未完成frame不dispatch。SSE connect5秒/header10秒，成功200/event-stream後沒有整體lifetime timeout；timeout或cancel後的late future/body仍須被清理。維持原event/id/strict JSON/微秒驗證及250/500/1000/2000/5000ms bounded reconnect。

- [ ] **402.7 canonical結果必在commit後返回。** Fubon response store、order-book store、StockSourceQuery.persistIntradayQuote與既有Taiex store，只有已成功commit的canonical result可交Redis。使用真獨立transaction或有效NOT_SUPPORTED編排＋writer，不能依賴現在caller碰巧無ambient transaction；所有read remain pure read。保留整批原子性、欄位精度、原SQL comparator、source precedence及同一Lua；DB失敗無Fubon price/book/response投影，不用raw候選補canonical。outer rollback不能留下未commit投影；已獨立commit的inner結果若依原契約保留，DB/Redis須一致，不誤稱外層能撤銷它。完整五檔仍同一source：same-source只較新、Fubon可取代Yahoo、Yahoo取代Fubon必更晚；DB revision遞增，Redis只接受較新DB revision。reader先比DB revision，DB不可驗時不回未驗證cache、不repair、不vendor-call。

- [ ] **402.8 有界index repair。** 用單一O(1) ProjectionState保存最高committed canonical、attempt generation及needsRepair。APPLIED返回後先登記watermark/attempt才查previous-close／投影；DB failure或無效event不創state。完成只CAS同watermark/attempt，成功清needsRepair但保留最高watermark；C1遲失敗不能在C2成功後復活，C1遲成功不能清C2失敗。previous-close／Redis例外要保留pending；較新canonical成功淘汰舊pending。exact-equal raw只有store回同timestamp canonical且目前needsRepair才可修，用DB canonical而非raw point；older／沒pending的equal不寫。重啟沒有pending時equal不自行repair，兩分鐘poller仍pure-read今日canonical修Redis、無今日row才Yahoo。index不寫tick/day-HL/volume/bidask，不覆蓋verified close或完成日K。

- [ ] **402.9 保留各producer既有範圍。** 所有LIVE入口先input∩各owner最新台股holding/alert radar、排除0000；empty/error零vendor。富邦40-code公平cursor、真正429暫停、MIS320直接能力上限、既有Fubon→MIS→Yahoo actual-price順序不變。Yahoo book只同輪selected缺完整Fubon者，8-code fair cursor、最多4 probes並行／每probe2秒／全輪9秒；只有.TW的HTTP404才能再.TWO。transport失敗與DB transaction失敗原本允許的book fallback分支不同，不混成同一catch。股利只append既有PARTIAL/FAILED、complete=false，不改source權威或17:00全市場排程。技術90秒batch/2worker/3.1秒間隔與來源cache語意不變；不重新計算雷達。

- [ ] **402.10 精確修改範圍。** 主要檔案為上表adapter／consumer，以及FubonNormalizedQuoteMapper、FubonTaiexIndexStreamClient、FubonStockPushStreamClient、FubonStockPushSubscriptionManager、FubonTechnicalCache及outer cache codec。必要機械caller可包含InternalPriceController、ClosePersister、DividendPersister、FubonMarketJson、TaiwanOfficialDividendCalendarClient、NasdaqDividendCalendarClient及legacy fixtures；只能更新中立型別／imports，不能更改它們的非富邦策略。新增ports／model／bounded transport helper與對應tests；不加Maven shared module或新依賴。同步bridge日期的BFF工作由另一task負責，wire始終ISO，兩邊可分別驗收。

- [ ] **402.11 必要驗證案例（規劃新增）。** 用latch驗兩SSE的old-open忽略interrupt、stop/start、新run先收到body後old finally到達，newbody/state保持、oldframe零ingest；manager late refresh在shutdown後零動作。實際production JDK transport配隔離loopback fixture驗2MiB邊界/chunked超限EOF前中斷/slowbody總期限，不能只mock完成的大String。SSE驗無newline超長、多短line超frame、UTF-8多byte／非法byte、delimiters計數、header timeout及late body cleanup，合法長stream跨header期限仍可用。index連續數千失敗只一份state、C1/C2兩種反序completion、DB失敗零Redis、canonical point與raw不同時只用canonical repair。真PostgreSQL16／Redis7驗commit前零投影、commit failure零Redis、outer transaction場景、兩來源revision競態、DB unavailable不返回cache、technical v1 hash/TTL/CAS不變。完整既有external測試必通過，不只測新interfaces或disabled。

## 驗證

先獨立spec審查才改production，從feature repo root執行：

```bash
bash scripts/spec-check.sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home DOCKER_HOST=unix:///Users/steven/.docker/run/docker.sock /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml clean test -DextraArgLine=-Dapi.version=1.44
git diff --check
bash scripts/tests/schema-sql-drift-test.sh
FUBON_ENABLED=false DOCKER_HOST=unix:///Users/steven/.docker/run/docker.sock FUBON_SECRETS_DIR_HOST=/Users/steven/Project/asset-management-main/secrets/fubon docker compose -p asset-management --env-file /Users/steven/Project/asset-management-main/.env build external-materials-service
FUBON_ENABLED=false DOCKER_HOST=unix:///Users/steven/.docker/run/docker.sock FUBON_SECRETS_DIR_HOST=/Users/steven/Project/asset-management-main/secrets/fubon docker compose -p asset-management --env-file /Users/steven/Project/asset-management-main/.env up -d --no-deps --force-recreate external-materials-service
```

共享stack只由協調者依run-stack操作，先核對main .env/flags/hash與canonical shared-only secret mount，不能down、刪volume、改flags或覆蓋其他服務。等待healthy後以正確main BFF image recreate BFF重連upstream；驗container工作目錄／image與tested classes/source hash。runtime只可用GET首頁、health/config、9090commodity/calendar純讀及authenticated富邦目錄／排程頁；不得method矩陣、internal alias／安全探測、真人broker、sync/subscription/rescan POST。目錄基線52/15/37與64jobs不因本task改變；與406整批交付且兩份來源報表完整驗收後，最終目錄52/17/35、jobs仍64。

以完整diff（含中立值、adapter及機械caller）作獨立架構review，feature Docker驗收通過才commit→no-ff merge→push，main重新部署驗證。原session全範圍尚未完成時不刪原Claude branches。

## 完成報告

尚未實作；本檔測試均為規劃，完成後才回填實際檔案／JUnit／runtime與獨立review證據，所有checkbox暫未勾選。
