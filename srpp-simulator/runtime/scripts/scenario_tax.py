"""Salary-only annual tax planning, over immutable payroll; no I/O clients."""
from __future__ import annotations
import datetime as dt
from decimal import Decimal
import pre_retirement_cashflow as payroll
from resident_income_tax import (TAIWAN_115_TAX_BRACKET_SOURCE,
    TAIWAN_SALARY_DEDUCTION_TWD, finite_decimal, progressive_slices, progressive_tax)
from scenario_money import money
from settings_catalog import SettingsError

SCOPE = 'SALARY_ONLY_SINGLE_RESIDENT_PLANNING'
NOTES = [
    '僅含本人薪資與獎金的單身居民規劃稅；未計其他家戶、列舉扣除、投資所得、股利及AMT，不是完整申報稅額。',
    '逐級累計5%／12%／20%／30%／40%；最高邊際稅率由所得淨額推導，20%不是全部薪資稅率或上限。',
    '115年度級距與扣除額沿用同版正式來源；未公布未來年度以115年度作規劃代理。',
    '年中起算前的薪資與預扣由同版薪資規則回推，只作年度稅基，未核對實際扣繳憑單，也不重播現金。',
    '預扣是已預繳稅；次年5月31日僅補退預扣與應納差額。生活費含稅預算及退款保留沿原規約。',
    '終點未到期補稅負債扣列；尚未到期的退款不提前計入可支用資產。',
]


def salary_year(tax_year, rows, model, birth, asof, end):
    gross = sum((Decimal(r['gross_pay']) for r in rows), Decimal(0))
    pension = sum((Decimal(r['voluntary_labor_pension']) for r in rows), Decimal(0))
    withheld = sum((Decimal(r['income_tax_withholding']) for r in rows), Decimal(0))
    salary = max(gross - pension, Decimal(0))
    assumptions = model['tax_assumptions']
    key = 'personal_exemption_age_70_or_older_twd' if tax_year - birth.year >= 70 else 'personal_exemption_under_70_twd'
    exemption = finite_decimal(assumptions[key], label=key)
    standard = finite_decimal(assumptions['single_standard_deduction_twd'], label='standard deduction')
    if exemption < 0 or standard < 0:
        raise SettingsError('TAX_SOURCE_INVALID', '所得稅免稅額與扣除額來源不合法。')
    salary_deduction = min(salary, TAIWAN_SALARY_DEDUCTION_TWD)
    taxable = max(salary - exemption - standard - salary_deduction, Decimal(0))
    liability = progressive_tax(taxable)
    slices = progressive_slices(taxable)
    marginal = max((row['rate'] for row in slices if row['taxable_slice'] > 0), default=Decimal(0))
    settlement = withheld - liability
    settlement_date = dt.date(tax_year + 1, 5, 31)
    treatment = ('SETTLED_BEFORE_AS_OF' if settlement_date <= asof else
                 'CASH_SETTLEMENT' if settlement_date <= end else 'TERMINAL_ACCRUAL')
    return dict(taxYear=tax_year, settlementDate=str(settlement_date),
        grossSalaryNominalTwd=money(gross), selfPensionNominalTwd=money(pension),
        taxableSalaryNominalTwd=money(salary), personalExemptionNominalTwd=money(exemption),
        standardDeductionNominalTwd=money(standard), salaryDeductionNominalTwd=money(salary_deduction),
        taxableIncomeNominalTwd=money(taxable), marginalRate=float(marginal),
        effectiveRate=float(liability / gross) if gross > 0 else 0.0,
        brackets=[dict(lowerNominalTwd=money(r['lower']), upperNominalTwd=money(r['upper']) if r['upper'] is not None else None,
                       rate=float(r['rate']), taxableSliceNominalTwd=money(r['taxable_slice']), taxNominalTwd=money(r['tax'])) for r in slices],
        liabilityNominalTwd=money(liability), withheldNominalTwd=money(withheld),
        settlementNominalTwd=money(settlement), treatment=treatment)


def annual_salary_tax(config, model, birth, asof, retire, end):
    """Rebuild calendar-year basis, never replay payments at/before as-of."""
    first_year = asof.year - (1 if (asof.month, asof.day) <= (5, 31) else 0)
    last_year = min(retire.year, end.year)
    source_start = min(int(year) for year in config['social_insurance_by_year'])
    # A due prior year cannot silently be invented or treated as zero income.
    if first_year < source_start and first_year <= last_year:
        raise SettingsError('TAX_HISTORY_UNAVAILABLE', '待結算年度早於可驗證薪資規則，無法回推年度稅基。')
    first_year = max(first_year, source_start)
    rows = []
    if first_year <= last_year and retire > dt.date(first_year - 1, 12, 31):
        full = payroll.scenario(config, retire, dt.date(first_year - 1, 12, 31))['ledger']
        for year in range(first_year, last_year + 1):
            selected = [row for row in full if dt.date.fromisoformat(row['pay_date']).year == year
                        and dt.date.fromisoformat(row['pay_date']) <= end]
            rows.append(salary_year(year, selected, model, birth, asof, end))
    events = {dt.date.fromisoformat(row['settlementDate']): Decimal(row['settlementNominalTwd'])
              for row in rows if row['treatment'] == 'CASH_SETTLEMENT'}
    terminal = sum((min(Decimal(row['settlementNominalTwd']), Decimal(0))
                    for row in rows if row['treatment'] == 'TERMINAL_ACCRUAL'), Decimal(0))
    schedule = dict(scope=SCOPE, ruleYear=2026, sourceUrl=TAIWAN_115_TAX_BRACKET_SOURCE,
        futureYearPolicy='CARRY_115_UNTIL_OFFICIAL', historicalBasis='SOURCE_PAYROLL_PROJECTION_NOT_VERIFIED_SLIPS',
        years=rows, notes=list(NOTES))
    return schedule, events, terminal
