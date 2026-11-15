# 情境模擬的訂閱／本機 AI 解讀

此功能只將系統已算好的情境數值交給 AI 補充文字。計算引擎、數值雜湊、發布內容與正式 SRPP 規則不受 AI 影響。使用者可選工具、模型及該模型支援的思考程度；本機 Gemma 透過 Ollama 使用，並非單獨寫死的工具。

## 執行方式與費用限制

| Adapter | 使用方式 | 每次解讀前的限制 |
|---|---|---|
| `codex_cli` | host 的 Codex CLI／既有個人 ChatGPT 訂閱 | 每次操作唯讀同步正常 CLI 最新登入，以不含 refresh secret 的暫存投影執行；固定 OpenAI provider。真正 exec 與其即時 `account/rateLimits/read`／`model/list` 共用同一投影，必須證明個人訂閱及可用方案額度，所有 pool 的 `credits.hasCredits=false`、`credits.unlimited=false`。登入失效、付費點數可用、額度耗盡或費用未知均拒絕。 |
| `claude_cli` | host 的 Claude CLI／既有 Claude 訂閱 | 乾淨環境的 `auth status` 必須是 `claude.ai` 訂閱；唯讀 OAuth usage 的 `extra_usage.is_enabled` 必須明確為 false。模型與 effort 直接取 CLI 的 initialize metadata，再套用下述訂閱費用政策；額外付費開啟、費用未知或探索失敗均拒絕。 |
| `ollama` | numeric loopback 上已安裝的本機模型 | 僅使用本機 inventory；排除 cloud／remote 模型，解讀前再次驗證所選模型 metadata。 |
| `local_openai` | numeric loopback 上的 LM Studio 相容服務 | 要求 `/api/v1/models` 原生庫存中的本機 `llm` 模型及 `gguf`／`mlx` 格式；一般雲端 API proxy 的 `/v1/models` 清單不足以通過驗證。 |

API 金鑰、provider／URL 覆寫、proxy、helper 等環境設定不會傳給 CLI。Claude 的 `--bare` 會排除訂閱 OAuth，所以本功能使用 `--safe-mode`、空 tools、空 MCP、停用 skills 與隔離工作目錄。Codex 使用專屬私有 `CODEX_HOME`、固定 provider、停用 shell／exec／hooks／apps／plugins／multi-agent／image 等功能並設唯讀及永不批准；若事件含非文字工具，整次結果拒絕，不假稱 CLI 具有已證明的全域工具移除開關。

Codex 的部分模型以 CodeModeOnly 宣告工具介面。刻意停用 code-mode host 時，CLI 0.169 仍會輸出「Code mode will fail closed」啟動診斷。本功能只精確接受已驗證的「host is disabled」且「will fail closed」組合，並要求之後只有文字、最終 `turn.completed`；不因此啟用 Code Mode。缺少執行檔、改成 direct-tool fallback、未知設定或安全警告均拒絕。回歸 fixture 只保存事件結構與公開啟動診斷，模型文字已換成合成內容。

預檢無法原子鎖住供應商帳戶的費用設定。此功能在真正請求前立即重查，任何不能取得明確證據的狀態都停止，不自動切換工具、模型、API、點數或額外用量，也不修改帳戶費用設定。營運上應維持帳戶無額外點數／額外付費用量，避免另一個工作階段同時改變費用設定。相容的本機服務必須是管理者信任的本機安裝；loopback 地址本身不構成對任意惡意代理服務的遠端稽核。

Codex／Claude CLI 本身仍會將提示送至供應商模型，使用訂閱不等於完全離線。需要完全在地端運算時，選擇本機 Ollama／LM Studio。

## 首次安裝與部署

正式程式來源為 `/Users/steven/Project/asset-management-main`。首次驗收尚未合併時，可用 `--source-root` 指定本次已審查的 feature worktree；合併後應重新執行安裝，將 LaunchAgent 指向 main。

