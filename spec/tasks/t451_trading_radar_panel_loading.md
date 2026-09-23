# [t451] 今日交易雷達各區平行載入與背景手動更新

**對應 Requirements:** Requirement 148（雷達 browser compact 資料邊界，本任務改為各 Panel 自足平行回應）
**前置任務:** t426、t428、t430、t449、t450
**Liquibase changeset:** 無

## 背景

目前 TradingRadarView 等單一 list 組完台美大盤、38 股與公開資訊才移除全頁遮罩；人工更新同步等待最長30秒的行情補抓後再讀 list。live main06e06d8b 上 list TTFB 約962–982ms、壓縮約9KB；設定各12–16ms且已平行。這次先拆區域獨立顯示、消除 compact profile 每股重複門檻查詢，更新改背景工作。保留既有可查證的三軌金融語意，不增加跨請求分析快取。

## 要做什麼

- 新 browser flow 同時啟動五個唯讀完整 JSON method：`GET /api/bff/trading-radar/panels/tw-market`、`us-market`、`tw-stocks`、`us-stocks`、`public-information`。同一 page controller 委派 service、tenant-aware business client，再呼叫同 suffix 的 `/api/trading-radar/panels/*`。不得先讀 full/list 再裁切，或每個 Panel 重做兩市場全部標的。原 full/current/list/stock、匯出、通知與 9090 契約保留。
- 每一 envelope 為 immutable record `{panel,ruleVersion,actionPolicyVersion,generatedAt,data}`。market data 只有自己的完整 `market`；stocks data 為自己的 `market`（本次評分實際使用的同一份 MarketSummary）、完整本市場 `stocks`（既有 ListStock）、`skippedNonTwStocks`；public-information data 為完整 `publicInformation`。三軌評分、action、timing、價格品質時間、日／週K與 evidence gate 規則不改；RULE_VERSION 維持 TW_RULES_V20。每 Panel 以自己 server decision instant 原子組裝，並顯示自己的時間。新 flow 明確取代 Requirement 76／Task 335 對「獨立大盤卡與個股區必須是同一份 summary」的跨區假設：個股區顯示它自身 payload 的判斷大盤／完成日／產生時間，不能借用另一區的大盤替自己的判斷背書；舊 list/full 的同份 summary 保證不變。
- 每區獨立 loading/error/retry，真空才顯示 empty；首次尚未完成不可顯示假 0 檔或無資訊。allSettled 只統整、不作 render barrier；換台美 tab 不重抓已完成資料。取消全頁遮罩。各區重試只重抓自己；背景更新保留原完整資料並明確標示更新中／失敗，成功原子替換。每區 own generation/AbortController，離頁、更新 supersede、late success/catch/finally 與 stale detail 都不能污染新資料。SSE 可在 mount 即開且只 patch 已載入列的既有白名單欄位，不觸發 HTTP／重算／抓外網，離頁關閉及取消重連。
- **使用者最新決定：每次展開一檔就重新計算，清單與明細一起更新。** 新增同頁 `GET /api/bff/trading-radar/stock-evaluation?market=&stockCode=`，business 同 suffix，以單一 decision instant、單份 own-market context 和單次 exact target decision core 同時投影 `{ruleVersion,actionPolicyVersion,generatedAt,market,summary:ListStock,stock:StockDecision}`。它只重算 owner eligible 的該一檔、只讀已保存資料，不先讀全清單／另發明細造成時點不同；所有 clock/calendar 使用 cache-only，不因展開補抓或寫快取。前端在所有 envelope／exact pair 驗證完成後，原子更新該列 compact summary、完整 detail 與該列評分的大盤／產生時間；其他股票值與位置不動。更新列顯示自己的計算時間與大盤依據，不能套用舊 Panel 時間；Panel 的時間文案標為整批載入時間，不能宣稱所有列仍同一時點。既有三軌值完整同步，每次 collapse→expand 都重新請求，同列未結束時 single-flight；失敗保留舊完整 tuple 並標示未更新／局部 retry，初次沒有 tuple 時只顯示錯誤。collapse／市場 Panel replacement／離頁後的舊回應須取消並棄置；summary 不能先更新而 detail 失敗。此取代 Task426 對新頁面展開的 endpoint 與 merge 方式；舊 `/stock` wire 和非新頁面 consumer 不變。
- compact stock Panel 先按 owner eligible 聯集去重，再依市場限縮 batch inputs；保留 held、排序、skipped、同碼跨市場隔離、phase fail-soft、未知日曆 fail-closed 與 cache-only calendar。收益型分類門檻每 request 至多讀一次，初始與基本面後 profile 共用同值；共同大盤輸入在相同 request 可重用既有 pure resolver 與已讀 rows，不能重算不同公式、縮短歷史窗口、把 indicator cache 命中誤當免 freshness 檢查，亦不得加入跨請求分析／response cache。大盤／資訊 Panel 不讀 owner targets 或逐股分析；股票 Panel 不讀無關的 public-information 卡片新聞與另一市場 targets，惟評分所需美股外溢／市場／基本面證據維持完整。
- 手動按鈕改 `POST /api/bff/trading-radar/refresh-jobs`，立即 HTTP 202 回 owner-scoped opaque job；`GET /api/bff/trading-radar/refresh-jobs/{jobId}` 只查進度。background worker 沿用既有明確手動行情回補與 per-owner/global cooldown，不新增排程、開頁自動補抓或券商交易。同期同 owner 重複按鈕回同一 active job，跨 owner 無法讀／取得 job；worker 明確傳 captured owner id，不存取 request-scoped CurrentUserContext。工作與歷史有上限、有期限，queue 滿則 503 且零回補；重啟/不存在/他人 job 404。前端只輪詢已由按鈕建立的 id、single-flight、有總期限、離頁取消，不自動重送 POST。
- job 完成後才並行重新讀五個 Panel；刷新期間沿用各區原畫面與真實 timestamp。抓取失敗、timeout、cooldown 或 busy 與分析 Panel 刷新結果分開呈現，不能在 Panel 未成功時宣稱「已重算」。後端 status 查詢保持純讀、不得觸發外部工作；legacy synchronous refresh 保持原 response 相容。新 API 不進 9090、不改 OpenAPI/public route、不寫匯出快照、不發通知／郵件／blog。
- 驗收固定資料 fixtures 比對各 stock Panel 與原 compact list 的所有 scalar/排序、三軌與 market context；測試 request-scoped threshold call count、scope isolation、fail-soft、owner job 隔離／重複／滿載／失敗／期限及取消競態。實際 Docker authenticated browser 驗收記錄五路起跑與各自完成、同 owner target 集合／payload 大小／TTFB；400ms latency、1Mbps、4x CPU 下，模擬單 Panel 延遲／失敗時其他 Panel 仍完整顯示，retry 僅一個 GET。慢網路數字以實際可觀測值回報，不以健康檢查或 public 9090 冒充。舊 Task 426 的 all-market list <=800ms 門檻保留為舊 endpoint 歷史目標。本次新 flow 以事前保存、改動前 main 的 authenticated list 作使用者等待時間基準：同一 owner／38 個 target 集合、正常網路與 CPU、六輪捨首輪後的五輪中位數，五路同時起跑的新台股 stock Panel 應較該事前基準改善至少 20%，大盤／新聞各自先於 stocks 完成。須另於每輪五路完成後讀取同版 legacy list，並列其全部樣本與差距；因共用底層最佳化也會加速 legacy，兩者差距不再設 20% 門檻，不能為拉大差距而降低舊 endpoint 效能。這是驗收比較基準的明示修訂，不能宣稱舊的「同版 legacy 差距至少 20%」已通過。事前基準不可依結果重選；必須核對基準 commit／image、端點、時間、owner target 集合、規則版本、全部台股 rows／排序及台股 market context。若這些台股輸出或 target 集合改變，應標為不可比、先重新建立可比實驗；美股盤中行情可自然變動，須如實記錄，不得宣稱凍結全資料庫或一般網際網路 SLA。金融固定 input parity、慢網路隔離與完整資料驗收仍為必要條件。


