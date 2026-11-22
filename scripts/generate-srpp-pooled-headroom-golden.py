#!/usr/bin/env python3
"""D-152 pooled post-buy headroom cases, proven against original SRPP pooled_funding (no registry, market or broker I/O)."""
import argparse
import copy
import datetime as dt
from decimal import Decimal
import importlib.util
import json
from pathlib import Path
import sys
sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "backend/src/test/resources/srpp/pooled-post-buy-headroom-golden.json"
FIXTURE = "data/history/D139-2026-08-30/latest_assets_snapshot.json"
CURRENCIES = ("TWD", "USD")
TERM_TYPES = {"定存", "美元定存"}


def load(srpp_root, name):
    scripts = str(srpp_root / "scripts")
    if scripts not in sys.path:
        sys.path.insert(0, scripts)  # the reference imports asset_reconciliation / execution_policy itself
    spec = importlib.util.spec_from_file_location(name, srpp_root / "scripts" / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def text(value):
    return None if value is None else str(value)


def dec(value):
    return Decimal(str(value))


def taipei_date(value, worker):
    return worker.taipei_timestamp(value).date().isoformat()


def expected_for(assets, trading_date, currency, term_base, total_base, index, execution):
    """Task 624.1 formula, computed independently of pooled_funding."""
    try:
        same_round = taipei_date(assets.get("generatedAt"), execution) == trading_date
    except ValueError:
        same_round = False
    if not same_round:
        return {"status": "UNAVAILABLE", "reason": "POOLED_ASSETS_NOT_SAME_ROUND", "settled": None, "knownPayables": None,
                "term": None, "termFloor": None, "totalFloor": None, "headroom": None}
    settled, payables, term = Decimal(0), Decimal(0), Decimal(0)
    for row in assets["snapshot"]["deposits"]:
        if row["currency"] not in {currency, "TRANSIT_" + currency}:
            continue
        amount = dec(row["amount"] if currency == "TWD" else row["originalAmount"])
        if row["currency"].startswith("TRANSIT_"):
            payables += min(amount, Decimal(0))
            continue
        settled += amount
        if row["depositType"] in TERM_TYPES:
            term += amount
    term_floor = Decimal(term_base) * index if currency == "TWD" else Decimal(0)
    total_floor = Decimal(total_base) * index if currency == "TWD" else Decimal(0)
    if term < term_floor:
        status, reason, headroom = "BLOCK", "POOLED_TERM_BELOW_FLOOR", None
    else:
        headroom = settled + payables - max(total_floor, term_floor)
        status, reason = ("PASS", "ALL_BANK_SETTLED_DEPOSITS_D152") if headroom >= 0 else ("BLOCK", "POOLED_TOTAL_BELOW_FLOOR")
    return {"status": status, "reason": reason, "settled": settled, "knownPayables": payables, "term": term,
            "termFloor": term_floor, "totalFloor": total_floor, "headroom": headroom}


def probes_for(headroom):
    if headroom is None or headroom < 0:
        values = [Decimal(0), Decimal(1), Decimal(3000000)]
    else:
        values = [Decimal(0), Decimal(1), headroom, headroom + Decimal("0.0001"),
                  max(headroom - Decimal("0.0001"), Decimal(0)), Decimal(3000000)]
    unique = []
    for value in values:
        if value not in unique:
            unique.append(value)
    return unique


def generate(srpp_root):
    blocker = load(srpp_root, "trade_blocker_policy")
    budget = load(srpp_root, "budget_accounting")
    execution = load(srpp_root, "execution_policy")
    source_model = json.loads((srpp_root / "assumptions/model_inputs.json").read_text())
    live_policy = source_model["execution_policy"]["stock_purchase_funding_policy_d150"]
    base = json.loads((srpp_root / FIXTURE).read_text())
    template = base["snapshot"]["deposits"][0]
    counter = [900000]

    def row(currency, deposit_type, amount, original=None):
        counter[0] += 1
        out = copy.deepcopy(template)
        out.update({"id": counter[0], "currency": currency, "depositType": deposit_type, "depositDisplayName": deposit_type,
                    "amount": amount, "originalAmount": original, "annualInterestRate": None, "estimatedAnnualInterest": None,
                    "notes": None})
        return out

    def assets_with(deposits, generated_at):
        assets = copy.deepcopy(base)
        if deposits is not None:
            old = sum((dec(r["amount"]) for r in assets["snapshot"]["deposits"]), Decimal(0))
            new = sum((dec(r["amount"]) for r in deposits), Decimal(0))
            delta = new - old
            assets["snapshot"]["deposits"] = deposits
            for parent, key in (("snapshot", "totalDeposit"), ("liveAssets", "totalDeposit"),
                                ("snapshot", "totalAssets"), ("liveAssets", "liveTotalAssets")):
                assets[parent][key] = float(dec(assets[parent][key]) + delta)
        assets["generatedAt"] = generated_at
        return assets

    usd_rows = lambda: [row("USD", "美元活存", 10750.0, 335.04), row("USD", "美元定存", 64174.0, 2000.0)]
    cases = []

    def add(name, trading_date, generated_at, deposits, term_base=None, total_base=None):
        term_base = live_policy["twd_term_deposit_floor_twd"] if term_base is None else term_base
        total_base = live_policy["twd_total_deposit_floor_twd"] if total_base is None else total_base
        policy = {**live_policy, "twd_term_deposit_floor_twd": term_base, "twd_total_deposit_floor_twd": total_base}
        model = {"execution_policy": {"stock_purchase_funding_policy_d150": policy}}
        assets = assets_with(deposits, generated_at)
        blocker_assets = copy.deepcopy(assets)
        index_float = budget.reference_price_level(dt.date.fromisoformat(trading_date))
        index = Decimal(str(index_float))
        expected, probes = {}, []
        for currency in CURRENCIES:
            result = expected_for(assets, trading_date, currency, term_base, total_base, index, execution)
            expected[currency] = {k: (text(v) if k not in ("status", "reason") else v) for k, v in result.items()}
            headroom = result["headroom"]
            for probe in probes_for(headroom):
                try:
                    reference = blocker.pooled_funding(blocker_assets, trading_date, currency, str(probe), model)
                except ValueError as error:
                    if result["status"] != "UNAVAILABLE" or str(error) != "FUNDING_SAME_ROUND_ASSETS_REQUIRED":
                        raise SystemExit(f"POOLED_HEADROOM_EQUIVALENCE_FAILED:{name}:{currency}:{probe}") from error
                    probes.append({"currency": currency, "cashRequired": str(probe),
                                   "referenceStatus": "FUNDING_SAME_ROUND_ASSETS_REQUIRED", "post_total": None, "post_term": None})
                    continue
                if result["status"] == "UNAVAILABLE":
                    raise SystemExit(f"POOLED_HEADROOM_EQUIVALENCE_FAILED:{name}:{currency}:{probe}")
                passed = reference["status"] == "PASS"
                if passed != (headroom is not None and probe <= headroom):
                    raise SystemExit(f"POOLED_HEADROOM_EQUIVALENCE_FAILED:{name}:{currency}:{probe}")
                if (Decimal(reference["settled_deposits"]) != result["settled"]
                        or Decimal(reference["known_payables"]) != result["knownPayables"]
                        or Decimal(reference["term_floor"]) != result["termFloor"]
                        or Decimal(reference["total_floor"]) != result["totalFloor"]):
                    raise SystemExit(f"POOLED_HEADROOM_EQUIVALENCE_FAILED:{name}:{currency}:{probe}")
                probes.append({"currency": currency, "cashRequired": str(probe), "referenceStatus": reference["status"],
                               "post_total": reference["post_total"], "post_term": reference["post_term"]})
        if blocker_assets != assets:
            raise SystemExit("POOLED_HEADROOM_REFERENCE_MUTATED_ASSETS:" + name)
        cases.append({"name": name, "tradingDate": trading_date, "generatedAt": generated_at,
                      "floors": {"termBase": str(term_base), "totalBase": str(total_base)},
                      "inflationFactor": str(index_float), "assets": assets, "expected": expected, "probes": probes})

    base_day = "2026-08-31"
    add("fixture_baseline", "2026-11-16", "2026-11-16T01:05:00.123456789Z", None)
    add("base_date_total_floor_exact", base_day, "2026-08-31T09:05:00+08:00",
        [row("TWD", "定存", 900000.0), row("TWD", "活存", 650000.0)] + usd_rows())
    add("term_exactly_at_floor", base_day, "2026-08-31T12:00:00",
        [row("TWD", "定存", 800000.0), row("TWD", "活存", 2000000.0)] + usd_rows())
    add("term_below_floor_by_one", base_day, "2026-08-31T12:00:00+08:00",
        [row("TWD", "定存", 799999.0), row("TWD", "活存", 5000000.0)] + usd_rows())
    add("negative_transit_reduces_headroom", base_day, "2026-08-31T01:05:00Z",
        [row("TWD", "定存", 1000000.0), row("TWD", "活存", 2000000.0),
         row("TRANSIT_TWD", "買股待付款", -300000.0), row("TRANSIT_TWD", "信用卡待付款", -1793.0)] + usd_rows())
    add("positive_transit_excluded", base_day, "2026-08-31T01:05:00Z",
        [row("TWD", "定存", 1000000.0), row("TWD", "活存", 2000000.0), row("TRANSIT_TWD", "交割待收款", 50000.0)] + usd_rows())
    add("negative_headroom_total_shortage", base_day, "2026-08-31T01:05:00Z",
        [row("TWD", "定存", 900000.0), row("TWD", "活存", 500000.0)] + usd_rows())
    add("usd_original_amount_zero_floor", base_day, "2026-08-31T01:05:00Z",
        [row("TWD", "定存", 1000000.0), row("TWD", "活存", 1000000.0), row("USD", "美元活存", 32000.0, 1000.5),
         row("USD", "美元定存", 64174.0, 2000.0), row("TRANSIT_USD", "美元待付款", -320.0, -10.0)])
    add("usd_no_rows", base_day, "2026-08-31T01:05:00Z",
        [row("TWD", "定存", 1000000.0), row("TWD", "活存", 1000000.0)])
    add("twd_no_rows", base_day, "2026-08-31T01:05:00Z", usd_rows())
    add("live_index_2026_11_16", "2026-11-16", "2026-11-16T09:05:00+08:00[Asia/Taipei]",
        [row("TWD", "定存", 1000000.0), row("TWD", "活存", 1234567.0), row("TRANSIT_TWD", "信用卡待付款", -8257.0)] + usd_rows())
    add("synthetic_term_floor_exceeds_total_floor", base_day, "2026-08-31T01:05:00Z",
        [row("TWD", "定存", 2500000.0), row("TWD", "活存", 100000.0), row("TRANSIT_TWD", "買股待付款", -50000.0)] + usd_rows(),
        term_base=2000000, total_base=1000000)
    add("non_exact_term_type_ignored", base_day, "2026-08-31T01:05:00Z",
        [row("TWD", "定存單", 2000000.0), row("TWD", "定存", 700000.0), row("TWD", "活存", 500000.0)] + usd_rows())
    add("deposit_type_prefix_is_display_only", base_day, "2026-08-31T01:05:00Z",
        [row("TWD", "TRANSIT_DISPLAY", 100000.0), row("TWD", "定存", 1000000.0), row("TWD", "活存", 1000000.0)] + usd_rows())
    stale_rows = lambda: [row("TWD", "定存", 1000000.0), row("TWD", "活存", 2000000.0)] + usd_rows()
    add("stale_assets_taipei_boundary", "2026-11-16", "2026-11-15T15:59:59Z", stale_rows())
    add("stale_assets_taipei_boundary_same_round", "2026-11-16", "2026-11-15T16:30:00Z", stale_rows())
    return json.dumps({"schema": "D152_POOLED_HEADROOM_GOLDEN_V1", "cases": cases},
                      ensure_ascii=False, sort_keys=True, indent=2) + "\n"


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--srpp-root", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    value = generate(args.srpp_root.resolve())
    if args.check:
        if not TARGET.exists() or TARGET.read_text() != value:
            raise SystemExit("POOLED_HEADROOM_GOLDEN_DRIFT")
    else:
        TARGET.write_text(value)
    print("POOLED_HEADROOM_GOLDEN_PASS:" + str(len(json.loads(value)["cases"])))
