from __future__ import annotations

import importlib.metadata
import json
import logging
import platform
import secrets
from contextlib import asynccontextmanager
from datetime import date
from typing import Annotated

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel, ConfigDict, Field, StrictBool, StrictStr, field_validator

from .bank_balance import BankBalanceError, BankBalanceService
from .config import ConfigLoader, ConfigSnapshot
from .counters import Outcome, OutcomeCounters
from .dividends import DividendError, DividendService
from .etf_holdings import EtfHoldingsService
from .normalization import stock_code, stock_codes, strict_iso_date
from .market_data_v1 import MarketDataV1Error, MarketDataV1Service
from .portfolio import PortfolioError, PortfolioService
from .quotes import QuoteError, QuoteService
from .realized_gain import RealizedGainError, RealizedGainService
from .redaction import install_log_redaction, redact_mapping
from .sdk_gateway import SdkCallError, SdkGateway
from .settlement import SettlementError, SettlementService
from .stock_push_stream import StockPushError, StockPushStream
from .taiex_index_stream import TaiexIndexStream, TaiexIndexStreamError
from .technical_indicators import TechnicalIndicatorError, TechnicalIndicatorService
from .trades import TradeReadError, TradeReadService


logger = logging.getLogger(__name__)
INTERNAL_TOKEN_HEADER = "X-Internal-Service-Token"


class MarketDataV1RouteError(RuntimeError):
    """Only the two Task408 v1 routes use this exact-root response transport."""

    def __init__(self, status_code: int, reason: str) -> None:
        super().__init__(reason)
        self.status_code, self.reason = status_code, reason

# Central last line of defense: even if a future call site logs a raw SDK
# exception or stringifies a credential field, the record is redacted before
# it reaches any handler. Installed on every logger this service actually
# uses (module-level loggers are per-module and do not inherit filters from
# ancestors during propagation, so each one is attached explicitly).
install_log_redaction(
    logger,
    logging.getLogger("fubon_broker_service.sdk_gateway"),
    logging.getLogger("fubon_broker_service.taiex_index_stream"),
    logging.getLogger("fubon_broker_service.stock_push_stream"),
)


class PortfolioReadRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    dryRun: StrictBool

    @field_validator("dryRun")
    @classmethod
    def must_be_dry_run(cls, value: bool) -> bool:
        if value is not True:
            raise ValueError("dryRun must be true")
        return value


class QuoteReadRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    codes: list[StrictStr]
    purpose: StrictStr

    @field_validator("purpose")
    @classmethod
    def valid_purpose(cls, value: str) -> str:
        if value not in {"LIVE", "INVENTORY"}:
            raise ValueError("INVALID_PURPOSE")
        return value


class EtfHoldingsReadRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    codes: list[StrictStr] = Field(min_length=1, max_length=50)

    @field_validator("codes")
    @classmethod
    def valid_etf_codes(cls, value: list[str]) -> list[str]:
        return EtfHoldingsService.validate_codes(value)


class TradeReadRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    startDate: StrictStr
    endDate: StrictStr

    @field_validator("startDate", "endDate")
    @classmethod
    def valid_iso_date(cls, value: str) -> str:
        strict_iso_date(value)
        return value


class DividendReadRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    symbols: list[StrictStr] = Field(min_length=1, max_length=2000)
    from_date: StrictStr = Field(alias="from")
    to_date: StrictStr = Field(alias="to")

    @field_validator("symbols")
    @classmethod
    def validate_symbols(cls, value: list[str]) -> list[str]:
        return stock_codes(value, maximum=2000)

    @field_validator("from_date", "to_date")
    @classmethod
    def validate_date(cls, value: str) -> str:
        strict_iso_date(value)
        return value


class TechnicalReadRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    symbol: StrictStr

    @field_validator("symbol")
    @classmethod
    def validate_symbol(cls, value: str) -> str:
        return stock_code(value)


