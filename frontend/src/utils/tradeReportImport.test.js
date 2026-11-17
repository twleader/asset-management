import test from 'node:test'
import assert from 'node:assert/strict'
import {
  MAX_RAW_MIME_BYTES,
  canApplyTradeReportPreview,
  canPreviewTradeReport,
  conflictReasonLabel,
  createTradeReportImportDraft,
  feeProvenanceLabel,
  extractMessageIdHeader,
  getTradeReportConflictRows,
  importStatusLabel,
  invalidateTradeReportPreview,
  readEmlFile,
  validateEmlFile
} from './tradeReportImport.js'

function bytes(value) {
  return new TextEncoder().encode(value)
}

function fileFrom(raw, name = 'synthetic.eml', declaredSize = raw.byteLength) {
  const copy = raw.slice()
  return {
    name,
    size: declaredSize,
    arrayBuffer: async () => copy.buffer.slice(copy.byteOffset, copy.byteOffset + copy.byteLength)
  }
}

test('accepts an EML exactly at 512 KiB and encodes the original bytes losslessly', async () => {
  const raw = new Uint8Array(MAX_RAW_MIME_BYTES)
  const header = bytes('Message-ID: <synthetic-601@example.test>\r\nSubject: test\r\n\r\n')
  raw.set(header)
  raw[raw.length - 1] = 0xff

  const prepared = await readEmlFile(fileFrom(raw))
  assert.equal(prepared.byteLength, MAX_RAW_MIME_BYTES)
  assert.equal(prepared.suggestedMessageId, '<synthetic-601@example.test>')
  assert.equal(prepared.rawMimeBase64.length, 4 * Math.ceil(MAX_RAW_MIME_BYTES / 3))
  assert.deepEqual(new Uint8Array(Buffer.from(prepared.rawMimeBase64, 'base64')), raw)
})

test('rejects non-EML, empty, and oversized files before reading bytes', async () => {
  assert.equal(validateEmlFile({ name: 'report.html', size: 12 }).reason, 'extension')
  assert.equal(validateEmlFile({ name: 'empty.eml', size: 0 }).reason, 'empty')

  let readCalled = false
  const oversized = {
    name: 'too-large.eml',
    size: MAX_RAW_MIME_BYTES + 1,
    arrayBuffer: async () => { readCalled = true; return new ArrayBuffer(0) }
  }
  await assert.rejects(readEmlFile(oversized), /512 KiB/)
  assert.equal(readCalled, false)
})

test('rechecks the actual bytes read instead of trusting File.size metadata', async () => {
  const oversizedBytes = new Uint8Array(MAX_RAW_MIME_BYTES + 1)
  await assert.rejects(readEmlFile(fileFrom(oversizedBytes, 'synthetic.eml', 1)), /512 KiB/)
})

test('prefills one unfolded Message-ID header only and declines duplicate or ambiguous headers', () => {
  assert.equal(
    extractMessageIdHeader(bytes('From: sender@example.test\r\nMessage-ID: <synthetic-\r\n 601@example.test>\r\n\r\nbody')),
    '<synthetic- 601@example.test>'
  )
  assert.equal(extractMessageIdHeader(bytes('Message-ID: <one@example.test>\nMessage-ID: <two@example.test>\n\nbody')), '')
  assert.equal(extractMessageIdHeader(bytes('Subject: no id\r\n\r\nMessage-ID: <body@example.test>')), '')
})

test('requires a bounded message ID, both attestations, and a locally loaded file for preview', () => {
  const draft = createTradeReportImportDraft()
  draft.rawMimeBase64 = 'c3ludGhldGlj'
  draft.sourceMessageId = 'message@example.test'
  assert.equal(canPreviewTradeReport(draft), false)
  draft.attestedAuthenticReport = true
  draft.attestedOwnerAccount = true
  assert.equal(canPreviewTradeReport(draft), true)
  draft.sourceMessageId = 'x'.repeat(201)
  assert.equal(canPreviewTradeReport(draft), false)
  draft.sourceMessageId = 'x'.repeat(200)
  draft.reading = true
  assert.equal(canPreviewTradeReport(draft), false)
})

