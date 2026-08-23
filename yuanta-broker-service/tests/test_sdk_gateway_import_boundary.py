"""Static regression guard for CLAUDE.md〈券商 API 只能查詢，不得交易〉— the
highest-priority, project-wide rule that no broker API integration may ever
place, modify, or cancel an order.

This scans sdk_gateway.py's own *source text* (not by importing pythonnet/clr
— this test suite never does that) for anything resembling an order-placement
symbol, so that a future, well-meaning contributor who adds
`self._api.SendOrder(...)` to PythonNetYuantaSparkGateway to "complete" the
adapter gets caught by CI before it ships.
"""

from __future__ import annotations

import re
from pathlib import Path

import yuanta_broker_service.sdk_gateway as sdk_gateway_module


SOURCE_PATH = Path(sdk_gateway_module.__file__)

# Exact symbols the task explicitly names as forbidden.
EXPLICITLY_BANNED_SYMBOLS = (
    "SendFutureCombined",
    "GetFutDepositOptimum",
)

# Common naming conventions for order placement/modification/cancellation
# across broker SDKs. Matched as whole identifiers (word boundaries) so this
# does not false-positive on unrelated words.
BANNED_IDENTIFIER_PATTERNS = (
    r"\bSend[A-Z]\w*",  # e.g. SendOrder, SendFutureOrder, SendCombo
    r"\bPlaceOrder\w*",
    r"\bModifyOrder\w*",
    r"\bCancelOrder\w*",
    r"\bNewOrder\w*",
    r"\bDeleteOrder\w*",
    r"\bReplaceOrder\w*",
)


def _source_text() -> str:
    return SOURCE_PATH.read_text(encoding="utf-8")


def test_sdk_gateway_source_contains_no_explicitly_banned_symbols():
    source = _source_text()
    for banned in EXPLICITLY_BANNED_SYMBOLS:
        assert banned not in source, f"forbidden trading symbol found in sdk_gateway.py: {banned}"


def test_sdk_gateway_source_contains_no_order_placement_identifier_patterns():
    source = _source_text()
    for pattern in BANNED_IDENTIFIER_PATTERNS:
        match = re.search(pattern, source)
        assert match is None, f"forbidden order-placement-shaped identifier found: {match.group(0) if match else pattern}"


def test_module_never_imports_pythonnet_or_clr_at_top_level():
    """Config/module-load must stay lazy (Task 364.4) — pythonnet's `clr` may
    only be imported inside a method body, never at module import time, so
    importing this module in the test image (which has no .NET runtime)
    never fails."""
    tree_source = _source_text()
    module_level_lines = []
    indent_depth = 0
    for line in tree_source.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        current_indent = len(line) - len(line.lstrip(" "))
        if current_indent == 0:
            module_level_lines.append(stripped)
    for line in module_level_lines:
        assert not line.startswith("import clr"), "clr must only be imported lazily inside a method"
        assert "import pythonnet" not in line


def test_importing_sdk_gateway_does_not_require_pythonnet_installed():
    """The module itself must already be imported successfully by the time
    this test runs (see the module-level import above) without pythonnet's
    `clr` submodule ever having been touched — this assertion just documents
    that expectation explicitly for future readers."""
    assert sdk_gateway_module.PythonNetYuantaSparkGateway is not None