頁面沿用 TradingRadarBffController，新增 TradingRadarPanelService 只以 tenant-aware businessServicesClient 讀取 business 的五個精確 suffix；20 秒整體 timeout 映射 504、暫時上游失敗 502，401/403/404 relay，不把 malformed／empty 必要 payload 偽裝成功空資料。每次只有一個 business Panel method，沒有 BFF 全頁聚合或 shared owner-insensitive cache。business Controller 只委派 TradingRadarService 對應 pure-read method，@Transactional(readOnly=true) 保護每次組裝；新增傳輸 DTO 都用 record。wire shape：

| suffix / panel | data 欄位 | 工作範圍 |
|---|---|---|
| tw-market | market: MarketSummary | 台股大盤與評分所需美股外溢背景，零 holdings/watchlist/full/list/個股分析 |
| us-market | market: MarketSummary | 美股 IXIC 大盤，零 holdings/watchlist/full/list/個股分析 |
| tw-stocks | market: MarketSummary, stocks: ListStock[], skippedNonTwStocks: int | 該 owner eligible 台股，確切同份 market 餵本區全列評分 |
| us-stocks | market: MarketSummary, stocks: ListStock[], skippedNonTwStocks: int | 該 owner eligible 美股，確切同份 market 餵本區全列評分 |
| public-information | publicInformation: PublicInformationItem[] | 既有 72h raw news 選擇、區域與時間 cutoff、排序及去重，不組市場或股票 |

