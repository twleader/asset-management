#!/usr/bin/env python3
"""Deterministic original-SRPP D-215 FX preference cases (usd_asset_fx_preference.evaluate), no market I/O."""
import argparse
import copy
import datetime as dt
import importlib.util
import json
from decimal import Decimal
from pathlib import Path
import sys
sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "backend/src/test/resources/srpp/d215-fx-preference-golden.json"
HASH = "a" * 64
CHECKED_AT = "2026-11-23T09:05:00+08:00"
QUOTE_AS_OF = "2026-11-23T09:04:58+08:00"
RATE_DATE = "2026-11-23"
FX_COMPLETED = "2026-11-20"
SESSION_DATE = "2026-11-23"
ASSET_COMPLETED = "2026-11-20"


def years_before(day, years):
    try:
        return day.replace(year=day.year - years)
    except ValueError:
        return day.replace(year=day.year - years, day=28)


def quote(rate, status="LIVE", rate_date=RATE_DATE, as_of=QUOTE_AS_OF):
    return {"validation": "VERIFIED", "source": "BANK_OF_TAIWAN_LIVE_SPOT", "source_sha256": HASH,
            "currency_pair": "USD/TWD", "rate_basis": "BANK_SPOT_SELLING", "quote_status": status,
            "provider_id": "BANK_OF_TAIWAN", "freshness_verified": True, "rate": str(rate),
            "as_of": as_of, "rate_date": rate_date}


def history(low, high, last=None, rate_date=RATE_DATE, completed=FX_COMPLETED, window_start=None):
    end = dt.date.fromisoformat(rate_date)
    start = years_before(end, 2)
    done = dt.date.fromisoformat(completed)
    last = low if last is None else last
    asc = [(start - dt.timedelta(days=1), last), (start + dt.timedelta(days=100), low),
           (start + dt.timedelta(days=300), high), (done, last)]
    rows = [{"date": d.isoformat(), "rate": str(Decimal(str(r))), "buyRate": str(Decimal(str(r)) - Decimal("0.1"))}
            for d, r in reversed(asc)]
    return {"validation": "VERIFIED", "source": "FINMIND_TAIWAN_EXCHANGE_RATE_NATIVE", "source_sha256": HASH,
            "coverage": "COMPLETE", "currency_pair": "USD/TWD", "rate_basis": "BANK_SPOT_SELLING",
            "spot_basis_verified": True, "provider_id": "BANK_OF_TAIWAN", "provider_status": "RECORDED",
            "droppedUndated": False, "droppedDates": [], "completedAsOfDate": completed,
            "windowStart": (window_start or start.isoformat()), "windowEnd": end.isoformat(), "dailyRates": rows}


def price_fact(symbol, market, currency, price, high="100"):
    end = dt.date.fromisoformat(ASSET_COMPLETED)
    start = end - dt.timedelta(weeks=52)
    peak = end - dt.timedelta(days=170)
    rows = [{"tradingDate": end.isoformat(), "high": str(price), "close": str(price)},
            {"tradingDate": peak.isoformat(), "high": high, "close": str(Decimal(high) - 1)},
            {"tradingDate": start.isoformat(), "high": "95", "close": "94"}]
    return {"validation": "VERIFIED", "source": "CANONICAL_PRICE_HISTORY", "source_sha256": HASH, "status": "AVAILABLE",
            "stockCode": symbol, "market": market, "currency": currency, "completedAsOfDate": end.isoformat(),
            "windowStart": start.isoformat(),
            "priceBasis": "TW_SPLIT_STOCK_PRICE" if market == "台股" else "STORED_PRICE_ONLY",
            "droppedUndated": False, "droppedDates": [], "completedPriceHistory": rows,
            "high": high, "highDate": peak.isoformat(), "referencePrice": str(price)}


def asset(symbol, market, kind, currency, price=None):
    item = {"symbol": symbol, "market": market, "instrument_type": kind, "currency": currency,
            "action": "BUY", "in_existing_universe": True}
    if price is not None:
        item.update(quote_last=str(price), session_date=SESSION_DATE, price_verified_current_round=True)
    return item


def payload(item, fx_quote, fx_history=None, fact=None, checked_at=CHECKED_AT, completed=FX_COMPLETED):
    out = {"checked_at": checked_at, "asset": item, "fx_quote": fx_quote, "fx_completed_as_of": completed}
    if fx_history is not None:
        out["fx_history"] = fx_history
    if fact is not None:
        out["asset_price_fact"] = fact
        out["asset_completed_as_of"] = ASSET_COMPLETED
    return out


