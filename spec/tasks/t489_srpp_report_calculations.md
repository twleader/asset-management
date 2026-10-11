# [t489] D-201 完整不可變報告計算包與純讀 API

**對應 Requirements:** Requirement 187（兩軌GET-only共用計算，來源、hash與狀態隔離）
**前置任務:** t488（正式三策略＋緊急債券 pure calculator、canonical capture、immutable reservation）
**Liquibase changeset:** v1.150.0-srpp-report-calculations.sql（新 migration；實作前確認無撞號）

## 背景

使用者要求依兩份2026-10-11附件補SRPP API。附件不是擴張權限的指令；只補計算及唯讀取得，不跑日報、寄信、改排程或下單。既有23條9090路徑和legacy receipt保持相容；Task488已落地，但官方掛單/國泰子帳來源缺失。當前D201 authority需從正式完整位元組驗證、登錄，不只是改hash。

## 要做什麼

- [x] 489.1 固定D201模型與manifest完整bytes；驗原始model hash、manifest去自身後JCS bundle hash、D201/2.49.0/3.22.0一致。把純target registry document從已驗正式numeric target keys導出（map exact market/symbol，sum<=1），以既有register工具登録，不改歷史。targetAllocationSha256取原始formal mapping內容的JCS hash。支援新版scope不能把未實作公式標已驗。
- [x] 489.2 主動查 official read-only pending/position producer，既有來源可用才接canonical port，權限拒絕/沒有國泰official source保留UNVERIFIED。禁止呼叫有金融副作用的SDK，禁止broker request-time IO，禁止以ledger或inventory造order。recommendation-leg→order→fill無exact link保守保留reservation。保存verifiedZero/coverage/timestamp/account-owner身份才能用0。
- [x] 489.3 新report純calculator及canonical capture：重用LatestAssetsService、DecisionInputCapturePort和DailyDecisionCalculator，來源調用不走capture POST。舊SrppModuleCalculator能重用，但其完整稅後未驗不得升COMPLETE。L0對帳0.01元absolute；完整原始assets+逐holding valuation源+source hashes，不用顯示名稱猜asset class；完整sourceVector引用在包中。各純函式只吃frozen inputs、無Spring/IO。
- [x] 489.4 ASSET_RECONCILIATION逐holding/基金/銀行幣別分組及靜態/即時總額；ALLOCATION_GAP exact正式targets＋分母/現值/目標/缺口/動態bond帶/緊急策略分帳缺口；FUNDING_CAPACITY逐幣別settled/待付/正TRANSIT排除/通膨保护金/reservations/fee，只有verified完整orders可mark deployable verified。CASH_INCOME逐來源gross及分類稅制/NHI/net/spendable/不可支配/年度結算receipt，缺正式稅/通知/利率時逐欄PARTIAL不補0；00865Bknown無配息、緊急定存续存、SGOV策略可支配。PAYROLL_CASHFLOW有正式薪資來源才套原共用公式，2027實際扣繳與2028-05净結算分開。BOND重用四leg calculator。其餘六module按下列完整凍結需求/正式policy公式移植，逐scope golden；沒有canonical prerequisite保留具名缺口；未實作scope必須NOT_EVALUATED/CALCULATOR_SCOPE_NOT_VERIFIED而非COMPLETE/假blocked。
- [x] 489.5 背景producer每60秒短tick（srpp.report-producer-cron可配置，預設0 * * * * *），cached日曆開市且09:05≤now<12:00處理09:05、12:00≤now<14:00處理12:00。來源暫缺/L0失敗下一tick重試；late source revision建立新包；同日restart首tick補當前window，不跨日/slot補舊包。cache日曆先行、active owners、authority/policy/swagger校驗、捕捉+純計算+原子publish。GET不觸發producer。source fingerprint保留來源原始實質內容/source time/revision，排除輪詢的capturedAt/transport generatedAt；同來源同政策同calculator不重算共同module；consumer stateBefore及其hash獨立，完整input必include state/reservations且不讀另一consumer。缺state明示UNAVAILABLE，不進賣出。sources改變建立新shared/run ID，舊ID永不更新。
- [x] 489.6 PostgreSQL base及package兩張insert-only表，owner FK/policy FK、base FK/consumer/date/slot/metadata/hash，unique source fingerprint/consumer state identities，DB trigger防UPDATE。原子transaction內先按owner/date/slot/policy/swagger/calculator/source fingerprint取得PG advisory transaction lock，再readbase；命中重用，未命中才pure shared calculate與insert。consumer identity/state fingerprint也先查再compose。鎖後驗hash與identity後insert，unique conflict讀既存winner；latest只選保存完成包，並行與崩潰不留假FINAL。長度/安全integer/hash/time/consumer/slot約束，indexes。重產db/schema.sql，完整PG tests。
- [x] 489.7 strict query與internal read service/exact controller/BFF代理/validator。runID同owner wrongidentity409、其他owner/不存在404；owner lookup5s與explicit tenant identity。不允許未知或重複key，UUID格式/hash/date/slot/consumer/email。read-only transactions及測試zero writes/producer calls。BFF驗所有成功schema/hash/source refs/coverage/leg數值/Decimal及bound consumer，不代算投資結論。problem七欄RFC9457 code/retryable；只有CALENDAR_UNAVAILABLE/CONTEXT_NOT_READY可重試。
- [x] 489.8 新唯一路徑同步gateway/security/catalog/deny/Tailscale/staticroute tests/OpenAPI(24 pairs20GET/4POST)。完整nested schema及資料用途，不把string payload當未驗黑箱，parameter未知/重复校验。由renderer重產repo/classpath/SRPP三份Swagger，重建business，驗published身分。register producer於SchedulePublicBffController.JOBS。
- [x] 489.9 按下列golden矩陣驗收；真日曆當天休市，只驗non-trading拒絕並報告業務runtime正例待交易日，不改當日日曆或植入正式假資料。可用isolatedPG/clock fixtures測成功/原子/readback，但證據分開。驗收通過後依skill merge/push，SRPP鏡像保留無關dirty改動並獨立landing；不得把新API自動啟用到SRPP排程allowlist，需其正式採用驗收。

