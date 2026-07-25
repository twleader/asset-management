# [t239] 交易紀錄「單價」支援並顯示小數 6 位

**對應 Requirements:** Requirement 49（資產交易紀錄：成交單價精度提升至小數 6 位，於輸入、儲存、明細顯示、Excel 匯出一致）
**前置任務:** t237（交易紀錄 entity／CRUD／匯出；本任務調整其 `price` 精度與顯示）
**Liquibase changeset:** v1.74.0-asset-transaction-price-scale.sql

## 背景

使用者反映交易紀錄的「單價」需支援小數 **6 位**。目前 `asset_transaction.price` 為 `NUMERIC(15,4)`（僅 4 位小數），前端明細表以 `fmtCurrency`（台幣 0 位、美元 2 位）顯示（畫面呈現如「$72」），表單輸入 blur 格式化為 4 位，Excel 匯出用 4 位格式——四處都低於 6 位，直接改顯示會與 4 位儲存不一致（輸入 72.123456 會被截成 72.1235）。故須在**儲存、輸入、明細顯示、Excel** 四處一致改為 6 位小數。

僅針對「單價（price）」。「數量（shares）」維持 `NUMERIC(15,5)`、「成交金額（amount）」維持 `NUMERIC(20,2)`、「台幣成交金額」維持整數顯示，皆不動。

## 要做什麼

- [ ] 239.1 **DB 欄位加寬（Liquibase）**：新增 `backend/src/main/resources/db/changelog/changes/v1.74.0-asset-transaction-price-scale.sql`（`--liquibase formatted sql`，changeset id `steven:v1.74.0-asset-transaction-price-scale`），內容：
  ```sql
  ALTER TABLE asset_transaction ALTER COLUMN price TYPE NUMERIC(17,6);
  ```
  這是純加寬（11 位整數 + 6 位小數，較原 `(15,4)` 的 11 整數 + 4 小數只增小數位、不縮整數位），對既有列無截斷風險（表目前為空亦無資料風險）。在 `db.changelog-master.yaml` 於 `v1.73.0-...` 之後新增 include（`relativeToChangelogFile: false`）。**建檔前先 `bash scripts/spec-check.sh` 確認 v1.74.0 未被佔號**；被佔則改下一版號並同步 changeset id／檔名／master include。
  > DB 現況以實機 introspection 為準（非照 changelog 推斷）：`asset_transaction` 是 dump 基準線（`db/init/01_dump.sql`）之後才新增的表，故不在 dump 內；其 `price` 目前欄型可由 `SELECT numeric_precision, numeric_scale FROM information_schema.columns WHERE table_name='asset_transaction' AND column_name='price'`（或 `\d asset_transaction`）確認為 **NUMERIC(15,4)**，本任務以增量 ALTER 加寬到 `(17,6)`。

- [ ] 239.2 **Entity 精度對齊**：`backend/src/main/java/com/steven/assets/model/AssetTransaction.java` 的 `price` 由 `@Column(precision = 15, scale = 4)` 改為 `@Column(precision = 17, scale = 6)`（與 DB 一致）。其餘欄位不動。

- [ ] 239.3 **Excel 匯出 6 位小數**：`backend/src/main/java/com/steven/assets/service/ExcelExportService.java`：
  - 在 `Styles` class 新增 `num6`（`CellStyle`，`DataFormat` 為 `"#,##0.000000"`），與既有 `num4`／`num2`／`money` 同段建立。
  - `writeAssetTransactionsSheet` 的「單價」欄（現為 `cell(row, 6, tx.getPrice(), st.num4)`）改用 `st.num6`。其餘欄（數量 num4、成交金額 money 等）不動。

- [ ] 239.4 **前端明細顯示 6 位小數**：`frontend/src/views/TransactionView.vue`：
  - 新增顯示 formatter `fmtPrice(v)`：`v==null → '-'`，否則 `` `$${Number(v).toLocaleString('zh-TW', { minimumFractionDigits: 6, maximumFractionDigits: 6 })}` ``（固定 6 位小數，含 `$` 前綴，保留千分位）。
  - 明細表「單價」欄由 `fmtCurrency(row.price, row.currency)` 改為 `fmtPrice(row.price)`。**不可改 `fmtCurrency` 本身**——它同時被「成交金額」欄（`fmtCurrency(row.amount, ...)`）使用，改它會誤動成交金額顯示。

- [ ] 239.5 **前端表單輸入 6 位**：`frontend/src/views/TransactionView.vue`：
  - 單價輸入的 blur 由 `@blur="onBlurField('price', 4)"` 改為 `onBlurField('price', 6)`。
  - `openEditDialog` 內回填 `txForm.priceStr = row.price != null ? fmtNum(row.price, 4) : ''` 的精度由 4 改為 6。
  - `submit` 送出走 `parseNum(txForm.priceStr)`（parseFloat，不丟精度），無須改；後端 `price` 為 `BigDecimal`，6 位小數可完整落庫。

## 驗證

```bash
cd /Users/steven/Project/asset-management/.claude/worktrees/sad-shamir-9f7960
bash scripts/spec-check.sh
git diff --check

JAVA_HOME=$(/usr/libexec/java_home -v 21) PATH="$JAVA_HOME/bin:$PATH" \
  /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
/Users/steven/.nvm/versions/node/v22.21.0/bin/node \
  /Users/steven/.nvm/versions/node/v22.21.0/bin/npm --prefix frontend run build

# 共用 stack：merge 後從 main 的 worktree 重建；後端有變更 → business-services 一律 --no-cache
cp /Users/steven/Project/asset-management/.env . 2>/dev/null || true
docker compose -p asset-management build --no-cache business-services frontend
docker compose -p asset-management up -d --no-deps --force-recreate business-services frontend
docker compose -p asset-management restart bff   # business 換 IP → bff 需重啟避免握舊 IP
curl -s http://localhost:8080/actuator/health

# migration 生效 + 欄型為 numeric(17,6)
docker exec asset-postgres psql -U assets -d assets -tAc \
  "SELECT numeric_precision, numeric_scale FROM information_schema.columns WHERE table_name='asset_transaction' AND column_name='price';"   # → 17|6

# 6 位小數 round-trip（帶 X-User header 免 OAuth）
docker exec asset-business-services sh -c "curl -s -X POST http://localhost:8080/api/asset-transactions \
  -H 'Content-Type: application/json' -H 'X-User-Id:1' -H 'X-User-Role:USER' -H 'X-User-Status:ACTIVE' \
  -d '{\"transactionType\":\"買\",\"assetType\":\"股票\",\"assetName\":\"測試\",\"market\":\"美股\",\"currency\":\"USD\",\"tradeDate\":\"2026-07-01\",\"price\":72.123456,\"amount\":100}'"
# 回應 price 應為 72.123456（未被截成 72.1235）；驗畢刪除該測試列並確認表歸零
```

前端於「資產管理 → 交易紀錄」新增一筆單價含 6 位小數（如 `72.123456`），確認明細「單價」顯示 `$72.123456`、Excel 匯出該欄為 6 位小數。驗證用測試列須刪除，勿留在實機。

## 完成報告

（實作者做完後回填：實際改檔、migration 與欄型驗證輸出、6 位 round-trip 證據、與計畫的偏差。）
