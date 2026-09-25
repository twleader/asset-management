# [t453] SRPP 共用計算結果：business 唯讀內部讀取端點與 freshness

**對應 Requirements:** Requirement 163（9090 `GET /api/public/srpp/daily-context` 的 business 端純唯讀讀取：最新／固定摘要、單一來源重播、freshness 與 RFC 9457 錯誤碼）
**前置任務:** t452（資料表、`SrppPolicyRegistryService`、`SrppSnapshotRevision`、`SrppJcs`、owner-explicit 資產讀取）
**Liquibase changeset:** 無

## 背景

t452 已由背景 producer 把不可變 package（`srpp_context_package.context_jcs`＋`srpp_context_evidence.body`）寫進 PostgreSQL。本任務新增 container-only 的 business 讀取端點，讓 BFF（t454）以顯式 owner header 讀取。**GET 絕不計算、不補抓、不寫入**：只讀 PostgreSQL 的 package／registry／owner 最新快照，日曆只用 cached-only。registry 上線時為空，因此實際環境中本端點對任何 hash 都回 409 `POLICY_UNSUPPORTED`，這是使用者選擇的 fail-closed 行為。

既有事實：
- BFF→business 以 `X-User-Id`／`X-User-Role`／`X-User-Status` 傳 owner，由 `CurrentUserFilter` 填入 request-scoped `CurrentUserContext`；無 header 時 `TenantFilterAspect` 以 ownerId −1 fail-closed。
- 內部端點慣例參考 `InternalPublicTransactionHistoryController`（`/internal/public-transaction-history/current`）；`AdminGateInterceptor` 的掛載範圍（`WebConfig`）不涵蓋 `/internal/public-srpp/**`。
- `MarketDataService.isTwTradingDayCachedOnly(LocalDate)`：無 I/O，週末 `Optional.of(false)`，cache 缺或過期（10 分鐘）回 empty。t452 producer 每 5 分鐘以 `warmTwHolidaysIfExpiringWithin(6 分鐘)` 預先續期此 cache（全週），因此正常運作下 cached-only 不會出現空窗；producer 停擺時 cached-only 回 empty 屬預期的 fail-closed。

## 要做什麼

- [ ] 453.1 **Controller `InternalSrppDailyContextController`。** `@GetMapping("/internal/public-srpp/daily-context")`，參數 `tradingDate`、`slot`、`policyBundleSha256`、`view`（預設 summary）、`packageId`、`sourceId`（皆字串、`required=false`，由 service 驗證）；只委派 `SrppDailyContextReadService`，並把其領域結果轉成 `ResponseEntity<String>`（HTTP status 與 problem body 由 controller 經 `SrppProblemCatalog` 對照產生，屬 HTTP 轉換、不算業務判斷），header `Content-Type`（成功 `application/json`、錯誤 `application/problem+json`）與 `Cache-Control: private, no-store`。不得注入 repository、不得有業務判斷。不接受 `email`、`ownerId` 或任何 owner 參數；owner 只來自 `CurrentUserContext`。

