from __future__ import annotations

import asyncio
import hashlib
import json
import re
from datetime import UTC, date, datetime
from decimal import Decimal
from typing import Callable
from zoneinfo import ZoneInfo

from .numeric import NumericError, canonical_decimal
from .sdk_gateway import SdkCallError, SdkGateway


TW_ZONE = ZoneInfo("Asia/Taipei")
_ETF_CODE = re.compile(r"00[0-9]{2,3}(?:[0-9A-Z])?")
_ISO_DATE = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}")
_SDK_FAILURE_REASONS = frozenset({
    "DISABLED", "MISCONFIGURED", "RUNTIME_MISCONFIGURED", "INVALID_ENABLED_FLAG",
    "MISSING_REQUIRED_SECRET", "INCOMPLETE_ACCOUNT_SELECTOR", "AUTH_SESSION_INVALID",
    "REALTIME_NOT_INITIALIZED", "ETF_HOLDINGS_CLIENT_UNAVAILABLE", "ETF_HOLDINGS_TIMEOUT",
    "ETF_HOLDINGS_TRANSPORT_FAILED", "SDK_CALL_SATURATED", "RATE_LIMITED", "LOGIN_TIMEOUT",
    "SDK_LOGIN_FAILED", "INVALID_ACCOUNT_LIST", "INVALID_STOCK_ACCOUNT",
    "STOCK_ACCOUNT_NOT_UNIQUE", "ACCOUNT_SELECTOR_NOT_UNIQUE", "REALTIME_INIT_TIMEOUT",
    "REALTIME_INIT_FAILED", "REALTIME_CLIENT_MISSING",
})


class EtfHoldingsService:
    """Read and normalize the official ownership.etf_holdings market-data schema.

    Only the latest provider source date and allowlisted constituent fields
    leave the adapter. The legacy rawResponseJson wire name contains our versioned
    normalized object, never the SDK response. Official schema support does not
    imply that a particular deployment has successfully queried the provider.
    """

    # Modest bound so a 50-code batch does not immediately overrun the
    # gateway's shared _blocking_slots (MAX_BLOCKING_CALLS=4) and turn every
    # call beyond the first few into a spurious SDK_CALL_SATURATED failure.
    MAX_CONCURRENCY = 4
    MAX_CODES = 50

    def __init__(
        self,
        gateway: SdkGateway,
        now: Callable[[], datetime] | None = None,
    ) -> None:
        self._gateway = gateway
        self._now = now or (lambda: datetime.now(UTC))
        self._semaphore = asyncio.Semaphore(self.MAX_CONCURRENCY)

    @staticmethod
    def validate_codes(codes: list[str]) -> list[str]:
        if not isinstance(codes, list) or not 1 <= len(codes) <= EtfHoldingsService.MAX_CODES:
            raise ValueError("INVALID_CODE_COUNT")
        seen: set[str] = set()
        for code in codes:
            if not isinstance(code, str) or code == "0000" or not _ETF_CODE.fullmatch(code):
                raise ValueError("INVALID_ETF_CODE")
            if code in seen:
                raise ValueError("DUPLICATE_CODE")
            seen.add(code)
        return codes

    async def read(self, codes: list[str]) -> dict[str, object]:
        codes = self.validate_codes(codes)
        today = self._now().astimezone(TW_ZONE).date()
        values = await asyncio.gather(*(self._read_one(code, today) for code in codes))
        seed = ":".join(codes) + ":" + today.isoformat()
        return {
            "batchId": hashlib.sha256(seed.encode("utf-8")).hexdigest()[:24],
            "holdings": list(values),
        }

    async def _read_one(self, code: str, today: date) -> dict[str, object]:
        async with self._semaphore:
            try:
                response = await asyncio.to_thread(self._gateway.read_etf_holdings, code)
            except SdkCallError as exc:
                reason = exc.reason if exc.reason in _SDK_FAILURE_REASONS else "ETF_HOLDINGS_FAILED"
                return self._failure(code, reason)
            except Exception:
                return self._failure(code, "ETF_HOLDINGS_FAILED")
        try:
            normalized = self._normalize_response(code, response, today)
            payload = json.dumps(normalized, ensure_ascii=False, allow_nan=False, separators=(",", ":"))
        except Exception:
            # Never serialize or log an SDK object, its repr, or validation details.
            return self._failure(code, "ETF_HOLDINGS_INVALID_RESPONSE")
        return {
            "stockCode": code,
            "status": "SUCCESS",
            "reason": None,
            "rawResponseJson": payload,
        }

    @staticmethod
    def _normalize_response(code: str, response: object, today: date) -> dict[str, object]:
        if not isinstance(response, dict) or response.get("symbol") != code:
            raise ValueError("INVALID_PROVIDER_SYMBOL")
        days = response.get("data")
        if not isinstance(days, list):
            raise ValueError("INVALID_PROVIDER_DATA")
        normalized: dict[str, object] = {
            "schemaVersion": 1, "stockCode": code, "sourceDate": None, "holdings": [],
        }
        if not days:
            return normalized

        latest_date: date | None = None
        latest_components: list[object] = []
        seen_dates: set[date] = set()
        for day in days:
            if not isinstance(day, dict):
                raise ValueError("INVALID_PROVIDER_DAY")
            raw_date = day.get("date")
            if not isinstance(raw_date, str) or not _ISO_DATE.fullmatch(raw_date):
                raise ValueError("INVALID_PROVIDER_DATE")
            source_date = date.fromisoformat(raw_date)
            if source_date > today or source_date in seen_dates:
                raise ValueError("INVALID_PROVIDER_DATE")
            seen_dates.add(source_date)
            components = day.get("components")
            if not isinstance(components, list):
                raise ValueError("INVALID_PROVIDER_COMPONENTS")
            if latest_date is None or source_date > latest_date:
                latest_date = source_date
                latest_components = components

        holdings = []
        for component in latest_components:
            holding = EtfHoldingsService._normalize_component(component)
            if holding is not None:
                holdings.append(holding)
        if latest_components and not holdings:
            raise ValueError("NO_VALID_PROVIDER_COMPONENTS")
        normalized["sourceDate"] = latest_date.isoformat()
        normalized["holdings"] = holdings
        return normalized

    @staticmethod
    def _normalize_component(component: object) -> dict[str, object] | None:
        if not isinstance(component, dict):
            return None
        code, name = component.get("symbol"), component.get("name")
        if not isinstance(code, str) or not code.strip() or not isinstance(name, str) or not name.strip():
            return None
        try:
            weight = canonical_decimal(component.get("weight"), positive=False)
            if Decimal(weight) > 100:
                return None
            quantity = component.get("quantity")
            shares = canonical_decimal(quantity, positive=False) if quantity is not None else None
        except NumericError:
            return None
        return {"stockCode": code.strip(), "stockName": name.strip(), "weight": weight, "shares": shares}

    @staticmethod
    def _failure(code: str, reason: str) -> dict[str, object]:
        return {"stockCode": code, "status": "FAILURE", "reason": reason, "rawResponseJson": None}
