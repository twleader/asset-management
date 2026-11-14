"""Conditional per-security execution, independent of report/order generation.

Future qualification is an explicit scenario assumption, not fabricated radar
evidence. Twenty synthetic sessions per month preserve five-session cooldown,
T+2 settlement and a conservative one-lot/day, five-lot/week bond cadence
proxy; future holidays and the D-195/D-196 live price selection are not predicted.
"""
from __future__ import annotations

import datetime as dt
import numpy as np
import voo_bridge_proxy
from execution_policy import GlobalEquityCadence, sell_shares


class TransitionLedger:
    def __init__(self, payload, inputs, paths, seed, prices=None, initial_scale=1.0):
        self.inputs = inputs
        self.n = paths
        self.rng = np.random.default_rng(np.random.SeedSequence([seed, 139, 2]))
        self.config = inputs["simulation_contract"]
        self.target = inputs["detailed_target_allocation_2027"]
        # 24B2 is the Schroder global income bond fund. It is held in the bond
        # bucket for valuation and redemption ordering, but it is NOT one of the
        # 56.53/26.47/17 target-table securities (D-143 §2 lists only 00865B, 00719B,
        # SGOV and 00697B with bond targets), so it carries no target weight.
        self.bond_codes = {"00865B", "00719B", "00697B", "SGOV", "00679B", "00751B", "24B2"}
        holdings = {}
        for row in payload["liveAssets"]["stocks"]:
            code = str(row["stockCode"])
            item = holdings.setdefault(code, {"shares": 0., "value": 0., "price": float(row["currentPrice"]), "market": row["market"]})
            item["shares"] += float(row["shares"])
            item["value"] += float(row["liveValue"])
        for row in payload["snapshot"].get("funds", []):
            holdings[str(row["fundCode"])] = {"shares": float(row["units"]), "value": float(row["currentValue"]), "price": float(row["currentValue"]) / float(row["units"]), "market": "fund"}
        wanted = {k for k, v in self.target.items() if v > 0 and k not in {"TWD_term_deposits", "demand_deposits_and_cash"}}
        for code in wanted - set(holdings):
            quote = (prices or {}).get(code)
            if not quote or float(quote.get("price") or 0) <= 0:
                raise ValueError(f"missing same-run reference price for transition target {code}")
            holdings[code] = {"shares": 0., "value": 0., "price": float(quote["price"]), "market": quote["market"]}
        self.bridge_enabled=bool(inputs.get("runtime_enable_voo_bridge",False))
        self.voo_fx_wait_proxy=bool(inputs.get("runtime_voo_fx_wait_proxy",False))
        if self.bridge_enabled:
            for alias,base in voo_bridge_proxy.ALIASES.items():
                holdings[alias]={**holdings[base],"shares":0.,"value":0.}
        self.codes = sorted(holdings)
        self.rows = [holdings[c] for c in self.codes]
        self.bucket = np.array([int(c in self.bond_codes) for c in self.codes])
        self.weights = np.array([float(self.target.get(c, 0)) for c in self.codes])
        self.core_caps = inputs["execution_policy"]["bull_correction_dip_d212"]["tsmc_exposure_waiver"]["effects_when_inactive"]["caps_pct"]
        self.high_policy = inputs["execution_policy"]["high_dividend_execution_policy"]
        self.high_group = np.array([c in self.high_policy["symbols"] for c in self.codes])
        self.high_cap = float(self.high_policy["aggregate_hard_cap"])
        self.high_upper = np.array([
            float(self.high_policy["individual_upper_bounds"].get(c, 0))
            * self.weights[j] / float(self.high_policy["individual_targets"].get(c, 1))
            for j, c in enumerate(self.codes)
        ])
        self.high_dividend_buy_cap_violations = 0
        self.unit = np.array([r["price"] for r in self.rows])
        self.us = np.array([r["market"] == "美股" for r in self.rows])
        exposure = inputs.get("runtime_usd_exposure_by_security", {})
        self.usd_exposure = np.array([float(exposure.get(c, self.us[j] or c in {"009826", "00865B", "00719B", "00697B", "00751B", "00679B"}))
                                      for j, c in enumerate(self.codes)])
        initial_price_level = float(inputs.get("runtime_initial_price_level", 1.))
        initial_units = np.array([r["shares"] for r in self.rows]) * initial_scale
        initial_values = np.array([r["value"] for r in self.rows]) * initial_scale / initial_price_level
        self.units = np.broadcast_to(initial_units, (paths, len(self.codes))).copy()
        self.values = np.broadcast_to(initial_values, self.units.shape).copy()
        self.nominal_prices = np.broadcast_to(self.unit, self.units.shape).copy()
        self.last_trade = np.full(self.units.shape, -100, dtype=np.int32)
        self.tranche_count = np.zeros(self.units.shape, dtype=np.int16)
        self.buy_count = np.zeros(self.units.shape, dtype=np.int16)
        total = float(payload["liveAssets"]["liveTotalAssets"]) * initial_scale / initial_price_level
        desired_units = np.divide(total * self.weights, np.maximum(initial_values / np.maximum(initial_units, 1e-30), 1e-30))
        self.baseline_excess = np.maximum(initial_units - desired_units, 0)
        self.baseline_excess[self.weights == 0] = initial_units[self.weights == 0]
        self.initial_values = initial_values
        self.initial_units = initial_units
        self.stock_reserve = np.zeros(paths)
        self.stock_reserve_lots = []
        self.pending_sales = []
        self.rotation_bear_paused = np.zeros(paths, dtype=int)
        self.rotation_completed = np.zeros(paths, dtype=bool)
        self.forced_voo_deployed = np.zeros(paths)
        self.forced_voo_blocked = np.zeros(paths, dtype=int)
        self.bond_reserve = np.zeros(paths)
        opening_receivable = sum(max(float(r.get("amount") or 0), 0) for r in payload.get("snapshot", {}).get("deposits", []) if r.get("currency") == "TRANSIT_TWD")
        self.opening_receivable = np.full(paths, opening_receivable * initial_scale / initial_price_level)
        self.pending_stock = self.opening_receivable.copy()
        self.pending_bond = np.zeros(paths)
        self.tax_cover = None
        self.costs = np.zeros(paths)
        self.turnover = np.zeros(paths)
        self.session = 0
        self.extended = np.ones(self.units.shape, dtype=bool)
        self.checkpoints = {}
        self.max_bond_daily_lots = 0
        self.max_bond_weekly_lots = 0
        self.unsettled_spend_violations = 0
        self.minimum_settlement_wait_sessions = 2
        self.retirement_date = dt.date.fromisoformat(inputs.get("runtime_retirement_date", inputs["retirement_start_date"]))
        self.funding_limit_violations = 0
        self.audit_rows = []
        self.cash_earmarked_global_equity = float(inputs.get("runtime_global_equity_earmarked_twd", 0))
        self.dca_spent = np.zeros(paths)
        self.dca_spent_usd = np.zeros(paths)
        self.initial_earmark_usd = self.cash_earmarked_global_equity / float(inputs.get("runtime_usd_twd", 31.0))
        self.voo_dca_schedule = tuple(
            inputs["execution_policy_overrides"]["global_equity_build_cadence_d141"]["VOO"]["scheduled_dca"]
        )
        self.vt_dca_schedule = tuple(
            i for i in inputs["execution_policy_overrides"]["global_equity_usd_dca_d182"]["scheduled_instructions"]
            if i["code"] == "VT"
        )
        self.cadence = GlobalEquityCadence(
            inputs["execution_policy_overrides"]["global_equity_build_cadence_d141"], paths)
        self.price_level = np.full(paths, initial_price_level)
        self.fx = np.full(paths, float(inputs.get("runtime_usd_twd", 31.0)))
        self.execution_fx_ratio=float(inputs.get("runtime_execution_fx_mid",self.fx[0]))/self.fx[0]
        self.cash_yield = np.array([float(inputs.get("runtime_security_rates", {}).get(c, {}).get("regular_cash_yield", 0)) for c in self.codes])
        self.tw_dca_stopped = {c: np.zeros(paths, dtype=bool) for c in ("00878", "0056", "00713")}
        self.bond_buy_stopped = {c: np.zeros(paths, dtype=bool) for c in ("00865B", "00719B", "00697B")}
        self.fx_bond_gate_violations = 0
        self.intermediate_cap_violations = 0
        history = inputs.get("runtime_trailing_twd_returns", {})
        previous = np.array([float(history.get(c, np.nan)) for c in self.codes])
        self.return_history = np.broadcast_to(np.power(1 + previous, 1/12), (12, paths, len(self.codes))).copy()
        self.return_month_growth = np.ones(self.units.shape)
        self.return_month_index = 0
        voo_bridge_proxy.initialize(self,inputs,seed)

    def trailing_twd_returns(self):
        return np.prod(self.return_history, axis=0) - 1

    def record_month_returns(self):
        self.return_history[self.return_month_index % 12] = self.return_month_growth
        self.return_month_growth.fill(1.)
        self.return_month_index += 1

    def execution_fx(self):
        return self.fx * self.execution_fx_ratio

    def usd_dca_earmark(self):
        return np.maximum(self.initial_earmark_usd - self.dca_spent_usd, 0) * self.fx / self.price_level

    def earmark(self):
        return self.usd_dca_earmark() + self.bridge_cash + self.bridge_pending

    def high_dividend_purchase_room(self, column, total, commission):
        """Four-fund and individual capacity after the purchase fee reduces NAV."""
        group_value = self.values[:, self.high_group].sum(axis=1)
        group_room = np.maximum((self.high_cap * total - group_value) / (1 + self.high_cap * commission), 0)
        target = self.weights[column]
        individual_room = np.maximum((target * total - self.values[:, column]) / (1 + target * commission), 0)
        return np.minimum(group_room, individual_room)

    def equity_purchase_room(self, column, assets, reserve, commission):
        total = assets.sum(axis=1) + reserve
        target = self.weights[column]
        bridge = self.values[:, [self.codes.index(c) for c in voo_bridge_proxy.ALIASES]].sum(axis=1) if self.bridge_enabled and self.codes[column]=="VOO" else 0
        room = np.maximum((target * total - self.values[:, column] - bridge - (self.bridge_cash+self.bridge_pending if self.bridge_enabled and self.codes[column]=="VOO" else 0)) / (1 + target * commission), 0)
        room = np.minimum(room, np.maximum((.60 * total - assets[:, 0]) / (1 + .60 * commission), 0))
        if self.codes[column] in {"VOO", "009826", "VT"}:
            global_mask = np.array([c in {"VOO", "009826", "VT"} for c in self.codes])
            cap = float(self.inputs["execution_policy_overrides"]["global_equity_wrapper_policy_d141"]["global_equity_group_cap"])
            room = np.minimum(room, np.maximum((cap * total - self.values[:, global_mask].sum(axis=1)) / (1 + cap * commission), 0))
        if self.high_group[column]:
            room = np.minimum(room, self.high_dividend_purchase_room(column, total, commission))
        return room

    def rotation_floor(self, date):
        cfg = self.inputs["execution_policy_overrides"]["rotation_equity_floor_d141"]
        active = np.full(self.n, date <= dt.date(2028, 6, 30))
        if date >= dt.date(2027, 7, 1):
            active &= ~self.rotation_completed
        return np.where(active, cfg["pre_retirement_rotation_equity_floor"], cfg["ordinary_rebalancing_band"][0])

    def add_stock_reserve(self, amount, *, settled_session=None):
        amount = np.broadcast_to(amount, (self.n,)).astype(float).copy()
        self.stock_reserve += amount
        self.stock_reserve_lots.append({"amount": amount, "age": np.zeros(self.n, dtype=int),
                                       "settled_session": self.session if settled_session is None else settled_session})

    def consume_stock_reserve(self, payment):
        remaining = np.minimum(payment, self.stock_reserve).copy()
        if not np.any(remaining > 1e-8):
            return
        self.stock_reserve -= remaining
        retained = []
        for index, lot in enumerate(self.stock_reserve_lots):
            used = np.minimum(lot["amount"], remaining)
            lot["amount"] -= used
            remaining -= used
            if np.any(lot["amount"] > 1e-8):
                retained.append(lot)
            if not np.any(remaining > 1e-8):
                retained.extend(self.stock_reserve_lots[index+1:])
                break
        self.stock_reserve_lots = retained

    def settle_proceeds(self):
        if self.session >= 2 and np.any(self.opening_receivable):
            self.pending_stock -= self.opening_receivable
            self.opening_receivable.fill(0.)
        unsettled = []
        for lot in self.pending_sales:
            if self.session < lot["settle_at"]:
                unsettled.append(lot)
            elif lot["bucket"] == 0:
                self.pending_stock -= lot["amount"]
                self.add_stock_reserve(lot["amount"], settled_session=lot["settle_at"])
            else:
                self.pending_bond -= lot["amount"]
                self.bond_reserve += lot["amount"]
        self.pending_sales = unsettled

    def reserve_day(self, bear_reserved):
        paused = np.zeros(self.n, dtype=bool) if bear_reserved is None else np.asarray(bear_reserved)
        self.rotation_bear_paused += paused
        for lot in self.stock_reserve_lots:
            # Age counts completed eligible TW sessions after settlement.
            if self.session > lot["settled_session"]:
                lot["age"] += ~paused
        return paused

    def deploy_overdue_reserve(self, assets, reserve, required_cash, commission, paused,
                               evidence_ok=None):
        limit = self.inputs["execution_policy_overrides"]["rotation_equity_floor_d141"]["stock_rotation_reserve_max_idle_sessions"]
        overdue = sum((np.where(lot["age"] > limit, lot["amount"], 0)
                       for lot in self.stock_reserve_lots), np.zeros(self.n))
        j = self.codes.index("VOO")
        physical = np.maximum(assets[:, 2] - required_cash - self.bond_reserve
                              - self.pending_stock - self.pending_bond - self.earmark(), 0)
        value = np.minimum(np.minimum(overdue, physical) / (1 + commission),
                           self.equity_purchase_room(j, assets, reserve, commission))
        value = np.minimum(value, self.cadence.room("VOO") / self.price_level)
        allowed = ~paused & (np.zeros(self.n, dtype=bool) if evidence_ok is None else np.asarray(evidence_ok, dtype=bool))
        if self.voo_fx_wait_proxy:
            allowed &= voo_bridge_proxy.voo_buy_allowed(self)
        value = np.where(allowed, value, 0)
        self.forced_voo_blocked += (overdue > 1e-8) & (value <= 1e-8)
        if not np.any(value > 1e-8):
            return
        unit_value = self.nominal_prices[:, j] * self.fx / self.price_level
        value = np.where(unit_value > 0, value, 0)
        payment = value * (1 + commission)
        self.consume_stock_reserve(payment)
        assets[:, 2] -= payment
        assets[:, 0] += value
        self.values[:, j] += value
        self.units[:, j] += value / np.maximum(unit_value, 1e-10)
        self.costs += value * commission
        self.turnover += value
        self.forced_voo_deployed += value
        self.buy_count[:, j] += value > 1e-8
        # The deadline waives weekly timing/cooldown, but shares the monthly
        # money cap and updates the latest confirmed price for ordinary buys.
        self.cadence.fill("VOO", value * self.price_level, price=self.nominal_prices[:, j], scheduled=True)

    def advance_idle_month(self, assets, reserve, required_cash, commission, bear_reserved=None, probability=0.):
        """Equivalent monthly batch once all remaining proceeds are overdue.

        There are no intervening prices, receipts or qualification changes in
        these synthetic sessions. The first fill already exhausts either the
        overdue money, cash, monthly amount or portfolio capacity. Retain every
        original cohort's age; only eliminate nineteen identical retry loops.
        """
        if not 0 <= probability <= 1:
            raise ValueError("qualification probability must be in [0,1]")
        for day in range(20):
            if day % 5 == 0:
                evidence_ok = self.rng.random(self.n) < probability
            self.settle_proceeds()
            paused = self.reserve_day(bear_reserved)
            self.deploy_overdue_reserve(assets, reserve, required_cash, commission, paused,
                                       evidence_ok=evidence_ok)
            self.session += 1
    def audit_high_dividend_purchase(self, value, assets, reserve):
        total = assets.sum(axis=1) + reserve
        group_value = self.values[:, self.high_group].sum(axis=1)
        self.high_dividend_buy_cap_violations += int(np.sum((value > 1e-8) & (group_value > self.high_cap * total + 1e-6)))

    def sync(self, assets, price_index=1.0):
        """Ordinary bucket withdrawals remain proportional; no age-based VOO sale."""
        for bucket in (0, 1):
            mask = self.bucket == bucket
            old = self.values[:, mask].sum(axis=1)
            ratio = np.divide(assets[:, bucket], old, out=np.ones(self.n), where=old > 0)
            self.values[:, mask] *= ratio[:, None]
            self.units[:, mask] *= ratio[:, None]

    def mark(self, returns, fraction, price_level, fx_return, assets, bear=None, security_returns=None):
        # Separate cash payouts from price returns without adding a second
        # total-return inflow. Price is a simulated proxy, never a future quote.
        old = self.values.copy()
        reductions = np.ones_like(old)
        if bear is not None:
            for j, code in enumerate(self.codes):
                reductions[:, j] = np.where(bear, .7 if code in {"0056", "00713", "00878", "00919", "00882"} else .8 if self.bucket[j] == 0 else 1., 1.)
        per_security = returns[:, self.bucket] if security_returns is None else security_returns
        payout = np.minimum(old * self.cash_yield * fraction * reductions, old * np.maximum(1 + per_security, .05))
        self.values = np.maximum(old * np.maximum(1 + per_security, .05) - payout, 0)
        for b in (0, 1):
            amount = payout[:, self.bucket == b].sum(axis=1)
            assets[:, b] -= amount
            assets[:, 2] += amount
        growth = np.divide(self.values, old, out=np.ones_like(old), where=old > 0)
        inflation = np.asarray(price_level) / self.price_level
        self.return_month_growth *= (1 + per_security) * inflation[:, None]
        self.nominal_prices *= growth * inflation[:, None]
        self.nominal_prices[:, self.us] /= np.maximum(1 + fx_return[:, None], .05)
        self.fx *= np.maximum(1 + fx_return, .05)
        self.price_level = np.asarray(price_level).copy()
        for tagged in (self.stock_reserve, self.bond_reserve, self.pending_stock, self.pending_bond, self.opening_receivable, self.bridge_cash, self.bridge_pending):
            tagged *= np.maximum(1 + returns[:, 2], .05)
        for lot in self.stock_reserve_lots + self.pending_sales:
            lot["amount"] *= np.maximum(1 + returns[:, 2], .05)
        # Unheld target prices also follow the same explicitly disclosed factor.
        for j in range(len(self.codes)):
            empty = old[:, j] == 0
            self.nominal_prices[empty, j] *= np.maximum(1 + per_security[empty, j] - self.cash_yield[j] * fraction, .05)
        return payout

    def _usd_dca_leg(self, column, nominal, assets, reserve, commission, *, use_cadence):
        """One USD-funded recurring buy (D-182). All-bank USD deposits fund it,

        independent of the TWD permanent/living-deposit floors. Capped by the
        ordinary per-security/global-equity-group purchase room; the VOO legs
        are additionally capped by the D-141 weekly/monthly build cadence,
        while the VT leg (a small satellite maintenance buy) is not.
        """
        code = self.codes[column]
        unit_value = self.nominal_prices[:, column] / self.price_level * self.fx
        full_payment = nominal * (1 + commission) * self.fx / self.price_level
        funded = self.usd_dca_earmark() + 1e-8 >= full_payment
        budget = np.where(funded, full_payment, 0)
        room = self.equity_purchase_room(column, assets, reserve, commission)
        qualified = full_payment / (1 + commission) <= room + 1e-8
        if use_cadence:
            nominal_value = nominal * self.fx
            qualified &= nominal_value <= self.cadence.voo_budget(self.nominal_prices[:, column], scheduled=True) + 1e-8
        budget = np.where(qualified, budget, 0)
        quantity = budget / (1 + commission) / np.maximum(unit_value, 1e-10)
        value = quantity * unit_value
        payment = value * (1 + commission)
        assets[:, 2] -= payment
        assets[:, 0] += value
        self.units[:, column] += quantity
        self.values[:, column] += value
        self.costs += value * commission
        self.turnover += value
        self.buy_count[:, column] += (value > 0).astype(np.int16)
        self.dca_spent += payment
        self.dca_spent_usd += payment * self.price_level / self.fx
        if use_cadence:
            self.cadence.fill(code, value * self.price_level, price=self.nominal_prices[:, column], scheduled=True)

    def scheduled_dca(self, assets, reserve, required_cash, deduct_costs, date):
        """Apply D-182's three USD legs (VT day 5, VOO days 15/25) and the retained Taiwan DCA.

        The simulation pools all own settled USD deposits. ``reserve`` represents
        the permanent TWD term deposit, while ``required_cash`` is the hard
        TWD 750K living floor (both only relevant to the Taiwan legs below). It
        never models an automatic redemption, transfer, conversion, or broker
        instruction change.
        """
        commission = .001425 if deduct_costs else 0.
        voo_column = self.codes.index("VOO")
        vt_column = self.codes.index("VT")
        self.cadence.begin(date, self.execution_fx())
        for instruction in self.voo_dca_schedule:
            if date.day != int(instruction["day"]):
                continue
            self._usd_dca_leg(voo_column, float(instruction["investment_amount_usd"]), assets, reserve, commission, use_cadence=True)
        for instruction in self.vt_dca_schedule:
            if date.day != int(instruction["day"]):
                continue
            self._usd_dca_leg(vt_column, float(instruction["investment_amount_usd"]), assets, reserve, commission, use_cadence=False)

        for code, day, nominal in (("00878", 7, 8500.), ("0056", 17, 5500.), ("00713", 27, 12000.)):
            if date.day != day or date > self.retirement_date:
                continue
            j = self.codes.index(code)
            total = assets.sum(axis=1) + reserve
            locked = self.stock_reserve + self.bond_reserve + self.pending_stock + self.pending_bond
            untagged = np.maximum(assets[:, 2] - required_cash - locked - self.earmark(), 0)
            gap = np.maximum(total * self.weights[j] - self.values[:, j], 0)
            self.tw_dca_stopped[code] |= gap < 1
            budget = np.where(self.tw_dca_stopped[code], 0, np.minimum(nominal / self.price_level, gap * (1 + commission)))
            budget = np.minimum(budget, self.high_dividend_purchase_room(j, total, commission) * (1 + commission))
            budget = np.minimum(budget, untagged)
            unit_value = self.nominal_prices[:, j] / self.price_level
            quantity = np.floor(budget / (1 + commission) / np.maximum(unit_value, 1e-10))
            value = quantity * unit_value
            payment = value * (1 + commission)
            assets[:, 2] -= payment
            assets[:, 0] += value
            self.units[:, j] += quantity
            self.values[:, j] += value
            self.costs += value * commission
            self.turnover += value
            self.buy_count[:, j] += (value > 0).astype(np.int16)
            self.audit_high_dividend_purchase(value, assets, reserve)

    def local_bond_week(self, assets, reserve, qualified_buy, required_cash, date, deduct_costs, bear_reserved, day_count=5):
        """One-lot scenario proxy; D-185 retired the fixed short-bond ratio.

        Normalized target gaps proxy candidate ordering, without live D-195
        prices or the D-196 two-lot/day, six-lot/week execution ceilings.
        """
        columns=[self.codes.index(c) for c in ("00865B","00719B","00697B")]
        commission=.001425 if deduct_costs else 0.
        lots=np.zeros(self.n,dtype=int)
        for day in range(day_count):
            total=assets.sum(axis=1)+reserve
            floor=np.full(self.n, required_cash)
            locked=self.stock_reserve+self.bond_reserve+self.pending_stock+self.pending_bond
            free=np.maximum(assets[:,2]-floor-locked-self.earmark(),0)
            physical=np.maximum(assets[:,2]-floor-self.stock_reserve-self.pending_stock-self.pending_bond-self.earmark(),0)
            available=np.minimum(free+self.bond_reserve,physical)
            scores=[];lot_values=[]
            for j in columns:
                code=self.codes[j]
                lot=1000*self.nominal_prices[:,j]/self.price_level
                gap=np.maximum(total*self.weights[j]-self.values[:,j],0)
                self.bond_buy_stopped[code] |= gap<1.
                gate=qualified_buy[:,j]&~self.bond_buy_stopped[code]&(self.execution_fx()<=33.)&(gap>=lot)&(available>=lot*(1+commission))&(assets.sum(axis=1)>0)
                if date>=dt.date(2028,1,1):gate &= self.extended[:,j]
                if bear_reserved is not None:gate &= ~bear_reserved
                if code=="00697B":
                    gate &= self.values[:,j]+lot<=.30*(assets[:,1]+lot)+1e-8
                scores.append(np.where(gate,gap/np.maximum(total*self.weights[j],1),-1.))
                lot_values.append(lot)
            scores=np.column_stack(scores);choice=np.argmax(scores,axis=1)
            any_eligible=np.max(scores,axis=1)>=0
            for k,j in enumerate(columns):
                hit=any_eligible&(choice==k)
                value=np.where(hit,lot_values[k],0)
                payment=value*(1+commission)
                self.funding_limit_violations+=int(np.sum(payment>available+1e-6))
                self.unsettled_spend_violations+=int(np.sum(payment>physical+1e-6))
                self.bond_reserve-=np.minimum(self.bond_reserve,payment)
                assets[:,2]-=payment;assets[:,1]+=value
                self.values[:,j]+=value;self.units[:,j]+=hit*1000
                self.costs+=value*commission;self.turnover+=value
                self.buy_count[:,j]+=hit.astype(np.int16)
                self.last_trade[hit,j]=self.session+day
                lots+=hit
                self.fx_bond_gate_violations+=int(np.sum(hit&(self.execution_fx()>33.)))
                if self.codes[j]=="00697B":self.intermediate_cap_violations+=int(np.sum(hit&(self.values[:,j]>.30*assets[:,1]+1e-6)))
            self.max_bond_daily_lots=max(self.max_bond_daily_lots,int(any_eligible.any()))
        self.max_bond_weekly_lots=max(self.max_bond_weekly_lots,int(lots.max()))

    def step(self, assets, reserve, date, end_date, probability, required_cash, deduct_costs=True, bear_reserved=None):
        """Twenty synthetic TW sessions, with one qualification draw per week.

        The monthly engine has already applied that month's dated recurring
        debits. They therefore occupy the same month budget before strategic
        opportunities. These are scenario sessions, never forecast holidays.
        """
        if date >= dt.date(2028, 7, 1):
            return
        self.sync(assets)
        commission = .001425 if deduct_costs else 0.
        for day in range(20):
            execution_date = date.replace(day=1) + dt.timedelta(days=day)
            self.cadence.begin(execution_date, self.execution_fx(), week=(date.year, date.month, day // 5),
                               day=(date.year, date.month, day))
            self.settle_proceeds()
            paused = self.reserve_day(bear_reserved)
            if day % 5 == 0:
                ordinary=[j for j,c in enumerate(self.codes) if c not in voo_bridge_proxy.ALIASES]
                uniforms=np.ones((self.n,len(self.codes),2))
                uniforms[:,ordinary,:]=self.rng.random((self.n,len(ordinary),2))
                qualified_buy = uniforms[:, :, 0] < probability
                qualified_sell = uniforms[:, :, 1] < probability
                weekly_bond_before = self.buy_count[:, [self.codes.index(c) for c in self.bond_buy_stopped]].sum(axis=1).copy()
            self.deploy_overdue_reserve(assets, reserve, required_cash, commission, paused,
                evidence_ok=qualified_buy[:, self.codes.index("VOO")])
            self.local_bond_week(assets,reserve,qualified_buy,required_cash,execution_date,deduct_costs,bear_reserved,day_count=1)
            weekly_bond_lots = self.buy_count[:, [self.codes.index(c) for c in self.bond_buy_stopped]].sum(axis=1) - weekly_bond_before
            self.max_bond_weekly_lots = max(self.max_bond_weekly_lots, int(weekly_bond_lots.max()))
            total = assets.sum(axis=1) + reserve
            floor = np.full(self.n, required_cash)
            equity_room = np.maximum(total * .60 - assets[:, 0], 0)
            # Stable priority by normalized opening shortfall. Actual reports
            # sort using observed same-round data, never this simulated order.
            gaps = np.maximum(total[:, None] * self.weights - self.values, 0)
            rel_gap = np.divide(gaps, total[:, None] * self.weights, out=np.zeros_like(gaps), where=self.weights > 0)
            priority = np.argsort(-np.mean(rel_gap, axis=0), kind="stable")
            for j in priority:
                code = self.codes[j]
                bucket = self.bucket[j]
                total = assets.sum(axis=1) + reserve
                if self.weights[j] == 0 or code == "24B2" or code in self.bond_buy_stopped:
                    continue
                gate = qualified_buy[:, j] & (assets.sum(axis=1)>0) & ~paused
                if code not in {"VOO", "VT", "SGOV", "009826"}:
                    gate &= self.session - self.last_trade[:, j] >= 5
                if date >= dt.date(2028, 1, 1):
                    gate &= self.extended[:, j]
                if code == "009826" and execution_date < dt.date(2026, 11, 2):
                    gate[:] = False
                locked = self.stock_reserve + self.bond_reserve + self.pending_stock + self.pending_bond
                earmark = self.earmark()
                free = np.maximum(assets[:, 2] - floor - locked - earmark, 0)
                own_reserve = self.stock_reserve if bucket == 0 else self.bond_reserve
                available = free + own_reserve
                other_reserve = self.bond_reserve if bucket == 0 else self.stock_reserve
                physical = np.maximum(assets[:,2] - floor - other_reserve - self.pending_stock - self.pending_bond - earmark, 0)
                available = np.minimum(available, physical)
                gap = np.maximum(total * self.weights[j] - self.values[:, j], 0)
                purchase = np.minimum(gap, available / (1 + commission))
                if bucket == 0:
                    purchase = np.minimum(purchase, self.equity_purchase_room(j, assets, reserve, commission))
                if self.high_group[j]:
                    purchase = np.minimum(purchase, self.high_dividend_purchase_room(j, total, commission))
                unit_value = self.nominal_prices[:, j] / self.price_level
                if self.us[j]:
                    unit_value *= self.fx
                if code == "VOO":
                    if self.voo_fx_wait_proxy:
                        gate &= voo_bridge_proxy.voo_buy_allowed(self)
                    purchase = np.minimum(purchase, self.cadence.voo_budget(self.nominal_prices[:, j]) / self.price_level)
                if code == "009826":
                    purchase = np.minimum(purchase, self.cadence.room(code) / self.price_level)
                    purchase = np.minimum(purchase, self.cadence.local_lots() * 1000 * unit_value)
                quantity = np.divide(purchase, unit_value, out=np.zeros(self.n), where=unit_value > 0)
                if not self.us[j]:
                    lot_size = 1000 if code == "009826" else 1
                    quantity = np.floor(quantity / lot_size + 1e-10) * lot_size
                quantity = np.where(gate, quantity, 0.)
                value = quantity * unit_value
                payment = value * (1 + commission)
                self.funding_limit_violations += int(np.sum(payment > available + 1e-7))
                self.unsettled_spend_violations += int(np.sum(payment > physical + 1e-7))
                from_tag = np.minimum(own_reserve, payment)
                if bucket == 0:
                    self.consume_stock_reserve(from_tag)
                else:
                    own_reserve -= from_tag
                assets[:, 2] -= payment
                assets[:, bucket] += value
                self.values[:, j] += value
                self.units[:, j] += quantity
                equity_room -= value if bucket == 0 else 0
                self.costs += value * commission
                self.turnover += value
                self.buy_count[:,j] += (value>0).astype(np.int16)
                if code in {"VOO", "009826"}:
                    self.cadence.fill(code, value * self.price_level, price=self.nominal_prices[:, j], shares=quantity)
                if self.high_group[j]:
                    self.audit_high_dividend_purchase(value, assets, reserve)
                hit = value > .01
                self.last_trade[hit, j] = self.session
            for j, code in enumerate(self.codes):
                if code in {"VOO", "VT", "SGOV", "24B2"} or code in voo_bridge_proxy.ALIASES:
                    continue  # no retirement-transition or age-triggered sale
                if code=="2330" and self.inputs.get("runtime_tsmc_waiver_active",True):
                    continue
                bucket = self.bucket[j]
                gate = qualified_sell[:, j] & (self.session - self.last_trade[:, j] >= 5) & (assets.sum(axis=1)>0)
                if date >= dt.date(2028, 1, 1):
                    gate &= self.extended[:, j]
                if code == "009826":
                    buyers=(self.bucket==0)&(self.weights>0)&(np.arange(len(self.codes))!=j)
                    gate &= np.any(qualified_buy[:,buyers] & (gaps[:,buyers]>1000),axis=1)
                if code == "00882":
                    # A conservative synthetic bid after spread must be >16;
                    # the probability also requires depth and radar alignment.
                    gate &= self.nominal_prices[:, j] * .998 > 16.
                total = assets.sum(axis=1) + reserve
                target_value = total * self.weights[j]
                if self.high_group[j]:
                    # Retention is not an instruction to churn around exact
                    # targets. Only genuine individual/group excess qualifies.
                    group_excess = self.values[:, self.high_group].sum(axis=1) > total * self.high_cap + 1e-6
                    individual_excess = self.values[:, j] > total * self.high_upper[j] + 1e-6
                    gate &= group_excess | individual_excess
                if code in self.core_caps:
                    gate &= self.values[:, j] > total * (float(self.core_caps[code]) / 100) + 1e-6
                excess = np.maximum(self.values[:, j] - target_value, 0)
                unit_value = np.maximum(np.divide(self.values[:, j], self.units[:, j], out=np.ones(self.n), where=self.units[:, j] > 1e-8), 1e-8)
                sell_tax = (.003 if not code.startswith("00") else .001) if deduct_costs and not self.us[j] else 0.
                if bucket == 1 and end_date <= dt.date(2026, 12, 31):
                    sell_tax = 0.
                capacity = self.units[:, j].copy()
                if bucket == 0:
                    stock_floor = self.rotation_floor(execution_date)
                    capacity = np.minimum(capacity, np.maximum(assets[:, 0] - total * stock_floor, 0)
                        / (1 - stock_floor * (commission + sell_tax)) / unit_value)
                baseline = self.baseline_excess[j] if self.weights[j] == 0 or code in {"0050", "00881"} else None
                quantity = sell_shares(self.units[:, j], excess / unit_value, capacity,
                    zero_target=self.weights[j] == 0, baseline_excess=baseline,
                    baseline_value_twd=self.initial_values[j] * float(self.inputs.get("runtime_initial_price_level", 1.)),
                    confirmed_batches=self.tranche_count[:, j], lot_size=1 if self.us[j] else 1000)
                quantity = np.where(gate, quantity, 0)
                value = quantity * unit_value
                cost = value * (commission + sell_tax)
                proceeds = value - cost
                assets[:, bucket] -= value
                assets[:, 2] += proceeds
                if self.tax_cover is not None:
                    assets[:, 2] += self.tax_cover(value*sell_tax, execution_date)
                self.values[:, j] -= value
                self.units[:, j] -= quantity
                if bucket == 0:
                    self.pending_stock += proceeds
                else:
                    self.pending_bond += proceeds
                if np.any(proceeds > 1e-8):
                    self.pending_sales.append({"amount": proceeds.copy(), "bucket": bucket,
                                               "settle_at": self.session + self.config["settlement_sessions"]})
                self.costs += cost
                self.turnover += value
                hit = value > .01
                self.last_trade[hit, j] = self.session
                self.tranche_count[hit, j] += 1
            self.session += 1
            if execution_date >= dt.date(2027, 7, 1):
                total = assets.sum(axis=1) + reserve
                managed = np.array([c not in {"VOO", "SGOV", "24B2"} and c not in voo_bridge_proxy.ALIASES for c in self.codes])
                complete = np.abs(self.values - total[:, None] * self.weights) <= total[:, None] * self.config["transition_completion_tolerance_total_weight"]
                complete[:, self.weights == 0] = self.units[:, self.weights == 0] < 1
                self.rotation_completed |= np.all(complete[:, managed], axis=1)
        if date < dt.date(2028, 1, 1) <= end_date:
            total = assets.sum(axis=1) + reserve
            self.extended = (np.abs(self.values - total[:, None] * self.weights) > np.maximum(total[:, None] * .01, 1000))
            self.extended[:, self.weights == 0] = self.units[:, self.weights == 0] >= 1

    def record(self, label, assets, reserve):
        total = assets.sum(axis=1) + reserve
        deviation = np.divide(self.values, total[:, None], out=np.zeros_like(self.values), where=total[:, None] > 0) - self.weights
        # VOO/SGOV and the deposit-replenishment fund have independent rules.
        managed = np.array([c not in {"VOO", "SGOV", "24B2"} and c not in voo_bridge_proxy.ALIASES for c in self.codes])
        complete_by_code = np.abs(deviation) <= self.config["transition_completion_tolerance_total_weight"]
        complete_by_code[:, self.weights == 0] = self.units[:, self.weights == 0] < 1
        complete = np.all(complete_by_code[:, managed], axis=1)
        allocation_complete_by_code = complete_by_code.copy()
        if "24B2" in self.codes:
            allocation_complete_by_code[:, self.codes.index("24B2")] = True  # retained until a verified cash shortfall, not a forced zero-target exit
        all_targets_complete = np.all(allocation_complete_by_code, axis=1)
        tolerance = self.config["transition_completion_tolerance_total_weight"]
        cash_target = self.inputs["recommended_total_allocation"]["cash_like_including_term_deposits_and_reserve"]
        cash_weight = (assets[:, 2] + reserve) / np.maximum(total, 1)
        settled = (self.pending_stock + self.pending_bond + self.stock_reserve + self.bond_reserve) < 1
        fully_allocated = all_targets_complete & (np.abs(cash_weight - cash_target) <= tolerance) & settled
        deployed_by_code=allocation_complete_by_code.copy()
        if self.bridge_enabled:
            bridge_js=[self.codes.index(c) for c in voo_bridge_proxy.ALIASES]
            effective_voo=self.values[:,self.codes.index("VOO")]+self.values[:,bridge_js].sum(axis=1)
            deployed_by_code[:,self.codes.index("VOO")]=np.abs(effective_voo/np.maximum(total,1)-self.target["VOO"])<=tolerance
            deployed_by_code[:,bridge_js]=True
        deployed=np.all(deployed_by_code,axis=1)&(np.abs(cash_weight-cash_target)<=tolerance)&settled
        fully_allocated &= self.values[:,self.high_group].sum(axis=1)<=self.high_cap*total+1e-6
        whole = np.column_stack((assets[:, 0], assets[:, 1], assets[:, 2] + reserve)) / np.maximum(total[:, None], 1)
        result = {"date": label, "completed_paths_pct": float(complete.mean() * 100), "unfinished_paths_pct": float((~complete).mean() * 100),
                  "completion_scope": "legacy rotation subset; excludes VOO, SGOV and 24B2",
                  "all_funds_allocated_paths_pct": float(fully_allocated.mean() * 100),
                  "including_temporary_bridge_deployed_paths_pct":float(deployed.mean()*100),
                  "all_funds_unallocated_paths_pct": float((~fully_allocated).mean() * 100),
                  "all_funds_scope": "all strategic securities and zero-target exits, target cash and unsettled/tagged proceeds; 24B2 permitted retention is disclosed separately; no bear exclusions",
                  "completion_tolerance_total_weight": tolerance,
                  "permitted_retained_fund_median_twd": float(np.median(self.values[:, self.codes.index("24B2")])) if "24B2" in self.codes else 0.,
                  "whole_weights_median": np.median(whole, axis=0).tolist(),
                  "whole_deviation_from_target_median_pp": ((np.median(whole, axis=0) - np.array([self.inputs["recommended_total_allocation"][k] for k in ("equity", "bond_etfs", "cash_like_including_term_deposits_and_reserve")])) * 100).tolist(),
                  "stock_rotation_reserve_median_twd": float(np.median(self.stock_reserve)),
                  "bond_rotation_reserve_median_twd": float(np.median(self.bond_reserve)),
                  "unsettled_stock_median_twd": float(np.median(self.pending_stock)), "unsettled_bond_median_twd": float(np.median(self.pending_bond))}
        self.checkpoints[label] = result
        for j, code in enumerate(self.codes):
            self.audit_rows.append({"Date": label, "Security": code, "InitialUnits": float(self.initial_units[j]), "InitialValue_TWD": float(self.initial_values[j]), "TargetWeight_pct": self.weights[j] * 100,
                                   "RemainingUnits_p10": float(np.percentile(self.units[:, j], 10)), "RemainingUnits_median": float(np.median(self.units[:, j])), "RemainingUnits_p90": float(np.percentile(self.units[:, j], 90)),
                                   "Value_median_TWD": float(np.median(self.values[:, j])), "Deviation_median_pp": float(np.median(deviation[:, j]) * 100), "CompletedPaths_pct": float(np.mean(complete_by_code[:, j]) * 100),
                                   "ModeledBatches_median": float(np.median(self.tranche_count[:, j]+self.buy_count[:,j])), "ModeledBuyBatches_median": float(np.median(self.buy_count[:,j])), "ModeledSellBatches_median": float(np.median(self.tranche_count[:,j])), "ManagedByTransition": bool(managed[j])})
        return result

    def diagnostics(self):
        return {"method": "PER_SECURITY_CONDITIONAL_EXECUTION_D140", "checkpoint_results": self.checkpoints, "per_security": self.audit_rows,
                "high_dividend_symbols": list(self.high_policy["symbols"]),
                "high_dividend_hard_cap": self.high_cap,
                "high_dividend_buy_cap_violations": self.high_dividend_buy_cap_violations,
                "turnover_median_twd": float(np.median(self.turnover)), "fees_and_transaction_taxes_median_twd": float(np.median(self.costs)),
                "settlement_sessions": 2, "sell_cooldown_sessions": 5, "synthetic_sessions_per_month": 20,
                "bond_buy_cooldown_sessions": 0, "weekly_bond_qualification_persists_for_five_synthetic_days": True,
                "max_bond_daily_lots": self.max_bond_daily_lots, "max_bond_weekly_lots": self.max_bond_weekly_lots,
                "minimum_actual_settlement_wait_sessions": self.minimum_settlement_wait_sessions,
                "funding_limit_violations": self.funding_limit_violations,
                "fx_bond_gate_violations": self.fx_bond_gate_violations,
                "intermediate_cap_violations": self.intermediate_cap_violations,
                "unsettled_proceeds_used": self.unsettled_spend_violations, "additional_living_reserve_injection_twd": 0,
                "bridge_enabled":self.bridge_enabled,
                "bridge_paths_with_build_pct":float(self.bridge_built.mean()*100),
                "bridge_paths_with_voo_conversion_pct":float(self.bridge_converted.mean()*100),
                "bridge_maximum_weight_p95":float(np.percentile(self.bridge_max_weight,95)),
                "bridge_open_value_median_twd":float(np.median(self.values[:,[self.codes.index(c) for c in voo_bridge_proxy.ALIASES]].sum(axis=1))) if self.bridge_enabled else 0.,
                "bridge_cash_released_to_general_median_twd":float(np.median(self.bridge_cash_released)),
                "bridge_pending_cash_median_twd":float(np.median(self.bridge_cash+self.bridge_pending)),
                "future_qualification_is_assumption": True, "future_holidays_and_order_books_are_not_predicted": True,
                "post_extension_residue": "retained in actual ending bucket; no new extension or forced disposal authorized", "places_orders": False}
