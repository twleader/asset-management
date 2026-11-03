#!/usr/bin/env python3
"""Validate historical US watch provenance and produce PRIVATE insert-only SQL; never connect to a database."""
from __future__ import annotations
import argparse
import copy
import datetime as dt
import hashlib
import json
import re
import sys
from decimal import Decimal
from pathlib import Path


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def load(path):
    def pairs(rows):
        out = {}
        for key, value in rows:
            require(key not in out, 'DUPLICATE_JSON_KEY')
            out[key] = value
        return out
    return json.loads(Path(path).read_text(), object_pairs_hook=pairs,
                      parse_constant=lambda value: (_ for _ in ()).throw(ValueError('NONFINITE_JSON')))


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def wire(value):
    if isinstance(value, dict):
        return {key: wire(child) for key, child in value.items()}
    if isinstance(value, list):
        return [wire(child) for child in value]
    if isinstance(value, (float, Decimal)):
        result = format(Decimal(str(value)), 'f').rstrip('0').rstrip('.') if '.' in format(Decimal(str(value)), 'f') else str(value)
        return '0' if Decimal(str(value)) == 0 else result
    return value


def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(',', ':'), allow_nan=False)


def validate(args):
    root = Path(args.srpp_root).resolve()
    sys.path.insert(0, str(root / 'scripts'))
    import core_sell_guard as core
    import record_report_inputs as reports
    from asset_reconciliation import validate_assets
    ledger, previous, anchor, result, history, facts, receipt, assets, owner = [load(getattr(args, key)) for key in (
        'ledger', 'previous_ledger', 'anchor_input', 'anchor_result', 'history_receipt',
        'report_facts', 'input_receipt', 'assets', 'owner_binding_receipt')]
    consumer = args.consumer
    require(consumer in ('Codex', 'Claude') and ledger.get('consumer') == previous.get('consumer') == consumer
            and ledger.get('decision') == previous.get('decision') == 'D-174'
            and isinstance(ledger.get('states'), dict) and isinstance(previous.get('states'), dict), 'WATCH_LEDGER_UNVERIFIED')
    require(anchor.get('market') == '美股' and history.get('market') == '美股' and history.get('consumer') == consumer
            and history.get('source_asof') == anchor.get('asof') and history.get('input_sha256') == core.fingerprint(anchor)
            and history.get('previous_ledger_sha256') == core.fingerprint(previous)
            and history.get('next_states_sha256') == core.fingerprint(ledger['states'])
            and history.get('trade_authorization') is False and history.get('maintenance_replay') is False, 'WATCH_HISTORY_ANCHOR_MISMATCH')
    guard = facts.get('core_sell_guard') or {}
    # Some historical reports saved the whole actual ledger under states_before.
    # Accept that exact archive, not a loosely extracted or newly empty wrapper.
    before_bound = guard.get('states_before') == previous['states'] or guard.get('states_before') == previous
    require(facts.get('assets') == assets and guard.get('status') == 'EVALUATED'
            and before_bound and guard.get('results') == result.get('results'), 'REPORT_FACTS_WATCH_BINDING_MISMATCH')
    require(receipt.get('status') == 'PASS' and receipt.get('consumer') == consumer
            and all(receipt.get(key) == facts.get(key) for key in ('trading_date', 'slot', 'cutoff_at'))
            and anchor.get('trading_date') == facts.get('trading_date') and anchor.get('slot') == facts.get('slot'), 'REPORT_RECEIPT_IDENTITY_MISMATCH')
    require(core.stamp(anchor['asof']) <= core.stamp(facts['cutoff_at']), 'REPORT_SOURCE_FROM_FUTURE')
    # The current authoritative normalizer projects the saved facts. Historical bundle
    # metadata is not relabelled as current; only the recomputed facts digest is compared.
    computed = reports.receipt(facts, consumer)
    require(computed['INPUT_SNAPSHOT_SHA256'] == receipt.get('INPUT_SNAPSHOT_SHA256'), 'REPORT_INPUT_FINGERPRINT_MISMATCH')
    l0 = validate_assets(assets)
    require(owner.get('source') == 'ASSET_SNAPSHOT_OWNER_READBACK' and owner.get('status') == 'ACTIVE'
            and isinstance(owner.get('ownerUserId'), int) and not isinstance(owner.get('ownerUserId'), bool)
            and owner['ownerUserId'] > 0 and owner.get('snapshotId') == l0['snapshot_id'], 'OWNER_SNAPSHOT_BINDING_UNVERIFIED')
    queried = dt.datetime.fromisoformat(owner['queriedAt'].replace('Z', '+00:00'))
    require(queried.tzinfo is not None, 'OWNER_SNAPSHOT_BINDING_UNVERIFIED')
    policy = json.loads((root / 'assumptions/model_inputs.json').read_text())['execution_policy']['core_sell_guard_d174']
    replay, after = core.screen(anchor, policy, previous['states'], root)
    require(all(result.get(key) == value for key, value in replay.items()) and after == ledger['states'], 'WATCH_REPLAY_MISMATCH')
    normalized = {code: core.normalize_watch_state(state, code, '美股', anchor['trading_date'], policy)
                  for code, state in ledger['states'].items()}
    require(all(re.fullmatch('[a-f0-9]{64}', state['evidence_sha256']) for state in normalized.values()), 'WATCH_EVIDENCE_HASH_UNVERIFIED')
    evidence = {'schemaVersion': 1, 'status': 'VERIFIED', 'scope': 'US_WATCH_MIGRATION_NO_TRADE',
                'validatorVersion': 'US_WATCH_MIGRATION_V1',
                'ownerUserId': owner['ownerUserId'], 'consumer': consumer, 'market': 'US',
                'snapshotId': l0['snapshot_id'], 'ledgerSha256': sha(args.ledger),
                'watchPolicySha256': core.watch_policy_fingerprint(policy),
                'originalLedgerPythonSha256': core.fingerprint(ledger),
                'previousLedgerPythonSha256': core.fingerprint(previous),
                'anchorInputPythonSha256': core.fingerprint(anchor),
                'inputSnapshotSha256': receipt['INPUT_SNAPSHOT_SHA256'],
                'sourceToolSha256': {name: sha(root / 'scripts' / name) for name in (
                    'core_sell_guard.py', 'execution_policy.py', 'record_report_inputs.py', 'asset_reconciliation.py')},
                'sourceFilesSha256': {key: sha(getattr(args, key)) for key in (
                    'ledger', 'previous_ledger', 'anchor_input', 'anchor_result', 'history_receipt',
                    'report_facts', 'input_receipt', 'assets', 'owner_binding_receipt')},
                'tradeAuthorization': False}
    return evidence, {code: wire(state) for code, state in normalized.items()}


