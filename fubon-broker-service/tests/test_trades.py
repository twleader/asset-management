from __future__ import annotations

import pytest

from fubon_broker_service.sdk_gateway import AccountingRead, SelectedAccount
from fubon_broker_service.trades import TradeReadError, TradeReadService

from helpers import TOKEN, account, filled_trade_row, response


class Gateway:
    def __init__(self, rows, *, success: bool = True):
        self.response = response(rows, success=success)
        self.selected = SelectedAccount(account(), "001", "00001234567")
        self.read_calls = 0
        self.selected_account_calls = 0

    def read_filled_trades(self, start_date, end_date):
        self.read_calls += 1
        self.last_range = (start_date, end_date)
        return AccountingRead(self.response, self.selected, TOKEN)

    def selected_account(self):
        self.selected_account_calls += 1
        return self.selected


def service(rows, *, success: bool = True, batch_id=None):
    gateway = Gateway(rows, success=success)
    svc = TradeReadService(gateway, batch_id=batch_id or (lambda: "fixed-batch-id"))
    return svc, gateway


def test_valid_row_is_normalized_and_fingerprint_hides_raw_account():
    svc, gateway = service([filled_trade_row()])
    result = svc.read("2026-08-21", "2026-08-21")
    assert result["batchId"] == "fixed-batch-id"
    assert result["startDate"] == "2026-08-21"
    assert result["endDate"] == "2026-08-21"
    assert result["emptyConfirmed"] is False
    assert result["trades"] == [
        {
            "stockCode": "2330",
            "side": "Buy",
            "filledQty": 1000,
            "filledPrice": "600.5",
            "filledAvgPrice": "600.5",
            "filledDate": "2026-08-21",
            "filledTime": "09:30:15.123",
            "filledNo": "F00000001",
        }
    ]
    assert "00001234567" not in result["accountFingerprint"]
    assert "001" not in result["accountFingerprint"]
    assert gateway.read_calls == 1
    assert gateway.selected_account_calls == 0


def test_empty_rows_is_a_legal_confirmed_empty_batch():
    svc, _gateway = service([])
    result = svc.read("2026-08-21", "2026-08-21")
    assert result["emptyConfirmed"] is True
    assert result["trades"] == []


def test_filled_history_business_failure_is_rejected():
    svc, _gateway = service([], success=False)
    with pytest.raises(TradeReadError, match="FILLED_HISTORY_FAILED"):
        svc.read("2026-08-21", "2026-08-21")


@pytest.mark.parametrize(
    ("mutation", "value"),
    [
        ("order_type", "Margin"),
        ("account", "WRONG"),
        ("branch_no", "999"),
        ("date", "2026-08-20"),
        ("buy_sell", "OddLot"),
        ("stock_no", "2330 "),
    ],
)
def test_raw_row_validation_fails_whole_batch(mutation, value):
    good = filled_trade_row()
    bad = filled_trade_row()
    bad[mutation] = value
    svc, _gateway = service([good, bad])
    with pytest.raises(TradeReadError, match="RECONCILE_FAILED"):
        svc.read("2026-08-21", "2026-08-21")


def test_date_outside_requested_range_fails_whole_batch():
    svc, _gateway = service([filled_trade_row(date="2026/08/22")])
    with pytest.raises(TradeReadError, match="RECONCILE_FAILED"):
        svc.read("2026-08-21", "2026-08-21")


def test_date_at_range_boundaries_is_accepted():
    svc, _gateway = service([filled_trade_row(date="2026/08/21"), filled_trade_row(date="2026/08/25")])
    result = svc.read("2026-08-21", "2026-08-25")
    assert len(result["trades"]) == 2


@pytest.mark.parametrize(
    ("field", "value", "reason"),
    [
        ("filled_price", "0", "RECONCILE_FAILED"),
        ("filled_price", "-1", "RECONCILE_FAILED"),
        ("filled_price", "1e10", "RECONCILE_FAILED"),
        ("filled_avg_price", None, "RECONCILE_FAILED"),
        ("filled_qty", 0, "RECONCILE_FAILED"),
        ("filled_qty", -1, "RECONCILE_FAILED"),
        ("filled_qty", 10_000_000_000, "RECONCILE_FAILED"),
        ("filled_no", "", "RECONCILE_FAILED"),
        ("filled_no", "X" * 51, "RECONCILE_FAILED"),
        ("filled_time", "", "RECONCILE_FAILED"),
        ("filled_time", None, "RECONCILE_FAILED"),
    ],
)
def test_numeric_and_string_boundaries_fail_whole_batch(field, value, reason):
    row = filled_trade_row()
    row[field] = value
    svc, _gateway = service([row])
    with pytest.raises(TradeReadError, match=reason):
        svc.read("2026-08-21", "2026-08-21")


@pytest.mark.parametrize("qty", [1, 9_999_999_999])
def test_filled_qty_boundaries_are_accepted(qty):
    svc, _gateway = service([filled_trade_row(filled_qty=qty)])
    result = svc.read("2026-08-21", "2026-08-21")
    assert result["trades"][0]["filledQty"] == qty


def test_filled_no_max_length_is_accepted():
    svc, _gateway = service([filled_trade_row(filled_no="F" * 50)])
    result = svc.read("2026-08-21", "2026-08-21")
    assert result["trades"][0]["filledNo"] == "F" * 50


def test_sell_side_is_accepted():
    svc, _gateway = service([filled_trade_row(buy_sell="Sell")])
    result = svc.read("2026-08-21", "2026-08-21")
    assert result["trades"][0]["side"] == "Sell"


def test_non_list_data_is_rejected():
    gateway = Gateway([])
    gateway.response = response(None, success=True)
    svc = TradeReadService(gateway)
    with pytest.raises(TradeReadError, match="FILLED_HISTORY_DATA_NOT_LIST"):
        svc.read("2026-08-21", "2026-08-21")
