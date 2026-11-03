# [t542] 美股 SRPP 計算 API、觀察移轉與雙專案發布

**對應 Requirements:** Requirement 199（以美東session身分讀取唯讀不可變美股擇時／戰術計算，資產端唯一保存US觀察）
**前置任務:** 無
**Liquibase changeset:** 新增獨立US tables；實作前再次查核未占用版號，不修改歷史changeset。

## 背景

現有report-calculations只支援台股09:05／12:00，台股HTTP200不能證明美股能力。基準兩專案遠端main為asset a5176ab2、SRPP bd8ec40，D-206／v2.55.0；所有發布身分仍須重新由工具產生。既有Codex-US帳為可核對歷史結果的空帳，Claude-US檔缺失，不能清帳／補空。Python core guard原有台北asof日期及固定16:00問題需修正為NY與官方半日市；投資條件不變。

## 要做什麼

- [x] 542.1 完成SDD機械檢查與另一唯讀subagent獨立spec審查；保留其他worktree同檔工作。實作由subagent負責；完成後另作diff-scoped arch審查。
- [x] 542.2 新exact GET `/api/public/srpp/us-report-calculations`，query只接受單值email?、consumer=Codex|Claude、market=US、sessionDate=ISO NY日期、reportSlot=US_OPEN_0945、兩個小寫64碼hash、calculationRunId? UUID。unknown／duplicate／blank／body／錯市場回400。RFC9457固定七欄，instance是新路由；400 INVALID_REQUEST，404 CALCULATION_NOT_FOUND，409 NON_TRADING_DAY／OUTSIDE_REPORT_WINDOW／POLICY_UNSUPPORTED／SWAGGER_MISMATCH／CONTEXT_IDENTITY_MISMATCH，503 OWNER_UNAVAILABLE／CALENDAR_UNAVAILABLE／CONTEXT_NOT_READY，502 UPSTREAM_INVALID，500 INTERNAL_ERROR。所有結果private,no-store。
- [x] 542.3 包schemaVersion1／apiScope US_REPORT_CALCULATIONS／status FINAL，包含identity(ownerUserId,consumer,US,sessionDate,US_OPEN_0945,policy,Swagger)、run UUID、calculatorVersion、capturedAt/publishedAt、displayReportTime、NY session open/close、frozen inputBundle/sourceVector及input/content JCS hash、固定CORE_SELL_GUARD與US_TACTICAL兩模組、tradeAuthorization=false。模組coverage COMPLETE/PARTIAL/UNAVAILABLE與逐檔有效等待／阻擋／NOT_ARMED／NO_CANDIDATES分離。缺數字null＋具名原因；完整OpenAPI閉合所有nested schema，BFF與Python獨立驗證來源、內容與identity。固定id讀取不得跨scope，GET沒有writer／capture／producer依賴，不因GET計算或外呼。
- [x] 542.4 America/New_York完整交易日、DST、半日市及跨台北日；已驗證官方年份之外fail closed。背景每分鐘tick只在NY09:40至10:45且regular session內發布，09:45前不供報告採用；NY09:45對應原台北21:45／22:45排程，沿用入口±300秒與60分鐘停止條件、不改automation。同步SchedulePublicBffController.JOBS排程登錄。官方early close來源：https://www.nyse.com/markets/hours-calendars 。不得手動觸發producer或偽造時鐘驗收。
- [x] 542.5 背景capture只讀既有資產／Redis／stock_price_history／配息／完整雷達；每標的一次detail、宇宙=正持有US∪全部US雷達∪已驗證明確觀察。各市場估值日按自己calendar；保留全量與coverage。USD、同regular session、五分鐘價格，獨立feed／時間／實質價差逐項保留證據或缺口。D108不要求／不讀五檔；business不外呼供應商。
- [x] 542.6 DB按owner/consumer/US/symbol保存current state，import marker確認帳真實存在，缺marker不可視為新空帳。離線匯入工具讀原ledger與原同軌anchor input/result核對input hash及重播結果；保留原始SHA、Python穩定觀察指紋、觀察起日／高水位／高點日／除息價基／證據。未知指紋拒絕；僅原legacy allowlist可銜接。完整政策hash或JCS不得代替Python stable watch fingerprint。Codex可驗真空帳，Claude缺失保持SOURCE_UNAVAILABLE。
- [x] 542.7 核心純計算與同版scripts/core_sell_guard.py同輸入等價：首次武裝且當日不能READY；創高持續更新且不重設起日；完整完成日高價、現金除息、最近三完成日KD兩日下降且在高點後、本輪KD恢復、1.5%邊界、雷達版本否決與missing。先更新有效價格觀察，雷達／KD不過仍保留創高。每輪stateBefore/stateAfter/source fingerprint/version append-only；鎖與版本原子更新，重複來源冪等，publication與state同交易，GET不更新舊包。
- [x] 542.8 戰術共用既有D186純計算元件與同版us_tactical_order_check.py parity，主RADAR路徑不套全球前10；FALLBACK維持原三情境2:1，兩路同向detail、牛市急跌止穩、RISK_OFF限制、原15%成本後空間、即時限價±0.5%與tick、0.25%／0.75%／2%上限、資金實質底線、國泰TWD桶、費稅、成交去重與原出場期限。目標／成本／帳本／資金缺正式來源逐項缺口，不補0或用verified旗標製造COMPLETE；GET不得傳此資料。D206研究論點及採納／等待／否決仍LLM，不固定排名或加分數。
- [x] 542.9 同步exact gateway/BFF/Security/Tailscale/前端拒絕與matrix變體、OpenAPI與三份Swagger mirrors、SRPP consumer schema/verifier與美股入口文件。SRPP逐scope優先採用已驗證API；有效BLOCKED不能後備解除。切換後US本機ledger禁止寫入，後備只使用已驗證DB包stateBefore與同證據唯讀，缺狀態不可用；台股ledger不動。禁止公網入口／廣泛路由／交易／寄信。
- [x] 542.10 全部適用測試與離線建置通過後，依renderer→sync→兩check限定dirty鏈與原policy/runtime工具產生最終bundle／authority／golden／insert-only SQL。兩專案feature commit、no-ff merge、push並核對remote後才執行registry；再從乾淨main及正式設定build/recreate必要服務、核對registry與JAR原bytes。schema新增後重產db/schema.sql並驗漂移；前置可用隔離測試DB驗schema，禁止先動正式部署。
- [ ] 542.11 從SRPP實際Python／主機以精確最終hash呼叫新US GET，保存HTTP原文及獨立verifier私人收據，公開repo僅摘要。另執行發布技能要求的唯讀連線檢查。時間不合法只能診斷，不當正式排程PASS；不重跑報告／不POST producer／不寄信／不交易。回報全部不可用欄位、未執行驗收及正式排程是否實跑。
- [x] 542.12 移轉owner須同輪report-facts+input receipt綁定原assets及core states/results、工具重算事實指紋並對原assets做L0，正式DB snapshotId→ownerUserId收據吻合；SQL再次WHERE asset_snapshot(id,owner_user_id)核對，不能只憑operator owner參數。缺鏈維持不可用，私人來源與SQL不提交公開repo。
- [x] 542.13 latest GET只在當前NY日期09:45至10:45且regular；同NY日期fixed runId可時窗外診斷，原包不變、不表示可採用。consumer本輪採用須入口time gate及60分鐘上限、capture和全部必要價格來源距本輪asof各不超五分鐘且無未來時間，舊包過期僅回退受影響scope。非當前NY日期400、休市409、未知日曆503。測試09:40包不得於10:44當fresh。
- [x] 542.14 用App automation工具同步實際22-00 prompt的美股證據片段並readback exact bytes/hash；將原US本機寫帳描述改API優先／同DB凍結狀態唯讀後備。保留原rrule/status/model、季節±300秒time gate、日曆no-op、原停止、Gmail及交易權限原文；不能只更新docs宣稱已接入。
- [x] 542.15 沿用現行Drive資料庫備份邊界：`ProcessBackupDatabaseProcess` 的原四個exclude-table-data不變，再加 `public.srpp_us_report_package` 與依賴package的 `public.srpp_us_watch_transition`。全部schema及canonical `srpp_us_watch_import`／`srpp_us_watch_state` 資料保留，不能新增凍結財務原文上傳或因懸空外鍵使還原失敗。只改必要排除與測試，不改排程／目的地／憑證，也不手動觸發正式備份／上傳。先獨立spec審查，再實作；以隔離PG dump/restore證明四US表schema皆在、package/transition無data、canonical import/state仍在且還原成功。

