--liquibase formatted sql

--changeset steven:v1.157.0-independent-asset-gain-export-schedules splitStatements:false
-- Task 511: child daily times become independent schedules. Old images require controlled DB rollback.
DO $$
DECLARE
    parent_table TEXT;
    child_table TEXT;
    owner_constraint TEXT;
    owner_index TEXT;
    limits RECORD;
    sequence_name TEXT;
    sequence_last BIGINT;
    maximum_id BIGINT;
BEGIN
    -- Validate both features before changing either one. Never truncate an over-limit owner's data.
    FOREACH parent_table IN ARRAY ARRAY['export_schedule_setting', 'realized_gain_export_schedule'] LOOP
        child_table := CASE parent_table WHEN 'export_schedule_setting' THEN 'export_schedule_time'
                         ELSE 'realized_gain_export_schedule_time' END;
        IF to_regclass('public.' || parent_table) IS NULL THEN
            RAISE EXCEPTION '% is required before v1.157.0', parent_table;
        END IF;
        IF to_regclass('public.' || child_table) IS NOT NULL THEN
            FOR limits IN EXECUTE format(
                'SELECT owner_user_id, SUM(GREATEST(child_count,1)) AS total FROM '
                || '(SELECT p.owner_user_id,p.id,COUNT(c.id) AS child_count FROM public.%I p '
                || 'LEFT JOIN public.%I c ON c.schedule_id=p.id GROUP BY p.owner_user_id,p.id) counts '
                || 'GROUP BY owner_user_id HAVING SUM(GREATEST(child_count,1))>10', parent_table, child_table)
            LOOP
                RAISE EXCEPTION 'owner % has % schedules in % after migration; maximum is 10',
                    limits.owner_user_id, limits.total, parent_table;
            END LOOP;
        END IF;
    END LOOP;

    FOREACH parent_table IN ARRAY ARRAY['export_schedule_setting', 'realized_gain_export_schedule'] LOOP
        child_table := CASE parent_table WHEN 'export_schedule_setting' THEN 'export_schedule_time'
                         ELSE 'realized_gain_export_schedule_time' END;
        owner_constraint := CASE parent_table WHEN 'export_schedule_setting' THEN 'uq_export_schedule_owner'
                              ELSE 'uq_rg_export_schedule_owner' END;
        owner_index := CASE parent_table WHEN 'export_schedule_setting' THEN 'idx_export_schedule_owner'
                         ELSE 'idx_realized_gain_export_schedule_owner' END;
        EXECUTE format('ALTER TABLE public.%I ADD COLUMN IF NOT EXISTS name VARCHAR(20)', parent_table);
        EXECUTE format('ALTER TABLE public.%I DROP CONSTRAINT IF EXISTS %I', parent_table, owner_constraint);

        -- Replaying after the children were dropped must preserve all new execution state.
        IF to_regclass('public.' || child_table) IS NOT NULL THEN
            EXECUTE format('CREATE TEMP TABLE t511_original_parents ON COMMIT DROP AS SELECT * FROM public.%I', parent_table);
            sequence_name := pg_get_serial_sequence('public.' || parent_table, 'id');
            EXECUTE format('SELECT last_value FROM %s', sequence_name) INTO sequence_last;
            EXECUTE format('SELECT COALESCE(MAX(id),0) FROM public.%I', parent_table) INTO maximum_id;
            PERFORM setval(sequence_name, GREATEST(maximum_id, sequence_last, 1), true);
            EXECUTE format(
                'CREATE TEMP TABLE t511_time_map ON COMMIT DROP AS SELECT ranked.id AS child_id, '
                || 'CASE WHEN row_number=1 THEN schedule_id ELSE nextval(%L) END AS new_id '
                || 'FROM (SELECT c.*, ROW_NUMBER() OVER (PARTITION BY schedule_id ORDER BY run_hour,run_minute,id) '
                || 'AS row_number FROM public.%I c) ranked', sequence_name, child_table);

            EXECUTE format(
                'INSERT INTO public.%I (id,owner_user_id,name,enabled,run_hour,run_minute,output_subpath,'
                || 'last_run_date,last_run_at,last_run_status,gdrive_enabled,gdrive_subpath,'
                || 'gdrive_last_run_at,gdrive_last_status,updated_at) '
                || 'SELECT m.new_id,p.owner_user_id,NULL,p.enabled AND c.enabled,c.run_hour,c.run_minute,p.output_subpath,'
                || 'c.last_run_date,c.last_run_at,c.last_run_status,p.gdrive_enabled,p.gdrive_subpath,NULL,NULL,'
                || 'COALESCE(c.updated_at,p.updated_at) FROM public.%I c JOIN t511_time_map m ON m.child_id=c.id '
                || 'JOIN t511_original_parents p ON p.id=c.schedule_id WHERE m.new_id<>p.id', parent_table, child_table);
            EXECUTE format(
                'UPDATE public.%I p SET name=NULL,enabled=o.enabled AND c.enabled,run_hour=c.run_hour,'
                || 'run_minute=c.run_minute,last_run_date=c.last_run_date,last_run_at=c.last_run_at,'
                || 'last_run_status=c.last_run_status,updated_at=COALESCE(c.updated_at,o.updated_at) '
                || 'FROM public.%I c JOIN t511_time_map m ON m.child_id=c.id '
                || 'JOIN t511_original_parents o ON o.id=c.schedule_id '
                || 'WHERE p.id=o.id AND m.new_id=p.id', parent_table, child_table);
            -- No-child rows retain their original time, enabled and local guard/status.
            EXECUTE format('UPDATE public.%I SET name=NULL,gdrive_last_run_at=NULL,gdrive_last_status=NULL', parent_table);
            EXECUTE format('DROP TABLE public.%I', child_table);
            DROP TABLE t511_time_map;
            DROP TABLE t511_original_parents;
        END IF;
        EXECUTE format('CREATE INDEX IF NOT EXISTS %I ON public.%I(owner_user_id)', owner_index, parent_table);
    END LOOP;
END;
$$;
