# [t492] 系統資訊 AI 判斷流程：雙軌每日執行紀錄與流程圖

**對應 Requirements:** Requirement 190（按日期、Claude/Codex及早午盤分開查詢實際執行歷程）
**前置任務:** t491（必須先完成 Java25/Boot4.1.1 的實際部署驗收，本文只新增觀測功能）
**Liquibase changeset:** v1.153.0-ai-flow-event-log.sql（實作前再查所有 worktree／master 保留號）

## 背景

使用者要求在「系統資訊」新增「AI 判斷流程」，記錄每日09:05早盤、12:00午盤的整個實際執行過程，按日期查詢，且Claude與Codex雙軌分開。並明確授權必要時新增9090 API供排程回報詳細流程。使用者另要求流程紀錄只保留90天；保留今天及前89個台北日期，以run.reportDate為基準。第一版採實際事件紀錄與ECharts流程圖，不引入Flowable、不調度LLM；AI流程事件不屬金融authority，不能據此解除historical reservations、認定order/fill/交割、改變計算包或financial state；不改投資政策／金融計算／交易日閘門／Gmail權限。Flowable若未來需要流程控制可另提任務。

使用者最新範圍優先：本次不得修改 SRPP 專案任何檔案、規則、validator、prompt、排程或遠端 skill；SRPP 的主動詳细回報接入另日再做。新增觀測契約必須與既有金融 Swagger 身分隔離，保留現有三份金融 Swagger bytes/hash 和全部原金融 route/schema；不能假稱雲端略過或讓明晨舊 SRPP gate 因本次觀測路由追加失敗。實作前查證獨立 observation OpenAPI supplement 與 union route parity 方案並在正式規格明文寫入。今日 Claude 遠端 raw trace 若沒有可讀來源，須據實顯示缺口，不能以本機舊 artifact 宣稱完整遠端流程。

已查證來源：Codex exact automation IDs `codex-srpp-09-30`（顯示codex-srpp-09-05）及`codex-srpp`（顯示codex-srpp-12-00），codex-dev.db automation_runs.thread_id可連state_5.sqlite threads.rollout_path與JSONL的公開工具call/output、task_started/task_complete/final。PENDING_REVIEW不能代表仍執行。Claude exact IDs `srpp-0905-claude`、`srpp-1150-claude`已遷遠端，本機disabled/lastRunAt不能證現在遠端執行；data/output/daily_runs的result/timing schema不統一，且部分無完整rawtrace或exactrun linkage，必須誠實標示coverage／自報／未確認。

## 要做什麼

