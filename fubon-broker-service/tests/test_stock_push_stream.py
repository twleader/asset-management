import asyncio
from copy import deepcopy
from datetime import timedelta
import json
import threading
import time

import pytest

from fubon_broker_service.stock_push_stream import StockPushError
from fubon_broker_service.taiex_index_stream import TaiexIndexStream

from helpers import ready_config
from stock_push_fixtures import FakeWebsocket, STOCK_NOW, StreamFixture, TRADE_AT, packet, wait_for


def test_desired_ack_is_not_provider_confirmation_and_empty_scope_opens_no_sdk(tmp_path):
    fixture = StreamFixture(tmp_path)
    stream, config = fixture.stream, fixture.loader.load()
    try:
        with pytest.raises(StockPushError, match="NO_SUBSCRIPTIONS"):
            stream.ensure_started(config)
        assert stream.replace_desired([], config) == {"outcome": "CLEARED", "symbolCount": 0, "leaseSeconds": 0}
        assert stream.replace_desired(["2330"], config) == {"outcome": "ACCEPTED", "symbolCount": 1, "leaseSeconds": 120}
        assert fixture.sdks == [] and stream._confirmed == {}
        stream.ensure_started(config)
        wait_for(lambda: stream._confirmed == {"2330": fixture.sdks[0].normal_clients[0].ids.get("2330")})
        sdk = fixture.sdks[0]
        assert fixture.gateway._stock_client is sdk.speed_rest
        assert fixture.gateway.realtime_stock_websocket() is sdk.index
        assert fixture.gateway._stock_push_websocket_client is sdk.normal_clients[0]
        assert sdk.normal_clients[0] is not sdk.index
        assert sdk.stock.accessed == [] and sdk.index.calls == []
    finally:
        fixture.close()


@pytest.mark.parametrize("symbols,reason", [(["0000"], "INVALID_REQUEST"), (["2330", "2330"], "INVALID_REQUEST"),
                                           (["bad symbol"], "INVALID_REQUEST"), ([2330], "INVALID_REQUEST"),
                                           ([f"S{i}" for i in range(301)], "SUBSCRIPTION_LIMIT")])
def test_invalid_or_overlimit_scope_is_not_truncated_and_makes_no_sdk_call(tmp_path, symbols, reason):
    fixture = StreamFixture(tmp_path)
    try:
        with pytest.raises(StockPushError, match=reason) as captured:
            fixture.stream.replace_desired(symbols, fixture.loader.load())
        assert captured.value.request_error and fixture.sdks == []
    finally:
        fixture.close()


@pytest.mark.parametrize("flag", ["false", "bad"])
def test_stock_flag_gate_independent_from_index_has_no_sdk_side_effect(tmp_path, flag):
    fixture = StreamFixture(tmp_path)
    config = ready_config(tmp_path, stock_push_enabled=flag, taiex_index_stream_enabled="true", taiex_index_symbol="IR0001").load()
    try:
        with pytest.raises(StockPushError):
            fixture.stream.replace_desired(["2330"], config)
        with pytest.raises(StockPushError):
            fixture.stream.ensure_started(config)
        assert fixture.sdks == []
        assert fixture.stream.replace_desired([], config)["outcome"] == "CLEARED"
    finally:
        fixture.close()


def test_same_set_renews_lease_without_duplicates_and_differences_use_ack_ids(tmp_path):
    fixture = StreamFixture(tmp_path)
    try:
        client = fixture.start(["2330", "0050"])
        wait_for(lambda: set(fixture.stream._confirmed) == {"2330", "0050"})
        removed_id = client.ids["0050"]
        fixture.clock[0] += 30
        fixture.stream.replace_desired(["0050", "2330"], fixture.loader.load())
        assert fixture.stream._lease_until == fixture.clock[0] + 120
        fixture.stream.replace_desired(["2330", "1101"], fixture.loader.load())
        wait_for(lambda: set(fixture.stream._confirmed) == {"2330", "1101"})
        assert [payload for name, payload in client.calls if name == "subscribe"] == [
            {"channel": "aggregates", "symbols": ["0050", "2330"], "intradayOddLot": False},
            {"channel": "aggregates", "symbols": ["1101"], "intradayOddLot": False},
        ]
        assert ("unsubscribe", {"ids": [removed_id]}) in client.calls
        assert sum(name == "connect" for name, _ in client.calls) == 1
        fixture.stream.replace_desired([], fixture.loader.load())
        wait_for(client.disconnected.is_set)
        assert fixture.stream._confirmed == {} and fixture.stream._latest == {}
        assert "logout" not in fixture.events
        assert client.callbacks["message"] == [client._provider_listener]
    finally:
        fixture.close()


