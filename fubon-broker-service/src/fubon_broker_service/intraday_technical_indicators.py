"""Strict latest-point adapter for Fubon 1-minute and 5-minute indicators."""
from __future__ import annotations

from collections.abc import Callable
from datetime import date, datetime, timedelta
from decimal import Decimal
import re
import time
from typing import Protocol

from .normalization import TAIPEI, canonical_number, instant, observed_at, stock_code, strict_iso_date, utc_now


class IntradayTechnicalIndicatorError(ValueError):
    def __init__(self, reason: str, *, request_error: bool = False) -> None:
        super().__init__(reason)
        self.reason = reason
        self.request_error = request_error


class IntradayTechnicalGateway(Protocol):
    """Narrow read-only port needed by the intraday indicator service."""

    def read_intraday_technical_indicator(
        self, kind: str, symbol: str, start_date: str, end_date: str, timeframe: str,
        *, deadline: float | None = None,
    ) -> object: ...


class IntradayTechnicalGatewayError(RuntimeError):
    """Neutral failure contract translated by the outer SDK adapter."""

    def __init__(self, reason: str, *, misconfigured: bool = False) -> None:
        super().__init__(reason)
        self.reason = reason
        self.misconfigured = misconfigured


_KINDS: dict[str, tuple[tuple[str, object], ...]] = {
    "kdj": (("rPeriod", 9), ("kPeriod", 3), ("dPeriod", 3)),
    "macd": (("fast", 12), ("slow", 26), ("signal", 9)),
    "bb": (("period", 20),),
}
_FIELDS: dict[str, tuple[str, ...]] = {
    "kdj": ("k", "d", "j"),
    "macd": ("macdLine", "signalLine"),
    "bb": ("upper", "middle", "lower"),
}
_ISO_DATE = re.compile(r"\A\d{4}-\d{2}-\d{2}\Z")


