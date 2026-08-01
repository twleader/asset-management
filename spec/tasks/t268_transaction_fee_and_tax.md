# [t268] 交易紀錄新增「手續費」與「證交稅」兩個欄位

**對應 Requirements:** Requirement 49（資產交易紀錄／手動買賣流水帳與 Excel 手動／每日排程匯出）
**前置任務:** 無（t237 建立的 `asset_transaction` 表與 `TransactionView.vue` 已在 main 上運行）
**Liquibase changeset:** `v1.84.0-asset-transaction-fee-tax.sql`

## 背景

使用者在「交易紀錄」頁記錄每一筆買賣，目前表單有：交易類型、資產類型、代號、資產名稱、市場、幣別、
交易日期、券商通路、數量、單價、成交金額、匯率（USD 時唯讀自動帶入）、備註。

**缺的是**：實際交易付出的**手續費**與**證交稅**無處可記。使用者只能把它們吸收進「成交金額」
（該欄既有定義即為「含手續費／交易稅後之實際交割金額」），事後無從得知某一筆到底付了多少費用、
一年下來的交易成本是多少。本任務把這兩筆金額拆成兩個獨立欄位，讓它們**看得見**。

### 這個任務刻意「只是加兩欄」——不重新定義成交金額

`asset_transaction` 目前有 **59 列**（實測 `SELECT count(*) FROM asset_transaction;` → 59，
其中 `transaction_type='買'` 39 列、`='賣'` 20 列，`count(DISTINCT owner_user_id)` ＝ 1）。
這 59 列的 `amount` 全都是依「含費用後之實際交割金額」語意輸入的。

因此本任務**不得**讓新欄位參與任何既有計算。具體見下方 268.1 的硬約束——這是本任務最容易做錯、
且錯了會靜默改寫使用者既有年度統計數字的一點。

## 要做什麼

### 268.0 硬約束（先讀完再動手；違反其中任一條即為做錯）

- **A. 不改 `amount` 語意。** `amount`（成交金額）仍是使用者輸入的實際交割金額，新欄位不回頭調整它。
- **B. 不改 `amountTwd` 公式。** 仍為 `"USD".equals(currency) && exchangeRate != null && amount != null
  ? amount.multiply(exchangeRate) : amount`。**不得**改成 `(amount ± fee ± tax) × rate`。
- **C. 不改年度彙總。** `YearSummaryResponse` 的 `totalBuyAmountTwd`／`totalSellAmountTwd` 仍為
  各筆 `amountTwd` 加總，**不得**扣除或加計 fee／tax，也**不得**新增第三、第四個彙總欄位。
- **D. 不做「淨額」衍生欄。** 不新增 `netAmount`／`totalCost` 之類由 `amount ± fee ± tax` 算出的欄位，
  前端也不得在表格或表單顯示這種即時算出的淨額。
- **E. 不回填既有 59 列。** changeset 只 `ADD COLUMN`，**不得**寫 `UPDATE ... SET fee = ...`。
  `amount` 與 `shares × price` 的差額混有手續費、交易稅與零股撮合價差三者，無法拆分，回推＝捏造。
- **F. 不給 DB DEFAULT。** 兩欄 nullable、**無 `DEFAULT 0`**。`NULL` ＝「這筆沒記費用」，
  `0` ＝「確實免收」，語意不同。給預設值等於替使用者宣稱既有 59 筆都免費。
- **G. `null` 與 `0` 在任何一層都不得互相轉換。** 前端空字串 → 送 `null`（不是 `0`）；
  後端收到 `0` → 存 `0`（不是 `null`）。明細表顯示：`null` → `-`，`0` → `$0`。
  **Excel 匯出的 `null` 是「空白格（BLANK cell）」而不是 `-`** ——`ExcelExportService.cell()` 會先
  `row.createCell(col)` 建出格子，再判 `value == null` 就 `return`，因此格子存在但無值；
  這是同一張 sheet 既有 nullable 欄（`數量`／`單價`／`匯率`）的一致行為，硬塞 `-` 會讓該欄
  變成文字型別。**不要為此改 `cell()`。**
- **H. 兩欄不依交易類型或市場設限。** **不得**做成「只有交易類型＝賣才顯示／才允許填證交稅」。
  本頁市場 tab 含**英股**，英國印花稅（Stamp Duty 0.5%）課在**買進**；綁死「賣才有稅」會使英股
  買進的稅無處可記。台股買進不課證交稅由使用者留空表達，不由程式強制。切換買↔賣時**不清空**兩欄。

### 交付清單（逐項打勾；每一項的完整規格與程式碼片段見下方同編號小節）

- [x] 268.1.1 新增 changeset `v1.84.0-asset-transaction-fee-tax.sql`（`fee`／`transaction_tax` 兩欄，
      `NUMERIC(15,2)` nullable、`ADD COLUMN IF NOT EXISTS`、無 DEFAULT、無 UPDATE 回填）