- [ ] 453.2 **`SrppDailyContextReadService`（`@Transactional(readOnly = true)`；`Clock` 比照 `FubonTradeSyncScheduler` 雙建構子慣例注入，Spring 用 `Clock.system(ZoneId.of("Asia/Taipei"))`，不新增全域 Clock bean）。** 判斷順序固定，第一個命中的錯誤即回（下列「→ 4xx／5xx `CODE`」只表示該 code 最終對應的 HTTP status，service 本身只回傳 code，不回 status）：
  1. 參數防禦性驗證（同 BFF：日期嚴格 ISO、slot 僅 `09:05`／`11:40`、hash `^[0-9a-f]{64}$`、view `summary|evidence`、packageId 小寫 canonical UUID、sourceId `^[a-z][a-z0-9_-]{0,63}$`、evidence 必須有 packageId 與 sourceId、summary 不得有 sourceId）→ 400 `INVALID_REQUEST`。
  2. `CurrentUserContext.hasUser()` 為 false → 503 `OWNER_UNAVAILABLE`。
  3. `SrppPolicyRegistryService.find(hash)` 空 → 409 `POLICY_UNSUPPORTED`。
  4. **最新摘要**（summary 且無 packageId）：
     - `tradingDate` ≠ 台北今天 → 400 `INVALID_REQUEST`。
     - 台北現在時刻早於 slot 時間 → 503 `CONTEXT_NOT_READY`（不得回較早時段）。
     - `isTwTradingDayCachedOnly(tradingDate)`：`false` → 409 `NON_TRADING_DAY`；empty → 503 `CALENDAR_UNAVAILABLE`。
     - 查 `(owner, date, slot, hash)` 依 `generated_at DESC, package_id DESC` 第一筆；無 → 503 `CONTEXT_NOT_READY`。
     - freshness（見 453.3）：STALE → 409 `CONTEXT_STALE`；UNKNOWN → 503 `CONTEXT_NOT_READY`；CURRENT → 200。
  5. **固定摘要**（summary 帶 packageId）：以 `(package_id, owner_user_id)` 查；無 → 404 `CONTEXT_NOT_FOUND`（他人 package 同樣 404，不得另外查存在性）；date、slot、hash 任一與 package 不同 → 409 `CONTEXT_IDENTITY_MISMATCH`；否則 200 附 freshness（CURRENT／STALE／UNKNOWN 皆可）。
  6. **evidence**：package 查詢與身分同 5；再以 `(package_id, source_id)` 查 evidence，查無或該 source 在 context 內非 AVAILABLE → 404 `SOURCE_EVIDENCE_NOT_FOUND`；否則 200。
  - repository 查詢一律帶明確 ownerId（`CurrentUserContext.getEffectiveUserId()`），不依賴 `ownerFilter`；evidence body 查詢也須 join package 比對 owner（`package_id`＋`source_id`＋`owner_user_id`），不能只靠前一步驗證。

- [ ] 453.3 **Freshness（讀取當下查核，不進 context hash）。** `checkedAt` = 現在（Asia/Taipei、秒精度、`yyyy-MM-dd'T'HH:mm:ssXXX`），若早於 package generatedAt → 500 `INTERNAL_ERROR`（時鐘異常，不得偽造）。
  - 快照：以 `findLatestWithStocksByOwnerUserId(owner)` ＋ `AssetService.getSnapshotDetail(entity)` ＋ `SrppSnapshotRevision.of(...)` 計算目前 revision，與 context 中 `sources[assets].revision` 比較；不同或已無快照 → `changedSourceIds` 加 `assets`、reason `SOURCE_REVISION_CHANGED`。DB 讀取例外 → UNKNOWN（`SOURCE_UNVERIFIABLE`）。
  - 日曆：`isTwTradingDayCachedOnly(package.tradingDate)`：`false` → 加 `calendar`、`CALENDAR_CHANGED`；empty → UNKNOWN、`CALENDAR_UNVERIFIABLE`。
  - 政策：步驟 3 已確認仍受支援。
  - **已知行為：** `MarketDataService` 只快取當年度台股假日，跨年讀取前一年度的 pinned package（例如 1 月初讀去年 12 月）時 cached-only 必回 empty → freshness UNKNOWN＋`CALENDAR_UNVERIFIABLE`（pinned 仍回 200）；須有對應測試。
  - **本版刻意範圍：** assets 的 freshness 只核對快照 persisted 投影 revision；live 估值與匯率變動不在讀取時判 STALE，而是由 producer 每 5 分鐘重新發布、最新模式取最新 `generated_at` 涵蓋（proposal §9：CURRENT 不代表行情時效）。
  - status：任一 changed → STALE（changedSourceIds 字典序）；否則任一 unverifiable → UNKNOWN（changedSourceIds []）；否則 CURRENT（兩陣列皆空）。reasonCodes 字典序去重。
  - **不得呼叫 `StockPriceService.getLiveAssets`、`PriceQueryService`、Redis、`isTwTradingDayKnown` 或任何會外呼的方法。**

