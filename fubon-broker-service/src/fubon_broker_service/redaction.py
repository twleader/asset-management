from __future__ import annotations

import logging
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


def redact_mapping(value: object, secrets: Iterable[str | None] = ()) -> object:
    """Recursively redact string leaves of a JSON-like structure.

    Unlike redact(), this never stringifies the whole structure: dict keys,
    list order, and non-string leaves (int/bool/None/...) are preserved
    exactly, so response schemas are untouched. Only string leaf values pass
    through redact(); reason codes and other fixed literals never match the
    sensitive-label pattern and come back unchanged.
    """
    if isinstance(value, str):
        return redact(value, secrets)
    if isinstance(value, dict):
        return {key: redact_mapping(item, secrets) for key, item in value.items()}
    if isinstance(value, list):
        return [redact_mapping(item, secrets) for item in value]
    if isinstance(value, tuple):
        return tuple(redact_mapping(item, secrets) for item in value)
    return value


class RedactingLogFilter(logging.Filter):
    """Central last line of defense for logging: redact every record's rendered
    message before it reaches any handler, regardless of what the call site
    passed in. Callers are still expected to log only stable reason codes;
    this filter is the backstop for the case where they don't.
    """

    def filter(self, record: logging.LogRecord) -> bool:
        try:
            rendered = record.getMessage()
        except Exception:  # malformed %-args must never crash logging itself
            rendered = str(record.msg)
        record.msg = redact(rendered)
        record.args = ()
        return True


def install_log_redaction(*loggers: logging.Logger) -> None:
    """Attach the central redaction filter to each logger, once each."""
    for target in loggers:
        if not any(isinstance(existing, RedactingLogFilter) for existing in target.filters):
            target.addFilter(RedactingLogFilter())
