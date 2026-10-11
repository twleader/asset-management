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
import sys
from pathlib import Path

# --check must not create Python cache files while importing the source compilers.
sys.dont_write_bytecode = True

ROOT = Path(__file__).resolve().parents[1]
SCHEMA = "assumptions/srpp_report_calculations_schema.json"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def synchronize(srpp, upstream, check=False):
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
    mirror = srpp / "docs/9090 Port API Swagger.md"
    resource = upstream / "backend/src/main/resources/srpp/9090-api-swagger.md"
    if not swagger.read_bytes() == mirror.read_bytes() == resource.read_bytes():
        raise ValueError("SWAGGER_MIRRORS_DIFFER")
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--srpp-root", type=Path, default=Path("/Users/steven/Project/SRPP"))
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    print(json.dumps(synchronize(args.srpp_root, ROOT, args.check), ensure_ascii=False))


if __name__ == "__main__":
    main()
