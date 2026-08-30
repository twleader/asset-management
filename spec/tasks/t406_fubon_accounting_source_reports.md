# [t406] 保存富邦官方交割與損益來源報表，提供本人唯讀頁面

> 階段性收尾：本檔尚未實作；第一輪3項 major已依建議修正文案，仍待第二輪獨立spec審查，不得視為已通過。接續前請讀 [交接紀錄](../../docs/handoffs/fubon-refactor-2026-08-30.md)。

**對應 Requirements:** 129／130 的來源報表正常保存與90的個人owner邊界；121只更新靜態能力說明，不承接任何私人報表資料。
**前置基線:** 6aa219719c4d20d3ef405ba4b5cad75e1316df15；Task405固定owner/explicit-account能力先具備，401–404已建立的ports/中立DTO沿用，不複製SDK/client。
**Liquibase changeset:** 規劃 `v1.121.0-fubon-accounting-source-reports.sql`，新增三張typed表；其他60個worktree前次盤點最高1.120.0，建立migration前須重新檢查版號競態。現在92表，migration驗收後才95表；本檔不是schema已存在的宣告。

## 背景

富邦官方是使用者指定的可靠來源，但它的報表沒有本地財務入帳要求的全部欄位。應保存實際提供的來源事實，不能因缺成本／淨收款就丟棄正常資料，也不能捏造那些欄位。新的完整正常路徑是官方回應→typed來源報表→本人純讀UI；結果為SOURCE_REPORT_SAVED，不是更新快照在途款或新增realized_gain。

