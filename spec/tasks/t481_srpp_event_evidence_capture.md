# [t481] SRPP 事件證據擷取 API（變體 A：排程判讀、伺服器驗證引用並確定性計分）

**對應 Requirements:** Requirement 181（排程的 LLM 提交帶引用的六項國際政經風險判讀，伺服器驗證引用、機械計分並保存不可變收據；不自行呼叫 LLM）
**前置任務:** t483（佔位實作先改為 fail-closed）、t484（共用基礎：415／400／500 handler、七欄 problem 載體、BFF `SrppOwnerResolver`、`PublishedSwaggerIdentity`、API 錯誤日誌目錄）
**Liquibase changeset:** v1.147.0-srpp-event-evidence-bundle.sql

## 背景

SRPP 日報（09:05、11:40，Claude 與 Codex 兩軌各自獨立執行）每輪都要判讀六項國際政經風險（D-167／D-168／D-169）。現行流程是：排程的 LLM 讀 `public_info_*.json` 與其他來源，自己填六列風險表，之後由 SRPP 端的 `verify_email_html.mjs` 事後核對總分、已評項數與風險模式。這個流程有兩個缺口：

1. 計分與風險模式沒有伺服器端的單一事實來源，Claude 與 Codex 各自填表（SRPP 專案實測 2026-09-30 至 2026-10-07 共 8 組同日同時段的兩軌配對，每一組的六項分數向量都不同，其中 6 組風險模式不同）。
2. 引用無法機械驗證：SRPP 專案歷史日報以標題引用的 25 筆不重複紀錄，只有 7 筆能在所標示日期的 `public_info` 檔找到；同日檔會被覆寫（`NewsPoller.exportPublicInfoJson` 的 Javadoc 註明「同日多輪覆寫＝當日最新」），日報也沒有記錄當時檔案的雜湊。

本任務建立 `POST /api/public/srpp/event-evidence/capture`（路徑沿用，gateway／BFF／frontend deny／Tailscale 的 exact 路由與 OpenAPI 路徑已存在）的真正實作，採**變體 A**：

- 判讀者仍是排程的 LLM（D-197「LLM 主控」不變），request 帶它的判讀與引用；
- 伺服器只做確定性的事：驗證引用確實存在於 `news_headline`（即 `public_info_*.json` 的單一上游）、依 `SrppRiskRubricV1` 機械計分、保存不可變收據並回傳；
- bundle 以 consumer 與 decisionId 區分，Claude 與 Codex 各自獨立（D-164 兩軌比較不失效），重跑（新的 decisionId）得到新 bundle；
- 伺服器**不**呼叫任何 LLM、不抓取、不寄信、不下單，結果永遠 `tradeAuthorization:false`、`placesOrders:false`。

此任務推翻先前版本（變體 B：伺服器端 LLM port、一個 owner／日期／時段只有一筆共用 bundle）。理由：B 會讓兩軌共用單一結果，與現況（兩軌各自判讀）不等價，且需要另行決定 LLM 供應商、金鑰與保護性儲存。**列為不做**：公開資訊快照 GET 端點、依 id 讀回的 GET 端點（replay 一律用同一個 POST）、伺服器端 LLM。

此 API 的呼叫端是 SRPP 專案。SRPP 專案的呼叫端契約提案（`docs/EVENT_EVIDENCE_INGEST_9090_API_SPEC.md`，SRPP repo 內）原本與本任務有差異，**以本檔為準**，SRPP 端已跟著改為：路徑 `/capture`、無 GET 端點與 `snapshot` 欄位、引用以 `source/url/category/title/publishedAt`、`analysisProfile` 選填、policy／swagger hash 不屬於 unique 識別而是 metadata 檢查（不符回 409 `BUNDLE_METADATA_MISMATCH`）。

本任務沿用 t484 提供的共用基礎：Content-Type 的 415、400 空 body、500 `INTERNAL_ERROR` 的 handler 與七欄 problem 目錄（t484 登錄了 `INVALID_REQUEST`、`UNSUPPORTED_MEDIA_TYPE`、`NON_TRADING_DAY`、`POLICY_UNSUPPORTED`、`SWAGGER_MISMATCH`、`OWNER_UNAVAILABLE`、`CALENDAR_UNAVAILABLE`、`CONTEXT_NOT_READY`、`UPSTREAM_INVALID`、`INTERNAL_ERROR`），BFF 的 package-private `SrppOwnerResolver`（`bff/src/main/java/com/steven/assets/bff/publicsrpp/`），以及 backend 的共用 bean `PublishedSwaggerIdentity`（`com.steven.assets.service.srpp`，`sha256()` 回 `Optional<String>`）。

## 要做什麼

### A. 路由與 BFF 流程

