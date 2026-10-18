"""Task492隔離測試；不接production、不讀真金融內容、不安裝OS工作。"""
import contextlib
import datetime as dt
import importlib.util
import io
import hashlib
import json
import os
import pathlib
import plistlib
import re
import select
import signal
import sqlite3
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest import mock


SCRIPTS = pathlib.Path(__file__).resolve().parents[1]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


c = load("collect_ai_flow_tests", SCRIPTS / "collect-srpp-ai-flow.py")
s = load("send_ai_flow_tests", SCRIPTS / "send-srpp-ai-flow.py")
i = load("install_ai_flow_tests", SCRIPTS / "install-ai-flow-collector.py")
TODAY = dt.date(2026, 10, 11)
THREAD = "019a1111-2222-7333-8444-555555555555"
TURN = "019a2222-2222-7333-8444-555555555555"
TURN2 = "019a3333-2222-7333-8444-555555555555"


def row(kind, payload, timestamp="2026-10-08T01:05:00Z"):
    return {"timestamp": timestamp, "type": kind, "payload": payload}


def source_rows(turn=TURN, *, complete=True, child=False, error=False):
    values = [row("session_meta", {"id": THREAD}),
              row("event_msg", {"type": "task_started", "turn_id": turn, "started_at": 1791421500000}),
              row("response_item", {"type": "reasoning", "summary": "PRIVATE_REASONING_CANARY", "encrypted_content": "TOKEN_CANARY"}),
              row("response_item", {"type": "message", "role": "assistant", "phase": "analysis", "content": [{"text": "PRIVATE_ANALYSIS_CANARY"}]}),
              row("response_item", {"type": "custom_tool_call", "call_id": "call_public1", "name": "spawn_agent" if child else "functions.exec", "input": "Bearer SECRET_CANARY; holdings=FINANCIAL_CANARY"}),
              row("response_item", {"type": "custom_tool_call_output", "call_id": "call_public1", "output": json.dumps({"exit_code": 1 if error else 0, "secret": "SECRET_OUTPUT_CANARY"})}, "2026-10-08T01:05:02Z"),
              row("response_item", {"type": "message", "role": "assistant", "phase": "commentary", "content": [{"text": "PUBLIC_FINANCIAL_CANARY"}]})]
    if complete:
        values += [row("response_item", {"type": "message", "role": "assistant", "phase": "final_answer", "content": [{"text": "FINAL_FINANCIAL_CANARY"}]}, "2026-10-08T01:05:03Z"),
                   row("event_msg", {"type": "task_complete", "turn_id": turn, "completed_at": 1791421504000}, "2026-10-08T01:05:04Z")]
    return values


def encode_rows(values):
    return b"".join(c.canonical(value) + b"\n" for value in values)