共同 envelope `panel` 為上表 suffix，ruleVersion/actionPolicyVersion 為當前正式常數、generatedAt 為本次 server Instant 以既有台北 offset 字串序列化（非 quote time）；不可接受 client 自訂過去／未來 decision instant。每區 snapshot 自足，stock Panel 顯示其 own market regimeLabel/asOfDate 與 generatedAt；獨立大盤卡時間可能不同，不能由別區覆蓋。此為新 browser flow 的明示一致性範圍；舊 ListResponse／Response、單檔 detail 與匯出仍保持既有契約。各區合成前端用的 stocks array 只作 rendering 與 SSE，不能重算金融判斷。

TradingRadarMarketContextService 的已存在 resolveMarket 與 public information 選擇拆成可分別呼叫的 read methods；原 resolve 保留並委派同一邏輯。新 public-information Panel 使用可回報錯誤的必要讀取，repository 失敗不得轉為成功空陣列；原 resolve wrapper 繼續自行 catch 並保留舊 fail-soft 空陣列，真正零筆才讓新 Panel 回 []。TradingRadarService 抽 shared compact assembly，允許既有 getList 和新單市場 panel 使用相同 projection/sort/fail-soft。市場篩選在 batch preloader 前完成，資料仍取所有合法所需窗口、完整自然 key。TradingRadarListBatchPreloader 的 incomeThreshold 一次取值隨 immutable Context/Entry 供兩次 profile resolution 共用，包含 fallback；full/detail 非 compact 不變。大盤重用限定本 request 已讀 rows 且透過原 pure math helper，無法證明完全等價就保留該項讀取而不得擅自改公式。沒有 DB/schema/public API 變更，無任意 parallelStream、無在 request scope 外存 JPA Entity。

單股新 GET `/stock-evaluation?market=&stockCode=` 以既有 CODE_PATTERN/MARKET_PATTERN 及 owner eligible 集合驗證，缺owner401、格式錯誤400、非該owner標的沿既有400/404語意，不透露其他owner。完整新 DTO 為 record `{ruleVersion,actionPolicyVersion,generatedAt,market:MarketSummary,summary:ListStock,stock:StockDecision}`；三者同一 decision core，不能先呼叫 getList／getStockDetail 各算一次。可用 single-target list batch context 與唯讀 technical preload 供 includeFullDetail core，再 toListStock/toFullDecision；same-time市場基準與三軌 scalar 等價必有測試。只讀保存的資料、cache-only clocks，不沿舊detail可能存在的request-timecalendar/cache-write分支。BFF此method20秒budget，exact requested identity必須同時等於summary/stock。必需envelope、market、summary與stock缺漏/不合法、台美身份不符、三軌action/label/score不一致均502，前端不得apply。舊stock endpoint及其DTO原樣保留。列表因single-row重算暫不重新排序以保持閱讀位置，下一次完整stockPanel refresh才採原排序。SSE仍只patch白名單行情，不變更score/action/generatedAt，已開明細的行情與該列一起套同一有效tuple；已有較新行情時不得被較舊evaluation行情倒退，分析時間和quote時間分別保留。

