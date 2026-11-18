#!/usr/bin/env python3
"""D-223 same-plan budget and independent price gates; never an order authority."""
from __future__ import annotations
import argparse
import datetime as dt
from decimal import Decimal, InvalidOperation
import hashlib
import json
from pathlib import Path
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parents[1]
PLAN_ID = 'D223_00881_CASH_2881_EXIT_20261118'
EXPECTED_POLICY = {'decision': 'D-223', 'enabled': True, 'plan_id': 'D223_00881_CASH_2881_EXIT_20261118', 'effective_date': '2026-11-18', 'market': '台股', 'buy_symbol': '00881', 'buy_price_exclusive_upper_twd': '38', 'total_buy_budget_twd': '400000', 'budget_includes_buy_fees': True, 'budget_includes_pending_orders': True, 'budget_includes_immutable_reservations': True, 'sell_symbol': '2881', 'sell_price_exclusive_lower_twd': '152', 'retained_symbols': ['2885'], 'buy_requires_prior_sell': False, 'sell_requires_prior_buy': False, 'batch_policy': 'EXISTING_QUOTA_AND_CAPACITY_NO_NEW_FIXED_SPLIT', 'missing_attribution_action': 'WAIT', 'reset_budget_automatically': False, 'original_gates_required': True, 'independent_existing_exits_unchanged': True, 'trade_authorization': False}


def require(ok, reason):
    if not ok:
        raise ValueError(reason)


def amount(value):
    require(not isinstance(value, bool) and isinstance(value, (str, int, float)), 'D223_AMOUNT_UNVERIFIED')
    try:
        value = Decimal(str(value))
    except InvalidOperation:
        raise ValueError('D223_AMOUNT_UNVERIFIED')
    require(value.is_finite() and value >= 0, 'D223_AMOUNT_UNVERIFIED')
    return value


def timestamp(value):
    require(isinstance(value, str), 'D223_CLOCK_UNVERIFIED')
    result = dt.datetime.fromisoformat(value.replace('Z', '+00:00'))
    require(result.tzinfo is not None, 'D223_CLOCK_UNVERIFIED')
    return result


def fingerprint(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False,
                                    separators=(',', ':'), allow_nan=False).encode()).hexdigest()


def ledger_usage(ledger, cfg, trading_date, as_of):
    require(isinstance(ledger, dict) and ledger.get('plan_id') == cfg['plan_id'], 'D223_PLAN_LEDGER_REQUIRED')
    require(ledger.get('coverage_start') == cfg['effective_date']
            and ledger.get('transactions_complete') is True
            and ledger.get('pending_orders_complete') is True,
            'D223_LEDGER_COVERAGE_UNVERIFIED')
    require(timestamp(ledger.get('evidence_as_of')) == timestamp(as_of), 'D223_LEDGER_CLOCK_MISMATCH')
    sources = ledger.get('source_refs')
    require(isinstance(sources, list) and sources and all(isinstance(s, str) and s.strip() for s in sources)
            and len(sources) == len(set(sources)), 'D223_LEDGER_SOURCE_REQUIRED')
    # Complete flags alone cannot establish a known zero. Bind coverage and
    # accounting to the actual supplied source projections and their hashes.
    # The parent must still verify those projections against the official raw
    # transaction/order source; a canonical hash proves consistency only.
    evidence = ledger.get('source_evidence')
    require(isinstance(evidence, dict) and set(evidence) == {'transactions', 'pending_orders', 'attribution'},
            'D223_RAW_LEDGER_SOURCES_REQUIRED')
    source_rows = []
    attribution = {}
    for scope, wrapper in evidence.items():
        require(isinstance(wrapper, dict) and set(wrapper) == {'source_ref', 'sha256', 'payload'}
                and wrapper['source_ref'] in sources and isinstance(wrapper['payload'], dict)
                and wrapper['sha256'] == fingerprint(wrapper['payload']), 'D223_RAW_LEDGER_HASH_MISMATCH')
        payload = wrapper['payload']
        require(payload.get('scope') == scope and payload.get('plan_id') == cfg['plan_id']
                and payload.get('coverage_start') == cfg['effective_date']
                and payload.get('coverage_end') == as_of and payload.get('complete') is True
                and isinstance(payload.get('rows'), list), 'D223_RAW_LEDGER_COVERAGE_UNVERIFIED')
        if scope == 'attribution':
            for row in payload['rows']:
                require(isinstance(row, dict) and isinstance(row.get('id'), str)
                        and row['id'] not in attribution and isinstance(row.get('plan_id'), str)
                        and row['plan_id'].strip(), 'D223_FILL_ATTRIBUTION_UNVERIFIED')
                attribution[row['id']] = row['plan_id']
        else:
            source_rows.extend(payload['rows'])
    entries = ledger.get('entries')
    require(isinstance(entries, list), 'D223_LEDGER_ENTRIES_REQUIRED')
    require(sorted(entries, key=fingerprint) == sorted(source_rows, key=fingerprint), 'D223_LEDGER_SOURCE_ROWS_MISMATCH')
    used, ids = Decimal(0), set()
    for row in entries:
        require(isinstance(row, dict) and isinstance(row.get('id'), str) and row['id']
                and row['id'] not in ids and row.get('source_ref') in sources, 'D223_LEDGER_ID_OR_SOURCE_UNVERIFIED')
        ids.add(row['id'])
        require(row.get('stock_code') == cfg['buy_symbol'] and row.get('side') == 'BUY', 'D223_LEDGER_SCOPE_INVALID')
        require(isinstance(row.get('plan_id'), str) and row['plan_id'].strip()
                and attribution.get(row['id']) == row['plan_id'], 'D223_FILL_ATTRIBUTION_UNVERIFIED')
        require(row.get('status') in ('FILLED', 'PENDING', 'RESERVED', 'CANCELED'), 'D223_LEDGER_STATUS_UNVERIFIED')
        debit = amount(row.get('cash_debit_twd'))
        # A canceled order can release only its unfilled reservation; actual fill
        # and fee debits must remain separate FILLED rows, never disappear.
        if row['plan_id'] == cfg['plan_id']:
            used += debit
    require(used <= amount(cfg['total_buy_budget_twd']), 'D223_BUDGET_ALREADY_EXCEEDED')
    return used


