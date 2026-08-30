# [t404] 富邦後端readiness、行情與持久化窄介面

> 階段性收尾：本檔尚未實作；第一輪3項 major已依建議修正文案，仍待第二輪獨立spec審查，不得視為已通過。接續前請讀 [交接紀錄](../../docs/handoffs/fubon-refactor-2026-08-30.md)。

**對應 Requirements:** Requirement 90（庫存唯讀同步與原子快照）、120（成交新增）、123（ETF持久化與純讀）、128–130（帳務既有gate）、109／114（pure-read五檔）、133（不改本地技術算法）
**前置任務:** t399／t400已落地；固定帳務owner由獨立owner工作單元處理，同檔按序接續，不並行覆蓋。
**Liquibase changeset:** 無

## 背景

main `6aa219719c4d20d3ef405ba4b5cad75e1316df15` 的MarketDataService直接建WebClient、組URL與HTTP。富邦core service/scheduler又依賴會讀Files的FubonConfigState及整個MarketDataService，交易查名與庫存upsert直接依StockMasterService；ETF sync直接依純DB writer，庫存／銀行writer直接依DB row-lock wrapper。這些是真正IO責任，需要以小介面隔離；snapshot calculator、parser、counter與純應用編排不需為形式包介面。

既有成交批次驗證、鎖後freshness與快照總額修復已驗收，這次只調整依賴，不可重新引入stale OSIV、self-invocation假交易、preflight之前取錯owner或先Redis後DB的行為。

## 要做什麼

- [ ] **404.1 範圍與保留。** 本task只改backend相依及相關tests，不改schema、前端、BFF、external、SDK、環境或排程。其他worktree原樣保留；重疊修改已獲使用者允許，但同一worktree內與owner實作者必須按序接手下列共用sync/writer，不能同時改同檔。富邦只唯讀、主開關false，不真人API、不執行同步/重掃POST。來源富邦官方，帳務只限tw.leader@gmail.com的政策由owner工作單元統一，本task不另造選人規則或解除394/395目前no-write。

- [ ] **404.2 MarketDataService依內層port。** 新增 `service/marketdata/InternalMarketDataPort`，只有下表六個固定方法；外層 `integration/marketdata/ExternalMaterialsMarketDataClient`實作WebClient、base URL、HTTP、codec與timeout，不帶caller/owner身分，不接受任意URL/path。核心constructor只收port＋既有repository，不保留URL字串constructor偷建adapter，不能直接import WebClient/URI或讀config檔。HTTP失敗沿原service fallback；四個原無界GET加20秒總deadline，其餘既有2/20秒保留，無自動retry。最後一項原本就有偵測副作用，不冒稱整個port均pure-read。

| port方法 | 固定internal入口 | 總deadline |
|---|---|---|
| quoteDetail(code,market) | GET /internal/quote-detail | 2秒 |
| dividendRate(code,market) | GET /internal/dividend-rate | 20秒 |
| etfHoldings(code,market) | GET /internal/etf-holdings | 20秒 |
| dividendHistory(code,market,years) | GET /internal/dividend-history | 20秒 |
| twHolidays(year) | GET /internal/tw-holidays | 20秒 |
| detectTwClosureToday() | 既有POST /internal/tw-closure/detect | 20秒 |

- [ ] **404.3 五個中立record與服務語意。** 將DividendRateResult、EtfHolding、EtfHoldingsResult、DividendRow、DividendHistoryResult與原compat constructors搬到 `model/marketdata/MarketDataResults`；保留原field名／順序／nullable／String日期與JSON shape。PriceResult不在此HTTP範圍，留原位置。MarketDataService所有public方法名稱／參數不改，同module呼叫者只改中立record import；不承諾舊nested FQCN二進位相容。保留quoteDetail exact code/market/source及完整五檔驗證、殖利率1小時cache/主檔name fallback、台股ETF本地snapshot+純parser（成功／失敗／empty均零portcall）、非台股ETF原internal來源、當年holiday10分鐘cache/空回應不毒化/非當年每次proxy、NYSE/LSE純日期算法和既有closure fallback。port不自行快取或改來源優先序。

