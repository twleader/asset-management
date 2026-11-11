--liquibase formatted sql
--changeset steven:v1.164.0-daily-inventory-retention-42-months splitStatements:false
--comment: Task571 shorten daily inventory retention from 66 to 42 calendar months (3.5 years), market-local dates.
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
  RETURN (local_today - INTERVAL '42 months')::DATE;
END;
$$;
