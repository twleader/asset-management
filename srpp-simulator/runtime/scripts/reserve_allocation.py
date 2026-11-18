#!/usr/bin/env python3
"""D-222 exact reserve/target projection. Pure calculation; never authorizes trades."""
from __future__ import annotations
import calendar
import datetime as dt
import math


def indexed_reserve_amounts(model, valuation_date):
    """Index the four real-TWD amounts without imposing initial target feasibility."""
    p=model['execution_policy']['reserve_allocation_d222']
    date=dt.date.fromisoformat(valuation_date);base=dt.date.fromisoformat(p['inflation_base_date'])
    if date<base:raise ValueError('D222_DATE_BEFORE_REAL_BASE')
    factor=(1+float(p['annual_inflation_rate']))**((date-base).days/365.25)
    amounts={k:float(p[k])*factor for k in ('living_term_deposit_twd_2026_real','living_demand_deposit_twd_2026_real','emergency_term_deposit_twd_2026_real','emergency_etf_twd_2026_real')}
    return {'inflation_factor':factor,'reserve_amounts_twd':amounts}


def project_targets(model, total_assets_twd, valuation_date, reductions_twd=None, additions_twd=None):
    p=model['execution_policy']['reserve_allocation_d222']
    if isinstance(total_assets_twd,bool):raise ValueError('D222_TOTAL_ASSETS_UNVERIFIED')
    total=float(total_assets_twd)
    if not math.isfinite(total) or total<=0:raise ValueError('D222_TOTAL_ASSETS_UNVERIFIED')
    indexed=indexed_reserve_amounts(model,valuation_date)
    factor,amounts=indexed['inflation_factor'],indexed['reserve_amounts_twd']
    reserve=sum(amounts.values())
    if reserve>total:raise ValueError('D222_INSUFFICIENT_ASSETS')
    delta=total*float(p['reference_cash_weight'])-reserve
    weights=dict(model['detailed_target_allocation_2027'])
    equity_target=model["recommended_total_allocation"]["equity"]+delta/total
    strategic_bond_target=model["recommended_total_allocation"]["bond_etfs"]
    # A pending market allocation is meaningful only if an authorized target
    # can exist. Fixed bonds and unchanged stocks cannot fund the three sleeves.
    if equity_target<0:raise ValueError('D222_INSUFFICIENT_ASSETS')
    adjustment_capacity=sum(float(weights[code])*total for code in p['shortfall_symbols'])
    if delta<0 and -delta>adjustment_capacity+1e-6:
        raise ValueError('D222_INSUFFICIENT_ADJUSTMENT_CAPACITY')
    weights['TWD_term_deposits']=(amounts['living_term_deposit_twd_2026_real']+amounts['emergency_term_deposit_twd_2026_real'])/total
    weights['demand_deposits_and_cash']=amounts['living_demand_deposit_twd_2026_real']/total
    weights['emergency_00865B']=amounts['emergency_etf_twd_2026_real']/total
    result={'decision':'D-222','valuation_date':valuation_date,'inflation_factor':factor,'total_assets_twd':total,
        'protected_target_twd':reserve,'reference_cash_delta_twd':delta,'reserve_amounts_twd':amounts,
        'equity_target':equity_target,'strategic_bond_target':strategic_bond_target,
        'economic_bond_target':strategic_bond_target+weights['emergency_00865B'],
        'bank_deposit_target':weights['TWD_term_deposits']+weights['demand_deposits_and_cash'],
        'reference_targets_are_executable':False,'transaction_authorized':False}
    shortfall=delta< -1e-7;excess=delta>1e-7
    allocation=reductions_twd if shortfall else additions_twd if excess else None
    if (shortfall and additions_twd) or (excess and reductions_twd) or (not(shortfall or excess) and (reductions_twd or additions_twd)):
        raise ValueError('D222_ALLOCATION_DIRECTION_INVALID')
    if shortfall or excess:
        symbols=p['shortfall_symbols'] if shortfall else p['excess_symbols']
        if allocation is None:
            result.update(status='MARKET_ALLOCATION_REQUIRED',target_weights=None,
                          known_target_weights={k:v for k,v in weights.items() if k not in symbols},
                          shortfall_twd=max(-delta,0),excess_twd=max(delta,0),eligible_adjustment_symbols=symbols)
            return result
        if not isinstance(allocation,dict) or set(allocation)-set(symbols):raise ValueError('D222_ADJUSTMENT_ALLOCATION_UNVERIFIED')
        values={}
        for code,value in allocation.items():
            if isinstance(value,bool):raise ValueError('D222_ADJUSTMENT_ALLOCATION_UNVERIFIED')
            number=float(value)
            if not math.isfinite(number) or number<0 or (shortfall and number>weights[code]*total):raise ValueError('D222_ADJUSTMENT_ALLOCATION_UNVERIFIED')
            values[code]=number
        if abs(sum(values.values())-abs(delta))>1e-6:raise ValueError('D222_ADJUSTMENT_ALLOCATION_DOES_NOT_RECONCILE')
        for code,value in values.items():weights[code]+=(-1 if shortfall else 1)*value/total
        for code,cap in p['ordinary_tw_core_caps'].items():
            if weights[code]>cap+1e-10:raise ValueError('D222_ORDINARY_CORE_CAP_EXCEEDED')
    if reserve>total or any(v<0 for v in weights.values()):raise ValueError('D222_INSUFFICIENT_ASSETS')
    if abs(sum(weights.values())-1)>1e-8:raise ValueError('D222_TARGETS_DO_NOT_CONSERVE_ASSETS')
    global_target=sum(weights[c] for c in ('VOO','VT','009826'))
    result.update(status='TARGETS_READY',target_weights=weights,
        global_equity_target=global_target,
        voo_monitoring_band=[max(0,weights['VOO']-.03),weights['VOO']+.03],
        global_equity_monitoring_band=[max(0,global_target-.03),global_target+.03])
    return result


