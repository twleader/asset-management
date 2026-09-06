from __future__ import annotations

import json
from dataclasses import replace
from datetime import UTC, datetime

import pytest

from fubon_broker_service.realized_gain import RealizedGainError, RealizedGainService
from accounting_fixtures import NOW, SELECTED, AccountingGateway, realized_row


def service(rows, **kwargs):
    return RealizedGainService(AccountingGateway(rows,**kwargs),now=lambda:NOW)


def test_official_result_exports_only_verified_fields_and_source_date():
    body=service([realized_row()]).read()
    assert body["rows"]==[{"stockNo":"2330","buySell":"Sell","orderType":"Stock","filledQty":1000,"filledPrice":"123.5","realizedProfit":"0","realizedLoss":"20","sourceDate":"2026-08-27"}]
    assert body["queryDate"]=="2026-08-28" and len(body["accountFingerprint"])==24
    assert body["accountBindingExplicit"] is True
    assert SELECTED.account_number not in json.dumps(body)
    assert SELECTED.branch_no not in json.dumps(body)
    assert not {"tradeDate","filledNo","proceeds","investmentCost"}.intersection(body["rows"][0])


@pytest.mark.parametrize("profit,loss", [(0,0),(20,0),(0,20),("0","0"),(str(10**20-1),0)])
def test_nonnegative_integer_profit_loss_allow_zero(profit,loss):
    row=service([realized_row(realized_profit=profit,realized_loss=loss)]).read()["rows"][0]
    assert row["realizedProfit"]==str(profit)
    assert row["realizedLoss"]==str(loss)


def test_both_profit_and_loss_is_a_named_semantic_rejection():
    with pytest.raises(RealizedGainError,match="ACCOUNTING_SEMANTICS_UNVERIFIED"):
        service([realized_row(realized_profit=1,realized_loss=1)]).read()


@pytest.mark.parametrize("field,value", [("stock_no",""),("stock_no"," "),("stock_no","0000"),("stock_no","2330 "),("buy_sell","Buy"),("order_type",None),("order_type","Margin"),("account",None),("account",""),("account","other"),("branch_no",None),("branch_no",""),("branch_no","002"),("date","2026-08-27"),("date","2026/02/30"),("date","2026/08/29")])
def test_identity_type_date_or_code_failure_poison_entire_batch(field,value):
    with pytest.raises(RealizedGainError,match="RECONCILE_FAILED"):
        service([realized_row(),realized_row(**{field:value})]).read()


@pytest.mark.parametrize("field", ["order_type","account","branch_no","date","stock_no"])
def test_required_fields_cannot_be_omitted(field):
    row=realized_row(); del row[field]
    with pytest.raises(RealizedGainError,match="RECONCILE_FAILED"):
        service([row]).read()


@pytest.mark.parametrize("field", ["realized_profit","realized_loss"])
@pytest.mark.parametrize("value", [None,True,-1,"-1",1.0,"1.0","1e4","NaN",float("inf"),10**20])
def test_profit_loss_reject_noncanonical_or_noninteger_values(field,value):
    with pytest.raises(RealizedGainError,match="RECONCILE_FAILED"):
        service([realized_row(**{field:value})]).read()


@pytest.mark.parametrize("field,value", [("filled_price",0),("filled_price",-1),("filled_price","NaN"),("filled_price","1e20"),("filled_price","123456789012345678901"),("filled_price","1.12345678901"),("filled_qty",0),("filled_qty",-1),("filled_qty",True),("filled_qty",10**10)])
def test_price_qty_bounds(field,value):
    with pytest.raises(RealizedGainError,match="RECONCILE_FAILED"):
        service([realized_row(**{field:value})]).read()


@pytest.mark.parametrize("qty", [1,9999999999])
def test_qty_inclusive_edges(qty):
    assert service([realized_row(filled_qty=qty)]).read()["rows"][0]["filledQty"]==qty


def test_empty_rows_never_suggest_delete_or_a_financial_mapping():
    body=service([]).read()
    assert body["rows"]==[] and "proceeds" not in body


def test_binding_is_a_real_false_boolean_without_a_configured_selector_pair():
    selected = replace(SELECTED, selector_explicit=False)
    body = RealizedGainService(AccountingGateway([], selected=selected), now=lambda: NOW).read()
    assert body["accountBindingExplicit"] is False
    assert type(body["accountBindingExplicit"]) is bool


def test_binding_is_false_when_selected_raw_identity_no_longer_matches_capture():
    selected = replace(SELECTED, raw={"branch_no": "002", "account": SELECTED.account_number})
    body = RealizedGainService(
        AccountingGateway([realized_row()], selected=selected), now=lambda: NOW
    ).read()
    assert body["accountBindingExplicit"] is False


@pytest.mark.parametrize("rows,envelope,success", [([],False,True),(None,True,True),({},True,True),([],True,False)])
def test_bare_failed_or_wrong_data_shape_is_rejected(rows,envelope,success):
    with pytest.raises(RealizedGainError,match="RECONCILE_FAILED"):
        service(rows,envelope=envelope,success=success).read()


def test_cross_day_accounting_observation_is_rejected():
    times=iter([datetime(2026,8,28,15,59,59,tzinfo=UTC),datetime(2026,8,28,16,0,0,tzinfo=UTC)])
    with pytest.raises(RealizedGainError,match="STALE_QUERY"):
        RealizedGainService(AccountingGateway([]),now=lambda:next(times)).read()
