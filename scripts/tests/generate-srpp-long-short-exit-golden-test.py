#!/usr/bin/env python3
"""Isolated provenance, rejection and zero-write checks for the long-exit fixture."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True
REPO = Path(__file__).resolve().parents[2]
SOURCE = Path(os.environ["SRPP_RELEASE_WORKTREE"]).resolve()
EMBEDDED = Path("backend/src/main/resources/srpp/decision")
GOLDEN = Path("backend/src/test/resources/srpp/report-long-short-exit-golden.json")
NAME = "generate-srpp-long-short-exit-golden.py"
SOURCE_NAMES = ("short_term_opportunities.py", "short_term_sleeve.py", "execution_policy.py",
                "test_long_short_term_exit.py", "test_short_term_sleeve.py")


def snapshot(paths):
    return {str(p): (p.read_bytes(), p.stat().st_mtime_ns) for p in paths}


class LongShortExitGoldenGeneratorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory()
        cls.repo = Path(cls.directory.name) / "asset"
        cls.script = cls.repo / "scripts" / NAME
        cls.script.parent.mkdir(parents=True)
        shutil.copyfile(REPO / "scripts" / NAME, cls.script)
        (cls.repo / EMBEDDED).mkdir(parents=True)
        for name in ("model_inputs.json", "policy_manifest.json"):
            shutil.copyfile(SOURCE / "assumptions" / name, cls.repo / EMBEDDED / name)
        cls.golden = cls.repo / GOLDEN
        cls.golden.parent.mkdir(parents=True)
        cls.protected = [SOURCE / "assumptions/model_inputs.json", SOURCE / "assumptions/policy_manifest.json",
                         *(SOURCE / "scripts" / name for name in SOURCE_NAMES),
                         REPO / EMBEDDED / "model_inputs.json", REPO / EMBEDDED / "policy_manifest.json", REPO / GOLDEN]
        cls.original = snapshot(cls.protected)
        result = cls.run_generator("--srpp-root", str(SOURCE))
        if result.returncode:
            raise AssertionError(result.stderr)
        cls.expected = cls.golden.read_bytes()
        cls.assert_protected_unchanged()

    @classmethod
    def tearDownClass(cls):
        cls.assert_protected_unchanged()
        cls.directory.cleanup()

    @classmethod
    def run_generator(cls, *args):
        return subprocess.run([sys.executable, "-B", str(cls.script), *args], cwd=cls.repo,
                              env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"},
                              capture_output=True, text=True, check=False)

    @classmethod
    def assert_protected_unchanged(cls):
        if snapshot(cls.protected) != cls.original:
            raise AssertionError("generator modified the actual SRPP or asset authority/fixture")

    def setUp(self):
        self.golden.write_bytes(self.expected)

    def assert_check_preserves_files(self, success):
        paths = [self.golden, *(self.repo / EMBEDDED / name for name in ("model_inputs.json", "policy_manifest.json"))]
        before = snapshot(paths)
        result = self.run_generator("--srpp-root", str(SOURCE), "--check")
        self.assertEqual(result.returncode == 0, success, result.stderr)
        self.assertEqual(snapshot(paths), before)
        self.assert_protected_unchanged()
        return result

    def test_current_check_and_source_provenance_are_read_only(self):
        self.assertIn("15 original full-holding-period parity cases match", self.assert_check_preserves_files(True).stdout)
        golden = json.loads(self.expected)
        self.assertEqual(len(golden["cases"]), 15)
        self.assertEqual(golden["originalCalculatorHashes"], {
            name: hashlib.sha256((SOURCE / "scripts" / name).read_bytes()).hexdigest() for name in SOURCE_NAMES})

    def test_implicit_relative_and_missing_roots_fail_without_output_writes(self):
        for args in [(), ("--srpp-root", "."), ("--srpp-root", str(self.repo / "missing"))]:
            with self.subTest(args=args):
                before = snapshot([self.golden])
                result = self.run_generator(*args, "--check")
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("--srpp-root", result.stderr)
                self.assertEqual(snapshot([self.golden]), before)
                self.assert_protected_unchanged()

    def test_failed_official_source_check_does_not_import_or_write(self):
        source = Path(self.directory.name) / "rejected-source"
        (source / "assumptions").mkdir(parents=True)
        (source / "scripts").mkdir()
        (source / "assumptions/model_inputs.json").write_text("{}")
        (source / "scripts/policy_bundle.py").write_text("raise SystemExit(1)\n")
        before = snapshot([self.golden])
        result = self.run_generator("--srpp-root", str(source), "--check")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("OFFICIAL_POLICY_BUNDLE_CHECK_FAILED", result.stderr)
        self.assertEqual(snapshot([self.golden]), before)
        self.assert_protected_unchanged()

    def test_each_embedded_authority_mismatch_is_read_only(self):
        for name in ("model_inputs.json", "policy_manifest.json"):
            path = self.repo / EMBEDDED / name
            original = path.read_bytes()
            try:
                path.write_bytes(original + b" ")
                result = self.assert_check_preserves_files(False)
                self.assertIn("EMBEDDED_AUTHORITY_BYTES_MISMATCH:" + name, result.stderr)
            finally:
                path.write_bytes(original)

    def test_stale_source_hash_case_and_format_fail_without_writes(self):
        for mutation in ("hash", "case", "format"):
            with self.subTest(mutation=mutation):
                stale = json.loads(self.expected)
                if mutation == "hash":
                    stale["originalCalculatorHashes"]["short_term_opportunities.py"] = "0" * 64
                elif mutation == "case":
                    stale["cases"] = stale["cases"][:-1]
                if mutation == "format":
                    self.golden.write_bytes(self.expected + b" ")
                else:
                    self.golden.write_text(json.dumps(stale), encoding="utf-8")
                self.assertIn("GOLDEN_BYTES_MISMATCH", self.assert_check_preserves_files(False).stderr)


if __name__ == "__main__":
    unittest.main()
