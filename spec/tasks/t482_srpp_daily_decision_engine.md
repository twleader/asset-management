# Task 482：SRPP 不可變決策引擎 API

**對應 Requirements:** Requirement 182（以 canonical snapshot 產生一致的 D-130／D-195 報告建議）
**前置任務:** t483（佔位實作先改為 fail-closed）、t484（沿用它的七欄 problem 載體、BFF `SrppOwnerResolver`、共用 bean `PublishedSwaggerIdentity`、415／400／500 handler）。本任務**不依賴** t481：t481 的事件證據 bundle 為 consumer 專屬〔變體 A〕，本任務不綁定它
**Liquibase changeset:** v1.145.0-srpp-daily-decision.sql 已被佔位表使用且已合併；本任務實作時另建新版號 changeset（取當時 `db.changelog-master.yaml` 與各分支皆未使用的下一個版號），不得改動既有 changeset 檔

## 背景

日報流程不能分別從不同入口重算 D-130／D-195。此 API 只產生不可變、可重播的報告建議收據；它不授權或執行交易、寄信、資金移動或資料刷新。

## 固定契約

Request 必須有 `Content-Type: application/json`；gateway/BFF/business 不得容忍缺失／其他 media type，非 JSON 是 415 `UNSUPPORTED_MEDIA_TYPE` application-problem，OpenAPI requestBody 只列 application/json。body 是嚴格 JSON object，只可有 optional ACTIVE `ownerEmail`、`tradingDate`（Asia/Taipei 今天且 `tw:true`）、`slot`（`09:05|11:40`）、64 位小寫 policy/SWagger hashes；unknown fields、candidate/rule/lots/limitPrice/snapshot/source URL/refresh/force/trade instruction 皆為 400 `INVALID_REQUEST`。identity 是 owner stable id/date/slot（unique 只含這三者）；policy hash 與 Swagger hash 是 metadata，同識別但不同值回 409 `RUN_METADATA_MISMATCH`；`srpp_decision_run` 保存 UUID、identity、`CAPTURING|FINAL`、input hash/content hash、created/finalized timestamps，unique `(owner_id,trading_date,slot)`；另以 immutable `DecisionInputBundle`、source receipts、candidate receipts 保存 source ID/revision/capturedAt/JCS hash/coverage/validation。首次 FINAL 201、same identity FINAL 200、CAPTURING 202 `DECISION_CAPTURE_IN_PROGRESS`＋Retry-After 2、metadata mismatch 409 `RUN_METADATA_MISMATCH`、unknown policy 409 `POLICY_UNSUPPORTED`、Swagger mismatch 409 `SWAGGER_MISMATCH`、non-trading day 409 `NON_TRADING_DAY`、bad canonical source 502 `UPSTREAM_INVALID`、L0 unavailable 503 `CONTEXT_NOT_READY`、owner unavailable 503 `OWNER_UNAVAILABLE`。

capture 一次只讀 persisted calendar、policy/SWagger registries、assets identity、same-day quotes/books/premium, exact radar details、ledger/pending-order de-dup、tradability and capacity evidence；禁止 self HTTP、vendor I/O、cache refresh/write。**不綁定事件證據（t481 採變體 A 的結論）。** 事件證據 bundle 以 `(owner, tradingDate, slot, analysisProfile, consumer, decisionId)` 識別，Claude 與 Codex 各自一筆；決策 run 則是每個 owner／日期／時段共用一筆。為避免一個共用 run 綁定到特定 consumer 的 bundle，本 API **不讀取、不綁定、不依賴**任何 `srpp_event_evidence_bundle` 資料列，也不查詢 crawler 或 LLM。D-092 事件因子本來就不阻擋交易、D-167／D-168 風險模式也不得單獨把 D-130 債券 ETF 降為 0 張，所以不綁定不改變任何張數、限價或 gate。回應的 `decision` 固定帶 `eventEvidenceIntegrationStatus:"NOT_BOUND"` 與 `eventEvidenceReason:"PER_CONSUMER_EVIDENCE"`，讓報告如實揭露事件證據由各 consumer 自己的 bundle 提供；run 內不含任何 D-092 逐標的狀態。