- [x] 268.1.2 `db.changelog-master.yaml` 檔尾註冊 v1.84.0
- [x] 268.2.1 `AssetTransaction` entity 新增 `fee`／`transactionTax` 兩欄（`precision=15, scale=2`；
      後者顯式 `@Column(name = "transaction_tax")`）
- [x] 268.3.1 `CreateAssetTransactionRequest` 於 `amount` 後插入兩個 `@PositiveOrZero BigDecimal` component
- [x] 268.3.2 `AssetTransactionResponse` 同位置插入兩個 `BigDecimal` component
- [x] 268.3.3 順手修正 `AssetTransactionDto` 檔頭 javadoc 中「即回 400」這句既有錯誤斷言（純註解）
- [x] 268.4.1 `createAssetTransaction` builder 補 `.fee(...)`／`.transactionTax(...)`
- [x] 268.4.2 `updateAssetTransaction` 補兩個 setter（全量取代語意：送 null 即清空）
- [x] 268.4.3 `toResponse` 於 `amount` 與 `exchangeRate` 之間插入兩個 getter，**`amountTwd` 計算與
      `getAssetTransactionsByYear` 彙總迴圈一字不動**
- [x] 268.5.1 `writeAssetTransactionsSheet()` headers 由 15 改 17 欄；資料列在 index 9、10 插入
      fee／transactionTax，**原本的 index 9–14（市場／幣別／券商通路／匯率／年度／備註）全部 +2
      變成 11–16**（現況資料列只到 index 14，沒有 15、16）
- [x] 268.5.2 該方法 javadoc「15 欄固定順序」改「17 欄固定順序」
- [x] 268.6.1 `txForm` 與 `resetForm` 的 `Object.assign` **兩處**都加 `feeStr`／`transactionTaxStr`
- [x] 268.6.2 `fieldMap` 加 `fee`／`transactionTax` 兩筆對應
- [x] 268.6.3 表單插入「手續費／證交稅」一列兩欄（無 `prop`、不進 `rules`、**不加任何 `v-if` 條件顯示**）
- [x] 268.6.4 `openEditDialog` 帶入既有值（null → 空字串）
- [x] 268.6.5 `submit` payload 加兩欄，**空字串送 `null` 不送 `0`**
- [x] 268.6.6 明細表在「台幣成交金額」後、「市場」前插入兩個 `el-table-column`（判 `!= null` 而非 truthy）
- [x] 268.6.7 確認年度彙總四個 computed 與 `computedAmountTwd` **未被更動**
- [x] 268.8.1 `AssetTransactionServiceTest`：補 6 個新測試 ＋ `req(...)` helper 補 `null, null`
      ＋ 檔頭 javadoc「覆蓋 CRUD、年度分組彙總、amountTwd 三情境」補上「手續費／證交稅純記錄欄」一項；
      既有 7 個測試方法斷言值不得更動
- [x] 268.8.2 `AssetTransactionExcelExportTest`：`EXPECTED_HEADERS` 改 17 個、方法名改 17 欄、
      **檔頭 javadoc「15 欄表頭順序正確」改「17 欄」**、
      `零交易時仍產出含表頭的合法檔()` 的 `getCell(14)` 改 `getCell(EXPECTED_HEADERS.length - 1)`、
      `tx(...)` helper 可帶 fee／tax、補新斷言（BLANK cell 判法見 268.8）

### 268.1 Liquibase changeset（新檔）

新增 `backend/src/main/resources/db/changelog/changes/v1.84.0-asset-transaction-fee-tax.sql`：

```sql
--liquibase formatted sql

--changeset steven:v1.84.0-asset-transaction-fee-tax
--comment Requirement 49（Task 268）：交易紀錄新增「手續費」fee 與「證交稅」transaction_tax 兩欄，皆為 NUMERIC(15,2) nullable、無 DEFAULT、不回填既有列。NULL 語意＝「這筆沒記費用」，0 語意＝「確實免收」，兩者不同故不給 DEFAULT 0。兩欄為純記錄欄，不參與 amountTwd 與年度彙總計算。ADD COLUMN IF NOT EXISTS 使本 changeset 冪等，重跑無害。
ALTER TABLE asset_transaction ADD COLUMN IF NOT EXISTS fee NUMERIC(15,2);
ALTER TABLE asset_transaction ADD COLUMN IF NOT EXISTS transaction_tax NUMERIC(15,2);
```

於 `backend/src/main/resources/db/changelog/db.changelog-master.yaml` **檔尾**（目前最後一筆是
`v1.82.0-etf-nav-pct-origin.sql`）追加：

```yaml
  - include:
      file: db/changelog/changes/v1.84.0-asset-transaction-fee-tax.sql
      relativeToChangelogFile: false
```

