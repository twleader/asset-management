from __future__ import annotations

import pytest

from yuanta_broker_service.sdk_gateway import (
    FieldValidationError,
    INVENTORY_STOCK_SPEC,
    SdkCallError,
    YuantaBrokerGateway,
    canonical_decimal,
    exact_integer,
    normalize_code,
    normalize_codes,
    normalize_row,
)

from helpers import DEFAULT_ACCOUNTS, FakeYuantaSparkGateway, quote_row, ready_config, stock_inventory_row


# --- numeric boundaries (Task 364.10) ---------------------------------------


@pytest.mark.parametrize(
    "value",
    ["0.01", "123", "123.456", "9" * 20, "0"],
)
def test_canonical_decimal_accepts_legal_values(value):
    assert canonical_decimal(value, sign="non_negative") == value


def test_canonical_decimal_rejects_scientific_notation():
    with pytest.raises(FieldValidationError, match="NON_CANONICAL_DECIMAL"):
        canonical_decimal("1e10", sign="non_negative")


def test_canonical_decimal_rejects_nan_and_infinity():
    with pytest.raises(FieldValidationError, match="NON_FINITE_DECIMAL"):
        canonical_decimal("NaN", sign="non_negative")
    with pytest.raises(FieldValidationError, match="NON_FINITE_DECIMAL"):
        canonical_decimal("Infinity", sign="non_negative")


def test_canonical_decimal_rejects_float_type_that_renders_scientific():
    with pytest.raises(FieldValidationError, match="NON_CANONICAL_DECIMAL"):
        canonical_decimal(1e30, sign="non_negative")


def test_canonical_decimal_sign_modes():
    assert canonical_decimal("5", sign="positive") == "5"
    with pytest.raises(FieldValidationError, match="NON_POSITIVE_DECIMAL"):
        canonical_decimal("0", sign="positive")
    assert canonical_decimal("0", sign="non_negative") == "0"
    with pytest.raises(FieldValidationError, match="NEGATIVE_DECIMAL"):
        canonical_decimal("-1", sign="non_negative")
    assert canonical_decimal("-42.5", sign="any") == "-42.5"


def test_canonical_decimal_rejects_precision_and_scale_overflow():
    with pytest.raises(FieldValidationError, match="DECIMAL_PRECISION_EXCEEDED"):
        canonical_decimal("1" * 21, sign="non_negative")
    with pytest.raises(FieldValidationError, match="DECIMAL_SCALE_EXCEEDED"):
        canonical_decimal("0." + "1" * 11, sign="non_negative")


def test_exact_integer_rejects_negative_and_overflow_and_float():
    with pytest.raises(FieldValidationError):
        exact_integer(-1, upper=100)
    with pytest.raises(FieldValidationError):
        exact_integer(101, upper=100)
    with pytest.raises(FieldValidationError):
        exact_integer(1.5, upper=100)
    with pytest.raises(FieldValidationError):
        exact_integer(True, upper=100)
    assert exact_integer(9_223_372_036_854_775_807, upper=9_223_372_036_854_775_807) == 9_223_372_036_854_775_807


def test_normalize_row_marks_that_entry_error_without_dropping_identity():
    raw = stock_inventory_row(cost_price="NaN")
    result = normalize_row(raw, INVENTORY_STOCK_SPEC)
    assert result["status"] == "ERROR"
    assert result["reason"] == "NON_FINITE_DECIMAL"
    # Identity survives so the caller can still tell which row failed; the
    # bad field (and anything validated after it) is never faked as 0/absent-ok.
    assert result["stockCode"] == "2330"
    assert "costPrice" not in result
    assert "marketValue" not in result


def test_normalize_row_success_shape():
    raw = stock_inventory_row()
    result = normalize_row(raw, INVENTORY_STOCK_SPEC)
    assert result == {
        "stockCode": "2330",
        "shares": 1000,
        "costPrice": "580.5",
        "marketValue": "600000",
        "status": "SUCCESS",
        "reason": None,
    }


def test_normalize_row_invalid_shape_is_error():
    result = normalize_row("not-a-dict", INVENTORY_STOCK_SPEC)
    assert result == {"status": "ERROR", "reason": "INVALID_ROW_SHAPE"}