- [ ] 492.1 新增系統資訊menu「AI 判斷流程」path `/ai-flow`，ADMIN限定，Vue route requiresAdmin=true與BFF ADMIN check一致。頁面沿既有日期選擇器/卡片風格；查詢條件date(ISO台北日)、consumer(Codex/Claude，必選、預設Codex)、period(早盤/午盤/全部)，分別展示每軌真實run與多次attempt。歷史11:40顯示「午盤11:40（歷史）」；不得把歷史1140改成1200。無資料顯示「尚無已記錄執行」，不等同沒執行或略過。不自動重跑、不執行金融操作。每個run可以開流程圖、時間軸、節點明細。
- [ ] 492.2 browser只經api/index.js的專屬 `/api/bff/ai-flow/runs?date=&consumer=&period=`及 `/api/bff/ai-flow/runs/{runId}?date=&consumer=`。BFF有獨立aiflow controller/service並委派business `/api/ai-flow/runs`及`/{runId}`，不直查DB／rawtranscripts／hostfiles。ADMIN驗證後以configured-admin顯式owner查相同business讀源；禁止caller ownerId/email/header spoof。consumer是SQL filter必要條件，任何detail需該owner授權及不可混用另一consumer事件。日期按run.reportDate，不按receivedAt，支援跨日完成。日期只接受真實ISO日期；period protocol `MORNING|NOON|ALL`。backend/repository讀取零寫入，分類/排序/摘要/state/layout於service/BFF，frontend只render。
- [ ] 492.3 新增唯一 exact `POST /api/srpp/ai-flow/events`（25個9090 method/path pairs＝20GET+5POST），只記錄事件。專用X-AI-Flow-Token producer credential，三個env AI_FLOW_CODEX_TOKEN/AI_FLOW_CLAUDE_TOKEN/AI_FLOW_COLLECTOR_TOKEN於business/BFF一致且互不相同；缺/空/錯/多值token401且無owner/bootstrap/DB I/O，method非POST405、非exact/matrix/descendant404。與mail token及金融寫入隔離。至少Codex/Claude token各自綁定consumer，localcollector另token可觀測兩軌；來源可信度依credential角色限制，不能讓schedule caller自稱rawapp-observed。BFF與business雙重核對grant，清除callerReactoridentity、不接受payload ownerId/email/X-User override，以configured-admin owner保存。空configured token則功能failclosed；secrets不在git、stdout、raw event或UI。記錄失敗不觸發金融API重跑、不改LLM建議。
- [ ] 492.4 POST content-type application/json，body<=256KiB，batch events1..100。strict JSON任何深度duplicate/unknown field拒400；idempotent stableUUID runId/eventId。schemaVersion=1，run metadata immutable：consumer、reportDate、reportSlot(09:05|12:00|11:40 historical)、sourceKind(CODEX_ROLLOUT|CLAUDE_ARTIFACT|SCHEDULE_EVENTS)、sourceRunId opaque<=200、automationId exactknown、startedAt ISO offset/null（unknown不得猜）、workflowVersion<=100。runId衝突metadata409 RUN_METADATA_MISMATCH；同owner/source/consumer/sourceRunId須唯一，重送不得另建run。trace身份由真exactrun/session或明確artifact hash建立，不能把相近檔名拼成某個app執行。artifact-only與unlinked狀態須顯示。
- [ ] 492.5 每event immutable：eventId、sequence nonnegative int、stepId opaque<=100、eventKind protocol START/END/OBSERVATION/TERMINAL、status protocol RUNNING/SUCCEEDED/FAILED/STOPPED/SKIPPED/UNKNOWN、occurredAt ISO offset/null、title<=100、summary<=500（公開操作/判斷摘要）、operation(method/path且不含query秘密)、reasonCode<=100/null、attemptNo positive int、parentEventIds<=16 UUID、relation CALL_RESULT/DEPENDS_ON/BRANCH/NEXT_OBSERVED/null、evidenceRefs<=10(role/id/hash，不含rawcontent)、reportedOutcome optional(COMPLETED/PARTIAL/INCOMPLETE/STOPPED_L0/STOPPED_CONTRACT/SKIPPED_NON_TRADING_DAY)只供自報。source/hashes/id字段均有閉合schema與用途說明（hash為64位lowercasehex、id為opaque無URL/路徑），schema不接受任意JSON map。不能收錄私人思考鏈、rawcommand、authorization headers/env/token/完整API/MIME/帳號/持股/金額；host採allowlist摘要，backend還拒絕未知與消毒可疑summary。
- [ ] 492.6 DB append-only run/event兩表，owner FK、unique(owner,consumer,source_kind,source_run_id)、PK(owner,runId)與event PK(owner,runId,eventId)，reportDate/consumer查詢index。canonicalJCS與sha256穩定存證（immutable JSON是歷史snapshot具名正規化例外，不另外copy相同事實到derivedcolumns），同event相同canonical payload replay200，異內容409 EVENT_CONFLICT，新event201。全batch atomic，parent需同run且已存在或同batch；missing parent409 MISSING_PARENT，cycle/selfloop400 INVALID_GRAPH，不依receivedAt亂連。不能以覆寫event顯示retry；每attempt獨立。持久化UPDATE一律由immutableguard拒絕；DELETE只准已過90天的bounded retention cleanup與FK cascade，由DB guard按Asia/Taipei cutoff限制，不能刪未過期run/event。使用者已明確授權90天自動清除。不可存計算的duration/state兩份；read時由事件導出。
- [ ] 492.7 回應strict DTO record，POST回runId/inserted/replayed/eventIds；混合replay+new201，純replay200；503配置或owner來源未ready、502下游無效、504boundeddeadline，完整application/problem+json(code/status/detail)。BFF不把後端失敗改成功或隱藏upload缺口。source observation executionState(STARTED/ENDED/UNKNOWN)、reportedOutcome(自報)、verifiedReportOutcome(未有獨立receipt時UNCONFIRMED)與traceCoverage(FULL_OBSERVED/PARTIAL_OBSERVED/SELF_REPORTED/RAW_TRACE_UNAVAILABLE/UNLINKED)分開。task_complete+final只證ENDED（現機JSONL assistant response_item.phase=final_answer；也兼容已知channel=final schema，不能僅猜channel而漏掉真的final），Gmail草稿和MIME／validator要另外佐證；result.json COMPLETED不能直接升級verifiedReportOutcome。未配對call-output、缺terminal、尾端半行、missingchildtrace都標partial/未確認。
- [ ] 492.8 沿用ECharts graph series、不新增graph套件。service/BFF提供nodes/edges/layout/presentation；箭頭標示依賴、分支、call-result或「接續紀錄」，時間先後不冒充因果。實際記錄的loop/retry/parallel分支各節點，無預填理想流程。顏色+文字顯示status（不能只色辨），點選節點顯示時間/耗時、API path/status/reason/evidence角色與hash、公開判斷摘要。支援縮放/拖曳與長流程可讀性；BFF另提供整輪elapsed及按實測配對step耗時排序的slowSteps（未知/null不排名），公開耗時是觀測證據、不推測LLM內部思考或未觀測平行等待；完全不v-html/執行輸入BPMN/JS。查詢失敗維持errorstate、不顯示前一日期資料為當日。保留載入中、空、partial／未確認狀態。
- [ ] 492.9 新增host readonly collector `scripts/collect-srpp-ai-flow.py`與producer CLI（共用傳輸庫可同檔），支持--once/--date/--consumer/--dry-run/--endpoint/token-file，預設canonical local sources與loopback9090。只讀上述exact IDs的appSQLite/rollouts及本機SRPP artifacts（僅讀；絕不改 SRPP 原檔），不讀金融API/Gmail、不修改appSQLite/SRPP、不控制投資排程。SQLite URI mode=ro/schema introspection/epoch-ms固定+8，來源path限canonicalroots且rejectsymlinkescape。Codextool call/output按call_id pair、排除reasoning/system prompt/私人內容。事件IDs以stableUUIDv5(sourceRunId, sourceEventId/kind)；collection/incremental重播不重複。既有result/timing僅分別匯入selfreported證據，不能透過mTime或相近輪次串接為raw apptrace。
- [ ] 492.10 collector以既有真實execs匯入有界歷史窗口驗收date/consumer queries，未啟動任何dailyreport；每次--once掃最近可設定窗口（最多90個台北日期；expired localspool先刪，禁止回灌過期run）並bounded100-event chunks，傳輸timeout2秒/單輪不無限重試；失敗寫獨立localspool，原子rename，重送保留同IDs/hash且serverack後才完成，缺record不能冒充成功。host background job只收集/上傳觀測資料、與SRPP投資排程分離；啟用後readback證明service/job指向main版本、token僅0600檔且權限0700目錄。新schedule直接發事件的CLI先localspool；任何早盤/午盤criticalpath仍必須先完成既有local calendar gate，invalid/closed日只本機狀態，collector可事後獨立匯入既有停止證據。不得新增日報monitor/heartbeat、延長報告時限或為記錄進行下游金融讀取。Claude遠端詳細回報若無native部署/真receipt不得聲稱已接通，仍需顯示RAW_TRACE_UNAVAILABLE；API與CLI提供future可用詳細回報介面。
- [ ] 492.11 同變更同步nginx exact/method/bodylimit、BFF security/admin &producerfilters、frontend exact404/matrix deny、Tailscale exact handler與mock contract、獨立 observation OpenAPI YAML閉合schemas/responses/examples/security、OpenApiRouteCatalog及API error operationcatalog/seed若新增此route觀測分類。既有金融OpenAPI與三份generated金融Swagger均不變；觀測補充與原金融契約union須恰好對應25 method/path pairs，新增AI觀測契約有獨立版本/hash，不參與金融 PublishedSwaggerIdentity。依使用者不動SRPP指令明文限縮例外，renderer原金融 --check仍真實三份一致；不得偽設CLAUDE_CODE_REMOTE。同步CLAUDE/tech/structure current-route及觀測契約例外、docker-compose/.env.example dedicated tokens與hostcollector安裝/操作說明。更新db/schema.sql真runtime pgdump表數117（實際重算，不盲填）。
- [ ] 492.12 全套與必要新增測試、獨立archaudit、Dockerbuild/recreate變更service、真實日期/雙軌資料API+UI驗收、token/tenant/error邊界、runtime源/健康/TLS不變證據，feature→main no-ffmerge→push；main重新部署與收集器正式readback。不得留下只有API/空頁、未有實際收集路徑的半成品。