> **編號已查撞號，且已避讓過一次。** main 上 `db.changelog-master.yaml` 的檔尾是
> `v1.82.0-etf-nav-pct-origin`，所以下一號**看起來**是 v1.83.0——**但 v1.83.0 不能用**，三方爭用：
>
> 1. **已經在共用 DB 執行過了**（最強的一項）：`SELECT id, orderexecuted FROM databasechangelog
>    ORDER BY orderexecuted DESC LIMIT 3` 實測最新一筆是
>    `v1.83.0-radar-notification-rule-version`（`orderexecuted` ＝ 116，排在 v1.82.0 的 115 之後）。
>    全機只有一套 `asset-*` 容器、多個 worktree 並行推進，該 changeset 由別的分支套上、尚未 merge 進 main。
> 2. `.claude/worktrees/trading-radar-optimization-79e41c` 底下有對應的實體檔
>    `v1.83.0-radar-notification-rule-version.sql`（其 t264，untracked 進行中）。
> 3. 同一個 worktree 的 `t266_stock_fundamental_ingestion.md` 又另外宣告了
>    `v1.83.0-stock-fundamental.sql`。
>
> **這正是「main 的 changelog 尾端」與「運行中 DB」會分歧的實例**——只看前者就會挑到一個
> DB 裡已經有執行紀錄的版號，接著在本機重跑時撞上 checksum 或 `already exists`。
> 故本任務避讓至 **v1.84.0**——已實測 v1.84.0 在全部 18 個 worktree ＋ 2 個 sibling clone 的
> `db/changelog/changes/` 與任務檔宣告中皆未出現，`databasechangelog` 內亦無。
>
> **實作前請重跑一次確認**（別的 worktree 隨時可能再往上占）：
>
> ```bash
> find /Users/steven/Project -path "*/db/changelog/changes/v1.8[4-9]*" -o -path "*/spec/tasks/*.md" -newer /dev/null -exec grep -l 'v1\.84\.0' {} +
> ```
>
> **不要事後用 sed 批次改編號**——checksum 含註解，changeset 檔一旦已被 Liquibase 執行過，
> 改動 `db/changelog/` 下任何字元（含 `--comment`）都會讓 `ValidationFailed` 使
> business-services 進入 crash loop。避讓要在**寫檔之前**決定完。

### 268.2 Entity：`backend/src/main/java/com/steven/assets/model/AssetTransaction.java`

在既有 `amount` 欄之後、`exchangeRate` 欄之前插入兩欄：

```java
    /** 手續費（原幣，與 amount 同幣別；純記錄，不參與 amountTwd 與年度彙總計算。Task 268） */
    @Column(precision = 15, scale = 2)
    private BigDecimal fee;

    /** 證交稅（原幣，與 amount 同幣別；純記錄，不參與 amountTwd 與年度彙總計算。Task 268） */
    @Column(name = "transaction_tax", precision = 15, scale = 2)
    private BigDecimal transactionTax;
```

`@Column(name = "transaction_tax")` 必須顯式指定——本專案未設 naming strategy 覆寫時
Spring Boot 預設 `CamelCaseToUnderscoresNamingStrategy` 雖會推出同名，但顯式寫出可避免
日後改組態時靜默對不到欄。`fee` 為單字、不需 `name`（與既有 `market`／`currency` 一致）。

### 268.3 DTO：`backend/src/main/java/com/steven/assets/dto/AssetTransactionDto.java`

`CreateAssetTransactionRequest` 於 `amount` 之後、`exchangeRate` 之前插入：

```java
            @PositiveOrZero BigDecimal fee,
            @PositiveOrZero BigDecimal transactionTax,
```

`AssetTransactionResponse` 同位置插入（response 不加驗證註解）：

```java
            BigDecimal fee,
            BigDecimal transactionTax,
```

需 `import jakarta.validation.constraints.PositiveOrZero;`（`spring-boot-starter-validation` 已在
`backend/pom.xml` 內；`@PositiveOrZero` 在本專案為首次使用，既有只有 `@NotNull`／`@NotBlank`／
`@Pattern`／`@Email`）。`@PositiveOrZero` 對 `null` 一律通過（Bean Validation 語意），故選填不受影響；
負值在 controller 既有的 `@Valid`（`AssetTransactionController.create`／`update` 已標）階段被擋下、
**不會寫入資料庫**。

> **狀態碼是 500，不是 400——這是本服務的既有行為，不要在本任務「順手修掉」。**
> `GlobalExceptionHandler` 最後有一條 `@ExceptionHandler(Exception.class)` 回 500，它排在 Spring 內建的
> `DefaultHandlerExceptionResolver` 之前，會把 `@Valid @RequestBody` 失敗擲出的
> `MethodArgumentNotValidException` 一併吃掉。實測：對 `POST /api/asset-transactions` 送缺 `@NotNull`
> 欄位的 body → `HTTP 500`，detail 為 `Validation failed for argument [0] ... with 2 errors`。
> 不修的是**行為**：補 `@ExceptionHandler(MethodArgumentNotValidException.class)` 會一次改掉
> business-services 所有端點的錯誤碼，屬跨切面變更、驗收範圍遠大於本任務，須獨立成案。
> **本任務只要確認負值被擋下、沒有落 DB。**
>
> **但檔頭那句錯誤的註解要順手改掉。** `AssetTransactionDto` javadoc 現存
> 「讓漏填在 `@Valid` 即回 400，而非落 DB 觸發 `DataIntegrityViolationException` 回 500」——
> 前半是既有的錯誤斷言（實際就是 500）。本任務本來就要編輯這個檔（268.3 要在同一個 record 插兩個
> component），留一句已知為假的話給下一個讀者沒有道理；改註解是純文件、零行為影響，不受上面那條
> 「跨切面變更須獨立成案」的限制。改為：「讓漏填在 `@Valid` 階段即被擋下、不落 DB
> （實際狀態碼為 500 而非 400——`GlobalExceptionHandler` 的 `Exception` 兜底吃掉了
> `MethodArgumentNotValidException`，為本服務所有 `@Valid @RequestBody` 端點的共同行為）」。