- [ ] 481.1 沿用既有 exact `POST /api/public/srpp/event-evidence/capture`；**9090 manifest 維持 23 個 method/path pairs，不新增路徑**。BFF `SrppCaptureController.event()` 的處理順序固定如下（`decision()` 維持原樣轉送，由 t482 另定），**任何一步失敗都不呼叫後面的步驟；步驟 2 的兩種 400 在 owner 解析之前確定且零 outbound**：
  1. 讀完整 body（codec 上限 1 MiB 已設定）。
  2. 以同套件既有的 `SrppDailyContextResponseValidator.parseStrict(byte[])`（拒絕重複 member）解析；無法解析、不是 JSON object、或 `ownerEmail` 存在但不是符合 pattern `^[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}$`（不分大小寫，最大 254 字元；與 BFF `SrppOrchestratedQuery.EMAIL` 相同，後者是 private，所以此處複製或放寬其可見度，business 端驗證同一個 pattern）的字串 → 400 `INVALID_REQUEST`。
  3. 用 t484 的 `SrppOwnerResolver` 解析 owner（缺省 configured-admin；有 `ownerEmail` 時 `byEmail` 且須 ACTIVE；5 秒逾時）；失敗一律 503 `OWNER_UNAVAILABLE` 且不揭露帳號是否存在。
  4. 帶 `X-User-*` headers 並清除 caller 身分（`SrppOwnerResolver` 的輔助方法）呼叫 business，仍轉送原始 JSON bytes，business 呼叫逾時 10 秒。
  5. 驗證 business 回應（**只用於 `event()`**）：2xx 必須是 JSON object 且 `status="FINAL"`、`tradeAuthorization=false`、`placesOrders=false`；非 2xx 必須是 `application/problem+json` 且 `code` 屬 t484 登錄的 code 加上本任務新增的 code（見 481.5）；逾時、transport 失敗或不符合上述 → 502 `UPSTREAM_INVALID`。
  business 端以 `CurrentUserContext`（`hasUser()` 為 false → 503 `OWNER_UNAVAILABLE`）取得 owner，**不得**再用 request 內的 email 字串當作 owner 識別。
- [ ] 481.2 Clean Architecture 與交接：`InternalSrppCaptureController` 只做 HTTP 與 DTO 轉換；邏輯放在新 service `EventEvidenceCaptureService`（`com.steven.assets.service.srpp`）；資料存取只經新 `SrppEventEvidenceBundleRepository`；`RiskEvidenceEvaluator` 與 `SrppRiskRubricV1` 放在同一個 package `com.steven.assets.service.srpp`（測試同 package，才能呼叫 package-private 方法）；controller 不得注入 repository。**與 t483 的交接**：從 `SrppCaptureService` 移除 `captureEvent`、`EVENT_FIELDS`、事件證據 repository 欄位與只服務它的私有方法；`InternalSrppCaptureController.event()` 改委派 `EventEvidenceCaptureService`；t483 針對 `captureEvent` 的測試改寫或移除，針對 `evaluate` 的測試保留不動。

### B. Request（嚴格 JSON object；未知欄位、重複 member、欄位型別錯誤、缺必填一律 400 `INVALID_REQUEST`）

- [ ] 481.3 欄位與限制：
  - `ownerEmail`：選填，pattern 同 481.1 步驟 2。**不納入** `request_sha256`（owner 已解析為 id）。
  - `tradingDate`：必填，`yyyy-MM-dd`，必須等於服務端 `Asia/Taipei` 今天（否則 400），且 `MarketDataService.isTwTradingDayCachedOnly(date)` 為 `Optional.of(true)`；`Optional.empty()` → 503 `CALENDAR_UNAVAILABLE`；`Optional.of(false)` → 409 `NON_TRADING_DAY`。
  - `slot`：必填，`09:05` 或 `11:40`。
  - `analysisProfile`：**選填**，缺省 `TW_DAILY`，有值只可 `TW_DAILY`（它是識別的一部分，缺省與明寫 `TW_DAILY` 視為相同）。
  - `consumer`：必填，只可 `Claude` 或 `Codex`。
  - `decisionId`：必填，符合 `^[A-Za-z0-9][A-Za-z0-9._:-]{0,99}$`（即 SRPP `report_facts.json` 的 `decision_id`；重跑會有新的 ID）。
  - `policyBundleSha256`、`swaggerSha256`：必填，各為 64 位小寫 hex。
  - `categories`：必填，JSON 陣列，最多 12 項（超過 → 400）。每項是 object：`code`（必填字串，最多 64 字元）、`claimedScore`（必填，JSON 整數且須在 Java `int` 範圍內；不是整數型別或超出範圍 → 400）、`conflicting`（選填布林，預設 false，LLM 判定來源互相矛盾或不可比時為 true）、`sources`（必填陣列，可為空，最多 20 筆）。**類別結構與分數範圍是 422 而非 400**（見 481.5）：`code` 只可是 `fed`、`geopolitics`、`oil`、`taiwan_politics`、`us_taiwan_inflation`、`semiconductor_cycle_and_advanced_process`，六個各恰好一次；`claimedScore` 在 0 至該類上限（fed 2、geopolitics 3、oil 2、taiwan_politics 3、us_taiwan_inflation 2、semiconductor_cycle_and_advanced_process 3）。
  - `officialEvents`：選填陣列，最多 100 筆，每項 `symbol`（`^[0-9A-Z]{4,8}$`）、`status`（`EVENT_FOUND`、`VERIFIED_NO_EVENT`、`SOURCE_UNAVAILABLE` 之一，其他值 → 400）、`sources`（最多 3 筆）。**非法組合**：`VERIFIED_NO_EVENT` 或 `SOURCE_UNAVAILABLE` 帶非空 `sources`、或 `EVENT_FOUND` 的 `sources` 含 `OFFICIAL_PAGE` 以外的 kind → 400 `INVALID_REQUEST`；symbol 重複 → 422 `OFFICIAL_EVENT_INVALID`。**`officialEvents` 內的來源問題不使整包 422**：`EVENT_FOUND` 若沒有任何通過驗證的 `OFFICIAL_PAGE`（來源不在允許清單、期間不合法或根本沒有）則**降級**為 `SOURCE_UNAVAILABLE`，並在該項回應記錄 `downgradeReason`（`SOURCE_NOT_ALLOWED`、`SOURCE_PERIOD_INVALID` 或 `OFFICIAL_SOURCE_MISSING`），避免中性的 D-092 揭露連坐六項風險評分。這只作揭露，**永遠中性，不得影響任何結果、分數或 gate**（D-092）。
  - 欄位上限的最大組合（`categories` 12 項×每項 20 筆來源、`officialEvents` 100 筆×每筆 3 筆來源）以 ASCII 計理論本文小於 gateway 預設的 1 MiB；含 CJK 字元或 `\uXXXX` 跳脫的極端本文可能超過，超過 1 MiB 由 nginx 回 413，屬本端點契約之外。
  - 任何其他欄位——包含 `score`、`assessed`、`evidenceStatus`、`totalScore`、`assessedCount`、`riskMode`、`tradeAuthorization`、`refresh`、`force`、crawler 原文、LLM prompt／model、交易指令——都是 400。
