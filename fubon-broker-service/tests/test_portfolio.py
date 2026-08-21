from __future__ import annotations

from dataclasses import replace
from datetime import UTC, datetime

import pytest

from fubon_broker_service.portfolio import PortfolioError, PortfolioService
from fubon_broker_service.sdk_gateway import AccountingPair, SelectedAccount

from helpers import TOKEN, account, inventory_row, response, unrealized_row


NOW = datetime(2026, 8, 21, 4, 30, tzinfo=UTC)


class Gateway:
    def __init__(self, inventories, unrealized):
        self.pair = AccountingPair(
            response(inventories),
            response(unrealized),
            SelectedAccount(account(), "001", "00001234567"),
            TOKEN,
        )

    def read_accounting_pair(self):
        return self.pair


def service(inventories, unrealized, fingerprint=None):
    return PortfolioService(Gateway(inventories, unrealized), now=lambda: NOW, fingerprint=fingerprint)


def test_reconciles_inventory_and_unrealized_with_canonical_cost():
    result = service([inventory_row()], [unrealized_row(cost=0.1)]).read()
    assert result["queryDate"] == "2026-08-21"
    assert result["emptyConfirmed"] is False
    assert result["positions"] == [{"stockCode": "2330", "shares": 3, "costPrice": "0.1"}]
    assert "00001234567" not in str(result)
    assert "001" not in str(result)


@pytest.mark.parametrize("side", ["inventory", "unrealized"])
@pytest.mark.parametrize(
    ("field", "value", "reason"),
    [
        ("date", "2026-08-20", "STALE_SOURCE_DATE"),
        ("account", "WRONG", "WRONG_SOURCE_ACCOUNT"),
        ("branch_no", "999", "WRONG_SOURCE_BRANCH"),
    ],
)
def test_raw_identity_is_rejected_before_fingerprint(side, field, value, reason):
    inv = inventory_row()
    unr = unrealized_row()
    (inv if side == "inventory" else unr)[field] = value
    calls = 0

    def fingerprint(*_args):
        nonlocal calls
        calls += 1
        return "should-not-run"

    with pytest.raises(PortfolioError, match=reason):
        service([inv], [unr], fingerprint=fingerprint).read()
    assert calls == 0


def test_both_concrete_empty_lists_are_confirmed():
    result = service([], []).read()
    assert result["emptyConfirmed"] is True
    assert result["positions"] == []


def test_all_matched_zero_rows_are_confirmed():
    result = service(
        [inventory_row(board=0, odd=0)],
        [unrealized_row(qty=0, cost=None)],
    ).read()
    assert result["emptyConfirmed"] is True
    assert result["positions"] == []


def test_one_sided_empty_is_not_an_empty_account_proof():
    with pytest.raises(PortfolioError, match="ONE_SIDED_EMPTY"):
        service([], [unrealized_row()]).read()


@pytest.mark.parametrize(
    ("inventory", "unrealized", "reason"),
    [
        (inventory_row(order_type="Margin"), unrealized_row(order_type="Margin"), "UNSUPPORTED_POSITION_TYPE"),
        (inventory_row(), unrealized_row(buy_sell="Sell"), "UNSUPPORTED_POSITION_TYPE"),
        (inventory_row(board=2, odd=1), unrealized_row(qty=4), "QUANTITY_MISMATCH"),
        (inventory_row(board=10_000_000_000, odd=0), unrealized_row(qty=10_000_000_000), "INTEGER_RANGE_EXCEEDED"),
        (inventory_row(), unrealized_row(cost="10000000000.0000000000"), "DECIMAL_PRECISION_EXCEEDED"),
        (inventory_row(), unrealized_row(cost="0.00000000001"), "DECIMAL_SCALE_EXCEEDED"),
    ],
)
def test_reconciliation_fail_closed_matrix(inventory, unrealized, reason):
    with pytest.raises(PortfolioError, match=reason):
        service([inventory], [unrealized]).read()


@pytest.mark.parametrize("shares", [1, 9_999_999_999])
def test_persistable_share_boundaries_are_accepted(shares):
    result = service(
        [inventory_row(board=shares, odd=0)],
        [unrealized_row(qty=shares, cost="1")],
    ).read()
    assert result["positions"][0]["shares"] == shares


def test_date_rollover_rejects_whole_pair():
    values = iter(
        [
            datetime(2026, 8, 21, 15, 59, tzinfo=UTC),
            datetime(2026, 8, 21, 16, 0, tzinfo=UTC),
        ]
    )
    subject = PortfolioService(Gateway([], []), now=lambda: next(values))
    with pytest.raises(PortfolioError, match="QUERY_DATE_ROLLOVER"):
        subject.read()


def test_null_accounting_data_is_not_empty_proof():
    gateway = Gateway([], [])
    gateway.pair = replace(gateway.pair, inventories=response(None))
    with pytest.raises(PortfolioError, match="ACCOUNTING_DATA_NOT_LIST"):
        PortfolioService(gateway, now=lambda: NOW).read()
