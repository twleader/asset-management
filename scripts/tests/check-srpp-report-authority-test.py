#!/usr/bin/env python3
"""Offline regression coverage for the Task499 release gate."""
from __future__ import annotations

import copy
import importlib.util
from pathlib import Path
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[2]
SRPP = Path("/Users/steven/Project/SRPP")
SPEC = importlib.util.spec_from_file_location(
    "check_srpp_report_authority", REPO / "scripts/check-srpp-report-authority.py")
assert SPEC and SPEC.loader
GATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GATE)


class SrppReportAuthorityReleaseGateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.model_bytes = (SRPP / "assumptions/model_inputs.json").read_bytes()
        cls.manifest_bytes = (SRPP / "assumptions/policy_manifest.json").read_bytes()
        cls.model = GATE.parse_json(cls.model_bytes, "MODEL")
        cls.manifest = GATE.parse_json(cls.manifest_bytes, "MANIFEST")
        cls.bundle = cls.manifest["POLICY_BUNDLE_SHA256"]

    def test_current_release_gate_passes_without_writing(self) -> None:
        self.assertEqual(self.bundle, GATE.check_release(SRPP))

    def test_stale_embedded_and_golden_drift_fail_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            stale = Path(directory) / "model_inputs.json"
            stale.write_bytes(self.model_bytes + b" ")
            with self.assertRaisesRegex(GATE.ReleaseFailure, "EMBEDDED_MODEL_BYTES_MISMATCH"):
                GATE.require_exact_bytes("EMBEDDED_MODEL", self.model_bytes, stale)

        golden = GATE.parse_json((REPO / GATE.GOLDEN_PATH).read_bytes(), "GOLDEN")
        stale_golden = copy.deepcopy(golden)
        stale_golden["policyBundleSha256"] = "0" * 64
        with self.assertRaisesRegex(GATE.ReleaseFailure, "GOLDEN_BUNDLE_MISMATCH"):
            GATE.validate_golden(stale_golden, self.bundle)
        stale_golden = copy.deepcopy(golden)
        stale_golden["cases"] = stale_golden["cases"][:-1]
        with self.assertRaisesRegex(GATE.ReleaseFailure, "GOLDEN_CASE_COUNT_INVALID"):
            GATE.validate_golden(stale_golden, self.bundle)

    def test_duplicate_model_key_fails_closed(self) -> None:
        with self.assertRaisesRegex(GATE.ReleaseFailure, "MODEL_JSON_INVALID:DUPLICATE_JSON_KEY:model_version"):
            GATE.parse_json(b'{"model_version":"3.22.0","model_version":"3.22.0"}', "MODEL")

    def test_formal_and_registration_drift_fail_closed(self) -> None:
        with self.assertRaisesRegex(GATE.ReleaseFailure, "FORMAL_POLICY_BYTES_MISMATCH"):
            GATE.validate_formal(self.model, b'{"schema":"SRPP_POLICY_DOCUMENT_V1"}\n')
        with self.assertRaisesRegex(GATE.ReleaseFailure, "REGISTRATION_SQL_NOT_INSERT_ONLY"):
            GATE.validate_registration_sql(b"UPDATE srpp_policy_registry SET x = 'bad';\n", self.bundle)
        with self.assertRaisesRegex(GATE.ReleaseFailure, "REGISTRATION_SQL_CONTENT_INVALID"):
            GATE.validate_registration_sql(
                b"INSERT INTO srpp_policy_registry VALUES ('other') ON CONFLICT (policy_bundle_sha256) DO NOTHING;\n",
                self.bundle)


if __name__ == "__main__":
    unittest.main()