- [ ] 481.4 `sources[]` 的三種 `kind`（其他值或缺必填欄位 → 400）。字串長度上限：`source` 100、`url` 1024、`title` 500、`category` 32、`endpoint` 200，且不得空白：
  - `NEWS_HEADLINE`：`source`、`url`、`category`、`title`、`publishedAt`（ISO-8601 含 offset 的字串，無法解析 → 422 `CITATION_NOT_FOUND`）。伺服器以 `sha256(source + "|" + url + "|" + category)`（UTF-8、小寫 hex；公式與 external-materials-service 的 `NewsPoller.dedupeKey` 相同，後者是 `private static`，所以 backend 必須自行重製並以 481.13 的黃金向量鎖住）查 `news_headline.dedupe_key`（新增 `NewsHeadlineRepository.findByDedupeKey`，該欄位有唯一索引 `uk_news_headline_dedupe`），找不到 → `CITATION_NOT_FOUND`。找到的列另須 `fetched_at` 不早於 `tradingDate` 往前 3 個日曆日的 00:00（Asia/Taipei），否則 `SNAPSHOT_STALE`（對應 `risk_score_evidence_policy`「public_info 的 generatedAt 不早於本輪日期前 3 個日曆日」；匯出只含 `fetched_at` 為當日的列，所以此檢查對當日檔的誠實引用恆通過，用來擋住引用數日前舊列的情形）。
  - **比對規則依 category 分兩類**。匯出 JSON 的 `publishedAt` 是秒精度（`NewsRow` 的 `@JsonFormat` 為 `yyyy-MM-dd'T'HH:mm:ssXXX`、`Asia/Taipei`），而 `news_headline.published_at` 保有微秒。(a) 預設：該列 `title` 與請求 `title` 相等（兩邊都先 NFKC、`臺`→`台`、連續空白折疊為單一空白、trim 後比較，與 `risk_score_evidence_policy` 的臺／台等價及 SRPP 端 `normalize` 一致），且該列 `published_at` 與請求 `publishedAt` 在**截斷到秒後**是同一個 instant，否則 `CITATION_NOT_FOUND`。(b) 設定 `srpp.event-evidence.volatile-categories`（環境變數 `SRPP_EVENT_EVIDENCE_VOLATILE_CATEGORIES`，預設 `fx,us-market,kr-market,kr-intraday`）所列的 category：這些列使用固定 URL，每輪由 `MarketSnapshotFetchClient`／`KrIntradayFetchClient` 以 `Instant.now()` 就地覆寫 `title`（內含即時數值）、`summary` 與 `published_at`，故**只驗證存在（dedupe_key 命中）與 `fetched_at` 視窗，不比對 title 與 publishedAt**。此清單與 external-materials-service 的 producer 沒有機械連結，所以必須在 `external-materials-service/src/main/java/com/steven/assets/externalmaterials/client/NewsRow.java` 的 Javadoc 加一句維護警語：新增「固定 URL 且每輪就地覆寫」的 producer 時，必須同步更新 `srpp.event-evidence.volatile-categories`，否則其合法引用會被誤判為 `CITATION_NOT_FOUND`。
  - 通過者 provenance＝`DB_VERIFIED`，來源期間＝**資料庫該列** `published_at` 截斷到秒後以 `yyyy-MM-dd'T'HH:mm:ssXXX`（Asia/Taipei）序列化的字串。`DB_VERIFIED` 只代表「該列存在於資料庫且在新鮮度視窗內」，**不驗證與該風險類別的相關性，也不驗證 `published_at` 視窗**（後者是 SRPP 提案列為未決的項目，不在本任務範圍）。
  - `API_RESPONSE`：`endpoint`（必須在 `srpp.event-evidence.api-endpoints`，環境變數 `SRPP_EVENT_EVIDENCE_API_ENDPOINTS`，預設 `/api/public/commodity-prices`、`/api/public/market-index`、`/api/public/exchange-rate/usd-twd`；不在清單 → 422 `SOURCE_NOT_ALLOWED`）、`observedDate`（`yyyy-MM-dd`；無法解析、晚於 `tradingDate` 或早於其往前 `srpp.event-evidence.api-observed-max-age-days` 天〔預設 31，沒有政策依據的初始值〕→ 422 `SOURCE_PERIOD_INVALID`）。provenance＝`ATTESTED`，來源期間＝`observedDate`。
  - `OFFICIAL_PAGE`：`url`（必須 `https`，host 等於 `srpp.event-evidence.official-domains`〔環境變數 `SRPP_EVENT_EVIDENCE_OFFICIAL_DOMAINS`，預設 `federalreserve.gov`、`bls.gov`、`bea.gov`、`treasury.gov`、`dgbas.gov.tw`、`stat.gov.tw`、`cbc.gov.tw`、`twse.com.tw`、`tpex.org.tw`〕的網域或其子網域，否則 422 `SOURCE_NOT_ALLOWED`）、`retrievedAt`（ISO-8601 含 offset，必須落在 `tradingDate` 當日 Asia/Taipei 且不晚於伺服器現在加 5 分鐘時鐘容忍，否則 422 `SOURCE_PERIOD_INVALID`）、`period`（必須符合 `^(?:19|20)\d{2}(?:-(?:0[1-9]|1[0-2])(?:-(?:0[1-9]|[12]\d|3[01]))?|-Q[1-4])$`，這是 SRPP 驗證器 `RISK_SOURCE_PERIOD_RE` 的**更嚴格子集**——不接受 `/`、`年月` 字樣與單位數月份，因此呼叫端須正規化；不符 → 422 `SOURCE_PERIOD_INVALID`）。provenance＝`ATTESTED`。
  - 預設允許清單只是初始值，上線前須由 Steven 確認（清單偏窄時，ATTESTED 來源減少會使 `assessedCount<4` 更常見、`riskMode` 更常是 `DEFENSIVE`）；清單為空時對應 kind 一律 `SOURCE_NOT_ALLOWED`。`ATTESTED` 與現行 SRPP 驗證器同一門檻（具名來源加明確期間），伺服器不重抓外部網站；收據照實記錄 provenance，**不得把 `ATTESTED` 呈現成伺服器已驗證**。

