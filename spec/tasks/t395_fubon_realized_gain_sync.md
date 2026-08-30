# [t395] 已實現損益明細查詢排程——核實交易身分與財務語意後新增既有損益資料

**對應 Requirements:** Requirement 130（每天 08:00／13:45／19:30／22:00 唯讀查詢富邦已實現損益；只將可核實、尚未被既有資料表表達的現股賣出結果新增到 `realized_gain`）
**前置任務:** t393；沿用 configured admin／broker gate、`StockMasterService.resolveNameLocalOnly`；t385 ledger只供候選搜尋，不是不可變來源證據
**Liquibase changeset:** 無；不新增表、欄位、唯一索引或 provenance side table

## 目前核實狀態與實作切片

**原目標保留、財務同步尚未完成。** [官方Go RealizedPnLDetail注意事項](https://www.fbs.com.tw/TradeAPI/docs/trading/library/go/accountManagement/RealizedPnLDetail/) 已明示每成交一列、profit/loss不應同時有值、損益已扣除手續費及交易稅；不再宣稱損益是否含費用完全未知。但Python欄位仍無fill id、逐筆net proceeds或取得成本，date仍標「資料日期」。官方跨語言說明沒有補上這些聯結；毛額減淨損益會把賣出費稅混進取得成本。

本階段只實作395.1–395.3與395.7/395.8的adapter、身分/日期/數字解析、token、唯讀預檢與安全no-write。沒有可靠來源關聯時回`IDENTITY_UNVERIFIED`，即使候選唯一也不開writer；只有獲得可信身分但仍缺淨收款/成本時才回`ACCOUNTING_SEMANTICS_UNVERIFIED`。395.4–395.6的財務mapping/新增保留為待核實設計，**目前不得實作或啟用writer，connected=false、Task395未完成**。受控fixture不能自行充當解除阻礙的證據。

解除阻礙需要：

1. 券商對realized.date與實際filledDate的關係、逐列與filled_no的可驗证聯結；或可重現的完整去識別同次realized/filled_history/官方逐筆對帳，證明一對一身分並規定多筆同價同量的拒絕條件。既有可編輯ledger只供找候選，不能是此證明。
2. 每筆可核實的淨賣出收款與取得成本口徑（含買入費用、賣出費稅/折讓如何處理），至少有含費稅及零損益的完整對帳可核對。只有`netGain`已知時仍不足以取得這兩個值。
3. 若嘗試「某日恰一筆現股賣出＋當日完整settlement」的限定正常子集，必先核实filled_history查詢覆蓋全部對應成交、settlement日範圍與date語意、同selected account及無其他買賣/非現股干擾，且gross、fee、tax、net逐項吻合；不可猜費用分攤、扣兩次費稅或以同值候選宣稱識別已證。本輪未找到足夠來源，故不放行此算法。

取得證據後先同步R130/design/本檔並重新獨立審查，再進入writer正常/rollback/併發驗證。所有證據去識別、無實帳號或secret；不新增schema或修改使用者原要求。兩項財務工作未完成前，第二個session不合併、原Claude branches不刪除。

## 背景

`db/schema.sql` 的 `realized_gain` 只有 id 主鍵與 owner 索引，**沒有經濟事件唯一鍵**。既有 `shares numeric(15,5)`、`sale_price numeric(15,4)`、`proceeds/investment_cost numeric(20,2)`、owner、market、broker 欄位可供候選比對，不能把 `(code,date,shares,price)` 宣稱為真實成交識別。同日同價同量可能是兩筆不同成交；catch `DataIntegrityViolationException` 不會讓不存在的 unique constraint 生效。

[富邦官方已實現損益文件](https://www.fbs.com.tw/TradeAPI/docs/trading/library/python/accountManagement/RealizedPnLDetail/) 已核實回 `Result{is_success,data: List[Realized],message}`；每列必有 date、branch_no、account、stock_no、buy_sell、filled_qty、filled_price、order_type、realized_profit、realized_loss。`order_type` 不是 optional；只支援 Stock/Sell，其他類型不可混入現股損益。官方稱 date 為「資料日期」，沒有成交識別或成本／淨收款欄位。

`filled_price × filled_qty` 是成交毛額，不能只憑代數把它宣稱為已核實的 proceeds，再用 `毛額 − (profit − loss)` 寫成取得成本。既有 t385 ledger.amount 同樣為毛額且 fee／tax 可能 null，不能據此假裝已知淨收款。未核實日期、身分或財務語意的列須整批 no-write；本任務不藉由新增 SQL schema 或猜填金額解決證據不足。

## 要做什麼

- [ ] 395.1 **四個每日 cron 與唯讀邊界。** 新增 `FUBON_REALIZED_GAIN_SYNC_ENABLED=false` 及 business-services Compose 轉接，不改既有部署旗標值。`FubonRealizedGainSyncScheduler` 使用 Asia/Taipei `0 0 8 * * *`、`0 45 13 * * *`、`0 30 19 * * *`、`0 0 22 * * *`，不加交易日曆 gate。feature flag → global/config READY → inFlight；關閉時不查 owner 或 API。只查 configured selected account，不傳入自選帳號。這是帳戶帳務查詢，非逐檔行情查詢；本任務不為已賣出股票呼叫任何外部個股／名稱 API。

- [ ] 395.2 **Python 在同次 accounting 呼叫中核對信封、selected account、日期與交易種類。** 共用既有 accounting lock、5/sec budget、5 秒 timeout、auth-invalid 最多重試一次，取得 response＋selected account＋當次 token 的不可變結果，不在呼叫結束後重新讀 mutable selected account。`is_success is True` 且 data 為 list；每列 branch_no/account 與 selected 都須非空且逐字相同，缺欄不能 fallback 或 None==None。驗證後沿用 token-key HMAC-SHA256(branch:account) 前 24 hex fingerprint，只有 fingerprint 可跨 adapter，raw 身分不可記錄或輸出。
  每列 stock_no 必須是合法台股代碼（非 0000）、buy_sell 必須 Sell、order_type 必須 Stock，缺欄／其他值整批 `RECONCILE_FAILED`。filled_qty 為 1..9,999,999,999 的 exact integer（拒 bool）；filled_price 正有限 canonical decimal；profit/loss 為非負 exact integer，允許兩者皆零，兩者皆非零須標 `ACCOUNTING_SEMANTICS_UNVERIFIED`，不得自稱已核實單一淨成交。date 嚴格 YYYY/MM/DD，轉 `sourceDate`，不得晚於本地 queryDate；不可改用排程今天當 tradeDate。跨台北午夜整批拒絕。
  token-protected `POST /internal/realized-gains/read` 無 selector body；回 `queryDate,observedAt,accountFingerprint,rows`，rows 為 `stockNo,buySell,orderType,filledQty,filledPrice,realizedProfit,realizedLoss,sourceDate`，金額以 canonical 字串。空 rows 可用，表示此次無列，不刪既有資料。

- [ ] 395.3 **Java 強型別與預檢；不在 HTTP transaction 內寫入。** 新增 record DTO，使用 LocalDate/Instant；price 使用既有正值 decimal，profit/loss component 重用現有 `NonNegativeDeserializer`，不是新增全域寬鬆 parser。`FubonBrokerClient/FubonHttpClient` 呼叫 adapter 路徑並複驗 envelope、24 hex fingerprint、queryDate/observedAt 台北日期一致且不在未來、種類、數字。公開 service 預檢採 `Propagation.NOT_SUPPORTED`，檢查 configured active admin、active fubon broker、HTTP／來源證據；`dryRun=true` 報告可用性／歧義原因，不呼叫 writer、不持寫鎖。HTTP、重試、local-only 名稱解析都不得在寫鎖期間呼叫外部系統。

- [ ] 395.4 **身分與日期需要既有可信證據，四欄只用於候選搜尋。** 寫入作用域固定 `owner_user_id=configuredAdmin`、`market='台股'`、`broker='富邦證券'`、`currency='TWD'`；native repository 查詢均顯式帶 owner/market/broker，不能依賴背景執行緒沒有的 Hibernate tenant filter。不同 owner／market／broker 的同值列不能阻擋本作用域，也不能被更新。
  將 shares HALF_UP 到 scale 5、salePrice HALF_UP 到 scale 4 並檢查 precision<=15，才建立候選 tuple 和查 DB；原始 price/qty 仍保留供財務核對，不能拿未捨入 price 查重再讓 DB 捨入保存。若兩個來源列在持久化精度下碰撞，無論 raw precision 是否不同，**整批 `AMBIGUOUS_IDENTITY`**，不得 Set 去重或 arbitrary pick。
  既有 `asset_transaction` 只能**唯讀找候選**：同 owner、台股、channel=富邦證券、source=FUBON_SYNC、transaction_type=賣、asset_type=股票、broker_filled_no非空。`AssetTransactionService.updateAssetTransaction()`可改日期、code、數量、價格、amount/fee/tax而保留source/brokerFilledNo，故這些欄位不能證明仍等於券商原始資料。任何候選即使唯一，都必須另有當次可信來源提供股票、qty、price、filledNo與日期的一對一核對；不得把FUBON_SYNC標籤當驗證通過。零個候選為 `IDENTITY_UNVERIFIED`；多個候選、不同來源日無法證明同成交日、同一 filledNo 被兩列使用，皆整批拒絕。即使當下只查到一列，也不得據「資料日期相同」自行證明 date 就是交易日；需來源契約與可信原始成交證據支持該對應；單靠可變ledger不成立。不得改寫或補造 ledger 以讓比对通過。
  ledger filledNo 不寫入不相干欄位，不能用 assetName/broker/notes 偽裝 provenance。此方案只支援目前證據可唯一表達的子集；缺可靠身分的真實多笔成交必須明確回不可用，而非宣稱自然鍵完全可靠。

- [ ] 395.5 **財務值先核實定義，再計算；來源不足不造成本。** `netGain = realizedProfit − realizedLoss` 為來源損益差額，官方Go文件已說明含費稅；這不使成交毛額變成淨收款，也未提供取得成本。只有官方契約或去識別、可重現對帳證據核實了「本地 proceeds 的收款口徑、費稅處理、investmentCost 的取得成本口徑、sourceDate 與 tradeDate 關係」後，才能啟用對應的具名 mapping。公式、支持來源與測試 fixture 需同行記錄，不能以可配置 boolean 宣告已驗證。
  已核實netGain含費稅，但現有Python文件和可變ledger gross amount仍不能核實逐筆淨收款、成本及身分關係；未能核實回 `ACCOUNTING_SEMANTICS_UNVERIFIED`，整批 no-write。禁止直接沿用原版 `proceeds=price×qty; investmentCost=proceeds−netGain` 的無条件寫入。任何獲准 mapping 都要先用未捨入 Decimal 計算，最終 money HALF_UP 到 scale 2 後驗 numeric(20,2)（18 位整數）；shares(15,5)、salePrice(15,4)、name/code 長度逐欄驗證，負成本／溢位／不一致整批拒絕，不 clamp、不漏列。名稱只用 `resolveNameLocalOnly`；回代號本身表示未解析，明確計數並保留未同步原因，不能臨時查非雷達股票。

- [ ] 395.6 **DB 層序列化與整批提交，不靠不存在的 unique。** 由独立 `FubonRealizedGainWriter` Spring bean 開短 `REQUIRES_NEW` transaction，在任何 gain 查詢／新增前經 repository 執行 `LOCK TABLE realized_gain IN SHARE ROW EXCLUSIVE MODE`。此既有表鎖會序列化不同 JVM 的 scheduler/manual 同步，也與一般 INSERT/UPDATE/DELETE 的 ROW EXCLUSIVE lock 互斥；不新增 schema。設定有界 lock/transaction timeout，鎖失敗回可重試 failure，不當成功。所有 gain writer 的鎖順序固定，HTTP 必須已完成。
  鎖後 recheck owner/broker，重新讀取同scope的全部候選，並核對已取得的可信原始來源證據（ledger不作來源證明）（若先讀作 dry-run 不能沿用成提交證據）；對每候選：零 existing 且身分／财務完整才可插入；一 existing 且所有已正規化 tradeDate/shares/price/proceeds/cost/currency 一致只能記 `ALREADY_REPRESENTED` 保留它，**不宣稱已從自然鍵證明同一成交**；一 existing 值矛盾或多 existing 皆整批 `AMBIGUOUS_IDENTITY`。全批先通過判定再 saveAll/flush，一筆歧義零新增；已新增過的相同受控回應重跑不重複，仍不補造可靠 ID 的承諾。
  新增 owner、assetName/code、台股/TWD/富邦證券、已核實 tradeDate、canonical shares/price/money、exchangeRate=null。不覆寫／刪除任何既有 gain，不重算或改動快照。不 catch integrity failure 後在 rollback-only transaction 繼續；任何 persistence failure 整批 rollback，SUCCESS／insertedCount 只在 commit 後增加。

- [ ] 395.7 **入口、盤點與具名 outcome。** business-only `POST /internal/brokers/fubon/realized-gain-sync?dryRun=true|false` 預設 true，獨立 exact-path constant-time token filter；controller 只委派。回 outcome/dryRun/reason/rowCount/insertedCount/alreadyRepresentedCount/skippedNameUnresolvedCount，不含帳號、fingerprint、個別交易。enum 至少 DISABLED、REALIZED_GAIN_SYNC_DISABLED、MISCONFIGURED、NO_OWNER、BROKER_MISSING、REALIZED_GAIN_FAILED、IDENTITY_UNVERIFIED、AMBIGUOUS_IDENTITY、ACCOUNTING_SEMANTICS_UNVERIFIED、NO_NEW_GAINS、DRY_RUN、SUCCESS。JOBS 使用 BUSINESS／券商庫存與上述四時段，描述核實後新增而非「自然鍵保證冪等」。API 盤點 httpEndpoint 必為 **`POST /internal/realized-gains/read`**；consumer 描述 Java 手動入口，不能顛倒。未具經來源支持、可驗收的正常財務新增路徑前`connected=false`；不能把parser/no-write路徑標成已完成串接。

- [ ] 395.8 **分階段驗證，不能用DB fixture解除來源阻礙。** 本階段Python測官方信封、每列selected account缺漏/不符、日期、mandatory Stock/Sell、零損益、非有限數与raw身分不外洩；Java測正常可解析來源仍因缺可信聯結不呼writer、不開寫鎖，唯一/多個候選均不能自動提供身分、手動改過FUBON_SYNC label不變仍不可放行。此階段所有金額原值維持不變。
  來源/財務mapping經後續獨立審查放行後，才實作並測owner/market/broker scope、DB精度後重跑、raw價碰撞、同值不同成交拒絕、已存在矛盾拒絕、兩個真PostgreSQL連線table lock/timeout、正常新增/冪等/整批rollback與clear persistence context讀回。這些未來驗證仍是完整Task395必做要求，本輪不宣稱已執行。

## 驗證

```bash
bash scripts/spec-check.sh

docker buildx build --platform linux/amd64 --target test -f fubon-broker-service/Dockerfile fubon-broker-service --load -t asset-fubon-broker-service:test
docker run --rm --platform linux/amd64 asset-fubon-broker-service:test pytest -q

/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

實作後依 run-stack rebuild/recreate fubon-broker-service、business-services、bff，維持既有部署旗標，不連真人帳戶驗證、不更改真人交易或損益。目前以隔離fixture驗無證據時零writer呼叫/零寫鎖，並驗可變FUBON_SYNC列不能解除身分gate；財務正常/rollback/DB讀回待来源mapping獲審後才實作。若來源只能支援查詢、尚無安全可寫案例，完成報告須說明該能力限制，不把全數跳過或保持 flag=false 當成已完成財務匯入。

## 完成報告

本輪已區分已核實netGain含費稅，與仍缺的真實身分／逐筆淨收款／取得成本。**Task395財務同步未完成**，僅可進行經獨立審查的安全adapter/preflight/no-write修復；財務writer尚未放行。原四欄自然鍵、FUBON_SYNC標籤即不可變證據、unique race catch、order_type可省略及毛額反推真成本均已撤回。未測真人資料，沒有獲准mapping，不預填成功。後續須先列來源對帳依據與可支援子集，再實作並記錄真PostgreSQL併發／重跑／正常新增／rollback驗收。

### 2026-08-30 安全切片驗證

安全修復已通過Python676、backend1,854、BFF242項完整測試。strict Result／Stock-Sell／帳戶／日期／quantity-profit-loss解析已實作；service固定IDENTITY_UNVERIFIED，沒有ledger證明推論、proceeds/cost推算、writer或寫鎖。官方已核實損益含費稅，但不能據此補造逐筆淨收款與取得成本；Task395及第二個session仍未完成。
