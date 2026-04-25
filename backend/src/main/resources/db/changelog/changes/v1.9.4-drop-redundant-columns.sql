-- v1.9.4: 正規化重構 — 移除冗餘 / 衍生欄位
--   stock_name：違反「同一資料只存一份」原則，由 stock 主檔 (code+market) 提供
--   price_change / change_percent：可由 price - previous_close 即時計算
--   mid_rate：可由 (buy_rate + sell_rate) / 2 即時計算
--   trade_year：可由 YEAR(trade_date) 即時計算

ALTER TABLE stock_holding         DROP COLUMN IF EXISTS stock_name;
ALTER TABLE stock_alert           DROP COLUMN IF EXISTS stock_name;
ALTER TABLE watch_stock           DROP COLUMN IF EXISTS stock_name;
ALTER TABLE stock_price           DROP COLUMN IF EXISTS stock_name;
ALTER TABLE stock_price           DROP COLUMN IF EXISTS price_change;
ALTER TABLE stock_price           DROP COLUMN IF EXISTS change_percent;
ALTER TABLE exchange_rate_history DROP COLUMN IF EXISTS mid_rate;
ALTER TABLE realized_gain         DROP COLUMN IF EXISTS trade_year;
