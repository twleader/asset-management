from datetime import datetime
from types import SimpleNamespace
import time
import pytest
from fastapi.testclient import TestClient
from fubon_broker_service.order_results import OrderResultsService, OrderResultsError
from fubon_broker_service.order_results_adapter import OrderResultsGatewayAdapter
from fubon_broker_service.sdk_gateway import SdkGateway, SdkCallError, AccountingRead, AccountingPair, OrderPortfolioRead, SelectedAccount
from fubon_broker_service.app import create_app
from helpers import ready_config, response, account, TOKEN

NOW=datetime.fromisoformat('2026-08-21T10:00:00+08:00')
def row(**updates):
    result=dict(date='2026/08/21',account='00001234567',branch_no='001',stock_no='00679B',order_no='A123',seq_no='1234',asset_type=0,market='TAIEX',market_type='Common',buy_sell='Buy',price_type='Limit',price='28.1',quantity=3000,time_in_force='ROD',order_type='Stock',is_pre_order=False,status=10,after_price_type=None,after_price='28.1',unit=1000,after_qty=2000,filled_qty=1000,filled_money=28100,last_time='09:20:00.123')
    result.update(updates);return result
class PairGateway:
    def __init__(self,rows,success=True):self.rows=rows;self.success=success;self.calls=0
    def read_order_portfolio(self):
        self.calls+=1;a=SelectedAccount(account(),'001','00001234567')
        return OrderPortfolioRead(AccountingRead(response(self.rows,self.success),a,TOKEN),AccountingPair(response([]),response([]),a,TOKEN))
def service(rows,success=True):return OrderResultsService(OrderResultsGatewayAdapter(PairGateway(rows,success)),now=lambda:NOW)
def test_partial_fill_uses_after_qty_and_preserves_unlinked_facts():
    payload=service([row()]).read();r=payload['orders'][0]
    assert r['quantity']==3000 and r['after_qty']==2000 and r['filled_qty']==1000 and r['remainingQty']==1000
    assert r['recommendationLegId'] is None and r['fillIds']==[] and payload['verifiedZero'] is False
    assert '00001234567' not in str(payload) and 'branch_no' not in r and 'account' not in r
@pytest.mark.parametrize('status,filled,effective',[(30,0,0),(40,1000,1000),(50,2000,2000),(90,0,0)])
def test_terminal_status_has_no_active_pending_but_keeps_fills(status,filled,effective):
    payload=service([row(status=status,filled_qty=filled,after_qty=effective)]).read()
    assert payload['verifiedZero'] is True and payload['orders'][0]['filled_qty']==filled
    assert payload['orders'][0]['remainingQty']==0

def test_complete_empty_is_known_zero_for_exact_selected_account():
    payload=service([]).read();assert payload['complete'] and payload['verifiedZero'] and payload['activeCount']==0
    assert payload['accountFingerprint']==payload['portfolio']['accountFingerprint']
@pytest.mark.parametrize('change',[
    dict(status=4),dict(status=8),dict(status=9),dict(status=999),dict(status=True),
    dict(filled_qty=2001),dict(quantity='3000.1'),dict(after_qty=-1),dict(unit=1),
    dict(account='OTHER'),dict(branch_no='OTHER'),dict(date='2026/08/20'),dict(order_no=''),
    dict(market_type='Fixing'),dict(order_type='Margin'),dict(price_type='Market'),dict(after_price='NaN'),
    dict(is_pre_order=True),dict(last_time='25:00:00'),dict(status=30,filled_qty=1000),dict(status=50,filled_qty=1),
])
def test_ambiguous_or_wrong_identity_never_becomes_zero(change):
    with pytest.raises(OrderResultsError):service([row(**change)]).read()
def test_permission_denial_and_duplicate_are_fail_closed():
    with pytest.raises(OrderResultsError):service([],False).read()
    with pytest.raises(OrderResultsError):service([row(),row()]).read()
