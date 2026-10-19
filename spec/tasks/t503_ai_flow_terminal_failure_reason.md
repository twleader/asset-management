# [t503] AI 判斷流程：記錄 Codex 模型服務容量不足終端原因

**對應 Requirements:** Requirement 190（AI 判斷流程；Task 503 將已白名單的 Codex `server_overloaded` 終端錯誤保存為 append-only 公開失敗觀測）
**前置任務:** t492、t494、t500、t501 已完成
**Liquibase changeset:** 無

## 背景

2026-10-19 的 09:05 Codex 日報 rollout 在 `task_complete.error.codex_error_info` 提供了 `server_overloaded`，但 collector 只將 `task_complete` 轉成 generic `TERMINAL/UNKNOWN`。因此 `/api/srpp/ai-flow/events` 已收到「本輪執行結束」，卻沒有可辨識的「模型服務容量不足」失敗節點。

既有 generic terminal、已 ACK event 與 backend payload 都是 immutable；重建 state 或改寫同 eventId 的 payload 會造成 `EVENT_CONFLICT` 並堵住 sender。修正必須以新的、穩定 UUID 的 append-only child event 表示公開模型服務失敗，不得讓 SRPP 排程直接 POST 或等待觀測 API，也不得保存 upstream 的原始錯誤內容。

## 要做什麼

