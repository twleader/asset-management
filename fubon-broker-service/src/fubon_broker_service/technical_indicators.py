from __future__ import annotations

from collections.abc import Callable
from datetime import datetime, timedelta
from decimal import Decimal
import time

from .normalization import (
    TAIPEI, canonical_number, instant, observed_at, stock_code, strict_iso_date, utc_now,
)
from .sdk_gateway import SdkCallError, SdkGateway


PARAMETERS = {
    "kdj": {"timeframe": "D", "rPeriod": 9, "kPeriod": 3, "dPeriod": 3},
    "macd": {"timeframe": "D", "fast": 12, "slow": 26, "signal": 9},
    "bb": {"timeframe": "D", "period": 20},
}
PAYLOAD_FIELDS = {"kdj": ("k", "d", "j"), "macd": ("macdLine", "signalLine"),
                  "bb": ("upper", "middle", "lower")}


class TechnicalIndicatorError(ValueError):
    def __init__(self, reason: str, *, request_error: bool = False) -> None:
        super().__init__(reason)
        self.reason, self.request_error = reason, request_error


def group_result(kind: str, status: str, reason: str | None = None,
                 source_date: str | None = None, payload: dict[str, str] | None = None) -> dict[str, object]:
    return {"status": status, "reason": reason, "parameters": dict(PARAMETERS[kind]),
            "sourceDate": source_date, "sourceTimestamp": None, "payload": payload}


class TechnicalIndicatorService:
    """Independent source-dated groups, without inventing a common date or indicator values."""

    def __init__(self, gateway: SdkGateway, *, now: Callable[[], datetime] = utc_now,
                 monotonic: Callable[[], float] = time.monotonic) -> None:
        self._gateway, self._now, self._monotonic = gateway, now, monotonic

    def read(self, symbol: str, start_date: str, end_date: str) -> dict[str, object]:
        started = instant(self._now())
        query_date = started.astimezone(TAIPEI).date()
        try:
            stock_code(symbol)
            start, end = strict_iso_date(start_date), strict_iso_date(end_date)
            if start != query_date - timedelta(days=120) or end != query_date:
                raise ValueError("INVALID_DATE_RANGE")
        except ValueError:
            raise TechnicalIndicatorError("INVALID_REQUEST", request_error=True) from None
        groups: dict[str, object] = {}
        stop_reason: str | None = None
        deadline = self._monotonic() + 25.0
        for kind in PARAMETERS:
            if instant(self._now()).astimezone(TAIPEI).date() != query_date:
                raise TechnicalIndicatorError("STALE_QUERY")
            if stop_reason is not None:
                groups[kind] = group_result(kind, "UNAVAILABLE", stop_reason)
                continue
            try:
                source = self._gateway.read_technical_indicator(kind, symbol, start_date, end_date, deadline=deadline)
            except SdkCallError as exc:
                if exc.misconfigured or exc.auth_invalid or exc.reason == "DISABLED":
                    raise
                if exc.reason in {"RATE_LIMITED", "HISTORY_BUDGET_EXHAUSTED"} or (
                    exc.reason == "MARKETDATA_TIMEOUT" and self._monotonic() >= deadline
                ):
                    stop_reason = exc.reason
                groups[kind] = group_result(kind, "UNAVAILABLE", exc.reason)
                continue
            groups[kind] = self._normalize(kind, source, symbol, start_date, end_date)
        finished = instant(self._now())
        if finished < started or finished.astimezone(TAIPEI).date() != query_date:
            raise TechnicalIndicatorError("STALE_QUERY")
        return {"symbol": symbol, "market": "台股", "provider": "FUBON_SDK",
                "queryFrom": start_date, "queryTo": end_date, "observedAt": observed_at(finished), **groups}

    @staticmethod
    def _normalize(kind: str, source: object, symbol: str, start: str, end: str) -> dict[str, object]:
        try:
            if not isinstance(source, dict) or source.get("symbol") != symbol or source.get("from") != start or source.get("to") != end:
                raise ValueError("TECHNICAL_SCHEMA_INVALID")
            if source.get("is_success") is False:
                raise ValueError("TECHNICAL_SCHEMA_INVALID")
            for name, expected in PARAMETERS[kind].items():
                actual = source.get(name)
                if name == "timeframe":
                    valid = actual == expected
                elif kind == "bb" and name == "period" and isinstance(actual, str):
                    valid = actual == str(expected)
                else:
                    valid = type(actual) is int and actual == expected
                if not valid:
                    raise ValueError("TECHNICAL_SCHEMA_INVALID")
            rows = source.get("data")
            if not isinstance(rows, list):
                raise ValueError("TECHNICAL_SCHEMA_INVALID")
            if not rows:
                return group_result(kind, "NO_DATA", "NO_DATA")
            dated: dict[str, dict[str, object]] = {}
            for row in rows:
                if not isinstance(row, dict):
                    raise ValueError("TECHNICAL_SCHEMA_INVALID")
                source_date = strict_iso_date(row.get("date")).isoformat()
                if source_date < start or source_date > end or source_date in dated:
                    raise ValueError("TECHNICAL_SCHEMA_INVALID")
                dated[source_date] = row
            latest = max(dated)
            payload = {name: canonical_number(dated[latest].get(name), precision=38, scale=18)
                       for name in PAYLOAD_FIELDS[kind]}
            if kind == "bb" and not (Decimal(payload["upper"]) >= Decimal(payload["middle"]) >= Decimal(payload["lower"])):
                raise ValueError("TECHNICAL_SCHEMA_INVALID")
            return group_result(kind, "AVAILABLE", source_date=latest, payload=payload)
        except (ValueError, TypeError):
            return group_result(kind, "SCHEMA_INVALID", "TECHNICAL_SCHEMA_INVALID")
