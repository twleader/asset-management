from __future__ import annotations

import hashlib
import hmac
import json
from dataclasses import replace
from datetime import UTC, datetime

import pytest

from fubon_broker_service.bank_balance import BankBalanceError, BankBalanceService
from accounting_fixtures import NOW, SELECTED, AccountingGateway, bank_row
from helpers import TOKEN


def service(row=None, **kwargs):
    gateway = AccountingGateway(bank_row() if row is None else row, **kwargs)
    return BankBalanceService(gateway, now=lambda: NOW), gateway


def test_official_result_zero_balance_and_no_identity_leakage():
    svc, gateway = service(bank_row(balance=0, available_balance="0"))
    result = svc.read()
    expected = hmac.new(TOKEN.encode(), b"001:00001234567", hashlib.sha256).hexdigest()[:24]
    assert result == {"queryDate": "2026-08-28", "observedAt": "2026-08-28T05:40:00Z",
                      "accountFingerprint": expected, "currency": "TWD", "balance": "0", "availableBalance": "0"}
    assert gateway.calls == 1
    assert SELECTED.account_number not in json.dumps(result)
    assert TOKEN not in json.dumps(result)
    assert "sourceDate" not in result


@pytest.mark.parametrize("value", [0, "0", 1, "100000", 10**20-1, str(10**20-1)])
def test_official_nonnegative_integer_shapes_and_bounds(value):
    svc, _ = service(bank_row(balance=value, available_balance=value))
    assert svc.read()["balance"] == str(value)


@pytest.mark.parametrize("field", ["balance", "available_balance"])
@pytest.mark.parametrize("value", [None, True, False, -1, "-1", "1e5", "+1", "01", " 1", "1 ", "1.0", 1.0, "NaN", float("inf"), 10**20])
def test_invalid_amount_never_normalizes(field, value):
    svc, _ = service(bank_row(**{field: value}))
    with pytest.raises(BankBalanceError, match="RECONCILE_FAILED"):
        svc.read()


@pytest.mark.parametrize("field,value", [("branch_no",None),("branch_no",""),("branch_no","002"),("account",None),("account",""),("account","other"),("currency","USD"),("currency",None)])
def test_identity_and_currency_are_required_exact_fields(field, value):
    svc, _ = service(bank_row(**{field:value}))
    with pytest.raises(BankBalanceError, match="RECONCILE_FAILED"):
        svc.read()


@pytest.mark.parametrize("field", ["branch_no", "account", "currency", "balance", "available_balance"])
def test_missing_source_field_rejected(field):
    row=bank_row(); del row[field]
    svc, _ = service(row)
    with pytest.raises(BankBalanceError, match="RECONCILE_FAILED"):
        svc.read()


@pytest.mark.parametrize("data,envelope,success", [(bank_row(),False,True),(None,True,True),([],True,True),(bank_row(),True,False)])
def test_bare_or_failed_or_wrong_result_is_not_accepted(data, envelope, success):
    gateway=AccountingGateway(data,envelope=envelope,success=success)
    with pytest.raises(BankBalanceError, match="RECONCILE_FAILED"):
        BankBalanceService(gateway,now=lambda:NOW).read()


@pytest.mark.parametrize("selected", [replace(SELECTED,branch_no=""),replace(SELECTED,account_number="")])
def test_selected_identity_cannot_be_empty_even_if_source_is_empty(selected):
    svc,_=service(bank_row(branch_no=selected.branch_no,account=selected.account_number),selected=selected)
    with pytest.raises(BankBalanceError,match="RECONCILE_FAILED"):
        svc.read()


def test_account_fingerprint_uses_captured_token_not_an_external_token():
    svc,_=service(token="rotated-fake-token")
    assert svc.read()["accountFingerprint"] == hmac.new(b"rotated-fake-token",b"001:00001234567",hashlib.sha256).hexdigest()[:24]


def test_cross_midnight_read_is_stale_not_a_new_source_date():
    times=iter([datetime(2026,8,28,15,59,59,tzinfo=UTC),datetime(2026,8,28,16,0,0,tzinfo=UTC)])
    with pytest.raises(BankBalanceError,match="STALE_QUERY"):
        BankBalanceService(AccountingGateway(bank_row()),now=lambda:next(times)).read()
