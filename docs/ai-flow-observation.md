# AI 判斷流程

「系統資訊 → AI 判斷流程」按台北日期、Codex／Claude及「全部時段、台股早盤、台股午盤、美股」查詢已記錄的公開執行歷程，預設全部時段。流程圖、時間軸與工具耗時來自實際事件；執行結束、自報結果、紀錄完整度與報告驗證分開顯示。沒有紀錄表示尚無已記錄執行，不能推定排程未跑。

今天只部署 asset-management 的觀測功能。SRPP 的規則、排程、validator、prompt 與遠端 skill 都維持既有檔案；原金融 API 路由與契約不變。觀測使用獨立契約 [AI 觀測 API](openapi/9090-ai-flow-observation.md)，但 Task 501 的 observer 1.2.0 會重產統一 Swagger、改變 PublishedSwaggerIdentity，必須在部署前依既有身分同步流程同步 SRPP 的 consumer schema、runtime contract 與規則包身分。SRPP 的隱私政策文字不在本任務改動，須另立發布任務。

## 被動收集與真正非同步

`scripts/collect-srpp-ai-flow.py` 唯讀連接 Codex app 的 automation_runs → thread → 公開 JSONL。一次流程用真 thread_id＋turn_id，工具用同一 turn 的 call_id 配對；最終回覆讀 phase=final_answer，也支援已知舊 final channel。私人 reasoning、系統指令、工具輸出、完整 API/MIME 回應及金融流程資料仍不保存；title／summary／evidence 仍只投影固定公開操作摘要，不保存原 command、env、帳戶、持股或金額。唯一具名例外是使用者 2026-10-18 授權：公開工具的 START 事件可帶 `toolCall.parameters` 原始輸入（見下方「公開工具名稱與參數」），collector 只對憑證樣式做最佳努力遮蔽，並非完整敏感資料偵測或保密保證。

Codex只接受三個已查證的exact排程ID：`codex-srpp-09-30`→台股早盤09:05、`codex-srpp`→台股午盤12:00、`22-00`→美股22:00；不納入`22-00-2`守門員、`srpp-codex`週日展望或名稱相似的工具。時段是planned reportSlot，提前、手動補跑或跨午夜仍保留原排程時段，actual startedAt另取公開START。reportDate始終取automation.created_at的台北日期，同thread後續turn保持該日期，不換算成美國交易日期或依DST重標。查詢period=ALL涵蓋四個slot，MORNING只09:05、NOON含12:00與歷史11:40、US只22:00；不重寫舊台股JSON、UUID或歷史11:40。新增22:00只允許Codex＋22-00，台股既有producer相容範圍保留。

Claude 只收集本機明確 scheduledTaskId／cliSessionId metadata，以及具有 consumer、日期、slot 的結果文件。沒有可讀 raw trace 就顯示「詳細紀錄不可讀」。獨立 artifact 無 exact app link 顯示「無法連結執行」，自報 COMPLETED 也不等於報告已驗證。metadata未明示午盤slot時不以 task 名字猜成12:00或11:40。相近時間、檔名及相鄰timing文件都不用於拼接身份。

Claude每日美股的exact ID與可讀來源尚未查證（`CLAUDE_US_SOURCE_UNVERIFIED`），選美股可合法回傳空紀錄，不建立虛構run、artifact或terminal；這也不代表遠端沒有排程。本次不改Claude遠端排程或接入方式。

既有早午盤 LLM 排程不新增任何回報工具呼叫或等待。collector只在自己0700目錄寫入清洗後事件、prefix hash與游標；每輪讀取預算25個run／64MiB／每來源32MiB／5秒。尾端半行保留至下一輪，真正已掃前綴改寫或截短永久標為部分紀錄。大型prefix的重驗可跨多輪續讀，不以未完成重驗覆蓋既有完整度判定。已讀完來源與Claude metadata以inode／size／mtime／ctime完整性cache避免重複讀取；這些時間不參與run身份、日期或app／artifact聯結。

