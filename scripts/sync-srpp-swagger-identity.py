#!/usr/bin/env python3
"""Maintain only Swagger metadata after rendering; reject unrelated policy drift.

Run locally after render-9090-openapi-docs.rb. SQL registration and container
publication remain explicit reviewed deployment steps; this tool performs no I/O
outside the named source/artifact files and does not connect to any API or DB.
"""
import argparse
import hashlib
import importlib.util
import json
import os
import subprocess
import sys
from pathlib import Path

# --check must not create Python cache files while importing the source compilers.
sys.dont_write_bytecode = True

ROOT = Path(__file__).resolve().parents[1]
SCHEMA = "assumptions/srpp_report_calculations_schema.json"
SRPP_SWAGGER_MIRROR = "docs/9090 Port API Swagger.md"
RUNTIME_CONTRACT = "assumptions/daily_runtime_contract.json"
POLICY_MANIFEST = "assumptions/policy_manifest.json"
SYNC_GENERATED_PATHS = frozenset({SRPP_SWAGGER_MIRROR, SCHEMA, RUNTIME_CONTRACT, POLICY_MANIFEST})


def digest(data):
    return hashlib.sha256(data).hexdigest()


def managed_srpp_worktree(raw, require_clean=True, allowed_dirty_paths=frozenset()):
    """在讀任何 SRPP source 前驗證明示 worktree；只容許指定的未 staged 產物接續。"""
    if raw is None:
        raise ValueError("SRPP_ROOT_REQUIRED")
    path = Path(raw)
    if not path.is_absolute():
        raise ValueError("SRPP_ROOT_MUST_BE_ABSOLUTE")
    expanded = Path(os.path.abspath(path))
    if os.path.islink(expanded):
        raise ValueError("SRPP_ROOT_SYMLINK_FORBIDDEN")
    try:
        canonical = expanded.resolve(strict=True)
    except OSError as failure:
        raise ValueError("SRPP_ROOT_UNAVAILABLE") from failure
    if canonical != expanded:
        raise ValueError("SRPP_ROOT_SYMLINK_FORBIDDEN")
    if not (canonical / ".git").is_file():
        raise ValueError("SRPP_ROOT_PRIMARY_OR_UNMANAGED")

    def git(*args):
        result = subprocess.run(["git", "-C", str(canonical), *args], text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.DEVNULL, check=False)
        if result.returncode != 0:
            raise ValueError("SRPP_ROOT_UNMANAGED")
        return result.stdout

    if Path(git("rev-parse", "--show-toplevel").strip()).resolve() != canonical:
        raise ValueError("SRPP_ROOT_NOT_WORKTREE_ROOT")
    managed = {Path(line.removeprefix("worktree ").strip()).resolve()
               for line in git("worktree", "list", "--porcelain").splitlines() if line.startswith("worktree ")}
    if canonical not in managed:
        raise ValueError("SRPP_ROOT_UNMANAGED")
    branch_result = subprocess.run(["git", "-C", str(canonical), "symbolic-ref", "--quiet", "--short", "HEAD"],
                                   text=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=False)
    if branch_result.returncode != 0:
        raise ValueError("SRPP_ROOT_MAIN_OR_DETACHED")
    branch = branch_result.stdout.strip()
    if not branch or branch == "main":
        raise ValueError("SRPP_ROOT_MAIN_OR_DETACHED")
    status = git("status", "--porcelain=v1", "-z")
    if status and require_clean:
        entries = [entry for entry in status.split("\0") if entry]
        # 只允許本任務的 renderer/sync 產物以未 staged 的 M 留在 worktree；
        # rename、staged、untracked 或任何其他路徑一律不屬於可安全接續的鏈。
        changed = set()
        for entry in entries:
            if not entry.startswith(" M "):
                raise ValueError("SRPP_ROOT_DIRTY")
            changed.add(entry[3:])
        if not allowed_dirty_paths or not changed.issubset(allowed_dirty_paths):
            raise ValueError("SRPP_ROOT_DIRTY")
    return canonical


def _rendered_mirror_matches_upstream(srpp, upstream):
    """只在 root/status guard 後比對 renderer 唯一允許的跨 repo 產物。"""
    swagger = upstream / "docs/openapi/9090-api-swagger.md"
    resource = upstream / "backend/src/main/resources/srpp/9090-api-swagger.md"
    mirror = srpp / SRPP_SWAGGER_MIRROR
    try:
        if not swagger.read_bytes() == mirror.read_bytes() == resource.read_bytes():
            raise ValueError("SWAGGER_MIRRORS_DIFFER")
    except OSError as failure:
        raise ValueError("SWAGGER_MIRRORS_DIFFER") from failure


