#!/usr/bin/env python3
"""被動收集公開執行紀錄；收集／enqueue 路徑絕不發出 HTTP。

來源 SQLite 及 JSONL 一律唯讀。只有清洗後的互通事件會寫入本功能自己的
0700 state 目錄。上傳由 send-srpp-ai-flow.py 的另一個 process 處理。
"""
from __future__ import annotations

import argparse
import contextlib
import datetime as dt
import fcntl
import hashlib
import json
import os
import pathlib
import re
import sqlite3
import stat
import subprocess
import sys
import time
import uuid
from dataclasses import dataclass
from typing import Any
from urllib.parse import urlsplit

TAIPEI = dt.timezone(dt.timedelta(hours=8), "Asia/Taipei")
NAMESPACE = uuid.UUID("a265309b-6621-5199-8706-e8a608e4b491")
CODEX_TASKS = {"codex-srpp-09-30": "09:05", "codex-srpp": "12:00", "22-00": "22:00"}
CLAUDE_TASKS = {"srpp-0905-claude": "09:05", "srpp-1150-claude": None}
OUTCOMES = {"COMPLETED", "PARTIAL", "INCOMPLETE", "STOPPED_L0", "STOPPED_CONTRACT", "SKIPPED_NON_TRADING_DAY"}
ROLES = {"CALENDAR", "POLICY", "API_RECEIPT", "VALIDATOR", "RESULT", "TIMING", "DRAFT_RECEIPT", "MIME_RECEIPT", "APP_TERMINAL", "TRACE_COVERAGE"}
EVENT_KEYS = {"eventId", "sequence", "stepId", "eventKind", "status", "occurredAt", "title", "summary", "operation", "reasonCode", "attemptNo", "parentEventIds", "relation", "evidenceRefs", "reportedOutcome"}
RUN_KEYS = {"runId", "consumer", "reportDate", "reportSlot", "sourceKind", "sourceRunId", "automationId", "startedAt", "workflowVersion"}
OPAQUE = re.compile(r"^[A-Za-z0-9_.:-]{1,200}$")
HTTP_PATH = re.compile(r"/api(?:/[A-Za-z0-9._~{}-]+)+")
DEFAULT_ENDPOINT = "http://127.0.0.1:9090/api/srpp/ai-flow/events"
# 公開工具名稱（Task 500）：只接受閉合識別字，其餘一律回到原泛稱。
TOOL_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}")
INNER_TOOL_DOT = re.compile(r"\btools\.([A-Za-z_][A-Za-z0-9_]{0,63})\s*\(")
INNER_TOOL_BRACKET = re.compile(r"""\btools\[\s*["']([A-Za-z0-9_.:-]{1,64})["']\s*\]""")
COMPOSITE_TOOLS = {"exec", "run_code"}
TOOL_LABEL_LIMIT = 80
TOOL_TITLE_NAMES = 3
TOOL_STATE_NAMES = 8
TOOL_SUMMARY_NOTE = "內含工具名稱取自呼叫文字，未收錄參數、指令或輸出。"
# Task 501：完整公開工具參數經使用者明確授權，但仍盡力遮蔽憑證樣式。
# 這些值是 observer／backend／BFF 共用契約的一部分，不能各自放寬。
TOOL_PARAM_LIMIT = 16384
TOOL_PARAM_PRESCAN_LIMIT = 1048576
REDACTED = "[REDACTED]"
REDACTED_COUNT_MAX = 100000
TOOL_CALL_KEYS = {"name", "innerNames", "parameters", "parametersChars", "truncated", "redactedCount"}
TOOL_CALL_OPTIONAL_KEYS = TOOL_CALL_KEYS | {"sourceTruncated"}

# 只在 key 的最後連續段符合這些具名秘密時遮蔽；例如 X-AI-Flow-Token
# 命中末段 token，secret_santa 與 max_tokens 則不命中。
SECRET_KEY_SUFFIXES = (
    ("authorization",), ("token",), ("secret",), ("password",), ("passwd",),
    ("apikey",), ("api", "key"), ("access", "key"), ("private", "key"),
    ("cookie",), ("credential",),
)
BEARER_CREDENTIAL = re.compile(
    r"\b(?P<prefix>(?:Bearer|Basic)\s+)(?P<secret>[A-Za-z0-9._~+/=-]{8,})", re.I
)
# key／value 可以位於普通 shell / JSON，也可以是另一層 JSON 字串中的 \"key\"。value
# 刻意不跨越引號、反斜線、空白及常見命令分隔符，避免吃掉後續公開文字。
NAMED_CREDENTIAL = re.compile(
    r"(?<![A-Za-z0-9_.-])(?P<key>[A-Za-z][A-Za-z0-9_. -]{0,127}?)"
    r"(?P<key_quote>\\?[\"'])?\s*[:=]\s*(?P<value_quote>\\?[\"'])?"
    r"(?P<value>[^\s\"'\\\\,;&)}\[\]]+)",
)
JWT_CREDENTIAL = re.compile(
    r"(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]*\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+(?![A-Za-z0-9_-])"
)
PROVIDER_CREDENTIAL = re.compile(
    r"(?<![A-Za-z0-9_-])(?:"
    r"sk-[A-Za-z0-9_-]{20,}|"
    r"gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,}|"
    r"AKIA[0-9A-Z]{16}|"
    r"xox[abprs]-[A-Za-z0-9-]{10,}|"
    r"AIza[A-Za-z0-9_-]{35}|"
    r"ya29\.[A-Za-z0-9._-]{8,}"
    r")(?![A-Za-z0-9_-])"
)
URL_USERINFO_CREDENTIAL = re.compile(
    r"(?P<prefix>\b[A-Za-z][A-Za-z0-9+.-]*://[^/\s:@]+:)(?P<secret>[^/\s@]+)(?=@)"
)
PEM_PRIVATE_KEY = re.compile(
    r"-----BEGIN [^\r\n-]*PRIVATE KEY-----.*?(?:-----END [^\r\n-]*PRIVATE KEY-----|\Z)", re.S
)
PARAMETER_SURROGATE = re.compile(r"[\ud800-\udfff]")
PARAMETER_CONTROL = re.compile(r"[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]")
# 與 backend AiFlowProtocol.SENSITIVE 的 pattern 字串逐分支一致（Java 的 (?is) 對應 re.I|re.S，
# \b／\s 為 ASCII 語意故加 re.A）。測試會從 Java 原始碼解析比對，backend 改規則時此處必須同步。
BACKEND_SENSITIVE_PATTERN = (
    r"(authorization|bearer\s|token\s*[:=]|password|secret|api[_-]?key|private[_ -]?reasoning|chain.of.thought|"
    r"\b(?:curl|wget|printenv|export)\s|\$\(|```|<[^>]+>|[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}|[A-Za-z]:\\|/Users/|/home/|"
    r"\b(?:NT\$|USD|TWD)\s*[0-9]|(?:price|amount|balance|holdings|account|成本|金額|持股|庫存|股數|張數|帳號|帳戶)\s*[:=：]?\s*[0-9]|[0-9]{6,})"
)
BACKEND_SENSITIVE = re.compile(BACKEND_SENSITIVE_PATTERN, re.I | re.S | re.A)


class ObservationError(Exception):
    """只公開固定錯誤碼，避免路徑、來源內容或憑證進入日誌。"""


def _code_point_count(text: str) -> int:
    """以 Unicode code point 計數，將手動組出的合法 surrogate pair 視為一個字元。"""
    if PARAMETER_SURROGATE.search(text) is None:
        return len(text)
    count, index = 0, 0
    while index < len(text):
        current = ord(text[index])
        if 0xD800 <= current <= 0xDBFF and index + 1 < len(text) and 0xDC00 <= ord(text[index + 1]) <= 0xDFFF:
            index += 2
        else:
            index += 1
        count += 1
    return count


def _take_code_points(text: str, limit: int) -> str:
    """取前 limit 個 Unicode code point，不在 surrogate pair 中間截斷。"""
    if limit <= 0:
        return ""
    if PARAMETER_SURROGATE.search(text) is None:
        return text[:limit]
    if len(text) <= limit:
        return text
    count, index = 0, 0
    while index < len(text) and count < limit:
        current = ord(text[index])
        width = 2 if 0xD800 <= current <= 0xDBFF and index + 1 < len(text) and 0xDC00 <= ord(text[index + 1]) <= 0xDFFF else 1
        index += width
        count += 1
    return text[:index]


def _has_invalid_parameter_character(text: str) -> bool:
    """檢查契約禁止的控制字元、DEL 與孤立 surrogate；成對 surrogate 合法。"""
    if PARAMETER_SURROGATE.search(text) is None:
        return PARAMETER_CONTROL.search(text) is not None
    index = 0
    while index < len(text):
        code = ord(text[index])
        if 0xD800 <= code <= 0xDBFF:
            if index + 1 < len(text) and 0xDC00 <= ord(text[index + 1]) <= 0xDFFF:
                index += 2
                continue
            return True
        if 0xDC00 <= code <= 0xDFFF:
            return True
        if (code < 0x20 and code not in {0x09, 0x0A, 0x0D}) or code == 0x7F:
            return True
        index += 1
    return False


def _secret_key_segments(key: str) -> list[str]:
    camel_split = re.sub(r"(?<=[a-z0-9])(?=[A-Z])", " ", key)
    return [piece.lower() for piece in re.split(r"[_ .-]+", camel_split) if piece]


