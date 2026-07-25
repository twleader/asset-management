# [t237] 資產交易紀錄（手動買賣流水帳）CRUD、手動 Excel 匯出與前端頁

**對應 Requirements:** Requirement 49（資產交易紀錄：手動逐筆記錄股票／基金買賣流水帳，依年度檢視，並可手動下載 Excel）
**前置任務:** 無（排程自動匯出另立 t238）
**Liquibase changeset:** v1.72.0-asset-transaction.sql

## 背景

「資產管理」下目前有「歷年資產管理」「已實現損益」「資產配置建議」三個頁面，缺一份**逐筆買賣流水帳**：使用者無處記錄「我在某日、以某價、經某券商，買進／賣出某股票或基金若干股」這種原始交易事件。

既有 `realized_gain`（已實現損益）只記「賣出且已結算之損益」，`asset_snapshot`（歷年資產快照）只記「某時點的資產存量」，兩者都不是流水帳。本任務新增獨立的 `asset_transaction` 表承載這份流水帳。

**定位（務必遵守，否則會踩正規化紅線）：** 交易紀錄是 flow event ledger，與 `realized_gain`（賣出結算）、`asset_snapshot`（時點存量）語意不同，三者**互不自動衍生、不共用資料表**。本任務**不得**讓交易紀錄自動產生／修改 `realized_gain`／`stock_holding`／`asset_snapshot`，也不得由它們反推——避免同一事實跨表存兩份。本任務範圍只有這份流水帳的 CRUD 與手動下載；排程自動匯出見 t238。

本專案為多租戶（Requirement 28）：交易紀錄為 per-user 私人資料，須以 `owner_user_id` ＋ `@Filter(ownerFilter)` 隔離，HTTP 情境由既有 `TenantFilterAspect` 自動 owner-scope 到當前使用者。

## 要做什麼

### 237.1 新增 entity `AssetTransaction`

`backend/src/main/java/com/steven/assets/model/AssetTransaction.java`：

- `@Entity @Table(name = "asset_transaction")`，加 `@Filter(name = "ownerFilter", condition = "owner_user_id = :ownerId")`（比照 `RealizedGain`）。
- Lombok `@Data @NoArgsConstructor @AllArgsConstructor @Builder`。
- 欄位（型別、精度、nullable 一律照此，不得改動）：
  - `id` Long，`@Id @GeneratedValue(strategy = GenerationType.IDENTITY)`
  - `ownerUserId` Long，`@Column(name = "owner_user_id", nullable = false)`
  - `transactionType` String，`@Column(nullable = false, length = 10)`（值：`買` / `賣`；必填，與 DDL 的 NOT NULL 對齊）
  - `assetType` String，`@Column(nullable = false, length = 10)`（值：`股票` / `基金`；必填，與 DDL 的 NOT NULL 對齊）
  - `assetName` String，`@Column(nullable = false, length = 50)`
  - `assetCode` String，`@Column(length = 20)`
  - `market` String，`@Column(length = 20)`
  - `currency` String，`@Column(length = 10)`（`TWD` / `USD`）
  - `channel` String，`@Column(length = 30)`（券商／通路，成交當下名稱字串）
  - `tradeDate` LocalDate，`@Column(nullable = false)`
  - `shares` BigDecimal，`@Column(precision = 15, scale = 5)`
  - `price` BigDecimal，`@Column(precision = 15, scale = 4)`
  - `amount` BigDecimal，`@Column(nullable = false, precision = 20, scale = 2)`
  - `exchangeRate` BigDecimal，`@Column(precision = 10, scale = 4)`
  - `notes` String，`@Column(length = 500)`
- `year` 為 `@Transient` getter：`return tradeDate != null ? tradeDate.getYear() : null;`（**不建 DB 欄位**）。
- **不得**新增 `profit`／`amountTwd` 之類的實體欄位——衍生值一律不入庫。

### 237.2 Liquibase changeset