def sql_quote(value):
    return "'" + str(value).replace("'", "''") + "'"


def registration_sql(evidence, states):
    owner, consumer = evidence['ownerUserId'], sql_quote(evidence['consumer'])
    identity = f'{owner},{consumer},\'US\''
    ledger, policy, body = map(sql_quote, (evidence['ledgerSha256'], evidence['watchPolicySha256'], canonical(evidence)))
    statements = [
        '-- PRIVATE: validated watch evidence; never commit this file to the public repository.',
        'BEGIN;',
        f"SELECT pg_advisory_xact_lock(hashtextextended('US/{owner}',542));",
        'DO $migration$ BEGIN',
        f"  IF NOT EXISTS (SELECT 1 FROM asset_snapshot s JOIN app_user u ON u.id=s.owner_user_id WHERE s.id={evidence['snapshotId']} AND s.owner_user_id={owner} AND u.status='ACTIVE') THEN RAISE EXCEPTION 'OWNER_SNAPSHOT_BINDING_UNVERIFIED'; END IF;",
        f"  IF EXISTS (SELECT 1 FROM srpp_us_watch_import WHERE owner_user_id={owner} AND consumer={consumer} AND market='US' AND (ledger_sha256<>{ledger} OR watch_policy_sha256<>{policy} OR evidence_jcs<>{body})) THEN RAISE EXCEPTION 'WATCH_IMPORT_CONFLICT'; END IF;",
        'END $migration$;',
        f'INSERT INTO srpp_us_watch_import(owner_user_id,consumer,market,ledger_sha256,watch_policy_sha256,evidence_jcs,imported_at) SELECT {identity},{ledger},{policy},{body},CURRENT_TIMESTAMP FROM asset_snapshot WHERE id={evidence["snapshotId"]} AND owner_user_id={owner} ON CONFLICT DO NOTHING;'
    ]
    for symbol, state in sorted(states.items()):
        text = canonical(state); digest = hashlib.sha256(text.encode()).hexdigest()
        statements.append(f'INSERT INTO srpp_us_watch_state(owner_user_id,consumer,market,symbol,version,state_jcs,state_sha256,updated_at) VALUES({identity},{sql_quote(symbol)},1,{sql_quote(text)},{sql_quote(digest)},CURRENT_TIMESTAMP) ON CONFLICT DO NOTHING;')
        delimiter = '$verify_' + digest + '$'
        while delimiter in text or delimiter in symbol:
            delimiter = delimiter[:-1] + '_next$'
        statements.append('DO ' + delimiter + ' BEGIN ' + f"IF NOT EXISTS(SELECT 1 FROM srpp_us_watch_state WHERE owner_user_id={owner} AND consumer={consumer} AND market='US' AND symbol={sql_quote(symbol)} AND state_jcs={sql_quote(text)} AND state_sha256={sql_quote(digest)}) THEN RAISE EXCEPTION 'WATCH_STATE_IMPORT_CONFLICT'; END IF; END " + delimiter + ';')
    statements.append('COMMIT;')
    return '\n'.join(statements) + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('srpp-root', 'ledger', 'previous-ledger', 'anchor-input', 'anchor-result', 'history-receipt',
                 'report-facts', 'input-receipt', 'assets', 'owner-binding-receipt', 'output', 'sql-output'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--consumer', choices=('Codex', 'Claude'), required=True)
    args = parser.parse_args()
    try:
        evidence, states = validate(args)
        args.output.parent.mkdir(parents=True, exist_ok=True); args.sql_output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + '\n')
        args.sql_output.write_text(registration_sql(evidence, states))
        print(json.dumps({'status': 'VERIFIED', 'consumer': args.consumer, 'stateCount': len(states),
                          'ledgerSha256': evidence['ledgerSha256'], 'sqlExecuted': False}))
    except (ValueError, KeyError, TypeError, OSError) as failure:
        print(json.dumps({'status': 'UNAVAILABLE', 'reason': str(failure), 'sqlExecuted': False}), file=sys.stderr)
        return 2
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
