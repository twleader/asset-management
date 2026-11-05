#!/usr/bin/env python3
"""Original SRPP field-binding regression against an actual synthetic Java module.

The Java test exports calculateCore's real result only when explicitly asked.
Bindings below are synthetic test inputs; this never claims full API, L0,
freshness, portfolio validation, or live financial adoption.
"""
from __future__ import annotations

import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sys
import unittest

sys.dont_write_bytecode = True


class CoreScopeOriginalConsumerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        source = Path(os.environ["SRPP_RELEASE_WORKTREE"]).resolve()
        fixture_path = Path(os.environ["SRPP_CORE_SCOPE_FIXTURE"]).resolve()
        verifier_path = source / "scripts/verify_srpp_report_calculations.py"
        manifest = json.loads((source / "assumptions/policy_manifest.json").read_bytes())
        if hashlib.sha256(verifier_path.read_bytes()).hexdigest() != manifest["files"]["scripts/verify_srpp_report_calculations.py"]:
            raise AssertionError("ORIGINAL_CONSUMER_SOURCE_HASH_MISMATCH")
        fixture = json.loads(fixture_path.read_bytes())
        if (fixture["testScope"] != "SYNTHETIC_OFFLINE_FIELD_ADOPTION_CONTRACT_ONLY"
                or fixture["policyBundleSha256"] != manifest["POLICY_BUNDLE_SHA256"]):
            raise AssertionError("SYNTHETIC_FIXTURE_AUTHORITY_MISMATCH")
        sys.path.insert(0, str(source / "scripts"))
        spec = importlib.util.spec_from_file_location("original_srpp_core_scope_consumer", verifier_path)
        if spec is None or spec.loader is None:
            raise AssertionError("ORIGINAL_CONSUMER_IMPORT_UNAVAILABLE")
        cls.consumer = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.consumer)
        cls.module = fixture["module"]
        cls.model = json.loads((source / "assumptions/model_inputs.json").read_bytes())
        # Minimal, explicitly synthetic dependencies for adoptable_fact only.
        cls.sources = {"assets": {"snapshot": {"stocks": [{"stockCode": "00919", "market": "台股"}]}}}
        cls.vector = {ref: {"validation": "VERIFIED"} for ref in cls.module["inputRefs"]}

    def representative_facts(self):
        facts = self.module["data"]["facts"]
        selected = [f for f in facts if f["subject"] == "00919" and f["name"] in {"highWater", "timingPass"}]
        self.assertEqual({f["name"] for f in selected}, {"highWater", "timingPass"})
        return selected

    def test_real_java_module_preserves_exact_original_scope_and_blocked_fact_adoption(self):
        self.assertEqual(self.module["scope"], [self.consumer.SCOPES["CORE_SELL_GUARD"]])
        self.assertEqual(self.module["status"], "COMPLETE")
        selected = self.representative_facts()
        self.assertEqual(next(f["value"] for f in selected if f["name"] == "timingPass"), "0")
        self.assertTrue(any(r["status"] == "BLOCK" and r["reason"] == "D208_LOW_POINT_SELL_BLOCKED"
                            for r in self.module["receipts"]))
        for fact in selected:
            self.assertTrue(self.consumer.adoptable_fact(self.module, fact, self.vector, self.sources, self.model))

    def test_extra_scope_is_still_rejected_by_the_unchanged_original_consumer(self):
        altered = copy.deepcopy(self.module)
        altered["scope"].append("D208_00919_LOW_POINT_SELL_PROTECTION")
        for fact in self.representative_facts():
            self.assertFalse(self.consumer.adoptable_fact(altered, fact, self.vector, self.sources, self.model))

    def test_unverified_required_source_still_cannot_authorize_a_fact(self):
        vector = copy.deepcopy(self.vector)
        vector["core-state"]["validation"] = "UNAVAILABLE"
        for fact in self.representative_facts():
            self.assertFalse(self.consumer.adoptable_fact(self.module, fact, vector, self.sources, self.model))


if __name__ == "__main__":
    unittest.main()
