#!/usr/bin/env python3
"""Hermetic formula identity tests; never registers a policy or touches production."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("formula_v3", REPO / "scripts/generate-srpp-formula-v3.py")
GEN = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GEN)


class FormulaV3Test(unittest.TestCase):
    def test_exact_sources_are_required_and_check_never_repairs_drift(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "asset"
            srpp = Path(directory) / "SRPP"
            fixture = root / "spec/fixtures/srpp_formula_manifest_v3.json"
            fixture.parent.mkdir(parents=True)
            (fixture.parent / "srpp_formula_manifest_v2.json").write_bytes(
                (REPO / "spec/fixtures/srpp_formula_manifest_v2.json").read_bytes())
            for source in GEN.JAVA_SOURCES:
                path = root / source
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("// isolated synthetic source\n")
            python_source = srpp / GEN.PYTHON_SOURCE
            python_source.parent.mkdir(parents=True)
            python_source.write_text("# isolated synthetic source\n")
            catalog = root / "Catalog.java"
            catalog.write_text("class Catalog {\n    // BEGIN GENERATED D218 FORMULA V3\n    // END GENERATED D218 FORMULA V3\n}\n")
            with patch.object(GEN, "ROOT", root), patch.object(GEN, "FIXTURE", fixture), patch.object(GEN, "CATALOG", catalog), patch.object(GEN, "BFF_MIRROR", root / "bff/mirror.json"):
                digest = GEN.generate(srpp, False)
                self.assertEqual(digest, GEN.generate(srpp, True))
                before = fixture.read_bytes()
                value = json.loads(before)
                self.assertEqual("ASSET_MGMT_SRPP_V3", value["formulaVersion"])
                self.assertEqual(4, len(value["calculations"]["dividendStabilization"]["sourceSha256"]))
                python_source.write_text("# changed isolated source\n")
                with self.assertRaisesRegex(ValueError, "FORMULA_V3_SOURCE_OR_ARTIFACT_DRIFT"):
                    GEN.generate(srpp, True)
                self.assertEqual(before, fixture.read_bytes())
                self.assertNotEqual(digest, GEN.generate(srpp, False))
                (root / GEN.JAVA_SOURCES[0]).unlink()
                with self.assertRaises(FileNotFoundError):
                    GEN.generate(srpp, True)


if __name__ == "__main__":
    unittest.main()
