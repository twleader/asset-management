#!/usr/bin/env python3
"""Offline same-input parity fixtures from the ORIGINAL SRPP pure calculators; no production writes."""
from pathlib import Path
from decimal import Decimal
import argparse, copy, datetime as dt, hashlib, json, os, subprocess, sys
sys.dont_write_bytecode=True
ROOT=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description='Generate or verify offline SRPP report same-input golden fixtures.')
parser.add_argument('--srpp-root',type=Path,default=Path('/Users/steven/Project/SRPP'))
parser.add_argument('--check',action='store_true',help='verify the tracked golden without writing it')
parser.add_argument('--output',type=Path,help='explicit generated golden destination (default: tracked test resource)')
args=parser.parse_args()
if not args.srpp_root.is_absolute():
 parser.error('--srpp-root must be an absolute path')
SRPP=args.srpp_root.resolve()
if not (SRPP/'assumptions/model_inputs.json').is_file():
 parser.error('--srpp-root must contain assumptions/model_inputs.json')

def reject_duplicate_keys(pairs):
 result={}
 for key,value in pairs:
  if key in result: raise SystemExit(f'SRPP_REPORT_SOURCE_DUPLICATE_JSON_KEY:{key}')
  result[key]=value
 return result

def parse_strict(data,label):
 try:
  return json.loads(data.decode('utf-8'),object_pairs_hook=reject_duplicate_keys,parse_float=Decimal)
 except (UnicodeDecodeError,json.JSONDecodeError) as error:
  raise SystemExit(f'SRPP_REPORT_{label}_JSON_INVALID:{error}') from error

def read_only(command,label,cwd):
 completed=subprocess.run(command,cwd=cwd,env={**os.environ,'PYTHONDONTWRITEBYTECODE':'1'},stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=False)
 if completed.returncode:
  detail=completed.stderr.decode('utf-8','replace').strip().splitlines()
  raise SystemExit(f'SRPP_REPORT_{label}_FAILED:{detail[-1] if detail else "exit non-zero"}')
 return completed.stdout

def canonical_decimal(value):
 if isinstance(value,bool) or not isinstance(value,(Decimal,int)): raise SystemExit('SRPP_REPORT_FORMAL_TARGET_NOT_NUMERIC')
 rendered=format(Decimal(value),'f')
 if '.' in rendered: rendered=rendered.rstrip('0').rstrip('.')
 return rendered or '0'

def formal_policy_document(model):
 allocations=model.get('detailed_target_allocation_2027') if isinstance(model,dict) else None
 if not isinstance(allocations,dict) or not allocations: raise SystemExit('SRPP_REPORT_FORMAL_TARGET_MISSING')
 targets={}
 for code,weight in allocations.items():
  if not isinstance(code,str): raise SystemExit('SRPP_REPORT_FORMAL_TARGET_CODE_INVALID')
  if code=='TWD_term_deposits': key='CASH:TWD:TERM'
  elif code=='demand_deposits_and_cash': key='CASH:TWD:DEMAND'
  else: key=f"STOCK:{'美股' if code.isascii() and all(c.isupper() or c=='.' for c in code) else '台股'}:{code}"
  targets[key]=canonical_decimal(weight)
 return (json.dumps({'schema':'SRPP_POLICY_DOCUMENT_V1','targets':targets},ensure_ascii=False,sort_keys=True,separators=(',',':'))+'\n').encode('utf-8')

MODEL_BYTES=(SRPP/'assumptions/model_inputs.json').read_bytes()
MANIFEST_BYTES=(SRPP/'assumptions/policy_manifest.json').read_bytes()
strict_model=parse_strict(MODEL_BYTES,'MODEL')
manifest=parse_strict(MANIFEST_BYTES,'MANIFEST')
receipt=parse_strict(read_only([sys.executable,str(SRPP/'scripts/policy_bundle.py'),'check','--purpose','daily','--consumer','Codex'],'OFFICIAL_POLICY_BUNDLE_CHECK',SRPP),'OFFICIAL_POLICY_BUNDLE_CHECK')
policy_bundle_sha=manifest.get('POLICY_BUNDLE_SHA256') if isinstance(manifest,dict) else None
if not isinstance(receipt,dict) or receipt.get('status')!='PASS' or not isinstance(policy_bundle_sha,str) or receipt.get('POLICY_BUNDLE_SHA256')!=policy_bundle_sha:
 raise SystemExit('SRPP_REPORT_OFFICIAL_BUNDLE_MISMATCH')
