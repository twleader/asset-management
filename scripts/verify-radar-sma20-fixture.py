#!/usr/bin/env python3
"""Replay persisted official SMA20 against immutable Fubon raw daily candle fixture."""
import csv
import hashlib
from collections import defaultdict
from decimal import Decimal, localcontext
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "backend/src/test/resources/radar/official_sma20_raw_replay.csv"
EXPECTED_HASH = "510546b046b633a71c78ef1489bc40e36142023483471fa266ccf745d3724644"
EXPECTED = {"0050": 241, "0056": 241, "006208": 968}
TOLERANCE = Decimal("0.00000001")


def main():
    assert hashlib.sha256(FIXTURE.read_bytes()).hexdigest() == EXPECTED_HASH, "fixture changed"
    rows = defaultdict(list)
    with FIXTURE.open(newline="") as stream:
        for row in csv.DictReader(stream):
            rows[row["stock_code"]].append(row)
    assert set(rows) == set(EXPECTED), "unexpected symbols"
    for symbol, expected_count in EXPECTED.items():
        candles = rows[symbol]
        assert all(a["trading_date"] < b["trading_date"] for a, b in zip(candles, candles[1:])), symbol
        counted = 0
        maximum = Decimal(0)
        for i, row in enumerate(candles):
            if not row["official_sma20"]:
                continue
            assert i >= 19, f"{symbol} missing 20 raw candles at {row['trading_date']}"
            with localcontext() as ctx:
                ctx.prec = 34
                replay = sum((Decimal(value["close"]) for value in candles[i-19:i+1]), Decimal(0)) / 20
            delta = abs(Decimal(row["official_sma20"]) - replay)
            assert delta <= TOLERANCE, f"{symbol} {row['trading_date']} delta={delta}"
            maximum = max(maximum, delta)
            counted += 1
        assert counted == expected_count, f"{symbol} fact rows {counted} != {expected_count}"
        print(f"{symbol}: {counted} source dates, max absolute difference {maximum}")


if __name__ == "__main__":
    main()
