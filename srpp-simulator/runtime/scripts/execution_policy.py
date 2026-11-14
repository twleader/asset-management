#!/usr/bin/env python3
"""Shared quantity and confirmed-fill cadence calculations; never places orders."""
from __future__ import annotations

import argparse
import datetime as dt
import json
import re
from decimal import ROUND_CEILING, Decimal
from pathlib import Path
from zoneinfo import ZoneInfo

import numpy as np


def sell_shares(holdings, excess, capacity, *, zero_target, baseline_excess=None,
                confirmed_batches=0, lot_size=1000, baseline_value_twd=None,
                large_exit_value_twd=1_000_000):
    """One mutually exclusive branch: tranche, small exit, or final remainder.

    Capacity is already the minimum of all external gates. Rounding can never
    enlarge it. Large exits use three batches even below one exchange lot.
    Opening plan value stays fixed; unknown value cannot grant a small exit.
    The caller must supply capacity from the applicable odd-lot order book.
    """
    holdings = np.maximum(np.asarray(holdings, dtype=float), 0)
    excess = np.maximum(np.asarray(excess, dtype=float), 0)
    capacity = np.maximum(np.asarray(capacity, dtype=float), 0)
    baseline = holdings if baseline_excess is None else np.maximum(baseline_excess, 0)
    has_baseline = baseline_excess is not None
    small_value = False if baseline_value_twd is None else np.asarray(baseline_value_twd) < large_exit_value_twd
    small_exit = (baseline <= 5 * lot_size) & small_value
    final = zero_target & (small_exit | (has_baseline & (np.asarray(confirmed_batches) >= 2)))
    # A high-value 500-share position uses odd lots: 166 / 166 / 168.
    step = np.where(zero_target & ~small_exit & (baseline < 3 * lot_size), 1, lot_size)
    tranche = baseline / 3 if has_baseline else np.minimum(excess, holdings) / 3
    tranche = np.maximum(np.floor(tranche / step + 1e-10) * step, step)
    if has_baseline:
        tranche = np.where(np.asarray(confirmed_batches) >= 2, excess, tranche)
    requested = np.where(final, holdings, tranche)
    available = np.minimum(np.minimum(holdings, excess), capacity)
    whole = np.floor(np.minimum(requested, available) / step + 1e-10) * step
    clear = final & (available + 1e-8 >= holdings)
    return np.where(clear, np.floor(holdings + 1e-8), whole)


def sell_request(request):
    """Daily-report scalar adapter; input must describe confirmed holdings."""
    if set(request) - {"holdings", "excess", "capacity", "zero_target", "baseline_excess", "confirmed_batches", "baseline_value_twd"}:
        raise ValueError("unknown quantity input; TW lot size is fixed at 1000")
    for key in ("holdings", "excess", "capacity", "confirmed_batches"):
        value = request.get(key, 0 if key == "confirmed_batches" else None)
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not np.isfinite(value) or value < 0:
            raise ValueError(f"{key} must be a nonnegative finite number")
    if not isinstance(request.get("zero_target"), bool):
        raise ValueError("zero_target must be a boolean")
    for key in ("holdings", "confirmed_batches", "baseline_excess", "baseline_value_twd"):
        if key in request and (isinstance(request[key], bool) or not isinstance(request[key], (int, float))
                               or not np.isfinite(request[key]) or request[key] < 0
                               or (key in {"holdings", "confirmed_batches"} and request[key] != int(request[key]))):
            raise ValueError(f"{key} is invalid")
    quantity = int(sell_shares(**request))
    clearing = request["zero_target"] and quantity > 0 and quantity == request["holdings"]
    return {"action": "CLEAR_REMAINDER" if clearing else "SELL_TRANCHE" if quantity else "NO_QUANTITY",
            "shares": quantity, "whole_lots": quantity // 1000, "odd_shares": quantity % 1000,
            "remaining_shares": request["holdings"] - quantity, "places_orders": False}


ROOT = Path(__file__).resolve().parents[1]


def _finite_positive(value):
    return (not isinstance(value, bool) and isinstance(value, (int, float))
            and bool(np.isfinite(value)) and value > 0)


def _iso_date(value):
    try:
        return dt.date.fromisoformat(value).isoformat() == value
    except (TypeError, ValueError):
        return False


def taipei_timestamp(value):
    """Parse the published source shape consistently on Python 3.10 and newer.

    Java emits nanoseconds; datetime stores microseconds. Truncating only the
    seventh through ninth fractional digits preserves the source instant to
    datetime's precision without rounding a timestamp into a later session.
    An absent offset has the existing Asia/Taipei meaning, never host TZ.
    """
    if not isinstance(value, str):
        raise ValueError('TIMESTAMP_UNVERIFIED')
    match = re.fullmatch(
        r'(\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?'
        r'(Z|[+-](?:[01]\d|2[0-3]):[0-5]\d)?'
        r'(?:\[(UTC|GMT|[A-Za-z_]+(?:/[A-Za-z0-9_+.-]+)+)\])?', value.strip())
    if not match:
        raise ValueError('TIMESTAMP_UNVERIFIED')
    base, fraction, offset, named_zone = match.groups()
    raw = base + ('.' + fraction[:6].ljust(6, '0') if fraction else '')
    raw += '+00:00' if offset == 'Z' else offset or ''
    try:
        result = dt.datetime.fromisoformat(raw)
        if named_zone:
            # DATA_FETCH_RULES: a valid bracketed Java zone is only a suffix.
            # The explicit offset wins; without one the existing meaning is
            # always Asia/Taipei, even when the suffix says UTC.
            ZoneInfo(named_zone)
    except (ValueError, KeyError) as error:
        raise ValueError('TIMESTAMP_UNVERIFIED') from error
    zone = ZoneInfo('Asia/Taipei')
    return (result.replace(tzinfo=zone) if result.tzinfo is None else result).astimezone(zone)


