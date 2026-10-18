# [t500] AI 判斷流程：公開工具呼叫寫出實際工具名稱

**對應 Requirements:** Requirement 190（AI 判斷流程；Task 500 公開工具名稱修訂）
**前置任務:** t492、t494 已完成
**Liquibase changeset:** 無

## 背景

使用者在系統資訊「AI 判斷流程」的畫面驗收指出：時間軸與「已配對的慢速步驟」表只寫「公開工具呼叫」「公開工具回傳」，操作欄只有 `TOOL tool`，看不出到底是哪個工具；要求「公開工具是什麼，要確實寫出名稱」。

唯讀查證（只擷取 `payload.name` 與 regex 命中的白名單識別字，未讀取或保存其他內容）：最近 3 個真實 Codex 每日 rollout 的 304 個工具呼叫，`payload.name` 全是 `custom_tool_call` 的 `exec`（Codex code mode 的複合包裝），其中每個呼叫都能從呼叫文字擷取至少一個 `tools.<名稱>(` 內含工具，實際分布為 `exec_command`、`clock__curr_time`、`web__run`、`apply_patch`、`write_stdin`、`mcp__codex_apps__gmail_*` 等。因此只寫 payload.name 會變成一整排「exec」，仍然看不出實際工具；必須一併列出內含工具識別字。這是一次性背景觀察，不是需要長期成立的驗收條件。

現行 collector 的 `parse_source_line` 把 title 寫死成「公開工具呼叫／公開工具回傳」，`operation_for_call` 對 exec 與無法判定者回 `{"method":"TOOL","path":"tool"}`。backend／BFF 對 title 只做長度（≤100）與敏感字樣檢查，projection 直接把 title 當 node.label 與 slowSteps[].title，沒有任何比對「公開工具呼叫」字串的邏輯（已用 grep 查證）。observer OpenAPI 的 title 只有 `maxLength: 100`、summary 只有 `maxLength: 500`。所以名稱只需在 collector 端放進 title／summary，不必新增欄位、不改 OpenAPI、DB、BFF、金融契約或 Swagger identity。操作欄仍是閉合分類碼 `TOOL tool`（不為每個工具名新增 path enum），名稱改由「步驟」欄、時間軸、tooltip 與節點明細呈現。

本任務不改 SRPP、不重跑金融 API／Gmail／交易、不新增 route。已持久化的 immutable 事件不回寫：舊 run（例如 10/16）仍顯示泛稱，這是預期，不是缺陷；只有 collector 更新後新解析的 rollout 行才帶名稱。

## 要做什麼

