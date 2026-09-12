import test from 'node:test'
import assert from 'node:assert/strict'

import { applyTradingRadarSsePriceUpdate } from './tradingRadarSsePriceUpdate.js'

function row(overrides = {}) {
  return {
    market: '台股',
    stockCode: '2330',
    price: 100,
    changePercent: 1,
    quoteStatus: 'PREVIOUS_CLOSE',
    priceUpdatedAt: 'old-time',
    action: 'HOLD',
    score: 73,
    evidence: { immutableForSse: true },
    ...overrides
  }
}

function livePayload(overrides = {}) {
  return {
    market: '台股',
    stockCode: '2330',
    tradingDate: '2026-09-12',
    quoteStatus: 'LIVE',
    price: 120,
    changePercent: 2.5,
    updatedAt: 'new-time',
    ...overrides
  }
}

test('Task426 accepted same-date LIVE atomically patches price, change, status and freshness only', () => {
  const stock = row()
  const changed = applyTradingRadarSsePriceUpdate([stock], livePayload({
    previousClose: 80,
    stockName: '不可覆寫',
    source: 'IGNORE_ME'
  }), '2026-09-12')

  assert.equal(changed, true)
  assert.deepEqual(stock, row({
    price: 120,
    changePercent: 2.5,
    quoteStatus: 'LIVE',
    priceUpdatedAt: 'new-time'
  }))
})

test('Task426 missing status, off-date LIVE, and terminal downgrade leave the row untouched', () => {
  for (const payload of [
    livePayload({ quoteStatus: undefined }),
    livePayload({ tradingDate: '2026-09-11' }),
    livePayload()
  ]) {
    const stock = payload.quoteStatus === 'LIVE' && payload.tradingDate === '2026-09-12'
      ? row({ quoteStatus: 'CLOSE_PENDING' })
      : row()
    const before = structuredClone(stock)

    assert.equal(applyTradingRadarSsePriceUpdate([stock], payload, '2026-09-12'), false)
    assert.deepEqual(stock, before)
  }
})

test('Task426 canonical changePercent wins; changePct is only used when canonical is absent', () => {
  const canonical = row()
  assert.equal(applyTradingRadarSsePriceUpdate([canonical], livePayload({ changePercent: 2.5, changePct: 99 }), '2026-09-12'), true)
  assert.equal(canonical.changePercent, 2.5)

  const fallback = row()
  const payload = livePayload({ changePct: -1.25 })
  delete payload.changePercent
  assert.equal(applyTradingRadarSsePriceUpdate([fallback], payload, '2026-09-12'), true)
  assert.equal(fallback.changePercent, -1.25)
})

test('Task426 invalid price tuple never advances quote status or freshness', () => {
  for (const payload of [
    livePayload({ price: 0 }),
    livePayload({ changePercent: Infinity }),
    // Canonical null is invalid and must not silently fall back to legacy changePct.
    livePayload({ changePercent: null, changePct: 9 }),
    (() => {
      const value = livePayload({ changePct: null })
      delete value.changePercent
      return value
    })(),
    livePayload({ price: '  ' }),
    livePayload({ changePercent: '' }),
    (() => {
      const value = livePayload()
      delete value.changePercent
      return value
    })()
  ]) {
    const stock = row()
    const before = structuredClone(stock)

    assert.equal(applyTradingRadarSsePriceUpdate([stock], payload, '2026-09-12'), false)
    assert.deepEqual(stock, before)
  }
})