- [ ] 492.13 固定保留今天及前89個台北日期（cutoff = LocalDate.now(Asia/Taipei).minusDays(89)）。DB run.report_date < cutoff即過期（DB INSERT guard同樣拒絕過期date；未來date回400 INVALID_REQUEST），read立即filter，detail expired返回410 RETENTION_EXPIRED，POST首次／重送皆410且零寫入；今日/跨日完成以run.reportDate決定。backend每天台北00:00及啟動時有界分批purge，刪run並cascade event，無使用者刪除API，service/DB同時限制cutoff。collector自有spool/ack cache/本功能備援只保留同90日期窗口，逾期刪除且不能因replay重建。UI日期選擇受同窗口限制，顯示「流程紀錄保留90天」。不刪既有SRPP報告或改金融資料/備份排程。clock測試今天/89/90天及台北午夜、跨日完結、cleanup race、停機重啟及補匯防復活。

- [ ] 492.14 使用者明確要求非同步且現行排程已過久：既有LLM金融排程不增加任何逐步回報tool/API呼叫，也不等待ingest HTTP/DB/upload/retry。預設hostcollector被動讀公開trace/artifact並在獨立背景process上傳；資料有界、ProcessType=Background/LowPriorityIO，閒置period60sec，scanning 預設每輪最多25個run、總計64MiB／單run32MiB／5秒讀取預算；超出保留游標於下輪續讀並標partial，不忙迴圈；一次性指定歷史日期可設更長預算但仍須有界。producer CLI只atomic localspool enqueue並退出、預設絕不HTTP，背景worker才連9090且timeout2秒；可供已允許的runtime hook使用，不拿LLM回合做逐步打卡。network hang/401/503、spool滿/磁碟錯誤都只能影響紀錄完整度，不能block正常金融流程、重跑金融API或改日報原deadline。receiver201/200只在durable transaction完成後回覆，不能用fire-and-forget假ack丟事件。tests以barrier/非時間flaky的方式證明enqueue不走sender、no new financial scheduled calls、失敗保持pending且可重播；驗收另量測collectorCPU/IO及enqueue overhead，根據真實samples回報，不虛保零耗時。

