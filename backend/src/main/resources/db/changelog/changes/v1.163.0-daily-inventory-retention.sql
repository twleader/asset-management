--liquibase formatted sql
--changeset steven:v1.163.0-daily-inventory-retention splitStatements:false
--comment: Task556 replace permanent history with inclusive 66-calendar-month retention, market-local dates.
CREATE OR REPLACE FUNCTION daily_inventory_retention_cutoff(p_market TEXT,p_at TIMESTAMPTZ)
RETURNS DATE LANGUAGE plpgsql STABLE AS $$
DECLARE local_today DATE;
BEGIN
  IF p_market IS NULL OR p_at IS NULL THEN
    RAISE EXCEPTION 'daily inventory retention identity unavailable' USING ERRCODE='23514';
  END IF;
  CASE p_market
    WHEN '台股' THEN local_today := (p_at AT TIME ZONE 'Asia/Taipei')::DATE;
    WHEN '美股' THEN local_today := (p_at AT TIME ZONE 'America/New_York')::DATE;
    ELSE RAISE EXCEPTION 'daily inventory retention market unavailable' USING ERRCODE='23514';
  END CASE;
  RETURN (local_today - INTERVAL '66 months')::DATE;
END;
$$;
CREATE INDEX IF NOT EXISTS idx_daily_inventory_retention
  ON daily_inventory_snapshot(market,trading_date,id);

CREATE OR REPLACE FUNCTION guard_daily_inventory_retention()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE row_market TEXT; row_date DATE; parent_id BIGINT; cutoff DATE;
BEGIN
  IF TG_OP='UPDATE' THEN
    RAISE EXCEPTION 'daily inventory history is immutable' USING ERRCODE='23514';
  END IF;
  IF TG_TABLE_NAME='daily_inventory_snapshot' THEN
    IF TG_OP='INSERT' THEN row_market := NEW.market; row_date := NEW.trading_date;
    ELSE row_market := OLD.market; row_date := OLD.trading_date; END IF;
  ELSIF TG_TABLE_NAME='daily_inventory_position' THEN
    IF TG_OP='INSERT' THEN parent_id := NEW.snapshot_id;
    ELSE parent_id := OLD.snapshot_id; END IF;
    SELECT market,trading_date INTO row_market,row_date FROM daily_inventory_snapshot WHERE id=parent_id;
    IF NOT FOUND THEN
      RAISE EXCEPTION 'daily inventory retention parent unavailable' USING ERRCODE='23514';
    END IF;
  ELSE
    RAISE EXCEPTION 'daily inventory retention table unavailable' USING ERRCODE='23514';
  END IF;
  cutoff := daily_inventory_retention_cutoff(row_market,statement_timestamp());
  IF row_date IS NULL THEN
    RAISE EXCEPTION 'daily inventory retention date unavailable' USING ERRCODE='23514';
  END IF;
  IF TG_OP='INSERT' THEN
    IF row_date<cutoff THEN
      RAISE EXCEPTION 'expired daily inventory cannot be inserted' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
  END IF;
  IF TG_OP='DELETE' AND row_date<cutoff THEN RETURN OLD; END IF;
  RAISE EXCEPTION 'daily inventory history is immutable until expired' USING ERRCODE='23514';
END;
$$;
DROP TRIGGER IF EXISTS daily_inventory_snapshot_immutable ON daily_inventory_snapshot;
CREATE TRIGGER daily_inventory_snapshot_immutable BEFORE INSERT OR UPDATE OR DELETE ON daily_inventory_snapshot
  FOR EACH ROW EXECUTE FUNCTION guard_daily_inventory_retention();
DROP TRIGGER IF EXISTS daily_inventory_position_immutable ON daily_inventory_position;
CREATE TRIGGER daily_inventory_position_immutable BEFORE INSERT OR UPDATE OR DELETE ON daily_inventory_position
  FOR EACH ROW EXECUTE FUNCTION guard_daily_inventory_retention();
