"""Ephemeral read captures. Raw broker values never belong in repr or transport."""
from __future__ import annotations

from dataclasses import dataclass, field


@dataclass(frozen=True)
class SelectedAccount:
    raw: object = field(repr=False)
    branch_no: str = field(repr=False)
    account_number: str = field(repr=False)
    selector_explicit: bool = False


@dataclass(frozen=True)
class AccountingRead:
    response: object = field(repr=False)
    account: SelectedAccount = field(repr=False)
    internal_token: str = field(repr=False)


@dataclass(frozen=True)
class AccountingPair:
    inventories: object = field(repr=False)
    unrealized: object = field(repr=False)
    account: SelectedAccount = field(repr=False)
    internal_token: str = field(repr=False)


@dataclass(frozen=True)
class OrderPortfolioRead:
    orders: AccountingRead = field(repr=False)
    portfolio: AccountingPair = field(repr=False)


@dataclass(frozen=True)
class StockPushConnection:
    client: object = field(repr=False)
    generation: int