class IntradayTechnicalIndicatorService:
    """Owns the immutable six-call manifest and emits only each latest point."""

    def __init__(self, gateway: IntradayTechnicalGateway, *, now: Callable[[], datetime] = utc_now,
                 monotonic: Callable[[], float] = time.monotonic) -> None:
        self._gateway = gateway
        self._now = now
        self._monotonic = monotonic

    def read(self, symbol: str) -> dict[str, object]:
        started = instant(self._now())
        today = started.astimezone(TAIPEI).date()
        try:
            stock_code(symbol)
        except ValueError:
            raise IntradayTechnicalIndicatorError("INVALID_REQUEST", request_error=True) from None

        query_from = (today - timedelta(days=29)).isoformat()
        query_to = today.isoformat()
        deadline = self._monotonic() + 60.0
        frames: dict[str, dict[str, object]] = {}

        for timeframe in ("1", "5"):
            results: dict[str, tuple[date, datetime | None, dict[str, str]]] = {}
            frame_observed: datetime | None = None
            for kind in ("kdj", "macd", "bb"):
                source = self._gateway.read_intraday_technical_indicator(
                    kind, symbol, query_from, query_to, timeframe, deadline=deadline,
                )
                observed = instant(self._now())
                if observed < started or observed.astimezone(TAIPEI).date() != today:
                    raise IntradayTechnicalIndicatorError("STALE_QUERY")
                source_date, source_timestamp, payload = self._normalize_response(
                    kind, source, symbol, query_from, query_to, timeframe, today, observed,
                )
                results[kind] = (source_date, source_timestamp, payload)
                frame_observed = observed if frame_observed is None else max(frame_observed, observed)

            dates = {item[0] for item in results.values()}
            if dates != {today}:
                raise IntradayTechnicalIndicatorError("TECHNICAL_SCHEMA_INVALID")
            timestamps = [item[1] for item in results.values()]
            if any(value is None for value in timestamps):
                if not all(value is None for value in timestamps):
                    raise IntradayTechnicalIndicatorError("TECHNICAL_SCHEMA_INVALID")
                source_timestamp = None
            else:
                unique_timestamps = set(timestamps)
                if len(unique_timestamps) != 1:
                    raise IntradayTechnicalIndicatorError("TECHNICAL_SCHEMA_INVALID")
                source_timestamp = timestamps[0]

            if frame_observed is None:
                raise IntradayTechnicalIndicatorError("TECHNICAL_SCHEMA_INVALID")
            frames[timeframe] = {
                "timeframe": timeframe,
                "sourceDate": today.isoformat(),
                "sourceTimestamp": None if source_timestamp is None else observed_at(source_timestamp),
                "observedAt": observed_at(frame_observed),
                "kdj": results["kdj"][2],
                "macd": results["macd"][2],
                "bollinger": results["bb"][2],
            }

        finished = instant(self._now())
        if finished < started or finished.astimezone(TAIPEI).date() != today:
            raise IntradayTechnicalIndicatorError("STALE_QUERY")
        return {
            "schemaVersion": 1,
            "symbol": symbol,
            "market": "台股",
            "provider": "FUBON_SDK",
            "observedAt": observed_at(finished),
            "oneMinute": frames["1"],
            "fiveMinute": frames["5"],
        }

    @staticmethod
    def _normalize_response(kind: str, source: object, symbol: str, start: str, end: str,
                            timeframe: str, today: date, observed: datetime
                            ) -> tuple[date, datetime | None, dict[str, str]]:
        parameters = dict(_KINDS[kind])
        required = {"symbol", "from", "to", "timeframe", *parameters, "data"}
        if not isinstance(source, dict) or set(source) != required:
            raise IntradayTechnicalIndicatorError("TECHNICAL_SCHEMA_INVALID")
        if (source.get("symbol") != symbol or source.get("from") != start or source.get("to") != end
                or source.get("timeframe") != timeframe):
            raise IntradayTechnicalIndicatorError("TECHNICAL_SCHEMA_INVALID")
        for name, expected in parameters.items():
            if type(source.get(name)) is not int or source[name] != expected:
                raise IntradayTechnicalIndicatorError("TECHNICAL_SCHEMA_INVALID")

        rows = source.get("data")
        if not isinstance(rows, list) or not rows or len(rows) > 20000:
            raise IntradayTechnicalIndicatorError("TECHNICAL_SCHEMA_INVALID")

        previous_date: date | None = None
        previous_timestamp: datetime | None = None
        normalized_rows: list[tuple[date, datetime | None, dict[str, str]]] = []
        expected_row_fields = {"date", *_FIELDS[kind]}
        for row in rows:
            if not isinstance(row, dict) or set(row) != expected_row_fields:
                raise IntradayTechnicalIndicatorError("TECHNICAL_SCHEMA_INVALID")
            source_date, source_timestamp = IntradayTechnicalIndicatorService._source_time(
                row.get("date"), start, end, observed,
            )
            if previous_date is not None and source_date < previous_date:
                raise IntradayTechnicalIndicatorError("TECHNICAL_SCHEMA_INVALID")
            if (source_timestamp is not None and previous_timestamp is not None
                    and source_timestamp <= previous_timestamp):
                raise IntradayTechnicalIndicatorError("TECHNICAL_SCHEMA_INVALID")
            payload = {
                field: canonical_number(row.get(field), precision=38, scale=18)
                for field in _FIELDS[kind]
            }
            if kind == "bb" and not Decimal(payload["upper"]) >= Decimal(payload["middle"]) >= Decimal(payload["lower"]):
                raise IntradayTechnicalIndicatorError("TECHNICAL_SCHEMA_INVALID")
            normalized_rows.append((source_date, source_timestamp, payload))
            previous_date, previous_timestamp = source_date, source_timestamp

        # The fixed vendor contract treats the final item as the latest point;
        # do not sort or reconstruct an indicator locally.
        latest = normalized_rows[-1]
        if latest[0] != today:
            raise IntradayTechnicalIndicatorError("TECHNICAL_SCHEMA_INVALID")
        return latest

    @staticmethod
    def _source_time(value: object, start: str, end: str, observed: datetime
                     ) -> tuple[date, datetime | None]:
        if not isinstance(value, str):
            raise IntradayTechnicalIndicatorError("TECHNICAL_SCHEMA_INVALID")
        try:
            if _ISO_DATE.fullmatch(value):
                source_date = strict_iso_date(value)
                source_timestamp = None
            else:
                # The date-only SDK shape is allowed, but a timestamp must be
                # full ISO-8601 with an explicit offset; naive local time is not evidence.
                if "T" not in value:
                    raise ValueError("INVALID_SOURCE_TIME")
                parsed = datetime.fromisoformat(value[:-1] + "+00:00" if value.endswith("Z") else value)
                if parsed.tzinfo is None or parsed.utcoffset() is None:
                    raise ValueError("INVALID_SOURCE_TIME")
                source_timestamp = instant(parsed)
                source_date = source_timestamp.astimezone(TAIPEI).date()
                if source_timestamp > observed:
                    raise ValueError("FUTURE_SOURCE_TIME")
            if source_date < strict_iso_date(start) or source_date > strict_iso_date(end):
                raise ValueError("SOURCE_DATE_OUT_OF_RANGE")
            return source_date, source_timestamp
        except ValueError:
            raise IntradayTechnicalIndicatorError("TECHNICAL_SCHEMA_INVALID") from None
