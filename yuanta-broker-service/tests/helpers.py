from __future__ import annotations

from pathlib import Path

from yuanta_broker_service.config import ConfigLoader
from yuanta_broker_service.sdk_gateway import LoginResult


TOKEN = "TEST_INTERNAL_TOKEN_SENTINEL"

DEFAULT_ACCOUNTS = [
    {"accountType": "stock", "branchNo": "001", "account": "0001234567"},
    {"accountType": "futures", "branchNo": "002", "account": "0007654321"},
]


def ready_config(
    tmp_path: Path,
    *,
    stock_selector: str | None = None,
    futures_selector: str | None = None,
) -> ConfigLoader:
    sdk = tmp_path / "sdk"
    sdk.mkdir()
    (sdk / "dll").mkdir()
    (sdk / "dll" / "YuantaSparkAPI.dll").write_bytes(b"not-a-real-dll")
    (sdk / "account").write_text("TEST_ACCOUNT_SENTINEL", encoding="utf-8")
    (sdk / "password").write_text("TEST_PASSWORD_SENTINEL", encoding="utf-8")
    (sdk / "certificate.pfx").write_bytes(b"not-a-real-certificate")
    (sdk / "certificate-password").write_text("TEST_CERT_PASSWORD_SENTINEL", encoding="utf-8")
    (tmp_path / "internal-service-token").write_text(TOKEN, encoding="utf-8")
    if stock_selector is not None:
        (sdk / "stock-account-selector").write_text(stock_selector, encoding="utf-8")
    if futures_selector is not None:
        (sdk / "futures-account-selector").write_text(futures_selector, encoding="utf-8")
    return ConfigLoader(tmp_path, lambda: "true")


class FakeYuantaSparkGateway:
    """Test double for sdk_gateway.RawSparkGateway. Never imports pythonnet/clr.

    Every raw call is driven by plain attributes the test sets directly, so
    each test stays a short, readable arrange/act/assert block instead of a
    mocking framework. `responses` holds the pre-normalization raw payload
    for each of the eleven query methods, keyed by method name.
    """

    def __init__(self) -> None:
        self.component_load_error: Exception | None = None
        self.login_error: Exception | None = None
        self.login_result: LoginResult | None = None
        self.accounts: list[object] = list(DEFAULT_ACCOUNTS)
        self.market_data_error: Exception | None = None
        self.responses: dict[str, object] = {}
        self.component_load_calls = 0
        self.login_calls = 0
        self.connect_market_data_calls = 0
        self.logout_calls = 0

    def ensure_component_loaded(self) -> None:
        self.component_load_calls += 1
        if self.component_load_error is not None:
            raise self.component_load_error

    def login(self, account, password, certificate_path, certificate_password) -> LoginResult:
        self.login_calls += 1
        if self.login_error is not None:
            raise self.login_error
        if self.login_result is not None:
            return self.login_result
        return LoginResult(is_success=True, accounts=self.accounts)

    def logout(self) -> None:
        self.logout_calls += 1

    def connect_market_data(self) -> None:
        self.connect_market_data_calls += 1
        if self.market_data_error is not None:
            raise self.market_data_error

    def get_stock_inventory(self, account):
        return self.responses.get("stock_inventory", [])

    def get_futures_inventory(self, account):
        return self.responses.get("futures_inventory", [])

    def get_unrealized_pnl(self, account):
        return self.responses.get("unrealized_pnl", [])

    def get_realized_pnl(self, account, start_date, end_date):
        return self.responses.get("realized_pnl", [])

    def get_settlement(self, account):
        return self.responses.get("settlement", [])

    def get_futures_margin(self, account):
        return self.responses.get("futures_margin", {})

    def get_quote(self, code):
        return self.responses.get(f"quote:{code}", self.responses.get("quote", {}))

    def get_five_best(self, code):
        return self.responses.get(f"five_best:{code}", self.responses.get("five_best", {"bids": [], "asks": []}))

    def get_intraday_ticks(self, code):
        return self.responses.get("intraday_ticks", [])

    def get_kline(self, code, interval, count, start_date, end_date):
        return self.responses.get("kline", [])

    def get_instrument_info(self, code):
        return self.responses.get("instrument_info", {})

    def get_order_execution_report(self, start_date, end_date):
        return self.responses.get("order_execution_report", [])


def stock_inventory_row(
    *, code: str = "2330", shares: object = 1000, cost_price: object = "580.5", market_value: object = "600000"
):
    return {"stockCode": code, "shares": shares, "costPrice": cost_price, "marketValue": market_value}


def quote_row(
    *,
    name: str = "台積電",
    market: str = "TSE",
    last_price: object = "580.0",
    previous_close: object = "578.0",
    open_price: object = "579.0",
    high_price: object = "582.0",
    low_price: object = "577.0",
    volume: object = 12_345,
):
    return {
        "stockName": name,
        "market": market,
        "lastPrice": last_price,
        "previousClose": previous_close,
        "openPrice": open_price,
        "highPrice": high_price,
        "lowPrice": low_price,
        "volume": volume,
    }
