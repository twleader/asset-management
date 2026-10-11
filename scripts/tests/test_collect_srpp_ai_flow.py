"""Task492隔離測試；不接production、不讀真金融內容、不安裝OS工作。"""
import contextlib
import datetime as dt
import importlib.util
import io
import json
import os
import pathlib
import plistlib
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
