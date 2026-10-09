# [t483] SRPP 事件證據與決策 API 的佔位實作改為 fail-closed

**對應 Requirements:** Requirement 183（t481／t482 真正實作完成前，兩個 SRPP POST 端點不得把佔位結果存成不可變 FINAL 收據）
**前置任務:** 無（獨立先行；t481、t482 會取代本任務留下的 503 行為）
**Liquibase changeset:** 無

## 背景

main 上已有 `POST /api/public/srpp/event-evidence/capture` 與 `POST /api/public/srpp/daily-decision/evaluate` 的 gateway、BFF、business controller 與 OpenAPI，但 business 端的實作只是佔位：

- `backend/src/main/java/com/steven/assets/service/srpp/SrppCaptureService.java` 的 `captureEvent`、`evaluate` 在 request 通過嚴格欄位驗證後，直接把一列 `status='FINAL'` 寫進 `srpp_event_evidence`／`srpp_decision_run`。事件證據的內容是空的 `officialEvents` 加 `riskAssessment.status="SOURCE_UNAVAILABLE"`；決策的內容是三檔（00865B、00719B、00697B）全部 `BLOCKED`／`CONTEXT_NOT_READY`、`assetSnapshotId="UNAVAILABLE"`。
- 兩張表都有 unique 識別鍵（`srpp_event_evidence_identity_uq`＝`owner_email,trading_date,slot,analysis_profile`；`srpp_decision_run_identity_uq`＝`owner_email,trading_date,slot`），且同識別鍵再呼叫一律 replay 第一次的內容。因此**第一次呼叫就把該 owner／日期／時段永久凍結成這份空結果**，之後即使真正的實作上線也無法重算。
- 該服務沒有呼叫 `SrppPolicyRegistryService`（任何 64 位小寫 hex 都被當成合法規則包），沒有透過 `CurrentUserContext` 解析 owner，`ownerEmail` 還是必填（與 Requirement 181／182 的 optional 相反），也沒有讀任何資產、報價、雷達或爬蟲資料。

正確行為：在 t481、t482 真正實作前，兩個端點通過 request 嚴格驗證後一律 fail closed——回 503 `CONTEXT_NOT_READY`，不讀、不寫任何 repository，也不 replay 既有的佔位列。這是對現況的修正，不推翻其他任何契約。

## 要做什麼

