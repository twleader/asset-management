#!/usr/bin/env python3
"""Deterministic original-SRPP D222 selector cases, without registry or market I/O."""
import argparse
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "backend/src/test/resources/srpp/reserve-allocation-golden.json"


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def wrap(payload):
    return {"source_ref": "isolated-synthetic-original", "sha256": hashlib.sha256(canonical(payload).encode()).hexdigest(), "payload": payload}


def candidate(code, price, side, capacity=2000000):
    facts = {"stock_code": code, "trading_date": "2026-08-31", "price": price, "low52": 10, "high52": 110, "adjustment_basis": "DISTRIBUTION_ADJUSTED_52_WEEK"}
    receipts = {}
    for gate in ("FX", side):
        receipts[gate] = wrap({"stock_code": code, "trading_date": "2026-08-31", "scope": gate, "status": "VERIFIED_PASS", "source_evidence": [wrap({"synthetic_original": True})]})
    return {"source": wrap(facts), "gate_receipts": receipts, "capacity_twd": capacity}


def generate(srpp_root):
    spec = importlib.util.spec_from_file_location("srpp_reserve", srpp_root / "scripts/reserve_allocation.py")
    worker = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(worker)
    source = json.loads((srpp_root / "assumptions/model_inputs.json").read_text())
    model = {"execution_policy": {"reserve_allocation_d222": source["execution_policy"]["reserve_allocation_d222"]},
             "detailed_target_allocation_2027": source["detailed_target_allocation_2027"],
             "recommended_total_allocation": source["recommended_total_allocation"]}
    buys = [candidate("VOO", 20, "BUY"), candidate("0050", 50, "BUY"), candidate("00881", 80, "BUY")]
    sells = [candidate("VOO", 20, "SELL"), candidate("0050", 50, "SELL"), candidate("00881", 80, "SELL")]
    cases = []

    def add(name, total, candidates, mode="INITIAL_ALLOCATION", living=None):
        expected = worker.select_market_adjustment(model, total, "2026-08-31", candidates, adjustment_mode=mode, living_reserve=living)
        cases.append({"name": name, "total": total, "date": "2026-08-31", "mode": mode,
                      "candidates": copy.deepcopy(candidates), "living": living, "expected": expected})

    add("initial_buy_lowest", 30000000, buys)
    add("initial_sell_highest", 20000000, sells)
    partial = copy.deepcopy(buys); partial[0]["capacity_twd"] = 1000000
    add("selected_capacity_no_reflow", 30000000, partial)
    tie = copy.deepcopy(buys); tie[1] = candidate("0050", 20, "BUY")
    add("position_tie", 30000000, tie)
    add("missing_third_range", 30000000, buys[:2])
    tampered = copy.deepcopy(buys); tampered[0]["source"]["sha256"] = "0" * 64
    add("tampered_range_hash", 30000000, tampered)
    blocked = copy.deepcopy(buys)
    payload = blocked[0]["gate_receipts"]["FX"]["payload"]; payload["status"] = "VERIFIED_BLOCKED"
    blocked[0]["gate_receipts"]["FX"] = wrap(payload)
    add("lowest_fx_blocked_select_next_eligible_with_original_cap", 30000000, blocked)
    no_fx = copy.deepcopy(buys); no_fx[0]["gate_receipts"].pop("FX")
    add("missing_fx_original", 30000000, no_fx)
    add("living_missing_ledger", 30000000, sells, "LIVING_RESERVE_REBALANCE")
    living = {"valuation_date": "2026-08-31", "ledger_scope": "LIVING_RESERVE_EXCLUDING_EMERGENCY", "reconciled_deposit_ids": ["term-1", "demand-1"], "living_term_twd": 1000000, "living_demand_twd": 1000000, "settled_unrestricted_available_twd": 0}
    add("living_deficit_uses_ledger_not_reference_cash", 30000000, sells, "LIVING_RESERVE_REBALANCE", wrap(living))
    living = {**living, "living_term_twd": 2000000, "settled_unrestricted_available_twd": 100000}
    add("living_surplus_settled_capacity", 30000000, buys, "LIVING_RESERVE_REBALANCE", wrap(living))
    living = {**living, "settled_unrestricted_available_twd": 1000000}
    buy_0050 = [candidate("VOO", 50, "BUY"), candidate("0050", 20, "BUY"), candidate("00881", 80, "BUY")]
    buy_00881 = [candidate("VOO", 50, "BUY"), candidate("0050", 80, "BUY"), candidate("00881", 20, "BUY")]
    add("living_0050_uses_current_capacity_not_initial_baseline_room", 30000000, buy_0050, "LIVING_RESERVE_REBALANCE", wrap(living))
    add("living_00881_uses_current_capacity_not_initial_baseline_room", 30000000, buy_00881, "LIVING_RESERVE_REBALANCE", wrap(living))
    living = {**living, "living_term_twd": 0, "living_demand_twd": 0, "settled_unrestricted_available_twd": 0}
    wide_sells = [candidate("VOO", 20, "SELL", 3000000), candidate("0050", 50, "SELL", 3000000), candidate("00881", 80, "SELL", 3000000)]
    add("living_sell_capacity_can_exceed_initial_baseline_value", 30000000, wide_sells, "LIVING_RESERVE_REBALANCE", wrap(living))
    # Low total assets may make INITIAL target ratios infeasible, while a verified
    # actual living-ledger cash flow still has a valid independent sell or buy.
    low_living = {"valuation_date": "2026-08-31", "ledger_scope": "LIVING_RESERVE_EXCLUDING_EMERGENCY", "reconciled_deposit_ids": ["term-1", "demand-1"], "living_term_twd": 1000000, "living_demand_twd": 1000000, "settled_unrestricted_available_twd": 0}
    low_sells = [candidate("VOO", 20, "SELL", 50000), candidate("0050", 50, "SELL", 50000), candidate("00881", 80, "SELL", 300000)]
    for total in (4000000, 6000000):
        add("low_assets_living_sell_" + str(total), total, low_sells, "LIVING_RESERVE_REBALANCE", wrap(low_living))
    low_living = {**low_living, "living_term_twd": 1550000, "living_demand_twd": 750000, "settled_unrestricted_available_twd": 50000}
    low_buys = [candidate("VOO", 20, "BUY", 50000), candidate("0050", 50, "BUY", 50000), candidate("00881", 80, "BUY", 50000)]
    for total in (4000000, 6000000):
        add("low_assets_living_buy_" + str(total), total, low_buys, "LIVING_RESERVE_REBALANCE", wrap(low_living))
    return json.dumps({"schema": "D222_ORIGINAL_SRPP_SELECTOR_GOLDEN_V1", "model": model, "cases": cases}, ensure_ascii=False, sort_keys=True, indent=2) + "\n"


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--srpp-root", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    value = generate(args.srpp_root)
    if args.check:
        if not TARGET.exists() or TARGET.read_text() != value:
            raise SystemExit("D222_SELECTOR_GOLDEN_DRIFT")
    else:
        TARGET.write_text(value)
    print("D222_SELECTOR_GOLDEN_PASS:" + str(len(json.loads(value)["cases"])))
