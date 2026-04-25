-- v1.9.3: 移除 realized_gain 中的衍生欄位
--          profit       = proceeds - investment_cost
--          profit_rate  = profit / investment_cost
--          兩者皆可由現有欄位即時計算，違反正規化原則。

ALTER TABLE realized_gain DROP COLUMN IF EXISTS profit;
ALTER TABLE realized_gain DROP COLUMN IF EXISTS profit_rate;
