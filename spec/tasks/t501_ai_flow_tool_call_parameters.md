# [t501] AI 判斷流程：公開工具呼叫另傳工具名稱與原始參數（toolCall）

**對應 Requirements:** Requirement 190（AI 判斷流程；Task 501 公開工具原始參數修訂：Codex 工具呼叫 START 事件帶選填閉合物件 `toolCall`，保存工具名稱與使用者授權的原始參數）
**前置任務:** t492、t494、t495、t499、t500 已完成
**Liquibase changeset:** 無（`ai_flow_event.payload_json` 為 text，不需 migration，`db/schema.sql` 不變）

## 背景

使用者 2026-10-18 指示：SRPP 每日流程呼叫 `POST /api/srpp/ai-flow/events` 保存公開執行事件時，「如果是使用外部工具，應該把工具名稱及參數也傳入」。

現況：
- 工具名稱：Task 500（同日已部署）已把實際工具名稱寫進 title／summary，例如「公開工具呼叫：exec（內含 exec_command、web__run 等 3 項）」。它是 title 自由文字，沒有結構化欄位。
- 工具參數：目前**明文禁止**——observer OpenAPI 1.1.0 的 `AiFlowOperation` 只有 `method`／`path`（閉合）；backend `AiFlowProtocol.SENSITIVE` 會把含持股、金額、帳號、email、`curl`、`/Users/`、6 位以上連續數字等的 title／summary 改寫成「[已省略敏感內容]」；Task 500 明列「不得保存或顯示參數、指令或輸出」；SRPP `DAILY_TRADING_AUTOMATION.md` §2C 同樣禁止保存原 shell command、環境變數、憑證、帳號、持股、金額、完整 API 回應或 MIME。
- 已向使用者說明上述界線，並說明「完整原始參數」會讓 Gmail 工具內文、持股查詢等存進 DB，使用者仍明確選擇「完整原始參數」。因此本任務是**對 Requirement 190 隱私界線的具名放寬**：推翻 Task 500 的「不保存參數」與 §2C 對 command 的禁令；title／summary 的規則不變，放寬只限新欄位 `toolCall.parameters`。
- 使用者沒有要求保存工具輸出，本任務不保存輸出。
- 唯一保留的最小防線：collector 對憑證樣式做 best-effort 遮蔽，因為把 token 存進 DB 與 ADMIN 頁面不是使用者想要的結果，且 observer 自己的 collector token 就可能出現在 curl 參數中。這是實作者預設，使用者可再要求取消。

這需要 observer 契約變更（新增選填欄位），所以牽動：observer OpenAPI 1.2.0、統一 9090 Swagger、PublishedSwaggerIdentity（Swagger 的 SHA-256）、SRPP consumer schema／runtime contract／policy manifest 的 Swagger 身分 metadata、SRPP 規則包 bundle SHA（因此 backend 的 report authority golden／registration SQL／測試釘死值）、business-services 重建。部署必須整組完成，詳見 501.8；**本任務的程式實作與 spec 不含部署**。

已知缺口（不在本任務）：SRPP 的政策文件（`DAILY_TRADING_AUTOMATION.md` §2C、`API_CALCULATION_RULES.md`、`daily_runtime_contract.py` 的 `ai_flow_observation.private_reasoning_or_raw_financial_content_allowed: False`）仍寫著不保存原 command／參數。本任務對 SRPP 只做 Swagger 身分 metadata 同步（Task 495 先例），不改政策文字；這些文字在事實上已過時，須另立「SRPP 政策發佈」任務（新決策＋bundle 重建＋同樣的 backend authority 重新登錄）更新，由使用者決定時機。

## 要做什麼

### 契約（frozen，所有元件必須一致）

事件 `AiFlowEvent` 新增**非 required** 屬性 `toolCall`，型別為閉合物件 `AiFlowToolCall`，**不可為 null**（明確 `"toolCall": null` 回 400 INVALID_REQUEST）；缺省代表「無此資訊」。

```json
"toolCall": {
  "name": "exec",
  "innerNames": ["exec_command", "web__run"],
  "parameters": "const r = await tools.exec_command({cmd: \"ls\"});\n…",
  "parametersChars": 1234,
  "truncated": false,
  "redactedCount": 0
}
```

六個 core key 全必填；另允許一個**僅在 collector 受 1,048,576 code-point 預掃上限截斷來源時出現**的選填 `sourceTruncated: true`。除這七個名稱外不得有其他 key（任何深度重複／未知 key 仍回 400）；`sourceTruncated` 若存在必須是 boolean `true`，`false`／`null` 一律非法且必須省略。

| key | 型別 | 限制 |
|---|---|---|
| `name` | string | 1..64，須「整串」符合 `[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}`（Java 用 `matches()`／`\z` 語意，結尾換行不可通過）；payload.name 原樣（`functions.` 前綴保留） |
| `innerNames` | array of string | 0..8 項、uniqueItems、每項同 `name` 的整串 pattern；exec／run_code 內含工具識別字，非複合工具為 `[]` |
| `parameters` | string | 長度以 **Unicode code point** 計 0..16384；不得含 NUL、除 `\t` `\n` `\r` 外的 C0 控制字元、DEL（U+007F）、孤立 surrogate（合法 surrogate pair 允許） |
| `parametersChars` | integer | 0..2147483647（32 位元整數，浮點、字串、布林一律拒絕）；**已處理來源前綴**經遮蔽與清理後、16384 輸出截斷前的 code point 數，不是完整原始輸入長度 |
| `truncated` | boolean | 見不變式 |
| `redactedCount` | integer | 0..100000；已處理來源前綴中被固定標記 `[REDACTED]` 取代的憑證片段數；值達 100000 是 capped count，只能表示至少此數，不能宣稱精確總數 |
| `sourceTruncated` | boolean，選填 | 僅值 `true` 合法；表示原始輸入超過 1,048,576 code point，collector 只對此前綴做遮蔽／清理／輸出。缺省表示未命中此預掃上限 |

