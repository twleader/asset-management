#!/usr/bin/env python3
"""Original SRPP state equations, frozen scalar same-input fixtures. Offline only."""
import argparse,copy,datetime as dt,hashlib,json,os,subprocess,sys
from pathlib import Path
from decimal import Decimal
import numpy as np
sys.dont_write_bytecode=True
ROOT=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--srpp-root',type=Path,required=True,help='explicit original SRPP source root')
parser.add_argument('--check',action='store_true',help='verify the tracked fixture without writing it')
args=parser.parse_args()
if not args.srpp_root.is_absolute():
 parser.error('--srpp-root must be an absolute path')
SRPP=args.srpp_root.resolve()
if not (SRPP/'assumptions/model_inputs.json').is_file() or not (SRPP/'scripts/policy_bundle.py').is_file():
 parser.error('--srpp-root must contain the original SRPP policy source and checker')

def reject_duplicate_keys(pairs):
 result={}
 for key,value in pairs:
  if key in result: raise SystemExit(f'SRPP_STATE_TOPUP_DUPLICATE_JSON_KEY:{key}')
  result[key]=value
 return result

def parse_strict(data,label):
 try:
  return json.loads(data.decode('utf-8'),object_pairs_hook=reject_duplicate_keys)
 except (UnicodeDecodeError,json.JSONDecodeError) as error:
  raise SystemExit(f'SRPP_STATE_TOPUP_{label}_JSON_INVALID:{error}') from error

# Verify the authored source before importing any of its pure calculators.
receipt_run=subprocess.run([sys.executable,str(SRPP/'scripts/policy_bundle.py'),'check','--purpose','daily','--consumer','Codex'],cwd=SRPP,env={**os.environ,'PYTHONDONTWRITEBYTECODE':'1'},stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=False)
if receipt_run.returncode:
 raise SystemExit('SRPP_STATE_TOPUP_OFFICIAL_POLICY_BUNDLE_CHECK_FAILED')
receipt=parse_strict(receipt_run.stdout,'OFFICIAL_RECEIPT')
model_bytes=(SRPP/'assumptions/model_inputs.json').read_bytes()
manifest_bytes=(SRPP/'assumptions/policy_manifest.json').read_bytes()
manifest=parse_strict(manifest_bytes,'MANIFEST')
if not isinstance(receipt,dict) or receipt.get('status')!='PASS' or not isinstance(manifest,dict) or receipt.get('POLICY_BUNDLE_SHA256')!=manifest.get('POLICY_BUNDLE_SHA256') or not isinstance(manifest.get('POLICY_BUNDLE_SHA256'),str):
 raise SystemExit('SRPP_STATE_TOPUP_OFFICIAL_BUNDLE_MISMATCH')
if manifest.get('files',{}).get('assumptions/model_inputs.json')!=hashlib.sha256(model_bytes).hexdigest():
 raise SystemExit('SRPP_STATE_TOPUP_SOURCE_MODEL_HASH_MISMATCH')
for name,expected in [('model_inputs.json',model_bytes),('policy_manifest.json',manifest_bytes)]:
 embedded=ROOT/'backend/src/main/resources/srpp/decision'/name
 if not embedded.is_file() or embedded.read_bytes()!=expected:
  raise SystemExit('SRPP_STATE_TOPUP_EMBEDDED_AUTHORITY_BYTES_MISMATCH:'+name)
model=parse_strict(model_bytes,'MODEL')
sys.path.insert(0,str(SRPP/'scripts'))
import execution_policy as ep
from bear_ladder import BullLadder,BearLadder
from transition_ledger import TransitionLedger
cfg=model['execution_policy_overrides']['global_equity_build_cadence_d141'];dyn=model['execution_policy']['dynamic_allocation_policy_d141'];cases=[]
def num(v):return float(np.asarray(v).reshape(-1)[0])
def pace_state(c,ids):
 return {'verified':True,'processedFillIds':sorted(ids),'month':('%04d-%02d'%c.month) if c.month else '', 'week':c.week or '', 'day':c.day or '', 'halved':bool(c.halved[0]), **{code:{'spent':num(c.spent[code]),'previousShortfall':num(c.previous_shortfall[code]),'consecutiveShortfalls':int(c.consecutive_shortfalls[code][0]),**({'monthBatches':int(c.voo_month_batches[0]),'weekBatches':int(c.voo_week_batches[0]),'lastFillPrice':num(c.last_voo_fill_price) if np.isfinite(c.last_voo_fill_price[0]) else None} if code=='VOO' else {'dayLots':int(c.local_day_lots[0]),'weekLots':int(c.local_week_lots[0])})}for code in ('VOO','009826')}}
