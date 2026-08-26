from __future__ import annotations

import asyncio
import threading

import pytest

from fubon_broker_service.sdk_gateway import SdkCallError
from fubon_broker_service.taiex_index_stream import (
    TaiexIndexStream,
    TaiexIndexStreamError,
    normalize_vendor_message,
)

from helpers import ready_config


class FakeWebsocket:
    def __init__(self):
        self.callbacks = {}
        self.calls = []
        self.connected = threading.Event()
        self.reconnected = threading.Event()

    def on(self, name, callback):
        self.callbacks[name] = callback

    def off(self, name, callback):
        if self.callbacks.get(name) == callback:
            self.callbacks.pop(name)
        self.calls.append(("off", name))

    def connect(self):
        self.calls.append("connect")
        self.connected.set()
        if self.calls.count("connect") >= 2:
            self.reconnected.set()

    def subscribe(self, payload):
        self.calls.append(("subscribe", payload))

    def disconnect(self):
        self.calls.append("disconnect")


class FakeGateway:
    def __init__(self):
        self.verify_calls = []
        self.websocket_calls = 0
        self.websocket = FakeWebsocket()

    def verify_taiex_index_symbol(self, symbol):
        self.verify_calls.append(symbol)

    def realtime_stock_websocket(self):
        self.websocket_calls += 1
        return self.websocket


def test_non_secret_disabled_or_invalid_gate_never_reads_sdk(tmp_path):
    gateway = FakeGateway()
    stream = TaiexIndexStream(gateway, sleeper=lambda _seconds: None)

    with pytest.raises(TaiexIndexStreamError):
        stream.ensure_started(ready_config(tmp_path).load())
    with pytest.raises(TaiexIndexStreamError):
        stream.ensure_started(
            ready_config(
                tmp_path, taiex_index_stream_enabled="true", taiex_index_symbol="not a market symbol"
            ).load()
        )

    assert gateway.verify_calls == []
    assert gateway.websocket_calls == 0


def test_symbol_is_verified_then_one_readonly_subscription_starts(tmp_path):
    gateway = FakeGateway()
    stream = TaiexIndexStream(gateway, sleeper=lambda _seconds: None)
    config = ready_config(
        tmp_path, taiex_index_stream_enabled="true", taiex_index_symbol="IR0001"
    ).load()

    stream.ensure_started(config)
    assert gateway.websocket.connected.wait(timeout=1.0)
    assert gateway.verify_calls == ["IR0001"]
    assert gateway.websocket.calls[:2] == ["connect", ("subscribe", {"channel": "indices", "symbol": "IR0001"})]
    stream.shutdown()
    assert {call for call in gateway.websocket.calls if isinstance(call, tuple) and call[0] == "off"} == {
        ("off", "connect"), ("off", "disconnect"), ("off", "error"), ("off", "message")
    }


def test_config_symbol_change_fails_closed_without_a_second_subscription(tmp_path):
    gateway = FakeGateway()
    stream = TaiexIndexStream(gateway, sleeper=lambda _seconds: None)
    first = ready_config(tmp_path, taiex_index_stream_enabled="true", taiex_index_symbol="IR0001").load()
    second = ready_config(tmp_path, taiex_index_stream_enabled="true", taiex_index_symbol="IR0002").load()

    stream.ensure_started(first)
    assert gateway.websocket.connected.wait(timeout=1.0)
    with pytest.raises(TaiexIndexStreamError, match="TAIEX_INDEX_SYMBOL_CHANGED"):
        stream.ensure_started(second)
    assert gateway.verify_calls == ["IR0001"]
    assert gateway.websocket_calls == 1
    stream.shutdown()


def test_disconnect_reconnects_and_resubscribes_exactly_the_configured_symbol(tmp_path):
    gateway = FakeGateway()
    stream = TaiexIndexStream(gateway, sleeper=lambda _seconds: None)
    config = ready_config(tmp_path, taiex_index_stream_enabled="true", taiex_index_symbol="IR0001").load()

    stream.ensure_started(config)
    assert gateway.websocket.connected.wait(timeout=1.0)
    gateway.websocket.callbacks["disconnect"]()
    assert gateway.websocket.reconnected.wait(timeout=1.0)
    assert gateway.websocket.calls.count("connect") >= 2
    assert gateway.websocket.calls.count(("subscribe", {"channel": "indices", "symbol": "IR0001"})) >= 2
    stream.shutdown()


def test_symbol_verification_failure_never_starts_websocket(tmp_path):
    class RejectingGateway(FakeGateway):
        def verify_taiex_index_symbol(self, _symbol):
            raise SdkCallError("TAIEX_INDEX_SYMBOL_UNVERIFIED", misconfigured=True)

    gateway = RejectingGateway()
    stream = TaiexIndexStream(gateway, sleeper=lambda _seconds: None)
    config = ready_config(
        tmp_path, taiex_index_stream_enabled="true", taiex_index_symbol="IR0001"
    ).load()

    with pytest.raises(TaiexIndexStreamError, match="TAIEX_INDEX_SYMBOL_UNVERIFIED"):
        stream.ensure_started(config)
    assert gateway.websocket_calls == 0


def test_normalizer_rejects_float_duplicate_unknown_and_wrong_identity():
    valid = '{"event":"data","channel":"indices","data":{"symbol":"IR0001","exchange":"TWSE","type":"INDEX","index":22345.67,"time":1724720400123456}}'
    event = normalize_vendor_message(valid, "IR0001")
    assert event is not None
    assert event.index == "22345.67"
    assert event.time == 1724720400123456

    assert normalize_vendor_message(
        '{"event":"data","channel":"indices","data":{"symbol":"IR0001","exchange":"TWSE","type":"INDEX","index":1.0,"index":2.0,"time":1724720400123456}}',
        "IR0001",
    ) is None
    assert normalize_vendor_message(
        {"event": "data", "channel": "indices", "data": {"symbol": "IR0001", "exchange": "TWSE", "type": "INDEX", "index": 22345.67, "time": 1724720400123456}},
        "IR0001",
    ) is None
    assert normalize_vendor_message(
        '{"event":"data","channel":"indices","data":{"symbol":"OTHER","exchange":"TWSE","type":"INDEX","index":"22345.67","time":1724720400123456}}',
        "IR0001",
    ) is None


def test_sse_uses_exact_normalized_payload_and_latest_replay():
    gateway = FakeGateway()
    stream = TaiexIndexStream(gateway, sleeper=lambda _seconds: None)
    stream._symbol = "IR0001"  # The callback is otherwise only reachable after the verified worker starts.
    stream._on_message(
        '{"event":"data","channel":"indices","data":{"symbol":"IR0001","exchange":"TWSE","type":"INDEX","index":22345.67,"time":1724720400123456}}'
    )

    async def first_frame():
        generator = stream.events()
        try:
            return await anext(generator)
        finally:
            await generator.aclose()

    frame = asyncio.run(first_frame())
    assert frame == (
        b'event: taiex-index\nid: 1724720400123456\ndata: '
        b'{"symbol":"IR0001","exchange":"TWSE","type":"INDEX","index":"22345.67","time":1724720400123456}\n\n'
    )
