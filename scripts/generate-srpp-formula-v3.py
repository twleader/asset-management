#!/usr/bin/env python3
"""Replay D218 formula identity from exact implementation bytes; never touches V1/V2."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
VERSION = "ASSET_MGMT_SRPP_V3"
CALCULATION_ID = "DIVIDEND_TOPUP_D218_COMPLETED_TWO_SESSIONS_FIXED_WAVE_CUMULATIVE_BUDGET"
JAVA_SOURCES = (
    "backend/src/main/java/com/steven/assets/service/srpp/report/SrppDividendStabilization.java",
    "backend/src/main/java/com/steven/assets/service/srpp/report/SrppReportModuleCalculator.java",
    "backend/src/main/java/com/steven/assets/service/srpp/report/SrppReportTopupSizing.java",
)
PYTHON_SOURCE = "scripts/dividend_stabilization.py"
FIXTURE = ROOT / "spec/fixtures/srpp_formula_manifest_v3.json"
CATALOG = ROOT / "backend/src/main/java/com/steven/assets/srpp/SrppFormulaCatalog.java"
BFF_MIRROR = ROOT / "bff/src/main/resources/srpp/formula_manifest_v3.json"
MARKER = re.compile(r"(?<=    // BEGIN GENERATED D218 FORMULA V3\n).*?(?=    // END GENERATED D218 FORMULA V3)", re.S)
V2_DIGEST = "0f3d9b67dcb7d519f8ef5e9ee378d86eaf26228409199bc487036732e0186e86"


def canonical(value: object) -> str:
    # This manifest has ASCII keys, strings only, and no floating point values.
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def expected(srpp_root: Path) -> str:
    legacy = json.loads((ROOT / "spec/fixtures/srpp_formula_manifest_v2.json").read_bytes())
    if hashlib.sha256(canonical(legacy).encode()).hexdigest() != V2_DIGEST:
        raise ValueError("HISTORICAL_V2_MANIFEST_CHANGED")
    legacy["schema"] = "SRPP_FORMULA_MANIFEST_V3"
    legacy["formulaVersion"] = VERSION
    hashes = {"asset-management/" + p: hashlib.sha256((ROOT / p).read_bytes()).hexdigest()
              for p in JAVA_SOURCES}
    hashes["SRPP/" + PYTHON_SOURCE] = hashlib.sha256((srpp_root / PYTHON_SOURCE).read_bytes()).hexdigest()
    legacy["calculations"]["dividendStabilization"] = {
        "calculationId": CALCULATION_ID, "sourceSha256": hashes}
    return canonical(legacy)


def generate(srpp_root: Path, check: bool) -> str:
    manifest = expected(srpp_root)
    catalog = CATALOG.read_text()
    if len(MARKER.findall(catalog)) != 1:
        raise ValueError("FORMULA_V3_GENERATED_MARKER_INVALID")
    literal = "    public static final String MANIFEST_JSON_V3 = " + json.dumps(manifest, ensure_ascii=False) + ";\n"
    rendered = MARKER.sub(lambda _: literal, catalog)
    wanted = manifest + "\n"
    if check:
        if (not FIXTURE.is_file() or FIXTURE.read_text() != wanted or catalog != rendered
                or not BFF_MIRROR.is_file() or BFF_MIRROR.read_text() != wanted):
            raise ValueError("FORMULA_V3_SOURCE_OR_ARTIFACT_DRIFT")
    else:
        FIXTURE.write_text(wanted)
        CATALOG.write_text(rendered)
        BFF_MIRROR.parent.mkdir(parents=True, exist_ok=True)
        BFF_MIRROR.write_text(wanted)
    return hashlib.sha256(manifest.encode()).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--srpp-root", required=True, type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    if not args.srpp_root.is_absolute() or not args.srpp_root.is_dir():
        parser.error("--srpp-root must be an existing absolute directory")
    try:
        digest = generate(args.srpp_root, args.check)
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"SRPP_FORMULA_V3_FAIL:{error}", file=sys.stderr)
        return 1
    print(f"SRPP_FORMULA_V3_{'CHECK' if args.check else 'GENERATED'}_PASS:{digest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