def begin(c,date,fx): iso=date.isocalendar();c.begin(date,fx,week=f'{iso.year}-W{iso.week:02}',day=date.isoformat())
c=ep.GlobalEquityCadence(cfg,1);ids=[]
for name,date,fx,fills in [('initial',dt.date(2026,9,1),32,[]),('scheduled',dt.date(2026,9,15),32,[{'symbol':'VOO','nominalTwd':11200,'price':100,'scheduled':True,'shares':0}]),('discretionary_and_local',dt.date(2026,9,15),34,[{'symbol':'VOO','nominalTwd':150000,'price':99,'scheduled':False,'shares':0},{'symbol':'009826','nominalTwd':50000,'price':50,'scheduled':False,'shares':1000}]),('sticky_halving',dt.date(2026,9,16),32,[]),('next_month_shortfall',dt.date(2026,10,1),32,[]),('skip_month',dt.date(2026,12,1),32,[])]:
 before=pace_state(c,list(ids));frozen=[]
 for i,f in enumerate(fills):
  fid=name+str(i);row={**f,'fillId':fid,'confirmed':True,'date':date.isoformat(),'fx':fx};frozen.append(row);begin(c,date,fx);c.fill(f['symbol'],np.array([f['nominalTwd']]),price=np.array([f['price']]),shares=np.array([f['shares']]),scheduled=f['scheduled']);ids.append(fid)
 begin(c,date,fx);cases.append({'kind':'pace','name':name,'date':date.isoformat(),'fx':fx,'before':before,'fills':frozen,'expected':pace_state(c,list(ids))})
def ladder_state(l,mode):
 out={'verified':True,'active':bool(l.active[0]),'windowBudget':num(l.window_budget),'budget':l.budget[0].tolist(),'spent':l.spent[0].tolist(),'fills':l.fills[0].tolist(),'lastFill':int(l.last_fill[0]),'lastAmount':num(l.last_amount),'initialBonds':num(l.initial_bonds) if mode=='BEAR' else 0,'bondsSold':num(l.bonds_sold) if mode=='BEAR' else 0,'highWaterGain':num(l.high_water_gain) if mode=='BULL' and np.isfinite(l.high_water_gain[0]) else 0,'pendingClose':bool(l.pending_close[0]) if mode=='BULL' else False}
 return out
for mode in ('BULL','BEAR'):
 l=(BullLadder if mode=='BULL' else BearLadder)(dyn,1); l.cooldown=5
 for i,(gain,dd,confirmed) in enumerate([(0.2,-.15,0),(0.15,-.15,0),(0.17,-.2,1),(0.15,-.2,0),(.05,-.05,0)]):
  before=ladder_state(l,mode);session=i*5;event={'verified':True,'qualified':True,'session':session,'total':10000000,'equity':6000000 if mode=='BULL' else 3500000,'gain':gain,'corrected':False,'drawdown':dd,'eligibleBonds':3000000,'totalBonds':3000000,'availableCash':1000000,'fills':[]}
  if mode=='BULL': l.update(np.array([gain]),np.array([True]),np.array([event['total']]),np.array([event['equity']]),corrected=np.array([False]))
  else:l.update(np.array([dd]),np.array([True]),np.array([event['total']]),np.array([event['eligibleBonds']]),available_cash=np.array([event['availableCash']]),total_bonds=np.array([event['totalBonds']]),equity=np.array([event['equity']]))
  if confirmed:
   rung,proposal=l.proposal(session,np.array([True])); deployed=proposal*.8
   if deployed[0]>0:
    fill={'confirmed':True,'rung':int(rung[0]),'deployed':num(deployed),'bondSale':0,'session':session};event['fills']=[fill]
    if mode=='BULL':l.record(rung,deployed,session)
    else:l.record(rung,deployed,np.array([0]),session)
  rung,amount=l.proposal(session,np.array([True]));expected={'nextBatch':int(rung[0]),'ladderAmount':num(amount),'growingFill':bool(l.growing_fill(amount)[0]),'eligible':bool(amount[0]>0)}
  if mode=='BEAR':expected['bondAllowance']=num(l.bond_allowance())
  else:expected.update(gainAboveAnchor=num(l.high_water_gain) if np.isfinite(l.high_water_gain[0]) else 0,pullback=num(l.pullback))
  cases.append({'kind':'ladder','name':mode+str(i),'mode':mode,'before':before,'event':event,'expected':expected})