if manifest.get('files',{}).get('assumptions/model_inputs.json')!=hashlib.sha256(MODEL_BYTES).hexdigest():
 raise SystemExit('SRPP_REPORT_SOURCE_MODEL_HASH_MISMATCH')
if (ROOT/'backend/src/main/resources/srpp/decision/model_inputs.json').read_bytes()!=MODEL_BYTES or (ROOT/'backend/src/main/resources/srpp/decision/policy_manifest.json').read_bytes()!=MANIFEST_BYTES:
 raise SystemExit('SRPP_REPORT_EMBEDDED_AUTHORITY_BYTES_MISMATCH')
if (ROOT/'backend/src/main/resources/srpp/report/d201-policy-document.json').read_bytes()!=formal_policy_document(strict_model):
 raise SystemExit('SRPP_REPORT_FORMAL_POLICY_MISMATCH')
generated_sql=read_only(['ruby',str(ROOT/'scripts/srpp-report-policy-register.rb')],'RUBY_REGISTRATION_GENERATOR',ROOT)
if generated_sql!=(ROOT/'backend/src/main/resources/srpp/report/d201-registration.sql').read_bytes():
 raise SystemExit('SRPP_REPORT_REGISTRATION_SQL_MISMATCH')
if not generated_sql.startswith(b'INSERT INTO srpp_policy_registry ') or b' ON CONFLICT (policy_bundle_sha256) DO NOTHING;' not in generated_sql or policy_bundle_sha.encode('ascii') not in generated_sql:
 raise SystemExit('SRPP_REPORT_REGISTRATION_SQL_INVALID')
model=json.loads(MODEL_BYTES.decode('utf-8'))
sys.path.insert(0,str(SRPP/'scripts'))
import execution_policy as ep
import pre_retirement_cashflow as payroll
import strategic_buy_gate as sb
import core_sell_guard as core
import short_term_opportunities as short
import us_tactical_order_check as us
from test_core_sell_guard import CoreSellGuardTests
from test_short_term_opportunities import ShortTermOpportunitiesTests
from test_us_tactical_order_check import USTacticalOrderCheckTests
from test_strategic_buy_gate import StrategicBuyGateTests, evaluate as evaluate_test_strategic
from bear_ladder import BullLadder
import numpy as np

def normalize(x):
 if isinstance(x,float): return format(Decimal(str(x)).normalize(),'f')
 if isinstance(x,Decimal): return format(x.normalize(),'f')
 if isinstance(x,dict): return {k:normalize(v) for k,v in x.items()}
 if isinstance(x,list): return [normalize(v) for v in x]
 return x

def body(q,radar=None):
 daily=q['marketData']['chart']['series']['daily'];dates=daily['dates']
 result={'symbol':q['stockCode'],'market':q['market'],'quote':{k:v for k,v in q.items() if k not in ('marketData','dividendHistory')},'calendarVerified':True,'completedSessions':[d for d in dates if d<q['tradingDate']],'daily':[],'indicators':[],'dividends':[],'nav':{'premiumDiscountPct':q.get('premiumDiscountPct',0)}}
 for i,date in enumerate(dates):
  result['daily'].append({'tradingDate':date,'closePrice':daily['closes'][i],'highPrice':daily.get('highs',daily['closes'])[i],'lowPrice':daily.get('lows',daily['closes'])[i],'trusted':True})
  if 'k' in daily:result['indicators'].append({'tradingDate':date,**{k:daily[k][i] for k in ('k','d','rsi5','bias10','wr9') if k in daily}})
 for row in q.get('dividendHistory',{}).get('rows',[]): result['dividends'].append({**row,'stockCode':q['stockCode'],'market':q['market'],'source':q['dividendHistory']['source']})
 if radar:result['radar']=radar
 if q.get('bidLevels'):result['book']={'levels':[{'bidPrice':q['bidLevels'][0]['price']}]}
 return result