```bash
cd /Users/steven/Project/asset-management-main
python3 -B scripts/install-srpp-local-ai-bridge.py --install
```

安裝器只建立本功能的狀態及 LaunchAgent，保留已存在的 token 與 registry，不變更任何訂閱／費用設定。未知的既有 LaunchAgent 或 symlink 會拒絕覆寫。

預設狀態位置 `~/.local/share/srpp-local-ai/`：

- `bridge.token`：原始連線 token，0600。
- `registry.json`：管理者設定，0600；新增工具時可重用任何已支援 adapter，前端無須新增固定選項。
- `claude-usage.lock`：0600、同使用者持有的協調鎖；host 與每次新建的 worker 共用 flock，避免同時查詢 Claude 費用端點。鎖等待受本輪 deadline 限制。
- `claude-usage-backoff.json`：僅在真實費用端點回 HTTP 429 後保存的 0600 負向狀態，只有失敗次數與時間界線；不保存成功授權、token、指紋、帳號、email、原 usage 或 header。重啟 bridge、換工具別名或更新憑證不會重設冷卻。
- `docker-token/bridge.token`：原 token 的唯讀 Docker 投影檔，0444；父目錄為 host 0700。僅 mount 此單一檔案，不能 mount home、CLI 設定目錄或原始憑證。
- `codex-home/`：早期版本的狀態目錄。Task 594 起不再讀取其中的 `auth.json`、不以舊登入 fallback，也不再使用其 refresh secret。既有檔案不自動刪改，無須人工更新副本；正常登入只在一般 Codex CLI 維護。舊模型快取若有仍僅供不可用時的展示，不構成登入或費用授權。
- `bridge.log`／`bridge-error.log`：不記錄請求、持股、提示、token 或模型全文。

LaunchAgent label 為 `com.steven.srpp-local-ai-bridge`，監聽位置固定 `127.0.0.1:8766`。Docker 的 business service 使用 `http://host.docker.internal:8766` 與專屬 token 唯讀檔。API 不掛 9090，不對公網發布；瀏覽器仍經原資產配置建議頁面的 BFF。

重新部署時依實際改動重建並 recreate 受影響容器；涉及 BFF 雙層 DTO 的變更也須重建 `bff`。Task 594 修改安全碼白名單與畫面文案，需重建 `business-services`、`frontend`，BFF wire 保持既有契約。另重新安裝 host bridge 以載入程式；health 僅代表服務存活，不代表已執行模型或費用限制已驗證。

## 狀態、停止與安全查核

```bash
python3 -B scripts/install-srpp-local-ai-bridge.py
python3 -B scripts/srpp-local-ai-bridge.py --check
python3 -B scripts/install-srpp-local-ai-bridge.py --stop
```

`--check` 僅查模型 metadata、登入及費用狀態，不發模型推論；輸出僅含能力清單及安全原因碼。它也遵守共享退避，真 429 時可更新私有負向狀態，不會修改供應商帳戶。安裝器無參數時只讀狀態，不重啟工作。停止會保留 token、registry 和訂閱憑證。重新啟動使用 `--install`；每次明確安裝都會 `kickstart -k` 重新載入程式，因此同一路徑的程式更新也會生效。

`GET /capabilities` 與 `POST /interpret` 均需 `Authorization: Bearer <token>`。不要在命令列或日誌列印 token；操作程式應從私有檔在記憶體中讀取。HTTP 路徑為 exact match，拒絕 query token、redirect、proxy 及遠端地址。body 最大 2 MiB，輸出最多 20,000 字元，同時僅一個解讀工作。推論 worker 最長 120 秒，逾時終止整個程序群組；CLI／本機模型均沒有自動 fallback。

能力 metadata 最多並行 16 個管理者設定工具，短暫快取 20 秒。catalogRevision 涵蓋工具設定（含 executable／port）與模型能力，不涵蓋額度、時間或可用狀態。請求使用過期清單會回 `AI_CATALOG_STALE`，重新載入後才能選擇。