- [x] **503.1 固定且 fail-closed 的終端錯誤 mapping。** 在 `scripts/collect-srpp-ai-flow.py` 建立單一純 helper，只接受 `payload.error` 為 object 且 `codex_error_info` 精確等於 `server_overloaded`。命中時產生固定 child event：`eventKind=OBSERVATION`、`stepId=task-complete-failure`、`status=FAILED`、`reasonCode=MODEL_SERVICE_OVERLOADED`、`operation={"method":"LOCAL","path":"artifact"}`、title「模型服務容量不足」、summary「已觀測到模型服務暫時容量不足；報告結果尚未確認。」、`reportedOutcome=null`、唯一 evidence ref `{role:"APP_TERMINAL",id:"task_complete",hash:null}`；完成時間、attempt、唯一 parent 及 `NEXT_OBSERVED` 均取既有 generic task-complete event。child sequence 固定為 parent sequence + 1，僅 parent `<2147483647` 合法；否則 `BLOCKED_SEQUENCE` 且無 child，確保排序和每100-event spool chunk 都是 parent 先於 child。`FAILED` 僅表示已觀測到模型服務失敗，不能聲稱金融報告成功或失敗。child event UUID seed 只能含既有 parent event ID 與固定 public reason code，不能含 message、unknown code 或其他 raw error field。`task-complete` generic terminal 必須維持既有 `UNKNOWN`／null reason／UNCONFIRMED 語意。
- [x] **503.2 新 rollout 與舊 state 都安全追加。** `parse_source_line` 在新增 generic task-complete terminal 後呼叫同一 helper。對部署前已 ACK、仍在 retention window、含既有 `APP_TERMINAL/task_complete` generic terminal、且 `cursor == source size` 的 state，新增 versioned、readonly、可續跑 reconciliation：只掃 linked rollout 的 `task_complete` 行，使用 explicit `turn_id` 對應既有 turn，再追加尚不存在的 child。資格不得要求 `final`、`TRACE_COMPLETE`、完整 tool pairing 或 report outcome；`complete=true`、`final=false`、`TRACE_PARTIAL` 是必測 legacy 例。不可重置 source cursor、重算／覆寫既有 event、call、assessment 或 ACK；不可使用 `--replay`。reconciliation 必須持久化自己的版本、cursor、line、prefix digest、完成旗標、opaque parent identity 與無 raw disposition；完整 source prefix 重驗吻合既有 state digest 後才可追加 child。封閉狀態機為：`PENDING`（budget／deadline 或暫時 source I/O）、`COMPLETE`（unknown code、known child 新增或既存）、`BLOCKED_SOURCE_CHANGED`、`BLOCKED_IDENTITY_MISMATCH`、`BLOCKED_MALFORMED`、`BLOCKED_PARENT_MISMATCH`、`BLOCKED_SEQUENCE`；state 本身暫時不可讀不寫 disposition。`BLOCKED_*` 不得再進 priority。每次讀取均服從既有64MiB總、32MiB單run、5秒限制。重啟與重複掃描不得產生重複 child event；既有 ACK filter 應排除該 run 的全部已 ACK event ID，只把新 child 放進 pending。
- [x] **503.3 當日優先回補且不干擾 discovery。** 僅在背景 `day=None` collect，以同一 readonly `automation_runs` 精確讀取當日 Taipei、既有 `CODEX_TASKS` 的 record；SQL rows 與 retained local state-load attempts 各自最多25，只選本版 disposition 缺省／`PENDING` 的候選，scan 時仍重新驗證資格。priority state loader 必須先安全 stat，並將實際讀取／strict parse bytes 計入 `ReadBudget`；每個 priority candidate 的 state+source 共用32MiB，所有 priority selection 只可用前2.5秒／64MiB總剩餘額度，額度或暫時 state I/O 不足即停止不寫 state。正常 `runs=25` 時最多 scan 一筆 priority source，保留最多24筆 discovery；`runs=1` 不作 priority。priority 與既有 discovery 合計最多 `runs` 個 source records、同 thread 去重；priority 不讀、寫、重設或推進 `codex-discovery` cursor，剩餘名額才走原 discovery page。必測 discovery page 停在歷史日期時，今天已 ACK state 仍能在同一既有總讀取預算內取得一次 priority scan；並測大 state、多個 COMPLETE／BLOCKED state 的計量預算、超過25筆歷史 discovery 加當日 `BLOCKED_*` state 時 cursor 仍前進。
- [x] **503.3a priority round-robin 與無副作用路徑。** 新增受既有 state retention 管理的 `terminal-failure-priority` cursor，只保存 `{reportDate,createdAt,threadId}`；從 cursor 後按既有排序取候選、到底才 wrap，每次 state／source 嘗試（含暫時 I/O）都前進，絕不改 `codex-discovery`。priority 一律以已計量 preloaded state 呼叫專用 reconciliation，不得再 `Store.load`，不得走 `unavailable_codex()`／`envelopes()`；budget／deadline／暫時 state/source I/O 只留下 `PENDING` 並回空，不能新增 coverage assessment、envelope、pending 或 ACK。完整成功只組裝 frozen existing events/assessments 加 child。必測兩個 `PENDING` 時最新連續失敗仍在有界輪數嘗試較舊者，以及暫時 I/O 下 bytes 不重複計量、state/ACK/pending/assessment 均未變。
- [x] **503.3b 暫時失敗的 state 邊界。** `STATE_UNAVAILABLE` 不寫 per-thread disposition；暫時 source I/O 只可安全保存或保留 per-thread `PENDING`。兩者都回空並必須推進 `terminal-failure-priority` cursor，不得新增 coverage assessment、envelope、pending、ACK 或其他事件，也不得改寫 `codex-*.json` 的 frozen event 區塊。測試的「state 未變」只斷言 frozen event 區塊，不包含必須前進的 priority cursor。
- [x] **503.4 隱私與未知錯誤邊界。** 沒有 `error`、`error` 非 object、缺漏／非字串／未知 `codex_error_info` 都只保留 generic terminal。測試以含敏感文字的 `error.message`、任意未知 code 和 nested raw fields 證明它們從 envelope、state、stable ID seed、title、summary、reasonCode 及 evidence ref 全部缺席。不可將任意 upstream error 字串轉成公開碼或人類文字。
- [x] **503.5 隔離與 ingress 測試。** 擴充 `scripts/tests/test_collect_srpp_ai_flow.py`：新 rollout 產生恰一筆合法 child 並可通過 `validate_envelope`；generic terminal 不變；child parent／relation／attempt／time／sequence／完整 evidence 固定內容正確；重複 scan/restart UUID 與事件數穩定；已 ACK generic events 下 `Store.enqueue` 只 queue 新 child；先持久化、重開 Store／Collector、保持 source bytes/signature 不變並走既有 `scan_codex` fast path 的 `complete=true/final=false/TRACE_PARTIAL` legacy state 一次性回補與 sender mock ACK 成功；當日 priority、bounded state loader、budget／暫時 I/O、known-success／known-existing／unknown `COMPLETE`、每個 `BLOCKED_*` 不再 priority、歷史 discovery cursor 公平性與 sourceChanged／identity mismatch 零回補。超過100 events且 parent 位於 spool chunk 邊界時，驗 sender 一定先提交 parent。不得直接只測 helper。保留既有 final／coverage、tool pair、title、toolCall 與 UUID 回歸測試。另於 `backend/src/test/java/com/steven/assets/service/aiflow/AiFlowRepositoryPostgresTest.java` 先寫入 generic terminal，再呼叫既有 ingress 僅提交 child；斷言201、parent edge 有效、舊 row 的 payload/hash 不變且只新增 child，不改 production backend。
- [ ] **503.6 文件與部署界線。** 更新 `docs/ai-flow-observation.md`，說明唯一 public mapping、unknown fail-closed、generic task-complete 與失敗 observation 的差別、舊 ACK state 的 append-only reconciliation、`PENDING`／封閉 `BLOCKED_*` priority 邊界、當日 priority 及禁止重建 state／`--replay`。不修改 OpenAPI、Swagger、production backend、BFF、gateway、Tailscale、SRPP 檔案、排程、token 或金融流程；以 renderer `--check` 證明契約 bytes 未改。重啟前，以既有已登入 ADMIN、唯讀 `GET /api/bff/ai-flow/runs/{runId}?date={reportDate}&consumer=Codex` detail 確認 generic parent eventId 仍是 `TERMINAL/UNKNOWN` 且含 `APP_TERMINAL/task_complete`；缺失或不符一律停在 `BACKEND_PARENT_NOT_READY`，不得重啟、enqueue child、直接 POST 或 `--replay`。preflight 通過後，合併至 main 才只重啟同一 `com.steven.srpp-ai-flow-collector` LaunchAgent，不改 plist／token／main.env、不刪 state；ACK 後以相同 BFF detail 回讀可見 parent 欄位不變、只新增 child／edge，canonical hash 由 PostgreSQL regression 證明。不得人工捏造事件、直接 POST、重跑報表、寄信或交易。

