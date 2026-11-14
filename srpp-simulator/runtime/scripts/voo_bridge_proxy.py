"""Explicitly limited monthly proxy for the approved D220 bridge policy.

Synthetic aliases isolate new bridge shares from existing core shares. This does
not reconstruct daily signals, real bank quotes or future executable fills.
"""
import datetime as dt
import numpy as np
from execution_policy import sell_shares

ALIASES={'0050:BRIDGE':'0050','00881:BRIDGE':'00881'}


def initialize(ledger,inputs,seed):
    n=ledger.n
    if (ledger.bridge_enabled or ledger.voo_fx_wait_proxy) and not all(k in inputs for k in ('runtime_bridge_spot_sell','runtime_bridge_history_low','runtime_bridge_history_high')):
        raise ValueError('BRIDGE_FX_SCENARIO_SOURCE_REQUIRED')
    ledger.bridge_rng=np.random.default_rng(np.random.SeedSequence([seed,219,1]))
    ledger.bridge_locked=np.zeros(n);ledger.bridge_spent=np.zeros((n,2))
    ledger.bridge_cash=np.zeros(n);ledger.bridge_pending=np.zeros(n)
    ledger.bridge_built=np.zeros(n,dtype=bool);ledger.bridge_converted=np.zeros(n,dtype=bool)
    ledger.bridge_max_weight=np.zeros(n)
    ledger.bridge_exit_started=np.zeros(n,dtype=bool)
    ledger.bridge_exit_baseline=np.zeros((n,2));ledger.bridge_sell_count=np.zeros((n,2),dtype=int)
    ledger.bridge_fx_ratio=float(inputs.get('runtime_bridge_spot_sell',ledger.fx[0]))/ledger.fx[0]
    low=float(inputs.get('runtime_bridge_history_low',ledger.fx[0]*ledger.bridge_fx_ratio))
    high=float(inputs.get('runtime_bridge_history_high',ledger.fx[0]*ledger.bridge_fx_ratio))
    if (ledger.bridge_enabled or ledger.voo_fx_wait_proxy) and not (np.isfinite(low) and np.isfinite(high) and 0<low<high):
        raise ValueError("BRIDGE_FX_RANGE_REQUIRED")
    ledger.bridge_fx_low=np.full((24,n),low);ledger.bridge_fx_high=np.full((24,n),high)
    ledger.bridge_cash_released=np.zeros(n)
    ledger.bridge_month_index=0
    ledger.bridge_voo_prices=np.repeat(ledger.nominal_prices[:,ledger.codes.index("VOO")][None,:],12,axis=0)


def expensive(ledger):
    p=ledger.inputs['execution_policy']['voo_bridge_d220']
    selling=ledger.fx*ledger.bridge_fx_ratio
    lo=np.min(ledger.bridge_fx_low,axis=0);hi=np.max(ledger.bridge_fx_high,axis=0)
    pos=np.divide(selling-lo,hi-lo,out=np.ones(ledger.n),where=hi>lo)
    return (selling>float(p['usd_expensive_above']))|(pos>=float(p['usd_expensive_range_at_or_above']))


def voo_exception(ledger):
    # A conservative observed-month proxy, not a reconstructed daily 52-week
    # high: an unobserved earlier high can only make the real decline larger.
    price=ledger.nominal_prices[:,ledger.codes.index("VOO")]
    high=np.maximum(ledger.bridge_voo_prices.max(axis=0),price)
    return price < high*.90


def voo_buy_allowed(ledger):
    return ~expensive(ledger) | voo_exception(ledger)


def cap_direct_voo_purchase(ledger,before,proposed,total,costs=True,applicable_cap=None):
    """Apply the same commitment/FX gate to every engine purchase path."""
    if not (ledger.bridge_enabled or ledger.voo_fx_wait_proxy):return proposed
    j=ledger.codes.index("VOO")
    committed=np.zeros(ledger.n)
    if ledger.bridge_enabled:
        committed=before[:,[ledger.codes.index(c) for c in ALIASES]].sum(axis=1)+ledger.bridge_cash+ledger.bridge_pending
    cap=ledger.weights[j] if applicable_cap is None else np.asarray(applicable_cap)
    # FX-only sensitivity changes only FX eligibility; preserve the original
    # proposal/cap so it does not introduce a new 20% purchase restriction.
    room=(np.maximum(total*cap-before[:,j]-committed,0)/(1+cap*(.001425 if costs else 0.))
          if ledger.bridge_enabled else np.full(ledger.n,np.inf))
    room=np.where(voo_buy_allowed(ledger),room,0)
    proposed[:,j]=np.minimum(proposed[:,j],before[:,j]+room)
    return proposed