def _synchronize_verified_worktree(srpp, upstream, check=False):
    """僅供已通過 managed_srpp_worktree 的公開入口呼叫；不可直接對外使用。"""
    sys.path.insert(0, str(srpp / "scripts"))
    spec = importlib.util.spec_from_file_location("srpp_policy_bundle", srpp / "scripts/policy_bundle.py")
    policy = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(policy)
    manifest_path = srpp / policy.MANIFEST
    old_bytes = manifest_path.read_bytes()
    old = json.loads(old_bytes)
    files = old["files"]
    if set(policy.policy_paths(srpp)) != set(files):
        raise ValueError("POLICY_PATHS_CHANGED: refusing metadata maintenance")
    changed = [name for name, expected in files.items() if digest((srpp / name).read_bytes()) != expected]
    if changed:
        raise ValueError("POLICY_SOURCE_DRIFT: " + ", ".join(changed))
    # Validate the old manifest hash and every formal policy invariant BEFORE mutation.
    body = {k: v for k, v in old.items() if k != "POLICY_BUNDLE_SHA256"}
    if digest(policy.canonical(body)) != old["POLICY_BUNDLE_SHA256"]:
        raise ValueError("POLICY_MANIFEST_INVALID")
    policy.validate_rules(srpp)
    runtime_spec = importlib.util.spec_from_file_location("srpp_runtime_contract", srpp / "scripts/daily_runtime_contract.py")
    runtime = importlib.util.module_from_spec(runtime_spec)
    runtime_spec.loader.exec_module(runtime)
    runtime_path = srpp / runtime.OUTPUT
    runtime_bytes = runtime_path.read_bytes()
    old_runtime = json.loads(runtime_bytes)
    runtime.check(srpp)
    swagger = upstream / "docs/openapi/9090-api-swagger.md"
    _rendered_mirror_matches_upstream(srpp, upstream)
    swagger_hash = digest(swagger.read_bytes())
    schema_path = srpp / SCHEMA
    schema_bytes = schema_path.read_bytes()
    schema = json.loads(schema_bytes)
    artifact = upstream / "backend/src/main/resources/srpp/decision/policy_manifest.json"
    if artifact.read_bytes() != old_bytes:
        raise ValueError("PUBLISHED_POLICY_ARTIFACT_DIFFER: refusing overwrite")
    if schema["x-swagger-sha256"] == swagger_hash:
        return {"status": "CURRENT", "swaggerSha256": swagger_hash, "policyBundleSha256": old["POLICY_BUNDLE_SHA256"]}
    if check:
        raise ValueError("CONSUMER_SCHEMA_SWAGGER_MISMATCH")
    # Replace exactly one fixed-width metadata value; keep all schema keywords and formatting.
    old_hash = schema["x-swagger-sha256"]
    marker = json.dumps(old_hash).encode()
    if len(old_hash) != 64 or schema_bytes.count(marker) != 1:
        raise ValueError("AMBIGUOUS_SCHEMA_METADATA")
    new_schema_bytes = schema_bytes.replace(marker, json.dumps(swagger_hash).encode(), 1)
    new_schema = json.loads(new_schema_bytes)
    new_schema["x-swagger-sha256"] = old_hash
    if new_schema != schema:
        raise ValueError("SCHEMA_SEMANTICS_CHANGED")
    try:
        schema_path.write_bytes(new_schema_bytes)
        new_runtime = runtime.make_contract(srpp)
        restored_runtime = json.loads(json.dumps(new_runtime))
        restored_runtime['source_sha256'][SCHEMA] = old_runtime['source_sha256'][SCHEMA]
        if restored_runtime != old_runtime:
            raise ValueError("RUNTIME_SEMANTICS_CHANGED")
        runtime_path.write_text(runtime.canonical(new_runtime))
        new = policy.make_manifest(srpp)
        changed_names = [name for name in files if files[name] != new["files"][name]]
        if set(changed_names) != {SCHEMA, str(runtime.OUTPUT)} or {k: v for k, v in old.items() if k not in {"files", "POLICY_BUNDLE_SHA256"}} != {k: v for k, v in new.items() if k not in {"files", "POLICY_BUNDLE_SHA256"}}:
            raise ValueError("NON_METADATA_POLICY_CHANGE")
        new_bytes = (json.dumps(new, indent=2, ensure_ascii=False) + "\n").encode()
        manifest_path.write_bytes(new_bytes)
        artifact.write_bytes(new_bytes)
    except BaseException:
        schema_path.write_bytes(schema_bytes)
        runtime_path.write_bytes(runtime_bytes)
        manifest_path.write_bytes(old_bytes)
        artifact.write_bytes(old_bytes)
        raise
    return {"status": "SYNCHRONIZED_METADATA_ONLY", "swaggerSha256": swagger_hash,
            "oldPolicyBundleSha256": old["POLICY_BUNDLE_SHA256"], "policyBundleSha256": new["POLICY_BUNDLE_SHA256"],
            "changedPolicySources": changed_names}


def synchronize(srpp_root, upstream, check=False):
    """唯一公開同步入口：先驗證 feature worktree，僅接續 renderer/sync 的固定產物。"""
    # mutation 只允許 renderer 剛產生的 mirror；check 另可接續本工具剛產生的
    # 三個 metadata 檔，且仍拒絕任何非固定產物的 dirty path。
    allowed = SYNC_GENERATED_PATHS if check else frozenset({SRPP_SWAGGER_MIRROR})
    srpp = managed_srpp_worktree(srpp_root, require_clean=True, allowed_dirty_paths=allowed)
    _rendered_mirror_matches_upstream(srpp, Path(upstream))
    return _synchronize_verified_worktree(srpp, upstream, check)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--srpp-root", type=Path)
    modes.add_argument("--asset-only", action="store_true")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    if args.asset_only:
        print(json.dumps({"status": "ASSET_ONLY_NO_SRPP"}, ensure_ascii=False))
        return
    if args.srpp_root is None:
        parser.error("--srpp-root 為必填；預設不讀寫 SRPP（或使用 --asset-only）")
    try:
        print(json.dumps(synchronize(args.srpp_root, ROOT, args.check), ensure_ascii=False))
    except ValueError as failure:
        print(str(failure), file=sys.stderr)
        raise SystemExit(2)


if __name__ == "__main__":
    main()