## Claude 費用查詢限流與退避

工具可以同時可用，Codex 與 Claude 沒有互斥限制。開始解讀時，業務端與 host parent 只驗證先前已載入的目錄結構、選擇與當前 registry 身分，不因選用 Codex 而重新探測全部工具。沒有目錄或 registry 已變更時要求重新載入。目錄的 20 秒顯示快取過期也不會讓 interpret 偷查其它工具；真正 worker 只重新檢查所選工具的模型／effort 與費用，再執行該工具。LOCAL 不進 AI 檢查。

Claude 工具清單的成功費用檢查可在同一 host 程序的記憶體暫存最多 60 秒，僅供 metadata 顯示。每次仍先確認正常訂閱登入與當下憑證身分，以只存在記憶體的指紋隔離；登入變更、失效、未知資料或查詢失敗不能沿用舊正證據。動態模型能力仍由原生 CLI 取得。worker 的 `fresh` 費用檢查永不使用成功快取，且每次只查一次，成功後立即解讀；不保存可供下一個 worker 使用的正向授權。

跨執行緒與跨程序透過全域 usage 鎖協調，涵蓋多個 Claude 工具別名與重新建立的 worker。只有固定 Claude usage 端點的可信 HTTP 429 會建立共享負向狀態與 `AI_RATE_LIMITED`；鎖忙碌或等待逾時使用既有忙碌／逾時原因，不冒充供應商 HTTP 429。鎖、負向狀態及目錄必須符合 no-follow、owner、0600／0700 安全條件，損壞狀態不會被當成沒有冷卻。

退避由約 60、120、240、480 秒逐步延長到 900 秒，加入非負、最多 6 秒的抖動，本機等待含抖動不超過 900 秒。有效 `Retry-After` 支援整數秒及 HTTP-date；若供應商要求更晚才重試，即使超過 900 秒也不截短。無效、負數、非有限或損壞 header 使用保守預設。時間回退不會提前解除已保存的期限。

退避期間，重新載入與新 worker 直接讀取共享狀態並回限流，不重打費用端點、不呼叫模型。每個 probe 最多一個 HTTP 請求；本輪不在 request 內睡到期後重試，也不啟動背景輪詢。到期後，下一次使用者合法查詢才可再嘗試一次。有效 HTTP 200 且完整費用安全檢查通過後，才清除連續限流狀態；401／403、網路失敗或未知 schema 不能當作恢復，更不能沿用先前快取放行。

worker 遇到限流會使 host 的正證據與目錄可用狀態失效。即使命中目錄快取，每次回傳仍會輕量套用共享負向狀態，因此既有模型名稱可保留為不可選 metadata，Claude 暫停不影響 Codex／Ollama 的獨立選擇。真服務仍回 429 時應如實顯示等待，不為了讓選單變亮而刷新冷卻、購買點數或跳過費用證據。

`AI_RATE_LIMITED` 的使用者處理方式是「費用狀態查詢暫時被限流，等待退避後再重新載入；等待期間不重複查詢，費用確認前不呼叫模型」。它不代表已額外付費，也不代表登入過期，更不保證會在背景自動重試。模型請求本身沒有自動重試或付費 fallback。普通 HTTP helper 的其他 adapter 行為不因本功能改變。

## Codex 最新登入同步與登入錯誤

bridge 只從目前 host 使用者正常 `~/.codex/auth.json` 讀取登入，不沿用早期私有副本，也不跟隨 HTTP 請求、registry 或 `CODEX_HOME` 環境變數改讀任意來源。來源必須是同使用者擁有、0600、非 symlink 的 regular file，最大 64 KiB；`.codex` 目錄必須同使用者擁有且不可由群組／其他人寫入，正常 0755 可保留。開啟時使用 no-follow／nonblocking，先 fstat 拒絕 FIFO／目錄等不合法來源，再核對讀取前後與目前路徑 identity；正常 CLI 正在原子替換時至多重讀一次，不能無限等待或使用部分檔案。bridge 不會 chmod、覆寫、refresh、logout 或更改正常登入。