不變式（backend 與 BFF 的 producer ingress 都驗，違反回 400 INVALID_REQUEST；collector 的 `validate_envelope` 也驗）：
1. `parametersChars ≥ codePointCount(parameters)`；
2. `truncated == (parametersChars > codePointCount(parameters))`；
3. `truncated` 為 true 時 `codePointCount(parameters) == 16384`；
4. `toolCall` 只可出現在 `eventKind == "START"` 的事件，其他 eventKind 帶此 key 回 400。
5. `sourceTruncated` 若存在，必須為 boolean `true`；它與 `truncated` 是不同維度：前者表示來源預掃截斷，後者只表示已處理前綴在 16384 輸出上限被截斷。

規則：
- **不補 null（入站形狀）。** backend 與 BFF 對 `toolCall` 缺省時不得補 `"toolCall":null`（`reportedOutcome` 在 backend 的既有補 null 行為維持不變，兩者刻意不同）。原因：`payload_json` 是 JCS 正規化後的文字，已存字串與雜湊相等才算純重播；若為缺省補 `"toolCall":null`，部署前已存的事件在 collector 重播時 canonical 內容會多一個 key，變成 409 EVENT_CONFLICT。
- **入站 Event 與讀取 Node 的 null 語意必須分流，且完整 run detail 不搬運參數：** 入站事件（producer → BFF → backend）的 `toolCall` 缺省合法、明確 null 非法；一般讀取側 `Node`（backend → BFF → 瀏覽器）永遠輸出 `toolCall` key，且**一律為 null**，不得把原始參數隨完整圖／時間軸明細載入。使用者選取節點後，才由同源 ADMIN BFF 的逐節點端點取得 `ToolCallDetail.toolCall`（可為 null，舊事件或非工具事件回 200/null）。因此兩個 `Event` record 的 `toolCall` 元件標 `@JsonInclude(JsonInclude.Include.NON_NULL)`（null 時省略 key，覆蓋 record 層級的 ALWAYS），兩個 `Node` record 的 `toolCall` 維持 ALWAYS；`ToolCall.sourceTruncated` 元件則標 `NON_NULL` 以免產生非法的 null key。既有測試 fixtures 以 `JSON.valueToTree(new Batch(...))` 序列化 Event，這個標註是它們不被新欄位打壞的前提。
- `parameters` 不套用 `AiFlowProtocol.sanitize`／`SENSITIVE`；`title`、`summary` 仍照原規則 sanitize，collector 的 `unsafe_public_text`／`BACKEND_SENSITIVE` 仍只套用 title／summary，**不套用 toolCall**。
- 既有 title／summary 的 Task 500 行為完全不變（泛稱 fallback 規則、`public_tool_text`、`tool_label` 都保留）。
- eventId／stepId／UUID seed／parentEventIds／relation／operation／status 對同一 rollout 行不變；`toolCall` 不參與任何 id 推導。
- **跨語言一致性向量（已建立，不得修改既有語意，只可追加）：** `scripts/tests/fixtures/ai_flow_tool_call_vectors.json` 最終為 78 個案例（20 合法、58 非法），欄位：`id`、`eventKind`、`valid`、`toolCall`（缺省＝無此 key，`null`＝明確 null）。追加 `sourceTruncated:true` 合法、`false`／非 boolean／`null` 非法四案；`parameters` 過長時以 `parametersRecipe: {unit, count}` 表示 `unit` 重複 `count` 次，載入時展開。collector（Python）、backend、BFF 三邊的測試**必須讀同一份檔案**，逐案例斷言結果等於 `valid`；Java 側從模組目錄以相對路徑 `../scripts/tests/fixtures/ai_flow_tool_call_vectors.json` 讀取（Docker build 皆 `-DskipTests`，不受影響）。這是因為 sender 對被 backend 拒收（4xx）的批次不會丟棄、只會 `break`，一旦三套驗證不一致，該批永遠排在最前面並堵死之後所有批次。

### 501.1 觀測契約與文件（一支 subagent，檔案：`docs/openapi/ai-flow-observation-api.yaml`、`docs/ai-flow-observation.md`、`CLAUDE.md`、兩支 renderer 與其測試、渲染產物；`spec/steering/{tech,structure}.md` 已由 501.6 在 spec gate 前同步，實作 subagent 不得改動）

