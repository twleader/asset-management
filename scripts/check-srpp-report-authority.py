#!/usr/bin/env python3
"""Read-only offline release gate for the current SRPP report authority."""
from __future__ import annotations

import argparse
from decimal import Decimal
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

sys.dont_write_bytecode = True

ROOT = Path(__file__).resolve().parents[1]
REPORT_ROUTE = "/api/public/srpp/report-calculations"
MODEL_PATH = Path("backend/src/main/resources/srpp/decision/model_inputs.json")
MANIFEST_PATH = Path("backend/src/main/resources/srpp/decision/policy_manifest.json")
GOLDEN_PATH = Path("backend/src/test/resources/srpp/report-same-input-golden.json")
FORMAL_PATH = Path("backend/src/main/resources/srpp/report/d201-policy-document.json")
SQL_PATH = Path("backend/src/main/resources/srpp/report/d201-registration.sql")


class ReleaseFailure(RuntimeError):
    pass


def reject_duplicate_keys(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ReleaseFailure(f"DUPLICATE_JSON_KEY:{key}")
        result[key] = value
    return result


def parse_json(data: bytes, label: str) -> object:
    try:
        return json.loads(data.decode("utf-8"), object_pairs_hook=reject_duplicate_keys,
                          parse_float=Decimal)
    except (UnicodeDecodeError, json.JSONDecodeError, ReleaseFailure) as error:
        raise ReleaseFailure(f"{label}_JSON_INVALID:{error}") from error


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def canonical_document(value: object) -> str:
    try:
        return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    except (TypeError, ValueError) as error:
        raise ReleaseFailure(f"FORMAL_POLICY_CANONICALIZATION_FAILED:{error}") from error


def canonical_decimal(value: object) -> str:
    if isinstance(value, bool) or not isinstance(value, (Decimal, int)):
        raise ReleaseFailure("FORMAL_TARGET_NOT_NUMERIC")
    decimal = Decimal(value)
    if decimal < 0 or decimal > 1:
        raise ReleaseFailure("FORMAL_TARGET_INVALID")
    rendered = format(decimal, "f")
    if "." in rendered:
        rendered = rendered.rstrip("0").rstrip(".")
    return rendered or "0"


def expected_formal_document(model: object) -> str:
    if not isinstance(model, dict):
        raise ReleaseFailure("MODEL_NOT_OBJECT")
    allocations = model.get("detailed_target_allocation_2027")
    if not isinstance(allocations, dict) or not allocations:
        raise ReleaseFailure("FORMAL_TARGET_MISSING")
    targets: dict[str, str] = {}
    for code, weight in allocations.items():
        if not isinstance(code, str):
            raise ReleaseFailure("FORMAL_TARGET_CODE_INVALID")
        if code == "TWD_term_deposits":
            key = "CASH:TWD:TERM"
        elif code == "demand_deposits_and_cash":
            key = "CASH:TWD:DEMAND"
        else:
            market = "美股" if code.isascii() and all(c.isupper() or c == "." for c in code) else "台股"
            key = f"STOCK:{market}:{code}"
        targets[key] = canonical_decimal(weight)
    return canonical_document({"schema": "SRPP_POLICY_DOCUMENT_V1", "targets": targets})


def run_read_only(command: list[str], cwd: Path, label: str) -> str:
    environment = {**os.environ, "PYTHONDONTWRITEBYTECODE": "1"}
    completed = subprocess.run(command, cwd=cwd, env=environment, text=True,
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
    if completed.returncode != 0:
        detail = completed.stderr.strip().splitlines()[-1] if completed.stderr.strip() else "exit non-zero"
        raise ReleaseFailure(f"{label}_FAILED:{detail}")
    return completed.stdout


def check_official_source(srpp_root: Path) -> tuple[bytes, bytes, dict[str, object]]:
    output = run_read_only(
        [sys.executable, str(srpp_root / "scripts/policy_bundle.py"), "check",
         "--purpose", "daily", "--consumer", "Codex"],
        srpp_root,
        "OFFICIAL_POLICY_BUNDLE_CHECK",
    )
    receipt = parse_json(output.encode("utf-8"), "OFFICIAL_POLICY_BUNDLE_CHECK")
    if not isinstance(receipt, dict) or receipt.get("status") != "PASS":
        raise ReleaseFailure("OFFICIAL_POLICY_BUNDLE_CHECK_NOT_PASS")
    model = (srpp_root / "assumptions/model_inputs.json").read_bytes()
    manifest = (srpp_root / "assumptions/policy_manifest.json").read_bytes()
    source_manifest = parse_json(manifest, "OFFICIAL_MANIFEST")
    if not isinstance(source_manifest, dict):
        raise ReleaseFailure("OFFICIAL_MANIFEST_NOT_OBJECT")
    bundle = source_manifest.get("POLICY_BUNDLE_SHA256")
    if not isinstance(bundle, str) or receipt.get("POLICY_BUNDLE_SHA256") != bundle:
        raise ReleaseFailure("OFFICIAL_BUNDLE_RECEIPT_MISMATCH")
    files = source_manifest.get("files")
    if not isinstance(files, dict) or files.get("assumptions/model_inputs.json") != sha256(model):
        raise ReleaseFailure("OFFICIAL_MODEL_HASH_MISMATCH")
    return model, manifest, source_manifest


def require_exact_bytes(label: str, expected: bytes, actual_path: Path) -> None:
    if not actual_path.is_file() or actual_path.read_bytes() != expected:
        raise ReleaseFailure(f"{label}_BYTES_MISMATCH")


def validate_golden(golden: object, bundle: str) -> None:
    if not isinstance(golden, dict) or golden.get("schema") != "SRPP_REPORT_SAME_INPUT_GOLDEN_V1":
        raise ReleaseFailure("GOLDEN_SCHEMA_INVALID")
    if golden.get("policyBundleSha256") != bundle:
        raise ReleaseFailure("GOLDEN_BUNDLE_MISMATCH")
    if not isinstance(golden.get("cases"), list) or len(golden["cases"]) != 22:
        raise ReleaseFailure("GOLDEN_CASE_COUNT_INVALID")


def verify_golden(srpp_root: Path, bundle: str) -> None:
    output = run_read_only(
        [sys.executable, str(ROOT / "scripts/generate-srpp-report-golden.py"),
         "--srpp-root", str(srpp_root), "--check"],
        ROOT,
        "GOLDEN_GENERATOR",
    )
    if "22 original-calculator parity cases match" not in output:
        raise ReleaseFailure("GOLDEN_GENERATOR_OUTPUT_INVALID")
    validate_golden(parse_json((ROOT / GOLDEN_PATH).read_bytes(), "GOLDEN"), bundle)


def validate_formal(model: object, formal_bytes: bytes) -> None:
    expected = expected_formal_document(model).encode("utf-8") + b"\n"
    if formal_bytes != expected:
        raise ReleaseFailure("FORMAL_POLICY_BYTES_MISMATCH")


def validate_registration_sql(generated_sql: bytes, bundle: str) -> None:
    sql = generated_sql.decode("utf-8")
    if not sql.startswith("INSERT INTO srpp_policy_registry ") or " ON CONFLICT (policy_bundle_sha256) DO NOTHING;" not in sql:
        raise ReleaseFailure("REGISTRATION_SQL_NOT_INSERT_ONLY")
    if "UPDATE " in sql.upper() or "DELETE " in sql.upper() or bundle not in sql:
        raise ReleaseFailure("REGISTRATION_SQL_CONTENT_INVALID")


def verify_formal_and_registration(model: object, bundle: str) -> None:
    formal_path = ROOT / FORMAL_PATH
    validate_formal(model, formal_path.read_bytes())
    generated_sql = run_read_only(
        ["ruby", str(ROOT / "scripts/srpp-report-policy-register.rb")],
        ROOT,
        "RUBY_REGISTRATION_GENERATOR",
    ).encode("utf-8")
    require_exact_bytes("REGISTRATION_SQL", generated_sql, ROOT / SQL_PATH)
    validate_registration_sql(generated_sql, bundle)


def check_release(srpp_root: Path) -> str:
    model_bytes, manifest_bytes, manifest = check_official_source(srpp_root)
    require_exact_bytes("EMBEDDED_MODEL", model_bytes, ROOT / MODEL_PATH)
    require_exact_bytes("EMBEDDED_MANIFEST", manifest_bytes, ROOT / MANIFEST_PATH)
    bundle = manifest["POLICY_BUNDLE_SHA256"]
    model = parse_json(model_bytes, "OFFICIAL_MODEL")
    verify_golden(srpp_root, bundle)
    verify_formal_and_registration(model, bundle)
    return bundle


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--srpp-root", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args(argv)
    if not args.check:
        parser.error("--check is required; this release gate never writes artifacts")
    if not args.srpp_root.is_absolute():
        parser.error("--srpp-root must be an absolute path")
    try:
        bundle = check_release(args.srpp_root.resolve())
    except (OSError, ReleaseFailure) as error:
        print(f"SRPP_REPORT_AUTHORITY_CHECK_FAIL:{error}", file=sys.stderr)
        return 1
    print(f"SRPP_REPORT_AUTHORITY_CHECK_PASS:{bundle}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
