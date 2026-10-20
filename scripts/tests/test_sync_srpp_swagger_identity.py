#!/usr/bin/env python3
"""Isolated metadata maintenance guards; never uses production policy files."""
import hashlib
import importlib.util
import json
import os
import shutil
import subprocess
import sys
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
        # macOS /var is itself a system symlink; use the repository root so this fixture can
        # distinguish an actual caller-provided symlink from that host-level alias.
        self.temp = tempfile.TemporaryDirectory(dir=SCRIPT.parents[1])
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.srpp = self.root / "SRPP"
        self.primary = self.root / "SRPP-primary"
        self.repo = self.root / "upstream"
        # 公開 synchronize() 不再允許 test 直接拿任意目錄當 writer；建立真實、
        # 非 main 的 managed worktree，讓每一寫入測試都經過同一條 release guard。
        self.git("init", "--initial-branch=main", str(self.primary))
        self.git("-C", str(self.primary), "config", "user.email", "test@example.invalid")
        self.git("-C", str(self.primary), "config", "user.name", "identity test")
        (self.primary / "README").write_text("fixture\n")
        self.git("-C", str(self.primary), "add", "README")
        self.git("-C", str(self.primary), "commit", "-m", "initial fixture")
        self.git("-C", str(self.primary), "worktree", "add", "-b", "task504-choice", str(self.srpp))
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
        self.commit_srpp("fixture sources")

    def commit_srpp(self, message):
        self.git("-C", str(self.srpp), "add", "-A")
        self.git("-C", str(self.srpp), "commit", "-m", message)

    def snapshot(self):
        # git status 可能更新 index/worktree 行政檔；唯讀 sync 的斷言只觀察
        # repo 產物，不把 Git 自己的快取變化誤當成寫入。
        return {
            str(p): p.read_bytes()
            for p in self.root.rglob("*")
            if p.is_file() and ".git" not in p.relative_to(self.root).parts
        }

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

    def test_real_renderer_sync_then_read_only_checks_allow_only_generated_srpp_paths(self):
        asset_root = SCRIPT.parents[1]
        renderer = self.repo / "scripts/render-9090-openapi-docs.rb"
        renderer.parent.mkdir(exist_ok=True)
        shutil.copy2(asset_root / "scripts/render-9090-openapi-docs.rb", renderer)
        for source in ("docker-external-api.yaml", "ai-flow-observation-api.yaml"):
            shutil.copy2(asset_root / "docs/openapi" / source, self.repo / "docs/openapi" / source)

        rendered = subprocess.run(["ruby", str(renderer), "--srpp-root", str(self.srpp)], text=True,
                                  stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
        self.assertEqual(0, rendered.returncode, rendered.stderr)
        self.assertEqual({sync.SRPP_SWAGGER_MIRROR}, self.changed_srpp_paths())
        mirror = self.srpp / sync.SRPP_SWAGGER_MIRROR
        self.assertEqual(hashlib.sha256(mirror.read_bytes()).hexdigest(),
                         hashlib.sha256((self.repo / "docs/openapi/9090-api-swagger.md").read_bytes()).hexdigest())
        # renderer 後只有 mirror dirty 時，兩支 --check 都必能越過 worktree guard；
        # sync 的 schema 尚未更新，所以它如實回報 mismatch 而不是誤報 DIRTY。
        before_pre_sync_checks = self.snapshot()
        renderer_pre_sync_check = subprocess.run(["ruby", str(renderer), "--check", "--srpp-root", str(self.srpp)], text=True,
                                                  stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
        self.assertEqual(0, renderer_pre_sync_check.returncode, renderer_pre_sync_check.stderr)
        with self.assertRaisesRegex(ValueError, "CONSUMER_SCHEMA_SWAGGER_MISMATCH"):
            sync.synchronize(self.srpp, self.repo, True)
        self.assertEqual(before_pre_sync_checks, self.snapshot())

        result = sync.synchronize(self.srpp, self.repo)
        self.assertEqual("SYNCHRONIZED_METADATA_ONLY", result["status"])
        self.assertEqual(sync.SYNC_GENERATED_PATHS, self.changed_srpp_paths())
        before_checks = self.snapshot()
        renderer_check = subprocess.run(["ruby", str(renderer), "--check", "--srpp-root", str(self.srpp)], text=True,
                                        stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
        self.assertEqual(0, renderer_check.returncode, renderer_check.stderr)
        self.assertEqual("CURRENT", sync.synchronize(self.srpp, self.repo, True)["status"])
        self.assertEqual(before_checks, self.snapshot())

        (self.srpp / "unrelated.txt").write_text("must reject before check/read/write")
        with self.assertRaisesRegex(ValueError, "SRPP_ROOT_DIRTY"):
            sync.synchronize(self.srpp, self.repo, True)
        renderer_reject = subprocess.run(["ruby", str(renderer), "--check", "--srpp-root", str(self.srpp)], text=True,
                                         stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
        self.assertNotEqual(0, renderer_reject.returncode)

    def changed_srpp_paths(self):
        output = subprocess.run(["git", "-C", str(self.srpp), "status", "--porcelain=v1", "-z"],
                                stdout=subprocess.PIPE, check=True).stdout.decode()
        entries = [entry for entry in output.split("\0") if entry]
        self.assertTrue(all(entry.startswith(" M ") for entry in entries), entries)
        return {entry[3:] for entry in entries}

    def test_check_and_unrelated_drift_are_fail_closed_without_writes(self):
        before = self.snapshot()
        with self.assertRaisesRegex(ValueError, "CONSUMER_SCHEMA_SWAGGER_MISMATCH"):
            sync.synchronize(self.srpp, self.repo, True)
        self.assertEqual(before, self.snapshot())
        (self.srpp / "assumptions/model_inputs.json").write_text("changed by another session")
        self.commit_srpp("unrelated source drift")
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
        self.commit_srpp("semantic drift fixture")
        before = self.snapshot()
        with self.assertRaisesRegex(ValueError, "RUNTIME_SEMANTICS_CHANGED"):
            sync.synchronize(self.srpp, self.repo)
        self.assertEqual(before, self.snapshot())

    def test_cli_default_primary_main_unmanaged_and_symlink_reject_before_any_write(self):
        primary = self.root / "primary"
        (primary / ".git").mkdir(parents=True)
        unmanaged = self.root / "unmanaged"
        unmanaged.mkdir()
        symlink = self.root / "symlink"
        os.symlink(unmanaged, symlink)
        managed_primary = self.root / "managed-primary"
        main_worktree = self.root / "main-worktree"
        feature_worktree = self.root / "feature-worktree"
        self.git("init", "--initial-branch=trunk", str(managed_primary))
        self.git("-C", str(managed_primary), "config", "user.email", "test@example.invalid")
        self.git("-C", str(managed_primary), "config", "user.name", "identity test")
        (managed_primary / "README").write_text("fixture\n")
        self.git("-C", str(managed_primary), "add", "README")
        self.git("-C", str(managed_primary), "commit", "-m", "fixture")
        self.git("-C", str(managed_primary), "branch", "main")
        self.git("-C", str(managed_primary), "worktree", "add", str(main_worktree), "main")
        self.git("-C", str(managed_primary), "worktree", "add", "-b", "task504-choice", str(feature_worktree))
        (feature_worktree / "intended-change").write_text("pending mirror\n")
        with self.assertRaisesRegex(ValueError, "SRPP_ROOT_DIRTY"):
            sync.managed_srpp_worktree(feature_worktree)
        self.assertEqual(feature_worktree.resolve(), sync.managed_srpp_worktree(feature_worktree, require_clean=False))
        before = self.snapshot()

        asset_only = self.cli("--asset-only")
        self.assertEqual(0, asset_only.returncode, asset_only.stderr)
        self.assertIn("ASSET_ONLY_NO_SRPP", asset_only.stdout)
        for args in ((), ("--srpp-root", str(primary)), ("--srpp-root", str(unmanaged)),
                     ("--srpp-root", str(symlink)), ("--srpp-root", str(main_worktree))):
            with self.subTest(args=args):
                result = self.cli(*args)
                self.assertEqual(2, result.returncode, result.stderr)
                self.assertEqual(before, self.snapshot())
        for bad_root in (primary, unmanaged, symlink, main_worktree, feature_worktree):
            with self.subTest(public_entrypoint=bad_root):
                with self.assertRaises(ValueError):
                    sync.synchronize(bad_root, self.repo)
                self.assertEqual(before, self.snapshot())

    @staticmethod
    def git(*args):
        result = subprocess.run(["git", *args], text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
        if result.returncode:
            raise AssertionError(result.stderr)

    @staticmethod
    def cli(*args):
        return subprocess.run([sys.executable, str(SCRIPT), *args], text=True, stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, check=False)


if __name__ == "__main__":
    unittest.main()