- [ ] 501.1.1 `docs/openapi/ai-flow-observation-api.yaml`：`info.version` 1.1.0→1.2.0；新增 `components.schemas.AiFlowToolCall`（`type: object`、`additionalProperties: false`、六個 required、上表每個限制：name pattern 與 minLength／maxLength、innerNames maxItems 8 uniqueItems 且 `items` 有自己的 description 與 pattern／maxLength、parameters maxLength 16384（description 註明以 Unicode code point 計）、parametersChars minimum 0 maximum 2147483647、redactedCount minimum 0 maximum 100000，以及**非 required、僅值 true 的** `sourceTruncated`；每個欄位與 schema 都有非空且不空泛的 description，過 `render-9090-openapi-docs.rb` 的 `WEAK_DESCRIPTION` 檢查）；`AiFlowEvent.properties` 加 `toolCall: {$ref: AiFlowToolCall, description: …}`，**不加進 `required`**；`AiFlowEvent.description` 中的「nullable key除reportedOutcome均必填」改為「除reportedOutcome與toolCall為選填外…」；POST endpoint 的 description（約 YAML 第 21 行）中「私人思考鏈、原commands/env/token/帳戶/持股/金額/完整API/MIME都禁止」改為：title／summary 仍禁止這些內容，唯 `toolCall.parameters` 依使用者 2026-10-18 授權保存公開工具呼叫的原始參數（憑證樣式以 `[REDACTED]` 取代、上限 16384 code point、僅 START 事件、不收輸出）；`info.description` 中「現有SRPP規則與排程不需改動」類句子若因本任務變成不實須一併修正；request example 增加一個帶 `toolCall` 的 TOOL START 事件，其 `parameters` 必須是無害假資料（例如 `{"cmd":"date"}`，`parametersChars` 14），不得含任何真實 token／持股／帳號。
- [ ] 501.1.2 renderer 與契約測試的硬編碼同步（spec-review 已查證，不改會失敗）：`scripts/render-9090-openapi-docs.rb` 的 `validate_document!(document, expected_schemas: 238)`（約第 390 行）改 239（金融 231＋觀測 8；用 ruby 實算確認，不要憑空相信這個數字）；`scripts/tests/docker-external-api-openapi-test.rb` 約第 1209 行的觀測 `info.version == '1.1.0'` 改 `'1.2.0'`；`scripts/tests/unified-9090-openapi-renderer-test.rb` 約第 21 行的 `| AI 流程觀測契約版本 | \`1.1.0\` |` 改 1.2.0。金融單檔的 231 不受影響。
- [ ] 501.1.3 **所有 renderer／測試一律在 `CLAUDE_CODE_REMOTE=true` 下執行。** 原因：不帶時 renderer 會覆寫地端 SRPP 主 checkout `/Users/steven/Project/SRPP/docs/9090 Port API Swagger.md`（目前在別的分支、有其他人的工作），而 `docker-external-api-openapi-test.rb`（`spec-check.sh` B9 呼叫）與 `render-9090 --check` 不帶時會要求 SRPP 鏡像與本專案三份位元組一致，在 501.7 之前必然失敗。依序執行 `ruby scripts/render-ai-flow-openapi-docs.rb`、`ruby scripts/render-9090-openapi-docs.rb` 產生 `docs/openapi/9090-ai-flow-observation.md`、`docs/openapi/9090-api-swagger.md`、`backend/src/main/resources/srpp/9090-api-swagger.md`。`docs/openapi/docker-external-api.yaml` 與金融 Markdown 內容不得有任何位元組變動。**不帶環境變數的一致性（含 SRPP 鏡像）由 501.7 以 SRPP 側 worktree 驗證。**
- [ ] 501.1.4 `docs/ai-flow-observation.md`：新增「公開工具參數」小節——說明 `toolCall` 欄位、僅 START、16384 code point 上限、憑證樣式 best-effort 遮蔽（非精確保證）、`sourceTruncated` 代表只有前 1,048,576 code point 經處理、`redactedCount` 達上限只代表至少此數、不存輸出、舊 run 與已入 state／pending 的事件不回寫；參數只在使用者選取節點後的 ADMIN 明細按需讀取，不在完整 run detail、圖／tooltip／時間軸；改寫本文檔開頭與「被動收集」／「公開工具名稱」段中「不保存、不顯示參數、指令或輸出」及「不改 PublishedSwaggerIdentity」的現況句子為正確的 Task 501 說法，保留 Task 500 的其他限制；「90天與重播」段補充 `toolCall` 同 eventId 內容凍結，不得為補參數而刪 state 或 `--replay`。
- [ ] 501.1.5 `CLAUDE.md`、`spec/steering/tech.md`、`spec/steering/structure.md`：在各自 Requirement 190 例外段落加入短的「Task 501 原始工具參數放寬」現況覆寫（使用者 2026-10-18 授權、只限 `toolCall.parameters`、observer 1.2.0、統一 Swagger 身分連動、title／summary 規則不變、參數經 ADMIN 節點按需讀取）；並把 Requirement 191／Task 497 等**現況**敘述中的 observer 版本更新為 1.2.0（歷史 Task 494 敘述保留 1.1.0）。
- [ ] 501.1.6 驗證（皆 `CLAUDE_CODE_REMOTE=true`）：兩支 renderer `--check`、`ruby scripts/tests/docker-external-api-openapi-test.rb`、`ruby scripts/tests/unified-9090-openapi-renderer-test.rb`、`bash scripts/spec-check.sh` 全綠。

### 501.2 backend（一支 subagent，目錄：`backend/src/main/java/com/steven/assets/{dto,service}/aiflow/`、`backend/src/test/java/com/steven/assets/service/aiflow/`）

