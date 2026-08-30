from __future__ import annotations

import asyncio
import json
import logging
import queue
import threading
import time
from collections.abc import AsyncIterator, Callable
from dataclasses import dataclass
from decimal import Decimal
from typing import Any

from .config import ConfigSnapshot
from .numeric import NumericError, canonical_decimal
from .sdk_gateway import SdkCallError, SdkGateway


logger = logging.getLogger(__name__)

_REQUIRED_FIELDS = frozenset({"symbol", "exchange", "type", "index", "time"})
_MAX_TIME_MICROS = 9_223_372_036_854_775_807


class TaiexIndexStreamError(RuntimeError):
    """A sanitized reason suitable for the internal SSE endpoint."""

    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


@dataclass(frozen=True)
class TaiexIndexEvent:
    symbol: str
    exchange: str
    type: str
    index: str
    time: int

    def sse_bytes(self) -> bytes:
        payload = json.dumps(
            {
                "symbol": self.symbol,
                "exchange": self.exchange,
                "type": self.type,
                "index": self.index,
                "time": self.time,
            },
            ensure_ascii=True,
            separators=(",", ":"),
        )
        return f"event: taiex-index\nid: {self.time}\ndata: {payload}\n\n".encode("utf-8")


def normalize_vendor_message(message: object, configured_symbol: str) -> TaiexIndexEvent | None:
    """Reject raw/ambiguous SDK payloads before they can reach an SSE subscriber."""

    envelope = _decode_message(message)
    if not isinstance(envelope, dict):
        return None
    if envelope.get("event") != "data" or envelope.get("channel") != "indices":
        return None
    data = envelope.get("data")
    if not isinstance(data, dict) or set(data) != _REQUIRED_FIELDS:
        return None
    symbol = data.get("symbol")
    exchange = data.get("exchange")
    event_type = data.get("type")
    if symbol != configured_symbol or exchange != "TWSE" or event_type != "INDEX":
        return None
    try:
        index = _canonical_vendor_decimal(data.get("index"))
        micros = _positive_microseconds(data.get("time"))
    except (NumericError, ValueError, TypeError):
        return None
    return TaiexIndexEvent(symbol, exchange, event_type, index, micros)


def _decode_message(message: object) -> dict[str, object] | None:
    if isinstance(message, str):
        try:
            decoded = json.loads(
                message,
                parse_float=str,
                parse_int=str,
                object_pairs_hook=_reject_duplicate_pairs,
            )
        except (TypeError, ValueError, json.JSONDecodeError):
            return None
        return decoded if isinstance(decoded, dict) else None
    return message if isinstance(message, dict) else None


