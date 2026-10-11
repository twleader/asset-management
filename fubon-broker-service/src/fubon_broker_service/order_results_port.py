"""Neutral read port. Raw SDK account/token/native objects stop at the outer adapter."""
from __future__ import annotations
from dataclasses import dataclass, field
from datetime import date
from typing import Mapping, Protocol

class OrderResultsError(ValueError):
    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason

@dataclass(frozen=True)
class OrderPortfolioCapture:
    query_date: date
    account_fingerprint: str
    orders: tuple[Mapping[str, object], ...] = field(repr=False)
    portfolio: Mapping[str, object] = field(repr=False)

class OrderResultsPort(Protocol):
    def read_order_portfolio(self, query_date: date) -> OrderPortfolioCapture: ...