沒有指定日期的背景輪巡，在本功能自己的`codex-discovery.json`保存最後實際掃描的created_at／thread_id key；SQLite以三個exact ID、90台北日及穩定created_at倒序／thread_id順序參數化讀取，每輪只取有界一頁。讀取預算先耗盡時不越過尚未掃描的候選；走到底後wrap回最新來源，重啟仍可續頁、新run會在後續遍歷被看見。游標僅是可清理的探索cache，不寫appSQLite或run/event metadata，不能把一次成功輪巡說成90日歷史全部已匯入。dry-run不寫探索游標，明確`--date`的一次性查證仍採固定日期選取。原60秒輪巡、讀取上限、非阻塞本機atomic lock及90日期限保持。

enqueue 原子寫入0600 spool後就退出，完全不HTTP。另一個 `scripts/send-srpp-ai-flow.py` process每輪最多3批，每批100事件、256KiB以內，單次網路timeout2秒、單輪5秒預算；失敗保留同一UUID/內容，下一輪重送。HTTP redirect不追蹤；ACK必須持久化後具有相同runId、全部eventIds與正確inserted/replayed計數，才刪pending。401、503、網路或磁碟故障只會留下紀錄缺口，不重跑金融流程。

## Codex 模型服務容量不足

`event_msg/task_complete` 一律先保留原有 generic `task-complete` terminal（`TERMINAL`／`UNKNOWN`、無 reasonCode）；它只表示公開執行已結束，並不表示報表成功、失敗或已寄送，`verifiedReportOutcome` 仍是 `UNCONFIRMED`。只有 `payload.error` 是物件且 `codex_error_info` **精確**為 `server_overloaded` 時，collector 才以該 generic terminal 為唯一 parent，追加一筆固定公開 child：`OBSERVATION`／`FAILED`／`MODEL_SERVICE_OVERLOADED`，標題「模型服務容量不足」，且只附 `APP_TERMINAL/task_complete` evidence。child 的 UUID 只由 parent event ID 與固定 public reason 建立；不保存 error message、未知 code、巢狀內容或其他 upstream 原文。

未知、缺漏、非字串或非物件的 error 一律不產生 child。`FAILED` 在此只代表已觀測到模型服務暫時容量不足，不能推定 Gmail draft、MIME、金融計算或報告結果。若 parent sequence 已到協定整數上限，collector fail closed 而不追加 child。

部署前已完整掃描、已 ACK 的舊 state 不會重建或改寫原 terminal。背景 collector 只在同一原始來源的 signature、完整 prefix hash、session identity 及 explicit `turn_id` 都重新驗證後，追加尚不存在的 child；不要求 final answer、`TRACE_COMPLETE`、工具配對或報表結果，因此 `complete=true`、`final=false`、`TRACE_PARTIAL` 的舊 run 也可安全補記。reconciliation state 只保存固定版本、進度 hash、opaque parent ID 與 `PENDING`／`COMPLETE` 或封閉 `BLOCKED_*` disposition，絕不保存 upstream error。`PENDING` 表示額度、期限或暫時 I/O 尚未完成；`BLOCKED_SOURCE_CHANGED`、`BLOCKED_IDENTITY_MISMATCH`、`BLOCKED_MALFORMED`、`BLOCKED_PARENT_MISMATCH`、`BLOCKED_SEQUENCE` 都不會重試為公開事件。

未指定日期的背景輪巡，另有只保存 `{reportDate, createdAt, threadId}` 的 `terminal-failure-priority` round-robin cursor。它只在當日、最多25筆的既有 Codex record 中尋找可回補 state，每輪最多讀一筆 source，且不讀、寫或推進 `codex-discovery`。priority state/source 同受32MiB、全輪64MiB與前2.5秒限制；暫時 state/source I/O 或預算不足只留下 `PENDING`／空結果，不建立 trace coverage、envelope、pending 或 ACK。成功時才把 frozen 原事件加上新 child 交給原有 sender；ACK 已涵蓋 parent 時，spool 只會含 child。

