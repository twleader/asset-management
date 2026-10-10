# Task 488：完成 SRPP 不可變決策引擎與盤中 K 線收據

**對應 Requirements:** Requirement 186（完成 Requirement 182／Task 482，盤中資料只作適用範圍內確認）
**前置任務:** Task 483／484／485；同次交付 Task 487 的純讀分钟 batch 與共享純確認政策
**Liquibase changeset:** `v1.149.0-srpp-decision-engine.sql`（不得修改既有 v1.145.0）

## 背景與範圍

使用者明確要求一併完成 `/api/public/srpp/daily-decision/evaluate` 真正決策引擎及盤中 K 線整合。本任務取代目前永遠回 `CONTEXT_NOT_READY` 的佔位入口，落實 t482 已接受規則。初版只評估 00865B、00719B、00697B 的 D-130／D-195 報告建議，其他標的／美股／賣出／短線為 `NOT_EVALUATED`。任何結果永遠 `executionScope:REPORT_RECOMMENDATION_ONLY`、`tradeAuthorization:false`、`placesOrders:false`。不抓券商、crawler、LLM，不 refresh、寄信、下單、轉帳或修改資產。

Task 487 共享純政策是台股 `SHORT` 買進確認，只能保守延後既有短線買進。本任務的 D-130 為 `MEDIUM`，必須記 `NOT_APPLICABLE`／`HORIZON_NOT_APPLICABLE`（政策適用範圍 `SHORT_ONLY`），不得用分鐘資料、`WAIT`、新聞防禦、等待止跌等額外理由減少中期購債張數（SRPP D-168）。09:05 不足十根完成分鐘棒可產生 `UNKNOWN` 資料品質，但中期結果不受影響。共用政策只一份實作，不另造 SRPP 版本。

## 正式政策與來源身分

既有 `srpp_policy_registry` V1 文書只有配置 targets，沒有決策参数；不得拿 targets 加硬寫常數冒充完整規則包。建立離線受版本控制的 typed decision policy，保留原始 SRPP `assumptions/model_inputs.json`、`assumptions/policy_manifest.json` bytes 或其完整可驗證資源。啟動／使用前驗原始 model bytes SHA-256 等於 manifest 的對應 files 項、manifest 的 `POLICY_BUNDLE_SHA256` 依 SRPP 同一演算法重算一致、decision/policy/model versions 一致；typed 欄位只從已驗原始資料抽取。輸入 hash 同時必須命中既有 calculation registry 與 decision policy 的已支持 hash。typed 參數與 manifest/raw-model hashes 要進 immutable sourceVector；不能 echo request hash 當支援證明，不能在請求提供參數。其他不支援 hash 回 409 `POLICY_UNSUPPORTED`。

按現行 SRPP authority：D-195 最近 20 個完成台股交易日最高「原始收盤減後續現金分配」參考價；回檔至少 0.3%、溢價不超過 +0.3%、合格非 null 折溢價絕對值不超過 1%；排序為回檔較深、溢價較低、標準化缺口較大、代號升冪。策略每日最多 2 張／週最多 6；00865B／00719B／00697B 目標為原始政策精確值 0.063333333333／0.176666666667／0.02；00697B 不超過債券桶 30%。USD/TWD 上限 33。國泰緊急 00865B 獨立額度，合適價 1 張、回檔至少 1% 可 2 張、按 verified gap 除每張現價向上取整容量；緊急部位不計富邦策略目標。台幣定存／全部存款買後底線 800000／1550000（2026-08 實質），按原始 2.25% 年率、自 2026-08-31 的實際天數／365.25 換算當日名目值。費用採原始政策 commission 最大比率並消耗共用現金容量。上述數值是查證說明，實作從 typed policy 讀取，不在 calculator 再寫死。

## 嚴格請求與 HTTP

沿用 exact POST 路徑，Content-Type 僅 `application/json`，缺失／其他型別 415。strict JSON 拒 duplicate member、非 object、未知 fields。僅 optional `ownerEmail` 與 required `tradingDate,slot,policyBundleSha256,swaggerSha256`；email 若存在需合法非空且最多 254 字元；日期是服務端臺北今天，slot `09:05|11:40`，請求當下不得早於該 slot 的臺北 wall-clock（未到時段回 400，不偽造 asOf）；hash 為小寫 hex64。拒 facts/candidate/lots/price/snapshot/refresh/force 或交易指令。BFF 先驗所有格式，400 為零 outbound；`SrppOwnerResolver` optional owner lookup、5 秒 timeout，帶明確 `X-User-*`，清 caller identity，再向 business 轉原始 bytes（10 秒 timeout）。

