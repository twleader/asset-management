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
from monthly_engine import spending_tier_for_age
from return_model import (CalendarMarketPath,InflationPath,security_returns,
                          covariance_factor,draw_period_returns,arithmetic_for_geometric_target)
from settings_catalog import SettingsError,BOND_CODES,CASH_CODES,canonical,digest

ENGINE_VERSION='SRPP_SCENARIO_CASHFLOW_V1'
WARNINGS=[
 '這是給定假設下的月頻存續率，不是退休保證，也不是完整正式模型重跑。',
 '股票18%／債券8%年波動率預設為可調示範規劃假設，不是本日觀測或正式模型校準。',
 '假設立即依目標配置建倉與按月檢查股票再平衡帶；未模擬逐檔過渡、成交機會、完整個人稅務、交易成本、交割及日頻買賣閘門。',
 '未模擬市況條件生活費增減及牛熊戰術擇時，沒有將這些規則的效益加進存續率。',
 '以單一股票及單一債券因子近似；同一資產桶內換標的不構成額外分散或超額報酬。',
 '報酬均值取正式政策的規劃來源；lognormal波動拖累補償不保證Student-t路徑實現該複合報酬。',
 '緊急預備金採固定實質額代理並排除於成功判準，未模擬00865B專屬報酬、緊急提款及補回。',
 '首次資金耗盡即記為失敗；其後路徑停止，不預測借款、支出調整或後續收入恢復。',
 '薪資與勞保勞退沿正式政策帳務假設；法定資格、未來雇主支付及未來法規未由本模擬另行查證。'
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

def _birthday(birth,age):
    try:return birth.replace(year=birth.year+age)
    except ValueError:return birth.replace(year=birth.year+age,day=28)
def _age(birth,date):return date.year-birth.year-((date.month,date.day)<(birth.month,birth.day))
def _money(value):
    """Explicit wire boundary: simulation floats never become stored money floats."""
    with localcontext() as context:
        context.prec = 400
        amount = value if isinstance(value, Decimal) else Decimal(str(value))
        if not amount.is_finite():
            raise SettingsError('NUMERIC_RESULT_INVALID','模擬金額不是有限數值。')
        rounded = amount.quantize(Decimal('0.01'), rounding=ROUND_HALF_UP)
        if rounded.is_zero():
            rounded = Decimal('0.00')
        return format(rounded, 'f')

def _money_difference(left, right):
    with localcontext() as context:
        context.prec = 400
        return _money(Decimal(left) - Decimal(right))

def _quantiles(values, monetary=True):
    quantiles = np.quantile(values, [.1, .5, .9])
    encoded = [_money(float(v)) if monetary else round(float(v), 2) for v in quantiles]
    return dict(zip(('p10','p50','p90'), encoded))

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
    birth=_date(model['labor_insurance']['birth_date'],'生日');retire=_date(values['retirementDate'],'退休')
    end=_birthday(birth,values['planningAge'])
    if asof>=end or retire>=end:raise SettingsError('CONFIG_INVALID','規劃終點必須晚於目前日期及退休日期。')
    reference,g,mean,vol,factor=return_assumptions(model,values)
    opening_level=reference_price_level(asof,reference);total=nominal_total/opening_level
    reserve=min(float(model['execution_policy']['permanent_reserve']),total)
    weights=values['targetWeights'];bonds=math.fsum(w for c,w in weights.items() if c in BOND_CODES);cash=math.fsum(w for c,w in weights.items() if c in CASH_CODES);equity=1-bonds-cash
    target=np.array([equity,bonds,cash]);notional=np.maximum(total*target-np.array([0,0,reserve]),0)
    active_weights=notional/notional.sum() if notional.sum()>0 else np.array([0.,0.,1.])
    assets=np.broadcast_to(active_weights*max(total-reserve,0),(simulations,3)).copy()
    price_level=np.full(simulations,opening_level);failed=np.zeros(simulations,dtype=bool);failure_ages=np.full(simulations,np.nan)
    payroll_config=copy.deepcopy(__import__('json').loads((context.root/'assumptions/pre_retirement_payroll.json').read_text()))
    ledger=payroll.scenario(payroll_config,retire,asof)['ledger'] if retire>asof else []
    payroll_events={_date(r['pay_date'],'薪資'):r for r in ledger}
    if retire<=_date(model['labor_retirement']['balance_as_of_date'],'勞退帳戶估值'):
        raise SettingsError('CONFIG_INVALID','退休日期早於既有勞退帳戶估值，缺少該情境帳務證據。')
    labor=labor_pension_projection(model['labor_retirement'],payroll,payroll_config,retire)
    pension_events={_date(k,'勞退'):float(v) for k,v in labor['receipt_events'].items()}
    terms=labor_insurance_claim_terms(birth,retire,model['labor_insurance'])
    pension=PensionIndexation(simulations,terms['claim_date'].year,terms['monthly_nominal'])
    spending=model['spending'];events={}
    for r in model['major_expense_events']['events']:
        day=_date(r['date'],'大額支出');events[day]=events.get(day,0)+float(r['amount'])
    for first,interval,amount in [(int(spending['home_repair_first_event_year']),values['homeRepairInterval'],values['homeRepairCost']),(int(spending['appliance_first_event_year']),values['applianceInterval'],values['applianceCost'])]:
        for year in range(first,end.year+1,interval):
            day=dt.date(year,8,31);events[day]=events.get(day,0)+amount
    # Exact flow dates split the shared monthly market innovation; no new draw.
    dates={asof,end,retire,retire+dt.timedelta(days=1),terms['claim_date'],*payroll_events,*pension_events,*events}
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
    for start,finish in zip(grid,grid[1:]):
        fraction=(finish-start).days/365.25;opening=price_level.copy();infl=inflation.period(start,fraction)
        draws=market.period(start,mean,fraction)
        security,cash_return=security_returns(draws,np.array([0,1]),np.array([exposure,1.]),infl,fraction,reference)
        assets*=1+np.column_stack((security,cash_return));price_level*=1+infl
        pension.observe(start,finish,opening,price_level)
        debit=annual_spend(start)*(finish-start).days/(366 if calendar.isleap(start.year) else 365)
        flow=np.full(simulations,-debit)
        row=payroll_events.get(finish)
        if row:
            taxes=float(row['income_tax_withholding'])+float(row['supplemental_nhi_high_bonus'])
            flow+=float(row['net_cash_after_payroll'])/price_level+budget.cover(taxes/price_level,finish.year)
        if finish in pension_events:flow+=pension_events[finish]/price_level
        # Initial benefit month is prorated by actual eligible days; no preclaim income.
        if finish.day==calendar.monthrange(finish.year,finish.month)[1] and finish>=terms['claim_date']:
            month_start=finish.replace(day=1);eligible=max(0,(finish-max(month_start,terms['claim_date'])).days+1)
            flow+=pension.payment(finish)*(eligible/finish.day)/price_level
        flow-=events.get(finish,0.)
        # Cash can be funded from the same hypothetical liquid portfolio. No broker calls.
        aggregate=assets.sum(axis=1)+flow
        newly=(aggregate<=0)&~failed;failed|=newly
        failure_ages[newly]=(finish-birth).days/365.25
        aggregate=np.maximum(aggregate,0);aggregate[failed]=0
        current=assets.sum(axis=1);equity_ratio=np.divide(assets[:,0],current+reserve,out=np.zeros(simulations),where=current+reserve>0)
        rebalance=(equity_ratio<values['equityFloor'])|(equity_ratio>values['equityCap'])
        shares=np.divide(assets,current[:,None],out=np.broadcast_to(active_weights,assets.shape).copy(),where=current[:,None]>0)
        shares[rebalance]=active_weights
        assets=aggregate[:,None]*shares
        if retirement_values is None and finish>=retire:retirement_values=aggregate.copy()
        if finish.month==12 and finish.day==31 or finish==end:paths.append(dict(date=str(finish),age=_age(birth,finish),**_quantiles(aggregate)))
    surviving=int((~failed).sum());prob=surviving/simulations;z=1.959963984540054;denom=1+z*z/simulations
    center=(prob+z*z/(2*simulations))/denom;half=z*math.sqrt(prob*(1-prob)/simulations+z*z/(4*simulations**2))/denom
    if retirement_values is None:retirement_values=np.full(simulations,max(total-reserve,0))
    if retire<=asof:retirement_values=np.full(simulations,max(total-reserve,0))
    modeled=[f['id'] for f in context.fields if f['simulationSupported']];not_modeled=[f['id'] for f in context.fields if not f['simulationSupported']]
    return dict(scope='MONTHLY_SCENARIO_NOT_FORMAL_MODEL',successProbability=prob,successProbabilityCi95=dict(low=max(0,center-half),high=min(1,center+half)),percentilePaths=paths,retirementAssets=_quantiles(retirement_values),terminalAssets=_quantiles(assets.sum(axis=1)),depletionAge=_quantiles(failure_ages[np.isfinite(failure_ages)],monetary=False) if failed.any() else None,modeledFields=modeled,notModeledFields=not_modeled,assumptions=dict(baseCurrency='TWD_2026_08_REAL',purchasingPowerBaseDate='2026-08-31',asOfDate=str(asof),assetSnapshotDate=payload['snapshot']['snapshotDate'],targetPriceComplete=payload.get('targetPriceComplete'),retirementDate=str(retire),planningAge=values['planningAge'],simulations=simulations,seed=seed,reserveExcluded=True,protectedReserveRealTwd=_money(reserve),referenceInflation=reference,scenarioInflationBounds=dict(low=low,high=high),equityRealCompoundPlanningTarget=g,equityArithmeticPlanningMean=float(mean[0]),bondReferenceRealArithmeticMean=float(mean[1]),cashReferenceRealArithmeticMean=float(mean[2]),equityVolatility=values['equityVolatility'],bondVolatility=values['bondVolatility'],fxVolatility=float(vol[3]),fxVolatilitySource=model['asset_assumptions']['usd_twd'].get('volatility_source','既有政策來源，非本日刷新'),bondNominalPlanningYield=float(model['bond_forward_assumptions']['weighted_nominal_yield_net_expense_before_fx']),returnMeanSource='Same-bundle global_equity_wrapper_assumptions/bond_forward_assumptions/asset_assumptions.cash',equityMeanConversion='exp(log1p(g)+0.5*volatility^2)-1; approximation, not Student-t compound calibration',approximationNotes=WARNINGS))

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
