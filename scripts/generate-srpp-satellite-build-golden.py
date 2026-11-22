#!/usr/bin/env python3
"""Deterministic original-SRPP D-143 009826 / D-234 VT satellite build cases (Task 623).

Loads global_build_009826 / global_build_vt and their API-input adapters from the given SRPP root;
no registry, broker, market or network I/O. Numbers are saved exactly as Python returns them.
"""
import argparse
import copy
import hashlib
import importlib.util
import json
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path
import sys

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "backend/src/test/resources/srpp/satellite-build-golden.json"
SCHEMA = "SATELLITE_BUILD_ORIGINAL_SRPP_GOLDEN_V1"


def load(srpp_root):
    source = srpp_root / "scripts/execution_policy.py"
    sys.path.insert(0, str(srpp_root / "scripts"))
    spec = importlib.util.spec_from_file_location("srpp_execution_policy_t623", source)
    worker = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(worker)
    return worker, hashlib.sha256(source.read_bytes()).hexdigest()


# ---- 009826 fixtures (SRPP test_global_build_009826.py) ----
MONDAY_009826 = "2026-11-02"
TOTAL_009826 = 20_563_384.92


def quote_009826(ask=10.08, date=MONDAY_009826, code="009826", price=None):
    return {"stockCode": code, "market": "台股", "tradingDate": date, "price": ask if price is None else price,
            "askLevels": [{"price": ask, "size": 1000}] if ask else []}


def buy_009826(date, shares=1000, price=10.0, code="009826", kind="買", market="台股"):
    return {"assetCode": code, "market": market, "transactionType": kind, "tradeDate": date,
            "shares": float(shares), "price": price, "amount": shares * price}


def book(*records, start, end):
    return {"selection": {"mode": "DATE_RANGE", "year": None, "start": start, "end": end}, "records": list(records)}


# ---- VT fixtures (SRPP test_global_build_vt.py) ----
MONDAY_VT = "2026-11-23"
US_SESSION = "2026-11-20"
TOTAL_VT = 20_942_182.82
RATE = 32.5


def quote_vt(ask=150.0, date=US_SESSION, code="VT", price=149.9):
    return {"stockCode": code, "market": "美股", "tradingDate": date, "price": price, "sellPrice": ask}


def buy_vt(date, shares=5.0, amount_twd=24_375.0, code="VT", kind="買", market="美股", currency="USD", rate=32.5):
    return {"assetCode": code, "market": market, "transactionType": kind, "tradeDate": date,
            "shares": float(shares), "amountTwd": amount_twd, "currency": currency, "exchangeRate": rate}


def mid(buy, sell):
    return float(((Decimal(str(buy)) + Decimal(str(sell))) / 2).quantize(Decimal("0.0001"), rounding=ROUND_HALF_UP))