cases=[]
date='2026-09-22';dates=ep.completed_calendar_window(date,60)
for price in (100,99,98,97.99):
 q={'stockCode':'00713','market':'台股','tradingDate':date,'price':price,'premiumDiscountPct':0,'quoteStatus':'LIVE','marketData':{'chart':{'status':'AVAILABLE','requestedStart':dates[-20],'requestedEnd':date,'series':{'daily':{'dates':dates[-20:],'closes':[100]*20}}}},'dividendHistory':{'stockCode':'00713','market':'台股','source':'TWSE','message':None,'rows':[{'exDividendDate':'2026-01-01','cashDividend':1,'stockDividend':0}]}}
 r=ep.daily_topup_tier(q,stock_code='00713',market='台股',trading_date=date,completed_session=dates[-1],policy=model['execution_policy']['daily_topup_tier_policy'])
 cases.append({'kind':'topup','date':date,'body':body(q),'expected':{k:r[v] for k,v in [('referenceHigh','reference_high'),('drawdownPct','drawdown_pct'),('tierLots','tier_lots'),('reason','reason')]}})
for price,rsi in ((108,50),(106,80),(105.9,90),(100,50)):
 q={'stockCode':'00713','market':'台股','tradingDate':date,'price':price,'quoteStatus':'LIVE','marketData':{'chart':{'series':{'daily':{'dates':dates,'closes':[100]*60,'highs':[110]*60,'lows':[90]*60,'k':[50]*60,'d':[50]*60,'rsi5':[rsi]*60,'bias10':[0]*60,'wr9':[50]*60}}}},'dividendHistory':{'stockCode':'00713','market':'台股','source':'TWSE','rows':[{'exDividendDate':'2025-01-01','cashDividend':1}]}}
 r=sb.evaluate('00713',q,date,model['execution_policy']['strategic_buy_high_gate_d188'])
 cases.append({'kind':'strategic','date':date,'body':body(q),'expected':{'position52w':r['week52_position'],'high52w':r['week52_high'],'low52w':r['week52_low'],'eligible':r['status']=='NOT_HIGH_BUY_ALLOWED','reason':r['reasons'][0] if r['reasons'] else 'STRATEGIC_PRICE_GATE_PASSED'}})
# SGOV source-complete fixtures include every ex-date close; omitted rows fail closed separately.
sg=StrategicBuyGateTests()
for price in (100.655,100.37):
 q=sg.sgov(price);r=evaluate_test_strategic('SGOV',q,q['tradingDate'],model['execution_policy']['strategic_buy_high_gate_d188'])
 cases.append({'kind':'strategic','date':q['tradingDate'],'body':body(q),'expected':{'averageExDividendClose':r['average_ex_dividend_close'],'eligible':r['status']=='NOT_HIGH_BUY_ALLOWED','reason':r['reasons'][0] if r['reasons'] else 'STRATEGIC_PRICE_GATE_PASSED'}})
CoreSellGuardTests.setUpClass();tc=CoreSellGuardTests()
for bid in (97,97.01):
 bundle,state=tc.fixture();bundle['items'][0]['quote']['bidLevels'][0]['price']=bid
 r=tc.run_one(bundle,state);cases.append({'kind':'core','date':bundle['trading_date'],'body':body(bundle['items'][0]['quote'],bundle['items'][0]['radar']),'state':state,'expected':{'highWater':r['high_water'],'pullbackPct':r['pullback_pct'],'trendReversal':r['trend_reversal'],'timingPass':r['timing_pass'],'reason':r['reason']}})
ShortTermOpportunitiesTests.setUpClass();st=ShortTermOpportunitiesTests()
for policy_key,code in [('short_term_opportunity_d171','00881'),('bond_short_term_opportunity_d185','00679B')]:
 bundle=st.fixture();item=bundle['items'][0];item['stock_code']=item['quote']['stockCode']=item['radar']['stock']['stockCode']=item['quote']['dividendHistory']['stockCode']=code
 r=short.evaluate(item,bundle['assets'],bundle['trading_date'],model,policy_key)
 cases.append({'kind':'short','policyKey':policy_key,'body':body(item['quote'],item['radar']),'price':50,'support':r['adjusted_support'],'pressure':r['adjusted_pressure'],'shares':1000,'expected':{'netUpsidePct':r['net_upside_pct'],'netDownsidePct':r['net_downside_pct'],'rewardRisk':r['net_reward_risk'],'reboundPct':r['rebound_pct'],'reasons':r['reasons']}})
