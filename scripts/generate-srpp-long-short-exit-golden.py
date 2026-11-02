#!/usr/bin/env python3
"""Offline full-holding-period parity from the original SRPP fixture and screen."""
from __future__ import annotations

import argparse
import copy
import hashlib
import importlib
import json
import os
from pathlib import Path
import subprocess
import sys

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
EMBEDDED = Path("backend/src/main/resources/srpp/decision")
GOLDEN = Path("backend/src/test/resources/srpp/report-long-short-exit-golden.json")
SOURCES = ("short_term_opportunities.py", "short_term_sleeve.py", "execution_policy.py",
           "test_long_short_term_exit.py", "test_short_term_sleeve.py")
POSITION_FIELDS = ("reference_date", "entry_date", "holding_sessions", "shares", "exit_status",
                   "exit_reasons", "locked_adjusted_support", "locked_adjusted_pressure",
                   "exit_share_ceiling", "pending_exit_shares", "remaining_exit_room")


def fail(reason: str) -> None:
    raise ValueError("SRPP_LONG_SHORT_EXIT_" + reason)


def generate(srpp_root: Path, asset_root: Path = ROOT) -> bytes:
    if not srpp_root.is_absolute() or not (srpp_root / "assumptions/model_inputs.json").is_file():
        fail("INVALID_ROOT:--srpp-root must be an existing absolute SRPP root")
    source = srpp_root.resolve()
    result = subprocess.run(
        [sys.executable, "-B", str(source / "scripts/policy_bundle.py"), "check", "--purpose", "daily", "--consumer", "Codex"],
        cwd=source, env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"}, capture_output=True, check=False,
    )
    if result.returncode:
        fail("OFFICIAL_POLICY_BUNDLE_CHECK_FAILED")
    receipt = json.loads(result.stdout)
    manifest = json.loads((source / "assumptions/policy_manifest.json").read_bytes())
    bundle = manifest.get("POLICY_BUNDLE_SHA256")
    if receipt.get("status") != "PASS" or not isinstance(bundle, str) or receipt.get("POLICY_BUNDLE_SHA256") != bundle:
        fail("OFFICIAL_BUNDLE_MISMATCH")
    for name in ("model_inputs.json", "policy_manifest.json"):
        if (asset_root / EMBEDDED / name).read_bytes() != (source / "assumptions" / name).read_bytes():
            fail("EMBEDDED_AUTHORITY_BYTES_MISMATCH:" + name)
    sys.path.insert(0, str(source / "scripts"))
    fixture_module = importlib.import_module("test_long_short_term_exit")
    short = importlib.import_module("short_term_opportunities")
    helpers = importlib.import_module("test_short_term_sleeve")
    for module in (importlib.import_module(Path(name).stem) for name in SOURCES):
        if Path(module.__file__).resolve().parent != source / "scripts":
            fail("SOURCE_MODULE_IDENTITY_MISMATCH")
    fixture = fixture_module.LongShortTermExitTests()
    fixture.setUp()
    cases = []
    try:
        def add(name: str, data: dict) -> None:
            expected = short.screen(data, fixture.inputs, "bond_short_term_opportunity_d185")
            # Java consumes the same saved, verified synthetic calendar facts.
            data["calendarVerified"] = True
            data["currentSessionVerified"] = True
            data["completedSessions"] = [d for d in fixture.sessions if d < data["trading_date"]]
            sleeve = expected["sleeve"]
            positions = [{k: p[k] for k in POSITION_FIELDS if k in p} for p in sleeve["positions"]]
            rows = [{k: row[k] for k in ("stock_code", "confirmed_short_shares", "core_weight", "core_allocation_full",
                                        "share_ceiling_before_funding", "buy_budget_ceiling_before_funding_twd",
                                        "may_recommend_after_daily_gates") if k in row}
                    for row in expected["results"]]
            cases.append({"name": name, "input": data, "expected": {
                "places_orders": expected["places_orders"], "results": rows,
                "sleeve": {"status": sleeve["status"], "new_buys_frozen": sleeve["new_buys_frozen"], "positions": positions},
            }})

        for code in ("00679B", "00751B"):
            for count in (61, 119, 120):
                add(f"{code}_{count}_sessions", fixture.fixture(count, code))
        add("old_distribution_adjusts_locked_references", fixture.fixture(cash_event=True))
        data = fixture.fixture()
        chart = data["items"][0]["quote"]["marketData"]["chart"]
        daily = chart["series"]["daily"]
        start = daily["dates"].index("2026-09-28")
        daily["dates"] = daily["dates"][start:]
        daily["closes"] = daily["closes"][start:]
        chart["requestedStart"] = daily["dates"][0]
        add("complete_history_from_locked_reference", data)
        data = fixture.fixture()
        daily = data["items"][0]["quote"]["marketData"]["chart"]["series"]["daily"]
        index = daily["dates"].index("2026-10-01")
        daily["dates"].pop(index)
        daily["closes"].pop(index)
        add("missing_old_holding_bar", data)
        data = fixture.fixture(cash_event=True)
        history = data["items"][0]["quote"]["dividendHistory"]["rows"]
        history.append({**history[-1], "cashDividend": 2.0})
        add("conflicting_old_distribution", data)
        for count in (119, 120):
            data = fixture.fixture(count)
            data["items"][0]["radar"]["stock"]["action"] = "BUY_CANDIDATE"
            add(f"buy_direction_{count}_sessions", data)
        # Sunday reference remains before the Monday fill and after activation.
        data = fixture.fixture(125)
        data["sleeve_ledger"]["assignments"] = []
        data["sleeve_ledger"]["positions"] = []
        data["transactions"]["records"] = []
        helpers.add_position(data, code="00679B", reference_date="2026-10-04", support=45.0, pressure=60.0)
        helpers.add_fill(data, helpers.transaction(code="00679B", shares=1000, trade_date="2026-10-05"), strategy="BOND_SHORT_TERM_D185")
        helpers.refresh_receipt(data)
        add("non_session_locked_reference", data)
        data = fixture.fixture(cash_event=True)
        history = data["items"][0]["quote"]["dividendHistory"]["rows"]
        history.append(copy.deepcopy(history[-1]))
        add("duplicate_old_distribution_counted_once", data)
        data = fixture.fixture(cash_event=True)
        data["items"][0]["quote"]["dividendHistory"]["rows"][-1]["stockDividend"] = 1.0
        add("old_stock_distribution_unsupported", data)
    finally:
        fixture.doCleanups()
    output = {
        "schema": "SRPP_LONG_SHORT_EXIT_GOLDEN_V1", "policyBundleSha256": bundle,
        "originalCalculatorHashes": {name: hashlib.sha256((source / "scripts" / name).read_bytes()).hexdigest() for name in SOURCES},
        "cases": cases,
    }
    return (json.dumps(output, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--srpp-root", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args(argv)
    try:
        rendered = generate(args.srpp_root)
        path = ROOT / GOLDEN
        if args.check:
            if not path.is_file() or path.read_bytes() != rendered:
                fail("GOLDEN_BYTES_MISMATCH")
            print("15 original full-holding-period parity cases match")
        else:
            path.write_bytes(rendered)
            print("15 original full-holding-period parity cases written")
    except (ValueError, OSError, KeyError, TypeError) as error:
        print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
