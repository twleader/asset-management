import test from 'node:test'
import assert from 'node:assert/strict'

import {
  applyEnrichedQuote,
  isAcceptedTodayQuote,
  marketToday,
  mergeSseQuote
} from './displayQuote.js'

test('marketToday uses each market timezone', () => {
  const instant = new Date('2026-08-07T16:30:00Z')
  assert.equal(marketToday('台股', instant), '2026-08-08')
  assert.equal(marketToday('美股', instant), '2026-08-07')
})

test('pending close cannot be overwritten by an old live SSE tick', () => {
  const pending = { market: '台股', tradingDate: '2026-08-07', quoteStatus: 'CLOSE_PENDING' }
  const live = { market: '台股', tradingDate: '2026-08-07', quoteStatus: 'LIVE', price: 100 }
  assert.equal(mergeSseQuote(pending, live, '2026-08-07'), pending)
})

test('verified close can replace pending and must match today', () => {
  const pending = { market: '台股', tradingDate: '2026-08-07', quoteStatus: 'CLOSE_PENDING' }
  const verified = { market: '台股', tradingDate: '2026-08-07', quoteStatus: 'VERIFIED_CLOSE', price: 101 }
  assert.equal(mergeSseQuote(pending, verified, '2026-08-07'), verified)
  assert.equal(isAcceptedTodayQuote(verified, '2026-08-07'), true)
})

test('pending enriched quote clears stale price fields', () => {
  const row = { latestPrice: 99, priceChange: 1, priceChangePct: 1.02 }
  applyEnrichedQuote(row, { quoteStatus: 'CLOSE_PENDING', source: null })
  assert.deepEqual(row, {
    latestPrice: null,
    priceChange: null,
    priceChangePct: null,
    quoteStatus: 'CLOSE_PENDING',
    priceSource: null
  })
})
