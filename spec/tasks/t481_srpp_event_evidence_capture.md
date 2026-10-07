# Task 481：SRPP 事件證據擷取 API

**對應 Requirements:** Requirement 181（以已保存 crawler snapshot 產生不可變事件證據）
**前置任務:** Task 480
**Liquibase changeset:** v1.144.0-srpp-event-evidence.sql

## 背景

日報消費者不得各自搜尋、解讀爬蟲文字或將 LLM 摘要轉成交易意見。此任務建立唯一且不可變的事件／風險事實來源；它不抓取、不交易、不寄信。

## 固定契約

Request 必須有 `Content-Type: application/json`，gateway/BFF/business 不得轉換或容忍缺失／其他 media type；非 JSON 是 415 `UNSUPPORTED_MEDIA_TYPE` application-problem，OpenAPI requestBody 只列 application/json。body 是嚴格 JSON object，只可有 optional `ownerEmail`（ACTIVE owner、最多 254）、`tradingDate`（Asia/Taipei 今天且 `tw:true`）、`slot`（`09:05|11:40`）、`analysisProfile`（只可 `TW_DAILY`）、64 位小寫 `policyBundleSha256`、64 位小寫 `swaggerSha256`。所有未知欄與 crawler content/URL/source、analysis universe、LLM prompt/model、event status、score、trade instruction、refresh/force 都是 400 `INVALID_REQUEST`。policy 不存在／未啟用為 409 `POLICY_UNSUPPORTED`，Swagger 不符為 409 `SWAGGER_MISMATCH`，非交易日為 409 `NON_TRADING_DAY`，owner 查詢失敗為 503 `OWNER_UNAVAILABLE`。

新增 immutable records：`srpp_event_evidence_bundle`（UUID id、owner stable id、trading_date、slot、analysis_profile、policy/swagger hashes、status `CAPTURING|FINAL`、crawler snapshot ID/hash/generated_at、window start/end/fallback、LLM receipt hashes、content hash、created/finalized timestamps；unique `(owner_id,trading_date,slot,analysis_profile)`）、`srpp_event_evidence_fact`（bundle id/fact id/source item/citation range/normalized fields/validation/rejection reason）、`srpp_event_evidence_symbol_status`（bundle id/symbol/status/official refs）及 `srpp_risk_assessment_receipt`（bundle id/category/rubric version/score/assessed/fact refs/reason）。另建 immutable `srpp_event_evidence_extraction`：以 `crawler_content_sha256` 唯一，保存 snapshot ID、provider/model/prompt/schema/inference identity、request/response hashes、protected response reference、validator outcome/times；它以完整 snapshot extraction（不含 owner universe）供多 owner bundle 重用，bundle 僅以各自 frozen universe filter/evaluate。這是「同一 frozen snapshot 至多一次 LLM」的 DB constraint，跨 owner／並發 loser 必重讀相同 receipt，絕不第二次呼叫。snapshot port 只選 capture 開始前同 profile 最新、已完成、content hash 可驗證、`generatedAt` 含 Asia/Taipei offset 的 persisted snapshot；最小 source fields 是 stable ID、generatedAt、tradingDayCutoff、contentSha256、itemCount、items。沒有合格 snapshot 為 503 `CONTEXT_NOT_READY`，絕不 rescan。

LLM port 對 frozen snapshot 至多呼叫一次，保存 provider/model/revision/template hash/schema/inference settings/request-response hashes/times/validator/audit reference；只能回 JSON facts。citation 必在保存原文範圍，symbol 必屬 universe 或市場層事件，time/enums/rubric 都合法，statement 不得超出 citation。`OFFICIAL_PRIMARY` 才可支撐 D-092 EVENT_FOUND；`PERMITTED_RISK_SOURCE` 可作 rubric candidate；CONTEXTUAL_ONLY 不得計分；缺 URL/time/content、unknown source 或 injection 為 REJECTED。LLM／schema／citation failure 回滾為 503 `CONTEXT_NOT_READY`。

FINAL response 固定 `schemaVersion,eventBundleId,status,created,identity,inputSnapshot,llmReceipt,officialEvents,riskAssessment,reportEvidence,tradeAuthorization:false,placesOrders:false,eventBundleContentSha256`；`created` 不進 JCS content hash，所有 score 使用 decimal string。officialEvents 逐 universe symbol 升冪，D-092 只可 EVENT_FOUND／VERIFIED_NO_EVENT／SOURCE_UNAVAILABLE；後者永遠中性。risk categories 固定依 `FED,GEOPOLITICS,OIL,TAIWAN_POLITICS,US_TW_INFLATION,SEMICONDUCTOR` 順序；無允許 evidence 是 score `"0"`、`assessed:false`、`EVIDENCE_INSUFFICIENT`。首次 FINAL 201、同 metadata FINAL 200、CAPTURING 202（`EVENT_CAPTURE_IN_PROGRESS`＋Retry-After 2）、同 owner/date/slot/profile metadata 不同 409 `BUNDLE_METADATA_MISMATCH`、持久化 source 結構/時間/hash 不合法 502 `UPSTREAM_INVALID`。

