# [t487] 盤中分鐘 K 確認與一年保留

**對應 Requirements:** Requirement 185（已完成5分K與1分輔助的SHORT買進確認，分鐘資料固定一年）
**前置任務:** t461（display-only分鐘指標）、t466（官方minutes回補）、t475（SMA gate）
**Liquibase changeset:** v1.148.0-fubon-intraday-candle-capture.sql

## 背景

已存約385萬分鐘K使compressed backup超過200MB，但现有producer只13:40抓當日，不供盤中；雷達尚未讀原始分鐘K。使用者明确要求納入判斷與一年保留，一併要求真正SRPP引擎（另由t488交付）。不得宣稱盤中數據已證實更準，不把回補observedAt偽裝歷史當時可用capture。

## 要做什麼

使用者要求把富邦盤中 K 線納入今日交易雷達判斷，並將分鐘資料固定保留一個曆年。先以已完成 5 分 K 做短線買進確認、1 分 K 做輔助；不宣稱未證實的準確率／報酬改善。這是明確授權的保守規則變更，取代 Task461「盤中指標僅顯示」之中新增分鐘 K 確認的限制；原有盤中 KD/MACD/布林 child 仍僅顯示。

- [ ] **唯一分鐘事實與完成截止。** 僅使用既有富邦 normalized 1m OHLCV，背景producer每分鐘、每輪最多5檔、30碼 fair cursor；共用SDK配額與ready/feature/台股交易日/09:00≤time<13:30 gates。以SDK呼叫前 requestStartedAt 排除當前／尾棒，僅接納 candleAt+60秒≤requestStartedAt−60秒、且不是response最後一棒；已知13:30拍賣棒不進普通5m聚合。不得以returned observedAt或AVAILABLE代替完成證據。同步修復Python normalized adapter與Java parser：當日average僅驗正值；wire最多271根，允許13:30但仍排除其判斷用途。每個成功capture的completed facts与latest capture metadata同一transaction；同鍵異hash不覆寫、整批rollback並保存CONFLICT receipt，最新失敗不可沿用舊AVAILABLE。
- [ ] **純讀邊界。** external新container-only GET `/internal/market-data/intraday-candles/batch-read`，business透過port與內部client批讀，不直連broker／vendor、不刷新／寫cache／DB，公共9090不新增路徑。每請求最多30碼，每碼最多30根同日完成分鐘；root及per-code exact wire與失敗語義按本任務契約。未知日曆、missing／expired／conflict fail closed。
- [ ] **短線確認。** 交易日盤中台股才適用；以09:00對齊兩個相鄰完整5m區間，各必須有5根exact consecutive已完成1m。request capture及latest minute end≤420秒且≤決策時間。5m OHLCV為本地從富邦分鐘事實聚合、非富邦官方5m技術指標。latest5.close>latest5.open且>prior5.close、latest5.volume≥prior5.volume且prior5.volume>0，latest1.close≥previous1.close才CONFIRMED；兩latest minute固定取lastCompletedAt−60秒起點與其緊鄰前一分鐘，缺棒直接UNAVAILABLE。僅將SHORT BUY_CANDIDATE／ADD_CANDIDATE／TRIAL_BUY在未確認或資料缺失時降為未持有WATCH／已持有HOLD，保留原候選、原始分數及原證據閘門；不生成SELL，不改MEDIUM/SWING。US或明確非session為NOT_APPLICABLE；未知日曆不是N/A。開盤尚不足兩個完整5m時為UNAVAILABLE/INSUFFICIENT_COMPLETED_BARS。full/list/export/notification由同一DecisionCore套用，SMA只可再降級。規則版本升TW_RULES_V22，notification版本重建baseline不發首次transition郵件。
- [ ] **可見證據。** full與compact list同一nullable typed `intradayCandleConfirmation` metadata，status=CONFIRMED|WAIT|UNAVAILABLE|NOT_APPLICABLE、reason、sourceDate、observedAt、lastCompletedAt、fiveMinuteAt、oneMinuteAt、aggregationSource（LOCAL_AGGREGATED_FUBON_1M或null）；不複製即時價。SHORT reasons/risks揭露確認／等待與來源時間，其他horizon不複製其風險。同步BFF strict schema、OpenAPI、三份Swagger鏡像與contract tests。
- [ ] **固定一年與回灌防線。** TaipeiToday.minusYears(1)為inclusive retention floor；只刪 `fubon_intraday_candle.source_date<floor` 與過期capture metadata，不刪日K、技術歷史、campaign稽核收據、帳務或雲端備份。啟動及每日00:10進行單表bounded批次清理（每transaction≤10000 rows、statement≤10秒、每次≤120秒）；未完成須留下可重試狀態並由後續排程續作，讀取永遠排除expired。所有minute write paths在transaction重新算floor、跳過expired；backfill planner/resume同樣限制minute但daily十年保持原設定。同步排程登錄表。首次部署須完成既有過期資料清理並回讀驗證；常規VACUUM可回收重用空間，不跑VACUUM FULL，不變更舊backup objects。
- [ ] **驗證與效益證據。** 精確單元/集成測試完整棒、缺分鐘、未完成／未來／昨日／stale／conflict、五分對齊、零量與三軌action保守性、所有projection同源、日曆unknown與閏年retention。固定輸入A/B重播同一純policy，記錄改變候選、等待、漏失與已知來源限制；若使用回補分鐘而無當時capture證據，明示為回顧source replay，不冒充point-in-time績效回測。沒有扣費／滑價且獨立歷史期的證據時不得宣稱更準。測試、獨立architecture review、Docker rebuilt/recreated實際API與DB驗收後no-ff落main。



