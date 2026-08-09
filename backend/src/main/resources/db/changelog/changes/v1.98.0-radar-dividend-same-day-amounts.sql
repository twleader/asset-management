--liquibase formatted sql

--changeset steven:v1.98.0-radar-dividend-same-day-amounts
--comment A single ex-date can carry multiple distribution components/revisions; amount is part of the current-state identity so one event cannot overwrite another same-day amount.
DROP INDEX IF EXISTS uk_dividend_event;

CREATE UNIQUE INDEX IF NOT EXISTS uk_dividend_event
    ON stock_dividend_history (
        stock_code,
        market,
        year,
        COALESCE(ex_dividend_date, DATE '1970-01-01'),
        COALESCE(cash_dividend, 0),
        COALESCE(stock_dividend, 0)
    );
