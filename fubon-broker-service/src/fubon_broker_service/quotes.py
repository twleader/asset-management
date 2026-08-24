from __future__ import annotations

import asyncio
import hashlib
import re
import time
from collections import deque
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from decimal import Decimal
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


class QuoteService:
    MAX_CODES = 100
    MAX_CONCURRENCY = 20
    PER_CALL_TIMEOUT_SECONDS = 5.0
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
        self._inflight: dict[tuple[str, str], asyncio.Future[dict[str, object]]] = {}
        self._call_starts: deque[float] = deque()
        self._circuit_until = 0.0

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
        try:
            values = await asyncio.wait_for(
                asyncio.gather(*(self._read_one(code, purpose) for code in codes)),
                timeout=self.ENDPOINT_TIMEOUT_SECONDS,
            )
        except TimeoutError:
            values = [self._failure(code, "BATCH_TIMEOUT") for code in codes]
        seed = ":".join(codes) + ":" + self._now().astimezone(TW_ZONE).date().isoformat()
        return {
            "batchId": hashlib.sha256(seed.encode("utf-8")).hexdigest()[:24],
            "quotes": values,
        }

    async def _read_one(self, code: str, purpose: str) -> dict[str, object]:
        owner = False
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
            future = self._inflight.get(key)
            if future is None:
                future = asyncio.get_running_loop().create_future()
                self._inflight[key] = future
                owner = True
        if not owner:
            return await asyncio.shield(future)

        try:
            value = await self._fetch_one(code)
            async with self._state_lock:
                if value.get("status") == "SUCCESS":
                    if purpose == "INVENTORY":
                        self._cache[key] = CachedQuote(self._monotonic() + self.CACHE_SECONDS, value)
            future.set_result(value)
            return value
        except asyncio.CancelledError:
            failure = self._failure(code, "BATCH_TIMEOUT")
            if not future.done():
                future.set_result(failure)
            raise
        except SdkCallError:
            failure = self._failure(code, "SESSION_UNAVAILABLE")
            if not future.done():
                future.set_result(failure)
            raise
        except Exception:
            failure = self._failure(code, "QUOTE_FAILED")
            if not future.done():
                future.set_result(failure)
            return failure
        finally:
            async with self._state_lock:
                self._inflight.pop(key, None)

    async def _fetch_one(self, code: str) -> dict[str, object]:
        budget_reason = await self._reserve_budget()
        if budget_reason is not None:
            return self._failure(code, budget_reason)
        async with self._semaphore:
            try:
                response = await asyncio.wait_for(
                    asyncio.to_thread(self._gateway.quote, code),
                    timeout=self.PER_CALL_TIMEOUT_SECONDS,
                )
                quote = self._normalize_quote(code, response)
                return {"stockCode": code, "status": "SUCCESS", "reason": None, "quote": quote}
            except TimeoutError:
                return self._failure(code, "QUOTE_TIMEOUT")
            except SdkCallError as exc:
                if exc.reason == "RATE_LIMITED":
                    await self._open_rate_limit_circuit(exc.retry_after_seconds)
                if exc.misconfigured or exc.auth_invalid:
                    raise
                return self._failure(code, exc.reason)
            except (QuoteError, NumericError) as exc:
                if isinstance(exc, QuoteError) and exc.reason == "RATE_LIMITED":
                    await self._open_rate_limit_circuit(exc.retry_after_seconds)
                return self._failure(code, str(exc))

    async def _open_rate_limit_circuit(self, retry_after_seconds: float | None = None) -> None:
        retry_after = 0.0 if retry_after_seconds is None else min(600.0, max(0.0, retry_after_seconds))
        circuit_seconds = max(self.RATE_LIMIT_CIRCUIT_SECONDS, retry_after)
        async with self._state_lock:
            self._circuit_until = max(
                self._circuit_until, self._monotonic() + circuit_seconds
            )

    async def _reserve_budget(self) -> str | None:
        async with self._state_lock:
            now = self._monotonic()
            if now < self._circuit_until:
                return "RATE_LIMIT_CIRCUIT_OPEN"
            while self._call_starts and now - self._call_starts[0] >= 60.0:
                self._call_starts.popleft()
            if len(self._call_starts) >= self.MAX_CALLS_PER_MINUTE:
                return "RATE_LIMIT_BUDGET_EXHAUSTED"
            self._call_starts.append(now)
            return None

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

        last_updated = raw_field(raw, "lastUpdated")
        if last_updated is not None:
            self._microsecond_instant(last_updated)

        actual_decimal = Decimal(actual_price)
        open_decimal = Decimal(open_price)
        high_decimal = Decimal(high_price)
        low_decimal = Decimal(low_price)
        if low_decimal > min(open_decimal, actual_decimal):
            raise QuoteError("INVALID_OHLC_RELATION")
        if high_decimal < max(open_decimal, actual_decimal) or low_decimal > high_decimal:
            raise QuoteError("INVALID_OHLC_RELATION")

        buy_price = self._book_price(raw_field(raw, "bids"), "INVALID_BID_BOOK")
        sell_price = self._book_price(raw_field(raw, "asks"), "INVALID_ASK_BOOK")
        total = raw_field(raw, "total")
        if total is None:
            raise QuoteError("MISSING_VOLUME")
        volume = exact_integer(raw_field(total, "tradeVolume"), upper=MAX_VOLUME)

        return {
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
    def _book_price(book: object, reason: str) -> str | None:
        if not isinstance(book, list):
            raise QuoteError(reason)
        if not book:
            return None
        normalized: list[str] = []
        for level in book:
            price = raw_field(level, "price")
            if price is None:
                raise QuoteError(reason)
            normalized.append(canonical_decimal(price))
        return normalized[0]

    @staticmethod
    def _failure(code: str, reason: str) -> dict[str, object]:
        return {"stockCode": code, "status": "FAILURE", "reason": reason, "quote": None}