### 固定互通範例與協定細節（task492契約）


下面只是隔離tests與OpenAPI example，禁止將這些sample UUID當作真run寫到production。

```json
{
  "schemaVersion": 1,
  "run": {
    "runId": "5bd55e21-7e39-5fa7-8c35-d2b2664e54e1",
    "consumer": "Codex",
    "reportDate": "2026-10-08",
    "reportSlot": "09:05",
    "sourceKind": "CODEX_ROLLOUT",
    "sourceRunId": "app-run-opaque-id",
    "automationId": "codex-srpp-09-30",
    "startedAt": "2026-10-08T09:05:00+08:00",
    "workflowVersion": "AI_FLOW_V1"
  },
  "events": [
    {
      "eventId": "6d6d7f46-c4c2-5520-8895-3f164c4f6d0e",
      "sequence": 0,
      "stepId": "calendar-gate",
      "eventKind": "START",
      "status": "RUNNING",
      "occurredAt": "2026-10-08T09:05:02+08:00",
      "title": "確認交易日",
      "summary": "讀取本機交易日曆",
      "operation": {"method": "LOCAL", "path": "calendar"},
      "reasonCode": null,
      "attemptNo": 1,
      "parentEventIds": [],
      "relation": null,
      "evidenceRefs": [],
      "reportedOutcome": null
    }
  ]
}
```

operation.method閉合集合GET/POST/PUT/PATCH/DELETE/LOCAL/TOOL。path對HTTP限canonicalpath（不要query/fragment/host），LOCAL/TOOL只許allowlist分類calendar/policy/validation/render/artifact/tool，不存原shellpath。evidence.role閉合集合CALENDAR/POLICY/API_RECEIPT/VALIDATOR/RESULT/TIMING/DRAFT_RECEIPT/MIME_RECEIPT/APP_TERMINAL/TRACE_COVERAGE；id為無URL opaque<=200、hash可null或64hex（沒有不能捏造）。接入的排程credential只准SCHEDULE_EVENTS，collector可CODEX_ROLLOUT/CLAUDE_ARTIFACT；不同sourceKind不能搶佔另一source身份。

producer auth filter 必須早於 PublicApiErrorCaptureWebFilter：未授權401與缺配置503直接完成 sanitized problem，不丟到錯誤日誌鏈且不續鏈；測 owner resolver、upstream WebClient、error ingest、DB 全零互動。觀測 ingress 列入 route catalog 身分，但對所有 outcome 排除 error-ingest（與金融report GET 的唯讀排除分別具名），保留最小應用／Nginx操作診斷，避免觀測故障帶入額外API/資料庫依賴。本次必要 catalog parity 亦補上目前 backend 缺的既有 OPEN_SRPP_REPORT_CALCULATIONS 定義；DB原v1.150種子已存在，依該既有標籤／順序追加code定義，不改歷史SQL或原金融行為。新 AI觀測operation種子於本任務新migration insert-only。