不得為補記刪除 state、使用 `--replay`、人工 POST `/api/srpp/ai-flow/events`，或讓 SRPP 報表排程等待觀測 API。正式重啟 collector 前，先以既有已登入 ADMIN 的唯讀 BFF detail 確認 generic parent 仍存在、是 `TERMINAL`／`UNKNOWN` 且含 `APP_TERMINAL/task_complete`；不符即停在 `BACKEND_PARENT_NOT_READY`。合併到 main 後才重啟同一 observer LaunchAgent，並以相同 detail 回讀「parent 不變、只多 child／edge」。

```bash
# 僅唯讀評估真實歷史來源，不寫任何 state / spool / SRPP
python3 scripts/collect-srpp-ai-flow.py --once --date 2026-10-08 --consumer Codex --dry-run
python3 scripts/collect-srpp-ai-flow.py --once --date 2026-10-08 --consumer Claude --dry-run

# collect只寫本功能spool；即使傳入token-file也不讀token、不HTTP
python3 scripts/collect-srpp-ai-flow.py --once --date 2026-10-08 --consumer Codex
python3 scripts/collect-srpp-ai-flow.py --once --date 2026-10-08 --consumer Claude

# 明確獨立sender；此token只能由collector role使用
python3 scripts/send-srpp-ai-flow.py --once --token-file /Users/steven/.local/share/srpp-ai-flow/collector.token
```

只顯示清洗後 run metadata、事件數、完整度原因及CPU/IO量測；不輸出來源全文或憑證。一般state/spool限256MiB pending，檔案或磁碟故障不回報假成功。可用`--max-runs`／`--max-read-mib`／`--per-run-mib`／`--read-seconds`調整一次性歷史掃描，但上限仍為250 run／512MiB／每來源256MiB／60秒；背景模式固定預設預算，不允許放大。

## 公開工具名稱與參數

時間軸、已配對的慢速步驟表與節點明細的公開工具事件，title 寫成「公開工具呼叫：<標籤>」與「公開工具回傳：<標籤>」，回傳沿用同一 call_id 配對到的呼叫標籤。標籤取自 Codex rollout 該筆呼叫的 `payload.name`，須符合 `[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}`；`functions.` 等前綴原樣保留。Codex 的 code mode 以 `exec`（或 `run_code`）包裝實際工具，`payload.name` 只會是 `exec`，所以這兩者另從呼叫文字擷取 `tools.<名稱>(` 與 `tools["名稱"]` 的內含工具識別字，寫成 `exec（內含 exec_command、web__run 等 N 項）`：title 最多列3個、整個標籤不超過80字元，列不完時附「等 N 項」；summary 另附至多8個名稱，並載明名稱取自呼叫文字、未收錄參數、指令或輸出。

Task 501 保留上述 Task 500 名稱擷取與 title／summary 規則，並只在**公開工具呼叫的 START 事件**新增選填閉合物件 `toolCall`：`name`、`innerNames`、`parameters`、`parametersChars`、`truncated`、`redactedCount`，以及必要時唯一允許值為 `true` 的 `sourceTruncated`。`parameters` 是使用者授權保存的原始輸入，不保存工具輸出；collector 只以 `[REDACTED]` 最佳努力取代已知憑證樣式。這不是萬全遮蔽，不能將任何非命中內容宣稱為安全。

