"""Per-security FX conversion and one bounded Taiwan inflation path."""
from __future__ import annotations

import numpy as np
import calendar
import math


class CalendarMarketPath:
    """One market innovation per calendar month, shared across cash-date grids.

    Log growth is interpolated within each month. Adding a retirement, tax or
    medical payment date cannot redraw the rest of the market history.
    Intramonth price jumps are not predicted by this monthly approximation.
    """
    def __init__(self, model, paths, seed, factor, t_adjust):
        self.model, self.n, self.seed = model, paths, seed
        self.factor, self.t_adjust = factor, t_adjust
        self.key, self.month_return = None, None

    def period(self, date, mean, fraction):
        days = calendar.monthrange(date.year, date.month)[1]
        key = (date.year, date.month, tuple(mean))
        if key != self.key:
            rng = np.random.default_rng(np.random.SeedSequence([self.seed, 151, date.year, date.month]))
            self.month_return = self.model.draw_period_returns(
                rng, mean, days / 365.25, self.n, self.factor, self.t_adjust)
            self.key = key
        return np.power(np.maximum(1 + self.month_return, .05), fraction / (days / 365.25)) - 1


class InflationPath:
    """Draw one annual rate per calendar year, shared by all dated subperiods."""
    def __init__(self, paths, seed, lower=.02, upper=.025):
        if not 0 <= lower <= upper:
            raise ValueError("inflation bounds are invalid")
        self.rng = np.random.default_rng(np.random.SeedSequence([seed, 151, 7]))
        self.n, self.lower, self.upper = paths, lower, upper
        self.year = None
        self.annual_rate = None

    def period(self, date, fraction, upper_bound=False):
        if date.year != self.year:
            self.year = date.year
            self.annual_rate = self.rng.uniform(self.lower, self.upper, self.n)
        annual = np.full(self.n, self.upper) if upper_bound else self.annual_rate
        return np.power(1 + annual, fraction) - 1


def security_returns(draws, buckets, usd_exposure, inflation, fraction, reference_inflation):
    """Convert each actual holding once, before weighting the portfolio.

    Equity forward assumptions are real returns. Cash and fixed nominal bond
    yields were stored in reference-real units by the input calibration, so
    undo that reference deflation and apply the path's actual CPI. TW equity
    and global equity share the disclosed equity market shock; only verified
    overseas exposure receives direct FX. This does not invent independent
    local/global diversification or alter shares when prices move.
    """
    buckets = np.asarray(buckets)
    result = draws[:, buckets].copy()
    result = (1 + result) * (1 + draws[:, 3, None] * np.asarray(usd_exposure)) - 1
    bonds = buckets == 1
    result[:, bonds] = (1 + result[:, bonds]) * (1 + reference_inflation) ** fraction / (1 + inflation[:, None]) - 1
    cash = (1 + draws[:, 2]) * (1 + reference_inflation) ** fraction / (1 + inflation) - 1
    return np.maximum(result, -.95), np.maximum(cash, -.95)


def weighted_bucket_returns(values, returns, buckets, cash_return):
    result = np.column_stack((np.zeros(len(values)), np.zeros(len(values)), cash_return))
    for bucket in (0, 1):
        mask = np.asarray(buckets) == bucket
        opening = values[:, mask].sum(axis=1)
        result[:, bucket] = np.divide((values[:, mask] * returns[:, mask]).sum(axis=1),
                                     opening, out=np.zeros(len(values)), where=opening > 0)
    return result


def arithmetic_for_geometric_target(geometric: float, volatility: float) -> float:
    """Lognormal planning approximation used only to undo volatility drag."""
    return math.exp(math.log1p(geometric) + 0.5 * volatility * volatility) - 1



def covariance_factor(covariance: np.ndarray) -> np.ndarray:
    """Return L with L @ L.T == covariance, tolerating positive-SEMI-definite input.

    ``np.linalg.cholesky`` raises ``LinAlgError`` on a merely semi-definite
    matrix, which happens for any legitimate sensitivity run that sets a
    volatility to zero (e.g. the cash-flow-conservation regression test).  The
    Cholesky branch is tried first and kept bit-identical so the declared base
    case is unaffected; only the failing cases fall back to a symmetric
    eigen-decomposition with negative eigenvalues clipped to zero.
    """
    try:
        return np.linalg.cholesky(covariance)
    except np.linalg.LinAlgError:
        eigenvalues, eigenvectors = np.linalg.eigh((covariance + covariance.T) / 2)
        eigenvalues = np.clip(eigenvalues, 0.0, None)
        return eigenvectors * np.sqrt(eigenvalues)


def draw_period_returns(
    rng: np.random.Generator,
    mean_vec: np.ndarray,
    fraction: float,
    simulations: int,
    factor: np.ndarray,
    t_adjust: float,
    degrees_freedom: int = 7,
) -> np.ndarray:
    """One period of correlated Student-t simple returns.

    Extracted verbatim from ``simulate()`` so the generator's moments can be
    regression-tested directly.  The RNG is consumed in exactly the same order
    (standard_normal block, then chisquare block), so the declared base case is
    bit-identical.

    ``mean_vec`` is an ARITHMETIC single-period expectation: the shock has zero
    mean, so E[return] == (1 + mean_vec) ** fraction - 1 and the realised
    compound growth is lower by the volatility drag.
    """
    period_mean = np.power(1 + mean_vec, fraction) - 1
    normal = rng.standard_normal((simulations, factor.shape[0]))
    chi = rng.chisquare(degrees_freedom, size=simulations)
    scale = (np.sqrt(degrees_freedom / chi) * t_adjust)[:, None]
    shocks = (normal @ (factor * math.sqrt(fraction)).T) * scale
    return np.maximum(period_mean + shocks, -0.95)
