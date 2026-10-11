"""Outer SDK boundary: account/raw identity checks and native-to-neutral capture."""
from __future__ import annotations
from datetime import date
import re
from .order_results_port import OrderPortfolioCapture, OrderResultsError
from .portfolio import PortfolioService, PortfolioError
from .sdk_gateway import SdkGateway, SdkCallError, raw_field, enum_text

_FIELDS = ('date','seq_no','order_no','asset_type','market','market_type','stock_no','buy_sell',
           'price_type','price','quantity','time_in_force','order_type','is_pre_order','status',
           'after_price_type','after_price','unit','after_qty','filled_qty','filled_money','last_time')
_ENUMS = {'market_type','buy_sell','price_type','time_in_force','order_type','after_price_type'}

def _native_enum(value: object) -> str | None:
    result=enum_text(value,allow_native_portfolio_text=True)
    if result is not None:return result
    if value is None:return None
    try:text=str(value)
    except Exception:return None
    match=re.fullmatch(r'(?:MarketType|PriceType|TimeInForce)\.([A-Za-z_][A-Za-z0-9_]*)',text)
    return match.group(1) if match else None

class OrderResultsGatewayAdapter:
    def __init__(self, gateway: SdkGateway) -> None:
        self._gateway=gateway
    def read_order_portfolio(self, query_date: date) -> OrderPortfolioCapture:
        try:
            observation=self._gateway.read_order_portfolio()
            read,pair=observation.orders,observation.portfolio
            if raw_field(read.response,'is_success') is not True:
                raise OrderResultsError('ORDER_RESULTS_FAILED')
            rows=raw_field(read.response,'data')
            if not isinstance(rows,list) or len(rows)>10000:
                raise OrderResultsError('ORDER_RESULTS_DATA_INVALID')
            if (pair.account.branch_no,pair.account.account_number)!=(read.account.branch_no,read.account.account_number):
                raise OrderResultsError('ORDER_PORTFOLIO_ACCOUNT_MISMATCH')
            portfolio=PortfolioService(self._gateway).normalize(pair,query_date)
            fingerprint=PortfolioService._hmac_fingerprint(read.internal_token,read.account.branch_no,read.account.account_number)
            if portfolio['accountFingerprint']!=fingerprint:
                raise OrderResultsError('ORDER_PORTFOLIO_ACCOUNT_MISMATCH')
            neutral=[]
            for row in rows:
                if raw_field(row,'account')!=read.account.account_number or raw_field(row,'branch_no')!=read.account.branch_no:
                    raise OrderResultsError('WRONG_SOURCE_ACCOUNT')
                result={name:(_native_enum(raw_field(row,name)) if name in _ENUMS else raw_field(row,name)) for name in _FIELDS}
                # No SDK/native object, account, token or arbitrary free text crosses the port.
                if any(value is not None and type(value) not in (str,int,float,bool) for value in result.values()):
                    raise OrderResultsError('ORDER_NATIVE_FIELD_INVALID')
                neutral.append(result)
            return OrderPortfolioCapture(query_date,fingerprint,tuple(neutral),portfolio)
        except (SdkCallError,PortfolioError) as error:
            raise OrderResultsError(error.reason) from None
