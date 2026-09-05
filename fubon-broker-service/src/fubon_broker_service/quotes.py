from __future__ import annotations

import asyncio
import hashlib
import re
import threading
import time
from collections import deque
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from decimal import Decimal, ROUND_HALF_UP
from typing import Callable
from zoneinfo import ZoneInfo

from .numeric import MAX_VOLUME, NumericError, canonical_decimal, exact_integer
from .sdk_gateway import SdkCallError, SdkGateway, raw_field


TW_ZONE = ZoneInfo("Asia/Taipei")
_CODE = re.compile(r"^[0-9A-Z]{2,10}$")


class QuoteError(ValueError):
    def __init__(self, reason: str, *, retry_after_seconds: float | None = None) -> None:
        super().__init__(reason)
        self.reason = reason
        self.retry_after_seconds = retry_after_seconds


@dataclass(frozen=True)
class CachedQuote:
    expires_at: float
    value: dict[str, object]


@dataclass
class InflightQuote:
    """One admitted (purpose, stockCode) flight and its native-lifecycle state."""

    future: asyncio.Future[dict[str, object]]
    task: asyncio.Task[None] | None = None
    waiters: int = 0
    dispatch_lock: threading.Lock = field(default_factory=threading.Lock)
    dispatched: bool = False
    cancel_requested: bool = False