以下凍結附件是本任務完整wire、module與驗收契約。任何scope未完成應在完成報告逐一列，不改成已支援。

### 實作契約補充

官方 pending 來源實作：富邦官方 SDK `stock.get_order_results(account)` 為唯讀委託查詢（官方契約：https://www.fbs.com.tw/TradeAPI/docs/trading/library/python/trade/GetOrderResults/ ）。新增 token 保護的內部唯讀 broker adapter，僅背景同步工作可呼叫；不新增 9090／browser 路由。背景工作使用既有 FubonSyncOwnerPolicy 選定 ACTIVE configured owner，先驗 cached 台股交易日、台北 09:00–14:00 window，再以可設定 cron（預設每分鐘）單飛讀取所選帳號委託及官方庫存。既有 read-only feature flag／憑證／有界 SDK budget 保持；不更改權限、不 import 或呼叫 order write API。

同步完成後 append-only 保存 owner、交易日、broker=fubon、HMAC account fingerprint、capturedAt、revision、complete／verifiedZero、官方委託原欄位與庫存身分。新的 canonical snapshot 表是待新增 schema，不是既有事實；需 migration、不可修改 trigger、pg_dump 與 owner/date index。抓取失敗亦保存具名 UNAVAILABLE，不能沿用先前成功包當新鮮成功。只有 request date 相同、捕捉不在未來且不超過 120 秒、帳號／owner 完全吻合、SDK 完整成功的 source 才可 VERIFIED；完整空集合可 VERIFIED_ZERO。

掛單解析驗 original quantity、after_qty、filled_qty、status、market_type、buy_sell、日期、order ID、價格及帳號。剩餘量依官方 after_qty（含已成交）減 filled_qty，不以原量忽略改量／取消；官方狀態未能確定語意或不支援委託型態須 fail closed。保存取消／部分成交事實，不造 fill。尚有有效買賣委託卻缺 recommendation-leg → order → fill 的 exact link，對受影響候選保持 BLOCKED 並保守保留 reservation；不以 symbol／amount 相同猜連結。不做自動 broker 對帳寫入。

本次 report D201 capture 只讀此 persisted canonical snapshot，凍結原始來源／sourceVector／Evidence；GET 不依賴 broker adapter 或同步服務。保留舊 policy 的 capture／hash 語意。富邦完整零掛單可以解除對應富邦 gate，不能代表國泰或別的帳號零掛單。00865B 富邦與國泰分帳各自保留驗證；無國泰官方來源則 combined subaccount gate 仍 UNVERIFIED，不能由富邦或總庫存倒推。其他 owner 無此帳號來源也保持 UNVERIFIED。

