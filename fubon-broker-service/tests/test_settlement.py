from __future__ import annotations

import json
from dataclasses import replace
from datetime import UTC, datetime

import pytest

from fubon_broker_service.settlement import AMOUNT_FIELDS, SettlementError, SettlementService
from accounting_fixtures import NOW, SELECTED, AccountingGateway, settlement_data, settlement_row


def service(rows, **identity):
    gateway=AccountingGateway(settlement_data(rows,**identity))
    return SettlementService(gateway,now=lambda:NOW)


def test_official_envelope_signed_zero_and_all_twelve_values_is_explicitly_bound():
    result=service([settlement_row()]).read()
    assert result["accountBindingExplicit"] is True
    assert result["coverageStatus"] == "SDK_RANGE_3D_RETURNED_ROWS"
    assert result["reason"] is None
    row=result["details"][0]
    assert row["sourceQueryDate"] == "2026-08-27"
    assert row["settlementDate"] == "2026-08-31"
    assert row["buySettlement"] == "-1002" and row["sellSettlement"] == "0"
    assert set(row)=={"status","sourceQueryDate","settlementDate","currency",*AMOUNT_FIELDS.values()}
    assert len(result["accountFingerprint"]) == 24
    assert SELECTED.account_number not in json.dumps(result)
    assert SELECTED.branch_no not in json.dumps(result)


def test_empty_and_all_none_placeholder_are_observations_not_zero_or_complete():
    assert service([]).read()["details"] == []
    raw={"date":"2026/08/28","settlement_date":None,"currency":None,**{name:None for name in AMOUNT_FIELDS}}
    body=service([raw]).read()
    assert body["coverageStatus"]=="SDK_RANGE_3D_RETURNED_ROWS"
    assert body["details"][0]["status"]=="NO_DATA_OBSERVED"
    assert body["details"][0]["buySettlement"] is None


@pytest.mark.parametrize("field", [*AMOUNT_FIELDS,"currency","settlement_date"])
def test_partial_null_or_missing_field_is_not_a_placeholder(field):
    raw=settlement_row(); raw[field]=None
    with pytest.raises(SettlementError,match="RECONCILE_FAILED"):
        service([raw]).read()
    del raw[field]
    with pytest.raises(SettlementError,match="RECONCILE_FAILED"):
        service([raw]).read()


@pytest.mark.parametrize("field", list(AMOUNT_FIELDS))
@pytest.mark.parametrize("bad", [True,"1e4","0.1",10**20])
def test_every_amount_requires_an_exact_bounded_integer(field,bad):
    with pytest.raises(SettlementError,match="RECONCILE_FAILED"):
        service([settlement_row(**{field:bad})]).read()


@pytest.mark.parametrize("changes", [{"buy_settlement":1},{"sell_settlement":-1},{"total_settlement_amount":-1003},{"currency":"USD"},{"date":"2026-08-27"},{"date":"2026/08/29"},{"date":"2026/02/30"},{"settlement_date":"2026-08-31"},{"settlement_date":"2026/08/26"}])
def test_sign_dates_currency_and_total_are_not_guessed(changes):
    with pytest.raises(SettlementError,match="RECONCILE_FAILED"):
        service([settlement_row(**changes)]).read()


def test_same_or_past_nonzero_and_duplicate_settlement_fail_whole_batch():
    with pytest.raises(SettlementError,match="AMBIGUOUS_SETTLEMENT"):
        service([settlement_row(settlement_date="2026/08/28")]).read()
    with pytest.raises(SettlementError,match="AMBIGUOUS_SETTLEMENT"):
        service([settlement_row(settlement_date="2026/08/27")]).read()
    with pytest.raises(SettlementError,match="AMBIGUOUS_SETTLEMENT"):
        service([settlement_row(),settlement_row(date="2026/08/28")]).read()


def test_same_day_zero_is_valid_observation_without_claiming_a_complete_range():
    result=service([settlement_row(settlement_date="2026/08/28",buy_settlement=0,total_settlement_amount=0)]).read()
    assert result["details"][0]["buySettlement"]=="0"
    assert result["coverageStatus"]=="SDK_RANGE_3D_RETURNED_ROWS"


def test_other_numeric_totals_are_retained_without_invented_fee_formulas():
    raw=settlement_row(buy_value=-7,buy_fee=-2,sell_tax=-3,total_fee=-4,total_bs_value=-5,total_tax=-6)
    normalized=service([raw]).read()["details"][0]
    assert normalized["buyValue"]=="-7" and normalized["totalFee"]=="-4"


@pytest.mark.parametrize("identity", [{"account":None},{"account":""},{"account":"other"},{"branch_no":None},{"branch_no":""},{"branch_no":"002"}])
def test_nested_response_account_is_required(identity):
    with pytest.raises(SettlementError,match="RECONCILE_FAILED"):
        service([],**identity).read()


@pytest.mark.parametrize("row_identity", [
    {"branch_no": "002", "account": SELECTED.account_number},
    {"branch_no": SELECTED.branch_no, "account": "other"},
    {"branch_no": SELECTED.branch_no},
    {"account": SELECTED.account_number},
])
def test_repeated_detail_identity_when_present_must_match_selected_account(row_identity):
    with pytest.raises(SettlementError,match="RECONCILE_FAILED"):
        service([settlement_row(**row_identity)]).read()


def test_binding_is_a_real_false_boolean_without_a_configured_selector_pair():
    selected = replace(SELECTED, selector_explicit=False)
    result = SettlementService(
        AccountingGateway(settlement_data([settlement_row()]), selected=selected), now=lambda: NOW
    ).read()
    assert result["accountBindingExplicit"] is False
    assert type(result["accountBindingExplicit"]) is bool


def test_binding_is_false_when_selected_raw_identity_no_longer_matches_capture():
    selected = replace(SELECTED, raw={"branch_no": "002", "account": SELECTED.account_number})
    result = SettlementService(
        AccountingGateway(settlement_data([settlement_row()]), selected=selected), now=lambda: NOW
    ).read()
    assert result["accountBindingExplicit"] is False


@pytest.mark.parametrize("data,envelope,success", [(settlement_data([]),False,True),(None,True,True),({"details":[]},True,True),(settlement_data([]),True,False)])
def test_only_official_success_result_is_accepted(data,envelope,success):
    gateway=AccountingGateway(data,envelope=envelope,success=success)
    with pytest.raises(SettlementError,match="RECONCILE_FAILED"):
        SettlementService(gateway,now=lambda:NOW).read()


def test_multiple_distinct_source_rows_preserve_their_real_dates():
    body=service([settlement_row(),settlement_row(date="2026/08/26",settlement_date="2026/08/30")]).read()
    assert [r["sourceQueryDate"] for r in body["details"]]==["2026-08-27","2026-08-26"]


def test_cross_day_response_is_rejected():
    times=iter([datetime(2026,8,28,15,59,59,tzinfo=UTC),datetime(2026,8,28,16,0,0,tzinfo=UTC)])
    with pytest.raises(SettlementError,match="STALE_QUERY"):
        SettlementService(AccountingGateway(settlement_data([])),now=lambda:next(times)).read()
