"""Monthly financial engine; shares policy/calculators with both report consumers.

The caller passes the configured model module. This module never obtains quotes,
writes orders, or replaces the caller's verified source snapshot.
"""
from __future__ import annotations

import datetime as dt
import calendar
import csv
import json
import math
import numpy as np
import voo_bridge_proxy
from transition_ledger import TransitionLedger
from return_model import CalendarMarketPath, InflationPath, security_returns, weighted_bucket_returns
from bear_ladder import BearLadder, BullLadder, ranked_bond_sale
from execution_policy import funded_rebalance, transaction_tax_rates, bull_proceeds
from budget_accounting import AnnualTaxBudget, PensionIndexation, labor_pension_projection, labor_insurance_claim_terms, reference_price_level


def ordinary_review_month(end):
    """Month-end execution proxy after the quarterly review on the 15th."""
    return (end-dt.timedelta(days=1)).month in (1, 4, 7, 10)


def constrain_bond_purchase(assets, before, capacity, fx):
    """Unfilled purchases stay as cash; a deadline never fills a gap."""
    wanted = np.maximum(assets[:, 1]-before[:, 1], 0)
    filled = np.minimum(wanted, np.where(fx<=33., capacity, 0))
    unfilled = wanted-filled
    assets[:, 1] -= unfilled
    assets[:, 2] += unfilled
    return filled


def bear_purchase_values(values, total, net_budget, *, equity_value, equity_cap,
                         target_weights, high_group, global_group, voo_index,
                         tw50_index, tw50_cap, pair_ratio=(4,1)):
    """Use whole-equity capacity in priority order, then keep VOO:0050 at 4:1.

    Constrain the available principal before filling high-dividend deficits.
    Scaling both stages after allocation would spend scarce equity capacity on
    the market pair while the first-stage target was still unfilled.
    """
    paths = len(total)
    equity_room = np.maximum(equity_cap * total - equity_value, 0)
    budget = np.minimum(np.maximum(net_budget, 0), equity_room)
    allocation = np.zeros_like(values)
    high_deficits = np.maximum(total[:, None] * target_weights - values, 0) * high_group
    deficits = high_deficits.sum(axis=1)
    group_room = np.maximum(total * target_weights[high_group].sum()
                            - values[:, high_group].sum(axis=1), 0)
    high_amount = np.minimum(budget, np.minimum(deficits, group_room))
    allocation += high_deficits * np.divide(high_amount, deficits,
        out=np.zeros(paths), where=deficits > 0)[:, None]

    market_budget = np.maximum(budget - high_amount, 0)
    voo_share=pair_ratio[0]/sum(pair_ratio)
    voo_desired, tw50_desired = market_budget * voo_share, market_budget * (1-voo_share)
    voo_room = np.maximum(total * .42 - values[:, voo_index], 0)
    tw50_room = np.maximum(total * tw50_cap - values[:, tw50_index], 0)
    global_room = np.maximum(total * .50 - values[:, global_group].sum(axis=1), 0)
    scale = np.ones(paths)
    for wanted, room in ((voo_desired, voo_room), (tw50_desired, tw50_room),
                         (voo_desired, global_room)):
        scale = np.minimum(scale, np.divide(room, wanted,
            out=np.ones(paths), where=wanted > 0))
    scale = np.clip(scale, 0, 1)
    allocation[:, voo_index] += voo_desired * scale
    allocation[:, tw50_index] += tw50_desired * scale
    return allocation


def spending_tier_for_age(age, pension_age, care_age, pre_pension_spend,
                          pre_care_spend, age_75_to_80_spend, care_spend,
                          age_75=75, age_80=80):
    """Return the accepted age-based annual spending tier, in real TWD."""
    if age < pension_age:
        return pre_pension_spend
    # D-193 fixes the third spending stage at age 80 even in delayed-care
    # sensitivities. An earlier care-start stress can still raise spending
    # before 80; a later care-start date cannot erase the accepted age-80 tier.
    if age >= age_80 or age >= care_age:
        return care_spend
    if age < age_75:
        return pre_care_spend
    if age < age_80:
        return age_75_to_80_spend
    return pre_care_spend


def market_spending_adjustment(bear, bull, amount):
    """Apply the annualized D-193 spending change to monthly market regimes.

    Bear takes precedence if malformed inputs mark both regimes true. The
    normal model signal definitions make the two states mutually exclusive.
    """
    return np.where(bear, -float(amount), np.where(bull, float(amount), 0.0))


def roll_permanent_term_deposit(reserve, real_return, floor):
    """Roll the aggregate protected-reserve proxy and preserve its real floor.

    D-193's actual reserve is split between term deposits and Taiwan-listed
    00865B. The base
    Monte Carlo does not model instrument-specific returns or emergency draws;
    it retains the historical aggregate real-TWD proxy, applies the TWD term
    deposit rate to the whole proxy, and tops up any real shortfall from
    operating cash. This is a disclosed approximation, not the live reserve
    ledger or a forecast of the 00865B sleeve.
    """
    renewed = np.asarray(reserve, dtype=float) * np.maximum(
        1 + np.asarray(real_return, dtype=float), 0.0
    )
    top_up = np.maximum(float(floor) - renewed, 0.0)
    return renewed + top_up, top_up


def accrue_terminal_tax(assets, pending_tax, price_level, end_date, tax_budget=None):
    """Net terminal wealth includes every unpaid year, including a partial year.

    May settlements already removed their year from pending_tax. Clearing the
    pending entries makes a second call harmless; neither withholding nor NHI
    is charged twice here.
    """
    entries = []
    adjustment = np.zeros(len(assets))
    for settlement_year, nominal in sorted(pending_tax.items()):
        real = nominal / price_level
        covered = tax_budget.cover(-real, end_date.year) if tax_budget is not None else np.zeros(len(assets))
        adjustment += real + covered
        entries.append({"tax_year": settlement_year - 1,
                        "nominal_balance_median_twd": float(np.median(nominal)),
                        "real_balance_median_twd": float(np.median(real)),
                        "included_in_living_budget_median_twd": float(np.median(covered))})
    assets[:, 2] += adjustment
    pending_tax.clear()
    return {"as_of": str(end_date), "included_unpaid_years": entries,
            "real_adjustment_median_twd": float(np.median(adjustment)),
            "positive_means_refund": True, "includes_final_partial_year": True,
            "withholding_deducted_once": True, "remaining_pending_years": 0}


class MonthlySignals:
    """Observable monthly proxy, never a substitute for daily radar evidence."""
    def __init__(self, paths, *, bear_drawdown=.20):
        if isinstance(bear_drawdown, bool) or not isinstance(bear_drawdown, (int, float)) or not math.isfinite(bear_drawdown) or not 0 < bear_drawdown < 1:
            raise ValueError("bear drawdown must be a finite fraction strictly between zero and one")
        self.bear_drawdown = float(bear_drawdown)
        self.history = np.ones((10, paths))
        self.count = 0
        self.ma = np.ones(paths)
        self.peak = np.ones(paths)
        self.anchor = np.full(paths, np.nan)
        self.window_months = np.zeros(paths, dtype=np.int16)
        self.used = np.zeros(paths, dtype=bool)
        self.since_sale = np.full(paths, 120, dtype=np.int16)

    def update(self, index, wait):
        self.since_sale += 1
        old_peak = self.peak.copy()
        old_ma = self.ma.copy()
        self.history[self.count % 10] = index
        self.count += 1
        self.ma = self.history.mean(axis=0)
        self.peak = np.maximum(self.peak, index)
        drawdown = index / self.peak - 1
        corrected = drawdown <= -.10
        self.used[corrected & (self.since_sale >= 12)] = False
        invalid = corrected | (index < self.anchor)
        self.anchor[invalid] = np.nan
        self.window_months[invalid] = 0
        warmed = self.count >= 11
        fresh = np.isnan(self.anchor) & ~self.used & (index >= old_peak) & warmed
        self.anchor[fresh] = old_peak[fresh]
        self.window_months[~np.isnan(self.anchor)] += 1
        rising = warmed & (self.ma > old_ma) & (index >= self.ma)
        risk_off = warmed & (self.ma < old_ma) & (index < self.ma)
        bull = rising & ~self.used & (self.window_months >= wait) & (index >= self.anchor * 1.15) & ~corrected
        bear = risk_off & (drawdown <= -self.bear_drawdown)
        return bull, bear, drawdown

    def sold(self, mask):
        self.used |= mask
        self.since_sale[mask] = 0