def quote_freshness(quote, *, trading_date, slot, asof, policy, stage='final'):
    """D-175 price age only; order-book and all other execution gates still apply.

    Slot is the actual report slot, never inferred from a clock or an old
    automation ID. Both acquisition and final price checks retain the same
    low-volume exemptions; ordinary prices use their separate 5/10m limits.
    """
    if policy.get('decision') != 'D-175' or stage not in ('acquisition', 'final'):
        raise ValueError('QUOTE_FRESHNESS_POLICY_UNVERIFIED')
    if not isinstance(slot, str) or not slot:
        raise ValueError('QUOTE_SLOT_UNVERIFIED')
    now, updated = taipei_timestamp(asof), taipei_timestamp(quote['updatedAt'])
    age = (now - updated).total_seconds()
    if (not _iso_date(trading_date) or now.date().isoformat() != trading_date
            or updated.date().isoformat() != trading_date or age < 0
            or quote.get('tradingDate') != trading_date or quote.get('market') != '台股'
            or quote.get('quoteStatus') != 'LIVE' or quote.get('closed') is not False
            or not isinstance(quote.get('source'), str) or not quote['source'].strip()
            or not _finite_positive(quote.get('price'))):
        raise ValueError('LIVE_SELL_PRICE_UNVERIFIED')
    ceiling = policy['default_acquisition_max_minutes' if stage == 'acquisition'
                     else 'default_final_limit_max_minutes']
    status = 'DEFAULT_ACQUISITION_5M' if stage == 'acquisition' else 'DEFAULT_FINAL_LIMIT_10M'
    volume = quote.get('volume')
    whole_volume = (_finite_positive(volume) and volume == int(volume))
    if (slot == '09:05' and whole_volume and volume < policy['early_volume_below_lots']
            and _finite_positive(quote.get('previousClose'))
            and quote['price'] != quote['previousClose']):
        ceiling = policy['early_price_age_limit_minutes']
        status = 'EARLY_TRADED_PRICE_NO_AGE_LIMIT'
    elif slot == '12:00' and whole_volume and volume <= 1000:
        ceiling = policy['midday_volume_at_most_1000_max_minutes']
        status = 'MIDDAY_VOLUME_LE_1000_20M'
    elif slot == '12:00' and whole_volume and 1001 <= volume <= 1500:
        ceiling = policy['midday_volume_1001_through_1500_max_minutes']
        status = 'MIDDAY_VOLUME_1001_1500_15M'
    if ceiling is not None and (not _finite_positive(ceiling) or age > ceiling * 60):
        raise ValueError('LIVE_SELL_PRICE_UNVERIFIED')
    return {'decision': 'D-175', 'status': status, 'slot': slot, 'stage': stage,
            'age_minutes': age / 60, 'max_age_minutes': ceiling}


def completed_calendar_window(trading_date, count, root=ROOT, *, market='台股'):
    """Require exact preceding sessions from the requested market calendar."""
    if market not in ('台股', '美股'):
        raise ValueError("EXACT_SELECTOR_MISMATCH")
    calendar_key = 'us' if market == '美股' else 'tw'
    if not _iso_date(trading_date) or not isinstance(count, int) or count < 1:
        raise ValueError("SESSION_DATES_UNVERIFIED")
    today = dt.date.fromisoformat(trading_date)
    start = today - dt.timedelta(days=count * 3 + 14)
    calendar = {}
    try:
        for year in range(start.year, today.year + 1):
            data = json.loads((Path(root) / 'data/input' / f'交易日曆_{year}.json').read_text())
            for row in data['days']:
                date = row['date']
                if not _iso_date(date) or not isinstance(row.get(calendar_key), bool) or date in calendar:
                    raise ValueError("TRADING_CALENDAR_UNVERIFIED")
                calendar[date] = row[calendar_key]
    except (OSError, KeyError, TypeError, json.JSONDecodeError) as error:
        raise ValueError("TRADING_CALENDAR_UNVERIFIED") from error
    if calendar.get(trading_date) is not True:
        raise ValueError("TRADING_CALENDAR_UNVERIFIED")
    dates = sorted(date for date, opened in calendar.items() if opened and date < trading_date)
    if len(dates) < count:
        raise ValueError("TRADING_CALENDAR_UNVERIFIED")
    return dates[-count:]


NO_DISTRIBUTION_MESSAGES = ("查無資料",)