**Response record 的參數順序即為呼叫端 `new AssetTransactionResponse(...)` 的位置參數順序**，
插入後務必同步改 `AssetTransactionService.toResponse()`（見 268.4），否則會是編譯期型別相同、
位置錯位的靜默 bug（`amount`／`fee`／`transactionTax` 三者皆為 `BigDecimal`）。

### 268.4 Service：`backend/src/main/java/com/steven/assets/service/AssetTransactionService.java`

三處各補兩行，**其餘邏輯一字不動**：

1. `createAssetTransaction`：builder 加 `.fee(req.fee())`、`.transactionTax(req.transactionTax())`
2. `updateAssetTransaction`：加 `tx.setFee(req.fee());`、`tx.setTransactionTax(req.transactionTax());`
   （**全量取代語意**：送 `null` 即清空該欄，與同表既有 `shares`／`price`／`notes` 一致）
3. `toResponse`：在 `tx.getAmount()` 之後、`tx.getExchangeRate()` 之前插入
   `tx.getFee(), tx.getTransactionTax(),`

**`toResponse` 開頭那段 `amountTwd` 計算與 `getAssetTransactionsByYear` 的彙總迴圈完全不動**
（硬約束 B、C）。

### 268.5 Excel 匯出：`backend/src/main/java/com/steven/assets/service/ExcelExportService.java`

`writeAssetTransactionsSheet()` 由 15 欄改為 **17 欄**，兩個新欄插在「台幣成交金額」（index 8）之後、
「市場」之前：

```java
        String[] headers = {"資產名稱","代號","交易類型","資產類型","交易日期","數量","單價","成交金額",
                "台幣成交金額","手續費","證交稅","市場","幣別","券商通路","匯率","年度","備註"};
```

資料列對應改為（index 9、10 為新欄，11 之後全部 +2）：

```java
            cell(row, 8, assetTxAmountTwd(tx), st.money);
            cell(row, 9, tx.getFee(), st.money);
            cell(row, 10, tx.getTransactionTax(), st.money);
            cell(row, 11, tx.getMarket(), null);
            cell(row, 12, tx.getCurrency(), null);
            cell(row, 13, tx.getChannel(), null);
            cell(row, 14, tx.getExchangeRate(), st.num4);
            cell(row, 15, tx.getYear(), null);
            cell(row, 16, tx.getNotes(), null);
```

`assetTxAmountTwd(tx)` 這個 private helper **不動**（硬約束 B）。
方法上方 javadoc 的「15 欄固定順序」須同步改為「17 欄固定順序」。
三個匯出入口（手動下載／run-now／背景排程）共用同一個 `buildAssetTransactionsWorkbook()`，
故只改這一處即三處同步生效，**不得**另外複製一份。

### 268.6 前端表單：`frontend/src/views/TransactionView.vue`

**(a) `txForm` 新增兩個字串欄**（本表單既有慣例：數值欄一律以 `Str` 結尾的字串持有，供自由輸入）：
在 `reactive({...})` 的 `sharesStr, priceStr, amountStr, exchangeRateStr` 之後加
`feeStr: '', transactionTaxStr: ''`。**`resetForm()` 內那份 `Object.assign` 也要同步加**，
否則關閉 dialog 後殘值會帶到下一筆。

**(b) `fieldMap` 新增對應**（供既有 `onBlurField` 格式化 helper 使用，不另立第二套）：

```js
  fee: 'feeStr',
  transactionTax: 'transactionTaxStr'
```

**(c) 表單版面**：在既有「單價／成交金額」那一列 `el-row` 之後、「匯率／台幣成交金額」那一列之前，
插入一列兩欄：

```vue
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="手續費">
              <el-input v-model="txForm.feeStr" :input-style="{ textAlign: 'right' }"
                @blur="onBlurField('fee', txForm.currency === 'USD' ? 2 : 0)" placeholder="選填" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="證交稅">
              <el-input v-model="txForm.transactionTaxStr" :input-style="{ textAlign: 'right' }"
                @blur="onBlurField('transactionTax', txForm.currency === 'USD' ? 2 : 0)" placeholder="選填" />
            </el-form-item>
          </el-col>
        </el-row>
```

