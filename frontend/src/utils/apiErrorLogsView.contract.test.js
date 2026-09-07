import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const view = readFileSync(new URL('../views/ApiErrorLogsView.vue', import.meta.url), 'utf8')

test('API error-log view keeps the three selects, source contract and lazy text-only stacktrace detail', () => {
  for (const token of ['aria-label="來源"', 'label="全部" value="ALL"', 'label="開放 API" value="OPEN_API"', 'label="富邦證 API" value="FUBON_API"', 'value="NEWEST"', 'value="OLDEST"', 'type="expand"']) assert.ok(view.includes(token))
  assert.equal(view.includes('el-radio-group'), false)
  assert.equal(view.includes('el-radio-button'), false)
  assert.ok(view.includes('grid-template-columns:160px minmax(0, 1fr) minmax(0, 2fr)'))
  assert.ok(view.includes('gap:12px'))
  assert.ok(view.includes('.filters>.el-select{width:100%}'))
  assert.ok(view.includes('const operationLabel = item => `${item.apiName} — ${item.apiUrl}`'))
  assert.ok(view.includes(':label="operationLabel(item)"'))
  assert.ok(view.includes('async function changeSource () { operationKey.value = null; await loadOperations(); await load() }'))
  assert.ok(view.includes('bffApi.apiErrorLogs.detail(row.id)'))
  assert.ok(view.includes('Object.hasOwn(details.value, row.id)'))
  assert.equal(view.includes('v-html'), false)
})

test('API error-log view adds an HTTP status column between source and apiName with a dash fallback for null (Task 421／Requirement 143)', () => {
  assert.ok(view.includes('prop="httpStatus" label="HTTP 狀態碼" width="120"'))
  assert.ok(view.includes("{{ row.httpStatus ?? '—' }}"))
  const sourceIndex = view.indexOf('prop="source"')
  const httpStatusIndex = view.indexOf('prop="httpStatus"')
  const apiNameIndex = view.indexOf('prop="apiName"')
  assert.ok(sourceIndex >= 0)
  assert.ok(httpStatusIndex > sourceIndex)
  assert.ok(apiNameIndex > httpStatusIndex)
})
