"""Narrow normalized ticker and one-minute-candle contracts for Task408.

The SDK payload is never returned.  Each success is rebuilt from a strict,
validated source snapshot so the Java side can treat it as a stable v1 wire
format rather than a vendor-object serialization.
"""
from __future__ import annotations

from collections.abc import Callable
from datetime import UTC, datetime, time as wall_time
from decimal import Decimal

from .normalization import TAIPEI, canonical_number, instant, observed_at, stock_code, strict_iso_date, utc_now
from .sdk_gateway import SdkGateway


def _task425_observed_at(value: datetime) -> str:
    """Fixed microsecond UTC wire representation permits a Redis lexical freshness fence."""
    return instant(value).strftime("%Y-%m-%dT%H:%M:%S.%fZ")


class MarketDataV1Error(ValueError):
    def __init__(self, reason: str, *, request_error: bool = False) -> None:
        super().__init__(reason)
        self.reason, self.request_error = reason, request_error


def _text(value: object, *, maximum: int, nullable: bool = False, ascii_only: bool = False,
          uppercase_currency: bool = False, preserve: bool = False) -> str | None:
    if value is None and nullable:
        return None
    if not isinstance(value, str):
        raise ValueError("SCHEMA_INVALID")
    if preserve and value != value.strip():
        raise ValueError("SCHEMA_INVALID")
    if not preserve:
        value = value.strip()
    if not value or len(value) > maximum:
        raise ValueError("SCHEMA_INVALID")
    if value.isspace() or any(ord(char) < 32 or ord(char) == 127 for char in value):
        raise ValueError("SCHEMA_INVALID")
    if ascii_only and (not value.isascii() or not all(32 <= ord(char) <= 126 for char in value)):
        raise ValueError("SCHEMA_INVALID")
    if uppercase_currency and (len(value) != 3 or not value.isascii() or not value.isalpha() or value != value.upper()):
        raise ValueError("SCHEMA_INVALID")
    return value


def _nullable_decimal(value: object) -> str | None:
    if value is None:
        return None
    return canonical_number(value, precision=20, scale=10, positive=True)


def _nullable_int(value: object, *, positive: bool) -> int | None:
    if value is None:
        return None
    if type(value) is not int or (positive and value <= 0) or (not positive and value < 0):
        raise ValueError("SCHEMA_INVALID")
    return value


def _root_identity(source: object, symbol: str, query_date: str, *, include_name: bool, allow_esb: bool = False,
                   source_market_maximum: int = 64) -> dict[str, object]:
    if not isinstance(source, dict):
        raise ValueError("SCHEMA_INVALID")
    required = {"date", "type", "exchange", "market", "symbol"}
    if include_name:
        required.add("name")
    if not required.issubset(source):
        raise ValueError("SCHEMA_INVALID")
    exchanges = {"TWSE", "TPEx", "ESB"} if allow_esb else {"TWSE", "TPEx"}
    if source["symbol"] != symbol or source["type"] != "EQUITY" or source["exchange"] not in exchanges:
        raise ValueError("SCHEMA_INVALID")
    if strict_iso_date(source["date"]).isoformat() != query_date:
        raise ValueError("STALE_QUERY")
    source_market = _text(source.get("market"), maximum=source_market_maximum, nullable=True, ascii_only=True, preserve=True)
    return {"schemaVersion": 1, "symbol": symbol, "market": "台股", "provider": "FUBON_SDK",
            "sourceDate": query_date, "instrumentType": "EQUITY", "exchange": source["exchange"],
            "sourceMarket": source_market}


