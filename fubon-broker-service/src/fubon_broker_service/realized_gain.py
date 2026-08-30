from __future__ import annotations

from collections.abc import Callable
from datetime import date, datetime

from .accounting_normalization import checked_result, observation, verify_identity
from .normalization import TAIPEI, instant, signed_integer, stock_code, strict_vendor_date, utc_now
from .numeric import MAX_SHARES, canonical_decimal, exact_integer
from .sdk_gateway import SdkGateway, SelectedAccount, enum_text, raw_field


class RealizedGainError(ValueError):
    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


class RealizedGainService:
    """Verified fields only: no invented fill identity, net proceeds or acquisition cost."""

    def __init__(self, gateway: SdkGateway, *, now: Callable[[], datetime] = utc_now) -> None:
        self._gateway, self._now = gateway, now

    def read(self) -> dict[str, object]:
        started = self._now()
        read = self._gateway.read_realized_gains()
        try:
            rows = checked_result(read)
            if not isinstance(rows, list):
                raise ValueError("RECONCILE_FAILED")
            query_date = instant(started).astimezone(TAIPEI).date()
            normalized = [self._normalize(row, read.account, query_date) for row in rows]
            return {**observation(read, started, self._now()), "rows": normalized}
        except ValueError as exc:
            reason = str(exc) if str(exc) in {"STALE_QUERY", "ACCOUNTING_SEMANTICS_UNVERIFIED"} else "RECONCILE_FAILED"
            raise RealizedGainError(reason) from None

    @staticmethod
    def _normalize(row: object, account: SelectedAccount, query_date: date) -> dict[str, object]:
        verify_identity(row, account)
        code = stock_code(raw_field(row, "stock_no"))
        if enum_text(raw_field(row, "buy_sell")) != "Sell" or enum_text(raw_field(row, "order_type")) != "Stock":
            raise ValueError("RECONCILE_FAILED")
        qty = exact_integer(raw_field(row, "filled_qty"), upper=MAX_SHARES)
        if qty < 1:
            raise ValueError("RECONCILE_FAILED")
        price = canonical_decimal(raw_field(row, "filled_price"), positive=True)
        profit = signed_integer(raw_field(row, "realized_profit"), nonnegative=True)
        loss = signed_integer(raw_field(row, "realized_loss"), nonnegative=True)
        if int(profit) and int(loss):
            raise ValueError("ACCOUNTING_SEMANTICS_UNVERIFIED")
        source_date = strict_vendor_date(raw_field(row, "date"))
        if source_date > query_date:
            raise ValueError("RECONCILE_FAILED")
        return {"stockNo": code, "buySell": "Sell", "orderType": "Stock", "filledQty": qty,
                "filledPrice": price, "realizedProfit": profit, "realizedLoss": loss,
                "sourceDate": source_date.isoformat()}
