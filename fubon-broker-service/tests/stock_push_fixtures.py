"""Invented offline aggregates packets and clients; never a live-account/provider attestation."""

from copy import deepcopy
from datetime import UTC, datetime, timedelta
import threading
import time
from types import SimpleNamespace

from fubon_broker_service.sdk_gateway import SdkGateway
from fubon_broker_service.stock_push_stream import StockPushStream

from helpers import ready_config, response
from test_sdk_gateway import FakeSdk, Intraday


STOCK_NOW = datetime(2026, 8, 28, 2, 15, 1, tzinfo=UTC)
TRADE_AT = STOCK_NOW - timedelta(seconds=1)
MICROS = int((TRADE_AT - datetime(1970, 1, 1, tzinfo=UTC)) / timedelta(microseconds=1))


def packet(symbol="2330", channel_id=None, *, at=TRADE_AT):
    micros = int((at - datetime(1970, 1, 1, tzinfo=UTC)) / timedelta(microseconds=1))
    return {"event": "data", "id": channel_id or f"channel:{symbol}", "channel": "aggregates",
            "data": {"date": "2026-08-28", "symbol": symbol, "exchange": "TWSE", "type": "EQUITY",
                     "name": " 測試股票 ", "lastTrade": {"price": "123.5", "size": 3, "time": micros},
                     "previousClose": "122", "openPrice": "122.5", "highPrice": "124", "lowPrice": "121.5",
                     "closePrice": "123.5", "closeTime": micros, "lastPrice": 9000,
                     "lastTrial": {"price": 9000, "size": 999, "time": micros + 1000000},
                     "total": {"tradeVolume": 456}, "lastUpdated": micros + 1000000}}


class FakeWebsocket:
    def __init__(self, label, *, auto_ack=True):
        self.label = label
        self.auto_ack = auto_ack
        self.callbacks = {"message": [self._provider_listener]}
        self.calls = []
        self.ids = {}
        self.connected = threading.Event()
        self.subscribed = threading.Event()
        self.disconnected = threading.Event()
        self.connect_block = None
        self.subscribe_count = 0

    def _provider_listener(self, *_args):
        pass

    def on(self, name, callback):
        self.callbacks.setdefault(name, []).append(callback)

    def off(self, name, callback):
        if callback in self.callbacks.get(name, []):
            self.callbacks[name].remove(callback)
        self.calls.append(("off", name))

    def emit(self, name, *args):
        for callback in list(self.callbacks.get(name, [])):
            callback(*args)

    def connect(self):
        self.calls.append(("connect", None))
        self.connected.set()
        if self.connect_block is not None:
            self.connect_block.wait()

    def subscribe(self, payload):
        self.calls.append(("subscribe", deepcopy(payload)))
        self.subscribe_count += 1
        if payload["channel"] == "aggregates":
            rows = []
            for symbol in payload.get("symbols", [payload.get("symbol")]):
                channel_id = f"{self.label}:{symbol}:{self.subscribe_count}"
                self.ids[symbol] = channel_id
                rows.append({"id": channel_id, "symbol": symbol, "channel": "aggregates"})
            if self.auto_ack:
                self.emit("message", {"event": "subscribed", "data": rows})
        self.subscribed.set()

    def unsubscribe(self, payload):
        self.calls.append(("unsubscribe", deepcopy(payload)))
        requested = payload.get("ids", [payload.get("id")])
        rows = [{"id": channel_id, "symbol": symbol, "channel": "aggregates"}
                for symbol, channel_id in self.ids.items() if channel_id in requested]
        for row in rows:
            self.ids.pop(row["symbol"], None)
        if rows and self.auto_ack:
            self.emit("message", {"event": "unsubscribed", "data": rows})

    def disconnect(self):
        self.calls.append(("disconnect", None))
        self.disconnected.set()


class StreamSdk(FakeSdk):
    def __init__(self, events, label, websocket_factory=None):
        super().__init__(events)
        self.label = label
        self.index = FakeWebsocket(f"{label}:index")
        self.normal_clients = []
        self.websocket_factory = websocket_factory or (lambda label: FakeWebsocket(label))
        self.speed_rest = None

    def init_realtime(self, mode=None):
        self.events.append(("init_realtime", mode))
        stock = SimpleNamespace(intraday=Intraday(self.events))
        if mode is None:
            websocket = self.index
            self.speed_rest = stock
        else:
            assert mode == "NORMAL", "stocks must explicitly select Normal mode"
            websocket = self.websocket_factory(f"{self.label}:stock:{len(self.normal_clients)}")
            self.normal_clients.append(websocket)
        self.marketdata = SimpleNamespace(rest_client=SimpleNamespace(stock=stock),
                                          websocket_client=SimpleNamespace(stock=websocket))
        return response(None)


class StreamFixture:
    def __init__(self, tmp_path, *, index_enabled="false", websocket_factory=None, sleeper=time.sleep):
        self.events, self.sdks = [], []
        self.clock = [100.0]
        self.now = [STOCK_NOW]
        self.loader = ready_config(tmp_path, stock_push_enabled="true",
                                   taiex_index_stream_enabled=index_enabled,
                                   taiex_index_symbol="IR0001" if index_enabled == "true" else "")
        def factory():
            sdk = StreamSdk(self.events, f"session{len(self.sdks)}", websocket_factory)
            self.sdks.append(sdk)
            return sdk
        self.gateway = SdkGateway(self.loader, sdk_factory=factory, normal_mode_factory=lambda: "NORMAL",
                                  monotonic=lambda: self.clock[0], sleeper=lambda _delay: None)
        self.stream = StockPushStream(self.gateway, now=lambda: self.now[0], monotonic=lambda: self.clock[0], sleeper=sleeper)

    def start(self, symbols=None):
        config = self.loader.load()
        self.stream.replace_desired(symbols or ["2330"], config)
        self.stream.ensure_started(config)
        wait_for(lambda: self.sdks and self.sdks[-1].normal_clients and self.sdks[-1].normal_clients[-1].subscribed.is_set())
        return self.sdks[-1].normal_clients[-1]

    def close(self):
        self.stream.shutdown()
        self.gateway.shutdown()


def wait_for(predicate, *, timeout=2):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if predicate():
            return
        time.sleep(0.005)
    assert predicate(), "timed out waiting for an offline stream lifecycle condition"
