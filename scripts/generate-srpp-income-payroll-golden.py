#!/usr/bin/env python3
"""Offline same-input goldens from original SRPP pure cash income/payroll/benefit functions."""
import copy, datetime as dt, hashlib, json, pathlib, sys
from decimal import Decimal
ROOT=pathlib.Path(__file__).resolve().parents[1]
SRPP=pathlib.Path('/Users/steven/Project/SRPP')
sys.path.insert(0,str(SRPP/'scripts'))
import current_cash_income as income
import pre_retirement_cashflow as payroll
import budget_accounting as benefits

def canonical(v):
    if isinstance(v,Decimal): return format(v,'f').rstrip('0').rstrip('.') if '.' in format(v,'f') else format(v,'f')
    if isinstance(v,float): return str(v)
    if isinstance(v,list):return [canonical(x) for x in v]
    if isinstance(v,dict):return {k:canonical(x) for k,x in v.items()}
    return v
rules={'taxYear':2026,'status':'OFFICIAL','brackets':[{'upper':str(a) if a.is_finite() else None,'rate':str(b)} for a,b in income.TAIWAN_TAX_BRACKETS],
'exemption':str(income.TAIWAN_PERSONAL_EXEMPTION_TWD),'exemptionAge70':str(income.TAIWAN_PERSONAL_EXEMPTION_AGE_70_TWD),'standardDeduction':str(income.TAIWAN_STANDARD_DEDUCTION_SINGLE_TWD),'salaryDeductionCap':str(income.TAIWAN_SALARY_DEDUCTION_TWD),'savingsDeductionCap':str(income.TAIWAN_INTEREST_SAVINGS_DEDUCTION_TWD),'dividendCreditRate':str(income.TAIWAN_DIVIDEND_CREDIT_RATE),'dividendCreditCap':str(income.TAIWAN_DIVIDEND_CREDIT_CAP_TWD),'separateDividendRate':str(income.TAIWAN_DIVIDEND_SEPARATE_TAX_RATE),'overseasInclusionThreshold':str(income.OVERSEAS_INCOME_AMT_INCLUSION_THRESHOLD_TWD),'basicIncomeThreshold':str(income.INDIVIDUAL_BASIC_INCOME_THRESHOLD_TWD),'basicTaxRate':str(income.INDIVIDUAL_BASIC_TAX_RATE),'nhiThreshold':str(income.SUPPLEMENTARY_PAYMENT_THRESHOLD_TWD),'nhiCap':str(income.SUPPLEMENTARY_PAYMENT_CAP_TWD),'nhiRate':str(income.SUPPLEMENTARY_PREMIUM_RATE)}
keys={'domesticDividend':'domestic_dividend','domesticInterest':'domestic_interest','savingsInterest':'savings_interest','otherDomesticIncome':'other_domestic_income','salaryIncome':'salary_income','overseasIncome':'overseas_income','creditableForeignTax':'creditable_foreign_tax','age70OrOlder':'age_70_or_older'}
cases=[]
for name,patch in [('refundable800k',{'domesticDividend':'800000'}),('separate_high_income',{'domesticDividend':'5000000','salaryIncome':'9000000'}),('overseas_below_threshold',{'overseasIncome':'999999','domesticDividend':'400000'}),('amt_foreign_credit',{'overseasIncome':'20000000','creditableForeignTax':'300000','salaryIncome':'1348500','domesticDividend':'800000'}),('foreign_credit_capped',{'overseasIncome':'12000000','creditableForeignTax':'9000000'}),('age70_interest_deductions',{'age70OrOlder':True,'domesticInterest':'600000','savingsInterest':'500000','salaryIncome':'100000'}),('known_zero',{})]:
    args={k:'0' for k in keys if k!='age70OrOlder'}|{'age70OrOlder':False}|patch
    expected=income.annual_taiwan_tax(**{keys[k]:v for k,v in args.items()})
    cases.append({'kind':'tax','name':name,'inputs':args,'expected':canonical(expected)})
for name,annual,events in [('threshold_aggregated_brokers','30000',[('1','1')]),('below_threshold','19999.99',[('1','1')]),('at_threshold','20000',[('1','1')]),('distinct_monthly_payments','120000',[('0.25','1')]*4),('nhi_cap','20000000',[('1','1')]),('capital_components_excluded','100000',[('1','0.1')])]:
    template=[{'paymentEventId':f'e{i}','grossEventFraction':fraction,'nhiChargeableShare':share}for i,(fraction,share)in enumerate(events)]
    base,audit=income._nhi_chargeable_from_annual_gross(Decimal(annual),[{'gross_event_fraction':x['grossEventFraction'],'nhi_chargeable_share':x['nhiChargeableShare']} for x in template])
    cases.append({'kind':'nhi','name':name,'annual':annual,'events':template,'expected':str(base)})
