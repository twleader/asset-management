--liquibase formatted sql
--changeset steven:v1.134.0-ledger-stock-cost-aggregate-rounding
--comment: Requirement 154: match SnapshotAggregateCalculator per-holding TWD rounding after v1.131
WITH latest AS (
  SELECT DISTINCT ON (owner_user_id) id, owner_user_id, snapshot_date
  FROM asset_snapshot ORDER BY owner_user_id, snapshot_date DESC, id DESC
), affected AS (
  SELECT sh.snapshot_id
  FROM stock_holding sh JOIN latest l ON l.id = sh.snapshot_id
  JOIN asset_transaction t ON t.owner_user_id = l.owner_user_id AND t.asset_type = '股票'
       AND t.asset_code = sh.stock_code AND t.market = sh.market AND t.currency = 'TWD'
       AND t.trade_date <= l.snapshot_date
  WHERE sh.market = '台股' AND sh.currency = 'TWD'
    AND NOT EXISTS (
      SELECT 1 FROM stock_holding duplicate_holding
      WHERE duplicate_holding.snapshot_id = sh.snapshot_id
        AND duplicate_holding.stock_code = sh.stock_code
        AND duplicate_holding.market = sh.market
        AND duplicate_holding.id <> sh.id
    )
  GROUP BY sh.id, sh.snapshot_id, sh.shares
  HAVING count(*) > 0 AND bool_and(t.transaction_type = '買')
     AND bool_and(t.shares > 0 AND t.price > 0 AND t.amount > 0
                  AND coalesce(t.fee, 0) >= 0 AND coalesce(t.transaction_tax, 0) >= 0)
     AND sum(t.shares) = sh.shares
     AND round(sum(greatest(t.amount, t.shares * t.price + coalesce(t.fee, 0) + coalesce(t.transaction_tax, 0))), 2)
         BETWEEN -999999999999999999.99 AND 999999999999999999.99
)
UPDATE asset_snapshot s SET total_stock_cost = coalesce((
  SELECT sum(CASE WHEN h.currency = 'USD'
                  THEN round(coalesce(h.investment_cost, 0) * coalesce(h.transaction_exchange_rate, s.usd_exchange_rate, 1), 0)
                  ELSE round(coalesce(h.investment_cost, 0), 0) END)
  FROM stock_holding h WHERE h.snapshot_id = s.id), 0)
WHERE s.id IN (SELECT DISTINCT snapshot_id FROM affected);