def test_normalize_codes_dedupes_and_bounds():
    assert normalize_codes(["2330", "2330", "2317"]) == ["2330", "2317"]
    with pytest.raises(FieldValidationError, match="INVALID_CODE_COUNT"):
        normalize_codes([])
    with pytest.raises(FieldValidationError, match="INVALID_CODE_COUNT"):
        normalize_codes([str(i) for i in range(101)])


def test_normalize_code_uppercases_and_rejects_bad_chars():
    assert normalize_code(" 2330 ") == "2330"
    with pytest.raises(FieldValidationError):
        normalize_code("bad code!")


# --- account selection (Task 364.6) -----------------------------------------


def _config_with(tmp_path, **kwargs):
    return ready_config(tmp_path, **kwargs).load()


def test_selector_exact_match_succeeds(tmp_path):
    config = _config_with(tmp_path, stock_selector="001:0001234567")
    gateway = YuantaBrokerGateway(raw_factory=lambda cfg: FakeYuantaSparkGateway())
    account = gateway.ensure_stock_account(config)
    assert account.branch_no == "001"
    assert account.account_number == "0001234567"


def test_selector_no_match_is_misconfigured(tmp_path):
    config = _config_with(tmp_path, stock_selector="999:9999999999")
    gateway = YuantaBrokerGateway(raw_factory=lambda cfg: FakeYuantaSparkGateway())
    with pytest.raises(SdkCallError) as exc_info:
        gateway.ensure_stock_account(config)
    assert exc_info.value.reason == "STOCK_ACCOUNT_SELECTOR_NOT_UNIQUE"
    assert exc_info.value.misconfigured is True
    assert gateway.runtime_misconfigured_for(config) is True


def test_no_selector_and_exactly_one_candidate_succeeds(tmp_path):
    config = _config_with(tmp_path)
    fake = FakeYuantaSparkGateway()
    gateway = YuantaBrokerGateway(raw_factory=lambda cfg: fake)
    account = gateway.ensure_futures_account(config)
    assert account.branch_no == "002"


def test_no_selector_and_zero_candidates_is_misconfigured(tmp_path):
    config = _config_with(tmp_path)
    fake = FakeYuantaSparkGateway()
    fake.accounts = [acc for acc in DEFAULT_ACCOUNTS if acc["accountType"] != "futures"]
    gateway = YuantaBrokerGateway(raw_factory=lambda cfg: fake)
    with pytest.raises(SdkCallError) as exc_info:
        gateway.ensure_futures_account(config)
    assert exc_info.value.reason == "FUTURES_ACCOUNT_NOT_UNIQUE"
    assert gateway.runtime_misconfigured_for(config) is True


def test_no_selector_and_multiple_candidates_is_misconfigured(tmp_path):
    config = _config_with(tmp_path)
    fake = FakeYuantaSparkGateway()
    fake.accounts = [
        {"accountType": "stock", "branchNo": "001", "account": "1111111111"},
        {"accountType": "stock", "branchNo": "002", "account": "2222222222"},
    ]
    gateway = YuantaBrokerGateway(raw_factory=lambda cfg: fake)
    with pytest.raises(SdkCallError) as exc_info:
        gateway.ensure_stock_account(config)
    assert exc_info.value.reason == "STOCK_ACCOUNT_NOT_UNIQUE"


# --- lazy capability caching (Task 364.4/364.6/364.11) ----------------------


def test_component_load_failure_marks_misconfigured_but_login_failure_does_not(tmp_path):
    config = _config_with(tmp_path)
    fake = FakeYuantaSparkGateway()
    fake.component_load_error = RuntimeError("boom")
    gateway = YuantaBrokerGateway(raw_factory=lambda cfg: fake)
    with pytest.raises(SdkCallError) as exc_info:
        gateway.ensure_component_loaded(config)
    assert exc_info.value.misconfigured is True
    assert gateway.runtime_misconfigured_for(config) is True


