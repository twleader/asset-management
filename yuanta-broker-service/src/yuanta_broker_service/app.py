from __future__ import annotations

import logging
import platform
from contextlib import asynccontextmanager
from typing import Annotated

from fastapi import Depends, FastAPI, Header, HTTPException
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from .config import ConfigLoader, ConfigSnapshot
from .models import (
    AccountingReadRequest,
    CodesRequest,
    KlineRequest,
    OrderExecutionReportRequest,
    RealizedPnlRequest,
    SingleCodeRequest,
)
from .sdk_gateway import FieldValidationError, SdkCallError, YuantaBrokerGateway, normalize_code, normalize_codes
from .security import INTERNAL_TOKEN_HEADER, install_log_redaction, redact_mapping, token_matches


logger = logging.getLogger(__name__)

# Central last line of defense: even if a future call site logs a raw SDK
# exception or stringifies a credential field, the record is redacted before
# it reaches any handler. Installed on every logger this service actually
# uses (module-level loggers are per-module and do not inherit filters from
# ancestors during propagation, so each one is attached explicitly).
install_log_redaction(logger, logging.getLogger("yuanta_broker_service.sdk_gateway"))


def create_app(
    config_loader: ConfigLoader | None = None,
    gateway: YuantaBrokerGateway | None = None,
) -> FastAPI:
    loader = config_loader or ConfigLoader.from_environment()
    sdk_gateway = gateway or YuantaBrokerGateway()

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        yield
        sdk_gateway.shutdown()

    application = FastAPI(
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
        lifespan=lifespan,
    )
    application.state.config_loader = loader
    application.state.sdk_gateway = sdk_gateway

    @application.middleware("http")
    async def sanitized_exception_boundary(request, call_next):
        try:
            return await call_next(request)
        except Exception:
            # Never let an SDK/raw provider exception reach the ASGI server logger or response.
            logger.error("Yuanta request failed reason=INTERNAL_FAILURE")
            return JSONResponse(status_code=503, content=redact_mapping({"reason": "INTERNAL_FAILURE"}))

    @application.exception_handler(RequestValidationError)
    async def validation_error_handler(_request, _exc):
        return JSONResponse(status_code=400, content=redact_mapping({"reason": "INVALID_REQUEST"}))

    def runtime_misconfigured(config: ConfigSnapshot) -> bool:
        return sdk_gateway.runtime_misconfigured_for(config)

    def effective_state(config: ConfigSnapshot) -> str:
        if config.enabled and config.state == "READY" and runtime_misconfigured(config):
            return "MISCONFIGURED"
        return config.state

    def authorize(
        tokens: Annotated[list[str] | None, Header(alias=INTERNAL_TOKEN_HEADER)] = None,
    ) -> ConfigSnapshot:
        config = loader.load()
        state = effective_state(config)
        if state != "READY":
            # Reason is the coarse configState itself (NOT_CONFIGURED/MISCONFIGURED),
            # not the granular presence-check detail — Task 364.7/364.8 assert on
            # exactly these two values for every token-gated route.
            raise HTTPException(status_code=503, detail=redact_mapping({"reason": state}))
        if tokens is None or len(tokens) != 1 or not tokens[0]:
            raise HTTPException(status_code=401, detail=redact_mapping({"reason": "TOKEN_REQUIRED"}))
        token = tokens[0]
        if not config.internal_service_token or not token_matches(config.internal_service_token, token):
            raise HTTPException(status_code=403, detail=redact_mapping({"reason": "TOKEN_INVALID"}))
        return config

    def sdk_call_failure(exc: SdkCallError) -> HTTPException:
        logger.warning("Yuanta SDK call failed reason=%s", exc.reason)
        return HTTPException(status_code=503, detail=redact_mapping({"reason": exc.reason}))

    def invalid_request(exc: FieldValidationError) -> HTTPException:
        return HTTPException(status_code=400, detail=redact_mapping({"reason": str(exc)}))

    @application.get("/internal/health")
    def health() -> dict[str, object]:
        config = loader.load()
        return {
            "status": "UP",
            "configState": effective_state(config),
            # There is no non-secret, non-lazy way to read a real SPARK
            # component version without loading the DLL (forbidden here —
            # health must stay side-effect free), so this is always null
            # until a real account/DLL is available.
            "sdkComponentVersion": None,
            "platform": platform.machine(),
        }

    @application.get("/internal/config")
    def config_endpoint(config: ConfigSnapshot = Depends(authorize)) -> dict[str, object]:
        ready = effective_state(config) == "READY"
        return {
            "configState": effective_state(config),
            "presence": config.presence.public_dict(),
            "capabilities": {"accounting": ready, "marketData": ready, "reports": ready},
        }

    # --- accounting (364.7) -------------------------------------------------

    @application.post("/internal/accounting/inventory-stock")
    def inventory_stock(_body: AccountingReadRequest, config: ConfigSnapshot = Depends(authorize)) -> dict[str, object]:
        try:
            return sdk_gateway.get_stock_inventory(config)
        except SdkCallError as exc:
            raise sdk_call_failure(exc) from None

    @application.post("/internal/accounting/inventory-futures")
    def inventory_futures(_body: AccountingReadRequest, config: ConfigSnapshot = Depends(authorize)) -> dict[str, object]:
        try:
            return sdk_gateway.get_futures_inventory(config)
        except SdkCallError as exc:
            raise sdk_call_failure(exc) from None

    @application.post("/internal/accounting/unrealized-pnl")
    def unrealized_pnl(_body: AccountingReadRequest, config: ConfigSnapshot = Depends(authorize)) -> dict[str, object]:
        try:
            return sdk_gateway.get_unrealized_pnl(config)
        except SdkCallError as exc:
            raise sdk_call_failure(exc) from None

    @application.post("/internal/accounting/realized-pnl")
    def realized_pnl(body: RealizedPnlRequest, config: ConfigSnapshot = Depends(authorize)) -> dict[str, object]:
        try:
            return sdk_gateway.get_realized_pnl(config, body.startDate, body.endDate)
        except SdkCallError as exc:
            raise sdk_call_failure(exc) from None

    @application.post("/internal/accounting/settlement")
    def settlement(_body: AccountingReadRequest, config: ConfigSnapshot = Depends(authorize)) -> dict[str, object]:
        try:
            return sdk_gateway.get_settlement(config)
        except SdkCallError as exc:
            raise sdk_call_failure(exc) from None

    @application.post("/internal/accounting/futures-margin")
    def futures_margin(_body: AccountingReadRequest, config: ConfigSnapshot = Depends(authorize)) -> dict[str, object]:
        try:
            return sdk_gateway.get_futures_margin(config)
        except SdkCallError as exc:
            raise sdk_call_failure(exc) from None

    # --- market data (364.8) -------------------------------------------------

    @application.post("/internal/market-data/quote")
    def quote(body: CodesRequest, config: ConfigSnapshot = Depends(authorize)) -> dict[str, object]:
        try:
            codes = normalize_codes(body.codes)
        except FieldValidationError as exc:
            raise invalid_request(exc) from None
        try:
            return sdk_gateway.get_quote(config, codes)
        except SdkCallError as exc:
            raise sdk_call_failure(exc) from None

    @application.post("/internal/market-data/five-best")
    def five_best(body: CodesRequest, config: ConfigSnapshot = Depends(authorize)) -> dict[str, object]:
        try:
            codes = normalize_codes(body.codes)
        except FieldValidationError as exc:
            raise invalid_request(exc) from None
        try:
            return sdk_gateway.get_five_best(config, codes)
        except SdkCallError as exc:
            raise sdk_call_failure(exc) from None

    @application.post("/internal/market-data/intraday-ticks")
    def intraday_ticks(body: SingleCodeRequest, config: ConfigSnapshot = Depends(authorize)) -> dict[str, object]:
        try:
            code = normalize_code(body.code)
        except FieldValidationError as exc:
            raise invalid_request(exc) from None
        try:
            return sdk_gateway.get_intraday_ticks(config, code)
        except SdkCallError as exc:
            raise sdk_call_failure(exc) from None

    @application.post("/internal/market-data/kline")
    def kline(body: KlineRequest, config: ConfigSnapshot = Depends(authorize)) -> dict[str, object]:
        try:
            code = normalize_code(body.code)
        except FieldValidationError as exc:
            raise invalid_request(exc) from None
        try:
            return sdk_gateway.get_kline(config, code, body.interval, body.count, body.startDate, body.endDate)
        except SdkCallError as exc:
            raise sdk_call_failure(exc) from None

    @application.post("/internal/market-data/instrument-info")
    def instrument_info(body: SingleCodeRequest, config: ConfigSnapshot = Depends(authorize)) -> dict[str, object]:
        try:
            code = normalize_code(body.code)
        except FieldValidationError as exc:
            raise invalid_request(exc) from None
        try:
            return sdk_gateway.get_instrument_info(config, code)
        except SdkCallError as exc:
            raise sdk_call_failure(exc) from None

    # --- reports (364.9) -------------------------------------------------

    @application.post("/internal/reports/order-execution")
    def order_execution_report(
        body: OrderExecutionReportRequest, config: ConfigSnapshot = Depends(authorize)
    ) -> dict[str, object]:
        try:
            return sdk_gateway.get_order_execution_report(config, body.startDate, body.endDate)
        except SdkCallError as exc:
            raise sdk_call_failure(exc) from None

    return application


app = create_app()
