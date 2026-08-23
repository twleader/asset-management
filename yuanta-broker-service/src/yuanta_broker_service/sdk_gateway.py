from __future__ import annotations

import hashlib
import hmac
import logging
import queue
import re
import threading
from dataclasses import dataclass, field
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Callable, Protocol

from .config import ConfigSnapshot


logger = logging.getLogger(__name__)

MAX_QUANTITY = 9_223_372_036_854_775_807
_CANONICAL_DECIMAL = re.compile(r"^-?(0|[1-9][0-9]*)(?:\.([0-9]+))?$")

# Import boundary regression guard: the real implementation below must only
# ever reference query/subscribe symbols. See
# tests/test_sdk_gateway_import_boundary.py, which statically scans this
# file's source for anything resembling an order-placement symbol.


class FieldValidationError(ValueError):
    """Raised when a single response field/row fails decimal/integer/shape
    validation. Callers catch this per-row and mark that entry as an error
    instead of silently coercing to zero or dropping the field (Task
    364.10)."""


class SdkCallError(RuntimeError):
    def __init__(self, reason: str, *, misconfigured: bool = False) -> None:
        super().__init__(reason)
        self.reason = reason
        # True only for failures that mean the *component/account setup itself*
        # is broken (DLL load failure, account-selector mismatch) and therefore
        # downgrade the global configState. Login failures and market-data
        # connection failures are transient/retryable capabilities and must
        # NOT set this (Task 364.4/364.6).
        self.misconfigured = misconfigured


def canonical_decimal(value: object, *, sign: str = "positive") -> str:
    """sign: 'positive' (>0), 'non_negative' (>=0), or 'any' (no sign bound,
    just finite and canonical)."""
    if isinstance(value, bool) or value is None:
        raise FieldValidationError("INVALID_DECIMAL")
    source = str(value)
    if "e" in source.lower():
        raise FieldValidationError("NON_CANONICAL_DECIMAL")
    try:
        decimal_value = Decimal(source)
    except (InvalidOperation, ValueError):
        raise FieldValidationError("INVALID_DECIMAL") from None
    if not decimal_value.is_finite():
        raise FieldValidationError("NON_FINITE_DECIMAL")
    if sign == "positive" and decimal_value <= 0:
        raise FieldValidationError("NON_POSITIVE_DECIMAL")
    if sign == "non_negative" and decimal_value < 0:
        raise FieldValidationError("NEGATIVE_DECIMAL")

    rendered = format(decimal_value, "f")
    if rendered.startswith("+"):
        rendered = rendered[1:]
    match = _CANONICAL_DECIMAL.fullmatch(rendered)
    if not match:
        raise FieldValidationError("NON_CANONICAL_DECIMAL")
    scale = len(match.group(2) or "")
    digits = rendered.replace("-", "").replace(".", "").lstrip("0")
    precision = len(digits) if digits else 1
    if precision > 20:
        raise FieldValidationError("DECIMAL_PRECISION_EXCEEDED")
    if scale > 10:
        raise FieldValidationError("DECIMAL_SCALE_EXCEEDED")
    return rendered


def exact_integer(value: object, *, upper: int = MAX_QUANTITY) -> int:
    if isinstance(value, bool) or value is None:
        raise FieldValidationError("INVALID_INTEGER")
    if isinstance(value, int):
        parsed = value
    elif isinstance(value, str) and re.fullmatch(r"0|[1-9][0-9]*", value):
        parsed = int(value)
    else:
        raise FieldValidationError("INVALID_INTEGER")
    if parsed < 0 or parsed > upper:
        raise FieldValidationError("INTEGER_RANGE_EXCEEDED")
    return parsed


def raw_field(value: object, name: str) -> object | None:
    if isinstance(value, dict):
        return value.get(name)
    return getattr(value, name, None)


@dataclass(frozen=True)
class LoginResult:
    is_success: bool
    accounts: list[object] = field(default_factory=list)


@dataclass(frozen=True)
class SelectedAccount:
    raw: object
    branch_no: str
    account_number: str
    category: str


@dataclass(frozen=True)
class RowSpec:
    identity: tuple[str, ...] = ()
    passthrough: tuple[str, ...] = ()
    decimals: dict[str, str] = field(default_factory=dict)
    integers: dict[str, int] = field(default_factory=dict)


