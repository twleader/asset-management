# [t494] AI 判斷流程：台股早盤／台股午盤／美股與全部時段

**對應 Requirements:** Requirement 190（四種查詢分類、真實雙軌流程來源）
**前置任務:** t491、t492已完成；耗時顯示維持分／秒／毫秒
**Liquibase changeset:** v1.154.0-ai-flow-us-report-slot.sql（所有worktree已查無佔號，實作前再查）

## 背景

使用者對已部署AI判斷流程提出畫面驗收修訂：時段應為「台股早盤、台股午盤、美股」，並明確補充「全部也要保留」。時段與排程ID是觀測協定碼，不是可管理的投資市場／資產業務分類，不新增金融分類設定。本次不只是增加下拉文字：查詢、來源抽取、producer閉合協定、DB CHECK與下游回應驗證須一致。保留既有ADMIN頁面與api、所有既有台股run/event immutable JSON／JCS／SHA／UUID，不能以UPDATE重新分類舊run。不改SRPP任何檔案、規則、prompt、validator、排程或遠端skill；不重新執行報告／金融API／Gmail／券商交易；只獨立被動觀測。Flowable仍不導入。

已唯讀查證：Codex每日美股automation exactID `22-00`，名稱「美股短中期投資建議 22:00」，ACTIVE、台北週一至週五22:00，90日範圍2026-07-14..2026-10-11有52真app-run，thread及rollout均存在。`22-00-2`是22:30守門員、`srpp-codex`是週日台美展望，兩者必排除；不能以名稱含美股或小時推定daily身份。Codex來源SQLite只以URI mode=ro查automation_runs.thread_id/automation_id/created_at，連state_5.sqlite thread.rollout_path，再抽公開turn_id/call_id/lifecycle資料，不讀reasoning或金融內容。

真10/8美股sourceRunId為 `01a11bd0-ab56-7801-98f8-2208c2f466a7:01a11bd0-ad08-7c13-99a1-bd489ee16a4d`，既有AI_FLOW_V1 UUID規則得到runId `41f376ef-91ae-5e98-9bba-4ce01d55ae45`，尚未匯入。reportSlot新增 `22:00`表示已查證的排程報告時段，與既有09:05／12:00同語義，不冒充actualstartedAt。提前／補跑／跨午夜皆保留plannedslot，真開始時間仍來自公開START。reportDate始終取automation.created_at台北日期，同thread後續turn維持原排程日期，不以美國交易日／DST／執行時刻重標。

Claude本機261份session metadata、14種taskIDs與8份排程registry未找到可查證daily US exactID；這不證明remote沒有排程。Claude美股查詢可以合法empty；不能捏造身份、假run、terminal或宣稱遠端已接通。remote詳細接入仍另日。

## 要做什麼

