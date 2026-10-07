# Task 482：SRPP 不可變決策引擎 API

**對應 Requirements:** Requirement 182（以 canonical snapshot 產生一致的 D-130／D-195 報告建議）
**前置任務:** Task 481
**Liquibase changeset:** v1.145.0-srpp-daily-decision.sql

## 背景

日報流程不能分別從不同入口重算 D-130／D-195。此 API 只產生不可變、可重播的報告建議收據；它不授權或執行交易、寄信、資金移動或資料刷新。

## 固定契約

Request 必須有 `Content-Type: application/json`；gateway/BFF/business 不得容忍缺失／其他 media type，非 JSON 是 415 `UNSUPPORTED_MEDIA_TYPE` application-problem，OpenAPI requestBody 只列 application/json。body 是嚴格 JSON object，只可有 optional ACTIVE `ownerEmail`、`tradingDate`（Asia/Taipei 今天且 `tw:true`）、`slot`（`09:05|11:40`）、64 位小寫 policy/SWagger hashes；unknown fields、candidate/rule/lots/limitPrice/snapshot/source URL/refresh/force/trade instruction 皆為 400 `INVALID_REQUEST`。identity 是 owner stable id/date/slot/policy hash/Swagger hash；`srpp_decision_run` 保存 UUID、identity、`CAPTURING|FINAL`、input hash/content hash、created/finalized timestamps，unique `(owner_id,trading_date,slot)`；另以 immutable `DecisionInputBundle`、source receipts、candidate receipts 保存 source ID/revision/capturedAt/JCS hash/coverage/validation。首次 FINAL 201、same identity FINAL 200、CAPTURING 202 `DECISION_CAPTURE_IN_PROGRESS`＋Retry-After 2、metadata mismatch 409 `RUN_METADATA_MISMATCH`、unknown policy 409 `POLICY_UNSUPPORTED`、Swagger mismatch 409 `SWAGGER_MISMATCH`、non-trading day 409 `NON_TRADING_DAY`、bad canonical source 502 `UPSTREAM_INVALID`、L0 unavailable 503 `CONTEXT_NOT_READY`、owner unavailable 503 `OWNER_UNAVAILABLE`。

capture 一次只讀 persisted calendar、policy/SWagger registries、assets identity、same-day quotes/books/premium, exact radar details、ledger/pending-order de-dup、tradability and capacity evidence；禁止 self HTTP、vendor I/O、cache refresh/write。event lookup 精確為 `(owner stable id,tradingDate,slot,analysisProfile=TW_DAILY,policyBundleSha256,swaggerSha256,status=FINAL)`；只讀該 bundle 已驗證 `officialEvents`、`riskAssessment` 及 bundle/input/content hashes，不得讀 protected crawler／provider 原文。lookup 唯一 FINAL 才綁定其 ID/content/input hashes；profile/metadata 不符、缺失、CAPTURING 或 `CONTEXT_NOT_READY` 時仍 FINAL：每個 symbol D-092=`SOURCE_UNAVAILABLE`、event factor 中性、`eventEvidenceIntegrationStatus=UNAVAILABLE` 與原因，絕不等待、取舊 bundle、再讀 crawler/LLM、降低 lots／limit 或作 L0/L1/L2 gate。

calculator 是純函式，只評估 00865B／00719B／00697B D-130／D-195。每個 receipt 必有 rule ID/calculator version/input references/status/reason；無 receipt 即 BLOCK。00865B 需要 `STRATEGIC_FUBON`、`EMERGENCY_CATHAY` 各自 broker/account/position/pending-orders verified 與 explicit verifiedZero or holding receipt；任一缺失使兩 leg BLOCKED、`SUBACCOUNT_UNVERIFIED`、0 lots/null price/null amount。每日 strategy 合計最多 2 lots、每週最多 6，lots 取 policy/capacity/funds/book-depth/de-dup limits 最小值；limit 僅取同 valid quote opposite five-level cumulative depth 並保守 tick round。其他 symbols/markets/short/sell 輸出 NOT_EVALUATED、0/null。

FINAL response 固定 `schemaVersion,decisionRunId,status,created,authorityRevision,identity,inputSnapshot,decision,decisionContentSha256`；created 不進 RFC8785 JCS content hash。`decision` 固定 `executionScope:"REPORT_RECOMMENDATION_ONLY",tradeAuthorization:false,placesOrders:false,candidates`。candidate 是 symbol/strategy/status (`ACTIONABLE_FOR_REPORT|BLOCKED|NOT_EVALUATED`)/action (`BUY|NONE`)/non-negative integer lots/nullable decimal-string limitPrice and amountTwd/sorted blockingReasons/receipts；0 lot 一律 null numeric fields。所有 decimal numbers 僅 JSON string。