class Fixture(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.home = pathlib.Path(self.temp.name).resolve()
        self.sessions = self.home / ".codex/sessions"
        self.sessions.mkdir(parents=True)
        self.path = self.sessions / "trace.jsonl"
        self.store = c.Store(self.home / "state", TODAY)
        self.collector = c.Collector(self.home, self.store)
        self.record = {"thread": THREAD, "automation": "codex-srpp-09-30", "day": "2026-10-08", "slot": "09:05", "path": str(self.path)}
        self.path.write_bytes(encode_rows(source_rows()))

    def tearDown(self):
        self.store.close()
        self.temp.cleanup()

    def scan(self, **kw):
        return self.collector.scan_codex(self.record, c.ReadBudget(**kw))

    def envelope(self):
        return self.scan()[0]

    def token(self):
        path = self.store.root / "collector.token"
        path.write_text("K" * 48)
        path.chmod(0o600)
        return path

    def ack(self, _, __, body):
        value = c.strict_json(body)
        return 201, c.canonical({"runId": value["run"]["runId"], "inserted": len(value["events"]), "replayed": 0,
                                 "eventIds": [e["eventId"] for e in value["events"]]})


class SourceTests(Fixture):
    def test_true_phase_final_without_channel_full(self):
        value = self.envelope()
        self.assertEqual("TRACE_COMPLETE", value["events"][-1]["reasonCode"])
        self.assertTrue(any(e["stepId"] == "final-answer" for e in value["events"]))
        self.assertIsNone(next(e for e in value["events"] if e["stepId"] == "final-answer")["reportedOutcome"])

    def test_private_reasoning_commands_financial_outputs_never_persist(self):
        self.envelope()
        text = "\n".join(p.read_text() for p in (self.store.root / "sources").glob("*.json"))
        self.assertNotIn("CANARY", text)
        self.assertNotIn("Bearer", text)
        self.assertNotIn("encrypted_content", text)

    def test_call_result_pairing(self):
        events = self.envelope()["events"]
        start = next(e for e in events if e["stepId"].startswith("call-") and e["eventKind"] == "START")
        end = next(e for e in events if e["eventKind"] == "END")
        self.assertEqual(start["stepId"], end["stepId"])
        self.assertEqual([start["eventId"]], end["parentEventIds"])
        self.assertEqual("CALL_RESULT", end["relation"])
        self.assertEqual("SUCCEEDED", end["status"])

    def test_error_result_does_not_become_success(self):
        self.path.write_bytes(encode_rows(source_rows(error=True)))
        self.assertEqual("FAILED", next(e for e in self.envelope()["events"] if e["eventKind"] == "END")["status"])

    def test_plain_output_is_unknown(self):
        values = source_rows()
        values[5]["payload"]["output"] = "HTTP 409 with raw financial data"
        self.path.write_bytes(encode_rows(values))
        self.assertEqual("UNKNOWN", next(e for e in self.envelope()["events"] if e["eventKind"] == "END")["status"])

    def test_missing_output_partial(self):
        values = source_rows()
        del values[5]
        self.path.write_bytes(encode_rows(values))
        self.assertEqual("TRACE_PARTIAL", self.envelope()["events"][-1]["reasonCode"])

    def test_orphan_output_partial(self):
        values = source_rows()
        values[5]["payload"]["call_id"] = "other_call"
        self.path.write_bytes(encode_rows(values))
        self.assertEqual("TRACE_PARTIAL", self.envelope()["events"][-1]["reasonCode"])

    def test_running_then_append_terminal_recovers_complete(self):
        values = source_rows(complete=False)
        self.path.write_bytes(encode_rows(values))
        before = self.envelope()
        self.assertEqual("TRACE_PARTIAL", before["events"][-1]["reasonCode"])
        with self.path.open("ab") as stream:
            stream.write(encode_rows(source_rows()[-2:]))
        after = self.envelope()
        self.assertEqual(before["run"], after["run"])
        reasons = {e["reasonCode"] for e in after["events"] if e["stepId"] == "trace-coverage"}
        self.assertEqual({"TRACE_PARTIAL", "TRACE_COMPLETE"}, reasons)

    def test_half_line_then_append_is_not_source_changed(self):
        raw = encode_rows(source_rows())
        split = len(raw) - 15
        self.path.write_bytes(raw[:split])
        before = self.envelope()
        coverage_before = next(e for e in before["events"] if e["stepId"] == "trace-coverage")
        cursor = int(coverage_before["evidenceRefs"][0]["id"].split(":")[1])
        self.assertLess(cursor, split)
        with self.path.open("ab") as stream:
            stream.write(raw[split:])
        after = self.envelope()
        reasons = {e["reasonCode"] for e in after["events"] if e["stepId"] == "trace-coverage"}
        self.assertEqual({"TRACE_PARTIAL", "TRACE_COMPLETE"}, reasons)

    def test_truncation_appends_changed_and_keeps_original_events(self):
        before = self.envelope()
        original_ids = {e["eventId"] for e in before["events"]}
        self.path.write_bytes(encode_rows(source_rows()[:2]))
        after = self.envelope()
        self.assertTrue(original_ids <= {e["eventId"] for e in after["events"]})
        self.assertIn("SOURCE_TRACE_CHANGED", [e["reasonCode"] for e in after["events"]])
        self.path.write_bytes(encode_rows(source_rows()))
        later = self.envelope()
        self.assertIn("SOURCE_TRACE_CHANGED", [e["reasonCode"] for e in later["events"]])

    def test_same_size_prefix_rewrite_forces_permanent_partial(self):
        self.envelope()
        raw = self.path.read_bytes()
        self.path.write_bytes(raw.replace(b"PUBLIC_FINANCIAL_CANARY", b"ALTERD_FINANCIAL_CANARY"))
        self.assertEqual(len(raw), self.path.stat().st_size)
        self.assertIn("SOURCE_TRACE_CHANGED", [e["reasonCode"] for e in self.envelope()["events"]])

    def test_assessment_stable_replay(self):
        first, second = self.envelope(), self.envelope()
        self.assertEqual(c.canonical(first), c.canonical(second))

    def test_missing_child_remains_partial(self):
        self.path.write_bytes(encode_rows(source_rows(child=True)))
        self.assertEqual("TRACE_PARTIAL", self.envelope()["events"][-1]["reasonCode"])

    def test_multi_turn_same_thread_distinct_and_followup(self):
        values = source_rows() + source_rows(TURN2)
        self.path.write_bytes(encode_rows(values))
        runs = self.scan()
        self.assertEqual(2, len(runs))
        self.assertNotEqual(runs[0]["run"]["runId"], runs[1]["run"]["runId"])
        self.assertEqual("2026-10-08", runs[1]["run"]["reportDate"])
        self.assertIn("後續嘗試", runs[1]["events"][0]["title"])
        self.assertEqual(2, next(e for e in runs[1]["events"] if e["eventKind"] == "END")["attemptNo"])

    def test_call_id_cannot_pair_across_turn(self):
        values = source_rows(complete=False)[:5] + source_rows(TURN2)[:2] + source_rows()[-2:]
        values.append(row("response_item", {"type": "custom_tool_call_output", "call_id": "call_public1", "output": "{}"}))
        self.path.write_bytes(encode_rows(values))
        self.assertTrue(all(any(e["reasonCode"] == "TRACE_PARTIAL" for e in run["events"]) for run in self.scan()))

    def test_missing_start_does_not_guess_turn(self):
        values = source_rows()
        del values[1]
        self.path.write_bytes(encode_rows(values))
        run = self.envelope()
        self.assertTrue(run["run"]["sourceRunId"].endswith("raw-unavailable"))
        self.assertEqual("RAW_TRACE_UNAVAILABLE", run["events"][0]["reasonCode"])

    def test_wrong_session_identity_unlinked(self):
        values = source_rows()
        values[0]["payload"]["id"] = "unrelated-thread"
        self.path.write_bytes(encode_rows(values))
        self.assertEqual("UNLINKED", self.envelope()["events"][0]["reasonCode"])

    def test_cross_day_terminal_keeps_automation_date(self):
        values = source_rows()
        values[-1]["payload"]["completed_at"] = 1791480600000
        values[-1]["timestamp"] = "2026-10-08T17:30:00Z"
        self.path.write_bytes(encode_rows(values))
        run = self.envelope()
        self.assertEqual("2026-10-08", run["run"]["reportDate"])
        self.assertIsNotNone(next(e for e in run["events"] if e["eventKind"] == "TERMINAL")["occurredAt"])

    def test_legacy_final_channel_supported(self):
        values = source_rows()
        final = values[-2]["payload"]
        del final["phase"]
        final["channel"] = "final"
        self.path.write_bytes(encode_rows(values))
        self.assertEqual("TRACE_COMPLETE", self.envelope()["events"][-1]["reasonCode"])

    def test_read_budget_and_cursor_resume(self):
        complete = encode_rows(source_rows())
        # 全來源大於單輪run budget；完整行小於budget，prefix重驗可跨round。
        filler = encode_rows([row("event_msg", {"type": "token_count", "ignored": "x" * 500})] * 30)
        self.path.write_bytes(complete + filler)
        seen_complete = False
        for _ in range(400):
            budget = c.ReadBudget(remaining=1800, per_run=1800, seconds=5)
            result = self.collector.scan_codex(self.record, budget)
            self.assertLessEqual(budget.consumed, 1800)
            if any(e["reasonCode"] == "TRACE_COMPLETE" for v in result for e in v["events"]):
                seen_complete = True
                break
        self.assertTrue(seen_complete)

    def test_composite_exec_not_assigned_one_api(self):
        op = c.operation_for_call({"name": "exec", "input": "await tools.exec_command({cmd:'curl /api/one'}); curl /api/two"})
        self.assertEqual({"method": "TOOL", "path": "tool"}, op)

    def test_unique_http_path_no_query_or_secret(self):
        op = c.operation_for_call({"name": "exec_command", "input": 'curl https://host/api/public/trading-calendar?token=SECRET'})
        self.assertEqual({"method": "GET", "path": "/api/public/trading-calendar"}, op)

    def test_passive_http_path_canonical_lengths_and_anonymized_template(self):
        for path in ("/api/" + "a" * 296, "/api/" + "a" * 495, "/api/assets/{id}/a._~-"):
            with self.subTest(length=len(path), template="{" in path):
                self.assertEqual({"method": "POST", "path": path}, c.operation_for_call({"name": "exec_command", "input": f"curl -X POST '{path}'"}))
        self.assertEqual({"method": "GET", "path": "/api/assets/{id}"},
                         c.operation_for_call({"name": "exec_command", "input": "curl 'https://api.example/api/assets/{id}?token=SECRET#PRIVATE'"}))

    def test_passive_invalid_http_path_falls_back_without_partial_matching(self):
        invalid = ("/api/" + "a" * 496, "/api//assets", "/api/assets/", "/api/../assets", "/api/a..b",
                   "/api/assets%2Fprivate", "/api/秘密", "/apiculture/assets", "/assets", "https://host/assets",
                   "http://[invalid/api/assets")
        for path in invalid:
            with self.subTest(path=path):
                self.assertEqual({"method": "TOOL", "path": "tool"}, c.operation_for_call({"name": "exec_command", "input": f"curl '{path}'"}))

    def test_calendar_and_policy_classifications(self):
        self.assertEqual("calendar", c.operation_for_call({"name": "exec_command", "input": "cat 交易日曆_2026.json"})["path"])
        self.assertEqual("policy", c.operation_for_call({"name": "exec_command", "input": "python policy_bundle.py check"})["path"])

    def test_unsafe_iso_unknown_null(self):
        for value in (None, "2026-10-08T09:05:00", "tomorrow", "2026-99-00", 123):
            self.assertIsNone(c.iso(value))
        self.assertTrue(c.iso("2026-10-08T01:05:00Z").endswith("+08:00"))

    def test_source_root_escape_and_symlink(self):
        outside = self.home / "outside.jsonl"
        outside.write_bytes(self.path.read_bytes())
        self.record["path"] = str(outside)
        self.assertEqual("RAW_TRACE_UNAVAILABLE", self.envelope()["events"][0]["reasonCode"])
        link = self.sessions / "linked.jsonl"
        link.symlink_to(outside)
        self.record["path"] = str(link)
        self.assertEqual("RAW_TRACE_UNAVAILABLE", self.envelope()["events"][0]["reasonCode"])

    def test_sqlite_readonly_epoch_consumer_and_injection(self):
        root = self.home / ".codex"
        (root / "sqlite").mkdir()
        app = sqlite3.connect(root / "sqlite/codex-dev.db")
        app.execute("CREATE TABLE automation_runs(thread_id TEXT,automation_id TEXT,created_at INTEGER)")
        app.executemany("INSERT INTO automation_runs VALUES(?,?,?)", [(THREAD, "codex-srpp", 1791421500000), ("x' OR 1=1 --", "codex-srpp", 1791421500000), ("other", "unrelated-task", 1791421500000), ("old", "codex-srpp", 1691421500000)])
        app.commit()
        app.close()
        db = sqlite3.connect(root / "state_5.sqlite")
        db.execute("CREATE TABLE threads(id TEXT,rollout_path TEXT)")
        db.execute("INSERT INTO threads VALUES(?,?)", (THREAD, str(self.path)))
        db.commit()
        db.close()
        records = self.collector.codex_runs(dt.date(2026, 10, 8))
        self.assertEqual(1, len(records))
        self.assertEqual("12:00", records[0]["slot"])
        with c.readonly_db(root / "state_5.sqlite", root, "threads", {"id"}) as connection:
            with self.assertRaises(sqlite3.OperationalError):
                connection.execute("DELETE FROM threads")

    def test_sqlite_schema_missing_fails_closed(self):
        root = self.home / ".codex"
        (root / "sqlite").mkdir()
        sqlite3.connect(root / "sqlite/codex-dev.db").close()
        self.assertEqual([], self.collector.codex_runs(dt.date(2026, 10, 8)))

    def test_epoch_is_fixed_milliseconds(self):
        self.assertEqual(dt.timedelta(hours=8), c.epoch_ms(1791421500000).utcoffset())
        with self.assertRaises(c.ObservationError):
            c.epoch_ms(1791421500)

    def test_negative_pair_time_forces_partial(self):
        values = source_rows()
        values[5]["timestamp"] = "2026-10-08T01:04:59Z"
        self.path.write_bytes(encode_rows(values))
        self.assertEqual("TRACE_PARTIAL", self.envelope()["events"][-1]["reasonCode"])

    def test_unchanged_complete_source_uses_integrity_cache_zero_bytes(self):
        self.envelope()
        budget = c.ReadBudget()
        result = self.collector.scan_codex(self.record, budget)
        self.assertEqual(0, budget.consumed)
        self.assertEqual("TRACE_COMPLETE", result[0]["events"][-1]["reasonCode"])

    def test_preserved_mtime_prefix_change_detected_via_ctime(self):
        self.envelope()
        previous = self.path.stat()
        raw = self.path.read_bytes()
        self.path.write_bytes(raw.replace(b"PUBLIC_FINANCIAL_CANARY", b"ALTERD_FINANCIAL_CANARY"))
        os.utime(self.path, ns=(previous.st_atime_ns, previous.st_mtime_ns))
        self.assertIn("SOURCE_TRACE_CHANGED", [e["reasonCode"] for e in self.envelope()["events"]])

    def test_restart_large_prefix_revalidation_is_bounded_and_resumes(self):
        filler = encode_rows([row("event_msg", {"type": "token_count", "ignored": "x" * 500})] * 12)
        self.path.write_bytes(encode_rows(source_rows()) + filler)
        for _ in range(3):
            self.scan(remaining=1800, per_run=1800)
        self.collector = c.Collector(self.home, self.store)
        complete = False
        for _ in range(40):
            budget = c.ReadBudget(remaining=1800, per_run=1800)
            result = self.collector.scan_codex(self.record, budget)
            self.assertLessEqual(budget.consumed, 1800)
            if any(e["reasonCode"] == "TRACE_COMPLETE" for run in result for e in run["events"]):
                complete = True
                break
        self.assertTrue(complete)

    def test_duplicate_source_json_is_partial_not_success(self):
        with self.path.open("ab") as stream:
            stream.write(b'{"type":"response_item","type":"private"}\n')
        self.assertEqual("TRACE_PARTIAL", self.envelope()["events"][-1]["reasonCode"])


START_GENERIC = "已觀測到工具開始；複合工具只記錄整批耗時。"
END_GENERIC = "已觀測到工具輸出，不推定金融操作或報告成功。"
TOOL_NOTE = "內含工具名稱取自呼叫文字，未收錄參數、指令或輸出。"
JAVA_PROTOCOL = SCRIPTS.parent / "backend/src/main/java/com/steven/assets/service/aiflow/AiFlowProtocol.java"


def tool_source_rows(calls, *, turn=TURN):
    """每個 payload 一組呼叫＋輸出；最後接最終回覆與結束，供公開工具名稱測試使用。"""
    values = [row("session_meta", {"id": THREAD}),
              row("event_msg", {"type": "task_started", "turn_id": turn, "started_at": 1791421500000})]
    for number, payload in enumerate(calls, 1):
        call_type = payload.get("type", "custom_tool_call")
        call_id = payload.get("call_id", f"call_tool{number}")
        values.append(row("response_item", dict(payload, type=call_type, call_id=call_id)))
        values.append(row("response_item", {"type": call_type + "_output", "call_id": call_id,
                                            "output": json.dumps({"exit_code": 0})}, "2026-10-08T01:05:02Z"))
    values += [row("response_item", {"type": "message", "role": "assistant", "phase": "final_answer", "content": [{"text": "FINAL"}]}, "2026-10-08T01:05:03Z"),
               row("event_msg", {"type": "task_complete", "turn_id": turn, "completed_at": 1791421504000}, "2026-10-08T01:05:04Z")]
    return values


def split_top_level(pattern):
    """依最外層 `|` 切分 regex 分支；跳過轉義、字元集與巢狀群組內的 `|`。"""
    branches, current, depth, in_class, index = [], [], 0, False, 0
    while index < len(pattern):
        char = pattern[index]
        if char == "\\":
            current.append(pattern[index:index + 2])
            index += 2
            continue
        if in_class:
            in_class = char != "]"
        elif char == "[":
            in_class = True
        elif char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
        elif char == "|" and depth == 0:
            branches.append("".join(current))
            current = []
            index += 1
            continue
        current.append(char)
        index += 1
    branches.append("".join(current))
    return branches


def java_sensitive_branches():
    source = JAVA_PROTOCOL.read_text(encoding="utf-8")
    match = re.search(r'Pattern\s+SENSITIVE\s*=\s*Pattern\.compile\("((?:[^"\\]|\\.)*)"\)', source)
    assert match, "找不到 AiFlowProtocol.SENSITIVE"
    pattern = re.sub(r"\\(.)", r"\1", match.group(1))
    assert pattern.startswith("(?is)(") and pattern.endswith(")"), pattern
    return split_top_level(pattern[len("(?is)("):-1])


class PublicToolNameTests(Fixture):
    def reset_state(self):
        # 同一測試內改寫來源檔前先清掉 collector state，避免被當成「來源被改寫」。
        for state_file in (self.store.root / "sources").glob("codex-*.json"):
            state_file.unlink()
        self.collector = c.Collector(self.home, self.store)

    def calls(self, *payloads, turn=TURN):
        self.reset_state()
        self.path.write_bytes(encode_rows(tool_source_rows(list(payloads), turn=turn)))
        value = self.envelope()
        c.validate_envelope(value, TODAY)
        return value

    def tool_events(self, value, kind):
        return [e for e in value["events"] if e["stepId"].startswith("call-") and e["eventKind"] == kind]

    def titles(self, value):
        return ([e["title"] for e in self.tool_events(value, "START")], [e["title"] for e in self.tool_events(value, "END")])

    def test_direct_tool_name_is_written_to_call_and_result(self):
        value = self.calls({"name": "exec_command", "input": "ls"})
        start, end = self.tool_events(value, "START")[0], self.tool_events(value, "END")[0]
        self.assertEqual("公開工具呼叫：exec_command", start["title"])
        self.assertEqual("公開工具回傳：exec_command", end["title"])
        # 沒有內含工具時 summary 維持原句，不加註記。
        self.assertEqual(START_GENERIC, start["summary"])
        self.assertEqual(END_GENERIC, end["summary"])
        self.assertEqual({"method": "TOOL", "path": "tool"}, start["operation"])

    def test_function_call_arguments_key_is_supported(self):
        value = self.calls({"type": "function_call", "name": "functions.exec_command", "arguments": json.dumps({"cmd": "ls"})})
        self.assertEqual((["公開工具呼叫：functions.exec_command"], ["公開工具回傳：functions.exec_command"]), self.titles(value))

    def test_exec_wrapper_lists_single_inner_tool(self):
        value = self.calls({"name": "exec", "input": "const r = await tools.exec_command({cmd: 'ls'}); text(r)"})
        start, end = self.tool_events(value, "START")[0], self.tool_events(value, "END")[0]
        self.assertEqual("公開工具呼叫：exec（內含 exec_command）", start["title"])
        self.assertEqual("公開工具回傳：exec（內含 exec_command）", end["title"])
        self.assertEqual(START_GENERIC + TOOL_NOTE + "exec_command", start["summary"])
        self.assertEqual(END_GENERIC + TOOL_NOTE + "exec_command", end["summary"])
        # 仍是閉合分類碼，名稱只出現在 title／summary。
        self.assertEqual({"method": "TOOL", "path": "tool"}, start["operation"])

    def test_exec_wrapper_lists_up_to_three_inner_tools_in_call_order(self):
        value = self.calls({"name": "exec", "input": "await tools.web__run({}); await tools.clock__curr_time({}); tools.exec_command({})"})
        self.assertEqual((["公開工具呼叫：exec（內含 web__run、clock__curr_time、exec_command）"],
                          ["公開工具回傳：exec（內含 web__run、clock__curr_time、exec_command）"]), self.titles(value))

    def test_exec_wrapper_with_four_or_more_inner_tools_shows_total(self):
        value = self.calls({"name": "exec", "input": "tools.apply_patch(a); tools.exec_command(b); tools.write_stdin(c); tools.web__run(d); tools.clock__curr_time(e)"})
        start = self.tool_events(value, "START")[0]
        self.assertEqual("公開工具呼叫：exec（內含 apply_patch、exec_command、write_stdin 等 5 項）", start["title"])
        # summary 至多列 8 個，這裡 5 個全列。
        self.assertTrue(start["summary"].endswith("apply_patch、exec_command、write_stdin、web__run、clock__curr_time"))

    def test_length_trimmed_inner_names_still_report_total(self):
        first, second = "mcp__codex_apps__gmail_search_emails", "mcp__codex_apps__gmail_read_message_x"
        self.assertEqual(36, len(first))
        value = self.calls({"name": "exec", "input": f"tools.{first}({{}}); tools.{second}({{}})"})
        start = self.tool_events(value, "START")[0]
        # 兩個名稱放不下80字元，只留第一個，但被裁掉的仍計入總數而附「等 2 項」。
        self.assertEqual(f"公開工具呼叫：exec（內含 {first} 等 2 項）", start["title"])
        self.assertLessEqual(len(start["title"].split("：", 1)[1]), 80)
        self.assertTrue(start["summary"].endswith(first + "、" + second))

    def test_label_never_exceeds_eighty_characters_and_drops_inner_when_none_fit(self):
        inner = "x" * 64
        label, names = c.tool_label({"name": "functions.exec", "input": f"tools.{inner}(1)"})
        self.assertEqual(["x" * 64], names)
        self.assertEqual("functions.exec", label)
        self.assertNotIn("內含", label)
        self.assertNotIn("等", label)
        label, _ = c.tool_label({"name": "exec", "input": f"tools.{inner}(1)"})
        self.assertEqual(f"exec（內含 {inner}）", label)
        self.assertLessEqual(len(label), 80)
        for count in range(0, 12):
            names = [f"tool_{number:02d}_" + "y" * 50 for number in range(count)]
            label, _ = c.tool_label({"name": "exec", "input": "; ".join(f"tools.{n}(1)" for n in names)})
            self.assertLessEqual(len(label), 80)

    def test_functions_prefix_is_kept_verbatim(self):
        value = self.calls({"name": "functions.exec", "input": "tools.exec_command({})"},
                           {"name": "functions.exec_command", "input": "ls"})
        self.assertEqual((["公開工具呼叫：functions.exec（內含 exec_command）", "公開工具呼叫：functions.exec_command"],
                          ["公開工具回傳：functions.exec（內含 exec_command）", "公開工具回傳：functions.exec_command"]), self.titles(value))

    def test_run_code_wrapper_is_also_expanded(self):
        value = self.calls({"name": "run_code", "input": "await tools.web__run({})"})
        self.assertEqual((["公開工具呼叫：run_code（內含 web__run）"], ["公開工具回傳：run_code（內含 web__run）"]), self.titles(value))

    def test_invalid_payload_names_fall_back_to_generic_titles(self):
        for name in ("exec command", "a/b", "tool$(x)", "工具", "x" * 65, "", "_exec", ".exec", "exec\n", "exec;ls", None, 7):
            with self.subTest(name=name):
                value = self.calls({"name": name, "input": "tools.exec_command({})"})
                self.assertEqual((["公開工具呼叫"], ["公開工具回傳"]), self.titles(value))
                self.assertEqual(START_GENERIC, self.tool_events(value, "START")[0]["summary"])
                self.assertEqual(END_GENERIC, self.tool_events(value, "END")[0]["summary"])
        self.assertIsNone(c.tool_label({}))
        self.assertEqual(("x" * 64, []), c.tool_label({"name": "x" * 64}))

    def test_inner_names_must_pass_the_same_allowlist(self):
        long_name, ok_long = "a" * 65, "b" * 64
        text = (f'tools._x(1); tools["_y"]; tools.${{"z"}}(1); mytools.hidden(1); tools.{long_name}(1); tools["{long_name}"]; '
                f'tools.ok_tool (1); tools[ "ok.bracket" ]; tools.{ok_long}(1); tools.a.b(1); tools. spaced(1)')
        label, names = c.tool_label({"name": "exec", "input": text})
        self.assertEqual(["ok_tool", "ok.bracket", ok_long], names)

    def test_duplicate_inner_tool_counts_once_and_positions_merge_dot_and_bracket(self):
        text = 'tools.alpha(1); tools["beta"]; tools.alpha(2); tools["alpha"]; tools.gamma(3); tools["beta"]; tools.delta(4)'
        label, names = c.tool_label({"name": "exec", "input": text})
        self.assertEqual(["alpha", "beta", "gamma", "delta"], names)
        self.assertEqual("exec（內含 alpha、beta、gamma 等 4 項）", label)

    def test_bracket_form_before_dot_form_follows_text_position(self):
        _, names = c.tool_label({"name": "exec", "input": "tools['first_tool'](); tools.second_tool(); tools[\"third_tool\"]()"})
        self.assertEqual(["first_tool", "second_tool", "third_tool"], names)

    def test_string_literal_containing_call_text_is_still_extracted_known_limit(self):
        # 名稱取自呼叫文字而非精確解析；字串字面值內的 tools.x( 也會被擷取，這是已載明的限制。
        _, names = c.tool_label({"name": "exec", "input": 'const note = "tools.not_really_called(1)"'})
        self.assertEqual(["not_really_called"], names)

    def test_only_composite_wrappers_scan_call_text(self):
        for name in ("exec_command", "functions.exec_command", "spawn_agent", "apply_patch"):
            self.assertEqual((name, []), c.tool_label({"name": name, "input": "tools.hidden_tool(1)"}))
        self.assertEqual(("exec", []), c.tool_label({"name": "exec", "input": 123}))
        self.assertEqual(("exec", []), c.tool_label({"name": "exec"}))

    def test_worst_case_eight_long_inner_names_stay_valid(self):
        names = [f"t{number}" + "x" * 62 for number in range(8)]
        self.assertTrue(all(len(n) == 64 for n in names))
        text = "; ".join(f"tools.{n}(1)" for n in names)
        for wrapper in ("exec", "functions.exec"):
            with self.subTest(wrapper=wrapper):
                value = self.calls({"name": wrapper, "input": text})
                for item in self.tool_events(value, "START") + self.tool_events(value, "END"):
                    self.assertLessEqual(len(item["title"]), 100)
                    self.assertLessEqual(len(item["summary"]), 500)
                start = self.tool_events(value, "START")[0]
                if wrapper == "exec":
                    self.assertEqual(f"公開工具呼叫：exec（內含 {names[0]} 等 8 項）", start["title"])
                else:
                    # 14字元的前綴讓一個64字元內含名稱也放不下，標籤只留名稱。
                    self.assertEqual("公開工具呼叫：functions.exec", start["title"])
                # summary 先逐一減少名稱到放得下，且至少保留1個。
                kept = [n for n in names if n in start["summary"]]
                self.assertGreaterEqual(len(kept), 1)
                self.assertLess(len(kept), 8)
                self.assertEqual(names[:len(kept)], kept)
                longer = START_GENERIC + TOOL_NOTE + "、".join(names[:len(kept) + 1])
                self.assertGreater(len(longer), 500)

    def test_sensitive_looking_names_fall_back_to_generic_text(self):
        cases = {
            "six_digits": "tools.tool_1234567(1)",
            "api_key": "tools.api_key_reader(1)",
            "secret": "tools.secret_store(1)",
            "password": "tools.password_vault(1)",
            "token_word": "tools.token(1)",
            # export 後接「等 N 項」的空白會命中 backend 的 \bexport\s。
            "export_with_total": "tools.alpha(1); tools.beta(2); tools.export(3); tools.delta(4)",
            "authorization": "tools.authorization_check(1)",
        }
        for case, text in cases.items():
            with self.subTest(case=case):
                value = self.calls({"name": "exec", "input": text})
                self.assertEqual((["公開工具呼叫"], ["公開工具回傳"]), self.titles(value))
                self.assertEqual(START_GENERIC, self.tool_events(value, "START")[0]["summary"])
        for name in ("token=abc", "api key", "secret/x", "tool_1234567x_"):
            with self.subTest(payload_name=name):
                value = self.calls({"name": name, "input": "ls"})
                self.assertEqual((["公開工具呼叫"], ["公開工具回傳"]), self.titles(value))
        # 六位數字只有 backend 規則會擋，確認這條防線真的有生效，而非靠 unsafe_public_text。
        self.assertFalse(c.unsafe_public_text("公開工具呼叫：exec（內含 tool_1234567）"))
        title, summary = c.public_tool_text("公開工具呼叫", {"label": "exec（內含 tool_1234567）", "names": ["tool_1234567"], "total": 1},
                                           "公開工具呼叫", START_GENERIC)
        self.assertEqual(("公開工具呼叫", START_GENERIC), (title, summary))

    def test_public_tool_text_rejects_unusable_or_oversized_state_labels(self):
        good = {"label": "exec（內含 exec_command）", "names": ["exec_command"], "total": 1}
        self.assertEqual(("公開工具呼叫：exec（內含 exec_command）", START_GENERIC + TOOL_NOTE + "exec_command"),
                         c.public_tool_text("公開工具呼叫", good, "公開工具呼叫", START_GENERIC))
        bad_values = [None, "x", [], {}, {"label": "x"}, {"label": "", "names": [], "total": 0},
                      {"label": "x" * 81, "names": [], "total": 0}, dict(good, names="exec_command"),
                      dict(good, names=["bad name"]), dict(good, names=["a"] * 9), dict(good, total="1"),
                      dict(good, total=True), dict(good, total=0), {"label": 5, "names": [], "total": 0}]
        for bad in bad_values:
            with self.subTest(bad=bad):
                self.assertEqual(("公開工具回傳", END_GENERIC), c.public_tool_text("公開工具回傳", bad, "公開工具回傳", END_GENERIC))

    def test_backend_sensitive_equivalent_is_ascii_semantics(self):
        self.assertTrue(c.BACKEND_SENSITIVE.search("叫export x"))
        self.assertIsNone(re.compile(c.BACKEND_SENSITIVE_PATTERN, re.I | re.S).search("叫export x"))
        self.assertTrue(c.BACKEND_SENSITIVE.search("EXPORT\tx"))
        self.assertTrue(c.BACKEND_SENSITIVE.search("a\nCHAIN\nof\nTHOUGHT"))
        self.assertIsNone(c.BACKEND_SENSITIVE.search("公開工具呼叫：exec（內含 exec_command、web__run 等 4 項）"))

    def test_backend_sensitive_pattern_is_parsed_from_java_source_branch_by_branch(self):
        self.assertTrue(JAVA_PROTOCOL.is_file(), JAVA_PROTOCOL)
        java_branches = java_sensitive_branches()
        python_pattern = c.BACKEND_SENSITIVE_PATTERN
        self.assertTrue(python_pattern.startswith("(") and python_pattern.endswith(")"))
        python_branches = split_top_level(python_pattern[1:-1])
        self.assertEqual(java_branches, python_branches)
        # 每個分支各給一個命中樣本；backend 新增或刪除分支會讓筆數不符而失敗，迫使雙方一起更新。
        samples = ["Authorization", "Bearer x", "token = 1", "password", "secret", "api-key", "private reasoning",
                   "chain of thought", "export x", "$(x)", "```", "<b>", "a@b.cc", "C:\\x", "/Users/x", "/home/x",
                   "USD 12", "金額：5", "1234567"]
        self.assertEqual(len(java_branches), len(samples))
        java_whole = re.compile("(" + "|".join(java_branches) + ")", re.I | re.S | re.A)
        for branch, sample in zip(java_branches, samples):
            with self.subTest(branch=branch):
                expected = re.compile(branch, re.I | re.S | re.A)
                self.assertIsNotNone(expected.search(sample))
                self.assertIsNotNone(c.BACKEND_SENSITIVE.search(sample))
                self.assertEqual(bool(java_whole.search(sample)), bool(c.BACKEND_SENSITIVE.search(sample)))
        for sample in ("公開工具呼叫：exec（內含 exec_command）", "web__run", "tools_12345", "Tokenizer", "export_x", "exports x"):
            self.assertEqual(bool(java_whole.search(sample)), bool(c.BACKEND_SENSITIVE.search(sample)), sample)

    def test_canary_input_never_reaches_title_summary_or_evidence(self):
        value = self.calls(
            {"name": "exec", "input": "Bearer SECRET_CANARY; holdings=FINANCIAL_CANARY; await tools.exec_command({cmd: 'curl -H Authorization https://x/api/assets/latest'})"},
            {"name": "exec_command", "input": "export TOKEN=SECRET_CANARY; echo HOLDINGS_CANARY"})
        text = c.canonical(value).decode()
        for canary in ("SECRET_CANARY", "FINANCIAL_CANARY", "HOLDINGS_CANARY", "Bearer", "Authorization", "curl", "https://"):
            self.assertNotIn(canary, text)
        self.assertEqual(["公開工具呼叫：exec（內含 exec_command）", "公開工具呼叫：exec_command"], self.titles(value)[0])
        persisted = "\n".join(p.read_text() for p in (self.store.root / "sources").glob("*.json"))
        for canary in ("SECRET_CANARY", "FINANCIAL_CANARY", "HOLDINGS_CANARY"):
            self.assertNotIn(canary, persisted)

    def test_state_keeps_bounded_label_even_for_pathological_input(self):
        text = "; ".join(f"tools.tool_{number}_{'z' * 40}(1)" for number in range(3000))
        value = self.calls({"name": "exec", "input": text})
        self.assertIn("等 3000 項", self.tool_events(value, "START")[0]["title"])
        state = c.strict_json(next((self.store.root / "sources").glob("codex-*.json")).read_bytes())
        label = next(iter(next(iter(state["turns"].values()))["calls"].values()))["label"]
        self.assertEqual({"label", "names", "total"}, set(label))
        self.assertEqual(8, len(label["names"]))
        self.assertEqual(3000, label["total"])
        self.assertLess(len(c.canonical(label)), 1200)

    def test_end_event_reuses_label_across_save_and_reload(self):
        full = tool_source_rows([{"name": "exec", "input": "await tools.exec_command({}); await tools.web__run({})"}])
        # session_meta、task_started、呼叫為第一段；輸出以後在下一輪（新 Store／Collector，模擬重啟）才看到。
        self.path.write_bytes(encode_rows(full[:3]))
        first = self.envelope()
        self.assertEqual([], self.tool_events(first, "END"))
        start = self.tool_events(first, "START")[0]
        state_file = next((self.store.root / "sources").glob("codex-*.json"))
        label = next(iter(next(iter(c.strict_json(state_file.read_bytes())["turns"].values()))["calls"].values()))["label"]
        self.assertIsInstance(label["names"], list)
        self.store.close()
        self.store = c.Store(self.home / "state", TODAY)
        self.collector = c.Collector(self.home, self.store)
        with self.path.open("ab") as stream:
            stream.write(encode_rows(full[3:]))
        second = self.envelope()
        c.validate_envelope(second, TODAY)
        self.assertEqual("公開工具回傳：exec（內含 exec_command、web__run）", self.tool_events(second, "END")[0]["title"])
        self.assertEqual(start, self.tool_events(second, "START")[0])
        self.assertEqual("TRACE_COMPLETE", second["events"][-1]["reasonCode"])

    def test_pre_deployment_state_without_label_keeps_generic_end_and_frozen_start(self):
        full = tool_source_rows([{"name": "exec", "input": "await tools.exec_command({})"}])
        self.path.write_bytes(encode_rows(full[:3]))
        # 舊 collector 的行為：沒有 label 欄位，START 事件已凍結為原泛稱。
        with mock.patch.object(c, "tool_label", return_value=None):
            old_first = self.envelope()
        state_file = next((self.store.root / "sources").glob("codex-*.json"))
        state = c.strict_json(state_file.read_bytes())
        for call in next(iter(state["turns"].values()))["calls"].values():
            call.pop("label")
            self.assertNotIn("label", call)
        c.atomic_json(state_file, state)
        self.store.close()
        self.store = c.Store(self.home / "state", TODAY)
        self.collector = c.Collector(self.home, self.store)
        with self.path.open("ab") as stream:
            stream.write(encode_rows(full[3:]))
        value = self.envelope()
        c.validate_envelope(value, TODAY)
        start, end = self.tool_events(value, "START")[0], self.tool_events(value, "END")[0]
        self.assertEqual("公開工具回傳", end["title"])
        self.assertEqual(END_GENERIC, end["summary"])
        self.assertEqual("CALL_RESULT", end["relation"])
        self.assertEqual([start["eventId"]], end["parentEventIds"])
        self.assertEqual("公開工具呼叫", start["title"])
        self.assertEqual(self.tool_events(old_first, "START")[0], start)
        self.assertEqual("TRACE_COMPLETE", value["events"][-1]["reasonCode"])

    def test_unrecognizable_state_label_does_not_raise(self):
        full = tool_source_rows([{"name": "exec", "input": "await tools.exec_command({})"}])
        self.path.write_bytes(encode_rows(full[:3]))
        self.envelope()
        state_file = next((self.store.root / "sources").glob("codex-*.json"))
        state = c.strict_json(state_file.read_bytes())
        for call in next(iter(state["turns"].values()))["calls"].values():
            call["label"] = {"label": 5, "names": "oops"}
        c.atomic_json(state_file, state)
        self.store.close()
        self.store = c.Store(self.home / "state", TODAY)
        self.collector = c.Collector(self.home, self.store)
        with self.path.open("ab") as stream:
            stream.write(encode_rows(full[3:]))
        value = self.envelope()
        c.validate_envelope(value, TODAY)
        self.assertEqual("公開工具回傳", self.tool_events(value, "END")[0]["title"])

    def rich_rows(self):
        return tool_source_rows([
            {"name": "exec", "input": "tools.exec_command({}); tools.web__run({})"},
            {"name": "functions.exec_command", "input": "curl -X POST https://host/api/public/trading-calendar"},
            {"name": "functions.exec", "input": "Bearer SECRET_CANARY"},
            {"name": "run_code", "input": "tools['clock__curr_time']()"},
            {"name": "invalid name!", "input": "ls"},
            {"type": "function_call", "name": "spawn_agent", "arguments": "{}"},
        ])

    def test_naming_changes_only_title_and_summary(self):
        # 同一輸入下，剔除 title／summary 後的 envelope 必須與舊泛稱輸出逐位元相同：
        # eventId／stepId／sequence／operation／parent／relation／status／evidence 都不得變。
        def strip(value):
            clone = json.loads(c.canonical(value))
            for item in clone["events"]:
                item["title"] = item["summary"] = ""
            return c.canonical(clone)
        for label, rows in (("default", source_rows()), ("rich", self.rich_rows()), ("child", source_rows(child=True))):
            with self.subTest(fixture=label):
                self.path.write_bytes(encode_rows(rows))
                named = c.Collector(self.home, c.Store(self.home / "named-no-write", TODAY, dry_run=True)).scan_codex(self.record, c.ReadBudget())
                with mock.patch.object(c, "tool_label", return_value=None):
                    generic = c.Collector(self.home, c.Store(self.home / "generic-no-write", TODAY, dry_run=True)).scan_codex(self.record, c.ReadBudget())
                self.assertEqual(strip(named[0]), strip(generic[0]))
                self.assertEqual({e["eventId"] for e in generic[0]["events"]}, {e["eventId"] for e in named[0]["events"]})
                generic_titles = {e["title"] for e in generic[0]["events"] if e["stepId"].startswith("call-")}
                self.assertEqual({"公開工具呼叫", "公開工具回傳"}, generic_titles)
                self.assertNotEqual(c.canonical(named[0]), c.canonical(generic[0]))
                c.validate_envelope(named[0], TODAY)

    def test_rich_titles_and_operation_classification_are_unchanged(self):
        self.path.write_bytes(encode_rows(self.rich_rows()))
        value = self.envelope()
        starts = self.tool_events(value, "START")
        self.assertEqual(["公開工具呼叫：exec（內含 exec_command、web__run）",
                          "公開工具呼叫：functions.exec_command",
                          "公開工具呼叫：functions.exec",
                          "公開工具呼叫：run_code（內含 clock__curr_time）",
                          "公開工具呼叫",
                          "公開工具呼叫：spawn_agent"], [e["title"] for e in starts])
        self.assertEqual([{"method": "TOOL", "path": "tool"},
                          {"method": "POST", "path": "/api/public/trading-calendar"},
                          {"method": "TOOL", "path": "tool"},
                          {"method": "TOOL", "path": "tool"},
                          {"method": "TOOL", "path": "tool"},
                          {"method": "TOOL", "path": "tool"}], [e["operation"] for e in starts])
        for start, end in zip(starts, self.tool_events(value, "END")):
            self.assertEqual(start["stepId"], end["stepId"])
            self.assertEqual([start["eventId"]], end["parentEventIds"])
            self.assertEqual("CALL_RESULT", end["relation"])
            self.assertEqual(start["operation"], end["operation"])
            self.assertEqual(start["title"].replace("公開工具呼叫", "公開工具回傳"), end["title"])
        self.assertEqual("TRACE_PARTIAL", value["events"][-1]["reasonCode"])

    def test_named_missing_child_tools_remain_partial(self):
        for name in ("spawn_agent", "functions.create_thread", "tools.spawn_thread"):
            with self.subTest(name=name):
                value = self.calls({"name": name, "input": "{}"})
                self.assertEqual(f"公開工具呼叫：{name}", self.tool_events(value, "START")[0]["title"])
                self.assertEqual("TRACE_PARTIAL", value["events"][-1]["reasonCode"])

    def test_duplicate_call_id_and_unpaired_output_keep_existing_malformed_behavior(self):
        value = self.calls({"name": "exec", "call_id": "dup1", "input": "tools.exec_command({})"},
                           {"name": "exec", "call_id": "dup1", "input": "tools.web__run({})"})
        self.assertEqual("TRACE_PARTIAL", value["events"][-1]["reasonCode"])
        # 第二個重複 call_id 不覆蓋原呼叫，標籤也維持第一個。
        self.assertEqual(["公開工具呼叫：exec（內含 exec_command）"], self.titles(value)[0])
        rows = tool_source_rows([{"name": "exec", "input": "tools.exec_command({})"}])
        rows[3]["payload"]["call_id"] = "unmatched"
        self.reset_state()
        self.path.write_bytes(encode_rows(rows))
        orphan = self.envelope()
        self.assertEqual("TRACE_PARTIAL", orphan["events"][-1]["reasonCode"])
        self.assertEqual([], self.tool_events(orphan, "END"))

    def test_acked_named_events_are_not_requeued_and_replay_keeps_ids(self):
        value = self.calls({"name": "exec", "input": "tools.exec_command({}); tools.web__run({})"})
        self.assertGreater(self.store.enqueue(value), 0)
        pending = "\n".join(p.read_text() for p in (self.store.root / "pending").glob("*.json"))
        self.assertIn("公開工具呼叫：exec（內含 exec_command、web__run）", pending)
        result = s.send_round(self.store, c.DEFAULT_ENDPOINT, self.token(), transport=self.ack)
        self.assertEqual(0, result["pendingBatches"])
        self.assertEqual(0, self.store.enqueue(value))
        self.assertEqual(len(value["events"]), self.store.enqueue(value, replay=True))


class MarketSessionDiscoveryTests(Fixture):
    def seed_sources(self, count=63):
        root = self.home / ".codex"
        (root / "sqlite").mkdir(exist_ok=True)
        with sqlite3.connect(root / "sqlite/codex-dev.db") as app, sqlite3.connect(root / "state_5.sqlite") as threads:
            app.execute("CREATE TABLE automation_runs(thread_id TEXT PRIMARY KEY,automation_id TEXT,created_at INTEGER)")
            threads.execute("CREATE TABLE threads(id TEXT PRIMARY KEY,rollout_path TEXT)")
            base = int(dt.datetime(2026, 10, 8, 22, tzinfo=c.TAIPEI).timestamp() * 1000)
            records = []
            for number in range(count):
                thread = f"test-thread-{number:05d}"
                path = self.sessions / (thread + ".jsonl")
                values = source_rows()
                values[0]["payload"]["id"] = thread
                path.write_bytes(encode_rows(values))
                automation = tuple(c.CODEX_TASKS)[number % 3]
                # 相同created_at的兩筆由thread_id穩定排序，不能用timestamp-only游標。
                stamp = base - (number // 2) * 60_000
                app.execute("INSERT INTO automation_runs VALUES(?,?,?)", (thread, automation, stamp))
                threads.execute("INSERT INTO threads VALUES(?,?)", (thread, str(path)))
                records.append((thread, automation, stamp, path))
        return records

    def collect_threads(self, collector=None, budget=None):
        collector = collector or self.collector
        seen = []
        original = collector.scan_codex
        def scan(record, limit):
            seen.append(record["thread"])
            return original(record, limit)
        with mock.patch.object(collector, "scan_codex", side_effect=scan), mock.patch.object(s, "post") as post:
            result = collector.collect(consumer="Codex", budget=budget)
            post.assert_not_called()
        self.assertLessEqual(len(result["runs"]), 25)
        self.assertLessEqual(result["readBytes"], 64 * 1024 * 1024)
        return seen, result

    def test_three_exact_source_ids_dynamic_placeholders_and_exclusion(self):
        sources = self.seed_sources(3)
        with sqlite3.connect(self.home / ".codex/sqlite/codex-dev.db") as db:
            for number, automation in enumerate(("22-00-2", "srpp-codex", "unknown-us-tool")):
                db.execute("INSERT INTO automation_runs VALUES(?,?,?)", (f"excluded-{number}", automation, sources[0][2]))
            for thread, date in (("expired", TODAY - dt.timedelta(days=90)), ("future", TODAY + dt.timedelta(days=1))):
                stamp = int(dt.datetime.combine(date, dt.time(), c.TAIPEI).timestamp() * 1000)
                db.execute("INSERT INTO automation_runs VALUES(?,?,?)", (thread, "22-00", stamp))
        records = self.collector.codex_runs(None)
        self.assertEqual({"codex-srpp-09-30", "codex-srpp", "22-00"}, {record["automation"] for record in records})
        self.assertEqual({"09:05", "12:00", "22:00"}, {record["slot"] for record in records})
        self.assertEqual(3, len(records))
        self.assertEqual(records, self.collector.codex_runs(None))
        self.assertFalse((self.store.root / "sources/codex-discovery.json").exists())

    def test_background_more_than_25_mixed_sources_fair_pages_and_wrap(self):
        sources = self.seed_sources()
        expected = [record[0] for record in sources]
        observed = []
        app_path = self.home / ".codex/sqlite/codex-dev.db"
        app_hash = hashlib.sha256(app_path.read_bytes()).hexdigest()
        for expected_page in (expected[:25], expected[25:50], expected[50:]):
            seen, _ = self.collect_threads()
            self.assertEqual(expected_page, seen)
            observed.extend(seen)
        self.assertEqual(set(expected), set(observed))
        self.assertEqual(expected[:25], self.collect_threads()[0])
        self.assertEqual(app_hash, hashlib.sha256(app_path.read_bytes()).hexdigest())
        cursor = self.store.load("codex-discovery")
        self.assertEqual(TODAY.isoformat(), cursor["reportDate"])
        self.assertEqual(expected[24], cursor["threadId"])

    def test_budget_midpage_advances_only_actually_scanned_candidates(self):
        sources = self.seed_sources()
        allowance = sum(record[3].stat().st_size for record in sources[:2])
        seen, _ = self.collect_threads(budget=c.ReadBudget(remaining=allowance))
        self.assertEqual([record[0] for record in sources[:2]], seen)
        self.assertEqual(sources[1][0], self.store.load("codex-discovery")["threadId"])
        self.assertEqual(sources[2][0], self.collector.codex_runs(None)[0]["thread"])
        rest = []
        for _ in range(3):
            rest.extend(self.collect_threads()[0])
        self.assertTrue({record[0] for record in sources[2:]}.issubset(rest))

    def test_exhausted_budget_before_first_candidate_never_advances_cursor(self):
        self.seed_sources()
        self.assertEqual([], self.collect_threads(budget=c.ReadBudget(seconds=0))[0])
        self.assertFalse((self.store.root / "sources/codex-discovery.json").exists())

    def test_restart_and_new_latest_source_are_eventually_discovered(self):
        sources = self.seed_sources()
        self.assertEqual([record[0] for record in sources[:25]], self.collect_threads()[0])
        newest_thread = "newest-us-run"
        newest_path = self.sessions / "newest-us.jsonl"
        values = source_rows()
        values[0]["payload"]["id"] = newest_thread
        newest_path.write_bytes(encode_rows(values))
        with sqlite3.connect(self.home / ".codex/sqlite/codex-dev.db") as app, sqlite3.connect(self.home / ".codex/state_5.sqlite") as threads:
            app.execute("INSERT INTO automation_runs VALUES(?,?,?)", (newest_thread, "22-00", sources[0][2] + 60_000))
            threads.execute("INSERT INTO threads VALUES(?,?)", (newest_thread, str(newest_path)))
        self.store.close()
        self.store = c.Store(self.home / "state", TODAY)
        self.collector = c.Collector(self.home, self.store)
        seen = []
        for _ in range(3):
            seen.extend(self.collect_threads()[0])
        self.assertEqual(sources[25][0], seen[0])
        self.assertIn(newest_thread, seen)
        self.assertTrue({record[0] for record in sources[25:]}.issubset(seen))

    def test_explicit_date_selection_is_deterministic_and_does_not_mutate_discovery(self):
        sources = self.seed_sources()
        self.collect_threads()
        cursor_path = self.store.root / "sources/codex-discovery.json"
        cursor = cursor_path.read_bytes()
        day = dt.date(2026, 10, 8)
        first = self.collector.codex_runs(day)
        self.assertEqual([record[0] for record in sources[:25]], [record["thread"] for record in first])
        self.assertEqual(first, self.collector.codex_runs(day))
        self.collector.collect(day=day, consumer="Codex")
        self.assertEqual(cursor, cursor_path.read_bytes())

    def test_dry_run_has_zero_discovery_cursor_and_filesystem_writes(self):
        self.seed_sources()
        state = self.home / "dry-state-must-not-exist"
        store = c.Store(state, TODAY, dry_run=True)
        collector = c.Collector(self.home, store)
        try:
            with mock.patch.object(store, "save", wraps=store.save) as save, mock.patch.object(c, "atomic_bytes") as writer:
                self.assertEqual(25, len(self.collect_threads(collector)[0]))
                self.assertFalse(any(call.args[0] == "codex-discovery" for call in save.call_args_list))
                writer.assert_not_called()
            self.assertFalse(state.exists())
        finally:
            store.close()

    def test_discovery_cache_uses_own_retention_and_crash_cleanup_scope(self):
        self.seed_sources(3)
        self.collect_threads()
        cursor_path = self.store.root / "sources/codex-discovery.json"
        self.assertTrue(cursor_path.exists())
        self.store.today = TODAY + dt.timedelta(days=90)
        self.store.purge()
        self.assertFalse(cursor_path.exists())
        self.assertTrue(c.managed_state_name("sources", "codex-discovery.json"))
        self.assertFalse(c.managed_state_name("sources", "app-discovery.json"))

    def test_us_early_manual_run_and_cross_midnight_turn_keep_planned_slot_original_date(self):
        self.record.update(automation="22-00", slot="22:00")
        values = []
        for turn, started in ((TURN, "2026-10-08T13:57:00+00:00"), (TURN2, "2026-10-08T16:05:00+00:00")):
            start = dt.datetime.fromisoformat(started)
            for index, value in enumerate(source_rows(turn)):
                value["timestamp"] = (start + dt.timedelta(seconds=index)).isoformat()
                value["payload"].pop("started_at", None)
                value["payload"].pop("completed_at", None)
                values.append(value)
        self.path.write_bytes(encode_rows(values))
        envelopes = self.scan()
        self.assertEqual(2, len(envelopes))
        self.assertEqual({"22:00"}, {value["run"]["reportSlot"] for value in envelopes})
        self.assertEqual({"2026-10-08"}, {value["run"]["reportDate"] for value in envelopes})
        self.assertEqual({"2026-10-08", "2026-10-09"}, {value["run"]["startedAt"][:10] for value in envelopes})
        self.assertEqual(2, len({value["run"]["runId"] for value in envelopes}))
        for value in envelopes:
            c.validate_envelope(value, TODAY)
            self.assertEqual("TRACE_COMPLETE", value["events"][-1]["reasonCode"])

    def test_us_missing_source_is_unavailable_without_fake_terminal(self):
        self.record.update(automation="22-00", slot="22:00", path=None)
        value = self.envelope()
        c.validate_envelope(value, TODAY)
        self.assertEqual("22:00", value["run"]["reportSlot"])
        self.assertEqual("RAW_TRACE_UNAVAILABLE", value["events"][0]["reasonCode"])
        self.assertFalse(any(item["eventKind"] in {"START", "END", "TERMINAL"} for item in value["events"]))

    def test_unverified_claude_us_metadata_and_artifact_do_not_create_runs(self):
        value = {"scheduledTaskId": "unverified-us-claude", "cliSessionId": TURN, "createdAt": 1791468000000, "reportSlot": "22:00"}
        self.assertEqual((None, None), self.collector.claude_metadata_envelope(value))
        root = self.home / "Project/SRPP/data/output/daily_runs/2026-10-08/unverified-us"
        root.mkdir(parents=True)
        (root / "result.json").write_bytes(c.canonical({"consumer": "Claude", "reportDate": "2026-10-08", "reportSlot": "22:00"}))
        self.assertEqual([], self.collector.claude_artifacts(dt.date(2026, 10, 8), c.ReadBudget()))
        self.assertEqual(1, self.collector.errors["CLAUDE_ARTIFACT_SLOT_UNAVAILABLE"])

    def test_us_exact_binding_accepts_both_codex_source_kinds_without_adding_fields(self):
        for source in ("CODEX_ROLLOUT", "SCHEDULE_EVENTS"):
            value = self.envelope()
            value["run"].update(automationId="22-00", reportSlot="22:00", sourceKind=source)
            c.validate_envelope(value, TODAY)
            self.assertEqual(c.RUN_KEYS, set(value["run"]))
            self.assertGreater(self.store.enqueue(value), 0)

    def test_us_binding_wrong_consumer_id_or_slot_reject_before_state_and_spool(self):
        cases = [("Codex", "22-00", slot) for slot in ("09:05", "12:00", "11:40")]
        cases += [("Codex", task, "22:00") for task in ("codex-srpp-09-30", "codex-srpp", "22-00-2", "srpp-codex", "unknown-us-tool")]
        cases += [("Claude", task, "22:00") for task in ("22-00", "srpp-0905-claude", "srpp-1150-claude", "unverified-us-claude")]
        for consumer, task, slot in cases:
            with self.subTest(consumer=consumer, automation=task, slot=slot):
                value = self.envelope()
                value["run"].update(consumer=consumer, automationId=task, reportSlot=slot,
                                    sourceKind="CODEX_ROLLOUT" if consumer == "Codex" else "CLAUDE_ARTIFACT")
                input_file = self.home / "invalid-us-envelope.json"
                input_file.write_bytes(c.canonical(value))
                state_directory = self.home / "invalid-us-no-state"
                with mock.patch.object(c, "Store") as store_factory, mock.patch.object(c, "atomic_bytes") as writer, mock.patch.object(c.dt, "datetime", wraps=dt.datetime) as clock, contextlib.redirect_stdout(io.StringIO()):
                    clock.now.return_value = dt.datetime(2026, 10, 11, 9, tzinfo=c.TAIPEI)
                    self.assertEqual(1, c.cli(["--enqueue-json", str(input_file), "--state-dir", str(state_directory)]))
                    store_factory.assert_not_called()
                    writer.assert_not_called()
                self.assertFalse(state_directory.exists())
                with mock.patch.object(c, "atomic_bytes") as writer, mock.patch.object(self.store, "save") as save:
                    with self.assertRaises(c.ObservationError):
                        self.store.enqueue(value)
                    writer.assert_not_called()
                    save.assert_not_called()
        self.assertEqual([], list((self.store.root / "pending").glob("*.json")))

    def test_existing_tw_consumer_id_allowed_sets_do_not_become_strict_slot_pairs(self):
        for consumer, tasks, source in (("Codex", ("codex-srpp-09-30", "codex-srpp"), "CODEX_ROLLOUT"),
                                        ("Claude", tuple(c.CLAUDE_TASKS), "CLAUDE_ARTIFACT")):
            for task in tasks:
                for slot in ("09:05", "12:00", "11:40"):
                    with self.subTest(consumer=consumer, automation=task, slot=slot):
                        value = self.envelope()
                        value["run"].update(consumer=consumer, automationId=task, reportSlot=slot, sourceKind=source)
                        c.validate_envelope(value, TODAY)

    def test_existing_tw_full_canonical_envelopes_and_uuid_are_unchanged(self):
        # Task 500：fixture 的呼叫名稱是 functions.exec，公開工具 title 由泛稱改為
        # 「公開工具呼叫／回傳：functions.exec」，整份 envelope 的 digest 必然改變，故重新鎖定。
        # runId 與「無 period 欄位」維持不變；eventId／stepId／operation 等不變量另由
        # PublicToolNameTests 的結構等價測試保護，不得為了舊 hash 回綠而特例化 title。
        expected = {
            "09:05": "44036bbfb34cb46a70f8297226b53ac5014a2f0d47e388ba0daa741f3d0bf211",
            "12:00": "d6a3f7ef5fc1f7e9da1fc71d7ba8d35fec6a2184de25c2a58203aabc2cc78613",
            "11:40": "24e0457d988af1116e29927c857b133b57259489bd238622c280d8861c3bd55e",
        }
        for slot, digest in expected.items():
            store = c.Store(self.home / "legacy-no-write", TODAY, dry_run=True)
            record = dict(self.record, slot=slot, automation="codex-srpp-09-30" if slot == "09:05" else "codex-srpp")
            value = c.Collector(self.home, store).scan_codex(record, c.ReadBudget())[0]
            self.assertEqual(digest, hashlib.sha256(c.canonical(value)).hexdigest())
            self.assertEqual("66a62e80-7ba1-54e1-9e9f-b0c22f2ebe55", value["run"]["runId"])
            self.assertNotIn("period", value["run"])


class SpoolSenderTests(Fixture):
    def test_enqueue_barrier_never_enters_sender_or_socket(self):
        barrier = threading.Event()
        def forbidden(*_):
            barrier.set()
            raise AssertionError("network reached")
        envelope = self.envelope()
        with mock.patch.object(s, "post", side_effect=forbidden), mock.patch("http.client.HTTPConnection", side_effect=forbidden):
            count = self.store.enqueue(envelope)
        self.assertEqual(len(envelope["events"]), count)
        self.assertFalse(barrier.is_set())

    def test_pending_batch_idempotent_and_secure(self):
        value = self.envelope()
        self.assertGreater(self.store.enqueue(value), 0)
        self.assertEqual(0, self.store.enqueue(value))
        self.assertEqual(0o700, self.store.root.stat().st_mode & 0o777)
        path = next((self.store.root / "pending").glob("*.json"))
        self.assertEqual(0o600, path.stat().st_mode & 0o777)

    def test_ack_only_matching_durable_ids_clears_pending(self):
        value = self.envelope()
        self.store.enqueue(value)
        result = s.send_round(self.store, c.DEFAULT_ENDPOINT, self.token(), transport=self.ack)
        self.assertEqual(1, result["ackedBatches"])
        self.assertEqual(0, result["pendingBatches"])
        self.assertEqual({e["eventId"] for e in value["events"]}, self.store.ack_ids(value["run"]))
        self.assertEqual(0, self.store.enqueue(value))
        self.assertGreater(self.store.enqueue(value, replay=True), 0)

    def test_failed_network_keeps_same_bytes_and_ids(self):
        self.store.enqueue(self.envelope())
        before = {p.name: p.read_bytes() for p in (self.store.root / "pending").glob("*.json")}
        def fail(*_):
            raise TimeoutError("secret endpoint")
        result = s.send_round(self.store, c.DEFAULT_ENDPOINT, self.token(), transport=fail)
        after = {p.name: p.read_bytes() for p in (self.store.root / "pending").glob("*.json")}
        self.assertEqual(before, after)
        self.assertEqual({"UPLOAD_UNAVAILABLE": 1}, result["errors"])
        self.assertNotIn("secret endpoint", json.dumps(result))

    def test_unauthorized_source_does_not_false_ack(self):
        value = self.envelope()
        value["run"]["sourceKind"] = "SCHEDULE_EVENTS"
        self.store.enqueue(value)
        result = s.send_round(self.store, c.DEFAULT_ENDPOINT, self.token(), transport=lambda *_: (403, b'{"code":"SOURCE_FORBIDDEN"}'))
        self.assertEqual(0, result["ackedBatches"])
        self.assertEqual(1, result["pendingBatches"])
        self.assertEqual(set(), self.store.ack_ids(value["run"]))

    def test_ack_wrong_ids_keeps_pending(self):
        self.store.enqueue(self.envelope())
        def bad(_, __, raw):
            value = c.strict_json(raw)
            return 201, c.canonical({"runId": value["run"]["runId"], "inserted": 1, "replayed": 0, "eventIds": ["wrong"]})
        result = s.send_round(self.store, c.DEFAULT_ENDPOINT, self.token(), transport=bad)
        self.assertEqual({"INVALID_ACK": 1}, result["errors"])
        self.assertEqual(1, result["pendingBatches"])

    def test_ack_duplicate_ids_counts_or_extra_field_rejected(self):
        value = self.envelope()
        ids = [e["eventId"] for e in value["events"]]
        for ack in ({"runId": value["run"]["runId"], "inserted": len(ids), "replayed": 0, "eventIds": ids + [ids[0]]},
                    {"runId": value["run"]["runId"], "inserted": 1, "replayed": 0, "eventIds": ids},
                    {"runId": value["run"]["runId"], "inserted": len(ids), "replayed": 0, "eventIds": ids, "success": True}):
            with self.assertRaises(s.collector.ObservationError):
                s.validate_ack(value, 201, c.canonical(ack))

    def test_redirect_is_rejected_and_never_followed(self):
        self.store.enqueue(self.envelope())
        calls = []
        def redirect(*args):
            calls.append(args[0])
            return 302, b'{"location":"https://evil.example"}'
        result = s.send_round(self.store, c.DEFAULT_ENDPOINT, self.token(), transport=redirect)
        self.assertEqual([c.DEFAULT_ENDPOINT], calls)
        self.assertEqual({"UPLOAD_REJECTED_302": 1}, result["errors"])

    def test_http_timeout_two_seconds_and_no_redirect_proxy(self):
        connection = mock.Mock()
        connection.getresponse.return_value.status = 401
        connection.getresponse.return_value.read.return_value = b'{}'
        with mock.patch.object(s.http.client, "HTTPConnection", return_value=connection) as factory:
            s.post(c.DEFAULT_ENDPOINT, "K" * 48, b'{}')
        factory.assert_called_once_with("127.0.0.1", 9090, timeout=2)
        connection.request.assert_called_once()
        connection.close.assert_called_once()

    def test_absolute_deadline_interrupts_slow_response_and_restores_handler(self):
        # 用barrier觸發真SIGALRM，不以sleep/耗時斷言製造flaky timeout測試。
        reached = threading.Event()
        connection = mock.Mock()
        previous_handler = s.signal.getsignal(s.signal.SIGALRM)
        def slow_response():
            reached.set()
            threading.Event().wait(5)
        connection.getresponse.side_effect = slow_response
        def interrupt():
            if reached.wait(1):
                os.kill(os.getpid(), s.signal.SIGALRM)
        worker = threading.Thread(target=interrupt)
        worker.start()
        try:
            with mock.patch.object(s.http.client, "HTTPConnection", return_value=connection):
                with self.assertRaises(TimeoutError):
                    s.post(c.DEFAULT_ENDPOINT, "K" * 48, b'{}')
        finally:
            worker.join(1)
        self.assertEqual(previous_handler, s.signal.getsignal(s.signal.SIGALRM))
        self.assertEqual((0.0, 0.0), s.signal.getitimer(s.signal.ITIMER_REAL))
        connection.close.assert_called_once()

    def test_token_permission_and_symlink(self):
        path = self.token()
        path.chmod(0o644)
        with self.assertRaises(s.collector.ObservationError):
            s.read_token(path)
        path.chmod(0o600)
        link = self.home / "token-link"
        link.symlink_to(path)
        with self.assertRaises(s.collector.ObservationError):
            s.read_token(link)

    def test_invalid_endpoint_or_credentials_in_url(self):
        for endpoint in ("http://public.example:9090/api/srpp/ai-flow/events", "http://127.0.0.1:9090/api/srpp/ai-flow/events?token=x", "https://u:p@host:9090/api/srpp/ai-flow/events", "https://host:9090/api/srpp/ai-flow/events/child"):
            with self.assertRaises(s.collector.ObservationError):
                s.endpoint_parts(endpoint)

    def test_chunking_max_100_and_round_bounded(self):
        value = self.envelope()
        source = value["run"]["sourceRunId"]
        value["events"] = [c.event(source, str(n), n, "step-" + str(n), "OBSERVATION", None, "公開觀測", "只有公開操作摘要。") for n in range(450)]
        self.store.enqueue(value)
        self.assertEqual(5, len(list((self.store.root / "pending").glob("*.json"))))
        result = s.send_round(self.store, c.DEFAULT_ENDPOINT, self.token(), transport=self.ack, max_batches=3)
        self.assertEqual(3, result["ackedBatches"])
        self.assertEqual(2, result["pendingBatches"])

    def test_parent_chunks_uploaded_in_dependency_order(self):
        value = self.envelope()
        source = value["run"]["sourceRunId"]
        events = []
        for number in range(205):
            previous = [events[-1]["eventId"]] if events else []
            events.append(c.event(source, str(number), number, "step-" + str(number), "OBSERVATION", None,
                                  "公開觀測", "已觀測到時間接續。", parents=previous, relation="NEXT_OBSERVED" if previous else None))
        value["events"] = events
        self.store.enqueue(value)
        persisted = set()
        def atomic_dependency_server(_, __, body):
            batch = c.strict_json(body)
            ids = {item["eventId"] for item in batch["events"]}
            self.assertTrue(all(set(item["parentEventIds"]) <= persisted | ids for item in batch["events"]))
            persisted.update(ids)
            return self.ack(_, __, body)
        result = s.send_round(self.store, c.DEFAULT_ENDPOINT, self.token(), transport=atomic_dependency_server)
        self.assertEqual(3, result["ackedBatches"])
        self.assertEqual(205, len(persisted))

    def test_round_does_not_start_request_when_less_than_two_seconds_remain(self):
        value = self.envelope()
        source = value["run"]["sourceRunId"]
        value["events"] = [c.event(source, str(number), number, "step-" + str(number), "OBSERVATION", None,
                                   "公開觀測", "已觀測到公開事件。") for number in range(250)]
        self.store.enqueue(value)
        clock = [0]
        def delayed_ack(*args):
            clock[0] += 2.1
            return self.ack(*args)
        with mock.patch.object(s.time, "monotonic", side_effect=lambda: clock[0]):
            result = s.send_round(self.store, c.DEFAULT_ENDPOINT, self.token(), transport=delayed_ack, seconds=5)
        self.assertEqual(2, result["ackedBatches"])
        self.assertEqual(1, result["pendingBatches"])
        self.assertEqual(4200, result["wallMillis"])

    def test_round_rechecks_remaining_budget_after_spool_read(self):
        self.store.enqueue(self.envelope())
        clock = [0]
        original_validate = s.collector.validate_envelope
        def slow_validate(*args):
            original_validate(*args)
            clock[0] = 4
        with mock.patch.object(s.time, "monotonic", side_effect=lambda: clock[0]), mock.patch.object(s.collector, "validate_envelope", side_effect=slow_validate), mock.patch.object(s, "post") as transport:
            result = s.send_round(self.store, c.DEFAULT_ENDPOINT, self.token(), transport=transport, seconds=5)
        transport.assert_not_called()
        self.assertEqual(0, result["ackedBatches"])
        self.assertEqual(1, result["pendingBatches"])

    def test_sender_failure_stops_children_until_parent_retry(self):
        value = self.envelope()
        source = value["run"]["sourceRunId"]
        events = []
        for number in range(101):
            parents = [events[-1]["eventId"]] if events else []
            events.append(c.event(source, str(number), number, "step-" + str(number), "OBSERVATION", None,
                                  "公開觀測", "依來源順序接續。", parents=parents, relation="NEXT_OBSERVED" if parents else None))
        value["events"] = events
        self.store.enqueue(value)
        calls = []
        def reject(*args):
            calls.append(args)
            return 503, b'{}'
        failed = s.send_round(self.store, c.DEFAULT_ENDPOINT, self.token(), transport=reject)
        self.assertEqual(1, len(calls))
        self.assertEqual(2, failed["pendingBatches"])
        retried = s.send_round(self.store, c.DEFAULT_ENDPOINT, self.token(), transport=self.ack)
        self.assertEqual(2, retried["ackedBatches"])

    def test_unknown_duplicate_and_injection_fields_rejected(self):
        with self.assertRaises(c.ObservationError):
            c.strict_json('{"a":{"token":1,"token":2}}')
        value = self.envelope()
        value["run"]["ownerId"] = 1
        with self.assertRaises(c.ObservationError):
            self.store.enqueue(value)
        del value["run"]["ownerId"]
        for text in ("Authorization: Bearer SECRET", "帳號123", "100元", "<script>boom</script>", "private@example.com", "https://secret.example"):
            value["events"][0]["summary"] = text
            with self.assertRaises(c.ObservationError):
                self.store.enqueue(value)

    def test_cli_sensitive_amount_account_shell_and_private_reasoning_reject_before_state(self):
        sensitive = ["USD 1234", "TWD 1,234.50", "NT$2000", "$900", "1234 USD", "price=100.5", '"amount":1000',
                     "balance:7000", "holdings=2330", "account=ABC123", "成本：500", "金額=4000",
                     "curl --silent /api/assets/latest", "python3 scripts/private.py", "cmd=cat data.json",
                     "<analysis>internal step</analysis>", "private_reasoning: internal step", "思考鏈：內部推論"]
        for summary in sensitive:
            with self.subTest(summary=summary):
                value = self.envelope()
                value["events"][0]["summary"] = summary
                input_file = self.home / "producer-input.json"
                input_file.write_bytes(c.canonical(value))
                state_directory = self.home / "must-not-be-created"
                with mock.patch.object(c, "Store") as store_factory, mock.patch.object(c, "atomic_bytes") as writer, mock.patch.object(c.dt, "datetime", wraps=dt.datetime) as clock, contextlib.redirect_stdout(io.StringIO()):
                    clock.now.return_value = dt.datetime(2026, 10, 11, 9, tzinfo=c.TAIPEI)
                    self.assertEqual(1, c.cli(["--enqueue-json", str(input_file), "--state-dir", str(state_directory)]))
                    store_factory.assert_not_called()
                    writer.assert_not_called()
                self.assertFalse(state_directory.exists())

    def test_active_http_path_canonical_301_500_and_template_accept(self):
        for method in ("GET", "POST", "PUT", "PATCH", "DELETE"):
            for path in ("/api/" + "a" * 296, "/api/" + "a" * 495, "/api/assets/{id}/a._~-"):
                with self.subTest(method=method, length=len(path)):
                    value = self.envelope()
                    value["events"][0]["operation"] = {"method": method, "path": path}
                    c.validate_envelope(value, TODAY)

    def test_invalid_active_http_path_rejects_cli_before_state_and_direct_before_writes(self):
        invalid = ("/api/" + "a" * 496, "/api/assets?secret=PRIVATE", "/api/assets#PRIVATE",
                   "https://host/api/assets", "//host/api/assets", "/assets", "/api", "/api/", "/api//assets",
                   "/api/assets/", "/api/../assets", "/api/a..b", "/api/assets%2Fprivate", "/api/秘密")
        for path in invalid:
            with self.subTest(path=path):
                value = self.envelope()
                value["events"][0]["operation"] = {"method": "GET", "path": path}
                input_file = self.home / "producer-input.json"
                input_file.write_bytes(c.canonical(value))
                state_directory = self.home / "must-not-be-created"
                with mock.patch.object(c, "Store") as store_factory, mock.patch.object(c, "atomic_bytes") as writer, mock.patch.object(c.dt, "datetime", wraps=dt.datetime) as clock, contextlib.redirect_stdout(io.StringIO()):
                    clock.now.return_value = dt.datetime(2026, 10, 11, 9, tzinfo=c.TAIPEI)
                    self.assertEqual(1, c.cli(["--enqueue-json", str(input_file), "--state-dir", str(state_directory)]))
                    store_factory.assert_not_called()
                    writer.assert_not_called()
                self.assertFalse(state_directory.exists())
                with mock.patch.object(c, "atomic_bytes") as writer, mock.patch.object(self.store, "save") as save:
                    with self.assertRaises(c.ObservationError):
                        self.store.enqueue(value)
                    writer.assert_not_called()
                    save.assert_not_called()
        self.assertEqual([], list((self.store.root / "pending").glob("*.json")))

    def test_local_tool_paths_remain_closed_and_distinct_from_http_paths(self):
        for method in ("LOCAL", "TOOL"):
            for path in ("calendar", "policy", "validation", "render", "artifact", "tool"):
                value = self.envelope()
                value["events"][0]["operation"] = {"method": method, "path": path}
                c.validate_envelope(value, TODAY)
            for path in ("/api/assets/{id}", "private-command", "/api/" + "a" * 495):
                value = self.envelope()
                value["events"][0]["operation"] = {"method": method, "path": path}
                with mock.patch.object(c, "atomic_bytes") as writer:
                    with self.assertRaises(c.ObservationError):
                        self.store.enqueue(value)
                    writer.assert_not_called()

    def test_direct_enqueue_sensitive_summary_never_writes_payload_or_calls_save(self):
        value = self.envelope()
        value["events"][0]["summary"] = "USD 1234"
        with mock.patch.object(c, "atomic_bytes") as writer, mock.patch.object(self.store, "save") as save:
            with self.assertRaises(c.ObservationError):
                self.store.enqueue(value)
            writer.assert_not_called()
            save.assert_not_called()
        self.assertEqual([], list((self.store.root / "pending").glob("*.json")))

    def test_ordinary_public_summary_and_currency_labels_remain_allowed(self):
        value = self.envelope()
        value["events"][0]["summary"] = "已查詢 USD/TWD 資料來源；公開流程仍未確認。"
        self.assertGreater(self.store.enqueue(value), 0)

    def test_wrong_nested_field_types_rejected_before_spool(self):
        for key in ("consumer", "sourceKind", "automationId"):
            value = self.envelope()
            value["run"][key] = {"private": "CANARY"}
            with self.assertRaises(c.ObservationError):
                self.store.enqueue(value)
        for key in ("status", "eventKind", "reportedOutcome", "relation"):
            value = self.envelope()
            value["events"][0][key] = {"private": "CANARY"}
            with self.assertRaises(c.ObservationError):
                self.store.enqueue(value)

    def test_body_closed_nested_schema(self):
        value = self.envelope()
        value["events"][0]["operation"]["rawCommand"] = "secret"
        with self.assertRaises(c.ObservationError):
            self.store.enqueue(value)

    def test_disk_full_does_not_call_sender_and_remains_retryable(self):
        value = self.envelope()
        with mock.patch.object(c, "atomic_bytes", side_effect=OSError("disk full")), mock.patch.object(s, "post") as post:
            with self.assertRaises(OSError):
                self.store.enqueue(value)
            post.assert_not_called()
        self.assertGreater(self.store.enqueue(value), 0)

    def test_dry_run_does_not_create_state(self):
        path = self.home / "not-created"
        store = c.Store(path, TODAY, dry_run=True)
        store.enqueue(self.envelope())
        self.assertFalse(path.exists())


class RetentionClaudeInstallTests(Fixture):
    def test_today_89_90_future_dates(self):
        for offset in (0, 89):
            c.date_in_window((TODAY - dt.timedelta(days=offset)).isoformat(), TODAY)
        with self.assertRaisesRegex(c.ObservationError, "RETENTION_EXPIRED"):
            c.date_in_window((TODAY - dt.timedelta(days=90)).isoformat(), TODAY)
        with self.assertRaisesRegex(c.ObservationError, "INVALID_DATE"):
            c.date_in_window((TODAY + dt.timedelta(days=1)).isoformat(), TODAY)

    def test_retention_purges_own_spool_ack_and_state_not_source(self):
        value = self.envelope()
        self.store.enqueue(value)
        source_bytes = self.path.read_bytes()
        self.store.today = dt.date(2027, 1, 7)
        self.assertGreater(self.store.purge(), 0)
        self.assertEqual([], list((self.store.root / "pending").glob("*.json")))
        self.assertEqual([], list((self.store.root / "sources").glob("*.json")))
        self.assertEqual(source_bytes, self.path.read_bytes())
        with self.assertRaises(c.ObservationError):
            self.store.enqueue(value, replay=True)

    def test_taipei_midnight_epoch_ms(self):
        before = dt.datetime(2026, 10, 10, 15, 59, 59, tzinfo=dt.timezone.utc)
        after = before + dt.timedelta(seconds=1)
        self.assertEqual(dt.date(2026, 10, 10), c.epoch_ms(int(before.timestamp() * 1000)).date())
        self.assertEqual(dt.date(2026, 10, 11), c.epoch_ms(int(after.timestamp() * 1000)).date())

    def atomic_child(self, target, payload, *, kill=True):
        program = '''
import importlib.util,os,pathlib,signal,sys
sys.dont_write_bytecode=True
spec=importlib.util.spec_from_file_location("atomic_crash_fixture",sys.argv[1])
module=importlib.util.module_from_spec(spec);sys.modules[spec.name]=module;spec.loader.exec_module(module)
original_replace=module.os.replace
def stop_after_fsync(source,destination):
    if sys.argv[4]=="kill":os.kill(os.getpid(),signal.SIGKILL)
    print("READY",flush=True)
    sys.stdin.buffer.read(1)
    original_replace(source,destination)
module.os.replace=stop_after_fsync
module.atomic_bytes(pathlib.Path(sys.argv[2]),bytes.fromhex(sys.argv[3]))
'''
        env = dict(os.environ, PYTHONDONTWRITEBYTECODE="1")
        return subprocess.Popen([sys.executable, "-c", program, str(SCRIPTS / "collect-srpp-ai-flow.py"), str(target),
                                 payload.hex(), "kill" if kill else "wait"], stdin=subprocess.PIPE,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)

    def test_sigkill_fsynced_complete_and_partial_atomic_temps_are_reclaimed(self):
        target = self.store.root / "sources" / ("codex-" + "a" * 64 + ".json")
        for payload in (c.canonical({"reportDate": "2026-07-13", "events": ["公開流程"]}), b'{"reportDate":"2026-07-13","events":['):
            with self.subTest(complete=payload.endswith(b"}")):
                process = self.atomic_child(target, payload)
                _, stderr = process.communicate(timeout=5)
                self.assertEqual(-signal.SIGKILL, process.returncode, stderr.decode())
                temps = list(target.parent.glob("." + target.name + ".*.tmp"))
                self.assertEqual(1, len(temps))
                self.assertEqual(payload, temps[0].read_bytes())
                self.assertFalse(target.exists())
                self.assertEqual(1, self.store.purge())
                self.assertEqual([], list(target.parent.glob("." + target.name + ".*.tmp")))

    def test_active_atomic_writer_temp_is_not_deleted_and_other_writer_does_not_block(self):
        target = self.store.root / "sources" / ("codex-" + "b" * 64 + ".json")
        payload = c.canonical({"reportDate": "2026-10-08", "events": ["公開流程"]})
        process = self.atomic_child(target, payload, kill=False)
        try:
            ready, _, _ = select.select([process.stdout], [], [], 5)
            self.assertTrue(ready)
            self.assertEqual(b"READY\n", process.stdout.readline())
            temps = list(target.parent.glob("." + target.name + ".*.tmp"))
            self.assertEqual(1, len(temps))
            self.assertEqual(0, self.store.purge())
            self.assertTrue(temps[0].exists())
            with self.assertRaisesRegex(c.ObservationError, "LOCAL_WRITER_BUSY"):
                c.atomic_bytes(target, payload)
            process.stdin.write(b"x")
            process.stdin.flush()
            _, stderr = process.communicate(timeout=5)
            self.assertEqual(0, process.returncode, stderr.decode())
            self.assertEqual(payload, target.read_bytes())
            self.assertEqual(0, self.store.purge())
            self.assertTrue(target.exists())
        finally:
            if process.poll() is None:
                process.kill()
                process.communicate(timeout=5)

    def test_temp_cleanup_only_owns_exact_atomic_names_and_own_directories(self):
        names = ["unknown.json", ".unknown.json." + "0" * 32 + ".tmp", ".collector.token." + "1" * 32 + ".tmp",
                 ".collector-receipt.json.not-a-uuid.tmp", "collector.token"]
        paths = []
        for name in names:
            path = self.store.root / name
            path.write_bytes(c.canonical({"reportDate": "2026-07-13"}))
            paths.append(path)
        unknown_source = self.store.root / "sources/unknown.json"
        unknown_source.write_bytes(c.canonical({"reportDate": "2026-07-13"}))
        paths.append(unknown_source)
        original_source = self.sessions / (".collector-receipt.json." + "f" * 32 + ".tmp")
        original_source.write_bytes(b"ORIGINAL_SOURCE")
        paths.append(original_source)
        managed = self.store.root / (".collector-receipt.json." + "2" * 32 + ".tmp")
        managed.write_bytes(b'{"runs":[')
        self.assertEqual(1, self.store.purge())
        self.assertFalse(managed.exists())
        self.assertTrue(all(path.exists() for path in paths))

    def test_cleanup_rejects_state_directory_symlink_before_any_source_write(self):
        source_directory = self.store.root / "sources"
        source_directory.rmdir()
        source_directory.symlink_to(self.sessions)
        before = self.path.read_bytes()
        with self.assertRaisesRegex(c.ObservationError, "STATE_SYMLINK"):
            self.store.purge()
        self.assertEqual(before, self.path.read_bytes())
        self.assertFalse((self.sessions / ".ai-flow-atomic.lock").exists())

    def test_uppercase_valid_run_uuid_spool_is_also_owned_by_cleanup(self):
        value = self.envelope()
        value["run"]["runId"] = value["run"]["runId"].upper()
        self.store.enqueue(value)
        self.store.today = dt.date(2027, 1, 7)
        self.assertGreater(self.store.purge(), 0)
        self.assertEqual([], list((self.store.root / "pending").glob("*.json")))

    def test_final_retention_uses_original_date_even_with_new_mtime(self):
        expired = self.store.root / "sources" / ("codex-" + "c" * 64 + ".json")
        retained = self.store.root / "sources" / ("codex-" + "d" * 64 + ".json")
        cutoff = TODAY - dt.timedelta(days=89)
        c.atomic_json(expired, {"reportDate": (cutoff - dt.timedelta(days=1)).isoformat()})
        c.atomic_json(retained, {"reportDate": cutoff.isoformat()})
        os.utime(expired, None)
        os.utime(retained, (1, 1))
        self.assertEqual(1, self.store.purge())
        self.assertFalse(expired.exists())
        self.assertTrue(retained.exists())

    def test_receipt_with_expired_original_run_is_deleted_even_if_receipt_is_fresh(self):
        receipt = self.store.root / "collector-receipt.json"
        c.atomic_json(receipt, {"reportDate": TODAY.isoformat(), "runs": [{"reportDate": "2026-07-13", "runId": "old"}]})
        self.assertEqual(1, self.store.purge())
        self.assertFalse(receipt.exists())

    def test_crash_cleanup_is_bounded_and_next_round_resumes(self):
        directory = self.store.root / "sources"
        for number in range(600):
            path = directory / (".codex-" + "e" * 64 + f".json.{number:032x}.tmp")
            path.write_bytes(b"partial")
        first = self.store.purge()
        self.assertLessEqual(first, 512)
        self.assertGreater(len(list(directory.glob("*.tmp"))), 0)
        self.store.purge()
        self.assertEqual([], list(directory.glob("*.tmp")))

    def artifact(self, name="Claude-result", **extra):
        root = self.home / "Project/SRPP/data/output/daily_runs/2026-10-08" / name
        root.mkdir(parents=True)
        value = {"consumer": "Claude", "trading_date": "2026-10-08", "slot": "1140", "status": "COMPLETED", "run_id": name,
                 "holdings": "PRIVATE_HOLDINGS_CANARY", "drafts": "PRIVATE_MIME_CANARY"}
        value.update(extra)
        (root / "result.json").write_bytes(c.canonical(value))
        return root

    def test_claude_selfreport_unlinked_not_verified_or_raw(self):
        self.artifact()
        runs = self.collector.claude_artifacts(dt.date(2026, 10, 8), c.ReadBudget())
        self.assertEqual(1, len(runs))
        self.assertEqual("11:40", runs[0]["run"]["reportSlot"])
        self.assertEqual("COMPLETED", runs[0]["events"][0]["reportedOutcome"])
        self.assertEqual("UNLINKED", runs[0]["events"][-1]["reasonCode"])
        self.assertNotIn("CANARY", c.canonical(runs).decode())

    def test_claude_consumer_date_mismatch_no_filename_join(self):
        self.artifact(consumer="Codex")
        self.artifact("misdated", trading_date="2026-10-07")
        self.assertEqual([], self.collector.claude_artifacts(dt.date(2026, 10, 8), c.ReadBudget()))

    def test_claude_timing_only_explicit_identity_links(self):
        root = self.artifact()
        (root / "timing.json").write_bytes(c.canonical({"run_id": "wrong", "trading_date": "2026-10-08"}))
        first = self.collector.claude_artifacts(dt.date(2026, 10, 8), c.ReadBudget())[0]
        self.assertEqual(2, len(first["events"]))
        (root / "timing.json").write_bytes(c.canonical({"run_id": "Claude-result", "trading_date": "2026-10-08"}))
        second = self.collector.claude_artifacts(dt.date(2026, 10, 8), c.ReadBudget())[0]
        self.assertEqual(3, len(second["events"]))
        self.assertEqual(first["run"], second["run"])

    def test_claude_metadata_no_raw_honest_missing_noon_slot(self):
        root = self.home / "Library/Application Support/Claude/local-agent-mode-sessions/a/b"
        root.mkdir(parents=True)
        for task in ("srpp-0905-claude", "srpp-1150-claude"):
            (root / ("local_" + task + ".json")).write_bytes(c.canonical({"scheduledTaskId": task, "cliSessionId": "session-" + task,
                                                                         "createdAt": 1791421500000, "systemPrompt": "PRIVATE_PROMPT_CANARY"}))
        runs = self.collector.claude_metadata(dt.date(2026, 10, 8), c.ReadBudget())
        self.assertEqual(1, len(runs))
        self.assertIsNone(runs[0]["run"]["startedAt"])
        self.assertEqual("RAW_TRACE_UNAVAILABLE", runs[0]["events"][-1]["reasonCode"])
        self.assertNotIn("CANARY", c.canonical(runs).decode())

    def test_claude_identity_metadata_has_no_terminal_receipt_or_lifecycle_event(self):
        envelope, _ = self.collector.claude_metadata_envelope({"scheduledTaskId": "srpp-0905-claude", "cliSessionId": "observed-session",
                                                               "createdAt": 1791421500000})
        identity = next(event for event in envelope["events"] if event["stepId"] == "session-identity")
        self.assertEqual([], identity["evidenceRefs"])
        self.assertEqual("OBSERVATION", identity["eventKind"])
        self.assertIsNone(envelope["run"]["startedAt"])
        self.assertTrue(all(event["eventKind"] not in {"START", "END", "TERMINAL"} for event in envelope["events"]))
        self.assertFalse(any(ref["role"] == "APP_TERMINAL" for event in envelope["events"] for ref in event["evidenceRefs"]))

    def test_claude_metadata_fair_cursor_reaches_later_sources(self):
        root = self.home / "Library/Application Support/Claude/local-agent-mode-sessions/a/b"
        root.mkdir(parents=True)
        for number in range(4):
            (root / f"local_{number}.json").write_bytes(c.canonical({"scheduledTaskId": "srpp-0905-claude", "cliSessionId": f"session-{number}",
                                                                    "createdAt": 1791421500000, "systemPrompt": "x" * 600}))
        collected = set()
        for _ in range(4):
            budget = c.ReadBudget(remaining=1000, per_run=1000, seconds=5, runs=1)
            runs = self.collector.claude_metadata(dt.date(2026, 10, 8), budget)
            collected.update(run["run"]["sourceRunId"] for run in runs)
            self.assertLessEqual(budget.consumed, 1000)
        self.assertEqual(4, len(collected))

    def test_install_configuration_only_background_no_investment(self):
        job = i.job_configuration(i.MAIN, pathlib.Path("/usr/bin/python3"), self.store.root, self.store.root / "collector.token")
        decoded = plistlib.loads(plistlib.dumps(job))
        self.assertTrue(i.owned_job(decoded))
        self.assertEqual("Background", decoded["ProcessType"])
        self.assertTrue(decoded["LowPriorityIO"])
        self.assertEqual(60, decoded["ThrottleInterval"])
        self.assertNotIn("SRPP/", json.dumps(decoded))
        decoded["ProgramArguments"][1] = "/tmp/unowned.py"
        self.assertFalse(i.owned_job(decoded))

    def test_schedule_producer_cli_only_local_enqueue(self):
        value = self.envelope()
        value["run"]["sourceKind"] = "SCHEDULE_EVENTS"
        path = self.home / "producer.json"
        path.write_bytes(c.canonical(value))
        with mock.patch.object(c.dt, "datetime", wraps=dt.datetime) as clock, mock.patch("http.client.HTTPConnection") as network, contextlib.redirect_stdout(io.StringIO()):
            clock.now.return_value = dt.datetime(2026, 10, 11, 9, tzinfo=c.TAIPEI)
            self.assertEqual(0, c.cli(["--enqueue-json", str(path), "--state-dir", str(self.store.root)]))
            network.assert_not_called()

    def test_db_export_excludes_only_ai_table_data(self):
        text = (SCRIPTS / "db-export.sh").read_text()
        self.assertIn("--exclude-table-data=public.ai_flow_run", text)
        self.assertIn("--exclude-table-data=public.ai_flow_event", text)
        self.assertNotIn("--exclude-table=", text)


if __name__ == "__main__":
    unittest.main()