def test_data_requires_current_provider_ack_and_snapshot_is_not_a_trade(tmp_path):
    fixture = StreamFixture(tmp_path, websocket_factory=lambda label: FakeWebsocket(label, auto_ack=False))
    try:
        client = fixture.start()
        data = packet(channel_id=client.ids["2330"])
        client.emit("message", data)
        assert fixture.stream._latest == {}
        client.emit("message", {"event": "subscribed", "data": {"id": client.ids["2330"], "symbol": "2330", "channel": "aggregates"}})
        client.emit("message", {**data, "event": "snapshot"})
        assert fixture.stream._latest == {}
        client.emit("message", data)
        assert fixture.stream._latest["2330"].price == "123.5"
    finally:
        fixture.close()


def test_no_ack_times_out_reconnects_full_scope_and_old_callback_cannot_publish(tmp_path):
    created = [0]
    def factory(label):
        created[0] += 1
        return FakeWebsocket(label, auto_ack=created[0] > 1)
    fixture = StreamFixture(tmp_path, websocket_factory=factory, sleeper=lambda _seconds: None)
    try:
        first = fixture.start(["2330", "0050"])
        old_callback = first.callbacks["message"][-1]
        fixture.clock[0] += 5
        fixture.stream._wake.set()
        wait_for(lambda: len(fixture.sdks[0].normal_clients) == 2 and set(fixture.stream._confirmed) == {"2330", "0050"})
        second = fixture.sdks[0].normal_clients[1]
        assert second.calls[1] == ("subscribe", {"channel": "aggregates", "symbols": ["0050", "2330"], "intradayOddLot": False})
        assert first.disconnected.is_set()
        old_callback(packet(channel_id=first.ids["2330"]))
        assert fixture.stream._latest == {}
        second.emit("message", packet(channel_id=second.ids["2330"]))
        assert "2330" in fixture.stream._latest
    finally:
        fixture.close()


def test_ack_for_unrequested_symbol_triggers_reconnect_instead_of_expanding_scope(tmp_path):
    fixture = StreamFixture(tmp_path, sleeper=lambda _seconds: None)
    try:
        first = fixture.start()
        first.emit("message", {"event": "subscribed", "data": {"id": "unknown", "symbol": "1101", "channel": "aggregates"}})
        wait_for(lambda: len(fixture.sdks[0].normal_clients) >= 2)
        wait_for(lambda: set(fixture.stream._confirmed) == {"2330"})
        assert fixture.stream._desired == {"2330"}
    finally:
        fixture.close()


def test_large_scope_replacement_waits_for_removal_acks_before_reusing_capacity(tmp_path):
    fixture = StreamFixture(tmp_path)
    original, replacement = [f"A{i}" for i in range(300)], [f"B{i}" for i in range(300)]
    try:
        client = fixture.start(original)
        wait_for(lambda: len(fixture.stream._confirmed) == 300)
        client.auto_ack = False
        old_ids = dict(client.ids)
        fixture.stream.replace_desired(replacement, fixture.loader.load())
        wait_for(lambda: any(name == "unsubscribe" for name, _ in client.calls))
        assert sum(name == "subscribe" for name, _ in client.calls) == 1
        client.auto_ack = True
        client.emit("message", {"event": "unsubscribed", "data": [
            {"id": channel_id, "channel": "aggregates", "symbol": symbol} for symbol, channel_id in old_ids.items()]})
        wait_for(lambda: set(fixture.stream._confirmed) == set(replacement))
        assert all(len(payload["symbols"]) <= 300 for name, payload in client.calls if name == "subscribe")
    finally:
        fixture.close()


