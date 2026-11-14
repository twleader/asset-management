"""Monthly conditional planning model; never a formal report or trading engine."""
from __future__ import annotations
import calendar
import copy
import datetime as dt
import math
from decimal import Decimal, ROUND_HALF_UP, localcontext
from types import SimpleNamespace
import numpy as np
import pre_retirement_cashflow as payroll
from budget_accounting import (reference_price_level,labor_insurance_claim_terms,
                               labor_pension_projection,PensionIndexation,AnnualTaxBudget)
from monthly_engine import spending_tier_for_age,market_spending_adjustment
from scenario_life import resolve_life,birthday as _birthday,age_on as _age
from scenario_money import money as _money,money_difference as _money_difference,quantiles as _quantiles
from scenario_reserves import ReserveStrategy,apply_cashflow
from return_model import (CalendarMarketPath,InflationPath,security_returns,
                          covariance_factor,draw_period_returns,arithmetic_for_geometric_target)
from settings_catalog import SettingsError,BOND_CODES,CASH_CODES,canonical,digest

ENGINE_VERSION='SRPP_SCENARIO_CASHFLOW_V3'
WARNINGS=[
 '這是給定假設下的月頻存續率，不是退休保證，也不是完整正式模型重跑。',
 '股票18%／債券8%年波動率預設為可調示範規劃假設，不是本日觀測或正式模型校準。',
 '假設立即依目標配置建倉與按月檢查股票再平衡帶；未模擬逐檔過渡、成交機會、完整家戶與投資所得稅務、交易成本、交割及日頻買賣閘門。',
 '基準市況生活費與生活cash回補有建模；額外配置戰術預設關閉，開啟時僅為月頻aggregate敏感度，未經績效驗證。',
 '以單一股票及單一債券因子近似；同一資產桶內換標的不構成額外分散或超額報酬。',
 '報酬均值取正式政策的規劃來源；lognormal波動拖累補償不保證Student-t路徑實現該複合報酬。',
 '緊急預備金採固定實質額代理並排除於成功判準，未模擬00865B專屬報酬、緊急提款及補回。',
 '首次資金耗盡即記為失敗；其後路徑停止，不預測借款、支出調整或後續收入恢復。',
 '薪資與勞保勞退沿正式政策帳務假設；法定資格、未來雇主支付及未來法規未由本模擬另行查證。',
 '退休當日為假設下可支用資產預估；未發薪資另列應收，自提勞退不提前計入，薪資年度稅已逐級計算與補退；未含完整家戶及投資所得綜所稅。',
 '保費及未公告年度級距沿既有規劃代理；現有級距表未完整涵蓋低薪，不能視為法定最新或精確保費。',
 '起領年度開始早於估值日時，歷史CPI以固定基準通膨代理暖機；並非真實官方歷史CPI或核定月額，未來通膨不回寫歷史。',
 'MonthlySignals使用合成股票總報酬、完成月與11月暖機的10月均線代理，非每日MA200或雷達同向證據；熊市5交易日僅以每月20合成時段代理。',
 'NOT_MODELED：VOO:0050逐檔容量、12月報酬排名售債、實際券商子帳、急難提款補回、交割、交易成本及日頻買賣硬閘。'
]

def _number(v,label):
    if type(v) not in (int,float) or not math.isfinite(v):raise SettingsError('INPUT_INVALID',label+'必須是有限數值。')
    return float(v)
def _date(v,label):
    try:
        if not isinstance(v,str):raise ValueError
        return dt.date.fromisoformat(v)
    except (ValueError,TypeError) as exc:raise SettingsError('INPUT_INVALID',label+'日期不合法。') from exc