def adjusted_completed_closes(quote, *, stock_code, market, trading_date,
                              completed_session, lookback, policy, root=ROOT):
    """D-171: validate identity, calendar coverage and cash-action evidence.

    This is the D-170 additive cash adjustment, not a total-return series or
    a replacement for the radar's technical indicators. Unsupported rights
    or stock distributions and ambiguous history never become a zero event.
    """
    if (not isinstance(stock_code, str) or not stock_code or market not in ('台股', '美股')
            or not isinstance(quote, dict) or quote.get('stockCode') != stock_code
            or quote.get('market') != market):
        raise ValueError("EXACT_SELECTOR_MISMATCH")
    expected = completed_calendar_window(trading_date, lookback, root, market=market)
    if completed_session != expected[-1]:
        raise ValueError("COMPLETED_CLOSES_UNVERIFIED")
    try:
        chart = quote['marketData']['chart']
        daily = chart['series']['daily']
        dates, closes = daily['dates'], daily['closes']
        if (chart.get('status') != 'AVAILABLE' or not isinstance(dates, list)
                or not isinstance(closes, list) or len(dates) != len(closes)
                or not dates or any(not _iso_date(d) for d in dates)
                or dates != sorted(set(dates)) or dates[-1] > trading_date
                or not _iso_date(chart.get('requestedStart'))
                or chart.get('requestedEnd') != trading_date
                or not chart['requestedStart'] <= dates[0] <= expected[0]):
            raise ValueError("COMPLETED_CLOSES_UNVERIFIED")
    except (KeyError, TypeError, AttributeError) as error:
        raise ValueError("COMPLETED_SERIES_MISSING") from error
    window = [(d, c) for d, c in zip(dates, closes) if d < trading_date][-lookback:]
    if [d for d, _ in window] != expected or any(not _finite_positive(c) for _, c in window):
        raise ValueError("COMPLETED_CLOSES_UNVERIFIED")
    history = quote.get('dividendHistory')
    # D-172: for a policy-listed non-distributing ETF (00865B, 009826) the API
    # answers an identity-matched empty history as source=None with the fixed
    # "查無資料" message. Only that exact shape counts as "no cash events";
    # any other message (timeout, error) or a distributing ETF stays unverified.
    known_empty = (stock_code in policy['non_distributing_symbols'] and isinstance(history, dict)
                   and history.get('stockCode') == stock_code and history.get('market') == market
                   and history.get('rows') == [] and history.get('source') is None
                   and history.get('message') in NO_DISTRIBUTION_MESSAGES)
    if not known_empty and (
            not isinstance(history, dict) or history.get('stockCode') != stock_code
            or history.get('market') != market or not isinstance(history.get('source'), str)
            or not history['source'].strip() or history.get('message') is not None
            or not isinstance(history.get('rows'), list)):
        raise ValueError("DISTRIBUTION_HISTORY_UNVERIFIED")
    rows = history['rows']
    if not rows and stock_code not in policy['non_distributing_symbols']:
        raise ValueError("DISTRIBUTION_HISTORY_UNVERIFIED")
    events = {}
    for row in rows:
        if not isinstance(row, dict):
            raise ValueError("DISTRIBUTION_HISTORY_UNVERIFIED")
        ex_date, rights_date = row.get('exDividendDate'), row.get('exRightsDate')
        if rights_date is not None:
            if not _iso_date(rights_date):
                raise ValueError("DISTRIBUTION_HISTORY_UNVERIFIED")
            if expected[0] < rights_date <= trading_date:
                raise ValueError("STOCK_DIVIDEND_ADJUSTMENT_UNSUPPORTED")
        if not _iso_date(ex_date):
            raise ValueError("DISTRIBUTION_HISTORY_UNVERIFIED")
        if not expected[0] < ex_date <= trading_date:
            continue
        stock_dividend, cash = row.get('stockDividend'), row.get('cashDividend')
        if isinstance(stock_dividend, bool) or stock_dividend not in (None, 0, 0.0):
            raise ValueError("STOCK_DIVIDEND_ADJUSTMENT_UNSUPPORTED")
        if (isinstance(cash, bool) or not isinstance(cash, (int, float))
                or not np.isfinite(cash) or cash < 0):
            raise ValueError("CASH_DISTRIBUTION_UNVERIFIED")
        amount = Decimal(str(cash))
        if ex_date in events and events[ex_date] != amount:
            raise ValueError("DISTRIBUTION_HISTORY_CONFLICT")
        events[ex_date] = amount
    adjusted = [(d, Decimal(str(c)) - sum((cash for ex, cash in events.items() if d < ex), Decimal(0)))
                for d, c in window]
    if any(c <= 0 for _, c in adjusted):
        raise ValueError("ADJUSTED_CLOSE_INVALID")
    return adjusted, [{'ex_date': ex, 'cash': float(cash)} for ex, cash in sorted(events.items())]


def daily_topup_tier(quote, *, stock_code, market, trading_date, completed_session, policy):
    """Return the D-198 autonomous high-dividend top-up lot ceiling.

    The caller verifies the calendar, live quote, hard gates, funding, depth,
    radar detail and deduplication. This function compares the live price with
    the highest distribution-adjusted close of the last N completed sessions.
    A suitable 1%/2% dip allows at most 2/3 lots; otherwise it allows zero.
    It does not recompute other indicators or place orders.
    """
    base = {"tier_lots": 0, "status": "TOPUP_BONUS_UNVERIFIED", "stock_code": stock_code,
            "trading_date": trading_date, "completed_session": completed_session,
            "places_orders": False}

    def no_buy(reason, **extra):
        return {**base, "reason": reason, **extra}

    if not (_iso_date(trading_date) and _iso_date(completed_session) and completed_session < trading_date):
        return no_buy("SESSION_DATES_UNVERIFIED")
    if not isinstance(quote, dict) or quote.get("stockCode") != stock_code or quote.get("market") != market:
        return no_buy("EXACT_SELECTOR_MISMATCH")
    if quote.get("tradingDate") != trading_date:
        return no_buy("QUOTE_DATE_MISMATCH")
    price, premium = quote.get("price"), quote.get("premiumDiscountPct")
    if not _finite_positive(price):
        return no_buy("LIVE_PRICE_MISSING")
    if isinstance(premium, bool) or not isinstance(premium, (int, float)) or not np.isfinite(premium):
        return no_buy("PREMIUM_NULL_OR_INVALID")
    if abs(premium) > policy["premium_pause_abs_pct"]:
        return {**base, "tier_lots": 0, "status": "TOPUP_PREMIUM_PAUSE", "premium_discount_pct": premium,
                "reason": "D124_ABS_PREMIUM_OVER_LIMIT"}
    lookback = policy["lookback_completed_sessions"]
    try:
        adjusted, events = adjusted_completed_closes(quote, stock_code=stock_code, market=market,
            trading_date=trading_date, completed_session=completed_session, lookback=lookback, policy=policy)
    except ValueError as error:
        return no_buy(str(error))
    high_date, high = max(adjusted, key=lambda item: (item[1], item[0]))
    drawdown = Decimal(str(price)) / high - 1
    detail = {"live_price": price, "premium_discount_pct": premium, "reference_high": float(high),
              "reference_high_date": high_date, "drawdown_pct": float(round(drawdown * 100, 4)),
              "reference_window_start": adjusted[0][0], "reference_window_end": adjusted[-1][0],
              "reference_sessions": len(adjusted), "distribution_adjustments": events}
    if premium > policy["bonus_max_premium_discount_pct"]:
        return {**base, **detail, "tier_lots": 0, "status": "TOPUP_PRICE_NOT_SUITABLE", "reason": "PREMIUM_ABOVE_BONUS_LIMIT"}
    if drawdown <= -Decimal(str(policy["tier3_drawdown_from_adjusted_high"])):
        lots, reason = 3, "DRAWDOWN_TIER_3"
    elif drawdown <= -Decimal(str(policy["tier2_drawdown_from_adjusted_high"])):
        lots, reason = 2, "DRAWDOWN_TIER_2"
    else:
        lots, reason = 0, "PRICE_NOT_SUITABLE"
    capped_lots = min(lots, policy["max_lots_per_day"])
    status = f"TOPUP_TIER_{capped_lots}" if capped_lots else "TOPUP_PRICE_NOT_SUITABLE"
    return {**base, **detail, "tier_lots": capped_lots, "status": status, "reason": reason}