- [ ] 494.1 AiFlowView.vue的時段選項精確為全部時段=ALL、台股早盤=MORNING、台股午盤=NOON、美股=US，依此順序，預設ALL、不清空選取。仍必選date與consumer(Codex/Claude)，日期90台北日、切換撤下舊圖並忽略過期請求。只經自身bffApi.aiFlow既有list/detail；不client端分類/filter或呼叫金融endpoint。保留分秒毫秒格式／unknown、長圖縮放捲動／節點點選與安全文字。
- [ ] 494.2 backend AiFlowService query閉合period擴ALL/MORNING/NOON/US，省略仍ALL、未知／空／duplicate400在owner/DB讀取前拒絕。JDBC同owner/date/consumer SQL filter：MORNING只09:05；NOON只12:00及historical11:40；US只22:00；ALL是四slot全集，不能以非09:05代表NOON。period只是查詢投影、不加欄位或寫入run metadata。detail既有date/consumer驗證與90日410/窗口內404不變。
- [ ] 494.3 producer與typed read新增reportSlot閉合值22:00，schemaVersion仍1、所有request／response欄位及nullable語義不變。22:00僅合法consumerCodex＋exactautomationId22-00；22-00出現在任一台股slot拒400，台股ID／Claude／watchdog22-00-2／weeklysrpp-codex出現在22:00拒400。保留各層既有台股合法組合相容範圍，不借此收緊既有TW producer。Codex台股：09:05↔codex-srpp-09-30、12:00/11:40↔codex-srpp；Claude台股：09:05↔srpp-0905-claude、12:00/11:40↔srpp-1150-claude。backend原TW嚴格slot-ID配對保留；BFF與collector原TW consumer-ID允許集合維持，不另收緊TW slot-ID組合；三層只對新增US施22:00↔(Codex,22-00)雙向exactbinding。sourceKind與credential授權原樣，schedule token只能SCHEDULE_EVENTS、collector可CODEX_ROLLOUT／CLAUDE_ARTIFACT；沒有ClaudeUS knownbinding，不新增假名稱。
- [ ] 494.4 actual db/schema.sql的ai_flow_run.report_slot為varchar5，具名ai_flow_run_report_slot_check目前只三台股slot。新增DDL migration v1.154.0並註冊master，僅冪等擴該CHECK到09:05/12:00/11:40/22:00，不改v1.153或其他歷史migration、不UPDATE任何row、不改PK/FK/unique/index/guard及保留期限。ALTER CHECK不得關閉immutable／expiryguard。實際部署後依pg_dump重產db/schema.sql，表數仍117並逐位元drift check。驗所有既有195 Liquibase identity/checksum未改、新筆196；如同期合法migration另有變更以具名subset比對與實際數字報告，不能盲填。
- [ ] 494.5 projection提供slotLabel：09:05→台股早盤09:05、12:00→台股午盤12:00、11:40→台股午盤11:40（歷史）、22:00→美股22:00。closedcase不得未知slot fallthrough誤標午盤。位置、status、coverage、call-pair duration、source／self-reported／verified結果分離、richTexttooltip及非HTML文字呈現全部不變。verifiedReportOutcome仍UNCONFIRMED，公開工具批次時間不推特定API或LLM私有等待。
- [ ] 494.6 BFF AiFlowProtocol query/typedmetadata/list一致接受新period／slot／binding，listperiod必echo selectedquery，NOON必explicit12:00/11:40，US只22:00，錯owner／consumer／date／slot／automation／period下游502。US＋Claude的空runs合法200，不能假稱不支援分類。原producer bytes原樣轉送、JsonNode/JCS/ISO offset不重新序列化；ADMIN與producerfilter／zeroerror-ingestauthboundaries不變，沒有新BFFroute／globaladvice依賴。
- [ ] 494.7 collector CODEX_TASKS新增exact22-00→planned22:00，codex_runs()的automation_id IN查詢必依exact allowlist產生對應數量placeholders，所有ID與窗口值仍參數化；測試三個合法ID與watchdog／weekly排除，避免原寫死兩個placeholders造成全部Codex讀取失效。validate_envelope同步閉合slot與US雙向IDbinding。正式day=None背景來源探索採本功能state內有界fair keyset／paging cursor，依exact三ID與90台北日窗口、created_at DESC及thread_id穩定排序遍歷並wrap；cursor只越過本輪實際處理候選，讀取預算提前耗盡不得跳過未掃描來源。重啟及新增run後仍能跨輪涵蓋後續頁；dry-run不寫cursor，明確--date的一次性驗收維持原date-scoped選取。此cursor是可清理的observer探索cache，不寫appSQLite或run/event metadata，也不宣稱全部90日已完成匯入。保留25run/64MiB總/32MiB單run/5秒，不提高預算。其他來源mapping、normalize歷史1140、不猜mtime、exactthread/turn身份／UUIDseed／prefixhash／coverage／privacy及SQLite只讀維持；不把weekday限制回套歷史週末或補跑。原Claude來源照舊，未verifiedUS不猜新ID或artifactslot。真正US missingraw如實partial/unavailable，不冒充FULL／金融完成。
- [ ] 494.8 獨立docs/openapi/ai-flow-observation-api.yaml升1.1.0：加有引號的22:00／22-00 enum及完整雙向consumer/slot/automation條件，使用目前OpenAPI3.1.0合法JSON Schema表達雙向binding（oneOf或if/then），不得只寫描述卻沒有可驗證條件。所有巢狀schema／response／security／examples維持strictclosure，request不新增period字段。只由render-ai-flow-openapi-docs.rb重產9090-ai-flow-observation.md，BFF打包補充YAML；同步contracttest原fixed1.0.0版本及新binding。金融docker-external-api.yaml與三份Swagger完整bytes/hash/PublishedSwaggerIdentity維持，金融renderer僅--check。route集合仍25pairs/24handlers，gateway/Tailscale/security/token/routes/bodylimits不變，不需重建gateway或更新Serve設定。
- [ ] 494.9 保留90日期含今天與前89日、append-only／stableACK／replay／immutableguard／原投資資料與備份全部不變。backgroundcollector既有60s/25run/64MiB/32MiB/5s與sender2s/5s/3批上限保持，不增加任何LLM回報call/等待；只重啟本功能observerjob以載入main新CODEX_TASKS，不動原7排程/Claude/Codex投資automations。jobplist/token/main.env原byte保持，正式程序仍main path／Background／LowPriorityIO／Nice10。
- [ ] 494.10 meaningful隔離tests覆蓋4period集合conservation與US/NOON互斥、未知／duplicate在ownerI/O前拒、USexactbinding拒錯軌/slot/watchdog/weekly、BFF下游mismatch502與ClaudeUSempty、realPGmigration後4slot分類及舊immutable/retention/atomic/replay、collector真source身份/跨午夜/Taipeidate/多turn/privacy/noHTTP、不改舊TWJCS；超過25混合TW/US候選跨輪fairpaging/wrap、預算提前耗盡不越過未掃候選、restart與新增source、dry-run零cursor寫入的隔離測試。前端既有contracttest驗4選項與US query/switchgeneration/原耗時格式。人工production一次性驗收僅使用已發生真10/8USobservations；正式背景工作則依既有90日窗口被動觀測其他真歷史與每日US來源。不製造test run，不重跑金融report／Gmail／交易。
- [ ] 494.11 先獨立spec-review、再delegatedimplementation、獨立archaudit（完整diff含untracked）與適當tests/packages；先feature run-stack重建受影響backend/BFF/frontend、真US collector有界一次localenqueue＋獨立sender durableACK、ADMIN browser驗全部／台股早／台股午／美股與雙軌；原10/8台股113身份/hashsubset完全不變。使用者要求的四下拉選項、label、US真41f376efrun圖與分秒毫秒均真UI驗收。然後正常feature commit→no-ffmainmerge→push，main同services重新部署、只重啟本功能observerjob並readback新sources/程序主版本。其他containers/main.env/TLS/OAuth/broker flags不改。最後回填具體版本、tests、sourcecoverage缺口與真runtime，不宣稱Claude remote已ready或US投資建議正確。