必要測試包括唯讀 SDK method allowlist、token／flag／calendar／window／owner gate、SDK 權限拒絕、timeout、未知 status／quantity／帳號錯誤、部分成交與取消、完整已知零、source age／跨日／revision 與最新失敗不可回退、atomic append／immutable rows、report capture 原始 source 引用、無 exact link 保留占用，以及 GET 零 broker／同步呼叫。部署新增受影響 fubon-broker-service，仍不改 secrets 或啟用交易。

正式 `detailed_target_allocation_2027` mapping 的數值先以 BigDecimal 無損讀取並正規化為十進位字串，再保留原 key 做 JCS SHA256；此為本包 `targetAllocationSha256` 的固定序列化。registry 的 market/symbol target document 有獨立 document hash。原 model／manifest 位元組 hash 另行保留並驗證，不能以重新序列化的 model 取代原檔身分。

純讀限制包含錯誤收集：exact GET report-calculations 不經 advice、WebFilter 或 gateway failure tailer 呼叫 ingestion；400／502／503 亦不得因 query 而寫入。仍保留一般執行日誌，其他既有路徑不受此例外影響。

# SRPP 計算外移至9090：缺口與API規格

狀態：待資產管理系統實作的設計提案；不是已發布Swagger、不是Accepted投資門檻、不授權排程呼叫。版本0.1；日期2026-10-11；需求來源D-201。排除於policy bundle；投資正本仍為SRPP D-201／政策v2.49.0／模型v3.22.0與其實際hash。

## 1. 問題與交付範圍

現有三條GET已提供固定context、三項計算與持股市場事實，但SRPP驗證器尚不能核準共同市場capture不明的盤中配置與未獨立驗證收益。FUNDING_CAPACITY與SYMBOL_RULE_FACTS未啟用。獨立completed-technicals／technical-series已實作，不重開。Task488的daily-decision已計算D130／D195四個leg；pending與券商子帳尚缺來源，且GET-only瀏覽器不能建立POST收據。

本案優先補現有API與canonical evidence；只新增一條GET共用計算結果路徑，使兩軌均可讀資產系統預先算好的完整報告數值。保留既有路徑與歷史receipt；不把日報render、LLM文字產生、Gmail、交易執行或Monte Carlo模擬搬入此API。

## 2. 現有能力須先補齊

| 缺口 | 擴充位置 | 驗收條件 |
|---|---|---|
| 盤中估值／配置共同capture | calculation-context、calculations與canonical資產估值 | 固定包保存完整assets response與逐holding quote revision；回傳valuationCaptureId及各來源hash。直接L0若後取，證明相同持股／存款／快照靜態內容，動態估值明示不同capture；不得偽裝為同一值。不能證明相同capture的欄位只可作歷史解讀，不取代直接L0。 |
| 目標與資產分類 | ALLOCATION_GAP | 目標由正式CSV／model同hash讀取，不靠顯示名稱猜類別。現值、分母、受保護現金、動態債券帶、緊急／策略00865B分帳逐項receipt；未知分類逐欄UNAVAILABLE。 |
| 稅後／可支配現金收入 | CASH_INCOME及新報告模組 | 股票／基金／存款逐來源，收益分配分類依正式通知；緊急定存利息續存、不列可支配，00865B不配息，SGOV戰略配息可支配。未知普通存款利率UNAVAILABLE，不補0。股利兩制、AMT、外國稅、二代健保及2027薪資／2028年5月淨結算分開列，不重複扣。 |
| 掛單與成交去重 | canonical pending ledger＋daily-decision | 官方broker唯讀來源保存owner／broker／account、order ID、symbol、side、原量／已成交／剩餘、status、交易市場、擷取時點、完整性與revision。完整查詢成功的空集合才能代表0；權限拒絕／來源不可用不是0掛單。不得以交易紀錄或庫存推造訂單。 |
| 00865B子帳 | canonical broker subaccount evidence＋daily-decision | 富邦策略／國泰緊急分別核對owner、account、持有量、已交割／待交割、市值與quote capture。帳戶可用且經完整查詢的0持股才是VERIFIED_ZERO；券商沒有可用官方讀取來源則UNVERIFIED，不由使用者總庫存倒推。 |
| 建議、掛單、成交連結 | reservations／ledger | 決策leg ID→真實order ID→fill ID確切連結，避免同一已成交建議再占容量。無連結保守保留占用並揭露；不得推測某筆成交消耗某建議。 |
| 支援新版規則 | policy registry與DecisionPolicyAuthority資源 | 登錄當前D-201正式完整model／manifest位元組；核對hash、版本與公式覆蓋，不只改資料庫hash。舊receipt可讀但不可當新版結果。 |

