# AI 判斷流程觀測補充 API

版本：1.4.0；來源 SHA256：`9259cfb0464c9bfd3f7d8b4a17e56445b907dec2b2b20f91778a9e73c12ed892`

獨立於原金融OpenAPI；只保存觀測事件，不調度LLM或執行金融操作。金融26 routes與本1觀測route的統一Swagger維持27 method/path pairs，且本契約變更會連動PublishedSwaggerIdentity與SRPP消費者身分同步。流程只保留90個台北日期，正常DB backup保留schema但排除本功能兩表data。 Task 504只為同一筆Codex公開工具START事件新增由版本化public trace exporter明示的choiceSet；它不是Chain-of-Thought，沒有公開候選時必須缺省。被動host collector仍非同步收集，不改SRPP規則或排程。

## POST /api/srpp/ai-flow/events

記錄雙軌AI公開執行事件

唯一exact POST，非POST405，descendant/matrix404。專用三token非空且互異；Codex/Claude schedule token只准自身consumer/SCHEDULE_EVENTS，collector只准Codex/CODEX_ROLLOUT或Claude/CLAUDE_ARTIFACT。choiceSet僅限collector的Codex/CODEX_ROLLOUT，其他grant或來源403且零寫入。授權在owner/DB/error-ingest I/O前；整條ingress所有結果不觸發API error ingestion。caller owner/email/headers不能override configured-admin。schema任何深度duplicate/unknown拒絕；1..100事件、256KiB、stable UUID，全batch atomic；相同內容replay，異內容409，無parent409，cycle400。新run未來日期400，90日外410且零寫入。sender是獨立背景工作，現有LLM流程不逐步call或等待。title／summary仍禁止私人思考鏈、原commands/env/token/帳戶/持股/金額/完整API/MIME並採allowlist；唯使用者2026-10-18授權的公開工具START事件可在toolCall.parameters保存原始參數，collector僅以[REDACTED]最佳努力遮蔽憑證樣式、上限16384 Unicode code point，且不收工具輸出。task_complete/final只表示執行結束，verifiedReportOutcome仍UNCONFIRMED。

成功／失敗回應：

| HTTP | 用途 |
| --- | --- |
| 200 | 純冪等重播，所有內容已durable保存。 |
| 201 | 新增或混合事件已全batch原子提交。 |
| 400 | 無效schema/query/graph（含任何深度duplicate/unknown）或未來日期。 |
| 401 | 缺/錯/空/多值X-AI-Flow-Token；owner/DB/upstream/error-ingest零I/O。 |
| 403 | token consumer/source grant不符。 |
| 405 | exact path只允POST，Allow: POST。 |
| 409 | RUN_METADATA_MISMATCH/EVENT_CONFLICT/MISSING_PARENT。 |
| 410 | RETENTION_EXPIRED，過期首次/重播皆零寫入。 |
| 413 | JSON body超過256KiB。 |
| 415 | Content-Type非application/json。 |
| 500 | 未預期服務失敗，sanitized INTERNAL_ERROR。 |
| 502 | 下游回應無效或服務連線失敗。 |
| 503 | 三token/configured-admin未ready，CONFIGURATION_UNAVAILABLE/OWNER_UNAVAILABLE。 |
| 504 | 有界下游逾時，UPSTREAM_TIMEOUT。 |

