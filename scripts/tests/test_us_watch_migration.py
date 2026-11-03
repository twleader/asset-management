#!/usr/bin/env python3
"""Synthetic provenance tests; authoritative SRPP source is explicit and no private input is read."""
import argparse
import copy
import datetime as dt
import importlib.util
import json
import sys
import tempfile
import types
import unittest
from pathlib import Path

PARSER = argparse.ArgumentParser()
PARSER.add_argument('--srpp-root', type=Path, required=True)
OPTIONS, TEST_ARGS = PARSER.parse_known_args()
ROOT = OPTIONS.srpp_root.resolve()
sys.path.insert(0, str(ROOT / 'scripts'))
import core_sell_guard as core
import record_report_inputs as reports
from test_us_core_sell_guard import UsCoreSellGuardTests

SPEC = importlib.util.spec_from_file_location('migration', Path(__file__).resolve().parents[1] / 'validate-us-watch-migration.py')
migration = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(migration)


class MigrationTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.path = Path(self.folder.name)
        anchor, _, policy = UsCoreSellGuardTests().fixture()
        previous = {'decision': 'D-174', 'consumer': 'Codex', 'states': {}}
        result, states = core.screen(anchor, policy, {}, ROOT)
        result['watch_persisted'] = True
        ledger = {**previous, 'states': states}
        assets = {'generatedAt': anchor['asof'], 'targetPriceComplete': True,
                  'snapshot': {'id': 17, 'snapshotDate': '2026-10-26', 'deposits': [], 'funds': [], 'stocks': [],
                               'totalDeposit': 0, 'totalFundValue': 0, 'totalStockValue': 0, 'totalAssets': 0},
                  'liveAssets': {'snapshotId': 17, 'snapshotDate': '2026-10-26', 'exchangeRate': 30,
                                 'stocks': [], 'totalDeposit': 0, 'totalFundValue': 0, 'liveStockValue': 0, 'liveTotalAssets': 0}}
        facts = {'trading_date': anchor['trading_date'], 'slot': anchor['slot'], 'cutoff_at': anchor['asof'],
                 'assets': assets, 'quotes': [], 'radar': {}, 'funding': {'status': 'UNAVAILABLE'},
                 'data_quality': {}, 'core_sell_guard': {'status': 'EVALUATED', 'states_before': {}, 'results': result['results']},
                 'api_calculations': {'input_facts': {'scope': 'SYNTHETIC'}}}
        receipt = reports.receipt(facts, 'Codex')
        history = {'market': '美股', 'consumer': 'Codex', 'source_asof': anchor['asof'],
                   'input_sha256': core.fingerprint(anchor), 'previous_ledger_sha256': core.fingerprint(previous),
                   'next_states_sha256': core.fingerprint(states), 'trade_authorization': False, 'maintenance_replay': False}
        owner = {'source': 'ASSET_SNAPSHOT_OWNER_READBACK', 'status': 'ACTIVE', 'ownerUserId': 7,
                 'snapshotId': 17, 'queriedAt': '2026-11-03T18:00:00Z'}
        values = dict(ledger=ledger, previous_ledger=previous, anchor_input=anchor, anchor_result=result,
                      history_receipt=history, report_facts=facts, input_receipt=receipt, assets=assets, owner_binding_receipt=owner)
        self.args = types.SimpleNamespace(srpp_root=ROOT, consumer='Codex')
        for name, value in values.items():
            path = self.path / (name + '.json'); path.write_text(json.dumps(value)); setattr(self.args, name, path)

    def mutate(self, field, fn):
        path = getattr(self.args, field); value = json.loads(path.read_text()); fn(value); path.write_text(json.dumps(value))

    def test_complete_empty_ledger_has_provenance_and_private_sql_guard(self):
        evidence, states = migration.validate(self.args)
        self.assertEqual(states, {})
        self.assertEqual(evidence['consumer'], 'Codex')
        sql = migration.registration_sql(evidence, states)
        self.assertIn('s.id=17 AND s.owner_user_id=7', sql)
        self.assertIn("u.status='ACTIVE'", sql)
        self.assertIn("hashtextextended('US/7',542)", sql)
        self.assertNotIn('UPDATE ', sql)
        self.assertNotIn('DELETE ', sql)

    def test_missing_ledger_cannot_initialize_empty(self):
        self.args.ledger.unlink()
        with self.assertRaises(OSError): migration.validate(self.args)

    def test_wrong_consumer_or_unknown_owner_proof_rejected(self):
        self.mutate('owner_binding_receipt', lambda v: v.update(source='OPERATOR_PARAMETER', ownerUserId=8))
        with self.assertRaisesRegex(ValueError, 'OWNER_SNAPSHOT_BINDING'): migration.validate(self.args)

    def test_wrong_snapshot_owner_binding_rejected(self):
        self.mutate('owner_binding_receipt', lambda v: v.update(snapshotId=18))
        with self.assertRaisesRegex(ValueError, 'OWNER_SNAPSHOT_BINDING'): migration.validate(self.args)

    def test_state_or_anchor_hash_change_rejected(self):
        self.mutate('history_receipt', lambda v: v.update(input_sha256='a' * 64))
        with self.assertRaisesRegex(ValueError, 'WATCH_HISTORY_ANCHOR_MISMATCH'): migration.validate(self.args)

    def test_facts_tampering_cannot_keep_original_input_receipt(self):
        self.mutate('report_facts', lambda v: v['data_quality'].update(scope='TAMPERED'))
        with self.assertRaisesRegex(ValueError, 'REPORT_INPUT_FINGERPRINT_MISMATCH'): migration.validate(self.args)

    def test_exact_archived_wrapper_supported_but_other_wrapper_rejected(self):
        previous = json.loads(self.args.previous_ledger.read_text())
        self.mutate('report_facts', lambda v: v['core_sell_guard'].update(states_before=previous))
        facts = json.loads(self.args.report_facts.read_text())
        self.args.input_receipt.write_text(json.dumps(reports.receipt(facts, 'Codex')))
        evidence, _ = migration.validate(self.args)
        self.assertEqual(evidence['status'], 'VERIFIED')
        self.mutate('report_facts', lambda v: v['core_sell_guard']['states_before'].update(consumer='Claude'))
        with self.assertRaisesRegex(ValueError, 'REPORT_FACTS_WATCH_BINDING'): migration.validate(self.args)


if __name__ == '__main__':
    unittest.main(argv=[sys.argv[0], *TEST_ARGS])