兩新表精確命名 `public.ai_flow_run`、`public.ai_flow_event`。為免新紀錄進入保留較久的一般資料庫備份，ProcessBackupDatabaseProcess 的既有 pg_dump 與 scripts/db-export.sh 僅追加這兩張表的 --exclude-table-data；保留schema與其他資料，金融備份／還原時點、壓縮與既有archive不改。隔離dump/restore測試驗新表schema仍在、AI rows不在、其他sentinel資料仍在，不在production觸發備份/還原。collector提供 --replay 重播指定仍在90天內的原observations以便還原後補錄，過期仍拒；不復活expired、亦不修改原app/SRPP來源。沒有另建本功能長期archive。

新增 `docs/openapi/ai-flow-observation-api.yaml`（獨立1.0.0）與 `docs/openapi/9090-ai-flow-observation.md`，local $ref 獨立解析，不能直接merge兩份components。獨立renderer只寫asset-management觀測Markdown，金融renderer本任務只跑--check。兩份path集合不相交、operationId全域唯一；金融24＋觀測1 union恰好等於gateway/catalog25 pairs（20GET+5POST）；Tailscale24handlers＝25pairs，mailprefix仍共享既有handler。金融 YAML SHA256固定cf280631526b057887696df03ad5804313fa53668dca4bd210b2c3bb1918adeb，三份Markdown固定1338bc9a6437e1b8c78ef0d40f7fc66ef9a124c9c5618909bcd81779990d14b4；驗實檔不只宣告。BFF追加打包觀測YAML與existing OpenApiView專屬GET /api/bff/open-api/observation-contract（登入保護），原/contract不變；前端開放API頁增加金融／AI觀測文件選擇，兩文件都只讀瀏覽、不執行API。修既有frontend contract test 19條漂移為原金融24、觀測1、union25。backend新增90天cleanup @Scheduled 時須同步BFF SchedulePublicBffController 的業務服務／資料清理technical retention catalog（BFF只展示，不另執行cleanup），hostcollectorjob與投資排程分開標示。

Tailscale腳本新增具名 `--observation-only` 模式：先完整讀目前Serve config，只接受原23handlers或已更新24handlers的同一managed9090集合；新增前重讀確認未變，不reset、不移除既有handler/其他設定，只set這一條新AI exact path。只用新API匿名401/非POST405/descendant404及TLS身份探測，不跑任何既有金融API/producer/日報。mock regression覆蓋此模式首次新增、冪等、ownership/race拒絕、既有route設定前後一致；default full配置模式保留原有檢查並同步新route。若當下非owned config須安全停下回報具體差異，不能刪除未知配置。

所有 enum 為閉合互通協定碼（consumer/source/event/status/relationship），不是銀行、券商等可管理業務分類；不新增投資分類設定。Detail request 必須帶 date 與 consumer，BFF核對selected list consumer與run.consumer；date查詢切換時撤下舊圖並以request generation忽略舊response。

ECharts tooltip使用 `renderMode: 'richText'`，表格／節點摘要都用Vue文字插值；不能從任意title/summary生成HTML formatter。事件中的工具批次耗時只能標為該工具步驟合計，不能說是批次內某一API的獨立耗時。API路徑僅在公開call可唯一確定時投影；無法唯一確定的functions.exec複合操作保留TOOL分類而非捏造特定金融API耗時。

已查本機真實 Codex lifecycle：event_msg.payload.task_started/task_complete 均有同一 turn_id。sourceRunId 使用 exact thread_id＋turn_id；同一 thread 多個 turn 不能合為一次流程，metadata/run UUID 以這個真identity導出，公開 call_id 只在自身turn配對。reportDate沿automation_runs.created_at的台北日，跨日完成仍原日期；後續手動turn標為觀測到的後續attempt，不冒稱另一日新排程。assistant message 真schema是 payload.phase=commentary/final_answer，channel缺失；也兼容已知舊channel schema，不能把reasoning列入來源。找不到turn_id／task_started則使用具名raw trace unavailable/partial，不用相近mtime拼接。

