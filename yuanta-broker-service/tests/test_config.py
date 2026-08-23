from __future__ import annotations

from yuanta_broker_service.config import ConfigLoader

from helpers import ready_config


def test_disabled_is_not_configured(tmp_path):
    snapshot = ConfigLoader(tmp_path, lambda: "false").load()
    assert snapshot.state == "NOT_CONFIGURED"
    assert snapshot.reason is None
    assert snapshot.enabled is False


def test_invalid_enabled_flag_is_misconfigured(tmp_path):
    snapshot = ConfigLoader(tmp_path, lambda: "yes-please").load()
    assert snapshot.state == "MISCONFIGURED"
    assert snapshot.reason == "INVALID_ENABLED_FLAG"


def test_enabled_with_all_secrets_present_is_ready(tmp_path):
    snapshot = ready_config(tmp_path).load()
    assert snapshot.state == "READY"
    assert snapshot.reason is None
    assert snapshot.presence.public_dict() == {
        "dll": True,
        "account": True,
        "password": True,
        "certificate": True,
        "certificatePassword": True,
        "stockAccountSelector": False,
        "futuresAccountSelector": False,
        "internalServiceToken": True,
    }


def test_enabled_missing_dll_is_misconfigured(tmp_path):
    loader = ready_config(tmp_path)
    (tmp_path / "sdk" / "dll" / "YuantaSparkAPI.dll").unlink()
    snapshot = loader.load()
    assert snapshot.state == "MISCONFIGURED"
    assert snapshot.reason == "MISSING_REQUIRED_SECRET"
    assert snapshot.presence.dll is False


def test_enabled_missing_internal_token_is_misconfigured(tmp_path):
    loader = ready_config(tmp_path)
    (tmp_path / "internal-service-token").unlink()
    snapshot = loader.load()
    assert snapshot.state == "MISCONFIGURED"
    assert snapshot.reason == "MISSING_REQUIRED_SECRET"


def test_optional_selector_present_alone_is_ready(tmp_path):
    snapshot = ready_config(tmp_path, stock_selector="001:0001234567").load()
    assert snapshot.state == "READY"
    assert snapshot.stock_account_selector == "001:0001234567"


def test_malformed_selector_is_misconfigured(tmp_path):
    loader = ready_config(tmp_path)
    (tmp_path / "sdk" / "stock-account-selector").write_text("not-a-valid-selector", encoding="utf-8")
    snapshot = loader.load()
    assert snapshot.state == "MISCONFIGURED"
    assert snapshot.reason == "INVALID_ACCOUNT_SELECTOR_FORMAT"


def test_repr_never_leaks_secret_values(tmp_path):
    snapshot = ready_config(tmp_path).load()
    rendered = repr(snapshot)
    assert "TEST_ACCOUNT_SENTINEL" not in rendered
    assert "TEST_PASSWORD_SENTINEL" not in rendered
    assert "TEST_CERT_PASSWORD_SENTINEL" not in rendered
