from __future__ import annotations

import re

from pydantic import BaseModel, ConfigDict, StrictStr, model_validator


_DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")


def _valid_date(value: str) -> bool:
    return bool(_DATE.fullmatch(value))


class AccountingReadRequest(BaseModel):
    """Shared, field-less request body for the five accounting/report reads that
    take no filter (inventory-stock, inventory-futures, unrealized-pnl,
    settlement, futures-margin). Callers still send `{}` — `extra=forbid`
    means any unexpected field is rejected as 400 rather than silently ignored.
    """

    model_config = ConfigDict(extra="forbid", strict=True)


class RealizedPnlRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    startDate: StrictStr
    endDate: StrictStr

    @model_validator(mode="after")
    def _validate_dates(self) -> "RealizedPnlRequest":
        if not _valid_date(self.startDate) or not _valid_date(self.endDate):
            raise ValueError("INVALID_DATE_FORMAT")
        if self.startDate > self.endDate:
            raise ValueError("START_AFTER_END")
        return self


class OrderExecutionReportRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    startDate: StrictStr | None = None
    endDate: StrictStr | None = None

    @model_validator(mode="after")
    def _validate_dates(self) -> "OrderExecutionReportRequest":
        if (self.startDate is None) != (self.endDate is None):
            raise ValueError("INCOMPLETE_DATE_RANGE")
        if self.startDate is not None and self.endDate is not None:
            if not _valid_date(self.startDate) or not _valid_date(self.endDate):
                raise ValueError("INVALID_DATE_FORMAT")
            if self.startDate > self.endDate:
                raise ValueError("START_AFTER_END")
        return self


class CodesRequest(BaseModel):
    """Shared body for /market-data/quote and /market-data/five-best.

    `codes` bounds are re-checked *after* de-duplication and normalization by
    the gateway (see sdk_gateway.normalize_codes) — this model only rejects
    the raw shape being obviously empty or absurdly large before that.
    """

    model_config = ConfigDict(extra="forbid", strict=True)
    codes: list[StrictStr]


class SingleCodeRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    code: StrictStr


class KlineRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    code: StrictStr
    interval: StrictStr
    count: int | None = None
    startDate: StrictStr | None = None
    endDate: StrictStr | None = None

    @model_validator(mode="after")
    def _validate_pagination(self) -> "KlineRequest":
        by_count = self.count is not None
        by_range = self.startDate is not None or self.endDate is not None
        if by_count == by_range:
            # Neither pagination mode given, or both given at once — ambiguous.
            raise ValueError("EXACTLY_ONE_PAGINATION_MODE_REQUIRED")
        if by_count and self.count <= 0:
            raise ValueError("INVALID_COUNT")
        if by_range:
            if self.startDate is None or self.endDate is None:
                raise ValueError("INCOMPLETE_DATE_RANGE")
            if not _valid_date(self.startDate) or not _valid_date(self.endDate):
                raise ValueError("INVALID_DATE_FORMAT")
            if self.startDate > self.endDate:
                raise ValueError("START_AFTER_END")
        return self
