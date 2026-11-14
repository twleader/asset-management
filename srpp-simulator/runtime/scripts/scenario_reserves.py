"""Pure aggregate liquidity and monthly strategy state for scenario V2.

All transfers conserve the supplied assets. No broker, price source or output I/O.
"""
from __future__ import annotations
import copy
import numpy as np
from bear_ladder import BearLadder,BullLadder
from monthly_engine import MonthlySignals
from scenario_money import money,quantiles


def refill_cash(assets,target):
    """Return an independent portfolio, actual transfer and unmet cash target."""
    result=np.array(assets,dtype=float,copy=True)
    target=np.broadcast_to(np.asarray(target,dtype=float),(len(result),))
    need=np.maximum(target-result[:,2],0.)
    investments=result[:,:2].sum(axis=1)
    moved=np.minimum(need,investments)
    fraction=np.divide(moved,investments,out=np.zeros_like(moved),where=investments>0)
    result[:,:2]*=1-fraction[:,None];result[:,2]+=moved
    return result,moved,np.maximum(target-result[:,2],0.)


def apply_cashflow(assets,flow):
    """Cash is spendable below its investment floor; deficits sell investments."""
    result=np.array(assets,dtype=float,copy=True);result[:,2]+=flow
    result,_,_=refill_cash(result,0.)
    exhausted=result.sum(axis=1)<=0
    result[exhausted]=0.
    return result,exhausted


def ordinary_rebalance(assets,reserve,weights,low,high,protected_cash,allowed):
    result=np.array(assets,dtype=float,copy=True);available=result.sum(axis=1);total=available+reserve
    ratio=np.divide(result[:,0],total,out=np.zeros_like(total),where=total>0)
    hit=np.asarray(allowed)&((ratio<low)|(ratio>high))
    desired=np.maximum(total[:,None]*np.asarray(weights)-np.array([0.,0.,reserve]),0.)
    denom=desired.sum(axis=1)
    desired*=np.divide(available,denom,out=np.zeros_like(available),where=denom>0)[:,None]
    cash=np.minimum(available,np.maximum(desired[:,2],protected_cash))
    investing=np.maximum(available-cash,0.)
    proportions=np.asarray(weights[:2],dtype=float)
    proportions=proportions/proportions.sum() if proportions.sum()>0 else np.array([0.,0.])
    desired[:,:2]=investing[:,None]*proportions;desired[:,2]=cash
    result[hit]=desired[hit]
    return result