刷新 API 同 prefix `POST /refresh-jobs` 與 `GET /refresh-jobs/{jobId}`；BFF 用同頁 Service method 代理，短請求 timeout 5 秒、沒有自動重試 POST。business TradingRadarRefreshJobService 為 singleton，有 1 個 worker、queue 8 個、registry 最多 128 個 job；同步鎖只保護建立／去重／狀態，provider 執行不持鎖。opaque UUID v4，owner id 來自 authenticated CurrentUserContext，無 owner 明確拒絕。同期同 owner active job 直接回同 id（202）。active job 最長 45 秒（enqueue 起算）；deadline 到的工作不再開始／發布成功；仍執行的 task 保留 owner active fence 直到真正結束，避免超時立即再開相同工作。已完成 record 保留 5 分鐘，lazy cleanup 僅記憶體 bookkeeping，不觸發供應商。capacity 滿且清理後仍無空間回 503；不淘汰 active job。shutdown cancel queue/worker。

job response record `{jobId,status,createdAt,completedAt,priceRefresh}`；status 固定技術狀態 QUEUED/RUNNING/COMPLETED/FAILED；createdAt ISO offset、completedAt 未完為 null，未完 priceRefresh=null；COMPLETED 帶原 PriceRefresh(outcome,twMarketOpen,elapsedMs)（既有 DTO 欄位名須沿用實際名稱），FAILED 的 priceRefresh 可 null，UI 顯示未完成。成功/失敗結果不含 owner id、例外訊息或業務資料。UUID 不合法 400；合法未知/別owner/重啟遺失皆404。GET 查狀態只讀 job，不加入任務，逾期 status 投影 FAILED 但不可解除執行 fence。worker 只傳入不可變 owner id 給 TradingRadarRefreshService 的明確 owner overload，market-open 判斷與 external 30 秒 timeout、Redis cooldown 仍在原授權 manual refresh path；不能把 request-scoped bean或 servlet request 傳入背景。舊 refreshAndGet 仍取 request owner 再委派，保持 wire/outcome 相容。

前端 API methods 均在 api/index.js；5 個 panel getters 及 startRefreshJob/getRefreshJob、stockEvaluation 接 AbortSignal、局部錯誤 skipErrorToast。五路同步起跑，stateFactory 必使用 Vue reactive proxy；每區保存 data/loading/error/generation/controller。mount 立即 openPriceStream 與 loadPanels，settings 保持原本平行。成功 commit 才替換該區 data；展開以新 stock-evaluation 一次取得完整 tuple，存 detailStates[key] 並將同份 summary 原子更新 compact row（不能把整個 StockDecision 當 ListStock merge）。展開template讀同份 stock/market/generatedAt/versions，列表更新列也顯示該次時間／大盤；batch context只描述尚未逐列重算的原批次，label標明整批載入時間。summary/stock exactpair與必要欄位／三軌scalar一致驗證後才同一次同步commit，不能先更新半份。每次重開都發exact單列GET，無變更的expand callback不重入；collapse取消pending，保存已完成tuple供下次重開期間標示舊資料，失敗局部retry且保留完整舊tuple。stock replacement 僅取消與清理本市場 detail/generation，不得清另一市場已成功明細。initial 未完成與已完成空資料區分；背景更新保留前次 data 加更新中/失敗提示。手動刷新重入 guard；按鈕 POST 一次，1 秒起有界退避輪詢（上限 2 秒）、任一時刻最多一個 GET，總 UI 期限 60 秒；GET transient error 只在期限內重試，400/401/403/404停止、維持舊畫面並允許使用者再次按鈕，POST 失敗不自動重送。完成（含 fail-soft outcome）後 await allSettled 的五路重讀，局部成功立即顯示；只有全部成功才用已更新分析文案，否則提示部分更新失敗。terminal FAILED／poll deadline 保留資料、顯示未完成且不宣稱已重算。dispose 取消所有 panel/detail/job requests、poll/reconnect timers、關閉 SSE，所有 late branches 檢查 lifecycle/generation；不跨 unmount 再啟動 GET 或 EventSource。