### C. 驗證順序與錯誤

- [ ] 481.5 business 端依序執行，第一個失敗即回應。**所有失敗都不寫入任何資料列、不佔用識別**：
  1. 媒體型別 415、空 body 400（t484）。
  2. JSON 結構、型別、未知欄位、長度上限 → 400 `INVALID_REQUEST`。
  3. owner：`CurrentUserContext.hasUser()` 為 false → 503 `OWNER_UNAVAILABLE`。（經 BFF 時，body 結構錯誤與 `ownerEmail` 格式錯誤的 400 在 owner 解析前確定，見 481.1；其餘 request 欄位的 400 在 owner 之後。）
  4. 日期不是今天 → 400；日曆 503 `CALENDAR_UNAVAILABLE`／409 `NON_TRADING_DAY`。
  5. 規則包：`SrppPolicyRegistryService.find(policyBundleSha256)` 為空 → 409 `POLICY_UNSUPPORTED`（呼叫端提供的 hash 只當查詢鍵，不 echo 冒充支援）。
  6. Swagger：`PublishedSwaggerIdentity.sha256()` 為 `Optional.empty()` → 503 `CONTEXT_NOT_READY`（fail closed，不放行任何 hash）；`swaggerSha256` 與已發布身分不同 → 409 `SWAGGER_MISMATCH`。
  7. **以識別查詢既有 bundle**（見 481.8）：存在時若 `policy_bundle_sha256` 或 `swagger_sha256` 與請求不同 → 409 `BUNDLE_METADATA_MISMATCH`；若 `request_sha256` 不同 → 409 `BUNDLE_CONTENT_CONFLICT`；兩者皆同 → 200 回傳既有內容，**replay 不重新查詢 `news_headline`**（來源列之後可能被覆寫或被 `retention-days` 清除，重送不得因此變 422）。
  8. 查無既有 bundle 才做證據驗證，**收集全部錯誤後一次以 422 回應**：HTTP 422、`application/problem+json`、`code="EVIDENCE_REJECTED"`、`errors[]` 逐項 `{code, riskCategory?, symbol?, sourceIndex?}`（`riskCategory` 是六項風險類別代碼，與 `NEWS_HEADLINE.category` 爬蟲分類無關；`officialEvents` 的錯誤用 `symbol`＋`sourceIndex`），`errors[].code` 為 `UNKNOWN_CATEGORY`、`MISSING_CATEGORY`、`DUPLICATE_CATEGORY`、`SCORE_OUT_OF_RANGE`、`CITATION_NOT_FOUND`、`SNAPSHOT_STALE`、`SOURCE_NOT_ALLOWED`、`SOURCE_PERIOD_INVALID`、`SCORE_WITHOUT_EVIDENCE`、`OFFICIAL_EVENT_INVALID`（僅限 symbol 重複）之一；`errors[]` 依 `(riskCategory 或 symbol, sourceIndex, code)` 穩定排序，最多 200 項（超過截斷並在 problem 加 `truncated:true`）。`SCORE_WITHOUT_EVIDENCE`：`claimedScore` 非 0，但該類沒有任何通過驗證的來源，或 `conflicting=true`。
  9. 評分（481.6）並 insert（481.8）。
  - 其餘：持久化資料違反契約 502 `UPSTREAM_INVALID`；非預期錯誤 500 `INTERNAL_ERROR`。
  - 本任務在 t484 的 problem 目錄新增這些 code：`EVIDENCE_REJECTED` 422（retryable false）、`BUNDLE_METADATA_MISMATCH` 409、`BUNDLE_CONTENT_CONFLICT` 409（皆 retryable false）；BFF 目錄不新增（它只轉送這些 business problem）。

