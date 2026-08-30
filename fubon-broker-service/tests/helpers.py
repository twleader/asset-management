from __future__ import annotations

from datetime import UTC, datetime
from pathlib import Path
from types import SimpleNamespace

from fubon_broker_service.config import ConfigLoader


TOKEN = "TEST_INTERNAL_TOKEN_SENTINEL"


def ready_config(
    tmp_path: Path,
    *,
    branch: str | None = None,
    account: str | None = None,
    taiex_index_stream_enabled: str = "false",
    taiex_index_symbol: str = "",
    stock_push_enabled: str = "false",
) -> ConfigLoader:
    sdk = tmp_path / "sdk"
    shared = tmp_path / "shared"
    sdk.mkdir(exist_ok=True)
    shared.mkdir(exist_ok=True)
    (sdk / "personal-id").write_text("TEST_PERSONAL_ID_SENTINEL", encoding="utf-8")
    (sdk / "api-key").write_text("TEST_API_KEY_SENTINEL", encoding="utf-8")
    (sdk / "certificate.pfx").write_bytes(b"not-a-real-certificate")
    (sdk / "certificate-password").write_text("TEST_CERT_PASSWORD_SENTINEL", encoding="utf-8")
    (shared / "internal-service-token").write_text(TOKEN, encoding="utf-8")
    if branch is not None:
        (sdk / "account-branch-no").write_text(branch, encoding="utf-8")
    if account is not None:
        (sdk / "account-number").write_text(account, encoding="utf-8")
    return ConfigLoader(
        tmp_path,
        lambda: "true",
        lambda: taiex_index_stream_enabled,
        lambda: taiex_index_symbol,
        lambda: stock_push_enabled,
    )


def response(data, success: bool = True, code: object | None = None):
    return SimpleNamespace(is_success=success, data=data, code=code)


def account(branch: str = "001", number: str = "00001234567"):
    return SimpleNamespace(account_type="stock", branch_no=branch, account=number)


def inventory_row(
    *,
    date: str = "2026/08/21",
    account_number: str = "00001234567",
    branch: str = "001",
    code: str = "2330",
    order_type: str = "Stock",
    board: object = 2,
    odd: object = 1,
):
    return {
        "date": date,
        "account": account_number,
        "branch_no": branch,
        "stock_no": code,
        "order_type": order_type,
        "today_qty": board,
        "odd": {"today_qty": odd},
    }

def unrealized_row(
    *,
    date: str = "2026/08/21",
    account_number: str = "00001234567",
    branch: str = "001",
    code: str = "2330",
    order_type: str = "Stock",
    buy_sell: str = "Buy",
    qty: object = 3,
    cost: object = "12.345",
):
    return {
        "date": date,
        "account": account_number,
        "branch_no": branch,
        "stock_no": code,
        "order_type": order_type,
        "buy_sell": buy_sell,
        "today_qty": qty,
        "cost_price": cost,
    }


def filled_trade_row(
    *,
    date: str = "2026/08/21",
    account_number: str = "00001234567",
    branch: str = "001",
    code: str = "2330",
    order_type: str = "Stock",
    buy_sell: str = "Buy",
    filled_qty: object = 1000,
    filled_price: object = "600.5",
    filled_avg_price: object = "600.5",
    filled_time: str = "09:30:15.123",
    filled_no: str = "F00000001",
):
    return {
        "date": date,
        "account": account_number,
        "branch_no": branch,
        "stock_no": code,
        "order_type": order_type,
        "buy_sell": buy_sell,
        "filled_qty": filled_qty,
        "filled_price": filled_price,
        "filled_avg_price": filled_avg_price,
        "filled_time": filled_time,
        "filled_no": filled_no,
    }


def fixed_now() -> datetime:
    return datetime(2026, 8, 21, 5, 0, 0, tzinfo=UTC)


def quote_raw(
    code: str = "2330",
    *,
    timestamp: int | None = None,
    actual: object = "100.1",
    previous: object = "99.5",
    open_price: object = "100.0",
    high_price: object = "101.0",
    low_price: object = "99.0",
    volume: object = 54_538,
):
    ts = timestamp if timestamp is not None else int(fixed_now().timestamp() * 1_000_000)
    return {
        "symbol": code,
        "name": "台積電",
        "exchange": "TWSE",
        "market": "TSE",
        "isTrial": False,
        "lastTrade": {"price": actual, "time": ts},
        "previousClose": previous,
        "openPrice": open_price,
        "highPrice": high_price,
        "lowPrice": low_price,
        "bids": [{"price": "100.0"}],
        "asks": [{"price": "100.2"}],
        "total": {"tradeVolume": volume},
        "lastUpdated": ts,
    }
