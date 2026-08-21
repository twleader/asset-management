from __future__ import annotations

import re
from collections.abc import Iterable


_SENSITIVE_LABEL = re.compile(
    r"(?i)(personal[-_ ]?id|account(?:[-_ ]?number)?|api[-_ ]?key|"
    r"branch[-_ ]?no|certificate(?:[-_ ]?(?:password|path))?|cert[-_ ]?path|"
    r"pfx(?:[-_ ]?path)?|internal[-_ ]?service[-_ ]?token)\s*[:=]\s*[^\s,;}]+"
)


def redact(value: object, secrets: Iterable[str | None] = ()) -> str:
    """Central last-resort redactor. Callers still log only stable reason codes."""
    text = str(value)
    for secret in secrets:
        if secret:
            text = text.replace(secret, "[REDACTED]")
    return _SENSITIVE_LABEL.sub("[REDACTED]", text)
