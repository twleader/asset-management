"""Shared real-budget and dated benefit accounting (base: August 2026)."""
from __future__ import annotations

import calendar
import datetime as dt
import numpy as np

BASE_DATE = dt.date(2026, 8, 31)


def labor_insurance_claim_terms(birth_date, retirement_date, policy):
    """Return the eligible claim date and month-prorated age adjustment.

    The caller's retirement date is the last insured day. Claim eligibility
    begins at the later of the minimum claim-age birthday and the following
    day. The project models the statutory adjustment in completed age-months;
    the benefit remains an estimate because service years and the personal
    highest-60-month wage record are not available here.
    """
    minimum_age = int(policy["start_age"])
    normal_age = int(policy["normal_claim_age"])
    birth_date = dt.date.fromisoformat(birth_date) if isinstance(birth_date, str) else birth_date
    retirement_date = dt.date.fromisoformat(retirement_date) if isinstance(retirement_date, str) else retirement_date

    def birthday(age):
        try:
            return birth_date.replace(year=birth_date.year + age)
        except ValueError:
            return birth_date.replace(year=birth_date.year + age, day=28)

    earliest_date = birthday(minimum_age)
    normal_date = birthday(normal_age)
    claim_date = max(earliest_date, retirement_date + dt.timedelta(days=1))
    attained_months = (claim_date.year - birth_date.year) * 12 + claim_date.month - birth_date.month
    if claim_date.day < birth_date.day:
        attained_months -= 1
    offset_months = attained_months - normal_age * 12
    adjustment_limit_months = int(policy.get("maximum_adjustment_years", 5)) * 12
    offset_months = max(-adjustment_limit_months, min(adjustment_limit_months, offset_months))
    adjustment = offset_months * float(policy["adjustment_per_year"]) / 12
    base_monthly = float(policy["base_monthly_nominal"])
    return {
        "earliest_claim_date": earliest_date,
        "normal_claim_date": normal_date,
        "claim_date": claim_date,
        "months_early": max(0, -offset_months),
        "months_delayed": max(0, offset_months),
        "permanent_adjustment": adjustment,
        "monthly_nominal": base_monthly * (1 + adjustment),
    }


def reference_price_level(date, annual_rate=.0225):
    """End-August 2026 = 1; use elapsed days, including partial years."""
    return (1 + annual_rate) ** ((date - BASE_DATE).days / 365.25)


class AnnualTaxBudget:
    """Taxes consume the living envelope; only any excess adds expenditure.

    cover() returns the amount already provided by the ordinary living debit.
    Refunds are retained as cash and do not increase the consumption budget.
    Guardrail cuts reduce the envelope and reclaim any excess prior coverage.
    """
    def __init__(self, limits, paths, protected=None):
        self.limits = dict(limits)
        self.protected = dict(protected or {})
        self.paths = paths
        self.used = {}
        self.charged = {}

    def cover(self, amount, year):
        amount = np.maximum(np.broadcast_to(amount, (self.paths,)), 0.)
        used = self.used.setdefault(year, np.zeros(self.paths))
        charge = self.charged.setdefault(year, np.zeros(self.paths))
        covered = np.minimum(amount, np.maximum(self.limits.get(year, 0.) - self.protected.get(year, 0.) - used, 0.))
        used += covered
        charge += amount
        return covered

    def reduce(self, amount, year):
        self.limits[year] = np.maximum(self.limits[year] - amount, 0.)
        used = self.used.setdefault(year, np.zeros(self.paths))
        reclaim = np.maximum(used - np.maximum(self.limits[year] - self.protected.get(year, 0.), 0), 0.)
        used -= reclaim
        return reclaim

    def adjust_limit(self, delta, year):
        """Apply a pathwise living-budget change and reclaim prior tax cover.

        Market-conditioned spending can change the same year's limit after
        earlier tax payments were covered by it. A lower limit must reverse
        any now-excess coverage as cash; a higher limit simply adds capacity.
        """
        delta = np.broadcast_to(np.asarray(delta, dtype=float), (self.paths,))
        self.limits[year] = np.broadcast_to(
            np.asarray(self.limits.get(year, 0.), dtype=float), (self.paths,)
        ).copy()
        reduction = np.maximum(-delta, 0.)
        reclaim = self.reduce(reduction, year) if np.any(reduction > 0) else np.zeros(self.paths)
        self.limits[year] += np.maximum(delta, 0.)
        return reclaim

    def audit(self):
        rows = []
        for year in sorted(self.used):
            cap = np.broadcast_to(self.limits[year], (self.paths,))
            covered = self.used[year]
            charge = self.charged.get(year, np.zeros(self.paths))
            if np.any(covered > cap - self.protected.get(year, 0.) + 1e-7):
                raise AssertionError("tax coverage exceeds living budget")
            rows.append({"year": year, "budget_real_median": float(np.median(cap)),
                "protected_care_cost_real": self.protected.get(year, 0.),
                "tax_charged_real_median": float(np.median(charge)),
                "tax_included_real_median": float(np.median(covered)),
                "non_tax_living_real_median": float(np.median(cap-covered)),
                "tax_excess_real_median": float(np.median(charge-covered))})
        return {"all_taxes_in_living_budget": True, "base_month": "2026-08",
            "refunds_retained": True, "rows": rows}


