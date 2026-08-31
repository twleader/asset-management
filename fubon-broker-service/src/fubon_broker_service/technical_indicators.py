"""Strict, fixed Fubon technical-indicator adapter.

The internal route has no period/date/timeframe selector.  This module owns the
immutable 17-profile manifest and produces the versioned document consumed by
the Java scheduler.  The private legacy helper preserves the frozen Task398
fixture seam only; it is not reachable from HTTP.
"""
from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timedelta
from decimal import Decimal
import hashlib
import json
import time
from uuid import uuid4

from .normalization import TAIPEI, canonical_number, instant, observed_at, stock_code, strict_iso_date, utc_now
from .sdk_gateway import SdkCallError, SdkGateway


@dataclass(frozen=True)
class TechnicalProfile:
    profile_id: str
    kind: str
    timeframe: str
    parameters: dict[str, object]
    payload_fields: tuple[str, ...]


def _profile(profile_id: str, kind: str, timeframe: str, parameters: dict[str, object],
             payload_fields: tuple[str, ...]) -> TechnicalProfile:
    return TechnicalProfile(profile_id, kind, timeframe, parameters, payload_fields)


# Fixed manifest order is a cross-service protocol, persistence identity and cache manifest.
PROFILES: tuple[TechnicalProfile, ...] = (
    _profile("sma_d_5", "sma", "D", {"timeframe": "D", "period": 5}, ("sma",)),
    _profile("sma_d_10", "sma", "D", {"timeframe": "D", "period": 10}, ("sma",)),
    _profile("sma_d_20", "sma", "D", {"timeframe": "D", "period": 20}, ("sma",)),
    _profile("sma_d_60", "sma", "D", {"timeframe": "D", "period": 60}, ("sma",)),
    _profile("sma_d_240", "sma", "D", {"timeframe": "D", "period": 240}, ("sma",)),
    _profile("rsi_d_5", "rsi", "D", {"timeframe": "D", "period": 5}, ("rsi",)),
    _profile("rsi_d_10", "rsi", "D", {"timeframe": "D", "period": 10}, ("rsi",)),
    _profile("kdj_d_9_3_3", "kdj", "D", {"timeframe": "D", "rPeriod": 9, "kPeriod": 3, "dPeriod": 3}, ("k", "d", "j")),
    _profile("macd_d_12_26_9", "macd", "D", {"timeframe": "D", "fast": 12, "slow": 26, "signal": 9}, ("macdLine", "signalLine")),
    _profile("bb_d_20", "bb", "D", {"timeframe": "D", "period": 20}, ("upper", "middle", "lower")),
    _profile("sma_w_5", "sma", "W", {"timeframe": "W", "period": 5}, ("sma",)),
    _profile("sma_w_10", "sma", "W", {"timeframe": "W", "period": 10}, ("sma",)),
    _profile("sma_w_20", "sma", "W", {"timeframe": "W", "period": 20}, ("sma",)),
    _profile("rsi_w_5", "rsi", "W", {"timeframe": "W", "period": 5}, ("rsi",)),
    _profile("rsi_w_10", "rsi", "W", {"timeframe": "W", "period": 10}, ("rsi",)),
    _profile("kdj_w_9_3_3", "kdj", "W", {"timeframe": "W", "rPeriod": 9, "kPeriod": 3, "dPeriod": 3}, ("k", "d", "j")),
    _profile("macd_w_12_26_9", "macd", "W", {"timeframe": "W", "fast": 12, "slow": 26, "signal": 9}, ("macdLine", "signalLine")),
)
PROFILE_BY_ID = {profile.profile_id: profile for profile in PROFILES}

# Frozen Task398 constants: v1 must never be derived from the v2 profile manifest.
PARAMETERS = {
    "kdj": {"timeframe": "D", "rPeriod": 9, "kPeriod": 3, "dPeriod": 3},
    "macd": {"timeframe": "D", "fast": 12, "slow": 26, "signal": 9},
    "bb": {"timeframe": "D", "period": 20},
}
PAYLOAD_FIELDS = {"kdj": ("k", "d", "j"), "macd": ("macdLine", "signalLine"),
                  "bb": ("upper", "middle", "lower")}


class TechnicalIndicatorError(ValueError):
    def __init__(self, reason: str, *, request_error: bool = False) -> None:
        super().__init__(reason)
        self.reason, self.request_error = reason, request_error


