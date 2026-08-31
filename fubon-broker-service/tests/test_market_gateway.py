"""Offline integration through the real gateway, session, limiter and bounded worker calls."""

from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace
import threading

import pytest

from fubon_broker_service.sdk_gateway import SdkCallError, SdkGateway
from fubon_broker_service.technical_indicators import TechnicalIndicatorService

from helpers import ready_config
from market_fixtures import DIVIDEND_FROM, DIVIDEND_TO, NOW, TECHNICAL_FROM, TODAY, technical_result, technical_v2_result
from test_sdk_gateway import FakeSdk


class Clock:
    def __init__(self):
        self.value = 100.0
        self.waits = []

    def __call__(self):
        return self.value

    def sleep(self, seconds):
        self.waits.append(seconds)
        self.value += seconds


class MarketSdk(FakeSdk):
    def __init__(self, events, handler=None):
        super().__init__(events)
        self.handler = handler or self.handle
        self.market_calls = []

    @staticmethod
    def handle(kind, params):
        if kind == "dividends":
            return {"data": []}
        if kind in {"sma", "rsi"}:
            return technical_v2_result(
                kind, params["symbol"], start=params["from"], end=params["to"],
                timeframe=params["timeframe"],
                parameters={key: value for key, value in params.items()
                            if key not in {"symbol", "from", "to", "timeframe"}},
            )
        return technical_result(kind, params["symbol"])

    def call(self, kind, params):
        self.market_calls.append((kind, dict(params)))
        return self.handler(kind, params)

    def init_realtime(self):
        result = super().init_realtime()
        stock = self.marketdata.rest_client.stock
        stock.corporate_actions = SimpleNamespace(dividends=lambda **kw: self.call("dividends", kw))
        stock.technical = SimpleNamespace(**{kind: (lambda kind=kind, **kw: self.call(kind, kw))
                                            for kind in ("sma", "rsi", "kdj", "macd", "bb")})
        return result


class RateLimited(RuntimeError):
    status_code = 429
    retry_after = 61


def make_gateway(tmp_path, *, handler=None):
    clock = Clock()
    sdk = MarketSdk([], handler)
    gateway = SdkGateway(ready_config(tmp_path), sdk_factory=lambda: sdk, monotonic=clock, sleeper=clock.sleep)
    return gateway, sdk, clock


def test_official_parameter_names_fixed_defaults_and_once_date_dividend_call(tmp_path):
    gateway, sdk, clock = make_gateway(tmp_path)
    gateway.read_dividends(DIVIDEND_FROM, DIVIDEND_TO)
    result = TechnicalIndicatorService(gateway, now=lambda: NOW, monotonic=clock).read("2330", TECHNICAL_FROM, TODAY)
    assert sdk.market_calls == [
        ("dividends", {"start_date": DIVIDEND_FROM, "end_date": DIVIDEND_TO}),
        ("kdj", {"symbol": "2330", "from": TECHNICAL_FROM, "to": TODAY, "timeframe": "D", "rPeriod": 9, "kPeriod": 3, "dPeriod": 3}),
        ("macd", {"symbol": "2330", "from": TECHNICAL_FROM, "to": TODAY, "timeframe": "D", "fast": 12, "slow": 26, "signal": 9}),
        ("bb", {"symbol": "2330", "from": TECHNICAL_FROM, "to": TODAY, "timeframe": "D", "period": 20}),
    ]
    assert result["kdj"]["status"] == "AVAILABLE" and sdk.stock.accessed == []
    assert len(gateway._history_starts) == 4


def test_thirty_ninth_task408_marketdata_start_respects_its_request_deadline(tmp_path):
    gateway, sdk, clock = make_gateway(tmp_path)
    for _ in range(38):
        gateway.read_technical_indicator("kdj", "2330", TECHNICAL_FROM, TODAY)
    with pytest.raises(SdkCallError) as captured:
        gateway.read_technical_indicator("kdj", "0050", TECHNICAL_FROM, TODAY, deadline=clock.value + 5)
    assert captured.value.reason == "HISTORY_BUDGET_EXHAUSTED"
    assert len(sdk.market_calls) == 38
    assert clock.waits == []
    assert gateway._history_paused_until == 0
    clock.value = 160.0
    gateway.read_technical_indicator("kdj", "0050", TECHNICAL_FROM, TODAY)
    assert len(sdk.market_calls) == 39


def test_waiting_near_window_edge_obtains_slot_without_rate_limited(tmp_path):
    gateway, sdk, clock = make_gateway(tmp_path)
    for _ in range(60):
        gateway.read_dividends(DIVIDEND_FROM, DIVIDEND_TO)
    clock.value = 159.0
    gateway.read_technical_indicator("kdj", "2330", TECHNICAL_FROM, TODAY)
    assert len(sdk.market_calls) == 61 and sum(clock.waits) == pytest.approx(1.0)


def test_twenty_one_symbols_complete_with_shared_task408_pacing_instead_of_starvation(tmp_path):
    gateway, sdk, clock = make_gateway(tmp_path)
    service = TechnicalIndicatorService(gateway, now=lambda: NOW, monotonic=clock)
    for index in range(21):
        started = clock.value
        result = service.read(f"X{index}", TECHNICAL_FROM, TODAY)
        assert all(result[kind]["status"] == "AVAILABLE" for kind in ("kdj", "macd", "bb"))
        clock.value = max(clock.value, started + 3.1)
    assert len(sdk.market_calls) == 63
    # 63 vendor starts cannot fit in any 60-second window under Task408's
    # process-wide 38-start permit; the fake clock records the bounded wait.
    assert clock.waits and all(wait > 0 for wait in clock.waits)