base=json.loads((SRPP/'assumptions/pre_retirement_payroll.json').read_text())
for name,asof,retire in [('canonical','2026-08-27','2027-06-30'),('partial_month_after_payday','2026-12-31','2027-04-15'),('partial_month_before_payday','2026-12-31','2027-04-07'),('future_brackets_carried','2027-12-31','2028-04-15'),('prior_bonus_affects_nhi','2027-02-20','2027-06-30'),('full_february','2026-12-31','2027-02-28')]:
    cfg=copy.deepcopy(base);cfg['as_of_date']=asof;cfg['retirement_date']=retire
    actual=payroll.calculate(cfg)
    cases.append({'kind':'payroll','name':name,'config':cfg,'expected':canonical(actual)})
model=json.loads((SRPP/'assumptions/model_inputs.json').read_text())
for retire in ['2027-06-30','2031-04-15']:
    cfg=copy.deepcopy(base);cfg['retirement_date']=retire
    expected=benefits.labor_pension_projection(model['labor_retirement'],payroll,cfg,dt.date.fromisoformat(retire))
    cases.append({'kind':'pension','name':retire,'config':cfg,'pension':model['labor_retirement'],'expected':canonical(expected)})
# Portfolio composition, original per-event NHI, SGOV withholding exemption and term renewal.
weights={"0050":Decimal("0.25"),"VOO":Decimal("0.25"),"SGOV":Decimal("0.1"),"TWD_term_deposits":Decimal("0.4")}
profiles={}
for code,yield_,kind,withheld in [("0050","0.08","domestic_dividend_share","0"),("VOO","0.02","overseas_income_share","0.3"),("SGOV","0.04","overseas_income_share","0"),("TWD_term_deposits","0.017","domestic_interest_share","0")]:
    item={"regular_cash_yield":Decimal(yield_),"cash_withholding_rate":Decimal(withheld),"creditable_foreign_tax_rate":Decimal(withheld),"domestic_dividend_share":Decimal(0),"domestic_interest_share":Decimal(0),"savings_interest_share":Decimal(0),"other_domestic_income_share":Decimal(0),"overseas_income_share":Decimal(0)}
    item[kind]=Decimal(1)
    if code=="TWD_term_deposits":item["savings_interest_share"]=Decimal(1)
    count=3 if code=="TWD_term_deposits" else 4 if code=="0050" else 1
    item["distribution_event_template"]=[{"gross_event_fraction":Decimal(1)/count,"nhi_chargeable_share":Decimal(1) if code in ("0050","TWD_term_deposits") else Decimal(0)}for _ in range(count)]
    profiles[code]=item
expected=income._target_income_components(Decimal("10000000"),weights,profiles,protected_permanent_twd=Decimal("800000"))
cases.append({"kind":"incomePortfolio","name":"stock_us_sgov_deposit_renewal","total":"10000000","weights":canonical(weights),"profiles":canonical(profiles),"expected":canonical(expected)})
for birth,retire in [("1970-10-15","2027-06-30"),("1970-10-15","2037-04-15"),("1972-02-29","2031-04-15")]:
    policy=model["labor_insurance"]
    expected=benefits.labor_insurance_claim_terms(birth,retire,policy)
    expected={k:str(v) if isinstance(v,dt.date) else v for k,v in expected.items()}
    cases.append({"kind":"laborInsurance","name":birth+":"+retire,"birth":birth,"retirement":retire,"policy":policy,"expected":canonical(expected)})
out={'source':'Original SRPP frozen same-input pure functions, no network IO','originalCalculatorHashes':{p:hashlib.sha256((SRPP/'scripts'/p).read_bytes()).hexdigest()for p in ['current_cash_income.py','pre_retirement_cashflow.py','budget_accounting.py']},'rules':rules,'cases':cases}
path=ROOT/'backend/src/test/resources/srpp/report-income-payroll-golden.json'
path.write_text(json.dumps(out,ensure_ascii=False,indent=2)+'\n')
print(f'{len(cases)} income/payroll same-input cases saved')
