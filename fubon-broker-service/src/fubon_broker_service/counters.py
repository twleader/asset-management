from __future__ import annotations

from collections import Counter
from enum import StrEnum
from threading import Lock


class Outcome(StrEnum):
    DISABLED = "DISABLED"
    MISCONFIGURED = "MISCONFIGURED"
    CALENDAR_UNKNOWN = "CALENDAR_UNKNOWN"
    ACCOUNTING_FAILED = "ACCOUNTING_FAILED"
    RECONCILE_FAILED = "RECONCILE_FAILED"
    QUOTE_FAILED = "QUOTE_FAILED"
    NO_OWNER = "NO_OWNER"
    NO_TODAY_SNAPSHOT = "NO_TODAY_SNAPSHOT"
    BROKER_MISSING = "BROKER_MISSING"
    DRY_RUN = "DRY_RUN"
    SUCCESS = "SUCCESS"
    EMPTY_CLEARED = "EMPTY_CLEARED"
    ROLLED_BACK = "ROLLED_BACK"


class OutcomeCounters:
    """Fixed-cardinality process-local counters; identities never become keys."""

    def __init__(self) -> None:
        self._values = Counter({outcome.value: 0 for outcome in Outcome})
        self._lock = Lock()

    def increment(self, outcome: Outcome) -> None:
        with self._lock:
            self._values[outcome.value] += 1

    def snapshot(self) -> dict[str, int]:
        with self._lock:
            return {outcome.value: self._values[outcome.value] for outcome in Outcome}
