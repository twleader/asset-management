"""Neutral adapter failures shared by read ports and their consumers."""
from __future__ import annotations

import threading


class SdkCallError(RuntimeError):
    def __init__(
        self,
        reason: str,
        *,
        auth_invalid: bool = False,
        misconfigured: bool = False,
        retry_after_seconds: float | None = None,
        completion_event: threading.Event | None = None,
    ) -> None:
        super().__init__(reason)
        self.reason = reason
        self.auth_invalid = auth_invalid
        self.misconfigured = misconfigured
        self.retry_after_seconds = retry_after_seconds
        # A bounded native SDK worker can outlive the caller that observed its
        # timeout.  The event is deliberately opaque and local-only; callers use
        # it solely to keep an admitted single-flight alive until the worker has
        # released its native slot in finally.
        self.completion_event = completion_event