- [x] 500.1 `scripts/collect-srpp-ai-flow.py` 新增純函式 `tool_label(payload)`，回傳 `(label, names)` 或 None；`names` 是去重後的內含工具識別字清單，順序依在呼叫文字中的出現位置（兩個 regex 的命中依位置合併）。`payload.name` 須符合 `[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}`，否則回 None。名稱（以最後一個 `.` 切分後）為 `exec` 或 `run_code` 時，從 `payload.input`／`arguments` 文字以 `\btools\.([A-Za-z_][A-Za-z0-9_]{0,63})\s*\(` 與 `\btools\[\s*["']([A-Za-z0-9_.:-]{1,64})["']\s*\]` 擷取內含工具識別字，各自過同一白名單、去重、保留首次出現順序。標籤為 `<name>` 或 `<name>（內含 a、b、c 等 N 項）`：title 最多列 3 個內含名稱，N 為去重總數，凡顯示數少於總數（含被長度裁掉者）且顯示數 ≥1 一律附「等 N 項」；整個標籤 ≤80 字元，放不下時逐一減少內含名稱，一個也放不下只留 `<name>`（不附 N、不含「內含」）。不得保存或顯示參數、指令、輸出，也不得把整批耗時歸給任一內含工具；`operation_for_call` 完全不動。
- [x] 500.2 `parse_source_line` 的呼叫事件 title 改為「公開工具呼叫：<標籤>」，輸出事件改為「公開工具回傳：<標籤>」，標籤存進 `turn["calls"][call_id]["label"]` 讓 END 事件沿用，不重新解析；state 只存 `{label, names[:8], total}`（不存完整清單，避免病態 exec input 使 state 暴增；JSON 往返後是 list 不是 tuple，END 組裝須能處理）。END 一律以 `call.get("label")` 取值；缺值（部署前已持久化進 state 的舊 call，`sources/codex-*.json` 內沒有 label 欄位）時維持原泛稱「公開工具回傳」且不得拋例外——collector 外層只接 `ObservationError`／`OSError`，KeyError 會讓 launchd 每次重啟都在同一行再炸一次。有內含名稱時 summary 在原句後附「內含工具名稱取自呼叫文字，未收錄參數、指令或輸出。」與至多 8 個名稱，總長 ≤500 字元：先逐一減少名稱至放得下為止（單名上限 64 字元，8 個名稱加分隔與固定句約 565 字元會超過 500，所以裁切是必要的；減到 1 個必然放得下，沒有需要 fallback 的分支）。沒有名稱或標籤失效時 title／summary 完全維持原泛稱字串，使既有事件格式零差異。
- [x] 500.3 事件 title／summary 組裝只經單一 helper `public_tool_text(prefix, label_info, fallback_title, fallback_summary)`：候選文字檢查 title ≤100、summary ≤500，並同時以 `unsafe_public_text` 與一份對齊 backend `AiFlowProtocol.SENSITIVE`（backend/src/main/java/com/steven/assets/service/aiflow/AiFlowProtocol.java:40）的 Python 等價規則檢查，任一失敗就回傳原泛稱。等價規則必須用 `re.I | re.S | re.A`（Java 的 `\b`、`\s`、`(?i)` 是 ASCII 語意；預設 Unicode 的 Python 在 CJK 緊鄰英文關鍵字時會少判，例如「叫export x」）。為防 backend 日後改規則而靜默漂移，測試從 `AiFlowProtocol.java` 原始碼文字解析出 SENSITIVE 的 pattern 字串，還原 Java 轉義後與 Python 版逐分支比對（各分支各給一個命中樣本、兩邊結果相同），而不是複製常數。另以含 6 位以上連續數字、`api_key`、`secret`、`token=`、`export` 的工具名稱確認 fallback 為泛稱，整個 envelope 仍通過 `validate_envelope`。
- [x] 500.4 保持不變並以測試固定：eventId／stepId／UUID seed／parent 關係／relation／operation／status 對同一輸入逐位元不變（只有 title／summary 不同）；重複 call_id、無配對輸出、跨 turn 配對的既有 malformed 行為；已 ACK 的 eventId 仍被 `Store.enqueue` 過濾而不重送；`spawn_agent`／`create_thread` 的 missingChild 判定；`exec`／`run_code` 與 curl 單一路徑的 operation 分類。沒有新 envelope 欄位、沒有新 evidence role、沒有放寬 `validate_envelope`。`test_existing_tw_full_canonical_envelopes_and_uuid_are_unchanged`（`scripts/tests/test_collect_srpp_ai_flow.py` 約 598-612 行）以 sha256 釘死含 title 的整份 envelope，其 fixture 的呼叫名稱是 `functions.exec`，title 會變成「公開工具呼叫：functions.exec」，三個 digest 必然改變：須明文重算這三個 digest，保留 `runId == 66a62e80-…` 與 `assertNotIn("period", …)` 兩個斷言，不得為了讓舊 hash 回綠而特例化 title；另新增結構等價斷言——同一輸入下，把 title／summary 剔除後的 envelope，與「`tool_label` 被 patch 成 None 的舊泛稱輸出」逐位元相同，使 eventId／stepId／operation／parent／status 不變仍受保護。新增相容測試：由舊格式 state（calls 無 label，START 已解析、OUTPUT 尚未）續解析 OUTPUT，END 事件 title 為原泛稱「公開工具回傳」、不拋例外、envelope 仍有效；已存於 state 的舊 START 事件 title 不被重算。
- [x] 500.5 測試加在 `scripts/tests/test_collect_srpp_ai_flow.py`，涵蓋：直接 `exec_command` → title「公開工具呼叫：exec_command」；`exec` 包 1 個與多個內含工具；`exec` 內含 4 個以上顯示「等 N 項」；內含名稱被長度裁掉時（例如兩個約 36 字元的 `mcp__codex_apps__gmail_*`）仍附「等 N 項」；含 `functions.` 前綴的名稱原樣保留；名稱含空白、`/`、`$()`、中文、超過 64 字元一律 fallback；同一內含工具重複出現只算一次；字串字面值內的 `tools.x(` 仍可能被擷取（summary 已載明來源為呼叫文字，測試固定此已知限制而不宣稱精確）；單名 64 字元 × 8 個內含名稱的最壞情況，summary 被裁切或 fallback，envelope 仍通過 `validate_envelope`；END 事件標籤與 START 相同，且須含「START → 存檔 → 重載 state → END」的跨輪情境（JSON 往返後 names 為 list）；Bearer／holdings 等既有 canary 輸入仍不外洩到任何 title／summary／evidence。
- [x] 500.6 `frontend/src/views/AiFlowView.vue` 的 graph series 節點 label 加 `width`（約 136）與 `overflow: 'truncate'`，兩行（標題＋狀態）格式不變；完整 title 仍在既有 tooltip（`tooltipText` 首行為 label）、時間軸、慢速步驟表與節點明細。已知取捨：「公開工具呼叫：」前綴佔去大半寬度，圖節點上只看得到很少的名稱，使用者需看 tooltip 或其他三處；不改 `aiFlowView.js` 資料映射（那會牴觸「不改資料映射」）、BFF 回傳或任何 API。label 設定寫在 data 層（約 387 行）。新增斷言必須併入既有 `frontend/src/utils/aiFlowView.contract.test.js`（`frontend/package.json` 的 `npm test` 以明列檔名串接，獨立新檔不會被執行），沿用其對原始碼 regex 斷言的風格，確認 label 有寬度與截斷設定、tooltip 仍含完整 label。向使用者回報驗收時須事先說明：圖上節點只看得到少量名稱，完整名稱在 tooltip、時間軸、慢速步驟表與節點明細。
- [x] 500.7 文件：更新 `docs/ai-flow-observation.md`：(a) 說明 title 現在寫出公開工具名稱、內含工具的擷取來源與已知限制（取自呼叫文字、非精確保證）、舊紀錄不回寫；(b) 改寫第 9 行「公開進度只投影固定操作摘要」與第 69 行「自動collector仍只產生固定公開操作摘要」，註明工具名來自 payload.name 或呼叫文字擷取的白名單識別字；(c) 在「90天與重播」段註明：state 遺失而 DB 仍保有舊 title 時不得 `--replay`（同 eventId 不同 payload 會 409，且該批會堵死 sender 後續批次）；state 目錄只能改名保留不得刪除；並把第 58 行「UUID／事件內容保持相同」改寫為「state 完整時內容相同」。部署前檢查 `acks/` 的 run 集合與 DB `ai_flow_run` 一致，最後手段為把衝突 eventId 寫入對應 `acks/<runId>.json` 並移除該 pending 檔。observer OpenAPI（1.1.0）、`docs/openapi/9090-ai-flow-observation.md`、統一 Swagger 與其他 repo／backend／SRPP mirror 位元組皆不得變動；以 `ruby scripts/render-ai-flow-openapi-docs.rb --check` 與 `ruby scripts/render-9090-openapi-docs.rb --check` 證實。
- [ ] 500.8 驗收與部署（維持 CLAUDE.md 的 SDD 順序）：spec-review → 實作（collector 與前端各由 subagent）→ 測試 → `/run-stack` 以 feature 重建 frontend（collector 為 host script，不需 docker）。title 取樣驗收不使用 `--dry-run`（它只印 run metadata 與事件數，看不到 title），改用一次性 Python 查證：以 `Store(dry_run=True)` 與 `Collector.scan_codex` 取得最近一個有 Codex run 日期的 envelopes，只印各事件 `title`，確認出現「公開工具呼叫：exec（內含 exec_command…）」類文字，且沒有指令、參數或金融內容；不入庫、不寫 state、不新增 CLI 旗標；`record` 由 `Collector.codex_runs(day, max_runs)` 取得，script 檔名含連字號須用 `importlib.util.spec_from_file_location` 載入（沿用測試檔 25-30 行做法），並以 `python3 -B` 執行避免在 worktree 寫 `__pycache__`。然後 commit → merge main → 從 main 重建 frontend。collector 部署序列：`launchctl bootout gui/$(id -u)/com.steven.srpp-ai-flow-collector` → 確認 main 已含新 script → 以同一 plist 重新 bootstrap／kickstart（不改 plist、token、main.env，不刪 state，不排空 pending；sender 是 daemon 子 process），回讀 `collector-receipt.json`／`sender-receipt.json`。不手動製造或重送任何 run／event，不 `--replay`。下一次真排程觀測後，ADMIN 頁面用瀏覽器確認新 run 的時間軸／慢速步驟表／節點明細顯示實際工具名稱，舊 run 仍顯示泛稱。