def normalize_row(raw: object, spec: RowSpec, extra: dict[str, object] | None = None) -> dict[str, object]:
    """Maps one raw response row/object into this service's normalized wire
    schema. A failure on any single field marks *that entry* with
    status=ERROR/reason and never silently zeroes or omits the field
    (Task 364.10); it never aborts sibling rows in the same batch."""
    out: dict[str, object] = dict(extra or {})
    if not isinstance(raw, dict):
        out["status"] = "ERROR"
        out["reason"] = "INVALID_ROW_SHAPE"
        return out
    try:
        for field_name in spec.identity:
            value = raw.get(field_name)
            if not isinstance(value, str) or not value:
                raise FieldValidationError(f"MISSING_{field_name.upper()}")
            out[field_name] = value
        for field_name in spec.passthrough:
            value = raw.get(field_name)
            if not isinstance(value, str) or not value:
                raise FieldValidationError(f"MISSING_{field_name.upper()}")
            out[field_name] = value
        for field_name, upper in spec.integers.items():
            out[field_name] = exact_integer(raw.get(field_name), upper=upper)
        for field_name, sign in spec.decimals.items():
            out[field_name] = canonical_decimal(raw.get(field_name), sign=sign)
        out["status"] = "SUCCESS"
        out["reason"] = None
        return out
    except FieldValidationError as exc:
        out["status"] = "ERROR"
        out["reason"] = str(exc)
        return out


def normalize_code(raw_code: str) -> str:
    if not isinstance(raw_code, str):
        raise FieldValidationError("INVALID_CODE")
    code = raw_code.strip().upper()
    if not re.fullmatch(r"[0-9A-Z]{1,10}", code):
        raise FieldValidationError("INVALID_CODE")
    return code


def normalize_codes(raw_codes: list[str], *, max_codes: int = 100) -> list[str]:
    if not isinstance(raw_codes, list) or not raw_codes:
        raise FieldValidationError("INVALID_CODE_COUNT")
    normalized: list[str] = []
    seen: set[str] = set()
    for raw_code in raw_codes:
        code = normalize_code(raw_code)
        if code not in seen:
            seen.add(code)
            normalized.append(code)
    if not 1 <= len(normalized) <= max_codes:
        raise FieldValidationError("INVALID_CODE_COUNT")
    return normalized


def _as_list(value: object) -> list[object]:
    if isinstance(value, list):
        return value
    raise SdkCallError("INVALID_RESPONSE_SHAPE")