def _reject_duplicate_pairs(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate field")
        result[key] = value
    return result


def _canonical_vendor_decimal(value: object) -> str:
    # A float supplied by an SDK object has already lost its original provider representation.
    if isinstance(value, float) or isinstance(value, bool) or value is None:
        raise NumericError("INVALID_DECIMAL")
    if isinstance(value, Decimal):
        raw = str(value)
    elif isinstance(value, (str, int)):
        raw = str(value)
    else:
        raise NumericError("INVALID_DECIMAL")
    if not raw or raw.startswith("+") or raw.startswith("0") and len(raw) > 1 and raw[1] != ".":
        raise NumericError("NON_CANONICAL_DECIMAL")
    # canonical_decimal additionally rejects exponent/non-finite/non-positive and bounds precision/scale.
    canonical = canonical_decimal(raw, positive=True)
    if canonical != raw:
        raise NumericError("NON_CANONICAL_DECIMAL")
    return canonical


def _positive_microseconds(value: object) -> int:
    if isinstance(value, bool):
        raise ValueError("INVALID_TIME")
    if isinstance(value, int):
        text = str(value)
    elif isinstance(value, str):
        text = value
    else:
        raise ValueError("INVALID_TIME")
    if not text or not text.isascii() or not text.isdecimal() or text.startswith("0"):
        raise ValueError("INVALID_TIME")
    parsed = int(text)
    if parsed <= 0 or parsed > _MAX_TIME_MICROS:
        raise ValueError("INVALID_TIME")
    return parsed


class TaiexIndexStream:
    """One read-only official indices subscription with in-memory SSE fan-out only."""

    _BACKOFF_SECONDS = (0.25, 0.5, 1.0, 2.0, 5.0)
    _QUEUE_SIZE = 16

    def __init__(
        self,
        gateway: SdkGateway,
        *,
        sleeper: Callable[[float], None] = time.sleep,
        thread_factory: Callable[..., threading.Thread] = threading.Thread,
    ) -> None:
        self._gateway = gateway
        self._sleeper = sleeper
        self._thread_factory = thread_factory
        self._lock = threading.RLock()
        self._stop = threading.Event()
        self._reconnect = threading.Event()
        self._worker: threading.Thread | None = None
        self._websocket: object | None = None
        self._bound_client: object | None = None
        self._symbol: str | None = None
        self._verified_symbol: str | None = None
        self._latest: TaiexIndexEvent | None = None
        self._subscribers: set[queue.Queue[TaiexIndexEvent]] = set()

    @staticmethod
    def configured(config: ConfigSnapshot) -> bool:
        return bool(
            config.enabled
            and config.state == "READY"
            and config.taiex_index_stream_enabled
            and config.taiex_index_symbol
            and config.taiex_index_stream_reason is None
        )

    @staticmethod
    def configuration_reason(config: ConfigSnapshot) -> str:
        if not config.enabled:
            return "DISABLED"
        if config.state != "READY":
            return config.reason or "MISCONFIGURED"
        if config.taiex_index_stream_reason:
            return config.taiex_index_stream_reason
        if not config.taiex_index_stream_enabled:
            return "TAIEX_INDEX_STREAM_DISABLED"
        if not config.taiex_index_symbol:
            return "INVALID_TAIEX_INDEX_SYMBOL"
        return "MISCONFIGURED"

    def ensure_started(self, config: ConfigSnapshot) -> None:
        if not self.configured(config):
            raise TaiexIndexStreamError(self.configuration_reason(config))
        symbol = config.taiex_index_symbol
        assert symbol is not None
        with self._lock:
            if self._worker is not None and self._worker.is_alive():
                if self._symbol == symbol:
                    return
                # Configuration is process-static in Compose.  If a caller somehow supplies a
                # different valid symbol while the first one is live, fail closed rather than
                # accidentally holding two official subscriptions in the same process.
                raise TaiexIndexStreamError("TAIEX_INDEX_SYMBOL_CHANGED")
            verification_needed = self._verified_symbol != symbol
        if verification_needed:
            try:
                self._gateway.verify_taiex_index_symbol(symbol)
            except SdkCallError as exc:
                raise TaiexIndexStreamError(exc.reason) from None
            with self._lock:
                self._verified_symbol = symbol
        with self._lock:
            self._stop.clear()
            self._reconnect.clear()
            self._symbol = symbol
            worker = self._thread_factory(target=self._run, name="fubon-taiex-index-stream", daemon=True)
            self._worker = worker
            worker.start()

    async def events(self) -> AsyncIterator[bytes]:
        subscriber: queue.Queue[TaiexIndexEvent] = queue.Queue(maxsize=self._QUEUE_SIZE)
        with self._lock:
            latest = self._latest
            if latest is not None:
                subscriber.put_nowait(latest)
            self._subscribers.add(subscriber)
        try:
            while not self._stop.is_set():
                try:
                    event = subscriber.get_nowait()
                except queue.Empty:
                    await asyncio.sleep(0.1)
                    continue
                yield event.sse_bytes()
        finally:
            with self._lock:
                self._subscribers.discard(subscriber)

    def shutdown(self) -> None:
        self._stop.set()
        self._reconnect.set()
        with self._lock:
            client = self._websocket
            worker = self._worker
            self._subscribers.clear()
        self._unbind_callbacks(client)
        self._disconnect(client)
        if worker is not None and worker.is_alive():
            worker.join(timeout=2.0)
        with self._lock:
            if self._worker is worker:
                self._worker = None
            self._websocket = None
            self._verified_symbol = None

    def _run(self) -> None:
        backoff_index = 0
        while not self._stop.is_set():
            client: object | None = None
            try:
                client = self._gateway.realtime_stock_websocket()
                generation = getattr(self._gateway, "session_generation", None)
                with self._lock:
                    self._websocket = client
                    symbol = self._symbol
                if symbol is None:
                    return
                self._bind_callbacks(client)
                self._reconnect.clear()
                connect = getattr(client, "connect", None)
                subscribe = getattr(client, "subscribe", None)
                if not callable(connect) or not callable(subscribe):
                    raise SdkCallError("INDICES_CLIENT_UNAVAILABLE", misconfigured=True)
                connect()
                subscribe({"channel": "indices", "symbol": symbol})
                backoff_index = 0
                while not self._reconnect.wait(timeout=0.1):
                    if generation != getattr(self._gateway, "session_generation", None):
                        break
            except SdkCallError:
                logger.warning("Fubon indices stream unavailable reason=SESSION_UNAVAILABLE")
            except Exception:
                logger.warning("Fubon indices stream unavailable reason=STREAM_CONNECT_FAILED")
            finally:
                self._unbind_callbacks(client)
                self._disconnect(client)
                with self._lock:
                    if self._websocket is client:
                        self._websocket = None
                    self._latest = None
                    for subscriber in self._subscribers:
                        while True:
                            try:
                                subscriber.get_nowait()
                            except queue.Empty:
                                break
            if self._stop.is_set():
                return
            delay = self._BACKOFF_SECONDS[min(backoff_index, len(self._BACKOFF_SECONDS) - 1)]
            backoff_index += 1
            self._sleep_interruptibly(delay)

    def _bind_callbacks(self, client: object) -> None:
        with self._lock:
            if self._bound_client is client:
                return
        on = getattr(client, "on", None)
        if not callable(on):
            raise SdkCallError("INDICES_CLIENT_UNAVAILABLE", misconfigured=True)
        on("connect", self._on_connect)
        on("disconnect", self._on_disconnect)
        on("error", self._on_error)
        on("message", self._on_message)
        with self._lock:
            self._bound_client = client

    def _unbind_callbacks(self, client: object | None) -> None:
        if client is None:
            return
        with self._lock:
            if self._bound_client is not client:
                return
        off = getattr(client, "off", None)
        if not callable(off):
            # Some SDK versions only expose `on`.  Keep the binding marker in that case so a
            # reconnect on the same client never adds duplicate callbacks; process shutdown still
            # disconnects it and the owning SDK session is then cleaned up by SdkGateway.
            return
        # Do not use an event-only off(name): this SDK client is shared with the existing realtime
        # session, so a broad unsubscribe could erase another component's callback.  If any exact
        # callback removal is unsupported, retain the marker for the same no-duplicate reason.
        for name, callback in (
            ("connect", self._on_connect),
            ("disconnect", self._on_disconnect),
            ("error", self._on_error),
            ("message", self._on_message),
        ):
            try:
                off(name, callback)
            except Exception:
                return
        with self._lock:
            if self._bound_client is client:
                self._bound_client = None

    def _on_connect(self, *_args: Any, **_kwargs: Any) -> None:
        # The worker performs the matching subscribe immediately after connect().
        return None

    def _on_disconnect(self, *_args: Any, **_kwargs: Any) -> None:
        self._reconnect.set()

    def _on_error(self, *_args: Any, **_kwargs: Any) -> None:
        self._reconnect.set()

    def _on_message(self, message: object, *_args: Any, **_kwargs: Any) -> None:
        with self._lock:
            symbol = self._symbol
        if symbol is None:
            return
        event = normalize_vendor_message(message, symbol)
        if event is None:
            return
        with self._lock:
            self._latest = event
            for subscriber in tuple(self._subscribers):
                try:
                    subscriber.put_nowait(event)
                except queue.Full:
                    # A slow consumer is never allowed to block the official SDK callback.
                    continue

    def _sleep_interruptibly(self, seconds: float) -> None:
        # The injected sleeper keeps deterministic tests instant; real shutdown is still bounded.
        if self._sleeper is time.sleep:
            self._stop.wait(seconds)
        else:
            self._sleeper(seconds)

    @staticmethod
    def _disconnect(client: object | None) -> None:
        disconnect = getattr(client, "disconnect", None) if client is not None else None
        if callable(disconnect):
            try:
                disconnect()
            except Exception:
                return None
