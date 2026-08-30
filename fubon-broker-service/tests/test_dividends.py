from copy import deepcopy
from datetime import timedelta
from decimal import Decimal

import pytest

from fubon_broker_service.dividends import DividendError, DividendService

from market_fixtures import DIVIDEND_FROM, DIVIDEND_TO, NOW, MarketGateway, dividend_row


def read(rows, symbols=None):
    gateway = MarketGateway(dividends={"data": rows})
    result = DividendService(gateway, now=lambda: NOW).read(symbols or ["2330"], DIVIDEND_FROM, DIVIDEND_TO)
    return result, gateway


def test_once_per_date_batch_exact_radar_intersection_and_partial_coverage():
    result, gateway = read([dividend_row("2330"), dividend_row("1101", cashDividend="bad")],
                           symbols=["0050", "2330"])
    assert gateway.calls == [("dividends", DIVIDEND_FROM, DIVIDEND_TO)]
    assert result["scopeFrom"] == DIVIDEND_FROM and result["scopeTo"] == DIVIDEND_TO
    assert result["queryDate"] == "2026-08-28" and result["provider"] == "FUBON_SDK"
    assert result["rows"][0] == {"symbol": "0050", "status": "PARTIAL", "usable": False,
                                 "reason": "NO_MATCHING_EVENTS", "events": []}
    selected = result["rows"][1]
    assert selected["symbol"] == "2330" and selected["status"] == "PARTIAL" and selected["usable"]
    assert selected["events"] == [{"date": "2026-08-28", "exchange": "TWSE", "dividendType": "息",
                                   "year": 2026, "cashDividend": "2.750000", "stockDividend": None,
                                   "exDividendDate": "2026-08-28", "exRightsDate": None,
                                   "cashPaymentDate": None, "stockPaymentDate": None}]


def test_stock_dividend_units_are_never_invented_and_cash_is_retained():
    result, _ = read([dividend_row(dividendType="權息", stockDividendShares=50),
                      dividend_row("0050", dividendType="權", cashDividend=None, stockDividendShares=20)],
                     ["2330", "0050"])
    mixed, stock = result["rows"]
    assert mixed["reason"] == stock["reason"] == "STOCK_DIVIDEND_UNIT_UNVERIFIED"
    assert mixed["events"][0]["stockDividend"] is None
    assert mixed["events"][0]["exRightsDate"] == "2026-08-28"
    assert stock["events"] == [] and not stock["usable"]


@pytest.mark.parametrize("cash,expected", [("1.2345675", "1.234568"), (0.1, "0.100000"),
                                           ("999999999999.999999", "999999999999.999999")])
def test_cash_rounding_is_explicit_half_up_six_places(cash, expected):
    result, _ = read([dividend_row(cashDividend=cash)])
    assert result["rows"][0]["events"][0]["cashDividend"] == expected


@pytest.mark.parametrize("changes", [
    {"cashDividend": True}, {"cashDividend": -1}, {"cashDividend": "NaN"},
    {"cashDividend": float("inf")}, {"cashDividend": "1e2"}, {"cashDividend": None},
    {"cashDividend": "999999999999.9999995"}, {"cashDividend": "1000000000000"},
    {"stockDividendShares": -1}, {"stockDividendShares": True},
    {"date": "20260828"}, {"date": "2026/08/28"}, {"date": "2026-02-30"},
    {"date": "2026-10-13"}, {"date": "2025-10-11"}, {"exchange": "NYSE"},
    {"exchange": None}, {"dividendType": "cash"},
])
def test_invalid_selected_symbol_fails_only_that_symbol(changes):
    result, _ = read([dividend_row(**changes), dividend_row("0050")], ["2330", "0050"])
    assert result["rows"][0] == {"symbol": "2330", "status": "FAILED", "usable": False,
                                 "reason": "DIVIDEND_SCHEMA_INVALID", "events": []}
    assert result["rows"][1]["usable"] is True


def test_duplicate_event_is_not_silently_deduplicated_and_latest_bad_does_not_hide():
    result, _ = read([dividend_row(), dividend_row()])
    assert result["rows"][0]["status"] == "FAILED"


def test_zero_and_rounding_to_zero_are_not_empty_complete():
    zero, _ = read([dividend_row(cashDividend=0)])
    tiny, _ = read([dividend_row(cashDividend="0.00000001")])
    assert zero["rows"][0]["status"] == "PARTIAL" and zero["rows"][0]["events"] == []
    assert tiny["rows"][0]["reason"] == "DIVIDEND_ROUNDED_TO_ZERO"


@pytest.mark.parametrize("data", [[None], [{}], [{"symbol": None}], [{"symbol": ""}], "bad", None])
def test_unattributable_schema_errors_cannot_be_hidden(data):
    gateway = MarketGateway(dividends={"data": data})
    with pytest.raises(DividendError, match="DIVIDEND_SCHEMA_INVALID"):
        DividendService(gateway, now=lambda: NOW).read(["2330"], DIVIDEND_FROM, DIVIDEND_TO)


@pytest.mark.parametrize("symbols,start,end", [
    ([], DIVIDEND_FROM, DIVIDEND_TO), (["2330"] * 2, DIVIDEND_FROM, DIVIDEND_TO),
    (["0000"], DIVIDEND_FROM, DIVIDEND_TO), ([2330], DIVIDEND_FROM, DIVIDEND_TO),
    ([f"X{i}" for i in range(2001)], DIVIDEND_FROM, DIVIDEND_TO),
    (["2330"], "2025-10-13", DIVIDEND_TO), (["2330"], DIVIDEND_FROM, "2026-10-11"),
])
def test_invalid_request_never_calls_sdk(symbols, start, end):
    gateway = MarketGateway()
    with pytest.raises(DividendError) as captured:
        DividendService(gateway, now=lambda: NOW).read(symbols, start, end)
    assert captured.value.request_error and gateway.calls == []


def test_cross_midnight_cannot_label_previous_query_as_today():
    ticks = iter([NOW, NOW + timedelta(days=1)])
    with pytest.raises(DividendError, match="STALE_QUERY"):
        DividendService(MarketGateway(), now=lambda: next(ticks)).read(["2330"], DIVIDEND_FROM, DIVIDEND_TO)
