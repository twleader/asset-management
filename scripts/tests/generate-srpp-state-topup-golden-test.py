#!/usr/bin/env python3
"""Offline generator regression with isolated output and explicit SRPP provenance."""
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

REPO = Path(__file__).resolve().parents[2]
GENERATOR = REPO / "scripts/generate-srpp-state-topup-golden.py"
SOURCE = Path(os.environ["SRPP_RELEASE_WORKTREE"]).resolve()
EMBEDDED = Path("backend/src/main/resources/srpp/decision")
GOLDEN = Path("backend/src/test/resources/srpp/report-state-topup-golden.json")


def snapshot(paths: list[Path]) -> dict[str, tuple[bytes, int]]:
    return {str(path): (path.read_bytes(), path.stat().st_mtime_ns) for path in paths}


class StateTopupGoldenGeneratorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        # All generator writes occur in an isolated asset tree. The original SRPP
        # source and real embedded artifacts/fixture remain read-only.
        cls.directory = tempfile.TemporaryDirectory()
        cls.repo = Path(cls.directory.name) / "asset"
        cls.script = cls.repo / "scripts/generate-srpp-state-topup-golden.py"
        cls.script.parent.mkdir(parents=True)
        shutil.copyfile(GENERATOR, cls.script)
        (cls.repo / EMBEDDED).mkdir(parents=True)
        for name in ("model_inputs.json", "policy_manifest.json"):
            shutil.copyfile(SOURCE / "assumptions" / name, cls.repo / EMBEDDED / name)
        cls.golden = cls.repo / GOLDEN
        cls.golden.parent.mkdir(parents=True)
        cls.protected = [
            SOURCE / "assumptions/model_inputs.json",
            SOURCE / "assumptions/policy_manifest.json",
            *(SOURCE / "scripts" / name for name in (
                "execution_policy.py", "bear_ladder.py", "transition_ledger.py")),
            REPO / EMBEDDED / "model_inputs.json",
            REPO / EMBEDDED / "policy_manifest.json",
            REPO / GOLDEN,
        ]
        cls.original = snapshot(cls.protected)
        result = cls.run_generator("--srpp-root", str(SOURCE))
        if result.returncode:
            raise AssertionError(f"isolated generation failed: {result.stderr}")
        cls.expected = cls.golden.read_bytes()
        cls.assert_protected_unchanged()

    @classmethod
    def tearDownClass(cls) -> None:
        cls.assert_protected_unchanged()
        cls.directory.cleanup()

    @classmethod
    def assert_protected_unchanged(cls) -> None:
        if snapshot(cls.protected) != cls.original:
            raise AssertionError("generator modified the real source or real authority artifacts")

    @classmethod
    def run_generator(cls, *arguments: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(cls.script), *arguments],
            cwd=cls.repo,
            env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"},
            capture_output=True, text=True, check=False,
        )

    def setUp(self) -> None:
        self.golden.write_bytes(self.expected)

    def assert_check_preserves_files(self, expected_success: bool) -> subprocess.CompletedProcess[str]:
        paths = [self.golden, *(self.repo / EMBEDDED / name for name in (
            "model_inputs.json", "policy_manifest.json"))]
        before = snapshot(paths)
        result = self.run_generator("--srpp-root", str(SOURCE), "--check")
        self.assertEqual(result.returncode == 0, expected_success, result.stderr)
        self.assertEqual(snapshot(paths), before)
        self.assert_protected_unchanged()
        return result

    def test_explicit_source_provenance_and_current_check_are_read_only(self) -> None:
        result = self.assert_check_preserves_files(True)
        self.assertIn("22 original state/component parity cases match", result.stdout)
        fixture = json.loads(self.expected)
        self.assertEqual(len(fixture["cases"]), 22)
        self.assertEqual(fixture["originalCalculatorHashes"], {
            name: hashlib.sha256((SOURCE / "scripts" / name).read_bytes()).hexdigest()
            for name in ("execution_policy.py", "bear_ladder.py", "transition_ledger.py")
        })

    def test_implicit_relative_and_missing_roots_are_rejected_before_output(self) -> None:
        for arguments in [(), ("--srpp-root", "."),
                          ("--srpp-root", str(self.repo / "missing-source"))]:
            with self.subTest(arguments=arguments):
                before = snapshot([self.golden])
                result = self.run_generator(*arguments, "--check")
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("--srpp-root", result.stderr)
                self.assertEqual(snapshot([self.golden]), before)

    def test_official_source_rejection_does_not_import_calculators_or_write_output(self) -> None:
        source = Path(self.directory.name) / "rejected-source"
        (source / "assumptions").mkdir(parents=True)
        (source / "scripts").mkdir()
        (source / "assumptions/model_inputs.json").write_text("{}")
        (source / "scripts/policy_bundle.py").write_text("raise SystemExit(1)\n")
        before = snapshot([self.golden])
        result = self.run_generator("--srpp-root", str(source), "--check")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("SRPP_STATE_TOPUP_OFFICIAL_POLICY_BUNDLE_CHECK_FAILED", result.stderr)
        self.assertEqual(snapshot([self.golden]), before)

    def test_each_stale_embedded_artifact_is_rejected_without_writing(self) -> None:
        for name in ("model_inputs.json", "policy_manifest.json"):
            path = self.repo / EMBEDDED / name
            original = path.read_bytes()
            try:
                path.write_bytes(original + b" ")
                result = self.assert_check_preserves_files(False)
                self.assertIn("SRPP_STATE_TOPUP_EMBEDDED_AUTHORITY_BYTES_MISMATCH:" + name,
                              result.stderr)
            finally:
                path.write_bytes(original)

    def test_stale_source_hash_case_and_format_are_rejected_without_writing(self) -> None:
        fixture = json.loads(self.expected)
        for mutation in ("source_hash", "case", "format"):
            with self.subTest(mutation=mutation):
                stale = json.loads(self.expected)
                if mutation == "source_hash":
                    stale["originalCalculatorHashes"]["execution_policy.py"] = "0" * 64
                elif mutation == "case":
                    stale["cases"] = stale["cases"][:-1]
                if mutation == "format":
                    self.golden.write_bytes(self.expected + b" ")
                else:
                    self.golden.write_text(json.dumps(stale), encoding="utf-8")
                result = self.assert_check_preserves_files(False)
                self.assertIn("SRPP_STATE_TOPUP_GOLDEN_BYTES_MISMATCH", result.stderr)
                self.assertEqual(len(fixture["cases"]), 22)


if __name__ == "__main__":
    unittest.main()