- `parameters` 最多 16,384 個 Unicode code point。`parametersChars` 是遮蔽、清理後且套用這個輸出上限前的**已處理來源前綴**長度，不是完整原始輸入長度；`truncated=true` 時只保留前 16,384 個字元。
- 原始輸入超過 1,048,576 個 code point 時，collector 只處理此前綴並帶 `sourceTruncated=true`；未帶該 key 不得被解讀為完整輸入已被驗證。`redactedCount=100000` 只表示「至少 100000 處」，不是精確總數。
- 參數不隨完整 run detail、流程圖、tooltip、時間軸或慢速步驟表載入或顯示。僅 ADMIN 使用者選取節點後，才由同源 BFF 的逐節點明細按需讀取；舊事件與非工具節點回傳 `toolCall: null`。
- 內含工具名稱取自呼叫文字，不是精確保證：字串字面值內的 `tools.x(` 也可能被擷取，也可能漏掉不用這兩種寫法的呼叫。
- 名稱不合白名單、title 超過100字元、summary 超過500字元，或命中機密／金額／私人推理等 title／summary 敏感字樣規則時，該事件退回原泛稱「公開工具呼叫」「公開工具回傳」，不會使整批上傳被拒。
- 已持久化的 immutable 事件不回寫：更新前的舊 run 仍顯示泛稱且沒有 `toolCall`，這是預期而非缺陷；只有 collector 更新後新解析的 rollout 行才帶新資料。尚未配對、更新前已入 state 的舊呼叫，其回傳事件也維持泛稱。
- 前端流程圖節點只有150px寬，前綴佔去大半，節點上只看得到少量名稱；完整名稱仍可在不含參數的 tooltip、時間軸與慢速步驟表查看，參數只在上述按需節點明細出現。

## 正式背景工作

先從正式 main rebuild/recreate及驗收API，再產生與backend/BFF相同的獨立 collector token。三個AI token互不相同，也與金融／寄信憑證分離。token存於`~/.local/share/srpp-ai-flow/collector.token`，owner為目前使用者、檔案權限0600；state目錄0700。憑證不進git、plist arguments、環境日誌或畫面。

```bash
# 預設只檢查正式main、token權限及既有同label ownership
python3 /Users/steven/Project/asset-management-main/scripts/install-ai-flow-collector.py
# 驗收／Git landing後才實際安裝；不修改任何投資排程
python3 /Users/steven/Project/asset-management-main/scripts/install-ai-flow-collector.py --install
launchctl print gui/$(id -u)/com.steven.srpp-ai-flow-collector
```

LaunchAgent `com.steven.srpp-ai-flow-collector`只接受正式main script，ProcessType=Background、LowPriorityIO、Nice=10。collector每60秒一輪，sender為另一個子process，不等待於金融排程。既有未知同label配置拒絕覆寫。`collector-receipt.json`與`sender-receipt.json`為最近一次實際收集／ACK回讀，不能以job已載入推定事件已寫入。daemon stdout只保留有界技術計數，日誌限1MiB，不另外保存長期流程archive。

## 90天與重播

台北日期只保留今天及前89日，按原reportDate判定，跨日完成仍屬原執行日期。collector只刪自己的過期spool、ack與state；不刪Codex／Claude／SRPP來源。backend啟動與每天台北00:00有界清理，SQL guard拒絕過期重灌、任意UPDATE與未過期DELETE。Drive 備份 pg_dump 排除 ai_flow_run／ai_flow_event 與 SRPP 報告計算 base／package 的 data（Requirement 192／Task 498），schema 與其他金融 data 維持。

本機atomic writer與cleanup共用每目錄空白0600技術lockfile，兩邊都非阻塞取鎖。writer活躍時cleanup跳過該目錄；SIGKILL／斷電後OS釋放lock，下一輪只回收本功能精確命名的未rename暫存檔，完整或殘缺JSON皆回收，不以mtime延長保留。每目錄每輪最多512 entries、整輪掃描1秒預算，Store保留iterator於下輪續掃；成功enqueue的final檔仍依原reportDate保留90天。本機collector receipt若含任何超期run也刪除。未知檔案、token、原app／SRPP來源及LaunchAgents其他檔案不在清理範圍；空技術lockfile不含流程資料。