## 驗證

以下測試項為實作計畫，類名尚待建立：美股日曆夏冬令／半日市／休市／NY跨台北日；完整identity隔離、query拒絕、來源與內容hash竄改；狀態移轉有效／缺失／legacy／未知指紋；首次武裝／創高／1.5%邊界／KD恢復／除息／missing；重複與併發原子更新；Python/Java同輸入正反例；無候選與缺來源；flag-only COMPLETE拒絕；GET零副作用／舊包不變／台股回歸。所有合成輸入去識別，不提交私人ledger或API財務原文。

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test
ruby scripts/tests/docker-external-api-openapi-test.rb
bash scripts/tests/configure-tailscale-api-gateway-test.sh
python3 scripts/check-srpp-report-authority.py --srpp-root /private/tmp/srpp-us-api-20261104 --check
```

正式順序：兩專案merge/push/remote核對→registry exact row→乾淨main `docker compose -p asset-management build business-services bff api-gateway frontend`（僅實際變更服務）→`up -d --no-deps --force-recreate`相同必要服務→有界healthy及業務GET驗證→JAR全文比對→SRPP host精確hashUS API收據；不觸碰無關服務與正式secret。新增schema後依db/schema.sql檔頭重產，執行`bash scripts/tests/schema-sql-drift-test.sh`要求0。

## 完成報告

2026-11-04（Asia/Taipei）已完成程式實作、獨立規格／架構審查、適用測試、兩專案 no-ff 合併推送、insert-only registry 與正式部署。542.11 的實機診斷已執行；合法時窗的計算包與正式排程採用仍為 **NOT_YET_EXECUTED**，因此該項保留未勾選。

### 契約與實作範圍

- 新 `GET /api/public/srpp/us-report-calculations`，OpenAPI 1.27.0；query 為 email?、consumer、market=US、sessionDate（NY）、reportSlot=US_OPEN_0945、policyBundleSha256、swaggerSha256、calculationRunId?。兩個固定模組為 CORE_SELL_GUARD／US_TACTICAL；JSON schemaVersion=1，來源／輸入／內容 hash 與 owner/consumer/session/policy/Swagger 隔離。
- GET、錯誤路徑及讀取後備均不寫觀察、不觸發 producer／供應商／行情刷新；美股 CLI 拒絕本機寫帳。背景 NY 時窗發布、官方2026–2028日曆／DST／半日市、全量宇宙、每日 OHLC 原始來源、原1.5%／D181／185／186及資金上限均已實作。D206 研究／論點與採納判斷保留 LLM。
- 缺資料與有效阻擋由 missingSources、blockingReasons、gateEvidence 分開；單一來源缺口不取消另一合格標的，既有有效 BLOCKED 不可後備解除。CORE_SELL_READY 僅擇時通過，所有交易權限仍為 false。
- Codex 原始空帳的 ledger／同軌anchor／facts／L0與正式snapshot owner鏈已驗真匯入，原本機檔未修改；不是新造空帳。Claude原美股ledger缺失，未匯入、未借另一軌、未初始化空帳，仍為 WATCH_STATE_SOURCE_UNAVAILABLE。
- US package／transition 的備份 data 排除已嵌入正式 JAR；canonical import／state 與全部 schema 保留。僅在隔離 PG 驗證 dump／restore，沒有啟動正式備份或上傳。

### 最終身分與落地

| 項目 | 實際值 |
| --- | --- |
| 決策／政策 | D-206／v2.55.0；投資數值未變，新增 US API 整合 metadata |
| SRPP main／remote | `6972fdb5db9ed17305b3631d5728e2105ebb0b90` |
| 部署來源 asset main／remote | `fc0cf7f8e456365b1df6dc9ecd32ee5e48c5c88b` |
| POLICY_BUNDLE_SHA256 | `05390a63d43d7621a1b4c05c4100b3366030620b23451181270e7136297760fc` |
| model bytes SHA256 | `b414138ce25e9d9824964aaad4dbeb7bd3a70c4be8ced728c89f5b47034d69db` |
| manifest bytes SHA256 | `35ef5a49658f8e47284ffe232ff4c23046a090c70e3feb2c0d6f4afc21104ef0` |
| Swagger SHA256 | `a7ce2c890c437199334b98fb914626ac955d98fbe729ec6c9a498564d52550b4` |
| registry SQL SHA256 | `778ca8642d54b295f00bc17cccbad1d1d151a593f255f72abb1b96935f8bfbf6` |
| 運行 business JAR SHA256 | `2b654e84d5918f71114e91a0793c1a61c349e975b50d98cde64c2293e6fabd44` |

SRPP 正本安全 ff-only 同步，原有未追蹤檔案名稱與 bytes 保留。Registry 由乾淨 main 受追蹤 SQL 新增一筆，27→28，原27筆全文不變；新row的 bundle／formula version／policy document／formula manifest 四欄精確相同。運行 JAR 的 model／manifest／Swagger／registration SQL／policy document／migration 原bytes均與部署 main 相同，新US類及備份兩排除字串可讀回。

從乾淨main重建並force-recreate frontend→business-services→bff→api-gateway；前端先拒絕新路徑才啟新BFF。其餘五服務容器ID及image不變，正式env／mount／TLS未改。Tailscale僅新增新US一條，原24條及其他設定不變，現25條；source catalog目標27條的差距為原有 order-plan／symbol-rule-facts 兩條未掛載，這次未擴修。新US gateway與Tailscale exact GET已可達，frontend exact／child／matrix均404。

### 驗證證據及限制

- Backend適用回歸154項：0fail／0error；僅既有 `SrppReportRepositoryPostgresTest.existingReadOnlyCanonicalMarketFrameFitsActualEscapedWireLimit` 因未設定 `srpp.report.size-probe` 跳過。新US隔離PG11項均通過；新增與既有備份focused14項均通過、零跳過。Backend與BFF離線package成功。
- BFF新US＋舊TW回歸40項全通過；Python核心18組逐欄等價、D186原機械9組正反例，以及完整正式來源、資金、費用、阻擋加缺口、竄改、首次武裝／創高／1.5%／KD恢復／除息／跨日／半日市均有確定性測試。SRPP consumer/client/session/quote/報告guard、既有TWguard／財報及policy相關回歸通過。
- 同一個真正 Java publisher＋隔離PG產生包，通過 BFF typed schema／hash／identity及獨立Python verifier；使用本輪精確最終bundle／Swagger。此為 **合成整合診斷**，adopted_modules={}、formal_report_adoption=false，不代替正式API採用。
- SRPP實際主機／Python 9090連線檢查通過（日曆365日、TWSE日線21筆、台股報價23筆）；只證明連線／內容，不是美股計算包驗收。
- 2026-11-04 02:56:47 +08:00（NY 2026-11-03 13:56:47 -05:00），從SRPP正本以最終精確兩hash呼叫新US API，實回 **409 OUTSIDE_REPORT_WINDOW**，七欄problem schema、instance與private,no-store獨立驗證通過。HTTP原文、body hash與收據保存私人發布證據目錄，未上傳公開repo。沒有製造計算包、偽造時鐘、手動producer或重跑報告；正式計算包／排程採用 **NOT_YET_EXECUTED**。
- 既有22-00 App prompt已以合併來源的EVIDENCE片段更新並讀回；時段、原time/no-op/停止／Gmail／交易權限prefix、rrule、status、model、project/cwd均不變。App僅移除單一末尾LF，其他字元精確相同。此為設定部署，不是已實跑。
- 正式DB現為117表，schema.sql已從實際schema-only dump重產並逐位元防漂移檢查通過；不含任何資料。此次後續提交僅補schema與本驗收紀錄，不改JAR執行內容，不為文件重複部署。

### 尚未供給或未執行的 scope

Production capture尚無下列八種正式補充來源，對應欄位保留null／missing，不把範例或旗標當正式來源。數學與來源完整時的檢查路徑已有合成正反例，但未宣稱正式US包目前COMPLETE：

| 來源 | 不可用欄位／用途 |
| --- | --- |
| D186_CANDIDATE | 正式目標／合理價及依據、費稅／估值輸入、候選名目批次 |
| US_MARKET_REGIME | US基準完成日MA200／升勢／熊回撤／RISK_OFF正式判定證據 |
| US_TACTICAL_BULL_DIP | 近期急跌及止穩／反轉正式證據 |
| US_TACTICAL_POSITIONS | 戰術歸屬、成本、已確認fill、批數與曝險 |
| US_TACTICAL_PENDING | 未成交／預留、去重與額度占用 |
| CATHAY_TWD_US_TACTICAL | 國泰TWD桶／帳戶、實際換匯、資金與存款底線、最低佣金／平台費及交割日 |
| US_TACTICAL_EXIT_PLAN | tradeId、論點證據、失效條件、原12月覆核與明確延長接受紀錄 |
| US_QUOTE_CROSSCHECK | 可驗證獨立feed、USD第二價格／時間與實質價差 |

此外Claude缺原US觀察帳；原台股其他不可用模組（含薪資）不在修復範圍；公司研究與財報壓力情境仍依原LLM／共用工具。尚無最終版合法時窗HTTP200包、真正當輪直接L0與完整宇宙之正式採用收據，也未實跑任何新報告排程。下一次原合法時段由既有排程自然執行，不能提前給PASS。