def _hmac_fingerprint(token: str, branch_no: str, account_number: str) -> str:
    return hmac.new(
        token.encode("utf-8"),
        f"{branch_no}:{account_number}".encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()[:24]


# --- Normalized response field specs (Task 364.7/364.8/364.9) --------------
#
# There is no real account to observe the SPARK API's actual field names or
# precision against (see spec/tasks/t364_yuanta_broker_service.md background).
# These specs define *this service's own* normalized contract; the real
# gateway implementation below is responsible for mapping SPARK's actual
# response shape into these field names once a real account is available.

INVENTORY_STOCK_SPEC = RowSpec(
    identity=("stockCode",), decimals={"costPrice": "positive", "marketValue": "non_negative"},
    integers={"shares": MAX_QUANTITY},
)
INVENTORY_FUTURES_SPEC = RowSpec(
    identity=("contractCode",), decimals={"costPrice": "positive", "marketValue": "non_negative"},
    integers={"lots": MAX_QUANTITY},
)
UNREALIZED_PNL_SPEC = RowSpec(
    identity=("stockCode",), decimals={"unrealizedPnl": "any"}, integers={"quantity": MAX_QUANTITY}
)
REALIZED_PNL_SPEC = RowSpec(identity=("stockCode", "tradeDate"), decimals={"realizedPnl": "any"})
SETTLEMENT_SPEC = RowSpec(identity=("settlementDate",), decimals={"amount": "any"})
FUTURES_MARGIN_SPEC = RowSpec(
    decimals={
        "equity": "non_negative",
        "maintenanceMargin": "non_negative",
        "initialMargin": "non_negative",
        "riskIndicatorPercent": "non_negative",
    }
)
QUOTE_SPEC = RowSpec(
    passthrough=("stockName", "market"),
    decimals={
        "lastPrice": "positive",
        "previousClose": "positive",
        "openPrice": "positive",
        "highPrice": "positive",
        "lowPrice": "positive",
    },
    integers={"volume": MAX_QUANTITY},
)
FIVE_BEST_LEVEL_SPEC = RowSpec(decimals={"price": "positive"}, integers={"volume": MAX_QUANTITY})
TICK_SPEC = RowSpec(passthrough=("time",), decimals={"price": "positive"}, integers={"volume": MAX_QUANTITY})
KLINE_BAR_SPEC = RowSpec(
    passthrough=("date",),
    decimals={"open": "positive", "high": "positive", "low": "positive", "close": "positive"},
    integers={"volume": MAX_QUANTITY},
)
INSTRUMENT_INFO_SPEC = RowSpec(
    passthrough=("name", "market"), decimals={"tickSize": "positive"}, integers={"lotSize": MAX_QUANTITY}
)
ORDER_REPORT_SPEC = RowSpec(
    identity=("orderNo",),
    passthrough=("stockCode", "side", "orderStatus", "reportTime"),
    decimals={"price": "positive"},
    integers={"quantity": MAX_QUANTITY},
)


class RawSparkGateway(Protocol):
    """The exact surface both the real pythonnet-backed gateway and
    FakeYuantaSparkGateway (tests/) must implement. Deliberately query/login
    only — see the module docstring above and CLAUDE.md〈券商 API 只能查詢，
    不得交易〉."""

    def ensure_component_loaded(self) -> None: ...
    def login(
        self, account: str, password: str, certificate_path: Path | None, certificate_password: str
    ) -> LoginResult: ...
    def logout(self) -> None: ...
    def connect_market_data(self) -> None: ...
    def get_stock_inventory(self, account: object) -> object: ...
    def get_futures_inventory(self, account: object) -> object: ...
    def get_unrealized_pnl(self, account: object) -> object: ...
    def get_realized_pnl(self, account: object, start_date: str, end_date: str) -> object: ...
    def get_settlement(self, account: object) -> object: ...
    def get_futures_margin(self, account: object) -> object: ...
    def get_quote(self, code: str) -> object: ...
    def get_five_best(self, code: str) -> object: ...
    def get_intraday_ticks(self, code: str) -> object: ...
    def get_kline(
        self, code: str, interval: str, count: int | None, start_date: str | None, end_date: str | None
    ) -> object: ...
    def get_instrument_info(self, code: str) -> object: ...
    def get_order_execution_report(self, start_date: str | None, end_date: str | None) -> object: ...


class PythonNetYuantaSparkGateway:
    """Real SPARK API adapter, loaded via pythonnet on top of the .NET 8
    CoreCLR runtime. **Never exercised by this repository's automated
    tests** — there is no real `YuantaSparkAPI.dll` or official Yuanta
    account available in this environment (see
    spec/tasks/t364_yuanta_broker_service.md background section). All tests
    drive FakeYuantaSparkGateway instead.

    Import boundary (CLAUDE.md〈券商 API 只能查詢，不得交易〉— highest-priority
    project-wide rule): this class may only reference Login/Logout, the six
    read-only accounting query calls, the five market-data query/subscribe
    calls, and the order-execution-report query call. Any order placement,
    modification, cancellation, combo-order, or margin-optimization symbol
    listed in spec/tasks/t364_yuanta_broker_service.md §364.5 as forbidden
    is disallowed here — enforced by the static source scan in
    tests/test_sdk_gateway_import_boundary.py (kept deliberately vague in
    this docstring so the scan's own banned-symbol list is not accidentally
    satisfied by this file mentioning the names in prose).
    """

    def __init__(self, config: ConfigSnapshot) -> None:
        self._config = config
        self._api: object | None = None

    def ensure_component_loaded(self) -> None:  # pragma: no cover - requires real DLL + .NET 8 runtime
        if self._api is not None:
            return
        import clr  # pythonnet's CLR loader; only imported lazily, never at module load time

        clr.AddReference(str(self._config.dll_path))
        from YuantaSparkAPI import API  # type: ignore[import-not-found]

        self._api = API()

    def login(
        self, account: str, password: str, certificate_path: Path | None, certificate_password: str
    ) -> LoginResult:  # pragma: no cover
        # Linux SPARK API login signature per official documentation:
        # Login(PfxPath, PfxPass, Account, Pass) — distinct from the
        # Windows-only Login(Account, Pass) overload.
        result = self._api.Login(str(certificate_path), certificate_password, account, password)
        is_success = bool(getattr(result, "IsSuccess", False))
        accounts = list(getattr(result, "Accounts", None) or [])
        return LoginResult(is_success=is_success, accounts=accounts)

    def logout(self) -> None:  # pragma: no cover
        if self._api is not None:
            self._api.Logout()

    def connect_market_data(self) -> None:  # pragma: no cover
        self._api.SubscribeMarketData()

    def get_stock_inventory(self, account: object) -> object:  # pragma: no cover
        return self._api.GetStockInventory(account)

    def get_futures_inventory(self, account: object) -> object:  # pragma: no cover
        return self._api.GetFutureInventory(account)

    def get_unrealized_pnl(self, account: object) -> object:  # pragma: no cover
        return self._api.GetUnrealizedProfitLoss(account)

    def get_realized_pnl(self, account: object, start_date: str, end_date: str) -> object:  # pragma: no cover
        return self._api.GetRealizedProfitLoss(account, start_date, end_date)

    def get_settlement(self, account: object) -> object:  # pragma: no cover
        return self._api.GetSettlement(account)

    def get_futures_margin(self, account: object) -> object:  # pragma: no cover
        return self._api.GetFutureMargin(account)

    def get_quote(self, code: str) -> object:  # pragma: no cover
        return self._api.GetQuote(code)

    def get_five_best(self, code: str) -> object:  # pragma: no cover
        return self._api.GetFiveBest(code)

    def get_intraday_ticks(self, code: str) -> object:  # pragma: no cover
        return self._api.GetIntradayTicks(code)

    def get_kline(
        self, code: str, interval: str, count: int | None, start_date: str | None, end_date: str | None
    ) -> object:  # pragma: no cover
        return self._api.GetKLine(code, interval, count, start_date, end_date)

    def get_instrument_info(self, code: str) -> object:  # pragma: no cover
        return self._api.GetInstrumentInfo(code)

    def get_order_execution_report(self, start_date: str | None, end_date: str | None) -> object:  # pragma: no cover
        return self._api.GetOrderExecutionReport(start_date, end_date)


class YuantaBrokerGateway:
    """Orchestrates the fail-closed state machine on top of a RawSparkGateway
    (real or fake): component loading, lazy login, lazy market-data
    connection, account selection, and normalization — all centralized here
    per Task 364.5's "raw→normalized mapping must not scatter" requirement.

    Three independently-cached, digest-scoped capabilities (Task 364.4/364.6):
      - component load (misconfigured=True on failure -> downgrades configState)
      - login + account selection (LOGIN_FAILED does not downgrade configState;
        account-selector failure does, since it means setup itself is broken)
      - market-data connection (MARKET_DATA_UNAVAILABLE does not downgrade
        configState)
    """

    CLEANUP_CALL_TIMEOUT_SECONDS = 2.0

    def __init__(
        self,
        raw_factory: Callable[[ConfigSnapshot], RawSparkGateway] | None = None,
    ) -> None:
        self._raw_factory = raw_factory or (lambda config: PythonNetYuantaSparkGateway(config))
        self._lock = threading.RLock()
        self._raw: RawSparkGateway | None = None
        self._digest: bytes | None = None
        self._component_misconfigured = False
        self._misconfigured_digest: bytes | None = None
        self._logged_in = False
        self._login_succeeded_at_least_once = False
        self._accounts: list[object] = []
        self._stock_account: SelectedAccount | None = None
        self._futures_account: SelectedAccount | None = None
        self._market_data_connected = False
        self._shutdown_done = False

    def runtime_misconfigured_for(self, config: ConfigSnapshot) -> bool:
        digest = self._digest_for(config)
        with self._lock:
            return self._component_misconfigured and self._misconfigured_digest == digest

    def ensure_component_loaded(self, config: ConfigSnapshot) -> RawSparkGateway:
        digest = self._digest_for(config)
        with self._lock:
            if self._raw is not None and self._digest == digest and not self._component_misconfigured:
                return self._raw
            if self._component_misconfigured and self._misconfigured_digest == digest:
                raise SdkCallError("COMPONENT_LOAD_FAILED", misconfigured=True)
            if self._misconfigured_digest != digest:
                self._component_misconfigured = False
                self._misconfigured_digest = None
            if self._digest != digest:
                self._reset_session_state_locked()
            raw = self._raw_factory(config)
            try:
                raw.ensure_component_loaded()
            except Exception as exc:
                self._component_misconfigured = True
                self._misconfigured_digest = digest
                raise SdkCallError("COMPONENT_LOAD_FAILED", misconfigured=True) from exc
            self._raw = raw
            self._digest = digest
            return raw

    def ensure_logged_in(self, config: ConfigSnapshot) -> None:
        raw = self.ensure_component_loaded(config)
        digest = self._digest_for(config)
        with self._lock:
            if self._logged_in and self._digest == digest:
                return
        try:
            result = raw.login(config.account or "", config.password or "", config.certificate_path, config.certificate_password or "")
        except Exception as exc:
            raise SdkCallError("LOGIN_FAILED") from exc
        if not isinstance(result, LoginResult) or not result.is_success or not isinstance(result.accounts, list):
            raise SdkCallError("LOGIN_FAILED")
        with self._lock:
            self._accounts = result.accounts
            self._logged_in = True
            self._login_succeeded_at_least_once = True
            self._stock_account = None
            self._futures_account = None

    def ensure_stock_account(self, config: ConfigSnapshot) -> SelectedAccount:
        return self._ensure_account(config, "stock")

    def ensure_futures_account(self, config: ConfigSnapshot) -> SelectedAccount:
        return self._ensure_account(config, "futures")

    def _ensure_account(self, config: ConfigSnapshot, category: str) -> SelectedAccount:
        self.ensure_logged_in(config)
        digest = self._digest_for(config)
        cache_attr = "_stock_account" if category == "stock" else "_futures_account"
        with self._lock:
            cached = getattr(self, cache_attr)
            if cached is not None and self._digest == digest:
                return cached
            accounts = self._accounts
            selector = config.stock_account_selector if category == "stock" else config.futures_account_selector
        try:
            selected = self._select_account(accounts, category, selector)
        except SdkCallError:
            self._mark_component_misconfigured(config)
            raise
        with self._lock:
            setattr(self, cache_attr, selected)
        return selected

    def ensure_market_data_connected(self, config: ConfigSnapshot) -> RawSparkGateway:
        raw = self.ensure_component_loaded(config)
        digest = self._digest_for(config)
        with self._lock:
            if self._market_data_connected and self._digest == digest:
                return raw
        try:
            raw.connect_market_data()
        except Exception as exc:
            raise SdkCallError("MARKET_DATA_UNAVAILABLE") from exc
        with self._lock:
            self._market_data_connected = True
        return raw

    # --- accounting (364.7) -------------------------------------------------

    def get_stock_inventory(self, config: ConfigSnapshot) -> dict[str, object]:
        account = self.ensure_stock_account(config)
        raw_rows = self._call(lambda: self._raw.get_stock_inventory(account.raw))
        rows = [normalize_row(row, INVENTORY_STOCK_SPEC) for row in _as_list(raw_rows)]
        return {"accountFingerprint": self._fingerprint(config, account), "rows": rows}

    def get_futures_inventory(self, config: ConfigSnapshot) -> dict[str, object]:
        account = self.ensure_futures_account(config)
        raw_rows = self._call(lambda: self._raw.get_futures_inventory(account.raw))
        rows = [normalize_row(row, INVENTORY_FUTURES_SPEC) for row in _as_list(raw_rows)]
        return {"accountFingerprint": self._fingerprint(config, account), "rows": rows}

    def get_unrealized_pnl(self, config: ConfigSnapshot) -> dict[str, object]:
        account = self.ensure_stock_account(config)
        raw_rows = self._call(lambda: self._raw.get_unrealized_pnl(account.raw))
        rows = [normalize_row(row, UNREALIZED_PNL_SPEC) for row in _as_list(raw_rows)]
        return {"accountFingerprint": self._fingerprint(config, account), "rows": rows}

    def get_realized_pnl(self, config: ConfigSnapshot, start_date: str, end_date: str) -> dict[str, object]:
        account = self.ensure_stock_account(config)
        raw_rows = self._call(lambda: self._raw.get_realized_pnl(account.raw, start_date, end_date))
        rows = [normalize_row(row, REALIZED_PNL_SPEC) for row in _as_list(raw_rows)]
        return {"accountFingerprint": self._fingerprint(config, account), "rows": rows}

    def get_settlement(self, config: ConfigSnapshot) -> dict[str, object]:
        account = self.ensure_stock_account(config)
        raw_rows = self._call(lambda: self._raw.get_settlement(account.raw))
        rows = [normalize_row(row, SETTLEMENT_SPEC) for row in _as_list(raw_rows)]
        return {"accountFingerprint": self._fingerprint(config, account), "rows": rows}

    def get_futures_margin(self, config: ConfigSnapshot) -> dict[str, object]:
        account = self.ensure_futures_account(config)
        raw_margin = self._call(lambda: self._raw.get_futures_margin(account.raw))
        return normalize_row(
            raw_margin, FUTURES_MARGIN_SPEC, extra={"accountFingerprint": self._fingerprint(config, account)}
        )

    # --- market data (364.8) ------------------------------------------------

    def get_quote(self, config: ConfigSnapshot, codes: list[str]) -> dict[str, object]:
        self.ensure_market_data_connected(config)
        quotes = [
            normalize_row(
                self._call(lambda code=code: self._raw.get_quote(code), reason="MARKET_DATA_UNAVAILABLE"),
                QUOTE_SPEC,
                extra={"stockCode": code},
            )
            for code in codes
        ]
        return {"quotes": quotes}

    def get_five_best(self, config: ConfigSnapshot, codes: list[str]) -> dict[str, object]:
        self.ensure_market_data_connected(config)
        results = []
        for code in codes:
            raw_book = self._call(lambda code=code: self._raw.get_five_best(code), reason="MARKET_DATA_UNAVAILABLE")
            bids_raw = raw_field(raw_book, "bids")
            asks_raw = raw_field(raw_book, "asks")
            if not isinstance(bids_raw, list) or not isinstance(asks_raw, list):
                results.append({"stockCode": code, "status": "ERROR", "reason": "INVALID_BOOK_SHAPE", "bids": [], "asks": []})
                continue
            bids = [normalize_row(level, FIVE_BEST_LEVEL_SPEC) for level in bids_raw]
            asks = [normalize_row(level, FIVE_BEST_LEVEL_SPEC) for level in asks_raw]
            all_ok = all(level["status"] == "SUCCESS" for level in bids + asks)
            results.append(
                {
                    "stockCode": code,
                    "status": "SUCCESS" if all_ok else "ERROR",
                    "reason": None if all_ok else "LEVEL_VALIDATION_FAILED",
                    "bids": bids,
                    "asks": asks,
                }
            )
        return {"fiveBest": results}

    def get_intraday_ticks(self, config: ConfigSnapshot, code: str) -> dict[str, object]:
        self.ensure_market_data_connected(config)
        raw_ticks = self._call(lambda: self._raw.get_intraday_ticks(code), reason="MARKET_DATA_UNAVAILABLE")
        ticks = [normalize_row(tick, TICK_SPEC) for tick in _as_list(raw_ticks)]
        return {"stockCode": code, "ticks": ticks}

    def get_kline(
        self, config: ConfigSnapshot, code: str, interval: str, count: int | None, start_date: str | None, end_date: str | None
    ) -> dict[str, object]:
        self.ensure_market_data_connected(config)
        raw_bars = self._call(
            lambda: self._raw.get_kline(code, interval, count, start_date, end_date), reason="MARKET_DATA_UNAVAILABLE"
        )
        bars = [normalize_row(bar, KLINE_BAR_SPEC) for bar in _as_list(raw_bars)]
        return {"stockCode": code, "interval": interval, "bars": bars}

    def get_instrument_info(self, config: ConfigSnapshot, code: str) -> dict[str, object]:
        self.ensure_market_data_connected(config)
        raw_info = self._call(lambda: self._raw.get_instrument_info(code), reason="MARKET_DATA_UNAVAILABLE")
        return normalize_row(raw_info, INSTRUMENT_INFO_SPEC, extra={"stockCode": code})

    # --- reports (364.9) ------------------------------------------------

    def get_order_execution_report(
        self, config: ConfigSnapshot, start_date: str | None, end_date: str | None
    ) -> dict[str, object]:
        self.ensure_logged_in(config)
        raw_rows = self._call(lambda: self._raw.get_order_execution_report(start_date, end_date))
        reports = [normalize_row(row, ORDER_REPORT_SPEC) for row in _as_list(raw_rows)]
        return {"reports": reports}

    # --- lifecycle (364.11) --------------------------------------------------

    def shutdown(self) -> None:
        with self._lock:
            if self._shutdown_done:
                return
            self._shutdown_done = True
            login_happened = self._login_succeeded_at_least_once
            raw = self._raw
        if not login_happened or raw is None:
            return
        try:
            self._run_bounded(raw.logout, self.CLEANUP_CALL_TIMEOUT_SECONDS)
        except BaseException:
            logger.warning("Yuanta SDK cleanup skipped reason=CLEANUP_FAILED")

    # --- internals -----------------------------------------------------------

    def _call(self, fn: Callable[[], object], *, reason: str = "ACCOUNTING_TRANSPORT_FAILED") -> object:
        try:
            return fn()
        except SdkCallError:
            raise
        except Exception as exc:
            raise SdkCallError(reason) from exc

    def _fingerprint(self, config: ConfigSnapshot, account: SelectedAccount) -> str:
        return _hmac_fingerprint(config.internal_service_token or "", account.branch_no, account.account_number)

    def _mark_component_misconfigured(self, config: ConfigSnapshot) -> None:
        digest = self._digest_for(config)
        with self._lock:
            self._component_misconfigured = True
            self._misconfigured_digest = digest
            self._reset_session_state_locked()

    def _reset_session_state_locked(self) -> None:
        self._logged_in = False
        self._accounts = []
        self._stock_account = None
        self._futures_account = None
        self._market_data_connected = False

    @staticmethod
    def _select_account(accounts: list[object], category: str, selector: str | None) -> SelectedAccount:
        candidates: list[SelectedAccount] = []
        for account in accounts:
            account_type = raw_field(account, "accountType") or raw_field(account, "account_type")
            if not isinstance(account_type, str) or account_type.lower() != category:
                continue
            branch = raw_field(account, "branchNo") or raw_field(account, "branch_no")
            number = raw_field(account, "account")
            if not isinstance(branch, str) or not branch or not isinstance(number, str) or not number:
                raise SdkCallError(f"INVALID_{category.upper()}_ACCOUNT", misconfigured=True)
            candidates.append(SelectedAccount(account, branch, number, category))

        if selector is not None:
            selector_branch, _, selector_account = selector.partition(":")
            matches = [
                candidate
                for candidate in candidates
                if candidate.branch_no == selector_branch and candidate.account_number == selector_account
            ]
            if len(matches) != 1:
                raise SdkCallError(f"{category.upper()}_ACCOUNT_SELECTOR_NOT_UNIQUE", misconfigured=True)
            return matches[0]
        if len(candidates) != 1:
            raise SdkCallError(f"{category.upper()}_ACCOUNT_NOT_UNIQUE", misconfigured=True)
        return candidates[0]

    @staticmethod
    def _run_bounded(call: Callable[[], object], timeout: float) -> None:
        output: queue.Queue[tuple[bool, object]] = queue.Queue(maxsize=1)

        def invoke() -> None:
            try:
                output.put((True, call()))
            except BaseException as exc:  # exception object stays internal; never serialized or logged
                output.put((False, exc))

        worker = threading.Thread(target=invoke, name="yuanta-sdk-cleanup", daemon=True)
        worker.start()
        worker.join(timeout)
        if worker.is_alive():
            return
        try:
            ok, value = output.get_nowait()
        except queue.Empty:
            return
        if not ok:
            assert isinstance(value, BaseException)
            raise value

    @staticmethod
    def _digest_for(config: ConfigSnapshot) -> bytes:
        joined = "\0".join(
            (
                config.account or "",
                config.password or "",
                config.certificate_password or "",
                str(config.certificate_path or ""),
                str(config.dll_path or ""),
                config.stock_account_selector or "",
                config.futures_account_selector or "",
                config.internal_service_token or "",
            )
        )
        return hashlib.sha256(joined.encode("utf-8")).digest()