官方[Python交割](https://www.fbs.com.tw/TradeAPI/docs/trading/library/python/accountManagement/QuerySettlement/)明示date為查詢日、交割日／金額有None；[Python損益](https://www.fbs.com.tw/TradeAPI/docs/trading/library/python/accountManagement/RealizedPnLDetail/)列出買賣、種類、量價與profit/loss；[Go損益](https://www.fbs.com.tw/TradeAPI/docs/trading/library/go/accountManagement/RealizedPnLDetail/)說明每成交一列與含費稅。沒有因此得到fillId、實際成交日聯結、逐筆成本／淨收款或完整未交割覆蓋。原Task394／395的財務入帳目標仍未完成，不勾選、不刪原Claude refs。

## 要做什麼

- [ ] **406.1 範圍、安全與交付拆分。** 只改Python settlement/realized正規化和必要accounting helper／tests；backend既有settlement/realized sync、typed report model/ports/adapter/read/write/controller、Liquibase與schema快照／tests；BFF新增本頁資料夾及既有catalog/JOBS兩項描述／tests；frontend本頁/API/router/選單必要修改。個人範圍固定專用owner，其他worktree不變。同worktree的sync/DTO/ownership/shared spec須按序接手，不能和404/405同時改同檔。Vue依專案指示由Terra/high agent實作。FUBON_ENABLED與所有既有flags/憑證保持現況、不真人SDK/login/query；不得manual sync/subscription/rescan POST，不做live/internal alias、方法矩陣或security probe。

- [ ] **406.2 原排程與短transaction正常保存。** 兩job仍每日Asia/Taipei `0 0 8 * * * / 0 45 13 * * * / 0 30 19 * * * / 0 0 22 * * *`，不加交易日或90秒帳務排程。FUBON_SETTLEMENT_SYNC_ENABLED／FUBON_REALIZED_GAIN_SYNC_ENABLED的本階段動作明確改為保存來源報表，原global/config/feature/owner gate不變，部署仍不啟用。broker HTTP/preflight在NOT_SUPPORTED；dryRun=true只驗證、零writer／寫鎖，scheduler/false成功後才呼獨立REQUIRES_NEW(timeout30s) report writer。Python仍只呼query_settlement(selected,"3d")或realized_gains_and_loses(selected)，不新增SDK能力、不用GET觸發同步。

- [ ] **406.3 本人與同次來源雙重gate。** 寫入owner只由FUBON_SYNC_OWNER_EMAIL正規化後匹配的既存ACTIVE ADMIN，且須與configured-admin同id/email；不fallback、不建user、不hardcode私人email。preflight不符在HTTP前拒絕。writer先鎖目標app_user並fresh重驗同一expected owner，再操作報表；OSIV舊entity不能通過。每個SDK batch在Python同一accounting臨界區捕捉selector/selected/raw response/token，raw branch/account逐字匹配後只輸出HMAC及必填strict boolean accountBindingExplicit=true。false/missing/null/number/string不可進writer；raw identity/token不出Python、不log/repr/persist，fingerprint不作永久owner/account或fill鍵。市場資料不套此owner政策。

- [ ] **406.4 嚴格有限的時間與normalized輸入。** 兩種reply各最多10,000行、2MiB UTF-8 bytes；HTTP body讀取中封頂，含chunked/無Content-Length，不能先ofString再判長度。Python輸出亦遵守行數／大小界線，過大安全失敗且不截列。observedAt在取得SDK response的同一accounting capture臨界區固定為不可變UTC微秒時點，隨capture傳遞；normalization、HTTP回傳與writer不得重新取完成時間替代。入口queryDate、capture及完成時間須同Asia/Taipei日且順序有序，不以其代替來源日；不未來/不跨午夜。writer鎖後同日、observedAt≤now、age≤60秒，且新report的 `observedAt.getNano()%1000==0`，確保PostgreSQL微秒可無損保存；不可round/truncate奈秒使sameAt規則失真。其他既有Instant契約不動。Java日期LocalDate、觀測Instant、金額價格BigDecimal；wire decimal是canonical plain string，serializer須toPlainString、不產生exponent、不用String取代核心數值型別。

- [ ] **406.5 交割來源觀測與財務投影分離。** sourceQueryDate取官方date（嚴格YYYY/MM/DD→ISO，≤queryDate），settlementDate取官方settlement_date且≥sourceQueryDate。所有12個原始金額欄位用exact integer驗證，TWD、buySettlement≤0、sellSettlement≥0且兩者和等於來源totalSettlementAmount；不估費稅。只保存sourceQueryDate/settlementDate/currency/buySettlement/sellSettlement，原其他金額只驗形狀、DB不重存毛額／費稅／合計。全optional欄位None是合法NO_DATA_OBSERVED，sourceQueryDate仍必填；partial None失敗。裸空list是合法此次空報表。合法過去/當日/未來交割、當日非零、同交割日多列及完全同值行都保存；不Set去重、不推斷已入銀行。原coverageStatus=UNVERIFIED／MISSING_SETTLEMENT_RANGE_CONTRACT仍表示不能投影完整未交割，不阻止report保存，也不能假設VERIFIED。這兩欄不落新表；GET scope為SOURCE_REPORT_ONLY。

- [ ] **406.6 損益來源的種類、精度與多筆保留。** sourceDate嚴格來源日且≤queryDate，不改名tradeDate。stockNo合法非0000，Buy/Sell及Stock/Margin/Short/DayTrade/SBL依官方值保存，不建立可下單物件或只悄悄略過非Stock。qty是1..9,999,999,999 exact integer；price正值finite canonical decimal precision≤20/scale≤10。profit/loss各非負exact integer、可全零但不可同時正；只持久化單一signed providerNetPnl=profit-loss，不另存profit/loss/net三份。所有numeric先驗可精確表示，DB不可默默round。相同來源日/代碼/種類/買賣/量價，無論PnL相同或不同都完整保留各行；不生fillId、不查可修改ledger猜對應。來源未提供currency則GET回null，完全不提供proceeds/investmentCost欄位、不推ROI。名稱僅讀既有local master補顯示，不外部查詢。

- [ ] **406.7 新增最小typed schema。** 只新增下表，無raw JSON/capture/歷史輪詢副本、無原financial表schema變更。migration先檢查版號，repository/SQL只能在外層adapter；core以窄report persistence/read ports及immutable值編排，不反依賴concrete repository nested DTO。

| 表 | 欄位與限制 |
|---|---|
| fubon_accounting_report | id bigint identity PK；owner_user_id bigint NOT NULL FK app_user(id)；report_kind varchar(12) NOT NULL CHECK IN ('SETTLEMENT','REALIZED')；observed_at timestamptz NOT NULL；UNIQUE(owner_user_id,report_kind) |
| fubon_settlement_report_row | report_id bigint NOT NULL FK header(id) ON DELETE CASCADE；row_index int NOT NULL CHECK 1..10000；PK(report_id,row_index)；source_query_date date NOT NULL；settlement_date date NULL；currency varchar(10) NULL；buy_settlement numeric(20,0) NULL；sell_settlement numeric(20,0) NULL |
| fubon_realized_report_row | report_id bigint NOT NULL FK header(id) ON DELETE CASCADE；row_index int NOT NULL CHECK 1..10000；PK(report_id,row_index)；source_date date NOT NULL；stock_code varchar(10) NOT NULL；buy_sell varchar(4) NOT NULL CHECK IN ('Buy','Sell')；order_type varchar(10) NOT NULL CHECK IN ('Stock','Margin','Short','DayTrade','SBL')；filled_qty bigint NOT NULL CHECK 1..9999999999；filled_price numeric(30,10) NOT NULL CHECK >0；provider_net_pnl numeric(20,0) NOT NULL |

  settlement另CHECK：settlement_date/currency/buy/sell四欄全NULL，或四欄皆非NULL、currency='TWD'、settlement_date≥source_query_date、buy≤0、sell≥0。price DB型別較寬不放寬來源precision≤20/scale≤10。writer typed saveSettlement/saveRealized固定kind並驗child對應，不能收caller自選reportId/kind。所有child lookup/delete均由顯式owner/kind header取得reportId，不重存owner/email/帳號/fingerprint/queryDate/rowCount/sum，也不加snapshot/deposit/ledger/fill FK。queryDate由observedAt的台北日衍生；status及同列交割合計只在GET時從已存值產生。rowIndex只本份報表定位，不是經濟事件鍵。

- [ ] **406.8 原子replacement與遲到fence。** writer的真Spring REQUIRES_NEW交易先app_user FOR UPDATE＋fresh owner，再該owner/kind header FOR UPDATE；無header時在相同owner鎖下插入，unique配合跨程序序列化，不鎖整表。兩支job分別保存自己kind，不假稱兩API同時觀測。HTTP不能在DB鎖內。收到不可變batch後完整驗source/date/decimal/能力，再於鎖後重驗owner與時間；排序為schema順序的來源欄位，date自然順序、文字固定比較、qty/decimal數值比較、NULL在前，decimal尾零忽略，保留duplicate數量。local名稱/rowIndex/observedAt不在來源內容比較中。按排序後順序分配1..N。

| 比較結果 | DB／結果 |
|---|---|
| 首次合法 | 寫header＋完整rows，SAVED |
| incoming observedAt較舊 | STALE_OBSERVATION，零mutation |
| 同時但canonical multiset不同 | OBSERVATION_CONFLICT，零mutation |
| 同時同內容 | NO_CHANGE，零mutation |
| 較新且同內容 | 只更新header observedAt，SAVED |
| 較新且不同 | 同交易delete該report children、insert完整新rows、更新header，SAVED |

  valid空報表可以替換為0row，但不改任何資產。來源或schema失敗保留最近成功header/children；第N列insert、flush、deferred commit failure均整批rollback。回SAVED前proxy commit必完成；outer ambient rollback不應讓Redis/報表假稱未提交，本流程不寫Redis。沒有歷史輪詢累積副本，舊完成不得覆蓋新。

- [ ] **406.9 manual response與counters誠實。** 保留兩支現有manual路由、token、dryRun與原欄位/enum值，新增outcome SOURCE_REPORT_SAVED／SOURCE_REPORT_UNCHANGED／SOURCE_REPORT_REJECTED／ROLLED_BACK，以及typed reportStatus、int reportRowCount、nullable Instant reportObservedAt。settlement payableAmount/receivableAmount始終null；realized insertedCount/alreadyRepresentedCount/skippedNameUnresolvedCount始終0，原rowCount可描述驗證來源行數，不能當已入帳。原SUCCESS不由此report流程回傳。

| 場景 | outcome；reportStatus；reportRowCount；reportObservedAt |
|---|---|
| gate/來源失敗 | 既有安全outcome；NOT_SAVED；0；null |
| 合法dryRun | DRY_RUN；NOT_SAVED；0；null |
| 真commit新觀測 | SOURCE_REPORT_SAVED；SAVED；committed N；committed observedAt |
| 同時同內容 | SOURCE_REPORT_UNCHANGED；UNCHANGED；existing N；existing observedAt |
| 舊/衝突觀測 | SOURCE_REPORT_REJECTED；REJECTED；0；null，reason=STALE_OBSERVATION/OBSERVATION_CONFLICT |
| writer/flush/commit失敗 | ROLLED_BACK；NOT_SAVED；0；null，sanitized reason |

  各Java獨立outcome counters同步新enum，SAVED只在真commit後增加，UNCHANGED分別計數。Python原13個全域counter不新增任意鍵。不回accountFingerprint、owner、明細或原SDK message。

- [ ] **406.10 專用owner本人純讀，寫/讀權限分離。** 新business `GET /api/brokers/fubon/accounting-reports`，無owner/email/account/reportId selector，未知query純parser回400。controller只委派，採既有verified caller tenant；read授權只接受FUBON_SYNC_OWNER_EMAIL對應既存ACTIVE owner本人，不要求仍ADMIN、不要求ADMIN_EMAIL同人，也不要求global/兩sync flags/READY。缺/非法設定、owner不存在/停用、caller不同含另一ADMIN均403通用FORBIDDEN，零report查詢；未登入沿既有security。不能使用configured-admin no-tenant公開bootstrap，不新增9090/OpenAPI/gateway/Tailscale路由。

- [ ] **406.11 BFF專頁阻擋代看，不能只信effective owner。** 新 `GET /api/bff/fubon-accounting-reports` 放 `bff/fubonaccountingreports/`，controller只把HTTP/auth輸入委派page service。service從可信OIDC principal以既有BffUser.fromPrincipal取actual id，與Reactor TenantIdentity effective id比較；缺失或不同一律403／零business report request。B代看A、A代看B都拒絕，A本人正常；不能以X-User-Id/query/cookie字面充當actual id，不新增全域TenantIdentity欄位或修改其他頁代看。只有相等才透過本頁internal adapter讀business，business再驗dedicated owner。對新business report route的可達BFF forwarding也須靜態確認不會繞過本頁guard；不得引入裸passthrough或將私人GET掛既有公開manifest。驗證用隔離授權fixture，不能live掃路由。

- [ ] **406.12 同一MVCC讀回、local bulk與有限transport。** read service以獨立Spring proxy的readOnly=true／REQUIRES_NEW／REPEATABLE_READ在同一PG snapshot核對fresh owner、查兩header與所有child並組DTO，不能讓OSIV/lazy reader跑到外面、不能READ_COMMITTED分次拼出新舊混搭、不FOR UPDATE、不寫入。名稱用小型bulk local-only port，最多10,000唯一代碼、每批≤1,000，不逐列N+1、不vendor補字；缺名稱null，UI顯示code。SDK/config HTTP/scheduler/writer/cache mutation全部0。syncDisabled只取本地非秘密global及該kind flag。BFF只有此page adapter用10秒總deadline、讀取中16MiB上限，不改global codec；超限/transport/timeout/schema回502通用REPORT_UNAVAILABLE、零部分rows，不帶URL/HTML/exception。正確403仍403，不能改成ACCOUNT_PENDING。

- [ ] **406.13 固定DTO與只render頁面。** wire shape如下，各object永遠存在、list不為null；日期ISO、Instant正常ISO，BigDecimal由canonical plain-string serializer輸出JSON字串，zero為"0"，不使用exponent。核心／傳輸DTO保留BigDecimal，不為前端改成String；無proceeds/investmentCost/ROI/owner/email/account/fingerprint/DB reportId。兩kind independent timestamp不冒稱同次。

```text
{
  settlement: {
    scope: SOURCE_REPORT_ONLY, financialPosting: NOT_APPLIED,
    syncDisabled: boolean, observedAt: Instant|null, queryDate: LocalDate|null,
    rowCount: int,
    rows: [{rowIndex:int, status:AVAILABLE|NO_DATA_OBSERVED,
      sourceQueryDate:LocalDate, settlementDate:LocalDate|null, currency:TWD|null,
      buySettlement:BigDecimal|null, sellSettlement:BigDecimal|null,
      totalSettlementAmount:BigDecimal|null}]
  },
  realizedGain: {
    scope: SOURCE_REPORT_ONLY, financialPosting: NOT_APPLIED,
    syncDisabled: boolean, observedAt: Instant|null, queryDate: LocalDate|null,
    rowCount: int,
    rows: [{rowIndex:int, sourceDate:LocalDate, stockCode:string, stockName:string|null,
      buySell:Buy|Sell, orderType:Stock|Margin|Short|DayTrade|SBL,
      filledQty:long, filledPrice:BigDecimal, providerNetPnl:BigDecimal, currency:null}]
  }
}
```

  queryDate僅從observedAt台北日產生，rowCount含None佔位/duplicates；交割合計只為該行buy+sell、None時null，不存DB。syncDisabled分別為!globalEnabled||!kindEnabled，不代表連線驗證成功。無header為observedAt/queryDate=null且rows=[]「尚未同步」；有header/0rows是「最近一次來源報表沒有資料」；None行保留sourceQueryDate且金額留白，不當0。

  新 `/fubon-accounting-reports` route name FubonAccountingReports/title富邦帳務報表，導覽放既有資產／交易群組尾端，不放公開資訊或系統API目錄，不移舊route/icon/order。兩獨立表格顯示來源日、觀測時間、原種類/量價/來源金額，清楚標示「未計入本系統在途款／已實現損益；來源缺成本與淨收款不推算」。不顯示年度總计/ROI/完整未交割或已入銀行聲明，不加入既有RealizedGainView合計；無sync/import/edit/delete/trade/export按鈕。reload只有GET，loading/empty/403/502分開，cancel不toast、latest request generation才改rows/loading/error，unmount清除本頁資料與取消工作；不得持久化到localStorage。

- [ ] **406.14 目錄與排程完整驗收後更新。** `/fubon-api`依然靜態、無私人資料與IO，httpEndpoint仍是既有Python read route，consumer補新report GET/頁面及「僅限已設定的專用同步帳戶、來源報表同步、不自動入帳」。只有source→DTO→PG→本人GET→UI完整成功驗收後，兩項connected=true，使52/15/37變52/17/35。JOBS兩項改報表保存描述但仍原四cron，合計64=business27+external37。不能把原Task394／395入帳打勾或刪原Claude branches。

- [ ] **406.15 規劃新增驗收矩陣。** 以下是規劃新增測試，不是現成通過結果：fake SDK官方Result/同次帳戶/明確selector/日期/None/零/負值、完整五種種類、profit/loss矛盾、同值兩筆/不同PnL、stock/price/qty邊界、2MiB與10k界線、query rollover/未來/微秒與不整除1000奈秒；每次只呼正確唯讀SDK方法並共用真dispatch budget。另以fake clock/latch令A先取得SDK來源後暫停normalization、B新來源先保存、A再完成，必須保留capture原時點並拒絕A覆蓋B。真PG first-save/replay/reorder/duplicate、同時衝突、old/new完成順序、較新空報表、兩owner、owner角色/狀態/email變動、lock timeout、insert第N列/flush/deferred commit failure/outer rollback，clear persistence context後精確header/children讀回。兩連線控制replacement與GET交錯，REPEATABLE_READ讀兩kind始終是該snapshot的完整header/children；同時測local name缺失及bulk查詢次數上限。對asset_snapshot/bank_deposit/stock_holding/asset_transaction/realized_gain前後摘要全部相同，不能只驗新表有行。

  BFF/backend測A本人成功、另一ADMIN403、B代看A與A代看B在BFF就403且零business report request；principal/effective缺失failclosed；A在flags=false/ADMIN_EMAIL改B/失去ADMIN但仍ACTIVE時仍可讀舊資料，B仍不能讀，A停用則拒絕。DTO日期/decimal/null/dup/status及16MiB/timeout/body cancellation、無來源I/O/寫入、未知query純parser、catalog無個資都需驗。正常有資料browser只用獨立fixture stack/DB，不能往正式owner DB灌假資料。主stack僅安全GET/UI與provenance驗證，disabled不冒充真人帳務成功。

## 驗證

先完整spec獨立審查再開始code；下列從feature worktree根目錄執行。所有normal-path資料來自隔離fake/真PostgreSQL，無真人券商。

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
export DOCKER_HOST=unix:///Users/steven/.docker/run/docker.sock
bash scripts/spec-check.sh
PYTHONPATH=fubon-broker-service/src /tmp/fubon-venv-t395/bin/python -m pytest -q fubon-broker-service/tests
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml clean test -DextraArgLine=-Dapi.version=1.44
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml clean test -DextraArgLine=-Dapi.version=1.44
/Users/steven/.nvm/versions/node/v22.21.0/bin/node /Users/steven/.nvm/versions/node/v22.21.0/lib/node_modules/npm/bin/npm-cli.js --prefix frontend test
/Users/steven/.nvm/versions/node/v22.21.0/bin/node /Users/steven/.nvm/versions/node/v22.21.0/lib/node_modules/npm/bin/npm-cli.js --prefix frontend run build
bash scripts/tests/schema-sql-drift-test.sh
git diff --check
```

先在隔離PG16實跑新增Liquibase、空DB到head與升級migration、typed constraint/unique/rollback；依db/schema.sql標頭的既有命令重產正式schema快照，再跑drift-test，確認95表與13 public routes。不改claude tasks舊完成狀態來繞過門檻。Python另以linux/amd64 test target/network none跑full suite，同套結果不重複加總。

完整production diff經獨立架構審查後，由協調者依run-stack針對business-services、bff、frontend、fubon-broker-service build/recreate，驗實際image中source與測試一致；不得docker down/刪volume/覆蓋main.env。唯一環境修改是405已授權的FUBON_SYNC_OWNER_EMAIL，FUBON_ENABLED=false與所有既有flags/secrets保持。隔離fixture browser驗兩份完整來源報表；main登入本人GET可顯示尚未同步/既有真報表，不做同步POST、不注入fixture。

驗收只限明確GET白名單（既有health/config、首頁/既有公開commodity/calendar純讀與authenticated catalog/新報表頁），不live方法矩陣/alias/security探測、重播已拒絕probe或真人SDK。全部通過後協調者commit→main no-ff merge→push，再main重建與純讀驗證；最終catalog52/17/35、JOBS64。原394/395財務入帳仍未完成時保留原Claude refs。

## 完成報告

尚未實作；checkbox全部未勾選。完成時回填新增migration/95表證據、source-report正常存取與本人隔離、原五類財務表無mutation、全量tests與真PG競態、獨立審查、fixture/main browser差異、flags未啟用、merge/push SHA及image來源；明示未做真人SDK，原Task394／395在途款／realized_gain入帳仍待來源mapping，不宣稱已補造成本或淨收款。
