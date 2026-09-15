# [t436] 台股持股成本以完整交易帳本回填

**對應 Requirements:** Requirement 154（可完整對帳的台股 TWD 最新持股成本必須寫回資料庫）
**前置任務:** t237、t268、t385、t435；保留唯讀券商與交易帳本的既有邊界。
**Liquibase changesets:** `v1.131.0-ledger-backed-tw-stock-cost.sql` 與 `v1.134.0-ledger-stock-cost-aggregate-rounding.sql`（idempotent data correction），以及 `v1.132.0-broker-filled-trade-cost-projection.sql`（投影 idempotency table）；後者須重產 `db/schema.sql`。

## 背景

owner 1 的最新快照已有 `00865B` 8,000 股及 `00719B` 9,000 股；兩者 `stock_holding.investment_cost` 卻被存成當日 `current_value`，所以畫面損益固定為零。相同 owner 的 `asset_transaction` 已有完整買入帳本，數量精確相符。正確行為是把可證實的帳本成本回填到既有持股，而不是只在前端顯示另一套計算值，或以行情、富邦庫存、交割款補造交易。

## 要做什麼

- [ ] **436.1 嚴格的純成本計算器。** 新增可單元測試的 Java cost engine，輸入固定為一個台股 TWD identity 的排序交易列。只接受 `買`／`賣`、正 shares／price／amount、fee／transactionTax 為 null 或非負、精確 TWD 二位成本；null fee/tax 固定視為 `0`。按 `(tradeDate,id)` 運行 moving-average quantity/cost；買入成本為 `max(amount, shares×price+coalesce(fee,0)+coalesce(transactionTax,0))`。純 engine 的賣出可覆蓋未賣超的 historical math，但本 task 不得用它投影 broker sell，因現有 source 沒有可驗證 pre-trade basis。未知 type、shares/price/amount null或非正、fee/tax負、overflow、賣超或負成本必回不合格，絕不猜測 fallback。
- [ ] **436.2 限縮且可驗證的 historic correction。** `v1.131.0-ledger-backed-tw-stock-cost.sql` 只處理 latest snapshot 中 `market=台股`、`currency=TWD`、每個 `(code,market)` 恰一筆 holding，且 ledger 同 owner／股票／code／market／TWD／不晚於 snapshotDate、至少一筆且**全部 `買`**。SQL 必拒絕 shares/price/amount 的 null、零或負值，fee/tax 的負值，並以 PostgreSQL `numeric` 對每列 `GREATEST(amount, shares*price+coalesce(fee,0)+coalesce(transaction_tax,0))` 加總、最後 `round(…,2)`，先 gate `NUMERIC(20,2)`，並要求 `sum(shares)=holding.shares`；所有其他情況零寫入。v1.134 對同一候選以 `SnapshotAggregateCalculator` 同值規則重算 `total_stock_cost`：每筆 holding 個別換匯並 HALF_UP 至整數後再 sum。Testcontainers 以同一全買 fixture 比對 Java calculator、migration cost 和 aggregate，覆蓋 per-row rounding、idempotence 與 buy/sell sequence 必不回填。
- [ ] **436.3 新券商成交的可重放累加投影。** 使用者手動交易 CRUD 不得改動持股成本。新增 provider-neutral `BrokerFilledTradeCostProjector` 和 schema `broker_filled_trade_cost_projection`：每列必含 `owner_user_id NOT NULL`、`broker_id NOT NULL REFERENCES broker`、`broker_filled_no NOT NULL`、`asset_transaction_id NOT NULL REFERENCES asset_transaction UNIQUE`、`stock_code/market/currency/transaction_type/trade_date/shares/buy_cost NOT NULL`、`status NOT NULL CHECK (PENDING/APPLIED/SKIPPED_NO_PRETRADE_BASIS)`，且 `(owner_user_id, broker_id, broker_filled_no)` UNIQUE。首次 native insert 的券商成交在同 transaction 建立此 row；它是唯一的 ledger-to-projection identity 與 idempotency evidence。projector 鎖 owner latest snapshot 的唯一 `(broker_id, stock_code, market=台股, currency=TWD)` holding；只有買入才以 `max(amount, shares×price+coalesce(fee,0)+coalesce(transactionTax,0))` 增加成本並標 `APPLIED`，無或多筆 matching holding 的買入均保留 `PENDING`。現有 source 沒有可驗證 pre-trade quantity/basis，故所有賣出一律 `SKIPPED_NO_PRETRADE_BASIS`，不得由 post-trade shares 或 round 後成本猜算。duplicate fill 不投影；預期 skip 不回滾交易，真 DB failure 整個 transaction rollback。現階段僅 `FubonTradeWriter` 的 `FUBON_SYNC` native insert 回傳 `1` 後呼叫；未來國泰、元大只能各自使用既有買入 Java contract，並在其導入時另行擴充 source/provenance schema與可驗證賣出 basis，不得宣稱本次 FUBON-only schema 已可承載。
- [ ] **436.3a 庫存 replace 保留基準、消費 pending。** `FubonInventoryWriter` 與 436.3 必經同一 locked latest snapshot。舊同 broker／台股／code 唯一 row 必保留 `investmentCost`；沒有舊 row 的 first target 必以 `0.00` 建立，禁止採用富邦 `costPrice × shares`，接著只以同 owner、broker、code、market、currency 的 `PENDING` 買入投影依 `(trade_date, broker_filled_no)` 套用並標 `APPLIED`，最後才 aggregate。PostgreSQL race tests 必涵蓋 inventory-first、fill-first、兩 transaction lock contention，三者成本均恰好一次；另驗 first pending sell 固定 skip。
- [ ] **436.4 回填既有正確資料。** owner 1 的固定驗收資料（全買序列）須使 00865B 成本為 390,854.00、00719B 成本為 279,435.00；在 2026-09-16 現值分別 390,880.00、279,360.00 時，損益為 +26.00、-75.00。
- [ ] **436.4a 手動成本基準在 source-owned scope 仍可寫。** 實際 `SOURCE_OWNED` Fubon scope 的完整 snapshot PUT 必先拿既有 lock；每個 locked row 僅在 payload **恰一筆**精確命中 `(broker_id, stock_code, market=台股, currency=TWD)` candidate 時，才可更新合法 `investmentCost`（`NUMERIC(20,2)` 可儲存、非負、0 可用、scale≤2）。shares/currentValue/transaction fields/currency/dividend/broker/code/row membership 全部保留；零或重複 candidate、identity mismatch、null、負值、scale/precision overflow 都讓該 row 零 mutation而不 rollback PUT；更新後以既有 aggregate calculator 同 transaction 重算。PostgreSQL test 覆蓋 duplicate candidate 與每種非法成本。此 manual baseline 是後續買入 projection 的起點，無新 endpoint、DTO 或券商寫入。
- [ ] **436.5 回歸與 runtime 證據。** 單元測試覆蓋全買、買賣 moving-average、含／不含獨立費稅、amount 已含費稅、unknown type、賣超、quantity mismatch、負／null／overflow及 rounding；另覆蓋新富邦成交的 buy delta、所有 sell 固定 `SKIPPED_NO_PRETRADE_BASIS`、duplicate fill、`PENDING` buy、無／多筆 `(broker_id, stock_code, market, currency)` target 保留 pending、first pending sell skip 與 aggregate 同 transaction。PostgreSQL tests 覆蓋 all-buy migration/engine parity、idempotence、buy/sell no-backfill、multiple holding、different owner/market/currency/future row isolation，以及 inventory-first、fill-first、lock-race 的 exactly-once projection。`FubonTradeWriter` tests 覆蓋 native insert 1 才建立 projection、skip 不回滾交易、資料庫失敗不留下半個 aggregate。只重建 `business-services`；health 和 BFF recovery 後以唯讀 PostgreSQL readback 檢查上述兩筆成本、現值與損益。不得呼叫富邦 SDK、manual sync、internal broker POST、下單、改單、撤單、轉帳或變更 `.env`／secrets。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
bash scripts/tests/schema-sql-drift-test.sh
```

以 run-stack 只 rebuild/recreate `business-services`，等待 health 與 BFF safe GET recovery，再以 PostgreSQL 唯讀查詢 owner 1 最新快照的 00865B／00719B shares、investment_cost、current_value 與差額。不得用手寫 SQL 修正 production 值；只允許 Liquibase migration 與應用程式受限回填邏輯。

## 完成報告

（實作者完成後回填實際變更、驗證輸出與任何偏差。）