def test_login_failure_does_not_downgrade_global_state_and_retry_succeeds(tmp_path):
    config = _config_with(tmp_path)
    fake = FakeYuantaSparkGateway()
    fake.login_error = RuntimeError("wrong password")
    gateway = YuantaBrokerGateway(raw_factory=lambda cfg: fake)
    with pytest.raises(SdkCallError) as exc_info:
        gateway.ensure_logged_in(config)
    assert exc_info.value.reason == "LOGIN_FAILED"
    assert exc_info.value.misconfigured is False
    assert gateway.runtime_misconfigured_for(config) is False

    fake.login_error = None
    gateway.ensure_logged_in(config)  # succeeds without recreating the gateway or restarting the service


def test_market_data_failure_does_not_downgrade_global_state_and_retry_succeeds(tmp_path):
    config = _config_with(tmp_path)
    fake = FakeYuantaSparkGateway()
    fake.market_data_error = RuntimeError("no feed")
    gateway = YuantaBrokerGateway(raw_factory=lambda cfg: fake)
    with pytest.raises(SdkCallError) as exc_info:
        gateway.ensure_market_data_connected(config)
    assert exc_info.value.reason == "MARKET_DATA_UNAVAILABLE"
    assert exc_info.value.misconfigured is False
    assert gateway.runtime_misconfigured_for(config) is False

    fake.market_data_error = None
    gateway.ensure_market_data_connected(config)


def test_login_and_market_data_are_independent_capabilities(tmp_path):
    config = _config_with(tmp_path)
    fake = FakeYuantaSparkGateway()
    fake.market_data_error = RuntimeError("no feed")
    gateway = YuantaBrokerGateway(raw_factory=lambda cfg: fake)

    # Login succeeds even though market data is unavailable.
    account = gateway.ensure_stock_account(config)
    assert account.branch_no == "001"

    with pytest.raises(SdkCallError) as exc_info:
        gateway.get_quote(config, ["2330"])
    assert exc_info.value.reason == "MARKET_DATA_UNAVAILABLE"


def test_login_session_is_reused_across_calls(tmp_path):
    config = _config_with(tmp_path)
    fake = FakeYuantaSparkGateway()
    gateway = YuantaBrokerGateway(raw_factory=lambda cfg: fake)
    gateway.ensure_stock_account(config)
    gateway.ensure_stock_account(config)
    assert fake.login_calls == 1


def test_get_quote_normalizes_success_and_marks_bad_field_error():
    fake = FakeYuantaSparkGateway()
    fake.responses["quote:2330"] = quote_row()
    fake.responses["quote:2317"] = quote_row(last_price="abc")
    gateway = YuantaBrokerGateway(raw_factory=lambda cfg: fake)

    class _Cfg:
        account = "a"
        password = "b"
        certificate_path = None
        certificate_password = "c"
        dll_path = None
        stock_account_selector = None
        futures_account_selector = None
        internal_service_token = "t"

    result = gateway.get_quote(_Cfg(), ["2330", "2317"])
    assert result["quotes"][0]["status"] == "SUCCESS"
    assert result["quotes"][0]["stockCode"] == "2330"
    assert result["quotes"][1]["status"] == "ERROR"
    assert result["quotes"][1]["stockCode"] == "2317"
    assert result["quotes"][1]["reason"] == "INVALID_DECIMAL"


def test_shutdown_calls_logout_only_if_login_ever_succeeded(tmp_path):
    config = _config_with(tmp_path)
    fake = FakeYuantaSparkGateway()
    gateway = YuantaBrokerGateway(raw_factory=lambda cfg: fake)
    gateway.shutdown()
    assert fake.logout_calls == 0

    gateway2 = YuantaBrokerGateway(raw_factory=lambda cfg: fake)
    gateway2.ensure_logged_in(config)
    gateway2.shutdown()
    assert fake.logout_calls == 1
    gateway2.shutdown()  # idempotent — does not call logout twice
    assert fake.logout_calls == 1


def test_shutdown_survives_logout_exception(tmp_path):
    config = _config_with(tmp_path)
    fake = FakeYuantaSparkGateway()
    gateway = YuantaBrokerGateway(raw_factory=lambda cfg: fake)
    gateway.ensure_logged_in(config)

    def _boom():
        raise RuntimeError("cleanup exploded")

    fake.logout = _boom  # type: ignore[method-assign]
    gateway.shutdown()  # must not raise