首次 FINAL 201；同 identity FINAL replay 200；CAPTURING 202、相同 `decisionRunId`、`status:CAPTURING`、`code:DECISION_CAPTURE_IN_PROGRESS`、`Retry-After:2`；同 owner stable id/date/slot 不同 metadata 409 `RUN_METADATA_MISMATCH`。既有 calendar/policy/Swagger/owner/upstream problems 沿用七欄 `application/problem+json`；metadata code 加固定目錄。路由 method/path 集合保持既有 23 pairs，非 POST 405 Allow、child／slash／matrix 404。

## Canonical capture 與 pure-read 邊界

1. 日曆先行：使用 cached-only canonical 台股 calendar，today 未知 503 `CALENDAR_UNAVAILABLE`、休市 409。不以週一至週五替代年度 authority。凍結日曆 identity/hash、exact 前 20 開市日與本週已確認開市日；缺完整窗口只阻擋依賴該窗口的 D-195，不偽造完成交易日。
2. 統一順序：strict request／resolved owner／calendar hard gate → 查既有 FINAL／claim identity → 比較 metadata、校驗 stored hashes、replay 或 202 → 僅新 capture 驗當前 registry／decision policy／published Swagger。replay 不重讀資產／分鐘／行情／雷達；新Swagger發布後同舊metadata仍replay，不同metadata先回RUN_METADATA_MISMATCH。不綁事件 bundle；固定 `eventEvidenceIntegrationStatus:NOT_BOUND,eventEvidenceReason:PER_CONSUMER_EVIDENCE`。
3. 新 capture 僅一次 owner-explicit `LatestAssetsService.getLatestForOwner`，驗 snapshot/live 同 ID、完整 holdings/funds/deposits、數值非負的適用欄位、明细加總與 source total 0.01 元內對帳、generatedAt／owner／holding identities。負 TRANSIT 待付款納入可部署資金，正待收不得先當 settled cash。完整基準不可得／identity或對帳失敗是 L0，無 JSON 後備，無 FINAL。
4. canonical current price 用 `PriceQueryService` bounded batch，dated premium 用同源 `getEtfNavBatch`。完成收盤用 `StockPriceHistoryRepository` bounded query；現價不能取分鐘close、book price、雷達price取代。每份 sourceVector 有 sourceId、revision／snapshot identity、capturedAt、JCS hash、coverage、validation，保存原始完整可重播輸入。decimal 一律 canonical JSON string。
5. 五檔只用 `MarketDataService.getQuoteDetail` 的同一 persisted producer snapshot（既有 DB canonical revision fence／Redis verification）。**具名限縮的 Requirement 114 例外**：本 report-only calculator 可使用完整相反側五檔作深度／限價證據；不得當權威即時價、估值、警報或任何券商下單依據。驗 exact code/market/source、同日 sourceTime/fetchedAt、五層 identity／排序／非負股數與整張單位、bid<=ask；任一必要缺口為 per-symbol block。
6. 每候選成功且恰一次 `TradingRadarService.getStockDetail`，固定 request owner，驗 code/market/ruleVersion/actionPolicyVersion/generatedAt/stale，凍結中期 action/candidateAction，反向只扣不加；不能用別檔或另一來源替代。三檔受試即使 pending 缺失仍捕捉、判計各獨立 gate，不可只回固定 missing-pending reasons。
7. Dividend capture 只讀 ACTIVE sourced records；現金還原是 SRPP additive（與 radar multiplicative 分開）；驗 exact 前20 sessions、每筆正close、事件 source/date與 duplicates、stock distributions／rights衝突 block，不短參數。00865B 只有原始政策 explicit known-non-distributing receipt 可接受 verified empty，其他空事件不自稱零分配證明。
8. 成交去重讀 business 帳本 explicit owner/date query（不可呼叫 BFF）；保留 row id/source/channel/shares/date，計同日／同週份額與策略歸屬。00865B 雙子帳需 broker/account/position/pending verified＋explicit verifiedZero，aggregate holding 和 missing row 都不能填足。現無 pending producer：00865B 兩leg `SUBACCOUNT_UNVERIFIED`；00719B／00697B `PENDING_ORDERS_UNAVAILABLE`，各為 BLOCKED 0 張，sourceVector 如實 `UNAVAILABLE`。提供 port 讓 pure calculator 的完整 verified fixtures 仍能產生正張数，不硬寫永遠 BLOCK。
9. tradability 缺來源是 L2 `TRADABILITY_SOURCE_UNAVAILABLE`，不減張數、不改排序；限價改 ask1 tickfloor，固定張數仍需五檔累計深度；清單命中是 L1。USD/TWD only persisted/cache read，驗 same-day/source/timestamp，缺失 per-symbol block，不能拿資產換算匯率冒充 current threshold。
10. 一次讀 Task487 `GET /internal/market-data/intraday-candles/batch-read?stockCodes=...&tradingDate=...&asOf=...` container-internal bounded minute batch，指定三檔、當日和 frozen capturedAt（UTC instant、不得晚於 ext server now、臺北日期一致）；root 與 stocks/candles 全部欄位、decimal精度、30根上限、asOf/source request/completion freshness及失敗狀態完全沿用t487，不另造wire；保存原始 batch／品質／source hashes，future/uncompleted bars 排除。共享 pure policy 在 MEDIUM 回 NOT_APPLICABLE；minute unavailable 不屬 L0，不影響購債 gate。不得增加 9090 minute endpoint。