這些是後端內部輸入能力，不要求新增對外broker寫入或sync API；計算請求不得刷新、登入broker或取得外部行情。來源更新由既有正式讀取工作供給。

## 3. 唯一新增路徑

```text
GET /api/public/srpp/report-calculations
```

GET純讀已發布immutable計算包，不觸發計算、worker、外呼、資料庫／Redis寫入、通知、email或下單。由背景producer在正式canonical來源revision變更後完成捕捉、純計算、驗證與原子發布；同輸入不重算。冷啟動或新規則尚未發布回503，不在GET中補算。延續loopback／獲準Tailscale網路邊界與ACTIVE owner解析。

Query契約：

| 欄位 | 必填 | 限制 |
|---|---|---|
| email | 否 | ACTIVE owner email；省略configured-admin；格式錯400，查無／停用503 OWNER_UNAVAILABLE |
| consumer | 是 | `Codex`或`Claude`；須與本輪執行端一致，只讀自身CORE_SELL_GUARD狀態，不能借另一軌狀態 |
| tradingDate | 是 | ISO date，Asia/Taipei今天；歷史包只供另外受控稽核流程，不由本查詢供日報沿用 |
| reportSlot | 是 | `09:05`或`12:00`，不早於該時段；這是新路徑的實際報告時段，與舊API的午盤11:40映射分開 |
| policyBundleSha256 | 是 | 64小寫hex；須驗證已登錄正式model／manifest完整內容 |
| swaggerSha256 | 是 | 64小寫hex，等於當輪已發布Swagger身份 |
| calculationRunId | 否 | UUID。無值讀該owner/date/reportSlot/hash最新已發布包；有值讀同identity固定包，其他owner與不存在統一404 |

未知、重複query欄位及非法型別回400；不接受stockCodes、formula、rawInput、amount、lots、limitPrice、score、refresh、force或caller建議。完整候選宇宙由服務端政策／canonical持股／合格觀察名單導出，包含未持有但正式允許的候選；市場外或策略外只NOT_EVALUATED，不能自動擴大白名單。

latest指標只指向完成保存的不可變包。每個包用`owner/consumer/date/reportSlot/policy/swagger/inputSnapshotSha256/calculatorVersion`唯一識別；sources改變才建立新calculationRunId，絕不覆寫舊包。消費者取得後以ID固定讀回，不把不同包的數值拼接。同一份核心／附錄保存同ID；新硬證據依D-200凍結規則在附錄揭露。producer發布不是每日報告完成證據。

## 4. Response envelope（schemaVersion 1）

| 欄位 | 型別／要求 |
|---|---|
| schemaVersion | integer，固定1 |
| calculationRunId | UUID，不可變包ID |
| status | `FINAL`；FINAL只表示捕捉與計算完成，不代表所有模組COMPLETE |
| identity | ownerUserId、consumer、tradingDate、reportSlot、policyBundleSha256、swaggerSha256，全部required |
| authorityRevision | serviceBuild、databaseSchema、calculatorVersion、modelInputsSha256、policyManifestSha256、targetAllocationSha256 |
| sharedCalculationId | 共用無consumer-state的來源與計算基底ID；兩軌基底相同時不重算共同模組，完整包ID可因各軌state_before不同而不同 |
| inputSnapshot | capturedAt、assetGeneratedAt、assetSnapshotId、valuationCaptureId、inputBundleJcs、inputSnapshotSha256、sourceVector |
| modules | 以下moduleId恰好各一次，含status、formulaVersion、scope、data、missing、reasonCodes、inputRefs、receipts |
| coverage | requestedCount、completeCount、partialCount、unavailableCount、notEvaluatedCount；總數須等於modules長度 |
| executionScope | 固定`REPORT_RECOMMENDATION_ONLY` |
| tradeAuthorization／placesOrders | boolean，固定false |
| contentSha256 | 移除此欄後envelope的RFC8785 JCS SHA256；資料包所有Decimal為正規化字串，計數與ID使用安全integer |