- [ ] **404.4 Secretless readiness。** 新增 `FubonReadinessPort.readiness()`，回中立 `FubonReadiness(State, reason)`，State僅DISABLED/READY/MISCONFIGURED，reason為nullable消毒字串；不含token、path、baseUrl、raw identity。FubonConfigState作外層實作，沿原lazy flag/Files檢查；含token的Snapshot只留FubonHttpClient／各internal-token filter，不讓core持有。Inventory/Trade/ETF/BankBalance/Settlement/RealizedGain sync及Inventory/Trade scheduler改依port；localConfigOutcome改中立State。feature/capacity gate仍在應有的位置早於readiness，false/invalid flag零token-file read；缺token不讓Spring startup失敗。READY不代表永久通行，HTTPclient每次仍依原規則讀當時token，rotation/misconfigured fail closed。若將State移中立，outer client/filter僅機械改enum引用，不能趁機變method/path/token outcome。SnapshotStockScopeOwnershipAdapter是外層，本身不為形式強加無作用wrapper；與owner修訂時共用同一readiness/政策即可。

- [ ] **404.5 Calendar與純ETF分類。** 新增單方法 `FubonCalendarPort.isTwTradingDayKnown(LocalDate)->Optional<Boolean>`，outer adapter只委派原MarketDataService；true/false/empty及exception→unknown保持，不用週一到週五猜交易日、不建立第二份cache、不繞過原facade直呼新HTTPport。富邦core/scheduler不再持MarketDataService。isEtf的prefix/whitelist是純計算，抽中立EtfClassification；原MarketDataService.isEtf保留delegate供既有caller，ETF sync使用同一函式，不複製新規則、不改美股whitelist或大小寫。

- [ ] **404.6 股票主檔兩種能力。** `FubonStockNameLookup.resolveNameLocalOnly(code,market)->String`與`FubonStockCatalogWriter.upsert(code,market,name)->void`可由原StockMasterService直接實作或outer adapter委派。成交只用前者，其缺資料仍回code並由原成交規則判未解析／略過；不能改用會向vendor外呼、upsert及回補的resolveName。庫存upsert仍走原Spring proxy與同一transaction，首次stock只在成功afterCommit排一次原歷史回補，rollback零回補，無active transaction的既有行為不變；不新增無caller的history port或在writer等待HTTP回補。

- [ ] **404.7 ETF writer與snapshot lock ports。** 新增單方法 `FubonEtfHoldingsWritePort.save(FubonEtfHoldingsSnapshot)`，原FubonEtfHoldingsWriter implements，依原REQUIRES_NEW saveAndFlush且成功commit後才返回；ETF sync不可依concrete writer或catch commit失敗宣稱成功。另新增 `FubonSnapshotLockPort.lockLatestForFubonConfiguredOwner(Long)->Optional<AssetSnapshot>`，原AssetSnapshotMutationLock實作，同一named native owner row-lock與MANDATORY不改。Inventory／Bank writer只依此port，取得同一asset_snapshot row仍是writer第一個DB動作，不能在decorator先查owner/config/children；一般CRUD lockById/lockLatestForOwner/lockAll不變，tenant filter不可停用。

- [ ] **404.8 保留Task399可證明的交易。** 庫存／銀行先lock→refreshLockedSnapshot→fresh owner/broker/bank/type→檢查，再改managed children與唯一SnapshotAggregateCalculator，同transaction flush與beforeCommit日期重驗；原owner被停用或children被他交易更新不能從L1舊值判成功。成交完整typed batch/strict codec先驗、精確partial conflict target insert-only、非重複錯誤全批rollback、commit後才SUCCESS，manual ledger不覆寫。新port不得吞掉failure或提前回可寫狀態。owner directory的fresh immutable DTO由獨立owner工作單元處理，不能用preflight舊DTO替代writer內fresh read。