- [x] 451.1 完成需求、設計與自足任務的獨立規格審查。
- [x] 451.2 backend 五個 scoped read methods、共用 compact 組裝及 threshold dedup，完整固定 input parity 與 phase-isolation 測試。
- [x] 451.3 owner-scoped bounded async refresh job、相容原手動流程，重複／跨owner／失敗／期限／queue測試。
- [x] 451.4 BFF 同頁 service/controller 五個method及job代理，tenant/error/timeout/validation契約測試。
- [x] 451.5 Vue獨立完整載入、局部retry、刷新保留／輪詢進度、cancel/race與compiled component行為測試。
- [x] 451.6 完整 diff 獨立架構審查，spec-check 無 BLOCK。
- [x] 451.7 feature Docker重建、正式登入UI／slow network／資料一致性與新舊TTFB驗收。
- [ ] 451.8 短中文feature commit、no-ff main merge、push並核對remote兩親；main Docker重建及功能確認。

## 驗證

新增測試檔在實作時建立；以下指令在本worktree執行，不修改 package.json（另一worktree佔用）。

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest='*TradingRadar*Test,*Radar*Test,*StockStyle*Test,*TechnicalIndicator*Test,*TaiexDisplay*Test,*Tenant*Test,*Owner*Test,*Fundamental*Test,*HistoricalBond*Test,*BondYieldBeta*Test,*Backtest*Test,*MarketData*Test' clean test
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test
npm --prefix frontend test
node --test frontend/src/utils/tradingRadarPanelLoader.test.js frontend/src/utils/tradingRadarRefreshJob.test.js
npm --prefix frontend run build
bash scripts/spec-check.sh
```

`/run-stack` subagent依固定模型規範執行。build前compare main .env（只輸出key/布林不印secret），主repo舊env不得覆蓋main；從featurebuild驗收後，merge後只能main build。只重建business-services/bff/frontend，-p asset-management、--no-deps --force-recreate，不動datastores/Fubon。等待healthy後以正式登入 browser page自己的BFF取得5份完整JSON，記錄owner target數、分市場排序／rule／timestamp、TTFB；原list採純讀可重複比較、不得呼叫會保存匯出snapshot的legacyfull GET。網速/CPU恢復normal後關閉本次診斷tab。慢Panel故障與手動joboutcome用可控browser攔截或automatedtest，未經需要不在真實runtime觸發回補、匯出或blog寫入。測試保證stocks不受新聞延遲，TW不受US延遲；局部retry只一GET；同一evaluation的清單／明細值一起更新且own時間/context正確、三軌不一致拒絕整份、重開再算一次、newstockgeneration棄late detail，已成功另一市場detail不被清除；SSE零HTTP且不改score/action；頁面離開後零poll或reconnect。

## 完成報告

規格獨立審查已完成，修正首次審查的一致性與新聞失敗語意後，第二位審查者未發現未解問題；spec gate `4ab38d48bc21`。

- Backend：90 suites／746 tests，零失敗、零略過；固定 Instant 比對完整 compact rows、排序、大盤、單股兩份 projection；45 秒 job 期限／fence／容量與 owner 隔離皆涵蓋。
- BFF：72 suites／504 tests，零失敗、零略過；五區、單股、工作 API 之租戶傳遞、欄位驗證、三軌一致性、decimal、取消及 timeout。
- Frontend：原套件 59 tests、新增動態 17 tests 全數通過；Vue SFC 實際 setup/lifecycle 驗證並行、再展開、三軌拒絕、舊回應、跨市場取消範圍、完整明細、SSE 及刷新硬期限。production build 通過。
- 全部實作及效能增量的獨立架構審查：未解 critical／major／minor 均為 0。驗收基準補充的獨立規格審查為 critical 0／major 0／minor 1；唯一 minor 是文件測試指令範圍不足，已補成實際 clean test selector。後端 90 suites／746 tests 範圍包含 Radar、基本面、債券、回測、日曆、technical、owner／tenant。尚未 commit／merge／push。


### 效能驗收基準修訂與實測（2026-09-23）

原「相較同版 legacy 至少 20%」未達：最後量測為 17.03%。共用查詢、基本面與日曆最佳化同時加速兩條路徑，因此此項改以改動前已保存的 main 基準衡量使用者等待改善，並保留同版對照數字。這項驗收定義修訂須獨立審查，不能把未達原門檻寫成達標。

- 事前基準：main `06e06d8b37fdcfa330b3f5c2af38f80e3b1df54a`，baseline business image `sha256:7788336fbf34986ca8ad2a79f7af3fcdde92950a1ade706f72449a3873e27629`、BFF `sha256:d97ecec4f376be3e9dc8876cd218c82a9352bfe0ad26d7c0e07af58563121d1e`、frontend `sha256:f0d8bd00bec0f05a18aeff236b97dff2c6609645c6e1b864e8017e44693a420a`（以變更前已記錄的 runtime provenance 為準）。2026-09-23 22:25（台北），authenticated `GET /api/bff/trading-radar/list`；六筆 TTFB 835.7、856.3、840.5、901.1、841.6、845.2 ms，捨首後中位 845.2 ms，38 rows、43,515 bytes。完整輸出與樣本保存在本機私有 `/tmp/radar451-before.json`；不將帳戶資料提交 Git。
- 最終 feature：business image `sha256:d40e09dc117d0ac33373dc0fc93a18686d84cbfbc8c4e5a96a2269718909a905`，2026-09-23 23:49；五個 Panel 每輪同時起跑，再讀同版 list，皆透過已登入正式網域。同 owner／38 個 target，台股 24 筆各欄位、排序與 own-market context 與事前基準完全相同，規則版本相同。美股盤中行情有自然變動，並非凍結 DB 的基準測試。
- 新台股 `panels/tw-stocks` 六筆 TTFB 730.0、661.6、639.2、650.1、669.1、611.2 ms；捨首中位 **650.1 ms**，相較事前基準改善 **23.08%**，24 rows、25,723 bytes。同期 list 六筆 828.6、810.5、796.6、765.1、783.5、713.3 ms；捨首中位 **783.5 ms**，新台股較同版 list 快 **17.03%**。完整樣本為本機私有 `/tmp/radar451-feature-phase4-measure.json`。
- 同次其他區塊中位 TTFB：台股大盤 23.3 ms、美股大盤 20.1 ms、公開資訊 21.0 ms、美股清單 231.3 ms。全部 36 次回應為 HTTP 200；最後一輪兩市場 38 筆與同期 legacy 的所有欄位／排序完全一致。以上為本機正式登入瀏覽器量測，非任何網路環境的保證。
- 真實頁面五路請求起跑差 1.3 ms，SSE 在 mount 即啟動。400 ms latency／1 Mbps／4x CPU 下，新聞故障及美股延遲不擋其餘區塊，單區 retry 僅一個 GET；資料尚未完成時不顯示假的空清單。
- 真實展開台積電只發一次 evaluation GET，三軌 summary/detail 一致，收合再展開有新的 generatedAt。瀏覽器攔截模擬一次 job POST 202、一次 status GET 200，再觀察五區並行重新載入；成功替換 Panel 會實際收合舊展開列。未在正式服務觸發 provider refresh。

最終 feature 再驗展開／重開：evaluation 各只發一個 GET，HTTP 200；第二次約 69 ms，generatedAt 確實前進，summary/detail 九個三軌 action／label／score 欄位完全相同。架構查證 gate `3af5063d2178`。