def _is_named_secret_key(key: str) -> bool:
    segments = _secret_key_segments(key)
    return any(len(segments) >= len(suffix) and tuple(segments[-len(suffix):]) == suffix
               for suffix in SECRET_KEY_SUFFIXES)


def redact_credentials(text: str) -> tuple[str, int]:
    """以固定順序 best-effort 遮蔽常見憑證，只回傳遮蔽後字串與實際取代次數。"""
    if not isinstance(text, str):
        return text, 0
    count = 0

    def substitute(pattern: re.Pattern[str], replacement):
        nonlocal text, count
        text, matches = pattern.subn(replacement, text)
        count += matches

    substitute(BEARER_CREDENTIAL, lambda match: match.group("prefix") + REDACTED)

    def named_replacement(match: re.Match[str]) -> str:
        nonlocal count
        value = match.group("value")
        # Authorization: Bearer [REDACTED] 已由第一條規則處理，不能再把 Bearer 當祕密值。
        if (len(value) < 4 or value == REDACTED or not _is_named_secret_key(match.group("key"))
                or re.match(r"(?:Bearer|Basic)\s+\[REDACTED\]", match.string[match.start("value"):], re.I)):
            return match.group(0)
        count += 1
        before = match.group(0)[:match.start("value") - match.start()]
        after = match.group(0)[match.end("value") - match.start():]
        return before + REDACTED + after

    text = NAMED_CREDENTIAL.sub(named_replacement, text)
    substitute(JWT_CREDENTIAL, REDACTED)
    substitute(PROVIDER_CREDENTIAL, REDACTED)
    def userinfo_replacement(match: re.Match[str]) -> str:
        nonlocal count
        if match.group("secret") == REDACTED:
            return match.group(0)
        count += 1
        return match.group("prefix") + REDACTED

    text = URL_USERINFO_CREDENTIAL.sub(userinfo_replacement, text)
    substitute(PEM_PRIVATE_KEY, REDACTED)
    return text, count


def clean_parameter_text(text: str) -> str:
    """將契約禁止的控制字元、DEL 與孤立 surrogate 改為 replacement character。"""
    if PARAMETER_SURROGATE.search(text) is None:
        return PARAMETER_CONTROL.sub("\ufffd", text)
    parts: list[str] = []
    index = 0
    while index < len(text):
        char = text[index]
        code = ord(char)
        if 0xD800 <= code <= 0xDBFF and index + 1 < len(text) and 0xDC00 <= ord(text[index + 1]) <= 0xDFFF:
            parts.append(chr(0x10000 + (code - 0xD800) * 0x400 + (ord(text[index + 1]) - 0xDC00)))
            index += 2
            continue
        if 0xD800 <= code <= 0xDFFF or (code < 0x20 and code not in {0x09, 0x0A, 0x0D}) or code == 0x7F:
            parts.append("\ufffd")
        else:
            parts.append(char)
        index += 1
    return "".join(parts)


def validate_tool_call(value: Any, event_kind: Any) -> bool:
    """驗證 Task 501 的閉合 toolCall，與 backend／BFF 使用同一個契約。"""
    if (event_kind != "START" or not isinstance(value, dict)
            or (set(value) != TOOL_CALL_KEYS and set(value) != TOOL_CALL_OPTIONAL_KEYS)):
        return False
    name = value.get("name")
    inner_names = value.get("innerNames")
    parameters = value.get("parameters")
    parameters_chars = value.get("parametersChars")
    truncated = value.get("truncated")
    redacted_count = value.get("redactedCount")
    if not isinstance(name, str) or TOOL_NAME.fullmatch(name) is None:
        return False
    if not isinstance(inner_names, list) or len(inner_names) > TOOL_STATE_NAMES:
        return False
    if any(not isinstance(item, str) or TOOL_NAME.fullmatch(item) is None for item in inner_names) or len(set(inner_names)) != len(inner_names):
        return False
    if not isinstance(parameters, str) or _code_point_count(parameters) > TOOL_PARAM_LIMIT or _has_invalid_parameter_character(parameters):
        return False
    if (isinstance(parameters_chars, bool) or not isinstance(parameters_chars, int)
            or not 0 <= parameters_chars <= 2147483647):
        return False
    if not isinstance(truncated, bool) or isinstance(redacted_count, bool) or not isinstance(redacted_count, int) or not 0 <= redacted_count <= REDACTED_COUNT_MAX:
        return False
    length = _code_point_count(parameters)
    if parameters_chars < length or truncated != (parameters_chars > length):
        return False
    if truncated and length != TOOL_PARAM_LIMIT:
        return False
    return "sourceTruncated" not in value or value["sourceTruncated"] is True


def tool_call(payload: dict, inner_names: list[str]) -> dict | None:
    """建立 START 專用工具參數；不讀 I/O、不再呼叫 tool_label 或掃描內含工具。"""
    try:
        name = payload.get("name")
        if not isinstance(name, str) or TOOL_NAME.fullmatch(name) is None:
            return None
        names: list[str] = []
        seen: set[str] = set()
        for inner in inner_names:
            if isinstance(inner, str) and TOOL_NAME.fullmatch(inner) is not None and inner not in seen:
                seen.add(inner)
                names.append(inner)
                if len(names) == TOOL_STATE_NAMES:
                    break
        raw = payload.get("input", payload.get("arguments", ""))
        if isinstance(raw, str):
            raw_text = raw
        elif isinstance(raw, (dict, list)):
            raw_text = json.dumps(raw, ensure_ascii=False, separators=(",", ":"))
        else:
            raw_text = ""
        source_truncated = _code_point_count(raw_text) > TOOL_PARAM_PRESCAN_LIMIT
        processed = _take_code_points(raw_text, TOOL_PARAM_PRESCAN_LIMIT)
        redacted, redacted_count = redact_credentials(processed)
        cleaned = clean_parameter_text(redacted)
        parameters_chars = _code_point_count(cleaned)
        truncated = parameters_chars > TOOL_PARAM_LIMIT
        result = {
            "name": name,
            "innerNames": names,
            "parameters": _take_code_points(cleaned, TOOL_PARAM_LIMIT),
            "parametersChars": parameters_chars,
            "truncated": truncated,
            "redactedCount": min(redacted_count, REDACTED_COUNT_MAX),
        }
        if source_truncated:
            result["sourceTruncated"] = True
        return result if validate_tool_call(result, "START") else None
    except Exception:
        return None