- [x] 483.1 修改 `SrppCaptureService.captureEvent(String raw)` 與 `evaluate(String raw)`：保留現有 `request(...)` 的全部嚴格驗證與其錯誤碼（非 JSON object／未知欄位／欄位集合不符／email 格式／日期不是 Asia/Taipei 今天／slot 不在 `09:05|11:40`／policy 或 swagger 不是 64 位小寫 hex／`analysisProfile` 不是 `TW_DAILY` → 400 `INVALID_REQUEST`；日曆無法確認 → 503 `CALENDAR_UNAVAILABLE`；非交易日 → 409 `NON_TRADING_DAY`），驗證通過後**立即**拋 `new SrppCaptureProblem(HttpStatus.SERVICE_UNAVAILABLE, "CONTEXT_NOT_READY")`。
- [x] 483.2 兩個方法在拋出 503 之前**不得**呼叫 `SrppEventEvidenceRepository`、`SrppDecisionRunRepository` 的任何方法（包含 `find*`、`save*`、`saveAndFlush`），不得產生 UUID、時間戳或 hash 當作結果。`SrppCaptureService` 的建構子仍保留這兩個 repository 與 `MarketDataService` 依賴（只是不呼叫，t481、t482 會各自移除），並**移除這兩個方法上的 `@Transactional`**（fail-closed 路徑不需要也不應開啟資料庫 transaction）。刪除只服務佔位結果的私有方法 `eventBody`、`decisionBody`、`eventReplay`、`decisionReplay`、`requestNode` 與其 `Result` 的 replay 欄位使用處；`Result` record 保留給 t481／t482。已存在於資料庫的佔位列不得被讀出或 replay。
- [x] 483.3 `SrppEventEvidence`、`SrppDecisionRun` entity、兩個 repository 與兩張表（`srpp_event_evidence`、`srpp_decision_run`）**保留不動**，不新增 migration，不刪既有資料列；t481、t482 會自行以新結構取代它們。
- [x] 483.4 `InternalSrppCaptureController` 的 HTTP 契約不變：`consumes=application/json` 宣告維持不動（**非 JSON 時的 415 problem+json 不在本任務範圍**：現況 Content-Type 不符在 BFF 得到 Spring 預設 415、在 business 落入全域 `GlobalExceptionHandler` 回 500，由 t481 一併改為專案自己的 415 problem）、成功路徑的回應處理保留、`SrppCaptureProblem` 的 handler 照舊輸出 `application/problem+json`、`Cache-Control: no-store`、body 為 `{"type":"about:blank","title":<code>,"status":<status>,"code":<code>}`。這個四欄 body 形狀是**暫時的**：t481 會把兩個端點共用的 handler 擴充為 RFC 9457 七欄，所以本任務的測試只斷言狀態碼、`code`、`Content-Type`、`Cache-Control`，不斷言完整欄位集合。BFF 的 `SrppCaptureController` 與 gateway、Tailscale、OpenAPI 路由不改。
- [x] 483.5 在 `backend/src/test/java/com/steven/assets/service/srpp/` 新增 `SrppCaptureService` 的單元測試類（純 JUnit＋Mockito，不啟動 Spring、不連資料庫；實際類名於完成報告回填），涵蓋：(a) 合法 event request 與合法 decision request 各自拋 `SrppCaptureProblem`，狀態 503、code `CONTEXT_NOT_READY`，且兩個 repository mock `verifyNoInteractions`（若使用 Mockito 嚴格模式，(d) 的 stubbing 須用 `lenient()`，否則會丟 `UnnecessaryStubbingException`）；(b) 日期不是今天、slot 錯、hash 非小寫 hex、多餘欄位、缺欄位各回 400 `INVALID_REQUEST`；(c) `MarketDataService.isTradingDayCachedOnly` 回 `Optional.empty()` 時 503 `CALENDAR_UNAVAILABLE`、回 `Optional.of(false)` 時 409 `NON_TRADING_DAY`；(d) 即使 repository mock 已回傳一列 `status="FINAL"` 的佔位資料，結果仍是 503，證明不 replay。
- [x] 483.6 在 `backend/src/test/java/com/steven/assets/controller/` 新增 `InternalSrppCaptureController` 的 MockMvc 測試類（`MockMvcBuilders.standaloneSetup(controller)`，注入 mock `SrppCaptureService`；實際類名於完成報告回填）：service 拋 `SrppCaptureProblem(503,"CONTEXT_NOT_READY")` 時回 503、`Content-Type: application/problem+json`、`Cache-Control: no-store`、body 的 `code` 為 `CONTEXT_NOT_READY`。
- [x] 483.7 約束：不得改動 `docs/openapi/**`、`api-gateway/**`、BFF 與前端（503 已在 OpenAPI 兩個端點的回應表內）；不得新增 endpoint、排程或 Liquibase changeset；不得引入任何 HTTP client、快取寫入或券商呼叫（CLAUDE.md 券商唯讀鐵則）。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest='*SrppCapture*' test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
grep -ran "saveAndFlush\|eventBody\|decisionBody" backend/src/main/java/com/steven/assets/service/srpp/SrppCaptureService.java   # 預期無輸出
```

以 Docker 重建 `business-services` 後，對 loopback 9090 送一個合法的 `event-evidence/capture` 與 `daily-decision/evaluate` request（今天為台股交易日時），預期都回 503 `CONTEXT_NOT_READY`，且資料庫 `srpp_event_evidence`、`srpp_decision_run` 的列數不變。

## 完成報告

實作於 2026-10-09 完成並已合併進 main（merge `88e612c2`）。

- 改動：`backend/.../service/srpp/SrppCaptureService.java`（`captureEvent`／`evaluate` 驗證通過後立即拋 503 `CONTEXT_NOT_READY`，移除 `@Transactional`，刪除佔位結果的私有方法，建構子仍保留三個依賴）。
- 新測試：`SrppCaptureServiceFailClosedTest`（78 案，含 repository 零互動、反射確認無 `@Transactional`、(d) 佔位 FINAL 列存在仍回 503）與 `InternalSrppCaptureControllerTest`（13 案，只斷言狀態碼、`code`、`Content-Type`、`Cache-Control`）。
- 驗證：完整 backend 測試 2709 個通過、0 失敗；本機 JDK 25 需加 `-DextraArgLine=-Dnet.bytebuddy.experimental=true`（或改用 JDK 21）。
- Docker 驗收：`business-services` 重建並 recreate 後 healthy；驗收日（2026-10-09）為國慶補假，兩端點回 `409 NON_TRADING_DAY`（行為正確），因此線上**未**驗到 `503 CONTEXT_NOT_READY` 路徑，該路徑由單元測試涵蓋，下一個交易日可補驗。400 與 405（`Allow: POST`）已驗，兩張佔位表列數驗收前後皆為 0。
- 偏差：無。順帶發現：此次 recreate 時 compose 把 fubon secrets 掛到 worktree 內的空目錄，已在 t484 驗收時改回 main 的正式目錄。