def technical_fact_canonical_bytes(profile_id: str, source_date: str,
                                   parameters: dict[str, object], payload: dict[str, str]) -> bytes:
    """Return Task408's cross-service technical-fact identity bytes.

    The HTTP aggregate deliberately does not expose a vendor-trusted content
    hash: the external writer recomputes it before persistence.  Keeping the
    byte grammar here nevertheless lets the normalizer prove that its exact
    parameters/payload representation remains compatible with the two Java
    persistence readers.  This must stay byte-for-byte aligned with
    ``FubonCanonicalHash`` and ``FubonTechnicalCanonicalHash``.
    """
    if (not isinstance(profile_id, str) or not profile_id or "\n" in profile_id
            or not isinstance(source_date, str) or not source_date or "\n" in source_date
            or not isinstance(parameters, dict) or not isinstance(payload, dict)):
        raise ValueError("TECHNICAL_SCHEMA_INVALID")
    try:
        canonical_parameters = json.dumps(parameters, ensure_ascii=False, allow_nan=False,
                                          separators=(",", ":"), sort_keys=True)
        canonical_payload = json.dumps(payload, ensure_ascii=False, allow_nan=False,
                                       separators=(",", ":"), sort_keys=True)
    except (TypeError, ValueError) as exc:
        raise ValueError("TECHNICAL_SCHEMA_INVALID") from exc
    return ("FUBON_TECHNICAL_FACT_V1\n" + profile_id + "\n" + source_date + "\n"
            + canonical_parameters + "\n" + canonical_payload).encode("utf-8")


def technical_fact_sha256(profile_id: str, source_date: str,
                          parameters: dict[str, object], payload: dict[str, str]) -> str:
    """Return the SHA-256 of :func:`technical_fact_canonical_bytes`."""
    return hashlib.sha256(technical_fact_canonical_bytes(
        profile_id, source_date, parameters, payload)).hexdigest()


def _canonical_period(value: object, expected: int) -> int:
    """The official SMA/RSI dual representation is the sole wire coercion."""
    if type(value) is int and value == expected:
        return value
    if isinstance(value, str) and value == str(expected) and value.isascii() and value.isdigit() and not value.startswith("0"):
        return expected
    raise ValueError("TECHNICAL_SCHEMA_INVALID")


def _unavailable(profile: TechnicalProfile, reason: str, observed: datetime) -> dict[str, object]:
    """Return a terminal profile result observed at the failed SDK return.

    A failed request still has an immutable observation time: it is the point
    at which this aggregate learned that the profile was unavailable.  Keeping
    it on the wire makes every profile record structurally identical and avoids
    making Java invent a timestamp when it persists the partial history.
    """
    mapped = reason if reason in {"RATE_LIMITED", "HISTORY_BUDGET_EXHAUSTED"} else "UPSTREAM_UNAVAILABLE"
    return {"profileId": profile.profile_id, "status": "UNAVAILABLE", "reason": mapped,
            "parameters": dict(profile.parameters), "observedAt": observed_at(observed), "history": []}


