from __future__ import annotations

import asyncio
import logging
import queue
import threading
import time
from collections.abc import AsyncIterator, Callable
from dataclasses import dataclass
from datetime import datetime

from .config import ConfigSnapshot
from .counters import Outcome, OutcomeCounters
from .normalization import stock_codes, utc_now
from .sdk_gateway import SdkCallError, SdkGateway, StockPushConnection
from .stock_push_events import StockPriceEvent, decode_stock_message, market_time_open, normalize_stock_message


logger = logging.getLogger(__name__)


class StockPushError(RuntimeError):
    def __init__(self, reason: str, *, request_error: bool = False) -> None:
        super().__init__(reason)
        self.reason, self.request_error = reason, request_error


@dataclass(frozen=True)
class _QueuedEvent:
    event: StockPriceEvent
    connection_epoch: int
    scope_version: int


class StockPushStream:
    """One independent aggregates connection, bounded queues, and a renewable desired-set lease.

    The external manager supplies the known-calendar/radar authorization. This adapter independently
    enforces feature/config, local 09:00 <= receive time < 13:30, lease and source trade evidence.
    Accepting a desired set does not attest a provider ACK or a usable trade.
    """

    LEASE_SECONDS = 120
    QUEUE_SIZE = 64
    ACK_TIMEOUT_SECONDS = 5.0
    _POLL_SECONDS = 0.1
    _BACKOFF_SECONDS = (0.25, 0.5, 1.0, 2.0, 5.0)

    def __init__(
        self, gateway: SdkGateway, *, now: Callable[[], datetime] = utc_now,
        monotonic: Callable[[], float] = time.monotonic,
        sleeper: Callable[[float], None] = time.sleep,
        counters: OutcomeCounters | None = None,
    ) -> None:
        self._gateway, self._now, self._monotonic, self._sleeper = gateway, now, monotonic, sleeper
        self._counters = counters
        self._lock = threading.RLock()
        self._start_lock = threading.Lock()
        self._shutdown = threading.Event()
        self._wake = threading.Event()
        self._reconnect = threading.Event()
        self._worker: threading.Thread | None = None
        self._desired: frozenset[str] = frozenset()
        self._lease_until = 0.0
        self._authorized_digest: bytes | None = None
        self._client: object | None = None
        self._generation: int | None = None
        self._epoch = 0
        self._scope_version = 0
        self._confirmed: dict[str, str] = {}
        self._pending_subscribe: dict[str, float] = {}
        self._pending_unsubscribe: dict[str, float] = {}
        self._retiring_ids: set[str] = set()
        self._callbacks: dict[str, Callable] = {}
        self._latest: dict[str, StockPriceEvent] = {}
        self._subscribers: set[queue.Queue[_QueuedEvent]] = set()
        self.last_reason = "NO_SUBSCRIPTIONS"

    @staticmethod
    def configured(config: ConfigSnapshot) -> bool:
        return bool(config.enabled and config.state == "READY" and config.stock_push_enabled
                    and config.stock_push_reason is None)

    @staticmethod
    def configuration_reason(config: ConfigSnapshot) -> str:
        if not config.enabled:
            return "DISABLED"
        if config.state != "READY":
            return config.reason or "MISCONFIGURED"
        return config.stock_push_reason or "STOCK_PUSH_DISABLED"

    def replace_desired(self, symbols: list[str], config: ConfigSnapshot) -> dict[str, object]:
        if isinstance(symbols, list) and len(symbols) > 300:
            raise StockPushError("SUBSCRIPTION_LIMIT", request_error=True)
        try:
            desired = frozenset(stock_codes(symbols, maximum=300, allow_empty=True))
        except ValueError:
            raise StockPushError("INVALID_REQUEST", request_error=True) from None
        if not desired:
            with self._lock:
                self._clear_locked("NO_SUBSCRIPTIONS")
            self._wake.set()
            return {"outcome": "CLEARED", "symbolCount": 0, "leaseSeconds": 0}
        if not self.configured(config):
            self.revoke(self.configuration_reason(config))
            raise StockPushError(self.configuration_reason(config))
        if not market_time_open(self._now()):
            self.revoke("MARKET_CLOSED")
            raise StockPushError("MARKET_CLOSED")
        with self._lock:
            if self._shutdown.is_set():
                raise StockPushError("SERVICE_SHUTDOWN")
            if desired != self._desired:
                self._scope_version += 1
                self._drain_queues_locked()
                self._latest = {symbol: event for symbol, event in self._latest.items() if symbol in desired}
            self._desired = desired
            self._authorized_digest = SdkGateway._session_config_digest(config)
            self._lease_until = self._monotonic() + self.LEASE_SECONDS
        self._wake.set()
        # No consumer means no SDK connection. The following GET creates the dedicated client;
        # subsequent POSTs change only the difference on that existing connection.
        return {"outcome": "ACCEPTED", "symbolCount": len(desired), "leaseSeconds": self.LEASE_SECONDS}

    def ensure_started(self, config: ConfigSnapshot) -> None:
        if not self.configured(config):
            self.revoke(self.configuration_reason(config))
            raise StockPushError(self.configuration_reason(config))
        with self._start_lock:
            with self._lock:
                if not self._active_locked():
                    self._clear_locked("NO_SUBSCRIPTIONS")
                    raise StockPushError("NO_SUBSCRIPTIONS")
                if self._authorized_digest != SdkGateway._session_config_digest(config):
                    self._clear_locked("CONFIG_CHANGED")
                    raise StockPushError("CONFIG_CHANGED")
                if self._worker is not None and self._worker.is_alive():
                    return
            try:
                connection = self._gateway.stock_push_websocket()
            except SdkCallError as exc:
                raise StockPushError(exc.reason) from None
            with self._lock:
                if not self._active_locked():
                    rejected = True
                else:
                    rejected = False
                    self._adopt_locked(connection)
                    worker = threading.Thread(target=self._run, args=(connection,),
                                              name="fubon-stock-push-stream", daemon=True)
                    self._worker = worker
                    worker.start()
            if rejected:
                self._gateway.release_stock_push_websocket(connection.client)
                raise StockPushError("NO_SUBSCRIPTIONS")

    async def events(self) -> AsyncIterator[bytes]:
        subscriber: queue.Queue[_QueuedEvent] = queue.Queue(maxsize=self.QUEUE_SIZE)
        with self._lock:
            if not self._active_locked():
                return
            self._subscribers.add(subscriber)
            for event in sorted(self._latest.values(), key=lambda item: item.tradeTimeMicros)[-self.QUEUE_SIZE:]:
                subscriber.put_nowait(_QueuedEvent(event, self._epoch, self._scope_version))
        try:
            while True:
                with self._lock:
                    if not self._active_locked():
                        return
                try:
                    item = subscriber.get_nowait()
                except queue.Empty:
                    await asyncio.sleep(self._POLL_SECONDS)
                    continue
                current_generation = self._gateway.session_generation
                with self._lock:
                    valid = (self._active_locked() and item.connection_epoch == self._epoch
                             and item.scope_version == self._scope_version
                             and current_generation == self._generation and item.event.symbol in self._desired
                             and item.event.symbol in self._confirmed
                             and self._confirmed[item.event.symbol] not in self._pending_unsubscribe)
                if valid:
                    yield item.event.sse_bytes()
        finally:
            with self._lock:
                self._subscribers.discard(subscriber)
                if not self._subscribers:
                    self._clear_locked("NO_CONSUMERS")
            self._wake.set()

    def revoke(self, reason: str = "NO_SUBSCRIPTIONS") -> None:
        with self._lock:
            self._clear_locked(reason)
        self._wake.set()

    def shutdown(self) -> None:
        self._shutdown.set()
        self.revoke("SERVICE_SHUTDOWN")
        with self._lock:
            client, worker = self._client, self._worker
        self._drop_connection(client)
        if worker is not None and worker is not threading.current_thread() and worker.is_alive():
            worker.join(timeout=2.0)

    def _active_locked(self) -> bool:
        return bool(not self._shutdown.is_set() and self._desired and self._monotonic() < self._lease_until
                    and market_time_open(self._now()))

    def _clear_locked(self, reason: str) -> None:
        self._desired = frozenset()
        self._lease_until = 0.0
        self._scope_version += 1
        self._invalidate_tracking_locked()
        self.last_reason = reason

    def _invalidate_tracking_locked(self) -> None:
        self._retiring_ids.update(self._confirmed.values())
        self._retiring_ids.update(self._pending_unsubscribe)
        self._confirmed.clear()
        self._pending_subscribe.clear()
        self._pending_unsubscribe.clear()
        self._latest.clear()
        self._drain_queues_locked()

    def _drain_queues_locked(self) -> None:
        for subscriber in self._subscribers:
            while True:
                try:
                    subscriber.get_nowait()
                except queue.Empty:
                    break

    def _adopt_locked(self, connection: StockPushConnection) -> None:
        self._invalidate_tracking_locked()
        self._retiring_ids.clear()
        self._client, self._generation = connection.client, connection.generation
        self._epoch += 1
        self._reconnect.clear()

    def _authorized_now(self) -> bool:
        config = self._gateway.current_config()
        with self._lock:
            if not self.configured(config):
                self._clear_locked(self.configuration_reason(config))
                return False
            if self._authorized_digest != SdkGateway._session_config_digest(config):
                self._clear_locked("CONFIG_CHANGED")
                return False
            if not self._active_locked():
                self._clear_locked("MARKET_CLOSED" if not market_time_open(self._now()) else "LEASE_EXPIRED")
                return False
            return True

    def _run(self, initial: StockPushConnection) -> None:
        connection: StockPushConnection | None = initial
        backoff = 0
        try:
            while not self._shutdown.is_set():
                client = connection.client if connection is not None else None
                try:
                    if not self._authorized_now():
                        return
                    if connection is None:
                        connection = self._gateway.stock_push_websocket()
                        client = connection.client
                        with self._lock:
                            self._adopt_locked(connection)
                    with self._lock:
                        epoch = self._epoch
                    self._bind_callbacks(connection, epoch)
                    self._call_current(connection, epoch, "connect")
                    started = self._monotonic()
                    while self._authorized_now() and not self._reconnect.is_set():
                        if self._gateway.session_generation != connection.generation:
                            raise SdkCallError("SESSION_CHANGED")
                        self._sync_subscriptions(connection, epoch)
                        if self._monotonic() - started >= 5:
                            backoff = 0
                        self._wake.wait(self._POLL_SECONDS)
                        self._wake.clear()
                except SdkCallError as exc:
                    self.last_reason = exc.reason
                    self._count(Outcome.QUOTE_FAILED)
                    logger.warning("Fubon stock stream unavailable reason=%s", exc.reason)
                except Exception:
                    self.last_reason = "STOCK_PUSH_CONNECT_FAILED"
                    self._count(Outcome.QUOTE_FAILED)
                    logger.warning("Fubon stock stream unavailable reason=STOCK_PUSH_CONNECT_FAILED")
                finally:
                    self._drop_connection(client)
                    connection = None
                with self._lock:
                    if not self._active_locked():
                        return
                self.last_reason = "RECONNECTING"
                delay = self._BACKOFF_SECONDS[min(backoff, len(self._BACKOFF_SECONDS) - 1)]
                backoff += 1
                if self._sleeper is time.sleep:
                    self._shutdown.wait(delay)
                else:
                    self._sleeper(delay)
        finally:
            with self._lock:
                if self._worker is threading.current_thread():
                    self._worker = None

    def _call_current(self, connection: StockPushConnection, epoch: int, name: str,
                      payload: dict[str, object] | None = None) -> None:
        current_generation = self._gateway.session_generation
        with self._lock:
            if (not self._active_locked() or self._client is not connection.client or self._epoch != epoch
                    or current_generation != connection.generation or self._reconnect.is_set()):
                raise SdkCallError("SUBSCRIPTION_CANCELLED")
        self._gateway.stock_push_call(connection.client, name, payload)

    def _sync_subscriptions(self, connection: StockPushConnection, epoch: int) -> None:
        with self._lock:
            now = self._monotonic()
            if any(now - sent >= self.ACK_TIMEOUT_SECONDS
                   for sent in (*self._pending_subscribe.values(), *self._pending_unsubscribe.values())):
                raise SdkCallError("STOCK_SUBSCRIPTION_ACK_TIMEOUT")
            removed = {channel_id for symbol, channel_id in self._confirmed.items()
                       if symbol not in self._desired and channel_id not in self._pending_unsubscribe}
            self._pending_unsubscribe.update({channel_id: now for channel_id in removed})
        if removed:
            self._call_current(connection, epoch, "unsubscribe", {"ids": sorted(removed)})
        with self._lock:
            # Pending removals still consume provider slots until ACK. Never temporarily exceed
            # the 300-symbol provider cap while replacing one large desired set with another.
            capacity = 300 - len(self._confirmed) - len(self._pending_subscribe)
            added = sorted(self._desired - self._confirmed.keys() - self._pending_subscribe.keys())[:max(0, capacity)]
            self._pending_subscribe.update({symbol: self._monotonic() for symbol in added})
        if added:
            self._call_current(connection, epoch, "subscribe",
                               {"channel": "aggregates", "symbols": added, "intradayOddLot": False})

    def _bind_callbacks(self, connection: StockPushConnection, epoch: int) -> None:
        client = connection.client
        on, off = getattr(client, "on", None), getattr(client, "off", None)
        if not callable(on) or not callable(off):
            raise SdkCallError("STOCK_PUSH_CLIENT_UNAVAILABLE")
        callbacks = {
            "message": lambda message, *_args, **_kw: self._on_message(connection, epoch, message),
            "disconnect": lambda *_args, **_kw: self._signal_reconnect(connection, epoch),
            "error": lambda *_args, **_kw: self._signal_reconnect(connection, epoch),
        }
        with self._lock:
            self._callbacks = callbacks
        for name, callback in callbacks.items():
            on(name, callback)

    def _signal_reconnect(self, connection: StockPushConnection, epoch: int) -> None:
        with self._lock:
            if self._client is not connection.client or self._epoch != epoch:
                return
            self._invalidate_tracking_locked()
            self._reconnect.set()
        self._wake.set()

    def _on_message(self, connection: StockPushConnection, epoch: int, message: object) -> None:
        current_generation = self._gateway.session_generation
        with self._lock:
            if (not self._active_locked() or self._client is not connection.client or self._epoch != epoch
                    or current_generation != connection.generation or self._reconnect.is_set()):
                return
        envelope = decode_stock_message(message)
        if envelope is None:
            self.last_reason = "INVALID_EVENT"
            self._count(Outcome.QUOTE_FAILED)
            return
        kind = envelope.get("event")
        if kind in {"subscribed", "unsubscribed"}:
            self._accept_ack(connection, epoch, kind, envelope.get("data"))
            return
        if kind == "error":
            self._signal_reconnect(connection, epoch)
            return
        if kind != "data":
            return
        with self._lock:
            confirmed = {symbol: channel_id for symbol, channel_id in self._confirmed.items()
                         if symbol in self._desired and channel_id not in self._pending_unsubscribe}
        event = normalize_stock_message(envelope, confirmed=confirmed, now=self._now())
        if event is None:
            self.last_reason = "INVALID_EVENT"
            self._count(Outcome.QUOTE_FAILED)
            return
        current_generation = self._gateway.session_generation
        with self._lock:
            if (not self._active_locked() or self._client is not connection.client or self._epoch != epoch
                    or current_generation != connection.generation or self._reconnect.is_set()
                    or event.symbol not in self._desired or self._confirmed.get(event.symbol) != envelope.get("id")
                    or envelope.get("id") in self._pending_unsubscribe):
                return
            previous = self._latest.get(event.symbol)
            if previous is not None and event.tradeTimeMicros <= previous.tradeTimeMicros:
                self.last_reason = "REJECTED_STALE"
                self._count(Outcome.QUOTE_FAILED)
                return
            self._latest[event.symbol] = event
            item = _QueuedEvent(event, self._epoch, self._scope_version)
            for subscriber in tuple(self._subscribers):
                try:
                    subscriber.put_nowait(item)
                except queue.Full:
                    try:
                        subscriber.get_nowait()
                    except queue.Empty:
                        pass
                    subscriber.put_nowait(item)
            self.last_reason = "AVAILABLE"
            self._count(Outcome.SUCCESS)

    def _accept_ack(self, connection: StockPushConnection, epoch: int, kind: str, data: object) -> None:
        rows = data if isinstance(data, list) else [data]
        if not rows or len(rows) > 300:
            self._signal_reconnect(connection, epoch)
            return
        try:
            parsed: dict[str, str] = {}
            ids: set[str] = set()
            for row in rows:
                if not isinstance(row, dict) or row.get("channel") != "aggregates":
                    raise ValueError("INVALID_ACK")
                symbol = stock_codes([row.get("symbol")], maximum=1)[0]
                channel_id = row.get("id")
                if (not isinstance(channel_id, str) or not channel_id or len(channel_id) > 256
                        or not channel_id.isascii() or any(ord(char) < 33 or ord(char) > 126 for char in channel_id)
                        or symbol in parsed or channel_id in ids
                        or "intradayOddLot" in row and row["intradayOddLot"] is not False):
                    raise ValueError("INVALID_ACK")
                parsed[symbol] = channel_id
                ids.add(channel_id)
            with self._lock:
                if self._client is not connection.client or self._epoch != epoch or not self._active_locked():
                    return
                for symbol, channel_id in parsed.items():
                    if kind == "subscribed":
                        if symbol not in self._pending_subscribe or channel_id in self._confirmed.values():
                            if self._confirmed.get(symbol) == channel_id:
                                continue
                            raise ValueError("UNEXPECTED_ACK")
                    elif self._confirmed.get(symbol) != channel_id or channel_id not in self._pending_unsubscribe:
                        raise ValueError("UNEXPECTED_ACK")
                for symbol, channel_id in parsed.items():
                    if kind == "subscribed":
                        self._pending_subscribe.pop(symbol, None)
                        self._confirmed[symbol] = channel_id
                    else:
                        self._pending_unsubscribe.pop(channel_id, None)
                        self._confirmed.pop(symbol, None)
                        self._latest.pop(symbol, None)
            self._wake.set()
        except (ValueError, TypeError):
            self._signal_reconnect(connection, epoch)

    def _drop_connection(self, client: object | None) -> None:
        with self._lock:
            if client is None or self._client is not client:
                return
            self._invalidate_tracking_locked()
            ids, callbacks = sorted(self._retiring_ids), self._callbacks
            self._retiring_ids.clear()
            self._callbacks = {}
            self._client = None
            self._generation = None
            self._epoch += 1
        off = getattr(client, "off", None)
        if callable(off):
            for name, callback in callbacks.items():
                try:
                    off(name, callback)
                except Exception:
                    pass
        if ids:
            try:
                self._gateway.stock_push_call(client, "unsubscribe", {"ids": ids})
            except Exception:
                logger.warning("Fubon stock stream unsubscribe failed reason=CLEANUP_FAILED")
        self._gateway.release_stock_push_websocket(client)

    def _count(self, outcome: Outcome) -> None:
        if self._counters is not None:
            self._counters.increment(outcome)