@pytest.mark.parametrize("trigger", ["lease", "market_close", "disabled", "config_rotation"])
def test_expiry_market_close_or_config_revocation_drops_queues_and_releases_client(tmp_path, trigger):
    fixture = StreamFixture(tmp_path)
    try:
        client = fixture.start()
        client.emit("message", packet(channel_id=client.ids["2330"]))
        if trigger == "lease":
            fixture.clock[0] += 120
        elif trigger == "market_close":
            fixture.now[0] = STOCK_NOW.replace(hour=5, minute=30)
        elif trigger == "disabled":
            fixture.loader._stock_push_enabled_reader = lambda: "false"
        else:
            (tmp_path / "shared" / "internal-service-token").write_text("another-fake-token")
        fixture.stream._wake.set()
        wait_for(client.disconnected.is_set)
        assert fixture.stream._desired == set() and fixture.stream._confirmed == {}
        assert fixture.stream._latest == {} and len(fixture.sdks[0].normal_clients) == 1
        assert "logout" not in fixture.events
    finally:
        fixture.close()


def test_index_and_stock_reconnect_or_cancellation_do_not_destroy_each_other(tmp_path):
    fixture = StreamFixture(tmp_path, index_enabled="true", sleeper=lambda _seconds: None)
    index = TaiexIndexStream(fixture.gateway, sleeper=lambda _seconds: None)
    try:
        index.ensure_started(fixture.loader.load())
        wait_for(lambda: fixture.sdks and fixture.sdks[0].index.subscribed.is_set())
        index_client = fixture.sdks[0].index
        stock_client = fixture.start()
        index_client.emit("disconnect")
        wait_for(lambda: sum(name == "connect" for name, _ in index_client.calls) == 2)
        assert fixture.stream._confirmed and not stock_client.disconnected.is_set()
        stock_client.emit("disconnect")
        wait_for(lambda: len(fixture.sdks[0].normal_clients) == 2 and fixture.stream._confirmed)
        assert sum(name == "connect" for name, _ in index_client.calls) == 2
        fixture.stream.replace_desired([], fixture.loader.load())
        wait_for(fixture.sdks[0].normal_clients[1].disconnected.is_set)
        assert not index_client.disconnected.is_set() or sum(name == "disconnect" for name, _ in index_client.calls) == 1
        assert index._worker.is_alive() and "logout" not in fixture.events
    finally:
        index.shutdown()
        fixture.close()


def test_sdk_session_generation_rebuilds_both_streams_and_stock_full_desired_set(tmp_path):
    fixture = StreamFixture(tmp_path, index_enabled="true", sleeper=lambda _seconds: None)
    index = TaiexIndexStream(fixture.gateway, sleeper=lambda _seconds: None)
    try:
        index.ensure_started(fixture.loader.load())
        wait_for(lambda: fixture.sdks and fixture.sdks[0].index.subscribed.is_set())
        first = fixture.start(["2330", "0050"])
        old_callback = first.callbacks["message"][-1]
        first.emit("message", packet(channel_id=first.ids["2330"]))
        old_packet = packet(channel_id=first.ids["2330"])
        first_generation = fixture.gateway.session_generation
        with fixture.gateway._session_lock:
            fixture.gateway._invalidate_locked()
        fixture.gateway.quote("2330")
        wait_for(lambda: len(fixture.sdks) == 2 and fixture.sdks[1].index.subscribed.is_set()
                 and fixture.sdks[1].normal_clients and set(fixture.stream._confirmed) == {"2330", "0050"})
        assert fixture.gateway.session_generation > first_generation
        assert fixture.stream._latest == {} and first.disconnected.is_set()
        old_callback(old_packet)
        assert fixture.stream._latest == {}
        latest = fixture.sdks[1].normal_clients[0]
        assert ("subscribe", {"channel": "aggregates", "symbols": ["0050", "2330"], "intradayOddLot": False}) in latest.calls
    finally:
        index.shutdown()
        fixture.close()