class QuoteService:
    MAX_CODES = 100
    MAX_CONCURRENCY = 20
    ENDPOINT_TIMEOUT_SECONDS = 30.0
    CACHE_SECONDS = 30.0
    MAX_CALLS_PER_MINUTE = 240
    RATE_LIMIT_CIRCUIT_SECONDS = 60.0

    def __init__(
        self,
        gateway: SdkGateway,
        now: Callable[[], datetime] | None = None,
        monotonic: Callable[[], float] = time.monotonic,
    ) -> None:
        self._gateway = gateway
        self._now = now or (lambda: datetime.now(UTC))
        self._monotonic = monotonic
        self._state_lock = asyncio.Lock()
        self._semaphore = asyncio.Semaphore(self.MAX_CONCURRENCY)
        self._cache: dict[tuple[str, str], CachedQuote] = {}
        self._inflight: dict[tuple[str, str], InflightQuote] = {}
        self._call_starts: deque[float] = deque()
        self._circuit_until = 0.0
        # This state is read in SdkGateway's worker thread at the final native
        # dispatch gate, so it cannot be guarded by the asyncio cache lock.
        self._budget_lock = threading.Lock()

    @staticmethod
    def normalize_codes(raw_codes: list[str]) -> list[str]:
        if not isinstance(raw_codes, list) or not 1 <= len(raw_codes) <= QuoteService.MAX_CODES:
            raise QuoteError("INVALID_CODE_COUNT")
        normalized: list[str] = []
        seen: set[str] = set()
        for raw in raw_codes:
            if not isinstance(raw, str):
                raise QuoteError("INVALID_CODE")
            code = raw.strip().upper()
            if not _CODE.fullmatch(code):
                raise QuoteError("INVALID_CODE")
            if code in seen:
                raise QuoteError("DUPLICATE_CODE")
            seen.add(code)
            normalized.append(code)
        return normalized

    async def read(self, raw_codes: list[str], purpose: str = "LIVE") -> dict[str, object]:
        codes = self.normalize_codes(raw_codes)
        if purpose not in {"LIVE", "INVENTORY"}:
            raise QuoteError("INVALID_PURPOSE")
        deadline = self._monotonic() + self.ENDPOINT_TIMEOUT_SECONDS
        try:
            values = await asyncio.wait_for(
                asyncio.gather(*(self._read_one(code, purpose, deadline) for code in codes)),
                timeout=max(0.0, deadline - self._monotonic()),
            )
        except TimeoutError:
            values = [self._failure(code, "BATCH_TIMEOUT") for code in codes]
        seed = ":".join(codes) + ":" + self._now().astimezone(TW_ZONE).date().isoformat()
        return {
            "batchId": hashlib.sha256(seed.encode("utf-8")).hexdigest()[:24],
            "quotes": values,
        }

    async def _read_one(
        self, code: str, purpose: str, deadline: float
    ) -> dict[str, object]:
        now_date = self._now().astimezone(TW_ZONE).date().isoformat()
        key = (purpose, code)
        async with self._state_lock:
            cached = self._cache.get(key) if purpose == "INVENTORY" else None
            if cached is not None:
                quote = cached.value.get("quote")
                if (
                    cached.expires_at > self._monotonic()
                    and isinstance(quote, dict)
                    and quote.get("tradingDate") == now_date
                ):
                    return cached.value
                self._cache.pop(key, None)
            flight = self._inflight.get(key)
            if flight is None:
                future = asyncio.get_running_loop().create_future()
                flight = InflightQuote(future)
                self._inflight[key] = flight
                flight.task = asyncio.create_task(
                    self._run_flight(key, code, purpose, deadline, flight),
                    name=f"fubon-quote-{purpose}-{code}",
                )
            flight.waiters += 1

        try:
            return await asyncio.shield(flight.future)
        except asyncio.CancelledError:
            await self._detach_waiter(flight, cancelled=True)
            raise
        else:
            await self._detach_waiter(flight, cancelled=False)

    async def _detach_waiter(self, flight: InflightQuote, *, cancelled: bool) -> None:
        task: asyncio.Task[None] | None = None
        async with self._state_lock:
            flight.waiters -= 1
            if cancelled and flight.waiters == 0 and not flight.future.done():
                # The exact same lock is acquired by the native worker's last
                # dispatch gate.  A cancellation therefore either wins before
                # native I/O, or joins a worker already known to be dispatched.
                with flight.dispatch_lock:
                    if not flight.dispatched:
                        flight.cancel_requested = True
                        task = flight.task
        if task is not None:
            task.cancel()

    async def _run_flight(
        self,
        key: tuple[str, str],
        code: str,
        purpose: str,
        deadline: float,
        flight: InflightQuote,
    ) -> None:
        completion_event: threading.Event | None = None
        deferred_remove = False
        try:
            value = await self._fetch_one(code, deadline, flight)
            if value.get("status") == "SUCCESS" and purpose == "INVENTORY":
                async with self._state_lock:
                    self._cache[key] = CachedQuote(self._monotonic() + self.CACHE_SECONDS, value)
            if not flight.future.done():
                flight.future.set_result(value)
        except asyncio.CancelledError:
            # `_detach_waiter` cancels only a flight which has not claimed native
            # dispatch, so it is safe to remove after producing a local failure.
            if not flight.future.done():
                flight.future.set_result(self._failure(code, "BATCH_TIMEOUT"))
        except SdkCallError as exc:
            completion_event = exc.completion_event
            if exc.reason == "RATE_LIMITED":
                self._open_rate_limit_circuit(exc.retry_after_seconds)
            reason = "SESSION_UNAVAILABLE" if exc.misconfigured or exc.auth_invalid else exc.reason
            if not flight.future.done():
                flight.future.set_result(self._failure(code, reason))
        except Exception:
            if not flight.future.done():
                flight.future.set_result(self._failure(code, "QUOTE_FAILED"))
        finally:
            if completion_event is not None and not completion_event.is_set():
                try:
                    # The SdkGateway event is set only after the timed-out native
                    # worker releases its blocking slot in finally.
                    await asyncio.shield(asyncio.to_thread(completion_event.wait))
                except asyncio.CancelledError:
                    asyncio.create_task(
                        self._remove_after_completion(key, flight, completion_event),
                        name=f"fubon-quote-finalize-{key[0]}-{key[1]}",
                    )
                    deferred_remove = True
            if not deferred_remove:
                await self._remove_flight(key, flight)

    async def _remove_after_completion(
        self,
        key: tuple[str, str],
        flight: InflightQuote,
        completion_event: threading.Event,
    ) -> None:
        await asyncio.shield(asyncio.to_thread(completion_event.wait))
        await self._remove_flight(key, flight)

    async def _remove_flight(self, key: tuple[str, str], flight: InflightQuote) -> None:
        async with self._state_lock:
            if self._inflight.get(key) is flight:
                self._inflight.pop(key, None)

    def _remaining(self, deadline: float) -> float:
        remaining = deadline - self._monotonic()
        if remaining <= 0:
            raise SdkCallError("QUOTE_TIMEOUT")
        return remaining

    async def _fetch_one(
        self, code: str, deadline: float, flight: InflightQuote
    ) -> dict[str, object]:
        acquired = False
        native_task: asyncio.Task[object] | None = None
        try:
            await asyncio.wait_for(self._semaphore.acquire(), timeout=self._remaining(deadline))
            acquired = True
            self._remaining(deadline)
            native_task = asyncio.create_task(
                asyncio.to_thread(
                    self._gateway.quote,
                    code,
                    deadline=deadline,
                    before_dispatch=lambda: self._claim_dispatch(flight, deadline),
                ),
                name=f"fubon-native-quote-{code}",
            )
            try:
                response = await asyncio.wait_for(
                    asyncio.shield(native_task), timeout=self._remaining(deadline)
                )
            except (asyncio.TimeoutError, asyncio.CancelledError):
                raise self._native_timeout(native_task) from None
            quote = self._normalize_quote(code, response)
            return {"stockCode": code, "status": "SUCCESS", "reason": None, "quote": quote}
        except asyncio.TimeoutError:
            raise SdkCallError("QUOTE_TIMEOUT") from None
        except SdkCallError:
            raise
        except (QuoteError, NumericError) as exc:
            if isinstance(exc, QuoteError) and exc.reason == "RATE_LIMITED":
                self._open_rate_limit_circuit(exc.retry_after_seconds)
            return self._failure(code, str(exc))
        finally:
            if acquired:
                self._semaphore.release()

    def _native_timeout(self, native_task: asyncio.Task[object]) -> SdkCallError:
        completion_event = threading.Event()

        def mark_complete(task: asyncio.Task[object]) -> None:
            # The caller deliberately stopped awaiting this late native bridge;
            # consume its terminal exception before allowing flight cleanup.
            try:
                task.exception()
            except BaseException:
                pass
            completion_event.set()

        native_task.add_done_callback(mark_complete)
        return SdkCallError("QUOTE_TIMEOUT", completion_event=completion_event)

    def _claim_dispatch(self, flight: InflightQuote, deadline: float) -> None:
        # Called from SdkGateway's worker after it owns its native slot and has
        # checked session state.  This is the only 240/min debit point.
        self._remaining(deadline)
        with flight.dispatch_lock:
            if flight.cancel_requested:
                raise SdkCallError("QUOTE_CANCELLED")
            self._reserve_dispatch_budget()
            flight.dispatched = True

    def _open_rate_limit_circuit(self, retry_after_seconds: float | None = None) -> None:
        retry_after = 0.0 if retry_after_seconds is None else min(600.0, max(0.0, retry_after_seconds))
        circuit_seconds = max(self.RATE_LIMIT_CIRCUIT_SECONDS, retry_after)
        with self._budget_lock:
            self._circuit_until = max(
                self._circuit_until, self._monotonic() + circuit_seconds
            )

    def _reserve_dispatch_budget(self) -> None:
        with self._budget_lock:
            now = self._monotonic()
            if now < self._circuit_until:
                raise SdkCallError("RATE_LIMIT_CIRCUIT_OPEN")
            while self._call_starts and now - self._call_starts[0] >= 60.0:
                self._call_starts.popleft()
            if len(self._call_starts) >= self.MAX_CALLS_PER_MINUTE:
                raise SdkCallError("RATE_LIMIT_BUDGET_EXHAUSTED")
            self._call_starts.append(now)

    def _normalize_quote(self, requested_code: str, response: object) -> dict[str, object]:
        success = raw_field(response, "is_success")
        if success is False:
            code = raw_field(response, "code") or raw_field(response, "error_code")
            if str(code) == "429":
                raise QuoteError(
                    "RATE_LIMITED", retry_after_seconds=self._retry_after_seconds(response)
                )
            raise QuoteError("QUOTE_PROVIDER_REJECTED")
        raw = raw_field(response, "data") if success is True else response
        if raw is None:
            raise QuoteError("QUOTE_DATA_MISSING")

        symbol = raw_field(raw, "symbol")
        name = raw_field(raw, "name")
        exchange = raw_field(raw, "exchange")
        market = raw_field(raw, "market")
        if symbol != requested_code:
            raise QuoteError("WRONG_SYMBOL")
        if not isinstance(name, str) or not name.strip() or name.strip().upper() == requested_code:
            raise QuoteError("INVALID_STOCK_NAME")
        if (exchange, market) not in {("TWSE", "TSE"), ("TPEx", "OTC")}:
            raise QuoteError("INVALID_MARKET_PAIR")
        is_trial = raw_field(raw, "isTrial")
        if is_trial is True:
            raise QuoteError("TRIAL_QUOTE_REJECTED")
        if is_trial not in (None, False):
            raise QuoteError("INVALID_TRIAL_FLAG")

        if any(raw_field(raw, alias) is not None for alias in ("open", "high", "low")) and any(
            raw_field(raw, official) is None for official in ("openPrice", "highPrice", "lowPrice")
        ):
            raise QuoteError("UNSUPPORTED_OHLC_ALIAS")

        previous_close = canonical_decimal(raw_field(raw, "previousClose"))
        open_price = canonical_decimal(raw_field(raw, "openPrice"))
        high_price = canonical_decimal(raw_field(raw, "highPrice"))
        low_price = canonical_decimal(raw_field(raw, "lowPrice"))
        actual_price, actual_instant = self._actual_trade(raw)
        now = self._now().astimezone(UTC)
        if actual_instant > now + timedelta(seconds=30):
            raise QuoteError("FUTURE_PROVIDER_TIMESTAMP")
        trading_date = actual_instant.astimezone(TW_ZONE).date().isoformat()
        if trading_date != now.astimezone(TW_ZONE).date().isoformat():
            raise QuoteError("STALE_PROVIDER_DATE")

        actual_decimal = Decimal(actual_price)
        open_decimal = Decimal(open_price)
        high_decimal = Decimal(high_price)
        low_decimal = Decimal(low_price)
        if low_decimal > min(open_decimal, actual_decimal):
            raise QuoteError("INVALID_OHLC_RELATION")
        if high_decimal < max(open_decimal, actual_decimal) or low_decimal > high_decimal:
            raise QuoteError("INVALID_OHLC_RELATION")

        # Best bid/ask is optional display metadata.  It must not invalidate a verified
        # actual trade merely because one optional order-book side is malformed.
        buy_price = self._book_price(raw_field(raw, "bids"))
        sell_price = self._book_price(raw_field(raw, "asks"))
        total = raw_field(raw, "total")
        if total is None:
            raise QuoteError("MISSING_VOLUME")
        volume = exact_integer(raw_field(total, "tradeVolume"), upper=MAX_VOLUME)

        normalized = {
            "stockCode": requested_code,
            "stockName": name.strip(),
            "market": "台股",
            "actualPrice": actual_price,
            "previousClose": previous_close,
            "openPrice": open_price,
            "highPrice": high_price,
            "lowPrice": low_price,
            "buyPrice": buy_price,
            "sellPrice": sell_price,
            "volume": volume,
            "updatedAt": actual_instant.isoformat().replace("+00:00", "Z"),
            "tradingDate": trading_date,
            "source": "FUBON_INTRADAY",
            "closed": False,
            "quoteStatus": "LIVE",
        }
        # Five-level data is a separate, optional projection from the exact same raw
        # Fubon response.  A malformed/incomplete book deliberately omits only this
        # projection; the established actual-price contract above remains intact.
        order_book = self._normalized_order_book(raw, now, trading_date)
        if order_book is not None:
            normalized["orderBook"] = order_book
        return normalized

    def _normalized_order_book(
        self, raw: object, now: datetime, trading_date: str
    ) -> dict[str, object] | None:
        try:
            book_updated_at = self._microsecond_instant(raw_field(raw, "lastUpdated"))
            if book_updated_at > now:
                return None
            if book_updated_at.astimezone(TW_ZONE).date().isoformat() != trading_date:
                return None
            bids = self._complete_book_side(raw_field(raw, "bids"))
            asks = self._complete_book_side(raw_field(raw, "asks"))
            if bids is None or asks is None:
                return None
            total = raw_field(raw, "total")
            return {
                "bookUpdatedAt": book_updated_at.isoformat().replace("+00:00", "Z"),
                "averagePrice": self._optional_positive_decimal(raw_field(raw, "avgPrice")),
                "turnoverYi": self._turnover_yi(raw_field(total, "tradeValue")),
                "innerVolumeLots": self._optional_integer(raw_field(total, "tradeVolumeAtBid")),
                "outerVolumeLots": self._optional_integer(raw_field(total, "tradeVolumeAtAsk")),
                "levels": [
                    {
                        "level": index + 1,
                        "bidPrice": bids[index][0],
                        "bidVolumeLots": bids[index][1],
                        "askPrice": asks[index][0],
                        "askVolumeLots": asks[index][1],
                    }
                    for index in range(5)
                ],
            }
        except (QuoteError, NumericError):
            return None

    @staticmethod
    def _complete_book_side(book: object) -> list[tuple[str | None, int | None]] | None:
        if not isinstance(book, list) or len(book) < 5:
            return None
        normalized: list[tuple[str | None, int | None]] = []
        # Decimal equality deliberately collapses textual scale variants such as
        # ``100.0`` and ``100.00``: they are one price level, not two.
        seen_prices: set[Decimal] = set()
        for raw_level in book[:5]:
            raw_price = raw_field(raw_level, "price")
            raw_size = raw_field(raw_level, "size")
            # Fubon emits fixed slots.  An empty slot is valid only as an all-null
            # pair; a half slot cannot be persisted safely.
            if raw_price is None or raw_size is None:
                if raw_price is None and raw_size is None:
                    normalized.append((None, None))
                    continue
                return None
            price = canonical_decimal(raw_price)
            lots = exact_integer(raw_size, upper=MAX_VOLUME)
            decimal_price = Decimal(price)
            if decimal_price in seen_prices:
                return None
            seen_prices.add(decimal_price)
            normalized.append((price, lots))
        return normalized

    @staticmethod
    def _optional_positive_decimal(value: object) -> str | None:
        if value is None:
            return None
        try:
            return canonical_decimal(value)
        except NumericError:
            return None

    @staticmethod
    def _optional_integer(value: object) -> int | None:
        if value is None:
            return None
        try:
            return exact_integer(value, upper=MAX_VOLUME)
        except NumericError:
            return None

    @staticmethod
    def _turnover_yi(value: object) -> str | None:
        if value is None:
            return None
        try:
            raw_value = Decimal(canonical_decimal(value, positive=False))
        except NumericError:
            return None
        return format(
            (raw_value / Decimal("100000000")).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP),
            "f",
        )

    def _actual_trade(self, raw: object) -> tuple[str, datetime]:
        candidates: list[tuple[str, datetime]] = []
        last_trade = raw_field(raw, "lastTrade")
        if last_trade is not None:
            price = raw_field(last_trade, "price")
            timestamp = raw_field(last_trade, "time")
            if price is not None or timestamp is not None:
                if price is None or timestamp is None:
                    raise QuoteError("INCOMPLETE_LAST_TRADE")
                candidates.append((canonical_decimal(price), self._microsecond_instant(timestamp)))
        close_price = raw_field(raw, "closePrice")
        close_time = raw_field(raw, "closeTime")
        if close_price is not None or close_time is not None:
            if close_price is None or close_time is None:
                raise QuoteError("INCOMPLETE_CLOSE_TRADE")
            candidates.append((canonical_decimal(close_price), self._microsecond_instant(close_time)))
        if not candidates:
            raise QuoteError("ACTUAL_TRADE_MISSING")
        first = candidates[0]
        if any(
            Decimal(candidate[0]) != Decimal(first[0]) or candidate[1] != first[1]
            for candidate in candidates[1:]
        ):
            raise QuoteError("CONFLICTING_ACTUAL_TRADE")
        return first

    @staticmethod
    def _microsecond_instant(value: object) -> datetime:
        if isinstance(value, bool):
            raise QuoteError("INVALID_PROVIDER_TIMESTAMP")
        rendered = str(value)
        if not re.fullmatch(r"[1-9][0-9]{15}", rendered):
            raise QuoteError("INVALID_PROVIDER_TIMESTAMP")
        try:
            seconds, microseconds = divmod(int(rendered), 1_000_000)
            return datetime.fromtimestamp(seconds, tz=UTC) + timedelta(microseconds=microseconds)
        except (OverflowError, OSError, ValueError):
            raise QuoteError("INVALID_PROVIDER_TIMESTAMP") from None

    @staticmethod
    def _retry_after_seconds(response: object) -> float | None:
        direct = raw_field(response, "retryAfter") or raw_field(response, "retry_after")
        headers = raw_field(response, "headers")
        if direct is None and isinstance(headers, dict):
            direct = headers.get("retry-after") or headers.get("Retry-After")
        if isinstance(direct, bool) or direct is None:
            return None
        try:
            parsed = float(str(direct).strip())
        except ValueError:
            return None
        return parsed if parsed >= 0 and parsed != float("inf") else None

    @staticmethod
    def _book_price(book: object) -> str | None:
        if not isinstance(book, list):
            return None
        if not book:
            return None
        price = raw_field(book[0], "price")
        if price is None:
            return None
        try:
            return canonical_decimal(price)
        except NumericError:
            return None

    @staticmethod
    def _failure(code: str, reason: str) -> dict[str, object]:
        return {"stockCode": code, "status": "FAILURE", "reason": reason, "quote": None}
