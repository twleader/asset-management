from __future__ import annotations

import os
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable


_SELECTOR_PATTERN = re.compile(r"^[0-9A-Za-z]+:[0-9A-Za-z]+$")


@dataclass(frozen=True)
class SecretPresence:
    dll: bool
    account: bool
    password: bool
    certificate: bool
    certificate_password: bool
    stock_account_selector: bool
    futures_account_selector: bool
    internal_service_token: bool

    def public_dict(self) -> dict[str, bool]:
        return {
            "dll": self.dll,
            "account": self.account,
            "password": self.password,
            "certificate": self.certificate,
            "certificatePassword": self.certificate_password,
            "stockAccountSelector": self.stock_account_selector,
            "futuresAccountSelector": self.futures_account_selector,
            "internalServiceToken": self.internal_service_token,
        }


@dataclass(frozen=True)
class ConfigSnapshot:
    """`state` only reflects whether components/credential files are present
    and (once combined with the gateway's runtime check) whether the DLL
    itself loaded. It never reflects whether Login() or a market-data
    connection has succeeded — those are independent, lazily-triggered
    capabilities tracked by the gateway, not by this snapshot.
    """

    enabled: bool
    state: str
    presence: SecretPresence
    reason: str | None = None
    dll_path: Path | None = field(default=None, repr=False)
    account: str | None = field(default=None, repr=False)
    password: str | None = field(default=None, repr=False)
    certificate_path: Path | None = field(default=None, repr=False)
    certificate_password: str | None = field(default=None, repr=False)
    stock_account_selector: str | None = field(default=None, repr=False)
    futures_account_selector: str | None = field(default=None, repr=False)
    internal_service_token: str | None = field(default=None, repr=False)

    def __repr__(self) -> str:
        return f"ConfigSnapshot(enabled={self.enabled!r}, state={self.state!r}, reason={self.reason!r})"


class ConfigLoader:
    """Loads mounted files lazily. No credential is accepted from HTTP or environment.

    This loader only checks *presence* of the required secret files and their
    static shape (e.g. selector format). It never attempts to load the DLL via
    pythonnet, never calls Login(), and never opens a market-data connection —
    those lazy, lower layers live in sdk_gateway.py and are only triggered by
    the first functional request.
    """

    def __init__(self, root: Path, enabled_reader: Callable[[], str | None]) -> None:
        self._root = root
        self._enabled_reader = enabled_reader

    @classmethod
    def from_environment(cls) -> "ConfigLoader":
        return cls(Path("/run/secrets/yuanta"), lambda: os.getenv("YUANTA_ENABLED", "false"))

    def load(self) -> ConfigSnapshot:
        enabled_raw = (self._enabled_reader() or "false").strip().lower()
        enabled_valid = enabled_raw in {"true", "false"}
        enabled = enabled_raw == "true"

        dll_path = self._nonempty_file("sdk/dll/YuantaSparkAPI.dll")
        account = self._read_text("sdk/account")
        password = self._read_text("sdk/password")
        certificate_path = self._nonempty_file("sdk/certificate.pfx")
        certificate_password = self._read_text("sdk/certificate-password")
        stock_selector = self._read_text("sdk/stock-account-selector")
        futures_selector = self._read_text("sdk/futures-account-selector")
        internal_service_token = self._read_text("internal-service-token")

        presence = SecretPresence(
            dll=dll_path is not None,
            account=bool(account),
            password=bool(password),
            certificate=certificate_path is not None,
            certificate_password=bool(certificate_password),
            stock_account_selector=bool(stock_selector),
            futures_account_selector=bool(futures_selector),
            internal_service_token=bool(internal_service_token),
        )

        selector_malformed = any(
            selector is not None and not _SELECTOR_PATTERN.fullmatch(selector)
            for selector in (stock_selector, futures_selector)
        )

        if not enabled:
            reason = "INVALID_ENABLED_FLAG" if not enabled_valid else None
            state = "MISCONFIGURED" if not enabled_valid else "NOT_CONFIGURED"
        elif not enabled_valid:
            state, reason = "MISCONFIGURED", "INVALID_ENABLED_FLAG"
        elif selector_malformed:
            state, reason = "MISCONFIGURED", "INVALID_ACCOUNT_SELECTOR_FORMAT"
        elif not all((dll_path, account, password, certificate_path, certificate_password, internal_service_token)):
            state, reason = "MISCONFIGURED", "MISSING_REQUIRED_SECRET"
        else:
            state, reason = "READY", None

        return ConfigSnapshot(
            enabled=enabled,
            state=state,
            presence=presence,
            reason=reason,
            dll_path=dll_path,
            account=account,
            password=password,
            certificate_path=certificate_path,
            certificate_password=certificate_password,
            stock_account_selector=stock_selector,
            futures_account_selector=futures_selector,
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