inputBundleJcs包含canonical原始source payload（含完整資產），政策原始位元組身分、抽取facts、state_before與reservation identity；sourceVector每項有sourceId、revision、capturedAt、sha256、coverage、validation。回傳包不含broker登入憑證或內部秘密。GET-only消費者不需再呼叫另一POST才能取得證據。

modules：

| moduleId | 確定性輸出與政策來源 |
|---|---|
| ASSET_RECONCILIATION | 靜態／即時各總額、銀行／幣別存款及逐holding；L0獨立核對保留 |
| ALLOCATION_GAP | 逐目標／群組現值、分母、目標、缺口、動態帶、受保護現金及未知分類 |
| FUNDING_CAPACITY | 各幣別已交割資金、待付款、未交割收款排除、保護金通膨、已占容量及費用；所有銀行合併不自動轉帳 |
| CASH_INCOME | 分來源稅前、逐稅制稅額、二代健保、稅後、可支配、不可支配、年度結算；欄位互相加總驗證 |
| PAYROLL_CASHFLOW | 正式薪資／勞退時間／2027實際扣繳，日期與金額；資料不足具名缺口，未達適用條件NOT_EVALUATED |
| BOND_D130_D195 | 重用Task488正式計算器，策略日2／週6與國泰緊急獨立額度、價位最深唯一選擇；完整掛單／子帳缺少就BLOCKED |
| DIVIDEND_TOPUP | D143／D198／D189，四檔還原前20完成日最高、1%／2%門檻、日3／週5、標準化缺口、資金與depth |
| CORE_SELL_GUARD | D174相同consumer-state來源、確認轉弱與高水位回撤；無完整state不能賣。state_before在包中；GET不更新state，需背景正式state機制與隔離策略驗收 |
| STRATEGIC_BUY_GATES | D188／D189、SGOV事件與除息close、D182 DCA豁免，eligible與blockingReasons |
| GLOBAL_EQUITY_PACE | VOO／009826月基準／上限、DCA計入方式、批次與月缺口；不把未結算或舊建議當成交 |
| ROTATION_AND_REGIME_CAPACITY | 股票換股準備金、年齡、牛熊觸發、先後順序與資金／配置容量；沿用D152／D156及正式條文，不啟用模型代理代替live |
| SHORT_TERM_AND_US_TACTICAL | 台美市場範圍與白名單、D173／D181／D185／D186、淨空間與全部成本、風險限額、同向精確detail；不得拿台股五檔規則套美股 |

COMPLETE僅限全部必需輸入verified且欄位完整。PARTIAL逐欄missing；null不補零、不把未知宣稱0。政策不允許的BLOCKED候選可以是COMPLETE計算；它不是模組UNAVAILABLE，消費者不得改走本機解除。未適用策略NOT_EVALUATED，不產生數量／限價。已知0與未知須以quality及reason分開。

每個建議leg包含symbol、market、broker／subaccount、strategy、status（ACTIONABLE_FOR_REPORT／BLOCKED／NOT_EVALUATED）、action、units或lots、limitPrice、amount、currency、fees、netProfitSpace、rankingReason、blockingReasons及逐gate receipts。各策略自有單位；不能把美股股數當台股張數。BLOCKED為0且限價／金額null，不適用者亦不得產生正數。來源、風險判讀與該consumer的額外否決分開；共享包不偷用另一軌的LLM事件或core-sell狀態。

公開風險的語意判讀保持每consumer獨立；六項風險分數加總／已評數／mode可重用既有risk evaluator或event-evidence既有服務。不得在此GET啟動LLM，亦不得宣稱NOT_BOUND的共享決策已覆蓋consumer事件。若需把事件receipt納入合成數量，須在後續明確spec中定義immutable引用與consumer隔离；本版不增加該POST權限。

## 5. 新鮮度、衝突與失敗

L0身分與政策全域失敗不發布假FINAL。來源某標的缺漏只影响對應leg／module。quote／book／radar按D175及策略原時效在producer計算與consumer最後使用時分別驗證；capture新不等于sources新。跨日期／未來時間／缺timestamp拒絕。旧runId可以原样讀回，但consumers必須拒絕過期具体限价，不能把replay當重新取价。

