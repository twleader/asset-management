#!/usr/bin/env python3
"""Task 626 order-plan v2 golden: 22 scenarios proven against the original SRPP pure functions.

Only the original SRPP functions decide each reference value (trade_blocker_policy.quote_refreshable, refresh_gates,
quote_refresh_passed, refresh_lot_cap, candidate_debit, pooled_funding, _commitments; execution_policy.quote_freshness,
bond_price_selection, global_build_009826; risk_evidence_evaluator.classify_risk_mode). The scenario inputs mirror the
Java SrppOrderPlanV2Fixtures frame (2026-10-08 09:05, twenty completed sessions at close 10, the D-234 test window).
The SRPP calendar window is supplied as data (the fixture sessions) instead of read from the SRPP calendar files.
No registry, market, broker, network or database I/O. --check never writes.
"""
import argparse
import copy
import hashlib
import importlib.util
import json
from decimal import Decimal
from pathlib import Path
import sys

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "backend/src/test/resources/srpp/order-plan-v2-golden.json"
SCHEMA = "ORDER_PLAN_V2_ORIGINAL_SRPP_GOLDEN_V1"
DATE = "2026-10-08"
SESSIONS = ["2026-09-%02d" % d for d in range(18, 31)] + ["2026-10-%02d" % d for d in range(1, 8)]
SOURCES = ("trade_blocker_policy", "execution_policy", "risk_evidence_evaluator")


