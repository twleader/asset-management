"""Task 592 isolated tests. No real credentials, accounts or inference requests."""
import copy
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
        home = self.path/"codex-home"
        home.mkdir(mode=0o700)
        auth = home/"auth.json"
        auth.write_bytes(b.encode({"auth_mode": "apikey", "OPENAI_API_KEY": "TEST_SECRET"}))
        auth.chmod(0o600)
        self.assert_code("AI_SUBSCRIPTION_REQUIRED", self.bridge.codex_home)

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