detail query date/consumer必填，其他未知/duplicate query拒400。日期先驗證窗口，過期回410；窗口內lookup同owner/consumer/date並核對run.reportDate，不存在回404。purge後仍以request date回410，不保存超期tombstone。List query date/consumer必填、period可省略但default ALL；同一key最多一次、未知/空/非法date拒400。

List payload固定 `{date, consumer, period, timezone, retentionDays, earliestDate, runs}`，各run包含run metadata＋derived executionState/reportedOutcome/verifiedReportOutcome/traceCoverage/eventCount/durationMillis(nullable)/startedAt/endedAt(nullable)/reasonCode與中文標籤。Detail payload固定 `{run, nodes, edges, slowSteps}`；nodes每個event含event原欄位及derived display status/label/category/timeLabel/durationMillis/x/y；edges含id/source/target/relation/label/curveness。不存layout/state/duration欄於DB。BFF不自行放宽业務閉合回應schema：先typeddecode/requiredidentities+retention+consumer核對，不合回502。

精確回應欄位凍結如下，null也必須保留key；全部records、不得任意map：

- RunView＝runId, ownerUserId（正整數，由server產生供BFF核對，不允許producer選owner）, consumer, reportDate, reportSlot, sourceKind, sourceRunId, automationId, startedAt, workflowVersion, executionState, reportedOutcome, verifiedReportOutcome, traceCoverage, eventCount, durationMillis, endedAt, reasonCode, slotLabel, coverageLabel, executionStateLabel, outcomeLabel。startedAt/endedAt為nullable ISO offset，reportedOutcome/reasonCode/outcomeLabel nullable；executionState STARTED/ENDED/UNKNOWN，verifiedReportOutcome固定UNCONFIRMED直到另任務真receipt驗證，traceCoverage前述五碼，eventCount非負integer、durationMillis非負long或null，其餘必填string。ownerUserId只經BFF核對，不顯示敏感帳戶。未觀測開始／结束時相應為null。
- Node＝所有 Event 原欄位＋statusLabel, label, categoryLabel, timeLabel, durationMillis, x, y, color；eventId即圖nodeId，x/y integer layout，color限合法#RRGGBB。categoryLabel/timeLabel/statusLabel由server供中文呈現；不把前端數值判斷拿來推狀態。
- SlowStep＝stepId, nodeId, title, operation, startedAt, endedAt, durationMillis, status, statusLabel；必須同run同stepId/attemptNo的START/END成功配對，durationMillis非負且已知，按duration↓/stepId↑。對未配對或負時間差不列名次，coverage據實partial；nodeId指已存在Node eventId。
- POST Ack只含runId, inserted, replayed, eventIds，events批次內eventId唯一；inserted/replayed非負且加總等於唯一批次event數，eventIds恰好該批次所有UUID，不是假成功。錯owner/consumer/metadata/conflict/expiry不回Ack。



### 新表精確模型（未來變更，現況仍以db/schema.sql為准）

ai_flow_run：owner_user_id bigint NOT NULL FK app_user(id)，run_id uuid NOT NULL，consumer varchar(6) NOT NULL且Codex/Claude，report_date date NOT NULL，report_slot varchar(5) NOT NULL且09:05/12:00/11:40，source_kind varchar(20) NOT NULL，source_run_id varchar(200) NOT NULL，metadata_json text NOT NULL（完整 canonicalJCS run metadata，含schemaVersion=1），metadata_sha256 char(64) NOT NULL，received_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP。PK(owner_user_id,run_id)，UNIQUE(owner_user_id,consumer,source_kind,source_run_id)，INDEX(owner_user_id,report_date,consumer,report_slot,run_id)。metadata中的少數查詢identity在獨立列重現，是本任務具名immutable历史snapshot＋索引例外；不增第二份JSONB或計算state/duration。

ai_flow_event：owner_user_id bigint NOT NULL，run_id uuid NOT NULL，event_id uuid NOT NULL，sequence integer NOT NULL CHECK>=0，payload_json text NOT NULL（閉合Event完整canonicalJCS），payload_sha256 char(64) NOT NULL，received_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP。PK(owner_user_id,run_id,event_id)，组合FK(owner_user_id,run_id)→ai_flow_run(owner_user_id,run_id) ON DELETE CASCADE，INDEX(owner_user_id,run_id,sequence,event_id)。parent IDs保存在该immutableJSON並由transaction service驗證，不建立第二份parent表。先lock同run再檢查batch所有內容，以保證並发append/replay/parent/cycle驗證原子化；unique冲突以重新讀取同owner已有snapshot核對，不猜成功。

