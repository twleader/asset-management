#!/usr/bin/env python3
"""Isolated metadata maintenance guards; never uses production policy files."""
import hashlib
import importlib.util
import json
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "sync-srpp-swagger-identity.py"
spec = importlib.util.spec_from_file_location("sync_identity", SCRIPT)
sync = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync)


class MetadataSyncTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.srpp = self.root / "SRPP"
        self.repo = self.root / "upstream"
        for folder in (self.srpp / "scripts", self.srpp / "assumptions", self.srpp / "docs", self.repo / "docs/openapi", self.repo / "backend/src/main/resources/srpp/decision"):
            folder.mkdir(parents=True)
        (self.srpp / "scripts/policy_bundle.py").write_text('''import hashlib, json
MANIFEST = "assumptions/policy_manifest.json"
def canonical(v): return json.dumps(v, sort_keys=True).encode()
def policy_paths(root): return ["assumptions/model_inputs.json", "assumptions/srpp_report_calculations_schema.json", "assumptions/daily_runtime_contract.json"]
def validate_rules(root): pass
def make_manifest(root):
 body={"schema_version":1,"files":{p:hashlib.sha256((root/p).read_bytes()).hexdigest() for p in policy_paths(root)}}
 return {**body,"POLICY_BUNDLE_SHA256":hashlib.sha256(canonical(body)).hexdigest()}
''')
        (self.srpp / "scripts/daily_runtime_contract.py").write_text('''import hashlib,json
OUTPUT="assumptions/daily_runtime_contract.json"
def make_contract(root): return {"decision":"FIXTURE","source_sha256":{"assumptions/srpp_report_calculations_schema.json":hashlib.sha256((root/"assumptions/srpp_report_calculations_schema.json").read_bytes()).hexdigest()}}
def canonical(v): return json.dumps(v,sort_keys=True)+"\\n"
def check(root):
 if json.loads((root/OUTPUT).read_text()) != make_contract(root): raise ValueError("RUNTIME_MISMATCH")
''')
        (self.srpp / "assumptions/model_inputs.json").write_text('{"immutable":"model"}')
        schema = {"type": "object", "additionalProperties": False, "x-swagger-sha256": "1" * 64}
        (self.srpp / sync.SCHEMA).write_text(json.dumps(schema))
        schema_hash = hashlib.sha256((self.srpp / sync.SCHEMA).read_bytes()).hexdigest()
        self.runtime = self.srpp / "assumptions/daily_runtime_contract.json"
        self.runtime.write_text(json.dumps({"decision": "FIXTURE", "source_sha256": {sync.SCHEMA: schema_hash}}, sort_keys=True) + "\n")
        files = {p: hashlib.sha256((self.srpp / p).read_bytes()).hexdigest() for p in ["assumptions/model_inputs.json", sync.SCHEMA, "assumptions/daily_runtime_contract.json"]}
        body = {"schema_version": 1, "files": files}
        manifest = {**body, "POLICY_BUNDLE_SHA256": hashlib.sha256(json.dumps(body, sort_keys=True).encode()).hexdigest()}
        self.manifest = self.srpp / "assumptions/policy_manifest.json"
        self.artifact = self.repo / "backend/src/main/resources/srpp/decision/policy_manifest.json"
        self.manifest.write_text(json.dumps(manifest, indent=2) + "\n")
        self.artifact.write_bytes(self.manifest.read_bytes())
        for file in [self.srpp / "docs/9090 Port API Swagger.md", self.repo / "docs/openapi/9090-api-swagger.md", self.repo / "backend/src/main/resources/srpp/9090-api-swagger.md"]:
            file.write_text("unified swagger fixture")

    def snapshot(self):
        return {str(p): p.read_bytes() for p in self.root.rglob("*") if p.is_file()}

    def test_metadata_only_sync_and_idempotent_check(self):
        before = self.snapshot()
        result = sync.synchronize(self.srpp, self.repo)
        self.assertEqual(result["status"], "SYNCHRONIZED_METADATA_ONLY")
        self.assertEqual(set(result["changedPolicySources"]), {sync.SCHEMA, "assumptions/daily_runtime_contract.json"})
        self.assertEqual(before[str(self.srpp / "assumptions/model_inputs.json")], (self.srpp / "assumptions/model_inputs.json").read_bytes())
        self.assertEqual(self.manifest.read_bytes(), self.artifact.read_bytes())
        after = self.snapshot()
        self.assertEqual(sync.synchronize(self.srpp, self.repo, True)["status"], "CURRENT")
        self.assertEqual(after, self.snapshot())

    def test_check_and_unrelated_drift_are_fail_closed_without_writes(self):
        before = self.snapshot()
        with self.assertRaisesRegex(ValueError, "CONSUMER_SCHEMA_SWAGGER_MISMATCH"):
            sync.synchronize(self.srpp, self.repo, True)
        self.assertEqual(before, self.snapshot())
        (self.srpp / "assumptions/model_inputs.json").write_text("changed by another session")
        before = self.snapshot()
        with self.assertRaisesRegex(ValueError, "POLICY_SOURCE_DRIFT"):
            sync.synchronize(self.srpp, self.repo)
        self.assertEqual(before, self.snapshot())

    def test_partial_first_write_failure_rolls_back(self):
        before = self.snapshot()
        original = Path.write_bytes
        first = True
        schema_path = self.srpp / sync.SCHEMA

        def fail_first(path, data):
            nonlocal first
            if first and path == schema_path:
                first = False
                original(path, b"partial")
                raise OSError("fixture interrupted write")
            return original(path, data)

        with patch.object(Path, "write_bytes", fail_first):
            with self.assertRaisesRegex(OSError, "fixture interrupted write"):
                sync.synchronize(self.srpp, self.repo)
        self.assertEqual(before, self.snapshot())

    def test_runtime_semantic_change_rolls_back_all_artifacts(self):
        script = self.srpp / "scripts/daily_runtime_contract.py"
        script.write_text(script.read_text().replace('"decision":"FIXTURE"', '"decision":"FIXTURE", "unexpected":(root/"assumptions/srpp_report_calculations_schema.json").read_text().find("1"*64)==-1'))
        # Establish a valid old contract for this compiler, then update its manifest hash.
        old = json.loads(self.runtime.read_text()); old["unexpected"] = False
        self.runtime.write_text(json.dumps(old, sort_keys=True) + "\n")
        manifest = json.loads(self.manifest.read_text())
        manifest["files"]["assumptions/daily_runtime_contract.json"] = hashlib.sha256(self.runtime.read_bytes()).hexdigest()
        body = {k: v for k, v in manifest.items() if k != "POLICY_BUNDLE_SHA256"}
        manifest["POLICY_BUNDLE_SHA256"] = hashlib.sha256(json.dumps(body, sort_keys=True).encode()).hexdigest()
        self.manifest.write_text(json.dumps(manifest, indent=2) + "\n"); self.artifact.write_bytes(self.manifest.read_bytes())
        before = self.snapshot()
        with self.assertRaisesRegex(ValueError, "RUNTIME_SEMANTICS_CHANGED"):
            sync.synchronize(self.srpp, self.repo)
        self.assertEqual(before, self.snapshot())


if __name__ == "__main__":
    unittest.main()