- [ ] 501.2.1 `AiFlowDtos`：新增 `record ToolCall(String name, List<String> innerNames, String parameters, int parametersChars, boolean truncated, int redactedCount, @JsonInclude(NON_NULL) Boolean sourceTruncated)`，六個 core 元件不允許 null，`sourceTruncated` 缺省時省略 key；新增 `record ToolCallDetail(String runId, String eventId, ToolCall toolCall)`，其 `toolCall` 為 ALWAYS、可為 null。`Event` 與 `Node` 在 record 末位新增 `ToolCall toolCall`。`Event.toolCall` 標 `@JsonInclude(JsonInclude.Include.NON_NULL)`（null 時省略 key）；`Node.toolCall` 維持 ALWAYS，但一般 `Detail` 只能輸出 null。同步修所有 `new Event(`／`new Node(` 呼叫處：`AiFlowProjection.java:79` 的 `new Node(`、測試 `AiFlowFixtures.java` 的 `new Event(`（約第 21、26、30 行）；其他檔案裡出現的 `new Event(` 若屬股利領域的同名類別則不在此列。
- [ ] 501.2.2 `AiFlowProtocol.parse`：`EVENT_FIELDS` 加 `toolCall`，`exact(node, EVENT_FIELDS, Set.of("reportedOutcome", "toolCall"))`；**不得**為缺省的 `toolCall` 呼叫 `putNull`。新增 `validateToolCall(node, eventKind)`：存在時必須是物件（`isNull()` 或非物件→INVALID_REQUEST）、`eventKind` 必須是 `START`、六個 core key 閉合必填並只允許選填 `sourceTruncated`、`name`／`innerNames` 用整串比對（`matches()`，不用 `find()`）與上限、`parameters` 為 textual 且 code point 數 ≤16384、無 NUL／C0（`\t\n\r` 例外）／DEL／孤立 surrogate、整數欄位要求 `isIntegralNumber() && canConvertToInt()` 並檢查範圍（`redactedCount` 上限 100000）、`truncated` 為 `isBoolean()`、`sourceTruncated` 缺省或嚴格 boolean true、五個不變式。`parameters` **不得**經過 `sanitize`。既有 `textAllowEmpty` 會拒絕換行與控制字元且以 `String.length()` 計長，不能用在 parameters，需獨立的 code point 驗證。`JSON.treeToValue(node, Event.class)` 需能解析新欄位。
- [ ] 501.2.3 `AiFlowProjection.detail`：`Node` 建構式一律傳入 `null`，使完整 run detail 不帶原始參數；`SlowStep` 不變。新增 service／repository 精確讀取單一 `(owner, runId, eventId)` 的不可變 payload，驗 run 的 `date`／`consumer`／90 天窗口與 event identity／hash 後回 `ToolCallDetail`；找不到 event 或不屬於該 run 回 `NOT_FOUND`，舊事件或沒有工具資訊回 200 且 `toolCall:null`。`readEvent` 對缺 `toolCall` 的舊 `payload_json` 得 null，不得拋例外。
- [ ] 501.2.4 `AiFlowController` 新增唯一 internal business read `GET /api/ai-flow/runs/{runId}/nodes/{eventId}/tool-call?date&consumer`，只委派 service，`Cache-Control: no-store`，沿用 owner／query／error 規則；它不是 9090／gateway／Tailscale 路由，也不新增 OpenAPI observation path。單次回應最多一個 16384-code-point `parameters`，現行 BFF 16MiB codec 保持不變。
- [ ] 501.2.5 測試（擴充 `AiFlowProtocolTest`、`AiFlowProjectionTest`、`AiFlowServiceTest`、`AiFlowRepositoryPostgresTest`、`AiFlowHttpTest`，沿用其風格）：(a) 讀 78-case `ai_flow_tool_call_vectors.json`，每個案例把 toolCall（或缺省／null）塞進一個合法 START／END 等事件的批次，`AiFlowProtocol.parse` 結果必須等於 `valid`，並明確覆蓋 `sourceTruncated:null` 拒絕；(b) 缺省時 canonical **不含** `toolCall` key，且與本任務前的 canonical 位元組一致（以既有 fixture 比對），同內容重播回 200 純 replay、不同內容 409；(c) `parameters` 含 `Authorization: Bearer x`、email、`curl `、`/Users/`、6 位數字時**原樣保存**，同事件 summary 含同樣文字時仍被 sanitize 成「[已省略敏感內容]」；(d) PG 往返測試保存／讀回 16384 code point（含換行、引號、反斜線、emoji、CJK），讀回位元組相同；(e) 任意含 toolCall 的 event 經 `AiFlowProjection.detail` 仍得 `Node.toolCall == null`，單節點 endpoint 得相同非 null ToolCall；(f) 300 個各 16384 emoji toolCall 的一般 `Detail` JSON <16MiB，逐節點 `ToolCallDetail` <256KiB；(g) 舊 payload（無 toolCall）經 `readEvent`／projection 與單節點 endpoint 都得 null；(h) 越權／錯 date、consumer、run 或 event identity 不能回傳參數。
- [ ] 501.2.6 不改 SQL、Liquibase、`db/schema.sql`、retention／purge、owner／credential 邏輯。

### 501.3 BFF（可與 501.2 同一支 subagent，目錄：`bff/src/main/java/com/steven/assets/bff/aiflow/`、`bff/src/test/java/com/steven/assets/bff/aiflow/`）

BFF 的 `AiFlowProtocol` 不是欄位清單式驗證，而是「typed record＋反射 `shape()`」：`decode(bytes, type, input)` 先 `shape(node, type, input)` 逐層核對，再 `treeToValue`。同一支 `shape` 同時驗 producer 入站（`input=true`）與 backend 回應（`input=false`，含 detail）。現況（約 `AiFlowProtocol.java` 第 78–85 行）：record 每個元件的 key 都必須存在，唯一例外是入站 `Event.reportedOutcome`；null 只在欄位名屬 `NULLABLE`（第 19 行）時合法；多餘 key 一律 invalid。依此，必須做以下修改，缺一不可：

- [ ] 501.3.1 BFF `AiFlowDtos`：新增 `ToolCall` record（`@JsonInclude(ALWAYS)`，六個 core 元件同 backend，`sourceTruncated` 元件 `NON_NULL`）；新增 `ToolCallDetail` record（`toolCall` ALWAYS、nullable）；`Event` 與 `Node` 末位新增 `ToolCall toolCall`，`Event.toolCall` 標 `@JsonInclude(NON_NULL)`、`Node.toolCall` 為 ALWAYS（理由見契約）。
- [ ] 501.3.2 `shape()` 依 `input` 與型別分流：入站 `Event`——`toolCall` key 缺省合法（比照第 80 行 `reportedOutcome` 的放行條件加一個 `toolCall`），明確 null 非法（不要把 `toolCall` 放進 `NULLABLE`，否則會連入站 null 一起放行）；`ToolCall.sourceTruncated` 只允許缺省或 true，拒 null／false。**現有 `shape()` 只支援 primitive `boolean.class`，實作必須明確支援 boxed `Boolean.class`，且僅對 `ToolCall.sourceTruncated` 放行缺省 key；不得把所有 boxed Boolean 或所有 record 欄位改成 optional／nullable。** 該 key 若存在必須以 boolean 節點通過 shape，後續再嚴格驗值為 true。讀取側 `Node`——`toolCall` key 必須存在（backend 永遠輸出）且只能為 null，非 null 為 `UPSTREAM_INVALID`，防止整個 run detail 意外夾帶參數；讀取側 `ToolCallDetail.toolCall` key 必須存在、null 合法、非 null 時套用完整驗證。`ToolCall` record 其餘元件走既有 record 分支（int／primitive boolean／String／`List<String>` 都已支援）。
- [ ] 501.3.3 `event(AiFlowDtos.Event e)` 新增 toolCall 驗證（與 501.2.2 相同規則與五個不變式，不得共用有 `c<32 && c!=10` 限制的 `text()`／`textAllowEmpty()`，它們拒絕 `\t` `\r` 且以 `String.length()` 計長）；`detail()` 驗 `Node.toolCall == null`，新增 `toolCallDetail()` 驗證返回 DTO identity、query identity 與其非 null ToolCall。不得由 Node 重建 Event 時把原始參數再次塞回一般 detail。
- [ ] 501.3.4 `AiFlowBffController`／`AiFlowBffService` 新增 ADMIN 專屬 `GET /api/bff/ai-flow/runs/{runId}/nodes/{eventId}/tool-call?date&consumer`，只向同源 business 的新 internal read 呼叫；同樣 configured-admin owner headers、5 秒 timeout、`private, no-store`、既有錯誤碼與 error-ingest 排除。它不是 9090／gateway／frontend exact-404 路由，不新增公開 API。
- [ ] 501.3.5 測試呼叫處同步：`AiFlowFixtures.java`（約第 8 行）、`AiFlowOperationContractTest.java`（約第 67、73 行）、`AiFlowTemporalContractTest.java`（約第 73 行）等所有 `new AiFlowDtos.Event(`／`Node(`；`AiFlowProtocolTest` 中以 `.replace(",\"reportedOutcome\":null","")` 去除 null key 的做法需確認仍成立。
- [ ] 501.3.6 測試：讀 78-case `ai_flow_tool_call_vectors.json`，每個案例走 `AiFlowProtocol.batch(bytes, clock)`，結果必須等於 `valid`；其中 `sourceTruncated` 缺省序列化後不得產生 null key，`true` 通過、`false`／非 boolean／null 均拒絕，覆蓋 boxed `Boolean` shape 分支。入站四組——toolCall 缺省通過、`toolCall:null` 400、非 START 400、合法帶 toolCall 通過；讀取側五組——舊 Node（`toolCall:null`）的 detail 通過、新 Node 帶 null 通過、Node 缺 `toolCall` key 回 `UPSTREAM_INVALID`、Node 的非 null toolCall 回 `UPSTREAM_INVALID`、`ToolCallDetail` 的 null／合法非 null 各通過且違反不變式回 `UPSTREAM_INVALID`；`AiFlowBffServiceTest`／`AiFlowSecurityTest`（授權、錯誤碼、error-ingest 排除不變）／`AiFlowOperationContractTest`（OpenAPI ingress 與實作一致）維持綠燈。量測：300 個各 16384 emoji toolCall 的**一般 detail** JSON <16MiB，逐節點 tool-call 回應 <256KiB，兩者記錄於完成報告；不得以提高全域 `WebClientConfig` codec 上限掩蓋 aggregate-response 問題。

