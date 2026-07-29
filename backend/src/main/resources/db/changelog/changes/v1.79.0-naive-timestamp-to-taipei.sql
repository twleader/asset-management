--liquibase formatted sql

--changeset steven:v1.79.0-naive-timestamp-to-taipei splitStatements:false
-- Requirement 53 / Task 252：把「存 UTC 牆鐘」的 11 個 naive timestamp 欄位校正為台北牆鐘，
-- 與同批上線的容器 TZ=Asia/Taipei 對齊。
--
-- 【changeset id 絕對不可改名】改名 = Liquibase 視為新 migration 重跑 = 資料變成 +16 小時。
--   本專案有前科：changeset 改 id 導致重跑 → already exists → business crash loop 整站掛。
-- 【只可與 docker-compose 的 TZ=Asia/Taipei 同一次部署上線】提早或延後單獨執行都會產生半套資料。
-- 【不加日期 cutoff 是刻意的】Liquibase runOnce ＋ 本 changeset 於 DataSource 初始化後、Web 層與排程
--   啟動之前執行；external-materials-service 又以 depends_on: business-services(service_healthy) 等待，
--   故執行當下全表皆為舊的 UTC 值。寫死日期 cutoff 反而會在部署延後時漏掉那幾天的新列。
-- 【台北全年固定 UTC+8、無夏令時間】固定 interval 安全（若目標是 America/New_York 就絕不能這樣寫）。
--
-- ── 判準：一個 naive 欄位會不會隨 JVM 時區位移，取決於「寫入端怎麼繫結」，不是取決於 Java 型別 ──
--   (a) 裸 LocalDateTime.now()                      → 位移 → 本清單納入
--   (b) SQL NOW() 灌進 naive 欄                      → 位移 → 本清單納入
--       （pgjdbc 每條連線把 JVM 預設時區當 session TimeZone 送出，NOW() 的 timestamptz→timestamp
--         隱式轉型即依該 session TimeZone）
--   (c) JdbcTemplate 的 Timestamp.from(instant)（不帶 Calendar） → 位移
--       → **但不在本清單**：破口在寫入端，改歷史值只會把舊列一起弄錯。已於同批把
--         CrawlerExportPathQuery / FundNavSourceQuery 改綁 LocalDateTime.ofInstant(now, UTC)。
--   (d) Hibernate 的 Instant 欄位                    → **不位移**，絕對不可動
--       （Hibernate 6 對 Instant 走 TimestampUtcAsJdbcTimestampJdbcType，bind/extract 兩側都帶
--         UTC Calendar，時區中立；crawler_export_setting.updated_at、crawler_schedule.updated_at、
--         market_analysis_send_time.created_at、crawler_export_setting.gdrive_last_run_at 屬此類）
--
-- 其餘不在清單內的欄位一律不動，特別是：
--   * 28 欄已是台北牆鐘（7 張匯出排程表 ×3、交易雷達匯出 ×4、備份 ×3；service 層 LocalDateTime.now(TW_ZONE)）
--     —— 動了會弄壞排程的「今日是否已跑過」判定
--   * 2 欄市場牆鐘：stock_alert.last_triggered_at、stock_alert_trigger.triggered_at（刻意設計）
--   * 10 個 timestamptz 欄位（本身帶時區，Postgres 會正確處理）
DO $$
DECLARE
    tgt RECORD;
BEGIN
    FOR tgt IN
        SELECT * FROM (VALUES
            -- entity @PrePersist/@PreUpdate 的裸 LocalDateTime.now()（5 支 entity、9 欄）
            ('app_user','created_at'), ('app_user','updated_at'),
            ('notification_recipient','created_at'), ('notification_recipient','updated_at'),
            ('stock_alert','created_at'), ('stock_alert','updated_at'),
            ('stock_alert_trigger','created_at'),
            ('trading_radar_notification_setting','created_at'),
            ('trading_radar_notification_setting','updated_at'),
            -- service 層唯一一處裸寫（MarketAnalysisService）
            ('market_analysis_setting','updated_at'),
            -- SQL NOW() 灌進 naive 欄（ext StockSourceQuery）
            ('stock_dividend_history','updated_at')
        ) AS t(tbl, col)
    LOOP
        -- guard 與 UPDATE 都鎖定 public schema，且**同時檢查表與欄位是否存在**：
        -- 只擋表不擋欄位的話，遇到部分還原的庫會擲 column does not exist → 整個 changeset 失敗
        -- → Liquibase 中止 → business 起不來。
        IF EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = 'public' AND table_name = tgt.tbl AND column_name = tgt.col) THEN
            EXECUTE format('UPDATE public.%I SET %I = %I + INTERVAL ''8 hours'' WHERE %I IS NOT NULL',
                           tgt.tbl, tgt.col, tgt.col, tgt.col);
        END IF;
    END LOOP;
END $$;

--rollback DO $$ DECLARE tgt RECORD; BEGIN FOR tgt IN SELECT * FROM (VALUES ('app_user','created_at'),('app_user','updated_at'),('notification_recipient','created_at'),('notification_recipient','updated_at'),('stock_alert','created_at'),('stock_alert','updated_at'),('stock_alert_trigger','created_at'),('trading_radar_notification_setting','created_at'),('trading_radar_notification_setting','updated_at'),('market_analysis_setting','updated_at'),('stock_dividend_history','updated_at')) AS t(tbl,col) LOOP IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name=tgt.tbl AND column_name=tgt.col) THEN EXECUTE format('UPDATE public.%I SET %I = %I - INTERVAL ''8 hours'' WHERE %I IS NOT NULL', tgt.tbl, tgt.col, tgt.col, tgt.col); END IF; END LOOP; END $$;