## 驗證

```bash
python3 -m unittest discover -s scripts/tests -p 'test_collect_srpp_ai_flow.py' -v
(cd frontend && PATH=/Users/steven/.nvm/versions/node/v22.21.0/bin:$PATH npm test && PATH=/Users/steven/.nvm/versions/node/v22.21.0/bin:$PATH npm run build)
ruby scripts/render-ai-flow-openapi-docs.rb --check
ruby scripts/render-9090-openapi-docs.rb --check
ruby scripts/tests/docker-external-api-openapi-test.rb
bash scripts/spec-check.sh
```

backend／BFF 不改，不需重跑其 AiFlow 測試；若實作過程發現必須動 backend／BFF／OpenAPI，代表本任務假設（title 為既有自由文字欄位）不成立，應停止並回報，不得自行擴大契約。

## 完成報告

完成於2026-10-18（500.1–500.7）；500.8 的部署已做、真排程回讀尚待下一次排程（見文末「部署與待回讀」）。

實作：`scripts/collect-srpp-ai-flow.py` 新增 `tool_label`／`build_tool_label`／`public_tool_text`，title 變成「公開工具呼叫：<標籤>」「公開工具回傳：<標籤>」，exec 另擷取內含工具；state 只存 `{label, names[:8], total}`，END 以 `call.get("label")` 取值，舊 state 無 label 維持泛稱；敏感字樣對齊 backend `SENSITIVE`（`re.I|re.S|re.A`），測試從 Java 原始碼解析比對防漂移。operation／eventId／stepId／UUID seed 不變。`test_existing_tw_full_canonical_envelopes_and_uuid_are_unchanged` 的三個 digest 已重算（09:05 `44036bbf…`、12:00 `d6a3f7ef…`、11:40 `24e0457d…`），runId 66a62e80-7ba1-54e1-9e9f-b0c22f2ebe55 與 `assertNotIn("period")` 保留，另有「剔除 title／summary 後與舊泛稱輸出逐位元相同」的結構等價斷言。`AiFlowView.vue` 節點 label 加 `width: 136`、`overflow: 'truncate'`，tooltip 仍含完整 title。`docs/ai-flow-observation.md` 已補「公開工具名稱」與重播／state 限制。backend、BFF、OpenAPI、DB、金融契約、SRPP 皆未改。

