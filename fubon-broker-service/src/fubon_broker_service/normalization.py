from __future__ import annotations

import math
import re
from datetime import UTC, date, datetime
from decimal import Decimal, InvalidOperation
from zoneinfo import ZoneInfo


TAIPEI = ZoneInfo("Asia/Taipei")
_ISO_DATE = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}")
_VENDOR_DATE = re.compile(r"[0-9]{4}/[0-9]{2}/[0-9]{2}")
_STOCK_CODE = re.compile(r"[0-9A-Z]{2,10}")
_SIGNED_INTEGER = re.compile(r"0|-?[1-9][0-9]*")
_DECIMAL = re.compile(r"-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?")


def strict_iso_date(value: object) -> date:
    if not isinstance(value, str) or not _ISO_DATE.fullmatch(value):
        raise ValueError("INVALID_DATE")
    parsed = date.fromisoformat(value)
    if parsed.isoformat() != value:
        raise ValueError("INVALID_DATE")
    return parsed


def strict_vendor_date(value: object) -> date:
    if not isinstance(value, str) or not _VENDOR_DATE.fullmatch(value):
        raise ValueError("INVALID_DATE")
    return strict_iso_date(value.replace("/", "-"))


def stock_code(value: object) -> str:
    if not isinstance(value, str) or not _STOCK_CODE.fullmatch(value) or value == "0000":
        raise ValueError("INVALID_SYMBOL")
    return value


def stock_codes(values: object, *, maximum: int, allow_empty: bool = False) -> list[str]:
    if not isinstance(values, list) or len(values) > maximum or not (values or allow_empty):
        raise ValueError("INVALID_SYMBOLS")
    checked = [stock_code(value) for value in values]
    if len(set(checked)) != len(checked):
        raise ValueError("DUPLICATE_SYMBOL")
    return checked


def utc_now() -> datetime:
    return datetime.now(UTC)


def instant(value: datetime) -> datetime:
    if not isinstance(value, datetime) or value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("INVALID_OBSERVATION_TIME")
    return value.astimezone(UTC)


def observed_at(value: datetime) -> str:
    return instant(value).isoformat().replace("+00:00", "Z")


def signed_integer(value: object, *, precision: int = 20, nonnegative: bool = False) -> str:
    if isinstance(value, bool) or not isinstance(value, (str, int)):
        raise ValueError("INVALID_INTEGER")
    text = str(value)
    if not _SIGNED_INTEGER.fullmatch(text):
        raise ValueError("INVALID_INTEGER")
    number = int(text)
    if nonnegative and number < 0 or len(str(abs(number))) > precision:
        raise ValueError("INTEGER_RANGE_EXCEEDED")
    return str(number)


def decimal_value(value: object, *, allow_float: bool = True) -> Decimal:
    if isinstance(value, bool) or value is None:
        raise ValueError("INVALID_DECIMAL")
    if isinstance(value, float):
        if not allow_float or not math.isfinite(value):
            raise ValueError("INVALID_DECIMAL")
        # The SDK has already decoded this float; do not claim to recover lost provider digits.
        return Decimal(str(value))
    if isinstance(value, Decimal):
        if not value.is_finite():
            raise ValueError("INVALID_DECIMAL")
        return value
    if not isinstance(value, (str, int)) or not _DECIMAL.fullmatch(str(value)):
        raise ValueError("INVALID_DECIMAL")
    try:
        parsed = Decimal(str(value))
    except InvalidOperation:
        raise ValueError("INVALID_DECIMAL") from None
    if not parsed.is_finite():
        raise ValueError("INVALID_DECIMAL")
    return parsed


def canonical_number(
    value: object, *, precision: int, scale: int, positive: bool = False,
    nonnegative: bool = False, allow_float: bool = True,
) -> str:
    parsed = decimal_value(value, allow_float=allow_float)
    if positive and parsed <= 0 or nonnegative and parsed < 0:
        raise ValueError("INVALID_DECIMAL_SIGN")
    if parsed == 0:
        return "0"
    if parsed != 0:
        parts = parsed.as_tuple()
        significant = len(parts.digits)
        while significant > 1 and parts.digits[significant - 1] == 0:
            significant -= 1
        effective_exponent = parts.exponent + len(parts.digits) - significant
        if max(0, -effective_exponent) > scale or significant + max(0, effective_exponent) > precision:
            raise ValueError("DECIMAL_RANGE_EXCEEDED")
    rendered = format(parsed, "f")
    if "." in rendered:
        rendered = rendered.rstrip("0").rstrip(".")
    fractional = rendered.partition(".")[2]
    digits = rendered.lstrip("-").replace(".", "").lstrip("0")
    if len(fractional) > scale or max(1, len(digits)) > precision:
        raise ValueError("DECIMAL_RANGE_EXCEEDED")
    return rendered


def require_fields(value: object, fields: tuple[str, ...]) -> None:
    if isinstance(value, dict):
        present = all(field in value for field in fields)
    else:
        present = value is not None and all(hasattr(value, field) for field in fields)
    if not present:
        raise ValueError("MISSING_SOURCE_FIELD")