def canonical(value: Any) -> bytes:
    # 此協定只有安全整數與文字，不含浮點數；排序按 UTF-16 code units (JCS)。
    def ordered(v: Any) -> Any:
        if isinstance(v, dict):
            return {k: ordered(v[k]) for k in sorted(v, key=lambda s: s.encode("utf-16-be", "surrogatepass"))}
        if isinstance(v, list):
            return [ordered(item) for item in v]
        return v
    return json.dumps(ordered(value), ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")


def strict_json(raw: bytes | str) -> Any:
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ObservationError("DUPLICATE_FIELD")
            result[key] = value
        return result
    try:
        return json.loads(raw, object_pairs_hook=pairs, parse_constant=lambda _: (_ for _ in ()).throw(ObservationError("INVALID_JSON")))
    except (UnicodeError, ValueError, TypeError) as exc:
        raise ObservationError("INVALID_JSON") from exc


def stable_id(source_id: str, name: str) -> str:
    return str(uuid.uuid5(uuid.uuid5(NAMESPACE, source_id), name))


def iso(value: Any) -> str | None:
    if not isinstance(value, str) or len(value) > 50:
        return None
    try:
        parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
        if parsed.tzinfo is None:
            return None
        return parsed.astimezone(TAIPEI).isoformat(timespec="milliseconds")
    except ValueError:
        return None


def epoch_ms(value: Any) -> dt.datetime:
    # App automation_runs.created_at / Claude createdAt 是毫秒，絕不自動猜秒。
    if isinstance(value, bool) or not isinstance(value, int) or not 946684800000 <= value <= 4102444800000:
        raise ObservationError("INVALID_EPOCH_MILLISECONDS")
    return dt.datetime.fromtimestamp(value / 1000, TAIPEI)


def public_time(value: Any) -> str | None:
    if isinstance(value, int) and not isinstance(value, bool):
        try:
            return epoch_ms(value).isoformat(timespec="milliseconds")
        except ObservationError:
            return None
    return iso(value)


def date_in_window(value: str, today: dt.date) -> dt.date:
    try:
        parsed = dt.date.fromisoformat(value)
    except (TypeError, ValueError) as exc:
        raise ObservationError("INVALID_DATE") from exc
    if parsed.isoformat() != value or parsed > today:
        raise ObservationError("INVALID_DATE")
    if parsed < today - dt.timedelta(days=89):
        raise ObservationError("RETENTION_EXPIRED")
    return parsed


def safe_source(path: pathlib.Path, root: pathlib.Path, *, exists: bool = True) -> pathlib.Path:
    # 不接受來源 symlink，包含 canonical root 以下任何一段，避免 TOCTOU 逃逸。
    root = root.expanduser().absolute()
    path = path.expanduser().absolute()
    try:
        relative = path.relative_to(root)
    except ValueError as exc:
        raise ObservationError("SOURCE_ROOT_ESCAPE") from exc
    current = root
    if root.is_symlink():
        raise ObservationError("SOURCE_SYMLINK")
    for part in relative.parts:
        current /= part
        if current.is_symlink():
            raise ObservationError("SOURCE_SYMLINK")
    try:
        resolved = path.resolve(strict=exists)
        resolved.relative_to(root.resolve(strict=True))
    except (OSError, ValueError) as exc:
        raise ObservationError("SOURCE_UNAVAILABLE") from exc
    return resolved


@contextlib.contextmanager
def open_readonly(path: pathlib.Path, root: pathlib.Path):
    resolved = safe_source(path, root)
    fd = os.open(resolved, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    try:
        if not stat.S_ISREG(os.fstat(fd).st_mode):
            raise ObservationError("SOURCE_NOT_REGULAR")
        with os.fdopen(fd, "rb") as stream:
            fd = -1
            yield stream
    finally:
        if fd >= 0:
            os.close(fd)


@contextlib.contextmanager
def readonly_db(path: pathlib.Path, root: pathlib.Path, table: str, fields: set[str]):
    path = safe_source(path, root)
    connection = sqlite3.connect(path.as_uri() + "?mode=ro", uri=True, timeout=0.2)
    try:
        connection.execute("PRAGMA query_only=ON")
        actual = {row[1] for row in connection.execute("PRAGMA table_info(" + table + ")")}
        if not fields <= actual:
            raise ObservationError("SOURCE_SCHEMA_UNAVAILABLE")
        yield connection
    finally:
        connection.close()


def event(source_id: str, identity: str, sequence: int, step: str, kind: str,
          timestamp: Any, title: str, summary: str, *, status="UNKNOWN", operation=None,
          parents=(), relation=None, reason=None, refs=(), outcome=None, attempt=1) -> dict:
    return {"eventId": stable_id(source_id, identity), "sequence": sequence, "stepId": step,
            "eventKind": kind, "status": status, "occurredAt": iso(timestamp), "title": title,
            "summary": summary, "operation": operation or {"method": "LOCAL", "path": "artifact"},
            "reasonCode": reason, "attemptNo": attempt, "parentEventIds": list(parents),
            "relation": relation, "evidenceRefs": list(refs), "reportedOutcome": outcome}


def coverage(source_id: str, cursor: int, size: int, digest: str, reason: str) -> dict:
    if not 0 <= cursor <= size <= 2147483647:
        raise ObservationError("SOURCE_TOO_LARGE")
    return event(source_id, f"TRACE_V1:{cursor}:{size}:{digest}:{reason}", cursor, "trace-coverage",
                 "OBSERVATION", None, "紀錄完整度檢查", "檢查公開來源的完整度，未驗證金融報告結果。",
                 reason=reason, refs=[{"role": "TRACE_COVERAGE", "id": f"TRACE_V1:{cursor}:{size}", "hash": digest}])


def canonical_http_path(value: Any) -> bool:
    return isinstance(value, str) and len(value) <= 500 and ".." not in value and HTTP_PATH.fullmatch(value) is not None


def operation_for_call(payload: dict) -> dict:
    name = payload.get("name", "")
    if not isinstance(name, str):
        return {"method": "TOOL", "path": "tool"}
    text = payload.get("input", payload.get("arguments", ""))
    if not isinstance(text, str):
        text = ""
    # 複合 exec 永遠不把整批工具耗時歸給其中一個 HTTP API。
    if name.rsplit(".", 1)[-1] in {"exec", "run_code"}:
        return {"method": "TOOL", "path": "tool"}
    if name in {"exec_command", "functions.exec_command", "tools.exec_command"}:
        if "交易日曆_" in text or "trading_calendar" in text:
            return {"method": "LOCAL", "path": "calendar"}
        if "policy_bundle" in text or "runtime_contract" in text:
            return {"method": "LOCAL", "path": "policy"}
        if re.search(r"(?:validator|validate_)", text):
            return {"method": "LOCAL", "path": "validation"}
        if re.search(r"(?:render_|render_report)", text):
            return {"method": "LOCAL", "path": "render"}
        candidates = re.findall(r"https?://[^\s'\"\\]+|/api[^\s'\"\\]*", text)
        try:
            paths = {urlsplit(candidate).path for candidate in candidates}
        except ValueError:
            return {"method": "TOOL", "path": "tool"}
        if len(paths) == 1 and re.search(r"\bcurl\b", text) and not re.search(r"(?:[;|]|\$\(|&&|\n)", text):
            methods = re.findall(r"(?:-X|--request)\s+(GET|POST|PUT|PATCH|DELETE)\b", text)
            path = paths.pop()
            if len(methods) <= 1 and canonical_http_path(path):
                return {"method": methods[0] if methods else "GET", "path": path}
    return {"method": "TOOL", "path": "tool"}


def build_tool_label(name: str, names: list[str]) -> str:
    """`<name>` 或 `<name>（內含 a、b、c 等 N 項）`；整體不超過80字元。

    title 最多列3個內含名稱；顯示數少於去重總數（含被長度裁掉者）一律附「等 N 項」。
    放不下時逐一減少內含名稱，一個也放不下就只留 `<name>`。
    """
    total = len(names)
    for shown in range(min(TOOL_TITLE_NAMES, total), 0, -1):
        suffix = f" 等 {total} 項" if shown < total else ""
        label = f"{name}（內含 {'、'.join(names[:shown])}{suffix}）"
        if len(label) <= TOOL_LABEL_LIMIT:
            return label
    return name


def tool_label(payload: dict) -> tuple[str, list[str]] | None:
    """從公開工具呼叫取得 (標籤, 去重後內含工具識別字)；不合白名單回 None。

    複合 exec／run_code 的實際工具不在 payload.name，只從呼叫文字擷取
    `tools.<名稱>(` 或 `tools["名稱"]` 的識別字；本函式只保存名稱，不把參數
    寫進 title／summary／label。Task 501 的 tool_call() 另依閉合契約保存已遮蔽
    toolCall.parameters，仍不保存工具輸出。
    名稱取自呼叫文字而非精確解析，字串字面值內的 `tools.x(` 也可能被擷取。
    """
    name = payload.get("name")
    if not isinstance(name, str) or TOOL_NAME.fullmatch(name) is None:
        return None
    names: list[str] = []
    if name.rsplit(".", 1)[-1] in COMPOSITE_TOOLS:
        text = payload.get("input", payload.get("arguments", ""))
        if isinstance(text, str):
            hits = [(match.start(), match[1]) for match in INNER_TOOL_DOT.finditer(text)]
            hits += [(match.start(), match[1]) for match in INNER_TOOL_BRACKET.finditer(text)]
            seen: set[str] = set()
            for _, inner in sorted(hits):
                if TOOL_NAME.fullmatch(inner) is not None and inner not in seen:
                    seen.add(inner)
                    names.append(inner)
    return build_tool_label(name, names), names


def tool_label_info(label: tuple[str, list[str]] | None) -> dict | None:
    """寫入 collector state 的標籤；只存 label、至多8個名稱與去重總數。"""
    if label is None:
        return None
    return {"label": label[0], "names": list(label[1][:TOOL_STATE_NAMES]), "total": len(label[1])}


def usable_label_info(value: Any) -> dict | None:
    """state 內的標籤可能缺漏（部署前的舊 call）或被改壞；無法確認一律視為沒有標籤。"""
    if not isinstance(value, dict):
        return None
    label, names, total = value.get("label"), value.get("names"), value.get("total")
    if not isinstance(label, str) or not 1 <= len(label) <= TOOL_LABEL_LIMIT:
        return None
    if not isinstance(names, list) or len(names) > TOOL_STATE_NAMES:
        return None
    if any(not isinstance(item, str) or TOOL_NAME.fullmatch(item) is None for item in names):
        return None
    if type(total) is not int or total < len(names):
        return None
    return {"label": label, "names": names}


def public_tool_text(prefix: str, label_info: Any, fallback_title: str, fallback_summary: str) -> tuple[str, str]:
    """公開工具事件 title／summary 的唯一組裝點；任何檢查失敗都回到原泛稱。"""
    info = usable_label_info(label_info)
    if info is None:
        return fallback_title, fallback_summary
    title = prefix + "：" + info["label"]
    summary = fallback_summary
    # 先逐一減少名稱到放得下；單名上限64字元，保留1個名稱必然放得下。
    for keep in range(len(info["names"]), 0, -1):
        candidate = fallback_summary + TOOL_SUMMARY_NOTE + "、".join(info["names"][:keep])
        if len(candidate) <= 500:
            summary = candidate
            break
    if len(title) > 100 or len(summary) > 500:
        return fallback_title, fallback_summary
    for text in (title, summary):
        if unsafe_public_text(text) or BACKEND_SENSITIVE.search(text) is not None:
            return fallback_title, fallback_summary
    return title, summary


def tool_result_status(payload: dict) -> str:
    value = payload.get("output")
    if isinstance(value, str) and len(value) <= 1024 * 1024:
        try:
            value = strict_json(value)
        except ObservationError:
            value = None
    if isinstance(value, dict):
        if value.get("isError") is True or value.get("exit_code") not in (None, 0):
            return "FAILED"
        if value.get("isError") is False or value.get("exit_code") == 0:
            return "SUCCEEDED"
    return "UNKNOWN"


def run_metadata(consumer: str, day: str, slot: str, source: str, identity: str,
                 automation: str, started=None) -> dict:
    return {"runId": stable_id(identity, "run:AI_FLOW_V1"), "consumer": consumer, "reportDate": day,
            "reportSlot": slot, "sourceKind": source, "sourceRunId": identity,
            "automationId": automation, "startedAt": iso(started), "workflowVersion": "AI_FLOW_V1"}


def parse_source_line(row: dict, line: int, state: dict, thread_id: str, automation: str,
                      day: str, slot: str) -> None:
    payload = row.get("payload")
    if not isinstance(payload, dict):
        return
    row_type, kind = row.get("type"), payload.get("type")
    if row_type == "session_meta":
        if payload.get("id") != thread_id:
            state["identityMismatch"] = True
        return
    if row_type == "event_msg" and kind == "task_started":
        turn_id = payload.get("turn_id")
        if not isinstance(turn_id, str) or not OPAQUE.fullmatch(turn_id):
            state["malformed"] = True
            return
        identity = thread_id + ":" + turn_id
        if len(identity) > 200:
            state["malformed"] = True
            return
        turns = state.setdefault("turns", {})
        if turn_id not in turns:
            attempt = len(turns) + 1
            started = public_time(payload.get("started_at")) or iso(row.get("timestamp"))
            turns[turn_id] = {"run": run_metadata("Codex", day, slot, "CODEX_ROLLOUT", identity, automation, started),
                              "events": [], "calls": {}, "complete": False, "final": False,
                              "missingChild": False, "attempt": attempt, "last": None}
            turn = turns[turn_id]
            append_event(turn, event(identity, f"line:{line}:task_started", line, "task-started", "START", started,
                         "排程開始" if attempt == 1 else "後續嘗試開始", "已觀測到本輪公開開始紀錄。",
                         status="RUNNING", refs=[{"role": "APP_TERMINAL", "id": "task_started", "hash": None}], attempt=attempt))
        state["activeTurn"] = turn_id
        return
    if row_type == "turn_context":
        turn_id = payload.get("turn_id")
        if turn_id in state.get("turns", {}):
            state["activeTurn"] = turn_id
        else:
            state["activeTurn"] = None
        return
    explicit_turn = payload.get("turn_id")
    turn_id = explicit_turn if explicit_turn is not None else state.get("activeTurn")
    turn = state.get("turns", {}).get(turn_id)
    if turn is None:
        return
    source_id, attempt = turn["run"]["sourceRunId"], turn["attempt"]
    timestamp = row.get("timestamp")
    if row_type == "event_msg" and kind == "task_complete":
        turn["complete"] = True
        append_event(turn, event(source_id, f"line:{line}:task_complete", line, "task-complete", "TERMINAL",
                     public_time(payload.get("completed_at")) or timestamp, "本輪執行結束", "只證明執行已結束，報告結果仍未確認。",
                     refs=[{"role": "APP_TERMINAL", "id": "task_complete", "hash": None}], attempt=attempt))
        state["activeTurn"] = None
    elif row_type == "response_item" and kind in {"function_call", "custom_tool_call"}:
        call_id = payload.get("call_id")
        if not isinstance(call_id, str) or not OPAQUE.fullmatch(call_id):
            state["malformed"] = True
            return
        # 重複 call_id 不能在另一 turn 配對，也不覆蓋原呼叫。
        if call_id in turn["calls"]:
            state["malformed"] = True
            return
        step = "call-" + hashlib.sha256(call_id.encode()).hexdigest()[:32]
        operation = operation_for_call(payload)
        name = payload.get("name", "")
        if isinstance(name, str) and re.search(r"(?:spawn_agent|spawn_thread|create_thread)$", name):
            turn["missingChild"] = True
        # 同一呼叫只執行一次完整的名稱掃描；ToolCall.innerNames 重用這個結果。
        label = tool_label(payload)
        label_info = tool_label_info(label)
        title, summary = public_tool_text("公開工具呼叫", label_info, "公開工具呼叫", "已觀測到工具開始；複合工具只記錄整批耗時。")
        call = event(source_id, f"line:{line}:call:{call_id}", line, step, "START", timestamp,
                     title, summary, status="RUNNING", operation=operation, attempt=attempt)
        captured = tool_call(payload, label[1] if label is not None else [])
        if captured is not None:
            call["toolCall"] = captured
        append_event(turn, call)
        turn["calls"][call_id] = {"eventId": call["eventId"], "stepId": step, "operation": operation, "paired": False,
                                 "occurredAt": call["occurredAt"], "label": label_info}
    elif row_type == "response_item" and kind in {"function_call_output", "custom_tool_call_output"}:
        call_id = payload.get("call_id")
        call = turn["calls"].get(call_id)
        if call is None or call["paired"]:
            state["malformed"] = True
            return
        call["paired"] = True
        # 部署前已入 state 的舊 call 沒有 label 欄位；缺值維持原泛稱，不得 KeyError。
        title, summary = public_tool_text("公開工具回傳", call.get("label"), "公開工具回傳", "已觀測到工具輸出，不推定金融操作或報告成功。")
        completed = event(source_id, f"line:{line}:output:{call_id}", line, call["stepId"], "END", timestamp,
                          title, summary, status=tool_result_status(payload), operation=call["operation"],
                          parents=[call["eventId"]], relation="CALL_RESULT", attempt=attempt)
        if call.get("occurredAt") and completed["occurredAt"] and dt.datetime.fromisoformat(completed["occurredAt"]) < dt.datetime.fromisoformat(call["occurredAt"]):
            state["malformed"] = True
        append_event(turn, completed, chronology=False)
    elif row_type == "response_item" and kind == "message" and payload.get("role") == "assistant":
        phase = payload.get("phase") or payload.get("channel")
        if phase in {"final_answer", "final"}:
            turn["final"] = True
            append_event(turn, event(source_id, f"line:{line}:final", line, "final-answer", "OBSERVATION", timestamp,
                         "AI 提供最終回覆", "已觀測到公開最終回覆；不儲存報告全文或私人推理。",
                         refs=[{"role": "APP_TERMINAL", "id": "final_answer", "hash": None}], attempt=attempt))
        elif phase == "commentary":
            append_event(turn, event(source_id, f"line:{line}:commentary", line, "progress-" + str(line), "OBSERVATION",
                         timestamp, "AI 提供執行進度", "已觀測到公開進度訊息，未收錄金融資料或私人推理。", attempt=attempt))


def append_event(turn: dict, item: dict, *, chronology=True) -> None:
    if chronology and turn["last"] is not None:
        item["parentEventIds"] = [turn["last"]]
        item["relation"] = "NEXT_OBSERVED"
    turn["events"].append(item)
    turn["last"] = item["eventId"]


@dataclass
class ReadBudget:
    remaining: int = 64 * 1024 * 1024
    per_run: int = 32 * 1024 * 1024
    seconds: float = 5.0
    runs: int = 25
    start: float = 0.0
    consumed: int = 0

    def __post_init__(self):
        self.start = time.monotonic()

    def available(self) -> bool:
        return self.remaining > 0 and time.monotonic() - self.start < self.seconds

    def charge(self, count: int):
        self.remaining -= count
        self.consumed += count


class Store:
    def __init__(self, directory: pathlib.Path, today: dt.date, *, dry_run=False):
        self.root, self.today, self.dry_run = directory.expanduser().absolute(), today, dry_run
        self.memory: dict[str, dict] = {}
        self.purge_iterators: dict[str, Any] = {}
        if not dry_run:
            secure_directory(self.root)
            for part in ("sources", "pending", "acks"):
                secure_directory(self.root / part)

    def load(self, name: str) -> dict:
        if self.dry_run:
            return self.memory.get(name, {})
        path = self.root / "sources" / (name + ".json")
        if not path.exists():
            return {}
        try:
            with open_readonly(path, self.root) as stream:
                value = strict_json(stream.read(64 * 1024 * 1024))
            return value if isinstance(value, dict) else {}
        except (OSError, ObservationError):
            raise ObservationError("STATE_UNAVAILABLE")

    def close(self):
        for iterator in self.purge_iterators.values():
            iterator.close()
        self.purge_iterators.clear()

    def save(self, name: str, value: dict):
        if self.dry_run:
            self.memory[name] = value
        else:
            atomic_json(self.root / "sources" / (name + ".json"), value)

    def ack_ids(self, run: dict) -> set[str]:
        path = self.root / "acks" / (run["runId"] + ".json")
        if self.dry_run or not path.exists():
            return set()
        with open_readonly(path, self.root) as stream:
            value = strict_json(stream.read(16 * 1024 * 1024))
        return set(value.get("eventIds", []))

    def enqueue(self, envelope: dict, *, replay=False) -> int:
        validate_envelope(envelope, self.today)
        if self.dry_run:
            return len(envelope["events"])
        run = envelope["run"]
        acked = set() if replay else self.ack_ids(run)
        events = [item for item in envelope["events"] if item["eventId"] not in acked]
        inserted = 0
        for offset in range(0, len(events), 100):
            chunk = events[offset:offset + 100]
            # 最多256KiB；大摘要批次自動縮小，不截斷或丟失 event。
            queue = [chunk]
            while queue:
                current = queue.pop(0)
                batch = {"schemaVersion": 1, "run": run, "events": current}
                encoded = canonical(batch)
                if len(encoded) > 256 * 1024:
                    if len(current) == 1:
                        raise ObservationError("EVENT_TOO_LARGE")
                    mid = len(current) // 2
                    queue[0:0] = [current[:mid], current[mid:]]
                    continue
                digest = hashlib.sha256(encoded).hexdigest()
                seq = min(item["sequence"] for item in current)
                path = self.root / "pending" / f"{run['runId']}-{seq:010d}-{digest}.json"
                if path.exists():
                    continue
                usage = sum(p.stat().st_size for p in (self.root / "pending").glob("*.json") if not p.is_symlink())
                if usage + len(encoded) > 256 * 1024 * 1024:
                    raise ObservationError("SPOOL_FULL")
                atomic_bytes(path, encoded)
                inserted += len(current)
        return inserted

    def purge(self) -> int:
        if self.dry_run:
            return 0
        count = 0
        deadline = time.monotonic() + 1
        # 各目錄每輪最多512 entries，iterator保留於Store，下一輪續掃。
        # root只識別本功能receipt，不掃／刪token或LaunchAgents配置。
        for section in ("sources", "pending", "acks", "receipts"):
            directory = self.root if section == "receipts" else self.root / section
            if time.monotonic() >= deadline:
                break
            with atomic_directory_lock(directory) as locked:
                if not locked:
                    continue
                iterator = self.purge_iterators.get(section)
                if iterator is None:
                    iterator = os.scandir(directory)
                    self.purge_iterators[section] = iterator
                for _ in range(512):
                    if time.monotonic() >= deadline:
                        break
                    try:
                        entry = next(iterator)
                    except StopIteration:
                        iterator.close()
                        self.purge_iterators.pop(section, None)
                        break
                    path = pathlib.Path(entry.path)
                    temp_match = re.fullmatch(r"\.(.+)\.([0-9a-f]{32})\.tmp", entry.name)
                    final_name = temp_match[1] if temp_match else entry.name
                    if not managed_state_name(section, final_name) or not entry.is_file(follow_symlinks=False):
                        continue
                    if temp_match:
                        # 同一目錄沒有writer持鎖，故為未成功rename的crash殘留。
                        # 不要求JSON完整／可解析，也不拿新mtime延長超期內容。
                        path.unlink(missing_ok=True)
                        count += 1
                        continue
                    try:
                        with open_readonly(path, self.root) as stream:
                            value = strict_json(stream.read(64 * 1024 * 1024))
                        day = value.get("reportDate") or value.get("run", {}).get("reportDate")
                        days = [day] if day is not None else []
                        if section == "receipts" and isinstance(value.get("runs"), list):
                            days.extend(run.get("reportDate") for run in value["runs"] if isinstance(run, dict) and run.get("reportDate"))
                        for recorded_day in days:
                            date_in_window(recorded_day, self.today)
                    except ObservationError as exc:
                        if str(exc) == "RETENTION_EXPIRED":
                            path.unlink(missing_ok=True)
                            count += 1
        return count


def managed_state_name(section: str, name: str) -> bool:
    uuid_pattern = r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    patterns = {
        "sources": r"(?:codex-[0-9a-f]{64}|claude-meta-[0-9a-f]{64}|codex-discovery|claude-discovery|emission-cursor)\.json",
        "pending": uuid_pattern + r"-[0-9]{10}-[0-9a-f]{64}\.json",
        "acks": uuid_pattern + r"\.json",
        "receipts": r"(?:collector|sender)-receipt\.json",
    }
    return re.fullmatch(patterns[section], name) is not None


@contextlib.contextmanager
def atomic_directory_lock(directory: pathlib.Path):
    directory = directory.absolute()
    current = pathlib.Path(directory.anchor)
    for part in directory.parts[1:]:
        current /= part
        if current.is_symlink():
            raise ObservationError("STATE_SYMLINK")
    path = directory / ".ai-flow-atomic.lock"
    fd = os.open(path, os.O_RDWR | os.O_CREAT | getattr(os, "O_NOFOLLOW", 0), 0o600)
    locked = False
    try:
        metadata = os.fstat(fd)
        if not stat.S_ISREG(metadata.st_mode) or metadata.st_uid != os.getuid() or metadata.st_size != 0 or stat.S_IMODE(metadata.st_mode) != 0o600:
            raise ObservationError("ATOMIC_LOCK_INVALID")
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            locked = True
        except BlockingIOError:
            pass
        yield locked
    finally:
        if locked:
            fcntl.flock(fd, fcntl.LOCK_UN)
        os.close(fd)


def secure_directory(path: pathlib.Path):
    path = path.absolute()
    current = pathlib.Path(path.anchor)
    for part in path.parts[1:]:
        current /= part
        if current.is_symlink():
            raise ObservationError("STATE_SYMLINK")
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    if not path.is_dir() or path.stat().st_uid != os.getuid():
        raise ObservationError("STATE_PERMISSIONS")
    path.chmod(0o700)


def atomic_bytes(path: pathlib.Path, encoded: bytes):
    if path.is_symlink() or not path.parent.is_dir():
        raise ObservationError("STATE_SYMLINK")
    with atomic_directory_lock(path.parent) as locked:
        if not locked:
            raise ObservationError("LOCAL_WRITER_BUSY")
        temporary = path.parent / ("." + path.name + "." + uuid.uuid4().hex + ".tmp")
        fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        try:
            with os.fdopen(fd, "wb") as stream:
                fd = -1
                stream.write(encoded)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, path)
            directory = os.open(path.parent, os.O_RDONLY)
            try:
                os.fsync(directory)
            finally:
                os.close(directory)
        finally:
            if fd >= 0:
                os.close(fd)
            temporary.unlink(missing_ok=True)


def atomic_json(path: pathlib.Path, value: dict):
    atomic_bytes(path, canonical(value))


def unsafe_public_text(text: str) -> bool:
    patterns = (
        r"https?://|[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}|authorization|bearer|api[_ -]?key|password|secret|\btoken\b",
        r"\$\(|```|<\s*script|持股|帳號|[0-9][,0-9]*(?:元|美元|股|張)|[\x00-\x1f]",
        r"(?:NT\s*\$|US\s*\$|\b(?:USD|TWD|NTD|EUR|JPY)\b|[$€£¥])\s*[-+]?\s*\d[\d,]*(?:\.\d+)?",
        r"\d[\d,]*(?:\.\d+)?\s*\b(?:USD|TWD|NTD|EUR|JPY)\b",
        r"(?:\b(?:price|amount|balance|holdings?|account|acct|position|quantity|qty|cost|shares?|lots?|portfolio|cash|market_value|total_value)\b|成本|金額|餘額|持倉|部位|股數|張數|單價|帳戶|帳號)\s*[\"']?\s*[:=：]",
        r"(?:\b(?:raw[_ -]*(?:command|shell|response|api|mime|reasoning)|command|cmd|shell)\b)\s*[\"']?\s*[:=：]",
        r"(?:<\s*/?\s*(?:analysis|think|thinking|reasoning)\s*>|(?:^|\n)\s*(?:analysis|thinking|reasoning|private[ _-]*(?:analysis|reasoning)|chain[ _-]*of[ _-]*thought|hidden[ _-]*reasoning)\s*[:=]|\b(?:BEGIN|END)[ _-]+(?:PRIVATE|CHAIN_OF_THOUGHT|REASONING)\b|(?:私人推理|內部思考|思考鏈)\s*[:=：])",
        r"^\s*(?:[$#]\s*)?(?:curl|wget|bash|zsh|sh|pwsh|powershell|python(?:3)?|node|cat|echo|printf)\s+\S",
        r"(?:/Users/|/private/|/tmp/|~/|[A-Z]:\\)",
    )
    return any(re.search(pattern, text, re.I) for pattern in patterns)


def validate_envelope(value: dict, today: dt.date):
    """CLI 輸入採閉合協定；禁止借 spool 搬運任意 JSON / 私人文字。"""
    def fail():
        raise ObservationError("INVALID_ENVELOPE")
    if not isinstance(value, dict) or set(value) != {"schemaVersion", "run", "events"} or type(value["schemaVersion"]) is not int or value["schemaVersion"] != 1:
        fail()
    run = value["run"]
    if not isinstance(run, dict) or set(run) != RUN_KEYS:
        fail()
    if not isinstance(run["consumer"], str) or not isinstance(run["reportSlot"], str) or run["consumer"] not in {"Codex", "Claude"} or run["reportSlot"] not in {"09:05", "12:00", "11:40", "22:00"}:
        fail()
    if not isinstance(run["sourceKind"], str) or run["sourceKind"] not in {"CODEX_ROLLOUT", "CLAUDE_ARTIFACT", "SCHEDULE_EVENTS"}:
        fail()
    if run["sourceKind"] == "CODEX_ROLLOUT" and run["consumer"] != "Codex" or run["sourceKind"] == "CLAUDE_ARTIFACT" and run["consumer"] != "Claude":
        fail()
    expected = CODEX_TASKS if run["consumer"] == "Codex" else CLAUDE_TASKS
    if not isinstance(run["automationId"], str) or run["automationId"] not in expected or not isinstance(run["sourceRunId"], str) or not OPAQUE.fullmatch(run["sourceRunId"]):
        fail()
    # 新US時段採雙向exact binding；既有TW consumer-ID集合不收緊。
    if (run["reportSlot"] == "22:00") != (run["consumer"] == "Codex" and run["automationId"] == "22-00"):
        fail()
    if not isinstance(run["workflowVersion"], str) or not 1 <= len(run["workflowVersion"]) <= 100:
        fail()
    date_in_window(run["reportDate"], today)
    for key in ("runId",):
        try:
            uuid.UUID(run[key])
        except (ValueError, TypeError, AttributeError):
            fail()
    if run["startedAt"] is not None and iso(run["startedAt"]) is None:
        fail()
    events = value["events"]
    if not isinstance(events, list) or not 1 <= len(events) <= 100000:
        fail()
    ids = set()
    for item in events:
        if not isinstance(item, dict) or set(item) not in (EVENT_KEYS, EVENT_KEYS | {"toolCall"}):
            fail()
        try:
            uuid.UUID(item["eventId"])
        except (ValueError, TypeError, AttributeError):
            fail()
        if item["eventId"] in ids:
            fail()
        ids.add(item["eventId"])
        if isinstance(item["sequence"], bool) or not isinstance(item["sequence"], int) or not 0 <= item["sequence"] <= 2147483647:
            fail()
        if not isinstance(item["eventKind"], str) or not isinstance(item["status"], str) or item["eventKind"] not in {"START", "END", "OBSERVATION", "TERMINAL"} or item["status"] not in {"RUNNING", "SUCCEEDED", "FAILED", "STOPPED", "SKIPPED", "UNKNOWN"}:
            fail()
        if "toolCall" in item and not validate_tool_call(item["toolCall"], item["eventKind"]):
            fail()
        if not isinstance(item["stepId"], str) or not 1 <= len(item["stepId"]) <= 100:
            fail()
        if isinstance(item["attemptNo"], bool) or not isinstance(item["attemptNo"], int) or not 1 <= item["attemptNo"] <= 2147483647:
            fail()
        if item["occurredAt"] is not None and iso(item["occurredAt"]) is None:
            fail()
        if item["reportedOutcome"] is not None and (not isinstance(item["reportedOutcome"], str) or item["reportedOutcome"] not in OUTCOMES):
            fail()
        if item["reasonCode"] is not None and (not isinstance(item["reasonCode"], str) or not re.fullmatch(r"[A-Z0-9_]{1,100}", item["reasonCode"])):
            fail()
        op = item["operation"]
        if not isinstance(op, dict) or set(op) != {"method", "path"}:
            fail()
        if not isinstance(op["method"], str) or not isinstance(op["path"], str):
            fail()
        if op["method"] in {"LOCAL", "TOOL"}:
            if op["path"] not in {"calendar", "policy", "validation", "render", "artifact", "tool"}:
                fail()
        elif op["method"] not in {"GET", "POST", "PUT", "PATCH", "DELETE"} or not canonical_http_path(op["path"]):
            fail()
        if not isinstance(item["parentEventIds"], list) or len(item["parentEventIds"]) > 16:
            fail()
        for parent in item["parentEventIds"]:
            try:
                uuid.UUID(parent)
            except (ValueError, TypeError, AttributeError):
                fail()
        if item["relation"] is not None and (not isinstance(item["relation"], str) or item["relation"] not in {"CALL_RESULT", "DEPENDS_ON", "BRANCH", "NEXT_OBSERVED"}):
            fail()
        refs = item["evidenceRefs"]
        if not isinstance(refs, list) or len(refs) > 10:
            fail()
        for ref in refs:
            if not isinstance(ref, dict) or set(ref) != {"role", "id", "hash"} or not isinstance(ref["role"], str) or ref["role"] not in ROLES or not isinstance(ref["id"], str) or not OPAQUE.fullmatch(ref["id"]):
                fail()
            if ref["hash"] is not None and (not isinstance(ref["hash"], str) or not re.fullmatch(r"[0-9a-f]{64}", ref["hash"])):
                fail()
        for field, limit in (("title", 100), ("summary", 500)):
            text = item[field]
            if not isinstance(text, str) or not text or len(text) > limit:
                fail()
            # 主動 CLI 也不能把私人／機密資訊帶入觀測協定。
            if unsafe_public_text(text):
                fail()


class Collector:
    def __init__(self, home: pathlib.Path, store: Store):
        self.home, self.store = home.absolute(), store
        self.verifiers: dict[str, dict] = {}
        self.prefixes: dict[str, dict] = {}
        self.errors: dict[str, int] = {}

    def note_error(self, code: str):
        self.errors[code] = self.errors.get(code, 0) + 1

    def codex_runs(self, day: dt.date | None, max_runs=25) -> list[dict]:
        root = self.home / ".codex"
        lo = day or self.store.today - dt.timedelta(days=89)
        hi = (day or self.store.today) + dt.timedelta(days=1)
        start = int(dt.datetime.combine(lo, dt.time(), TAIPEI).timestamp() * 1000)
        end = int(dt.datetime.combine(hi, dt.time(), TAIPEI).timestamp() * 1000)
        try:
            # 明確日期只讀既有date-scoped排序；背景從自己cache最後實掃key續頁。
            discovery = self.store.load("codex-discovery") if day is None else {}
            after_created, after_thread = discovery.get("createdAt"), discovery.get("threadId")
            has_cursor = type(after_created) is int and start <= after_created < end and isinstance(after_thread, str) and OPAQUE.fullmatch(after_thread)
            placeholders = ",".join("?" for _ in CODEX_TASKS)
            select = f"SELECT thread_id, automation_id, created_at FROM automation_runs WHERE automation_id IN ({placeholders}) AND created_at >= ? AND created_at < ?"
            suffix = " ORDER BY created_at DESC, thread_id LIMIT ?"
            with readonly_db(root / "sqlite/codex-dev.db", root, "automation_runs", {"thread_id", "automation_id", "created_at"}) as app:
                parameters = (*CODEX_TASKS, start, end)
                if has_cursor:
                    records = app.execute(select + " AND (created_at < ? OR (created_at = ? AND thread_id > ?))" + suffix,
                                          (*parameters, after_created, after_created, after_thread, max_runs)).fetchall()
                    if not records:
                        # 一輪最多一頁，不一次載入90日歷史；走到底後於下一頁wrap。
                        records = app.execute(select + suffix, (*parameters, max_runs)).fetchall()
                else:
                    records = app.execute(select + suffix, (*parameters, max_runs)).fetchall()
            with readonly_db(root / "state_5.sqlite", root, "threads", {"id", "rollout_path"}) as threads:
                result = []
                for thread, automation, created in records:
                    if not isinstance(thread, str) or not OPAQUE.fullmatch(thread):
                        self.note_error("INVALID_SOURCE_ID")
                        continue
                    match = threads.execute("SELECT rollout_path FROM threads WHERE id=?", (thread,)).fetchone()
                    actual_day = epoch_ms(created).date().isoformat()
                    record = {"thread": thread, "automation": automation, "day": actual_day,
                              "slot": CODEX_TASKS[automation], "path": match[0] if match else None}
                    if day is None:
                        record["discoveryCreatedAt"] = created
                    result.append(record)
                return result
        except (sqlite3.Error, OSError, ObservationError) as exc:
            self.note_error(str(exc) if isinstance(exc, ObservationError) else "CODEX_SOURCE_UNAVAILABLE")
            return []

    def advance_codex_discovery(self, record: dict):
        if not self.store.dry_run and "discoveryCreatedAt" in record:
            self.store.save("codex-discovery", {"reportDate": self.store.today.isoformat(),
                                               "createdAt": record["discoveryCreatedAt"], "threadId": record["thread"]})

    def scan_codex(self, record: dict, budget: ReadBudget) -> list[dict]:
        name = "codex-" + hashlib.sha256(record["thread"].encode()).hexdigest()
        state = self.store.load(name)
        state.setdefault("reportDate", record["day"])
        state.setdefault("cursor", 0)
        state.setdefault("line", 0)
        state.setdefault("prefixHash", hashlib.sha256(b"").hexdigest())
        state.setdefault("turns", {})
        path = pathlib.Path(record["path"]) if record["path"] else None
        root = self.home / ".codex/sessions"
        if path is None:
            return self.unavailable_codex(record, state, "RAW_TRACE_UNAVAILABLE")
        try:
            with open_readonly(path, root) as stream:
                source_stat = os.fstat(stream.fileno())
                size = source_stat.st_size
                signature = [source_stat.st_dev, source_stat.st_ino, size, source_stat.st_mtime_ns, source_stat.st_ctime_ns]
                if size > 2147483647:
                    raise ObservationError("SOURCE_TOO_LARGE")
                per_run = min(budget.per_run, budget.remaining)
                key = str(path)
                # 此stat僅是已掃bytes的完整性cache，不是run身份／日期／app聯結。
                # ctime/inode也必須一致；同mtime的前綴改寫仍重新hash並永久partial。
                if state.get("sourceSignature") == signature and state["cursor"] == size and not state.get("sourceChanged") and state["turns"]:
                    return self.envelopes(state, size)
                target = min(state["cursor"], size)
                verifier = self.verifiers.get(key)
                if verifier is None or verifier["target"] != target or verifier["expected"] != state["prefixHash"]:
                    prefix = self.prefixes.get(key)
                    reusable = (prefix is not None and prefix["signature"] == signature and prefix["cursor"] == target)
                    verifier = {"target": target, "expected": state["prefixHash"], "offset": target if reusable else 0,
                                "sha": prefix["sha"].copy() if reusable else hashlib.sha256()}
                    self.verifiers[key] = verifier
                stream.seek(verifier["offset"])
                while verifier["offset"] < target and budget.available() and per_run > 0:
                    amount = min(1024 * 1024, target - verifier["offset"], per_run, budget.remaining)
                    chunk = stream.read(amount)
                    if not chunk:
                        break
                    verifier["sha"].update(chunk)
                    verifier["offset"] += len(chunk)
                    per_run -= len(chunk)
                    budget.charge(len(chunk))
                if verifier["offset"] < target:
                    if size > state["cursor"]:
                        result = self.envelopes(state, size, "TRACE_PARTIAL")
                        self.store.save(name, state)
                        return result
                    # 尚未完成重驗既有prefix；不能用同範圍相反assessment覆寫旧FULL。
                    return [{"schemaVersion": 1, "run": turn["run"],
                             "events": turn["events"] + list(turn.get("assessments", {}).values())}
                            for turn in state["turns"].values()]
                digest = verifier["sha"].hexdigest()
                changed = size < state["cursor"] or digest != state["prefixHash"]
                if changed or state.get("sourceChanged"):
                    state["sourceChanged"] = True
                    assessment_cursor = target
                    envelopes = []
                    for turn in state["turns"].values():
                        assessment = coverage(turn["run"]["sourceRunId"], assessment_cursor, size, digest, "SOURCE_TRACE_CHANGED")
                        turn.setdefault("assessments", {})[assessment["eventId"]] = assessment
                        envelopes.append({"schemaVersion": 1, "run": turn["run"], "events": turn["events"] + list(turn["assessments"].values())})
                    self.store.save(name, state)
                    return envelopes
                sha = verifier["sha"].copy()
                stream.seek(state["cursor"])
                while stream.tell() < size and budget.available() and per_run > 0:
                    # 讀到完整 newline 才推進 cursor；尾端半行不屬已掃 prefix。
                    before = stream.tell()
                    raw = stream.readline(min(per_run, budget.remaining, size - before))
                    budget.charge(len(raw))
                    per_run -= len(raw)
                    if not raw.endswith(b"\n"):
                        stream.seek(before)
                        break
                    sha.update(raw)
                    state["cursor"] += len(raw)
                    state["line"] += 1
                    try:
                        row = strict_json(raw)
                        if isinstance(row, dict):
                            parse_source_line(row, state["line"], state, record["thread"], record["automation"], record["day"], record["slot"])
                        else:
                            state["malformed"] = True
                    except (ObservationError, TypeError, ValueError, AttributeError):
                        state["malformed"] = True
                state["prefixHash"] = sha.hexdigest()
                state["sourceSignature"] = signature
                self.prefixes[key] = {"cursor": state["cursor"], "signature": signature, "sha": sha.copy()}
                if state["cursor"] != target or (per_run > 0 and budget.available()):
                    self.verifiers.pop(key, None)
                if state.get("identityMismatch"):
                    state["turns"] = {}
                    self.note_error("CODEX_IDENTITY_MISMATCH")
                    return self.unavailable_codex(record, state, "UNLINKED")
                self.store.save(name, state)
                if not state["turns"]:
                    return self.unavailable_codex(record, state, "RAW_TRACE_UNAVAILABLE")
                envelopes = self.envelopes(state, size)
                self.store.save(name, state)
                return envelopes
        except (OSError, ObservationError) as exc:
            self.note_error(str(exc) if isinstance(exc, ObservationError) else "CODEX_SOURCE_UNAVAILABLE")
            return self.unavailable_codex(record, state, "RAW_TRACE_UNAVAILABLE")

    def envelopes(self, state: dict, size: int, forced=None) -> list[dict]:
        result = []
        for turn in state["turns"].values():
            complete = turn["complete"] and turn["final"] and all(call["paired"] for call in turn["calls"].values())
            complete = complete and not turn["missingChild"] and not state.get("malformed") and state["cursor"] == size
            reason = forced or ("TRACE_COMPLETE" if complete else "TRACE_PARTIAL")
            assessment = coverage(turn["run"]["sourceRunId"], state["cursor"], size, state["prefixHash"], reason)
            turn.setdefault("assessments", {})[assessment["eventId"]] = assessment
            result.append({"schemaVersion": 1, "run": turn["run"], "events": sorted(turn["events"] + list(turn["assessments"].values()), key=lambda e: (e["sequence"], e["eventId"]))})
        return result

    def unavailable_codex(self, record: dict, state: dict, reason: str) -> list[dict]:
        if state["turns"]:
            return self.envelopes(state, max(state["cursor"], state["cursor"]), reason)
        # 已有 exact automation/thread link，但缺真 turn。獨立 unavailable 身份，不能猜 turn。
        identity = record["thread"] + ":raw-unavailable"
        run = run_metadata("Codex", record["day"], record["slot"], "CODEX_ROLLOUT", identity, record["automation"])
        return [{"schemaVersion": 1, "run": run, "events": [coverage(identity, 0, 0, hashlib.sha256(b"").hexdigest(), reason)]}]

    def claude_metadata(self, day: dt.date | None, budget: ReadBudget) -> list[dict]:
        root = self.home / "Library/Application Support/Claude/local-agent-mode-sessions"
        result = []
        if not root.is_dir() or root.is_symlink():
            self.note_error("CLAUDE_SOURCE_UNAVAILABLE")
            return result
        seen = 0
        paths = sorted(root.glob("*/*/local_*.json"))
        discovery = self.store.load("claude-discovery")
        start_index = discovery.get("index", 0) % len(paths) if paths else 0
        walked = 0
        for index in range(len(paths)):
            path = paths[(start_index + index) % len(paths)]
            if not budget.available() or seen >= budget.runs:
                break
            walked += 1
            try:
                resolved = safe_source(path, root)
                info = resolved.stat()
                signature = [info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns]
                cache_name = "claude-meta-" + hashlib.sha256(str(path.relative_to(root)).encode()).hexdigest()
                cached = self.store.load(cache_name)
                if cached.get("signature") == signature:
                    envelope = cached.get("envelope")
                    code = cached.get("code")
                else:
                    with open_readonly(path, root) as stream:
                        raw = stream.read(min(4 * 1024 * 1024, budget.remaining))
                    budget.charge(len(raw))
                    value = strict_json(raw)
                    envelope, code = self.claude_metadata_envelope(value)
                    cache_date = envelope["run"]["reportDate"] if envelope else self.store.today.isoformat()
                    self.store.save(cache_name, {"reportDate": cache_date, "signature": signature, "envelope": envelope, "code": code})
                if code:
                    self.note_error(code)
                if not envelope or day is not None and envelope["run"]["reportDate"] != day.isoformat():
                    continue
                date_in_window(envelope["run"]["reportDate"], self.store.today)
                result.append(envelope)
                seen += 1
            except (OSError, ObservationError, AttributeError, TypeError, ValueError):
                continue
        self.store.save("claude-discovery", {"reportDate": self.store.today.isoformat(), "index": (start_index + walked) % len(paths) if paths else 0})
        return result

    def claude_metadata_envelope(self, value: dict) -> tuple[dict | None, str | None]:
        task, session = value.get("scheduledTaskId"), value.get("cliSessionId")
        if task not in CLAUDE_TASKS or not isinstance(session, str) or not OPAQUE.fullmatch(session):
            return None, None
        report_day = epoch_ms(value.get("createdAt")).date()
        date_in_window(report_day.isoformat(), self.store.today)
        # Metadata 未含歷史午盤時刻時不把11:40默改成12:00。
        slot = CLAUDE_TASKS[task] or normalize_slot(value.get("reportSlot") or value.get("slot"))
        if slot is None:
            return None, "CLAUDE_METADATA_SLOT_UNAVAILABLE"
        identity = "claude-session:" + session
        run = run_metadata("Claude", report_day.isoformat(), slot, "CLAUDE_ARTIFACT", identity, task)
        marker = event(identity, "session-identity", 0, "session-identity", "OBSERVATION", None,
                       "本機排程執行身份", "已觀測到排程身份資料；詳細公開流程目前不可讀。",
                       reason="RAW_TRACE_UNAVAILABLE")
        return {"schemaVersion": 1, "run": run, "events": [marker, coverage(identity, 0, 0, hashlib.sha256(b"").hexdigest(), "RAW_TRACE_UNAVAILABLE")]}, None

    def claude_artifacts(self, day: dt.date | None, budget: ReadBudget) -> list[dict]:
        root = self.home / "Project/SRPP/data/output/daily_runs"
        if not root.is_dir() or root.is_symlink():
            return []
        result = []
        days = [day.isoformat()] if day else sorted((p.name for p in root.iterdir() if p.is_dir() and re.fullmatch(r"\d{4}-\d{2}-\d{2}", p.name)), reverse=True)
        for directory_day in days:
            try:
                date_in_window(directory_day, self.store.today)
            except ObservationError:
                continue
            for path in sorted((root / directory_day).glob("*/result.json")):
                if len(result) >= budget.runs or not budget.available():
                    return result
                try:
                    with open_readonly(path, root) as stream:
                        raw = stream.read(min(4 * 1024 * 1024, budget.remaining))
                    budget.charge(len(raw))
                    value = strict_json(raw)
                    if not isinstance(value, dict) or value.get("consumer") != "Claude":
                        continue
                    report_day = value.get("trading_date") or value.get("date") or value.get("reportDate")
                    if report_day != directory_day:
                        self.note_error("CLAUDE_ARTIFACT_DATE_MISMATCH")
                        continue
                    slot = normalize_slot(value.get("slot") or value.get("reportSlot"))
                    if slot is None:
                        self.note_error("CLAUDE_ARTIFACT_SLOT_UNAVAILABLE")
                        continue
                    date_in_window(report_day, self.store.today)
                    task = "srpp-0905-claude" if slot == "09:05" else "srpp-1150-claude"
                    explicit_task = value.get("automation_id") or value.get("automationId") or value.get("scheduledTaskId")
                    if explicit_task is not None and explicit_task != task:
                        self.note_error("CLAUDE_ARTIFACT_IDENTITY_MISMATCH")
                        continue
                    digest = hashlib.sha256(raw).hexdigest()
                    # 一份 artifact 本身可識別，但沒有 exact app link 時不能拼接 metadata。
                    explicit_session = value.get("cliSessionId") or value.get("cli_session_id")
                    exact_link = explicit_task == task and isinstance(explicit_session, str) and OPAQUE.fullmatch(explicit_session)
                    identity = "claude-artifact:" + digest
                    started = iso(value.get("started_at") or value.get("start"))
                    run = run_metadata("Claude", report_day, slot, "CLAUDE_ARTIFACT", identity, task, started)
                    outcome = value.get("status") if isinstance(value.get("status"), str) and value.get("status") in OUTCOMES else None
                    item = event(identity, "result", 0, "artifact-result", "OBSERVATION",
                                 value.get("finished_at") or value.get("end"), "報告結果自報", "來源文件提供自報狀態，尚未獨立驗證。",
                                 outcome=outcome, refs=[{"role": "RESULT", "id": "result-" + digest[:32], "hash": digest}])
                    reason = "RAW_TRACE_UNAVAILABLE" if exact_link else "UNLINKED"
                    events = [item]
                    timing_path = path.with_name("timing.json")
                    if timing_path.is_file():
                        with open_readonly(timing_path, root) as stream:
                            timing_raw = stream.read(min(1024 * 1024, budget.remaining))
                        budget.charge(len(timing_raw))
                        timing = strict_json(timing_raw)
                        # timing僅有同一explicit run_id與日期才接入，不用相鄰檔案或mtime。
                        result_id = value.get("run_id")
                        if isinstance(timing, dict) and result_id is not None and timing.get("run_id") == result_id and (timing.get("trading_date") or timing.get("date")) == report_day:
                            thash = hashlib.sha256(timing_raw).hexdigest()
                            events.append(event(identity, "timing:" + thash, 1, "artifact-timing", "OBSERVATION", None,
                                                "同一執行的自報時間證據", "明確身份相同的時間文件；不把自報時間當作公開工具耗時。",
                                                refs=[{"role": "TIMING", "id": "timing-" + thash[:32], "hash": thash}]))
                    events.append(coverage(identity, len(raw), len(raw), digest, reason))
                    result.append({"schemaVersion": 1, "run": run, "events": events})
                except (OSError, ObservationError, TypeError, ValueError):
                    self.note_error("CLAUDE_ARTIFACT_UNAVAILABLE")
        return result

    def collect(self, *, day=None, consumer=None, budget=None, replay=False) -> dict:
        budget = budget or ReadBudget()
        start_cpu = time.process_time()
        purged = self.store.purge()
        envelopes = []
        if consumer in (None, "Codex"):
            for record in self.codex_runs(day, budget.runs):
                if not budget.available():
                    break
                envelopes.extend(self.scan_codex(record, budget))
                self.advance_codex_discovery(record)
        if consumer in (None, "Claude") and budget.available():
            envelopes.extend(self.claude_artifacts(day, budget))
            if budget.available():
                envelopes.extend(self.claude_metadata(day, budget))
        if len(envelopes) > budget.runs:
            # 多turn／雙軌多來源也遵守run上限；輪替排出，不永遠卡在固定前25筆。
            cursor = self.store.load("emission-cursor").get("index", 0) % len(envelopes)
            envelopes = (envelopes[cursor:] + envelopes[:cursor])[:budget.runs]
            self.store.save("emission-cursor", {"reportDate": self.store.today.isoformat(), "index": cursor + budget.runs})
        queued = 0
        metadata = []
        for envelope in envelopes:
            try:
                queued += self.store.enqueue(envelope, replay=replay)
                reasons = [e["reasonCode"] for e in envelope["events"] if e["stepId"] == "trace-coverage"]
                metadata.append({"runId": envelope["run"]["runId"], "consumer": envelope["run"]["consumer"],
                                 "reportDate": envelope["run"]["reportDate"], "reportSlot": envelope["run"]["reportSlot"],
                                 "sourceKind": envelope["run"]["sourceKind"], "eventCount": len(envelope["events"]), "coverageReasons": sorted(set(reasons))})
            except (OSError, ObservationError) as exc:
                self.note_error(str(exc) if isinstance(exc, ObservationError) else "SPOOL_UNAVAILABLE")
        return {"schemaVersion": 1, "mode": "DRY_RUN" if self.store.dry_run else "LOCAL_ENQUEUE",
                "runs": metadata, "queuedEvents": queued, "purgedFiles": purged, "readBytes": budget.consumed,
                "wallMillis": round((time.monotonic() - budget.start) * 1000, 3),
                "cpuMillis": round((time.process_time() - start_cpu) * 1000, 3), "errors": self.errors}


def normalize_slot(value: Any) -> str | None:
    return {"0905": "09:05", "09:05": "09:05", "1140": "11:40", "11:40": "11:40", "1200": "12:00", "12:00": "12:00"}.get(value) if isinstance(value, str) else None


def default_state() -> pathlib.Path:
    return pathlib.Path.home() / ".local/share/srpp-ai-flow"


def cli(argv=None) -> int:
    parser = argparse.ArgumentParser(description="唯讀收集 AI 公開執行流程；只寫本機 spool，不呼叫金融 API 或上傳。")
    parser.add_argument("--once", action="store_true", help="執行一輪有界收集後退出（預設）")
    parser.add_argument("--daemon", action="store_true", help="獨立背景 process，每60秒收集一次並啟動獨立sender")
    parser.add_argument("--date", help="台北 ISO 日期，最多90天內")
    parser.add_argument("--consumer", choices=["Codex", "Claude"])
    parser.add_argument("--dry-run", action="store_true", help="不寫 state 或 spool，只輸出清洗後metadata")
    parser.add_argument("--endpoint", default=DEFAULT_ENDPOINT, help="僅供獨立背景sender；enqueue不使用")
    parser.add_argument("--token-file", type=pathlib.Path, default=default_state() / "collector.token", help="僅供獨立sender讀取")
    parser.add_argument("--state-dir", type=pathlib.Path, default=default_state())
    parser.add_argument("--replay", action="store_true", help="忽略本功能ack cache，重送90天內原observations的相同IDs")
    parser.add_argument("--enqueue-json", help="只enqueue閉合JSON batch；檔案或 '-' stdin，不HTTP")
    parser.add_argument("--max-runs", type=int, default=25)
    parser.add_argument("--max-read-mib", type=int, default=64)
    parser.add_argument("--per-run-mib", type=int, default=32)
    parser.add_argument("--read-seconds", type=float, default=5)
    args = parser.parse_args(argv)
    try:
        today = dt.datetime.now(TAIPEI).date()
        day = date_in_window(args.date, today) if args.date else None
        if not 1 <= args.max_runs <= 250 or not 1 <= args.max_read_mib <= 512 or not 1 <= args.per_run_mib <= 256 or not 0 < args.read_seconds <= 60:
            raise ObservationError("INVALID_BUDGET")
        if args.daemon and (args.once or args.date or args.dry_run or args.enqueue_json or args.replay or args.max_runs != 25 or args.max_read_mib != 64 or args.per_run_mib != 32 or args.read_seconds != 5):
            raise ObservationError("INVALID_BACKGROUND_OPTIONS")
        if args.enqueue_json:
            if args.enqueue_json == "-":
                raw = sys.stdin.buffer.read(256 * 1024 + 1)
            else:
                with pathlib.Path(args.enqueue_json).open("rb") as stream:
                    raw = stream.read(256 * 1024 + 1)
            if len(raw) > 256 * 1024:
                raise ObservationError("BODY_TOO_LARGE")
            value = strict_json(raw)
            # 主動CLI先完成privacy／閉合驗證，連自己的state目錄都尚未建立。
            validate_envelope(value, today)
            store = Store(args.state_dir, today, dry_run=args.dry_run)
            count = store.enqueue(value, replay=args.replay)
            print(json.dumps({"mode": "LOCAL_ENQUEUE", "queuedEvents": count}, ensure_ascii=False))
            return 0
        store = Store(args.state_dir, today, dry_run=args.dry_run)
        collector = Collector(pathlib.Path.home(), store)
        sender = None
        while True:
            store.today = dt.datetime.now(TAIPEI).date()
            budget = ReadBudget(args.max_read_mib * 1024 * 1024, args.per_run_mib * 1024 * 1024, args.read_seconds, args.max_runs)
            receipt = collector.collect(day=day, consumer=args.consumer, budget=budget, replay=args.replay)
            if not args.dry_run:
                atomic_json(store.root / "collector-receipt.json", receipt)
            public_receipt = receipt if not args.daemon else {key: receipt[key] for key in ("mode", "queuedEvents", "purgedFiles", "readBytes", "wallMillis", "cpuMillis", "errors")}
            print(json.dumps(public_receipt, ensure_ascii=False), flush=True)
            if not args.daemon:
                break
            if sender is None or sender.poll() is not None:
                sender_path = pathlib.Path(__file__).with_name("send-srpp-ai-flow.py")
                sender = subprocess.Popen([sys.executable, str(sender_path), "--once", "--state-dir", str(store.root),
                                           "--endpoint", args.endpoint, "--token-file", str(args.token_file)], close_fds=True)
            collector.errors = {}
            for log_name in ("collector.log", "collector-error.log"):
                log_path = store.root / log_name
                if log_path.is_file() and not log_path.is_symlink() and log_path.stat().st_size > 1024 * 1024:
                    # 技術日誌不含run內容／identity；仍限制大小，無長期流程archive。
                    with log_path.open("wb"):
                        pass
            time.sleep(60)
        return 0
    except (OSError, ObservationError) as exc:
        code = str(exc) if isinstance(exc, ObservationError) else "LOCAL_IO_UNAVAILABLE"
        print(json.dumps({"mode": "OBSERVATION_UNAVAILABLE", "code": code}, ensure_ascii=False))
        return 1


if __name__ == "__main__":
    raise SystemExit(cli())