## 計算與收據

純 `DailyDecisionCalculator` 不依賴 Spring／DB／HTTP。依上述順序對三個 STRATEGIC_FUBON 候選與一個 EMERGENCY_CATHAY00865B 留所有 named rule receipts（ruleId/calculatorVersion/inputRefs/status/reason）。候選 status/action/lots 必自洽，支持 receipt 不得混用 adverse safety reason。未通過必要 gate 0 張、limitPrice/amountTwd null；反向／未知／資料缺失不能補0當PASS。其他symbol/market/short/sell為NOT_EVALUATED。

策略排序先只選所有硬閘門與 D195 合適價通過者，排序後只第一名配置strategy張數；其餘候選0張、null限價/金額並留NOT_SELECTED理由。第一名容量不足不得把剩餘額度分給次名。lots 是 policy/daily/weekly/individual/group/fee-inclusive funds/fixed-lot cumulative depth capacities 的最小值。國泰緊急與富邦策略獨立日額度，**同一 input 的兩leg 共用並扣回一次現金餘額**；同 code不同leg不混歸屬。限價以足覆 fixed lots 的最後 asklevel 為 ceiling，再以既有合法 ETF tick helper 向下取整；L2 tradability 則取 ask1，不因降級減量。amountTwd=lots*1000*limitPrice，费用另外扣资金预算，不重复进入amount。

D195數值分工與正式execution_policy完全一致：0.3%合適價比較未取整原始Decimal回檔；排序及國泰緊急兩張門檻使用百分比四位小數、HALF_EVEN（Python Decimal round）。例如−0.99996%→−1.0000%可兩張；−0.29996%即使取整−0.3000%也不通過原值0.3%門檻。

D17509:05 same-row positive integer volume<600、positive previousClose、price不同previousClose可不限制成交價updatedAt age，但仍需same-day timestamp與合格book freshness；11:40採普通 acquisition 5min/final limit10min，不能套12:00低量例外。未來 timestamp不合格。final content／input是同一 frozen capture，不能判算間重新抓行情。

## Persistence、lease recovery 與跨 slot budget

依482.0a drop舊 placeholder `srpp_decision_run`，另建 owner_user_id FK app_user、policy FK registry、unique(owner,date,slot)、UUID、完整input_jcs/inputhash/content_jcs/contenthash及timestamps。FINAL insert-once，沿用 reject immutable UPDATE trigger；不得同一 immutable表 UPDATE CAPTURING→FINAL。

