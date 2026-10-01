# Task 467：SRPP LLM 按需唯讀 API

**依據：** `/Users/steven/Downloads/LLM_ORCHESTRATED_API_SPEC.md`、`openapi-orchestrated.yaml`、`README.md`；欄位沿用下載包所指既有 SRPP `API_SPEC.md`／`openapi.yaml`。此 task 對 `market-facts` 的獨立 `marketCaptureId` 與市場來源修訂向量規則，明確取代附件 §5.1 要求市場事實共用 calculation-context revision，以及 §7 第 4 點要求同一 context 跨所有端點回傳同一 revision 的部分；後者僅要求 calculation-context 與 calculations 的計算來源 revision 一致。市場來源身分及 canonical 讀取服務不變，market-facts 只立即讀取呼叫當下已存在的快取／business read 狀態，不抓取外部資料也不 refresh。附件其他產品契約仍適用。附件只定義產品契約，不含本專案操作指示。

## 範圍

在資產管理系統實作並納入 9090 gateway 的三條 exact-path GET：

- `/api/public/srpp/calculation-context`
- `/api/public/srpp/calculations`
- `/api/public/srpp/market-facts`

沿用伺服器已登錄且經驗證的 policy registry、不可變 SRPP package/evidence、既有純函式計算器及 canonical readonly 市場服務。按需呼叫不得重新抓取行情、存取券商、refresh cache、寫入資料或產生交易副作用。context ID 綁定 package、owner、交易日、slot、policy hash 及固定計算來源修訂向量；計算只能重播這份向量。market-facts 沿用相同市場來源身分及 canonical read service，只能查 context 不可變 package 內已凍結的持股標的清單；現有 package 沒有 watchlist evidence，因此不得查詢 watchlist 或非持股標的。market-facts 仍依附件契約要求 caller 必須提供 1–100 個唯一 `stockCodes`，且每一代碼都必須屬於該 context 凍結持股清單；caller 可選其中子集，回應與 coverage 只涵蓋該次明確要求的代碼，不可省略缺資料標的。每次請求讀取呼叫當下已存在的快取／business read 狀態；該資料修訂可晚於 calculation-context，因此以獨立 `marketCaptureId` 和市場來源修訂向量批次固定，不改寫計算 context 的向量。同一 market capture 內逐檔來源需一致。每次後續請求仍須由 configured-admin／active-account selector 解析 owner 並做 owner-scoped 查詢。

僅 `ASSET_RECONCILIATION`、`ALLOCATION_GAP`、`CASH_INCOME` 可以提供既有公式結果。`FUNDING_CAPACITY`、`COMPLETED_TECHNICALS` 與後續階段 `SYMBOL_RULE_FACTS` 不可冒稱已完成；依契約逐項回 `UNAVAILABLE` 和原因碼。不得修改 SRPP 程式、政策或排程。依使用者後續明確要求，資產管理端 OpenAPI 定稿後必須執行既有產生器，同步產生本專案 Markdown 與 `/Users/steven/Project/SRPP/docs/9090 Port API Swagger.md`；此要求覆蓋附件所載不更新 Swagger 鏡像的限制。

所有端點嚴格檢查 query key、重複值、GET body、格式與清單限制；回應 `Cache-Control: private, no-store`，不提供 304。跨 owner 與不存在 context 同回 404；過期或無法驗證的來源回契約指定 409／503。市場事實逐標的降級且不得將缺漏轉為零，批次產生可重現的 capture ID/hash。

## 驗收

