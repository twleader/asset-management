#!/usr/bin/env python3
"""Offline D222 Ruby/Python canonical document parity and rejection tests."""
import copy
from decimal import Decimal
import importlib.util
import json
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("authority", ROOT / "scripts/check-srpp-report-authority.py")
authority = importlib.util.module_from_spec(spec)
spec.loader.exec_module(authority)


class D222DocumentTest(unittest.TestCase):
    def setUp(self):
        self.example = (ROOT / "spec/fixtures/srpp_d222_reserve_document_example.json").read_text()
        document = json.loads(self.example)
        reserve = document["reserveAllocation"]
        for key in ("annual_inflation_rate", "living_term_deposit_twd_2026_real",
                    "living_demand_deposit_twd_2026_real", "emergency_term_deposit_twd_2026_real",
                    "emergency_etf_twd_2026_real", "reference_cash_weight"):
            reserve[key] = Decimal(reserve[key])
        self.model = {"execution_policy": {"reserve_allocation_d222": reserve},
                      "detailed_target_allocation_2027": {"TWD_term_deposits": Decimal(".164"),
                          "demand_deposits_and_cash": Decimal(".006"), "VOO": Decimal(".83")}}

    def ruby(self, model):
        script = ('require File.expand_path("scripts/srpp-report-policy-register.rb"); '
                  'p=SrppReportPolicyRegister.policy_document(SrppReportPolicyRegister.parse_model_strict(STDIN.read)); '
                  'SrppPolicyRegister.validate_policy!(SrppPolicyRegister.parse_strict(p)); print p; puts')
        return subprocess.run(["ruby", "-e", script], cwd=ROOT,
                              input=json.dumps(model, default=float), text=True,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def test_full_bytes_match_common_fixture(self):
        self.assertEqual(authority.expected_formal_document(self.model) + "\n", self.example)
        ruby = self.ruby(self.model)
        self.assertEqual(ruby.returncode, 0, ruby.stderr)
        self.assertEqual(ruby.stdout, self.example)

    def test_invalid_reserve_contract_is_rejected_in_both_languages(self):
        cases = [("excess_symbols", ["VOO"]), ("excess_requires_explicit_allocation", False),
                 ("annual_inflation_rate", Decimal("-0.1")),
                 ("living_term_deposit_twd_2026_real", True), ("inflation_base_date", "2026-8-31")]
        for key, value in cases:
            model = copy.deepcopy(self.model)
            model["execution_policy"]["reserve_allocation_d222"][key] = value
            with self.subTest(key=key):
                with self.assertRaises(authority.ReleaseFailure):
                    authority.expected_formal_document(model)
                self.assertNotEqual(self.ruby(model).returncode, 0)

    def test_v1_remains_exactly_two_fields(self):
        model = copy.deepcopy(self.model)
        model["execution_policy"].clear()
        document = json.loads(authority.expected_formal_document(model))
        self.assertEqual(set(document), {"schema", "targets"})
        self.assertEqual(document["schema"], "SRPP_POLICY_DOCUMENT_V1")
        self.assertEqual(self.ruby(model).returncode, 0)


if __name__ == "__main__":
    unittest.main()
