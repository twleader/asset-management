"""Money encoding at the scenario wire boundary; no trading or I/O."""
from decimal import Decimal, ROUND_HALF_UP, localcontext
import numpy as np
from settings_catalog import SettingsError


def money(value):
    with localcontext() as context:
        context.prec = 400
        amount = value if isinstance(value, Decimal) else Decimal(str(value))
        if not amount.is_finite():
            raise SettingsError('NUMERIC_RESULT_INVALID','模擬金額不是有限數值。')
        rounded=amount.quantize(Decimal('0.01'), rounding=ROUND_HALF_UP)
        if rounded.is_zero():rounded=Decimal('0.00')
        return format(rounded,'f')


def money_difference(left,right):
    with localcontext() as context:
        context.prec=400
        return money(Decimal(left)-Decimal(right))


def quantiles(values,monetary=True):
    qs=np.quantile(values,[.1,.5,.9])
    return dict(zip(('p10','p50','p90'),[money(float(v)) if monetary else round(float(v),2) for v in qs]))