- 9090 nginx、Tailscale Serve path 清單、BFF、business internal endpoints、安全規則、route catalog、OpenAPI、本專案產生文件與指定 SRPP Swagger mirror 均包含三條路由，path/method 集合機械一致；SRPP mirror 僅由既有 renderer 產生。
- Tailscale Serve 修改前接受空設定或由預期 exact proxy handlers 組成的子集（包含既有 14 路）；任何非本任務 handler 都須拒絕 reset。preflight 後仍須重讀及比較設定，套用後必須精確驗證完整 17 路。
- `coverage` 是嚴格物件，含 `status`（`COMPLETE` 或 `PARTIAL`）、`requestedCount`、`successCount`、`partialCount`、`unavailableCount`；以回應內逐 calculation／逐 symbol 最終 status 計數，所有 count 皆為非負整數且 `successCount + partialCount + unavailableCount = requestedCount`。計算端點須回傳 query 所要求的全部 calculation ID（含明確 `UNAVAILABLE` 項目）；市場端點按該次 query 要求且屬於 context 凍結持股的代碼計數，缺資料不可省略標的。
- 須同步更新 `CLAUDE.md`、`spec/steering/structure.md`、`spec/steering/tech.md` 與現行 `spec/design.md` 的 9090 exact-route manifest 和數量（17 條：16 GET、1 POST），保留 exact-path、Tailscale、前端拒絕變體及 OpenAPI 機械一致限制；明確以 `stockCodes` 選取凍結持股子集。
- 兩個 API response 的 `coverage` 均包含 `status`、`requestedCount`、`successCount`、`partialCount`、`unavailableCount`，每項以其最終 status 分類且 `successCount + partialCount + unavailableCount = requestedCount`；market-facts 僅能接受必填 `stockCodes` 子集，且所有代碼須存在 context 凍結持股清單，逐項結果與 coverage 僅計該次要求的全部代碼。
- BFF 僅負責嚴格 query／owner lookup／business relay 與 payload validation；公式只由既有 business 計算器執行，資料只經 owner-scoped immutable evidence 或 canonical readonly market service 取得。
- 不支援或不完整計算逐項明確回 `UNAVAILABLE`；計算回應重現 context 的固定 source vector，market-facts 回應另回該 market capture 的來源修訂向量，向量逐來源保留 revision、`dataAsOf` 與 body hash；逐項 source ID 必須對應向量 source ID，逐項保留實際使用來源 ID 與 `dataAsOf`。計算與市場回應依 RFC 8785 JCS 計算內容 SHA-256。
- 契約驗證涵蓋成功、部分結果、未知/重複 query、GET body、path 變體、owner 隔離、未知 policy、stale/缺失 evidence、market per-symbol failure、no-store 與不觸發 refresh。
- 不宣稱與 SRPP reference calculator parity 已通過，除非另有逐欄與 adversarial shadow 證據；不接入或修改 SRPP adapter。
- 啟動資產管理 Docker stack 後確認服務綁定 9090 並對三條新 exact routes 回應。任何需要執行的資料庫 migration 須先呈現具體結果並取得使用者確認。不得修改 SRPP 程式、adapter、policy、排程或其他檔案；唯一允許的 SRPP repository 寫入是使用者指定的 Swagger mirror。

## 任務切片

- [ ] 467.1 business 端新增 owner-scoped immutable context lookup、evidence 重播計算與單一市場批次讀取。
- [ ] 467.2 BFF 新增 exact GET controller、嚴格 query parser、configured-admin/active owner selector、relay validator 及錯誤映射。
- [ ] 467.3 更新 exact route gateway/Tailscale contract、security allowlist、API route catalog、OpenAPI 3.1 與本專案及 SRPP Swagger Markdown 鏡像（依使用者明確要求）。
- [ ] 467.4 新增契約、隔離、來源固定、partial/error 與 no-side-effect 驗收案例。
- [ ] 467.5 完成 spec 與架構獨立審查、啟動 9090 stack 並記錄實際路由驗收；禁止修改 SRPP repository 的程式、adapter、policy、排程或其他檔案，唯一例外是由 renderer 產生使用者指定的 `docs/9090 Port API Swagger.md`。
- [ ] 467.6 **Immutable API error catalog rows。** 新增 Liquibase `v1.140.0-srpp-llm-orchestrated-error-log-catalog.sql`，insert-only seed：`OPEN_API`／`OPEN_SRPP_CALCULATION_CONTEXT`／`SRPP 計算脈絡`／150、`OPEN_SRPP_CALCULATIONS`／`SRPP 按需計算`／160、`OPEN_SRPP_MARKET_FACTS`／`SRPP 批次市場事實`／170。必須只用 `ON CONFLICT (source, operation_key) DO NOTHING`，不得 UPDATE／DELETE，且 `api_name` 精確等於 immutable catalog 的 `operation_label`，以滿足 `api_error_log(source, operation_key, api_name)` composite FK。將 changeset include 在 master changelog 末端；用 PostgreSQL migration test 兩次執行並讀回三筆 exact row，證明 immutable trigger 拒絕 update/delete；同步 Java catalog contract test 的完整 row count/order。此為純資料 seed，不新增 schema 欄位或改 `db/schema.sql` 結構。
