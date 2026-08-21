from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable


@dataclass(frozen=True)
class SecretPresence:
    personal_id: bool
    api_key: bool
    certificate: bool
    certificate_password: bool
    account_selector_pair: bool
    internal_service_token: bool

    def public_dict(self) -> dict[str, bool]:
        return {
            "personalId": self.personal_id,
            "apiKey": self.api_key,
            "certificate": self.certificate,
            "certificatePassword": self.certificate_password,
            "accountSelectorPair": self.account_selector_pair,
            "internalServiceToken": self.internal_service_token,
        }


@dataclass(frozen=True)
class ConfigSnapshot:
    enabled: bool
    state: str
    presence: SecretPresence
    reason: str | None = None
    personal_id: str | None = field(default=None, repr=False)
    api_key: str | None = field(default=None, repr=False)
    certificate_path: Path | None = field(default=None, repr=False)
    certificate_password: str | None = field(default=None, repr=False)
    account_branch_no: str | None = field(default=None, repr=False)
    account_number: str | None = field(default=None, repr=False)
    internal_service_token: str | None = field(default=None, repr=False)

    def __repr__(self) -> str:
        return f"ConfigSnapshot(enabled={self.enabled!r}, state={self.state!r}, reason={self.reason!r})"


class ConfigLoader:
    """Loads mounted files lazily. No credential is accepted from HTTP or environment."""

    def __init__(self, root: Path, enabled_reader: Callable[[], str | None]) -> None:
        self._root = root
        self._enabled_reader = enabled_reader

    @classmethod
    def from_environment(cls) -> "ConfigLoader":
        return cls(Path("/run/secrets/fubon"), lambda: os.getenv("FUBON_ENABLED", "false"))

    def load(self) -> ConfigSnapshot:
        enabled_raw = (self._enabled_reader() or "false").strip().lower()
        enabled_valid = enabled_raw in {"true", "false"}
        enabled = enabled_raw == "true"

        personal_id = self._read_text("sdk/personal-id")
        api_key = self._read_text("sdk/api-key")
        certificate_path = self._nonempty_file("sdk/certificate.pfx")
        certificate_password = self._read_text("sdk/certificate-password")
        account_branch_no = self._read_text("sdk/account-branch-no")
        account_number = self._read_text("sdk/account-number")
        internal_service_token = self._read_text("shared/internal-service-token")

        selector_pair = bool(account_branch_no) and bool(account_number)
        selector_partial = bool(account_branch_no) != bool(account_number)
        presence = SecretPresence(
            personal_id=bool(personal_id),
            api_key=bool(api_key),
            certificate=certificate_path is not None,
            certificate_password=bool(certificate_password),
            account_selector_pair=selector_pair,
            internal_service_token=bool(internal_service_token),
        )

        if not enabled:
            reason = "INVALID_ENABLED_FLAG" if not enabled_valid else None
            state = "MISCONFIGURED" if not enabled_valid else "NOT_CONFIGURED"
        elif not enabled_valid:
            state, reason = "MISCONFIGURED", "INVALID_ENABLED_FLAG"
        elif selector_partial:
            state, reason = "MISCONFIGURED", "INCOMPLETE_ACCOUNT_SELECTOR"
        elif not all((personal_id, api_key, certificate_path, certificate_password, internal_service_token)):
            state, reason = "MISCONFIGURED", "MISSING_REQUIRED_SECRET"
        else:
            state, reason = "READY", None

        return ConfigSnapshot(
            enabled=enabled,
            state=state,
            presence=presence,
            reason=reason,
            personal_id=personal_id,
            api_key=api_key,
            certificate_path=certificate_path,
            certificate_password=certificate_password,
            account_branch_no=account_branch_no,
            account_number=account_number,
            internal_service_token=internal_service_token,
        )

    def _read_text(self, relative: str) -> str | None:
        path = self._root / relative
        try:
            if not path.is_file() or path.is_symlink():
                return None
            value = path.read_text(encoding="utf-8").strip()
            return value or None
        except (OSError, UnicodeError):
            return None

    def _nonempty_file(self, relative: str) -> Path | None:
        path = self._root / relative
        try:
            if path.is_symlink() or not path.is_file() or path.stat().st_size <= 0:
                return None
            return path
        except OSError:
            return None
