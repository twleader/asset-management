import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const view = readFileSync(new URL('../views/ExchangeRateView.vue', import.meta.url), 'utf8')

test('exchange-rate schedules are independent rows with an editable per-row range', () => {
  for (const token of [
    'v-for="(item, index) in schedules"',
    'openNewSchedule',
    'openEditSchedule(item)',
    'handleDeleteSchedule(item)',
    'handleRunNow(item)',
    'formatExportRangeMonths(item.rangeMonths)',
    'getExportRangeMonthOptions(scheduleDialog.draft.rangeMonths)',
    'includeRangeMonths: true',
    'requireRangeMonths: true'
  ]) assert.ok(view.includes(token), `missing ${token}`)
  assert.equal(view.includes('scheduleTimes'), false)
  assert.equal(view.includes('addScheduleTime'), false)
})
