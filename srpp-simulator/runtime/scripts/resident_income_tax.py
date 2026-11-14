"""Pure 115-year Taiwan resident progressive tax primitives; no I/O clients."""
from __future__ import annotations
from decimal import Decimal
from typing import Any

TAIWAN_TAX_BRACKETS = (
    (Decimal('610000'), Decimal('0.05')),
    (Decimal('1380000'), Decimal('0.12')),
    (Decimal('2770000'), Decimal('0.20')),
    (Decimal('5190000'), Decimal('0.30')),
    (Decimal('Infinity'), Decimal('0.40')),
)
TAIWAN_PERSONAL_EXEMPTION_TWD = Decimal('101000')
TAIWAN_PERSONAL_EXEMPTION_AGE_70_TWD = Decimal('151500')
TAIWAN_STANDARD_DEDUCTION_SINGLE_TWD = Decimal('136000')
TAIWAN_SALARY_DEDUCTION_TWD = Decimal('227000')
TAIWAN_115_TAX_BRACKET_SOURCE = 'https://www.mof.gov.tw/singlehtml/384fb3077bb349ea973e7fc6f13b6974?cntId=34b463dc8f1b49f29d440d92a6fd5139'


def finite_decimal(value: Any, *, label: str) -> Decimal:
    if value is None or isinstance(value, bool):
        raise ValueError(f'{label} must be numeric, got {value!r}')
    try:
        result = Decimal(str(value))
        if not result.is_finite():
            raise ValueError(f'{label} must be finite')
        return result
    except Exception as exc:
        raise ValueError(f'{label} must be numeric, got {value!r}') from exc


def progressive_slices(taxable_income_after_deductions: Decimal | float) -> list[dict]:
    taxable = max(finite_decimal(taxable_income_after_deductions, label='taxable income'), Decimal(0))
    rows = []
    lower = Decimal(0)
    for upper, rate in TAIWAN_TAX_BRACKETS:
        amount = max(Decimal(0), taxable - lower if upper.is_infinite() else min(taxable, upper) - lower)
        rows.append(dict(lower=lower, upper=None if upper.is_infinite() else upper,
                         rate=rate, taxable_slice=amount, tax=amount * rate))
        lower = upper
    return rows


def progressive_tax(taxable_income_after_deductions: Decimal | float) -> Decimal:
    """Official 2026 resident progressive tax before credits; historical API."""
    taxable = max(finite_decimal(taxable_income_after_deductions, label='taxable income'), Decimal(0))
    tax = Decimal(0)
    lower = Decimal(0)
    for upper, rate in TAIWAN_TAX_BRACKETS:
        if taxable <= lower:
            break
        amount = taxable - lower if upper.is_infinite() else min(taxable, upper) - lower
        if amount > 0:
            tax += amount * rate
        lower = upper
    return tax
