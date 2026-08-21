from __future__ import annotations

import pytest

from fubon_broker_service.numeric import MAX_SHARES, MAX_VOLUME, NumericError, canonical_decimal, exact_integer


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        (0.1, "0.1"),
        ("0.1", "0.1"),
        ("9999999999.9999999999", "9999999999.9999999999"),
    ],
)
def test_decimal_is_canonical_without_binary_artifact(raw, expected):
    assert canonical_decimal(raw) == expected
    assert "e" not in expected.lower()


@pytest.mark.parametrize(
    "raw",
    [
        "10000000000.0000000000",
        "0.00000000001",
        "1e2",
        float("nan"),
        float("inf"),
        -1,
        0,
    ],
)
def test_invalid_decimal_boundaries_are_rejected(raw):
    with pytest.raises(NumericError):
        canonical_decimal(raw)


@pytest.mark.parametrize("raw", [0, MAX_VOLUME, str(MAX_VOLUME)])
def test_volume_boundaries(raw):
    assert exact_integer(raw, upper=MAX_VOLUME) == int(raw)


@pytest.mark.parametrize("raw", [-1, MAX_VOLUME + 1, 1.0, "01", "1e2"])
def test_volume_overflow_and_non_exact_values_are_rejected(raw):
    with pytest.raises(NumericError):
        exact_integer(raw, upper=MAX_VOLUME)


@pytest.mark.parametrize("raw", [1, MAX_SHARES])
def test_share_boundaries(raw):
    assert exact_integer(raw, upper=MAX_SHARES) == raw