def validate_input(input_data):
    if not isinstance(input_data,dict) or set(input_data)!={'latestAssets','capturedAt'}:raise SettingsError('INPUT_INVALID','凍結資產輸入不完整。')
    p=input_data['latestAssets']
    if not isinstance(p,dict):raise SettingsError('INPUT_INVALID','資產輸入格式不符。')
    snapshot=p.get('snapshot');live=p.get('liveAssets')
    if not isinstance(snapshot,dict) or not isinstance(live,dict) or snapshot.get('id') is None or snapshot.get('id')!=live.get('snapshotId'):
        raise SettingsError('INPUT_INVALID','最新快照與即時估值的身份不一致。')
    if snapshot.get('snapshotDate')!=live.get('snapshotDate'):raise SettingsError('INPUT_INVALID','快照日期不一致。')
    _date(snapshot.get('snapshotDate'),'快照')
    try:
        captured=dt.datetime.fromisoformat(input_data['capturedAt'].replace('Z','+00:00'))
        generated=dt.datetime.fromisoformat(p['generatedAt'].replace('Z','+00:00'))
        if captured.tzinfo is None or generated.tzinfo is None or abs((captured-generated).total_seconds())>300:raise ValueError
    except (KeyError,ValueError,AttributeError,TypeError) as exc:raise SettingsError('INPUT_INVALID','資產產生時間與凍結時間須帶時區且相差不超過五分鐘。') from exc
    asof=captured.astimezone(dt.timezone(dt.timedelta(hours=8))).date()
    total=_number(live.get('liveTotalAssets'),'資產總額')
    if total<=0:raise SettingsError('INPUT_INVALID','資產總額必須大於零。')
    if not isinstance(live.get('stocks'),list) or not isinstance(snapshot.get('funds'),list):raise SettingsError('INPUT_INVALID','持股及基金明細格式不完整。')
    stock=sum(_number(r.get('liveValue'),'持股估值') for r in live['stocks'] if isinstance(r,dict))
    if len([r for r in live['stocks'] if isinstance(r,dict)])!=len(live['stocks']):raise SettingsError('INPUT_INVALID','持股明細格式不符。')
    deposits=_number(snapshot.get('totalDeposit'),'存款總額')
    funds=sum(_number(r.get('currentValue'),'基金估值') for r in snapshot['funds'])
    tolerance=max(.02,total*1e-8)
    for actual,expected in [(stock,_number(live.get('liveStockValue'),'持股合計')),(funds,_number(live.get('totalFundValue'),'基金合計')),(deposits,_number(live.get('totalDeposit'),'即時存款合計')),(stock+funds+deposits,total)]:
        if abs(actual-expected)>tolerance:raise SettingsError('INPUT_INVALID','資產明細與總額無法對帳。')
    return asof,total,p

def return_assumptions(model,values):
    wrapper=model['global_equity_wrapper_assumptions']
    reference=float(wrapper['forward_return_assumptions']['inflation_assumption']['midpoint'])
    geometric=float(wrapper['core_real_compound_return_before_wrapper_overlay'])-float(wrapper['target_weighted_equity_wrapper_drag'])
    equity=arithmetic_for_geometric_target(geometric,values['equityVolatility'])
    bond_nominal=float(model['bond_forward_assumptions']['weighted_nominal_yield_net_expense_before_fx'])
    bond=(1+bond_nominal)/(1+reference)-1
    cash=float(model['asset_assumptions']['cash']['real_return_at_2_25_percent_inflation'])
    vol=np.array([values['equityVolatility'],values['bondVolatility'],.005,float(model['asset_assumptions']['usd_twd']['volatility'])])
    c=model['correlations']
    eb=float(c['equity_bonds']);ec=float(c['equity_cash']);bc=float(c['bonds_cash']);ef=float(c['equity_usd_twd']);bf=float(c['bonds_usd_twd'])
    correlation=np.array([[1,eb,ec,ef],[eb,1,bc,bf],[ec,bc,1,0],[ef,bf,0,1]])
    return reference,geometric,np.array([equity,bond,cash,0]),vol,covariance_factor(np.outer(vol,vol)*correlation)