**現況限制（實作前先用 `grep -ran` 重新驗證，再開工）。** 目前 backend 與 external-materials-service 沒有任何券商掛單（pending／open order）資料來源，也沒有證交所／櫃買「處置、注意、暫停交易」可交易性清單的資料來源（本檔建立時的搜尋只命中 HTTP `ContentDisposition`）。因此在另立任務提供「唯讀券商掛單」來源之前，`pendingOrdersVerified` 永遠無法為 true，00865B 的 `STRATEGIC_FUBON` 與 `EMERGENCY_CATHAY` 兩個 leg 依本檔規則一律 `BLOCKED`、`SUBACCOUNT_UNVERIFIED`；可交易性來源不存在時，各標的的 tradability receipt 為 `UNAVAILABLE`、`TRADABILITY_SOURCE_UNAVAILABLE`，依已接受的 SRPP D-096／D-144 僅收緊限價：採合格方向相反側最佳五檔第一檔價並保守 tick 取整，不得因此阻擋或縮減張數；既有方向相反側五檔累計深度不足既定張數時依深度 L1 阻擋。清單命中仍是 L1。掛單缺失不得推定為零。**00719B、00697B 缺掛單來源時**：run 仍為 FINAL（不是 L0 的 503），逐檔 receipt 記 `PENDING_ORDERS_UNAVAILABLE`；預設 fail closed——該檔 `BLOCKED`、0 lot、`PENDING_ORDERS_UNAVAILABLE`。這會使三檔在掛單來源出現前都不產生買進建議，**本任務的最終規格就是這個預設 fail closed**，不得自行放寬；是否改為「只揭露、不阻擋」（SRPP 規則 D-130 的 L1 清單只列成交去重、未列掛單）由 Steven 另行決定、另立任務。已確認成交的來源是 business 層帳本（`AssetTransactionRepository`／`AssetTransactionService`），可用於同日／同週成交去重；BFF 的 `PublicTransactionHistoryService` 是 9090 的 BFF 投影，business 層不得呼叫它。

calculator 是純函式，只評估 00865B／00719B／00697B D-130／D-195。每個 receipt 必有 rule ID/calculator version/input references/status/reason；無 receipt 即 BLOCK。00865B 需要 `STRATEGIC_FUBON`、`EMERGENCY_CATHAY` 各自 broker/account/position/pending-orders verified 與 explicit verifiedZero or holding receipt；任一缺失使兩 leg BLOCKED、`SUBACCOUNT_UNVERIFIED`、0 lots/null price/null amount。每日 strategy 合計最多 2 lots、每週最多 6，lots 取 policy/capacity/funds/book-depth/de-dup limits 最小值；limit 僅取同 valid quote opposite five-level cumulative depth 並保守 tick round。其他 symbols/markets/short/sell 輸出 NOT_EVALUATED、0/null。

FINAL response 固定 `schemaVersion,decisionRunId,status,created,authorityRevision,identity,inputSnapshot,decision,decisionContentSha256`；created 不進 RFC8785 JCS content hash。`decision` 固定 `executionScope:"REPORT_RECOMMENDATION_ONLY",tradeAuthorization:false,placesOrders:false,eventEvidenceIntegrationStatus:"NOT_BOUND",eventEvidenceReason:"PER_CONSUMER_EVIDENCE",candidates`。candidate 是 symbol/strategy/status (`ACTIONABLE_FOR_REPORT|BLOCKED|NOT_EVALUATED`)/action (`BUY|NONE`)/non-negative integer lots/nullable decimal-string limitPrice and amountTwd/sorted blockingReasons/receipts；0 lot 一律 null numeric fields。所有 decimal numbers 僅 JSON string。