`backend/src/main/resources/db/changelog/changes/v1.72.0-asset-transaction.sql`（`--liquibase formatted sql`，changeset id `steven:v1.72.0-asset-transaction`）建表：

```sql
CREATE TABLE IF NOT EXISTS asset_transaction (
    id               BIGSERIAL PRIMARY KEY,
    owner_user_id    BIGINT       NOT NULL,
    transaction_type VARCHAR(10)  NOT NULL,
    asset_type       VARCHAR(10)  NOT NULL,
    asset_name       VARCHAR(50)  NOT NULL,
    asset_code       VARCHAR(20),
    market           VARCHAR(20),
    currency         VARCHAR(10),
    channel          VARCHAR(30),
    trade_date       DATE         NOT NULL,
    shares           NUMERIC(15,5),
    price            NUMERIC(15,4),
    amount           NUMERIC(20,2) NOT NULL,
    exchange_rate    NUMERIC(10,4),
    notes            VARCHAR(500)
);
CREATE INDEX IF NOT EXISTS idx_asset_transaction_owner_date
    ON asset_transaction (owner_user_id, trade_date DESC);
```

- 用 `CREATE TABLE IF NOT EXISTS`（冪等，容忍多 worktree 並行已建）。表內無 seed。
- 在 `backend/src/main/resources/db/changelog/db.changelog-master.yaml` 末端（現最後一筆為 `v1.71.0-configurable-admin-email.sql`）新增 include：
  ```yaml
    - include:
        file: db/changelog/changes/v1.72.0-asset-transaction.sql
        relativeToChangelogFile: false
  ```
- **注意**：本專案 schema 基準線是 `db/init/01_dump.sql`（pg_dump），Liquibase 只做增量；`asset_transaction` 為全新表、dump 未含，故一般建表路徑即可。**建檔前先跑 `bash scripts/spec-check.sh` 確認 v1.72.0 未被其他 worktree 佔號**；若已被佔，改用下一個可用版號並同步改 changeset id、檔名與 master include。

### 237.3 Repository

`backend/src/main/java/com/steven/assets/repository/AssetTransactionRepository.java`：`extends JpaRepository<AssetTransaction, Long>`。

- `List<AssetTransaction> findAllByOrderByTradeDateDesc();`（列表與匯出用，owner 由 `@Filter` 自動縮）。
- 年度篩選比照已實現損益頁：由前端自 `getAssetTransactionsByYear()` 回的各年度 `records` 於客戶端切換，repository 不另設日期區間查詢方法（避免未被 controller 曝露的死碼）。若日後需伺服端年度端點，再以 `tradeDate` 區間查（**不得**用已不存在的 `year` 欄位查）。

### 237.4 DTO

`backend/src/main/java/com/steven/assets/dto/AssetTransactionDto.java`（比照 `RealizedGainDto` 的 record 風格）：

- `CreateAssetTransactionRequest`（新增／編輯共用）：`@NotNull transactionType`、`@NotNull assetType`、`@NotNull assetName`、`assetCode`、`market`、`currency`、`channel`、`@NotNull tradeDate`（LocalDate）、`shares`、`price`、`@NotNull amount`（BigDecimal）、`exchangeRate`、`notes`。（`transactionType`／`assetType`／`assetName`／`tradeDate`／`amount` 五個必填欄一律加 `@NotNull`，與 entity `nullable = false` 及 DDL `NOT NULL` 三處對齊，讓漏填在 `@Valid` 即回 400，而非落 DB 觸發 `DataIntegrityViolationException` 回 500。）
- `AssetTransactionResponse`：上述欄位 ＋ `id` ＋ 衍生 `amountTwd`（`currency` 等於 `USD` 且 `exchangeRate` 非 null 時 ＝ `amount × exchangeRate`，否則 ＝ `amount`）＋ `year`（`tradeDate.getYear()`）。**衍生值於此即時計算，不入庫。**
- `YearSummaryResponse`：`year`、`buyCount`、`sellCount`、`totalBuyAmountTwd`、`totalSellAmountTwd`、`List<AssetTransactionResponse> records`（供頁面依年度彙總檢視；彙總在 service／BFF 算，前端不重算）。

