"""Official read-only order results. No recommendation/order/fill link is inferred.

Source: https://www.fbs.com.tw/TradeAPI/docs/trading/library/python/trade/GetOrderResults/
Status meanings: https://www.fbs.com.tw/TradeAPI/docs/trading/library/python/EnumMatrix/#status
"""
from __future__ import annotations

import hashlib
import json
import re
from datetime import datetime
from typing import Callable

from .normalization import strict_vendor_date
from .numeric import MAX_SHARES, NumericError, canonical_decimal, exact_integer
from zoneinfo import ZoneInfo
from .order_results_port import OrderResultsError, OrderResultsPort
TW_ZONE = ZoneInfo("Asia/Taipei")


class OrderResultsService:
    def __init__(self, gateway: OrderResultsPort, now: Callable[[], datetime] | None = None) -> None:
        self._gateway = gateway
        self._now = now or (lambda: datetime.now(TW_ZONE))

    def read(self) -> dict[str, object]:
        started = self._now().astimezone(TW_ZONE)
        observation = self._gateway.read_order_portfolio(started.date())
        completed = self._now().astimezone(TW_ZONE)
        if completed.date() != started.date():
            raise OrderResultsError("QUERY_DATE_ROLLOVER")
        if observation.query_date != started.date():
            raise OrderResultsError("STALE_SOURCE_DATE")
        rows = observation.orders
        portfolio = dict(observation.portfolio)
        fingerprint = observation.account_fingerprint
        orders = [self._normalize(row, started.date(), fingerprint) for row in rows]
        ids = [row["order_no"] for row in orders]
        if len(set(ids)) != len(ids):
            raise OrderResultsError("DUPLICATE_ORDER_ID")
        orders.sort(key=lambda row: row["order_no"])
        active = sum(row["active"] for row in orders)
        payload = {
            "schemaVersion": 1, "broker": "fubon", "queryDate": started.date().isoformat(),
            "accountFingerprint": fingerprint, "capturedAt": completed.isoformat(),
            "complete": True, "verifiedZero": active == 0, "activeCount": active,
            "orders": orders, "portfolio": portfolio,
        }
        payload["revision"] = hashlib.sha256(json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()
        return payload

    @staticmethod
    def _normalize(row, query_date, fingerprint) -> dict[str, object]:
        def reject(reason):
            raise OrderResultsError(reason)
        try:
            if strict_vendor_date(row.get("date")) != query_date:
                reject("STALE_SOURCE_DATE")
        except (TypeError, ValueError):
            reject("INVALID_SOURCE_DATE")
        symbol = row.get("stock_no")
        order_id = row.get("order_no")
        sequence = row.get("seq_no")
        if not isinstance(symbol, str) or not re.fullmatch(r"[0-9A-Z]{2,10}", symbol):
            reject("INVALID_STOCK_CODE")
        if not isinstance(order_id, str) or not re.fullmatch(r"[A-Za-z0-9]{1,32}", order_id):
            reject("INVALID_ORDER_ID")
        if not isinstance(sequence, str) or not re.fullmatch(r"[A-Za-z0-9]{1,32}", sequence):
            reject("INVALID_SEQUENCE")
        enum = lambda name: row.get(name)
        market_type, side, order_type = enum("market_type"), enum("buy_sell"), enum("order_type")
        price_type, after_price_type = enum("price_type"), enum("after_price_type")
        market, tif = row.get("market"), enum("time_in_force")
        if market_type not in {"Common", "IntradayOdd", "Odd"} or market not in {"TAIEX", "TAISDAQ"} or order_type != "Stock":
            reject("UNSUPPORTED_ORDER_TYPE")
        if side not in {"Buy", "Sell"} or price_type != "Limit" or after_price_type not in {None, "Limit"} or tif not in {"ROD", "FOK", "IOC"}:
            reject("UNSUPPORTED_ORDER_TYPE")
        if row.get("is_pre_order") is not False or row.get("asset_type") != 0:
            reject("UNSUPPORTED_ORDER_TYPE")
        try:
            status = exact_integer(row.get("status"), upper=99)
            original = exact_integer(row.get("quantity"), upper=MAX_SHARES)
            effective = exact_integer(row.get("after_qty"), upper=MAX_SHARES)
            filled = exact_integer(row.get("filled_qty"), upper=MAX_SHARES)
            unit = exact_integer(row.get("unit"), upper=1000)
            price = canonical_decimal(row.get("price"), positive=True)
            after_price = canonical_decimal(row.get("after_price"), positive=True)
            filled_money = exact_integer(row.get("filled_money"), upper=9007199254740991)
        except NumericError as exc:
            reject(str(exc))
        if status not in {0, 10, 30, 40, 50, 90}:
            reject("ORDER_STATUS_UNVERIFIED")
        if original <= 0 or effective > original or filled > effective:
            reject("ORDER_QUANTITY_INVALID")
        if unit != (1000 if market_type == "Common" else 1):
            reject("ORDER_UNIT_INVALID")
        if status == 30 and filled != 0 or status == 40 and filled == 0 or status == 50 and effective != filled:
            reject("ORDER_STATUS_QUANTITY_MISMATCH")
        last_time = row.get("last_time")
        if not isinstance(last_time, str) or not re.fullmatch(r"(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](?:\.[0-9]{3})?", last_time):
            reject("ORDER_TIME_INVALID")
        active = status in {0, 10} and effective > filled
        # The original official fields are retained except direct account identifiers,
        # personal/error free text, and unbounded history. Identity is replaced by HMAC.
        return {"date": query_date.isoformat(), "accountFingerprint": fingerprint,
                "seq_no": sequence, "order_no": order_id, "asset_type": 0,
                "market": market, "market_type": market_type, "stock_no": symbol,
                "buy_sell": side, "price_type": price_type, "price": price,
                "quantity": original, "time_in_force": tif, "order_type": order_type,
                "is_pre_order": False, "status": status, "after_price_type": after_price_type,
                "after_price": after_price, "unit": unit, "after_qty": effective,
                "filled_qty": filled, "filled_money": filled_money, "last_time": last_time,
                "active": active, "remainingQty": effective - filled if active else 0,
                "recommendationLegId": None, "fillIds": []}
