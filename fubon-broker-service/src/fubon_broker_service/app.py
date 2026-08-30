from __future__ import annotations

import importlib.metadata
import logging
import platform
import secrets
from contextlib import asynccontextmanager
from datetime import date
from typing import Annotated

from fastapi import Depends, FastAPI, Header, HTTPException
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel, ConfigDict, Field, StrictBool, StrictStr, field_validator

from .config import ConfigLoader, ConfigSnapshot
from .counters import Outcome, OutcomeCounters
from .etf_holdings import EtfHoldingsService
from .portfolio import PortfolioError, PortfolioService
from .quotes import QuoteError, QuoteService
from .redaction import install_log_redaction, redact_mapping
from .sdk_gateway import SdkCallError, SdkGateway
from .taiex_index_stream import TaiexIndexStream, TaiexIndexStreamError
from .trades import TradeReadError, TradeReadService


logger = logging.getLogger(__name__)
INTERNAL_TOKEN_HEADER = "X-Internal-Service-Token"

# Central last line of defense: even if a future call site logs a raw SDK
# exception or stringifies a credential field, the record is redacted before
# it reaches any handler. Installed on every logger this service actually
# uses (module-level loggers are per-module and do not inherit filters from
# ancestors during propagation, so each one is attached explicitly).
install_log_redaction(
    logger,
    logging.getLogger("fubon_broker_service.sdk_gateway"),
    logging.getLogger("fubon_broker_service.taiex_index_stream"),
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
        date.fromisoformat(value)  # raises ValueError -> 400 via RequestValidationError
        return value


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
) -> FastAPI:
    loader = config_loader or ConfigLoader.from_environment()
    sdk_gateway = gateway or SdkGateway(loader)
    portfolio = portfolio_service or PortfolioService(sdk_gateway)
    quotes = quote_service or QuoteService(sdk_gateway)
    trades = trade_service or TradeReadService(sdk_gateway)
    outcome_counters = counters or OutcomeCounters()
    index_stream = taiex_index_stream or TaiexIndexStream(sdk_gateway)
    etf_holdings = etf_holdings_service or EtfHoldingsService(sdk_gateway)

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        yield
        index_stream.shutdown()
        sdk_gateway.shutdown()

    application = FastAPI(
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
        lifespan=lifespan,
    )
    application.state.config_loader = loader
    application.state.sdk_gateway = sdk_gateway
    application.state.outcome_counters = outcome_counters
    application.state.taiex_index_stream = index_stream

    @application.middleware("http")
    async def sanitized_exception_boundary(request, call_next):
        try:
            return await call_next(request)
        except Exception:
            # Never let an SDK/raw provider exception reach the ASGI server logger or response.
            logger.error("Fubon request failed reason=INTERNAL_FAILURE")
            return JSONResponse(status_code=503, content=redact_mapping({"reason": "INTERNAL_FAILURE"}))

    @application.exception_handler(RequestValidationError)
    async def validation_error_handler(_request, _exc):
        return JSONResponse(status_code=400, content=redact_mapping({"reason": "INVALID_REQUEST"}))

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
            result = trades.read(request.startDate, request.endDate, _config.internal_service_token or "")
        except SdkCallError as exc:
            outcome = Outcome.MISCONFIGURED if exc.misconfigured else Outcome.ACCOUNTING_FAILED
            outcome_counters.increment(outcome)
            logger.warning("Fubon filled-trades read failed reason=%s", exc.reason)
            raise HTTPException(status_code=503, detail=redact_mapping({"reason": exc.reason})) from None
        except TradeReadError as exc:
            outcome_counters.increment(Outcome.RECONCILE_FAILED)
            logger.warning("Fubon filled-trades reconciliation rejected reason=%s", exc.reason)
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

    return application


app = create_app()