### 501.4 collector（一支 subagent，檔案：`scripts/collect-srpp-ai-flow.py`、`scripts/send-srpp-ai-flow.py`（如需）、`scripts/tests/test_collect_srpp_ai_flow.py`）

- [ ] 501.4.0 同步 `tool_label()` 既有 docstring：它本身仍只由呼叫文字擷取工具**名稱**，不把參數寫入 title／summary／label；但不得再泛稱「不保存參數」，須明確說明 Task 501 的 `tool_call()` 另依本契約保存已遮蔽的 `toolCall.parameters`，不保存工具輸出。Task 500 的既有名稱擷取語意維持不變；對同一呼叫只可執行一次 `tool_label(payload)`，其結果由本輪 `toolCall.innerNames` 重用，不得為 toolCall 再掃一次完整原始文字。
- [ ] 501.4.1 新增常數與純函式（不做 I/O）：
  - `TOOL_PARAM_LIMIT = 16384`、`TOOL_PARAM_PRESCAN_LIMIT = 1048576`、`REDACTED = "[REDACTED]"`、`REDACTED_COUNT_MAX = 100000`。
  - `redact_credentials(text: str) -> tuple[str, int]`：依序套用下列規則，只取代**祕密值**，保留 key／前綴；回傳取代後文字與總取代次數。規則以行為定義，實作者自行寫 regex，但下列正反例樣本必須全數通過：
    1. `Bearer`／`Basic` 後接 ≥8 個 `[A-Za-z0-9._~+/=-]` 的憑證 → `Bearer [REDACTED]`；
    2. 命名祕密的 `key:value`／`key=value`／JSON `"key":"value"`：key 名稱以 `_ . -` 與駝峰邊界切成小寫段後，其**連續段序列**等於 `authorization｜token｜secret｜password｜passwd｜apikey｜api key｜access key｜private key｜cookie｜credential` 之一（所以 `X-AI-Flow-Token`、`API_KEY`、`access_token`、`clientSecret` 命中；`max_tokens`（段為 `max`、`tokens`）、`tokenizer`、`secret_santa` 不命中）；值字元集排除空白、`"` `'` `\` `,` `;` `&` `)` `}` `]`（因此能處理值被 `\"` 跳脫在另一層 JSON 字串內的情形）；值長 <4 或值已是 `[REDACTED]` 不重複取代；
    3. JWT：`eyJ…` 三段 base64url；
    4. 常見 provider key：`sk-…`（≥20）、`ghp_／gho_／ghu_／ghs_／ghr_／github_pat_…`、`AKIA[0-9A-Z]{16}`、`xox[abprs]-…`、`AIza…`（35）、`ya29.…`；
    5. URL userinfo：`scheme://user:pass@host` → `scheme://user:[REDACTED]@host`；
    6. PEM `-----BEGIN … PRIVATE KEY-----` 到 `END` 行（無 END 則到文字結尾）整段取代。
  - 必須命中（每個都要 count≥1 且原祕密值不得殘留）：`Authorization: Bearer abcdefgh12345678`、`curl -H 'X-AI-Flow-Token: abcd1234efgh5678'`、`API_KEY=sk-live-abcdef0123456789abcd`、`password=hunter2hunter2`、`{"access_token":"abc123def456"}`、`{"clientSecret":"abc123def456"}`、轉成 JSON 字串後的 `{\"cmd\":\"curl -H 'Authorization: Bearer abc12345def'\"}`、`https://user:pass1234@example.com/x`、一個合成 JWT、`ghp_` 加 36 位字元的合成值、合成 PEM 區塊。
  - 必須**不**變動（count==0）：`{"max_tokens": 1000}`、`tokenizer`、`git commit -m 'fix token handling'`、`{"query":"台積電 2330 本週走勢"}`、`curl http://127.0.0.1:9090/api/assets/latest`、`secret_santa`、一般 base64 長字串。
  - `clean_parameter_text(text) -> str`：把除 `\t\n\r` 外的 C0 控制字元、DEL（0x7f）與孤立 surrogate 換成 `�`（不計入 redactedCount）。
  - `tool_call(payload: dict, inner_names: list[str]) -> dict | None`：**全函式、不得拋例外**（最外層 `try/except Exception` 回 None），輸出必須通過下方的 `validate_tool_call`。`payload.get("name")` 不是 str 或不符 `TOOL_NAME.fullmatch` → None。`inner_names` 是本次同一呼叫**唯一一次** `tool_label(payload)` 已得的去重名單，函式只驗每一項白名單、去重並取前 8 個；`tool_call` 不得呼叫 `tool_label`、不得以 `INNER_TOOL_*` 對完整原始文字再掃一次。這保留 Task 500 名稱擷取的現有語意，並使 `toolCall.innerNames` 與 title／summary 的名稱來源相同。原始文字：`payload.get("input", payload.get("arguments", ""))`，str 直接用、dict／list 以 `json.dumps(ensure_ascii=False, separators=(",", ":"))`，其他型別視為 `""`。處理順序固定：先以 `len(raw_text) > TOOL_PARAM_PRESCAN_LIMIT` 判定 `sourceTruncated`（命中時，**只加入** `"sourceTruncated": true`；未命中時 key 必須缺省）→ 只取前 `TOOL_PARAM_PRESCAN_LIMIT` 個 code point（此上限只限制 parameters 的遮蔽／清理及輸出，不改 Task 500 的既有單次名稱擷取）→ redact → clean → `parametersChars = len(text)`（這是已處理前綴的長度，非完整原始長度）→ 超過 `TOOL_PARAM_LIMIT` 取前 16384 並 `truncated=True`；`redactedCount = min(count, REDACTED_COUNT_MAX)`（值達 cap 不能宣稱完整總數）。回傳六 core key 加上必要時的選填 marker。純函式、不讀環境、不寫 state。
  - `validate_tool_call(value, event_kind) -> bool`：與契約、五個不變式完全一致（排除 bool 冒充 int、整串 `fullmatch`）；只允許 core 六 key 或 core 六 key 加 `sourceTruncated:true`。