另建 `srpp_decision_capture_claim`：unique identity、UUID、metadata、claimedAt、leaseUntil（30秒），短 transaction INSERT 後 commit，讓對手看見202同UUID。新 capture持有token generation，final transaction驗generation與lease尚有效才能insertFINAL＋deleteclaim；失敗刪自己的claim，L0無run/input/receipt半成品。replay precedence在現有FINAL metadata比較前不得因新環境政策不一致改變receipt。dead process lease expired：短transaction rowlock recheck無FINAL，沿用原UUID但換generation/lease，僅同metadata可recapture；舊generation不得finalize/delete新claim。requestbounded capture超過lease應release/failclosed，不能永久202。restart可replayFINAL；CAPTURING恢復有測試。

owner/date PostgreSQL advisory transaction lock serialize跨slot quota snapshot與finalization；same-day／same-week過往FINAL裡已建議策略/緊急張數算 reservation，不可11:40再次分配09:05額度。ledger以可驗證stable action/fill linkage跟reservation union去重；無link的其他fills保守另扣，不推定已成交恰好同suggestion。無法驗scope/channel/share則相應quota UNKNOWN並block；不得用max(sumfills,sumsuggestions)在無link時猜測重疊。wholeweekFINAL與confirmedfill原始來源及reconciliation receipt都凍結。同一owner其他slot正在capture時新slot不能在未完成reservation狀態搶分同額度；回202其自身claim或延後同scope capture並受lease bound。只認具名identity23505作並行競爭；其他DB错误500。

## Response／BFF／Swagger／SRPP同步

FINAL封閉root：schemaVersion、decisionRunId、status、created、authorityRevision、identity、inputSnapshot、decision、decisionContentSha256。authorityRevision必有serviceBuild/databaseSchema/calculatorVersion/policy原始hash；inputSnapshot含inputSnapshotSha256/capturedAt/assetSnapshotId/assetGeneratedAt/sourceVector；inputhash是完整凍結input，不只metadata。created不進contenthash；hash重算時也移除hash本身。完整 source/candidate receipts可由content引用但不能省略可達schema。

decision-only BFF validator驗200/201/202相符、身份與原request／resolvedowner綁定、所有nestedrequired/type/enum、flagsfalse、固定reportscope/eventNOT_BOUND、status/action/lots與0lotnull、decimal string正負范围、receiptinputrefs已存在及sourcehash、contenthash重算。202body/RetryAfter/UUID严格驗證；error只接受已登錄完整七欄且HTTP/code/status/instance/retryable一致。任何假FINAL或語義不自洽502 UPSTREAM_INVALID；BFF不可重算投資策略。event route保持現有行为。

YAML是正本，minorversion升級，evaluate增加200/201/202完整封閉schemas、合法examples、metadata409；Task487的intradaymetadata也由同一YAML作者合併避免撞檔。同步manifest test釘版本与statuses，重產repo/classpath/SRPP/docs三份Swagger字节一致。SRPP `policy_bundle.py check --purpose daily --consumer Codex` 重新核對；Swagger已有D166排除於bundle，不應擅改policyhash；若本任務必要改受hash文件/validator，先驗完整scope再rebuildmanifest／runtimecontract，保持D168等財務政策原意。兩repo依授權commit→no-ffmerge→push，交付hash与實際發布Swagger一致，不讓document先更新導致執行中mismatch。

## 驗證與驗收

- [ ] 新Swagger後舊metadata replay200／不同metadata409；兩檔同時合格只第一名、第一名容量1張次名仍0張、HALF_EVEN半位及−0.99996%／−0.29996%／四位同分golden。
- [ ] strict request、calendar-first、unsupportedpolicy/swagger、assetsL0零FINAL与零claim殘留。
- [ ] pure calculator verifiedpending／subaccounts正例真的ACTIONABLE，D195選擇／tie／20完成日／cash-dividend／fee-inclusivefunds／target/bondcap／dailyweeklyquota／linkedfilldedup／unlinkedfill保守計數／雙leg共享cash／D175邊界反例。
- [ ] current缺pending逐檔BLOCK但其他source/evidence真捕捉，minuteNOT_APPLICABLE、missing／09:05不足bar不改D130張數；同source更改其他gate必影響對應receipt，不能固定假FINAL。
- [ ] PostgreSQL20-way並行，claim202UUID／leasegenerationrecover／finalatomicity／跨slot预算／immutableUPDATE拒絕／restartreplay／metadata409，不能以H2/mock代替DB concurrency。
- [ ] BFFownerheaders/calleridentity/10sdeadline、incorrecthash/identity/receipt/flags/lots/status semantic rejection；既有eventtests保持通過。
- [ ] 20個台股交易日凍結同input（兩slots／noaction／missing／subaccount／dedup/capacity）用實際權威 `execution_policy.bond_price_selection` fieldgolden，diagnosticengine不作規則權威；只接受本spec明列保守來源gate和receipt補充差異，不宣稱收益更準。
- [ ] focusedMaven/backend/bff、OpenAPIruby/renderer、gateway/Tailscaledenymatrix、spec/schema drift，受影響Dockerbuild/recreate実际serve。运行中文案如實揭露 source缺失，public入口正常FINAL只能在完整L0下，缺L0仍503。
- [ ] regenerate `db/schema.sql`，runtimepublishedSwaggerhash＝三份docs，SRPP完整bundle/runtimechecks，兩repolanding。Task482原Gmailend-to-endaudit只由主工作建立新未寄draft完成MIMEreadback；本API不發信，也不得宣稱未做audit已完成。

