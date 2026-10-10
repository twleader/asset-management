"""Raw one-minute transport contract remains separate from Java completion selection."""
from copy import deepcopy
from datetime import UTC, datetime, timedelta

import pytest

from fubon_broker_service.market_data_v1 import MarketDataV1Error, MarketDataV1Service
from test_task408_market_data import FixedGateway


def test_all_271_minutes_and_closing_auction_are_valid_raw_candles_without_completion_claim():
    gateway = FixedGateway()
    started = datetime(2026, 8, 28, 5, 40, tzinfo=UTC)
    first = datetime(2026, 8, 28, 1, 0, tzinfo=UTC)
    sample = deepcopy(gateway.candle_source["data"][0])
    sample["average"] = "999"
    gateway.candle_source["data"] = [
        {**sample, "date": (first + timedelta(minutes=n)).isoformat()} for n in range(271)
    ]
    result = MarketDataV1Service(gateway, now=lambda: started).candles("2330")
    assert result["status"] == "AVAILABLE"
    assert len(result["candles"]) == 271
    assert result["candles"][-1]["candleAt"] == "2026-08-28T05:30:00Z"
    assert result["candles"][0]["average"] == "999"
    assert "completed" not in result and "isFinal" not in result


@pytest.mark.parametrize("change", [{"average": "0"}, {"date": "2026-08-28T05:31:00Z"},
                                   {"date": "2026-08-28T01:00:01Z"}])
def test_invalid_prices_outside_session_and_nonminute_times_remain_rejected(change):
    gateway = FixedGateway()
    gateway.candle_source["data"][0].update(change)
    with pytest.raises(MarketDataV1Error, match="SCHEMA_INVALID"):
        MarketDataV1Service(gateway, now=lambda: datetime(2026, 8, 28, 5, 40, tzinfo=UTC)).candles("2330")
