from __future__ import annotations

import logging
import re
import secrets
from collections.abc import Iterable


INTERNAL_TOKEN_HEADER = "X-Internal-Service-Token"


def token_matches(expected: str, provided: str) -> bool:
    """Constant-time comparison for the internal service token."""
    return secrets.compare_digest(expected.encode("utf-8"), provided.encode("utf-8"))


# --- Central redaction -------------------------------------------------
#
# Mirrors fubon-broker-service/src/fubon_broker_service/redaction.py. Kept in
# this module (rather than a separate redaction.py) because the yuanta task's
# file list (spec/tasks/t364_yuanta_broker_service.md §364.1) names exactly
# app.py / config.py / security.py / sdk_gateway.py / models.py, and
# redaction is itself a security concern.

_SENSITIVE_LABEL = re.compile(
    r"(?i)(account|password|branch[-_ ]?no|certificate(?:[-_ ]?(?:password|path))?|"
    r"cert[-_ ]?path|pfx(?:[-_ ]?path)?|internal[-_ ]?service[-_ ]?token|"
    r"stock[-_ ]?account[-_ ]?selector|futures[-_ ]?account[-_ ]?selector)\s*[:=]\s*[^\s,;}]+"
)


def redact(value: object, secret_values: Iterable[str | None] = ()) -> str:
    """Central last-resort redactor. Callers still log only stable reason codes."""
    text = str(value)
    for secret_value in secret_values:
        if secret_value:
            text = text.replace(secret_value, "[REDACTED]")
    return _SENSITIVE_LABEL.sub("[REDACTED]", text)


def redact_mapping(value: object, secret_values: Iterable[str | None] = ()) -> object:
    """Recursively redact string leaves of a JSON-like structure.

    Unlike redact(), this never stringifies the whole structure: dict keys,
    list order, and non-string leaves (int/bool/None/...) are preserved
    exactly, so response schemas are untouched. Only string leaf values pass
    through redact(); reason codes and other fixed literals never match the
    sensitive-label pattern and come back unchanged.
    """
    if isinstance(value, str):
        return redact(value, secret_values)
    if isinstance(value, dict):
        return {key: redact_mapping(item, secret_values) for key, item in value.items()}
    if isinstance(value, list):
        return [redact_mapping(item, secret_values) for item in value]
    if isinstance(value, tuple):
        return tuple(redact_mapping(item, secret_values) for item in value)
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
