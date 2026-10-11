--liquibase formatted sql

--changeset steven:v1.153.0-ai-flow-event-log splitStatements:false
-- Task 492：immutable historical JSON snapshot 與少數查詢 identity 是具名正規化例外。
CREATE TABLE IF NOT EXISTS public.ai_flow_run (
    owner_user_id bigint NOT NULL REFERENCES public.app_user(id),
    run_id uuid NOT NULL,
    consumer varchar(6) NOT NULL CHECK (consumer IN ('Codex','Claude')),
    report_date date NOT NULL,
    report_slot varchar(5) NOT NULL CHECK (report_slot IN ('09:05','12:00','11:40')),
    source_kind varchar(20) NOT NULL CHECK (source_kind IN ('CODEX_ROLLOUT','CLAUDE_ARTIFACT','SCHEDULE_EVENTS')),
    source_run_id varchar(200) NOT NULL,
    metadata_json text NOT NULL,
    metadata_sha256 char(64) NOT NULL CHECK (metadata_sha256 ~ '^[0-9a-f]{64}$'),
    received_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(owner_user_id,run_id),
    UNIQUE(owner_user_id,consumer,source_kind,source_run_id),
    CHECK (source_kind='SCHEDULE_EVENTS' OR (source_kind='CODEX_ROLLOUT' AND consumer='Codex') OR (source_kind='CLAUDE_ARTIFACT' AND consumer='Claude'))
);
CREATE INDEX IF NOT EXISTS idx_ai_flow_run_query ON public.ai_flow_run(owner_user_id,report_date,consumer,report_slot,run_id);
CREATE TABLE IF NOT EXISTS public.ai_flow_event (
    owner_user_id bigint NOT NULL,
    run_id uuid NOT NULL,
    event_id uuid NOT NULL,
    sequence integer NOT NULL CHECK(sequence>=0),
    payload_json text NOT NULL,
    payload_sha256 char(64) NOT NULL CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    received_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(owner_user_id,run_id,event_id),
    FOREIGN KEY(owner_user_id,run_id) REFERENCES public.ai_flow_run(owner_user_id,run_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_ai_flow_event_query ON public.ai_flow_event(owner_user_id,run_id,sequence,event_id);

CREATE OR REPLACE FUNCTION public.guard_ai_flow_run() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE today date := (transaction_timestamp() AT TIME ZONE 'Asia/Taipei')::date;
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.report_date < today - 89 THEN RAISE EXCEPTION 'AI_FLOW_RETENTION_EXPIRED'; END IF;
        IF NEW.report_date > today THEN RAISE EXCEPTION 'AI_FLOW_FUTURE_DATE'; END IF;
        RETURN NEW;
    END IF;
    IF TG_OP='UPDATE' THEN RAISE EXCEPTION 'AI_FLOW_IMMUTABLE'; END IF;
    IF OLD.report_date >= today - 89 THEN RAISE EXCEPTION 'AI_FLOW_WITHIN_RETENTION'; END IF;
    RETURN OLD;
END;
$$;
CREATE OR REPLACE FUNCTION public.guard_ai_flow_event() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE source_date date; today date := (transaction_timestamp() AT TIME ZONE 'Asia/Taipei')::date;
BEGIN
    IF TG_OP='UPDATE' THEN RAISE EXCEPTION 'AI_FLOW_IMMUTABLE'; END IF;
    IF TG_OP='DELETE' THEN
        -- 只允已通過 run DELETE guard 的 FK cascade；不能單獨刪除事件。
        IF pg_trigger_depth()>1 AND NOT EXISTS(SELECT 1 FROM public.ai_flow_run WHERE owner_user_id=OLD.owner_user_id AND run_id=OLD.run_id) THEN RETURN OLD; END IF;
        RAISE EXCEPTION 'AI_FLOW_EVENT_DELETE_REQUIRES_EXPIRED_RUN';
    END IF;
    SELECT report_date INTO source_date FROM public.ai_flow_run WHERE owner_user_id=NEW.owner_user_id AND run_id=NEW.run_id FOR KEY SHARE;
    IF source_date IS NULL THEN RAISE EXCEPTION 'AI_FLOW_PARENT_NOT_FOUND'; END IF;
    IF source_date < today - 89 THEN RAISE EXCEPTION 'AI_FLOW_RETENTION_EXPIRED'; END IF;
    IF source_date > today THEN RAISE EXCEPTION 'AI_FLOW_FUTURE_DATE'; END IF;
    RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS ai_flow_run_guard ON public.ai_flow_run;
CREATE TRIGGER ai_flow_run_guard BEFORE INSERT OR UPDATE OR DELETE ON public.ai_flow_run FOR EACH ROW EXECUTE FUNCTION public.guard_ai_flow_run();
DROP TRIGGER IF EXISTS ai_flow_event_guard ON public.ai_flow_event;
CREATE TRIGGER ai_flow_event_guard BEFORE INSERT OR UPDATE OR DELETE ON public.ai_flow_event FOR EACH ROW EXECUTE FUNCTION public.guard_ai_flow_event();
INSERT INTO public.api_error_log_operation(source,operation_key,operation_label,display_order)
VALUES('OPEN_API','OPEN_SRPP_AI_FLOW_EVENTS','AI 判斷流程紀錄',250)
ON CONFLICT(source,operation_key) DO NOTHING;