class TechnicalIndicatorService:
    """Fixed 420-day aggregate; no caller-controlled vendor method or parameters."""

    def __init__(self, gateway: SdkGateway, *, now: Callable[[], datetime] = utc_now,
                 monotonic: Callable[[], float] = time.monotonic) -> None:
        self._gateway, self._now, self._monotonic = gateway, now, monotonic

    def read(self, symbol: str, start_date: str | None = None, end_date: str | None = None) -> dict[str, object]:
        # Optional dates retain a private old-test seam. The HTTP route never passes them.
        if start_date is not None or end_date is not None:
            if start_date is None or end_date is None:
                raise TechnicalIndicatorError("INVALID_REQUEST", request_error=True)
            return self._legacy_read(symbol, start_date, end_date)
        started = instant(self._now())
        # It identifies this aggregate observation, not the moment the loop
        # happens to finish.  A retry must therefore use a fresh UUID.
        capture_id = str(uuid4())
        query_date = started.astimezone(TAIPEI).date()
        try:
            stock_code(symbol)
        except ValueError:
            raise TechnicalIndicatorError("INVALID_REQUEST", request_error=True) from None
        query_from = (query_date - timedelta(days=420)).isoformat()
        query_to = query_date.isoformat()
        deadline = self._monotonic() + 60.0
        profiles: list[dict[str, object]] = []
        stop_reason: str | None = None
        for profile in PROFILES:
            if instant(self._now()).astimezone(TAIPEI).date() != query_date:
                raise TechnicalIndicatorError("STALE_QUERY")
            if stop_reason is not None:
                profiles.append(_unavailable(profile, stop_reason, instant(self._now())))
                continue
            try:
                source = self._gateway.read_technical_indicator(
                    profile.kind, symbol, query_from, query_to, timeframe=profile.timeframe,
                    parameters={key: value for key, value in profile.parameters.items() if key != "timeframe"},
                    deadline=deadline,
                )
            except SdkCallError as exc:
                if exc.misconfigured:
                    raise
                # A deadline exhaustion is terminal for this aggregate.  Do
                # not start a later profile after its shared 60-second window
                # has expired (the wire normalizer deliberately exposes it as
                # UPSTREAM_UNAVAILABLE rather than the internal timeout name).
                if exc.reason in {"RATE_LIMITED", "HISTORY_BUDGET_EXHAUSTED"} or (
                    exc.reason == "MARKETDATA_TIMEOUT" and self._monotonic() >= deadline
                ):
                    stop_reason = exc.reason
                profiles.append(_unavailable(profile, exc.reason, instant(self._now())))
                continue
            observed = instant(self._now())
            try:
                profiles.append(self._normalize_profile(profile, source, symbol, query_from, query_to, observed))
            except ValueError:
                profiles.append({"profileId": profile.profile_id, "status": "SCHEMA_INVALID",
                                 "reason": "TECHNICAL_SCHEMA_INVALID", "parameters": dict(profile.parameters),
                                 "observedAt": observed_at(observed), "history": []})
        finished = instant(self._now())
        if finished < started or finished.astimezone(TAIPEI).date() != query_date:
            raise TechnicalIndicatorError("STALE_QUERY")
        return {"schemaVersion": 2, "captureId": capture_id, "symbol": symbol, "market": "台股",
                "provider": "FUBON_SDK", "queryFrom": query_from, "queryTo": query_to, "profiles": profiles}

    @staticmethod
    def _normalize_profile(profile: TechnicalProfile, source: object, symbol: str, start: str, end: str,
                           observed: datetime) -> dict[str, object]:
        if not isinstance(source, dict):
            raise ValueError("TECHNICAL_SCHEMA_INVALID")
        required = {"symbol", "from", "to", "timeframe", *profile.parameters.keys(), "data"}
        if set(source) != required or source.get("symbol") != symbol or source.get("from") != start or source.get("to") != end:
            raise ValueError("TECHNICAL_SCHEMA_INVALID")
        if source.get("timeframe") != profile.timeframe:
            raise ValueError("TECHNICAL_SCHEMA_INVALID")
        for name, expected in profile.parameters.items():
            if name == "timeframe":
                continue
            if profile.kind in {"sma", "rsi"} and name == "period":
                _canonical_period(source.get(name), int(expected))
            elif type(source.get(name)) is not int or source.get(name) != expected:
                raise ValueError("TECHNICAL_SCHEMA_INVALID")
        rows = source.get("data")
        if not isinstance(rows, list) or len(rows) > 421:
            raise ValueError("TECHNICAL_SCHEMA_INVALID")
        if not rows:
            return {"profileId": profile.profile_id, "status": "NO_DATA", "reason": "NO_DATA",
                    "parameters": dict(profile.parameters), "observedAt": observed_at(observed), "history": []}
        seen: set[str] = set()
        history: list[dict[str, object]] = []
        for row in rows:
            if not isinstance(row, dict) or set(row) != {"date", *profile.payload_fields}:
                raise ValueError("TECHNICAL_SCHEMA_INVALID")
            source_date = strict_iso_date(row.get("date")).isoformat()
            if source_date < start or source_date > end or source_date in seen:
                raise ValueError("TECHNICAL_SCHEMA_INVALID")
            seen.add(source_date)
            payload = {field: canonical_number(row.get(field), precision=38, scale=18) for field in profile.payload_fields}
            if profile.kind == "bb" and not Decimal(payload["upper"]) >= Decimal(payload["middle"]) >= Decimal(payload["lower"]):
                raise ValueError("TECHNICAL_SCHEMA_INVALID")
            history.append({"sourceDate": source_date, "sourceTimestamp": None, "payload": payload})
        history.sort(key=lambda row: str(row["sourceDate"]))
        return {"profileId": profile.profile_id, "status": "AVAILABLE", "reason": None,
                "parameters": dict(profile.parameters), "observedAt": observed_at(observed), "history": history}

    # Kept out of HTTP so old v1 cache tests can exercise a frozen source fixture.
    def _legacy_read(self, symbol: str, start_date: str, end_date: str) -> dict[str, object]:
        started = instant(self._now())
        query_date = started.astimezone(TAIPEI).date()
        try:
            stock_code(symbol)
            start, end = strict_iso_date(start_date), strict_iso_date(end_date)
            if start != query_date - timedelta(days=120) or end != query_date:
                raise ValueError("INVALID_DATE_RANGE")
        except ValueError:
            raise TechnicalIndicatorError("INVALID_REQUEST", request_error=True) from None
        groups: dict[str, object] = {}
        stop_reason: str | None = None
        deadline = self._monotonic() + 25.0
        for kind in PARAMETERS:
            if stop_reason is not None:
                groups[kind] = self._legacy_group(kind, "UNAVAILABLE", stop_reason)
                continue
            try:
                source = self._gateway.read_technical_indicator(kind, symbol, start_date, end_date, deadline=deadline)
            except SdkCallError as exc:
                if exc.misconfigured or exc.auth_invalid or exc.reason == "DISABLED":
                    raise
                if exc.reason in {"RATE_LIMITED", "HISTORY_BUDGET_EXHAUSTED"} or (
                    exc.reason == "MARKETDATA_TIMEOUT" and self._monotonic() >= deadline
                ):
                    stop_reason = exc.reason
                groups[kind] = self._legacy_group(kind, "UNAVAILABLE", exc.reason)
                continue
            groups[kind] = self._legacy_normalize(kind, source, symbol, start_date, end_date)
        return {"symbol": symbol, "market": "台股", "provider": "FUBON_SDK", "queryFrom": start_date,
                "queryTo": end_date, "observedAt": observed_at(instant(self._now())), **groups}

    @staticmethod
    def _legacy_group(kind: str, status: str, reason: str | None = None, source_date: str | None = None,
                      payload: dict[str, str] | None = None) -> dict[str, object]:
        return {"status": status, "reason": reason, "parameters": dict(PARAMETERS[kind]),
                "sourceDate": source_date, "sourceTimestamp": None, "payload": payload}

    @classmethod
    def _legacy_normalize(cls, kind: str, source: object, symbol: str, start: str, end: str) -> dict[str, object]:
        try:
            if not isinstance(source, dict) or source.get("symbol") != symbol or source.get("from") != start or source.get("to") != end:
                raise ValueError("TECHNICAL_SCHEMA_INVALID")
            for name, expected in PARAMETERS[kind].items():
                actual = source.get(name)
                # Task398's frozen BB fixture permitted the official canonical
                # string period.  It is deliberately a legacy-only seam and
                # must not be inferred from the v2 profile manifest.
                valid = actual == expected if name == "timeframe" else (
                    kind == "bb" and name == "period" and actual == str(expected)
                ) or (type(actual) is int and actual == expected)
                if not valid:
                    raise ValueError("TECHNICAL_SCHEMA_INVALID")
            rows = source.get("data")
            if not isinstance(rows, list):
                raise ValueError("TECHNICAL_SCHEMA_INVALID")
            if not rows:
                return cls._legacy_group(kind, "NO_DATA", "NO_DATA")
            dated: dict[str, dict[str, object]] = {}
            for row in rows:
                if not isinstance(row, dict):
                    raise ValueError("TECHNICAL_SCHEMA_INVALID")
                source_date = strict_iso_date(row.get("date")).isoformat()
                if source_date < start or source_date > end or source_date in dated:
                    raise ValueError("TECHNICAL_SCHEMA_INVALID")
                dated[source_date] = row
            latest = max(dated)
            payload = {name: canonical_number(dated[latest].get(name), precision=38, scale=18)
                       for name in PAYLOAD_FIELDS[kind]}
            if kind == "bb" and not Decimal(payload["upper"]) >= Decimal(payload["middle"]) >= Decimal(payload["lower"]):
                raise ValueError("TECHNICAL_SCHEMA_INVALID")
            return cls._legacy_group(kind, "AVAILABLE", source_date=latest, payload=payload)
        except (ValueError, TypeError):
            return cls._legacy_group(kind, "SCHEMA_INVALID", "TECHNICAL_SCHEMA_INVALID")