- [ ] 501.4.2 `parse_source_line` 的 function_call／custom_tool_call 分支：先只呼叫一次 `label = tool_label(payload)`，以它產生既有 `label_info`／title／summary；再從同一 `label` 或 `label_info` 取 names 呼叫 `tc = tool_call(payload, inner_names)`，若非 None 則 `call["toolCall"] = tc`（只加在 START；END 事件、title、summary、operation、eventId、stepId、parents、relation 全不變）。不得讓 `tool_call` 或任何第二段流程重新對完整 input／arguments 做內含工具 regex 掃描；`name` 不合白名單時事件沿用泛稱 title 且不帶 toolCall。
- [ ] 501.4.3 `validate_envelope`：事件 key 集合接受 `EVENT_KEYS` 或 `EVENT_KEYS | {"toolCall"}`；帶 toolCall 時呼叫 `validate_tool_call`，失敗即 `fail()`。`unsafe_public_text`／`BACKEND_SENSITIVE` 不套用到 toolCall。`--enqueue-json` 路徑同樣通過這個驗證。
- [ ] 501.4.4 舊資料相容：state 內既有事件（無 toolCall）原樣保留、不重算；`Store.enqueue` 的 ack 過濾與 pending 內舊批次不受影響；舊 state 的 call 缺新欄位不得拋例外。不得讀取或要求 token、不新增 CLI 旗標。
- [ ] 501.4.5 既有測試必須調整（spec-review 已查證，它們在新行為下按現狀會紅）：`test_private_reasoning_commands_financial_outputs_never_persist`（掃整個 `sources/*.json` 斷言不含 "CANARY"、"Bearer"）、`test_canary_input_never_reaches_title_summary_or_evidence`（對整份 `c.canonical(value)` 斷言不含 "curl"、"https://"、"Authorization"）、`test_naming_changes_only_title_and_summary`（把 `tool_label` mock 成 None 當對照組）。調整原則：把斷言範圍縮到「title／summary／evidence／operation 等 toolCall 以外的欄位」（剔除每個事件的 `toolCall` key 後再檢查），另新增斷言驗證 `toolCall.parameters` 內的 canary 依 501.4.1 規則被遮蔽（涵蓋規則者）或依規格原樣保留（未涵蓋者）；對照組測試則比較剔除 `toolCall` 後的 envelope。不得為通過測試而放寬 title／summary 的檢查。`test_existing_tw_full_canonical_envelopes_and_uuid_are_unchanged`：digest 在該測試函式內的區域 dict `expected`（約第 1059–1063 行）；該 fixture 的呼叫是 `functions.exec`，START 事件會多 toolCall，三個 digest 必然改變——新增「剔除每個事件的 `toolCall` key 後的 envelope 仍等於 Task 500 的三個 digest（09:05 `44036bbf…`、12:00 `d6a3f7ef…`、11:40 `24e0457d…`，完整值取自該 dict）」的斷言，另以明文重算並釘死含 toolCall 的新三個 digest；保留 `runId == 66a62e80-7ba1-54e1-9e9f-b0c22f2ebe55` 與 `assertNotIn("period", …)`；不得為讓舊 hash 回綠而特例化。
- [ ] 501.4.6 新增測試：(a) 讀 78-case `scripts/tests/fixtures/ai_flow_tool_call_vectors.json`，每個案例用 `validate_tool_call(call, eventKind)`（缺省／null 案例用 `validate_envelope` 的事件層）結果必須等於 `valid`，並明確確認 `sourceTruncated:null` 非法；(b) 501.4.1 全部正反例；(c) `tool_call` 對 `exec` 內含多工具、`exec_command` 的 arguments 字串、dict arguments、非字串、空輸入、非白名單 name、超過 1Mi code point 的輸入（必含 `sourceTruncated:true` 且 UI 可據此不宣稱原長）、遮蔽次數超過 100000 的輸入（輸出須被夾住且只把 count 當至少此數）；其中超限複合工具的整合測試必須驗 `tool_label(payload)` 只跑一次、`toolCall.innerNames` 重用該結果（即使其名稱命中在 parameters 預掃前綴之外也維持 Task 500 既有 title 語意），且 `tool_call` 沒有第二次完整 input／arguments 的內含工具 regex 掃描；(d) 截斷邊界 16383／16384／16385 code point（含 emoji 使 Python `len` 與 UTF-16 長度不同的案例）、截斷後 `parametersChars` 為遮蔽後已處理前綴長、孤立 surrogate 與控制字元清理；(e) START 帶 toolCall 而 END 不帶；(f) 整份 envelope 通過 `validate_envelope`，「單一事件 16384 個 emoji」的最壞情況下單批 canonical 位元組 <256KiB 或被 `Store.enqueue` 自動縮小；(g) 跨輪（START → 存檔 → 重載 state → END）仍正常。
- [ ] 501.4.7 量測（寫進完成報告）：300 個各 16384 code point 呼叫的 state 檔大小、一輪處理時間、`Store.enqueue` 切出的 pending 批數與總位元組、sender 以現行「每輪最多 3 批」送完所需輪數；若單檔 state 超過 32MiB 或單輪處理 >2 秒，停止並回報（不得自行改 cap 或預算）。另以最近的真實 Codex rollout 做唯讀取樣（沿用 Task 500 的一次性 Python：`Store(dry_run=True)`＋`Collector.scan_codex`，`python3 -B`，不入庫、不寫 state、不新增 CLI 旗標），**只印統計**（toolCall 事件數、`parametersChars` 的 p50／p99／最大、truncated 數、redactedCount 總和），不得印出任何 parameters 內容。