- [ ] **404.9 修改檔案界線。** 主要既有檔：MarketDataService、FubonConfigState、六類Fubon sync、Inventory/Trade scheduler、Inventory/Bank writer、ETF writer、StockMasterService、AssetSnapshotMutationLock；新增上述port/value/outer adapter/pure classifier。MarketDataController、InternalPublicMarketDataController、AssetService、DividendHistoryService、FubonEtfHoldingsParser只能作五record機械引用修改；FubonHttpClient與各token filter只在需要時改中立State。不得因此重寫其他PriceQueryService/MacroHistoryService/HistoricalDataService transport，不能修改原Task399/400已完成task以掩蓋行為退化。確保owner工作單元接續共用sync/writer的當前版本。

- [ ] **404.10 有意義的回歸（規劃新增）。** 純core使用fake port即可驗readiness/known calendar與台股ETF所有結果零HTTP，cached dividend只首次查、非當年calendar每次查、source與stock-name fallback不變。outer HTTP用隔離fixture驗六個原path/query/verb及DTO shape、2秒quote與20秒其他deadline涵蓋slowbody，closure POST只在fixture，不打live。readiness false/invalid/feature-disabled零token/calendar/broker；READY/missing token/rotation消毒。local-name missing零vendor/upsert/backfill；真PostgreSQL16證明inventory新stock成功afterCommit一次、rollback零回補、ETF commit failure不成功，snapshot port仍first DB lock/同row且MANDATORY。保留完整1948項基線涵蓋的OSIV、owner隔離、兩種full PUT鎖順序及成交rollback，不改POM/tenant filter或拿H2代替；新增總數以實際JUnit為準，不預宣稱。

## 驗證

先獨立spec審查，從feature repo root：

```bash
bash scripts/spec-check.sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home DOCKER_HOST=unix:///Users/steven/.docker/run/docker.sock /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml clean test -DextraArgLine=-Dapi.version=1.44
git diff --check
bash scripts/tests/schema-sql-drift-test.sh
FUBON_ENABLED=false DOCKER_HOST=unix:///Users/steven/.docker/run/docker.sock FUBON_SECRETS_DIR_HOST=/Users/steven/Project/asset-management-main/secrets/fubon docker compose -p asset-management --env-file /Users/steven/Project/asset-management-main/.env build business-services
FUBON_ENABLED=false DOCKER_HOST=unix:///Users/steven/.docker/run/docker.sock FUBON_SECRETS_DIR_HOST=/Users/steven/Project/asset-management-main/secrets/fubon docker compose -p asset-management --env-file /Users/steven/Project/asset-management-main/.env up -d --no-deps --force-recreate business-services
```

保留Java21與POM的Asia/Taipei，extraArgLine只設既有Docker API相容值，不蓋argLine或skip測試。DB使用隔離Testcontainers。協調者依run-stack先核對main .env/flag/hash、shared-only canonical secret mount，序列操作shared stack，等待healthy再以正確main image recreate BFF重連；不down、不刪volume、不覆蓋其他worktree/.env。

runtime驗image worktree與tested class byte/hash、既定GET首頁/health/config/commodity/calendar及authenticated API目錄在404單獨階段為52/15/37，若與406整批且兩報表已完整驗收則最終52/17/35；排程64；不執行closure detect、真人broker、manual sync、subscriptions、rescan POST、internal alias／method矩陣／安全探測。feature驗收與完整diff獨立架構review後commit→no-ff merge→push，再從main重建並讀回證據。帳務owner與來源報表未完成時不宣稱全富邦工作完成、不刪原Claude branches。

## 完成報告

尚未實作；完成後回填實際檔案、JUnit/交易證據、獨立審查與runtime結果。所有checkbox維持未勾。
