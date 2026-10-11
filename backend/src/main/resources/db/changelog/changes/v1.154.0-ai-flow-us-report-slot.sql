--liquibase formatted sql

--changeset steven:v1.154.0-ai-flow-us-report-slot splitStatements:false
-- Task 494：只擴充觀測排程時段；既有 immutable JSON、identity 與 guard 保持原樣。
ALTER TABLE public.ai_flow_run DROP CONSTRAINT IF EXISTS ai_flow_run_report_slot_check;
ALTER TABLE public.ai_flow_run ADD CONSTRAINT ai_flow_run_report_slot_check
    CHECK (report_slot IN ('09:05','12:00','11:40','22:00'));
