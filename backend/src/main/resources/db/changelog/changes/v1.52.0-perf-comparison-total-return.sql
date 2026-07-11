--liquibase formatted sql

--changeset steven:v1.52.0-perf-comparison-total-return
--comment 績效比較頁（Requirement 33）：加權股價「報酬指數」（含息，TWSE 發行量加權股價報酬指數）逐日收盤。twse_index_daily_history 既有 close_point 為「發行量加權股價指數」（純價格），新增 close_point_tr 存同日報酬指數收盤，供含息 vs 純價格報酬率同圖比較；由 /api/twse-daily-index/refresh-tr 背景回補、每日排程增量更新。nullable：舊列與尚未回補的日期為 NULL。
ALTER TABLE twse_index_daily_history ADD COLUMN close_point_tr NUMERIC(12, 2);
