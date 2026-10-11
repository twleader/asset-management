# AI 判斷流程

「系統資訊 → AI 判斷流程」按台北日期、Codex／Claude及早午盤查詢已記錄的公開執行歷程。流程圖、時間軸與工具耗時來自實際事件；執行結束、自報結果、紀錄完整度與報告驗證分開顯示。沒有紀錄表示尚無已記錄執行，不能推定排程未跑。

今天只部署 asset-management 的觀測功能。SRPP 的規則、排程、validator、prompt、遠端 skill 與原金融 Swagger 都維持既有檔案。觀測使用獨立契約 [AI 觀測 API](openapi/9090-ai-flow-observation.md)，不改金融 PublishedSwaggerIdentity。

## 被動收集與真正非同步

`scripts/collect-srpp-ai-flow.py` 唯讀連接 Codex app 的 automation_runs → thread → 公開 JSONL。一次流程用真 thread_id＋turn_id，工具用同一 turn 的 call_id 配對；最終回覆讀 phase=final_answer，也支援已知舊 final channel。私人 reasoning、系統指令、shell指令、token、完整API/MIME內容及金融金額都不保存；公開進度只投影固定操作摘要。

Claude 只收集本機明確 scheduledTaskId／cliSessionId metadata，以及具有 consumer、日期、slot 的結果文件。沒有可讀 raw trace 就顯示「詳細紀錄不可讀」。獨立 artifact 無 exact app link 顯示「無法連結執行」，自報 COMPLETED 也不等於報告已驗證。metadata未明示午盤slot時不以 task 名字猜成12:00或11:40。相近時間、檔名及相鄰timing文件都不用於拼接身份。

既有早午盤 LLM 排程不新增任何回報工具呼叫或等待。collector只在自己0700目錄寫入清洗後事件、prefix hash與游標；每輪讀取預算25個run／64MiB／每來源32MiB／5秒。尾端半行保留至下一輪，真正已掃前綴改寫或截短永久標為部分紀錄。大型prefix的重驗可跨多輪續讀，不以未完成重驗覆蓋既有完整度判定。已讀完來源與Claude metadata以inode／size／mtime／ctime完整性cache避免重複讀取；這些時間不參與run身份、日期或app／artifact聯結。

enqueue 原子寫入0600 spool後就退出，完全不HTTP。另一個 `scripts/send-srpp-ai-flow.py` process每輪最多3批，每批100事件、256KiB以內，單次網路timeout2秒、單輪5秒預算；失敗保留同一UUID/內容，下一輪重送。HTTP redirect不追蹤；ACK必須持久化後具有相同runId、全部eventIds與正確inserted/replayed計數，才刪pending。401、503、網路或磁碟故障只會留下紀錄缺口，不重跑金融流程。

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

台北日期只保留今天及前89日，按原reportDate判定，跨日完成仍屬原執行日期。collector只刪自己的過期spool、ack與state；不刪Codex／Claude／SRPP來源。backend啟動與每天台北00:00有界清理，SQL guard拒絕過期重灌、任意UPDATE與未過期DELETE。一般DB備份只排除ai_flow_run/event的data，schema及其餘金融data維持原備份流程。

本機atomic writer與cleanup共用每目錄空白0600技術lockfile，兩邊都非阻塞取鎖。writer活躍時cleanup跳過該目錄；SIGKILL／斷電後OS釋放lock，下一輪只回收本功能精確命名的未rename暫存檔，完整或殘缺JSON皆回收，不以mtime延長保留。每目錄每輪最多512 entries、整輪掃描1秒預算，Store保留iterator於下輪續掃；成功enqueue的final檔仍依原reportDate保留90天。本機collector receipt若含任何超期run也刪除。未知檔案、token、原app／SRPP來源及LaunchAgents其他檔案不在清理範圍；空技術lockfile不含流程資料。

還原後可以重播90天內原觀測，UUID/事件內容保持相同；過期仍拒絕：

```bash
python3 scripts/collect-srpp-ai-flow.py --once --date 2026-10-08 --consumer Codex --replay
python3 scripts/send-srpp-ai-flow.py --once --token-file /Users/steven/.local/share/srpp-ai-flow/collector.token
```

## 未來排程主動接入

本次不接入SRPP。未來獲准的runtime hook若要主動提供SCHEDULE_EVENTS，只呼叫本機enqueue，不能讓LLM逐步打卡，也必須先遵守原calendar gate。缺欄位或secret摘要會拒絕，不接受任意JSON。

主動CLI在建立state或寫入spool前就驗證title／summary；拒絕USD／TWD／NT$等金額、price／amount／balance／account等key-value、原始shell指令與私人reasoning標記。自動collector仍只產生固定公開操作摘要，普通文字及無金額的貨幣標籤可保留。backend會再驗證，不依賴上傳後才保護本機spool。

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
