"""Scenario-local payroll, benefits and dated expenditure; immutable source inputs."""
from __future__ import annotations
import calendar
import copy
import datetime as dt
import numpy as np
import pre_retirement_cashflow as payroll
from budget_accounting import labor_insurance_claim_terms,labor_pension_projection,PensionIndexation,reference_price_level
from scenario_money import money
from scenario_tax import annual_salary_tax
from settings_catalog import PAYROLL_POINTERS,pointer_set,SettingsError


def birthday(birth,age):
    try:return birth.replace(year=birth.year+age)
    except ValueError:return birth.replace(year=birth.year+age,day=28)


def age_on(birth,date):return date.year-birth.year-((date.month,date.day)<(birth.month,birth.day))


def pension_state(paths,claim,monthly,asof,reference):
    """Fill only missing CPI observation state, with an explicitly disclosed proxy."""
    year_start=dt.date(claim.year,1,1)
    if year_start>=asof:
        return PensionIndexation(paths,claim.year,monthly),'SCENARIO_CPI'
    history=PensionIndexation(1,claim.year,monthly)
    dates={year_start,asof}
    for year in range(claim.year,asof.year+1):
        for month in range(1,13):
            first=dt.date(year,month,1)
            last=dt.date(year,month,calendar.monthrange(year,month)[1])
            if year_start<first<asof:dates.add(first)
            if year_start<last<asof:dates.add(last)
    grid=sorted(dates)
    for start,finish in zip(grid,grid[1:]):
        history.observe(start,finish,np.array([reference_price_level(start,reference)]),np.array([reference_price_level(finish,reference)]))
        if finish>=claim and (finish.month,finish.day)==(6,30):history.payment(finish)
    state=PensionIndexation(paths,claim.year,monthly)
    for key,value in vars(history).items():
        if isinstance(value,np.ndarray):value=np.broadcast_to(value,(paths,)).copy()
        elif isinstance(value,list):value=copy.deepcopy(value)
        setattr(state,key,value)
    return state,'REFERENCE_INFLATION_HISTORY_PROXY'


def payroll_wire(config,ledger,asof,retire,annual_tax):
    fields={
        'contractualMonthlySalaryNominalTwd':'contractual_monthly_salary',
        'regularSalaryNominalTwd':'regular_salary','bonusNominalTwd':'bonus',
        'grossPayNominalTwd':'gross_pay','laborInsuranceNominalTwd':'labor_and_employment_insurance',
        'healthInsuranceNominalTwd':'health_insurance','selfPensionNominalTwd':'voluntary_labor_pension',
        'withholdingNominalTwd':'income_tax_withholding','supplementalHealthNominalTwd':'supplemental_nhi_high_bonus',
        'netCashNominalTwd':'net_cash_after_payroll',
    }
    rows=[]
    for raw in ledger:
        day=dt.date.fromisoformat(raw['pay_date'])
        row={key:money(raw[source]) for key,source in fields.items()}
        row.update(payDate=str(day),paymentAfterRetirement=day>retire,includedAtRetirement=asof<day<=retire,
                   yearRuleStatus=raw['year_rule_status'],bonusEvents=[dict(name=e['name'],months=e['months'],amountNominalTwd=money(e['amount']),festivalDate=e.get('festival_date'),timingStatus=e['timing_status']) for e in raw['bonus_events']])
        rows.append(row)
    profile=config['employee_profile'];raise_rule=profile['annual_salary_increase']
    return dict(monthlySalaryNominalTwd=money(profile['monthly_salary']),payday=config['payday_day_of_month'],
        annualRaiseAmountNominalTwd=money(raise_rule['amount']),annualRaiseFirstDate=raise_rule['first_increase_date'],
        withholdingRate=profile['income_tax_withholding']['rate'],selfPensionRate=profile['voluntary_labor_pension']['employee_rate'],
        nhiDependents=profile['nhi_dependents'],monthlyExpenseRealTwd=money(config['planned_living_costs']['monthly_living_expense']),
        bonusEvents=copy.deepcopy(config['bonus_events']),recurringBonuses=copy.deepcopy(config['recurring_bonuses']),ledger=rows,annualIncomeTax=annual_tax,
        receivedByRetirementNominalTwd=money(sum(r['net_cash_after_payroll'] for r in ledger if dt.date.fromisoformat(r['pay_date'])<=retire)),
        receivableAfterRetirementNominalTwd=money(sum(r['net_cash_after_payroll'] for r in ledger if dt.date.fromisoformat(r['pay_date'])>retire)))