### 237.5 Service

`backend/src/main/java/com/steven/assets/service/AssetTransactionService.java`：

- `createAssetTransaction(CreateAssetTransactionRequest)`：以既有 `TenantGuard.requireCurrentUserId()` 取當前 owner 寫入 `ownerUserId`（**比照 `AssetService.createRealizedGain` 的既有寫法 `.ownerUserId(tenantGuard.requireCurrentUserId())`**，該 helper 為 fail-closed，未識別使用者即擲例外），回 `AssetTransactionResponse`。
- `updateAssetTransaction(Long id, CreateAssetTransactionRequest)`：`findById` 後更新欄位（`@Filter` 已限本人；查無回 404／擲既有 not-found 例外慣例）。
- `deleteAssetTransaction(Long id)`。
- `getAssetTransactionsByYear()`：讀 `findAllByOrderByTradeDateDesc()`，於記憶體 group by `year` 組 `YearSummaryResponse` 列表（依年度新到舊）。此為 GET 端點回傳的唯一形狀；前端由各年度 `records` 客戶端切換年度（比照已實現損益頁），故**不**另做伺服端 year 篩選方法，避免產生未被 controller 曝露的死碼。
- toResponse helper 統一算 `amountTwd`／`year`。
- **owner 取得**：一律走既有 `TenantGuard.requireCurrentUserId()`（其底層讀 request-scoped `CurrentUserContext`），不得自行從 header 解析。by-id 載入（update／delete）另以 `tenantGuard.assertOwned(entity.getOwnerUserId())` 驗歸屬（`findById` 不受 `@Filter` 約束，比照 `AssetService` 對 realized gain 的 `assertOwned` 慣例）。

### 237.6 Controller

`backend/src/main/java/com/steven/assets/controller/AssetTransactionController.java`，`@RestController @RequestMapping("/api/asset-transactions")`：

- `GET /` → 回 `List<YearSummaryResponse>` 年度彙總（依年度新到舊，比照 `RealizedGainController.getAll`，無 year 參數；年度篩選由前端客戶端做）。
- `POST /` → create。
- `PUT /{id}` → update。
- `DELETE /{id}` → `ResponseEntity.noContent()`。
- `GET /export` → 下載 xlsx，比照 `RealizedGainController.exportExcel`：
  ```java
  byte[] data = excelExportService.exportAssetTransactions();
  String filename = "交易紀錄_" + LocalDate.now() + ".xlsx";
  HttpHeaders headers = new HttpHeaders();
  headers.setContentDisposition(ContentDisposition.attachment()
          .filename(filename, StandardCharsets.UTF_8).build());
  return ResponseEntity.ok().headers(headers)
          .contentType(MediaType.parseMediaType(
              "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
          .contentLength(data.length).body(new ByteArrayResource(data));
  ```
- 建立時 `@Valid @RequestBody`。
- `GET /export` 回應加 `Cache-Control: no-store`（`ResponseEntity...cacheControl(CacheControl.noStore())`）：交易資料常變動，匯出檔不得被瀏覽器快取，否則重複匯出會回舊檔（使用者症狀：改過的列匯出仍顯示舊值）。前端 `exportExcel` 另帶 cache-bust 參數 `_t` 雙重保險。

### 237.7 手動 Excel 匯出（`ExcelExportService`）

在既有 `backend/src/main/java/com/steven/assets/service/ExcelExportService.java` 新增（比照既有 `exportRealizedGains()`／`buildRealizedGainsWorkbook()`／`writeRealizedGainsSheet()` 三段式，並注入 `AssetTransactionRepository`）：

