#!/usr/bin/env python3
"""D-226 radar/LLM entry, original D-223 shared budget; never an order authority."""
from __future__ import annotations
import argparse
import copy
import datetime as dt
from decimal import Decimal, InvalidOperation
import hashlib
import json
from pathlib import Path
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parents[1]
PLAN_ID = 'D223_00881_CASH_2881_EXIT_20261118'
EXPECTED_POLICY = {'decision': 'D-223', 'enabled': True, 'plan_id': 'D223_00881_CASH_2881_EXIT_20261118', 'effective_date': '2026-11-18', 'market': '台股', 'buy_symbol': '00881', 'total_buy_budget_twd': '400000', 'budget_includes_buy_fees': True, 'budget_includes_pending_orders': True, 'budget_includes_immutable_reservations': True, 'sell_symbol': '2881', 'retained_symbols': ['2885'], 'buy_requires_prior_sell': False, 'sell_requires_prior_buy': False, 'batch_policy': 'EXISTING_QUOTA_AND_CAPACITY_NO_NEW_FIXED_SPLIT', 'missing_attribution_action': 'WAIT', 'reset_budget_automatically': False, 'original_gates_required': True, 'independent_existing_exits_unchanged': True, 'trade_authorization': False, 'entry_rule_decision': 'D-226', 'buy_radar_action': 'ADD_CANDIDATE', 'buy_requires_llm_adoption': True, 'sell_radar_actions': ['EXIT_CANDIDATE', 'REDUCE_CANDIDATE'], 'sell_requires_llm_adoption': True}


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


def radar_llm_entry(value, cfg, day, as_of, price, side='buy'):
    """Bind the new entry decision to exact same-round radar evidence.

    The common candidate audit independently checks every financial condition;
    an LLM adoption receipt alone never proves funding, risk or capacity.
    """
    source = value.get('radar_detail' if side == 'buy' else 'sell_radar_detail')
    require(isinstance(source, dict) and isinstance(source.get('payload'), dict)
            and isinstance(source.get('source_ref'), str) and bool(source['source_ref'].strip())
            and source.get('sha256') == fingerprint(source['payload']), 'D226_EXACT_RADAR_SOURCE_REQUIRED')
    body = source['payload']
    stock = body.get('stock', body)
    symbol = cfg['buy_symbol'] if side == 'buy' else cfg['sell_symbol']
    actions = {cfg['buy_radar_action']} if side == 'buy' else set(cfg['sell_radar_actions'])
    require(stock.get('stockCode') == symbol and stock.get('market') == cfg['market']
            and stock.get('asOfDate') == day and stock.get('action') in actions
            and stock.get('dataComplete') is True,
            'D226_RADAR_ADD_CANDIDATE_REQUIRED' if side == 'buy' else 'D226_RADAR_EXIT_OR_REDUCE_REQUIRED')
    age = (timestamp(as_of) - timestamp(body.get('generatedAt'))).total_seconds()
    # The signal and final executable quote may be different ticks. Entry is
    # based on the current radar action; the original quote/freshness/limit
    # gates, not equality to an earlier trade price, validate execution.
    require(0 <= age <= 300, 'D226_RADAR_QUOTE_NOT_SAME_ROUND')
    adoption = value.get('llm_adoption' if side == 'buy' else 'sell_llm_adoption')
    require(isinstance(adoption, dict) and adoption.get('decision') == 'ADOPT'
            and adoption.get('symbol') == symbol and adoption.get('plan_id') == cfg['plan_id']
            and adoption.get('trading_date') == day and adoption.get('as_of') == as_of
            and adoption.get('radar_sha256') == source['sha256']
            and isinstance(adoption.get('candidate_audit_sha256'), str)
            and len(adoption['candidate_audit_sha256']) == 64
            and isinstance(adoption.get('candidate_id'), str) and bool(adoption['candidate_id'].strip())
            and isinstance(adoption.get('reason'), str) and bool(adoption['reason'].strip())
            and isinstance(adoption.get('condition_evidence_refs'), list)
            and bool(adoption['condition_evidence_refs'])
            and all(isinstance(r, str) and bool(r.strip()) for r in adoption['condition_evidence_refs']),
            'D226_LLM_ADOPTION_REQUIRED')
    return adoption['candidate_id']


