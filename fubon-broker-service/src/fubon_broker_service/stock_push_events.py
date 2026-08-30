from __future__ import annotations

import json
from dataclasses import asdict, dataclass
from datetime import UTC, datetime, time, timedelta
from decimal import Decimal, DecimalException

from .normalization import TAIPEI, canonical_number, instant, signed_integer, stock_code, strict_iso_date


MAX_PROVIDER_FRAME_BYTES = 256 * 1024
MAX_SSE_FRAME_BYTES = 16 * 1024
_EPOCH = datetime(1970, 1, 1, tzinfo=UTC)
_OPEN, _CLOSE = time(9), time(13, 30)
_MAX_INT64 = 9_223_372_036_854_775_807


def market_time_open(now: datetime) -> bool:
    return _OPEN <= instant(now).astimezone(TAIPEI).time() < _CLOSE


def _unique_pairs(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("DUPLICATE_FIELD")
        result[key] = value
    return result


def _invalid_constant(_value: str) -> object:
    raise ValueError("INVALID_NUMBER")


def decode_stock_message(message: object) -> dict[str, object] | None:
    try:
        if isinstance(message, (str, bytes)):
            if len(message.encode("utf-8") if isinstance(message, str) else message) > MAX_PROVIDER_FRAME_BYTES:
                return None
            message = json.loads(message, parse_float=Decimal, object_pairs_hook=_unique_pairs,
                                 parse_constant=_invalid_constant)
        elif isinstance(message, dict):
            # Some SDK/fake callback implementations already decode JSON. Keep the same size cap.
            size = 0
            for part in json.JSONEncoder(ensure_ascii=False, default=str).iterencode(message):
                size += len(part.encode("utf-8"))
                if size > MAX_PROVIDER_FRAME_BYTES:
                    return None
        return message if isinstance(message, dict) else None
    except (ValueError, TypeError, UnicodeError, RecursionError, DecimalException):
        return None


def positive_int64(value: object) -> int:
    parsed = int(signed_integer(value))
    if parsed <= 0 or parsed > _MAX_INT64:
        raise ValueError("INVALID_POSITIVE_INTEGER")
    return parsed


@dataclass(frozen=True)
class StockPriceEvent:
    symbol: str
    exchange: str
    sourceDate: str
    tradeTimeMicros: int
    tradeSize: int
    price: str
    previousClose: str | None
    openPrice: str | None
    highPrice: str | None
    lowPrice: str | None
    name: str | None
    market: str = "台股"
    type: str = "EQUITY"
    source: str = "FUBON_WS_AGGREGATES"
    buyPrice: None = None
    sellPrice: None = None
    volume: None = None

    def sse_bytes(self) -> bytes:
        payload = json.dumps(asdict(self), ensure_ascii=False, separators=(",", ":"))
        frame = f"event: stock-price\nid: {self.symbol}:{self.tradeTimeMicros}\ndata: {payload}\n\n".encode("utf-8")
        if len(frame) > MAX_SSE_FRAME_BYTES:
            raise ValueError("STOCK_EVENT_TOO_LARGE")
        return frame


def normalize_stock_message(
    envelope: dict[str, object], *, confirmed: dict[str, str], now: datetime,
) -> StockPriceEvent | None:
    """Trust only the actual trade and complete, internally consistent same-packet metadata."""
    try:
        now = instant(now)
        if not market_time_open(now) or envelope.get("event") != "data" or envelope.get("channel") != "aggregates":
            return None
        data = envelope.get("data")
        if not isinstance(data, dict):
            return None
        symbol = stock_code(data.get("symbol"))
        if symbol not in confirmed or envelope.get("id") != confirmed[symbol]:
            return None
        exchange = data.get("exchange")
        if exchange not in {"TWSE", "TPEx"} or data.get("type") != "EQUITY":
            return None
        if "isTrial" in data and (type(data["isTrial"]) is not bool or data["isTrial"]):
            return None
        if "intradayOddLot" in data and data["intradayOddLot"] is not False:
            return None
        source_date = strict_iso_date(data.get("date"))
        trade = data.get("lastTrade")
        if not isinstance(trade, dict):
            return None
        price = canonical_number(trade.get("price"), precision=20, scale=10, positive=True)
        size, micros = positive_int64(trade.get("size")), positive_int64(trade.get("time"))
        traded_at = _EPOCH + timedelta(microseconds=micros)
        if (traded_at > now or traded_at.astimezone(TAIPEI).date() != source_date
                or now.astimezone(TAIPEI).date() != source_date or not market_time_open(traded_at)):
            return None
        if "closePrice" in data and canonical_number(data["closePrice"], precision=20, scale=10, positive=True) != price:
            return None
        if "closeTime" in data and positive_int64(data["closeTime"]) != micros:
            return None
        metadata = {
            field: None if data.get(field) is None else canonical_number(data[field], precision=20, scale=10, positive=True)
            for field in ("previousClose", "openPrice", "highPrice", "lowPrice")
        }
        high, low, opened = (Decimal(metadata[key]) if metadata[key] is not None else None
                             for key in ("highPrice", "lowPrice", "openPrice"))
        actual = Decimal(price)
        if (high is not None and (high < actual or opened is not None and high < opened)
                or low is not None and (low > actual or opened is not None and low > opened)
                or high is not None and low is not None and high < low):
            return None
        name = data.get("name")
        if name is not None:
            if not isinstance(name, str):
                return None
            name = name.strip()
            if not name or len(name) > 100 or any(ord(char) < 32 or 127 <= ord(char) <= 159 for char in name):
                return None
        # Aggregate size/volume units have not been proved to match the cache's shares unit.
        # Keep volume and bid/ask null; the supplied size only proves a nonzero actual trade.
        return StockPriceEvent(symbol, exchange, source_date.isoformat(), micros, size, price, name=name, **metadata)
    except (ValueError, TypeError, OverflowError):
        return None