## 驗證

```bash
python3 -B -m unittest discover -s scripts/tests -p 'test_collect_srpp_ai_flow.py' -v
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home /usr/local/apache-maven/current/bin/mvn -B -f backend/pom.xml -Dtest=AiFlowRepositoryPostgresTest test
ruby scripts/render-ai-flow-openapi-docs.rb --check
ruby scripts/render-9090-openapi-docs.rb --check
ruby scripts/tests/docker-external-api-openapi-test.rb
bash scripts/spec-check.sh
```

實作完成後，以既有 `/run-stack` 流程確認本次沒有容器／9090 契約變動；host collector 只在合併至 main 且上述驗證通過後重啟。重啟前後比對 main `.env`，並以現有 run 的 `acks/<runId>.json`、collector／sender receipt 與 ADMIN AI-flow read-only detail 確認：舊 generic terminal 未改、只新增 `MODEL_SERVICE_OVERLOADED` child，且不含 raw error message。不得以手動 API POST 取代這個驗收。

## 完成報告

### 已完成的實作

- `scripts/collect-srpp-ai-flow.py`：新增唯一白名單 mapping、append-only child、legacy readonly reconciliation、獨立當日 priority round-robin 與 bounded state loader；generic terminal、既有 ACK、discovery cursor 與 report outcome 均不改寫。
- `scripts/tests/test_collect_srpp_ai_flow.py`：覆蓋新 source、legacy `TRACE_PARTIAL` ACK 回補、未知／敏感 error、封閉 blocked 狀態、priority 的 bounded／PENDING／state unavailable 路徑，以及 100-event spool chunk 的 parent-before-child 順序。
- `backend/src/test/java/com/steven/assets/service/aiflow/AiFlowRepositoryPostgresTest.java`：先持久化 generic parent，再以 child-only ingress 驗證 immutable payload/hash、edge 和 `UNCONFIRMED` outcome。
- `docs/ai-flow-observation.md`：補上 public mapping、reconciliation、priority 與部署前後唯讀驗收界線。

### 實際驗證

- `python3 -B -m unittest discover -s scripts/tests -p 'test_collect_srpp_ai_flow.py' -v`：145 tests，`OK`。
- `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home /usr/local/apache-maven/current/bin/mvn -B -f backend/pom.xml -Dtest=AiFlowRepositoryPostgresTest test`：15 tests，0 failures／0 errors，`BUILD SUCCESS`。
- `ruby scripts/render-ai-flow-openapi-docs.rb --check`、`ruby scripts/render-9090-openapi-docs.rb --check` 與 `ruby scripts/tests/docker-external-api-openapi-test.rb`：皆通過，且未寫入契約 mirror。
- `bash scripts/spec-check.sh`：`BLOCK: 0`、`CHECK: 1`；唯一提醒是 `origin/main` 已 26 小時未更新，撞號檢查可能失準。`git diff --check` 亦通過。

### 尚未執行的 runtime 步驟

尚未合併到 main，因此未執行 `/run-stack`、ADMIN BFF 唯讀 parent preflight、LaunchAgent restart、真實 collector/sender ACK 或 BFF detail 回讀；也沒有直接 POST、`--replay`、重跑報表、寄信或交易。這些必須在乾淨 main、preflight 通過後，依本任務部署界線執行。
