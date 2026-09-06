import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const view = readFileSync(new URL('../views/ApiErrorLogsView.vue', import.meta.url), 'utf8')

test('API error-log view keeps the fixed controls and lazy text-only stacktrace detail', () => {
  for (const token of ['label="ALL"', 'label="OPEN_API"', 'label="FUBON_API"', 'value="NEWEST"', 'value="OLDEST"', 'type="expand"']) assert.ok(view.includes(token))
  assert.ok(view.includes('bffApi.apiErrorLogs.detail(row.id)'))
  assert.ok(view.includes('Object.hasOwn(details.value, row.id)'))
  assert.equal(view.includes('v-html'), false)
})
