"""Invented, offline fixtures for the documented provider shape; not live SDK evidence."""

from copy import deepcopy
from datetime import UTC, datetime, timedelta

from fubon_broker_service.sdk_gateway import SdkCallError
from fubon_broker_service.technical_indicators import PARAMETERS


NOW = datetime(2026, 8, 28, 5, 0, tzinfo=UTC)
TODAY = NOW.date().isoformat()
DIVIDEND_FROM = (NOW.date() - timedelta(days=320)).isoformat()
DIVIDEND_TO = (NOW.date() + timedelta(days=45)).isoformat()
TECHNICAL_FROM = (NOW.date() - timedelta(days=120)).isoformat()


def dividend_row(symbol="2330", **changes):
    row = {"symbol": symbol, "date": "2026-08-28", "exchange": "TWSE", "name": "測試股票",
           "dividendType": "息", "cashDividend": 2.75, "stockDividendShares": None,
           "previousClose": None, "referencePrice": None}
    row.update(changes)
    return row


def technical_result(kind, symbol="2330", source_date=TODAY, **changes):
    values = {"kdj": {"k": "30.125", "d": "45.5", "j": "-0.625"},
              "macd": {"macdLine": "-2.125", "signalLine": "0"},
              "bb": {"upper": "3", "middle": "0", "lower": "-3.75"}}
    result = {"symbol": symbol, "from": TECHNICAL_FROM, "to": TODAY,
              **PARAMETERS[kind], "data": [{"date": source_date, **values[kind]}]}
    result.update(changes)
    return result


class MarketGateway:
    def __init__(self, *, dividends=None, groups=None):
        self.dividends = dividends if dividends is not None else {"data": [dividend_row()]}
        self.groups = groups or {kind: technical_result(kind) for kind in PARAMETERS}
        self.calls = []

    def read_dividends(self, start, end):
        self.calls.append(("dividends", start, end))
        if isinstance(self.dividends, SdkCallError):
            raise self.dividends
        return deepcopy(self.dividends)

    def read_technical_indicator(self, kind, symbol, start, end, *, deadline=None):
        self.calls.append((kind, symbol, start, end, deadline))
        item = self.groups[kind]
        if callable(item):
            item = item()
        if isinstance(item, SdkCallError):
            raise item
        return deepcopy(item)