新增 external-owned `FubonIntradayCandleSyncService`（producer）／`FubonIntradayCandleRepository`（唯一minute/capture資料存取）／`FubonIntradayCandleReadService`（純batch read）與controller；business新增 `RadarIntradayCandlePort`／内部client及純 `RadarIntradayCandlePolicy`，在既有evidence gate之後、DecisionCore形成之前加SHORT-only保守確認，既有SMA projection不得恢復降級。Repository可與既有minute history store共享persistence，但Service不得直接組SQL。原始1m仍唯一存 `fubon_intraday_candle`，新 `fubon_intraday_candle_capture` 僅latest采集metadata，不重複OHLCV。migration v1.148.0建立capture table，PK(stock_code,market,provider)，stock_code varchar(20)、market varchar(20)、provider varchar(32)，source_date date、request_started_at/captured_at/latest_completed_at timestamptz（後者nullable），status varchar(20)、reason varchar(80) nullable；protocol checks限AVAILABLE/UNAVAILABLE/CONFLICT，台股/FUBON_SDK identity，AVAILABLE須有完成時間及request證據。更新以captured_at嚴格較新為界，不用觀察時間覆寫既有fact。

內部GET只接受stockCodes逗號分隔unique 1–30合法台股碼（排除0000）、tradingDate ISO日期、asOf UTC instant；不能晚於server now、不允許tradingDate非asOf台北日期。400 invalid，pure read依asOf保守選receipt；未準備／missing per-code為UNAVAILABLE，資料讀失敗不保留舊available。response封閉root `{schemaVersion:1,tradingDate,asOf,stocks:[...]}`，每stock精確 `{stockCode,market,provider,status,reason,sourceDate,requestStartedAt,capturedAt,lastCompletedAt,candles}`，status AVAILABLE|STALE|UNAVAILABLE|CONFLICT；無有效receipt時時間nullable，sourceDate為requested date，candles為空。AVAILABLE candles≤30、升序唯一、同日普通時段，欄位exact `{candleAt,open,high,low,close,volume}`，price為positive canonical decimal string（precision≤20/scale≤10），volume nonnegative JSON整數且long bounds；原1munits保持來源不與其他來源混算。lastCompletedAt為latest minute end；requestStartedAt/capturedAt≤asOf，latest完成≤requestStartedAt−60秒。STALE期限420秒。receipt與facts必須由單一一致DB snapshot／單一查詢讀取；每筆fact的immutable首次observed_at≤receipt.capturedAt≤asOf，observed_at由來源回應的實際取得時間寫入且samehash重觀察不得刷新。沒有當時存在的有效latest receipt就UNAVAILABLE，不重建歷史receipt、不使用晚取得的早期棒。

producer最多5碼/分鐘，cache cursor僅排程使用不得被GET觸發；source request budget及stop理由沿用既有SDK。完成窗口由requestStartedAt−60秒決定並排除response最後棒與13:30 auction。CONFLICT整批rollback facts，但獨立失敗receipt transaction令後續read不可沿用舊capture。samehash重觀察只更新capture receipt。latest30完成分鐘供兩個完整5m（09:00格點，必須10根exact consecutive），5m聚合不持久化。由lastCompletedAt在09:00格點向下取最近完整5m桶終點，只驗該桶及緊鄰前桶，不得最新桶缺棒後向前搜尋。輔助1m固定最新completed起點及candleAt−60秒，缺棒即UNAVAILABLE。fiveMinuteAt是最新5m桶終點、oneMinuteAt是最新1m棒終點。短線CONFIRMED條件為最新5m紅棒/較前棒上升、量不縮且前量>0、最新完成1m close≥前1m close；其他為WAIT或資料不完整UNAVAILABLE。gate只延後short買進候選，理由與typedmetadata同源；MEDIUM/SWING及候選原始分數不變，聚合是本地來源明示。

