"""Pure accessors for in-memory broker values; no SDK import or IO."""
from __future__ import annotations

import re


_PORTFOLIO_NATIVE_ENUM_TEXT = re.compile(r"\A(?:OrderType|BSAction)\.([A-Za-z_][A-Za-z0-9_]*)\Z")


def raw_field(value: object, name: str) -> object | None:
    if isinstance(value, dict):
        return value.get(name)
    return getattr(value, name, None)


def enum_text(value: object, *, allow_native_portfolio_text: bool = False) -> str | None:
    if value is None:
        return None
    candidate = getattr(value, "value", value)
    if not isinstance(candidate, (str, int)):
        candidate = getattr(value, "name", None)
    if candidate is not None:
        return str(candidate)
    if not allow_native_portfolio_text:
        return None
    try:
        native_text = str(value)
    except Exception:
        return None
    match = _PORTFOLIO_NATIVE_ENUM_TEXT.fullmatch(native_text)
    return match.group(1) if match else None
