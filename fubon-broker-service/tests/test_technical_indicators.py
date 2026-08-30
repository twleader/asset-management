from copy import deepcopy
from decimal import Decimal

import pytest

from fubon_broker_service.normalization import canonical_number
from fubon_broker_service.sdk_gateway import SdkCallError
from fubon_broker_service.technical_indicators import PARAMETERS, TechnicalIndicatorError, TechnicalIndicatorService

from market_fixtures import NOW, TECHNICAL_FROM, TODAY, MarketGateway, technical_result


def read(groups=None, gateway=None, monotonic=lambda: 100.0):
    gateway = gateway or MarketGateway(groups=groups)
    return TechnicalIndicatorService(gateway, now=lambda: NOW, monotonic=monotonic).read("2330", TECHNICAL_FROM, TODAY), gateway


def test_groups_keep_independent_source_dates_signed_values_and_parameters():
    groups = {kind: technical_result(kind, source_date=day) for kind, day in
              [("kdj", "2026-08-26"), ("macd", "2026-08-27"), ("bb", "2026-08-28")]}
    result, gateway = read(groups)
    assert result["symbol"] == "2330" and result["queryFrom"] == TECHNICAL_FROM
    assert result["kdj"]["payload"]["j"] == "-0.625"
    assert result["macd"]["payload"] == {"macdLine": "-2.125", "signalLine": "0"}
    assert result["bb"]["payload"]["lower"] == "-3.75"
    for kind, day in [("kdj", "2026-08-26"), ("macd", "2026-08-27"), ("bb", "2026-08-28")]:
        assert result[kind]["sourceDate"] == day
        assert result[kind]["sourceTimestamp"] is None and result[kind]["status"] == "AVAILABLE"
        assert result[kind]["parameters"] == PARAMETERS[kind]
    assert [call[0] for call in gateway.calls] == ["kdj", "macd", "bb"]
    assert {call[-1] for call in gateway.calls} == {125.0}
    assert "histogram" not in str(result)


@pytest.mark.parametrize("kind,key,value", [
    ("kdj", "symbol", "0050"), ("kdj", "timeframe", "W"), ("kdj", "rPeriod", "9"),
    ("kdj", "kPeriod", True), ("kdj", "dPeriod", 4), ("macd", "fast", 11),
    ("macd", "slow", 25), ("macd", "signal", 8), ("bb", "period", "020"),
    ("bb", "period", 20.0), ("bb", "period", None), ("bb", "to", "2026-08-27"),
    ("kdj", "from", "2026-05-01"), ("macd", "data", None), ("bb", "data", {}),
])
def test_wrong_echo_or_shape_invalidates_only_one_group(kind, key, value):
    groups = {name: technical_result(name) for name in PARAMETERS}
    groups[kind][key] = value
    result, _ = read(groups)
    assert result[kind]["status"] == "SCHEMA_INVALID"
    assert result[kind]["payload"] is None and result[kind]["sourceDate"] is None
    assert all(result[other]["status"] == "AVAILABLE" for other in PARAMETERS if other != kind)


def test_bb_accepts_only_documented_canonical_string_period():
    groups = {name: technical_result(name) for name in PARAMETERS}
    groups["bb"]["period"] = "20"
    result, _ = read(groups)
    assert result["bb"]["status"] == "AVAILABLE"


@pytest.mark.parametrize("bad_date", ["20260828", "2026/08/28", "2026-8-28", "2026-02-30", None,
                                      "2026-08-29", "2026-04-29"])
def test_any_invalid_date_in_source_array_invalidates_group(bad_date):
    groups = {kind: technical_result(kind) for kind in PARAMETERS}
    invalid = deepcopy(groups["kdj"]["data"][0])
    invalid["date"] = bad_date
    groups["kdj"]["data"].insert(0, invalid)
    result, _ = read(groups)
    assert result["kdj"]["status"] == "SCHEMA_INVALID"
    assert result["macd"]["status"] == "AVAILABLE"