### 501.5 前端（只可由 subagent 執行，檔案：`frontend/src/api/index.js`、`frontend/src/views/AiFlowView.vue`、`frontend/src/utils/aiFlowView.js`、`frontend/src/utils/aiFlowView.contract.test.js`）

- [ ] 501.5.1 **按需讀取，禁止在 run detail 預載。** `Node.toolCall` 在 `state.detail.nodes` 永遠為 null；在 `frontend/src/api/index.js` 的既有 `bffApi.aiFlow` 新增 `toolCall(runId,eventId,{date,consumer,signal})`，只呼叫自己的 `/bff/ai-flow/runs/{runId}/nodes/{eventId}/tool-call`。`createAiFlowViewState`／loader 增加 `selectedToolCall`、loading/error 與獨立 AbortController／generation fence：選取節點後才讀，清除選取、切換 run／filter、關閉 detail 或卸載時中止，舊 response 永不回填；200/null 是舊／非工具節點且不是錯誤。不得 prefetch、不得寫入 `state.detail` 或 ECharts data。
- [ ] 501.5.2 節點明細（`AiFlowView.vue` 約第 206–245 行）：當按需回應的 `selectedToolCall` 非 null 才顯示兩列——「工具」：`toolCall.name`，`innerNames` 非空時附「（內含 a、b、c）」；「參數」：`<pre>` 區塊顯示 `toolCall.parameters`，樣式 `max-height: 240px; overflow: auto; white-space: pre-wrap; word-break: break-all;`，空字串顯示「（無參數）」；`truncated` 為 true 顯示「已截斷：已處理前綴的遮蔽後長度 {parametersChars} 字元，僅保留前 16384 字元」；`sourceTruncated===true` 顯示「原始輸入超出 1,048,576 字元預掃上限；下列內容與長度只反映已處理前綴」；`redactedCount > 0` 顯示已處理範圍的遮蔽數，值為 100000 時文字必須是「至少 100000 處」，不得稱精確總數。全部用 Vue 文字插值（`{{ }}`），**禁止 `v-html`**，不得進 ECharts label／tooltip／richText、時間軸或慢速步驟表，不得 `console.*` 輸出參數。`selectedToolCall` 為 null 或載入前／失敗時不顯示參數內容、不留空白佔位；可顯示不含參數本身的載入／錯誤狀態。
- [ ] 501.5.3 `mapAiFlowGraphData`（`aiFlowView.js` 約第 81 行）目前把整個 node 以 `rawNode: node` 放進 series data（`AiFlowView.vue` 約第 387 行以 `...node` 展開）。為防禦未來上游偏差，僅此處做最小修改：`rawNode` 不含 `toolCall`（例如 `const { toolCall, ...rawNode } = node`）。除此之外不改圖資料映射、路由或任何非 AI-flow BFF 呼叫。
- [ ] 501.5.4 測試併入既有 `frontend/src/utils/aiFlowView.contract.test.js`（`frontend/package.json` 的 `npm test` 以明列檔名串接，獨立新檔不會被執行），沿用其對原始碼 regex 斷言的風格，並加一個執行 `mapAiFlowGraphData` 的行為斷言：`Node.toolCall`／`selectedToolCall.parameters` 未出現在 label／tooltip 設定或 `tooltipText`、未出現在 console 呼叫，任何 series data 項目（含 `rawNode`）都不含 `toolCall`；另驗證 BFF 按需 endpoint、selection 的 abort/generation fence、null 回應不顯示行、`<pre>`／`v-if`／無 `v-html`／max-height／overflow、source-truncated 與 capped redaction 的誠實文案。向使用者回報驗收時說明：參數只在選取後的節點明細，圖與 tooltip 不顯示。

### 501.6 spec 同步（主 agent）

- [ ] 501.6.1 `spec/requirements.md`（Requirement 190 的 Task 501 修訂段）、`spec/design.md`（Requirement 190／Task 501 段）與 `spec/steering/{tech,structure}.md`（目前 observer 版號及此具名例外）已在 spec gate 前寫入；實作若與其不符，先修 spec 再改程式。
- [ ] 501.6.2 `CLAUDE_CODE_REMOTE=true bash scripts/spec-check.sh` 全綠。

### 501.7 SRPP 側身分同步（主 agent，不由 subagent；需 501.1 完成；在 SRPP 側另開的 git worktree 進行，不碰 SRPP 主 checkout）