def simulate_one(context,values,input_data,simulations,seed):
    model=context.model;asof,nominal_total,payload=validate_input(input_data)
    birth=_date(values['birthDate'],'生日');retire=_date(values['retirementDate'],'退休')
    end=_birthday(birth,values['planningAge'])
    if birth>asof or asof>=end or retire>=end:raise SettingsError('CONFIG_INVALID','生日及規劃終點必須符合本輪日期與退休日期。')
    reference,g,mean,vol,factor=return_assumptions(model,values)
    opening_level=reference_price_level(asof,reference);total=nominal_total/opening_level
    reserve=float(Decimal(str(values['emergencyDepositReserve']))+Decimal(str(values['emergencyEtfReserve'])))
    if reserve>total:raise SettingsError('RESERVE_EXCEEDS_ASSETS','緊急備用金合計超過本輪可驗證期初資產。')
    weights=values['targetWeights'];bonds=math.fsum(w for c,w in weights.items() if c in BOND_CODES);cash=math.fsum(w for c,w in weights.items() if c in CASH_CODES);equity=1-bonds-cash
    target=np.array([equity,bonds,cash]);notional=np.maximum(total*target-np.array([0,0,reserve]),0)
    active_weights=notional/notional.sum() if notional.sum()>0 else np.array([0.,0.,1.])
    assets=np.broadcast_to(active_weights*max(total-reserve,0),(simulations,3)).copy()
    strategy=ReserveStrategy(model,values,simulations,reserve);assets=strategy.open(assets)
    opening_available=assets.sum(axis=1).copy()
    price_level=np.full(simulations,opening_level);failed=np.zeros(simulations,dtype=bool);failure_ages=np.full(simulations,np.nan)
    life=resolve_life(context,values,asof,retire,end,reference,simulations)
    payroll_config=life['config'];payroll_events={_date(r['pay_date'],'薪資'):r for r in life['ledger']}
    pension_events=life['pension_events'];pension=life['pension'];claim=life['claim'];events=life['events'];tax_events=life['tax_events']
    dates={asof,end,retire,retire+dt.timedelta(days=1),claim,*payroll_events,*pension_events,*events,*tax_events}
    for year in range(asof.year,end.year+1):
        dates.update({_birthday(birth,age) for age in [75,values['careStartAge']] if birth.year+age==year})
        for month in range(1,13):dates.add(dt.date(year,month,1));dates.add(dt.date(year,month,calendar.monthrange(year,month)[1]))
    grid=sorted(d for d in dates if asof<=d<=end)
    def annual_spend(day):
        if day<=retire:return float(payroll_config['planned_living_costs']['monthly_living_expense'])*12
        return spending_tier_for_age(_age(birth,day),int(model['labor_insurance']['start_age']),values['careStartAge'],values['livingExpense'],values['livingExpense'],values['laterLivingExpense'],values['careExpense'],75,values['careStartAge'])
    limits={}
    for start,finish in zip(grid,grid[1:]):limits[start.year]=limits.get(start.year,0)+annual_spend(start)*(finish-start).days/(366 if calendar.isleap(start.year) else 365)
    budget=AnnualTaxBudget(limits,simulations)
    bounds=model['global_equity_wrapper_assumptions']['forward_return_assumptions']['inflation_assumption'];shift=values['inflationRate']-reference
    low=max(0,float(bounds['minimum'])+shift);high=max(low,float(bounds['maximum'])+shift)
    inflation=InflationPath(simulations,seed,low,high)
    market=CalendarMarketPath(SimpleNamespace(draw_period_returns=draw_period_returns),simulations,seed,factor,math.sqrt(5/7))
    global_weight=sum(weights.get(c,0) for c in ['009826','VOO','VT']);exposure=global_weight/equity if equity>0 else 0
    paths=[dict(date=str(asof),age=_age(birth,asof),**_quantiles(assets.sum(axis=1)))];retirement_values=None
    equity_index=np.ones(simulations)
    first_full_month=asof if asof.day==1 else (asof.replace(day=1)+dt.timedelta(days=32)).replace(day=1)
    for start,finish in zip(grid,grid[1:]):
        fraction=(finish-start).days/365.25;opening=price_level.copy();infl=inflation.period(start,fraction)
        draws=market.period(start,mean,fraction)
        security,cash_return=security_returns(draws,np.array([0,1]),np.array([exposure,1.]),infl,fraction,reference)
        assets*=1+np.column_stack((security,cash_return));price_level*=1+infl
        equity_index*=1+security[:,0]
        pension.observe(start,finish,opening,price_level)
        spending_fraction=(finish-start).days/(366 if calendar.isleap(start.year) else 365)
        base_spend=annual_spend(start);delta=np.zeros(simulations)
        if start>retire and strategy.base_enabled:
            delta=market_spending_adjustment(strategy.base_bear,strategy.base_bull,values['marketSpendingAdjustment'])
            delta=np.maximum(base_spend+delta,0)-base_spend
        reclaim=budget.adjust_limit(delta*spending_fraction,start.year)
        flow=-(base_spend+delta)*spending_fraction-reclaim
        row=payroll_events.get(finish)
        if row:
            taxes=float(row['income_tax_withholding'])+float(row['supplemental_nhi_high_bonus'])
            flow+=float(row['net_cash_after_payroll'])/price_level+budget.cover(taxes/price_level,finish.year)
        if finish in tax_events:
            settlement=float(tax_events[finish])/price_level
            flow+=settlement+budget.cover(-settlement,finish.year)
        if finish==end and life['terminal_tax']<0:
            terminal=float(life['terminal_tax'])/price_level
            flow+=terminal+budget.cover(-terminal,finish.year)
        if finish in pension_events:flow+=pension_events[finish]/price_level
        if finish.day==calendar.monthrange(finish.year,finish.month)[1] and finish>=claim:
            month_start=finish.replace(day=1);eligible=max(0,(finish-max(month_start,claim)).days+1)
            flow+=pension.payment(finish)*(eligible/finish.day)/price_level
        flow-=events.get(finish,0.)
        assets,exhausted=apply_cashflow(assets,flow)
        newly=exhausted&~failed;failed|=exhausted;failure_ages[newly]=(finish-birth).days/365.25
        assets[failed]=0.
        # Dates only split cash flows; market observations and allocation occur once per complete month.
        if finish.day==calendar.monthrange(finish.year,finish.month)[1] and finish>=first_full_month:
            assets=strategy.month_end(assets,equity_index,finish,failed,target,finish>retire)
        aggregate=assets.sum(axis=1)
        if retirement_values is None and finish>=retire:retirement_values=aggregate.copy()
        if finish.month==12 and finish.day==31 or finish==end:paths.append(dict(date=str(finish),age=_age(birth,finish),**_quantiles(aggregate)))
    surviving=int((~failed).sum());prob=surviving/simulations;z=1.959963984540054;denom=1+z*z/simulations
    center=(prob+z*z/(2*simulations))/denom;half=z*math.sqrt(prob*(1-prob)/simulations+z*z/(4*simulations**2))/denom
    if retirement_values is None or retire<=asof:retirement_values=opening_available
    modeled=[f['id'] for f in context.fields if f['simulationSupported']];not_modeled=[f['id'] for f in context.fields if not f['simulationSupported']]
    assumptions=dict(baseCurrency='TWD_2026_08_REAL',purchasingPowerBaseDate='2026-08-31',asOfDate=str(asof),assetSnapshotDate=payload['snapshot']['snapshotDate'],targetPriceComplete=payload.get('targetPriceComplete'),retirementDate=str(retire),planningAge=values['planningAge'],simulations=simulations,seed=seed,reserveExcluded=True,protectedReserveRealTwd=_money(reserve),referenceInflation=reference,scenarioInflationBounds=dict(low=low,high=high),equityRealCompoundPlanningTarget=g,equityArithmeticPlanningMean=float(mean[0]),bondReferenceRealArithmeticMean=float(mean[1]),cashReferenceRealArithmeticMean=float(mean[2]),equityVolatility=values['equityVolatility'],bondVolatility=values['bondVolatility'],fxVolatility=float(vol[3]),fxVolatilitySource=model['asset_assumptions']['usd_twd'].get('volatility_source','既有政策來源，非本日刷新'),bondNominalPlanningYield=float(model['bond_forward_assumptions']['weighted_nominal_yield_net_expense_before_fx']),returnMeanSource='Same-bundle global_equity_wrapper_assumptions/bond_forward_assumptions/asset_assumptions.cash',equityMeanConversion='exp(log1p(g)+0.5*volatility^2)-1; approximation, not Student-t compound calibration',approximationNotes=WARNINGS,birthDate=str(birth),cashflowSchedule=life['schedule'],reserveStrategy=strategy.assumptions())
    return dict(scope='MONTHLY_SCENARIO_NOT_FORMAL_MODEL',successProbability=prob,successProbabilityCi95=dict(low=max(0,center-half),high=min(1,center+half)),percentilePaths=paths,retirementAssets=_quantiles(retirement_values),terminalAssets=_quantiles(assets.sum(axis=1)),depletionAge=_quantiles(failure_ages[np.isfinite(failure_ages)],monetary=False) if failed.any() else None,modeledFields=modeled,notModeledFields=not_modeled,assumptions=assumptions,reserveStrategyAudit=strategy.audit())