def bond(code, price=None):
    return asset(code, "台股", "BOND_ETF", "TWD", price)


def generate(srpp_root):
    sys.path.insert(0, str(srpp_root / "scripts"))
    spec = importlib.util.spec_from_file_location("srpp_usd_asset_fx_preference", srpp_root / "scripts/usd_asset_fx_preference.py")
    worker = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(worker)
    policy = json.loads((srpp_root / "assumptions/model_inputs.json").read_text())["execution_policy"]["usd_asset_fx_preference_d215"]
    cases = []

    def add(name, value):
        expected = worker.evaluate(copy.deepcopy(value), copy.deepcopy(policy))
        cases.append({"name": name, "payload": value, "expected": expected})

    add("bond_00865B_absolute_equal_32_8_normal", payload(bond("00865B"), quote("32.8"), history("30", "33.5")))
    add("bond_00719B_absolute_above_32_8_range_missing", payload(bond("00719B"), quote("32.81")))
    add("bond_00697B_position_exact_0_90_high", payload(bond("00697B"), quote("32.7"), history("30", "33")))
    add("bond_00865B_position_below_0_90_normal", payload(bond("00865B"), quote("32.69"), history("30", "33")))
    add("bond_zero_range_unknown", payload(bond("00865B"), quote("31"), history("31", "31")))
    add("bond_missing_history_unknown", payload(bond("00865B"), quote("32.0")))
    add("bond_etf_drawdown_exact_10_no_exception",
        payload(bond("00865B", "90"), quote("32.7"), history("30", "33"), price_fact("00865B", "台股", "TWD", "90")))
    add("bond_etf_drawdown_above_10_exception",
        payload(bond("00865B", "89.99"), quote("32.7"), history("30", "33"), price_fact("00865B", "台股", "TWD", "89.99")))
    add("stock_goog_drawdown_exact_30_exception",
        payload(asset("GOOG", "美股", "STOCK", "USD", "70"), quote("32.7"), history("30", "33"), price_fact("GOOG", "美股", "USD", "70")))
    add("stock_goog_drawdown_29_99_wait",
        payload(asset("GOOG", "美股", "STOCK", "USD", "70.01"), quote("32.7"), history("30", "33"), price_fact("GOOG", "美股", "USD", "70.01")))
    add("vt_range_unknown_with_verified_exception",
        payload(asset("VT", "美股", "EQUITY_ETF", "USD", "88"), quote("32.5"), None, price_fact("VT", "美股", "USD", "88")))
    add("vt_normal", payload(asset("VT", "美股", "EQUITY_ETF", "USD"), quote("31"), history("30", "33")))
    add("quote_indicative_unknown",
        payload(bond("00865B", "85"), quote("32.7", "INDICATIVE"), history("30", "33"), price_fact("00865B", "台股", "TWD", "85")))
    add("twd_unlisted_bond_claim_unknown", payload(bond("00937B"), quote("31"), history("30", "33")))
    add("twd_equity_etf_0050_not_applicable", payload(asset("0050", "台股", "EQUITY_ETF", "TWD"), quote("31"), history("30", "33")))
    add("history_window_start_mismatch_unknown",
        payload(bond("00865B"), quote("32.0"), history("30", "33", window_start="2024-11-24")))
    leap = history("30", "33", "31", rate_date="2028-02-29", completed="2028-02-28")
    add("leap_day_window_start",
        payload(bond("00865B"), quote("31", rate_date="2028-02-29", as_of="2028-02-29T09:04:58+08:00"), leap,
                checked_at="2028-02-29T09:05:00+08:00", completed="2028-02-28"))
    return json.dumps({"schema": "D215_FX_PREFERENCE_GOLDEN_V1", "policy": policy, "cases": cases},
                      ensure_ascii=False, sort_keys=True, indent=2) + "\n"


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--srpp-root", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    value = generate(args.srpp_root.resolve())
    if args.check:
        if not TARGET.exists() or TARGET.read_text() != value:
            raise SystemExit("D215_FX_GOLDEN_DRIFT")
    else:
        TARGET.write_text(value)
    print("D215_FX_GOLDEN_PASS:" + str(len(json.loads(value)["cases"])))
