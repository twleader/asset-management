from __future__ import annotations

import hashlib
import hmac
import re
import uuid
from typing import Callable

from .numeric import MAX_SHARES, NumericError, canonical_decimal, exact_integer
from .sdk_gateway import SdkGateway, SelectedAccount, enum_text, raw_field


_STOCK_CODE = re.compile(r"^[0-9A-Z]{2,10}$")
_VALID_SIDES = {"Buy", "Sell"}
_MAX_FILLED_NO_LENGTH = 50


class TradeReadError(ValueError):
    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


class TradeReadService:
    """Read-only reconciliation of sdk.stock.filled_history() rows.

    Mirrors PortfolioService's "validate raw identity before anything else"
    posture: every raw row is checked against the exact selected account
    before any normalized DTO or the account fingerprint is built, and a
    single invalid row fails the whole batch rather than being silently
    dropped -- a partial validation failure here means the adapter's
    assumptions about the raw schema may already be wrong, so nothing from
    that batch can be trusted.
    """

    def __init__(
        self,
        gateway: SdkGateway,
        fingerprint: Callable[[str, str, str], str] | None = None,
        batch_id: Callable[[], str] | None = None,
    ) -> None:
        self._gateway = gateway
        self._fingerprint = fingerprint or self._hmac_fingerprint
        self._batch_id = batch_id or (lambda: str(uuid.uuid4()))

    def read(self, start_date: str, end_date: str, internal_token: str) -> dict[str, object]:
        response = self._gateway.read_filled_trades(start_date, end_date)
        account = self._gateway.selected_account()

        if raw_field(response, "is_success") is not True:
            raise TradeReadError("FILLED_HISTORY_FAILED")
        rows = raw_field(response, "data")
        if not isinstance(rows, list):
            raise TradeReadError("FILLED_HISTORY_DATA_NOT_LIST")

        # Raw identity (and every other field) is validated for every row
        # before the fingerprint or any normalized trade is built.
        trades = [self._validate_and_normalize(row, account, start_date, end_date) for row in rows]

        fingerprint = self._fingerprint(internal_token, account.branch_no, account.account_number)
        return {
            "batchId": self._batch_id(),
            "startDate": start_date,
            "endDate": end_date,
            "accountFingerprint": fingerprint,
            "emptyConfirmed": not rows,
            "trades": trades,
        }

    @staticmethod
    def _validate_and_normalize(
        row: object, account: SelectedAccount, start_date: str, end_date: str
    ) -> dict[str, object]:
        # (a) order_type must be exactly "Stock".
        order_type = enum_text(raw_field(row, "order_type"))
        if order_type != "Stock":
            raise TradeReadError("RECONCILE_FAILED")

        # (b) stock code / side / account / branch must exist and be parseable.
        raw_code = raw_field(row, "stock_no")
        raw_side = enum_text(raw_field(row, "buy_sell"))
        raw_account = raw_field(row, "account")
        raw_branch = raw_field(row, "branch_no")
        raw_date = raw_field(row, "date")
        if not all(
            isinstance(value, str) and value for value in (raw_code, raw_account, raw_branch, raw_date)
        ):
            raise TradeReadError("RECONCILE_FAILED")
        if raw_side is None:
            raise TradeReadError("RECONCILE_FAILED")

        # (c) account / branch_no must match the exact selected account.
        if raw_account != account.account_number or raw_branch != account.branch_no:
            raise TradeReadError("RECONCILE_FAILED")

        # (d) date must fall within [start_date, end_date] inclusive.
        if not (start_date <= raw_date <= end_date):
            raise TradeReadError("RECONCILE_FAILED")

        # (e) side must be one of the official BSAction Buy/Sell values.
        if raw_side not in _VALID_SIDES:
            raise TradeReadError("RECONCILE_FAILED")

        normalized_code = raw_code.strip().upper()
        if normalized_code != raw_code or not _STOCK_CODE.fullmatch(normalized_code):
            raise TradeReadError("RECONCILE_FAILED")

        try:
            filled_price = canonical_decimal(raw_field(row, "filled_price"), positive=True)
            filled_avg_price = canonical_decimal(raw_field(row, "filled_avg_price"), positive=True)
            filled_qty = exact_integer(raw_field(row, "filled_qty"), upper=MAX_SHARES)
        except NumericError:
            raise TradeReadError("RECONCILE_FAILED") from None
        if filled_qty < 1:
            raise TradeReadError("RECONCILE_FAILED")

        raw_no = raw_field(row, "filled_no")
        if not isinstance(raw_no, str) or not raw_no or len(raw_no) > _MAX_FILLED_NO_LENGTH:
            raise TradeReadError("RECONCILE_FAILED")

        raw_time = raw_field(row, "filled_time")
        if not isinstance(raw_time, str) or not raw_time:
            raise TradeReadError("RECONCILE_FAILED")

        return {
            "stockCode": normalized_code,
            "side": raw_side,
            "filledQty": filled_qty,
            "filledPrice": filled_price,
            "filledAvgPrice": filled_avg_price,
            "filledDate": raw_date,
            "filledTime": raw_time,
            "filledNo": raw_no,
        }

    @staticmethod
    def _hmac_fingerprint(token: str, branch_no: str, account_number: str) -> str:
        return hmac.new(
            token.encode("utf-8"),
            f"{branch_no}:{account_number}".encode("utf-8"),
            hashlib.sha256,
        ).hexdigest()[:24]