def load_topup_policy(root=ROOT):
    inputs = json.loads((Path(root) / "assumptions/model_inputs.json").read_text())
    return inputs["execution_policy"]["daily_topup_tier_policy"]


def bond_price_quality(quote, *, stock_code, market, trading_date, completed_session, policy):
    """D-195: price-suitability evidence for one Taiwan-listed bond ETF.

    Suitability is the live price versus the highest distribution-adjusted
    close of the last N completed sessions (the same D-170/D-171 reference
    series), plus a qualified premium/discount. It is evidence only: it never
    authorizes a lot by itself, never places orders, and every independent
    hard gate still applies.
    """
    base = {"stock_code": stock_code, "trading_date": trading_date,
            "completed_session": completed_session, "suitable": False, "places_orders": False}
    if not (_iso_date(trading_date) and _iso_date(completed_session) and completed_session < trading_date):
        return {**base, "status": "PRICE_SUITABILITY_UNVERIFIED", "reason": "SESSION_DATES_UNVERIFIED"}
    if not isinstance(quote, dict) or quote.get("stockCode") != stock_code or quote.get("market") != market:
        return {**base, "status": "PRICE_SUITABILITY_UNVERIFIED", "reason": "EXACT_SELECTOR_MISMATCH"}
    if quote.get("tradingDate") != trading_date:
        return {**base, "status": "PRICE_SUITABILITY_UNVERIFIED", "reason": "QUOTE_DATE_MISMATCH"}
    price, premium = quote.get("price"), quote.get("premiumDiscountPct")
    if not _finite_positive(price):
        return {**base, "status": "PRICE_SUITABILITY_UNVERIFIED", "reason": "LIVE_PRICE_MISSING"}
    if isinstance(premium, bool) or not isinstance(premium, (int, float)) or not np.isfinite(premium):
        return {**base, "status": "PRICE_SUITABILITY_UNVERIFIED", "live_price": price,
                "reason": "PREMIUM_NULL_OR_INVALID"}
    try:
        adjusted, events = adjusted_completed_closes(quote, stock_code=stock_code, market=market,
            trading_date=trading_date, completed_session=completed_session,
            lookback=policy["lookback_completed_sessions"], policy=policy)
    except ValueError as error:
        return {**base, "status": "PRICE_SUITABILITY_UNVERIFIED", "live_price": price,
                "premium_discount_pct": premium, "reason": str(error)}
    high_date, high = max(adjusted, key=lambda item: (item[1], item[0]))
    drawdown = Decimal(str(price)) / high - 1
    detail = {**base, "live_price": price, "premium_discount_pct": premium,
              "reference_high": float(high), "reference_high_date": high_date,
              "drawdown_pct": float(round(drawdown * 100, 4)),
              "reference_window_start": adjusted[0][0], "reference_window_end": adjusted[-1][0],
              "reference_sessions": len(adjusted), "distribution_adjustments": events}
    if abs(premium) > policy["premium_pause_abs_pct"]:
        return {**detail, "status": "PRICE_PREMIUM_PAUSE", "reason": "D124_ABS_PREMIUM_OVER_LIMIT"}
    if premium > policy["max_premium_discount_pct"]:
        return {**detail, "status": "PRICE_NOT_SUITABLE", "reason": "PREMIUM_ABOVE_SUITABLE_LIMIT"}
    if drawdown > -Decimal(str(policy["suitable_min_drawdown_from_adjusted_high"])):
        return {**detail, "status": "PRICE_NOT_SUITABLE", "reason": "DRAWDOWN_BELOW_SUITABLE_MIN"}
    return {**detail, "suitable": True, "status": "PRICE_SUITABLE", "reason": "DRAWDOWN_AND_PREMIUM_SUITABLE"}