class MarketDataV1ReadRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    symbol: StrictStr

    @field_validator("symbol")
    @classmethod
    def validate_symbol(cls, value: str) -> str:
        return stock_code(value)


class HistoricalDailyCandlesReadRequest(MarketDataV1ReadRequest):
    from_date: StrictStr = Field(alias="from")
    to_date: StrictStr = Field(alias="to")

    @field_validator("from_date", "to_date")
    @classmethod
    def validate_date(cls, value: str) -> str:
        strict_iso_date(value)
        return value

    @field_validator("to_date")
    @classmethod
    def validate_range(cls, value: str, info) -> str:
        start = info.data.get("from_date")
        if start is None:
            raise ValueError("INVALID_REQUEST")
        if strict_iso_date(value) < strict_iso_date(start) or (strict_iso_date(value) - strict_iso_date(start)).days + 1 > 366:
            raise ValueError("INVALID_REQUEST")
        return value


class StockSubscriptionsRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    symbols: list[StrictStr]


def _unique_json_keys(pairs: list[tuple[str, object]]) -> dict[str, object]:
    output: dict[str, object] = {}
    for key, value in pairs:
        if key in output:
            raise ValueError("DUPLICATE_JSON_KEY")
        output[key] = value
    return output


def _sdk_version() -> str:
    for distribution in ("fubon-neo", "fubon_neo"):
        try:
            return importlib.metadata.version(distribution)
        except importlib.metadata.PackageNotFoundError:
            continue
    return "2.2.9"


