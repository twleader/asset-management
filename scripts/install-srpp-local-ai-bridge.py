#!/usr/bin/env python3
"""Install/check/stop the owned Task 592 host bridge; never change account billing."""
from __future__ import annotations

import argparse
import importlib.util
import json
import os
import pathlib
import plistlib
import secrets
import subprocess
import sys

sys.dont_write_bytecode = True
LABEL = "com.steven.srpp-local-ai-bridge"
DEFAULT_ROOT = pathlib.Path("/Users/steven/Project/asset-management-main")


def load_bridge(root):
    path = root/"scripts/srpp-local-ai-bridge.py"
    if not path.is_file() or path.is_symlink():
        raise ValueError("BRIDGE_SOURCE_UNAVAILABLE")
    spec = importlib.util.spec_from_file_location("srpp_local_ai_install", path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def atomic_file(path, raw, mode):
    if path.is_symlink():
        raise ValueError("UNSAFE_STATE_PATH")
    temp = path.with_name(path.name+".tmp-"+secrets.token_hex(6))
    fd = os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_EXCL, mode)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(raw)
        os.replace(temp, path)
    finally:
        temp.unlink(missing_ok=True)


def prepare_state(bridge, state):
    bridge.secure_directory(state)
    token = state/"bridge.token"
    if token.exists():
        bridge.private_file(token)
        value = token.read_bytes()
    else:
        value = secrets.token_urlsafe(48).encode()
        atomic_file(token, value, 0o600)
    registry = state/"registry.json"
    if not registry.exists():
        atomic_file(registry, bridge.encode(bridge.default_registry())+b"\n", 0o600)
    bridge.load_registry(registry)
    # The enclosing host directory is private. Only this single read-only file
    # is mounted into Docker, whose application UID cannot read a host-0600 file.
    projection = bridge.secure_directory(state/"docker-token")/"bridge.token"
    if projection.exists() and projection.is_symlink():
        raise ValueError("UNSAFE_STATE_PATH")
    if not projection.exists() or projection.read_bytes() != value or projection.stat().st_mode & 0o777 != 0o444:
        atomic_file(projection, value, 0o444)
    return token, registry


def job_configuration(root, state, python):
    return {"Label": LABEL, "ProgramArguments": [str(python), str(root/"scripts/srpp-local-ai-bridge.py"),
            "--registry", str(state/"registry.json"), "--state-dir", str(state), "--token-file", str(state/"bridge.token")],
            "WorkingDirectory": str(root), "RunAtLoad": True, "KeepAlive": {"SuccessfulExit": False},
            "ThrottleInterval": 15, "ProcessType": "Background", "LowPriorityIO": True,
            "StandardOutPath": str(state/"bridge.log"), "StandardErrorPath": str(state/"bridge-error.log")}


def owned_job(value):
    args = value.get("ProgramArguments", [])
    return (value.get("Label") == LABEL and isinstance(args, list) and len(args) == 8
            and pathlib.Path(args[1]).name == "srpp-local-ai-bridge.py"
            and args[2] == "--registry" and args[4] == "--state-dir" and args[6] == "--token-file")


def cli(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_mutually_exclusive_group()
    actions.add_argument("--install", action="store_true")
    actions.add_argument("--stop", action="store_true")
    parser.add_argument("--source-root", type=pathlib.Path, default=DEFAULT_ROOT)
    parser.add_argument("--state-dir", type=pathlib.Path, default=pathlib.Path.home()/".local/share/srpp-local-ai")
    args = parser.parse_args(argv)
    try:
        root = args.source_root.expanduser().absolute()
        if root.is_symlink() or root.resolve(strict=True) != root:
            raise ValueError("UNSAFE_SOURCE_ROOT")
        bridge = load_bridge(root)
        state = args.state_dir.expanduser().absolute()
        target = pathlib.Path.home()/"Library/LaunchAgents"/(LABEL+".plist")
        current = None
        if target.exists() or target.is_symlink():
            if target.is_symlink():
                raise ValueError("EXISTING_JOB_NOT_OWNED")
            current = plistlib.loads(target.read_bytes())
            if not owned_job(current):
                raise ValueError("EXISTING_JOB_NOT_OWNED")
        job_id = f"gui/{os.getuid()}/{LABEL}"
        if args.stop:
            if current is not None:
                result = subprocess.run(["launchctl", "bootout", job_id], capture_output=True)
                if result.returncode not in (0, 3, 113):
                    raise ValueError("JOB_STOP_FAILED")
            print(json.dumps({"label": LABEL, "mode": "STOPPED", "statePreserved": True}))
            return 0
        if args.install:
            prepare_state(bridge, state)
            desired = job_configuration(root, state, pathlib.Path(sys.executable).resolve())
            target.parent.mkdir(parents=True, exist_ok=True)
            if current != desired:
                if current is not None:
                    result = subprocess.run(["launchctl", "bootout", job_id], capture_output=True)
                    if result.returncode not in (0, 3, 113):
                        raise ValueError("JOB_STOP_FAILED")
                atomic_file(target, plistlib.dumps(desired, sort_keys=True), 0o600)
            status = subprocess.run(["launchctl", "print", job_id], capture_output=True)
            if status.returncode:
                result = subprocess.run(["launchctl", "bootstrap", f"gui/{os.getuid()}", str(target)], capture_output=True)
                if result.returncode:
                    raise ValueError("JOB_START_FAILED")
            # A code update at the same source path does not change the plist.
            # Explicit install must replace the running process as well.
            result = subprocess.run(["launchctl", "kickstart", "-k", job_id], capture_output=True)
            if result.returncode:
                raise ValueError("JOB_RESTART_FAILED")
            status = subprocess.run(["launchctl", "print", job_id], capture_output=True)
            if status.returncode:
                raise ValueError("JOB_READBACK_FAILED")
        else:
            bridge.private_file(state/"bridge.token")
            bridge.load_registry(state/"registry.json")
            status = subprocess.run(["launchctl", "print", job_id], capture_output=True)
        print(json.dumps({"label": LABEL, "mode": "INSTALLED" if args.install else "CHECKED",
                          "jobLoaded": status.returncode == 0, "sourceRoot": str(root),
                          "bind": "127.0.0.1:8766", "tokenPreserved": True, "registryPreserved": True}))
        return 0
    except Exception as error:
        code = str(error) if isinstance(error, ValueError) and str(error).isupper() else "INSTALL_UNAVAILABLE"
        print(json.dumps({"mode": "UNAVAILABLE", "code": code}))
        return 1


if __name__ == "__main__":
    raise SystemExit(cli())