每個 source vector member 都有 sourceId、revision/snapshot identity、capturedAt、JCS SHA-256、coverage、validation。capture 必驗證：唯一且可解析的 Taiwan calendar `tw:true`；policy bytes hash、decision/policy/model rules versions；published Swagger identity；latest assets 的 snapshot ID=live assets snapshot ID、owner、完整 deposits/holdings/generatedAt/hash/reconciliation；三個 candidate 的 same-day quote/updatedAt/premiumDiscount/opposite five-level cumulative depth；每個可能建議 symbol 恰一筆 exact radar detail；same day/week 已確認成交的 de-dup scope（讀 business 層帳本）；券商掛單與官方 tradability list/match 僅在有資料來源時驗證，無來源時依下方「現況限制」fail closed 或揭露，不得略過不記。`authorityRevision` 必有 serviceBuild、databaseSchema、calculatorVersion；inputSnapshot 必有 inputSnapshotSha256/capturedAt/assetSnapshotId/assetGeneratedAt/sourceVector。

algorithm order 固定是：(1) policy/Swagger/calendar/assets identity L0；(2) per-symbol quote freshness/premium/book/radar/tradability/funds/de-dup/capacity；(3) policy-defined restored completed-close sequence 判 D-195，禁止 intraday 回推 20-day high；(4) 仍合格且低於 targets/caps 的 strategy candidates 取 deepest pullback，tie 必用 policy fixed tie-breaker；(5) lots 取 D-130 daily/weekly/individual capacity/funds/depth/de-dup/policy cap minimum；(6) 兩子帳 verified 後才獨立算 emergency 00865B；(7) opposite-book cumulative price conservative tick rounding。每個 PASS/BLOCK/NOT_APPLICABLE/UNAVAILABLE 都有 rule ID/calculator version/input refs/named reason；`ACTIONABLE_FOR_REPORT` 只表示可寫進報告，絕不是下單許可。

CAPTURING 的 202 body 必含同一 `decisionRunId`，header 必有 `Retry-After: 2`；FINAL replay 必維持 decisionRunId、inputSnapshotSha256、decisionContentSha256。驗收必證 JSON media type 415、回應固定 `eventEvidenceIntegrationStatus:"NOT_BOUND"` 且即使資料庫存在同日 FINAL 事件證據 bundle，lots／limit／receipts 與 inputSnapshotSha256 都不受影響；並含 20 個台股交易日（09:05、11:40、no-action、missing data、00865B missing subaccount、D-130 de-dup/capacity）的 old authoritative diagnostic vs API field-by-field golden diff，任一差異要有接受的 rule change；loopback/Tailscale、20-way concurrency、restart 後 immutable replay 均不變；gateway/OpenAPI/BFF/business/migration/Tailscale/timeouts/deny matrix 必通過。一次端到端 audit 必為 API result→report facts→HTML/plain validator→Gmail DRAFT MIME readback→final policy recheck。這些證據完成前 endpoint 永遠 report-only，既有 `scripts/daily_decision_engine.py` 必維持 `DIAGNOSTIC_ONLY`，不得被排程當成交易授權。

## 要做什麼