class MarketDataV1Service:
    def __init__(self, gateway: SdkGateway, *, now: Callable[[], datetime] = utc_now) -> None:
        self._gateway, self._now = gateway, now

    def basic(self, symbol: str) -> dict[str, object]:
        started = instant(self._now())
        try:
            stock_code(symbol)
        except ValueError:
            raise MarketDataV1Error("INVALID_REQUEST", request_error=True) from None
        query_date = started.astimezone(TAIPEI).date().isoformat()
        source = self._gateway.read_ticker(symbol)
        returned = instant(self._now())
        try:
            if returned < started or returned.astimezone(TAIPEI).date().isoformat() != query_date:
                raise ValueError("STALE_QUERY")
            result = _root_identity(source, symbol, query_date, include_name=True)
            # Deliberately rebuild only the permitted basic-info fields.
            result.update({
                "observedAt": observed_at(returned),
                "sourceName": _text(source.get("name"), maximum=100, preserve=True),
                "industry": _text(source.get("industry"), maximum=100, nullable=True),
                "securityType": _text(source.get("securityType"), maximum=64, nullable=True),
                "limitUpPrice": _nullable_decimal(source.get("limitUpPrice")),
                "limitDownPrice": _nullable_decimal(source.get("limitDownPrice")),
                "tradingStatus": self._status(source.get("securityStatus")),
                "matchingInterval": _nullable_int(source.get("matchingInterval"), positive=False),
                "boardLot": _nullable_int(source.get("boardLot"), positive=True),
                "currency": _text(source.get("currency"), maximum=10, nullable=True, uppercase_currency=True),
            })
            status = result["tradingStatus"]
            result["tradingEligible"] = None if status is None else status == "NORMAL"
            return self._ordered_basic(result)
        except MarketDataV1Error:
            raise
        except ValueError as error:
            reason = "STALE_QUERY" if str(error) == "STALE_QUERY" else "SCHEMA_INVALID"
            raise MarketDataV1Error(reason) from None

    def candles(self, symbol: str) -> dict[str, object]:
        started = instant(self._now())
        try:
            stock_code(symbol)
        except ValueError:
            raise MarketDataV1Error("INVALID_REQUEST", request_error=True) from None
        query_date = started.astimezone(TAIPEI).date().isoformat()
        source = self._gateway.read_intraday_candles(symbol)
        returned = instant(self._now())
        try:
            if returned < started or returned.astimezone(TAIPEI).date().isoformat() != query_date:
                raise ValueError("STALE_QUERY")
            if not isinstance(source, dict):
                raise ValueError("SCHEMA_INVALID")
            allowed = {"date", "type", "exchange", "market", "symbol", "timeframe", "data"}
            if not set(source).issubset(allowed) or "data" not in source:
                raise ValueError("SCHEMA_INVALID")
            if "timeframe" in source and source["timeframe"] != 1:
                raise ValueError("SCHEMA_INVALID")
            identity = _root_identity(source, symbol, query_date, include_name=False)
            rows = source.get("data")
            if not isinstance(rows, list) or len(rows) > 270:
                raise ValueError("SCHEMA_INVALID")
            candles = [self._candle(row, query_date) for row in rows]
            if [row["candleAt"] for row in candles] != sorted(row["candleAt"] for row in candles):
                raise ValueError("SCHEMA_INVALID")
            if len({row["candleAt"] for row in candles}) != len(candles):
                raise ValueError("SCHEMA_INVALID")
            status, reason = ("NO_DATA", "NO_DATA") if not candles else ("AVAILABLE", None)
            return {"schemaVersion": 1, "symbol": identity["symbol"], "market": identity["market"],
                    "provider": identity["provider"], "sourceDate": identity["sourceDate"],
                    "observedAt": observed_at(returned), "instrumentType": identity["instrumentType"],
                    "exchange": identity["exchange"], "sourceMarket": identity["sourceMarket"],
                    "timeframe": 1, "status": status, "reason": reason, "candles": candles}
        except MarketDataV1Error:
            raise
        except ValueError as error:
            reason = "STALE_QUERY" if str(error) == "STALE_QUERY" else "SCHEMA_INVALID"
            raise MarketDataV1Error(reason) from None

    def volumes(self, symbol: str) -> dict[str, object]:
        started = instant(self._now())
        try:
            stock_code(symbol)
        except ValueError:
            raise MarketDataV1Error("INVALID_REQUEST", request_error=True) from None
        query_date = started.astimezone(TAIPEI).date().isoformat()
        source = self._gateway.read_intraday_volumes(symbol)
        returned = instant(self._now())
        try:
            if returned < started or returned.astimezone(TAIPEI).date().isoformat() != query_date:
                raise ValueError("STALE_QUERY")
            if not isinstance(source, dict):
                raise ValueError("SCHEMA_INVALID")
            allowed = {"date", "type", "exchange", "market", "symbol", "data"}
            if not set(source).issubset(allowed) or "data" not in source:
                raise ValueError("SCHEMA_INVALID")
            identity = _root_identity(source, symbol, query_date, include_name=False, allow_esb=True, source_market_maximum=20)
            rows = source.get("data")
            if not isinstance(rows, list) or len(rows) > 10000:
                raise ValueError("SCHEMA_INVALID")
            levels = [self._volume(row) for row in rows]
            if [Decimal(row["price"]) for row in levels] != sorted(Decimal(row["price"]) for row in levels):
                raise ValueError("SCHEMA_INVALID")
            if len({row["price"] for row in levels}) != len(levels):
                raise ValueError("SCHEMA_INVALID")
            status, reason = ("NO_DATA", "NO_DATA") if not levels else ("OK", None)
            return {"schemaVersion": 1, "symbol": identity["symbol"], "market": identity["market"],
                    "provider": identity["provider"], "sourceDate": identity["sourceDate"],
                    "observedAt": _task425_observed_at(returned), "instrumentType": identity["instrumentType"],
                    "exchange": identity["exchange"], "sourceMarket": identity["sourceMarket"],
                    "status": status, "reason": reason, "levels": levels}
        except ValueError as error:
            reason = "STALE_QUERY" if str(error) == "STALE_QUERY" else "INVALID_RESPONSE"
            raise MarketDataV1Error(reason) from None

    def daily_candles(self, symbol: str, start_date: str, end_date: str) -> dict[str, object]:
        try:
            stock_code(symbol)
            start, end = strict_iso_date(start_date), strict_iso_date(end_date)
            if end < start or (end - start).days + 1 > 366:
                raise ValueError("INVALID_REQUEST")
        except ValueError:
            raise MarketDataV1Error("INVALID_REQUEST", request_error=True) from None
        started = instant(self._now())
        source = self._gateway.read_historical_daily_candles(symbol, start_date, end_date)
        returned = instant(self._now())
        try:
            if returned < started or not isinstance(source, dict):
                raise ValueError("SCHEMA_INVALID")
            allowed = {"type", "exchange", "market", "symbol", "data"}
            if not set(source).issubset(allowed) or "data" not in source:
                raise ValueError("SCHEMA_INVALID")
            if source.get("symbol") != symbol or source.get("type") != "EQUITY" or source.get("exchange") not in {"TWSE", "TPEx", "ESB"}:
                raise ValueError("SCHEMA_INVALID")
            source_market = _text(source.get("market"), maximum=20, nullable=True, ascii_only=True, preserve=True)
            rows = source.get("data")
            if not isinstance(rows, list) or len(rows) > 366:
                raise ValueError("SCHEMA_INVALID")
            candles = [self._daily_candle(row, start, end) for row in rows]
            if [row["tradingDate"] for row in candles] != sorted(row["tradingDate"] for row in candles):
                raise ValueError("SCHEMA_INVALID")
            if len({row["tradingDate"] for row in candles}) != len(candles):
                raise ValueError("SCHEMA_INVALID")
            status, reason = ("NO_DATA", "NO_DATA") if not candles else ("OK", None)
            return {"schemaVersion": 1, "symbol": symbol, "market": "台股", "provider": "FUBON_SDK",
                    "queryFrom": start_date, "queryTo": end_date, "observedAt": _task425_observed_at(returned),
                    "instrumentType": "EQUITY", "exchange": source["exchange"], "sourceMarket": source_market,
                    "status": status, "reason": reason, "candles": candles}
        except ValueError:
            raise MarketDataV1Error("INVALID_RESPONSE") from None

    @staticmethod
    def _status(value: object) -> str | None:
        if value is None:
            return None
        if value not in {"NORMAL", "TERMINATED", "SUSPENDED"}:
            raise ValueError("SCHEMA_INVALID")
        return str(value)

    @staticmethod
    def _ordered_basic(result: dict[str, object]) -> dict[str, object]:
        keys = ("schemaVersion", "symbol", "market", "provider", "sourceDate", "observedAt", "instrumentType", "exchange",
                "sourceMarket", "sourceName", "industry", "securityType", "limitUpPrice", "limitDownPrice",
                "tradingEligible", "tradingStatus", "matchingInterval", "boardLot", "currency")
        return {key: result[key] for key in keys}

    @staticmethod
    def _candle(row: object, query_date: str) -> dict[str, str]:
        if not isinstance(row, dict) or set(row) != {"date", "open", "high", "low", "close", "volume", "average"}:
            raise ValueError("SCHEMA_INVALID")
        value = row.get("date")
        if not isinstance(value, str):
            raise ValueError("SCHEMA_INVALID")
        try:
            parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError:
            raise ValueError("SCHEMA_INVALID") from None
        if parsed.tzinfo is None or parsed.second != 0 or parsed.microsecond != 0:
            raise ValueError("SCHEMA_INVALID")
        parsed = parsed.astimezone(UTC)
        local = parsed.astimezone(TAIPEI)
        if local.date().isoformat() != query_date or not (wall_time(9, 0) <= local.time() < wall_time(13, 30)):
            raise ValueError("SCHEMA_INVALID")
        open_ = canonical_number(row.get("open"), precision=20, scale=10, positive=True)
        high = canonical_number(row.get("high"), precision=20, scale=10, positive=True)
        low = canonical_number(row.get("low"), precision=20, scale=10, positive=True)
        close = canonical_number(row.get("close"), precision=20, scale=10, positive=True)
        average = canonical_number(row.get("average"), precision=20, scale=10, positive=True)
        if not Decimal(high) >= Decimal(open_) >= Decimal(low) or not Decimal(high) >= Decimal(close) >= Decimal(low):
            raise ValueError("SCHEMA_INVALID")
        if not Decimal(low) <= Decimal(average) <= Decimal(high):
            raise ValueError("SCHEMA_INVALID")
        volume = row.get("volume")
        if isinstance(volume, bool) or not isinstance(volume, (str, int)):
            raise ValueError("SCHEMA_INVALID")
        volume_text = str(volume)
        if not (volume_text == "0" or (volume_text.isascii() and volume_text.isdigit() and not volume_text.startswith("0"))):
            raise ValueError("SCHEMA_INVALID")
        if int(volume_text) > 9223372036854775807:
            raise ValueError("SCHEMA_INVALID")
        return {"candleAt": observed_at(parsed), "open": open_, "high": high, "low": low,
                "close": close, "volume": volume_text, "average": average}

    @staticmethod
    def _nonnegative_int_text(value: object) -> str:
        if isinstance(value, bool) or not isinstance(value, (str, int)):
            raise ValueError("SCHEMA_INVALID")
        text = str(value)
        if not (text == "0" or (text.isascii() and text.isdigit() and not text.startswith("0"))) or int(text) > 9223372036854775807:
            raise ValueError("SCHEMA_INVALID")
        return text

    @classmethod
    def _volume(cls, row: object) -> dict[str, object]:
        if not isinstance(row, dict) or set(row) != {"price", "volume", "bidVolume", "askVolume"}:
            raise ValueError("SCHEMA_INVALID")
        bid, ask = row.get("bidVolume"), row.get("askVolume")
        return {"price": canonical_number(row.get("price"), precision=20, scale=10, positive=True),
                "volume": cls._nonnegative_int_text(row.get("volume")),
                "bidVolume": None if bid is None else cls._nonnegative_int_text(bid),
                "askVolume": None if ask is None else cls._nonnegative_int_text(ask)}

    @classmethod
    def _daily_candle(cls, row: object, start: object, end: object) -> dict[str, object]:
        if not isinstance(row, dict) or set(row) != {"date", "open", "high", "low", "close", "volume", "turnover", "change"}:
            raise ValueError("SCHEMA_INVALID")
        trading_date = strict_iso_date(row.get("date")) if isinstance(row.get("date"), str) else None
        if trading_date is None or trading_date < start or trading_date > end:
            raise ValueError("SCHEMA_INVALID")
        open_ = canonical_number(row.get("open"), precision=20, scale=10, positive=True)
        high = canonical_number(row.get("high"), precision=20, scale=10, positive=True)
        low = canonical_number(row.get("low"), precision=20, scale=10, positive=True)
        close = canonical_number(row.get("close"), precision=20, scale=10, positive=True)
        turnover = canonical_number(row.get("turnover"), precision=20, scale=10, nonnegative=True)
        if Decimal(high) < Decimal(open_) or Decimal(high) < Decimal(close) or Decimal(open_) < Decimal(low) or Decimal(close) < Decimal(low):
            raise ValueError("SCHEMA_INVALID")
        change = row.get("change")
        return {"tradingDate": trading_date.isoformat(), "open": open_, "high": high, "low": low, "close": close,
                "volume": cls._nonnegative_int_text(row.get("volume")), "turnover": turnover,
                "change": None if change is None else canonical_number(change, precision=20, scale=10, positive=False)}
