"""Stateful, bounded bear-window tranches and ranked bond funding."""
from __future__ import annotations

import numpy as np


class BearLadder:
    """Five growing funding batches, locked to one bear-window budget.

    A partial fill consumes only its actual funding and one deployment date;
    its unused allocation stays cash. It never creates additional small buys.
    """
    def __init__(self, policy, paths):
        rungs = policy["bear_ladder_d141"]
        self.thresholds = np.array([row["drawdown"] for row in rungs])
        self.weights = np.array([row["share_of_window_budget"] for row in rungs])
        if not (4 <= len(rungs) <= 5 and 0 < self.weights[0] <= .10
                and np.all(np.diff(self.weights) > 0) and np.isclose(self.weights.sum(), 1)):
            raise ValueError("invalid growing bear tranches")
        self.n = paths
        self.active = np.zeros(paths, dtype=bool)
        self.window_budget = np.zeros(paths)
        self.budget = np.zeros((paths, len(rungs)))
        self.spent = np.zeros_like(self.budget)
        self.fills = np.zeros_like(self.budget, dtype=int)
        self.last_fill = np.full(paths, -100, dtype=int)
        self.last_amount = np.zeros(paths)
        self.initial_bonds = np.zeros(paths)
        self.bonds_sold = np.zeros(paths)
        self.lifetime_fills = np.zeros(len(rungs), dtype=int)
        self.maximum_first_batch_share = 0.
        self.maximum_window_fills = 0
        self.cooldown = 5
        self.bond_share = float(policy["bear_bond_sale_max_share"])
        self.bond_floor = float(policy["total_bond_floor"])
        self.equity_cap = float(policy["bear_equity_cap"])

    def update(self, drawdown, qualified_window, total, eligible_bonds, *,
               available_cash, total_bonds, equity):
        drawdown = np.asarray(drawdown)
        recovered = drawdown > -.10
        self.active[recovered] = False
        self.window_budget[recovered] = 0
        self.budget[recovered] = 0
        self.spent[recovered] = 0
        self.fills[recovered] = 0
        self.bonds_sold[recovered] = 0
        self.last_fill[recovered] = -100
        self.last_amount[recovered] = 0
        bond_limit = np.minimum(np.maximum(eligible_bonds, 0) * self.bond_share,
                                np.maximum(total_bonds - self.bond_floor * total, 0))
        planned = np.minimum(np.maximum(available_cash, 0) + bond_limit,
                             np.maximum(self.equity_cap * total - equity, 0))
        fresh = ~self.active & qualified_window & (drawdown <= -self.thresholds[0]) & (planned > .01)
        self.active |= fresh
        self.initial_bonds[fresh] = eligible_bonds[fresh]
        self.window_budget[fresh] = planned[fresh]
        self.budget[fresh] = planned[fresh, None] * self.weights
        self.drawdown = drawdown

    def proposal(self, session, qualified):
        count = (self.fills > 0).sum(axis=1)
        rung = np.minimum(count, len(self.weights)-1)
        rows = np.arange(self.n)
        allowed = (self.active & qualified & (count < len(self.weights))
                   & (self.drawdown <= -self.thresholds[rung])
                   & (session - self.last_fill >= self.cooldown))
        return rung, np.where(allowed, self.budget[rows, rung], 0)

    def growing_fill(self, deployed):
        # If capacity shrinks below the preceding actual batch, wait. Never
        # force a larger purchase through a cash, bond, or allocation ceiling.
        return np.asarray(deployed) > self.last_amount + 1e-8

    def record(self, rung, deployed, bond_sale, session):
        hit = np.asarray(deployed) > 1e-8
        rows = np.flatnonzero(hit)
        if not len(rows):
            return
        count = (self.fills > 0).sum(axis=1)
        if (np.any(rung[hit] != count[hit])
                or np.any(deployed[hit] > self.budget[rows, rung[hit]] + 1e-6)
                or np.any(~self.growing_fill(deployed)[hit])):
            raise ValueError("bear batch sequence, size or budget violated")
        first = hit & (count == 0)
        if np.any(first):
            self.maximum_first_batch_share = max(self.maximum_first_batch_share,
                float(np.max(deployed[first] / self.window_budget[first])))
        self.spent[rows, rung[rows]] += deployed[rows]
        self.fills[rows, rung[rows]] += 1
        self.last_fill[hit] = session
        self.last_amount[hit] = deployed[hit]
        self.bonds_sold += bond_sale
        self.lifetime_fills += np.bincount(rung[hit], minlength=len(self.thresholds))
        self.maximum_window_fills = max(self.maximum_window_fills, int((count + hit).max()))

    def bond_allowance(self):
        return np.maximum(self.initial_bonds * self.bond_share - self.bonds_sold, 0)