HTTP：200已發布包（可部分模組不可用）；400 INVALID_REQUEST；404 CALCULATION_NOT_FOUND（不揭露他人包）；409 NON_TRADING_DAY、POLICY_UNSUPPORTED、SWAGGER_MISMATCH、CONTEXT_IDENTITY_MISMATCH；502 UPSTREAM_INVALID；503 OWNER_UNAVAILABLE、CALENDAR_UNAVAILABLE、CONTEXT_NOT_READY。problem使用RFC9457，帶固定code／retryable；只有日曆／context暫缺可重試。不因單一模組缺值回整包500。BFF驗證所有schema、hash、coverage、leg數值與來源ref，不能只轉送200。

## 6. 實作順序與驗收

1. 固定D201正式model／manifest與已發布Swagger，同步registry及DecisionPolicyAuthority；不能讓同hash套用不同公式。
2. 先補官方pending／subaccount及建議成交連結；官方沒有提供的來源保持UNVERIFIED，不能用本機ledger製造broker事實。
3. 補共同valuation capture、target mapping與稅後收益；舊GET的V1/V2與歷史receipt相容，新增欄位不改舊hash。
4. 實作上述背景producer與單一GET，完整同步OpenAPI、Nginx、BFF安全／route catalog、Tailscale、前端exact404及SRPP Swagger鏡像。
5. 按模組逐一移植／重用正式共用計算器，以同一凍結輸入對原SRPP計算器做逐欄差分；只把已驗證scope標COMPLETE。未完成策略保持NOT_EVALUATED，不由LLM補。
6. 最後才把新GET接到SRPP規則與consumer validator；保存實際runtime請求、原始回應、完整讀取／采用收據。拒絕探测、mock或單纯contract PASS不代表业务成功。

必要golden矩陣：成功／已知0／政策BLOCKED／來源未知；兩owner隔離；台北跨日與09:05／12:00；D175低量各級；同hash異model bytes；同輸入重播與同時producer原子發布；來源變動新ID／舊ID不變；一次股票價變動L0差異；null利率／在途0；00865B雙券商缺一／完整0；掛單部分成交／取消／未知與予約去重；日週額度跨slot；四高股息1%／2%邊界；D174趨勢／回落與consumer狀態隔離；SGOV錯代號／日期／事件close；DCA豁免與月批次；short／US units與成本；風險NOT_BOUND；第35／40／60分鐘與核心凍結。

runtime成功至少需要當前正式hash、真實交易日完整canonical evidence、各已啟用scope的成功golden與缺口案例、兩軌GET-only讀回相同共用基底sharedCalculationId/hash，並各自核對consumer隔離的完整包ID/hash、來源／receipt逐欄驗證。若官方broker來源不可用，交付精確限制並保留BLOCKED，不以合成資料宣布實盤建議可用。本spec本身不是實作或部署完成證據。