驗證：collector 測試 128 項（原 98＋新 30）全過；`render-ai-flow-openapi-docs.rb --check`、`render-9090-openapi-docs.rb --check` 通過；`aiFlowView.contract.test.js` 通過、`npm run build` 成功。唯讀取樣（Store dry_run＋scan_codex，只印 title）最近 3 個 Codex run 共 200 個工具事件全部帶名稱，例如「公開工具呼叫：exec（內含 exec_command）」，envelope 皆通過 `validate_envelope`。feature 分支重建 frontend 後，運行中 `AiFlowView-*.js` 內確認含 `width:136` 與 `overflow:"truncate"`。

已知取捨與既有問題：圖節點只有 150px，節點上看不全名稱，完整名稱在 tooltip、時間軸、慢速步驟表與節點明細；舊 run（如 10/16）已持久化的 immutable 事件仍顯示泛稱，只有部署後新解析的 rollout 行才帶名稱；內含工具名稱取自呼叫文字，非精確保證。前端 `npm test` 另有 `openApiContract.test.js` 一項既有紅燈（斷言金融 24 路／union 25，現況 26／27，Task 497 遺留），與本任務無關、未處理。spec 審查跑了兩輪；第二輪唯一 major（既有 digest 測試）修正後未再派第三輪，僅以 spec-check 與一致性 grep 複驗。

部署與待回讀（2026-10-18）：feature `3fd6a502`、main no-ff merge `b53a1f62` 已 push。frontend 先從 feature 重建驗收，merge 後再從 main（`/Users/steven/Project/asset-management-main`）重建收斂；image SHA `72f667d5…`，運行中 `AiFlowView-*.js` 含 `width:136` 與 `overflow:"truncate"`，公網 Let's Encrypt 憑證指紋重建前後相同，effective 設定與 main 相同。collector：pending 為空，以 `launchctl kickstart -k` 重啟同一個 LaunchAgent（原 plist／token／main.env 不動、不刪 state、不 `--replay`），新 process 執行 main 的 script，`collector-receipt.json`／`sender-receipt.json` 回讀正常（queuedEvents 0、pending 0；errors 僅既有的 Claude metadata 項目）。當日（週日）沒有新排程 run，所以**尚未**看到線上新事件帶工具名稱；下一次真排程（週一台股 09:05）產生新 run 後，需在 ADMIN 頁面確認時間軸／慢速步驟表／節點明細顯示實際工具名稱、舊 run 仍為泛稱，500.8 的勾選待此回讀完成。
