"""Hand-written fake provider data; never imported from a real account."""
from datetime import UTC, datetime
from types import SimpleNamespace

from fubon_broker_service.sdk_gateway import AccountingRead, SelectedAccount
from fubon_broker_service.settlement import AMOUNT_FIELDS
from helpers import TOKEN, account, response


NOW = datetime(2026, 8, 28, 5, 40, tzinfo=UTC)
SELECTED = SelectedAccount(account(), "001", "00001234567", True)


def bank_row(**changes):
    return {"branch_no": SELECTED.branch_no, "account": SELECTED.account_number, "currency": "TWD",
            "balance": 10000, "available_balance": "0", **changes}


def settlement_row(**changes):
    return {"date": "2026/08/27", "settlement_date": "2026/08/31", "currency": "TWD",
            **{name: 0 for name in AMOUNT_FIELDS}, "buy_value": 1000, "buy_fee": 2,
            "buy_settlement": -1002, "total_bs_value": 1000, "total_fee": 2,
            "total_settlement_amount": -1002, **changes}


def realized_row(**changes):
    return {"date": "2026/08/27", "branch_no": SELECTED.branch_no, "account": SELECTED.account_number,
            "stock_no": "2330", "buy_sell": "Sell", "order_type": "Stock", "filled_qty": 1000,
            "filled_price": "123.5", "realized_profit": 0, "realized_loss": 20, **changes}


class AccountingGateway:
    def __init__(self, data, *, selected=SELECTED, token=TOKEN, envelope=True, success=True):
        self.read = AccountingRead(response(data, success=success) if envelope else data, selected, token)
        self.calls = 0

    def _read(self):
        self.calls += 1
        return self.read

    def read_bank_balance(self):
        return self._read()

    def read_settlement(self, range_param):
        assert range_param == "3d"
        return self._read()

    def read_realized_gains(self):
        return self._read()

    def selected_account(self):
        raise AssertionError("mutable selected_account must not be re-read after provider call")


def settlement_data(rows, **identity):
    return {"account": {"branch_no": SELECTED.branch_no, "account": SELECTED.account_number, **identity},
            "details": rows}
