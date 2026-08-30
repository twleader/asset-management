from __future__ import annotations

import hashlib
import hmac
from datetime import date, datetime

from .normalization import TAIPEI, instant, observed_at
from .sdk_gateway import AccountingRead, SelectedAccount, raw_field


def checked_result(read: AccountingRead) -> object:
    if not isinstance(read, AccountingRead):
        raise ValueError("RECONCILE_FAILED")
    account = read.account
    if not isinstance(account, SelectedAccount) or not all(
        isinstance(value, str) and value
        for value in (account.branch_no, account.account_number, read.internal_token)
    ):
        raise ValueError("RECONCILE_FAILED")
    if raw_field(read.response, "is_success") is not True:
        raise ValueError("RECONCILE_FAILED")
    data = raw_field(read.response, "data")
    if data is None:
        raise ValueError("RECONCILE_FAILED")
    return data


def verify_identity(source: object, account: SelectedAccount) -> None:
    branch, number = raw_field(source, "branch_no"), raw_field(source, "account")
    if not all(isinstance(value, str) and value for value in (
        branch, number, account.branch_no, account.account_number
    )) or branch != account.branch_no or number != account.account_number:
        raise ValueError("RECONCILE_FAILED")


def fingerprint(read: AccountingRead) -> str:
    return hmac.new(
        read.internal_token.encode("utf-8"),
        (read.account.branch_no + ":" + read.account.account_number).encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()[:24]


def observation(read: AccountingRead, started: datetime, finished: datetime) -> dict[str, object]:
    start, end = instant(started), instant(finished)
    query_date: date = start.astimezone(TAIPEI).date()
    if end < start or end.astimezone(TAIPEI).date() != query_date:
        raise ValueError("STALE_QUERY")
    return {"queryDate": query_date.isoformat(), "observedAt": observed_at(end),
            "accountFingerprint": fingerprint(read)}
