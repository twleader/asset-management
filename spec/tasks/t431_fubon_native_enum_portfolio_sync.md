# [t431] 富邦 Python 原生列舉正規化恢復現股庫存同步

**對應 Requirements:** Requirement 149（富邦 SDK 的 `OrderType`／`BSAction` 原生列舉可安全正規化，現股庫存同步不再誤判缺欄位）
**前置任務:** t352、t400、t401（既有 Linux SDK、來源日期對帳與唯讀生命週期）
**Liquibase changeset:** 無；不得修改 schema、migration 或 `db/schema.sql`。

## 背景

2026-09-14 的受控唯讀診斷確認，富邦 Python SDK 2.2.9 的 18 筆 `inventories` 與 18 筆 `unrealized_gains_and_loses` 都具有 `order_type`，未實現損益也具有 `buy_sell`；它們是原生 `OrderType.Stock`／`BSAction.Buy` 物件，卻沒有可用 `.value` 或 `.name`。既有 `enum_text` 於每列回 `None`，使 `PortfolioService` 在 HMAC 或 DTO 建立之前 fail closed 為 `MISSING_ORDER_TYPE`，庫存和未實現損益完全無法保存。

富邦官方文件將這兩個欄位列為正式回應欄位，且現股、融資、融券、借券、當沖與空方有不同類別。修正必須辨認 SDK 的有限原生文字形式，不能用預設值把缺欄位或非現股資料寫入本機。

## 要做什麼

- [ ] **431.1 有限 enum helper。** 在 `fubon_broker_service.sdk_gateway.enum_text` 保留 `None`、`str`、`int`、可用 `.value`／`.name` 的既有預設投影；native textual fallback 必由 `PortfolioService` 明確 opt-in，所有既有成交、已實現損益、帳戶選擇與認證失效 caller 都維持不 opt-in。只有 opt-in 且既有路徑拿不到文字時，才接受完整匹配 `OrderType.<member>` 或 `BSAction.<member>` 的 `str(value)`；prefix 與 member 都是 ASCII identifier、恰一個 dot，回傳 member。其他 prefix、空白、多個 dot、空 member、任意 repr、`__str__` 例外或缺欄位一律回 `None`。不得將 `None` 或任何無法辨認值預設為 `Stock`／`Buy`，不得記錄 raw SDK object、帳號、key 或 token。
- [ ] **431.2 保持 portfolio fail-closed 對帳。** 不改 `PortfolioService` 的 source `date/account/branch_no/stock_no` 精確驗證、HMAC 時機、兩側 identity unique／set equality、數量守恆、空庫存證明、decimal gate 或 sanitized reason。只有正規化為 `Stock` 與 `Buy` 的 matched row 可形成 position；`Margin`、`Short`、`DayTrade`、`SBL`、`Sell`、未知／缺值／不合法 enum 或任何 pairing mismatch 仍整批零 position／零 writer commit。
- [ ] **431.3 Focused Python tests。** 為 native-like enum fixture（沒有 `.value`／`.name`，字串化為 `OrderType.Stock`／`BSAction.Buy`）增加直接 helper 與 portfolio success test；直接測試必覆蓋既有 `str`／`int`／`.value`／`.name`、未 opt-in native text、opt-in success、malformed、unknown prefix、空白、空 member、多 dot 與 `__str__` 例外。測試必證明 normalized output 仍無 raw account／branch；另以 `Margin`、`Sell`、缺 `order_type` 及兩側不相等 fixtures 證明維持 reject。不得使用真人帳號、秘密或 raw SDK payload。
- [ ] **431.4 服務驗收與授權的本機回補。** 只重建／recreate `fubon-broker-service`，確認健康、source image 和 token-protected `POST /internal/portfolio/read` 可回 normalized positions、不是 `MISSING_ORDER_TYPE`。使用者已在本任務授權同步自己的資料時，才可由已 healthy 的 business service 呼叫既有 internal manual inventory sync `dryRun=false`；此操作保留完整既有唯讀流程：validated portfolio pair、交易日 gate、inventory-purpose TW quote read／新鮮度驗證後才可進入本機 DB writer。任一 gate 失敗都維持零寫入並回既有 typed reason，嚴禁任何券商寫入。成功後以唯讀 DB 查詢確認 configured active admin 今日快照的富邦台股 rows、2885 股數與 aggregate 一起更新；若 sync 失敗，保留原資料並回報 typed reason。歷史成交仍只使用 `sdk.stock.filled_history`，其 API-Key 權限不會被本任務繞過或模擬。

## 驗證

```bash
bash scripts/spec-check.sh
docker buildx build --platform linux/amd64 --target test \
  -f fubon-broker-service/Dockerfile fubon-broker-service \
  --load -t asset-fubon-broker-service:test
docker run --rm --platform linux/amd64 asset-fubon-broker-service:test \
  pytest -q tests/test_portfolio.py tests/test_sdk_gateway.py tests/test_app_routes.py

# 合併前由 feature worktree；合併後改由乾淨 main worktree 執行。
docker compose -p asset-management build fubon-broker-service
docker compose -p asset-management up -d --no-deps --force-recreate fubon-broker-service
docker inspect asset-fubon-broker-service --format '{{.State.Health.Status}}'
```

完成 runtime health 後，必先以現有 internal token 對 `POST /internal/portfolio/read` 做一次唯讀 normalized read；不得輸出帳號、token、原始 SDK 內容。只有本任務使用者授權仍有效時，才執行既有 business manual inventory sync `dryRun=false`；它仍會經過 pair、交易日與 inventory-purpose quote／新鮮度 gates，全部通過才有本機保存。以 PostgreSQL 唯讀查詢驗證 configured admin 最新今日快照的富邦台股與 aggregate；不得把 HTTP 200 或 broker health 當成 DB write 成功，也不得呼叫下單、改單、撤單、圈存、轉帳或匯款。

## 完成報告

（實作者做完後回填：修改檔、focused/full tests、image／container provenance、唯讀 adapter 驗證、授權本機保存的 DB readback，以及歷史成交權限的未解狀態。）
