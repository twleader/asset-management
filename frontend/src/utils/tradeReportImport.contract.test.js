import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'

const source = async relative => readFile(fileURLToPath(new URL(relative, import.meta.url)), 'utf8')

test('import dialog renders only normalized facts and never executes or fetches email HTML', async () => {
  const dialog = await source('../components/TradeReportImportDialog.vue')
  assert.doesNotMatch(dialog, /\bv-html\b/i)
  assert.doesNotMatch(dialog, /\bfetch\s*\(/i)
  assert.doesNotMatch(dialog, /XMLHttpRequest|EventSource/i)
  assert.match(dialog, /bffApi\.transaction\.previewTradeReportImport/)
  assert.match(dialog, /bffApi\.transaction\.applyTradeReportImport/)
  assert.match(dialog, /displayFeeOrTax\(row\.fee\)/)
  assert.match(dialog, /displayFeeOrTax\(row\.transactionTax\)/)
  assert.match(dialog, /feeProvenanceLabel\(row\.feeProvenance\)/)
  assert.match(dialog, /row\.feeProvenance\.status === 'UNAVAILABLE'/)
  assert.match(dialog, /未知（原始回報未提供）/)
  assert.match(dialog, /ASSERTED_BY_AUTHENTICATED_IMPORTER/)
  assert.match(dialog, /previewController\?\.abort\(\)/)
  assert.match(dialog, /sequence !== previewRequestSequence \|\| !props\.modelValue/)
  assert.match(dialog, /if \(!canApply\.value \|\| !props\.modelValue \|\| draft\.confirming \|\| draft\.applying/)
})

test('API calls use the page BFF, JSON, and the exact import-only marker header', async () => {
  const api = await source('../api/index.js')
  assert.match(api, /previewTradeReportImport:[\s\S]*?\/bff\/transaction\/report-import\/preview/)
  assert.match(api, /applyTradeReportImport:[\s\S]*?\/bff\/transaction\/report-import\/apply/)
  const importer = api.slice(api.indexOf('previewTradeReportImport:'), api.indexOf('// Task 251'))
  assert.equal((importer.match(/'X-Trade-Report-Import': '1'/g) || []).length, 2)
  assert.equal((importer.match(/'Content-Type': 'application\/json'/g) || []).length, 2)
})

test('transaction page owns an independent dialog and refreshes the ledger from the success event', async () => {
  const view = await source('../views/TransactionView.vue')
  assert.match(view, /TradeReportImportDialog v-model="tradeReportImportVisible" @applied="handleTradeReportImportApplied"/)
  assert.match(view, /async function handleTradeReportImportApplied\(\)[\s\S]*?await load\(\)/)
  assert.doesNotMatch(view, /handleTradeReportImportApplied[\s\S]{0,500}resetForm\(\)/)
})

 test('conflict filtering and apply eligibility come only from the BFF view', async () => {
  const utility = await source('./tradeReportImport.js')
  const projection = utility.slice(utility.indexOf('export function getTradeReportConflictRows'), utility.indexOf('export function importStatusLabel'))
  assert.doesNotMatch(projection, /\.filter\(|preview\.rows|preview\.status/)
  assert.match(projection, /preview\?\.canApply === true/)
  assert.match(projection, /preview\.conflictRows/)
})

test('fee provenance formatter does not determine transaction conflicts or apply eligibility', async () => {
  const utility = await source('./tradeReportImport.js')
  const projection = utility.slice(utility.indexOf('export function feeProvenanceLabel'), utility.indexOf('export function conflictReasonLabel'))
  assert.match(projection, /provenance\.status === 'DERIVED'/)
  assert.match(projection, /provenance\.status === 'UNAVAILABLE'/)
  assert.match(projection, /provenance\.status === 'RECORDED'/)
  assert.doesNotMatch(projection, /canApplyTradeReportPreview|getTradeReportConflictRows|\.filter\(/)
})