def test_sse_real_normalization_bounded_queue_membership_recheck_and_final_consumer_cleanup(tmp_path):
    fixture = StreamFixture(tmp_path)
    try:
        client = fixture.start(["2330", "0050"])
        async def consume():
            iterator = fixture.stream.events()
            pending = asyncio.create_task(anext(iterator))
            await asyncio.sleep(0)
            for index in range(fixture.stream.QUEUE_SIZE + 10):
                client.emit("message", packet(channel_id=client.ids["2330"], at=TRADE_AT + timedelta(microseconds=index)))
            subscriber = next(iter(fixture.stream._subscribers))
            assert subscriber.qsize() == fixture.stream.QUEUE_SIZE
            frame = await asyncio.wait_for(pending, 1)
            assert json.loads(frame.decode().split("data: ")[1])["symbol"] == "2330"
            previous = fixture.stream._latest["2330"]
            client.emit("message", packet(channel_id=client.ids["2330"]))
            assert fixture.stream._latest["2330"] == previous and fixture.stream.last_reason == "REJECTED_STALE"
            fixture.stream.replace_desired(["0050"], fixture.loader.load())
            assert subscriber.qsize() == 0
            pending = asyncio.create_task(anext(iterator))
            await asyncio.sleep(0)
            client.emit("message", packet("0050", client.ids["0050"]))
            frame = await asyncio.wait_for(pending, 1)
            assert json.loads(frame.decode().split("data: ")[1])["symbol"] == "0050"
            await iterator.aclose()
        asyncio.run(consume())
        wait_for(client.disconnected.is_set)
        assert fixture.stream._subscribers == set() and fixture.stream._desired == set()
    finally:
        fixture.close()


def test_closing_one_of_two_consumers_preserves_subscription_until_last_closes(tmp_path):
    fixture = StreamFixture(tmp_path)
    try:
        client = fixture.start()
        async def consume():
            first, second = fixture.stream.events(), fixture.stream.events()
            tasks = [asyncio.create_task(anext(iterator)) for iterator in (first, second)]
            await asyncio.sleep(0)
            client.emit("message", packet(channel_id=client.ids["2330"]))
            await asyncio.wait_for(asyncio.gather(*tasks), 1)
            await first.aclose()
            assert fixture.stream._desired == {"2330"}
            await second.aclose()
        asyncio.run(consume())
        wait_for(client.disconnected.is_set)
    finally:
        fixture.close()


def test_connect_hang_has_bounded_shutdown_and_no_post_cancel_subscribe(tmp_path):
    release = threading.Event()
    def factory(label):
        client = FakeWebsocket(label)
        client.connect_block = release
        return client
    fixture = StreamFixture(tmp_path, websocket_factory=factory)
    fixture.gateway.QUOTE_CALL_TIMEOUT_SECONDS = 0.05
    try:
        fixture.stream.replace_desired(["2330"], fixture.loader.load())
        fixture.stream.ensure_started(fixture.loader.load())
        client = fixture.sdks[0].normal_clients[0]
        assert client.connected.wait(1)
        started = time.monotonic()
        fixture.stream.shutdown()
        assert time.monotonic() - started < 0.5
        assert not any(name == "subscribe" for name, _ in client.calls)
        assert client.disconnected.is_set()
    finally:
        release.set()
        fixture.close()


def test_failed_connection_uses_capped_backoff_and_never_logs_raw_sdk_data(tmp_path, caplog):
    class FailingWebsocket(FakeWebsocket):
        def connect(self):
            raise RuntimeError("TEST_SECRET_PROVIDER_PAYLOAD")
    delays = []
    fixture = None
    def sleeper(seconds):
        delays.append(seconds)
        if len(delays) == 7:
            fixture.stream.revoke()
    fixture = StreamFixture(tmp_path, websocket_factory=FailingWebsocket, sleeper=sleeper)
    try:
        fixture.stream.replace_desired(["2330"], fixture.loader.load())
        fixture.stream.ensure_started(fixture.loader.load())
        wait_for(lambda: len(delays) == 7 and fixture.stream._worker is None)
        assert delays == [0.25, 0.5, 1.0, 2.0, 5.0, 5.0, 5.0]
        assert "TEST_SECRET_PROVIDER_PAYLOAD" not in caplog.text
        assert len(fixture.sdks) == 1
    finally:
        fixture.close()
