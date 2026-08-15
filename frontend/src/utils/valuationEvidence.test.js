import test from 'node:test'
import assert from 'node:assert/strict'

import { LEGACY_MESSAGE, projectValuationEvidence } from './valuationEvidence.js'

function evidenceGroups(components) {
  return { evidenceGroups: { VALUATION: { components } } }
}

test('three valuation components keep their own provider, dates, URLs, status and reason', () => {
  const fundamental = {
    valuationProvider: 'GENERIC_MUST_NOT_BE_USED',
    valuationAsOf: '1999-01-01',
    valuationSourceUrls: ['https://generic.invalid'],
    peEvidence: {
      value: 18.5, percentile: 22, provider: 'TWSE',
      sourceUrls: ['https://example.test/pe'], availableAt: '2026-08-08T01:00:00Z',
      asOf: '2026-08-07', loss: false
    },
    pbEvidence: {
      value: 4.2, percentile: 80, provider: 'WANTGOO',
      sourceUrls: ['https://example.test/pb'], availableAt: '2026-08-08T02:00:00Z',
      asOf: '2026-08-06', loss: false
    },
    dividendYieldEvidence: {
      value: 2.1, percentile: 35, provider: 'FINMIND',
      sourceUrls: ['https://example.test/yield'], availableAt: '2026-08-08T03:00:00Z',
      asOf: '2026-08-05', loss: false
    }
  }
  const radarEvidence = evidenceGroups([
    { name: 'pe', applicability: 'AVAILABLE', missingReason: null },
    { name: 'pb', applicability: 'STALE', missingReason: 'PB observation 已過期' },
    { name: 'dividend_yield', applicability: 'MISSING', missingReason: '殖利率歷史不足 250 筆' }
  ])

  const [pe, pb, dividendYield] = projectValuationEvidence(fundamental, radarEvidence)
  assert.deepEqual([pe.key, pb.key, dividendYield.key], ['pe', 'pb', 'dividend_yield'])
  assert.deepEqual([pe.provider, pb.provider, dividendYield.provider], ['TWSE', 'WANTGOO', 'FINMIND'])
  assert.deepEqual([pe.asOf, pb.asOf, dividendYield.asOf], ['2026-08-07', '2026-08-06', '2026-08-05'])
  assert.deepEqual([pe.status, pb.status, dividendYield.status], ['AVAILABLE', 'STALE', 'MISSING'])
  assert.deepEqual(pe.sourceUrls, ['https://example.test/pe'])
  assert.equal(pb.missingReason, 'PB observation 已過期')
  assert.equal(dividendYield.missingReason, '殖利率歷史不足 250 筆')
  assert.ok([pe, pb, dividendYield].every(item => item.provider !== 'GENERIC_MUST_NOT_BE_USED'))
})

test('PE loss keeps the loss observation provenance and never inherits generic provenance', () => {
  const [pe] = projectValuationEvidence({
    valuationProvider: 'GENERIC_MUST_NOT_BE_USED',
    peEvidence: {
      value: null, percentile: null, provider: 'SEC_EDGAR',
      sourceUrls: ['https://example.test/loss'], availableAt: '2026-08-08T04:00:00Z',
      asOf: '2026-06-30', loss: true
    },
    pbEvidence: null,
    dividendYieldEvidence: null
  }, evidenceGroups([
    { name: 'pe', applicability: 'AVAILABLE', missingReason: null },
    { name: 'pb', applicability: 'MISSING', missingReason: 'PB 缺漏' },
    { name: 'dividend_yield', applicability: 'MISSING', missingReason: '殖利率缺漏' }
  ]))

  assert.equal(pe.loss, true)
  assert.equal(pe.provider, 'SEC_EDGAR')
  assert.equal(pe.asOf, '2026-06-30')
  assert.deepEqual(pe.sourceUrls, ['https://example.test/loss'])
})

test('not-applicable valuation still projects three named backend components outside coverage', () => {
  const projected = projectValuationEvidence({
    applicable: false,
    peEvidence: null,
    pbEvidence: null,
    dividendYieldEvidence: null
  }, evidenceGroups([
    { name: 'pe', applicability: 'NOT_APPLICABLE', missingReason: null },
    { name: 'pb', applicability: 'NOT_APPLICABLE', missingReason: null },
    { name: 'dividend_yield', applicability: 'NOT_APPLICABLE', missingReason: null }
  ]))

  assert.deepEqual(projected.map(item => item.key), ['pe', 'pb', 'dividend_yield'])
  assert.ok(projected.every(item => item.status === 'NOT_APPLICABLE'))
  assert.ok(projected.every(item => item.legacy === false))
  assert.ok(projected.every(item => item.value === null && item.provider === null))
})

test('legacy snapshot is explicit and never turns missing values into zero or AVAILABLE', () => {
  const projected = projectValuationEvidence({
    valuationProvider: 'GENERIC_MUST_NOT_BE_USED',
    valuationAsOf: '2026-08-07',
    peValue: 0,
    pePercentile: 0
  }, {})

  assert.ok(projected.every(item => item.legacy))
  assert.ok(projected.every(item => item.status === 'LEGACY'))
  assert.ok(projected.every(item => item.statusLabel === LEGACY_MESSAGE))
  assert.ok(projected.every(item => item.value === null && item.percentile === null))
  assert.ok(projected.every(item => item.provider === null && item.sourceUrls.length === 0))
})

test('SEC_DERIVED 的推導標記以純文字揭露，不得混進可點擊的來源連結', () => {
  const [pe] = projectValuationEvidence({
    peEvidence: {
      value: 31.2, percentile: 44, provider: 'SEC_DERIVED',
      sourceUrls: [
        'https://data.sec.gov/api/xbrl/companyfacts/CIK0001018724.json',
        'derived://sec-edgar-companyfacts/us-valuation'
      ],
      availableAt: '2026-08-14T20:00:00Z', asOf: '2026-08-14', loss: false
    },
    pbEvidence: null,
    dividendYieldEvidence: null
  }, evidenceGroups([
    { name: 'pe', applicability: 'AVAILABLE', missingReason: null },
    { name: 'pb', applicability: 'MISSING', missingReason: 'PB 缺漏' },
    { name: 'dividend_yield', applicability: 'MISSING', missingReason: '殖利率缺漏' }
  ]))

  // 原始清單保留完整內容（後端契約不變）
  assert.equal(pe.sourceUrls.length, 2)
  // 只有 http(s) 才可導覽；derived:// 標記若被 render 成 <a href> 會是一個看起來像官方來源的死連結
  assert.deepEqual(pe.sourceLinks, ['https://data.sec.gov/api/xbrl/companyfacts/CIK0001018724.json'])
  assert.deepEqual(pe.sourceNotes, ['derived://sec-edgar-companyfacts/us-valuation'])
  assert.ok(pe.sourceLinks.every(url => /^https?:\/\//i.test(url)))
})