- `public byte[] exportAssetTransactions() throws IOException { return buildAssetTransactionsWorkbook(); }`（HTTP 情境，owner 由 `TenantFilterAspect` 自動縮；標 `@Transactional(readOnly = true)`）。
- `private byte[] buildAssetTransactionsWorkbook()`：`new XSSFWorkbook()` ＋ `Styles`，呼叫 `writeAssetTransactionsSheet(wb, st)`，`wb.write(out)` 回 bytes。
- `private void writeAssetTransactionsSheet(Workbook wb, Styles st)`：sheet 名「交易紀錄」，header 順序固定：
  `資產名稱／代號／交易類型／資產類型／交易日期／數量／單價／成交金額／台幣成交金額／市場／幣別／券商通路／匯率／年度／備註`。
  逐列以既有 `cell(row, col, value, style)` helper 寫入（`shares`／`price` 用 `st.num4`、`amount`／`amountTwd` 用 `st.money`、`exchangeRate` 用 `st.num4`、日期用既有 `ISO.format(...)`、`amountTwd` 依 237.4 規則即時算）。末端 `autoSizeColumn`。涵蓋**所有年度**，`gainRepo` 對應改用 `assetTxRepo.findAllByOrderByTradeDateDesc()`。
- **排程用的 `exportAssetTransactionsForOwner(Long)` 於 t238 新增**，本任務只做 HTTP 版本。

### 237.8 BFF（一頁一 BFF）

`bff/src/main/java/com/steven/assets/bff/transaction/`：

- `TransactionBffController.java`，`@RequestMapping("/api/bff/transaction")`：
  - `GET`：比照 `RealizedGainBffController`，以 `Mono.zip` 同時取交易紀錄年度彙總（business `GET /api/asset-transactions`）與下拉選單（既有市場 `GET /api/settings/market-types`、券商 `GET /api/settings/brokers`），回前端一包 `{ summaries, markets, brokers }`（brokers 過濾 active==true）。無 year 參數。
  - `POST` / `PUT /{id}` / `DELETE /{id}`：passthrough 至 business `/api/asset-transactions[/{id}]`。
  - `GET /export`：passthrough business `GET /api/asset-transactions/export`，回傳 blob（`Content-Disposition` 透傳，比照既有已實現損益匯出 passthrough）。
  - `GET /lookup-name`（`@RequestParam String code, @RequestParam String market`）：passthrough business `GET /api/stock-alerts/lookup-name`（輸入代號自動帶股名，**複用同一支 business API、不新增 business 端點**，逐字比照 `RealizedGainBffController.lookupName`——以 `uriBuilder.path(...).queryParam("code", code).queryParam("market", market).build()` 建構，自動編碼）。回 `{ stockName }`。
- `TransactionBffRoutes.java`：Spring Cloud Gateway route，`/api/asset-transactions/**` → business-services（純轉發；聚合端點走 controller）。比照 `RealizedGainBffRoutes`。
- BFF 呼叫 business 一律沿用既有把 `X-User-Id/Role/Status` 往下帶的機制（既有 filter），確保 business `TenantFilterAspect` 能 owner-scope。

### 237.9 前端 API 命名空間

`frontend/src/api/index.js` 新增 `transaction` 命名空間（比照既有 `realizedGain`）：

- `list()` → `GET /api/bff/transaction`（回 `{ summaries, markets, brokers }`；年度篩選前端做）
- `create(payload)` → `POST /api/bff/transaction`
- `update(id, payload)` → `PUT /api/bff/transaction/{id}`
- `remove(id)` → `DELETE /api/bff/transaction/{id}`
- `exportExcel()` → `GET /api/bff/transaction/export`（`responseType: 'blob'`）
- `lookupName(params)` → `GET /api/bff/transaction/lookup-name`（`params: { code, market }`；回 `{ stockName }`）

### 237.10 前端頁面 `TransactionView.vue`