### D. 確定性計分（純函式）

- [ ] 481.6 新增 `RiskEvidenceEvaluator`（無 IO、無 Spring 依賴）與常數類 `SrppRiskRubricV1`（類別代碼順序、各類上限、決策閾值 `defensive_category_score=3`、`defensive_total_score=8`、`minimum_evaluated_categories=4`、`cautious_total_score=5`；數值取自 SRPP 專案 `assumptions/model_inputs.json` 的 `execution_policy.risk_score_category_maxima` 與 `risk_score_decision_policy`，對應 SRPP 規則包 D-198／policy v2.46.0 的 D-167／D-168／D-169）。這些是**協定列舉與由 SRPP 政策持有的版本化常數**，不是使用者可維護的業務分類，因此不入資料庫、不開 `/api/settings/*`（放進設定頁反而讓使用者能改到政策閾值）；先例是 `SrppFormulaCatalog`（公式版本與 manifest 內建於程式）。`consumer`、`analysisProfile` 與來源 `kind` 同理，是協定列舉，以資料庫 CHECK 與程式列舉固定。SRPP 規則改變時須新增 `SrppRiskRubricV2` 並以新 `rubricSha256` 揭露，不得就地修改 V1。`rubricSha256`＝下列 JSON 的 JCS（`SrppJcs.canonicalize`，key 依字母序）UTF-8 SHA-256：`{"schema":"SRPP_RISK_RUBRIC_V1","categories":[{"code":"fed","max":2},{"code":"geopolitics","max":3},{"code":"oil","max":2},{"code":"taiwan_politics","max":3},{"code":"us_taiwan_inflation","max":2},{"code":"semiconductor_cycle_and_advanced_process","max":3}],"decision":{"cautiousTotalScore":5,"defensiveCategoryScore":3,"defensiveTotalScore":8,"minimumEvaluatedCategories":4}}`；它隨回應揭露（計算值，不存欄位）；此 JSON 的 `rubricSha256` 黃金值為 `3a780e748aa3dc8cc94053178c378069fc54b3c10a6d1a09bd32c9bc0628f378`，測試必須斷言。`policyBundleSha256` 在此只是「規則包已登錄」的查詢鍵，計分用的是 `SrppRiskRubricV1`，不是規則包內容（`SrppPolicyDocument` 目前只含 `schema` 與 `targets`）。每個類別由已驗證來源數與 `claimedScore` 決定：
  - 無已驗證來源，或 `conflicting=true`（此時 `claimedScore` 必須為 0，否則 `SCORE_WITHOUT_EVIDENCE`）→ `score=0`、`assessed=false`、`evidenceStatus="EVIDENCE_INSUFFICIENT"`；
  - 有已驗證來源且 `claimedScore=0` → `score=0`、`assessed=true`、`evidenceStatus="VERIFIED_NO_RUBRIC_EVENT"`；
  - 有已驗證來源且 `claimedScore≥1` → `score=claimedScore`、`assessed=true`、`evidenceStatus="RUBRIC_EVENT_FOUND"`。
  - 「已驗證來源」＝通過 481.4 檢查的來源（`DB_VERIFIED` 或 `ATTESTED` 皆算）。
  - `totalScore`＝六類 `score` 總和；`assessedCount`＝`assessed=true` 的類別數；`riskMode`：任一類 `score ≥ 3`、或 `totalScore ≥ 8`、或 `assessedCount < 4` → `DEFENSIVE`；否則 `totalScore ≥ 5` → `CAUTIOUS`；其餘 `NORMAL`。
  - `RiskEvidenceEvaluator` 對外暴露 package-private 的 `classify(int[] scores, int assessedCount)`，供窮舉測試直接呼叫（經 `evaluate` 無法造出「有分數但已評數不足」這類組合）。
