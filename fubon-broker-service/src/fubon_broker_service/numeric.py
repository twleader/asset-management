from __future__ import annotations

import re
from decimal import Decimal, InvalidOperation


MAX_SHARES = 9_999_999_999
MAX_VOLUME = 9_223_372_036_854_775_807
_CANONICAL_DECIMAL = re.compile(r"^(0|[1-9][0-9]*)(?:\.([0-9]+))?$")


class NumericError(ValueError):
    pass


def canonical_decimal(value: object, *, positive: bool = True) -> str:
    if isinstance(value, bool) or value is None:
        raise NumericError("INVALID_DECIMAL")
    source = str(value)
    if "e" in source.lower():
        raise NumericError("NON_CANONICAL_DECIMAL")
    try:
        decimal_value = Decimal(source)
    except (InvalidOperation, ValueError):
        raise NumericError("INVALID_DECIMAL") from None
    if not decimal_value.is_finite():
        raise NumericError("NON_FINITE_DECIMAL")
    if positive and decimal_value <= 0:
        raise NumericError("NON_POSITIVE_DECIMAL")
    if not positive and decimal_value < 0:
        raise NumericError("NEGATIVE_DECIMAL")

    rendered = format(decimal_value, "f")
    if rendered.startswith("+"):
        rendered = rendered[1:]
    match = _CANONICAL_DECIMAL.fullmatch(rendered)
    if not match:
        raise NumericError("NON_CANONICAL_DECIMAL")
    scale = len(match.group(2) or "")
    digits = rendered.replace(".", "").lstrip("0")
    precision = len(digits) if digits else 1
    if precision > 20:
        raise NumericError("DECIMAL_PRECISION_EXCEEDED")
    if scale > 10:
        raise NumericError("DECIMAL_SCALE_EXCEEDED")
    return rendered


def exact_integer(value: object, *, upper: int) -> int:
    if isinstance(value, bool) or value is None:
        raise NumericError("INVALID_INTEGER")
    if isinstance(value, int):
        parsed = value
    elif isinstance(value, str) and re.fullmatch(r"0|[1-9][0-9]*", value):
        parsed = int(value)
    else:
        raise NumericError("INVALID_INTEGER")
    if parsed < 0 or parsed > upper:
        raise NumericError("INTEGER_RANGE_EXCEEDED")
    return parsed


def checked_share_add(left: int, right: int) -> int:
    total = left + right
    if total > MAX_SHARES:
        raise NumericError("SHARES_RANGE_EXCEEDED")
    return total
