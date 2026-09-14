--
-- 資產管理系統 — Schema-only 基準線（純結構、**不含任何資料**）
--
-- 用途
--   本檔是 DB schema 的唯一標準，由 pg_dump --schema-only 直接自運行中的 asset-postgres
--   產生（見下方「重新產生」段）。表存在與否、欄位、型別、位數、nullable、預設值、CHECK、
--   索引一律以本檔為準，供程式碼審查與規格稽核比對「Entity / Liquibase changelog /
--   實際 DB」三者是否一致。在此檔納入版控之前，含真實個人財務資料的 db/init/01_dump.sql
--   因資安 Requirement 29 被 .gitignore 排除，任何從 git 取得原始碼的人（CI／新進成員／
--   稽核）都無法查證欄位位數與 nullable，此類問題永遠無法被驗證或回歸測試。
--   db/changelog/** 一律不得用於描述 schema 現況——那裡有永不執行的 changeset。
--
-- ⚠ 本檔「不會」被執行
--   它刻意放在 db/ 而非 db/init/。docker-compose 只把 ./db/init 掛進
--   docker-entrypoint-initdb.d，故本檔不參與 DB 初始化，也不會與 01_dump.sql 衝突。
--   全新環境初始化／還原一律仍用 db/init/01_dump.sql（由部署方本機提供）。
--
-- 重新產生（schema 變更後請同步更新本檔）
--   1) 先把 pg_dump 輸出寫到「暫存檔」。**不可**直接重導向到 db/schema.sql——shell 會在
--      pipeline 啟動的當下就把本檔連同這段檔頭一起截斷，第 2 步屆時已無來源可接，只能
--      靠 git show 撈回：
--        docker exec asset-postgres pg_dump -U assets -d assets \
--          --schema-only --no-owner --no-privileges \
--          | grep -v '^\\restrict\|^\\unrestrict' > /tmp/fresh-schema.sql
--   2) 再把本檔第 1 行至「PostgreSQL database dump」那行的前一行（即本段檔頭；檔頭最後
--      一行恰為一行 --，其後才是 pg_dump 本體），接到 /tmp/fresh-schema.sql 之前寫回本檔：
--        HDR=$(( $(grep -nxF -- '-- PostgreSQL database dump' db/schema.sql | head -1 | cut -d: -f1) - 2 ))
--        head -n "$HDR" db/schema.sql > /tmp/schema-header.sql
--        cat /tmp/schema-header.sql /tmp/fresh-schema.sql > db/schema.sql
--   3) 最後把下面「產生當下表數」那一行更新成 grep -c '^CREATE TABLE' db/schema.sql 的
--      實際值（防漂移腳本會離線驗證這一項）。
--   註：pg_dump 16 會輸出帶「隨機 token」的 \restrict / \unrestrict 兩行，每次產生皆不同，
--       會造成無意義的 diff 雜訊，故上面刻意濾除；濾除後同一 schema 可位元級重現。
--       （grep 樣式的反斜線必須寫成 \\，單一 \r 會被 grep 當成字面 r、濾不掉。）
--
-- 防漂移閘門
--   本檔與運行中 DB 的同步由 scripts/tests/schema-sql-drift-test.sh 逐位元比對（去除本段
--   檔頭後「全文」比對，不是只比表名），並由 scripts/spec-check.sh 的 B10 實際執行。
--   這是 lagging 檢查：spec-check 跑在實作「之前」，schema 漂移卻產生於實作「之後」，
--   因此改動 schema 後未重產本檔，會在「下一次」spec-check 被機械指出，而不是當場攔下。
--   剛動過 db/changelog/** 的當下本檔可能尚未重產，此時在本檔查不到某張表，代表本檔過期
--   需要重產（不確定時跑 bash scripts/tests/schema-sql-drift-test.sh：回 0 就是本檔新鮮、
--   該表確實不存在）。發現本檔與運行中 DB 不一致，唯一的處置是依上方
--   「重新產生」段的指令重產本檔並納入本次變更，不要改用別的來源當基準。運行中的
--   asset-postgres 是多個 worktree 共用的可變狀態，本檔因此可能短暫含尚未 merge 的表；
--   那不影響它的標準地位——那些 changeset 其後都會 land，本檔的下一次重產也會自動收斂。
--
-- 產生資訊：PostgreSQL 16.14 / pg_dump 16.14，來源 asset-postgres schema-only dump，2026-09-14
-- 產生當下表數：101 張 CREATE TABLE（對照：SELECT count(*) FROM pg_tables WHERE schemaname='public';）
--
--
--
-- PostgreSQL database dump
--


-- Dumped from database version 16.14
-- Dumped by pg_dump version 16.14

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: guard_api_error_log_retention(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.guard_api_error_log_retention() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN RAISE EXCEPTION 'api_error_log is append-only'; END IF;
    IF OLD.occurred_at >= transaction_timestamp() - interval '30 days' THEN
        RAISE EXCEPTION 'api_error_log is within retention window';
    END IF;
    RETURN OLD;
END;
$$;


--
-- Name: guard_fubon_historical_daily_candle_immutable(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.guard_fubon_historical_daily_candle_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'fubon_historical_daily_candle is immutable';
END;
$$;


--
-- Name: reject_api_error_log_operation_mutation(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.reject_api_error_log_operation_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN RAISE EXCEPTION 'api_error_log_operation is immutable'; END;
$$;


--
-- Name: reject_fubon_technical_history_mutation(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.reject_fubon_technical_history_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'fubon technical history is immutable';
END;
$$;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: api_error_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.api_error_log (
    id bigint NOT NULL,
    source character varying(16) NOT NULL,
    operation_key character varying(160) NOT NULL,
    api_name character varying(255) NOT NULL,
    message_header text NOT NULL,
    stack_trace text NOT NULL,
    occurred_at timestamp with time zone NOT NULL,
    http_status smallint,
    dedupe_key character varying(64),
    CONSTRAINT api_error_log_http_status_check CHECK (((http_status IS NULL) OR ((http_status >= 100) AND (http_status <= 599)))),
    CONSTRAINT api_error_log_source_check CHECK (((source)::text = ANY ((ARRAY['OPEN_API'::character varying, 'FUBON_API'::character varying])::text[])))
);


--
-- Name: api_error_log_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.api_error_log ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.api_error_log_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: api_error_log_operation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.api_error_log_operation (
    source character varying(16) NOT NULL,
    operation_key character varying(160) NOT NULL,
    operation_label character varying(255) NOT NULL,
    display_order smallint NOT NULL,
    CONSTRAINT api_error_log_operation_display_order_check CHECK ((display_order > 0)),
    CONSTRAINT api_error_log_operation_source_check CHECK (((source)::text = ANY ((ARRAY['OPEN_API'::character varying, 'FUBON_API'::character varying])::text[])))
);


--
-- Name: app_feature; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.app_feature (
    id bigint NOT NULL,
    code character varying(100) NOT NULL,
    display_name character varying(50) NOT NULL,
    menu_group character varying(50),
    sort_order integer DEFAULT 0 NOT NULL,
    enabled_for_user boolean DEFAULT true NOT NULL
);


--
-- Name: app_feature_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.app_feature_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: app_feature_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.app_feature_id_seq OWNED BY public.app_feature.id;


--
-- Name: app_user; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.app_user (
    id bigint NOT NULL,
    email character varying(255) NOT NULL,
    name character varying(255),
    picture character varying(512),
    role character varying(20) DEFAULT 'USER'::character varying NOT NULL,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: app_user_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.app_user ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.app_user_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: asset_class; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.asset_class (
    id bigint NOT NULL,
    code character varying(20) NOT NULL,
    display_name character varying(50) NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    active boolean DEFAULT true NOT NULL
);


--
-- Name: asset_class_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.asset_class ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.asset_class_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: asset_snapshot; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.asset_snapshot (
    id bigint NOT NULL,
    estimated_annual_dividend numeric(20,2),
    notes character varying(500),
    realized_gain numeric(20,2),
    snapshot_date date NOT NULL,
    total_assets numeric(20,2),
    total_deposit numeric(20,2),
    total_fund_cost numeric(20,2),
    total_fund_value numeric(20,2),
    total_stock_cost numeric(20,2),
    total_stock_value numeric(20,2),
    usd_exchange_rate numeric(10,4),
    owner_user_id bigint NOT NULL
);


--
-- Name: asset_snapshot_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.asset_snapshot ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.asset_snapshot_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: asset_transaction; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.asset_transaction (
    id bigint NOT NULL,
    owner_user_id bigint NOT NULL,
    transaction_type character varying(10) NOT NULL,
    asset_type character varying(10) NOT NULL,
    asset_name character varying(50) NOT NULL,
    asset_code character varying(20),
    market character varying(20),
    currency character varying(10),
    channel character varying(30),
    trade_date date NOT NULL,
    shares numeric(15,5),
    price numeric(17,6),
    amount numeric(20,2) NOT NULL,
    exchange_rate numeric(10,4),
    notes character varying(500),
    fee numeric(15,2),
    transaction_tax numeric(15,2),
    source character varying(20) DEFAULT 'MANUAL'::character varying NOT NULL,
    broker_filled_no character varying(50),
    CONSTRAINT asset_transaction_source_check CHECK (((source)::text = ANY ((ARRAY['MANUAL'::character varying, 'FUBON_SYNC'::character varying])::text[])))
);


--
-- Name: asset_transaction_export_schedule; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.asset_transaction_export_schedule (
    id bigint NOT NULL,
    owner_user_id bigint NOT NULL,
    enabled boolean DEFAULT false NOT NULL,
    run_hour integer DEFAULT 8 NOT NULL,
    run_minute integer DEFAULT 0 NOT NULL,
    output_subpath character varying(255) DEFAULT 'input'::character varying NOT NULL,
    last_run_date date,
    last_run_at timestamp without time zone,
    last_run_status character varying(500),
    updated_at timestamp without time zone,
    gdrive_enabled boolean DEFAULT false NOT NULL,
    gdrive_subpath character varying(512),
    gdrive_last_run_at timestamp without time zone,
    gdrive_last_status character varying(512),
    name character varying(50),
    CONSTRAINT ck_at_export_schedule_hour CHECK (((run_hour >= 0) AND (run_hour <= 23))),
    CONSTRAINT ck_at_export_schedule_minute CHECK (((run_minute >= 0) AND (run_minute <= 59)))
);


--
-- Name: asset_transaction_export_schedule_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.asset_transaction_export_schedule_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: asset_transaction_export_schedule_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.asset_transaction_export_schedule_id_seq OWNED BY public.asset_transaction_export_schedule.id;


--
-- Name: asset_transaction_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.asset_transaction_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: asset_transaction_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.asset_transaction_id_seq OWNED BY public.asset_transaction.id;


--
-- Name: backup_record; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.backup_record (
    id bigint NOT NULL,
    folder character varying(20) NOT NULL,
    filename character varying(255) NOT NULL,
    size_bytes bigint DEFAULT 0 NOT NULL,
    modified_at timestamp without time zone NOT NULL,
    auto_pre_restore boolean DEFAULT false NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: backup_record_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.backup_record_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: backup_record_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.backup_record_id_seq OWNED BY public.backup_record.id;


--
-- Name: backup_setting; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.backup_setting (
    id integer DEFAULT 1 NOT NULL,
    manual_retention integer DEFAULT 5 NOT NULL,
    daily_retention integer DEFAULT 50 NOT NULL,
    weekly_retention integer DEFAULT 5 NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    backup_enabled boolean DEFAULT true NOT NULL,
    CONSTRAINT backup_setting_daily_range CHECK (((daily_retention >= 1) AND (daily_retention <= 999))),
    CONSTRAINT backup_setting_manual_range CHECK (((manual_retention >= 1) AND (manual_retention <= 999))),
    CONSTRAINT backup_setting_single_row CHECK ((id = 1)),
    CONSTRAINT backup_setting_weekly_range CHECK (((weekly_retention >= 1) AND (weekly_retention <= 999)))
);


--
-- Name: bank; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.bank (
    id bigint NOT NULL,
    active boolean NOT NULL,
    code character varying(50) NOT NULL,
    display_name character varying(50) NOT NULL,
    keywords character varying(200)
);


--
-- Name: bank_deposit; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.bank_deposit (
    id bigint NOT NULL,
    amount numeric(20,2) NOT NULL,
    currency character varying(20),
    deposit_type character varying(30) NOT NULL,
    notes character varying(200),
    original_amount numeric(20,4),
    snapshot_id bigint NOT NULL,
    bank_id bigint,
    annual_interest_rate numeric(7,4),
    source character varying(20) DEFAULT 'MANUAL'::character varying NOT NULL,
    CONSTRAINT ck_bank_deposit_source CHECK (((source)::text = ANY ((ARRAY['MANUAL'::character varying, 'FUBON_SYNC'::character varying])::text[])))
);


--
-- Name: bank_deposit_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.bank_deposit ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.bank_deposit_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: bank_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.bank ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.bank_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: blog_publish_credential; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.blog_publish_credential (
    id bigint DEFAULT 1 NOT NULL,
    blog_id character varying(64),
    blog_url character varying(512) DEFAULT 'https://myrader.blogspot.com/'::character varying NOT NULL,
    account_label character varying(255),
    access_token character varying(2048),
    access_token_expires_at timestamp without time zone,
    refresh_token character varying(2048),
    needs_reconnect boolean DEFAULT false NOT NULL,
    connected_at timestamp without time zone,
    updated_at timestamp without time zone,
    CONSTRAINT blog_publish_credential_id_check CHECK ((id = 1))
);


--
-- Name: bond_term; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.bond_term (
    id bigint NOT NULL,
    code character varying(20) NOT NULL,
    display_name character varying(50) NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    active boolean DEFAULT true NOT NULL
);


--
-- Name: bond_term_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.bond_term ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.bond_term_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: broker; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.broker (
    id bigint NOT NULL,
    active boolean NOT NULL,
    code character varying(50) NOT NULL,
    display_name character varying(50) NOT NULL,
    keywords character varying(200)
);


--
-- Name: broker_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.broker ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.broker_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: commodity_export_schedule; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.commodity_export_schedule (
    id bigint NOT NULL,
    owner_user_id bigint NOT NULL,
    enabled boolean DEFAULT false NOT NULL,
    run_hour integer DEFAULT 8 NOT NULL,
    run_minute integer DEFAULT 0 NOT NULL,
    output_subpath character varying(255) DEFAULT 'input'::character varying NOT NULL,
    range_months integer,
    last_run_date date,
    last_run_at timestamp without time zone,
    last_run_status character varying(500),
    updated_at timestamp without time zone,
    gdrive_enabled boolean DEFAULT false NOT NULL,
    gdrive_subpath character varying(512),
    gdrive_last_run_at timestamp without time zone,
    gdrive_last_status character varying(512),
    CONSTRAINT ck_commodity_export_schedule_hour CHECK (((run_hour >= 0) AND (run_hour <= 23))),
    CONSTRAINT ck_commodity_export_schedule_minute CHECK (((run_minute >= 0) AND (run_minute <= 59))),
    CONSTRAINT ck_commodity_export_schedule_range CHECK (((range_months IS NULL) OR ((range_months >= 1) AND (range_months <= 120))))
);


--
-- Name: commodity_export_schedule_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.commodity_export_schedule_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: commodity_export_schedule_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.commodity_export_schedule_id_seq OWNED BY public.commodity_export_schedule.id;


--
-- Name: commodity_export_schedule_time; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.commodity_export_schedule_time (
    id bigint NOT NULL,
    schedule_id bigint NOT NULL,
    run_hour integer NOT NULL,
    run_minute integer NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    last_run_date date,
    last_run_at timestamp without time zone,
    last_run_status character varying(500),
    updated_at timestamp without time zone,
    CONSTRAINT ck_commodity_export_schedule_time_hour CHECK (((run_hour >= 0) AND (run_hour <= 23))),
    CONSTRAINT ck_commodity_export_schedule_time_minute CHECK (((run_minute >= 0) AND (run_minute <= 59)))
);


--
-- Name: commodity_export_schedule_time_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.commodity_export_schedule_time_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: commodity_export_schedule_time_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.commodity_export_schedule_time_id_seq OWNED BY public.commodity_export_schedule_time.id;


--
-- Name: commodity_price_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.commodity_price_history (
    id bigint NOT NULL,
    commodity_code character varying(20) NOT NULL,
    price_date date NOT NULL,
    close_price numeric(12,4) NOT NULL,
    provider character varying(64),
    source_url text,
    source_available_at timestamp with time zone,
    fetched_at timestamp with time zone,
    CONSTRAINT ck_commodity_source_time CHECK (((source_available_at IS NULL) OR (fetched_at IS NULL) OR (source_available_at <= fetched_at)))
);


--
-- Name: commodity_price_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.commodity_price_history ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.commodity_price_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: crawler_export_setting; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.crawler_export_setting (
    id bigint NOT NULL,
    crawler_key character varying(64) NOT NULL,
    output_subpath character varying(512) NOT NULL,
    updated_at timestamp without time zone,
    gdrive_enabled boolean DEFAULT false NOT NULL,
    gdrive_subpath character varying(512),
    gdrive_last_run_at timestamp without time zone,
    gdrive_last_status character varying(512)
);


--
-- Name: COLUMN crawler_export_setting.gdrive_enabled; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.crawler_export_setting.gdrive_enabled IS '是否在本機檔寫成功後，額外上傳一份副本到 Google Drive。false（預設）＝只寫本機，行為與 Task 212 完全相同。本欄為「附加」開關，不是儲存目標單選——本機一律照寫，SRPP 依賴那一份。';


--
-- Name: COLUMN crawler_export_setting.gdrive_subpath; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.crawler_export_setting.gdrive_subpath IS 'Google Drive 上的相對子路徑（例 投資理財/資產管理），基底為 rclone remote（名稱由環境變數 GDRIVE_OUTPUT_REMOTE 指定，預設 GDriveOutput）。只存相對子路徑、不存絕對路徑或含 remote 前綴的完整 spec，理由同 output_subpath。gdrive_enabled 為真時必填（service 層驗證，空值回 400）。';


--
-- Name: COLUMN crawler_export_setting.gdrive_last_run_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.crawler_export_setting.gdrive_last_run_at IS '上次 Drive 上傳的判斷時間（含跳過與失敗，非僅成功）。由 external-materials-service 的 NewsPoller 寫入——刻意的所有權例外：上傳結果只有 ext 知道，沒有別的地方能寫；ext 只碰本欄與 gdrive_last_status，列的所有權仍在 backend（UPDATE 命中 0 列時只 warn，不得 upsert）。';


--
-- Name: COLUMN crawler_export_setting.gdrive_last_status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.crawler_export_setting.gdrive_last_status IS '上次 Drive 上傳結果：成功記落點與檔案大小、失敗記錯誤摘要、跳過記原因（子路徑不合法／config 不可用／本機檔寫入失敗）。截斷至 512 字元內。凡「已啟用 Drive 但未實際上傳」也必須寫入，否則本欄會停在上一次的成功、顯示過期的好消息。';


--
-- Name: crawler_export_setting_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.crawler_export_setting_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: crawler_export_setting_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.crawler_export_setting_id_seq OWNED BY public.crawler_export_setting.id;


--
-- Name: crawler_schedule; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.crawler_schedule (
    id bigint NOT NULL,
    crawler_key character varying(64) NOT NULL,
    run_hour integer NOT NULL,
    run_minute integer NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    updated_at timestamp without time zone,
    CONSTRAINT ck_crawler_schedule_hour CHECK (((run_hour >= 0) AND (run_hour <= 23))),
    CONSTRAINT ck_crawler_schedule_minute CHECK (((run_minute >= 0) AND (run_minute <= 59)))
);


--
-- Name: crawler_schedule_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.crawler_schedule_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: crawler_schedule_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.crawler_schedule_id_seq OWNED BY public.crawler_schedule.id;


--
-- Name: daily_market_analysis; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.daily_market_analysis (
    analysis_date date NOT NULL,
    bias character varying(16),
    confidence integer,
    summary text,
    key_factors text,
    news_highlights text,
    tw_context text,
    us_context text,
    model character varying(64),
    status character varying(16) NOT NULL,
    error_message text,
    raw_response text,
    generated_at timestamp with time zone NOT NULL,
    batch_id character varying(64),
    email_sent_at timestamp with time zone,
    factor_groups text
);


--
-- Name: databasechangelog; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.databasechangelog (
    id character varying(255) NOT NULL,
    author character varying(255) NOT NULL,
    filename character varying(255) NOT NULL,
    dateexecuted timestamp without time zone NOT NULL,
    orderexecuted integer NOT NULL,
    exectype character varying(10) NOT NULL,
    md5sum character varying(35),
    description character varying(255),
    comments character varying(255),
    tag character varying(255),
    liquibase character varying(20),
    contexts character varying(255),
    labels character varying(255),
    deployment_id character varying(10)
);


--
-- Name: databasechangeloglock; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.databasechangeloglock (
    id integer NOT NULL,
    locked boolean NOT NULL,
    lockgranted timestamp without time zone,
    lockedby character varying(255)
);


--
-- Name: deposit_type; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.deposit_type (
    id bigint NOT NULL,
    active boolean NOT NULL,
    code character varying(30) NOT NULL,
    display_name character varying(50) NOT NULL,
    sort_order integer NOT NULL,
    withdrawal_order integer DEFAULT 50 NOT NULL
);


--
-- Name: deposit_type_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.deposit_type ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.deposit_type_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: etf_nav_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.etf_nav_history (
    id bigint NOT NULL,
    stock_code character varying(20) NOT NULL,
    market character varying(20) NOT NULL,
    nav_date date NOT NULL,
    nav numeric(15,4) NOT NULL,
    premium_discount_pct numeric(8,4),
    source character varying(30),
    pct_origin character varying(20)
);


--
-- Name: etf_nav_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.etf_nav_history ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.etf_nav_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: etf_nav_observation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.etf_nav_observation (
    id bigint NOT NULL,
    stock_code character varying(20) NOT NULL,
    market character varying(20) NOT NULL,
    nav_date date NOT NULL,
    nav numeric(15,4) NOT NULL,
    premium_discount_pct numeric(8,4),
    pct_origin character varying(20),
    source character varying(64) NOT NULL,
    observed_at timestamp with time zone NOT NULL,
    available_at timestamp with time zone NOT NULL,
    availability_basis character varying(48) NOT NULL,
    CONSTRAINT ck_etf_nav_observation_available CHECK ((available_at >= observed_at))
);


--
-- Name: etf_nav_observation_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.etf_nav_observation ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.etf_nav_observation_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: exchange_rate_export_schedule; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.exchange_rate_export_schedule (
    id bigint NOT NULL,
    owner_user_id bigint NOT NULL,
    enabled boolean DEFAULT false NOT NULL,
    run_hour integer DEFAULT 8 NOT NULL,
    run_minute integer DEFAULT 0 NOT NULL,
    output_subpath character varying(255) DEFAULT 'input'::character varying NOT NULL,
    range_months integer,
    last_run_date date,
    last_run_at timestamp without time zone,
    last_run_status character varying(500),
    updated_at timestamp without time zone,
    gdrive_enabled boolean DEFAULT false NOT NULL,
    gdrive_subpath character varying(512),
    gdrive_last_run_at timestamp without time zone,
    gdrive_last_status character varying(512),
    CONSTRAINT ck_exchange_rate_export_schedule_hour CHECK (((run_hour >= 0) AND (run_hour <= 23))),
    CONSTRAINT ck_exchange_rate_export_schedule_minute CHECK (((run_minute >= 0) AND (run_minute <= 59))),
    CONSTRAINT ck_exchange_rate_export_schedule_range CHECK (((range_months IS NULL) OR ((range_months >= 1) AND (range_months <= 120))))
);


--
-- Name: exchange_rate_export_schedule_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.exchange_rate_export_schedule_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: exchange_rate_export_schedule_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.exchange_rate_export_schedule_id_seq OWNED BY public.exchange_rate_export_schedule.id;


--
-- Name: exchange_rate_export_schedule_time; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.exchange_rate_export_schedule_time (
    id bigint NOT NULL,
    schedule_id bigint NOT NULL,
    run_hour integer NOT NULL,
    run_minute integer NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    last_run_date date,
    last_run_at timestamp without time zone,
    last_run_status character varying(500),
    updated_at timestamp without time zone,
    CONSTRAINT ck_exchange_rate_export_schedule_time_hour CHECK (((run_hour >= 0) AND (run_hour <= 23))),
    CONSTRAINT ck_exchange_rate_export_schedule_time_minute CHECK (((run_minute >= 0) AND (run_minute <= 59)))
);


--
-- Name: exchange_rate_export_schedule_time_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.exchange_rate_export_schedule_time_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: exchange_rate_export_schedule_time_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.exchange_rate_export_schedule_time_id_seq OWNED BY public.exchange_rate_export_schedule_time.id;


--
-- Name: exchange_rate_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.exchange_rate_history (
    id bigint NOT NULL,
    buy_rate numeric(10,4),
    currency character varying(10) NOT NULL,
    rate_date date NOT NULL,
    sell_rate numeric(10,4)
);


--
-- Name: exchange_rate_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.exchange_rate_history ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.exchange_rate_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: export_schedule_setting; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.export_schedule_setting (
    id bigint NOT NULL,
    owner_user_id bigint NOT NULL,
    enabled boolean DEFAULT false NOT NULL,
    run_hour integer DEFAULT 8 NOT NULL,
    run_minute integer DEFAULT 0 NOT NULL,
    output_subpath character varying(255) DEFAULT 'input'::character varying NOT NULL,
    last_run_date date,
    last_run_at timestamp without time zone,
    last_run_status character varying(500),
    updated_at timestamp without time zone,
    gdrive_enabled boolean DEFAULT false NOT NULL,
    gdrive_subpath character varying(512),
    gdrive_last_run_at timestamp without time zone,
    gdrive_last_status character varying(512),
    CONSTRAINT ck_export_schedule_hour CHECK (((run_hour >= 0) AND (run_hour <= 23))),
    CONSTRAINT ck_export_schedule_minute CHECK (((run_minute >= 0) AND (run_minute <= 59)))
);


--
-- Name: COLUMN export_schedule_setting.gdrive_enabled; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.export_schedule_setting.gdrive_enabled IS '是否在本機檔寫成功後額外上傳一份副本到 Google Drive。false（預設）＝只寫本機，行為與 Requirement 51 之前完全相同。僅「主要管理者」（ADMIN_EMAIL）可啟用——rclone remote 全機只有一份且綁定特定 Google 帳號，若他人啟用，其財務報表會被上傳到該帳號。';


--
-- Name: COLUMN export_schedule_setting.gdrive_subpath; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.export_schedule_setting.gdrive_subpath IS 'Google Drive 上的相對子路徑（基底為 rclone remote，名稱由環境變數 GDRIVE_OUTPUT_REMOTE 指定）。啟用時必填。驗證：不得以 / 開頭、不得含 .. 路徑段、不得含冒號（會被 rclone 解讀為切換 remote）。';


--
-- Name: COLUMN export_schedule_setting.gdrive_last_run_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.export_schedule_setting.gdrive_last_run_at IS '上次 Drive 上傳的判斷時間（含成功／失敗／跳過，非僅成功）。與 last_run_at 分離：本機成功而 Drive 失敗是正常且必須可分辨的狀態。';


--
-- Name: COLUMN export_schedule_setting.gdrive_last_status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.export_schedule_setting.gdrive_last_status IS '上次 Drive 上傳結果，截斷至 512 字元內。成功記落點與檔案大小；rclone 非零退出記「失敗：…」；逾時記「逾時（N 秒）：Drive 端可能已完成」（逾時的判準是行程未在時限內 exit，不是檔案沒上去——實測發生過狀態記失敗但 Drive 上檔案完整的假失敗）；已啟用但未實際上傳記「跳過：…原因」。';


--
-- Name: export_schedule_setting_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.export_schedule_setting_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: export_schedule_setting_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.export_schedule_setting_id_seq OWNED BY public.export_schedule_setting.id;


--
-- Name: export_schedule_time; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.export_schedule_time (
    id bigint NOT NULL,
    schedule_id bigint NOT NULL,
    run_hour integer NOT NULL,
    run_minute integer NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    last_run_date date,
    last_run_at timestamp without time zone,
    last_run_status character varying(500),
    updated_at timestamp without time zone,
    CONSTRAINT ck_export_schedule_time_hour CHECK (((run_hour >= 0) AND (run_hour <= 23))),
    CONSTRAINT ck_export_schedule_time_minute CHECK (((run_minute >= 0) AND (run_minute <= 59)))
);


--
-- Name: export_schedule_time_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.export_schedule_time_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: export_schedule_time_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.export_schedule_time_id_seq OWNED BY public.export_schedule_time.id;


--
-- Name: foreign_stock_daily_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.foreign_stock_daily_history (
    stock_code character varying(16) NOT NULL,
    trading_date date NOT NULL,
    close_point numeric(18,4) NOT NULL
);


--
-- Name: fubon_etf_holdings_snapshot; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.fubon_etf_holdings_snapshot (
    etf_stock_code character varying(20) NOT NULL,
    market character varying(10) DEFAULT '台股'::character varying NOT NULL,
    fetched_at timestamp without time zone NOT NULL,
    success boolean NOT NULL,
    reason character varying(50),
    raw_response_json jsonb,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_fubon_etf_holdings_snapshot_market CHECK (((market)::text = '台股'::text))
);


--
-- Name: fubon_historical_daily_candle; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.fubon_historical_daily_candle (
    stock_code character varying(20) NOT NULL,
    market character varying(20) NOT NULL,
    provider character varying(32) NOT NULL,
    trading_date date NOT NULL,
    exchange character varying(10) NOT NULL,
    source_market character varying(20),
    open numeric(30,10) NOT NULL,
    high numeric(30,10) NOT NULL,
    low numeric(30,10) NOT NULL,
    close numeric(30,10) NOT NULL,
    volume bigint NOT NULL,
    turnover numeric(30,10) NOT NULL,
    price_change numeric(30,10),
    observed_at timestamp with time zone NOT NULL,
    schema_version integer NOT NULL,
    canonical_payload jsonb NOT NULL,
    payload_hash character(64) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_fubon_historical_daily_candle_ohlc CHECK (((high >= open) AND (high >= close) AND (open >= low) AND (close >= low))),
    CONSTRAINT fubon_historical_daily_candle_close_check CHECK ((close > (0)::numeric)),
    CONSTRAINT fubon_historical_daily_candle_exchange_check CHECK (((exchange)::text = ANY ((ARRAY['TWSE'::character varying, 'TPEx'::character varying, 'ESB'::character varying])::text[]))),
    CONSTRAINT fubon_historical_daily_candle_high_check CHECK ((high > (0)::numeric)),
    CONSTRAINT fubon_historical_daily_candle_low_check CHECK ((low > (0)::numeric)),
    CONSTRAINT fubon_historical_daily_candle_market_check CHECK (((market)::text = '台股'::text)),
    CONSTRAINT fubon_historical_daily_candle_open_check CHECK ((open > (0)::numeric)),
    CONSTRAINT fubon_historical_daily_candle_payload_hash_check CHECK ((payload_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT fubon_historical_daily_candle_provider_check CHECK (((provider)::text = 'FUBON_SDK'::text)),
    CONSTRAINT fubon_historical_daily_candle_schema_version_check CHECK ((schema_version = 1)),
    CONSTRAINT fubon_historical_daily_candle_turnover_check CHECK ((turnover >= (0)::numeric)),
    CONSTRAINT fubon_historical_daily_candle_volume_check CHECK ((volume >= 0))
);


--
-- Name: fubon_intraday_candle; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.fubon_intraday_candle (
    stock_code character varying(20) NOT NULL,
    market character varying(20) NOT NULL,
    provider character varying(32) NOT NULL,
    timeframe smallint NOT NULL,
    candle_at timestamp with time zone NOT NULL,
    source_date date NOT NULL,
    exchange character varying(20) NOT NULL,
    open numeric(20,10) NOT NULL,
    high numeric(20,10) NOT NULL,
    low numeric(20,10) NOT NULL,
    close numeric(20,10) NOT NULL,
    average numeric(20,10) NOT NULL,
    volume bigint NOT NULL,
    observed_at timestamp with time zone NOT NULL,
    content_hash character(64) NOT NULL,
    CONSTRAINT ck_fubon_intraday_candle_exchange CHECK (((exchange)::text = ANY ((ARRAY['TWSE'::character varying, 'TPEx'::character varying])::text[]))),
    CONSTRAINT ck_fubon_intraday_candle_hash CHECK ((content_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT ck_fubon_intraday_candle_identity CHECK ((((market)::text = '台股'::text) AND ((provider)::text = 'FUBON_SDK'::text) AND (timeframe = 1))),
    CONSTRAINT ck_fubon_intraday_candle_minute CHECK ((date_trunc('minute'::text, candle_at) = candle_at)),
    CONSTRAINT ck_fubon_intraday_candle_prices CHECK (((open > (0)::numeric) AND (high > (0)::numeric) AND (low > (0)::numeric) AND (close > (0)::numeric) AND (average > (0)::numeric) AND (high >= open) AND (high >= close) AND (open >= low) AND (close >= low) AND (average >= low) AND (average <= high))),
    CONSTRAINT ck_fubon_intraday_candle_source_day CHECK ((((candle_at AT TIME ZONE 'Asia/Taipei'::text))::date = source_date)),
    CONSTRAINT ck_fubon_intraday_candle_volume CHECK ((volume >= 0))
);


--
-- Name: fubon_stock_basic_info; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.fubon_stock_basic_info (
    stock_code character varying(20) NOT NULL,
    market character varying(20) NOT NULL,
    provider character varying(32) NOT NULL,
    source_date date NOT NULL,
    exchange character varying(20) NOT NULL,
    instrument_type character varying(20) NOT NULL,
    source_name character varying(100) NOT NULL,
    industry character varying(100),
    security_type character varying(64),
    source_market character varying(64),
    price_limit_up numeric(20,10),
    price_limit_down numeric(20,10),
    trading_eligible boolean,
    trading_status character varying(64),
    matching_interval integer,
    board_lot integer,
    currency character varying(10),
    observed_at timestamp with time zone NOT NULL,
    content_hash character(64) NOT NULL,
    CONSTRAINT ck_fubon_stock_basic_info_exchange CHECK ((((exchange)::text = ANY ((ARRAY['TWSE'::character varying, 'TPEx'::character varying])::text[])) AND ((instrument_type)::text = 'EQUITY'::text))),
    CONSTRAINT ck_fubon_stock_basic_info_hash CHECK ((content_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT ck_fubon_stock_basic_info_identity CHECK ((((market)::text = '台股'::text) AND ((provider)::text = 'FUBON_SDK'::text))),
    CONSTRAINT ck_fubon_stock_basic_info_interval CHECK (((matching_interval IS NULL) OR (matching_interval >= 0))),
    CONSTRAINT ck_fubon_stock_basic_info_lot CHECK (((board_lot IS NULL) OR (board_lot > 0))),
    CONSTRAINT ck_fubon_stock_basic_info_price CHECK ((((price_limit_up IS NULL) OR (price_limit_up > (0)::numeric)) AND ((price_limit_down IS NULL) OR (price_limit_down > (0)::numeric))))
);


--
-- Name: fubon_taiex_index_latest; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.fubon_taiex_index_latest (
    index_code character varying(20) NOT NULL,
    provider_symbol character varying(64) NOT NULL,
    exchange character varying(20) NOT NULL,
    trading_date date NOT NULL,
    provider_updated_at timestamp with time zone NOT NULL,
    index_point numeric(30,10) NOT NULL,
    source character varying(32) NOT NULL,
    CONSTRAINT ck_fubon_taiex_index_code CHECK (((index_code)::text = '0000'::text)),
    CONSTRAINT ck_fubon_taiex_index_exchange CHECK (((exchange)::text = 'TWSE'::text)),
    CONSTRAINT ck_fubon_taiex_index_point CHECK ((index_point > (0)::numeric)),
    CONSTRAINT ck_fubon_taiex_index_provider_date CHECK ((((provider_updated_at AT TIME ZONE 'Asia/Taipei'::text))::date = trading_date)),
    CONSTRAINT ck_fubon_taiex_index_source CHECK (((source)::text = 'FUBON_INDICES'::text))
);


--
-- Name: fubon_technical_capture_member; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.fubon_technical_capture_member (
    capture_id uuid NOT NULL,
    profile_id character varying(32) NOT NULL,
    stock_code character varying(20) NOT NULL,
    market character varying(20) NOT NULL,
    provider character varying(32) NOT NULL,
    timeframe character(1) NOT NULL,
    source_date date NOT NULL,
    content_hash character(64) NOT NULL,
    observed_at timestamp with time zone NOT NULL,
    previous_source_date date,
    previous_content_hash character(64),
    CONSTRAINT ck_fubon_technical_capture_member_hash CHECK ((content_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT ck_fubon_technical_capture_member_identity CHECK ((((market)::text = '台股'::text) AND ((provider)::text = 'FUBON_SDK'::text))),
    CONSTRAINT ck_fubon_technical_capture_member_previous_hash CHECK (((previous_content_hash IS NULL) OR (previous_content_hash ~ '^[0-9a-f]{64}$'::text))),
    CONSTRAINT ck_fubon_technical_capture_member_previous_kdj CHECK ((((previous_source_date IS NULL) AND (previous_content_hash IS NULL)) OR (((profile_id)::text = ANY ((ARRAY['kdj_d_9_3_3'::character varying, 'kdj_w_9_3_3'::character varying])::text[])) AND (previous_source_date < source_date)))),
    CONSTRAINT ck_fubon_technical_capture_member_previous_pair CHECK (((previous_source_date IS NULL) = (previous_content_hash IS NULL))),
    CONSTRAINT ck_fubon_technical_capture_member_profile CHECK (((((profile_id)::text = ANY ((ARRAY['sma_d_5'::character varying, 'sma_d_10'::character varying, 'sma_d_20'::character varying, 'sma_d_60'::character varying, 'sma_d_240'::character varying])::text[])) AND (timeframe = 'D'::bpchar)) OR (((profile_id)::text = ANY ((ARRAY['rsi_d_5'::character varying, 'rsi_d_10'::character varying, 'kdj_d_9_3_3'::character varying, 'macd_d_12_26_9'::character varying, 'bb_d_20'::character varying])::text[])) AND (timeframe = 'D'::bpchar)) OR (((profile_id)::text = ANY ((ARRAY['sma_w_5'::character varying, 'sma_w_10'::character varying, 'sma_w_20'::character varying, 'rsi_w_5'::character varying, 'rsi_w_10'::character varying, 'kdj_w_9_3_3'::character varying, 'macd_w_12_26_9'::character varying])::text[])) AND (timeframe = 'W'::bpchar))))
);


--
-- Name: fubon_tw_live_quote_response; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.fubon_tw_live_quote_response (
    stock_code character varying(20) NOT NULL,
    market character varying(20) NOT NULL,
    received_at timestamp with time zone NOT NULL,
    batch_id character varying(64) NOT NULL,
    counters jsonb NOT NULL,
    response_row jsonb NOT NULL,
    CONSTRAINT ck_fubon_tw_live_quote_response_batch_id CHECK ((btrim((batch_id)::text) <> ''::text)),
    CONSTRAINT ck_fubon_tw_live_quote_response_counters_object CHECK ((jsonb_typeof(counters) = 'object'::text)),
    CONSTRAINT ck_fubon_tw_live_quote_response_market CHECK (((market)::text = '台股'::text)),
    CONSTRAINT ck_fubon_tw_live_quote_response_row_object CHECK ((jsonb_typeof(response_row) = 'object'::text))
);


--
-- Name: fund_class_override; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.fund_class_override (
    fund_name character varying(150) NOT NULL,
    asset_class character varying(20),
    stock_style character varying(20),
    bond_term character varying(20)
);


--
-- Name: fund_dividend_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.fund_dividend_history (
    id bigint NOT NULL,
    fund_code character varying(20) NOT NULL,
    base_date date NOT NULL,
    amount numeric(20,6) NOT NULL,
    currency character varying(3),
    frequency character varying(20),
    fetched_at timestamp without time zone NOT NULL
);


--
-- Name: fund_dividend_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.fund_dividend_history ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.fund_dividend_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: fund_holding; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.fund_holding (
    id bigint NOT NULL,
    current_value numeric(20,2) NOT NULL,
    fund_code character varying(20),
    fund_name character varying(100) NOT NULL,
    investment_amount numeric(20,2) NOT NULL,
    snapshot_id bigint NOT NULL,
    bank_id bigint,
    units numeric(20,4),
    estimated_dividend numeric(20,2)
);


--
-- Name: fund_holding_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.fund_holding ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.fund_holding_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: fund_master; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.fund_master (
    fund_code character varying(20) NOT NULL,
    fund_name character varying(150) NOT NULL,
    bank_id bigint,
    currency character varying(3) NOT NULL,
    site character varying(10) NOT NULL,
    fundclear_org_code character varying(20) NOT NULL,
    fundclear_fund_code character varying(20) NOT NULL,
    fundclear_class_code character varying(20) NOT NULL,
    active boolean DEFAULT true NOT NULL
);


--
-- Name: fund_nav; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.fund_nav (
    id bigint NOT NULL,
    fund_code character varying(20) NOT NULL,
    nav_date date NOT NULL,
    nav numeric(20,6) NOT NULL,
    source character varying(20) NOT NULL,
    fetched_at timestamp without time zone NOT NULL
);


--
-- Name: fund_nav_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.fund_nav ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.fund_nav_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: index_export_schedule; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.index_export_schedule (
    id bigint NOT NULL,
    owner_user_id bigint NOT NULL,
    enabled boolean DEFAULT false NOT NULL,
    output_subpath character varying(255) DEFAULT 'input'::character varying NOT NULL,
    range_months integer,
    updated_at timestamp without time zone,
    gdrive_enabled boolean DEFAULT false NOT NULL,
    gdrive_subpath character varying(512),
    gdrive_last_run_at timestamp without time zone,
    gdrive_last_status character varying(512),
    CONSTRAINT ck_index_export_schedule_range CHECK (((range_months IS NULL) OR ((range_months >= 1) AND (range_months <= 120))))
);


--
-- Name: index_export_schedule_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.index_export_schedule_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: index_export_schedule_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.index_export_schedule_id_seq OWNED BY public.index_export_schedule.id;


--
-- Name: index_export_schedule_time; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.index_export_schedule_time (
    id bigint NOT NULL,
    schedule_id bigint NOT NULL,
    run_hour integer DEFAULT 8 NOT NULL,
    run_minute integer DEFAULT 0 NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    last_run_date date,
    last_run_at timestamp without time zone,
    last_run_status character varying(500),
    updated_at timestamp without time zone,
    CONSTRAINT ck_index_export_schedule_time_hour CHECK (((run_hour >= 0) AND (run_hour <= 23))),
    CONSTRAINT ck_index_export_schedule_time_minute CHECK (((run_minute >= 0) AND (run_minute <= 59)))
);


--
-- Name: index_export_schedule_time_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.index_export_schedule_time_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: index_export_schedule_time_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.index_export_schedule_time_id_seq OWNED BY public.index_export_schedule_time.id;


--
-- Name: index_export_schedule_time_market; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.index_export_schedule_time_market (
    schedule_time_id bigint NOT NULL,
    market character varying(16) NOT NULL
);


--
-- Name: industry_monthly_revenue; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.industry_monthly_revenue (
    id bigint NOT NULL,
    industry_name character varying(100) NOT NULL,
    revenue_year integer NOT NULL,
    revenue_month integer NOT NULL,
    revenue numeric(24,0),
    prior_year_revenue numeric(24,0),
    revenue_yoy_pct numeric(12,4),
    company_count integer NOT NULL,
    provider character varying(20) NOT NULL,
    source_urls jsonb DEFAULT '[]'::jsonb NOT NULL,
    source_available_at timestamp with time zone NOT NULL,
    availability_basis character varying(20) NOT NULL,
    observed_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT industry_monthly_revenue_revenue_month_check CHECK (((revenue_month >= 1) AND (revenue_month <= 12)))
);


--
-- Name: industry_monthly_revenue_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.industry_monthly_revenue_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: industry_monthly_revenue_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.industry_monthly_revenue_id_seq OWNED BY public.industry_monthly_revenue.id;


--
-- Name: investment_planned_expense; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.investment_planned_expense (
    id bigint NOT NULL,
    owner_user_id bigint NOT NULL,
    expense_date date NOT NULL,
    name character varying(100),
    amount numeric(20,2) NOT NULL,
    updated_at timestamp with time zone NOT NULL
);


--
-- Name: investment_planned_expense_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.investment_planned_expense_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: investment_planned_expense_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.investment_planned_expense_id_seq OWNED BY public.investment_planned_expense.id;


--
-- Name: investment_profile; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.investment_profile (
    id bigint NOT NULL,
    owner_user_id bigint NOT NULL,
    goals character varying(300),
    risk_tolerance character varying(20),
    expected_annual_return character varying(20),
    updated_at timestamp with time zone NOT NULL,
    retirement_date date,
    birth_date date,
    labor_insurance_monthly numeric(20,2),
    labor_insurance_start_date date,
    labor_pension_lump_sum numeric(20,2),
    labor_pension_claim_date date,
    assumed_annual_inflation_rate numeric(5,2),
    accumulation_annual_return_rate numeric(5,2),
    retirement_annual_return_rate numeric(5,2),
    retirement_annual_expense numeric(20,2),
    long_term_care_annual_expense numeric(20,2),
    long_term_care_start_age integer,
    pre_retirement_annual_salary numeric(20,2),
    pre_retirement_annual_expense numeric(20,2)
);


--
-- Name: investment_profile_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.investment_profile_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: investment_profile_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.investment_profile_id_seq OWNED BY public.investment_profile.id;


--
-- Name: japan_gdp_per_capita_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.japan_gdp_per_capita_history (
    year integer NOT NULL,
    gdp_usd numeric(12,2) NOT NULL,
    real_gdp_growth_rate numeric(8,4)
);


--
-- Name: korea_gdp_per_capita_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.korea_gdp_per_capita_history (
    year integer NOT NULL,
    gdp_usd numeric(12,2) NOT NULL,
    real_gdp_growth_rate numeric(8,4)
);


--
-- Name: market_analysis_send_time; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.market_analysis_send_time (
    id bigint NOT NULL,
    send_time time without time zone NOT NULL,
    active boolean DEFAULT true NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: market_analysis_send_time_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.market_analysis_send_time ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.market_analysis_send_time_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: market_analysis_setting; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.market_analysis_setting (
    id integer DEFAULT 1 NOT NULL,
    model character varying(64) DEFAULT 'claude-opus-5'::character varying NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    effort character varying(16) DEFAULT 'medium'::character varying NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    engine character varying(16) DEFAULT 'local'::character varying NOT NULL,
    CONSTRAINT market_analysis_setting_single_row CHECK ((id = 1))
);


--
-- Name: market_type; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.market_type (
    id bigint NOT NULL,
    active boolean NOT NULL,
    code character varying(20) NOT NULL,
    display_name character varying(50) NOT NULL,
    sort_order integer NOT NULL
);


--
-- Name: market_type_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.market_type ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.market_type_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: news_headline; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.news_headline (
    id bigint NOT NULL,
    title character varying(500) NOT NULL,
    source character varying(100) NOT NULL,
    url character varying(1024) NOT NULL,
    category character varying(32) NOT NULL,
    region character varying(16),
    summary text,
    published_at timestamp with time zone NOT NULL,
    fetched_at timestamp with time zone DEFAULT now() NOT NULL,
    dedupe_key character varying(64) NOT NULL
);


--
-- Name: news_headline_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.news_headline_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: news_headline_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.news_headline_id_seq OWNED BY public.news_headline.id;


--
-- Name: notification_recipient; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.notification_recipient (
    id bigint NOT NULL,
    email character varying(255) NOT NULL,
    active boolean DEFAULT true NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    owner_user_id bigint NOT NULL,
    receive_market_analysis boolean DEFAULT true NOT NULL,
    add_to_calendar boolean DEFAULT false NOT NULL
);


--
-- Name: notification_recipient_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.notification_recipient ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.notification_recipient_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: payment_account; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.payment_account (
    id bigint NOT NULL,
    category_id bigint NOT NULL,
    item_name character varying(100) NOT NULL,
    payment_account character varying(100),
    note character varying(255),
    sort_order integer DEFAULT 0 NOT NULL,
    owner_user_id bigint NOT NULL
);


--
-- Name: payment_account_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.payment_account ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.payment_account_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: payment_category; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.payment_category (
    id bigint NOT NULL,
    code character varying(30) NOT NULL,
    display_name character varying(50) NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    active boolean DEFAULT true NOT NULL
);


--
-- Name: payment_category_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.payment_category ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.payment_category_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: portfolio_advice; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.portfolio_advice (
    id bigint NOT NULL,
    owner_user_id bigint NOT NULL,
    status character varying(20) NOT NULL,
    model character varying(64),
    created_at timestamp with time zone NOT NULL,
    completed_at timestamp with time zone,
    error_message character varying(1000),
    age integer,
    investment_horizon_years integer,
    monthly_investment numeric(20,2),
    goals character varying(300),
    risk_tolerance character varying(20),
    expected_annual_return character varying(20),
    based_on_snapshot_id bigint,
    based_on_snapshot_date date,
    based_on_total_assets numeric(20,2),
    raw_response text,
    result_json text
);


--
-- Name: portfolio_advice_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.portfolio_advice_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: portfolio_advice_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.portfolio_advice_id_seq OWNED BY public.portfolio_advice.id;


--
-- Name: portfolio_advice_setting; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.portfolio_advice_setting (
    id integer NOT NULL,
    model character varying(64) NOT NULL,
    effort character varying(16) NOT NULL,
    web_search_max_uses integer NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    engine character varying(16) DEFAULT 'local'::character varying NOT NULL
);


--
-- Name: realized_gain; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.realized_gain (
    id bigint NOT NULL,
    asset_code character varying(20),
    asset_name character varying(50) NOT NULL,
    investment_cost numeric(20,2) NOT NULL,
    market character varying(20),
    proceeds numeric(20,2) NOT NULL,
    sale_price numeric(15,4),
    shares numeric(15,5),
    trade_date date NOT NULL,
    broker character varying(30),
    currency character varying(10),
    exchange_rate numeric(10,4),
    owner_user_id bigint NOT NULL,
    sync_source character varying(32),
    sync_fingerprint character(64),
    sync_occurrence integer,
    CONSTRAINT ck_realized_gain_sync_provenance CHECK ((((sync_source IS NULL) AND (sync_fingerprint IS NULL) AND (sync_occurrence IS NULL)) OR (((sync_source)::text = 'FUBON_REALIZED_GAIN_SYNC'::text) AND (sync_fingerprint IS NOT NULL) AND (sync_fingerprint ~ '^[0-9a-f]{64}$'::text) AND (sync_occurrence IS NOT NULL) AND (sync_occurrence >= 1)))),
    CONSTRAINT realized_gain_market_check CHECK (((market)::text = ANY (ARRAY[('台股'::character varying)::text, ('美股'::character varying)::text])))
);


--
-- Name: realized_gain_export_schedule; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.realized_gain_export_schedule (
    id bigint NOT NULL,
    owner_user_id bigint NOT NULL,
    enabled boolean DEFAULT false NOT NULL,
    run_hour integer DEFAULT 8 NOT NULL,
    run_minute integer DEFAULT 0 NOT NULL,
    output_subpath character varying(255) DEFAULT 'input'::character varying NOT NULL,
    last_run_date date,
    last_run_at timestamp without time zone,
    last_run_status character varying(500),
    updated_at timestamp without time zone,
    gdrive_enabled boolean DEFAULT false NOT NULL,
    gdrive_subpath character varying(512),
    gdrive_last_run_at timestamp without time zone,
    gdrive_last_status character varying(512),
    CONSTRAINT ck_rg_export_schedule_hour CHECK (((run_hour >= 0) AND (run_hour <= 23))),
    CONSTRAINT ck_rg_export_schedule_minute CHECK (((run_minute >= 0) AND (run_minute <= 59)))
);


--
-- Name: realized_gain_export_schedule_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.realized_gain_export_schedule_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: realized_gain_export_schedule_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.realized_gain_export_schedule_id_seq OWNED BY public.realized_gain_export_schedule.id;


--
-- Name: realized_gain_export_schedule_time; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.realized_gain_export_schedule_time (
    id bigint NOT NULL,
    schedule_id bigint NOT NULL,
    run_hour integer NOT NULL,
    run_minute integer NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    last_run_date date,
    last_run_at timestamp without time zone,
    last_run_status character varying(500),
    updated_at timestamp without time zone,
    CONSTRAINT ck_rg_export_schedule_time_hour CHECK (((run_hour >= 0) AND (run_hour <= 23))),
    CONSTRAINT ck_rg_export_schedule_time_minute CHECK (((run_minute >= 0) AND (run_minute <= 59)))
);


--
-- Name: realized_gain_export_schedule_time_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.realized_gain_export_schedule_time_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: realized_gain_export_schedule_time_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.realized_gain_export_schedule_time_id_seq OWNED BY public.realized_gain_export_schedule_time.id;


--
-- Name: realized_gain_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.realized_gain ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.realized_gain_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: stock; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stock (
    code character varying(20) NOT NULL,
    market character varying(20) NOT NULL,
    name character varying(100) NOT NULL,
    asset_class character varying(20),
    stock_style character varying(20),
    bond_term character varying(20),
    underlying_currency character varying(10)
);


--
-- Name: COLUMN stock.underlying_currency; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.stock.underlying_currency IS '底層資產幣別（TWD/USD/GBP…）。null 時依 market 推斷：美股→USD、英股→GBP、台股→TWD。台幣計價但持有外幣資產的 ETF 必須顯式標記，否則匯率因子會誤判為無曝險。誤判修正路徑為 DBA 手動 UPDATE（刻意不建設定頁）。';


--
-- Name: stock_alert; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stock_alert (
    id bigint NOT NULL,
    stock_code character varying(20) NOT NULL,
    market character varying(20) NOT NULL,
    alert_type character varying(50) NOT NULL,
    threshold numeric(10,4) NOT NULL,
    active boolean DEFAULT true NOT NULL,
    last_triggered_at timestamp without time zone,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    last_triggered_price numeric(16,4),
    last_triggered_kd_value numeric(10,4),
    last_triggered_ma_value numeric(16,4),
    last_triggered_d_value numeric(10,4),
    display_order integer DEFAULT 0 NOT NULL,
    ma_period integer,
    owner_user_id bigint NOT NULL,
    group_id bigint
);


--
-- Name: stock_alert_export_setting; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stock_alert_export_setting (
    id bigint NOT NULL,
    owner_user_id bigint NOT NULL,
    enabled boolean DEFAULT false NOT NULL,
    output_subpath character varying(512) DEFAULT 'input'::character varying NOT NULL,
    last_run_at timestamp without time zone,
    last_run_status character varying(512),
    gdrive_enabled boolean DEFAULT false NOT NULL,
    gdrive_subpath character varying(512),
    gdrive_last_run_at timestamp without time zone,
    gdrive_last_status character varying(512),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: stock_alert_export_setting_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.stock_alert_export_setting_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: stock_alert_export_setting_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.stock_alert_export_setting_id_seq OWNED BY public.stock_alert_export_setting.id;


--
-- Name: stock_alert_group; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stock_alert_group (
    id bigint NOT NULL,
    owner_user_id bigint NOT NULL,
    stock_code character varying(20) NOT NULL,
    market character varying(20) NOT NULL,
    active boolean DEFAULT true NOT NULL,
    display_order integer DEFAULT 0 NOT NULL,
    last_triggered_at timestamp without time zone,
    last_triggered_price numeric(16,4),
    last_triggered_ma_value numeric(16,4),
    last_triggered_kd_value numeric(10,4),
    last_triggered_d_value numeric(10,4),
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_at timestamp without time zone DEFAULT now() NOT NULL
);


--
-- Name: stock_alert_group_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.stock_alert_group_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: stock_alert_group_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.stock_alert_group_id_seq OWNED BY public.stock_alert_group.id;


--
-- Name: stock_alert_group_recipient; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stock_alert_group_recipient (
    id bigint NOT NULL,
    group_id bigint NOT NULL,
    recipient_id bigint NOT NULL
);


--
-- Name: stock_alert_group_recipient_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.stock_alert_group_recipient ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.stock_alert_group_recipient_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: stock_alert_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.stock_alert_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: stock_alert_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.stock_alert_id_seq OWNED BY public.stock_alert.id;


--
-- Name: stock_alert_recipient; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stock_alert_recipient (
    id bigint NOT NULL,
    alert_id bigint NOT NULL,
    recipient_id bigint NOT NULL
);


--
-- Name: stock_alert_recipient_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.stock_alert_recipient ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.stock_alert_recipient_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: stock_alert_trigger; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stock_alert_trigger (
    id bigint NOT NULL,
    alert_id bigint,
    stock_code character varying(20) NOT NULL,
    market character varying(20) NOT NULL,
    triggered_at timestamp without time zone NOT NULL,
    price numeric(10,4),
    monthly_ma numeric(10,4),
    quarterly_ma numeric(10,4),
    annual_ma numeric(10,4),
    k_value numeric(10,4),
    d_value numeric(10,4),
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    group_id bigint,
    CONSTRAINT ck_sat_alert_xor_group CHECK (((alert_id IS NOT NULL) <> (group_id IS NOT NULL)))
);


--
-- Name: stock_alert_trigger_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.stock_alert_trigger_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: stock_alert_trigger_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.stock_alert_trigger_id_seq OWNED BY public.stock_alert_trigger.id;


--
-- Name: stock_dividend_fetch_attempt; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stock_dividend_fetch_attempt (
    id bigint NOT NULL,
    stock_code character varying(32),
    market character varying(16),
    provider character varying(128),
    observed_at timestamp with time zone NOT NULL,
    status character varying(32) NOT NULL,
    scope_from date,
    scope_to date,
    source_urls text,
    error_reason text,
    snapshot_id bigint
);


--
-- Name: stock_dividend_fetch_attempt_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.stock_dividend_fetch_attempt_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: stock_dividend_fetch_attempt_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.stock_dividend_fetch_attempt_id_seq OWNED BY public.stock_dividend_fetch_attempt.id;


--
-- Name: stock_dividend_fetch_observation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stock_dividend_fetch_observation (
    id bigint NOT NULL,
    snapshot_id bigint NOT NULL,
    observed_at timestamp with time zone NOT NULL,
    status character varying(16) NOT NULL,
    complete boolean NOT NULL,
    scope_from date NOT NULL,
    scope_to date NOT NULL,
    source_available_at timestamp with time zone,
    error_reason text
);


--
-- Name: stock_dividend_fetch_observation_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.stock_dividend_fetch_observation_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: stock_dividend_fetch_observation_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.stock_dividend_fetch_observation_id_seq OWNED BY public.stock_dividend_fetch_observation.id;


--
-- Name: stock_dividend_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stock_dividend_history (
    id bigint NOT NULL,
    stock_code character varying(20) NOT NULL,
    market character varying(20) NOT NULL,
    year integer NOT NULL,
    cash_dividend numeric(15,6),
    stock_dividend numeric(15,6),
    ex_dividend_date date,
    yield_pct numeric(10,4),
    cash_payment_date date,
    stock_payment_date date,
    fill_days integer,
    previous_close numeric(15,4),
    source character varying(128),
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    event_status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    event_key character varying(64),
    ex_rights_date date
);


--
-- Name: COLUMN stock_dividend_history.ex_rights_date; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.stock_dividend_history.ex_rights_date IS '除權日（FinMind StockExDividendTradingDate）。只配現金的事件為 null；不得以 ex_dividend_date 頂替。回補完成前，既有列的 ex_dividend_date 可能存的其實是除權日。';


--
-- Name: stock_dividend_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.stock_dividend_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: stock_dividend_history_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.stock_dividend_history_id_seq OWNED BY public.stock_dividend_history.id;


--
-- Name: stock_dividend_snapshot; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stock_dividend_snapshot (
    id bigint NOT NULL,
    stock_code character varying(32) NOT NULL,
    market character varying(16) NOT NULL,
    provider character varying(128) NOT NULL,
    scope_from date NOT NULL,
    scope_to date NOT NULL,
    source_url text,
    content_hash character varying(64) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: stock_dividend_snapshot_event; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stock_dividend_snapshot_event (
    id bigint NOT NULL,
    snapshot_id bigint NOT NULL,
    event_key character varying(64) NOT NULL,
    year integer,
    ex_dividend_date date,
    cash_dividend numeric(18,6),
    stock_dividend numeric(18,6),
    cash_payment_date date,
    stock_payment_date date,
    source_available_at timestamp with time zone,
    ex_rights_date date
);


--
-- Name: COLUMN stock_dividend_snapshot_event.ex_rights_date; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.stock_dividend_snapshot_event.ex_rights_date IS '除權日（FinMind StockExDividendTradingDate）。只配現金的事件為 null；不得以 ex_dividend_date 頂替。';


--
-- Name: stock_dividend_snapshot_event_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.stock_dividend_snapshot_event_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: stock_dividend_snapshot_event_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.stock_dividend_snapshot_event_id_seq OWNED BY public.stock_dividend_snapshot_event.id;


--
-- Name: stock_dividend_snapshot_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.stock_dividend_snapshot_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: stock_dividend_snapshot_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.stock_dividend_snapshot_id_seq OWNED BY public.stock_dividend_snapshot.id;


--
-- Name: stock_financial_quarter; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stock_financial_quarter (
    id bigint NOT NULL,
    stock_code character varying(20) NOT NULL,
    market character varying(20) NOT NULL,
    fiscal_year integer NOT NULL,
    fiscal_quarter integer NOT NULL,
    eps numeric(12,4),
    net_income_parent bigint,
    equity_parent bigint,
    provider character varying(20) NOT NULL,
    source_urls jsonb DEFAULT '[]'::jsonb NOT NULL,
    source_available_at timestamp with time zone NOT NULL,
    availability_basis character varying(20) NOT NULL,
    observed_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT stock_financial_quarter_fiscal_quarter_check CHECK (((fiscal_quarter >= 1) AND (fiscal_quarter <= 4)))
);


--
-- Name: stock_financial_quarter_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.stock_financial_quarter_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: stock_financial_quarter_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.stock_financial_quarter_id_seq OWNED BY public.stock_financial_quarter.id;


--
-- Name: stock_holding; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stock_holding (
    id bigint NOT NULL,
    currency character varying(3),
    current_value numeric(20,2) NOT NULL,
    dividend_rate numeric(10,6),
    estimated_dividend numeric(20,4),
    investment_cost numeric(20,2) NOT NULL,
    market character varying(20) NOT NULL,
    original_currency_value numeric(20,4),
    shares numeric(15,5) NOT NULL,
    stock_code character varying(20) NOT NULL,
    snapshot_id bigint NOT NULL,
    broker_id bigint,
    transaction_type character varying(10),
    transaction_date date,
    transaction_exchange_rate numeric(10,4),
    display_order integer
);


--
-- Name: stock_holding_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.stock_holding ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.stock_holding_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: stock_intraday_order_book; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stock_intraday_order_book (
    stock_code character varying(20) NOT NULL,
    market character varying(20) NOT NULL,
    trading_date date NOT NULL,
    source_updated_at timestamp with time zone NOT NULL,
    fetched_at timestamp with time zone NOT NULL,
    source character varying(32) NOT NULL,
    market_status character varying(16) NOT NULL,
    actual_price numeric(20,10) NOT NULL,
    previous_close numeric(20,10) NOT NULL,
    open_price numeric(20,10),
    high_price numeric(20,10),
    low_price numeric(20,10),
    average_price numeric(20,10),
    turnover_yi numeric(20,10),
    volume_lots bigint,
    previous_volume_lots bigint,
    inner_volume_lots bigint,
    outer_volume_lots bigint,
    canonical_revision bigint NOT NULL,
    CONSTRAINT ck_stock_intraday_order_book_canonical_revision CHECK ((canonical_revision > 0)),
    CONSTRAINT ck_stock_intraday_order_book_date CHECK ((((source_updated_at AT TIME ZONE 'Asia/Taipei'::text))::date = trading_date)),
    CONSTRAINT ck_stock_intraday_order_book_lots CHECK ((((volume_lots IS NULL) OR (volume_lots >= 0)) AND ((previous_volume_lots IS NULL) OR (previous_volume_lots >= 0)) AND ((inner_volume_lots IS NULL) OR (inner_volume_lots >= 0)) AND ((outer_volume_lots IS NULL) OR (outer_volume_lots >= 0)))),
    CONSTRAINT ck_stock_intraday_order_book_market CHECK (((market)::text = '台股'::text)),
    CONSTRAINT ck_stock_intraday_order_book_optional_price CHECK ((((open_price IS NULL) OR (open_price > (0)::numeric)) AND ((high_price IS NULL) OR (high_price > (0)::numeric)) AND ((low_price IS NULL) OR (low_price > (0)::numeric)) AND ((average_price IS NULL) OR (average_price >= (0)::numeric)) AND ((turnover_yi IS NULL) OR (turnover_yi >= (0)::numeric)))),
    CONSTRAINT ck_stock_intraday_order_book_required_price CHECK (((actual_price > (0)::numeric) AND (previous_close > (0)::numeric))),
    CONSTRAINT ck_stock_intraday_order_book_source CHECK (((source)::text = ANY ((ARRAY['FUBON_BOOKS'::character varying, 'YAHOO_TW'::character varying])::text[]))),
    CONSTRAINT ck_stock_intraday_order_book_status CHECK (((market_status)::text = ANY ((ARRAY['OPEN'::character varying, 'CLOSED'::character varying, 'UNKNOWN'::character varying])::text[])))
);


--
-- Name: stock_intraday_order_book_level; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stock_intraday_order_book_level (
    stock_code character varying(20) NOT NULL,
    market character varying(20) NOT NULL,
    level smallint NOT NULL,
    bid_price numeric(20,10) NOT NULL,
    bid_volume_lots bigint NOT NULL,
    ask_price numeric(20,10) NOT NULL,
    ask_volume_lots bigint NOT NULL,
    CONSTRAINT ck_stock_intraday_order_book_level_lots CHECK (((bid_volume_lots > 0) AND (ask_volume_lots > 0))),
    CONSTRAINT ck_stock_intraday_order_book_level_number CHECK (((level >= 1) AND (level <= 5))),
    CONSTRAINT ck_stock_intraday_order_book_level_price CHECK (((bid_price > (0)::numeric) AND (ask_price > (0)::numeric)))
);


--
-- Name: stock_intraday_quote; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stock_intraday_quote (
    stock_code character varying(20) NOT NULL,
    market character varying(20) NOT NULL,
    trading_date date NOT NULL,
    provider_updated_at timestamp with time zone NOT NULL,
    source character varying(64) NOT NULL,
    actual_price numeric(20,10) NOT NULL,
    previous_close numeric(20,10),
    open_price numeric(20,10),
    high_price numeric(20,10),
    low_price numeric(20,10),
    buy_price numeric(20,10),
    sell_price numeric(20,10),
    volume bigint,
    CONSTRAINT ck_stock_intraday_quote_actual_positive CHECK ((actual_price > (0)::numeric)),
    CONSTRAINT ck_stock_intraday_quote_date CHECK ((((provider_updated_at AT TIME ZONE 'Asia/Taipei'::text))::date = trading_date)),
    CONSTRAINT ck_stock_intraday_quote_optional_positive CHECK ((((previous_close IS NULL) OR (previous_close > (0)::numeric)) AND ((open_price IS NULL) OR (open_price > (0)::numeric)) AND ((high_price IS NULL) OR (high_price > (0)::numeric)) AND ((low_price IS NULL) OR (low_price > (0)::numeric)) AND ((buy_price IS NULL) OR (buy_price > (0)::numeric)) AND ((sell_price IS NULL) OR (sell_price > (0)::numeric)))),
    CONSTRAINT ck_stock_intraday_quote_volume CHECK (((volume IS NULL) OR (volume >= 0)))
);


--
-- Name: stock_monthly_revenue; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stock_monthly_revenue (
    id bigint NOT NULL,
    stock_code character varying(20) NOT NULL,
    market character varying(20) NOT NULL,
    revenue_year integer NOT NULL,
    revenue_month integer NOT NULL,
    industry_name character varying(100),
    revenue bigint,
    prior_year_revenue bigint,
    revenue_yoy_pct numeric(12,4),
    provider character varying(20) NOT NULL,
    source_urls jsonb DEFAULT '[]'::jsonb NOT NULL,
    source_available_at timestamp with time zone NOT NULL,
    availability_basis character varying(20) NOT NULL,
    observed_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT stock_monthly_revenue_revenue_month_check CHECK (((revenue_month >= 1) AND (revenue_month <= 12)))
);


--
-- Name: stock_monthly_revenue_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.stock_monthly_revenue_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: stock_monthly_revenue_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.stock_monthly_revenue_id_seq OWNED BY public.stock_monthly_revenue.id;


--
-- Name: stock_price_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stock_price_history (
    id bigint NOT NULL,
    close_price numeric(15,4) NOT NULL,
    high_price numeric(15,4),
    low_price numeric(15,4),
    market character varying(20) NOT NULL,
    open_price numeric(15,4),
    stock_code character varying(20) NOT NULL,
    trading_date date NOT NULL,
    volume bigint,
    close_source character varying(64),
    CONSTRAINT ck_sph_close_price_positive CHECK ((close_price > (0)::numeric))
);


--
-- Name: stock_price_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.stock_price_history ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.stock_price_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: stock_style; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stock_style (
    id bigint NOT NULL,
    code character varying(20) NOT NULL,
    display_name character varying(50) NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    active boolean DEFAULT true NOT NULL,
    dividend_threshold numeric(6,4)
);


--
-- Name: stock_style_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.stock_style ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.stock_style_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: stock_technical_indicator; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stock_technical_indicator (
    stock_code character varying(20) NOT NULL,
    market character varying(20) NOT NULL,
    provider character varying(32) NOT NULL,
    timeframe character(1) NOT NULL,
    profile_id character varying(32) NOT NULL,
    source_date date NOT NULL,
    indicator_kind character varying(16) NOT NULL,
    parameters jsonb NOT NULL,
    payload jsonb NOT NULL,
    source_timestamp timestamp with time zone,
    capture_id uuid NOT NULL,
    observed_at timestamp with time zone NOT NULL,
    first_observed_at timestamp with time zone NOT NULL,
    content_hash character(64) NOT NULL,
    CONSTRAINT ck_stock_technical_indicator_hash CHECK ((content_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT ck_stock_technical_indicator_identity CHECK ((((market)::text = '台股'::text) AND ((provider)::text = 'FUBON_SDK'::text))),
    CONSTRAINT ck_stock_technical_indicator_json CHECK (((jsonb_typeof(parameters) = 'object'::text) AND (jsonb_typeof(payload) = 'object'::text))),
    CONSTRAINT ck_stock_technical_indicator_observed CHECK ((first_observed_at <= observed_at)),
    CONSTRAINT ck_stock_technical_indicator_profile CHECK (((((profile_id)::text = ANY ((ARRAY['sma_d_5'::character varying, 'sma_d_10'::character varying, 'sma_d_20'::character varying, 'sma_d_60'::character varying, 'sma_d_240'::character varying])::text[])) AND (timeframe = 'D'::bpchar) AND ((indicator_kind)::text = 'SMA'::text)) OR (((profile_id)::text = ANY ((ARRAY['rsi_d_5'::character varying, 'rsi_d_10'::character varying])::text[])) AND (timeframe = 'D'::bpchar) AND ((indicator_kind)::text = 'RSI'::text)) OR (((profile_id)::text = 'kdj_d_9_3_3'::text) AND (timeframe = 'D'::bpchar) AND ((indicator_kind)::text = 'KDJ'::text)) OR (((profile_id)::text = 'macd_d_12_26_9'::text) AND (timeframe = 'D'::bpchar) AND ((indicator_kind)::text = 'MACD'::text)) OR (((profile_id)::text = 'bb_d_20'::text) AND (timeframe = 'D'::bpchar) AND ((indicator_kind)::text = 'BBANDS'::text)) OR (((profile_id)::text = ANY ((ARRAY['sma_w_5'::character varying, 'sma_w_10'::character varying, 'sma_w_20'::character varying])::text[])) AND (timeframe = 'W'::bpchar) AND ((indicator_kind)::text = 'SMA'::text)) OR (((profile_id)::text = ANY ((ARRAY['rsi_w_5'::character varying, 'rsi_w_10'::character varying])::text[])) AND (timeframe = 'W'::bpchar) AND ((indicator_kind)::text = 'RSI'::text)) OR (((profile_id)::text = 'kdj_w_9_3_3'::text) AND (timeframe = 'W'::bpchar) AND ((indicator_kind)::text = 'KDJ'::text)) OR (((profile_id)::text = 'macd_w_12_26_9'::text) AND (timeframe = 'W'::bpchar) AND ((indicator_kind)::text = 'MACD'::text)))),
    CONSTRAINT ck_stock_technical_indicator_timeframe CHECK ((timeframe = ANY (ARRAY['D'::bpchar, 'W'::bpchar])))
);


--
-- Name: stock_valuation_daily; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.stock_valuation_daily (
    id bigint NOT NULL,
    stock_code character varying(20) NOT NULL,
    market character varying(20) NOT NULL,
    trading_date date NOT NULL,
    pe_ratio numeric(12,4),
    pb_ratio numeric(12,4),
    dividend_yield_pct numeric(12,4),
    pe_loss_flag boolean,
    provider character varying(20) NOT NULL,
    source_urls jsonb DEFAULT '[]'::jsonb NOT NULL,
    source_available_at timestamp with time zone NOT NULL,
    availability_basis character varying(20) NOT NULL,
    observed_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: stock_valuation_daily_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.stock_valuation_daily_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: stock_valuation_daily_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.stock_valuation_daily_id_seq OWNED BY public.stock_valuation_daily.id;


--
-- Name: taiwan_gdp_per_capita_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.taiwan_gdp_per_capita_history (
    year integer NOT NULL,
    gdp_usd numeric(12,2) NOT NULL,
    real_gdp_growth_rate numeric(8,4)
);


--
-- Name: trading_calendar_export_schedule; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.trading_calendar_export_schedule (
    id bigint NOT NULL,
    owner_user_id bigint NOT NULL,
    enabled boolean DEFAULT false NOT NULL,
    run_hour integer DEFAULT 8 NOT NULL,
    run_minute integer DEFAULT 0 NOT NULL,
    format character varying(10) DEFAULT 'json'::character varying NOT NULL,
    output_subpath character varying(255) DEFAULT 'input'::character varying NOT NULL,
    last_run_date date,
    last_run_at timestamp without time zone,
    last_run_status character varying(500),
    updated_at timestamp without time zone,
    gdrive_enabled boolean DEFAULT false NOT NULL,
    gdrive_subpath character varying(512),
    gdrive_last_run_at timestamp without time zone,
    gdrive_last_status character varying(512),
    CONSTRAINT ck_tc_export_schedule_format CHECK (((format)::text = ANY ((ARRAY['json'::character varying, 'excel'::character varying])::text[]))),
    CONSTRAINT ck_tc_export_schedule_hour CHECK (((run_hour >= 0) AND (run_hour <= 23))),
    CONSTRAINT ck_tc_export_schedule_minute CHECK (((run_minute >= 0) AND (run_minute <= 59)))
);


--
-- Name: trading_calendar_export_schedule_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.trading_calendar_export_schedule_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: trading_calendar_export_schedule_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.trading_calendar_export_schedule_id_seq OWNED BY public.trading_calendar_export_schedule.id;


--
-- Name: trading_radar_export_setting; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.trading_radar_export_setting (
    id bigint NOT NULL,
    owner_user_id bigint NOT NULL,
    output_subpath character varying(512) NOT NULL,
    last_run_at timestamp without time zone,
    last_run_status character varying(500),
    updated_at timestamp without time zone,
    gdrive_enabled boolean DEFAULT false NOT NULL,
    gdrive_subpath character varying(512),
    gdrive_last_run_at timestamp without time zone,
    gdrive_last_status character varying(512),
    blog_enabled boolean DEFAULT false NOT NULL,
    blog_last_post_id character varying(64),
    blog_last_post_url character varying(512),
    blog_last_run_at timestamp without time zone,
    blog_last_status character varying(512)
);


--
-- Name: TABLE trading_radar_export_setting; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.trading_radar_export_setting IS '交易雷達排程匯出的輸出資料夾與上次執行狀態（Requirement 48 追加／Task 231）。一使用者一列。';


--
-- Name: COLUMN trading_radar_export_setting.output_subpath; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.trading_radar_export_setting.output_subpath IS '輸出目錄相對子路徑（相對容器基底 EXPORT_OUTPUT_DIR=/home/steven，docker volume 對映主機家目錄）。只存相對子路徑：絕對路徑不可攜且繞過基底防護，一律於 service 層擋下。';


--
-- Name: trading_radar_export_setting_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.trading_radar_export_setting_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: trading_radar_export_setting_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.trading_radar_export_setting_id_seq OWNED BY public.trading_radar_export_setting.id;


--
-- Name: trading_radar_export_time; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.trading_radar_export_time (
    id bigint NOT NULL,
    owner_user_id bigint NOT NULL,
    run_hour integer NOT NULL,
    run_minute integer NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    last_run_date date,
    updated_at timestamp without time zone,
    CONSTRAINT ck_tr_export_time_hour CHECK (((run_hour >= 0) AND (run_hour <= 23))),
    CONSTRAINT ck_tr_export_time_minute CHECK (((run_minute >= 0) AND (run_minute <= 59)))
);


--
-- Name: TABLE trading_radar_export_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.trading_radar_export_time IS '交易雷達排程匯出的執行時間點（Requirement 48 追加／Task 231）。一列一時間點、per owner；比照公開資訊爬蟲可設定多個。';


--
-- Name: COLUMN trading_radar_export_time.last_run_date; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.trading_radar_export_time.last_run_date IS '當日 guard，per 時間點（不是 per owner，否則同日多時間點只會跑第一個）。成功或失敗都設為當日，避免命中分鐘後每 poll 重試整天。';


--
-- Name: trading_radar_export_time_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.trading_radar_export_time_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: trading_radar_export_time_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.trading_radar_export_time_id_seq OWNED BY public.trading_radar_export_time.id;


--
-- Name: trading_radar_notification_recipient; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.trading_radar_notification_recipient (
    id bigint NOT NULL,
    setting_id bigint NOT NULL,
    recipient_id bigint NOT NULL
);


--
-- Name: trading_radar_notification_recipient_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.trading_radar_notification_recipient ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.trading_radar_notification_recipient_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: trading_radar_notification_setting; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.trading_radar_notification_setting (
    id bigint NOT NULL,
    owner_user_id bigint NOT NULL,
    stock_code character varying(20) NOT NULL,
    market character varying(20) NOT NULL,
    active boolean DEFAULT true NOT NULL,
    initialized boolean DEFAULT false NOT NULL,
    last_action character varying(50),
    last_counter_trend_state character varying(50),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    rule_version character varying(30),
    action_policy_version character varying(40),
    technical_source_version character varying(40)
);


--
-- Name: trading_radar_notification_setting_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.trading_radar_notification_setting ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.trading_radar_notification_setting_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: trading_radar_notification_state; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.trading_radar_notification_state (
    id bigint NOT NULL,
    setting_id bigint NOT NULL,
    state_type character varying(30) NOT NULL,
    state_code character varying(50) NOT NULL,
    last_notified_at timestamp with time zone,
    CONSTRAINT ck_trn_state_type CHECK (((state_type)::text = ANY ((ARRAY['ACTION'::character varying, 'COUNTER_TREND'::character varying])::text[])))
);


--
-- Name: trading_radar_notification_state_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.trading_radar_notification_state ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.trading_radar_notification_state_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: transit_fund_type; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.transit_fund_type (
    id bigint NOT NULL,
    code character varying(30) NOT NULL,
    display_name character varying(50) NOT NULL,
    payable boolean DEFAULT true NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    active boolean DEFAULT true NOT NULL
);


--
-- Name: transit_fund_type_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.transit_fund_type ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.transit_fund_type_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: treasury_yield_batch; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.treasury_yield_batch (
    id bigint NOT NULL,
    curve_date date NOT NULL,
    provider character varying(32) NOT NULL,
    source_url text NOT NULL,
    available_at timestamp with time zone NOT NULL,
    availability_basis character varying(40) NOT NULL,
    fetched_at timestamp with time zone DEFAULT now() NOT NULL,
    complete boolean NOT NULL,
    content_hash character varying(64) NOT NULL,
    CONSTRAINT ck_treasury_yield_batch_hash_length CHECK ((char_length((content_hash)::text) = 64)),
    CONSTRAINT ck_treasury_yield_batch_provider CHECK (((provider)::text = ANY ((ARRAY['US_TREASURY'::character varying, 'YAHOO_PROXY'::character varying])::text[])))
);


--
-- Name: treasury_yield_batch_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.treasury_yield_batch ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.treasury_yield_batch_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: treasury_yield_daily; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.treasury_yield_daily (
    batch_id bigint NOT NULL,
    tenor character varying(8) NOT NULL,
    yield_percent numeric(10,4) NOT NULL,
    source_url text NOT NULL,
    CONSTRAINT ck_treasury_yield_daily_tenor CHECK (((tenor)::text = ANY ((ARRAY['M3'::character varying, 'Y5'::character varying, 'Y10'::character varying, 'Y30'::character varying])::text[]))),
    CONSTRAINT ck_treasury_yield_daily_value CHECK (((yield_percent >= (0)::numeric) AND (yield_percent <= (100)::numeric)))
);


--
-- Name: tw_market_closure; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tw_market_closure (
    closure_date date NOT NULL,
    reason character varying(200) NOT NULL,
    source character varying(32) NOT NULL,
    raw_status character varying(500),
    detected_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: twse_index_daily_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.twse_index_daily_history (
    trading_date date NOT NULL,
    close_point numeric(12,2) NOT NULL,
    open_point numeric(12,2),
    high_point numeric(12,2),
    low_point numeric(12,2),
    close_point_tr numeric(12,2),
    trade_volume bigint,
    trade_value numeric(20,0)
);


--
-- Name: twse_index_year_end_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.twse_index_year_end_history (
    year integer NOT NULL,
    close_point numeric(12,2) NOT NULL
);


--
-- Name: twse_institutional_daily; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.twse_institutional_daily (
    id bigint NOT NULL,
    trading_date date,
    foreign_net numeric(20,2),
    trust_net numeric(20,2),
    dealer_net numeric(20,2),
    total_net numeric(20,2),
    provider character varying(64) NOT NULL,
    source_url text,
    observed_at timestamp with time zone NOT NULL,
    source_available_at timestamp with time zone,
    availability_basis character varying(64) NOT NULL,
    status character varying(20) NOT NULL,
    error_reason text,
    CONSTRAINT ck_twse_institutional_available_values CHECK ((((status)::text <> 'AVAILABLE'::text) OR ((trading_date IS NOT NULL) AND (foreign_net IS NOT NULL) AND (trust_net IS NOT NULL) AND (dealer_net IS NOT NULL) AND (total_net IS NOT NULL) AND (source_url IS NOT NULL)))),
    CONSTRAINT ck_twse_institutional_status CHECK (((status)::text = ANY ((ARRAY['AVAILABLE'::character varying, 'UNAVAILABLE'::character varying])::text[])))
);


--
-- Name: twse_institutional_daily_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.twse_institutional_daily ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.twse_institutional_daily_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: us_index_daily_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.us_index_daily_history (
    index_code character varying(16) NOT NULL,
    trading_date date NOT NULL,
    open_point numeric(14,4),
    high_point numeric(14,4),
    low_point numeric(14,4),
    close_point numeric(14,4) NOT NULL,
    volume bigint
);


--
-- Name: app_feature id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_feature ALTER COLUMN id SET DEFAULT nextval('public.app_feature_id_seq'::regclass);


--
-- Name: asset_transaction id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.asset_transaction ALTER COLUMN id SET DEFAULT nextval('public.asset_transaction_id_seq'::regclass);


--
-- Name: asset_transaction_export_schedule id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.asset_transaction_export_schedule ALTER COLUMN id SET DEFAULT nextval('public.asset_transaction_export_schedule_id_seq'::regclass);


--
-- Name: backup_record id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.backup_record ALTER COLUMN id SET DEFAULT nextval('public.backup_record_id_seq'::regclass);


--
-- Name: commodity_export_schedule id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.commodity_export_schedule ALTER COLUMN id SET DEFAULT nextval('public.commodity_export_schedule_id_seq'::regclass);


--
-- Name: commodity_export_schedule_time id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.commodity_export_schedule_time ALTER COLUMN id SET DEFAULT nextval('public.commodity_export_schedule_time_id_seq'::regclass);


--
-- Name: crawler_export_setting id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.crawler_export_setting ALTER COLUMN id SET DEFAULT nextval('public.crawler_export_setting_id_seq'::regclass);


--
-- Name: crawler_schedule id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.crawler_schedule ALTER COLUMN id SET DEFAULT nextval('public.crawler_schedule_id_seq'::regclass);


--
-- Name: exchange_rate_export_schedule id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.exchange_rate_export_schedule ALTER COLUMN id SET DEFAULT nextval('public.exchange_rate_export_schedule_id_seq'::regclass);


--
-- Name: exchange_rate_export_schedule_time id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.exchange_rate_export_schedule_time ALTER COLUMN id SET DEFAULT nextval('public.exchange_rate_export_schedule_time_id_seq'::regclass);


--
-- Name: export_schedule_setting id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.export_schedule_setting ALTER COLUMN id SET DEFAULT nextval('public.export_schedule_setting_id_seq'::regclass);


--
-- Name: export_schedule_time id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.export_schedule_time ALTER COLUMN id SET DEFAULT nextval('public.export_schedule_time_id_seq'::regclass);


--
-- Name: index_export_schedule id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.index_export_schedule ALTER COLUMN id SET DEFAULT nextval('public.index_export_schedule_id_seq'::regclass);


--
-- Name: index_export_schedule_time id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.index_export_schedule_time ALTER COLUMN id SET DEFAULT nextval('public.index_export_schedule_time_id_seq'::regclass);


--
-- Name: industry_monthly_revenue id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.industry_monthly_revenue ALTER COLUMN id SET DEFAULT nextval('public.industry_monthly_revenue_id_seq'::regclass);


--
-- Name: investment_planned_expense id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.investment_planned_expense ALTER COLUMN id SET DEFAULT nextval('public.investment_planned_expense_id_seq'::regclass);


--
-- Name: investment_profile id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.investment_profile ALTER COLUMN id SET DEFAULT nextval('public.investment_profile_id_seq'::regclass);


--
-- Name: news_headline id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.news_headline ALTER COLUMN id SET DEFAULT nextval('public.news_headline_id_seq'::regclass);


--
-- Name: portfolio_advice id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_advice ALTER COLUMN id SET DEFAULT nextval('public.portfolio_advice_id_seq'::regclass);


--
-- Name: realized_gain_export_schedule id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.realized_gain_export_schedule ALTER COLUMN id SET DEFAULT nextval('public.realized_gain_export_schedule_id_seq'::regclass);


--
-- Name: realized_gain_export_schedule_time id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.realized_gain_export_schedule_time ALTER COLUMN id SET DEFAULT nextval('public.realized_gain_export_schedule_time_id_seq'::regclass);


--
-- Name: stock_alert id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_alert ALTER COLUMN id SET DEFAULT nextval('public.stock_alert_id_seq'::regclass);


--
-- Name: stock_alert_export_setting id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_alert_export_setting ALTER COLUMN id SET DEFAULT nextval('public.stock_alert_export_setting_id_seq'::regclass);


--
-- Name: stock_alert_group id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_alert_group ALTER COLUMN id SET DEFAULT nextval('public.stock_alert_group_id_seq'::regclass);


--
-- Name: stock_alert_trigger id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_alert_trigger ALTER COLUMN id SET DEFAULT nextval('public.stock_alert_trigger_id_seq'::regclass);


--
-- Name: stock_dividend_fetch_attempt id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_dividend_fetch_attempt ALTER COLUMN id SET DEFAULT nextval('public.stock_dividend_fetch_attempt_id_seq'::regclass);


--
-- Name: stock_dividend_fetch_observation id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_dividend_fetch_observation ALTER COLUMN id SET DEFAULT nextval('public.stock_dividend_fetch_observation_id_seq'::regclass);


--
-- Name: stock_dividend_history id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_dividend_history ALTER COLUMN id SET DEFAULT nextval('public.stock_dividend_history_id_seq'::regclass);


--
-- Name: stock_dividend_snapshot id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_dividend_snapshot ALTER COLUMN id SET DEFAULT nextval('public.stock_dividend_snapshot_id_seq'::regclass);


--
-- Name: stock_dividend_snapshot_event id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_dividend_snapshot_event ALTER COLUMN id SET DEFAULT nextval('public.stock_dividend_snapshot_event_id_seq'::regclass);


--
-- Name: stock_financial_quarter id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_financial_quarter ALTER COLUMN id SET DEFAULT nextval('public.stock_financial_quarter_id_seq'::regclass);


--
-- Name: stock_monthly_revenue id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_monthly_revenue ALTER COLUMN id SET DEFAULT nextval('public.stock_monthly_revenue_id_seq'::regclass);


--
-- Name: stock_valuation_daily id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_valuation_daily ALTER COLUMN id SET DEFAULT nextval('public.stock_valuation_daily_id_seq'::regclass);


--
-- Name: trading_calendar_export_schedule id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trading_calendar_export_schedule ALTER COLUMN id SET DEFAULT nextval('public.trading_calendar_export_schedule_id_seq'::regclass);


--
-- Name: trading_radar_export_setting id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trading_radar_export_setting ALTER COLUMN id SET DEFAULT nextval('public.trading_radar_export_setting_id_seq'::regclass);


--
-- Name: trading_radar_export_time id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trading_radar_export_time ALTER COLUMN id SET DEFAULT nextval('public.trading_radar_export_time_id_seq'::regclass);


--
-- Name: api_error_log_operation api_error_log_operation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.api_error_log_operation
    ADD CONSTRAINT api_error_log_operation_pkey PRIMARY KEY (source, operation_key);


--
-- Name: api_error_log_operation api_error_log_operation_source_operation_key_operation_labe_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.api_error_log_operation
    ADD CONSTRAINT api_error_log_operation_source_operation_key_operation_labe_key UNIQUE (source, operation_key, operation_label);


--
-- Name: api_error_log api_error_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.api_error_log
    ADD CONSTRAINT api_error_log_pkey PRIMARY KEY (id);


--
-- Name: app_feature app_feature_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_feature
    ADD CONSTRAINT app_feature_code_key UNIQUE (code);


--
-- Name: app_feature app_feature_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_feature
    ADD CONSTRAINT app_feature_pkey PRIMARY KEY (id);


--
-- Name: app_user app_user_email_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_user
    ADD CONSTRAINT app_user_email_key UNIQUE (email);


--
-- Name: app_user app_user_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.app_user
    ADD CONSTRAINT app_user_pkey PRIMARY KEY (id);


--
-- Name: asset_class asset_class_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.asset_class
    ADD CONSTRAINT asset_class_code_key UNIQUE (code);


--
-- Name: asset_class asset_class_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.asset_class
    ADD CONSTRAINT asset_class_pkey PRIMARY KEY (id);


--
-- Name: asset_snapshot asset_snapshot_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.asset_snapshot
    ADD CONSTRAINT asset_snapshot_pkey PRIMARY KEY (id);


--
-- Name: asset_transaction_export_schedule asset_transaction_export_schedule_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.asset_transaction_export_schedule
    ADD CONSTRAINT asset_transaction_export_schedule_pkey PRIMARY KEY (id);


--
-- Name: asset_transaction asset_transaction_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.asset_transaction
    ADD CONSTRAINT asset_transaction_pkey PRIMARY KEY (id);


--
-- Name: backup_record backup_record_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.backup_record
    ADD CONSTRAINT backup_record_pkey PRIMARY KEY (id);


--
-- Name: backup_record backup_record_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.backup_record
    ADD CONSTRAINT backup_record_unique UNIQUE (folder, filename);


--
-- Name: backup_setting backup_setting_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.backup_setting
    ADD CONSTRAINT backup_setting_pkey PRIMARY KEY (id);


--
-- Name: bank_deposit bank_deposit_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.bank_deposit
    ADD CONSTRAINT bank_deposit_pkey PRIMARY KEY (id);


--
-- Name: bank bank_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.bank
    ADD CONSTRAINT bank_pkey PRIMARY KEY (id);


--
-- Name: blog_publish_credential blog_publish_credential_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.blog_publish_credential
    ADD CONSTRAINT blog_publish_credential_pkey PRIMARY KEY (id);


--
-- Name: bond_term bond_term_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.bond_term
    ADD CONSTRAINT bond_term_code_key UNIQUE (code);


--
-- Name: bond_term bond_term_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.bond_term
    ADD CONSTRAINT bond_term_pkey PRIMARY KEY (id);


--
-- Name: broker broker_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.broker
    ADD CONSTRAINT broker_pkey PRIMARY KEY (id);


--
-- Name: commodity_export_schedule commodity_export_schedule_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.commodity_export_schedule
    ADD CONSTRAINT commodity_export_schedule_pkey PRIMARY KEY (id);


--
-- Name: commodity_export_schedule_time commodity_export_schedule_time_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.commodity_export_schedule_time
    ADD CONSTRAINT commodity_export_schedule_time_pkey PRIMARY KEY (id);


--
-- Name: commodity_price_history commodity_price_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.commodity_price_history
    ADD CONSTRAINT commodity_price_history_pkey PRIMARY KEY (id);


--
-- Name: crawler_export_setting crawler_export_setting_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.crawler_export_setting
    ADD CONSTRAINT crawler_export_setting_pkey PRIMARY KEY (id);


--
-- Name: crawler_schedule crawler_schedule_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.crawler_schedule
    ADD CONSTRAINT crawler_schedule_pkey PRIMARY KEY (id);


--
-- Name: daily_market_analysis daily_market_analysis_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.daily_market_analysis
    ADD CONSTRAINT daily_market_analysis_pkey PRIMARY KEY (analysis_date);


--
-- Name: databasechangeloglock databasechangeloglock_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.databasechangeloglock
    ADD CONSTRAINT databasechangeloglock_pkey PRIMARY KEY (id);


--
-- Name: deposit_type deposit_type_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.deposit_type
    ADD CONSTRAINT deposit_type_pkey PRIMARY KEY (id);


--
-- Name: etf_nav_history etf_nav_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.etf_nav_history
    ADD CONSTRAINT etf_nav_history_pkey PRIMARY KEY (id);


--
-- Name: etf_nav_observation etf_nav_observation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.etf_nav_observation
    ADD CONSTRAINT etf_nav_observation_pkey PRIMARY KEY (id);


--
-- Name: exchange_rate_export_schedule exchange_rate_export_schedule_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.exchange_rate_export_schedule
    ADD CONSTRAINT exchange_rate_export_schedule_pkey PRIMARY KEY (id);


--
-- Name: exchange_rate_export_schedule_time exchange_rate_export_schedule_time_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.exchange_rate_export_schedule_time
    ADD CONSTRAINT exchange_rate_export_schedule_time_pkey PRIMARY KEY (id);


--
-- Name: exchange_rate_history exchange_rate_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.exchange_rate_history
    ADD CONSTRAINT exchange_rate_history_pkey PRIMARY KEY (id);


--
-- Name: export_schedule_setting export_schedule_setting_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.export_schedule_setting
    ADD CONSTRAINT export_schedule_setting_pkey PRIMARY KEY (id);


--
-- Name: export_schedule_time export_schedule_time_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.export_schedule_time
    ADD CONSTRAINT export_schedule_time_pkey PRIMARY KEY (id);


--
-- Name: foreign_stock_daily_history foreign_stock_daily_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.foreign_stock_daily_history
    ADD CONSTRAINT foreign_stock_daily_history_pkey PRIMARY KEY (stock_code, trading_date);


--
-- Name: fubon_historical_daily_candle fubon_historical_daily_candle_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fubon_historical_daily_candle
    ADD CONSTRAINT fubon_historical_daily_candle_pkey PRIMARY KEY (stock_code, market, trading_date);


--
-- Name: fubon_taiex_index_latest fubon_taiex_index_latest_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fubon_taiex_index_latest
    ADD CONSTRAINT fubon_taiex_index_latest_pkey PRIMARY KEY (index_code);


--
-- Name: fund_class_override fund_class_override_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fund_class_override
    ADD CONSTRAINT fund_class_override_pkey PRIMARY KEY (fund_name);


--
-- Name: fund_dividend_history fund_dividend_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fund_dividend_history
    ADD CONSTRAINT fund_dividend_history_pkey PRIMARY KEY (id);


--
-- Name: fund_holding fund_holding_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fund_holding
    ADD CONSTRAINT fund_holding_pkey PRIMARY KEY (id);


--
-- Name: fund_master fund_master_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fund_master
    ADD CONSTRAINT fund_master_pkey PRIMARY KEY (fund_code);


--
-- Name: fund_nav fund_nav_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fund_nav
    ADD CONSTRAINT fund_nav_pkey PRIMARY KEY (id);


--
-- Name: index_export_schedule index_export_schedule_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.index_export_schedule
    ADD CONSTRAINT index_export_schedule_pkey PRIMARY KEY (id);


--
-- Name: index_export_schedule_time_market index_export_schedule_time_market_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.index_export_schedule_time_market
    ADD CONSTRAINT index_export_schedule_time_market_pkey PRIMARY KEY (schedule_time_id, market);


--
-- Name: index_export_schedule_time index_export_schedule_time_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.index_export_schedule_time
    ADD CONSTRAINT index_export_schedule_time_pkey PRIMARY KEY (id);


--
-- Name: industry_monthly_revenue industry_monthly_revenue_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.industry_monthly_revenue
    ADD CONSTRAINT industry_monthly_revenue_pkey PRIMARY KEY (id);


--
-- Name: investment_planned_expense investment_planned_expense_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.investment_planned_expense
    ADD CONSTRAINT investment_planned_expense_pkey PRIMARY KEY (id);


--
-- Name: investment_profile investment_profile_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.investment_profile
    ADD CONSTRAINT investment_profile_pkey PRIMARY KEY (id);


--
-- Name: japan_gdp_per_capita_history japan_gdp_per_capita_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.japan_gdp_per_capita_history
    ADD CONSTRAINT japan_gdp_per_capita_history_pkey PRIMARY KEY (year);


--
-- Name: korea_gdp_per_capita_history korea_gdp_per_capita_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.korea_gdp_per_capita_history
    ADD CONSTRAINT korea_gdp_per_capita_history_pkey PRIMARY KEY (year);


--
-- Name: market_analysis_send_time market_analysis_send_time_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.market_analysis_send_time
    ADD CONSTRAINT market_analysis_send_time_pkey PRIMARY KEY (id);


--
-- Name: market_analysis_send_time market_analysis_send_time_send_time_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.market_analysis_send_time
    ADD CONSTRAINT market_analysis_send_time_send_time_key UNIQUE (send_time);


--
-- Name: market_analysis_setting market_analysis_setting_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.market_analysis_setting
    ADD CONSTRAINT market_analysis_setting_pkey PRIMARY KEY (id);


--
-- Name: market_type market_type_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.market_type
    ADD CONSTRAINT market_type_pkey PRIMARY KEY (id);


--
-- Name: news_headline news_headline_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.news_headline
    ADD CONSTRAINT news_headline_pkey PRIMARY KEY (id);


--
-- Name: notification_recipient notification_recipient_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notification_recipient
    ADD CONSTRAINT notification_recipient_pkey PRIMARY KEY (id);


--
-- Name: payment_account payment_account_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_account
    ADD CONSTRAINT payment_account_pkey PRIMARY KEY (id);


--
-- Name: payment_category payment_category_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_category
    ADD CONSTRAINT payment_category_code_key UNIQUE (code);


--
-- Name: payment_category payment_category_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_category
    ADD CONSTRAINT payment_category_pkey PRIMARY KEY (id);


--
-- Name: fubon_etf_holdings_snapshot pk_fubon_etf_holdings_snapshot; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fubon_etf_holdings_snapshot
    ADD CONSTRAINT pk_fubon_etf_holdings_snapshot PRIMARY KEY (etf_stock_code);


--
-- Name: fubon_intraday_candle pk_fubon_intraday_candle; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fubon_intraday_candle
    ADD CONSTRAINT pk_fubon_intraday_candle PRIMARY KEY (stock_code, market, provider, timeframe, candle_at);


--
-- Name: fubon_stock_basic_info pk_fubon_stock_basic_info; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fubon_stock_basic_info
    ADD CONSTRAINT pk_fubon_stock_basic_info PRIMARY KEY (stock_code, market, provider);


--
-- Name: fubon_technical_capture_member pk_fubon_technical_capture_member; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fubon_technical_capture_member
    ADD CONSTRAINT pk_fubon_technical_capture_member PRIMARY KEY (capture_id, profile_id);


--
-- Name: fubon_tw_live_quote_response pk_fubon_tw_live_quote_response; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fubon_tw_live_quote_response
    ADD CONSTRAINT pk_fubon_tw_live_quote_response PRIMARY KEY (stock_code, market);


--
-- Name: stock_technical_indicator pk_stock_technical_indicator; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_technical_indicator
    ADD CONSTRAINT pk_stock_technical_indicator PRIMARY KEY (stock_code, market, provider, timeframe, profile_id, source_date);


--
-- Name: portfolio_advice portfolio_advice_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_advice
    ADD CONSTRAINT portfolio_advice_pkey PRIMARY KEY (id);


--
-- Name: portfolio_advice_setting portfolio_advice_setting_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.portfolio_advice_setting
    ADD CONSTRAINT portfolio_advice_setting_pkey PRIMARY KEY (id);


--
-- Name: realized_gain_export_schedule realized_gain_export_schedule_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.realized_gain_export_schedule
    ADD CONSTRAINT realized_gain_export_schedule_pkey PRIMARY KEY (id);


--
-- Name: realized_gain_export_schedule_time realized_gain_export_schedule_time_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.realized_gain_export_schedule_time
    ADD CONSTRAINT realized_gain_export_schedule_time_pkey PRIMARY KEY (id);


--
-- Name: realized_gain realized_gain_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.realized_gain
    ADD CONSTRAINT realized_gain_pkey PRIMARY KEY (id);


--
-- Name: stock_alert_export_setting stock_alert_export_setting_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_alert_export_setting
    ADD CONSTRAINT stock_alert_export_setting_pkey PRIMARY KEY (id);


--
-- Name: stock_alert_group stock_alert_group_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_alert_group
    ADD CONSTRAINT stock_alert_group_pkey PRIMARY KEY (id);


--
-- Name: stock_alert_group_recipient stock_alert_group_recipient_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_alert_group_recipient
    ADD CONSTRAINT stock_alert_group_recipient_pkey PRIMARY KEY (id);


--
-- Name: stock_alert stock_alert_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_alert
    ADD CONSTRAINT stock_alert_pkey PRIMARY KEY (id);


--
-- Name: stock_alert_recipient stock_alert_recipient_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_alert_recipient
    ADD CONSTRAINT stock_alert_recipient_pkey PRIMARY KEY (id);


--
-- Name: stock_alert_trigger stock_alert_trigger_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_alert_trigger
    ADD CONSTRAINT stock_alert_trigger_pkey PRIMARY KEY (id);


--
-- Name: stock_dividend_fetch_attempt stock_dividend_fetch_attempt_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_dividend_fetch_attempt
    ADD CONSTRAINT stock_dividend_fetch_attempt_pkey PRIMARY KEY (id);


--
-- Name: stock_dividend_fetch_observation stock_dividend_fetch_observation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_dividend_fetch_observation
    ADD CONSTRAINT stock_dividend_fetch_observation_pkey PRIMARY KEY (id);


--
-- Name: stock_dividend_fetch_observation stock_dividend_fetch_observation_snapshot_id_observed_at_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_dividend_fetch_observation
    ADD CONSTRAINT stock_dividend_fetch_observation_snapshot_id_observed_at_key UNIQUE (snapshot_id, observed_at);


--
-- Name: stock_dividend_history stock_dividend_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_dividend_history
    ADD CONSTRAINT stock_dividend_history_pkey PRIMARY KEY (id);


--
-- Name: stock_dividend_snapshot_event stock_dividend_snapshot_event_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_dividend_snapshot_event
    ADD CONSTRAINT stock_dividend_snapshot_event_pkey PRIMARY KEY (id);


--
-- Name: stock_dividend_snapshot_event stock_dividend_snapshot_event_snapshot_id_event_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_dividend_snapshot_event
    ADD CONSTRAINT stock_dividend_snapshot_event_snapshot_id_event_key_key UNIQUE (snapshot_id, event_key);


--
-- Name: stock_dividend_snapshot stock_dividend_snapshot_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_dividend_snapshot
    ADD CONSTRAINT stock_dividend_snapshot_pkey PRIMARY KEY (id);


--
-- Name: stock_dividend_snapshot stock_dividend_snapshot_stock_code_market_provider_scope_fr_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_dividend_snapshot
    ADD CONSTRAINT stock_dividend_snapshot_stock_code_market_provider_scope_fr_key UNIQUE (stock_code, market, provider, scope_from, scope_to, content_hash);


--
-- Name: stock_financial_quarter stock_financial_quarter_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_financial_quarter
    ADD CONSTRAINT stock_financial_quarter_pkey PRIMARY KEY (id);


--
-- Name: stock_holding stock_holding_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_holding
    ADD CONSTRAINT stock_holding_pkey PRIMARY KEY (id);


--
-- Name: stock_intraday_order_book_level stock_intraday_order_book_level_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_intraday_order_book_level
    ADD CONSTRAINT stock_intraday_order_book_level_pkey PRIMARY KEY (stock_code, market, level);


--
-- Name: stock_intraday_order_book stock_intraday_order_book_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_intraday_order_book
    ADD CONSTRAINT stock_intraday_order_book_pkey PRIMARY KEY (stock_code, market);


--
-- Name: stock_intraday_quote stock_intraday_quote_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_intraday_quote
    ADD CONSTRAINT stock_intraday_quote_pkey PRIMARY KEY (stock_code, market);


--
-- Name: stock_monthly_revenue stock_monthly_revenue_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_monthly_revenue
    ADD CONSTRAINT stock_monthly_revenue_pkey PRIMARY KEY (id);


--
-- Name: stock stock_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock
    ADD CONSTRAINT stock_pkey PRIMARY KEY (code, market);


--
-- Name: stock_price_history stock_price_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_price_history
    ADD CONSTRAINT stock_price_history_pkey PRIMARY KEY (id);


--
-- Name: stock_style stock_style_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_style
    ADD CONSTRAINT stock_style_code_key UNIQUE (code);


--
-- Name: stock_style stock_style_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_style
    ADD CONSTRAINT stock_style_pkey PRIMARY KEY (id);


--
-- Name: stock_valuation_daily stock_valuation_daily_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_valuation_daily
    ADD CONSTRAINT stock_valuation_daily_pkey PRIMARY KEY (id);


--
-- Name: taiwan_gdp_per_capita_history taiwan_gdp_per_capita_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.taiwan_gdp_per_capita_history
    ADD CONSTRAINT taiwan_gdp_per_capita_history_pkey PRIMARY KEY (year);


--
-- Name: trading_calendar_export_schedule trading_calendar_export_schedule_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trading_calendar_export_schedule
    ADD CONSTRAINT trading_calendar_export_schedule_pkey PRIMARY KEY (id);


--
-- Name: trading_radar_export_setting trading_radar_export_setting_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trading_radar_export_setting
    ADD CONSTRAINT trading_radar_export_setting_pkey PRIMARY KEY (id);


--
-- Name: trading_radar_export_time trading_radar_export_time_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trading_radar_export_time
    ADD CONSTRAINT trading_radar_export_time_pkey PRIMARY KEY (id);


--
-- Name: trading_radar_notification_recipient trading_radar_notification_recipient_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trading_radar_notification_recipient
    ADD CONSTRAINT trading_radar_notification_recipient_pkey PRIMARY KEY (id);


--
-- Name: trading_radar_notification_setting trading_radar_notification_setting_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trading_radar_notification_setting
    ADD CONSTRAINT trading_radar_notification_setting_pkey PRIMARY KEY (id);


--
-- Name: trading_radar_notification_state trading_radar_notification_state_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trading_radar_notification_state
    ADD CONSTRAINT trading_radar_notification_state_pkey PRIMARY KEY (id);


--
-- Name: transit_fund_type transit_fund_type_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transit_fund_type
    ADD CONSTRAINT transit_fund_type_code_key UNIQUE (code);


--
-- Name: transit_fund_type transit_fund_type_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transit_fund_type
    ADD CONSTRAINT transit_fund_type_pkey PRIMARY KEY (id);


--
-- Name: treasury_yield_batch treasury_yield_batch_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.treasury_yield_batch
    ADD CONSTRAINT treasury_yield_batch_pkey PRIMARY KEY (id);


--
-- Name: treasury_yield_daily treasury_yield_daily_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.treasury_yield_daily
    ADD CONSTRAINT treasury_yield_daily_pkey PRIMARY KEY (batch_id, tenor);


--
-- Name: tw_market_closure tw_market_closure_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tw_market_closure
    ADD CONSTRAINT tw_market_closure_pkey PRIMARY KEY (closure_date);


--
-- Name: twse_index_daily_history twse_index_daily_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.twse_index_daily_history
    ADD CONSTRAINT twse_index_daily_history_pkey PRIMARY KEY (trading_date);


--
-- Name: twse_index_year_end_history twse_index_year_end_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.twse_index_year_end_history
    ADD CONSTRAINT twse_index_year_end_history_pkey PRIMARY KEY (year);


--
-- Name: twse_institutional_daily twse_institutional_daily_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.twse_institutional_daily
    ADD CONSTRAINT twse_institutional_daily_pkey PRIMARY KEY (id);


--
-- Name: broker uk4kgt9prhx3clxdw9x0t4d3s01; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.broker
    ADD CONSTRAINT uk4kgt9prhx3clxdw9x0t4d3s01 UNIQUE (code);


--
-- Name: exchange_rate_history uk977p0we3c7unf4kuwcaefe2gb; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.exchange_rate_history
    ADD CONSTRAINT uk977p0we3c7unf4kuwcaefe2gb UNIQUE (currency, rate_date);


--
-- Name: fund_dividend_history uk_fund_div_code_date; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fund_dividend_history
    ADD CONSTRAINT uk_fund_div_code_date UNIQUE (fund_code, base_date);


--
-- Name: fund_nav uk_fund_nav_code_date; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fund_nav
    ADD CONSTRAINT uk_fund_nav_code_date UNIQUE (fund_code, nav_date);


--
-- Name: stock_price_history ukgoypknu8vhw2x3m63hpa7suxu; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_price_history
    ADD CONSTRAINT ukgoypknu8vhw2x3m63hpa7suxu UNIQUE (stock_code, market, trading_date);


--
-- Name: deposit_type ukj0oo653mssbv9x3d1n69oe7pb; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.deposit_type
    ADD CONSTRAINT ukj0oo653mssbv9x3d1n69oe7pb UNIQUE (code);


--
-- Name: market_type ukmbg7rlqx90v5oya66p7e2emrs; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.market_type
    ADD CONSTRAINT ukmbg7rlqx90v5oya66p7e2emrs UNIQUE (code);


--
-- Name: bank uknc70mw7kj0k56c4pjpl6b0xwt; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.bank
    ADD CONSTRAINT uknc70mw7kj0k56c4pjpl6b0xwt UNIQUE (code);


--
-- Name: commodity_export_schedule uq_commodity_export_schedule_owner; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.commodity_export_schedule
    ADD CONSTRAINT uq_commodity_export_schedule_owner UNIQUE (owner_user_id);


--
-- Name: commodity_export_schedule_time uq_commodity_export_schedule_time; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.commodity_export_schedule_time
    ADD CONSTRAINT uq_commodity_export_schedule_time UNIQUE (schedule_id, run_hour, run_minute);


--
-- Name: commodity_price_history uq_commodity_price_code_date; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.commodity_price_history
    ADD CONSTRAINT uq_commodity_price_code_date UNIQUE (commodity_code, price_date);


--
-- Name: crawler_export_setting uq_crawler_export_setting_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.crawler_export_setting
    ADD CONSTRAINT uq_crawler_export_setting_key UNIQUE (crawler_key);


--
-- Name: crawler_schedule uq_crawler_schedule_key_time; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.crawler_schedule
    ADD CONSTRAINT uq_crawler_schedule_key_time UNIQUE (crawler_key, run_hour, run_minute);


--
-- Name: etf_nav_history uq_etf_nav_code_market_date; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.etf_nav_history
    ADD CONSTRAINT uq_etf_nav_code_market_date UNIQUE (stock_code, market, nav_date);


--
-- Name: etf_nav_observation uq_etf_nav_observation_identity; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.etf_nav_observation
    ADD CONSTRAINT uq_etf_nav_observation_identity UNIQUE (stock_code, market, nav_date, observed_at, source);


--
-- Name: exchange_rate_export_schedule uq_exchange_rate_export_schedule_owner; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.exchange_rate_export_schedule
    ADD CONSTRAINT uq_exchange_rate_export_schedule_owner UNIQUE (owner_user_id);


--
-- Name: exchange_rate_export_schedule_time uq_exchange_rate_export_schedule_time; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.exchange_rate_export_schedule_time
    ADD CONSTRAINT uq_exchange_rate_export_schedule_time UNIQUE (schedule_id, run_hour, run_minute);


--
-- Name: export_schedule_setting uq_export_schedule_owner; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.export_schedule_setting
    ADD CONSTRAINT uq_export_schedule_owner UNIQUE (owner_user_id);


--
-- Name: export_schedule_time uq_export_schedule_time; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.export_schedule_time
    ADD CONSTRAINT uq_export_schedule_time UNIQUE (schedule_id, run_hour, run_minute);


--
-- Name: index_export_schedule uq_index_export_schedule_owner; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.index_export_schedule
    ADD CONSTRAINT uq_index_export_schedule_owner UNIQUE (owner_user_id);


--
-- Name: index_export_schedule_time uq_index_export_schedule_time; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.index_export_schedule_time
    ADD CONSTRAINT uq_index_export_schedule_time UNIQUE (schedule_id, run_hour, run_minute);


--
-- Name: investment_profile uq_investment_profile_owner; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.investment_profile
    ADD CONSTRAINT uq_investment_profile_owner UNIQUE (owner_user_id);


--
-- Name: notification_recipient uq_recipient_owner_email; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notification_recipient
    ADD CONSTRAINT uq_recipient_owner_email UNIQUE (owner_user_id, email);


--
-- Name: realized_gain_export_schedule uq_rg_export_schedule_owner; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.realized_gain_export_schedule
    ADD CONSTRAINT uq_rg_export_schedule_owner UNIQUE (owner_user_id);


--
-- Name: realized_gain_export_schedule_time uq_rg_export_schedule_time; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.realized_gain_export_schedule_time
    ADD CONSTRAINT uq_rg_export_schedule_time UNIQUE (schedule_id, run_hour, run_minute);


--
-- Name: asset_snapshot uq_snapshot_owner_date; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.asset_snapshot
    ADD CONSTRAINT uq_snapshot_owner_date UNIQUE (owner_user_id, snapshot_date);


--
-- Name: stock_alert_export_setting uq_stock_alert_export_owner; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_alert_export_setting
    ADD CONSTRAINT uq_stock_alert_export_owner UNIQUE (owner_user_id);


--
-- Name: stock_alert_group_recipient uq_stock_alert_group_recipient; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_alert_group_recipient
    ADD CONSTRAINT uq_stock_alert_group_recipient UNIQUE (group_id, recipient_id);


--
-- Name: stock_alert_recipient uq_stock_alert_recipient; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_alert_recipient
    ADD CONSTRAINT uq_stock_alert_recipient UNIQUE (alert_id, recipient_id);


--
-- Name: stock_technical_indicator uq_stock_technical_indicator_fact_hash; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_technical_indicator
    ADD CONSTRAINT uq_stock_technical_indicator_fact_hash UNIQUE (stock_code, market, provider, timeframe, profile_id, source_date, content_hash);


--
-- Name: trading_calendar_export_schedule uq_tc_export_schedule_owner; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trading_calendar_export_schedule
    ADD CONSTRAINT uq_tc_export_schedule_owner UNIQUE (owner_user_id);


--
-- Name: trading_radar_export_setting uq_tr_export_setting_owner; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trading_radar_export_setting
    ADD CONSTRAINT uq_tr_export_setting_owner UNIQUE (owner_user_id);


--
-- Name: trading_radar_export_time uq_tr_export_time_owner_time; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trading_radar_export_time
    ADD CONSTRAINT uq_tr_export_time_owner_time UNIQUE (owner_user_id, run_hour, run_minute);


--
-- Name: treasury_yield_batch uq_treasury_yield_batch_content; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.treasury_yield_batch
    ADD CONSTRAINT uq_treasury_yield_batch_content UNIQUE (curve_date, provider, content_hash);


--
-- Name: trading_radar_notification_recipient uq_trn_recipient; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trading_radar_notification_recipient
    ADD CONSTRAINT uq_trn_recipient UNIQUE (setting_id, recipient_id);


--
-- Name: trading_radar_notification_setting uq_trn_setting_owner_stock; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trading_radar_notification_setting
    ADD CONSTRAINT uq_trn_setting_owner_stock UNIQUE (owner_user_id, stock_code, market);


--
-- Name: trading_radar_notification_state uq_trn_state; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trading_radar_notification_state
    ADD CONSTRAINT uq_trn_state UNIQUE (setting_id, state_type, state_code);


--
-- Name: twse_institutional_daily uq_twse_institutional_observation; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.twse_institutional_daily
    ADD CONSTRAINT uq_twse_institutional_observation UNIQUE (provider, observed_at);


--
-- Name: us_index_daily_history us_index_daily_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.us_index_daily_history
    ADD CONSTRAINT us_index_daily_history_pkey PRIMARY KEY (index_code, trading_date);


--
-- Name: idx_api_error_log_source_name_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_api_error_log_source_name_time ON public.api_error_log USING btree (source, api_name, occurred_at DESC, id DESC);


--
-- Name: idx_api_error_log_source_operation_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_api_error_log_source_operation_time ON public.api_error_log USING btree (source, operation_key, occurred_at DESC, id DESC);


--
-- Name: idx_api_error_log_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_api_error_log_time ON public.api_error_log USING btree (occurred_at DESC, id DESC);


--
-- Name: idx_asset_snapshot_owner; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_asset_snapshot_owner ON public.asset_snapshot USING btree (owner_user_id);


--
-- Name: idx_asset_transaction_owner_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_asset_transaction_owner_date ON public.asset_transaction USING btree (owner_user_id, trade_date DESC);


--
-- Name: idx_at_export_schedule_owner; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_at_export_schedule_owner ON public.asset_transaction_export_schedule USING btree (owner_user_id);


--
-- Name: idx_backup_record_modified; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_backup_record_modified ON public.backup_record USING btree (modified_at DESC);


--
-- Name: idx_bank_deposit_snapshot_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_bank_deposit_snapshot_id ON public.bank_deposit USING btree (snapshot_id);


--
-- Name: idx_commodity_export_schedule_time_schedule; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_commodity_export_schedule_time_schedule ON public.commodity_export_schedule_time USING btree (schedule_id);


--
-- Name: idx_commodity_price_available; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_commodity_price_available ON public.commodity_price_history USING btree (commodity_code, source_available_at, price_date DESC);


--
-- Name: idx_commodity_price_code_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_commodity_price_code_date ON public.commodity_price_history USING btree (commodity_code, price_date);


--
-- Name: idx_crawler_schedule_key; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_crawler_schedule_key ON public.crawler_schedule USING btree (crawler_key);


--
-- Name: idx_dividend_event_key; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dividend_event_key ON public.stock_dividend_history USING btree (stock_code, market, event_key) WHERE (event_key IS NOT NULL);


--
-- Name: idx_dividend_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dividend_lookup ON public.stock_dividend_history USING btree (stock_code, market, year DESC);


--
-- Name: idx_etf_nav_code_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_etf_nav_code_date ON public.etf_nav_history USING btree (stock_code, market, nav_date);


--
-- Name: idx_etf_nav_observation_decision; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_etf_nav_observation_decision ON public.etf_nav_observation USING btree (stock_code, market, available_at, nav_date DESC);


--
-- Name: idx_etf_nav_observation_nav_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_etf_nav_observation_nav_date ON public.etf_nav_observation USING btree (stock_code, market, nav_date, available_at DESC);


--
-- Name: idx_exchange_rate_export_schedule_time_schedule; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_exchange_rate_export_schedule_time_schedule ON public.exchange_rate_export_schedule_time USING btree (schedule_id);


--
-- Name: idx_export_schedule_time_schedule; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_export_schedule_time_schedule ON public.export_schedule_time USING btree (schedule_id);


--
-- Name: idx_fubon_intraday_candle_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_fubon_intraday_candle_date ON public.fubon_intraday_candle USING btree (stock_code, market, provider, source_date, candle_at);


--
-- Name: idx_fubon_technical_capture_member_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_fubon_technical_capture_member_lookup ON public.fubon_technical_capture_member USING btree (stock_code, market, provider, observed_at DESC, capture_id);


--
-- Name: idx_fund_div_code_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_fund_div_code_date ON public.fund_dividend_history USING btree (fund_code, base_date DESC);


--
-- Name: idx_fund_holding_snapshot_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_fund_holding_snapshot_id ON public.fund_holding USING btree (snapshot_id);


--
-- Name: idx_fund_nav_code_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_fund_nav_code_date ON public.fund_nav USING btree (fund_code, nav_date DESC);


--
-- Name: idx_industry_revenue_asof; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_industry_revenue_asof ON public.industry_monthly_revenue USING btree (industry_name, revenue_year DESC, revenue_month DESC, observed_at DESC);


--
-- Name: idx_news_headline_cat; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_news_headline_cat ON public.news_headline USING btree (category, published_at DESC);


--
-- Name: idx_news_headline_recent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_news_headline_recent ON public.news_headline USING btree (published_at DESC);


--
-- Name: idx_notification_recipient_owner; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_notification_recipient_owner ON public.notification_recipient USING btree (owner_user_id);


--
-- Name: idx_payment_account_category; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_payment_account_category ON public.payment_account USING btree (category_id);


--
-- Name: idx_payment_account_owner; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_payment_account_owner ON public.payment_account USING btree (owner_user_id);


--
-- Name: idx_planned_expense_owner; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_planned_expense_owner ON public.investment_planned_expense USING btree (owner_user_id, expense_date);


--
-- Name: idx_portfolio_advice_owner_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_portfolio_advice_owner_created ON public.portfolio_advice USING btree (owner_user_id, created_at DESC);


--
-- Name: idx_realized_gain_owner; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_realized_gain_owner ON public.realized_gain USING btree (owner_user_id);


--
-- Name: idx_rg_export_schedule_time_schedule; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_rg_export_schedule_time_schedule ON public.realized_gain_export_schedule_time USING btree (schedule_id);


--
-- Name: idx_sag_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sag_active ON public.stock_alert_group USING btree (active);


--
-- Name: idx_sag_code_market; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sag_code_market ON public.stock_alert_group USING btree (stock_code, market);


--
-- Name: idx_sag_owner; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sag_owner ON public.stock_alert_group USING btree (owner_user_id);


--
-- Name: idx_sagr_group; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sagr_group ON public.stock_alert_group_recipient USING btree (group_id);


--
-- Name: idx_sagr_recipient; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sagr_recipient ON public.stock_alert_group_recipient USING btree (recipient_id);


--
-- Name: idx_sar_alert; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sar_alert ON public.stock_alert_recipient USING btree (alert_id);


--
-- Name: idx_sar_recipient; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sar_recipient ON public.stock_alert_recipient USING btree (recipient_id);


--
-- Name: idx_sat_group; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sat_group ON public.stock_alert_trigger USING btree (group_id);


--
-- Name: idx_sph_code_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sph_code_date ON public.stock_price_history USING btree (stock_code, trading_date);


--
-- Name: idx_stock_alert_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_alert_active ON public.stock_alert USING btree (active);


--
-- Name: idx_stock_alert_code_market; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_alert_code_market ON public.stock_alert USING btree (stock_code, market);


--
-- Name: idx_stock_alert_group; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_alert_group ON public.stock_alert USING btree (group_id);


--
-- Name: idx_stock_alert_owner; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_alert_owner ON public.stock_alert USING btree (owner_user_id);


--
-- Name: idx_stock_alert_trigger_alert_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_alert_trigger_alert_time ON public.stock_alert_trigger USING btree (alert_id, triggered_at DESC);


--
-- Name: idx_stock_alert_trigger_created_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_alert_trigger_created_at ON public.stock_alert_trigger USING btree (created_at);


--
-- Name: idx_stock_dividend_fetch_asof; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_dividend_fetch_asof ON public.stock_dividend_fetch_observation USING btree (snapshot_id, observed_at DESC);


--
-- Name: idx_stock_dividend_fetch_attempt_lookup; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_dividend_fetch_attempt_lookup ON public.stock_dividend_fetch_attempt USING btree (stock_code, market, observed_at DESC);


--
-- Name: idx_stock_dividend_snapshot_event_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_dividend_snapshot_event_date ON public.stock_dividend_snapshot_event USING btree (snapshot_id, ex_dividend_date);


--
-- Name: idx_stock_financial_asof; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_financial_asof ON public.stock_financial_quarter USING btree (stock_code, market, fiscal_year DESC, fiscal_quarter DESC, observed_at DESC);


--
-- Name: idx_stock_holding_snapshot_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_holding_snapshot_id ON public.stock_holding USING btree (snapshot_id);


--
-- Name: idx_stock_revenue_asof; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_revenue_asof ON public.stock_monthly_revenue USING btree (stock_code, market, revenue_year DESC, revenue_month DESC, observed_at DESC);


--
-- Name: idx_stock_valuation_asof; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_stock_valuation_asof ON public.stock_valuation_daily USING btree (stock_code, market, trading_date DESC, observed_at DESC);


--
-- Name: idx_tr_export_time_owner; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tr_export_time_owner ON public.trading_radar_export_time USING btree (owner_user_id);


--
-- Name: idx_treasury_yield_batch_decision; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_treasury_yield_batch_decision ON public.treasury_yield_batch USING btree (complete, available_at, curve_date DESC);


--
-- Name: idx_trn_recipient_recipient; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_trn_recipient_recipient ON public.trading_radar_notification_recipient USING btree (recipient_id);


--
-- Name: idx_trn_recipient_setting; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_trn_recipient_setting ON public.trading_radar_notification_recipient USING btree (setting_id);


--
-- Name: idx_trn_setting_stock; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_trn_setting_stock ON public.trading_radar_notification_setting USING btree (stock_code, market, active);


--
-- Name: idx_trn_state_setting; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_trn_state_setting ON public.trading_radar_notification_state USING btree (setting_id);


--
-- Name: idx_twse_institutional_decision; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_twse_institutional_decision ON public.twse_institutional_daily USING btree (trading_date DESC, observed_at DESC);


--
-- Name: uk_dividend_event; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_dividend_event ON public.stock_dividend_history USING btree (stock_code, market, year, COALESCE(ex_dividend_date, '1970-01-01'::date), COALESCE(ex_rights_date, '1970-01-01'::date), COALESCE(cash_dividend, (0)::numeric), COALESCE(stock_dividend, (0)::numeric), COALESCE(cash_payment_date, '1970-01-01'::date), COALESCE(stock_payment_date, '1970-01-01'::date), COALESCE(event_key, ''::character varying));


--
-- Name: uk_news_headline_dedupe; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_news_headline_dedupe ON public.news_headline USING btree (dedupe_key);


--
-- Name: uq_api_error_log_dedupe_key; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_api_error_log_dedupe_key ON public.api_error_log USING btree (dedupe_key) WHERE (dedupe_key IS NOT NULL);


--
-- Name: uq_realized_gain_fubon_sync_occurrence; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_realized_gain_fubon_sync_occurrence ON public.realized_gain USING btree (owner_user_id, sync_source, sync_fingerprint, sync_occurrence) WHERE (sync_source IS NOT NULL);


--
-- Name: ux_asset_transaction_owner_broker_filled_no; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_asset_transaction_owner_broker_filled_no ON public.asset_transaction USING btree (owner_user_id, broker_filled_no) WHERE (broker_filled_no IS NOT NULL);


--
-- Name: api_error_log_operation api_error_log_operation_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER api_error_log_operation_immutable BEFORE DELETE OR UPDATE ON public.api_error_log_operation FOR EACH ROW EXECUTE FUNCTION public.reject_api_error_log_operation_mutation();


--
-- Name: api_error_log api_error_log_retention_guard; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER api_error_log_retention_guard BEFORE DELETE OR UPDATE ON public.api_error_log FOR EACH ROW EXECUTE FUNCTION public.guard_api_error_log_retention();


--
-- Name: fubon_historical_daily_candle trg_fubon_historical_daily_candle_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_fubon_historical_daily_candle_immutable BEFORE DELETE OR UPDATE ON public.fubon_historical_daily_candle FOR EACH ROW EXECUTE FUNCTION public.guard_fubon_historical_daily_candle_immutable();


--
-- Name: fubon_technical_capture_member trg_fubon_technical_capture_member_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_fubon_technical_capture_member_immutable BEFORE DELETE OR UPDATE ON public.fubon_technical_capture_member FOR EACH ROW EXECUTE FUNCTION public.reject_fubon_technical_history_mutation();


--
-- Name: stock_technical_indicator trg_stock_technical_indicator_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_stock_technical_indicator_immutable BEFORE DELETE OR UPDATE ON public.stock_technical_indicator FOR EACH ROW EXECUTE FUNCTION public.reject_fubon_technical_history_mutation();


--
-- Name: api_error_log api_error_log_source_operation_key_api_name_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.api_error_log
    ADD CONSTRAINT api_error_log_source_operation_key_api_name_fkey FOREIGN KEY (source, operation_key, api_name) REFERENCES public.api_error_log_operation(source, operation_key, operation_label) ON UPDATE RESTRICT ON DELETE RESTRICT;


--
-- Name: commodity_export_schedule_time commodity_export_schedule_time_schedule_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.commodity_export_schedule_time
    ADD CONSTRAINT commodity_export_schedule_time_schedule_id_fkey FOREIGN KEY (schedule_id) REFERENCES public.commodity_export_schedule(id) ON DELETE CASCADE;


--
-- Name: exchange_rate_export_schedule_time exchange_rate_export_schedule_time_schedule_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.exchange_rate_export_schedule_time
    ADD CONSTRAINT exchange_rate_export_schedule_time_schedule_id_fkey FOREIGN KEY (schedule_id) REFERENCES public.exchange_rate_export_schedule(id) ON DELETE CASCADE;


--
-- Name: export_schedule_time export_schedule_time_schedule_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.export_schedule_time
    ADD CONSTRAINT export_schedule_time_schedule_id_fkey FOREIGN KEY (schedule_id) REFERENCES public.export_schedule_setting(id) ON DELETE CASCADE;


--
-- Name: fund_holding fk2ic58t3gkyb93k5qiavdn43x6; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fund_holding
    ADD CONSTRAINT fk2ic58t3gkyb93k5qiavdn43x6 FOREIGN KEY (snapshot_id) REFERENCES public.asset_snapshot(id);


--
-- Name: stock_holding fk7mnwqhx8fc8nk9c0i0te8ocxc; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_holding
    ADD CONSTRAINT fk7mnwqhx8fc8nk9c0i0te8ocxc FOREIGN KEY (snapshot_id) REFERENCES public.asset_snapshot(id);


--
-- Name: asset_snapshot fk_asset_snapshot_owner; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.asset_snapshot
    ADD CONSTRAINT fk_asset_snapshot_owner FOREIGN KEY (owner_user_id) REFERENCES public.app_user(id);


--
-- Name: fubon_technical_capture_member fk_fubon_technical_capture_member_fact; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fubon_technical_capture_member
    ADD CONSTRAINT fk_fubon_technical_capture_member_fact FOREIGN KEY (stock_code, market, provider, timeframe, profile_id, source_date, content_hash) REFERENCES public.stock_technical_indicator(stock_code, market, provider, timeframe, profile_id, source_date, content_hash) ON DELETE RESTRICT;


--
-- Name: fubon_technical_capture_member fk_fubon_technical_capture_member_previous_fact; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fubon_technical_capture_member
    ADD CONSTRAINT fk_fubon_technical_capture_member_previous_fact FOREIGN KEY (stock_code, market, provider, timeframe, profile_id, previous_source_date, previous_content_hash) REFERENCES public.stock_technical_indicator(stock_code, market, provider, timeframe, profile_id, source_date, content_hash) ON DELETE RESTRICT;


--
-- Name: notification_recipient fk_notification_recipient_owner; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notification_recipient
    ADD CONSTRAINT fk_notification_recipient_owner FOREIGN KEY (owner_user_id) REFERENCES public.app_user(id);


--
-- Name: payment_account fk_payment_account_owner; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_account
    ADD CONSTRAINT fk_payment_account_owner FOREIGN KEY (owner_user_id) REFERENCES public.app_user(id);


--
-- Name: realized_gain fk_realized_gain_owner; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.realized_gain
    ADD CONSTRAINT fk_realized_gain_owner FOREIGN KEY (owner_user_id) REFERENCES public.app_user(id);


--
-- Name: stock_alert_group_recipient fk_sagr_group; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_alert_group_recipient
    ADD CONSTRAINT fk_sagr_group FOREIGN KEY (group_id) REFERENCES public.stock_alert_group(id) ON DELETE CASCADE;


--
-- Name: stock_alert_group_recipient fk_sagr_recipient; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_alert_group_recipient
    ADD CONSTRAINT fk_sagr_recipient FOREIGN KEY (recipient_id) REFERENCES public.notification_recipient(id) ON DELETE CASCADE;


--
-- Name: stock_alert_recipient fk_sar_alert; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_alert_recipient
    ADD CONSTRAINT fk_sar_alert FOREIGN KEY (alert_id) REFERENCES public.stock_alert(id) ON DELETE CASCADE;


--
-- Name: stock_alert_recipient fk_sar_recipient; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_alert_recipient
    ADD CONSTRAINT fk_sar_recipient FOREIGN KEY (recipient_id) REFERENCES public.notification_recipient(id) ON DELETE CASCADE;


--
-- Name: stock_alert_trigger fk_sat_group; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_alert_trigger
    ADD CONSTRAINT fk_sat_group FOREIGN KEY (group_id) REFERENCES public.stock_alert_group(id) ON DELETE CASCADE;


--
-- Name: stock_alert fk_stock_alert_group; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_alert
    ADD CONSTRAINT fk_stock_alert_group FOREIGN KEY (group_id) REFERENCES public.stock_alert_group(id) ON DELETE CASCADE;


--
-- Name: stock_alert fk_stock_alert_owner; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_alert
    ADD CONSTRAINT fk_stock_alert_owner FOREIGN KEY (owner_user_id) REFERENCES public.app_user(id);


--
-- Name: stock_intraday_order_book_level fk_stock_intraday_order_book_level_header; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_intraday_order_book_level
    ADD CONSTRAINT fk_stock_intraday_order_book_level_header FOREIGN KEY (stock_code, market) REFERENCES public.stock_intraday_order_book(stock_code, market) ON DELETE CASCADE;


--
-- Name: trading_radar_notification_recipient fk_trn_recipient_recipient; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trading_radar_notification_recipient
    ADD CONSTRAINT fk_trn_recipient_recipient FOREIGN KEY (recipient_id) REFERENCES public.notification_recipient(id) ON DELETE CASCADE;


--
-- Name: trading_radar_notification_recipient fk_trn_recipient_setting; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trading_radar_notification_recipient
    ADD CONSTRAINT fk_trn_recipient_setting FOREIGN KEY (setting_id) REFERENCES public.trading_radar_notification_setting(id) ON DELETE CASCADE;


--
-- Name: trading_radar_notification_setting fk_trn_setting_owner; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trading_radar_notification_setting
    ADD CONSTRAINT fk_trn_setting_owner FOREIGN KEY (owner_user_id) REFERENCES public.app_user(id) ON DELETE CASCADE;


--
-- Name: trading_radar_notification_state fk_trn_state_setting; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trading_radar_notification_state
    ADD CONSTRAINT fk_trn_state_setting FOREIGN KEY (setting_id) REFERENCES public.trading_radar_notification_setting(id) ON DELETE CASCADE;


--
-- Name: bank_deposit fkgh3kqmvrffwjl3rj7vmb6qisn; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.bank_deposit
    ADD CONSTRAINT fkgh3kqmvrffwjl3rj7vmb6qisn FOREIGN KEY (bank_id) REFERENCES public.bank(id);


--
-- Name: bank_deposit fkjxcn193425e8y6bdobkkma5jo; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.bank_deposit
    ADD CONSTRAINT fkjxcn193425e8y6bdobkkma5jo FOREIGN KEY (snapshot_id) REFERENCES public.asset_snapshot(id);


--
-- Name: stock_holding fklyb4h3jgxxou8j1smvo6qq2mc; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_holding
    ADD CONSTRAINT fklyb4h3jgxxou8j1smvo6qq2mc FOREIGN KEY (broker_id) REFERENCES public.broker(id);


--
-- Name: fund_holding fkq9964x3qa2cdcxgdbqpitmobd; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fund_holding
    ADD CONSTRAINT fkq9964x3qa2cdcxgdbqpitmobd FOREIGN KEY (bank_id) REFERENCES public.bank(id);


--
-- Name: fund_master fund_master_bank_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fund_master
    ADD CONSTRAINT fund_master_bank_id_fkey FOREIGN KEY (bank_id) REFERENCES public.bank(id);


--
-- Name: index_export_schedule_time_market index_export_schedule_time_market_schedule_time_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.index_export_schedule_time_market
    ADD CONSTRAINT index_export_schedule_time_market_schedule_time_id_fkey FOREIGN KEY (schedule_time_id) REFERENCES public.index_export_schedule_time(id) ON DELETE CASCADE;


--
-- Name: index_export_schedule_time index_export_schedule_time_schedule_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.index_export_schedule_time
    ADD CONSTRAINT index_export_schedule_time_schedule_id_fkey FOREIGN KEY (schedule_id) REFERENCES public.index_export_schedule(id) ON DELETE CASCADE;


--
-- Name: payment_account payment_account_category_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payment_account
    ADD CONSTRAINT payment_account_category_id_fkey FOREIGN KEY (category_id) REFERENCES public.payment_category(id);


--
-- Name: realized_gain_export_schedule_time realized_gain_export_schedule_time_schedule_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.realized_gain_export_schedule_time
    ADD CONSTRAINT realized_gain_export_schedule_time_schedule_id_fkey FOREIGN KEY (schedule_id) REFERENCES public.realized_gain_export_schedule(id) ON DELETE CASCADE;


--
-- Name: stock_alert_group stock_alert_group_owner_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_alert_group
    ADD CONSTRAINT stock_alert_group_owner_user_id_fkey FOREIGN KEY (owner_user_id) REFERENCES public.app_user(id);


--
-- Name: stock_alert_trigger stock_alert_trigger_alert_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_alert_trigger
    ADD CONSTRAINT stock_alert_trigger_alert_id_fkey FOREIGN KEY (alert_id) REFERENCES public.stock_alert(id) ON DELETE CASCADE;


--
-- Name: stock_dividend_fetch_attempt stock_dividend_fetch_attempt_snapshot_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_dividend_fetch_attempt
    ADD CONSTRAINT stock_dividend_fetch_attempt_snapshot_id_fkey FOREIGN KEY (snapshot_id) REFERENCES public.stock_dividend_snapshot(id);


--
-- Name: stock_dividend_fetch_observation stock_dividend_fetch_observation_snapshot_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_dividend_fetch_observation
    ADD CONSTRAINT stock_dividend_fetch_observation_snapshot_id_fkey FOREIGN KEY (snapshot_id) REFERENCES public.stock_dividend_snapshot(id);


--
-- Name: stock_dividend_snapshot_event stock_dividend_snapshot_event_snapshot_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.stock_dividend_snapshot_event
    ADD CONSTRAINT stock_dividend_snapshot_event_snapshot_id_fkey FOREIGN KEY (snapshot_id) REFERENCES public.stock_dividend_snapshot(id);


--
-- Name: treasury_yield_daily treasury_yield_daily_batch_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.treasury_yield_daily
    ADD CONSTRAINT treasury_yield_daily_batch_id_fkey FOREIGN KEY (batch_id) REFERENCES public.treasury_yield_batch(id);


--
-- PostgreSQL database dump complete
--