- [ ] 481.7 黃金案例與窮舉：黃金案例檔**已隨本 spec 變更存在於** `spec/fixtures/srpp_risk_evidence_golden.json`（位元組 SHA-256 為 `8b534496df63ccc09ff9906598518a6c3f368c5d0fb46a0f779b62dcf3101644`；它由 SRPP 專案的 `scripts/fixtures/risk_evidence_golden.json` 原樣複製而來，SRPP 路徑只是出處，實作者不需要也不應依賴它）。測試以與 `SrppFormulaCatalogTest` 相同的方式讀取（先試 `spec/fixtures/…`，不存在再試 `../spec/fixtures/…`），先斷言檔案 SHA-256 等於此值，再使用。外層結構 `{"schema":"SRPP_RISK_EVIDENCE_GOLDEN_V1","cases":[…]}`，共 23 案；每案欄位 `caseId`、`tradingDate`、`slot`、`consumer`、`profile`、`otherProfileIdentical`、`categories.<code>.{score,assessed,evidenceStatus}`、`stated.{totalScore,assessedCount,riskMode}`。測試逐案以 `categories` 還原 evaluator 輸入（`assessed=true` 視為有 1 筆已驗證來源且 `claimedScore=score`，`false` 視為 0 筆且 `claimedScore=0`），斷言輸出的 `totalScore`、`assessedCount`、`riskMode` 與 `stated` 完全相等，且各類 `evidenceStatus` 相等。另加窮舉：六類分數的全部組合（每類 0 至上限，共 1,728 種）乘上 `assessedCount` 0 至 6（共 12,096 組），`classify` 的結果與一個獨立寫在測試內、依 481.6 文字規則實作的參考式逐一比對。

### E. 識別、冪等與保存

- [ ] 481.8 識別＝`(owner_user_id, trading_date, slot, analysis_profile, consumer, decision_id)`，資料庫 unique。`request_sha256`＝**正規化後**的 request 經 RFC 8785 JCS 的 UTF-8 SHA-256；正規化＝去除 `ownerEmail`、`analysisProfile` 缺省補成 `TW_DAILY`、每個 category 的 `conflicting` 缺省補成 `false`、`officialEvents` 缺省補成 `[]`（所以缺省與明寫預設值的等價重送不會被誤判為內容衝突）。處理流程見 481.5 第 7 至 9 步。**並行**：外層方法**不得**加 `@Transactional`；用 `TransactionTemplate`（或另一個 bean 的 `REQUIRES_NEW` 方法）把「查詢既有 → 評分 → insert」包成單一 transaction，insert 在 callback 內以 `saveAndFlush` 執行（讓唯一鍵衝突在 callback 內就拋出，而不是延到 commit 時以 `TransactionSystemException` 或 `JpaSystemException` 出現）。**callback 內不得 catch** 該例外——它一離開 repository 外層交易就已被標為 rollback-only 且 PostgreSQL 交易已 abort；必須讓例外穿出 `template.execute`（template 會 rollback 並原樣 rethrow），再在**外層**判斷。並行輸家的判斷：沿 cause chain 找到 `java.sql.SQLException` 且 `getSQLState()` 為 `23505`，並且（`org.hibernate.exception.ConstraintViolationException#getConstraintName()` 或訊息）指向具名 constraint `srpp_event_evidence_bundle_identity_uq`；其他例外（包含外鍵違反 `23503`）一律 500 `INTERNAL_ERROR`。註：`org.postgresql:postgresql` 在 `backend/pom.xml` 是 `runtime` scope，main 程式**不可** import `PSQLException`，它只能出現在測試。輸家在**第二個全新的 `template.execute`** 重讀並依 481.5 第 7 步回 200／409，仍查無則 500，不重試。**明禁**沿用現有 `SrppCaptureService` 的寫法（同一 `@Transactional` 方法內 `saveAndFlush` 後 catch `DataIntegrityViolationException` 再查詢：在 PostgreSQL 上交易已被標為 abort，重讀會失敗）。不同 `consumer` 或不同 `decisionId` 是各自獨立的 bundle；本 API 不強制一個時段只有一筆。
- [ ] 481.9 新增 Liquibase changeset `v1.147.0-srpp-event-evidence-bundle.sql`（冪等：`CREATE TABLE IF NOT EXISTS`、`DROP TABLE IF EXISTS`、trigger 先 `DROP TRIGGER IF EXISTS`；並登錄於 `db.changelog-master.yaml`）：建立 `srpp_event_evidence_bundle`，欄位 `id uuid` 主鍵、`owner_user_id bigint NOT NULL` 外鍵 `app_user(id)`、`trading_date date NOT NULL`、`slot varchar(5) NOT NULL` 且 CHECK 為 `09:05|11:40`、`analysis_profile varchar(24) NOT NULL` 且 CHECK 為 `TW_DAILY`、`consumer varchar(16) NOT NULL` 且 CHECK 為 `Claude|Codex`、`decision_id varchar(100) NOT NULL`、`policy_bundle_sha256 char(64) NOT NULL` 外鍵 `srpp_policy_registry(policy_bundle_sha256) ON DELETE RESTRICT`、`swagger_sha256 char(64) NOT NULL`、`request_sha256 char(64) NOT NULL`、`content_jcs text NOT NULL`、`created_at timestamptz NOT NULL`，具名 unique constraint `srpp_event_evidence_bundle_identity_uq` 於 `(owner_user_id, trading_date, slot, analysis_profile, consumer, decision_id)`；並加上 `BEFORE UPDATE` trigger `trg_srpp_event_evidence_bundle_no_update`，呼叫既有函式 `reject_srpp_immutable_update()`（與 `srpp_context_package` 等 SRPP 不可變表相同）。同一 changeset 刪除佔位表 `srpp_event_evidence`（它只由 t483 之前的佔位服務寫入，內容是空的 `SOURCE_UNAVAILABLE` 結果，沒有任何使用端依賴；t483 先上線保證不再有新列）；同步刪除 `SrppEventEvidence` entity 與 `SrppEventEvidenceRepository`。**正規化說明**：不存 `content_sha256`、`rubric_sha256`（可由 `content_jcs`／常數類計算，讀取時才算）。保留的 denormalization 及理由：`request_sha256`——request 本身不保存，無法重算，衝突偵測需要它；`content_jcs` 內含 `score`、`assessed`、`riskMode` 等——它是評分當下的不可變收據，評分輸入（request 與 `news_headline` 列）不保存或之後會被覆寫／清除，結果無法重算，所以不屬「可計算衍生值」；識別欄位同時出現在欄位與 `content_jcs.identity`——與 `srpp_context_package` 相同的查詢鍵 denormalization。
- [ ] 481.10 保存與雜湊：`content_jcs` 是 RFC 8785 JCS 的**完整回應本體，但不含 `created` 與 `idempotentReplay`**（回傳時才補上），**包含** `eventBundleContentSha256`。`eventBundleContentSha256` 的算法固定為：取不含 `created`、`idempotentReplay`、`eventBundleContentSha256` 的本體 → JCS → UTF-8 SHA-256 → 以小寫 hex 補入本體。不保存 request 原文、爬蟲內文或任何持股／現金／帳號資料；對外 log 只可寫 `eventBundleId`、雜湊、狀態、耗時、穩定 error code，**唯一例外**是 t484 的 `INTERNAL_ERROR` handler 會在伺服器端記錄例外類別與 stack trace（不含 request 內容與帳號）。保留期至少涵蓋對應日報草稿與 MIME 稽核週期；row 不得被 update／delete 當作重跑（trigger 擋 update；本任務不新增刪除排程）。**已知風險（明列、不在本任務處理）**：9090 沒有應用層驗證（私有網路信任模型）且 `decisionId` 由呼叫端自訂，bundle 筆數沒有上限，日後可另立任務沿用 `srpp.retention-days` 清理超過保留期的列；本端點不要求 slot 時間已到才可 capture（排程在 slot 時間之後才會呼叫，早呼叫只會得到當時資料的 bundle，不影響其他 consumer 或 decisionId）。

