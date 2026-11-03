#!/usr/bin/env python3
"""Generate de-identified US calculator parity cases from the authoritative SRPP Python functions."""
import argparse
import copy
import datetime as dt
import json
import sys
from pathlib import Path


def generate(root):
    sys.path.insert(0, str(root / 'scripts'))
    import core_sell_guard as core
    import us_tactical_order_check as tactical
    from test_us_core_sell_guard import UsCoreSellGuardTests
    model = json.loads((root / 'assumptions/model_inputs.json').read_text())
    minimal = {'execution_policy': {key: model['execution_policy'][key] for key in (
        'core_sell_guard_d174', 'us_megacap_tactical_policy_d133')}}
    bundle, state, policy = UsCoreSellGuardTests().fixture()
    calendar = json.loads((root / 'data/input/交易日曆_2026.json').read_text())['days']
    bundle['calendar'] = [{'date': row['date'], 'us': row['us']} for row in calendar
                          if '2026-09-01' <= row['date'] <= bundle['trading_date']]
    bundle['session'] = {'open': '2026-10-26T13:30:00Z', 'close': '2026-10-26T20:00:00Z'}
    cases = []
    def add(name, edit=None, *, retained=True):
        current, before = copy.deepcopy(bundle), copy.deepcopy(state)
        if edit:
            edit(current, before)
        states = {before['stock_code']: before} if retained else {}
        expected, after = core.screen(current, policy, states, root)
        cases.append({'name': name, 'input': current, 'ledger': {'decision': 'D-174', 'consumer': 'Codex', 'states': states},
                      'expected': expected, 'stateAfter': after})
    add('ready')
    add('not-armed', retained=False)
    def arm(b, s):
        stock = b['items'][0]['radar']['stock']; stock.update(week52Position=.97, kValue=95, dValue=85)
        stock['extendedIndicators']['rsi5'] = 85
    add('first-arm-never-ready', arm, retained=False)
    add('new-high-retains-start', lambda b, s: b['items'][0]['quote'].update(highPrice=103))
    add('pullback-under-boundary', lambda b, s: b['items'][0]['quote'].update(price=98.5001))
    add('pullback-exact-boundary', lambda b, s: b['items'][0]['quote'].update(price=98.5))
    add('live-kd-recovered', lambda b, s: b['items'][0]['radar']['stock'].update(kValue=80))
    add('completed-kd-missing', lambda b, s: b['items'][0]['quote']['marketData']['chart']['series']['daily']['k'].__setitem__(-1, None))
    def conflict(b, s):
        b['items'][0]['quote']['highPrice'] = 103
        b['items'][0]['radar']['actionPolicyVersion'] = 'EVIDENCE_GATE_V2'
        b['items'][0]['radar']['stock']['officialSma20Verification'] = {'status': 'CONFLICT', 'gateApplied': True}
    add('radar-veto-keeps-new-high', conflict)
    def dividend(b, s):
        s['basis_date'] = '2026-10-22'
        b['items'][0]['quote']['dividendHistory']['rows'].append({'exDividendDate': '2026-10-23', 'cashDividend': .2})
    add('cash-adjusted-watch-kd-blocked', dividend)
    add('unknown-watch-fingerprint', lambda b, s: s.update(policy_sha256='f' * 64))
    add('distribution-source-missing', lambda b, s: b['items'][0]['quote'].update(dividendHistory=None))
    add('missing-price-preserves-state', lambda b, s: b['items'][0]['quote'].update(price=None))
    add('missing-radar-keeps-valid-new-high', lambda b, s: (b['items'][0]['quote'].update(highPrice=103), b['items'][0].update(radar=None)))
    add('missing-radar-stock-keeps-new-high', lambda b, s: (b['items'][0]['quote'].update(highPrice=103), b['items'][0]['radar'].pop('stock')))
    def cross_day(b, s):
        b['asof'] = '2026-10-27T00:01:00+08:00'
        b['items'][0]['quote']['updatedAt'] = '2026-10-27T00:00:00+08:00'
        b['items'][0]['radar']['generatedAt'] = '2026-10-27T00:00:00+08:00'
    add('ny-session-taipei-next-day', cross_day)
    def early_close(b, s, closed=False):
        day = '2026-11-27'; b['trading_date'] = day; b['slot'] = '22:45'
        b['calendar'] = [{'date': row['date'], 'us': row['us']} for row in calendar if '2026-09-01' <= row['date'] <= day]
        b['session'] = {'open': '2026-11-27T14:30:00Z', 'close': '2026-11-27T18:00:00Z'}
        b['asof'] = '2026-11-27T18:01:00Z' if closed else '2026-11-27T17:59:00Z'
        q = b['items'][0]['quote']; q['tradingDate'] = day; q['updatedAt'] = '2026-11-27T17:58:00Z'
        r = b['items'][0]['radar']; r['generatedAt'] = '2026-11-27T17:58:00Z'; r['stock']['asOfDate'] = day
        expected = core.completed_calendar_window(day, 11, root, market='美股')
        chart = q['marketData']['chart']; chart.update(requestedStart=expected[0], requestedEnd=day); chart['series']['daily']['dates'] = expected
        s['basis_date'] = day
    add('early-close-before-1300', early_close)
    add('early-close-after-1300-rejected', lambda b, s: early_close(b, s, True))
    item = {'stock_code': 'SYNTH', 'qualification_path': 'RADAR',
            'quote': {'stockCode': 'SYNTH', 'market': '美股', 'currency': 'USD', 'closed': False,
                      'tradingDate': '2026-10-26', 'quoteStatus': 'LIVE', 'source': 'SYNTHETIC_FEED',
                      'asOf': '2026-10-26T13:45:00Z', 'last': 100, 'bid': 99.99, 'ask': 100.01},
            'radar': {'stockCode': 'SYNTH', 'market': '美股', 'asOfDate': '2026-10-26',
                      'dataComplete': True, 'action': 'BUY_CANDIDATE', 'candidateAction': 'BUY_CANDIDATE'},
            'target_price': 130, 'target_price_basis': 'SYNTHETIC_SCENARIO',
            'costs': {'commission_each_side_pct': .1, 'spread_each_side_pct': .05,
                      'fx_spread_each_side_pct': .05, 'tax_on_gain_pct': .5},
            'proposed_notional_twd': 100000, 'existing_company_cost_twd': 0, 'open_tactical_total_twd': 0,
            'session': copy.deepcopy(bundle['session'])}
    tactical_cases = []
    def tactical_add(name, edit=None):
        candidate = copy.deepcopy(item)
        if edit:
            edit(candidate)
        expected = tactical.evaluate(candidate, '2026-10-26', 100000000, tactical.load_policy(root))
        tactical_cases.append({'name': name, 'item': candidate, 'sessionDate': '2026-10-26',
                               'asOf': '2026-10-26T13:46:00Z', 'total': 100000000, 'expected': expected})
    tactical_add('radar-primary')
    tactical_add('limit-too-low', lambda i: i.update(proposed_limit_price=99.4))
    tactical_add('net-profit-under-15', lambda i: i.update(target_price=110))
    tactical_add('batch-cap', lambda i: i.update(proposed_notional_twd=250001))
    tactical_add('company-cap', lambda i: i.update(existing_company_cost_twd=700000))
    tactical_add('sleeve-cap', lambda i: i.update(open_tactical_total_twd=1990000))
    tactical_add('radar-wait', lambda i: i['radar'].update(action='WAIT'))
    tactical_add('fallback-fails', lambda i: i.update(qualification_path='FALLBACK'))
    tactical_add('fallback-passes', lambda i: i.update(qualification_path='FALLBACK', fallback_evidence={
        'global_top10_market_cap': True, 'us_tech_giant': True,
        'twelve_month_base_net_return_pct': 20, 'twelve_month_upside_to_downside': 2}))
    return {'source': 'AUTHORITATIVE_PYTHON_SYNTHETIC_CASES', 'model': minimal,
            'watchPolicyFingerprint': core.watch_policy_fingerprint(policy), 'core': cases, 'tactical': tactical_cases}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--srpp-root', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=Path(__file__).resolve().parents[1] / 'backend/src/test/resources/srpp/us-calculator-parity.json')
    args = parser.parse_args()
    result = generate(args.srpp_root.resolve())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False) + '\n')
    print('Generated', len(result['core']), 'core and', len(result['tactical']), 'tactical synthetic cases')