- [ ] 501.7.1 把本專案重產的 `docs/openapi/9090-api-swagger.md` 位元組複製為 SRPP worktree 的 `docs/9090 Port API Swagger.md`。
- [ ] 501.7.2 `python3 scripts/sync-srpp-swagger-identity.py --srpp-root <SRPP worktree> --check`：此時**預期**以 `CONSUMER_SCHEMA_SWAGGER_MISMATCH` 結束（它是前置條件檢查，不是 dry-run diff），出現 `POLICY_SOURCE_DRIFT`、`SWAGGER_MIRRORS_DIFFER`、`POLICY_PATHS_CHANGED` 或其他錯誤碼即停止回報。再不帶 `--check` 執行：它只更新 SRPP schema 的 `x-swagger-sha256`、`daily_runtime_contract.json` 與 policy manifest 的對應 source hash，並同步 backend 的 `srpp/decision/policy_manifest.json`；再以 `--check` 執行應回 `CURRENT`。僅允許 Swagger 身分相關 hash 變動，其他漂移 fail closed。
- [ ] 501.7.3 bundle SHA 因此改變（`ef4bd047…` 不再有效），backend 的 report authority 衍生物必須依 t499 同款流程重產並更新釘死值：`python3 scripts/generate-srpp-report-golden.py --srpp-root <SRPP worktree>`、`ruby scripts/srpp-report-policy-register.rb > backend/src/main/resources/srpp/report/d201-registration.sql`（insert-only 新 hash 登錄，不 UPDATE／DELETE 歷史 row）、更新 `DailyDecisionCalculatorTest`／`DecisionPolicyAuthorityTest` 等測試中釘死的 bundle SHA（D-204／policy 2.53.0／model 3.22.0 本身不變）、`src/test/resources/srpp/report-same-input-golden.json`；驗證：`python3 scripts/check-srpp-report-authority.py --srpp-root <SRPP worktree> --check`、`ruby scripts/tests/srpp-policy-register-test.rb`、`ruby scripts/tests/srpp-report-policy-register-test.rb`、`python3 scripts/tests/check-srpp-report-authority-test.py`、`python3 scripts/tests/test_sync_srpp_swagger_identity.py`、`mvn -q -f backend/pom.xml test`（全套）。
- [ ] 501.7.4 SRPP 側 worktree 內 `python3 scripts/policy_bundle.py check --purpose daily --consumer Codex` 通過；不帶 `CLAUDE_CODE_REMOTE` 的 `ruby scripts/render-9090-openapi-docs.rb --check` 與 `ruby scripts/tests/docker-external-api-openapi-test.rb` 在「SRPP 主 checkout 已合併 SRPP 側分支」之後才會全綠，這是 501.8 的合併順序問題，不是 501.7 的失敗條件。
- [ ] 501.7.5 SRPP 政策文件不在此改（見背景「已知缺口」）。

### 501.8 部署（不屬本任務實作；需使用者逐步授權）

整組原子性：SRPP 側與 asset-management 側分支、Swagger 身分、backend 的 report authority 登錄必須同時生效，否則下一個台北交易日 09:05 的 SRPP capture／evaluate 會回 409 `SWAGGER_MISMATCH`，或 L0 規則包不一致而停止當日報告。建議順序：

1. 合併順序（避免 main 上 spec-check／契約測試出現紅燈視窗）：先合併 SRPP 側分支至 SRPP main，再合併 asset-management 分支至 main；兩者合併後不帶 `CLAUDE_CODE_REMOTE` 的 `ruby scripts/render-9090-openapi-docs.rb --check`、`ruby scripts/tests/docker-external-api-openapi-test.rb`、`bash scripts/spec-check.sh` 必須全綠。
2. 從乾淨 main 一次重建並 recreate `business-services` 與 `bff`（backend 與 BFF 的 Node／Event shape 互相嚴格驗證，必須同時更新），再重建 `frontend`；部署前依 CLAUDE.md 比對 `.env`。
3. 執行 t499 產生的 insert-only registration SQL 一次，唯讀回讀 registry row、jar 內 embedded artifact 與 `localhost:9090` report-calculations 的精確 bundle SHA。
4. **最後**才 `launchctl kickstart -k gui/$(id -u)/com.steven.srpp-ai-flow-collector` 重啟 observer（不改 plist／token／main.env、不刪 state、不 `--replay`）；若 collector 早於 backend／BFF 更新，帶 toolCall 的批次會被 400 拒收並堵死 sender 佇列。
5. 下一次真排程後，用 ADMIN 頁面確認新 run 的節點明細顯示工具名稱與參數、舊 run 不顯示。

## 驗證

```bash
python3 -B -m unittest discover -s scripts/tests -p 'test_collect_srpp_ai_flow.py' -v
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test -Dtest='AiFlow*Test'
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test -Dtest='AiFlow*Test'
(cd frontend && PATH=/Users/steven/.nvm/versions/node/v22.21.0/bin:$PATH node --test src/utils/aiFlowView.contract.test.js)
(cd frontend && PATH=/Users/steven/.nvm/versions/node/v22.21.0/bin:$PATH npm run build)
CLAUDE_CODE_REMOTE=true ruby scripts/render-ai-flow-openapi-docs.rb --check
CLAUDE_CODE_REMOTE=true ruby scripts/render-9090-openapi-docs.rb --check
CLAUDE_CODE_REMOTE=true ruby scripts/tests/docker-external-api-openapi-test.rb
CLAUDE_CODE_REMOTE=true ruby scripts/tests/unified-9090-openapi-renderer-test.rb
CLAUDE_CODE_REMOTE=true bash scripts/spec-check.sh
```

`AiFlowRepositoryPostgresTest` 需要 PostgreSQL 容器；無法執行時須在完成報告明確寫出「未執行」與原因，不得以其他測試代替宣稱已驗證。前端 `npm test` 的 `openApiContract.test.js` 已知有 Task 497 遺留的既有紅燈（斷言金融 24 路／union 25，現況 26／27），與本任務無關，所以驗證指令改為直接跑 `aiFlowView.contract.test.js` 並另跑 `npm run build`，不以 `&&` 串接。501.7 另有自己的驗證清單。

## 完成報告

（實作者做完後回填：實際改了哪些檔、驗證輸出、與原計畫的偏差及原因。）