### F. 回應與 OpenAPI

- [ ] 481.11 成功回應（首次 201、replay 200，本體相同，只有 `created` 與 `idempotentReplay` 不同）：`schemaVersion`（整數 1）、`eventBundleId`（UUID 字串）、`status`（固定 `"FINAL"`）、`created`、`idempotentReplay`、`identity{tradingDate,slot,analysisProfile,consumer,decisionId,policyBundleSha256,swaggerSha256}`、`rubricSha256`、`riskAssessment{categories[],totalScore,assessedCount,riskMode}`、`officialEvents[]`、`tradeAuthorization:false`、`placesOrders:false`、`eventBundleContentSha256`。`categories` 固定依 481.3 的代碼順序，每項 `{code, score, assessed, evidenceStatus, sourceReceipts[]}`；`sourceReceipts` 依請求順序，每項 `{kind, provenance, period}`，`NEWS_HEADLINE` 另有 `dedupeKey`，`API_RESPONSE` 另有 `endpoint`，`OFFICIAL_PAGE` 另有 `host`。`score`、`totalScore`、`assessedCount` 是 JSON 整數（沒有小數欄位，不使用浮點）。`officialEvents` 依 `symbol` 升冪，每項 `{symbol, status, sourceReceipts[], downgradeReason}`，其中 `downgradeReason` 只在降級時出現（否則整個欄位省略，不輸出 null）。
- [ ] 481.12 `docs/openapi/docker-external-api.yaml`：把 `/api/public/srpp/event-evidence/capture` 的 requestBody 寫成上列完整 schema（含 `additionalProperties:false`、各欄位的 pattern／enum／範圍／長度、`sources` 的 `oneOf`），把 200／201 的 `object` 換成完整 response schema（列出所有 class attribute 與用途），新增 `422`（`application/problem+json`，含 `errors[]` 與 `truncated` schema），保留 t484 已統一的七欄 problem（400／409／415／500／502／503）並列出本檔所有 `code`；`info.version` 再升一個 minor（t484 之後為 `1.22.0`），同步更新 `scripts/tests/docker-external-api-openapi-test.rb` 釘死的版本與 capture 端點的狀態集合（加 `422`；evaluate 不加），重產三份 Swagger Markdown 並地端覆寫 `/Users/steven/Project/SRPP/docs/9090 Port API Swagger.md`（流程與同步清單見 t484 的 484.9）。`db/schema.sql` 依檔頭「重新產生」段重產並納入本次變更；`spec/steering/structure.md` 與 `spec/design.md` 對 9090 manifest 的描述維持 23 pairs，只補充本端點的 request／response 契約。