def generate(srpp_root):
    worker, digest = load(srpp_root)
    p009826 = worker.load_global_build_policy(srpp_root)
    pvt = worker.load_global_build_vt_policy(srpp_root)
    cases = []

    def lot(name, trading_date=MONDAY_009826, q=None, held=0., total=TOTAL_009826, tx=None, rates=(32.69,)):
        inp = {"quote": q if q is not None else quote_009826(date=trading_date), "trading_date": trading_date,
               "held_value_twd": held, "total_assets_twd": total,
               "transactions": tx if tx is not None else book(start="2026-09-22", end=trading_date),
               "usd_twd_rates": list(rates)}
        expected = worker.global_build_009826(copy.deepcopy(inp["quote"]), trading_date=trading_date, held_value_twd=held,
                                              total_assets_twd=total, transactions=copy.deepcopy(inp["transactions"]),
                                              usd_twd_rates=list(rates), policy=p009826)
        cases.append({"kind": "009826", "name": name, "input": inp, "expected": expected})

    def tx9(*records, start="2026-09-22", end=MONDAY_009826):
        return book(*records, start=start, end=end)

    lot("first_day_three_lot_ceiling_ask1")
    lot("last_price_when_no_ask_level", q={**quote_009826(), "askLevels": []})
    lot("identity_wrong_with_ask_keeps_ask1_basis", q=quote_009826(code="00881"))
    lot("identity_wrong_date", q=quote_009826(date="2026-10-30"))
    lot("before_earliest_buy_date", "2026-10-30", tx=tx9(end="2026-10-30"))
    lot("build_window_closed", "2027-07-01", tx=tx9(start="2027-06-28", end="2027-07-01"))
    lot("daily_lot_limit_three_today", tx=tx9(buy_009826(MONDAY_009826, shares=3000)))
    lot("only_confirmed_buys_of_this_code_count", tx=tx9(buy_009826(MONDAY_009826, kind="賣"), buy_009826(MONDAY_009826, code="00881"), buy_009826(MONDAY_009826, market="美股")))
    lot("odd_lot_fill_leaves_whole_lots", tx=tx9(buy_009826(MONDAY_009826, shares=500)))
    fills = tx9(buy_009826("2026-11-02", shares=2000), buy_009826("2026-11-03", shares=2000), buy_009826("2026-11-04", shares=2000), end="2026-11-05")
    lot("weekly_lot_limit_six_this_week", "2026-11-05", tx=fills)
    lot("next_week_reset", "2026-11-09", tx=tx9(*fills["records"], end="2026-11-09"))
    lot("monthly_cap_under_one_lot_remaining", "2026-11-10", tx=tx9(buy_009826("2026-11-03", shares=20_000, price=10.0), end="2026-11-10"))
    lot("monthly_cap_boundary_one_lot", "2026-11-10", tx=tx9(buy_009826("2026-11-03", shares=19_000, price=10.0), end="2026-11-10"))
    tx_half = tx9(buy_009826("2026-11-03", shares=10_000, price=10.0), end="2026-11-10")
    lot("fx_33_01_halves_cap_to_monthly_cap", "2026-11-10", tx=tx_half, rates=(33.01, 32.7))
    lot("fx_unknown_rates_empty_assumed_halved", rates=())
    lot("fx_at_threshold_not_halved", "2026-11-10", tx=tx_half, rates=(33.0,))
    lot("target_weight_reached", held=p009826["stop_at_weight"] * TOTAL_009826)
    lot("target_weight_fits_one_lot", held=p009826["stop_at_weight"] * TOTAL_009826 - 10_100)
    lot("holding_unverified_none", held=None)
    lot("holding_unverified_boolean", held=True)
    lot("total_assets_unverified", total=None)
    lot("pace_unverified_late_start_records_not_walked", tx=tx9(buy_009826(MONDAY_009826, shares=2000), start="2026-11-03"))
    lot("pace_unverified_early_end", "2026-11-05", tx=tx9(end="2026-11-04"))
    lot("pace_unverified_amount_null", tx=tx9({**buy_009826(MONDAY_009826), "amount": None}))
    lot("pace_unverified_records_missing", tx={"selection": tx9()["selection"]})
    lot("pace_unverified_non_object_record_still_accumulates", tx=tx9(buy_009826(MONDAY_009826, shares=1000), "bad"))
    lot("room_exactly_one_lot", tx=tx9(buy_009826(MONDAY_009826, shares=2000)))
    lot("room_0_9999999995_floors_to_one", tx=tx9(buy_009826(MONDAY_009826, shares=2000.0000005, price=10.0)))
    lot("multiple_codes_in_formula_order", "2026-10-30", q=quote_009826(code="00881"), held=None, total=None, tx=tx9(start="2026-10-28", end="2026-10-30"))

    def vt(name, trading_date=MONDAY_VT, q=None, held=298_308., total=TOTAL_VT, tx=None, rates=(32.4,), rate=RATE,
           session=US_SESSION, policy=None):
        inp = {"quote": q if q is not None else quote_vt(date=session), "trading_date": trading_date, "us_session_date": session,
               "held_value_twd": held, "total_assets_twd": total,
               "transactions": tx if tx is not None else book(start="2026-10-01", end=trading_date),
               "usd_twd_rates": list(rates), "spot_selling_rate": rate}
        if policy is not None:
            inp["policy"] = policy
        expected = worker.global_build_vt(copy.deepcopy(inp["quote"]), trading_date=trading_date, us_session_date=session,
                                          held_value_twd=held, total_assets_twd=total, transactions=copy.deepcopy(inp["transactions"]),
                                          usd_twd_rates=list(rates), spot_selling_rate=rate, policy=policy or pvt)
        cases.append({"kind": "VT", "name": name, "input": inp, "expected": expected})

    def txv(*records, start="2026-10-01", end=MONDAY_VT):
        return book(*records, start=start, end=end)

    vt("first_day_five_share_ceiling")
    vt("last_price_when_no_ask", q=quote_vt(ask=None))
    vt("quote_date_not_us_session", q=quote_vt(date="2026-11-19"))
    vt("quote_code_wrong", q=quote_vt(code="VOO"))
    vt("before_earliest_buy_date", "2026-11-20", tx=txv(end="2026-11-20"))
    vt("build_window_closed", "2027-07-01", tx=txv(start="2027-06-28", end="2027-07-01"))
    vt("daily_share_limit_one_today", tx=txv(buy_vt(MONDAY_VT, shares=1.0, amount_twd=4_875.)))
    wk = txv(*(buy_vt(d, amount_twd=10_000.) for d in ("2026-11-23", "2026-11-24", "2026-11-25")), end="2026-11-26")
    vt("weekly_share_limit", "2026-11-26", tx=wk)
    vt("weekly_reset_next_week", "2026-11-30", tx=txv(*wk["records"], end="2026-11-30"))
    dca = buy_vt("2026-11-23", shares=2.22533, amount_twd=11_504.5)
    vt("dca_fill_consumes_month_and_week", "2026-11-24", tx=txv(dca, end="2026-11-24"))
    vt("weekly_share_limit_with_fractional_dca", "2026-11-24", tx=txv(dca, buy_vt("2026-11-23", shares=10.0, amount_twd=20_000.), end="2026-11-24"))
    vt("monthly_cap_below_minimum", tx=txv(buy_vt("2026-11-02", shares=12.0, amount_twd=60_000.)))
    vt("fx_halved_monthly_cap", rates=(33.1,), tx=txv(buy_vt("2026-11-02", shares=4.0, amount_twd=20_000.)))
    vt("fx_unknown_assumed_halved", rates=())
    vt("fx_conversion_unverified", rate=None)
    vt("target_weight_reached_four_shares", held=0.025 * TOTAL_VT - 4 * 150 * RATE)
    below = {**pvt, "min_shares_per_order": 5.5, "max_shares_per_day": 10, "max_shares_per_week": 30}
    vt("below_min_order_shares_dedicated_policy", tx=txv(buy_vt("2026-11-02", shares=10.0, amount_twd=80_000 - 5.8 * 150 * RATE)), policy=below)
    vt("pace_unverified_late_start_records_not_walked", tx=txv(buy_vt(MONDAY_VT, shares=1.0), start="2026-11-10"))
    vt("pace_unverified_amount_twd_null", tx=txv({**buy_vt(MONDAY_VT, shares=1.0), "amountTwd": None}))
    vt("pace_unverified_usd_exchange_rate_null", tx=txv({**buy_vt(MONDAY_VT), "exchangeRate": None}))
    vt("pace_unverified_usd_exchange_rate_zero", tx=txv({**buy_vt(MONDAY_VT), "exchangeRate": 0}))
    vt("twd_row_without_exchange_rate_is_verified", tx=txv(buy_vt("2026-11-02", shares=2.0, amount_twd=9_000., currency="TWD", rate=None)))
    vt("holding_unverified", held=None)
    vt("total_assets_unverified", total=None)
    vt("room_exactly_five_week", "2026-11-24", tx=txv(buy_vt("2026-11-23", shares=10.0, amount_twd=20_000.), end="2026-11-24"))
    usd_dca = buy_vt("2027-01-05", shares=2.22533, amount_twd=11_375.0)
    twd_dca = buy_vt("2027-01-05", shares=2.22533, amount_twd=11_375.0, currency="TWD", rate=None)
    vt("d182_dca_usd_ledger_row", "2027-01-06", tx=txv(usd_dca, start="2027-01-01", end="2027-01-06"), session="2027-01-05", q=quote_vt(date="2027-01-05"))
    vt("d182_dca_twd_ledger_row_same_consumption", "2027-01-06", tx=txv(twd_dca, start="2027-01-01", end="2027-01-06"), session="2027-01-05", q=quote_vt(date="2027-01-05"))
    vt("multiple_codes_in_formula_order", "2026-11-20", q=quote_vt(code="VOO"), rate=None, held=None, total=None, tx=txv(start="2026-11-16", end="2026-11-20"))

    def api(kind, name, assets, fx, trading_date):
        fn = worker.global_build_api_inputs if kind == "api009826" else worker.global_build_vt_api_inputs
        cases.append({"kind": kind, "name": name, "input": {"assets": copy.deepcopy(assets), "fx": copy.deepcopy(fx), "trading_date": trading_date},
                      "expected": fn(copy.deepcopy(assets), copy.deepcopy(fx), trading_date)})

    assets = {"liveAssets": {"liveTotalAssets": TOTAL_009826, "stocks": [
        {"stockCode": "009826", "market": "台股", "liveValue": 20_160},
        {"stockCode": "009826", "market": "台股", "liveValue": 10_080},
        {"stockCode": "009826", "market": "台股", "liveValue": 0},
        {"stockCode": "0050", "market": "台股", "liveValue": 723_354}]}}
    fx = {"history": [{"date": "2026-10-30", "midRate": 33.4}, {"date": "2026-11-01", "midRate": 32.47}, {"date": "2026-11-03", "midRate": 33.5}],
          "spot": {"date": MONDAY_009826, "midRate": 32.69, "quoteStatus": "LIVE", "sellRate": 32.74}}
    api("api009826", "multi_account_sum_with_zero_and_month_history_filter", assets, fx, MONDAY_009826)
    api("api009826", "empty_inputs", {}, {}, MONDAY_009826)
    api("api009826", "spot_not_live_excluded", assets, {"history": [], "spot": {**fx["spot"], "quoteStatus": "DELAYED"}}, MONDAY_009826)
    api("api009826", "spot_date_mismatch_excluded", assets, {"history": [], "spot": {**fx["spot"], "date": "2026-10-30"}}, MONDAY_009826)
    bool_assets = {"liveAssets": {"liveTotalAssets": TOTAL_009826, "stocks": [{"stockCode": "009826", "market": "台股", "liveValue": True}]}}
    api("api009826", "boolean_holding_value_is_none", bool_assets, fx, MONDAY_009826)
    api("api009826", "no_holding_rows_is_zero_and_bad_total_none", {"liveAssets": {"liveTotalAssets": 0, "stocks": []}}, fx, MONDAY_009826)
    vt_assets = {"liveAssets": {"liveTotalAssets": TOTAL_VT, "stocks": [
        {"stockCode": "VT", "market": "美股", "liveValue": 46598},
        {"stockCode": "VT", "market": "美股", "liveValue": 219091},
        {"stockCode": "VT", "market": "美股", "liveValue": 0},
        {"stockCode": "009826", "market": "台股", "liveValue": 86670}]}}
    vfx = {"spot": {"date": MONDAY_VT, "quoteStatus": "LIVE", "midRate": 32.4, "sellRate": 32.45},
           "history": [{"date": "2026-10-31", "midRate": 33.2}, {"date": "2026-11-20", "midRate": 32.35}]}
    api("apiVt", "all_vt_accounts_live_selling_rate", vt_assets, vfx, MONDAY_VT)
    api("apiVt", "spot_date_mismatch_selling_none", vt_assets, {"spot": {**vfx["spot"], "date": "2026-11-20"}}, MONDAY_VT)
    api("apiVt", "spot_not_live_selling_none", vt_assets, {**vfx, "spot": {**vfx["spot"], "quoteStatus": "INDICATIVE"}}, MONDAY_VT)
    api("apiVt", "boolean_vt_holding_is_none", {"liveAssets": {"liveTotalAssets": TOTAL_VT, "stocks": [{"stockCode": "VT", "market": "美股", "liveValue": False}]}}, vfx, MONDAY_VT)
    api("apiVt", "empty_inputs", {}, None, MONDAY_VT)

    # KNOWN_DIFFERENCE_MIDS_WINDOW: the BFF validates its whole two-year window and answers 502
    # (SRPP then has rates=[] and assumes halved); 9090 validates only the current month's rows.
    trading = "2026-11-24"
    fx_rows = [{"date": "2026-10-30", "buyRate": 33.5, "sellRate": 33.4},   # out-of-month buyRate > sellRate
               {"date": "2026-11-02", "buyRate": 32.3, "sellRate": 32.4},
               {"date": "2026-11-23", "buyRate": 32.4, "sellRate": 32.5}]
    live = {"date": trading, "quoteStatus": "INDICATIVE", "buyRate": 32.45, "sellRate": 32.55}
    month_rows = [r for r in fx_rows if r["date"][:7] == trading[:7] and r["date"] <= trading]
    history = [{"date": r["date"], "midRate": mid(r["buyRate"], r["sellRate"])} for r in month_rows]
    spot = {"date": live["date"], "quoteStatus": live["quoteStatus"], "sellRate": live["sellRate"], "midRate": mid(live["buyRate"], live["sellRate"])}
    known_book = {"supported": True, "available": True, "source": "FUBON_BOOKS", "marketStatus": "OPEN", "price": 10.05, "previousClose": 10.0,
                  "sourceTime": "2026-11-24T01:30:00Z", "levels": [{"level": i + 1, "bidPrice": round(10.04 - 0.01 * i, 2), "bidVolumeLots": 10,
                                                                     "askPrice": round(10.06 + 0.01 * i, 2), "askVolumeLots": 10} for i in range(5)]}
    known_assets = {"liveAssets": {"liveTotalAssets": TOTAL_VT, "stocks": [{"stockCode": "VT", "market": "美股", "liveValue": 298_308}]}}
    known_tx = book(buy_vt("2026-11-02", shares=2.0, amount_twd=9_000.), buy_009826("2026-11-03", shares=1000), start="2026-11-01", end=trading)
    q009826 = {"stockCode": "009826", "market": "台股", "tradingDate": trading, "price": 10.05}
    qvt = quote_vt(date="2026-11-23")
    shared = {"trading_date": trading, "us_session_date": "2026-11-23", "captured_at": "2026-11-24T01:35:00Z", "fx_rows": fx_rows, "fx": live,
              "assets": known_assets, "transactions": known_tx, "market_009826": {"quote": q009826, "book": known_book},
              "market_vt": {"quote": qvt, "calendarVerified": True, "completedSessions": ["2026-11-20", "2026-11-23"]}}

    def known(kind, name):
        python_quote = {**q009826, "askLevels": [{"price": known_book["levels"][0]["askPrice"]}]}
        out = {}
        for label, fxshape in (("expected", {"history": [], "spot": spot}), ("javaExpected", {"history": history, "spot": spot})):
            if kind == "api009826":
                a = worker.global_build_api_inputs(known_assets, fxshape, trading)
                out[label] = worker.global_build_009826(copy.deepcopy(python_quote), trading_date=trading, held_value_twd=a["held_value_twd"],
                                                        total_assets_twd=a["total_assets_twd"], transactions=copy.deepcopy(known_tx),
                                                        usd_twd_rates=a["usd_twd_rates"], policy=p009826)
            else:
                a = worker.global_build_vt_api_inputs(known_assets, fxshape, trading)
                out[label] = worker.global_build_vt(copy.deepcopy(qvt), trading_date=trading, us_session_date="2026-11-23",
                                                    held_value_twd=a["held_value_twd"], total_assets_twd=a["total_assets_twd"],
                                                    transactions=copy.deepcopy(known_tx), usd_twd_rates=a["usd_twd_rates"],
                                                    spot_selling_rate=a["spot_selling_rate"], policy=pvt)
        cases.append({"kind": kind, "name": name, "knownDifference": "KNOWN_DIFFERENCE_MIDS_WINDOW", "input": copy.deepcopy(shared), **out})

    known("api009826", "api009826_mids_out_of_month_bad_row_known_difference")
    known("apiVt", "apiVt_mids_out_of_month_bad_row_known_difference")

    payload = {"schema": SCHEMA, "calculatorSha256": digest, "policy": {"009826": p009826, "VT": pvt}, "cases": cases}
    return json.dumps(payload, ensure_ascii=False, sort_keys=True, indent=2, allow_nan=False) + "\n"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--srpp-root", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    if not args.srpp_root.is_absolute() or not args.srpp_root.is_dir():
        parser.error("--srpp-root must be an existing absolute directory")
    value = generate(args.srpp_root)
    if args.check:
        if not TARGET.exists() or TARGET.read_text() != value:
            raise SystemExit("SATELLITE_BUILD_GOLDEN_DRIFT")
    else:
        TARGET.write_text(value)
    print("SATELLITE_BUILD_GOLDEN_PASS:" + str(len(json.loads(value)["cases"])))
