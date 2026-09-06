from __future__ import annotations

from collections.abc import Callable
from datetime import date, datetime

from .accounting_normalization import (
    account_binding_explicit,
    checked_result,
    observation,
    verify_identity,
    verify_optional_identity,
)
from .normalization import TAIPEI, instant, require_fields, signed_integer, strict_vendor_date, utc_now
from .sdk_gateway import SdkGateway, raw_field


AMOUNT_FIELDS = {
    "buy_value": "buyValue", "buy_fee": "buyFee", "buy_settlement": "buySettlement",
    "buy_tax": "buyTax", "sell_value": "sellValue", "sell_fee": "sellFee",
    "sell_settlement": "sellSettlement", "sell_tax": "sellTax",
    "total_bs_value": "totalBsValue", "total_fee": "totalFee", "total_tax": "totalTax",
    "total_settlement_amount": "totalSettlementAmount",
}


class SettlementError(ValueError):
    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


class SettlementService:
    """Parse source observations without claiming the undocumented 3d range is complete."""

    def __init__(self, gateway: SdkGateway, *, now: Callable[[], datetime] = utc_now) -> None:
        self._gateway, self._now = gateway, now

    def read(self) -> dict[str, object]:
        started = self._now()
        read = self._gateway.read_settlement("3d")
        try:
            data = checked_result(read)
            verify_identity(raw_field(data, "account"), read.account)
            rows = raw_field(data, "details")
            if not isinstance(rows, list):
                raise ValueError("RECONCILE_FAILED")
            query_date = instant(started).astimezone(TAIPEI).date()
            normalized = []
            for row in rows:
                verify_optional_identity(row, read.account)
                normalized.append(self._normalize(row, query_date))
            seen: set[tuple[str, str | None]] = set()
            settlement_days: set[str] = set()
            for row in normalized:
                key = (row["sourceQueryDate"], row["settlementDate"])
                day = row["settlementDate"]
                if key in seen or day is not None and day in settlement_days:
                    raise ValueError("AMBIGUOUS_SETTLEMENT")
                seen.add(key)
                if day is not None:
                    settlement_days.add(day)
            return {
                **observation(read, started, self._now()),
                "accountBindingExplicit": account_binding_explicit(read),
                "coverageStatus": "SDK_RANGE_3D_RETURNED_ROWS",
                "reason": None,
                "details": normalized,
            }
        except ValueError as exc:
            reason = str(exc) if str(exc) in {"STALE_QUERY", "AMBIGUOUS_SETTLEMENT"} else "RECONCILE_FAILED"
            raise SettlementError(reason) from None

    @staticmethod
    def _normalize(row: object, query_date: date) -> dict[str, object]:
        required = ("date", "settlement_date", "currency", *AMOUNT_FIELDS)
        require_fields(row, required)
        source_date = strict_vendor_date(raw_field(row, "date"))
        if source_date > query_date:
            raise ValueError("RECONCILE_FAILED")
        values = {name: raw_field(row, name) for name in required if name != "date"}
        if all(value is None for value in values.values()):
            return {"status": "NO_DATA_OBSERVED", "sourceQueryDate": source_date.isoformat(),
                    "settlementDate": None, "currency": None, **{wire: None for wire in AMOUNT_FIELDS.values()}}
        if any(value is None for value in values.values()) or values["currency"] != "TWD":
            raise ValueError("RECONCILE_FAILED")
        settlement_date = strict_vendor_date(values["settlement_date"])
        if settlement_date < source_date:
            raise ValueError("RECONCILE_FAILED")
        amounts = {wire: signed_integer(values[name]) for name, wire in AMOUNT_FIELDS.items()}
        buy, sell = int(amounts["buySettlement"]), int(amounts["sellSettlement"])
        if buy > 0 or sell < 0 or buy + sell != int(amounts["totalSettlementAmount"]):
            raise ValueError("RECONCILE_FAILED")
        if settlement_date <= query_date and (buy != 0 or sell != 0):
            raise ValueError("AMBIGUOUS_SETTLEMENT")
        return {"status": "AVAILABLE", "sourceQueryDate": source_date.isoformat(),
                "settlementDate": settlement_date.isoformat(), "currency": "TWD", **amounts}
