from __future__ import annotations

from collections.abc import Callable
from datetime import datetime

from .accounting_normalization import checked_result, observation, verify_identity
from .normalization import signed_integer, utc_now
from .sdk_gateway import SdkGateway, raw_field


class BankBalanceError(ValueError):
    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


class BankBalanceService:
    """Validate the official Result/BankRemain before exposing a fingerprint-only DTO."""

    def __init__(self, gateway: SdkGateway, *, now: Callable[[], datetime] = utc_now) -> None:
        self._gateway, self._now = gateway, now

    def read(self) -> dict[str, object]:
        started = self._now()
        read = self._gateway.read_bank_balance()
        try:
            data = checked_result(read)
            verify_identity(data, read.account)
            if raw_field(data, "currency") != "TWD":
                raise ValueError("RECONCILE_FAILED")
            balance = signed_integer(raw_field(data, "balance"), nonnegative=True)
            available = signed_integer(raw_field(data, "available_balance"), nonnegative=True)
            return {**observation(read, started, self._now()), "currency": "TWD",
                    "balance": balance, "availableBalance": available}
        except ValueError as exc:
            reason = "STALE_QUERY" if str(exc) == "STALE_QUERY" else "RECONCILE_FAILED"
            raise BankBalanceError(reason) from None