def simulate(context,request):
    checked=context.validate(request)
    if set(request)-{'schemaVersion','baseBundleSha256','configSha256','values','input','seed','simulations','catalogVersion','inputSha256'}:raise SettingsError('INVALID_REQUEST','模擬含未知欄位。',400)
    if request.get('catalogVersion', context.catalog['catalogVersion']) != context.catalog['catalogVersion']:raise SettingsError('CATALOG_UNSUPPORTED','欄位規約版本不符。',409)
    if 'inputSha256' in request and request['inputSha256']!=digest(request.get('input')):raise SettingsError('INPUT_HASH_MISMATCH','凍結輸入與雜湊不符。',409)
    simulations=request.get('simulations');seed=request.get('seed')
    if type(simulations) is not int or not 100<=simulations<=100000 or type(seed) is not int or not 0<=seed<=2147483647:raise SettingsError('CONFIG_INVALID','模擬次數或亂數種子超出允許範圍。')
    input_data=request.get('input');validate_input(input_data)
    baseline=simulate_one(context,context.defaults,input_data,simulations,seed)
    scenario=baseline if canonical(checked['normalizedValues'])==canonical(context.defaults) else simulate_one(context,checked['normalizedValues'],input_data,simulations,seed)
    diffs=dict(successProbability=scenario['successProbability']-baseline['successProbability'],retirementAssetsP50=_money_difference(scenario['retirementAssets']['p50'],baseline['retirementAssets']['p50']),terminalAssetsP50=_money_difference(scenario['terminalAssets']['p50'],baseline['terminalAssets']['p50']))
    return dict(schemaVersion=1,baseBundleSha256=context.base,configSha256=checked['configSha256'],inputSha256=digest(input_data),engineVersion=ENGINE_VERSION,result=dict(baseline=baseline,scenario=scenario,differences=diffs,changes=checked['changes'],warnings=WARNINGS))
