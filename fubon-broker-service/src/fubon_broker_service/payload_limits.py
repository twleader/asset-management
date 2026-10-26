"""Pure decoded-payload bounds shared by normalizers and the outer adapter."""
from __future__ import annotations

import json

from .errors import SdkCallError


def check_marketdata_size(response: object, *, maximum_bytes: int = 8 * 1024 * 1024) -> None:
    # SDK responses are already decoded; bound the adapter payload without logging it or
    # allocating another unbounded serialized copy. The blocking-call semaphore also bounds
    # timed-out SDK requests that cannot be interrupted by Python.
    try:
        size = 0
        for chunk in json.JSONEncoder(ensure_ascii=False, default=str).iterencode(response):
            size += len(chunk.encode("utf-8"))
            if size > maximum_bytes:
                raise SdkCallError("MARKETDATA_RESPONSE_TOO_LARGE")
    except (TypeError, ValueError):
        raise SdkCallError("MARKETDATA_SCHEMA_INVALID") from None
