import test from 'node:test'
import assert from 'node:assert/strict'
import { comparisonLabel, intradayLatestDate, normalizeIntradaySession } from './intradaySession.js'

const valid = () => ({
  tradingDate: '2026-08-18', ticks: [{ time: '2026-08-18T09:00:00', price: 49.48 }],
  sessionReferencePrice: 50.55, sessionReferenceDate: '2026-08-18', sessionReferenceSource: 'TWSE_MIS_Y',
  comparisonPrice: 50.55, comparisonKind: 'EX_RIGHTS_REFERENCE', comparisonSource: 'TWSE_MIS_Y',
  lastPrice: 49.48, change: -1.07, changePercent: -2.116716
})

test('session object keeps a complete verified comparison tuple and every label is explicit', () => {
  assert.equal(comparisonLabel('PREVIOUS_CLOSE'), '昨收')
  assert.equal(comparisonLabel('EX_RIGHTS_REFERENCE'), '除權息參考價')
  assert.equal(comparisonLabel('SESSION_REFERENCE'), '參考價')
  assert.equal(comparisonLabel('UNAVAILABLE'), '比較基準')
  const session = normalizeIntradaySession(valid())
  assert.equal(session.ticks.length, 1)
  assert.equal(session.comparisonPrice, 50.55)
  assert.equal(session.comparisonSource, 'TWSE_MIS_Y')
  assert.equal(session.sessionReferenceDate, '2026-08-18')
  assert.equal(session.changePercent, -2.116716)
})

test('incomplete or inconsistent comparison tuple fails closed without inventing a price change', () => {
  for (const partial of [
    { ...valid(), comparisonSource: null },
    { ...valid(), comparisonKind: 'UNAVAILABLE' },
    { ...valid(), change: -1.00 },
    { ...valid(), changePercent: -2 },
    { ...valid(), comparisonSource: 'YAHOO' },
    { ...valid(), comparisonKind: 'SESSION_REFERENCE', comparisonSource: 'STOCK_PRICE_HISTORY' },
    { ...valid(), comparisonPrice: null },
    { ...valid(), lastPrice: null },
    { ...valid(), change: null },
    { ...valid(), changePercent: null }
  ]) {
    const session = normalizeIntradaySession(partial)
    assert.equal(session.comparisonPrice, null)
    assert.equal(session.comparisonKind, 'UNAVAILABLE')
    assert.equal(session.comparisonSource, null)
    assert.equal(session.sessionReferencePrice, null)
    assert.equal(session.sessionReferenceDate, null)
    assert.equal(session.sessionReferenceSource, null)
    assert.equal(session.lastPrice, null)
    assert.equal(session.change, null)
    assert.equal(session.changePercent, null)
  }
})

test('unavailable session keeps chart date fallback and never claims a reference', () => {
  const session = normalizeIntradaySession({
    tradingDate: '2026-08-19', ticks: [], comparisonPrice: null, comparisonKind: 'UNAVAILABLE',
    comparisonSource: null, lastPrice: null, change: null, changePercent: null
  })
  assert.equal(session.comparisonPrice, null)
  assert.equal(session.change, null)
  assert.equal(session.changePercent, null)
  assert.equal(intradayLatestDate(session, ['2026-08-17']), '2026-08-17')
  assert.equal(intradayLatestDate(session, []), null)
})

test('invalid or missing ISO calendar dates fail every comparison field closed', () => {
  for (const partial of [
    { ...valid(), tradingDate: '2026-02-30' },
    { ...valid(), tradingDate: null },
    { ...valid(), sessionReferenceDate: '2026-02-30' },
    { ...valid(), sessionReferenceDate: null },
    { ...valid(), sessionReferenceDate: '2026-08-17' },
    { ...valid(), sessionReferenceSource: null },
    { ...valid(), sessionReferencePrice: null }
  ]) {
    const session = normalizeIntradaySession(partial)
    assert.equal(session.comparisonPrice, null)
    assert.equal(session.comparisonKind, 'UNAVAILABLE')
    assert.equal(session.comparisonSource, null)
    assert.equal(session.lastPrice, null)
    assert.equal(session.change, null)
    assert.equal(session.changePercent, null)
  }
})

test('raw-history comparison is only valid with no raw session-reference metadata', () => {
  const raw = {
    tradingDate: '2026-08-18', ticks: [{ time: '2026-08-18T09:00:00', price: 500 }],
    sessionReferencePrice: null, sessionReferenceDate: null, sessionReferenceSource: null,
    comparisonPrice: 490, comparisonKind: 'PREVIOUS_CLOSE', comparisonSource: 'STOCK_PRICE_HISTORY',
    lastPrice: 500, change: 10, changePercent: 2.0408163265306123
  }
  assert.equal(normalizeIntradaySession(raw).comparisonPrice, 490)
  assert.equal(normalizeIntradaySession({ ...raw, sessionReferenceDate: '2026-02-30' }).comparisonPrice, null)
})

test('legacy bare-array wire shape fails closed instead of being treated as ticks', () => {
  const session = normalizeIntradaySession([valid()])
  assert.equal(session.ticks.length, 0)
  assert.equal(session.comparisonPrice, null)
  assert.equal(session.comparisonKind, 'UNAVAILABLE')
  assert.equal(session.change, null)
})