def bond_price_selection(quotes, *, trading_date, completed_session, policy,
                         strategic_candidates=None, emergency_gap_twd=None, market="台股"):
    """D-195: choose D-130 strategic bond buys and emergency 00865B lots by price.

    ``strategic_candidates`` maps each strategic ETF that the caller has already
    verified as below its individual target, within the 00697B caps and with
    same-round hard-gate evidence to its normalized remaining gap (used only as
    the last tie-break). The strategic sleeve picks the suitable ETF with the
    deepest drawdown and gets at most the current strategic daily quota;
    no suitable ETF means 0 lots.
    ``emergency_gap_twd`` is the verified Cathay emergency 00865B gap to its
    elapsed-day-indexed target (None or <= 0 means no emergency buy). The
    emergency sleeve has its own quota: 1 lot when suitable, 2 lots when the
    drawdown reaches the two-lot threshold, never more lots than needed to
    reach the target. Quantities remain ceilings; the caller still applies
    remaining daily and weekly quotas, target capacity, best-five depth,
    funding, radar detail and deduplication.
    """
    strategic_candidates = strategic_candidates or {}
    evidence = {}
    for code in sorted(set(strategic_candidates) | ({policy["emergency_symbol"]} if emergency_gap_twd else set())):
        evidence[code] = bond_price_quality(quotes.get(code), stock_code=code, market=market,
                                            trading_date=trading_date, completed_session=completed_session,
                                            policy=policy)
    suitable = [c for c in strategic_candidates if evidence[c]["suitable"]]
    ranked = sorted(suitable, key=lambda c: (evidence[c]["drawdown_pct"], evidence[c]["premium_discount_pct"],
                                             -float(strategic_candidates[c]), c))
    strategic = {"sleeve": "STRATEGIC_FUBON", "selected": ranked[0] if ranked else None,
                 "lots": policy["strategic_max_lots_per_day"] if ranked else 0,
                 "ranking": ranked,
                 "reason": "DEEPEST_SUITABLE_DRAWDOWN" if ranked else (
                     "NO_STRATEGIC_CANDIDATE" if not strategic_candidates else "NO_SUITABLE_PRICE")}
    emergency_code = policy["emergency_symbol"]
    if not emergency_gap_twd or emergency_gap_twd <= 0:
        emergency = {"sleeve": "EMERGENCY_CATHAY", "stock_code": emergency_code, "lots": 0,
                     "reason": "EMERGENCY_TARGET_FULL_OR_NOT_ELIGIBLE"}
    else:
        q = evidence[emergency_code]
        if not q["suitable"]:
            emergency = {"sleeve": "EMERGENCY_CATHAY", "stock_code": emergency_code, "lots": 0,
                         "reason": q["reason"]}
        else:
            two = q["drawdown_pct"] <= -policy["emergency_two_lot_drawdown_from_adjusted_high"] * 100
            lots = 2 if two else 1
            needed = int((Decimal(str(emergency_gap_twd)) / (Decimal(str(q["live_price"])) * 1000)).to_integral_value(rounding=ROUND_CEILING))
            lots = max(0, min(lots, needed, policy["emergency_max_lots_per_day"]))
            emergency = {"sleeve": "EMERGENCY_CATHAY", "stock_code": emergency_code, "lots": lots,
                         "reason": "EMERGENCY_TWO_LOT_DRAWDOWN" if two else "EMERGENCY_SUITABLE_PRICE"}
    return {"decision": "D-195", "trading_date": trading_date, "completed_session": completed_session,
            "places_orders": False, "strategic": strategic, "emergency": emergency, "price_evidence": evidence}


def load_bond_price_policy(root=ROOT):
    inputs = json.loads((Path(root) / "assumptions/model_inputs.json").read_text())
    e = inputs["execution_policy"]
    return {**e["bond_price_selection_d195"],
            "non_distributing_symbols": e["daily_topup_tier_policy"]["non_distributing_symbols"]}


def transaction_tax_rates(codes, us, date):
    """Applicable modeled sale tax; US listings/fund redemption have no TW tax."""
    rates=np.array([0. if foreign or code=='24B2' else .001 if code.startswith('00') else .003
                    for code,foreign in zip(codes,us)])
    if date <= dt.date(2026,12,31):
        rates[np.array([code.startswith('00') and code.endswith('B') for code in codes])]=0.
    return rates


def bull_proceeds(requested, equity_values, sale_tax_rates, cash, living_ceiling,
                  bond_values, bond_targets, total, bond_capacity, fx, *, costs=True,
                  return_allocations=False):
    """Cap a bull sale by its two destinations, funding living cash first.

    FX and bond cadence constrain only the residual bond purchase. Each bond
    stays below its target after fees. D-185 removed the fixed pairing ratio;
    eligible destinations are ranked by normalized remaining target gap.
    All values are same-date real TWD. The optional allocation matrix is for
    the monthly engine; the default preserves the scalar-total interface.
    """
    fee = .001425 if costs else 0.
    equity_total = equity_values.sum(axis=1)
    tax_rate = (np.divide((equity_values * sale_tax_rates).sum(axis=1), equity_total,
                         out=np.zeros_like(equity_total), where=equity_total > 0)
                if costs else np.zeros_like(equity_total))
    net_rate = 1 - fee - tax_rate
    cash_room = np.maximum(living_ceiling - cash, 0)
    sell_drag = fee + tax_rate
    # Beyond the cash refill, T_after = T_cash_only - alpha * B.
    # Solve B = sum(max(target*T_after - current_value, 0)), removing
    # destinations whose room vanishes under fees. At most K removals occur.
    base_total = total - cash_room * sell_drag / net_rate
    base_room = base_total[:, None] * bond_targets - bond_values
    alpha = fee + (1 + fee) * sell_drag / net_rate
    active = base_room > 0
    for _ in range(bond_values.shape[1] + 1):
        target_room = np.maximum(np.where(active, base_room, 0).sum(axis=1), 0) / (
            1 + alpha * (active * bond_targets).sum(axis=1))
        active &= base_room - bond_targets * (alpha * target_room)[:, None] > 0
    bond_room = np.where(np.asarray(fx) <= 33.,
                         np.minimum(np.maximum(bond_capacity, 0), target_room), 0)
    capacity = (cash_room + bond_room * (1 + fee)) / net_rate
    sale = np.minimum(np.maximum(requested, 0), np.minimum(equity_total, capacity))
    taxes = sale * tax_rate
    net = sale * net_rate
    cash_fill = np.minimum(net, cash_room)
    bonds = np.maximum(net - cash_fill, 0) / (1 + fee)
    fees = sale * fee + bonds * fee
    if return_allocations:
        after_total = total - fees - taxes
        room = np.maximum(after_total[:, None] * bond_targets - bond_values, 0)
        scores = np.divide(room, after_total[:, None] * bond_targets,
                           out=np.zeros_like(room), where=after_total[:, None] * bond_targets > 0)
        order = np.argsort(-scores, axis=1, kind="stable")
        allocation = np.zeros_like(bond_values)
        remaining = bonds.copy()
        rows = np.arange(len(total))
        for rank in range(bond_values.shape[1]):
            columns = order[:, rank]
            take = np.minimum(remaining, room[rows, columns])
            allocation[rows, columns] = take
            remaining -= take
        bonds = allocation
    return sale, cash_fill, bonds, fees, taxes


