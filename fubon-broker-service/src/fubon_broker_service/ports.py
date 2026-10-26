"""Consumer-owned read capabilities, structurally implemented by outer SDK adapters.

No protocol grants access to the SDK root, session internals or broker writes.
Each consumer receives only the fixed operations it already uses.
"""
from __future__ import annotations

from collections.abc import Callable
from typing import Protocol, runtime_checkable

from .captures import AccountingPair, AccountingRead, StockPushConnection
from .config_values import ConfigSnapshot


@runtime_checkable
class PortfolioReader(Protocol):
    def read_accounting_pair(self, *, deadline: float | None = None) -> AccountingPair:
        ...


@runtime_checkable
class TradeReader(Protocol):
    def read_filled_trades(self, start_date: str, end_date: str, *, deadline: float | None = None) -> AccountingRead:
        ...


@runtime_checkable
class BankBalanceReader(Protocol):
    def read_bank_balance(self, *, deadline: float | None = None) -> AccountingRead:
        ...


@runtime_checkable
class SettlementReader(Protocol):
    def read_settlement(self, range_param: str = "3d", *, deadline: float | None = None) -> AccountingRead:
        ...


@runtime_checkable
class RealizedGainReader(Protocol):
    def read_realized_gains(self, *, deadline: float | None = None) -> AccountingRead:
        ...


@runtime_checkable
class QuoteReader(Protocol):
    def quote(
        self, code: str, *, deadline: float | None = None,
        before_dispatch: Callable[[], None] | None = None,
    ) -> object:
        ...


@runtime_checkable
class EtfHoldingsReader(Protocol):
    def read_etf_holdings(self, symbol: str) -> object:
        ...


@runtime_checkable
class DividendReader(Protocol):
    def read_dividends(self, start_date: str, end_date: str) -> object:
        ...


@runtime_checkable
class TechnicalIndicatorReader(Protocol):
    def read_technical_indicator(
        self, kind: str, symbol: str, start_date: str, end_date: str, *,
        timeframe: str = "D", parameters: dict[str, object] | None = None,
        deadline: float | None = None,
    ) -> object:
        ...


@runtime_checkable
class MarketDataV1Source(Protocol):
    def read_ticker(self, symbol: str, *, deadline: float | None = None) -> object:
        ...

    def read_intraday_candles(self, symbol: str, *, deadline: float | None = None) -> object:
        ...

    def read_intraday_volumes(self, symbol: str, *, deadline: float | None = None) -> object:
        ...

    def read_historical_daily_candles(
        self, symbol: str, start_date: str, end_date: str, *, deadline: float | None = None,
    ) -> object:
        ...

    def read_historical_intraday_candles(
        self, symbol: str, start_date: str, end_date: str, *, deadline: float | None = None,
    ) -> object:
        ...


@runtime_checkable
class IndexStreamGateway(Protocol):
    @property
    def session_generation(self) -> int:
        ...

    def verify_taiex_index_symbol(self, symbol: str) -> None:
        ...

    def realtime_stock_websocket(self) -> object:
        ...


@runtime_checkable
class StockPushGateway(Protocol):
    @property
    def session_generation(self) -> int:
        ...

    def current_config(self) -> ConfigSnapshot:
        ...

    def stock_push_websocket(self) -> StockPushConnection:
        ...

    def stock_push_call(
        self, client: object, method_name: str, payload: dict[str, object] | None = None,
    ) -> None:
        ...

    def release_stock_push_websocket(self, client: object | None) -> None:
        ...
