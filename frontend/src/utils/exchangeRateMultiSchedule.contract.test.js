import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const view = readFileSync(new URL('../views/ExchangeRateView.vue', import.meta.url), 'utf8')

test('exchange-rate schedule supports add/remove, validates times payload, and keeps legacy fallback narrow', () => {
  for (const token of ['scheduleTimes', 'addScheduleTime', 'removeScheduleTime', 'scheduleTimes.length === 1', 'times.push({ runHour:', 'seenTimes.has(key)', 'schedule.enabled && !times.some(time => time.enabled)']) assert.ok(view.includes(token))
  assert.ok(view.includes('if (Array.isArray(s.times))'))
  assert.ok(view.includes('if (s.times.length === 0) throw new Error'))
  assert.ok(view.includes('} else {\n    rebuildScheduleTimes([{ runHour: s.runHour'))
  assert.equal(view.includes('scheduleTime.value'), false)
})