`frontend/src/views/TransactionView.vue`（Vue 3 `<script setup>` ＋ Element Plus，比照 `RealizedGainView.vue` 的結構，但**不含**排程卡——排程卡於 t238 加）：

- 年度彙總卡片（`el-row`/`el-card`，點擊切換年度篩選；顯示每年度買／賣筆數與台幣買／賣金額）。
- 明細表格 `el-table`：欄位＝資產名稱／代號／交易類型／資產類型/交易日期／數量／單價／成交金額／台幣成交金額／市場／幣別／券商通路／備註／操作（編輯、刪除搭 `el-popconfirm`）。
- 明細表上方市場 tab（`el-tabs`，比照 `RealizedGainView`）：全部（name=""）／台股／美股／英股；`marketFilter` ref，`filteredRecords` 在年度篩選後再依 `r.market === marketFilter` 過濾（marketFilter 為空＝全部，不過濾）。
- 「匯出 Excel」按鈕：呼叫 `transaction.exportExcel()` 取 blob，以既有 anchor-click 下載慣例存檔（檔名由回應 header 帶）。
- 新增／編輯 dialog `el-form`：
  - 交易類型（`買`/`賣`）、資產類型（`股票`/`基金`）以 `el-select` 提供（選項為前端常數陣列，非後端 enum）。
  - **欄位順序：「代號」欄置於「資產名稱」欄之前**（交易標的通常為股票，先填代號較符合輸入習慣）。
  - **輸入代號自動帶出股名**：代號 `el-input` 綁 `@blur="autoFillAssetName"` `@change="autoFillAssetName"`（逐字比照 `RealizedGainView.vue` 的 `autoFillAssetName`）——`code` trim/大寫；`assetName` 已有值則不覆寫；**僅在 `assetType === '股票'`** 時呼叫 `bffApi.transaction.lookupName({ code, market: txForm.market })`，取 `res.stockName`，有值才填入 `assetName`；查無或例外靜默不覆寫（`resolveName` 找不到回空字串）。`market` 表單預設 `台股`，故常見台股情境輸入代號即帶名。
  - 市場、券商通路以 BFF 回傳的 `markets`／`brokers` 下拉；基金通路允許自由輸入（`el-select` 開 `allow-create` 或 `el-input`）。
  - 數字欄位（`shares`/`price`/`amount`/`exchangeRate`）沿用既有 string field ＋ `onBlur` 千分位格式化慣例（比照 `RealizedGainView` 的 `parseNum`/`fmtNum`）。
  - `assetName` 必填、`tradeDate` 必填、`amount` 必填（前端 `rules` ＋ 後端 `@NotNull` 雙重）。
  - 提供台幣成交金額即時預覽（`amount`／`exchangeRate`／`currency` 算，唯讀顯示）。
- 多 panel（彙總卡＋表格）若各自打 API，一律 `Promise.allSettled` 並行，不序列 await。

### 237.11 選單與路由

- `frontend/src/App.vue` 的 `mainMenuItems` computed：在 `index: 'asset-management'` 的 `children` 陣列（現有「歷年資產管理／已實現損益／資產配置建議」）加一項：
  ```js
  { path: '/transactions', title: '交易紀錄', icon: 'Tickets' }
  ```
- `frontend/src/router/index.js`：註冊路由（比照 `/realized-gains`）：
  ```js
  {
    path: '/transactions',
    name: 'Transactions',
    component: () => import('@/views/TransactionView.vue'),
    meta: { title: '交易紀錄', icon: 'Tickets' }
  }
  ```
- 兩處的 `path`／`title`／`icon` 必須一致（本專案無自動同步機制，需手動同時改兩處）。`Tickets` 為 Element Plus 既有 icon，若專案 icon 註冊方式需顯式 import，比照既有選單 icon 的註冊處理。

### 237.12 測試

