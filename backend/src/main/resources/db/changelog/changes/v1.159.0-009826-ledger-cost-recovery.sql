--liquibase formatted sql
--changeset steven:v1.159.0-009826-ledger-cost-recovery splitStatements:false
--comment: Task 532: narrowly recover the verified 2026-11-02 manual buy; no inventory-price or settlement-cost inference
DO $$
DECLARE
    target_snapshot_id bigint;
    target_snapshot_date date;
    target_holding_id bigint;
    target_cost numeric;
BEGIN
    -- Same first row lock as normal snapshot mutation; concurrent inventory replacement waits.
    SELECT id, snapshot_date INTO target_snapshot_id, target_snapshot_date
    FROM asset_snapshot WHERE owner_user_id = 1
    ORDER BY snapshot_date DESC, id DESC LIMIT 1 FOR UPDATE;
    IF target_snapshot_id IS NULL OR target_snapshot_date <> DATE '2026-11-02' THEN RETURN; END IF;

    IF (SELECT count(*) FROM stock_holding
        WHERE snapshot_id = target_snapshot_id AND stock_code = '009826'
          AND market = '台股' AND currency = 'TWD') <> 1 THEN RETURN; END IF;

    SELECT h.id INTO target_holding_id
    FROM stock_holding h JOIN broker b ON b.id = h.broker_id
    WHERE h.snapshot_id = target_snapshot_id AND h.stock_code = '009826'
      AND h.market = '台股' AND h.currency = 'TWD' AND h.shares = 1000
      AND h.investment_cost = 0 AND b.code = 'fubon' AND b.active = true;
    IF target_holding_id IS NULL THEN RETURN; END IF;

    IF (SELECT count(*) FROM asset_transaction
        WHERE owner_user_id = 1 AND asset_type = '股票' AND asset_code = '009826'
          AND market = '台股' AND currency = 'TWD' AND trade_date <= target_snapshot_date) <> 1
       OR EXISTS (SELECT 1 FROM broker_filled_trade_cost_projection
                  WHERE owner_user_id = 1 AND stock_code = '009826') THEN RETURN; END IF;

    SELECT greatest(amount, shares * price + fee + transaction_tax) INTO target_cost
    FROM asset_transaction
    WHERE id = 208 AND owner_user_id = 1 AND transaction_type = '買' AND asset_type = '股票'
      AND asset_code = '009826' AND market = '台股' AND currency = 'TWD'
      AND channel = '富邦證券' AND source = 'MANUAL' AND trade_date = DATE '2026-11-02'
      AND shares = 1000 AND price = 10.08 AND amount = 10080 AND fee = 20 AND transaction_tax = 0;
    IF target_cost IS NULL OR target_cost <> 10100 THEN RETURN; END IF;

    UPDATE stock_holding SET investment_cost = target_cost WHERE id = target_holding_id;
    -- Deliberately a separate statement: see the updated holding in this transaction, not a CTE snapshot.
    UPDATE asset_snapshot s SET total_stock_cost = coalesce((
        SELECT sum(CASE WHEN h.currency = 'USD'
            THEN round(coalesce(h.investment_cost, 0) * coalesce(h.transaction_exchange_rate, s.usd_exchange_rate, 1), 0)
            ELSE round(coalesce(h.investment_cost, 0), 0) END)
        FROM stock_holding h WHERE h.snapshot_id = s.id), 0)
    WHERE s.id = target_snapshot_id;
END;
$$;