access JWT 的 exp、nbf 與 account claim 只作本機負向檢查：過期、距離期限不足 180 秒、尚未生效、格式未知或與來源 account ID 不一致均拒絕。這些未自行驗簽的 claims 不能證明訂閱或費用，仍須取得原生 CLI 的最新費用 readback。

每次能力探測建立新的 0700 暫存 `CODEX_HOME` 與 0600 `auth.json`，採 CLI 支援的 `chatgptAuthTokens` 外部 access 模式。投影只保留最新 access JWT、已核對的 account identity，並以同一 access JWT 作 schema 所需的 id_token；refresh_token 固定空字串，不複製原 id_token、refresh secret、API key、provenance、helper 或一般設定。此模式不自行做 managed OAuth refresh，收到外部刷新要求立即停止。並行操作各用獨立投影。

真正解讀會重新讀正常來源，在同一不可變投影上先取得 `account/rateLimits/read` 與 `model/list`、驗證費用與選定組合，再啟動 exec。即使一般 CLI 在中間更新登入，也不會出現「檢查帳戶 A、執行帳戶 B」。20 秒能力快取可能使畫面狀態短暫落後，但不能授權推論；失效後下一次操作必須使用正常 CLI 新的登入。

能力探測在成功、錯誤、逾時時會先終止並等待所屬程序群組、關閉 pipes／selector，再刪除投影。解讀 worker 的投影位於監護 parent 持有的私有 operation root；worker、RPC、exec 保持同一程序群組。parent 在正常完成或 120 秒硬逾時後先 killpg／wait／關管線，再移除整個 root，因此 worker 被 SIGKILL、來不及執行自己的 finally，也不會留下暫存登入。exec 的 105 秒內部 deadline 同時涵蓋非阻塞 stdin、stdout 與程序退出。

| 安全碼 | 使用者看到的原因與處理方式 |
|---|---|
| `AI_AUTH_REQUIRED` | 尚未登入、來源缺失／不安全／格式無效或帳戶不一致。請在一般 Codex CLI 重新登入；bridge 不使用舊副本。 |
| `AI_AUTH_EXPIRED` | access 已過期或需要更新，或原生 RPC／exec 的結構化錯誤明確指出 401／token 失效，或要求外部 token 刷新。請在一般 Codex CLI 更新登入，再重新載入工具狀態。 |
| `AI_SUBSCRIPTION_REQUIRED` | 使用 API key、其他登入方式或非允許的個人訂閱；不能藉此另外計費。 |
| `AI_BILLING_UNVERIFIED` | 沒有明確登入失效證據，但網路、429／5xx、未知 RPC／費用資料使訂閱內使用無法確認。未發模型請求，稍後重試或檢查服務。 |

只從原生 RPC／exec 的結構化 error 位置辨識登入失效；模型文字或任意 stderr 裡的「401」不當作證據。未知安全診斷仍拒絕，原始錯誤、token、帳號與登入內容都不回傳瀏覽器／日誌／測試收據。bridge 不獨立刷新、不重試付費路徑、不購買點數。