小數位規則與「成交金額」**完全相同**（`currency === 'USD' ? 2 : 0`），不另立第二套。
兩欄皆**無 `prop`、不進 `rules`**（選填）。**不得**加 `v-if` 依交易類型或市場條件顯示（硬約束 H）。

**(d) `openEditDialog(row)`**：加兩行帶入既有值，`null` 一律帶空字串：

```js
  txForm.feeStr = row.fee != null ? fmtNum(row.fee, isUsd ? 2 : 0) : ''
  txForm.transactionTaxStr = row.transactionTax != null ? fmtNum(row.transactionTax, isUsd ? 2 : 0) : ''
```

（`isUsd` 為該函式內既有的區域變數，已在 `amountStr` 那行之前算好，直接沿用。）

**(e) `submit()` 的 payload**：**空字串一律送 `null`、不得送 `0`**（硬約束 G）。
沿用該函式內 `shares`／`price` 既有的同一種寫法：

```js
    const fee = String(txForm.feeStr || '').trim() ? parseNum(txForm.feeStr) : null
    const transactionTax = String(txForm.transactionTaxStr || '').trim() ? parseNum(txForm.transactionTaxStr) : null
```

payload 物件在 `amount` 之後加 `fee, transactionTax,`。

> 注意 `parseNum` 對無法解析的字串回 `0`（既有行為）。上面的三元式先以 `trim()` 判斷是否為空，
> 空字串走 `null` 分支、根本不呼叫 `parseNum`，故「留空 → 0」的錯誤不會發生。

**(f) 明細表格新增兩欄**：在既有「台幣成交金額」`el-table-column` 之後、「市場」之前插入：

```vue
        <el-table-column label="手續費" align="right" width="90">
          <template #default="{ row }">
            <span v-if="row.fee != null">{{ fmtCurrency(row.fee, row.currency) }}</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="證交稅" align="right" width="90">
          <template #default="{ row }">
            <span v-if="row.transactionTax != null">{{ fmtCurrency(row.transactionTax, row.currency) }}</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
```

`v-if="row.fee != null"` 而非 `v-if="row.fee"` ——後者會把 `0` 也顯示成 `-`，違反硬約束 G。
`fmtCurrency(v, currency)` 為該檔既有 helper（USD 2 位小數、其餘 0 位），沿用不另寫。

**(g) 年度彙總卡片（`totalBuyAmountTwd` 等四個 `computed`）與 `computedAmountTwd` 完全不動**
（硬約束 B、C、D）。

### 268.7 不需異動的檔案（列出以免實作者多改）

- `bff/.../transaction/TransactionBffController.java`：`create`／`update` 的 body 型別為
  `Map<String, Object>` passthrough，新欄位自動穿透，**不需改**。
- `frontend/src/api/index.js`：`transaction.create`／`update` 直接傳整包 payload，**不需改**。
- `AssetTransactionExportScheduleService.java`：排程走 `exportAssetTransactionsForOwner` →
  同一個 `buildAssetTransactionsWorkbook()`，**不需改**。
- `SchedulePublicBffController.JOBS`：本任務**未新增任何 `@Scheduled`**，排程總數維持 **46**，**不需改**。

### 268.8 測試

**(a) `backend/src/test/java/com/steven/assets/service/AssetTransactionServiceTest.java`** 新增：

- `建立時手續費與證交稅正確寫入()`：`CreateAssetTransactionRequest` 帶 `fee=20`、`transactionTax=81`，
  驗證存入的 entity 兩欄值正確、且 response 兩欄回得出來。
- `手續費與證交稅為null時不影響amountTwd()`：USD 交易 `amount=100`、`exchangeRate=32`、
  `fee=null`、`transactionTax=null` → `amountTwd` 仍為 `3200`。
- `手續費與證交稅不參與amountTwd計算()`：USD 交易 `amount=100`、`exchangeRate=32`、
  `fee=1`、`transactionTax=2` → `amountTwd` **仍為 `3200`**（不是 3104／3296 之類）。此測試即為
  硬約束 B 的回歸錨點。
- `手續費與證交稅不參與年度彙總()`：同年度兩筆 TWD 交易（買 `amount=1000, fee=20`、
  賣 `amount=2000, fee=30, transactionTax=6`），驗證 `totalBuyAmountTwd` ＝ `1000`、
  `totalSellAmountTwd` ＝ `2000`（**未扣費用**）。此測試即為硬約束 C 的回歸錨點。
- `更新時手續費送null即清空()`：既有列 `fee=20`，以 `fee=null` 的請求更新後 entity `fee` 為 `null`。
- `手續費為零與未填在response中可區分()`：一筆 `fee=BigDecimal.ZERO`、一筆 `fee=null`，
  驗證 response 分別為 `0` 與 `null`（硬約束 G 的回歸錨點）。

