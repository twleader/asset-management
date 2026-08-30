# [t398] 個股技術指標查詢排程——KD／MACD／布林通道寫既有 Redis 市場快取

**對應 Requirements:** Requirement 133（交易日收盤後查詢雷達台股的富邦日線技術指標，存入既有 Redis 的獨立來源快取並提供內部唯讀讀回；不新增 SQL 表或欄位）
**前置任務:** t396 external config-state、StockSourceQuery；既有 Redis 連線與 internal token 模式
**Liquibase changeset:** 無；不建立 stock_technical_indicator，不新增 v1.121.0 或其他 migration，不修改 db/schema.sql

## 背景

已盤點既有 schema、`TechnicalIndicatorService`、Redis writer：本地技術指標是從 stock_price_history 現算，沒有通用 SQL 指標儲存；stock_alert 的上次觸發 KD 是告警歷史狀態，TradingRadar snapshot 是決策證據，都不能挪用。使用者限制是「不另開新表格、新欄位」，並未禁止在**既有 Redis 資料庫**建立合理的市場 cache keys。本任務採獨立 `FUBON_SDK` technical cache；它具有 TTL，是可重建市場快取，**不宣稱 SQL 永續存檔**。原文件「使用者另外批准新表」沒有依據，明確作廢。

已核實官方 [KDJ](https://www.fbs.com.tw/TradeAPI/docs/market-data/http-api/technical/kdj.txt)、[MACD](https://www.fbs.com.tw/TradeAPI/docs/market-data/http-api/technical/macd.txt)、[Bbands](https://www.fbs.com.tw/TradeAPI/docs/market-data/http-api/technical/bbands.txt)：三支均需 symbol/from/to/timeframe 與各自參數，回應 echo symbol/params，data 為有 date 的陣列。KDJ 值為 k/d/j；MACD 為 macdLine/signalLine，無官方 histogram 欄位；BB 為 upper/middle/lower。禁止丟掉 symbol/date、把 scheduler 今天填成來源日、把 MACD 不同定義名混用。

本輪固定交易日 13:40（設計預設，非聲稱使用者指定）；timeframe=D、KDJ(9,3,3)、MACD(12,26,9)、BB(period=20)，query window `[queryDate.minusDays(120),queryDate]`。這些參數是查詢選擇，不代表富邦算法與本地 TechnicalIndicatorService 完全相同。本任務不改本地計算、雷達評分或其既有 consumer。

## 要做什麼

- [x] 398.1 **服務歸屬與 flag。** 新增 `FUBON_TECHNICAL_INDICATOR_SYNC_ENABLED=false`，只在 external-materials-service 建 scheduler/service/cache repository/writer/readback controller，Compose 轉接所需設定；保留既有 flags。不新增 backend entity/JPA repository，不讓 external 反向呼叫 business-service 寫入。Asia/Taipei cron `0 40 13 * * MON-FRI`，依序 feature flag → global/config READY → 既有日曆 known true → 台北時間至少13:40 → inFlight；unknown/nontrading 不外呼。排程、manual 與 dry-run 共用13:40 gate，較早回 `BEFORE_CLOSE`、零 SDK 外呼及零 Redis 寫入；每檔外呼與提交前重驗同一台北日，跨日停止。禁止盤中先保存當日未完成的 D 指標而阻擋收盤值。
  `StockSourceQuery.collectTwRadarCodes` 的各 owner 最新台股持股∪台股 alerts 去 0000 是唯一股票範圍；empty 回 NO_SYMBOLS、不打 SDK。每次逐檔前及寫入前確認仍在本輪／目前有效雷達集合；不使用 collectAllStockCodes，不替缺資料個股查別來源或別股票。

- [x] 398.2 **adapter 使用官方完整參數與逐組結果。** token-protected `POST /internal/market-data/technical-indicators/read` body `{"symbol":"...","from":"YYYY-MM-DD","to":"YYYY-MM-DD"}`，驗證合法非 0000 台股與上述固定有界查詢窗；parameters 固定於 adapter，不接受任意 method、account、timeframe 或外部 URL。依序唯讀呼叫 `.technical.kdj(**{symbol,from,to,timeframe:"D",rPeriod:9,kPeriod:3,dPeriod:3})`、`.macd(**{...,fast:12,slow:26,signal:9})`、`.bb(**{...,period:20})`；Python 以 dict 展開處理保留字 from。使用既有 marketdata session/timeout/budget，不套用 accounting 方法、不中途下單。保守共用歷史行情上限 60 calls/min，429 暫停剩餘外呼，不密集重試；每呼叫有 deadline、單次排程最長 30 分鐘，未處理檔明確計入未完成，而非成功。
  回 `symbol,market="台股",provider="FUBON_SDK",queryFrom,queryTo,observedAt,kdj,macd,bb`；每組為 `status,reason,parameters,sourceDate,sourceTimestamp,payload`，不可把任一失敗轉成全組 null 又稱成功。status 為 AVAILABLE／NO_DATA／UNAVAILABLE／SCHEMA_INVALID；sourceTimestamp 官方沒提供則 null。單組 fail 只影響該組，其他組照常；global config/auth 失敗仍回既有整體錯誤，不冒稱股票沒有指標。

- [x] 398.3 **逐組驗 identity、參數、日期與值，整組不可拼接。** 每支官方 response 的 symbol、from/to、timeframe、各週期參數必須與請求精確相符；BB period 官方表格 string/範例 number 不一致，僅容許 exact integer 或其 canonical integer 字串，正規化後必須 20。data 必為陣列；各 date 嚴格 ISO 且在 query window、不得晚於 queryTo／當日，不用 list 最後一列臆測最新，明確取最大合格來源日。重複日期／同日期不同值、日期缺漏、其他股票回應使該組 SCHEMA_INVALID；不可丟錯列後挑一個看似可用值。
  每組在選定 sourceDate 的 required payload 全部可解析才 AVAILABLE：KDJ k/d/j、MACD macdLine/signalLine、BB upper/middle/lower。數值用技術指標專用 finite Decimal→canonical 字串（precision<=38、scale<=18，拒 bool/NaN/Infinity），保留負值與零；不套價格必須正值或財務 scale<=10 的 parser。BB 檢查 upper>=middle>=lower，不把可能為負的下軌強制歸零；J/MACD 不 clamp。SDK JSON 優先 Decimal 解碼；若 SDK 已給 finite float，Decimal(str(value)) 僅保留 SDK 可提供精度，不能聲稱恢復原始未捨入數值。超界該組不可用，不猜捨入修復。
  不自行加 histogram，不以本地 KD/MACD 或其他日期補值。三組 sourceDate 可以不同，回應及 cache 各自明示；即使同一請求完成，也不能造一個共同今日 tradeDate。只有觀測時間 observedAt 是本地時間，不作 source revision 或來源發佈時間。

- [x] 398.4 **既有 Redis 的獨立 cache schema，external 是唯一 writer。** key 固定 `fubon:technical:tw:{stockCode}:D:v1`（code 嚴格校驗）；value 是 schemaVersion=1、symbol/market/provider、固定參數 manifest、以及 kdj/macd/bb 各組 record。每組保存整組 payload、sourceDate、sourceTimestamp(nullable)、parameters、contentHash、首次 observedAt、expiresAt，以及獨立的 lastAttemptStatus/reason/observedAt。所有物件使用 immutable record；來源值與最近嘗試狀態不能混成同一新鮮度標記。
  不共用或覆寫 price/quoteDetail、本地 indicator、owner radar snapshot、alert trigger/cooldown keys；不寫任何 SQL 表。Redis key 的 v1 是 cache 格式版本，不是資料庫版號。參數 manifest 改動須另版本或重建此獨立 key，不能無聲把不同算法結果寫入同一身分。

- [x] 398.5 **原子逐組 freshness fence 與有限生命週期。** `FubonTechnicalIndicatorCacheWriter` 以一支專用小型 Lua/等價 Redis transaction 原子比較完整 key 中每組，不能 Java read→merge→SET 產生競態。這是技術快取自己的契約，不更改 price writer 或 book comparator。
  - 候選 AVAILABLE 且 sourceDate 較該組既有日期新，才整組替換 payload/日期/參數；較舊拒絕。沒有舊值且通過來源驗證可新增，支援正常成功路徑。sourceDate 相同且 canonical payload/parameters hash 相同為冪等，保留首次觀測與到期時間；相同日不同值、又沒有可信 source timestamp/revision 可排序時 `CONFLICT_NO_SOURCE_REVISION`，保留舊值并揭露 conflict，**不得以較晚 observedAt 判新**。目前官方三支都沒有 sourceTimestamp，故不得造時間突破此規則。
  - 一組 NO_DATA/UNAVAILABLE/SCHEMA_INVALID 只更新該組 recent attempt 診斷，不抹掉其他有效組、不把舊組改成今日。整組 sourceDate/values/status 必須保持一致；不能從新 K＋舊 D 合成一組。
  - 各有效組 expiresAt 固定為來源日台北零時＋7 天；來源已過此期限不可新增。key TTL 取仍有效組的最晚 expiresAt（最多 7 天），用 Redis server time 算餘量；沒有任何有效組而只有 failure 診斷則最多 15 分鐘。等值重跑／失敗觀測不得延長舊有效組 expiresAt。讀者逐組檢查期限，某組先到期即不再回其 payload，即使其他組使同 key 暫存更久。
  - 重啟、Redis eviction/故障可使 cache miss，下一次排程重新取得；不回補假 SQL 歷史、不把 cache miss 當零值。malformed existing cache 回 CORRUPT_CACHE，不混入不可信值。所有 write outcomes／TTL 的驗證需用真 Redis，不只 mock。

- [x] 398.6 **純讀 repository 與內部讀回路徑，保留來源差異。** external 的 `FubonTechnicalIndicatorCacheRepository` 為純 Redis reader，查詢不呼叫 SDK、不延 TTL、不回寫或重算；`GET /internal/technical-indicators/fubon-cache?symbol=...` 由獨立 exact-path constant-time token filter 保護，controller 只委派 reader service。驗證 symbol 仍屬 radar 集合，非雷達拒絕；只回該 symbol 的 provider/parameters、各組 sourceDate、有效 payload、lastAttemptStatus/reason、expiresAt，及 AVAILABLE/HISTORICAL/UNAVAILABLE/MISS 狀態。sourceDate 早於本次既有日曆確認的最近應有交易日為 HISTORICAL，不隱瞞成當日；calendar unknown 明示，不能從本機日期造來源日。
  此 readback 是本輪驗收與後續 consumer 的穩定內部介面，不新增 public/BFF/frontend/9090 路由、不修改現有 TechnicalIndicatorService API 或雷達評分。UI/既有 consumer 不會自動改用富邦算法；報告必須分清查詢快取完成與未包含的 UI 整合。

- [x] 398.7 **手動、狀態與列表。** `POST /internal/technical-indicators/fubon-sync?dryRun=true|false` 預設 true，外部材料服務 exact-path token filter；dry-run 只取得/驗證來源，不寫 cache／attempt，回 outcome/reason/processedCount/writtenCount/partialCount/failedCount/skippedCount。operational enum 至少 DISABLED、TECHNICAL_INDICATOR_SYNC_DISABLED、MISCONFIGURED、CALENDAR_UNKNOWN、MARKET_CLOSED、NO_SYMBOLS、DRY_RUN、SUCCESS、PARTIAL、TECHNICAL_INDICATOR_FAILED；逐組 write outcome 含 WRITTEN、UNCHANGED、REJECTED_STALE、CONFLICT_NO_SOURCE_REVISION、CORRUPT_CACHE。全部組失敗不得報 SUCCESS。
  outcome 另包含 `BEFORE_CLOSE`；manual 不可繞過398.1的收盤與同日 gate。JOBS 使用 EXTERNAL 既有行情分類，時間「交易日 13:40」，描述獨立 Redis cache/7 天上限，不寫「新 SQL 表」。API inventory 三個技術項目 httpEndpoint 都為 `POST /internal/market-data/technical-indicators/read`，consumer 補 external sync/readback；connected 在三個正常官方格式都能被解析、寫入且讀回後更新，不以 flag=false 代替驗收。

- [x] 398.8 **契約與真 Redis 驗證。** Python 分別測完整官方參數、echo identity mismatch、out-of-range/future/duplicate dates、各組獨立失敗、negative MACD/J、BB 次序、精度、latest date 排序；不假設三組日期相同。Java 真 Redis 測首次三組成功→內部 readback、部分成功保留各自來源日、同日同內容冪等/不同內容拒絕、較舊拒絕、新日期原子替換、兩writer併發、逐組到期／TTL不被失敗刷新、cache miss/corrupt、非雷達拒绝、dry-run 零寫。保留本地 TechnicalIndicatorService/雷達計算與行情 key 完全不受影響的既有回歸；不得添加 SQL migration 來讓測試通過。

## 驗證

另以固定時鐘驗證交易日13:39的排程/manual/dry-run全部零外呼零寫入，13:40可正常首次保存，之後同來源日異值仍拒絕；跨台北午夜不寫前一輪回應。

```bash
bash scripts/spec-check.sh

docker buildx build --platform linux/amd64 --target test -f fubon-broker-service/Dockerfile fubon-broker-service --load -t asset-fubon-broker-service:test
docker run --rm --platform linux/amd64 asset-fubon-broker-service:test pytest -q

/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

實作後依 run-stack rebuild/recreate adapter、external-materials-service、bff，保留既有旗標設定。以隔離 fake SDK→Redis→token-protected readback 核對 sourceDate、來源參數、數值與 TTL，並核對沒有 SQL schema 變更。來源實機權限／行情樣本不在本次規格核實範圍；官方契約足以先做 offline 正常/拒絕路徑，不能把 disabled/no-data 算功能完成。

## 完成報告

**程式、隔離測試、獨立架構與 feature Docker 驗收已完成；第二個 session 整體仍待394／395來源契約。** Python676項、external700項全部通過。依使用者90秒節拍意圖，採兩個symbol worker，共用每批最多20檔／90秒、相邻dispatch至少3.1秒；每檔KDJ／MACD／BB仍順序呼叫，全部共用Python rolling60 calls/min的保守限制。25檔案例全數處理，第21檔至少在第90秒開始、同時最多2檔；真vendor429停止剩餘外呼，local HISTORY_BUDGET_EXHAUSTED只延後有限一次重試，保留30分鐘期限、取消、同日與雷達資格複驗。官方歷史行情上限60/min不等於已證明技術指標有独立quota，因此不得保證供應商一定回齊；未處理與失敗數明示。

真Redis測試包含各組來源日、負值／精度、相同內容冪等、同日異值拒絕、舊值拒絕、逐組expiry、corrupt、兩writer併發與完整raw byte CAS；純GET在冷日曆下不查vendor、不修復、不延TTL。獨立審查指出read service直接依賴Redis concrete adapter，已改注入既有FubonTechnicalCachePort並重跑700項全綠，獨立審查者已複核。此功能是7天上限的獨立Redis來源快取，不是SQL永續歷史，也沒有改本地TechnicalIndicatorService、UI或雷達算法；未驗真人SDK權限／行情樣本。

共用實機驗收見 [Task386接手更新](t386_fubon_api_documentation_view.md)：四個服務從此feature rebuild/recreate且healthy、class/source雜湊與測試產物相符，內部disabled GET及登入後盤點／排程頁已驗。全域FUBON_ENABLED保持false，新flags保持false，正向資料回寫只在隔離PostgreSQL／Redis測試，未啟用真人券商查詢。這不構成第二個session已完成或已push。