## 驗證

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest='*SrppReport*,*DailyDecision*' test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml -Dtest='*SrppReport*,*SrppCapture*' test
ruby scripts/tests/docker-external-api-openapi-test.rb
bash scripts/tests/configure-tailscale-api-gateway-test.sh
ruby scripts/render-9090-openapi-docs.rb --check
bash scripts/spec-check.sh
bash scripts/tests/schema-sql-drift-test.sh
git diff --check
```

新測試類與隔離 PostgreSQL 測資已建立；測資不得寫正式 owner 狀態。concurrent calculator invocation counter須證實同共同輸入只算一次；源於slot暫缺→晚到、restart補當前window、slot後revision改變須測。日週额度、金額、unknown/0/blocked、兩owner/consumer隔離、12:00時效、replay/來源變revision/並行、payload tampering、安全boundary與official缺口為必測。模型公式scope parity需要SRPP原共用calculator的相同輸入差分證據。

Docker驗收使用run-stack，未merge feature先比對main的.env（不輸出secret），只重建受影響的business-services/bff/api-gateway/frontend/fubon-broker-service並force-recreate。health及真API拒絕/已發布純讀結果逐欄檢核；不得呼叫券商或寄信。schema產生用docker exec asset-postgres pg_dump --schema-only，保持現有檔頭并更新table count。runtime報告區分live boundary、isolatedfixture成功和未可核准scope，不用mock代替真交易日證據。

## 完成報告

2026-10-11 完成實作與 feature Docker 驗收。本報告記錄提交前的測試及部署證據；兩個 repository 的 merge／push SHA 與合併後 main 重建結果由本任務最終回報及 Git history 查證。

- 唯一新增公開路徑為 `GET /api/public/srpp/report-calculations`，12 個模組、兩軌獨立完整包、共享計算基底、背景生產與不可變保存。GET 不計算、不刷新官方來源、不寫入錯誤收集；既有路徑及舊 receipt 保持相容。
- D201 正式 model／manifest 完整位元組一致，registry insert-only 新增一筆後逐欄完整讀回。正式 bundle SHA256 為 `e9dbac6389f05ce8d9c1c52a35754b4862a65d1769f4e49d321e4022a2f560f9`。
- OpenAPI 版本 1.25.0，24 method/path pairs（20 GET／4 POST）。repo、backend classpath、SRPP/docs 三份 Swagger 位元組一致，SHA256 `1338bc9a6437e1b8c78ef0d40f7fc66ef9a124c9c5618909bcd81779990d14b4`。Tailscale 實際讀回 23 path handlers，保留既有 mail child prefix，沒有新增 root 或 /api wildcard。
- 最後 backend focused suite 128 tests、BFF all SRPP／OpenAPI suite 257 tests 均零失敗、零錯誤、零跳過。富邦 Python 全套 875 tests 通過；其後中立 port／adapter 架構調整再驗相關 248 tests 通過，兩者有重疊，不合併宣稱唯一測試總數。
- 82 組相同輸入 golden 差分涵蓋共同公式、收益／薪資、short／US、state／topup。TOPUP 完整 sizing 的原 Python 差分涵蓋 tier 與 fee denominator；新增 amount／depth 約束另有明確測試，不宣稱整個 solver 已對單一原 Python 函式逐欄差分。
- 真 PostgreSQL 隔離測試包含 16 個並行 producer 共同計算一次、兩軌 state／包 ID 隔離、source revision 新 ID、原子 rollback、不可變舊包、跨週／跨 policy reservation 保留、GET 純讀及完整 TOPUP／台股 short BUY／SELL 正例。tracked wire fixtures 全為合成隔離資料，不含正式個人資產，也未植入正式 report tables。
- 正式 cached source 容量驗證為 24 標的、11,154 筆日資料、43 筆持股；完整 escaped wire 8,607,531 bytes，低於既有 16 MiB BFF 限制。這只證明容量，不證明今日盤中來源可用；實際個人來源暫存檔已移除。
- feature 五個受影響服務均完成 image rebuild／container recreate，所有有 healthcheck 的服務健康。backend 8.2 秒恢復 healthy，舊 BFF safe GET 恢復 200 後才正常重建 BFF。正式 environment／secrets bind mounts 與 main 完整比較一致，未更改憑證或交易權限。deployed backend jar SHA256 `4acaf8280f8bb9d1ddd9b645d1292fd17c652892deef7e9cdbde05d55c0fe4b5`，classpath Swagger 身分一致。
- 今日台灣交易日曆為休市，Codex／Claude 正式 query 與 Tailscale 真 TLS 皆回 `409 NON_TRADING_DAY`，七欄 RFC9457、retryable=false 逐欄驗證。非法 query／body 為 400、POST 405、slash／descendant／frontend 404。GET operation error ingestion 測前後皆 0；正式 report base／package rows 皆 0。未改日曆、日期或正式植入正例。真交易日的正式業務正例仍需以當日完整 canonical evidence 驗收，不能由隔離正例替代。
- 富邦官方唯讀委託背景來源、token／calendar／window／owner gate、immutable snapshot、最新失敗不可回退、部分成交／取消／verified zero 均實作並測試。今日未手動呼叫 SDK；正式無 token probe 回 401。國泰官方子帳來源仍缺，00865B combined gate 保持 UNVERIFIED；無 exact 建議→委託→成交連結保守保留 reservation。正式薪資時程、稅制通知、ordinary deposit 利率、consumer state 等未提供的 canonical 欄位逐欄 PARTIAL／UNAVAILABLE，未以 0 或虛構來源補齊。
- 兩個 migration 生效，schema 由運行 PostgreSQL 重產為 115 張表，保留完整標準檔頭，full-byte schema drift PASS；spec-check BLOCK=0／CHECK=0、renderer／OpenAPI／Tailscale contracts、git diff --check PASS。獨立 spec 與架構審查已修正全部 critical／major findings。
- 沒有自動將新 GET 接入 SRPP 排程 allowlist，也沒有產生日報、寄信、下單或變更 Codex automation。後續 SRPP 採用須另有完整讀回與採用收據。