ut=USTacticalOrderCheckTests();ut.policy=us.load_policy()
for target in (125,115.3):
 item=ut.candidate();item['target_price']=target;r=ut.run_one(item);item['quote']['market']='美股'
 cases.append({'kind':'us','date':'2026-09-25','total':20000000,'item':item,'expected':{'referencePrice':r['reference_price'],'limitPrice':r['checked_limit_price'],'netProfitSpacePct':r['net_profit_space_pct'],'maximumNotional':r['max_notional_twd']}})
cfg=model['execution_policy_overrides']['global_equity_build_cadence_d141']
for fx,spent,prior,week,month,last,price in ((32,100000,0,0,0,100,100),(34,400000,100000,1,5,100,99),(32,900000,0,0,0,100,100)):
 c=ep.GlobalEquityCadence(cfg,1);c.begin(dt.date(2026,9,22),fx,week=39,day=22);c.spent['VOO'][0]=spent;c.previous_shortfall['VOO'][0]=prior;c.voo_week_batches[0]=week;c.voo_month_batches[0]=month;c.last_voo_fill_price[0]=last
 state={'verified':True,'halved':False,'VOO':{'spent':spent,'previousShortfall':prior,'weekBatches':week,'monthBatches':month,'lastFillPrice':last}}
 cases.append({'kind':'pace','code':'VOO','fx':fx,'price':price,'state':state,'expected':{'remaining':float(c.room('VOO')[0]),'discretionaryBudget':float(c.voo_budget(price)[0])}})
# BullLadder is an offline pure formula reference, never a live regime source.
dynamic=model['execution_policy']['dynamic_allocation_policy_d141'];ladder=BullLadder(dynamic,1)
ladder.update(np.array([.20]),np.array([True]),np.array([10000000]),np.array([6000000]),corrected=np.array([False]))
ladder.update(np.array([.15]),np.array([True]),np.array([10000000]),np.array([6000000]),corrected=np.array([False]))
_,amount=ladder.proposal(1,np.array([True]))
cases.append({'kind':'regime','live':{'verified':True,'mode':'BULL','anchor':100,'highWater':120,'price':115,'lockedBudget':1000000,'nextBatch':0,'cooldownVerified':True},'total':10000000,'cash':3000000,'bonds':1000000,'equity':6000000,'inflation':1,'expected':{'ladderAmount':float(amount[0]),'gainAboveAnchor':float(ladder.high_water_gain[0]),'pullback':float(ladder.pullback[0])}})
payroll_config=json.loads((SRPP/'assumptions/pre_retirement_payroll.json').read_text());payroll_result=payroll.calculate(payroll_config)
for row in payroll_result['ledger'][:2]:
 cases.append({'kind':'payroll','row':row,'expected':{'grossSalary':row['gross_pay'],'withholding':row['income_tax_withholding'],'voluntaryPension':row['voluntary_labor_pension'],'takeHome':row['net_cash_after_payroll'],'investableContribution':row['investable_surplus']}})
result={'schema':'SRPP_REPORT_SAME_INPUT_GOLDEN_V1','policyBundleSha256':policy_bundle_sha,'originalCalculatorHashes':{n:hashlib.sha256((SRPP/'scripts'/n).read_bytes()).hexdigest() for n in ['execution_policy.py','strategic_buy_gate.py','core_sell_guard.py','short_term_opportunities.py','us_tactical_order_check.py','bear_ladder.py','pre_retirement_cashflow.py']},'cases':cases}
output=(args.output if args.output else ROOT/'backend/src/test/resources/srpp/report-same-input-golden.json').resolve()
rendered=json.dumps(normalize(result),ensure_ascii=False,sort_keys=True,indent=2)+'\n'
if args.check:
 if not output.is_file() or output.read_text()!=rendered: raise SystemExit('SRPP_REPORT_GOLDEN_MISMATCH')
 print(f'{len(cases)} original-calculator parity cases match')
else:
 output.write_text(rendered)
 print(f'{len(cases)} original-calculator parity cases written')