def require_targets(model,total_assets_twd,valuation_date,reductions_twd=None,additions_twd=None):
    result=project_targets(model,total_assets_twd,valuation_date,reductions_twd,additions_twd)
    if result['status']!='TARGETS_READY':raise ValueError('D222_MARKET_ALLOCATION_REQUIRED')
    return result


def replenishment_due_date(withdrawal_date):
    day=dt.date.fromisoformat(withdrawal_date)
    return day.replace(year=day.year+1,day=min(day.day,calendar.monthrange(day.year+1,day.month)[1])).isoformat()


def emergency_use_allowed(reason,*,end_of_life=False,other_funds_exhausted=False):
    return reason in ('ACCIDENT','SERIOUS_ILLNESS') or (reason=='END_OF_LIFE' and end_of_life is True and other_funds_exhausted is True)


def bear_funding_capacity(*, total_assets_twd, non_emergency_bonds_twd,
                          non_emergency_term_deposits_twd, model, valuation_date,
                          emergency_etf_twd=None, emergency_etf_used_twd=None,
                          living_term_twd=None, living_term_used_twd=None):
    """Capacity ceilings from verified sleeves; used values include active commitments.

    Dedicated balances and used amounts must come from complete, reconciled
    sources for the active bear window. Missing evidence adds no capacity.
    """
    def verified(value):
        return type(value) in (int, float) and math.isfinite(value) and value >= 0

    if (not all(verified(v) for v in (total_assets_twd, non_emergency_bonds_twd,
                                    non_emergency_term_deposits_twd))
            or total_assets_twd <= 0):
        raise ValueError('D222_FUNDING_UNVERIFIED')
    p = model['execution_policy']['reserve_allocation_d222']
    factor = indexed_reserve_amounts(model, valuation_date)['inflation_factor']
    floor = model['execution_policy']['dynamic_allocation_policy_d141']['total_bond_floor']
    living_limit = p.get('bear_living_term_2026_real', p['living_term_deposit_twd_2026_real']) * factor
    living_verified = verified(living_term_twd) and verified(living_term_used_twd)
    if 'bear_living_term_2026_real' not in p:
        living_capacity = min(non_emergency_term_deposits_twd, living_limit)
    elif living_verified:
        protected_term = p['living_term_deposit_twd_2026_real'] * factor - living_limit
        living_capacity = min(max(0, living_term_twd - protected_term),
                              max(0, living_limit - living_term_used_twd),
                              non_emergency_term_deposits_twd)
    else:
        living_capacity = 0
    emergency_capacity = 0
    emergency_verified = verified(emergency_etf_twd) and verified(emergency_etf_used_twd)
    if non_emergency_bonds_twd >= floor * total_assets_twd and emergency_verified:
        emergency_capacity = min(emergency_etf_twd,
                                 max(0, p.get('bear_emergency_etf_cap_twd', 0) - emergency_etf_used_twd))
    return {'bond_sale_capacity_twd': max(0, non_emergency_bonds_twd - floor * total_assets_twd),
            'living_term_capacity_twd': living_capacity,
            'emergency_capacity_twd': emergency_capacity, 'living_demand_capacity_twd': 0,
            'living_term_evidence': 'VERIFIED' if living_verified else 'UNAVAILABLE',
            'emergency_etf_evidence': 'VERIFIED' if emergency_verified else 'UNAVAILABLE',
            'transaction_authorized': False}


