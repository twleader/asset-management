from __future__ import annotations

from collections.abc import Callable
from datetime import datetime, timedelta
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP, localcontext

from .normalization import (
    TAIPEI, canonical_number, decimal_value, instant, observed_at, stock_codes,
    strict_iso_date, utc_now,
)
from .sdk_gateway import SdkCallError, SdkGateway


class DividendError(ValueError):
    def __init__(self, reason: str, *, request_error: bool = False) -> None:
        super().__init__(reason)
        self.reason, self.request_error = reason, request_error


class DividendService:
    """One official date batch, then exact requested-symbol filtering and partial evidence."""

    def __init__(self, gateway: SdkGateway, *, now: Callable[[], datetime] = utc_now) -> None:
        self._gateway, self._now = gateway, now

    def read(self, symbols: list[str], start_date: str, end_date: str) -> dict[str, object]:
        started = instant(self._now())
        query_date = started.astimezone(TAIPEI).date()
        try:
            checked = stock_codes(symbols, maximum=2000)
            start, end = strict_iso_date(start_date), strict_iso_date(end_date)
            if start != query_date - timedelta(days=320) or end != query_date + timedelta(days=45):
                raise ValueError("INVALID_DATE_RANGE")
        except ValueError:
            raise DividendError("INVALID_REQUEST", request_error=True) from None
        source = self._gateway.read_dividends(start_date, end_date)
        if not isinstance(source, dict) or source.get("is_success") is False or not isinstance(source.get("data"), list):
            raise DividendError("DIVIDEND_SCHEMA_INVALID")
        selected: dict[str, list[object]] = {symbol: [] for symbol in checked}
        for row in source["data"]:
            # Unknown identity cannot be attributed to a particular requested symbol safely.
            if not isinstance(row, dict) or not isinstance(row.get("symbol"), str) or not row["symbol"]:
                raise DividendError("DIVIDEND_SCHEMA_INVALID")
            if row["symbol"] in selected:
                selected[row["symbol"]].append(row)
        output = [self._symbol_result(symbol, rows, start_date, end_date) for symbol, rows in selected.items()]
        finished = instant(self._now())
        if finished < started or finished.astimezone(TAIPEI).date() != query_date:
            raise DividendError("STALE_QUERY")
        result = {"queryDate": query_date.isoformat(), "observedAt": observed_at(finished),
                  "scopeFrom": start_date, "scopeTo": end_date, "provider": "FUBON_SDK", "rows": output}
        try:
            SdkGateway._check_marketdata_size(result)
        except SdkCallError:
            raise DividendError("DIVIDEND_RESPONSE_TOO_LARGE") from None
        return result

    @staticmethod
    def _symbol_result(symbol: str, rows: list[object], start: str, end: str) -> dict[str, object]:
        events: list[dict[str, object]] = []
        seen: set[str] = set()
        reason: str | None = None
        try:
            for row in rows:
                if row.get("exchange") not in {"TWSE", "TPEx"}:
                    raise ValueError("DIVIDEND_SCHEMA_INVALID")
                event_date = strict_iso_date(row.get("date"))
                if not (start <= event_date.isoformat() <= end) or event_date.isoformat() in seen:
                    raise ValueError("DIVIDEND_SCHEMA_INVALID")
                seen.add(event_date.isoformat())
                kind = row.get("dividendType")
                if kind not in {"息", "權", "權息"}:
                    raise ValueError("DIVIDEND_SCHEMA_INVALID")
                shares = row.get("stockDividendShares")
                if shares is not None:
                    canonical_number(shares, precision=38, scale=18, nonnegative=True)
                cash_raw = row.get("cashDividend")
                if cash_raw is None and "息" in kind:
                    raise ValueError("DIVIDEND_SCHEMA_INVALID")
                cash = decimal_value(cash_raw) if cash_raw is not None else Decimal(0)
                if cash < 0 or cash.adjusted() > 12:
                    raise ValueError("DIVIDEND_SCHEMA_INVALID")
                with localcontext() as context:
                    context.prec = 40
                    rounded = cash.quantize(Decimal("0.000001"), rounding=ROUND_HALF_UP)
                if len(rounded.as_tuple().digits) > 18:
                    raise ValueError("DIVIDEND_SCHEMA_INVALID")
                if "權" in kind:
                    reason = "STOCK_DIVIDEND_UNIT_UNVERIFIED"
                if "息" not in kind or rounded <= 0:
                    if cash > 0 and rounded == 0 and reason is None:
                        reason = "DIVIDEND_ROUNDED_TO_ZERO"
                    continue
                date_text = event_date.isoformat()
                events.append({"date": date_text, "exchange": row["exchange"], "dividendType": kind,
                               "year": event_date.year, "cashDividend": format(rounded, "f"),
                               "stockDividend": None, "exDividendDate": date_text,
                               "exRightsDate": date_text if "權" in kind else None,
                               "cashPaymentDate": None, "stockPaymentDate": None})
        except (ValueError, InvalidOperation, TypeError):
            return {"symbol": symbol, "status": "FAILED", "usable": False,
                    "reason": "DIVIDEND_SCHEMA_INVALID", "events": []}
        return {"symbol": symbol, "status": "PARTIAL", "usable": bool(events),
                "reason": reason if reason is not None else (None if events else "NO_MATCHING_EVENTS"),
                "events": sorted(events, key=lambda event: event["date"])}
