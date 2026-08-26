# [t377] 9090 交易紀錄年度／日期區間唯讀查詢

**對應 Requirements:** Requirement 112（9090 configured-admin 交易紀錄 API，支援全部、年、日期區間，並完整回傳畫面明細與彙總）
**前置任務:** t376（共用 OpenAPI-to-Markdown generator 與 10-route manifest）
**Liquibase changeset:** 無

## 背景

目前交易紀錄頁只可經登入後的 `GET /api/bff/transaction` 取得年度群組與明細，資料源是 owner-scoped `asset_transaction` ledger。9090 尚無交易紀錄讀取 API，外部 consumer 不能用既有 gateway 查閱指定年份或日期區間；也不得以資產快照、券商庫存、結算或 Excel 反推出交易。

本任務要新增一個 configured-admin-only-by-selection、但在 loopback／Tailscale network boundary 內免應用層登入的 public read endpoint。它只回已保存 ledger，固定由 BFF configured-admin bootstrap 決定 owner；絕不授權 caller 代入 userId，也絕不做 broker sync、交易或檔案匯出。本任務獨立驗收時由 t376 的 10-route manifest 新增交易紀錄而成 **11** 條（10 GET、1 POST）；t378 隨後才使全 branch 到最終 12 條。

## 要做什麼

- [ ] 377.1 **新增精確 9090 route。** 新增 `GET /api/public/transactions`，operationId `getPublicTransactionHistory`；更新 api-gateway exact Nginx location、BFF exact GET permitAll、Tailscale Serve ownership/config/preflight/mock、frontend exact deny、OpenAPI path and route-parity test，以及 `CLAUDE.md`、`spec/steering/structure.md`、`spec/steering/tech.md`、`scripts/README.md`、`.agents/skills/run-stack/SKILL.md`、`INSTALLATION.md` 的 current count/path list，使本 task 的 manifest 精確為 11 routes（10 GET、1 POST）。不得新增 wildcard、dynamic path、public export、sync、schedule、root proxy、host port、Funnel、OAuth/API key 或 Swagger UI。非 GET 是 405 with `Allow: GET`；unknown／descendant／trailing slash／matrix 是 404。

- [ ] 377.2 **嚴格解析三種 filter mode。** endpoint 接受零或一組：無 query = `ALL`；`year=YYYY`（1900..9999、四位）= `[YYYY-01-01, YYYY-12-31]` inclusive；`start=YYYY-MM-DD&end=YYYY-MM-DD` = inclusive date range。`year` 不能與日期混用；`start`／`end` 必同時存在；空、multiple values、格式錯誤、year 範圍外或 `start > end` 必在 BFF local validation 直接回 sanitized 400，零 bootstrap／downstream request，不能把原字串回顯。空結果是 200 empty records/year summaries plus zero selected totals，不是 404。URI builder 必須正確 encode query；沒有 default today、截斷或隱藏 pagination。

- [ ] 377.3 **business typed read-only service。** 新增 container-only exact business current transaction-read endpoint `GET /internal/public-transaction-history/current?year=&start=&end=`；它不得被 `TransactionBffRoutes`（`/api/asset-transactions/**`）、public gateway 或 frontend route 直接轉送，也不得用 `/internal/**` wildcard 放行。controller 僅驗參數與委派。service `@Transactional(readOnly=true)` 只透過 tenant filter／`CurrentUserContext` 查 `asset_transaction`；新增 repository date range query（`tradeDate` desc、同日 `id` desc）或等價保證 stable sort。不得改既有 private `GET /api/asset-transactions` 的全年度群組/CRUD/Excel/export schedule 契約，也不得使用 create/update/delete/export/schedule、造成 DB／Redis／filesystem／Drive mutation。