輸入範例（只供隔離測試，不可回填為真執行）：

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
      "operation": {
        "method": "LOCAL",
        "path": "calendar"
      },
      "reasonCode": null,
      "attemptNo": 1,
      "parentEventIds": [

      ],
      "relation": null,
      "evidenceRefs": [

      ],
      "reportedOutcome": null
    },
    {
      "eventId": "9f3ba524-c143-5bb8-9514-45e783a646ab",
      "sequence": 1,
      "stepId": "tool-date",
      "eventKind": "START",
      "status": "RUNNING",
      "occurredAt": "2026-10-08T09:05:03+08:00",
      "title": "公開工具呼叫：exec",
      "summary": "以公開工具讀取日期",
      "operation": {
        "method": "TOOL",
        "path": "tool"
      },
      "reasonCode": null,
      "attemptNo": 1,
      "parentEventIds": [

      ],
      "relation": null,
      "evidenceRefs": [

      ],
      "reportedOutcome": null,
      "toolCall": {
        "name": "exec",
        "innerNames": [

        ],
        "parameters": "{\"cmd\":\"date\"}",
        "parametersChars": 14,
        "truncated": false,
        "redactedCount": 0
      },
      "choiceSet": {
        "title": "日期讀取方式",
        "selectedOptionId": "current-date",
        "options": [
          {
            "optionId": "current-date",
            "label": "目前日期",
            "toolName": "exec",
            "parameters": "{\"cmd\":\"date\"}",
            "parametersChars": 14,
            "truncated": false,
            "redactedCount": 0
          },
          {
            "optionId": "utc-date",
            "label": "協調世界時間",
            "toolName": "exec",
            "parameters": "{\"cmd\":\"date -u\"}",
            "parametersChars": 17,
            "truncated": false,
            "redactedCount": 0
          }
        ]
      }
    }
  ]
}
```

## 憑證

與金融、mail、broker token完全分離的觀測憑證；不得print/log/存入event。

## AiFlowOperation

公開操作分類；HTTP僅canonical path，LOCAL/TOOL僅六個allowlist分類。原始工具參數只可依Task 501存於獨立的AiFlowToolCall.parameters，絕不寫入operation。

| 欄位 | 型別／限制 | 必填 | 用途 |
| --- | --- | --- | --- |
| method | "string" {"enum":["GET","POST","PUT","PATCH","DELETE","LOCAL","TOOL"]} | 是 | 觀測操作協定碼。 固定值：GET／POST／PUT／PATCH／DELETE／LOCAL／TOOL。 |
| path | "string" {"maxLength":500} | 是 | HTTP 不含host/query/fragment；LOCAL/TOOL限calendar、policy、validation、render、artifact、tool。 |

## AiFlowEvidence

不可變公開證據參照；不是證據rawcontent或金融完成認證。

| 欄位 | 型別／限制 | 必填 | 用途 |
| --- | --- | --- | --- |
| role | "string" {"enum":["CALENDAR","POLICY","API_RECEIPT","VALIDATOR","RESULT","TIMING","DRAFT_RECEIPT","MIME_RECEIPT","APP_TERMINAL","TRACE_COVERAGE"]} | 是 | 證據用途協定碼；TRACE_COVERAGE只表示紀錄完整度。 固定值：CALENDAR／POLICY／API_RECEIPT／VALIDATOR／RESULT／TIMING／DRAFT_RECEIPT／MIME_RECEIPT／APP_TERMINAL／TRACE_COVERAGE。 |
| id | "string" {"maxLength":200} | 是 | 無URL/檔案路徑opaque identity；coverage精確TRACE_V1:cursorBytes:sourceSizeBytes。 |
| hash | ["string", "null"] {"pattern":"^[0-9a-f]{64}$"} | 是 | 來源SHA256；未知為null，禁止捏造。 |

## AiFlowRunMetadata

run immutable identity；server owner不能由producer指定；同source identity不可另建run。22:00美股時段與Codex／22-00雙向綁定；台股三時段保留既有身份相容範圍。

| 欄位 | 型別／限制 | 必填 | 用途 |
| --- | --- | --- | --- |
| runId | "string" {"format":"uuid"} | 是 | 真source identity導出的穩定UUID。 |
| consumer | "string" {"enum":["Codex","Claude"]} | 是 | 排程軌別，token授權綁定。 固定值：Codex／Claude。 |
| reportDate | "string" {"format":"date"} | 是 | 原排程台北日期，不以接收或完成日期替代；保留今天及前89日期。 |
| reportSlot | "string" {"enum":["09:05","12:00","11:40","22:00"]} | 是 | 台股早盤／午盤、原11:40歷史時點與美股22:00排程時段；提前、補跑或跨午夜仍保留plannedslot。 固定值：09:05／12:00／11:40／22:00。 |
| sourceKind | "string" {"enum":["CODEX_ROLLOUT","CLAUDE_ARTIFACT","SCHEDULE_EVENTS"]} | 是 | schedule token只能SCHEDULE_EVENTS；collector只能與consumer一致的CODEX_ROLLOUT/CLAUDE_ARTIFACT。 固定值：CODEX_ROLLOUT／CLAUDE_ARTIFACT／SCHEDULE_EVENTS。 |
| sourceRunId | "string" {"maxLength":200} | 是 | exact thread_id+turn_id或有明確身份artifact的opaque ID；不得mtime拼接。 |
| automationId | "string" {"enum":["codex-srpp-09-30","codex-srpp","srpp-0905-claude","srpp-1150-claude","22-00"]} | 是 | 已知排程exact ID；Codex台股為codex-srpp-09-30/codex-srpp、美股為22-00，Claude台股為srpp-0905-claude/srpp-1150-claude；未查證Claude美股來源不猜ID。 固定值：codex-srpp-09-30／codex-srpp／srpp-0905-claude／srpp-1150-claude／22-00。 |
| startedAt | ["string", "null"] {"format":"date-time"} | 是 | 真實公開開始時間；缺來源為null。 |
| workflowVersion | "string" {"maxLength":100} | 是 | 觀測協定版本，不替代投資政策版本。 |

## AiFlowToolCall

使用者2026-10-18授權保存的公開工具呼叫輸入；只允許START事件，輸出永不保存，collector只做憑證樣式的最佳努力遮蔽。

| 欄位 | 型別／限制 | 必填 | 用途 |
| --- | --- | --- | --- |
| name | "string" {"minLength":1,"maxLength":64,"pattern":"^[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}$"} | 是 | 公開工具名稱，保留payload原始名稱與functions.等前綴，必須完整符合白名單識別字格式。 |
| innerNames | "array" {"minItems":0,"maxItems":8,"uniqueItems":true,"items":{"type":"string","description":"一個完整符合白名單格式的公開內含工具名稱，不含任何參數或工具輸出。","minLength":1,"maxLength":64,"pattern":"^[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}$"}} | 是 | exec或run_code呼叫文字中已擷取的公開內含工具名稱；非複合工具使用空陣列，最多八個不重複名稱。 |
| parameters | "string" {"maxLength":16384} | 是 | 經最佳努力憑證樣式遮蔽後的公開工具原始參數；以Unicode code point計，最多16384，永不保存工具輸出。 |
| parametersChars | "integer" {"minimum":0,"maximum":2147483647} | 是 | 已處理來源前綴在16384輸出截斷前、經遮蔽與清理後的Unicode code point數；不是完整原始輸入長度。 |
| truncated | "boolean"  | 是 | 當已處理前綴超過16384 Unicode code point且parameters只保留前16384個時為true；否則為false。 |
| redactedCount | "integer" {"minimum":0,"maximum":100000} | 是 | 已處理來源前綴中被固定[REDACTED]標記取代的憑證片段數；100000代表至少此數，不保證精確總數。 |
| sourceTruncated | "boolean" {"const":true} | 否 | 僅在原始輸入超過1048576 Unicode code point預掃上限時為true；缺省表示未命中，false與null非法。 |

## AiFlowChoiceOption

上游public trace exporter已明示的單一公開工具候選；只保留已遮蔽參數，沒有工具輸出、private reasoning或巢狀raw envelope。

| 欄位 | 型別／限制 | 必填 | 用途 |
| --- | --- | --- | --- |
| optionId | "string" {"minLength":1,"maxLength":64,"pattern":"^[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}$"} | 是 | 候選的閉合公開識別字，僅在本choiceSet內唯一且可作selectedOptionId。 |
| label | "string" {"minLength":1,"maxLength":100} | 是 | public-safe候選顯示名稱，以Unicode code point計1至100；不得含命令、帳戶、金額或private reasoning。 |
| toolName | "string" {"minLength":1,"maxLength":64,"pattern":"^[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}$"} | 是 | 候選公開工具名稱；selected option必須逐欄等於同事件的實際toolCall。 |
| parameters | "string" {"maxLength":16384} | 是 | 同Task 501標準化與憑證樣式遮蔽後的公開候選參數；只在ADMIN逐節點明細回傳，所有候選合計最多32768 code point。 |
| parametersChars | "integer" {"minimum":0,"maximum":2147483647} | 是 | 遮蔽與控制字元清理後、parameters截斷前的Unicode code point數。 |
| truncated | "boolean"  | 是 | 當處理後參數超過16384 Unicode code point且parameters保留前綴時為true。 |
| redactedCount | "integer" {"minimum":0,"maximum":100000} | 是 | 已用固定遮蔽標記取代的憑證樣式數，100000表示至少該數。 |
| sourceTruncated | "boolean" {"const":true} | 否 | 只在候選公開input超過1048576 Unicode code point預掃上限時為true；false與null非法。 |

## AiFlowChoiceSet

版本化public trace exporter明示的公開候選集合，不是LLM private reasoning；僅能附於未sourceTruncated的Codex CODEX_ROLLOUT START toolCall。

| 欄位 | 型別／限制 | 必填 | 用途 |
| --- | --- | --- | --- |
| title | "string" {"minLength":1,"maxLength":100} | 是 | public-safe候選集合標題，以Unicode code point計1至100；不能含private reasoning、命令、帳戶、持股或金額。 |
| selectedOptionId | "string" {"minLength":1,"maxLength":64,"pattern":"^[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}$"} | 是 | 唯一採用候選的閉合識別字，必須存在於options且其完整參數與metadata逐欄等於實際toolCall。 |
| options | "array" {"minItems":2,"maxItems":8,"items":{"$ref":"#/components/schemas/AiFlowChoiceOption","description":"一個封閉的公開候選，沿用AiFlowChoiceOption完整限制。"}} | 是 | 兩至八個明示公開候選，optionId不得重複；collector不從工具目錄、後續呼叫或缺漏資料推測選項。 |

## AiFlowPublicDecisionOption

使用者明確公開的候選摘要；reason、refs及擬議工具輸入只供ADMIN按需明細，不代表工具已執行。

| 欄位 | 型別／限制 | 必填 | 用途 |
| --- | --- | --- | --- |
| id | "string" {"minLength":1,"maxLength":64,"pattern":"^[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}$"} | 是 | 本公開決策內唯一的閉合識別字。 |
| label | "string" {"minLength":1,"maxLength":100} | 是 | 沿既有public-safe限制的公開文字，以Unicode code point計數。 |
| action | "string" {"minLength":1,"maxLength":100} | 是 | 任意public-safe公開動作描述，不是業務狀態enum。 |
| reason | "string" {"minLength":1,"maxLength":500} | 是 | 明確公開的簡短理由，最佳努力憑證遮蔽後1至500 code point；私人思考標記拒絕、超長拒絕不截斷。 |
| evidenceRefs | "array" {"maxItems":3,"items":{"$ref":"#/components/schemas/AiFlowEvidence","description":"閉合公開依據。"}} | 是 | 最多三筆既有閉合Evidence公開依據。 |
| toolCall | nil {"oneOf":[{"$ref":"#/components/schemas/AiFlowToolCall","description":"完整擬議工具公開輸入。"},{"type":"null","description":"未擬議工具輸入。"}]} | 是 | 擬議工具輸入或null，innerNames必須空陣列；不是實際toolCall。沿Task501參數與截斷metadata限制。 |

## AiFlowPublicDecision

版本化LLM公開決策摘要；只允許OBSERVATION/UNKNOWN及collector grant，Codex/CODEX_ROLLOUT或Claude/CLAUDE_ARTIFACT。所有候選完整保存，參數合計最多32768 code point、整物件JSON UTF8最多196608 bytes；不是私人思考或執行證明。

| 欄位 | 型別／限制 | 必填 | 用途 |
| --- | --- | --- | --- |
| source | "string" {"const":"LLM_PUBLIC_SUMMARY_V1"} | 是 | LLM_PUBLIC_SUMMARY_V1表示公開摘要唯一來源版本。 |
| decisionId | "string" {"minLength":1,"maxLength":64,"pattern":"^[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}$"} | 是 | 本公開決策內唯一的閉合識別字。 |
| topic | "string" {"minLength":1,"maxLength":100} | 是 | 沿既有public-safe限制的公開文字，以Unicode code point計數。 |
| options | "array" {"minItems":1,"maxItems":32,"items":{"$ref":"#/components/schemas/AiFlowPublicDecisionOption","description":"一筆完整候選。"}} | 是 | 全部公開候選，id唯一；不得因上限刪除候選，超額整筆拒絕。 |
| selectedOptionId | ["string", "null"] {"minLength":1,"maxLength":64,"pattern":"^[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}$"} | 是 | 明確選擇的現有option id或null；null表示未選擇。 |

## AiFlowEvent

不可變事件；所有欄位閉合，任何深度重複或未知JSON field拒400；除reportedOutcome與可省略的toolCall／choiceSet外，nullable key均必填。

| 欄位 | 型別／限制 | 必填 | 用途 |
| --- | --- | --- | --- |
| eventId | "string" {"format":"uuid"} | 是 | 按真source event identity/kind導出的穩定UUID。 |
| sequence | "integer" {"minimum":0} | 是 | 真來源行序；完整度assessment使用cursorBytes，可同序號不同ID。 |
| stepId | "string" {"maxLength":100} | 是 | 同一觀測操作/attempt的opaque ID。 |
| eventKind | "string" {"enum":["START","END","OBSERVATION","TERMINAL"]} | 是 | 事件生命週期協定碼。 固定值：START／END／OBSERVATION／TERMINAL。 |
| status | "string" {"enum":["RUNNING","SUCCEEDED","FAILED","STOPPED","SKIPPED","UNKNOWN"]} | 是 | 公開觀測到的操作狀態，UNKNOWN表示不可證實。 固定值：RUNNING／SUCCEEDED／FAILED／STOPPED／SKIPPED／UNKNOWN。 |
| occurredAt | ["string", "null"] {"format":"date-time"} | 是 | 公開來源ISO offset時間；未知null，不猜mtime。 |
| title | "string" {"maxLength":100} | 是 | 公開動作標題，不可包含私人思考或秘密。 |
| summary | "string" {"maxLength":500} | 是 | allowlist公開操作摘要，不收原command/env/header/完整API/MIME/帳號/持股/金額。 |
| operation | #/components/schemas/AiFlowOperation  | 是 | 公開操作協定。 |
| toolCall | #/components/schemas/AiFlowToolCall  | 否 | 選填的公開工具START呼叫資料；缺省表示無此資訊，明確null與非START事件皆非法。 |
| choiceSet | #/components/schemas/AiFlowChoiceSet  | 否 | 選填的公開候選集合；只限同一Codex CODEX_ROLLOUT START toolCall與collector grant，actual sourceTruncated、明確null或任一不一致皆非法。 |
| publicDecision | #/components/schemas/AiFlowPublicDecision  | 否 | 選填公開決策摘要；缺省合法、null非法，只允許OBSERVATION及UNKNOWN。 |
| reasonCode | ["string", "null"] {"maxLength":100} | 是 | 公開停止/失敗或完整度原因碼；未知為null。 |
| attemptNo | "integer" {"minimum":1} | 是 | 同一step重試的正整數，不覆写上一attempt。 |
| parentEventIds | "array" {"items":{"type":"string","format":"uuid","description":"每一筆為同run的穩定事件UUID。"},"maxItems":16,"uniqueItems":true} | 是 | 同run已保存或本batch存在的父事件；禁止self/cycle。 |
| relation | ["string", "null"] {"enum":["CALL_RESULT","DEPENDS_ON","BRANCH","NEXT_OBSERVED",null]} | 是 | 真因果關係或僅接續紀錄；NEXT_OBSERVED只代表時間先後。 固定值：CALL_RESULT／DEPENDS_ON／BRANCH／NEXT_OBSERVED／null。 |
| evidenceRefs | "array" {"items":{"$ref":"#/components/schemas/AiFlowEvidence","description":"每一筆沿用對應的完整typed schema。"},"minItems":0,"maxItems":10} | 是 | 最多十個公開證據參照。 |
| reportedOutcome | ["string", "null"] {"enum":["COMPLETED","PARTIAL","INCOMPLETE","STOPPED_L0","STOPPED_CONTRACT","SKIPPED_NON_TRADING_DAY",null]} | 否 | AI自報結果；不是獨立validator/MIME完成認證，可省略或null。 固定值：COMPLETED／PARTIAL／INCOMPLETE／STOPPED_L0／STOPPED_CONTRACT／SKIPPED_NON_TRADING_DAY／null。 |

## AiFlowEventBatch

只記錄已發生公開流程，不控制排程、不呼叫金融API，不影響現有LLM critical path。

| 欄位 | 型別／限制 | 必填 | 用途 |
| --- | --- | --- | --- |
| schemaVersion | "integer" {"const":1} | 是 | 此觀測協定版本固定1。 固定值：1。 |
| run | #/components/schemas/AiFlowRunMetadata  | 是 | 不可變run身份。 |
| events | "array" {"items":{"$ref":"#/components/schemas/AiFlowEvent","description":"每一筆沿用對應的完整typed schema。"},"minItems":1,"maxItems":100} | 是 | 同run事件batch；eventId唯一；全batch原子提交後才ack。 |

## AiFlowAck

只在持久化transaction提交後回覆；純重播200，新或混合201；各count加總等於batch唯一事件數。

| 欄位 | 型別／限制 | 必填 | 用途 |
| --- | --- | --- | --- |
| runId | "string" {"format":"uuid"} | 是 | 已提交run UUID。 |
| inserted | "integer" {"minimum":0} | 是 | 本次新增事件數。 |
| replayed | "integer" {"minimum":0} | 是 | 內容完全相同的既有事件數。 |
| eventIds | "array" {"items":{"type":"string","format":"uuid","description":"每一筆為同run的穩定事件UUID。"},"minItems":1,"maxItems":100,"uniqueItems":true} | 是 | 恰好此次batch所有UUID，不是排隊假ack。 |

## AiFlowProblem

完整sanitized application/problem+json；不包含憑證、owner或raw payload。

| 欄位 | 型別／限制 | 必填 | 用途 |
| --- | --- | --- | --- |
| type | "string" {"const":"about:blank"} | 是 | RFC9457一般problem類型。 固定值：about:blank。 |
| title | "string"  | 是 | 可公開的問題標題。 |
| status | "integer" {"minimum":100} | 是 | 與實際HTTP status一致。 |
| detail | "string"  | 是 | sanitized問題說明。 |
| instance | "string"  | 是 | 發生問題的canonical API path。 |
| code | "string" {"enum":["INVALID_REQUEST","INVALID_GRAPH","TOKEN_REQUIRED","FORBIDDEN","NOT_FOUND","METHOD_NOT_ALLOWED","EVENT_CONFLICT","RUN_METADATA_MISMATCH","MISSING_PARENT","RETENTION_EXPIRED","PAYLOAD_TOO_LARGE","UNSUPPORTED_MEDIA_TYPE","INTERNAL_ERROR","UPSTREAM_INVALID","CONFIGURATION_UNAVAILABLE","OWNER_UNAVAILABLE","UPSTREAM_TIMEOUT"]} | 是 | 固定觀測協定錯誤碼。 固定值：INVALID_REQUEST／INVALID_GRAPH／TOKEN_REQUIRED／FORBIDDEN／NOT_FOUND／METHOD_NOT_ALLOWED／EVENT_CONFLICT／RUN_METADATA_MISMATCH／MISSING_PARENT／RETENTION_EXPIRED／PAYLOAD_TOO_LARGE／UNSUPPORTED_MEDIA_TYPE／INTERNAL_ERROR／UPSTREAM_INVALID／CONFIGURATION_UNAVAILABLE／OWNER_UNAVAILABLE／UPSTREAM_TIMEOUT。 |
| retryable | "boolean"  | 是 | 是否屬服務暫時未就緒或逾時，可由獨立背景sender有界重試。 |

