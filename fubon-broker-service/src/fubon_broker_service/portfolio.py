from __future__ import annotations

import hashlib
import hmac
import re
from dataclasses import dataclass
from datetime import datetime
from typing import Callable
from zoneinfo import ZoneInfo

from .numeric import MAX_SHARES, NumericError, canonical_decimal, checked_share_add, exact_integer
from .sdk_gateway import AccountingPair, SdkCallError, SdkGateway, enum_text, raw_field


TW_ZONE = ZoneInfo("Asia/Taipei")
_STOCK_CODE = re.compile(r"^[0-9A-Z]{2,10}$")


class PortfolioError(ValueError):
    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


@dataclass(frozen=True)
class RawIdentity:
    source_date: str
    account: str
    branch_no: str
    stock_code: str
    order_type: str


class PortfolioService:
    def __init__(
        self,
        gateway: SdkGateway,
        now: Callable[[], datetime] | None = None,
        fingerprint: Callable[[str, str, str], str] | None = None,
    ) -> None:
        self._gateway = gateway
        self._now = now or (lambda: datetime.now(TW_ZONE))
        self._fingerprint = fingerprint or self._hmac_fingerprint

    def read(self) -> dict[str, object]:
        query_date = self._now().astimezone(TW_ZONE).date()
        try:
            pair = self._gateway.read_accounting_pair()
        except SdkCallError:
            raise
        if self._now().astimezone(TW_ZONE).date() != query_date:
            raise PortfolioError("QUERY_DATE_ROLLOVER")

        inventories = self._response_list(pair.inventories, "INVENTORIES_FAILED")
        unrealized = self._response_list(pair.unrealized, "UNREALIZED_FAILED")
        if (not inventories) != (not unrealized):
            raise PortfolioError("ONE_SIDED_EMPTY")

        # Raw source identity is validated for every row before HMAC or any normalized identity is built.
        inventory_identities = [
            self._validate_raw_identity(row, pair, query_date.isoformat()) for row in inventories
        ]
        unrealized_identities = [
            self._validate_raw_identity(row, pair, query_date.isoformat()) for row in unrealized
        ]

        fingerprint = self._fingerprint(
            pair.internal_token, pair.account.branch_no, pair.account.account_number
        )
        inventory_map = self._unique_map(inventories, inventory_identities, fingerprint, "INVENTORY_DUPLICATE")
        unrealized_map = self._unique_map(unrealized, unrealized_identities, fingerprint, "UNREALIZED_DUPLICATE")
        if set(inventory_map) != set(unrealized_map):
            raise PortfolioError("IDENTITY_SET_MISMATCH")

        positions: list[dict[str, object]] = []
        all_zero = bool(inventory_map)
        for identity in sorted(inventory_map):
            inventory_row = inventory_map[identity]
            unrealized_row = unrealized_map[identity]
            inv_identity = identity[-2:]
            if inv_identity[1] != "Stock":
                raise PortfolioError("UNSUPPORTED_POSITION_TYPE")
            buy_sell = enum_text(raw_field(unrealized_row, "buy_sell"))
            if buy_sell != "Buy":
                raise PortfolioError("UNSUPPORTED_POSITION_TYPE")

            try:
                board_qty = exact_integer(raw_field(inventory_row, "today_qty"), upper=MAX_SHARES)
                odd = raw_field(inventory_row, "odd")
                if odd is None:
                    raise NumericError("MISSING_ODD_QUANTITY")
                odd_qty = exact_integer(raw_field(odd, "today_qty"), upper=MAX_SHARES)
                inventory_qty = checked_share_add(board_qty, odd_qty)
                unrealized_qty = exact_integer(raw_field(unrealized_row, "today_qty"), upper=MAX_SHARES)
            except NumericError as exc:
                raise PortfolioError(str(exc)) from None
            if inventory_qty != unrealized_qty:
                raise PortfolioError("QUANTITY_MISMATCH")
            if inventory_qty == 0:
                continue
            all_zero = False
            try:
                cost_price = canonical_decimal(raw_field(unrealized_row, "cost_price"), positive=True)
            except NumericError as exc:
                raise PortfolioError(str(exc)) from None
            positions.append(
                {
                    "stockCode": inv_identity[0],
                    "shares": inventory_qty,
                    "costPrice": cost_price,
                }
            )

        empty_confirmed = (not inventories and not unrealized) or all_zero
        if not empty_confirmed and not positions:
            raise PortfolioError("EMPTY_NOT_PROVEN")
        batch_seed = f"{query_date.isoformat()}:{fingerprint}:{len(positions)}"
        batch_id = hashlib.sha256(batch_seed.encode("utf-8")).hexdigest()[:24]
        return {
            "batchId": batch_id,
            "queryDate": query_date.isoformat(),
            "accountFingerprint": fingerprint,
            "emptyConfirmed": empty_confirmed,
            "positions": positions,
            "reason": "EMPTY_CONFIRMED" if empty_confirmed else None,
        }

    @staticmethod
    def _response_list(response: object, failure_reason: str) -> list[object]:
        if raw_field(response, "is_success") is not True:
            raise PortfolioError(failure_reason)
        data = raw_field(response, "data")
        if not isinstance(data, list):
            raise PortfolioError("ACCOUNTING_DATA_NOT_LIST")
        return data

    @staticmethod
    def _validate_raw_identity(row: object, pair: AccountingPair, query_date: str) -> RawIdentity:
        raw_date = raw_field(row, "date")
        raw_account = raw_field(row, "account")
        raw_branch = raw_field(row, "branch_no")
        raw_code = raw_field(row, "stock_no")
        raw_order_type = enum_text(raw_field(row, "order_type"))
        if not all(isinstance(value, str) and value for value in (raw_date, raw_account, raw_branch, raw_code)):
            raise PortfolioError("MISSING_RAW_IDENTITY")
        if raw_date != query_date:
            raise PortfolioError("STALE_SOURCE_DATE")
        if raw_account != pair.account.account_number:
            raise PortfolioError("WRONG_SOURCE_ACCOUNT")
        if raw_branch != pair.account.branch_no:
            raise PortfolioError("WRONG_SOURCE_BRANCH")
        normalized_code = raw_code.strip().upper()
        if normalized_code != raw_code or not _STOCK_CODE.fullmatch(normalized_code):
            raise PortfolioError("INVALID_STOCK_CODE")
        if raw_order_type is None:
            raise PortfolioError("MISSING_ORDER_TYPE")
        return RawIdentity(raw_date, raw_account, raw_branch, normalized_code, raw_order_type)

    @staticmethod
    def _unique_map(
        rows: list[object],
        identities: list[RawIdentity],
        fingerprint: str,
        duplicate_reason: str,
    ) -> dict[tuple[str, str, str, str, str], object]:
        result: dict[tuple[str, str, str, str, str], object] = {}
        for row, identity in zip(rows, identities, strict=True):
            key = (
                identity.source_date,
                fingerprint,
                identity.branch_no,
                identity.stock_code,
                identity.order_type,
            )
            if key in result:
                raise PortfolioError(duplicate_reason)
            result[key] = row
        return result

    @staticmethod
    def _hmac_fingerprint(token: str, branch_no: str, account_number: str) -> str:
        return hmac.new(
            token.encode("utf-8"),
            f"{branch_no}:{account_number}".encode("utf-8"),
            hashlib.sha256,
        ).hexdigest()[:24]
