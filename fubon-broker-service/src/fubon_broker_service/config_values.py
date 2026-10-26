"""Immutable configuration observations, independent of mounted-file loading."""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path


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
    taiex_index_stream_enabled: bool = False
    taiex_index_symbol: str | None = None
    taiex_index_stream_reason: str | None = None
    stock_push_enabled: bool = False
    stock_push_reason: str | None = None

    def __repr__(self) -> str:
        return f"ConfigSnapshot(enabled={self.enabled!r}, state={self.state!r}, reason={self.reason!r})"