現有 7 個測試方法（`建立時ownerUserId正確寫入`／`更新載入後驗歸屬並更新欄位`／`刪除載入後驗歸屬`／
`amountTwd_USD幣別等於amount乘匯率`／`amountTwd_TWD幣別等於amount`／`amountTwd_USD但匯率為null時退回amount`／
`getAssetTransactionsByYear依年度分組並彙總買賣筆數與台幣金額`）**必須全部保持通過且不修改斷言值**——
若有任何一個需要改斷言，代表違反了硬約束。

> **但該檔的 private helper 一定要改，否則整包編不過。** 檔內
> `private static AssetTransactionDto.CreateAssetTransactionRequest req(String type, String currency,
> LocalDate date, BigDecimal amount, BigDecimal rate)` 是**位置參數**建構 record（目前 13 個參數，
> 結尾為 `..., amount, rate, "備註"`）。268.3 在 `amount` 之後插了兩個 component，故此處必須在
> `amount` 與 `rate` 之間補 `null, null`（變 15 個參數）。這是簽章對齊、**不是修改斷言值**，不違反上一段。
> 全樹只有兩處以位置參數建構這兩個 record：本 helper 與 `AssetTransactionService.toResponse()`（見 268.4），
> 兩處都要改。

**(b) `backend/src/test/java/com/steven/assets/service/AssetTransactionExcelExportTest.java`** 異動：

- 既有 `EXPECTED_HEADERS` 常數由 15 個字串改為 17 個（順序見 268.5）。
- 既有測試方法名 `匯出交易紀錄_sheet名與15欄表頭與列數正確()` 改為 `..._sheet名與17欄表頭與列數正確()`，
  其檔頭 javadoc 的「15 欄」同步改「17 欄」。
- 既有斷言「台幣成交金額欄（第 9 欄 index 8）：USD 列 = 100×32 = 3200」**保留不動**（index 8 未位移）。
- **另一個測試方法 `零交易時仍產出含表頭的合法檔()` 也硬編了 index，必須一併改**：該方法斷言
  `sheet.getRow(0).getCell(14).getStringCellValue()).isEqualTo("備註")`——15 欄時 index 14 是最後一格
  「備註」，改成 17 欄後 index 14 變成「匯率」，此斷言必定失敗。改為
  `getCell(EXPECTED_HEADERS.length - 1)`（＝16），日後再增欄就不會重蹈覆轍。
- **該檔的 `tx(...)` helper 也要擴充**：現簽章為
  `private static AssetTransaction tx(String type, String currency, LocalDate date, BigDecimal amount,
  BigDecimal rate)`，builder 內未設 `fee`／`transactionTax`，兩個既有測試都只透過它建資料。
  不擴充的話新斷言無資料可驗（fee 恆為 null）。擴充成可帶 `fee`／`transactionTax`（或在測試內
  直接以 `AssetTransaction.builder()` 另建「有值」與「不帶值」各一列），以同時覆蓋兩種情形。
- 新增斷言：手續費欄（index 9）與證交稅欄（index 10）**有值時**值正確；**未填（`null`）時該格為
  BLANK cell**。**注意不是 `getCell(9) == null`** ——`ExcelExportService.cell()` 是先
  `row.createCell(col)` 再判 `value == null` 才 `return`，格子本身已經被建出來，POI 預設的
  `RETURN_NULL_AND_BLANK` policy 會回傳那個 BLANK cell 而非 `null`（實測既有匯出檔的匯率欄
  在 `xl/worksheets/sheet1.xml` 中序列化為 `<c r="M2" s="0"/>`，元素存在）。正確寫法擇一：
  `assertThat(row.getCell(9).getCellType()).isEqualTo(CellType.BLANK)`（**需加
  `import org.apache.poi.ss.usermodel.CellType;`** ——該檔目前只 import 了 `Row`／`Sheet`／`Workbook`
  與 `XSSFWorkbook`），或
  `assertThat(row.getCell(9, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL)).isNull()`（`Row` 已 import，
  不需新增）。

**(c) 不新增 BFF 測試**——BFF 為 `Map` passthrough，無邏輯可測。

## 驗證

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

**從 worktree 跑 compose 前，先把主 repo 的 `.env` 複製進來**（`env_file` 相對 compose 檔解析，
`--env-file` 救不了）。本 worktree 的 `.env` 缺 `ADMIN_EMAIL`，而 `docker-compose.yml` 寫的是
`ADMIN_EMAIL: ${ADMIN_EMAIL:?…}`（必填），**任何** `docker compose` 子指令（含 `build`）都會在
變數插值階段就中止（實測 `docker compose config -q` 即報
`required variable ADMIN_EMAIL is missing a value`）：

```bash
cp /Users/steven/Project/asset-management/.env .env
```

```bash
docker compose -p asset-management build --no-cache business-services frontend
```

```bash
docker compose -p asset-management up -d --no-deps --force-recreate business-services frontend
```

重建 business-services 會換 IP，BFF 握著舊 IP 會回 500 且 Docker DNS TTL 600s 內不自癒，故必須：

```bash
docker compose -p asset-management restart bff
```

DB 欄位確實建立（兩欄存在、皆 nullable、無 default）：