class Sdk:
    def __init__(self,delay=0):
        self.events=[];self.delay=delay
        self.stock=SimpleNamespace(get_order_results=self.orders)
        self.accounting=SimpleNamespace(inventories=self.inventories,unrealized_gains_and_loses=self.unrealized)
        self.marketdata=SimpleNamespace(rest_client=SimpleNamespace(stock=object()),websocket_client=None)
    def apikey_login(self,*args):self.events.append('login');return response([account()])
    def init_realtime(self,*args):pass
    def orders(self,a):self.events.append('get_order_results');time.sleep(self.delay);return response([])
    def inventories(self,a):self.events.append('inventories');return response([])
    def unrealized(self,a):self.events.append('unrealized_gains_and_loses');return response([])
    def logout(self):pass

def test_real_gateway_exact_read_allowlist_no_write_namespace_access(tmp_path):
    sdk=Sdk();gateway=SdkGateway(ready_config(tmp_path),sdk_factory=lambda:sdk,normal_mode_factory=lambda:'NORMAL')
    result=gateway.read_order_portfolio()
    assert result.orders.account==result.portfolio.account
    assert sdk.events==['login','get_order_results','inventories','unrealized_gains_and_loses']
    with pytest.raises(SdkCallError):gateway._read_accounting('cancel_order')

def test_shared_bounded_timeout_does_not_continue_to_inventory(tmp_path):
    sdk=Sdk(.15);gateway=SdkGateway(ready_config(tmp_path),sdk_factory=lambda:sdk,normal_mode_factory=lambda:'NORMAL')
    gateway.ACCOUNT_CALL_TIMEOUT_SECONDS=.01
    with pytest.raises(SdkCallError,match='ORDER_RESULTS_TIMEOUT'):gateway.read_order_portfolio()
    assert sdk.events==['login','get_order_results']

def test_exact_internal_route_token_body_query_method_before_read(tmp_path):
    gate=PairGateway([]);svc=OrderResultsService(OrderResultsGatewayAdapter(gate),now=lambda:NOW)
    app=create_app(ready_config(tmp_path),order_results_service=svc)
    with TestClient(app) as client:
        path='/internal/orders/read';h={'X-Internal-Service-Token':TOKEN}
        assert client.post(path).status_code==401
        assert client.post(path,headers={'X-Internal-Service-Token':'wrong'}).status_code==403
        assert client.post(path+'?refresh=true',headers=h).status_code==400
        assert client.post(path,headers=h,json={}).status_code==400
        assert client.get(path,headers=h).status_code==405
        assert gate.calls==0
        assert client.post(path,headers=h).status_code==200 and gate.calls==1

def test_service_accepts_neutral_port_without_sdk_or_secret_dependency():
    from fubon_broker_service.order_results_port import OrderPortfolioCapture
    class NeutralPort:
        def read_order_portfolio(self, query_date):
            return OrderPortfolioCapture(query_date,'a'*24,(),dict(queryDate=query_date.isoformat(),accountFingerprint='a'*24,positions=[],emptyConfirmed=True))
    payload=OrderResultsService(NeutralPort(),now=lambda:NOW).read()
    assert payload['verifiedZero'] and payload['accountFingerprint']=='a'*24

@pytest.mark.parametrize('name,rows',[('knownzero',[]),('partial',[row()])])
def test_neutral_boundary_keeps_original_java_fixture_and_revision_exact(name,rows):
    import json
    from pathlib import Path
    expected=json.loads((Path(__file__).parents[2]/'backend/src/test/resources/srpp'/f'fubon-source-{name}.json').read_text())
    assert service(rows).read()==expected

def test_outer_adapter_strips_native_identity_before_neutral_capture():
    adapter=OrderResultsGatewayAdapter(PairGateway([row()]))
    capture=adapter.read_order_portfolio(NOW.date())
    assert '00001234567' not in repr(capture) and TOKEN not in repr(capture)
    assert 'account' not in capture.orders[0] and 'branch_no' not in capture.orders[0]
    assert all(value is None or type(value) in (str,int,float,bool) for value in capture.orders[0].values())
