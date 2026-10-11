--liquibase formatted sql

--changeset steven:v1.152.0-api-error-log-retention-insert-guard splitStatements:false
CREATE OR REPLACE FUNCTION guard_api_error_log_retention() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.occurred_at < transaction_timestamp() - interval '30 days' THEN
            RAISE EXCEPTION 'api_error_log is outside retention window';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'api_error_log is append-only';
    END IF;
    IF OLD.occurred_at >= transaction_timestamp() - interval '30 days' THEN
        RAISE EXCEPTION 'api_error_log is within retention window';
    END IF;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS api_error_log_retention_guard ON api_error_log;
CREATE TRIGGER api_error_log_retention_guard
    BEFORE INSERT OR UPDATE OR DELETE ON api_error_log
    FOR EACH ROW EXECUTE FUNCTION guard_api_error_log_retention();