## 完成報告

實作與 feature Docker 驗收完成；本報告記錄收尾提交前狀態，雙 repo 的 no-ff merge／push 與 main 再部署接續由同次工作完成，landing SHA 由 Git history 追溯。

- 新增純 `DailyDecisionCalculator`、typed verified policy、canonical input capture adapter、immutable FINAL／claim repository、decision BFF semantic validator；migration149 先拒絕刪除非空舊 placeholder。真 PostgreSQL 6 tests 包含 20-way 並行、受控 FINAL／claim 競態、lease recovery、兩筆真 FINAL 的跨 slot 預算及 migration guard。
- backend focused 33、BFF focused 37 tests 全通過且無 skip，最新 backend 正例 receipt 由 BFF 8 tests 再驗。20 個真正交易日日期×兩 slots 的欄位 golden 使用原始 SRPP `execution_policy.bond_price_selection` 產生合成對照；沒有 live actionable、真實績效或 Gmail／MIME 等價證明。
- 獨立 architecture review 含最後 schema artifact：critical 0、major 0、minor 0。16 個實作檔終稿 SHA256 為 `49672dd8b27d14fb970aacef27b5d18cbb262d36687f7ea8089e24d73d10ddad`。
- Swagger 1.24.0 已重產 repo／classpath／SRPP feature/docs 三份，SHA256 `15fe7fb043ea0002809bb12033b473cee0a069e5bd26f76ee05eabac3a1ae0a6`。SRPP Codex 與 Claude daily policy check 各驗 121 個受 hash 文件均 PASS，daily runtime contract PASS；Swagger 原已排除於 bundle，本次未改財務政策，bundle hash 維持 `919c7b79d79b62442063dac64f2aea14809d25ff75f7328147505013db70095f`。SRPP 舊提案文書已標示 historical，正式契約以新 Swagger 為準。
- 實際休市 evaluate 回 409 `NON_TRADING_DAY`、非法 request 400／media 415／非 POST 405／descendant 404，FINAL 與 claim 都為 0，日曆先行。受影響四服務重建與 schema112 全文 drift 通過；[runtime 報告](../../docs/validation/task487-488-runtime.md) 區分 live 邊界與 fixture 正例。
- 真來源仍缺 pending-order authority／00865B 雙子帳證據，相關候選如實 `PENDING_ORDERS_UNAVAILABLE`／`SUBACCOUNT_UNVERIFIED`、0 張；完整 verified fixture 可得正張數。本次完成來源捕捉、正式政策計算與不可變收據，沒有補造缺少的券商證據。D-168 中期固定 `NOT_APPLICABLE / SHORT_ONLY`，盤中 WAIT 不減中期張數。
- Task482 原 Gmail end-to-end audit 不屬本 API 交付，本次未建立或寄送郵件，也未呼叫券商下單；不得將上述測試當成原 mail audit 完成。
- 機械 B3 的一般 `IF NOT EXISTS` 規則不適用已審的 v1.149.0 一次性 placeholder 替換：它刻意遇非空資料即 HALT，SQL 不可任意改號重跑。工具僅對 exact path 與完整固定 SHA256 `dc346a635aa8b588ab6b6540a349c24ba2fa852da3693863ede18d64a9a216e4` 列為具名 CHECK，其他 migration 或本檔 bytes 變更仍 BLOCK；保留原 changeset／已執行 checksum，不修改已部署 SQL。
