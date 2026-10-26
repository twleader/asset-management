"""Pure configuration identity; only its digest is retained by stream consumers."""
from __future__ import annotations

import hashlib
from typing import Protocol


class SessionIdentityConfig(Protocol):
    @property
    def personal_id(self) -> str | None: ...
    @property
    def api_key(self) -> str | None: ...
    @property
    def certificate_password(self) -> str | None: ...
    @property
    def certificate_path(self) -> object | None: ...
    @property
    def account_branch_no(self) -> str | None: ...
    @property
    def account_number(self) -> str | None: ...
    @property
    def internal_service_token(self) -> str | None: ...


def session_config_digest(config: SessionIdentityConfig) -> bytes:
    joined = "\0".join(
        (
            config.personal_id or "",
            config.api_key or "",
            config.certificate_password or "",
            str(config.certificate_path or ""),
            config.account_branch_no or "",
            config.account_number or "",
            config.internal_service_token or "",
        )
    )
    return hashlib.sha256(joined.encode("utf-8")).digest()
