# 情境模擬的訂閱／本機 AI 解讀

此功能只將系統已算好的情境數值交給 AI 補充文字。計算引擎、數值雜湊、發布內容與正式 SRPP 規則不受 AI 影響。使用者可選工具、模型及該模型支援的思考程度；本機 Gemma 透過 Ollama 使用，並非單獨寫死的工具。

## 執行方式與費用限制

| Adapter | 使用方式 | 每次解讀前的限制 |
|---|---|---|
| `codex_cli` | host 的 Codex CLI／既有個人 ChatGPT 訂閱 | 強制 `chatgpt` 登入、固定 OpenAI provider；`account/rateLimits/read` 必須證明個人訂閱且可使用方案額度，所有回傳 pool 的 `credits.hasCredits=false`、`credits.unlimited=false`。付費點數可用、額度耗盡或未知均拒絕。 |
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
- `docker-token/bridge.token`：原 token 的唯讀 Docker 投影檔，0444；父目錄為 host 0700。僅 mount 此單一檔案，不能 mount home、CLI 設定目錄或原始憑證。
- `codex-home/`：0700；首次複用 host 的 `~/.codex/auth.json`，之後由這個專屬 CLI home 自行 refresh。憑證始終留在 host，不回傳瀏覽器、不寫日誌、不加入 Git。重新登入後若專屬登入過期，先停止本 bridge，再由管理者將新登入 `auth.json` 更新到此私有目錄並維持 0600，不能改用 API key。
- `bridge.log`／`bridge-error.log`：不記錄請求、持股、提示、token 或模型全文。

LaunchAgent label 為 `com.steven.srpp-local-ai-bridge`，監聽位置固定 `127.0.0.1:8766`。Docker 的 business service 使用 `http://host.docker.internal:8766` 與專屬 token 唯讀檔。API 不掛 9090，不對公網發布；瀏覽器仍經原資產配置建議頁面的 BFF。

重新部署後，必須重建並 recreate `business-services`、`bff`、`frontend` 容器（BFF 同步更新的雙層 DTO 也必須載入），確認實際畫面與功能；health 僅代表服務存活，不代表已執行模型或費用限制已驗證。

## 狀態、停止與安全查核

```bash
python3 -B scripts/install-srpp-local-ai-bridge.py
python3 -B scripts/srpp-local-ai-bridge.py --check
python3 -B scripts/install-srpp-local-ai-bridge.py --stop
```

`--check` 僅查模型 metadata、登入及費用狀態，不發模型推論；輸出僅含能力清單及安全原因碼。安裝器無參數時只讀狀態，不重啟工作。停止會保留 token、registry 和訂閱憑證。重新啟動使用 `--install`；每次明確安裝都會 `kickstart -k` 重新載入程式，因此同一路徑的程式更新也會生效。

`GET /capabilities` 與 `POST /interpret` 均需 `Authorization: Bearer <token>`。不要在命令列或日誌列印 token；操作程式應從私有檔在記憶體中讀取。HTTP 路徑為 exact match，拒絕 query token、redirect、proxy 及遠端地址。body 最大 2 MiB，輸出最多 20,000 字元，同時僅一個解讀工作。推論 worker 最長 120 秒，逾時終止整個程序群組；CLI／本機模型均沒有自動 fallback。

能力 metadata 最多並行 16 個管理者設定工具，短暫快取 20 秒。catalogRevision 涵蓋工具設定（含 executable／port）與模型能力，不涵蓋額度、時間或可用狀態。請求使用過期清單會回 `AI_CATALOG_STALE`，重新載入後才能選擇。

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

單元測試使用隔離 fixture，涵蓋付費／未知狀態零推論、憑證隔離、組合驗證、版本過期、單工作鎖、逾時 killpg、拒絕遠端模型、token 驗證及安裝冪等。Claude 自動探索另驗真實去識別 initialize 結構、動態第四模型、能力矛盾、未知 effort、原始付費 context 拒絕、legacy 配置不裁切、共同 deadline、成功／失敗／輸出超限的程序與管線清理。fixture 只保存合成 request_id 與公開 model metadata，不含 account、email、org、憑證、commands 或 skills 來源。整體驗收另外應經正式頁面 BFF 執行 LOCAL 與所選 AI，確認數值雜湊一致、文字解讀與收據保留、未選模型及工具不被呼叫。

參考官方說明：[Codex app-server](https://developers.openai.com/codex/app-server/)、[Codex 額外 credits](https://help.openai.com/en/articles/12642688-using-credits-for-flexible-usage-in-chatgpt-plus-pro)、[Claude Code CLI](https://code.claude.com/docs/en/cli-reference)、[Claude 額外用量](https://support.claude.com/en/articles/12429409-manage-usage-credits-for-paid-claude-plans)、[LM Studio 本機庫存](https://lmstudio.ai/docs/developer/rest/list)。