def funded_rebalance(original, proposed, cash, protected_cash, total, targets, groups=(), costs=True, sell_tax_rates=None):
    """Fund buys after protected cash and sale costs; cap after all trade fees.

    Existing overweights can remain. Only new purchases are clipped. Using
    the full requested fee as the capacity denominator is conservative when
    a later capacity clip reduces the actual fee.
    """
    buy_fee = .001425 if costs else 0.
    sell_fee = buy_fee + (np.asarray(.001 if sell_tax_rates is None else sell_tax_rates) if costs else 0.)
    sales = np.where(original - proposed > 1e-8, original - proposed, 0)
    buys = np.where(proposed - original > 1e-8, proposed - original, 0)
    available = np.maximum(cash - protected_cash + sales.sum(axis=1) - (sales*sell_fee).sum(axis=1), 0)
    wanted = buys.sum(axis=1) * (1 + buy_fee)
    buys *= np.minimum(1, np.divide(available, wanted, out=np.ones(len(cash)), where=wanted > 0))[:, None]
    after_fees = total - (sales*sell_fee).sum(axis=1) - buys.sum(axis=1) * buy_fee
    retained = original - sales
    buys = np.minimum(buys, np.maximum(after_fees[:, None] * targets - retained, 0))
    for mask, cap in groups:
        requested = buys[:, mask].sum(axis=1)
        room = np.maximum(after_fees * cap - retained[:, mask].sum(axis=1), 0)
        buys[:, mask] *= np.minimum(1, np.divide(room, requested, out=np.ones(len(cash)), where=requested > 0))[:, None]
    fees = (sales*sell_fee).sum(axis=1) + buys.sum(axis=1) * buy_fee
    return retained + buys, cash + sales.sum(axis=1) - buys.sum(axis=1) - fees, fees


class GlobalEquityCadence:
    """Vector ledger. Only confirmed simulated fills consume a budget.

    Amount limits use nominal TWD purchase principal; fees are separately
    funded. Scheduled VOO debits share the monthly amount, but are not one of
    the six discretionary batch_twd batches (225K under D-213). A month that crosses FX 33 keeps the
    reduced cap until the next month; previous fills are never undone.
    """
    def __init__(self, config, paths):
        self.config, self.n = config, paths
        self.month = self.week = self.day = None
        self.spent = {code: np.zeros(paths) for code in ("VOO", "009826")}
        self.previous_shortfall = {code: np.zeros(paths) for code in self.spent}
        self.consecutive_shortfalls = {code: np.zeros(paths, dtype=int) for code in self.spent}
        self.halved = np.zeros(paths, dtype=bool)
        self.voo_month_batches = np.zeros(paths, dtype=int)
        self.voo_week_batches = np.zeros(paths, dtype=int)
        self.local_week_lots = np.zeros(paths, dtype=int)
        self.local_day_lots = np.zeros(paths, dtype=int)
        self.last_voo_fill_price = np.full(paths, np.nan)
        self.history = []

    def begin(self, date, fx, *, week=None, day=None):
        month = (date.year, date.month)
        if self.month != month:
            if self.month is not None:
                prior = dt.date(*self.month, 1)
                following = (prior.replace(day=28) + dt.timedelta(days=4)).replace(day=1)
                consecutive = following == date.replace(day=1)
                for code in self.spent:
                    baseline = self.config[code]["monthly_baseline_twd"] * np.where(self.halved, .5, 1.)
                    shortfall = np.maximum(baseline - self.spent[code], 0)
                    self.previous_shortfall[code] = shortfall if consecutive else np.zeros(self.n)
                    self.consecutive_shortfalls[code] = np.where(shortfall > .01,
                        self.consecutive_shortfalls[code] + 1 if consecutive else 1, 0)
                    self.history.append({"month": "%04d-%02d" % self.month, "code": code,
                        "maximum_spent_twd": float(self.spent[code].max()),
                        "shortfall_paths": int(np.sum(shortfall > .01)),
                        "early_review_paths": int(np.sum(self.consecutive_shortfalls[code] >= 2))})
            self.month = month
            for value in self.spent.values():
                value.fill(0)
            self.halved.fill(False)
            self.voo_month_batches.fill(0)
        self.halved |= np.asarray(fx) > self.config["fx_halving_threshold"]
        if week is not None and self.week != week:
            self.week = week
            self.voo_week_batches.fill(0)
            self.local_week_lots.fill(0)
        if day is not None and self.day != day:
            self.day = day
            self.local_day_lots.fill(0)

    def room(self, code):
        return np.maximum(self.config[code]["monthly_cap_twd"] * np.where(self.halved, .5, 1.) - self.spent[code], 0)

    def voo_budget(self, price, *, scheduled=False):
        room = self.room("VOO")
        if scheduled:
            return room
        cfg = self.config["VOO"]
        second = ((self.previous_shortfall["VOO"] > .01)
                  | (np.asarray(price) <= self.last_voo_fill_price * (1 - self.config["second_batch_price_drop"])))
        allowed = ((self.voo_week_batches < cfg["baseline_batches_per_week"])
                   | ((self.voo_week_batches < cfg["max_batches_per_week"]) & second))
        allowed &= self.voo_month_batches < cfg["max_batches_per_month"]
        return np.where(allowed, np.minimum(room, cfg["batch_twd"]), 0)

    def local_lots(self):
        cfg = self.config["009826"]
        return np.maximum(np.minimum(cfg["max_lots_per_day"] - self.local_day_lots,
                                     cfg["max_lots_per_week"] - self.local_week_lots), 0)

    def fill(self, code, nominal_value, *, price=None, shares=None, scheduled=False):
        value = np.asarray(nominal_value)
        hit = value > 1e-8
        self.spent[code] += value
        if code == "VOO":
            self.last_voo_fill_price = np.where(hit, price, self.last_voo_fill_price)
            if not scheduled:
                self.voo_month_batches += hit
                self.voo_week_batches += hit
                self.previous_shortfall[code] = np.maximum(self.previous_shortfall[code] - value, 0)
        else:
            lots = np.rint(np.asarray(shares) / 1000).astype(int)
            self.local_day_lots += lots
            self.local_week_lots += lots