class BullLadder:
    """D-156 profit capture: arm on gains, sell only after a high-water pullback.

    The window locks one target-reduction budget. New highs ratchet the
    watermark and keep the next tranche invested; a tranche becomes eligible
    only after both its gain rung was reached and its pullback rung is met.
    This avoids treating a breakout itself as evidence that the future top is
    known. A partial fill consumes one batch and unused budget is never forced.
    """
    def __init__(self, policy, paths):
        rungs = policy["bull_ladder_d156"]
        self.thresholds = np.array([row["gain_above_anchor"] for row in rungs])
        self.pullback_thresholds = np.array([row["pullback_from_high_watermark"] for row in rungs])
        self.weights = np.array([row["share_of_window_budget"] for row in rungs])
        if not (4 <= len(rungs) <= 5 and 0 < self.weights[0] <= .10
                and np.all(np.diff(self.weights) > 0) and np.isclose(self.weights.sum(), 1)
                and np.all(np.diff(self.thresholds) > 0)
                and np.all(np.diff(self.pullback_thresholds) > 0)
                and self.pullback_thresholds[-1] < .10):
            raise ValueError("invalid growing bull tranches")
        self.n = paths
        self.active = np.zeros(paths, dtype=bool)
        self.window_budget = np.zeros(paths)
        self.budget = np.zeros((paths, len(rungs)))
        self.spent = np.zeros_like(self.budget)
        self.fills = np.zeros_like(self.budget, dtype=int)
        self.last_fill = np.full(paths, -100, dtype=int)
        self.last_amount = np.zeros(paths)
        self.lifetime_fills = np.zeros(len(rungs), dtype=int)
        self.maximum_first_batch_share = 0.
        self.maximum_window_fills = 0
        # The bull window is checked monthly (one proposal/record call per
        # retired_months step), unlike the bear ladder's four-week, 5-session
        # loop. A cooldown of 1 means "not the same month again"; it is not
        # a literal five-trading-day count at this cadence.
        self.cooldown = 1
        self.equity_floor = float(policy["monthly_bull_first_equity_target"])
        self.gain = np.full(paths, -np.inf)
        self.high_water_gain = np.full(paths, -np.inf)
        self.pullback = np.zeros(paths)
        self.pending_close = np.zeros(paths, dtype=bool)

    def update(self, gain_above_anchor, qualified_window, total, equity, *, corrected):
        gain_above_anchor = np.asarray(gain_above_anchor)
        corrected = np.asarray(corrected)
        planned = np.maximum(equity - self.equity_floor * total, 0)
        fresh = ~self.active & qualified_window & (gain_above_anchor >= self.thresholds[0]) & (planned > .01)
        self.active |= fresh
        self.window_budget[fresh] = planned[fresh]
        self.budget[fresh] = planned[fresh, None] * self.weights
        self.high_water_gain[fresh] = gain_above_anchor[fresh]
        live = self.active & np.isfinite(gain_above_anchor)
        self.high_water_gain[live] = np.maximum(self.high_water_gain[live], gain_above_anchor[live])
        self.gain = gain_above_anchor
        self.pullback.fill(0)
        valid = live & (self.high_water_gain > -1)
        self.pullback[valid] = (
            (self.high_water_gain[valid] - self.gain[valid])
            / (1 + self.high_water_gain[valid])
        )
        self.pending_close = self.active & corrected

    def proposal(self, session, qualified):
        count = (self.fills > 0).sum(axis=1)
        rung = np.minimum(count, len(self.weights)-1)
        rows = np.arange(self.n)
        allowed = (self.active & qualified & (count < len(self.weights))
                   & (self.high_water_gain >= self.thresholds[rung])
                   & (self.pullback >= self.pullback_thresholds[rung])
                   & (self.gain > 0)
                   & (session - self.last_fill >= self.cooldown))
        return rung, np.where(allowed, self.budget[rows, rung], 0)

    def close_window(self, mask):
        """Reset a corrected window after its current eligible tranche is evaluated."""
        mask = np.asarray(mask, dtype=bool) & self.active
        self.active[mask] = False
        self.window_budget[mask] = 0
        self.budget[mask] = 0
        self.spent[mask] = 0
        self.fills[mask] = 0
        self.last_fill[mask] = -100
        self.last_amount[mask] = 0
        self.high_water_gain[mask] = -np.inf
        self.pullback[mask] = 0
        self.pending_close[mask] = False

    def growing_fill(self, deployed):
        # If the released amount shrinks below the preceding actual batch,
        # wait. Never force a smaller-than-prior batch or skip ahead.
        return np.asarray(deployed) > self.last_amount + 1e-8

    def record(self, rung, deployed, session):
        hit = np.asarray(deployed) > 1e-8
        rows = np.flatnonzero(hit)
        if not len(rows):
            return
        count = (self.fills > 0).sum(axis=1)
        if (np.any(rung[hit] != count[hit])
                or np.any(deployed[hit] > self.budget[rows, rung[hit]] + 1e-6)
                or np.any(~self.growing_fill(deployed)[hit])):
            raise ValueError("bull batch sequence, size or budget violated")
        first = hit & (count == 0)
        if np.any(first):
            self.maximum_first_batch_share = max(self.maximum_first_batch_share,
                float(np.max(deployed[first] / self.window_budget[first])))
        self.spent[rows, rung[rows]] += deployed[rows]
        self.fills[rows, rung[rows]] += 1
        self.last_fill[hit] = session
        self.last_amount[hit] = deployed[hit]
        self.lifetime_fills += np.bincount(rung[hit], minlength=len(self.thresholds))
        self.maximum_window_fills = max(self.maximum_window_fills, int((count + hit).max()))


def ranked_bond_sale(values, requested, trailing_returns, durations, eligible):
    """Sell highest trailing-12m TWD total return first, then longer duration.

    The caller supplies contemporaneous path returns. Unavailable histories
    exclude a security; they are never converted to a favourable zero return.
    """
    returns = np.broadcast_to(trailing_returns, values.shape)
    duration = np.broadcast_to(durations, values.shape)
    valid = np.broadcast_to(eligible, values.shape) & np.isfinite(returns)
    order = np.lexsort((-duration, -np.where(valid, returns, -np.inf)), axis=1)
    remainder = np.maximum(requested, 0).copy()
    sales = np.zeros_like(values)
    rows = np.arange(len(values))
    for rank in range(values.shape[1]):
        columns = order[:, rank]
        take = np.where(valid[rows, columns], np.minimum(values[rows, columns], remainder), 0)
        sales[rows, columns] = take
        remainder -= take
    return sales
