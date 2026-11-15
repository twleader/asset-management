"""Task 592 isolated tests. No real credentials, accounts or inference requests."""
import copy
import base64
import importlib.util
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from unittest import mock

ROOT = pathlib.Path(__file__).resolve().parents[2]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


b = load("srpp_ai_bridge_test", ROOT/"scripts/srpp-local-ai-bridge.py")
i = load("srpp_ai_install_test", ROOT/"scripts/install-srpp-local-ai-bridge.py")


def limits():
    row = {"planType": "pro", "credits": {"hasCredits": False, "unlimited": False, "balance": "0"},
           "primary": {"usedPercent": 20}, "secondary": None}
    return {"ordinaryUsageAllowed": True, "rateLimits": row, "rateLimitsByLimitId": {"codex": copy.deepcopy(row)}}


def model():
    return {"id": "test-model", "label": "Test Model", "efforts": ["low", "high"], "defaultEffort": "low"}


class Fixture(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = pathlib.Path(self.temp.name)
        self.path.chmod(0o700)
        self.registry = self.path/"registry.json"
        self.tools = [{"id": "test", "label": "Test", "adapter": "claude_cli", "executable": "/test/claude", "models": [model()]}]
        self.save()
        self.bridge = b.Bridge(self.registry, self.path)

    def tearDown(self):
        self.temp.cleanup()

    def save(self):
        self.registry.write_bytes(b.encode({"schemaVersion": 1, "tools": self.tools}))
        self.registry.chmod(0o600)

    def catalog(self, **updates):
        tool = {"id": "test", "label": "Test", "adapter": "claude_cli", "billingMode": "SUBSCRIPTION",
                "available": True, "code": None, "modelSource": "CONFIGURED", "models": [model()]}
        tool.update(updates)
        return {"schemaVersion": 1, "catalogRevision": "a"*64, "tools": [tool]}

    def selection(self):
        return {"toolId": "test", "model": "test-model", "effort": "low", "catalogRevision": "a"*64}

    def body(self):
        return {"selection": self.selection(), "result": {"result": {"baseline": {"successProbability": .6}, "scenario": {"successProbability": .7}, "warnings": ["fixture"]}}}

    def assert_code(self, code, call, *args, **kwargs):
        with self.assertRaises(b.Problem) as found:
            call(*args, **kwargs)
        self.assertEqual(code, found.exception.code)
        return found.exception


class BillingTests(Fixture):
    def test_codex_only_personal_subscription_no_credits(self):
        b.Bridge.check_codex_billing(limits())
        for field in ("hasCredits", "unlimited"):
            value = limits()
            value["rateLimits"]["credits"][field] = True
            self.assert_code("AI_PAID_CREDITS_ENABLED", b.Bridge.check_codex_billing, value)
        value = limits()
        value["rateLimits"]["planType"] = "enterprise"
        self.assert_code("AI_SUBSCRIPTION_REQUIRED", b.Bridge.check_codex_billing, value)

    def test_codex_unknown_or_exhausted_fails_closed(self):
        for mutation in [lambda d: d.pop("ordinaryUsageAllowed"),
                         lambda d: d["rateLimits"].pop("credits"),
                         lambda d: d["rateLimits"]["credits"].update(hasCredits=None),
                         lambda d: d["rateLimits"]["primary"].update(usedPercent=100)]:
            value = limits()
            mutation(value)
            self.assert_code("AI_BILLING_UNVERIFIED", b.Bridge.check_codex_billing, value)

    def test_codex_secondary_billing_pool_credits_rejected(self):
        value = limits()
        value["rateLimitsByLimitId"]["codex"]["credits"]["hasCredits"] = True
        self.assert_code("AI_PAID_CREDITS_ENABLED", b.Bridge.check_codex_billing, value)

    def test_codex_api_key_or_unknown_auth_rejected(self):
        home = self.path/"normal-home"
        (home/".codex").mkdir(parents=True, mode=0o700)
        auth = home/".codex/auth.json"
        auth.write_bytes(b.encode({"auth_mode": "apikey", "OPENAI_API_KEY": "TEST_SECRET"}))
        auth.chmod(0o600)
        with mock.patch.object(b.pathlib.Path, "home", return_value=home):
            self.assert_code("AI_SUBSCRIPTION_REQUIRED", b.read_codex_auth_snapshot)

    def test_codex_rejected_billing_never_calls_inference(self):
        self.tools = [{"id": "test", "label": "Test", "adapter": "codex_cli", "executable": "/test/codex"}]
        self.save()
        paid = limits()
        paid["rateLimits"]["credits"]["hasCredits"] = True
        data = {"data": [{"model": "test-model", "id": "test-model", "displayName": "Test Model",
                          "supportedReasoningEfforts": [{"reasoningEffort": "low"}], "defaultReasoningEffort": "low"}]}
        with mock.patch.object(self.bridge, "codex_rpc", return_value=(paid, data)), mock.patch.object(self.bridge, "infer") as infer:
            self.assert_code("AI_PAID_CREDITS_ENABLED", self.bridge.execute_inline, self.body())
            infer.assert_not_called()

    def test_claude_api_key_status_no_billing_request(self):
        with mock.patch.object(b, "run_process", return_value=b.encode({"loggedIn": True, "authMethod": "api_key", "subscriptionType": "pro"})), mock.patch.object(b, "http_json") as http:
            self.assert_code("AI_SUBSCRIPTION_REQUIRED", self.bridge.claude_proof, self.tools[0])
            http.assert_not_called()

    def test_claude_unknown_extra_usage_or_enabled_no_inference(self):
        auth = {"loggedIn": True, "authMethod": "claude.ai", "subscriptionType": "pro"}
        credential = {"claudeAiOauth": {"accessToken": "TEST_TOKEN"}}
        for usage, code in [({}, "AI_BILLING_UNVERIFIED"), ({"extra_usage": {"is_enabled": True}}, "AI_EXTRA_USAGE_ENABLED"),
                            ({"extra_usage": {"is_enabled": None}}, "AI_BILLING_UNVERIFIED")]:
            with mock.patch.object(b, "run_process", side_effect=[b.encode(auth), b.encode(credential)]), mock.patch.object(b, "http_json", return_value=usage), mock.patch.object(self.bridge, "infer") as infer:
                self.assert_code(code, self.bridge.execute_inline, self.body())
                infer.assert_not_called()

    def test_claude_disabled_extra_usage_passes(self):
        auth = {"loggedIn": True, "authMethod": "claude.ai", "subscriptionType": "pro"}
        with mock.patch.object(b, "run_process", side_effect=[b.encode(auth), b.encode({"claudeAiOauth": {"accessToken": "TEST_TOKEN"}})]), mock.patch.object(b, "http_json", return_value={"extra_usage": {"is_enabled": False}, "spend": {"enabled": False}}):
            self.bridge.claude_proof(self.tools[0])

    def test_environment_whitelist_removes_keys_helpers_proxies(self):
        with mock.patch.dict(os.environ, {"ANTHROPIC_API_KEY": "SECRET", "OPENAI_API_KEY": "SECRET", "HTTPS_PROXY": "SECRET",
                                         "CLAUDE_CODE_USE_BEDROCK": "1", "ANTHROPIC_BASE_URL": "https://remote", "CODEX_HOME": "/custom"}):
            env = b.clean_environment()
        for field in ("ANTHROPIC_API_KEY", "OPENAI_API_KEY", "HTTPS_PROXY", "ANTHROPIC_BASE_URL", "CLAUDE_CODE_USE_BEDROCK", "CODEX_HOME"):
            self.assertNotIn(field, env)


class ContractTests(Fixture):
    def test_closed_selection_and_stale_catalog(self):
        for field in ("toolId", "model", "effort", "catalogRevision"):
            value = self.selection()
            value.pop(field)
            self.assert_code("AI_INVALID_SELECTION", self.bridge.validate_selection, value, self.catalog())
        value = self.selection()
        value["command"] = "rm -rf /"
        self.assert_code("AI_INVALID_SELECTION", self.bridge.validate_selection, value, self.catalog())
        value = self.selection()
        value["catalogRevision"] = "b"*64
        self.assert_code("AI_CATALOG_STALE", self.bridge.validate_selection, value, self.catalog())

    def test_invalid_combination_and_unavailable(self):
        for field, value in [("toolId", "unknown"), ("model", "unknown"), ("effort", "max"), ("effort", None)]:
            selection = self.selection()
            selection[field] = value
            self.assert_code("AI_INVALID_SELECTION", self.bridge.validate_selection, selection, self.catalog())
        self.assert_code("AI_EXTRA_USAGE_ENABLED", self.bridge.validate_selection, self.selection(), self.catalog(available=False, code="AI_EXTRA_USAGE_ENABLED"))

    def test_no_effort_requires_null(self):
        catalog = self.catalog()
        catalog["tools"][0]["models"][0].update(efforts=[], defaultEffort=None)
        selection = self.selection()
        selection["effort"] = None
        self.bridge.validate_selection(selection, catalog)
        selection["effort"] = "low"
        self.assert_code("AI_INVALID_SELECTION", self.bridge.validate_selection, selection, catalog)

    def test_catalog_revision_does_not_depend_on_availability(self):
        available = self.catalog()["tools"][0]
        unavailable = {**available, "available": False, "code": "AI_BILLING_UNVERIFIED"}
        with mock.patch.object(self.bridge, "probe", return_value=available):
            first = self.bridge.capabilities(fresh=True)
        with mock.patch.object(self.bridge, "probe", return_value=unavailable):
            second = self.bridge.capabilities(fresh=True)
        self.assertEqual(first["catalogRevision"], second["catalogRevision"])
        self.assertFalse(second["tools"][0]["available"])

    def test_success_receipt_requested_only_and_immutable_numbers(self):
        body = self.body()
        before = copy.deepcopy(body)
        with mock.patch.object(self.bridge, "probe", return_value=self.catalog()["tools"][0]), mock.patch.object(self.bridge, "infer", return_value=("情境文字", None)):
            result = self.bridge.execute_inline(body)
        self.assertEqual(before, body)
        self.assertEqual("REQUESTED_ONLY", result["execution"]["modelEvidence"])
        self.assertIsNone(result["execution"]["actualModel"])
        self.assertEqual("test-model", result["execution"]["requestedModel"])
        self.assertEqual("SUCCEEDED", result["execution"]["status"])

    def test_failed_execution_receipt_and_no_fallback(self):
        with mock.patch.object(self.bridge, "probe", return_value=self.catalog()["tools"][0]), mock.patch.object(self.bridge, "infer", side_effect=b.Problem("AI_MODEL_UNAVAILABLE")) as infer:
            failure = self.assert_code("AI_MODEL_UNAVAILABLE", self.bridge.execute_inline, self.body())
        infer.assert_called_once()
        self.assertEqual("FAILED", failure.execution["status"])

    def test_provider_reported_model_is_separate(self):
        with mock.patch.object(self.bridge, "probe", return_value=self.catalog()["tools"][0]), mock.patch.object(self.bridge, "infer", return_value=("文字", "actual-model")):
            result = self.bridge.execute_inline(self.body())
        self.assertEqual("PROVIDER_REPORTED", result["execution"]["modelEvidence"])
        self.assertEqual("actual-model", result["execution"]["actualModel"])

    def test_busy_does_not_start_process(self):
        self.bridge.lock.acquire()
        try:
            with mock.patch.object(self.bridge, "capabilities", return_value=self.catalog()), mock.patch.object(b.subprocess, "Popen") as popen:
                self.assert_code("AI_BUSY", self.bridge.execute, self.body())
                popen.assert_not_called()
        finally:
            self.bridge.lock.release()

    def test_worker_timeout_kills_process_group_releases_slot(self):
        process = mock.Mock(pid=12345, returncode=None)
        process.communicate.side_effect = subprocess.TimeoutExpired("fixture", 120)
        with mock.patch.object(self.bridge, "capabilities", return_value=self.catalog()), mock.patch.object(b.subprocess, "Popen", return_value=process), mock.patch.object(b.os, "killpg") as killpg:
            self.assert_code("AI_TIMEOUT", self.bridge.execute, self.body())
        killpg.assert_called_once_with(12345, b.signal.SIGKILL)
        self.assertTrue(self.bridge.lock.acquire(blocking=False))
        self.bridge.lock.release()

    def test_worker_refuses_registry_changed_after_parent_validation(self):
        with mock.patch.object(self.bridge, "infer") as infer:
            self.assert_code("AI_CATALOG_STALE", self.bridge.execute_inline, self.body(), "old-hash")
            infer.assert_not_called()

    def test_cli_arguments_disable_customization_and_never_use_bare(self):
        response = b.encode({"result": "文字", "is_error": False})
        with mock.patch.object(b, "run_process", return_value=response) as run:
            self.bridge.infer(self.tools[0], self.selection(), "提示")
        args, kwargs = run.call_args
        argv = args[0]
        self.assertIn("--safe-mode", argv)
        self.assertIn("--strict-mcp-config", argv)
        self.assertIn("--disable-slash-commands", argv)
        self.assertEqual("", argv[argv.index("--tools")+1])
        self.assertNotIn("--bare", argv)
        self.assertNotIn("--fallback-model", argv)
        opts = " ".join(self.bridge.codex_options())
        self.assertIn('forced_login_method="chatgpt"', opts)
        self.assertIn("features.shell_tool=false", opts)
        self.assertIn("features.fast_mode=false", opts)

    def codex_event_stream(self, diagnostic=None, ending="turn.completed", item_type="agent_message"):
        rows = [{"type": "thread.started", "thread_id": "fixture"}]
        if diagnostic is not None:
            rows.append({"type": "item.completed", "item": {"id": "warning", "type": "error", "message": diagnostic}})
        rows += [{"type": "turn.started"}, {"type": "item.completed", "item": {"id": "text", "type": item_type, "text": "合成文字"}}, {"type": ending}]
        return b"\n".join(b.encode(row) for row in rows)

    def test_codex_known_legacy_notice_and_completed_text_accepted(self):
        notice = "`[features].codex_hooks` is deprecated. Use `[features].hooks` instead."
        self.bridge.validate_codex_events(self.codex_event_stream(notice))
        self.bridge.validate_codex_events(self.codex_event_stream())
        options = " ".join(self.bridge.codex_options())
        self.assertNotIn("features.codex_hooks=", options)
        self.assertIn("suppress_unstable_features_warning=true", options)

    def test_codex_error_and_failed_turn_never_accepted(self):
        known = "`[features].codex_hooks` is deprecated. Use `[features].hooks` instead."
        self.assert_code("AI_EXECUTION_FAILED", self.bridge.validate_codex_events, self.codex_event_stream(known, ending="turn.failed"))
        self.assert_code("AI_EXECUTION_FAILED", self.bridge.validate_codex_events, self.codex_event_stream("Unknown config: security policy did not apply"))
        self.assert_code("AI_EXECUTION_FAILED", self.bridge.validate_codex_events, self.codex_event_stream(ending="error"))

    def test_codex_unknown_action_or_real_tools_rejected(self):
        for kind in ("command_execution", "file_change", "mcp_tool_call", "unknown_action"):
            self.assert_code("AI_TOOL_USE_FORBIDDEN", self.bridge.validate_codex_events, self.codex_event_stream(item_type=kind))
        self.assert_code("AI_EXECUTION_FAILED", self.bridge.validate_codex_events, self.codex_event_stream()+b'\n{"type":"unknown_action"}')

    def test_codex_text_without_completed_turn_rejected(self):
        events = self.codex_event_stream().splitlines()
        self.assert_code("AI_EXECUTION_FAILED", self.bridge.validate_codex_events, b"\n".join(events[:-1]))

    def test_real_sanitized_codex_disabled_code_mode_fixture(self):
        raw = (ROOT/"scripts/tests/fixtures/srpp_codex_exec_fail_closed.jsonl").read_bytes()
        self.bridge.validate_codex_events(raw)
        # A weaker mode or an unrelated host/config failure is not equivalent
        # to the intentional code execution refusal proven by this fixture.
        for altered in (raw.replace(b"Code mode will fail closed", b"Falling back to direct tools"),
                        raw.replace(b"code-mode host is disabled", b"failed to spawn code-mode host"),
                        raw.replace(b'"turn.completed"', b'"turn.failed"')):
            self.assert_code("AI_EXECUTION_FAILED", self.bridge.validate_codex_events, altered)

    def test_projection_omits_ledgers_without_calculating(self):
        body = self.body()["result"]
        body["result"]["baseline"]["assumptions"] = {"cashflowSchedule": {"secret": "not-needed"}, "protectedReserveRealTwd": "123456.78"}
        projected = b.project_result(body)
        self.assertNotIn("cashflowSchedule", projected["baseline"]["assumptions"])
        self.assertEqual("123456.78", projected["baseline"]["assumptions"]["protectedReserveRealTwd"])

    def test_duplicate_json_nonfinite_and_unsafe_registry(self):
        for raw in [b'{"a":1,"a":2}', b'{"a":NaN}']:
            self.assert_code("AI_INVALID_REQUEST", b.decode, raw)
        self.registry.chmod(0o644)
        self.assert_code("AI_TOOL_UNAVAILABLE", b.load_registry, self.registry)


class LocalTests(Fixture):
    def test_remote_registry_hosts_and_urls_rejected(self):
        for host in ("localhost", "example.com", "192.168.1.1", "https://127.0.0.1"):
            self.tools = [{"id": "local", "label": "Local", "adapter": "ollama", "host": host, "port": 11434}]
            self.save()
            self.assert_code("AI_REMOTE_MODEL_FORBIDDEN", b.load_registry, self.registry)

    def test_cloud_and_remote_models_filtered(self):
        tool = {"adapter": "ollama", "host": "127.0.0.1", "port": 11434}
        rows = {"models": [{"name": "gemma4:31b"}, {"name": "model:cloud"}, {"name": "proxy", "remote_host": "remote"}]}
        with mock.patch.object(b, "http_json", return_value=rows):
            models = self.bridge.local_models(tool)
        self.assertEqual(["gemma4:31b"], [row["id"] for row in models])
        self.assertEqual([], models[0]["efforts"])

    def test_ollama_remote_metadata_rejected_before_chat(self):
        with mock.patch.object(b, "http_json", return_value={"remote_host": "https://remote"}) as http:
            self.assert_code("AI_REMOTE_MODEL_FORBIDDEN", self.bridge.infer, {"adapter": "ollama", "host": "127.0.0.1", "port": 11434}, {"model": "gemma", "effort": None}, "test")
        http.assert_called_once()

    def test_local_openai_requires_native_local_inventory(self):
        rows = {"models": [{"key": "local", "type": "llm", "format": "gguf"}, {"key": "cloud", "type": "llm", "format": "gguf"}, {"key": "unknown", "type": "llm"}]}
        with mock.patch.object(b, "http_json", return_value=rows):
            models = self.bridge.local_models({"adapter": "local_openai", "host": "127.0.0.1", "port": 1234})
        self.assertEqual(["local"], [row["id"] for row in models])

    def test_redirect_never_followed(self):
        self.assert_code("AI_REMOTE_MODEL_FORBIDDEN", b.NoRedirect().redirect_request, None, None, None, None, None, None)


class HttpTests(Fixture):
    def setUp(self):
        super().setUp()
        self.server = b.Server(("127.0.0.1", 0), self.bridge, "T"*48)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.url = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        super().tearDown()

    def test_auth_before_catalog_probe(self):
        with mock.patch.object(self.bridge, "capabilities") as capabilities:
            with self.assertRaises(urllib.error.HTTPError) as error:
                urllib.request.urlopen(self.url+"/capabilities", timeout=2)
        self.assertEqual(401, error.exception.code)
        error.exception.close()
        capabilities.assert_not_called()

    def test_authorized_catalog_and_exact_path(self):
        with mock.patch.object(self.bridge, "capabilities", return_value=self.catalog()):
            request = urllib.request.Request(self.url+"/capabilities", headers={"Authorization": "Bearer "+"T"*48})
            with urllib.request.urlopen(request, timeout=2) as response:
                self.assertEqual(self.catalog(), json.load(response))
            request = urllib.request.Request(self.url+"/capabilities?token=secret", headers={"Authorization": "Bearer "+"T"*48})
            with self.assertRaises(urllib.error.HTTPError) as error:
                urllib.request.urlopen(request, timeout=2)
            self.assertEqual(404, error.exception.code)
            error.exception.close()

    def test_oversized_body_rejected_before_execution(self):
        request = urllib.request.Request(self.url+"/interpret", data=b"{}", headers={"Authorization": "Bearer "+"T"*48, "Content-Length": str(b.MAX_BODY+1)})
        with mock.patch.object(self.bridge, "execute") as execute:
            with self.assertRaises(urllib.error.HTTPError) as error:
                urllib.request.urlopen(request, timeout=2)
        self.assertEqual(400, error.exception.code)
        error.exception.close()
        execute.assert_not_called()


class CodexAuthSyncTests(Fixture):
    def setUp(self):
        super().setUp()
        self.normal_home = self.path/"normal-home"
        self.source_directory = self.normal_home/".codex"
        self.source_directory.mkdir(parents=True, mode=0o700)
        self.source = self.source_directory/"auth.json"
        self.home_patch = mock.patch.object(b.pathlib.Path, "home", return_value=self.normal_home)
        self.home_patch.start()
        self.addCleanup(self.home_patch.stop)
        self.write_source()

    def token(self, account="fixture-account-a", extra=None):
        claims = {"exp": int(time.time())+3600, "https://api.openai.com/auth": {"chatgpt_account_id": account}}
        claims.update(extra or {})
        part = lambda value:base64.urlsafe_b64encode(json.dumps(value).encode()).decode().rstrip("=")
        return part({"alg": "RS256"})+"."+part(claims)+".fixture-signature"

    def write_source(self, account="fixture-account-a", extra_claims=None, **updates):
        value = {"auth_mode": "chatgpt", "OPENAI_API_KEY": None,
                 "tokens": {"access_token": self.token(account, extra_claims), "account_id": account,
                            "refresh_token": "FIXTURE_REFRESH_SECRET", "id_token": "FIXTURE_ORIGINAL_ID_TOKEN",
                            "oauth_provenance": {"issuer": "fixture-authority", "client_id": "fixture"}},
                 "last_refresh": "2026-01-01T00:00:00Z", "unrelated": "DO_NOT_COPY"}
        value.update(updates)
        self.source.write_bytes(b.encode(value))
        self.source.chmod(0o600)
        return value

    def codex_catalog(self):
        return {"data": [{"id": "test-model", "model": "test-model", "displayName": "Test Model",
                          "supportedReasoningEfforts": [{"reasoningEffort": "low"}], "defaultReasoningEffort": "low"}]}

    def fake_rpc_cli(self, mode="success", error=None):
        executable = self.path/"codex-rpc-fixture"
        recorded = self.path/"rpc-projection-metadata.json"
        program = ("#!"+sys.executable+"\nimport os,sys,json,pathlib,time\n"
                   +"root=pathlib.Path(os.environ['CODEX_HOME'])\n"
                   +"a=json.loads((root/'auth.json').read_text())\n"
                   +f"pathlib.Path({str(recorded)!r}).write_text(json.dumps({{'mode':a['auth_mode'],'refreshPresent':bool(a['tokens']['refresh_token']),'sameJwt':a['tokens']['access_token']==a['tokens']['id_token']}}))\n"
                   +"def emit(v): print(json.dumps(v),flush=True)\n")
        if mode == "timeout":
            program += "time.sleep(30)\n"
        elif mode == "invalid":
            program += "print('not-json',flush=True);time.sleep(30)\n"
        elif mode == "oversize":
            program += f"sys.stdout.write('x'*{b.MAX_BODY+1});sys.stdout.flush();time.sleep(30)\n"
        else:
            program += "for line in sys.stdin:\n request=json.loads(line)\n ident=request.get('id')\n"
            program += " if ident==1:\n  emit({'id':1,'result':{}})\n"
            if mode == "duplicate-init":
                program += "  emit({'id':1,'result':{}})\n"
            if mode == "refresh":
                program += "  emit({'id':'refresh-fixture','method':'account/chatgptAuthTokens/refresh','params':{}})\n"
            if mode == "error":
                program += f" elif ident==2: emit({{'id':2,'error':{error!r}}})\n"
            else:
                program += f" elif ident==2: emit({{'id':2,'result':{limits()!r}}})\n"
                if mode == "duplicate-result":
                    program += f" elif ident==3:\n  emit({{'id':2,'result':{limits()!r}}})\n  emit({{'id':3,'result':{self.codex_catalog()!r}}})\n"
                else:
                    program += f" elif ident==3: emit({{'id':3,'result':{self.codex_catalog()!r}}})\n"
        executable.write_text(program)
        executable.chmod(0o700)
        return {"id": "codex", "label": "Codex", "adapter": "codex_cli", "executable": str(executable)}, recorded

    def rpc_with_cleanup_assertions(self, tool, seconds=1):
        processes, directories, group_settings = [], [], []
        original = b.subprocess.Popen
        def capture(*args, **kwargs):
            directories.append(pathlib.Path(kwargs["env"]["CODEX_HOME"]))
            group_settings.append(kwargs.get("start_new_session"))
            process = original(*args, **kwargs)
            processes.append(process)
            return process
        with mock.patch.object(b.subprocess, "Popen", side_effect=capture), mock.patch.object(b, "CODEX_METADATA_SECONDS", seconds):
            try:
                return self.bridge.codex_rpc(tool)
            finally:
                self.assertEqual(1, len(processes))
                self.assertEqual([True], group_settings)
                self.assertIsNotNone(processes[0].poll())
                self.assertTrue(processes[0].stdin.closed)
                self.assertTrue(processes[0].stdout.closed)
                self.assertFalse(directories[0].exists())

    def test_projection_access_only_private_and_normal_source_unchanged(self):
        before, mode = self.source.read_bytes(), self.source.stat().st_mode
        with self.bridge.codex_session() as env:
            home = pathlib.Path(env["CODEX_HOME"])
            projection = json.loads((home/"auth.json").read_text())
            self.assertEqual(0o700, home.stat().st_mode & 0o777)
            self.assertEqual(0o600, (home/"auth.json").stat().st_mode & 0o777)
            self.assertEqual("chatgptAuthTokens", projection["auth_mode"])
            self.assertEqual("", projection["tokens"]["refresh_token"])
            self.assertEqual(projection["tokens"]["access_token"], projection["tokens"]["id_token"])
            self.assertEqual({"access_token", "id_token", "refresh_token", "account_id"}, set(projection["tokens"]))
            self.assertNotIn("OPENAI_API_KEY", projection)
            for secret in ("FIXTURE_REFRESH_SECRET", "FIXTURE_ORIGINAL_ID_TOKEN", "DO_NOT_COPY"):
                self.assertNotIn(secret, json.dumps(projection))
        self.assertFalse(home.exists())
        self.assertEqual(before, self.source.read_bytes())
        self.assertEqual(mode, self.source.stat().st_mode)

    def test_latest_source_used_each_operation_not_old_private_copy(self):
        old = self.path/"codex-home"
        old.mkdir(mode=0o700)
        (old/"auth.json").write_text('{"auth_mode":"apikey","OPENAI_API_KEY":"DO_NOT_USE"}')
        with self.bridge.codex_session() as env:
            a = json.loads((pathlib.Path(env["CODEX_HOME"])/"auth.json").read_text())
        self.write_source(account="fixture-account-b")
        with self.bridge.codex_session() as env:
            newer = json.loads((pathlib.Path(env["CODEX_HOME"])/"auth.json").read_text())
        self.assertNotEqual(a["tokens"]["access_token"], newer["tokens"]["access_token"])
        self.assertEqual("fixture-account-b", newer["tokens"]["account_id"])
        self.assertIn("DO_NOT_USE", (old/"auth.json").read_text())

    def test_missing_normal_source_never_falls_back_to_old_copy(self):
        old = self.path/"codex-home"
        old.mkdir(mode=0o700)
        (old/"auth.json").write_bytes(self.source.read_bytes())
        self.source.unlink()
        self.assert_code("AI_AUTH_REQUIRED", b.read_codex_auth_snapshot)

    def test_environment_codex_home_does_not_redirect_normal_source(self):
        with mock.patch.dict(os.environ, {"CODEX_HOME": "/untrusted-home", "OPENAI_API_KEY": "UNTRUSTED"}):
            snapshot = b.read_codex_auth_snapshot()
        self.assertEqual("fixture-account-a", snapshot.account_id)
        self.assertNotIn(snapshot.access_token, repr(snapshot))
        self.assertNotIn(snapshot.account_id, repr(snapshot))

    def test_source_file_symlink_rejected(self):
        target = self.source.with_name("real-auth.json")
        self.source.rename(target)
        self.source.symlink_to(target)
        self.assert_code("AI_AUTH_REQUIRED", b.read_codex_auth_snapshot)

    def test_source_directory_symlink_rejected(self):
        target = self.source_directory.with_name("real-codex")
        self.source_directory.rename(target)
        self.source_directory.symlink_to(target, target_is_directory=True)
        self.assert_code("AI_AUTH_REQUIRED", b.read_codex_auth_snapshot)

    def test_non_private_source_mode_and_writable_parent_rejected(self):
        self.source.chmod(0o644)
        self.assert_code("AI_AUTH_REQUIRED", b.read_codex_auth_snapshot)
        self.source.chmod(0o600)
        self.source_directory.chmod(0o777)
        self.assert_code("AI_AUTH_REQUIRED", b.read_codex_auth_snapshot)

    def test_standard_non_writable_0755_parent_is_preserved(self):
        self.source_directory.chmod(0o755)
        b.read_codex_auth_snapshot()
        self.assertEqual(0o755, self.source_directory.stat().st_mode & 0o777)

    def test_other_owner_rejected(self):
        original = b.os.fstat
        def wrong_owner(fd):
            info = original(fd)
            if b.stat.S_ISREG(info.st_mode):
                values = list(info)
                values[4] = os.getuid()+1
                return os.stat_result(values)
            return info
        with mock.patch.object(b.os, "fstat", side_effect=wrong_owner):
            self.assert_code("AI_AUTH_REQUIRED", b.read_codex_auth_snapshot)

    def test_fifo_and_directory_refused_without_blocking(self):
        self.source.unlink()
        os.mkfifo(self.source, 0o600)
        started = time.monotonic()
        self.assert_code("AI_AUTH_REQUIRED", b.read_codex_auth_snapshot)
        self.assertLess(time.monotonic()-started, .5)
        self.source.unlink()
        self.source.mkdir(mode=0o700)
        self.assert_code("AI_AUTH_REQUIRED", b.read_codex_auth_snapshot)

    def test_oversize_empty_or_malformed_source_rejected(self):
        for raw in (b"x"*(b.AUTH_MAX_BYTES+1), b"", b"not-json", b"[]"):
            self.source.write_bytes(raw)
            self.assert_code("AI_AUTH_REQUIRED", b.read_codex_auth_snapshot)

    def test_expired_or_too_near_expiry_is_auth_expired(self):
        for expires in (int(time.time())-1, int(time.time())+30):
            self.write_source(extra_claims={"exp": expires})
            self.assert_code("AI_AUTH_EXPIRED", b.read_codex_auth_snapshot)

    def test_invalid_expiry_not_before_or_account_is_auth_required(self):
        for extra in ({"exp": None}, {"exp": True}, {"exp": "never"}, {"nbf": int(time.time())+3600},
                      {"nbf": True}, {"https://api.openai.com/auth": {}},
                      {"https://api.openai.com/auth": {"chatgpt_account_id": "other-account"}}):
            self.write_source(extra_claims=extra)
            self.assert_code("AI_AUTH_REQUIRED", b.read_codex_auth_snapshot)

    def test_missing_access_or_invalid_jwt_cannot_use_original_id_token(self):
        for access in (None, "", "invalid", "a.%%%.b", "a.e30.b"):
            auth = self.write_source()
            auth["tokens"]["access_token"] = access
            self.source.write_bytes(b.encode(auth))
            self.assert_code("AI_AUTH_REQUIRED", b.read_codex_auth_snapshot)

    def test_api_key_source_separately_rejected(self):
        self.write_source(OPENAI_API_KEY="FIXTURE_API_KEY")
        self.assert_code("AI_SUBSCRIPTION_REQUIRED", b.read_codex_auth_snapshot)

    def test_source_atomic_replace_reopens_latest_consistent_snapshot(self):
        original = b.os.read
        replaced = []
        def rotate(fd, size):
            raw = original(fd, size)
            if not replaced:
                auth = json.loads(raw)
                auth["tokens"].update(access_token=self.token("fixture-account-b"), account_id="fixture-account-b")
                pending = self.source.with_suffix(".new")
                pending.write_bytes(b.encode(auth));pending.chmod(0o600);os.replace(pending, self.source)
                replaced.append(True)
            return raw
        with mock.patch.object(b.os, "read", side_effect=rotate):
            snapshot = b.read_codex_auth_snapshot()
        self.assertEqual("fixture-account-b", snapshot.account_id)

    def test_continually_replaced_source_fails_without_unbounded_retry(self):
        original = b.os.read
        reads = []
        def rotate(fd, size):
            raw = original(fd, size)
            reads.append(True)
            pending = self.source.with_suffix(".new")
            pending.write_bytes(raw);pending.chmod(0o600);os.replace(pending, self.source)
            return raw
        with mock.patch.object(b.os, "read", side_effect=rotate):
            self.assert_code("AI_AUTH_REQUIRED", b.read_codex_auth_snapshot)
        self.assertEqual(2, len(reads))

    def test_session_exception_removes_projection_and_preserves_source(self):
        before = self.source.read_bytes()
        home = None
        with self.assertRaises(b.Problem):
            with self.bridge.codex_session() as env:
                home = pathlib.Path(env["CODEX_HOME"])
                raise b.Problem("AI_AUTH_EXPIRED")
        self.assertFalse(home.exists())
        self.assertEqual(before, self.source.read_bytes())

    def test_concurrent_operations_have_independent_private_projections(self):
        barrier = threading.Barrier(2)
        def enter():
            with self.bridge.codex_session() as env:
                home = pathlib.Path(env["CODEX_HOME"])
                barrier.wait(timeout=3)
                self.assertTrue((home/"auth.json").is_file())
                return home
        with b.concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            homes = list(pool.map(lambda _:enter(), range(2)))
        self.assertNotEqual(homes[0], homes[1])
        self.assertTrue(all(not home.exists() for home in homes))

    def test_same_snapshot_proof_and_exec_despite_normal_login_rotation(self):
        paths = []
        def proof(tool, env):
            paths.append(pathlib.Path(env["CODEX_HOME"]))
            before = json.loads((paths[0]/"auth.json").read_text())
            self.assertEqual("fixture-account-a", before["tokens"]["account_id"])
            self.write_source("fixture-account-b")
            return limits(), self.codex_catalog()
        def execute(argv, **kwargs):
            home = pathlib.Path(kwargs["env"]["CODEX_HOME"])
            self.assertEqual(paths[0], home)
            self.assertEqual("fixture-account-a", json.loads((home/"auth.json").read_text())["tokens"]["account_id"])
            pathlib.Path(argv[argv.index("--output-last-message")+1]).write_text("合成文字")
            return b'\n'.join(b.encode(v) for v in [{"type":"turn.started"},{"type":"item.completed","item":{"type":"agent_message","text":"fixture"}},{"type":"turn.completed"}])
        with mock.patch.object(self.bridge, "codex_rpc", side_effect=proof), mock.patch.object(b, "run_process", side_effect=execute):
            text, actual = self.bridge.infer({"adapter":"codex_cli","executable":"/fixture/codex"}, {"model":"test-model","effort":"low"}, "fixture")
        self.assertEqual("合成文字", text)
        self.assertIsNone(actual)
        self.assertFalse(paths[0].exists())
        self.assertEqual("fixture-account-b", b.read_codex_auth_snapshot().account_id)

    def test_final_pinned_billing_or_model_failure_is_zero_exec(self):
        paid = limits()
        paid["rateLimits"]["credits"]["hasCredits"] = True
        tool = {"adapter":"codex_cli","executable":"/fixture/codex"}
        for value, models, code in [(paid, self.codex_catalog(), "AI_PAID_CREDITS_ENABLED"),
                                    ({}, self.codex_catalog(), "AI_BILLING_UNVERIFIED"),
                                    (limits(), {"data":[]}, "AI_MODEL_UNAVAILABLE")]:
            with mock.patch.object(self.bridge, "codex_rpc", return_value=(value, models)), mock.patch.object(b, "run_process") as execute:
                self.assert_code(code, self.bridge.infer, tool, {"model":"test-model","effort":"low"}, "fixture")
                execute.assert_not_called()

    def test_rpc_success_projection_cleanup_and_no_refresh_material(self):
        tool, recorded = self.fake_rpc_cli()
        value, models = self.rpc_with_cleanup_assertions(tool)
        self.bridge.check_codex_billing(value)
        self.assertEqual(1, len(models["data"]))
        self.assertEqual({"mode":"chatgptAuthTokens","refreshPresent":False,"sameJwt":True}, json.loads(recorded.read_text()))

    def test_rpc_auth_401_expired_and_external_refresh_have_distinct_code(self):
        for error in ({"code":-32000,"message":"HTTP 401 Unauthorized: access token has expired"},
                      {"code":401,"message":"fixture"}, {"code":"token_expired","message":"fixture"}):
            tool, _ = self.fake_rpc_cli(mode="error", error=error)
            self.assert_code("AI_AUTH_EXPIRED", self.rpc_with_cleanup_assertions, tool)
        tool, _ = self.fake_rpc_cli(mode="refresh")
        self.assert_code("AI_AUTH_EXPIRED", self.rpc_with_cleanup_assertions, tool)

    def test_rpc_network_unknown_and_rate_limit_remain_billing_unverified(self):
        for error in ({"code":429,"message":"Rate limit reached"},{"code":500,"message":"Service unavailable"},
                      {"code":403,"message":"Forbidden"},{"code":-32000,"message":"Unknown provider response"}):
            tool, _ = self.fake_rpc_cli(mode="error", error=error)
            self.assert_code("AI_BILLING_UNVERIFIED", self.rpc_with_cleanup_assertions, tool)

    def test_rpc_unknown_or_repeated_handshake_is_bounded_and_cleaned(self):
        for mode in ("duplicate-init", "duplicate-result", "invalid"):
            tool, _ = self.fake_rpc_cli(mode=mode)
            self.assert_code("AI_BILLING_UNVERIFIED", self.rpc_with_cleanup_assertions, tool)

    def test_rpc_timeout_cleans_process_group_pipes_and_projection(self):
        tool, _ = self.fake_rpc_cli(mode="timeout")
        started = time.monotonic()
        self.assert_code("AI_BILLING_UNVERIFIED", self.rpc_with_cleanup_assertions, tool, seconds=.15)
        self.assertLess(time.monotonic()-started, 1)

    def test_exec_structured_auth_failure_is_classified_even_nonzero_exit(self):
        for event in ({"type":"error","message":"HTTP 401 Unauthorized"},
                      {"type":"turn.failed","error":{"code":"token_expired","message":"fixture"}},
                      {"method":"account/chatgptAuthTokens/refresh","id":"fixture"}):
            raw = b.encode(event)
            self.assert_code("AI_AUTH_EXPIRED", self.bridge.validate_codex_events, raw)
            program = "import sys;sys.stdout.buffer.write("+repr(raw)+");sys.stdout.flush();sys.exit(1)"
            self.assert_code("AI_AUTH_EXPIRED", b.run_process, [sys.executable,"-c",program],
                             failure_classifier=self.bridge.codex_exec_auth_error, own_process_group=True)

    def test_stderr_model_text_or_unknown_diagnostics_are_not_auth_evidence(self):
        self.assertIsNone(self.bridge.codex_auth_error({"type":"item.completed","item":{"type":"agent_message","text":"401 token expired"}}))
        self.assertIsNone(self.bridge.codex_auth_error({"message":"401"}))
        program = "import sys;print('401 Unauthorized token expired',file=sys.stderr);sys.exit(1)"
        self.assert_code("AI_EXECUTION_FAILED", b.run_process, [sys.executable,"-c",program],
                         failure_classifier=self.bridge.codex_exec_auth_error, own_process_group=True)
        self.assert_code("AI_EXECUTION_FAILED", self.bridge.validate_codex_events, b'{"type":"error","message":"unknown safety configuration"}')

    def test_nonblocking_stdin_timeout_cleans_owned_process_and_pipes(self):
        processes = []
        original = b.subprocess.Popen
        def capture(*args, **kwargs):
            process = original(*args, **kwargs);processes.append(process);return process
        with mock.patch.object(b.subprocess, "Popen", side_effect=capture):
            self.assert_code("AI_TIMEOUT", b.run_process, [sys.executable,"-c","import time;time.sleep(30)"],
                             data=b"x"*b.MAX_BODY, timeout=.15, own_process_group=True)
        self.assertIsNotNone(processes[0].poll())
        self.assertTrue(processes[0].stdin.closed)
        self.assertTrue(processes[0].stdout.closed)

    def test_private_parent_marker_is_not_forwarded_by_clean_environment(self):
        with mock.patch.dict(os.environ, {"SRPP_AI_OPERATION_ROOT":"/untrusted"}):
            self.assertNotIn("SRPP_AI_OPERATION_ROOT", b.clean_environment())
            self.assert_code("AI_AUTH_REQUIRED", b.parent_operation_root)

    def test_worker_projection_lives_until_parent_group_cleanup(self):
        root = self.path/"operation-root"
        root.mkdir(mode=0o700)
        with mock.patch.dict(os.environ, {"SRPP_AI_OPERATION_ROOT":str(root),"TMPDIR":str(root)}), mock.patch.object(b.os,"getpgrp",return_value=os.getpid()):
            with self.bridge.codex_session() as env:
                projected = pathlib.Path(env["CODEX_HOME"])
            self.assertTrue(projected.is_relative_to(root))
            self.assertTrue((projected/"auth.json").exists())
        b.shutil.rmtree(root)
        self.assertFalse(projected.exists())

    def test_outer_hard_timeout_cleans_parent_root_even_child_finally_cannot_run(self):
        roots, processes = [], []
        original = b.subprocess.Popen
        program = ("import os,pathlib,time;root=pathlib.Path(os.environ['SRPP_AI_OPERATION_ROOT']);"
                   "p=root/'access';p.mkdir(mode=0o700);f=p/'auth.json';f.write_text('SYNTHETIC_ONLY');f.chmod(0o600);"
                   "os.fork();time.sleep(30)")
        def fixture_child(argv, **kwargs):
            root = pathlib.Path(kwargs["env"]["SRPP_AI_OPERATION_ROOT"])
            self.assertEqual(str(root), kwargs["env"]["TMPDIR"])
            self.assertEqual(0o700, root.stat().st_mode & 0o777)
            roots.append(root)
            process = original([sys.executable,"-c",program], **kwargs)
            processes.append(process)
            return process
        with mock.patch.object(self.bridge,"capabilities",return_value=self.catalog()), mock.patch.object(b.subprocess,"Popen",side_effect=fixture_child), mock.patch.object(b,"DEADLINE",.2):
            self.assert_code("AI_TIMEOUT", self.bridge.execute, self.body())
        self.assertFalse(roots[0].exists())
        self.assertIsNotNone(processes[0].poll())
        self.assertTrue(processes[0].stdin.closed)
        self.assertTrue(processes[0].stdout.closed)


class ClaudeDiscoveryTests(Fixture):
    def metadata(self):
        return json.loads((ROOT/"scripts/tests/fixtures/srpp_claude_initialize_models.json").read_text())

    def rows(self):
        return self.metadata()["response"]["response"]["models"]

    def cli_program(self, *, payload=None, suffix="", sleep=False, oversize=False):
        executable = self.path/"metadata-cli"
        recorded = self.path/"control-input.json"
        script = ("#!"+sys.executable+"\nimport sys,json,time,pathlib,os\n"
                  +"request=json.loads(sys.stdin.readline())\n"
                  +f"pathlib.Path({str(recorded)!r}).write_text(json.dumps(request))\n")
        if sleep:
            script += "time.sleep(30)\n"
        elif oversize:
            script += f"sys.stdout.write('x'*{b.MAX_BODY+1});sys.stdout.flush();time.sleep(30)\n"
        else:
            value = payload if payload is not None else self.metadata()
            script += f"sys.stdout.write({json.dumps(value,ensure_ascii=False)!r}+'\\n'+{suffix!r});sys.stdout.flush();time.sleep(30)\n"
        executable.write_text(script)
        executable.chmod(0o700)
        return {"id": "dynamic", "label": "Dynamic", "adapter": "claude_cli", "executable": str(executable)}, recorded

    def discover_with_process_capture(self, tool, *, seconds=1):
        processes = []
        original = b.subprocess.Popen
        def capture(*args, **kwargs):
            process = original(*args, **kwargs)
            processes.append(process)
            return process
        with mock.patch.object(b.subprocess, "Popen", side_effect=capture):
            try:
                result = self.bridge.claude_models(tool, time.monotonic()+seconds)
                return result
            finally:
                self.assertEqual(1, len(processes))
                process = processes[0]
                self.assertIsNotNone(process.poll())
                self.assertTrue(process.stdin.closed)
                self.assertTrue(process.stdout.closed)

    def test_default_registry_no_longer_contains_claude_models(self):
        tool = next(t for t in b.default_registry()["tools"] if t["adapter"] == "claude_cli")
        self.assertNotIn("models", tool)
        self.tools = [tool]
        self.save()
        self.assertEqual([tool], b.load_registry(self.registry))

    def test_real_cli_fixture_dynamic_models_and_efforts(self):
        entries = b.Bridge.claude_model_entries(self.rows())
        self.assertEqual(["claude-haiku-5-5", "claude-opus-5-5", "claude-sonnet-5-5"], [e["id"] for e in entries])
        for entry in entries:
            self.assertIn(entry["id"], entry["label"])
            self.assertEqual(["low", "medium", "high", "xhigh", "max"], entry["efforts"])
            self.assertEqual("medium", entry["defaultEffort"])

    def test_fourth_cli_standard_version_is_not_capped_or_hardcoded(self):
        rows = self.rows()
        extra = copy.deepcopy(rows[1])
        extra.update(value="claude-opus-5-4", resolvedModel="claude-opus-5-4", displayName="Opus 5.4",
                     supportedEffortLevels=["low", "medium"])
        rows.append(extra)
        models = b.Bridge.claude_model_entries(rows)
        self.assertEqual(4, len(models))
        fourth = next(m for m in models if m["id"] == "claude-opus-5-4")
        self.assertEqual(["low", "medium"], fourth["efforts"])

    def test_explicit_unsupported_effort_only_null_without_invented_levels(self):
        row = self.rows()[1]
        row["supportsEffort"] = False
        row["supportedEffortLevels"] = []
        model = b.Bridge.claude_model_entries([row])[0]
        self.assertEqual([], model["efforts"])
        self.assertIsNone(model["defaultEffort"])

    def test_unknown_or_conflicting_effort_metadata_fails_closed(self):
        mutations = [lambda r:r.pop("supportsEffort"), lambda r:r.update(supportsEffort=None),
                     lambda r:r.pop("supportedEffortLevels"), lambda r:r.update(supportedEffortLevels=[]),
                     lambda r:r.update(supportedEffortLevels=["low", 2]), lambda r:r.update(supportedEffortLevels=["low", "low"]),
                     lambda r:r.update(supportsEffort=False), lambda r:r.update(defaultEffort="not-returned")]
        for mutate in mutations:
            row = self.rows()[1]
            mutate(row)
            self.assert_code("AI_MODEL_UNAVAILABLE", b.Bridge.claude_model_entries, [row])

    def test_cli_future_effort_and_default_are_not_replaced(self):
        row = self.rows()[1]
        row.update(supportedEffortLevels=["low", "future"], defaultEffort="future")
        entry = b.Bridge.claude_model_entries([row])[0]
        self.assertEqual(["low", "future"], entry["efforts"])
        self.assertEqual("future", entry["defaultEffort"])
        row.pop("defaultEffort")
        self.assertEqual("low", b.Bridge.claude_model_entries([row])[0]["defaultEffort"])

    def test_duplicates_same_capabilities_deduplicate_conflicts_reject(self):
        row = self.rows()[1]
        self.assertEqual(1, len(b.Bridge.claude_model_entries([row, copy.deepcopy(row)])))
        conflict = copy.deepcopy(row)
        conflict["supportedEffortLevels"] = ["low"]
        self.assert_code("AI_MODEL_UNAVAILABLE", b.Bridge.claude_model_entries, [row, conflict])

    def test_paid_context_original_value_never_loses_modifier(self):
        row = self.rows()[1]
        row.update(value="claude-opus-4-6[1m]", resolvedModel="claude-opus-4-6")
        self.assert_code("AI_MODEL_UNAVAILABLE", b.Bridge.claude_model_entries, [row])
        row.update(value="claude-opus-4-6", resolvedModel="claude-opus-4-6[1m]")
        self.assert_code("AI_MODEL_UNAVAILABLE", b.Bridge.claude_model_entries, [row])

    def test_fable_unknown_billing_or_explicit_credits_not_inferred_free(self):
        row = self.rows()[2]
        row["value"] = "claude-fable-5-2"
        self.assert_code("AI_MODEL_UNAVAILABLE", b.Bridge.claude_model_entries, [row])
        for changes in [{"resolvedModel": "claude-future-9", "value": "future"}, {"requiresUsageCredits": True},
                        {"subscriptionIncluded": False}, {"description": "Requires usage credits"}]:
            row = self.rows()[1]
            row.update(changes)
            self.assert_code("AI_MODEL_UNAVAILABLE", b.Bridge.claude_model_entries, [row])

    def test_behavior_modes_and_unknown_schema_rejected(self):
        for mode in ("default", "opusplan"):
            row = self.rows()[1]
            row["value"] = mode
            self.assert_code("AI_MODEL_UNAVAILABLE", b.Bridge.claude_model_entries, [row])
        for bad in [None, [], {}, [None], [{"value": "opus"}]]:
            self.assert_code("AI_MODEL_UNAVAILABLE", b.Bridge.claude_model_entries, bad)
        row = self.rows()[1]
        row["new_unknown_billing_field"] = True
        self.assert_code("AI_MODEL_UNAVAILABLE", b.Bridge.claude_model_entries, [row])

    def test_legacy_registry_is_not_model_authority_or_filter(self):
        before = self.registry.read_bytes()
        runtime = b.Bridge.claude_model_entries(self.rows())
        extra = {"id": "claude-opus-5-4", "label": "Opus · claude-opus-5-4", "efforts": ["low"], "defaultEffort": "low"}
        with mock.patch.object(self.bridge, "claude_proof"), mock.patch.object(self.bridge, "claude_models", return_value=runtime+[extra]):
            result = self.bridge.probe(self.tools[0])
        self.assertEqual(4, len(result["models"]))
        self.assertTrue(result["available"])
        self.assertEqual("RUNTIME", result["modelSource"])
        self.assertEqual(before, self.registry.read_bytes())

    def test_discovery_failure_never_falls_back_to_legacy_models(self):
        with mock.patch.object(self.bridge, "claude_proof"), mock.patch.object(self.bridge, "claude_models", side_effect=b.Problem("AI_TIMEOUT")):
            result = self.bridge.probe(self.tools[0])
        self.assertFalse(result["available"])
        self.assertEqual("AI_TIMEOUT", result["code"])
        self.assertEqual([], result["models"])

    def test_dynamic_effort_changes_revision_and_old_selection_is_stale(self):
        first = self.catalog()["tools"][0]
        first["models"] = b.Bridge.claude_model_entries(self.rows())
        second = copy.deepcopy(first)
        second["models"][0].update(efforts=["low"], defaultEffort="low")
        with mock.patch.object(self.bridge, "probe", return_value=first):
            old = self.bridge.capabilities(fresh=True)
        with mock.patch.object(self.bridge, "probe", return_value=second):
            new = self.bridge.capabilities(fresh=True)
        self.assertNotEqual(old["catalogRevision"], new["catalogRevision"])
        selection = {"toolId": "test", "model": first["models"][0]["id"], "effort": "medium", "catalogRevision": old["catalogRevision"]}
        self.assert_code("AI_CATALOG_STALE", self.bridge.validate_selection, selection, new)

    def test_removed_model_rejected_before_inference_no_fallback(self):
        body = self.body()
        body["selection"].update(model="claude-opus-5-4")
        available = self.catalog()["tools"][0]
        available["models"] = b.Bridge.claude_model_entries(self.rows())
        with mock.patch.object(self.bridge, "probe", return_value=available), mock.patch.object(self.bridge, "infer") as infer:
            self.assert_code("AI_INVALID_SELECTION", self.bridge.execute_inline, body)
            infer.assert_not_called()

    def test_cli_initialize_only_no_user_turn_and_success_cleanup(self):
        tool, recorded = self.cli_program()
        models = self.discover_with_process_capture(tool)
        self.assertEqual(3, len(models))
        request = json.loads(recorded.read_text())
        self.assertEqual("control_request", request["type"])
        self.assertEqual({"subtype": "initialize", "hooks": {}, "sdkMcpServers": []}, request["request"])
        self.assertNotIn("prompt", request)

    def test_cli_timeout_kills_process_group_and_closes_pipes(self):
        tool, _ = self.cli_program(sleep=True)
        started = time.monotonic()
        self.assert_code("AI_TIMEOUT", self.discover_with_process_capture, tool, seconds=.15)
        self.assertLess(time.monotonic()-started, 1)

    def test_cli_output_limit_kills_process_and_closes_pipes(self):
        tool, _ = self.cli_program(oversize=True)
        self.assert_code("AI_MODEL_UNAVAILABLE", self.discover_with_process_capture, tool)

    def test_cli_non_initialize_or_model_event_is_rejected(self):
        for payload in [{"type": "assistant", "message": {}}, {"type": "result", "subtype": "success"},
                        {"type": "control_response", "response": {"request_id": "other", "subtype": "success"}},
                        {"type": "control_response", "response": {"request_id": "srpp-model-metadata", "subtype": "error"}}]:
            tool, _ = self.cli_program(payload=payload)
            self.assert_code("AI_MODEL_UNAVAILABLE", self.discover_with_process_capture, tool)
        for suffix in ('{"type":"assistant"}\n', '{"type":"control_response"}\n', '{"type":"user"'):
            tool, _ = self.cli_program(suffix=suffix)
            self.assert_code("AI_MODEL_UNAVAILABLE", self.discover_with_process_capture, tool)

    def test_paid_or_unknown_proof_never_starts_discovery_or_inference(self):
        for code in ("AI_SUBSCRIPTION_REQUIRED", "AI_EXTRA_USAGE_ENABLED", "AI_BILLING_UNVERIFIED"):
            with mock.patch.object(self.bridge, "claude_proof", side_effect=b.Problem(code)), mock.patch.object(self.bridge, "claude_models") as models, mock.patch.object(self.bridge, "infer") as infer:
                result = self.bridge.probe(self.tools[0])
            self.assertEqual(code, result["code"])
            self.assertEqual([], result["models"])
            models.assert_not_called()
            infer.assert_not_called()

    def test_proof_and_discovery_share_one_absolute_deadline(self):
        deadlines = []
        def proof(tool, deadline):
            deadlines.append(deadline)
        def models(tool, deadline):
            deadlines.append(deadline)
            return b.Bridge.claude_model_entries(self.rows())
        before = time.monotonic()
        with mock.patch.object(self.bridge, "claude_proof", side_effect=proof), mock.patch.object(self.bridge, "claude_models", side_effect=models):
            self.assertTrue(self.bridge.probe(self.tools[0])["available"])
        self.assertEqual(deadlines[0], deadlines[1])
        self.assertLessEqual(deadlines[0]-before, 8)

    def test_exhausted_deadline_cannot_start_another_metadata_operation(self):
        with mock.patch.object(b, "run_process") as run:
            self.assert_code("AI_TIMEOUT", self.bridge.claude_proof, self.tools[0], deadline=time.monotonic()-1)
            run.assert_not_called()


class InstallTests(Fixture):
    def test_state_token_registry_preserved_and_projection_permissions(self):
        state = self.path/"state"
        token, registry = i.prepare_state(b, state)
        before_token, before_registry = token.read_bytes(), registry.read_bytes()
        i.prepare_state(b, state)
        self.assertEqual(before_token, token.read_bytes())
        self.assertEqual(before_registry, registry.read_bytes())
        self.assertEqual(0o600, token.stat().st_mode & 0o777)
        self.assertEqual(0o444, (state/"docker-token/bridge.token").stat().st_mode & 0o777)
        self.assertEqual(before_token, (state/"docker-token/bridge.token").read_bytes())

    def test_owned_launchagent_has_no_token_or_secret_in_argv(self):
        job = i.job_configuration(ROOT, self.path, pathlib.Path(sys.executable))
        self.assertTrue(i.owned_job(job))
        self.assertNotIn("EnvironmentVariables", job)
        self.assertNotIn("TEST_SECRET", str(job))
        self.assertFalse(i.owned_job({"Label": "unrelated", "ProgramArguments": job["ProgramArguments"]}))

    def test_install_kickstarts_even_when_source_path_is_unchanged(self):
        home = self.path/"home"
        home.mkdir()
        state = self.path/"install-state"
        with mock.patch.dict(os.environ, {"HOME": str(home)}), mock.patch.object(i.subprocess, "run", return_value=mock.Mock(returncode=0)) as run:
            self.assertEqual(0, i.cli(["--install", "--source-root", str(ROOT), "--state-dir", str(state)]))
            run.reset_mock()
            self.assertEqual(0, i.cli(["--install", "--source-root", str(ROOT), "--state-dir", str(state)]))
        self.assertTrue(any(call.args[0][:3] == ["launchctl", "kickstart", "-k"] for call in run.call_args_list))


if __name__ == "__main__":
    unittest.main()