def run(m, allocation, simulations, seed, guardrail, initial_total=None,
        reserve_amount=1_600_000., care_age=80, care_spend=1_400_000.,
        pension_age=60, pre_pension_spend=750_000., pre_care_spend=750_000.,
        age_75_to_80_spend=None, market_spend_adjustment_twd=None, terminal_age=90,
        home_repair_cost=100_000., home_repair_interval=5, appliance_cost=120_000.,
        appliance_interval=4, appliance_first_year=2031, early_crash=False,
        lower_return_decade=False, high_inflation_5y=False, pre_retirement=True,
        band_rebalance=True, deduct_costs=True, care_guardrail=True,
        capture_annual_cash_income=False, retirement_cashflow_timing="start",
        enable_bull_timing=False, enable_bear_timing=False, tactical_wait_months=6,
        transition_probability=None, equity_real_geometric=None, bond_yield_shift=0.,
        bond_parallel_rate_shock=0.,
        retirement_date=None, extra_grid_dates=(), capture_percentiles=False, enable_voo_bridge=False, enable_voo_fx_wait_proxy=False, tsmc_waiver_active=True, **unsupported):
    if unsupported:
        raise ValueError(f"retired/unsupported model arguments: {sorted(unsupported)}")
    if retirement_cashflow_timing not in {"start", "midpoint"}:
        raise ValueError("cashflow timing must be start or midpoint")
    if tactical_wait_months not in (3, 6, 9, 12):
        raise ValueError("monthly tactical wait must be 3/6/9/12")
    cfg = m.STATIC_MODEL_INPUTS["simulation_contract"]
    # D-214: the normal global-equity group cap comes from the accepted policy (target +/-3pp).
    GLOBAL_GROUP_CAP = float(m.STATIC_MODEL_INPUTS["execution_policy_overrides"]["global_equity_wrapper_policy_d141"]["global_equity_group_cap"])
    probability = cfg["transition_opportunity_scenarios"]["Planning"] if transition_probability is None else transition_probability
    if not 0 <= probability <= 1:
        raise ValueError("transition qualification probability must be in [0,1]")
    n = simulations
    retire_date = dt.date.fromisoformat(retirement_date) if isinstance(retirement_date, str) else retirement_date or m.RETIRE_DATE
    if not m.SNAPSHOT_DATE < retire_date < m.terminal_date(terminal_age):
        raise ValueError("retirement date must fall after the snapshot and before the horizon")
    spending_policy = m.STATIC_MODEL_INPUTS["spending"]
    stage_age_75 = int(spending_policy["stage_age_75"])
    stage_age_80 = int(spending_policy["stage_age_80"])
    if not 0 < stage_age_75 < stage_age_80 < 90:
        raise ValueError("D-193 spending stage ages are inconsistent")
    if age_75_to_80_spend is None:
        age_75_to_80_spend = float(spending_policy["age_75_to_80_spend"])
    if market_spend_adjustment_twd is None:
        market_spend_adjustment_twd = float(
            spending_policy["market_spend_adjustment_d193"]["annual_adjustment_twd_2026_real"]
        )
    payroll_config = json.loads(m.PAYROLL_CONFIG.read_text())
    scenario_payroll = m.payroll.scenario(payroll_config, retire_date, m.SNAPSHOT_DATE)
    tax_payroll = m.payroll.scenario(payroll_config, retire_date, dt.date(m.SNAPSHOT_DATE.year - 1, 12, 31))
    salary_by_year = tax_payroll["calendar_year_totals"]
    labor_projection = labor_pension_projection(m.STATIC_MODEL_INPUTS["labor_retirement"], m.payroll, payroll_config, retire_date)
    periods = m.build_periods(m.terminal_date(terminal_age), home_repair_cost, home_repair_interval,
        appliance_cost, appliance_interval, appliance_first_year, pre_retirement,
        retire_date, scenario_payroll, tuple(dt.date.fromisoformat(d) if isinstance(d,str) else d for d in extra_grid_dates))
    budget_limits = {}
    protected_care = {}
    for period in periods:
        date = period["start"]
        age_at_start = date.year-m.BIRTH.year-((date.month,date.day)<(m.BIRTH.month,m.BIRTH.day))
        tier = spending_tier_for_age(age_at_start, pension_age, care_age,
            pre_pension_spend, pre_care_spend, age_75_to_80_spend, care_spend,
            stage_age_75, stage_age_80)
        budget_limits[date.year] = budget_limits.get(date.year, 0.) + tier*(period["end"]-date).days/(366 if calendar.isleap(date.year) else 365)
        if period['retired'] and age_at_start >= care_age:
            protected_care[date.year] = protected_care.get(date.year, 0.) + min(tier,m.STATIC_MODEL_INPUTS['spending']['foreign_caregiver_annual'])*(period['end']-date).days/(366 if calendar.isleap(date.year) else 365)
    tax_budget = AnnualTaxBudget(budget_limits, n, protected_care)
    initial_total = float(initial_total if initial_total is not None else (m.INITIAL_TOTAL_2026 if pre_retirement else m.INITIAL_TOTAL_2027))
    if initial_total <= 0 or reserve_amount < 0:
        raise ValueError("initial assets must be positive and reserve nonnegative")
    reserve_amount = min(reserve_amount, initial_total)
    target = np.asarray(allocation, dtype=float)
    start_weight = np.asarray(m.START_WEIGHTS if pre_retirement else allocation)
    assets = np.broadcast_to(m.active_weights_for(start_weight, initial_total, reserve_amount) * (initial_total - reserve_amount), (n, 3)).copy()
    reserve = np.full(n, reserve_amount)
    reserve_min = reserve.copy()
    reserve_max = reserve.copy()
    failed = np.zeros(n, dtype=bool)
    failure_period = np.full(n, -1, dtype=np.int32)
    initial_price_level = getattr(m, "SNAPSHOT_PRICE_LEVEL", reference_price_level(m.SNAPSHOT_DATE, m.INFLATION_MEAN))
    price_level = np.full(n, initial_price_level)
    inflation_path = InflationPath(n, seed, m.INFLATION_LOWER, m.INFLATION_UPPER)
    pension_terms = labor_insurance_claim_terms(
        m.BIRTH, retire_date, m.STATIC_MODEL_INPUTS["labor_insurance"]
    )
    pension_index = PensionIndexation(n, pension_terms["claim_date"].year,
                                     pension_terms["monthly_nominal"])
    car_earmark = np.zeros(n)
    labor_receipt_audit = None
    first_pension = None
    equity_index = np.ones(n)
    bond_index = np.ones(n)
    equity_net_index = np.ones(n)
    bond_net_index = np.ones(n)
    equity_peak = np.ones(n)
    equity_up = np.ones(n, dtype=bool)
    benchmark_1 = np.full(n, np.nan)
    benchmark_2 = np.full(n, np.nan)
    retirement_start_value = np.full(n, np.nan)
    retirement_start_weights = np.broadcast_to(start_weight, (n, 3)).copy()
    elapsed = 0.
    retired_months = 0
    signals = MonthlySignals(n, bear_drawdown=cfg["monthly_signal_proxy"]["bear_drawdown_initial"])
    bull_signal = np.zeros(n, dtype=bool)
    spending_bull_signal = np.zeros(n, dtype=bool)
    spending_bear_signal = np.zeros(n, dtype=bool)
    # D-193 is a market state, independent of tactical wait and prior sales.
    spending_signals = MonthlySignals(n, bear_drawdown=cfg["monthly_signal_proxy"]["bear_drawdown_initial"])
    bear_signal = np.zeros(n, dtype=bool)
    tactical_rng = np.random.default_rng(np.random.SeedSequence([seed, 139, 3]))
    ordinary_rng = np.random.default_rng(np.random.SeedSequence([seed, 139, 4]))
    ordinary_dates = []
    ordinary_events = np.zeros(n, dtype=np.int16)
    ordinary_fx_violations = 0
    ordinary_capacity_violations = 0
    bull_sales = np.zeros(n, dtype=np.int16)
    bear_buys = np.zeros(n, dtype=np.int16)
    bull_turnover = np.zeros(n)
    bear_turnover = np.zeros(n)
    tactical_cost = np.zeros(n)
    all_trade_cost = np.zeros(n)
    all_turnover = np.zeros(n)
    # Twelve monthly observation slots, compared before being overwritten.
    wrong_events = [None] * 12
    wrong_count = observed_count = incomplete_count = 0
    labor_routes = {"BULL_HIGH_BONDS": 0, "BEAR_LOW_EQUITY": 0, "NEUTRAL_STRATEGIC": 0}
    rates = m.simulation_security_rates()
    source_inputs = dict(m.STATIC_MODEL_INPUTS)
    if enable_voo_bridge:
        for alias,base in voo_bridge_proxy.ALIASES.items():rates[alias]=dict(rates[base])
    source_inputs["runtime_tsmc_waiver_active"]=bool(tsmc_waiver_active)
    source_inputs["runtime_enable_voo_bridge"]=enable_voo_bridge
    source_inputs["runtime_voo_fx_wait_proxy"]=enable_voo_fx_wait_proxy or enable_voo_bridge
    source_inputs["runtime_execution_fx_mid"]=float(m.TEN_YEAR_PERFORMANCE["fx_history"]["last_mid"])
    bridge_fx=getattr(m,"AUDIT_BRIDGE_FX",{})
    source_inputs.update(bridge_fx)
    source_inputs["runtime_security_rates"] = rates
    source_inputs["runtime_usd_twd"] = m.FX_CURRENT_MID
    source_inputs["runtime_initial_price_level"] = initial_price_level
    source_inputs["runtime_retirement_date"] = str(retire_date)
    source_inputs["runtime_usd_exposure_by_security"] = {a["asset"]: a["usd_exposed"] for a in m.TEN_YEAR_PERFORMANCE["assets"]}
    source_inputs["runtime_trailing_twd_returns"] = {a["asset"]: a["periods"][-1]["twd_price_return"] + a["periods"][-1]["twd_cash_yield_gross"]
        for a in m.TEN_YEAR_PERFORMANCE["assets"] if a.get("periods")}
    source_inputs["runtime_global_equity_earmarked_twd"] = m.CURRENT_CASH_INCOME["tactical_funding_capacity"]["usd_earmarked_for_global_equity_twd"]
    # Allocation diagnostics are explicit hypothetical target changes. Preserve
    # within-bucket proportions; never write them back to accepted policy.
    hypothetical = not np.allclose(target,m.TARGET_WEIGHTS,rtol=0,atol=1e-12)
    if hypothetical:
        bonds={"00865B","00719B","00697B","SGOV","00679B","00751B","24B2"}
        source_inputs["detailed_target_allocation_2027"]={
            c:w*target[2 if c in {"TWD_term_deposits","demand_deposits_and_cash"} else int(c in bonds)]/m.TARGET_WEIGHTS[2 if c in {"TWD_term_deposits","demand_deposits_and_cash"} else int(c in bonds)]
            for c,w in source_inputs["detailed_target_allocation_2027"].items()}
    ledger = TransitionLedger(m.RUNTIME_ASSET_PAYLOAD, source_inputs, n, seed, m.RUNTIME_TARGET_PRICES, initial_total / m.INITIAL_TOTAL_2026)
    ledger.tax_cover = lambda amount, date: tax_budget.cover(amount, date.year)
    ledger.sync(assets)
    bear_ladder = BearLadder(source_inputs["execution_policy"]["dynamic_allocation_policy_d141"], n)
    bull_ladder = BullLadder(source_inputs["execution_policy"]["dynamic_allocation_policy_d141"], n)
    bull_ladder.equity_floor = float(target[0])  # D-216: optional profit taking retains the strategic core.
    bond_codes = ledger.bond_codes
    high_codes = {"0056", "00713", "00878", "00919", "00882"}
    with (m.ROOT/"portfolio/target_allocation_2027.csv").open() as f:
        upper_by_code={r["ETF或資產"]:float(r["上限_pct"])/100 for r in csv.DictReader(f)}
    upper = np.array([upper_by_code.get(c,0) for c in ledger.codes])
    if hypothetical:
        upper *= target[ledger.bucket]/np.asarray(m.TARGET_WEIGHTS)[ledger.bucket]
    high_group = ledger.high_group
    global_group = np.array([c in {"009826","VT","VOO"} for c in ledger.codes])
    codes = ledger.codes + ["TWD_term_deposits", "demand_deposits_and_cash"]
    groups = {c: ("cash_like" if c.startswith(("TWD_", "demand_")) else "funds" if c=="24B2" else "bonds" if c in bond_codes else "high_dividend" if c in high_codes else "market_growth") for c in codes}
    raw_income = np.zeros((n, len(codes)))
    prior_income = np.zeros_like(raw_income)
    prior_fraction = (m.SNAPSHOT_DATE - dt.date(m.SNAPSHOT_DATE.year, 1, 1)).days / 365.25 if pre_retirement else 0.
    for j, code in enumerate(ledger.codes):
        prior_income[:, j] = ledger.values[:, j] * initial_price_level * float(rates[code]["regular_cash_yield"]) * prior_fraction
    prior_income[:, -2] = float(m.CURRENT_CASH_INCOME["current"]["deposit_interest_twd"]) * prior_fraction
    # Opening-year investment income before the live snapshot is a disclosed
    # current-run-rate estimate for tax only, never a second asset inflow.
    prior_tax = m.tax_components_from_income({c: prior_income[:, j] for j,c in enumerate(codes)}, rates, False, 0.)
    prior_paid_cash_tax = prior_tax["supplementary"] + prior_tax["us_withholding"]
    raw_income += prior_income
    # market-growth, high-dividend, bonds, active cash, permanent term deposit
    exposures = np.zeros((n, 5))
    permanent_term_interest_reinvested = np.zeros(n)
    avg_buckets = np.zeros((n, 3))
    year_fraction = 0.
    annual_pension = np.zeros(n)
    annual_living_spend = np.zeros(n)
    month_fraction = 0.
    year_start = periods[0]["start"]
    pending_tax = {}
    tax_audit = []
    annual_rows = []
    frozen_fractions = np.zeros_like(ledger.values)
    for bucket in (0, 1):
        mask = ledger.bucket == bucket
        denominator = ledger.values[:, mask].sum(axis=1)
        frozen_fractions[:, mask] = np.divide(ledger.values[:, mask], denominator[:, None],
            out=np.zeros_like(ledger.values[:, mask]), where=denominator[:, None] > 0)
    snapshots = np.zeros((len(periods) + 1, n)) if capture_percentiles else None
    if snapshots is not None:
        snapshots[0] = initial_total
    dates = [periods[0]["start"]]
    weight_sum = np.zeros(n)
    weight_min = np.ones(n)
    weight_max = np.zeros(n)
    bond_min = np.ones(n)
    bond_max = np.zeros(n)
    month_observations = 0
    mean = m.GROSS_MEAN.copy()
    if equity_real_geometric is not None:
        mean[0] = m.calibrated_equity_mean(equity_real_geometric, tuple(m.VOLATILITY), m.USD_EQUITY_EXPOSURE)
    if bond_yield_shift:
        mean[1] = m._real_return(m._bond_forward_nominal_net() + bond_yield_shift)
    factor = m.covariance_factor(np.outer(m.VOLATILITY, m.VOLATILITY) * m.CORRELATION)
    t_adjust = math.sqrt((m.DEGREES_FREEDOM - 2) / m.DEGREES_FREEDOM)
    market_path = CalendarMarketPath(m, n, seed, factor, t_adjust)

    def mark_failure(index, terminal=False):
        voo_bridge_proxy.release_excess_cash(ledger,assets,reserve)
        raise_cash(0.)
        broke = assets[:, 2] - ledger.bridge_cash - ledger.bridge_pending < -1e-7
        if terminal:
            broke |= assets.sum(axis=1) <= 0
        new = broke & ~failed
        failure_period[new] = index
        failed[:] |= broke
        assets[failed] = 0.
        ledger.bridge_cash[failed] = 0.; ledger.bridge_pending[failed] = 0.
        # Protected reserve is never consumed, including failed paths.

    def flush_exposures():
        nonlocal permanent_term_interest_reinvested
        if frozen_fractions is None:
            return
        for j,c in enumerate(ledger.codes):
            col = 2 if ledger.bucket[j] == 1 else 1 if c in high_codes else 0
            raw_income[:, j] += exposures[:, col] * frozen_fractions[:, j] * float(rates[c]["regular_cash_yield"])
        for k, weight in ((len(codes)-2, .164/.17), (len(codes)-1, .006/.17)):
            raw_income[:, k] += exposures[:, 3] * weight * float(rates[codes[k]]["regular_cash_yield"])
        # The permanent reserve is entirely a TWD term deposit.  Its interest
        # remains source income for tax but renews into the next term, so keep
        # it distinct from active cash when creating the annual cash report.
        locked = exposures[:, 4] * float(
            rates["TWD_term_deposits"]["regular_cash_yield"]
        )
        raw_income[:, len(codes)-2] += locked
        permanent_term_interest_reinvested += locked
        exposures.fill(0)

    def positions():
        ledger.sync(assets)
        return ledger.values.copy()

    def apply_positions(values):
        """Keep security composition through both transition and long term."""
        nonlocal frozen_fractions
        flush_exposures()
        values = np.maximum(values, 0)
        per_unit = ledger.nominal_prices / price_level[:, None]
        per_unit[:, ledger.us] *= ledger.fx[:, None]
        # Unchanged holdings retain exact shares through the full horizon.
        existing = ledger.values > 0
        ratio = np.divide(values, ledger.values, out=np.zeros_like(values), where=existing)
        new_units = np.divide(values, per_unit, out=np.zeros_like(values), where=per_unit>0)
        ledger.units = np.where(existing, ledger.units * ratio, new_units)
        ledger.values = values
        if not ledger_active or frozen_fractions is not None:
            for b in (0, 1):
                mask = ledger.bucket == b
                total_b = values[:, mask].sum(axis=1)
                frozen_fractions[:, mask] = np.divide(values[:, mask], total_b[:, None], out=np.zeros_like(values[:, mask]), where=total_b[:, None]>0)
        assets[:, 0] = values[:, ledger.bucket == 0].sum(axis=1)
        assets[:, 1] = values[:, ledger.bucket == 1].sum(axis=1)

    def advance_prices(security_return, cash_return, fraction, inflation, fx_return):
        """Mark actual shares once and accrue the same distributions for tax."""
        nonlocal price_level, reserve, frozen_fractions, permanent_term_interest_reinvested
        ledger.sync(assets)
        opening_cash = assets[:, 2].copy()
        opening_reserve = reserve.copy()
        opening_price = price_level.copy()
        bucket_return = weighted_bucket_returns(ledger.values, security_return, ledger.bucket, cash_return)
        assets[:] *= 1 + bucket_return
        next_price = opening_price * (1 + inflation)
        payouts = ledger.mark(bucket_return, fraction, next_price, fx_return, assets, bear, security_return)
        raw_income[:, :len(ledger.codes)] += payouts * next_price[:, None]
        for k, weight in ((len(codes)-2, .164/.17), (len(codes)-1, .006/.17)):
            raw_income[:, k] += np.maximum(opening_cash, 0) * weight * float(rates[codes[k]]["regular_cash_yield"]) * fraction * opening_price
        nominal_term_return = (1 + float(m.cash_income.TWD_TERM_DEPOSIT_RATE)) ** fraction - 1
        term_real_return = (1 + nominal_term_return) / (1 + inflation) - 1
        reserve, top_up = roll_permanent_term_deposit(opening_reserve, term_real_return, m.PERMANENT_RESERVE_2026_TWD)
        assets[:, 2] -= top_up
        locked_interest = opening_reserve * opening_price * nominal_term_return
        raw_income[:, len(codes)-2] += locked_interest
        permanent_term_interest_reinvested += locked_interest
        reserve_min[:] = np.minimum(reserve_min, reserve)
        reserve_max[:] = np.maximum(reserve_max, reserve)
        price_level = next_price
        if frozen_fractions is not None:
            for bucket in (0, 1):
                mask = ledger.bucket == bucket
                denominator = ledger.values[:, mask].sum(axis=1)
                frozen_fractions[:, mask] = np.divide(ledger.values[:, mask], denominator[:, None],
                    out=np.zeros_like(ledger.values[:, mask]), where=denominator[:, None] > 0)
        return bucket_return

    def equity_purchase_shares(high_share, age):
        result = np.zeros(len(ledger.codes))
        for high, share in ((True, high_share), (False, 1-high_share)):
            eligible = [j for j,c in enumerate(ledger.codes) if ledger.bucket[j]==0 and ((c in high_codes)==high) and ledger.weights[j]>0 and not (age>=70 and c=="VT")]
            norm = sum(ledger.weights[j] for j in eligible)
            for j in eligible:
                result[j] = share * ledger.weights[j] / norm
        return result

    def equity_capacity(values, weights, total, global_cap=GLOBAL_GROUP_CAP):
        # D-141 §2/§6 (D-214 value): the global-equity group cap is 35% normally and is
        # relaxed to 50% only inside a verified bear window. The high-dividend
        # cap (ledger.high_cap, 12%) is never relaxed.
        capacity=np.full(n,np.inf)
        for j in np.flatnonzero(weights):
            purchase_cap = ledger.weights[j] if high_group[j] else upper[j]
            capacity=np.minimum(capacity,np.maximum(total*purchase_cap-values[:,j],0)/weights[j])
        for mask,cap in ((high_group,ledger.high_cap),(global_group,global_cap)):
            share=weights[mask].sum()
            if share:
                capacity=np.minimum(capacity,np.maximum(total*cap-values[:,mask].sum(axis=1),0)/share)
        return capacity

    def raise_cash(required, only_fund=False):
        required = required + ledger.bridge_cash + ledger.bridge_pending
        """Redeem 24B2 only after retirement and only for a cash shortfall."""
        need = np.maximum(required-assets[:,2],0)
        if not np.any(need>1e-8):
            return
        values = positions()
        masks = []
        if retired:
            masks.append((np.array([c=="24B2" for c in ledger.codes]), np.ones(n,dtype=bool)))
        if not only_fund:
            # During age-based restrictions use the other securities. A
            # future qualified VT/SGOV exit and destination are not invented.
            for path_gate, order in ((equity_up,(0,1)),(~equity_up,(1,0))):
                for b in order:
                    masks.append(((ledger.bucket==b)&np.array([not (age>=70 and c in {"VT","SGOV"}) and c!="24B2" and c not in voo_bridge_proxy.ALIASES for c in ledger.codes]),path_gate))
        tax_rates = transaction_tax_rates(ledger.codes, ledger.us, start) if deduct_costs else np.zeros(len(ledger.codes))
        for eligible, path_gate in masks:
            balance=values[:,eligible].sum(axis=1)
            weighted_tax = np.divide((values[:,eligible]*tax_rates[eligible]).sum(axis=1), balance,
                                     out=np.zeros(n), where=balance>0)
            rate = weighted_tax + (.001425 if deduct_costs else 0.)
            gross=np.where(path_gate,np.minimum(need/(1-rate),balance),0)
            fraction=np.divide(gross,balance,out=np.zeros(n),where=balance>0)
            values[:,eligible] *= 1-fraction[:,None]
            assets[:,2] += gross*(1-rate)
            if deduct_costs:
                assets[:,2] += tax_budget.cover(gross*weighted_tax, start.year)
            need=np.maximum(required-assets[:,2],0)
            all_trade_cost[:] += gross*rate
            all_turnover[:] += gross
        apply_positions(values)

    def apply_bucket_targets(before):
        """Only accepted positive-target securities receive ordinary buys."""
        values=frozen_fractions*before[:,ledger.bucket]
        original_values = values.copy()
        desired=assets[:,:2].copy()
        for b in (0,1):
            delta=desired[:,b]-before[:,b]
            buy=(ledger.bucket==b)&(ledger.weights>0)&np.array([not (age>=70 and c in {"VT","SGOV"}) for c in ledger.codes])
            weights=np.where(buy,ledger.weights,0)
            weights /= weights.sum()
            additions = np.maximum(delta,0)[:,None]*weights
            # The broad bucket target cannot authorize a symbol above its
            # current accepted purchase cap. Unfilled amounts remain cash.
            total = np.minimum(before.sum(axis=1), assets.sum(axis=1))+reserve
            headroom = np.maximum(total[:,None]*ledger.weights-values,0)
            additions = np.minimum(additions,headroom)
            if b == 0:
                group_additions = additions[:, high_group].sum(axis=1)
                group_room = np.maximum(total * ledger.high_cap - values[:, high_group].sum(axis=1), 0)
                scale = np.divide(np.minimum(group_additions, group_room), group_additions,
                                  out=np.zeros(n), where=group_additions > 0)
                additions[:, high_group] *= scale[:, None]
            if b==1:
                j=ledger.codes.index("00697B")
                additions[:,j]=np.minimum(additions[:,j],np.maximum((.30*(before[:,1]+additions.sum(axis=1)-additions[:,j])-values[:,j])/.70,0))
            values += additions
            sell=(ledger.bucket==b)&np.array([c!="24B2" and c not in voo_bridge_proxy.ALIASES and not (age>=70 and c in {"VT","SGOV"}) for c in ledger.codes])
            available=values[:,sell].sum(axis=1)
            sale=np.minimum(np.maximum(-delta,0),available)
            fraction=np.divide(sale,available,out=np.zeros(n),where=available>0)
            values[:,sell] *= 1-fraction[:,None]
        # Solve purchases and fees against the protected cash before applying
        # any position change. A later living-cash refill must not undo a buy.
        protected = living_floor + car_earmark + ledger.stock_reserve + ledger.bond_reserve + ledger.pending_stock + ledger.pending_bond + ledger.earmark()
        values=voo_bridge_proxy.cap_direct_voo_purchase(ledger,original_values,values,before.sum(axis=1)+reserve,deduct_costs)
        values, funded_cash, fees = funded_rebalance(original_values, values, before[:,2], protected,
            before.sum(axis=1)+reserve, ledger.weights,
            ((high_group, ledger.high_cap), (global_group, GLOBAL_GROUP_CAP), (ledger.bucket == 0, .60)), deduct_costs,
            sell_tax_rates=transaction_tax_rates(ledger.codes, ledger.us, start))
        # The caller books the actual fees once after measuring the net legs.
        assets[:, 2] = funded_cash + fees
        apply_positions(values)
        sales = np.maximum(original_values-values,0)
        taxes = (sales*transaction_tax_rates(ledger.codes,ledger.us,start)).sum(axis=1) if deduct_costs else np.zeros(n)
        return fees, taxes, np.abs(values-original_values).sum(axis=1)

    for index, p in enumerate(periods):
        start, end = p["start"], p["end"]
        fraction = p["year_fraction"]
        spending_fraction = (end-start).days/(366 if calendar.isleap(start.year) else 365)
        retired = p["retired"]
        ledger_active = start < dt.date(2028, 7, 1)
        age = start.year - m.BIRTH.year - ((start.month, start.day) < (m.BIRTH.month, m.BIRTH.day))
        if retired and np.all(np.isnan(retirement_start_value)):
            retirement_start_value = assets.sum(axis=1) + reserve
            retirement_start_weights = np.column_stack((assets[:, 0], assets[:, 1], assets[:, 2] + reserve)) / retirement_start_value[:, None]
            benchmark_1[:] = retirement_start_value
            benchmark_2[:] = retirement_start_value
            ledger.record(retire_date.isoformat(), assets, reserve)
        period_opening_cpi = price_level.copy()
        opening = assets.copy()
        whole_open = np.column_stack((assets[:, 0], assets[:, 1], assets[:, 2] + reserve))
        reserve_open = reserve.copy()
        drawdown_open = equity_index / equity_peak - 1
        bear = drawdown_open <= -.20
        high_share = np.full(n, m.HIGH_DIVIDEND_SHARE_AT_RETIREMENT)
        if frozen_fractions is not None:
            high_share = sum(frozen_fractions[:, j] for j,c in enumerate(ledger.codes) if c in high_codes)
        # D-152: funding retains a fixed TWD 750K one-year living floor. The
        # 2.25M ceiling is a qualified-bull refill target, not a second buy gate.
        spend_tier = spending_tier_for_age(age, pension_age, care_age,
            pre_pension_spend, pre_care_spend, age_75_to_80_spend, care_spend,
            stage_age_75, stage_age_80)
        market_delta = market_spending_adjustment(spending_bear_signal, spending_bull_signal,
            market_spend_adjustment_twd) if retired else np.zeros(n)
        market_budget_reclaim = np.zeros(n)
        if retired:
            # Match the annual tax-included envelope to the path's actual
            # monthly market state without using future months in the year.
            market_budget_reclaim = tax_budget.adjust_limit(
                market_delta * spending_fraction, start.year
            )
        living_floor = m.required_operating_living_cash(end, spend_tier if retired else None)
        expense = np.full(n, pre_pension_spend * spending_fraction)
        if retired:
            tier = spend_tier
            expense[:] = (tier + market_delta) * spending_fraction
            if guardrail:
                total = assets.sum(axis=1) + reserve
                cut = np.where(total < benchmark_1, 100_000., np.where(total < benchmark_2, 50_000., 0.))
                if age >= care_age and not care_guardrail:
                    cut[:] = 0
                reduced = np.minimum(expense, cut * spending_fraction)
                expense -= reduced
                assets[:, 2] -= tax_budget.reduce(reduced, start.year)
        pension = np.zeros(n)
        if p["pension_months"] > 0:
            pension_nominal = pension_index.payment(start)
            pension = pension_nominal * p["pension_months"] / price_level
            if first_pension is None:
                first_pension = {"period_index": index, "period_start": str(start), "claim_date": str(pension_terms["claim_date"]),
                    "months": p["pension_months"], "months_early": pension_terms["months_early"],
                    "months_delayed": pension_terms["months_delayed"],
                    "permanent_adjustment": pension_terms["permanent_adjustment"],
                    "elapsed_years_from_snapshot": elapsed, "nominal_monthly": float(np.median(pension_nominal)),
                    "real_total_median": float(np.median(pension)), "price_level_median": float(np.median(price_level))}
        special = p["special"]
        if start == dt.date.fromisoformat(m.STATIC_MODEL_INPUTS["labor_retirement"]["new_car_purchase_date"]):
            car_earmark[:] = 0.
        payroll_tax_covered = tax_budget.cover(p["payroll_tax_nominal"] / price_level, start.year)
        cashflow = (pension + special + p["payroll_nominal"] / price_level
                    - expense + payroll_tax_covered - market_budget_reclaim)
        annual_pension += pension
        annual_living_spend += expense
        if (start.month, start.day) == (5, 31) and start.year in pending_tax:
            nominal = pending_tax.pop(start.year)
            cashflow += nominal / price_level + tax_budget.cover(-nominal/price_level, start.year)
            salary_row = salary_by_year.get(str(start.year - 1), {})
            tax_audit.append({"TaxYear": start.year - 1, "SettlementDate": str(start), "SalaryGross": salary_row.get("gross_pay", 0),
                "SalaryWithheld": salary_row.get("income_tax_withholding", 0),
                "SettlementNominal_p10": float(np.percentile(nominal, 10)), "SettlementNominal_median": float(np.median(nominal)), "SettlementNominal_p90": float(np.percentile(nominal, 90)), "PositiveMeansRefund": True})
        period_mean = mean.copy()
        if lower_return_decade and retired and (start - retire_date).days < 365.25 * 10:
            period_mean = np.array([.015, .005, -.002, 0.])
        draws = market_path.period(start, period_mean, fraction)
        infl_upper = high_inflation_5y and retired and (start - retire_date).days < 365.25 * 5
        inflation = inflation_path.period(start, fraction, upper_bound=infl_upper)
        per_security_returns, cash_return = security_returns(draws, ledger.bucket, ledger.usd_exposure,
                                                              inflation, fraction, m.BASE_INFLATION_RATE)
        if early_crash and retired and start == retire_date:
            per_security_returns[:, ledger.bucket == 0] = -.35
            per_security_returns[:, ledger.bucket == 1] = .03
            cash_return[:] = 0.
        if retired and start == retire_date and bond_parallel_rate_shock:
            durations = np.array([float(m.BOND_FORWARD["funds"].get(c, {}).get("effective_duration_years", m._bond_forward_duration())) for c in ledger.codes])
            per_security_returns[:, ledger.bucket == 1] -= durations[ledger.bucket == 1] * bond_parallel_rate_shock
        for j, code in enumerate(ledger.codes):
            if ledger.bucket[j] == 0:
                per_security_returns[bear, j] -= ledger.cash_yield[j] * (.30 if code in high_codes else .20) * fraction
        per_security_returns = np.maximum(per_security_returns, -.95)
        returns = weighted_bucket_returns(positions(), per_security_returns, ledger.bucket, cash_return)
        mid = retired and retirement_cashflow_timing == "midpoint"
        first_half_return = np.zeros_like(returns)
        if mid:
            first_half_return = advance_prices(np.sqrt(1 + per_security_returns) - 1,
                np.sqrt(1 + cash_return) - 1, fraction / 2,
                np.sqrt(1 + inflation) - 1, np.sqrt(1 + draws[:, 3]) - 1)
        assets[:, 2] += cashflow
        lump = np.zeros(n)
        if p["labor_retirement"]:
            received = (float(p["labor_retirement"]) / price_level
                        if m.STATIC_MODEL_INPUTS["labor_retirement"].get("receipt_amount_basis") == "nominal TWD at receipt"
                        else np.full(n, float(p["labor_retirement"])))
            car_earmark = (np.minimum(received, m.AGE_60_NEW_CAR_COST_2026_TWD)
                if start < dt.date.fromisoformat(m.STATIC_MODEL_INPUTS["labor_retirement"]["new_car_purchase_date"]) else np.zeros(n))
            assets[:, 2] += car_earmark
            lump = received - car_earmark
            receipt_audit = {"date": str(start), "nominal_receipt_median_twd": float(np.median(received * price_level)),
                "real_receipt_median_twd": float(np.median(received)), "car_reserved_real_median_twd": float(np.median(car_earmark)),
                "investable_real_median_twd": float(np.median(lump)), "car_expense_booked_separately_once": True}
            bull = (bull_signal & ~failed) if enable_bull_timing else np.zeros(n,dtype=bool)
            low = (bear_signal & ~failed) if enable_bear_timing else np.zeros(n,dtype=bool)
            neutral = ~(bull | low) & ~failed
            purchase_share=np.where(bull|low,1.,target[0]+target[1])
            invest=lump/(1+purchase_share*(.001425 if deduct_costs else 0.))
            values=positions()
            original_values = values.copy()
            assets[:, 2] += lump
            # Bear receipts remain cash until the same five-batch ladder permits a fill.
            low_allocations = np.zeros_like(values)
            values += low_allocations
            values += (invest*neutral*target[0])[:,None]*equity_purchase_shares(m.HIGH_DIVIDEND_SHARE_AT_RETIREMENT,age)
            # D-185: SGOV is now authorized for new purchases and shares in
            # ordinary strategic bond money like any other accepted bond target.
            bond_weights=np.where(ledger.bucket==1,ledger.weights,0);bond_weights/=bond_weights.sum()
            values += (invest*neutral*target[1])[:,None]*bond_weights
            short=np.array([c in {"00865B","00719B","SGOV"} for c in ledger.codes])
            short_weights=np.where(short,ledger.weights,0);short_weights/=short_weights.sum()
            values += (invest*bull)[:,None]*short_weights
            caps = np.broadcast_to(upper, values.shape).copy()
            caps[low, ledger.codes.index("VOO")] = .42
            caps[:, high_group] = ledger.weights[high_group]
            protected = living_floor + car_earmark + ledger.stock_reserve + ledger.bond_reserve + ledger.pending_stock + ledger.pending_bond + ledger.earmark()
            values=voo_bridge_proxy.cap_direct_voo_purchase(ledger,original_values,values,assets.sum(axis=1)+reserve,deduct_costs,applicable_cap=caps[:,ledger.codes.index("VOO")])
            values, cash_after, commission = funded_rebalance(original_values, values, assets[:, 2],
                protected, assets.sum(axis=1) + reserve, caps,
                ((high_group, ledger.high_cap), (global_group, np.where(low, .50, GLOBAL_GROUP_CAP)),
                 (ledger.bucket == 0, np.where(low, .70, .60))), deduct_costs)
            assets[:, 2] = cash_after
            apply_positions(values)
            labor_routes["BULL_HIGH_BONDS"] += int(bull.sum())
            labor_routes["BEAR_LOW_EQUITY"] += int(low.sum())
            labor_routes["NEUTRAL_STRATEGIC"] += int(neutral.sum())
            all_trade_cost[~failed] += commission[~failed]
            all_turnover[~failed] += (values-original_values).sum(axis=1)[~failed]
            receipt_audit["actually_invested_real_median_twd"] = float(np.median((values-original_values).sum(axis=1)))
            receipt_audit["fees_real_median_twd"] = float(np.median(commission))
            if labor_receipt_audit is None:
                labor_receipt_audit = dict(receipt_audit, projection=labor_projection, receipts=[])
            labor_receipt_audit["receipts"].append(receipt_audit)
        mark_failure(index)
        if ledger_active or start < retire_date:
            ledger.sync(assets)
            before_cost = ledger.costs.copy()
            before_turn = ledger.turnover.copy()
            # D-152 applies the 2026-08-real TWD 750K one-year floor throughout
            # the rotation ledger.  The permanent TWD term deposit is in the
            # separate ``reserve`` bucket, the USD earmark is separately
            # protected by the ledger, and the TWD 2.25M living-reserve amount
            # is a qualified-bull refill target rather than a second buy gate.
            ledger.scheduled_dca(assets, reserve, m.required_operating_living_cash(start), deduct_costs, start)
            all_trade_cost += ledger.costs - before_cost
            all_turnover += ledger.turnover - before_turn
        if not mid:
            whole_open = np.column_stack((assets[:, 0], assets[:, 1], assets[:, 2] + reserve))
        remaining_return = advance_prices(
            np.sqrt(1 + per_security_returns) - 1 if mid else per_security_returns,
            np.sqrt(1 + cash_return) - 1 if mid else cash_return,
            fraction / 2 if mid else fraction,
            np.sqrt(1 + inflation) - 1 if mid else inflation,
            np.sqrt(1 + draws[:, 3]) - 1 if mid else draws[:, 3])
        returns = (1 + first_half_return) * (1 + remaining_return) - 1
        next_price = price_level
        pension_index.observe(start, end, period_opening_cpi, price_level)
        equity_up = returns[:, 0] >= 0
        equity_index *= np.maximum(1 + returns[:, 0], .05)
        bond_index *= np.maximum(1 + returns[:, 1], .05)
        equity_net_index *= np.maximum(1 + returns[:, 0], .05)
        bond_net_index *= np.maximum(1 + returns[:, 1], .05)
        equity_peak = np.maximum(equity_peak, equity_index)
        month_fraction += fraction
        execution_tick = ((p["month_end"] and not (start.month == 6 and start.day == 30))
                          or (end.month == 6 and end.day == 30))
        if execution_tick and month_fraction >= 20/365.25:
            old_cost, old_turn = ledger.costs.copy(), ledger.turnover.copy()
            if ledger_active:
                ledger.step(assets, reserve, start, end, probability,
                    m.required_operating_living_cash(end), deduct_costs,
                    bear_ladder.active if enable_bear_timing else None)
            elif ledger.stock_reserve_lots or ledger.pending_sales:
                # An existing reserve does not lose its age or settlement
                # obligation when ordinary transition trading has ended.
                ledger.cadence.begin(start, ledger.execution_fx())
                ledger.advance_idle_month(assets, reserve, living_floor,
                    .001425 if deduct_costs else 0., bear_ladder.active if enable_bear_timing else None, probability=probability)
            all_trade_cost += ledger.costs - old_cost
            all_turnover += ledger.turnover - old_turn
        if end == dt.date(2028, 7, 1):
            frozen_fractions = np.zeros_like(ledger.values)
            for bucket in (0, 1):
                mask = ledger.bucket == bucket
                denominator = ledger.values[:, mask].sum(axis=1)
                frozen_fractions[:, mask] = np.divide(ledger.values[:, mask], denominator[:, None],
                    out=np.zeros_like(ledger.values[:, mask]), where=denominator[:, None] > 0)
        if p["month_end"]:
            ledger.record_month_returns()
        avg_buckets += whole_open * fraction
        year_fraction += fraction
        price_level = next_price
        elapsed += fraction
        if p["year_end"] or index == len(periods)-1:
            year = start.year
            flush_exposures()
            incomes = {c: raw_income[:, j] for j,c in enumerate(codes)}
            salary_row = salary_by_year.get(str(year), {})
            salary = salary_row.get("gross_pay", 0) - salary_row.get("voluntary_labor_pension", 0)
            tc = m.tax_components_from_income(incomes, rates, year >= 2040, salary)
            net_taxes = [np.zeros(n), np.zeros(n)]
            domestic_basis = tc["domestic"] + tc["domestic_interest"] + tc["other_domestic"]
            for j,c in enumerate(ledger.codes):
                gross = incomes[c]
                r = rates[c]
                direct = gross * float(r.get("cash_withholding_rate") or 0) + tc["nhi_by_code"][c]
                db = gross * sum(float(r.get(k) or 0) for k in ("domestic_dividend_share", "domestic_interest_share", "other_domestic_income_share"))
                direct += tc["domestic_tax"] * np.divide(db, domestic_basis, out=np.zeros(n), where=domestic_basis>0)
                fb = gross * float(r.get("overseas_income_share") or 0)
                direct += tc["overseas_amt"] * np.divide(fb, tc["foreign"], out=np.zeros(n), where=tc["foreign"]>0)
                net_taxes[ledger.bucket[j]] += direct
            for b, idx in ((0,equity_net_index),(1,bond_net_index)):
                base = avg_buckets[:, b] / max(year_fraction, 1e-10) * price_level
                idx *= np.maximum(1 - np.divide(net_taxes[b], base, out=np.zeros(n), where=base>0), .05)
            # Payment-event NHI and US withholding are settled annually as a
            # forward timing approximation; no double deduction in May.
            paid_tax = (tc["supplementary"] + tc["us_withholding"] - prior_paid_cash_tax) / price_level
            assets[:, 2] -= paid_tax - tax_budget.cover(paid_tax, year)
            prior_paid_cash_tax = np.zeros(n)
            if year >= m.SNAPSHOT_DATE.year:
                liability = tc["domestic_tax"] + tc["salary_only_tax"] + tc["overseas_amt"]
                pending_tax[year+1] = salary_row.get("income_tax_withholding", 0) - liability
            if capture_annual_cash_income and year >= 2027:
                nominal = price_level
                normalized = {k: ({c: a / nominal / year_fraction for c,a in v.items()} if isinstance(v,dict) else v / nominal / year_fraction) for k,v in tc.items()}
                normalized_permanent_term_interest = (
                    permanent_term_interest_reinvested / nominal / year_fraction
                )
                ref = avg_buckets / year_fraction
                annual = m.annual_cash_income_row({"start": year_start, "end": end, "months": year_fraction*12, "year_fraction": year_fraction}, ref, ref,
                    high_dividend_share_by_path=high_share, bear_mask=bear, tax_components_override=normalized, income_group_map=groups,
                    permanent_term_interest_reinvested_by_path=normalized_permanent_term_interest,
                    salary_income_override=salary, price_level_by_path=price_level)
                annual["income_basis"] = "same-path calendar-year source accrual; permanent TWD term interest is taxed and renewed, not spendable"
                annual["pension_period_real_median_twd"] = float(np.median(annual_pension))
                annual["pension_annualized_real_median_twd"] = float(np.median(annual_pension)/year_fraction)
                annual["living_spend_period_real_median_twd"] = float(np.median(annual_living_spend))
                annual_rows.append(annual)
            raw_income.fill(0); exposures.fill(0); permanent_term_interest_reinvested.fill(0); avg_buckets.fill(0)
            annual_pension.fill(0); annual_living_spend.fill(0)
            year_fraction = 0.; year_start = end
        if p["month_end"]:
            bond_capacity_used = np.zeros(n)
            retired_months += 1
            prior_bull_anchor = signals.anchor.copy()
            bull_signal, bear_signal, drawdown = signals.update(equity_index, tactical_wait_months)
            spending_bull_signal, spending_bear_signal, _ = spending_signals.update(equity_index, 6)
            if enable_voo_bridge or enable_voo_fx_wait_proxy:
                old_cost,old_turn=ledger.costs.copy(),ledger.turnover.copy()
                voo_bridge_proxy.step(ledger,assets,reserve,end-dt.timedelta(days=1),drawdown,bull_signal,probability,living_floor,deduct_costs,
                    tax_cover=lambda tax:tax_budget.cover(tax,start.year))
                all_trade_cost+=ledger.costs-old_cost;all_turnover+=ledger.turnover-old_turn
                apply_positions(ledger.values.copy())
            bond_eligible = np.array([c in {"00865B", "00719B", "00697B"} for c in ledger.codes])
            if enable_bear_timing:
                locked = car_earmark + ledger.stock_reserve + ledger.bond_reserve + ledger.pending_stock + ledger.pending_bond + ledger.earmark()
                bear_ladder.update(drawdown, bear_signal, assets.sum(axis=1) + reserve,
                    positions()[:, bond_eligible].sum(axis=1),
                    available_cash=np.maximum(assets[:, 2]-living_floor-locked, 0),
                    total_bonds=assets[:, 1], equity=assets[:, 0])
            if enable_bull_timing:
                # D-156: preserve the locked anchor through the correction
                # observation so the current pullback can be evaluated before
                # the window resets. New highs raise BullLadder's watermark and
                # do not themselves cause a sale.
                bull_anchor = np.where(np.isnan(signals.anchor), prior_bull_anchor, signals.anchor)
                gain_above_anchor = np.where(np.isnan(bull_anchor), -np.inf, equity_index/bull_anchor - 1)
                bull_ladder.update(gain_above_anchor, bull_signal, assets.sum(axis=1) + reserve,
                    assets[:, 0], corrected=(drawdown <= -.10))
            slot = (retired_months-1) % 12
            event = wrong_events[slot]
            if event is not None:
                mask, ei, bi = event
                observed_count += int(mask.sum())
                penalty = (2*.001425 + .001) if deduct_costs else 0
                wrong_count += int(np.sum(mask & ((equity_net_index / ei) > (bond_net_index / bi) * (1-penalty))))
                wrong_events[slot] = None
            before = assets.copy()
            if not ledger_active and ordinary_review_month(end):
                ordinary_dates.append(str(end-dt.timedelta(days=1)))
                # Two synthetic weeks after the scheduled 15th. Qualification
                # is conditional, not a prediction of future executable data.
                ordinary_ok=ordinary_rng.random(n)<(1-(1-cfg["transition_opportunity_scenarios"]["Planning"])**2)
                if band_rebalance:
                    tactical_cap = np.where(drawdown <= -.30, .70, .60)
                    bear_rebalance = enable_bear_timing & bear_ladder.active
                    m.rebalance_policy_band(assets, reserve, target,
                        cap=np.where(bear_rebalance, tactical_cap, .60),
                        floor=np.where(bear_rebalance | (enable_bull_timing & signals.used), 0., .50),
                        rebuild_to=m.EQUITY_REBUILD_TO, tactical_bear=bear_rebalance,
                        protected_operating_cash=living_floor + car_earmark + ledger.stock_reserve + ledger.bond_reserve
                            + ledger.pending_stock + ledger.pending_bond + ledger.earmark())
                else:
                    total = assets.sum(axis=1)+reserve
                    assets[:] = (total-reserve)[:,None] * m.active_weights_for(target, total, reserve)
                ordinary_ok &= ~(bear_ladder.active if enable_bear_timing else np.zeros(n, dtype=bool))
                assets[~ordinary_ok] = before[~ordinary_ok]
                local=[ledger.codes.index(c) for c in ("00865B","00719B","00697B")]
                capacity=np.min(ledger.nominal_prices[:,local],axis=1)*1000*cfg["ordinary_rebalancing_proxy"]["maximum_bond_purchase_days"]/price_level
                bond_capacity_used = constrain_bond_purchase(assets,before,capacity,ledger.execution_fx())
                actual_fees, actual_taxes, actual_turnover = apply_bucket_targets(before)
                delta = assets - before
                actual_bond_buy=np.maximum(delta[:,1],0)
                ordinary_capacity_violations += int(np.sum(actual_bond_buy>capacity+1e-6))
                ordinary_fx_violations += int(np.sum((actual_bond_buy>1e-6)&(ledger.execution_fx()>33.)))
                ordinary_events += (np.abs(delta[:,:2]).sum(axis=1)>1e-6).astype(np.int16)
                cost = actual_fees
                if deduct_costs:
                    assets[:,2] -= cost
                    assets[:,2] += tax_budget.cover(actual_taxes, start.year)
                    all_trade_cost += cost
                all_turnover += actual_turnover
            # Both legs still need a future qualified opportunity. This joint
            # scenario is independent of market shocks and is never a quote.
            opportunity = tactical_rng.random(n) < (1-(1-probability**2)**4)
            if enable_bear_timing:
                durations = np.array([float(m.BOND_FORWARD["funds"].get(c, {}).get("effective_duration_years", 0)) for c in ledger.codes])
                trailing = ledger.trailing_twd_returns()
                eligible = bond_eligible[None, :] & np.isfinite(trailing)
                for week in range(4):
                    session = retired_months * 20 + week * 5
                    qualified = (tactical_rng.random(n) < probability**2) & ~failed
                    rung, requested = bear_ladder.proposal(session, qualified)
                    values = positions()
                    total = assets.sum(axis=1) + reserve
                    cap = np.where(drawdown <= -.30, .70, .60)
                    # If all eligible bonds have negative trailing TWD total
                    # returns, the accepted inflationary-bear rule halves this
                    # tranche; it does not silently consume the unfilled half.
                    weak_bonds = np.max(np.where(eligible, trailing, -np.inf), axis=1) < 0
                    requested *= np.where(weak_bonds, .5, 1.)
                    locked = car_earmark + ledger.stock_reserve + ledger.bond_reserve + ledger.pending_stock + ledger.pending_bond + ledger.earmark()
                    free = np.maximum(assets[:, 2] - living_floor - locked, 0)
                    cash_used = np.minimum(free, requested)
                    bear_sale_tax = (0. if start <= dt.date(2026,12,31) else .001) if deduct_costs else 0.
                    sale_fee = (.001425 + bear_sale_tax) if deduct_costs else 0.
                    buy_fee = .001425 if deduct_costs else 0.
                    bond_room = np.maximum(assets[:, 1] - .10 * total, 0)
                    sale = np.minimum(np.maximum(requested - cash_used, 0),
                        np.minimum(bear_ladder.bond_allowance(), bond_room))
                    sale = np.minimum(sale, np.where(eligible, values, 0).sum(axis=1))
                    principal = (cash_used + sale * (1 - sale_fee)) / (1 + buy_fee)
                    costs = sale * sale_fee + principal * buy_fee
                    allocations = bear_purchase_values(values, total - costs, principal,
                        equity_value=assets[:, 0], equity_cap=cap, target_weights=ledger.weights,
                        high_group=high_group, global_group=global_group,
                        voo_index=ledger.codes.index("VOO"), tw50_index=ledger.codes.index("0050"),
                        tw50_cap=upper[ledger.codes.index("0050")],pair_ratio=(1,1) if tsmc_waiver_active else (4,1))
                    proposed=voo_bridge_proxy.cap_direct_voo_purchase(ledger,values,values+allocations,total-costs,deduct_costs,applicable_cap=.42)
                    allocations=np.maximum(proposed-values,0)
                    bought = allocations.sum(axis=1)
                    scale = np.divide(bought, principal, out=np.zeros(n), where=principal > 0)
                    sale *= scale
                    cash_used *= scale
                    costs *= scale
                    accepted = bear_ladder.growing_fill(sale+cash_used)
                    sale *= accepted; cash_used *= accepted; costs *= accepted
                    allocations *= accepted[:, None]; bought *= accepted
                    sold_values = ranked_bond_sale(values, sale, trailing, durations, eligible)
                    values -= sold_values
                    values += allocations
                    assets[:, 2] -= cash_used
                    if deduct_costs:
                        assets[:, 2] += tax_budget.cover(sale*bear_sale_tax, start.year)
                    apply_positions(values)
                    executed = bought > 1e-8
                    bear_ladder.record(rung, sale + cash_used, sale, session)
                    bear_buys += executed.astype(np.int16)
                    bear_turnover += sale + cash_used
                    tactical_cost += costs
                    all_trade_cost += costs
                    all_turnover += sale + bought
            if enable_bull_timing:
                total = assets.sum(axis=1)+reserve
                # D-156: a gain rung only arms a tranche. The proposal remains
                # zero at a fresh high and releases the next growing tranche
                # only after its high-water pullback rung and cooldown are met.
                qualified = opportunity & ~failed
                rung, requested = bull_ladder.proposal(retired_months, qualified)
                sale = np.minimum(requested, np.maximum(assets[:,0]-bull_ladder.equity_floor*total,0))
                # The monthly proxy uses at most 20 local one-lot purchases,
                # with the two-session settlement inside that month. It does
                # not simulate the D-170 bonus lots; the impact is unmeasured.
                local = [ledger.codes.index(c) for c in ("00865B","00719B")]
                monthly_capacity = np.minimum(*(ledger.nominal_prices[:,j] for j in local)) * 1000*20/price_level
                monthly_capacity = np.maximum(monthly_capacity-bond_capacity_used,0)
                values = positions()
                equity_mask = (ledger.bucket==0)&np.array([c not in voo_bridge_proxy.ALIASES and not(tsmc_waiver_active and c=="2330") and not(age>=70 and c=="VT") for c in ledger.codes])
                sellable = np.maximum(values[:, equity_mask] - total[:, None] * ledger.weights[equity_mask], 0)
                sale = np.minimum(sale, sellable.sum(axis=1))
                sale, cash_fill, bond_purchase, fees, bull_taxes = bull_proceeds(
                    sale, sellable,
                    transaction_tax_rates(ledger.codes,ledger.us,start)[equity_mask],
                    assets[:,2], m.THREE_YEAR_LIVING_RESERVE_2026_TWD,
                    values[:,local], ledger.weights[local], total,
                    monthly_capacity, ledger.fx, costs=deduct_costs, return_allocations=True)
                accepted = bull_ladder.growing_fill(sale)
                sale *= accepted; cash_fill *= accepted; bond_purchase *= accepted[:, None]
                fees *= accepted; bull_taxes *= accepted
                eq = sellable.sum(axis=1)
                ratio = np.divide(sale,eq,out=np.zeros(n),where=eq>0)
                values[:,equity_mask] -= sellable * ratio[:,None]
                cost = fees+bull_taxes
                # D-156 routes actual proceeds, rather than buying bonds and
                # then selling another asset to manufacture the same cash.
                # Refill the three-year living reserve first; only the residue
                # follows each eligible bond's normalized target gap (D-185).
                values[:,local] += bond_purchase
                apply_positions(values)
                assets[:,2] += cash_fill
                executed = sale>1e-8
                bull_ladder.record(rung, sale, retired_months)
                signals.sold(executed)
                bull_sales += executed.astype(np.int16); bull_turnover += sale
                if deduct_costs:
                    assets[:, 2] += tax_budget.cover(bull_taxes, start.year)
                tactical_cost += cost; all_trade_cost += cost; all_turnover += sale+bond_purchase.sum(axis=1)
                wrong_events[slot] = (executed.copy(), equity_net_index.copy(), bond_net_index.copy())
                bull_ladder.close_window(bull_ladder.pending_close)
            if not ledger_active:
                # D-152: a qualified bull restores the full three-year TWD
                # living reserve. A bear may use two years for low buys, but
                # the hard one-year floor remains intact in every branch. The
                # permanent term deposit is never a source.
                runway_target = np.where(
                    bull_signal,
                    m.THREE_YEAR_LIVING_RESERVE_2026_TWD,
                    living_floor,
                )
                raise_cash(runway_target)
            if retired:
                total = assets.sum(axis=1)+reserve
                eqw = assets[:,0]/np.maximum(total,1)
                bw = assets[:,1]/np.maximum(total,1)
                weight_sum += eqw; weight_min = np.minimum(weight_min,eqw); weight_max = np.maximum(weight_max,eqw)
                bond_min = np.minimum(bond_min,bw); bond_max = np.maximum(bond_max,bw)
                month_observations += 1
        if p["month_end"]:
            month_fraction = 0.
        if end in (dt.date(2028,1,1), dt.date(2028,7,1)):
            ledger.record((end-dt.timedelta(days=1)).isoformat(), assets, reserve)
        if retired:
            benchmark_1 = np.maximum(0, benchmark_1*(1.01**fraction) + cashflow + lump)
            benchmark_2 = np.maximum(0, benchmark_2*(1.02**fraction) + cashflow + lump)
        mark_failure(index)
        if snapshots is not None:
            snapshots[index+1] = assets.sum(axis=1) + reserve
        dates.append(end)
    # Terminal wealth is net of accrued unpaid income tax; no age-based ETF sale.
    terminal_tax = accrue_terminal_tax(assets, pending_tax, price_level, periods[-1]["end"], tax_budget)
    mark_failure(len(periods)-1, terminal=True)
    if snapshots is not None:
        snapshots[-1] = assets.sum(axis=1) + reserve
    incomplete_count = sum(int(e[0].sum()) for e in wrong_events if e is not None)
    final = assets.sum(axis=1) + reserve
    survive = ~failed
    success = float(survive.mean())
    se = math.sqrt(success*(1-success)/n)
    ages = np.array([(p["start"]-m.BIRTH).days/365.25 for p in periods])
    failure_ages = ages[failure_period[failed]]
    pool = survive if survive.any() else np.ones(n,dtype=bool)
    realized = weight_sum/max(month_observations,1)
    total_years = (periods[-1]["end"]-retire_date).days/365.25
    tactical = {"bull_enabled": bool(enable_bull_timing), "bear_enabled": bool(enable_bear_timing), "wait_months": tactical_wait_months,
        "bull_paths_with_sale_pct": float(np.mean(bull_sales>0)*100), "bear_paths_with_purchase_pct": float(np.mean(bear_buys>0)*100),
        "bull_events": int(bull_sales.sum()), "bear_events": int(bear_buys.sum()), "bull_events_per_path_year": float(bull_sales.mean()/total_years),
        "bear_events_per_path_year": float(bear_buys.mean()/total_years), "turnover_median_twd": float(np.median((bull_turnover+bear_turnover)*2)),
        "costs_median_twd": float(np.median(tactical_cost)), "wrong_sale_events": wrong_count, "wrong_sale_observed_events": observed_count,
        "wrong_sale_incomplete_events": incomplete_count, "wrong_sale_rate": wrong_count/observed_count if observed_count else None,
        "bull_window_locks_one_release_budget": True, "below_40_paths_pct": float(np.mean(weight_min < .40)*100)}
    return {"success":success,"success_se":se,"success_ci95":[max(0,success-1.959964*se),min(1,success+1.959964*se)],
        "failure_age_median":float(np.median(failure_ages)) if len(failure_ages) else float("nan"), "failure_ages":failure_ages,
        "period_start_ages":ages,"failure_period":failure_period,"_survived":survive,
        "A":float(np.mean(survive&(final>20_000_000))), "B":float(np.mean(survive&(final>10_000_000)&(final<=20_000_000))),
        "C":float(np.mean(survive&(final<=10_000_000))),"Failure":1-success,
        "median_final":float(np.median(final)),"p10_final":float(np.percentile(final,10)),"p90_final":float(np.percentile(final,90)),
        "retirement_start_median":float(np.nanmedian(retirement_start_value)),"retirement_start_p10":float(np.nanpercentile(retirement_start_value,10)),"retirement_start_p90":float(np.nanpercentile(retirement_start_value,90)),
        "retirement_start_weights_median":np.nanmedian(retirement_start_weights,axis=0).tolist(),"transition_probability":probability,
        "transition_execution":ledger.diagnostics(),"tactical":tactical,"tax_settlements":tax_audit,
        "terminal_tax_accrual": terminal_tax,
        "retirement_date": str(retire_date), "pre_retirement_payroll": scenario_payroll,
        "tax_budget": tax_budget.audit(), "pension_indexation": pension_index.audit,
        "bear_batch_audit": {"maximum_first_batch_share": float(bear_ladder.maximum_first_batch_share), "maximum_window_fills": int(bear_ladder.maximum_window_fills)},
        "bull_batch_audit": {"maximum_first_batch_share": float(bull_ladder.maximum_first_batch_share), "maximum_window_fills": int(bull_ladder.maximum_window_fills)},
        "salary_tax_basis_by_year": salary_by_year, "labor_retirement_receipt": labor_receipt_audit,
        "opening_year_tax_basis": "full-year projected dated payroll; pre-snapshot investment income at current verified run rate, for tax only; replace with actual annual tax statements",
        "major_expense_events": m.STATIC_MODEL_INPUTS["major_expense_events"]["events"],
        "bear_market_policy":{"base_status":"SENSITIVITY_ONLY" if enable_bear_timing else "DISABLED_BASE", "paths_with_at_least_one_bond_deployment_pct":tactical["bear_paths_with_purchase_pct"],"bond_deployment_limit":"at most two-thirds of eligible bond ETFs while retaining 10% total bonds","purchase_order":f"fill four-fund high-dividend deficits to {ledger.weights[high_group].sum()*100:g}%, then route the remainder VOO:0050 at {'1:1' if tsmc_waiver_active else '4:1'}; capacity-bound residue stays cash","deep_bear_equity_cap":.70},
        "bull_high_policy":{"base_status":"SENSITIVITY_ONLY" if enable_bull_timing else "DISABLED_BASE", "paths_with_at_least_one_sale_pct":tactical["bull_paths_with_sale_pct"],"ath_sell_delay_months":tactical_wait_months,"equity_target_after_signal":float(target[0]),"batches":"D-156 five growing tranches 8/12/20/25/35% of one locked release budget; gains of 15/20/25/30/35% arm the rungs and pullbacks of 3/4/5/6/8% from a ratcheted high-water mark release them"},
        "dynamic_allocation_diagnostics":{"paths_equity_ever_above_60_pct":float(np.mean(weight_max[pool]>.60)*100),"paths_equity_ever_below_40_pct":float(np.mean(weight_min[pool]<.40)*100),"minimum_bond_weight_p1":float(np.percentile(bond_min[pool],1)),"maximum_bond_weight_p99":float(np.percentile(bond_max[pool],99))},
        "realized_equity_weight_post":float(np.mean(realized[pool])),"realized_equity_weight_post_median":float(np.median(realized[pool])),
        "labor_retirement_route_counts":labor_routes,"first_pension":first_pension,"annual_cash_income_projection":annual_rows,
        "all_trade_costs_median_twd":float(np.median(all_trade_cost)),"all_turnover_median_twd":float(np.median(all_turnover)),
        "ordinary_execution":{"quarterly_review_dates":"Jan/Apr/Jul/Oct 15", "modeled_execution_dates":ordinary_dates,
            "events":int(ordinary_events.sum()), "max_synthetic_buy_days_after_review":10,
            "fx_gate_violations":ordinary_fx_violations,"bond_capacity_violations":ordinary_capacity_violations,
            "unfilled_target_is_retained":True,"future_qualification_is_assumption":True},
        "dates":dates,"percentiles":np.percentile(snapshots,[5,10,25,50,75,90,95],axis=1).T if snapshots is not None else None,
        "emergency_reserve_proxy_min":float(reserve_min.min()),
        "emergency_reserve_proxy_max":float(reserve_max.max()),
        "emergency_reserve_proxy_rollover":"D193_AGGREGATE_TWD_TERM_RATE_PROXY;00865B_SPECIFIC_RETURN_AND_EMERGENCY_WITHDRAWALS_NOT_MODELED",
        "emergency_reserve_model_scope": {
            "aggregate_real_reserve_proxy_twd": float(reserve_amount),
            "reserve_etf_code": "00865B",
            "instrument_split_modeled": False,
            "annual_00865B_market_value_gap_review_modeled": False,
            "emergency_events_and_six_month_replenishment_modeled": False,
            "ordinary_living_withdrawal_from_reserve": False,
        },
        "spending_policy": {
            "age_tiers_annual_real_twd": {
                "retirement_to_75": float(pre_pension_spend),
                "age_75_to_80": float(age_75_to_80_spend),
                "age_80_to_terminal": float(care_spend),
            },
            "stage_age_boundaries": {"age_75_start": stage_age_75, "age_80_start": stage_age_80},
            "market_adjustment_annualized_real_twd": float(market_spend_adjustment_twd),
            "bear_definition": f"MonthlySignals: at least {100*cfg['monthly_signal_proxy']['bear_drawdown_initial']:g}% drawdown and falling 10-month trend; applied from the next modeled period.",
            "strong_bull_definition": f"MonthlySignals D-156 proxy: rising 10-month trend, at least 15% above the locked anchor, after 6 months; applied from the next modeled period.",
            "application": "Prorated by modeled period duration while each path is in the confirmed monthly regime; no future-month look-ahead.",
            "base_allocation_bull_bear_timing_enabled": False,
        },
        "grid":"calendar-months-with-dated-cash-events"}
