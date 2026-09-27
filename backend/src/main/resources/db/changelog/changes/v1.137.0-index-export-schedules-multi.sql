--liquibase formatted sql

--changeset steven:v1.137.0-index-export-schedules-multi splitStatements:false
-- Requirement 45 / Task 464: one parent row is one independent daily schedule.
DO $$
DECLARE
    has_run_hour BOOLEAN;
    owner_limit RECORD;
BEGIN
    IF to_regclass('public.index_export_schedule') IS NULL THEN
        RAISE EXCEPTION 'index_export_schedule is required before v1.137.0';
    END IF;

    SELECT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'index_export_schedule' AND column_name = 'run_hour'
    ) INTO has_run_hour;

    IF NOT has_run_hour AND to_regclass('public.index_export_schedule_time') IS NULL THEN
        RAISE EXCEPTION 'index_export_schedule_time is required to migrate the existing schedule model';
    END IF;

    IF NOT has_run_hour AND to_regclass('public.index_export_schedule_time_market') IS NULL THEN
        RAISE EXCEPTION 'index_export_schedule_time_market is required to preserve selected markets during migration';
    END IF;

    IF NOT has_run_hour THEN
        -- Refuse the entire transaction before touching data/schema if an owner cannot fit in the new limit.
        FOR owner_limit IN
            SELECT schedule_counts.owner_user_id,
                   SUM(GREATEST(schedule_counts.time_count, 1))::INTEGER AS migrated_count
            FROM (
                SELECT s.owner_user_id, s.id, COUNT(t.id)::INTEGER AS time_count
                FROM public.index_export_schedule s
                LEFT JOIN public.index_export_schedule_time t ON t.schedule_id = s.id
                GROUP BY s.owner_user_id, s.id
            ) schedule_counts
            GROUP BY schedule_counts.owner_user_id
            HAVING SUM(GREATEST(schedule_counts.time_count, 1)) > 10
        LOOP
            RAISE EXCEPTION 'owner % has % index export schedules after migration; maximum is 10',
                    owner_limit.owner_user_id, owner_limit.migrated_count;
        END LOOP;
    END IF;

    ALTER TABLE public.index_export_schedule ADD COLUMN IF NOT EXISTS name VARCHAR(20);
    ALTER TABLE public.index_export_schedule ADD COLUMN IF NOT EXISTS run_hour INTEGER;
    ALTER TABLE public.index_export_schedule ADD COLUMN IF NOT EXISTS run_minute INTEGER;
    ALTER TABLE public.index_export_schedule ADD COLUMN IF NOT EXISTS last_run_date DATE;
    ALTER TABLE public.index_export_schedule ADD COLUMN IF NOT EXISTS last_run_at TIMESTAMP;
    ALTER TABLE public.index_export_schedule ADD COLUMN IF NOT EXISTS last_run_status VARCHAR(500);

    CREATE TABLE IF NOT EXISTS public.index_export_schedule_market (
        schedule_id BIGINT NOT NULL REFERENCES public.index_export_schedule(id) ON DELETE CASCADE,
        market VARCHAR(16) NOT NULL,
        PRIMARY KEY (schedule_id, market)
    );

    ALTER TABLE public.index_export_schedule DROP CONSTRAINT IF EXISTS uq_index_export_schedule_owner;

    IF NOT has_run_hour THEN
        CREATE TEMPORARY TABLE t464_index_export_time_map (
            old_time_id BIGINT PRIMARY KEY,
            schedule_id BIGINT NOT NULL
        ) ON COMMIT DROP;

        INSERT INTO t464_index_export_time_map (old_time_id, schedule_id)
        SELECT ranked.id,
               CASE WHEN ranked.row_number = 1 THEN ranked.schedule_id
                    ELSE nextval(pg_get_serial_sequence('public.index_export_schedule', 'id')) END
        FROM (
            SELECT t.id, t.schedule_id,
                   ROW_NUMBER() OVER (PARTITION BY t.schedule_id ORDER BY t.run_hour, t.run_minute, t.id) AS row_number
            FROM public.index_export_schedule_time t
        ) ranked;

        CREATE TEMPORARY TABLE t464_index_export_parent_enabled ON COMMIT DROP AS
        SELECT id AS schedule_id, enabled AS original_enabled
        FROM public.index_export_schedule;

        WITH first_time AS (
            SELECT t.*,
                   ROW_NUMBER() OVER (PARTITION BY t.schedule_id ORDER BY t.run_hour, t.run_minute, t.id) AS row_number
            FROM public.index_export_schedule_time t
        )
        UPDATE public.index_export_schedule s
        SET run_hour = first_time.run_hour,
            run_minute = first_time.run_minute,
            enabled = s.enabled AND first_time.enabled,
            last_run_date = first_time.last_run_date,
            last_run_at = first_time.last_run_at,
            last_run_status = first_time.last_run_status,
            updated_at = COALESCE(first_time.updated_at, s.updated_at)
        FROM first_time
        WHERE first_time.schedule_id = s.id AND first_time.row_number = 1;

        UPDATE public.index_export_schedule s
        SET run_hour = 8, run_minute = 0, enabled = FALSE,
            last_run_date = NULL, last_run_at = NULL, last_run_status = NULL
        WHERE NOT EXISTS (
            SELECT 1 FROM public.index_export_schedule_time t WHERE t.schedule_id = s.id
        );

        INSERT INTO public.index_export_schedule (
            id, owner_user_id, name, enabled, run_hour, run_minute,
            last_run_date, last_run_at, last_run_status, output_subpath, range_months,
            gdrive_enabled, gdrive_subpath, gdrive_last_run_at, gdrive_last_status, updated_at
        )
        SELECT mapping.schedule_id, parent.owner_user_id, NULL,
               original_parent.original_enabled AND child.enabled, child.run_hour, child.run_minute,
               child.last_run_date, child.last_run_at, child.last_run_status,
               parent.output_subpath, parent.range_months, parent.gdrive_enabled,
               parent.gdrive_subpath, NULL, NULL, child.updated_at
        FROM public.index_export_schedule_time child
        JOIN t464_index_export_time_map mapping ON mapping.old_time_id = child.id
        JOIN public.index_export_schedule parent ON parent.id = child.schedule_id
        JOIN t464_index_export_parent_enabled original_parent ON original_parent.schedule_id = parent.id
        WHERE mapping.schedule_id <> child.schedule_id;

        IF to_regclass('public.index_export_schedule_time_market') IS NOT NULL THEN
            INSERT INTO public.index_export_schedule_market (schedule_id, market)
            SELECT mapping.schedule_id, old_market.market
            FROM public.index_export_schedule_time_market old_market
            JOIN t464_index_export_time_map mapping ON mapping.old_time_id = old_market.schedule_time_id
            ON CONFLICT (schedule_id, market) DO NOTHING;
        END IF;

        -- Old owner-level Drive execution state cannot be attributed to a split schedule.
        UPDATE public.index_export_schedule
        SET gdrive_last_run_at = NULL, gdrive_last_status = NULL;

        CREATE TEMPORARY TABLE t464_duplicate_markets ON COMMIT DROP AS
        SELECT s.owner_user_id, market.market
        FROM public.index_export_schedule s
        JOIN public.index_export_schedule_market market ON market.schedule_id = s.id
        GROUP BY s.owner_user_id, market.market
        HAVING COUNT(*) > 1;

        UPDATE public.index_export_schedule schedule
        SET name = 'legacy_' || LPAD(schedule.run_hour::TEXT, 2, '0') || LPAD(schedule.run_minute::TEXT, 2, '0')
        FROM public.index_export_schedule_market market,
             t464_duplicate_markets duplicate
        WHERE market.schedule_id = schedule.id
          AND duplicate.owner_user_id = schedule.owner_user_id
          AND duplicate.market = market.market;

        PERFORM setval(pg_get_serial_sequence('public.index_export_schedule', 'id'),
                       GREATEST(COALESCE((SELECT MAX(id) FROM public.index_export_schedule), 1), 1),
                       EXISTS (SELECT 1 FROM public.index_export_schedule));

        DROP TABLE IF EXISTS public.index_export_schedule_time_market;
        DROP TABLE IF EXISTS public.index_export_schedule_time;
    END IF;

    ALTER TABLE public.index_export_schedule ALTER COLUMN run_hour SET DEFAULT 8;
    ALTER TABLE public.index_export_schedule ALTER COLUMN run_minute SET DEFAULT 0;
    UPDATE public.index_export_schedule SET run_hour = 8 WHERE run_hour IS NULL;
    UPDATE public.index_export_schedule SET run_minute = 0 WHERE run_minute IS NULL;
    ALTER TABLE public.index_export_schedule ALTER COLUMN run_hour SET NOT NULL;
    ALTER TABLE public.index_export_schedule ALTER COLUMN run_minute SET NOT NULL;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = 'public.index_export_schedule'::regclass
                     AND conname = 'ck_index_export_schedule_hour') THEN
        ALTER TABLE public.index_export_schedule
            ADD CONSTRAINT ck_index_export_schedule_hour CHECK (run_hour BETWEEN 0 AND 23);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = 'public.index_export_schedule'::regclass
                     AND conname = 'ck_index_export_schedule_minute') THEN
        ALTER TABLE public.index_export_schedule
            ADD CONSTRAINT ck_index_export_schedule_minute CHECK (run_minute BETWEEN 0 AND 59);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = 'public.index_export_schedule'::regclass
                     AND conname = 'ck_index_export_schedule_range') THEN
        ALTER TABLE public.index_export_schedule
            ADD CONSTRAINT ck_index_export_schedule_range CHECK (range_months IS NULL OR range_months BETWEEN 1 AND 120);
    END IF;

    CREATE INDEX IF NOT EXISTS idx_index_export_schedule_owner
        ON public.index_export_schedule(owner_user_id);
END;
$$;