def resolve_life(context,values,asof,retire,end,reference,paths):
    model=context.model;birth=dt.date.fromisoformat(values['birthDate'])
    config=copy.deepcopy(context.payroll)
    for key,pointer in PAYROLL_POINTERS.items():pointer_set(config,pointer,values[key])
    ledger=payroll.scenario(config,retire,asof)['ledger'] if retire>asof else []
    annual_tax,tax_events,terminal_tax=annual_salary_tax(config,model,birth,asof,retire,end)
    if values['laborPensionReceiptDate'] is None:
        lp=copy.deepcopy(model['labor_retirement'])
        lp['eligible_date']=str(birthday(birth,int(lp['age'])))
        if retire<=dt.date.fromisoformat(lp['balance_as_of_date']):
            raise SettingsError('CONFIG_INVALID','自動勞退缺少退休前帳戶估值，請改填實收日期與金額。')
        projection=labor_pension_projection(lp,payroll,config,retire)
        receipt=dt.date.fromisoformat(projection['receipt_date']);amount=projection['total_nominal_twd'];lp_mode='AUTO'
    else:
        receipt=dt.date.fromisoformat(values['laborPensionReceiptDate']);amount=values['laborPensionReceiptAmount'];lp_mode='MANUAL'
    if values['laborInsuranceStartDate'] is None:
        terms=labor_insurance_claim_terms(birth,retire,model['labor_insurance'])
        claim=terms['claim_date'];monthly=terms['monthly_nominal'];li_mode='AUTO'
    else:
        claim=dt.date.fromisoformat(values['laborInsuranceStartDate']);monthly=values['laborInsuranceMonthlyAmount'];li_mode='MANUAL'
    pension,basis=pension_state(paths,claim,monthly,asof,reference)
    events={};major=[]
    for event in values['majorExpenseEvents']:
        day=dt.date.fromisoformat(event['date'])
        included=asof<day<=end
        major.append(dict(date=str(day),name=event['name'],amountRealTwd=money(event['amount']),included=included,
                          exclusionReason=None if included else 'AT_OR_BEFORE_AS_OF' if day<=asof else 'AFTER_HORIZON'))
        if included and event['amount']>0:events[day]=events.get(day,0.)+event['amount']
    for year,interval,cost in [(values['homeRepairFirstYear'],values['homeRepairInterval'],values['homeRepairCost']),
                               (values['applianceFirstYear'],values['applianceInterval'],values['applianceCost'])]:
        for y in range(year,end.year+1,interval):
            day=dt.date(y,8,31)
            if asof<day<=end and cost>0:events[day]=events.get(day,0.)+cost
    schedule=dict(
        laborPension=dict(mode=lp_mode,receiptDate=str(receipt),amountNominalTwd=money(amount),included=asof<receipt<=end),
        laborInsurance=dict(mode=li_mode,startDate=str(claim),monthlyNominalTwd=money(monthly),asOfMonthlyNominalTwd=money(float(pension.monthly[0])),indexationBasis=basis),
        homeRepair=dict(firstYear=values['homeRepairFirstYear'],eventMonthDay='08-31',intervalYears=values['homeRepairInterval'],amountRealTwd=money(values['homeRepairCost'])),
        appliance=dict(firstYear=values['applianceFirstYear'],eventMonthDay='08-31',intervalYears=values['applianceInterval'],amountRealTwd=money(values['applianceCost'])),
        majorExpenses=major,payroll=payroll_wire(config,ledger,asof,retire,annual_tax))
    return dict(config=config,ledger=ledger,pension=pension,claim=claim,tax_events=tax_events,terminal_tax=terminal_tax,
                pension_events={receipt:float(amount)} if asof<receipt<=end else {},events=events,schedule=schedule)