test('input invalidation discards stale preview and advances the revision', () => {
  const draft = createTradeReportImportDraft()
  draft.preview = { status: 'READY', previewHash: 'hash', rows: [{ status: 'NEW' }] }
  draft.previewing = true
  const previousRevision = draft.revision
  invalidateTradeReportPreview(draft)
  assert.equal(draft.preview, null)
  assert.equal(draft.previewing, false)
  assert.equal(draft.revision, previousRevision + 1)
})

test('ALREADY_IMPORTED still needs explicit apply; conflicts never expose an apply path', () => {
  const ready = { canApply: true, conflictRows: [], status: 'READY', previewHash: 'hash', rows: [{ status: 'NEW' }] }
  const duplicate = { canApply: true, conflictRows: [], status: 'ALREADY_IMPORTED', previewHash: 'hash-2', rows: [{ status: 'ALREADY_IMPORTED' }] }
  const conflict = { canApply: false, conflictRows: [{ status: 'CONFLICT', reason: 'POSSIBLE_DUPLICATE' }], status: 'CONFLICT', previewHash: 'hash-3', rows: [{ status: 'CONFLICT', reason: 'POSSIBLE_DUPLICATE' }] }
  assert.equal(canApplyTradeReportPreview(ready), true)
  assert.equal(canApplyTradeReportPreview({ status: 'READY', previewHash: 'h', rows: [{ status: 'NEW' }] }), false)
  assert.deepEqual(getTradeReportConflictRows({ rows: [{ status: 'CONFLICT' }] }), [])
  assert.equal(getTradeReportConflictRows(conflict), conflict.conflictRows)
  assert.equal(canApplyTradeReportPreview(duplicate), true)
  assert.equal(canApplyTradeReportPreview(conflict), false)
  assert.equal(getTradeReportConflictRows(conflict).length, 1)
  assert.equal(conflictReasonLabel('POSSIBLE_DUPLICATE').includes('請先核對'), true)
  assert.match(conflictReasonLabel('MESSAGE_CONTENT_CHANGED'), /相同郵件識別碼/)
})

test('status labels make duplicate and conflict outcomes explicit', () => {
  assert.match(importStatusLabel('ALREADY_IMPORTED'), /待確認來源紀錄/)
  assert.match(importStatusLabel('CONFLICT'), /未寫入/)
})

test('fee provenance is a text-only display projection with exact backend as-of value', () => {
  const asOf = '2026-11-17T03:04:05Z'
  assert.equal(
    feeProvenanceLabel({ status: 'DERIVED', asOf }),
    `依官方交割與訂單資料推導（截至 ${asOf}），非原始郵件提供`
  )
  assert.match(feeProvenanceLabel({ status: 'DERIVED' }), /截至 時間未知/)
  assert.match(feeProvenanceLabel({ status: 'RECORDED' }), /既有匯入記錄/)
  assert.equal(feeProvenanceLabel(null), '')
})

test('UNAVAILABLE fee provenance shows a safe reason without creating a transaction conflict', () => {
  const unavailable = { status: 'UNAVAILABLE', reason: 'SETTLEMENT_UNAVAILABLE' }
  const preview = {
    canApply: true,
    conflictRows: [],
    rows: [{ status: 'NEW', fee: null, transactionTax: null, feeProvenance: unavailable }]
  }
  assert.match(feeProvenanceLabel(unavailable), /最新官方交割資料不可用/)
  assert.equal(getTradeReportConflictRows(preview).length, 0)
  assert.equal(canApplyTradeReportPreview(preview), true)
  assert.match(feeProvenanceLabel({ status: 'UNAVAILABLE', reason: 'UNRECOGNIZED' }), /官方交割／訂單證據不足/)
})