- 新增後端 service／controller 層測試（比照既有已實現損益的測試風格與命名慣例，與實作同一支任務交付）至少覆蓋：
  - create/update/delete 正常路徑，`ownerUserId` 正確寫入。
  - `getAssetTransactionsByYear()` 依年度分組、買賣筆數與台幣金額彙總正確、年度新到舊。
  - `amountTwd` 衍生：`USD` 幣別 ＝ `amount × exchangeRate`；`TWD` 幣別 ＝ `amount`；`exchangeRate` 為 null 時退回 `amount`。
- Excel 匯出測試：`exportAssetTransactions()` 產出的 workbook sheet 名為「交易紀錄」、表頭 15 欄順序正確、資料列數 ＝ 交易筆數。
- 若測試用 Mockito 且在 Java 25 環境跑，記得 `-DargLine="-Dnet.bytebuddy.experimental=true"`（本機為 Java 21 則不需要，依實際 JDK）。

## 驗證

```bash
cd /Users/steven/Project/asset-management/.claude/worktrees/sad-shamir-9f7960
bash scripts/spec-check.sh
git diff --check

# 後端建置與測試（本機 JDK 21）
JAVA_HOME=$(/usr/libexec/java_home -v 21) PATH="$JAVA_HOME/bin:$PATH" \
  /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test

# 前端建置（確認 view/router/menu 無語法錯）
/Users/steven/.nvm/versions/node/v22.21.0/bin/node \
  /Users/steven/.nvm/versions/node/v22.21.0/bin/npm --prefix frontend run build

# 本專案無 dev server；「改好」＝ image rebuild + container recreate（見 .claude/skills/run-stack）
# 從主 repo（main 的 worktree）重建共用 stack，避免只在自己 worktree build 被其他 session 洗掉
cp /Users/steven/Project/asset-management/.env . 2>/dev/null || true
docker compose -p asset-management build --no-cache business-services bff frontend
docker compose -p asset-management up -d --no-deps --force-recreate business-services bff frontend
curl -s http://localhost:8080/actuator/health

# 端到端（免 OAuth，以 X-User-* header 模擬租戶；比照既有 e2e 慣例）
docker exec asset-business-services sh -c \
  "curl -s -X POST http://localhost:8080/api/asset-transactions \
     -H 'Content-Type: application/json' \
     -H 'X-User-Id: 1' -H 'X-User-Role: USER' -H 'X-User-Status: ACTIVE' \
     -d '{\"transactionType\":\"買\",\"assetType\":\"股票\",\"assetName\":\"台積電\",\"assetCode\":\"2330\",\"market\":\"台股\",\"currency\":\"TWD\",\"channel\":\"富邦\",\"tradeDate\":\"2026-07-01\",\"shares\":1000,\"price\":1000,\"amount\":1000000}'"
docker exec asset-business-services sh -c \
  "curl -s 'http://localhost:8080/api/asset-transactions' \
     -H 'X-User-Id: 1' -H 'X-User-Role: USER' -H 'X-User-Status: ACTIVE'"
# 另建 X-User-Id: 2 一筆，確認 user 1 的列表看不到 user 2 的交易（租戶隔離）
docker exec asset-business-services sh -c \
  "curl -s 'http://localhost:8080/api/asset-transactions/export' -o /tmp/tx.xlsx \
     -H 'X-User-Id: 1' -H 'X-User-Role: USER' -H 'X-User-Status: ACTIVE'; \
   file /tmp/tx.xlsx"   # 應為 Microsoft Excel 2007+
```

前端經 gateway 打開頁面（`http://localhost` 或既有前端埠），於「資產管理 → 交易紀錄」新增一筆買進、一筆賣出，確認列表、年度彙總、編輯、刪除、下載 Excel 皆正常，且下載檔內容涵蓋全部年度、15 欄順序正確。

## 完成報告

（實作者做完後回填：實際改了哪些檔、測試與 e2e 輸出、Excel 欄位截圖或 sheet dump、與原計畫的偏差及原因。）