`inputSnapshot` 必含 crawlerSnapshotId、crawlerGeneratedAt、inputSnapshotSha256 及 start/end/fallbackUsed window；`llmReceipt` 必含 model、promptTemplateSha256、schemaVersion、responseSha256、validated；每個 official event 是 symbol/status/events/sourceReceiptIds。每個 category 是 code/decimal-string score/assessed/evidenceStatus/factIds/固定繁中 summary；risk assessment 另有 decimal-string totalScore、assessedCount、riskMode。report evidence 只有 eventSummaryZhTw/riskSummaryZhTw；完整 crawler body/provider response 只放 protected retention storage，log 僅可 bundle ID/hash/status/duration/stable code。crawler item 必保存 itemId/title/source/url/category/region/publishedAt/content hash/snapshot position；analysisUniverse 是本輪 canonical assets/radar 的 allowed Taiwan symbols。

source classifier 是固定 allowlist：`OFFICIAL_PRIMARY`（MOPS/TWSE/TPEx/issuer 可驗證公告，唯一可使 D-092 EVENT_FOUND）、`PERMITTED_RISK_SOURCE`（policy public_info/official primary/approved market or commodity source，可作風險 candidate）、`CONTEXTUAL_ONLY`（一般新聞、媒體、search summary、unverified reprint，只作背景不得 EVENT_FOUND/score）與 `REJECTED`（缺 URL/time/body、unidentified、prompt injection、format violation，完全排除且留 audit reason）。bundle retention 至少涵蓋同日報草稿和 MIME audit 期，且其 immutable records 不能 update/delete 當作重跑。

CAPTURING 的 202 body 必含同一 `eventBundleId`，header 必有 `Retry-After: 2`；FINAL replay 逐字保留 eventBundleId、inputSnapshotSha256、eventBundleContentSha256。併發 20 個相同 request 只能一列 bundle、所有 FINAL content hash 相同；跨 owner 對同 crawler content hash 的併發仍只能一筆 extraction/一次 LLM；loopback/Tailscale 必回相同 bundle ID，不因入口重跑 LLM；FINAL 後 crawler 更新不影響 replay。測試必證 JSON media type 415、無 snapshot 503 且無 rescan/network、injection 被 REJECTED、非官方不可 EVENT_FOUND、citation/rubric 任一漏失不計分、六 risk golden fixtures 與 policy 完全相符，並證 daily-decision 消費 FINAL 時綁同 bundle/input/content hash、不得再讀 crawler/LLM。

## 要做什麼

- [ ] 481.1 建立 exact `POST /api/public/srpp/event-evidence/capture` 的 gateway、BFF、business controller、frontend deny、Tailscale Serve、OpenAPI 與 generated Markdown 同步；非 POST 同路徑為 405 `Allow: POST`，尾斜線、child、matrix 皆為 404。
- [ ] 481.2 request 嚴格只允許 ownerEmail、tradingDate、slot、analysisProfile、policyBundleSha256、swaggerSha256；拒絕未知欄位、錯誤 hash／slot／profile／日期。交易日期只接受 Asia/Taipei 當日且 canonical calendar `tw:true`。
- [ ] 481.3 以 owner/date/slot/profile 的唯一鍵保存 CAPTURING／FINAL bundle。並行回 202 + `Retry-After: 2`；FINAL replay 保持 ID、input hash、content hash 完全不變；metadata 不同回 409；失敗 rollback，絕不留下可讀 partial。
- [ ] 481.4 input 只能由已持久化 crawler snapshot、canonical analysis universe、frozen policy/SWagger registry 取得；禁止 request-time rescan、外部 HTTP、tool call、cache/market/asset write。
- [ ] 481.5 以 ports 隔離 LLM、source validator 與 evaluator。LLM 僅輸出嚴格 JSON candidate facts；citation、source class、universe、time、schema、rubric 都必須由 server 驗證。未驗證 fact 不得計分；prompt injection／不完整 item 記為 REJECTED。
- [ ] 481.6 deterministic evaluator 逐一輸出 D-092 status，六種固定 risk category。SOURCE_UNAVAILABLE 必為中性，不得改變張數、限價、可交易性或任何 trade gate；回應永遠 `tradeAuthorization:false`、`placesOrders:false`。
- [ ] 481.7 建立單元／資料庫／HTTP／併發／route deny 契約測試與 golden evaluator fixtures；schema 變更後重產 `db/schema.sql`。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test
ruby scripts/tests/docker-external-api-openapi-test.rb
bash scripts/tests/configure-tailscale-api-gateway-test.sh
bash scripts/tests/schema-sql-drift-test.sh
```

並以 Docker 重新建立 `business-services`、`bff`、`api-gateway`，確認 9090 對無合格 snapshot 安全回 503，且 POST 以外方法被拒絕、未觸發外部 rescan。

## 完成報告

（實作者回填。）