def test_real_429_pauses_every_historical_reader_for_at_least_one_minute(tmp_path):
    first = [True]
    def handler(kind, params):
        if first.pop() if first else False:
            raise RateLimited("SECRET_PROVIDER_BODY")
        return MarketSdk.handle(kind, params)
    gateway, sdk, clock = make_gateway(tmp_path, handler=handler)
    with pytest.raises(SdkCallError) as captured:
        gateway.read_technical_indicator("kdj", "2330", TECHNICAL_FROM, TODAY)
    assert str(captured.value) == "RATE_LIMITED"
    for kind in ("macd", "bb"):
        with pytest.raises(SdkCallError, match="RATE_LIMITED"):
            gateway.read_technical_indicator(kind, "0050", TECHNICAL_FROM, TODAY)
    with pytest.raises(SdkCallError, match="RATE_LIMITED"):
        gateway.read_dividends(DIVIDEND_FROM, DIVIDEND_TO)
    assert len(sdk.market_calls) == 1 and clock.waits == []
    clock.value += 60
    with pytest.raises(SdkCallError, match="RATE_LIMITED"):
        gateway.read_dividends(DIVIDEND_FROM, DIVIDEND_TO)
    clock.value += 1
    gateway.read_dividends(DIVIDEND_FROM, DIVIDEND_TO)
    assert len(sdk.market_calls) == 2


def test_two_concurrent_symbols_share_session_and_budget_without_mixing_params(tmp_path):
    barrier = threading.Barrier(2)
    def handler(kind, params):
        if kind == "kdj":
            barrier.wait(timeout=2)
        result = technical_result(kind, params["symbol"])
        if kind == "kdj":
            result["data"][0]["j"] = "-1" if params["symbol"] == "2330" else "-2"
        return result
    gateway, sdk, clock = make_gateway(tmp_path, handler=handler)
    service = TechnicalIndicatorService(gateway, now=lambda: NOW, monotonic=clock)
    with ThreadPoolExecutor(max_workers=2) as workers:
        futures = [workers.submit(service.read, code, TECHNICAL_FROM, TODAY) for code in ("2330", "0050")]
        results = [future.result(timeout=3) for future in futures]
    assert [item["kdj"]["payload"]["j"] for item in results] == ["-1", "-2"]
    assert [item["symbol"] for item in results] == ["2330", "0050"]
    for symbol in ("2330", "0050"):
        assert [kind for kind, params in sdk.market_calls if params["symbol"] == symbol] == ["kdj", "macd", "bb"]
    assert len(gateway._history_starts) == 6 and sdk.events.count("login") == 1


def test_inflight_worker_may_complete_after_429_but_cannot_start_next_indicator(tmp_path):
    first_entered = threading.Event()
    second_entered = threading.Event()
    release_second = threading.Event()
    def handler(kind, params):
        assert kind == "kdj", "a new SDK call bypassed the global vendor pause"
        if params["symbol"] == "2330":
            first_entered.set()
            assert second_entered.wait(2)
            raise RateLimited()
        second_entered.set()
        assert release_second.wait(2)
        return technical_result(kind, params["symbol"])
    gateway, sdk, clock = make_gateway(tmp_path, handler=handler)
    service = TechnicalIndicatorService(gateway, now=lambda: NOW, monotonic=clock)
    with ThreadPoolExecutor(max_workers=2) as workers:
        first = workers.submit(service.read, "2330", TECHNICAL_FROM, TODAY)
        assert first_entered.wait(2)
        second = workers.submit(service.read, "0050", TECHNICAL_FROM, TODAY)
        try:
            first_result = first.result(timeout=2)
        finally:
            release_second.set()
        second_result = second.result(timeout=2)
    assert first_result["kdj"]["reason"] == "RATE_LIMITED"
    assert second_result["kdj"]["status"] == "AVAILABLE"
    assert second_result["macd"]["reason"] == second_result["bb"]["reason"] == "RATE_LIMITED"
    assert len(sdk.market_calls) == 2


def test_shutdown_and_route_deadline_prevent_outbound_and_cancel_local_wait(tmp_path):
    gateway, sdk, clock = make_gateway(tmp_path)
    with pytest.raises(SdkCallError, match="MARKETDATA_TIMEOUT"):
        gateway.read_technical_indicator("kdj", "2330", TECHNICAL_FROM, TODAY, deadline=clock.value)
    assert sdk.market_calls == []
    gateway._history_starts.extend([clock.value] * 60)
    def cancel_after_first_wait(seconds):
        clock.sleep(seconds)
        gateway.shutdown()
    gateway._sleeper = cancel_after_first_wait
    with pytest.raises(SdkCallError, match="SERVICE_SHUTDOWN"):
        gateway.read_technical_indicator("kdj", "2330", TECHNICAL_FROM, TODAY)
    assert sum(clock.waits) <= 0.1 and sdk.market_calls == []


def test_unknown_method_never_reaches_trading_and_oversized_response_rejected(tmp_path):
    gateway, sdk, _ = make_gateway(tmp_path)
    with pytest.raises(SdkCallError, match="TECHNICAL_METHOD_UNAVAILABLE"):
        gateway.read_technical_indicator("histogram", "2330", TECHNICAL_FROM, TODAY)
    with pytest.raises(SdkCallError, match="MARKETDATA_METHOD_UNAVAILABLE"):
        gateway._marketdata_read("stock", "anything", {})
    with pytest.raises(SdkCallError, match="MARKETDATA_RESPONSE_TOO_LARGE"):
        gateway._check_marketdata_size({"data": "X" * (8 * 1024 * 1024)})
    assert sdk.events == [] and sdk.stock.accessed == []