def apply_projection(model,total_assets_twd,valuation_date,reductions_twd=None,additions_twd=None):
    """Return a copy with current target weights; segregated emergency ETF stays separate."""
    import copy
    result=require_targets(model,total_assets_twd,valuation_date,reductions_twd,additions_twd)
    projected=copy.deepcopy(model);projected['runtime_reserve_allocation_d222']=result
    projected['detailed_target_allocation_2027']=result['target_weights']
    projected['recommended_total_allocation'].update(equity=result['equity_target'],
        bond_etfs=result['economic_bond_target'],cash_like_including_term_deposits_and_reserve=result['bank_deposit_target'])
    projected['execution_policy']['dynamic_allocation_policy_d141']['normal_equity_target']=result['equity_target']
    wp=projected['execution_policy_overrides']['global_equity_wrapper_policy_d141']
    wp.update(VOO_target=result['target_weights']['VOO'],global_equity_target=result['global_equity_target'],
              VOO_lower_band=result['voo_monitoring_band'][0],VOO_upper_band=result['voo_monitoring_band'][1],
              global_equity_group_floor=result['global_equity_monitoring_band'][0],global_equity_group_cap=result['global_equity_monitoring_band'][1])
    return projected



def select_market_adjustment(model,total_assets_twd,valuation_date,candidates,adjustment_mode="INITIAL_ALLOCATION",living_reserve=None):
    """Select one eligible symbol by its OWN adjusted 52-week range position.

    Source content and gate receipts are hash-bound, not boolean eligibility.
    This validates saved-input consistency only: the report consumer must still
    verify the originals and the named existing gates. It grants no trade right.
    """
    import hashlib,json
    p=model['execution_policy']['reserve_allocation_d222']
    if adjustment_mode not in ('INITIAL_ALLOCATION','LIVING_RESERVE_REBALANCE'):
        return {'decision':'D-222','status':'UNVERIFIED','reason':'D222_ADJUSTMENT_MODE_UNVERIFIED','transaction_authorized':False}
    living_available=None
    if adjustment_mode=='INITIAL_ALLOCATION':
        projection=project_targets(model,total_assets_twd,valuation_date)
        delta=projection['reference_cash_delta_twd']
    else:
        try:
            if isinstance(total_assets_twd,bool):raise ValueError('D222_TOTAL_ASSETS_UNVERIFIED')
            total=float(total_assets_twd)
            if not math.isfinite(total) or total<=0:raise ValueError('D222_TOTAL_ASSETS_UNVERIFIED')
            amounts=indexed_reserve_amounts(model,valuation_date)['reserve_amounts_twd']
            payload=living_reserve['payload']
            fingerprint=hashlib.sha256(json.dumps(payload,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()).hexdigest()
            if living_reserve.get('sha256')!=fingerprint or not living_reserve.get('source_ref'):
                raise ValueError('D222_LIVING_SUBLEDGER_HASH_UNVERIFIED')
            if payload.get('valuation_date')!=valuation_date or payload.get('ledger_scope')!='LIVING_RESERVE_EXCLUDING_EMERGENCY':
                raise ValueError('D222_LIVING_SUBLEDGER_SCOPE_UNVERIFIED')
            ids=payload.get('reconciled_deposit_ids')
            if not isinstance(ids,list) or not ids or len(ids)!=len(set(ids)):
                raise ValueError('D222_LIVING_SUBLEDGER_UNVERIFIED')
            balances=[payload[k] for k in ('living_term_twd','living_demand_twd','settled_unrestricted_available_twd')]
            if any(type(v) not in (int,float) or not math.isfinite(v) or v<0 for v in balances):
                raise ValueError('D222_LIVING_SUBLEDGER_UNVERIFIED')
            target=amounts['living_term_deposit_twd_2026_real']+amounts['living_demand_deposit_twd_2026_real']
            delta=balances[0]+balances[1]-target
            living_available=min(balances[2],max(delta,0))
        except (KeyError,TypeError,ValueError) as error:
            return {'decision':'D-222','status':'UNVERIFIED','reason':str(error),'adjustment_mode':adjustment_mode,'transaction_authorized':False,'api_capacity_acceptance':False}
    side='BUY' if delta>1e-7 else 'SELL' if delta< -1e-7 else 'NONE'
    result={'decision':'D-222','adjustment_mode':adjustment_mode,'side':side,'status':'UNVERIFIED','selected_symbol':None,
            'allocation_twd':{},'remaining_adjustment_twd':abs(delta),'transaction_authorized':False,
            'source_consistency_only':True,'api_capacity_acceptance':False}
    if side=='NONE':result['status']='NO_ADJUSTMENT';return result
    def fingerprint(value):return hashlib.sha256(json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()).hexdigest()
    try:
        if not isinstance(candidates,list) or len(candidates)!=3:raise ValueError('D222_ALL_THREE_CANDIDATES_REQUIRED')
        verified={}
        for candidate in candidates:
            source=candidate['source'];facts=source['payload'];code=facts['stock_code']
            if code not in p['shortfall_symbols'] or code in verified:raise ValueError('D222_CANDIDATE_IDENTITY_UNVERIFIED')
            if source.get('sha256')!=fingerprint(facts) or not source.get('source_ref'):raise ValueError('D222_SOURCE_HASH_UNVERIFIED')
            if facts.get('trading_date')!=valuation_date or facts.get('adjustment_basis')!='DISTRIBUTION_ADJUSTED_52_WEEK':raise ValueError('D222_RANGE_BASIS_UNVERIFIED')
            vals=[facts[k] for k in ('price','low52','high52')]
            if any(type(v) not in (int,float) or not math.isfinite(v) or v<=0 for v in vals):raise ValueError('D222_RANGE_UNVERIFIED')
            price,low,high=vals
            if not low<=price<=high or high<=low:raise ValueError('D222_RANGE_UNVERIFIED')
            position=(price-low)/(high-low)
            receipts=candidate['gate_receipts'];gate_status=[]
            for gate in ('FX',side):
                entry=receipts[gate];payload=entry['payload']
                if entry.get('sha256')!=fingerprint(payload) or not entry.get('source_ref'):raise ValueError('D222_GATE_HASH_UNVERIFIED')
                if payload.get('stock_code')!=code or payload.get('trading_date')!=valuation_date or payload.get('scope')!=gate:raise ValueError('D222_GATE_IDENTITY_UNVERIFIED')
                if payload.get('status') not in ('VERIFIED_PASS','VERIFIED_BLOCKED'):raise ValueError('D222_GATE_UNVERIFIED')
                evidence=payload.get('source_evidence')
                if not isinstance(evidence,list) or not evidence:raise ValueError('D222_GATE_SOURCE_EVIDENCE_REQUIRED')
                for raw in evidence:
                    if not raw.get('source_ref') or raw.get('sha256')!=fingerprint(raw.get('payload')):raise ValueError('D222_GATE_SOURCE_HASH_UNVERIFIED')
                gate_status.append(payload['status'])
            cap=candidate['capacity_twd']
            if type(cap) not in (int,float) or not math.isfinite(cap) or cap<0:raise ValueError('D222_CAPACITY_UNVERIFIED')
            verified[code]={'position':position,'eligible':all(s=='VERIFIED_PASS' for s in gate_status),'capacity_twd':cap}
        if set(verified)!=set(p['shortfall_symbols']):raise ValueError('D222_ALL_THREE_CANDIDATES_REQUIRED')
        result['candidates']=verified
        eligible=[(code,row) for code,row in verified.items() if row['eligible']]
        if not eligible:result['status']='NO_ELIGIBLE_CANDIDATE';return result
        extreme=(min if side=='BUY' else max)(row['position'] for _,row in eligible)
        selected=[(code,row) for code,row in eligible if math.isclose(row['position'],extreme,rel_tol=0,abs_tol=1e-12)]
        if len(selected)!=1:result['status']='AMBIGUOUS_TIE';return result
        code,row=selected[0];amount=min(abs(delta),row['capacity_twd'])
        reference=model['detailed_target_allocation_2027'][code]*float(total_assets_twd)
        if living_available is not None and side=='BUY':amount=min(amount,living_available)
        if adjustment_mode=='INITIAL_ALLOCATION' and side=='SELL':amount=min(amount,reference)
        elif adjustment_mode=='INITIAL_ALLOCATION' and code in p['ordinary_tw_core_caps']:
            amount=min(amount,max(0,(p['ordinary_tw_core_caps'][code]-model['detailed_target_allocation_2027'][code])*float(total_assets_twd)))
        result.update(status='ALLOCATION_REVIEW' if amount>=abs(delta)-1e-6 else 'PARTIAL_CAPACITY_WAIT',
                      selected_symbol=code,allocation_twd={code:amount},remaining_adjustment_twd=max(abs(delta)-amount,0))
        if adjustment_mode=='INITIAL_ALLOCATION' and result['remaining_adjustment_twd']<=1e-6:
            result['target_projection']=project_targets(model,total_assets_twd,valuation_date,
                reductions_twd={code:amount} if side=='SELL' else None,additions_twd={code:amount} if side=='BUY' else None)
        return result
    except (KeyError,TypeError,ValueError) as error:
        result['reason']=str(error);return result


def projection_from_selection(model,total_assets_twd,valuation_date,receipt):
    """Replay hash-bound market selection before consumers use adjusted targets."""
    import json
    if not isinstance(receipt,dict) or set(receipt)!={'input','result'}:raise ValueError('D222_MARKET_SELECTION_REQUIRED')
    value=receipt['input']
    if value.get('total_assets_twd')!=total_assets_twd or value.get('valuation_date')!=valuation_date or value.get('adjustment_mode','INITIAL_ALLOCATION')!='INITIAL_ALLOCATION':
        raise ValueError('D222_MARKET_SELECTION_IDENTITY_MISMATCH')
    replay=select_market_adjustment(model,total_assets_twd,valuation_date,value.get('candidates'))
    if json.dumps(replay,sort_keys=True,separators=(',',':'))!=json.dumps(receipt['result'],sort_keys=True,separators=(',',':')):
        raise ValueError('D222_MARKET_SELECTION_REPLAY_MISMATCH')
    if replay.get('status')!='ALLOCATION_REVIEW' or 'target_projection' not in replay:
        raise ValueError('D222_MARKET_ALLOCATION_REQUIRED')
    adjustment=replay['allocation_twd']
    return apply_projection(model,total_assets_twd,valuation_date,
        reductions_twd=adjustment if replay['side']=='SELL' else None,
        additions_twd=adjustment if replay['side']=='BUY' else None)


if __name__=='__main__':
    import argparse,json
    from pathlib import Path
    parser=argparse.ArgumentParser();parser.add_argument('request',type=Path)
    args=parser.parse_args();value=json.loads(args.request.read_text())
    model=json.loads((Path(__file__).resolve().parents[1]/'assumptions/model_inputs.json').read_text())
    if value.get('mode')=='BEAR_FUNDING':
        result=bear_funding_capacity(model=model, **{k:v for k,v in value.items() if k != 'mode'})
    elif value.get('mode')=='MARKET_SELECTOR':
        result=select_market_adjustment(model,value['total_assets_twd'],value['valuation_date'],value.get('candidates'),value.get('adjustment_mode','INITIAL_ALLOCATION'),value.get('living_reserve'))
    else:result=project_targets(model,value['total_assets_twd'],value['valuation_date'],value.get('reductions_twd'),value.get('additions_twd'))
    print(json.dumps(result,ensure_ascii=False,indent=2))