```bash
docker exec asset-postgres psql -U assets -d assets -c "\d asset_transaction" | grep -E 'fee|transaction_tax'
```

既有 59 列未被回填（硬約束 E、F —— 兩個 count 都必須是 59）：

```bash
docker exec asset-postgres psql -U assets -d assets -c "SELECT count(*) AS total, count(*) FILTER (WHERE fee IS NULL) AS fee_null, count(*) FILTER (WHERE transaction_tax IS NULL) AS tax_null FROM asset_transaction;"
```

changeset 已套用：

```bash
docker exec asset-postgres psql -U assets -d assets -c "SELECT id, exectype FROM databasechangelog WHERE id = 'v1.84.0-asset-transaction-fee-tax';"
```

端到端（免 Google 登入，於 business 容器內以 X-User-* 標頭模擬租戶）。本機實測
`SELECT DISTINCT owner_user_id FROM asset_transaction;` ＝ **1**（唯一使用者，`tw.leader@gmail.com`），
故下面 curl 用 `X-User-Id: 1`；若你的環境不同，先跑這條確認：

```bash
docker exec asset-postgres psql -U assets -d assets -c "SELECT DISTINCT owner_user_id FROM asset_transaction;"
```

```bash
docker exec asset-business-services sh -c "curl -s -X POST http://localhost:8080/api/asset-transactions -H 'Content-Type: application/json' -H 'X-User-Id: 1' -H 'X-User-Role: ADMIN' -H 'X-User-Status: ACTIVE' -d '{\"transactionType\":\"賣\",\"assetType\":\"股票\",\"assetName\":\"測試t268\",\"tradeDate\":\"2026-08-01\",\"amount\":27000,\"currency\":\"TWD\",\"fee\":20,\"transactionTax\":0}'"
```

回應須含 `"fee":20`、`"transactionTax":0`（**關鍵是不為 `null`** ——證明明確輸入的 0 沒被正規化成 null）、
且 `"amountTwd":27000`（**未扣費用**，這是硬約束 B 的端到端驗證）。

> **小數位數不要當成判準。** create 的回應是 `toResponse(txRepo.save(tx))`，其中的 `BigDecimal` 直接來自
> 請求 JSON 反序列化、**不是從 DB 重讀**，故 scale 跟著請求走（送 `20` 就回 `20`）。重新 `GET
> /api/asset-transactions` 才會看到 DB `NUMERIC(15,2)` 的 `20.00`。兩者都正確，別誤判成 bug。

負值必須被擋下且**不落 DB**（`@PositiveOrZero`）。**注意實際狀態碼是 500 不是 400**——
`GlobalExceptionHandler` 的 `@ExceptionHandler(Exception.class)` 兜底吃掉了
`MethodArgumentNotValidException`，此為本服務所有 `@Valid @RequestBody` 端點的既有共同行為，
不在本任務範圍內修正（見 268.3 的方框）：

```bash
docker exec asset-business-services sh -c "curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8080/api/asset-transactions -H 'Content-Type: application/json' -H 'X-User-Id: 1' -H 'X-User-Role: ADMIN' -H 'X-User-Status: ACTIVE' -d '{\"transactionType\":\"買\",\"assetType\":\"股票\",\"assetName\":\"負值測試\",\"tradeDate\":\"2026-08-01\",\"amount\":1000,\"fee\":-1}'"
```

預期 `500`。真正的判準是**那筆沒有落 DB**（下面的 DELETE 應該只刪到 1 列＝「測試t268」那筆）：

驗完刪掉上面那筆測試資料（回傳應為 `DELETE 1`，若是 `DELETE 2` 代表負值那筆真的被寫進去了，
即 `@PositiveOrZero` 沒生效）：

```bash
docker exec asset-postgres psql -U assets -d assets -c "DELETE FROM asset_transaction WHERE asset_name IN ('測試t268','負值測試');"
```

Excel 匯出確實是 17 欄且順序正確（**要真的解出表頭字串，不能只看檔案大小**）：

```bash
docker exec asset-business-services sh -c "curl -s -H 'X-User-Id: 1' -H 'X-User-Role: ADMIN' -H 'X-User-Status: ACTIVE' http://localhost:8080/api/asset-transactions/export" | /usr/bin/python3 -c "import sys,io,zipfile,re;z=zipfile.ZipFile(io.BytesIO(sys.stdin.buffer.read()));print(re.findall(r'<t[^>]*>([^<]*)</t>',z.read('xl/sharedStrings.xml').decode())[:17])"
```

預期印出 17 個字串，依序為
`資產名稱／代號／交易類型／資產類型／交易日期／數量／單價／成交金額／台幣成交金額／手續費／證交稅／市場／幣別／券商通路／匯率／年度／備註`
（第 10、11 個為「手續費」「證交稅」）。

前端：開 `http://localhost/transactions` →「新增」，確認表單有「手續費」「證交稅」兩欄且
**交易類型切買／賣時兩欄都在**；填入後儲存，明細表對應兩欄顯示正確、未填的舊資料顯示 `-`；
年度彙總卡的「買入／賣出」金額與改動前**完全相同**。