# Original reserve clock and FIFO equations operate on actual completed-session indices.
l=TransitionLedger.__new__(TransitionLedger);l.n=1;l.stock_reserve=np.zeros(1);l.stock_reserve_lots=[];l.rotation_bear_paused=np.zeros(1);l.session=0;l.add_stock_reserve(np.array([100000]),settled_session=0);l.add_stock_reserve(np.array([200000]),settled_session=1)
before={'verified':True,'lastProcessedDate':'2026-09-01','lots':[{'settledDate':'2026-09-01','settlementVerified':True,'amount':100000,'age':0},{'settledDate':'2026-09-02','settlementVerified':True,'amount':200000,'age':0}]};sessions=[]
for i,paused in [(1,False),(2,True),(3,False)]:l.session=i;l.reserve_day(np.array([paused]));sessions.append({'date':str(dt.date(2026,9,1)+dt.timedelta(days=i)),'bearPaused':paused})
l.consume_stock_reserve(np.array([120000]));cases.append({'kind':'reserve','name':'settlement_age_bear_pause_fifo','before':before,'sessions':sessions,'fills':[{'confirmed':True,'symbol':'VOO','market':'美股','payment':120000}],'date':'2026-09-05','expected':{'settledReserve':num(l.stock_reserve),'ages':[int(x['age'][0]) for x in l.stock_reserve_lots],'amounts':[num(x['amount']) for x in l.stock_reserve_lots]}})
# Exact fee-denominator capacity is compared to the authoritative original component equation.
l=TransitionLedger.__new__(TransitionLedger);l.high_cap=model['execution_policy']['high_dividend_execution_policy']['aggregate_hard_cap'];l.high_group=np.array([True,True]);l.weights=np.array([.0725,.045]);l.values=np.array([[1400000,0]])
for current in (1400000,1440000,1450000):
 l.values[0,0]=current;room=num(l.high_dividend_purchase_room(0,np.array([20000000]),.001425));cases.append({'kind':'topupRoom','name':str(current),'total':20000000,'current':current,'group':current,'targetWeight':.0725,'groupCap':l.high_cap,'feeRate':.001425,'expectedRoom':room})
for paused in (False,True):
 l=TransitionLedger.__new__(TransitionLedger);l.n=1;l.inputs=model;l.codes=['VOO'];l.weights=np.array([.20]);l.values=np.array([[1000000.]]);l.high_group=np.array([False]);l.high_cap=.28;l.stock_reserve=np.array([1000000.]);l.stock_reserve_lots=[{'amount':np.array([1000000.]),'age':np.array([21]),'settled_session':0}];l.bond_reserve=np.zeros(1);l.pending_stock=np.zeros(1);l.pending_bond=np.zeros(1);l.initial_earmark_usd=np.zeros(1);l.dca_spent_usd=np.zeros(1);l.fx=np.array([32.]);l.price_level=1.;l.cadence=ep.GlobalEquityCadence(cfg,1);begin(l.cadence,dt.date(2026,9,22),32);l.forced_voo_blocked=np.zeros(1);l.forced_voo_deployed=np.zeros(1);l.nominal_prices=np.array([[100.]]);l.units=np.zeros((1,1));l.costs=np.zeros(1);l.turnover=np.zeros(1);l.buy_count=np.zeros((1,1));assets=np.array([[8000000.,3000000.,9000000.]]);reserve=np.zeros(1);fee=.001425;individual=(.2*20000000-1000000)/(1+.2*fee);group=(.4*20000000-1000000)/(1+.4*fee);equity=(.6*20000000-8000000)/(1+.6*fee);l.deploy_overdue_reserve(assets,reserve,np.array([1550000.]),fee,np.array([paused]),evidence_ok=np.array([True]));cases.append({'kind':'reserveDeployment','name':'paused' if paused else 'priority_monthly_cap','input':{'overdue':1000000,'physical':7450000,'individualRoom':individual,'groupRoom':group,'equityRoom':equity,'monthlyRoom':900000,'priceLevel':1,'commission':fee,'bearPaused':paused,'exactBuyGates':True},'expected':num(l.forced_voo_deployed)})
def normalize(v):
 if isinstance(v,float):return format(Decimal(str(v)).normalize(),'f')
 if isinstance(v,dict):return {k:normalize(x) for k,x in v.items()}
 if isinstance(v,list):return [normalize(x) for x in v]
 return v
out={'schema':'SRPP_STATE_TOPUP_GOLDEN_V1','originalCalculatorHashes':{n:hashlib.sha256((SRPP/'scripts'/n).read_bytes()).hexdigest() for n in ('execution_policy.py','bear_ladder.py','transition_ledger.py')},'cases':cases}
if len(cases)!=22:
 raise SystemExit('SRPP_STATE_TOPUP_CASE_COUNT_INVALID')
path=ROOT/'backend/src/test/resources/srpp/report-state-topup-golden.json'
rendered=(json.dumps(normalize(out),ensure_ascii=False,sort_keys=True,indent=2)+'\n').encode('utf-8')
if args.check:
 if not path.is_file() or path.read_bytes()!=rendered:
  raise SystemExit('SRPP_STATE_TOPUP_GOLDEN_BYTES_MISMATCH')
 print(f'{len(cases)} original state/component parity cases match')
else:
 path.write_bytes(rendered)
 print(f'{len(cases)} original state/component parity cases written')