官方依據：[外部 ChatGPT access token 模式與刷新要求](https://developers.openai.com/codex/app-server/)、[TokenData 及 JWT 解析／序列化](https://github.com/openai/codex/blob/main/codex-rs/login/src/token_data.rs)、[AuthDotJson schema](https://github.com/openai/codex/blob/main/codex-rs/login/src/auth/storage.rs)、[Codex auth manager](https://github.com/openai/codex/blob/main/codex-rs/login/src/auth/manager.rs)。auth manager 的 `from_external_access_token` 使用同一 access JWT 作 token info 並將 refresh_token 設為空字串；`proactive_refresh_reason` 只處理 managed Chatgpt，外部 ChatgptAuthTokens 不做該刷新。外部模式仍是實驗能力，若未來 CLI 無法接受或無法讀回費用證據，功能應停止而非回退 API。

## 新增或調整工具

registry 根物件閉合為 `schemaVersion` 與 `tools`。每一筆 CLI 工具接受 `id/label/adapter/executable`，新 Claude 工具不需 `models`。既有 Claude `models` 欄位仍可讀取，安裝時保留原檔 bytes 與權限，但不再當模型來源、篩選條件或 fallback。本機工具只接受 `id/label/adapter/host/port`，host 必須是 numeric loopback，不能是 DNS／外網 URL。

例如可新增第二個獨立 Ollama 工具：

```json
{"id":"ollama_secondary","label":"第二個本機模型服務","adapter":"ollama","host":"127.0.0.1","port":11435}
```

Claude 與 Codex 的模型均由 CLI 自動探索，`modelSource=RUNTIME`。Claude 使用與官方 Agent SDK `supportedModels()` 相同的 initialize 控制回應，不採用 API 模型目錄擴張可用範圍。探索只送一筆 `control_request/initialize`，不送 user prompt、不啟動模型回合；成功、失敗、逾時都終止該子程序群組並關閉管線。Claude 的登入、費用與探索共用 7.5 秒 deadline，保留清理時間，避免超過既有 BFF 能力查詢期限。探索有 2 MiB 輸出上限，非 initialize 事件、額外訊息或未知 schema 均拒絕，不回退舊配置。

模型格式維持 `id/label/efforts/defaultEffort`。Claude 使用 CLI 的具體 `resolvedModel` 並去重，排除 default／opusplan 等行為模式；標籤同時顯示 CLI 名稱與具體 model 版本。`supportsEffort` 必須明確為布林值：false 才是空陣列與 null；true 必須有 CLI 回報的合法 levels，不補 low/high/max 等不存在的能力。CLI 若提供預設且位於其支援清單，沿用該值；否則優先選其已回報的 medium，再無則選第一個已回報值。此為畫面的選擇預設，不假稱 CLI 未回報的內部預設。相同 model 的能力矛盾會拒絕，不能 union 出額外支援。

目前本機 Claude CLI 的真實回應有 Opus、Sonnet、Haiku、Fable 四個具體模型；default 與 Opus 是同一模型。Fable 沒有可證明包含於訂閱額度的逐模型費用欄位，因此安全清單只提供前三個。這是本次動態資料與費用條件的結果，不是固定三模型上限；測試同時加入 CLI 回報的第二個標準 Opus 版本，證明第四個安全模型也會呈現，型號與 effort 都不由舊 registry 限制。

逐模型費用政策依據是官方的[標準模型與別名說明](https://code.claude.com/docs/en/model-config#model-aliases)、[既有付費方案的 Claude Code 使用方式](https://support.claude.com/en/articles/11145838-use-claude-code-with-your-pro-max-or-ultra-plan)，配合每次執行前的個人訂閱及 `extra_usage.is_enabled=false` 證據。此版僅接受 CLI 真正回報的標準 Opus／Sonnet／Haiku lineage 的具體版本，並保留以下限制：

- 官方明示 [Fable 在非互動／SDK 模式可能不詢問就扣 usage credits](https://code.claude.com/docs/en/model-config#fable-and-usage-credits)，所以不能只因 CLI 列出或額外用量關閉，就開放 Fable。
- CLI 原始 `value` 中的 `[1m]` 等 context 修飾會先拒絕；不先移除修飾再誤當普通訂閱模型。官方也列出[部分舊模型的 1M context 需要 usage credits](https://code.claude.com/docs/en/model-config#extended-context)。新標準模型原生 context 依 CLI 的普通具體型號處理，不自行附加 `[1m]`。
- 明示 requiresUsageCredits、非訂閱包含、付費描述、未知 lineage 或未知 schema 一律不放行。未來新增 lineage 必須先取得正式訂閱費用政策及真實 metadata 證據，不能只按名稱猜免費，也不能為了湊模型數量放寬費用限制。

Codex 不可用時仍僅展示標 `CACHE` 的 CLI 模型快取，無權靠快取發模型請求。Ollama 與 LM Studio 從本機庫存動態取得模型；本版不為其虛構 low/high/max 等 effort。

執行結果分別保存 requestedModel 與供應商明確回報的 actualModel。CLI 未提供足夠證據時，actualModel 為 null 且 modelEvidence=`REQUESTED_ONLY`；本機服務回報模型時為 `PROVIDER_REPORTED`。歷史收據不是模型能力、費用設定永久有效或正式規則已採用的證明。

## 驗證

```bash
python3 -B -m unittest scripts/tests/test_srpp_local_ai_bridge.py
```

單元測試使用隔離 fixture，涵蓋付費／未知狀態零推論、憑證隔離、組合驗證、版本過期、單工作鎖、逾時 killpg、拒絕遠端模型、token 驗證及安裝冪等。Codex 同步測試只用合成 JWT，覆蓋登入輪替、同 snapshot proof／exec、來源安全讀取、過期／錯帳、舊副本不可 fallback、401 與費用未知分流、並行投影及外層硬逾時清理；不讀寫真實登入、不發模型推論。Claude 自動探索另驗真實去識別 initialize 結構、動態第四模型、能力矛盾、未知 effort、原始付費 context 拒絕、legacy 配置不裁切、共同 deadline、成功／失敗／輸出超限的程序與管線清理。usage 退避測試使用 loopback 合成 HTTP、時鐘及新建程序，驗證 429→零外呼冷卻→200 恢復、跨程序／重啟共享、原子負向檔、記憶體 TTL／登入輪替、fresh 不讀正快取，以及真 parent→worker 路徑過目錄 TTL 後選 Codex 零 Claude query、選 Claude 僅一次 fresh query，不打真 usage 刷限流。fixture 只保存合成 request_id 與公開 model metadata，不含真實 account、email、org、憑證、commands 或 skills 來源。整體驗收另外應經正式頁面 BFF 執行 LOCAL 與所選 AI，確認數值雜湊一致、文字解讀與收據保留、未選模型及工具不被呼叫。

參考官方說明：[Codex app-server](https://developers.openai.com/codex/app-server/)、[Codex 額外 credits](https://help.openai.com/en/articles/12642688-using-credits-for-flexible-usage-in-chatgpt-plus-pro)、[Claude Code CLI](https://code.claude.com/docs/en/cli-reference)、[Claude 額外用量](https://support.claude.com/en/articles/12429409-manage-usage-credits-for-paid-claude-plans)、[LM Studio 本機庫存](https://lmstudio.ai/docs/developer/rest/list)。

## Claude 訂閱登入與本地期限（Task 596）

主要管理者本人可在 Claude 工具列選擇重新登入。介面先顯示站內提示；只有按下登入才啟動原生 CLI，開啟官方授權頁。使用者完成官方頁面後，將完整 `code#state` 貼回站內視窗。此欄僅接收一次授權碼，不收密碼、API key 或 token。登入與費用確認為獨立狀態：登入成功只表示本地正常訂閱憑證已更新，費用仍受原有 fresh proof 與持久 429 冷卻約束。

獨立 `/auth/claude/state` 在費用冷卻期間仍可查本地登入狀態，沒有 usage 或模型請求。明確本地過期顯示 EXPIRED；存在但不合法的期限需要重新登入；缺少期限為 UNKNOWN，不能稱本地有效。真正費用查詢保留 cooldown 優先；允許查詢時，先讀正常憑證的 `expiresAt`（epoch 毫秒），已過期回 `AI_AUTH_EXPIRED`，不對 usage 發送失效 access token。期限缺失仍沿既有 fresh 服務端費用證明，不以本地有效期限授權免費執行。

Host 登入管理器獨立在 `scripts/srpp_claude_auth.py`，bridge 由自身同目錄明確載入。Host 的 `state/start/status/complete/cancel` 全部為固定 bearer 保護的 POST；ownerId 與 43 字元 sessionBinding 由伺服器權威身分／WebSession 產生，不接受瀏覽器自訂。每台 host 最多一個流程，flowId 為 256bit 隨機值、有效期300秒，terminal snapshot 最多保留60秒。跨 owner/binding 不透露 URL、不取消他人流程；重啟後舊 flow 不可重播。

登入唯一命令是固定 registry executable 的 `--safe-mode auth login --claudeai`，乾淨環境額外固定 `BROWSER=/usr/bin/true` 防止在 host 自開瀏覽器。正常登入的 PKCE、OAuth 交換與 Keychain 寫入全部由 native CLI 執行；不複製／備份 refresh token、不自行交換或手寫憑證。CLI 登入本身可執行必要的帳號／政策網路驗證，但本功能不額外查 usage、也不送模型回合。

原生2.1.325的正式授權網址精確為 `https://claude.com/cai/oauth/authorize`，manual redirect 為 `https://platform.claude.com/oauth/code/callback`，client ID 與八項原生 scope 在管理器閉合驗證。拒絕未知網址／query、重複欄位、不同 client、scope 變動及不合法 PKCE／state；可選 orgUUID 僅合法 UUID。此版不支援 login_hint/login_method，若 CLI 未來改協定則安全失敗，不能換成其它登入供應商。scope 中原生的 `org:create_api_key` 不代表允許此功能用 API 計費，登入始終強制 `--claudeai`。

完整 authorization URL、state、code、native stdout/stderr、OAuth 回應與憑證不進檔案、日誌、錯誤 ingestion、DB 或 localStorage。只有等待貼碼時回傳已驗的官方 URL，送碼後立即隱藏；伺服器 constant-time 比對 state、限制單行 ASCII 與4096字元、最多寫 native stdin 一次。總輸出上限128KiB；完成、取消、逾時、超限都終止完整程序群組及 callback、關閉 selector／管線。UI 關閉或取消後不要沿用舊授權碼。

`claude-operation.lock` 為私有0600 no-follow flock：Claude metadata／所選 Claude worker 持共享鎖，登入全流程持排他鎖。登入中 Claude 回 `AI_AUTH_IN_PROGRESS`；Claude 操作中開始登入回 `AUTH_BUSY`。Codex／Ollama不參與此鎖，可繼續使用。登入成功只清記憶體目錄／Claude正向metadata，不刪或縮短 `claude-usage-backoff.json`，不改模型、effort 或原模擬結果。

原生來源備查（本機2.1.325，非跨版本契約）：callback class 的 `listen(...,"127.0.0.1",...)` 明確只綁loopback；authLogin 正常路徑在授權碼交換成功前不執行 credential 寫入，登入成功後才呼叫原生保存。部署驗收只允許真正 native start 取得已驗 manual URL 後立即 cancel，零模型／不開外站／不送碼；前後憑證、token、registry 的 bytes 比對只保存布林結果，不保存任何憑證 hash 或 raw 值。若發現正常憑證有變，立即停止，不以還原 refresh token 解決。真正完成官方登入由使用者操作。

合成測試使用獨立 fake CLI 與 loopback HTTP 驗 URL allowlist、八 scope、重複query、PKCE state、單次code、跨 owner/binding、取消／逾時／輸出上限／native失敗／程序與鎖清理、短terminal保留、路由閉合／bearer／no-store，以及本地expiry在429下獨立顯示、過期零usage。這些測試不呼叫真CLI登入、Keychain、usage 或模型。部署需同步重建 recreate `business-services`、`bff`、`frontend` 與 reload host，依既有權威環境／TLS保護流程執行。

官方來源：[CLI 訂閱登入](https://code.claude.com/docs/en/cli-reference)、[正常憑證儲存與多帳號](https://code.claude.com/docs/en/authentication#credential-management)、[更新到期登入](https://code.claude.com/docs/en/authentication#renew-an-expiring-login)。