- [ ] 377.4 **凍結回應 DTO。** 成功回 `PublicTransactionHistoryResponse(selection, allTimeSummary, summary, yearSummaries, records)`：
  - `selection` 是 typed `TransactionHistorySelection(mode=ALL|YEAR|DATE_RANGE, nullable year, nullable start, nullable end)`，反映已套用條件；
  - `allTimeSummary` 永遠統計 configured admin 所有 ledger 的 `buyCount`、`sellCount`、`totalBuyAmountTwd`、`totalSellAmountTwd`；
  - `summary` 為 filter 範圍的同四個 totals；`yearSummaries[]` 僅包含 filter 範圍、年份降冪，每列 `year` 加同四 totals；
  - `records[]` 含所有範圍內交易、stable date/id desc，元素 `PublicTransactionRecord` 精確含 `id`, `transactionType`, `assetType`, `assetName`, `assetCode`, `market`, `currency`, `channel`, `tradeDate`, `shares`, `price`, `amount`, `fee`, `transactionTax`, `exchangeRate`, `notes`, `amountTwd`, `year`。
  這是現有 `AssetTransactionResponse` 的**精確 18 個** ledger attributes，OpenAPI 與 regression tests 必須鎖定上述名稱與順序；所有 null／BigDecimal precision 維持現有 response；`amountTwd` 只使用 `USD + stored exchangeRate -> amount * exchangeRate`，其餘為 amount；fee/tax 是原幣純紀錄、完全不計入 amountTwd 或 totals。不可用 Map/object 取代 DTO，也不能輸出 ownerUserId、帳戶、broker credential、token、快照、持股、成本／損益推論、結算／庫存或任一從非 ledger 衍生的「成交」資料。

- [ ] 377.5 **configured-admin BFF boundary 和錯誤。** 專用 public controller 只 delegate 專用 service；service 先 `BusinessUserClient.configuredAdmin()`、驗證 configured and active，再用 bootstrap owner 的 explicit tenant headers 呼叫 business current-read，並 `contextWrite(ctx -> ctx.delete(AuthConstants.CTX_IDENTITY))`。caller cookie、ownerId、email、`X-User-*`／Tailscale identity 不能選 owner；BFF 不直查 DB。business non-2xx/decode mapping sanitized 502、bootstrap/transport mapping 503、timeout mapping 504；無 raw response body、SQL、URL、exception、帳戶或 broker text 透出。

- [ ] 377.6 **OpenAPI 和 SRPP 文件。** OpenAPI YAML 以 typed schemas 寫 query mode mutual exclusion、all/selected summary、every record property、nullable/type/format、read-only/configured-admin boundary 和 200/400/502/503/504；每個 operation/schema/property/array item/enum 都須具體 description。用 t375 建立、t376 重用的 YAML-to-Markdown generator 重產 `docs/openapi/9090-api-swagger.md` 和 `/Users/steven/Project/SRPP/docs/9090 Port API Swagger.md`，兩檔 bytes 必相同；`--check` 必失敗於任一檔或 OpenAPI docs mismatch。

- [ ] 377.7 **測試、重建、readback。** backend tests 覆蓋 owner scope、ALL/year/range、inclusive endpoints、multiple years、empty 200、date/id order、summary，USD conversion 和 fee/tax exclusion，以及 never write/sync/export；BFF tests 覆蓋 local 400 zero calls、configured admin headers／identity clear、encoded query、sanitized errors；gateway/Tailscale tests 含 route parity、405/404。跑 target Maven/OpenAPI/generator tests；使用 run-stack 從 main build/recreate business-services、bff、api-gateway。runtime 以 127.0.0.1 呼叫 no query、已存在 year 和合法 range，讀 response；在 calls 前後 readback `asset_transaction` 行數與最大 id／updated state，確認零 mutation。不得對 crawler rescan POST。

## 驗證

```bash
bash scripts/spec-check.sh
ruby scripts/tests/docker-external-api-openapi-test.rb
ruby scripts/render-9090-openapi-docs.rb --check
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -Dnet.bytebuddy.experimental=true -f backend/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -Dnet.bytebuddy.experimental=true -f bff/pom.xml test
docker compose -p asset-management build --no-cache business-services bff api-gateway
docker compose -p asset-management up -d --no-deps --force-recreate business-services bff api-gateway
curl -fsS 'http://127.0.0.1:9090/api/public/transactions?year=2026'
```

## 完成報告

（實作者做完後回填：實際改動、篩選／summary 測試、OpenAPI／兩份 Markdown check、Docker runtime readback 與 zero-mutation 證據。）
