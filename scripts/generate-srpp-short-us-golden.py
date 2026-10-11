#!/usr/bin/env python3
"""Original SRPP D173/D185/D186 frozen-input parity, offline only."""
import copy, hashlib, json, sys
from pathlib import Path
from decimal import Decimal
ROOT=Path(__file__).resolve().parents[1]; SRPP=Path('/Users/steven/Project/SRPP')
sys.path.insert(0,str(SRPP/'scripts'))
import short_term_opportunities as short
import us_tactical_order_check as us
from test_short_term_opportunities import ShortTermOpportunitiesTests
from test_short_term_sleeve import add_position,add_fill,transaction,pending_order,set_pending,refresh_receipt
from test_us_tactical_order_check import USTacticalOrderCheckTests
ShortTermOpportunitiesTests.setUpClass(); st=ShortTermOpportunitiesTests(); model=st.inputs
calendar=json.loads((SRPP/'data/input/交易日曆_2026.json').read_text())['days']
def normalize(x):
 if isinstance(x,(float,Decimal)):return format(Decimal(str(x)).normalize(),'f')
 if isinstance(x,dict):return {k:normalize(v) for k,v in x.items()}
 if isinstance(x,list):return [normalize(v) for v in x]
 return x
cases=[]
def add(name,b):
 expected=short.screen(b,model)
 b['calendarVerified']=True;b['currentSessionVerified']=True;b['completedSessions']=[r['date'] for r in calendar if r['tw'] and r['date']<b['trading_date']]
 fields=['stock_code','status','reasons','adjusted_support','adjusted_pressure','rebound_pct','net_upside_pct','net_downside_pct','net_reward_risk','core_weight','core_allocation_full','confirmed_short_shares','share_ceiling_before_funding','buy_budget_ceiling_before_funding_twd','planned_loss_before_funding_twd','actual_size_net_reward_risk','may_recommend_after_daily_gates']
 rows=[{k:r[k] for k in fields if k in r} for r in expected['results']]
 sleeve={k:v for k,v in expected['sleeve'].items() if k in ('status','reason','used_twd','capacity_denominator_twd','remaining_twd','new_buys_frozen','batch_reserved_before_funding_twd')}
 if 'positions' in expected['sleeve']:sleeve['positions']=[{k:v for k,v in p.items() if k in ('shares','holding_sessions','market_value_twd','used_twd','exit_status','exit_reasons','pending_exit_shares','remaining_exit_room','exit_share_ceiling','locked_adjusted_support','locked_adjusted_pressure')} for p in expected['sleeve'].get('positions',[])]
 cases.append({'kind':'short','name':name,'input':b,'expected':normalize({'results':rows,'sleeve':sleeve})})
add('positive_known_empty',st.fixture())
b=st.fixture();st.add_candidate(b,'0050');st.add_candidate(b,'0056');add('batch_position_cap',b)
b=st.fixture();b['sleeve_evidence']['pending_orders_verified']=False;add('pending_unknown',b)
b=st.fixture();b['sleeve_ledger']['pending_orders']=[];b['items'][0]['radar']['stock']['shortAction']='WAIT';add('policy_wait',b)
b=st.held_bundle(support=51);add('support_exit',b)
b=st.held_bundle(support=51);b['items'][0]['radar']['stock']['action']='BUY_CANDIDATE';add('opposing_exit',b)
b=st.held_bundle();b['items'][0]['radar']['stock']['candidateAction']='REDUCE_CANDIDATE';set_pending(b,[pending_order(side='SELL',remaining_shares=600,reserved_twd=0)]);add('pending_sell_room',b)
b=st.fixture();add_position(b,reference_date=b['trading_date']);set_pending(b,[pending_order(remaining_shares=1000,reserved_twd=50130)]);st.add_candidate(b,'0050');add('pending_buy_capacity',b)
b=st.held_bundle();b['transactions']['records'].append(copy.deepcopy(b['transactions']['records'][0]));refresh_receipt(b);add('duplicate_fill_once',b)
b=st.fixture();b['items'].append(copy.deepcopy(b['items'][0]));add('duplicate_candidate',b)
ut=USTacticalOrderCheckTests();ut.policy=us.load_policy()
for name,target,path in [('us_radar_positive',125,'RADAR'),('us_profit_block',115.3,'RADAR'),('us_fallback_missing',125,'FALLBACK')]:
 item=ut.candidate();item.update(target_price=target,qualification_path=path)
 expected=us.evaluate(item,'2026-09-25',20000000,ut.policy)
 fields=['status','reasons','reference_price','reference_basis','recommended_limit_price','checked_limit_price','net_profit_space_pct','max_notional_twd']
 cases.append({'kind':'us','name':name,'date':'2026-09-25','total':'20000000','input':item,'expected':normalize({k:expected[k] for k in fields if k in expected})})
result={'schema':'SHORT_US_FULL_PARITY_V1','calculatorHashes':{n:hashlib.sha256((SRPP/'scripts'/n).read_bytes()).hexdigest() for n in ['short_term_sleeve.py','short_term_opportunities.py','us_tactical_order_check.py']},'cases':cases}
p=ROOT/'backend/src/test/resources/srpp/report-short-us-full-golden.json';p.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');print(len(cases),'original cases written')