class ReserveStrategy:
    def __init__(self,model,values,paths,reserve):
        self.v=values;self.paths=paths;self.reserve=reserve
        self.base_enabled=model['simulation_contract']['base_living_runway_market_branches'] is True
        policy=copy.deepcopy(model['execution_policy']['dynamic_allocation_policy_d141'])
        for key,source in [('bear_bond_sale_max_share','bearBondSaleMaxShare'),('total_bond_floor','minimumBondWeight'),('bear_equity_cap','bearEquityCap'),('monthly_bull_first_equity_target','bullEquityFloor')]:policy[key]=values[source]
        policy['bear_ladder_d141']=copy.deepcopy(values['bearLadder'])
        self.bear=BearLadder(policy,paths);self.bull=BullLadder(policy,paths)
        source_threshold=model['simulation_contract']['monthly_signal_proxy']['bear_drawdown_initial']
        self.signals=MonthlySignals(paths,bear_drawdown=policy['bear_ladder_d141'][0]['drawdown'])
        self.base_signals=MonthlySignals(paths,bear_drawdown=source_threshold)
        self.base_bull=np.zeros(paths,dtype=bool);self.base_bear=np.zeros(paths,dtype=bool)
        self.bear_cash_budget=np.zeros(paths);self.bear_cash_spent=np.zeros(paths)
        self.months=0;self.monthly=[]
        self.opening_transfer=np.zeros(paths);self.opening_shortfall=np.zeros(paths)
    def open(self,assets):
        result,self.opening_transfer,self.opening_shortfall=refill_cash(assets,self.v['livingReserveCeiling'])
        return result
    def month_end(self,assets,index,date,failed,weights,retired):
        self.months+=1;n=self.paths;v=self.v
        prior_anchor=self.signals.anchor.copy()
        bull,bear,drawdown=self.signals.update(index,v['tacticalWaitMonths'])
        self.base_bull,self.base_bear,_=self.base_signals.update(index,6)
        if not self.base_enabled:self.base_bull[:]=False;self.base_bear[:]=False
        target=np.where(self.base_bull & retired,v['livingReserveCeiling'],v['livingReserveFloor'])
        target=np.where(failed,0.,target)
        result,refill,shortfall=refill_cash(assets,target)
        cash_deployed=np.zeros(n);bond_sold=np.zeros(n);equity_sold=np.zeros(n)
        total=result.sum(axis=1)+self.reserve
        if v['bearStrategyEnabled'] and retired:
            available_cash=np.minimum(np.maximum(result[:,2]-v['livingReserveFloor'],0),v['bearLivingDeploymentCap'])
            active_before=self.bear.active.copy()
            self.bear.update(drawdown,bear & ~failed,total,result[:,1],available_cash=available_cash,total_bonds=result[:,1],equity=result[:,0])
            fresh=self.bear.active & ~active_before
            self.bear_cash_budget[fresh]=available_cash[fresh];self.bear_cash_spent[fresh]=0
            cash_room=np.minimum(available_cash,np.maximum(self.bear_cash_budget-self.bear_cash_spent,0))
            bond_room=np.minimum(self.bear.bond_allowance(),np.maximum(result[:,1]-v['minimumBondWeight']*total,0))
            equity_room=np.maximum(v['bearEquityCap']*total-result[:,0],0)
            rung,request=self.bear.proposal(self.months*20,bear & ~failed)
            proposed=np.minimum(request,np.minimum(cash_room+bond_room,equity_room))
            proposed=np.where(self.bear.growing_fill(proposed),proposed,0.)
            cash_deployed=np.minimum(proposed,cash_room);bond_sold=proposed-cash_deployed
            result[:,2]-=cash_deployed;result[:,1]-=bond_sold;result[:,0]+=proposed
            self.bear_cash_spent+=cash_deployed
            self.bear.record(rung,proposed,bond_sold,self.months*20)
        if v['bullStrategyEnabled'] and retired:
            anchor=np.where(np.isnan(self.signals.anchor),prior_anchor,self.signals.anchor)
            gain=np.divide(index,anchor,out=np.full(n,-np.inf),where=np.isfinite(anchor))-1
            self.bull.update(gain,bull & ~failed,total,result[:,0],corrected=drawdown<=-.10)
            rung,request=self.bull.proposal(self.months,~failed)
            equity_sold=np.minimum(request,np.maximum(result[:,0]-v['bullEquityFloor']*total,0))
            equity_sold=np.where(self.bull.growing_fill(equity_sold),equity_sold,0.)
            cash_fill=np.minimum(equity_sold,np.maximum(v['livingReserveCeiling']-result[:,2],0))
            result[:,0]-=equity_sold;result[:,2]+=cash_fill;result[:,1]+=equity_sold-cash_fill
            self.bull.record(rung,equity_sold,self.months);self.signals.sold(equity_sold>1e-8)
            self.bull.close_window(self.bull.pending_close)
            refill+=cash_fill;shortfall=np.maximum(target-result[:,2],0)
        busy=self.bear.active|self.bull.active|(cash_deployed+bond_sold+equity_sold>1e-8)|failed
        result=ordinary_rebalance(result,self.reserve,weights,v['equityFloor'],v['equityCap'],target,~busy)
        result[failed]=0.
        self.monthly.append(dict(date=str(date),bullSignalPathCount=int(self.base_bull.sum()),bearSignalPathCount=int(self.base_bear.sum()),
            bullFillPathCount=int((equity_sold>1e-8).sum()),bearFillPathCount=int((cash_deployed+bond_sold>1e-8).sum()),
            bullEquitySoldRealTwd=quantiles(equity_sold),bearCashDeployedRealTwd=quantiles(cash_deployed),
            bearBondsSoldRealTwd=quantiles(bond_sold),livingRefillRealTwd=quantiles(refill),livingShortfallRealTwd=quantiles(shortfall)))
        return result
    def assumptions(self):
        v=self.v
        return dict(emergencyDepositReserveRealTwd=money(v['emergencyDepositReserve']),emergencyEtfReserveRealTwd=money(v['emergencyEtfReserve']),
            protectedReserveRealTwd=money(self.reserve),livingReserveCeilingRealTwd=money(v['livingReserveCeiling']),livingReserveFloorRealTwd=money(v['livingReserveFloor']),
            bearLivingDeploymentCapRealTwd=money(v['bearLivingDeploymentCap']),bullStrategyEnabled=v['bullStrategyEnabled'],bearStrategyEnabled=v['bearStrategyEnabled'],
            tacticalWaitMonths=v['tacticalWaitMonths'],bearBondSaleMaxShare=v['bearBondSaleMaxShare'],minimumBondWeight=v['minimumBondWeight'],bearEquityCap=v['bearEquityCap'],
            bullEquityFloor=v['bullEquityFloor'],marketSpendingAdjustmentRealTwd=money(v['marketSpendingAdjustment']),bearLadder=copy.deepcopy(v['bearLadder']),bullLadder=copy.deepcopy(v['bullLadder']),
            signalBasis='MONTHLY_SYNTHETIC_TOTAL_RETURN_PROXY',liquidityBranchesEnabled=self.base_enabled)
    def audit(self):
        return dict(openingCashTransferRealTwd=quantiles(self.opening_transfer),openingLivingShortfallRealTwd=quantiles(self.opening_shortfall),monthUpdateCount=self.months,
            bearFillCount=int(self.bear.lifetime_fills.sum()),bullFillCount=int(self.bull.lifetime_fills.sum()),bearMaximumWindowFills=int(self.bear.maximum_window_fills),
            bullMaximumWindowFills=int(self.bull.maximum_window_fills),bearMaximumFirstBatchShare=float(self.bear.maximum_first_batch_share),bullMaximumFirstBatchShare=float(self.bull.maximum_first_batch_share),monthlyAudit=self.monthly)
