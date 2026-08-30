from __future__ import annotations

from dataclasses import replace
from datetime import UTC, date, datetime
from unittest.mock import Mock

import pytest

from fubon_broker_service.portfolio import PortfolioError, PortfolioService
from fubon_broker_service.sdk_gateway import AccountingPair, SelectedAccount

from helpers import TOKEN, account, inventory_row, response, unrealized_row


NOW = datetime(2026, 8, 21, 4, 30, tzinfo=UTC)
MISSING_DATE = object()


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


@pytest.mark.parametrize(
    ("now", "source_date", "query_date", "batch_id"),
    [
        (
            datetime(2026, 8, 20, 16, 30, tzinfo=UTC),
            "2026/08/21",
            "2026-08-21",
            "bce8bc9abfcfceb1c8a0d7ca",
        ),
        (
            datetime(2024, 2, 28, 16, 30, tzinfo=UTC),
            "2024/02/29",
            "2024-02-29",
            "7b20148866804341389f3ca9",
        ),
    ],
    ids=["taipei-day-differs-from-utc", "valid-leap-day"],
)
def test_official_source_date_preserves_iso_wire_and_batch_contract(now, source_date, query_date, batch_id):
    fingerprint = Mock(wraps=PortfolioService._hmac_fingerprint)
    subject = PortfolioService(
        Gateway([inventory_row(date=source_date)], [unrealized_row(date=source_date)]),
        now=lambda: now,
        fingerprint=fingerprint,
    )

    assert subject.read() == {
        "batchId": batch_id,
        "queryDate": query_date,
        "accountFingerprint": "6ccad7ff94bfbe8ed5fdef31",
        "emptyConfirmed": False,
        "positions": [{"stockCode": "2330", "shares": 3, "costPrice": "12.345"}],
        "reason": None,
    }
    fingerprint.assert_called_once_with(TOKEN, "001", "00001234567")


@pytest.mark.parametrize("side", ["inventory", "unrealized"])
@pytest.mark.parametrize(
    ("raw_date", "reason"),
    [
        pytest.param(MISSING_DATE, "MISSING_RAW_IDENTITY", id="missing"),
        pytest.param(None, "MISSING_RAW_IDENTITY", id="null"),
        pytest.param("", "MISSING_RAW_IDENTITY", id="empty"),
        pytest.param(20260821, "MISSING_RAW_IDENTITY", id="numeric"),
        pytest.param(date(2026, 8, 21), "MISSING_RAW_IDENTITY", id="date-object"),
        pytest.param("2026-08-21", "INVALID_SOURCE_DATE", id="raw-iso"),
        pytest.param("2026/8/21", "INVALID_SOURCE_DATE", id="unpadded-month"),
        pytest.param("2026/08/1", "INVALID_SOURCE_DATE", id="unpadded-day"),
        pytest.param(" 2026/08/21", "INVALID_SOURCE_DATE", id="leading-whitespace"),
        pytest.param("2026/08/21 ", "INVALID_SOURCE_DATE", id="trailing-whitespace"),
        pytest.param("2026/04/31", "INVALID_SOURCE_DATE", id="impossible-day"),
        pytest.param("2026/13/21", "INVALID_SOURCE_DATE", id="impossible-month"),
        pytest.param("2026/02/29", "INVALID_SOURCE_DATE", id="invalid-leap-day"),
        pytest.param("RAW_DATE_SENSITIVE_SENTINEL", "INVALID_SOURCE_DATE", id="sanitized"),
        pytest.param("2026/08/22", "STALE_SOURCE_DATE", id="future-day"),
        pytest.param("2024/02/29", "STALE_SOURCE_DATE", id="past-valid-leap-day"),
    ],
)
def test_source_date_failures_are_sanitized_before_fingerprint(side, raw_date, reason):
    inv = inventory_row()
    unr = unrealized_row()
    row = inv if side == "inventory" else unr
    if raw_date is MISSING_DATE:
        row.pop("date")
    else:
        row["date"] = raw_date
    fingerprint = Mock()

    with pytest.raises(PortfolioError) as raised:
        service([inv], [unr], fingerprint=fingerprint).read()

    assert raised.value.reason == reason
    assert str(raised.value) == reason
    assert raised.value.__cause__ is None
    fingerprint.assert_not_called()


@pytest.mark.parametrize("side", ["inventory", "unrealized"])
@pytest.mark.parametrize(
    ("raw_date", "reason"),
    [("2026-08-21", "INVALID_SOURCE_DATE"), ("2026/08/20", "STALE_SOURCE_DATE")],
)
def test_bad_source_date_in_later_row_rejects_whole_batch_before_fingerprint(side, raw_date, reason):
    inventories = [inventory_row(), inventory_row(code="2317")]
    unrealized = [unrealized_row(), unrealized_row(code="2317")]
    (inventories if side == "inventory" else unrealized)[1]["date"] = raw_date
    fingerprint = Mock()

    with pytest.raises(PortfolioError, match=reason):
        service(inventories, unrealized, fingerprint=fingerprint).read()

    fingerprint.assert_not_called()


@pytest.mark.parametrize("side", ["inventory", "unrealized"])
@pytest.mark.parametrize(
    ("field", "value", "reason"),
    [
        ("date", "2026/08/20", "STALE_SOURCE_DATE"),
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


@pytest.mark.parametrize("empty", [True, False], ids=["empty-account", "with-positions"])
def test_date_rollover_rejects_whole_pair(empty):
    values = iter(
        [
            datetime(2026, 8, 21, 15, 59, tzinfo=UTC),
            datetime(2026, 8, 21, 16, 0, tzinfo=UTC),
        ]
    )
    fingerprint = Mock()
    gateway = Gateway([] if empty else [inventory_row()], [] if empty else [unrealized_row()])
    subject = PortfolioService(gateway, now=lambda: next(values), fingerprint=fingerprint)
    with pytest.raises(PortfolioError, match="QUERY_DATE_ROLLOVER"):
        subject.read()
    fingerprint.assert_not_called()


def test_null_accounting_data_is_not_empty_proof():
    gateway = Gateway([], [])
    gateway.pair = replace(gateway.pair, inventories=response(None))
    with pytest.raises(PortfolioError, match="ACCOUNTING_DATA_NOT_LIST"):
        PortfolioService(gateway, now=lambda: NOW).read()