def adoption_context(facts, candidate_id):
    """Return the exact same-round conditions the LLM must explicitly adopt.

    This emits no ADOPT decision. Changing any candidate gate, source reference,
    package, cutoff or audited debit requires a fresh LLM judgment.
    """
    from trade_blocker_policy import audit_facts, PURPOSE_GATES
    candidates = [c for c in facts.get('candidate_evidence', []) if c.get('id') == candidate_id]
    require(len(candidates) == 1 and (candidates[0].get('symbol'), candidates[0].get('purpose')) in
            {('00881', 'EQUITY_BUY'), ('2881', 'CORE_SELL')}, 'D226_ADOPTION_CANDIDATE_REQUIRED')
    candidate = candidates[0]
    if candidate['symbol'] == '2881':
        from named_radar_sell import validate_final_quote
        validate_final_quote(facts)
    audited = [r for r in audit_facts(facts)['candidates'] if r['id'] == candidate_id]
    require(len(audited) == 1 and audited[0]['outcome'] == 'ELIGIBLE_FOR_REVIEW',
            'D226_ADOPTION_CONDITIONS_NOT_PASSED')
    refs = sorted({candidate['gates'][gate]['evidence_ref'] for gate in PURPOSE_GATES[candidate['purpose']]})
    context = {key: facts.get(key) for key in ('trading_date', 'slot', 'consumer', 'cutoff_at', 'policy_bundle_sha256')}
    require(all(isinstance(v, str) and bool(v.strip()) for v in context.values()), 'D226_ADOPTION_CONTEXT_REQUIRED')
    context.update(candidate=candidate, audited_candidate=audited[0],
                   api_package=facts.get('api_calculations', {}).get('input_facts', {}),
                   final_sources={key: facts.get(key) for key in ('assets', 'quotes', 'radar',
                       'core_retention_d216', 'funding_commitments')})
    plan = facts.get('named_cash_rotation_d223', {}).get('input')
    require(isinstance(plan, dict) and plan.get('trading_date') == facts['trading_date']
            and plan.get('as_of') == facts['cutoff_at'], 'D226_ADOPTION_PLAN_SOURCES_REQUIRED')
    keys = ('ledger', 'settled_unrestricted_cash_twd', 'radar_detail') if candidate['symbol'] == '00881' else ('sell_radar_detail',)
    context['named_plan_sources'] = {key: plan.get(key) for key in keys}
    return {'candidate_audit_sha256': fingerprint(context), 'condition_evidence_refs': refs}


def with_plan_context(facts, *, clear_buy_budget=False):
    """Attach direct same-round sources; either leg can proceed independently."""
    result=copy.deepcopy(facts)
    value=copy.deepcopy(result.get('named_cash_rotation_d223',{}).get('input',{}))
    if clear_buy_budget:
        for field in ('ledger','settled_unrestricted_cash_twd','llm_adoption'):
            value.pop(field,None)
    value.update(trading_date=result['trading_date'],as_of=result['cutoff_at'],assets=result.get('assets'),quotes=result.get('quotes'))
    for symbol,field in (('00881','radar_detail'),('2881','sell_radar_detail')):
        body=result.get('radar',{}).get('details',{}).get(symbol)
        if isinstance(body,dict):
            value[field]={'source_ref':'same-run:direct-radar:'+symbol,'payload':body,'sha256':fingerprint(body)}
    result['named_cash_rotation_d223']={'input':value,'result':evaluate(value)}
    return result


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
        candidate_id = radar_llm_entry(value, cfg, day, as_of, price)
        used = ledger_usage(value.get('ledger'), cfg, day, as_of)
        cash = amount(value.get('settled_unrestricted_cash_twd'))
        remaining = amount(cfg['total_buy_budget_twd']) - used
        ceiling = min(remaining, cash)
        require(ceiling > 0, 'D223_NO_REMAINING_BUDGET_OR_CASH')
        result['buy'].update(status='RADAR_LLM_AND_BUDGET_CANDIDATE', candidate_id=candidate_id,
                             entry_rule_decision=cfg['entry_rule_decision'], used_budget_twd=str(used),
                             remaining_budget_twd=str(remaining), debit_ceiling_twd=str(ceiling))
    except (ValueError, KeyError, TypeError) as error:
        result['buy']['reason'] = str(error)
    try:
        price = quote_price(value.get('quotes'), cfg['sell_symbol'], day)
        candidate_id = radar_llm_entry(value, cfg, day, as_of, price, side='sell')
        result['sell'].update(status='RADAR_LLM_CANDIDATE', candidate_id=candidate_id,
                             entry_rule_decision=cfg['entry_rule_decision'],
                             quantity_retention_and_execution_gates_required=True)
    except (ValueError, KeyError, TypeError) as error:
        result['sell']['reason'] = str(error)
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input', type=Path, help='plan request or, with --candidate-id, same-round report facts')
    parser.add_argument('--candidate-id', help='print exact condition binding only; never creates LLM adoption')
    args = parser.parse_args()
    value = json.loads(args.input.read_text())
    result = adoption_context(value, args.candidate_id) if args.candidate_id else evaluate(value)
    print(json.dumps(result, ensure_ascii=False, indent=2))