每個 source vector member 都有 sourceId、revision/snapshot identity、capturedAt、JCS SHA-256、coverage、validation。capture 必驗證：唯一且可解析的 Taiwan calendar `tw:true`；policy bytes hash、decision/policy/model rules versions；published Swagger identity；latest assets 的 snapshot ID=live assets snapshot ID、owner、完整 deposits/holdings/generatedAt/hash/reconciliation；三個 candidate 的 same-day quote/updatedAt/premiumDiscount/opposite five-level cumulative depth；每個可能建議 symbol 恰一筆 exact radar detail；same day/week fills/pending/de-dup scope；官方 tradability list/match。`authorityRevision` 必有 serviceBuild、databaseSchema、calculatorVersion；inputSnapshot 必有 inputSnapshotSha256/capturedAt/assetSnapshotId/assetGeneratedAt/sourceVector。

algorithm order 固定是：(1) policy/Swagger/calendar/assets identity L0；(2) per-symbol quote freshness/premium/book/radar/tradability/funds/de-dup/capacity；(3) policy-defined restored completed-close sequence 判 D-195，禁止 intraday 回推 20-day high；(4) 仍合格且低於 targets/caps 的 strategy candidates 取 deepest pullback，tie 必用 policy fixed tie-breaker；(5) lots 取 D-130 daily/weekly/individual capacity/funds/depth/de-dup/policy cap minimum；(6) 兩子帳 verified 後才獨立算 emergency 00865B；(7) opposite-book cumulative price conservative tick rounding。每個 PASS/BLOCK/NOT_APPLICABLE/UNAVAILABLE 都有 rule ID/calculator version/input refs/named reason；`ACTIONABLE_FOR_REPORT` 只表示可寫進報告，絕不是下單許可。

CAPTURING 的 202 body 必含同一 `decisionRunId`，header 必有 `Retry-After: 2`；FINAL replay 必維持 decisionRunId、inputSnapshotSha256、decisionContentSha256。驗收必證 JSON media type 415、event profile/metadata mismatch/CAPTURING/absent FINAL 四情境都走中性 fallback；並含 20 個台股交易日（09:05、11:40、no-action、missing data、00865B missing subaccount、D-130 de-dup/capacity）的 old authoritative diagnostic vs API field-by-field golden diff，任一差異要有接受的 rule change；loopback/Tailscale、20-way concurrency、restart 後 immutable replay 均不變；gateway/OpenAPI/BFF/business/migration/Tailscale/timeouts/deny matrix 必通過。一次端到端 audit 必為 API result→report facts→HTML/plain validator→Gmail DRAFT MIME readback→final policy recheck。這些證據完成前 endpoint 永遠 report-only，既有 `scripts/daily_decision_engine.py` 必維持 `DIAGNOSTIC_ONLY`，不得被排程當成交易授權。

## 要做什麼

- [ ] 482.1 建立 exact `POST /api/public/srpp/daily-decision/evaluate` 的 gateway、BFF、business controller、frontend deny、Tailscale Serve、OpenAPI 及 generated Markdown 同步；非 POST 同路徑為 405 `Allow: POST`，尾斜線、child、matrix 變體為 404。
- [ ] 482.2 request 僅允許 ownerEmail、tradingDate、slot、policyBundleSha256、swaggerSha256；未知 facts、candidates、lots、price、snapshot、refresh、force 或交易指令一律 400。交易日期必須是服務端 Asia/Taipei 當日且 `tw:true`。
- [ ] 482.3 用 owner/date/slot unique identity 保存 CAPTURING／FINAL run 和 immutable input bundle。並行回 202 + `Retry-After: 2`；FINAL replay 固定 ID/input/content hashes；metadata mismatch 回 409；capture 失敗 rollback。
- [ ] 482.4 server capture 一次讀 canonical calendar、policy/SWagger registries、assets、quotes/books、radar、ledger、tradability 與同 identity FINAL event evidence；不得 9090 self-call、外部 I/O、refresh 或 write。
- [ ] 482.5 calculator 僅對 00865B、00719B、00697B 的 D-130／D-195 產出 deterministic `REPORT_RECOMMENDATION_ONLY`。所有 gate 有 named receipt；無收據為 BLOCK；任何 0 lot 的 limitPrice／amountTwd 必為 null，所有數字為 decimal string。
- [ ] 482.6 00865B 策略富邦與國泰緊急子帳分開 capture；任一 account／position／pending order 未驗證或沒有明確 verified-zero 時，兩個 00865B leg 都 BLOCKED、0 lot、`SUBACCOUNT_UNVERIFIED`，不得由 aggregate holding 補足。
- [ ] 482.7 response 永遠 `tradeAuthorization:false`、`placesOrders:false`；其他股票、美股、賣出、短線範圍皆是 NOT_EVALUATED 且不得含交易數字。
- [ ] 482.8 建立 calculator golden fixtures、併發／重播／metadata mismatch／subaccount／容量／gateway deny 契約測試；schema 變更後重產 `db/schema.sql`。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test
ruby scripts/tests/docker-external-api-openapi-test.rb
bash scripts/tests/configure-tailscale-api-gateway-test.sh
bash scripts/tests/schema-sql-drift-test.sh
```

以 Docker 實際重建受影響服務，確認缺完整 global context 安全回 503，所有 9090 結果仍為 report-only／禁止交易。

## 完成報告

（實作者回填。）
