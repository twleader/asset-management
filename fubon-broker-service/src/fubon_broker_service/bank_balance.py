from __future__ import annotations

from collections.abc import Callable
from datetime import datetime

from .accounting_normalization import checked_result, observation, verify_identity
from .normalization import signed_integer, utc_now
from .sdk_gateway import SdkGateway, raw_field


class BankBalanceError(ValueError):
    def __init__(self, reason: str, stage: str = "unknown") -> None:
        super().__init__(reason)
        self.reason = reason
        # Non-sensitive check name for operators; never exposed in the HTTP body.
        self.stage = stage


class BankBalanceService:
    """Validate the official Result/BankRemain before exposing a fingerprint-only DTO."""

    def __init__(self, gateway: SdkGateway, *, now: Callable[[], datetime] = utc_now) -> None:
        self._gateway, self._now = gateway, now

    def read(self) -> dict[str, object]:
        started = self._now()
        read = self._gateway.read_bank_balance()
        stage = "result"
        try:
            data = checked_result(read)
            stage = "identity"
            verify_identity(data, read.account)
            stage = "currency"
            if raw_field(data, "currency") != "TWD":
                raise ValueError("RECONCILE_FAILED")
            stage = "amount"
            balance = signed_integer(raw_field(data, "balance"), nonnegative=True)
            available = signed_integer(raw_field(data, "available_balance"), nonnegative=True)
            stage = "observation"
            return {**observation(read, started, self._now()), "currency": "TWD",
                    "balance": balance, "availableBalance": available}
        except ValueError as exc:
            reason = "STALE_QUERY" if str(exc) == "STALE_QUERY" else "RECONCILE_FAILED"
            raise BankBalanceError(reason, stage) from None