## 驗證

以下新增測試是待實作，不能先算通過。重用既有AiFlowProtocol／Projection／Service／HTTP／真PostgreSQL、BFF aiflow與collector／frontend tests，新增需要的period/migration cases並依最新類名執行。

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home /usr/local/apache-maven/current/bin/mvn -B -f backend/pom.xml '-Dtest=*AiFlow*Test' package
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home /usr/local/apache-maven/current/bin/mvn -B -f bff/pom.xml '-Dtest=*AiFlow*Test' package
(cd frontend && PATH=/Users/steven/.nvm/versions/node/v22.21.0/bin:$PATH npm test && PATH=/Users/steven/.nvm/versions/node/v22.21.0/bin:$PATH npm run build)
python3 -m unittest discover -s scripts/tests -p 'test_collect_srpp_ai_flow.py' -v
ruby scripts/tests/docker-external-api-openapi-test.rb
ruby scripts/tests/api-error-log-operation-parity-test.rb
ruby scripts/render-9090-openapi-docs.rb --check
ruby scripts/render-ai-flow-openapi-docs.rb --check
bash scripts/tests/schema-sql-drift-test.sh
bash scripts/spec-check.sh
python3 scripts/collect-srpp-ai-flow.py --once --date 2026-10-08 --consumer Codex --dry-run --max-runs 250 --max-read-mib 512 --per-run-mib 256 --read-seconds 60
```

美股 dry-run只列清洗後metadata，不輸出rawcommands/金融data/secret且不寫state、SRPP或appSQLite。真runtime首次匯入用獨立本功能state-dir（0700、tokenfile0600、仍同90日），有界原observations與stableIDs，不引入假成功；獨立sender只201/200 durableACK後移pending。ADMIN透過既有真登入檢查date2026-10-08/Codex四period各exact集合，US至少41f376ef真run；ClaudeUS空不假資料，其它TW仍可讀。直接production query/readback不觸發金融或其他公開route。原金融YAML SHA256 cf280631526b057887696df03ad5804313fa53668dca4bd210b2c3bb1918adeb，三份金融MD相同 SHA256 1338bc9a6437e1b8c78ef0d40f7fc66ef9a124c9c5618909bcd81779990d14b4，重讀實bytes驗保持。

## 完成報告

待完成後回填四選項、真US來源/畫面、測試與版本；Claude美股來源缺口必據實區分。本檔只變觀測分類，不能以完成這項宣稱SRPP detailed integration已完成。