def quote_price(quotes, code, trading_date):
    require(isinstance(quotes, dict), 'D223_QUOTE_REQUIRED')
    q = quotes.get(code)
    require(isinstance(q, dict) and q.get('stockCode') == code and q.get('market') == '台股'
            and q.get('tradingDate') == trading_date, 'D223_QUOTE_IDENTITY_UNVERIFIED')
    price = amount(q.get('price', q.get('currentPrice')))
    require(price > 0, 'D223_QUOTE_PRICE_UNVERIFIED')
    return price


def evaluate(value, model=None):
    if model is None:
        model = json.loads((ROOT / 'assumptions/model_inputs.json').read_text())
    cfg = model['execution_policy']['named_cash_rotation_d223']
    require(cfg == EXPECTED_POLICY, 'D223_POLICY_UNVERIFIED')
    day, as_of = value['trading_date'], value['as_of']
    require(dt.date.fromisoformat(day).isoformat() == day and day >= cfg['effective_date']
            and timestamp(as_of).astimezone(ZoneInfo('Asia/Taipei')).date().isoformat() == day,
            'D223_DATE_UNVERIFIED')
    result = {'decision': 'D-223', 'plan_id': cfg['plan_id'], 'trading_date': day,
              'input_sha256': fingerprint(value), 'trade_authorization': False,
              'original_gates_required': True, 'retained_symbols': cfg['retained_symbols'],
              'buy': {'symbol': cfg['buy_symbol'], 'status': 'WAIT'},
              'sell': {'symbol': cfg['sell_symbol'], 'status': 'WAIT'}}
    # Evaluate separately: missing seller quote or budget evidence must not erase
    # a qualified buyer, and an unavailable buy ledger never blocks seller review.
    try:
        price = quote_price(value.get('quotes'), cfg['buy_symbol'], day)
        require(price < amount(cfg['buy_price_exclusive_upper_twd']), 'D223_BUY_PRICE_NOT_BELOW_38')
        used = ledger_usage(value.get('ledger'), cfg, day, as_of)
        cash = amount(value.get('settled_unrestricted_cash_twd'))
        remaining = amount(cfg['total_buy_budget_twd']) - used
        ceiling = min(remaining, cash)
        require(ceiling > 0, 'D223_NO_REMAINING_BUDGET_OR_CASH')
        result['buy'].update(status='PRICE_AND_BUDGET_CANDIDATE', used_budget_twd=str(used),
                             remaining_budget_twd=str(remaining), debit_ceiling_twd=str(ceiling))
    except (ValueError, KeyError, TypeError) as error:
        result['buy']['reason'] = str(error)
    try:
        price = quote_price(value.get('quotes'), cfg['sell_symbol'], day)
        require(price > amount(cfg['sell_price_exclusive_lower_twd']), 'D223_SELL_PRICE_NOT_ABOVE_152')
        result['sell'].update(status='PRICE_CANDIDATE', original_core_sell_gates_required=True)
    except (ValueError, KeyError, TypeError) as error:
        result['sell']['reason'] = str(error)
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input', type=Path)
    args = parser.parse_args()
    print(json.dumps(evaluate(json.loads(args.input.read_text())), ensure_ascii=False, indent=2))
