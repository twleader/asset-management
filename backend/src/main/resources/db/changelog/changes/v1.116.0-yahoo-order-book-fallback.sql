--liquibase formatted sql
--changeset steven:v1.116.0-yahoo-order-book-fallback
-- Invalid legacy partial books cannot meet the canonical full-five contract and are safely discarded.
DELETE FROM stock_intraday_order_book h
WHERE (SELECT count(*) FROM stock_intraday_order_book_level l
       WHERE l.stock_code=h.stock_code AND l.market=h.market) <> 5
   OR EXISTS (
       SELECT 1 FROM stock_intraday_order_book_level l
       WHERE l.stock_code=h.stock_code AND l.market=h.market
         AND (l.bid_price IS NULL OR l.bid_price <= 0 OR l.bid_volume_lots IS NULL OR l.bid_volume_lots <= 0
              OR l.ask_price IS NULL OR l.ask_price <= 0 OR l.ask_volume_lots IS NULL OR l.ask_volume_lots <= 0)
   )
   OR EXISTS (
       SELECT 1
       FROM stock_intraday_order_book_level nearer
       JOIN stock_intraday_order_book_level farther
         ON farther.stock_code=nearer.stock_code AND farther.market=nearer.market
        AND farther.level > nearer.level
       WHERE nearer.stock_code=h.stock_code AND nearer.market=h.market
         AND (nearer.bid_price <= farther.bid_price OR nearer.ask_price >= farther.ask_price)
   );

ALTER TABLE stock_intraday_order_book
    ADD COLUMN IF NOT EXISTS canonical_revision BIGINT;

UPDATE stock_intraday_order_book
SET canonical_revision = 1
WHERE canonical_revision IS NULL OR canonical_revision <= 0;

ALTER TABLE stock_intraday_order_book
    ALTER COLUMN canonical_revision SET NOT NULL;

ALTER TABLE stock_intraday_order_book
    DROP CONSTRAINT IF EXISTS ck_stock_intraday_order_book_source;

ALTER TABLE stock_intraday_order_book
    ADD CONSTRAINT ck_stock_intraday_order_book_source
        CHECK (source IN ('FUBON_BOOKS', 'YAHOO_TW'));

ALTER TABLE stock_intraday_order_book
    DROP CONSTRAINT IF EXISTS ck_stock_intraday_order_book_canonical_revision;

ALTER TABLE stock_intraday_order_book
    ADD CONSTRAINT ck_stock_intraday_order_book_canonical_revision
        CHECK (canonical_revision > 0);

ALTER TABLE stock_intraday_order_book_level
    DROP CONSTRAINT IF EXISTS ck_stock_intraday_order_book_level_bid_pair;

ALTER TABLE stock_intraday_order_book_level
    DROP CONSTRAINT IF EXISTS ck_stock_intraday_order_book_level_ask_pair;

ALTER TABLE stock_intraday_order_book_level
    DROP CONSTRAINT IF EXISTS ck_stock_intraday_order_book_level_price;

ALTER TABLE stock_intraday_order_book_level
    DROP CONSTRAINT IF EXISTS ck_stock_intraday_order_book_level_lots;

ALTER TABLE stock_intraday_order_book_level
    ALTER COLUMN bid_price SET NOT NULL,
    ALTER COLUMN bid_volume_lots SET NOT NULL,
    ALTER COLUMN ask_price SET NOT NULL,
    ALTER COLUMN ask_volume_lots SET NOT NULL;

ALTER TABLE stock_intraday_order_book_level
    ADD CONSTRAINT ck_stock_intraday_order_book_level_price
        CHECK (bid_price > 0 AND ask_price > 0);

ALTER TABLE stock_intraday_order_book_level
    ADD CONSTRAINT ck_stock_intraday_order_book_level_lots
        CHECK (bid_volume_lots > 0 AND ask_volume_lots > 0);