def load_global_build_policy(root=ROOT):
    cadence = json.loads((Path(root) / "assumptions/model_inputs.json").read_text())[
        "execution_policy_overrides"]["global_equity_build_cadence_d141"]
    return {**cadence["009826"], "fx_halving_threshold": cadence["fx_halving_threshold"]}


def global_build_api_inputs(assets, fx, trading_date):
    """Holding value, total assets and month-to-date USD/TWD mid rates from saved API responses.

    Anything missing or malformed becomes None / [] so global_build_009826 fails closed.
    """
    live = assets.get("liveAssets") if isinstance(assets, dict) else None
    total = live.get("liveTotalAssets") if isinstance(live, dict) else None
    stocks = live.get("stocks") if isinstance(live, dict) else None
    held = None
    if isinstance(stocks, list):
        rows = [r.get("liveValue") for r in stocks
                if isinstance(r, dict) and r.get("stockCode") == "009826" and r.get("market") == "台股"]
        if all(_finite_positive(v) or v == 0 for v in rows) and not any(isinstance(v, bool) for v in rows):
            held = float(sum(rows))
    rates = []
    if isinstance(fx, dict) and _iso_date(trading_date):
        month = trading_date[:7]
        rates = [r["midRate"] for r in fx.get("history") or []
                 if isinstance(r, dict) and isinstance(r.get("date"), str) and r["date"][:7] == month
                 and r["date"] <= trading_date and _finite_positive(r.get("midRate"))]
        spot = fx.get("spot")
        if (isinstance(spot, dict) and spot.get("date") == trading_date and spot.get("quoteStatus") == "LIVE"
                and _finite_positive(spot.get("midRate"))):
            rates.append(spot["midRate"])
    return {"held_value_twd": held, "total_assets_twd": total if _finite_positive(total) else None,
            "usd_twd_rates": rates}