還原後可以重播90天內原觀測，UUID保持相同，state 完整時事件內容也相同；過期仍拒絕：

```bash
python3 scripts/collect-srpp-ai-flow.py --once --date 2026-10-08 --consumer Codex --replay
python3 scripts/send-srpp-ai-flow.py --once --token-file /Users/steven/.local/share/srpp-ai-flow/collector.token
```

公開工具名稱與 `toolCall` 都是不可變事件 payload 的一部分，在寫入 collector state 時即凍結。已入 state／pending 的舊事件不回寫、不補參數；不得為補資料刪 state 或使用 `--replay`。只有更新後新讀到的 rollout 行才套用新規則，所以新舊內容不會對同一 eventId 並存。下列情況會讓同一 eventId 產生不同內容，必須遵守：

- state 遺失（刪除或重置）而 DB 仍保有舊 title 或 `toolCall` 時，不得 `--replay` 或重新從頭解析：backend 對同 eventId 但不同 payload 回 409 `EVENT_CONFLICT`，sender 對非200／201拒收並停止，依檔名排序該批永遠排最前，會堵死後面所有批次。
- state 目錄（`~/.local/share/srpp-ai-flow`，含 sources／pending／acks）只能改名保留，不得刪除。`Store.enqueue` 先以 `acks/<runId>.json` 過濾已 ACK 的事件，所以只刪 state 通常不會 409；acks 也一併遺失，或 pending 仍有未送出的舊 title 批次時才會重送不同內容。
- 部署前先檢查 `acks/` 的 run 集合與 DB `ai_flow_run` 一致；DB 已被還原清空（備份本就排除這兩表 data）時重播沒有舊 title 可衝突，才可 `--replay`。
- 若已卡在 409，最後手段是把衝突 eventId 寫入對應的 `acks/<runId>.json`，並移除該 pending 檔（backend 的 SQL guard 禁止未過期 DELETE，沒有別的出口）。

## 未來排程主動接入

本次不接入SRPP。未來獲准的runtime hook若要主動提供SCHEDULE_EVENTS，只呼叫本機enqueue，不能讓LLM逐步打卡，也必須先遵守原calendar gate。缺欄位或secret摘要會拒絕，不接受任意JSON。

主動CLI在建立state或寫入spool前就驗證 title／summary；拒絕 USD／TWD／NT$ 等金額、price／amount／balance／account 等 key-value、原始 shell 指令與私人 reasoning 標記。自動collector仍只產生固定公開操作摘要，工具名稱由 `payload.name` 或呼叫文字擷取的白名單識別字補充；Task 501 的選填 `toolCall.parameters` 另依其專屬閉合契約保存已遮蔽的公開工具輸入，不改寫這些 title／summary 規則，也不保存輸出。backend會再驗證，不依賴上傳後才保護本機spool。

```bash
# 排程只enqueue並退出；獨立state防止混用collector credential
python3 scripts/collect-srpp-ai-flow.py --enqueue-json /private/tmp/closed-observation-batch.json \
  --state-dir /Users/steven/.local/share/srpp-ai-flow-codex
# 另一背景worker使用consumer專用AI_FLOW_CODEX_TOKEN檔，僅可SCHEDULE_EVENTS
python3 scripts/send-srpp-ai-flow.py --once \
  --state-dir /Users/steven/.local/share/srpp-ai-flow-codex \
  --token-file /Users/steven/.local/share/srpp-ai-flow-codex/codex.token
```

Claude使用自己的state與AI_FLOW_CLAUDE_TOKEN；兩種consumer token不能假稱CODEX_ROLLOUT／CLAUDE_ARTIFACT。collector credential亦不能發SCHEDULE_EVENTS；錯誤credential/source由API拒絕，sender保留pending不產生假ACK。金融和Gmail權限不從觀測憑證繼承。
