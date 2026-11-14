"""Finite timing classification: evidence only, never creates a BUY or quantity."""
from __future__ import annotations
import argparse
import json
import math
from pathlib import Path

def classify_kd(k,d,execution):
    if any(type(v) not in (int,float) or not math.isfinite(v) or not 0<=v<=100 for v in (k,d)):
        return dict(status='UNKNOWN',entryWatch=False,overheated=False,tradeAuthorization=False)
    entry=k<execution['kd_entry_watch_k_below'] and d<execution['kd_entry_watch_d_below']
    high=k>execution['kd_overbought_k_greater_than'] and d>execution['kd_overbought_d_greater_than']
    return dict(status='ENTRY_WATCH' if entry else 'OVERHEATED' if high else 'NEUTRAL',entryWatch=entry,overheated=high,tradeAuthorization=False)

def main():
    p=argparse.ArgumentParser();p.add_argument('--k',type=float,required=True);p.add_argument('--d',type=float,required=True);a=p.parse_args()
    model=json.loads((Path(__file__).resolve().parents[1]/'assumptions/model_inputs.json').read_text())
    print(json.dumps(classify_kd(a.k,a.d,model['execution_policy']),ensure_ascii=False))
if __name__=='__main__':main()

def validate_formal_settings(model,root=None):
    """Bounds and exact dependent aliases; independent of the web/runtime library."""
    e=model['execution_policy'];p=e['bond_price_selection_d195'];c=e['bond_purchase_cadence'];r=e['risk_score_decision_policy']
    def number(v,low,high):return type(v) in (int,float) and math.isfinite(v) and low<=v<=high
    if not all(number(e[k],0,100) for k in ['kd_entry_watch_k_below','kd_entry_watch_d_below','kd_overbought_k_greater_than','kd_overbought_d_greater_than']):raise ValueError('SETTINGS_KD_BOUNDS')
    if not (e['kd_entry_watch_k_below']<e['kd_overbought_k_greater_than'] and e['kd_entry_watch_d_below']<e['kd_overbought_d_greater_than']):raise ValueError('SETTINGS_KD_ORDER')
    for sub in ('strategic_buy_high_gate_d188','core_sell_guard_d174'):
        if any(e[sub].get(k)!=e[k] for k in ['kd_overbought_k_greater_than','kd_overbought_d_greater_than']):raise ValueError('SETTINGS_KD_ALIAS')
    if not (number(p['suitable_min_drawdown_from_adjusted_high'],0,1) and number(p['max_premium_discount_pct'],0,5) and number(p['premium_pause_abs_pct'],0,10) and p['max_premium_discount_pct']<p['premium_pause_abs_pct']):raise ValueError('SETTINGS_PRICE_BOUNDS')
    day=p['strategic_max_lots_per_day'];week=p['strategic_weekly_total_lot_cap']
    if not (type(day) is int and type(week) is int and 1<=day<=10 and day<=week<=50):raise ValueError('SETTINGS_QUOTA_BOUNDS')
    if not (c['normal_daily_total_lot_cap']==c['max_daily_total_lot_cap']==day and c['weekly_total_lot_cap']==week):raise ValueError('SETTINGS_QUOTA_ALIAS')
    cautious=r['cautious_total_score'];defensive=r['defensive_total_score']
    if not (type(cautious) is int and type(defensive) is int and 1<=cautious<defensive<=15):raise ValueError('SETTINGS_RISK_BOUNDS')
    if root is not None:
        text=(Path(root)/'docs/SETTINGS_CURRENT_AUTHORITY.md').read_text()
        mapping={'bondPriceDrawdown':p['suitable_min_drawdown_from_adjusted_high'],'bondPremiumCeiling':p['max_premium_discount_pct'],'bondPremiumPause':p['premium_pause_abs_pct'],'bondDailyLots':day,'bondWeeklyLots':week,'entryK':e['kd_entry_watch_k_below'],'entryD':e['kd_entry_watch_d_below'],'overboughtK':e['kd_overbought_k_greater_than'],'overboughtD':e['kd_overbought_d_greater_than'],'riskCautiousTotal':cautious,'riskDefensiveTotal':defensive}
        for key,value in mapping.items():
            if f'| {key} | {json.dumps(value,ensure_ascii=False)} |' not in text:raise ValueError('SETTINGS_AUTHORITY_TABLE_ALIAS:'+key)
    return True