- [ ] 482.0 **沿用已接受政策**：缺掛單來源時，00865B 兩個 leg 一律 `SUBACCOUNT_UNVERIFIED`；00719B、00697B 一律 `BLOCKED`、0 lot、`PENDING_ORDERS_UNAVAILABLE`。可交易性清單缺來源只產生 L2 `TRADABILITY_SOURCE_UNAVAILABLE`：保持既定張數，限價採方向相反側最佳五檔第一檔價並保守 tick 取整；既有方向相反側五檔累計深度不足既定張數仍依 L1 阻擋。不得將 L2 視為 L0／L1 或推定無掛單。掛單規則若要放寬仍須 Steven 另行決定。
- [ ] 482.0a **取代佔位表**：`srpp_decision_run` 已由 `v1.145.0` 以 `owner_email` 與 unique `(owner_email,trading_date,slot)` 建立（內容只有 t483 之前的佔位結果，沒有使用端依賴）。新 changeset 必須 `DROP TABLE IF EXISTS srpp_decision_run` 後以 `owner_user_id bigint`（外鍵 `app_user(id)`）與外鍵 `srpp_policy_registry(policy_bundle_sha256)` 的新結構重建，unique 為 `(owner_user_id,trading_date,slot)`，加 `BEFORE UPDATE` trigger（既有 `reject_srpp_immutable_update()`）；同步刪除 `SrppDecisionRun` entity 與 `SrppDecisionRunRepository`。不得依 `CREATE TABLE IF NOT EXISTS` 對舊表靜默 no-op。
- [ ] 482.1 建立 exact `POST /api/public/srpp/daily-decision/evaluate` 的 gateway、BFF、business controller、frontend deny、Tailscale Serve、OpenAPI 及 generated Markdown 同步；非 POST 同路徑為 405 `Allow: POST`，尾斜線、child、matrix 變體為 404。
- [ ] 482.1a `docs/openapi/docker-external-api.yaml`（`info.version` 再升一個 minor；evaluate 端點的狀態集合加入 `200`、`201`、`202`；`scripts/tests/docker-external-api-openapi-test.rb` 釘死的版本與該端點的 `MANIFEST` 狀態集合同步更新，流程見 t484 的 484.9）中 `evaluateSrppDailyDecision` 的描述原寫「事件證據缺失或不可用是中性 `SOURCE_UNAVAILABLE` fallback」，改為「不綁定事件證據，回應固定 `eventEvidenceIntegrationStatus:\"NOT_BOUND\"`」，並同步 response schema；problem 形狀沿用 t481 擴充的七欄。
- [ ] 482.1b BFF `SrppCaptureController.decision()`（t481 只改 `event()`，`decision()` 目前仍只轉送 bytes、不帶 `X-User-*`，business 端 `CurrentUserContext.hasUser()` 會是 false）改為與 t481 的 481.1 相同的五步流程：讀 body → `SrppDailyContextResponseValidator.parseStrict` 解析並檢查 `ownerEmail` 格式（400，零 outbound）→ t484 的 `SrppOwnerResolver` 解析 owner（503 `OWNER_UNAVAILABLE`）→ 帶 `X-User-*` 並清除 caller 身分呼叫 business（逾時 10 秒）→ 驗證 business 回應。**回應驗證專屬於 decision**：2xx 只接受 200、201、202；200／201 本體必須是 JSON object 且 `status="FINAL"`、`decision.tradeAuthorization=false`、`decision.placesOrders=false`；202（CAPTURING）本體必須含 `decisionRunId` 並帶 `Retry-After` header，BFF 原樣透傳；非 2xx 必須是 `application/problem+json` 且 `code` 屬 t484 登錄的 code 加上本任務新增的 code；其餘 → 502 `UPSTREAM_INVALID`。本任務在 problem 目錄新增 `RUN_METADATA_MISMATCH` 409（retryable false）。
- [ ] 482.2 request 僅允許 ownerEmail、tradingDate、slot、policyBundleSha256、swaggerSha256；未知 facts、candidates、lots、price、snapshot、refresh、force 或交易指令一律 400。交易日期必須是服務端 Asia/Taipei 當日且 `tw:true`。
- [ ] 482.3 用 owner/date/slot unique identity 保存 CAPTURING／FINAL run 和 immutable input bundle。並行回 202 + `Retry-After: 2`；FINAL replay 固定 ID/input/content hashes；metadata mismatch 回 409；capture 失敗 rollback。
- [ ] 482.4 server capture 一次讀 canonical calendar、policy/SWagger registries、assets、quotes/books、radar、ledger 與（若有來源）tradability；**不讀事件證據 bundle**；不得 9090 self-call、外部 I/O、refresh 或 write。
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