一年floor每次依台北execution clock重新計算，不依campaign原始from、UTC日期或365天。startup/daily清理與所有minutewriter/backfillresume共用floor，不改dailyhistory深度，不刪遠端backup。retention采用bounded小transaction避免長鎖，timestamp/floor穩定於每輪，讀側也排除expired。新增／變更@Scheduled同步BFF排程catalog。公共23method/path不新增；OpenAPI僅新增確認metadata及行為說明，generated Swagger三份位元組一致。API保留candidate/originalscore、動作較保守，不聲稱績效改善。

- [ ] 487.1 external producer/repository/capture migration/pure read及Python normalized adapter與Java parser正值average/271收盤棒wire同步修復。
- [ ] 487.2 business共享純policy、三軌保守gate、所有projection與metadata、RULE_VERSION及通知baseline。
- [ ] 487.3 一年floor、所有minute writers/backfill/resume防回灌、啟動+00:10bounded清理及排程頁同步。
- [ ] 487.4 focused tests、固定input A/B/source replay診斷及限制、BFF/OpenAPI/Swagger contract、獨立architecture review、實際Docker驗收。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test
python3 -m pytest fubon-broker-service/tests -q
ruby scripts/render-9090-openapi-docs.rb --check
# run-stack需以權威main .env同步的feature context驗收，再落main後從main重建
docker compose -p asset-management build business-services external-materials-service fubon-broker-service bff
docker compose -p asset-management up -d --no-deps --force-recreate business-services external-materials-service fubon-broker-service bff
bash scripts/tests/schema-sql-drift-test.sh
```

migration后依db/schema.sql檔頭重產完整schema並檢查drift。實際安全GET檢查public radar list/detail同一confirmation status；休日只能證明NOT_APPLICABLE，不冒充真盤中CONFIRMED。DB回讀source_date floor/剩餘行數與latestcapture確認一年界線；杜絕live injection或broker write。不可觸發備份上傳作smoke；若要比較大小只做新的本機唯讀dump，不upload/delete舊檔。

## 完成報告

實作與 feature Docker 驗收完成；本報告記錄收尾提交前的驗收狀態，兩個 repo 的 no-ff merge／push 及 main 再部署由同次工作接續完成，Git history 為 landing 證據。

- 新增 external 分鐘 producer／atomic capture repository／pure batch read／一年保留服務、business 共用 `RadarIntradayCandlePolicy` 與內部 client；同源套用 full／compact／export，規則版本 `TW_RULES_V22`。Python 與 Java normalized adapter 同步接受 271 棒及 13:30 wire，確認仍只取普通時段完成棒。
- external 全套 847 tests、Python 全套 844 tests 通過；radar backend focused 148、BFF focused 128 通過，最後秒數對齊修正的 BFF 5 與 Java OpenAPI 4 重驗通過。包含真 PostgreSQL immutable conflict／asOf snapshot／10001 筆 bounded prune／閏年／writer 防回灌證據。
- 獨立 architecture review 最終 critical 0、major 0、minor 0。固定 8 組合成 A/B 輸入中，5 組短線買進延後，中期／波段不變；[A/B 證據](../../docs/validation/task487-intraday-candle-ab-replay.json) 明示 `accuracyImprovementProven=false`，未驗收益、費用、滑價或獨立歷史績效。
- 四個受影響服務已 image rebuild＋container recreate，148／149 migration 完成。台北 2026-10-10 的保留界線為 2025-10-10；分鐘事實由 3,846,788 筆減至 1,177,636 筆，清除 2,669,152 筆，過期 minute／capture 均為 0。112 張 schema 全文 drift 通過。
- 新本機唯讀 custom／gzip9 dump 為 85,885,825 bytes（81.91 MiB），相對既有 2026-10-07 歷史備份 217,551,460 bytes（207.47 MiB）縮小 60.52%；兩份格式相同，但不是相同時點輸入的前後 dump。`pg_restore --list` 可讀。未刪除舊 pending dump 或雲端備份，亦未觸發備份上傳。
- 實際休日 API：內部無 token 401／非法請求 400，合法 read 200／`MARKET_CLOSED`；雷達 list/detail 同為 V22、`NOT_APPLICABLE / OUTSIDE_TRADING_SESSION`。這只證明休市分支，盤中確認正例由固定輸入測試驗證。完整 runtime 證據見 [驗收報告](../../docs/validation/task487-488-runtime.md)。

追加必測：Python 271棒、13:30拍賣棒wire接受與決策排除、正值累積average不強限當棒高低；晚取得早期fact不得出現在舊asOf、一致snapshot不混兩次capture、最新桶缺一分鐘不得回退、兩輔助分鐘必exact consecutive。