## 完成報告

**完成日期：** 2026-08-01　**分支：** `claude/add-fee-tax-fields-5c0e69`

### 實際改動（9 檔：8 修改 ＋ 1 新增）

| 檔案 | 內容 |
|---|---|
| `backend/.../db/changelog/changes/v1.84.0-asset-transaction-fee-tax.sql` | **新增**。兩欄 `ADD COLUMN IF NOT EXISTS`，無 DEFAULT、無 UPDATE |
| `backend/.../db/changelog/db.changelog-master.yaml` | 檔尾註冊 v1.84.0 |
| `backend/.../model/AssetTransaction.java` | `fee`／`transactionTax` 兩欄（`precision=15, scale=2`） |
| `backend/.../dto/AssetTransactionDto.java` | 兩個 record 各插兩個 component；`@PositiveOrZero`；檔頭 javadoc「回 400」改為據實描述 |
| `backend/.../service/AssetTransactionService.java` | create／update／toResponse 各補兩行；`amountTwd` 與彙總迴圈零變更 |
| `backend/.../service/ExcelExportService.java` | `writeAssetTransactionsSheet()` 15 → 17 欄 |
| `frontend/src/views/TransactionView.vue` | 表單兩欄、明細表兩欄、`txForm`／`resetForm`／`fieldMap`／`openEditDialog`／`submit` |
| `backend/src/test/.../AssetTransactionServiceTest.java` | ＋6 測試、`req()`／`tx()` 改帶 cost 的 overload |
| `backend/src/test/.../AssetTransactionExcelExportTest.java` | 17 欄、＋1 測試、`getCell(14)` → `length-1` |

**BFF 與 `frontend/src/api/index.js` 如預期未動**（`Map<String,Object>` passthrough，新欄自動穿透）。

### 驗證輸出

- **單元測試**：`Tests run: 388, Failures: 0, Errors: 0` BUILD SUCCESS。
  `AssetTransactionServiceTest` 7 → **13**、`AssetTransactionExcelExportTest` 2 → **3**；既有斷言值全未更動。
- **部署**：`build --no-cache business-services frontend` → `force-recreate` → business healthy（15s）→ `restart bff`。
- **changeset**：`v1.84.0-asset-transaction-fee-tax` `EXECUTED`；`\d asset_transaction` 兩欄皆
  `numeric(15,2)`、nullable、無 default。
- **未回填**（硬約束 E／F）：`total=59, fee_null=59, tax_null=59`。
- **端到端 POST**（`fee:20, transactionTax:0`）回
  `{"amount":27000,"fee":20,"transactionTax":0,"amountTwd":27000}` —— `transactionTax` **為 0 不是 null**
  （硬約束 G）、`amountTwd` **未扣費用**（硬約束 B）。
- **負值**：`HTTP=500`（既有全域行為，見 268.3），且**未落 DB**——清理時 `DELETE 1`（只刪到正常那筆）。
- **Excel 17 欄**：實際解 `sharedStrings.xml` 得
  `['資產名稱','代號','交易類型','資產類型','交易日期','數量','單價','成交金額','台幣成交金額','手續費','證交稅','市場','幣別','券商通路','匯率','年度','備註']`。
- **前端非 stale**：`asset-frontend` 內 `TransactionView-*.js` 命中 `手續費`×2／`證交稅`×2／`transactionTaxStr`×8。
- **架構查證**：`arch-auditor` 回 **0 critical / 0 major / 0 minor**，已 `arch-review-pass.sh` 記錄。
  （該報告提到「DB 尚未套用 changeset」是它查證時點早於本次部署，實際已 EXECUTED。）

### 與原計畫的偏差

1. **changeset 版號 v1.83.0 → v1.84.0**。撰寫規格當下 v1.83.0 尚未被占用，第 2 輪 spec 審查時發現
   `trading-radar-optimization-79e41c` 已建立實體檔、且其 t266 也宣告同版號；第 3 輪再發現該 changeset
   **已在共用 DB 執行**（`orderexecuted` 116）。避讓在寫檔之前完成，未事後 sed 改已執行的 changeset。
2. **`req(...)`／`tx(...)` helper 改法**：任務檔寫「補 `null, null`」，實作改為保留原簽章、
   另加 `reqWithCost(...)`／`txWithCost(...)` overload，原 helper 委派過去。效果相同（既有 7 個測試
   一字未改）且新測試可讀性較好。
3. **瀏覽器 UI 目視未做**：閘道 `http://localhost:8080/transactions` 回 401（需 Google OAuth 登入），
   代為登入不在可執行範圍。前端已以「bundle 內含新符號 ＋ 後端端到端正確」間接驗證，
   **畫面目視留給使用者確認**。

### 尚未執行

- [ ] commit ＋ 兩段式 merge（`--no-ff` 進 main）＋ 依共用 stack 規則從 main 的 worktree 重建映像。