- [ ] 453.4 **回應組裝。** 分層：`SrppDailyContextReadService` 回傳領域結果（sealed interface，例如 `Ok(String body)` 與 `Problem(String code)`），**不決定 HTTP status、不組 problem body**；code→HTTP status 對照、problem JSON 與 Content-Type／Cache-Control 由 controller（或其 scoped advice）以 `SrppProblemCatalog` 產生（structure.md §2.2：Service 不直接組 HTTP response）。summary／evidence 的 context 嵌入與字串組裝可留在 service。
  - summary：以字串組 `{"kind":"SUMMARY","context":<context_jcs 原文>,"contextContentSha256":"<sha256(context_jcs)>","freshness":{"status":…,"checkedAt":…,"changedSourceIds":[…],"reasonCodes":[…]}}`；context 原文不得 parse 後重新序列化。
  - evidence：`{"kind":"EVIDENCE","packageId":…,"sourceId":…,"bodyMediaType":"application/json","bodyEncoding":"UTF-8","body":<body 以 Jackson 字串逸出>,"bodySha256":"<sha256(body)>"}`。
  - problem：`{"type":"about:blank","title":…,"status":…,"detail":…,"instance":"/api/public/srpp/daily-context","code":…,"retryable":…}`，title／detail 用 `SrppProblemCatalog` 的固定文案（每個 code 一組固定英文 title＋繁中 detail；retryable 逐 code 固定：`CALENDAR_UNAVAILABLE`、`CONTEXT_NOT_READY` 為 true，其餘（含 503 `OWNER_UNAVAILABLE`——帳號不存在或停用時重試無益）一律 false），不得含帳號、SQL、例外訊息。未預期例外由本 controller 範圍的 `@RestControllerAdvice(assignableTypes = InternalSrppDailyContextController.class)` 轉 500 `INTERNAL_ERROR`。

- [ ] 453.5 **測試。**
  - `SrppDailyContextReadService` 的單元測試（Mockito、固定 `Clock`、不啟 Spring）：每一個錯誤碼分支一個案例；package 與 evidence 查詢都以本人 ownerId 呼叫（evidence 查詢帶 owner 比對）；並驗證判斷順序（例如未知政策＋錯誤日期 → 409）；latest 09:04 → 503、11:39 查 `11:40` → 503；cached 日曆 false／empty；他人 packageId → 404 且 repository 只被以本人 ownerId 查詢；pinned STALE／UNKNOWN 回 200；evidence 非 AVAILABLE／查無 → 404；summary 回應中 context 片段逐位元等於 `context_jcs`、hash 相符。
  - 零副作用：以 Mockito `verifyNoInteractions` 證明 `StockPriceService`、`PriceQueryService`、Redis template、`SrppPackagePublisher`、所有 repository 的 save／delete 方法都沒有被呼叫，`MarketDataService` 只被呼叫 `isTwTradingDayCachedOnly`。
  - `InternalSrppDailyContextController` 的單元測試（`@WebMvcTest` 或 standalone MockMvc）：Content-Type、`Cache-Control: private, no-store`、problem JSON 欄位精確；每個 problem code 的 HTTP status 由 controller 依 `SrppProblemCatalog` 對照（逐 code 斷言）。

- [ ] 453.6 **鐵則。** 本端點只供 container 內 BFF 呼叫，不加入 frontend proxy、`MarketDataBffRoutes` 或 9090 gateway（9090 對外路由由 t454 的 BFF 公開 route 負責）；不 import 券商 SDK、不寫任何表、不觸發 producer。

## 驗證

```bash
bash scripts/spec-check.sh
cd backend && mvn -q test -Dtest='SrppDailyContextReadService*,InternalSrppDailyContextController*' -DextraArgLine=-Dnet.bytebuddy.experimental=true
cd backend && mvn -q test -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

地端 `/run-stack` 重建 business-services 後，於容器網路內以 configured-admin header 呼叫 `/internal/public-srpp/daily-context?tradingDate=<今天>&slot=09:05&policyBundleSha256=<64 個 0>`，預期 409 `POLICY_UNSUPPORTED`，並比對前後 `srpp_context_package`、`srpp_owner_key` 列數不變。

## 完成報告

（實作者回填：改動檔案、各錯誤碼測試結果、零副作用證據、未能在雲端執行的驗證項。）