source_kind僅CODEX_ROLLOUT/CLAUDE_ARTIFACT/SCHEDULE_EVENTS且與consumer角色一致；该协议编碼約束不是可管理投資业務分類。SQLguard用transaction_timestamp() AT TIME ZONE 'Asia/Taipei'計算cutoff；直接未來/過期run INSERT也拒绝，event INSERT須存在同owner未過期run，run/event任何UPDATE都拒绝，直接DELETE未過期event/run拒绝。合法expiredrun級cascade只能刪除自身expired events；不以應用role任意disabletrigger。purge每批最多500runs，startup或00:00每輪最多20批，仍有backlog記最小技術日志並下輪續清，不阻塞服務read/write。app日界clock與DB權威可出現邊界race時以DB判定拒绝並映射410，不為零点争用放宽日期。

coverage以公開證據導出：sourceKind=SCHEDULE_EVENTS只SELF_REPORTED；CLAUDE_ARTIFACT無rawtrace最多SELF_REPORTED。collector對同一exact sourceRunId／turn追加獨立完整度評估事件：eventKind=OBSERVATION、stepId=trace-coverage、operation={method:LOCAL,path:artifact}、occurredAt=null、reasonCode=TRACE_COMPLETE/TRACE_PARTIAL/RAW_TRACE_UNAVAILABLE/UNLINKED/SOURCE_TRACE_CHANGED；evidence.role=TRACE_COVERAGE，id精確格式TRACE_V1:<cursorBytes>:<sourceSizeBytes>（非負整數且cursor<=size），hash為所掃描prefix bytes的64hex SHA256；event.sequence=cursorBytes（不超integer），eventId=UUIDv5(sourceRunId,assessmentVersion+cursor+size+prefixHash+reasonCode)，同評估重播必相同。來源檔可增量，但不可修改已掃描prefix；若來源截短或已掃描前綴改寫，只追加SOURCE_TRACE_CHANGED評估且不覆寫已有source事件。SOURCE_TRACE_CHANGED在同run永久優先導出PARTIAL_OBSERVED，不能被任何較早／較大／後續TRACE_COMPLETE遮住；這是來源一致性損壞，與可補齊的暫時尾端缺口不同。先掃同run所有評估：任一SOURCE_TRACE_CHANGED，或同size/cursor出現不同prefixHash，都強制PARTIAL_OBSERVED；來源損壞優先於一般assessment排序。其餘一致來源才按sourceSizeBytes降序、cursorBytes降序選最新有效範圍；同size/cursor/hash的一般TRACE_COMPLETE與TRACE_PARTIAL相反判定拒400 INVALID_GRAPH，但SOURCE_TRACE_CHANGED不受此拒絕規則阻擋，必可追加存證。不能靠接收時間互相覆蓋。所有source呼叫與輸出仍以真call_id/turn配對，源事件sequence使用真source行序（允許與評估sequence相同，但eventId不同）；圖中assessment明確標「紀錄完整度檢查」。讀取完畢且terminal/final已觀測時才能TRACE_COMPLETE，running第一次缺terminal或暫時截斷只TRACE_PARTIAL，後續補齊以較新評估解除該暫時缺口，歷史partial事件保留但不永久否決完整度。CODEX_ROLLOUT必須同真turn有task_started、task_complete、final_answer，公開call均已配對、cursor覆蓋sourceSize且無目前缺失childtrace，才FULL_OBSERVED；其餘PARTIAL_OBSERVED。找不到來源與無exactlink用最新評估RAW_TRACE_UNAVAILABLE/UNLINKED，只有真新可讀source補齊才可解除。不可讀childtrace持續partial；不捏造child節點。assessment僅完整度觀測，不是金融VALIDATOR或APP_TERMINAL收據；verifiedReportOutcome永遠UNCONFIRMED。run耗時僅true START→TERMINAL，缺端null。手動後續turn在有exact所屬automation/thread時保留reportDate與slot，title含「後續嘗試」；無exactlink則UNLINKED。

## 驗證

以下測試均為本任務待新增，必須實作並通過，不把尚未存在的測試當成驗收證據：backend服務邏輯、HTTP嚴格協定、真PostgreSQL原子寫入與隔離、保留期限；BFF typed回應驗證、ADMIN與producer授權及error-ingest零互動；host新增 scripts/tests/test_collect_srpp_ai_flow.py；frontend新增 src/utils/aiFlowView.contract.test.js 並納入npm test。每項覆蓋案例與可執行命令列於本節。