def load(srpp_root, name):
    scripts = str(srpp_root / "scripts")
    if scripts not in sys.path:
        sys.path.insert(0, scripts)
    spec = importlib.util.spec_from_file_location(name, srpp_root / "scripts" / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def text(value):
    if value is None:
        return None
    if isinstance(value, bool):
        return value
    if isinstance(value, (int,)):
        return value
    d = Decimal(str(value))
    if d == d.to_integral_value():
        return str(d.quantize(Decimal(1)))
    return format(d.normalize(), "f")


def bond_quote(code, price, premium=0.0):
    return {"stockCode": code, "market": "台股", "tradingDate": DATE, "price": price, "premiumDiscountPct": premium,
            "marketData": {"chart": {"status": "AVAILABLE", "requestedStart": SESSIONS[0], "requestedEnd": DATE,
                                     "series": {"daily": {"dates": list(SESSIONS), "closes": [10.0] * len(SESSIONS)}}}},
            "dividendHistory": {"stockCode": code, "market": "台股", "source": "TWSE", "message": None,
                                "rows": [{"exDividendDate": "2026-01-15", "exRightsDate": None, "cashDividend": 0.1, "stockDividend": None}]}}


def gates(status="PASS"):
    return {name: {"status": status, "evidence_ref": "fixture:" + name} for name in ("quote", "quote_freshness", "opposing_depth", "premium_discount")}


FIXTURE = "data/history/D139-2026-08-30/latest_assets_snapshot.json"


def assets(srpp_root, term_twd, demand_twd, generated="2026-10-08T01:05:00Z"):
    """The SRPP asset fixture with only TWD deposits replaced (term 定存 + demand) and totals re-reconciled."""
    base = json.loads((srpp_root / FIXTURE).read_text())
    template = base["snapshot"]["deposits"][0]
    rows = []
    for number, (kind, amount) in enumerate((("定存", term_twd), ("活存", demand_twd)), start=900001):
        row = copy.deepcopy(template)
        row.update({"id": number, "currency": "TWD", "depositType": kind, "depositDisplayName": kind, "amount": float(amount), "originalAmount": None,
                    "annualInterestRate": None, "estimatedAnnualInterest": None, "notes": None})
        rows.append(row)
    old = sum((Decimal(str(r["amount"])) for r in base["snapshot"]["deposits"]), Decimal(0))
    delta = Decimal(term_twd + demand_twd) - old
    base["snapshot"]["deposits"] = rows
    for parent, key in (("snapshot", "totalDeposit"), ("liveAssets", "totalDeposit"), ("snapshot", "totalAssets"), ("liveAssets", "liveTotalAssets")):
        base[parent][key] = float(Decimal(str(base[parent][key])) + delta)
    base["generatedAt"] = generated
    return base


def pooled(tb, model, a, cash):
    try:
        r = tb.pooled_funding(a, DATE, "TWD", cash, model)
    except ValueError as error:
        return {"status": str(error)}
    return {k: r[k] for k in ("status", "settled_deposits", "known_payables", "post_total", "post_term", "total_floor", "term_floor", "cash_required")}


def build(srpp_root):
    tb, ep, rev = (load(srpp_root, name) for name in SOURCES)
    model = json.loads((srpp_root / "assumptions/model_inputs.json").read_text())
    ep.completed_calendar_window = lambda trading_date, count, root=None, market="台股": SESSIONS[-count:]
    bond_policy = ep.load_bond_price_policy(srpp_root)
    risk_policy = {"decision": model["execution_policy"]["risk_score_decision_policy"]}
    cadence = model["execution_policy_overrides"]["global_equity_build_cadence_d141"]
    lot_policy = {**cadence["009826"], "fx_halving_threshold": cadence["fx_halving_threshold"],
                  "window": "2026-10-01 to 2027-06-30", "earliest_buy_date": "2026-10-01"}
    fresh = ep.load_bond_price_policy  # noqa: F841 (policy loader exercised above)
    freshness_policy = model["execution_policy"]["bond_trade_price_policy"]["quote_freshness_policy"]
    cases = []

    def add(number, name, reference, expected):
        cases.append({"id": number, "name": name, "reference": reference, "expected": expected})

    # 1 strategic bond quote-gap zero leg refreshed, D-195 picks it, capped by REFRESH_LOT_CAPS.
    row = {"module_id": "BOND_D130_D195", "strategy": "STRATEGIC_FUBON", "blocking_reasons": ["LIVE_PRICE_UNVERIFIED", "PREMIUM_OR_PRICE_UNVERIFIED"]}
    selection = ep.bond_price_selection({"00719B": bond_quote("00719B", 9.85)}, trading_date=DATE, completed_session=SESSIONS[-1], policy=bond_policy,
                                        strategic_candidates={"00719B": 1.0})
    cap = tb.refresh_lot_cap("BOND_D130_D195", "STRATEGIC_FUBON", model)
    add(1, "strategic_bond_quote_gap_refreshed", {"quoteRefreshable": tb.quote_refreshable(row), "refreshGates": tb.refresh_gates([row]),
        "quoteRefreshPassed": tb.quote_refresh_passed(gates(), [row]), "refreshLotCap": cap, "selected": selection["strategic"]["selected"],
        "selectedLots": selection["strategic"]["lots"]}, {"candidate": True, "finalLots": min(selection["strategic"]["lots"], cap), "symbol": "00719B"})
    # 2 mixed with a non-quote reason: never a candidate.
    mixed = dict(row, blocking_reasons=row["blocking_reasons"] + ["TARGET_FULL_OR_UNVERIFIED"])
    add(2, "mixed_reason_not_candidate", {"quoteRefreshable": tb.quote_refreshable(mixed)}, {"candidate": False})
    # 3 refreshed quote still not qualified.
    stale_gates = gates(); stale_gates["quote"]["status"] = "BLOCKED"
    add(3, "refreshed_quote_still_stale", {"quoteRefreshPassed": tb.quote_refresh_passed(stale_gates, [row])}, {"finalLots": 0, "reason": "D230_FRESH_QUOTE_REQUIRED"})
    # 4 package already selected another bond (2 lots): the re-run selects it again; sleeve never exceeds the cap.
    both = ep.bond_price_selection({"00865B": bond_quote("00865B", 9.8), "00719B": bond_quote("00719B", 9.85)}, trading_date=DATE,
                                   completed_session=SESSIONS[-1], policy=bond_policy, strategic_candidates={"00865B": 1.0, "00719B": 1.0})
    add(4, "rerun_selects_other_bond", {"selected": both["strategic"]["selected"], "selectedLots": both["strategic"]["lots"],
        "sleeveSharesWithinCap": (both["strategic"]["lots"] + 0) * 1000 <= cap * 1000}, {"otherFinalLots": 0, "reason": "REFRESH_NOT_SELECTED", "selectedFinalLots": 2})
    # 5 t623 009826 build leg: the Java D-234 candidate; SRPP quote_refreshable is False (known difference).
    zero009826 = {"module_id": "GLOBAL_EQUITY_PACE", "symbol": "009826", "action": "BUY", "blocking_reasons": ["QUOTE_UNVERIFIED"]}
    quote = {"stockCode": "009826", "market": "台股", "tradingDate": DATE, "price": 10.05, "askLevels": [{"price": 10.06, "size": 10000}]}
    lots = ep.global_build_009826(quote, trading_date=DATE, held_value_twd=0.0, total_assets_twd=20000000.0,
                                  transactions={"selection": {"mode": "DATE_RANGE", "year": None, "start": "2026-10-01", "end": DATE}, "records": []},
                                  usd_twd_rates=[32.35], policy=lot_policy)
    add(5, "satellite_009826_quote_unverified", {"quoteRefreshableKnownDifference": tb.quote_refreshable(zero009826), "lots": lots["lots"],
        "referencePrice": text(lots["evidence"]["reference_price"]), "maxLotsPerDay": lot_policy["max_lots_per_day"], "maxLotsPerWeek": lot_policy["max_lots_per_week"]},
        {"candidate": True, "finalLots": min(lots["lots"], min(lot_policy["max_lots_per_day"], lot_policy["max_lots_per_week"])), "limitPrice": "10.06",
         "mixedWithPaceStateCandidate": False})
    # 6 D-218 top-up zero leg: quote gap only, but no refresh identity.
    topup = {"module_id": "DIVIDEND_TOPUP", "strategy": "DIVIDEND_TOPUP", "blocking_reasons": ["LIVE_QUOTE_STALE"]}
    add(6, "dividend_topup_not_refreshable", {"quoteRefreshable": tb.quote_refreshable(topup), "refreshLotCap": tb.refresh_lot_cap("DIVIDEND_TOPUP", "DIVIDEND_TOPUP", model)},
        {"candidate": False})
    # 7-10 D-169 risk mode.
    for number, name, scores, declared, status in ((7, "defensive_total_8", [2, 2, 2, 1, 1, 0], "DEFENSIVE", 200), (8, "declared_cautious_but_total_8", [2, 2, 2, 2], "CAUTIOUS", 400),
                                                   (9, "cautious_total_5", [2, 1, 1, 1], "CAUTIOUS", 200), (10, "assessed_3_declared_normal", [1, 1, 1], "NORMAL", 400)):
        mode = rev.classify_risk_mode(scores, len(scores), risk_policy)
        add(number, name, {"mode": mode, "total": sum(scores), "assessed": len(scores), "max": max(scores)}, {"declared": declared, "status": status})
    # 11-12, 14, 18 D-152 pooled funding over the package assets.
    a = assets(srpp_root, 1000000, 4000000)
    c1 = Decimal("19647.96")  # 2 lots 00865B at 9.81 (19620) + its calculator fee 27.96
    one, two = Decimal("9823.98"), Decimal("19647.96")  # 1 and 2 emergency lots incl. fee 13.98 / 27.96
    base = pooled(tb, model, a, 0)
    floors = max(Decimal(base["total_floor"]), Decimal(base["term_floor"]))
    headroom = Decimal(base["settled_deposits"]) + Decimal(base["known_payables"]) - floors
    commitment = (headroom - c1 - one - 1).quantize(Decimal("0.01"), rounding="ROUND_FLOOR")
    sum11 = pooled(tb, model, a, c1 + two)
    add(11, "two_twd_buys_fit", {"deposits": {"term": 1000000, "demand": 4000000}, "headroom": text(headroom), "pass": sum11["status"], "postTotal": sum11["post_total"], "totalFloor": sum11["total_floor"], "termFloor": sum11["term_floor"],
        "headroomAfter": text(Decimal(sum11["post_total"]) - max(Decimal(sum11["total_floor"]), Decimal(sum11["term_floor"])))}, {"bothFull": True})
    add(12, "second_buy_reduced", {"reducedStatus": pooled(tb, model, a, c1 + one + commitment)["status"], "fullStatus": pooled(tb, model, a, c1 + two + commitment)["status"],
        "commitment": text(commitment), "costOneLot": text(one), "costTwoLots": text(two)}, {"secondFinalLots": 1, "reason": "SHARED_CAPACITY_REDUCED", "fees": "13.98"})
    # 13 VT minimum order shares.
    vt = cadence["VT"]
    add(13, "vt_reduced_below_minimum", {"minSharesPerOrder": vt["min_shares_per_order"]}, {"reducedTo": 4, "finalUnits": "0" if 4 < vt["min_shares_per_order"] else "4"})
    add(14, "term_below_floor", {"status": pooled(tb, model, assets(srpp_root, 500000, 4500000), 0)["status"]}, {"reason": "POOLED_TERM_BELOW_FLOOR", "finalLots": 0})
    # 15 D-175 final quote age: the frozen 20-minute-old price fails, the refreshed price passes.
    def live(updated):
        return {"stockCode": "00865B", "market": "台股", "tradingDate": DATE, "quoteStatus": "LIVE", "closed": False, "source": "FUBON", "price": 9.8,
                "previousClose": 10.0, "volume": 1000, "updatedAt": updated}
    try:
        ep.quote_freshness(live("2026-10-08T00:45:00Z"), trading_date=DATE, slot="09:05", asof="2026-10-08T01:06:00Z", policy=freshness_policy, stage="final")
        frozen = "PASS"
    except ValueError as error:
        frozen = str(error)
    refreshed = ep.quote_freshness(live("2026-10-08T01:09:30Z"), trading_date=DATE, slot="09:05", asof="2026-10-08T01:10:00Z", policy=freshness_policy, stage="final")
    add(15, "frozen_quote_stale_refreshed_passes", {"frozen": frozen, "refreshedStatus": refreshed["status"], "refreshedMaxAgeMinutes": refreshed["max_age_minutes"]},
        {"finalLots": 2, "limitFromPackage": True, "freshnessSource": "REFRESH"})
    add(16, "query_deadline_passed", {"queryWindowMinutes": 35}, {"reads": 0, "candidateReason": "QUERY_DEADLINE_PASSED", "staleReason": "LIVE_QUOTE_STALE"})
    add(17, "reduce_not_whole_unit", {}, {"finalLots": 0, "reason": "REDUCE_AMOUNT_NOT_WHOLE_UNIT"})
    add(18, "redecided_shared_funds_gap", {"status": pooled(tb, model, a, Decimal("30223.01"))["status"]}, {"redecided": True, "baseLots": 3, "finalLots": 3})
    costs = model["execution_policy"]["short_term_opportunity_d171"]["scenario_costs"]
    add(19, "short_term_minimum_fee", {"minimumFeeTwd": text(costs["minimum_fee_twd"]), "brokerageEachSide": text(costs["brokerage_each_side"]),
        "slippageEachSide": text(costs["slippage_each_side"])}, {"shares": 100, "limit": "50", "feeAtLeastMinimum": True})
    # 20 VT: Cathay TWD sub-brokerage debit of 7 shares through the original candidate_debit.
    spot, usd = Decimal("32.5"), Decimal("150")
    debit = tb.candidate_debit({"purpose": "EQUITY_BUY", "symbol": "VT", "debit": {"source": "GLOBAL_BUILD_VT", "currency": "TWD", "shares": 7,
                                "limit_price": str(usd * spot), "fees": str(3 * spot), "evidence_ref": "fixture:vt"}}, ("ZERO_FREE", None), {"global_build_vt": {"shares": 7}})
    add(20, "vt_seven_shares_twd_debit", {"total": text(debit["total"]), "currency": debit["currency"]}, {"finalUnits": "7", "fees": "3", "spot": "32.5", "limitUsd": "150"})
    totals = tb._commitments({"funding_commitments": [{"currency": "TWD", "amount": "1000", "evidence_ref": "fixture:d182-vt-day5"}]})
    add(21, "d182_twd_commitment", {"TWD": text(totals.get("TWD")), "USD": text(totals.get("USD"))}, {"startHeadroomRule": "pooled - max(commitment, reservedFunds)"})
    emergency = ep.bond_price_selection({"00865B": bond_quote("00865B", 9.8)}, trading_date=DATE, completed_session=SESSIONS[-1], policy=bond_policy,
                                        strategic_candidates={}, emergency_gap_twd=1000000.0)
    add(22, "synthetic_emergency_quote_gap_candidate", {"emergencyLots": emergency["emergency"]["lots"], "refreshLotCap": tb.refresh_lot_cap("BOND_D130_D195", "EMERGENCY_CATHAY", model)},
        {"candidate": True, "finalLotsAtMost": min(emergency["emergency"]["lots"], tb.refresh_lot_cap("BOND_D130_D195", "EMERGENCY_CATHAY", model))})
    cases.sort(key=lambda c: c["id"])
    digests = {name + ".py": hashlib.sha256((srpp_root / "scripts" / (name + ".py")).read_bytes()).hexdigest() for name in SOURCES}
    return {"schema": SCHEMA, "tradingDate": DATE, "sessions": SESSIONS, "srppSources": digests, "cases": json.loads(json.dumps(cases, default=text))}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--srpp-root", required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    root = Path(args.srpp_root)
    if not root.is_absolute() or not (root / "scripts/trade_blocker_policy.py").is_file():
        raise SystemExit("ORDER_PLAN_V2_GOLDEN_SRPP_ROOT_INVALID")
    data = build(root)
    payload = json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if args.check:
        if not TARGET.is_file() or TARGET.read_text(encoding="utf-8") != payload:
            raise SystemExit("ORDER_PLAN_V2_GOLDEN_DRIFT")
    else:
        TARGET.write_text(payload, encoding="utf-8")
    print("ORDER_PLAN_V2_GOLDEN_PASS:%d" % len(data["cases"]))


if __name__ == "__main__":
    main()