def global_build_009826(quote, *, trading_date, held_value_twd, total_assets_twd, transactions,
                        usd_twd_rates, policy):
    """D-143: the deterministic 009826 build-lot ceiling for one report round.

    The build is a strategic accumulation with no price trigger: while 009826 is
    below its 5% target and the window is open, the ceiling is the pace room
    (1 lot per day, 3 per trading week, the monthly TWD cap), not an optional
    maximum the model may shrink or round away. Only confirmed buy fills consume
    pace; unfilled proposals consume nothing, so the 09:05 and 12:00 rounds of
    one day propose the same single lot rather than two.

    This is a ceiling, never an order. The caller still applies the D-188 gate,
    calendar, price freshness, best-five depth, exact radar detail and direction,
    tradability HIT, dedupe and post-buy deposit floors; the LLM may WAIT or VETO
    with a named reason but never raises the lots. Unverifiable pace, quote,
    holding or total-assets evidence gives 0 lots; unknown FX uses the halved cap.
    """
    code, lot = "009826", 1000
    blocks, today = [], dt.date.fromisoformat(trading_date)
    month_start, week_start = today.replace(day=1), today - dt.timedelta(days=today.weekday())
    window_end = policy["window"].split(" to ")[1]
    if trading_date < policy["earliest_buy_date"]:
        blocks.append("BEFORE_EARLIEST_BUY_DATE")
    if trading_date > window_end:
        blocks.append("BUILD_WINDOW_CLOSED")

    book = quote.get("askLevels") if isinstance(quote, dict) else None
    ask = book[0].get("price") if isinstance(book, list) and book and isinstance(book[0], dict) else None
    identity = (isinstance(quote, dict) and quote.get("stockCode") == code
                and quote.get("market") == "台股" and quote.get("tradingDate") == trading_date)
    basis, reference = ("ASK1", ask) if _finite_positive(ask) else ("LAST_PRICE", quote.get("price") if identity else None)
    if not identity or not _finite_positive(reference):
        blocks.append("QUOTE_UNVERIFIED")
        reference = None
    if not _finite_positive(total_assets_twd):
        blocks.append("TOTAL_ASSETS_UNVERIFIED")
    if not (isinstance(held_value_twd, (int, float)) and not isinstance(held_value_twd, bool)
            and bool(np.isfinite(held_value_twd)) and held_value_twd >= 0):
        blocks.append("HOLDING_UNVERIFIED")

    selection = transactions.get("selection") if isinstance(transactions, dict) else None
    records = transactions.get("records") if isinstance(transactions, dict) else None
    start, end = (selection.get("start"), selection.get("end")) if isinstance(selection, dict) else (None, None)
    day_lots = week_lots = month_amount = 0.
    pace_ok = (_iso_date(start) and _iso_date(end) and start <= min(month_start, week_start).isoformat()
               and end >= trading_date and isinstance(records, list))
    for r in (records if pace_ok else []):
        if not isinstance(r, dict):
            pace_ok = False
            continue
        if r.get("assetCode") != code or r.get("market") != "台股" or r.get("transactionType") != "買":
            continue
        shares, amount, date = r.get("shares"), r.get("amount"), r.get("tradeDate")
        if not (_finite_positive(shares) and _finite_positive(amount) and _iso_date(date)):
            pace_ok = False
            continue
        if date > trading_date:
            continue
        month_amount += amount if date >= month_start.isoformat() else 0.
        week_lots += shares / lot if date >= week_start.isoformat() else 0.
        day_lots += shares / lot if date == trading_date else 0.
    if not pace_ok:
        blocks.append("PACE_STATE_UNVERIFIED")

    rates = [x for x in usd_twd_rates or [] if _finite_positive(x)]
    halved = max(rates) > policy["fx_halving_threshold"] if rates else True
    factor = .5 if halved else 1.
    cap, baseline = policy["monthly_cap_twd"] * factor, policy["monthly_baseline_twd"] * factor
    room = {}
    if pace_ok:
        room["day"] = policy["max_lots_per_day"] - day_lots
        room["week"] = policy["max_lots_per_week"] - week_lots
        if room["day"] < 1 - 1e-9:
            blocks.append("DAILY_LOT_LIMIT")
        if room["week"] < 1 - 1e-9:
            blocks.append("WEEKLY_LOT_LIMIT")
    post_buy_weight = None
    if reference is not None:
        lot_value = lot * reference
        room["month"] = (cap - month_amount) / lot_value
        if pace_ok and room["month"] < 1 - 1e-9:
            blocks.append("MONTHLY_CAP")
        if _finite_positive(total_assets_twd) and "HOLDING_UNVERIFIED" not in blocks:
            room["weight"] = (policy["stop_at_weight"] * total_assets_twd - held_value_twd) / lot_value
            post_buy_weight = (held_value_twd + lot_value) / total_assets_twd
            if room["weight"] < 1 - 1e-9:
                blocks.append("TARGET_WEIGHT_REACHED")
    lots = 0 if blocks else max(min(int(np.floor(v + 1e-9)) for v in room.values()), 0)
    return {"decision": "D-143", "stock_code": code, "trading_date": trading_date, "places_orders": False,
            "status": "BUILD_LOT_CEILING" if lots else "BUILD_ZERO", "lots": lots, "shares": lots * lot,
            "reasons": blocks or ["WITHIN_PACE_AND_TARGET"],
            "evidence": {"reference_price": reference, "reference_basis": basis,
                         "day_lots": day_lots, "week_lots": week_lots, "month_amount_twd": month_amount,
                         "week_start": week_start.isoformat(), "month_start": month_start.isoformat(),
                         "fx_halved": halved, "fx_status": "VERIFIED" if rates else "UNVERIFIED_ASSUMED_HALVED",
                         "fx_month_max_mid": max(rates) if rates else None,
                         "monthly_cap_twd_effective": cap, "monthly_baseline_twd_effective": baseline,
                         "held_value_twd": held_value_twd, "total_assets_twd": total_assets_twd,
                         "post_buy_weight": post_buy_weight, "stop_at_weight": policy["stop_at_weight"],
                         "max_lots_per_day": policy["max_lots_per_day"],
                         "max_lots_per_week": policy["max_lots_per_week"]}}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, help="JSON sell request, saved /api/quotes/one response with --topup-tier, or D-195 request with --bond-price-select")
    parser.add_argument("--topup-tier", action="store_true", help="D-170 lot ceiling for high-dividend top-ups")
    parser.add_argument("--bond-price-select", action="store_true",
                        help="D-195 price-based D-130 strategic pick and emergency 00865B lots; input is a JSON request")
    parser.add_argument("--global-build-009826", action="store_true",
                        help="D-143 009826 build-lot ceiling; input is a JSON request naming saved quote/assets/transactions/fx files")
    parser.add_argument("--stock-code")
    parser.add_argument("--market")
    parser.add_argument("--trading-date", help="today's calendar-verified Taiwan trading date")
    parser.add_argument("--completed-session", help="latest completed YYYY-MM-DD, already verified against the trading calendar")
    args = parser.parse_args()
    selectors = (args.stock_code, args.market, args.trading_date, args.completed_session)
    if args.bond_price_select:
        if any(selectors):
            parser.error("--bond-price-select takes all selectors from the JSON request")
        request = json.loads(args.input.read_text())
        base_dir = args.input.parent
        quotes = {code: json.loads((base_dir / path).read_text()) for code, path in request["quote_files"].items()}
        result = bond_price_selection(quotes, trading_date=request["trading_date"],
                                      completed_session=request["completed_session"],
                                      policy=load_bond_price_policy(),
                                      strategic_candidates=request.get("strategic_candidates"),
                                      emergency_gap_twd=request.get("emergency_gap_twd"))
    elif args.global_build_009826:
        if any(selectors):
            parser.error("--global-build-009826 takes all selectors from the JSON request")
        request = json.loads(args.input.read_text())
        base_dir = args.input.parent
        saved = {key: json.loads((base_dir / request[f"{key}_file"]).read_text())
                 for key in ("quote", "assets", "transactions", "fx")}
        api = global_build_api_inputs(saved["assets"], saved["fx"], request["trading_date"])
        result = global_build_009826(saved["quote"], trading_date=request["trading_date"],
                                     held_value_twd=api["held_value_twd"], total_assets_twd=api["total_assets_twd"],
                                     transactions=saved["transactions"], usd_twd_rates=api["usd_twd_rates"],
                                     policy=load_global_build_policy())
    elif args.topup_tier:
        if not all(selectors):
            parser.error("--topup-tier requires --stock-code, --market, --trading-date and --completed-session")
        result = daily_topup_tier(json.loads(args.input.read_text()), stock_code=args.stock_code,
                                  market=args.market, trading_date=args.trading_date,
                                  completed_session=args.completed_session, policy=load_topup_policy())
    else:
        if any(selectors):
            parser.error("selector arguments require --topup-tier")
        result = sell_request(json.loads(args.input.read_text()))
    print(json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False))