### G. 測試

- [ ] 481.13 backend 測試（實際類名於完成報告回填）：`RiskEvidenceEvaluator` 單元測試（純 JUnit，即 481.7）；`EventEvidenceCaptureService` 單元測試（Mockito，涵蓋 481.5 的每個 code 各一案、每種 source kind 的通過與失敗、`volatile-categories` 的存在性驗證、帶奈秒的 `Instant` 列被秒精度字串引用必須通過、`conflicting`、重複／缺少／未知 category、`officialEvents` 的非法組合與降級、metadata 與 content 衝突、等價重送〔缺省與明寫預設值〕回 200、replay 不查 `news_headline`、失敗不呼叫 repository 的 `save*`）；`dedupe_key` 黃金向量（`ltn|https://news.ltn.com.tw/news/business/breakingnews/1|news` → `3688d331d3b6765b1ee2dc4b273c4b54b049eaa89f1a661aa433aa4b9a9a5405`；`kr-index|https://finance.yahoo.com/quote/%5EKS11|kr-market` → `ff0ec0f42ac1183b367be9a4c4b35d1e6203eaddbe88ced98c1e253e5b8f9ff1`；`twse|https://www.twse.com.tw/zh/trading/foreign/bfi82u.html|twse-institutional` → `1e76d14cd86b556c9498389229e6eb2b4f626304f3e6aa1edb30b9f550e6a3f7`）；`SrppEventEvidenceBundleRepository` 的真實 PostgreSQL 測試（沿用既有 `SrppRepositoriesPostgresTest` 的 Testcontainers 設定）驗證 unique 識別、CHECK、外鍵、`BEFORE UPDATE` trigger 擋更新，以及 20 執行緒並行送同一請求只產生一列、所有成功回應的 `eventBundleContentSha256` 相同（測試內可使用 `PSQLException` 判斷）。BFF 測試：body 無法解析／重複 key／`ownerEmail` 非字串或空白或格式錯 → 400 且零 outbound、owner 解析（configured-admin、byEmail、非 ACTIVE、逾時 → `OWNER_UNAVAILABLE` 且不揭露帳號）、原始 bytes 轉送、business 非契約回應或逾時 → 502 `UPSTREAM_INVALID`。契約測試：`scripts/tests/docker-external-api-openapi-test.rb` 通過新 schema；`scripts/tests/configure-tailscale-api-gateway-test.sh` 路由不變仍通過。
- [ ] 481.14 約束：不得新增 HTTP client、LLM client、快取寫入、排程或券商呼叫；不得讀取 `asset_snapshot`、持股、現金、交易或任何個人財務資料（本端點只讀 `news_headline`、政策 registry 與日曆）；不得把 `ATTESTED` 來源標成已驗證；`score` 等結果欄位只由 evaluator 產生，request 內出現即 400；實作者不得沿用佔位服務的並行寫法。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test
ruby scripts/render-9090-openapi-docs.rb --check
ruby scripts/tests/docker-external-api-openapi-test.rb
bash scripts/tests/configure-tailscale-api-gateway-test.sh
bash scripts/tests/schema-sql-drift-test.sh
```

以 Docker 重建 `business-services`、`bff`、`api-gateway` 後（依 `.claude/skills/run-stack`），對 loopback 9090 驗證。準備：`shasum -a 256 backend/src/main/resources/srpp/9090-api-swagger.md` 取得 `swaggerSha256`；以 `ruby scripts/srpp-policy-register.rb --bundle <測試用64位小寫hex> --policy <{"schema":"SRPP_POLICY_DOCUMENT_V1","targets":{}}> --manifest <spec/fixtures/srpp_formula_manifest_v2.json>` 產生登錄 SQL，經 `docker exec -i asset-postgres psql` 執行；驗收資料一律用明顯的測試 `decisionId`（例如 `ACCEPT-TEST-481`），驗收後以 DELETE 清掉這些列（trigger 只擋 UPDATE）。案例：非 JSON 回 415 且是 problem+json；未登錄規則包回 409 `POLICY_UNSUPPORTED`；以測試用已登錄規則包送一份引用不存在的 `NEWS_HEADLINE`，回 422 `EVIDENCE_REJECTED` 且資料庫沒有新增列；送合法 request 回 201，同一 request 再送回 200 且 `eventBundleContentSha256` 相同；GET 同路徑回 405 `Allow: POST`；整個過程沒有觸發 crawler rescan 或外部 HTTP。

## 完成報告

（實作者回填。）