def release_excess_cash(ledger,assets,reserve):
    if not ledger.bridge_enabled:return
    total=assets.sum(axis=1)+reserve
    held=ledger.values[:,[ledger.codes.index(c) for c in ALIASES]].sum(axis=1)
    need=np.maximum(total*ledger.target["VOO"]-ledger.values[:,ledger.codes.index("VOO")]-held-ledger.bridge_pending,0)
    release=np.maximum(ledger.bridge_cash-need,0)
    ledger.bridge_cash-=release;ledger.bridge_cash_released+=release


def roll_fx_history(ledger):
    selling=ledger.fx*ledger.bridge_fx_ratio
    slot=ledger.bridge_month_index%24
    ledger.bridge_fx_low[slot]=selling;ledger.bridge_fx_high[slot]=selling
    ledger.bridge_voo_prices[ledger.bridge_month_index%12]=ledger.nominal_prices[:,ledger.codes.index("VOO")]
    ledger.bridge_month_index+=1


def step(ledger,assets,reserve,date,drawdown,bull,probability,required_cash,costs=True,tax_cover=None):
    if not ledger.bridge_enabled:
        if ledger.voo_fx_wait_proxy:roll_fx_history(ledger)
        return
    p=ledger.inputs['execution_policy']['voo_bridge_d220']
    ledger.cadence.begin(date,ledger.execution_fx())
    js=[ledger.codes.index(c) for c in ALIASES]
    voo=ledger.codes.index('VOO');n=ledger.n
    # Previous month's sale proceeds settle before this month's purchases;
    # this is more conservative than two actual trading sessions.
    ledger.bridge_cash+=ledger.bridge_pending;ledger.bridge_pending.fill(0)
    release_excess_cash(ledger,assets,reserve)
    exp=expensive(ledger);qualified=ledger.bridge_rng.random(n)<(1-(1-probability)**4)
    fee=.001425 if costs else 0.
    slip=.001 if costs else 0.
    total=assets.sum(axis=1)+reserve
    held=ledger.values[:,js].sum(axis=1)
    room=np.maximum(total*ledger.weights[voo]-ledger.values[:,voo]-held,0)/(1+ledger.weights[voo]*(fee+slip))
    foreign=[j for j,c in enumerate(ledger.codes) if c in {'VOO','VT','009826'}]
    global_cap=float(ledger.inputs['execution_policy_overrides']['global_equity_wrapper_policy_d141']['global_equity_group_cap'])
    room=np.minimum(room,np.maximum(global_cap*total-ledger.values[:,foreign].sum(axis=1),0)/(1+global_cap*(fee+slip)))
    room=np.minimum(room,np.maximum(.60*total-assets[:,0],0)/(1+.60*(fee+slip)))
    # Ringfenced converted proceeds may wait in cash if VOO is not qualified.
    physical=np.maximum(assets[:,2]-required_cash-ledger.pending_stock-ledger.pending_bond-ledger.bond_reserve-ledger.stock_reserve,0)
    buy=np.minimum.reduce((room,ledger.bridge_cash/(1+fee+slip),physical/(1+fee+slip),ledger.cadence.voo_budget(ledger.nominal_prices[:,voo])/ledger.price_level))
    buy=np.where(~exp & qualified,buy,0)
    ledger.bridge_cash-=buy*(1+fee+slip);assets[:,2]-=buy*(1+fee+slip);assets[:,0]+=buy
    ledger.values[:,voo]+=buy;ledger.units[:,voo]+=buy/np.maximum(ledger.nominal_prices[:,voo]*ledger.fx/ledger.price_level,1e-20)
    ledger.cadence.fill('VOO',buy*ledger.price_level,price=ledger.nominal_prices[:,voo])
    ledger.costs+=buy*(fee+slip);ledger.turnover+=buy
    ledger.bridge_converted|=buy>0
    # Proxy original high-zone sell: confirmed monthly bull plus a fresh
    # independent qualified two-leg opportunity. No loss-triggered exit.
    sell_gate=(~exp)&bull&(ledger.bridge_rng.random(n)<(1-(1-probability**2)**4))
    sell_rate=fee+slip+(.001 if costs else 0.)
    first_exit=sell_gate & ~ledger.bridge_exit_started
    ledger.bridge_exit_baseline[first_exit]=ledger.units[first_exit][:,js]
    ledger.bridge_exit_started|=sell_gate & (held>0)
    for k,j in enumerate(js):
        price=ledger.nominal_prices[:,j]/ledger.price_level
        quantity=sell_shares(ledger.units[:,j],ledger.units[:,j],ledger.units[:,j],zero_target=True,
            baseline_excess=ledger.bridge_exit_baseline[:,k],baseline_value_twd=ledger.bridge_exit_baseline[:,k]*price,
            confirmed_batches=ledger.bridge_sell_count[:,k],lot_size=1000)
        quantity=np.where(sell_gate,quantity,0);value=quantity*price
        ledger.values[:,j]-=value;ledger.units[:,j]-=quantity
        ledger.bridge_sell_count[:,k]+=(quantity>0)
        assets[:,0]-=value;assets[:,2]+=value*(1-sell_rate)
        ledger.bridge_pending+=value*(1-sell_rate);ledger.costs+=value*sell_rate;ledger.turnover+=value
        if tax_cover is not None:assets[:,2]+=tax_cover(value*(.001 if costs else 0.))
    # One locked wave. Falling prices never replenish its original principal.
    fresh=(ledger.bridge_locked==0)&exp&~voo_exception(ledger)&(drawdown<=-float(p['tw_crash_drawdown']))&qualified
    total=assets.sum(axis=1)+reserve
    held=ledger.values[:,js].sum(axis=1)
    gap=np.maximum(total*ledger.weights[voo]-ledger.values[:,voo]-held-ledger.bridge_cash-ledger.bridge_pending,0)/(1+ledger.weights[voo]*(fee+slip))
    ledger.bridge_locked=np.where(fresh,gap,ledger.bridge_locked)
    ledger.cadence.begin(date,ledger.execution_fx())
    available=np.maximum(assets[:,2]-required_cash-ledger.pending_stock-ledger.pending_bond-ledger.stock_reserve-ledger.bond_reserve-ledger.earmark(),0)
    cap=np.maximum(.60*total-assets[:,0],0)/(1+.60*(fee+slip))
    budget=np.minimum.reduce((gap,np.maximum(ledger.bridge_locked-ledger.bridge_spent.sum(axis=1),0),available/(1+fee+slip),cap,ledger.cadence.voo_budget(ledger.nominal_prices[:,voo])/ledger.price_level))
    gate=exp&~voo_exception(ledger)&(drawdown<=-float(p['tw_crash_drawdown']))&qualified&~ledger.bridge_exit_started&(date<=dt.date.fromisoformat(p['new_build_cutoff']))
    budget=np.where(gate,budget,0)
    bridge_fill=np.zeros(n)
    for k,j in enumerate(js):
        base=ALIASES[ledger.codes[j]]
        ordinary=ledger.codes.index(base)
        combined_limit=total*float(ledger.core_caps[base])/100+ledger.bridge_locked/2
        combined_room=np.maximum(combined_limit-ledger.values[:,ordinary]-ledger.values[:,j],0)/(1+(fee+slip)*float(ledger.core_caps[base])/100)
        value=np.minimum.reduce((budget/2,np.maximum(ledger.bridge_locked/2-ledger.bridge_spent[:,k],0),combined_room))
        price=ledger.nominal_prices[:,j]/ledger.price_level
        units=np.floor(value/np.maximum(price,1e-20)/1000)*1000
        value=units*price;payment=value*(1+fee+slip)
        ledger.units[:,j]+=units;ledger.values[:,j]+=value;assets[:,0]+=value;assets[:,2]-=payment
        ledger.bridge_spent[:,k]+=value;ledger.costs+=payment-value;ledger.turnover+=value
        bridge_fill+=value
        ledger.bridge_built|=value>0
    # Capacity consumption is not an actual VOO fill or price observation.
    previous_price=ledger.cadence.last_voo_fill_price.copy()
    ledger.cadence.fill('VOO',bridge_fill*ledger.price_level,price=ledger.nominal_prices[:,voo])
    ledger.cadence.last_voo_fill_price=previous_price
    total=assets.sum(axis=1)+reserve
    ledger.bridge_max_weight=np.maximum(ledger.bridge_max_weight,ledger.values[:,js].sum(axis=1)/np.maximum(total,1))
    roll_fx_history(ledger)
