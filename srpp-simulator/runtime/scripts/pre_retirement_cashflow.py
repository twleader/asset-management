#!/usr/bin/env python3
"""Canonical payroll ledger for SRPP's remaining pre-retirement cash flow.

The calculator deliberately distinguishes:

* gross salary / bonuses;
* payroll deductions that reduce available cash;
* the voluntary labor-pension contribution, which is saved but inaccessible
  before age 60; and
* the model's planned living/capital costs, which turn take-home pay into an
  investable pre-retirement contribution.

Both the Monte Carlo model and the workbook consume this calculation. Agents
must not replace it with an annualised fraction or an independently derived
salary estimate.
"""
from __future__ import annotations

import argparse
import calendar
import copy
import hashlib
import json
from datetime import date
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CONFIG = ROOT / "assumptions" / "pre_retirement_payroll.json"
DEFAULT_OUTPUT = ROOT / "data" / "output" / "pre_retirement_cashflow.json"


def decimal(value: Any) -> Decimal:
    return Decimal(str(value))


def twd(value: Decimal | int | float) -> int:
    return int(decimal(value).quantize(Decimal("1"), rounding=ROUND_HALF_UP))


def iso(value: date) -> str:
    return value.isoformat()


def monthly_dates(start: date, end: date, payday: int) -> list[date]:
    """Return all cash pay dates strictly after start and through end."""
    dates: list[date] = []
    year, month = start.year, start.month
    while (year, month) <= (end.year, end.month):
        candidate = date(year, month, payday)
        if start < candidate <= end:
            dates.append(candidate)
        month += 1
        if month == 13:
            year += 1
            month = 1
    return dates


def stable_hash(payload: dict[str, Any]) -> str:
    encoded = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()


def distribute(total: int, count: int) -> list[int]:
    if count == 0:
        return []
    quotient, remainder = divmod(total, count)
    return [quotient + (1 if index < remainder else 0) for index in range(count)]


def salary_on(config: dict, pay_date: date) -> int:
    profile = config["employee_profile"]
    rule = profile.get("annual_salary_increase")
    salary = twd(profile["monthly_salary"])
    if rule:
        first = date.fromisoformat(rule["first_increase_date"])
        count = max(0, pay_date.year - first.year +
                    int((pay_date.month, pay_date.day) >= (first.month, first.day)))
        salary += count * twd(rule["amount"])
    return salary


def insurance_rule(config: dict, pay_date: date, salary: int) -> dict:
    years = config["social_insurance_by_year"]
    available = [int(y) for y in years if int(y) <= pay_date.year]
    if not available:
        raise ValueError(f"missing social-insurance rule for {pay_date.year}")
    selected = max(available)
    rule = dict(years[str(selected)])
    if selected != pay_date.year:
        rule["status"] = f"projection_carries_{selected}_rules_pending_official_{pay_date.year}"
    brackets = config.get("wage_brackets_115", {})
    for field in ("nhi_wage", "labor_pension_wage"):
        if field in brackets:
            levels = brackets[field]
            rule[field] = next((w for w in levels if w >= salary), levels[-1])
    return rule


def bonus_calendar(config: dict, first_year: int, last_year: int) -> list[dict]:
    events = {(e["pay_date"], e["name"]): dict(e) for e in config["bonus_events"]}
    for year in range(first_year, last_year + 1):
        for rule in config.get("recurring_bonuses", []):
            month = rule.get("month_by_year", {}).get(str(year), rule["month"])
            pay_date = date(year, month, config["payday_day_of_month"]).isoformat()
            # An explicit event for this bonus/year is authoritative.
            if any(e["name"] == rule["name"] and e["pay_date"].startswith(str(year)) for e in events.values()):
                continue
            events[(pay_date, rule["name"])] = {
                "name": rule["name"], "pay_date": pay_date, "months": rule["months"],
                "timing_status": "projected_pay_month;_employer_payment_calendar_required",
            }
    return sorted(events.values(), key=lambda e: (e["pay_date"], e["name"]))


def scenario(config: dict, retirement_date: date, as_of_date: date | None = None) -> dict:
    """Build an isolated dated ledger; scenario workers never mutate globals."""
    cfg = copy.deepcopy(config)
    cfg["retirement_date"] = retirement_date.isoformat()
    if as_of_date is not None:
        cfg["as_of_date"] = as_of_date.isoformat()
    return calculate(cfg)


