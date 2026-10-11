#!/usr/bin/env python3
"""安裝本功能專屬 LaunchAgent；只允許正式 main，拒絕未知既有 job。"""
from __future__ import annotations

import argparse
import json
import os
import pathlib
import plistlib
import subprocess
import sys

sys.dont_write_bytecode = True
LABEL = "com.steven.srpp-ai-flow-collector"
MAIN = pathlib.Path("/Users/steven/Project/asset-management-main")


def job_configuration(main: pathlib.Path, python: pathlib.Path, state: pathlib.Path, token: pathlib.Path) -> dict:
    return {"Label": LABEL,
            "ProgramArguments": [str(python), str(main / "scripts/collect-srpp-ai-flow.py"), "--daemon",
                                 "--state-dir", str(state), "--token-file", str(token)],
            "WorkingDirectory": str(main), "RunAtLoad": True, "KeepAlive": {"SuccessfulExit": False},
            "ProcessType": "Background", "LowPriorityIO": True, "Nice": 10,
            "ThrottleInterval": 60, "StandardOutPath": str(state / "collector.log"),
            "StandardErrorPath": str(state / "collector-error.log")}


def owned_job(value: dict) -> bool:
    arguments = value.get("ProgramArguments")
    return (value.get("Label") == LABEL and value.get("WorkingDirectory") == str(MAIN)
            and isinstance(arguments, list) and len(arguments) >= 3
            and arguments[1] == str(MAIN / "scripts/collect-srpp-ai-flow.py") and arguments[2] == "--daemon")


def cli(argv=None):
    parser = argparse.ArgumentParser(description="正式main專屬AI流程背景collector，預設只檢查；--install才安裝。")
    parser.add_argument("--install", action="store_true")
    parser.add_argument("--token-file", type=pathlib.Path, default=pathlib.Path.home() / ".local/share/srpp-ai-flow/collector.token")
    parser.add_argument("--state-dir", type=pathlib.Path, default=pathlib.Path.home() / ".local/share/srpp-ai-flow")
    args = parser.parse_args(argv)
    try:
        main = MAIN.resolve(strict=True)
        if main != MAIN or main.is_symlink():
            raise ValueError("MAIN_PATH_UNAVAILABLE")
        branch = subprocess.check_output(["git", "-C", str(main), "branch", "--show-current"], text=True).strip()
        dirty = subprocess.check_output(["git", "-C", str(main), "status", "--porcelain", "--untracked-files=no"], text=True)
        if branch != "main" or dirty:
            raise ValueError("MAIN_NOT_CLEAN")
        script = main / "scripts/collect-srpp-ai-flow.py"
        sender = main / "scripts/send-srpp-ai-flow.py"
        if not script.is_file() or not sender.is_file() or script.is_symlink() or sender.is_symlink():
            raise ValueError("MAIN_COLLECTOR_UNAVAILABLE")
        # 憑證內容只在記憶體核對，不進輸出、plist、arguments或環境。
        import importlib.util
        spec = importlib.util.spec_from_file_location("ai_flow_sender_install", sender)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        state = args.state_dir.expanduser().absolute()
        module.collector.secure_directory(state)
        token = args.token_file.expanduser().absolute()
        module.read_token(token)
        python = pathlib.Path(sys.executable).resolve(strict=True)
        desired = job_configuration(main, python, state, token)
        agents = pathlib.Path.home() / "Library/LaunchAgents"
        agents.mkdir(parents=True, exist_ok=True)
        target = agents / (LABEL + ".plist")
        current = None
        if target.exists() or target.is_symlink():
            if target.is_symlink():
                raise ValueError("EXISTING_JOB_NOT_OWNED")
            current = plistlib.loads(target.read_bytes())
            if not owned_job(current):
                raise ValueError("EXISTING_JOB_NOT_OWNED")
        target_id = f"gui/{os.getuid()}/{LABEL}"
        if args.install:
            if current != desired:
                if current is not None:
                    result = subprocess.run(["launchctl", "bootout", target_id], capture_output=True)
                    if result.returncode not in (0, 3, 113):
                        raise ValueError("JOB_BOOTOUT_FAILED")
                module.collector.atomic_bytes(target, plistlib.dumps(desired, sort_keys=True))
            status = subprocess.run(["launchctl", "print", target_id], capture_output=True)
            if status.returncode != 0:
                result = subprocess.run(["launchctl", "bootstrap", f"gui/{os.getuid()}", str(target)], capture_output=True)
                if result.returncode != 0:
                    raise ValueError("JOB_BOOTSTRAP_FAILED")
            result = subprocess.run(["launchctl", "print", target_id], capture_output=True, text=True)
            if result.returncode != 0 or str(script) not in result.stdout:
                raise ValueError("JOB_READBACK_FAILED")
        print(json.dumps({"label": LABEL, "mode": "INSTALLED" if args.install else "CHECKED",
                          "mainScript": str(script), "background": True, "lowPriorityIO": True,
                          "periodSeconds": 60, "existingJobOwned": current is not None}, ensure_ascii=False))
        return 0
    except Exception as exc:
        code = str(exc) if isinstance(exc, ValueError) and str(exc).isupper() else "INSTALL_UNAVAILABLE"
        print(json.dumps({"mode": "INSTALL_UNAVAILABLE", "code": code}, ensure_ascii=False))
        return 1


if __name__ == "__main__":
    raise SystemExit(cli())