在本worktree根目錄依序執行（JAVA_HOME指向真Temurin25，不加任何experimental flag；NODE工具使用本機v22.21.0，首次npm ci不使用devserver）：

```bash
(cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home /usr/local/apache-maven/current/bin/mvn -B clean test package)
(cd bff && JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home /usr/local/apache-maven/current/bin/mvn -B clean test package)
(cd frontend && PATH=/Users/steven/.nvm/versions/node/v22.21.0/bin:$PATH npm ci && PATH=/Users/steven/.nvm/versions/node/v22.21.0/bin:$PATH npm test && PATH=/Users/steven/.nvm/versions/node/v22.21.0/bin:$PATH npm run build)
python3 -m unittest discover -s scripts/tests -p 'test_collect_srpp_ai_flow.py' -v
ruby scripts/tests/docker-external-api-openapi-test.rb
ruby scripts/tests/api-error-log-operation-parity-test.rb
bash scripts/tests/configure-tailscale-api-gateway-test.sh
ruby scripts/render-9090-openapi-docs.rb --check
ruby scripts/render-ai-flow-openapi-docs.rb --check
bash scripts/tests/schema-sql-drift-test.sh
bash scripts/spec-check.sh
```

部署經run-stack只重建受影響 business-services/bff/frontend/api-gateway，保留datastores/broker flags/正式OAuth/TLS；deploy/configure本功能專屬token後backend/bff不能用假預設。以已知實際日曆日例如2026-10-08（非金融讀取/重跑）執行collector dry-run及once，各consumer分開查詢：

```bash
python3 scripts/collect-srpp-ai-flow.py --once --date 2026-10-08 --consumer Codex --dry-run
python3 scripts/collect-srpp-ai-flow.py --once --date 2026-10-08 --consumer Claude --dry-run
python3 scripts/collect-srpp-ai-flow.py --once --date 2026-10-08 --consumer Codex --token-file /Users/steven/.local/share/srpp-ai-flow/collector.token
python3 scripts/collect-srpp-ai-flow.py --once --date 2026-10-08 --consumer Claude --token-file /Users/steven/.local/share/srpp-ai-flow/collector.token
curl --silent --output /tmp/ai-flow-noauth.json --write-out '%{http_code}' --request POST --header 'Content-Type: application/json' --data '{}' http://127.0.0.1:9090/api/srpp/ai-flow/events
```

最後一probe須401且body無token/user資訊；SQL只讀驗兩軌run/event counts與stableIDs、同命令重送zero-new且hash不變。透過ADMIN browser/BFF驗2026-10-08 date/consumer/period list/detail、流程圖點選/縮放/partial標示，非ADMIN拒絕測試在隔離context不是prod造fake session。正式query backend可用容器內configured-admin安全headers但不得輸出敏感值；browser view真登入可用既有授權session。安裝host LaunchAgent com.steven.srpp-ai-flow-collector（若既有同label先完整讀配置再更新），指向正式main script與0600tokenfile/0700state-dir，launchctl print該gui/$UID/label readback、觸發一次/查receipt並再次讀DB/UI；任意失敗不得將狀態標成功。

隔離新增測試必須包含：同run初次partial→真增量補齊→最新assessment導出FULL、舊FULL後來源截短／前綴改寫／同範圍不同hash均強制partial且原事件保留、評估穩定重播、尚缺child持續partial，以及purge前後detail必要date過期410／窗口內未知ID404。

至少Java25 backend/bff suite、frontend npm test/build、Python unittest collector（callpair/truncated/reasoning/secret/injection/rootescape/SQLisecond/date+consumer/metadata/runIdentity/idempotency/safeISO/null/pairing）、realPostgreSQL append-only/atomic/concurrency/tenant/date跨日及consumer隔離；匿名401/nonadmin403、wrongmethod405/descendant404、schema duplicate/unknown rejects、missingparent/cycle/conflict、queryzero-write；historical真記錄冪等回填與UIflow操作。renderer --check、OpenAPI/Tailscale/APIoperation/schema contract、speccheck、schema drift。prod煙測不得新增TEST run／模擬Gmail完成；可在隔離測試DB/client fixtures驗失敗，prod只匯入已發生真observations與驗未授權拒絕。時間/資料字段無來源時null+UNCONFIRMED，不能填假成功。

## 完成報告

待實作／驗收後回填；另列Claude遠端詳細trace的實際接通/缺口，不用本機舊timestamp宣稱remote已ready。

