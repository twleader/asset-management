import test from 'node:test'
import assert from 'node:assert/strict'
import { backupDateFolder, backupLocation } from './backupPath.js'

test('active backup path uses the date in the filename', () => {
  const row = { folder: 'daily', filename: 'asset_daily_tw_20261001_153000.dump' }
  assert.equal(backupDateFolder(row), '2026-10-01')
  assert.equal(backupLocation(row), 'asset-management-backup/2026-10-01/')
  assert.equal(backupDateFolder({ folder: 'manual', filename: 'asset_auto-pre-restore_20261002_013000_abcd.dump' }), '2026-10-02')
})

test('older, mismatched, and malformed records cannot be restored', () => {
  for (const row of [
    { folder: 'daily', filename: 'asset_daily_tw_20260923_153000.dump' },
    { folder: 'manual', filename: 'asset_daily_tw_20261001_153000.dump' },
    { folder: 'daily', filename: 'asset_daily_tw_20261301_153000.dump' },
    { folder: 'daily', filename: 'asset_daily_tw_20261001_253000.dump' }
  ]) {
    assert.equal(backupDateFolder(row), null)
    assert.equal(backupLocation(row), '舊備份（未搬移）')
  }
})