def test_selects_latest_unsorted_row_but_does_not_fallback_from_bad_latest_or_duplicates():
    groups = {kind: technical_result(kind) for kind in PARAMETERS}
    groups["kdj"]["data"].append({"date": "2026-08-27", "k": None, "d": None, "j": None})
    result, _ = read(groups)
    assert result["kdj"]["sourceDate"] == TODAY and result["kdj"]["status"] == "AVAILABLE"
    groups["kdj"]["data"][0]["j"] = None
    groups["kdj"]["data"][1]["j"] = "9"
    result, _ = read(groups)
    assert result["kdj"]["status"] == "SCHEMA_INVALID"
    groups["kdj"] = technical_result("kdj")
    groups["kdj"]["data"].append(deepcopy(groups["kdj"]["data"][0]))
    result, _ = read(groups)
    assert result["kdj"]["status"] == "SCHEMA_INVALID"


@pytest.mark.parametrize("bad", [True, None, "NaN", float("nan"), float("inf"), "1e2", " 1", "01",
                                  "0.0000000000000000001", "9" * 39, Decimal("1E100000")])
def test_invalid_required_latest_number_never_becomes_available(bad):
    groups = {kind: technical_result(kind) for kind in PARAMETERS}
    groups["macd"]["data"][0]["macdLine"] = bad
    result, _ = read(groups)
    assert result["macd"]["status"] == "SCHEMA_INVALID"
    assert result["kdj"]["status"] == result["bb"]["status"] == "AVAILABLE"


def test_decimal_limits_signed_zero_finite_float_and_no_clamping():
    maximum = "9" * 20 + "." + "9" * 18
    assert canonical_number(maximum, precision=38, scale=18) == maximum
    assert canonical_number(Decimal("-0E-100000"), precision=38, scale=18) == "0"
    assert canonical_number(-2.5, precision=38, scale=18) == "-2.5"
    assert canonical_number("-300.1000", precision=38, scale=18) == "-300.1"
    groups = {kind: technical_result(kind) for kind in PARAMETERS}
    groups["bb"]["data"][0]["lower"] = "4"
    result, _ = read(groups)
    assert result["bb"]["status"] == "SCHEMA_INVALID"


def test_no_data_and_ordinary_timeout_do_not_remove_other_groups():
    groups = {"kdj": technical_result("kdj", data=[]), "macd": SdkCallError("MARKETDATA_TIMEOUT"),
              "bb": technical_result("bb")}
    result, gateway = read(groups)
    assert result["kdj"]["status"] == "NO_DATA" and result["kdj"]["sourceDate"] is None
    assert result["macd"]["reason"] == "MARKETDATA_TIMEOUT" and result["bb"]["status"] == "AVAILABLE"
    assert len(gateway.calls) == 3


@pytest.mark.parametrize("reason", ["RATE_LIMITED", "HISTORY_BUDGET_EXHAUSTED"])
def test_rate_limit_and_local_budget_stop_remaining_calls_with_distinct_reasons(reason):
    groups = {"kdj": technical_result("kdj"), "macd": SdkCallError(reason), "bb": technical_result("bb")}
    result, gateway = read(groups)
    assert result["kdj"]["status"] == "AVAILABLE" and len(gateway.calls) == 2
    assert result["macd"]["reason"] == result["bb"]["reason"] == reason


def test_whole_route_deadline_cancels_remaining_groups():
    clock = [100.0]
    def timeout():
        clock[0] = 125.0
        return SdkCallError("MARKETDATA_TIMEOUT")
    groups = {"kdj": timeout, "macd": technical_result("macd"), "bb": technical_result("bb")}
    result, gateway = read(groups, monotonic=lambda: clock[0])
    assert len(gateway.calls) == 1
    assert all(result[kind]["reason"] == "MARKETDATA_TIMEOUT" for kind in PARAMETERS)


@pytest.mark.parametrize("symbol,start,end", [("0000", TECHNICAL_FROM, TODAY), ("2330", "2026-05-01", TODAY),
                                              ("2330", TECHNICAL_FROM, "2026-08-27")])
def test_fixed_window_and_symbol_invalid_before_sdk(symbol, start, end):
    gateway = MarketGateway()
    with pytest.raises(TechnicalIndicatorError) as captured:
        TechnicalIndicatorService(gateway, now=lambda: NOW).read(symbol, start, end)
    assert captured.value.request_error and gateway.calls == []