class PensionIndexation:
    """Annual-average CPI by claim cohort; May adjustment paid in June."""
    def __init__(self, paths, claim_year, initial_monthly):
        self.monthly = np.full(paths, float(initial_monthly))
        self.claim_year = claim_year
        self.anchor = None
        self.previous_average = None
        self.integral = np.zeros(paths)
        self.days = 0
        self.calendar_year = None
        self.audit = []

    def observe(self, start, end, opening, closing):
        """Record CPI without letting a partial simulation year poison later years.

        The model begins at the August 2026 valuation date, while the Labour
        Insurance rule needs complete calendar-year averages.  Periods can be
        split by payroll, pension and dated-spending events, so accumulation is
        explicitly partitioned at each 1 January rather than assuming that the
        first observed span is a full calendar year.
        """
        if end <= start:
            return
        total_days = (end - start).days
        total_log_growth = np.log(closing / opening)
        cursor = start
        while cursor < end:
            next_year = dt.date(cursor.year + 1, 1, 1)
            segment_end = min(end, next_year)
            segment_days = (segment_end - cursor).days
            left_fraction = (cursor - start).days / total_days
            right_fraction = (segment_end - start).days / total_days
            segment_opening = opening * np.exp(total_log_growth * left_fraction)
            segment_closing = opening * np.exp(total_log_growth * right_fraction)
            if self.calendar_year != cursor.year:
                self.calendar_year = cursor.year
                self.integral.fill(0.)
                self.days = 0
            segment_log_growth = np.log(segment_closing / segment_opening)
            ratio = np.divide(
                np.expm1(segment_log_growth), segment_log_growth,
                out=np.ones_like(segment_log_growth),
                where=np.abs(segment_log_growth) > 1e-12,
            )
            self.integral += segment_opening * ratio * segment_days
            self.days += segment_days
            if segment_end == next_year:
                full_days = 366 if calendar.isleap(cursor.year) else 365
                if self.days == full_days:
                    self.previous_average = self.integral / full_days
                    if cursor.year == self.claim_year:
                        self.anchor = self.previous_average.copy()
                self.calendar_year = None
                self.integral.fill(0.)
                self.days = 0
            cursor = segment_end

    def payment(self, date):
        if (date.month, date.day) == (6, 30) and self.anchor is not None:
            # Official percentage is rounded to two decimal places.
            growth = np.floor((self.previous_average/self.anchor - 1)*10000 + .500000001)/10000
            hit = growth >= .05
            self.monthly[hit] *= 1 + growth[hit]
            self.anchor[hit] = self.previous_average[hit]
            if np.any(hit):
                self.audit.append({"cash_date": str(date), "adjusted_paths": int(hit.sum()),
                    "cpi_growth_median": float(np.median(growth[hit])),
                    "monthly_nominal_median": float(np.median(self.monthly[hit]))})
        return self.monthly


def labor_pension_projection(config, payroll_module, payroll_config, retirement_date):
    """Locked account balance, separate from payroll cash and liquid assets.

    Employer contributions use the user-specified contractual salary basis.
    Existing employee contributions, when enabled, use the payroll ledger.
    Projected earnings use an explicitly declared simple-interest assumption.
    The statutory lifetime guarantee is not added to projected earnings.
    """
    as_of = dt.date.fromisoformat(config["balance_as_of_date"])
    ledger = payroll_module.scenario(payroll_config, retirement_date, as_of)["ledger"]
    employer = sum(r["regular_salary"] * config["employer_contribution_rate"] for r in ledger)
    employee = sum(r["voluntary_labor_pension"] for r in ledger)
    application = max(dt.date.fromisoformat(config["eligible_date"]),
                      retirement_date + dt.timedelta(days=1))
    receipt = application + dt.timedelta(days=config["receipt_lag_days"])
    annual_return = float(config['investment_return_assumption'])
    # Reference-rate planning proxy. The August balance may already contain
    # gains: this must never be labelled an exact statutory guarantee.
    earnings = config['benefit'] * annual_return * (application-as_of).days/365.25
    for row in ledger:
        payday=dt.date.fromisoformat(row['pay_date'])
        earned=min(payday.replace(day=calendar.monthrange(payday.year,payday.month)[1]),retirement_date)
        contribution=row['regular_salary']*config['employer_contribution_rate']+row['voluntary_labor_pension']
        earnings += contribution*annual_return*max((application-earned).days,0)/365.25
    total=round(config['benefit']+employer+employee+earnings,2)
    events = {str(receipt): total}
    return {"balance_as_of_date": str(as_of), "opening_nominal_twd": config["benefit"],
        "employer_contributions_nominal_twd": round(employer, 2),
        "employee_contributions_nominal_twd": employee,
        "total_nominal_twd": total,
        "projected_future_earnings_nominal_twd": round(earnings,2),
        "statutory_guarantee_top_up_nominal_twd": None,
        "earnings_basis": "simple-interest planning proxy; exact lifetime guarantee requires contribution and prior-earnings history",
        "retirement_date": str(retirement_date), "investment_return_assumption": annual_return,
        "employer_basis": "6% of earned regular salary; bonuses excluded",
        "receipt_events": {d: round(v, 2) for d, v in sorted(events.items())},
        "application_date": str(application), "receipt_date": str(receipt),
        "posting_convention": "One receipt after retirement and age 60; application the day after retirement if already 60; cash 30 days after application. Includes final earned contributions; actual account posting must be confirmed."}