def create_app(
    config_loader: ConfigLoader | None = None,
    gateway: SdkGateway | None = None,
    portfolio_service: PortfolioService | None = None,
    quote_service: QuoteService | None = None,
    trade_service: TradeReadService | None = None,
    counters: OutcomeCounters | None = None,
    taiex_index_stream: TaiexIndexStream | None = None,
    etf_holdings_service: EtfHoldingsService | None = None,
    bank_balance_service: BankBalanceService | None = None,
    settlement_service: SettlementService | None = None,
    realized_gain_service: RealizedGainService | None = None,
    dividend_service: DividendService | None = None,
    technical_indicator_service: TechnicalIndicatorService | None = None,
    market_data_v1_service: MarketDataV1Service | None = None,
    stock_push_stream: StockPushStream | None = None,
) -> FastAPI:
    loader = config_loader or ConfigLoader.from_environment()
    sdk_gateway = gateway or SdkGateway(loader)
    portfolio = portfolio_service or PortfolioService(sdk_gateway)
    quotes = quote_service or QuoteService(sdk_gateway)
    trades = trade_service or TradeReadService(sdk_gateway)
    outcome_counters = counters or OutcomeCounters()
    index_stream = taiex_index_stream or TaiexIndexStream(sdk_gateway)
    etf_holdings = etf_holdings_service or EtfHoldingsService(sdk_gateway)
    bank_balance = bank_balance_service or BankBalanceService(sdk_gateway)
    settlement = settlement_service or SettlementService(sdk_gateway)
    realized_gain = realized_gain_service or RealizedGainService(sdk_gateway)
    dividends = dividend_service or DividendService(sdk_gateway)
    technical = technical_indicator_service or TechnicalIndicatorService(sdk_gateway)
    market_v1 = market_data_v1_service or MarketDataV1Service(sdk_gateway)
    stock_stream = stock_push_stream or StockPushStream(sdk_gateway, counters=outcome_counters)

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        yield
        stock_stream.shutdown()
        index_stream.shutdown()
        sdk_gateway.shutdown()

    application = FastAPI(
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
        lifespan=lifespan,
        redirect_slashes=False,
    )
    application.state.config_loader = loader
    application.state.sdk_gateway = sdk_gateway
    application.state.outcome_counters = outcome_counters
    application.state.taiex_index_stream = index_stream
    application.state.stock_push_stream = stock_stream

    no_body_paths = {"/internal/bank-balance/read", "/internal/settlement/read", "/internal/realized-gains/read",
                     "/internal/market-data/stock-push/stream"}
    v1_market_data_paths = {"/internal/market-data/stock-basic/read", "/internal/market-data/intraday-candles/read",
                            "/internal/market-data/intraday-volumes/read", "/internal/market-data/historical-daily-candles/read"}
    strict_json_paths = {"/internal/trades/read", "/internal/market-data/dividends/read",
                         "/internal/market-data/technical-indicators/read",
                         "/internal/market-data/stock-push/subscriptions", *v1_market_data_paths}

    @application.middleware("http")
    async def sanitized_exception_boundary(request: Request, call_next):
        try:
            # The v1 routes intentionally authenticate before parsing their body,
            # so disabled/misconfigured state cannot leak an INVALID_REQUEST
            # precedence and their legacy neighbours stay wire-compatible.
            if request.url.path in v1_market_data_paths:
                config = loader.load()
                if (not config.enabled or config.state != "READY" or not config.internal_service_token
                        or runtime_misconfigured(config)):
                    outcome_counters.increment(Outcome.MISCONFIGURED)
                    return JSONResponse(status_code=503, content={"reason": "MISCONFIGURED"})
                tokens = request.headers.getlist(INTERNAL_TOKEN_HEADER)
                if len(tokens) != 1 or not tokens[0]:
                    return JSONResponse(status_code=401, content={"reason": "UNAUTHORIZED"})
                if not secrets.compare_digest(config.internal_service_token.encode("utf-8"), tokens[0].encode("utf-8")):
                    return JSONResponse(status_code=403, content={"reason": "FORBIDDEN"})
                request.state.market_data_v1_config = config
            if request.url.path in no_body_paths | strict_json_paths:
                if request.query_params:
                    return JSONResponse(status_code=400, content={"reason": "INVALID_REQUEST"})
                chunks, body_size = [], 0
                async for chunk in request.stream():
                    body_size += len(chunk)
                    if body_size > 65536 or request.url.path in no_body_paths and chunk:
                        return JSONResponse(status_code=400, content={"reason": "INVALID_REQUEST"})
                    chunks.append(chunk)
                body = b"".join(chunks)
                # Starlette's cached middleware request replays this bounded body to FastAPI.
                request._body = body
                if body and request.url.path in strict_json_paths:
                    try:
                        json.loads(body, object_pairs_hook=_unique_json_keys)
                    except (ValueError, UnicodeError):
                        return JSONResponse(status_code=400, content={"reason": "INVALID_REQUEST"})
            return await call_next(request)
        except Exception:
            # Never let an SDK/raw provider exception reach the ASGI server logger or response.
            if request.url.path in v1_market_data_paths:
                logger.error("Fubon market-data v1 request failed reason=UPSTREAM_UNAVAILABLE")
                return JSONResponse(status_code=503, content={"reason": "UPSTREAM_UNAVAILABLE"})
            logger.error("Fubon request failed reason=INTERNAL_FAILURE")
            return JSONResponse(status_code=503, content=redact_mapping({"reason": "INTERNAL_FAILURE"}))

    @application.exception_handler(RequestValidationError)
    async def validation_error_handler(_request, _exc):
        return JSONResponse(status_code=400, content=redact_mapping({"reason": "INVALID_REQUEST"}))

    @application.exception_handler(MarketDataV1RouteError)
    async def market_data_v1_error_handler(_request, exc: MarketDataV1RouteError):
        return JSONResponse(status_code=exc.status_code, content={"reason": exc.reason})

    def runtime_misconfigured(config: ConfigSnapshot) -> bool:
        checker = getattr(sdk_gateway, "runtime_misconfigured_for", None)
        if callable(checker):
            return bool(checker(config))
        return bool(getattr(sdk_gateway, "runtime_misconfigured", False))

    def authorize(
        tokens: Annotated[list[str] | None, Header(alias=INTERNAL_TOKEN_HEADER)] = None,
    ) -> ConfigSnapshot:
        config = loader.load()
        if config.state == "MISCONFIGURED":
            outcome_counters.increment(Outcome.MISCONFIGURED)
            raise HTTPException(
                status_code=503, detail=redact_mapping({"reason": config.reason or "MISCONFIGURED"})
            )
        if not config.enabled:
            outcome_counters.increment(Outcome.DISABLED)
            raise HTTPException(status_code=503, detail=redact_mapping({"reason": "DISABLED"}))
        if config.state != "READY" or not config.internal_service_token:
            outcome_counters.increment(Outcome.MISCONFIGURED)
            raise HTTPException(
                status_code=503, detail=redact_mapping({"reason": config.reason or "MISCONFIGURED"})
            )
        if tokens is None or len(tokens) != 1 or not tokens[0]:
            raise HTTPException(status_code=401, detail=redact_mapping({"reason": "TOKEN_REQUIRED"}))
        token = tokens[0]
        if not secrets.compare_digest(
            config.internal_service_token.encode("utf-8"), token.encode("utf-8")
        ):
            raise HTTPException(status_code=403, detail=redact_mapping({"reason": "TOKEN_INVALID"}))
        if runtime_misconfigured(config):
            outcome_counters.increment(Outcome.MISCONFIGURED)
            raise HTTPException(status_code=503, detail=redact_mapping({"reason": "RUNTIME_MISCONFIGURED"}))
        return config

    @application.get("/internal/health")
    def health() -> dict[str, object]:
        config = loader.load()
        state = config.state
        if config.enabled and state == "READY" and runtime_misconfigured(config):
            state = "MISCONFIGURED"
        return {
            "status": "UP",
            "configState": state,
            "sdkVersion": _sdk_version(),
            "platform": platform.machine(),
        }

    @application.get("/internal/config")
    def config(_config: ConfigSnapshot = Depends(authorize)) -> dict[str, object]:
        current = _config
        effective_state = (
            "MISCONFIGURED"
            if current.enabled and current.state == "READY" and runtime_misconfigured(current)
            else current.state
        )
        ready = effective_state == "READY"
        return {
            "configState": effective_state,
            "presence": current.presence.public_dict(),
            "capabilities": {
                "portfolioRead": ready,
                "twQuotes": ready,
                "taiexIndexStream": ready and TaiexIndexStream.configured(current),
            },
        }

    @application.post("/internal/portfolio/read")
    def portfolio_read(
        _request: PortfolioReadRequest,
        _config: ConfigSnapshot = Depends(authorize),
    ) -> dict[str, object]:
        try:
            result = portfolio.read()
            outcome_counters.increment(Outcome.DRY_RUN)
            result["counters"] = outcome_counters.snapshot()
            return result
        except SdkCallError as exc:
            outcome = Outcome.MISCONFIGURED if exc.misconfigured else Outcome.ACCOUNTING_FAILED
            outcome_counters.increment(outcome)
            logger.warning("Fubon portfolio dry-read failed reason=%s", exc.reason)
            raise HTTPException(status_code=503, detail=redact_mapping({"reason": exc.reason})) from None
        except PortfolioError as exc:
            outcome_counters.increment(Outcome.RECONCILE_FAILED)
            logger.warning("Fubon portfolio reconciliation rejected reason=%s", exc.reason)
            raise HTTPException(status_code=503, detail=redact_mapping({"reason": exc.reason})) from None

    @application.post("/internal/market-data/tw-quotes")
    async def tw_quotes(
        request: QuoteReadRequest,
        _config: ConfigSnapshot = Depends(authorize),
    ) -> dict[str, object]:
        try:
            result = await quotes.read(request.codes, request.purpose)
        except SdkCallError as exc:
            outcome = Outcome.MISCONFIGURED if exc.misconfigured else Outcome.QUOTE_FAILED
            outcome_counters.increment(outcome)
            logger.warning("Fubon quote session failed reason=%s", exc.reason)
            raise HTTPException(status_code=503, detail=redact_mapping({"reason": exc.reason})) from None
        except QuoteError as exc:
            outcome_counters.increment(Outcome.QUOTE_FAILED)
            raise HTTPException(status_code=400, detail=redact_mapping({"reason": exc.reason})) from None
        successes = sum(1 for row in result["quotes"] if row.get("status") == "SUCCESS")
        if successes != len(result["quotes"]):
            outcome_counters.increment(Outcome.QUOTE_FAILED)
        else:
            outcome_counters.increment(Outcome.SUCCESS)
        result["counters"] = outcome_counters.snapshot()
        return result

    @application.post("/internal/market-data/etf-holdings")
    async def etf_holdings_read(
        request: EtfHoldingsReadRequest,
        _config: ConfigSnapshot = Depends(authorize),
    ) -> dict[str, object]:
        result = await etf_holdings.read(request.codes)
        successes = sum(1 for row in result["holdings"] if row.get("status") == "SUCCESS")
        if successes != len(result["holdings"]):
            # ETF is another read-only market-data query. Preserve the existing exact 13-key
            # process counter contract consumed by the quote client; per-row ETF reasons stay specific.
            outcome_counters.increment(Outcome.QUOTE_FAILED)
        else:
            outcome_counters.increment(Outcome.SUCCESS)
        result["counters"] = outcome_counters.snapshot()
        return result

    @application.post("/internal/trades/read")
    def trades_read(
        request: TradeReadRequest,
        _config: ConfigSnapshot = Depends(authorize),
    ) -> dict[str, object]:
        start = date.fromisoformat(request.startDate)
        end = date.fromisoformat(request.endDate)
        if start > end or (end - start).days > 7:
            raise HTTPException(status_code=400, detail=redact_mapping({"reason": "INVALID_DATE_RANGE"}))
        try:
            result = trades.read(request.startDate, request.endDate)
        except SdkCallError as exc:
            outcome = Outcome.MISCONFIGURED if exc.misconfigured else Outcome.ACCOUNTING_FAILED
            outcome_counters.increment(outcome)
            logger.warning("Fubon filled-trades read failed reason=%s", exc.reason)
            raise HTTPException(status_code=503, detail=redact_mapping({"reason": exc.reason})) from None
        except TradeReadError as exc:
            outcome_counters.increment(Outcome.RECONCILE_FAILED)
            logger.warning(
                "Fubon filled-trades reconciliation rejected reason=%s detail=%s", exc.reason, exc.detail
            )
            raise HTTPException(status_code=503, detail=redact_mapping({"reason": exc.reason})) from None
        outcome_counters.increment(Outcome.SUCCESS)
        return result

    @application.post("/internal/bank-balance/read")
    def bank_balance_read(_config: ConfigSnapshot = Depends(authorize)) -> dict[str, object]:
        try:
            result = bank_balance.read()
        except SdkCallError as exc:
            outcome = Outcome.MISCONFIGURED if exc.misconfigured else Outcome.ACCOUNTING_FAILED
            outcome_counters.increment(outcome)
            logger.warning("Fubon bank balance read failed reason=%s", exc.reason)
            raise HTTPException(status_code=503, detail=redact_mapping({"reason": exc.reason})) from None
        except BankBalanceError as exc:
            outcome_counters.increment(Outcome.RECONCILE_FAILED)
            logger.warning("Fubon bank balance reconciliation rejected reason=%s", exc.reason)
            raise HTTPException(status_code=503, detail=redact_mapping({"reason": exc.reason})) from None
        outcome_counters.increment(Outcome.SUCCESS)
        return result

    @application.post("/internal/settlement/read")
    def settlement_read(_config: ConfigSnapshot = Depends(authorize)) -> dict[str, object]:
        try:
            result = settlement.read()
        except SdkCallError as exc:
            outcome = Outcome.MISCONFIGURED if exc.misconfigured else Outcome.ACCOUNTING_FAILED
            outcome_counters.increment(outcome)
            logger.warning("Fubon settlement read failed reason=%s", exc.reason)
            raise HTTPException(status_code=503, detail=redact_mapping({"reason": exc.reason})) from None
        except SettlementError as exc:
            outcome_counters.increment(Outcome.RECONCILE_FAILED)
            logger.warning("Fubon settlement reconciliation rejected reason=%s", exc.reason)
            raise HTTPException(status_code=503, detail=redact_mapping({"reason": exc.reason})) from None
        outcome_counters.increment(Outcome.SUCCESS)
        return result

    @application.post("/internal/realized-gains/read")
    def realized_gains_read(_config: ConfigSnapshot = Depends(authorize)) -> dict[str, object]:
        try:
            result = realized_gain.read()
        except SdkCallError as exc:
            outcome = Outcome.MISCONFIGURED if exc.misconfigured else Outcome.ACCOUNTING_FAILED
            outcome_counters.increment(outcome)
            logger.warning("Fubon realized gain read failed reason=%s", exc.reason)
            raise HTTPException(status_code=503, detail=redact_mapping({"reason": exc.reason})) from None
        except RealizedGainError as exc:
            outcome_counters.increment(Outcome.RECONCILE_FAILED)
            logger.warning("Fubon realized gain reconciliation rejected reason=%s", exc.reason)
            raise HTTPException(status_code=503, detail=redact_mapping({"reason": exc.reason})) from None
        outcome_counters.increment(Outcome.SUCCESS)
        return result

    @application.get("/internal/market-data/taiex-index/stream")
    async def taiex_index_sse(_config: ConfigSnapshot = Depends(authorize)) -> StreamingResponse:
        try:
            index_stream.ensure_started(_config)
        except TaiexIndexStreamError as exc:
            outcome_counters.increment(Outcome.MISCONFIGURED)
            raise HTTPException(status_code=503, detail=redact_mapping({"reason": exc.reason})) from None
        return StreamingResponse(
            index_stream.events(),
            media_type="text/event-stream",
            headers={"Cache-Control": "no-store", "X-Accel-Buffering": "no"},
        )

    @application.post("/internal/market-data/dividends/read")
    def dividends_read(request: DividendReadRequest, _config: ConfigSnapshot = Depends(authorize)) -> dict[str, object]:
        try:
            result = dividends.read(request.symbols, request.from_date, request.to_date)
        except SdkCallError as exc:
            outcome_counters.increment(Outcome.MISCONFIGURED if exc.misconfigured else Outcome.QUOTE_FAILED)
            raise HTTPException(status_code=503, detail=redact_mapping({"reason": exc.reason})) from None
        except DividendError as exc:
            outcome_counters.increment(Outcome.QUOTE_FAILED)
            raise HTTPException(status_code=400 if exc.request_error else 503, detail=redact_mapping({"reason": exc.reason})) from None
        outcome_counters.increment(Outcome.QUOTE_FAILED if any(row["status"] == "FAILED" for row in result["rows"]) else Outcome.SUCCESS)
        return result

    @application.post("/internal/market-data/technical-indicators/read")
    def technical_read(request: TechnicalReadRequest, _config: ConfigSnapshot = Depends(authorize)) -> dict[str, object]:
        try:
            result = technical.read(request.symbol)
        except SdkCallError as exc:
            outcome_counters.increment(Outcome.MISCONFIGURED if exc.misconfigured else Outcome.QUOTE_FAILED)
            raise HTTPException(status_code=503, detail=redact_mapping({"reason": exc.reason})) from None
        except TechnicalIndicatorError as exc:
            outcome_counters.increment(Outcome.QUOTE_FAILED)
            raise HTTPException(status_code=400 if exc.request_error else 503, detail=redact_mapping({"reason": exc.reason})) from None
        outcome_counters.increment(Outcome.SUCCESS if all(profile["status"] == "AVAILABLE" for profile in result["profiles"]) else Outcome.QUOTE_FAILED)
        return result

    def market_data_v1_sdk_error(exc: SdkCallError) -> MarketDataV1RouteError:
        if exc.misconfigured:
            return MarketDataV1RouteError(503, "MISCONFIGURED")
        if exc.reason in {"RATE_LIMITED", "HISTORY_BUDGET_EXHAUSTED"}:
            return MarketDataV1RouteError(503, exc.reason)
        return MarketDataV1RouteError(503, "UPSTREAM_UNAVAILABLE")

    @application.post("/internal/market-data/stock-basic/read")
    def stock_basic_read(request: MarketDataV1ReadRequest) -> dict[str, object]:
        try:
            result = market_v1.basic(request.symbol)
        except SdkCallError as exc:
            raise market_data_v1_sdk_error(exc) from None
        except MarketDataV1Error as exc:
            raise MarketDataV1RouteError(400 if exc.request_error else 503, exc.reason) from None
        outcome_counters.increment(Outcome.SUCCESS)
        return result

    @application.post("/internal/market-data/intraday-candles/read")
    def intraday_candles_read(request: MarketDataV1ReadRequest) -> dict[str, object]:
        try:
            result = market_v1.candles(request.symbol)
        except SdkCallError as exc:
            raise market_data_v1_sdk_error(exc) from None
        except MarketDataV1Error as exc:
            raise MarketDataV1RouteError(400 if exc.request_error else 503, exc.reason) from None
        outcome_counters.increment(Outcome.SUCCESS)
        return result

    @application.post("/internal/market-data/intraday-volumes/read")
    def intraday_volumes_read(request: MarketDataV1ReadRequest) -> dict[str, object]:
        try:
            result = market_v1.volumes(request.symbol)
        except SdkCallError as exc:
            raise market_data_v1_sdk_error(exc) from None
        except MarketDataV1Error as exc:
            raise MarketDataV1RouteError(400 if exc.request_error else 503, exc.reason) from None
        outcome_counters.increment(Outcome.SUCCESS)
        return result

    @application.post("/internal/market-data/historical-daily-candles/read")
    def historical_daily_candles_read(request: HistoricalDailyCandlesReadRequest) -> dict[str, object]:
        try:
            result = market_v1.daily_candles(request.symbol, request.from_date, request.to_date)
        except SdkCallError as exc:
            raise market_data_v1_sdk_error(exc) from None
        except MarketDataV1Error as exc:
            raise MarketDataV1RouteError(400 if exc.request_error else 503, exc.reason) from None
        outcome_counters.increment(Outcome.SUCCESS)
        return result

    @application.post("/internal/market-data/stock-push/subscriptions")
    def stock_subscriptions(request: StockSubscriptionsRequest,
                            _config: ConfigSnapshot = Depends(authorize)) -> dict[str, object]:
        try:
            result = stock_stream.replace_desired(request.symbols, _config)
        except StockPushError as exc:
            outcome_counters.increment(Outcome.DISABLED if exc.reason == "STOCK_PUSH_DISABLED" else Outcome.QUOTE_FAILED)
            raise HTTPException(status_code=400 if exc.request_error else 503,
                                detail=redact_mapping({"reason": exc.reason})) from None
        outcome_counters.increment(Outcome.EMPTY_CLEARED if result["outcome"] == "CLEARED" else Outcome.SUCCESS)
        return result

    @application.get("/internal/market-data/stock-push/stream")
    def stock_price_sse(_config: ConfigSnapshot = Depends(authorize)) -> StreamingResponse:
        try:
            stock_stream.ensure_started(_config)
        except StockPushError as exc:
            outcome_counters.increment(Outcome.DISABLED if exc.reason == "STOCK_PUSH_DISABLED" else Outcome.QUOTE_FAILED)
            raise HTTPException(status_code=503, detail=redact_mapping({"reason": exc.reason})) from None
        return StreamingResponse(stock_stream.events(), media_type="text/event-stream",
                                 headers={"Cache-Control": "no-store", "X-Accel-Buffering": "no"})

    return application


app = create_app()