def calculate(config: dict[str, Any]) -> dict[str, Any]:
    as_of = date.fromisoformat(config["as_of_date"])
    retirement = date.fromisoformat(config["retirement_date"])
    payday = int(config["payday_day_of_month"])
    if retirement <= as_of:
        raise ValueError("retirement_date must be after as_of_date")
    final_payday = date(retirement.year, retirement.month, payday)
    # A final partial month's earned wages remain receivable even if the
    # employee leaves before the usual payday. Bonuses require employment
    # on their stated payment date under the separately declared assumption.
    pay_dates = monthly_dates(as_of, max(retirement, final_payday), payday)
    if not pay_dates:
        raise ValueError("no pay dates fall inside the requested cash-paydate window")

    rows: dict[date, dict[str, Any]] = {
        pay_date: {
            "pay_date": iso(pay_date),
            "contractual_monthly_salary": salary_on(config, pay_date),
            "bonus_events": [],
        }
        for pay_date in pay_dates
    }
    month_days = calendar.monthrange(retirement.year, retirement.month)[1]
    for pay_date, row in rows.items():
        final_month = (pay_date.year, pay_date.month) == (retirement.year, retirement.month)
        fraction = decimal(retirement.day) / decimal(month_days) if final_month else Decimal(1)
        row["earned_month_fraction"] = fraction
        row["regular_salary"] = twd(decimal(row["contractual_monthly_salary"]) * fraction)
        row["insurance_month_fraction"] = (decimal(min(retirement.day, 30)) / Decimal(30)
            if final_month and retirement.day < month_days else Decimal(1))
        row["employer_nhi_due"] = not final_month or retirement.day == month_days
    for event in bonus_calendar(config, as_of.year, retirement.year):
        pay_date = date.fromisoformat(event["pay_date"])
        if as_of < pay_date <= retirement:
            if pay_date not in rows:
                raise ValueError(
                    f"bonus {event['name']} has pay date {event['pay_date']} that is not a monthly payday"
                )
            amount = twd(decimal(rows[pay_date]["contractual_monthly_salary"]) * decimal(event["months"]))
            rows[pay_date]["bonus_events"].append({
                "name": event["name"],
                "months": float(event["months"]),
                "amount": amount,
                "festival_date": event.get("festival_date"),
                "timing_status": event.get("timing_status", "explicit_paydate"),
            })

    planned = config["planned_living_costs"]
    annual_other_cost = sum(twd(value) for value in planned["annual_prorated_costs"].values())
    profile = config["employee_profile"]
    tax_rate = decimal(profile["income_tax_withholding"]["rate"])
    pension_rate = decimal(profile["voluntary_labor_pension"]["employee_rate"])
    nhi_dependents = int(profile["nhi_dependents"])
    cumulative_bonus_by_year: dict[int, int] = {}
    # Prior bonuses affect the annual four-month NHI threshold even when the
    # cash ledger starts midyear. They are not added to spendable cash again.
    for event in bonus_calendar(config, as_of.year, as_of.year):
        paid = date.fromisoformat(event["pay_date"])
        if paid <= as_of:
            cumulative_bonus_by_year[paid.year] = cumulative_bonus_by_year.get(paid.year, 0) + twd(
                decimal(salary_on(config, paid)) * decimal(event["months"]))

    tax_budget_limits = {}
    tax_budget_used = {}
    for pay_date, row in rows.items():
        tax_budget_limits[pay_date.year] = tax_budget_limits.get(pay_date.year, Decimal(0)) + decimal(planned['monthly_living_expense'])*row['earned_month_fraction']
    ledger: list[dict[str, Any]] = []
    budget_accrued = Decimal(0)
    budget_paid = 0
    for pay_date in pay_dates:
        row = rows[pay_date]
        rule = insurance_rule(config, pay_date, row["contractual_monthly_salary"])

        bonus = sum(event["amount"] for event in row["bonus_events"])
        gross = row["regular_salary"] + bonus
        labor = twd(
            decimal(rule["labor_insurance_wage"])
            * (decimal(rule["ordinary_labor_insurance_rate"]) + decimal(rule["employment_insurance_rate"]))
            * decimal(rule["labor_and_employment_employee_share"])
            * row["insurance_month_fraction"]
        )
        health = twd(
            decimal(rule["nhi_wage"])
            * decimal(rule["nhi_rate"])
            * decimal(rule["nhi_employee_share"])
            * Decimal(1 + nhi_dependents)
        ) if row["employer_nhi_due"] else 0
        pension = twd(decimal(rule["labor_pension_wage"]) * pension_rate * row["insurance_month_fraction"])
        tax = twd(decimal(gross) * tax_rate)

        previous_bonus = cumulative_bonus_by_year.get(pay_date.year, 0)
        cumulative_bonus = previous_bonus + bonus
        cumulative_bonus_by_year[pay_date.year] = cumulative_bonus
        high_bonus_base = min(
            bonus,
            max(0, cumulative_bonus - twd(decimal(rule["nhi_wage"]) * Decimal("4"))),
        )
        supplemental_nhi = twd(decimal(high_bonus_base) * decimal(rule["supplemental_nhi_rate"]))

        payroll_deductions = labor + health + pension + tax + supplemental_nhi
        net_after_payroll = gross - payroll_deductions
        budget_accrued += (decimal(planned["monthly_living_expense"]) +
            decimal(annual_other_cost) / Decimal(12)) * row["earned_month_fraction"]
        planned_cost = twd(budget_accrued) - budget_paid
        budget_paid += planned_cost
        available_tax_budget = max(twd(tax_budget_limits[pay_date.year])-tax_budget_used.get(pay_date.year, 0), 0)
        included_tax = min(tax+supplemental_nhi, available_tax_budget) if planned.get('all_taxes_included') else 0
        tax_budget_used[pay_date.year] = tax_budget_used.get(pay_date.year, 0)+included_tax
        investable_surplus = net_after_payroll - planned_cost + included_tax
        ledger.append({
            "pay_date": iso(pay_date),
            "year_rule_status": rule["status"],
            "nhi_wage": rule["nhi_wage"],
            "labor_pension_wage": rule["labor_pension_wage"],
            "contractual_monthly_salary": row["contractual_monthly_salary"],
            "earned_month_fraction": float(row["earned_month_fraction"]),
            "insurance_month_fraction": float(row["insurance_month_fraction"]),
            "payment_after_retirement": pay_date > retirement,
            "regular_salary": row["regular_salary"],
            "bonus": bonus,
            "bonus_events": row["bonus_events"],
            "gross_pay": gross,
            "labor_and_employment_insurance": labor,
            "health_insurance": health,
            "voluntary_labor_pension": pension,
            "income_tax_withholding": tax,
            "supplemental_nhi_high_bonus": supplemental_nhi,
            "total_payroll_deductions": payroll_deductions,
            "net_cash_after_payroll": net_after_payroll,
            "planned_living_and_capex_cost": planned_cost,
            "tax_included_in_living_budget": included_tax,
            "investable_surplus": investable_surplus,
        })

    totals = {
        key: sum(row[key] for row in ledger)
        for key in (
            "regular_salary",
            "bonus",
            "gross_pay",
            "labor_and_employment_insurance",
            "health_insurance",
            "voluntary_labor_pension",
            "income_tax_withholding",
            "supplemental_nhi_high_bonus",
            "total_payroll_deductions",
            "net_cash_after_payroll",
            "planned_living_and_capex_cost",
            "tax_included_in_living_budget",
            "investable_surplus",
        )
    }
    payload = {
        "calculation_standard": config["calculation_standard"],
        "calculation_version": config["calculation_version"],
        "input_fingerprint": stable_hash(config),
        "as_of_date": config["as_of_date"],
        "retirement_date": config["retirement_date"],
        "payday_day_of_month": payday,
        "payday_convention": config["payday_convention"],
        "tax_treatment": profile["income_tax_withholding"],
        "revalidation": config["revalidation"],
        "included_payment_count": len(ledger),
        "ledger": ledger,
        "totals": totals,
        "calendar_year_totals": {str(year): {
            key: sum(row[key] for row in ledger if date.fromisoformat(row["pay_date"]).year == year)
            for key in totals
        } for year in sorted({date.fromisoformat(row["pay_date"]).year for row in ledger})},
    }
    payload["calculation_hash"] = stable_hash(payload)
    return payload


def load_and_calculate(config_path: Path = DEFAULT_CONFIG) -> dict[str, Any]:
    return calculate(json.loads(config_path.read_text(encoding="utf-8")))


def write_output(payload: dict[str, Any], output_path: Path = DEFAULT_OUTPUT) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--check", action="store_true", help="require output to match the canonical calculation")
    args = parser.parse_args()

    payload = load_and_calculate(args.config)
    if args.check:
        if not args.output.exists():
            raise SystemExit(f"missing generated payroll ledger: {args.output}")
        actual = json.loads(args.output.read_text(encoding="utf-8"))
        if actual.get("calculation_hash") != payload["calculation_hash"]:
            raise SystemExit("generated payroll ledger does not match the canonical calculation")
    else:
        write_output(payload, args.output)

    print(json.dumps({
        "included_payment_count": payload["included_payment_count"],
        "gross_pay": payload["totals"]["gross_pay"],
        "net_cash_after_payroll": payload["totals"]["net_cash_after_payroll"],
        "investable_surplus": payload["totals"]["investable_surplus"],
        "calculation_hash": payload["calculation_hash"],
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
